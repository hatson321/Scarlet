package net.sybyline.scarlet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import net.sybyline.scarlet.ext.AvatarSearch.VrcxAvatar;

/** Optional, profile-owned public metadata cache. Never stores a query, provider URL or account identity. */
public final class ScarletAvatarProviderCache implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/AvatarProviderCache");
    private static final String FORMAT = "scarlet-avatar-provider-cache";
    private static final int VERSION = 1;
    private static final byte[] RETRY = new byte[0];
    private final Object stateLock = new Object(), ioLock = new Object();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<String, Flight> flights = new HashMap<>();
    private final Path file;
    private final LongSupplier clock;
    private final int maximumEntries, maximumBytes, maximumEntryBytes, maximumResults, maximumFlights, maximumFileBytes;
    private final long positiveTtlMillis, emptyTtlMillis;
    private long generation, retainedBytes, estimatedDiskBytes, lastNow = Long.MIN_VALUE;
    private volatile boolean closed;
    // Only accessed while holding ioLock. Construction deliberately performs no file access.
    private volatile boolean initialized;
    private boolean diskAvailable, warned;
    private Object parentIdentity;
    private java.nio.file.attribute.FileTime parentCreated;
    private BasicFileAttributes expectedFile;

    public ScarletAvatarProviderCache(Path cacheFile)
    {
        this(cacheFile, System::currentTimeMillis, 256, 8 * 1024 * 1024, 512 * 1024, 5000, 16,
            TimeUnit.HOURS.toMillis(24), TimeUnit.MINUTES.toMillis(5));
    }

    ScarletAvatarProviderCache(Path cacheFile, LongSupplier clock, int maximumEntries, int maximumBytes,
            int maximumEntryBytes, int maximumResults, int maximumFlights, long positiveTtlMillis, long emptyTtlMillis)
    {
        if (maximumEntries < 1 || maximumBytes < 1 || maximumEntryBytes < 4 || maximumEntryBytes > maximumBytes
            || maximumResults < 1 || maximumResults > 5000 || maximumFlights < 1
            || positiveTtlMillis < 1 || emptyTtlMillis < 1)
            throw new IllegalArgumentException("Invalid avatar cache limits");
        this.file = cacheFile == null ? null : cacheFile.toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumEntries = maximumEntries;
        this.maximumBytes = maximumBytes;
        this.maximumEntryBytes = maximumEntryBytes;
        this.maximumResults = maximumResults;
        this.maximumFlights = maximumFlights;
        this.maximumFileBytes = Math.max(4096, maximumBytes);
        this.positiveTtlMillis = positiveTtlMillis;
        this.emptyTtlMillis = emptyTtlMillis;
    }

    /** A revoked caller never receives cached or newly loaded data. Null/failures are not negative-cache entries. */
    public VrcxAvatar[] get(String provider, String mode, int n, String query, BooleanSupplier allowed,
            Supplier<VrcxAvatar[]> loader)
    {
        if (this.closed || !permitted(allowed) || loader == null || n <= 0
            || !bounded(provider, 4096) || !bounded(mode, 64) || query == null || query.length() > 4096)
            return null;
        this.initialize(allowed, true);
        String key = key(provider, mode, n, query);
        while (!this.closed && permitted(allowed))
        {
            Entry cached;
            Flight flight;
            boolean owner;
            long epoch;
            synchronized (this.stateLock)
            {
                if (this.closed) return null;
                long now = this.observeClock();
                epoch = this.generation;
                cached = this.entries.get(key);
                if (cached != null && !this.fresh(cached, now))
                {
                    this.remove(key);
                    cached = null;
                }
                flight = cached == null ? this.flights.get(key) : null;
                owner = cached == null && flight == null;
                if (owner)
                {
                    if (this.flights.size() >= this.maximumFlights) return null;
                    flight = new Flight(epoch);
                    this.flights.put(key, flight);
                }
            }
            if (cached != null)
            {
                VrcxAvatar[] decoded = this.decodeOrNull(cached.bytes);
                if (decoded != null) return this.current(epoch, allowed) ? decoded : null;
                synchronized (this.stateLock) { if (this.entries.get(key) == cached) this.remove(key); }
                continue;
            }
            if (!owner)
            {
                byte[] result = this.await(flight, allowed);
                if (result == RETRY)
                {
                    if (!this.current(epoch, allowed)) return null;
                    continue;
                }
                VrcxAvatar[] decoded = result == null ? null : this.decodeOrNull(result);
                return this.current(epoch, allowed) ? decoded : null;
            }
            return this.load(key, n, flight, allowed, loader);
        }
        return null;
    }

    private VrcxAvatar[] load(String key, int n, Flight flight, BooleanSupplier allowed, Supplier<VrcxAvatar[]> loader)
    {
        byte[] shared = null;
        try
        {
            if (!this.current(flight.epoch, allowed)) return null;
            VrcxAvatar[] loaded = loader.get();
            if (loaded == null || !this.current(flight.epoch, allowed)) return null;
            // The transport also caps results; defend this API against oversized custom loaders.
            VrcxAvatar[] limited = Arrays.copyOf(loaded, Math.min(loaded.length, Math.min(n, this.maximumResults)));
            byte[] encoded;
            try { encoded = this.encode(limited); }
            catch (IOException | RuntimeException invalidOrOversized)
            {
                // An optional cache must not discard a successful response merely because it cannot retain it.
                // Followers retry independently; no oversized object graph is retained in a flight or cache.
                shared = RETRY;
                return this.current(flight.epoch, allowed) ? limited : null;
            }
            if (!this.current(flight.epoch, allowed)) return null;
            synchronized (this.stateLock)
            {
                long now = this.observeClock();
                if (this.closed || flight.epoch != this.generation) return null;
                this.put(key, new Entry(now, limited.length == 0, encoded));
            }
            shared = encoded;
            this.persist(flight.epoch, allowed);
            VrcxAvatar[] decoded = this.decodeOrNull(encoded);
            return this.current(flight.epoch, allowed) ? decoded : null;
        }
        catch (RuntimeException optionalFailure)
        {
            return null;
        }
        finally
        {
            synchronized (this.stateLock)
            {
                if (this.flights.get(key) == flight) this.flights.remove(key);
                if (this.closed || this.generation != flight.epoch) shared = null;
            }
            flight.result.complete(shared);
        }
    }

    private byte[] await(Flight flight, BooleanSupplier allowed)
    {
        while (this.current(flight.epoch, allowed))
        {
            try { return flight.result.get(100, TimeUnit.MILLISECONDS); }
            catch (TimeoutException waiting) { }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
            catch (ExecutionException failed) { return null; }
        }
        return null;
    }

    /** Clears only this cache. False means old on-disk data could not safely be cleared; memory is still cleared. */
    public boolean clear()
    {
        long epoch;
        List<Flight> pending;
        synchronized (this.stateLock)
        {
            if (this.closed) return false;
            epoch = ++this.generation;
            this.entries.clear(); this.retainedBytes = this.estimatedDiskBytes = 0;
            pending = new ArrayList<>(this.flights.values());
            this.flights.clear();
        }
        for (Flight flight : pending) flight.result.complete(null);
        synchronized (this.ioLock)
        {
            // A user-requested clear authorizes exact cache inspection, but never imports its old records.
            this.initializeLocked(() -> true, false);
            if (this.file == null) return true;
            if (!this.diskAvailable || this.closed) return false;
            return this.persistLocked(epoch, () -> true);
        }
    }

    /** Invalidates late loaders immediately, then waits only for any current cache I/O, never for the network. */
    @Override public void close()
    {
        List<Flight> pending = new ArrayList<>();
        synchronized (this.stateLock)
        {
            if (!this.closed)
            {
                this.closed = true; this.generation++;
                this.entries.clear(); this.retainedBytes = this.estimatedDiskBytes = 0;
                pending.addAll(this.flights.values());
                this.flights.clear();
            }
        }
        for (Flight flight : pending) flight.result.complete(null);
        synchronized (this.ioLock) { /* No cache filesystem operation can outlive this barrier. */ }
    }

    private boolean current(long epoch, BooleanSupplier allowed)
    {
        if (this.closed || !permitted(allowed)) return false;
        synchronized (this.stateLock) { return !this.closed && this.generation == epoch; }
    }

    private static boolean permitted(BooleanSupplier allowed)
    {
        try { return allowed != null && allowed.getAsBoolean(); }
        catch (RuntimeException invalidContext) { return false; }
    }

    private static boolean bounded(String text, int maximum)
    { return text != null && !text.isEmpty() && text.length() <= maximum; }

    private long observeClock()
    {
        long now = this.clock.getAsLong();
        if (now < 0 || now < this.lastNow)
        {
            this.entries.clear(); this.retainedBytes = this.estimatedDiskBytes = 0;
            this.generation++;
        }
        this.lastNow = now;
        return now;
    }

    private boolean fresh(Entry entry, long now)
    {
        long age = now - entry.created;
        return entry.created >= 0 && entry.created <= now && age >= 0
            && age < (entry.empty ? this.emptyTtlMillis : this.positiveTtlMillis);
    }

    private void remove(String key)
    {
        Entry old = this.entries.remove(key);
        if (old != null) { this.retainedBytes -= old.bytes.length; this.estimatedDiskBytes -= old.diskWeight(); }
    }

    private void put(String key, Entry entry)
    {
        this.remove(key);
        this.entries.put(key, entry);
        this.retainedBytes += entry.bytes.length;
        this.estimatedDiskBytes += entry.diskWeight();
        Iterator<Map.Entry<String, Entry>> oldest = this.entries.entrySet().iterator();
        while ((this.entries.size() > this.maximumEntries || this.retainedBytes > this.maximumBytes
                || this.estimatedDiskBytes + 256 > this.maximumFileBytes) && oldest.hasNext())
        {
            Entry evicted = oldest.next().getValue(); oldest.remove();
            this.retainedBytes -= evicted.bytes.length; this.estimatedDiskBytes -= evicted.diskWeight();
        }
    }

    private void initialize(BooleanSupplier allowed, boolean importExisting)
    {
        if (this.initialized || this.closed || !permitted(allowed)) return;
        synchronized (this.ioLock) { this.initializeLocked(allowed, importExisting); }
    }

    private void initializeLocked(BooleanSupplier allowed, boolean importExisting)
    {
        if (this.initialized || this.closed || !permitted(allowed)) return;
        if (this.file == null) { this.initialized = true; return; }
        long epoch;
        synchronized (this.stateLock) { epoch = this.generation; }
        try
        {
            this.checkParent(false);
            List<Saved> saved = Files.exists(this.file, LinkOption.NOFOLLOW_LINKS) ? this.readDisk() : new ArrayList<>();
            this.diskAvailable = true;
            if (importExisting && this.current(epoch, allowed)) synchronized (this.stateLock)
            {
                long now = this.observeClock();
                if (!this.closed && epoch == this.generation)
                    for (Saved item : saved) if (this.fresh(item.entry, now)) this.put(item.key, item.entry);
            }
        }
        catch (IOException | RuntimeException unsafeStorage) { this.disableDisk(); }
        finally { this.initialized = true; }
    }

    private void persist(long epoch, BooleanSupplier allowed)
    {
        synchronized (this.ioLock) { this.persistLocked(epoch, allowed); }
    }

    private boolean persistLocked(long epoch, BooleanSupplier allowed)
    {
        if (this.file == null) return true;
        if (!this.diskAvailable || !this.current(epoch, allowed)) return false;
        Path temporary = null;
        try
        {
            List<Saved> snapshot = new ArrayList<>();
            long now;
            synchronized (this.stateLock)
            {
                now = this.observeClock();
                if (this.closed || epoch != this.generation) return false;
                for (Map.Entry<String, Entry> item : this.entries.entrySet())
                    if (this.fresh(item.getValue(), now)) snapshot.add(new Saved(item.getKey(), item.getValue()));
            }
            this.checkParent(true);
            this.checkExpectedTarget();
            temporary = ScarletPrivateFiles.file(this.file.getParent(), ".scarlet-avatar-cache-", ".tmp");
            this.checkPrivateFile(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS))
            {
                JsonWriter writer = new JsonWriter(new OutputStreamWriter(
                    new BoundedOutput(Channels.newOutputStream(channel), this.maximumFileBytes), StandardCharsets.UTF_8));
                writer.beginObject().name("format").value(FORMAT).name("version").value(VERSION)
                    .name("savedAt").value(now).name("entries").beginArray();
                for (Saved item : snapshot)
                    writer.beginObject().name("key").value(item.key).name("createdAt").value(item.entry.created)
                        .name("checksum").value(hex(digest(item.entry.bytes)))
                        .name("metadata").value(Base64.getEncoder().encodeToString(item.entry.bytes)).endObject();
                writer.endArray().endObject(); writer.flush(); channel.force(true);
            }
            if (!this.current(epoch, allowed)) return false;
            this.checkParent(true);
            this.checkPrivateFile(temporary);
            this.checkExpectedTarget();
            Files.move(temporary, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            this.expectedFile = this.checkPrivateFile(this.file);
            return true;
        }
        catch (IOException | RuntimeException optionalFailure) { this.disableDisk(); return false; }
        finally
        {
            if (temporary != null) try
            {
                this.checkParent(true);
                Files.deleteIfExists(temporary); // Exact generated leaf only; never recursive and never follows a link.
            }
            catch (IOException | RuntimeException ignored) { }
        }
    }

    private void disableDisk()
    {
        this.diskAvailable = false;
        if (!this.warned)
        {
            this.warned = true;
            LOG.warn("Saved avatar-provider cache unavailable; continuing with a bounded memory-only cache.");
        }
    }

    private void checkParent(boolean compareIdentity) throws IOException
    {
        Path parent = this.file.getParent();
        if (parent == null) throw new IOException("Missing cache parent");
        Path cursor = parent.getRoot();
        for (Path component : parent)
        {
            cursor = cursor.resolve(component);
            BasicFileAttributes attrs = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther() || !cursor.toRealPath().equals(cursor))
                throw new IOException("Aliased cache parent");
        }
        BasicFileAttributes attributes = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object identity = attributes.fileKey();
        if (compareIdentity && (!Objects.equals(this.parentIdentity, identity)
            || !Objects.equals(this.parentCreated, attributes.creationTime()))) throw new IOException("Changed cache parent");
        if (!compareIdentity) { this.parentIdentity = identity; this.parentCreated = attributes.creationTime(); }
    }

    private void checkExpectedTarget() throws IOException
    {
        boolean exists = Files.exists(this.file, LinkOption.NOFOLLOW_LINKS);
        if (exists != (this.expectedFile != null)) throw new IOException("Changed cache target presence");
        if (!exists) return;
        BasicFileAttributes actual = this.checkPrivateFile(this.file);
        // Windows may return a null fileKey. Still require continuity of an observed exact leaf,
        // including its presence, size and timestamps; never recreate a vanished published file.
        if (!Objects.equals(this.expectedFile.fileKey(), actual.fileKey())
            || !this.expectedFile.creationTime().equals(actual.creationTime())
            || !this.expectedFile.lastModifiedTime().equals(actual.lastModifiedTime())
            || this.expectedFile.size() != actual.size()) throw new IOException("Changed cache target");
    }

    private BasicFileAttributes checkPrivateFile(Path path) throws IOException
    {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther() || !path.toRealPath().equals(path))
            throw new IOException("Cache must be an exact regular file");
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null)
        {
            Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
            for (PosixFilePermission permission : permissions)
                if (permission != PosixFilePermission.OWNER_READ && permission != PosixFilePermission.OWNER_WRITE)
                    throw new IOException("Cache is not private");
            if (!permissions.contains(PosixFilePermission.OWNER_READ)) throw new IOException("Unreadable cache");
        }
        else
        {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("Private cache permissions unavailable");
            List<AclEntry> permissions = acl.getAcl();
            if (permissions.size() != 1 || permissions.get(0).type() != AclEntryType.ALLOW
                || !permissions.get(0).principal().equals(acl.getOwner()) || !permissions.get(0).flags().isEmpty())
                throw new IOException("Cache is not owner-only");
        }
        return attrs;
    }

    private List<Saved> readDisk() throws IOException
    {
        BasicFileAttributes before = this.checkPrivateFile(this.file);
        if (before.size() > this.maximumFileBytes) throw new IOException("Oversized cache");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int)before.size());
        try (InputStream input = Channels.newInputStream(Files.newByteChannel(this.file,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
        {
            byte[] chunk = new byte[8192]; int count;
            while ((count = input.read(chunk)) != -1)
            {
                if ((long)bytes.size() + count > this.maximumFileBytes) throw new IOException("Growing oversized cache");
                bytes.write(chunk, 0, count);
            }
        }
        this.checkParent(true);
        BasicFileAttributes after = this.checkPrivateFile(this.file);
        if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
            || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("Changed cache file");
        this.expectedFile = after;
        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        List<Saved> saved = new ArrayList<>();
        long savedAt = -1; int seen = 0;
        try (JsonReader reader = new JsonReader(new StringReader(json)))
        {
            reader.setLenient(false); reader.beginObject();
            while (reader.hasNext())
            {
                String field = reader.nextName(); int bit;
                if ("format".equals(field)) { bit = 1; if (!FORMAT.equals(reader.nextString())) throw new IOException("Unknown cache format"); }
                else if ("version".equals(field)) { bit = 2; if (reader.nextInt() != VERSION) throw new IOException("Unknown cache version"); }
                else if ("savedAt".equals(field)) { bit = 4; savedAt = reader.nextLong(); }
                else if ("entries".equals(field))
                {
                    bit = 8; reader.beginArray();
                    Map<String, Boolean> unique = new HashMap<>();
                    long total = 0;
                    while (reader.hasNext())
                    {
                        if (saved.size() >= this.maximumEntries) throw new IOException("Too many cache entries");
                        Saved item = this.readSaved(reader);
                        if (unique.put(item.key, Boolean.TRUE) != null) throw new IOException("Duplicate cache key");
                        total += item.entry.bytes.length;
                        if (total > this.maximumBytes) throw new IOException("Oversized cache contents");
                        saved.add(item);
                    }
                    reader.endArray();
                }
                else throw new IOException("Unknown cache field");
                if ((seen & bit) != 0) throw new IOException("Duplicate cache field");
                seen |= bit;
            }
            reader.endObject();
            if (seen != 15 || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Incomplete cache");
        }
        long now = this.clock.getAsLong();
        if (savedAt < 0 || savedAt > now) return new ArrayList<>();
        for (Saved item : saved) if (item.entry.created > savedAt) throw new IOException("Invalid cache timestamp");
        return saved;
    }

    private Saved readSaved(JsonReader reader) throws IOException
    {
        String key = null, checksum = null, metadata = null; long created = -1; int seen = 0;
        reader.beginObject();
        while (reader.hasNext())
        {
            String name = reader.nextName(); int bit;
            if ("key".equals(name)) { bit = 1; key = reader.nextString(); }
            else if ("createdAt".equals(name)) { bit = 2; created = reader.nextLong(); }
            else if ("checksum".equals(name)) { bit = 4; checksum = reader.nextString(); }
            else if ("metadata".equals(name)) { bit = 8; metadata = reader.nextString(); }
            else throw new IOException("Unknown cache entry field");
            if ((seen & bit) != 0) throw new IOException("Duplicate cache entry field");
            seen |= bit;
        }
        reader.endObject();
        if (seen != 15 || created < 0 || !hashString(key) || !hashString(checksum)
            || metadata.length() > 4L * ((this.maximumEntryBytes + 2L) / 3L)) throw new IOException("Invalid cache entry");
        byte[] payload;
        try { payload = Base64.getDecoder().decode(metadata); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid cache encoding"); }
        if (payload.length > this.maximumEntryBytes || !checksum.equals(hex(digest(payload)))) throw new IOException("Corrupt cache metadata");
        VrcxAvatar[] decoded = this.decode(payload);
        return new Saved(key, new Entry(created, decoded.length == 0, payload));
    }

    private byte[] encode(VrcxAvatar[] values) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new BoundedOutput(bytes, this.maximumEntryBytes)))
        {
            out.writeInt(values.length);
            for (VrcxAvatar avatar : values)
            {
                out.writeBoolean(avatar != null);
                if (avatar == null) continue;
                writeText(out, avatar.id, 128); writeText(out, avatar.name, 1024);
                writeText(out, avatar.authorId, 128); writeText(out, avatar.authorName, 1024);
                writeText(out, avatar.description, 16384);
                writeText(out, imageUrl(avatar.imageUrl), 2048); writeText(out, imageUrl(avatar.thumbnailImageUrl), 2048);
                writeText(out, avatar.releaseStatus, 128);
                writeText(out, avatar.createdAt == null ? null : avatar.createdAt.toString(), 64);
                writeText(out, avatar.updatedAt == null ? null : avatar.updatedAt.toString(), 64);
                out.writeBoolean(avatar.performance != null);
                if (avatar.performance != null)
                {
                    writeText(out, avatar.performance.pcRating, 128); writeText(out, avatar.performance.androidRating, 128);
                    writeText(out, avatar.performance.iosRating, 128); out.writeBoolean(avatar.performance.hasImpostor);
                    out.writeByte(avatar.performance.hasSecurityVariant == null ? -1 : avatar.performance.hasSecurityVariant ? 1 : 0);
                }
            }
        }
        return bytes.toByteArray();
    }

    private VrcxAvatar[] decodeOrNull(byte[] bytes)
    {
        try { return this.decode(bytes); }
        catch (IOException | RuntimeException corrupt) { return null; }
    }

    private VrcxAvatar[] decode(byte[] bytes) throws IOException
    {
        if (bytes.length > this.maximumEntryBytes) throw new IOException("Oversized entry");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)))
        {
            int count = in.readInt();
            if (count < 0 || count > this.maximumResults) throw new IOException("Invalid result count");
            VrcxAvatar[] values = new VrcxAvatar[count];
            for (int index = 0; index < count; index++)
            {
                if (!in.readBoolean()) continue;
                VrcxAvatar avatar = new VrcxAvatar(); values[index] = avatar;
                avatar.id = readText(in, 128); avatar.name = readText(in, 1024);
                avatar.authorId = readText(in, 128); avatar.authorName = readText(in, 1024);
                avatar.description = readText(in, 16384);
                avatar.imageUrl = imageUrl(readText(in, 2048)); avatar.thumbnailImageUrl = imageUrl(readText(in, 2048));
                avatar.releaseStatus = readText(in, 128);
                String created = readText(in, 64), updated = readText(in, 64);
                avatar.createdAt = created == null ? null : OffsetDateTime.parse(created);
                avatar.updatedAt = updated == null ? null : OffsetDateTime.parse(updated);
                if (in.readBoolean())
                {
                    avatar.performance = new VrcxAvatar.Performance();
                    avatar.performance.pcRating = readText(in, 128); avatar.performance.androidRating = readText(in, 128);
                    avatar.performance.iosRating = readText(in, 128); avatar.performance.hasImpostor = in.readBoolean();
                    int security = in.readByte();
                    if (security < -1 || security > 1) throw new IOException("Invalid performance field");
                    avatar.performance.hasSecurityVariant = security == -1 ? null : security == 1;
                }
            }
            if (in.read() != -1) throw new IOException("Trailing cache metadata");
            return values;
        }
    }

    private static String imageUrl(String value)
    {
        if (value == null || value.length() > 2048) return null;
        try
        {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null ? value : null;
        }
        catch (java.net.URISyntaxException invalid) { return null; }
    }

    private static void writeText(DataOutputStream out, String value, int maximumCharacters) throws IOException
    {
        if (value == null) { out.writeInt(-1); return; }
        if (value.length() > maximumCharacters) throw new IOException("Oversized metadata field");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static String readText(DataInputStream in, int maximumCharacters) throws IOException
    {
        int length = in.readInt();
        if (length == -1) return null;
        if (length < 0 || length > maximumCharacters * 4 || length > in.available()) throw new IOException("Invalid metadata field size");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if (text.length() > maximumCharacters) throw new IOException("Oversized metadata field");
        return text;
    }

    private static String key(String provider, String mode, int n, String query)
    {
        MessageDigest digest = sha256();
        for (String item : new String[]{provider, mode, Integer.toString(n), query})
        {
            byte[] bytes = item.getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
        return hex(digest.digest());
    }

    private static boolean hashString(String value)
    { return value != null && value.matches("[0-9a-f]{64}"); }
    private static byte[] digest(byte[] bytes) { return sha256().digest(bytes); }
    private static MessageDigest sha256()
    {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String hex(byte[] bytes)
    {
        char[] chars = new char[bytes.length * 2]; String alphabet = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) { chars[i * 2] = alphabet.charAt((bytes[i] >>> 4) & 15); chars[i * 2 + 1] = alphabet.charAt(bytes[i] & 15); }
        return new String(chars);
    }

    int retainedEntryCount() { synchronized (this.stateLock) { return this.entries.size(); } }
    long retainedByteCount() { synchronized (this.stateLock) { return this.retainedBytes; } }
    int pendingCount() { synchronized (this.stateLock) { return this.flights.size(); } }

    private static final class Entry
    {
        final long created; final boolean empty; final byte[] bytes;
        Entry(long created, boolean empty, byte[] bytes) { this.created = created; this.empty = empty; this.bytes = bytes; }
        long diskWeight() { return 256L + 4L * ((this.bytes.length + 2L) / 3L); }
    }
    private static final class Saved
    {
        final String key; final Entry entry;
        Saved(String key, Entry entry) { this.key = key; this.entry = entry; }
    }
    private static final class Flight
    {
        final long epoch; final CompletableFuture<byte[]> result = new CompletableFuture<>();
        Flight(long epoch) { this.epoch = epoch; }
    }
    private static final class BoundedOutput extends OutputStream
    {
        final OutputStream out; final long maximum; long written;
        BoundedOutput(OutputStream out, long maximum) { this.out = out; this.maximum = maximum; }
        @Override public void write(int value) throws IOException
        { this.reserve(1); this.out.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException
        { this.reserve(length); this.out.write(bytes, offset, length); }
        private void reserve(int length) throws IOException
        { if (length < 0 || this.written + length > this.maximum) throw new IOException("Cache output limit exceeded"); this.written += length; }
        @Override public void flush() throws IOException { this.out.flush(); }
        @Override public void close() throws IOException { this.out.close(); }
    }
}

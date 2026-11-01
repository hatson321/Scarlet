package net.sybyline.scarlet;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import io.github.vrchatapi.JSON;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;

import net.sybyline.scarlet.util.LRUMap;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.UniqueStrings;

public class ScarletData
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/Data");

    private static Gson storageGson(Class<?> type)
    {
        // Historical audit records are Scarlet's local format, not newly validated
        // HTTP responses. Preserve their raw data even before the API client starts.
        return type == AuditEntryMetadata.class ? Scarlet.GSON : JSON.getGson();
    }

    public ScarletData(File dir)
    {
        this.dir = dir;
        this.objs = new ConcurrentHashMap<>();
        this.subs = new ConcurrentHashMap<>();
        
        this.liveInstances = this.getDatum("live.json", LiveInstancesMetadata.class, LiveInstancesMetadata::new);
        this.global = this.getDatum("global.json", GlobalMetadata.class, GlobalMetadata::new);
    }

    final File dir;
    final Map<String, Datum<?>> objs;
    final Map<String, Data<?>> subs;
    private final Object[] entityLocks = new Object[256];
    {
        Arrays.setAll(this.entityLocks, i -> new Object());
    }
    private final Map<String, PendingWrite> pendingWrites = new ConcurrentHashMap<>();

    private Object entityLock(String kind, String id)
    {
        return this.entityLocks[((kind + "/" + id).hashCode() & Integer.MAX_VALUE) % this.entityLocks.length];
    }
    private static final class PendingWrite
    {
        final String kind, id;
        final Object value;
        final Runnable retry;
        PendingWrite(String kind, String id, Object value, Runnable retry)
        { this.kind = kind; this.id = id; this.value = value; this.retry = retry; }
    }

    final Datum<LiveInstancesMetadata> liveInstances;
    final Datum<GlobalMetadata> global;

    public <T> Datum<T> getDatum(String name, Class<T> type, Supplier<T> createDefault)
    {
        return this.objs.computeIfAbsent(name, _name -> new ObjDatum<>(_name, type, createDefault)).as(type);
    }
    public Datum<?> freeDatum(String name)
    {
        return this.objs.remove(name);
    }
    public <T> Data<T> getData(String kind, Class<T> type)
    {
        return this.subs.computeIfAbsent(kind, _kind -> new SubData<>(_kind, type)).as(type);
    }
    public Data<?> freeData(String name)
    {
        return this.subs.remove(name);
    }

    public void saveDirty()
    {
        this.retryPendingWrites();
        this.objs.values().forEach(Datum::saveIfDirty);
        this.subs.values().forEach(Data::saveIfDirty);
    }

    public void saveAll()
    {
        this.retryPendingWrites();
        this.objs.values().forEach(Datum::save);
        this.subs.values().forEach(Data::save);
    }

    boolean hasPendingAuditMetadataWrites()
    {
        for (String key : this.pendingWrites.keySet())
            if (key.startsWith("gaud/")) return true;
        return false;
    }

    private void retryPendingWrites()
    {
        for (PendingWrite pending : this.pendingWrites.values())
            synchronized (this.entityLock(pending.kind, pending.id))
            {
                if (this.pendingWrites.get(pending.kind + "/" + pending.id) == pending) pending.retry.run();
            }
    }

    <T> T readObj(String name, Class<T> type)
    {
        return this.readObj(name, type, false);
    }
    <T> T readObj(String name, Class<T> type, boolean orNew)
    {
        if (name == null)
        {
            LOG.error("Reading data: name == null", new Exception());
            return null;
        }
        if (type == null)
        {
            LOG.error("Reading data `"+name+"`: type == null", new Exception());
            return null;
        }
        File targetFile = new File(this.dir, name);
        if (!existingRecord(targetFile))
        {
            LOG.warn("Reading data `"+name+"`: No such file: "+targetFile.getAbsolutePath());
        }
        else try (Reader reader = MiscUtils.reader(targetFile))
        {
            T value = storageGson(type).fromJson(reader, type);
            if (value == null) throw new IOException("Existing metadata contains no object");
            return value;
        }
        catch (Exception ex)
        {
            LOG.error("Exception reading data "+name, ex);
            throw readFailure(ex);
        }
        if (orNew) try
        {
            return type.getDeclaredConstructor().newInstance();
        }
        catch (Exception ex)
        {
            LOG.error("Exception creating data "+name, ex);
        }
        return null;
    }
    <T> boolean writeObj(String name, Class<T> type, T data)
    {
        if (name == null)
        {
            LOG.error("Writing data: name == null", new Exception());
            return false;
        }
        if (type == null)
        {
            LOG.error("Writing data `"+name+"`: type == null", new Exception());
            return false;
        }
        if (data == null)
        {
            LOG.error("Writing data `"+name+"`: data == null", new Exception());
            return false;
        }
        File targetFile = new File(this.dir, name);
        if (!targetFile.getParentFile().isDirectory())
            targetFile.getParentFile().mkdirs();
        try (net.sybyline.scarlet.util.FileBackups.AtomicWriter writer = net.sybyline.scarlet.util.FileBackups.writer(targetFile))
        {
            storageGson(type).toJson(data, type, writer);
            writer.commit();
        }
        catch (Exception ex)
        {
            LOG.error("Exception writing data "+name, ex);
            return false;
        }
        return true;
    }
    <T> void editObj(String name, Class<T> type, UnaryOperator<T> edit)
    {
        T data = this.readObj(name, type);
        if (data != null)
        {
            data = edit.apply(data);
            this.writeObj(name, type, data);
        }
    }

    String[] listSub(String kind, Predicate<File> condition)
    {
        File targetDir = new File(this.dir, kind);
        if (!targetDir.isDirectory())
            return new String[0];
        return targetDir.list((targetDir0, targetFileName) ->
        {
            File targetFile = new File(targetDir0, targetFileName);
            return targetFile.isFile() && condition.test(targetFile);
        });
    }
    <T> boolean existsSub(String kind, String id)
    {
        return new File(new File(this.dir, kind), id).isFile();
    }
    <T> T readSub(String kind, String id, Class<T> type)
    {
        return this.readSub(kind, id, type, false);
    }
    <T> T readSub(String kind, String id, Class<T> type, boolean orNew)
    {
        PendingWrite pending = this.pendingWrites.get(kind + "/" + id);
        if (pending != null && type != null && type.isInstance(pending.value)) return type.cast(pending.value);
        if (kind == null)
        {
            LOG.error("Reading sub data: kind == null", new Exception());
            return null;
        }
        if (id == null)
        {
            LOG.error("Reading sub data `"+kind+"`: id == null", new Exception());
            return null;
        }
        if (type == null)
        {
            LOG.error("Reading sub data `"+kind+"`: type == null", new Exception());
            return null;
        }
        File targetDir = new File(this.dir, kind);
        File targetFile = new File(targetDir, id);
        if (existingRecord(targetFile))
        try (Reader reader = MiscUtils.reader(targetFile))
        {
            T value = storageGson(type).fromJson(reader, type);
            if (value == null) throw new IOException("Existing metadata contains no object");
            return value;
        }
        catch (Exception ex)
        {
            LOG.error("Exception reading sub data "+kind+":"+id, ex);
            throw readFailure(ex);
        }
        if (orNew) try
        {
            return type.getDeclaredConstructor().newInstance();
        }
        catch (Exception ex)
        {
            LOG.error("Exception creating sub data "+kind+":"+id, ex);
        }
        return null;
    }

    /** Only a confirmed absent file permits a new default; damaged records stay untouched. */
    static boolean existingRecord(File file)
    {
        try
        {
            BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("Existing metadata is not a regular file");
            return true;
        }
        catch (NoSuchFileException absent) { return false; }
        catch (IOException | RuntimeException failure) { throw readFailure(failure); }
    }

    private static java.io.UncheckedIOException readFailure(Exception cause)
    {
        return new java.io.UncheckedIOException("Existing metadata could not be read; its original bytes were preserved. Restore or repair the record before retrying.",
            cause instanceof IOException ? (IOException) cause : new IOException(cause));
    }
    <T> boolean writeSub(String kind, String id, Class<T> type, T data)
    {
        synchronized (this.entityLock(kind, id))
        {
            boolean saved = this.writeSubNow(kind, id, type, data);
            String key = kind + "/" + id;
            if (saved) this.pendingWrites.remove(key);
            else if (kind != null && id != null && type != null && data != null)
                this.pendingWrites.put(key, new PendingWrite(kind, id, data, () -> this.writeSub(kind, id, type, data)));
            return saved;
        }
    }
    private <T> boolean writeSubNow(String kind, String id, Class<T> type, T data)
    {
        if (kind == null)
        {
            LOG.error("Writing sub data: kind == null", new Exception());
            return false;
        }
        if (id == null)
        {
            LOG.error("Writing sub data `"+kind+"`: id == null", new Exception());
            return false;
        }
        if (type == null)
        {
            LOG.error("Writing sub data `"+kind+"`: type == null", new Exception());
            return false;
        }
        if (data == null)
        {
            LOG.error("Writing sub data `"+kind+"`: data == null", new Exception());
            return false;
        }
        File targetDir = new File(this.dir, kind);
        if (!targetDir.isDirectory())
            targetDir.mkdirs();
        File targetFile = new File(targetDir, id);
        // High-cardinality per-entity file: crash-safe atomic write (fsync) but keep=0 so we do
        // not spawn a dated backup per user/audit-entry/event. Truncation-on-crash is the risk we
        // are closing here; the singleton configs above keep dated backups.
        try (net.sybyline.scarlet.util.FileBackups.AtomicWriter writer = net.sybyline.scarlet.util.FileBackups.writer(targetFile, 0))
        {
            storageGson(type).toJson(data, type, writer);
            writer.commit();
        }
        catch (Exception ex)
        {
            LOG.error("Exception writing sub data "+kind+":"+id, ex);
            return false;
        }
        return true;
    }
    <T> void editSub(String kind, String id, Class<T> type, UnaryOperator<T> edit)
    {
        synchronized (this.entityLock(kind, id))
        {
            T data = this.readSub(kind, id, type, true);
            if (data != null)
            {
                // Do not publish partial mutations from a callback that throws.
                Gson gson = storageGson(type);
                T edited = edit.apply(gson.fromJson(gson.toJson(data), type));
                if (!this.writeSub(kind, id, type, edited)) throw persistenceFailure();
            }
        }
    }

    private static java.io.UncheckedIOException persistenceFailure()
    { return new java.io.UncheckedIOException(new java.io.IOException("Metadata could not be saved; pending changes are retained for retry")); }

    public interface Datum<T>
    {
        Class<T> getType();
        default <TT> Datum<TT> as(Class<TT> ttype)
        {
            if (this.getType() != ttype)
                throw new ClassCastException(this.getType()+" is not the same as "+ttype);
            @SuppressWarnings("unchecked")
            Datum<TT> tthis = (Datum<TT>)this;
            return tthis;
        }
        boolean free();
        boolean exists();
        void markDirty();
        boolean pollDirty();
        void save();
        default void saveIfDirty()
        {
            if (this.pollDirty())
                this.save();
        }
        T get();
    }

    public interface Data<T>
    {
        Class<T> getType();
        default <TT> Data<TT> as(Class<TT> ttype)
        {
            if (this.getType() != ttype)
                throw new ClassCastException(this.getType()+" is not the same as "+ttype);
            @SuppressWarnings("unchecked")
            Data<TT> tthis = (Data<TT>)this;
            return tthis;
        }
        boolean free();
        boolean exists(String id);
        void flush();
        void save();
        void saveIfDirty();
        Datum<T> get(String id);
    }

    final class ObjDatum<T> implements Datum<T>
    {
        ObjDatum(String name, Class<T> type, Supplier<T> createDefault)
        {
            this.name = name;
            this.type = type;
            this.createDefault = createDefault;
            this.value = null;
            this.dirty = false;
        }
        final String name;
        final Class<T> type;
        final Supplier<T> createDefault;
        T value;
        boolean dirty;
        @Override
        public Class<T> getType()
        {
            return this.type;
        }
        @Override
        public boolean free()
        {
            return ScarletData.this.objs.remove(this.name, this);
        }
        @Override
        public boolean exists()
        {
            return this.value != null || new File(ScarletData.this.dir, this.name).isFile();
        }
        @Override
        public synchronized void markDirty()
        {
            this.dirty = true;
        }
        @Override
        public synchronized boolean pollDirty()
        {
            return this.dirty;
        }
        @Override
        public synchronized void save()
        {
            T value = this.value;
            if (value != null)
            {
                this.dirty = !ScarletData.this.writeObj(this.name, this.type, value);
            }
        }
        @Override
        public synchronized T get()
        {
            T value = this.value;
            if (value == null)
            {
                value = ScarletData.this.readObj(this.name, this.type, true);
                if (value == null)
                {
                    value = this.createDefault.get();
                }
                this.value = value;
            }
            return value;
        }
    }

    final class SubData<T> implements Data<T>
    {
        SubData(String kind, Class<T> type)
        {
            this.kind = kind;
            this.type = type;
            this.subdir = new File(ScarletData.this.dir, kind);
            this.values = LRUMap.ofSynchronized(this::save);
            this.dirties = Collections.newSetFromMap(new ConcurrentHashMap<>());
        }
        final String kind;
        final Class<T> type;
        final File subdir;
        final Map<String, T> values;
        final Set<String> dirties;
        void saveIfDirty(String id, T value)
        {
            synchronized (this.values)
            {
                if (this.dirties.contains(id) && value != null) this.save(id, value);
            }
        }
        void save(String id, T value)
        {
            synchronized (this.values)
            {
                if (value != null)
                {
                    if (ScarletData.this.writeSub(this.kind, id, this.type, value)) this.dirties.remove(id);
                    else this.dirties.add(id);
                }
            }
        }
        void flush(String id, T value)
        {
            synchronized (this.values)
            {
                if (value != null)
                {
                    if (ScarletData.this.writeSub(this.kind, id, this.type, value))
                    { this.values.remove(id, value); this.dirties.remove(id); }
                    else this.dirties.add(id);
                }
            }
        }
        T get0(String id)
        {
            return this.values.computeIfAbsent(id, _id -> ScarletData.this.readSub(this.kind, _id, this.type, true));
        }
        @Override
        public Class<T> getType()
        {
            return this.type;
        }
        @Override
        public boolean free()
        {
            return ScarletData.this.subs.remove(this.kind, this);
        }
        @Override
        public boolean exists(String id)
        {
            return this.values.containsKey(id) || new File(this.subdir, id).isFile();
        }
        @Override
        public void flush()
        {
            new HashMap<>(this.values).forEach(this::flush);
        }
        @Override
        public void save()
        {
            this.values.forEach(this::save);
        }
        @Override
        public void saveIfDirty()
        {
            this.values.forEach(this::saveIfDirty);
        }
        @Override
        public Datum<T> get(String id)
        {
            return new Datum<T>()
            {
                @Override
                public Class<T> getType()
                {
                    return SubData.this.type;
                }
                @Override
                public boolean free()
                {
                    return false;
                }
                @Override
                public boolean exists()
                {
                    return SubData.this.exists(id);
                }
                @Override
                public void markDirty()
                {
                    synchronized (SubData.this.values) { SubData.this.dirties.add(id); }
                }
                @Override
                public boolean pollDirty()
                {
                    return SubData.this.dirties.contains(id);
                }
                @Override
                public void save()
                {
                    SubData.this.save(id, SubData.this.values.get(id));
                }
                @Override
                public T get()
                {
                    return SubData.this.get0(id);
                }
            };
        }
    }

    public synchronized String globalMetadata_getSnowflakeId(String userSnowflake)
    {
        ScarletData.GlobalMetadata globalMeta = this.global.get();
        return globalMeta.getSnowflakeId(userSnowflake);
    }
    public synchronized ScarletData.GlobalMetadata globalMetadata_setSnowflakeId(String userSnowflake, String userId)
    {
        ScarletData.GlobalMetadata globalMeta = this.global.get();
        globalMeta.setSnowflakeId(userSnowflake, userId);
        this.global.markDirty();
        return globalMeta;
    }
    public synchronized ScarletData.GlobalMetadata globalMetadata_removeSnowflakeId(String userSnowflake)
    {
        ScarletData.GlobalMetadata globalMeta = this.global.get();
        globalMeta.removeSnowflakeId(userSnowflake);
        this.global.markDirty();
        return globalMeta;
    }

    public String liveInstancesMetadata_getLocationAudit(String location, boolean remove)
    {
        ScarletData.LiveInstancesMetadata liveInstancesMeta = this.liveInstances.get();
        String auditEntryId = liveInstancesMeta.getLocationAudit(location, remove);
        if (remove && auditEntryId != null)
            this.liveInstances.markDirty();
        return auditEntryId;
    }
    public ScarletData.LiveInstancesMetadata liveInstancesMetadata_setLocationAudit(String location, String auditEntryId)
    {
        ScarletData.LiveInstancesMetadata liveInstancesMeta = this.liveInstances.get();
        liveInstancesMeta.setLocationAudit(location, auditEntryId);
        this.liveInstances.markDirty();
        return liveInstancesMeta;
    }
    public Set<String> liveInstancesMetadata_getLocations()
    {
        ScarletData.LiveInstancesMetadata liveInstancesMeta = this.liveInstances.get();
        return liveInstancesMeta.getLocations();
    }
    public InstanceEmbedMessage liveInstancesMetadata_getLocationInstanceEmbedMessage(String location, boolean remove)
    {
        ScarletData.LiveInstancesMetadata liveInstancesMeta = this.liveInstances.get();
        InstanceEmbedMessage instanceEmbedMessage = liveInstancesMeta.getLocationInstanceEmbedMessage(location, remove);
        if (remove && instanceEmbedMessage != null)
            this.liveInstances.markDirty();
        return instanceEmbedMessage;
    }
    public ScarletData.LiveInstancesMetadata liveInstancesMetadata_setLocationInstanceEmbedMessage(String location, String guildSnowflake, String channelSnowflake, String messageSnowflake, String threadSnowflake, String auxMessageSnowflake, OffsetDateTime openedAt)
    {
        ScarletData.LiveInstancesMetadata liveInstancesMeta = this.liveInstances.get();
        liveInstancesMeta.setLocationInstanceEmbedMessage(location, guildSnowflake, channelSnowflake, messageSnowflake, threadSnowflake, auxMessageSnowflake, openedAt);
        this.liveInstances.markDirty();
        return liveInstancesMeta;
    }

    public UserMetadata userMetadata(String userId)
    {
        return this.readSub("usr", userId, UserMetadata.class);
    }
    public void userMetadata(String userId, UserMetadata userMetadata)
    {
        if (!this.writeSub("usr", userId, UserMetadata.class, userMetadata)) throw persistenceFailure();
    }
    public void userMetadata(String userId, UnaryOperator<UserMetadata> edit)
    {
        this.updateUserMetadata(userId, edit);
    }
    public UserMetadata updateUserMetadata(String userId, UnaryOperator<UserMetadata> edit)
    {
        synchronized (this.entityLock("usr", userId))
        {
            this.editSub("usr", userId, UserMetadata.class, edit);
            return this.userMetadata(userId);
        }
    }
    public ScarletData.UserMetadata userMetadata_setSnowflake(String userId, String userSnowflake)
    {
        return this.updateUserMetadata(userId, userMeta -> { userMeta.userSnowflake = userSnowflake; return userMeta; });
    }
    public synchronized String userMetadata_getSnowflake(String userId)
    {
        ScarletData.UserMetadata userMeta = this.userMetadata(userId);
        if (userMeta == null)
            return null;
        return userMeta.userSnowflake;
    }

    public AuditEntryMetadata auditEntryMetadata(String auditEntryId)
    {
        return this.readSub("gaud", auditEntryId, AuditEntryMetadata.class);
    }
    public boolean auditEntryMetadataExists(String auditEntryId)
    {
        return this.existsSub("gaud", auditEntryId);
    }
    public void auditEntryMetadata(String auditEntryId, AuditEntryMetadata auditEntryMetadata)
    {
        if (!this.writeSub("gaud", auditEntryId, AuditEntryMetadata.class, auditEntryMetadata)) throw persistenceFailure();
    }
    public void auditEntryMetadata(String auditEntryId, UnaryOperator<AuditEntryMetadata> edit)
    {
        this.editSub("gaud", auditEntryId, AuditEntryMetadata.class, edit);
    }
    /** Update only a still-existing audit record and return the committed snapshot. */
    public AuditEntryMetadata updateAuditEntryMetadata(String auditEntryId, UnaryOperator<AuditEntryMetadata> edit)
    {
        synchronized (this.entityLock("gaud", auditEntryId))
        {
            AuditEntryMetadata current = this.auditEntryMetadata(auditEntryId);
            if (current == null) return null;
            Gson gson = storageGson(AuditEntryMetadata.class);
            AuditEntryMetadata edited = edit.apply(gson.fromJson(gson.toJson(current), AuditEntryMetadata.class));
            if (!this.writeSub("gaud", auditEntryId, AuditEntryMetadata.class, edited)) throw persistenceFailure();
            return this.auditEntryMetadata(auditEntryId);
        }
    }
    public ScarletData.AuditEntryMetadata auditEntryMetadata_setTags(String auditEntryId, String[] entryTags)
    {
        synchronized (this.entityLock("gaud", auditEntryId))
        {
            this.editSub("gaud", auditEntryId, AuditEntryMetadata.class, value -> { value.entryTags.clear().addAll(entryTags); return value; });
            return this.auditEntryMetadata(auditEntryId);
        }
    }
    public ScarletData.AuditEntryMetadata auditEntryMetadata_editTags(String auditEntryId, String[] entryTagsRemove, String[] entryTagsAdd)
    {
        synchronized (this.entityLock("gaud", auditEntryId))
        {
            this.editSub("gaud", auditEntryId, AuditEntryMetadata.class, value -> {
                value.entryTags.removeAll(entryTagsRemove); value.entryTags.addAll(entryTagsAdd); return value;
            });
            return this.auditEntryMetadata(auditEntryId);
        }
    }
    public ScarletData.AuditEntryMetadata auditEntryMetadata_setDescription(String auditEntryId, String entryDescription)
    {
        synchronized (this.entityLock("gaud", auditEntryId))
        {
            this.editSub("gaud", auditEntryId, AuditEntryMetadata.class, value -> {
                value.entryDescription = entryDescription == null || entryDescription.trim().isEmpty() ? null : entryDescription.trim(); return value;
            });
            return this.auditEntryMetadata(auditEntryId);
        }
    }

    public CustomEvent customEvent(String customEventId)
    {
        return this.readSub("ex", customEventId, CustomEvent.class);
    }
    public void customEvent(String customEventId, CustomEvent customEvent)
    {
        this.writeSub("ex", customEventId, CustomEvent.class, customEvent);
    }
    public void customEvent(String customEventId, UnaryOperator<CustomEvent> edit)
    {
        this.editSub("ex", customEventId, CustomEvent.class, edit);
    }

    public static class GlobalMetadata
    {
        public GlobalMetadata()
        {
        }
        
        public Map<String, String> userSnowflake2userId;
        
        public synchronized void setSnowflakeId(String userSnowflake, String userId)
        {
            Map<String, String> userSnowflake2userId = this.userSnowflake2userId;
            if (userSnowflake2userId == null)
                this.userSnowflake2userId = userSnowflake2userId = new HashMap<>();
            if (!(userSnowflake2userId instanceof HashMap))
                this.userSnowflake2userId = userSnowflake2userId = new HashMap<>(userSnowflake2userId);
            userSnowflake2userId.put(userSnowflake, userId);
        }
        public synchronized String getSnowflakeId(String userSnowflake)
        {
            Map<String, String> userSnowflake2userId = this.userSnowflake2userId;
            if (userSnowflake2userId == null)
                return null;
            return userSnowflake2userId.get(userSnowflake);
        }
        public synchronized String removeSnowflakeId(String userSnowflake)
        {
            Map<String, String> userSnowflake2userId = this.userSnowflake2userId;
            if (userSnowflake2userId == null)
                return null;
            if (!(userSnowflake2userId instanceof HashMap))
                this.userSnowflake2userId = userSnowflake2userId = new HashMap<>(userSnowflake2userId);
            return userSnowflake2userId.remove(userSnowflake);
        }
    }

    public static class LiveInstancesMetadata
    {
        public LiveInstancesMetadata()
        {
        }
        
        public Map<String, String> location2AuditEntryId;
        public Map<String, InstanceEmbedMessage> location2instanceEmbedMessage;
        
        public synchronized void setLocationAudit(String location, String auditEntryId)
        {
            Map<String, String> location2AuditEntryId = this.location2AuditEntryId;
            if (location2AuditEntryId == null)
                this.location2AuditEntryId = location2AuditEntryId = new HashMap<>();
            if (!(location2AuditEntryId instanceof HashMap))
                this.location2AuditEntryId = location2AuditEntryId = new HashMap<>(location2AuditEntryId);
            location2AuditEntryId.put(location, auditEntryId);
        }
        public synchronized String getLocationAudit(String location, boolean remove)
        {
            Map<String, String> location2AuditEntryId = this.location2AuditEntryId;
            if (location2AuditEntryId == null)
                return null;
            return remove
                ? location2AuditEntryId.remove(location)
                : location2AuditEntryId.get(location);
        }
        public synchronized Set<String> getLocations()
        {
            Map<String, String> location2AuditEntryId = this.location2AuditEntryId;
            if (location2AuditEntryId == null)
                return null;
            return location2AuditEntryId.keySet();
        }
        public synchronized void setLocationInstanceEmbedMessage(String location, String guildSnowflake, String channelSnowflake, String messageSnowflake, String threadSnowflake, String auxMessageSnowflake, OffsetDateTime openedAt)
        {
            Map<String, InstanceEmbedMessage> location2instanceEmbedMessage = this.location2instanceEmbedMessage;
            if (location2instanceEmbedMessage == null)
                this.location2instanceEmbedMessage = location2instanceEmbedMessage = new HashMap<>();
            if (!(location2instanceEmbedMessage instanceof HashMap))
                this.location2instanceEmbedMessage = location2instanceEmbedMessage = new HashMap<>(location2instanceEmbedMessage);
            location2instanceEmbedMessage.put(location, new InstanceEmbedMessage(guildSnowflake, channelSnowflake, messageSnowflake, threadSnowflake, auxMessageSnowflake, openedAt));
        }
        public synchronized InstanceEmbedMessage getLocationInstanceEmbedMessage(String location, boolean remove)
        {
            Map<String, InstanceEmbedMessage> location2instanceEmbedMessage = this.location2instanceEmbedMessage;
            if (location2instanceEmbedMessage == null)
                return null;
            return remove
                ? location2instanceEmbedMessage.remove(location)
                : location2instanceEmbedMessage.get(location);
        }
    }
    public static class InstanceEmbedMessage
    {
        public InstanceEmbedMessage(String guildSnowflake, String channelSnowflake, String messageSnowflake, String threadSnowflake, String auxMessageSnowflake, OffsetDateTime openedAt)
        {
            this.guildSnowflake = guildSnowflake;
            this.channelSnowflake = channelSnowflake;
            this.messageSnowflake = messageSnowflake;
            this.threadSnowflake = threadSnowflake;
            this.auxMessageSnowflake = auxMessageSnowflake;
            this.openedAt = openedAt;
            this.closedAt = null;
            this.playerlistMessageSnowflake = null;
        }
        public InstanceEmbedMessage()
        {
        }
        
        public String guildSnowflake;
        public String channelSnowflake;
        public String messageSnowflake;
        public String threadSnowflake;
        public String auxMessageSnowflake;
        public OffsetDateTime openedAt;
        public OffsetDateTime closedAt;
        /** Snowflake of the live player list message in the instance thread. */
        public String playerlistMessageSnowflake;
    }

    public static class UserMetadata
    {
        public UserMetadata()
        {
        }
        
        public String userSnowflake;
        public String[] auditEntryIds;
        public EvidenceSubmission[] evidenceSubmissions;
        
        public synchronized void addAuditEntryId(String auditEntryId)
        {
            if (auditEntryId == null)
                return;
            String[] auditEntryIds = this.auditEntryIds;
            if (auditEntryIds == null)
                this.auditEntryIds = new String[]{auditEntryId};
            else
                (this.auditEntryIds = Arrays.copyOf(auditEntryIds, auditEntryIds.length + 1))[auditEntryIds.length] = auditEntryId;
        }
        public int getUserCaseEvidenceCount()
        {
            String[] auditEntryIds = this.auditEntryIds;
            return auditEntryIds == null ? 0 : auditEntryIds.length;
        }
        public synchronized void addUserCaseEvidence(EvidenceSubmission evidenceSubmission)
        {
            if (evidenceSubmission == null)
                return;
            EvidenceSubmission[] evidenceSubmissions = this.evidenceSubmissions;
            if (evidenceSubmissions == null)
                this.evidenceSubmissions = new EvidenceSubmission[]{evidenceSubmission};
            else
                (this.evidenceSubmissions = Arrays.copyOf(evidenceSubmissions, evidenceSubmissions.length + 1))[evidenceSubmissions.length] = evidenceSubmission;
        }
    }
    public static class EvidenceSubmission
    {
        public EvidenceSubmission(String auditEntryId, String submitterSnowflake, String submitterDisplayName, OffsetDateTime submissionTime, String fileName, String filePath, String url, String proxyUrl)
        {
            this.auditEntryId = auditEntryId;
            this.submitterSnowflake = submitterSnowflake;
            this.submitterDisplayName = submitterDisplayName;
            this.submissionTime = submissionTime;
            this.fileName = fileName;
            this.filePath = filePath;
            this.url = url;
            this.proxyUrl = proxyUrl;
        }
        public EvidenceSubmission()
        {
        }
        
        public String auditEntryId;
        public String submitterSnowflake;
        public String submitterDisplayName;
        public OffsetDateTime submissionTime;
        public String fileName;
        public String filePath;
        public String url;
        public String proxyUrl;
    }

    public static class AuditEntryMetadata
    {
        public AuditEntryMetadata()
        {
        }
        
        public String guildSnowflake;
        public String channelSnowflake;
        public String messageSnowflake;
        public String threadSnowflake;
        public String auxMessageSnowflake;
        
        public GroupAuditLogEntryUnknown entry;
        
        public UniqueStrings entryTags = new UniqueStrings();
        public String entryDescription;
        public boolean entryRedacted;
        
        public String auxActorId;
        public String auxActorDisplayName;
        
        public String parentEventId;
        
        public JsonObject auxData = null;
        
        public JsonObject snapshotTargetUser = null;
        public JsonArray snapshotTargetUserGroups = null;
        public JsonObject snapshotTargetUserRepresentedGroup = null;
        
        public boolean hasTags()
        { return this.entryTags != null && !this.entryTags.isEmpty(); }
        public boolean hasDescription()
        { return this.entryDescription != null && !this.entryDescription.trim().isEmpty(); }
        
        public boolean hasMessage()
        { return this.guildSnowflake != null && this.channelSnowflake != null && this.messageSnowflake != null; }
        public String getMessageDelineated()
        { return !this.hasMessage() ? null : String.format("%s/%s/%s", this.guildSnowflake, this.channelSnowflake, this.messageSnowflake); }
        public String getMessageUrl()
        { return !this.hasMessage() ? null : String.format("https://discord.com/channels/%s/%s/%s", this.guildSnowflake, this.channelSnowflake, this.messageSnowflake); }
        
        public boolean hasChannel()
        { return this.guildSnowflake != null && this.channelSnowflake != null; }
        public String getChannelDelineated()
        { return !this.hasChannel() ? null : String.format("%s/%s", this.guildSnowflake, this.channelSnowflake); }
        public String getChannelUrl()
        { return !this.hasChannel() ? null : String.format("https://discord.com/channels/%s/%s", this.guildSnowflake, this.channelSnowflake); }
        
        public boolean hasAuxMessage()
        { return this.guildSnowflake != null && this.channelSnowflake != null && this.auxMessageSnowflake != null; }
        public String getAuxMessageDelineated()
        { return !this.hasAuxMessage() ? null : String.format("%s/%s/%s", this.guildSnowflake, this.channelSnowflake, this.auxMessageSnowflake); }
        public String getAuxMessageUrl()
        { return !this.hasAuxMessage() ? null : String.format("https://discord.com/channels/%s/%s/%s", this.guildSnowflake, this.channelSnowflake, this.auxMessageSnowflake); }
        
        public boolean hasThread()
        { return this.guildSnowflake != null && this.threadSnowflake != null; }
        public String getThreadDelineated()
        { return !this.hasThread() ? null : String.format("%s/%s", this.guildSnowflake, this.threadSnowflake); }
        public String getThreadUrl()
        { return !this.hasThread() ? null : String.format("https://discord.com/channels/%s/%s", this.guildSnowflake, this.threadSnowflake); }
        
        public boolean hasSomeUrl()
        { return this.hasThread() || this.hasMessage() || this.hasAuxMessage(); }
        public String getSomeUrl()
        { return this.hasThread() ? this.getThreadUrl() : this.hasMessage() ? this.getMessageUrl() : this.hasAuxMessage() ? this.getAuxMessageUrl() : null; }
        
        public boolean hasAuxActor()
        { return this.auxActorId != null; }
        
        public boolean hasParentEvent()
        { return this.parentEventId != null; }
        
        public boolean hasNonEmptyData()
        { JsonObject data = ScarletAuditData.json(this.entry.getData()); return data != null && !data.entrySet().isEmpty(); }
        public Map<String, Object> getData()
        { return Scarlet.GSON.fromJson(ScarletAuditData.json(this.entry.getData()), new TypeToken<Map<String, Object>>(){}.getType()); }
        public <T> T getData(String name)
        { Map<String, Object> data = this.getData(); if (data == null) return null; @SuppressWarnings("unchecked") T value = (T)data.get(name); return value; }
        public <T> T getData(Type type)
        { return Scarlet.GSON.fromJson(ScarletAuditData.json(this.entry.getData()), type); }
        public <T> T getData(Class<T> type)
        { return Scarlet.GSON.fromJson(ScarletAuditData.json(this.entry.getData()), type); }
        public <T> T getData(TypeToken<T> type)
        { return Scarlet.GSON.fromJson(ScarletAuditData.json(this.entry.getData()), type); }
        
        public JsonElement getAuxData(String name)
        { JsonObject auxData = this.auxData; return auxData == null ? null : auxData.get(name); }
        public <T> T getAuxData(String name, Function<JsonElement, T> func)
        { return this.getAuxData(name, null, func); }
        public <T> T getAuxData(String name, T fallback, Function<JsonElement, T> func)
        { JsonElement auxDataElement = this.getAuxData(name); return auxDataElement == null ? fallback : func.apply(auxDataElement); }
        JsonObject setAuxData()
        { JsonObject auxData = this.auxData; if (auxData == null) this.auxData = auxData = new JsonObject(); return auxData; }
        public void setAuxData(String name, Boolean value)
        { this.setAuxData().addProperty(name, value); }
        public void setAuxData(String name, Character value)
        { this.setAuxData().addProperty(name, value); }
        public void setAuxData(String name, Number value)
        { this.setAuxData().addProperty(name, value); }
        public void setAuxData(String name, String value)
        { this.setAuxData().addProperty(name, value); }
        public void setAuxData(String name, JsonElement value)
        { this.setAuxData().add(name, value); }
    }
    public synchronized void linkIdToSnowflake(String userId, String userSnowflake)
    {
        java.util.Objects.requireNonNull(userId, "userId");
        java.util.Objects.requireNonNull(userSnowflake, "userSnowflake");
        String previousSnowflake = this.userMetadata_getSnowflake(userId);
        String previousUser = this.globalMetadata_getSnowflakeId(userSnowflake);
        if (previousSnowflake != null && !previousSnowflake.equals(userSnowflake)
            && userId.equals(this.globalMetadata_getSnowflakeId(previousSnowflake)))
            this.globalMetadata_removeSnowflakeId(previousSnowflake);
        if (previousUser != null && !previousUser.equals(userId)
            && userSnowflake.equals(this.userMetadata_getSnowflake(previousUser)))
            this.userMetadata_setSnowflake(previousUser, null);
        this.userMetadata_setSnowflake(userId, userSnowflake);
        this.globalMetadata_setSnowflakeId(userSnowflake, userId);
        this.global.save();
        if (this.global.pollDirty()) throw persistenceFailure();
    }
    /**
     * Removes the Discord&lt;-&gt;VRChat link for the given Discord snowflake, clearing both
     * sides of the association (the global snowflake-&gt;id map and the per-user snowflake).
     * Returns the VRChat user id that was linked, or null if there was no link.
     */
    public synchronized String unlinkBySnowflake(String userSnowflake)
    {
        if (userSnowflake == null)
            return null;
        String userId = this.globalMetadata_getSnowflakeId(userSnowflake);
        this.globalMetadata_removeSnowflakeId(userSnowflake);
        if (userId != null && userSnowflake.equals(this.userMetadata_getSnowflake(userId)))
            this.userMetadata_setSnowflake(userId, null);
        this.global.save();
        if (this.global.pollDirty()) throw persistenceFailure();
        return userId;
    }
    /**
     * Removes the Discord&lt;-&gt;VRChat link for the given VRChat user id, clearing both
     * sides of the association (the per-user snowflake and the global snowflake-&gt;id map).
     * Returns the Discord snowflake that was linked, or null if there was no link.
     */
    public synchronized String unlinkById(String userId)
    {
        if (userId == null)
            return null;
        String userSnowflake = this.userMetadata_getSnowflake(userId);
        this.userMetadata_setSnowflake(userId, null);
        if (userSnowflake != null && userId.equals(this.globalMetadata_getSnowflakeId(userSnowflake)))
            this.globalMetadata_removeSnowflakeId(userSnowflake);
        this.global.save();
        if (this.global.pollDirty()) throw persistenceFailure();
        return userSnowflake;
    }

    public static class CustomEvent
    {
        public String id, typeEx;
        public String actorId, actorDisplayName;
        public String targetId, targetDisplayName;
        public OffsetDateTime timestamp;
        public JsonObject data;
    }
    public synchronized String customEvent_new(GroupAuditTypeEx typeEx, OffsetDateTime timestamp, String actorId, String actorDisplayName, String targetId, String targetDisplayName)
    {
        return this.customEvent_new(typeEx, timestamp, actorId, actorDisplayName, targetId, targetDisplayName, null);
    }
    public synchronized String customEvent_new(GroupAuditTypeEx typeEx, OffsetDateTime timestamp, String actorId, String actorDisplayName, String targetId, String targetDisplayName, JsonObject data)
    {
        String id;
        {
            UUID uuid;
            do uuid = new UUID(timestamp.toInstant().toEpochMilli(), ((0xFFFFFFFFL & (long)ThreadLocalRandom.current().nextInt()) << 32) | (0xFFFFFFFFL & (long)typeEx.id.hashCode()));
            while (new File(this.dir, "ex/"+uuid).isFile());
            id = uuid.toString();
        }
        CustomEvent ce = new CustomEvent();
        ce.id = id;
        ce.typeEx = typeEx.id;
        ce.actorId = actorId;
        ce.actorDisplayName = actorDisplayName;
        ce.targetId = targetId;
        ce.targetDisplayName = targetDisplayName;
        ce.timestamp = timestamp;
        ce.data = data;
        this.customEvent(id, ce);
        return id;
    }
    public String[] customEvent_filter(GroupAuditTypeEx typeEx, OffsetDateTime from, OffsetDateTime to)
    {
        long beginning = from == null ? Long.MIN_VALUE : from.toInstant().toEpochMilli(),
             end = to == null ? Long.MAX_VALUE : to.toInstant().toEpochMilli();
        int typeHc = typeEx == null ? 0 : typeEx.id.hashCode();
        return new File(this.dir, "ex").list((dir, name) ->
        {
            try
            {
                UUID uuid = UUID.fromString(name);
                long epochMillis = uuid.getMostSignificantBits();
                int hc = (int)(uuid.getLeastSignificantBits() & 0xFFFFFFFFL);
                return (typeHc == 0 || typeHc == hc) && beginning <= epochMillis && epochMillis <= end;
            }
            catch (Exception ex)
            {
            }
            return false;
        });
    }

}

package net.sybyline.scarlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.reflect.TypeToken;

import net.sybyline.scarlet.util.LRUMap;
import net.sybyline.scarlet.util.MiscUtils;

public class ScarletJsonCache<T>
{

    public static final long
        ALWAYS_FETCH = Long.MAX_VALUE,
        ALWAYS_PREFER_CACHED = Long.MIN_VALUE;

    public @FunctionalInterface interface Populator<T>
    {
        T populate(String id) throws Exception;
        static <T> Populator<T> of(Callable<T> callable)
        {
            return callable == null ? null : $ -> callable.call();
        }
    }

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/JsonCache");

    static final File caches = new File(Scarlet.dir, "caches");

    public ScarletJsonCache(String kind, Class<T> type)
    {
        this(kind, TypeToken.get(type));
    }

    public ScarletJsonCache(String kind, TypeToken<T> type)
    {
        this(caches, kind, type);
    }

    ScarletJsonCache(File cacheRoot, String kind, TypeToken<T> type)
    {
        if (!safeName(kind)) throw new IllegalArgumentException("Invalid cache kind");
        this.cacheRoot = cacheRoot;
        this.kind = kind;
        this.type = type;
        this.dir = new File(cacheRoot, kind);
        this.entries = LRUMap.ofSynchronized();
        this.known404s = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.load404s();
    }

    private final String kind;
    private final File cacheRoot;
    private final TypeToken<T> type;
    private final File dir;
    private final Map<String, Entry> entries;
    private final Set<String> known404s;

    public boolean is404(String id)
    {
        return !safeKey(id) || this.known404s.contains(id);
    }

    public boolean add404(String id)
    {
        if (!safeKey(id) || !this.known404s.add(id))
            return false;
        this.save404s();
        return true;
    }

    public boolean remove404(String id)
    {
        if (!safeKey(id) || !this.known404s.remove(id))
            return false;
        this.save404s();
        return true;
    }

    void load404s()
    {
        if (!Files.exists(this.dir.toPath().resolve("known_404s.json"), LinkOption.NOFOLLOW_LINKS))
            return;
        String[] known404sa;
        try (Reader reader = this.reader("known_404s"))
        {
            known404sa = Scarlet.GSON_PRETTY.fromJson(reader, String[].class);
        }
        catch (Exception ex)
        {
            LOG.error("Exception reading knows 404s JSON of kind `"+this.kind+"`");
            return;
        }
        if (known404sa != null)
            Arrays.stream(known404sa).filter(ScarletJsonCache::safeKey).forEach(this.known404s::add);
    }

    void save404s()
    {
        try (Writer writer = this.writer("known_404s"))
        {
            Scarlet.GSON_PRETTY.toJson(this.known404s, Set.class, writer);
        }
        catch (Exception ex)
        {
            LOG.error("Exception writing knows 404s JSON of kind `"+this.kind+"`");
        }
    }

    public T get(String id)
    {
        return this.get(id, Long.MIN_VALUE, null);
    }
    public T get(String id, long minEpoch)
    {
        return this.get(id, minEpoch, null);
    }
    public T getFrom(String id, long minEpoch, Callable<T> populate)
    {
        return this.get(id, minEpoch, Populator.of(populate));
    }
    public T get(String id, long minEpoch, Populator<T> populate)
    {
        Entry entry = this.getEntry(id, minEpoch, populate);
        return entry == null ? null : entry.value;
    }

    public Entry getEntry(String id)
    {
        return this.getEntry(id, Long.MIN_VALUE, null);
    }
    public Entry getEntry(String id, long minEpoch)
    {
        return this.getEntry(id, minEpoch, null);
    }
    public Entry getEntryFrom(String id, long minEpoch, Callable<T> populate)
    {
        return this.getEntry(id, minEpoch, Populator.of(populate));
    }
    public Entry getEntry(String id, long minEpoch, Populator<T> populate)
    {
        if (!safeKey(id))
            return null;
        return this.entries.compute(id, (id0, entry) ->
        {
            if (entry == null)
            {
                entry = this.loadEntry(id0, minEpoch);
            }
            else if (entry.modified >= minEpoch)
            {
                return entry;
            }
            if (populate != null) try
            {
                T value = populate.populate(id0);
                if (value != null)
                {
                    if (entry == null)
                    {
                        entry = new Entry(id0, value, 0L);
                    }
                    else
                    {
                        entry.value = value;
                    }
                    entry.save();
                }
            }
            catch (Exception ex)
            {
                LOG.error("Exception populating JSON cache of kind `"+this.kind+"`, id `"+id0+"`");
                
            }
            return entry;
        });
    }

    public void put(String id, T value)
    {
        if (!safeKey(id))
            return;
        if (value == null)
            return;
        Entry entry = this.entries.computeIfAbsent(id, Entry::new);
        entry.value = value;
        entry.save();
    }

    private Entry loadEntry(String id, long minEpoch)
    {
        if (!safeKey(id))
            return null;
        if (!Files.exists(this.dir.toPath().resolve(id + ".json"), LinkOption.NOFOLLOW_LINKS))
            return null;
        T value;
        long modified;
        try
        {
            File file = this.cacheFile(id, false).toFile();
            if (!file.isFile()) return null;
            modified = MiscUtils.lastModified(file);
            if (modified < minEpoch)
                return null;
            try (Reader reader = this.reader(id))
            {
                value = Scarlet.GSON_PRETTY.fromJson(reader, this.type);
            }
        }
        catch (Exception ex)
        {
            LOG.error("Exception reading cached JSON of kind `"+this.kind+"`, id `"+id+"`");
            return null;
        }
        return new Entry(id, value, modified);
    }

    private void saveEntry(Entry entry)
    {
        if (entry == null)
            return;
        try
        {
            Path file = this.cacheFile(entry.id, true);
            try (Writer writer = this.writer(entry.id))
            {
                Scarlet.GSON_PRETTY.toJson(entry.value, this.type.getType(), writer);
            }
            entry.modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
        }
        catch (Exception ex)
        {
            LOG.error("Exception writing cached JSON of kind `"+this.kind+"`, id `"+entry.id+"`");
        }
    }

    private static boolean safeKey(String id)
    {
        return safeName(id) && !"known_404s".equalsIgnoreCase(id);
    }

    private static boolean safeName(String name)
    {
        if (name == null || !name.matches("[A-Za-z0-9_-][A-Za-z0-9_.#-]{0,199}")) return false;
        String base = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        return !base.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");
    }

    private Path cacheFile(String name, boolean create) throws IOException
    {
        Path root = this.cacheRoot.toPath().toAbsolutePath().normalize();
        if (create)
        {
            Path parent = root.getParent();
            if (parent == null || !parent.equals(parent.toRealPath()))
                throw new IOException("Cache parent must not be an alias");
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root);
        }
        requireDirectory(root);
        Path directory = root.resolve(this.kind);
        if (create && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
        requireDirectory(directory);
        Path file = directory.resolve(name + ".json");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.equals(file.toRealPath())))
            throw new IOException("Cache entry must be an exact regular file");
        return file;
    }

    private static void requireDirectory(Path directory) throws IOException
    {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.toRealPath()))
            throw new IOException("Cache directory must not be an alias");
    }

    private Reader reader(String name) throws IOException
    {
        return new InputStreamReader(Channels.newInputStream(Files.newByteChannel(this.cacheFile(name, false),
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)), StandardCharsets.UTF_8);
    }

    private Writer writer(String name) throws IOException
    {
        return new OutputStreamWriter(Channels.newOutputStream(Files.newByteChannel(this.cacheFile(name, true),
            StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS)), StandardCharsets.UTF_8);
    }

    public class Entry
    {
        Entry(String id)
        {
            this(id, null, 0L);
        }
        Entry(String id, T value, long modified)
        {
            this.id = id;
            this.value = value;
            this.modified = modified;
        }
        private final String id;
        private T value;
        private long modified;
        public String id()
        {
            return this.id;
        }
        public T value()
        {
            return this.value;
        }
        public long modified()
        {
            return this.modified;
        }
        public void save()
        {
            ScarletJsonCache.this.saveEntry(this);
        }
    }

}

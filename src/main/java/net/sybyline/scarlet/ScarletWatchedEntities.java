package net.sybyline.scarlet;

import java.awt.Color;
import net.sybyline.scarlet.util.FileBackups;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// commons-csv removed: CSV parsing inlined as MiscUtils.parseExcelCsv()
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dv8tion.jda.api.EmbedBuilder;
import net.sybyline.scarlet.server.discord.DEnum;
import net.sybyline.scarlet.util.Func;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.Translator;
import net.sybyline.scarlet.util.UniqueStrings;

public class ScarletWatchedEntities<E>
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/WatchedEntities");

    public ScarletWatchedEntities(File watchedEntitiesFile, String idPrefix_, Func.V3.NE<E, String, EmbedBuilder> builder)
    {
        this(watchedEntitiesFile, id -> id.startsWith(idPrefix_), builder);
    }
    public ScarletWatchedEntities(File watchedEntitiesFile, Pattern idPattern, Func.V3.NE<E, String, EmbedBuilder> builder)
    {
        this(watchedEntitiesFile, id -> idPattern.matcher(id).matches(), builder);
    }
    public ScarletWatchedEntities(File watchedEntitiesFile, Predicate<String> idValid, Func.V3.NE<E, String, EmbedBuilder> builder)
    {
        this.watchedEntitiesFile = watchedEntitiesFile;
        this.idValid = idValid;
        this.watchedEntities = new ConcurrentHashMap<>();
        this.builder = builder;
        if (!this.load())
            throw new IllegalStateException("Watched entities could not be loaded. Original data was preserved; restore or repair the file before restarting.");
    }

    final File watchedEntitiesFile;
    final Predicate<String> idValid;
    Map<String, WatchedEntity> watchedEntities;
    final Func.V3.NE<E, String, EmbedBuilder> builder;

    public static class WatchedEntity implements Comparable<WatchedEntity>
    {
        public String id;
        public Type type = Type.UNKNOWN;
        public static enum Type implements DEnum.DEnumString<Type>
        {
            UNKNOWN(null),
            
            MALICIOUS(new Color(235,  95,  95)),
            NUISANCE(new Color(235, 165,  65)),
            
            COMMUNITY(new Color( 95, 200, 120)),
            AFFILIATED(new Color( 95, 165, 235)),
            
            OTHER(null),
            ;
            public final Color text_color;
            private Type(Color text_color)
            {
                this.text_color = text_color;
            }
            @Override
            public String value()
            {
                return this.name();
            }
            @Override
            public String display()
            {
                return this.name();
            }
        }
        public UniqueStrings tags = new UniqueStrings();
        public int priority = 0;
        public boolean critical = false;
        public boolean silent = false;
        public String message = null;
        public String notes = null;
        // Advisory translation state (see ScarletWatchedGroups). Originals of the translated
        // free-text fields are kept so a translation can be restored losslessly; ids, tags and
        // types are never translated. Null when no translation is applied.
        public String messageOriginal = null;
        public String notesOriginal = null;
        public String translatedLang = null;

        public <E> EmbedBuilder embed(ScarletWatchedEntities<E> context, E entity)
        {
            EmbedBuilder builder = new EmbedBuilder();
            context.builder.invoke(entity, this.id, builder);
            return builder
                .addField("Id", "`"+this.id+"`", false)
                .addField("Watch type", this.type == null ? "`none`" : this.type.name(), false)
                .addField("Priority", "`"+this.priority+"`", true)
                .addField("Critical", this.critical ? "`true`" : "`false`", true)
                .addField("Silent", this.silent ? "`true`" : "`false`", true)
                .addField("Message", this.message == null ? "`none`" : this.message, false)
                .addField("Tags", this.tags.isEmpty() ? "`none`" : this.tags
                    .strings()
                    .stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", ")), false)
                .addField("Notes", this.notes == null ? "`none`" : this.notes, false)
            ;
        }

        @Override
        public int compareTo(WatchedEntity o)
        {
            int cmp;
            if ((cmp = Integer.compare(this.priority, o.priority)) != 0) return cmp;
            if ((cmp = Boolean.compare(this.critical, o.critical)) != 0) return cmp;
            if ((cmp = Boolean.compare(this.silent, o.silent)) != 0) return cmp;
            return 0;
        }

        @Override
        public int hashCode()
        {
            int hash = 1;
            hash = 31 * hash + Objects.hashCode(this.id);
            hash = 31 * hash + Objects.hashCode(this.type);
            hash = 31 * hash + Objects.hashCode(this.tags);
            hash = 31 * hash + Integer.hashCode(this.priority);
            hash = 31 * hash + Boolean.hashCode(this.critical);
            hash = 31 * hash + Boolean.hashCode(this.silent);
            hash = 31 * hash + Objects.hashCode(this.message);
            return hash;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) return true;
            if (!(obj instanceof WatchedEntity)) return false;
            WatchedEntity other = (WatchedEntity)obj;
            if (!Objects.equals(this.id, other.id)) return false;
            if (!Objects.equals(this.type, other.type)) return false;
            if (!Objects.equals(this.tags, other.tags)) return false;
            if (!Objects.equals(this.priority, other.priority)) return false;
            if (!Objects.equals(this.critical, other.critical)) return false;
            if (!Objects.equals(this.silent, other.silent)) return false;
            if (!Objects.equals(this.message, other.message)) return false;
            return true;
        }

        @Override
        public String toString()
        {
            return this.id;
        }
        
    }

    @SuppressWarnings("resource")
    public boolean importLegacyCSV(Reader reader, boolean overwrite) throws IOException
    {
        List<WatchedEntity> importedWatchedEntities = new ArrayList<>();
        for (String[] record : MiscUtils.parseExcelCsv(reader))
        {
            WatchedEntity watchedEntity = new WatchedEntity();
            watchedEntity.id = record[0];
            switch (record[1])
            {
            case "TOXIC":   watchedEntity.type = WatchedEntity.Type.MALICIOUS;  break;
            case "WATCH":   watchedEntity.type = WatchedEntity.Type.NUISANCE;   break;
            case "SPECIAL": watchedEntity.type = WatchedEntity.Type.COMMUNITY;  break;
            case "PARTNER": watchedEntity.type = WatchedEntity.Type.AFFILIATED; break;
            default:        watchedEntity.type = WatchedEntity.Type.OTHER;      break;
            }

            Arrays
                .stream(record[5].split("[,;/\\|]"))
                .filter($ -> !$.isEmpty())
                .map(String::toLowerCase)
                .forEach(watchedEntity.tags.strings()::add);
            watchedEntity.critical = Boolean.parseBoolean(record[3]);
            watchedEntity.message = record[4];
            importedWatchedEntities.add(watchedEntity);
        }
        return this.importWatchedEntities(importedWatchedEntities, overwrite);
    }

    public boolean importJson(Reader reader, boolean overwrite) throws IOException
    {
        WatchedEntity[] watchedEntitiesArray = Scarlet.GSON_PRETTY.fromJson(reader, WatchedEntity[].class);
        List<WatchedEntity> importedWatchedEntities = Arrays.asList(watchedEntitiesArray);
        return this.importWatchedEntities(importedWatchedEntities, overwrite);
    }

    public boolean exportJson(Writer writer) throws IOException
    {
        WatchedEntity[] watchedEntitiesArray = this.watchedEntities.values().toArray(new WatchedEntity[0]);
        Scarlet.GSON_PRETTY.toJson(watchedEntitiesArray, WatchedEntity[].class, writer);
        return true;
    }

    public boolean importWatchedEntities(List<WatchedEntity> importedWatchedEntities, boolean overwrite)
    {
        for (WatchedEntity watchedEntity : importedWatchedEntities)
        {
            if (watchedEntity.id != null && this.idValid.test(watchedEntity.id))
            {
                if (overwrite)
                {
                    this.watchedEntities.put(watchedEntity.id, watchedEntity);
                }
                else
                {
                    this.watchedEntities.putIfAbsent(watchedEntity.id, watchedEntity);
                }
            }
        }
        this.save();
        return true;
    }

    public WatchedEntity getWatchedEntity(String entityId)
    {
        return this.watchedEntities.get(entityId);
    }

    public boolean addWatchedEntity(String entityId, WatchedEntity watchedEntity)
    {
        if (watchedEntity == null)
            return false;
        this.watchedEntities.put(entityId, watchedEntity);
        this.save();
        return true;
    }

    public boolean removeWatchedEntity(String entityId)
    {
        if (this.watchedEntities.remove(entityId) == null)
            return false;
        this.save();
        return true;
    }

    private boolean loadFailed;

    public synchronized boolean load()
    {
        boolean failedBefore = this.loadFailed;
        this.loadFailed = true;
        try
        {
            if (!ScarletData.existingRecord(this.watchedEntitiesFile))
            {
                if (failedBefore) throw new java.io.IOException("Previously unreadable watched entities must be restored or repaired before reload");
                this.loadFailed = false;
                return this.save();
            }
        }
        catch (Exception ex) { LOG.error("Could not inspect watched entity storage", ex); return false; }
        WatchedEntity[] watchedEntitiesArray;
        try (FileReader fr = new FileReader(this.watchedEntitiesFile, java.nio.charset.StandardCharsets.UTF_8))
        {
            watchedEntitiesArray = Scarlet.GSON_PRETTY.fromJson(fr, WatchedEntity[].class);
            if (watchedEntitiesArray == null) throw new java.io.IOException("Existing watched entities must contain a JSON array");
        }
        catch (Exception ex)
        {
            LOG.error("Exception loading watched entities", ex);
            return false;
        }
        Map<String, WatchedEntity> watchedEntities = new ConcurrentHashMap<>();
        if (watchedEntitiesArray != null)
        for (WatchedEntity watchedEntity : watchedEntitiesArray)
            if (watchedEntity != null && watchedEntity.id != null && this.idValid.test(watchedEntity.id))
                watchedEntities.put(watchedEntity.id, watchedEntity);
        this.watchedEntities = watchedEntities;
        this.loadFailed = false;
        return true;
    }

    public synchronized boolean save()
    {
        if (this.loadFailed)
        {
            LOG.error("Watched entity storage was not loaded; refusing to overwrite the original data");
            return false;
        }
        WatchedEntity[] watchedEntitiesArray = this.watchedEntities.values().toArray(new WatchedEntity[0]);
        try (FileBackups.AtomicWriter fw = FileBackups.writer(this.watchedEntitiesFile))
        {
            Scarlet.GSON_PRETTY.toJson(watchedEntitiesArray, WatchedEntity[].class, fw);
            fw.commit();
        }
        catch (Exception ex)
        {
            LOG.error("Exception saving watched entities", ex);
            return false;
        }
        return true;
    }

    /** Number of watched entities currently showing a translated advisory. */
    public int countTranslated()
    {
        int n = 0;
        for (WatchedEntity e : this.watchedEntities.values())
            if (e.translatedLang != null || e.messageOriginal != null || e.notesOriginal != null)
                n++;
        return n;
    }

    /**
     * Translates every watched entity's advisory ({@code message} + {@code notes}) into
     * {@code targetLang} via a LibreTranslate-compatible endpoint, preserving originals so
     * {@link #restoreAdvisories()} can undo it. Ids, tags and types are never translated.
     * Re-running translates from the preserved original. Returns the number changed.
     */
    public int translateAdvisories(String endpoint, String apiKey, String targetLang) throws java.io.IOException
    {
        int changed = 0;
        java.io.IOException failure = null;
        try
        {
            for (WatchedEntity e : this.watchedEntities.values())
            {
                boolean any = false;
                String srcMsg = e.messageOriginal != null ? e.messageOriginal : e.message;
                if (srcMsg != null && !srcMsg.trim().isEmpty())
                {
                    String t = Translator.translate(endpoint, apiKey, targetLang, srcMsg);
                    if (t != null && !t.equals(e.message))
                    {
                        if (e.messageOriginal == null) e.messageOriginal = e.message;
                        e.message = t;
                        any = true;
                    }
                }
                String srcNotes = e.notesOriginal != null ? e.notesOriginal : e.notes;
                if (srcNotes != null && !srcNotes.trim().isEmpty())
                {
                    String t = Translator.translate(endpoint, apiKey, targetLang, srcNotes);
                    if (t != null && !t.equals(e.notes))
                    {
                        if (e.notesOriginal == null) e.notesOriginal = e.notes;
                        e.notes = t;
                        any = true;
                    }
                }
                if (any)
                {
                    e.translatedLang = targetLang;
                    changed++;
                }
            }
        }
        catch (java.io.IOException ex)
        {
            failure = ex;
        }
        if (changed > 0)
            this.save();
        if (failure != null)
            throw failure;
        return changed;
    }

    /** Restores original (pre-translation) advisory text for every watched entity. Returns count restored. */
    public int restoreAdvisories()
    {
        int restored = 0;
        for (WatchedEntity e : this.watchedEntities.values())
        {
            boolean any = false;
            if (e.messageOriginal != null) { e.message = e.messageOriginal; e.messageOriginal = null; any = true; }
            if (e.notesOriginal != null)   { e.notes   = e.notesOriginal;   e.notesOriginal   = null; any = true; }
            if (e.translatedLang != null)  { e.translatedLang = null; any = true; }
            if (any) restored++;
        }
        if (restored > 0)
            this.save();
        return restored;
    }

}

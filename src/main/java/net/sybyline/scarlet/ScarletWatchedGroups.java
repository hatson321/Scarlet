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
import java.util.stream.Collectors;

// commons-csv removed: CSV parsing inlined as MiscUtils.parseExcelCsv()
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.vrchatapi.model.Group;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.sybyline.scarlet.server.discord.DEnum;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.Translator;
import net.sybyline.scarlet.util.UniqueStrings;

public class ScarletWatchedGroups
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/WatchedGroups");

    public ScarletWatchedGroups(File watchedGroupsFile)
    {
        this.watchedGroupsFile = watchedGroupsFile;
        this.watchedGroups = new ConcurrentHashMap<>();
        if (!this.load())
            throw new IllegalStateException("Watched groups could not be loaded. Original data was preserved; restore or repair the file before restarting.");
    }

    final File watchedGroupsFile;
    Map<String, WatchedGroup> watchedGroups;
    private boolean loadFailed;

    public static class WatchedGroup implements Comparable<WatchedGroup>
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
        // Advisory translation state. When advisories are translated on request, the English
        // originals are preserved here so the translation can be restored losslessly; the group's
        // id, type, tags and name are never translated. Null when no translation is applied.
        public String messageOriginal = null;
        public String notesOriginal = null;
        public String translatedLang = null;

        public EmbedBuilder embed(Group group)
        {
            String code = group != null ? (group.getShortCode()+"."+group.getDiscriminator()) : null,
                   icon = group != null ? group.getIconUrl() : null,
                   name = group != null ? MarkdownSanitizer.escape(group.getName()) : this.id,
                   thumbnail = group != null ? group.getBannerUrl() : "https://assets.vrchat.com/www/groups/default_banner.png";
            return new EmbedBuilder()
                .setAuthor(code, null, icon)
                .setTitle(name, "https://vrchat.com/home/group/"+this.id)
                .setThumbnail(thumbnail)
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
        public int compareTo(WatchedGroup o)
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
            if (!(obj instanceof WatchedGroup)) return false;
            WatchedGroup other = (WatchedGroup)obj;
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
        List<WatchedGroup> importedWatchedGroups = new ArrayList<>();
        for (String[] record : MiscUtils.parseExcelCsv(reader))
        {
            WatchedGroup watchedGroup = new WatchedGroup();
            watchedGroup.id = record[0];
            switch (record[1])
            {
            case "TOXIC":   watchedGroup.type = WatchedGroup.Type.MALICIOUS;  break;
            case "WATCH":   watchedGroup.type = WatchedGroup.Type.NUISANCE;   break;
            case "SPECIAL": watchedGroup.type = WatchedGroup.Type.COMMUNITY;  break;
            case "PARTNER": watchedGroup.type = WatchedGroup.Type.AFFILIATED; break;
            default:        watchedGroup.type = WatchedGroup.Type.OTHER;      break;
            }

            Arrays
                .stream(record[5].split("[,;/\\|]"))
                .filter($ -> !$.isEmpty())
                .map(String::toLowerCase)
                .forEach(watchedGroup.tags.strings()::add);
            watchedGroup.critical = Boolean.parseBoolean(record[3]);
            watchedGroup.message = record[4];
            importedWatchedGroups.add(watchedGroup);
        }
        return this.importWatchedGroups(importedWatchedGroups, overwrite);
    }

    public boolean importJson(Reader reader, boolean overwrite) throws IOException
    {
        WatchedGroup[] watchedGroupsArray = Scarlet.GSON_PRETTY.fromJson(reader, WatchedGroup[].class);
        List<WatchedGroup> importedWatchedGroups = Arrays.asList(watchedGroupsArray);
        return this.importWatchedGroups(importedWatchedGroups, overwrite);
    }

    public boolean exportJson(Writer writer) throws IOException
    {
        WatchedGroup[] watchedGroupsArray = this.watchedGroups.values().toArray(new WatchedGroup[0]);
        Scarlet.GSON_PRETTY.toJson(watchedGroupsArray, WatchedGroup[].class, writer);
        return true;
    }

    public boolean importWatchedGroups(List<WatchedGroup> importedWatchedGroups, boolean overwrite)
    {
        for (WatchedGroup watchedGroup : importedWatchedGroups)
        {
            if (watchedGroup.id != null && watchedGroup.id.startsWith("grp_"))
            {
                if (overwrite)
                {
                    this.watchedGroups.put(watchedGroup.id, watchedGroup);
                }
                else
                {
                    this.watchedGroups.putIfAbsent(watchedGroup.id, watchedGroup);
                }
            }
        }
        this.save();
        return true;
    }

    public WatchedGroup getWatchedGroup(String groupId)
    {
        return this.watchedGroups.get(groupId);
    }

    public boolean addWatchedGroup(String groupId, WatchedGroup watchedGroup)
    {
        if (watchedGroup == null)
            return false;
        this.watchedGroups.put(groupId, watchedGroup);
        this.save();
        return true;
    }

    public boolean removeWatchedGroup(String groupId)
    {
        if (this.watchedGroups.remove(groupId) == null)
            return false;
        this.save();
        return true;
    }

    public synchronized boolean load()
    {
        boolean failedBefore = this.loadFailed;
        this.loadFailed = true;
        try
        {
            if (!ScarletData.existingRecord(this.watchedGroupsFile))
            {
                if (failedBefore) throw new java.io.IOException("Previously unreadable watched groups must be restored or repaired before reload");
                this.loadFailed = false;
                return this.save();
            }
        }
        catch (Exception ex) { LOG.error("Could not inspect watched groups storage", ex); return false; }
        WatchedGroup[] watchedGroupsArray;
        try (FileReader fr = new FileReader(this.watchedGroupsFile, java.nio.charset.StandardCharsets.UTF_8))
        {
            watchedGroupsArray = Scarlet.GSON_PRETTY.fromJson(fr, WatchedGroup[].class);
            if (watchedGroupsArray == null) throw new java.io.IOException("Existing watched groups must contain a JSON array");
        }
        catch (Exception ex)
        {
            LOG.error("Exception loading watched groups", ex);
            return false;
        }
        Map<String, WatchedGroup> watchedGroups = new ConcurrentHashMap<>();
        if (watchedGroupsArray != null)
        for (WatchedGroup watchedGroup : watchedGroupsArray)
            if (watchedGroup != null && watchedGroup.id != null && watchedGroup.id.startsWith("grp_"))
                watchedGroups.put(watchedGroup.id, watchedGroup);
        this.watchedGroups = watchedGroups;
        this.loadFailed = false;
        return true;
    }

    public synchronized boolean save()
    {
        if (this.loadFailed)
        {
            LOG.error("Watched groups storage was not loaded; refusing to overwrite the original data");
            return false;
        }
        WatchedGroup[] watchedGroupsArray = this.watchedGroups.values().toArray(new WatchedGroup[0]);
        try (FileBackups.AtomicWriter fw = FileBackups.writer(this.watchedGroupsFile))
        {
            Scarlet.GSON_PRETTY.toJson(watchedGroupsArray, WatchedGroup[].class, fw);
            fw.commit();
        }
        catch (Exception ex)
        {
            LOG.error("Exception saving watched groups", ex);
            return false;
        }
        return true;
    }

    /** Number of watched groups whose advisory is currently showing a translation. */
    public int countTranslated()
    {
        int n = 0;
        for (WatchedGroup g : this.watchedGroups.values())
            if (g.translatedLang != null || g.messageOriginal != null || g.notesOriginal != null)
                n++;
        return n;
    }

    /**
     * Translates every watched group's advisory text (the {@code message} and {@code notes} fields)
     * into {@code targetLang} via a LibreTranslate-compatible endpoint, preserving the English
     * originals so {@link #restoreAdvisories()} can undo it. Group ids, types, tags and names are
     * never translated. Re-running always translates from the preserved original, so switching
     * languages never compounds. Saves once at the end. Returns the number of groups changed.
     *
     * @throws IOException if the endpoint fails; already-translated groups up to that point are kept.
     */
    public int translateAdvisories(String endpoint, String apiKey, String targetLang) throws IOException
    {
        int changed = 0;
        IOException failure = null;
        try
        {
            for (WatchedGroup g : this.watchedGroups.values())
            {
                boolean any = false;
                String srcMsg = g.messageOriginal != null ? g.messageOriginal : g.message;
                if (srcMsg != null && !srcMsg.trim().isEmpty())
                {
                    String t = Translator.translate(endpoint, apiKey, targetLang, srcMsg);
                    if (t != null && !t.equals(g.message))
                    {
                        if (g.messageOriginal == null) g.messageOriginal = g.message;
                        g.message = t;
                        any = true;
                    }
                }
                String srcNotes = g.notesOriginal != null ? g.notesOriginal : g.notes;
                if (srcNotes != null && !srcNotes.trim().isEmpty())
                {
                    String t = Translator.translate(endpoint, apiKey, targetLang, srcNotes);
                    if (t != null && !t.equals(g.notes))
                    {
                        if (g.notesOriginal == null) g.notesOriginal = g.notes;
                        g.notes = t;
                        any = true;
                    }
                }
                if (any)
                {
                    g.translatedLang = targetLang;
                    changed++;
                }
            }
        }
        catch (IOException ex)
        {
            failure = ex;
        }
        if (changed > 0)
            this.save();
        if (failure != null)
            throw failure;
        return changed;
    }

    /**
     * Restores the original (pre-translation) advisory text for every watched group, dropping the
     * translation. Saves once if anything changed. Returns the number of groups restored.
     */
    public int restoreAdvisories()
    {
        int restored = 0;
        for (WatchedGroup g : this.watchedGroups.values())
        {
            boolean any = false;
            if (g.messageOriginal != null) { g.message = g.messageOriginal; g.messageOriginal = null; any = true; }
            if (g.notesOriginal != null)   { g.notes   = g.notesOriginal;   g.notesOriginal   = null; any = true; }
            if (g.translatedLang != null)  { g.translatedLang = null; any = true; }
            if (any) restored++;
        }
        if (restored > 0)
            this.save();
        return restored;
    }

}

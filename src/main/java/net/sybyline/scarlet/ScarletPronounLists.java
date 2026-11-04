package net.sybyline.scarlet;

import java.io.File;
import net.sybyline.scarlet.util.FileBackups;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the two user-editable pronoun list files stored in Scarlet's data
 * directory alongside settings.json:
 *
 * <ul>
 *   <li>{@code good_pronoun.json} — strings that are always accepted as
 *       legitimate, even if they would otherwise trigger the heuristic rules.
 *       Add entries here to suppress false positives.</li>
 *   <li>{@code bad_pronoun.json} — strings that are always flagged as
 *       suspicious, regardless of the heuristic rules.
 *       Add entries here to catch specific known-bad values.</li>
 * </ul>
 *
 * <p>Both files contain a simple JSON array of lowercase strings, e.g.:
 * <pre>["she/her","he/him","they/them"]</pre>
 *
 * <p>On first run the files are created with sensible defaults so users have
 * an example to build on. The lists are reloaded every time
 * {@link PronounValidator#isFlagged(String)} is called via the shared
 * {@link ScarletPronounLists} instance held by {@link Scarlet}.
 *
 * <p>The files can be opened directly from the Settings tab using the
 * "Edit good_pronoun.json" / "Edit bad_pronoun.json" buttons, which open the
 * files in the system's default editor (Notepad on Windows, the default text
 * editor on Linux/macOS). After saving the file, Scarlet will pick up the
 * changes automatically on the next player join — no restart needed.
 */
public class ScarletPronounLists
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/PronounLists");

    // ── Default content written on first run ───────────────────────────────────

    private static final String[] DEFAULT_GOOD = {
        // Binary + singular they + it
        "she/her", "she/her/hers",
        "he/him", "he/him/his",
        "they/them", "they/them/theirs", "they/them/themself",
        "it/its", "one/one's",
        // Common interchangeable mixes
        "she/they", "he/they", "they/she", "they/he", "she/he", "he/she",
        "he/him or she/her", "she/her or they/them", "he/him or they/them",
        // Catch-alls people actually write
        "any", "any/all", "all/any", "any pronouns", "any normative",
        "ask", "ask/me", "ask me", "no pronouns", "none", "use my name",
        // Popular neopronouns (nominative/accusative/possessive)
        "ae/aer", "ae/aer/aers",
        "ey/em", "ey/em/eir", "ey/em/eirs",
        "e/em/eir", "e/em/es",
        "fae/faer", "fae/faer/faers",
        "xe/xem", "xe/xem/xyr", "xe/xem/xyrs", "xe/xir", "xe/xyr",
        "ze/hir", "ze/hir/hirs", "ze/zir", "ze/zir/zirs", "zie/zir",
        "co/cos", "hu/hum", "ne/nem", "ne/nir",
        "per/per", "per/pers", "thon/thons",
        "ve/ver", "ve/ver/vers", "vi/vir", "vi/vim",
        "zhe/zher", "ki/kin"
    };

    private static final String[] DEFAULT_BAD = {
        // Common username-style / non-answer abuses seen in VRChat
        "nick/her",
        "nick/him",
        "call/me/daddy",
        "not/specified",
        "i/dont/care",
        "who/cares",
        // Slurs and harassment commonly weaponised in the pronouns field.
        // This is a user-editable deny-list — adjust it to taste. Self-harm and
        // violence directives ("kill/yourself", "kys", …) are detected in code
        // and do not need to be listed here.
        "tranny",
        "faggot",
        "nigger",
        "retard",
        "kike",
        "chink",
        "spic"
    };

    // ──────────────────────────────────────────────────────────────────────────

    public ScarletPronounLists(File goodFile, File badFile)
    {
        this.goodFile = goodFile;
        this.badFile  = badFile;
        this.goodSet  = Collections.synchronizedSet(new LinkedHashSet<>());
        this.badSet   = Collections.synchronizedSet(new LinkedHashSet<>());
        this.load();
    }

    public final File goodFile;
    public final File badFile;

    /** Exact lowercase matches that are always accepted (user-maintained). */
    final Set<String> goodSet;
    /** Exact lowercase matches that are always flagged (user-maintained). */
    final Set<String> badSet;

    // ── Public accessors used by PronounValidator ──────────────────────────────

    public boolean isKnownGood(String normalised)
    {
        return this.goodSet.contains(normalised);
    }

    public boolean isKnownBad(String normalised)
    {
        return this.badSet.contains(normalised);
    }

    /**
     * Returns {@code true} if any deny-list entry appears anywhere in the given
     * field (whitespace-insensitive substring match), so embedded entries are
     * caught — e.g. a deny-list entry {@code "call/me/daddy"} flags the field
     * {@code "she/her call me daddy"}. Used by {@link PronounValidator}.
     */
    public boolean containsKnownBad(String normalised)
    {
        if (normalised == null || normalised.isEmpty() || this.badSet.isEmpty())
            return false;
        String haystack = despace(normalised);
        synchronized (this.badSet)
        {
            for (String bad : this.badSet)
            {
                if (bad.isEmpty())
                    continue;
                if (haystack.contains(despace(bad)))
                    return true;
            }
        }
        return false;
    }

    private static String despace(String s)
    {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c))
                sb.append(c);
        }
        return sb.toString();
    }

    // ── Load / save ────────────────────────────────────────────────────────────

    /**
     * Loads both files. Creates them with defaults if they don't yet exist.
     * Safe to call at any time; updates the in-memory sets atomically.
     */
    public void load()
    {
        this.goodSet.clear();
        this.badSet.clear();

        this.loadInto(this.goodFile, this.goodSet, DEFAULT_GOOD, "good_pronoun");
        this.loadInto(this.badFile,  this.badSet,  DEFAULT_BAD,  "bad_pronoun");

        LOG.info("Pronoun lists loaded: {} good, {} bad entries.",
                 this.goodSet.size(), this.badSet.size());
    }

    private void loadInto(File file, Set<String> target,
                           String[] defaults, String label)
    {
        if (!file.isFile())
        {
            // First run — write defaults so the user has something to edit
            this.saveFile(file, defaults, label);
            target.addAll(Arrays.asList(defaults));
            return;
        }
        try (FileReader fr = new FileReader(file, java.nio.charset.StandardCharsets.UTF_8))
        {
            String[] arr = Scarlet.GSON_PRETTY.fromJson(fr, String[].class);
            if (arr != null)
                for (String s : arr)
                    if (s != null && !s.trim().isEmpty())
                        target.add(s.trim().toLowerCase(java.util.Locale.ROOT));
        }
        catch (Exception ex)
        {
            LOG.error("Exception loading {}.json — using defaults", label, ex);
            target.addAll(Arrays.asList(defaults));
        }
    }

    private void saveFile(File file, String[] values, String label)
    {
        try (FileBackups.AtomicWriter fw = FileBackups.writer(file))
        {
            Scarlet.GSON_PRETTY.toJson(values, String[].class, fw);
            fw.commit();
        }
        catch (Exception ex)
        {
            LOG.error("Exception saving {}.json", label, ex);
        }
    }

}

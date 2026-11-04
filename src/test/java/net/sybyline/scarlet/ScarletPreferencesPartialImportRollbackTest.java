package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real import/copy/rollback code with synthetic files and an in-memory preferences root. */
class ScarletPreferencesPartialImportRollbackTest
{
    private static final String SCARLET_PATH = "/net/sybyline/scarlet";
    private static final String IMPORT_XML = "<preferences EXTERNAL_XML_VERSION=\"1.0\"><root type=\"user\"><map/>"
        + "<node name=\"net\"><map/><node name=\"sybyline\"><map/><node name=\"scarlet\">"
        + "<map><entry key=\"new-root\" value=\"imported\"/></map><node name=\"nested\"><map>"
        + "<entry key=\"first\" value=\"already copied\"/><entry key=\"fail-here\" value=\"partly applied\"/>"
        + "<entry key=\"last\" value=\"copied only before flush failure\"/></map></node>"
        + "</node></node></node></root></preferences>";

    @TempDir Path directory;

    enum FailurePoint { DURING_COPY, DURING_FLUSH }

    @ParameterizedTest
    @EnumSource(FailurePoint.class)
    void failedRealPreferenceReplacementRestoresWholeTransaction(FailurePoint point) throws Exception
    {
        Failure failure = new Failure(point);
        MemoryPreferences root = new MemoryPreferences(null, "", failure);
        root.put("root-sentinel", "untouched");
        root.node("other/app").put("sentinel", "untouched");
        root.node("net/sybyline/other").put("sentinel", "untouched");
        Preferences original = root.node(SCARLET_PATH);
        original.put("original", "must be restored");
        original.node("old-child").put("old", "must be restored too");
        String originalTree = new String(snapshot(root), StandardCharsets.UTF_8);

        Path data = Files.createDirectory(this.directory.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "{\"state\":\"original\"}");
        Files.writeString(data.resolve("global-prefs.key"), "synthetic-original-key");
        Path bundle = this.directory.resolve("synthetic-import.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle)))
        {
            entry(zip, "data/settings.json", "{\"state\":\"replacement\"}");
            entry(zip, "data/new.json", "{\"new\":true}");
            entry(zip, "data/global-prefs.key", "synthetic-replacement-key");
            entry(zip, "preferences.xml", IMPORT_XML);
        }

        int[] replacements = { 0 };
        ScarletMigration.PreferenceTransaction transaction = new ScarletMigration.PreferenceTransaction()
        {
            @Override public byte[] backup() throws Exception
            {
                return snapshot(root.node(SCARLET_PATH));
            }

            @Override public void replace(byte[] xml) throws Exception
            {
                failure.armed = ++replacements[0] == 1;
                try
                {
                    ScarletMigration.replaceScarletPreferences(xml, root);
                }
                catch (Exception expected)
                {
                    if (replacements[0] == 1)
                    {
                        // Prove the fault happened after the original node was removed and
                        // replacement keys were copied, rather than during input validation.
                        Preferences partial = root.node(SCARLET_PATH);
                        assertNull(partial.get("original", null));
                        assertFalse(partial.nodeExists("old-child"));
                        assertEquals("imported", partial.get("new-root", null));
                        assertEquals("already copied", partial.node("nested").get("first", null));
                        assertEquals("partly applied", partial.node("nested").get("fail-here", null));
                        assertEquals(point == FailurePoint.DURING_FLUSH,
                            partial.node("nested").get("last", null) != null);
                        assertEquals("{\"state\":\"replacement\"}", Files.readString(data.resolve("settings.json")));
                    }
                    throw expected;
                }
            }
        };

        IOException result = assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(
            bundle.toFile(), ScarletMigration.ImportOptions.all(), data.toFile(), transaction));
        assertTrue(result.getMessage().contains("restoring the original configuration"));
        assertTrue(failure.triggered, "The actual replacement backend must reach the selected failure point");
        assertEquals(2, replacements[0], "The transaction must restore its saved preferences after the failed copy");
        assertEquals(originalTree, new String(snapshot(root), StandardCharsets.UTF_8),
            "Rollback must restore Scarlet and leave all unrelated user preferences identical");
        assertEquals("{\"state\":\"original\"}", Files.readString(data.resolve("settings.json")));
        assertEquals("synthetic-original-key", Files.readString(data.resolve("global-prefs.key")));
        assertFalse(Files.exists(data.resolve("new.json")));
        try (java.util.stream.Stream<Path> children = Files.list(this.directory))
        {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".scarlet-import-")),
                "Successful rollback must clean the transaction staging directory");
        }
    }

    private static byte[] snapshot(Preferences node) throws Exception
    {
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        node.exportSubtree(xml);
        return xml.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException
    {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final class Failure
    {
        final FailurePoint point;
        boolean armed, triggered;
        Failure(FailurePoint point) { this.point = point; }
        boolean trigger(FailurePoint candidate)
        {
            if (!this.armed || this.triggered || this.point != candidate) return false;
            this.triggered = true;
            return true;
        }
    }

    private static final class MemoryPreferences extends AbstractPreferences
    {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, MemoryPreferences> children = new LinkedHashMap<>();
        private final Failure failure;
        MemoryPreferences(AbstractPreferences parent, String name, Failure failure)
        {
            super(parent, name);
            this.failure = failure;
        }
        @Override public boolean isUserNode() { return true; }
        @Override protected void putSpi(String key, String value)
        {
            this.values.put(key, value);
            if ((SCARLET_PATH + "/nested").equals(this.absolutePath()) && "fail-here".equals(key)
                && this.failure.trigger(FailurePoint.DURING_COPY))
                throw new IllegalStateException("Synthetic failure after a partial preferences copy");
        }
        @Override protected String getSpi(String key) { return this.values.get(key); }
        @Override protected void removeSpi(String key) { this.values.remove(key); }
        @Override protected void removeNodeSpi()
        {
            if (this.parent() != null) ((MemoryPreferences) this.parent()).children.remove(this.name());
            this.values.clear();
            this.children.clear();
        }
        @Override protected String[] keysSpi() { return this.values.keySet().toArray(new String[0]); }
        @Override protected String[] childrenNamesSpi() { return this.children.keySet().toArray(new String[0]); }
        @Override protected AbstractPreferences childSpi(String name)
        {
            return this.children.computeIfAbsent(name, n -> new MemoryPreferences(this, n, this.failure));
        }
        @Override protected void syncSpi() { }
        @Override protected void flushSpi() throws BackingStoreException
        {
            if (SCARLET_PATH.equals(this.absolutePath()) && this.values.containsKey("new-root")
                && this.failure.trigger(FailurePoint.DURING_FLUSH))
                throw new BackingStoreException("Synthetic failure flushing replaced preferences");
        }
    }
}

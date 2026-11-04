package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScarletPreferencesImportScopeTest
{
    private static String xml(String content)
    {
        return "<preferences EXTERNAL_XML_VERSION=\"1.0\"><root type=\"user\"><map/>"
            + "<node name=\"net\"><map/><node name=\"sybyline\"><map/>"
            + "<node name=\"scarlet\">" + content + "</node></node></node></root></preferences>";
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static MemoryPreferences seeded(boolean user)
    {
        MemoryPreferences root = new MemoryPreferences(null, "", user);
        root.put("root-sentinel", "untouched");
        root.node("other/app").put("sentinel", "untouched");
        root.node("net/sybyline/other").put("sentinel", "untouched");
        root.node("net/sybyline/scarlet").put("original", "retained-until-valid");
        return root;
    }

    private static String snapshot(Preferences root) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        root.exportSubtree(out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void rejectsWithoutMutation(String input) throws Exception
    {
        MemoryPreferences user = seeded(true), system = seeded(false);
        String beforeUser = snapshot(user), beforeSystem = snapshot(system);
        assertThrows(IOException.class, () -> ScarletMigration.normalizeScarletOnlyPrefs(bytes(input)));
        assertThrows(IOException.class, () -> ScarletMigration.replaceScarletPreferences(bytes(input), user));
        assertEquals(beforeUser, snapshot(user), "Invalid input must not even clear the existing Scarlet node");
        assertEquals(beforeSystem, snapshot(system));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/outside", "relative/path", "\\outside", "relative\\path", "", ".", ".."})
    void rejectsPathNamesAtEveryDepth(String name) throws Exception
    {
        String child = "<node name=\"" + name + "\"><map/></node>";
        rejectsWithoutMutation(xml("<map/>" + child));
        rejectsWithoutMutation(xml("<map/><node name=\"safe\"><map/>" + child + "</node>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"system", "", "USER", "other"})
    void refusesNonUserRoot(String type) throws Exception
    {
        rejectsWithoutMutation(xml("<map/>").replace("type=\"user\"", "type=\"" + type + "\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<map/><map><entry key=\"x\" value=\"y\"/></map>",
        "<map/><node name=\"same\"><map/></node><node name=\"same\"><map/></node>",
        "<map><entry key=\"same\" value=\"1\"/><entry key=\"same\" value=\"2\"/></map>",
        "<map><entry key=\"no-value\"/></map>",
        "<map><entry value=\"no-key\"/></map>",
        "<map/><node name=\"child\"/>",
        "<map/><unexpected/>",
        "<map/><node name=\"child\" other=\"x\"><map/></node>",
        "<map><entry key=\"a\" value=\"b\"><node name=\"x\"><map/></node></entry></map>",
        "<map/>unexpected text",
        "<map/><node><map/></node>"
    })
    void refusesAmbiguousOrMalformedNodeStructure(String content) throws Exception
    {
        rejectsWithoutMutation(xml(content));
    }

    @Test void refusesExtraRootsAncestorValuesAndWrongEnvelope() throws Exception
    {
        rejectsWithoutMutation(xml("<map/>").replace("</preferences>", "<root type=\"system\"><map/></root></preferences>"));
        rejectsWithoutMutation(xml("<map/>").replace("<root type=\"user\"><map/>",
            "<root type=\"user\"><map><entry key=\"outside\" value=\"bad\"/></map>"));
        rejectsWithoutMutation(xml("<map/>").replace("<node name=\"sybyline\"><map/>",
            "<node name=\"sybyline\"><map><entry key=\"outside\" value=\"bad\"/></map>"));
        rejectsWithoutMutation(xml("<map/>").replace("preferences", "unexpected"));
        rejectsWithoutMutation(xml("<map/>").replace("EXTERNAL_XML_VERSION=\"1.0\"", "EXTERNAL_XML_VERSION=\"2.0\""));
    }

    @Test void refusesEntitiesAndUntrustedDoctype() throws Exception
    {
        rejectsWithoutMutation("<!DOCTYPE preferences [<!ENTITY payload 'private'>]>" + xml("<map/>&payload;"));
        rejectsWithoutMutation("<!DOCTYPE preferences SYSTEM 'file:///not-to-be-read'>" + xml("<map/>"));
    }

    @Test void boundsDepthCountAndPreferenceSizes() throws Exception
    {
        rejectsWithoutMutation(xml("<map/>" + "<node name=\"d\"><map/>".repeat(33) + "</node>".repeat(33)));
        StringBuilder nodes = new StringBuilder("<map/>");
        for (int i = 0; i < 4096; i++) nodes.append("<node name=\"n").append(i).append("\"><map/></node>");
        rejectsWithoutMutation(xml(nodes.toString()));
        rejectsWithoutMutation(xml("<map/><node name=\"" + "n".repeat(81) + "\"><map/></node>"));
        rejectsWithoutMutation(xml("<map><entry key=\"" + "k".repeat(81) + "\" value=\"v\"/></map>"));
        rejectsWithoutMutation(xml("<map><entry key=\"k\" value=\"" + "v".repeat(8193) + "\"/></map>"));
    }

    @Test void validNestedImportChangesOnlyScarletAndAllowsRollbackSnapshot() throws Exception
    {
        MemoryPreferences root = seeded(true);
        Preferences current = root.node("net/sybyline/scarlet");
        current.node("old-child").put("old", "value");
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        current.exportSubtree(backup);
        String previous = snapshot(root);
        ScarletMigration.replaceScarletPreferences(bytes(xml("<map><entry key=\"new\" value=\"safe\"/></map>"
            + "<node name=\"nested\"><map><entry key=\"key\" value=\"value\"/></map></node>")), root);
        assertNull(root.node("net/sybyline/scarlet").get("original", null));
        assertFalse(root.nodeExists("net/sybyline/scarlet/old-child"));
        assertEquals("value", root.node("net/sybyline/scarlet/nested").get("key", null));
        assertEquals("untouched", root.get("root-sentinel", null));
        assertEquals("untouched", root.node("other/app").get("sentinel", null));
        assertEquals("untouched", root.node("net/sybyline/other").get("sentinel", null));
        ScarletMigration.replaceScarletPreferences(backup.toByteArray(), root);
        assertEquals(previous, snapshot(root));
    }

    @Test void validXmlCannotBeAppliedToSystemRoot() throws Exception
    {
        MemoryPreferences system = seeded(false);
        String before = snapshot(system);
        assertThrows(IOException.class, () -> ScarletMigration.replaceScarletPreferences(bytes(xml("<map/>")), system));
        assertEquals(before, snapshot(system));
    }

    @Test void validXmlCannotRedirectAnInjectedNonRootDestination() throws Exception
    {
        MemoryPreferences user = seeded(true);
        Preferences other = user.node("other/app");
        String before = snapshot(user);
        assertThrows(IOException.class, () -> ScarletMigration.replaceScarletPreferences(bytes(xml("<map/>")), other));
        assertEquals(before, snapshot(user));
    }

    @Test void refusesOversizedEntryCountAndUnexpectedEnvelopeContent() throws Exception
    {
        StringBuilder entries = new StringBuilder("<map>");
        for (int i = 0; i <= 65536; i++) entries.append("<entry key=\"k").append(i).append("\" value=\"v\"/>");
        rejectsWithoutMutation(xml(entries.append("</map>").toString()));
        rejectsWithoutMutation(xml("<map/>").replace("<preferences ", "<preferences extra=\"no\" "));
        rejectsWithoutMutation(xml("<map/>").replace("<root type=\"user\">", "<root type=\"user\"><?unexpected data?>"));
        rejectsWithoutMutation(xml("<map/>").replace("</root>", "<node name=\"outside\"><map/></node></root>"));
    }

    @Test void acceptsEscapedUnicodeAndCommentsWithoutExpandingPaths() throws Exception
    {
        MemoryPreferences user = seeded(true);
        Preferences restored = ScarletMigration.replaceScarletPreferences(bytes(xml(
            "<!-- ordinary export comment --><map><entry key=\"label\" value=\"caf&#233; &amp; tea\"/></map>"
            + "<node name=\"relative label\"><map><entry key=\"a/b\" value=\"text only\"/></map></node>")), user);
        assertEquals("caf\u00e9 & tea", restored.get("label", null));
        assertEquals("text only", restored.node("relative label").get("a/b", null));
        assertEquals("untouched", user.node("other/app").get("sentinel", null));
    }

    @Test void normalizedInterimExportCanStillBeRestoredInsideCurrentSubtree() throws Exception
    {
        MemoryPreferences user = seeded(true);
        String input = xml("<map><entry key=\"safe\" value=\"value\"/></map>").replace("name=\"sybyline\"", "name=\"hatson321\"");
        byte[] normalized = ScarletMigration.normalizeScarletOnlyPrefs(bytes(input));
        Preferences result = ScarletMigration.replaceScarletPreferences(normalized, user);
        assertEquals("/net/sybyline/scarlet", result.absolutePath());
        assertTrue(result.getBoolean(ScarletSettings.INTERIM_NAMESPACE_RECONCILED_KEY, false));
        assertEquals("value", result.get("safe", null));
        assertFalse(user.nodeExists("net/hatson321"));
    }

    private static final class MemoryPreferences extends AbstractPreferences
    {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, MemoryPreferences> children = new LinkedHashMap<>();
        private final boolean user;
        MemoryPreferences(AbstractPreferences parent, String name, boolean user) { super(parent, name); this.user = user; }
        @Override public boolean isUserNode() { return this.user; }
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() throws BackingStoreException
        {
            if (parent() != null) ((MemoryPreferences) parent()).children.remove(name());
            values.clear(); children.clear();
        }
        @Override protected String[] keysSpi() { return values.keySet().toArray(new String[0]); }
        @Override protected String[] childrenNamesSpi() { return children.keySet().toArray(new String[0]); }
        @Override protected AbstractPreferences childSpi(String name) { return children.computeIfAbsent(name, n -> new MemoryPreferences(this, n, user)); }
        @Override protected void syncSpi() { }
        @Override protected void flushSpi() { }
    }
}

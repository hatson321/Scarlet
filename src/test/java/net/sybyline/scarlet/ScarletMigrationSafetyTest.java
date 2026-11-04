package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.junit.jupiter.api.Test;

import net.sybyline.scarlet.util.EncryptedPrefs;

class ScarletMigrationSafetyTest
{
    @Test
    void credentialSelectionKeepsEverySecureStoreFileOutOfDataOnlyImports()
    {
        ScarletMigration.ImportOptions dataOnly = new ScarletMigration.ImportOptions(true, false);
        ScarletMigration.ImportOptions credentialsOnly = new ScarletMigration.ImportOptions(false, true);
        String[] credentialFiles = {
            "global-prefs.key",
            "store.bin",
            "secure-prefs.dat",
            "secure-prefs.dat.bak",
            "secure-prefs.dat.tmp",
            "secure-prefs.dat.backup-20261001-120000.dat",
            "secure-prefs.registry-backup-20261001-120000.dat",
            "secure-prefs.quarantine-20261001-120000-entry.b64",
            "preference-namespace-backups/20261001-120000-interim.xml"
        };

        for (String relative : credentialFiles)
        {
            assertTrue(ScarletMigration.isCredentialDataEntry(relative), relative);
            assertFalse(ScarletMigration.shouldImportDataEntry(relative, dataOnly), relative);
            assertTrue(ScarletMigration.shouldImportDataEntry(relative, credentialsOnly), relative);
        }
        assertTrue(ScarletMigration.shouldImportDataEntry("settings.json", dataOnly));
        assertFalse(ScarletMigration.shouldImportDataEntry("settings.json", credentialsOnly));
    }

    @Test
    void interimPreferencesXmlIsMarkedForSeedRewrap() throws Exception
    {
        String source = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<preferences EXTERNAL_XML_VERSION=\"1.0\"><root type=\"user\"><map/>"
            + "<node name=\"net\"><map/><node name=\"hatson321\"><map/>"
            + "<node name=\"scarlet\"><map><entry key=\"lastRunTime\" value=\"2026-10-01T12:00:00Z\"/>"
            + "</map></node></node></node></root></preferences>";

        String normalized = new String(
            ScarletMigration.normalizeScarletOnlyPrefs(source.getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8);

        assertTrue(normalized.contains("name=\"sybyline\""));
        assertFalse(normalized.contains("name=\"hatson321\""));
        assertTrue(normalized.contains("key=\"" + ScarletSettings.INTERIM_NAMESPACE_RECONCILED_KEY + "\""));
    }

    @Test
    void movedInterimSeedCanBeVerifiedAndRewrappedForCurrentPath() throws Exception
    {
        MemoryPreferences root = new MemoryPreferences(null, "");
        Preferences interim = root.node("/net/hatson321/scarlet");
        Preferences current = root.node("/net/sybyline/scarlet");
        String globalPassword = "test-global-password";
        String localPassword = "test-local-password";

        EncryptedPrefs.installLocalPassword(interim, globalPassword, localPassword);
        for (String key : interim.keys())
            current.put(key, interim.get(key, null));

        String recovered = EncryptedPrefs.tryReadLocalPasswordForPath(
            current,
            null,
            interim.absolutePath(),
            globalPassword);
        assertEquals(localPassword, recovered);

        EncryptedPrefs.installLocalPassword(current, globalPassword, recovered);
        assertEquals(localPassword, EncryptedPrefs.tryReadLocalPassword(current, globalPassword));
    }

    private static final class MemoryPreferences extends AbstractPreferences
    {
        MemoryPreferences(AbstractPreferences parent, String name)
        {
            super(parent, name);
        }

        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, MemoryPreferences> children = new LinkedHashMap<>();

        @Override
        protected void putSpi(String key, String value)
        {
            this.values.put(key, value);
        }

        @Override
        protected String getSpi(String key)
        {
            return this.values.get(key);
        }

        @Override
        protected void removeSpi(String key)
        {
            this.values.remove(key);
        }

        @Override
        protected void removeNodeSpi() throws BackingStoreException
        {
            this.values.clear();
            this.children.clear();
        }

        @Override
        protected String[] keysSpi() throws BackingStoreException
        {
            return this.values.keySet().toArray(new String[this.values.size()]);
        }

        @Override
        protected String[] childrenNamesSpi() throws BackingStoreException
        {
            return this.children.keySet().toArray(new String[this.children.size()]);
        }

        @Override
        protected AbstractPreferences childSpi(String name)
        {
            MemoryPreferences child = this.children.get(name);
            if (child == null)
            {
                child = new MemoryPreferences(this, name);
                this.children.put(name, child);
            }
            return child;
        }

        @Override
        protected void syncSpi() throws BackingStoreException
        {
        }

        @Override
        protected void flushSpi() throws BackingStoreException
        {
        }
    }
}

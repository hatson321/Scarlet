package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.sybyline.scarlet.util.ProcLock;

class ScarletImportTransactionTest
{
    @TempDir Path directory;
    private static final String PREFS = "<preferences EXTERNAL_XML_VERSION=\"1.0\"><root type=\"user\"><map/>"
        + "<node name=\"net\"><map/><node name=\"sybyline\"><map/><node name=\"scarlet\"><map/>"
        + "</node></node></node></root></preferences>";

    @Test void credentialPathAliasesAndCaseCannotBypassOptOut()
    {
        ScarletMigration.ImportOptions dataOnly = new ScarletMigration.ImportOptions(true, false);
        for (String path : new String[] { "./global-prefs.key", "sub/../global-prefs.key", "GLOBAL-PREFS.KEY",
            "./secure-prefs.dat", "secure-prefs.dat.", "secure-prefs.dat ", "sub\\..\\secure-prefs.dat",
            "secure-prefs.dat:stream", "secure-prefs.dat.unique.tmp", "secure-prefs.dat.quarantine-id" })
            assertFalse(ScarletMigration.shouldImportDataEntry(path, dataOnly), path);
        assertTrue(ScarletMigration.shouldImportDataEntry("settings.json", dataOnly));
    }

    @Test void invalidLatePreferencesFailBeforeAnyLiveDataChanges() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "original");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/settings.json", "replacement");
        entries.put("data/global-prefs.key", "key");
        entries.put("preferences.xml", "<not-valid>");
        assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(bundle(entries).toFile(),
            ScarletMigration.ImportOptions.all(), data.toFile(), null));
        assertEquals("original", Files.readString(data.resolve("settings.json")));
        assertFalse(Files.exists(data.resolve("global-prefs.key")));
    }

    @Test void emptyIncomingCredentialKeyRejectsImportBeforeAnyMutation() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("global-prefs.key"), "original-key");
        Files.writeString(data.resolve("settings.json"), "original");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/settings.json", "replacement");
        entries.put("data/global-prefs.key", "");
        entries.put("preferences.xml", PREFS);
        assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(bundle(entries).toFile(),
            ScarletMigration.ImportOptions.all(), data.toFile(), null));
        assertEquals("original-key", Files.readString(data.resolve("global-prefs.key")));
        assertEquals("original", Files.readString(data.resolve("settings.json")));
    }

    @Test void failedPreferenceCommitRollsBackEveryAppliedFileAndPreferences() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "original");
        Files.writeString(data.resolve("global-prefs.key"), "original-key");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/settings.json", "replacement");
        entries.put("data/new.json", "new");
        entries.put("data/global-prefs.key", "replacement-key");
        entries.put("preferences.xml", PREFS);
        final int[] replacements = { 0 };
        ScarletMigration.PreferenceTransaction prefs = new ScarletMigration.PreferenceTransaction()
        {
            public byte[] backup() { return "original-prefs".getBytes(StandardCharsets.UTF_8); }
            public void replace(byte[] xml) throws Exception
            {
                if (++replacements[0] == 1) throw new IOException("synthetic late commit failure");
                assertEquals("original-prefs", new String(xml, StandardCharsets.UTF_8));
            }
        };
        assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(bundle(entries).toFile(),
            ScarletMigration.ImportOptions.all(), data.toFile(), prefs));
        assertEquals(2, replacements[0]);
        assertEquals("original", Files.readString(data.resolve("settings.json")));
        assertEquals("original-key", Files.readString(data.resolve("global-prefs.key")));
        assertFalse(Files.exists(data.resolve("new.json")));
    }

    @Test void duplicateCaseDestinationsAndLateUnsafeEntriesRejectEntireBundle() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "original");
        for (String unsafe : new String[] { "data/SETTINGS.JSON", "data/sub/../global-prefs.key" })
        {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("data/settings.json", "replacement"); entries.put(unsafe, "unexpected");
            assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(bundle(entries).toFile(),
                new ScarletMigration.ImportOptions(true, false), data.toFile(), null));
            assertEquals("original", Files.readString(data.resolve("settings.json")));
        }
    }

    @Test void dataOnlyImportLeavesCredentialsUntouchedAndCommitsCompleteData() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("global-prefs.key"), "original-key");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/settings.json", "{\"note\":\"日本語\"}");
        entries.put("data/global-prefs.key", "unselected-key");
        entries.put("preferences.xml", "unselected-prefs");
        ScarletMigration.importBundleContents(bundle(entries).toFile(), new ScarletMigration.ImportOptions(true, false), data.toFile(), null);
        assertEquals("original-key", Files.readString(data.resolve("global-prefs.key")));
        assertEquals("日本語", JsonParser.parseString(Files.readString(data.resolve("settings.json"))).getAsJsonObject().get("note").getAsString());
    }

    @Test void truncatedCentralDirectoryFailsWithoutMutation() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "original");
        Path archive = bundle(java.util.Collections.singletonMap("data/settings.json", "replacement"));
        byte[] bytes = Files.readAllBytes(archive);
        Files.write(archive, java.util.Arrays.copyOf(bytes, bytes.length - 22));
        assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(archive.toFile(),
            new ScarletMigration.ImportOptions(true, false), data.toFile(), null));
        assertEquals("original", Files.readString(data.resolve("settings.json")));
    }

    @Test void dataOnlyDiscordImportPreservesLocalCredentialsAndUnknownConfiguration() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("discord_bot.json"), "{\"token\":\"local-synthetic-token\","
            + "\"clientSecret\":\"local-synthetic-secret\",\"scarletAuxWh2webhookUrl\":{\"local\":\"synthetic-local-url\"}}");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/DISCORD_BOT.JSON", "{\"token\":\"incoming-synthetic-token\",\"BOT_TOKEN\":\"incoming-alias\","
            + "\"clientSecret\":\"incoming-secret\",\"scarletAuxWh2webhookUrl\":{\"remote\":\"incoming-url\"},"
            + "\"guildSf\":\"synthetic-guild\",\"futureSetting\":{\"keep\":true},\"audioChannelSf\":\"synthetic-channel\"}");
        ScarletMigration.importBundleContents(bundle(entries).toFile(), new ScarletMigration.ImportOptions(true, false), data.toFile(), null);
        JsonObject config = JsonParser.parseString(Files.readString(data.resolve("discord_bot.json"))).getAsJsonObject();
        assertEquals("local-synthetic-token", config.get("token").getAsString());
        assertEquals("local-synthetic-secret", config.get("clientSecret").getAsString());
        assertFalse(config.has("BOT_TOKEN"));
        assertTrue(config.getAsJsonObject("scarletAuxWh2webhookUrl").has("local"));
        assertFalse(config.getAsJsonObject("scarletAuxWh2webhookUrl").has("remote"));
        assertTrue(config.getAsJsonObject("futureSetting").get("keep").getAsBoolean());
        assertEquals("synthetic-guild", config.get("guildSf").getAsString());
        assertEquals("synthetic-channel", config.get("audioChannelSf").getAsString());
        try (java.util.stream.Stream<Path> paths = Files.list(data))
        { assertEquals(1, paths.filter(path -> path.getFileName().toString().equalsIgnoreCase("discord_bot.json")).count()); }
    }

    @Test void noLocalTokenDoesNotAdoptIncomingLegacyCredentials() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        String incoming = "{\"token\":\"incoming\",\"discord_token\":\"alias\",\"tokenEncrypted\":\"alias\","
            + "\"scarletAuxWh2webhookUrl\":{\"remote\":\"incoming\"},\"guildSf\":\"keep\"}";
        ScarletMigration.importBundleContents(bundle(java.util.Collections.singletonMap("data/discord_bot.json", incoming)).toFile(),
            new ScarletMigration.ImportOptions(true, false), data.toFile(), null);
        JsonObject config = JsonParser.parseString(Files.readString(data.resolve("discord_bot.json"))).getAsJsonObject();
        assertEquals("keep", config.get("guildSf").getAsString());
        assertEquals(1, config.size());
    }

    @Test void dataOnlySettingsImportPreservesExistingLegacyLoginAndRejectsSourceCredentials() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "{\"vrc_username\":\"local-user\",\"vrc_password\":\"local-password\","
            + "\"vrc_secret\":\"local-secret\",\"unrelated\":\"old\"}");
        String incoming = "{\"vrc_username\":\"incoming-user\",\"vrc_password\":\"incoming-password\","
            + "\"vrc_secret\":\"incoming-secret\",\"VRC_PASSWORD\":\"case-alias\",\"unrelated\":\"new\",\"nullable\":null}";
        ScarletMigration.importBundleContents(bundle(java.util.Collections.singletonMap("data/SETTINGS.JSON", incoming)).toFile(),
            new ScarletMigration.ImportOptions(true, false), data.toFile(), null);
        JsonObject config = JsonParser.parseString(Files.readString(data.resolve("settings.json"))).getAsJsonObject();
        assertEquals("local-user", config.get("vrc_username").getAsString());
        assertEquals("local-password", config.get("vrc_password").getAsString());
        assertEquals("local-secret", config.get("vrc_secret").getAsString());
        assertFalse(config.has("VRC_PASSWORD"));
        assertEquals("new", config.get("unrelated").getAsString());
        assertTrue(config.get("nullable").isJsonNull());
    }

    @Test void dataOnlySettingsWithoutExistingLoginDoesNotAdoptLegacyLogin() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        String incoming = "{\"vrc_username\":\"incoming-user\",\"vrc_password\":\"incoming-password\",\"vrc_secret\":\"incoming-secret\",\"keep\":true}";
        ScarletMigration.importBundleContents(bundle(java.util.Collections.singletonMap("data/settings.json", incoming)).toFile(),
            new ScarletMigration.ImportOptions(true, false), data.toFile(), null);
        JsonObject config = JsonParser.parseString(Files.readString(data.resolve("settings.json"))).getAsJsonObject();
        assertEquals(1, config.size());
        assertTrue(config.get("keep").getAsBoolean());
    }

    @Test void dataOnlyImportCannotPlantLegacyCredentialsInAutomaticRecoveryBackups() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.createDirectory(data.resolve("backups"));
        Path existing = data.resolve("backups/settings.json.20261005-010203.bak");
        Files.writeString(existing, "{\"vrc_password\":\"local-recovery-password\"}");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/backups/settings.json.20261005-010203.bak", "{\"vrc_password\":\"incoming\"}");
        entries.put("data/backups/SETTINGS.JSON.20990101-000000.bak", "{\"vrc_password\":\"incoming-newer\"}");
        entries.put("data/backups/discord_bot.json.20990101-000000.bak", "{\"token\":\"incoming\"}");
        entries.put("data/backups/secure-prefs.dat.20990101-000000.bak", "incoming secure store");
        entries.put("data/backups/audit.json.20261005-010203.bak", "unrelated audit backup");
        ScarletMigration.importBundleContents(bundle(entries).toFile(), new ScarletMigration.ImportOptions(true, false), data.toFile(), null);
        assertEquals("{\"vrc_password\":\"local-recovery-password\"}", Files.readString(existing));
        assertFalse(Files.exists(data.resolve("backups/SETTINGS.JSON.20990101-000000.bak")));
        assertFalse(Files.exists(data.resolve("backups/discord_bot.json.20990101-000000.bak")));
        assertFalse(Files.exists(data.resolve("backups/secure-prefs.dat.20990101-000000.bak")));
        assertEquals("unrelated audit backup", Files.readString(data.resolve("backups/audit.json.20261005-010203.bak")));
    }

    @Test void channelScrubbingDoesNotReplaceLocalCredentialsOrDropUnknownSettings() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("discord_bot.json"), "{\"token\":\"local\",\"scarletAuxWh2webhookUrl\":{\"local\":\"local\"}}");
        String incoming = "{\"token\":\"incoming\",\"audioChannelSf\":\"incoming\",\"auditType2channelSf\":{\"event\":\"channel\"},"
            + "\"auditType2scarletAuxWh\":{\"event\":\"remote\"},\"futureSetting\":7}";
        ScarletMigration.importBundleContents(bundle(java.util.Collections.singletonMap("data/discord_bot.json", incoming)).toFile(),
            new ScarletMigration.ImportOptions(true, false, false), data.toFile(), null);
        JsonObject config = JsonParser.parseString(Files.readString(data.resolve("discord_bot.json"))).getAsJsonObject();
        assertEquals("local", config.get("token").getAsString());
        assertTrue(config.getAsJsonObject("scarletAuxWh2webhookUrl").has("local"));
        assertTrue(config.get("audioChannelSf").isJsonNull());
        assertEquals(0, config.getAsJsonObject("auditType2channelSf").size());
        assertEquals(0, config.getAsJsonObject("auditType2scarletAuxWh").size());
        assertEquals(7, config.get("futureSetting").getAsInt());
    }

    @Test void invalidSourceOrLocalDiscordConfigRejectsAllStagedChanges() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "original");
        for (boolean invalidLocal : new boolean[] { false, true })
        {
            String local = invalidLocal ? "not-json" : "{\"token\":\"local\"}";
            Files.writeString(data.resolve("discord_bot.json"), local);
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("data/settings.json", "replacement");
            entries.put("data/discord_bot.json", invalidLocal ? "{\"token\":\"incoming\"}" : "[]");
            assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(bundle(entries).toFile(),
                new ScarletMigration.ImportOptions(true, false), data.toFile(), null));
            assertEquals("original", Files.readString(data.resolve("settings.json")));
            assertEquals(local, Files.readString(data.resolve("discord_bot.json")));
        }
    }

    @Test void explicitCredentialImportMayReplaceTheLegacyToken() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("discord_bot.json"), "{\"token\":\"local\"}");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/discord_bot.json", "{\"token\":\"incoming\"}");
        entries.put("data/global-prefs.key", "synthetic-key"); entries.put("preferences.xml", PREFS);
        ScarletMigration.PreferenceTransaction prefs = new ScarletMigration.PreferenceTransaction()
        {
            public byte[] backup() { return PREFS.getBytes(StandardCharsets.UTF_8); }
            public void replace(byte[] xml) { assertTrue(xml.length > 0); }
        };
        ScarletMigration.importBundleContents(bundle(entries).toFile(), ScarletMigration.ImportOptions.all(), data.toFile(), prefs);
        assertEquals("incoming", JsonParser.parseString(Files.readString(data.resolve("discord_bot.json"))).getAsJsonObject().get("token").getAsString());
    }

    @Test void firstInstallCanSnapshotAbsentHomeThenRestoreCompleteBundleUnderItsLeases() throws Exception
    {
        Path absent = directory.resolve("first-install"), backup = directory.resolve("before-import.zip");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/settings.json", "{\"restored\":true}");
        entries.put("data/global-prefs.key", "synthetic-key"); entries.put("preferences.xml", PREFS);
        Path archive = bundle(entries);
        final int[] commits = { 0 };
        ScarletMigration.PreferenceTransaction prefs = new ScarletMigration.PreferenceTransaction()
        {
            public byte[] backup() { return PREFS.getBytes(StandardCharsets.UTF_8); }
            public void replace(byte[] xml) { commits[0]++; }
        };
        try (ProcLock.Lease dataLease = ProcLock.acquireDataDirectory(absent.toFile());
             ProcLock.Lease preferenceLease = ProcLock.acquirePreferences(directory.resolve("fake-user").toFile(), false))
        {
            ScarletMigration.createPreImportBackup(absent.toFile(), backup.toFile(), () -> PREFS.getBytes(StandardCharsets.UTF_8));
            assertFalse(Files.exists(absent));
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(backup.toFile()))
            {
                assertNotNull(zip.getEntry("preferences.xml"));
                assertNotNull(zip.getEntry("scarlet-migration.txt"));
                assertNull(zip.getEntry("data/global-prefs.key"));
                assertEquals(2, zip.size());
            }
            // An empty-state safety snapshot cannot authorize deleting an installed identity.
            assertThrows(IOException.class, () -> ScarletMigration.verifyBundleContents(backup.toFile(), null, null));
            ScarletMigration.importBundleContents(archive.toFile(), ScarletMigration.ImportOptions.all(), absent.toFile(), prefs);
        }
        assertEquals(1, commits[0]);
        assertEquals("synthetic-key", Files.readString(absent.resolve("global-prefs.key")));
        assertEquals("{\"restored\":true}", Files.readString(absent.resolve("settings.json")));
    }

    @Test void discordPathAliasesCannotBypassDataOnlySanitizing() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("discord_bot.json"), "{\"token\":\"local\"}");
        for (String name : new String[] { "data/./discord_bot.json", "data/sub/../discord_bot.json", "data/sub\\..\\discord_bot.json" })
        {
            assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(
                bundle(java.util.Collections.singletonMap(name, "{\"token\":\"incoming\"}")).toFile(),
                new ScarletMigration.ImportOptions(true, false), data.toFile(), null));
            assertEquals("{\"token\":\"local\"}", Files.readString(data.resolve("discord_bot.json")));
        }
    }

    @Test void directoryAliasesCannotRedirectImportOutsideOrInsideItsRoot() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Path external = Files.createDirectory(directory.resolve("outside"));
        Path internal = Files.createDirectory(data.resolve("actual-subdirectory"));
        Files.writeString(data.resolve("unchanged.txt"), "original");
        for (Path target : new Path[] { external, internal })
        {
            Files.writeString(target.resolve("sentinel.txt"), "must remain");
            Path alias = data.resolve("directory-alias");
            if (System.getProperty("os.name", "").startsWith("Windows"))
            {
                Process command = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", alias.toString(), target.toString())
                    .redirectErrorStream(true).start();
                boolean finished = command.waitFor(10L, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) command.destroyForcibly();
                org.junit.jupiter.api.Assumptions.assumeTrue(finished && command.exitValue() == 0,
                    "Synthetic directory junctions unavailable on this filesystem");
            }
            else Files.createSymbolicLink(alias, target);
            try
            {
                Map<String, String> entries = new LinkedHashMap<>();
                entries.put("data/unchanged.txt", "replacement");
                entries.put("data/directory-alias/sentinel.txt", "must not replace target");
                assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(bundle(entries).toFile(),
                    new ScarletMigration.ImportOptions(true, false), data.toFile(), null));
                assertEquals("must remain", Files.readString(target.resolve("sentinel.txt")));
                assertEquals("original", Files.readString(data.resolve("unchanged.txt")));
            }
            finally { Files.deleteIfExists(alias); }
        }
    }

    @Test void failedRollbackRetainsRecoveryFilesAndReportsIncompleteRecovery() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "original");
        Files.writeString(data.resolve("global-prefs.key"), "original-synthetic-key");
        Files.writeString(data.resolve("discord_bot.json"), "{\"token\":\"original-synthetic-token\"}");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/settings.json", "replacement"); entries.put("data/global-prefs.key", "key");
        entries.put("data/discord_bot.json", "{\"token\":\"incoming-synthetic-token\"}");
        entries.put("preferences.xml", PREFS);
        ScarletMigration.PreferenceTransaction prefs = new ScarletMigration.PreferenceTransaction()
        {
            public byte[] backup() { return PREFS.getBytes(StandardCharsets.UTF_8); }
            public void replace(byte[] xml) throws Exception { throw new IOException("synthetic persistent preferences failure"); }
        };
        IOException failure = assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(bundle(entries).toFile(),
            ScarletMigration.ImportOptions.all(), data.toFile(), prefs));
        assertTrue(failure.getMessage().contains("rollback could not complete"));
        assertEquals("original", Files.readString(data.resolve("settings.json")));
        assertEquals("original-synthetic-key", Files.readString(data.resolve("global-prefs.key")));
        assertTrue(Files.readString(data.resolve("discord_bot.json")).contains("original-synthetic-token"));
        ScarletPrivateFilesTest.assertPrivate(data.resolve("global-prefs.key"));
        ScarletPrivateFilesTest.assertPrivate(data.resolve("discord_bot.json"));
        String marker = "Recovery files retained at ";
        Path recovery = Path.of(failure.getMessage().substring(failure.getMessage().indexOf(marker) + marker.length()));
        assertTrue(Files.isDirectory(recovery));
        assertFalse(recovery.startsWith(data), "Recovery plaintext belongs in private staging, outside imported data");
        ScarletPrivateFilesTest.assertPrivate(recovery);
        boolean originalKeySeen = false, originalConfigSeen = false, rewrittenConfigSeen = false, preferencesSeen = false;
        try (java.util.stream.Stream<Path> files = Files.list(recovery))
        {
            for (Path file : (Iterable<Path>) files::iterator)
            {
                ScarletPrivateFilesTest.assertPrivate(file);
                String content = Files.readString(file);
                originalKeySeen |= content.equals("original-synthetic-key");
                originalConfigSeen |= content.contains("original-synthetic-token");
                rewrittenConfigSeen |= content.contains("incoming-synthetic-token");
                preferencesSeen |= file.getFileName().toString().equals("original-preferences.xml");
                Files.delete(file);
            }
        }
        Files.delete(recovery);
        assertTrue(originalKeySeen && originalConfigSeen && rewrittenConfigSeen && preferencesSeen,
            "Recovery must retain privately protected originals, rewritten config, and preference snapshot");
    }

    private Path bundle(Map<String, String> entries) throws Exception
    {
        Path archive = Files.createTempFile(directory, "test-bundle-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive)))
        {
            for (Map.Entry<String, String> entry : entries.entrySet())
            {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return archive;
    }
}

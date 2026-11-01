package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.sybyline.scarlet.util.ProcLock;

class ScarletPersistenceRegressionTest
{
    @TempDir Path directory;

    @Test void settingsPersistNormalizedValuesAndValidateLoadedValues() throws Exception
    {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, "{\"heuristicKickCount\":99,\"group\":\"https://example.test/grp_123\"}");
        ScarletSettings settings = new ScarletSettings(null, file.toFile(), false);
        assertEquals(10, settings.heuristicKickCount.get());
        assertTrue(settings.heuristicKickCount.set(-5, "test"));
        assertEquals(1, settings.heuristicKickCount.get());
        assertEquals(1, settings.getObject("heuristicKickCount", Integer.class));
        ScarletSettings.FileValuedStringPattern group = settings.new FileValuedStringPattern("group", "group", "grp_0", "grp_[0-9]+", true);
        assertEquals("grp_123", group.get());
        assertEquals("grp_123", settings.getObject("group", String.class));
        assertTrue(group.set("prefix grp_456 suffix", "test"));
        assertEquals("grp_456", group.get());
        assertFalse(group.set("invalid", "test"));
        assertEquals("grp_456", group.get());
    }

    @Test void reassignmentAndStaleUnlinkKeepBothAccountDirectionsConsistent() throws Exception
    {
        ScarletData data = new ScarletData(directory.toFile());
        data.linkIdToSnowflake("user-a", "discord-x");
        data.linkIdToSnowflake("user-b", "discord-x");
        assertNull(data.userMetadata_getSnowflake("user-a"));
        assertEquals("discord-x", data.userMetadata_getSnowflake("user-b"));
        assertEquals("user-b", data.globalMetadata_getSnowflakeId("discord-x"));
        data.unlinkById("user-a");
        assertEquals("user-b", data.globalMetadata_getSnowflakeId("discord-x"));
        data.linkIdToSnowflake("user-b", "discord-y");
        assertNull(data.globalMetadata_getSnowflakeId("discord-x"));
        ScarletData reopened = new ScarletData(directory.toFile());
        assertEquals("user-b", reopened.globalMetadata_getSnowflakeId("discord-y"));
        assertEquals("discord-y", reopened.userMetadata_getSnowflake("user-b"));
    }

    @Test void parallelEntityUpdatesRetainEveryFieldAndAuditEntry() throws Exception
    {
        ScarletData data = new ScarletData(directory.toFile());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try
        {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++)
            {
                String id = "audit-" + i;
                futures.add(executor.submit(() -> data.updateUserMetadata("user", value -> { value.addAuditEntryId(id); return value; })));
            }
            futures.add(executor.submit(() -> data.userMetadata_setSnowflake("user", "discord")));
            for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
            assertEquals(30, data.userMetadata("user").auditEntryIds.length);
            assertEquals("discord", data.userMetadata("user").userSnowflake);
        }
        finally { executor.shutdownNow(); }
    }

    @Test void failedEntitySaveRetainsValueAndRetriesAfterStorageRecovers() throws Exception
    {
        ScarletData data = new ScarletData(directory.toFile());
        Path blocker = Files.createDirectories(directory.resolve("usr/user"));
        ScarletData.UserMetadata replacement = new ScarletData.UserMetadata();
        replacement.userSnowflake = "retained";
        assertThrows(java.io.UncheckedIOException.class, () -> data.userMetadata("user", replacement));
        assertEquals("retained", data.userMetadata("user").userSnowflake);
        Files.delete(blocker);
        data.saveDirty();
        assertEquals("retained", new ScarletData(directory.toFile()).userMetadata("user").userSnowflake);
    }

    @Test void fieldOnlyAuditUpdatePreservesChangesCommittedAfterAnEarlierSnapshot() throws Exception
    {
        ScarletData data = new ScarletData(directory.toFile());
        data.auditEntryMetadata("audit", new ScarletData.AuditEntryMetadata());
        ScarletData.AuditEntryMetadata stale = data.auditEntryMetadata("audit");
        data.auditEntryMetadata_setTags("audit", new String[] { "saved-tag" });
        data.auditEntryMetadata_setDescription("audit", "saved description");
        data.auditEntryMetadata("audit", current -> {
            current.threadSnowflake = "saved-thread";
            current.auxMessageSnowflake = "saved-auxiliary";
            current.setAuxData("deliveryState", "complete");
            return current;
        });
        assertNull(stale.threadSnowflake);
        ScarletData.AuditEntryMetadata committed = data.updateAuditEntryMetadata("audit", current -> {
            current.entryRedacted = true; return current;
        });
        assertTrue(committed.entryRedacted);
        assertTrue(committed.entryTags.contains("saved-tag"));
        assertEquals("saved description", committed.entryDescription);
        assertEquals("saved-thread", committed.threadSnowflake);
        assertEquals("saved-auxiliary", committed.auxMessageSnowflake);
        assertEquals("complete", committed.getAuxData("deliveryState").getAsString());
        assertTrue(new ScarletData(directory.toFile()).auditEntryMetadata("audit").entryRedacted);
        assertNull(data.updateAuditEntryMetadata("missing", current -> { fail("Absent records cannot be recreated"); return current; }));
        assertFalse(Files.exists(directory.resolve("gaud/missing")));
    }

    @Test void malformedExistingUserMetadataNeverLosesHistoryEvidenceOrAccountLink() throws Exception
    {
        Path record = Files.createDirectories(directory.resolve("usr")).resolve("synthetic-user");
        String intact = "{\"userSnowflake\":\"synthetic-account\",\"auditEntryIds\":[\"previous-audit\"],"
            + "\"evidenceSubmissions\":[{\"fileName\":\"synthetic-evidence.txt\"}]}";
        Files.writeString(record, intact);
        assertEquals(1, new ScarletData(directory.toFile()).userMetadata("synthetic-user").evidenceSubmissions.length);
        for (String unreadable : new String[] { intact.substring(0, intact.length() - 1), "null", "" })
        {
            Files.writeString(record, unreadable);
            ScarletData data = new ScarletData(directory.toFile());
            assertThrows(java.io.UncheckedIOException.class, () -> data.updateUserMetadata("synthetic-user", value -> {
                value.addAuditEntryId("new-audit"); return value;
            }));
            data.saveAll();
            assertEquals(unreadable, Files.readString(record), "Read failure must not publish an empty replacement");
        }
        Files.writeString(record, intact);
        ScarletData reopened = new ScarletData(directory.toFile());
        reopened.updateUserMetadata("synthetic-user", value -> { value.addAuditEntryId("new-audit"); return value; });
        ScarletData.UserMetadata retained = reopened.userMetadata("synthetic-user");
        assertEquals("synthetic-account", retained.userSnowflake);
        assertArrayEquals(new String[] { "previous-audit", "new-audit" }, retained.auditEntryIds);
        assertEquals("synthetic-evidence.txt", retained.evidenceSubmissions[0].fileName);
    }

    @Test void unreadableExistingSingletonNeverBecomesADefaultOnShutdown() throws Exception
    {
        Path record = directory.resolve("global.json");
        for (String unreadable : new String[] { "{\"userSnowflake2userId\":", "null", "" })
        {
            Files.writeString(record, unreadable);
            ScarletData data = new ScarletData(directory.toFile());
            assertThrows(java.io.UncheckedIOException.class, () -> data.globalMetadata_getSnowflakeId("synthetic-account"));
            data.saveAll();
            assertEquals(unreadable, Files.readString(record));
        }
    }

    @Test void existingNonFileMetadataIsAnErrorButAbsentRecordsCanBeCreated() throws Exception
    {
        ScarletData data = new ScarletData(directory.toFile());
        Path blocked = Files.createDirectories(directory.resolve("usr/blocked"));
        Path sentinel = Files.writeString(blocked.resolve("keep.txt"), "synthetic preservation sentinel");
        assertThrows(java.io.UncheckedIOException.class, () -> data.userMetadata_setSnowflake("blocked", "replacement"));
        assertEquals("synthetic preservation sentinel", Files.readString(sentinel));
        assertNull(data.userMetadata("absent"));
        data.userMetadata_setSnowflake("absent", "new-account");
        assertEquals("new-account", new ScarletData(directory.toFile()).userMetadata_getSnowflake("absent"));
    }

    @Test void dirtySingletonIsClearedOnlyAfterSuccessfulSave() throws Exception
    {
        ScarletData data = new ScarletData(directory.toFile());
        ScarletData.Datum<ScarletData.UserMetadata> datum = data.getDatum("singleton.json", ScarletData.UserMetadata.class, ScarletData.UserMetadata::new);
        datum.get().userSnowflake = "retained"; datum.markDirty();
        Path blocker = Files.createDirectory(directory.resolve("singleton.json"));
        datum.saveIfDirty();
        assertTrue(datum.pollDirty());
        Files.delete(blocker); datum.saveIfDirty();
        assertFalse(datum.pollDirty());
        assertTrue(Files.readString(directory.resolve("singleton.json")).contains("retained"));
    }

    @Test void throwingEditDoesNotPublishPartialMutation() throws Exception
    {
        ScarletData data = new ScarletData(directory.toFile());
        data.userMetadata_setSnowflake("user", "original");
        assertThrows(IllegalStateException.class, () -> data.updateUserMetadata("user", value -> {
            value.userSnowflake = "partial"; throw new IllegalStateException("synthetic edit failure");
        }));
        assertEquals("original", data.userMetadata_getSnowflake("user"));
    }

    @Test void offlineMigrationLeaseExcludesRunningAppAndCanBeReacquired() throws Exception
    {
        Path data = directory.resolve("data");
        try (ProcLock.Lease running = ProcLock.acquireDataDirectory(data.toFile()))
        { assertThrows(java.io.IOException.class, () -> ProcLock.acquireDataDirectory(data.toFile())); }
        try (ProcLock.Lease importer = ProcLock.acquireDataDirectory(data.toFile())) { assertNotNull(importer); }
    }

    @Test void watchedListsAndPronounsRoundTripExplicitUtf8() throws Exception
    {
        Path groupsFile = directory.resolve("groups.json"), entitiesFile = directory.resolve("entities.json");
        String unicode = "日本語 naïve Ελληνικά";
        Files.writeString(groupsFile, "[{\"id\":\"grp_test\",\"notes\":\"" + unicode + "\"}]");
        Files.writeString(entitiesFile, "[{\"id\":\"usr_test\",\"notes\":\"" + unicode + "\"}]");
        ScarletWatchedGroups groups = new ScarletWatchedGroups(groupsFile.toFile());
        ScarletWatchedEntities<Object> entities = new ScarletWatchedEntities<>(entitiesFile.toFile(), "usr_", null);
        assertEquals(unicode, groups.getWatchedGroup("grp_test").notes);
        assertEquals(unicode, entities.getWatchedEntity("usr_test").notes);
        assertTrue(groups.save()); assertTrue(entities.save());
        assertEquals(unicode, new ScarletWatchedGroups(groupsFile.toFile()).getWatchedGroup("grp_test").notes);
        Path good = directory.resolve("good.json"), bad = directory.resolve("bad.json");
        Files.writeString(good, "[\"日本語\"]"); Files.writeString(bad, "[\"échec\"]");
        ScarletPronounLists lists = new ScarletPronounLists(good.toFile(), bad.toFile());
        assertTrue(lists.isKnownGood("日本語")); assertTrue(lists.isKnownBad("échec"));
    }

    @Test void separateDataHomesSharePreferencesButExcludeOfflineImport() throws Exception
    {
        Path user = Files.createDirectory(directory.resolve("synthetic-os-user"));
        Path firstHome = directory.resolve("home-a"), secondHome = directory.resolve("home-b");
        String executable = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ExecutorService outputReader = Executors.newSingleThreadExecutor();
        Process child = null;
        try (ProcLock.Lease firstData = ProcLock.acquireDataDirectory(firstHome.toFile()))
        {
            try (ProcLock.Lease firstPrefs = ProcLock.acquirePreferences(user.toFile(), true))
            {
                child = new ProcessBuilder(executable, "-cp", classpath, SharedLeaseChild.class.getName(),
                    user.toString(), secondHome.toString()).redirectErrorStream(true).start();
                final Process observed = child;
                Future<String> ready = outputReader.submit(() -> new java.io.BufferedReader(
                    new java.io.InputStreamReader(observed.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)).readLine());
                assertEquals("READY", ready.get(10, TimeUnit.SECONDS), "second home must be able to share the preference lease");
                assertThrows(java.io.IOException.class, () -> ProcLock.acquirePreferences(user.toFile(), false));
            }
            // The first app has released its preference lease; the second still prevents import.
            assertThrows(java.io.IOException.class, () -> ProcLock.acquirePreferences(user.toFile(), false));
            child.getOutputStream().close();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            try (ProcLock.Lease importer = ProcLock.acquirePreferences(user.toFile(), false)) { assertNotNull(importer); }
        }
        finally
        {
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            outputReader.shutdownNow();
        }
    }

    public static final class SharedLeaseChild
    {
        public static void main(String[] args) throws Exception
        {
            try (ProcLock.Lease data = ProcLock.acquireDataDirectory(new java.io.File(args[1]));
                 ProcLock.Lease preferences = ProcLock.acquirePreferences(new java.io.File(args[0]), true))
            {
                System.out.println("READY"); System.out.flush();
                System.in.read();
            }
        }
    }
}

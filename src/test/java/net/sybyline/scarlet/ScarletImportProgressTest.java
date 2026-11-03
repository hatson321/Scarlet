package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletImportProgressTest
{
    private static final String VALIDATING = "Validating and staging archive entries";
    private static final String PREPARING = "Preparing selected configuration";
    private static final String CAPTURING = "Preparing rollback state (files)";
    private static final String SAVING = "Saving current credentials for rollback";
    private static final String APPLYING = "Applying and verifying imported files";
    private static final String CREDENTIALS = "Applying credentials";
    private static final String ROLLBACK_FILES = "Import failed; restoring original files";
    private static final String ROLLBACK_PREFS = "Restoring original credentials";
    private static final String ROLLBACK_DONE = "Rollback complete; import failed";
    private static final String CLEANING = "Cleaning temporary import files";
    private static final String PREFS = "<preferences EXTERNAL_XML_VERSION=\"1.0\"><root type=\"user\"><map/>"
        + "<node name=\"net\"><map/><node name=\"sybyline\"><map/><node name=\"scarlet\"><map/>"
        + "</node></node></node></root></preferences>";

    @TempDir Path directory;

    @Test void successfulImportReportsOrderedPhasesAndVerifiedCounts() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Map<String, String> entries = entries();
        List<Update> updates = new ArrayList<>();
        AtomicInteger commits = new AtomicInteger();
        ScarletMigration.PreferenceTransaction preferences = preferences(commits, false);

        String summary = ScarletMigration.importBundleContents(bundle(entries).toFile(),
            ScarletMigration.ImportOptions.all(), data.toFile(), preferences,
            (phase, completed, total) -> {
                updates.add(new Update(phase, completed, total));
                if (APPLYING.equals(phase) && completed > 0)
                {
                    // A completed file must already be present with its final contents.
                    try
                    {
                        assertEquals("synthetic first data", Files.readString(data.resolve("first.json")));
                        if (completed >= 2) assertEquals("synthetic second data", Files.readString(data.resolve("second.json")));
                        if (completed >= 3) assertEquals("synthetic incoming key", Files.readString(data.resolve("global-prefs.key")));
                    }
                    catch (IOException failure) { throw new AssertionError(failure); }
                }
            });

        assertNotNull(summary);
        assertEquals(1, commits.get());
        assertOrdered(updates, VALIDATING, PREPARING, CAPTURING, SAVING, APPLYING, CREDENTIALS, CLEANING);
        assertFinishedCount(updates, VALIDATING, entries.size());
        assertFinishedCount(updates, CAPTURING, 3);
        assertFinishedCount(updates, APPLYING, 3);
        assertMonotonicCounts(updates);
        assertFalse(hasPhase(updates, ROLLBACK_FILES));
    }

    @Test void dataOnlyImportCountsAllArchiveEntriesButOnlyAppliesSelectedFiles() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("global-prefs.key"), "synthetic original key");
        Map<String, String> entries = entries();
        entries.put("ignored-directory/", "");
        List<Update> updates = new ArrayList<>();
        ScarletMigration.PreferenceTransaction forbidden = new ScarletMigration.PreferenceTransaction()
        {
            public byte[] backup() { throw new AssertionError("Data-only import must not read preferences"); }
            public void replace(byte[] xml) { throw new AssertionError("Data-only import must not replace preferences"); }
        };

        ScarletMigration.importBundleContents(bundle(entries).toFile(),
            new ScarletMigration.ImportOptions(true, false), data.toFile(), forbidden, record(updates));

        assertFinishedCount(updates, VALIDATING, entries.size());
        assertFinishedCount(updates, APPLYING, 2);
        assertFalse(hasPhase(updates, SAVING));
        assertFalse(hasPhase(updates, CREDENTIALS));
        assertEquals("synthetic original key", Files.readString(data.resolve("global-prefs.key")));
        assertEquals("synthetic first data", Files.readString(data.resolve("first.json")));
    }

    @Test void credentialsOnlyDoesNotApplyOrdinaryDataFiles() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("first.json"), "synthetic existing data");
        List<Update> updates = new ArrayList<>();
        AtomicInteger commits = new AtomicInteger();

        ScarletMigration.importBundleContents(bundle(entries()).toFile(),
            new ScarletMigration.ImportOptions(false, true), data.toFile(), preferences(commits, false), record(updates));

        assertFinishedCount(updates, APPLYING, 1);
        assertEquals(1, commits.get());
        assertTrue(hasPhase(updates, CREDENTIALS));
        assertEquals("synthetic existing data", Files.readString(data.resolve("first.json")));
        assertFalse(Files.exists(data.resolve("second.json")));
        assertEquals("synthetic incoming key", Files.readString(data.resolve("global-prefs.key")));
    }

    @Test void failedPreferenceCommitReportsRollbackAndNeverSuccess() throws Exception
    {
        Path data = originalData();
        List<Update> updates = new ArrayList<>();
        AtomicInteger commits = new AtomicInteger();

        assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(bundle(entries()).toFile(),
            ScarletMigration.ImportOptions.all(), data.toFile(), preferences(commits, true), record(updates)));

        assertEquals(2, commits.get());
        assertOriginalData(data);
        assertOrdered(updates, APPLYING, CREDENTIALS, ROLLBACK_FILES, ROLLBACK_PREFS, ROLLBACK_DONE, CLEANING);
        assertFinishedCount(updates, ROLLBACK_FILES, 3);
        assertFalse(updates.stream().anyMatch(update -> "IMPORT COMPLETE".equalsIgnoreCase(update.phase)));
    }

    @Test void invalidArchivePreferencesNeverReportAppliedFiles() throws Exception
    {
        Path data = originalData();
        Map<String, String> entries = entries();
        entries.put("preferences.xml", "<invalid>");
        List<Update> updates = new ArrayList<>();
        AtomicInteger commits = new AtomicInteger();

        assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(bundle(entries).toFile(),
            ScarletMigration.ImportOptions.all(), data.toFile(), preferences(commits, false), record(updates)));

        assertOriginalData(data);
        assertEquals(0, commits.get());
        assertFalse(hasPhase(updates, APPLYING));
        assertFalse(hasPhase(updates, CREDENTIALS));
        assertTrue(hasPhase(updates, CLEANING));
    }

    @Test void observerFailureDuringApplyIsDisabledWithoutAbortingImport() throws Exception
    {
        Path data = originalData();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger callbacksAfterFailure = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();

        assertDoesNotThrow(() -> ScarletMigration.importBundleContents(bundle(entries()).toFile(),
            ScarletMigration.ImportOptions.all(), data.toFile(), preferences(commits, false),
            (phase, completed, total) -> {
                if (failures.get() > 0) callbacksAfterFailure.incrementAndGet();
                if (APPLYING.equals(phase) && completed > 0)
                {
                    failures.incrementAndGet();
                    throw new IllegalStateException("synthetic progress observer failure");
                }
            }));

        assertEquals(1, failures.get());
        assertEquals(0, callbacksAfterFailure.get());
        assertEquals(1, commits.get());
        assertEquals("synthetic first data", Files.readString(data.resolve("first.json")));
        assertEquals("synthetic second data", Files.readString(data.resolve("second.json")));
        assertEquals("synthetic incoming key", Files.readString(data.resolve("global-prefs.key")));
    }

    @Test void observerFailureDuringRollbackDoesNotPreventRecovery() throws Exception
    {
        Path data = originalData();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger callbacksAfterFailure = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();

        assertThrows(IOException.class, () -> ScarletMigration.importBundleContents(bundle(entries()).toFile(),
            ScarletMigration.ImportOptions.all(), data.toFile(), preferences(commits, true),
            (phase, completed, total) -> {
                if (failures.get() > 0) callbacksAfterFailure.incrementAndGet();
                if (ROLLBACK_FILES.equals(phase))
                {
                    failures.incrementAndGet();
                    throw new IllegalStateException("synthetic rollback observer failure");
                }
            }));

        assertEquals(1, failures.get());
        assertEquals(0, callbacksAfterFailure.get());
        assertEquals(2, commits.get());
        assertOriginalData(data);
    }

    @Test void progressContainsNoEntryNamesPathsOrCredentialContents() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Map<String, String> entries = entries();
        entries.put("data/private-synthetic-member-name.json", "synthetic-secret-token-never-print");
        List<Update> updates = new ArrayList<>();

        ScarletMigration.importBundleContents(bundle(entries).toFile(), ScarletMigration.ImportOptions.all(),
            data.toFile(), preferences(new AtomicInteger(), false), record(updates));

        assertFalse(updates.isEmpty());
        String phases = updates.stream().map(update -> update.phase).collect(java.util.stream.Collectors.joining("\n"));
        for (String forbidden : new String[] { "private-synthetic-member-name", "synthetic-secret-token-never-print",
            "synthetic incoming key", "global-prefs.key", "preferences.xml", "first.json", data.toString(), PREFS })
            assertFalse(phases.contains(forbidden), forbidden);
    }

    @Test void encryptedPreparationCountsTheWholeInputIncludingItsHeader() throws Exception
    {
        Path encrypted = encryptedBundle();
        long expectedBytes = Files.size(encrypted);
        List<Update> decryption = new ArrayList<>();
        List<Update> importing = new ArrayList<>();
        Path data = Files.createDirectory(directory.resolve("import-target"));
        AtomicInteger commits = new AtomicInteger();

        try (ScarletMigration.PreparedBundle prepared = ScarletMigration.prepareBundle(encrypted.toFile(),
            "synthetic-correct-pin".toCharArray(), (phase, detail, done, count, bytes, total) -> {
                decryption.add(new Update(phase, bytes, total));
                return true;
            }))
        {
            assertTrue(Files.isRegularFile(prepared.file.toPath()));
            ScarletMigration.importBundleContents(prepared.file, ScarletMigration.ImportOptions.all(),
                data.toFile(), preferences(commits, false), record(importing));
        }

        assertTrue(decryption.size() > 1, "Synthetic incompressible content must exercise multiple encrypted reads");
        assertFinishedCount(decryption, "Verifying encryption", expectedBytes);
        assertTrue(decryption.stream().allMatch(update -> update.total == expectedBytes));
        assertMonotonicCounts(decryption);
        assertEquals(1, commits.get());
        assertTrue(hasPhase(importing, CREDENTIALS));
        assertArrayEquals(syntheticPayload(), Files.readAllBytes(data.resolve("payload.bin")));
    }

    @Test void encryptedByteCountCompletionDoesNotAuthorizeWrongPinImport() throws Exception
    {
        Path encrypted = encryptedBundle();
        Path data = originalData();
        long expectedBytes = Files.size(encrypted);
        List<Update> decryption = new ArrayList<>();
        List<Update> importing = new ArrayList<>();
        AtomicInteger importAttempts = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();

        IOException failure = assertThrows(IOException.class, () -> {
            try (ScarletMigration.PreparedBundle prepared = ScarletMigration.prepareBundle(encrypted.toFile(),
                "synthetic-wrong-pin".toCharArray(), (phase, detail, done, count, bytes, total) -> {
                    decryption.add(new Update(phase, bytes, total));
                    return true;
                }))
            {
                importAttempts.incrementAndGet();
                ScarletMigration.importBundleContents(prepared.file, ScarletMigration.ImportOptions.all(),
                    data.toFile(), preferences(commits, false), record(importing));
            }
        });

        assertTrue(failure.getMessage().contains("Incorrect PIN"));
        // Reading every encrypted byte is not proof that GCM authentication succeeded.
        assertFinishedCount(decryption, "Verifying encryption", expectedBytes);
        assertMonotonicCounts(decryption);
        assertEquals(0, importAttempts.get());
        assertEquals(0, commits.get());
        assertTrue(importing.isEmpty());
        assertOriginalData(data);
        assertFalse(Files.exists(data.resolve("payload.bin")));
    }

    private Path encryptedBundle() throws IOException
    {
        Path source = Files.createDirectory(directory.resolve("export-source"));
        Files.write(source.resolve("payload.bin"), syntheticPayload());
        Files.writeString(source.resolve("global-prefs.key"), "synthetic encrypted fixture key");
        Path encrypted = directory.resolve("synthetic-progress.encrypted");
        try (ScarletMigration.ExportSession session = ScarletMigration.openExportSession(source.toFile(),
            directory.resolve("synthetic-export-user").toFile(), () -> PREFS.getBytes(StandardCharsets.UTF_8),
            () -> { throw new AssertionError("Progress fixtures must not remove the source"); }))
        {
            session.exportNewBundle(encrypted.toFile(), "synthetic-correct-pin".toCharArray(), null);
        }
        return encrypted;
    }

    private static byte[] syntheticPayload()
    {
        byte[] payload = new byte[24000];
        new java.util.Random(6101L).nextBytes(payload);
        return payload;
    }

    private Path originalData() throws IOException
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("first.json"), "synthetic original data");
        Files.writeString(data.resolve("global-prefs.key"), "synthetic original key");
        return data;
    }

    private static void assertOriginalData(Path data) throws IOException
    {
        assertEquals("synthetic original data", Files.readString(data.resolve("first.json")));
        assertEquals("synthetic original key", Files.readString(data.resolve("global-prefs.key")));
        assertFalse(Files.exists(data.resolve("second.json")));
    }

    private static ScarletMigration.PreferenceTransaction preferences(AtomicInteger commits, boolean failFirst)
    {
        return new ScarletMigration.PreferenceTransaction()
        {
            public byte[] backup() { return PREFS.getBytes(StandardCharsets.UTF_8); }
            public void replace(byte[] xml) throws IOException
            {
                if (commits.incrementAndGet() == 1 && failFirst) throw new IOException("synthetic credential commit failure");
                assertNotNull(xml);
                assertTrue(xml.length > 0);
            }
        };
    }

    private static Map<String, String> entries()
    {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("data/first.json", "synthetic first data");
        entries.put("data/second.json", "synthetic second data");
        entries.put("data/global-prefs.key", "synthetic incoming key");
        entries.put("preferences.xml", PREFS);
        entries.put("scarlet-migration.txt", "synthetic manifest not imported");
        return entries;
    }

    private Path bundle(Map<String, String> entries) throws IOException
    {
        Path archive = Files.createTempFile(directory, "synthetic-progress-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive)))
        {
            for (Map.Entry<String, String> entry : entries.entrySet())
            {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    private static ScarletMigration.ImportProgress record(List<Update> updates)
    { return (phase, completed, total) -> updates.add(new Update(phase, completed, total)); }

    private static boolean hasPhase(List<Update> updates, String phase)
    { return updates.stream().anyMatch(update -> phase.equals(update.phase)); }

    private static void assertFinishedCount(List<Update> updates, String phase, long expected)
    {
        Update last = updates.stream().filter(update -> phase.equals(update.phase)).reduce((left, right) -> right).orElseThrow();
        assertEquals(expected, last.completed, phase);
        assertEquals(expected, last.total, phase);
    }

    private static void assertOrdered(List<Update> updates, String... expected)
    {
        int previous = -1;
        for (String phase : expected)
        {
            int found = -1;
            for (int index = previous + 1; index < updates.size(); index++)
                if (phase.equals(updates.get(index).phase)) { found = index; break; }
            assertTrue(found > previous, "Expected ordered phase: " + phase);
            previous = found;
        }
    }

    private static void assertMonotonicCounts(List<Update> updates)
    {
        Map<String, Long> previous = new LinkedHashMap<>();
        for (Update update : updates)
        {
            assertTrue(update.completed >= -1 && update.total >= -1, update.phase);
            if (update.completed >= 0 && update.total >= 0) assertTrue(update.completed <= update.total, update.phase);
            if (update.completed >= 0)
            {
                assertTrue(update.completed >= previous.getOrDefault(update.phase, -1L), update.phase);
                previous.put(update.phase, update.completed);
            }
        }
    }

    private static final class Update
    {
        final String phase;
        final long completed, total;
        Update(String phase, long completed, long total)
        { this.phase = phase; this.completed = completed; this.total = total; }
    }
}

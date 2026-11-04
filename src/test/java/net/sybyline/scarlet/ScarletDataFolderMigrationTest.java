package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.sybyline.scarlet.Scarlet.DataFolderMigrationResult;
import net.sybyline.scarlet.util.ProcLock;

class ScarletDataFolderMigrationTest
{
    @Test
    void missingDestinationIsCreatedOnlyAfterDeliberateDecline(@TempDir Path tempDir) throws Exception
    {
        Path legacy = Files.createDirectory(tempDir.resolve("earlier"));
        Files.write(legacy.resolve("settings.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Path destination = tempDir.resolve("maintained");

        DataFolderMigrationResult result = Scarlet.copyLegacyDataDir(
            legacy.toFile(),
            destination.toFile(),
            () -> {
                assertFalse(Files.exists(destination), "The prompt must not create the destination");
                return false;
            });

        assertEquals(DataFolderMigrationResult.Kind.DECLINED, result.kind);
        assertTrue(Files.isDirectory(destination));
        assertTrue(Files.isRegularFile(destination.resolve("hatson321-scarlet-logger-data-folder-notice-v1.flag")));
        assertFalse(Files.exists(destination.resolve("settings.json")));
    }

    @Test
    void failedDecisionDoesNotCreateMissingDestination(@TempDir Path tempDir) throws Exception
    {
        Path legacy = Files.createDirectory(tempDir.resolve("earlier"));
        Path destination = tempDir.resolve("maintained");

        DataFolderMigrationResult result = Scarlet.copyLegacyDataDir(
            legacy.toFile(),
            destination.toFile(),
            () -> {
                assertFalse(Files.exists(destination), "The prompt must not create the destination");
                throw new IllegalStateException("simulated prompt cancellation");
            });

        assertEquals(DataFolderMigrationResult.Kind.FAILED, result.kind);
        assertFalse(Files.exists(destination));
    }

    @Test
    void nonEmptyDestinationStopsBeforeDecision(@TempDir Path tempDir) throws Exception
    {
        Path legacy = Files.createDirectory(tempDir.resolve("earlier"));
        Path destination = Files.createDirectory(tempDir.resolve("maintained"));
        Files.write(destination.resolve("existing.txt"), new byte[] { 1 });
        AtomicBoolean decisionRequested = new AtomicBoolean();

        DataFolderMigrationResult result = Scarlet.copyLegacyDataDir(
            legacy.toFile(),
            destination.toFile(),
            () -> {
                decisionRequested.set(true);
                return true;
            });

        assertEquals(DataFolderMigrationResult.Kind.SKIPPED_EXISTING_TARGET, result.kind);
        assertFalse(decisionRequested.get());
        assertTrue(Files.isRegularFile(destination.resolve("existing.txt")));
    }

    @Test
    void destinationLeaseConflictCannotPromptCopyOrAcknowledge(@TempDir Path tempDir) throws Exception
    {
        Path legacy = Files.createDirectory(tempDir.resolve("earlier"));
        Files.writeString(legacy.resolve("settings.json"), "{\"synthetic\":true}");
        Path destination = Files.createDirectory(tempDir.resolve("maintained"));
        AtomicBoolean prompted = new AtomicBoolean();
        try (ProcLock.Lease importer = ProcLock.acquireDataDirectory(destination.toFile()))
        {
            assertThrows(IOException.class, () -> Scarlet.copyLegacyDataDirWithLeases(
                legacy.toFile(), destination.toFile(), () -> { prompted.set(true); return true; },
                tempDir.resolve("synthetic-user").toFile()));
        }
        assertFalse(prompted.get());
        try (java.util.stream.Stream<Path> children = Files.list(destination))
        { assertEquals(0L, children.count(), "No configuration or acknowledgement may be written"); }
    }

    @Test
    void preferenceLeaseConflictCannotCreateHomeAndReleasesDataLease(@TempDir Path tempDir) throws Exception
    {
        Path legacy = Files.createDirectory(tempDir.resolve("earlier"));
        Path destination = tempDir.resolve("maintained"), user = tempDir.resolve("synthetic-user");
        AtomicBoolean prompted = new AtomicBoolean();
        try (ProcLock.Lease importer = ProcLock.acquirePreferences(user.toFile(), false))
        {
            assertThrows(IOException.class, () -> Scarlet.copyLegacyDataDirWithLeases(
                legacy.toFile(), destination.toFile(), () -> { prompted.set(true); return false; }, user.toFile()));
            try (ProcLock.Lease available = ProcLock.acquireDataDirectory(destination.toFile()))
            { assertFalse(Files.exists(destination)); }
        }
        assertFalse(prompted.get());
        assertFalse(Files.exists(destination));
    }

    @Test
    void migrationHoldsBothLeasesAcrossDecisionAndCopy(@TempDir Path tempDir) throws Exception
    {
        Path legacy = Files.createDirectory(tempDir.resolve("earlier"));
        Files.writeString(legacy.resolve("settings.json"), "{\"synthetic\":true}");
        Path destination = tempDir.resolve("maintained"), user = tempDir.resolve("synthetic-user");
        DataFolderMigrationResult result = Scarlet.copyLegacyDataDirWithLeases(
            legacy.toFile(), destination.toFile(), () -> {
                assertFalse(Files.exists(destination));
                assertThrows(IOException.class, () -> ProcLock.acquireDataDirectory(destination.toFile()));
                assertThrows(IOException.class, () -> ProcLock.acquirePreferences(user.toFile(), false));
                return true;
            }, user.toFile());
        assertEquals(DataFolderMigrationResult.Kind.COPIED, result.kind);
        assertEquals("{\"synthetic\":true}", Files.readString(destination.resolve("settings.json")));
        assertTrue(Files.isRegularFile(destination.resolve("hatson321-scarlet-logger-data-folder-notice-v1.flag")));
        try (ProcLock.Lease data = ProcLock.acquireDataDirectory(destination.toFile());
             ProcLock.Lease preferences = ProcLock.acquirePreferences(user.toFile(), false))
        { assertTrue(Files.isDirectory(destination)); }
    }

    @Test
    void staticResolutionDoesNotCreateExplicitDataHome(@TempDir Path tempDir) throws Exception
    {
        Path destination = tempDir.resolve("uncreated-home");
        assertEquals(0, runStartupProbe(tempDir, destination, "resolve"));
        assertFalse(Files.exists(destination), "Class initialization must not create a data home");
    }

    @Test
    void migrationWaitsForRestartingParentToReleaseLease(@TempDir Path tempDir) throws Exception
    {
        Path legacy = Files.createDirectory(tempDir.resolve("earlier"));
        Path destination = tempDir.resolve("maintained"), user = tempDir.resolve("synthetic-user");
        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try (ProcLock.Lease parent = ProcLock.acquireDataDirectory(destination.toFile()))
        {
            java.util.concurrent.Future<?> release = scheduler.schedule(() -> {
                try { parent.close(); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }, 100L, TimeUnit.MILLISECONDS);
            DataFolderMigrationResult result = Scarlet.copyLegacyDataDirWithLeases(
                legacy.toFile(), destination.toFile(), () -> false, user.toFile(), 2_000L);
            release.get(3, TimeUnit.SECONDS);
            assertEquals(DataFolderMigrationResult.Kind.DECLINED, result.kind);
        }
        finally { scheduler.shutdownNow(); }
    }

    @Test
    void failedMainLeaseDoesNotCreateLogOrDataDirectory(@TempDir Path tempDir) throws Exception
    {
        Path destination = tempDir.resolve("uncreated-home");
        try (ProcLock.Lease importer = ProcLock.acquireDataDirectory(destination.toFile()))
        {
            assertTrue(runStartupProbe(tempDir, destination, "main") != 0);
            assertFalse(Files.exists(destination), "Startup failure reporting must not initialize the file logger");
        }
        assertTrue(Files.readString(tempDir.resolve("startup-probe.txt")).contains("startup stopped before obtaining storage access"));
    }

    private static int runStartupProbe(Path tempDir, Path destination, String action) throws Exception
    {
        String executable = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(executable, "-Djava.awt.headless=true",
            "-DSCARLET_HOME=" + destination, "-cp", classpath, StartupProbe.class.getName(), action)
            .redirectErrorStream(true).redirectOutput(tempDir.resolve("startup-probe.txt").toFile()).start();
        try
        {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Synthetic startup probe exceeded its deadline");
            return child.exitValue();
        }
        finally
        {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    public static final class StartupProbe
    {
        public static void main(String[] args) throws Exception
        {
            if ("main".equals(args[0])) Scarlet.main(new String[0]);
            else Class.forName("net.sybyline.scarlet.Scarlet");
        }
    }
}

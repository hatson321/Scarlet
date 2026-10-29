package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletOfflineExportPolicyTest
{
    @TempDir Path directory;

    @Test void exportRequiresANewExternalFilename() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Path nested = Files.createDirectory(data.resolve("nested"));
        assertThrows(IOException.class, () -> ScarletOfflineExport.validateDestination(data, nested.resolve("export.zip")));
        assertThrows(IOException.class, () -> ScarletOfflineExport.validateDestination(data, data));
        Path priorExport = Files.write(directory.resolve("prior.zip"), "preserve".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ScarletOfflineExport.validateDestination(data, priorExport));
        assertEquals("preserve", new String(Files.readAllBytes(priorExport), StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> ScarletOfflineExport.validateDestination(data, directory.resolve("new.zip")));
        assertThrows(IOException.class, () -> ScarletOfflineExport.validateDestination(data, directory.resolve("missing/new.zip")));
    }

    @Test void destinationCannotReachDataThroughASymlink() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Path alias = symlink(directory.resolve("alias"), data);
        assertThrows(IOException.class, () -> ScarletOfflineExport.validateDestination(data, alias.resolve("export.zip")));
        Path external = Files.createDirectory(directory.resolve("external"));
        Path dangling = symlink(external.resolve("export.zip"), external.resolve("missing.zip"));
        assertThrows(IOException.class, () -> ScarletOfflineExport.validateDestination(data, dangling));
    }

    @Test void cleanupOnlyRemovesCachesTtsAndOlderScarletLogs() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Path caches = Files.createDirectories(data.resolve("caches/nested"));
        Path tts = Files.createDirectory(data.resolve("tts"));
        Path logs = Files.createDirectory(data.resolve("logs"));
        Path config = Files.write(data.resolve("settings.json"), new byte[] { 1, 2 });
        Path key = Files.write(data.resolve("global-prefs.key"), new byte[] { 3, 4 });
        Path cache = Files.write(caches.resolve("cached.json"), new byte[3]);
        Path audio = Files.write(tts.resolve("generated.wav"), new byte[4]);
        Path oldLog = Files.write(logs.resolve("scarlet_log_2026-01-01_00-00-00.txt"), new byte[5]);
        Path latestLog = Files.write(logs.resolve("scarlet_log_2026-01-02_00-00-00.txt"), new byte[6]);
        Path other = Files.write(logs.resolve("user-notes.txt"), new byte[7]);
        ScarletOfflineExport.CleanupResult result = ScarletOfflineExport.cleanup(data);
        assertEquals(12L, result.bytes);
        assertEquals(0, result.failures);
        assertFalse(Files.exists(cache));
        assertFalse(Files.exists(audio));
        assertFalse(Files.exists(oldLog));
        assertTrue(Files.exists(latestLog));
        assertTrue(Files.exists(other));
        assertArrayEquals(new byte[] { 1, 2 }, Files.readAllBytes(config));
        assertArrayEquals(new byte[] { 3, 4 }, Files.readAllBytes(key));
        assertTrue(Files.isDirectory(data.resolve("caches")));
        assertTrue(Files.isDirectory(tts));
    }

    @Test void cleanupDoesNotTraverseExternalLinksOrCountTheirBytes() throws Exception
    {
        Path data = Files.createDirectory(directory.resolve("data"));
        Path caches = Files.createDirectory(data.resolve("caches"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path protectedFile = Files.write(outside.resolve("keep.txt"), new byte[19]);
        symlink(caches.resolve("external"), outside);
        symlink(data.resolve("tts"), outside);
        assertEquals(0L, ScarletOfflineExport.cleanupSize(data));
        ScarletOfflineExport.CleanupResult result = ScarletOfflineExport.cleanup(data);
        assertEquals(0L, result.bytes);
        assertEquals(2, result.failures);
        assertTrue(Files.isSymbolicLink(caches.resolve("external")));
        assertTrue(Files.isSymbolicLink(data.resolve("tts")));
        assertArrayEquals(new byte[19], Files.readAllBytes(protectedFile));
    }

    @Test void removalRequiresTenSecondsOfActualElapsedTime()
    {
        long start = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(3L);
        assertFalse(ScarletOfflineExport.wipeDelayElapsed(start, start));
        assertFalse(ScarletOfflineExport.wipeDelayElapsed(start, start + TimeUnit.SECONDS.toNanos(10L) - 1));
        assertTrue(ScarletOfflineExport.wipeDelayElapsed(start, start + TimeUnit.SECONDS.toNanos(10L)));
        assertTrue(ScarletOfflineExport.wipeDelayElapsed(start, start + TimeUnit.SECONDS.toNanos(20L)));
    }

    private Path symlink(Path link, Path target) throws IOException
    {
        try { return Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | IOException unavailable)
        {
            assumeTrue(false, "Symbolic-link creation is unavailable on this host");
            throw unavailable;
        }
    }
}

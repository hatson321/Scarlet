package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScarletCleanupContainmentTest
{
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = { "symlink", "junction" })
    void topLevelAliasesAreNeitherCountedNorCleaned(String kind) throws Exception
    {
        for (String category : new String[] { "caches", "tts", "logs" })
        {
            Path root = Files.createDirectory(directory.resolve("data-" + category));
            Path outside = Files.createDirectory(directory.resolve("outside-" + category));
            Path oldest = Files.write(outside.resolve("scarlet_log_2026-01-01_00-00-00.txt"), new byte[19]);
            Path newest = Files.write(outside.resolve("scarlet_log_2026-01-02_00-00-00.txt"), new byte[23]);
            Path alias = root.resolve(category);
            createAlias(kind, alias, outside);
            try
            {
                assertEquals(0L, ScarletOfflineExport.cleanupSize(root));
                assertEquals(1, ScarletOfflineExport.cleanup(root).failures);
                assertTrue(ScarletCacheCleanup.collectEligible(root, Long.MAX_VALUE, null, null).isEmpty());
                assertArrayEquals(new byte[19], Files.readAllBytes(oldest));
                assertArrayEquals(new byte[23], Files.readAllBytes(newest));
            }
            finally { Files.deleteIfExists(alias); }
        }
    }

    @ParameterizedTest @ValueSource(strings = { "symlink", "junction" })
    void nestedAliasesAndCyclesAreSkippedByBothCleanupRoutes(String kind) throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path caches = Files.createDirectory(root.resolve("caches"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path protectedFile = Files.write(outside.resolve("entry.json"), new byte[29]);
        Path alias = caches.resolve("usr"), cycle = caches.resolve("cycle");
        createAlias(kind, alias, outside);
        try
        {
            createAlias(kind, cycle, caches);
            Path normal = Files.createDirectories(caches.resolve("ordinary"));
            Files.write(normal.resolve("cache.json"), new byte[7]);
            assertEquals(7L, ScarletOfflineExport.cleanupSize(root));
            List<File> selected = ScarletCacheCleanup.collectEligible(root, Long.MAX_VALUE, null, null);
            assertEquals(1, selected.size());
            assertEquals("cache.json", selected.get(0).getName());
            ScarletOfflineExport.CleanupResult result = ScarletOfflineExport.cleanup(root);
            assertEquals(7L, result.bytes);
            assertEquals(2, result.failures);
            assertArrayEquals(new byte[29], Files.readAllBytes(protectedFile));
        }
        finally { Files.deleteIfExists(cycle); Files.deleteIfExists(alias); }
    }

    @ParameterizedTest @ValueSource(strings = { "symlink", "junction" })
    void aliasedDataRootIsRefusedBeforeTraversal(String kind) throws Exception
    {
        Path target = Files.createDirectory(directory.resolve("actual-data"));
        Path cache = Files.createDirectories(target.resolve("caches/usr"));
        Path sentinel = Files.write(cache.resolve("entry.json"), new byte[31]);
        Path alias = directory.resolve("data-alias");
        createAlias(kind, alias, target);
        try
        {
            assertThrows(IOException.class, () -> ScarletOfflineExport.cleanupSize(alias));
            assertThrows(IOException.class, () -> ScarletOfflineExport.cleanup(alias));
            assertTrue(ScarletCacheCleanup.collectEligible(alias, Long.MAX_VALUE, null, null).isEmpty());
            assertFalse(ScarletCacheCleanup.deleteCollectedFile(alias, alias.resolve("caches/usr/entry.json").toFile()));
            assertArrayEquals(new byte[31], Files.readAllBytes(sentinel));
        }
        finally { Files.deleteIfExists(alias); }
    }

    @ParameterizedTest @ValueSource(strings = { "symlink", "junction" })
    void directoryReplacedAfterSelectionCannotRedirectDeletion(String kind) throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path cache = Files.createDirectories(root.resolve("caches/usr"));
        Files.write(cache.resolve("entry.json"), new byte[3]);
        List<File> selected = ScarletCacheCleanup.collectEligible(root, Long.MAX_VALUE, null, null);
        assertEquals(1, selected.size());
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path sentinel = Files.write(outside.resolve("entry.json"), new byte[37]);
        Path moved = root.resolve("retained-cache");
        Files.move(cache, moved);
        createAlias(kind, cache, outside);
        try
        {
            assertFalse(ScarletCacheCleanup.deleteCollectedFile(root, selected.get(0)));
            assertArrayEquals(new byte[37], Files.readAllBytes(sentinel));
            assertArrayEquals(new byte[3], Files.readAllBytes(moved.resolve("entry.json")));
        }
        finally { Files.deleteIfExists(cache); }
    }

    @Test void ordinaryCleanupRetainsActiveRecentAndUnmanagedFiles() throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path tts = Files.createDirectory(root.resolve("tts"));
        Path logs = Files.createDirectory(root.resolve("logs"));
        Path caches = Files.createDirectories(root.resolve("caches/usr"));
        Path old = Files.write(tts.resolve("old.wav"), new byte[5]);
        Path recent = Files.write(tts.resolve("recent.wav"), new byte[7]);
        Path activeLog = Files.write(logs.resolve("scarlet_log_2026-01-01_00-00-00.txt"), new byte[11]);
        Path activeTail = Files.write(caches.resolve("protected.json"), new byte[13]);
        Path unmanaged = Files.write(root.resolve("settings.json"), new byte[17]);
        for (Path path : new Path[] { old, activeLog, activeTail, unmanaged })
            Files.setLastModifiedTime(path, FileTime.fromMillis(1L));
        List<File> selected = ScarletCacheCleanup.collectEligible(root, 1000L, activeLog.toFile(), activeTail.toFile());
        assertEquals(java.util.Collections.singletonList(old.toFile()), selected);
        assertTrue(ScarletCacheCleanup.deleteCollectedFile(root, old.toFile()));
        assertFalse(Files.exists(old));
        assertFalse(ScarletCacheCleanup.deleteCollectedFile(root, unmanaged.toFile()));
        for (Path path : new Path[] { recent, activeLog, activeTail, unmanaged }) assertTrue(Files.exists(path));
    }

    @ParameterizedTest @ValueSource(strings = { "symlink", "junction" })
    void dataRootReplacedAfterSelectionCannotRedirectDeletion(String kind) throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path tts = Files.createDirectory(root.resolve("tts"));
        Files.write(tts.resolve("entry.wav"), new byte[3]);
        List<File> selected = ScarletCacheCleanup.collectEligible(root, Long.MAX_VALUE, null, null);
        assertEquals(1, selected.size());
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path externalTts = Files.createDirectory(outside.resolve("tts"));
        Path sentinel = Files.write(externalTts.resolve("entry.wav"), new byte[43]);
        Path moved = directory.resolve("retained-data");
        Files.move(root, moved);
        createAlias(kind, root, outside);
        try
        {
            assertFalse(ScarletCacheCleanup.deleteCollectedFile(root, selected.get(0)));
            assertArrayEquals(new byte[43], Files.readAllBytes(sentinel));
            assertArrayEquals(new byte[3], Files.readAllBytes(moved.resolve("tts/entry.wav")));
        }
        finally { Files.deleteIfExists(root); }
    }

    @Test void linkedFilesCannotBeSelectedOrDeleted() throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path tts = Files.createDirectory(root.resolve("tts"));
        Path sentinel = Files.write(directory.resolve("outside.wav"), new byte[41]);
        Path alias = tts.resolve("linked.wav");
        createAlias("symlink", alias, sentinel);
        try
        {
            assertTrue(ScarletCacheCleanup.collectEligible(root, Long.MAX_VALUE, null, null).isEmpty());
            assertFalse(ScarletCacheCleanup.deleteCollectedFile(root, alias.toFile()));
            assertEquals(0L, ScarletOfflineExport.cleanupSize(root));
            assertEquals(1, ScarletOfflineExport.cleanup(root).failures);
            assertArrayEquals(new byte[41], Files.readAllBytes(sentinel));
        }
        finally { Files.deleteIfExists(alias); }
    }

    private static void createAlias(String kind, Path alias, Path target) throws Exception
    {
        if ("junction".equals(kind))
        {
            assumeTrue(System.getProperty("os.name", "").startsWith("Windows"), "Junctions are Windows-specific");
            Process command = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", alias.toString(), target.toString())
                .redirectErrorStream(true).start();
            boolean finished = command.waitFor(10L, TimeUnit.SECONDS);
            if (!finished) command.destroyForcibly();
            assumeTrue(finished && command.exitValue() == 0, "Synthetic junction creation unavailable");
        }
        else
        {
            try { Files.createSymbolicLink(alias, target); }
            catch (IOException | UnsupportedOperationException unavailable)
            { assumeTrue(false, "Synthetic symbolic-link creation unavailable on this host"); }
        }
    }
}

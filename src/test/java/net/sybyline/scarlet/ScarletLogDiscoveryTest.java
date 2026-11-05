package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletLogDiscoveryTest
{
    @TempDir Path directory;
    static ScarletVRChatLogs.Listener listener(java.util.function.BiConsumer<String, Object[]> callback)
    {
        return (ScarletVRChatLogs.Listener) Proxy.newProxyInstance(ScarletLogDiscoveryTest.class.getClassLoader(),
            new Class<?>[]{ScarletVRChatLogs.Listener.class}, (proxy, method, args) -> {
                callback.accept(method.getName(), args); return null;
            });
    }

    @Test void mixedInvalidNamesCannotHideTheNewestValidSession() throws Exception
    {
        for (String name : List.of("output_log_backup.txt", "output_log_2026-99-12_12-00-00.txt",
            "output_log_2026-02-30_12-00-00.txt", "output_log_2026-12-12_25-00-00.txt",
            "output_log_2026-10-12_12-00-00.txt.bak", "output_log_2026-10-12_12-00-00.txt"))
            Files.createFile(directory.resolve(name));
        Path newest = Files.createFile(directory.resolve("output_log_2026-11-04_12-00-00.txt"));
        Files.createDirectory(directory.resolve("output_log_2027-01-01_00-00-00.txt"));
        try (ScarletVRChatLogs logs = new ScarletVRChatLogs(listener((name, args) -> {})) {
            @Override File logDirectory() { return directory.toFile(); }
        })
        { assertEquals(newest.toFile(), logs.locateTarget()); }
    }

    @Test void initiallyMissingDirectoryCanAppearWithoutRestartingScarlet() throws Exception
    {
        Path missing = directory.resolve("synthetic-later");
        CountDownLatch caughtUp = new CountDownLatch(1);
        AtomicInteger pauses = new AtomicInteger();
        try (ScarletVRChatLogs logs = new ScarletVRChatLogs(listener((name, args) -> {
            if (name.equals("log_catchUp") && args[0] != null) caughtUp.countDown();
        })) {
            @Override File logDirectory() { return missing.toFile(); }
            @Override boolean discoveryPause()
            {
                try
                {
                    if (pauses.incrementAndGet() == 1)
                    {
                        Files.createDirectory(missing);
                        Files.writeString(missing.resolve("output_log_2026-11-04_12-00-00.txt"),
                            "2026.11.04 12:00:00 Debug      -  synthetic entry\n");
                    }
                    return pauses.get() <= 2;
                }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
        })
        {
            logs.start();
            assertTrue(caughtUp.await(3, TimeUnit.SECONDS));
            assertEquals(1, pauses.get());
        }
    }

    @Test void directoryPollingFindsNewAndRemovedSessionsAmongUnrelatedFiles() throws Exception
    {
        for (int index = 0; index < 400; index++) Files.createFile(directory.resolve("unrelated-" + index + ".txt"));
        Path older = Files.createFile(directory.resolve("output_log_2026-11-04_12-00-00.txt"));
        try (ScarletVRChatLogs logs = new ScarletVRChatLogs(listener((name, args) -> {})) {
            @Override File logDirectory() { return directory.toFile(); }
        })
        {
            assertEquals(older.toFile(), logs.locateTarget());
            Path newer = Files.createFile(directory.resolve("output_log_2026-11-04_13-00-00.txt"));
            assertEquals(newer.toFile(), logs.locateTarget());
            Files.delete(newer); assertEquals(older.toFile(), logs.locateTarget());
            Files.delete(older); assertNull(logs.locateTarget());
        }
    }

    @Test void failedInitialisationDoesNotReplaySameFileInTightLoop() throws Exception
    {
        Files.writeString(directory.resolve("output_log_2026-11-04_12-00-00.txt"), "synthetic\n");
        AtomicInteger initialisations = new AtomicInteger();
        CountDownLatch paused = new CountDownLatch(1);
        try (ScarletVRChatLogs logs = new ScarletVRChatLogs(listener((name, args) -> {
            if (name.equals("log_init")) { initialisations.incrementAndGet(); throw new IllegalStateException("synthetic"); }
        })) {
            @Override File logDirectory() { return directory.toFile(); }
            @Override boolean discoveryPause() { paused.countDown(); return false; }
        })
        {
            logs.start();
            assertTrue(paused.await(3, TimeUnit.SECONDS));
            assertEquals(1, initialisations.get());
            assertFalse(logs.clientExitTrackingHealthy());
        }
    }
}

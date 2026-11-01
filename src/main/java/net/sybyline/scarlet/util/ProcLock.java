package net.sybyline.scarlet.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProcLock
{

    /** Held by the running application and by offline import, never both. */
    public static Lease acquireDataDirectory(File dataDirectory) throws IOException
    {
        File canonical = dataDirectory.getCanonicalFile();
        File lockFile = new File(canonical.getParentFile(), canonical.getName() + ".scarlet-data.lock");
        return acquire(lockFile, false);
    }

    /** Stable OS-user location: never derived from SCARLET_HOME or a group folder. */
    public static File preferencesLockDirectory()
    {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        String directory = System.getenv(windows ? "LOCALAPPDATA" : "HOME");
        return new File(directory == null || directory.trim().isEmpty() ? System.getProperty("user.home") : directory);
    }

    /** Normal processes share this lease; an offline importer requires it exclusively. */
    public static Lease acquirePreferences(File userDirectory, boolean shared) throws IOException
    {
        return acquire(new File(userDirectory.getCanonicalFile(), ".scarlet-preferences.lock"), shared);
    }

    public static Lease acquirePreferences(File userDirectory, boolean shared, long waitMillis) throws IOException
    {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMillis));
        while (true)
        {
            try { return acquirePreferences(userDirectory, shared); }
            catch (IOException busy)
            {
                if (System.nanoTime() >= deadline) throw busy;
                try { Thread.sleep(50L); }
                catch (InterruptedException interrupted)
                { Thread.currentThread().interrupt(); throw new IOException("Interrupted while waiting for Scarlet preference access", interrupted); }
            }
        }
    }

    private static Lease acquire(File lockFile, boolean shared) throws IOException
    {
        java.nio.file.Files.createDirectories(lockFile.getParentFile().toPath());
        FileChannel channel = new RandomAccessFile(lockFile, "rw").getChannel();
        try
        {
            FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, shared);
            if (lock == null) throw new IOException("Scarlet storage is in use; close every Scarlet instance before importing.");
            return new Lease(channel, lock);
        }
        catch (IOException | RuntimeException ex)
        {
            channel.close();
            throw new IOException("Cannot acquire Scarlet storage access; close every Scarlet instance before importing.", ex);
        }
    }

    /** Bounded startup wait allows a restarting parent process to exit and release its lease. */
    public static Lease acquireDataDirectory(File dataDirectory, long waitMillis) throws IOException
    {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMillis));
        while (true)
        {
            try { return acquireDataDirectory(dataDirectory); }
            catch (IOException busy)
            {
                if (System.nanoTime() >= deadline) throw busy;
                try { Thread.sleep(50L); }
                catch (InterruptedException interrupted)
                { Thread.currentThread().interrupt(); throw new IOException("Interrupted while waiting for Scarlet data access", interrupted); }
            }
        }
    }

    public static final class Lease implements AutoCloseable
    {
        private final FileChannel channel;
        private final FileLock lock;
        private Lease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        public void close() throws IOException
        {
            try { if (this.lock.isValid()) this.lock.release(); }
            finally { this.channel.close(); }
            // Never unlink lock files: a second process may already hold the same inode.
        }
    }

    @SuppressWarnings("resource")
    public static boolean tryLock(File file)
    {
        try
        {
            FileChannel channel = new RandomAccessFile(file, "rw").getChannel();
            FileLock lock = channel.tryLock();
            if (lock == null)
            {
                channel.close();
                throw new IOException();
            }
            locks.add(() ->
            {
                MiscUtils.close(lock);
                MiscUtils.close(channel);
                file.delete();
            });
            return true;
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    private static final List<Runnable> locks = Collections.synchronizedList(new ArrayList<>());
    public static void releaseAll()
    {
        List<Runnable> copy;
        synchronized (locks)
        {
            copy = new ArrayList<>(locks);
            locks.clear();
        }
        for (Runnable lock : copy)
        {
            try
            {
                lock.run();
            }
            catch (Throwable ignored)
            {
            }
        }
    }
    static
    {
        Runtime.getRuntime().addShutdownHook(new Thread(ProcLock::releaseAll, "ProcLock.shutdownHook"));
    }

}

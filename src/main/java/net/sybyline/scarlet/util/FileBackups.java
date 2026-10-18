package net.sybyline.scarlet.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crash-safe saves for Scarlet's small JSON data files (watched lists, settings,
 * Discord config, ...). These files are a moderation team's crown jewels — a
 * half-written {@code watched_groups.json} after a crash or power loss would
 * silently lose a community's entire blocklist.
 *
 * <p>{@link #write(File, int, WriteOperation)} writes to a unique temporary sibling file first,
 * and only when the serialization callback returns normally: (1) copies the previous version into a {@code backups/}
 * folder next to the file, stamped with the current date-time and pruned to the
 * newest {@code keep} copies, then (2) atomically moves the temp file into place
 * (failing safely where the filesystem lacks atomic moves). If the
 * write fails midway, the original file is untouched. Content is always UTF-8.
 *
 * <p>Recovery is manual and simple by design: pick a dated file out of
 * {@code backups/} and copy it back.
 */
public final class FileBackups
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/FileBackups");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Default number of dated backups retained per file. */
    public static final int DEFAULT_KEEP = 10;
    private static final ReentrantLock[] SAVE_LOCKS = new ReentrantLock[256];
    static
    {
        Arrays.setAll(SAVE_LOCKS, i -> new ReentrantLock());
    }

    @FunctionalInterface
    public interface WriteOperation<T>
    {
        void write(T output) throws IOException;
    }

    private static ReentrantLock lockFor(File target) throws IOException
    {
        String path = target.getCanonicalPath();
        if (File.separatorChar == '\\') path = path.toLowerCase(java.util.Locale.ROOT);
        return SAVE_LOCKS[(path.hashCode() & Integer.MAX_VALUE) % SAVE_LOCKS.length];
    }

    public static void write(File target, WriteOperation<Writer> operation) throws IOException
    {
        write(target, DEFAULT_KEEP, operation);
    }

    public static void write(File target, int keep, WriteOperation<Writer> operation) throws IOException
    {
        ReentrantLock lock = lockFor(target);
        lock.lock();
        try (AtomicWriter output = writer(target, keep))
        {
            operation.write(output);
            output.commit();
        }
        finally { lock.unlock(); }
    }

    /** Byte-oriented equivalent used for credential and migration storage. */
    public static void writeBytes(File target, int keep, WriteOperation<java.io.OutputStream> operation) throws IOException
    {
        ReentrantLock lock = lockFor(target);
        lock.lock();
        Path temp = null;
        try
        {
            Path destination = target.getAbsoluteFile().toPath();
            Files.createDirectories(destination.getParent());
            temp = Files.createTempFile(destination.getParent(), target.getName() + ".", ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp.toFile()))
            {
                operation.write(output);
                output.flush();
                output.getFD().sync();
            }
            commit(temp.toFile(), target, keep);
        }
        finally
        {
            try { if (temp != null) Files.deleteIfExists(temp); }
            finally { lock.unlock(); }
        }
    }

    private FileBackups()
    {
        throw new UnsupportedOperationException();
    }

    /**
     * A UTF-8 transaction. Closing aborts unpublished output; callers must explicitly
     * {@link AtomicWriter#commit()} after successful serialization. Prefer {@link #write}.
     */
    public static AtomicWriter writer(File target, int keep) throws IOException
    {
        File parent = target.getAbsoluteFile().getParentFile();
        Files.createDirectories(parent.toPath());
        File temp = Files.createTempFile(parent.toPath(), target.getName() + ".", ".tmp").toFile();
        FileOutputStream fos = new FileOutputStream(temp);
        Writer out = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
        return new AtomicWriter(out, fos, temp, target, keep);
    }

    public static final class AtomicWriter extends FilterWriter
    {
        private final FileOutputStream stream;
        private final File temp, target;
        private final int keep;
        private boolean closed;
        private AtomicWriter(Writer out, FileOutputStream stream, File temp, File target, int keep)
        {
            super(out);
            this.stream = stream; this.temp = temp; this.target = target; this.keep = keep;
        }
        public void commit() throws IOException
        {
            if (this.closed) throw new IOException("Save transaction is already closed");
            this.flush();
            this.stream.getFD().sync();
            super.close();
            this.closed = true;
            ReentrantLock lock = lockFor(this.target);
            lock.lock();
            try { FileBackups.commit(this.temp, this.target, this.keep); }
            finally { lock.unlock(); }
        }
        @Override public void close() throws IOException
        {
            try
            {
                if (!this.closed) { this.closed = true; super.close(); }
            }
            finally { Files.deleteIfExists(this.temp.toPath()); }
        }
    }

    /** As {@link #writer(File, int)} with the default retention. */
    public static AtomicWriter writer(File target) throws IOException
    {
        return writer(target, DEFAULT_KEEP);
    }

    static void commit(File temp, File target, int keep) throws IOException
    {
        if (target.isFile())
            backup(target, keep);
        // Never retry an arbitrary I/O failure with a destructive/non-atomic replacement.
        // Filesystems without atomic sibling renames report failure and retain the old target.
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        // Best-effort: fsync the containing directory so the rename itself is durable across a
        // crash. POSIX only -- Windows can't fsync a directory handle, so failures are ignored.
        fsyncDir(target.getAbsoluteFile().getParentFile());
    }

    private static void fsyncDir(File dir)
    {
        if (dir == null)
            return;
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ))
        {
            ch.force(true);
        }
        catch (Exception ignore)
        {
            // Directory fsync is unsupported on some platforms (e.g. Windows); ignore.
        }
    }

    private static void backup(File target, int keep)
    {
        if (keep <= 0)
            return;
        try
        {
            File dir = new File(target.getAbsoluteFile().getParentFile(), "backups");
            if (!dir.isDirectory() && !dir.mkdirs())
                return;
            String name = target.getName(),
                   stamped = name + "." + LocalDateTime.now().format(STAMP) + ".bak";
            Files.copy(target.toPath(), new File(dir, stamped).toPath(), StandardCopyOption.REPLACE_EXISTING);
            prune(dir, name, keep);
        }
        catch (Exception ex)
        {
            // A failed backup must never block the save itself.
            LOG.warn("Could not back up {}: {}", target, ex.toString());
        }
    }

    private static void prune(File dir, String baseName, int keep)
    {
        File[] backups = dir.listFiles((d, n) -> n.startsWith(baseName + ".") && n.endsWith(".bak"));
        if (backups == null || backups.length <= keep)
            return;
        Arrays.sort(backups, Comparator.comparing(File::getName).reversed()); // newest first (stamp sorts lexically)
        for (int i = keep; i < backups.length; i++)
            if (!backups[i].delete())
                LOG.debug("Could not prune old backup {}", backups[i]);
    }

    /**
     * Dated backups for {@code target} (from the sibling {@code backups/} folder), newest first.
     * The timestamp stamp sorts lexically, so a reverse name sort is newest-first. Empty when none.
     * Used to recover a file whose primary copy was truncated/emptied by an unclean shutdown.
     */
    public static File[] backupsNewestFirst(File target)
    {
        File dir = new File(target.getAbsoluteFile().getParentFile(), "backups");
        File[] backups = dir.listFiles((d, n) -> n.startsWith(target.getName() + ".") && n.endsWith(".bak"));
        if (backups == null)
            return new File[0];
        Arrays.sort(backups, Comparator.comparing(File::getName).reversed());
        return backups;
    }
}

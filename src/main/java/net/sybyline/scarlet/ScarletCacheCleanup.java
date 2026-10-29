package net.sybyline.scarlet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.log.ScarletLogger;

/**
 * Manages periodic deletion of stale cached files: TTS .wav files, Scarlet log
 * files, and JSON API-cache files.
 *
 * <p>Safety rules:
 * <ul>
 *   <li>The Scarlet log file written by the current session is never deleted.</li>
 *   <li>The VRChat output_log file currently being tailed is never deleted.</li>
 *   <li>If Scarlet appears to be actively in use (a VRChat log is being tailed
 *       or an instance is loaded) when files are due, a dismissible popup is
 *       shown and the actual deletion is deferred until the next idle check
 *       rather than proceeding immediately.</li>
 *   <li>Cleanup runs at startup (after login) and then every 6 hours on the
 *       background worker thread pool, so long-running sessions are covered.</li>
 * </ul>
 *
 * <p>All settings are exposed in the UI under the "Cache Cleanup" section:
 * <ul>
 *   <li>{@code cache_cleanup_enabled} — master on/off toggle (default true)</li>
 *   <li>{@code cache_cleanup_days}    — files older than N days are eligible
 *       (1–30, default 7)</li>
 * </ul>
 */
public class ScarletCacheCleanup
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/CacheCleanup");

    /** How often the periodic check fires while Scarlet is running (ms). */
    private static final long CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);

    /** The minimum elapsed time since last cleanup before we act again (ms).
     *  Prevents double-running if Scarlet is restarted quickly. */
    private static final long MIN_BETWEEN_CLEANUPS_MS = TimeUnit.HOURS.toMillis(1);

    /** How long to wait before retrying a cleanup that was deferred because
     *  Scarlet was actively in use (minutes). Much shorter than CHECK_INTERVAL_MS
     *  so cleanup isn't silently skipped for hours during a long session. */
    private static final long RETRY_DELAY_MINUTES = 15L;

    // ── Directories / file categories ─────────────────────────────────────────

    /** Keep the configured path, not an alias-resolved destination, for every cleanup check. */
    private final Path dataRoot;

    private final Scarlet scarlet;

    /**
     * True while a one-shot retry is already queued in the executor.
     * Prevents stacking up multiple pending retries when cleanup is deferred
     * repeatedly across a long session.
     */
    private volatile boolean retryPending = false;

    public ScarletCacheCleanup(Scarlet scarlet)
    {
        this.scarlet     = scarlet;
        this.dataRoot   = Scarlet.dir.toPath().toAbsolutePath().normalize();
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Invoked by the "Run cache cleanup now" button in Settings.
     *
     * <p>Unlike {@link #maybeCleanup()}, this method:
     * <ul>
     *   <li>Always runs regardless of the last-cleanup timestamp or active-use state
     *       (the user explicitly requested it, so we honour that).</li>
     *   <li>Shows a confirmation dialog listing how many files will be deleted and
     *       how much disk space will be freed <em>before</em> touching anything.</li>
     * </ul>
     *
     * <p>Must be called from the modal executor thread ({@code execModal}), not the EDT.
     */
    public void promptAndCleanup()
    {
        int days = this.scarlet.settings.cacheCleanupDays.get();
        long cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);

        List<File> eligible = this.collectEligible(cutoffMs);
        if (eligible.isEmpty())
        {
            this.scarlet.splash.queueFeedbackPopup(
                null,
                4_000L,
                "Nothing to clean",
                "No files older than " + days + " day(s) were found.",
                new java.awt.Color(100, 180, 255),
                new java.awt.Color(60, 130, 200));
            return;
        }

        long totalBytes = 0;
        for (File f : eligible)
            totalBytes += f.length();
        long kb = totalBytes / 1024;

        String message = eligible.size() + " file(s) older than " + days + " day(s) will be\n"
            + "permanently deleted  (" + kb + " KB).\n\nContinue?";

        boolean confirmed = this.scarlet.settings.requireConfirmYesNo(message, "Confirm cache cleanup");
        if (confirmed)
        {
            // Reset the throttle timestamp so deleteFiles records this run correctly.
            this.scarlet.settings.lastCacheCleanup.clear();
            this.deleteFiles(eligible, days);
        }
    }

    /**
     * Called once after startup completes and on the periodic 6-hour schedule.
     * Decides whether to run, defer, or skip based on settings and active-use state.
     */
    public void maybeCleanup()
    {
        if (!this.scarlet.settings.cacheCleanupEnabled.get())
            return;

        // Throttle: don't run if we cleaned recently
        OffsetDateTime lastClean = this.scarlet.settings.lastCacheCleanup.getOrNull();
        if (lastClean != null)
        {
            long msSinceLast = OffsetDateTime.now(ZoneOffset.UTC).toInstant().toEpochMilli()
                             - lastClean.toInstant().toEpochMilli();
            if (msSinceLast < MIN_BETWEEN_CLEANUPS_MS)
                return;
        }

        int days = this.scarlet.settings.cacheCleanupDays.get();
        long cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);

        // Collect all eligible files (excluding protected active files)
        List<File> eligible = this.collectEligible(cutoffMs);
        if (eligible.isEmpty())
        {
            // Nothing to do — still update the timestamp so we don't re-scan constantly
            this.scarlet.settings.lastCacheCleanup.set(OffsetDateTime.now(ZoneOffset.UTC));
            return;
        }

        // Check whether Scarlet is actively in use
        if (this.isActivelyInUse())
        {
            // Show a non-blocking popup warning — don't delete while active
            LOG.info("Cache cleanup deferred: {} file(s) eligible but Hatson321/Scarlet Logger is actively in use. "
                + "Will retry in {} minutes.", eligible.size(), RETRY_DELAY_MINUTES);
            this.scarlet.splash.queueFeedbackPopup(
                null,
                8_000L,
                "Cache cleanup pending",
                eligible.size() + " old file(s) will be removed when Scarlet is idle.",
                new java.awt.Color(255, 200, 60),  // amber — informational
                new java.awt.Color(200, 160, 40)
            );
            // Schedule a short retry rather than waiting for the next 6-hour tick.
            // Guard with retryPending so repeated deferrals don't stack up tasks.
            if (!this.retryPending)
            {
                this.retryPending = true;
                this.scarlet.exec.schedule(
                    this::maybeCleanupSafe,
                    RETRY_DELAY_MINUTES,
                    TimeUnit.MINUTES);
            }
            // Do NOT update lastCacheCleanup — we want the retry to proceed
            return;
        }

        // Safe to delete
        this.deleteFiles(eligible, days);
    }

    /**
     * Schedules periodic cleanup on the Scarlet worker thread pool.
     * Call this once after startup completes.
     */
    public void schedulePeriodicCleanup()
    {
        this.scarlet.exec.scheduleAtFixedRate(
            this::maybeCleanupSafe,
            CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    /** Wrapper that catches and logs any unexpected exception so the scheduler survives. */
    private void maybeCleanupSafe()
    {
        // Clear the flag before running so a fresh retry can be scheduled if
        // this attempt also defers (i.e. Scarlet is still active).
        this.retryPending = false;
        try
        {
            this.maybeCleanup();
        }
        catch (Exception ex)
        {
            LOG.error("Unexpected exception during cache cleanup check", ex);
        }
    }

    /**
     * Returns true if Scarlet is actively doing something that makes it unsafe
     * to delete files mid-operation.
     *
     * "Active" means any of:
     *   - A VRChat output log file is currently being tailed (VRChat is running
     *     and Scarlet is processing log lines from it)
     *   - At least one player is currently shown in the instance table
     *     (an active instance session is in progress)
     */
    private boolean isActivelyInUse()
    {
        // VRChat log is being actively tailed
        if (this.scarlet.logs.currentTarget() != null)
            return true;

        // At least one player is present in the instance table
        if (this.scarlet.ui.hasActivePlayers())
            return true;

        return false;
    }

    /**
     * Collects all files that are older than {@code cutoffMs} across the three
     * managed directories, excluding any file that must not be deleted while
     * Scarlet is running.
     */
    private List<File> collectEligible(long cutoffMs)
    {
        // Active Scarlet log — never delete while running
        File activeLog = ScarletLogger.getActiveLogFile();

        // Active VRChat log — never delete while it is being tailed
        File activeTail = this.scarlet.logs.currentTarget();
        return collectEligible(this.dataRoot, cutoffMs, activeLog, activeTail);
    }

    /** Explicit root/active-file inputs also permit isolated filesystem regression tests. */
    static List<File> collectEligible(Path data, long cutoffMs, File activeLog, File activeTail)
    {
        List<File> result = new ArrayList<>();
        final Path root;
        try { root = ScarletCleanupPaths.checkedRoot(data); }
        catch (IOException | SecurityException invalid)
        {
            LOG.warn("Cache cleanup skipped: data directory could not be verified as an exact path");
            return result;
        }
        File ttsDir = root.resolve("tts").toFile();
        File scarletLogDir = root.resolve("logs").toFile();
        File cachesDir = root.resolve("caches").toFile();

        // ── TTS .wav files ────────────────────────────────────────────────────
        collectFromDir(root, ttsDir, cutoffMs, activeLog, activeTail, result);

        // ── Scarlet log files ─────────────────────────────────────────────────
        // Only touch files matching the known log name pattern
        if (ScarletCleanupPaths.directory(root, scarletLogDir.toPath()))
        {
            File[] logFiles = scarletLogDir.listFiles(
                (dir, name) -> ScarletLogger.lfpattern.matcher(name).find());
            if (logFiles != null)
            {
                for (File f : logFiles)
                {
                    if (isSameFile(f, activeLog) || isSameFile(f, activeTail))
                        continue;  // never touch the live log
                    if (ScarletCleanupPaths.regularFile(root, f.toPath()) && f.lastModified() < cutoffMs)
                        result.add(f);
                }
            }
        }

        // ── JSON cache subdirectories ─────────────────────────────────────────
        if (ScarletCleanupPaths.directory(root, cachesDir.toPath()))
        {
            File[] kindDirs = cachesDir.listFiles(f -> ScarletCleanupPaths.directory(root, f.toPath()));
            if (kindDirs != null)
            {
                for (File kindDir : kindDirs)
                    collectFromDir(root, kindDir, cutoffMs, activeLog, activeTail, result);
            }
        }

        return result;
    }

    /**
     * Robust "same file" check for the live-log guard. Plain {@code File.equals}
     * compares abstract path strings and fails when one side was built relative
     * (the logger can capture {@code Scarlet.dir} before it is assigned during
     * class initialization) and the other absolute — which made cleanup try to
     * delete the active session's log and fail with a file-in-use error. The
     * timestamped log file name is unique per session, so a name match within
     * the logs folder is decisive; canonical paths are tried first.
     */
    private static boolean isSameFile(File file, File activeLog)
    {
        if (file == null || activeLog == null)
            return false;
        if (file.equals(activeLog))
            return true;
        try
        {
            if (file.getCanonicalFile().equals(activeLog.getCanonicalFile()))
                return true;
        }
        catch (java.io.IOException ignored)
        {
        }
        return file.getName().equals(activeLog.getName());
    }

    /**
     * Scans a single directory for files older than cutoffMs, skipping
     * {@code skip1} and {@code skip2} if non-null.
     */
    private static void collectFromDir(Path root, File dir, long cutoffMs,
                                       File skip1, File skip2,
                                       List<File> result)
    {
        if (!ScarletCleanupPaths.directory(root, dir.toPath()))
            return;
        File[] files = dir.listFiles(f -> ScarletCleanupPaths.regularFile(root, f.toPath()));
        if (files == null)
            return;
        for (File f : files)
        {
            if (isSameFile(f, skip1) || isSameFile(f, skip2))
                continue;
            if (f.lastModified() < cutoffMs)
                result.add(f);
        }
    }

    /** Deletes the given files, logging a summary. Updates lastCacheCleanup. */
    private void deleteFiles(List<File> files, int days)
    {
        int deleted = 0, failed = 0;
        long totalBytes = 0;
        for (File f : files)
        {
            // The active file and directory layout can change while a confirmation is open.
            if (isSameFile(f, ScarletLogger.getActiveLogFile()) || isSameFile(f, this.scarlet.logs.currentTarget()))
                continue;
            long size = f.length();
            if (deleteCollectedFile(this.dataRoot, f))
            {
                deleted++;
                totalBytes += size;
                LOG.debug("Deleted stale cache file: {}", f.getAbsolutePath());
            }
            else
            {
                failed++;
                LOG.warn("Failed to delete cache file: {}", f.getAbsolutePath());
            }
        }

        long kb = totalBytes / 1024;
        LOG.info("Cache cleanup complete: {} file(s) deleted ({} KB freed), {} failed. "
                + "Retention policy: {} days.",
                deleted, kb, failed, days);

        if (deleted > 0)
        {
            this.scarlet.splash.queueFeedbackPopup(
                null,
                5_000L,
                "Cache cleaned",
                deleted + " old file(s) removed  \u2022  " + kb + " KB freed",
                new java.awt.Color(60, 200, 90),   // green — success
                new java.awt.Color(40, 160, 70)
            );
        }

        this.scarlet.settings.lastCacheCleanup.set(OffsetDateTime.now(ZoneOffset.UTC));
    }

    static boolean deleteCollectedFile(Path data, File file)
    {
        try
        {
            Path root = ScarletCleanupPaths.checkedRoot(data);
            Path path = file.toPath().toAbsolutePath().normalize();
            if (!ScarletCleanupPaths.regularFile(root, path)) return false;
            Path relative = root.relativize(path);
            if (relative.getNameCount() < 2) return false;
            String category = relative.getName(0).toString();
            if (!category.equals("caches") && !category.equals("tts") && !category.equals("logs")) return false;
            return Files.deleteIfExists(path);
        }
        catch (IOException | SecurityException invalid) { return false; }
    }

}

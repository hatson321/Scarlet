package net.sybyline.scarlet;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.ext.VrcAppData;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.Tail;

public class ScarletVRChatLogs implements Closeable
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/VRChat/Logs");

    private static final String SPAWN_UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern PROP_SPAWN = Pattern.compile(
        "(?:\\[VRCProps\\] Prop|\\[VRCItems\\] Item) (prop_"+SPAWN_UUID+") spawned by (usr_"+SPAWN_UUID+")");
    private static final Pattern AUTHENTICATED_USER = Pattern.compile("(.+) \\((usr_"+SPAWN_UUID+")\\)");

    public ScarletVRChatLogs(Listener listener)
    {
        this.listener = listener;
        
        this.tailThread = new Thread(this::thread);
        this.running = true;
        this.currentTargetIndex = -1;
        this.currentTarget = null;
        this.currentTail = null;
    }

    public interface Listener
    {
        
        void log_init(File file);
        void log_catchUp(File file);
        
        void log_userAuthenticated(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId);
        void log_userQuit(boolean preamble, LocalDateTime timestamp, double lifetimeSeconds);
        
        void log_userJoined(boolean preamble, LocalDateTime timestamp, String location);
        void log_userLeft(boolean preamble, LocalDateTime timestamp);
        
        void log_playerJoined(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId);
        void log_playerLeft(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId);
        void log_playerSwitchAvatar(boolean preamble, LocalDateTime timestamp, String userDisplayName, String avatarDisplayName);
        
        void log_vtkInit(boolean preamble, LocalDateTime timestamp, String targetDisplayName, String nullable_actorDisplayName);
        
        void log_playerSpawnPedestal(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId, String contentType, String contentId);
        void log_playerSpawnSticker(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId, String stickerId);
        void log_playerSpawnProp(boolean preamble, LocalDateTime timestamp, String userId, String propId);
        
        void log_apiRequest(boolean preamble, LocalDateTime timestamp, int index, String method, String url);
        
        void log_videoLoad(boolean preamble, LocalDateTime timestamp, String userDisplayName, String url, String title);
        
    }

    private final Listener listener;

    /** Wall-clock time the tailer last delivered a parsed log entry; 0 before the first. */
    private volatile long lastEntryMillis = 0L;
    private final java.util.concurrent.atomic.AtomicLong startupProgress = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong startupJoins = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong startupAuthentications = new java.util.concurrent.atomic.AtomicLong();
    private volatile String startupUser;
    private volatile long startupTargetScan;

    ScarletStartupRecovery.LogState startupLogState()
    {
        VRChatLogTail tail = this.currentTail;
        long scanAge = this.trackingNowNanos() - this.startupTargetScan;
        boolean healthy = this.clientExitTrackingHealthy()
            || (this.running && this.trackingThreadAlive() && this.currentTail == null && this.currentTarget == null
                && this.startupTargetScan != 0L && scanAge >= 0L && scanAge <= TimeUnit.SECONDS.toNanos(12));
        return new ScarletStartupRecovery.LogState(this.startupProgress.get(), this.startupJoins.get(),
            this.startupAuthentications.get(), this.startupUser, healthy && tail == this.currentTail,
            tail, tail == null ? null : tail.startupFileTime,
            tail == null ? null : tail.startupFailures.snapshot());
    }

    /** @see #lastEntryMillis */
    public long lastEntryMillis()
    {
        return this.lastEntryMillis;
    }

    /**
     * Whether the current live parser has recently finished processing available log
     * entries. Quiet logs remain healthy; stale, stopped or damaged tracking cannot
     * authorize automatic client exit. An entry/parser failure invalidates that entire
     * tail session instead of silently trusting a roster that may have missed events.
     */
    public boolean clientExitTrackingHealthy()
    {
        VRChatLogTail tail = this.currentTail;
        if (!this.running || !this.trackingThreadAlive() || tail == null || this.currentTarget == null
            || !tail.isRunning() || !tail.trackingValid || tail.isInPreamble || tail.completedLoopNanos == 0L)
            return false;
        long elapsed = this.trackingNowNanos() - tail.completedLoopNanos;
        return elapsed >= 0L && elapsed <= TimeUnit.SECONDS.toNanos(2L)
            && this.currentTail == tail && tail.trackingValid && tail.trackingCaughtUp();
    }

    long trackingNowNanos() { return System.nanoTime(); }
    boolean trackingThreadAlive() { return this.tailThread.isAlive() && !this.tailThread.isInterrupted(); }

    /** Best-effort fixed reason codes only; never an alternative to the fresh health predicate. */
    String clientExitTrackingFailure()
    {
        VRChatLogTail tail = this.currentTail;
        if (!this.running) return "tracking-owner-stopped";
        if (!this.trackingThreadAlive()) return "tracking-thread-unavailable";
        if (tail == null || this.currentTarget == null) return "tracking-log-unavailable";
        if (!tail.isRunning()) return "tracking-reader-stopped";
        if (!tail.trackingValid) return "tracking-session-invalidated";
        if (tail.isInPreamble) return "tracking-initial-catchup";
        if (tail.readInProgress) return "tracking-read-in-progress";
        if (!tail.completeRead) return "tracking-incomplete-line-or-read";
        if (!tail.trackingCaughtUp()) return "tracking-unread-bytes-or-file-unavailable";
        if (tail.completedLoopNanos == 0L) return "tracking-awaiting-completed-loop";
        long elapsed = this.trackingNowNanos() - tail.completedLoopNanos;
        if (elapsed < 0L) return "tracking-clock-moved-backwards";
        if (elapsed > TimeUnit.SECONDS.toNanos(2L)) return "tracking-reader-heartbeat-stale";
        return "tracking-state-changed-during-check";
    }

    /**
     * Let a recently healthy reader finish an ordinary append before sampling it.
     * This is never an alternative health proof: callers must still check fresh
     * health afterwards. Call outside observation locks so parser callbacks can run.
     */
    void awaitClientExitTracking()
    {
        VRChatLogTail tail = this.currentTail;
        // At most 1.5 seconds; never extend the existing two-second freshness requirement.
        for (int retry = 0; retry < 75 && !this.clientExitTrackingHealthy(); retry++)
        {
            if (Thread.currentThread().isInterrupted() || !this.running || !this.trackingThreadAlive()
                || tail == null || tail != this.currentTail || this.currentTarget == null
                || !tail.isRunning() || !tail.trackingValid || tail.isInPreamble || tail.lastHealthyLoopNanos == 0L)
                return;
            long elapsed = this.trackingNowNanos() - tail.lastHealthyLoopNanos;
            if (elapsed < 0L || elapsed > TimeUnit.SECONDS.toNanos(2L)) return;
            try { this.trackingPause(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
        }
    }

    void trackingPause() throws InterruptedException { Thread.sleep(20L); }

    private void invalidateClientExitTracking()
    {
        VRChatLogTail tail = this.currentTail;
        if (tail != null)
            tail.invalidateTracking();
    }

    private boolean handleAuthentication(boolean preamble, LocalDateTime timestamp, String text)
    {
        boolean behaviour = text.startsWith("[Behaviour] ");
        String body = behaviour ? text.substring(12) : text;
        String payload;
        if (body.startsWith("[User Authenticated: "))
        {
            if (!body.endsWith("]"))
                throw new IllegalArgumentException("Malformed bracketed authentication log entry");
            payload = body.substring(21, body.length() - 1);
        }
        else if (body.startsWith("User Authenticated: "))
            payload = body.substring(20);
        else
            return false;
        Matcher authenticated = AUTHENTICATED_USER.matcher(payload);
        if (!authenticated.matches() || authenticated.group(1).trim().isEmpty())
            throw new IllegalArgumentException("Malformed authentication identity in log entry");
        this.startupUser = authenticated.group(2);
        this.startupAuthentications.incrementAndGet();
        this.listener.log_userAuthenticated(preamble, timestamp, authenticated.group(1), authenticated.group(2));
        return true;
    }

    private void handleEntry(File file, boolean preamble, LocalDateTime timestamp, String level, String text, List<String> lines)
    {
        this.lastEntryMillis = System.currentTimeMillis();
        int cidx;
        Matcher propSpawn;
        if (this.handleAuthentication(preamble, timestamp, text))
        {
            // Authentication is recognized before the generic Behaviour branch.
        }
        else if (text.startsWith("[Behaviour] "))
        {
            if (text.startsWith("Joining ", 12) && !text.startsWith("or Creating Room: ", 20))
            {
                String location = text.substring(20);
                this.startupJoins.incrementAndGet();
                this.listener.log_userJoined(preamble, timestamp, location);
            }
            else if (text.equals("[Behaviour] Successfully joined room") || text.equals("[Behaviour] Finished entering world."))
            {
                // Additional success markers disarm startup recovery even if a join line was absent.
                this.startupJoins.incrementAndGet();
            }
            else if (text.startsWith("OnLeftRoom", 12))
            {
                this.listener.log_userLeft(preamble, timestamp);
            }
            else if (text.startsWith("OnPlayerJoined ", 12))
            {
                int oparen = text.lastIndexOf(" ("),
                    cparen = text.lastIndexOf(')');
                String displayName = text.substring(27, oparen),
                       userId = text.substring(oparen + 2, cparen);
                this.listener.log_playerJoined(preamble, timestamp, displayName, userId);
            }
            else if (text.startsWith("OnPlayerLeft ", 12))
            {
                int oparen = text.lastIndexOf(" ("),
                    cparen = text.lastIndexOf(')');
                String displayName = text.substring(25, oparen),
                       userId = text.substring(oparen + 2, cparen);
                this.listener.log_playerLeft(preamble, timestamp, displayName, userId);
            }
            else if (text.startsWith("Switching ", 12) && (cidx = text.lastIndexOf(" to avatar ")) != -1)
            {
                String displayName = text.substring(22, cidx),
                       avatarDisplayName = text.substring(cidx + 11, text.length());
                this.listener.log_playerSwitchAvatar(preamble, timestamp, displayName, avatarDisplayName);
            }
        }
        else if (text.startsWith("[API] [") && (cidx = text.indexOf("] Sending ")) != -1)
        {
            int sep1 = text.indexOf(" request to ", cidx + 10),
                idx = MiscUtils.parseIntElse(text.substring(7, cidx), -1);
            String method = text.substring(cidx + 10, sep1).intern(), // intern because is constantly one of a few short strings
                   url = text.substring(sep1 + 12);
            this.listener.log_apiRequest(preamble, timestamp, idx, method, url);
        }
        else if (text.startsWith("[ModerationManager] "))
        {
            if (text.startsWith("A vote kick has been initiated against ", 20) && text.endsWith(", do you agree?"))
            {
                String displayName = text.substring(59, text.length() - 15);
                String targetDisplayName, nullable_actorDisplayName;
                int by = displayName.indexOf(" by ");
                if (by == -1)
                {
                    targetDisplayName = displayName;
                    nullable_actorDisplayName = null;
                }
                else
                {
                    targetDisplayName = displayName.substring(0, by);
                    nullable_actorDisplayName = displayName.substring(by + 4, displayName.length());
                }
                this.listener.log_vtkInit(preamble, timestamp, targetDisplayName, nullable_actorDisplayName);
            }
        }
        else if (text.startsWith("[SharingManager] User ") && (cidx = text.lastIndexOf(") created sharing pedestal for ")) != -1)
        {
            int oidx = text.indexOf(" ("),
                sidx = text.lastIndexOf(" ");
            String userId = text.substring(22, oidx),
                   userDisplayName = text.substring(oidx + 2, cidx),
                   contentType = text.substring(cidx + 31, sidx),
                   contentId = text.substring(sidx + 1);
            this.listener.log_playerSpawnPedestal(preamble, timestamp, userDisplayName, userId, contentType, contentId);
        }
        else if (text.startsWith("[StickersManager] User ") && (cidx = text.lastIndexOf(") spawned sticker ")) != -1)
        {
            int oidx = text.indexOf(" (");
            String userId = text.substring(23, oidx),
                   userDisplayName = text.substring(oidx + 2, cidx),
                   stickerId = text.substring(cidx + 18);
            this.listener.log_playerSpawnSticker(preamble, timestamp, userDisplayName, userId, stickerId);
        }
        else if ((text.startsWith("[VRCProps] Prop ") || text.startsWith("[VRCItems] Item "))
                && (propSpawn = PROP_SPAWN.matcher(text)).matches())
        {
            // Newer clients call props "items" in logs; both formats identify the same event.
            String propId = propSpawn.group(1),
                   userId = propSpawn.group(2);
            this.listener.log_playerSpawnProp(preamble, timestamp, userId, propId);
        }
        else if (text.startsWith("VRCApplication: HandleApplicationQuit at "))
        {
            double lifetimeSeconds = MiscUtils.parseDoubleElse(text.substring(41), Double.NaN);
            this.listener.log_userQuit(preamble, timestamp, lifetimeSeconds);
        }
    // Video players
        else if (text.startsWith("[<color=#9C6994>USharpVideo</color>] Started video load for URL: ") && (cidx = text.lastIndexOf(", requested by ")) != -1)
        {
            String url = text.substring(65, cidx),
                   userDisplayName = text.substring(cidx + 15);
            this.listener.log_videoLoad(preamble, timestamp, userDisplayName, url, "");
        }
        // old version (pre 2025-02-12)
        else if (text.startsWith("[USharpVideo] Started video load for URL: ") && (cidx = text.lastIndexOf(", requested by ")) != -1)
        {
            String url = text.substring(42, cidx),
                   userDisplayName = text.substring(cidx + 15);
            this.listener.log_videoLoad(preamble, timestamp, userDisplayName, url, "");
        }
 /*
        else if (text.startsWith("[VRCX] "))
        {
            VrcxVideoPlay.Info vrcx = VrcxVideoPlay.parse(text);
            if (vrcx.url.isEmpty())
                ;//vrcx.url = ; // TODO : reverse resolve somehow with more log entries?
            this.listener.log_videoLoad(preamble, timestamp, vrcx.owner, vrcx.url, vrcx.title);
        }
//*/
    }

    private final Thread tailThread;
    private volatile boolean running;
    private volatile int currentTargetIndex;
    private volatile File currentTarget;
    private volatile VRChatLogTail currentTail;

    public int currentTargetIndex()
    {
        return this.currentTargetIndex;
    }

    public File currentTarget()
    {
        return this.currentTarget;
    }

    public void start()
    {
        synchronized (this.tailThread)
        {
            if (this.running && !this.tailThread.isAlive())
            {
                this.tailThread.setName("Scarlet VRChat Log Tailer");
                this.tailThread.setDaemon(true);
                this.tailThread.start();
            }
        }
    }

    @Override
    public void close()
    {
        this.running = false;
        this.invalidateClientExitTracking();
        if (this.tailThread.isAlive())
        {
            VRChatLogTail currentTail = this.currentTail;
            if (currentTail != null)
                currentTail.stop();
            this.tailThread.interrupt();
            while (this.tailThread.isAlive()) try
            {
                LOG.info("Waiting for tailer thread...");
                this.tailThread.join(1_000L);
            }
            catch (InterruptedException iex)
            {
                break;
            }
        }
    }

    private void thread()
    {
        if (Thread.currentThread() != this.tailThread)
            throw new IllegalStateException();
        this.running = true;
        this.currentTarget = null;
        this.currentTail = null;
        try
        {
            if (!VrcAppData.DIR.isDirectory())
            {
                this.catchUp(null);
                LOG.warn("The VRChat Client does not seem to be installed on this machine, disabling log tailer-parser!");
                return;
            }
            while (!this.pollTarget())
            {
                this.catchUp(null);
                if (!MiscUtils.sleep(10_000L))
                    return;
                if (!this.running)
                    return;
                LOG.info("Waiting for log file to appear...");
            }
            while (this.running && !this.tailThread.isInterrupted())
            {
                this.invalidateClientExitTracking();
                this.currentTargetIndex++;
                LOG.info("Tailing " + this.currentTarget.getName());
                this.listener.log_init(this.currentTarget);
                this.currentTail = new VRChatLogTail(this.currentTarget);
                try
                {
                    this.currentTail.run();
                }
                catch (Exception ex)
                {
                    this.invalidateClientExitTracking();
                    LOG.error("Exception whilst tailing " + this.currentTarget.getName(), ex);
                }
            }
        }
        catch (Exception ex)
        {
            this.invalidateClientExitTracking();
            LOG.error("Exception in tailer thread", ex);
        }
        finally
        {
            this.invalidateClientExitTracking();
            this.running = false;
            this.currentTarget = null;
            this.currentTail = null;
        }
    }

    private void catchUp(File file)
    {
        if (file != null)
            LOG.info("Caught up with " + file.getName());
        this.listener.log_catchUp(file);
    }

    private boolean pollTarget()
    {
        File nextTarget = this.locateTarget();
        this.startupTargetScan = this.trackingNowNanos();
        if (Objects.equals(this.currentTarget, nextTarget))
            return false;
        this.invalidateClientExitTracking();
        this.currentTarget = nextTarget;
        return true;
    }

    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    File locateTarget()
    {
        File[] files = VrcAppData.DIR.listFiles();
        if (files == null) throw new IllegalStateException("VRChat log directory cannot be enumerated");
        return Optional
            .ofNullable(files)
            .map(Arrays::stream)
            .orElseGet(Stream::empty)
            .filter(file -> file.isFile() && file.getName().startsWith("output_log_") && file.getName().endsWith(".txt"))
            .max(Comparator.comparing($ -> LocalDateTime.parse($.getName().substring(11, 30), dtf)))
            .orElse(null);
    }

    static final Pattern entry = Pattern.compile("\\A(?<ldt>\\d{4}\\.\\d{2}\\.\\d{2}\\s\\d{2}:\\d{2}:\\d{2})\\s(?<lvl>\\w+)\\s+-\\s\\s(?<txt>.+)\\z");
    static final DateTimeFormatter entry_dtf = DateTimeFormatter.ofPattern("yyyy'.'MM'.'dd HH':'mm':'ss");
    static LocalDateTime startupFileTime(File file)
    {
        String name = file.getName();
        if (!name.matches("output_log_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.txt")) return null;
        try { return LocalDateTime.parse(name.substring(11, 30), dtf); }
        catch (java.time.DateTimeException invalid) { return null; }
    }
    class VRChatLogTail extends Tail
    {
        VRChatLogTail(File target)
        {
            super(target, StandardCharsets.UTF_8, 150L, false, false, 8192);
            this.buffer = new ArrayList<>();
            this.lastPolled = ScarletVRChatLogs.this.trackingNowNanos();
            this.isInPreamble = true;
            this.startupFileTime = ScarletVRChatLogs.startupFileTime(target);
        }
        final LocalDateTime startupFileTime;
        final ScarletStartupFailureLoop startupFailures = new ScarletStartupFailureLoop();
        final List<String> buffer;
        long lastPolled;
        volatile boolean isInPreamble;
        volatile boolean trackingValid = true;
        volatile long completedLoopNanos;
        volatile long lastHealthyLoopNanos;
        volatile long completedReadPosition;
        volatile boolean completeRead = true;
        volatile boolean readInProgress;
        LocalDateTime entryLdt;
        String entryLvl, entryTxt;
        @Override
        protected void on_line(boolean inPreamble, String line)
        {
            ScarletVRChatLogs.this.startupProgress.incrementAndGet();
            this.completedLoopNanos = 0L;
            Matcher matcher = entry.matcher(line);
            if (matcher.find())
            {
                this.handleCurrentEntry();
                this.entryLdt = LocalDateTime.parse(matcher.group("ldt"), entry_dtf);
                this.entryLvl = matcher.group("lvl");
                this.entryTxt = matcher.group("txt");
            }
            this.buffer.add(line);
            if (this.isInPreamble && !inPreamble)
                ScarletVRChatLogs.this.catchUp(this.file);
            this.isInPreamble &= inPreamble;
        }
        @Override
        protected void on_eof()
        {
            this.handleCurrentEntry();
            if (this.isInPreamble)
                ScarletVRChatLogs.this.catchUp(this.file);
            this.isInPreamble = false;
        }
        @Override
        protected void on_loop()
        {
            long now = ScarletVRChatLogs.this.trackingNowNanos(),
                 diff = now - this.lastPolled;
            if (diff < 0L || diff >= TimeUnit.SECONDS.toNanos(10L))
            {
                this.lastPolled = now;
                if (ScarletVRChatLogs.this.pollTarget())
                {
                    this.invalidateTracking();
                    this.run = false;
                    return;
                }
            }
            if (this.trackingValid && !this.isInPreamble && this.buffer.isEmpty() && this.isRunning() && this.trackingCaughtUp())
            {
                // Target discovery can involve slow filesystem enumeration. Stamp the
                // completed healthy loop, not the time before that work started.
                long completed = ScarletVRChatLogs.this.trackingNowNanos();
                this.completedLoopNanos = completed;
                this.lastHealthyLoopNanos = completed;
            }
        }

        boolean trackingCaughtUp()
        {
            try
            {
                return this.completeRead && this.file.isFile() && this.file.length() == this.completedReadPosition;
            }
            catch (SecurityException ex)
            {
                this.invalidateTracking();
                return false;
            }
        }

        @Override
        protected long readLines(long fileInitialLength, RandomAccessFile reader) throws IOException
        {
            this.readInProgress = true;
            this.completedLoopNanos = 0L;
            this.completeRead = false;
            try
            {
                long consumed = super.readLines(fileInitialLength, reader);
                this.completedReadPosition = consumed;
                this.completeRead = consumed == reader.length();
                return consumed;
            }
            catch (IOException | RuntimeException ex)
            {
                this.invalidateTracking();
                throw ex;
            }
            finally { this.readInProgress = false; }
        }

        void invalidateTracking()
        {
            this.trackingValid = false;
            this.completedLoopNanos = 0L;
        }

        @Override protected void on_fnf(FileNotFoundException ex) { this.invalidateTracking(); }
        @Override protected void on_rotated() { this.invalidateTracking(); }
        @Override protected void on_exception(Exception ex) { this.invalidateTracking(); }

        @Override public void stop()
        {
            this.invalidateTracking();
            super.stop();
        }
        @Override
        protected boolean isRunning()
        {
            return super.isRunning() && ScarletVRChatLogs.this.running;
//            return super.isRunning() && ScarletVRChatLogs.this.running && !Thread.currentThread().isInterrupted();
        }
        private void handleCurrentEntry()
        {
            if (!this.buffer.isEmpty()) try
            {
                // Headers and continuation-only text are not parsed events. Clearing the
                // entry after dispatch also prevents replaying it for later bare lines.
                if (this.entryLdt != null && this.entryLvl != null && this.entryTxt != null)
                {
                    this.startupFailures.accept(this.isInPreamble, this.entryLdt, this.entryLvl, this.entryTxt,
                        ScarletVRChatLogs.this.trackingNowNanos());
                    ScarletVRChatLogs.this.handleEntry(this.file, this.isInPreamble, this.entryLdt, this.entryLvl, this.entryTxt, this.buffer);
                }
            }
            catch (Exception ex)
            {
                this.invalidateTracking();
                LOG.error("Error handling log line: " + this.entryTxt, ex);
            }
            finally
            {
                this.buffer.clear();
                this.entryLdt = null;
                this.entryLvl = null;
                this.entryTxt = null;
            }
        }
    }

}

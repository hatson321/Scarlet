package net.sybyline.scarlet;

import java.util.Objects;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.sybyline.scarlet.ext.ManagedVrcClient.Client;

/** Startup-only recovery. Log failure evidence never substitutes for process ownership. */
final class ScarletStartupRecovery
{
    static final long STARTUP_GRACE = TimeUnit.MINUTES.toNanos(3);
    static final long QUIET_LOG = TimeUnit.MINUTES.toNanos(2);
    static final long WATCH_LIMIT = TimeUnit.MINUTES.toNanos(10);
    static final int MAX_REPLACEMENTS = 2;

    static final class LogState
    {
        final long progress, joins, authentications;
        final String user;
        final boolean healthy;
        final Object session;
        final LocalDateTime fileTime;
        final ScarletStartupFailureLoop.Evidence failures;
        LogState(long progress, long joins, long authentications, String user, boolean healthy)
        { this(progress, joins, authentications, user, healthy, null, null, null); }
        LogState(long progress, long joins, long authentications, String user, boolean healthy,
                 Object session, LocalDateTime fileTime, ScarletStartupFailureLoop.Evidence failures)
        {
            this.progress = progress; this.joins = joins; this.authentications = authentications;
            this.user = user; this.healthy = healthy; this.session = session; this.fileTime = fileTime; this.failures = failures;
        }
    }
    static final class Launch
    {
        final Client client;
        final LogState beforeDispatch;
        Launch(Client client, LogState beforeDispatch) { this.client = client; this.beforeDispatch = beforeDispatch; }
    }
    interface Environment
    {
        boolean allowed();
        String user();
        long now();
        LogState logs();
        Launch recover(Client expected, BooleanSupplier allowed, BooleanSupplier closeAllowed) throws Exception;
        void log(String fixedReason);
    }
    private static final class Watch
    {
        final Environment environment;
        final Client client;
        final LogState baseline;
        final long started;
        final int replacements;
        long progress, lastProgress;
        boolean unhealthyReported;
        Object startupSession;
        Watch(Environment environment, Launch launch, long now, int replacements)
        {
            this.environment = environment; this.client = launch.client; this.baseline = launch.beforeDispatch;
            this.started = this.lastProgress = now; this.progress = this.baseline.progress; this.replacements = replacements;
        }
    }
    private volatile Watch watch;

    synchronized void start(Environment environment, Launch launch)
    {
        this.watch = null;
        if (environment != null && environment.allowed() && launch != null && launch.client != null && launch.beforeDispatch != null)
        {
            this.watch = new Watch(environment, launch, environment.now(), 0);
            environment.log("watching-auto-launch; minimum-startup=180s; quiet-log=120s; region-failure-cycles=3; maximum-replacements=2");
        }
    }
    synchronized void cancel() { this.watch = null; }
    synchronized void cancelForDifferentDestination(String location)
    {
        if (this.watch != null && !Objects.equals(this.watch.client.requestedLocation(), location))
            this.watch = null;
    }
    boolean active() { return this.watch != null; }

    private boolean allowed(Watch w)
    {
        if (this.watch != w || Thread.currentThread().isInterrupted() || !w.environment.allowed()) return false;
        LogState state = w.environment.logs();
        return state != null && state.joins == w.baseline.joins
            && (w.startupSession == null || state.session == w.startupSession)
            && (state.authentications == w.baseline.authentications || Objects.equals(state.user, w.environment.user()));
    }
    private boolean failureLoop(Watch w, LogState state, long now)
    {
        return state != null && state.session != null && state.session != w.baseline.session
            && state.session == w.startupSession && state.fileTime != null && w.client.requestedAt() != null
            && !state.fileTime.isBefore(LocalDateTime.ofInstant(w.client.requestedAt(), ZoneId.systemDefault()).minusSeconds(2))
            && state.authentications > w.baseline.authentications && Objects.equals(state.user, w.environment.user())
            && state.failures != null && state.failures.first >= w.started && state.failures.repeated(now);
    }
    private boolean closeAllowed(Watch w, LogState observed, boolean loop)
    {
        if (!this.allowed(w)) return false;
        LogState state = w.environment.logs();
        long now = w.environment.now();
        return state != null && state.healthy && state.joins == w.baseline.joins
            && (w.startupSession == null || state.session == w.startupSession)
            && (state.authentications == w.baseline.authentications || Objects.equals(state.user, w.environment.user()))
            && now - w.started >= STARTUP_GRACE && now - w.started < WATCH_LIMIT
            && (loop ? state.session == observed.session && state.authentications == observed.authentications
                        && this.failureLoop(w, state, now)
                     : state.progress == observed.progress && now - w.lastProgress >= QUIET_LOG);
    }
    private synchronized void finish(Watch w, String reason)
    {
        if (this.watch != w) return;
        this.watch = null;
        w.environment.log(reason);
    }

    /** Called only on the serialized client-launch worker, never the log parser or UI. */
    void tick()
    {
        Watch w = this.watch;
        if (w == null) return;
        try
        {
            if (!this.allowed(w)) { this.finish(w, "stopped-join-observed-or-context-changed"); return; }
            long now = w.environment.now();
            if (now - w.started < 0 || now - w.lastProgress < 0) { this.finish(w, "stopped-clock-uncertain"); return; }
            LogState state = w.environment.logs();
            if (state == null) { this.finish(w, "stopped-log-observation-unavailable"); return; }
            if (state.session != null && state.session != w.baseline.session)
            {
                if (w.startupSession == null) w.startupSession = state.session;
                else if (w.startupSession != state.session) { this.finish(w, "stopped-startup-log-session-changed"); return; }
            }
            else if (w.startupSession != null) { this.finish(w, "stopped-startup-log-session-lost"); return; }
            if (state.progress != w.progress) { w.progress = state.progress; w.lastProgress = now; }
            if (now - w.started >= WATCH_LIMIT) { this.finish(w, "watch-expired-no-further-recovery"); return; }
            boolean loop = this.failureLoop(w, state, now);
            if (now - w.started < STARTUP_GRACE || (!loop && now - w.lastProgress < QUIET_LOG)) return;
            if (!state.healthy)
            {
                if (!w.unhealthyReported) w.environment.log("waiting-log-reader-unhealthy-not-proof-of-client-freeze");
                w.unhealthyReported = true;
                return;
            }
            if (w.replacements >= MAX_REPLACEMENTS) { this.finish(w, "replacement-budget-exhausted-manual-action-required"); return; }
            if (!this.closeAllowed(w, state, loop)) return;
            if (loop) w.environment.log("repeated-region-connection-failure-loop-confirmed");
            w.environment.log("attempting-normal-close-and-relaunch-" + (w.replacements + 1) + "-of-2");
            // The transport must verify exact ownership, normal close, complete exit,
            // quiet inventory and fresh target authorization. Never replay an exception.
            Launch next = w.environment.recover(w.client, () -> this.allowed(w), () -> this.closeAllowed(w, state, loop));
            synchronized (this)
            {
                if (this.watch != w) return;
                if (next == null || next.client == null || next.client == w.client || next.beforeDispatch == null)
                { this.finish(w, "replacement-unverified-manual-action-required"); return; }
                this.watch = new Watch(w.environment, next, w.environment.now(), w.replacements + 1);
            }
            w.environment.log("replacement-launched-watching-startup");
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt(); this.finish(w, "cancelled-no-further-recovery");
        }
        catch (Exception | LinkageError ex)
        {
            // No exception bodies, account IDs or private launch links in diagnostics.
            this.finish(w, "recovery-refused-or-uncertain-no-further-retry");
        }
    }
}

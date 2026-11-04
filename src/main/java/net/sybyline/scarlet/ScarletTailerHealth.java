package net.sybyline.scarlet;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Alert-only state; never authorizes client launching, closing or roster trust. */
final class ScarletTailerHealth
{
    static final long STALL_NANOS = TimeUnit.MINUTES.toNanos(5);
    static final long FRESH_NANOS = TimeUnit.SECONDS.toNanos(30);
    static final long RECOVERY_NANOS = TimeUnit.SECONDS.toNanos(30);
    enum Change { NONE, STALLED, RECOVERED }

    static final class Clients
    {
        final Map<String, Instant> identities;
        final long newestStartMillis;
        Clients(Map<String, Instant> identities)
        {
            this.identities = Map.copyOf(identities);
            this.newestStartMillis = identities.values().stream().mapToLong(Instant::toEpochMilli).max().orElse(0L);
        }
    }

    /** Unknown metadata is not evidence of a running or recovered client. */
    static Clients captureClients(List<String> confirmedPids)
    {
        Map<String, Instant> identities = new TreeMap<>();
        try
        {
            for (String pid : confirmedPids)
            {
                ProcessHandle process = ProcessHandle.of(Long.parseLong(pid)).orElse(null);
                if (process == null || !process.isAlive()) return null;
                Instant start = process.info().startInstant().orElse(null);
                if (start == null) return null;
                identities.put(pid, start);
            }
            return new Clients(identities);
        }
        catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    static final class Reader
    {
        final Object session;
        final Long fileStartMillis;
        final long completedLoopNanos;
        final String failure;
        Reader(Object session, Long fileStartMillis, long completedLoopNanos, String failure)
        {
            this.session = session;
            this.fileStartMillis = fileStartMillis;
            this.completedLoopNanos = completedLoopNanos;
            this.failure = failure;
        }
    }

    private Clients clients;
    private boolean observed, unhealthy, alerted, recovering;
    private long lastCheck, unhealthySince, recoverySince, recoveryLoop;
    private Object recoverySession;
    private String reason;

    synchronized Change observe(long now, Clients current, Reader reader)
    {
        if (current == null || current.identities.isEmpty())
        {
            this.clients = null;
            this.observed = this.unhealthy = this.alerted = this.recovering = false;
            return Change.NONE;
        }
        if (this.clients == null || !this.clients.identities.equals(current.identities))
        {
            this.clients = current;
            this.observed = this.unhealthy = this.alerted = this.recovering = false;
        }
        if (this.observed && now - this.lastCheck < 0L)
        {
            this.unhealthy = this.recovering = false;
            this.lastCheck = now;
            return Change.NONE;
        }
        this.observed = true;
        this.lastCheck = now;
        this.reason = failure(now, current, reader);
        if (this.reason != null)
        {
            this.recovering = false;
            if (!this.unhealthy) { this.unhealthy = true; this.unhealthySince = now; }
            if (!this.alerted && now - this.unhealthySince >= STALL_NANOS)
            {
                this.alerted = true;
                return Change.STALLED;
            }
            return Change.NONE;
        }
        this.unhealthy = false;
        if (!this.alerted) return Change.NONE;
        if (!this.recovering || this.recoverySession != reader.session)
        {
            this.recovering = true;
            this.recoverySession = reader.session;
            this.recoverySince = now;
            this.recoveryLoop = reader.completedLoopNanos;
        }
        else if (now - this.recoverySince >= RECOVERY_NANOS && reader.completedLoopNanos - this.recoveryLoop > 0L)
        {
            this.alerted = this.recovering = false;
            return Change.RECOVERED;
        }
        return Change.NONE;
    }

    private static String failure(long now, Clients clients, Reader reader)
    {
        if (reader == null) return "tracking-log-unavailable";
        if (reader.failure != null) return reader.failure;
        // Log filenames have second precision. A previous client's file cannot prove recovery.
        if (reader.fileStartMillis == null || reader.fileStartMillis < clients.newestStartMillis - 5_000L)
            return "tracking-log-session-unconfirmed";
        long age = now - reader.completedLoopNanos;
        if (reader.session == null || reader.completedLoopNanos == 0L || age < 0L || age > FRESH_NANOS)
            return "tracking-reader-heartbeat-stale";
        return null;
    }

    String reason() { return this.reason; }
    long unhealthyMinutes() { return Math.max(0L, this.lastCheck - this.unhealthySince) / TimeUnit.MINUTES.toNanos(1); }
}

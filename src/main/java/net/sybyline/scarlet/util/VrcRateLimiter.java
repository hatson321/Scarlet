package net.sybyline.scarlet.util;

import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * A single global gate for all VRChat API traffic, complementing (not replacing)
 * the per-feature pacers elsewhere in Scarlet.
 *
 * <p>The per-feature limiters stop any one feature from spamming a specific
 * operation; this limiter bounds the <em>aggregate</em> request rate across every
 * feature at once — the situation that actually trips VRChat's limits (e.g. joining
 * a busy instance fires audit polling, avatar hydration for dozens of players, and
 * user/group lookups simultaneously). It does two things:
 *
 * <ol>
 *   <li><b>Burst smoothing</b> — a token bucket caps the sustained request rate
 *       while allowing short bursts.</li>
 *   <li><b>Global backoff</b> — when VRChat answers 429 (or the caller reports a
 *       rate-limit), <em>all</em> subsequent calls wait out the backoff, honoring a
 *       {@code Retry-After} hint when present. So one 429 eases the whole client off
 *       rather than only the feature that received it.</li>
 * </ol>
 *
 * <p>Intended to sit in the VRChat okhttp client's interceptor chain, so every call
 * — generated API methods and hand-built ones alike — passes through it with no
 * change to call sites. Thread-safe; waits happen outside the lock.
 */
public final class VrcRateLimiter
{
    private final double maxPermits;
    private final double refillPerMs;
    private double available;
    private long lastRefillMs;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private int consecutivePenalties;
    private boolean resumeRefillAfterBackoff;

    private long backoffUntilMs = 0L;
    private long lastPenaltyMs = 0L;

    /** Longest single sleep chunk, so backoff changes are noticed promptly. */
    private static final long MAX_SLEEP_CHUNK_MS = 1_000L;
    private static final long MIN_BACKOFF_MS = 30_000L;
    private static final long MAX_POLICY_BACKOFF_MS = 300_000L;
    private static final long PENALTY_RESET_QUIET_MS = 600_000L;

    @FunctionalInterface
    interface Sleeper
    {
        void sleep(long milliseconds) throws InterruptedException;
    }

    /**
     * @param permitsPerSecond sustained request ceiling
     * @param burst            bucket capacity (how many can go back-to-back)
     */
    public VrcRateLimiter(double permitsPerSecond, double burst)
    {
        this(permitsPerSecond, burst, System::currentTimeMillis, Thread::sleep);
    }

    VrcRateLimiter(double permitsPerSecond, double burst, LongSupplier clock, Sleeper sleeper)
    {
        this.refillPerMs = Math.max(0.0001, permitsPerSecond) / 1000.0;
        this.maxPermits = Math.max(1.0, burst);
        this.available = this.maxPermits;
        this.clock = clock;
        this.sleeper = sleeper;
        this.lastRefillMs = this.clock.getAsLong();
    }

    /**
     * Blocks until any active global backoff has elapsed and a token is available.
     * Interruption cancels the wait and fails the call, rather than letting the
     * request bypass its cooldown. The interrupt flag is preserved.
     */
    public void acquire() throws InterruptedIOException
    {
        this.acquireUntil(Long.MAX_VALUE);
    }

    /**
     * Like {@link #acquire()}, but fails when the caller's epoch-millisecond
     * deadline is reached. A caller timing out does not shorten a shared cooldown.
     */
    public void acquireUntil(long deadlineMillis) throws InterruptedIOException
    {
        this.acquireUntil(deadlineMillis, () -> false);
    }

    /**
     * Also observes cancellation between sleep chunks, without relying on a
     * network client's cancellation to interrupt the waiting Java thread.
     */
    public void acquireUntil(long deadlineMillis, BooleanSupplier cancelled) throws InterruptedIOException
    {
        while (true)
        {
            long sleepMs;
            synchronized (this)
            {
                if (Thread.currentThread().isInterrupted())
                    throw interrupted(null);
                if (cancelled.getAsBoolean())
                    throw new InterruptedIOException("VRChat API rate-limit wait was cancelled");
                long now = this.clock.getAsLong();
                if (now >= deadlineMillis)
                    throw new InterruptedIOException("VRChat API rate-limit wait exceeded the request deadline");
                // Check the cooldown under the token lock so a concurrent 429
                // cannot be missed between checking the gate and taking a token.
                if (this.backoffUntilMs > now)
                {
                    sleepMs = positiveDifference(this.backoffUntilMs, now);
                }
                else
                {
                    if (this.resumeRefillAfterBackoff)
                    {
                        // Resume from an empty bucket even if the first caller
                        // arrives well after the cooldown deadline.
                        this.lastRefillMs = now;
                        this.resumeRefillAfterBackoff = false;
                    }
                    long elapsed = positiveDifference(now, this.lastRefillMs);
                    if (elapsed > 0)
                    {
                        this.available = Math.min(this.maxPermits, this.available + elapsed * this.refillPerMs);
                        this.lastRefillMs = now;
                    }
                    if (this.available >= 1.0)
                    {
                        this.available -= 1.0;
                        return;
                    }
                    sleepMs = (long) Math.ceil((1.0 - this.available) / this.refillPerMs);
                }
                sleepMs = Math.min(sleepMs, positiveDifference(deadlineMillis, now));
            }
            try
            {
                this.sleeper.sleep(Math.max(1L, Math.min(sleepMs, MAX_SLEEP_CHUNK_MS)));
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                throw interrupted(ie);
            }
        }
    }

    /**
     * Records a 429 and returns the effective remaining cooldown in milliseconds.
     * Missing, zero, and short server hints still receive a conservative 30-second
     * minimum. Repeated penalties escalate up to five minutes, while larger server
     * hints are always honored. Ten quiet minutes after the last cooldown reset
     * the escalation. This method never retries the failed HTTP request.
     */
    public synchronized long penalize(long backoffMs)
    {
        long now = this.clock.getAsLong();
        if (this.consecutivePenalties > 0 && now >= this.backoffUntilMs
            && positiveDifference(now, this.backoffUntilMs) >= PENALTY_RESET_QUIET_MS)
            this.consecutivePenalties = 0;
        this.consecutivePenalties = Math.min(5, this.consecutivePenalties + 1);
        long policyMs = Math.min(MAX_POLICY_BACKOFF_MS,
            MIN_BACKOFF_MS * (1L << (this.consecutivePenalties - 1)));
        long durationMs = Math.max(policyMs, backoffMs);
        long until = now > Long.MAX_VALUE - durationMs ? Long.MAX_VALUE : now + durationMs;
        this.lastPenaltyMs = now;
        if (until > this.backoffUntilMs)
            this.backoffUntilMs = until;
        // Do not accumulate a full burst while requests are blocked by a 429.
        this.available = 0.0;
        this.lastRefillMs = this.backoffUntilMs;
        this.resumeRefillAfterBackoff = true;
        return positiveDifference(this.backoffUntilMs, now);
    }

    /** Milliseconds of backoff still remaining (0 when not backing off). */
    public synchronized long backoffRemainingMs()
    {
        return positiveDifference(this.backoffUntilMs, this.clock.getAsLong());
    }

    /** Whether the client is currently in a global backoff. */
    public boolean isBackingOff()
    {
        return this.backoffRemainingMs() > 0L;
    }

    /** Epoch millis of the most recent penalty, or 0 if none this session. */
    public synchronized long lastPenaltyMillis()
    {
        return this.lastPenaltyMs;
    }

    private static long positiveDifference(long later, long earlier)
    {
        if (later <= earlier)
            return 0L;
        long difference = later - earlier;
        return difference < 0L ? Long.MAX_VALUE : difference;
    }

    private static InterruptedIOException interrupted(InterruptedException cause)
    {
        InterruptedIOException failure = new InterruptedIOException("Interrupted while waiting for the VRChat API rate limit");
        if (cause != null)
            failure.initCause(cause);
        return failure;
    }
}

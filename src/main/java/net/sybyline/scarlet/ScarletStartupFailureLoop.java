package net.sybyline.scarlet;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/** Per-tail, bounded evidence of the specific pre-join region failure/reset loop. */
final class ScarletStartupFailureLoop
{
    static final String FAILURE = "[Behaviour] Failed to connect to region, status was: Disconnecting";
    static final String RESET = "[Behaviour] <color=red>Resetting game flow because \"Failed to connect to network region\": [None]</color>";
    static final long MIN_SPACING = TimeUnit.SECONDS.toNanos(20);
    static final long MAX_SPAN = TimeUnit.SECONDS.toNanos(120);
    static final long FRESHNESS = TimeUnit.SECONDS.toNanos(45);

    static final class Evidence
    {
        final int count;
        final long first, last;
        Evidence(int count, long first, long last) { this.count = count; this.first = first; this.last = last; }
        boolean repeated(long now)
        {
            return this.count == 3 && this.last - this.first >= 2 * MIN_SPACING
                && this.last - this.first <= MAX_SPAN && now - this.last >= 0 && now - this.last <= FRESHNESS;
        }
    }

    private final long[] observations = new long[3];
    private int count;
    private LocalDateTime pending, lastTimestamp;
    private long pendingAt;

    synchronized void accept(boolean preamble, LocalDateTime timestamp, String level, String text, long now)
    {
        if (preamble || timestamp == null || !"Debug".equals(level)) return;
        if (text.equals("[Behaviour] OnConnectedToMaster") || text.equals("[Behaviour] Successfully joined room")
            || text.equals("[Behaviour] Finished entering world."))
        {
            this.pending = null;
            this.count = 0;
            return;
        }
        if (FAILURE.equals(text))
        {
            this.pending = timestamp;
            this.pendingAt = now;
        }
        else if (RESET.equals(text) && this.pending != null)
        {
            LocalDateTime failure = this.pending;
            this.pending = null; // One reset cannot reuse a preceding failure.
            if (timestamp.isBefore(failure) || timestamp.isAfter(failure.plusSeconds(5))
                || now - this.pendingAt < 0 || now - this.pendingAt > TimeUnit.SECONDS.toNanos(5)) return;
            if (this.lastTimestamp != null && !timestamp.isAfter(this.lastTimestamp.plusSeconds(19))) return;
            if (this.count > 0)
            {
                long gap = now - this.observations[this.count - 1];
                if (gap < MIN_SPACING) return; // Replayed/backlogged/duplicate entries are not live cycles.
                if (gap > MAX_SPAN) this.count = 0;
            }
            if (this.count == 3)
            {
                this.observations[0] = this.observations[1];
                this.observations[1] = this.observations[2];
                this.count = 2;
            }
            this.observations[this.count++] = now;
            this.lastTimestamp = timestamp;
        }
    }

    synchronized Evidence snapshot()
    {
        return new Evidence(this.count, this.observations[0], this.count == 0 ? 0 : this.observations[this.count - 1]);
    }
}

package net.sybyline.scarlet;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Bounded, memory-only operator notice. It never grants permission to stop a process. */
final class ScarletExitEscalation
{
    static final long EMPTY_WAIT = TimeUnit.MINUTES.toNanos(5), EXPIRY = TimeUnit.MINUTES.toNanos(10);
    static final class Notice
    {
        final String token, counts;
        Notice(String token, String counts) { this.token = token; this.counts = counts; }
    }
    private long generation, revision, noticeAt;
    private Long soleSince;
    private String self, token;
    private Object client;
    private boolean offered;

    synchronized void observe(long generation, String self, Set<String> roster, boolean ready, long now)
    {
        boolean sole = ready && self != null && roster.size() == 1 && roster.contains(self);
        if (this.generation != generation || !Objects.equals(this.self, self) || !sole
            || (this.soleSince != null && now - this.soleSince < 0))
        {
            this.generation = generation; this.self = self;
            this.soleSince = null; this.token = null; this.client = null; this.offered = false;
            ++this.revision;
        }
        if (sole && this.soleSince == null) this.soleSince = now;
    }

    synchronized Notice offer(Object client, String counts, long now)
    {
        if (client == null || !this.oldEnough(now) || (this.offered && now - this.noticeAt < EXPIRY)) return null;
        this.client = client; this.noticeAt = now; this.offered = true;
        this.token = UUID.randomUUID().toString();
        return new Notice(this.token, counts);
    }

    synchronized boolean valid(String token, long generation, Object client, long now)
    {
        return token != null && token.equals(this.token) && generation == this.generation
            && client != null && client == this.client && this.oldEnough(now)
            && now - this.noticeAt >= 0 && now - this.noticeAt < EXPIRY;
    }

    synchronized long consume(String token, long generation, Object client, long now)
    {
        if (!this.valid(token, generation, client, now)) return -1L;
        this.token = null;
        return this.revision;
    }

    synchronized boolean sameObservation(long revision, long generation, Object client, long now)
    {
        return revision == this.revision && generation == this.generation && client != null && client == this.client
            && this.oldEnough(now) && now - this.noticeAt >= 0 && now - this.noticeAt < EXPIRY;
    }

    synchronized void revoke() { this.token = null; ++this.revision; }
    private boolean oldEnough(long now) { return this.soleSince != null && now - this.soleSince >= EMPTY_WAIT; }
}

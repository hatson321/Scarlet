package net.sybyline.scarlet;

import java.time.OffsetDateTime;

/** One coalesced creation event, not a launch backlog or a retry queue for native launches. */
final class ScarletDeferredInstanceFollow
{
    static final class Event
    {
        final String location;
        final OffsetDateTime createdAt;

        private Event(String location, OffsetDateTime createdAt)
        {
            this.location = location;
            this.createdAt = createdAt;
        }
    }

    private Event latest;

    synchronized void record(String location, OffsetDateTime createdAt)
    {
        if (location == null || location.trim().isEmpty() || createdAt == null) return;
        if (this.latest == null || createdAt.isAfter(this.latest.createdAt))
            this.latest = new Event(location, createdAt);
    }

    synchronized Event peek() { return this.latest; }

    /** A newer event arriving during hand-off must not be discarded with the earlier one. */
    synchronized void clearIfSame(Event event)
    {
        if (this.latest == event) this.latest = null;
    }
}

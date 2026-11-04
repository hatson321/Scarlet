package net.sybyline.scarlet;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Fail-closed policy for an optional, graceful exit of a managed VRChat client.
 * This class never closes an instance or discovers/kills a process. Integration
 * must supply a fresh live log session, separately prove process ownership, and
 * freshly confirm the server instance is already closed before committing. An
 * audit event alone can describe a scheduled closure and is not sufficient.
 */
final class ScarletClosedInstanceExit
{
    static final long GRACE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern GROUP = Pattern.compile("grp_" + UUID), USER = Pattern.compile("usr_" + UUID);
    private static final Pattern LOCATION = Pattern.compile("wrld_" + UUID + ":[^\\s]+");

    /** A proposal, not permission to close: commit revalidates its generation and revision. */
    static final class Attempt
    {
        private final ScarletClosedInstanceExit owner;
        final long generation, revision;
        final String groupId, location, selfUserId;
        final Instant sessionStartedAt;
        private Attempt(ScarletClosedInstanceExit policy)
        {
            this.owner = policy;
            this.generation = policy.generation;
            this.revision = policy.revision;
            this.groupId = policy.groupId;
            this.location = policy.location;
            this.selfUserId = policy.selfUserId;
            this.sessionStartedAt = policy.sessionStartedAt;
        }
    }

    private boolean enabled, liveCaughtUp, counting, attempted;
    private String groupId, location, selfUserId;
    private Instant sessionStartedAt, closedAt, createdAt;
    private long generation, revision, eligibleSince;
    private Set<String> roster = Collections.emptySet();

    synchronized void configure(boolean enabled, String groupId)
    {
        boolean valid = groupId != null && GROUP.matcher(groupId).matches();
        String checkedGroup = valid ? groupId : null;
        boolean checkedEnabled = enabled && valid;
        if (this.enabled != checkedEnabled || !Objects.equals(this.groupId, checkedGroup))
        {
            this.enabled = checkedEnabled;
            this.groupId = checkedGroup;
            this.reset();
        }
    }

    /** Call for a new log, a world transition, quit, or any uncertain session boundary. */
    synchronized void reset()
    {
        ++this.generation;
        ++this.revision;
        this.location = this.selfUserId = null;
        this.sessionStartedAt = this.closedAt = this.createdAt = null;
        this.liveCaughtUp = this.counting = this.attempted = false;
        this.roster = Collections.emptySet();
    }

    /** Even rejoining the same location creates a different generation. */
    synchronized long beginSession(String location, Instant startedAt)
    {
        this.reset();
        if (this.enabled && startedAt != null && this.validLocation(location))
        {
            this.location = location;
            this.sessionStartedAt = startedAt;
        }
        return this.generation;
    }

    synchronized long generation() { return this.generation; }

    /**
     * A temporarily busy tailer is not a new session. Preserve dated audit proof,
     * but require a fresh full grace period once tracking is healthy again. Never
     * clear the consumed-attempt flag: suspension must not retry a denied close.
     */
    synchronized void suspendCountdown()
    {
        if (this.counting) ++this.revision;
        this.counting = false;
    }

    /**
     * Unknown/incomplete tracking discards closure proof; zero observations never
     * mean an empty instance. Identical live snapshots leave the grace period intact.
     */
    synchronized void updateTracking(long generation, String selfUserId, Collection<String> players, boolean liveCaughtUp)
    {
        if (generation != this.generation) return;
        Set<String> checked = new HashSet<>();
        boolean valid = this.enabled && this.location != null && liveCaughtUp
            && selfUserId != null && USER.matcher(selfUserId).matches()
            && players != null && players.size() <= 10_000;
        if (valid)
        {
            for (String player : players)
            {
                if (player == null || !USER.matcher(player).matches() || !checked.add(player))
                {
                    valid = false;
                    break;
                }
            }
            valid &= checked.contains(selfUserId);
        }
        if (!valid)
        {
            if (this.liveCaughtUp || this.closedAt != null || this.selfUserId != null || !this.roster.isEmpty()) ++this.revision;
            this.liveCaughtUp = this.counting = this.attempted = false;
            this.selfUserId = null;
            this.closedAt = null;
            this.roster = Collections.emptySet();
            return;
        }
        if (!this.liveCaughtUp || !Objects.equals(this.selfUserId, selfUserId) || !this.roster.equals(checked))
        {
            // An account change is a different identity, not a continuation of prior proof.
            if (this.selfUserId != null && !this.selfUserId.equals(selfUserId)) this.closedAt = null;
            ++this.revision;
            this.counting = this.attempted = false;
            this.selfUserId = selfUserId;
            this.roster = checked;
            this.liveCaughtUp = true;
        }
    }

    /** Only an exact, current-session, already-observed server closure is evidence. */
    synchronized boolean confirmClosed(String groupId, String location, Instant eventAt, Instant observedAt)
    {
        if (!this.matchesEvent(groupId, location, eventAt, observedAt) || !this.liveCaughtUp
            || (this.createdAt != null && !eventAt.isAfter(this.createdAt))) return false;
        if (this.closedAt == null)
        {
            this.closedAt = eventAt;
            ++this.revision;
            this.counting = this.attempted = false;
        }
        else if (eventAt.isAfter(this.closedAt))
        {
            // Duplicate/repeated closure evidence cannot restart or repeat a close attempt.
            this.closedAt = eventAt;
        }
        return true;
    }

    /** A newer create/reopen observation invalidates any earlier closure, regardless of arrival order. */
    synchronized void invalidateReopened(String groupId, String location, Instant eventAt, Instant observedAt)
    {
        if (!this.matchesEvent(groupId, location, eventAt, observedAt)) return;
        if (this.createdAt == null || eventAt.isAfter(this.createdAt)) this.createdAt = eventAt;
        if (this.closedAt != null && !eventAt.isBefore(this.closedAt))
        {
            this.closedAt = null;
            ++this.revision;
            this.counting = this.attempted = false;
        }
    }

    synchronized Attempt poll(long nowNanos)
    {
        if (!this.eligible() || this.attempted)
        {
            this.counting = false;
            return null;
        }
        if (!this.counting || nowNanos - this.eligibleSince < 0)
        {
            this.counting = true;
            this.eligibleSince = nowNanos;
            return null;
        }
        return nowNanos - this.eligibleSince >= GRACE_NANOS ? new Attempt(this) : null;
    }

    /**
     * Integration must freshly verify actual server closure before this call.
     * The callback must perform its own final managed-process check and issue only
     * a short, nonblocking graceful-close request. Marking before callback ensures
     * a denied/failed request cannot cause a retry storm. No forced exit fallback.
     */
    synchronized boolean commit(Attempt attempt, long nowNanos, Runnable gracefulClose)
    {
        if (attempt == null || gracefulClose == null || attempt.owner != this || attempt.generation != this.generation
            || attempt.revision != this.revision || !this.eligible() || this.attempted || !this.counting
            || nowNanos - this.eligibleSince < GRACE_NANOS) return false;
        this.attempted = true;
        gracefulClose.run();
        return true;
    }

    private boolean eligible()
    {
        return this.enabled && this.location != null && this.sessionStartedAt != null
            && this.closedAt != null && this.liveCaughtUp && this.selfUserId != null
            && this.roster.size() == 1 && this.roster.contains(this.selfUserId);
    }

    private boolean matchesEvent(String groupId, String location, Instant eventAt, Instant observedAt)
    {
        return this.enabled && this.location != null && this.sessionStartedAt != null
            && Objects.equals(this.groupId, groupId) && this.location.equals(location)
            && eventAt != null && observedAt != null && !eventAt.isBefore(this.sessionStartedAt)
            && !eventAt.isAfter(observedAt);
    }

    private boolean validLocation(String location)
    {
        if (location == null || !LOCATION.matcher(location).matches()) return false;
        String marker = "~group(" + this.groupId + ")";
        int at = location.indexOf("~group(");
        return at >= 0 && location.startsWith(marker, at) && location.indexOf("~group(", at + 1) < 0;
    }
}

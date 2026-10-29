package net.sybyline.scarlet;

import java.io.File;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.util.MiscUtils;

public class ScarletPendingModActions
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/PendingModActions");

    public ScarletPendingModActions(Scarlet scarlet, File pendingModActionsFile)
    {
        this(scarlet, pendingModActionsFile, System::currentTimeMillis);
    }

    ScarletPendingModActions(Scarlet scarlet, File pendingModActionsFile, LongSupplier clock)
    {
        this.scarlet = scarlet;
        this.pendingModActionsFile = pendingModActionsFile;
        this.clock = clock;
        this.pendingModActions = new ConcurrentHashMap<>();
        this.pendingStartedAt = new HashMap<>();
        this.pendingTokens = new HashMap<>();
        this.pendingBanInfo = new ConcurrentHashMap<>();
        this.timedBans = new ConcurrentHashMap<>();
        this.timedBanWriteRetries = new HashMap<>();
        this.load();
    }

    final Scarlet scarlet;
    final File pendingModActionsFile;
    final Map<String, String> pendingModActions;
    // A lost response or missed audit event must not block moderation forever.
    static final long PENDING_TIMEOUT_MILLIS = 10L * 60L * 1000L;
    final LongSupplier clock;
    final Map<String, Long> pendingStartedAt;
    final Map<String, String> pendingTokens;
    final Map<String, BanInfo> pendingBanInfo;
    private boolean loadFailed;
    // Users banned with an automatic expiry. Keyed by VRChat user id. Persisted so a
    // pending unban survives restarts (a 7-day ban must outlive the app being closed).
    final Map<String, TimedBan> timedBans;
    // Only this process knows a confirmed remote result after its following save failed.
    // After a restart the durable PREPARING/UNBAN_SUBMITTED record stays quarantined.
    private final Map<String, TimedBan> timedBanWriteRetries;

    public static class DataSpec
    {
        public Map<String, String> pendingModActions;
        public Map<String, Long> pendingStartedAt;
        public Map<String, String> pendingTokens;
        public Map<String, BanInfo> pendingBanInfo;
        public Map<String, TimedBan> timedBans;
    }
    public static class TimedBan
    {
        public TimedBan()
        {
        }
        public TimedBan(long expiresAt, String actorUserId)
        {
            this.expiresAt = expiresAt;
            this.actorUserId = actorUserId;
        }
        /** Epoch milliseconds at which the automatic unban becomes due. */
        public long expiresAt;
        /** VRChat user id of the moderator who set the ban, so the unban is attributed to them. */
        public String actorUserId;
        /** The one group this timer belongs to; never inferred for legacy records. */
        public String groupId;
        /** Distinguishes successive bans of the same user. */
        public String recordId;
        public TimedBanState state;
        /** Server identity from the successful ban response, never inferred from current membership. */
        public String originalBanAt;
        public String originalBanActorId;
        /** Bound only after a complete, unambiguous history check. */
        public String originalAuditId;
        public String reviewReason;
        public long nextVerificationAt;

        boolean hasBanIdentity()
        {
            try
            {
                return this.originalBanActorId != null && !this.originalBanActorId.isEmpty()
                    && this.originalBanAt != null && java.time.Instant.parse(this.originalBanAt) != null;
            }
            catch (RuntimeException ex) { return false; }
        }

        public boolean isActiveFor(String currentGroupId)
        {
            return this.state == TimedBanState.ACTIVE && this.groupId != null
                && this.groupId.equals(currentGroupId) && this.recordId != null
                && !this.recordId.isEmpty() && this.actorUserId != null
                && !this.actorUserId.isEmpty() && this.expiresAt > 0L;
        }

        TimedBan copy()
        {
            TimedBan copy = new TimedBan(this.expiresAt, this.actorUserId);
            copy.groupId = this.groupId;
            copy.recordId = this.recordId;
            copy.state = this.state;
            copy.originalBanAt = this.originalBanAt;
            copy.originalBanActorId = this.originalBanActorId;
            copy.originalAuditId = this.originalAuditId;
            copy.reviewReason = this.reviewReason;
            copy.nextVerificationAt = this.nextVerificationAt;
            return copy;
        }
    }
    public enum TimedBanState
    {
        PREPARING, ACTIVE, UNBAN_SUBMITTED, RETIRED, QUARANTINED
    }
    public static class BanInfo
    {
        public BanInfo()
        {
        }
        public String ownerSnowflake;
        public String actorUserId;
        public String vrchatGroupId;
        public String discordGuildId;
        public String description;
        public String[] tags;
    }

    public synchronized boolean hasPending(GroupAuditType type, String targetUserId)
    {
        this.expirePending(type.id+":"+targetUserId);
        return this.pendingModActions.containsKey(type.id+":"+targetUserId);
    }

    public synchronized String addPending(GroupAuditType type, String targetUserId, String actorUserId)
    {
        String key = type.id+":"+targetUserId;
        this.expirePending(key);
        String prevActorUserId = this.pendingModActions.get(key);
        if (prevActorUserId == null)
            this.reservePending(type, targetUserId, actorUserId);
        return prevActorUserId;
    }

    /** A per-attempt token prevents a delayed failed response clearing a newer request. */
    public static final class PendingReservation
    {
        private final String key, token;

        private PendingReservation(String key, String token)
        {
            this.key = key;
            this.token = token;
        }
    }

    public synchronized PendingReservation reservePending(GroupAuditType type, String targetUserId, String actorUserId)
    {
        return this.reservePending(type, targetUserId, actorUserId,
            this.scarlet == null || this.scarlet.vrc == null ? null : this.scarlet.vrc.groupId);
    }

    public synchronized PendingReservation reservePending(GroupAuditType type, String targetUserId, String actorUserId,
        String expectedGroupId)
    {
        java.util.Objects.requireNonNull(actorUserId, "actorUserId");
        String key = type.id+":"+targetUserId;
        this.expirePending(key);
        if (this.pendingModActions.containsKey(key))
            return null;
        if (type == GroupAuditType.USER_BAN || type == GroupAuditType.USER_UNBAN)
        {
            String opposite = (type == GroupAuditType.USER_BAN ? GroupAuditType.USER_UNBAN : GroupAuditType.USER_BAN).id+":"+targetUserId;
            this.expirePending(opposite);
            if (this.pendingModActions.containsKey(opposite))
                return null;
        }
        // A new ban must not inherit an old automatic-unban deadline. Commit this
        // retirement with the reservation, before any caller may submit the ban.
        TimedBan retired = type == GroupAuditType.USER_BAN ? this.timedBans.get(targetUserId) : null;
        if (retired != null)
        {
            if (!retired.isActiveFor(expectedGroupId))
                return null; // legacy, other-group and uncertain records require review, never reassignment
            this.timedBans.remove(targetUserId);
        }
        String token = UUID.randomUUID().toString();
        this.pendingModActions.put(key, actorUserId);
        this.pendingStartedAt.put(key, this.clock.getAsLong());
        this.pendingTokens.put(key, token);
        if (!this.save())
        {
            this.pendingModActions.remove(key);
            this.pendingStartedAt.remove(key);
            this.pendingTokens.remove(key);
            if (retired != null) this.timedBans.put(targetUserId, retired);
            throw new IllegalStateException("Cannot persist pending moderation request; action was not started");
        }
        if (this.scarlet != null)
            this.scarlet.pollAuditSoon();
        return new PendingReservation(key, token);
    }

    public synchronized boolean releasePending(PendingReservation reservation)
    {
        if (reservation == null || !reservation.token.equals(this.pendingTokens.get(reservation.key)))
            return false;
        this.removePending(reservation.key);
        return true;
    }

    private void expirePending(String key)
    {
        Long started = this.pendingStartedAt.get(key);
        if (started != null && this.clock.getAsLong() - started.longValue() >= PENDING_TIMEOUT_MILLIS)
        {
            LOG.warn("Pending moderation request expired; a fresh membership check is required before retrying");
            this.removePending(key);
        }
    }

    public synchronized String getPending(GroupAuditType type, String targetUserId)
    {
        this.expirePending(type.id+":"+targetUserId);
        return this.pendingModActions.get(type.id+":"+targetUserId);
    }

    public synchronized String pollPending(GroupAuditType type, String targetUserId)
    {
        this.expirePending(type.id+":"+targetUserId);
        return this.removePending(type.id+":"+targetUserId);
    }

    /** Immutable, restart-stable ownership proof for the audit receipt handoff. */
    public static final class PendingAttribution
    {
        public final String actorUserId, token;

        private PendingAttribution(String actorUserId, String token)
        {
            this.actorUserId = actorUserId;
            this.token = token;
        }
    }

    public synchronized PendingAttribution peekPendingAttribution(GroupAuditType type, String targetUserId)
    {
        if (this.loadFailed)
            throw new IllegalStateException("Pending attribution storage is unavailable");
        String key = type.id+":"+targetUserId;
        this.expirePending(key);
        String actor = this.pendingModActions.get(key), token = this.pendingTokens.get(key);
        if (actor == null) return null;
        if (this.loadFailed || token == null)
            throw new IllegalStateException("Pending attribution is not safely available");
        return new PendingAttribution(actor, token);
    }

    /** Called only after the destination receipt durably owns the actor and this token. */
    public synchronized boolean acknowledgePendingAttribution(GroupAuditType type, String targetUserId, String token)
    {
        String key = type.id+":"+targetUserId;
        if (token == null || !token.equals(this.pendingTokens.get(key))) return false;
        this.removePending(key);
        return true;
    }

    private String removePending(String key)
    {
        String prevActorUserId = this.pendingModActions.remove(key);
        Long started = this.pendingStartedAt.remove(key);
        String token = this.pendingTokens.remove(key);
        if (prevActorUserId != null)
        {
            if (!this.save())
            {
                this.pendingModActions.put(key, prevActorUserId);
                if (started != null) this.pendingStartedAt.put(key, started);
                if (token != null) this.pendingTokens.put(key, token);
                throw new IllegalStateException("Cannot persist completed moderation request; retained for retry");
            }
            // All Scarlet-initiated actions have been observed in the audit
            // log; no need to keep burst polling.
            if (this.scarlet != null && this.pendingModActions.isEmpty())
                this.scarlet.endAuditFastPoll();
        }
        return prevActorUserId;
    }

    public synchronized boolean addBanInfo(String targetUserId)
    {
        return this.addBanInfo(targetUserId, null);
    }

    public synchronized boolean addBanInfo(String targetUserId, String ownerSnowflake)
    {
        return this.addBanInfo(targetUserId, ownerSnowflake, null, null, null);
    }

    public synchronized boolean addBanInfo(String targetUserId, String ownerSnowflake, String actorUserId,
        String vrchatGroupId, String discordGuildId)
    {
        BanInfo info = new BanInfo();
        info.ownerSnowflake = ownerSnowflake;
        info.actorUserId = actorUserId;
        info.vrchatGroupId = vrchatGroupId;
        info.discordGuildId = discordGuildId;
        if (this.pendingBanInfo.putIfAbsent(targetUserId, info) != null)
            return false;
        this.save();
        return true;
    }

    /** Snapshot the original requester/context without exposing mutable pending state. */
    public synchronized BanInfo getBanInfo(String targetUserId)
    {
        BanInfo info = this.pendingBanInfo.get(targetUserId);
        if (info == null) return null;
        BanInfo copy = new BanInfo();
        copy.ownerSnowflake = info.ownerSnowflake;
        copy.actorUserId = info.actorUserId;
        copy.vrchatGroupId = info.vrchatGroupId;
        copy.discordGuildId = info.discordGuildId;
        copy.description = info.description;
        copy.tags = info.tags == null ? null : info.tags.clone();
        return copy;
    }

    public synchronized boolean isBanInfoOwner(String targetUserId, String ownerSnowflake)
    {
        BanInfo info = this.pendingBanInfo.get(targetUserId);
        if (info == null)
            return false;
        return info.ownerSnowflake == null || info.ownerSnowflake.equals(ownerSnowflake);
    }

    public synchronized boolean setBanInfoTags(String targetUserId, String[] tags)
    {
        BanInfo info = this.pendingBanInfo.get(targetUserId);
        if (info == null)
            return false;
        info.tags = tags;
        this.save();
        return true;
    }

    public synchronized boolean setBanInfoDescription(String targetUserId, String description)
    {
        BanInfo info = this.pendingBanInfo.get(targetUserId);
        if (info == null)
            return false;
        info.description = description;
        this.save();
        return true;
    }

    public synchronized BanInfo pollBanInfo(String targetUserId)
    {
        BanInfo info = this.pendingBanInfo.remove(targetUserId);
        if (info != null)
            this.save();
        return info;
    }

    /** Compatibility import only. Unbound legacy timers are retained for operator review. */
    @Deprecated
    public synchronized void setTimedBan(String targetUserId, long expiresAt, String actorUserId)
    {
        TimedBan previous = this.timedBans.put(targetUserId, new TimedBan(expiresAt, actorUserId));
        if (!this.save())
        {
            if (previous == null) this.timedBans.remove(targetUserId);
            else this.timedBans.put(targetUserId, previous);
            throw new IllegalStateException("Cannot persist automatic-unban record");
        }
    }

    /** Persist before submitting a new timed ban. PREPARING is never automatically unbanned. */
    public synchronized TimedBan prepareTimedBan(String groupId, String targetUserId, long expiresAt, String actorUserId)
    {
        if (MiscUtils.blank(groupId) || MiscUtils.blank(targetUserId) || MiscUtils.blank(actorUserId)
            || expiresAt <= this.clock.getAsLong())
            throw new IllegalArgumentException("A timed ban requires its group, target, actor and future expiry");
        if (this.timedBans.containsKey(targetUserId))
            throw new IllegalStateException("An existing automatic-unban record must be resolved before scheduling another");
        TimedBan timer = new TimedBan(expiresAt, actorUserId);
        timer.groupId = groupId;
        timer.recordId = UUID.randomUUID().toString();
        timer.state = TimedBanState.PREPARING;
        this.timedBans.put(targetUserId, timer);
        if (!this.save())
        {
            this.timedBans.remove(targetUserId);
            throw new IllegalStateException("Cannot persist automatic-unban intent; ban must not be submitted");
        }
        return timer.copy();
    }

    public synchronized boolean activateTimedBan(String targetUserId, TimedBan expected)
    {
        return this.activateTimedBan(targetUserId, expected, null, null);
    }

    public synchronized boolean activateTimedBan(String targetUserId, TimedBan expected,
        java.time.OffsetDateTime serverBanAt, String serverActorId)
    {
        TimedBan timer = this.timedBans.get(targetUserId);
        if (!sameTimedBan(timer, expected) || timer.state != TimedBanState.PREPARING) return false;
        if (serverBanAt == null || MiscUtils.blank(serverActorId))
        {
            this.quarantineTimedBan(targetUserId, timer, "The ban response did not establish its server identity");
            return false;
        }
        timer.originalBanAt = serverBanAt.toInstant().toString();
        timer.originalBanActorId = serverActorId;
        return this.transitionTimedBan(targetUserId, timer, TimedBanState.PREPARING, TimedBanState.ACTIVE, true);
    }

    /** Keep the live record blocked even when the durable quarantine write must be retried. */
    synchronized boolean quarantineTimedBan(String targetUserId, TimedBan expected, String reason)
    {
        TimedBan timer = this.timedBans.get(targetUserId);
        if (!sameTimedBan(timer, expected)) return false;
        timer.state = TimedBanState.QUARANTINED;
        timer.reviewReason = reason;
        this.timedBanWriteRetries.put(targetUserId, timer.copy());
        if (!this.save()) return false;
        this.timedBanWriteRetries.remove(targetUserId);
        return true;
    }

    synchronized boolean confirmTimedBanAudit(String targetUserId, TimedBan expected, String auditId)
    {
        TimedBan timer = this.timedBans.get(targetUserId);
        if (!sameTimedBan(timer, expected) || timer.state != TimedBanState.ACTIVE || MiscUtils.blank(auditId)) return false;
        if (timer.originalAuditId != null && !timer.originalAuditId.equals(auditId)) return false;
        if (auditId.equals(timer.originalAuditId) && timer.reviewReason == null) return true;
        String previousId = timer.originalAuditId, previousReason = timer.reviewReason;
        timer.originalAuditId = auditId;
        timer.reviewReason = null;
        if (this.save()) return true;
        timer.originalAuditId = previousId;
        timer.reviewReason = previousReason;
        return false;
    }

    synchronized void deferTimedBan(String targetUserId, TimedBan expected)
    {
        TimedBan timer = this.timedBans.get(targetUserId);
        if (!sameTimedBan(timer, expected) || timer.state != TimedBanState.ACTIVE) return;
        String reason = "A fresh complete audit history and matching current ban could not be verified";
        timer.nextVerificationAt = this.clock.getAsLong() + 5L * 60L * 1000L;
        if (!reason.equals(timer.reviewReason))
        {
            timer.reviewReason = reason;
            this.save(); // Even a failed notice save never authorizes an unban.
        }
    }

    /** Observed replacement events cancel timers independently of Discord delivery or redaction. */
    synchronized void observeTimedBanAudit(io.github.vrchatapi.model.GroupAuditLogEntryUnknown entry)
    {
        if (entry == null || (!GroupAuditType.USER_BAN.id.equals(entry.getEventType())
            && !GroupAuditType.USER_UNBAN.id.equals(entry.getEventType()))) return;
        TimedBan timer = this.timedBans.get(entry.getTargetId());
        if (timer == null || !java.util.Objects.equals(timer.groupId, entry.getGroupId())
            || timer.state != TimedBanState.ACTIVE) return;
        if (!timer.hasBanIdentity())
        {
            this.quarantineTimedBan(entry.getTargetId(), timer, "Legacy timer has no verified original ban identity");
            return;
        }
        if (entry.getCreatedAt() != null && entry.getCreatedAt().toInstant().isBefore(
            java.time.Instant.parse(timer.originalBanAt).minusSeconds(ScarletTimedBanContinuity.AUDIT_TIME_TOLERANCE_SECONDS))) return;
        if (entry.getCreatedAt() != null && GroupAuditType.USER_BAN.id.equals(entry.getEventType())
            && ScarletTimedBanContinuity.isOriginalCandidate(timer, entry)
            && (timer.originalAuditId == null || timer.originalAuditId.equals(entry.getId()))) return;
        this.quarantineTimedBan(entry.getTargetId(), timer, "A different ban or an intervening unban was observed");
    }

    /** Write-ahead marker: a restart must never repeat an uncertain unban against a later ban. */
    public synchronized boolean beginTimedUnban(String targetUserId, TimedBan expected)
    {
        return this.transitionTimedBan(targetUserId, expected, TimedBanState.ACTIVE, TimedBanState.UNBAN_SUBMITTED, false);
    }

    /** Only a definite rejection proves that the previous unban did not happen. */
    public synchronized boolean retryRejectedTimedUnban(String targetUserId, TimedBan expected)
    {
        return this.transitionTimedBan(targetUserId, expected, TimedBanState.UNBAN_SUBMITTED, TimedBanState.ACTIVE, true);
    }

    private boolean transitionTimedBan(String targetUserId, TimedBan expected, TimedBanState from, TimedBanState to, boolean retry)
    {
        TimedBan timer = this.timedBans.get(targetUserId);
        if (!sameTimedBan(timer, expected) || timer.state != from)
            return false;
        timer.state = to;
        if (!this.save())
        {
            timer.state = from;
            if (retry)
            {
                TimedBan desired = timer.copy();
                desired.state = to;
                this.timedBanWriteRetries.put(targetUserId, desired);
            }
            return false;
        }
        this.timedBanWriteRetries.remove(targetUserId);
        return true;
    }

    /** Retry known results only while their original group remains selected. */
    synchronized void retryTimedBanWrites(String currentGroupId)
    {
        for (Map.Entry<String, TimedBan> entry : new HashMap<>(this.timedBanWriteRetries).entrySet())
        {
            TimedBan desired = entry.getValue(), actual = this.timedBans.get(entry.getKey());
            if (!sameTimedBan(actual, desired))
            {
                this.timedBanWriteRetries.remove(entry.getKey());
                continue;
            }
            if (desired.groupId == null || !desired.groupId.equals(currentGroupId)) continue;
            this.transitionTimedBan(entry.getKey(), actual, actual.state, desired.state, true);
        }
    }

    private static boolean sameTimedBan(TimedBan actual, TimedBan expected)
    {
        return actual != null && expected != null && actual.recordId != null
            && actual.recordId.equals(expected.recordId) && java.util.Objects.equals(actual.groupId, expected.groupId);
    }

    public synchronized TimedBan getTimedBan(String targetUserId)
    {
        TimedBan timer = this.timedBans.get(targetUserId);
        return timer == null ? null : timer.copy();
    }

    public synchronized boolean removeTimedBan(String targetUserId)
    {
        TimedBan timer = this.timedBans.get(targetUserId);
        if (timer == null) return false;
        return this.removeTimedBanRecord(targetUserId, timer);
    }

    public synchronized boolean removeTimedBan(String targetUserId, TimedBan expected)
    {
        TimedBan timer = this.timedBans.get(targetUserId);
        return sameTimedBan(timer, expected) && this.removeTimedBanRecord(targetUserId, timer);
    }

    private boolean removeTimedBanRecord(String targetUserId, TimedBan timer)
    {
        // Persist a tombstone first. A failed deletion can then never revive an
        // automatic unban after restart, even if the user receives a later ban.
        if (timer.recordId != null && timer.state != TimedBanState.RETIRED
            && !this.transitionTimedBan(targetUserId, timer, timer.state, TimedBanState.RETIRED, true))
            return false;
        this.timedBans.remove(targetUserId);
        if (!this.save())
        {
            this.timedBans.put(targetUserId, timer);
            return false;
        }
        this.timedBanWriteRetries.remove(targetUserId);
        return true;
    }

    /** Aggregate notice intentionally contains no user or group identifiers. */
    synchronized String timedBanReviewSummary(String currentGroupId)
    {
        int legacy = 0, otherGroup = 0, uncertain = 0, cleanup = 0;
        for (TimedBan timer : this.timedBans.values())
        {
            if (MiscUtils.blank(timer.groupId) || MiscUtils.blank(timer.recordId) || timer.state == null)
                legacy++;
            else if (!timer.groupId.equals(currentGroupId))
                otherGroup++;
            else if (timer.state == TimedBanState.RETIRED)
                cleanup++;
            else if (!timer.isActiveFor(currentGroupId) || !timer.hasBanIdentity() || timer.reviewReason != null)
                uncertain++;
        }
        if (legacy + otherGroup + uncertain + cleanup == 0) return null;
        return "Automatic-unban records need review: " + legacy + " legacy/unbound, "
            + otherGroup + " for another group, " + uncertain + " unconfirmed, " + cleanup + " awaiting cleanup. "
            + "Automatic unbans are currently blocked pending verification or manual review. Check the original group's bans and pending moderation data; "
            + "never assign legacy timers to the current group without verifying their origin.";
    }

    /** A stable copy for iteration by the expiry sweep. */
    public synchronized Map<String, TimedBan> timedBansSnapshot()
    {
        Map<String, TimedBan> copy = new HashMap<>();
        this.timedBans.forEach((user, timer) -> copy.put(user, timer.copy()));
        return copy;
    }

    public synchronized boolean load()
    {
        if (!this.pendingModActionsFile.exists())
        {
            this.loadFailed = false;
            boolean initialized = this.save();
            this.loadFailed = !initialized;
            return initialized;
        }
        DataSpec spec;
        try (Reader r = MiscUtils.reader(this.pendingModActionsFile))
        {
            spec = Scarlet.GSON_PRETTY.fromJson(r, DataSpec.class);
            if (spec == null || hasNullEntries(spec.pendingModActions) || hasNullEntries(spec.pendingStartedAt)
                || hasNullEntries(spec.pendingTokens)
                || hasNullEntries(spec.pendingBanInfo) || hasNullEntries(spec.timedBans))
                throw new java.io.IOException("Invalid pending moderation data");
        }
        catch (Exception ex)
        {
            LOG.error("Exception loading pending moderation actions", ex);
            this.loadFailed = true;
            return false;
        }
        this.loadFailed = false;
        this.pendingModActions.clear();
        this.pendingStartedAt.clear();
        this.pendingTokens.clear();
        if (spec != null && spec.pendingModActions != null && !spec.pendingModActions.isEmpty())
        {
            this.pendingModActions.putAll(spec.pendingModActions);
        }
        long now = this.clock.getAsLong();
        boolean migrated = false;
        for (String key : new java.util.ArrayList<>(this.pendingModActions.keySet()))
        {
            Long started = spec.pendingStartedAt == null ? null : spec.pendingStartedAt.get(key);
            // Old files had no timestamps. Give them one bounded recovery window,
            // persisted now so restarting cannot renew that window indefinitely.
            if (started == null || started.longValue() > now)
            {
                started = now;
                migrated = true;
            }
            if (now - started.longValue() >= PENDING_TIMEOUT_MILLIS)
            {
                this.pendingModActions.remove(key);
                migrated = true;
            }
            else
            {
                this.pendingStartedAt.put(key, started);
                String token = spec.pendingTokens == null ? null : spec.pendingTokens.get(key);
                if (token == null || token.isEmpty())
                {
                    token = UUID.randomUUID().toString();
                    migrated = true;
                }
                this.pendingTokens.put(key, token);
            }
        }
        this.pendingBanInfo.clear();
        if (spec != null && spec.pendingBanInfo != null && !spec.pendingBanInfo.isEmpty())
        {
            this.pendingBanInfo.putAll(spec.pendingBanInfo);
        }
        this.timedBans.clear();
        this.timedBanWriteRetries.clear();
        if (spec != null && spec.timedBans != null && !spec.timedBans.isEmpty())
        {
            this.timedBans.putAll(spec.timedBans);
            for (TimedBan timer : this.timedBans.values())
                if (timer.state == TimedBanState.ACTIVE && !timer.hasBanIdentity())
                {
                    timer.state = TimedBanState.QUARANTINED;
                    timer.reviewReason = "Legacy timer has no verified original ban identity";
                    migrated = true;
                }
        }
        if (migrated && !this.save())
        {
            this.loadFailed = true;
            return false;
        }
        return true;
    }

    public synchronized boolean save()
    {
        if (this.loadFailed)
        {
            LOG.error("Pending moderation storage could not be loaded; refusing to overwrite it or authorize new moderation");
            return false;
        }
        DataSpec spec = new DataSpec();
        spec.pendingModActions = new HashMap<>(this.pendingModActions);
        spec.pendingStartedAt = new HashMap<>(this.pendingStartedAt);
        spec.pendingTokens = new HashMap<>(this.pendingTokens);
        spec.pendingBanInfo = new HashMap<>(this.pendingBanInfo);
        spec.timedBans = new HashMap<>(this.timedBans);
        try
        {
            net.sybyline.scarlet.util.FileBackups.write(this.pendingModActionsFile,
                w -> Scarlet.GSON_PRETTY.toJson(spec, DataSpec.class, w));
        }
        catch (Exception ex)
        {
            LOG.error("Exception saving pending moderation actions", ex);
            return false;
        }
        return true;
    }

    private static boolean hasNullEntries(Map<?, ?> values)
    {
        return values != null && (values.containsKey(null) || values.containsValue(null));
    }

}

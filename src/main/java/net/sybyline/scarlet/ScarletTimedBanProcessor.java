package net.sybyline.scarlet;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.vrchatapi.model.GroupMemberStatus;
import net.sybyline.scarlet.ScarletPendingModActions.PendingReservation;
import net.sybyline.scarlet.ScarletPendingModActions.TimedBan;
import net.sybyline.scarlet.ScarletPendingModActions.TimedBanState;
import net.sybyline.scarlet.ScarletVRChat.GroupModerationResult;

/** Write-ahead expiry processing, also exercised with offline synthetic API responses. */
final class ScarletTimedBanProcessor
{
    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/TimedBans");

    private ScarletTimedBanProcessor()
    {
    }

    static void process(ScarletPendingModActions pending, Supplier<String> currentGroup, LongSupplier clock,
        BiFunction<String, String, GroupMemberStatus> membership,
        BiFunction<String, String, GroupModerationResult> unban)
    {
        process(pending, currentGroup, clock, membership, (group, user, timer) -> ScarletTimedBanContinuity.unavailable(), unban);
    }

    static void process(ScarletPendingModActions pending, Supplier<String> currentGroup, LongSupplier clock,
        BiFunction<String, String, GroupMemberStatus> membership,
        ScarletTimedBanContinuity.Check continuity,
        BiFunction<String, String, GroupModerationResult> unban)
    {
        String groupId = currentGroup.get();
        if (groupId == null || groupId.isEmpty()) return;
        pending.retryTimedBanWrites(groupId);
        long now = clock.getAsLong();
        for (Map.Entry<String, TimedBan> entry : pending.timedBansSnapshot().entrySet())
        {
            String userId = entry.getKey();
            TimedBan timer = entry.getValue();
            if (timer.groupId == null || !timer.groupId.equals(groupId)) continue;
            if (timer.state != TimedBanState.RETIRED && (!timer.isActiveFor(groupId) || timer.expiresAt > now || timer.nextVerificationAt > now)) continue;
            try
            {
                // Serializes the claim and submission with newer ban reservations,
                // timer removal, and concurrent expiry sweeps for this store.
                synchronized (pending)
                {
                    if (!groupId.equals(currentGroup.get())) return;
                    TimedBan actual = pending.getTimedBan(userId);
                    if (actual == null || !java.util.Objects.equals(timer.recordId, actual.recordId)) continue;
                    if (actual.state == TimedBanState.RETIRED)
                        pending.removeTimedBan(userId, actual);
                    else if (actual.isActiveFor(groupId))
                        processOne(pending, currentGroup, membership, continuity, unban, groupId, userId, actual);
                }
            }
            catch (RuntimeException ex)
            {
                // A failed save/lookup must not terminate the scheduled executor's future sweeps.
                LOG.warn("Timed-ban processing failed for {}; its durable state requires retry or operator review", userId, ex);
            }
        }
    }

    private static void processOne(ScarletPendingModActions pending, Supplier<String> currentGroup,
        BiFunction<String, String, GroupMemberStatus> membership,
        ScarletTimedBanContinuity.Check continuity,
        BiFunction<String, String, GroupModerationResult> unban,
        String groupId, String userId, TimedBan timer)
    {
        if (!groupId.equals(currentGroup.get())) return;
        if (!timer.hasBanIdentity())
        {
            pending.quarantineTimedBan(userId, timer, "Legacy timer has no verified original ban identity");
            return;
        }
        GroupMemberStatus status = membership.apply(groupId, userId);
        if (status == null || !groupId.equals(currentGroup.get())) return;
        if (status != GroupMemberStatus.BANNED)
        {
            // Retire durably before deletion: failed cleanup cannot resurrect a timer
            // against a later, independently imposed ban after the application restarts.
            if (!pending.beginTimedUnban(userId, timer))
            {
                LOG.warn("Cannot persist retirement of the completed timer for {}; no action taken", userId);
                return;
            }
            if (groupId.equals(currentGroup.get()) && !pending.removeTimedBan(userId, timer))
                LOG.warn("Completed timer cleanup failed for {}; retained non-replayable record for operator review", userId);
            return;
        }

        ScarletTimedBanContinuity.Proof proof = continuity.check(groupId, userId, timer);
        TimedBan fresh = pending.getTimedBan(userId);
        if (!groupId.equals(currentGroup.get()) || fresh == null || !fresh.isActiveFor(groupId)
            || !java.util.Objects.equals(timer.recordId, fresh.recordId)) return;
        if (proof != null && proof.verdict == ScarletTimedBanContinuity.Verdict.SUPERSEDED)
        {
            pending.quarantineTimedBan(userId, timer, "The original ban was superseded or its event identity is ambiguous");
            LOG.warn("Automatic unban cancelled: the original ban was superseded or ambiguous; review the retained timer");
            return;
        }
        if (proof == null || proof.verdict != ScarletTimedBanContinuity.Verdict.VERIFIED
            || !pending.confirmTimedBanAudit(userId, timer, proof.originalAuditId))
        {
            pending.deferTimedBan(userId, timer);
            LOG.warn("Automatic unban deferred: complete current ban continuity could not be verified or saved");
            return;
        }

        PendingReservation reservation = pending.reservePending(GroupAuditType.USER_UNBAN, userId, timer.actorUserId, groupId);
        if (reservation == null) return;
        if (!groupId.equals(currentGroup.get()) || !pending.beginTimedUnban(userId, timer))
        {
            pending.releasePending(reservation);
            LOG.warn("Cannot safely persist or authorize the timed unban for {}; no unban was sent", userId);
            return;
        }

        GroupModerationResult result;
        try
        {
            if (!groupId.equals(currentGroup.get()))
            {
                // No request was sent. Keep the record bound to its original group.
                pending.retryRejectedTimedUnban(userId, timer);
                pending.releasePending(reservation);
                return;
            }
            // Endpoint receives the recorded group explicitly, never a fresh mutable setting.
            result = unban.apply(groupId, userId);
        }
        catch (RuntimeException ex)
        {
            LOG.warn("Timed unban outcome is unknown for {}; automatic replay is blocked, check the group manually", userId, ex);
            return;
        }
        if (result == GroupModerationResult.SUCCEEDED)
        {
            if (!groupId.equals(currentGroup.get())) return;
            if (pending.removeTimedBan(userId, timer))
                LOG.info("Timed ban expired for {}; unban confirmed in its original group", userId);
            else
                LOG.warn("Unban succeeded for {}, but timer cleanup could not be saved; its durable marker blocks replay and requires operator review", userId);
        }
        else if (result == GroupModerationResult.REJECTED)
        {
            if (!groupId.equals(currentGroup.get())) return;
            if (!pending.retryRejectedTimedUnban(userId, timer))
                LOG.warn("Unban was rejected for {}, and retry state could not be saved; operator review required", userId);
            else
                LOG.warn("Timed unban was definitively rejected for {}; the saved timer will retry", userId);
            pending.releasePending(reservation);
        }
        else
        {
            LOG.warn("Timed unban outcome is unknown for {}; automatic replay is blocked, check the group manually", userId);
        }
    }
}

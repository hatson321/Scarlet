package net.sybyline.scarlet;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.vrchatapi.model.GroupMemberStatus;
import net.sybyline.scarlet.ScarletPendingModActions.PendingReservation;
import net.sybyline.scarlet.ScarletVRChat.GroupModerationResult;

/** Outcome handling shared by desktop moderation and offline regression tests. */
final class ScarletManualModeration
{
    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/ManualModeration");

    private ScarletManualModeration()
    {
    }

    static GroupModerationResult perform(ScarletPendingModActions pending, PendingReservation reservation,
            boolean ban, Supplier<GroupModerationResult> action, Supplier<GroupMemberStatus> membership)
    {
        GroupModerationResult result = GroupModerationResult.UNKNOWN;
        try
        {
            GroupModerationResult response = action.get();
            result = response == null ? GroupModerationResult.UNKNOWN : response;
            if (result == GroupModerationResult.UNKNOWN)
            {
                GroupMemberStatus current = membership.get();
                if (current != null && (ban ? current == GroupMemberStatus.BANNED : current != GroupMemberStatus.BANNED))
                    result = GroupModerationResult.SUCCEEDED;
            }
            return result;
        }
        catch (RuntimeException ex)
        {
            LOG.error("Manual moderation outcome could not be confirmed; retaining a bounded pending request", ex);
            return GroupModerationResult.UNKNOWN;
        }
        finally
        {
            if (result == GroupModerationResult.REJECTED)
                pending.releasePending(reservation);
        }
    }
}

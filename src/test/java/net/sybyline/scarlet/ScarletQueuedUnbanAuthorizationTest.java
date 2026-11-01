package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletVRChatBanEntryPointsTest.*;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.sybyline.scarlet.server.discord.DPerms.PermType;

/** Actual queue worker and strict parser, with entirely synthetic Discord/VRChat responses. */
class ScarletQueuedUnbanAuthorizationTest
{
    @TempDir Path temp;
    static final String ACTOR_ROLE = "grol_10000000-0000-0000-0000-000000000011";
    static final String BOT_ROLE = "grol_10000000-0000-0000-0000-000000000012";

    Fixture fixture() throws Exception
    {
        Fixture f = new Fixture(temp);
        UnbanVrc vrc = inert(UnbanVrc.class);
        vrc.groupId = GROUP;
        vrc.currentUserId = BOT;
        vrc.actorCanBan = true;
        vrc.botCanBan = true;
        vrc.actorPresent = true;
        vrc.targetBanned = true;
        vrc.outcome = ScarletVRChat.GroupModerationResult.SUCCEEDED;
        put(f.scarlet, "vrc", vrc);
        f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-unban-multi", true);
        return f;
    }

    static ScarletDiscord.Action action()
    {
        return new ScarletDiscord.Action("unban", ACTOR, TARGET, null, REQUESTER, GUILD, GROUP);
    }

    @Test void syntheticWireSnapshotsPassStrictParsingAndCurrentAuthority() throws Exception
    {
        Fixture f = fixture();
        UnbanVrc vrc = (UnbanVrc)f.scarlet.vrc;
        Group group = ScarletBanSnapshot.group(vrc.readBanGroup(GROUP), GROUP);
        GroupMember actor = ScarletBanSnapshot.member(vrc.readBanMember(GROUP, ACTOR), GROUP, ACTOR);
        GroupMember bot = ScarletBanSnapshot.member(vrc.readBanMember(GROUP, BOT), GROUP, BOT);
        GroupMember target = ScarletBanSnapshot.member(vrc.readBanMember(GROUP, TARGET), GROUP, TARGET);
        assertTrue(ScarletBanAuthorization.evaluateUnban(group, ACTOR, BOT, actor, bot, false).allowed());
        assertEquals(GroupMemberStatus.BANNED, target.getMembershipStatus());
        assertEquals(4, vrc.reads);
    }

    @Test void currentOrdinaryAuthoritySubmitsAfterAllThreeDiscordChecks() throws Exception
    {
        Fixture f = fixture();
        UnbanVrc vrc = (UnbanVrc)f.scarlet.vrc;
        assertEquals(60L, f.discord.pollQueuedAction_unban(action()));
        assertEquals(4, vrc.reads);
        assertEquals(3, f.discord.retrievals);
        assertEquals(1, vrc.unbans);
        assertTrue(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
    }

    @Test void bulkSubmissionPersistsRequesterGuildAndOriginalGroup() throws Exception
    {
        Fixture f = fixture();
        f.ui.vrchatUserUnbanMulti(f.event(ModalInteractionEvent.class, TARGET, "vrchat-user-unban-multi"));
        assertEquals(1, f.discord.queuedActions.size());
        ScarletDiscord.Action queued = f.discord.queuedActions.get(0);
        assertEquals(REQUESTER, queued.requesterDiscordId);
        assertEquals(GUILD, queued.discordGuildId);
        assertEquals(GROUP, queued.vrchatGroupId);
        assertEquals(ACTOR, queued.actorId);
    }

    @Test void legacyAndChangedContextRequestsNeverReachTheApi() throws Exception
    {
        Fixture f = fixture();
        UnbanVrc vrc = (UnbanVrc)f.scarlet.vrc;
        f.discord.pollQueuedAction_unban(new ScarletDiscord.Action("unban", ACTOR, TARGET, null));
        f.discord.guildSf = "another-guild";
        f.discord.pollQueuedAction_unban(action());
        f.discord.guildSf = GUILD;
        vrc.groupId = "grp_20000000-0000-0000-0000-000000000001";
        f.discord.pollQueuedAction_unban(action());
        vrc.groupId = GROUP;
        f.scarlet.data.globalMetadata_setSnowflakeId(REQUESTER, OTHER);
        f.discord.pollQueuedAction_unban(action());
        assertEquals(0, vrc.reads);
        assertEquals(0, vrc.unbans);
        assertFalse(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
    }

    @Test void revokedSlashModalMembershipAndVrchatAuthorityFailClosed() throws Exception
    {
        Fixture f = fixture();
        UnbanVrc vrc = (UnbanVrc)f.scarlet.vrc;
        f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-unban-multi", false);
        f.discord.pollQueuedAction_unban(action());
        f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-unban-multi", true);
        f.perms.set(PermType.MODAL_SUBMIT, f.member, "vrchat-user-unban-multi", false);
        f.discord.pollQueuedAction_unban(action());
        f.perms.set(PermType.MODAL_SUBMIT, f.member, "vrchat-user-unban-multi", true);
        f.discord.freshMember = null;
        f.discord.pollQueuedAction_unban(action());
        f.discord.freshMember = f.member;
        vrc.actorCanBan = false;
        f.discord.pollQueuedAction_unban(action());
        vrc.actorCanBan = true;
        vrc.botCanBan = false;
        f.discord.pollQueuedAction_unban(action());
        assertEquals(8, vrc.reads, "Only the two VRChat-authority denials reach the four snapshot reads");
        assertEquals(0, vrc.unbans);
        assertFalse(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
    }

    @Test void delegatedOverrideAllowsUnbanButCannotReplaceMembershipOrBotAuthority() throws Exception
    {
        Fixture f = fixture();
        UnbanVrc vrc = (UnbanVrc)f.scarlet.vrc;
        vrc.actorCanBan = false;
        f.perms.set(PermType.OTHER, f.member, ScarletPermission.GROUPEX_BANS_MANAGE.id, true);
        vrc.actorPresent = false;
        f.discord.pollQueuedAction_unban(action());
        assertEquals(4, vrc.reads);
        assertEquals(0, vrc.unbans);
        vrc.actorPresent = true;
        vrc.botCanBan = false;
        f.discord.pollQueuedAction_unban(action());
        assertEquals(8, vrc.reads);
        assertEquals(0, vrc.unbans);
        vrc.botCanBan = true;
        assertEquals(60L, f.discord.pollQueuedAction_unban(action()));
        assertEquals(12, vrc.reads);
        assertEquals(1, vrc.unbans);
        assertTrue(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
        assertTrue(f.discord.retrievals >= 3);
    }

    @Test void changesDuringBlockingLookupsAreRecheckedBeforeMutation() throws Exception
    {
        Fixture f = fixture();
        UnbanVrc vrc = (UnbanVrc)f.scarlet.vrc;
        vrc.afterTargetRead = () -> f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-unban-multi", false);
        f.discord.pollQueuedAction_unban(action());
        f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-unban-multi", true);
        vrc.afterTargetRead = () -> vrc.groupId = "grp_20000000-0000-0000-0000-000000000001";
        f.discord.pollQueuedAction_unban(action());
        assertEquals(8, vrc.reads, "Both mutations must occur after all four snapshot reads");
        assertEquals(0, vrc.unbans);
        assertFalse(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
    }

    @Test void permissionRevokedDuringReservationSaveReleasesOnlyUnsubmittedRequest() throws Exception
    {
        Fixture f = fixture();
        UnbanVrc vrc = (UnbanVrc)f.scarlet.vrc;
        ChangingPending pending = new ChangingPending(temp.resolve("pending-changing.json").toFile());
        pending.afterReserve = () -> f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-unban-multi", false);
        put(f.scarlet, "pendingModActions", pending);
        f.discord.pollQueuedAction_unban(action());
        assertEquals(1, pending.reservations, "The test must reach the persistence-time revocation gate");
        assertEquals(0, vrc.unbans);
        assertFalse(pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
    }

    @Test void unknownOutcomeRetainsReservationAndTimerWhileDefiniteRejectionReleases() throws Exception
    {
        Fixture f = fixture();
        UnbanVrc vrc = (UnbanVrc)f.scarlet.vrc;
        ScarletPendingModActions.TimedBan timer = f.pending.prepareTimedBan(GROUP, TARGET, System.currentTimeMillis() + 60000L, ACTOR);
        vrc.outcome = ScarletVRChat.GroupModerationResult.REJECTED;
        f.discord.pollQueuedAction_unban(action());
        assertEquals(1, vrc.unbans);
        assertFalse(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
        assertEquals(timer.recordId, f.pending.getTimedBan(TARGET).recordId);
        vrc.outcome = ScarletVRChat.GroupModerationResult.UNKNOWN;
        f.discord.pollQueuedAction_unban(action());
        assertEquals(2, vrc.unbans);
        assertTrue(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
        assertEquals(timer.recordId, f.pending.getTimedBan(TARGET).recordId);
        f.discord.pollQueuedAction_unban(action());
        assertEquals(2, vrc.unbans);
    }

    @Test void malformedSnapshotAndNoLongerBannedTargetNeverSubmit() throws Exception
    {
        Fixture f = fixture();
        UnbanVrc vrc = (UnbanVrc)f.scarlet.vrc;
        vrc.malformed = true;
        f.discord.pollQueuedAction_unban(action());
        vrc.malformed = false;
        vrc.targetBanned = false;
        f.discord.pollQueuedAction_unban(action());
        assertEquals(5, vrc.reads, "Malformed group stops immediately; unbanned target reaches all four reads");
        assertEquals(0, vrc.unbans);
    }

    static class ChangingPending extends ScarletPendingModActions
    {
        ChangingPending(File file) { super(null, file); }
        Runnable afterReserve;
        int reservations;
        @Override public synchronized PendingReservation reservePending(GroupAuditType type, String target, String actor, String group)
        {
            PendingReservation result = super.reservePending(type, target, actor, group);
            if (result != null) reservations++;
            if (afterReserve != null) afterReserve.run();
            return result;
        }
    }

    static class UnbanVrc extends ScarletVRChat
    {
        UnbanVrc() { super(null, null, null); }
        boolean actorCanBan, botCanBan, actorPresent, targetBanned, malformed;
        int reads, unbans;
        Runnable afterTargetRead;
        GroupModerationResult outcome;
        @Override JsonElement readBanGroup(String group)
        {
            reads++;
            if (malformed) return new JsonObject();
            // Explicit API-wire fixtures avoid the SDK's compatibility defaults and bare Gson's
            // reflection into java.time fields on full generated models under modular JDKs.
            JsonObject response = new JsonObject();
            response.addProperty("id", GROUP);
            response.addProperty("ownerId", OTHER);
            JsonArray roles = new JsonArray();
            roles.add(role(ACTOR_ROLE, 10, actorCanBan));
            roles.add(role(BOT_ROLE, 20, botCanBan));
            response.add("roles", roles);
            return response;
        }
        private static JsonObject role(String id, int order, boolean canBan)
        {
            JsonObject role = new JsonObject();
            role.addProperty("id", id);
            role.addProperty("groupId", GROUP);
            role.addProperty("order", order);
            role.addProperty("defaultRole", false);
            JsonArray permissions = new JsonArray();
            if (canBan) permissions.add("group-bans-manage");
            role.add("permissions", permissions);
            return role;
        }
        @Override JsonElement readBanMember(String group, String user)
        {
            reads++;
            JsonObject member = new JsonObject();
            member.addProperty("groupId", GROUP);
            member.addProperty("userId", user);
            member.add("mRoleIds", new JsonArray());
            member.addProperty("membershipStatus", ACTOR.equals(user) && !actorPresent ? "inactive"
                : TARGET.equals(user) && targetBanned ? "banned" : "member");
            JsonArray roles = new JsonArray();
            if (!TARGET.equals(user)) roles.add(ACTOR.equals(user) ? ACTOR_ROLE : BOT_ROLE);
            member.add("roleIds", roles);
            if (TARGET.equals(user) && afterTargetRead != null) afterTargetRead.run();
            return member;
        }
        @Override public boolean checkUserHasVRChatPermission(GroupPermissions permission, String user) { return actorCanBan; }
        @Override public boolean checkSelfUserHasVRChatPermission(GroupPermissions permission) { return botCanBan; }
        @Override public GroupModerationResult unbanFromGroupDetailed(String group, String user)
        {
            assertEquals(GROUP, group);
            assertEquals(this.groupId, group);
            assertEquals(TARGET, user);
            unbans++;
            return outcome;
        }
        @Override public boolean unbanFromGroup(String target) { throw new AssertionError("Unbound unban path must not be used"); }
    }
}

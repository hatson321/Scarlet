package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.GroupRole;

/** Pure policy checks: synthetic API models, without Scarlet startup or API calls. */
class ScarletBanAuthorizationTest
{
    private static final String GROUP = "grp_00000000-0000-0000-0000-000000000001";
    private static final String OTHER_GROUP = "grp_00000000-0000-0000-0000-000000000002";
    private static final String OWNER = "usr_00000000-0000-0000-0000-000000000001";
    private static final String ACTOR = "usr_00000000-0000-0000-0000-000000000002";
    private static final String TARGET = "usr_00000000-0000-0000-0000-000000000003";
    private static final String BOT = "usr_00000000-0000-0000-0000-000000000004";
    private static final String HIGH = "grol_00000000-0000-0000-0000-000000000010";
    private static final String MODERATOR = "grol_00000000-0000-0000-0000-000000000040";
    private static final String LOW = "grol_00000000-0000-0000-0000-000000000900";
    private static final String EVERYONE = "grol_00000000-0000-0000-0000-000000000999";
    private static final String UNKNOWN = "grol_00000000-0000-0000-0000-000000000777";

    @Test void lowerNumericOrderAuthorizesDespiteUnorderedNonContiguousCatalog()
    {
        Fixture f = new Fixture();
        allow(f.evaluate());
        Collections.reverse(f.group.getRoles());
        allow(f.evaluate());
    }

    @Test void equalActorAndTargetRanksAreDeniedEvenWhenRoleIdsDiffer()
    {
        Fixture f = new Fixture();
        f.targetRole.setOrder(f.actorRole.getOrder());
        deny(f.evaluate());
        f.target.setRoleIds(list(MODERATOR));
        deny(f.evaluate());
    }

    @Test void actorCannotBanHigherRankedTarget()
    {
        Fixture f = new Fixture();
        f.targetRole.setOrder(20);
        deny(f.evaluate());
    }

    @Test void highestRoleAcrossAllActorAssignmentsDeterminesRank()
    {
        Fixture f = new Fixture();
        f.actor.setRoleIds(list(LOW, MODERATOR));
        allow(f.evaluate());
        f.actor.setRoleIds(list(MODERATOR, LOW));
        allow(f.evaluate());
    }

    @Test void highestTargetRoleCannotBeHiddenByLowerRoleOrListOrder()
    {
        Fixture f = new Fixture();
        f.target.setRoleIds(list(LOW, HIGH));
        deny(f.evaluate());
        f.target.setRoleIds(list(HIGH, LOW));
        deny(f.evaluate());
    }

    @Test void highestRankAndBanPermissionMayComeFromDifferentAssignedRoles()
    {
        Fixture f = new Fixture();
        f.botRole.setPermissions(list());
        f.targetRole.setOrder(20);
        f.actor.setRoleIds(list(MODERATOR, HIGH));
        f.bot.setRoleIds(list(HIGH, MODERATOR));
        allow(f.evaluate());
    }

    @Test void botMustIndependentlyOutrankTarget()
    {
        Fixture f = new Fixture();
        f.botRole.setOrder(900);
        deny(f.evaluate());
        f.botRole.setOrder(1_200);
        deny(f.evaluate());
    }

    @Test void ownerActorHasInherentAuthorityButDoesNotOverrideBotHierarchy()
    {
        Fixture f = new Fixture();
        f.group.setOwnerId(ACTOR);
        f.actor.setRoleIds(list());
        f.actorRole.setPermissions(list());
        allow(f.evaluate());
        f.botRole.setOrder(f.targetRole.getOrder());
        deny(f.evaluate());
    }

    @Test void ownerBotHasInherentPermissionAndRankButCannotRescueLowerActor()
    {
        Fixture f = new Fixture();
        f.group.setOwnerId(BOT);
        f.bot.setRoleIds(list());
        f.botRole.setPermissions(list());
        allow(f.evaluate());
        f.actorRole.setOrder(f.targetRole.getOrder());
        deny(f.evaluate());
    }

    @Test void ownerTargetSelfTargetAndBotTargetAreAlwaysProtected()
    {
        Fixture f = new Fixture();
        f.group.setOwnerId(TARGET);
        deny(f.evaluate());
        f.group.setOwnerId(OWNER);
        deny(ScarletBanAuthorization.evaluate(f.group, ACTOR, ACTOR, BOT,
            f.actor, member(ACTOR, GroupMemberStatus.MEMBER, LOW), f.bot, true));
        deny(ScarletBanAuthorization.evaluate(f.group, ACTOR, BOT, BOT,
            f.actor, f.bot, f.bot, true));
    }

    @Test void actorPermissionOverrideChangesPermissionOnly()
    {
        Fixture f = new Fixture();
        f.actorRole.setPermissions(list());
        deny(f.evaluate());
        f.override = true;
        allow(f.evaluate());
        f.targetRole.setOrder(f.actorRole.getOrder());
        deny(f.evaluate());
        f.targetRole.setOrder(20);
        deny(f.evaluate());
    }

    @Test void overrideNeverReplacesBotBanPermission()
    {
        Fixture f = new Fixture();
        f.override = true;
        f.botRole.setPermissions(list(GroupPermissions.group_members_manage));
        deny(f.evaluate());
    }

    @Test void unrelatedPermissionsDoNotAuthorizeBans()
    {
        Fixture f = new Fixture();
        f.actorRole.setPermissions(list(GroupPermissions.group_members_remove,
            GroupPermissions.group_roles_manage, GroupPermissions.group_instance_moderate));
        deny(f.evaluate());
        f.actorRole.setPermissions(list(GroupPermissions.group_all));
        f.botRole.setPermissions(list(GroupPermissions.group_all));
        allow(f.evaluate());
    }

    @Test void everyonePermissionAppliesWithoutExplicitAssignmentButGrantsNoRank()
    {
        Fixture f = new Fixture();
        f.actorRole.setPermissions(list());
        f.botRole.setPermissions(list());
        f.group.addRolesItem(role(EVERYONE, 0, GroupPermissions.group_bans_manage).defaultRole(true));
        allow(f.evaluate());
        f.actor.setRoleIds(list());
        deny(f.evaluate());
        f.actor.setRoleIds(list(EVERYONE));
        deny(f.evaluate());
    }

    @Test void noRoleMembersAreBelowEveryAssignedRoleIncludingMaximumIntegerOrder()
    {
        Fixture f = new Fixture();
        f.actorRole.setOrder(Integer.MAX_VALUE);
        f.target.setRoleIds(list());
        allow(f.evaluate());
    }

    @Test void everyonePermissionDoesNotBreakEqualNoRoleMemberTie()
    {
        Fixture f = new Fixture();
        f.group.addRolesItem(role(EVERYONE, 0, GroupPermissions.group_bans_manage).defaultRole(true));
        f.actor.setRoleIds(list());
        f.target.setRoleIds(list());
        deny(f.evaluate());
    }

    @Test void verifiedOutsiderIsBelowNoRoleMemberWithBanPermission()
    {
        Fixture f = new Fixture();
        f.group.addRolesItem(role(EVERYONE, 0, GroupPermissions.group_bans_manage).defaultRole(true));
        f.actor.setRoleIds(list());
        f.bot.setRoleIds(list());
        f.target.setRoleIds(list());
        f.target.setMembershipStatus(GroupMemberStatus.INACTIVE);
        allow(f.evaluate());
    }

    @Test void explicitSuccessfulOutsiderStatusesAreAcceptedOnlyWithNoAssignedRoles()
    {
        for (GroupMemberStatus status : outsiderStatuses())
        {
            Fixture f = new Fixture();
            f.target = member(TARGET, status);
            allow(f.evaluate());
            f.target.setRoleIds(list(LOW));
            deny(f.evaluate());
            f.target.setRoleIds(list());
            f.target.setmRoleIds(list(MODERATOR));
            deny(f.evaluate());
        }
    }

    @Test void absentOrUnknownTargetIsNotAutomaticallyAnOutsider()
    {
        Fixture f = new Fixture();
        f.target = null;
        deny(f.evaluate());
        f.target = member(TARGET, null);
        deny(f.evaluate());
        f.target = member(TARGET, GroupMemberStatus.BANNED);
        deny(f.evaluate());
    }

    @Test void explicitlyVerifiedAbsentTargetCanBeAuthorizedButUnknownAbsenceCannot()
    {
        Fixture f = new Fixture();
        deny(ScarletBanAuthorization.evaluate(f.group, ACTOR, TARGET, BOT,
            f.actor, null, f.bot, false, false));
        allow(ScarletBanAuthorization.evaluate(f.group, ACTOR, TARGET, BOT,
            f.actor, null, f.bot, false, true));
        f.actorRole.setPermissions(list());
        deny(ScarletBanAuthorization.evaluate(f.group, ACTOR, TARGET, BOT,
            f.actor, null, f.bot, false, true));
    }

    @Test void absenceProofCannotBypassProtectedTargetIds()
    {
        Fixture f = new Fixture();
        for (String protectedTarget : list(OWNER, ACTOR, BOT))
            deny(ScarletBanAuthorization.evaluate(f.group, ACTOR, protectedTarget, BOT,
                f.actor, null, f.bot, true, true));
    }

    @Test void contradictoryAbsentFlagAndReturnedTargetAreRejected()
    {
        Fixture f = new Fixture();
        deny(ScarletBanAuthorization.evaluate(f.group, ACTOR, TARGET, BOT,
            f.actor, f.target, f.bot, false, true));
    }

    @Test void actorAndBotRequireConfirmedMembershipEvenWithOverride()
    {
        for (GroupMemberStatus status : GroupMemberStatus.values())
        {
            if (status == GroupMemberStatus.MEMBER) continue;
            Fixture actor = new Fixture();
            actor.override = true;
            actor.actor.setMembershipStatus(status);
            deny(actor.evaluate());
            Fixture bot = new Fixture();
            bot.override = true;
            bot.bot.setMembershipStatus(status);
            deny(bot.evaluate());
        }
    }

    @Test void missingActorOrBotModelsAndStatusesFailClosed()
    {
        Fixture f = new Fixture();
        deny(ScarletBanAuthorization.evaluate(f.group, ACTOR, TARGET, BOT, null, f.target, f.bot, true));
        deny(ScarletBanAuthorization.evaluate(f.group, ACTOR, TARGET, BOT, f.actor, f.target, null, true));
        f.actor.setMembershipStatus(null);
        deny(f.evaluate());
        f.actor.setMembershipStatus(GroupMemberStatus.MEMBER);
        f.bot.setMembershipStatus(null);
        deny(f.evaluate());
    }

    @Test void nullPrimaryRoleListsAreUnverifiedForEveryMemberPosition()
    {
        for (int position = 0; position < 3; position++)
        {
            Fixture f = new Fixture();
            f.override = true;
            f.members()[position].setRoleIds(null);
            deny(f.evaluate());
        }
    }

    @Test void unassignedManagementRoleIdsCannotSupplyActorPermissionOrRank()
    {
        Fixture f = new Fixture();
        f.actor.setRoleIds(list());
        f.actor.setmRoleIds(list(MODERATOR));
        deny(f.evaluate());
        f.override = true;
        deny(f.evaluate());
    }

    @Test void managementRoleSubsetIsAcceptedWithoutGrantingAdditionalAuthority()
    {
        Fixture f = new Fixture();
        f.actor.setmRoleIds(list(MODERATOR));
        f.bot.setmRoleIds(list(HIGH));
        f.target.setmRoleIds(list(LOW));
        allow(f.evaluate());
        f.targetRole.setOrder(20);
        deny(f.evaluate());
    }

    @Test void extraKnownManagementRoleIdsAreUnverifiedForEveryMemberPosition()
    {
        // These extra lower-ranked catalog roles would not block the ordinary valid ban
        // if mRoleIds were incorrectly unioned into assigned authority.
        for (int position = 0; position < 3; position++)
        {
            Fixture f = new Fixture();
            String extra = position == 1 ? UNKNOWN : LOW;
            if (position == 1) f.group.addRolesItem(role(UNKNOWN, 1_200));
            f.members()[position].setmRoleIds(list(extra));
            deny(f.evaluate());
        }
        Fixture bot = new Fixture();
        bot.bot.setRoleIds(list());
        bot.bot.setmRoleIds(list(HIGH));
        deny(bot.evaluate());
    }

    @Test void unknownAssignmentsFailClosedAcrossBothRoleListsAndAllMembers()
    {
        for (int position = 0; position < 3; position++)
        {
            Fixture direct = new Fixture();
            direct.members()[position].getRoleIds().add(UNKNOWN);
            deny(direct.evaluate());
            Fixture management = new Fixture();
            management.members()[position].setmRoleIds(list(UNKNOWN));
            deny(management.evaluate());
        }
    }

    @Test void blankOrNullAssignedIdsAreMalformedRatherThanIgnored()
    {
        for (String invalid : new String[] { null, "", "  " })
        {
            Fixture direct = new Fixture();
            direct.actor.getRoleIds().add(invalid);
            deny(direct.evaluate());
            Fixture management = new Fixture();
            management.target.setmRoleIds(list(invalid));
            deny(management.evaluate());
        }
    }

    @Test void mismatchedMembershipUserOrGroupIdsFailClosedForAllPositions()
    {
        for (int position = 0; position < 3; position++)
        {
            Fixture wrongUser = new Fixture();
            wrongUser.members()[position].setUserId(OWNER);
            deny(wrongUser.evaluate());
            Fixture wrongGroup = new Fixture();
            wrongGroup.members()[position].setGroupId(OTHER_GROUP);
            deny(wrongGroup.evaluate());
        }
    }

    @Test void blankOrNullMembershipIdentityFieldsFailClosed()
    {
        for (String invalid : new String[] { null, "", "  " })
            for (int position = 0; position < 3; position++)
            {
                Fixture user = new Fixture();
                user.members()[position].setUserId(invalid);
                deny(user.evaluate());
                Fixture group = new Fixture();
                group.members()[position].setGroupId(invalid);
                deny(group.evaluate());
            }
    }

    @Test void missingGroupRolesOwnerOrRequestedIdsFailClosed()
    {
        Fixture f = new Fixture();
        deny(ScarletBanAuthorization.evaluate(null, ACTOR, TARGET, BOT, f.actor, f.target, f.bot, false));
        f.group.setRoles(null);
        deny(f.evaluate());
        for (String invalid : new String[] { null, "", "  " })
        {
            Fixture group = new Fixture();
            group.group.setId(invalid);
            deny(group.evaluate());
            Fixture owner = new Fixture();
            owner.group.setOwnerId(invalid);
            deny(owner.evaluate());
            Fixture ids = new Fixture();
            deny(ScarletBanAuthorization.evaluate(ids.group, invalid, TARGET, BOT, ids.actor, ids.target, ids.bot, true));
            deny(ScarletBanAuthorization.evaluate(ids.group, ACTOR, invalid, BOT, ids.actor, ids.target, ids.bot, true));
            deny(ScarletBanAuthorization.evaluate(ids.group, ACTOR, TARGET, invalid, ids.actor, ids.target, ids.bot, true));
        }
    }

    @Test void malformedRoleCatalogIsNotSilentlyReduced()
    {
        Fixture duplicate = new Fixture();
        duplicate.group.addRolesItem(role(MODERATOR, 1, GroupPermissions.group_bans_manage));
        deny(duplicate.evaluate());
        Fixture missingOrder = new Fixture();
        missingOrder.targetRole.setOrder(null);
        deny(missingOrder.evaluate());
        Fixture wrongGroup = new Fixture();
        wrongGroup.actorRole.setGroupId(OTHER_GROUP);
        deny(wrongGroup.evaluate());
        Fixture nullEntry = new Fixture();
        nullEntry.group.getRoles().add(null);
        deny(nullEntry.evaluate());
        for (String invalid : new String[] { null, "", "  " })
        {
            Fixture missingId = new Fixture();
            missingId.actorRole.setId(invalid);
            deny(missingId.evaluate());
        }
    }

    @Test void conflictingEveryoneRolesAndNegativeAssignedOrderFailClosed()
    {
        Fixture defaults = new Fixture();
        defaults.actorRole.setDefaultRole(true);
        defaults.botRole.setDefaultRole(true);
        deny(defaults.evaluate());
        Fixture order = new Fixture();
        order.targetRole.setOrder(-1);
        deny(order.evaluate());
    }

    @Test void missingOrNullPermissionEntriesCannotGrantAuthority()
    {
        Fixture actor = new Fixture();
        actor.actorRole.setPermissions(null);
        deny(actor.evaluate());
        Fixture bot = new Fixture();
        bot.botRole.getPermissions().add(null);
        deny(bot.evaluate());
    }

    @Test void previouslyAllowedDecisionDoesNotAuthorizeChangedMembershipSnapshot()
    {
        Fixture f = new Fixture();
        allow(f.evaluate());
        f.actor.setRoleIds(list(LOW));
        deny(f.evaluate());
        f.actor.setRoleIds(list(MODERATOR));
        allow(f.evaluate());
        f.bot.setMembershipStatus(GroupMemberStatus.INACTIVE);
        deny(f.evaluate());
    }

    @Test void changedPermissionHierarchyOrOwnerIsReevaluated()
    {
        Fixture f = new Fixture();
        allow(f.evaluate());
        f.actorRole.getPermissions().clear();
        deny(f.evaluate());
        f.actorRole.setPermissions(list(GroupPermissions.group_bans_manage));
        allow(f.evaluate());
        f.targetRole.setOrder(1);
        deny(f.evaluate());
        f.targetRole.setOrder(900);
        allow(f.evaluate());
        f.group.setOwnerId(TARGET);
        deny(f.evaluate());
    }

    @Test void unverifiedAndExplicitDenialDecisionsNeverAllow()
    {
        deny(ScarletBanAuthorization.Decision.unverified());
        deny(ScarletBanAuthorization.Decision.deny("Synthetic policy denial"));
    }

    private static GroupMemberStatus[] outsiderStatuses()
    {
        return new GroupMemberStatus[] { GroupMemberStatus.INACTIVE, GroupMemberStatus.INVITED,
            GroupMemberStatus.REQUESTED, GroupMemberStatus.USERBLOCKED };
    }

    private static void allow(ScarletBanAuthorization.Decision decision)
    {
        assertNotNull(decision);
        assertTrue(decision.allowed(), decision.message());
    }

    private static void deny(ScarletBanAuthorization.Decision decision)
    {
        assertNotNull(decision);
        assertFalse(decision.allowed());
        assertNotNull(decision.message());
        assertFalse(decision.message().trim().isEmpty(), "A denied action needs an operator-readable reason");
    }

    @SafeVarargs private static <T> ArrayList<T> list(T... values)
    {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static GroupRole role(String id, int order, GroupPermissions... permissions)
    {
        return new GroupRole().id(id).groupId(GROUP).order(order).permissions(list(permissions)).defaultRole(false);
    }

    private static GroupMember member(String user, GroupMemberStatus status, String... roles)
    {
        return new GroupMember().groupId(GROUP).userId(user).membershipStatus(status)
            .roleIds(list(roles)).mRoleIds(list());
    }

    private static final class Fixture
    {
        final GroupRole botRole = role(HIGH, 10, GroupPermissions.group_bans_manage);
        final GroupRole actorRole = role(MODERATOR, 40, GroupPermissions.group_bans_manage);
        final GroupRole targetRole = role(LOW, 900);
        final Group group = new Group().id(GROUP).ownerId(OWNER).roles(list(targetRole, actorRole, botRole));
        GroupMember actor = member(ACTOR, GroupMemberStatus.MEMBER, MODERATOR);
        GroupMember target = member(TARGET, GroupMemberStatus.MEMBER, LOW);
        GroupMember bot = member(BOT, GroupMemberStatus.MEMBER, HIGH);
        boolean override;

        ScarletBanAuthorization.Decision evaluate()
        {
            return ScarletBanAuthorization.evaluate(group, ACTOR, TARGET, BOT, actor, target, bot, override);
        }

        GroupMember[] members()
        {
            return new GroupMember[] { actor, target, bot };
        }
    }
}

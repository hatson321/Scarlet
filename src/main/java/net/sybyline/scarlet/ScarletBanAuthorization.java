package net.sybyline.scarlet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.GroupRole;

/** Target-specific authorization. Discord permission overrides never grant a higher VRChat rank. */
final class ScarletBanAuthorization
{
    private static final long ROLELESS = (long) Integer.MAX_VALUE + 1L;
    private static final long OUTSIDER = ROLELESS + 1L;

    private ScarletBanAuthorization() {}

    static final class Decision
    {
        private final boolean allowed;
        private final String message;

        private Decision(boolean allowed, String message)
        {
            this.allowed = allowed;
            this.message = message;
        }

        boolean allowed() { return this.allowed; }
        String message() { return this.message; }

        static Decision deny(String message) { return new Decision(false, message); }
        static Decision unverified()
        {
            return deny("Ban blocked: current VRChat group membership, permissions or role hierarchy could not be verified. No ban was sent.");
        }
    }

    static Decision evaluate(Group group, String actorId, String targetId, String botId,
            GroupMember actor, GroupMember target, GroupMember bot, boolean allowPermissionOverride)
    {
        return evaluate(group, actorId, targetId, botId, actor, target, bot, allowPermissionOverride, false);
    }

    static Decision evaluate(Group group, String actorId, String targetId, String botId,
            GroupMember actor, GroupMember target, GroupMember bot, boolean allowPermissionOverride,
            boolean targetAbsent)
    {
        if (group == null || blank(group.getId()) || blank(group.getOwnerId())
                || blank(actorId) || blank(targetId) || blank(botId))
            return Decision.unverified();
        if (targetId.equals(group.getOwnerId()))
            return Decision.deny("Ban blocked: the VRChat group owner is protected.");
        if (targetId.equals(actorId) || targetId.equals(botId))
            return Decision.deny("Ban blocked: you cannot target yourself or Scarlet's VRChat account.");
        try
        {
            Map<String, GroupRole> roles = catalog(group);
            Rank actorRank = rank(group, roles, actor, actorId, true);
            Rank botRank = rank(group, roles, bot, botId, true);
            Rank targetRank;
            if (targetAbsent)
            {
                if (target != null) return Decision.unverified();
                targetRank = new Rank(OUTSIDER, false);
            }
            else
            {
                if (target == null || target.getMembershipStatus() == GroupMemberStatus.BANNED)
                    return Decision.deny("Ban blocked: the target is already banned or its current membership could not be verified.");
                targetRank = rank(group, roles, target, targetId, false);
            }
            if (!actorRank.canBan && !allowPermissionOverride)
                return Decision.deny("Ban blocked: you no longer have VRChat group ban-management permission.");
            if (!botRank.canBan)
                return Decision.deny("Ban blocked: Scarlet's VRChat account cannot manage group bans.");
            if (actorRank.order >= targetRank.order)
                return Decision.deny("Ban blocked: the target has an equal or higher VRChat group role than you.");
            if (botRank.order >= targetRank.order)
                return Decision.deny("Ban blocked: the target has an equal or higher VRChat group role than Scarlet's account.");
            return new Decision(true, "VRChat group ban authorized.");
        }
        catch (IllegalArgumentException ex)
        {
            return Decision.unverified();
        }
    }

    /** Lifting a ban requires current membership and ban-management authority, not target rank. */
    static Decision evaluateUnban(Group group, String actorId, String botId,
        GroupMember actor, GroupMember bot, boolean allowPermissionOverride)
    {
        if (group == null || blank(group.getId()) || blank(group.getOwnerId()) || blank(actorId) || blank(botId))
            return Decision.deny("Unban blocked: current VRChat group authority could not be verified.");
        try
        {
            Map<String, GroupRole> roles = catalog(group);
            Rank actorRank = rank(group, roles, actor, actorId, true);
            Rank botRank = rank(group, roles, bot, botId, true);
            if (!actorRank.canBan && !allowPermissionOverride)
                return Decision.deny("Unban blocked: you no longer have VRChat group ban-management permission.");
            if (!botRank.canBan)
                return Decision.deny("Unban blocked: Scarlet's VRChat account cannot manage group bans.");
            return new Decision(true, "VRChat group unban authorized.");
        }
        catch (IllegalArgumentException ex)
        {
            return Decision.deny("Unban blocked: current VRChat group membership or permissions could not be verified.");
        }
    }

    private static Map<String, GroupRole> catalog(Group group)
    {
        if (group.getRoles() == null) throw new IllegalArgumentException();
        Map<String, GroupRole> roles = new HashMap<>();
        int defaults = 0;
        for (GroupRole role : group.getRoles())
        {
            if (role == null || blank(role.getId()) || roles.put(role.getId(), role) != null
                    || (role.getGroupId() != null && !group.getId().equals(role.getGroupId())))
                throw new IllegalArgumentException();
            if (Boolean.TRUE.equals(role.getDefaultRole()))
            {
                if (++defaults > 1) throw new IllegalArgumentException();
            }
            else if (role.getOrder() == null || role.getOrder().intValue() < 0)
                throw new IllegalArgumentException();
        }
        return roles;
    }

    private static Rank rank(Group group, Map<String, GroupRole> roles, GroupMember member,
            String expectedUser, boolean mustBeMember)
    {
        if (member == null || !expectedUser.equals(member.getUserId())
                || !group.getId().equals(member.getGroupId()) || member.getMembershipStatus() == null
                || member.getRoleIds() == null)
            throw new IllegalArgumentException();
        Set<String> assigned = new HashSet<>(member.getRoleIds());
        // The API schema does not define mRoleIds as assigned authority. Never grant extra
        // rank or permissions from it; unexplained additional roles require manual review.
        if (member.getmRoleIds() != null && !assigned.containsAll(member.getmRoleIds()))
            throw new IllegalArgumentException();
        if (member.getMembershipStatus() != GroupMemberStatus.MEMBER)
        {
            if (mustBeMember || !assigned.isEmpty() || member.getMembershipStatus() == GroupMemberStatus.BANNED)
                throw new IllegalArgumentException();
            return new Rank(OUTSIDER, false);
        }
        boolean owner = expectedUser.equals(group.getOwnerId());
        boolean canBan = owner;
        long order = ROLELESS;
        for (String id : assigned)
        {
            GroupRole role = roles.get(id);
            if (blank(id) || role == null) throw new IllegalArgumentException();
            // Everyone's permissions apply below, but an implicit/default role must never grant rank.
            if (!Boolean.TRUE.equals(role.getDefaultRole())) order = Math.min(order, role.getOrder().longValue());
            canBan |= canBan(role.getPermissions());
        }
        for (GroupRole role : roles.values())
            if (Boolean.TRUE.equals(role.getDefaultRole())) canBan |= canBan(role.getPermissions());
        return new Rank(owner ? -1L : order, canBan);
    }

    private static boolean canBan(List<GroupPermissions> permissions)
    {
        if (permissions == null || permissions.contains(null)) throw new IllegalArgumentException();
        return permissions.contains(GroupPermissions.group_all) || permissions.contains(GroupPermissions.group_bans_manage);
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static final class Rank
    {
        final long order;
        final boolean canBan;
        Rank(long order, boolean canBan) { this.order = order; this.canBan = canBan; }
    }
}

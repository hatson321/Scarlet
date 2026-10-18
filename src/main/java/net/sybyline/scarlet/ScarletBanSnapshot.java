package net.sybyline.scarlet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.GroupRole;

/** Strict authorization-only snapshots; never use the SDK's compatibility/defaulting parser. */
final class ScarletBanSnapshot
{
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern GROUP_ID = Pattern.compile("grp_" + UUID);
    private static final Pattern USER_ID = Pattern.compile("(?:usr_" + UUID + "|[A-Za-z0-9]{10})");
    private static final Pattern ROLE_ID = Pattern.compile("grol_" + UUID);

    private ScarletBanSnapshot() { }

    static Group group(JsonElement json, String expectedGroupId)
    {
        requireIdentifier(expectedGroupId, GROUP_ID, "expected group ID");
        JsonObject object = object(json, "group");
        String id = identifier(object.get("id"), GROUP_ID, "group.id");
        if (!expectedGroupId.equals(id)) throw invalid("group.id does not match the requested group");
        String ownerId = identifier(object.get("ownerId"), USER_ID, "group.ownerId");
        JsonArray rolesJson = array(object.get("roles"), "group.roles");
        if (rolesJson.size() == 0) throw invalid("group.roles is empty");
        List<GroupRole> roles = new ArrayList<>();
        Set<String> roleIds = new HashSet<>();
        boolean defaultSeen = false;
        for (int i = 0; i < rolesJson.size(); i++)
        {
            String location = "group.roles[" + i + "]";
            JsonObject roleJson = object(rolesJson.get(i), location);
            String roleId = identifier(roleJson.get("id"), ROLE_ID, location + ".id");
            if (!roleIds.add(roleId)) throw invalid(location + ".id is duplicated");
            if (roleJson.has("groupId") && !id.equals(identifier(roleJson.get("groupId"), GROUP_ID, location + ".groupId")))
                throw invalid(location + ".groupId does not match the requested group");
            int order = nonnegativeInteger(roleJson.get("order"), location + ".order");
            // The API schema explicitly defaults omitted defaultRole to false. Do not coerce null or strings.
            boolean defaultRole = roleJson.has("defaultRole") && bool(roleJson.get("defaultRole"), location + ".defaultRole");
            if (defaultRole && defaultSeen) throw invalid("group.roles contains multiple default roles");
            defaultSeen |= defaultRole;
            JsonArray permissionJson = array(roleJson.get("permissions"), location + ".permissions");
            List<GroupPermissions> permissions = new ArrayList<>();
            for (int p = 0; p < permissionJson.size(); p++)
            {
                String permissionLocation = location + ".permissions[" + p + "]";
                String value = string(permissionJson.get(p), permissionLocation);
                try { permissions.add(GroupPermissions.fromValue(value)); }
                catch (IllegalArgumentException ex) { throw invalid(permissionLocation + " is unrecognized"); }
            }
            GroupRole role = new GroupRole();
            role.setId(roleId);
            role.setGroupId(id);
            role.setOrder(order);
            role.setDefaultRole(defaultRole);
            role.setPermissions(permissions);
            roles.add(role);
        }
        Group group = new Group();
        group.setId(id);
        group.setOwnerId(ownerId);
        group.setRoles(roles);
        return group;
    }

    /** Java null means no usable response; only an explicit successful JSON null means no membership. */
    static GroupMember member(JsonElement json, String expectedGroupId, String expectedUserId)
    {
        requireIdentifier(expectedGroupId, GROUP_ID, "expected group ID");
        requireIdentifier(expectedUserId, USER_ID, "expected user ID");
        if (json == null) throw invalid("membership response is absent");
        if (json.isJsonNull()) return null;
        JsonObject object = object(json, "member");
        String groupId = identifier(object.get("groupId"), GROUP_ID, "member.groupId");
        String userId = identifier(object.get("userId"), USER_ID, "member.userId");
        if (!expectedGroupId.equals(groupId)) throw invalid("member.groupId does not match the requested group");
        if (!expectedUserId.equals(userId)) throw invalid("member.userId does not match the requested user");
        String statusValue = string(object.get("membershipStatus"), "member.membershipStatus");
        GroupMemberStatus status;
        // Enumerate recognized states explicitly: compatibility code elsewhere may add SDK enum constants.
        switch (statusValue)
        {
        case "member": status = GroupMemberStatus.MEMBER; break;
        case "inactive": status = GroupMemberStatus.INACTIVE; break;
        case "banned": status = GroupMemberStatus.BANNED; break;
        case "invited": status = GroupMemberStatus.INVITED; break;
        case "requested": status = GroupMemberStatus.REQUESTED; break;
        case "userblocked": status = GroupMemberStatus.USERBLOCKED; break;
        default: throw invalid("member.membershipStatus is unrecognized");
        }
        List<String> roleIds = roleIds(object.get("roleIds"), "member.roleIds");
        List<String> managementRoleIds = object.has("mRoleIds")
            ? roleIds(object.get("mRoleIds"), "member.mRoleIds") : new ArrayList<>();
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setMembershipStatus(status);
        member.setRoleIds(roleIds);
        member.setmRoleIds(managementRoleIds);
        return member;
    }

    private static List<String> roleIds(JsonElement json, String location)
    {
        JsonArray array = array(json, location);
        List<String> roles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.size(); i++)
        {
            String id = identifier(array.get(i), ROLE_ID, location + "[" + i + "]");
            if (!seen.add(id)) throw invalid(location + " contains a duplicate role ID");
            roles.add(id);
        }
        return roles;
    }

    private static JsonObject object(JsonElement json, String location)
    {
        if (json == null || !json.isJsonObject()) throw invalid(location + " must be an object");
        return json.getAsJsonObject();
    }

    private static JsonArray array(JsonElement json, String location)
    {
        if (json == null || !json.isJsonArray()) throw invalid(location + " must be an array");
        return json.getAsJsonArray();
    }

    private static String string(JsonElement json, String location)
    {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString())
            throw invalid(location + " must be a string");
        String value = json.getAsString();
        if (value.isEmpty() || !value.equals(value.trim())) throw invalid(location + " must be nonempty and unpadded");
        return value;
    }

    private static String identifier(JsonElement json, Pattern pattern, String location)
    {
        String value = string(json, location);
        requireIdentifier(value, pattern, location);
        return value;
    }

    private static void requireIdentifier(String value, Pattern pattern, String location)
    {
        if (value == null || !pattern.matcher(value).matches()) throw invalid(location + " has an invalid identifier");
    }

    private static boolean bool(JsonElement json, String location)
    {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isBoolean())
            throw invalid(location + " must be a boolean");
        return json.getAsBoolean();
    }

    private static int nonnegativeInteger(JsonElement json, String location)
    {
        if (json == null || !json.isJsonPrimitive()) throw invalid(location + " must be an integer");
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (!primitive.isNumber()) throw invalid(location + " must be an integer");
        try
        {
            int value = new BigDecimal(primitive.getAsString()).intValueExact();
            if (value < 0) throw invalid(location + " must not be negative");
            return value;
        }
        catch (NumberFormatException | ArithmeticException ex) { throw invalid(location + " must be a finite 32-bit integer"); }
    }

    private static IllegalArgumentException invalid(String detail)
    {
        return new IllegalArgumentException("Invalid VRChat ban authorization snapshot: " + detail);
    }
}

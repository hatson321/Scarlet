package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;

class ScarletBanSnapshotTest
{
    private static final String GROUP = "grp_00000000-0000-0000-0000-000000000001";
    private static final String OTHER_GROUP = "grp_00000000-0000-0000-0000-000000000002";
    private static final String USER = "usr_00000000-0000-0000-0000-000000000001";
    private static final String OWNER = "usr_00000000-0000-0000-0000-000000000002";
    private static final String ROLE = "grol_00000000-0000-0000-0000-000000000001";
    private static final String OTHER_ROLE = "grol_00000000-0000-0000-0000-000000000002";

    @Test void explicitSnapshotsPreserveOnlyAuthorityRelevantValues()
    {
        JsonObject groupJson = group();
        groupJson.addProperty("unrelatedFutureField", "ignored");
        Group group = ScarletBanSnapshot.group(groupJson, GROUP);
        assertEquals(GROUP, group.getId());
        assertEquals(OWNER, group.getOwnerId());
        assertEquals(2, group.getRoles().get(0).getOrder());
        assertFalse(group.getRoles().get(0).getDefaultRole());
        assertEquals(GroupPermissions.group_bans_manage, group.getRoles().get(0).getPermissions().get(0));
        GroupMember member = ScarletBanSnapshot.member(member(), GROUP, USER);
        assertEquals(USER, member.getUserId());
        assertEquals(GROUP, member.getGroupId());
        assertEquals(GroupMemberStatus.MEMBER, member.getMembershipStatus());
        assertEquals(java.util.Collections.singletonList(ROLE), member.getRoleIds());
        assertTrue(member.getmRoleIds().isEmpty());
    }

    @Test void onlyExplicitJsonNullCanMeanNoMembership()
    {
        assertNull(ScarletBanSnapshot.member(JsonNull.INSTANCE, GROUP, USER));
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(null, GROUP, USER));
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(new JsonObject(), GROUP, USER));
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(JsonNull.INSTANCE, GROUP));
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(null, GROUP));
    }

    @Test void modelDefaultsCannotReplaceAbsentMembershipAuthorityFields()
    {
        for (String field : new String[] { "groupId", "userId", "membershipStatus", "roleIds" })
        {
            JsonObject json = member();
            json.remove(field);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(json, GROUP, USER), field);
            json.add(field, JsonNull.INSTANCE);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(json, GROUP, USER), field + " null");
        }
    }

    @Test void groupOwnerAndExplicitRolesAreRequired()
    {
        for (String field : new String[] { "id", "ownerId", "roles" })
        {
            JsonObject json = group();
            json.remove(field);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP), field);
            json.add(field, JsonNull.INSTANCE);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP), field + " null");
        }
        JsonObject emptyRoles = group();
        emptyRoles.add("roles", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(emptyRoles, GROUP));
    }

    @Test void snapshotsMustMatchRequestedIdentities()
    {
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(group(), OTHER_GROUP));
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(member(), OTHER_GROUP, USER));
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(member(), GROUP, OWNER));
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(JsonNull.INSTANCE, GROUP, "not-a-user-id"));
        JsonObject json = group();
        role(json).addProperty("groupId", OTHER_GROUP);
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP));
    }

    @Test void numericBooleanAndPaddedIdentityValuesAreNotCoerced()
    {
        for (JsonElement bad : new JsonElement[] { new JsonPrimitive(123), new JsonPrimitive(true),
            new JsonPrimitive(" " + USER), new JsonPrimitive(USER + " "), new JsonPrimitive(""), new JsonArray() })
        {
            JsonObject json = member();
            json.add("userId", bad);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(json, GROUP, USER));
            JsonObject group = group();
            group.add("ownerId", bad);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(group, GROUP));
        }
    }

    @Test void roleOrderMustBeANonnegativeFiniteIntegerWithoutStringCoercion()
    {
        for (JsonElement bad : new JsonElement[] { new JsonPrimitive("2"), new JsonPrimitive(true),
            new JsonPrimitive(-1), new JsonPrimitive(1.5), new JsonPrimitive(2147483648L),
            new JsonPrimitive(Double.NaN), JsonNull.INSTANCE, new JsonObject() })
        {
            JsonObject json = group();
            role(json).add("order", bad);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP));
        }
        JsonObject json = group();
        role(json).remove("order");
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP));
    }

    @Test void defaultRoleUsesOnlyTheDocumentedFalseDefault()
    {
        assertFalse(ScarletBanSnapshot.group(group(), GROUP).getRoles().get(0).getDefaultRole());
        JsonObject json = group();
        role(json).addProperty("defaultRole", true);
        assertTrue(ScarletBanSnapshot.group(json, GROUP).getRoles().get(0).getDefaultRole());
        for (JsonElement bad : new JsonElement[] { new JsonPrimitive("false"), new JsonPrimitive(0), JsonNull.INSTANCE })
        {
            role(json).add("defaultRole", bad);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP));
        }
    }

    @Test void permissionsMustBeExplicitRecognizedStringArray()
    {
        JsonObject json = group();
        role(json).remove("permissions");
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP));
        for (JsonElement bad : new JsonElement[] { JsonNull.INSTANCE, new JsonPrimitive("group-bans-manage"), new JsonObject() })
        {
            role(json).add("permissions", bad);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP));
        }
        for (JsonElement bad : new JsonElement[] { new JsonPrimitive("unrecognized-permission"), new JsonPrimitive(true), JsonNull.INSTANCE })
        {
            JsonArray permissions = new JsonArray();
            permissions.add(bad);
            role(json).add("permissions", permissions);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP));
        }
        role(json).add("permissions", new JsonArray());
        assertTrue(ScarletBanSnapshot.group(json, GROUP).getRoles().get(0).getPermissions().isEmpty());
    }

    @Test void duplicateRoleDefinitionsAndMultipleDefaultsAreAmbiguous()
    {
        JsonObject json = group();
        json.getAsJsonArray("roles").add(role(json).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP));
        json.getAsJsonArray("roles").get(1).getAsJsonObject().addProperty("id", OTHER_ROLE);
        role(json).addProperty("defaultRole", true);
        json.getAsJsonArray("roles").get(1).getAsJsonObject().addProperty("defaultRole", true);
        assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(json, GROUP));
    }

    @Test void unknownOrMistypedMembershipStateDoesNotBecomeInactive()
    {
        for (JsonElement bad : new JsonElement[] { new JsonPrimitive("unknown-status"), new JsonPrimitive("MEMBER"),
            new JsonPrimitive(" member "), new JsonPrimitive(0), new JsonPrimitive(false) })
        {
            JsonObject json = member();
            json.add("membershipStatus", bad);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(json, GROUP, USER));
        }
        for (GroupMemberStatus status : new GroupMemberStatus[] { GroupMemberStatus.MEMBER, GroupMemberStatus.INACTIVE,
            GroupMemberStatus.BANNED, GroupMemberStatus.INVITED, GroupMemberStatus.REQUESTED, GroupMemberStatus.USERBLOCKED })
        {
            JsonObject json = member();
            json.addProperty("membershipStatus", status.getValue());
            assertEquals(status, ScarletBanSnapshot.member(json, GROUP, USER).getMembershipStatus());
        }
    }

    @Test void roleArraysCannotBeMissingMalformedOrDuplicated()
    {
        for (String field : new String[] { "roleIds", "mRoleIds" })
        {
            for (JsonElement bad : new JsonElement[] { JsonNull.INSTANCE, new JsonPrimitive(ROLE), new JsonObject() })
            {
                JsonObject json = member();
                json.add(field, bad);
                assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(json, GROUP, USER));
            }
            JsonObject json = member();
            JsonArray ids = new JsonArray();
            ids.add(ROLE); ids.add(ROLE);
            json.add(field, ids);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(json, GROUP, USER));
            ids.remove(1); ids.add(42);
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(json, GROUP, USER));
        }
    }

    @Test void optionalManagementRoleArrayAndRolelessActiveMemberArePreserved()
    {
        JsonObject json = member();
        json.add("roleIds", new JsonArray());
        JsonArray management = new JsonArray();
        management.add(ROLE);
        json.add("mRoleIds", management);
        GroupMember result = ScarletBanSnapshot.member(json, GROUP, USER);
        assertTrue(result.getRoleIds().isEmpty());
        assertEquals(java.util.Collections.singletonList(ROLE), result.getmRoleIds());
        assertEquals(GroupMemberStatus.MEMBER, result.getMembershipStatus());
    }

    @Test void errorOrScalarResponsesCannotBeAuthorizationSnapshots()
    {
        JsonObject error = new JsonObject();
        JsonObject detail = new JsonObject();
        detail.addProperty("status_code", 403); detail.addProperty("message", "You're not a member.");
        error.add("error", detail);
        for (JsonElement bad : new JsonElement[] { error, new JsonPrimitive("null"), new JsonPrimitive(false), new JsonArray() })
        {
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.group(bad, GROUP));
            assertThrows(IllegalArgumentException.class, () -> ScarletBanSnapshot.member(bad, GROUP, USER));
        }
    }

    private static JsonObject group()
    {
        JsonObject object = new JsonObject();
        object.addProperty("id", GROUP);
        object.addProperty("ownerId", OWNER);
        JsonObject role = new JsonObject();
        role.addProperty("id", ROLE);
        role.addProperty("order", 2);
        JsonArray permissions = new JsonArray();
        permissions.add("group-bans-manage");
        role.add("permissions", permissions);
        JsonArray roles = new JsonArray();
        roles.add(role);
        object.add("roles", roles);
        return object;
    }

    private static JsonObject role(JsonObject group)
    {
        return group.getAsJsonArray("roles").get(0).getAsJsonObject();
    }

    private static JsonObject member()
    {
        JsonObject object = new JsonObject();
        object.addProperty("groupId", GROUP);
        object.addProperty("userId", USER);
        object.addProperty("membershipStatus", "member");
        JsonArray roles = new JsonArray();
        roles.add(ROLE);
        object.add("roleIds", roles);
        return object;
    }
}

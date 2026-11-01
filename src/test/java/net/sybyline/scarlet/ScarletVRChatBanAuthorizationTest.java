package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import io.github.vrchatapi.ApiException;

/** Exercises the real final authorization boundary without constructing any API client. */
class ScarletVRChatBanAuthorizationTest
{
    static final String GROUP = id("grp", 1), ACTOR = id("usr", 1), BOT = id("usr", 2),
        TARGET = id("usr", 3), OWNER = id("usr", 4), HIGH = id("grol", 1), MID = id("grol", 2), LOW = id("grol", 3);

    static String id(String prefix, int value) { return prefix + "_00000000-0000-0000-0000-" + String.format("%012d", value); }

    static JsonObject member(String user, String role)
    {
        JsonObject member = new JsonObject();
        member.addProperty("groupId", GROUP);
        member.addProperty("userId", user);
        member.addProperty("membershipStatus", "member");
        JsonArray roles = new JsonArray();
        if (role != null) roles.add(role);
        member.add("roleIds", roles);
        member.add("mRoleIds", new JsonArray());
        return member;
    }

    static JsonObject role(String id, int order)
    {
        JsonObject role = new JsonObject();
        role.addProperty("id", id);
        role.addProperty("order", order);
        JsonArray permissions = new JsonArray();
        permissions.add("group-bans-manage");
        role.add("permissions", permissions);
        return role;
    }

    static class Fixture extends ScarletVRChat
    {
        JsonObject group;
        Map<String, JsonElement> members;
        int submissions, reads;
        String failUser;
        ApiException failure;
        Runnable afterTargetRead;
        GroupModerationResult nextOutcome;

        Fixture() { super(null, null, null); throw new AssertionError("No live API construction"); }
        @Override JsonElement readBanGroup(String selectedGroup) { reads++; return group.deepCopy(); }
        @Override JsonElement readBanMember(String selectedGroup, String user) throws ApiException
        {
            reads++;
            if (user.equals(failUser)) throw failure;
            JsonElement result = members.get(user);
            if (user.equals(TARGET) && afterTargetRead != null) afterTargetRead.run();
            return result == null ? null : result.deepCopy();
        }
        @Override GroupBanSubmission submitGroupBanWithIdentity(String selectedGroup, String target)
        {
            assertEquals(GROUP, selectedGroup);
            assertEquals(TARGET, target);
            submissions++;
            return new GroupBanSubmission(nextOutcome, null);
        }
    }

    static Fixture fixture() throws Exception
    {
        Fixture vrc = inert(Fixture.class);
        vrc.groupId = GROUP;
        vrc.currentUserId = BOT;
        vrc.nextOutcome = ScarletVRChat.GroupModerationResult.SUCCEEDED;
        vrc.group = new JsonObject();
        vrc.group.addProperty("id", GROUP);
        vrc.group.addProperty("ownerId", OWNER);
        JsonArray roles = new JsonArray();
        roles.add(role(HIGH, 1)); roles.add(role(MID, 2)); roles.add(role(LOW, 9));
        vrc.group.add("roles", roles);
        vrc.members = new HashMap<>();
        vrc.members.put(ACTOR, member(ACTOR, MID));
        vrc.members.put(BOT, member(BOT, HIGH));
        vrc.members.put(TARGET, member(TARGET, LOW));
        return vrc;
    }

    static void assertBlocked(Fixture vrc, boolean override)
    {
        ScarletVRChat.GroupBanResult result = vrc.banFromGroupAs(ACTOR, TARGET, override);
        assertFalse(result.authorization.allowed());
        assertNull(result.outcome);
        assertEquals(0, vrc.submissions, "Denied requests must never reach the API write seam");
    }

    @Test void validFinalCheckSubmitsExactlyOnce() throws Exception
    {
        Fixture vrc = fixture();
        ScarletVRChat.GroupBanResult result = vrc.banFromGroupAs(ACTOR, TARGET, false);
        assertTrue(result.authorization.allowed(), result.authorization.message());
        assertEquals(ScarletVRChat.GroupModerationResult.SUCCEEDED, result.outcome);
        assertEquals(4, vrc.reads);
        assertEquals(1, vrc.submissions);
    }

    @Test void promotionAfterUiPrecheckIsCaughtAtFinalBoundary() throws Exception
    {
        Fixture vrc = fixture();
        assertTrue(vrc.checkBanAuthorization(ACTOR, TARGET, false).allowed());
        vrc.members.put(TARGET, member(TARGET, HIGH));
        assertBlocked(vrc, false);
        assertEquals(8, vrc.reads);
    }

    @Test void actorDemotionAndPermissionRevocationInvalidateEarlierChecks() throws Exception
    {
        Fixture demoted = fixture();
        assertTrue(demoted.checkBanAuthorization(ACTOR, TARGET, false).allowed());
        demoted.members.put(ACTOR, member(ACTOR, LOW));
        assertBlocked(demoted, false);
        Fixture revoked = fixture();
        assertTrue(revoked.checkBanAuthorization(ACTOR, TARGET, false).allowed());
        revoked.group.getAsJsonArray("roles").get(1).getAsJsonObject().add("permissions", new JsonArray());
        assertBlocked(revoked, false);
    }

    @Test void botDemotionAndOwnerTransferAreRechecked() throws Exception
    {
        Fixture demoted = fixture();
        demoted.members.put(BOT, member(BOT, LOW));
        assertBlocked(demoted, true);
        Fixture transfer = fixture();
        transfer.group.addProperty("ownerId", TARGET);
        assertBlocked(transfer, true);
    }

    @Test void everyLookupFailureIsUnknownNotAbsent() throws Exception
    {
        for (String user : new String[] { ACTOR, BOT, TARGET })
            for (int status : new int[] { 0, 401, 403, 404, 429, 500 })
            {
                Fixture vrc = fixture();
                vrc.failUser = user;
                vrc.failure = new ApiException(status, "not a member");
                assertBlocked(vrc, true);
            }
    }

    @Test void onlySuccessfulExplicitNullAllowsAnOutsideTarget() throws Exception
    {
        Fixture vrc = fixture();
        vrc.members.put(TARGET, ScarletVRChat.parseBanResponse("null"));
        assertTrue(vrc.banFromGroupAs(ACTOR, TARGET, false).authorization.allowed());
        assertEquals(1, vrc.submissions);
        Fixture missing = fixture();
        missing.members.remove(TARGET);
        assertBlocked(missing, true);
    }

    @Test void absentActorOrBotNeverReceivesOutsiderAuthority() throws Exception
    {
        for (String user : new String[] { ACTOR, BOT })
        {
            Fixture vrc = fixture();
            vrc.members.put(user, JsonNull.INSTANCE);
            assertBlocked(vrc, true);
        }
    }

    @Test void sdkDefaultsCannotMakeMissingRoleIdsLookLikeOrdinaryMembership() throws Exception
    {
        Fixture vrc = fixture();
        vrc.members.get(TARGET).getAsJsonObject().remove("roleIds");
        assertBlocked(vrc, true);
    }

    @Test void groupOrAuthenticatedAccountChangeDuringLookupStopsSubmission() throws Exception
    {
        Fixture changedGroup = fixture();
        changedGroup.afterTargetRead = () -> changedGroup.groupId = id("grp", 2);
        assertBlocked(changedGroup, false);
        Fixture changedBot = fixture();
        changedBot.afterTargetRead = () -> changedBot.currentUserId = OWNER;
        assertBlocked(changedBot, false);
    }

    @Test void desktopWrapperUsesBotAuthorityAndStillProtectsOwner() throws Exception
    {
        Fixture vrc = fixture();
        assertEquals(ScarletVRChat.GroupModerationResult.SUCCEEDED, vrc.banFromGroupDetailed(TARGET));
        assertEquals(3, vrc.reads, "Actor and bot share one verified membership on the desktop path");
        Fixture protectedTarget = fixture();
        protectedTarget.group.addProperty("ownerId", TARGET);
        assertEquals(ScarletVRChat.GroupModerationResult.REJECTED, protectedTarget.banFromGroupDetailed(TARGET));
        assertEquals(0, protectedTarget.submissions);
    }

    @Test void uncertainAndRejectedWritesRetainTheirDistinctOutcomes() throws Exception
    {
        for (ScarletVRChat.GroupModerationResult outcome : ScarletVRChat.GroupModerationResult.values())
        {
            Fixture vrc = fixture();
            vrc.nextOutcome = outcome;
            ScarletVRChat.GroupBanResult result = vrc.banFromGroupAs(ACTOR, TARGET, false);
            assertTrue(result.authorization.allowed());
            assertEquals(outcome, result.outcome);
            assertEquals(1, vrc.submissions);
        }
    }

    @Test void rawDecoderPreservesNullButRejectsEmptyMalformedAndTrailingBodies()
    {
        assertSame(JsonNull.INSTANCE, ScarletVRChat.parseBanResponse(" \nnull\t"));
        assertTrue(ScarletVRChat.parseBanResponse("{\"roles\":[]}").isJsonObject());
        for (String body : new String[] { null, "", "   ", "null {}", "{roles:[]}", "//comment\nnull", "{", "NaN" })
            assertThrows(RuntimeException.class, () -> ScarletVRChat.parseBanResponse(body), String.valueOf(body));
    }

    static okhttp3.Call syntheticResponse(int code, String body)
    {
        // The application interceptor never proceeds to the network: all HTTP bodies are synthetic.
        return new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> new okhttp3.Response.Builder()
            .request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("Synthetic")
            .body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"), body)).build())
            .build().newCall(new okhttp3.Request.Builder().url("https://example.invalid/authorization-test").build());
    }

    @Test void actualHttpReaderPreservesSuccessfulNull() throws Exception
    {
        assertSame(JsonNull.INSTANCE, ScarletVRChat.readBanResponse(syntheticResponse(200, "null")));
    }

    @Test void errorResponseNeverBecomesConfirmedAbsence()
    {
        for (int code : new int[]{401, 403, 404, 429, 500})
            assertThrows(ApiException.class, () -> ScarletVRChat.readBanResponse(syntheticResponse(code, "null")));
    }

    @Test void emptySuccessfulResponseIsNotConfirmedAbsence()
    {
        for (int code : new int[]{200, 204})
            assertThrows(IllegalArgumentException.class, () -> ScarletVRChat.readBanResponse(syntheticResponse(code, "")));
    }

    @Test void oversizedBodyIsRefusedBeforeParsing()
    {
        assertThrows(IllegalArgumentException.class, () -> ScarletVRChat.readBanResponse(
            syntheticResponse(200, " ".repeat(2 * 1024 * 1024 + 1))));
    }
}

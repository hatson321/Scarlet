package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.Configuration;
import io.github.vrchatapi.JSON;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** Intercepts real generated request methods; DNS and real moderation are forbidden. */
class ScarletTimedBanTransportTest
{
    static final String GROUP = ScarletTimedBanContinuityTest.GROUP, USER = ScarletTimedBanContinuityTest.USER;
    static final OffsetDateTime BAN = ScarletTimedBanContinuityTest.BAN;

    @Test void serverBanIdentityComesFromTheSamePostResponseNotALaterLookup() throws Exception
    {
        try (Fixture f = new Fixture("valid"))
        {
            ScarletVRChat.GroupBanSubmission result = f.vrc.submitGroupBanWithIdentity(GROUP, USER);
            assertEquals(ScarletVRChat.GroupModerationResult.SUCCEEDED, result.outcome);
            assertEquals(BAN, result.bannedAt);
            assertEquals(1, f.requests.size());
            assertEquals("POST", f.requests.get(0).method());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing_time", "wrong_group", "wrong_user", "not_banned", "empty"})
    void successfulButUnverifiableResponsesCannotProvideATimerIdentity(String kind) throws Exception
    {
        try (Fixture f = new Fixture(kind))
        {
            ScarletVRChat.GroupBanSubmission result = f.vrc.submitGroupBanWithIdentity(GROUP, USER);
            assertEquals(ScarletVRChat.GroupModerationResult.SUCCEEDED, result.outcome);
            assertNull(result.bannedAt);
            assertEquals(1, f.requests.size(), "No guessed identity via follow-up GET");
        }
    }

    @Test void changedSelectedGroupCannotReachTheBanTransport() throws Exception
    {
        try (Fixture f = new Fixture("valid"))
        {
            f.vrc.groupId = "grp_other";
            assertEquals(ScarletVRChat.GroupModerationResult.REJECTED,
                f.vrc.submitGroupBanWithIdentity(GROUP, USER).outcome);
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test void currentBanAndAuditHistoryReadTheRecordedGroupAndTarget() throws Exception
    {
        try (Fixture f = new Fixture("history"))
        {
            ScarletPendingModActions.TimedBan timer = new ScarletPendingModActions.TimedBan();
            timer.groupId = GROUP;
            timer.originalBanAt = BAN.toInstant().toString();
            timer.originalBanActorId = ScarletTimedBanContinuityTest.BOT;
            f.vrc.groupId = "grp_mutable_setting_not_used";
            // BAN is a fixed synthetic date: use the pure verifier's bounded clock but
            // actual generated requests, rather than relying on the machine's date.
            io.github.vrchatapi.api.GroupsApi api = new io.github.vrchatapi.api.GroupsApi(f.client);
            ScarletTimedBanContinuity.Proof proof = ScarletTimedBanContinuity.verify(GROUP, USER, timer,
                ScarletTimedBanContinuityTest.FROM, ScarletTimedBanContinuityTest.TO,
                () -> api.getGroupMember(GROUP, USER),
                (n, o) -> api.getGroupAuditLogs(GROUP, n, o, BAN.minusSeconds(5), BAN.plusHours(1), null, null, USER));
            assertEquals(ScarletTimedBanContinuity.Verdict.VERIFIED, proof.verdict);
            assertEquals(3, f.requests.size());
            Request history = f.requests.get(1);
            assertEquals(USER, history.url().queryParameter("targetIds"));
            assertEquals("100", history.url().queryParameter("n"));
            assertEquals("0", history.url().queryParameter("offset"));
            assertNotNull(history.url().queryParameter("startDate"));
            assertNotNull(history.url().queryParameter("endDate"));
        }
    }

    static final class Fixture implements AutoCloseable
    {
        final Gson previous;
        final ApiClient client;
        final ScarletVRChat vrc;
        final List<Request> requests = new ArrayList<>();

        Fixture(String kind) throws Exception
        {
            Configuration.getDefaultApiClient();
            previous = JSON.getGson();
            OkHttpClient http = new OkHttpClient.Builder().dns(host -> { throw new AssertionError("No network allowed"); })
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    requests.add(request);
                    assertEquals("timed-ban.invalid", request.url().host());
                    assertNull(request.header("Cookie"));
                    assertNull(request.header("Authorization"));
                    String path = request.url().encodedPath();
                    JsonObject reply = new JsonObject();
                    if (kind.equals("history") && path.endsWith("/auditLogs"))
                    {
                        assertEquals("/api/1/groups/" + GROUP + "/auditLogs", path);
                        reply = JsonParser.parseString(Scarlet.GSON.toJson(ScarletTimedBanContinuityTest.page(
                            java.util.Collections.singletonList(ScarletTimedBanContinuityTest.original()), 1, false))).getAsJsonObject();
                    }
                    else
                    {
                        if (kind.equals("history")) assertEquals("/api/1/groups/" + GROUP + "/members/" + USER, path);
                        else
                        {
                            assertEquals("POST", request.method());
                            assertEquals("/api/1/groups/" + GROUP + "/bans", path);
                            Buffer body = new Buffer();
                            request.body().writeTo(body);
                            assertEquals(USER, JsonParser.parseString(body.readUtf8()).getAsJsonObject().get("userId").getAsString());
                        }
                        if (!kind.equals("empty"))
                        {
                            reply.addProperty("groupId", kind.equals("wrong_group") ? "grp_other" : GROUP);
                            reply.addProperty("userId", kind.equals("wrong_user") ? "usr_other" : USER);
                            reply.addProperty("membershipStatus", kind.equals("not_banned") ? "member" : "banned");
                            if (!kind.equals("missing_time")) reply.addProperty("bannedAt", BAN.toString());
                        }
                    }
                    return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                        .message("Synthetic response").header("Content-Type", "application/json")
                        .body(ResponseBody.create(MediaType.parse("application/json"), reply.toString())).build();
                }).build();
            client = new ApiClient(http).setBasePath("https://timed-ban.invalid/api/1");
            ScarletVRChat.initJson();
            vrc = ScarletDiscordSafetyTest.inert(ScarletVRChat.class);
            ScarletDiscordSafetyTest.put(vrc, "client", client);
            vrc.groupId = GROUP;
        }

        @Override public void close()
        {
            JSON.setGson(previous);
            client.getHttpClient().connectionPool().evictAll();
            client.getHttpClient().dispatcher().executorService().shutdownNow();
        }
    }
}

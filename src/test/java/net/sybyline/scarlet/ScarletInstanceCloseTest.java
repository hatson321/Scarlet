package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.Configuration;
import io.github.vrchatapi.JSON;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.sybyline.scarlet.log.ScarletLogger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Synthetic responses only: closing an instance must never be retried automatically. */
class ScarletInstanceCloseTest
{
    private static final String WORLD = "wrld_00000000-0000-0000-0000-000000000000";
    private static final String GROUP = "grp_00000000-0000-0000-0000-000000000000";
    private static final String INSTANCE = "12345~group(" + GROUP + ")~groupAccessType(members)~ageGate~region(us)";
    private static final String LOCATION = WORLD + ":" + INSTANCE;
    private static final String PLUS_USE_INSTANCE = "54321~group(" + GROUP + ")~groupAccessType(plus)~region(use)";
    private static final OffsetDateTime FUTURE = OffsetDateTime.parse("2100-01-01T12:34:56Z");
    private static final OffsetDateTime PAST = OffsetDateTime.parse("2020-01-01T12:34:56Z");
    private static final String ALREADY_CLOSED = "You're not allowed to close an already closed instance.";
    private static final String PRIVATE_DETAIL = "SYNTHETIC_PRIVATE_SERVER_DETAIL";
    @TempDir Path temp;

    @Test void groupPlusUsEastFullLinkKeepsEveryTagWithBothOptionsOmitted() throws Exception
    {
        try (Fixture f = new Fixture(403, error("Not allowed to close this instance.", 403)))
        {
            ScarletVRChatBanEntryPointsTest.Fixture command = commandFixture(f);
            command.commands.new VrchatGroups().closeInstance(
                command.event(SlashCommandInteractionEvent.class, "synthetic", "close-instance"), command.hook,
                "https://vrchat.com/home/launch?worldId=" + WORLD + "&instanceId=" + PLUS_USE_INSTANCE,
                null, null);
            f.assertRequest(WORLD + ":" + PLUS_USE_INSTANCE, null, null);
            assertEquals(1, command.messages.size());
            String reply = command.messages.get(0);
            assertTrue(reply.contains("HTTP 403"));
            assertFalse(reply.toLowerCase(Locale.ROOT).contains("permission"));
            assertFalse(reply.contains("Not allowed to close this instance."));
            assertTrue(reply.contains("Reference:"));
        }
    }

    @Test void rejectedCloseKeepsRequestedOptionsSeparateFromUnconfirmedCloseTime() throws Exception
    {
        for (Boolean hard : new Boolean[]{null, Boolean.FALSE, Boolean.TRUE})
            for (OffsetDateTime at : new OffsetDateTime[]{null, FUTURE})
                try (Fixture f = new Fixture(403, error("Not allowed to close this instance.", 403)))
                {
                    ScarletInstanceClose.Result result = f.submit(hard, at);
                    assertEquals(ScarletInstanceClose.Status.REJECTED, result.status);
                    assertEquals(hard, result.requestedHardClose);
                    assertEquals(at, result.requestedClosedAt);
                    assertNull(result.closedAt, "A rejected reply does not confirm the requested closing time");
                    f.assertRequest(hard, at);
                }
    }

    @Test void forbiddenReplyIsNeutralAndReferencesDiagnosticsWithoutEchoingServerText() throws Exception
    {
        try (Fixture f = new Fixture(403, error(PRIVATE_DETAIL, 403)))
        {
            ScarletInstanceClose.Result result = f.submit(null, null);
            String reply = result.message();
            assertTrue(reply.contains("HTTP 403"));
            assertTrue(reply.contains(result.diagnosticId));
            assertTrue(reply.contains("local log"));
            assertFalse(reply.toLowerCase(Locale.ROOT).contains("permission"));
            assertFalse(reply.contains("group-instance-manage"));
            assertFalse(reply.contains(PRIVATE_DETAIL));
        }
    }

    @Test void eachSubmissionHasItsOwnDiagnosticReference() throws Exception
    {
        String first;
        try (Fixture f = new Fixture(403, error(PRIVATE_DETAIL, 403)))
        {
            first = f.submit(null, null).diagnosticId;
        }
        try (Fixture f = new Fixture(403, error(PRIVATE_DETAIL, 403)))
        {
            assertNotEquals(first, f.submit(null, null).diagnosticId);
        }
    }

    @Test void actualDiagnosticLogsCorrelateRequestReplyAndOutcomeWithoutRawData() throws Exception
    {
        JsonObject replyBody = new JsonObject();
        JsonObject detail = new JsonObject();
        detail.addProperty("message", "Not allowed to close this instance.");
        detail.addProperty("status_code", 403);
        replyBody.add("error", detail);
        replyBody.addProperty("unrelated", PRIVATE_DETAIL);
        try (Fixture f = new Fixture(403, replyBody.toString()); LogCapture logs = new LogCapture())
        {
            ScarletVRChatBanEntryPointsTest.Fixture command = commandFixture(f);
            command.commands.new VrchatGroups().closeInstance(
                command.event(SlashCommandInteractionEvent.class, "synthetic", "close-instance"), command.hook,
                "https://vrchat.com/home/launch?worldId=" + WORLD + "&instanceId=" + PLUS_USE_INSTANCE,
                null, null);
            f.assertRequest(WORLD + ":" + PLUS_USE_INSTANCE, null, null);
            String reply = command.messages.get(0);
            java.util.regex.Matcher reference = java.util.regex.Pattern.compile(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(reply);
            assertTrue(reference.find(), "Discord must provide the local diagnostic reference");
            String id = reference.group();
            String output = logs.text();
            assertTrue(output.contains("Close-instance request [" + id + "]"));
            assertTrue(output.contains("Close-instance response [" + id + "]"));
            assertTrue(output.contains("outcome [" + id + "]: REJECTED"));
            assertTrue(output.contains("requestedHardClose=omitted (default false)"));
            assertTrue(output.contains("requestedClosedAt=omitted (immediate)"));
            assertTrue(output.contains("effectiveClosedAt=null"));
            assertTrue(output.contains("error.message=not allowed to close this instance"));
            assertTrue(output.contains("status_code=403"));
            assertFalse(output.contains(PRIVATE_DETAIL));
            assertFalse(output.contains("X-Synthetic-Private"));
            assertFalse(output.contains(replyBody.toString()));
            assertFalse(output.contains(WORLD));
            assertFalse(output.contains(GROUP));
            assertFalse(output.contains("Synthetic target"));
            assertFalse(reply.contains("not allowed to close this instance"));
        }
    }

    @Test void timedFailureLogsTheRequestedTimeWithoutPretendingItWasConfirmed() throws Exception
    {
        try (Fixture f = new Fixture(403, error("Not allowed to close this instance.", 403));
             LogCapture logs = new LogCapture())
        {
            ScarletInstanceClose.Result result = f.submit(Boolean.TRUE, FUTURE);
            String output = logs.text();
            assertEquals(ScarletInstanceClose.Status.REJECTED, result.status);
            assertNull(result.closedAt);
            assertTrue(output.contains("[" + result.diagnosticId + "]"));
            assertTrue(output.contains("requestedHardClose=true"));
            assertTrue(output.contains("requestedClosedAt=" + FUTURE));
            assertFalse(output.contains("requestedClosedAt=null"));
            f.assertRequest(Boolean.TRUE, FUTURE);
        }
    }

    @Test void slashCommandSchedulesAFullAgeGatedLinkAndReportsTheScheduledTime() throws Exception
    {
        try (Fixture f = new Fixture(200, instanceBody(null)))
        {
            ScarletVRChatBanEntryPointsTest.Fixture command = commandFixture(f);
            OffsetDateTime before = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1);
            command.commands.new VrchatGroups().closeInstance(
                command.event(SlashCommandInteractionEvent.class, "synthetic", "close-instance"), command.hook,
                "https://vrchat.com/home/launch?worldId=" + WORLD + "&instanceId=" + INSTANCE, Boolean.FALSE, 1);
            OffsetDateTime after = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1);
            assertEquals(1, f.requests.size());
            OffsetDateTime requested = OffsetDateTime.parse(f.requests.get(0).url().queryParameter("closedAt"));
            assertFalse(requested.isBefore(before));
            assertFalse(requested.isAfter(after));
            f.assertRequest(Boolean.FALSE, requested);
            assertEquals(1, command.messages.size());
            assertTrue(command.messages.get(0).contains("closure scheduled"));
            assertTrue(command.messages.get(0).contains("<t:" + requested.toEpochSecond() + ":F>"));
        }
    }

    @Test void slashCommandTurnsAlreadyClosedResponseIntoHelpfulStatus() throws Exception
    {
        try (Fixture f = new Fixture(403, error(ALREADY_CLOSED, 403)))
        {
            ScarletVRChatBanEntryPointsTest.Fixture command = commandFixture(f);
            command.commands.new VrchatGroups().closeInstance(
                command.event(SlashCommandInteractionEvent.class, "synthetic", "close-instance"), command.hook,
                LOCATION, null, null);
            assertEquals(1, f.requests.size());
            assertEquals(1, command.messages.size());
            assertEquals("This instance is already closed. No additional close was performed.", command.messages.get(0));
        }
    }

    @Test void slashCommandKeepsPermissionCheckBeforeAnyCloseRequest() throws Exception
    {
        try (Fixture f = new Fixture(200, instanceBody(null)))
        {
            ScarletVRChatBanEntryPointsTest.Fixture command = commandFixture(f);
            command.vrc.actorCanBan = false; // The shared fixture uses this switch for any VRChat permission.
            command.commands.new VrchatGroups().closeInstance(
                command.event(SlashCommandInteractionEvent.class, "synthetic", "close-instance"), command.hook,
                LOCATION, Boolean.TRUE, 1);
            assertTrue(f.requests.isEmpty());
            assertEquals(1, command.messages.size());
            assertTrue(command.messages.get(0).contains("do not have permission"));
        }
    }

    @Test void slashCommandKeepsInputAndOwnGroupChecksBeforeAnyCloseRequest() throws Exception
    {
        for (String input : new String[]{WORLD,
            LOCATION.replace(GROUP, "grp_00000000-0000-0000-0000-000000000001")})
            try (Fixture f = new Fixture(200, instanceBody(null)))
            {
                ScarletVRChatBanEntryPointsTest.Fixture command = commandFixture(f);
                command.commands.new VrchatGroups().closeInstance(
                    command.event(SlashCommandInteractionEvent.class, "synthetic", "close-instance"), command.hook,
                    input, null, null);
                assertTrue(f.requests.isEmpty());
                assertEquals(1, command.messages.size());
                assertFalse(command.messages.get(0).startsWith("Closed instance"));
            }
    }

    private ScarletVRChatBanEntryPointsTest.Fixture commandFixture(Fixture transport) throws Exception
    {
        ScarletVRChatBanEntryPointsTest.Fixture command = new ScarletVRChatBanEntryPointsTest.Fixture(temp);
        command.vrc.groupId = GROUP;
        ScarletDiscordSafetyTest.put(command.vrc, "client", transport.client);
        return command;
    }

    @Test void immediateCloseAcceptsDateOnlyJoinDateInSuccessfulInstanceResponse() throws Exception
    {
        try (Fixture f = new Fixture(200, instanceBody(PAST)))
        {
            ScarletInstanceClose.Result result = f.submit(null, null);
            assertEquals(ScarletInstanceClose.Status.CLOSED, result.status);
            assertEquals(LOCATION, result.location);
            assertEquals(PAST, result.closedAt);
            assertTrue(result.message().startsWith("Closed instance"));
            f.assertRequest(null, null);
        }
    }

    @Test void oneMinuteScheduleAcceptsDateOnlyJoinDateWithoutReportingPrematureClosure() throws Exception
    {
        OffsetDateTime oneMinuteLater = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1);
        try (Fixture f = new Fixture(200, instanceBody(oneMinuteLater)))
        {
            ScarletInstanceClose.Result result = f.submit(Boolean.FALSE, oneMinuteLater);
            assertEquals(ScarletInstanceClose.Status.SCHEDULED, result.status);
            assertEquals(oneMinuteLater, result.closedAt);
            assertTrue(result.message().contains("closure scheduled"));
            assertTrue(result.message().contains("<t:" + oneMinuteLater.toEpochSecond() + ":F>"));
            assertFalse(result.message().startsWith("Closed instance"));
            f.assertRequest(Boolean.FALSE, oneMinuteLater);
        }
    }

    @Test void everyHardCloseAndTimingOptionIsPassedUnchangedInOneRequest() throws Exception
    {
        for (Boolean hard : new Boolean[]{null, Boolean.FALSE, Boolean.TRUE})
            for (OffsetDateTime at : new OffsetDateTime[]{null, FUTURE})
                try (Fixture f = new Fixture(200, instanceBody(at)))
                {
                    ScarletInstanceClose.Result result = f.submit(hard, at);
                    assertEquals(at == null ? ScarletInstanceClose.Status.CLOSED : ScarletInstanceClose.Status.SCHEDULED,
                        result.status);
                    f.assertRequest(hard, at);
                }
    }

    @Test void successfulReplyUsesValidatedRequestLocationInsteadOfUntrustedResponseId() throws Exception
    {
        try (Fixture f = new Fixture(200, "{\"id\":\"" + PRIVATE_DETAIL + "\",\"location\":\"unrelated-location\"}"))
        {
            ScarletInstanceClose.Result result = f.submit(null, null);
            assertEquals(ScarletInstanceClose.Status.CLOSED, result.status);
            assertEquals(LOCATION, result.location);
            assertTrue(result.message().contains(LOCATION));
            assertFalse(result.message().contains(PRIVATE_DETAIL));
        }
    }

    @Test void acknowledgedEmptySuccessDoesNotTriggerAnotherDestructiveRequest() throws Exception
    {
        try (Fixture f = new Fixture(204, ""))
        {
            ScarletInstanceClose.Result result = f.submit(null, null);
            assertEquals(ScarletInstanceClose.Status.CLOSED, result.status);
            assertEquals(LOCATION, result.location);
            f.assertRequest(null, null);
        }
    }

    @Test void absentResponseTimestampUsesTheRequestedSchedule() throws Exception
    {
        try (Fixture f = new Fixture(200, "{}"))
        {
            ScarletInstanceClose.Result result = f.submit(null, FUTURE);
            assertEquals(ScarletInstanceClose.Status.SCHEDULED, result.status);
            assertEquals(FUTURE, result.closedAt);
            f.assertRequest(null, FUTURE);
        }
    }

    @Test void actualResponseTimestampTakesPrecedenceOverRequestedTimestamp() throws Exception
    {
        try (Fixture f = new Fixture(200, instanceBody(PAST)))
        {
            ScarletInstanceClose.Result result = f.submit(null, FUTURE);
            assertEquals(ScarletInstanceClose.Status.CLOSED, result.status);
            assertEquals(PAST, result.closedAt);
        }
        try (Fixture f = new Fixture(200, instanceBody(FUTURE)))
        {
            ScarletInstanceClose.Result result = f.submit(null, null);
            assertEquals(ScarletInstanceClose.Status.SCHEDULED, result.status);
            assertEquals(FUTURE, result.closedAt);
        }
    }

    @Test void exactAlreadyClosedResponseIsAStatusNotANewClosure() throws Exception
    {
        try (Fixture f = new Fixture(403, error(ALREADY_CLOSED, 403)))
        {
            ScarletInstanceClose.Result result = f.submit(null, null);
            assertEquals(ScarletInstanceClose.Status.ALREADY_CLOSED, result.status);
            assertEquals("This instance is already closed. No additional close was performed.", result.message());
            f.assertRequest(null, null);
        }
    }

    @Test void alreadyClosedAllowsAbsentStatusAndSurroundingMessageWhitespace() throws Exception
    {
        try (Fixture f = new Fixture(403, error("  " + ALREADY_CLOSED + "\n", null)))
        {
            assertEquals(ScarletInstanceClose.Status.ALREADY_CLOSED, f.submit(null, null).status);
        }
    }

    @Test void alreadyClosedPhraseDoesNotOverrideAnotherHttpStatus() throws Exception
    {
        for (int code : new int[]{401, 404, 429})
            try (Fixture f = new Fixture(code, error(ALREADY_CLOSED, 403)))
            {
                assertEquals(ScarletInstanceClose.Status.REJECTED, f.submit(null, null).status);
            }
    }

    @Test void unrelatedOrAmbiguousForbiddenMessagesAreNotCalledAlreadyClosed() throws Exception
    {
        for (String body : new String[]{
            error("You lack permission. " + PRIVATE_DETAIL, 403),
            error("Prefix " + ALREADY_CLOSED, 403),
            error(ALREADY_CLOSED + " Additional information.", 403),
            error(ALREADY_CLOSED, 401),
            "{\"error\":{\"message\":[\"" + ALREADY_CLOSED + "\"],\"status_code\":403}}",
            "{\"message\":\"" + ALREADY_CLOSED + "\",\"status_code\":403}",
            "{\"error\":null}", "not-json"})
            try (Fixture f = new Fixture(403, body))
            {
                ScarletInstanceClose.Result result = f.submit(null, null);
                assertEquals(ScarletInstanceClose.Status.REJECTED, result.status, body);
                assertFalse(result.message().contains(PRIVATE_DETAIL));
                assertFalse(result.message().contains("No additional close was performed"));
            }
    }

    @Test void deeplyNestedForbiddenBodyFailsClosedWithoutCrashOrAnotherRequest() throws Exception
    {
        StringBuilder body = new StringBuilder("{\"error\":{\"message\":\"")
            .append(ALREADY_CLOSED).append("\",\"status_code\":403},\"unrelated\":");
        for (int i = 0; i < 2000; i++) body.append('[');
        body.append('0');
        for (int i = 0; i < 2000; i++) body.append(']');
        body.append('}');
        try (Fixture f = new Fixture(403, body.toString()); LogCapture logs = new LogCapture())
        {
            ScarletInstanceClose.Result result = f.submit(null, null);
            assertEquals(ScarletInstanceClose.Status.REJECTED, result.status);
            assertTrue(result.message().contains("HTTP 403"));
            assertFalse(result.message().contains(ALREADY_CLOSED));
            assertTrue(logs.text().contains("error body omitted: structure limit exceeded"));
            assertFalse(logs.text().contains(body.toString()));
            f.assertRequest(null, null);
        }
    }

    @Test void rejectedAuthenticationPermissionMissingInstanceAndRateLimitUseSafeMessages() throws Exception
    {
        for (int code : new int[]{401, 403, 404, 429})
            try (Fixture f = new Fixture(code, error(PRIVATE_DETAIL, code)))
            {
                ScarletInstanceClose.Result result = f.submit(null, FUTURE);
                assertEquals(ScarletInstanceClose.Status.REJECTED, result.status);
                assertNotNull(result.message());
                assertFalse(result.message().isEmpty());
                assertFalse(result.message().contains(PRIVATE_DETAIL));
                assertFalse(result.message().startsWith("Closed instance"));
                f.assertRequest(null, FUTURE);
            }
    }

    @Test void serverErrorsDoNotClaimFailureOrRetryTheClose() throws Exception
    {
        for (int code : new int[]{500, 502, 503})
            try (Fixture f = new Fixture(code, error(PRIVATE_DETAIL, code)))
            {
                ScarletInstanceClose.Result result = f.submit(Boolean.TRUE, FUTURE);
                assertEquals(ScarletInstanceClose.Status.UNCERTAIN, result.status);
                assertFalse(result.message().contains(PRIVATE_DETAIL));
                f.assertRequest(Boolean.TRUE, FUTURE);
            }
    }

    @Test void malformedSuccessfulJsonIsUncertainRatherThanAutomaticallyResubmitted() throws Exception
    {
        for (String body : new String[]{"{broken json", "{\"closedAt\":\"not-a-timestamp\"}"})
            try (Fixture f = new Fixture(200, body))
            {
                ScarletInstanceClose.Result result = f.submit(Boolean.FALSE, FUTURE);
                assertEquals(ScarletInstanceClose.Status.UNCERTAIN, result.status);
                assertFalse(result.message().contains(body));
                f.assertRequest(Boolean.FALSE, FUTURE);
            }
    }

    @Test void transportFailureIsUncertainAndDoesNotResubmit() throws Exception
    {
        try (Fixture f = new Fixture(0, PRIVATE_DETAIL))
        {
            ScarletInstanceClose.Result result = f.submit(null, null);
            assertEquals(ScarletInstanceClose.Status.UNCERTAIN, result.status);
            assertFalse(result.message().contains(PRIVATE_DETAIL));
            f.assertRequest(null, null);
        }
    }

    private static String instanceBody(OffsetDateTime at)
    {
        return "{\"id\":\"" + LOCATION + "\",\"location\":\"" + LOCATION + "\","
            + "\"worldId\":\"" + WORLD + "\",\"instanceId\":\"" + INSTANCE + "\","
            + "\"closedAt\":" + (at == null ? "null" : "\"" + at + "\"")
            + ",\"users\":[{\"id\":\"usr_00000000-0000-0000-0000-000000000000\","
            + "\"displayName\":\"Synthetic close-response user\",\"date_joined\":\"2017-10-17\"}]}";
    }

    private static String error(String message, Integer status)
    {
        JsonObject detail = new JsonObject();
        detail.addProperty("message", message);
        if (status != null) detail.addProperty("status_code", status);
        JsonObject body = new JsonObject();
        body.add("error", detail);
        return body.toString();
    }

    /** Capture only this synthetic test's synchronous logger output, restoring both globals. */
    private static final class LogCapture implements AutoCloseable
    {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream previousOutput = System.out;
        final boolean previousConsoleOnly;
        final PrintStream output;

        LogCapture() throws Exception
        {
            Field consoleOnly = ScarletLogger.class.getDeclaredField("consoleOnly");
            consoleOnly.setAccessible(true);
            previousConsoleOnly = consoleOnly.getBoolean(null);
            output = new PrintStream(bytes, true, "UTF-8");
            ScarletLogger.setConsoleOnly(true);
            System.setOut(output);
        }

        String text() throws Exception
        {
            output.flush();
            return bytes.toString("UTF-8");
        }

        @Override public void close()
        {
            System.setOut(previousOutput);
            ScarletLogger.setConsoleOnly(previousConsoleOnly);
            output.close();
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final Gson previousGson;
        final ApiClient client;
        final List<Request> requests = new ArrayList<>();

        Fixture(int code, String body)
        {
            Configuration.getDefaultApiClient();
            previousGson = JSON.getGson();
            OkHttpClient http = new OkHttpClient.Builder()
                .dns(host -> { throw new AssertionError("Network forbidden in close-instance tests"); })
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    requests.add(request);
                    assertEquals("close.invalid", request.url().host());
                    assertNull(request.header("Authorization"));
                    assertNull(request.header("Cookie"));
                    if (code == 0) throw new IOException(body);
                    // Never call chain.proceed: no request is sent to a real service.
                    return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(code).message("Synthetic offline response")
                        .header("Content-Type", "application/json")
                        .header("X-Synthetic-Private", PRIVATE_DETAIL)
                        .body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
                }).build();
            client = new ApiClient(http).setBasePath("https://close.invalid/api/1");
            ScarletVRChat.initJson();
        }

        ScarletInstanceClose.Result submit(Boolean hard, OffsetDateTime at)
        {
            ScarletInstanceClose.Result result = ScarletInstanceClose.submit(client, WORLD, INSTANCE, hard, at);
            assertEquals(1, requests.size(), "A close request must never be automatically retried or followed by another request");
            assertEquals(hard, result.requestedHardClose);
            assertEquals(at, result.requestedClosedAt);
            assertNotNull(result.diagnosticId);
            UUID reference = UUID.fromString(result.diagnosticId);
            assertEquals(reference.toString(), result.diagnosticId);
            assertEquals(4, reference.version(), "A correlation reference must be an independently generated UUID");
            if (result.status == ScarletInstanceClose.Status.REJECTED || result.status == ScarletInstanceClose.Status.UNCERTAIN)
                assertTrue(result.message().contains(result.diagnosticId));
            return result;
        }

        void assertRequest(Boolean hard, OffsetDateTime at)
        {
            assertRequest(LOCATION, hard, at);
        }

        void assertRequest(String location, Boolean hard, OffsetDateTime at)
        {
            assertEquals(1, requests.size());
            Request request = requests.get(0);
            assertEquals("DELETE", request.method());
            assertEquals("/api/1/instances/" + location, request.url().uri().getPath());
            assertEquals(hard == null ? null : hard.toString(), request.url().queryParameter("hardClose"));
            String sentAt = request.url().queryParameter("closedAt");
            assertEquals(at, sentAt == null ? null : OffsetDateTime.parse(sentAt),
                "Equivalent ISO timestamps may omit trailing fractional-second zeros");
        }

        @Override public void close()
        {
            JSON.setGson(previousGson);
            client.getHttpClient().connectionPool().evictAll();
            client.getHttpClient().dispatcher().executorService().shutdownNow();
        }
    }
}

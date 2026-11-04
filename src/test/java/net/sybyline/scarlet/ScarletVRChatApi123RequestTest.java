package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.Configuration;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.model.CalendarEventAccess;
import io.github.vrchatapi.model.CalendarEventCategory;
import io.github.vrchatapi.model.CreateInstanceRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** Exercises production request builders without application startup or network access. */
class ScarletVRChatApi123RequestTest
{
    static final String GROUP = "grp_00000000-0000-0000-0000-000000000000";
    static final String WORLD = "wrld_00000000-0000-0000-0000-000000000000";
    static final String EVENT = "cal_00000000-0000-0000-0000-000000000000";
    static final LocalDate DATE = LocalDate.of(2026, 10, 12);

    @Test void newScheduledEventPublishesInsteadOfAcceptingTheSdkDraftDefault() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            ScarletCalendar.EventSpec spec = eventSpec(new ScarletCalendar.EventSpec());
            spec.makeEvent(new MemoryCalendar(), f.vrc, GROUP, null, DATE);
            assertPublished(f.onlyBody(), spec);
        }
    }

    @Test void legacyScheduleWithoutDraftFieldPublishesAfterDeserialization() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            ScarletCalendar.EventSpec spec = eventSpec(Scarlet.GSON.fromJson(
                "{\"vrcCalendarEventParameters\":{\"title\":\"Saved event\"}}",
                ScarletCalendar.EventSpec.class));
            spec.makeEvent(new MemoryCalendar(), f.vrc, GROUP, null, DATE);
            assertPublished(f.onlyBody(), spec);
        }
    }

    @Test void scheduledInstanceRetainsUsDefaultForNewAndLegacyRequests() throws Exception
    {
        for (String saved : new String[]{null,
            "{\"vrcInstanceParameters\":{\"worldId\":\"" + WORLD + "\"}}",
            "{\"vrcInstanceParameters\":{\"worldId\":\"" + WORLD + "\",\"region\":null}}"})
        {
            try (Fixture f = new Fixture())
            {
                ScarletCalendar.EventSpec spec = saved == null ? new ScarletCalendar.EventSpec()
                    : Scarlet.GSON.fromJson(saved, ScarletCalendar.EventSpec.class);
                spec.vrcInstanceParameters.setWorldId(WORLD);
                ScarletCalendar.Scheduled scheduled = scheduled();
                spec.makeInstance(new MemoryCalendar(), f.vrc, scheduled);
                JsonObject body = f.onlyBody();
                assertEquals("us", body.get("region").getAsString());
                assertEquals(EVENT, body.get("calendarEntryId").getAsString());
                assertEquals(GROUP, body.get("ownerId").getAsString());
                assertEquals("group", body.get("type").getAsString());
                assertTrue(scheduled.instanceCreationAttempted);
                assertEquals(WORLD + ":synthetic", scheduled.vrchatLocation);
            }
        }
    }

    @Test void scheduledInstancePreservesEveryPreviouslySavedSupportedRegion() throws Exception
    {
        for (String region : new String[]{"us", "use", "eu", "jp"})
        {
            try (Fixture f = new Fixture())
            {
                ScarletCalendar.EventSpec spec = new ScarletCalendar.EventSpec();
                spec.vrcInstanceParameters = Scarlet.GSON.fromJson(
                    "{\"worldId\":\"" + WORLD + "\",\"region\":\"" + region + "\"}",
                    CreateInstanceRequest.class);
                spec.makeInstance(new MemoryCalendar(), f.vrc, scheduled());
                assertEquals(region, f.onlyBody().get("region").getAsString());
            }
        }
    }

    @Test void discordInstanceBuilderSendsDefaultAndSelectedRequestRegions() throws Exception
    {
        for (String region : new String[]{null, "us", "use", "eu", "jp"})
        {
            try (Fixture f = new Fixture())
            {
                ScarletDiscordJDA.InstanceCreation selection = region == null
                    ? new ScarletDiscordJDA.InstanceCreation("synthetic", WORLD, GROUP, "synthetic-owner")
                    : Scarlet.GSON.fromJson("{\"worldId\":\"" + WORLD + "\",\"groupId\":\"" + GROUP
                        + "\",\"region\":\"" + region + "\"}", ScarletDiscordJDA.InstanceCreation.class);
                f.vrc.createInstanceEx(selection.createRequestEx());
                JsonObject body = f.onlyBody();
                assertEquals(region == null ? "us" : region, body.get("region").getAsString());
                assertEquals(WORLD, body.get("worldId").getAsString());
                assertEquals(GROUP, body.get("ownerId").getAsString());
            }
        }
    }

    @Test void desktopInstanceBuilderSendsEachSelectedRequestRegion() throws Exception
    {
        Method build = ScarletUI.class.getDeclaredMethod("createInstanceRequest",
            ScarletUI.InstanceWizardSelection.class, String.class);
        build.setAccessible(true);
        for (String region : new String[]{"us", "use", "eu", "jp"})
        {
            try (Fixture f = new Fixture())
            {
                ScarletUI ui = ScarletDiscordSafetyTest.inert(ScarletUI.class);
                ScarletUI.InstanceWizardSelection selection = Scarlet.GSON.fromJson(
                    "{\"worldId\":\"" + WORLD + "\",\"region\":\"" + region + "\"}",
                    ScarletUI.InstanceWizardSelection.class);
                f.vrc.createInstanceEx((JsonObject)build.invoke(ui, selection, GROUP));
                JsonObject body = f.onlyBody();
                assertEquals(region, body.get("region").getAsString());
                assertEquals(WORLD, body.get("worldId").getAsString());
                assertEquals(GROUP, body.get("ownerId").getAsString());
            }
        }
    }

    static ScarletCalendar.EventSpec eventSpec(ScarletCalendar.EventSpec spec)
    {
        spec.id = "synthetic-event";
        spec.active = true;
        spec.time = OffsetTime.parse("12:00:00Z");
        spec.vrcCalendarEventParameters.setTitle("Synthetic published event");
        spec.vrcCalendarEventParameters.setDescription("Synthetic offline calendar test");
        spec.vrcCalendarEventParameters.setAccessType(CalendarEventAccess.PUBLIC);
        spec.vrcCalendarEventParameters.setCategory(CalendarEventCategory.OTHER);
        spec.vrcCalendarEventParameters.setSendCreationNotification(Boolean.TRUE);
        return spec;
    }

    static void assertPublished(JsonObject body, ScarletCalendar.EventSpec spec)
    {
        assertTrue(body.has("isDraft"), "Publication must not depend on an upstream default");
        assertFalse(body.get("isDraft").getAsBoolean());
        assertFalse(body.get("featured").getAsBoolean());
        assertTrue(body.get("sendCreationNotification").getAsBoolean());
        assertEquals("2026-10-12T12:00:00Z", body.get("startsAt").getAsString());
        assertEquals("2026-10-12T13:00:00Z", body.get("endsAt").getAsString());
        assertFalse(body.has("recurrence"));
        assertEquals(EVENT, spec.pending.get(DATE).vrchatEventId);
        assertTrue(spec.active);
    }

    static ScarletCalendar.Scheduled scheduled()
    {
        ScarletCalendar.Scheduled scheduled = new ScarletCalendar.Scheduled();
        scheduled.vrchatGroupId = GROUP;
        scheduled.vrchatEventId = EVENT;
        return scheduled;
    }

    static final class MemoryCalendar extends ScarletCalendar
    {
        MemoryCalendar() { super(null, null); }
        @Override public boolean load() { return true; }
        @Override public boolean save() { return true; }
    }

    static final class Fixture implements AutoCloseable
    {
        final Gson previousGson;
        final ScarletVRChat vrc;
        final ApiClient client;
        final List<JsonObject> bodies = new ArrayList<>();

        Fixture() throws Exception
        {
            Configuration.getDefaultApiClient();
            previousGson = JSON.getGson();
            OkHttpClient http = new OkHttpClient.Builder()
                .dns(host -> { throw new AssertionError("Network access forbidden in request compatibility tests"); })
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    assertEquals("compatibility.invalid", request.url().host());
                    assertEquals("POST", request.method());
                    assertNull(request.header("Authorization"));
                    assertNull(request.header("Cookie"));
                    Buffer buffer = new Buffer();
                    request.body().writeTo(buffer);
                    JsonObject body = JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
                    bodies.add(body);
                    JsonObject reply = new JsonObject();
                    if (request.url().encodedPath().equals("/api/1/calendar/" + GROUP + "/event"))
                    {
                        reply = body.deepCopy();
                        reply.addProperty("id", EVENT);
                    }
                    else
                    {
                        assertEquals("/api/1/instances", request.url().encodedPath());
                        reply.addProperty("id", WORLD + ":synthetic");
                        reply.addProperty("instanceId", "synthetic");
                        reply.addProperty("location", WORLD + ":synthetic");
                    }
                    // Do not call chain.proceed: no request can reach DNS or a server.
                    return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("Synthetic offline response")
                        .header("Content-Type", "application/json")
                        .body(ResponseBody.create(MediaType.parse("application/json"), reply.toString())).build();
                }).build();
            client = new ApiClient(http).setBasePath("https://compatibility.invalid/api/1");
            ScarletVRChat.initJson();
            vrc = ScarletDiscordSafetyTest.inert(ScarletVRChat.class);
            ScarletDiscordSafetyTest.put(vrc, "client", client);
        }

        JsonObject onlyBody()
        {
            assertEquals(1, bodies.size(), "Exactly one API request expected");
            return bodies.get(0);
        }

        @Override public void close()
        {
            JSON.setGson(previousGson);
            client.getHttpClient().connectionPool().evictAll();
            client.getHttpClient().dispatcher().executorService().shutdownNow();
        }
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.api.GroupsApi;
import io.github.vrchatapi.model.GroupAuditLogEntry;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.GroupAuditLogEntryDataGroupInstanceAnnouncement;
import io.github.vrchatapi.model.GroupAuditLogEntryDataGroupRoleCreate;
import io.github.vrchatapi.model.GroupAuditLogEntryGroupRoleCreate;
import io.github.vrchatapi.model.PaginatedGroupAuditLogEntryList;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

class ScarletAuditDataCompatibilityTest
{
    private Gson original;
    @TempDir Path temp;

    @BeforeEach void setUp()
    {
        original = JSON.getGson();
        new ApiClient();
        ScarletVRChat.initJson();
    }

    @AfterEach void tearDown()
    {
        JSON.setGson(original);
    }

    @Test void sdkDefaultHeaderAndPackagedVersionMatchTheUpgrade() throws Exception
    {
        URL applicationClass = Scarlet.class.getResource("Scarlet.class");
        assertNotNull(applicationClass);
        // Maven can run this class before packaging; packaged verification must assert
        // the real loaded artifact's manifest and the origin of its SDK classes.
        if ("jar".equals(applicationClass.getProtocol()))
        {
            JarURLConnection connection = (JarURLConnection)applicationClass.openConnection();
            connection.setUseCaches(false);
            try (java.util.jar.JarFile jar = connection.getJarFile())
            {
                assertEquals("1.23.0", jar.getManifest().getMainAttributes().getValue("Scarlet-VRChatApi-Version"));
            }
            assertEquals(Scarlet.class.getProtectionDomain().getCodeSource().getLocation(),
                ApiClient.class.getProtectionDomain().getCodeSource().getLocation());
        }
        ApiClient client = new ApiClient(new OkHttpClient.Builder()
            .dns(host -> { throw new AssertionError("No network or DNS permitted"); }).build())
            .setBasePath("https://offline.invalid/api/1");
        okhttp3.Request request = new GroupsApi(client).getGroupAuditLogsCall(
            "grp_synthetic_group", 1, 0, null, null, null, null, null, null).request();
        // Upstream 1.23 deliberately supplies an unversioned default header.
        assertEquals("vrchatapi-java", request.header("User-Agent"));
    }

    private static final String[][] PAYLOADS = {
        {"group.member.user.update", "{\"managerNotes\":{\"old\":null,\"new\":\"synthetic note\"}}"},
        {"group.role.create", "{\"name\":\"Synthetic\",\"isAddedOnJoin\":false,\"isSelfAssignable\":true,\"order\":1,\"permissions\":[\"group-audit-view\"]}"},
        {"group.role.delete", "{\"name\":\"Synthetic\",\"isAddedOnJoin\":true,\"order\":2,\"permissions\":[]}"},
        {"group.role.update", "{\"name\":{\"old\":\"Before\",\"new\":\"After\"},\"isAddedOnJoin\":{\"old\":false,\"new\":true},\"order\":{\"old\":1,\"new\":2},\"permissions\":{\"old\":[],\"new\":[\"group-audit-view\"]}}"},
        {"group.update", "{\"description\":{\"old\":null,\"new\":\"Synthetic\"},\"tags\":{\"old\":[],\"new\":[\"synthetic\"]},\"unknownFutureSetting\":{\"old\":1,\"new\":2}}"},
        {"group.post.create", "{\"title\":\"Synthetic\",\"text\":\"Text\",\"visibility\":\"public\",\"futureField\":{\"flag\":true,\"nullValue\":null}}"},
        {"group.announcement", "{\"title\":\"Synthetic\",\"text\":\"Announcement body\",\"sendNotification\":false}"},
        {"group.instance.announcement", "{\"title\":\"Synthetic\",\"message\":\"Instance message\"}"},
        {"group.calendarEvent.create", "{\"title\":\"Synthetic\",\"accessType\":\"group\",\"recurrence\":null,\"platforms\":[\"standalonewindows\"]}"},
        {"group.calendarEvent.delete", "{\"title\":\"Synthetic\",\"occurrenceKind\":\"single\",\"futureCalendarField\":17}"},
        {"group.future.synthetic", "{\"future\":[null,true,3,{\"nested\":null}],\"exactInteger\":9007199254740993}"},
        {"group.instance.warn", "{}"}
    };

    private static JsonObject entry(String eventType, String payload)
    {
        JsonObject result = new JsonObject();
        result.addProperty("actorDisplayName", "Synthetic actor");
        result.addProperty("actorId", "usr_synthetic_actor");
        result.addProperty("created_at", "2030-01-01T12:00:00Z");
        result.addProperty("description", "Synthetic audit event");
        result.addProperty("eventType", eventType);
        result.addProperty("groupId", "grp_synthetic_group");
        result.addProperty("id", "gaud_synthetic");
        result.addProperty("targetId", "usr_synthetic_target");
        result.add("data", JsonParser.parseString(payload));
        return result;
    }

    @TestFactory Stream<DynamicTest> payloadsRoundTripWithoutUnknownLossOrInventedDefaults()
    {
        return Arrays.stream(PAYLOADS).flatMap(payload -> Stream.of(
            DynamicTest.dynamicTest(payload[0] + " client", () -> roundTrip(JSON.getGson(), payload)),
            DynamicTest.dynamicTest(payload[0] + " local", () -> roundTrip(Scarlet.GSON, payload)),
            DynamicTest.dynamicTest(payload[0] + " pretty", () -> roundTrip(Scarlet.GSON_PRETTY, payload))));
    }

    private static void roundTrip(Gson gson, String[] payload)
    {
        JsonObject input = entry(payload[0], payload[1]);
        GroupAuditLogEntryUnknown parsed = gson.fromJson(input, GroupAuditLogEntryUnknown.class);
        assertInstanceOf(ScarletAuditData.class, parsed.getData());
        assertEquals(input, JsonParser.parseString(gson.toJson(parsed, GroupAuditLogEntryUnknown.class)));
        assertEquals(input.get("data"), ScarletAuditData.json(parsed.getData()));
        ScarletData.AuditEntryMetadata meta = new ScarletData.AuditEntryMetadata();
        meta.entry = parsed;
        String saved = gson.toJson(meta);
        ScarletData.AuditEntryMetadata restored = gson.fromJson(saved, ScarletData.AuditEntryMetadata.class);
        assertEquals(input.get("data"), restored.getData(JsonObject.class));
        assertEquals(!input.getAsJsonObject("data").entrySet().isEmpty(), restored.hasNonEmptyData());
    }

    @TestFactory Stream<DynamicTest> everySdkUnionEventKeepsRawDataAcrossTheBoundary()
    {
        return GroupAuditLogEntry.schemas.values().stream()
            .flatMap(schema -> Arrays.stream(schema.getDeclaredClasses()))
            .filter(type -> type.isEnum() && "EventTypeEnum".equals(type.getSimpleName()))
            .flatMap(type -> Arrays.stream(type.getEnumConstants()))
            .map(Object::toString)
            .distinct()
            .flatMap(event -> Stream.of(
                DynamicTest.dynamicTest(event + " union client", () -> unionRoundTrip(JSON.getGson(), event)),
                DynamicTest.dynamicTest(event + " union local", () -> unionRoundTrip(Scarlet.GSON, event))));
    }

    private static void unionRoundTrip(Gson gson, String event)
    {
        // Synthetic future data is intentional: the SDK branch must not narrow received data.
        JsonObject input = entry(event, "{\"future\":null,\"exactInteger\":9007199254740993}");
        GroupAuditLogEntry parsed = gson.fromJson(input, GroupAuditLogEntry.class);
        GroupAuditLogEntryUnknown normalized = ScarletAuditData.normalize(parsed);
        assertEquals(event, normalized.getEventType());
        assertInstanceOf(ScarletAuditData.class, normalized.getData());
        assertEquals(input, JsonParser.parseString(gson.toJson(parsed, GroupAuditLogEntry.class)));
    }

    @Test void typedSdkEntriesNormalizeWithoutRequiringTheUnknownBranch()
    {
        GroupAuditLogEntryGroupRoleCreate typed = new GroupAuditLogEntryGroupRoleCreate()
            .id("gaud_typed_synthetic")
            .eventType(GroupAuditLogEntryGroupRoleCreate.EventTypeEnum.GROUP_ROLE_CREATE)
            .data(new GroupAuditLogEntryDataGroupRoleCreate().name("Synthetic").isAddedOnJoin(true).order(2));
        GroupAuditLogEntry union = new GroupAuditLogEntry(typed);
        GroupAuditLogEntryUnknown normalized = ScarletAuditData.normalize(union);
        assertEquals("gaud_typed_synthetic", normalized.getId());
        assertEquals("group.role.create", normalized.getEventType());
        JsonObject data = ScarletAuditData.json(normalized.getData());
        assertEquals("Synthetic", data.get("name").getAsString());
        assertTrue(data.get("isAddedOnJoin").getAsBoolean());
        assertEquals(2, data.get("order").getAsInt());
        assertTrue(data.has("description"));
        assertTrue(data.get("description").isJsonNull());
        assertEquals(data, ScarletAuditData.json(ScarletAuditData.entries(
            new PaginatedGroupAuditLogEntryList().results(Arrays.asList(union))).get(0).getData()));
        assertEquals("group.role.create", JsonParser.parseString(JSON.getGson().toJson(union))
            .getAsJsonObject().get("eventType").getAsString());
    }

    @Test void unknownEnvelopeFieldsSurviveMetadataCopyAndKnownFieldEdits()
    {
        JsonObject input = entry("group.future.synthetic", "{\"unknown\":null}");
        input.add("futureEnvelope", JsonParser.parseString("{\"number\":9007199254740993,\"nullValue\":null}"));
        for (Gson gson : Arrays.asList(JSON.getGson(), Scarlet.GSON, Scarlet.GSON_PRETTY))
        {
            ScarletData.AuditEntryMetadata metadata = new ScarletData.AuditEntryMetadata();
            metadata.entry = gson.fromJson(input, GroupAuditLogEntryUnknown.class);
            metadata.setAuxData("deliveryState", "pending");
            metadata.entry.setDescription("Edited synthetic description");
            ScarletData.AuditEntryMetadata restored = gson.fromJson(gson.toJson(metadata), ScarletData.AuditEntryMetadata.class);
            assertEquals("Edited synthetic description", restored.entry.getDescription());
            assertEquals("pending", restored.getAuxData("deliveryState").getAsString());
            JsonObject result = JsonParser.parseString(gson.toJson(restored.entry)).getAsJsonObject();
            assertEquals(input.get("futureEnvelope"), result.get("futureEnvelope"));
            assertEquals(input.get("data"), result.get("data"));
        }
    }

    @Test void updateComponentsReceiveOnlyRealChanges()
    {
        for (String[] fixture : PAYLOADS)
        {
            if (!fixture[0].endsWith(".update")) continue;
            ScarletData.AuditEntryMetadata meta = new ScarletData.AuditEntryMetadata();
            meta.entry = JSON.getGson().fromJson(entry(fixture[0], fixture[1]), GroupAuditLogEntryUnknown.class);
            Map<String, GroupAuditType.UpdateSubComponent> changes = meta.getData(GroupAuditType.UpdateSubComponent.TYPE_TOKEN);
            assertEquals(JsonParser.parseString(fixture[1]).getAsJsonObject().keySet(), changes.keySet());
            assertFalse(changes.containsKey("accessType"));
            assertFalse(changes.containsKey("occurrenceKind"));
            assertFalse(changes.containsKey("platforms"));
            assertFalse(changes.containsKey("groupAccessType"));
        }
    }

    @Test void mapAndNamedAccessorsKeepPrimitiveAndNestedValues()
    {
        ScarletData.AuditEntryMetadata meta = new ScarletData.AuditEntryMetadata();
        meta.entry = JSON.getGson().fromJson(entry("group.future.synthetic",
            "{\"name\":\"Synthetic\",\"enabled\":true,\"nested\":{\"value\":null}}"), GroupAuditLogEntryUnknown.class);
        assertEquals("Synthetic", meta.<String>getData("name"));
        assertTrue(meta.<Boolean>getData("enabled"));
        assertTrue(meta.getData().containsKey("nested"));
        assertTrue(meta.<Map<String, Object>>getData("nested").containsKey("value"));
    }

    @Test void actualSdkPageUsesAdapterEvenWhenNestedGeneratedValidationWouldRejectPayload() throws Exception
    {
        JsonObject input = entry("group.future.synthetic", "{\"futureField\":{\"value\":null}}" );
        JsonObject body = new JsonObject();
        com.google.gson.JsonArray results = new com.google.gson.JsonArray();
        results.add(input);
        body.add("results", results);
        body.addProperty("totalCount", 1);
        body.addProperty("hasNext", false);
        OkHttpClient http = new OkHttpClient.Builder()
            .dns(host -> { throw new AssertionError("No network or DNS permitted"); })
            .addInterceptor(chain -> {
                assertEquals("/api/1/groups/grp_synthetic_group/auditLogs", chain.request().url().encodedPath());
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Synthetic").body(ResponseBody.create(body.toString(), MediaType.get("application/json"))).build();
            }).build();
        ApiClient client = new ApiClient(http).setBasePath("https://offline.invalid/api/1");
        ScarletVRChat.initJson();
        PaginatedGroupAuditLogEntryList page = new GroupsApi(client).getGroupAuditLogs(
            "grp_synthetic_group", 100, 0, null, null, null, null, null);
        assertEquals(1, page.getResults().size());
        assertInstanceOf(GroupAuditLogEntryUnknown.class, page.getResults().get(0).getActualInstance());
        assertEquals(input.get("data"), ScarletAuditData.json(ScarletAuditData.normalize(page.getResults().get(0)).getData()));
        assertEquals(body, JsonParser.parseString(JSON.getGson().toJson(page)));
    }

    @Test void validNestedPagesAlsoAvoidGeneratedDefaults()
    {
        JsonObject input = entry("group.instance.warn", "{}");
        String pageJson = "{\"results\":[" + input + "],\"totalCount\":1,\"hasNext\":false}";
        PaginatedGroupAuditLogEntryList page = JSON.getGson().fromJson(pageJson, PaginatedGroupAuditLogEntryList.class);
        assertEquals(new JsonObject(), ScarletAuditData.json(ScarletAuditData.normalize(page.getResults().get(0)).getData()));
        Type listType = new TypeToken<List<GroupAuditLogEntryUnknown>>(){}.getType();
        List<GroupAuditLogEntryUnknown> list = JSON.getGson().fromJson("[" + input + "]", listType);
        assertEquals(new JsonObject(), ScarletAuditData.json(list.get(0).getData()));
    }

    @Test void rawDataIsCopiedAndEqualityReflectsPayload()
    {
        JsonObject input = JsonParser.parseString("{\"future\":1}").getAsJsonObject();
        ScarletAuditData data = ScarletAuditData.fromJson(input);
        input.addProperty("future", 2);
        JsonObject exported = ScarletAuditData.json(data);
        exported.addProperty("future", 3);
        assertEquals(1, ScarletAuditData.json(data).get("future").getAsInt());
        assertEquals(data, ScarletAuditData.fromJson(ScarletAuditData.json(data)));
        assertNotEquals(data, ScarletAuditData.fromJson(input));
    }

    @Test void rawDataMapViewPreservesLargeNumbersAndMapEquality()
    {
        ScarletAuditData data = ScarletAuditData.fromJson(JsonParser.parseString("{\"exactInteger\":9007199254740993}").getAsJsonObject());
        Map<String, Object> ordinary = java.util.Collections.singletonMap("exactInteger", new BigDecimal("9007199254740993"));
        assertEquals(new BigDecimal("9007199254740993"), data.get("exactInteger"));
        assertEquals(ordinary, data);
        assertEquals(data, ordinary);
        assertEquals(ordinary.hashCode(), data.hashCode());
        assertThrows(UnsupportedOperationException.class, () -> data.put("changed", true));
    }

    @Test void standaloneRuntimeAndDeclaredDataSerializationKeepOnlyRawFields()
    {
        ScarletAuditData data = ScarletAuditData.fromJson(JsonParser.parseString("{\"future\":null}").getAsJsonObject());
        for (Gson gson : Arrays.asList(JSON.getGson(), Scarlet.GSON, Scarlet.GSON_PRETTY))
        {
            assertEquals("{\"future\":null}", gson.toJson(data).replaceAll("\\s", ""));
            assertEquals("{\"future\":null}", gson.toJson(data, ScarletAuditData.class).replaceAll("\\s", ""));
        }
    }

    @Test void nullDataRemainsAbsentAndMalformedDataIsRejected()
    {
        for (Gson gson : Arrays.asList(JSON.getGson(), Scarlet.GSON, Scarlet.GSON_PRETTY))
        {
            ScarletData.AuditEntryMetadata meta = new ScarletData.AuditEntryMetadata();
            meta.entry = gson.fromJson(entry("group.instance.warn", "null"), GroupAuditLogEntryUnknown.class);
            assertNull(meta.getData());
            assertNull(meta.getData(JsonObject.class));
            assertFalse(meta.hasNonEmptyData());
            for (String invalid : Arrays.asList("[]", "true", "7", "\"text\""))
                assertThrows(RuntimeException.class, () -> gson.fromJson(entry("group.future.synthetic", invalid), GroupAuditLogEntryUnknown.class));
        }
    }

    @Test void explicitlyConstructedSdkDataStillSerializesWithoutAdapterRecursion()
    {
        GroupAuditLogEntryDataGroupInstanceAnnouncement data = new GroupAuditLogEntryDataGroupInstanceAnnouncement().message("Synthetic message");
        assertEquals("Synthetic message", ScarletAuditData.json(data).get("message").getAsString());
        for (Gson gson : Arrays.asList(JSON.getGson(), Scarlet.GSON, Scarlet.GSON_PRETTY))
        {
            JsonObject serialized = JsonParser.parseString(gson.toJson(data, GroupAuditLogEntryDataGroupInstanceAnnouncement.class)).getAsJsonObject();
            assertEquals("Synthetic message", serialized.get("message").getAsString());
        }
    }

    @Test void missingAndNullEnvelopeFieldsStayNullThroughPersistenceAndCopy()
    {
        for (Gson gson : Arrays.asList(JSON.getGson(), Scarlet.GSON, Scarlet.GSON_PRETTY))
        {
            for (String input : Arrays.asList("{\"id\":\"gaud_synthetic\"}",
                "{\"id\":\"gaud_synthetic\",\"actorId\":null,\"created_at\":null,\"data\":null,\"targetId\":null}"))
            {
                GroupAuditLogEntryUnknown parsed = gson.fromJson(input, GroupAuditLogEntryUnknown.class);
                assertNull(parsed.getActorId());
                assertNull(parsed.getActorDisplayName());
                assertNull(parsed.getCreatedAt());
                assertNull(parsed.getData());
                assertNull(parsed.getTargetId());
                JsonObject serialized = JsonParser.parseString(gson.toJson(parsed)).getAsJsonObject();
                assertEquals(JsonParser.parseString("{\"id\":\"gaud_synthetic\"}"), serialized);
                assertEquals(parsed, gson.fromJson(serialized, GroupAuditLogEntryUnknown.class));
            }
        }
    }

    @Test void historicalMetadataStorageDoesNotDependOnMutableSdkValidation()
    {
        // Simulate the SDK global Gson being initialized/reset before Scarlet's client.
        JSON.setGson(JSON.getGson().newBuilder()
            .registerTypeAdapterFactory(new GroupAuditLogEntryUnknown.CustomTypeAdapterFactory()).create());
        ScarletData store = new ScarletData(temp.toFile());
        ScarletData.AuditEntryMetadata meta = new ScarletData.AuditEntryMetadata();
        meta.entry = new GroupAuditLogEntryUnknown().id("gaud_synthetic");
        meta.entry.setData(ScarletAuditData.fromJson(JsonParser.parseString("{\"unknownField\":null}").getAsJsonObject()));
        store.auditEntryMetadata("gaud_synthetic", meta);
        ScarletData.AuditEntryMetadata restored = store.auditEntryMetadata("gaud_synthetic");
        assertNotNull(restored);
        assertEquals(meta.entry, restored.entry);
        assertNull(restored.entry.getActorId());
        assertEquals(JsonParser.parseString("{\"unknownField\":null}"), restored.getData(JsonObject.class));
        store.auditEntryMetadata("gaud_synthetic", value -> {
            value.entry.setDescription("Synthetic edited description");
            return value;
        });
        assertEquals("Synthetic edited description", store.auditEntryMetadata("gaud_synthetic").entry.getDescription());
    }

    @Test void historicalOutboxRoundTripsAndFinishesUnderStrictSdkReset() throws Exception
    {
        JSON.setGson(JSON.getGson().newBuilder()
            .registerTypeAdapterFactory(new GroupAuditLogEntryUnknown.CustomTypeAdapterFactory()).create());
        ScarletData store = new ScarletData(temp.toFile());
        ScarletData.AuditEntryMetadata meta = new ScarletData.AuditEntryMetadata();
        meta.entry = new GroupAuditLogEntryUnknown().id("gaud_synthetic");
        JsonObject raw = JsonParser.parseString("{\"unknownField\":null,\"nested\":{\"value\":null}}").getAsJsonObject();
        meta.entry.setData(ScarletAuditData.fromJson(raw));
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(temp.toFile());
        outbox.save(meta);
        ScarletData.AuditEntryMetadata restored = new ScarletAuditOutbox(temp.toFile()).read("gaud_synthetic");
        assertEquals(raw, restored.getData(JsonObject.class));
        assertNull(restored.entry.getCreatedAt());
        restored.setAuxData("deliveryState", "delivered");
        Scarlet scarlet = ScarletDiscordSafetyTest.inert(Scarlet.class);
        ScarletDiscordSafetyTest.put(scarlet, "data", store);
        outbox.finish(scarlet, restored);
        assertFalse(outbox.exists("gaud_synthetic"));
        assertTrue(ScarletAuditOutbox.complete(store.auditEntryMetadata("gaud_synthetic")));
        assertEquals(raw, store.auditEntryMetadata("gaud_synthetic").getData(JsonObject.class));
    }

    @Test void nestedAuditPagesAndListsKeepNullDataWithoutAddingNullEnvelopeFields()
    {
        JsonObject input = entry("group.future.synthetic", "{\"nullable\":null,\"nested\":{\"value\":null}}");
        input.remove("description");
        GroupAuditLogEntryUnknown parsed = JSON.getGson().fromJson(input, GroupAuditLogEntryUnknown.class);
        Type listType = new TypeToken<List<GroupAuditLogEntryUnknown>>(){}.getType();
        JsonElement serializedList = JsonParser.parseString(JSON.getGson().toJson(Arrays.asList(parsed, null), listType));
        assertEquals(input, serializedList.getAsJsonArray().get(0));
        assertTrue(serializedList.getAsJsonArray().get(1).isJsonNull());
        PaginatedGroupAuditLogEntryList page = new PaginatedGroupAuditLogEntryList().results(Arrays.asList(new GroupAuditLogEntry(parsed)));
        JsonObject serializedPage = JsonParser.parseString(JSON.getGson().toJson(page)).getAsJsonObject();
        assertEquals(input, serializedPage.getAsJsonArray("results").get(0));
        assertFalse(serializedPage.has("hasNext"));
        assertFalse(serializedPage.has("totalCount"));
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.model.Instance;
import io.github.vrchatapi.model.LimitedUserInstance;
import io.github.vrchatapi.model.User;

/** Synthetic JSON only; no live account, instance, network request, or closure. */
class ScarletInstanceUserDateTest
{
    private Gson previous;

    @BeforeEach void installActualSdkAndScarletAdapters()
    {
        previous = JSON.getGson();
        new ApiClient(); // Installs SDK model/date adapters without making a request.
        ScarletVRChat.initJson();
    }

    @AfterEach void restoreAdapters()
    {
        JSON.setGson(previous);
    }

    @Test void observedDateOnlyUserInInstanceResponseIsReadable()
    {
        Instance response = readInstance(user("2017-10-17"));
        assertEquals(OffsetDateTime.parse("2017-10-17T00:00:00Z"),
            response.getUsers().get(0).getDateJoined());
        assertEquals(OffsetDateTime.parse("2026-10-15T18:30:00Z"), response.getClosedAt());
    }

    @Test void generatedLimitedUserAdapterAlsoAcceptsTheDateOnlyForm() throws Exception
    {
        JsonObject body = user("2017-10-17");
        LimitedUserInstance.validateJsonElement(body);
        LimitedUserInstance parsed = JSON.getGson().fromJson(body, LimitedUserInstance.class);
        assertEquals(OffsetDateTime.parse("2017-10-17T00:00:00Z"), parsed.getDateJoined());
    }

    @Test void enclosingInstanceFallbackKeepsTheNestedNormalization()
    {
        JsonObject body = instance(user("2017-10-17"));
        body.addProperty("syntheticFutureField", true);
        assertThrows(Exception.class, () -> Instance.validateJsonElement(body));
        Instance parsed = JSON.getGson().fromJson(body, Instance.class);
        assertEquals(OffsetDateTime.parse("2017-10-17T00:00:00Z"), parsed.getUsers().get(0).getDateJoined());
    }

    @Test void listOfInstanceUsersUsesTheScopedAdapter()
    {
        JsonArray users = new JsonArray();
        users.add(user("2017-10-17"));
        List<LimitedUserInstance> parsed = JSON.getGson().fromJson(users,
            new TypeToken<List<LimitedUserInstance>>(){}.getType());
        assertEquals(OffsetDateTime.parse("2017-10-17T00:00:00Z"), parsed.get(0).getDateJoined());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-02-29", "2000-02-29", "2017-01-01", "2017-12-31"})
    void validCalendarDatesKeepTheirCalendarDay(String value)
    {
        OffsetDateTime parsed = readInstance(user(value)).getUsers().get(0).getDateJoined();
        assertEquals(LocalDate.parse(value), parsed.toLocalDate());
        assertEquals(0, parsed.getOffset().getTotalSeconds());
        assertEquals(0, parsed.toLocalTime().toSecondOfDay());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2017-10-17T12:34:56Z", "2017-10-17T12:34:56.123456+05:30", "2017-10-17T23:59:59-07:00"})
    void fullTimestampAndOffsetArePreserved(String value)
    {
        assertEquals(OffsetDateTime.parse(value), readInstance(user(value)).getUsers().get(0).getDateJoined());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2017-02-29", "1900-02-29", "2024-02-30", "2017-13-17", "2017-10-32", "2017-00-17", "2017-10-00"})
    void impossibleDatesAreRejectedWithoutFallbackRepair(String value)
    {
        assertThrows(RuntimeException.class, () -> readInstance(user(value)));
        assertThrows(RuntimeException.class, () -> JSON.getGson().fromJson(user(value), LimitedUserInstance.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2017-1-17", "17-10-2017", "2017-10-17T", "2017-10-17T12:00:00", " 2017-10-17", "2017-10-17 "})
    void malformedOrMissingOffsetDatesRemainInvalid(String value)
    {
        assertThrows(RuntimeException.class, () -> readInstance(user(value)));
    }

    @Test void nullAndMissingJoinDatesRemainNull()
    {
        JsonObject body = user("2017-10-17");
        body.add("date_joined", JsonNull.INSTANCE);
        assertNull(readInstance(body).getUsers().get(0).getDateJoined());
        body.remove("date_joined");
        assertNull(readInstance(body).getUsers().get(0).getDateJoined());
    }

    @ParameterizedTest
    @ValueSource(strings = {"20171017", "true", "[\"2017-10-17\"]", "{\"date\":\"2017-10-17\"}"})
    void nonStringJoinDatesAreRejected(String value)
    {
        JsonObject body = user("2017-10-17");
        body.add("date_joined", JsonParser.parseString(value));
        assertThrows(RuntimeException.class, () -> readInstance(body));
        assertThrows(RuntimeException.class, () -> JSON.getGson().fromJson(body, LimitedUserInstance.class));
    }

    @Test void unrelatedUserModelKeepsItsActualLocalDateType()
    {
        JsonObject body = new JsonObject();
        body.addProperty("date_joined", "2017-10-17");
        User parsed = JSON.getGson().fromJson(body, User.class);
        assertEquals(LocalDate.of(2017, 10, 17), parsed.getDateJoined());
    }

    @Test void dateOnlyClosedAtIsNotAcceptedAsAClosureTime()
    {
        JsonObject body = instance(user("2017-10-17"));
        body.addProperty("closedAt", "2026-10-15");
        assertThrows(RuntimeException.class, () -> JSON.getGson().fromJson(body, Instance.class));
    }

    @Test void otherInstanceUserTimestampFieldsStillRequireTimeAndOffset()
    {
        for (String field : new String[]{"last_activity", "last_mobile"})
        {
            JsonObject body = user("2017-10-17");
            body.addProperty(field, "2026-10-15");
            assertThrows(RuntimeException.class, () -> readInstance(body));
        }
    }

    @Test void genericDateTimeAdapterIsNotRelaxed()
    {
        assertThrows(RuntimeException.class, () -> JSON.getGson().fromJson("\"2017-10-17\"", OffsetDateTime.class));
    }

    @Test void genericJsonWithSameFieldNameIsNotRewritten()
    {
        JsonObject body = user("2017-10-17");
        JsonObject parsed = JSON.getGson().fromJson(body, JsonObject.class);
        assertEquals("2017-10-17", parsed.get("date_joined").getAsString());
    }

    private static Instance readInstance(JsonObject user)
    {
        return JSON.getGson().fromJson(instance(user), Instance.class);
    }

    private static JsonObject instance(JsonObject user)
    {
        // Minimal optional response intentionally exercises Scarlet's existing
        // fallback if the generated SDK expects fields the server does not send.
        JsonObject body = new JsonObject();
        body.addProperty("closedAt", "2026-10-15T18:30:00Z");
        JsonArray users = new JsonArray();
        users.add(user);
        body.add("users", users);
        return body;
    }

    private static JsonObject user(String joined)
    {
        JsonObject body = new JsonObject();
        body.addProperty("ageVerificationStatus", "hidden");
        body.addProperty("ageVerified", false);
        body.addProperty("allowAvatarCopying", false);
        body.addProperty("date_joined", joined);
        body.addProperty("developerType", "none");
        body.addProperty("displayName", "Synthetic user");
        body.addProperty("friendKey", "");
        body.addProperty("id", "usr_10000000-0000-0000-0000-000000000001");
        body.addProperty("isFriend", false);
        body.add("last_activity", JsonNull.INSTANCE);
        body.addProperty("last_platform", "standalonewindows");
        body.addProperty("pronouns", "");
        body.addProperty("status", "offline");
        body.addProperty("statusDescription", "");
        body.add("tags", new JsonArray());
        return body;
    }
}

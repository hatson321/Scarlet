package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.vrchatapi.api.AuthenticationApi;
import io.github.vrchatapi.model.CurrentUser;
import io.github.vrchatapi.model.PublicProfile;
import io.github.vrchatapi.model.User;

/** Offline checks for scalar fields that SDK 1.23 now represents as unions or enums. */
class ScarletVRChatApi123CompatibilityTest
{
    @TempDir Path temp;

    @Test void legacyUserCacheRetainsEmptyAndDatedActivityScalars()
    {
        for (String activity : new String[]{"", "2026-10-11T12:34:56Z"})
        {
            JsonObject legacy = publicUser(activity);
            User restored = Scarlet.GSON.fromJson(legacy, User.class);
            JsonObject saved = Scarlet.GSON.toJsonTree(restored).getAsJsonObject();
            assertEquals(ScarletVRChatApi122CompatibilityTest.USER, restored.getId());
            assertScalar(saved, "last_login", activity);
            assertScalar(saved, "last_activity", activity);
            assertScalar(saved, "last_mobile", "");
            assertNotNull(restored.getLastLogin());
            assertNotNull(restored.getLastActivity());
        }
    }

    @Test void fetchedPublicUserKeepsActivityScalarsInPersistentCache() throws Exception
    {
        for (String activity : new String[]{"", "2026-10-11T12:34:56Z"})
            try (ScarletVRChatApi122CompatibilityTest.Fixture f = fixture())
            {
                f.reply(publicUser(activity).toString());
                User user = f.vrc.getUser(ScarletVRChatApi122CompatibilityTest.USER);
                assertNotNull(user);
                assertScalar(f.cachedJson(), "last_login", activity);
                assertScalar(f.cachedJson(), "last_activity", activity);
                User restored = Scarlet.GSON.fromJson(f.cachedJson(), User.class);
                assertScalar(Scarlet.GSON.toJsonTree(restored).getAsJsonObject(), "last_login", activity);
                f.assertGet(0, "/users/" + ScarletVRChatApi122CompatibilityTest.USER);
            }
    }

    @Test void selfLookupRetainsPublicActivityWithoutPrivateAccountFields() throws Exception
    {
        try (ScarletVRChatApi122CompatibilityTest.Fixture f = fixture())
        {
            f.reply(ScarletVRChatApi122CompatibilityTest.currentUser("[]"));
            User user = f.vrc.getUserFresh(ScarletVRChatApi122CompatibilityTest.USER);
            ScarletVRChatApi122CompatibilityTest.assertPublicProjection(f, user);
            assertScalar(f.cachedJson(), "last_login", "2026-10-10T12:00:00Z");
        }
    }

    @Test void knownBannerTypesKeepUserAndLoginResponsesReadable() throws Exception
    {
        for (String banner : new String[]{"avatarBanner", "color", "customImage"})
            try (ScarletVRChatApi122CompatibilityTest.Fixture f = fixture())
            {
                JsonObject userBody = publicUser("");
                userBody.addProperty("bannerType", banner);
                f.reply(userBody.toString());
                User user = f.vrc.getUser(ScarletVRChatApi122CompatibilityTest.USER);
                assertNotNull(user);
                assertEquals(banner, user.getBannerType().getValue());
                assertScalar(f.cachedJson(), "bannerType", banner);

                JsonObject loginBody = JsonParser.parseString(
                    ScarletVRChatApi122CompatibilityTest.currentUser("[]")).getAsJsonObject();
                loginBody.addProperty("bannerType", banner);
                f.reply(loginBody.toString());
                CurrentUser current = f.vrc.getCurrentUser(new AuthenticationApi(f.client));
                assertEquals(ScarletVRChatApi122CompatibilityTest.USER, current.getId());
                assertEquals(banner, current.getBannerType().getValue());
            }
    }

    @Test void futureBannerValueDoesNotDiscardTheUserLookup() throws Exception
    {
        try (ScarletVRChatApi122CompatibilityTest.Fixture f = fixture())
        {
            JsonObject body = publicUser("");
            body.addProperty("bannerType", "syntheticFutureBanner123");
            f.reply(body.toString());
            User user = f.vrc.getUser(ScarletVRChatApi122CompatibilityTest.USER);
            assertNotNull(user);
            assertEquals("Synthetic user", user.getDisplayName());
            assertNull(user.getBannerType(), "Unknown optional decoration is ignored, not the user");
            assertEquals(ScarletVRChatApi122CompatibilityTest.USER, f.cachedJson().get("id").getAsString());
            User restored = Scarlet.GSON.fromJson(body, User.class);
            assertEquals(user.getId(), restored.getId());
            assertNull(restored.getBannerType());

            JsonObject login = JsonParser.parseString(
                ScarletVRChatApi122CompatibilityTest.currentUser("[]")).getAsJsonObject();
            login.addProperty("bannerType", "syntheticFutureBanner123");
            f.reply(login.toString());
            CurrentUser current = f.vrc.getCurrentUser(new AuthenticationApi(f.client));
            assertEquals(user.getId(), current.getId());
            assertNull(current.getBannerType());
        }
    }

    @Test void legacyPublicProfileThemeRemainsAScalarInCache()
    {
        for (String theme : new String[]{"", "synthetic-theme"})
        {
            JsonObject legacy = new JsonObject();
            legacy.addProperty("id", ScarletVRChatApi122CompatibilityTest.USER);
            legacy.addProperty("bio", "Synthetic public biography");
            legacy.addProperty("themeId", theme);
            legacy.addProperty("bannerType", "color");
            PublicProfile profile = Scarlet.GSON.fromJson(legacy, PublicProfile.class);
            assertEquals("Synthetic public biography", profile.getBio());
            JsonObject saved = Scarlet.GSON.toJsonTree(profile).getAsJsonObject();
            assertScalar(saved, "themeId", theme);
            assertScalar(saved, "bannerType", "color");
            assertEquals(theme, profile.getThemeId().getActualInstance());
        }
    }

    @Test void searchUsersPreservesExistingQueryAndPaginationWithNewOptionalArguments() throws Exception
    {
        try (ScarletVRChatApi122CompatibilityTest.Fixture f = fixture())
        {
            f.reply("[]");
            assertTrue(f.vrc.searchUsers("Synthetic + name", 25, 10).isEmpty());
            f.assertGet(0, "/users");
            assertEquals("Synthetic + name", f.requests.get(0).url().queryParameter("search"));
            assertEquals("25", f.requests.get(0).url().queryParameter("n"));
            assertEquals("10", f.requests.get(0).url().queryParameter("offset"));
            assertNull(f.requests.get(0).url().queryParameter("sort"));
            assertNull(f.requests.get(0).url().queryParameter("customFields"));
        }
    }

    private ScarletVRChatApi122CompatibilityTest.Fixture fixture() throws Exception
    {
        return new ScarletVRChatApi122CompatibilityTest.Fixture(Files.createTempDirectory(temp, "api123-"));
    }

    private static JsonObject publicUser(String activity)
    {
        JsonObject user = JsonParser.parseString(ScarletVRChatApi122CompatibilityTest.publicUser()).getAsJsonObject();
        user.addProperty("last_login", activity);
        user.addProperty("last_activity", activity);
        return user;
    }

    private static void assertScalar(JsonObject object, String key, String expected)
    {
        assertTrue(object.has(key), key + " must be retained");
        assertTrue(object.get(key).isJsonPrimitive(), key + " must not become a generated Java wrapper object");
        assertEquals(expected, object.get(key).getAsString());
    }
}

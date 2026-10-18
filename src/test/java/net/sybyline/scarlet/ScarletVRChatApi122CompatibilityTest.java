package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.Configuration;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.api.AuthenticationApi;
import io.github.vrchatapi.model.CurrentUser;
import io.github.vrchatapi.model.GroupGalleryImage;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupPost;
import io.github.vrchatapi.model.User;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Actual Scarlet adapters, synthetic HTTP only, and an isolated on-disk user cache. */
class ScarletVRChatApi122CompatibilityTest
{
    static final String USER = "usr_10000000-0000-0000-0000-000000000001";
    static final String GROUP = "grp_10000000-0000-0000-0000-000000000002";
    static final String GALLERY = "ggal_10000000-0000-0000-0000-000000000003";
    @TempDir Path temp;

    @Test void successfulAuthenticationStillUsesCurrentUserAndNormalizesArrayAvatarTags() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply(currentUser("[\"tag-one\",\"tag-two\"]"));
            CurrentUser user = f.vrc.getCurrentUser(new AuthenticationApi(f.client));
            assertEquals(USER, user.getId());
            assertNotNull(user.getLastLogin());
            assertEquals("tag-one,tag-two", user.getPresence().getCurrentAvatarTags());
            f.assertGet(0, "/auth/user");
            assertFalse(Files.exists(f.cacheFile()), "Authentication must not write a public user cache entry");
        }
    }

    @Test void successfulAuthenticationPreservesStringAndEmptyArrayAvatarTags() throws Exception
    {
        for (String tags : new String[]{"\"already-normalized\"", "[]"})
            try (Fixture f = fixture())
            {
                f.reply(currentUser(tags));
                CurrentUser user = f.vrc.getCurrentUser(new AuthenticationApi(f.client));
                assertEquals(tags.equals("[]") ? "" : "already-normalized", user.getPresence().getCurrentAvatarTags());
            }
    }

    @Test void rawAuthenticationChallengeRetainsTotpOtpAndEmailChoices() throws Exception
    {
        // Login branches intentionally inspect raw JSON before deciding whether a CurrentUser exists.
        for (String choice : new String[]{"totp", "otp", "emailOtp"})
            try (Fixture f = fixture())
            {
                f.reply("{\"requiresTwoFactorAuth\":[\"" + choice + "\"]}");
                JsonObject challenge = f.client.<JsonObject>execute(
                    new AuthenticationApi(f.client).getCurrentUserCall(null), JsonObject.class).getData();
                assertFalse(challenge.has("id"));
                assertEquals(choice, challenge.getAsJsonArray("requiresTwoFactorAuth").get(0).getAsString());
                f.assertGet(0, "/auth/user");
            }
    }

    @Test void failedAuthenticationPreservesHttpStatus() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply(401, error(401));
            ApiException failure = assertThrows(ApiException.class,
                () -> f.vrc.getCurrentUser(new AuthenticationApi(f.client)));
            assertEquals(401, failure.getCode());
        }
    }

    @Test void ordinaryUserEmptyTimestampsAreStringsAndCacheRoundTrips() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply(publicUser());
            User user = f.vrc.getUser(USER);
            assertNotNull(user);
            assertEquals(User.class, user.getClass());
            assertEquals("", user.getLastLogin().getActualInstance());
            assertEquals("", user.getLastActivity().getActualInstance());
            assertEquals("", user.getLastMobile());
            assertEquals("Synthetic user", user.getDisplayName());
            JsonObject stored = f.cachedJson();
            assertEquals("", stored.get("last_login").getAsString());
            assertEquals(USER, stored.get("id").getAsString());
            f.assertGet(0, "/users/" + USER);
            assertSame(user, f.vrc.getUser(USER, Long.MIN_VALUE));
            assertEquals(1, f.requests.size(), "A fresh-enough cache hit must not issue a second request");
        }
    }

    @Test void selfLookupProjectsToPublicUserBeforePersistingCache() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply(currentUser("[]"));
            User user = f.vrc.getUser(USER);
            assertPublicProjection(f, user);
            f.assertGet(0, "/users/" + USER);
        }
    }

    @Test void freshSelfLookupBypassesOldCacheAndClearsNotFoundWithoutPersistingPrivateFields() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.vrc.cachedUsers.put(USER, new User().id(USER).displayName("Old cached name"));
            f.vrc.cachedUsers.add404(USER);
            f.reply(currentUser("[\"fixture-tag\"]"));
            User user = f.vrc.getUserFresh(USER);
            assertPublicProjection(f, user);
            assertEquals("Synthetic user", user.getDisplayName());
            assertFalse(f.vrc.cachedUsers.is404(USER));
            f.assertGet(0, "/users/" + USER);
        }
    }

    @Test void unknownPublicUserFieldsDoNotPreventDecodingOrEnterCache() throws Exception
    {
        try (Fixture f = fixture())
        {
            JsonObject body = JsonParser.parseString(publicUser()).getAsJsonObject();
            body.addProperty("syntheticFutureField", "not part of the public cache model");
            f.reply(body.toString());
            assertNotNull(f.vrc.getUser(USER));
            assertFalse(f.cachedJson().has("syntheticFutureField"));
        }
    }

    @Test void userReadFailuresNeverCreateSuccessfulCacheEntries() throws Exception
    {
        for (int status : new int[]{401, 403, 404, 429, 500})
            try (Fixture f = fixture())
            {
                f.reply(status, error(status));
                assertNull(f.vrc.getUser(USER), "HTTP " + status);
                assertFalse(Files.exists(f.cacheFile()));
                assertEquals(status == 404, f.vrc.cachedUsers.is404(USER));
                assertEquals(status == 401, f.scarlet.wantsVrcRefresh);
            }
    }

    @Test void freshUserReadFailureDoesNotOverwriteExistingCache() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.vrc.cachedUsers.put(USER, new User().id(USER).displayName("Known cached value"));
            byte[] before = Files.readAllBytes(f.cacheFile());
            f.reply(503, error(503));
            assertNull(f.vrc.getUserFresh(USER));
            assertArrayEquals(before, Files.readAllBytes(f.cacheFile()));
        }
    }

    @Test void memberSearchWrapperKeepsResultsTotalAndPagination() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply(memberPage(0, 100, 101));
            f.reply(memberPage(100, 1, 101));
            List<GroupMember> members = f.vrc.searchGroupMembers(GROUP, "Synthetic + query", 200);
            assertEquals(101, members.size());
            assertEquals("member-100", members.get(100).getId());
            assertEquals(2, f.requests.size(), "Server total stops pagination without an extra request");
            f.assertGet(0, "/groups/" + GROUP + "/members/search");
            assertEquals("Synthetic + query", f.requests.get(0).url().queryParameter("query"));
            assertEquals("100", f.requests.get(0).url().queryParameter("n"));
            assertEquals("100", f.requests.get(1).url().queryParameter("offset"));
        }
    }

    @Test void emptyMemberSearchWrapperProducesEmptyList() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply(memberPage(0, 0, 0));
            assertEquals(Collections.emptyList(), f.vrc.searchGroupMembers(GROUP, "Synthetic", 10));
            assertEquals(1, f.requests.size());
        }
    }

    @Test void postsWrapperKeepsTypedPostsPublicOnlyAndBoundedPagination() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply(postPage(0, 100));
            f.reply(postPage(100, 1));
            List<GroupPost> posts = f.vrc.getGroupPosts(GROUP, true, 101);
            assertEquals(101, posts.size());
            assertEquals("post-100", posts.get(100).getId());
            assertEquals("Synthetic post", posts.get(0).getTitle());
            f.assertGet(0, "/groups/" + GROUP + "/posts");
            assertEquals("true", f.requests.get(0).url().queryParameter("publicOnly"));
            assertEquals("100", f.requests.get(1).url().queryParameter("offset"));
            assertEquals("1", f.requests.get(1).url().queryParameter("n"));
            assertEquals(2, f.requests.size());
        }
    }

    @Test void emptyPostsWrapperProducesEmptyList() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply(postPage(0, 0));
            assertEquals(Collections.emptyList(), f.vrc.getGroupPosts(GROUP, false, 10));
            assertEquals("false", f.requests.get(0).url().queryParameter("publicOnly"));
        }
    }

    @Test void galleryResponseContainsRealImagesRatherThanUntypedMaps() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply("[{\"id\":\"image-one\",\"galleryId\":\"" + GALLERY + "\",\"groupId\":\"" + GROUP
                + "\",\"imageUrl\":\"https://example.invalid/synthetic.png\",\"approved\":true}]");
            f.reply("[]");
            List<GroupGalleryImage> images = f.vrc.getGroupGalleryImages(GROUP, GALLERY, Boolean.TRUE);
            assertEquals(1, images.size());
            assertEquals(GroupGalleryImage.class, images.get(0).getClass());
            assertEquals("image-one", images.get(0).getId());
            assertEquals("https://example.invalid/synthetic.png", images.get(0).getImageUrl().toString());
            assertEquals(Boolean.TRUE, images.get(0).getApproved());
            f.assertGet(0, "/groups/" + GROUP + "/galleries/" + GALLERY);
            assertEquals("true", f.requests.get(0).url().queryParameter("approved"));
            assertEquals("1", f.requests.get(1).url().queryParameter("offset"));
            assertEquals(2, f.requests.size());
        }
    }

    @Test void emptyGalleryResponseStopsAndOmitsUnspecifiedApprovalFilter() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply("[]");
            assertEquals(Collections.emptyList(), f.vrc.getGroupGalleryImages(GROUP, GALLERY, null));
            assertEquals(1, f.requests.size());
            assertNull(f.requests.get(0).url().queryParameter("approved"));
        }
    }

    @Test void groupReadHttpErrorsAreNotReportedAsSuccessfulEmptyResults() throws Exception
    {
        try (Fixture f = fixture())
        {
            f.reply(403, error(403));
            f.reply(429, error(429));
            f.reply(500, error(500));
            assertNull(f.vrc.searchGroupMembers(GROUP, "Synthetic", 10));
            assertNull(f.vrc.getGroupPosts(GROUP, true, 10));
            assertNull(f.vrc.getGroupGalleryImages(GROUP, GALLERY, null));
            assertEquals(3, f.requests.size());
        }
    }

    static String publicUser()
    {
        return "{\"id\":\"" + USER + "\",\"displayName\":\"Synthetic user\",\"last_login\":\"\",\"last_activity\":\"\",\"last_mobile\":\"\"}";
    }

    static String currentUser(String tags)
    {
        return "{\"id\":\"" + USER + "\",\"displayName\":\"Synthetic user\",\"last_login\":\"2026-10-10T12:00:00Z\","
            + "\"last_activity\":\"2026-10-10T12:00:00Z\",\"email\":\"synthetic@example.invalid\",\"emailVerified\":true,"
            + "\"twoFactorAuthEnabled\":true,\"friends\":[\"synthetic-private-friend\"],"
            + "\"presence\":{\"currentAvatarTags\":" + tags + "}}";
    }

    static void assertPublicProjection(Fixture f, User user) throws Exception
    {
        assertNotNull(user);
        assertEquals(User.class, user.getClass(), "Do not retain the CurrentUser arm of the SDK union");
        assertEquals(USER, user.getId());
        JsonObject stored = f.cachedJson();
        for (String key : new String[]{"email", "emailVerified", "twoFactorAuthEnabled", "friends", "presence"})
            assertFalse(stored.has(key), "Private self field must not enter public user cache: " + key);
    }

    static String memberPage(int start, int count, int total)
    {
        JsonArray items = new JsonArray();
        for (int i = start; i < start + count; i++)
        {
            JsonObject item = new JsonObject();
            item.addProperty("id", "member-" + i);
            item.addProperty("userId", USER);
            item.addProperty("groupId", GROUP);
            items.add(item);
        }
        JsonObject page = new JsonObject();
        page.add("results", items);
        page.addProperty("total", total);
        return page.toString();
    }

    static String postPage(int start, int count)
    {
        JsonArray posts = new JsonArray();
        for (int i = start; i < start + count; i++)
        {
            JsonObject item = new JsonObject();
            item.addProperty("id", "post-" + i);
            item.addProperty("title", "Synthetic post");
            item.addProperty("groupId", GROUP);
            posts.add(item);
        }
        JsonObject page = new JsonObject();
        page.add("posts", posts);
        return page.toString();
    }

    static String error(int status)
    {
        return "{\"error\":{\"message\":\"Synthetic test error\",\"status_code\":" + status + "}}";
    }

    Fixture fixture() throws Exception { return new Fixture(Files.createTempDirectory(temp, "api122-")); }

    static final class Fixture implements AutoCloseable
    {
        final Path cacheDir;
        final Gson previousGson;
        final Scarlet scarlet;
        final ScarletVRChat vrc;
        final ApiClient client;
        final List<Request> requests = new ArrayList<>();
        final Queue<Integer> codes = new ArrayDeque<>();
        final Queue<String> bodies = new ArrayDeque<>();

        Fixture(Path cacheDir) throws Exception
        {
            this.cacheDir = cacheDir;
            Configuration.getDefaultApiClient();
            previousGson = JSON.getGson();
            OkHttpClient http = new OkHttpClient.Builder()
                .dns(host -> { throw new AssertionError("Network access is forbidden in synthetic compatibility tests"); })
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    assertEquals("compatibility.invalid", request.url().host());
                    assertNull(request.header("Authorization"));
                    assertNull(request.header("Cookie"));
                    assertFalse(bodies.isEmpty(), "Unexpected extra API request: " + request.url());
                    requests.add(request);
                    // Never call chain.proceed: these requests cannot reach a DNS resolver or server.
                    return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(codes.remove()).message("Synthetic offline response")
                        .header("Content-Type", "application/json")
                        .body(ResponseBody.create(MediaType.parse("application/json"), bodies.remove())).build();
                }).build();
            client = new ApiClient(http).setBasePath("https://compatibility.invalid/api/1");
            ScarletVRChat.initJson();
            scarlet = ScarletDiscordSafetyTest.inert(Scarlet.class);
            vrc = ScarletDiscordSafetyTest.inert(ScarletVRChat.class);
            ScarletDiscordSafetyTest.put(vrc, "client", client);
            ScarletDiscordSafetyTest.put(vrc, "scarlet", scarlet);
            @SuppressWarnings("unchecked")
            ScarletJsonCache<User> cache = ScarletDiscordSafetyTest.inert(ScarletJsonCache.class);
            ScarletDiscordSafetyTest.put(cache, "kind", "usr");
            ScarletDiscordSafetyTest.put(cache, "type", TypeToken.get(User.class));
            ScarletDiscordSafetyTest.put(cache, "dir", cacheDir.toFile());
            ScarletDiscordSafetyTest.put(cache, "entries", new HashMap<>());
            ScarletDiscordSafetyTest.put(cache, "known404s", new HashSet<>());
            ScarletDiscordSafetyTest.put(vrc, "cachedUsers", cache);
        }

        void reply(String body) { reply(200, body); }
        void reply(int status, String body) { codes.add(status); bodies.add(body); }
        Path cacheFile() { return cacheDir.resolve(USER + ".json"); }
        JsonObject cachedJson() throws Exception
        {
            return JsonParser.parseString(new String(Files.readAllBytes(cacheFile()), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        void assertGet(int index, String path)
        {
            assertEquals("GET", requests.get(index).method());
            assertEquals("/api/1" + path, requests.get(index).url().encodedPath());
        }
        @Override public void close()
        {
            JSON.setGson(previousGson);
            client.getHttpClient().connectionPool().evictAll();
            client.getHttpClient().dispatcher().executorService().shutdownNow();
        }
    }
}

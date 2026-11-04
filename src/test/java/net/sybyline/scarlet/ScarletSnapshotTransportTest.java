package net.sybyline.scarlet;

import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.Configuration;
import io.github.vrchatapi.JSON;
import net.sybyline.scarlet.ext.ExtendedUserAgent;
import net.sybyline.scarlet.util.VrcRateLimiter;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Interceptor-only transport: synthetic identities; DNS/network access is forbidden. */
class ScarletSnapshotTransportTest
{
    @TempDir Path temp;
    static final String USER = ScarletSnapshotPrivacyTest.USER;

    static final class Fixture implements AutoCloseable
    {
        final Gson previousGson;
        final ScarletVRChat vrc = inert(ScarletVRChat.class);
        final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> bodies = new ConcurrentHashMap<>();
        final Map<String, Integer> codes = new ConcurrentHashMap<>();
        final AtomicInteger passedLimiter = new AtomicInteger();
        final VrcRateLimiter limiter = new VrcRateLimiter(10D, 20D);
        final ApiClient client;
        CountDownLatch overlap;
        Consumer<Request> onRequest = request -> {};
        int replyIndex;

        Fixture(Path dir) throws Exception
        {
            Configuration.getDefaultApiClient(); previousGson = JSON.getGson();
            OkHttpClient http = new OkHttpClient.Builder()
                .dns(host -> { throw new AssertionError("Network access forbidden"); })
                .addInterceptor(chain -> {
                    ScarletVRChat.checkSnapshotRequest(chain);
                    limiter.acquire();
                    ScarletVRChat.checkSnapshotRequest(chain);
                    passedLimiter.incrementAndGet();
                    return chain.proceed(chain.request());
                }).addInterceptor(chain -> {
                    Request request = chain.request();
                    assertEquals("snapshot.invalid", request.url().host());
                    assertNull(request.header("Authorization")); assertNull(request.header("Cookie"));
                    assertNull(ScarletVRChatCookieJar._context.get()); assertNull(ScarletVRChatCookieJar._context2.get());
                    assertEquals(5_000, chain.connectTimeoutMillis());
                    assertEquals(5_000, chain.readTimeoutMillis());
                    assertEquals(TimeUnit.SECONDS.toNanos(5L), chain.call().timeout().timeoutNanos());
                    requests.add(request);
                    if (overlap != null)
                    {
                        overlap.countDown();
                        try { assertTrue(overlap.await(2L, TimeUnit.SECONDS)); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.io.IOException("Cancelled synthetic lookup"); }
                    }
                    onRequest.accept(request);
                    String path = request.url().encodedPath();
                    String body = bodies.get(path);
                    assertNotNull(body, "Unexpected synthetic request: " + path);
                    return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(codes.getOrDefault(path, 200)).message("Synthetic response")
                        .header("Content-Type", "application/json")
                        .body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
                }).build();
            client = new ApiClient(http).setBasePath("https://snapshot.invalid/api/1");
            ScarletVRChat.initJson();
            put(vrc, "client", client); put(vrc, "scarlet", inert(Scarlet.class));
            put(vrc, "currentUserId", USER); put(vrc, "groupId", ScarletSnapshotPrivacyTest.GROUP);
            put(vrc, "cookies", new ScarletVRChatCookieJar(dir.resolve("absent-cookies").toFile(), new ScarletVRChatCookieJar.Stores() {
                public String primary() { return null; }
                public void primary(String value) { fail("Snapshot must not save cookies"); }
                public Map<String, List<String>> alternates() { return new HashMap<>(); }
                public void alternates(Map<String, List<String>> value) { fail("Snapshot must not save alternate cookies"); }
                public void removeLoginValue(String key) { fail("Snapshot must not remove credentials"); }
                public boolean persistOnShutdown() { return false; }
            }));
            ExtendedUserAgent.setCurrentUserId(USER);
        }

        void reply(String body) { reply(200, body); }
        void reply(int code, String body)
        {
            String path = "/api/1/users/" + USER + new String[]{"", "/groups", "/groups/represented"}[replyIndex++];
            bodies.put(path, body); codes.put(path, code);
        }
        void replies()
        {
            reply(ScarletSnapshotPrivacyTest.self().toString());
            JsonArray groups = new JsonArray(); groups.add(ScarletSnapshotPrivacyTest.group());
            reply(groups.toString()); reply(ScarletSnapshotPrivacyTest.group().toString());
        }
        void assertGet(int kind, String path)
        {
            assertEquals(1L, requests.stream().filter(request -> request.method().equals("GET")
                && request.url().encodedPath().equals("/api/1" + path)).count());
        }
        ScarletData.AuditEntryMetadata metadata()
        {
            ScarletData.AuditEntryMetadata metadata = ScarletDiscordSafetyTest.metadata(ScarletSnapshotPrivacyTest.AUDIT, "ordinary");
            metadata.entry.setTargetId(USER); return metadata;
        }
        public void close()
        {
            assertTrue(vrc.shutdownSnapshots()); JSON.setGson(previousGson);
            client.getHttpClient().connectionPool().evictAll();
            client.getHttpClient().dispatcher().executorService().shutdownNow();
        }
    }

    @Test void actualTransportOverlapsThreeRequestsAndUsesBoundedPublicResults() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            f.replies(); f.overlap = new CountDownLatch(3);
            ScarletData.AuditEntryMetadata metadata = f.metadata();
            assertEquals(1, f.vrc.snapshot(metadata).size());
            assertEquals(3, f.requests.size()); assertEquals(3, f.passedLimiter.get());
            ScarletSnapshotPrivacyTest.assertPublic(metadata.snapshotTargetUser);
            assertFalse(Scarlet.GSON.toJson(metadata).contains("PRIVATE_"));
        }
    }

    @Test void inFlightRedactionTargetReplacementAndAuthenticationChangesDiscardAllResults() throws Exception
    {
        for (String change : new String[]{"redact", "target", "replace", "group", "user", "authentication"})
        {
            try (Fixture f = new Fixture(temp.resolve(change)))
            {
                f.replies();
                ScarletData.AuditEntryMetadata metadata = f.metadata();
                f.onRequest = request -> {
                    synchronized (metadata)
                    {
                        if (change.equals("redact")) metadata.entryRedacted = true;
                        if (change.equals("target")) metadata.entry.setTargetId("usr_10000000-0000-0000-0000-000000000099");
                        if (change.equals("replace")) metadata.entry = null;
                        if (change.equals("group")) f.vrc.groupId = "grp_10000000-0000-0000-0000-000000000099";
                        if (change.equals("user")) f.vrc.currentUserId = "usr_10000000-0000-0000-0000-000000000099";
                        if (change.equals("authentication")) ExtendedUserAgent.beginPrimaryAuthentication();
                    }
                };
                assertNull(f.vrc.snapshot(metadata), change);
                assertNull(metadata.snapshotTargetUser, change);
                assertNull(metadata.snapshotTargetUserGroups, change);
                assertNull(metadata.snapshotTargetUserRepresentedGroup, change);
            }
        }
    }

    @Test void alternateAndTemporaryContextsAreRejectedWithoutPropagatingCredentials() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            ScarletVRChatCookieJar._context.set("alternate-test-context");
            try { assertNull(f.vrc.snapshot(f.metadata())); }
            finally { ScarletVRChatCookieJar._context.remove(); }
            ScarletVRChatCookieJar._context2.set(new ArrayList<>());
            try { assertNull(f.vrc.snapshot(f.metadata())); }
            finally { ScarletVRChatCookieJar._context2.remove(); }
            f.vrc.pendingUsername = "private-pending-login";
            try { assertNull(f.vrc.snapshot(f.metadata())); }
            finally { f.vrc.pendingUsername = null; }
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test void snapshotClientFreezesOnlyPrimaryCookiesRetainsLimiterAndNeverSavesResponseCookies() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            okhttp3.HttpUrl url = okhttp3.HttpUrl.parse("https://snapshot.invalid/api/1/users/" + USER);
            okhttp3.Cookie primary = new okhttp3.Cookie.Builder().name("auth").value("synthetic-primary")
                .hostOnlyDomain("snapshot.invalid").path("/").secure().build();
            List<okhttp3.Cookie> original = new ArrayList<>(); original.add(primary);
            AtomicBoolean allowed = new AtomicBoolean(true);
            OkHttpClient client = ScarletVRChat.snapshotHttpClient(f.client.getHttpClient(), original, allowed::get);
            original.clear();
            ScarletVRChatCookieJar._context.set("synthetic-alternate");
            ScarletVRChatCookieJar._context2.set(Collections.singletonList(new okhttp3.Cookie.Builder()
                .name("auth").value("synthetic-temporary").hostOnlyDomain("snapshot.invalid").build()));
            try
            {
                assertEquals(Collections.singletonList(primary), client.cookieJar().loadForRequest(url));
                client.cookieJar().saveFromResponse(url, Collections.singletonList(new okhttp3.Cookie.Builder()
                    .name("auth").value("synthetic-response").hostOnlyDomain("snapshot.invalid").build()));
                assertEquals(Collections.singletonList(primary), client.cookieJar().loadForRequest(url));
                assertTrue(client.cookieJar().loadForRequest(okhttp3.HttpUrl.parse("https://other.invalid/")).isEmpty());
                assertSame(f.client.getHttpClient().interceptors().get(0), client.interceptors().get(0));
                assertSame(f.client.getHttpClient().connectionPool(), client.connectionPool());
                allowed.set(false); assertTrue(client.cookieJar().loadForRequest(url).isEmpty());
            }
            finally { ScarletVRChatCookieJar._context.remove(); ScarletVRChatCookieJar._context2.remove(); }
        }
    }

    @Test void cancellationCannotBypassSharedRateLimitBackoff() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            f.replies(); f.limiter.penalize(30_000L);
            ScarletData.AuditEntryMetadata metadata = f.metadata();
            Thread caller = new Thread(() -> f.vrc.snapshot(metadata)); caller.start();
            // Wait until the pool has been installed, then cancel its rate-limited calls.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (caller.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield();
            assertTrue(f.vrc.shutdownSnapshots()); caller.join(2_000L);
            assertFalse(caller.isAlive()); assertTrue(f.requests.isEmpty());
            assertEquals(0, f.passedLimiter.get()); assertTrue(f.limiter.isBackingOff());
            assertNull(metadata.snapshotTargetUser);
        }
    }

    @Test void snapshotAndShutdownDoNotWaitForAccountLoginMonitor() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            f.replies();
            CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
            Thread login = new Thread(() -> {
                synchronized (f.vrc)
                {
                    holding.countDown();
                    try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
            });
            login.start(); assertTrue(holding.await(2L, TimeUnit.SECONDS));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread capture = new Thread(() -> {
                try {
                    assertNotNull(f.vrc.snapshot(f.metadata()));
                    assertTrue(f.vrc.shutdownSnapshots());
                } catch (Throwable ex) { failure.set(ex); }
            });
            try
            {
                capture.start(); capture.join(2_000L);
                assertFalse(capture.isAlive(), "Snapshot lifecycle cannot wait behind an account login prompt");
                assertNull(failure.get());
            }
            finally { release.countDown(); login.join(2_000L); capture.join(2_000L); }
        }
    }
}

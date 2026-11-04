package net.sybyline.scarlet;

import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;

import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.Configuration;
import io.github.vrchatapi.JSON;
import net.sybyline.scarlet.ext.ExtendedUserAgent;
import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Interceptor-only HTTP and synthetic cookies; DNS, live APIs and game access forbidden. */
class ScarletIdleInstanceTransportTest
{
    @TempDir Path temp;
    static final String GROUP = ScarletIdleInstanceCloseTest.GROUP, SELF = ScarletIdleInstanceCloseTest.SELF;
    static final String LOCATION = ScarletIdleInstanceCloseTest.LOCATION;

    static Response response(Request request, int code, String body)
    {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
            .message("Synthetic response").body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
    }

    static final class Fixture implements AutoCloseable
    {
        final Gson previous;
        final ScarletVRChat vrc = inert(ScarletVRChat.class);
        final ApiClient client;
        final List<Request> requests = new ArrayList<>();
        int status = 200;
        String body = ScarletIdleInstanceCloseTest.alone().toString();
        Runnable duringResponse = () -> {};
        Fixture(Path dir) throws Exception
        {
            Configuration.getDefaultApiClient(); previous = JSON.getGson();
            OkHttpClient base = new OkHttpClient.Builder().dns(host -> { throw new AssertionError("No network allowed"); })
                .addInterceptor(chain -> {
                    requests.add(chain.request());
                    assertEquals("idle-close.invalid", chain.request().url().host());
                    assertEquals(5_000, chain.connectTimeoutMillis());
                    assertEquals(5_000, chain.readTimeoutMillis());
                    assertEquals(TimeUnit.SECONDS.toNanos(5), chain.call().timeout().timeoutNanos());
                    duringResponse.run(); return response(chain.request(), status, body);
                }).build();
            client = new ApiClient(base).setBasePath("https://idle-close.invalid/api/1");
            ScarletVRChat.initJson(); put(vrc, "client", client);
            put(vrc, "cookies", new ScarletVRChatCookieJar(dir.resolve("absent-cookies").toFile(), new ScarletVRChatCookieJar.Stores() {
                public String primary() { return null; }
                public void primary(String value) { fail("No credential writes"); }
                public Map<String, List<String>> alternates() { return new HashMap<>(); }
                public void alternates(Map<String, List<String>> value) { fail("No alternate writes"); }
                public void removeLoginValue(String key) { fail("No credential removal"); }
                public boolean persistOnShutdown() { return false; }
            }));
            vrc.groupId = GROUP; vrc.currentUserId = SELF; ExtendedUserAgent.setCurrentUserId(SELF);
        }
        public void close()
        {
            JSON.setGson(previous); client.getHttpClient().connectionPool().evictAll();
            client.getHttpClient().dispatcher().executorService().shutdownNow();
        }
    }

    @Test void actualRequestsUseFreshExactInstanceGetAndOnlyExplicitSoftDelete() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            assertNotNull(f.vrc.readIdleInstance(LOCATION, () -> true));
            assertTrue(f.vrc.softCloseIdleInstance(LOCATION, () -> true));
            assertEquals(2, f.requests.size());
            Request get = f.requests.get(0), close = f.requests.get(1);
            assertEquals("GET", get.method()); assertNull(get.url().query());
            assertEquals("/api/1/instances/" + LOCATION, get.url().encodedPath());
            assertEquals("DELETE", close.method()); assertEquals(get.url().encodedPath(), close.url().encodedPath());
            assertEquals("false", close.url().queryParameter("hardClose")); assertNull(close.url().queryParameter("closedAt"));
            assertEquals(1, close.url().querySize());
        }
    }

    @Test void deniedChangedGroupAlternateAndTemporaryAuthenticationNeverDispatch() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            assertNull(f.vrc.readIdleInstance(LOCATION, () -> false));
            assertFalse(f.vrc.softCloseIdleInstance(LOCATION, () -> false));
            f.vrc.groupId = ScarletIdleInstanceCloseTest.OTHER_GROUP;
            assertNull(f.vrc.readIdleInstance(LOCATION, () -> true)); f.vrc.groupId = GROUP;
            ScarletVRChatCookieJar._context.set("synthetic-alternate");
            try { assertFalse(f.vrc.softCloseIdleInstance(LOCATION, () -> true)); }
            finally { ScarletVRChatCookieJar._context.remove(); }
            ScarletVRChatCookieJar._context2.set(new ArrayList<>());
            try { assertNull(f.vrc.readIdleInstance(LOCATION, () -> true)); }
            finally { ScarletVRChatCookieJar._context2.remove(); }
            f.vrc.pendingUsername = "synthetic-pending";
            assertFalse(f.vrc.softCloseIdleInstance(LOCATION, () -> true)); f.vrc.pendingUsername = null;
            ExtendedUserAgent.beginPrimaryAuthentication();
            assertNull(f.vrc.readIdleInstance(LOCATION, () -> true));
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test void lateSafetyAndIdentityChangesDiscardReadProof() throws Exception
    {
        for (String kind : new String[]{"guard", "group", "user", "authentication"})
        {
            try (Fixture f = new Fixture(temp.resolve(kind)))
            {
                AtomicBoolean allowed = new AtomicBoolean(true);
                f.duringResponse = () -> {
                    if (kind.equals("guard")) allowed.set(false);
                    if (kind.equals("group")) f.vrc.groupId = ScarletIdleInstanceCloseTest.OTHER_GROUP;
                    if (kind.equals("user")) f.vrc.currentUserId = ScarletIdleInstanceCloseTest.OTHER;
                    if (kind.equals("authentication")) ExtendedUserAgent.beginPrimaryAuthentication();
                };
                assertNull(f.vrc.readIdleInstance(LOCATION, allowed::get), kind);
                assertEquals(1, f.requests.size(), kind);
            }
        }
    }

    @Test void badStatusMalformedTrailingAndOversizeBodiesNeverSupplyProofOrRetry() throws Exception
    {
        for (String kind : new String[]{"429", "503", "redirect", "empty", "malformed", "trailing", "oversize"})
        {
            try (Fixture f = new Fixture(temp.resolve(kind)))
            {
                if (kind.equals("429")) f.status = 429;
                if (kind.equals("503")) f.status = 503;
                if (kind.equals("redirect")) f.status = 302;
                if (kind.equals("empty")) f.body = "";
                if (kind.equals("malformed")) f.body = "{";
                if (kind.equals("trailing")) f.body += " {}";
                if (kind.equals("oversize")) f.body = new String(new char[2 * 1024 * 1024 + 1]);
                assertNull(f.vrc.readIdleInstance(LOCATION, () -> true), kind);
                assertEquals(1, f.requests.size(), kind);
            }
        }
    }

    static Interceptor.Chain chain(Call call, Request request, AtomicInteger proceeds, AtomicReference<Request> sent)
    {
        return (Interceptor.Chain) Proxy.newProxyInstance(ScarletIdleInstanceTransportTest.class.getClassLoader(),
            new Class<?>[]{Interceptor.Chain.class}, (proxy, method, args) -> {
                if (method.getName().equals("request")) return request;
                if (method.getName().equals("call")) return call;
                if (method.getName().equals("proceed"))
                {
                    proceeds.incrementAndGet(); sent.set((Request) args[0]); return response((Request) args[0], 200, "{}");
                }
                throw new AssertionError("Unexpected chain operation: " + method.getName());
            });
    }

    @Test void finalApplicationAndNetworkGuardsCancelAfterWaitOrConnectionAndBlockFollowups() throws Exception
    {
        AtomicBoolean allowed = new AtomicBoolean(true);
        OkHttpClient base = new OkHttpClient.Builder().dns(host -> { throw new AssertionError("No network allowed"); })
            .addInterceptor(chain -> { throw new AssertionError("Base transport must not execute"); }).build();
        OkHttpClient guarded = ScarletVRChat.idleCloseHttpClient(base, Collections.emptyList(), allowed::get);
        assertSame(base.interceptors().get(0), guarded.interceptors().get(0));
        assertFalse(guarded.retryOnConnectionFailure()); assertFalse(guarded.followRedirects()); assertFalse(guarded.followSslRedirects());
        assertNull(guarded.cache()); assertSame(okhttp3.Authenticator.NONE, guarded.authenticator());
        Request request = new Request.Builder().url("https://idle-close.invalid/api/1/instances/synthetic")
            .header("Authorization", "synthetic-pending").header("Cookie", "synthetic-pending").build();
        Call call = guarded.newCall(request); AtomicInteger proceeds = new AtomicInteger(); AtomicReference<Request> sent = new AtomicReference<>();
        Interceptor.Chain chain = chain(call, request, proceeds, sent);
        Interceptor appGuard = guarded.interceptors().get(guarded.interceptors().size() - 1);
        Interceptor networkGuard = guarded.networkInterceptors().get(guarded.networkInterceptors().size() - 1);
        allowed.set(false); assertThrows(IOException.class, () -> appGuard.intercept(chain));
        assertThrows(IOException.class, () -> networkGuard.intercept(chain)); assertEquals(0, proceeds.get());
        allowed.set(true);
        try (Response ignored = appGuard.intercept(chain))
        { assertNull(sent.get().header("Authorization")); assertNull(sent.get().header("Cookie")); }
        try (Response ignored = networkGuard.intercept(chain)) { assertEquals(2, proceeds.get()); }
        assertThrows(IOException.class, () -> networkGuard.intercept(chain)); assertEquals(2, proceeds.get());
        call.cancel(); assertThrows(IOException.class, () -> appGuard.intercept(chain));
    }

    @Test void clientFreezesOnlyPrimaryCookiesAndCannotUpdateAuthentication()
    {
        Cookie cookie = new Cookie.Builder().name("auth").value("synthetic-primary").hostOnlyDomain("idle-close.invalid").build();
        List<Cookie> source = new ArrayList<>(); source.add(cookie); AtomicBoolean allowed = new AtomicBoolean(true);
        OkHttpClient guarded = ScarletVRChat.idleCloseHttpClient(new OkHttpClient(), source, allowed::get);
        source.clear(); HttpUrl url = HttpUrl.parse("https://idle-close.invalid/api/1/instances/synthetic");
        assertEquals(Collections.singletonList(cookie), guarded.cookieJar().loadForRequest(url));
        guarded.cookieJar().saveFromResponse(url, Collections.emptyList());
        assertEquals(Collections.singletonList(cookie), guarded.cookieJar().loadForRequest(url));
        allowed.set(false); assertTrue(guarded.cookieJar().loadForRequest(url).isEmpty());
    }
}

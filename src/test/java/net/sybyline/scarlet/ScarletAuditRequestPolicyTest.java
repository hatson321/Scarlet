package net.sybyline.scarlet;

import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.Configuration;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import net.sybyline.scarlet.util.RateLimiterTestClock;
import net.sybyline.scarlet.util.VrcRateLimiter;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Actual SDK + HTTP interceptor, synthetic time/responses, DNS and network forbidden. */
class ScarletAuditRequestPolicyTest
{
    static final String URL = "https://audit.invalid/api/1/groups/grp_synthetic/auditLogs";
    static final OffsetDateTime FROM = OffsetDateTime.parse("2030-01-01T00:00:00Z");

    static Response response(Request request, int code, String hint, String body)
    {
        Response.Builder builder = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("Synthetic").header("Content-Type", "application/json")
            .body(ResponseBody.create(MediaType.parse("application/json"), body));
        if (hint != null) builder.header("Retry-After", hint);
        return builder.build();
    }

    static String page(String id, boolean next)
    {
        return "{\"results\":[{\"id\":\"" + id + "\",\"actorId\":\"usr_synthetic\","
            + "\"eventType\":\"group.instance.kick\",\"created_at\":\"2030-01-01T01:00:00Z\",\"data\":{}}],"
            + "\"hasNext\":" + next + ",\"totalCount\":300}";
    }

    static final class Fixture implements AutoCloseable
    {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final VrcRateLimiter global = RateLimiterTestClock.create(10D, 20D, clock::get, clock::addAndGet);
        final VrcRateLimiter auditGate = RateLimiterTestClock.create(0.5D, 1D, clock::get, clock::addAndGet);
        final List<Long> penalties = new ArrayList<>(), times = new ArrayList<>();
        final List<Request> requests = new ArrayList<>();
        final ScarletAuditRequestPolicy policy;
        final OkHttpClient http;
        final ScarletVRChat vrc = inert(ScarletVRChat.class);
        final Gson previousGson;
        Function<Request, Response> reply = request -> response(request, 200, null, page("first", false));

        Fixture() throws Exception
        {
            this(ScarletAuditRequestPolicy.HISTORY_BUDGET_MS);
        }

        Fixture(long budgetMs) throws Exception
        {
            policy = new ScarletAuditRequestPolicy(global, auditGate, penalties::add, clock::get, budgetMs);
            Configuration.getDefaultApiClient(); previousGson = JSON.getGson(); ScarletVRChat.initJson();
            http = new OkHttpClient.Builder().dns(host -> { throw new AssertionError("Network forbidden"); })
                .addInterceptor(policy).addInterceptor(chain -> {
                    assertEquals("audit.invalid", chain.request().url().host());
                    assertNull(chain.request().header("Authorization"));
                    assertNull(chain.request().header("Cookie"));
                    requests.add(chain.request()); times.add(clock.get());
                    return reply.apply(chain.request());
                }).build();
            put(vrc, "scarlet", inert(Scarlet.class));
            put(vrc, "groupId", "grp_synthetic");
            put(vrc, "client", new ApiClient(http).setBasePath("https://audit.invalid/api/1"));
            put(vrc, "auditRequests", policy);
        }

        List<GroupAuditLogEntryUnknown> historical()
        {
            return vrc.auditQueryHistorical(FROM, FROM.plusDays(14), null, "group.instance.kick", null);
        }

        List<String> offsets()
        {
            List<String> result = new ArrayList<>();
            requests.forEach(r -> result.add(r.url().queryParameter("offset")));
            return result;
        }

        @Override public void close()
        {
            JSON.setGson(previousGson);
            http.connectionPool().evictAll(); http.dispatcher().executorService().shutdownNow();
        }
    }

    @Test void zeroMissingInvalidAndPastHintsUseThirtySecondFloor()
    {
        Request request = new Request.Builder().url(URL).build();
        long now = FROM.toInstant().toEpochMilli();
        for (String value : new String[]{null, "", "0", "-1", "invalid", "1", "29",
                FROM.minusMinutes(1).format(DateTimeFormatter.RFC_1123_DATE_TIME)})
            try (Response r = response(request, 429, value, "{}")) {
                assertEquals(30_000L, ScarletAuditRequestPolicy.retryAfterMillis(r, 30_000L, now), "hint=" + value);
            }
    }

    @Test void positiveDateAndLargeHintsAreNeverShortenedToFiveMinutes()
    {
        Request request = new Request.Builder().url(URL).build();
        long now = FROM.toInstant().toEpochMilli();
        String future = FROM.plusMinutes(11).withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        String[] hints = {"600", future, "9223372036854775807", "9999999999999999999999999999999"};
        long[] waits = {600_000L, 660_000L, Long.MAX_VALUE, Long.MAX_VALUE};
        for (int i = 0; i < hints.length; i++)
            try (Response r = response(request, 429, hints[i], "{}")) {
                assertEquals(waits[i], ScarletAuditRequestPolicy.retryAfterMillis(r, 30_000L, now));
            }
    }

    @Test void secondPage429RetriesSameOffsetWithoutRestartingOrDuplicatingFirstPage() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            AtomicInteger second = new AtomicInteger();
            f.reply = r -> "0".equals(r.url().queryParameter("offset")) ? response(r, 200, null, page("first", true))
                : second.incrementAndGet() == 1 ? response(r, 429, "0", "{}") : response(r, 200, null, page("second", false));
            List<GroupAuditLogEntryUnknown> result = f.historical();
            assertNotNull(result); assertEquals(2, result.size());
            assertEquals("first", result.get(0).getId()); assertEquals("second", result.get(1).getId());
            assertEquals(Arrays.asList("0", "100", "100"), f.offsets());
            assertEquals(Arrays.asList(30_000L), f.penalties);
            assertTrue(f.times.get(1) - f.times.get(0) >= 2_000L);
            assertTrue(f.times.get(2) - f.times.get(1) >= 30_000L);
        }
    }

    @Test void persistent429IsBoundedAndEscalatesWithoutReturningPartialTotals() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.reply = r -> response(r, 429, "0", "{}");
            assertNull(f.historical()); assertEquals(3, f.requests.size());
            assertEquals(Arrays.asList(30_000L, 60_000L, 120_000L), f.penalties);
            assertTrue(f.global.isBackingOff());
        }
    }

    @Test void retryAllowanceIsSharedAcrossPagesInsteadOfResettingForEachPage() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            AtomicInteger index = new AtomicInteger();
            f.reply = r -> index.getAndIncrement() % 2 == 0 ? response(r, 429, "0", "{}")
                : response(r, 200, null, page("page-" + r.url().queryParameter("offset"), true));
            assertNull(f.historical());
            assertEquals(Arrays.asList("0", "0", "100", "100", "200"), f.offsets());
        }
    }

    @Test void longServerHintAbortsHistoryInsteadOfRetryingBeforeServerAllowsIt() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.reply = r -> response(r, 429, "600", "{}");
            assertNull(f.historical()); assertEquals(1, f.requests.size());
            assertEquals(600_000L, f.global.backoffRemainingMs());
        }
    }

    @Test void routineAuditPollingDoesNotAutoRetryAndCannotBypassSharedPacing() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            assertNotNull(f.vrc.auditQuery(FROM, FROM.plusDays(1)));
            assertNotNull(f.vrc.auditQuery(FROM, FROM.plusDays(1)));
            assertTrue(f.times.get(1) - f.times.get(0) >= 2_000L);
            f.reply = r -> response(r, 429, "0", "{}");
            assertNull(f.vrc.auditQuery(FROM, FROM.plusDays(1)));
            assertEquals(3, f.requests.size());
        }
    }

    @Test void writeAndUnrelatedGetNeverAutoRetryEvenWithinHistoricalScope() throws Exception
    {
        for (boolean write : new boolean[]{true, false}) try (Fixture f = new Fixture())
        {
            f.reply = r -> response(r, 429, "0", "{}");
            Request.Builder r = new Request.Builder().url(write ? URL : "https://audit.invalid/api/1/users/usr_synthetic");
            if (write) r.post(RequestBody.create(MediaType.parse("application/json"), "{}"));
            try (ScarletAuditRequestPolicy.History scope = f.policy.historicalRead(); Response result = f.http.newCall(r.build()).execute())
            {
                assertEquals(429, result.code()); assertEquals(1, f.requests.size());
            }
        }
    }

    @Test void historyScopeIsRemovedAfterCompletionAndDoesNotLeakToLaterCalls() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            assertNotNull(f.historical());
            f.reply = r -> response(r, 429, "0", "{}");
            try (Response result = f.http.newCall(new Request.Builder().url(URL).build()).execute()) {
                assertEquals(429, result.code());
            }
            assertEquals(2, f.requests.size());
        }
    }

    @Test void queryDeadlineStopsBeforeAnotherDispatch() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.reply = r -> { f.clock.addAndGet(ScarletAuditRequestPolicy.HISTORY_BUDGET_MS); return response(r, 200, null, page("first", true)); };
            assertNull(f.historical()); assertEquals(1, f.requests.size());
        }
    }

    @Test void non429FailuresAreNotRetried() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.reply = r -> response(r, 503, null, "{}");
            assertNull(f.historical()); assertEquals(1, f.requests.size()); assertTrue(f.penalties.isEmpty());
        }
    }

    @Test void cancelledOrInterruptedRequestsNeverReachSyntheticServer() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            okhttp3.Call call = f.http.newCall(new Request.Builder().url(URL).build()); call.cancel();
            assertThrows(IOException.class, call::execute); assertTrue(f.requests.isEmpty());
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedIOException.class, () -> f.http.newCall(new Request.Builder().url(URL).build()).execute()); }
            finally { Thread.interrupted(); }
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test void scopedWatchdogCancelsResponseConsumptionNotOnlyRequestDispatch() throws Exception
    {
        AtomicLong clock = new AtomicLong(1_000_000L);
        VrcRateLimiter global = RateLimiterTestClock.create(10D, 20D, clock::get, clock::addAndGet);
        VrcRateLimiter gate = RateLimiterTestClock.create(0.5D, 1D, clock::get, clock::addAndGet);
        ScarletAuditRequestPolicy policy = new ScarletAuditRequestPolicy(global, gate, ignored -> {}, clock::get, 100L);
        OkHttpClient http = new OkHttpClient.Builder().dns(host -> { throw new AssertionError("Network forbidden"); })
            .addInterceptor(policy).addInterceptor(chain -> {
                ResponseBody slow = new ResponseBody() {
                    final okio.BufferedSource source = okio.Okio.buffer(new okio.Source() {
                        public long read(okio.Buffer sink, long count) throws IOException {
                            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
                            while (!chain.call().isCanceled() && System.nanoTime() < stop)
                                try { Thread.sleep(5L); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                            assertTrue(chain.call().isCanceled(), "Watchdog must cancel while the body is being read");
                            throw new IOException("Synthetic cancelled response body");
                        }
                        public okio.Timeout timeout() { return okio.Timeout.NONE; }
                        public void close() {}
                    });
                    public MediaType contentType() { return MediaType.parse("application/json"); }
                    public long contentLength() { return -1L; }
                    public okio.BufferedSource source() { return source; }
                };
                return response(chain.request(), 200, null, "{}").newBuilder().body(slow).build();
            }).build();
        okhttp3.Call call = http.newCall(new Request.Builder().url(URL).build());
        try (ScarletAuditRequestPolicy.History scope = policy.historicalRead(); Response result = call.execute())
        {
            assertThrows(IOException.class, () -> result.body().string());
            assertTrue(scope.expired()); assertTrue(call.isCanceled());
        }
        finally { http.connectionPool().evictAll(); http.dispatcher().executorService().shutdownNow(); }
    }

    @Test void closingSuccessfulScopeDisarmsItsWatchdog() throws Exception
    {
        try (Fixture f = new Fixture(150L))
        {
            okhttp3.Call call = f.http.newCall(new Request.Builder().url(URL).build());
            try (ScarletAuditRequestPolicy.History scope = f.policy.historicalRead(); Response result = call.execute()) {
                assertEquals(200, result.code()); result.body().string();
            }
            Thread.sleep(200L);
            assertFalse(call.isCanceled(), "Completed scope must not leave a later cancellation behind");
        }
    }
}

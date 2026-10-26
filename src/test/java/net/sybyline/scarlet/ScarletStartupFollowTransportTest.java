package net.sybyline.scarlet;

import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.Configuration;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import net.sybyline.scarlet.ext.ExtendedUserAgent;
import net.sybyline.scarlet.util.VrcRateLimiter;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Synthetic intercepted HTTP only. No DNS, real login, process or game access. */
class ScarletStartupFollowTransportTest
{
    @TempDir Path temp;
    static final String GROUP = ScarletIdleInstanceCloseTest.GROUP;
    static final String SELF = ScarletIdleInstanceCloseTest.SELF;
    static final String LOCATION = ScarletIdleInstanceCloseTest.LOCATION;
    static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-23T12:00:00Z");
    static final OffsetDateTime TO = OffsetDateTime.parse("2026-10-23T12:00:00Z");

    enum Read { ACTIVE, AUDIT, SECURE }

    static JsonObject creation()
    {
        JsonObject entry = new JsonObject();
        entry.addProperty("actorDisplayName", "Synthetic actor");
        entry.addProperty("actorId", SELF);
        entry.addProperty("created_at", "2026-10-23T11:00:00Z");
        entry.addProperty("description", "Synthetic instance created");
        entry.addProperty("eventType", "group.instance.create");
        entry.addProperty("groupId", GROUP);
        entry.addProperty("id", "gaud_00000000-0000-0000-0000-000000000001");
        entry.addProperty("targetId", LOCATION);
        JsonObject data = new JsonObject();
        data.addProperty("futureField", "preserved");
        entry.add("data", data);
        return entry;
    }

    static String page(boolean next, int count)
    {
        JsonObject page = new JsonObject();
        page.addProperty("hasNext", next);
        JsonArray entries = new JsonArray();
        for (int i = 0; i < count; i++) entries.add(creation());
        page.add("results", entries);
        return page.toString();
    }

    static final class Fixture implements AutoCloseable
    {
        final Gson previous;
        final ScarletVRChat vrc = inert(ScarletVRChat.class);
        final ApiClient client;
        final List<Request> requests = new ArrayList<>();
        final AtomicInteger policyPasses = new AtomicInteger(), auditPasses = new AtomicInteger();
        final AtomicLong penalty = new AtomicLong();
        final VrcRateLimiter global = new VrcRateLimiter(1000.0, 1000.0);
        final ScarletAuditRequestPolicy policy = new ScarletAuditRequestPolicy(global,
            new VrcRateLimiter(1000.0, 1000.0), penalty::set, System::currentTimeMillis);
        int status = 200;
        String body = "[]";
        Runnable duringResponse = () -> {};

        Fixture(Path dir) throws Exception
        {
            Configuration.getDefaultApiClient();
            previous = JSON.getGson();
            OkHttpClient base = new OkHttpClient.Builder()
                .dns(host -> { throw new AssertionError("No network or DNS allowed"); })
                .addInterceptor(chain -> {
                    policyPasses.incrementAndGet();
                    if (ScarletAuditRequestPolicy.isAuditRead(chain.request())) auditPasses.incrementAndGet();
                    return chain.proceed(chain.request());
                })
                .addInterceptor(policy)
                .addInterceptor(chain -> {
                    requests.add(chain.request());
                    assertEquals("startup-follow.invalid", chain.request().url().host());
                    assertEquals(5_000, chain.connectTimeoutMillis());
                    assertEquals(5_000, chain.readTimeoutMillis());
                    assertEquals(5_000, chain.writeTimeoutMillis());
                    assertEquals(TimeUnit.SECONDS.toNanos(5), chain.call().timeout().timeoutNanos());
                    duringResponse.run();
                    return ScarletIdleInstanceTransportTest.response(chain.request(), status, body).newBuilder()
                        .header("Retry-After", "0").header("Location", "https://must-not-follow.invalid/").build();
                }).build();
            client = new ApiClient(base).setBasePath("https://startup-follow.invalid/api/1");
            ScarletVRChat.initJson();
            put(vrc, "client", client);
            put(vrc, "cookies", new ScarletVRChatCookieJar(dir.resolve("absent-cookies").toFile(), new ScarletVRChatCookieJar.Stores() {
                public String primary() { return null; }
                public void primary(String value) { fail("No credential writes"); }
                public Map<String, List<String>> alternates() { return new HashMap<>(); }
                public void alternates(Map<String, List<String>> value) { fail("No alternate writes"); }
                public void removeLoginValue(String key) { fail("No credential removal"); }
                public boolean persistOnShutdown() { return false; }
            }));
            vrc.groupId = GROUP;
            vrc.currentUserId = SELF;
            ExtendedUserAgent.setCurrentUserId(SELF);
        }

        Object read(Read kind, BooleanSupplier allowed) throws IOException
        {
            if (kind == Read.ACTIVE) return vrc.getStartupGroupInstances(GROUP, allowed);
            if (kind == Read.AUDIT) return vrc.getStartupInstanceCreationPage(GROUP, FROM, TO, 0, allowed);
            return vrc.getStartupInstanceSecureName(GROUP, LOCATION, allowed);
        }

        void validBody(Read kind)
        {
            body = kind == Read.ACTIVE ? "[{\"location\":\"" + LOCATION + "\"}]"
                : kind == Read.AUDIT ? page(false, 1) : "{\"secureName\":\"synthetic-secure\"}";
        }

        public void close()
        {
            JSON.setGson(previous);
            ExtendedUserAgent.setCurrentUserId(null);
            client.getHttpClient().connectionPool().evictAll();
            client.getHttpClient().dispatcher().executorService().shutdownNow();
        }
    }

    @Test void realRequestPathsKeepLiteralInstanceTagsAndBoundedCreationQuery() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            f.validBody(Read.ACTIVE);
            assertEquals(Collections.singletonList(LOCATION), f.vrc.getStartupGroupInstances(GROUP, () -> true));
            f.body = page(true, 1);
            ScarletStartupInstanceJoin.AuditPage audit = f.vrc.getStartupInstanceCreationPage(GROUP, FROM, TO, 100, () -> true);
            assertTrue(audit.hasNext);
            assertEquals(1, audit.entries.size());
            GroupAuditLogEntryUnknown entry = audit.entries.get(0);
            assertEquals("group.instance.create", entry.getEventType());
            assertEquals(GROUP, entry.getGroupId());
            assertEquals(LOCATION, entry.getTargetId());
            assertEquals(OffsetDateTime.parse("2026-10-23T11:00:00Z"), entry.getCreatedAt());
            assertEquals("preserved", ScarletAuditData.json(entry.getData()).get("futureField").getAsString());
            f.body = "{\"secureName\":\"synthetic-secure\",\"shortName\":\"fallback\"}";
            assertEquals("synthetic-secure", f.vrc.getStartupInstanceSecureName(GROUP, LOCATION, () -> true));
            assertEquals(3, f.policyPasses.get());
            assertEquals(1, f.auditPasses.get());
            assertTrue(f.client.getHttpClient().interceptors().contains(f.policy));
            for (Request request : f.requests) { assertEquals("GET", request.method()); assertNull(request.body()); }
            assertEquals("/api/1/groups/" + GROUP + "/instances", f.requests.get(0).url().encodedPath());
            assertNull(f.requests.get(0).url().query());
            Request request = f.requests.get(1);
            assertEquals("/api/1/groups/" + GROUP + "/auditLogs", request.url().encodedPath());
            assertEquals("100", request.url().queryParameter("n"));
            assertEquals("100", request.url().queryParameter("offset"));
            assertEquals(FROM, OffsetDateTime.parse(request.url().queryParameter("startDate")));
            assertEquals(TO, OffsetDateTime.parse(request.url().queryParameter("endDate")));
            assertEquals("group.instance.create", request.url().queryParameter("eventTypes"));
            assertEquals(5, request.url().querySize());
            assertEquals("/api/1/instances/" + LOCATION + "/shortName", f.requests.get(2).url().encodedPath());
            assertNull(f.requests.get(2).url().query());
        }
    }

    @Test void sharedPacingHandles429WithoutRetryingOrLeakingResponse() throws Exception
    {
        for (Read kind : Read.values()) try (Fixture f = new Fixture(temp.resolve(kind.name())))
        {
            f.status = 429;
            f.body = "{\"secret\":\"DO-NOT-LOG-SYNTHETIC\"}";
            IOException failure = assertThrows(IOException.class, () -> f.read(kind, () -> true));
            assertEquals(1, f.requests.size());
            assertEquals(1, f.policyPasses.get());
            assertTrue(f.global.isBackingOff());
            assertTrue(f.penalty.get() >= 30_000L);
            assertFalse(failure.toString().contains("DO-NOT-LOG"));
        }
    }

    @Test void failedStatusesMalformedTrailingAndOversizedBodiesNeverRetry() throws Exception
    {
        for (Read kind : Read.values())
            for (String bad : new String[]{"403", "503", "302", "401", "empty", "malformed", "trailing", "oversize"})
                try (Fixture f = new Fixture(temp.resolve(kind.name() + bad)))
                {
                    f.validBody(kind);
                    if (bad.matches("[0-9]+")) f.status = Integer.parseInt(bad);
                    if (bad.equals("empty")) f.body = "";
                    if (bad.equals("malformed")) f.body = "{";
                    if (bad.equals("trailing")) f.body += " {}";
                    if (bad.equals("oversize")) f.body = new String(new char[2 * 1024 * 1024 + 1]);
                    assertThrows(IOException.class, () -> f.read(kind, () -> true), kind + bad);
                    assertEquals(1, f.requests.size(), kind + bad);
                }
    }

    @Test void disabledChangedIdentityAndAuthenticationContextsNeverDispatch() throws Exception
    {
        for (Read kind : Read.values())
            for (String invalid : new String[]{"disabled", "group", "user", "pending", "authentication", "alternate", "temporary", "stopped"})
                try (Fixture f = new Fixture(temp.resolve(kind.name() + invalid)))
                {
                    if (invalid.equals("group")) f.vrc.groupId = ScarletIdleInstanceCloseTest.OTHER_GROUP;
                    if (invalid.equals("user")) f.vrc.currentUserId = null;
                    if (invalid.equals("pending")) f.vrc.pendingUsername = "synthetic-pending";
                    if (invalid.equals("authentication")) ExtendedUserAgent.beginPrimaryAuthentication();
                    if (invalid.equals("alternate")) ScarletVRChatCookieJar._context.set("synthetic-alternate");
                    if (invalid.equals("temporary")) ScarletVRChatCookieJar._context2.set(new ArrayList<>());
                    if (invalid.equals("stopped")) put(f.vrc, "snapshotsStopped", true);
                    try
                    {
                        assertThrows(IOException.class, () -> f.read(kind, () -> !invalid.equals("disabled")), kind + invalid);
                        assertTrue(f.requests.isEmpty(), kind + invalid);
                    }
                    finally
                    {
                        ScarletVRChatCookieJar._context.remove();
                        ScarletVRChatCookieJar._context2.remove();
                    }
                }
    }

    @Test void lateCancellationAndIdentityChangesDiscardAlreadyReadResults() throws Exception
    {
        for (Read kind : Read.values())
            for (String invalid : new String[]{"disabled", "group", "user", "authentication"})
                try (Fixture f = new Fixture(temp.resolve(kind.name() + invalid)))
                {
                    f.validBody(kind);
                    AtomicBoolean allowed = new AtomicBoolean(true);
                    f.duringResponse = () -> {
                        if (invalid.equals("disabled")) allowed.set(false);
                        if (invalid.equals("group")) f.vrc.groupId = ScarletIdleInstanceCloseTest.OTHER_GROUP;
                        if (invalid.equals("user")) f.vrc.currentUserId = ScarletIdleInstanceCloseTest.OTHER;
                        if (invalid.equals("authentication")) ExtendedUserAgent.beginPrimaryAuthentication();
                    };
                    assertThrows(IOException.class, () -> f.read(kind, allowed::get), kind + invalid);
                    assertEquals(1, f.requests.size());
                }
    }

    @Test void structurallyInvalidListsAndIncompleteAuditPagesFailClosed() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            for (String bad : new String[]{"null", "{}", "[null]", "[{}]", "[{\"location\":null}]", "[{\"location\":42}]"})
            {
                f.body = bad;
                assertThrows(IOException.class, () -> f.read(Read.ACTIVE, () -> true), bad);
            }
            for (String bad : new String[]{"null", "[]", "{}", "{\"hasNext\":false}",
                "{\"hasNext\":\"false\",\"results\":[]}", "{\"hasNext\":false,\"results\":null}", page(false, 101)})
            {
                f.body = bad;
                assertThrows(IOException.class, () -> f.read(Read.AUDIT, () -> true), bad);
            }
            f.body = page(false, 1).replace("2026-10-23T11:00:00Z", "not-a-date");
            assertThrows(IOException.class, () -> f.read(Read.AUDIT, () -> true));
            f.body = page(false, 0);
            assertTrue(f.vrc.getStartupInstanceCreationPage(GROUP, FROM, TO, 0, () -> true).entries.isEmpty());
        }
    }

    @Test void invalidArgumentsDoNotMakeRequestsAndSecureNameRequiresUsableString() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            for (int offset : new int[]{-1, 1, 99, 101, 500, 600})
                assertThrows(IOException.class, () -> f.vrc.getStartupInstanceCreationPage(GROUP, FROM, TO, offset, () -> true));
            assertThrows(IOException.class, () -> f.vrc.getStartupInstanceCreationPage(GROUP, null, TO, 0, () -> true));
            assertThrows(IOException.class, () -> f.vrc.getStartupInstanceCreationPage(GROUP, FROM, null, 0, () -> true));
            assertThrows(IOException.class, () -> f.vrc.getStartupGroupInstances("../invalid", () -> true));
            assertThrows(IOException.class, () -> f.vrc.getStartupInstanceSecureName(GROUP,
                LOCATION.replace(GROUP, ScarletIdleInstanceCloseTest.OTHER_GROUP), () -> true));
            assertTrue(f.requests.isEmpty());
            for (String missing : new String[]{"null", "[]", "{}", "{\"secureName\":null}", "{\"secureName\":false}", "{\"secureName\":\" \"}"})
            {
                f.body = missing;
                assertNull(f.vrc.getStartupInstanceSecureName(GROUP, LOCATION, () -> true));
            }
            f.body = "{\"secureName\":\" \",\"shortName\":\"synthetic-fallback\"}";
            assertEquals("synthetic-fallback", f.vrc.getStartupInstanceSecureName(GROUP, LOCATION, () -> true));
        }
    }

    @Test void derivedClientKeepsExistingPacingButDisablesAutomaticHttpFollowups()
    {
        Interceptor pace = chain -> { throw new AssertionError("No transport should execute"); };
        OkHttpClient base = new OkHttpClient.Builder().addInterceptor(pace).build();
        OkHttpClient guarded = ScarletVRChat.idleCloseHttpClient(base, Collections.emptyList(), () -> true);
        assertSame(pace, guarded.interceptors().get(0));
        assertFalse(guarded.retryOnConnectionFailure());
        assertFalse(guarded.followRedirects());
        assertFalse(guarded.followSslRedirects());
        assertSame(okhttp3.Authenticator.NONE, guarded.authenticator());
        assertSame(okhttp3.Authenticator.NONE, guarded.proxyAuthenticator());
        assertNull(guarded.cache());
        assertEquals(5_000, guarded.callTimeoutMillis());
    }

    @Test void ageGatedSecureLookupPreservesBareFlagAcrossEveryGroupAccessType() throws Exception
    {
        for (String access : new String[]{"members", "plus", "public"})
            for (String tags : new String[]{"~ageGate~region(us)", "~region(us)~ageGate"})
                try (Fixture f = new Fixture(temp.resolve(access + (tags.startsWith("~ageGate") ? "first" : "last"))))
                {
                    String location = groupLocation(access) + tags;
                    assertNotNull(ScarletCloseInstanceInput.parse(location, GROUP));
                    f.body = "{\"secureName\":\"synthetic-age-gated-token\"}";
                    assertEquals("synthetic-age-gated-token", f.vrc.getStartupInstanceSecureName(GROUP, location, () -> true));
                    assertEquals(1, f.requests.size());
                    Request request = f.requests.get(0);
                    assertEquals("/api/1/instances/" + location + "/shortName", request.url().encodedPath());
                    assertNull(request.url().query());
                    assertEquals("GET", request.method());
                    assertEquals(1, f.policyPasses.get());
                }
    }

    @Test void ordinaryGroupSecureLookupStillWorksAcrossEveryGroupAccessType() throws Exception
    {
        for (String access : new String[]{"members", "plus", "public"})
            try (Fixture f = new Fixture(temp.resolve(access)))
            {
                String location = groupLocation(access) + "~region(eu)";
                f.body = "{\"secureName\":\"synthetic-ordinary-token\"}";
                assertEquals("synthetic-ordinary-token", f.vrc.getStartupInstanceSecureName(GROUP, location, () -> true));
                assertEquals(1, f.requests.size());
                assertEquals("/api/1/instances/" + location + "/shortName", f.requests.get(0).url().encodedPath());
            }
    }

    @Test void wrongGroupMalformedAgeGateAndNonLocationInputsFailBeforeHttp() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            String gated = groupLocation("members") + "~ageGate~region(us)";
            String[] bad = {
                null, "", "offline", gated.replace(GROUP, ScarletIdleInstanceCloseTest.OTHER_GROUP),
                gated.replace("groupAccessType(members)", "groupAccessType(unknown)"),
                gated + "~ageGate", gated.replace("~ageGate", "~ageGate("),
                gated + "/../other", gated + "?shortName=injected", gated + "#fragment",
                " " + gated, gated + " ", "<" + gated + ">",
                "https://vrchat.com/home/launch?worldId=" + gated.replace(":", "&instanceId=")
            };
            for (String location : bad)
            {
                assertThrows(IOException.class, () -> f.vrc.getStartupInstanceSecureName(GROUP, location, () -> true),
                    "Raw startup locations must stay strict; malformed input must not become an HTTP path");
                assertTrue(f.requests.isEmpty(), "Rejected startup location made a request");
            }
        }
    }

    @Test void actualStartupSelectionCanReachRealSecureLookupForAgeGatedInstances() throws Exception
    {
        for (String access : new String[]{"members", "plus", "public"})
            try (Fixture f = new Fixture(temp.resolve(access)))
            {
                String location = groupLocation(access) + "~ageGate~region(us)";
                installStartupResponses(f, activeList(location), null, location);
                TransportJoinEnvironment environment = new TransportJoinEnvironment(f);
                ScarletStartupInstanceJoin startup = new ScarletStartupInstanceJoin(GROUP, TO.minusSeconds(10));
                startup.attempt(environment, TO.toInstant().toEpochMilli());
                assertFalse(startup.isPending(), "Accepted age-gated selection must not fail the secure-name validator afterward");
                assertEquals(1, environment.handoffs.get());
                assertEquals(location, environment.selected);
                assertEquals("synthetic-pipeline-token", environment.secureName);
                assertEquals(3, f.requests.size(), "Two active-list reads and one secure-name read; no history query needed");
                assertEquals(0, f.auditPasses.get());
                assertTrue(environment.logs.stream().noneMatch(message -> message.contains("synthetic-pipeline-token")));
                startup.attempt(environment, TO.plusMinutes(2).toInstant().toEpochMilli());
                assertEquals(1, environment.handoffs.get(), "A completed lookup must not repeat its launch hand-off");
                assertEquals(3, f.requests.size());
            }
    }

    @Test void actualNewestSelectionAndAuditTransportPreserveAgeGateThroughSecureLookup() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            String older = LOCATION;
            String newest = LOCATION.replace(":12345~", ":98765~")
                .replace("groupAccessType(members)", "groupAccessType(plus)") + "~ageGate~region(eu)";
            JsonObject oldCreation = creation(), newCreation = creation();
            oldCreation.addProperty("targetId", older);
            oldCreation.addProperty("created_at", TO.minusHours(2).toString());
            newCreation.addProperty("targetId", newest);
            newCreation.addProperty("created_at", TO.minusMinutes(1).toString());
            JsonArray entries = new JsonArray();
            entries.add(newCreation);
            entries.add(oldCreation);
            JsonObject history = new JsonObject();
            history.addProperty("hasNext", false);
            history.add("results", entries);
            installStartupResponses(f, activeList(older, newest), history.toString(), newest);
            TransportJoinEnvironment environment = new TransportJoinEnvironment(f);
            ScarletStartupInstanceJoin startup = new ScarletStartupInstanceJoin(GROUP, TO.minusSeconds(10));
            startup.attempt(environment, TO.toInstant().toEpochMilli());
            assertFalse(startup.isPending());
            assertEquals(1, environment.handoffs.get());
            assertEquals(newest, environment.selected);
            assertEquals("synthetic-pipeline-token", environment.secureName);
            assertEquals(4, f.requests.size(), "Active list, creation page, active-list recheck, secure name");
            assertEquals(1, f.auditPasses.get());
            assertEquals("group.instance.create", f.requests.get(1).url().queryParameter("eventTypes"));
            assertEquals("/api/1/instances/" + newest + "/shortName", f.requests.get(3).url().encodedPath());
        }
    }

    private static String groupLocation(String access)
    {
        return LOCATION.replace("groupAccessType(members)", "groupAccessType(" + access + ")");
    }

    private static String activeList(String... locations)
    {
        JsonArray instances = new JsonArray();
        for (String location : locations)
        {
            JsonObject instance = new JsonObject();
            instance.addProperty("location", location);
            instances.add(instance);
        }
        return instances.toString();
    }

    private static void installStartupResponses(Fixture fixture, String active, String history, String selected)
    {
        fixture.duringResponse = () -> {
            String path = fixture.requests.get(fixture.requests.size() - 1).url().encodedPath();
            if (path.equals("/api/1/groups/" + GROUP + "/instances"))
                fixture.body = active;
            else if (path.equals("/api/1/groups/" + GROUP + "/auditLogs") && history != null)
                fixture.body = history;
            else if (path.equals("/api/1/instances/" + selected + "/shortName"))
                fixture.body = "{\"secureName\":\"synthetic-pipeline-token\"}";
            else
                fail("Unexpected synthetic startup request path");
        };
    }

    /** Uses the actual startup coordinator and HTTP readers; only the final OS hand-off is inert. */
    private static final class TransportJoinEnvironment implements ScarletStartupInstanceJoin.Environment
    {
        final Fixture fixture;
        final AtomicInteger handoffs = new AtomicInteger();
        final List<String> logs = new ArrayList<>();
        String selected, secureName;

        TransportJoinEnvironment(Fixture fixture) { this.fixture = fixture; }
        public boolean allowed() { return true; }
        public boolean clientRunning() { return false; }
        public List<String> activeLocations() throws Exception
        {
            return this.fixture.vrc.getStartupGroupInstances(GROUP, this::allowed);
        }
        public ScarletStartupInstanceJoin.AuditPage creationPage(OffsetDateTime from, OffsetDateTime to, int offset) throws Exception
        {
            return this.fixture.vrc.getStartupInstanceCreationPage(GROUP, from, to, offset, this::allowed);
        }
        public void launch(String location) throws Exception
        {
            String token = this.fixture.vrc.getStartupInstanceSecureName(GROUP, location, this::allowed);
            if (token == null) throw new IOException("Synthetic secure name absent");
            this.selected = location;
            this.secureName = token;
            this.handoffs.incrementAndGet();
        }
        public void log(String message) { this.logs.add(message); }
        public long nowMillis() { return TO.toInstant().toEpochMilli(); }
    }
}

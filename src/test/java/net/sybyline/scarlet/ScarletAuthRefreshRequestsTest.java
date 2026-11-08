package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;
import java.time.OffsetDateTime;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ScarletAuthRefreshRequestsTest
{
    static class Time extends ScarletSettings.RegistryOffsetDateTime
    {
        OffsetDateTime value = OffsetDateTime.now();
        Time(ScarletSettings settings) { settings.super("synthetic-refresh-time"); }
        @Override public OffsetDateTime getOrSupply() { return this.value; }
        @Override public void set(OffsetDateTime value) { this.value = value; }
    }
    static class Client extends ScarletVRChat
    {
        int calls;
        Runnable during;
        Client() { super(null, null, null); throw new AssertionError("No real client"); }
        @Override public void refresh() { this.calls++; if (this.during != null) this.during.run(); }
    }
    static class Fixture
    {
        final Scarlet app = inert(Scarlet.class);
        final ScarletSettings settings = inert(ScarletSettings.class);
        final Time time = new Time(this.settings);
        final Client client = inert(Client.class);
        Fixture() throws Exception
        {
            put(this.settings, "lastAuthRefresh", this.time);
            put(this.app, "settings", this.settings);
            put(this.app, "vrc", this.client);

        }
    }
    @Test void noRequestAndRecentAuthenticationDoesNotRefresh() throws Exception
    {
        Fixture f = new Fixture();
        f.app.maybeRefresh();
        assertEquals(0, f.client.calls);
    }
    @Test void queuedRequestsCoalesceWithoutAnImmediateRetryLoop() throws Exception
    {
        Fixture f = new Fixture();
        for (int i = 0; i < 100; i++) f.app.queueVrcRefresh();
        f.app.maybeRefresh();
        f.app.maybeRefresh();
        assertEquals(1, f.client.calls);
        f.app.queueVrcRefresh();
        f.app.maybeRefresh();
        assertEquals(2, f.client.calls);
    }
    @Test void requestsDuringInFlightRefreshSurviveAndCoalesce() throws Exception
    {
        Fixture f = new Fixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.client.during = () -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> running = null;
        try
        {
            f.app.queueVrcRefresh();
            running = worker.submit(f.app::maybeRefresh);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) f.app.queueVrcRefresh();
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
            f.client.during = null;
            assertEquals(1, f.client.calls);
            f.app.maybeRefresh();
            assertEquals(2, f.client.calls, "One later pass must retain the requests raised in flight");
            f.app.maybeRefresh();
            assertEquals(2, f.client.calls, "Requests must remain coalesced");
        }
        finally
        {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    @Test void periodicRefreshAlsoRetainsNewRequests() throws Exception
    {
        Fixture f = new Fixture();
        f.time.value = OffsetDateTime.now().minusHours(73);
        f.client.during = f.app::queueVrcRefresh;
        f.app.maybeRefresh();
        f.client.during = null;
        f.app.maybeRefresh();
        f.app.maybeRefresh();
        assertEquals(2, f.client.calls);
        assertTrue(f.time.value.isAfter(OffsetDateTime.now().minusMinutes(1)));
    }
    @Test void failedRefreshLeavesARequestForSubsequentRecovery() throws Exception
    {
        Fixture f = new Fixture();
        f.client.during = () -> { throw new IllegalStateException("synthetic failure"); };
        f.app.queueVrcRefresh();
        assertThrows(IllegalStateException.class, f.app::maybeRefresh);
        f.client.during = null;
        f.app.maybeRefresh();
        f.app.maybeRefresh();
        assertEquals(2, f.client.calls);
    }
}

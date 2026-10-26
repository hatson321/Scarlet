package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletClientExitMonitorTest.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.vrchatapi.model.Instance;

/** Fake processes, clocks, API responses and notices. No Discord, live game or credentials. */
class ScarletExitOperatorTest
{
    static long seconds(long n) { return TimeUnit.SECONDS.toNanos(n); }
    static final class Scenario
    {
        final Fixture f = new Fixture();
        final List<ScarletExitEscalation.Notice> notices = new ArrayList<>();
        Scenario() { f.monitor.setOperatorAlerts(notices::add); f.response = closed().nUsers(2); f.arm(); }
        void at(long seconds) { f.nanos.set(seconds(seconds)); f.monitor.tick(); }
        String notice() { at(300); assertEquals(1, notices.size()); return notices.get(0).token; }
    }

    @Test void catchupOccursAfterSlowProcessInventory()
    {
        Fixture f = new Fixture(); f.arm();
        f.duringCurrent = () -> f.healthy.set(false);
        f.settle = () -> f.healthy.set(true);
        f.due(); assertEquals(1, f.requested.get());
        assertFalse(f.diagnostics.contains("log-tracking-not-healthy"));
    }

    @Test void processInventoryNeverHoldsRosterLock() throws Exception
    {
        Fixture f = new Fixture(); f.arm();
        ExecutorService parser = Executors.newSingleThreadExecutor();
        try
        {
            f.duringCurrent = () -> {
                try { parser.submit(() -> f.monitor.playerJoined(OTHER)).get(2, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new AssertionError("Inventory blocked roster observation", ex); }
            };
            f.due(); assertEquals(0, f.queries.get()); assertEquals(0, f.closeCalls.get());
        }
        finally { parser.shutdownNow(); }
    }

    @Test void sessionChangeDuringInventoryCancelsOldCheck()
    {
        Fixture f = new Fixture(); f.arm(); f.duringCurrent = f::live;
        f.due(); assertEquals(0, f.queries.get()); assertEquals(0, f.closeCalls.get());
    }

    @Test void alertRequiresFiveMinutesAndNeverClosesAutomatically()
    {
        Scenario s = new Scenario(); s.at(299); assertTrue(s.notices.isEmpty());
        s.at(359); assertEquals(1, s.notices.size());
        assertEquals(0, s.f.closeCalls.get());
        s.at(419); s.at(479); assertEquals(1, s.notices.size());
        assertTrue(s.notices.get(0).counts.contains("n_users=2"));
        assertFalse(s.notices.get(0).counts.contains(LOCATION));
    }

    @Test void authorizedCurrentNoticeAllowsOnlyNormalClientExitOnce()
    {
        Scenario s = new Scenario(); String token = s.notice();
        String result = s.f.monitor.operatorExit(token, () -> true);
        assertTrue(result.startsWith("Normal exit requested"), result);
        assertEquals(1, s.f.requested.get());
        s.f.monitor.operatorExit(token, () -> true); s.at(500);
        assertEquals(1, s.f.closeCalls.get());
    }

    @Test void unauthorizedAttemptCannotConsumeNoticeOrQuery()
    {
        Scenario s = new Scenario(); String token = s.notice(); int queries = s.f.queries.get();
        s.f.monitor.operatorExit(token, () -> false);
        assertEquals(queries, s.f.queries.get()); assertEquals(0, s.f.closeCalls.get());
        assertTrue(s.f.monitor.operatorNoticeValid(token));
    }

    @Test void permissionLossBeforeFinalCloseCancels()
    {
        Scenario s = new Scenario(); String token = s.notice();
        java.util.concurrent.atomic.AtomicBoolean permitted = new java.util.concurrent.atomic.AtomicBoolean(true);
        s.f.beforeClose = () -> permitted.set(false);
        s.f.monitor.operatorExit(token, permitted::get);
        assertEquals(0, s.f.requested.get());
    }

    @Test void playerArrivalAndDepartureInvalidateOldNotice()
    {
        Scenario s = new Scenario(); String token = s.notice();
        s.f.monitor.playerJoined(OTHER); s.f.monitor.playerLeft(OTHER);
        assertFalse(s.f.monitor.operatorNoticeValid(token));
        s.f.monitor.operatorExit(token, () -> true); assertEquals(0, s.f.closeCalls.get());
    }

    @Test void expiredAndRestartedNoticesCannotBeUsed()
    {
        Scenario s = new Scenario(); String token = s.notice(); s.f.nanos.set(seconds(900));
        assertFalse(s.f.monitor.operatorNoticeValid(token));
        s.f.monitor.operatorExit(token, () -> true); assertEquals(0, s.f.closeCalls.get());
        Scenario fresh = new Scenario(); fresh.notice();
        fresh.f.monitor.operatorExit(token, () -> true); assertEquals(0, fresh.f.closeCalls.get());
    }

    @ParameterizedTest(name = "operator retains: {0}")
    @MethodSource("net.sybyline.scarlet.ScarletClientExitMonitorTest#invalidServerStates")
    void operatorCannotBypassNonOccupancyServerFailures(String name, UnaryOperator<Instance> mutate)
    {
        Scenario s = new Scenario(); String token = s.notice(); s.f.response = mutate.apply(closed());
        boolean occupancy = name.equals("nUsers occupied") || name.equals("userCount occupied")
            || name.equals("two reported users") || name.equals("different sole reported user");
        s.f.monitor.operatorExit(token, () -> true);
        assertEquals(occupancy ? 1 : 0, s.f.requested.get());
    }

    @Test void manualExitRetainsHealthModeProcessAndGraceChecks()
    {
        for (int kind = 0; kind < 4; kind++)
        {
            Scenario s = new Scenario(); String token = s.notice();
            if (kind == 0) s.f.healthy.set(false);
            if (kind == 1) s.f.enabled.set(false);
            if (kind == 2) s.f.current.set(null);
            if (kind == 3) { s.f.healthy.set(false); s.at(305); s.f.healthy.set(true); }
            s.f.monitor.operatorExit(token, () -> true); assertEquals(0, s.f.closeCalls.get());
        }
    }

    @Test void playerArrivingDuringManualServerReadCancels()
    {
        Scenario s = new Scenario(); String token = s.notice(); s.f.duringQuery = () -> s.f.monitor.playerJoined(OTHER);
        s.f.monitor.operatorExit(token, () -> true); assertEquals(0, s.f.closeCalls.get());
    }

    @Test void staleManualServerReadCannotClose()
    {
        Scenario s = new Scenario(); String token = s.notice(); s.f.duringQuery = () -> s.f.nanos.addAndGet(seconds(11));
        s.f.monitor.operatorExit(token, () -> true); assertEquals(0, s.f.closeCalls.get());
    }

    @Test void changedProcessReopenedInstanceOrNewSessionCannotUseNotice()
    {
        for (int kind = 0; kind < 3; kind++)
        {
            Scenario s = new Scenario(); String token = s.notice();
            if (kind == 0) s.f.current.set(client(LOCATION, START.minusSeconds(30), START.minusSeconds(20)));
            if (kind == 1) s.f.monitor.audit(event("group.instance.create", CLOSED.plusSeconds(1)));
            if (kind == 2) s.f.live();
            s.f.monitor.operatorExit(token, () -> true); assertEquals(0, s.f.closeCalls.get());
        }
    }

    @Test void throwingNoticeDeliveryCannotGrantExit()
    {
        Scenario s = new Scenario(); s.f.monitor.setOperatorAlerts(notice -> { throw new IllegalStateException(); });
        s.at(300); assertEquals(0, s.f.closeCalls.get());
        assertTrue(s.f.diagnostics.contains("operator-alert-delivery-unavailable"));
    }

    @Test void finalPermissionFetchDoesNotBlockNewPlayerObservation() throws Exception
    {
        Scenario s = new Scenario(); String token = s.notice();
        ExecutorService parser = Executors.newSingleThreadExecutor();
        try {
            s.f.monitor.operatorExit(token, () -> true, () -> {
                try { parser.submit(() -> s.f.monitor.playerJoined(OTHER)).get(2, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new AssertionError("Authorization blocked live roster", ex); }
            });
            assertEquals(0, s.f.closeCalls.get());
        }
        finally { parser.shutdownNow(); }
    }

    @Test void slowPermissionRefreshCannotReuseStaleServerProof()
    {
        Scenario s = new Scenario(); String token = s.notice();
        s.f.monitor.operatorExit(token, () -> true, () -> s.f.nanos.addAndGet(seconds(11)));
        assertEquals(0, s.f.closeCalls.get());
    }

    @Test void unhealthyTrackingDuringAlertCheckRestartsFullGrace()
    {
        Scenario s = new Scenario(); s.f.duringQuery = () -> s.f.healthy.set(false); s.at(300);
        assertTrue(s.notices.isEmpty()); s.f.duringQuery = () -> {}; s.f.healthy.set(true);
        s.at(360); assertTrue(s.notices.isEmpty());
        s.at(389); assertTrue(s.notices.isEmpty()); s.at(390); assertEquals(1, s.notices.size());
    }

    @Test void nonOccupancyFailureDoesNotOfferOverride()
    {
        Scenario s = new Scenario(); s.f.response = closed().nUsers(2).closedAt(null); s.at(300);
        assertTrue(s.notices.isEmpty()); assertEquals(0, s.f.closeCalls.get());
    }

    @Test void confirmationIsBoundToUserGuildChannelAndSingleUse()
    {
        ScarletExitOperatorDiscord.Confirmations c = new ScarletExitOperatorDiscord.Confirmations();
        String t = c.create("notice", "user", "guild", "channel", 1);
        assertNull(c.consume(t, "other", "guild", "channel", 2));
        assertNull(c.consume(t, "user", "other", "channel", 2));
        assertNull(c.consume(t, "user", "guild", "other", 2));
        assertEquals("notice", c.consume(t, "user", "guild", "channel", 2));
        assertNull(c.consume(t, "user", "guild", "channel", 3));
    }

    @Test void confirmationsExpireAndAreBounded()
    {
        ScarletExitOperatorDiscord.Confirmations c = new ScarletExitOperatorDiscord.Confirmations();
        String t = c.create("notice", "user", "guild", "channel", 1);
        assertNull(c.consume(t, "user", "guild", "channel", 1 + seconds(60)));
        for (int i = 0; i < 64; i++) assertNotNull(c.create("n", "u", "g", "c", 2));
        assertNull(c.create("n", "u", "g", "c", 2));
        assertNotNull(c.create("n", "u", "g", "c", 2 + seconds(60)));
    }
}

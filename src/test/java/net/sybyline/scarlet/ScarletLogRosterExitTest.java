package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletClientExitMonitorTest.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.vrchatapi.model.Instance;
import net.sybyline.scarlet.ext.ManagedVrcClient;
import net.sybyline.scarlet.ext.ManagedVrcClient.Client;
import net.sybyline.scarlet.ext.ManagedVrcClient.CloseResult;

/** Synthetic observations and fake transports only. Never reads game logs or closes real clients. */
class ScarletLogRosterExitTest
{
    static Fixture logs()
    {
        Fixture f = new Fixture(ScarletClientExitMonitor.EvidenceMode.LIVE_LOG_ROSTER);
        f.duringQuery = () -> fail("Log-authoritative client exit must not request server state");
        f.monitor.setOperatorAlerts(notice -> fail("No API-occupancy conflict exists in this mode"));
        return f;
    }

    @Test void exactClosedSoleSelfSessionExitsAfterFullGraceWithoutApi()
    {
        Fixture f = logs(); f.arm(); f.nanos.set(WAIT - 1); f.monitor.tick();
        assertEquals(0, f.closeCalls.get()); f.nanos.incrementAndGet(); f.monitor.tick();
        assertEquals(1, f.requested.get()); assertEquals(0, f.queries.get());
        assertTrue(f.diagnostics.contains("exit-evidence-live-output-log-roster-no-server-occupancy-query"));
        f.evidence(); f.unchangedTicks(); assertEquals(1, f.closeCalls.get());
    }

    @Test void productionStyleConstructionNeedsNoApiTransportEvenForForceFallback()
    {
        Fixture f = logs();
        ScarletClientExitMonitor monitor = new ScarletClientExitMonitor(f.enabled::get, f.group::get, f.healthy::get,
            f, null, f.result::set, f.clock::get, f.nanos::get, f.diagnostics::add, () -> {}, () -> "synthetic",
            ScarletClientExitMonitor.EvidenceMode.LIVE_LOG_ROSTER);
        monitor.setForceCloseEnabled(() -> true);
        monitor.newLog(); monitor.authenticated(SELF); monitor.joined(LOCATION, START, false);
        monitor.playerJoined(SELF); monitor.audit(event("group.instance.close", CLOSED)); monitor.tick();
        f.nanos.set(WAIT); monitor.tick(); assertEquals(1, f.requested.get());
        f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(15)); monitor.tick();
        assertEquals(1, f.forced.get()); assertEquals(0, f.queries.get());
    }

    @ParameterizedTest @MethodSource("net.sybyline.scarlet.ScarletClientExitMonitorTest#invalidServerStates")
    void unavailableOrInconsistentApiIsNotReadForNormalOrForceExit(String name, UnaryOperator<Instance> mutate)
    {
        Fixture f = logs(); f.response = mutate.apply(closed()); f.monitor.setForceCloseEnabled(() -> true);
        f.arm(); f.due(); assertEquals(1, f.requested.get(), name);
        f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(15)); f.monitor.tick();
        assertEquals(1, f.forced.get(), name); assertEquals(0, f.queries.get());
    }

    @Test void missingCloseAuditOrReopenedInstanceNeverExits()
    {
        Fixture open = logs(); open.live(); open.monitor.tick(); open.due(); open.unchangedTicks();
        assertEquals(0, open.closeCalls.get());
        Fixture reopened = logs(); reopened.arm(); reopened.monitor.audit(event("group.instance.create", CLOSED.plusSeconds(1)));
        reopened.due(); reopened.unchangedTicks(); assertEquals(0, reopened.closeCalls.get());
        Fixture old = logs(); old.live(); old.monitor.audit(event("group.instance.close", START.minusSeconds(1)));
        old.monitor.tick(); old.due(); assertEquals(0, old.closeCalls.get());
    }

    @Test void occupiedOrUnknownSelfIsNotEmpty()
    {
        for (int kind = 0; kind < 4; kind++)
        {
            Fixture f = logs(); f.live();
            if (kind == 0) f.monitor.playerJoined(OTHER);
            if (kind == 1) f.monitor.playerLeft(SELF);
            if (kind == 2) f.monitor.authenticated(null);
            if (kind == 3) f.monitor.playerJoined("invalid-id");
            f.evidence(); f.monitor.tick(); f.due(); f.unchangedTicks(); assertEquals(0, f.closeCalls.get());
        }
    }

    @ParameterizedTest @MethodSource("net.sybyline.scarlet.ScarletClientExitMonitorTest#mismatchedProcesses")
    void unownedOrMismatchedProcessIsNeverClosed(String name, Client client)
    {
        Fixture f = logs(); f.current.set(client); f.arm(); f.due(); f.unchangedTicks();
        assertEquals(0, f.closeCalls.get(), name); assertEquals(0, f.queries.get());
    }

    @Test void trackingFailureRestartsFullGraceAndNeverBecomesProofOfEmptiness()
    {
        Fixture f = logs(); f.arm(); f.nanos.set(TimeUnit.SECONDS.toNanos(20));
        f.healthy.set(false); f.monitor.tick(); f.healthy.set(true); f.due();
        f.nanos.set(WAIT * 2 - 1); f.monitor.tick(); assertEquals(0, f.requested.get());
        f.nanos.incrementAndGet(); f.monitor.tick(); assertEquals(1, f.requested.get());
    }

    @Test void arrivalsDuringCatchupCancelTheAttempt()
    {
        Fixture f = logs(); f.arm(); AtomicInteger catchups = new AtomicInteger();
        f.settle = () -> { if (catchups.incrementAndGet() == 2) f.monitor.playerJoined(OTHER); };
        f.due(); assertEquals(0, f.closeCalls.get()); assertEquals(0, f.queries.get());
    }

    @Test void finalNativeBoundaryRechecksEveryLocalGuard()
    {
        for (int kind = 0; kind < 9; kind++)
        {
            Fixture f = logs(); f.arm(); final int change = kind;
            f.beforeClose = () -> invalidate(f, change);
            f.due(); assertEquals(0, f.requested.get(), "change=" + kind); assertEquals(0, f.queries.get());
        }
    }

    static void invalidate(Fixture f, int kind)
    {
        if (kind == 0) f.monitor.playerJoined(OTHER);
        if (kind == 1) f.monitor.authenticated(OTHER);
        if (kind == 2) f.monitor.newLog();
        if (kind == 3) f.group.set(OTHER_GROUP);
        if (kind == 4) f.enabled.set(false);
        if (kind == 5) f.healthy.set(false);
        if (kind == 6) f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(11));
        if (kind == 7) f.monitor.audit(event("group.instance.create", CLOSED.plusSeconds(1)));
        if (kind == 8) f.current.set(client(LOCATION, START.minusSeconds(30), START.minusSeconds(20)));
    }

    @Test void forceKeepsSeparateOptInWaitAndFreshLocalChecks()
    {
        Fixture off = logs(); off.arm(); off.due(); off.unchangedTicks(); assertEquals(0, off.forceCalls.get());
        Fixture f = logs(); f.monitor.setForceCloseEnabled(() -> true); f.arm(); f.due();
        f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(15) - 1); f.monitor.tick(); assertEquals(0, f.forceCalls.get());
        f.nanos.incrementAndGet(); f.monitor.tick(); assertEquals(1, f.forced.get());
        f.evidence(); f.unchangedTicks(); assertEquals(1, f.forceCalls.get()); assertEquals(0, f.queries.get());
        for (int kind = 0; kind < 9; kind++)
        {
            Fixture changed = logs(); changed.monitor.setForceCloseEnabled(() -> true); changed.arm(); changed.due();
            final int change = kind; changed.beforeForce = () -> invalidate(changed, change);
            changed.nanos.addAndGet(TimeUnit.SECONDS.toNanos(15)); changed.monitor.tick();
            assertEquals(0, changed.forced.get(), "change=" + kind);
        }
    }

    @Test void departedProcessOrRevokedForceSettingCannotBeForced()
    {
        for (int kind = 0; kind < 3; kind++)
        {
            Fixture f = logs(); f.monitor.setForceCloseEnabled(() -> true); f.arm(); f.due();
            if (kind == 0) f.current.set(null);
            if (kind == 1) f.monitor.setForceCloseEnabled(() -> false);
            if (kind == 2) { f.monitor.playerJoined(OTHER); f.monitor.playerLeft(OTHER); }
            f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(15)); f.monitor.tick(); assertEquals(0, f.forceCalls.get());
        }
    }

    @ParameterizedTest @EnumSource(CloseResult.class)
    void normalRetryBudgetAndTerminalOutcomesRemainBounded(CloseResult result)
    {
        Fixture f = logs(); f.closeResult = result; f.arm(); f.due();
        for (int n = 0; n < 5; n++) { f.monitor.tick(); f.nanos.addAndGet(WAIT); f.monitor.tick(); f.evidence(); }
        assertEquals(result.retryableBeforeDispatch() ? 3 : 1, f.closeCalls.get());
        assertEquals(0, f.queries.get()); assertEquals(0, f.forceCalls.get());
    }

    @ParameterizedTest @EnumSource(ManagedVrcClient.ForceResult.class)
    void forceRemainsOneShotRegardlessOfTransportResult(ManagedVrcClient.ForceResult result)
    {
        Fixture f = logs(); f.forceResult = result; f.monitor.setForceCloseEnabled(() -> true); f.arm(); f.due();
        f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(15)); f.monitor.tick(); f.unchangedTicks();
        assertEquals(1, f.forceCalls.get()); assertEquals(0, f.queries.get());
    }

    @Test void oldOperatorOverrideCannotTriggerCloseOrApiRead()
    {
        Fixture f = logs(); f.live();
        String result = f.monitor.operatorExit("synthetic-old-token", () -> { fail("No permission fetch needed for inactive action"); return false; });
        assertTrue(result.contains("No manual override applied"));
        assertEquals(0, f.closeCalls.get()); assertEquals(0, f.queries.get());
    }
}

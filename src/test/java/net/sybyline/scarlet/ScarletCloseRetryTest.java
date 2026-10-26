package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletClientExitMonitorTest.*;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.vrchatapi.model.Instance;
import net.sybyline.scarlet.ext.ManagedVrcClient.CloseResult;

/** Fake clock, process transport and server only; no game or network access. */
class ScarletCloseRetryTest
{
    static Fixture failed(CloseResult result)
    {
        Fixture f = new Fixture(); f.closeResult = result; f.arm(); f.due();
        assertEquals(1, f.closeCalls.get()); return f;
    }
    static void next(Fixture f) { f.monitor.tick(); f.nanos.addAndGet(WAIT); f.monitor.tick(); }

    @ParameterizedTest @EnumSource(value = CloseResult.class, names = {
        "WINDOW_ENUMERATION_FAILED", "WINDOW_NOT_FOUND", "WINDOW_AMBIGUOUS", "FINAL_GUARD_VETO"})
    void retryRequiresFullNewGraceAndFreshServerQuery(CloseResult result)
    {
        Fixture f = failed(result); f.closeResult = CloseResult.REQUESTED;
        assertTrue(f.diagnostics.contains("close-stage-" + result.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        f.monitor.tick(); f.nanos.addAndGet(WAIT - 1); f.monitor.tick();
        assertEquals(1, f.queries.get()); assertEquals(1, f.closeCalls.get());
        f.nanos.incrementAndGet(); f.monitor.tick();
        assertEquals(2, f.queries.get()); assertEquals(2, f.closeCalls.get()); assertEquals(1, f.requested.get());
        f.evidence(); f.unchangedTicks(); assertEquals(2, f.closeCalls.get());
    }

    @Test void budgetIsThreeTotalAndAuditOrRosterReplayCannotRefillIt()
    {
        Fixture f = failed(CloseResult.WINDOW_NOT_FOUND); next(f); next(f);
        assertEquals(3, f.closeCalls.get());
        assertTrue(f.diagnostics.contains("close-retry-budget-exhausted-or-observation-changed"));
        f.closeResult = CloseResult.REQUESTED;
        f.evidence(); f.monitor.playerJoined(OTHER); f.monitor.playerLeft(OTHER); f.evidence();
        f.unchangedTicks(); assertEquals(3, f.closeCalls.get()); assertEquals(0, f.requested.get());
    }

    @ParameterizedTest @EnumSource(value = CloseResult.class, names = {
        "REQUESTED", "NOT_MANAGED", "IDENTITY_CHANGED", "CANCELLED", "UNSUPPORTED_OR_DENIED",
        "WINDOW_IDENTITY_CHANGED", "WINDOW_CHECK_UNAVAILABLE", "WINDOWS_ACCESS_DENIED",
        "WINDOWS_POST_REJECTED", "DISPATCH_UNCERTAIN", "PLATFORM_UNSUPPORTED"})
    void sentUnknownAndTerminalFailuresNeverAutomaticallyRepeat(CloseResult result)
    {
        Fixture f = failed(result); f.evidence(); f.unchangedTicks();
        f.monitor.playerJoined(OTHER); f.monitor.playerLeft(OTHER); f.evidence(); f.unchangedTicks();
        assertEquals(1, f.closeCalls.get());
    }

    @Test void nullAndThrownTransportResultsAreUncertainAndTerminal()
    {
        Fixture f = failed(null); assertEquals(CloseResult.DISPATCH_UNCERTAIN, f.result.get());
        f.unchangedTicks(); assertEquals(1, f.closeCalls.get());
        Fixture g = new Fixture(); g.beforeClose = () -> { throw new IllegalStateException(); }; g.arm(); g.due();
        assertEquals(CloseResult.DISPATCH_UNCERTAIN, g.result.get()); g.unchangedTicks(); assertEquals(1, g.closeCalls.get());
    }

    @ParameterizedTest @MethodSource("net.sybyline.scarlet.ScarletClientExitMonitorTest#invalidServerStates")
    void retryCannotReuseEarlierServerProof(String name, UnaryOperator<Instance> mutate)
    {
        Fixture f = failed(CloseResult.WINDOW_NOT_FOUND); f.response = mutate.apply(closed()); next(f);
        assertEquals(2, f.queries.get()); assertEquals(1, f.closeCalls.get(), name);
    }

    @Test void unhealthyRetryRestartsFullGrace()
    {
        Fixture f = failed(CloseResult.FINAL_GUARD_VETO); f.monitor.tick();
        f.nanos.addAndGet(WAIT - 1); f.healthy.set(false); f.monitor.tick();
        f.healthy.set(true); f.monitor.tick(); f.nanos.addAndGet(WAIT - 1); f.monitor.tick();
        assertEquals(1, f.closeCalls.get()); f.nanos.incrementAndGet(); f.monitor.tick(); assertEquals(2, f.closeCalls.get());
    }

    @Test void retryCannotSurviveProcessReplacementReopenOrNewLog()
    {
        for (int kind = 0; kind < 3; kind++)
        {
            Fixture f = failed(CloseResult.WINDOW_NOT_FOUND);
            if (kind == 0) f.current.set(client(LOCATION, START.minusSeconds(30), START.minusSeconds(20)));
            if (kind == 1) f.monitor.audit(event("group.instance.create", CLOSED.plusSeconds(1)));
            if (kind == 2) f.monitor.newLog();
            f.unchangedTicks(); assertEquals(1, f.closeCalls.get());
        }
    }

    @Test void operatorOverrideRemainsOneShotEvenForDefinitelyUnsentFailure()
    {
        ScarletExitOperatorTest.Scenario s = new ScarletExitOperatorTest.Scenario(); String token = s.notice();
        s.f.closeResult = CloseResult.WINDOW_NOT_FOUND;
        assertTrue(s.f.monitor.operatorExit(token, () -> true).contains("WINDOW_NOT_FOUND"));
        s.f.response = closed(); s.f.closeResult = CloseResult.REQUESTED;
        s.f.monitor.operatorExit(token, () -> true); s.f.unchangedTicks(); assertEquals(1, s.f.closeCalls.get());
    }

    @Test void finalGuardExplainsHealthAndProofAgeWithoutDispatch()
    {
        Fixture f = new Fixture(); f.arm(); f.beforeClose = () -> f.healthy.set(false); f.due();
        assertEquals(0, f.requested.get()); assertTrue(f.diagnostics.contains("close-guard-log-tracking-not-healthy"));
        Fixture g = new Fixture(); g.arm(); g.beforeClose = () -> g.nanos.addAndGet(TimeUnit.SECONDS.toNanos(11)); g.due();
        assertEquals(0, g.requested.get()); assertTrue(g.diagnostics.contains("close-guard-server-proof-stale"));
    }

    @Test void rearmInvalidatesOldAttemptAndRequiresCommittedSameObservation()
    {
        ScarletClosedInstanceExitTest f = new ScarletClosedInstanceExitTest(); f.start(); f.close();
        ScarletClosedInstanceExit.Attempt old = f.ready();
        assertFalse(f.policy.retryUnsent(old, CloseResult.WINDOW_NOT_FOUND));
        assertTrue(f.policy.commit(old, WAIT, () -> {}));
        assertFalse(f.policy.retryUnsent(old, CloseResult.REQUESTED));
        assertTrue(f.policy.retryUnsent(old, CloseResult.WINDOW_NOT_FOUND));
        assertFalse(f.policy.retryUnsent(old, CloseResult.WINDOW_NOT_FOUND));
        assertFalse(f.policy.commit(old, WAIT * 3, () -> fail("Old proof reused")));
        assertNull(f.policy.poll(WAIT)); assertNull(f.policy.poll(WAIT * 2 - 1));
        ScarletClosedInstanceExit.Attempt fresh = f.policy.poll(WAIT * 2); assertNotNull(fresh);
        assertTrue(f.policy.commit(fresh, WAIT * 2, () -> {}));
        f.track(SELF, OTHER);
        assertFalse(f.policy.retryUnsent(fresh, CloseResult.WINDOW_NOT_FOUND));
    }
}

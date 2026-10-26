package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletClientExitMonitorTest.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.vrchatapi.model.Instance;
import net.sybyline.scarlet.ext.ManagedVrcClient;

/** Synthetic process transport only; never terminates a real process. */
class ScarletForceFallbackTest
{
    static final long FORCE_WAIT = TimeUnit.SECONDS.toNanos(15);
    static Fixture armed()
    {
        Fixture f = new Fixture(); f.monitor.setForceCloseEnabled(() -> true); f.arm(); f.due();
        assertEquals(1, f.requested.get()); return f;
    }
    static void forceDue(Fixture f) { f.nanos.addAndGet(FORCE_WAIT); f.monitor.tick(); }
    @Test void separateOptInAndFreshServerProofAfterFullNormalGrace()
    {
        Fixture off = new Fixture(); off.arm(); off.due(); off.unchangedTicks(); assertEquals(0, off.forceCalls.get());
        Fixture f = armed(); f.nanos.addAndGet(FORCE_WAIT - 1); f.monitor.tick(); assertEquals(0, f.forceCalls.get());
        f.nanos.incrementAndGet(); f.monitor.tick(); assertEquals(2, f.queries.get()); assertEquals(1, f.forced.get());
        f.evidence(); f.unchangedTicks(); assertEquals(1, f.forceCalls.get());
    }
    @ParameterizedTest @MethodSource("net.sybyline.scarlet.ScarletClientExitMonitorTest#invalidServerStates")
    void forceNeverBypassesServerChecks(String name, UnaryOperator<Instance> mutate)
    {
        Fixture f = armed(); f.response = mutate.apply(closed()); forceDue(f);
        assertEquals(0, f.forceCalls.get(), name); f.response = closed(); f.unchangedTicks(); assertEquals(0, f.forceCalls.get());
    }
    @Test void noForceAfterNormalClientExit()
    {
        Fixture f = armed(); f.current.set(null); forceDue(f); assertEquals(0, f.forceCalls.get());
    }
    @Test void rosterAccountGroupSessionAndHealthChangesPreventForce()
    {
        for (int kind = 0; kind < 8; kind++)
        {
            Fixture f = armed();
            if (kind == 0) f.monitor.playerJoined(OTHER);
            if (kind == 1) { f.monitor.playerJoined(OTHER); f.monitor.playerLeft(OTHER); }
            if (kind == 2) f.group.set(OTHER_GROUP);
            if (kind == 3) f.monitor.newLog();
            if (kind == 4) f.healthy.set(false);
            if (kind == 5) f.enabled.set(false);
            if (kind == 6) f.current.set(client(LOCATION, START.minusSeconds(30), START.minusSeconds(20)));
            if (kind == 7) f.monitor.authenticated(OTHER);
            forceDue(f); assertEquals(0, f.forceCalls.get(), "kind=" + kind);
        }
    }
    @Test void freshGuardRejectsChangesAtNativeBoundary()
    {
        for (int kind = 0; kind < 4; kind++)
        {
            Fixture f = armed();
            if (kind == 0) f.beforeForce = () -> f.healthy.set(false);
            if (kind == 1) f.beforeForce = () -> f.monitor.playerJoined(OTHER);
            if (kind == 2) f.beforeForce = () -> f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(11));
            if (kind == 3) f.beforeForce = () -> f.monitor.setForceCloseEnabled(() -> false);
            forceDue(f); assertEquals(0, f.forced.get());
        }
    }
    @Test void turnedOffExpiredOrSlowProofCannotForce()
    {
        Fixture f = armed(); f.monitor.setForceCloseEnabled(() -> false); forceDue(f); assertEquals(0, f.forceCalls.get());
        Fixture g = armed(); g.nanos.addAndGet(TimeUnit.SECONDS.toNanos(76)); g.monitor.tick(); assertEquals(0, g.forceCalls.get());
        Fixture h = armed(); h.duringQuery = () -> h.nanos.addAndGet(TimeUnit.SECONDS.toNanos(11)); forceDue(h); assertEquals(0, h.forceCalls.get());
    }
    @Test void allForceOutcomesAreOneShot()
    {
        for (ManagedVrcClient.ForceResult result : ManagedVrcClient.ForceResult.values())
        {
            Fixture f = armed(); f.forceResult = result; forceDue(f); f.evidence(); f.unchangedTicks(); assertEquals(1, f.forceCalls.get());
        }
    }
    @Test void operatorOccupancyBypassCanNeverAuthorizeForce()
    {
        ScarletExitOperatorTest.Scenario s = new ScarletExitOperatorTest.Scenario();
        s.f.monitor.setForceCloseEnabled(() -> true); String token = s.notice();
        s.f.monitor.operatorExit(token, () -> true); assertEquals(1, s.f.requested.get());
        s.f.response = closed(); s.f.unchangedTicks(); assertEquals(0, s.f.forceCalls.get());
    }
}

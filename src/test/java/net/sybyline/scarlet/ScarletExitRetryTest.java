package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletClientExitMonitorTest.*;

import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.vrchatapi.model.Instance;

/** Synthetic clock, process and API fixtures only. */
class ScarletExitRetryTest
{
    static long seconds(long value) { return TimeUnit.SECONDS.toNanos(value); }
    static void atSeconds(Fixture f, long value) { f.nanos.set(seconds(value)); f.monitor.tick(); }

    @Test void occupancyUsesFifteenThenThirtyThenSixtySecondsWithoutClosing()
    {
        Fixture f = new Fixture(); f.response = closed().nUsers(2); f.arm(); f.due();
        atSeconds(f, 44); assertEquals(1, f.queries.get());
        atSeconds(f, 45); assertEquals(2, f.queries.get());
        atSeconds(f, 74); assertEquals(2, f.queries.get());
        atSeconds(f, 75); assertEquals(3, f.queries.get());
        atSeconds(f, 134); assertEquals(3, f.queries.get());
        atSeconds(f, 135); assertEquals(4, f.queries.get());
        atSeconds(f, 194); assertEquals(4, f.queries.get());
        atSeconds(f, 195); assertEquals(5, f.queries.get());
        assertEquals(0, f.closeCalls.get());
        assertTrue(f.diagnostics.contains("server-occupancy-retry-in-15-seconds"));
        assertTrue(f.diagnostics.contains("server-occupancy-retry-in-30-seconds"));
    }

    @ParameterizedTest(name = "retry: {0}")
    @MethodSource("net.sybyline.scarlet.ScarletClientExitMonitorTest#invalidServerStates")
    void onlyOccupancyFailuresQualifyForAcceleratedRecheck(String name, UnaryOperator<Instance> mutate)
    {
        Fixture f = new Fixture(); f.response = mutate.apply(closed()); f.arm(); f.due();
        boolean occupancy = name.equals("nUsers occupied") || name.equals("userCount occupied")
            || name.equals("two reported users") || name.equals("different sole reported user");
        atSeconds(f, 44); assertEquals(1, f.queries.get());
        atSeconds(f, 45); assertEquals(occupancy ? 2 : 1, f.queries.get());
        assertEquals(0, f.closeCalls.get());
    }

    @Test void successfulRetryStillRequiresFreshServerProofAndClosesOnce()
    {
        Fixture f = new Fixture(); f.response = closed().nUsers(2); f.arm(); f.due();
        f.response = closed(); atSeconds(f, 44); assertEquals(0, f.closeCalls.get());
        atSeconds(f, 45); assertEquals(1, f.requested.get());
        f.unchangedTicks(); assertEquals(1, f.closeCalls.get());
    }

    @Test void combinedFailureConsumesAccelerationEvenWhenCountsLaterBecomeOnlyBlocker()
    {
        Fixture f = new Fixture(); f.response = closed().nUsers(2).closedAt(null); f.arm(); f.due();
        f.response = closed().nUsers(2);
        atSeconds(f, 89); assertEquals(1, f.queries.get());
        atSeconds(f, 90); assertEquals(2, f.queries.get());
        atSeconds(f, 149); assertEquals(2, f.queries.get());
        atSeconds(f, 150); assertEquals(3, f.queries.get());
        assertEquals(0, f.closeCalls.get());
    }

    @Test void failedLongQueryWaitsFullMinuteAfterCompletionAndConsumesAcceleration()
    {
        Fixture f = new Fixture(); f.arm();
        f.duringQuery = () -> { f.nanos.addAndGet(seconds(70)); throw new IllegalStateException("synthetic failure"); };
        assertThrows(IllegalStateException.class, f::due);
        f.duringQuery = () -> {}; f.response = closed().nUsers(2);
        atSeconds(f, 159); assertEquals(1, f.queries.get());
        atSeconds(f, 160); assertEquals(2, f.queries.get());
        atSeconds(f, 219); assertEquals(2, f.queries.get());
        assertEquals(0, f.closeCalls.get());
    }

    @Test void staleOccupancyResponseDoesNotGetFastRetry()
    {
        Fixture f = new Fixture(); f.response = closed().nUsers(2); f.arm();
        f.duringQuery = () -> f.nanos.addAndGet(seconds(11)); f.due();
        f.duringQuery = () -> {};
        atSeconds(f, 100); assertEquals(1, f.queries.get());
        atSeconds(f, 101); assertEquals(2, f.queries.get());
        assertEquals(0, f.closeCalls.get());
    }

    @Test void auditReplaysAndSameRosterDoNotRefillFastRetryAllowance()
    {
        Fixture f = new Fixture(); f.response = closed().nUsers(2); f.arm(); f.due();
        atSeconds(f, 45); atSeconds(f, 75);
        f.evidence(); f.monitor.playerJoined(SELF);
        atSeconds(f, 90); atSeconds(f, 105); atSeconds(f, 134);
        assertEquals(3, f.queries.get()); assertEquals(0, f.closeCalls.get());
    }

    @Test void newSessionCannotInheritOldResponseRetryState()
    {
        Fixture f = new Fixture(); f.response = closed().nUsers(2); f.arm();
        f.duringQuery = f::live; f.due(); f.duringQuery = () -> {};
        f.evidence(); f.monitor.tick();
        atSeconds(f, 59); assertEquals(1, f.queries.get());
        atSeconds(f, 60); assertEquals(2, f.queries.get());
        atSeconds(f, 75); assertEquals(3, f.queries.get());
        assertEquals(0, f.closeCalls.get());
    }

    @Test void acceleratedRetryCannotBypassRosterHealthModeOrProcessChecks()
    {
        for (int kind = 0; kind < 4; kind++)
        {
            Fixture f = new Fixture(); f.response = closed().nUsers(2); f.arm(); f.due();
            f.response = closed();
            if (kind == 0) f.monitor.playerJoined(OTHER);
            if (kind == 1) f.healthy.set(false);
            if (kind == 2) f.enabled.set(false);
            if (kind == 3) f.current.set(null);
            atSeconds(f, 45);
            assertEquals(1, f.queries.get()); assertEquals(0, f.closeCalls.get());
        }
    }
}

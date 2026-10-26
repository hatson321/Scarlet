package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletClientExitMonitorTest.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.sybyline.scarlet.ext.ManagedVrcClient.Client;
import net.sybyline.scarlet.ext.SingleVrcClient.Status;

/** Synthetic selection/roster/Discord callbacks only. */
class ScarletSingleClientExitTest
{
    Fixture manual()
    {
        Fixture f = new Fixture(ScarletClientExitMonitor.EvidenceMode.LIVE_LOG_ROSTER);
        f.current.set(new Client(-1, LOCATION, START, 42, START.minusSeconds(120), "synthetic-user"));
        f.duringQuery = () -> fail("No instance API lookup is authorized by shutdown");
        return f;
    }

    @Test void shutdownOnlyReceiptNeedsFullGraceAndSeparateForceOptIn()
    {
        Fixture f = manual(); f.arm(); f.nanos.set(WAIT - 1); f.monitor.tick(); assertEquals(0, f.requested.get());
        f.due(); assertEquals(1, f.requested.get());
        f.unchangedTicks(); assertEquals(0, f.forced.get());
        Fixture force = manual(); force.monitor.setForceCloseEnabled(() -> true); force.arm(); force.due();
        force.nanos.addAndGet(TimeUnit.SECONDS.toNanos(15)); force.monitor.tick(); assertEquals(1, force.forced.get());
    }

    @Test void multipleClientsNeverCloseAndOpsWarningIsRateLimited()
    {
        Fixture f = manual(); AtomicInteger warnings = new AtomicInteger();
        f.monitor.setMultipleClientAlert(warnings::incrementAndGet); f.selectionStatus = Status.MULTIPLE;
        f.arm(); assertEquals(1, warnings.get());
        for (int i = 0; i < 20; i++) { f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(5)); f.monitor.tick(); }
        assertEquals(1, warnings.get()); assertEquals(0, f.closeCalls.get()); assertEquals(0, f.forceCalls.get());
        f.nanos.set(TimeUnit.MINUTES.toNanos(5)); f.monitor.tick(); assertEquals(2, warnings.get());
        f.selectionStatus = null; f.monitor.tick();
        f.nanos.addAndGet(WAIT - 1); f.monitor.tick(); assertEquals(0, f.closeCalls.get());
        f.nanos.incrementAndGet(); f.monitor.tick(); assertEquals(1, f.requested.get());
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2,3,4,5})
    void noOpsWarningWithoutClosedHealthyEmptySession(int kind)
    {
        Fixture f = manual(); AtomicInteger warnings = new AtomicInteger();
        f.monitor.setMultipleClientAlert(warnings::incrementAndGet); f.selectionStatus = Status.MULTIPLE;
        f.live();
        if (kind != 0) f.evidence();
        switch (kind)
        {
            case 1: f.monitor.playerJoined(OTHER); break;
            case 2: f.monitor.playerLeft(SELF); break;
            case 3: f.healthy.set(false); break;
            case 4: f.enabled.set(false); break;
            case 5: f.monitor.audit(event("group.instance.create", CLOSED.plusSeconds(1))); break;
        }
        f.monitor.tick(); f.due(); assertEquals(0, warnings.get()); assertEquals(0, f.closeCalls.get());
    }

    @Test void failedOrUnconfiguredNotificationDoesNotPermitClosing()
    {
        Fixture f = manual(); f.monitor.setMultipleClientAlert(() -> { throw new IllegalStateException("synthetic delivery failure"); });
        f.selectionStatus = Status.MULTIPLE; assertDoesNotThrow(f::arm); f.due(); assertEquals(0, f.closeCalls.get());
        Fixture noChannel = manual(); noChannel.selectionStatus = Status.MULTIPLE; noChannel.arm(); noChannel.due();
        assertEquals(0, noChannel.closeCalls.get());
    }

    @Test void secondClientBeforeForceCancelsForceAndAlerts()
    {
        Fixture f = manual(); AtomicInteger warnings = new AtomicInteger(); f.monitor.setMultipleClientAlert(warnings::incrementAndGet);
        f.monitor.setForceCloseEnabled(() -> true); f.arm(); f.due(); assertEquals(1, f.requested.get());
        f.selectionStatus = Status.MULTIPLE; f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(15)); f.monitor.tick();
        assertEquals(0, f.forceCalls.get()); assertEquals(1, warnings.get());
    }

    @Test void changedSessionDuringSelectionCannotEmitStaleWarning()
    {
        Fixture f = manual(); AtomicInteger warnings = new AtomicInteger(); f.monitor.setMultipleClientAlert(warnings::incrementAndGet);
        f.selectionStatus = Status.MULTIPLE; f.settle = f.monitor::newLog;
        f.arm(); assertEquals(0, warnings.get()); assertEquals(0, f.closeCalls.get());
    }
}

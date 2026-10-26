package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Guard callbacks only; never enumerates processes, reads game files or launches anything. */
class StartupLaunchGuardTest
{
    @Test void lateOptOutOrAccountChangeBlocksNativeDispatch() throws Exception
    {
        AtomicBoolean allowed = new AtomicBoolean(true);
        AtomicInteger baseChecks = new AtomicInteger();
        ManagedVrcClient.LaunchGuard guard = VrcLaunch.startupGuard(baseChecks::incrementAndGet, allowed::get);
        guard.check();
        allowed.set(false);
        assertThrows(InterruptedException.class, guard::check);
        assertEquals(2, baseChecks.get());
    }

    @Test void shutdownCancellationIsNeverBypassedByOptIn()
    {
        ManagedVrcClient.LaunchGuard guard = VrcLaunch.startupGuard(
            () -> { throw new InterruptedException("Synthetic shutdown"); },
            () -> { fail("Shutdown check must run first"); return true; });
        assertThrows(InterruptedException.class, guard::check);
    }
}

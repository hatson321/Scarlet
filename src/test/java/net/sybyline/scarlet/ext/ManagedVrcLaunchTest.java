package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import net.sybyline.scarlet.ext.ManagedVrcClient.Client;
import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;
import net.sybyline.scarlet.ext.ManagedVrcClient.Tracker;

/** Fake inventories, launch/close transports and elapsed time only; no native process operations. */
class ManagedVrcLaunchTest
{
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String LOCATION = "wrld_synthetic:123~group(grp_synthetic)";

    private static IOException refused(Executable action, String reason)
    {
        IOException failure = assertThrows(IOException.class, action);
        assertTrue(failure.getMessage().contains(reason), () -> "Expected refusal reason '" + reason + "', got: " + failure);
        return failure;
    }

    private static void interrupted(Executable action)
    {
        assertThrows(InterruptedException.class, action);
        assertTrue(Thread.currentThread().isInterrupted(), "Cancellation must preserve the caller's interrupt flag");
    }

    private static final class FakeClock extends Clock
    {
        Instant now = START;
        long nanos;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return this.now; }
        void advance(long millis)
        {
            this.now = this.now.plusMillis(millis);
            this.nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }

    private static final class FakeProcesses implements ManagedVrcClient.Processes
    {
        final Map<Long, ProcessInfo> entries = new LinkedHashMap<>();
        String user = "synthetic-user";
        boolean failSnapshot, failFind, denyClose, exitOnClose;
        int closeAttempts, closeSignals;
        int forceAttempts, forceSignals;
        boolean forceExits = true;
        ProcessInfo closeTarget;
        Runnable atClose = () -> {};
        public String currentUser() { return this.user; }
        public List<ProcessInfo> snapshot()
        {
            if (this.failSnapshot) throw new SecurityException("synthetic inventory unavailable");
            return new ArrayList<>(this.entries.values());
        }
        public ProcessInfo find(long pid)
        {
            if (this.failFind) throw new SecurityException("synthetic identity unavailable");
            return this.entries.get(pid);
        }
        public boolean requestClose(ProcessInfo expected, BooleanSupplier guard)
        {
            this.closeAttempts++;
            this.closeTarget = expected;
            this.atClose.run();
            if (this.denyClose || !guard.getAsBoolean()) return false;
            this.closeSignals++;
            if (this.exitOnClose) this.entries.remove(expected.pid());
            return true;
        }
        public ManagedVrcClient.ForceResult forceClose(ProcessInfo expected, BooleanSupplier guard)
        {
            forceAttempts++;
            if (!guard.getAsBoolean()) return ManagedVrcClient.ForceResult.GUARD_VETO;
            forceSignals++;
            if (forceExits) entries.remove(expected.pid());
            return ManagedVrcClient.ForceResult.REQUESTED;
        }
    }

    private static final class Harness
    {
        final FakeClock clock = new FakeClock();
        final FakeProcesses processes = new FakeProcesses();
        final Tracker tracker = new Tracker(this.processes, this.clock, () -> this.clock.nanos, this::pause);
        ManagedVrcClient.Pause onPause = millis -> {};
        boolean createClient = true;
        int starts, pauses;
        long pausedMillis;

        void pause(long millis) throws InterruptedException
        {
            assertTrue(millis > 0, "Coordinator polls must be paced");
            this.pauses++;
            this.pausedMillis += millis;
            assertTrue(this.pausedMillis <= 300_000, "Coordinator must stop at a bounded synthetic deadline");
            this.clock.advance(millis);
            this.onPause.pause(millis);
        }

        ProcessInfo client(long pid, long parent)
        {
            return new ProcessInfo(pid, this.clock.instant(), this.processes.user, "/synthetic/VRChat.exe", List.of(), parent);
        }

        ProcessInfo start()
        {
            this.starts++;
            ProcessInfo launcher = new ProcessInfo(100, this.clock.instant(), this.processes.user, "/synthetic/launch.exe", List.of());
            this.processes.entries.put(100L, launcher);
            if (this.createClient) this.processes.entries.put(41L, client(41, 100));
            return launcher;
        }

        void launch() throws Exception { this.tracker.launch(LOCATION, true, this::start); }

        Client managed()
        {
            long token = this.tracker.beginLaunch(LOCATION);
            assertTrue(token > 0);
            ProcessInfo launcher = new ProcessInfo(90, this.clock.instant(), this.processes.user, "/synthetic/launch.exe", List.of());
            this.processes.entries.put(90L, launcher);
            this.tracker.launchCompleted(token, launcher);
            this.clock.advance(1_000);
            this.processes.entries.put(40L, client(40, 90));
            assertNull(this.tracker.current());
            this.clock.advance(3_000);
            Client managed = this.tracker.current();
            assertNotNull(managed);
            return managed;
        }
    }

    @Test void explicitForceRejectsUnownedChangedOrMultipleClientsAndRevokedGuard()
    {
        Harness empty = new Harness();
        assertEquals(ManagedVrcClient.ForceResult.NOT_MANAGED, empty.tracker.forceClose(null, () -> true));
        for (int kind = 0; kind < 5; kind++)
        {
            Harness h = new Harness(); Client owned = h.managed();
            if (kind == 0) h.processes.entries.put(40L, h.client(40, 90)); // Same PID, new start time.
            if (kind == 1) h.processes.entries.put(42L, h.client(42, 90));
            if (kind == 2) h.processes.user = "different-user";
            if (kind == 3) h.processes.failSnapshot = true;
            final boolean allowed = kind != 4;
            assertNotEquals(ManagedVrcClient.ForceResult.REQUESTED, h.tracker.forceClose(owned, () -> allowed));
            assertEquals(0, h.processes.forceSignals);
        }
    }

    @Test void unknownNormalCloseFailureNeverArmsStartupForce()
    {
        Harness h = new Harness(); Client owned = h.managed(); h.processes.denyClose = true;
        assertThrows(IOException.class, () -> h.tracker.launch(LOCATION, true, guard -> h.start(), owned,
            () -> true, () -> true, () -> true));
        assertEquals(0, h.processes.forceAttempts); assertEquals(0, h.starts);
    }

    @Test void emptyInventoryLaunchesOnceAndWaitsForStableOwnedStartup() throws Exception
    {
        Harness h = new Harness();
        h.launch();
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
        assertTrue(h.pausedMillis >= 3_000);
        assertNotNull(h.tracker.current());
        assertEquals(41, h.tracker.current().pid());
    }

    @Test void stalledRecoveryRetainsExactClientAndWaitsForNormalExit() throws Exception
    {
        Harness h = new Harness(); Client owned = h.managed(); h.processes.exitOnClose = true;
        h.tracker.launch(LOCATION, true, guard -> { guard.check(); return h.start(); }, owned, () -> true, () -> true, null);
        assertEquals(1, h.processes.closeSignals); assertEquals(0, h.processes.forceAttempts); assertEquals(1, h.starts);
    }

    @Test void startupForceRequiresExplicitOptInAndFifteenSecondNormalWait() throws Exception
    {
        Harness h = new Harness(); Client owned = h.managed(); long started = h.clock.nanos;
        h.tracker.launch(LOCATION, true, guard -> { guard.check(); return h.start(); }, owned, () -> true, () -> true, () -> true);
        assertTrue(h.clock.nanos - started >= Tracker.CLOSE_NANOS + Tracker.QUIET_NANOS);
        assertEquals(1, h.processes.closeSignals); assertEquals(1, h.processes.forceSignals); assertEquals(1, h.starts);
        Harness denied = new Harness(); Client other = denied.managed();
        assertThrows(IOException.class, () -> denied.tracker.launch(LOCATION, true, guard -> denied.start(), other, () -> true, () -> true, () -> false));
        assertEquals(0, denied.processes.forceAttempts); assertEquals(0, denied.starts);
    }

    @Test void forceCannotLaunchReplacementUntilExitIsActuallyObserved()
    {
        Harness h = new Harness(); Client owned = h.managed(); h.processes.forceExits = false;
        assertThrows(IOException.class, () -> h.tracker.launch(LOCATION, true, guard -> h.start(), owned, () -> true, () -> true, () -> true));
        assertEquals(1, h.processes.forceSignals); assertEquals(0, h.starts);
    }

    @Test void startupProgressOrOwnershipChangeDuringGracePreventsForce()
    {
        for (int kind = 0; kind < 2; kind++)
        {
            Harness h = new Harness(); Client owned = h.managed(); boolean[] valid = {true};
            if (kind == 0) h.onPause = millis -> valid[0] = false;
            else h.onPause = millis -> h.processes.entries.put(40L, h.client(40, 90));
            assertThrows(IOException.class, () -> h.tracker.launch(LOCATION, true, guard -> h.start(), owned, () -> true, () -> valid[0], () -> true));
            assertEquals(0, h.processes.forceSignals); assertEquals(0, h.starts);
        }
    }

    @Test void differentOwnedClientOrDestinationCannotBeRecovered()
    {
        Harness h = new Harness(); Client owned = h.managed();
        Client forged = new Client(owned.launchToken(), LOCATION, owned.requestedAt(), owned.pid(), owned.startedAt(), owned.user());
        assertThrows(IOException.class, () -> h.tracker.launch(LOCATION, true, guard -> h.start(), forged, () -> true, () -> true, () -> true));
        assertThrows(IOException.class, () -> h.tracker.launch(LOCATION + "other", true, guard -> h.start(), owned, () -> true, () -> true, () -> true));
        assertEquals(0, h.starts); assertEquals(0, h.processes.closeAttempts); assertEquals(0, h.processes.forceAttempts);
    }

    @Test void manuallyExitedStartupRequiresQuietInventoryButNoTermination() throws Exception
    {
        Harness h = new Harness(); Client owned = h.managed(); h.processes.entries.remove(owned.pid());
        h.tracker.launch(LOCATION, true, guard -> h.start(), owned, () -> true, () -> true, () -> true);
        assertEquals(1, h.starts); assertEquals(0, h.processes.forceAttempts); assertEquals(0, h.processes.closeAttempts);
    }

    @Test void unownedExistingClientIsNeitherClosedNorLaunchedOver()
    {
        Harness h = new Harness();
        h.processes.entries.put(70L, h.client(70, 900));
        refused(h::launch, "not uniquely verified");
        assertEquals(0, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void unknownOrFailedInventoryCannotAuthorizeLaunch()
    {
        Harness unknown = new Harness();
        unknown.processes.entries.put(70L, new ProcessInfo(70, START, "synthetic-user", null, List.of()));
        assertInstanceOf(IllegalStateException.class, refused(unknown::launch, "process state could not be verified").getCause());
        assertEquals(0, unknown.starts);
        Harness failed = new Harness();
        failed.processes.failSnapshot = true;
        assertInstanceOf(SecurityException.class, refused(failed::launch, "process state could not be verified").getCause());
        assertEquals(0, failed.starts);
        assertEquals(0, failed.processes.closeAttempts);
    }

    @Test void nullCurrentUserCannotAuthorizeLaunch()
    {
        Harness h = new Harness();
        h.processes.user = null;
        refused(h::launch, "ownership could not be established");
        assertEquals(0, h.starts);
    }

    @Test void missingDestinationCannotCloseOrStartAnyClient()
    {
        Harness empty = new Harness();
        refused(() -> empty.tracker.launch(null, true, empty::start), "concrete destination is required");
        refused(() -> empty.tracker.launch(" ", true, empty::start), "concrete destination is required");
        assertEquals(0, empty.starts);
        Harness unowned = new Harness();
        unowned.processes.entries.put(70L, unowned.client(70, 900));
        refused(() -> unowned.tracker.launch(null, true, unowned::start), "concrete destination is required");
        assertEquals(0, unowned.starts);
        assertEquals(0, unowned.processes.closeAttempts);
    }

    @Test void restartRequiresExplicitManagedRestartPermission()
    {
        Harness h = new Harness();
        h.managed();
        refused(() -> h.tracker.launch(LOCATION, false, h::start), "already running");
        assertEquals(0, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void gracefulRestartWaitsForExitAndQuietIntervalBeforeStarting() throws Exception
    {
        Harness h = new Harness();
        Client original = h.managed();
        h.processes.exitOnClose = true;
        long[] launchAfterWait = {-1};
        h.tracker.launch(LOCATION, true, () ->
        {
            launchAfterWait[0] = h.pausedMillis;
            assertFalse(h.processes.entries.containsKey(original.pid()));
            return h.start();
        });
        assertEquals(1, h.processes.closeAttempts);
        assertEquals(1, h.processes.closeSignals);
        assertEquals(original.pid(), h.processes.closeTarget.pid());
        assertEquals(original.startedAt(), h.processes.closeTarget.startedAt());
        assertTrue(launchAfterWait[0] > 0, "Exit must be followed by a quiet observation interval");
        assertEquals(1, h.starts);
    }

    @Test void deniedCloseNeverStartsReplacementOrRetriesWithForce()
    {
        Harness h = new Harness();
        h.managed();
        h.processes.denyClose = true;
        refused(h::launch, "could not be asked to exit normally");
        assertEquals(1, h.processes.closeAttempts);
        assertEquals(0, h.processes.closeSignals);
        assertEquals(0, h.starts);
        assertTrue(h.processes.entries.containsKey(40L));
    }

    @Test void gracefulCloseTimeoutNeverForceClosesOrStartsReplacement()
    {
        Harness h = new Harness();
        h.managed();
        refused(h::launch, "did not finish its normal exit");
        assertEquals(1, h.processes.closeAttempts);
        assertEquals(1, h.processes.closeSignals);
        assertEquals(0, h.starts);
        assertTrue(h.pausedMillis > 0);
        assertTrue(h.processes.entries.containsKey(40L));
    }

    @Test void samePidWithNewStartIsNotClosedOrRelaunchedOver()
    {
        Harness h = new Harness();
        h.managed();
        h.processes.entries.put(40L, h.client(40, 90));
        refused(h::launch, "not uniquely verified");
        assertEquals(0, h.processes.closeAttempts);
        assertEquals(0, h.starts);
    }

    @Test void changedProcessOwnerIsNotClosedOrRelaunchedOver()
    {
        Harness h = new Harness();
        Client client = h.managed();
        h.processes.entries.put(40L, new ProcessInfo(40, client.startedAt(), "different-user", "/synthetic/VRChat.exe", List.of(), 90));
        refused(h::launch, "not uniquely verified");
        assertEquals(0, h.processes.closeAttempts);
        assertEquals(0, h.starts);
    }

    @Test void processAppearingDuringExitQuietIntervalAbortsReplacement()
    {
        Harness h = new Harness();
        h.managed();
        h.processes.exitOnClose = true;
        h.onPause = millis -> h.processes.entries.put(77L, h.client(77, 900));
        refused(h::launch, "Another or changed VRChat process appeared");
        assertEquals(1, h.processes.closeSignals);
        assertEquals(0, h.starts);
        assertEquals(1, h.processes.closeAttempts, "The late unowned client must never receive a close");
    }

    @Test void inventoryFailureAfterCloseDoesNotCountAsConfirmedExit()
    {
        Harness h = new Harness();
        h.managed();
        h.processes.atClose = () -> h.processes.failSnapshot = true;
        refused(h::launch, "could not be asked to exit normally");
        assertEquals(0, h.starts);
        assertEquals(0, h.processes.closeSignals, "Final transport guard must reject the unknown inventory");
    }

    @Test void startupWithoutCandidateTimesOutAndNeverAdoptsLateProcess()
    {
        Harness h = new Harness();
        h.createClient = false;
        IOException failure = refused(h::launch, "could not verify process ownership within two minutes");
        ManagedVrcClient.LaunchFailure known = assertInstanceOf(ManagedVrcClient.LaunchFailure.class, failure);
        assertEquals(ManagedVrcClient.LaunchFailure.Kind.REQUESTED_BUT_UNVERIFIED, known.kind());
        assertTrue(known.description().contains("does not say whether an instance was joined"));
        assertEquals(1, h.starts);
        assertTrue(h.pausedMillis >= TimeUnit.NANOSECONDS.toMillis(Tracker.QUIET_NANOS + Tracker.STARTUP_NANOS));
        h.processes.entries.put(41L, h.client(41, 100));
        assertNull(h.tracker.current());
        h.clock.advance(10_000);
        assertNull(h.tracker.current());
        refused(h::launch, "previous VRChat startup is still unresolved");
        assertEquals(1, h.starts, "A late unowned client prevents another dispatch");
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void unrelatedClientDuringStartupDoesNotBecomeManaged()
    {
        Harness h = new Harness();
        h.createClient = false;
        h.onPause = millis -> { if (h.starts > 0) h.processes.entries.putIfAbsent(77L, h.client(77, 900)); };
        refused(h::launch, "could not verify process ownership within two minutes");
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
        assertNull(h.tracker.current());
    }

    @Test void survivingLauncherAfterStartupDeadlineBlocksAnotherDispatch()
    {
        Harness h = new Harness();
        h.createClient = false;
        refused(h::launch, "could not verify process ownership within two minutes");
        assertTrue(h.processes.entries.containsKey(100L));
        assertTrue(h.tracker.hasClientsOrPending(), "A still-live known launcher can produce a late client after the deadline");
        refused(h::launch, "previous VRChat startup is still unresolved");
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void confirmedAbsentStartupProcessesAllowRetryAfterDeadline() throws Exception
    {
        Harness h = new Harness();
        h.createClient = false;
        refused(h::launch, "could not verify process ownership within two minutes");
        h.processes.entries.clear();
        assertFalse(h.tracker.hasClientsOrPending());
        h.createClient = true;
        h.launch();
        assertEquals(2, h.starts);
        assertNotNull(h.tracker.current());
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void inaccessibleRetainedLauncherIdentityCannotBeTreatedAsExited()
    {
        Harness h = new Harness();
        h.createClient = false;
        refused(h::launch, "could not verify process ownership within two minutes");
        h.processes.failFind = true;
        assertTrue(h.tracker.hasClientsOrPending());
        assertInstanceOf(SecurityException.class, refused(h::launch, "process state could not be verified").getCause());
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void returnedLauncherWithUnknownStartIdentityRemainsBlockedAfterDeadline()
    {
        Harness h = new Harness();
        refused(() -> h.tracker.launch(LOCATION, true, () ->
        {
            h.starts++;
            ProcessInfo partial = new ProcessInfo(100, null, "synthetic-user", "/synthetic/launch.exe", List.of());
            h.processes.entries.put(100L, partial);
            return partial;
        }), "could not verify process ownership within two minutes");
        assertNull(h.tracker.current());
        assertTrue(h.tracker.hasClientsOrPending());
        refused(h::launch, "previous VRChat startup is still unresolved");
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void observedBootstrapChildStillBlocksAfterOriginalLauncherExits()
    {
        Harness h = new Harness();
        h.createClient = false;
        h.onPause = millis ->
        {
            if (h.starts > 0) h.processes.entries.putIfAbsent(200L,
                new ProcessInfo(200, h.clock.instant(), "synthetic-user", "/synthetic/bootstrap.exe", List.of(), 100));
        };
        refused(h::launch, "could not verify process ownership within two minutes");
        h.processes.entries.remove(100L);
        assertTrue(h.tracker.hasClientsOrPending());
        refused(h::launch, "previous VRChat startup is still unresolved");
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void overlappingLaunchDuringStartupIsRejectedUntilFirstCompletes() throws Exception
    {
        Harness h = new Harness();
        int[] overlaps = {0};
        h.onPause = millis ->
        {
            if (h.starts > 0) overlaps[0]++;
            refused(h::launch, "already in progress");
            assertTrue(h.starts <= 1);
        };
        h.launch();
        assertTrue(overlaps[0] > 0);
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void overlappingLaunchDuringExitWaitIsRejected()
    {
        Harness h = new Harness();
        h.managed();
        int[] overlaps = {0};
        h.onPause = millis ->
        {
            overlaps[0]++;
            refused(h::launch, "already in progress");
        };
        refused(h::launch, "did not finish its normal exit");
        assertTrue(overlaps[0] > 0);
        assertEquals(1, h.processes.closeAttempts);
        assertEquals(0, h.starts);
    }

    @Test void interruptionDuringExitWaitPreventsReplacementAndPropagates()
    {
        Harness h = new Harness();
        h.managed();
        h.onPause = millis -> { throw new InterruptedException("synthetic shutdown"); };
        try
        {
            interrupted(h::launch);
            assertEquals(0, h.starts);
            assertEquals(1, h.processes.closeAttempts);
            assertEquals(1, h.processes.closeSignals);
        }
        finally { Thread.interrupted(); }
    }

    @Test void interruptionDuringStartupDoesNotAuthorizeLateCandidate()
    {
        Harness h = new Harness();
        h.createClient = false;
        h.onPause = millis -> { if (h.starts > 0) throw new InterruptedException("synthetic shutdown"); };
        try
        {
            interrupted(h::launch);
            assertEquals(1, h.starts);
            h.processes.entries.put(41L, h.client(41, 100));
            h.clock.advance(10_000);
            assertNull(h.tracker.current());
            assertEquals(0, h.processes.closeAttempts);
        }
        finally { Thread.interrupted(); }
    }

    @Test void alreadyInterruptedCallerCannotDispatchLaunch()
    {
        Harness h = new Harness();
        Thread.currentThread().interrupt();
        try
        {
            interrupted(h::launch);
            assertEquals(0, h.starts);
            assertEquals(0, h.processes.closeAttempts);
        }
        finally { Thread.interrupted(); }
    }

    @Test void launchTransportFailureDoesNotRetryOrAdoptLaterClient()
    {
        Harness h = new Harness();
        IOException failure = refused(() -> h.tracker.launch(LOCATION, true, () ->
        {
            h.starts++;
            throw new IllegalStateException("synthetic start failure");
        }), "launch dispatch or process ownership could not be confirmed within two minutes");
        ManagedVrcClient.LaunchFailure known = assertInstanceOf(ManagedVrcClient.LaunchFailure.class, failure);
        assertEquals(ManagedVrcClient.LaunchFailure.Kind.DISPATCH_UNCERTAIN, known.kind());
        assertFalse(known.description().contains("launch was requested"));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("synthetic start failure", failure.getCause().getMessage());
        h.processes.entries.put(41L, h.client(41, 100));
        h.clock.advance(10_000);
        assertNull(h.tracker.current());
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void singletonHandoffRemainsUnownedButReportsRequestedLaunchTruthfully()
    {
        Harness h = new Harness();
        ManagedVrcClient.LaunchFailure failure = assertThrows(ManagedVrcClient.LaunchFailure.class,
            () -> h.tracker.launch(LOCATION, false, () ->
            {
                h.starts++;
                h.processes.entries.put(41L, h.client(41, 900));
                return null;
            }));
        assertEquals(ManagedVrcClient.LaunchFailure.Kind.REQUESTED_BUT_UNVERIFIED, failure.kind());
        assertNull(h.tracker.current());
        assertTrue(h.tracker.hasClientsOrPending());
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
        assertEquals(0, h.processes.closeSignals);
    }

    @Test void vanishedParentCannotAuthorizeAChildFirstSeenAfterItExited()
    {
        Harness h = new Harness();
        h.createClient = false;
        h.onPause = millis ->
        {
            if (h.starts > 0)
            {
                h.processes.entries.remove(100L);
                h.processes.entries.putIfAbsent(41L, h.client(41, 100));
            }
        };
        ManagedVrcClient.LaunchFailure failure = assertThrows(ManagedVrcClient.LaunchFailure.class, h::launch);
        assertEquals(ManagedVrcClient.LaunchFailure.Kind.REQUESTED_BUT_UNVERIFIED, failure.kind());
        assertNull(h.tracker.current());
        assertTrue(h.tracker.hasClientsOrPending());
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void interruptedStartupRetainsQuarantineUntilOriginalDeadline() throws Exception
    {
        Harness h = new Harness();
        h.createClient = false;
        h.onPause = millis -> { if (h.starts > 0) throw new InterruptedException("synthetic shutdown"); };
        try { interrupted(h::launch); }
        finally { Thread.interrupted(); }
        h.onPause = millis -> {};
        assertTrue(h.tracker.hasClientsOrPending());
        refused(h::launch, "previous VRChat startup is still unresolved");
        assertEquals(1, h.starts);
        h.clock.advance(TimeUnit.NANOSECONDS.toMillis(Tracker.STARTUP_NANOS));
        h.processes.entries.clear();
        h.createClient = true;
        h.launch();
        assertEquals(2, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void ambiguousStarterInterruptedAfterDispatchRetainsQuarantine()
    {
        Harness h = new Harness();
        h.createClient = false;
        h.onPause = millis -> { if (h.starts > 0) throw new InterruptedException("synthetic shutdown"); };
        try
        {
            interrupted(() -> h.tracker.launch(LOCATION, true, () -> { h.starts++; return null; }));
        }
        finally { Thread.interrupted(); }
        h.onPause = millis -> {};
        refused(h::launch, "previous VRChat startup is still unresolved");
        assertEquals(1, h.starts);
        h.processes.entries.put(77L, h.client(77, 900));
        h.clock.advance(TimeUnit.NANOSECONDS.toMillis(Tracker.STARTUP_NANOS));
        assertNull(h.tracker.current());
        refused(h::launch, "not uniquely verified");
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void wallClockChangesCannotShortenUnresolvedMonotonicQuarantine()
    {
        Harness h = new Harness();
        h.createClient = false;
        h.onPause = millis -> { if (h.starts > 0) throw new InterruptedException("synthetic shutdown"); };
        try { interrupted(h::launch); }
        finally { Thread.interrupted(); }
        h.clock.now = h.clock.now.plusSeconds(86_400);
        refused(h::launch, "previous VRChat startup is still unresolved");
        h.clock.now = START.minusSeconds(86_400);
        refused(h::launch, "previous VRChat startup is still unresolved");
        assertEquals(1, h.starts);
    }

    @Test void cancelledQuietWaitCannotReachStartTransport()
    {
        Harness h = new Harness();
        h.onPause = millis -> h.tracker.cancelPendingLaunches();
        try
        {
            interrupted(h::launch);
            assertEquals(0, h.starts);
            assertEquals(0, h.processes.closeAttempts);
        }
        finally { Thread.interrupted(); }
    }

    @Test void permanentStopPreventsNewLaunchesWithoutTouchingManagedClient()
    {
        Harness h = new Harness();
        Client client = h.managed();
        h.tracker.stopLaunching();
        try
        {
            interrupted(h::launch);
            assertEquals(ManagedVrcClient.CloseResult.CANCELLED, h.tracker.close(client, () -> true));
            assertEquals(0, h.starts);
            assertEquals(0, h.processes.closeAttempts);
            assertTrue(h.processes.entries.containsKey(client.pid()));
        }
        finally { Thread.interrupted(); }
    }

    @Test void permanentStopDuringQuietWaitPreventsDispatch()
    {
        Harness h = new Harness();
        h.onPause = millis -> h.tracker.stopLaunching();
        try
        {
            interrupted(h::launch);
            assertEquals(0, h.starts);
            assertEquals(0, h.processes.closeAttempts);
        }
        finally { Thread.interrupted(); }
    }

    @Test void permanentStopAtFinalCloseBoundaryPreventsSignal()
    {
        Harness h = new Harness();
        h.managed();
        h.processes.atClose = h.tracker::stopLaunching;
        try
        {
            refused(h::launch, "could not be asked to exit normally");
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, h.processes.closeAttempts);
            assertEquals(0, h.processes.closeSignals);
            assertEquals(0, h.starts);
        }
        finally { Thread.interrupted(); }
    }

    @Test void permanentStopDuringStartupNeverClosesLateClient()
    {
        Harness h = new Harness();
        h.createClient = false;
        h.onPause = millis -> { if (h.starts > 0) h.tracker.stopLaunching(); };
        try { interrupted(h::launch); }
        finally { Thread.interrupted(); }
        h.processes.entries.put(41L, h.client(41, 100));
        h.clock.advance(TimeUnit.NANOSECONDS.toMillis(Tracker.STARTUP_NANOS));
        assertNull(h.tracker.current());
        assertEquals(1, h.starts);
        assertEquals(0, h.processes.closeAttempts);
    }

    @Test void finalTransportGuardBlocksStopDuringLaunchPreparation()
    {
        Harness h = new Harness();
        int[] preparations = {0};
        try
        {
            interrupted(() -> h.tracker.launch(LOCATION, true, guard ->
            {
                preparations[0]++;
                h.tracker.stopLaunching();
                guard.check();
                return h.start();
            }));
            assertEquals(1, preparations[0]);
            assertEquals(0, h.starts);
            assertEquals(0, h.processes.closeAttempts);
        }
        finally { Thread.interrupted(); }
    }

    @Test void stopInterruptsActiveFakeTransportWithoutSignallingAnyProcess() throws Exception
    {
        Harness h = new Harness();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try
        {
            Future<?> active = worker.submit(() ->
            {
                try
                {
                    interrupted(() -> h.tracker.launch(LOCATION, true, guard ->
                    {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic transport release timed out");
                        guard.check();
                        return h.start();
                    }));
                }
                finally { Thread.interrupted(); }
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            h.tracker.stopLaunching();
            active.get(2, TimeUnit.SECONDS);
            assertEquals(0, h.starts);
            assertEquals(0, h.processes.closeAttempts);
            assertEquals(0, h.processes.closeSignals);
        }
        finally
        {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void independentCallerIsRejectedWhileStartTransportIsStillRunning() throws Exception
    {
        Harness h = new Harness();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try
        {
            Future<?> first = workers.submit(() ->
            {
                h.tracker.launch(LOCATION, true, () ->
                {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic start release timed out");
                    return h.start();
                });
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> overlap = workers.submit(() ->
            {
                refused(h::launch, "already in progress");
                return null;
            });
            overlap.get(2, TimeUnit.SECONDS);
            assertEquals(0, h.starts, "Overlap cannot dispatch before the first transport returns");
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(1, h.starts);
            assertEquals(0, h.processes.closeAttempts);
        }
        finally
        {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

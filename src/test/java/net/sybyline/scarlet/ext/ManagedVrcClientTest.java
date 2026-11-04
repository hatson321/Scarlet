package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.sybyline.scarlet.ext.ManagedVrcClient.Client;
import net.sybyline.scarlet.ext.ManagedVrcClient.CloseResult;
import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;
import net.sybyline.scarlet.ext.ManagedVrcClient.Tracker;

/** Entirely synthetic process metadata and close transport; never starts or touches VRChat. */
class ManagedVrcClientTest
{
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String LOCATION = "wrld_test:123~group(grp_test)";
    private final MutableClock clock = new MutableClock();
    private final FakeProcesses processes = new FakeProcesses();
    private final Tracker tracker = new Tracker(this.processes, this.clock);

    static final class MutableClock extends Clock
    {
        Instant now = START;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return this.now; }
        void advance(long seconds) { this.now = this.now.plusSeconds(seconds); }
    }

    static final class FakeProcesses implements ManagedVrcClient.Processes
    {
        String user = "test-user";
        List<ProcessInfo> entries = new ArrayList<>();
        ProcessInfo launcher;
        boolean throwSnapshot, throwFind, denyClose;
        Runnable beforeClose = () -> {};
        int attempts, signals;
        ProcessInfo closeTarget;
        public String currentUser() { return this.user; }
        public List<ProcessInfo> snapshot()
        {
            if (this.throwSnapshot) throw new SecurityException("synthetic metadata failure");
            List<ProcessInfo> snapshot = new ArrayList<>(this.entries);
            if (this.launcher != null) snapshot.add(this.launcher);
            return snapshot;
        }
        public ProcessInfo find(long pid)
        {
            if (this.throwFind) throw new SecurityException("synthetic inaccessible process");
            if (this.launcher != null && this.launcher.pid() == pid) return this.launcher;
            return this.entries.stream().filter(entry -> entry.pid() == pid).findFirst().orElse(null);
        }
        public boolean requestClose(ProcessInfo expected, BooleanSupplier revalidate)
        {
            this.attempts++;
            this.closeTarget = expected;
            this.beforeClose.run();
            if (this.denyClose || !revalidate.getAsBoolean())
                return false;
            this.signals++;
            return true;
        }
    }

    private ProcessInfo process(long pid)
    {
        return new ProcessInfo(pid, this.clock.instant(), "test-user", "/synthetic/VRChat.exe", List.of(), 100);
    }

    private long begin()
    {
        long token = this.tracker.beginLaunch(LOCATION);
        assertTrue(token > 0);
        this.processes.launcher = new ProcessInfo(100, this.clock.instant(), "test-user", "/synthetic/launch.exe", List.of());
        this.tracker.launchCompleted(token, this.processes.launcher);
        this.clock.advance(1);
        return token;
    }

    private Client managed()
    {
        begin();
        this.processes.entries.add(process(41));
        assertNull(this.tracker.current());
        this.clock.advance(3);
        Client client = this.tracker.current();
        assertNotNull(client);
        return client;
    }

    @Test void uniqueNewClientNeedsStableIdentityAndRetainsLaunchSession()
    {
        long token = begin();
        ProcessInfo process = process(41);
        this.processes.entries.add(process);
        assertNull(this.tracker.current());
        this.clock.advance(2);
        assertNull(this.tracker.current());
        this.clock.advance(1);
        Client client = this.tracker.current();
        assertNotNull(client);
        assertEquals(token, client.launchToken());
        assertEquals(LOCATION, client.requestedLocation());
        assertEquals(START, client.requestedAt());
        assertEquals(process.startedAt(), client.startedAt());
        assertEquals(41, client.pid());
        assertEquals("test-user", client.user());
        assertSame(client, this.tracker.current());
    }

    @Test void incompleteLaunchIsNeverAdopted()
    {
        this.tracker.beginLaunch(LOCATION);
        this.clock.advance(1);
        this.processes.entries.add(process(41));
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void existingClientIsNeverAdoptedEvenUnderAnotherAccount()
    {
        this.processes.entries.add(new ProcessInfo(41, START.minusSeconds(10), "other-user", "VRChat.exe", List.of()));
        assertEquals(0, this.tracker.beginLaunch(LOCATION));
        this.clock.advance(5);
        assertNull(this.tracker.current());
    }

    @Test void missingLocationOrCurrentUserDoesNotArmDiscovery()
    {
        assertEquals(0, this.tracker.beginLaunch(null));
        assertEquals(0, this.tracker.beginLaunch(" "));
        this.processes.user = null;
        assertEquals(0, this.tracker.beginLaunch(LOCATION));
    }

    @Test void failedBaselineSnapshotDoesNotArmDiscovery()
    {
        this.processes.throwSnapshot = true;
        assertEquals(0, this.tracker.beginLaunch(LOCATION));
    }

    @Test void baselinePidReuseDoesNotQualifyAsNewClient()
    {
        this.processes.entries.add(new ProcessInfo(41, START.minusSeconds(10), "test-user", "/synthetic/steam", List.of()));
        begin();
        this.processes.entries.clear();
        this.processes.entries.add(process(41));
        assertNull(this.tracker.current());
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void missingStartIdentityFailsClosed()
    {
        begin();
        this.processes.entries.add(new ProcessInfo(41, null, "test-user", "VRChat.exe", List.of()));
        assertNull(this.tracker.current());
    }

    @Test void preLaunchStartIdentityFailsClosed()
    {
        begin();
        this.processes.entries.add(new ProcessInfo(41, START.minusSeconds(1), "test-user", "VRChat.exe", List.of()));
        assertNull(this.tracker.current());
    }

    @Test void futureStartIdentityFailsClosed()
    {
        begin();
        this.processes.entries.add(new ProcessInfo(41, this.clock.instant().plusSeconds(10), "test-user", "VRChat.exe", List.of()));
        assertNull(this.tracker.current());
    }

    @Test void wrongOrUnknownOwnerFailsClosed()
    {
        for (String user : Arrays.asList(null, "other-user"))
        {
            FakeProcesses processes = new FakeProcesses();
            Tracker tracker = new Tracker(processes, this.clock);
            long token = tracker.beginLaunch(LOCATION);
            processes.launcher = new ProcessInfo(100, this.clock.instant(), "test-user", "/synthetic/launch.exe", List.of());
            tracker.launchCompleted(token, processes.launcher);
            this.clock.advance(1);
            processes.entries.add(new ProcessInfo(41, this.clock.instant(), user, "VRChat.exe", List.of()));
            assertNull(tracker.current());
        }
    }

    @Test void ambiguousClientsPermanentlyInvalidateThisLaunch()
    {
        begin();
        this.processes.entries.add(process(41));
        this.processes.entries.add(process(42));
        assertNull(this.tracker.current());
        this.processes.entries.remove(1);
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void discoveryTimeoutCannotAdoptALaterUnrelatedClient()
    {
        begin();
        this.clock.advance(120);
        this.processes.entries.add(process(41));
        assertNull(this.tracker.current());
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void backwardsClockInvalidatesDiscovery()
    {
        begin();
        this.clock.now = START.minusSeconds(1);
        assertNull(this.tracker.current());
        this.clock.advance(10);
        this.processes.entries.add(process(41));
        assertNull(this.tracker.current());
    }

    @Test void candidateDisappearingRestartsStabilityWindow()
    {
        begin();
        this.processes.entries.add(process(41));
        assertNull(this.tracker.current());
        this.clock.advance(2);
        this.processes.entries.clear();
        assertNull(this.tracker.current());
        this.processes.entries.add(process(42));
        assertNull(this.tracker.current());
        this.clock.advance(2);
        assertNull(this.tracker.current());
        this.clock.advance(1);
        assertEquals(42, this.tracker.current().pid());
    }

    @Test void failedLaunchClearsTracking()
    {
        long token = begin();
        this.tracker.launchFailed(token);
        this.processes.entries.add(process(41));
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void newLaunchInvalidatesOldOwnershipAndOldCompletionCannotArmIt()
    {
        Client client = managed();
        this.processes.entries.clear();
        long newer = this.tracker.beginLaunch(LOCATION);
        assertTrue(newer > client.launchToken());
        this.tracker.launchCompleted(client.launchToken());
        this.processes.entries.add(process(42));
        this.clock.advance(10);
        assertNull(this.tracker.current());
        assertEquals(CloseResult.NOT_MANAGED, this.tracker.close(client, () -> true));
    }

    @Test void processExitClearsOwnershipWithoutAdoptingReplacement()
    {
        managed();
        this.processes.entries.clear();
        assertNull(this.tracker.current());
        this.processes.entries.add(process(42));
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void reusedPidWithDifferentStartCannotReceiveClose()
    {
        Client client = managed();
        this.processes.entries.set(0, process(41));
        assertEquals(CloseResult.IDENTITY_CHANGED, this.tracker.close(client, () -> true));
        assertEquals(0, this.processes.signals);
    }

    @Test void samePidStartButDifferentExecutableCannotReceiveClose()
    {
        Client client = managed();
        this.processes.entries.set(0, new ProcessInfo(41, client.startedAt(), client.user(), "/different/VRChat.exe", List.of()));
        assertNull(this.tracker.current());
        assertEquals(CloseResult.NOT_MANAGED, this.tracker.close(client, () -> true));
    }

    @Test void lostMetadataClearsCurrentClient()
    {
        managed();
        this.processes.throwFind = true;
        assertNull(this.tracker.current());
    }

    @Test void discoveryReadFailurePermanentlyInvalidatesAttempt()
    {
        begin();
        this.processes.throwSnapshot = true;
        assertNull(this.tracker.current());
        this.processes.throwSnapshot = false;
        this.processes.entries.add(process(41));
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void untrackedOrForgedClientDoesNotAuthorizeClose()
    {
        Client client = managed();
        Client forged = new Client(client.launchToken(), client.requestedLocation(), client.requestedAt(),
            client.pid(), client.startedAt(), client.user());
        assertEquals(CloseResult.NOT_MANAGED, this.tracker.close(null, () -> true));
        assertEquals(CloseResult.NOT_MANAGED, this.tracker.close(forged, () -> true));
        assertEquals(0, this.processes.attempts);
    }

    @Test void freshStateVetoPreventsAnyCloseAttempt()
    {
        Client client = managed();
        assertEquals(CloseResult.CANCELLED, this.tracker.close(client, () -> false));
        assertEquals(CloseResult.CANCELLED, this.tracker.close(client, null));
        assertEquals(0, this.processes.attempts);
    }

    @Test void successfulCloseOnlyRequestsTheExactIdentity()
    {
        Client client = managed();
        assertEquals(CloseResult.REQUESTED, this.tracker.close(client, () -> true));
        assertEquals(1, this.processes.signals);
        assertEquals(client.pid(), this.processes.closeTarget.pid());
        assertEquals(client.startedAt(), this.processes.closeTarget.startedAt());
        assertEquals(client.user(), this.processes.closeTarget.user());
        assertSame(client, this.tracker.current(), "A close request is not evidence that the process exited");
    }

    @Test void temporalAppearanceWithoutLaunchHandleNeverEstablishesOwnership()
    {
        long token = this.tracker.beginLaunch(LOCATION);
        this.tracker.launchCompleted(token);
        this.clock.advance(1);
        this.processes.entries.add(process(41));
        assertNull(this.tracker.current());
        this.clock.advance(10);
        assertNull(this.tracker.current());
        assertEquals(0, this.processes.attempts);
    }

    @Test void unrelatedStartupClientIsNeverAdoptedDespiteMatchingTimeAndUser()
    {
        begin();
        this.processes.entries.add(new ProcessInfo(41, this.clock.instant(), "test-user", "/synthetic/VRChat.exe", List.of(), 900));
        assertNull(this.tracker.current());
        this.clock.advance(10);
        assertNull(this.tracker.current());
        assertEquals(0, this.processes.attempts);
    }

    @Test void processReturnedDirectlyByLauncherCanEstablishOwnership()
    {
        long token = this.tracker.beginLaunch(LOCATION);
        ProcessInfo direct = new ProcessInfo(41, this.clock.instant(), "test-user", "/synthetic/VRChat.exe", List.of());
        this.processes.entries.add(direct);
        this.tracker.launchCompleted(token, direct);
        assertNull(this.tracker.current());
        this.clock.advance(3);
        assertEquals(41, this.tracker.current().pid());
    }

    @Test void missingLauncherIdentityDoesNotEstablishOwnership()
    {
        for (ProcessInfo launcher : Arrays.asList(null,
            new ProcessInfo(100, null, "test-user", "/synthetic/launch.exe", List.of()),
            new ProcessInfo(100, START, null, "/synthetic/launch.exe", List.of()),
            new ProcessInfo(100, START, "test-user", null, List.of())))
        {
            MutableClock clock = new MutableClock();
            FakeProcesses processes = new FakeProcesses();
            Tracker tracker = new Tracker(processes, clock);
            long token = tracker.beginLaunch(LOCATION);
            processes.launcher = launcher;
            tracker.launchCompleted(token, launcher);
            clock.advance(1);
            processes.entries.add(new ProcessInfo(41, clock.instant(), "test-user", "/synthetic/VRChat.exe", List.of(), 100));
            assertNull(tracker.current());
            clock.advance(10);
            assertNull(tracker.current());
        }
    }

    @Test void replacedLauncherPidCannotProveParentage()
    {
        begin();
        this.processes.launcher = new ProcessInfo(100, this.clock.instant(), "test-user", "/synthetic/launch.exe", List.of());
        this.processes.entries.add(process(41));
        assertNull(this.tracker.current());
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void changedLauncherOwnerCannotProveParentage()
    {
        begin();
        this.processes.launcher = new ProcessInfo(100, START, "other-user", "/synthetic/launch.exe", List.of());
        this.processes.entries.add(process(41));
        assertNull(this.tracker.current());
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void unobservedExitedLauncherCannotProveLateChildParentage()
    {
        begin();
        this.processes.launcher = null;
        this.processes.entries.add(process(41));
        assertNull(this.tracker.current());
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void fullyObservedIntermediateParentChainCanEstablishOwnership()
    {
        begin();
        this.processes.entries.add(new ProcessInfo(200, this.clock.instant(), "test-user", "/synthetic/bootstrap.exe", List.of(), 100));
        this.processes.entries.add(new ProcessInfo(41, this.clock.instant(), "test-user", "/synthetic/VRChat.exe", List.of(), 200));
        assertNull(this.tracker.current());
        this.clock.advance(3);
        assertEquals(41, this.tracker.current().pid());
    }

    @Test void unknownExecutableAtBaselineFailsClosed()
    {
        this.processes.entries.add(new ProcessInfo(77, START.minusSeconds(1), "test-user", null, List.of()));
        assertEquals(0, this.tracker.beginLaunch(LOCATION));
    }

    @Test void duplicatePidsInSnapshotFailClosed()
    {
        this.processes.entries.add(new ProcessInfo(77, START.minusSeconds(1), "test-user", "/synthetic/other.exe", List.of()));
        this.processes.entries.add(new ProcessInfo(77, START.minusSeconds(1), "test-user", "/synthetic/other.exe", List.of()));
        assertEquals(0, this.tracker.beginLaunch(LOCATION));
        assertTrue(this.tracker.hasClientsOrPending(), "Invalid inventory cannot mean the machine is empty");
    }

    @Test void oversizedInventoryFailsClosedBeforeScanningItsEntries()
    {
        this.processes.entries = java.util.Collections.nCopies(100_001,
            new ProcessInfo(77, START.minusSeconds(1), "test-user", "/synthetic/other.exe", List.of()));
        assertEquals(0, this.tracker.beginLaunch(LOCATION));
        assertTrue(this.tracker.hasClientsOrPending());
    }

    @Test void wineWithoutArgumentsCannotBeAssumedToBeAnUnrelatedProcess()
    {
        this.processes.entries.add(new ProcessInfo(77, START.minusSeconds(1), "test-user", "/synthetic/wine64", List.of()));
        assertEquals(0, this.tracker.beginLaunch(LOCATION));
        assertTrue(this.tracker.hasClientsOrPending());
    }

    @Test void protectedNonClientNameDoesNotPretendToProveOwnership()
    {
        this.processes.entries.add(new ProcessInfo(0, null, null, "[synthetic system process]", List.of()));
        this.processes.entries.add(new ProcessInfo(77, null, null, "synthetic-service.exe", List.of()));
        long token = begin();
        this.processes.entries.add(process(41));
        assertNull(this.tracker.current());
        this.clock.advance(3);
        assertEquals(token, this.tracker.current().launchToken());
    }

    @Test void unknownExecutableDuringDiscoveryCannotBeIgnored()
    {
        begin();
        this.processes.entries.add(process(41));
        this.processes.entries.add(new ProcessInfo(77, this.clock.instant(), "test-user", null, List.of()));
        assertNull(this.tracker.current());
        this.processes.entries.remove(1);
        this.clock.advance(10);
        assertNull(this.tracker.current());
    }

    @Test void samePidStartButChangedProcessOwnerCannotReceiveClose()
    {
        Client client = managed();
        this.processes.entries.set(0, new ProcessInfo(41, client.startedAt(), "other-user", "/synthetic/VRChat.exe", List.of(), 100));
        assertEquals(CloseResult.IDENTITY_CHANGED, this.tracker.close(client, () -> true));
        assertEquals(0, this.processes.attempts);
    }

    @Test void lateProcessOwnerChangePreventsCloseAtTransportBoundary()
    {
        Client client = managed();
        this.processes.beforeClose = () -> this.processes.entries.set(0,
            new ProcessInfo(41, client.startedAt(), "other-user", "/synthetic/VRChat.exe", List.of(), 100));
        assertEquals(CloseResult.UNSUPPORTED_OR_DENIED, this.tracker.close(client, () -> true));
        assertEquals(0, this.processes.signals);
    }

    @Test void lateStateVetoPreventsSignalAtTransportBoundary()
    {
        Client client = managed();
        boolean[] allowed = {true};
        this.processes.beforeClose = () -> allowed[0] = false;
        assertEquals(CloseResult.UNSUPPORTED_OR_DENIED, this.tracker.close(client, () -> allowed[0]));
        assertEquals(0, this.processes.signals);
    }

    @Test void lateIdentityChangePreventsSignalAtTransportBoundary()
    {
        Client client = managed();
        this.processes.beforeClose = () -> this.processes.entries.set(0, process(41));
        assertEquals(CloseResult.UNSUPPORTED_OR_DENIED, this.tracker.close(client, () -> true));
        assertEquals(0, this.processes.signals);
    }

    @Test void changedCurrentOwnerPreventsClose()
    {
        Client client = managed();
        this.processes.user = "other-user";
        assertEquals(CloseResult.IDENTITY_CHANGED, this.tracker.close(client, () -> true));
        assertEquals(0, this.processes.signals);
    }

    @Test void deniedTransportDoesNotClaimAnExitOrFallBackToForce()
    {
        Client client = managed();
        this.processes.denyClose = true;
        assertEquals(CloseResult.UNSUPPORTED_OR_DENIED, this.tracker.close(client, () -> true));
        assertEquals(1, this.processes.attempts);
        assertEquals(0, this.processes.signals);
    }

    @Test void unavailableMetadataOrThrowingGuardFailsClosed()
    {
        Client client = managed();
        assertEquals(CloseResult.UNSUPPORTED_OR_DENIED, this.tracker.close(client, () -> { throw new IllegalStateException(); }));
        this.processes.throwFind = true;
        assertEquals(CloseResult.UNSUPPORTED_OR_DENIED, this.tracker.close(client, () -> true));
        assertEquals(0, this.processes.attempts);
    }

    @Test void executableMatcherAcceptsExactNativeAndWineClients()
    {
        assertTrue(ManagedVrcClient.isClient(new ProcessInfo(41, START, "test-user", "C:\\synthetic\\VRChat.exe", List.of())));
        assertTrue(ManagedVrcClient.isClient(new ProcessInfo(41, START, "test-user", "/synthetic/wine64-preloader", List.of("Z:\\synthetic\\VRChat.exe"))));
        assertTrue(ManagedVrcClient.isClient(new ProcessInfo(41, START, "test-user", "/synthetic/wine", List.of("/synthetic/VRChat.exe", "--no-vr"))));
    }

    @Test void laterSecondClientPermanentlyInvalidatesManagedBinding()
    {
        Client client = managed();
        this.processes.entries.add(process(42));
        assertNull(this.tracker.current());
        this.processes.entries.remove(1);
        this.clock.advance(5);
        assertNull(this.tracker.current());
        assertEquals(CloseResult.NOT_MANAGED, this.tracker.close(client, () -> true));
    }

    @Test void finalCloseRechecksThatNoSecondClientWasStarted()
    {
        Client client = managed();
        this.processes.entries.add(process(42));
        assertEquals(CloseResult.IDENTITY_CHANGED, this.tracker.close(client, () -> true));
        assertEquals(0, this.processes.signals);
        this.processes.entries.remove(1);
        assertNull(this.tracker.current());
    }

    @Test void secondClientAppearingAtTransportBoundaryPreventsClose()
    {
        Client client = managed();
        this.processes.beforeClose = () -> this.processes.entries.add(process(42));
        assertEquals(CloseResult.UNSUPPORTED_OR_DENIED, this.tracker.close(client, () -> true));
        assertEquals(0, this.processes.signals);
        this.processes.entries.remove(1);
        assertNull(this.tracker.current());
    }

    @Test void lostProcessEnumerationPermanentlyInvalidatesExistingBinding()
    {
        Client client = managed();
        this.processes.throwSnapshot = true;
        assertNull(this.tracker.current());
        this.processes.throwSnapshot = false;
        assertNull(this.tracker.current());
        assertEquals(CloseResult.NOT_MANAGED, this.tracker.close(client, () -> true));
    }

    @Test void looseNameMatchesShellCommandsAndWineOtherProgramsAreNotClients()
    {
        assertFalse(ManagedVrcClient.isClient(null));
        assertFalse(ManagedVrcClient.isClient(new ProcessInfo(41, START, "test-user", null, List.of("VRChat.exe"))));
        assertFalse(ManagedVrcClient.isClient(new ProcessInfo(41, START, "test-user", "VRChat.exe.backup", List.of())));
        assertFalse(ManagedVrcClient.isClient(new ProcessInfo(41, START, "test-user", "/bin/sh", List.of("VRChat.exe"))));
        assertFalse(ManagedVrcClient.isClient(new ProcessInfo(41, START, "test-user", "/synthetic/wine", List.of("notepad.exe", "VRChat.exe"))));
        assertFalse(ManagedVrcClient.isClient(new ProcessInfo(41, START, "test-user", "/synthetic/wine64", List.of())));
    }

    @Test void confirmedPidQueryReportsLiveManagedClientOnly()
    {
        managed();
        assertEquals(List.of("41"), this.tracker.confirmedClientPids());
        assertEquals(0, this.processes.attempts);
    }

    @Test void confirmedPidQueryRecognizesUnownedClientWithoutAdoptingIt()
    {
        this.processes.entries.add(new ProcessInfo(77, START.minusSeconds(1), "other-user", "/synthetic/VRChat.exe", List.of()));
        assertEquals(List.of("77"), this.tracker.confirmedClientPids());
        assertNull(this.tracker.current());
        assertEquals(0, this.processes.attempts);
    }

    @Test void confirmedPidQueryReturnsEmptyOnSnapshotFailure()
    {
        this.processes.entries.add(process(41));
        this.processes.throwSnapshot = true;
        assertEquals(List.of(), this.tracker.confirmedClientPids());
        assertTrue(this.tracker.hasClientsOrPending(), "Conservative launch blocking is intentionally different from a confirmed live PID query");
    }

    @Test void confirmedPidQueryReturnsNoPartialResultsFromUnknownInventory()
    {
        this.processes.entries.add(process(41));
        this.processes.entries.add(new ProcessInfo(77, START, "test-user", null, List.of()));
        assertEquals(List.of(), this.tracker.confirmedClientPids());
        assertTrue(this.tracker.hasClientsOrPending());
    }

    @Test void confirmedPidQueryDoesNotCountPendingLauncherAsLiveClient()
    {
        begin();
        assertTrue(this.tracker.hasClientsOrPending());
        assertEquals(List.of(), this.tracker.confirmedClientPids());
        assertNull(this.tracker.current());
        assertEquals(0, this.processes.attempts);
    }

    @Test void confirmedPidQueryDoesNotReturnPidZeroOrLooseNameMatches()
    {
        this.processes.entries.add(new ProcessInfo(0, START, "test-user", "VRChat.exe", List.of()));
        this.processes.entries.add(new ProcessInfo(77, START, "test-user", "VRChat.exe.backup", List.of()));
        assertEquals(List.of(), this.tracker.confirmedClientPids());
        assertEquals(0, this.processes.attempts);
    }
}

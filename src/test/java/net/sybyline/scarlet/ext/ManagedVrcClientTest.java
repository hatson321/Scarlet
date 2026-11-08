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
        assertEquals(CloseResult.FINAL_GUARD_VETO, this.tracker.close(client, () -> false));
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

    @Test void truncatedOrUnrecognizedWineNamesCannotAuthorizeAnotherLaunch()
    {
        for (String name : List.of("wine", "wine64", "wine-preloader", "wine64-preloader", "wine64-preloade", "wine-unknown"))
        {
            this.processes.entries.clear();
            this.processes.entries.add(new ProcessInfo(77, START.minusSeconds(1), "test-user", "/synthetic/" + name, List.of()));
            assertEquals(0, this.tracker.beginLaunch(LOCATION), name);
            assertTrue(this.tracker.hasClientsOrPending(), name);
            assertNull(this.tracker.current());
            assertEquals(0, this.processes.signals);
        }
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

    private static ProcessInfo loader(long pid, List<String> arguments)
    {
        return new ProcessInfo(pid, START, "test-user", "/synthetic/wine64-preloader", arguments, 100);
    }

    private static ProcessInfo procProgram(ProcessInfo process, String name)
    {
        return ManagedVrcClient.resolveLinuxMetadata(process, name, "C:\\synthetic\\" + name, process);
    }

    @Test void corroboratedWineHelpersDoNotBlockAnOtherwiseEmptyInventory()
    {
        for (String helper : List.of("services.exe", "winedevice.exe", "plugplay.exe", "rpcss.exe",
            "svchost.exe", "explorer.exe", "conhost.exe"))
        {
            FakeProcesses fake = new FakeProcesses();
            ProcessInfo original = loader(77, List.of());
            ProcessInfo resolved = procProgram(original, helper);
            fake.entries.add(resolved);
            assertEquals(original.executable(), resolved.executable(), helper);
            assertEquals(original.arguments(), resolved.arguments(), helper);
            assertTrue(resolved.inventoryOnly(), helper);
            assertFalse(ManagedVrcClient.isClient(resolved), helper);
            assertFalse(ManagedVrcClient.uncertainWineIdentity(resolved), helper);
            Tracker tested = new Tracker(fake, this.clock);
            assertFalse(tested.hasClientsOrPending(), helper);
            assertTrue(tested.beginLaunch(LOCATION) > 0, helper);
            assertEquals(0, fake.signals, helper);
        }
    }

    @Test void exactNativeWineServerNeedsAPathAndCompleteIdentity()
    {
        ProcessInfo server = new ProcessInfo(77, START, "test-user", "/synthetic/wineserver", List.of());
        assertFalse(ManagedVrcClient.uncertainWineIdentity(server));
        for (ProcessInfo uncertain : List.of(
            new ProcessInfo(77, START, "test-user", "wineserver", List.of()),
            new ProcessInfo(77, START, "test-user", "relative/wineserver", List.of()),
            new ProcessInfo(77, null, "test-user", "/synthetic/wineserver", List.of()),
            new ProcessInfo(77, START, null, "/synthetic/wineserver", List.of()),
            new ProcessInfo(77, START, "test-user", "/synthetic/wineserver64", List.of())))
            assertTrue(ManagedVrcClient.uncertainWineIdentity(uncertain));
    }

    @Test void procClientHintBlocksDuplicateLaunchAndReportsPresenceWithoutGrantingOwnership()
    {
        ProcessInfo original = loader(77, List.of("--no-vr"));
        ProcessInfo resolved = procProgram(original, "VRChat.exe");
        this.processes.entries.add(resolved);
        assertTrue(ManagedVrcClient.isClient(resolved));
        assertFalse(ManagedVrcClient.uncertainWineIdentity(resolved));
        assertTrue(this.tracker.hasClientsOrPending());
        assertEquals(List.of("77"), this.tracker.confirmedClientPids());
        assertEquals(0, this.tracker.beginLaunch(LOCATION));
        assertNull(this.tracker.current());
        SingleVrcClient.Selection selected = this.tracker.shutdownClient(LOCATION, START);
        assertEquals(SingleVrcClient.Status.IDENTITY_UNVERIFIED, selected.status);
        assertNull(selected.client);
        assertFalse(ManagedVrcClient.matches(new Client(1, LOCATION, START, 77, START, "test-user"), resolved));
        assertEquals(0, this.processes.attempts);
    }

    @Test void procClientHintCannotBecomeALaunchDescendantWithCloseAuthority()
    {
        begin();
        ProcessInfo raw = new ProcessInfo(41, this.clock.instant(), "test-user", "/synthetic/wine64", List.of(), 100);
        this.processes.entries.add(procProgram(raw, "VRChat.exe"));
        assertNull(this.tracker.current());
        this.clock.advance(5);
        assertNull(this.tracker.current());
        assertEquals(0, this.processes.attempts);
        assertEquals(0, this.processes.signals);
    }

    @Test void rawClientArgumentsWinOverConflictingHelperNames()
    {
        ProcessInfo raw = loader(77, List.of("Z:\\synthetic\\VRChat.exe", "--no-vr"));
        ProcessInfo resolved = procProgram(raw, "services.exe");
        assertSame(raw, resolved);
        assertTrue(ManagedVrcClient.isClient(resolved));
        assertFalse(resolved.inventoryOnly());
        assertFalse(ManagedVrcClient.uncertainWineIdentity(resolved));
    }

    @Test void rawUnknownLoaderAndConflictingExecutableCannotBecomeHelpers()
    {
        for (String executable : List.of("wine64-preloade", "wine-other", "wine64.exe"))
        {
            ProcessInfo raw = new ProcessInfo(77, START, "test-user", "/synthetic/" + executable, List.of());
            assertSame(raw, procProgram(raw, "services.exe"));
            assertTrue(ManagedVrcClient.uncertainWineIdentity(raw));
        }
        ProcessInfo conflict = loader(77, List.of("notepad.exe"));
        assertSame(conflict, procProgram(conflict, "services.exe"));
        assertFalse(ManagedVrcClient.uncertainWineIdentity(conflict));
        assertFalse(ManagedVrcClient.isClient(conflict));
    }

    @Test void optionShapedClientNamesNeverBecomeOwnershipEvidence()
    {
        for (String argument : List.of("--program=C:\\synthetic\\VRChat.exe", "--config=/synthetic/VRChat.exe",
            "--path/VRChat.exe", "program=C:\\synthetic\\VRChat.exe", "VRChat.exe\n"))
        {
            ProcessInfo raw = loader(77, List.of(argument));
            assertFalse(ManagedVrcClient.isClient(raw), argument);
            assertTrue(ManagedVrcClient.uncertainWineIdentity(raw), argument);
            assertFalse(ManagedVrcClient.matches(new Client(1, LOCATION, START, 77, START, "test-user"), raw));
        }
    }

    @Test void equalsInsideAnAbsoluteProgramPathDoesNotHideTheClient()
    {
        for (String argument : List.of("C:\\synthetic=a\\VRChat.exe", "/synthetic=a/VRChat.exe"))
        {
            ProcessInfo raw = loader(77, List.of(argument));
            assertTrue(ManagedVrcClient.isClient(raw), argument);
            assertFalse(ManagedVrcClient.uncertainWineIdentity(raw), argument);
        }
    }

    @Test void unrelatedWineProgramDoesNotBecomeANewLaunchBlocker()
    {
        ProcessInfo other = loader(77, List.of("C:\\synthetic\\notepad.exe"));
        this.processes.entries.add(other);
        assertFalse(ManagedVrcClient.isClient(other));
        assertFalse(this.tracker.hasClientsOrPending());
        assertTrue(this.tracker.beginLaunch(LOCATION) > 0);
        for (String ambiguous : List.of("--no-vr", "--program=notepad.exe", "--notepad.exe", "notepad", ""))
            assertTrue(ManagedVrcClient.uncertainWineIdentity(loader(77, List.of(ambiguous))));
    }

    @Test void unknownTruncatedBlankAndMismatchedProcNamesRemainUncertain()
    {
        ProcessInfo raw = loader(77, List.of());
        for (String name : Arrays.asList(null, "", " ", "wine64-preloade", "services.ex", "unknown.exe",
            "services.exe.backup", "services.exe\n", "path/services.exe", "services.exe\0"))
        {
            ProcessInfo resolved = ManagedVrcClient.resolveLinuxMetadata(raw, name, "C:\\synthetic\\services.exe", raw);
            assertSame(raw, resolved, String.valueOf(name));
            assertTrue(ManagedVrcClient.uncertainWineIdentity(resolved));
        }
        for (String argument : Arrays.asList(null, "", "VRChat.exe", "services.exe.bad", "services.exe\0",
            "services.exe\n", "services.exe\r", "--program=C:\\synthetic\\services.exe",
            "program=C:\\synthetic\\services.exe", "--path/services.exe"))
            assertSame(raw, ManagedVrcClient.resolveLinuxMetadata(raw, "services.exe", argument, raw));
    }

    @Test void incompleteIdentityCannotBeEnrichedByProcNames()
    {
        for (ProcessInfo raw : List.of(
            new ProcessInfo(77, null, "test-user", "/synthetic/wine64", List.of()),
            new ProcessInfo(77, START, null, "/synthetic/wine64", List.of()),
            new ProcessInfo(0, START, "test-user", "/synthetic/wine64", List.of())))
        {
            assertSame(raw, procProgram(raw, "services.exe"));
            assertTrue(ManagedVrcClient.uncertainWineIdentity(raw));
        }
    }

    @Test void changingRawIdentityDuringProcReadRejectsTheEntireSample()
    {
        ProcessInfo raw = loader(77, List.of());
        for (ProcessInfo changed : Arrays.asList(null,
            new ProcessInfo(78, START, "test-user", raw.executable(), List.of(), 100),
            new ProcessInfo(77, START.plusSeconds(1), "test-user", raw.executable(), List.of(), 100),
            new ProcessInfo(77, START, "other-user", raw.executable(), List.of(), 100),
            new ProcessInfo(77, START, "test-user", "/other/wine64", List.of(), 100),
            new ProcessInfo(77, START, "test-user", raw.executable(), List.of("changed"), 100),
            new ProcessInfo(77, START, "test-user", raw.executable(), List.of(), 101)))
            assertThrows(IllegalStateException.class,
                () -> ManagedVrcClient.resolveLinuxMetadata(raw, "services.exe", "services.exe", changed));
    }

    @Test void hintProvenanceChangesInvalidateProcessEquality()
    {
        ProcessInfo raw = loader(77, List.of());
        ProcessInfo helper = procProgram(raw, "services.exe"), client = procProgram(raw, "VRChat.exe");
        assertFalse(ManagedVrcClient.sameProcess(raw, helper));
        assertFalse(ManagedVrcClient.sameProcess(helper, client));
        assertTrue(ManagedVrcClient.sameProcess(helper, procProgram(raw, "services.exe")));
    }

    @Test void protectedCommandNameFallbackCanReportClientButNeverControlIt()
    {
        ProcessInfo raw = new ProcessInfo(77, START, "test-user", null, List.of());
        ProcessInfo named = ManagedVrcClient.resolveLinuxMetadata(raw, "VRChat.exe", null, raw);
        this.processes.entries.add(named);
        assertTrue(named.inventoryOnly());
        assertTrue(ManagedVrcClient.isClient(named));
        assertEquals(0, this.tracker.beginLaunch(LOCATION));
        assertEquals(SingleVrcClient.Status.IDENTITY_UNVERIFIED,
            this.tracker.shutdownClient(LOCATION, START).status);
        assertFalse(ManagedVrcClient.matches(new Client(1, LOCATION, START, 77, START, "test-user"), named));
        assertThrows(IllegalStateException.class,
            () -> ManagedVrcClient.resolveLinuxMetadata(raw, "wine64-preloade", null, raw));
    }

    @Test void secondHintOnlyClientStillInvalidatesManagedClient()
    {
        Client client = managed();
        this.processes.entries.add(procProgram(loader(77, List.of()), "VRChat.exe"));
        assertNull(this.tracker.current());
        assertEquals(CloseResult.NOT_MANAGED, this.tracker.close(client, () -> true));
        assertEquals(0, this.processes.signals);
    }

    private static java.io.InputStream bytes(String value)
    {
        return new java.io.ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void procCommandNameParserRequiresACompleteUntruncatedRecord() throws Exception
    {
        assertEquals("VRChat.exe", ManagedVrcClient.readProcCommandName(bytes("VRChat.exe\n")));
        assertEquals("winedevice.exe", ManagedVrcClient.readProcCommandName(bytes("winedevice.exe\n")));
        for (String invalid : List.of("", "\n", "services.exe", "services.exe\nX", "services.exe\0\n",
            "services.exe\n\n", "1234567890123456\n"))
            assertThrows(java.io.IOException.class, () -> ManagedVrcClient.readProcCommandName(bytes(invalid)));
    }

    @Test void procArgumentParserReadsOnlyBoundedArgvZero() throws Exception
    {
        java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(
            "C:\\synthetic\\VRChat.exe\0--private-synthetic-argument\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("C:\\synthetic\\VRChat.exe", ManagedVrcClient.readProcFirstArgument(input));
        assertEquals('-', input.read(), "Later arguments must not be consumed");
        for (String invalid : List.of("", "\0", "unterminated", "x".repeat(4096) + "\0"))
            assertThrows(java.io.IOException.class, () -> ManagedVrcClient.readProcFirstArgument(bytes(invalid)));
    }
}

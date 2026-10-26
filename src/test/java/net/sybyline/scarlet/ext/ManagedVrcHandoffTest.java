package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.sybyline.scarlet.ext.ManagedVrcClient.Client;
import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;
import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessLease;
import net.sybyline.scarlet.ext.ManagedVrcClient.Tracker;

/** Entire launch histories are synthetic. No actual game, launcher or OS inventory is accessed. */
class ManagedVrcHandoffTest
{
    static final Instant START = Instant.parse("2026-10-24T12:00:00Z");
    static final String LOCATION = "wrld_synthetic:1~group(grp_synthetic)";

    static final class TestClock extends Clock
    {
        Instant now = START;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return this.now; }
        void advance(long seconds) { this.now = this.now.plusSeconds(seconds); }
    }

    static final class FakeProcesses implements ManagedVrcClient.Processes
    {
        final List<ProcessInfo> entries = new ArrayList<>();
        final Map<Long, WindowsProcessEvidenceTest.FakeAccess> receipts = new HashMap<>();
        int signals;
        boolean unavailable;
        public String currentUser() { return "synthetic-user"; }
        public List<ProcessInfo> snapshot()
        {
            if (this.unavailable) throw new SecurityException("synthetic inventory refusal");
            return new ArrayList<>(this.entries);
        }
        public ProcessInfo find(long pid) { return this.entries.stream().filter(p -> p.pid() == pid).findFirst().orElse(null); }
        public boolean requestClose(ProcessInfo expected, BooleanSupplier revalidate)
        {
            if (!revalidate.getAsBoolean()) return false;
            this.signals++;
            return true;
        }
        public ProcessLease retain(ProcessInfo expected)
        {
            WindowsProcessEvidenceTest.FakeAccess access = this.receipts.get(expected.pid());
            return access == null ? null : WindowsProcessEvidence.retain(expected, access);
        }
        WindowsProcessEvidenceTest.FakeAccess receipt(ProcessInfo info)
        {
            WindowsProcessEvidenceTest.FakeAccess access = new WindowsProcessEvidenceTest.FakeAccess();
            access.identity = info;
            access.lifetime = new WindowsProcessEvidence.Lifetime(info.startedAt(), null);
            this.receipts.put(info.pid(), access);
            return access;
        }
    }

    final TestClock clock = new TestClock();
    final FakeProcesses processes = new FakeProcesses();
    final Tracker tracker = new Tracker(this.processes, this.clock);
    final ProcessInfo root = process(100, "launch.exe", START, -1);
    WindowsProcessEvidenceTest.FakeAccess rootAccess;

    static ProcessInfo process(long pid, String executable, Instant started, long parent)
    {
        return new ProcessInfo(pid, started, "synthetic-user", "/synthetic/" + executable, List.of(), parent);
    }

    long begin()
    {
        long token = this.tracker.beginLaunch(LOCATION);
        assertTrue(token > 0);
        this.processes.entries.add(this.root);
        this.rootAccess = this.processes.receipt(this.root);
        this.tracker.launchCompleted(token, this.root);
        return token;
    }

    ProcessInfo client()
    {
        ProcessInfo client = process(200, "VRChat.exe", START.plusSeconds(1), 100);
        this.processes.entries.add(client);
        return client;
    }

    void exitRoot(long seconds)
    {
        this.rootAccess.lifetime = new WindowsProcessEvidence.Lifetime(START, START.plusSeconds(seconds));
        this.processes.entries.remove(this.root);
    }

    @Test void childDiscoveredAfterItsVerifiedLauncherExitedCanBeManaged()
    {
        long token = begin();
        client();
        exitRoot(2);
        this.clock.advance(3);
        assertNull(this.tracker.current());
        this.clock.advance(3);
        Client managed = this.tracker.current();
        assertNotNull(managed);
        assertEquals(token, managed.launchToken());
        assertEquals(LOCATION, managed.requestedLocation());
        assertEquals(200, managed.pid());
        assertEquals(1, this.rootAccess.closes, "A verified binding retains no discovery handles");
        assertEquals(ManagedVrcClient.CloseResult.FINAL_GUARD_VETO, this.tracker.close(managed, () -> false));
        assertEquals(0, this.processes.signals);
        assertEquals(ManagedVrcClient.CloseResult.REQUESTED, this.tracker.close(managed, () -> true));
        assertEquals(1, this.processes.signals);
    }

    @Test void observedIntermediateMayExitBeforeItsChildIsDiscovered()
    {
        begin();
        ProcessInfo bootstrap = process(150, "bootstrap.exe", START.plusSeconds(1), 100);
        this.processes.entries.add(bootstrap);
        WindowsProcessEvidenceTest.FakeAccess intermediate = this.processes.receipt(bootstrap);
        this.clock.advance(2);
        assertNull(this.tracker.current()); // captures the verified intermediate
        exitRoot(2);
        this.processes.entries.remove(bootstrap);
        intermediate.lifetime = new WindowsProcessEvidence.Lifetime(bootstrap.startedAt(), START.plusSeconds(3));
        this.processes.entries.add(process(200, "VRChat.exe", START.plusSeconds(2), 150));
        this.clock.advance(2);
        assertNull(this.tracker.current());
        this.clock.advance(3);
        assertNotNull(this.tracker.current());
        assertEquals(1, this.rootAccess.closes);
        assertEquals(1, intermediate.closes);
    }

    @Test void anEntirelyUnobservedIntermediateIsNotInvented()
    {
        begin();
        exitRoot(2);
        this.processes.entries.add(process(200, "VRChat.exe", START.plusSeconds(1), 150));
        this.clock.advance(3);
        assertNull(this.tracker.current());
        this.clock.advance(10);
        assertNull(this.tracker.current());
        assertEquals(1, this.rootAccess.closes);
    }

    @Test void postExitChildWithRecycledParentNumberRemainsUnmanaged()
    {
        begin();
        exitRoot(1);
        this.processes.entries.add(process(200, "VRChat.exe", START.plusSeconds(1).plusNanos(100), 100));
        this.clock.advance(3);
        assertNull(this.tracker.current());
        assertEquals(1, this.rootAccess.closes);
    }

    @Test void contradictoryLiveParentIdentityOverridesRetainedEvidence()
    {
        begin();
        client();
        this.processes.entries.remove(this.root);
        this.processes.entries.add(process(100, "launch.exe", START.plusNanos(100), -1));
        this.clock.advance(3);
        assertNull(this.tracker.current());
        assertEquals(1, this.rootAccess.closes);
    }

    @Test void wrongOwnerOrPreexistingClientCannotBorrowRetainedParentage()
    {
        begin();
        this.processes.entries.add(new ProcessInfo(200, START.plusSeconds(1), "other-user", "/synthetic/VRChat.exe", List.of(), 100));
        this.clock.advance(3);
        assertNull(this.tracker.current());
        assertEquals(1, this.rootAccess.closes);
        assertEquals(0, this.processes.signals);
    }

    @Test void twoCandidatesPermanentlyInvalidateAndReleaseAllHandles()
    {
        begin();
        client();
        this.processes.entries.add(process(201, "VRChat.exe", START.plusSeconds(1), 100));
        this.clock.advance(3);
        assertNull(this.tracker.current());
        assertEquals(1, this.rootAccess.closes);
        this.processes.entries.removeIf(p -> p.pid() == 201);
        this.clock.advance(3);
        assertNull(this.tracker.current());
    }

    @Test void failedAttemptTimeoutCancellationAndShutdownReleaseReferences()
    {
        for (int scenario = 0; scenario < 4; scenario++)
        {
            TestClock time = new TestClock();
            FakeProcesses inventory = new FakeProcesses();
            Tracker tracker = new Tracker(inventory, time);
            long token = tracker.beginLaunch(LOCATION);
            inventory.entries.add(this.root);
            WindowsProcessEvidenceTest.FakeAccess access = inventory.receipt(this.root);
            tracker.launchCompleted(token, this.root);
            if (scenario == 0) tracker.launchFailed(token);
            if (scenario == 1) { time.advance(121); assertNull(tracker.current()); }
            if (scenario == 2) tracker.cancelPendingLaunches();
            if (scenario == 3) tracker.stopLaunching();
            assertEquals(1, access.closes, "Scenario " + scenario);
            tracker.launchFailed(token);
            assertEquals(1, access.closes);
        }
    }

    @Test void unavailableInventoryOrLifetimeReleasesHandlesAndDoesNotCloseClient()
    {
        begin();
        client();
        exitRoot(2);
        this.clock.advance(3);
        this.rootAccess.throwLifetime = true;
        assertNull(this.tracker.current());
        assertEquals(1, this.rootAccess.closes);
        assertEquals(0, this.processes.signals);
    }

    @Test void replacingAttemptReleasesOldHandlesAndOldCompletionCannotAdopt()
    {
        long old = begin();
        this.processes.entries.clear();
        long replacement = this.tracker.beginLaunch(LOCATION);
        assertTrue(replacement > old);
        assertEquals(1, this.rootAccess.closes);
        this.tracker.launchCompleted(old, this.root);
        client();
        this.clock.advance(3);
        assertNull(this.tracker.current());
    }

    @Test void duplicateCompletionDoesNotLeakOrReplaceAnOwnedReceipt()
    {
        long token = begin();
        this.tracker.launchCompleted(token, this.root);
        assertEquals(1, this.rootAccess.opens);
        assertEquals(0, this.rootAccess.closes);
        this.tracker.launchFailed(token);
        assertEquals(1, this.rootAccess.closes);
    }

    @Test void capacityLimitReleasesEveryRetainedIntermediate()
    {
        begin();
        for (int i = 0; i < 130; i++)
        {
            ProcessInfo child = process(1000 + i, "bootstrap.exe", START.plusSeconds(1), 100);
            this.processes.entries.add(child);
            this.processes.receipt(child);
        }
        this.clock.advance(2);
        assertNull(this.tracker.current());
        int opens = 0, closes = 0;
        for (WindowsProcessEvidenceTest.FakeAccess access : this.processes.receipts.values())
        {
            opens += access.opens;
            closes += access.closes;
        }
        assertEquals(128, opens);
        assertEquals(opens, closes);
    }

    @Test void fullLaunchTimeoutReleasesReferencesAndReportsOnlyFixedDiagnosticText() throws Exception
    {
        TestClock time = new TestClock();
        FakeProcesses inventory = new FakeProcesses();
        long[] elapsed = {0};
        Tracker tracker = new Tracker(inventory, time, () -> elapsed[0], millis -> {
            elapsed[0] += millis * 1_000_000L;
            time.now = time.now.plusMillis(millis);
        });
        ManagedVrcClient.LaunchFailure failure = assertThrows(ManagedVrcClient.LaunchFailure.class,
            () -> tracker.launch(LOCATION, false, () -> {
                ProcessInfo root = process(100, "private-path-token-launch.exe", time.instant(), -1);
                inventory.entries.add(root);
                inventory.receipt(root);
                return root;
            }));
        assertEquals(1, inventory.receipts.get(100L).closes);
        assertTrue(failure.description().contains("No verifiable client completed startup"));
        assertFalse(failure.description().contains("private-path-token"));
        assertFalse(failure.description().contains("synthetic-user"));
        assertFalse(failure.description().contains(LOCATION));
        assertTrue(tracker.hasClientsOrPending(), "Unresolved live launcher remains a blocker after its evidence handle is closed");
    }

    @Test void fullLaunchCancellationReleasesReferencesAndRetainsUnresolvedLauncherBlock() throws Exception
    {
        TestClock time = new TestClock();
        FakeProcesses inventory = new FakeProcesses();
        long[] elapsed = {0};
        Tracker[] tracker = new Tracker[1];
        tracker[0] = new Tracker(inventory, time, () -> elapsed[0], millis -> {
            elapsed[0] += millis * 1_000_000L;
            time.now = time.now.plusMillis(millis);
            if (!inventory.receipts.isEmpty()) tracker[0].cancelPendingLaunches();
        });
        try
        {
            assertThrows(InterruptedException.class, () -> tracker[0].launch(LOCATION, false, () -> {
                ProcessInfo root = process(100, "launch.exe", time.instant(), -1);
                inventory.entries.add(root);
                inventory.receipt(root);
                return root;
            }));
            assertEquals(1, inventory.receipts.get(100L).closes);
            assertTrue(tracker[0].hasClientsOrPending());
        }
        finally { Thread.interrupted(); }
    }
}

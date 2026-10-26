package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.sybyline.scarlet.ext.ManagedVrcClient.*;
import net.sybyline.scarlet.ext.SingleVrcClient.Status;

/** Fake process metadata and transports only; never enumerates or closes real processes. */
class SingleVrcClientTest
{
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z"), JOIN = START.plusSeconds(30);
    static final String LOCATION = "synthetic-location";
    static ProcessInfo process(long pid) { return new ProcessInfo(pid, START, "test-user", "/fake/VRChat.exe", List.of()); }
    static class Fake implements Processes
    {
        List<ProcessInfo> entries = new ArrayList<>(List.of(process(42)));
        String user = "test-user";
        boolean unavailable, missingFind;
        int closes, forces;
        Runnable beforeDispatch = () -> {};
        public String currentUser() { return user; }
        public List<ProcessInfo> snapshot() { if (unavailable) throw new IllegalStateException(); return entries; }
        public ProcessInfo find(long pid) { return missingFind ? null : entries.stream().filter(p -> p.pid() == pid).findFirst().orElse(null); }
        public boolean requestClose(ProcessInfo expected, BooleanSupplier guard) { return requestCloseDetailed(expected, guard) == CloseResult.REQUESTED; }
        public CloseResult requestCloseDetailed(ProcessInfo expected, BooleanSupplier guard)
        { beforeDispatch.run(); if (!guard.getAsBoolean()) return CloseResult.FINAL_GUARD_VETO; closes++; return CloseResult.REQUESTED; }
        public ForceResult forceClose(ProcessInfo expected, BooleanSupplier guard)
        { beforeDispatch.run(); if (!guard.getAsBoolean()) return ForceResult.GUARD_VETO; forces++; return ForceResult.REQUESTED; }
    }
    static class Fixture
    {
        Fake fake = new Fake();
        java.util.concurrent.atomic.AtomicLong elapsed = new java.util.concurrent.atomic.AtomicLong();
        Tracker tracker = new Tracker(fake, Clock.fixed(JOIN.plusSeconds(60), ZoneOffset.UTC), elapsed::get,
            millis -> elapsed.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis)));
        SingleVrcClient.Selection select() { return tracker.shutdownClient(LOCATION, JOIN); }
    }

    @Test void manualClientClosesButNeverBecomesManagedOrRestartable() throws Exception
    {
        Fixture f = new Fixture(); Client c = f.select().client;
        assertNotNull(c); assertSame(c, f.select().client); assertNull(f.tracker.current());
        assertEquals(CloseResult.NOT_MANAGED, f.tracker.close(c, () -> true));
        assertEquals(ForceResult.NOT_MANAGED, f.tracker.forceClose(c, () -> true));
        assertThrows(java.io.IOException.class, () -> f.tracker.launch(LOCATION, true,
            (ManagedVrcClient.LaunchAction) () -> { fail("Manual client must never authorize a replacement launch"); return null; }));
        assertEquals(0, f.fake.closes);
        assertEquals(CloseResult.REQUESTED, f.tracker.closeShutdownClient(c, () -> true));
        assertEquals(1, f.fake.closes); assertEquals(0, f.fake.forces); assertNull(f.tracker.current());
    }

    @Test void separateForcePathRequiresFreshGuardAndDoesNotGrantLaunchOwnership()
    {
        Fixture f = new Fixture(); Client c = f.select().client;
        assertEquals(ForceResult.GUARD_VETO, f.tracker.forceShutdownClient(c, () -> false));
        assertEquals(0, f.fake.forces);
        assertEquals(ForceResult.REQUESTED, f.tracker.forceShutdownClient(c, () -> true));
        assertEquals(1, f.fake.forces); assertNull(f.tracker.current());
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2,3,4,5,6,7,8,9,10})
    void incompleteOrAmbiguousInventoryCannotSelect(int kind)
    {
        Fixture f = new Fixture();
        switch (kind)
        {
            case 0: f.fake.entries.clear(); break;
            case 1: f.fake.entries.add(process(43)); break;
            case 2: f.fake.unavailable = true; break;
            case 3: f.fake.missingFind = true; break;
            case 4: f.fake.user = "other-user"; break;
            case 5: f.fake.entries = Arrays.asList((ProcessInfo) null); break;
            case 6: f.fake.entries.add(process(42)); break;
            case 7: f.fake.entries.set(0, new ProcessInfo(42, null, "test-user", "/fake/VRChat.exe", List.of())); break;
            case 8: f.fake.entries.set(0, new ProcessInfo(42, START, null, "/fake/VRChat.exe", List.of())); break;
            case 9: f.fake.entries.set(0, new ProcessInfo(42, START, "test-user", "VRChat.exe", List.of())); break;
            case 10: f.fake.entries.add(new ProcessInfo(43, START, "other-user", "wine64", List.of())); break;
        }
        assertNull(f.select().client);
        if (kind == 1) assertEquals(Status.MULTIPLE, f.select().status);
        assertEquals(0, f.fake.closes); assertEquals(0, f.fake.forces);
    }

    @Test void secondClientWithUnreadableIdentityStillCountsAsMultiple()
    {
        Fixture f = new Fixture(); f.fake.entries.add(new ProcessInfo(43, null, null, "VRChat.exe", List.of()));
        assertEquals(Status.MULTIPLE, f.select().status); assertNull(f.select().client);
    }

    @Test void unrelatedProtectedProcessDoesNotNeedPrivateMetadata()
    {
        Fixture f = new Fixture(); f.fake.entries.add(new ProcessInfo(43, null, null, "system.exe", List.of()));
        assertEquals(Status.READY, f.select().status);
    }

    @Test void oldOrFutureLogSessionCannotBindAClient()
    {
        Fixture f = new Fixture();
        assertNull(f.tracker.shutdownClient(LOCATION, START.minusNanos(1)).client);
        assertNull(f.tracker.shutdownClient(LOCATION, JOIN.plusSeconds(61)).client);
        assertNull(f.tracker.shutdownClient(null, JOIN).client);
        assertNull(f.tracker.shutdownClient(LOCATION, null).client);
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2,3,4,5,6})
    void identityChangesAtNativeBoundaryVetoNormalAndForce(int kind)
    {
        for (boolean force : List.of(false, true))
        {
            Fixture f = new Fixture(); Client c = f.select().client;
            f.fake.beforeDispatch = () -> {
                switch (kind)
                {
                    case 0: f.fake.entries.add(process(43)); break;
                    case 1: f.fake.entries.set(0, new ProcessInfo(42, START.plusSeconds(1), "test-user", "/fake/VRChat.exe", List.of())); break;
                    case 2: f.fake.user = "other-user"; break;
                    case 3: f.fake.entries.clear(); break;
                    case 4: f.fake.entries.set(0, new ProcessInfo(42, START, "test-user", "/changed/VRChat.exe", List.of())); break;
                    case 5: f.fake.unavailable = true; break;
                    case 6: f.tracker.stopLaunching(); break;
                }
            };
            if (force) assertNotEquals(ForceResult.REQUESTED, f.tracker.forceShutdownClient(c, () -> true));
            else assertNotEquals(CloseResult.REQUESTED, f.tracker.closeShutdownClient(c, () -> true));
            assertEquals(0, f.fake.closes); assertEquals(0, f.fake.forces);
        }
    }

    @Test void changedSessionInvalidatesReceiptAndForeignReceiptsAreRejected()
    {
        Fixture f = new Fixture(); Client old = f.select().client;
        Client fresh = f.tracker.shutdownClient(LOCATION, JOIN.plusSeconds(1)).client;
        assertNotSame(old, fresh);
        assertEquals(CloseResult.NOT_MANAGED, f.tracker.closeShutdownClient(old, () -> true));
        assertEquals(CloseResult.NOT_MANAGED, f.tracker.closeShutdownClient(new Client(fresh.launchToken, fresh.requestedLocation,
            fresh.requestedAt, fresh.pid, fresh.startedAt, fresh.user), () -> true));
    }

    @Test void uncertaintyCannotCloseButSameIdentityCanBeFreshlyRechecked()
    {
        Fixture f = new Fixture(); Client c = f.select().client;
        f.fake.unavailable = true; assertNull(f.select().client);
        assertNotEquals(CloseResult.REQUESTED, f.tracker.closeShutdownClient(c, () -> true));
        f.fake.unavailable = false; assertSame(c, f.select().client);
    }

    @Test void identityVetoIsTerminalNotAWindowRetry()
    {
        Fixture f = new Fixture(); Client c = f.select().client;
        f.fake.beforeDispatch = () -> f.fake.entries.add(process(43));
        CloseResult result = f.tracker.closeShutdownClient(c, () -> true);
        assertEquals(CloseResult.IDENTITY_CHANGED, result); assertFalse(result.retryableBeforeDispatch());
    }

    @Test void activeLauncherCannotRaceShutdownSelectionOrDispatch() throws Exception
    {
        Fixture f = new Fixture(); Client c = f.select().client;
        f.fake.entries.clear();
        assertThrows(java.io.IOException.class, () -> f.tracker.launch(LOCATION, false,
            (ManagedVrcClient.LaunchAction) () -> {
                f.fake.entries.add(process(42));
                assertEquals(Status.LAUNCH_BUSY, f.select().status);
                assertEquals(CloseResult.FINAL_GUARD_VETO, f.tracker.closeShutdownClient(c, () -> true));
                assertEquals(ForceResult.GUARD_VETO, f.tracker.forceShutdownClient(c, () -> true));
                throw new java.io.IOException("synthetic launcher abort");
            }));
        assertEquals(0, f.fake.closes); assertEquals(0, f.fake.forces);
    }
}

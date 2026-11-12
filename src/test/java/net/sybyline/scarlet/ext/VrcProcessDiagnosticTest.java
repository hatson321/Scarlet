package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;
import net.sybyline.scarlet.ext.VrcClientPresence.State;

/** Synthetic metadata and streams only: no process enumeration, game files or native dispatch. */
class VrcProcessDiagnosticTest
{
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private static final String PRIVATE = "private-synthetic-token";

    private static class Processes implements ManagedVrcClient.Processes
    {
        List<ProcessInfo> entries = List.of();
        Throwable failure;
        int snapshots, actions;
        public String currentUser() { return PRIVATE; }
        public List<ProcessInfo> snapshot()
        {
            this.snapshots++;
            if (this.failure instanceof RuntimeException) throw (RuntimeException)this.failure;
            if (this.failure instanceof LinkageError) throw (LinkageError)this.failure;
            return this.entries;
        }
        public ProcessInfo find(long pid) { return null; }
        public boolean requestClose(ProcessInfo expected, BooleanSupplier guard) { this.actions++; return false; }
    }

    private static ManagedVrcClient.Tracker tracker(Processes processes)
    {
        return new ManagedVrcClient.Tracker(processes, Clock.fixed(NOW, ZoneOffset.UTC), () -> 0L,
            millis -> fail("No waiting or launch discovery was requested"));
    }

    private static ProcessInfo process(String executable, List<String> arguments)
    {
        return new ProcessInfo(987654321L, NOW, PRIVATE, executable, arguments, 876543210L);
    }

    private static ProcessInfo loader()
    {
        return process("/" + PRIVATE + "/wine64", List.of());
    }

    private static ByteArrayInputStream bytes(String text)
    {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static VrcClientPresence failed(Runnable operation)
    {
        Throwable failure = assertThrows(IllegalStateException.class, operation::run);
        assertNull(failure.getCause(), "Original exception paths/messages must not be retained");
        return VrcClientPresence.unverified(failure);
    }

    private static void reason(VrcClientPresence presence, String code)
    {
        assertEquals(State.UNVERIFIED, presence.state);
        assertTrue(presence.clientPids().isEmpty());
        assertTrue(presence.diagnostic().contains("reason=" + code + ";"), presence.diagnostic());
        for (String secret : List.of(PRIVATE, "987654321", "876543210", "vrchat://", "grp_", "usr_"))
            assertFalse(presence.diagnostic().contains(secret), secret);
        assertFalse(presence.diagnostic().contains("\n"));
        assertFalse(presence.diagnostic().contains("\r"));
        assertTrue(presence.diagnostic().length() < 400);
        assertEquals(presence.diagnostic(), presence.toString());
    }

    @Test void absentAndUnverifiedStayDistinctAndNeverAuthorizeActions()
    {
        Processes processes = new Processes();
        ManagedVrcClient.Tracker tracker = tracker(processes);
        assertEquals(State.ABSENT, tracker.clientPresence().state);
        assertFalse(tracker.hasClientsOrPending());
        processes.failure = new SecurityException(PRIVATE);
        VrcClientPresence unknown = tracker.clientPresence();
        reason(unknown, "access-denied");
        assertTrue(unknown.rescanSkipReason().contains("could not be verified"));
        assertTrue(tracker.hasClientsOrPending());
        assertEquals(0, tracker.beginLaunch("wrld_synthetic:1"));
        assertEquals(ManagedVrcClient.CloseResult.NOT_MANAGED, tracker.close(null, () -> true));
        assertEquals(0, processes.actions);
    }

    @Test void confirmedPresenceRetainsExistingPidsWithoutLoggingThemOrGrantingOwnership()
    {
        Processes processes = new Processes();
        processes.entries = List.of(process("/" + PRIVATE + "/VRChat.exe", List.of("vrchat://" + PRIVATE)));
        ManagedVrcClient.Tracker tracker = tracker(processes);
        VrcClientPresence presence = tracker.clientPresence();
        assertEquals(State.PRESENT, presence.state);
        assertEquals(List.of("987654321"), presence.clientPids());
        assertEquals("state=present; reason=inventory-verified; clients=1; name-only-clients=0", presence.diagnostic());
        assertThrows(UnsupportedOperationException.class, () -> presence.clientPids().clear());
        assertNull(tracker.current());
        assertEquals(0, processes.actions);
        assertEquals(1, processes.snapshots, "Diagnostics must not enumerate processes again");
    }

    @Test void aClientPlusAnUnreadableOtherProcessStillFailsClosed()
    {
        Processes processes = new Processes();
        processes.entries = List.of(process("VRChat.exe", List.of()), new ProcessInfo(4, null, null, null, List.of()));
        ManagedVrcClient.Tracker tracker = tracker(processes);
        reason(tracker.clientPresence(), "executable-unavailable");
        assertTrue(tracker.confirmedClientPids().isEmpty());
        assertTrue(tracker.hasClientsOrPending());
        assertEquals(0, processes.actions);
    }

    @Test void malformedInventoriesHaveDistinctBoundedReasons()
    {
        Processes processes = new Processes();
        ManagedVrcClient.Tracker tracker = tracker(processes);
        processes.entries = null;
        reason(tracker.clientPresence(), "inventory-unavailable");
        processes.entries = java.util.Collections.nCopies(100001, process("other", List.of()));
        reason(tracker.clientPresence(), "inventory-limit");
        processes.entries = Arrays.asList((ProcessInfo)null);
        reason(tracker.clientPresence(), "invalid-record");
        processes.entries = List.of(new ProcessInfo(-1, NOW, PRIVATE, "other", List.of()));
        reason(tracker.clientPresence(), "invalid-pid");
        processes.entries = List.of(process("VRChat.exe", List.of()), process("other", List.of()));
        reason(tracker.clientPresence(), "duplicate-pid");
    }

    @Test void exceptionMessagesAndTypesNeverBecomeDiagnosticContent()
    {
        Processes processes = new Processes();
        ManagedVrcClient.Tracker tracker = tracker(processes);
        processes.failure = new LinkageError("/" + PRIVATE);
        reason(tracker.clientPresence(), "runtime-unavailable");
        processes.failure = new IllegalArgumentException("vrchat://" + PRIVATE + "\nforged-log");
        reason(tracker.clientPresence(), "inventory-read-failed");
    }

    @Test void unresolvedWineNamesExposeOnlyFixedCategoryAndPresenceFlags()
    {
        Processes processes = new Processes();
        processes.entries = List.of(process("/" + PRIVATE + "/wine-" + PRIVATE, List.of("--" + PRIVATE)));
        VrcClientPresence presence = tracker(processes).clientPresence();
        reason(presence, "wine-identity-unverified");
        assertTrue(presence.diagnostic().contains("process-kind=wine-related"));
        assertTrue(presence.diagnostic().contains("arguments-present=true"));
    }

    @Test void procNameReadFailureIsIdentifiedWithoutReadingArgumentsOrAnotherIdentity()
    {
        reason(failed(() -> ManagedVrcClient.readLinuxMetadata(loader(),
            () -> { throw new IOException("/proc/" + PRIVATE + "/comm"); },
            () -> { fail("No argument read after name failure"); return null; },
            () -> { fail("No reread after name failure"); return null; }, () -> true)), "proc-name-unavailable");
    }

    @Test void malformedAndOversizedProcNamesUseTheSameSafeReadFailure()
    {
        for (String value : List.of("", "x".repeat(20), "VRChat.exe\nextra", "bad\0name\n"))
            reason(failed(() -> ManagedVrcClient.readLinuxMetadata(loader(), () -> bytes(value),
                () -> bytes(PRIVATE), () -> loader(), () -> true)), "proc-name-unavailable");
    }

    @Test void argvZeroFailureIsIdentifiedWithoutReadingTheRestOfTheCommandLine()
    {
        reason(failed(() -> ManagedVrcClient.readLinuxMetadata(loader(), () -> bytes("VRChat.exe\n"),
            () -> { throw new IOException(PRIVATE); }, () -> loader(), () -> true)), "proc-argv0-unavailable");
        ByteArrayInputStream huge = bytes("x".repeat(4096) + "\0" + PRIVATE);
        reason(failed(() -> ManagedVrcClient.readLinuxMetadata(loader(), () -> bytes("VRChat.exe\n"),
            () -> huge, () -> loader(), () -> true)), "proc-argv0-unavailable");
        assertEquals(0, huge.read(), "The existing bounded parser must not consume more than 4096 bytes");
    }

    @Test void secondJavaIdentityFailureIsDistinguishedFromProcReadFailure()
    {
        reason(failed(() -> ManagedVrcClient.readLinuxMetadata(loader(), () -> bytes("VRChat.exe\n"),
            () -> bytes("VRChat.exe\0"), () -> { throw new SecurityException(PRIVATE); }, () -> true)), "java-metadata-unavailable");
    }

    @Test void changingIdentityCannotBeTurnedIntoConfirmedPresence()
    {
        ProcessInfo changed = new ProcessInfo(987654321L, NOW.plusSeconds(1), PRIVATE, loader().executable(), List.of());
        reason(failed(() -> ManagedVrcClient.readLinuxMetadata(loader(), () -> bytes("VRChat.exe\n"),
            () -> bytes("VRChat.exe\0"), () -> changed, () -> true)), "linux-identity-changed");
    }

    @Test void processExitDuringMetadataFailureIsStillIgnored()
    {
        assertNull(ManagedVrcClient.readLinuxMetadata(loader(), () -> { throw new IOException(PRIVATE); },
            () -> bytes(""), () -> loader(), () -> false));
        assertNull(ManagedVrcClient.readLinuxMetadata(loader(), () -> bytes("VRChat.exe\n"),
            () -> bytes("VRChat.exe\0"), () -> null, () -> false));
    }

    @Test void mismatchedNamesAndUnsupportedProgramsHaveActionableReasons()
    {
        reason(failed(() -> ManagedVrcClient.readLinuxMetadata(loader(), () -> bytes("services.exe\n"),
            () -> bytes("VRChat.exe\0"), () -> loader(), () -> true)), "wine-name-mismatch");
        reason(failed(() -> ManagedVrcClient.readLinuxMetadata(loader(), () -> bytes("other.exe\n"),
            () -> bytes("C:/" + PRIVATE + "/other.exe\0"), () -> loader(), () -> true)), "wine-program-unrecognized");
        reason(failed(() -> ManagedVrcClient.readLinuxMetadata(loader(), () -> bytes("VRChat.exe\n"),
            () -> bytes("--" + PRIVATE + "\0"), () -> loader(), () -> true)), "wine-argv0-not-program");
    }

    @Test void incompleteIdentityAndConflictingArgumentsStayUntrusted()
    {
        ProcessInfo incomplete = new ProcessInfo(987654321L, null, PRIVATE, loader().executable(), List.of());
        reason(failed(() -> ManagedVrcClient.readLinuxMetadata(incomplete, () -> bytes("VRChat.exe\n"),
            () -> bytes("VRChat.exe\0"), () -> incomplete, () -> true)), "wine-identity-incomplete");
        ProcessInfo conflict = process(loader().executable(), List.of("--private.exe"));
        reason(failed(() -> ManagedVrcClient.readLinuxMetadata(conflict, () -> bytes("VRChat.exe\n"),
            () -> bytes("VRChat.exe\0"), () -> conflict, () -> true)), "wine-argument-conflict");
    }

    @Test void corroboratedClientHintsRetainDetectionOnlyAuthorityAndDoNotReadLaterArguments()
    {
        ProcessInfo raw = loader();
        ByteArrayInputStream arguments = bytes("C:/" + PRIVATE + "/VRChat.exe\0--private-argument\0");
        ProcessInfo result = ManagedVrcClient.readLinuxMetadata(raw, () -> bytes("VRChat.exe\n"),
            () -> arguments, () -> raw, () -> true);
        assertEquals('-', arguments.read());
        assertTrue(result.inventoryOnly());
        Processes processes = new Processes();
        processes.entries = List.of(result);
        ManagedVrcClient.Tracker tracker = tracker(processes);
        assertEquals(State.PRESENT, tracker.clientPresence().state);
        assertTrue(tracker.clientPresence().diagnostic().contains("name-only-clients=1"));
        assertEquals(0, tracker.beginLaunch("wrld_synthetic:1"));
        assertEquals(SingleVrcClient.Status.IDENTITY_UNVERIFIED, tracker.shutdownClient("wrld_synthetic:1", NOW).status);
        assertEquals(0, processes.actions);
    }

    @Test void nameOnlyFallbackDoesNotReadArgumentsAndKnownHelpersRemainNonClients()
    {
        ProcessInfo hidden = process(null, List.of());
        ProcessInfo fallback = ManagedVrcClient.readLinuxMetadata(hidden, () -> bytes("VRChat.exe\n"),
            () -> { fail("No argv read for the existing name-only fallback"); return null; }, () -> hidden, () -> true);
        assertTrue(fallback.inventoryOnly());
        assertTrue(ManagedVrcClient.isClient(fallback));
        ProcessInfo raw = loader();
        ProcessInfo helper = ManagedVrcClient.readLinuxMetadata(raw, () -> bytes("services.exe\n"),
            () -> bytes("services.exe\0"), () -> raw, () -> true);
        assertFalse(ManagedVrcClient.isClient(helper));
        assertFalse(ManagedVrcClient.uncertainWineIdentity(helper));
    }

    @Test void repeatedAndChangingFailuresAreRateLimitedTogether()
    {
        VrcClientPresence.LogGate gate = new VrcClientPresence.LogGate();
        for (int second = 0; second <= 180; second++)
        {
            VrcClientPresence failure = VrcClientPresence.unverified(second % 2 == 0
                ? new SecurityException(PRIVATE) : new IllegalArgumentException(PRIVATE));
            assertEquals(second % 60 == 0, gate.shouldLog(failure, TimeUnit.SECONDS.toNanos(second)));
        }
    }

    @Test void recoveryIsReportedAfterTheBoundAndHealthyPollingDoesNotRepeat()
    {
        VrcClientPresence.LogGate gate = new VrcClientPresence.LogGate();
        assertTrue(gate.shouldLog(VrcClientPresence.unverified(new SecurityException()), 0L));
        VrcClientPresence absent = VrcClientPresence.verified(List.of(), 0);
        assertFalse(gate.shouldLog(absent, TimeUnit.SECONDS.toNanos(59)));
        assertTrue(gate.shouldLog(absent, TimeUnit.SECONDS.toNanos(60)));
        assertFalse(gate.shouldLog(absent, TimeUnit.DAYS.toNanos(1)));
        assertEquals("no VRChat client was detected", absent.rescanSkipReason());
    }

    @Test void rateLimitWorksAcrossNanoTimeWrap()
    {
        VrcClientPresence.LogGate gate = new VrcClientPresence.LogGate();
        VrcClientPresence unknown = VrcClientPresence.unverified(new SecurityException());
        long start = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(30);
        assertTrue(gate.shouldLog(unknown, start));
        assertFalse(gate.shouldLog(unknown, start + TimeUnit.SECONDS.toNanos(59)));
        assertTrue(gate.shouldLog(unknown, start + TimeUnit.SECONDS.toNanos(60)));
    }

    @Test void emittedDiagnosticsStayPrivateAndBoundedDuringRealTrackerPolling()
    {
        Processes processes = new Processes();
        processes.failure = new SecurityException(PRIVATE + "\nforged");
        long[] now = {0L};
        java.util.ArrayList<String> messages = new java.util.ArrayList<>();
        ManagedVrcClient.Tracker tracker = new ManagedVrcClient.Tracker(processes,
            Clock.fixed(NOW, ZoneOffset.UTC), () -> now[0], millis -> fail("Unexpected wait"), messages::add);
        for (int second = 0; second < 120; second++)
        {
            now[0] = TimeUnit.SECONDS.toNanos(second);
            assertTrue(tracker.confirmedClientPids().isEmpty());
        }
        assertEquals(2, messages.size());
        assertTrue(messages.stream().allMatch(m -> m.equals(
            "VRChat process diagnostic: state=unverified; reason=access-denied; process-kind=unavailable")));
        processes.failure = null;
        processes.entries = List.of(process("VRChat.exe", List.of(PRIVATE)));
        now[0] = TimeUnit.MINUTES.toNanos(2);
        assertEquals(List.of("987654321"), tracker.confirmedClientPids());
        assertEquals(3, messages.size());
        assertTrue(messages.get(2).contains("state=present"));
        assertFalse(messages.toString().contains(PRIVATE));
        assertEquals(121, processes.snapshots);
        assertEquals(0, processes.actions);
    }

    @Test void aBrokenDiagnosticSinkCannotChangePresenceOrLeaveLaunchLocked()
    {
        Processes processes = new Processes();
        processes.entries = List.of(process("VRChat.exe", List.of()));
        for (Throwable failure : List.of(new IllegalStateException(PRIVATE), new LinkageError(PRIVATE)))
        {
            ManagedVrcClient.Tracker tracker = new ManagedVrcClient.Tracker(processes,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> 0L, millis -> fail("Unexpected wait"), message -> {
                    if (failure instanceof RuntimeException) throw (RuntimeException)failure;
                    throw (LinkageError)failure;
                });
            assertEquals(State.PRESENT, tracker.clientPresence().state);
            for (int attempt = 0; attempt < 2; attempt++)
            {
                IOException rejected = assertThrows(IOException.class, () -> tracker.launch("wrld_synthetic:1", false,
                    () -> { fail("Existing-client guard must still prevent dispatch"); return null; }));
                assertTrue(rejected.getMessage().contains("VRChat is already running"), rejected.getMessage());
            }
        }
        assertEquals(0, processes.actions);
    }

    @Test void rejectedScarletLaunchHasMarkersButNoDispatchOrPrivateDestination()
    {
        Processes processes = new Processes();
        processes.entries = List.of(process("VRChat.exe", List.of()));
        java.util.ArrayList<String> messages = new java.util.ArrayList<>();
        ManagedVrcClient.Tracker tracker = new ManagedVrcClient.Tracker(processes,
            Clock.fixed(NOW, ZoneOffset.UTC), () -> 0L, millis -> fail("Unexpected wait"), messages::add);
        assertThrows(IOException.class, () -> tracker.launch("wrld_" + PRIVATE + ":1~group(grp_" + PRIVATE + ")", false,
            () -> { fail("Must not dispatch"); return null; }));
        assertEquals(List.of("VRChat launch diagnostic: stage=request-received", "VRChat launch diagnostic: stage=not-confirmed"), messages);
        assertFalse(messages.toString().contains(PRIVATE));
    }
}

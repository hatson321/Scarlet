package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;

/** Synthetic procfs records only; no live process signals or game-file access. */
class LinuxProcessInventoryTest
{
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final long PID = 77L;
    private static final String KERNEL = "2097152";

    private static ByteArrayInputStream bytes(String value)
    {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String stat(long pid, String name, String flags)
    {
        List<String> fields = new ArrayList<>(Collections.nCopies(20, "0"));
        fields.set(0, "S");
        fields.set(1, "2");
        fields.set(5, "-1");
        fields.set(6, flags);
        fields.set(19, "100");
        return pid + " (" + name + ") " + String.join(" ", fields) + "\n";
    }

    private static ProcessInfo hidden()
    {
        return new ProcessInfo(PID, START, "synthetic-user", null, List.of(), 2);
    }

    private static ManagedVrcClient.ProcInput forbidden()
    {
        return () -> { fail("This metadata stream must not be opened"); return null; };
    }

    private static ProcessInfo readKernel(ProcessInfo before, Supplier<ProcessInfo> reread)
    {
        return ManagedVrcClient.readLinuxMetadata(before,
            () -> bytes(stat(PID, "kworker/u48:0-events_unbound", KERNEL)), forbidden(), forbidden(), reread, () -> true);
    }

    private static void failure(VrcClientPresence.Reason reason, Runnable action)
    {
        VrcClientPresence.Failure failure = assertThrows(VrcClientPresence.Failure.class, action::run);
        assertEquals(reason, failure.reason);
        assertNull(failure.getCause());
        assertFalse(VrcClientPresence.unverified(failure).diagnostic().contains("synthetic-user"));
    }

    @Test void extendedKernelNamesAreAcceptedWithoutRelaxingApplicationNames() throws Exception
    {
        for (String name : List.of("kworker/u48:0-events_unbound", "kworker/0:1", "x".repeat(63),
            "kernel (worker) with spaces", "kernel\\worker", "VRChat.exe"))
            assertTrue(ManagedVrcClient.readProcKernelThread(bytes(stat(PID, name, KERNEL)), PID));
        assertThrows(IOException.class, () -> ManagedVrcClient.readProcCommandName(bytes("kworker/0:1\n")));
        assertThrows(IOException.class, () -> ManagedVrcClient.readProcCommandName(bytes("x".repeat(16) + "\n")));
    }

    @Test void normalAndUnrelatedFlagsDoNotClaimKernelStatus() throws Exception
    {
        for (String flags : List.of("0", "4194304", "1077936128"))
            assertFalse(ManagedVrcClient.readProcKernelThread(bytes(stat(PID, "kworker/0:1", flags)), PID));
        assertTrue(ManagedVrcClient.readProcKernelThread(bytes(stat(PID, "worker", "2097153")), PID));
    }

    @Test void parenthesesAndFlagTextInsideANameCannotForgeKernelStatus() throws Exception
    {
        String deceptive = "worker) S 2 0 0 0 -1 2097152 (nested)";
        assertFalse(ManagedVrcClient.readProcKernelThread(bytes(stat(PID, deceptive, "0")), PID));
        assertThrows(IOException.class,
            () -> ManagedVrcClient.readProcKernelThread(bytes(stat(PID, "worker\nKthread: 1", "0")), PID));
    }

    @Test void wrongPidAndInvalidExpectedPidAreRejected()
    {
        assertThrows(IOException.class, () -> ManagedVrcClient.readProcKernelThread(bytes(stat(PID + 1, "worker", KERNEL)), PID));
        for (long expected : new long[] {0, -1})
            assertThrows(IOException.class, () -> ManagedVrcClient.readProcKernelThread(bytes(stat(expected, "worker", KERNEL)), expected));
    }

    @Test void incompleteOrMalformedStatusCannotExcludeAProcess()
    {
        String valid = stat(PID, "worker", KERNEL);
        for (String invalid : List.of("", valid.stripTrailing(), "77 (worker) S 2 0 0 0 -1 2097152\n",
            valid.replace(") S ", ") ? "), valid.replace(") S ", ") SS "),
            valid.replace(") S ", ") S  "), valid.replace("worker", "bad\0worker"),
            valid + valid, valid + "extra", valid.replace(" 100\n", " invalid\n"),
            stat(PID, "x".repeat(64), KERNEL)))
            assertThrows(IOException.class, () -> ManagedVrcClient.readProcKernelThread(bytes(invalid), PID));
    }

    @Test void invalidOrOverflowingFlagsAreRejected()
    {
        for (String flags : List.of("-2097152", "+2097152", "0x200000", "2097152x", "18446744073709551616"))
            assertThrows(IOException.class, () -> ManagedVrcClient.readProcKernelThread(bytes(stat(PID, "worker", flags)), PID));
    }

    @Test void statusReadHasAFiniteBound()
    {
        ByteArrayInputStream huge = bytes("x".repeat(5000));
        assertThrows(IOException.class, () -> ManagedVrcClient.readProcKernelThread(huge, PID));
        assertEquals(903, huge.available(), "At most 4097 bytes may be consumed");
    }

    @Test void stableKernelIdentityIsExcludedBeforeReadingItsName()
    {
        ProcessInfo before = hidden();
        assertNull(readKernel(before, () -> before));
    }

    @Test void exitedKernelThreadIsIgnored()
    {
        assertNull(readKernel(hidden(), () -> null));
    }

    @Test void reusedPidOrChangedParentCannotBeExcluded()
    {
        for (ProcessInfo after : List.of(
            new ProcessInfo(PID, START.plusSeconds(1), "synthetic-user", null, List.of(), 2),
            new ProcessInfo(PID, START, "different-user", null, List.of(), 2),
            new ProcessInfo(PID, START, "synthetic-user", null, List.of(), 3),
            new ProcessInfo(PID, START, "synthetic-user", "/bin/VRChat.exe", List.of(), 2)))
            failure(VrcClientPresence.Reason.LINUX_IDENTITY_CHANGED, () -> readKernel(hidden(), () -> after));
    }

    @Test void incompleteOrContradictoryIdentityCannotBeExcluded()
    {
        for (ProcessInfo before : List.of(new ProcessInfo(PID, null, "synthetic-user", null, List.of(), 2),
            new ProcessInfo(PID, START, null, null, List.of(), 2),
            new ProcessInfo(PID, START, " ", null, List.of(), 2),
            new ProcessInfo(PID, START, "synthetic-user", null, List.of("VRChat.exe"), 2)))
            failure(VrcClientPresence.Reason.EXECUTABLE_UNAVAILABLE, () -> readKernel(before, () -> before));
    }

    @Test void deniedOrMalformedStatStaysUnverifiedWithPrivateDiagnostic()
    {
        ManagedVrcClient.ProcInput denied = () -> { throw new IOException("/private-synthetic-path"); };
        for (ManagedVrcClient.ProcInput input : List.of(denied, () -> bytes("broken\n")))
            failure(VrcClientPresence.Reason.PROC_STAT_UNAVAILABLE, () -> ManagedVrcClient.readLinuxMetadata(
                hidden(), input, forbidden(), forbidden(), () -> { fail("No reread"); return null; }, () -> true));
        assertNull(ManagedVrcClient.readLinuxMetadata(hidden(), denied, forbidden(), forbidden(), () -> hidden(), () -> false));
    }

    @Test void identityReadFailureStaysUnverified()
    {
        failure(VrcClientPresence.Reason.JAVA_METADATA_UNAVAILABLE,
            () -> readKernel(hidden(), () -> { throw new SecurityException("private-synthetic-value"); }));
    }

    @Test void kernelLookingApplicationNameIsNotAnExclusionRule()
    {
        ProcessInfo before = hidden();
        failure(VrcClientPresence.Reason.PROC_NAME_UNAVAILABLE, () -> ManagedVrcClient.readLinuxMetadata(before,
            () -> bytes(stat(PID, "kworker/0:1", "0")), () -> bytes("kworker/0:1\n"), forbidden(), () -> before, () -> true));
    }

    @Test void hiddenClientRemainsPresentWithoutCloseAuthority()
    {
        ProcessInfo before = hidden();
        ProcessInfo client = ManagedVrcClient.readLinuxMetadata(before, () -> bytes(stat(PID, "VRChat.exe", "0")),
            () -> bytes("VRChat.exe\n"), forbidden(), () -> before, () -> true);
        assertTrue(ManagedVrcClient.isClient(client));
        assertTrue(client.inventoryOnly());
        assertFalse(ManagedVrcClient.matches(new ManagedVrcClient.Client(1, "synthetic", START, PID, START, "synthetic-user"), client));
    }

    @Test void wineIdentityDoesNotReadKernelFlagsOrGainCloseAuthority()
    {
        ProcessInfo before = new ProcessInfo(PID, START, "synthetic-user", "/bin/wine64", List.of(), 2);
        ProcessInfo client = ManagedVrcClient.readLinuxMetadata(before, forbidden(), () -> bytes("VRChat.exe\n"),
            () -> bytes("Z:\\synthetic\\VRChat.exe\0"), () -> before, () -> true);
        assertTrue(ManagedVrcClient.isClient(client));
        assertTrue(client.inventoryOnly());
    }

    @Test void kernelWorkersDoNotPreventAbsentOrPresentInventoryResults()
    {
        List<ProcessInfo> entries = new ArrayList<>();
        ManagedVrcClient.Processes processes = new ManagedVrcClient.Processes() {
            public String currentUser() { return "synthetic-user"; }
            public List<ProcessInfo> snapshot() {
                ProcessInfo kernel = readKernel(hidden(), LinuxProcessInventoryTest::hidden);
                assertNull(kernel);
                return List.copyOf(entries);
            }
            public ProcessInfo find(long pid) { return null; }
            public boolean requestClose(ProcessInfo p, BooleanSupplier allowed) { fail("No process signals"); return false; }
        };
        ManagedVrcClient.Tracker tracker = new ManagedVrcClient.Tracker(processes, Clock.fixed(START, ZoneOffset.UTC));
        assertEquals(VrcClientPresence.State.ABSENT, tracker.clientPresence().state);
        entries.add(new ProcessInfo(78, START, "synthetic-user", "/synthetic/VRChat.exe", List.of(), 2));
        assertEquals(VrcClientPresence.State.PRESENT, tracker.clientPresence().state);
        assertEquals(0, tracker.beginLaunch("synthetic"));
        assertNull(tracker.current());
        assertEquals(ManagedVrcClient.CloseResult.NOT_MANAGED, tracker.close(null, () -> true));
    }

    @Test void protonHelpersAreInventoryOnlyAndNeverClients()
    {
        for (String program : List.of("steam.exe", "tabtip.exe", "xalia.exe", "UnityCrashHandler64.exe"))
        {
            ProcessInfo before = new ProcessInfo(PID, START, "synthetic-user", "/bin/wine64-preloader", List.of(), 2);
            String comm = program.substring(0, Math.min(15, program.length()));
            ProcessInfo result = ManagedVrcClient.readLinuxMetadata(before, forbidden(), () -> bytes(comm + "\n"),
                () -> bytes("Z:\\synthetic\\" + program + "\0"), () -> before, () -> true);
            assertTrue(result.inventoryOnly());
            assertFalse(ManagedVrcClient.isClient(result));
            assertFalse(ManagedVrcClient.uncertainWineIdentity(result));
            assertFalse(ManagedVrcClient.matches(new ManagedVrcClient.Client(1, "synthetic", START, PID, START, "synthetic-user"), result));
        }
    }

    @Test void truncatedHelperNeedsTheExactKnownFullProgram()
    {
        ProcessInfo before = new ProcessInfo(PID, START, "synthetic-user", "/bin/wine64-preloader", List.of(), 2);
        for (String program : List.of("UnityCrashHandlerEvil.exe", "VRChat.exe"))
            failure(VrcClientPresence.Reason.WINE_NAME_MISMATCH, () -> ManagedVrcClient.readLinuxMetadata(before,
                forbidden(), () -> bytes("UnityCrashHandl\n"), () -> bytes(program + "\0"), () -> before, () -> true));
        failure(VrcClientPresence.Reason.WINE_ARGV0_NOT_PROGRAM, () -> ManagedVrcClient.readLinuxMetadata(before,
            forbidden(), () -> bytes("UnityCrashHandl\n"), () -> bytes("UnityCrashHandler64.exe.backup\0"), () -> before, () -> true));
    }

    @Test void helperNameMustMatchTheFullOrPreciselyTruncatedCommandName()
    {
        ProcessInfo before = new ProcessInfo(PID, START, "synthetic-user", "/bin/wine64-preloader", List.of(), 2);
        for (String comm : List.of("UnityCrash", "UnityCrashHand", "VRChat.exe", "notepad.exe"))
            failure(VrcClientPresence.Reason.WINE_NAME_MISMATCH, () -> ManagedVrcClient.readLinuxMetadata(before,
                forbidden(), () -> bytes(comm + "\n"), () -> bytes("UnityCrashHandler64.exe\0"), () -> before, () -> true));
    }

    @Test void recognizedHelperStillRequiresCompleteStableIdentity()
    {
        ProcessInfo incomplete = new ProcessInfo(PID, null, "synthetic-user", "/bin/wine64-preloader", List.of(), 2);
        failure(VrcClientPresence.Reason.WINE_IDENTITY_INCOMPLETE, () -> ManagedVrcClient.readLinuxMetadata(incomplete,
            forbidden(), () -> bytes("steam.exe\n"), () -> bytes("steam.exe\0"), () -> incomplete, () -> true));
        ProcessInfo before = new ProcessInfo(PID, START, "synthetic-user", "/bin/wine64-preloader", List.of(), 2);
        ProcessInfo changed = new ProcessInfo(PID, START.plusSeconds(1), "synthetic-user", before.executable(), List.of(), 2);
        failure(VrcClientPresence.Reason.LINUX_IDENTITY_CHANGED, () -> ManagedVrcClient.readLinuxMetadata(before,
            forbidden(), () -> bytes("steam.exe\n"), () -> bytes("steam.exe\0"), () -> changed, () -> true));
    }

    @Test void steamWrapperLaunchTargetDoesNotCreateASecondClient()
    {
        ProcessInfo before = new ProcessInfo(PID, START, "synthetic-user", "/bin/wine-preloader",
            List.of("Z:\\synthetic\\VRChat.exe", "--no-vr"), 2);
        ProcessInfo wrapper = ManagedVrcClient.readLinuxMetadata(before, forbidden(), () -> bytes("steam.exe\n"),
            () -> bytes("C:\\windows\\system32\\steam.exe\0"), () -> before, () -> true);
        assertFalse(ManagedVrcClient.isClient(wrapper));
        assertFalse(ManagedVrcClient.uncertainWineIdentity(wrapper));
        assertTrue(wrapper.inventoryOnly());
        assertEquals(before.arguments(), wrapper.arguments());
        assertFalse(ManagedVrcClient.matches(new ManagedVrcClient.Client(1, "synthetic", START, PID, START, "synthetic-user"), wrapper));
        ProcessInfo clientRaw = new ProcessInfo(78, START, "synthetic-user", "/bin/wine64-preloader", List.of("--no-vr"), PID);
        ProcessInfo client = ManagedVrcClient.readLinuxMetadata(clientRaw, forbidden(), () -> bytes("VRChat.exe\n"),
            () -> bytes("Z:\\synthetic\\VRChat.exe\0"), () -> clientRaw, () -> true);
        ManagedVrcClient.Processes processes = new ManagedVrcClient.Processes() {
            public String currentUser() { return "synthetic-user"; }
            public List<ProcessInfo> snapshot() { return List.of(wrapper, client); }
            public ProcessInfo find(long pid) { return null; }
            public boolean requestClose(ProcessInfo p, BooleanSupplier allowed) { fail("No process signals"); return false; }
        };
        ManagedVrcClient.Tracker tracker = new ManagedVrcClient.Tracker(processes, Clock.fixed(START, ZoneOffset.UTC));
        assertEquals(List.of("78"), tracker.clientPresence().clientPids());
        assertEquals(0, tracker.beginLaunch("synthetic"));
        assertEquals(SingleVrcClient.Status.IDENTITY_UNVERIFIED, tracker.shutdownClient("synthetic", START).status);
    }

    @Test void uncorroboratedSteamNameCannotHideARawClient()
    {
        ProcessInfo before = new ProcessInfo(PID, START, "synthetic-user", "/bin/wine-preloader", List.of("VRChat.exe"), 2);
        for (String[] names : List.of(new String[] {"steam.exe", "VRChat.exe"},
            new String[] {"VRChat.exe", "steam.exe"}, new String[] {"steam.exe", "--steam.exe"},
            new String[] {"steam.exe", "steam.exe.backup"}, new String[] {"services.exe", "services.exe"}))
            assertSame(before, ManagedVrcClient.readLinuxMetadata(before, forbidden(), () -> bytes(names[0] + "\n"),
                () -> bytes(names[1] + "\0"), () -> before, () -> true));
    }

    @Test void steamWrapperClassificationRequiresStableIdentity()
    {
        ProcessInfo before = new ProcessInfo(PID, START, "synthetic-user", "/bin/wine-preloader", List.of("VRChat.exe"), 2);
        ProcessInfo after = new ProcessInfo(PID, START.plusSeconds(1), "synthetic-user", before.executable(), before.arguments(), 2);
        failure(VrcClientPresence.Reason.LINUX_IDENTITY_CHANGED, () -> ManagedVrcClient.readLinuxMetadata(before,
            forbidden(), () -> bytes("steam.exe\n"), () -> bytes("steam.exe\0"), () -> after, () -> true));
        failure(VrcClientPresence.Reason.PROC_ARGV0_UNAVAILABLE, () -> ManagedVrcClient.readLinuxMetadata(before,
            forbidden(), () -> bytes("steam.exe\n"), () -> { throw new IOException("private-synthetic"); }, () -> before, () -> true));
    }
}

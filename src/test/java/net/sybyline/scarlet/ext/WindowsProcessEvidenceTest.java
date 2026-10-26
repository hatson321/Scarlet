package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;
import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessLease;

/** Synthetic process objects and native transports only: no native process or game access. */
class WindowsProcessEvidenceTest
{
    static final Instant START = Instant.parse("2026-10-24T12:00:00.123456700Z");
    static final ProcessInfo PARENT = new ProcessInfo(100, START, "synthetic-user", "/synthetic/launch.exe", List.of());

    static final class FakeAccess implements WindowsProcessEvidence.Access
    {
        final HANDLE handle = new HANDLE(Pointer.createConstant(37));
        ProcessInfo identity = PARENT;
        WindowsProcessEvidence.Lifetime lifetime = new WindowsProcessEvidence.Lifetime(START, null);
        int opens, closes;
        boolean denied, throwIdentity, throwLifetime;
        public HANDLE open(long pid) { this.opens++; return this.denied ? null : this.handle; }
        public ProcessInfo identity(HANDLE handle, long pid, long parent)
        {
            if (this.throwIdentity) throw new SecurityException("synthetic metadata failure");
            return this.identity;
        }
        public WindowsProcessEvidence.Lifetime lifetime(HANDLE handle)
        {
            if (this.throwLifetime) throw new SecurityException("synthetic lifetime failure");
            return this.lifetime;
        }
        public void close(HANDLE handle) { assertSame(this.handle, handle); this.closes++; }
    }

    static final class FakeProcess extends Process
    {
        boolean alive = true;
        public OutputStream getOutputStream() { throw new AssertionError("No process IO"); }
        public InputStream getInputStream() { throw new AssertionError("No process IO"); }
        public InputStream getErrorStream() { throw new AssertionError("No process IO"); }
        public int waitFor() { throw new AssertionError("No waits"); }
        public int exitValue() { return this.alive ? 259 : 0; }
        public void destroy() { throw new AssertionError("Never signal a launcher"); }
        public boolean isAlive() { return this.alive; }
        public long pid() { return 100; }
    }

    static ProcessInfo child(Instant time)
    {
        return new ProcessInfo(200, time, "synthetic-user", "/synthetic/VRChat.exe", List.of(), 100);
    }

    static WindowsProcessEvidence lease(FakeAccess access)
    {
        return new WindowsProcessEvidence(access, access.handle, PARENT);
    }

    @Test void captureTransfersExactlyOneReadOnlyHandleOnlyForActualStillAliveProcess()
    {
        FakeAccess access = new FakeAccess();
        ProcessInfo receipt = WindowsProcessEvidence.captureStarted(new FakeProcess(), access);
        assertNotNull(receipt);
        assertEquals(PARENT.startedAt(), receipt.startedAt());
        assertEquals(0, access.closes);
        ManagedVrcClient.Tracker tracker = new ManagedVrcClient.Tracker(new ManagedVrcClientTest.FakeProcesses(), new ManagedVrcClientTest.MutableClock());
        tracker.launchCompleted(123, receipt); // stale receipt must release its handle
        assertEquals(1, access.closes);
    }

    @Test void exitedOriginalCannotBeReplacedByAReusedPidReceipt()
    {
        FakeAccess access = new FakeAccess();
        FakeProcess original = new FakeProcess();
        original.alive = false;
        assertNull(WindowsProcessEvidence.captureStarted(original, access));
        assertEquals(1, access.closes);
    }

    @Test void deniedCaptureDoesNotCloseAnUnopenedHandle()
    {
        FakeAccess access = new FakeAccess();
        access.denied = true;
        assertNull(WindowsProcessEvidence.captureStarted(new FakeProcess(), access));
        assertEquals(0, access.closes);
    }

    @Test void incompleteCaptureAndExceptionalMetadataReleaseTheHandle()
    {
        FakeAccess incomplete = new FakeAccess();
        incomplete.identity = new ProcessInfo(100, START, null, "/synthetic/launch.exe", List.of());
        assertNull(WindowsProcessEvidence.captureStarted(new FakeProcess(), incomplete));
        assertEquals(1, incomplete.closes);
        FakeAccess failure = new FakeAccess();
        failure.throwIdentity = true;
        assertThrows(SecurityException.class, () -> WindowsProcessEvidence.captureStarted(new FakeProcess(), failure));
        assertEquals(1, failure.closes);
    }

    @Test void transientReadAlwaysReleasesHandleIncludingExceptionalReads()
    {
        FakeAccess access = new FakeAccess();
        assertSame(PARENT, WindowsProcessEvidence.read(100, -1, access));
        assertEquals(1, access.closes);
        access.throwIdentity = true;
        assertThrows(SecurityException.class, () -> WindowsProcessEvidence.read(100, -1, access));
        assertEquals(2, access.closes);
    }

    @Test void childRetentionRequiresExactIdentityIncludingSubMillisecondCreation()
    {
        FakeAccess access = new FakeAccess();
        access.identity = new ProcessInfo(100, START.plusNanos(100), "synthetic-user", "/synthetic/launch.exe", List.of());
        assertNull(WindowsProcessEvidence.retain(PARENT, access));
        assertEquals(1, access.closes);
        access.identity = PARENT;
        ProcessLease retained = WindowsProcessEvidence.retain(PARENT, access);
        assertNotNull(retained);
        retained.close();
        retained.close();
        assertEquals(2, access.closes, "Native handles are released once, not twice");
    }

    @Test void retainedExitedParentProvesOnlyChildrenWithinItsExactLifetime()
    {
        FakeAccess access = new FakeAccess();
        access.lifetime = new WindowsProcessEvidence.Lifetime(START, START.plusSeconds(2));
        try (WindowsProcessEvidence lease = lease(access))
        {
            assertTrue(lease.provesParent(PARENT, child(START.plusSeconds(1)), START.plusSeconds(3)));
            assertTrue(lease.provesParent(PARENT, child(START.plusSeconds(2)), START.plusSeconds(3)));
            assertFalse(lease.provesParent(PARENT, child(START.plusSeconds(2).plusNanos(100)), START.plusSeconds(3)));
            assertFalse(lease.provesParent(PARENT, child(START.minusNanos(100)), START.plusSeconds(3)));
        }
        assertEquals(1, access.closes);
    }

    @Test void lifetimeIdentityMismatchMissingOrInvalidTimesRefuseParentage()
    {
        FakeAccess access = new FakeAccess();
        try (WindowsProcessEvidence lease = lease(access))
        {
            for (WindowsProcessEvidence.Lifetime lifetime : new WindowsProcessEvidence.Lifetime[]{null,
                new WindowsProcessEvidence.Lifetime(START.plusNanos(100), START.plusSeconds(2)),
                new WindowsProcessEvidence.Lifetime(START, START.minusNanos(100)),
                new WindowsProcessEvidence.Lifetime(START, START.plusSeconds(10))})
            {
                access.lifetime = lifetime;
                assertFalse(lease.provesParent(PARENT, child(START.plusSeconds(1)), START.plusSeconds(3)));
            }
        }
    }

    @Test void currentLiveParentMayProveAnExistingChildButNotAFutureOne()
    {
        FakeAccess access = new FakeAccess();
        try (WindowsProcessEvidence lease = lease(access))
        {
            assertTrue(lease.provesParent(PARENT, child(START.plusSeconds(1)), START.plusSeconds(2)));
            assertFalse(lease.provesParent(PARENT, child(START.plusSeconds(3)), START.plusSeconds(2)));
        }
    }

    @Test void retainedReferenceCannotAuthorizeWrongParentOrDifferentUser()
    {
        FakeAccess access = new FakeAccess();
        try (WindowsProcessEvidence lease = lease(access))
        {
            assertFalse(lease.provesParent(PARENT, new ProcessInfo(200, START, "synthetic-user", "VRChat.exe", List.of(), 101), START.plusSeconds(3)));
            assertFalse(lease.provesParent(PARENT, new ProcessInfo(200, START, "different-user", "VRChat.exe", List.of(), 100), START.plusSeconds(3)));
            ProcessInfo changedParent = new ProcessInfo(100, START, "synthetic-user", "different.exe", List.of());
            assertFalse(lease.provesParent(changedParent, child(START), START.plusSeconds(3)));
        }
    }

    @Test void aClosedHandleCannotProveParentageOrBeClosedTwice()
    {
        FakeAccess access = new FakeAccess();
        WindowsProcessEvidence lease = lease(access);
        lease.close();
        lease.close();
        assertFalse(lease.provesParent(PARENT, child(START), START.plusSeconds(3)));
        assertEquals(1, access.closes);
    }

    @Test void nativeFileTimeRoundTripsAll100NanosecondIdentityBits()
    {
        long ticks = (START.getEpochSecond() + WindowsProcessEvidence.WINDOWS_EPOCH_SECONDS) * 10_000_000L + START.getNano() / 100L;
        assertEquals(START, WindowsProcessEvidence.fileTime((int)(ticks >>> 32), (int)ticks));
        assertNotEquals(START, WindowsProcessEvidence.fileTime((int)((ticks + 1) >>> 32), (int)(ticks + 1)));
        assertNull(WindowsProcessEvidence.fileTime(0, 0));
        assertNull(WindowsProcessEvidence.fileTime(-1, -1));
    }

    @Test void toolhelpParentCannotBeBorrowedByAProcessBornAfterItsSnapshot()
    {
        assertDoesNotThrow(() -> ManagedVrcClient.validateSnapshotCreation(PARENT, START));
        assertDoesNotThrow(() -> ManagedVrcClient.validateSnapshotCreation(PARENT, START.plusNanos(100)));
        assertThrows(IllegalStateException.class, () -> ManagedVrcClient.validateSnapshotCreation(PARENT, START.minusNanos(100)),
            "Reject the whole inventory, not a fallback carrying the old process name and parent");
    }

    @Test void liveLifetimeNeverInterpretsTheUndefinedExitTimestamp()
    {
        WindowsProcessEvidence.Lifetime result = WindowsProcessEvidence.stableLifetime(() -> WinError.WAIT_TIMEOUT,
            () -> new WindowsProcessEvidence.Lifetime(START, START.minusSeconds(100)));
        assertNotNull(result);
        assertNull(result.exited);
        assertEquals(START, result.created);
    }

    @Test void exitDuringNativeReadRequiresAStableResample()
    {
        int[] states = {WinError.WAIT_TIMEOUT, WinBase.WAIT_OBJECT_0, WinBase.WAIT_OBJECT_0, WinBase.WAIT_OBJECT_0};
        int[] index = {0}, reads = {0};
        WindowsProcessEvidence.Lifetime result = WindowsProcessEvidence.stableLifetime(() -> states[index[0]++], () -> {
            reads[0]++;
            return new WindowsProcessEvidence.Lifetime(START, reads[0] == 1 ? null : START.plusSeconds(1));
        });
        assertNotNull(result);
        assertEquals(START.plusSeconds(1), result.exited);
        assertEquals(2, reads[0]);
    }

    @Test void nativeWaitFailureUnstableStateOrMissingExitTimeRemainUnknown()
    {
        assertNull(WindowsProcessEvidence.stableLifetime(() -> WinBase.WAIT_FAILED,
            () -> { throw new AssertionError("No timing read after failed wait"); }));
        assertNull(WindowsProcessEvidence.stableLifetime(() -> WinBase.WAIT_OBJECT_0,
            () -> new WindowsProcessEvidence.Lifetime(START, null)));
        int[] index = {0};
        assertNull(WindowsProcessEvidence.stableLifetime(() -> index[0]++ % 2 == 0 ? WinError.WAIT_TIMEOUT : WinBase.WAIT_OBJECT_0,
            () -> new WindowsProcessEvidence.Lifetime(START, START.plusSeconds(1))));
        assertEquals(4, index[0], "Changing native observations never cause unbounded retry");
    }

    @Test @EnabledOnOs(OS.WINDOWS) void nativeAbiSmokeReadsAndRetainsOnlyThisTestJvm()
    {
        // No process enumeration and no game files: only this test JVM's own PID.
        long pid = ProcessHandle.current().pid();
        ProcessInfo first = WindowsProcessEvidence.read(pid, -1L);
        ProcessInfo second = WindowsProcessEvidence.read(pid, -1L);
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(ManagedVrcClient.sameProcess(first, second));
        assertEquals(0, first.startedAt().getNano() % 100);
        ProcessLease retained = WindowsProcessEvidence.retain(first);
        assertNotNull(retained);
        try
        {
            ProcessInfo syntheticChild = new ProcessInfo(pid + 1, first.startedAt(), first.user(), "/synthetic/not-a-game.exe", List.of(), pid);
            assertTrue(retained.provesParent(first, syntheticChild, Instant.now()));
        }
        finally { retained.close(); }
        retained.close();
        assertFalse(retained.provesParent(first, child(START), Instant.now()));
    }
}

package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;
import net.sybyline.scarlet.ext.ManagedVrcClient.ForceResult;

/** Fake OS handles and termination API only; never opens or terminates real processes. */
class WindowsClientTerminationTest
{
    static final ProcessInfo CLIENT = new ProcessInfo(41, Instant.EPOCH, "synthetic-user", "C:/synthetic/VRChat.exe", Collections.emptyList());
    static final class Fake implements WindowsClientTermination.Access
    {
        HANDLE handle = new HANDLE(Pointer.createConstant(81));
        ProcessInfo identity = CLIENT;
        int terminations, closes, reads;
        boolean denied, accepted = true, throwTerminate;
        Runnable onRead = () -> {};
        public HANDLE open(long pid) { assertEquals(41, pid); return denied ? null : handle; }
        public ProcessInfo identity(HANDLE handle, long pid) { assertSame(this.handle, handle); reads++; onRead.run(); return identity; }
        public boolean terminate(HANDLE handle) { assertSame(this.handle, handle); terminations++; if (throwTerminate) throw new IllegalStateException(); return accepted; }
        public void close(HANDLE handle) { assertSame(this.handle, handle); closes++; }
        ForceResult run(BooleanSupplier guard) { return WindowsClientTermination.request(CLIENT, guard, this); }
    }
    @Test void incompleteIdentityNeverOpensAProcess()
    {
        for (int kind = 0; kind < 4; kind++)
        {
            Fake f = new Fake();
            ProcessInfo bad = new ProcessInfo(kind == 0 ? -1 : kind == 1 ? 0x1_0000_0000L : 41,
                kind == 2 ? null : Instant.EPOCH, kind == 3 ? " " : "synthetic-user", "C:/synthetic/VRChat.exe", Collections.emptyList());
            assertEquals(ForceResult.IDENTITY_CHANGED, WindowsClientTermination.request(bad, () -> true, f));
            assertEquals(0, f.reads); assertEquals(0, f.terminations); assertEquals(0, f.closes);
        }
    }
    @Test void exactPinnedHandleTerminatesOnceAndIsReleased()
    {
        Fake f = new Fake(); assertEquals(ForceResult.REQUESTED, f.run(() -> true));
        assertEquals(1, f.terminations); assertEquals(1, f.closes); assertEquals(2, f.reads);
    }
    @Test void deniedOpenNeverDispatchesOrClosesUnopenedHandle()
    {
        Fake f = new Fake(); f.denied = true; assertEquals(ForceResult.DENIED, f.run(() -> true));
        assertEquals(0, f.terminations); assertEquals(0, f.closes);
    }
    @Test void pidReuseOrChangedIdentityIsRejectedBeforeDispatch()
    {
        for (int kind = 0; kind < 4; kind++)
        {
            Fake f = new Fake();
            f.identity = new ProcessInfo(kind == 0 ? 42 : 41, kind == 1 ? Instant.EPOCH.plusNanos(100) : Instant.EPOCH,
                kind == 2 ? "other-user" : "synthetic-user", kind == 3 ? "C:/synthetic/other.exe" : "C:/synthetic/VRChat.exe", Collections.emptyList());
            assertEquals(ForceResult.IDENTITY_CHANGED, f.run(() -> true)); assertEquals(0, f.terminations); assertEquals(1, f.closes);
        }
    }
    @Test void changedGuardAfterMetadataReadCannotTerminate()
    {
        Fake f = new Fake(); int[] guards = {0}; assertEquals(ForceResult.GUARD_VETO, f.run(() -> ++guards[0] < 2));
        assertEquals(0, f.terminations); assertEquals(1, f.closes);
    }
    @Test void exceptionsAndRejectedDispatchRemainTerminal()
    {
        Fake f = new Fake(); f.throwTerminate = true; assertEquals(ForceResult.UNCERTAIN, f.run(() -> true)); assertEquals(1, f.closes);
        Fake g = new Fake(); g.accepted = false; assertEquals(ForceResult.DENIED, g.run(() -> true)); assertEquals(1, g.closes);
    }
    @Test void identityOrGuardExceptionsDoNotDispatchAndAlwaysRelease()
    {
        Fake f = new Fake(); f.onRead = () -> { throw new IllegalStateException(); };
        assertEquals(ForceResult.UNCERTAIN, f.run(() -> true)); assertEquals(0, f.terminations); assertEquals(1, f.closes);
        Fake g = new Fake(); assertEquals(ForceResult.UNCERTAIN, g.run(() -> { throw new IllegalStateException(); }));
        assertEquals(0, g.terminations); assertEquals(1, g.closes);
    }
}

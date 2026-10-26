package net.sybyline.scarlet.ext;

import java.util.function.BooleanSupplier;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;
import net.sybyline.scarlet.ext.ManagedVrcClient.ForceResult;

/** Explicit opt-in only. Termination targets a pinned process object, never an image-name kill. */
final class WindowsClientTermination
{
    interface Access
    {
        HANDLE open(long pid);
        ProcessInfo identity(HANDLE handle, long pid);
        boolean terminate(HANDLE handle);
        void close(HANDLE handle);
    }
    static ForceResult request(ProcessInfo expected, BooleanSupplier guard)
    {
        return request(expected, guard, new Access() {
            public HANDLE open(long pid) {
                if (pid <= 0 || pid > 0xFFFF_FFFFL) return null;
                return Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION | WinNT.SYNCHRONIZE
                    | WinNT.PROCESS_TERMINATE, false, (int)pid);
            }
            public ProcessInfo identity(HANDLE handle, long pid) { return WindowsProcessEvidence.identity(handle, pid); }
            public boolean terminate(HANDLE handle) { return Kernel32.INSTANCE.TerminateProcess(handle, 1); }
            public void close(HANDLE handle) { Kernel32.INSTANCE.CloseHandle(handle); }
        });
    }
    static ForceResult request(ProcessInfo expected, BooleanSupplier guard, Access access)
    {
        if (expected == null || expected.pid() <= 0 || expected.pid() > 0xFFFF_FFFFL || expected.startedAt() == null
            || expected.user() == null || expected.user().isBlank() || !ManagedVrcClient.isClient(expected))
            return ForceResult.IDENTITY_CHANGED;
        HANDLE handle = null;
        try
        {
            handle = access.open(expected.pid());
            if (handle == null) return ForceResult.DENIED;
            if (!ManagedVrcClient.sameProcess(expected, access.identity(handle, expected.pid()))) return ForceResult.IDENTITY_CHANGED;
            if (!guard.getAsBoolean()) return ForceResult.GUARD_VETO;
            // The handle pins the original process through dispatch, preventing PID reuse.
            if (!ManagedVrcClient.sameProcess(expected, access.identity(handle, expected.pid()))) return ForceResult.IDENTITY_CHANGED;
            if (!guard.getAsBoolean()) return ForceResult.GUARD_VETO;
            return access.terminate(handle) ? ForceResult.REQUESTED : ForceResult.DENIED;
        }
        catch (RuntimeException | LinkageError ex) { return ForceResult.UNCERTAIN; }
        finally { if (handle != null) try { access.close(handle); } catch (RuntimeException | LinkageError ignored) { } }
    }
}

package net.sybyline.scarlet.ext;

import java.time.Instant;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;
import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessLease;

/**
 * Read-only Windows process-object evidence. Holding a handle keeps that process
 * identity available after its launcher exits; it never opens a game file,
 * requests termination access, changes EAC, or adopts a process by name alone.
 */
final class WindowsProcessEvidence implements ProcessLease
{
    static final long WINDOWS_EPOCH_SECONDS = 11_644_473_600L;

    interface Access
    {
        HANDLE open(long pid);
        ProcessInfo identity(HANDLE handle, long pid, long parentPid);
        Lifetime lifetime(HANDLE handle);
        void close(HANDLE handle);
    }

    static final class Lifetime
    {
        final Instant created, exited;
        Lifetime(Instant created, Instant exited) { this.created = created; this.exited = exited; }
    }

    private final Access access;
    private final ProcessInfo identity;
    private HANDLE handle;

    WindowsProcessEvidence(Access access, HANDLE handle, ProcessInfo identity)
    {
        this.access = access;
        this.handle = handle;
        this.identity = identity;
    }

    static ProcessInfo captureStarted(Process original)
    {
        return captureStarted(original, NativeAccess.INSTANCE);
    }

    static ProcessInfo captureStarted(Process original, Access access)
    {
        HANDLE handle = access.open(original.pid());
        if (handle == null) return null;
        boolean transferred = false;
        try
        {
            ProcessInfo info = access.identity(handle, original.pid(), -1L);
            // java.lang.Process is the actual returned process object. If it has
            // already exited, a PID lookup alone cannot establish a new receipt.
            if (!complete(info) || info.pid() != original.pid() || !original.isAlive()) return null;
            WindowsProcessEvidence evidence = new WindowsProcessEvidence(access, handle, info);
            ProcessInfo receipt = new ProcessInfo(info.pid(), info.startedAt(), info.user(), info.executable(),
                info.arguments(), info.parentPid(), evidence);
            transferred = true;
            return receipt;
        }
        finally { if (!transferred) access.close(handle); }
    }

    static ProcessInfo read(long pid, long parentPid)
    {
        return read(pid, parentPid, NativeAccess.INSTANCE);
    }

    /** Read-only identity for a handle owned and closed by the explicit termination transport. */
    static ProcessInfo identity(HANDLE handle, long pid)
    {
        return NativeAccess.INSTANCE.identity(handle, pid, -1L);
    }

    static ProcessInfo read(long pid, long parentPid, Access access)
    {
        HANDLE handle = access.open(pid);
        if (handle == null) return null;
        try { return access.identity(handle, pid, parentPid); }
        finally { access.close(handle); }
    }

    static ProcessLease retain(ProcessInfo expected)
    {
        return retain(expected, NativeAccess.INSTANCE);
    }

    static ProcessLease retain(ProcessInfo expected, Access access)
    {
        if (!complete(expected)) return null;
        HANDLE handle = access.open(expected.pid());
        if (handle == null) return null;
        boolean transferred = false;
        try
        {
            ProcessInfo actual = access.identity(handle, expected.pid(), expected.parentPid());
            if (!complete(actual) || !ManagedVrcClient.sameProcess(expected, actual)) return null;
            WindowsProcessEvidence evidence = new WindowsProcessEvidence(access, handle, expected);
            transferred = true;
            return evidence;
        }
        finally { if (!transferred) access.close(handle); }
    }

    public synchronized boolean provesParent(ProcessInfo parent, ProcessInfo child, Instant now)
    {
        if (this.handle == null || !complete(parent) || !complete(child)
            || !ManagedVrcClient.sameProcess(this.identity, parent) || child.parentPid() != parent.pid()
            || !parent.user().equals(child.user()) || now == null || child.startedAt().isAfter(now)) return false;
        Lifetime lifetime = this.access.lifetime(this.handle);
        // Exit is an exact OS timestamp, not the time a polling loop noticed the
        // parent disappear. Missing/inconsistent data never extends ownership.
        return lifetime != null && parent.startedAt().equals(lifetime.created)
            && !child.startedAt().isBefore(lifetime.created)
            && (lifetime.exited == null || (!lifetime.exited.isBefore(lifetime.created)
                && !lifetime.exited.isAfter(now) && !child.startedAt().isAfter(lifetime.exited)));
    }

    public synchronized void close()
    {
        HANDLE current = this.handle;
        this.handle = null;
        if (current != null) this.access.close(current);
    }

    private static boolean complete(ProcessInfo info)
    {
        return info != null && info.pid() > 0 && info.startedAt() != null && info.user() != null
            && !info.user().isBlank() && info.executable() != null && !info.executable().isBlank();
    }

    /** FILETIME conversion preserves 100ns identity precision, unlike Date/toTime(). */
    static Instant fileTime(int high, int low)
    {
        long ticks = (Integer.toUnsignedLong(high) << 32) | Integer.toUnsignedLong(low);
        if (ticks <= 0) return null;
        return Instant.ofEpochSecond(ticks / 10_000_000L - WINDOWS_EPOCH_SECONDS, (ticks % 10_000_000L) * 100L);
    }

    /** A parent exiting during the metadata read gets one bounded resample. */
    static Lifetime stableLifetime(IntSupplier state, Supplier<Lifetime> times)
    {
        for (int attempt = 0; attempt < 2; attempt++)
        {
            int before = state.getAsInt();
            if (before != WinBase.WAIT_OBJECT_0 && before != WinError.WAIT_TIMEOUT) return null;
            Lifetime timing = times.get();
            if (timing == null || timing.created == null) return null;
            int after = state.getAsInt();
            if (after != before) continue;
            if (before == WinBase.WAIT_OBJECT_0 && timing.exited == null) return null;
            return new Lifetime(timing.created, before == WinBase.WAIT_OBJECT_0 ? timing.exited : null);
        }
        return null;
    }

    private static final class NativeAccess implements Access
    {
        static final NativeAccess INSTANCE = new NativeAccess();

        public HANDLE open(long pid)
        {
            if (pid <= 0 || pid > 0xFFFF_FFFFL) return null;
            // No PROCESS_TERMINATE, VM_READ, injection, debug privilege or inherited handle.
            return Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION | WinNT.SYNCHRONIZE, false, (int)pid);
        }

        public ProcessInfo identity(HANDLE handle, long pid, long parentPid)
        {
            if (Integer.toUnsignedLong(Kernel32.INSTANCE.GetProcessId(handle)) != pid) return null;
            Lifetime before = lifetime(handle);
            if (before == null || before.exited != null) return null;
            ProcessHandle process = ProcessHandle.of(pid).orElse(null);
            if (process == null) return null;
            ProcessHandle.Info info = process.info();
            Lifetime after = lifetime(handle);
            if (after == null || after.exited != null || !before.created.equals(after.created)) return null;
            // All fields refer to the pinned process. Both discovery and final close
            // use this native creation timestamp, never a mixture of millisecond times.
            return new ProcessInfo(pid, before.created, info.user().orElse(null), info.command().orElse(null),
                info.arguments().map(List::of).orElse(List.of()), parentPid);
        }

        public Lifetime lifetime(HANDLE handle)
        {
            return stableLifetime(() -> Kernel32.INSTANCE.WaitForSingleObject(handle, 0), () -> {
                WinBase.FILETIME created = new WinBase.FILETIME(), exited = new WinBase.FILETIME();
                if (!Kernel32.INSTANCE.GetProcessTimes(handle, created, exited, new WinBase.FILETIME(), new WinBase.FILETIME())) return null;
                return new Lifetime(fileTime(created.dwHighDateTime, created.dwLowDateTime),
                    fileTime(exited.dwHighDateTime, exited.dwLowDateTime));
            });
        }

        public void close(HANDLE handle) { Kernel32.INSTANCE.CloseHandle(handle); }
    }
}

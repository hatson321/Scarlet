package net.sybyline.scarlet.ext;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import net.sybyline.scarlet.util.Platform;

/**
 * In-memory ownership evidence for a client newly launched by this Scarlet process.
 * Discovery only reads operating-system process metadata; no game files are opened.
 * Unknown, pre-existing and ambiguous clients are never adopted. Ownership does not
 * survive a Scarlet restart, and does not by itself establish which log is current.
 */
public final class ManagedVrcClient
{
    private ManagedVrcClient() {}

    public static final class Client
    {
        public final long launchToken, pid;
        public final String requestedLocation, user;
        public final Instant requestedAt, startedAt;

        public Client(long launchToken, String requestedLocation, Instant requestedAt,
                      long pid, Instant startedAt, String user)
        {
            this.launchToken = launchToken;
            this.requestedLocation = requestedLocation;
            this.requestedAt = requestedAt;
            this.pid = pid;
            this.startedAt = startedAt;
            this.user = user;
        }

        public long launchToken() { return this.launchToken; }
        public String requestedLocation() { return this.requestedLocation; }
        public Instant requestedAt() { return this.requestedAt; }
        public long pid() { return this.pid; }
        public Instant startedAt() { return this.startedAt; }
        public String user() { return this.user; }
    }

    public enum CloseResult
    {
        REQUESTED, NOT_MANAGED, IDENTITY_CHANGED, CANCELLED, UNSUPPORTED_OR_DENIED,
        WINDOW_ENUMERATION_FAILED, WINDOW_NOT_FOUND, WINDOW_AMBIGUOUS, WINDOW_IDENTITY_CHANGED,
        WINDOW_CHECK_UNAVAILABLE, FINAL_GUARD_VETO, WINDOWS_ACCESS_DENIED, WINDOWS_POST_REJECTED,
        DISPATCH_UNCERTAIN, PLATFORM_UNSUPPORTED;

        /** Only these outcomes prove that no native close dispatch was attempted. */
        public boolean retryableBeforeDispatch()
        {
            return this == WINDOW_ENUMERATION_FAILED || this == WINDOW_NOT_FOUND
                || this == WINDOW_AMBIGUOUS || this == FINAL_GUARD_VETO;
        }
    }

    public enum ForceResult { REQUESTED, NOT_MANAGED, IDENTITY_CHANGED, GUARD_VETO, DENIED, UNCERTAIN, UNSUPPORTED }

    /** Closed, privacy-safe launch diagnostics; these are not world-join outcomes. */
    public static final class LaunchFailure extends IOException
    {
        private static final long serialVersionUID = 1L;

        public enum Kind
        {
            REQUESTED_BUT_UNVERIFIED("VRChat launch was requested, but Scarlet could not verify process ownership within two minutes. "
                + "This does not say whether an instance was joined. Any opened client was left running; startup recovery will not close or restart it. The enabled closed/empty-instance exit setting is independent."),
            DISPATCH_UNCERTAIN("VRChat launch dispatch or process ownership could not be confirmed within two minutes. "
                + "Check VRChat directly; any opened client was left running; startup recovery will not close or restart it. The enabled closed/empty-instance exit setting is independent.");

            private final String description;
            Kind(String description) { this.description = description; }
        }

        private final Kind kind;
        private final String description;

        public LaunchFailure(Kind kind)
        {
            this(kind, null);
        }

        private LaunchFailure(Kind kind, DiscoveryIssue issue)
        {
            super(Objects.requireNonNull(kind, "kind").description
                + (issue == null ? "" : " Tracking check: " + issue.description));
            this.kind = kind;
            this.description = getMessage();
        }

        public Kind kind() { return this.kind; }
        public String description() { return this.description; }
    }

    /** Fixed descriptions only: never include commands, join links, account names or process IDs. */
    private enum DiscoveryIssue
    {
        WAITING("No verifiable client completed startup before the deadline."),
        LAUNCHER_IDENTITY("The returned launcher identity was unavailable; no other process was substituted."),
        PARENTAGE("The client could not be linked to the original launcher. A missing or unobserved handoff remains untrusted."),
        AMBIGUOUS("More than one client was observed; ownership was refused."),
        IDENTITY("A client had an unexpected, pre-existing or incomplete identity; ownership was refused."),
        INVENTORY("Windows/Linux process metadata could not be read consistently; ownership was refused."),
        CLOCK("The discovery time window was invalid or expired."),
        BOUND("The launcher handoff exceeded the bounded tracking capacity.");
        final String description;
        DiscoveryIssue(String description) { this.description = description; }
    }

    /** A read-only OS reference, never a right to terminate a process. */
    interface ProcessLease extends AutoCloseable
    {
        boolean provesParent(ProcessInfo parent, ProcessInfo child, Instant now);
        @Override void close();
    }

    static final class ProcessInfo
    {
        private final long pid;
        private final Instant startedAt;
        private final String user, executable;
        private final List<String> arguments;
        private final long parentPid;
        private final ProcessLease launchLease;

        ProcessInfo(long pid, Instant startedAt, String user, String executable, List<String> arguments)
        {
            this(pid, startedAt, user, executable, arguments, -1L);
        }

        ProcessInfo(long pid, Instant startedAt, String user, String executable, List<String> arguments, long parentPid)
        {
            this(pid, startedAt, user, executable, arguments, parentPid, null);
        }

        ProcessInfo(long pid, Instant startedAt, String user, String executable, List<String> arguments,
                    long parentPid, ProcessLease launchLease)
        {
            this.pid = pid;
            this.startedAt = startedAt;
            this.user = user;
            this.executable = executable;
            this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
            this.parentPid = parentPid;
            this.launchLease = launchLease;
        }

        long pid() { return this.pid; }
        Instant startedAt() { return this.startedAt; }
        String user() { return this.user; }
        String executable() { return this.executable; }
        List<String> arguments() { return this.arguments; }
        long parentPid() { return this.parentPid; }
    }

    interface Processes
    {
        String currentUser();
        List<ProcessInfo> snapshot();
        default List<ProcessInfo> clientSnapshot() { return this.snapshot(); }
        ProcessInfo find(long pid);
        boolean requestClose(ProcessInfo expected, BooleanSupplier revalidate);
        default CloseResult requestCloseDetailed(ProcessInfo expected, BooleanSupplier revalidate)
        {
            // Legacy transports cannot prove why false was returned; never retry it.
            return this.requestClose(expected, revalidate) ? CloseResult.REQUESTED : CloseResult.UNSUPPORTED_OR_DENIED;
        }
        default ProcessLease retain(ProcessInfo expected) { return null; }
        default ForceResult forceClose(ProcessInfo expected, BooleanSupplier revalidate) { return ForceResult.UNSUPPORTED; }
    }

    @FunctionalInterface interface LaunchAction { ProcessInfo start() throws Exception; }
    @FunctionalInterface interface LaunchGuard { void check() throws InterruptedException; }
    @FunctionalInterface interface GuardedLaunchAction { ProcessInfo start(LaunchGuard guard) throws Exception; }
    @FunctionalInterface interface Pause { void pause(long millis) throws InterruptedException; }

    /** Poll-driven bounded discovery avoids adding a permanent background thread. */
    public static final class Tracker
    {
        private static final Duration DISCOVERY_LIMIT = Duration.ofSeconds(120);
        private static final Duration STABLE_FOR = Duration.ofSeconds(3);
        static final long STARTUP_NANOS = TimeUnit.SECONDS.toNanos(120);
        static final long CLOSE_NANOS = TimeUnit.SECONDS.toNanos(15);
        static final long QUIET_NANOS = TimeUnit.SECONDS.toNanos(2);
        private final Processes processes;
        private final Clock clock;
        private final LongSupplier nanos;
        private final Pause pause;
        private final SingleVrcClient shutdownClients;
        private final AtomicBoolean launching = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicLong cancellation = new AtomicLong();
        private volatile long unresolvedUntil;
        private volatile Thread launchThread;
        private final Map<Long, ProcessInfo> unresolvedProcesses = new HashMap<>();
        private Attempt attempt;
        private Client managed;
        private long nextToken;
        private DiscoveryIssue discoveryIssue = DiscoveryIssue.WAITING;
        private static final int MAX_PROVENANCE = 128;

        public Tracker()
        {
            this(new NativeProcesses(), Clock.systemUTC());
        }

        Tracker(Processes processes, Clock clock)
        {
            this(processes, clock, System::nanoTime, Thread::sleep);
        }

        Tracker(Processes processes, Clock clock, LongSupplier nanos, Pause pause)
        {
            this.processes = processes;
            this.clock = clock;
            this.nanos = nanos;
            this.pause = pause;
            this.shutdownClients = new SingleVrcClient(processes, clock);
        }

        /** Explicit exit-toggle authority, separate from launch/recovery ownership. */
        public synchronized SingleVrcClient.Selection shutdownClient(String location, Instant joinedAt)
        {
            if (this.launching.get() || this.stopped.get())
                return new SingleVrcClient.Selection(null, SingleVrcClient.Status.LAUNCH_BUSY);
            return this.shutdownClients.select(location, joinedAt);
        }

        public synchronized CloseResult closeShutdownClient(Client expected, BooleanSupplier allowed)
        {
            return this.shutdownClients.close(expected, () -> !this.launching.get() && !this.stopped.get()
                && allowed != null && allowed.getAsBoolean());
        }

        public synchronized ForceResult forceShutdownClient(Client expected, BooleanSupplier allowed)
        {
            return this.shutdownClients.force(expected, () -> !this.launching.get() && !this.stopped.get()
                && allowed != null && allowed.getAsBoolean());
        }

        /** Process metadata only. Uncertainty must never be interpreted as an empty machine. */
        private List<ProcessInfo> checkedSnapshot()
        {
            List<ProcessInfo> snapshot = this.processes.snapshot();
            if (snapshot == null || snapshot.size() > 100_000) throw new IllegalStateException("Process inventory is unavailable or exceeds its bound");
            Set<Long> pids = new HashSet<>();
            for (ProcessInfo process : snapshot)
                if (process == null || process.pid() < 0L || !pids.add(process.pid())
                    || process.executable() == null || process.executable().isBlank()
                    || (basename(process.executable()).startsWith("wine") && process.arguments().isEmpty()))
                    throw new IllegalStateException("Process inventory is incomplete");
            return snapshot;
        }

        public boolean hasClientsOrPending()
        {
            if (this.launching.get() || this.beforeUnresolvedDeadline()) return true;
            try { return this.hasUnresolvedProcesses() || this.checkedSnapshot().stream().anyMatch(ManagedVrcClient::isClient); }
            catch (RuntimeException | LinkageError unknown) { return true; }
        }

        /** Positive detection for health/privacy callers; pending startup is not a live client. */
        public List<String> confirmedClientPids()
        {
            try
            {
                return this.checkedSnapshot().stream().filter(process -> process.pid() > 0L && isClient(process))
                    .map(process -> Long.toString(process.pid())).collect(Collectors.toList());
            }
            catch (RuntimeException | LinkageError unknown) { return List.of(); }
        }

        public void cancelPendingLaunches()
        {
            this.cancellation.incrementAndGet();
            synchronized (this)
            {
                if (this.managed == null)
                {
                    if (!this.launching.get()) this.clearAttempt();
                    else if (this.attempt != null) this.attempt.releaseLeases();
                }
            }
        }

        /** Scarlet shutdown only cancels launcher work; it never closes a game by itself. */
        public void stopLaunching()
        {
            this.stopped.set(true);
            this.cancellation.incrementAndGet();
            synchronized (this) { this.clearAttempt(); }
            Thread active = this.launchThread;
            if (active != null) active.interrupt();
        }

        private boolean beforeUnresolvedDeadline()
        {
            long deadline = this.unresolvedUntil;
            return deadline != 0L && this.nanos.getAsLong() - deadline < 0L;
        }

        /** A timed-out launcher is not evidence that the actual launch process exited. */
        private synchronized boolean hasUnresolvedProcesses()
        {
            if (this.unresolvedProcesses.isEmpty()) return false;
            Map<Long, ProcessInfo> live = new HashMap<>();
            for (ProcessInfo process : this.checkedSnapshot()) live.put(process.pid(), process);
            // Keep observing a still-live retained parent even after ownership was
            // refused, so a newly observed bootstrap child cannot be forgotten.
            for (int pass = 0; pass < 64; pass++)
            {
                boolean changed = false;
                for (ProcessInfo child : live.values())
                {
                    if (this.unresolvedProcesses.containsKey(child.pid()) || !completeIdentity(child)) continue;
                    ProcessInfo parent = this.unresolvedProcesses.get(child.parentPid());
                    if (completeIdentity(parent) && sameProcess(parent, live.get(parent.pid()))
                        && parent.user().equals(child.user()) && !child.startedAt().isBefore(parent.startedAt())
                        && !child.startedAt().isAfter(this.clock.instant()))
                    {
                        this.unresolvedProcesses.put(child.pid(), child);
                        changed = true;
                    }
                }
                if (!changed) break;
            }
            this.unresolvedProcesses.entrySet().removeIf(entry -> {
                ProcessInfo observed = live.get(entry.getKey()), current = this.processes.find(entry.getKey());
                if (observed == null && current == null) return true;
                Instant started = entry.getValue().startedAt();
                // Only known, consistent replacement identities prove that the old
                // process is gone. Missing/inconsistent metadata remains a blocker.
                return started != null && observed != null && current != null
                    && observed.startedAt() != null && observed.startedAt().equals(current.startedAt())
                    && !started.equals(current.startedAt());
            });
            return !this.unresolvedProcesses.isEmpty();
        }

        /** One bounded lifecycle, without holding the ownership monitor while waiting. */
        void launch(String location, boolean allowManagedRestart, LaunchAction starter) throws Exception
        {
            this.launch(location, allowManagedRestart, guard -> { guard.check(); return starter.start(); });
        }

        void launch(String location, boolean allowManagedRestart, GuardedLaunchAction starter) throws Exception
        {
            this.launch(location, allowManagedRestart, starter, null, () -> true, () -> true, null);
        }

        void launch(String location, boolean allowManagedRestart, GuardedLaunchAction starter, Client recoveryExpected,
            BooleanSupplier allowed, BooleanSupplier closeAllowed, BooleanSupplier forceAllowed) throws Exception
        {
            if (location == null || location.isBlank())
                throw new IOException("A concrete destination is required; no VRChat client was stopped or launched.");
            synchronized (this)
            {
                if (!this.launching.compareAndSet(false, true))
                    throw new IOException("A VRChat launch or restart is already in progress. Wait for it to finish.");
            }
            this.launchThread = Thread.currentThread();
            long generation = this.cancellation.get();
            long token = 0L;
            boolean success = false;
            try
            {
                if (!allowed.getAsBoolean()) throw new InterruptedException("Automatic launch context changed");
                if (recoveryExpected != null && !Objects.equals(location, recoveryExpected.requestedLocation()))
                    throw new IOException("Recovery destination changed; no client was stopped");
                if (this.beforeUnresolvedDeadline())
                    throw new IOException("A previous VRChat startup is still unresolved. Wait before requesting another launch.");
                this.checkCancelled(generation);
                if (this.hasUnresolvedProcesses())
                    throw new IOException("A previous VRChat startup is still unresolved: its launcher or an observed child is still running, or its exit cannot be verified. Close it yourself and retry; Scarlet did not stop it or dispatch another launch.");
                List<ProcessInfo> clients = this.checkedSnapshot().stream().filter(ManagedVrcClient::isClient).collect(Collectors.toList());
                Client existing;
                synchronized (this) { existing = this.currentInternal(); }
                if (recoveryExpected != null)
                {
                    if (existing != null && existing != recoveryExpected)
                        throw new IOException("Recovery process ownership changed; no client was stopped");
                    if (clients.isEmpty() && this.processes.find(recoveryExpected.pid()) != null)
                        throw new IOException("Recovery process exit cannot be verified");
                }
                if (!clients.isEmpty())
                {
                    if (!allowManagedRestart)
                        throw new IOException("VRChat is already running; automatic opening did not stop it. Use an invite to join.");
                    if (clients.size() != 1 || existing == null || !matches(existing, clients.get(0)))
                        throw new IOException("The running VRChat client is not uniquely verified as Scarlet-launched. Close it yourself or use an invite; Scarlet did not stop it.");
                    CloseResult result;
                    synchronized (this)
                    {
                        result = this.closeInternal(existing, () -> this.validGeneration(generation)
                            && allowed.getAsBoolean() && closeAllowed.getAsBoolean());
                    }
                    if (result != CloseResult.REQUESTED)
                    {
                        if (recoveryExpected != null) org.slf4j.LoggerFactory.getLogger("Scarlet/VrcLaunch")
                            .info("Startup recovery close-stage: {}", result);
                        boolean definiteRejection = result == CloseResult.WINDOW_NOT_FOUND || result == CloseResult.WINDOW_AMBIGUOUS
                            || result == CloseResult.WINDOWS_ACCESS_DENIED || result == CloseResult.WINDOWS_POST_REJECTED;
                        if (recoveryExpected == null || forceAllowed == null || !definiteRejection || !forceAllowed.getAsBoolean())
                            throw new IOException("The verified VRChat client could not be asked to exit normally (" + result + "). No replacement was launched.");
                    }
                    this.awaitQuiet(existing, generation, recoveryExpected == null || forceAllowed == null ? null
                        : () -> allowed.getAsBoolean() && closeAllowed.getAsBoolean() && forceAllowed.getAsBoolean());
                }
                else this.awaitQuiet(recoveryExpected, generation);

                this.checkCancelled(generation);
                if (!allowed.getAsBoolean()) throw new InterruptedException("Automatic launch context changed");
                token = this.beginLaunch(location);
                if (token == 0L)
                    throw new IOException("VRChat process ownership could not be established. No launch was requested; check existing clients and process access.");
                this.checkCancelled(generation);
                this.unresolvedUntil = this.nanos.getAsLong() + STARTUP_NANOS;
                Exception startFailure = null;
                ProcessInfo launcher = null;
                try { launcher = starter.start(() -> this.checkCancelled(generation)); }
                catch (InterruptedException interrupted) { throw interrupted; }
                catch (Exception failure) { startFailure = failure; }
                this.launchCompleted(token, launcher);
                while (this.beforeUnresolvedDeadline())
                {
                    this.checkCancelled(generation);
                    Client current;
                    synchronized (this) { current = this.currentInternal(); }
                    if (current != null && current.launchToken() == token)
                    {
                        this.checkCancelled(generation);
                        success = true;
                        this.unresolvedUntil = 0L;
                        synchronized (this) { this.unresolvedProcesses.clear(); }
                        return;
                    }
                    this.pause.pause(100L);
                }
                LaunchFailure failure = new LaunchFailure(startFailure == null
                    ? LaunchFailure.Kind.REQUESTED_BUT_UNVERIFIED : LaunchFailure.Kind.DISPATCH_UNCERTAIN, this.discoveryIssue);
                if (startFailure != null) failure.initCause(startFailure);
                throw failure;
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            catch (RuntimeException | LinkageError unknown)
            {
                throw new IOException("VRChat process state could not be verified. Scarlet refused to stop or restart an uncertain client.", unknown);
            }
            finally
            {
                if (!success && token != 0L) this.launchFailed(token);
                this.launchThread = null;
                this.launching.set(false);
            }
        }

        private boolean validGeneration(long generation)
        {
            return !this.stopped.get() && this.cancellation.get() == generation && !Thread.currentThread().isInterrupted();
        }

        private void checkCancelled(long generation) throws InterruptedException
        {
            if (!this.validGeneration(generation)) throw new InterruptedException("Launcher cancelled");
        }

        private void awaitQuiet(Client closing, long generation) throws Exception
        {
            this.awaitQuiet(closing, generation, null);
        }

        private void awaitQuiet(Client closing, long generation, BooleanSupplier forceAllowed) throws Exception
        {
            long started = this.nanos.getAsLong(), quietSince = 0L;
            boolean quiet = false, forceRequested = false;
            long limit = CLOSE_NANOS + QUIET_NANOS + (forceAllowed == null ? 0 : CLOSE_NANOS);
            while (this.nanos.getAsLong() - started < limit)
            {
                this.checkCancelled(generation);
                List<ProcessInfo> clients = this.checkedSnapshot().stream().filter(ManagedVrcClient::isClient).collect(Collectors.toList());
                if (closing != null)
                {
                    ProcessInfo stillPresent = this.processes.find(closing.pid());
                    if (stillPresent != null && (!matches(closing, stillPresent) || clients.isEmpty()))
                        throw new IOException("The closing process changed identity or its exit could not be confirmed. No replacement was launched.");
                }
                if (clients.isEmpty())
                {
                    long now = this.nanos.getAsLong();
                    if (!quiet) { quiet = true; quietSince = now; }
                    if (now - quietSince >= QUIET_NANOS) return;
                }
                else
                {
                    if (closing == null || clients.size() != 1 || !matches(closing, clients.get(0)))
                        throw new IOException("Another or changed VRChat process appeared. Restart was cancelled without stopping it.");
                    quiet = false;
                    if (this.nanos.getAsLong() - started >= (forceRequested ? CLOSE_NANOS * 2 : CLOSE_NANOS))
                    {
                        if (forceRequested || forceAllowed == null || !forceAllowed.getAsBoolean())
                            throw new IOException("VRChat did not finish its normal exit. No replacement was launched.");
                        ForceResult result;
                        synchronized (this) { result = this.forceInternal(closing, () -> this.validGeneration(generation) && forceAllowed.getAsBoolean()); }
                        org.slf4j.LoggerFactory.getLogger("Scarlet/VrcLaunch").warn("Startup recovery force-close result: {}", result);
                        if (result != ForceResult.REQUESTED) throw new IOException("Startup force-close was refused or uncertain; no replacement was launched");
                        forceRequested = true;
                    }
                }
                this.pause.pause(100L);
            }
            throw new IOException("VRChat shutdown did not reach a verified quiet state. No replacement was launched.");
        }

        /** Begin only after an independently verified empty, quiet process inventory. */
        public synchronized long beginLaunch(String location)
        {
            if (location == null || location.isBlank())
                return 0;
            try
            {
                String user = this.processes.currentUser();
                if (user == null || user.isBlank())
                    return 0;
                Instant requested = this.clock.instant();
                Set<Long> existing = new HashSet<>();
                for (ProcessInfo process : this.checkedSnapshot())
                {
                    existing.add(process.pid());
                    // An existing client makes attribution unsafe, even under another account.
                    if (isClient(process))
                        return 0;
                }
                long token = ++this.nextToken;
                this.clearAttempt();
                this.discoveryIssue = DiscoveryIssue.WAITING;
                this.attempt = new Attempt(token, location, requested, user, existing);
                this.managed = null;
                return token;
            }
            catch (RuntimeException ex)
            {
                return 0;
            }
        }

        public synchronized void launchCompleted(long token)
        {
            this.launchCompleted(token, null);
        }

        synchronized void launchCompleted(long token, ProcessInfo launcher)
        {
            if (this.attempt != null && this.attempt.token == token && this.attempt.ready)
            {
                if (launcher != null && this.attempt.leases.get(launcher.pid()) != launcher.launchLease)
                    releaseLease(launcher.launchLease);
                return;
            }
            boolean retained = false;
            if (this.attempt != null && this.attempt.token == token)
            {
                this.attempt.ready = true;
                if (launcher != null && launcher.pid() > 0L)
                    this.unresolvedProcesses.put(launcher.pid(), launcher);
                if (launcher != null && completeIdentity(launcher) && this.attempt.user.equals(launcher.user())
                    && !this.attempt.existing.contains(launcher.pid()) && !launcher.startedAt().isBefore(this.attempt.requested)
                    && !launcher.startedAt().isAfter(this.clock.instant()))
                {
                    this.attempt.provenance.put(launcher.pid(), launcher);
                    ProcessLease lease = launcher.launchLease != null ? launcher.launchLease : this.processes.retain(launcher);
                    if (lease != null)
                    {
                        this.attempt.leases.put(launcher.pid(), lease);
                        retained = lease == launcher.launchLease;
                    }
                }
                else this.discoveryIssue = DiscoveryIssue.LAUNCHER_IDENTITY;
            }
            if (!retained && launcher != null) releaseLease(launcher.launchLease);
        }

        public synchronized void launchFailed(long token)
        {
            if (this.attempt != null && this.attempt.token == token)
            {
                this.clearAttempt();
            }
        }

        public synchronized Client current()
        {
            return this.launching.get() ? null : this.currentInternal();
        }

        private Client currentInternal()
        {
            try
            {
                if (this.managed != null)
                {
                    if (!matchesManaged(this.managed, this.processes.find(this.managed.pid())) || !uniqueManaged(this.managed))
                    {
                        this.clearAttempt();
                    }
                    return this.managed;
                }
                Attempt pending = this.attempt;
                if (pending == null || !pending.ready)
                    return null;
                Instant now = this.clock.instant();
                if (now.isBefore(pending.requested) || now.isAfter(pending.requested.plus(DISCOVERY_LIMIT)))
                {
                    this.discoveryIssue = DiscoveryIssue.CLOCK;
                    this.clearAttempt();
                    return null;
                }
                List<ProcessInfo> snapshot = this.checkedSnapshot();
                this.extendProvenance(pending, snapshot);
                List<ProcessInfo> candidates = new ArrayList<>();
                for (ProcessInfo process : snapshot)
                    if (isClient(process))
                        candidates.add(process);
                if (candidates.size() > 1)
                {
                    this.discoveryIssue = DiscoveryIssue.AMBIGUOUS;
                    this.clearAttempt(); // Permanent ambiguity for this launch, not just a retry.
                    return null;
                }
                if (candidates.isEmpty())
                {
                    pending.candidate = null;
                    pending.candidateSince = null;
                    return null;
                }
                ProcessInfo candidate = candidates.get(0);
                if (pending.existing.contains(candidate.pid()) || candidate.startedAt() == null
                    || candidate.startedAt().isBefore(pending.requested) || candidate.startedAt().isAfter(now)
                    || !pending.user.equals(candidate.user()) || !sameProcess(pending.provenance.get(candidate.pid()), candidate))
                {
                    if (pending.existing.contains(candidate.pid()) || !completeIdentity(candidate)
                        || candidate.startedAt().isBefore(pending.requested) || candidate.startedAt().isAfter(now)
                        || !pending.user.equals(candidate.user()))
                        this.discoveryIssue = DiscoveryIssue.IDENTITY;
                    else if (this.discoveryIssue != DiscoveryIssue.LAUNCHER_IDENTITY)
                        this.discoveryIssue = DiscoveryIssue.PARENTAGE;
                    this.clearAttempt();
                    return null;
                }
                if (!sameProcess(pending.candidate, candidate))
                {
                    pending.candidate = candidate;
                    pending.candidateSince = now;
                    return null;
                }
                if (now.isBefore(pending.candidateSince.plus(STABLE_FOR)))
                    return null;
                this.managed = new Client(pending.token, pending.location, pending.requested,
                    candidate.pid(), candidate.startedAt(), candidate.user());
                // Provenance is now fixed. No OS handles need to survive discovery.
                pending.releaseLeases();
                if (!this.launching.get()) this.unresolvedProcesses.clear();
                return this.managed;
            }
            catch (RuntimeException | LinkageError ex)
            {
                if (this.discoveryIssue != DiscoveryIssue.BOUND) this.discoveryIssue = DiscoveryIssue.INVENTORY;
                this.clearAttempt();
                return null;
            }
        }

        private void extendProvenance(Attempt pending, List<ProcessInfo> snapshot)
        {
            Map<Long, ProcessInfo> live = new HashMap<>();
            for (ProcessInfo process : snapshot) live.put(process.pid(), process);
            // Windows can finish a launcher before the next poll. An OS reference
            // retained while that exact parent was alive proves its lifetime after exit.
            // Without it, keep the original live-parent rule (including Linux).
            for (int pass = 0; pass < 64; pass++)
            {
                boolean changed = false;
                for (ProcessInfo child : snapshot)
                {
                    if (pending.provenance.containsKey(child.pid()) || pending.existing.contains(child.pid())
                        || !completeIdentity(child) || !pending.user.equals(child.user())) continue;
                    ProcessInfo parent = pending.provenance.get(child.parentPid());
                    ProcessInfo observedParent = parent == null ? null : live.get(parent.pid());
                    ProcessLease lease = parent == null ? null : pending.leases.get(parent.pid());
                    boolean retainedParent = lease != null && (observedParent == null || sameProcess(parent, observedParent))
                        && lease.provesParent(parent, child, this.clock.instant());
                    if (parent != null && (retainedParent || sameProcess(parent, observedParent))
                        && !child.startedAt().isBefore(parent.startedAt()) && !child.startedAt().isAfter(this.clock.instant()))
                    {
                        if (pending.provenance.size() >= MAX_PROVENANCE)
                        {
                            this.discoveryIssue = DiscoveryIssue.BOUND;
                            throw new IllegalStateException("Bounded launcher provenance exhausted");
                        }
                        pending.provenance.put(child.pid(), child);
                        this.unresolvedProcesses.put(child.pid(), child);
                        ProcessLease childLease = this.processes.retain(child);
                        if (childLease != null) pending.leases.put(child.pid(), childLease);
                        changed = true;
                    }
                }
                if (!changed) return;
            }
        }

        /**
         * Only requests a normal close; REQUESTED does not promise that the app exited.
         * The caller must freshly confirm its instance, roster, log session and setting.
         */
        public synchronized CloseResult close(Client expected, BooleanSupplier revalidate)
        {
            if (this.launching.get() || this.stopped.get()) return CloseResult.CANCELLED;
            return this.closeInternal(expected, revalidate);
        }

        /** Separate opt-in path; ordinary close() and launch() never invoke forced termination. */
        public synchronized ForceResult forceClose(Client expected, BooleanSupplier revalidate)
        {
            if (this.launching.get() || this.stopped.get()) return ForceResult.GUARD_VETO;
            return this.forceInternal(expected, revalidate);
        }

        private ForceResult forceInternal(Client expected, BooleanSupplier revalidate)
        {
            if (expected == null || expected != this.managed) return ForceResult.NOT_MANAGED;
            try
            {
                ProcessInfo info = this.processes.find(expected.pid());
                if (!matchesManaged(expected, info) || !expected.user().equals(this.processes.currentUser()) || !uniqueManaged(expected))
                    return ForceResult.IDENTITY_CHANGED;
                if (this.stopped.get() || revalidate == null || !revalidate.getAsBoolean()) return ForceResult.GUARD_VETO;
                return this.processes.forceClose(info, () -> !this.stopped.get() && expected == this.managed
                    && matchesManaged(expected, this.processes.find(expected.pid()))
                    && expected.user().equals(this.processes.currentUser()) && uniqueManaged(expected) && revalidate.getAsBoolean());
            }
            catch (RuntimeException | LinkageError ex) { return ForceResult.UNCERTAIN; }
        }

        private CloseResult closeInternal(Client expected, BooleanSupplier revalidate)
        {
            if (this.stopped.get()) return CloseResult.CANCELLED;
            if (expected == null || !expected.equals(this.managed))
                return CloseResult.NOT_MANAGED;
            try
            {
                ProcessInfo process = this.processes.find(expected.pid());
                if (!matchesManaged(expected, process) || !expected.user().equals(this.processes.currentUser())
                    || !uniqueManaged(expected))
                {
                    this.clearAttempt();
                    return CloseResult.IDENTITY_CHANGED;
                }
                if (revalidate == null) return CloseResult.CANCELLED;
                if (!revalidate.getAsBoolean()) return CloseResult.FINAL_GUARD_VETO;
                java.util.concurrent.atomic.AtomicReference<CloseResult> veto = new java.util.concurrent.atomic.AtomicReference<>();
                CloseResult result = this.processes.requestCloseDetailed(process, () -> {
                    if (this.stopped.get()) { veto.set(CloseResult.CANCELLED); return false; }
                    if (!expected.equals(this.managed) || !matchesManaged(expected, this.processes.find(expected.pid()))
                        || !expected.user().equals(this.processes.currentUser()) || !uniqueManaged(expected))
                    { veto.set(CloseResult.IDENTITY_CHANGED); return false; }
                    if (!revalidate.getAsBoolean()) { veto.set(CloseResult.FINAL_GUARD_VETO); return false; }
                    return true;
                });
                // Only refine the explicit pre-dispatch veto. Legacy/unknown failures
                // remain terminal, even if a transport happened to invoke the guard.
                return result == CloseResult.FINAL_GUARD_VETO && veto.get() != null ? veto.get() : result;
            }
            catch (RuntimeException | LinkageError ex)
            {
                return CloseResult.UNSUPPORTED_OR_DENIED;
            }
        }

        private boolean matchesManaged(Client client, ProcessInfo process)
        {
            return this.attempt != null && sameProcess(this.attempt.candidate, process) && matches(client, process);
        }

        private boolean uniqueManaged(Client client)
        {
            try
            {
                int count = 0;
                for (ProcessInfo process : this.checkedSnapshot())
                    if (isClient(process))
                    {
                        if (!matchesManaged(client, process) || ++count > 1)
                        {
                            this.clearAttempt();
                            return false;
                        }
                    }
                if (count == 1)
                    return true;
            }
            catch (RuntimeException ex)
            {
                // Losing process enumeration also loses evidence that the current log
                // belongs to our only client. Do not reacquire this launch afterward.
            }
            this.clearAttempt();
            return false;
        }

        private void clearAttempt()
        {
            if (this.attempt != null) this.attempt.releaseLeases();
            this.attempt = null;
            this.managed = null;
        }
    }

    private static final class Attempt
    {
        final long token;
        final String location, user;
        final Instant requested;
        final Set<Long> existing;
        final Map<Long, ProcessInfo> provenance = new HashMap<>();
        final Map<Long, ProcessLease> leases = new HashMap<>();
        boolean ready;
        ProcessInfo candidate;
        Instant candidateSince;

        Attempt(long token, String location, Instant requested, String user, Set<Long> existing)
        {
            this.token = token;
            this.location = location;
            this.requested = requested;
            this.user = user;
            this.existing = Set.copyOf(existing);
        }

        void releaseLeases()
        {
            for (ProcessLease lease : this.leases.values()) releaseLease(lease);
            this.leases.clear();
        }
    }

    private static void releaseLease(ProcessLease lease)
    {
        if (lease != null)
            try { lease.close(); }
            catch (RuntimeException | LinkageError unavailable) { /* Continue releasing the remaining handles. */ }
    }

    static boolean matches(Client client, ProcessInfo process)
    {
        return process != null && client.pid() == process.pid() && client.startedAt() != null
            && client.startedAt().equals(process.startedAt()) && client.user() != null
            && client.user().equals(process.user()) && isClient(process);
    }

    private static boolean completeIdentity(ProcessInfo process)
    {
        return process != null && process.pid() > 0L && process.startedAt() != null
            && process.user() != null && !process.user().isBlank()
            && process.executable() != null && !process.executable().isBlank();
    }

    static boolean sameProcess(ProcessInfo first, ProcessInfo second)
    {
        return first != null && second != null && first.pid() == second.pid()
            && Objects.equals(first.startedAt(), second.startedAt()) && Objects.equals(first.user(), second.user())
            && Objects.equals(first.executable(), second.executable()) && first.arguments().equals(second.arguments());
    }

    static boolean isClient(ProcessInfo process)
    {
        if (process == null || process.executable() == null)
            return false;
        String executable = basename(process.executable());
        if ("vrchat.exe".equals(executable))
            return true;
        // Proton may expose the Wine loader as the executable. Accept only its first
        // argument naming VRChat.exe, never a loose substring or a later shell argument.
        return Set.of("wine", "wine64", "wine-preloader", "wine64-preloader").contains(executable)
            && !process.arguments().isEmpty() && "vrchat.exe".equals(basename(process.arguments().get(0)));
    }

    private static String basename(String value)
    {
        int last = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return value.substring(last + 1).toLowerCase(Locale.ROOT);
    }

    /** Metadata for the actual returned launcher, never a later name-based lookup. */
    static ProcessInfo startedProcess(Process process)
    {
        if (process == null) return null;
        try
        {
            if (Platform.CURRENT.isNT())
            {
                ProcessInfo retained = WindowsProcessEvidence.captureStarted(process);
                return retained == null ? new ProcessInfo(process.pid(), null, null, null, List.of()) : retained;
            }
            ProcessInfo info = NativeProcesses.info(process.toHandle());
            return info == null ? new ProcessInfo(process.pid(), null, null, null, List.of()) : info;
        }
        catch (RuntimeException failure) { return new ProcessInfo(process.pid(), null, null, null, List.of()); }
    }

    private static final class NativeProcesses implements Processes
    {
        public ForceResult forceClose(ProcessInfo expected, BooleanSupplier revalidate)
        {
            return Platform.CURRENT.isNT() ? WindowsClientTermination.request(expected, revalidate) : ForceResult.UNSUPPORTED;
        }
        public ProcessLease retain(ProcessInfo expected)
        {
            return Platform.CURRENT.isNT() ? WindowsProcessEvidence.retain(expected) : null;
        }

        public String currentUser()
        {
            return ProcessHandle.current().info().user().orElse(null);
        }

        public List<ProcessInfo> snapshot()
        {
            if (Platform.CURRENT.isNT()) return windowsSnapshot();
            if (!"linux".equalsIgnoreCase(System.getProperty("os.name")))
                throw new IllegalStateException("Process inventory is unsupported on this operating system");
            try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses())
            {
                return processes.map(process -> {
                    ProcessInfo info = info(process);
                    if (info == null || info.executable() != null) return info;
                    // /proc/comm is OS process metadata, not a game file. It supplies a
                    // name for protected unrelated processes, not ownership evidence.
                    try
                    {
                        String name = java.nio.file.Files.readString(java.nio.file.Path.of("/proc", Long.toString(process.pid()), "comm")).trim();
                        if (name.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith("wine"))
                            throw new IOException("Unidentified process command");
                        return new ProcessInfo(info.pid(), info.startedAt(), info.user(), name, info.arguments(), info.parentPid());
                    }
                    catch (IOException | RuntimeException failure)
                    {
                        if (!process.isAlive()) return null;
                        throw new IllegalStateException("Process inventory is incomplete", failure);
                    }
                }).filter(Objects::nonNull).collect(Collectors.toList());
            }
        }

        public List<ProcessInfo> clientSnapshot()
        {
            return Platform.CURRENT.isNT() ? windowsSnapshot(true) : this.snapshot();
        }

        private static List<ProcessInfo> windowsSnapshot()
        {
            return windowsSnapshot(false);
        }

        private static List<ProcessInfo> windowsSnapshot(boolean clientsOnly)
        {
            Instant snapshotStarted = Instant.now();
            HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new DWORD(0));
            if (snapshot == null || WinBase.INVALID_HANDLE_VALUE.equals(snapshot))
                throw new IllegalStateException("Windows process inventory is unavailable");
            try
            {
                List<ProcessInfo> result = new ArrayList<>();
                Tlhelp32.PROCESSENTRY32 entry = new Tlhelp32.PROCESSENTRY32();
                if (!Kernel32.INSTANCE.Process32First(snapshot, entry))
                    throw new IllegalStateException("Windows process inventory could not be read");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                do
                {
                    if (result.size() >= 100_000 || System.nanoTime() - deadline >= 0L)
                        throw new IllegalStateException("Windows process inventory exceeded its bound");
                    long pid = entry.th32ProcessID.longValue(), parent = entry.th32ParentProcessID.longValue();
                    String name = Native.toString(entry.szExeFile);
                    // Shutdown needs a complete name inventory, but not the identity of
                    // unrelated processes. Their normal churn cannot invalidate a client.
                    boolean readIdentity = !clientsOnly || "vrchat.exe".equalsIgnoreCase(name);
                    ProcessInfo info = pid <= 0L || !readIdentity ? null : WindowsProcessEvidence.read(pid, parent);
                    // The Toolhelp parent PID belongs to its captured process. Do not
                    // attach that parent to a replacement born after capture began.
                    validateSnapshotCreation(info, snapshotStarted);
                    // Toolhelp supplies complete executable names even when protected
                    // OS-process metadata is unavailable. Never use that name alone to
                    // establish ownership or select a close target.
                    if (info == null)
                        result.add(new ProcessInfo(pid, null, null, name, List.of(), parent));
                    else
                        result.add(new ProcessInfo(pid, info.startedAt(), info.user(),
                            info.executable() == null ? name : info.executable(), info.arguments(), parent));
                }
                while (Kernel32.INSTANCE.Process32Next(snapshot, entry));
                if (Kernel32.INSTANCE.GetLastError() != WinError.ERROR_NO_MORE_FILES)
                    throw new IllegalStateException("Windows process inventory ended unexpectedly");
                return result;
            }
            finally { Kernel32.INSTANCE.CloseHandle(snapshot); }
        }

        public ProcessInfo find(long pid)
        {
            if (Platform.CURRENT.isNT()) return WindowsProcessEvidence.read(pid, -1L);
            return ProcessHandle.of(pid).map(NativeProcesses::info).orElse(null);
        }

        private static ProcessInfo info(ProcessHandle process)
        {
            if (!process.isAlive())
                return null;
            ProcessHandle.Info info = process.info();
            return new ProcessInfo(process.pid(), info.startInstant().orElse(null), info.user().orElse(null),
                info.command().orElse(null), info.arguments().map(List::of).orElse(List.of()),
                process.parent().map(ProcessHandle::pid).orElse(-1L));
        }

        public boolean requestClose(ProcessInfo expected, BooleanSupplier revalidate)
        {
            return this.requestCloseDetailed(expected, revalidate) == CloseResult.REQUESTED;
        }

        public CloseResult requestCloseDetailed(ProcessInfo expected, BooleanSupplier revalidate)
        {
            if (Platform.CURRENT.isNT())
            {
                return WindowsClientClose.request(expected.pid(), new WindowsClientClose.Windows<HWND>() {
                    public List<HWND> visible(long expectedPid) {
                        List<HWND> windows = new ArrayList<>();
                        boolean enumerated = User32.INSTANCE.EnumWindows((window, data) -> {
                            if (this.belongsTo(window, expectedPid) && User32.INSTANCE.IsWindowVisible(window)) windows.add(window);
                            return true;
                        }, null);
                        return enumerated ? windows : null;
                    }
                    public boolean belongsTo(HWND window, long pid) {
                        IntByReference found = new IntByReference();
                        return User32.INSTANCE.GetWindowThreadProcessId(window, found) != 0
                            && Integer.toUnsignedLong(found.getValue()) == pid;
                    }
                    public boolean postClose(HWND window) {
                        Native.setLastError(0);
                        return WindowsMessages.INSTANCE.PostMessageW(window, WinUser.WM_CLOSE, new WPARAM(0), new LPARAM(0));
                    }
                    public int lastError() { return Native.getLastError(); }
                }, () -> sameProcess(expected, find(expected.pid())), revalidate,
                    code -> org.slf4j.LoggerFactory.getLogger("Scarlet/VrcLaunch").warn("VRChat close diagnostic: windows-post-rejected; win32-error={}", code));
            }
            if (!"linux".equalsIgnoreCase(System.getProperty("os.name")))
                return CloseResult.PLATFORM_UNSUPPORTED;
            ProcessHandle handle = ProcessHandle.of(expected.pid()).orElse(null);
            if (handle == null || !sameProcess(expected, info(handle))) return CloseResult.IDENTITY_CHANGED;
            if (!revalidate.getAsBoolean()) return CloseResult.FINAL_GUARD_VETO;
            try { return handle.destroy() ? CloseResult.REQUESTED : CloseResult.UNSUPPORTED_OR_DENIED; }
            catch (RuntimeException | LinkageError ex) { return CloseResult.DISPATCH_UNCERTAIN; }
        }
    }

    private interface WindowsMessages extends StdCallLibrary
    {
        WindowsMessages INSTANCE = Native.load("user32", WindowsMessages.class);
        boolean PostMessageW(HWND window, int message, WPARAM wParam, LPARAM lParam);
    }

    static void validateSnapshotCreation(ProcessInfo info, Instant snapshotStarted)
    {
        if (info != null && (info.startedAt() == null || snapshotStarted == null || info.startedAt().isAfter(snapshotStarted)))
            throw new IllegalStateException("Process identity changed during Windows inventory");
    }
}

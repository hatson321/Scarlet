package net.sybyline.scarlet.ext;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Process presence is an observation, never permission to launch or close a client. */
public final class VrcClientPresence
{
    public enum State { PRESENT, ABSENT, UNVERIFIED }

    enum Reason
    {
        INVENTORY_UNAVAILABLE, INVENTORY_LIMIT, INVALID_RECORD, INVALID_PID, DUPLICATE_PID,
        EXECUTABLE_UNAVAILABLE, WINE_IDENTITY_UNVERIFIED, LINUX_IDENTITY_CHANGED,
        PROC_NAME_UNAVAILABLE, PROC_ARGV0_UNAVAILABLE, JAVA_METADATA_UNAVAILABLE,
        WINE_IDENTITY_INCOMPLETE, WINE_NAME_INVALID, WINE_ARGV0_NOT_PROGRAM,
        WINE_NAME_MISMATCH, WINE_PROGRAM_UNRECOGNIZED, WINE_ARGUMENT_CONFLICT,
        ACCESS_DENIED, RUNTIME_UNAVAILABLE, INVENTORY_READ_FAILED;

        String code() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }

    /** Contains only closed reason labels and availability flags; no original exception is retained. */
    static final class Failure extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;
        final Reason reason;
        final String facts;

        Failure(Reason reason, ManagedVrcClient.ProcessInfo process)
        {
            super("Process inventory could not be verified: " + reason.code());
            this.reason = reason;
            this.facts = facts(process);
        }
    }

    public final State state;
    private final List<String> clientPids;
    private final String diagnostic;

    private VrcClientPresence(State state, List<String> clientPids, String diagnostic)
    {
        this.state = state;
        this.clientPids = List.copyOf(clientPids);
        this.diagnostic = diagnostic;
    }

    static VrcClientPresence verified(List<String> clientPids, long nameOnlyClients)
    {
        State state = clientPids.isEmpty() ? State.ABSENT : State.PRESENT;
        return new VrcClientPresence(state, clientPids,
            "state=" + state.name().toLowerCase(Locale.ROOT) + "; reason=inventory-verified; clients="
            + clientPids.size() + "; name-only-clients=" + nameOnlyClients);
    }

    static VrcClientPresence unverified(Throwable failure)
    {
        Reason reason = failure instanceof Failure ? ((Failure)failure).reason
            : failure instanceof SecurityException ? Reason.ACCESS_DENIED
            : failure instanceof LinkageError ? Reason.RUNTIME_UNAVAILABLE : Reason.INVENTORY_READ_FAILED;
        String facts = failure instanceof Failure ? ((Failure)failure).facts : "process-kind=unavailable";
        return new VrcClientPresence(State.UNVERIFIED, List.of(),
            "state=unverified; reason=" + reason.code() + "; " + facts);
    }

    public List<String> clientPids() { return this.clientPids; }
    public String diagnostic() { return this.diagnostic; }

    public String rescanSkipReason()
    {
        return this.state == State.ABSENT ? "no VRChat client was detected"
            : this.state == State.UNVERIFIED ? "VRChat process state could not be verified; see VRChat process diagnostic"
            : "a VRChat client was detected";
    }

    @Override public String toString() { return this.diagnostic; }

    private static String facts(ManagedVrcClient.ProcessInfo process)
    {
        if (process == null) return "process-kind=unavailable";
        String executable = process.executable();
        String name = executable == null ? "" : executable.substring(
            Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        String kind = ManagedVrcClient.isClient(process) ? "client"
            : name.equals("wine") || name.equals("wine64") || name.equals("wine-preloader") || name.equals("wine64-preloader")
                ? "wine-loader" : name.startsWith("wine") ? "wine-related"
                : executable == null || executable.isBlank() ? "unavailable" : "other";
        return "process-kind=" + kind + "; executable-present=" + (executable != null && !executable.isBlank())
            + "; start-present=" + (process.startedAt() != null)
            + "; user-present=" + (process.user() != null && !process.user().isBlank())
            + "; arguments-present=" + !process.arguments().isEmpty()
            + "; parent-present=" + (process.parentPid() > 0L);
    }

    /** At most one diagnostic per minute across changing failures and successful observations. */
    static final class LogGate
    {
        private boolean emitted;
        private long lastAt;
        private String lastDiagnostic;

        synchronized boolean shouldLog(VrcClientPresence presence, long now)
        {
            if (this.emitted && now - this.lastAt < TimeUnit.MINUTES.toNanos(1L)) return false;
            if (this.emitted && presence.state != State.UNVERIFIED && presence.diagnostic.equals(this.lastDiagnostic)) return false;
            this.emitted = true;
            this.lastAt = now;
            this.lastDiagnostic = presence.diagnostic;
            return true;
        }
    }
}

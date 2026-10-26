package net.sybyline.scarlet.ext;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.sybyline.scarlet.ext.ManagedVrcClient.Client;
import net.sybyline.scarlet.ext.ManagedVrcClient.CloseResult;
import net.sybyline.scarlet.ext.ManagedVrcClient.ForceResult;
import net.sybyline.scarlet.ext.ManagedVrcClient.ProcessInfo;

/** Shutdown-only identity receipts. These never establish Scarlet launch ownership. */
public final class SingleVrcClient
{
    public enum Status { READY, NONE, MULTIPLE, UNAVAILABLE, IDENTITY_UNVERIFIED, SESSION_MISMATCH, LAUNCH_BUSY }
    public static final class Selection
    {
        public final Client client;
        public final Status status;
        public Selection(Client client, Status status) { this.client = client; this.status = status; }
    }

    private final ManagedVrcClient.Processes processes;
    private final Clock clock;
    private Client receipt;
    private ProcessInfo identity;
    private long token;

    SingleVrcClient(ManagedVrcClient.Processes processes, Clock clock)
    { this.processes = processes; this.clock = clock; }

    Selection select(String location, Instant joinedAt)
    {
        try
        {
            List<ProcessInfo> clients = clients();
            if (clients.size() > 1) return new Selection(null, Status.MULTIPLE);
            if (clients.isEmpty()) return new Selection(null, Status.NONE);
            ProcessInfo candidate = clients.get(0);
            if (!verified(candidate)) return new Selection(null, Status.IDENTITY_UNVERIFIED);
            if (location == null || location.isBlank() || joinedAt == null
                || joinedAt.isBefore(candidate.startedAt()) || joinedAt.isAfter(this.clock.instant()))
                return new Selection(null, Status.SESSION_MISMATCH);
            if (this.receipt == null || !ManagedVrcClient.sameProcess(this.identity, candidate)
                || !Objects.equals(location, this.receipt.requestedLocation) || !Objects.equals(joinedAt, this.receipt.requestedAt))
            {
                this.identity = candidate;
                this.receipt = new Client(--this.token, location, joinedAt, candidate.pid(), candidate.startedAt(), candidate.user());
            }
            return new Selection(this.receipt, Status.READY);
        }
        catch (RuntimeException | LinkageError ex) { return new Selection(null, Status.UNAVAILABLE); }
    }

    private List<ProcessInfo> clients()
    {
        List<ProcessInfo> snapshot = this.processes.clientSnapshot();
        if (snapshot == null || snapshot.size() > 100_000) throw new IllegalStateException("Client inventory unavailable");
        Set<Long> seen = new HashSet<>();
        java.util.ArrayList<ProcessInfo> clients = new java.util.ArrayList<>();
        for (ProcessInfo process : snapshot)
        {
            if (process == null || process.pid() < 0 || !seen.add(process.pid()) || process.executable() == null
                || process.executable().isBlank()) throw new IllegalStateException("Client inventory incomplete");
            String name = process.executable().replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith("wine") && process.arguments().isEmpty()) throw new IllegalStateException("Wine identity unavailable");
            if (ManagedVrcClient.isClient(process)) clients.add(process);
        }
        return clients;
    }

    private boolean verified(ProcessInfo candidate)
    {
        return candidate.pid() > 0 && candidate.startedAt() != null && !candidate.startedAt().isAfter(this.clock.instant())
            && candidate.user() != null && !candidate.user().isBlank() && candidate.user().equals(this.processes.currentUser())
            && (candidate.executable().contains("/") || candidate.executable().contains("\\"))
            && ManagedVrcClient.sameProcess(candidate, this.processes.find(candidate.pid()));
    }

    private boolean valid(Client expected)
    {
        if (expected == null || expected != this.receipt) return false;
        List<ProcessInfo> clients = clients();
        return clients.size() == 1 && ManagedVrcClient.sameProcess(this.identity, clients.get(0)) && verified(clients.get(0));
    }

    CloseResult close(Client expected, BooleanSupplier allowed)
    {
        if (expected == null || expected != this.receipt) return CloseResult.NOT_MANAGED;
        try
        {
            if (!valid(expected)) return CloseResult.IDENTITY_CHANGED;
            if (allowed == null || !allowed.getAsBoolean()) return CloseResult.FINAL_GUARD_VETO;
            java.util.concurrent.atomic.AtomicReference<CloseResult> veto = new java.util.concurrent.atomic.AtomicReference<>();
            CloseResult result = this.processes.requestCloseDetailed(this.identity, () -> {
                if (!valid(expected)) { veto.set(CloseResult.IDENTITY_CHANGED); return false; }
                if (!allowed.getAsBoolean()) { veto.set(CloseResult.FINAL_GUARD_VETO); return false; }
                return true;
            });
            // An identity change is terminal, not a retryable window/setting veto.
            return result == CloseResult.FINAL_GUARD_VETO && veto.get() != null ? veto.get() : result;
        }
        catch (RuntimeException | LinkageError ex) { return CloseResult.UNSUPPORTED_OR_DENIED; }
    }

    ForceResult force(Client expected, BooleanSupplier allowed)
    {
        if (expected == null || expected != this.receipt) return ForceResult.NOT_MANAGED;
        try
        {
            if (!valid(expected)) return ForceResult.IDENTITY_CHANGED;
            if (allowed == null || !allowed.getAsBoolean()) return ForceResult.GUARD_VETO;
            return this.processes.forceClose(this.identity, () -> valid(expected) && allowed.getAsBoolean());
        }
        catch (RuntimeException | LinkageError ex) { return ForceResult.UNCERTAIN; }
    }
}

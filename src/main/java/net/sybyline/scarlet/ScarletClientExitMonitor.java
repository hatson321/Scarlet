package net.sybyline.scarlet;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.Instance;
import net.sybyline.scarlet.ext.ManagedVrcClient;
import net.sybyline.scarlet.util.Location;

/** Connects independent audit, live-log, server-state and process proofs. No instance writes. */
final class ScarletClientExitMonitor
{
    interface Processes
    {
        ManagedVrcClient.Client current();
        ManagedVrcClient.CloseResult close(ManagedVrcClient.Client expected, BooleanSupplier revalidate);
    }

    private final ScarletClosedInstanceExit policy = new ScarletClosedInstanceExit();
    private final BooleanSupplier enabled, healthy;
    private final Supplier<String> group;
    private final Supplier<Instant> clock;
    private final LongSupplier nanos;
    private final Processes processes;
    private final Function<String, Instance> instances;
    private final Consumer<ManagedVrcClient.CloseResult> results;
    private final AtomicBoolean checking = new AtomicBoolean();
    private final Set<String> roster = new HashSet<>();
    private String location, self;
    private Instant joinedAt;
    private boolean ready;
    private long generation, retryAfter;
    private ManagedVrcClient.Client bound;

    ScarletClientExitMonitor(BooleanSupplier enabled, Supplier<String> group, BooleanSupplier healthy,
        Processes processes, Function<String, Instance> instances, Consumer<ManagedVrcClient.CloseResult> results,
        Supplier<Instant> clock, LongSupplier nanos)
    {
        this.enabled = enabled; this.group = group; this.healthy = healthy;
        this.processes = processes; this.instances = instances; this.results = results;
        this.clock = clock; this.nanos = nanos;
    }

    synchronized void newLog()
    {
        this.left();
        this.self = null;
    }

    synchronized void authenticated(String userId)
    {
        this.self = userId;
        this.refresh();
    }

    synchronized void joined(String location, Instant timestamp, boolean preamble)
    {
        this.policy.configure(this.enabled.getAsBoolean(), this.group.get());
        this.location = location;
        this.joinedAt = timestamp;
        this.roster.clear();
        this.ready = !preamble;
        this.bound = null;
        this.retryAfter = 0L;
        this.generation = this.policy.beginSession(location, timestamp);
        this.refresh();
    }

    synchronized void caughtUp()
    {
        this.ready = true;
        this.refresh();
    }

    synchronized void left()
    {
        this.policy.reset();
        this.generation = this.policy.generation();
        this.location = null; this.joinedAt = null; this.ready = false;
        this.bound = null; this.retryAfter = 0L; this.roster.clear();
    }

    synchronized void playerJoined(String userId)
    {
        this.roster.add(userId);
        this.refresh();
    }

    synchronized void playerLeft(String userId)
    {
        this.roster.remove(userId);
        this.refresh();
    }

    private void refresh()
    {
        this.policy.configure(this.enabled.getAsBoolean(), this.group.get());
        if (this.generation != this.policy.generation())
        {
            this.generation = this.policy.beginSession(this.location, this.joinedAt);
            this.bound = null;
            this.retryAfter = 0L;
        }
        this.policy.updateTracking(this.generation, this.self, this.roster, this.ready);
    }

    /** Called before Discord delivery filtering/deduplication, including harmless audit replays. */
    synchronized void audit(GroupAuditLogEntryUnknown entry)
    {
        if (entry == null || entry.getCreatedAt() == null) return;
        this.refresh();
        if ("group.instance.close".equals(entry.getEventType()))
            this.policy.confirmClosed(entry.getGroupId(), entry.getTargetId(), entry.getCreatedAt().toInstant(), this.clock.get());
        else if ("group.instance.create".equals(entry.getEventType()))
            this.policy.invalidateReopened(entry.getGroupId(), entry.getTargetId(), entry.getCreatedAt().toInstant(), this.clock.get());
    }

    /** Network verification never holds the observation lock; a log event can cancel an in-flight check. */
    void tick()
    {
        if (!this.checking.compareAndSet(false, true)) return;
        try
        {
            final ScarletClosedInstanceExit.Attempt attempt;
            final ManagedVrcClient.Client client;
            synchronized (this)
            {
                this.refresh();
                if (!this.enabled.getAsBoolean()) return;
                client = this.processes.current(); // Also resolves a new Steam/launcher child before discovery expires.
                if (!this.matchesSession(client))
                {
                    this.policy.updateTracking(this.generation, this.self, this.roster, false);
                    return;
                }
                if (!this.healthy.getAsBoolean())
                {
                    // A busy parser is not evidence that the server reopened the instance.
                    // Keep exact-session audit history, but require a new healthy grace period.
                    this.policy.suspendCountdown();
                    return;
                }
                if (this.bound != null && this.bound != client)
                {
                    // A different client cannot inherit closure proof from this observed session.
                    this.left();
                    return;
                }
                this.bound = client;
                long now = this.nanos.getAsLong();
                if (this.retryAfter != 0L && now - this.retryAfter < 0L) return;
                attempt = this.policy.poll(now);
                if (attempt == null) return;
                this.retryAfter = now + TimeUnit.SECONDS.toNanos(60);
            }
            long queryStarted = this.nanos.getAsLong();
            Instance current = this.instances.apply(attempt.location);
            if (!confirmedClosed(current, attempt.location, attempt.selfUserId, attempt.sessionStartedAt, this.clock.get())) return;
            synchronized (this)
            {
                this.refresh();
                long elapsed = this.nanos.getAsLong() - queryStarted;
                if (!this.healthy.getAsBoolean())
                {
                    this.policy.suspendCountdown();
                    return;
                }
                if (elapsed < 0L || elapsed > TimeUnit.SECONDS.toNanos(10) || !this.matchesSession(client)) return;
                this.policy.commit(attempt, this.nanos.getAsLong(), () ->
                {
                    ManagedVrcClient.CloseResult result = this.processes.close(client,
                        () -> this.enabled.getAsBoolean() && this.healthy.getAsBoolean()
                            && Objects.equals(attempt.groupId, this.group.get()) && this.matchesSession(client));
                    this.results.accept(result);
                });
            }
        }
        finally { this.checking.set(false); }
    }

    private boolean matchesSession(ManagedVrcClient.Client client)
    {
        return client != null && this.joinedAt != null && this.location != null
            && this.location.equals(client.requestedLocation) && client.requestedAt != null && client.startedAt != null
            && !this.joinedAt.isBefore(client.requestedAt) && !this.joinedAt.isBefore(client.startedAt)
            && !this.joinedAt.isAfter(this.clock.get());
    }

    /** A scheduled closure, an inactive/404 instance, or a missing timestamp is not proof of closure. */
    static boolean confirmedClosed(Instance instance, String location, String expectedSelf, Instant sessionStarted, Instant now)
    {
        if (instance == null || location == null || expectedSelf == null || sessionStarted == null || now == null
            || !location.equals(instance.getId()) || instance.getClosedAt() == null) return false;
        Location parsed;
        try { parsed = Location.of(location); }
        catch (RuntimeException ex) { return false; }
        Instant closed = instance.getClosedAt().toInstant();
        return parsed.isConcrete() && Objects.equals(parsed.world, instance.getWorldId())
            && Objects.equals(parsed.instance, instance.getInstanceId())
            && !closed.isBefore(sessionStarted) && !closed.isAfter(now)
            && (instance.getnUsers() == null || instance.getnUsers() >= 0 && instance.getnUsers() <= 1)
            && (instance.getUserCount() == null || instance.getUserCount() >= 0 && instance.getUserCount() <= 1)
            && (instance.getUsers() == null || instance.getUsers().size() <= 1
                && instance.getUsers().stream().allMatch(user -> user != null && expectedSelf.equals(user.getId())));
    }
}

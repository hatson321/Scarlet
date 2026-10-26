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
import net.sybyline.scarlet.ext.SingleVrcClient;
import net.sybyline.scarlet.util.Location;

/** Connects closure audit, live-log and process proofs. No instance writes. */
final class ScarletClientExitMonitor
{
    // Production client shutdown explicitly selects LIVE_LOG_ROSTER. Retain the
    // stricter policy for regression coverage without silently querying as a fallback.
    enum EvidenceMode { API_CONFIRMED, LIVE_LOG_ROSTER }
    private final EvidenceMode evidenceMode;
    interface Processes
    {
        ManagedVrcClient.Client current();
        default SingleVrcClient.Selection select(String location, Instant joinedAt)
        {
            ManagedVrcClient.Client client = this.current();
            return new SingleVrcClient.Selection(client, client == null ? SingleVrcClient.Status.UNAVAILABLE : SingleVrcClient.Status.READY);
        }
        ManagedVrcClient.CloseResult close(ManagedVrcClient.Client expected, BooleanSupplier revalidate);
        default ManagedVrcClient.ForceResult force(ManagedVrcClient.Client expected, BooleanSupplier revalidate)
        { return ManagedVrcClient.ForceResult.UNSUPPORTED; }
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
    private int occupancyRetries;
    private int closeRetries;
    private boolean closeTerminal;
    private BooleanSupplier forceEnabled = () -> false;
    private ForcePending forcePending;
    private static final class ForcePending
    {
        final ScarletClosedInstanceExit.Attempt attempt;
        final ManagedVrcClient.Client client;
        final long scheduled;
        ForcePending(ScarletClosedInstanceExit.Attempt attempt, ManagedVrcClient.Client client, long scheduled)
        { this.attempt = attempt; this.client = client; this.scheduled = scheduled; }
    }
    synchronized void setForceCloseEnabled(BooleanSupplier enabled) { this.forceEnabled = enabled; }
    private ManagedVrcClient.Client bound;
    private final Consumer<String> diagnostics;
    private final Runnable awaitTracking;
    private final Supplier<String> trackingFailure;
    private final ScarletExitEscalation escalation = new ScarletExitEscalation();
    private Consumer<ScarletExitEscalation.Notice> operatorAlerts = notice -> {};
    private Runnable multipleClientAlert = () -> {};
    private Long multipleClientAlertAt;
    synchronized void setMultipleClientAlert(Runnable alert) { this.multipleClientAlert = alert; }
    private final java.util.Map<String, Long> diagnosticTimes = new java.util.HashMap<>();

    ScarletClientExitMonitor(BooleanSupplier enabled, Supplier<String> group, BooleanSupplier healthy,
        Processes processes, Function<String, Instance> instances, Consumer<ManagedVrcClient.CloseResult> results,
        Supplier<Instant> clock, LongSupplier nanos)
    {
        this(enabled, group, healthy, processes, instances, results, clock, nanos,
            reason -> Scarlet.LOG.info("Automatic VRChat exit diagnostic: {}", reason));
    }

    ScarletClientExitMonitor(BooleanSupplier enabled, Supplier<String> group, BooleanSupplier healthy,
        Processes processes, Function<String, Instance> instances, Consumer<ManagedVrcClient.CloseResult> results,
        Supplier<Instant> clock, LongSupplier nanos, Consumer<String> diagnostics)
    {
        this(enabled, group, healthy, processes, instances, results, clock, nanos, diagnostics, () -> {});
    }

    ScarletClientExitMonitor(BooleanSupplier enabled, Supplier<String> group, BooleanSupplier healthy,
        Processes processes, Function<String, Instance> instances, Consumer<ManagedVrcClient.CloseResult> results,
        Supplier<Instant> clock, LongSupplier nanos, Runnable awaitTracking)
    {
        this(enabled, group, healthy, processes, instances, results, clock, nanos,
            reason -> Scarlet.LOG.info("Automatic VRChat exit diagnostic: {}", reason), awaitTracking);
    }

    ScarletClientExitMonitor(BooleanSupplier enabled, Supplier<String> group, BooleanSupplier healthy,
        Processes processes, Function<String, Instance> instances, Consumer<ManagedVrcClient.CloseResult> results,
        Supplier<Instant> clock, LongSupplier nanos, Consumer<String> diagnostics, Runnable awaitTracking)
    {
        this(enabled, group, healthy, processes, instances, results, clock, nanos, diagnostics, awaitTracking,
            () -> "tracking-details-unavailable");
    }

    ScarletClientExitMonitor(BooleanSupplier enabled, Supplier<String> group, BooleanSupplier healthy,
        Processes processes, Function<String, Instance> instances, Consumer<ManagedVrcClient.CloseResult> results,
        Supplier<Instant> clock, LongSupplier nanos, Consumer<String> diagnostics, Runnable awaitTracking,
        Supplier<String> trackingFailure)
    {
        this(enabled, group, healthy, processes, instances, results, clock, nanos, diagnostics, awaitTracking,
            trackingFailure, EvidenceMode.API_CONFIRMED);
    }

    ScarletClientExitMonitor(BooleanSupplier enabled, Supplier<String> group, BooleanSupplier healthy,
        Processes processes, Function<String, Instance> instances, Consumer<ManagedVrcClient.CloseResult> results,
        Supplier<Instant> clock, LongSupplier nanos, Consumer<String> diagnostics, Runnable awaitTracking,
        Supplier<String> trackingFailure, EvidenceMode evidenceMode)
    {
        this.evidenceMode = Objects.requireNonNull(evidenceMode);
        this.enabled = enabled; this.group = group; this.healthy = healthy;
        this.processes = processes; this.instances = instances; this.results = results;
        this.clock = clock; this.nanos = nanos;
        this.diagnostics = diagnostics;
        this.awaitTracking = awaitTracking;
        this.trackingFailure = trackingFailure;
    }

    /** Fixed reason codes only: never log a location, account ID or access key. */
    private synchronized void diagnostic(String reason)
    {
        long now = this.nanos.getAsLong();
        Long previous = this.diagnosticTimes.get(reason);
        if (previous != null && now - previous >= 0 && now - previous < TimeUnit.SECONDS.toNanos(60)) return;
        this.diagnosticTimes.put(reason, now);
        // Observability must never grant permission, prevent revalidation or break the monitor.
        try { this.diagnostics.accept(reason); }
        catch (RuntimeException ignored) { }
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
        this.occupancyRetries = 0;
        this.closeRetries = 0; this.closeTerminal = false;
        this.forcePending = null;
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
        this.occupancyRetries = 0;
        this.closeRetries = 0; this.closeTerminal = false;
        this.forcePending = null;
        this.escalation.observe(this.generation, this.self, this.roster, false, this.nanos.getAsLong());
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
            this.occupancyRetries = 0;
            this.closeRetries = 0; this.closeTerminal = false;
            this.forcePending = null;
        }
        this.policy.updateTracking(this.generation, this.self, this.roster, this.ready);
        this.escalation.observe(this.generation, this.self, this.roster, this.ready, this.nanos.getAsLong());
    }

    /** Called before Discord delivery filtering/deduplication, including harmless audit replays. */
    synchronized void audit(GroupAuditLogEntryUnknown entry)
    {
        if (entry == null) return;
        if (entry.getCreatedAt() == null)
        {
            if ("group.instance.close".equals(entry.getEventType())) this.diagnostic("close-audit-missing-timestamp");
            return;
        }
        this.refresh();
        if ("group.instance.close".equals(entry.getEventType()))
            this.diagnostic(this.policy.confirmClosed(entry.getGroupId(), entry.getTargetId(), entry.getCreatedAt().toInstant(), this.clock.get())
                ? "matching-close-audit-accepted" : "close-audit-rejected-session-group-time-or-roster");
        else if ("group.instance.create".equals(entry.getEventType()))
            this.policy.invalidateReopened(entry.getGroupId(), entry.getTargetId(), entry.getCreatedAt().toInstant(), this.clock.get());
        if (!"waiting-for-30-second-grace".equals(this.policy.waitingReason())) this.escalation.revoke();
    }

    synchronized void setOperatorAlerts(Consumer<ScarletExitEscalation.Notice> alerts) { this.operatorAlerts = alerts; }

    synchronized boolean operatorNoticeValid(String token)
    {
        this.refresh();
        return this.enabled.getAsBoolean() && "waiting-for-30-second-grace".equals(this.policy.waitingReason())
            && this.escalation.valid(token, this.generation, this.bound, this.nanos.getAsLong());
    }

    private static final class Override
    {
        final String token;
        final BooleanSupplier authorized;
        final Runnable refreshAuthorization;
        long generation, revision;
        ManagedVrcClient.Client client;
        String result = "No exit requested: the request is busy, expired, unauthorized, or a safety check is not satisfied.";
        Override(String token, BooleanSupplier authorized, Runnable refreshAuthorization)
        { this.token = token; this.authorized = authorized; this.refreshAuthorization = refreshAuthorization; }
    }

    String operatorExit(String token, BooleanSupplier authorized)
    {
        return this.operatorExit(token, authorized, () -> {});
    }

    String operatorExit(String token, BooleanSupplier authorized, Runnable refreshAuthorization)
    {
        Override request = new Override(token, authorized, refreshAuthorization);
        this.tick(request);
        return request.result;
    }

    private boolean overrideValid(Override request, ManagedVrcClient.Client client)
    {
        return request != null && request.authorized.getAsBoolean() && request.client == client
            && request.generation == this.generation
            && this.escalation.sameObservation(request.revision, this.generation, client, this.nanos.getAsLong());
    }

    /** Network verification never holds the observation lock; a log event can cancel an in-flight check. */
    void tick()
    {
        this.tick(null);
    }

    private void tick(Override override)
    {
        if (override != null && this.evidenceMode == EvidenceMode.LIVE_LOG_ROSTER)
        {
            override.result = "No manual override applied: automatic client exit uses the live output-log roster; server occupancy is not a prerequisite.";
            return;
        }
        if (!this.checking.compareAndSet(false, true)) return;
        try
        {
            if (override == null && this.tickForceFallback()) return;
            final long observedGeneration;
            final String observedLocation;
            final Instant observedJoinedAt;
            synchronized (this)
            {
                this.refresh();
                if (!this.enabled.getAsBoolean()) { this.diagnostic("disabled-setting-or-operating-mode"); return; }
                observedGeneration = this.generation;
                observedLocation = this.location;
                observedJoinedAt = this.joinedAt;
                if (override != null)
                {
                    if (!override.authorized.getAsBoolean() || !this.operatorNoticeValid(override.token)) return;
                    override.generation = this.generation; override.client = this.bound;
                    override.revision = this.escalation.consume(override.token, this.generation, this.bound, this.nanos.getAsLong());
                    if (override.revision < 0) return;
                }
            }
            // Native process inventory may be slow. Run it BEFORE catch-up and outside
            // the observation lock, so parser callbacks cannot be held up by inventory.
            final SingleVrcClient.Selection selection = this.processes.select(observedLocation, observedJoinedAt);
            final ManagedVrcClient.Client client = selection.client;
            if (this.enabled.getAsBoolean()) this.awaitTracking.run();
            if (Thread.currentThread().isInterrupted()) return;
            if (selection.status == SingleVrcClient.Status.MULTIPLE) this.offerMultipleClientAlert(observedGeneration);
            if (client == null) this.diagnostic("shutdown-client-" + selection.status.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
            final ScarletClosedInstanceExit.Attempt attempt;
            synchronized (this)
            {
                this.refresh();
                if (!this.enabled.getAsBoolean()) { this.diagnostic("disabled-setting-or-operating-mode"); return; }
                if (observedGeneration != this.generation) return;
                if (override != null && !this.overrideValid(override, client)) return;
                if (!this.matchesSession(client))
                {
                    this.diagnostic(client == null ? "no-verified-shutdown-client" : "shutdown-client-session-location-or-time-mismatch");
                    if (client == null && this.bound == null)
                    {
                        // Initial process discovery is not a broken log session. Keep
                        // dated, exact-session audit history, but start no grace period
                        // or server query until a verified matching process is bound.
                        this.policy.suspendCountdown();
                    }
                    else
                    {
                        // Losing a bound process or observing a different launch is
                        // uncertain identity, not delayed initial discovery.
                        this.policy.updateTracking(this.generation, this.self, this.roster, false);
                    }
                    return;
                }
                if (!this.healthy.getAsBoolean())
                {
                    // A busy parser is not evidence that the server reopened the instance.
                    // Keep exact-session audit history, but require a new healthy grace period.
                    this.policy.suspendCountdown();
                    this.diagnostic("log-tracking-not-healthy");
                    this.explainTrackingFailure();
                    return;
                }
                if (this.bound != null && this.bound != client)
                {
                    // A different client cannot inherit closure proof from this observed session.
                    this.left();
                    this.diagnostic("managed-process-identity-changed");
                    return;
                }
                this.bound = client;
                if (this.closeTerminal) { this.diagnostic("exit-already-attempted-no-repeat"); return; }
                long now = this.nanos.getAsLong();
                if (override == null && this.retryAfter != 0L && now - this.retryAfter < 0L) { this.diagnostic("server-check-retry-backoff"); return; }
                attempt = this.policy.poll(now);
                if (attempt == null) { this.diagnostic(this.policy.waitingReason()); return; }
                if (this.evidenceMode == EvidenceMode.API_CONFIRMED)
                    this.retryAfter = now + TimeUnit.SECONDS.toNanos(60);
            }
            long queryStarted = this.nanos.getAsLong();
            if (this.evidenceMode == EvidenceMode.API_CONFIRMED)
            {
                Instance current;
                try { current = this.instances.apply(attempt.location); }
                catch (RuntimeException ex)
                {
                    this.scheduleServerRetry(attempt, java.util.Collections.emptySet(), queryStarted);
                    throw ex;
                }
                Instant checkedAt = this.clock.get();
                if (!confirmedClosed(current, attempt.location, attempt.selfUserId, attempt.sessionStartedAt, checkedAt))
                {
                    this.diagnostic(current == null ? "server-instance-response-unavailable" : "server-closure-location-time-or-occupancy-unconfirmed");
                    Set<String> failures = this.explainServerRejection(current, attempt, checkedAt);
                    if (override == null || !occupancyOnly(failures) || !closedIdentity(current, attempt, checkedAt))
                    {
                        this.scheduleServerRetry(attempt, failures, queryStarted);
                        if (override == null && occupancyOnly(failures)) this.offerOperatorNotice(attempt, client, current, queryStarted);
                        return;
                    }
                }
            }
            // A second permission fetch must not hold the roster lock or starve
            // player observations. Its time counts against server-proof freshness.
            if (override != null) override.refreshAuthorization.run();
            this.awaitTracking.run();
            if (Thread.currentThread().isInterrupted()) return;
            synchronized (this)
            {
                this.refresh();
                if (override != null && !this.overrideValid(override, client)) return;
                long elapsed = this.nanos.getAsLong() - queryStarted;
                if (!this.healthy.getAsBoolean())
                {
                    this.policy.suspendCountdown();
                    this.diagnostic(this.evidenceMode == EvidenceMode.API_CONFIRMED
                        ? "log-tracking-lost-during-server-check" : "log-tracking-lost-during-final-check");
                    this.explainTrackingFailure();
                    return;
                }
                if (elapsed < 0L || elapsed > TimeUnit.SECONDS.toNanos(10) || !this.matchesSession(client))
                { this.diagnostic(this.evidenceMode == EvidenceMode.API_CONFIRMED
                    ? "server-response-stale-or-session-changed" : "log-observation-stale-or-session-changed"); return; }
                boolean committed = this.policy.commit(attempt, this.nanos.getAsLong(), () ->
                {
                    if (this.evidenceMode == EvidenceMode.LIVE_LOG_ROSTER)
                        this.diagnostic("exit-evidence-live-output-log-roster-no-server-occupancy-query");
                    // Terminal until a typed, definitely-unsent result explicitly rearms it.
                    // Exceptions or a lost result cannot silently grant a retry.
                    this.closeTerminal = true;
                    ManagedVrcClient.CloseResult result;
                    try { result = this.processes.close(client, () -> this.finalCloseGuard(attempt, client, override, queryStarted)); }
                    catch (RuntimeException | LinkageError ex) { result = ManagedVrcClient.CloseResult.DISPATCH_UNCERTAIN; }
                    if (result == null) result = ManagedVrcClient.CloseResult.DISPATCH_UNCERTAIN;
                    this.diagnostic("close-stage-" + result.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
                    if (override == null && result.retryableBeforeDispatch())
                    {
                        if (this.closeRetries < 2 && this.policy.retryUnsent(attempt, result))
                        {
                            ++this.closeRetries;
                            this.closeTerminal = false; this.retryAfter = 0L;
                            this.diagnostic("close-retry-" + this.closeRetries + "-of-2-requires-fresh-grace-and-"
                                + (this.evidenceMode == EvidenceMode.API_CONFIRMED ? "server-proof" : "log-proof"));
                        }
                        else this.diagnostic("close-retry-budget-exhausted-or-observation-changed");
                    }
                    if (override != null) override.result = result == ManagedVrcClient.CloseResult.REQUESTED
                        ? "Normal exit requested for Scarlet's VRChat client. Only the server occupancy disagreement was overridden."
                        : "No normal exit requested: " + result + ". No force-close fallback was used.";
                    if (override == null && this.closeTerminal && this.forceEnabled.getAsBoolean() && forceEligible(result))
                    {
                        this.forcePending = new ForcePending(attempt, client, this.nanos.getAsLong());
                        this.diagnostic("force-fallback-waiting-15-seconds-before-fresh-checks");
                    }
                    this.results.accept(result);
                });
                this.diagnostic(committed ? "exit-attempt-completed-see-result" : "exit-cancelled-by-final-policy-revalidation");
            }
        }
        finally { this.checking.set(false); }
    }

    private static boolean forceEligible(ManagedVrcClient.CloseResult result)
    {
        return result == ManagedVrcClient.CloseResult.REQUESTED || result == ManagedVrcClient.CloseResult.WINDOW_NOT_FOUND
            || result == ManagedVrcClient.CloseResult.WINDOW_AMBIGUOUS || result == ManagedVrcClient.CloseResult.WINDOWS_ACCESS_DENIED
            || result == ManagedVrcClient.CloseResult.WINDOWS_POST_REJECTED;
    }

    private boolean forceGuard(ForcePending pending, long queryStarted)
    {
        return !Thread.currentThread().isInterrupted() && this.forceEnabled.getAsBoolean() && this.policy.committedObservation(pending.attempt, this.nanos.getAsLong())
            && this.bound == pending.client && this.finalCloseGuard(pending.attempt, pending.client, null, queryStarted);
    }

    /** One asynchronous follow-up; never wait for shutdown while holding the roster lock. */
    private boolean tickForceFallback()
    {
        final ForcePending pending;
        synchronized (this)
        {
            pending = this.forcePending;
            if (pending == null) return false;
            long elapsed = this.nanos.getAsLong() - pending.scheduled;
            if (!this.forceEnabled.getAsBoolean() || !this.enabled.getAsBoolean() || elapsed < 0 || elapsed > TimeUnit.SECONDS.toNanos(75))
            { this.forcePending = null; this.diagnostic("force-fallback-cancelled-or-expired"); return true; }
            if (elapsed < TimeUnit.SECONDS.toNanos(15)) return true;
            this.forcePending = null; // One fresh verification/force opportunity; no replay after uncertainty.
        }
        try
        {
            SingleVrcClient.Selection selection = this.processes.select(pending.attempt.location, pending.attempt.sessionStartedAt);
            ManagedVrcClient.Client current = selection.client;
            this.awaitTracking.run();
            if (selection.status == SingleVrcClient.Status.MULTIPLE) this.offerMultipleClientAlert(pending.attempt.generation);
            long queryStarted = this.nanos.getAsLong();
            synchronized (this)
            {
                this.refresh();
                if (current != pending.client || !this.forceGuard(pending, queryStarted))
                { this.diagnostic("force-fallback-cancelled-process-or-observation-changed"); return true; }
            }
            Instance response = this.evidenceMode == EvidenceMode.API_CONFIRMED ? this.instances.apply(pending.attempt.location) : null;
            this.awaitTracking.run();
            synchronized (this)
            {
                this.refresh();
                if ((this.evidenceMode == EvidenceMode.API_CONFIRMED
                        && !confirmedClosed(response, pending.attempt.location, pending.attempt.selfUserId, pending.attempt.sessionStartedAt, this.clock.get()))
                    || !this.forceGuard(pending, queryStarted))
                { this.diagnostic(this.evidenceMode == EvidenceMode.API_CONFIRMED
                    ? "force-fallback-cancelled-fresh-server-or-tracking-check" : "force-fallback-cancelled-fresh-tracking-check"); return true; }
                ManagedVrcClient.ForceResult result = this.processes.force(pending.client, () -> this.forceGuard(pending, queryStarted));
                this.diagnostic("force-fallback-result-" + (result == null ? "uncertain" : result.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
            }
        }
        catch (RuntimeException | LinkageError ex) { this.diagnostic("force-fallback-uncertain-no-repeat"); }
        return true;
    }

    private boolean finalCloseGuard(ScarletClosedInstanceExit.Attempt attempt, ManagedVrcClient.Client client, Override override, long queryStarted)
    {
        long age = this.nanos.getAsLong() - queryStarted;
        String failure = !this.enabled.getAsBoolean() ? "close-guard-disabled-setting-or-mode"
            : !Objects.equals(attempt.groupId, this.group.get()) ? "close-guard-group-changed"
            : !this.matchesSession(client) ? "close-guard-session-changed"
            : override != null && !this.overrideValid(override, client) ? "close-guard-operator-authorization-changed"
            : age < 0L || age > TimeUnit.SECONDS.toNanos(10) ? (this.evidenceMode == EvidenceMode.API_CONFIRMED
                ? "close-guard-server-proof-stale" : "close-guard-log-observation-stale")
            : !this.healthy.getAsBoolean() ? "close-guard-log-tracking-not-healthy"
            : !this.policy.committedObservation(attempt, this.nanos.getAsLong()) ? "close-guard-roster-or-closure-observation-changed" : null;
        if (failure == null) return true;
        this.diagnostic(failure);
        if (failure.equals("close-guard-log-tracking-not-healthy")) this.explainTrackingFailure();
        return false;
    }

    private void offerMultipleClientAlert(long observedGeneration)
    {
        Runnable alert;
        synchronized (this)
        {
            this.refresh();
            String reason = this.policy.waitingReason();
            if (observedGeneration != this.generation || !this.enabled.getAsBoolean() || !this.healthy.getAsBoolean()
                || !("waiting-for-30-second-grace".equals(reason) || "exit-already-attempted-no-repeat".equals(reason))) return;
            long now = this.nanos.getAsLong();
            if (this.multipleClientAlertAt != null && now - this.multipleClientAlertAt >= 0
                && now - this.multipleClientAlertAt < TimeUnit.MINUTES.toNanos(5)) return;
            this.multipleClientAlertAt = now;
            alert = this.multipleClientAlert;
        }
        // Delivery is best effort and outside the observation lock. No close button:
        // an operator cannot disambiguate a process using a stale Discord message.
        try { alert.run(); }
        catch (RuntimeException ex) { this.diagnostic("multiple-client-ops-alert-unavailable"); }
    }

    private void offerOperatorNotice(ScarletClosedInstanceExit.Attempt attempt, ManagedVrcClient.Client client, Instance current, long started)
    {
        this.awaitTracking.run();
        ScarletExitEscalation.Notice notice;
        synchronized (this)
        {
            this.refresh();
            long elapsed = this.nanos.getAsLong() - started;
            if (!this.healthy.getAsBoolean())
            {
                this.policy.suspendCountdown();
                this.diagnostic("log-tracking-lost-during-server-check");
                this.explainTrackingFailure();
                return;
            }
            ScarletClosedInstanceExit.Attempt fresh = this.policy.poll(this.nanos.getAsLong());
            if (Thread.currentThread().isInterrupted() || !this.enabled.getAsBoolean() || !this.healthy.getAsBoolean()
                || elapsed < 0 || elapsed > TimeUnit.SECONDS.toNanos(10) || !this.matchesSession(client) || this.bound != client
                || fresh == null || fresh.generation != attempt.generation || fresh.revision != attempt.revision) return;
            notice = this.escalation.offer(client, "n_users=" + current.getnUsers() + ", userCount=" + current.getUserCount()
                + ", listed users=" + (current.getUsers() == null ? "not supplied" : current.getUsers().size()), this.nanos.getAsLong());
        }
        if (notice != null) try { this.operatorAlerts.accept(notice); }
        catch (RuntimeException ex) { this.diagnostic("operator-alert-delivery-unavailable"); }
    }

    private static boolean occupancyOnly(Set<String> failures)
    {
        return !failures.isEmpty() && failures.stream().allMatch(reason -> reason.equals("server-check-nUsers-above-one")
            || reason.equals("server-check-userCount-above-one") || reason.equals("server-check-users-more-than-one")
            || reason.equals("server-check-users-not-self"));
    }

    /** Independent closure proof: diagnostic classification alone cannot grant an override. */
    private static boolean closedIdentity(Instance current, ScarletClosedInstanceExit.Attempt attempt, Instant checkedAt)
    {
        if (current == null) return false;
        // This separate object intentionally carries no occupancy claim. The original
        // response is never changed, and the automatic-exit predicate stays unchanged.
        Instance identity = new Instance().id(current.getId()).worldId(current.getWorldId())
            .instanceId(current.getInstanceId()).closedAt(current.getClosedAt());
        return confirmedClosed(identity, attempt.location, attempt.selfUserId, attempt.sessionStartedAt, checkedAt);
    }

    private boolean matchesSession(ManagedVrcClient.Client client)
    {
        return client != null && this.joinedAt != null && this.location != null
            && this.location.equals(client.requestedLocation) && client.requestedAt != null && client.startedAt != null
            && !this.joinedAt.isBefore(client.requestedAt) && !this.joinedAt.isBefore(client.startedAt)
            && !this.joinedAt.isAfter(this.clock.get());
    }

    private void explainTrackingFailure()
    {
        try { this.diagnostic(this.trackingFailure.get()); }
        catch (RuntimeException ex) { this.diagnostic("tracking-details-unavailable"); }
    }

    /** Recheck sooner only for a fresh occupancy-only rejection, never permission to close.
     * Two accelerated retries per session (15s, 30s), then 60s. Errors consume that allowance.
     * API-wide rate limiting still applies. Measure backoff from completion, not query start. */
    private synchronized void scheduleServerRetry(ScarletClosedInstanceExit.Attempt attempt, Set<String> failures, long started)
    {
        if (this.generation != attempt.generation) return;
        long now = this.nanos.getAsLong(), elapsed = now - started;
        boolean occupancyOnly = elapsed >= 0L && elapsed <= TimeUnit.SECONDS.toNanos(10) && occupancyOnly(failures);
        int seconds = 60;
        if (occupancyOnly && this.occupancyRetries < 2)
            seconds = this.occupancyRetries++ == 0 ? 15 : 30;
        else this.occupancyRetries = 2;
        this.retryAfter = now + TimeUnit.SECONDS.toNanos(seconds);
        if (seconds < 60) this.diagnostic("server-occupancy-retry-in-" + seconds + "-seconds");
    }

    /** Explanations cannot authorize an exit; failure to explain retains the conservative retry. */
    private Set<String> explainServerRejection(Instance instance, ScarletClosedInstanceExit.Attempt attempt, Instant now)
    {
        try
        {
            Set<String> failures = serverCheckFailures(instance, attempt.location, attempt.selfUserId, attempt.sessionStartedAt, now);
            if (failures.isEmpty()) this.diagnostic("server-check-details-unavailable");
            for (String reason : failures) this.diagnostic(reason);
            return failures;
        }
        catch (RuntimeException ex)
        {
            // No exception message/body: a malformed response may contain private data.
            this.diagnostic("server-check-details-unavailable");
            return java.util.Collections.emptySet();
        }
    }

    /** Fixed, bounded reason codes only. Mirrors confirmedClosed without changing its decision. */
    static Set<String> serverCheckFailures(Instance instance, String location, String expectedSelf, Instant sessionStarted, Instant now)
    {
        Set<String> failures = new java.util.LinkedHashSet<>();
        if (location == null) failures.add("server-check-expected-location-missing");
        if (expectedSelf == null) failures.add("server-check-expected-self-missing");
        if (sessionStarted == null) failures.add("server-check-session-start-missing");
        if (now == null) failures.add("server-check-current-time-missing");
        if (instance == null)
        {
            failures.add("server-check-response-unavailable");
            return failures;
        }
        if (instance.getId() == null) failures.add("server-check-id-missing");
        else if (location != null && !location.equals(instance.getId())) failures.add("server-check-id-mismatch");
        Location parsed = null;
        if (location != null) try { parsed = Location.of(location); }
        catch (RuntimeException ex) { /* Report a fixed code below, never the supplied location. */ }
        if (location != null && (parsed == null || !parsed.isConcrete())) failures.add("server-check-expected-location-invalid");
        if (instance.getWorldId() == null) failures.add("server-check-worldId-missing");
        else if (parsed != null && !Objects.equals(parsed.world, instance.getWorldId())) failures.add("server-check-worldId-mismatch");
        if (instance.getInstanceId() == null) failures.add("server-check-instanceId-missing");
        else if (parsed != null && !Objects.equals(parsed.instance, instance.getInstanceId())) failures.add("server-check-instanceId-mismatch");
        if (instance.getClosedAt() == null) failures.add("server-check-closedAt-missing");
        else
        {
            Instant closed = instance.getClosedAt().toInstant();
            if (sessionStarted != null && closed.isBefore(sessionStarted)) failures.add("server-check-closedAt-before-session");
            if (now != null && closed.isAfter(now)) failures.add("server-check-closedAt-in-future");
        }
        if (instance.getnUsers() != null)
        {
            if (instance.getnUsers() < 0) failures.add("server-check-nUsers-negative");
            else if (instance.getnUsers() > 1) failures.add("server-check-nUsers-above-one");
        }
        if (instance.getUserCount() != null)
        {
            if (instance.getUserCount() < 0) failures.add("server-check-userCount-negative");
            else if (instance.getUserCount() > 1) failures.add("server-check-userCount-above-one");
        }
        if (instance.getUsers() != null)
        {
            // A larger list already fails occupancy; do not traverse arbitrary response lists.
            if (instance.getUsers().size() > 1) failures.add("server-check-users-more-than-one");
            else if (!instance.getUsers().isEmpty())
            {
                io.github.vrchatapi.model.LimitedUserInstance user = instance.getUsers().get(0);
                if (user == null) failures.add("server-check-users-null-entry");
                else if (user.getId() == null) failures.add("server-check-users-id-missing");
                else if (expectedSelf != null && !expectedSelf.equals(user.getId())) failures.add("server-check-users-not-self");
            }
        }
        return failures;
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

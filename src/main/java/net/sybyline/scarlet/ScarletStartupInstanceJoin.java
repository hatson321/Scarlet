package net.sybyline.scarlet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import net.sybyline.scarlet.util.Location;

/** Bounded startup recovery. Network and launch operations run only through injected callbacks. */
final class ScarletStartupInstanceJoin
{
    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 5;
    static final int MAX_ATTEMPTS = 3;
    static final long RETRY_MILLIS = 60_000L;
    static final int LOOKBACK_DAYS = 30;

    interface Environment
    {
        boolean allowed();
        boolean clientRunning() throws Exception;
        List<String> activeLocations() throws Exception;
        AuditPage creationPage(OffsetDateTime from, OffsetDateTime to, int offset) throws Exception;
        void launch(String location) throws Exception;
        void log(String message);
        default long nowMillis() { return System.currentTimeMillis(); }
    }

    static final class AuditPage
    {
        final List<GroupAuditLogEntryUnknown> entries;
        final boolean hasNext;

        AuditPage(List<GroupAuditLogEntryUnknown> entries, boolean hasNext)
        {
            this.entries = entries;
            this.hasNext = hasNext;
        }
    }

    private final String groupId;
    private boolean pending = true;
    private boolean running;
    private int attempts;
    private long nextAttempt;
    private OffsetDateTime auditCutoff;

    ScarletStartupInstanceJoin(String groupId, OffsetDateTime startedAt)
    {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.auditCutoff = Objects.requireNonNull(startedAt, "startedAt");
    }

    synchronized boolean isPending() { return this.pending; }

    synchronized boolean isDue(long now)
    {
        return this.pending && !this.running && now >= this.nextAttempt;
    }

    synchronized void cancel() { this.pending = false; }

    /** Audit delivery continues, but catch-up events must never redirect the startup destination. */
    synchronized boolean shouldFollowCreationAudit(OffsetDateTime createdAt)
    {
        return !this.pending && this.isAfterCutoff(createdAt);
    }

    synchronized boolean isAfterCutoff(OffsetDateTime createdAt)
    {
        return createdAt != null && createdAt.isAfter(this.auditCutoff);
    }

    void attempt(Environment environment, long now)
    {
        final OffsetDateTime snapshotTo = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        synchronized (this)
        {
            if (!this.isDue(now)) return;
            this.running = true;
            this.attempts++;
            if (snapshotTo.isAfter(this.auditCutoff)) this.auditCutoff = snapshotTo;
        }
        String stage = "checking the VRChat client state";
        try
        {
            this.checkAllowed(environment);
            if (environment.clientRunning())
            {
                this.finish(environment, "Startup instance check skipped: VRChat is already running or starting; it was left unchanged.");
                return;
            }
            this.checkAllowed(environment);
            stage = "reading active group instances";
            Set<String> active = this.validateLocations(environment.activeLocations());
            this.checkAllowed(environment);
            if (active.isEmpty())
            {
                this.finish(environment, "Startup instance check complete: no active group instance was found.");
                return;
            }
            stage = "checking instance creation history";
            String selected = active.size() == 1 ? active.iterator().next()
                : this.newest(environment, active, snapshotTo);
            this.checkAllowed(environment);
            stage = "rechecking active group instances";
            Set<String> rechecked = this.validateLocations(environment.activeLocations());
            this.checkAllowed(environment);
            if (!active.equals(rechecked))
                throw new Retry("The active group instances changed during the startup check.");
            stage = "rechecking the VRChat client state";
            if (environment.clientRunning())
            {
                this.finish(environment, "Startup instance check skipped: VRChat started during the check; it was left unchanged.");
                return;
            }
            this.checkAllowed(environment);
            environment.log("Startup instance check complete: requesting one safe launch into the newest active group instance.");
            stage = "preparing the secure join and launch";
            environment.launch(selected);
            // This records a launch attempt, not proof that the client joined successfully.
            synchronized (this) { this.pending = false; }
        }
        catch (Cancelled ex)
        {
            this.cancel();
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            this.cancel();
            environment.log("Startup instance check cancelled.");
        }
        catch (Exception ex)
        {
            boolean exhausted;
            synchronized (this)
            {
                if (!this.pending) return;
                exhausted = this.attempts >= MAX_ATTEMPTS;
                if (exhausted) this.pending = false;
                long finishedAt = Math.max(now, environment.nowMillis());
                this.nextAttempt = finishedAt > Long.MAX_VALUE - RETRY_MILLIS ? Long.MAX_VALUE : finishedAt + RETRY_MILLIS;
            }
            // Do not include network exception bodies, join tokens or locations in this status.
            String reason = ex instanceof Retry ? ex.getMessage() : ScarletStartupFailure.describe(ex);
            environment.log("Startup auto-join failed while " + stage + ": " + reason + (exhausted
                ? " Startup auto-join was skipped after three attempts; join manually if needed."
                : " Startup auto-join will retry no sooner than one minute later."));
        }
        finally
        {
            synchronized (this) { this.running = false; }
        }
    }

    private void finish(Environment environment, String message)
    {
        synchronized (this) { this.pending = false; }
        environment.log(message);
    }

    private void checkAllowed(Environment environment) throws Cancelled, InterruptedException
    {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!this.isPending() || !environment.allowed()) throw new Cancelled();
    }

    private Set<String> validateLocations(List<String> locations) throws Retry
    {
        if (locations == null) throw new Retry("The active group instance list was unavailable.");
        Set<String> result = new TreeSet<>();
        for (String location : locations)
        {
            try
            {
                parseLocation(location, this.groupId);
            }
            catch (IllegalArgumentException ex)
            {
                throw new Retry("The active group instance list contained an invalid or different-group location.");
            }
            result.add(location);
        }
        return result;
    }

    /** The same raw-location boundary applies to selection and the secure-name request. */
    static Location parseLocation(String location, String group)
    {
        if (location == null || !location.startsWith("wrld_") || !location.equals(location.trim()))
            throw new IllegalArgumentException();
        Location parsed = ScarletCloseInstanceInput.parse(location, group);
        if (!location.equals(parsed.world + ":" + parsed.instance)) throw new IllegalArgumentException();
        return parsed;
    }

    private String newest(Environment environment, Set<String> active, OffsetDateTime to) throws Exception
    {
        OffsetDateTime from = to.minusDays(LOOKBACK_DAYS);
        Map<String, OffsetDateTime> creationTimes = new HashMap<>();
        for (int page = 0; page < MAX_PAGES; page++)
        {
            this.checkAllowed(environment);
            AuditPage data = environment.creationPage(from, to, page * PAGE_SIZE);
            this.checkAllowed(environment);
            if (data == null || data.entries == null || data.entries.size() > PAGE_SIZE)
                throw new Retry("The instance creation history was unavailable or incomplete.");
            for (GroupAuditLogEntryUnknown entry : data.entries)
            {
                if (entry == null || !this.groupId.equals(entry.getGroupId())
                    || !"group.instance.create".equals(entry.getEventType())
                    || !active.contains(entry.getTargetId()) || entry.getCreatedAt() == null
                    || entry.getCreatedAt().isBefore(from) || entry.getCreatedAt().isAfter(to))
                    continue;
                OffsetDateTime previous = creationTimes.get(entry.getTargetId());
                if (previous == null || entry.getCreatedAt().isAfter(previous))
                    creationTimes.put(entry.getTargetId(), entry.getCreatedAt());
            }
            if (!data.hasNext)
            {
                if (!creationTimes.keySet().containsAll(active))
                    throw new Retry("The newest active instance could not be verified from recent creation history.");
                String selected = null;
                for (String location : active)
                    if (selected == null || creationTimes.get(location).isAfter(creationTimes.get(selected)))
                        selected = location;
                return selected;
            }
            if (data.entries.isEmpty())
                throw new Retry("The instance creation history reported an incomplete empty page.");
        }
        throw new Retry("The startup instance creation lookup reached its five-page safety limit.");
    }

    private static final class Retry extends Exception
    {
        private static final long serialVersionUID = 1L;
        Retry(String message) { super(message); }
    }

    private static final class Cancelled extends Exception
    {
        private static final long serialVersionUID = 1L;
    }
}

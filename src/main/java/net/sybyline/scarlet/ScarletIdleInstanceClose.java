package net.sybyline.scarlet;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.sybyline.scarlet.util.Location;
import net.sybyline.scarlet.util.VrcIds;

/** Session-bound, fail-closed policy for the optional idle-instance soft close. */
final class ScarletIdleInstanceClose
{
    private static final Pattern USER = Pattern.compile("usr_[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    static final long MAX_QUERY_NANOS = TimeUnit.SECONDS.toNanos(10);

    interface Transport
    {
        JsonElement read(String location, BooleanSupplier allowed);
        boolean softClose(String location, BooleanSupplier allowed);
    }

    private final BooleanSupplier active, healthy;
    private final IntSupplier minutes;
    private final Supplier<String> group, apiUser;
    private final LongSupplier authentication, nanos;
    private final Supplier<Instant> clock;
    private final Transport transport;
    private final Consumer<String> closed;
    private final AtomicBoolean checking = new AtomicBoolean();
    private final AtomicLong configurationChanges = new AtomicLong();
    private final Set<String> players = new HashSet<>();
    private String self, location, configuredGroup, configuredUser;
    private Instant joinedAt;
    private boolean ready, validRoster = true, enabled, counting, attempted;
    private int configuredMinutes;
    private long revision, eligibleSince, configuredAuthentication, configuredChanges;

    ScarletIdleInstanceClose(BooleanSupplier active, IntSupplier minutes, Supplier<String> group,
        Supplier<String> apiUser, LongSupplier authentication, BooleanSupplier healthy,
        Transport transport, Consumer<String> closed, Supplier<Instant> clock, LongSupplier nanos)
    {
        this.active = active; this.minutes = minutes; this.group = group; this.apiUser = apiUser;
        this.authentication = authentication; this.healthy = healthy; this.transport = transport;
        this.closed = closed; this.clock = clock; this.nanos = nanos;
    }

    /** Lock-free: setting listeners run while holding the setting's own lock. */
    void configurationChanged() { this.configurationChanges.incrementAndGet(); }

    synchronized void newLog() { this.left(); this.self = null; }

    synchronized void authenticated(String self)
    {
        if (!Objects.equals(this.self, self))
        {
            this.left();
            this.self = self;
        }
    }

    synchronized void joined(String location, Instant timestamp, boolean preamble)
    {
        this.left();
        this.location = location;
        this.joinedAt = timestamp;
        this.ready = !preamble;
    }

    synchronized void caughtUp()
    {
        if (!this.ready) { this.ready = true; this.changed(); }
    }

    synchronized void left()
    {
        this.location = null; this.joinedAt = null; this.ready = false;
        this.players.clear(); this.validRoster = true; this.changed();
    }

    synchronized void playerJoined(String user)
    {
        if (!validUser(user) || this.players.size() >= 10_000)
        {
            this.validRoster = false; this.changed();
        }
        else if (this.players.add(user)) this.changed();
    }

    synchronized void playerLeft(String user)
    {
        if (!validUser(user)) { this.validRoster = false; this.changed(); }
        else if (this.players.remove(user)) this.changed();
    }

    private void changed()
    {
        ++this.revision;
        this.counting = false;
        this.attempted = false;
    }

    private void refresh()
    {
        boolean enabled = this.active.getAsBoolean();
        int minutes = this.minutes.getAsInt();
        String group = this.group.get(), user = this.apiUser.get();
        long authentication = this.authentication.getAsLong(), changes = this.configurationChanges.get();
        if (this.enabled != enabled || this.configuredMinutes != minutes
            || !Objects.equals(this.configuredGroup, group) || !Objects.equals(this.configuredUser, user)
            || this.configuredAuthentication != authentication || this.configuredChanges != changes)
        {
            this.enabled = enabled; this.configuredMinutes = minutes;
            this.configuredGroup = group; this.configuredUser = user;
            this.configuredAuthentication = authentication; this.configuredChanges = changes;
            this.changed();
        }
    }

    private boolean eligible()
    {
        return this.enabled && this.configuredMinutes > 0 && this.configuredMinutes <= 1440
            && this.ready && this.validRoster && validUser(this.self) && this.self.equals(this.configuredUser)
            && this.players.size() == 1 && this.players.contains(this.self)
            && this.joinedAt != null && !this.joinedAt.isAfter(this.clock.get())
            && groupLocation(this.location, this.configuredGroup) != null;
    }

    private boolean healthyNow()
    {
        if (this.healthy.getAsBoolean()) return true;
        // Keep an already-consumed mutation consumed even if its response was uncertain.
        if (this.counting) ++this.revision;
        this.counting = false;
        return false;
    }

    /** Network work never holds the observation lock: incoming log events can cancel it. */
    void tick()
    {
        if (!this.checking.compareAndSet(false, true)) return;
        try
        {
            final Attempt attempt;
            synchronized (this)
            {
                this.refresh();
                if (!this.healthyNow() || !this.eligible()) { this.counting = false; return; }
                if (this.attempted) return;
                long now = this.nanos.getAsLong();
                if (!this.counting || now - this.eligibleSince < 0L)
                {
                    this.counting = true; this.eligibleSince = now; return;
                }
                if (now - this.eligibleSince < TimeUnit.MINUTES.toNanos(this.configuredMinutes)) return;
                attempt = new Attempt(this, now);
            }
            JsonElement response;
            try { response = this.transport.read(attempt.location, () -> this.current(attempt)); }
            catch (RuntimeException ex) { response = null; }
            if (!serverAlone(response, attempt.location, attempt.group, attempt.self))
            {
                synchronized (this)
                {
                    if (this.revision == attempt.revision) this.changed();
                }
                return;
            }
            synchronized (this)
            {
                if (!this.current(attempt)) return;
                // Never retry a mutation with an uncertain result during the same idle episode.
                this.attempted = true;
            }
            if (this.transport.softClose(attempt.location, () -> this.current(attempt)))
                this.closed.accept(attempt.location);
        }
        finally { this.checking.set(false); }
    }

    private synchronized boolean current(Attempt attempt)
    {
        this.refresh();
        long elapsed = this.nanos.getAsLong() - attempt.queryStarted;
        if (!this.healthyNow() || !this.eligible() || !this.counting || this.revision != attempt.revision) return false;
        if (elapsed < 0L || elapsed > MAX_QUERY_NANOS)
        {
            // Stale proof starts a full grace interval, but cannot revive a consumed mutation.
            this.counting = false; ++this.revision; return false;
        }
        return this.nanos.getAsLong() - this.eligibleSince >= TimeUnit.MINUTES.toNanos(this.configuredMinutes);
    }

    private static final class Attempt
    {
        final long revision, queryStarted;
        final String location, group, self;
        Attempt(ScarletIdleInstanceClose owner, long queryStarted)
        {
            this.revision = owner.revision; this.queryStarted = queryStarted;
            this.location = owner.location; this.group = owner.configuredGroup; this.self = owner.self;
        }
    }

    static Location groupLocation(String location, String group)
    {
        if (group == null || !VrcIds.id_group.matcher(group).matches()
            || location == null || !VrcIds.id_location.matcher(location).matches()) return null;
        try
        {
            Location parsed = Location.of(location);
            return parsed.isConcrete() && group.equals(parsed.owner)
                && (parsed.type == Location.Type.GROUP || parsed.type == Location.Type.GROUP_PLUS
                    || parsed.type == Location.Type.GROUP_PUBLIC) ? parsed : null;
        }
        catch (RuntimeException ex) { return null; }
    }

    /** Explicit raw fields only: generated model defaults cannot establish absence of players. */
    static boolean serverAlone(JsonElement response, String location, String group, String self)
    {
        Location parsed = groupLocation(location, group);
        if (parsed == null || !validUser(self) || response == null || !response.isJsonObject()) return false;
        JsonObject value = response.getAsJsonObject();
        if (!string(value, "worldId", parsed.world) || !string(value, "instanceId", parsed.instance)
            || !string(value, "ownerId", group) || !string(value, "type", "group")
            || !value.has("closedAt") || !value.get("closedAt").isJsonNull()
            || !bool(value, "active", true) || !one(value.get("n_users"))) return false;
        if (value.has("location") && !string(value, "location", location)) return false;
        if (value.has("id") && !string(value, "id", location) && !string(value, "id", parsed.instance)) return false;
        if (value.has("userCount") && !one(value.get("userCount"))) return false;
        JsonElement users = value.get("users");
        if (users == null || !users.isJsonArray() || users.getAsJsonArray().size() != 1) return false;
        JsonElement only = users.getAsJsonArray().get(0);
        return only.isJsonObject() && string(only.getAsJsonObject(), "id", self);
    }

    private static boolean validUser(String user) { return user != null && USER.matcher(user).matches(); }
    private static boolean string(JsonObject value, String key, String expected)
    {
        JsonElement element = value.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
            && expected.equals(element.getAsString());
    }
    private static boolean bool(JsonObject value, String key, boolean expected)
    {
        JsonElement element = value.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
            && element.getAsBoolean() == expected;
    }
    private static boolean one(JsonElement element)
    {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
            && "1".equals(element.getAsString());
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Synthetic policy observations only: no API calls, processes or game files. */
class ScarletIdleInstanceCloseTest
{
    static final String GROUP = ScarletClosedInstanceExitTest.GROUP, OTHER_GROUP = ScarletClosedInstanceExitTest.OTHER_GROUP;
    static final String SELF = ScarletClosedInstanceExitTest.SELF, OTHER = ScarletClosedInstanceExitTest.OTHER;
    static final String LOCATION = ScarletClosedInstanceExitTest.LOCATION;
    static final String WORLD = LOCATION.substring(0, LOCATION.indexOf(':')), INSTANCE = LOCATION.substring(LOCATION.indexOf(':') + 1);
    static final Instant START = Instant.parse("2026-10-17T12:00:00Z");
    static final long WAIT = TimeUnit.MINUTES.toNanos(1);

    static JsonObject alone()
    {
        JsonObject value = new JsonObject();
        value.addProperty("worldId", WORLD); value.addProperty("instanceId", INSTANCE);
        value.addProperty("ownerId", GROUP); value.addProperty("type", "group");
        value.add("closedAt", JsonNull.INSTANCE); value.addProperty("active", true);
        value.addProperty("n_users", 1); value.addProperty("userCount", 1);
        value.addProperty("id", LOCATION); value.addProperty("location", LOCATION);
        JsonArray users = new JsonArray(); JsonObject user = new JsonObject(); user.addProperty("id", SELF);
        users.add(user); value.add("users", users); return value;
    }

    static final class Fixture implements ScarletIdleInstanceClose.Transport
    {
        final AtomicBoolean active = new AtomicBoolean(true), healthy = new AtomicBoolean(true);
        final AtomicInteger minutes = new AtomicInteger(1), reads = new AtomicInteger(), closes = new AtomicInteger(), notices = new AtomicInteger();
        final AtomicReference<String> group = new AtomicReference<>(GROUP), user = new AtomicReference<>(SELF);
        final AtomicLong authentication = new AtomicLong(), nanos = new AtomicLong();
        JsonElement response = alone();
        Runnable duringRead = () -> {}, beforeDispatch = () -> {};
        boolean result = true;
        final ScarletIdleInstanceClose policy = new ScarletIdleInstanceClose(active::get, minutes::get, group::get,
            user::get, authentication::get, healthy::get, this, location -> notices.incrementAndGet(),
            () -> START.plusSeconds(600), nanos::get);
        public JsonElement read(String location, BooleanSupplier allowed)
        {
            assertEquals(LOCATION, location); assertTrue(allowed.getAsBoolean()); reads.incrementAndGet();
            duringRead.run(); return response;
        }
        public boolean softClose(String location, BooleanSupplier allowed)
        {
            assertEquals(LOCATION, location); beforeDispatch.run();
            if (!allowed.getAsBoolean()) return false;
            closes.incrementAndGet(); return result;
        }
        void live()
        {
            policy.newLog(); policy.authenticated(SELF); policy.joined(LOCATION, START, false); policy.playerJoined(SELF);
        }
        void arm() { live(); policy.tick(); }
        void due() { nanos.addAndGet(WAIT); policy.tick(); }
        void fullGrace()
        {
            int prior = reads.get(); policy.tick(); nanos.addAndGet(WAIT - 1); policy.tick();
            assertEquals(prior, reads.get()); nanos.incrementAndGet(); policy.tick();
        }
    }

    @Test void healthyCurrentBotOnlySessionWaitsFullIntervalAndSoftClosesOnce()
    {
        Fixture f = new Fixture(); f.arm(); f.nanos.set(WAIT - 1); f.policy.tick();
        assertEquals(0, f.reads.get()); f.nanos.incrementAndGet(); f.policy.tick();
        assertEquals(1, f.closes.get()); assertEquals(1, f.notices.get());
        f.due(); f.due(); assertEquals(1, f.reads.get());
    }

    @Test void missingSelfUnknownRosterAndOccupiedRosterCannotReadOrClose()
    {
        for (String kind : new String[]{"empty", "other", "occupied", "invalid"})
        {
            Fixture f = new Fixture(); f.live();
            if (!kind.equals("occupied")) f.policy.playerLeft(SELF);
            if (!kind.equals("empty")) f.policy.playerJoined(kind.equals("invalid") ? "bad-user" : OTHER);
            f.policy.tick(); f.due(); assertEquals(0, f.reads.get(), kind); assertEquals(0, f.closes.get(), kind);
        }
    }

    @Test void quitNewLogAndLeaveDiscardLegacyLookingState()
    {
        for (String event : new String[]{"quit-or-new-log", "left", "auth-change"})
        {
            Fixture f = new Fixture(); f.arm();
            if (event.equals("left")) f.policy.left();
            else if (event.equals("auth-change")) f.policy.authenticated(OTHER);
            else f.policy.newLog();
            f.due(); assertEquals(0, f.reads.get(), event);
            f.live(); f.fullGrace(); assertEquals(1, f.closes.get(), event);
        }
    }

    @Test void catchupAndUnhealthyTrackingRequireFullNewInterval()
    {
        Fixture f = new Fixture(); f.live(); f.policy.joined(LOCATION, START, true); f.policy.playerJoined(SELF);
        f.policy.tick(); f.due(); assertEquals(0, f.reads.get());
        f.policy.caughtUp(); f.policy.tick(); f.nanos.addAndGet(WAIT / 2);
        f.healthy.set(false); f.policy.tick(); f.due(); assertEquals(0, f.reads.get());
        f.healthy.set(true); f.fullGrace(); assertEquals(1, f.closes.get());
    }

    @Test void disabledTrainingStaffShutdownWrongGroupAndApiIdentityFailClosed()
    {
        for (String kind : new String[]{"inactive", "zero", "negative", "too-large", "group", "api-user", "public"})
        {
            Fixture f = new Fixture(); f.live();
            if (kind.equals("inactive")) f.active.set(false);
            if (kind.equals("zero")) f.minutes.set(0);
            if (kind.equals("negative")) f.minutes.set(-1);
            if (kind.equals("too-large")) f.minutes.set(1441);
            if (kind.equals("group")) f.group.set(OTHER_GROUP);
            if (kind.equals("api-user")) f.user.set(OTHER);
            if (kind.equals("public")) { f.policy.joined(WORLD + ":123", START, false); f.policy.playerJoined(SELF); }
            f.policy.tick(); f.due(); assertEquals(0, f.reads.get(), kind);
        }
    }

    @Test void configToggleBetweenPollsInvalidatesGraceEvenWhenValueIsRestored()
    {
        Fixture f = new Fixture(); f.arm(); f.nanos.addAndGet(WAIT / 2);
        f.policy.configurationChanged(); f.policy.configurationChanged();
        f.fullGrace(); assertEquals(1, f.closes.get());
    }

    @Test void joinsDuringReadAndImmediatelyBeforeDispatchCancelClosure()
    {
        for (boolean atDispatch : new boolean[]{false, true})
        {
            Fixture f = new Fixture(); f.arm();
            Runnable change = () -> { f.policy.playerJoined(OTHER); f.policy.playerLeft(OTHER); };
            if (atDispatch) f.beforeDispatch = change; else f.duringRead = change;
            f.due(); assertEquals(0, f.closes.get());
            f.beforeDispatch = () -> {}; f.duringRead = () -> {};
            f.fullGrace(); assertEquals(1, f.closes.get());
        }
    }

    @Test void everySessionAndSafetyChangeDuringLookupCancelsMutation()
    {
        for (String change : new String[]{"new-log", "left", "rejoin", "health", "active", "setting", "group", "user", "auth"})
        {
            Fixture f = new Fixture(); f.arm(); f.duringRead = () -> {
                if (change.equals("new-log")) f.policy.newLog();
                if (change.equals("left")) f.policy.left();
                if (change.equals("rejoin")) { f.policy.joined(LOCATION, START, false); f.policy.playerJoined(SELF); }
                if (change.equals("health")) f.healthy.set(false);
                if (change.equals("active")) f.active.set(false);
                if (change.equals("setting")) f.policy.configurationChanged();
                if (change.equals("group")) f.group.set(OTHER_GROUP);
                if (change.equals("user")) f.user.set(OTHER);
                if (change.equals("auth")) f.authentication.incrementAndGet();
            };
            f.due(); assertEquals(0, f.closes.get(), change);
        }
    }

    @Test void staleReadAndMalformedOrFailedReadRestartFullGrace()
    {
        for (String kind : new String[]{"stale", "null", "throw", "occupied"})
        {
            Fixture f = new Fixture(); f.arm();
            if (kind.equals("stale")) f.duringRead = () -> f.nanos.addAndGet(ScarletIdleInstanceClose.MAX_QUERY_NANOS + 1);
            if (kind.equals("null")) f.response = null;
            if (kind.equals("throw")) f.duringRead = () -> { throw new IllegalStateException("synthetic"); };
            if (kind.equals("occupied")) f.response.getAsJsonObject().addProperty("n_users", 2);
            f.due(); assertEquals(0, f.closes.get(), kind);
            f.duringRead = () -> {}; f.response = alone(); f.fullGrace(); assertEquals(1, f.closes.get(), kind);
        }
    }

    @Test void uncertainMutationIsNeverRetriedInTheSameIdleEpisode()
    {
        Fixture f = new Fixture(); f.arm(); f.result = false; f.due();
        f.healthy.set(false); f.policy.tick(); f.healthy.set(true); f.due(); f.due();
        assertEquals(1, f.reads.get()); assertEquals(1, f.closes.get()); assertEquals(0, f.notices.get());
    }

    @Test void responseMustExplicitlyProveExactGroupInstanceAndSelfOnly()
    {
        assertTrue(ScarletIdleInstanceClose.serverAlone(alone(), LOCATION, GROUP, SELF));
        for (String key : new String[]{"worldId", "instanceId", "ownerId", "type", "closedAt", "active", "n_users", "users"})
        {
            JsonObject value = alone(); value.remove(key);
            assertFalse(ScarletIdleInstanceClose.serverAlone(value, LOCATION, GROUP, SELF), key);
        }
        for (String kind : new String[]{"world", "instance", "owner", "closed", "inactive", "string-count", "zero", "count-disagreement", "other-user", "empty-users", "location", "id"})
        {
            JsonObject value = alone();
            if (kind.equals("world")) value.addProperty("worldId", "wrld_other");
            if (kind.equals("instance")) value.addProperty("instanceId", "other");
            if (kind.equals("owner")) value.addProperty("ownerId", OTHER_GROUP);
            if (kind.equals("closed")) value.addProperty("closedAt", START.toString());
            if (kind.equals("inactive")) value.addProperty("active", false);
            if (kind.equals("string-count")) value.addProperty("n_users", "1");
            if (kind.equals("zero")) value.addProperty("n_users", 0);
            if (kind.equals("count-disagreement")) value.addProperty("userCount", 2);
            if (kind.equals("other-user")) value.getAsJsonArray("users").get(0).getAsJsonObject().addProperty("id", OTHER);
            if (kind.equals("empty-users")) value.add("users", new JsonArray());
            if (kind.equals("location")) value.addProperty("location", "wrong");
            if (kind.equals("id")) value.addProperty("id", "wrong");
            assertFalse(ScarletIdleInstanceClose.serverAlone(value, LOCATION, GROUP, SELF), kind);
        }
    }

    @Test void blockedNetworkReadDoesNotBlockSessionEventsOrPermitParallelQueries() throws Exception
    {
        Fixture f = new Fixture(); f.arm(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.duringRead = () -> {
            entered.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { f.due(); } catch (Throwable ex) { failure.set(ex); } });
        worker.start();
        try
        {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            f.policy.newLog(); f.policy.tick(); assertEquals(1, f.reads.get());
        }
        finally { release.countDown(); worker.join(3_000); }
        assertFalse(worker.isAlive()); assertNull(failure.get()); assertEquals(0, f.closes.get());
    }

    @Test void actualEntryPointAndLogQuitOrRotationCannotUseStaleLegacyRoster() throws Exception
    {
        for (boolean quit : new boolean[]{false, true})
        {
            Fixture f = new Fixture(); f.arm();
            Scarlet scarlet = ScarletDiscordSafetyTest.inert(Scarlet.class);
            ScarletEventListener listener = ScarletDiscordSafetyTest.inert(ScarletEventListener.class);
            ScarletDiscordSafetyTest.put(scarlet, "eventListener", listener);
            ScarletDiscordSafetyTest.put(listener, "idleInstanceClose", f.policy);
            ScarletDiscordSafetyTest.put(listener, "clientExitMonitor", new ScarletClientExitMonitor(() -> false, () -> null,
                () -> false, null, location -> null, result -> {}, Instant::now, System::nanoTime));
            ScarletDiscordSafetyTest.put(listener, "livePlayerListSession", new ScarletEventListener.LivePlayerListSession(() -> null));
            ScarletDiscordSafetyTest.put(listener, "clientLocation_userDisplayName2avatarDisplayName", new HashMap<>());
            ScarletDiscordSafetyTest.put(listener, "clientLocation_userDisplayName2avatarBundleInfo", new HashMap<>());
            ScarletDiscordSafetyTest.put(listener, "clientLocation_avatarDisplayName2userDisplayName", net.sybyline.scarlet.util.CollectionMap.setsConcurrent());
            listener.clientUserId = SELF; listener.clientLocation = LOCATION;
            listener.clientLocation_userIdsJoinOrder = new HashSet<>(); listener.clientLocation_userIdsJoinOrder.add(SELF);
            if (quit) listener.log_userQuit(false, LocalDateTime.of(2026, 10, 17, 12, 0), 600D);
            else listener.log_init(null);
            f.nanos.set(WAIT * 10); scarlet.maybeAutoCloseBotInstance();
            assertEquals(LOCATION, listener.clientLocation, "Legacy display state intentionally still resembles the old bug");
            assertEquals(0, f.reads.get()); assertEquals(0, f.closes.get());
        }
    }
}

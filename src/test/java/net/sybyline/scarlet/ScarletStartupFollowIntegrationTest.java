package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Inert controllers and callbacks only: no accounts, games or real API clients. */
class ScarletStartupFollowIntegrationTest
{
    static final String GROUP = "grp_11111111-1111-1111-1111-111111111111";
    static final String LOCATION = "wrld_11111111-1111-1111-1111-111111111111:1~group(" + GROUP + ")~groupAccessType(members)~region(us)";

    @Test void startupRecoveryStillTicksWhenFastPollingIsZero() throws Exception
    {
        AtomicInteger startup = new AtomicInteger();
        Scarlet scarlet = inert(Scarlet.class);
        ScarletDiscord discord = (ScarletDiscord) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{ScarletDiscord.class}, (proxy, method, args) -> {
                if (method.getName().equals("maybeJoinActiveInstanceOnStartup")) { startup.incrementAndGet(); return null; }
                if (method.getName().equals("isStartupInstanceJoinPending")) return false;
                if (method.getName().equals("isLaunchOnInstanceCreateEnabled")) return true;
                throw new AssertionError("Unexpected operation " + method.getName());
            });
        ScarletSettings settings = inert(ScarletSettings.class);
        put(settings, "fileValuedSettings", new HashMap<>());
        ScarletSettings.FileValuedIntRange interval = settings.new FileValuedIntRange("synthetic_interval", "Synthetic", 0, 0, 300);
        put(interval, "cached", 0);
        put(scarlet, "instanceFollowFastPollSeconds", interval);
        put(scarlet, "discord", discord);
        scarlet.maybeFastDetectNewInstance();
        assertEquals(1, startup.get());
    }

    @Test void disabledAtStartupDoesNotCreateWorkerOrProbeAClient() throws Exception
    {
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        Scarlet scarlet = inert(Scarlet.class);
        ScarletVRChat vrc = inert(ScarletVRChat.class);
        vrc.groupId = GROUP;
        vrc.currentUserId = "usr_11111111-1111-1111-1111-111111111111";
        put(scarlet, "vrc", vrc);
        put(scarlet, "running", true);
        put(discord, "scarlet", scarlet);
        put(discord, "startupDeferredFollow", new ScarletDeferredInstanceFollow());
        ScarletSettings settings = inert(ScarletSettings.class);
        put(settings, "fileValuedSettings", new HashMap<>());
        ScarletSettings.FileValuedBoolean toggle = settings.new FileValuedBoolean("synthetic_follow", "Synthetic", false);
        put(toggle, "cached", false);
        put(discord, "vrchatClient_launchOnInstanceCreate", toggle);
        discord.maybeJoinActiveInstanceOnStartup();
        assertFalse(discord.isStartupInstanceJoinPending());
        assertNull(field(discord, "clientLaunchWorker"));
        put(toggle, "cached", true);
        discord.maybeJoinActiveInstanceOnStartup();
        assertNull(field(discord, "clientLaunchWorker"), "Enabling later must not replay a startup join");
    }

    @Test void actualSubmissionRunsInBackgroundAndDoesNotQueueDuplicateStartupWork() throws Exception
    {
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        ScarletStartupInstanceJoin helper = new ScarletStartupInstanceJoin(GROUP, OffsetDateTime.now());
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger(), reads = new AtomicInteger();
        put(discord, "startupInstanceJoin", helper);
        put(discord, "startupDeferredFollow", new ScarletDeferredInstanceFollow());
        put(discord, "startupInstanceEnvironment", new ScarletStartupInstanceJoin.Environment() {
            public boolean allowed() { return true; }
            public boolean clientRunning() { return false; }
            public List<String> activeLocations() throws Exception {
                assertEquals("Scarlet-ClientLaunch", Thread.currentThread().getName());
                reads.incrementAndGet(); started.countDown(); release.await();
                return Collections.singletonList(LOCATION);
            }
            public ScarletStartupInstanceJoin.AuditPage creationPage(OffsetDateTime from, OffsetDateTime to, int offset) {
                throw new AssertionError("One instance must not require creation history");
            }
            public void launch(String location) { assertEquals(LOCATION, location); launches.incrementAndGet(); completed.countDown(); }
            public void log(String message) { }
        });
        ScarletClientLaunchWorker worker = null;
        try
        {
            assertTimeout(Duration.ofSeconds(1), discord::maybeJoinActiveInstanceOnStartup);
            worker = (ScarletClientLaunchWorker) field(discord, "clientLaunchWorker");
            assertTrue(started.await(3, TimeUnit.SECONDS));
            for (int n = 0; n < 20; n++) discord.maybeJoinActiveInstanceOnStartup();
            assertEquals(1, reads.get());
            // A catch-up/fast-path launch must be suppressed while startup choice is unresolved.
            discord.launchClientIntoInstanceIfEnabled(LOCATION);
            release.countDown();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
        }
        finally
        {
            release.countDown();
            if (worker != null) worker.close();
        }
        assertEquals(1, launches.get());
        assertEquals(2, reads.get());
    }

    private static Object field(Object object, String name) throws Exception
    {
        Field field = ScarletDiscordJDA.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static class CapturingFollow extends ScarletDiscordJDA
    {
        boolean busy;
        String accepted;
        int submissions;
        CapturingFollow() { super(null, null, null); throw new AssertionError("No real Discord"); }
        @Override synchronized boolean queueClientLaunch(String location, boolean reportBusy)
        {
            submissions++;
            if (busy) return false;
            accepted = location;
            return true;
        }
    }

    @Test void newestCreationArrivingDuringStartupSurvivesBusyWorkerHandOff() throws Exception
    {
        CapturingFollow discord = inert(CapturingFollow.class);
        OffsetDateTime start = OffsetDateTime.now();
        ScarletStartupInstanceJoin helper = new ScarletStartupInstanceJoin(GROUP, start);
        ScarletDeferredInstanceFollow deferred = new ScarletDeferredInstanceFollow();
        put(discord, "startupInstanceJoin", helper);
        put(discord, "startupDeferredFollow", deferred);
        ScarletStartupInstanceJoinTest.Fake env = new ScarletStartupInstanceJoinTest.Fake();
        put(discord, "startupInstanceEnvironment", env);
        discord.followInstanceCreation("historical", start.minusHours(1));
        assertNull(deferred.peek());
        discord.followInstanceCreation("newest", start.plusSeconds(20));
        discord.followInstanceCreation("earlier", start.plusSeconds(10));
        assertEquals("newest", deferred.peek().location);
        assertEquals(0, discord.submissions);
        // Completion can precede the worker releasing its slot by a small interval.
        helper.cancel();
        discord.busy = true;
        discord.maybeJoinActiveInstanceOnStartup();
        assertNotNull(deferred.peek());
        assertNull(discord.accepted);
        discord.followInstanceCreation("even-newer", start.plusSeconds(30));
        assertEquals("even-newer", deferred.peek().location);
        discord.busy = false;
        discord.maybeJoinActiveInstanceOnStartup();
        assertEquals("even-newer", discord.accepted);
        assertNull(deferred.peek());
        discord.maybeJoinActiveInstanceOnStartup();
        assertEquals(3, discord.submissions, "Exactly one accepted hand-off, not repeated launches");
    }

    @Test void creationJustAfterCompletionIsRetainedWhenWorkerIsStillBusy() throws Exception
    {
        CapturingFollow discord = inert(CapturingFollow.class);
        OffsetDateTime start = OffsetDateTime.now();
        ScarletStartupInstanceJoin helper = new ScarletStartupInstanceJoin(GROUP, start);
        ScarletDeferredInstanceFollow deferred = new ScarletDeferredInstanceFollow();
        put(discord, "startupInstanceJoin", helper);
        put(discord, "startupDeferredFollow", deferred);
        put(discord, "startupInstanceEnvironment", new ScarletStartupInstanceJoinTest.Fake());
        helper.cancel();
        discord.busy = true;
        discord.followInstanceCreation("new", start.plusSeconds(1));
        assertEquals("new", deferred.peek().location);
        discord.busy = false;
        discord.maybeJoinActiveInstanceOnStartup();
        assertEquals("new", discord.accepted);
        assertNull(deferred.peek());
    }

    @Test void optOutDiscardsDeferredCreationInsteadOfLaunchingLater() throws Exception
    {
        CapturingFollow discord = inert(CapturingFollow.class);
        OffsetDateTime start = OffsetDateTime.now();
        ScarletStartupInstanceJoin helper = new ScarletStartupInstanceJoin(GROUP, start);
        ScarletDeferredInstanceFollow deferred = new ScarletDeferredInstanceFollow();
        put(discord, "startupInstanceJoin", helper);
        put(discord, "startupDeferredFollow", deferred);
        ScarletStartupInstanceJoinTest.Fake env = new ScarletStartupInstanceJoinTest.Fake();
        put(discord, "startupInstanceEnvironment", env);
        discord.followInstanceCreation("new", start.plusSeconds(1));
        env.allowed = false;
        discord.maybeJoinActiveInstanceOnStartup();
        assertFalse(helper.isPending());
        assertNull(deferred.peek());
        env.allowed = true;
        discord.maybeJoinActiveInstanceOnStartup();
        assertEquals(0, discord.submissions);
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.FileAnalysis;
import io.github.vrchatapi.model.ModelFile;
import io.github.vrchatapi.model.User;
import net.sybyline.scarlet.util.CollectionMap;
import net.sybyline.scarlet.util.Func;
import net.sybyline.scarlet.util.Pacer;
import net.sybyline.scarlet.util.VersionedFile;

/** Runs the actual log event entry points with inert app objects and blocking fake lookups. */
class ScarletBackgroundPlayerTrackingTest
{
    static final String USER = "usr_synthetic_player";
    static final String LOCATION = "wrld_synthetic:100~group(grp_synthetic)";
    static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 23, 12, 0);
    static final String ANALYSIS = "https://api.vrchat.cloud/api/1/analysis/file_11111111-1111-1111-1111-111111111111/1";

    static class FakeVrc extends ScarletVRChat
    {
        CountDownLatch profileEntered, profileRelease, analysisEntered, analysisRelease;
        AtomicInteger profileReads, modelReads, analysisReads;
        volatile boolean blockProfile, blockAnalysis;
        FakeVrc() { super(null, null, null); throw new AssertionError("No real app constructor"); }
        @Override public User getUser(String id)
        {
            profileReads.incrementAndGet();
            if (blockProfile) { profileEntered.countDown(); waitFor(profileRelease); }
            return new User().id(id);
        }
        @Override public ModelFile getModelFile(String id)
        {
            modelReads.incrementAndGet();
            if (blockAnalysis) { analysisEntered.countDown(); waitFor(analysisRelease); }
            return new ModelFile().name("Avatar - Synthetic Avatar - Asset bundle - synthetic");
        }
        @Override public FileAnalysis getFileAnalysis(VersionedFile file, long minimum)
        { analysisReads.incrementAndGet(); return new FileAnalysis().performanceRating("Good"); }
    }

    static class Listener extends ScarletEventListener
    {
        Listener() { super(null); throw new AssertionError("No real app constructor"); }
        @Override Color checkPlayer(List<String> advisories, int[] priority, boolean preamble,
                String name, String id, boolean rejoined, User user, BooleanSupplier allowed)
        { return null; }
    }

    static void waitFor(CountDownLatch latch)
    {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try
        {
            while (latch.getCount() != 0)
            {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0L, "synthetic worker did not finish");
                try { assertTrue(latch.await(remaining, TimeUnit.NANOSECONDS)); }
                catch (InterruptedException ex) { interrupted = true; }
            }
        }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    static class Fixture implements AutoCloseable
    {
        final Scarlet scarlet;
        final Listener listener;
        final FakeVrc vrc;
        final List<String> rows = Collections.synchronizedList(new ArrayList<>());
        final Map<String, ScarletUI.ConnectedPlayer> playerRows = new ConcurrentHashMap<>();
        final AtomicInteger enriched = new AtomicInteger();
        Fixture() throws Exception
        {
            scarlet = ScarletDiscordSafetyTest.inert(Scarlet.class);
            listener = ScarletDiscordSafetyTest.inert(Listener.class);
            vrc = ScarletDiscordSafetyTest.inert(FakeVrc.class);
            vrc.profileEntered = new CountDownLatch(1); vrc.profileRelease = new CountDownLatch(1);
            vrc.analysisEntered = new CountDownLatch(1); vrc.analysisRelease = new CountDownLatch(1);
            vrc.profileReads = new AtomicInteger(); vrc.modelReads = new AtomicInteger(); vrc.analysisReads = new AtomicInteger();
            ScarletDiscordSafetyTest.put(scarlet, "vrc", vrc);
            ScarletDiscordSafetyTest.put(scarlet, "running", true);
            ScarletDiscordSafetyTest.put(scarlet, "eventListener", listener);
            IScarletUI ui = (IScarletUI)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IScarletUI.class}, (proxy, method, args) -> {
                if ("playerJoin".equals(method.getName()))
                {
                    rows.add("join:"+args[1]);
                    playerRows.put((String)args[1], ScarletDiscordSafetyTest.inert(ScarletUI.ConnectedPlayer.class));
                }
                if ("playerUpdate".equals(method.getName()) && playerRows.containsKey(args[1]))
                    ((Func.V1.NE<ScarletUI.ConnectedPlayer>)args[2]).invoke(playerRows.get(args[1]));
                if ("playerLeave".equals(method.getName())) rows.add("leave:"+args[1]);
                if ("playerEnrich".equals(method.getName()) && ((BooleanSupplier)args[5]).getAsBoolean()) enriched.incrementAndGet();
                return method.getReturnType() == boolean.class ? false : null;
            });
            ScarletDiscordSafetyTest.put(scarlet, "ui", ui);
            ScarletDiscordSafetyTest.put(listener, "scarlet", scarlet);
            ScarletDiscordSafetyTest.put(listener, "roster", new InstanceRoster());
            ScarletDiscordSafetyTest.put(listener, "lookupSession", new AtomicLong());
            ScarletDiscordSafetyTest.put(listener, "playerVisits", new ConcurrentHashMap<>());
            ScarletDiscordSafetyTest.put(listener, "avatarVersions", new ConcurrentHashMap<>());
            ScarletDiscordSafetyTest.put(listener, "playerLookups", new ScarletBackgroundWork("synthetic-player-details", 256, ex -> fail(ex)));
            ScarletDiscordSafetyTest.put(listener, "avatarLookups", new ScarletBackgroundWork("synthetic-avatar-details", 256, ex -> fail(ex)));
            ScarletDiscordSafetyTest.put(listener, "checkPlayerLimiter", new Pacer(1L));
            ScarletDiscordSafetyTest.put(listener, "livePlayerListSession", new ScarletEventListener.LivePlayerListSession(() -> null));
            ScarletDiscordSafetyTest.put(listener, "clientExitMonitor", new ScarletClientExitMonitor(() -> false, () -> null,
                () -> false, null, location -> null, result -> {}, Instant::now, System::nanoTime));
            ScarletDiscordSafetyTest.put(listener, "clientLocation_userDisplayName2userId", new ConcurrentHashMap<>());
            ScarletDiscordSafetyTest.put(listener, "clientLocation_userDisplayName2avatarDisplayName", new ConcurrentHashMap<>());
            ScarletDiscordSafetyTest.put(listener, "clientLocation_userDisplayName2avatarBundleInfo", new ConcurrentHashMap<>());
            ScarletDiscordSafetyTest.put(listener, "clientLocation_userId2userJoined", new ConcurrentHashMap<>());
            ScarletDiscordSafetyTest.put(listener, "clientLocation_avatarDisplayName2userDisplayName", CollectionMap.setsConcurrent());
            ScarletDiscordSafetyTest.put(listener, "clientLocation_leftUserIds", Collections.synchronizedSet(new HashSet<>()));
            listener.clientLocation_userIdsJoinOrder = Collections.synchronizedSet(new HashSet<>());
            listener.clientLocationPrev_userIds = new HashSet<>();
            listener.clientLocation = LOCATION; listener.isTailerLive = true; listener.seenApiLogLines = true;
        }
        void join(String id) { listener.log_playerJoined(false, TIME, id.equals(USER) ? "Player" : id, id); }
        void drain(ScarletBackgroundWork work)
        {
            CountDownLatch done = new CountDownLatch(1);
            work.submit("synthetic-barrier", 0L, () -> true, allowed -> done.countDown()); waitFor(done);
        }
        public void close()
        {
            vrc.profileRelease.countDown(); vrc.analysisRelease.countDown();
            assertTrue(listener.shutdownBackgroundLookups());
        }
    }

    @Test void blockedProfileLookupCannotDelayFortyFiveRosterRowsOrLeave() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.vrc.blockProfile = true; f.join(USER); waitFor(f.vrc.profileEntered);
            for (int i = 0; i < 44; i++) f.join("usr_synthetic_"+i);
            f.listener.log_playerLeft(false, TIME.plusSeconds(1), "Player", USER);
            assertEquals(46, f.rows.size()); assertEquals(44, f.listener.roster.presentCount());
            assertEquals("leave:"+USER, f.rows.get(45)); assertEquals(0, f.enriched.get());
            f.vrc.profileRelease.countDown(); f.drain(f.listener.playerLookups);
            assertEquals(44, f.enriched.get(), "departed player's blocked result must not apply");
        }
    }

    @Test void blockedAnalysisCannotDelayRosterAndQuitCancelsFurtherRequests() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.vrc.blockAnalysis = true;
            f.listener.log_apiRequest(false, TIME, 1, "GET", ANALYSIS); waitFor(f.vrc.analysisEntered);
            f.join(USER); assertEquals(List.of("join:"+USER), f.rows);
            f.listener.log_userQuit(false, TIME.plusSeconds(1), 1D);
            f.vrc.analysisRelease.countDown(); f.drain(f.listener.avatarLookups);
            assertEquals(0, f.vrc.analysisReads.get(), "quit stops the next API step");
            assertTrue(f.listener.clientLocation_userDisplayName2avatarBundleInfo.isEmpty());
        }
    }

    @Test void oldAnalysisCannotApplyAfterLeaveAndRejoinWithSameAvatar() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.join(USER); f.listener.log_playerSwitchAvatar(false, TIME, "Player", "Synthetic Avatar");
            f.vrc.blockAnalysis = true;
            f.listener.log_apiRequest(false, TIME, 1, "GET", ANALYSIS); waitFor(f.vrc.analysisEntered);
            f.listener.log_playerLeft(false, TIME.plusSeconds(1), "Player", USER); f.join(USER);
            f.vrc.analysisRelease.countDown(); f.drain(f.listener.avatarLookups);
            assertEquals(1, f.vrc.analysisReads.get());
            assertTrue(f.listener.clientLocation_userDisplayName2avatarBundleInfo.isEmpty());
        }
    }

    @Test void analysisAppliesToUnchangedPresentPlayerButNotToSwitchedAvatar() throws Exception
    {
        for (boolean change : new boolean[]{false, true}) try (Fixture f = new Fixture())
        {
            f.join(USER); f.listener.log_playerSwitchAvatar(false, TIME, "Player", "Synthetic Avatar");
            f.vrc.blockAnalysis = true;
            f.listener.log_apiRequest(false, TIME, 1, "GET", ANALYSIS); waitFor(f.vrc.analysisEntered);
            if (change) f.listener.log_playerSwitchAvatar(false, TIME.plusSeconds(1), "Player", "Other Avatar");
            f.vrc.analysisRelease.countDown(); f.drain(f.listener.avatarLookups);
            assertEquals(!change, f.listener.clientLocation_userDisplayName2avatarBundleInfo.containsKey("Player"));
        }
    }

    @Test void unrelatedApiLinesDoNotFillBackgroundQueue() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            for (int i = 0; i < 500; i++)
                f.listener.log_apiRequest(false, TIME, i, "GET", "https://api.vrchat.cloud/api/1/config");
            assertEquals(0, f.listener.avatarLookups.pendingCount()); assertEquals(0, f.vrc.modelReads.get());
            assertTrue(f.listener.seenApiLogLines);
        }
    }

    @Test void exactPrejoinAnalysisPopulatesFirstRowWithoutAvatarProviders() throws Exception
    {
        for (boolean joinedBeforeResponse : new boolean[]{false, true}) try (Fixture f = new Fixture())
        {
            assertNull(f.scarlet.discord, "no provider client or provider consent exists in this fixture");
            f.listener.log_playerSwitchAvatar(false, TIME, "Player", "Synthetic Avatar");
            f.vrc.blockAnalysis = true;
            f.listener.log_apiRequest(false, TIME, 1, "GET", ANALYSIS); waitFor(f.vrc.analysisEntered);
            if (joinedBeforeResponse) f.join(USER);
            f.vrc.analysisRelease.countDown(); f.drain(f.listener.avatarLookups);
            if (!joinedBeforeResponse) f.join(USER);
            assertNotNull(f.playerRows.get(USER).avatarInfo);
            assertEquals("Good", f.playerRows.get(USER).avatarInfo.analysis.getPerformanceRating());
        }
    }

    @Test void prejoinAnalysisCannotReviveAfterThatFirstVisitLeaves() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.listener.log_playerSwitchAvatar(false, TIME, "Player", "Synthetic Avatar");
            f.vrc.blockAnalysis = true;
            f.listener.log_apiRequest(false, TIME, 1, "GET", ANALYSIS); waitFor(f.vrc.analysisEntered);
            f.join(USER); f.listener.log_playerLeft(false, TIME.plusSeconds(1), "Player", USER); f.join(USER);
            f.vrc.analysisRelease.countDown(); f.drain(f.listener.avatarLookups);
            assertNull(f.playerRows.get(USER).avatarInfo);
            assertTrue(f.listener.clientLocation_userDisplayName2avatarBundleInfo.isEmpty());
        }
    }
}

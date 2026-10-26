package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.User;
import net.sybyline.scarlet.ext.AvatarSearch;
import net.sybyline.scarlet.util.CollectionMap;
import net.sybyline.scarlet.util.Func;
import net.sybyline.scarlet.util.Pacer;
import net.sybyline.scarlet.util.tts.TtsService;

/** Real event handlers and background workers, with no app constructors, network, audio or file writes. */
class ScarletStaffModeAlertsTest
{
    static final String USER = "usr_synthetic_alert_target", NAME = "Synthetic Player";
    static final String AVATAR = "Synthetic Watched Avatar", AVATAR_ID = "avtr_synthetic_watched";
    static final String GROUP = "grp_synthetic_configured";
    static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 24, 12, 0);

    static class FakeTts extends TtsService
    {
        List<String> spoken;
        FakeTts() { super(null, null, null); throw new AssertionError("No real audio constructor"); }
        @Override public CompletableFuture<Void> submit(String marker, String text)
        { spoken.add(text); return CompletableFuture.completedFuture(null); }
    }

    static class FakeDiscord extends ScarletDiscordJDA
    {
        volatile boolean consent;
        volatile String[] providers;
        Map<GroupAuditTypeEx, AtomicInteger> emitted;
        FakeDiscord() { super(null, null, null); throw new AssertionError("No real Discord constructor"); }
        @Override String[] getAvatarSearchProviders()
        { return AvatarSearch.effectiveProviders(true, consent, true, providers, null); }
        @Override public boolean isEmitting(GroupAuditTypeEx type) { return true; }
        void count(GroupAuditTypeEx type) { emitted.computeIfAbsent(type, key -> new AtomicInteger()).incrementAndGet(); }
        int countOf(GroupAuditTypeEx type) { return emitted.getOrDefault(type, new AtomicInteger()).get(); }
        @Override public void emitExtendedUserJoin(Scarlet s, LocalDateTime t, String l, String id, String n)
        { count(GroupAuditTypeEx.USER_JOIN); }
        @Override public void emitExtendedUserLeave(Scarlet s, LocalDateTime t, String l, String id, String n)
        { count(GroupAuditTypeEx.USER_LEAVE); }
        @Override public void emitExtendedUserAvatar(Scarlet s, LocalDateTime t, String l, String id, String n, String a, String[] ids)
        { count(GroupAuditTypeEx.USER_AVATAR); }
        @Override public void emitExtendedVtkInitiated(Scarlet s, LocalDateTime t, String l, String id, String n, String actor, String actorName)
        { count(GroupAuditTypeEx.VTK_START); }
    }

    static class FakeData extends ScarletData
    {
        Map<GroupAuditTypeEx, AtomicInteger> events;
        FakeData() { super(null); throw new AssertionError("No real data constructor"); }
        @Override public synchronized String customEvent_new(GroupAuditTypeEx type, OffsetDateTime t, String actor,
                String actorName, String target, String targetName)
        { events.computeIfAbsent(type, key -> new AtomicInteger()).incrementAndGet(); return "synthetic-event"; }
        int countOf(GroupAuditTypeEx type) { return events.getOrDefault(type, new AtomicInteger()).get(); }
    }

    static class FakeDesktop extends ScarletDesktopNotifications
    {
        volatile boolean enabled;
        AtomicInteger avatars, votes;
        FakeDesktop() { super(null); throw new AssertionError("No real notification constructor"); }
        @Override public boolean wants(NotificationType type) { return enabled; }
        @Override public void notifyWatchedAvatar(String name, String id, String avatar, String[] ids,
                ScarletWatchedEntities.WatchedEntity watched, String location)
        { if (enabled) avatars.incrementAndGet(); }
        @Override public void notifyVoteToKick(String name, String id, String actorName, String actorId, String location)
        { if (enabled) votes.incrementAndGet(); }
    }

    static class Listener extends ScarletEventListener
    {
        CountDownLatch searchEntered, searchRelease;
        AtomicInteger searches;
        volatile boolean blockSearch;
        volatile Thread searchThread;
        Listener() { super(null); throw new AssertionError("No real listener constructor"); }
        @Override Color checkPlayer(List<String> advisories, int[] priority, boolean preamble,
                String name, String id, boolean rejoined, User user, BooleanSupplier allowed)
        { return null; }
        // Avatar performance hydration is unrelated to watched-avatar alerts and must never query a provider here.
        @Override void maybeHydrateAvatarInfo(String name, String id, String avatar) {}
        @Override String[] searchAvatar(String avatar, BooleanSupplier allowed)
        {
            FakeDiscord discord = (FakeDiscord)this.scarlet.discord;
            if (!allowed.getAsBoolean() || discord.getAvatarSearchProviders().length == 0) return new String[0];
            searchThread = Thread.currentThread();
            searches.incrementAndGet();
            if (blockSearch)
            {
                searchEntered.countDown();
                ScarletBackgroundPlayerTrackingTest.waitFor(searchRelease);
            }
            // Deliberately return a result even after invalidation, like a non-cancellable remote response.
            return AVATAR.equals(avatar) ? new String[]{AVATAR_ID} : new String[0];
        }
    }

    static ScarletSettings.FileValued<Boolean> flag(boolean value) throws Exception
    {
        ScarletSettings.FileValued<Boolean> flag = ScarletDiscordSafetyTest.inert(ScarletSettings.FileValuedBoolean.class);
        flag.cached = value;
        return flag;
    }

    static class Fixture implements AutoCloseable
    {
        final Scarlet scarlet = ScarletDiscordSafetyTest.inert(Scarlet.class);
        final Listener listener = ScarletDiscordSafetyTest.inert(Listener.class);
        final ScarletBackgroundPlayerTrackingTest.FakeVrc vrc = ScarletDiscordSafetyTest.inert(ScarletBackgroundPlayerTrackingTest.FakeVrc.class);
        final FakeDiscord discord = ScarletDiscordSafetyTest.inert(FakeDiscord.class);
        final FakeData data = ScarletDiscordSafetyTest.inert(FakeData.class);
        final FakeTts tts = ScarletDiscordSafetyTest.inert(FakeTts.class);
        final FakeDesktop desktop = ScarletDiscordSafetyTest.inert(FakeDesktop.class);
        final ScarletWatchedEntities.WatchedEntity watched = new ScarletWatchedEntities.WatchedEntity();
        final Map<String, ScarletUI.ConnectedPlayer> rows = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        Fixture(boolean staffMode, boolean ownGroup) throws Exception
        {
            assertTrue(Features.WATCHED_AVATARS_ENABLED);
            assertTrue(Features.AVATAR_SEARCH_ENABLED);
            vrc.profileReads = new AtomicInteger();
            ScarletDiscordSafetyTest.put(vrc, "groupId", staffMode && !ownGroup ? null : GROUP);
            ScarletDiscordSafetyTest.put(scarlet, "vrc", vrc);
            ScarletDiscordSafetyTest.put(scarlet, "running", true);
            ScarletDiscordSafetyTest.put(scarlet, "staffMode", staffMode);
            ScarletDiscordSafetyTest.put(scarlet, "eventListener", listener);
            discord.consent = true;
            discord.providers = new String[]{"https://synthetic.invalid/vrcx_search"};
            discord.emitted = new ConcurrentHashMap<>();
            data.events = new ConcurrentHashMap<>();
            tts.spoken = Collections.synchronizedList(new ArrayList<>());
            desktop.enabled = true;
            desktop.avatars = new AtomicInteger(); desktop.votes = new AtomicInteger();
            ScarletDiscordSafetyTest.put(scarlet, "discord", discord);
            ScarletDiscordSafetyTest.put(scarlet, "data", data);
            ScarletDiscordSafetyTest.put(scarlet, "ttsService", tts);
            ScarletDiscordSafetyTest.put(scarlet, "desktopNotifications", desktop);
            ScarletStaffList staff = ScarletDiscordSafetyTest.inert(ScarletStaffList.class);
            ScarletDiscordSafetyTest.put(staff, "staffUserIds", Collections.emptySet());
            ScarletDiscordSafetyTest.put(scarlet, "staffList", staff);
            ScarletWatchedEntities<?> entities = ScarletDiscordSafetyTest.inert(ScarletWatchedEntities.class);
            watched.id = AVATAR_ID; watched.message = "Synthetic watched-avatar advisory";
            watched.priority = 40; watched.type = ScarletWatchedEntities.WatchedEntity.Type.NUISANCE;
            ScarletDiscordSafetyTest.put(entities, "watchedEntities", new ConcurrentHashMap<>(Collections.singletonMap(AVATAR_ID, watched)));
            ScarletDiscordSafetyTest.put(scarlet, "watchedAvatars", entities);
            IScarletUI ui = (IScarletUI)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IScarletUI.class}, (proxy, method, args) -> {
                if ("playerJoin".equals(method.getName()))
                    rows.put((String)args[1], ScarletDiscordSafetyTest.inert(ScarletUI.ConnectedPlayer.class));
                if ("playerUpdate".equals(method.getName()) && rows.containsKey(args[1]))
                    ((Func.V1.NE<ScarletUI.ConnectedPlayer>)args[2]).invoke(rows.get(args[1]));
                if ("clearInstance".equals(method.getName())) rows.clear();
                return method.getReturnType() == boolean.class ? false : null;
            });
            ScarletDiscordSafetyTest.put(scarlet, "ui", ui);
            ScarletDiscordSafetyTest.put(listener, "scarlet", scarlet);
            ScarletDiscordSafetyTest.put(listener, "roster", new InstanceRoster());
            ScarletDiscordSafetyTest.put(listener, "lookupSession", new AtomicLong());
            ScarletDiscordSafetyTest.put(listener, "playerVisits", new ConcurrentHashMap<>());
            ScarletDiscordSafetyTest.put(listener, "avatarVersions", new ConcurrentHashMap<>());
            ScarletDiscordSafetyTest.put(listener, "playerLookups", new ScarletBackgroundWork("synthetic-staff-player", 256, errors::add));
            ScarletDiscordSafetyTest.put(listener, "avatarLookups", new ScarletBackgroundWork("synthetic-staff-avatar", 256, errors::add));
            ScarletDiscordSafetyTest.put(listener, "watchedAvatarLookups", new ScarletBackgroundWork("synthetic-watched-avatar", 256, errors::add));
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
            listener.clientLocation = ownGroup ? "wrld_synthetic:100~group("+GROUP+")" : "wrld_synthetic:100";
            listener.isInGroupInstance = ownGroup; listener.isTailerLive = true; listener.seenApiLogLines = true;
            for (String setting : new String[]{"advisoryShowWatchedAvatars", "announceWatchedAvatars", "advisoryShowVotesToKick", "announceVotesToKick"})
                ScarletDiscordSafetyTest.put(listener, setting, flag(true));
            ScarletDiscordSafetyTest.put(listener, "attemptAvatarImageMatch", flag(false));
            listener.searchEntered = new CountDownLatch(1); listener.searchRelease = new CountDownLatch(1);
            listener.searches = new AtomicInteger();
        }

        void join() { listener.log_playerJoined(false, TIME, NAME, USER); }
        void avatar() { listener.log_playerSwitchAvatar(false, TIME.plusSeconds(1), NAME, AVATAR); }
        void vote(boolean preamble) { listener.log_vtkInit(preamble, TIME.plusSeconds(2), NAME, null); }
        void drain()
        {
            CountDownLatch done = new CountDownLatch(1);
            assertTrue(listener.watchedAvatarLookups.submit("synthetic-barrier", 0L, () -> true, allowed -> done.countDown()));
            ScarletBackgroundPlayerTrackingTest.waitFor(done);
            assertTrue(errors.isEmpty(), () -> "Background error: "+errors);
        }
        void noAlertRecords()
        {
            for (GroupAuditTypeEx type : new GroupAuditTypeEx[]{GroupAuditTypeEx.USER_AVATAR, GroupAuditTypeEx.VTK_START})
            {
                assertEquals(0, discord.countOf(type), "Staff-local alert must not send "+type);
                assertEquals(0, data.countOf(type), "Staff-local alert must not persist group event "+type);
            }
        }
        void noLocalAvatarOutput()
        {
            assertTrue(tts.spoken.isEmpty()); assertEquals(0, desktop.avatars.get());
            if (rows.containsKey(USER)) assertNull(rows.get(USER).avatarAdvisory);
        }
        public void close()
        {
            listener.searchRelease.countDown();
            assertTrue(listener.shutdownBackgroundLookups());
            assertTrue(errors.isEmpty(), () -> "Background error: "+errors);
        }
    }

    @Test void staffModeWithoutGroupReceivesWatchedAvatarSpeechAndAdvisory() throws Exception
    {
        try (Fixture f = new Fixture(true, false))
        {
            assertNull(f.vrc.groupId);
            f.join(); f.avatar(); f.drain();
            assertEquals(1, f.listener.searches.get()); assertEquals(1, f.tts.spoken.size());
            assertTrue(f.rows.get(USER).avatarAdvisory.contains(f.watched.message));
            assertEquals(f.watched.priority, f.rows.get(USER).avatarPriority);
            assertEquals(1, f.desktop.avatars.get());
            assertTrue(f.discord.emitted.isEmpty()); assertTrue(f.data.events.isEmpty());
        }
    }

    @Test void slowOptionalHydrationCannotBlockWatchedSpeech() throws Exception
    {
        try (Fixture f = new Fixture(true, false))
        {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            f.listener.avatarLookups.submit("slow-details", 0L, () -> true, allowed -> {
                started.countDown(); ScarletBackgroundPlayerTrackingTest.waitFor(release);
            });
            try
            {
                ScarletBackgroundPlayerTrackingTest.waitFor(started);
                f.join(); f.avatar(); f.drain();
                assertFalse(f.tts.spoken.isEmpty());
            }
            finally { release.countDown(); }
        }
    }

    @Test void silentRescanDoesNotCancelAnInFlightLiveAnnouncement() throws Exception
    {
        try (Fixture f = new Fixture(true, false))
        {
            f.listener.blockSearch = true;
            f.join(); f.avatar(); ScarletBackgroundPlayerTrackingTest.waitFor(f.listener.searchEntered);
            f.listener.clientLocation = "synthetic-current-instance";
            f.listener.rescanCurrentAvatarAdvisories();
            f.listener.searchRelease.countDown(); f.drain();
            assertEquals(1, f.tts.spoken.size());
            assertEquals(1, f.listener.searches.get());
        }
    }

    @Test void rescanRepairsCurrentAdvisoryWithoutReplayingSpeechOrDiscordEvents() throws Exception
    {
        try (Fixture f = new Fixture(true, false))
        {
            f.discord.consent = false;
            f.join(); f.avatar(); f.drain(); f.noLocalAvatarOutput();
            f.discord.consent = true;
            f.listener.clientLocation = "synthetic-current-instance";
            f.listener.rescanCurrentAvatarAdvisories(); f.drain();
            assertNotNull(f.rows.get(USER).avatarAdvisory);
            assertTrue(f.tts.spoken.isEmpty()); assertEquals(0, f.desktop.avatars.get());
            f.noAlertRecords();
        }
    }

    @Test void rejoinDoesNotAnnounceTheAvatarFromThePreviousVisit() throws Exception
    {
        try (Fixture f = new Fixture(true, false))
        {
            f.join(); f.avatar(); f.drain(); f.tts.spoken.clear();
            f.listener.log_playerLeft(false, TIME.plusSeconds(3), NAME, USER);
            int searches = f.listener.searches.get();
            f.join(); f.drain();
            assertEquals(searches, f.listener.searches.get());
            assertTrue(f.tts.spoken.isEmpty());
            f.avatar(); f.drain(); assertFalse(f.tts.spoken.isEmpty());
        }
    }

    @Test void watchedAvatarObservedBeforeJoinIsCheckedWhenStaffPlayerJoins() throws Exception
    {
        try (Fixture f = new Fixture(true, false))
        {
            f.avatar(); f.drain(); assertEquals(0, f.listener.searches.get());
            f.join(); f.drain();
            assertEquals(1, f.listener.searches.get()); assertEquals(1, f.tts.spoken.size());
            assertNotNull(f.rows.get(USER).avatarAdvisory); f.noAlertRecords();
        }
    }

    @Test void staffModeGetsVtkWithOrWithoutKnownActorAndWithoutGroup() throws Exception
    {
        for (boolean knownActor : new boolean[]{false, true}) try (Fixture f = new Fixture(true, false))
        {
            if (knownActor) f.listener.clientLocation_userDisplayName2userId.put("Synthetic Actor", "usr_synthetic_actor");
            f.listener.log_vtkInit(false, TIME, NAME, knownActor ? "Synthetic Actor" : null);
            assertEquals(1, f.tts.spoken.size()); assertTrue(f.tts.spoken.get(0).contains(NAME));
            if (knownActor) assertTrue(f.tts.spoken.get(0).contains("Synthetic Actor"));
            assertEquals(1, f.desktop.votes.get()); f.noAlertRecords();
        }
    }

    @Test void staffWithRememberedConfiguredGroupStillKeepsAlertsLocal() throws Exception
    {
        try (Fixture f = new Fixture(true, true))
        {
            f.join(); f.avatar(); f.vote(false); f.drain();
            assertEquals(2, f.tts.spoken.size()); assertNotNull(f.rows.get(USER).avatarAdvisory);
            assertEquals(1, f.desktop.avatars.get()); assertEquals(1, f.desktop.votes.get());
            // Existing USER_JOIN behavior is outside this fix; avatar/VTK must not become group records.
            f.noAlertRecords();
        }
    }

    @Test void staffVisitingDifferentGroupReceivesLocalAlertsWithoutGroupRecords() throws Exception
    {
        try (Fixture f = new Fixture(true, false))
        {
            ScarletDiscordSafetyTest.put(f.vrc, "groupId", GROUP);
            f.listener.clientLocation = "wrld_synthetic:200~group(grp_synthetic_other)";
            assertFalse(f.listener.isInGroupInstance);
            f.join(); f.avatar(); f.vote(false); f.drain();
            assertEquals(2, f.tts.spoken.size());
            assertNotNull(f.rows.get(USER).avatarAdvisory);
            assertEquals(1, f.desktop.avatars.get()); assertEquals(1, f.desktop.votes.get());
            f.noAlertRecords();
            assertTrue(f.discord.emitted.isEmpty()); assertTrue(f.data.events.isEmpty());
        }
    }

    @Test void ordinaryModeStillSuppressesAlertsOutsideConfiguredGroup() throws Exception
    {
        try (Fixture f = new Fixture(false, false))
        {
            f.join(); f.avatar(); f.vote(false); f.drain();
            assertEquals(0, f.listener.searches.get()); f.noLocalAvatarOutput();
            assertEquals(0, f.desktop.votes.get());
            assertTrue(f.discord.emitted.isEmpty()); assertTrue(f.data.events.isEmpty());
        }
    }

    @Test void ordinaryConfiguredGroupRetainsLocalAndGroupOutputs() throws Exception
    {
        try (Fixture f = new Fixture(false, true))
        {
            f.join(); f.avatar(); f.vote(false); f.drain();
            assertEquals(2, f.tts.spoken.size()); assertNotNull(f.rows.get(USER).avatarAdvisory);
            for (GroupAuditTypeEx type : new GroupAuditTypeEx[]{GroupAuditTypeEx.USER_AVATAR, GroupAuditTypeEx.VTK_START})
            { assertEquals(1, f.discord.countOf(type)); assertEquals(1, f.data.countOf(type)); }
            assertEquals(1, f.desktop.avatars.get()); assertEquals(1, f.desktop.votes.get());
        }
    }

    @Test void historicalEventsDoNotProduceStaffAlertSpeech() throws Exception
    {
        try (Fixture f = new Fixture(true, false))
        {
            f.join();
            f.listener.log_playerSwitchAvatar(true, TIME, NAME, AVATAR);
            f.vote(true); f.drain();
            assertEquals(0, f.listener.searches.get()); f.noLocalAvatarOutput();
            assertEquals(0, f.desktop.votes.get()); f.noAlertRecords();
        }
    }

    @Test void bothVtkTogglesRemainNecessaryForSpeech() throws Exception
    {
        for (String setting : new String[]{"advisoryShowVotesToKick", "announceVotesToKick"})
            try (Fixture f = new Fixture(true, false))
            {
                ScarletDiscordSafetyTest.put(f.listener, setting, flag(false));
                f.vote(false); assertTrue(f.tts.spoken.isEmpty()); f.noAlertRecords();
            }
    }

    @Test void avatarSpeechAndAdvisoryTogglesAreNotBypassed() throws Exception
    {
        for (String setting : new String[]{"advisoryShowWatchedAvatars", "announceWatchedAvatars"})
            try (Fixture f = new Fixture(true, false))
            {
                ScarletDiscordSafetyTest.put(f.listener, setting, flag(false));
                f.join(); f.avatar(); f.drain();
                assertTrue(f.tts.spoken.isEmpty());
                assertEquals("advisoryShowWatchedAvatars".equals(setting), f.rows.get(USER).avatarAdvisory == null);
                f.noAlertRecords();
            }
    }

    @Test void silentWatchedAvatarIsNotAnnouncedOrDisplayed() throws Exception
    {
        try (Fixture f = new Fixture(true, false))
        {
            f.watched.silent = true; f.join(); f.avatar(); f.drain();
            f.noLocalAvatarOutput(); f.noAlertRecords();
        }
    }

    @Test void noConsentOrNoSelectedProvidersStillPreventsWatchedAvatarLookup() throws Exception
    {
        for (boolean noConsent : new boolean[]{false, true}) try (Fixture f = new Fixture(true, false))
        {
            if (noConsent) f.discord.consent = false; else f.discord.providers = new String[0];
            f.join(); f.avatar(); f.drain();
            assertEquals(0, f.listener.searches.get()); f.noLocalAvatarOutput(); f.noAlertRecords();
        }
    }

    @Test void blockedAvatarLookupDoesNotHoldUpAnotherRosterRow() throws Exception
    {
        try (Fixture f = new Fixture(true, false))
        {
            f.listener.blockSearch = true; f.join(); f.avatar();
            ScarletBackgroundPlayerTrackingTest.waitFor(f.listener.searchEntered);
            f.listener.log_playerJoined(false, TIME.plusSeconds(2), "Second Synthetic Player", "usr_synthetic_second");
            assertEquals(2, f.rows.size()); assertEquals(2, f.listener.roster.presentCount());
            assertNotEquals(Thread.currentThread(), f.listener.searchThread);
            f.listener.searchRelease.countDown(); f.drain();
            assertEquals(1, f.tts.spoken.size()); f.noAlertRecords();
        }
    }

    @Test void lateAvatarResultsCannotSurviveConsentScopeVisitOrSessionChanges() throws Exception
    {
        for (String change : new String[]{"consent", "providers", "staff-mode", "leave", "world-left", "avatar", "stopped"})
            try (Fixture f = new Fixture(true, false))
            {
                f.listener.blockSearch = true; f.join(); f.avatar();
                ScarletBackgroundPlayerTrackingTest.waitFor(f.listener.searchEntered);
                switch (change)
                {
                case "consent": f.discord.consent = false; break;
                case "providers": f.discord.providers = new String[]{"https://other-synthetic.invalid/vrcx_search"}; break;
                case "staff-mode": f.scarlet.staffMode = false; break;
                case "leave": f.listener.log_playerLeft(false, TIME.plusSeconds(2), NAME, USER); break;
                case "world-left": f.listener.log_userLeft(false, TIME.plusSeconds(2)); break;
                case "avatar": f.listener.log_playerSwitchAvatar(false, TIME.plusSeconds(2), NAME, "Unwatched Synthetic Avatar"); break;
                case "stopped": f.scarlet.running = false; break;
                default: fail(change);
                }
                f.listener.searchRelease.countDown(); f.drain();
                f.noLocalAvatarOutput(); f.noAlertRecords();
            }
    }
}

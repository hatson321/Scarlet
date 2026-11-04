package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletLivePlayerlist.MAX_DESCRIPTION;
import static net.sybyline.scarlet.ScarletLivePlayerlist.MAX_FAILURES;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.sybyline.scarlet.ScarletLivePlayerlist.*;

class ScarletLivePlayerlistCoreTest
{
    static class Clock implements Scheduler
    {
        static class Task implements Comparable<Task>
        {
            long when, order;
            Runnable run;
            Task(long when, long order, Runnable run) { this.when = when; this.order = order; this.run = run; }
            public int compareTo(Task other)
            { int result = Long.compare(this.when, other.when); return result == 0 ? Long.compare(this.order, other.order) : result; }
        }
        long now, order;
        boolean stopped;
        PriorityQueue<Task> tasks = new PriorityQueue<>();
        public void execute(Runnable action) { schedule(action, 0); }
        public void schedule(Runnable action, long delay) { if (!stopped) tasks.add(new Task(now + delay, order++, action)); }
        public void stop() { stopped = true; tasks.clear(); }
        void advance(long milliseconds)
        {
            long until = now + milliseconds;
            int safety = 0;
            while (!tasks.isEmpty() && tasks.peek().when <= until)
            {
                if (++safety > 2000) throw new AssertionError("Unbounded scheduler activity");
                Task next = tasks.remove();
                now = next.when;
                next.run.run();
            }
            now = until;
        }
    }

    static class Sent
    {
        final Route route;
        final String id, nonce;
        final MessageEmbed embed;
        final CompletableFuture<String> result = new CompletableFuture<>();
        Sent(Route route, String id, String nonce, MessageEmbed embed)
        { this.route = route; this.id = id; this.nonce = nonce; this.embed = embed; }
    }

    static class Fake implements Environment
    {
        List<Route> routes = new ArrayList<>(Collections.singletonList(new Route("guild", "normal", false)));
        Set<String> secrets = new HashSet<>();
        List<Sent> sent = new ArrayList<>();
        SavedMessages stored = new SavedMessages();
        boolean saveFails, automatic = true;
        int writes, recoveries;
        String recovered;
        public List<Route> routes() { return routes; }
        public boolean secret(String user) { return secrets.contains(user); }
        public boolean staff(String user) { return user.equals("usr_secret"); }
        public SavedMessages load() { return stored; }
        public boolean save(SavedMessages saved) { writes++; if (saveFails) return false; stored = saved; return true; }
        public CompletableFuture<String> recover(Route route, String nonce) { recoveries++; return CompletableFuture.completedFuture(recovered); }
        public CompletableFuture<String> send(Route route, String id, String nonce, MessageEmbed embed)
        {
            Sent next = new Sent(route, id, nonce, embed);
            sent.add(next);
            if (automatic) next.result.complete(id == null ? "message-" + sent.size() : id);
            return next.result;
        }
        public boolean missing(Throwable error) { return error instanceof Missing; }
        Sent last() { return sent.get(sent.size() - 1); }
    }
    static class Missing extends RuntimeException { private static final long serialVersionUID = 1L; }
    static PlayerSnapshot player(String user, String avatar)
    { return new PlayerSnapshot(user, user, OffsetDateTime.parse("2026-10-04T11:00:00-05:00"), avatar); }
    static final String LOCATION = "wrld_00000000-0000-0000-0000-000000000001:12345~group(grp_00000000-0000-0000-0000-000000000002)";

    @Test void routesDirectlyWithoutMonitorAndFiltersSecretsBeforeCounting()
    {
        Fake env = new Fake();
        env.routes.add(new Route("guild", "secret", true));
        env.secrets.add("usr_secret");
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Arrays.asList(player("usr_public", "Public"), player("usr_secret", "SecretAvatar")));
        clock.advance(2000);
        assertEquals(2, env.sent.size());
        Sent publicMessage = env.sent.get(0), secretMessage = env.sent.get(1);
        assertEquals("normal", publicMessage.route.channel);
        assertTrue(publicMessage.embed.getDescription().contains("1 observed present"));
        assertFalse(publicMessage.embed.getDescription().contains("usr_secret"));
        assertFalse(publicMessage.embed.getDescription().contains("SecretAvatar"));
        assertTrue(secretMessage.embed.getDescription().contains("2 observed present"));
        assertTrue(secretMessage.embed.getDescription().contains("usr_secret"));
        assertNotNull(publicMessage.embed.getUrl());
        assertEquals(2, env.stored.messages.size());
    }

    @Test void secretOnlyDestinationWorksWithoutPublicRoute()
    {
        Fake env = new Fake();
        env.routes = Collections.singletonList(new Route("guild", "secret", true));
        env.secrets.add("usr_secret");
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_secret", "Avatar")));
        clock.advance(2000);
        assertEquals(1, env.sent.size());
        assertTrue(env.last().embed.getDescription().contains("usr_secret"));
    }

    @Test void sharedAudienceChannelNeverReceivesSecretIdentities()
    {
        Fake env = new Fake();
        env.routes.add(new Route("guild", "normal", true));
        env.secrets.add("usr_secret");
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_secret", "SECRET")));
        clock.advance(2000);
        assertEquals(1, env.sent.size());
        assertFalse(env.last().embed.getDescription().contains("usr_secret"));
        assertFalse(env.last().embed.getDescription().contains("SECRET"));
    }

    @Test void continuousChangesCannotPostponeFirstFlush()
    {
        Fake env = new Fake();
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        for (int index = 0; index < 4; index++)
        {
            core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar" + index)));
            clock.advance(500);
        }
        assertEquals(1, env.sent.size());
        assertTrue(env.last().embed.getDescription().contains("Avatar3"));
        assertTrue(env.last().embed.getDescription().contains("<t:" + java.time.Instant.parse("2026-10-04T16:00:00Z").getEpochSecond() + ":R>"));
    }

    @Test void restartEditsDurableMessageAfterDelayedInitialObservation()
    {
        Fake env = new Fake();
        Clock first = new Clock();
        ScarletLivePlayerlist original = new ScarletLivePlayerlist(env, first);
        original.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Old")));
        first.advance(2000);
        String id = env.stored.messages.get(0).message;
        first.stop();
        Clock restarted = new Clock();
        ScarletLivePlayerlist resumed = new ScarletLivePlayerlist(env, restarted);
        restarted.advance(90_000);
        assertEquals(1, env.sent.size(), "Unknown startup observation must not close a recovered message");
        resumed.reconcile(LOCATION, Collections.singletonList(player("usr_public", "New")));
        restarted.advance(2000);
        assertEquals(id, env.last().id);
        assertTrue(env.last().embed.getDescription().contains("New"));
        assertFalse(env.last().embed.getDescription().contains("Old"));
    }

    @Test void knownEmptyObservationRetiresRecoveredAddress()
    {
        Fake env = new Fake();
        Clock first = new Clock();
        ScarletLivePlayerlist initial = new ScarletLivePlayerlist(env, first);
        initial.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Old")));
        first.advance(2000);
        first.stop();
        Clock resumed = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, resumed);
        core.reconcile(null, Collections.emptyList());
        resumed.advance(0);
        assertTrue(env.last().embed.getFooter().getText().startsWith("Observation ended"));
        assertNull(env.last().embed.getUrl());
        assertTrue(env.stored.messages.isEmpty());
    }

    @Test void inFlightEditFinishesBeforeFinalisationAndCannotRestoreLiveFooter()
    {
        Fake env = new Fake();
        env.automatic = false;
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        clock.advance(2000);
        core.onLocalSessionEnded(LOCATION);
        clock.advance(0);
        assertEquals(1, env.sent.size(), "Finalisation must wait for the create completion");
        env.last().result.complete("existing-message");
        clock.advance(0);
        assertEquals(2, env.sent.size());
        assertEquals("existing-message", env.last().id);
        assertTrue(env.last().embed.getFooter().getText().startsWith("Observation ended"));
        env.last().result.complete("existing-message");
        clock.advance(60000);
        assertEquals(2, env.sent.size());
        assertTrue(env.stored.messages.isEmpty());
    }

    @Test void disablingFinalisesAndDoesNotResumeFromLaterRosterUpdates()
    {
        Fake env = new Fake();
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "First")));
        clock.advance(2000);
        env.routes.clear();
        core.reconcile();
        clock.advance(0);
        assertTrue(env.last().embed.getFooter().getText().startsWith("Observation ended"));
        core.reconcile(LOCATION, Collections.singletonList(player("usr_new", "New")));
        clock.advance(60000);
        assertEquals(2, env.sent.size());
    }

    @Test void destinationChangeFinalisesOldAndCreatesNewWithoutReusingItsId()
    {
        Fake env = new Fake();
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        clock.advance(2000);
        env.routes = Collections.singletonList(new Route("guild", "replacement", false));
        core.reconcile();
        clock.advance(2000);
        assertEquals(3, env.sent.size());
        assertEquals("normal", env.sent.get(1).route.channel);
        assertNotNull(env.sent.get(1).id);
        assertEquals("replacement", env.last().route.channel);
        assertNull(env.last().id);
        assertEquals(1, env.stored.messages.size());
    }

    @Test void declassifyingSecretDestinationReplacesItsContentWithFilteredRoster()
    {
        Fake env = new Fake();
        env.routes = Collections.singletonList(new Route("guild", "destination", true));
        env.secrets.add("usr_secret");
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_secret", "PRIVATE")));
        clock.advance(2000);
        env.routes = Collections.singletonList(new Route("guild", "destination", false));
        core.reconcile();
        clock.advance(2000);
        assertNotNull(env.last().id);
        assertFalse(env.last().embed.getDescription().contains("PRIVATE"));
        assertFalse(env.last().embed.getDescription().contains("usr_secret"));
    }

    @Test void inFlightSecretCreateCannotRestoreSecretAudienceAfterReassignment()
    {
        Fake env = new Fake();
        env.routes = Collections.singletonList(new Route("guild", "destination", true));
        env.secrets.add("usr_secret");
        env.automatic = false;
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_secret", "PRIVATE")));
        clock.advance(2000);
        assertTrue(env.last().embed.getDescription().contains("PRIVATE"));
        env.routes = Collections.singletonList(new Route("guild", "destination", false));
        core.reconcile();
        clock.advance(0);
        assertEquals(1, env.sent.size());
        env.last().result.complete("in-flight-message");
        clock.advance(2000);
        assertEquals(2, env.sent.size());
        assertEquals("in-flight-message", env.last().id);
        assertFalse(env.last().embed.getDescription().contains("PRIVATE"));
        assertFalse(env.last().embed.getDescription().contains("usr_secret"));
        env.last().result.complete("in-flight-message");
        clock.advance(0);
        assertFalse(env.stored.messages.get(0).secret);
    }

    @Test void legacyThreadMessageIsRetiredNotAdoptedAndDirectDestinationStillWorks()
    {
        ScarletData.LiveInstancesMetadata metadata = new ScarletData.LiveInstancesMetadata();
        metadata.location2instanceEmbedMessage = new HashMap<>();
        ScarletData.InstanceEmbedMessage old = new ScarletData.InstanceEmbedMessage();
        old.guildSnowflake = "guild";
        old.threadSnowflake = "old-thread";
        old.playerlistMessageSnowflake = "old-player-list";
        metadata.location2instanceEmbedMessage.put(LOCATION, old);
        Fake env = new Fake();
        env.stored = ScarletLivePlayerlist.legacyMessages(metadata);
        assertTrue(env.stored.upgradeImport);
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Current")));
        clock.advance(2000);
        assertEquals(2, env.sent.size());
        Sent retired = env.sent.get(0), active = env.sent.get(1);
        assertEquals("old-thread", retired.route.channel);
        assertEquals("old-player-list", retired.id);
        assertTrue(retired.embed.getFooter().getText().startsWith("Observation ended"));
        assertFalse(retired.embed.getDescription().contains("usr_public"));
        assertNull(retired.embed.getUrl());
        assertEquals("normal", active.route.channel);
        assertNull(active.id);
        assertTrue(active.embed.getDescription().contains("Current"));
        assertEquals("old-player-list", old.playerlistMessageSnowflake, "Existing monitor metadata is not rewritten");
        assertEquals(1, env.stored.messages.size());
        assertFalse(env.stored.messages.get(0).legacy);
        assertFalse(env.stored.upgradeImport, "Once the new store exists, legacy import must not repeat");
    }

    @Test void ambiguousCreateRecoversByNonceWithoutPostingAgain()
    {
        Fake env = new Fake();
        env.automatic = false;
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        clock.advance(2000);
        assertNotNull(env.stored.messages.get(0).nonce, "Intent must exist before network completion");
        env.last().result.completeExceptionally(new TimeoutException("Synthetic uncertain delivery"));
        env.recovered = "actually-posted";
        clock.advance(2000);
        assertEquals(1, env.recoveries);
        assertEquals(2, env.sent.size());
        assertEquals("actually-posted", env.last().id);
    }

    @Test void demonstrablePreDispatchFailureCanRetryAfterAvailabilityReturns()
    {
        Fake env = new Fake()
        {
            int attempts;
            @Override public CompletableFuture<String> send(Route route, String id, String nonce, MessageEmbed embed)
            {
                if (attempts++ == 0) throw new BeforeDispatchFailure("Synthetic destination unavailable before dispatch");
                return super.send(route, id, nonce, embed);
            }
        };
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        clock.advance(2000);
        assertTrue(env.stored.messages.isEmpty(), "A known rejected intent must not survive as an ambiguous post");
        clock.advance(2000);
        assertEquals(1, env.sent.size());
        assertEquals(0, env.recoveries);
    }

    @Test void periodicPrivacyRefreshContinuesAfterOneSourceFailure()
    {
        Clock clock = new Clock();
        Fake env = new Fake()
        {
            boolean once = true;
            @Override public List<Route> routes()
            {
                if (clock.now >= 30000 && once) { once = false; throw new IllegalStateException("Synthetic transient settings read failure"); }
                return super.routes();
            }
        };
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        // Initial create succeeds; the next periodic route read fails once.
        clock.advance(2000);
        clock.advance(62000);
        assertFalse(env.sent.isEmpty());
        assertEquals(2, env.sent.size(), "Second periodic tick must still refresh the existing message");
    }

    @Test void flushPreparationFailureRetriesWithoutAmbiguousNetworkRecovery()
    {
        Fake env = new Fake()
        {
            boolean once = true;
            @Override public List<Route> routes()
            {
                if (writes > 0 && once) { once = false; throw new IllegalStateException("Synthetic preparation failure after intent save"); }
                return super.routes();
            }
        };
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        clock.advance(2000);
        assertTrue(env.sent.isEmpty());
        assertTrue(env.stored.messages.isEmpty(), "Failed preparation never dispatched its recorded intent");
        clock.advance(2000);
        assertEquals(1, env.sent.size());
        assertEquals(0, env.recoveries);
    }

    @Test void ambiguousUnrecoverableCreateStopsBoundedlyWithoutDuplicates()
    {
        Fake env = new Fake();
        env.automatic = false;
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        clock.advance(2000);
        env.last().result.completeExceptionally(new TimeoutException("Synthetic uncertain delivery"));
        clock.advance(180000);
        assertEquals(1, env.sent.size());
        assertTrue(env.recoveries <= MAX_FAILURES);
        assertTrue(env.stored.messages.get(0).ambiguous);
    }

    @Test void serverErrorsAreAmbiguousAndOnlyKnownClientRejectionsPermitReposting()
    {
        assertFalse(ScarletLivePlayerlist.definitiveRejection(net.dv8tion.jda.api.requests.ErrorResponse.SERVER_ERROR, 500));
        assertFalse(ScarletLivePlayerlist.definitiveRejection(net.dv8tion.jda.api.requests.ErrorResponse.MISSING_PERMISSIONS, 503));
        assertFalse(ScarletLivePlayerlist.definitiveRejection(net.dv8tion.jda.api.requests.ErrorResponse.SERVER_ERROR, 400));
        assertFalse(ScarletLivePlayerlist.definitiveRejection(null, -1));
        assertTrue(ScarletLivePlayerlist.definitiveRejection(net.dv8tion.jda.api.requests.ErrorResponse.MISSING_PERMISSIONS, 403));
        assertTrue(ScarletLivePlayerlist.definitiveRejection(net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_CHANNEL, 404));
        assertTrue(ScarletLivePlayerlist.definitiveRejection(net.dv8tion.jda.api.requests.ErrorResponse.INVALID_FORM_BODY, 400));
        assertTrue(ScarletLivePlayerlist.definitiveRejection(net.dv8tion.jda.api.requests.ErrorResponse.CHANNEL_WRITE_RATE_LIMIT, 429));
    }

    @Test void crashWithUnconfirmedIntentDoesNotBlindlyResend()
    {
        Fake env = new Fake();
        env.automatic = false;
        Clock first = new Clock();
        ScarletLivePlayerlist initial = new ScarletLivePlayerlist(env, first);
        initial.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        first.advance(2000);
        first.stop();
        Clock resumed = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, resumed);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        resumed.advance(180000);
        assertEquals(1, env.sent.size());
        assertTrue(env.recoveries > 0);
    }

    @Test void deletedMessageIsRecreatedButMissingFinalMessageIsNot()
    {
        Fake env = new Fake();
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        clock.advance(2000);
        env.automatic = false;
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Updated")));
        clock.advance(2000);
        env.last().result.completeExceptionally(new Missing());
        env.automatic = true;
        clock.advance(2000);
        assertNull(env.last().id, "Confirmed deleted message may be recreated");
        env.automatic = false;
        core.onLocalSessionEnded(LOCATION);
        clock.advance(0);
        env.last().result.completeExceptionally(new Missing());
        int count = env.sent.size();
        clock.advance(2000);
        assertEquals(count, env.sent.size());
        assertTrue(env.stored.messages.isEmpty());
    }

    @Test void failedFinalisationIsRetriedWithoutLosingAddress()
    {
        Fake env = new Fake();
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        clock.advance(2000);
        env.automatic = false;
        core.onLocalSessionEnded(LOCATION);
        clock.advance(0);
        String id = env.last().id;
        env.last().result.completeExceptionally(new RuntimeException("Temporary edit failure"));
        assertEquals(id, env.stored.messages.get(0).message);
        env.automatic = true;
        clock.advance(2000);
        assertEquals(id, env.last().id);
        assertTrue(env.last().embed.getFooter().getText().startsWith("Observation ended"));
        assertTrue(env.stored.messages.isEmpty());
    }

    @Test void corruptStoreIsNotOverwrittenAndNoPostingOccurs()
    {
        Fake env = new Fake();
        env.stored = null;
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        clock.advance(120000);
        assertEquals(0, env.writes);
        assertTrue(env.sent.isEmpty());
    }

    @Test void failedPersistenceCannotLoseAcknowledgedIdOrProduceSecondCreate()
    {
        Fake env = new Fake();
        env.automatic = false;
        Clock clock = new Clock();
        ScarletLivePlayerlist core = new ScarletLivePlayerlist(env, clock);
        core.reconcile(LOCATION, Collections.singletonList(player("usr_public", "Avatar")));
        clock.advance(2000);
        env.saveFails = true;
        env.last().result.complete("acknowledged");
        clock.advance(2000);
        assertEquals(1, env.sent.size());
        env.saveFails = false;
        env.automatic = true;
        clock.advance(4000);
        assertEquals("acknowledged", env.last().id);
        assertEquals("acknowledged", env.stored.messages.get(0).message);
    }

    @Test void rendererOmitsWholeRowsAndReportsOnlyVisibleOmissions()
    {
        State state = new State(LOCATION, new Route("guild", "normal", false));
        for (int index = 0; index < 150; index++)
        {
            PlayerSnapshot player = new PlayerSnapshot("usr_visible-" + index, "x".repeat(200), OffsetDateTime.now(), "y".repeat(200));
            state.players.put(player.userId, new ObservedPlayer(player));
        }
        state.players.put("usr_secret", new ObservedPlayer(player("usr_secret", "PRIVATE")));
        MessageEmbed embed = ScarletLivePlayerlist.render(state, false, id -> id.equals("usr_secret"), id -> false, id -> true);
        assertTrue(embed.getDescription().length() <= MAX_DESCRIPTION);
        assertTrue(embed.getDescription().contains("150 observed present"));
        assertTrue(embed.getDescription().contains("additional observed rows omitted"));
        assertFalse(embed.getDescription().contains("PRIVATE"));
        for (String row : embed.getDescription().split("\n"))
            if (row.contains("](https://")) assertTrue(row.endsWith("*"), "Whole avatar row must be preserved");
        assertTrue(embed.getDescription().contains("⚠"));
    }
}

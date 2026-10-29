package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordReadinessTest.proxy;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.PublicProfile;
import io.github.vrchatapi.model.User;
import io.github.vrchatapi.model.UserStatus;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.sybyline.scarlet.ext.ExtendedUserAgent;

/** Inert objects, synthetic caches and transport proxies only; no application/API/game startup. */
class ScarletVoteKickProfileIntegrationTest
{
    static final String TARGET = "usr_00000000-0000-0000-0000-000000000001";
    static final String ACTOR = "usr_00000000-0000-0000-0000-000000000002";
    static final String ACCOUNT = "usr_00000000-0000-0000-0000-000000000003";
    static final String GROUP = "grp_00000000-0000-0000-0000-000000000004";
    static final String GUILD = "100", PARENT = "200", THREAD = "300";
    static final String LOCATION = "wrld_synthetic:123";
    static final String IMAGE = "https://example.invalid/synthetic-profile.png";

    static PublicProfile profile()
    {
        return new PublicProfile().id(TARGET).bio("Synthetic biography").pronouns("they/them")
            .status(UserStatus.ACTIVE).statusDescription("Synthetic status").currentAvatarImageUrl(IMAGE);
    }

    static void await(CountDownLatch latch)
    {
        try { assertTrue(latch.await(5L, TimeUnit.SECONDS), "Synthetic worker did not reach the expected state"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }

    static String field(MessageEmbed embed, String name)
    {
        return embed.getFields().stream().filter(f -> name.equals(f.getName()))
            .map(MessageEmbed.Field::getValue).findFirst().orElse(null);
    }

    static RuntimeException privateFailure()
    {
        return new RuntimeException()
        {
            private static final long serialVersionUID = 1L;
            @Override public String getMessage() { throw new AssertionError("Do not read private failure details"); }
            @Override public String toString() { throw new AssertionError("Do not render private failure details"); }
        };
    }

    static class UserCache extends ScarletJsonCache<User>
    {
        User value;
        int reads;
        RuntimeException failure;
        UserCache() { super("synthetic", User.class); throw new AssertionError("Never construct a real cache"); }
        @Override public User get(String id)
        {
            assertEquals(TARGET, id); reads++;
            if (failure != null) throw failure;
            return value;
        }
    }

    static class ProfileCache extends ScarletJsonCache<PublicProfile>
    {
        PublicProfile value;
        int reads;
        RuntimeException failure;
        ProfileCache() { super("synthetic", PublicProfile.class); throw new AssertionError("Never construct a real cache"); }
        @Override public PublicProfile get(String id)
        {
            assertEquals(TARGET, id); reads++;
            if (failure != null) throw failure;
            return value;
        }
    }

    static class OfflineVRChat extends ScarletVRChat
    {
        AtomicInteger lookups;
        Function<String, PublicProfile> lookup;
        BooleanSupplier delivered;
        OfflineVRChat() { super(null, null, null); throw new AssertionError("Never construct an API client"); }
        @Override public PublicProfile getPublicProfile(String id, long minEpoch)
        {
            assertEquals(TARGET, id);
            assertEquals(ScarletJsonCache.ALWAYS_PREFER_CACHED, minEpoch);
            assertTrue(delivered.getAsBoolean(), "A profile lookup must only follow acknowledged base delivery");
            lookups.incrementAndGet();
            return lookup.apply(id);
        }
        @Override public User getUser(String id) { throw new AssertionError("No full-user lookup is allowed"); }
        @Override public User getUser(String id, long epoch) { throw new AssertionError("No full-user lookup is allowed"); }
    }

    static class CapturingDiscord extends ScarletDiscordJDA
    {
        Fixture fixture;
        volatile long now;
        CapturingDiscord() { super(null, null, null); throw new AssertionError("Never construct Discord"); }
        @Override long voteKickProfileNanoTime() { return this.now; }
        @Override void condEmitEx(boolean training, GroupAuditTypeEx type, boolean log, boolean secret, String location, CondEmit emit)
        {
            assertEquals(GroupAuditTypeEx.VTK_START, type);
            assertTrue(log); assertFalse(secret); assertEquals(LOCATION, location);
            fixture.training.add(training);
            Message returned = emit.emit(THREAD, fixture.guild, fixture.channel);
            assertNotNull(returned, "Preserve the synchronous message result for delivery-ID logging");
            fixture.returnedIds.add(returned.getId());
        }
    }

    static class Fixture implements AutoCloseable
    {
        final CapturingDiscord discord = inert(CapturingDiscord.class);
        final Scarlet scarlet = inert(Scarlet.class);
        final OfflineVRChat vrc = inert(OfflineVRChat.class);
        final UserCache users = inert(UserCache.class);
        final ProfileCache profiles = inert(ProfileCache.class);
        final Map<String, String> routes = new ConcurrentHashMap<>();
        final List<MessageEmbed> base = new CopyOnWriteArrayList<>(), edits = new CopyOnWriteArrayList<>();
        final List<String> returnedIds = new ArrayList<>(), editIds = new CopyOnWriteArrayList<>();
        final List<List<String>> buttons = new ArrayList<>();
        final List<Boolean> training = new ArrayList<>();
        final AtomicInteger acknowledged = new AtomicInteger(), attempts = new AtomicInteger();
        final AtomicReference<BooleanSupplier> dispatchGuard = new AtomicReference<>();
        volatile RuntimeException sendFailure, editFailure;
        volatile Runnable afterBase = () -> {}, beforeEditDispatch = () -> {};
        volatile CountDownLatch editsExpected;
        volatile String actualGuild = GUILD;
        final Guild guild;
        final GuildMessageChannel channel;

        Fixture() throws Exception
        {
            this.discord.fixture = this;
            this.vrc.lookups = new AtomicInteger();
            this.vrc.lookup = id -> profile();
            this.vrc.delivered = () -> this.acknowledged.get() > 0;
            put(vrc, "groupId", GROUP); put(vrc, "currentUserId", ACCOUNT);
            put(vrc, "cachedUsers", users); put(vrc, "cachedPublicProfiles", profiles);
            put(scarlet, "vrc", vrc); put(scarlet, "discord", discord);
            put(discord, "scarlet", scarlet); put(discord, "guildSf", GUILD);
            put(discord, "jda", proxy(JDA.class, (p, m, a) -> { throw new AssertionError("No gateway calls are allowed"); }));
            routes.put(GroupAuditTypeEx.VTK_START.id, PARENT);
            put(discord, "auditExType2channelSf", routes);
            guild = proxy(Guild.class, (p, m, a) -> {
                if (m.getName().equals("getId")) return actualGuild;
                throw new AssertionError("Unexpected guild operation " + m.getName());
            });
            channel = proxy(GuildMessageChannel.class, (p, m, a) -> {
                if (m.getName().equals("sendMessageEmbeds")) return create(embed(a[0]));
                if (m.getName().equals("editMessageEmbedsById")) return edit((String)a[0], embed(a[1]));
                throw new AssertionError("Unexpected channel operation " + m.getName());
            });
        }

        static MessageEmbed embed(Object value)
        {
            if (value instanceof Collection) return (MessageEmbed)((Collection<?>)value).iterator().next();
            if (value instanceof Object[]) return (MessageEmbed)((Object[])value)[0];
            return (MessageEmbed)value;
        }

        MessageCreateAction create(MessageEmbed embed)
        {
            final List<String> componentIds = new ArrayList<>();
            return proxy(MessageCreateAction.class, (p, method, args) -> {
                if (method.getName().equals("addComponents"))
                {
                    Object value = args[0];
                    Iterable<?> rows = value instanceof Collection ? (Collection<?>)value : Arrays.asList((Object[])value);
                    for (Object row : rows) for (Button button : ((ActionRow)row).getButtons())
                        componentIds.add(button.getCustomId());
                    return p;
                }
                if (method.getName().equals("complete"))
                {
                    if (sendFailure != null) throw sendFailure;
                    String id = String.valueOf(1000 + acknowledged.incrementAndGet());
                    base.add(embed); buttons.add(componentIds);
                    afterBase.run();
                    return proxy(Message.class, (mp, mm, ma) -> {
                        if (mm.getName().equals("getId")) return id;
                        throw new AssertionError("Unexpected message operation " + mm.getName());
                    });
                }
                throw new AssertionError("Unexpected base-send operation " + method.getName());
            });
        }

        MessageEditAction edit(String messageId, MessageEmbed embed)
        {
            AtomicReference<BooleanSupplier> allowed = new AtomicReference<>();
            return proxy(MessageEditAction.class, (p, method, args) -> {
                if (method.getName().equals("timeout"))
                {
                    assertEquals(10L, args[0]); assertEquals(TimeUnit.SECONDS, args[1]); return p;
                }
                if (method.getName().equals("setCheck"))
                {
                    allowed.set((BooleanSupplier)args[0]); dispatchGuard.set(allowed.get()); return p;
                }
                if (method.getName().equals("complete"))
                {
                    attempts.incrementAndGet();
                    beforeEditDispatch.run();
                    assertNotNull(allowed.get(), "The edit must revalidate at actual dispatch");
                    if (!allowed.get().getAsBoolean()) throw new IllegalStateException("Synthetic cancelled edit");
                    if (editFailure != null) throw editFailure;
                    editIds.add(messageId); edits.add(embed);
                    CountDownLatch expected = editsExpected;
                    if (expected != null) expected.countDown();
                    return null;
                }
                throw new AssertionError("Embed enrichment must not alter components: " + method.getName());
            });
        }

        void emit() { emit(TARGET); }
        void emit(String target)
        {
            discord.emitExtendedVtkInitiated(scarlet, LocalDateTime.of(2026, 10, 28, 12, 0), LOCATION,
                target, "Synthetic Target", ACTOR, "Synthetic Initiator");
        }

        ScarletBackgroundWork worker() throws Exception
        {
            Field field = ScarletDiscordJDA.class.getDeclaredField("voteKickProfileWorker");
            field.setAccessible(true);
            return (ScarletBackgroundWork)field.get(discord);
        }

        void idle() throws Exception
        {
            ScarletBackgroundWork worker = worker();
            if (worker == null) return;
            CountDownLatch finished = new CountDownLatch(1);
            assertTrue(worker.submit("synthetic-barrier", 0L, () -> true, ignored -> finished.countDown()));
            await(finished);
        }

        @Override public void close() throws Exception { discord.closeVoteKickProfileWorker(); }
    }

    @Test void cachedProfileIsIncludedImmediatelyWithoutAnyLookupAndKeepsOriginalAlert() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.profiles.value = profile(); f.emit();
            assertEquals(1, f.base.size()); assertNull(f.worker()); assertEquals(0, f.vrc.lookups.get());
            MessageEmbed initial = f.base.get(0);
            assertEquals(IMAGE, initial.getThumbnail().getUrl()); assertNull(initial.getImage());
            assertEquals("Synthetic biography", field(initial, "Bio (excerpt)"));
            assertEquals("they/them", field(initial, "Pronouns"));
            assertEquals("Online", field(initial, "Status (profile snapshot)"));
            assertEquals("Synthetic status", field(initial, "Status message"));
            assertEquals("`" + TARGET + "`", field(initial, "Target User ID"));
            assertEquals("`" + ACTOR + "`", field(initial, "Initiating User ID"));
            assertEquals("Synthetic Initiator", initial.getAuthor().getName());
            assertTrue(initial.getDescription().contains("was targeted by a vote-to-kick"));
            assertEquals(List.of("1001"), f.returnedIds);
            assertEquals(List.of("vrchat-user-ban:" + TARGET, "vrchat-user-unban:" + TARGET), f.buttons.get(0));
        }
    }

    @Test void missingProfileCannotDelayBaseDeliveryAndEditsTheSameThreadMessageOnlyOnce() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture())
        {
            Thread caller = Thread.currentThread();
            f.users.value = new User().id(TARGET).pronouns("cached pronouns");
            f.vrc.lookup = id -> { assertNotSame(caller, Thread.currentThread()); entered.countDown(); await(release); return profile(); };
            f.emit(); await(entered);
            assertEquals(1, f.base.size()); assertEquals(0, f.edits.size());
            assertEquals("cached pronouns", field(f.base.get(0), "Pronouns"));
            release.countDown(); f.idle();
            assertEquals(1, f.edits.size()); assertEquals(List.of("1001"), f.editIds);
            assertEquals(f.base.get(0).getDescription(), f.edits.get(0).getDescription());
            assertEquals(f.base.get(0).getTimestamp(), f.edits.get(0).getTimestamp());
            assertEquals(f.base.get(0).getAuthor().getName(), f.edits.get(0).getAuthor().getName());
            assertEquals("they/them", field(f.edits.get(0), "Pronouns"));
            assertEquals(1, f.buttons.size(), "The embed-only edit must not rewrite buttons");
            assertEquals(1, f.vrc.lookups.get());
        }
        finally { release.countDown(); }
    }

    @Test void optionalCacheAndLookupFailuresCannotSuppressTheBaseOrExposeExceptionText() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.users.failure = privateFailure(); f.profiles.failure = privateFailure();
            f.vrc.lookup = id -> { throw privateFailure(); };
            f.emit(); f.idle();
            assertEquals(1, f.base.size()); assertNull(f.base.get(0).getThumbnail());
            assertEquals(1, f.vrc.lookups.get()); assertEquals(0, f.attempts.get());
            f.idle(); assertEquals(1, f.vrc.lookups.get(), "A failed optional lookup is not retried");
        }
    }

    @Test void nullLookupResultRetainsTheBaseWithoutRetrying() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.vrc.lookup = id -> null; f.emit(); f.idle();
            assertEquals(1, f.base.size()); assertEquals(1, f.vrc.lookups.get()); assertTrue(f.edits.isEmpty());
        }
    }

    @Test void trainingAndInvalidTargetsNeverReadCachesOrSubmitAnyLookup() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            for (String target : new String[]{"usr_training-target", null, "", "../private", "..\\private", TARGET + "/other"})
                f.emit(target);
            assertEquals(6, f.base.size()); assertEquals(0, f.users.reads); assertEquals(0, f.profiles.reads);
            assertEquals(0, f.vrc.lookups.get()); assertNull(f.worker());
            assertEquals(Boolean.TRUE, f.training.get(0));
        }
    }

    @Test void mismatchedCachedProfileIsNeverDisplayedAndCanReceiveCorrectOptionalMetadata() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.users.value = new User().id(ACTOR).pronouns("wrong user");
            f.profiles.value = profile().id(ACTOR).bio("wrong profile");
            f.emit(); f.idle();
            assertNull(field(f.base.get(0), "Pronouns")); assertNull(field(f.base.get(0), "Bio (excerpt)"));
            assertEquals("Synthetic biography", field(f.edits.get(0), "Bio (excerpt)"));
        }
    }

    @Test void contextChangesDuringLookupDiscardTheOptionalResult() throws Exception
    {
        for (String change : List.of("group", "guild", "account", "route", "client", "gateway", "authentication"))
        {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            long authentication = -1L;
            try (Fixture f = new Fixture())
            {
                f.vrc.lookup = id -> { entered.countDown(); await(release); return profile(); };
                f.emit(); await(entered);
                switch (change)
                {
                case "group": put(f.vrc, "groupId", "changed"); break;
                case "guild": put(f.discord, "guildSf", "changed"); break;
                case "account": put(f.vrc, "currentUserId", "changed"); break;
                case "route": f.routes.put(GroupAuditTypeEx.VTK_START.id, "changed"); break;
                case "client": put(f.scarlet, "vrc", inert(OfflineVRChat.class)); break;
                case "gateway": put(f.discord, "jda", proxy(JDA.class, (p, m, a) -> null)); break;
                case "authentication": authentication = ExtendedUserAgent.beginPrimaryAuthentication(); break;
                default: fail(change);
                }
                release.countDown(); f.idle();
                assertEquals(1, f.base.size(), change); assertTrue(f.edits.isEmpty(), change);
            }
            finally
            {
                release.countDown();
                if (authentication >= 0L) ExtendedUserAgent.completePrimaryAuthentication(null, authentication);
            }
        }
    }

    @Test void contextChangedWhileBaseSendCompletesDoesNotStartAProfileLookup() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.afterBase = () -> f.routes.remove(GroupAuditTypeEx.VTK_START.id);
            f.emit();
            assertEquals(1, f.base.size()); assertEquals(0, f.vrc.lookups.get()); assertNull(f.worker());
        }
    }

    @Test void actualDiscordDispatchRechecksTheRouteAndUsesABoundedEdit() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.beforeEditDispatch = () -> f.routes.remove(GroupAuditTypeEx.VTK_START.id);
            f.emit(); f.idle();
            assertEquals(1, f.attempts.get()); assertTrue(f.edits.isEmpty());
            assertNotNull(f.dispatchGuard.get());
        }
    }

    @Test void profileResultsAndWaitingJobsOlderThanThirtySecondsAreDiscarded() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture())
        {
            f.vrc.lookup = id -> { entered.countDown(); await(release); return profile(); };
            f.emit(); await(entered); f.emit();
            f.discord.now = TimeUnit.SECONDS.toNanos(30L);
            release.countDown(); f.idle();
            assertEquals(2, f.base.size()); assertEquals(1, f.vrc.lookups.get()); assertTrue(f.edits.isEmpty());
        }
        finally { release.countDown(); }
    }

    @Test void distinctMessagesForTheSameTargetKeepSeparateJobsAndTheBacklogIsBounded() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture())
        {
            f.editsExpected = new CountDownLatch(17);
            f.vrc.lookup = id -> { entered.countDown(); await(release); return profile(); };
            f.emit(); await(entered);
            for (int i = 1; i < 25; i++) f.emit();
            assertEquals(16, f.worker().pendingCount()); assertEquals(25, f.base.size());
            release.countDown(); await(f.editsExpected); f.idle();
            assertEquals(17, f.vrc.lookups.get()); assertEquals(17, f.edits.size());
            assertTrue(f.editIds.contains("1001"), "A later vote against the same target must not invalidate the running message");
            assertEquals(17L, f.editIds.stream().distinct().count());
        }
        finally { release.countDown(); }
    }

    @Test void closedWorkerRejectsNewEnrichmentAndDiscardsAnInterruptedResult() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        try (Fixture f = new Fixture())
        {
            f.vrc.lookup = id -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return profile();
            };
            f.emit(); await(entered);
            f.discord.closeVoteKickProfileWorker();
            f.emit();
            assertEquals(2, f.base.size()); assertTrue(f.edits.isEmpty()); assertEquals(1, f.vrc.lookups.get());
            assertEquals(1, f.users.reads); assertEquals(1, f.profiles.reads);
        }
    }

    @Test void wrongResolvedGuildDoesNotReadOrEnrichAnyProfile() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.actualGuild = "other-server"; f.emit();
            assertEquals(1, f.base.size()); assertEquals(0, f.users.reads); assertEquals(0, f.profiles.reads);
            assertNull(f.worker());
        }
    }

    @Test void aFailedBaseSendNeverStartsAnOptionalLookup() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.sendFailure = privateFailure();
            assertSame(f.sendFailure, assertThrows(RuntimeException.class, f::emit));
            assertEquals(0, f.vrc.lookups.get()); assertNull(f.worker());
        }
    }

    @Test void failedOrDeletedMessageEditsAreNotRetriedAndDoNotBreakLaterAlerts() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.editFailure = privateFailure(); f.emit(); f.idle();
            assertEquals(1, f.attempts.get()); assertTrue(f.edits.isEmpty());
            f.editFailure = null; f.emit(); f.idle();
            assertEquals(2, f.attempts.get()); assertEquals(1, f.edits.size());
            assertEquals(List.of("1002"), f.editIds);
        }
    }
}

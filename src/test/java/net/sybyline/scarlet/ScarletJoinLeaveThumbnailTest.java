package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.*;
import static net.sybyline.scarlet.ScarletDiscordReadinessTest.proxy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.PublicProfile;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

class ScarletJoinLeaveThumbnailTest
{
    static final String USER = "usr_00000000-0000-0000-0000-000000000001";
    static final String IMAGE = "https://example.invalid/profile.png";
    static final String THUMBNAIL = "https://example.invalid/avatar-thumb.png";

    static MessageEmbed render(boolean enabled, PublicProfile profile)
    {
        return ScarletJoinLeaveThumbnail.apply(new EmbedBuilder().setDescription("Joined"), enabled, () -> profile).build();
    }

    @Test void onlyConcreteUserIdsMayAccessTheProfileCache()
    {
        assertTrue(ScarletJoinLeaveThumbnail.isUserId(USER));
        assertTrue(ScarletJoinLeaveThumbnail.isUserId("AbCd123456"), "Legacy VRChat user IDs remain supported");
        for (String invalid : new String[]{null, "", "../private", "..\\private", "usr_training-user", USER + "/other"})
            assertFalse(ScarletJoinLeaveThumbnail.isUserId(invalid));
    }

    @Test void profilePictureUsesSmallThumbnailSlotNotLargeImage()
    {
        MessageEmbed embed = render(true, new PublicProfile().currentAvatarImageUrl(IMAGE)
            .currentAvatarThumbnailImageUrl(THUMBNAIL));
        assertEquals(IMAGE, embed.getThumbnail().getUrl());
        assertNull(embed.getImage());
        assertEquals("Joined", embed.getDescription());
    }

    @Test void fallbackOrderUsesAvatarThumbnailThenProfileIcons()
    {
        PublicProfile profile = new PublicProfile().currentAvatarImageUrl(" ")
            .currentAvatarThumbnailImageUrl(THUMBNAIL).iconUrl(IMAGE).userIcon("https://example.invalid/icon.png");
        assertEquals(THUMBNAIL, render(true, profile).getThumbnail().getUrl());
        profile.setCurrentAvatarThumbnailImageUrl(null);
        assertEquals(IMAGE, render(true, profile).getThumbnail().getUrl());
        profile.setIconUrl(null);
        assertEquals("https://example.invalid/icon.png", render(true, profile).getThumbnail().getUrl());
    }

    @Test void disabledToggleDoesNotEvenReadTheCache()
    {
        MessageEmbed embed = ScarletJoinLeaveThumbnail.apply(new EmbedBuilder().setDescription("Left"), false,
            () -> { throw new AssertionError("Disabled thumbnails must not resolve any profile"); }).build();
        assertNull(embed.getThumbnail());
        assertEquals("Left", embed.getDescription());
    }

    @Test void missingAndBrokenCachedProfilesNeverLoseTheEvent()
    {
        assertNull(render(true, null).getThumbnail());
        assertNull(render(true, new PublicProfile()).getThumbnail());
        MessageEmbed embed = ScarletJoinLeaveThumbnail.apply(new EmbedBuilder().setDescription("Left"), true,
            () -> { throw new IllegalStateException("Synthetic unreadable cache"); }).build();
        assertEquals("Left", embed.getDescription());
        assertNull(embed.getThumbnail());
        assertNull(embed.getImage());
    }

    @Test void unsafeOrMalformedPictureFallsBackToUsableThumbnail()
    {
        for (String bad : Arrays.asList("file:///private/image.png", "data:image/png;base64,abc",
            "javascript:alert(1)", "https://token@example.invalid/image.png", "https://example.invalid/image.png#secret",
            "https://example.invalid/bad image.png", "https:///no-host", "//example.invalid/image.png"))
        {
            assertNull(ScarletJoinLeaveThumbnail.safeImageUrl(bad), bad);
            assertEquals(THUMBNAIL, render(true, new PublicProfile().currentAvatarImageUrl(bad)
                .currentAvatarThumbnailImageUrl(THUMBNAIL)).getThumbnail().getUrl());
        }
        assertNull(ScarletJoinLeaveThumbnail.safeImageUrl(null));
        assertNull(ScarletJoinLeaveThumbnail.safeImageUrl(" "));
        assertNull(ScarletJoinLeaveThumbnail.safeImageUrl("https://example.invalid/" + String.join("", java.util.Collections.nCopies(2000, "x"))));
        assertEquals(IMAGE, ScarletJoinLeaveThumbnail.safeImageUrl(" " + IMAGE + " "));
        assertEquals("http://example.invalid/image.png", ScarletJoinLeaveThumbnail.safeImageUrl("http://example.invalid/image.png"));
    }

    static class CountingCache extends ScarletJsonCache<PublicProfile>
    {
        PublicProfile profile;
        boolean broken;
        int reads;
        CountingCache() { super("synthetic", PublicProfile.class); throw new AssertionError("Never construct a real cache"); }
        @Override public PublicProfile get(String userId)
        {
            assertEquals(USER, userId);
            reads++;
            if (broken) throw new IllegalStateException("Synthetic corrupt cache");
            return profile;
        }
    }

    static class NoNetworkVRChat extends ScarletVRChat
    {
        NoNetworkVRChat() { super(null, null, null); throw new AssertionError("Never start an API client"); }
        @Override public PublicProfile getPublicProfile(String userId, long minEpoch)
        { throw new AssertionError("Join/leave thumbnails must not fetch profiles"); }
        @Override public String getUserImageUrlOrNull(String userId)
        { throw new AssertionError("Join/leave thumbnails must not use the network image helper"); }
    }

    static class CapturingDiscord extends ScarletDiscordJDA
    {
        GuildMessageChannel channel;
        List<GroupAuditTypeEx> routes;
        CapturingDiscord() { super(null, null, null); throw new AssertionError("Never start Discord"); }
        @Override void condEmitEx(GroupAuditTypeEx type, boolean log, boolean secret, String location, CondEmit emit)
        {
            assertFalse(log);
            assertFalse(secret);
            routes.add(type);
            assertNull(emit.emit("123", null, channel), "Nonblocking log sends have no synchronous message result");
        }
        @Override void condEmitEx(boolean training, GroupAuditTypeEx type, boolean log, boolean secret, String location, CondEmit emit)
        {
            assertFalse(training);
            this.condEmitEx(type, log, secret, location, emit);
        }
    }

    @Test void allFourActualEmittersKeepRoutingAndButtonsWithLiveToggleChanges() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.cache.profile = new PublicProfile().currentAvatarImageUrl(IMAGE);
        fixture.emitAll();
        assertEquals(4, fixture.cache.reads);
        for (MessageEmbed embed : fixture.sent)
        {
            assertEquals(IMAGE, embed.getThumbnail().getUrl());
            assertNull(embed.getImage());
        }
        put(fixture.toggle, "cached", false);
        fixture.emitAll();
        assertEquals(4, fixture.cache.reads, "Turning off immediately stops cache reads");
        for (MessageEmbed embed : fixture.sent.subList(4, 8)) assertNull(embed.getThumbnail());
        put(fixture.toggle, "cached", true);
        fixture.cache.broken = true;
        fixture.emitAll();
        assertEquals(12, fixture.queued.get(), "Optional images must never prevent message submission");
        assertEquals(6, fixture.components.get(), "Ordinary user events retain their existing moderation buttons");
        for (int offset : new int[]{0, 4, 8})
            assertEquals(Arrays.asList(GroupAuditTypeEx.STAFF_JOIN, GroupAuditTypeEx.STAFF_LEAVE,
                GroupAuditTypeEx.USER_JOIN, GroupAuditTypeEx.USER_LEAVE), fixture.discord.routes.subList(offset, offset + 4));
        for (MessageEmbed embed : fixture.sent)
            assertTrue(embed.getDescription().contains("https://vrchat.com/home/user/" + USER));
    }

    @Test void joinAndLeaveSubmissionsDoNotWaitForAnyDiscordAcknowledgement() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.emitAll();
        // The transport deliberately never acknowledges any send. Every later event still queues.
        assertEquals(4, fixture.queued.get());
        assertEquals(4, fixture.failures.size());
        assertEquals(4, fixture.sent.size());
        assertEquals(2, fixture.components.get());
    }

    @Test void delayedDiscordFailuresNeverReadPrivateExceptionDetailsOrBlockLaterEvents() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.emitAll();
        RuntimeException privateFailure = new RuntimeException()
        {
            private static final long serialVersionUID = 1L;
            @Override public String getMessage() { throw new AssertionError("Do not expose remote message details"); }
            @Override public String toString() { throw new AssertionError("Do not render remote exceptions"); }
        };
        for (Consumer<Throwable> failure : new ArrayList<>(fixture.failures))
            assertDoesNotThrow(() -> failure.accept(privateFailure));
        fixture.emitAll();
        assertEquals(8, fixture.queued.get());
    }

    static class Fixture
    {
        final CapturingDiscord discord = inert(CapturingDiscord.class);
        final CountingCache cache = inert(CountingCache.class);
        final ScarletSettings.FileValuedBoolean toggle;
        final List<MessageEmbed> sent = new ArrayList<>();
        final AtomicInteger queued = new AtomicInteger(), components = new AtomicInteger();
        final List<Consumer<Throwable>> failures = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Fixture() throws Exception
        {
            ScarletSettings settings = inert(ScarletSettings.class);
            put(settings, "fileValuedSettings", new HashMap<>());
            toggle = settings.new FileValuedBoolean("discord_instance_join_leave_thumbnails", "Synthetic toggle", true);
            put(toggle, "cached", true);
            put(discord, "instanceJoinLeaveThumbnails", toggle);
            Scarlet scarlet = inert(Scarlet.class);
            NoNetworkVRChat vrc = inert(NoNetworkVRChat.class);
            put(vrc, "cachedPublicProfiles", cache);
            put(scarlet, "vrc", vrc);
            put(discord, "scarlet", scarlet);
            discord.routes = new ArrayList<>();
            MessageCreateAction action = proxy(MessageCreateAction.class, (p, method, args) -> {
                if (method.getName().equals("addComponents")) { components.incrementAndGet(); return p; }
                if (method.getName().equals("queue"))
                {
                    assertEquals(2, args.length, "Every asynchronous send needs an explicit safe failure handler");
                    queued.incrementAndGet();
                    failures.add((Consumer<Throwable>)args[1]);
                    return null;
                }
                throw new AssertionError("Unexpected outbound operation " + method.getName());
            });
            discord.channel = proxy(GuildMessageChannel.class, (p, method, args) -> {
                if (method.getName().equals("sendMessageEmbeds"))
                {
                    Object first = args[0];
                    sent.add(first instanceof Collection ? (MessageEmbed)((Collection<?>)first).iterator().next() : (MessageEmbed)first);
                    return action;
                }
                throw new AssertionError("Unexpected channel operation " + method.getName());
            });
        }

        void emitAll()
        {
            LocalDateTime timestamp = LocalDateTime.of(2026, 10, 23, 10, 20);
            discord.emitExtendedStaffJoin(null, timestamp, "wrld_synthetic:123", USER, "Test User");
            discord.emitExtendedStaffLeave(null, timestamp, "wrld_synthetic:123", USER, "Test User");
            discord.emitExtendedUserJoin(null, timestamp, "wrld_synthetic:123", USER, "Test User");
            discord.emitExtendedUserLeave(null, timestamp, "wrld_synthetic:123", USER, "Test User");
        }
    }
}

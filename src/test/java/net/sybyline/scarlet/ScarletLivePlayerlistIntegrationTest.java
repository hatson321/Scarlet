package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.*;
import static net.sybyline.scarlet.ScarletDiscordReadinessTest.proxy;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.Channel;

/** Offline log-to-roster integration: no application construction, credentials, or API calls. */
class ScarletLivePlayerlistIntegrationTest
{
    static final LocalDateTime JOINED = LocalDateTime.of(2026, 4, 4, 12, 0);

    static final class Captured
    {
        final String location;
        final List<ScarletLivePlayerlist.PlayerSnapshot> players;
        Captured(String location, List<ScarletLivePlayerlist.PlayerSnapshot> players)
        {
            this.location = location;
            this.players = players;
        }
    }

    static final class Fixture
    {
        final List<Captured> sent = new ArrayList<>();
        final List<String> ended = new ArrayList<>();
        final ScarletDiscord discord = proxy(ScarletDiscord.class, (p, method, args) -> {
            if (method.getName().equals("updateLivePlayerList"))
            {
                @SuppressWarnings("unchecked")
                List<ScarletLivePlayerlist.PlayerSnapshot> players = (List<ScarletLivePlayerlist.PlayerSnapshot>)args[1];
                sent.add(new Captured((String)args[0], players));
                return null;
            }
            if (method.getName().equals("endLivePlayerList"))
            {
                ended.add((String)args[0]);
                return null;
            }
            throw new AssertionError("Unexpected Discord operation: " + method.getName());
        });
        final ScarletEventListener.LivePlayerListSession session = new ScarletEventListener.LivePlayerListSession(() -> discord);
        Captured last() { return sent.get(sent.size() - 1); }
    }

    @Test void catchUpPublishesOnlyTheFinalCurrentWorldAndPresentPlayers()
    {
        Fixture f = new Fixture();
        f.session.joined("old-instance", true, true);
        f.session.playerJoined(true, JOINED, "usr_old", "Old Player");
        f.session.joined("current-instance", true, true);
        f.session.playerJoined(true, JOINED, "usr_current", "Current Player");
        f.session.playerJoined(true, JOINED, "usr_departed", "Departed Player");
        f.session.playerLeft(true, "usr_departed");
        assertTrue(f.sent.isEmpty());
        assertTrue(f.ended.isEmpty());
        f.session.caughtUp();
        assertEquals(1, f.sent.size());
        assertEquals("current-instance", f.last().location);
        assertEquals(1, f.last().players.size());
        assertEquals("usr_current", f.last().players.get(0).userId);
    }

    @Test void quitDuringCatchUpDoesNotResurrectAnOldRoster()
    {
        Fixture f = new Fixture();
        f.session.joined("old-instance", true, true);
        f.session.playerJoined(true, JOINED, "usr_old", "Old Player");
        f.session.reset();
        f.session.caughtUp();
        assertEquals(1, f.sent.size());
        assertNull(f.last().location);
        assertTrue(f.last().players.isEmpty());
        assertTrue(f.ended.isEmpty());
    }

    @Test void leavingOrChangingInstanceEndsObservationExactlyOnce()
    {
        Fixture f = new Fixture();
        f.session.joined("first", true, false);
        f.session.playerJoined(false, JOINED, "usr_first", "First Player");
        Captured firstSnapshot = f.last();
        f.session.joined("second", true, false);
        assertEquals(List.of("first"), f.ended);
        assertTrue(f.last().players.isEmpty());
        assertEquals("first", firstSnapshot.location);
        assertEquals("usr_first", firstSnapshot.players.get(0).userId);
        f.session.reset();
        f.session.reset();
        assertEquals(List.of("first", "second"), f.ended);
    }

    @Test void anotherGroupsOrNonGroupInstanceNeverPublishes()
    {
        Fixture f = new Fixture();
        f.session.joined("our-group", true, false);
        f.session.joined("other-group", false, false);
        int previousCount = f.sent.size();
        f.session.playerJoined(false, JOINED, "usr_other", "Other Player");
        f.session.avatar(false, "Other Player", "Unrelated Avatar");
        f.session.caughtUp();
        assertEquals(previousCount + 1, f.sent.size());
        assertNull(f.last().location, "Publish a known-empty observation after catch-up");
        assertTrue(f.last().players.isEmpty());
        assertEquals(List.of("our-group"), f.ended);
    }

    @Test void avatarNamesUpdateWithoutConsultingAvatarLoggingOrProviders()
    {
        Fixture f = new Fixture();
        f.session.joined("current", true, false);
        f.session.playerJoined(false, JOINED, "usr_player", "Player");
        f.session.avatar(false, "Player", "Observed Avatar");
        assertEquals("Observed Avatar", f.last().players.get(0).avatarName);
        f.session.avatar(false, "Player", "New Avatar");
        assertEquals("New Avatar", f.last().players.get(0).avatarName);
    }

    @Test void catchUpIncludesAvatarObservedBeforeJoinButIgnoresDepartedSwitches()
    {
        Fixture f = new Fixture();
        f.session.joined("current", true, true);
        f.session.avatar(true, "Player", "Initial Avatar");
        f.session.playerJoined(true, JOINED, "usr_player", "Player");
        f.session.caughtUp();
        assertEquals("Initial Avatar", f.last().players.get(0).avatarName);
        f.session.playerLeft(false, "usr_player");
        int count = f.sent.size();
        f.session.avatar(false, "Player", "Must Not Be Tracked");
        assertEquals(count, f.sent.size());
        assertTrue(f.last().players.isEmpty());
        f.session.playerJoined(false, JOINED.plusMinutes(1), "usr_player", "Player");
        assertNull(f.last().players.get(0).avatarName);
    }

    @Test void timestampsConvertTheLocalInstantInsteadOfRelabelingItUtc()
    {
        Fixture f = new Fixture();
        f.session.joined("current", true, false);
        f.session.playerJoined(false, JOINED, "usr_player", "Player");
        assertEquals(JOINED.atZone(ZoneId.systemDefault()).toInstant(),
            f.last().players.get(0).joinedAt.toInstant());
        assertEquals(ZoneOffset.UTC, f.last().players.get(0).joinedAt.getOffset());
    }

    @Test void snapshotsAreImmutableAndTrainingPlayersNeverAppear()
    {
        Fixture f = new Fixture();
        f.session.joined("current", true, false);
        f.session.playerJoined(false, JOINED, "usr_training-synthetic", "Training Player");
        assertTrue(f.last().players.isEmpty());
        f.session.playerJoined(false, JOINED, "usr_real", "Observed Player");
        Captured earlier = f.last();
        assertThrows(UnsupportedOperationException.class, earlier.players::clear);
        f.session.playerLeft(false, "usr_real");
        assertEquals(1, earlier.players.size());
        assertTrue(f.last().players.isEmpty());
    }

    @Test void logInitializationEndsThePreviousSessionBeforePreamble() throws Exception
    {
        Fixture f = new Fixture();
        f.session.joined("previous-session", true, false);
        ScarletEventListener listener = inert(ScarletEventListener.class);
        put(listener, "livePlayerListSession", f.session);
        put(listener, "clientExitMonitor", new ScarletClientExitMonitor(() -> false, () -> null,
            () -> false, null, location -> null, result -> {}, java.time.Instant::now, System::nanoTime));
        listener.log_init(null);
        f.session.caughtUp();
        assertEquals(List.of("previous-session"), f.ended);
        assertNull(f.session.location);
    }

    static class NoSaveDiscord extends ScarletDiscordJDA
    {
        int saves;
        NoSaveDiscord() { super(null, null, null); throw new AssertionError("Do not construct the app"); }
        @Override public void save() { saves++; }
    }

    static class CountingPlayerList extends ScarletLivePlayerlist
    {
        int reconciles;
        CountingPlayerList() { super((Scarlet)null); throw new AssertionError("No worker in this test"); }
        @Override public void reconcile() { reconciles++; }
    }

    @Test void reconnectAndChangedDestinationsRefreshDeliveryWithoutRestartingScarlet() throws Exception
    {
        NoSaveDiscord discord = discordFixture();
        CountingPlayerList list = inert(CountingPlayerList.class);
        put(discord, "livePlayerlist", list);
        ScarletDiscordJDA.JDAEvents events = discord.new JDAEvents();
        events.onSessionResume(null);
        events.onSessionRecreate(null);
        discord.setAuditExChannel(GroupAuditTypeEx.LIVE_PLAYERLIST, null);
        discord.setAuditExSecretChannel(GroupAuditTypeEx.LIVE_PLAYERLIST, null);
        assertEquals(4, list.reconciles);
        discord.setAuditExChannel(GroupAuditTypeEx.USER_AVATAR, null);
        assertEquals(4, list.reconciles, "Unrelated avatar setting must not gate or reset live delivery");
    }

    static NoSaveDiscord discordFixture() throws Exception
    {
        NoSaveDiscord discord = inert(NoSaveDiscord.class);
        put(discord, "auditExType2channelSf", new HashMap<String, String>());
        put(discord, "auditExType2secretChannelSf", new HashMap<String, String>());
        put(discord, "jda", proxy(JDA.class, (p, method, args) -> {
            throw new AssertionError("No JDA API calls are allowed in this test");
        }));
        return discord;
    }

    @Test void onlyLivePlayerListGainsSecretOnlyEnablement() throws Exception
    {
        NoSaveDiscord discord = discordFixture();
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
        {
            discord.auditExType2secretChannelSf.put(type.id, "123");
            assertEquals(type == GroupAuditTypeEx.LIVE_PLAYERLIST, discord.isEmitting(type));
        }
    }

    @Test void unsupportedSecretRoutesAreRejectedButOldAssignmentsCanBeCleared() throws Exception
    {
        NoSaveDiscord discord = discordFixture();
        Channel channel = proxy(Channel.class, (p, method, args) -> {
            if (method.getName().equals("getId")) return "123";
            if (method.getName().equals("getName")) return "synthetic-channel";
            throw new AssertionError("Unexpected channel operation " + method.getName());
        });
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
        {
            if (type.supportsSecretRouting())
            {
                discord.setAuditExSecretChannel(type, channel);
                assertEquals("123", discord.auditExType2secretChannelSf.get(type.id));
            }
            else
            {
                assertThrows(IllegalArgumentException.class, () -> discord.setAuditExSecretChannel(type, channel));
                assertFalse(discord.auditExType2secretChannelSf.containsKey(type.id));
                discord.auditExType2secretChannelSf.put(type.id, "legacy");
            }
            discord.setAuditExSecretChannel(type, null);
            assertFalse(discord.auditExType2secretChannelSf.containsKey(type.id));
        }
    }
}

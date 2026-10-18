package net.sybyline.scarlet;

import static net.sybyline.scarlet.ScarletAuditPrivacyEntryPointsTest.primitive;
import static net.sybyline.scarlet.ScarletAuditPrivacyEntryPointsTest.proxy;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonInteraction;
import net.sybyline.scarlet.server.discord.DInteractions;
import net.sybyline.scarlet.server.discord.DPerms;

/** Synthetic HTTP, disk and Discord boundaries only; no real identities or outbound requests. */
class ScarletSnapshotPrivacyTest
{
    @TempDir Path temp;
    static final String USER = ScarletVRChatApi122CompatibilityTest.USER;
    static final String GROUP = ScarletVRChatApi122CompatibilityTest.GROUP;
    static final String AUDIT = "gaud_10000000-0000-0000-0000-000000000003";
    static final String[] OPERATIONS = {"view-snapshot-user", "view-snapshot-user-groups", "view-snapshot-user-represented-group"};

    static JsonObject self()
    {
        JsonObject raw = JsonParser.parseString(ScarletVRChatApi122CompatibilityTest.currentUser("[]")).getAsJsonObject();
        raw.addProperty("username", "PRIVATE_LOGIN_MARKER");
        raw.addProperty("obfuscatedEmail", "PRIVATE_EMAIL_MARKER");
        raw.addProperty("email", "PRIVATE_EMAIL_MARKER");
        raw.add("steamDetails", JsonParser.parseString("{\"id\":\"PRIVATE_STEAM_MARKER\"}"));
        raw.add("futureAccountFields", JsonParser.parseString("{\"nested\":[\"PRIVATE_FUTURE_MARKER\"]}"));
        raw.addProperty("bio", "Public profile biography");
        raw.add("bioLinks", JsonParser.parseString("[\"https://example.invalid/public\",{\"private\":\"PRIVATE_NESTED_MARKER\"}]"));
        return raw;
    }

    static JsonObject group()
    {
        JsonObject group = JsonParser.parseString("{\"groupId\":\"" + GROUP + "\",\"name\":\"Public group name\","
            + "\"description\":\"Public group description\",\"memberVisibility\":\"visible\",\"privacy\":\"default\","
            + "\"shortCode\":\"TEST\",\"discriminator\":\"0001\",\"lastPostReadAt\":\"PRIVATE_READING_MARKER\","
            + "\"futurePrivate\":{\"credential\":\"PRIVATE_GROUP_MARKER\"}}").getAsJsonObject();
        return group;
    }

    static void assertPublic(JsonObject safe)
    {
        assertEquals(USER, safe.get("id").getAsString());
        assertEquals("Synthetic user", safe.get("displayName").getAsString());
        assertEquals("Public profile biography", safe.get("bio").getAsString());
        for (String field : new String[]{"username", "obfuscatedEmail", "email", "steamDetails", "emailVerified",
            "twoFactorAuthEnabled", "friends", "presence", "futureAccountFields", "location", "statusHistory"})
            assertFalse(safe.has(field), field);
        assertFalse(safe.toString().contains("PRIVATE_"));
    }

    @Test void actualSelfHttpCaptureIsFilteredBeforePersistingAllThreeSnapshotShapes() throws Exception
    {
        try (ScarletSnapshotTransportTest.Fixture f = new ScarletSnapshotTransportTest.Fixture(temp.resolve("cache")))
        {
            put(f.vrc, "currentUserId", USER);
            f.reply(self().toString());
            JsonArray groups = new JsonArray();
            groups.add(group());
            JsonObject hidden = group(); hidden.addProperty("memberVisibility", "hidden"); groups.add(hidden);
            f.reply(groups.toString());
            f.reply(group().toString());
            ScarletData.AuditEntryMetadata metadata = ScarletDiscordSafetyTest.metadata(AUDIT, "ordinary");
            metadata.entry.setTargetId(USER);
            assertEquals(1, f.vrc.snapshot(metadata).size());
            assertEquals(3, f.requests.size());
            f.assertGet(0, "/users/" + USER);
            f.assertGet(1, "/users/" + USER + "/groups");
            f.assertGet(2, "/users/" + USER + "/groups/represented");
            assertPublic(metadata.snapshotTargetUser);
            assertFalse(Scarlet.GSON.toJson(metadata).contains("PRIVATE_"));
            ScarletData data = new ScarletData(temp.resolve("data").toFile());
            data.auditEntryMetadata(AUDIT, metadata);
            ScarletData.AuditEntryMetadata loaded = new ScarletData(temp.resolve("data").toFile()).auditEntryMetadata(AUDIT);
            assertPublic(loaded.snapshotTargetUser);
            assertEquals(1, loaded.snapshotTargetUserGroups.size());
            assertFalse(Scarlet.GSON.toJson(loaded).contains("PRIVATE_"));
        }
    }

    @Test void failedOrRedactedCaptureCannotKeepPreviouslyRawPrivateResponse() throws Exception
    {
        try (ScarletSnapshotTransportTest.Fixture f = new ScarletSnapshotTransportTest.Fixture(temp.resolve("cache")))
        {
            ScarletData.AuditEntryMetadata metadata = ScarletDiscordSafetyTest.metadata(AUDIT, "ordinary");
            metadata.entry.setTargetId(USER);
            metadata.snapshotTargetUser = self();
            metadata.snapshotTargetUserRepresentedGroup = group();
            metadata.entryRedacted = true;
            assertNull(f.vrc.snapshot(metadata));
            assertNull(metadata.snapshotTargetUser);
            assertNull(metadata.snapshotTargetUserRepresentedGroup);
            assertTrue(f.requests.isEmpty());
            metadata.entryRedacted = false;
            metadata.snapshotTargetUser = self();
            f.reply(403, "{}"); f.reply(403, "{}"); f.reply(403, "{}");
            f.vrc.snapshot(metadata);
            assertNull(metadata.snapshotTargetUser);
            assertNull(metadata.snapshotTargetUserGroups);
            assertNull(metadata.snapshotTargetUserRepresentedGroup);
        }
    }

    @Test void projectionsRejectMalformedUnknownPrivateAndMismatchedDataWithoutMutatingOriginal()
    {
        JsonObject raw = self();
        String original = raw.toString();
        assertPublic(ScarletSnapshotPrivacy.user(raw, USER));
        assertEquals(original, raw.toString());
        assertNull(ScarletSnapshotPrivacy.user(raw, "usr_10000000-0000-0000-0000-000000000099"));
        raw.add("displayName", JsonParser.parseString("{\"secret\":\"PRIVATE_MARKER\"}"));
        raw.add("bio", JsonParser.parseString("[\"PRIVATE_MARKER\"]"));
        assertFalse(ScarletSnapshotPrivacy.user(raw, USER).has("displayName"));
        assertFalse(ScarletSnapshotPrivacy.user(raw, USER).has("bio"));
        for (String visibility : new String[]{"hidden", "friends", "future", ""})
        {
            JsonObject group = group(); group.addProperty("memberVisibility", visibility);
            assertNull(ScarletSnapshotPrivacy.group(group));
        }
        JsonObject group = group(); group.addProperty("privacy", "private");
        assertNull(ScarletSnapshotPrivacy.group(group));
        group.remove("privacy"); assertNull(ScarletSnapshotPrivacy.group(group));
        JsonArray malformed = JsonParser.parseString("[null,42,\"str\",[],{}, {\"groupId\":{}}]").getAsJsonArray();
        assertEquals(0, ScarletSnapshotPrivacy.groups(malformed).size());
    }

    @Test void boundedProjectionAndEmbedsStayWithinDiscordLimits()
    {
        JsonObject raw = self();
        raw.addProperty("bio", String.join("", Collections.nCopies(10000, "*")));
        JsonArray links = new JsonArray();
        for (int i = 0; i < 100; i++) links.add(String.join("", Collections.nCopies(2500, "a")));
        raw.add("bioLinks", links);
        JsonObject safe = ScarletSnapshotPrivacy.user(raw, USER);
        assertEquals(32, safe.getAsJsonArray("bioLinks").size());
        for (MessageEmbed embed : ScarletSnapshotPrivacy.embeds(safe))
        {
            assertTrue(embed.getDescription().length() <= 2000);
            assertTrue(embed.getFields().isEmpty());
        }
        JsonArray groups = new JsonArray();
        for (int i = 0; i < 250; i++) groups.add(group());
        assertEquals(200, ScarletSnapshotPrivacy.groups(groups).size());
    }

    class Fixture
    {
        final Scarlet scarlet = inert(Scarlet.class);
        final ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        final ScarletDiscordUI ui = inert(ScarletDiscordUI.class);
        final DInteractions interactions = inert(DInteractions.class);
        final Map<String, DInteractions.Pagination> pagination = new HashMap<>();
        final Set<String> secret = new HashSet<>();
        final List<MessageEmbed> embeds = new ArrayList<>();
        final List<String> denials = new ArrayList<>();
        final ScarletData.AuditEntryMetadata metadata = ScarletDiscordSafetyTest.metadata(AUDIT, "ordinary");
        boolean permission = true, channelAccess = true, webhook, missingMember;
        String guildId = "1", author = "10", originId = "101", threadId = "100", parentId = "20";
        String requester = "50", command = OPERATIONS[0], customId;
        final Guild guild;
        Member member;
        ThreadChannel thread;
        final Message origin;
        final JDA jda;

        Fixture() throws Exception
        {
            put(scarlet, "data", new ScarletData(temp.resolve("fixture-" + System.nanoTime()).toFile()));
            ScarletSecretStaffList staff = inert(ScarletSecretStaffList.class);
            put(staff, "secretStaffUserIds", secret);
            put(scarlet, "secretStaffList", staff);
            put(discord, "scarlet", scarlet); put(ui, "discord", discord);
            put(discord, "guildSf", "1"); put(discord, "interactions", interactions);
            put(interactions, "pagination", pagination);
            put(discord, "auditType2channelSf", Collections.singletonMap("group.member.join", "20"));
            put(discord, "auditType2secretChannelSf", Collections.singletonMap("group.member.join", "20"));
            put(discord, "perms", new DPerms(null) {
                @Override public boolean checkOperation(Member m, PermType type, String operation) {
                    assertEquals(PermType.BUTTON_PRESS, type); assertEquals(command, operation); return permission;
                }
            });
            guild = proxy(Guild.class, (p,m,a) -> {
                if (m.getName().equals("getId")) return guildId;
                if (m.getName().equals("getMemberById")) return missingMember ? null : this.member;
                if (m.getName().equals("getThreadChannelById")) return this.thread;
                return primitive(m);
            });
            member = proxy(Member.class, (p,m,a) -> {
                if (m.getName().equals("getId")) return requester;
                if (m.getName().equals("getGuild")) return guild;
                if (m.getName().equals("hasPermission")) return channelAccess;
                return primitive(m);
            });
            thread = proxy(ThreadChannel.class, (p,m,a) -> {
                if (m.getName().equals("getId")) return threadId;
                if (m.getName().equals("getGuild")) return guild;
                if (m.getName().equals("getParentChannel")) return proxy(m.getReturnType(), (q,n,b) -> n.getName().equals("getId") ? parentId : primitive(n));
                return primitive(m);
            });
            origin = proxy(Message.class, (p,m,a) -> {
                if (m.getName().equals("getId")) return originId;
                if (m.getName().equals("isFromGuild")) return true;
                if (m.getName().equals("isWebhookMessage")) return webhook;
                if (m.getName().equals("getGuild")) return guild;
                if (m.getName().equals("getChannel")) return proxy(m.getReturnType(), (q,n,b) -> n.getName().equals("getId") ? threadId : primitive(n));
                if (m.getName().equals("getAuthor")) return proxy(m.getReturnType(), (q,n,b) -> n.getName().equals("getId") ? author : primitive(n));
                return primitive(m);
            });
            SelfUser bot = proxy(SelfUser.class, (p,m,a) -> m.getName().equals("getId") ? "10" : primitive(m));
            jda = proxy(JDA.class, (p,m,a) -> m.getName().equals("getSelfUser") ? bot : primitive(m));
            put(discord, "jda", jda);
            metadata.entry.setTargetId(USER);
            metadata.guildSnowflake = "1"; metadata.channelSnowflake = "20";
            metadata.messageSnowflake = "100"; metadata.threadSnowflake = "100"; metadata.auxMessageSnowflake = "101";
            metadata.snapshotTargetUser = self();
            metadata.snapshotTargetUserGroups = new JsonArray(); metadata.snapshotTargetUserGroups.add(group());
            metadata.snapshotTargetUserRepresentedGroup = group();
            save();
        }

        void save() { scarlet.data.auditEntryMetadata(AUDIT, metadata); }

        @SuppressWarnings("unchecked")
        void click(int kind)
        {
            command = OPERATIONS[kind];
            ButtonInteraction interaction = proxy(ButtonInteraction.class, (p,m,a) -> {
                if (m.getName().equals("getButton")) return Button.secondary(customId == null ? command + ":" + AUDIT : customId, "Snapshot");
                if (m.getName().equals("getId")) return "300";
                if (m.getName().equals("getIdLong")) return 300L;
                if (m.getName().equals("getMember")) return missingMember ? null : member;
                if (m.getName().equals("getMessage")) return origin;
                return primitive(m);
            });
            InteractionHook hook = proxy(InteractionHook.class, (p,m,a) -> {
                if (m.getName().equals("sendMessage")) {
                    denials.add(a[0].toString()); return ScarletAuditPrivacyEntryPointsTest.fluent(m.getReturnType());
                }
                if (m.getName().equals("editOriginalEmbeds") || m.getName().equals("editMessageEmbedsById")) {
                    Object values = a[m.getName().equals("editOriginalEmbeds") ? 0 : 1];
                    if (values instanceof MessageEmbed[]) Collections.addAll(embeds, (MessageEmbed[])values);
                    else embeds.addAll((java.util.Collection<MessageEmbed>)values);
                    return proxy(m.getReturnType(), (q,n,b) -> {
                        if (n.getName().equals("queue") && b != null && b.length > 0) {
                            Message sent = proxy(Message.class, (r,o,c) -> o.getName().equals("getId") ? "900" : primitive(o));
                            ((Consumer<Message>)b[0]).accept(sent);
                        }
                        return n.getReturnType().isInstance(q) ? q : primitive(n);
                    });
                }
                return primitive(m);
            });
            ButtonInteractionEvent event = new ButtonInteractionEvent(jda, 0L, interaction);
            if (kind == 0) ui.viewSnapshotUser(event, hook);
            else if (kind == 1) ui.viewSnapshotUserGroups(event, hook);
            else ui.viewSnapshotUserRepresentedGroup(event, hook);
        }

        boolean pageAllowed(String user) throws Exception
        {
            Interaction next = proxy(Interaction.class, (p,m,a) -> {
                if (m.getName().equals("getMember")) return member;
                if (m.getName().equals("getUser")) return proxy(m.getReturnType(), (q,n,b) -> n.getName().equals("getId") ? user : primitive(n));
                return primitive(m);
            });
            Method permits = DInteractions.Pagination.class.getDeclaredMethod("permits", Interaction.class);
            permits.setAccessible(true);
            return (boolean) permits.invoke(pagination.get("300"), next);
        }

        void inspectAllRenderedPages() throws Exception
        {
            DInteractions.Pagination pages = pagination.get("300");
            java.lang.reflect.Field allPages = pages.getClass().getDeclaredField("pages"); allPages.setAccessible(true);
            int count = java.lang.reflect.Array.getLength(allPages.get(pages));
            assertTrue(count > 0);
            Method action = pages.getClass().getDeclaredMethod("action", int.class); action.setAccessible(true);
            embeds.clear();
            for (int i = 1; i <= count; i++) action.invoke(pages, i);
            assertEquals(count, embeds.size());
            for (MessageEmbed embed : embeds)
            {
                assertFalse(embed.toData().toString().contains("PRIVATE_"));
                assertFalse(java.util.Arrays.asList("username", "obfuscatedEmail", "email", "twoFactorAuthEnabled", "friends", "presence").contains(embed.getTitle()));
                assertTrue(embed.getDescription().length() <= 2000);
                assertTrue(embed.getFields().isEmpty());
            }
        }
    }

    @Test void allActualButtonsFilterLegacyDiskDataAndKeepNormalPublicSnapshotsUseful() throws Exception
    {
        for (int kind = 0; kind < 3; kind++)
        {
            Fixture f = new Fixture();
            f.click(kind);
            assertTrue(f.denials.isEmpty(), f.denials.toString());
            assertEquals(1, f.embeds.size());
            assertNotNull(f.pagination.get("300"));
            f.inspectAllRenderedPages();
            String expectedPublicText = kind == 0 ? "Public profile biography" : "Public group";
            assertTrue(f.embeds.stream().anyMatch(embed -> embed.getDescription().contains(expectedPublicText)));
            // Validate the saved-data projection in addition to every actual Discord page.
            String json = kind == 0 ? ScarletSnapshotPrivacy.user(f.metadata.snapshotTargetUser, USER).toString()
                : kind == 1 ? ScarletSnapshotPrivacy.groups(f.metadata.snapshotTargetUserGroups).toString()
                : ScarletSnapshotPrivacy.group(f.metadata.snapshotTargetUserRepresentedGroup).toString();
            assertFalse(json.contains("PRIVATE_"));
            assertTrue(f.pageAllowed("50")); assertFalse(f.pageAllowed("51"));
            assertTrue(f.scarlet.data.auditEntryMetadata(AUDIT).snapshotTargetUser.has("obfuscatedEmail"), "Read-time protection must not destructively rewrite legacy data");
            f.metadata.entryRedacted = true; f.save();
            assertFalse(f.pageAllowed("50"), "A cached page must honor new redaction");
        }
    }

    @Test void allButtonsDenyRedactedConfidentialMissingAndMismatchedRecords() throws Exception
    {
        for (int kind = 0; kind < 3; kind++) for (String reason : new String[]{"redacted", "secret", "aux-secret", "missing", "mismatch", "null-entry", "missing-actor", "invalid-target", "policy"})
        {
            Fixture f = new Fixture();
            if (reason.equals("redacted")) f.metadata.entryRedacted = true;
            if (reason.equals("secret")) f.secret.add("ordinary");
            if (reason.equals("aux-secret")) { f.metadata.auxActorId = "secret"; f.secret.add("secret"); }
            if (reason.equals("mismatch")) f.metadata.entry.setId("gaud_10000000-0000-0000-0000-000000000099");
            if (reason.equals("null-entry")) f.metadata.entry = null;
            if (reason.equals("missing-actor")) f.metadata.entry.setActorId(null);
            if (reason.equals("invalid-target")) f.metadata.entry.setTargetId("not-a-user");
            if (reason.equals("missing")) f.customId = OPERATIONS[kind] + ":gaud_10000000-0000-0000-0000-000000000099";
            if (reason.equals("policy")) put(f.scarlet, "secretStaffList", null);
            f.save(); f.click(kind);
            assertTrue(f.embeds.isEmpty(), reason); assertEquals(1, f.denials.size(), reason);
        }
    }

    @Test void confidentialAuthorizedViewerStillReceivesOnlyPublicFieldsAndCannotBypassRedaction() throws Exception
    {
        Fixture f = new Fixture(); f.secret.add("ordinary");
        f.scarlet.data.globalMetadata_setSnowflakeId("50", "ordinary");
        f.click(0); assertFalse(f.embeds.isEmpty()); assertTrue(f.denials.isEmpty()); f.inspectAllRenderedPages();
        assertFalse(ScarletSnapshotPrivacy.user(f.metadata.snapshotTargetUser, USER).toString().contains("PRIVATE_"));
        f.metadata.entryRedacted = true; f.save(); f.embeds.clear(); f.click(0);
        assertTrue(f.embeds.isEmpty()); assertFalse(f.pageAllowed("50"));
    }

    @Test void provenancePermissionsAndCurrentRouteCannotBeBypassedByCopiedButtons() throws Exception
    {
        for (int kind = 0; kind < 3; kind++) for (String reason : new String[]{"guild", "author", "webhook", "message", "thread", "parent", "permission", "channel", "member", "malformed"})
        {
            Fixture f = new Fixture();
            if (reason.equals("guild")) f.guildId = "2";
            if (reason.equals("author")) f.author = "11";
            if (reason.equals("webhook")) f.webhook = true;
            if (reason.equals("message")) f.originId = "102";
            if (reason.equals("thread")) f.threadId = "200";
            if (reason.equals("parent")) f.parentId = "30";
            if (reason.equals("permission")) f.permission = false;
            if (reason.equals("channel")) f.channelAccess = false;
            if (reason.equals("member")) f.missingMember = true;
            if (reason.equals("malformed")) f.customId = OPERATIONS[kind] + ":../outside";
            f.click(kind); assertTrue(f.embeds.isEmpty(), reason); assertEquals(1, f.denials.size(), reason);
        }
    }

    @Test void laterPagesRecheckAuthorityPrivacyAndMetadataInsteadOfTrustingInitialClick() throws Exception
    {
        for (String reason : new String[]{"secret", "permission", "channel", "changed-aux", "policy"})
        {
            Fixture f = new Fixture(); f.click(0); assertTrue(f.pageAllowed("50"));
            if (reason.equals("secret")) f.secret.add("ordinary");
            if (reason.equals("permission")) f.permission = false;
            if (reason.equals("channel")) f.channelAccess = false;
            if (reason.equals("changed-aux")) { f.metadata.auxMessageSnowflake = "102"; f.save(); }
            if (reason.equals("policy")) put(f.scarlet, "secretStaffList", null);
            assertFalse(f.pageAllowed("50"), reason);
        }
    }
}

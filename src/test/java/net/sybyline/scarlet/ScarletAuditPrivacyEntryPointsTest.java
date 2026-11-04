package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.context.MessageContextInteraction;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonInteraction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.sybyline.scarlet.server.discord.DInteractions.Ephemeral;
import net.sybyline.scarlet.server.discord.DPerms;

/** Synthetic Discord entities only: application constructors, clients and network calls never start. */
class ScarletAuditPrivacyEntryPointsTest
{
    @TempDir Path temp;
    static final String AUDIT = "gaud_00000000-0000-0000-0000-000000000001";

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler)
    {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    static Object fluent(Class<?> type)
    {
        return proxy(type, (p, m, a) -> m.getReturnType().isInstance(p) ? p : primitive(m));
    }

    static Object primitive(Method method)
    {
        if (method.getReturnType() == boolean.class) return false;
        if (method.getReturnType() == long.class) return 0L;
        if (method.getReturnType() == int.class) return 0;
        return null;
    }

    static class Permissions extends DPerms
    {
        boolean permitted = true;
        Permissions() { super(null); }
        @Override public boolean checkOperation(Member member, PermType type, String operation)
        {
            assertEquals(PermType.MESSAGE_COMMAND, type);
            assertEquals("fix-audit-thread", operation);
            return this.permitted;
        }
    }

    static class OfflineVRChat extends ScarletVRChat
    {
        Runnable lookup = () -> {};
        private OfflineVRChat() { super(null, null, null); }
        @Override public io.github.vrchatapi.model.User getUser(String id, long epoch)
        {
            this.lookup.run();
            return new io.github.vrchatapi.model.User().id(id).displayName("Synthetic target");
        }
    }

    class Fixture
    {
        final Scarlet scarlet = inert(Scarlet.class);
        final ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        final Set<String> secret = new HashSet<>();
        final Permissions permissions = new Permissions();
        final AtomicBoolean channelAccess = new AtomicBoolean(true);
        final Map<String, String> mappings = new HashMap<>();
        final Map<String, String> routes = new HashMap<>(), secretRoutes = new HashMap<>();
        final AtomicInteger sends = new AtomicInteger();
        final AtomicReference<Object> reply = new AtomicReference<>();
        String author = "10", messageId = "100", channelId = "20", guildId = "1", threadId = "100", parentId = "20";
        boolean webhook;
        Runnable onRetrieve = () -> {};
        final ScarletData.AuditEntryMetadata metadata = ScarletDiscordSafetyTest.metadata(AUDIT, "ordinary");
        final Guild guild;
        Member member;
        Message message;
        ThreadChannel thread;
        final JDA jda;

        Fixture() throws Exception
        {
            put(this.scarlet, "data", new ScarletData(temp.toFile()));
            ScarletSecretStaffList staff = inert(ScarletSecretStaffList.class);
            put(staff, "secretStaffUserIds", this.secret);
            put(this.scarlet, "secretStaffList", staff);
            put(this.discord, "scarlet", this.scarlet);
            put(this.discord, "guildSf", "1");
            put(this.discord, "perms", this.permissions);
            this.routes.put("group.member.join", "20");
            this.secretRoutes.put("group.member.join", "30");
            put(this.discord, "auditType2channelSf", this.routes);
            put(this.discord, "auditType2secretChannelSf", this.secretRoutes);
            put(this.discord, "moderationThreadSf2auditId", this.mappings);
            this.guild = proxy(Guild.class, (p, m, a) -> {
                if (m.getName().equals("getId")) return this.guildId;
                if (m.getName().equals("getMemberById")) return this.member;
                if (m.getName().equals("getThreadChannelById")) return this.thread;
                return primitive(m);
            });
            this.member = proxy(Member.class, (p, m, a) -> {
                if (m.getName().equals("getId")) return "50";
                if (m.getName().equals("getGuild")) return this.guild;
                if (m.getName().equals("hasPermission")) return this.channelAccess.get();
                return primitive(m);
            });
            Object parent = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TextChannel.class, MessageChannelUnion.class, GuildMessageChannelUnion.class}, (p, m, a) -> {
                    if (m.getName().equals("getId")) return this.channelId;
                    if (m.getName().equals("getGuild")) return this.guild;
                    if (m.getName().equals("asTextChannel")) return p;
                    if (m.getName().equals("createThreadChannel")) fail("Unexpected thread creation");
                    return primitive(m);
                });
            this.thread = proxy(ThreadChannel.class, (p, m, a) -> {
                if (m.getName().equals("getId")) return this.threadId;
                if (m.getName().equals("getGuild")) return this.guild;
                if (m.getName().equals("getParentChannel"))
                    return proxy(m.getReturnType(), (x, n, b) -> n.getName().equals("getId") ? this.parentId : primitive(n));
                if (m.getName().equals("sendMessage")) { this.sends.incrementAndGet(); fail("Unexpected send"); }
                if (m.getName().equals("retrieveMessageById"))
                    return proxy(m.getReturnType(), (action, operation, args) -> {
                        if (operation.getName().equals("complete")) { this.onRetrieve.run(); return this.message; }
                        return primitive(operation);
                    });
                return primitive(m);
            });
            this.message = proxy(Message.class, (p, m, a) -> {
                switch (m.getName()) {
                case "getId": return this.messageId;
                case "getGuild": return this.guild;
                case "isFromGuild": return true;
                case "getChannel": case "getGuildChannel": return parent;
                case "getStartedThread": return this.thread;
                case "isWebhookMessage": return this.webhook;
                case "getAuthor": return proxy(net.dv8tion.jda.api.entities.User.class,
                    (u, n, b) -> n.getName().equals("getId") ? this.author : primitive(n));
                case "getEmbeds": return Collections.singletonList(new EmbedBuilder().setFooter(AUDIT).build());
                default: return primitive(m);
                }
            });
            SelfUser self = proxy(SelfUser.class, (p, m, a) -> m.getName().equals("getId") ? "10" : primitive(m));
            this.jda = proxy(JDA.class, (p, m, a) -> m.getName().equals("getSelfUser") ? self : primitive(m));
            put(this.discord, "jda", this.jda);
            this.metadata.guildSnowflake = "1";
            this.metadata.channelSnowflake = "20";
            this.metadata.messageSnowflake = "100";
            this.metadata.auxMessageSnowflake = "101";
            this.metadata.entry.setDescription("Synthetic audit details");
            this.scarlet.data.auditEntryMetadata(AUDIT, this.metadata);
        }

        void assertRepairDeniedWithoutMutation()
        {
            String original = Scarlet.GSON.toJson(this.metadata);
            String persisted = Scarlet.GSON.toJson(this.scarlet.data.auditEntryMetadata(AUDIT));
            assertThrows(IllegalStateException.class, () -> this.discord.emitUserModeration_thread(
                this.scarlet, this.metadata, "ordinary", this.message, "50"));
            assertEquals(original, Scarlet.GSON.toJson(this.metadata));
            assertEquals(persisted, Scarlet.GSON.toJson(this.scarlet.data.auditEntryMetadata(AUDIT)));
            assertTrue(this.mappings.isEmpty());
            assertEquals(0, this.sends.get());
        }

        ButtonInteractionEvent reportEvent()
        {
            ButtonInteraction interaction = proxy(ButtonInteraction.class, (p, m, a) -> {
                if (m.getName().equals("getButton")) return Button.primary("vrchat-report:" + AUDIT, "Report");
                if (m.getName().equals("getMember")) return this.member;
                return primitive(m);
            });
            return new ButtonInteractionEvent(this.jda, 0L, interaction);
        }

        InteractionHook hook()
        {
            return proxy(InteractionHook.class, (p, m, a) -> {
                if (m.getName().equals("sendMessage")) {
                    this.reply.set(a[0]);
                    return fluent(m.getReturnType());
                }
                return primitive(m);
            });
        }

        void report() throws Exception
        {
            ScarletDiscordUI ui = inert(ScarletDiscordUI.class);
            put(ui, "discord", this.discord);
            ui.vrchatReport(this.reportEvent(), this.hook());
        }

        void prepareReport(Runnable duringLookup) throws Exception
        {
            OfflineVRChat vrc = inert(OfflineVRChat.class);
            vrc.lookup = duringLookup;
            put(vrc, "groupId", "grp_synthetic");
            put(vrc, "group", new Group().id("grp_synthetic").name("Synthetic group").shortCode("TEST").discriminator("1"));
            put(this.scarlet, "vrc", vrc);
            ScarletVRChatReportTemplate template = new ScarletVRChatReportTemplate(temp.resolve("template.txt").toFile());
            template.trySet("Actor: {actorId} {actorName}<br>Target: {targetId}");
            put(this.scarlet, "vrcReport", template);
            ScarletSettings.FileValued<?> email = inert(ScarletSettings.FileValued.class);
            put(email, "cached", "moderator@example.invalid");
            put(this.discord, "requestingEmail", email);
            ScarletSettings.FileValued<?> footer = inert(ScarletSettings.FileValued.class);
            put(footer, "cached", false);
            put(this.discord, "appendTemplateFooter", footer);
            // The method reference is resolved even with an empty tag array.
            put(this.scarlet, "moderationTags", inert(ScarletModerationTags.class));
        }
    }

    @Test void identityAndHistoryCommandsUsePrivateInitialDeferral()
    {
        Set<String> required = new HashSet<>(Arrays.asList("discordUserInfo", "vrchatUserInfo",
            "queryActorHistory", "queryTargetHistory", "actorModerationSummary"));
        for (Method method : ScarletDiscordCommands.class.getDeclaredMethods())
            if (required.remove(method.getName())) assertNotNull(method.getAnnotation(Ephemeral.class), method.getName());
        assertTrue(required.isEmpty());
    }

    @Test void historyFiltersRawAuxiliaryRedactedAndUnverifiedEventsAndRechecksSecrecy() throws Exception
    {
        Fixture f = new Fixture();
        assertTrue(f.discord.auditHistoryLine(f.metadata.entry, f.metadata, "50").contains("discord.com/channels/1/20/100"));
        f.secret.add("protected");
        f.metadata.auxActorId = "protected";
        assertNull(f.discord.auditHistoryLine(f.metadata.entry, f.metadata, "50"));
        f.scarlet.data.globalMetadata_setSnowflakeId("50", "protected");
        assertNotNull(f.discord.auditHistoryLine(f.metadata.entry, f.metadata, "50"));
        f.metadata.entryRedacted = true;
        assertNull(f.discord.auditHistoryLine(f.metadata.entry, f.metadata, "50"));
        f.metadata.entryRedacted = false;
        f.scarlet.data.globalMetadata_removeSnowflakeId("50");
        f.metadata.auxActorId = null;
        GroupAuditLogEntryUnknown raw = new GroupAuditLogEntryUnknown().id(AUDIT).actorId("protected");
        assertNull(f.discord.auditHistoryLine(raw, f.metadata, "50"));
        assertNull(f.discord.auditHistoryLine(f.metadata.entry, null, "50"));
        raw.setId("gaud_other");
        assertNull(f.discord.auditHistoryLine(raw, f.metadata, "50"));
        f.secret.add("ordinary");
        assertNull(f.discord.auditHistoryLine(f.metadata.entry, f.metadata, "50"));
    }

    @Test void fabricatedFooterIsRejectedByActualCommandWithoutMutation() throws Exception
    {
        Fixture f = new Fixture();
        f.author = "99";
        String before = Scarlet.GSON.toJson(f.metadata);
        ScarletDiscordCommands commands = inert(ScarletDiscordCommands.class);
        put(commands, "discord", f.discord);
        AtomicBoolean privateReply = new AtomicBoolean();
        AtomicInteger replies = new AtomicInteger();
        MessageContextInteraction interaction = proxy(MessageContextInteraction.class, (p, m, a) -> {
            if (m.getName().equals("getTarget")) return f.message;
            if (m.getName().equals("getMember")) return f.member;
            if (m.getName().equals("deferReply"))
                return proxy(m.getReturnType(), (action, operation, values) -> {
                    if (operation.getName().equals("setContent")) f.reply.set(values[0]);
                    if (operation.getName().equals("setEphemeral")) privateReply.set((Boolean) values[0]);
                    if (operation.getName().equals("queue")) {
                        assertTrue(privateReply.get(), "The rejection must be private before it is queued");
                        replies.incrementAndGet();
                    }
                    return operation.getReturnType().isInstance(action) ? action : primitive(operation);
                });
            return primitive(m);
        });
        commands.fixAuditThread(new MessageContextInteractionEvent(f.jda, 0L, interaction));
        assertEquals(1, replies.get());
        assertTrue(f.reply.get().toString().contains("Nothing changed"));
        assertEquals(before, Scarlet.GSON.toJson(f.metadata));
        assertTrue(f.mappings.isEmpty());
        assertEquals(0, f.sends.get());
    }

    @Test void repairRequiresRecordedAuthorGuildChannelMessageAndThread() throws Exception
    {
        Fixture f = new Fixture();
        assertNull(f.discord.auditThreadProblem(f.metadata, f.message, "50"));
        f.author = "99"; f.assertRepairDeniedWithoutMutation(); f.author = "10";
        f.webhook = true; f.assertRepairDeniedWithoutMutation(); f.webhook = false;
        f.guildId = "2"; f.assertRepairDeniedWithoutMutation(); f.guildId = "1";
        f.channelId = "21"; f.assertRepairDeniedWithoutMutation(); f.channelId = "20";
        f.messageId = "102"; f.assertRepairDeniedWithoutMutation(); f.messageId = "100";
        f.threadId = "102"; f.assertRepairDeniedWithoutMutation(); f.threadId = "100";
        f.parentId = "21"; f.assertRepairDeniedWithoutMutation(); f.parentId = "20";
        f.metadata.threadSnowflake = "102"; f.assertRepairDeniedWithoutMutation(); f.metadata.threadSnowflake = null;
        f.metadata.entryRedacted = true; f.assertRepairDeniedWithoutMutation(); f.metadata.entryRedacted = false;
        f.metadata.parentEventId = "gaud_parent"; f.assertRepairDeniedWithoutMutation();
    }

    @Test void secretRequesterCannotRepairConfidentialEventInPreviouslyPublicDestination() throws Exception
    {
        Fixture f = new Fixture();
        f.secret.add("protected");
        f.scarlet.data.globalMetadata_setSnowflakeId("50", "protected");
        f.metadata.auxActorId = "protected";
        assertTrue(f.discord.auditVisibleTo(f.metadata, "50"));
        f.assertRepairDeniedWithoutMutation();
        f.metadata.channelSnowflake = f.channelId = f.parentId = "30";
        f.scarlet.data.auditEntryMetadata(AUDIT, f.metadata);
        assertNull(f.discord.auditThreadProblem(f.metadata, f.message, "50"));
        f.scarlet.data.globalMetadata_removeSnowflakeId("50");
        f.assertRepairDeniedWithoutMutation();
    }

    @Test void queuedRepairRechecksCommandChannelAndConfidentialPermissions() throws Exception
    {
        Fixture f = new Fixture();
        assertNull(f.discord.auditThreadProblem(f.metadata, f.message, "50"));
        f.permissions.permitted = false; f.assertRepairDeniedWithoutMutation(); f.permissions.permitted = true;
        f.channelAccess.set(false); f.assertRepairDeniedWithoutMutation(); f.channelAccess.set(true);
        f.onRetrieve = () -> f.permissions.permitted = false;
        f.assertRepairDeniedWithoutMutation();
    }

    @Test void repairRechecksRoutingAfterLookupBeforeMappingMutation() throws Exception
    {
        Fixture f = new Fixture();
        f.onRetrieve = () -> f.routes.put("group.member.join", "21");
        f.assertRepairDeniedWithoutMutation();
    }

    @Test void queuedRepairCannotRestoreOldMetadataAfterConcurrentRedaction() throws Exception
    {
        Fixture f = new Fixture();
        AtomicReference<String> updated = new AtomicReference<>();
        f.onRetrieve = () -> {
            ScarletData.AuditEntryMetadata current = f.scarlet.data.auditEntryMetadata(AUDIT);
            current.entryRedacted = true;
            f.scarlet.data.auditEntryMetadata(AUDIT, current);
            updated.set(Scarlet.GSON.toJson(current));
        };
        String queued = Scarlet.GSON.toJson(f.metadata);
        assertThrows(IllegalStateException.class, () -> f.discord.emitUserModeration_thread(
            f.scarlet, f.metadata, "ordinary", f.message, "50"));
        assertEquals(queued, Scarlet.GSON.toJson(f.metadata));
        assertEquals(updated.get(), Scarlet.GSON.toJson(f.scarlet.data.auditEntryMetadata(AUDIT)));
        assertTrue(f.mappings.isEmpty());
        assertEquals(0, f.sends.get());
    }

    @Test void validRecordedRepairRetainsOriginalThread() throws Exception
    {
        Fixture f = new Fixture();
        assertSame(f.thread, f.discord.emitUserModeration_thread(f.scarlet, f.metadata, "ordinary", f.message, "50"));
        assertEquals("100", f.metadata.threadSnowflake);
        assertEquals(AUDIT, f.mappings.get("100"));
        assertEquals(0, f.sends.get());
    }

    @Test void oldReportButtonRechecksRawAuxiliaryAndRedactedPolicyBeforeLookup() throws Exception
    {
        Fixture f = new Fixture();
        // No VRChat client or template exists: a rejection must occur before any lookup/expansion.
        f.secret.add("ordinary");
        f.report(); assertTrue(f.reply.get().toString().contains("unavailable"));
        f.secret.clear(); f.secret.add("protected"); f.metadata.auxActorId = "protected";
        f.scarlet.data.auditEntryMetadata(AUDIT, f.metadata);
        f.report(); assertTrue(f.reply.get().toString().contains("unavailable"));
        f.metadata.auxActorId = null; f.metadata.entryRedacted = true;
        f.scarlet.data.auditEntryMetadata(AUDIT, f.metadata);
        f.report(); assertTrue(f.reply.get().toString().contains("unavailable"));
    }

    @Test void reportTemplateDoesNotLeakActorAfterSecrecyChangesDuringLookup() throws Exception
    {
        Fixture f = new Fixture();
        f.prepareReport(() -> f.secret.add("ordinary"));
        f.report();
        assertTrue(f.reply.get() instanceof String);
        assertFalse(f.reply.get().toString().contains("ordinary"));
        assertTrue(f.reply.get().toString().contains("unavailable"));
    }

    @Test void authorizedSecretRequesterReceivesTemplateOnlyInPrivateReport() throws Exception
    {
        Fixture f = new Fixture();
        f.secret.add("ordinary");
        f.scarlet.data.globalMetadata_setSnowflakeId("50", "ordinary");
        f.prepareReport(() -> {});
        f.report();
        assertTrue(f.reply.get() instanceof MessageCreateData);
        try (MessageCreateData data = (MessageCreateData) f.reply.get()) {
            assertTrue(data.getContent().contains("Actor: ordinary ordinary"));
        }
        assertNotNull(ScarletDiscordUI.class.getMethod("vrchatReport", ButtonInteractionEvent.class, InteractionHook.class)
            .getAnnotation(Ephemeral.class));
    }
}

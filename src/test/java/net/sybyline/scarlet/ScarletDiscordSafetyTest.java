package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.sybyline.scarlet.server.discord.DInteractions.Ephemeral;

/** Offline regression tests: application constructors and network clients never start. */
class ScarletDiscordSafetyTest
{
    @TempDir Path temp;

    static <T> T inert(Class<T> type) throws Exception
    {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    static void put(Object object, String name, Object value) throws Exception
    {
        Class<?> type = object.getClass();
        Field field;
        while (true)
        {
            try { field = type.getDeclaredField(name); break; }
            catch (NoSuchFieldException ex) { type = type.getSuperclass(); if (type == null) throw ex; }
        }
        field.setAccessible(true);
        field.set(object, value);
    }

    static ScarletData.AuditEntryMetadata metadata(String id, String actor)
    {
        ScarletData.AuditEntryMetadata meta = new ScarletData.AuditEntryMetadata();
        meta.entry = new GroupAuditLogEntryUnknown().id(id).actorId(actor).actorDisplayName(actor)
            .targetId("usr_training-target").eventType("group.member.join")
            .createdAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return meta;
    }

    Scarlet context() throws Exception
    {
        Scarlet scarlet = inert(Scarlet.class);
        put(scarlet, "data", new ScarletData(temp.toFile()));
        ScarletVRChat vrc = inert(ScarletVRChat.class);
        put(vrc, "currentUserId", "usr_synthetic_bot");
        put(scarlet, "vrc", vrc);
        ScarletSecretStaffList secret = inert(ScarletSecretStaffList.class);
        put(secret, "secretStaffUserIds", new HashSet<>(Arrays.asList("usr_training-secret")));
        put(scarlet, "secretStaffList", secret);
        return scarlet;
    }

    static ScarletDiscord discord(java.lang.reflect.InvocationHandler handler)
    {
        return (ScarletDiscord) Proxy.newProxyInstance(ScarletDiscordSafetyTest.class.getClassLoader(),
            new Class<?>[]{ScarletDiscord.class}, (proxy, method, arguments) -> {
                if (!method.isDefault()) return handler.invoke(proxy, method, arguments);
                return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                    .unreflectSpecial(method, method.getDeclaringClass()).bindTo(proxy)
                    .invokeWithArguments(arguments == null ? new Object[0] : arguments);
            });
    }

    @Test void confidentialActorAndAuxiliaryActorAreFilteredBeforeHistoryAndWebhooks()
    {
        ScarletData.AuditEntryMetadata meta = metadata("gaud_test", "ordinary");
        assertTrue(ScarletAuditPrivacy.visible(meta, "secret"::equals, false));
        meta.auxActorId = "secret";
        assertFalse(ScarletAuditPrivacy.visible(meta, "secret"::equals, false));
        assertFalse(ScarletAuditPrivacy.auxiliaryAllowed(meta, "secret"::equals));
        assertTrue(ScarletAuditPrivacy.visible(meta, "secret"::equals, true));
        meta.entryRedacted = true;
        assertFalse(ScarletAuditPrivacy.visible(meta, "secret"::equals, true));
        meta.entryRedacted = false;
        meta.auxActorId = null;
        meta.entry.setActorId("secret");
        assertFalse(ScarletAuditPrivacy.auxiliaryAllowed(meta, "secret"::equals));
        meta.entry.setActorId("ordinary");
        meta.entry.setId(ScarletSimulation.TRAINING_AUDIT_ID_PREFIX + "test");
        assertFalse(ScarletAuditPrivacy.auxiliaryAllowed(meta, "secret"::equals));
    }

    @Test void ordinaryModerationNeverCopiesSecretHistory() throws Exception
    {
        Scarlet scarlet = context();
        ScarletData.AuditEntryMetadata secret = metadata("gaud_previous", "usr_training-secret");
        secret.entry.setEventType("group.instance.warn");
        scarlet.data.auditEntryMetadata(secret.entry.getId(), secret);
        ScarletData.UserMetadata user = new ScarletData.UserMetadata();
        user.addAuditEntryId(secret.entry.getId());
        scarlet.data.userMetadata("usr_training-target", user);
        AtomicReference<String> recent = new AtomicReference<>();
        ScarletDiscord discord = discord((proxy, method, arguments) -> {
            if (method.getName().equals("emitUserModeration")) recent.set((String) arguments[7]);
            return null;
        });
        ScarletData.AuditEntryMetadata current = metadata("gaud_current", "usr_training-normal");
        current.entry.setEventType("group.instance.warn");
        current.entry.setCreatedAt(secret.entry.getCreatedAt().plusMinutes(1));
        discord.processUserModeration(scarlet, current);
        assertNull(recent.get());
    }

    @Test void secretAuxiliaryRoutesNeverCreateClientsOrPayloads() throws Exception
    {
        Scarlet scarlet = context();
        ScarletDiscordJDA jda = inert(ScarletDiscordJDA.class);
        put(jda, "scarlet", scarlet);
        for (String type : new String[]{"group.instance.create", "group.instance.close", "group.post.create"})
        {
            ScarletData.AuditEntryMetadata meta = metadata("gaud_test", "usr_training-secret");
            meta.entry.setEventType(type);
            jda.emitAuxWh(meta, () -> { fail("No payload may be built for a confidential auxiliary route"); return ""; },
                (client, state) -> { fail("No client may receive confidential content"); return null; });
        }
    }

    @Test void webhookListingIsPrivateAndNeverReturnsCredentialText() throws Exception
    {
        assertFalse(ScarletAuditPrivacy.redactedWebhook("https://discord.com/api/webhooks/123/SYNTHETIC_SECRET").contains("SYNTHETIC_SECRET"));
        for (java.lang.reflect.Method method : ScarletDiscordCommands.AuxWebhooks.class.getDeclaredMethods())
            if (method.getName().equals("list")) assertNotNull(method.getAnnotation(Ephemeral.class));
        for (java.lang.reflect.Method method : ScarletDiscordCommands.SecretStaffList.class.getDeclaredMethods())
            if (method.getName().equals("list")) assertNotNull(method.getAnnotation(Ephemeral.class));
        for (java.lang.reflect.Method method : ScarletDiscordCommands.class.getDeclaredMethods())
            if (method.getName().equals("moderationLog") || method.getName().equals("vrchatUserInfo"))
                assertNotNull(method.getAnnotation(Ephemeral.class));
    }

    @Test void failedDeliverySurvivesRestartAndSuccessfulRetryDeduplicates() throws Exception
    {
        Scarlet scarlet = context();
        AtomicInteger attempts = new AtomicInteger();
        ScarletDiscord discord = discord((proxy, method, arguments) -> {
            if (method.getName().equals("emitMemberJoin"))
            {
                ScarletData.AuditEntryMetadata meta = (ScarletData.AuditEntryMetadata) arguments[1];
                if (attempts.incrementAndGet() == 1)
                {
                    meta.setAuxData("deliveryAux:alreadyAcknowledged", true);
                    throw new IllegalStateException("Synthetic delivery failure");
                }
                assertTrue(meta.getAuxData("deliveryAux:alreadyAcknowledged", false, e -> e.getAsBoolean()));
                meta.setAuxData("deliveryAcknowledged", true);
            }
            return null;
        });
        GroupAuditLogEntryUnknown entry = metadata("gaud_retry", "usr_actor").entry;
        discord.process(scarlet, entry);
        assertEquals(1, attempts.get());
        assertFalse(discord.isAuditProcessed(scarlet, entry.getId()));
        assertFalse(scarlet.data.auditEntryMetadataExists(entry.getId()));
        discord.process(scarlet, entry);
        assertEquals(1, attempts.get(), "Backoff prevents immediate re-emission");
        ScarletAuditOutbox restarted = new ScarletAuditOutbox(temp.toFile());
        ScarletData.AuditEntryMetadata pending = restarted.read(entry.getId());
        pending.setAuxData("deliveryRetryAt", 0L);
        restarted.save(pending);
        discord.retryPending(scarlet);
        assertEquals(2, attempts.get());
        assertTrue(discord.isAuditProcessed(scarlet, entry.getId()));
        assertFalse(restarted.exists(entry.getId()));
        discord.process(scarlet, entry);
        assertEquals(2, attempts.get());
        assertEquals(300_000L, ScarletAuditOutbox.retryDelay(100));
    }

    @Test void terminalReceiptDoesNotReemitWhenMetadataNeedsRecovery() throws Exception
    {
        Scarlet scarlet = context();
        ScarletData.AuditEntryMetadata completed = metadata("gaud_terminal", "usr_actor");
        completed.setAuxData("deliveryState", "delivered");
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(temp.toFile());
        outbox.save(completed);
        ScarletDiscord discord = discord((proxy, method, arguments) -> { fail("No outbound action is permitted for a terminal receipt"); return null; });
        discord.retryPending(scarlet);
        assertTrue(discord.isAuditProcessed(scarlet, completed.entry.getId()));
        assertFalse(outbox.exists(completed.entry.getId()));
        assertThrows(IllegalArgumentException.class, () -> outbox.file("../outside"));
    }

    @Test void actualAuxiliaryRetryDoesNotRepeatAcknowledgedDestinations() throws Exception
    {
        assertTrue(Features.AUX_WEBHOOKS_ENABLED);
        Scarlet scarlet = context();
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        put(discord, "scarlet", scarlet);
        net.dv8tion.jda.api.entities.IncomingWebhookClient first = (net.dv8tion.jda.api.entities.IncomingWebhookClient)
            Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{net.dv8tion.jda.api.entities.IncomingWebhookClient.class}, (p, m, a) -> null);
        net.dv8tion.jda.api.entities.IncomingWebhookClient second = (net.dv8tion.jda.api.entities.IncomingWebhookClient)
            Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{net.dv8tion.jda.api.entities.IncomingWebhookClient.class}, (p, m, a) -> null);
        java.util.Map<String, net.dv8tion.jda.api.entities.IncomingWebhookClient> clients = new java.util.HashMap<>();
        clients.put("first", first); clients.put("second", second);
        put(discord, "scarletAuxWh2incomingWebhookClient", clients);
        put(discord, "auditType2scarletAuxWh", java.util.Collections.singletonMap("group.member.join", new net.sybyline.scarlet.util.UniqueStrings("first", "second")));
        AtomicInteger firstSends = new AtomicInteger(), secondSends = new AtomicInteger();
        java.util.function.Function<net.dv8tion.jda.api.entities.IncomingWebhookClient, net.dv8tion.jda.api.requests.restaction.AbstractWebhookMessageAction<?, ?>> action = client ->
            (net.dv8tion.jda.api.requests.restaction.AbstractWebhookMessageAction<?, ?>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction.class}, (p, method, args) -> {
                    if (method.getName().equals("completeAfter"))
                    {
                        if (client == first) firstSends.incrementAndGet();
                        else if (secondSends.incrementAndGet() == 1) throw new IllegalStateException("Synthetic auxiliary failure");
                        return null;
                    }
                    return p;
                });
        ScarletData.AuditEntryMetadata pending = metadata("gaud_aux_retry", "usr_actor");
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(temp.toFile());
        outbox.save(pending);
        assertThrows(IllegalStateException.class, () -> discord.emitAuxWh(pending, action));
        // Route sets need not preserve insertion order; retry both and assert exactly one success per route.
        discord.emitAuxWh(outbox.read(pending.entry.getId()), action);
        assertEquals(1, firstSends.get());
        assertEquals(2, secondSends.get());
        ScarletData.AuditEntryMetadata restored = outbox.read(pending.entry.getId());
        assertTrue(restored.getAuxData("deliveryAux:first", false, e -> e.getAsBoolean()));
        assertTrue(restored.getAuxData("deliveryAux:second", false, e -> e.getAsBoolean()));
        restored.guildSnowflake = "1"; restored.channelSnowflake = "2"; restored.messageSnowflake = "3";
        assertTrue(discord.condEmit(restored, (channel, guild, target) -> { fail("Acknowledged main send must not repeat"); return null; }));
    }

    static Member member(String id, boolean permitted, boolean above, boolean owner, String guildId)
    {
        Guild guild = (Guild) Proxy.newProxyInstance(ScarletDiscordSafetyTest.class.getClassLoader(), new Class<?>[]{Guild.class},
            (proxy, method, args) -> method.getName().equals("getId") ? guildId : null);
        return (Member) Proxy.newProxyInstance(ScarletDiscordSafetyTest.class.getClassLoader(), new Class<?>[]{Member.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                case "getId": return id;
                case "getGuild": return guild;
                case "hasPermission": return permitted;
                case "canInteract": return above;
                case "isOwner": return owner;
                default: return null;
                }
            });
    }

    @Test void nativePermissionAndHumanHierarchyAreBothRequired()
    {
        Member target = member("target", true, true, false, "guild");
        for (Permission permission : new Permission[]{Permission.KICK_MEMBERS, Permission.BAN_MEMBERS})
        {
            assertTrue(DiscordModerationAuthorization.allowed(member("actor", true, true, false, "guild"), target, permission));
            assertFalse(DiscordModerationAuthorization.allowed(member("actor", true, false, false, "guild"), target, permission));
            assertFalse(DiscordModerationAuthorization.allowed(member("actor", false, true, false, "guild"), target, permission));
            assertFalse(DiscordModerationAuthorization.allowed(member("target", true, true, false, "guild"), target, permission));
            assertFalse(DiscordModerationAuthorization.allowed(member("actor", true, true, false, "other"), target, permission));
            assertFalse(DiscordModerationAuthorization.allowed(null, target, permission));
            assertFalse(DiscordModerationAuthorization.allowed(target, member("owner", true, true, true, "guild"), permission));
        }
    }

    @Test void confirmationCannotBeStolenReplayedOrUsedForAnotherAction() throws Exception
    {
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        put(discord, "pendingDiscordModeration", new java.util.concurrent.ConcurrentHashMap<>());
        String token = discord.prepareDiscordModeration("owner", "target", "Synthetic full reason", Permission.BAN_MEMBERS);
        assertNull(discord.consumeDiscordModeration(token, "other", Permission.BAN_MEMBERS));
        assertNull(discord.consumeDiscordModeration(token, "owner", Permission.KICK_MEMBERS));
        ScarletDiscordJDA.PendingDiscordModeration pending = discord.consumeDiscordModeration(token, "owner", Permission.BAN_MEMBERS);
        assertEquals("Synthetic full reason", pending.reason);
        assertEquals("target", pending.target);
        assertNull(discord.consumeDiscordModeration(token, "owner", Permission.BAN_MEMBERS));
    }

    @Test void editingOldAuxiliaryMessageDoesNotRestoreConfidentialNameOrDescription() throws Exception
    {
        Scarlet scarlet = context();
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        put(discord, "scarlet", scarlet);
        ScarletDiscordUI ui = inert(ScarletDiscordUI.class);
        put(ui, "discord", discord);
        ScarletData.AuditEntryMetadata meta = metadata("gaud_edit", "usr_training-secret");
        meta.guildSnowflake = "1";
        meta.channelSnowflake = "2";
        meta.messageSnowflake = "3";
        meta.auxMessageSnowflake = "4";
        meta.entryDescription = "Confidential name mentioned here";
        AtomicReference<String> sent = new AtomicReference<>();
        net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel = (net.dv8tion.jda.api.entities.channel.middleman.MessageChannel)
            Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{net.dv8tion.jda.api.entities.channel.middleman.MessageChannel.class},
                (p, method, arguments) -> {
                    if (!method.getName().equals("editMessageById")) throw new AssertionError(method.getName());
                    sent.set(arguments[1].toString());
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{method.getReturnType()},
                        (action, operation, values) -> operation.getReturnType().isInstance(action) ? action : null);
                });
        ui.updateAuxMessage(channel, meta);
        assertNotNull(sent.get());
        assertTrue(sent.get().contains("Confidential staff action"));
        assertFalse(sent.get().contains("usr_training-secret"));
        assertFalse(sent.get().contains("Confidential name mentioned here"));
    }
}

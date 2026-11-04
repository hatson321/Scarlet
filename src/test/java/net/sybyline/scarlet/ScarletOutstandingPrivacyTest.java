package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

class ScarletOutstandingPrivacyTest
{
    @TempDir Path directory;
    static class FakeVrc extends ScarletVRChat
    {
        List<GroupAuditLogEntryUnknown> events;
        FakeVrc() { super(null, null, null); }
        @Override public List<GroupAuditLogEntryUnknown> auditQuery(OffsetDateTime from, OffsetDateTime to, String actor, String types, String target)
        { return events; }
    }
    static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler calls)
    { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, calls)); }

    final class Fixture
    {
        final Scarlet scarlet = inert(Scarlet.class);
        final ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        final FakeVrc vrc = inert(FakeVrc.class);
        final ScarletSecretStaffList secrets = inert(ScarletSecretStaffList.class);
        final ScarletData data = new ScarletData(directory.toFile());
        final List<String> pages = new ArrayList<>();
        Runnable work, beforeSend = () -> {};
        final MessageChannelUnion channel = proxy(MessageChannelUnion.class, (p, m, a) -> {
            if (m.getName().equals("getType")) return ChannelType.TEXT;
            if (m.getName().equals("sendMessage")) return action(a[0].toString());
            return null;
        });
        final Message message = proxy(Message.class, (p, m, a) -> {
            if (m.getName().equals("getChannel")) return channel;
            if (m.getName().equals("isEphemeral")) return true;
            return null;
        });

        Fixture() throws Exception
        {
            vrc.groupId = "grp_synthetic";
            vrc.events = new ArrayList<>();
            put(scarlet, "vrc", vrc);
            put(scarlet, "data", data);
            put(scarlet, "secretStaffList", secrets);
            put(secrets, "secretStaffUserIds", new HashSet<String>());
            put(discord, "scarlet", scarlet);
            put(scarlet, "exec", proxy(ScheduledExecutorService.class, (p, m, a) -> {
                if (m.getName().equals("submit")) work = (Runnable) a[0];
                return null;
            }));
            ScarletSettings settings = inert(ScarletSettings.class);
            put(settings, "fileValuedSettings", new HashMap<>());
            for (String name : List.of("instanceWarn", "instanceKick", "memberRemove", "userBan", "userUnban"))
            {
                ScarletSettings.FileValuedBoolean setting = settings.new FileValuedBoolean("synthetic_" + name, "Synthetic", true);
                put(setting, "cached", true);
                put(discord, "pingOnOutstandingModeration_" + name, setting);
            }
        }

        void event(String id, boolean saved)
        {
            ScarletData.AuditEntryMetadata metadata = new ScarletData.AuditEntryMetadata();
            metadata.entry = new GroupAuditLogEntryUnknown().id(id).groupId(vrc.groupId).actorId("usr_synthetic")
                .actorDisplayName("Synthetic actor").eventType("group.user.ban").description("Synthetic action")
                .createdAt(OffsetDateTime.now().minusHours(1));
            metadata.guildSnowflake = "1"; metadata.channelSnowflake = "2"; metadata.messageSnowflake = "3";
            vrc.events.add(metadata.entry);
            if (saved) data.auditEntryMetadata(id, metadata);
        }

        MessageCreateAction action(String page)
        {
            BooleanSupplier[] check = { () -> true };
            return proxy(MessageCreateAction.class, (p, m, a) -> {
                if (m.getName().equals("setCheck")) { check[0] = (BooleanSupplier) a[0]; return p; }
                if (m.getName().equals("complete") || m.getName().equals("completeAfter"))
                {
                    if (page != null) beforeSend.run();
                    if (!check[0].getAsBoolean()) throw new IllegalStateException("cancelled");
                    if (page != null) pages.add(page);
                    return message;
                }
                return m.getReturnType().isInstance(p) ? p : null;
            });
        }
        void queue() { discord.emitOutstandingMod(scarlet, OffsetDateTime.now(), 24, embed -> action(null)); }
    }

    @Test void missingLocalMetadataDoesNotDiscardLaterValidEntries() throws Exception
    {
        Fixture f = new Fixture();
        f.event("gaud_missing", false); f.event("gaud_saved", true);
        f.queue(); f.work.run();
        assertEquals(1, f.pages.size());
        assertTrue(f.pages.get(0).contains("Synthetic action"));
    }

    @Test void privacyChangesBeforeWorkerOrAtDeferredSendCancelPages() throws Exception
    {
        for (boolean late : new boolean[]{false, true})
            for (boolean secret : new boolean[]{false, true})
            {
                Fixture f = new Fixture(); f.event("gaud_saved", true); f.queue();
                Runnable change = () -> {
                    if (secret) f.secrets.secretStaffUserIds.add("usr_synthetic");
                    else { f.discord.beginLocalSummaryPrivacyChange(); f.discord.endLocalSummaryPrivacyChange(); }
                };
                if (late) f.beforeSend = change; else change.run();
                f.work.run();
                assertTrue(f.pages.isEmpty(), "Private or redacted results must not pass the deferred-send check");
            }
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.*;

import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

class ScarletDiscordReadinessTest
{
    @TempDir Path temp;

    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler)
    {
        return (T) Proxy.newProxyInstance(ScarletDiscordReadinessTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    @Test void ordinarySocketTimeoutReachesFailureHandler()
    {
        AtomicInteger called = new AtomicInteger();
        ScarletDiscordJDA.handleRestFailure(new SocketTimeoutException("Synthetic timeout"), false, failure -> called.incrementAndGet());
        assertEquals(1, called.get());
        ScarletDiscordJDA.handleRestFailure(new RuntimeException(new SocketTimeoutException("Synthetic shutdown")), true, failure -> called.incrementAndGet());
        assertEquals(1, called.get());
        ScarletDiscordJDA.handleRestFailure(new IllegalStateException("Synthetic non-interruption"), true, failure -> called.incrementAndGet());
        assertEquals(2, called.get());
    }

    @Test void invalidWebhookDiagnosticOmitsCredentialsAndDoesNotDiscardConfiguration() throws Exception
    {
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        String credential = "SYNTHETIC_SECRET_NEVER_DISCLOSE";
        Map<String, String> configured = new HashMap<>();
        configured.put("synthetic", "not-a-valid-url/" + credential);
        put(discord, "scarletAuxWh2webhookUrl", configured);
        put(discord, "scarletAuxWh2incomingWebhookClient", new HashMap<>());
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CountDownLatch logged = new java.util.concurrent.CountDownLatch(1);
        java.io.PrintStream capture = new java.io.PrintStream(new java.io.OutputStream() {
            @Override public synchronized void write(int value) {
                output.write(value);
                if (output.toString().contains("credential value omitted")) logged.countDown();
            }
        }, true, "UTF-8");
        java.io.PrintStream previous = System.err;
        try
        {
            System.setErr(capture);
            assertNull(discord.getOrCreateIWC("synthetic"));
            assertTrue(logged.await(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        finally { System.setErr(previous); }
        assertFalse(output.toString("UTF-8").contains(credential));
        assertTrue(configured.containsKey("synthetic"));
    }

    static class CapturingDiscord extends ScarletDiscordJDA
    {
        GuildMessageChannel channel;
        CapturingDiscord() { super(null, null, null); throw new AssertionError("Never start the application in tests"); }
        @Override boolean condEmit(ScarletData.AuditEntryMetadata metadata, CondEmit emit)
        {
            emit.emit("123", null, channel);
            return true;
        }
    }

    @Test void actualGenericEmitterBoundsLongUnicodeAndManyFields() throws Exception
    {
        CapturingDiscord discord = inert(CapturingDiscord.class);
        put(discord, "auditType2color", new HashMap<>());
        AtomicReference<MessageEmbed> sent = new AtomicReference<>();
        Message message = proxy(Message.class, (p, m, a) -> m.getName().equals("getId") ? "123" : null);
        MessageCreateAction action = proxy(MessageCreateAction.class, (p, m, a) -> m.getName().startsWith("complete") ? message : p);
        discord.channel = proxy(GuildMessageChannel.class, (p, m, a) -> {
            if (m.getName().equals("sendMessageEmbeds"))
            {
                sent.set(a[0] instanceof Collection ? ((Collection<MessageEmbed>) a[0]).iterator().next() : (MessageEmbed) a[0]);
                return action;
            }
            throw new AssertionError("Unexpected network operation " + m.getName());
        });
        ScarletData.AuditEntryMetadata meta = metadata("gaud_oversized", "usr_ordinary");
        meta.entry.setEventType("group.create");
        meta.entry.setDescription("Synthetic long description " + "\uD83D\uDE42".repeat(3000));
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int index = 0; index < 40; index++) payload.put("field" + index + "x".repeat(300), "\uD83D\uDE42".repeat(700));
        meta.entry.setData(ScarletAuditData.fromJson(Scarlet.GSON.toJsonTree(payload).getAsJsonObject()));
        discord.emitDefault(null, meta);
        MessageEmbed embed = sent.get();
        assertNotNull(embed);
        assertTrue(embed.getLength() <= 6000);
        assertTrue(embed.getFields().size() <= 25);
        assertTrue(embed.getDescription().contains("[truncated]"));
        for (MessageEmbed.Field field : embed.getFields())
        {
            assertTrue(field.getName().length() <= 256);
            assertTrue(field.getValue().length() <= 1024);
        }
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("Additional data")));
    }

    static class FixtureVrc extends ScarletVRChat
    {
        FixtureVrc() { super(null, null, null); throw new AssertionError("Never start an API client"); }
        @Override public List<GroupAuditLogEntryUnknown> auditQueryHistorical(OffsetDateTime from, OffsetDateTime to, String actor, String types, String target)
        { throw new AssertionError("A local summary must not query VRChat"); }
        @Override public List<GroupAuditLogEntryUnknown> auditQuery(OffsetDateTime from, OffsetDateTime to, String actor, String types, String target)
        { throw new AssertionError("A local summary must not query VRChat"); }
        @Override public io.github.vrchatapi.model.User getUser(String userId)
        { throw new AssertionError("A local summary must not fetch profile names"); }
        @Override public io.github.vrchatapi.model.User getUser(String userId, long minEpoch)
        { throw new AssertionError("A local summary must not fetch profile names"); }
    }

    @Test void actualSummaryOmitsStaffWhoAreAlsoOnSecretList() throws Exception
    {
        Scarlet scarlet = inert(Scarlet.class);
        put(scarlet, "data", new ScarletData(temp.toFile()));
        ScarletSecretStaffList secrets = inert(ScarletSecretStaffList.class);
        put(secrets, "secretStaffUserIds", new HashSet<>(Arrays.asList("usr_secret")));
        put(scarlet, "secretStaffList", secrets);
        ScarletStaffList staff = inert(ScarletStaffList.class);
        put(staff, "staffUserIds", new HashSet<>(Arrays.asList("usr_secret")));
        put(staff, "staffNames", new HashMap<>());
        put(scarlet, "staffList", staff);
        FixtureVrc vrc = inert(FixtureVrc.class);
        put(vrc, "groupId", "grp_test");
        ScarletData.AuditEntryMetadata saved = metadata("gaud_secret", "usr_secret");
        saved.entry.eventType("group.instance.warn").groupId("grp_test").createdAt(OffsetDateTime.now().minusHours(1));
        scarlet.data.auditEntryMetadata(saved.entry.getId(), saved);
        put(scarlet, "vrc", vrc);
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        put(discord, "scarlet", scarlet);
        ScarletSettings settings = inert(ScarletSettings.class);
        put(settings, "fileValuedSettings", new HashMap<>());
        ScarletSettings.FileValuedBoolean onlyActivity = settings.new FileValuedBoolean("synthetic", "Synthetic", false);
        put(onlyActivity, "cached", false);
        put(discord, "moderationSummary_onlyActivity", onlyActivity);
        List<MessageEmbed> sent = new ArrayList<>();
        Message message = proxy(Message.class, (p, m, a) -> m.getName().equals("getId") ? "123" : null);
        MessageCreateAction action = proxy(MessageCreateAction.class, (p, m, a) -> m.getName().startsWith("complete") ? message : p);
        discord.emitModSummary(scarlet, OffsetDateTime.now(), 24, embed -> { sent.add(embed); return action; });
        assertFalse(sent.isEmpty());
        assertEquals("Moderation Summary (saved local history)", sent.get(0).getTitle());
        assertFalse(sent.stream().anyMatch(embed -> String.valueOf(embed.getDescription()).contains("usr_secret")));
        assertTrue(sent.stream().anyMatch(embed -> String.valueOf(embed.getDescription()).contains("Staff totals: 0 / 0 / 0 / 0 / 0")));
    }
}

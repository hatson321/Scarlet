package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.*;
import java.lang.reflect.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import io.github.vrchatapi.model.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.sybyline.scarlet.util.BoundedSerialWork;

class ScarletSuggestedModerationTest
{
    @TempDir Path temp;
    static class Vrc extends ScarletVRChat
    {
        Vrc() { super(null, null, null); }
        AtomicInteger queries;
        CountDownLatch entered, release;
        @Override public List<GroupAuditLogEntryUnknown> auditQuery(OffsetDateTime from, OffsetDateTime to, String actor, String type, String target)
        {
            queries.incrementAndGet(); entered.countDown();
            try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            return Collections.emptyList();
        }
    }
    static class Discord extends ScarletDiscordJDA
    {
        Discord() { super(null, null, null); }
        AtomicInteger sends;
        @Override void condEmitEx(GroupAuditTypeEx type, boolean log, boolean secret, String location, CondEmit send) { sends.incrementAndGet(); }
    }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, java.util.function.Function<String,Object> value)
    { return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> value.apply(m.getName())); }

    @Test void missingRouteDoesNotQueryAndSlowLookupCoalescesWithoutHoldingCaller() throws Exception
    {
        Scarlet scarlet = inert(Scarlet.class); Vrc vrc = inert(Vrc.class); Discord discord = inert(Discord.class);
        vrc.queries = new AtomicInteger(); vrc.entered = new CountDownLatch(1); vrc.release = new CountDownLatch(1); vrc.groupId = "group";
        discord.sends = new AtomicInteger(); discord.guildSf = "guild"; discord.auditExType2channelSf = new ConcurrentHashMap<>();
        put(scarlet, "vrc", vrc); put(scarlet, "settings", new ScarletSettings(null, temp.resolve("settings.json").toFile(), false)); put(discord, "scarlet", scarlet);
        TextChannel channel = proxy(TextChannel.class, name -> name.equals("getId") ? "route" : null);
        Guild guild = proxy(Guild.class, name -> name.equals("getTextChannelById") ? channel : name.equals("getId") ? "guild" : null);
        put(discord, "jda", proxy(JDA.class, name -> name.equals("getGuildById") ? guild : null));
        User target = new User().id("usr_synthetic").displayName("Synthetic");
        discord.tryEmitExtendedSuggestedModeration(scarlet, target); assertEquals(0, vrc.queries.get());
        discord.auditExType2channelSf.put(GroupAuditTypeEx.SUGGESTED_MODERATION.id, "route");
        Field field = ScarletDiscordJDA.class.getDeclaredField("suggestedWorker"); field.setAccessible(true);
        try
        {
            assertTimeout(java.time.Duration.ofSeconds(1), () -> discord.tryEmitExtendedSuggestedModeration(scarlet, target));
            assertTrue(vrc.entered.await(2, TimeUnit.SECONDS));
            for (int i=0;i<100;i++) discord.tryEmitExtendedSuggestedModeration(scarlet, target);
            assertEquals(1, vrc.queries.get());
            discord.auditExType2channelSf.put(GroupAuditTypeEx.SUGGESTED_MODERATION.id, "changed");
            vrc.release.countDown();
            BoundedSerialWork worker = (BoundedSerialWork)field.get(discord);
            worker.submit("barrier", current -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(0, discord.sends.get(), "Stale route must prevent the send stage");
            discord.tryEmitExtendedSuggestedModeration(scarlet, target);
            worker.submit("barrier2", current -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(2, vrc.queries.get()); assertEquals(1, discord.sends.get());
        }
        finally { vrc.release.countDown(); BoundedSerialWork worker = (BoundedSerialWork)field.get(discord); if (worker != null) worker.close(); }
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonInteraction;
import net.sybyline.scarlet.server.discord.DPerms;

/** Actual handler, entirely fake Discord interactions, HTTP tasks and processes. */
class ScarletExitDiscordFlowTest
{
    @TempDir Path temp;
    final ScarletExitOperatorTest.Scenario scenario = new ScarletExitOperatorTest.Scenario();
    final List<String> messages = new ArrayList<>(), buttons = new ArrayList<>();
    ScarletDiscordJDA discord;
    ScarletExitOperatorDiscord controls;
    InteractionHook hook;
    Guild guild;
    Member member;
    TextChannel channel;
    JDA jda;
    String component, eventChannel = "300", author = "999";
    boolean authorized = true, cachedMember = true;
    int refreshes;
    Runnable onRefresh = () -> {};

    @SuppressWarnings("unchecked") <T> T mock(Class<T> type)
    {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            String n = m.getName();
            if (n.equals("getId")) {
                if (type == Guild.class) return "100";
                if (type == Member.class || type == User.class) return "200";
                if (type == SelfUser.class) return "999";
                if (net.dv8tion.jda.api.entities.channel.Channel.class.isAssignableFrom(type)) return eventChannel;
            }
            if (n.equals("getGuild") || n.equals("getGuildById")) return guild;
            if (n.equals("getTextChannelById")) return channel;
            if (n.equals("getChannel")) return mock(m.getReturnType());
            if (n.equals("getMember")) return member;
            if (n.equals("getMemberById")) return cachedMember ? member : null;
            if (n.equals("getSelfUser")) return mock(SelfUser.class);
            if (n.equals("getUser")) return mock(User.class);
            if (n.equals("getAuthor")) return net.sybyline.scarlet.ScarletVRChatBanEntryPointsTest.proxy(User.class,
                (method, args) -> method.equals("getId") ? author : null);
            if (n.equals("getComponentId")) return component;
            if (n.equals("getButton")) return Button.secondary(component, "Test");
            if (n.equals("isFromGuild")) return true;
            if (n.equals("hasPermission")) return authorized;
            if (n.equals("getRoles")) return Collections.emptyList();
            if (n.equals("getHook")) return hook;
            if (n.equals("submit")) { refreshes++; onRefresh.run(); return CompletableFuture.completedFuture(member); }
            if (n.equals("execute")) { ((Runnable)a[0]).run(); return null; }
            if (n.equals("queue")) {
                if (a != null && a.length > 0 && a[0] instanceof Consumer)
                    ((Consumer<Object>)a[0]).accept(type == net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction.class
                        ? hook : mock(Message.class));
                return null;
            }
            if (n.equals("reply") || n.equals("editOriginal")) messages.add(String.valueOf(a[0]));
            if (n.equals("setComponents")) {
                Object value = a[0];
                Iterable<?> rows = value instanceof java.util.Collection ? (Iterable<?>)value : java.util.Arrays.asList((Object[])value);
                for (Object row : rows) if (row instanceof ActionRow)
                    for (Button button : ((ActionRow)row).getButtons()) buttons.add(button.getCustomId());
            }
            if (m.getReturnType().isInstance(p)) return p;
            if (m.getReturnType().isInterface()) return mock(m.getReturnType());
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType() == long.class) return 0L;
            if (m.getReturnType() == int.class) return 0;
            return null;
        });
    }
    void setup() throws Exception
    {
        discord = inert(ScarletDiscordJDA.class);
        discord.guildSf = "100"; discord.opsAlertChannelSf = "300";
        guild = mock(Guild.class); member = mock(Member.class); channel = mock(TextChannel.class);
        jda = mock(JDA.class); hook = mock(InteractionHook.class);
        put(discord, "jda", jda);
        DPerms perms = new DPerms(temp.resolve("permissions.json").toFile());
        perms.getGuildSnowflakesMutable().add("100"); put(discord, "perms", perms);
        Scarlet app = inert(Scarlet.class); ScarletEventListener listener = inert(ScarletEventListener.class);
        put(listener, "clientExitMonitor", scenario.f.monitor); put(app, "eventListener", listener);
        put(app, "exec", mock(ScheduledExecutorService.class)); put(discord, "scarlet", app);
        controls = new ScarletExitOperatorDiscord(discord);
    }
    boolean click(String id)
    {
        component = id;
        return controls.handle(new ButtonInteractionEvent(jda, 0L, mock(ButtonInteraction.class)));
    }
    @Test void actualReviewAndConfirmationRequestOneNormalExit() throws Exception
    {
        setup(); String notice = scenario.notice();
        assertTrue(click(ScarletExitOperatorDiscord.REVIEW + notice));
        assertEquals(0, scenario.f.closeCalls.get()); assertEquals(1, buttons.size(), messages.toString());
        assertTrue(buttons.get(0).startsWith(ScarletExitOperatorDiscord.CONFIRM));
        click(buttons.get(0)); assertEquals(1, scenario.f.requested.get());
        assertEquals(3, refreshes);
        assertTrue(messages.stream().anyMatch(s -> s.startsWith("Normal exit requested")), messages.toString());
        click(buttons.get(0)); assertEquals(1, scenario.f.closeCalls.get());
    }
    @Test void sourceChannelAndAuthorAndPermissionFailuresRejectBeforeRefresh() throws Exception
    {
        setup(); String id = ScarletExitOperatorDiscord.REVIEW + scenario.notice();
        eventChannel = "301"; click(id); eventChannel = "300";
        author = "888"; click(id); author = "999";
        authorized = false; click(id);
        assertEquals(0, refreshes); assertEquals(0, scenario.f.closeCalls.get()); assertTrue(buttons.isEmpty());
        assertFalse(click("unrelated:test"));
    }
    @Test void permissionRevokedDuringMemberRefreshRejects() throws Exception
    {
        setup(); onRefresh = () -> authorized = false;
        click(ScarletExitOperatorDiscord.REVIEW + scenario.notice());
        assertEquals(1, refreshes, messages.toString()); assertTrue(buttons.isEmpty()); assertEquals(0, scenario.f.closeCalls.get());
    }
    @Test void channelReconfiguredDuringMemberRefreshRejectsOldInteraction() throws Exception
    {
        setup(); onRefresh = () -> discord.opsAlertChannelSf = "301";
        click(ScarletExitOperatorDiscord.REVIEW + scenario.notice());
        assertTrue(buttons.isEmpty()); assertEquals(0, scenario.f.closeCalls.get());
    }
    @Test void reportUsesExistingOpsChannelAndNoChannelMeansNoSend() throws Exception
    {
        setup(); controls.emit("synthetic-notice", "n_users=2, userCount=2, listed users=0");
        assertEquals(Collections.singletonList(ScarletExitOperatorDiscord.REVIEW + "synthetic-notice"), buttons);
        discord.opsAlertChannelSf = null; controls.emit("not-sent", "n_users=2");
        assertEquals(1, buttons.size()); assertEquals(0, scenario.f.closeCalls.get());
    }
    @Test void finalFreshPermissionCheckRejectsRevocation() throws Exception
    {
        setup(); click(ScarletExitOperatorDiscord.REVIEW + scenario.notice());
        onRefresh = () -> { if (refreshes == 3) authorized = false; };
        click(buttons.get(0)); assertEquals(3, refreshes); assertEquals(0, scenario.f.requested.get());
    }
    @Test void uncachedMemberStillRequiresFreshPermissionAtBothSteps() throws Exception
    {
        setup(); cachedMember = false; click(ScarletExitOperatorDiscord.REVIEW + scenario.notice());
        click(buttons.get(0)); assertEquals(3, refreshes); assertEquals(1, scenario.f.requested.get());
    }
}

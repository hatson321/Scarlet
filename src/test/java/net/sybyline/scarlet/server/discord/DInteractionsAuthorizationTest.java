package net.sybyline.scarlet.server.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;

class DInteractionsAuthorizationTest
{
    static Object fluent(Class<?> type)
    {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType().isInstance(p)) return p;
            return null;
        });
    }

    static <T> T event(Class<T> type, String userId, AtomicInteger denied) throws Exception
    {
        return event(type, userId, denied, null, null, null);
    }

    static <T> T event(Class<T> type, String userId, AtomicInteger denied,
        String guildId, String modalId, AtomicReference<Modal> opened) throws Exception
    {
        User user = (User) Proxy.newProxyInstance(User.class.getClassLoader(), new Class<?>[]{User.class},
            (p, m, a) -> m.getName().equals("getId") ? userId : null);
        for (Constructor<?> constructor : type.getConstructors())
        {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 3 || !parameters[2].isInterface()) continue;
            Object interaction = Proxy.newProxyInstance(parameters[2].getClassLoader(), new Class<?>[]{parameters[2]}, (p, m, a) -> {
                if (m.getName().equals("getUser")) return user;
                if (m.getName().equals("getGuild")) return guildId == null ? null : Proxy.newProxyInstance(
                    Guild.class.getClassLoader(), new Class<?>[]{Guild.class}, (gp, gm, ga) -> gm.getName().equals("getId") ? guildId : null);
                if (m.getName().equals("getModalId")) return modalId;
                if (m.getName().equals("getFullCommandName")) return "settings edit";
                if (m.getName().equals("getName")) return "settings";
                if (m.getName().equals("getSubcommandName")) return "edit";
                if (m.getName().equals("replyModal")) {
                    if (opened != null) opened.set((Modal)a[0]);
                    return fluent(m.getReturnType());
                }
                if (m.getName().equals("reply") || m.getName().equals("deferReply")) {
                    denied.incrementAndGet(); return fluent(m.getReturnType());
                }
                if (m.getName().equals("deferEdit")) return fluent(m.getReturnType());
                if (m.getReturnType() == boolean.class) return false;
                if (m.getReturnType() == long.class) return 0L;
                return null;
            });
            return type.cast(constructor.newInstance(fluent(parameters[0]), 0L, interaction));
        }
        throw new AssertionError("Unexpected JDA interaction constructor");
    }

    @Test void everyPaginationInputRejectsOtherUsersAndRevokedPermissions() throws Exception
    {
        DInteractions interactions = new DInteractions(false, null);
        AtomicBoolean permission = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger();
        DInteractions.Pagination pagination = interactions.new Pagination("session", new MessageEmbed[0],
            new net.dv8tion.jda.api.components.selections.SelectOption[0], 25,
            (event, value) -> writes.incrementAndGet()).restrictTo("owner", event -> permission.get());
        pagination.pendingSubmission = "synthetic";
        interactions.pagination.put("session", pagination);
        AtomicInteger denied = new AtomicInteger();
        interactions.handlePagination(event(ButtonInteractionEvent.class, "other", denied), "pagination-submit:session:1",
            new String[]{"pagination-submit", "session", "1"}, "pagination-submit");
        interactions.handlePagination(event(StringSelectInteractionEvent.class, "other", denied), "pagination-submission:session:1",
            new String[]{"pagination-submission", "session", "1"}, "pagination-submission");
        interactions.handlePagination(event(ModalInteractionEvent.class, "other", denied), "pagination-select:session:1",
            new String[]{"pagination-select", "session", "1"}, "pagination-select");
        assertEquals(3, denied.get());
        assertEquals(0, writes.get());
        assertEquals("synthetic", pagination.pendingSubmission);
        permission.set(false);
        interactions.handlePagination(event(ButtonInteractionEvent.class, "owner", denied), "pagination-submit:session:1",
            new String[]{"pagination-submit", "session", "1"}, "pagination-submit");
        assertEquals(4, denied.get());
        assertEquals(0, writes.get());
        permission.set(true);
        interactions.handlePagination(event(ButtonInteractionEvent.class, "owner", denied), "pagination-submit:session:1",
            new String[]{"pagination-submit", "session", "1"}, "pagination-submit");
        assertEquals(1, writes.get());
    }

    static Modal form()
    {
        return Modal.create("settings:synthetic", "Synthetic setting").addComponents(Label.of("Value",
            TextInput.create("value", TextInputStyle.SHORT).build())).build();
    }

    @Test void immediateSessionChecksOriginalOperationAndConsumesOnlyOnce() throws Exception
    {
        DInteractions interactions = new DInteractions(false, null);
        AtomicBoolean allowed = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger(), denied = new AtomicInteger();
        AtomicReference<Modal> opened = new AtomicReference<>();
        AtomicReference<String> checkedOperation = new AtomicReference<>();
        interactions.setModalAuthorization((event, operation) -> {
            checkedOperation.set(operation);
            return allowed.get();
        });
        SlashCommandInteractionEvent opener = event(SlashCommandInteractionEvent.class, "owner", denied, "guild", null, opened);
        interactions.submitModalFlow(opener, form(), event -> writes.incrementAndGet());
        String first = opened.get().getId();
        assertNotEquals("settings:synthetic", first);
        allowed.set(false);
        interactions.handle(event(ModalInteractionEvent.class, "owner", denied, "guild", first, null));
        assertEquals("settings edit", checkedOperation.get());
        assertEquals(0, writes.get());
        allowed.set(true);
        interactions.handle(event(ModalInteractionEvent.class, "owner", denied, "guild", first, null));
        assertEquals(0, writes.get());
        interactions.submitModalFlow(opener, form(), event -> writes.incrementAndGet());
        String current = opened.get().getId();
        interactions.handle(event(ModalInteractionEvent.class, "owner", denied, "guild", current, null));
        interactions.handle(event(ModalInteractionEvent.class, "owner", denied, "guild", current, null));
        assertEquals(1, writes.get());
    }

    @Test void sessionsRejectCrossUserCrossGuildExpiryReplacementAndChangedContext() throws Exception
    {
        DInteractions interactions = new DInteractions(false, null);
        interactions.setModalAuthorization((event, operation) -> true);
        AtomicInteger writes = new AtomicInteger(), denied = new AtomicInteger();
        AtomicReference<Modal> opened = new AtomicReference<>();
        SlashCommandInteractionEvent opener = event(SlashCommandInteractionEvent.class, "owner", denied, "guild", null, opened);
        interactions.submitModalFlow(opener, form(), event -> writes.incrementAndGet());
        String oldId = opened.get().getId();
        interactions.handle(event(ModalInteractionEvent.class, "other", denied, "guild", oldId, null));
        assertEquals(0, writes.get());
        assertTrue(interactions.modalSessions.containsKey("owner"));
        interactions.submitModalFlow(opener, form(), event -> writes.incrementAndGet());
        interactions.handle(event(ModalInteractionEvent.class, "owner", denied, "guild", oldId, null));
        assertEquals(0, writes.get());
        interactions.handle(event(ModalInteractionEvent.class, "owner", denied, "other-guild", opened.get().getId(), null));
        assertEquals(0, writes.get());
        DInteractions.ModalSession expired = new DInteractions.ModalSession("owner", "guild", "settings edit", 0L,
            event -> true, event -> writes.incrementAndGet());
        interactions.modalSessions.put("owner", expired);
        interactions.handle(event(ModalInteractionEvent.class, "owner", denied, "guild", expired.modalId, null));
        assertEquals(0, writes.get());
        interactions.submitModalFlow(opener, form(), event -> writes.incrementAndGet(), event -> false);
        interactions.handle(event(ModalInteractionEvent.class, "owner", denied, "guild", opened.get().getId(), null));
        assertEquals(0, writes.get());
    }

    @Test void missingOrFailingAuthorizationFailsClosed() throws Exception
    {
        DInteractions interactions = new DInteractions(false, null);
        AtomicInteger writes = new AtomicInteger(), denied = new AtomicInteger();
        AtomicReference<Modal> opened = new AtomicReference<>();
        SlashCommandInteractionEvent opener = event(SlashCommandInteractionEvent.class, "owner", denied, "guild", null, opened);
        interactions.submitModalFlow(opener, form(), event -> writes.incrementAndGet());
        interactions.handle(event(ModalInteractionEvent.class, "owner", denied, "guild", opened.get().getId(), null));
        interactions.setModalAuthorization((event, operation) -> { throw new IllegalStateException("synthetic unavailable"); });
        interactions.submitModalFlow(opener, form(), event -> writes.incrementAndGet());
        interactions.handle(event(ModalInteractionEvent.class, "owner", denied, "guild", opened.get().getId(), null));
        assertEquals(0, writes.get());
        assertEquals(2, denied.get());
    }

    @Test void localValidationWindowNeverAuthorizesSlowWork()
    {
        assertTrue(DInteractions.withinModalValidationWindow(System.nanoTime()));
        assertFalse(DInteractions.withinModalValidationWindow(System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(2)));
    }
}

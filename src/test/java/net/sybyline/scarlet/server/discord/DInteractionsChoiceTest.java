package net.sybyline.scarlet.server.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.CalendarEventAccess;
import io.github.vrchatapi.model.GroupPostVisibility;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.CommandInteractionPayload;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.sybyline.scarlet.GroupAuditType;
import net.sybyline.scarlet.GroupAuditTypeEx;
import net.sybyline.scarlet.GroupEventCategory;
import net.sybyline.scarlet.ScarletCalendar;
import net.sybyline.scarlet.ScarletDiscordCommands.DiscordAccountAgeThreshold;
import net.sybyline.scarlet.ScarletWatchedEntities;
import net.sybyline.scarlet.ScarletWatchedGroups;
import net.sybyline.scarlet.server.discord.DInteractions.SlashOption;
import net.sybyline.scarlet.server.discord.DInteractions.SlashOptionsChoicesUnsanitized;

public class DInteractionsChoiceTest
{
    static OptionMapping mapping(String name, OptionType type, Object value)
    {
        return new OptionMapping(DataObject.empty().put("name", name).put("type", type.getKey()).put("value", value),
            null, null, null);
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler)
    {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    static CommandInteractionPayload payload(OptionMapping option)
    {
        return proxy(CommandInteractionPayload.class, (p, m, a) -> {
            if (m.getName().equals("getOptions")) return option == null ? Collections.emptyList() : Arrays.asList(option);
            if (m.isDefault()) return MethodHandles.privateLookupIn(m.getDeclaringClass(), MethodHandles.lookup())
                .unreflectSpecial(m, m.getDeclaringClass()).bindTo(p).invokeWithArguments(a);
            return null;
        });
    }

    static <E extends Enum<E> & DEnum<E, ?>> void checkAllEnums(Class<E> type)
    {
        SlashOption<E> option = SlashOption.ofEnum("choice", "Choice", true, type);
        for (E expected : type.getEnumConstants())
            assertSame(expected, option.get(payload(mapping("choice", expected.type(), expected.value()))), expected.name());
        Object invalid = option.data().getType() == OptionType.INTEGER ? Long.valueOf(-12345) : "not-a-choice";
        assertThrows(DInteractions.InvalidSlashOptionException.class,
            () -> option.get(payload(mapping("choice", option.data().getType(), invalid))), type.getName());
    }

    @Test void everyNormalAndExtendedAuditEventResolvesToItsOwnEnum()
    {
        checkAllEnums(GroupAuditType.class);
        checkAllEnums(GroupAuditTypeEx.class);
    }

    @Test void everyOtherFiniteConfigEnumRetainsValidValuesAndRejectsUnknowns()
    {
        checkAllEnums(DiscordAccountAgeThreshold.class);
        checkAllEnums(ScarletWatchedEntities.WatchedEntity.Type.class);
        checkAllEnums(ScarletWatchedGroups.WatchedGroup.Type.class);
        checkAllEnums(ScarletCalendar.Frequency.class);
        checkAllEnums(GroupEventCategory.class);
    }

    @Test void rawAuditSearchTermsNeverBecomeTheFirstEvent()
    {
        SlashOption<GroupAuditType> option = SlashOption.ofEnum("audit-event-type", "Audit type", true, GroupAuditType.class);
        for (String raw : new String[]{"warn", "kick", "ban", "Instance Warn", "", "0", "group.instance.invalid"})
        {
            DInteractions.InvalidSlashOptionException failure = assertThrows(DInteractions.InvalidSlashOptionException.class,
                () -> option.get(payload(mapping("audit-event-type", OptionType.STRING, raw))));
            assertEquals("Select a valid option from the suggested choices. The command was not applied.", failure.getMessage());
        }
        assertSame(GroupAuditType.INSTANCE_WARN, option.get(payload(mapping("audit-event-type", OptionType.STRING, "group.instance.warn"))));
    }

    static <E extends Enum<E> & DEnum<E, ?>> void checkAutocomplete(Class<E> type)
    {
        SlashOption<E> option = SlashOption.ofEnum("event", "Event", true, type);
        if (type.getEnumConstants().length <= 25)
        {
            assertFalse(option.data().isAutoComplete());
            assertNull(option.autocomplete);
            assertEquals(Arrays.asList(type.getEnumConstants()[0].choices()), option.data().getChoices());
            return;
        }
        assertTrue(option.data().isAutoComplete());
        SlashOptionsChoicesUnsanitized autocomplete = (SlashOptionsChoicesUnsanitized) option.autocomplete;
        assertFalse(autocomplete.includeTyping, "Finite choices cannot offer arbitrary typed values");
        for (E expected : type.getEnumConstants())
        {
            for (String input : new String[]{expected.display(), expected.value().toString(), expected.display().toUpperCase(java.util.Locale.ROOT)})
            {
                Command.Choice[] choices = SlashOptionsChoicesUnsanitized.choices(input, autocomplete.values.get(), autocomplete.includeTyping);
                assertTrue(choices.length <= 25);
                assertEquals(expected.value().toString(), choices[0].getAsString(), input);
                for (Command.Choice choice : choices)
                    assertTrue(Arrays.stream(type.getEnumConstants()).anyMatch(v -> v.value().toString().equals(choice.getAsString())));
            }
        }
        assertEquals(25, SlashOptionsChoicesUnsanitized.choices("", autocomplete.values.get(), false).length);
    }

    @Test void allAuditChoicesAreDiscoverableWithoutInventingRawChoices()
    {
        checkAutocomplete(GroupAuditType.class);
        checkAutocomplete(GroupAuditTypeEx.class);
        Command.Choice[] warn = SlashOptionsChoicesUnsanitized.choices("warn", GroupAuditType.INSTANCE_CLOSE.choices(), false);
        assertEquals(GroupAuditType.INSTANCE_WARN.id, warn[0].getAsString());
    }

    @Test void explicitlyFreeTextAutocompleteRemainsAvailable()
    {
        Command.Choice[] choices = SlashOptionsChoicesUnsanitized.choices("new value",
            new Command.Choice[]{new Command.Choice("Existing", "existing")}, true);
        assertEquals("new value", choices[0].getAsString());
        SlashOption<String> free = SlashOption.ofString("value", "Value", false, null);
        assertEquals("new value", free.get(payload(mapping("value", OptionType.STRING, "new value"))));
        assertEquals(47, SlashOption.ofInt("number", "Number", false, 0)
            .get(payload(mapping("number", OptionType.INTEGER, 47))));
    }

    @Test void missingOptionalEnumStillUsesItsExplicitFallbackButUnknownDoesNot()
    {
        SlashOption<GroupAuditType> option = SlashOption.ofEnum("event", "Event", false, GroupAuditType.INSTANCE_WARN);
        assertSame(GroupAuditType.INSTANCE_WARN, option.get(payload(null)));
        assertThrows(DInteractions.InvalidSlashOptionException.class,
            () -> option.get(payload(mapping("event", OptionType.STRING, "wrong"))));
    }

    @Test void missingRequiredOptionsNeverUseAnEnumOrScalarDefault()
    {
        assertThrows(DInteractions.InvalidSlashOptionException.class,
            () -> SlashOption.ofEnum("event", "Event", true, GroupAuditType.INSTANCE_CLOSE).get(payload(null)));
        assertThrows(DInteractions.InvalidSlashOptionException.class,
            () -> SlashOption.ofInt("number", "Number", true, 42).get(payload(null)));
        assertThrows(DInteractions.InvalidSlashOptionException.class,
            () -> SlashOption.ofString("text", "Text", true, "fallback").get(payload(null)));
        assertEquals(42, SlashOption.ofInt("number", "Number", false, 42).get(payload(null)));
    }

    @Test void wrappedApiEnumsRejectUnknownAndPreserveEveryAllowedChoice()
    {
        checkApiEnums(DOptionEnum.of("visibility", "Visibility", GroupPostVisibility.class, GroupPostVisibility::getValue,
            value -> value.getValue()));
        checkApiEnums(DOptionEnum.of("access", "Access", CalendarEventAccess.class, CalendarEventAccess::getValue,
            value -> value.getValue()));
    }

    static <E extends Enum<E>> void checkApiEnums(DOptionEnum<E> values)
    {
        SlashOption<E> option = SlashOption.ofDOptionEnum(values, true);
        for (Command.Choice choice : values.choices)
            assertSame(values.byId(choice.getAsString()), option.get(payload(mapping(values.name, OptionType.STRING, choice.getAsString()))));
        assertThrows(DInteractions.InvalidSlashOptionException.class,
            () -> option.get(payload(mapping(values.name, OptionType.STRING, "unrecognized"))));
    }

    public static class AuditSetterFixture
    {
        public final SlashOption<GroupAuditType> event = SlashOption.ofEnum("event", "Event", true, GroupAuditType.class);
        final Map<GroupAuditType, String> routing = new HashMap<>();
        @DInteractions.SlashCmd("set-test-audit")
        public void set(SlashCommandInteractionEvent ignored, @DInteractions.SlashOpt("event") GroupAuditType selected)
        {
            this.routing.put(selected, "synthetic-channel");
        }
    }

    @Test void actualCommandArgumentBindingRejectsInvalidBeforeAnySetterMutation() throws Exception
    {
        DInteractions interactions = new DInteractions(false, null);
        AuditSetterFixture fixture = new AuditSetterFixture();
        interactions.register(fixture);
        AtomicReference<String> reply = new AtomicReference<>();
        AtomicBoolean privateReply = new AtomicBoolean();
        interactions.handle(event("warn", reply, privateReply));
        assertTrue(fixture.routing.isEmpty());
        assertTrue(privateReply.get());
        assertEquals("Select a valid option from the suggested choices. The command was not applied.", reply.get());
        interactions.handle(event("group.instance.warn", reply, privateReply));
        assertEquals(Collections.singletonMap(GroupAuditType.INSTANCE_WARN, "synthetic-channel"), fixture.routing);
    }

    static Object replyAction(Class<?> type, AtomicReference<String> reply, AtomicBoolean privateReply)
    {
        return proxy(type, (p, m, a) -> {
            if (m.getName().equals("setEphemeral")) privateReply.set((Boolean) a[0]);
            if (m.getName().equals("setContent")) reply.set(String.valueOf(a[0]));
            return m.getReturnType().isInstance(p) ? p : null;
        });
    }

    static SlashCommandInteractionEvent event(String value, AtomicReference<String> reply, AtomicBoolean privateReply)
    {
        CommandInteractionPayload payload = payload(mapping("event", OptionType.STRING, value));
        SlashCommandInteraction interaction = proxy(SlashCommandInteraction.class, (p, m, a) -> {
            if (m.getName().equals("getFullCommandName") || m.getName().equals("getName")) return "set-test-audit";
            if (m.getName().equals("getOptions")) return payload.getOptions();
            if (m.getName().equals("isAcknowledged")) return false;
            if (m.getName().equals("reply")) { reply.set(String.valueOf(a[0])); return replyAction(m.getReturnType(), reply, privateReply); }
            if (m.getName().equals("deferReply")) return replyAction(m.getReturnType(), reply, privateReply);
            if (m.isDefault()) return MethodHandles.privateLookupIn(m.getDeclaringClass(), MethodHandles.lookup())
                .unreflectSpecial(m, m.getDeclaringClass()).bindTo(p).invokeWithArguments(a);
            if (m.getReturnType() == long.class) return 0L;
            if (m.getReturnType() == boolean.class) return false;
            return null;
        });
        return new SlashCommandInteractionEvent(proxy(JDA.class, (p, m, a) -> null), 0L, interaction);
    }

    @Test void deferredInvalidOptionReplyIsPrivateAndContainsNoRawInput() throws Exception
    {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<String> reply = new AtomicReference<>();
        AtomicBoolean privateReply = new AtomicBoolean();
        CountDownLatch sent = new CountDownLatch(1);
        InteractionHook hook = proxy(InteractionHook.class, (p, m, a) -> {
            if (m.getName().equals("sendMessage"))
            {
                reply.set(String.valueOf(a[0]));
                return proxy(m.getReturnType(), (action, operation, values) -> {
                    if (operation.getName().equals("setEphemeral")) privateReply.set((Boolean) values[0]);
                    if (operation.getName().equals("queue")) sent.countDown();
                    return operation.getReturnType().isInstance(action) ? action : null;
                });
            }
            return null;
        });
        IReplyCallback event = proxy(IReplyCallback.class, (p, m, a) -> {
            if (m.getName().equals("deferReply")) return proxy(m.getReturnType(), (action, operation, values) -> {
                if (operation.getName().equals("queue")) ((Consumer<InteractionHook>) values[0]).accept(hook);
                return operation.getReturnType().isInstance(action) ? action : null;
            });
            return null;
        });
        try
        {
            new DInteractions(false, executor).handleAsync(event, false, ignored -> {
                throw new DInteractions.InvalidSlashOptionException();
            });
            assertTrue(sent.await(5, TimeUnit.SECONDS));
            assertTrue(privateReply.get());
            assertEquals("Select a valid option from the suggested choices. The command was not applied.", reply.get());
        }
        finally { executor.shutdownNow(); }
    }
}

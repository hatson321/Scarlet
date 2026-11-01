package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IModalCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.sybyline.scarlet.server.discord.DInteractions;
import net.sybyline.scarlet.server.discord.DPerms;
import net.sybyline.scarlet.util.UniqueStrings;

/** Offline handlers with synthetic Discord objects: no bot, network, or user Preferences. */
class ScarletConfigCommandMappingsTest
{
    @TempDir Path temp;

    @Test void everyNormalAndExtendedAuditTypeWritesOnlyItsRequestedMap() throws Exception
    {
        Fixture f = new Fixture(temp);
        TextChannel channel = channel("configured", "123", true);
        for (GroupAuditType type : GroupAuditType.values())
        {
            f.commands.setAuditChannel(null, f.hook, type, channel);
            assertEquals("123", f.discord.auditType2channelSf.get(type.id));
            assertTrue(f.discord.auditType2secretChannelSf.isEmpty());
            f.commands.setAuditChannel(null, f.hook, type, null);
            assertFalse(f.discord.auditType2channelSf.containsKey(type.id));
            f.commands.setAuditSecretChannel(null, f.hook, type, channel);
            assertEquals("123", f.discord.auditType2secretChannelSf.get(type.id));
            assertTrue(f.discord.auditType2channelSf.isEmpty());
            f.commands.setAuditSecretChannel(null, f.hook, type, null);
            assertTrue(f.discord.auditType2secretChannelSf.isEmpty());
        }
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
        {
            f.commands.setAuditExChannel(null, f.hook, type, channel);
            assertEquals("123", f.discord.auditExType2channelSf.get(type.id));
            assertTrue(f.discord.auditExType2secretChannelSf.isEmpty());
            f.commands.setAuditExChannel(null, f.hook, type, null);
            f.commands.setAuditSecretExChannel(null, f.hook, type, channel);
            assertEquals(type.supportsSecretRouting() ? "123" : null, f.discord.auditExType2secretChannelSf.get(type.id));
            assertTrue(f.discord.auditExType2channelSf.isEmpty());
            f.commands.setAuditSecretExChannel(null, f.hook, type, null);
        }
        assertEquals(4 * GroupAuditType.values().length + 3 * GroupAuditTypeEx.values().length + 3, f.discord.saves);
    }

    @Test void unsupportedForeignAndInaccessibleChannelsCannotReplaceAnyLogRoute() throws Exception
    {
        Fixture f = new Fixture(temp);
        f.discord.auditType2channelSf.put(GroupAuditType.INSTANCE_WARN.id, "old");
        f.discord.auditType2secretChannelSf.put(GroupAuditType.INSTANCE_WARN.id, "old-secret");
        f.discord.auditExType2channelSf.put(GroupAuditTypeEx.SPAWN_PROP.id, "old-ex");
        f.discord.auditExType2secretChannelSf.put(GroupAuditTypeEx.SPAWN_PROP.id, "old-secret-ex");
        f.discord.trainingChannelSf = f.discord.opsAlertChannelSf = f.discord.discordActionLogChannelSf = "old";
        Channel wrongType = proxy(Channel.class, (m, a) -> m.equals("getType") ? ChannelType.VOICE : null, f.messages);
        for (Channel rejected : Arrays.asList(wrongType, channel("other", "456", true), channel("configured", "456", false)))
        {
            f.commands.setAuditChannel(null, f.hook, GroupAuditType.INSTANCE_WARN, rejected);
            f.commands.setAuditSecretChannel(null, f.hook, GroupAuditType.INSTANCE_WARN, rejected);
            f.commands.setAuditExChannel(null, f.hook, GroupAuditTypeEx.SPAWN_PROP, rejected);
            f.commands.setAuditSecretExChannel(null, f.hook, GroupAuditTypeEx.SPAWN_PROP, rejected);
            f.commands.setTrainingChannel(null, f.hook, rejected);
            f.commands.setOpsAlertChannel(null, f.hook, rejected);
            f.commands.setDiscordActionLogChannel(null, f.hook, rejected);
        }
        assertEquals(0, f.discord.saves);
        assertEquals("old", f.discord.auditType2channelSf.get(GroupAuditType.INSTANCE_WARN.id));
        assertEquals("old-secret", f.discord.auditType2secretChannelSf.get(GroupAuditType.INSTANCE_WARN.id));
        assertEquals("old-ex", f.discord.auditExType2channelSf.get(GroupAuditTypeEx.SPAWN_PROP.id));
        assertEquals("old-secret-ex", f.discord.auditExType2secretChannelSf.get(GroupAuditTypeEx.SPAWN_PROP.id));
        assertEquals("old", f.discord.trainingChannelSf);
        assertEquals("old", f.discord.opsAlertChannelSf);
        assertEquals("old", f.discord.discordActionLogChannelSf);
        assertTrue(f.messages.stream().allMatch(message -> message.contains("Nothing changed")));
    }

    @Test void selectedTextChannelRequiresAllThreeEffectiveBotPermissions()
    {
        for (Permission absent : new Permission[]{Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS})
        {
            SelfMember bot = proxy(SelfMember.class, (m, a) -> {
                if (m.equals("hasPermission"))
                    return !Arrays.asList((Permission[])a[1]).contains(absent);
                return null;
            }, new ArrayList<>());
            Guild guild = proxy(Guild.class, (m, a) -> m.equals("getId") ? "configured" : m.equals("getSelfMember") ? bot : null, new ArrayList<>());
            TextChannel channel = proxy(TextChannel.class, (m, a) -> m.equals("getType") ? ChannelType.TEXT : m.equals("getGuild") ? guild : null, new ArrayList<>());
            assertNotNull(ScarletDiscordCommands.textChannelConfigurationProblem(channel, "configured"));
        }
        assertNull(ScarletDiscordCommands.textChannelConfigurationProblem(null, "configured"));
    }

    @Test void integerCommandBoundsMatchActualSettingAndRepliesShowStoredValues() throws Exception
    {
        Fixture f = new Fixture(temp);
        ScarletDiscordCommands.ConfigSet config = f.commands.new ConfigSet();
        ScarletDiscordCommands.ConfigSet.SuggestedMod suggested = config.new SuggestedMod();
        ScarletDiscordCommands.ConfigSet.OutstandingMod outstanding = config.new OutstandingMod();
        assertEquals(10, suggested._kickCount.data().getMaxValue().intValue());
        assertEquals(30, suggested._periodDays.data().getMaxValue().intValue());
        assertEquals(30, outstanding._periodDays.data().getMaxValue().intValue());
        suggested.kickCount(f.slash(), 999);
        suggested.periodDays(f.slash(), 999);
        outstanding.periodDays(f.slash(), -5);
        assertEquals(10, f.settings.heuristicKickCount.get());
        assertEquals(30, f.settings.heuristicPeriodDays.get());
        assertEquals(1, f.settings.outstandingPeriodDays.get());
        assertTrue(f.messages.get(0).endsWith("10"));
        assertTrue(f.messages.get(1).endsWith("30"));
        assertTrue(f.messages.get(2).endsWith("1"));
    }

    @Test void invalidSummaryTimeLeavesBothSchedulesUntouched() throws Exception
    {
        Fixture f = new Fixture(temp);
        OffsetDateTime old = OffsetDateTime.parse("2026-01-02T03:04:00Z");
        f.settings.nextModSummary.cached = old;
        f.settings.nextOutstandingMod.cached = old;
        ScarletDiscordCommands.ConfigSet config = f.commands.new ConfigSet();
        config.new ModSummary().modSummaryTimeOfDay(f.slash(), ZoneId.of("UTC"), "invalid");
        config.new OutstandingMod().modSummaryTimeOfDay(f.slash(), ZoneId.of("UTC"), "99:99");
        assertEquals(old, f.settings.nextModSummary.cached);
        assertEquals(old, f.settings.nextOutstandingMod.cached);
        assertEquals(2, f.messages.size());
        assertTrue(f.messages.stream().allMatch(message -> message.contains("Nothing changed")));
    }

    @Test void summaryReplyMatchesSavedNextRunWithoutAnExtraDay() throws Exception
    {
        Fixture f = new Fixture(temp);
        ScarletSettings.RegistryOffsetDateTime summary = f.settings.new RegistryOffsetDateTime("test-summary") {
            @Override protected void write(String value) {}
        };
        ScarletSettings.RegistryOffsetDateTime outstanding = f.settings.new RegistryOffsetDateTime("test-outstanding") {
            @Override protected void write(String value) {}
        };
        ScarletDiscordSafetyTest.put(f.settings, "nextModSummary", summary);
        ScarletDiscordSafetyTest.put(f.settings, "nextOutstandingMod", outstanding);
        ScarletDiscordCommands.ConfigSet config = f.commands.new ConfigSet();
        config.new ModSummary().modSummaryTimeOfDay(f.slash(), ZoneId.of("UTC"), "14:15");
        config.new OutstandingMod().modSummaryTimeOfDay(f.slash(), ZoneId.of("UTC"), "15:16");
        assertTrue(f.messages.get(0).contains("<t:"+summary.cached.toEpochSecond()+":f>"));
        assertTrue(f.messages.get(1).contains("<t:"+outstanding.cached.toEpochSecond()+":f>"));
    }

    @Test void nextSummaryTimeUsesChosenLocalTimeAcrossDaylightSavingBoundary()
    {
        ZoneId zone = ZoneId.of("America/Chicago");
        ZonedDateTime now = ZonedDateTime.of(2026, 3, 7, 23, 0, 0, 0, zone);
        OffsetDateTime next = ScarletDiscordCommands.nextSummaryTime(zone, LocalTime.of(9, 30), now);
        assertEquals("2026-03-08T14:30Z", next.toString());
    }

    @Test void integerSettingsModalReadsTextInputAndConfirmsNormalizedValue() throws Exception
    {
        Fixture f = new Fixture(temp);
        f.commands.new Settings().edit(f.slash(), f.settings.heuristicKickCount);
        ModalMapping input = ScarletVRChatBanEntryPointsTest.record(ModalMapping.class, DataObject.empty()
            .put("id", 1).put("custom_id", "heuristicKickCount").put("type", 4).put("value", "99"));
        f.interactions.immediate.handle(event(ModalInteractionEvent.class, (m, a) -> m.equals("getValues") ? Collections.singletonList(input) : null, f.messages));
        assertEquals(10, f.settings.heuristicKickCount.get());
        assertTrue(f.messages.get(f.messages.size()-1).endsWith("10"));
    }

    @Test void stringChoiceMenusUseAtMost25OptionsAndOnlyValidDefaults() throws Exception
    {
        Fixture f = new Fixture(temp);
        for (int size : new int[]{26, 50, 100, 125})
        {
            List<String> choices = IntStream.range(0, size).mapToObj(i -> "voice-"+i).collect(java.util.stream.Collectors.toList());
            ScarletSettings.FileValuedStringChoice setting = f.settings.new FileValuedStringChoice("voices-"+size, "Voices", "voice-"+(size-1), () -> choices);
            f.commands.new Settings().edit(f.slash(), setting);
            assertNotNull(f.interactions.modal);
        }
        SelectOption[] page = {SelectOption.of("One", "one"), SelectOption.of("Two", "two")};
        assertFalse(ScarletDiscordCommands.settingChoiceMenu("test", page, "other-page", false).getOptions().stream().anyMatch(SelectOption::isDefault));
        assertTrue(ScarletDiscordCommands.settingChoiceMenu("test", page, "two", false).getOptions().get(1).isDefault());
    }

    @Test void settingModalContextRejectsReplacedPropertyAndGroupSwitch() throws Exception
    {
        Fixture f = new Fixture(temp);
        f.commands.new Settings().edit(f.slash(), f.settings.heuristicKickCount);
        assertTrue(f.interactions.context.test(null));
        f.discord.scarlet.vrc.groupId = "changed-group";
        assertFalse(f.interactions.context.test(null));
        f.discord.scarlet.vrc.groupId = ScarletVRChatBanEntryPointsTest.GROUP;
        f.settings.fileValuedSettings.remove(f.settings.heuristicKickCount.id());
        assertFalse(f.interactions.context.test(null));
    }

    @Test void emptyAndStaleSettingChoicesDoNotPretendToApply() throws Exception
    {
        Fixture f = new Fixture(temp);
        ScarletSettings.FileValuedStringChoice empty = f.settings.new FileValuedStringChoice("empty", "No voices", "", Collections::emptyList);
        f.commands.new Settings().edit(f.slash(), empty);
        assertTrue(f.messages.get(0).contains("No values are currently available"));
        assertNull(f.interactions.modal);
        ScarletSettings.FileValuedStringChoice live = f.settings.new FileValuedStringChoice("live", "Voices", "one", () -> Collections.singletonList("one"));
        assertFalse(live.set("removed", "test"));
        assertTrue(ScarletDiscordCommands.settingResult(live, false).contains("Nothing changed"));
        assertEquals("one", live.get());
    }

    @Test void auxiliaryWebhookAddRemoveSavesAndClearsDeletedReferences() throws Exception
    {
        Fixture f = new Fixture(temp);
        ScarletDiscordCommands.AuxWebhooks commands = f.commands.new AuxWebhooks();
        commands.add(null, f.hook, "example", "https://discord.com/api/webhooks/123456789012345678/"+String.join("", Collections.nCopies(68, "x")));
        assertEquals(1, f.discord.saves);
        assertTrue(f.discord.scarletAuxWh2webhookUrl.containsKey("example"));
        f.discord.auditType2scarletAuxWh.put(GroupAuditType.INSTANCE_WARN.id, new UniqueStrings(Collections.singletonList("example")));
        commands.remove(null, f.hook, "example");
        assertEquals(2, f.discord.saves);
        assertTrue(f.discord.scarletAuxWh2webhookUrl.isEmpty());
        assertTrue(f.discord.auditType2scarletAuxWh.get(GroupAuditType.INSTANCE_WARN.id).isEmpty());
    }

    @Test void auxiliaryRouteSelectionPersistsClearingAndRejectsStaleNames() throws Exception
    {
        Fixture f = new Fixture(temp);
        ScarletDiscordUI ui = ScarletDiscordSafetyTest.inert(ScarletDiscordUI.class);
        ScarletDiscordSafetyTest.put(ui, "discord", f.discord);
        Member admin = proxy(Member.class, (m, a) -> m.equals("hasPermission") ? true : null, f.messages);
        StringSelectMenu menu = StringSelectMenu.create("set-audit-aux-webhooks:"+GroupAuditType.INSTANCE_WARN.id).addOption("one", "one").build();
        f.discord.scarletAuxWh2webhookUrl.put("one", "synthetic-url");
        for (List<String> values : Arrays.asList(Collections.singletonList("one"), Collections.singletonList("missing"), Collections.<String>emptyList()))
            ui.setAuditAuxWebhooks(event(StringSelectInteractionEvent.class, (m, a) ->
                m.equals("getMember") ? admin : m.equals("getComponent") ? menu : m.equals("getValues") ? values : null, f.messages));
        assertEquals(2, f.discord.saves);
        assertFalse(f.discord.auditType2scarletAuxWh.containsKey(GroupAuditType.INSTANCE_WARN.id));
        assertTrue(f.messages.stream().anyMatch(message -> message.contains("no longer exists")));
    }

    @Test void auxiliaryMenuEmptyAndOversizedConfigurationsAreExplicitNotTruncated() throws Exception
    {
        Fixture f = new Fixture(temp);
        f.commands.setAuditAuxWebhooks(f.slash(), GroupAuditType.INSTANCE_WARN);
        assertTrue(f.messages.get(0).contains("No auxiliary webhooks"));
        for (int i = 0; i < 26; i++) f.discord.scarletAuxWh2webhookUrl.put("name-"+i, "synthetic");
        f.commands.setAuditAuxWebhooks(f.slash(), GroupAuditType.INSTANCE_WARN);
        assertTrue(f.messages.get(1).contains("at most 25"));
        assertEquals(0, f.discord.saves);
    }

    @Test void scheduleFeedbackNamesTheChangedFieldAndUsesDeferredHook() throws Exception
    {
        Fixture f = new Fixture(temp);
        FakeCalendar calendar = ScarletDiscordSafetyTest.inert(FakeCalendar.class);
        ScarletDiscordSafetyTest.put(f.discord.scarlet, "calendar", calendar);
        ScarletDiscordSafetyTest.put(f.commands, "languages", Collections.singletonMap("eng", "English"));
        ScarletDiscordCommands.Schedule schedule = ScarletDiscordSafetyTest.inert(ScarletDiscordCommands.Schedule.class);
        ScarletDiscordSafetyTest.put(schedule, "this$0", f.commands);
        ScarletCalendar.EventSpec spec = new ScarletCalendar.EventSpec();
        spec.vrcCalendarEventParameters.setTitle("Preserved title");
        schedule.setDescription(null, f.hook, spec, "New description");
        assertEquals("Preserved title", spec.vrcCalendarEventParameters.getTitle());
        assertEquals("New description", spec.vrcCalendarEventParameters.getDescription());
        assertEquals("Set event schedule description", f.messages.get(0));
        schedule.setTags(null, f.hook, spec, null, null, null, null, null);
        schedule.setLanguages(null, f.hook, spec, null, null, null);
        schedule.setLanguages(null, f.hook, spec, "invalid", null, null);
        assertTrue(f.messages.contains("Cleared event schedule tags"));
        assertTrue(f.messages.contains("Cleared event schedule languages"));
        assertTrue(f.messages.stream().anyMatch(message -> message.startsWith("Invalid or unknown languages")));
        assertEquals(3, calendar.saves);
    }

    @Test void permissionCommandMutationIsDurableWithoutWaitingForAnotherSave() throws Exception
    {
        List<String> messages = new ArrayList<>();
        Path file = temp.resolve("permissions.json");
        DPerms perms = new DPerms(file.toFile());
        Member member = proxy(Member.class, (m, a) -> m.equals("getId") ? "123" : m.equals("getRoles") ? Collections.emptyList() : m.equals("getAsMention") ? "<@123>" : null, messages);
        gnu.trove.map.hash.TLongObjectHashMap<Object> resolved = new gnu.trove.map.hash.TLongObjectHashMap<>();
        resolved.put(123L, member);
        OptionMapping target = new OptionMapping(DataObject.empty().put("name", "target").put("type", 9).put("value", "123"), resolved, null, null);
        Map<String, OptionMapping> options = new HashMap<>();
        options.put("target", target);
        for (String[] pair : new String[][]{{"type", "slash-command"}, {"name", "set-audit-channel"}, {"value", "true"}})
            options.put(pair[0], ScarletVRChatBanEntryPointsTest.record(OptionMapping.class, DataObject.empty().put("name", pair[0]).put("type", 3).put("value", pair[1])));
        SlashCommandInteractionEvent event = event(SlashCommandInteractionEvent.class, (m, a) -> {
            if (m.equals("getSubcommandName")) return "set";
            if (m.equals("getOptions")) return new ArrayList<>(options.values());
            if (m.equals("getOption")) {
                OptionMapping option = options.get((String)a[0]);
                if (a.length == 2) return ((java.util.function.Function<OptionMapping, ?>)a[1]).apply(option);
                return option;
            }
            if (m.equals("isAcknowledged")) return true;
            return null;
        }, messages);
        perms.internal_handle(event);
        DPerms reopened = new DPerms(file.toFile());
        reopened.load();
        assertEquals(Boolean.TRUE, reopened.get(DPerms.PermType.SLASH_COMMAND, member, "set-audit-channel"));
    }

    @Test void failedScheduleSaveWarnsWithoutPretendingToRollbackOrSucceed() throws Exception
    {
        Fixture f = new Fixture(temp);
        FakeCalendar calendar = ScarletDiscordSafetyTest.inert(FakeCalendar.class);
        calendar.fail = true;
        ScarletDiscordSafetyTest.put(f.discord.scarlet, "calendar", calendar);
        ScarletDiscordCommands.Schedule schedule = ScarletDiscordSafetyTest.inert(ScarletDiscordCommands.Schedule.class);
        ScarletDiscordSafetyTest.put(schedule, "this$0", f.commands);
        ScarletCalendar.EventSpec spec = new ScarletCalendar.EventSpec();
        spec.id = "synthetic-schedule";
        ScarletDiscordSafetyTest.put(calendar, "eventSpecs", new HashMap<String, ScarletCalendar.EventSpec>());
        calendar.eventSpecs.put(spec.id, spec);
        schedule.setDescription(null, f.hook, spec, "Changed in memory");
        assertEquals("Changed in memory", spec.vrcCalendarEventParameters.getDescription());
        assertEquals(1, calendar.saves);
        assertEquals(1, f.messages.size());
        assertTrue(f.messages.get(0).contains("could not be saved to disk"));
        assertFalse(f.messages.get(0).contains("Nothing changed"));
        assertFalse(f.messages.get(0).startsWith("Set event schedule"));
        f.messages.clear();
        ScarletDiscordCommands.Schedule.SetPlatformsModal modal = schedule.new SetPlatformsModal(spec);
        modal.eventPlatforms = new GroupEventPlatform[0];
        modal.handle(event(ModalInteractionEvent.class, (m, a) -> null, f.messages));
        assertTrue(f.messages.get(0).contains("could not be saved to disk"));
    }

    @Test void typedScheduleFormsRejectDeletedReplacedAndOtherGroupContexts() throws Exception
    {
        Fixture f = new Fixture(temp);
        FakeCalendar calendar = ScarletDiscordSafetyTest.inert(FakeCalendar.class);
        ScarletDiscordSafetyTest.put(calendar, "eventSpecs", new HashMap<String, ScarletCalendar.EventSpec>());
        ScarletDiscordSafetyTest.put(f.discord.scarlet, "calendar", calendar);
        ScarletDiscordCommands.Schedule schedule = ScarletDiscordSafetyTest.inert(ScarletDiscordCommands.Schedule.class);
        ScarletDiscordSafetyTest.put(schedule, "this$0", f.commands);
        ScarletCalendar.EventSpec spec = new ScarletCalendar.EventSpec();
        spec.id = "synthetic-schedule";
        calendar.eventSpecs.put(spec.id, spec);
        ScarletDiscordCommands.Schedule.SetPlatformsModal modal = schedule.new SetPlatformsModal(spec);
        modal.eventPlatforms = new GroupEventPlatform[0];
        calendar.eventSpecs.remove(spec.id);
        modal.handle(event(ModalInteractionEvent.class, (m, a) -> null, f.messages));
        calendar.eventSpecs.put(spec.id, new ScarletCalendar.EventSpec());
        modal.handle(event(ModalInteractionEvent.class, (m, a) -> null, f.messages));
        calendar.eventSpecs.put(spec.id, spec);
        f.discord.scarlet.vrc.groupId = "changed-group";
        modal.handle(event(ModalInteractionEvent.class, (m, a) -> null, f.messages));
        assertEquals(0, calendar.saves);
        assertEquals(3, f.messages.size());
        assertTrue(f.messages.stream().allMatch(message -> message.contains("No changes were applied")));
    }

    static final class Fixture
    {
        final List<String> messages = new ArrayList<>();
        final ScarletSettings settings;
        final FakeDiscord discord;
        final ScarletDiscordCommands commands;
        final CaptureInteractions interactions = new CaptureInteractions();
        final InteractionHook hook;
        Fixture(Path temp) throws Exception
        {
            Scarlet scarlet = ScarletDiscordSafetyTest.inert(Scarlet.class);
            ScarletVRChat vrc = ScarletDiscordSafetyTest.inert(ScarletVRChat.class);
            vrc.groupId = ScarletVRChatBanEntryPointsTest.GROUP;
            ScarletDiscordSafetyTest.put(scarlet, "vrc", vrc);
            settings = new ScarletSettings(null, temp.resolve("settings.json").toFile(), false);
            ScarletDiscordSafetyTest.put(scarlet, "settings", settings);
            discord = ScarletDiscordSafetyTest.inert(FakeDiscord.class);
            ScarletDiscordSafetyTest.put(discord, "scarlet", scarlet);
            ScarletDiscordSafetyTest.put(discord, "interactions", interactions);
            discord.guildSf = "configured";
            discord.auditType2channelSf = new HashMap<>();
            discord.auditType2secretChannelSf = new HashMap<>();
            discord.auditExType2channelSf = new HashMap<>();
            discord.auditExType2secretChannelSf = new HashMap<>();
            discord.scarletAuxWh2webhookUrl = new HashMap<>();
            discord.auditType2scarletAuxWh = new HashMap<>();
            commands = ScarletDiscordSafetyTest.inert(ScarletDiscordCommands.class);
            ScarletDiscordSafetyTest.put(commands, "discord", discord);
            hook = proxy(InteractionHook.class, (m, a) -> null, messages);
        }
        SlashCommandInteractionEvent slash() throws Exception { return event(SlashCommandInteractionEvent.class, (m, a) -> null, messages); }
    }

    static class FakeDiscord extends ScarletDiscordJDA
    {
        FakeDiscord() { super(null, null, null); }
        int saves;
        @Override public void save() { saves++; }
    }

    static class FakeCalendar extends ScarletCalendar
    {
        FakeCalendar() { super(null, null); }
        int saves;
        boolean fail;
        @Override public boolean save() { saves++; return !fail; }
    }

    static class CaptureInteractions extends DInteractions
    {
        CaptureInteractions() { super(false, null); }
        Modal modal;
        ModalFlowImmediate immediate;
        java.util.function.Predicate<ModalInteractionEvent> context;
        @Override public ModalCallbackAction submitModalFlow(IModalCallback event, Modal modal, ModalFlowImmediate immediate,
            java.util.function.Predicate<ModalInteractionEvent> context)
        {
            this.modal = modal;
            this.immediate = immediate;
            this.context = context;
            return proxy(ModalCallbackAction.class, (m, a) -> null, new ArrayList<>());
        }
    }

    static TextChannel channel(String guildId, String id, boolean access)
    {
        List<String> ignored = new ArrayList<>();
        SelfMember bot = proxy(SelfMember.class, (m, a) -> m.equals("hasPermission") ? access : null, ignored);
        Guild guild = proxy(Guild.class, (m, a) -> m.equals("getId") ? guildId : m.equals("getSelfMember") ? bot : null, ignored);
        return proxy(TextChannel.class, (m, a) -> m.equals("getType") ? ChannelType.TEXT : m.equals("getGuild") ? guild : m.equals("getId") ? id : m.equals("getName") ? "synthetic" : null, ignored);
    }

    static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> answer, List<String> messages)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
            Object value = answer.apply(method.getName(), args);
            if (value != null) return value;
            if (method.getName().matches("(sendMessage|reply)(Format)?|setContent") && args != null && args.length > 0)
                messages.add(method.getName().endsWith("Format") ? String.format((String)args[0], (Object[])args[1]) : String.valueOf(args[0]));
            if (method.getReturnType().isInstance(p)) return p;
            if (method.getReturnType().isInterface()) return proxy(method.getReturnType(), (m, a) -> null, messages);
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == int.class) return 0;
            return null;
        }));
    }

    static <T> T event(Class<T> type, BiFunction<String, Object[], Object> answer, List<String> messages) throws Exception
    {
        for (Constructor<?> constructor : type.getConstructors())
        {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 3 || !parameters[2].isInterface()) continue;
            return type.cast(constructor.newInstance(proxy(parameters[0], (m, a) -> null, messages), 0L, proxy(parameters[2], answer, messages)));
        }
        throw new AssertionError("Unexpected interaction constructor");
    }
}

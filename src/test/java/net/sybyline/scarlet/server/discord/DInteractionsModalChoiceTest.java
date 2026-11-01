package net.sybyline.scarlet.server.discord;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.server.discord.DInteractionsChoiceTest.*;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.GroupRole;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalInteraction;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.sybyline.scarlet.GroupEventPlatform;
import net.sybyline.scarlet.server.discord.DInteractions.ModalFlowOption;

public class DInteractionsModalChoiceTest
{
    static ModalMapping selection(String id, String... selected)
    {
        return new ModalMapping(null, DataObject.empty(), DataObject.empty()
            .put("id", 1).put("custom_id", id).put("type", Component.Type.STRING_SELECT.getKey())
            .put("values", Arrays.asList(selected)));
    }

    static ModalFlowOption<GroupEventPlatform[]> platforms(boolean required)
    {
        return ModalFlowOption.ofUniqueEnums("platforms", "Platforms", null, required, "Choose platforms",
            required ? 1 : 0, 3, GroupEventPlatform.class, new GroupEventPlatform[]{GroupEventPlatform.WINDOWS});
    }

    @Test void singleEnumReadsActualSelectValuesInsteadOfTextInputOrFallback()
    {
        ModalFlowOption<GroupEventPlatform> option = ModalFlowOption.ofEnum("platform", "Platform", null, true,
            "Choose platform", GroupEventPlatform.class, GroupEventPlatform.WINDOWS);
        for (GroupEventPlatform platform : GroupEventPlatform.values())
            assertSame(platform, option.resolver.apply(selection("platform", platform.value())));
        for (String[] bad : new String[][]{{}, {"unknown"}, {"ios", "android"}})
            assertThrows(DInteractions.InvalidSlashOptionException.class,
                () -> option.resolver.apply(selection("platform", bad)));
        assertThrows(DInteractions.InvalidSlashOptionException.class, () -> option.resolver.apply(null));
        ModalFlowOption<GroupEventPlatform> optional = ModalFlowOption.ofEnum("platform", "Platform", null, false,
            "Choose platform", GroupEventPlatform.class, GroupEventPlatform.WINDOWS);
        assertNull(optional.resolver.apply(null));
        assertNull(optional.resolver.apply(selection("platform")));
    }

    @Test void multipleEnumsRejectUnknownDuplicatesAndMissingRequiredSelections()
    {
        ModalFlowOption<GroupEventPlatform[]> option = platforms(true);
        assertArrayEquals(GroupEventPlatform.values(), option.resolver.apply(selection("platforms", "android", "ios", "standalonewindows")));
        for (String[] bad : new String[][]{{}, {"unknown"}, {"ios", "unknown"}, {"ios", "ios"}, {"android", "ios", "standalonewindows", "extra"}})
            assertThrows(DInteractions.InvalidSlashOptionException.class,
                () -> option.resolver.apply(selection("platforms", bad)));
        assertThrows(DInteractions.InvalidSlashOptionException.class, () -> option.resolver.apply(null));
        assertArrayEquals(new GroupEventPlatform[0], platforms(false).resolver.apply(null));
        assertArrayEquals(new GroupEventPlatform[0], platforms(false).resolver.apply(selection("platforms")));
    }

    @Test void formShowsCurrentPlatformsRatherThanHardcodedWindowsDefault()
    {
        ModalFlowOption<GroupEventPlatform[]> option = platforms(false);
        Label populated = (Label) option.populator.apply(new GroupEventPlatform[]{GroupEventPlatform.IOS});
        assertEquals(Collections.singletonList("ios"), populated.getChild().asStringSelectMenu().getOptions().stream()
            .filter(value -> value.isDefault()).map(value -> value.getValue()).collect(java.util.stream.Collectors.toList()));
        Label cleared = (Label) option.populator.apply(new GroupEventPlatform[0]);
        assertTrue(cleared.getChild().asStringSelectMenu().getOptions().stream().noneMatch(value -> value.isDefault()));
    }

    @Test void unknownGroupRoleSelectionsDoNotSilentlyClearOrPartiallyReplaceRoles()
    {
        GroupRole known = new GroupRole().id("grol_known").name("Synthetic");
        Map<String, GroupRole> roles = Collections.singletonMap(known.getId(), known);
        assertArrayEquals(new GroupRole[]{known}, ModalFlowOption.selectedGroupRoles(selection("roles", known.getId()), false, 0, 25, roles));
        assertThrows(DInteractions.InvalidSlashOptionException.class,
            () -> ModalFlowOption.selectedGroupRoles(selection("roles", known.getId(), "grol_unknown"), false, 0, 25, roles));
        assertArrayEquals(new GroupRole[0], ModalFlowOption.selectedGroupRoles(null, false, 0, 25, roles));
    }

    @Test void roleMenusCanReopenWithoutAccumulatingOptionsAndDefaultsStayOnTheirPage()
    {
        Map<String, GroupRole> roles = new java.util.HashMap<>();
        for (int index = 0; index < 30; index++)
        {
            String id = String.format("grol_%03d", index);
            roles.put(id, new GroupRole().id(id).name("Role " + index));
        }
        ModalFlowOption<GroupRole[]> first = ModalFlowOption.ofUniqueGroupRoles("first", "First roles", null,
            false, "Choose", 0, 25, roles);
        ModalFlowOption<GroupRole[]> next = ModalFlowOption.ofUniqueGroupRolesNext25("next", "More roles", null,
            false, "Choose", 0, 25, roles);
        GroupRole[] selected = {roles.get("grol_003"), roles.get("grol_027"), new GroupRole().id("grol_deleted").name("Deleted")};
        for (int opening = 0; opening < 4; opening++)
        {
            net.dv8tion.jda.api.components.selections.StringSelectMenu firstMenu = ((Label) first.populator.apply(selected)).getChild().asStringSelectMenu();
            net.dv8tion.jda.api.components.selections.StringSelectMenu nextMenu = ((Label) next.populator.apply(selected)).getChild().asStringSelectMenu();
            assertEquals(25, firstMenu.getOptions().size());
            assertEquals(5, nextMenu.getOptions().size());
            assertEquals(Collections.singletonList("grol_003"), firstMenu.getOptions().stream().filter(value -> value.isDefault())
                .map(value -> value.getValue()).collect(java.util.stream.Collectors.toList()));
            assertEquals(Collections.singletonList("grol_027"), nextMenu.getOptions().stream().filter(value -> value.isDefault())
                .map(value -> value.getValue()).collect(java.util.stream.Collectors.toList()));
            assertTrue(firstMenu.getOptions().stream().noneMatch(value -> nextMenu.getOptions().stream()
                .anyMatch(other -> value.getValue().equals(other.getValue()))));
        }
    }

    @Test void emptyOrShortRoleListsHaveSafeSecondPagesAndOptionalClearStillWorks()
    {
        for (int size : new int[]{0, 1, 24, 25})
        {
            Map<String, GroupRole> roles = new java.util.HashMap<>();
            for (int index = 0; index < size; index++)
                roles.put("grol_" + index, new GroupRole().id("grol_" + index).name("Role " + index));
            ModalFlowOption<GroupRole[]> next = ModalFlowOption.ofUniqueGroupRolesNext25("next", "More roles", null,
                false, "Choose", 0, 25, roles);
            assertEquals(Component.Type.TEXT_DISPLAY, next.populator.apply(null).getType());
            assertArrayEquals(new GroupRole[0], next.resolver.apply(null));
            assertArrayEquals(new GroupRole[0], next.resolver.apply(selection("next")));
        }
    }

    @DInteractions.ModalSub("synthetic-settings")
    @DInteractions.Desc("Synthetic settings")
    public static class FormFixture implements DInteractions.ModalFlow<FormFixture>
    {
        @DInteractions.StringSel("first") public GroupEventPlatform first = GroupEventPlatform.WINDOWS;
        @DInteractions.StringSel("platforms") public GroupEventPlatform[] platforms = {GroupEventPlatform.WINDOWS};
        boolean saved;
        @Override public void handle(ModalInteractionEvent ignored) { this.saved = true; }
    }

    @Test void invalidFormDoesNotAssignEarlierFieldsOrInvokeSaveHandler()
    {
        DInteractions interactions = new DInteractions(false, null);
        interactions.registerModalFlowOption(null, null, "first", ModalFlowOption.ofEnum("first", "First", null,
            true, "Choose", GroupEventPlatform.class, GroupEventPlatform.WINDOWS));
        interactions.registerModalFlowOption(null, null, "platforms", platforms(false));
        DInteractions.ModalFlowInfo<FormFixture> info = interactions.new ModalFlowInfo<>(FormFixture.class, null, null);
        FormFixture form = new FormFixture();
        AtomicReference<String> reply = new AtomicReference<>();
        AtomicBoolean privateReply = new AtomicBoolean();
        info.handle(modalEvent(reply, privateReply, selection("first", "ios"), selection("platforms", "unknown")), form, event -> true);
        assertFalse(form.saved);
        assertSame(GroupEventPlatform.WINDOWS, form.first);
        assertArrayEquals(new GroupEventPlatform[]{GroupEventPlatform.WINDOWS}, form.platforms);
        assertTrue(privateReply.get());
        assertEquals("Select a valid option from the suggested choices. The command was not applied.", reply.get());

        FormFixture valid = new FormFixture();
        info.handle(modalEvent(reply, privateReply, selection("first", "ios"), selection("platforms", "android")), valid, event -> true);
        assertTrue(valid.saved);
        assertSame(GroupEventPlatform.IOS, valid.first);
        assertArrayEquals(new GroupEventPlatform[]{GroupEventPlatform.ANDRIOD}, valid.platforms);
    }

    @Test void authorityRevokedDuringOptionResolutionPreventsFieldAssignmentAndSave()
    {
        DInteractions interactions = new DInteractions(false, null);
        interactions.registerModalFlowOption(null, null, "first", ModalFlowOption.ofEnum("first", "First", null,
            true, "Choose", GroupEventPlatform.class, GroupEventPlatform.WINDOWS));
        interactions.registerModalFlowOption(null, null, "platforms", platforms(false));
        DInteractions.ModalFlowInfo<FormFixture> info = interactions.new ModalFlowInfo<>(FormFixture.class, null, null);
        FormFixture form = new FormFixture();
        AtomicReference<String> reply = new AtomicReference<>();
        info.handle(modalEvent(reply, new AtomicBoolean(), selection("first", "ios"), selection("platforms", "android")),
            form, event -> false);
        assertFalse(form.saved);
        assertSame(GroupEventPlatform.WINDOWS, form.first);
        assertTrue(reply.get().contains("permissions/context changed"));
    }

    static ModalInteractionEvent modalEvent(AtomicReference<String> reply, AtomicBoolean privateReply, ModalMapping... values)
    {
        User user = proxy(User.class, (p, m, a) -> m.getName().equals("getId") ? "synthetic-user" : "Synthetic");
        ModalInteraction interaction = proxy(ModalInteraction.class, (p, m, a) -> {
            if (m.getName().equals("getUser")) return user;
            if (m.getName().equals("getValues")) return Arrays.asList(values);
            if (m.getName().equals("getValue")) return Arrays.stream(values).filter(value -> value.getCustomId().equals(a[0])).findFirst().orElse(null);
            if (m.getName().equals("deferReply")) return replyAction(m.getReturnType(), reply, privateReply);
            if (m.isDefault()) return MethodHandles.privateLookupIn(m.getDeclaringClass(), MethodHandles.lookup())
                .unreflectSpecial(m, m.getDeclaringClass()).bindTo(p).invokeWithArguments(a);
            if (m.getReturnType() == long.class) return 0L;
            if (m.getReturnType() == boolean.class) return false;
            return null;
        });
        return new ModalInteractionEvent(proxy(JDA.class, (p, m, a) -> null), 0L, interaction);
    }
}

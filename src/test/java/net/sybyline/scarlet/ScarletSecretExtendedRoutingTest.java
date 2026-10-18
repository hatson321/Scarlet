package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.dv8tion.jda.api.interactions.commands.Command;
import net.sybyline.scarlet.server.discord.DInteractions;

class ScarletSecretExtendedRoutingTest
{
    @TempDir Path temp;

    @Test void onlyThreeImplementedSecretRoutesAreOfferedForNewConfiguration()
    {
        Set<String> choices = Arrays.stream(ScarletDiscordCommands.secretAuditExChoices(Collections.emptyMap()))
            .map(Command.Choice::getAsString).collect(Collectors.toSet());
        assertEquals(new java.util.HashSet<>(Arrays.asList(GroupAuditTypeEx.INSTANCE_MONITOR.id,
            GroupAuditTypeEx.ACTION_FAIL.id, GroupAuditTypeEx.LIVE_PLAYERLIST.id)), choices);
    }

    @Test void legacyRoutesAreOfferedOnlyWithExplicitClearLabel()
    {
        Map<String, String> saved = new HashMap<>();
        saved.put(GroupAuditTypeEx.SPAWN_EMOJI.id, "synthetic");
        saved.put("unknown-old-entry", "synthetic");
        Command.Choice[] choices = ScarletDiscordCommands.secretAuditExChoices(saved);
        assertEquals(4, choices.length);
        assertTrue(Arrays.stream(choices).anyMatch(choice -> choice.getAsString().equals(GroupAuditTypeEx.SPAWN_EMOJI.id)
            && choice.getName().startsWith("Clear legacy entry:")));
        assertEquals(2, saved.size(), "Listing choices must not alter the stored configuration");
    }

    @Test void unsupportedAssignmentsAreRejectedButExistingEntriesRemainClearable() throws Exception
    {
        ScarletConfigCommandMappingsTest.Fixture fixture = new ScarletConfigCommandMappingsTest.Fixture(temp);
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
        {
            if (type.supportsSecretRouting()) continue;
            fixture.discord.auditExType2secretChannelSf.put(type.id, "old");
            int before = fixture.discord.saves;
            fixture.commands.setAuditSecretExChannel(null, fixture.hook, type,
                ScarletConfigCommandMappingsTest.channel("configured", "123", true));
            assertEquals(before, fixture.discord.saves);
            assertEquals("old", fixture.discord.auditExType2secretChannelSf.get(type.id));
            assertTrue(fixture.messages.get(fixture.messages.size() - 1).startsWith("Nothing changed"));
            assertThrows(IllegalArgumentException.class, () -> fixture.discord.setAuditExSecretChannel(type,
                ScarletConfigCommandMappingsTest.channel("configured", "123", true)));
            fixture.commands.setAuditSecretExChannel(null, fixture.hook, type, null);
            assertFalse(fixture.discord.auditExType2secretChannelSf.containsKey(type.id));
            assertEquals(before + 1, fixture.discord.saves);
        }
    }

    @Test void canonicalValuesRemainExactAndUnknownWordsNeverBecomeAnEvent()
    {
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
            assertSame(type, ScarletDiscordCommands.parseSecretAuditExType(type.id));
        for (String value : new String[]{"live", "kick", "warns", "", "Instance Close", "GROUPEX.INSTANCE.MONITOR"})
            assertThrows(DInteractions.InvalidSlashOptionException.class,
                () -> ScarletDiscordCommands.parseSecretAuditExType(value));
    }

    @Test void reflectedCommandUsesTheRestrictedSecretOption() throws Exception
    {
        ScarletConfigCommandMappingsTest.Fixture fixture = new ScarletConfigCommandMappingsTest.Fixture(temp);
        DInteractions.SlashOption<GroupAuditTypeEx> option = fixture.commands.secretAuditExOption();
        ScarletDiscordSafetyTest.put(fixture.commands, "_auditSecretExEventType", option);
        assertTrue(fixture.interactions.registerSlashCmdOption(fixture.commands,
            ScarletDiscordCommands.class.getField("_auditSecretExEventType"), null, null));
        fixture.interactions.registerOption(null, null, "_textChannel",
            DInteractions.SlashOption.ofChannel("discord-text-channel", "Synthetic channel", false));
        java.lang.reflect.Method method = ScarletDiscordCommands.class.getMethod("setAuditSecretExChannel",
            net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent.class,
            net.dv8tion.jda.api.interactions.InteractionHook.class, GroupAuditTypeEx.class,
            net.dv8tion.jda.api.entities.channel.Channel.class);
        DInteractions.SlashOption<?>[] bound = fixture.interactions.findOptions(null, null, method, 2);
        assertSame(option, bound[0]);
        assertEquals("secret-audit-ex-event-type", bound[0].data().getName());
        assertTrue(bound[0].data().isAutoComplete());
        assertNotNull(bound[1]);
    }
}

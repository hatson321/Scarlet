package net.sybyline.scarlet.server.discord;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.commands.*;
import net.dv8tion.jda.api.interactions.commands.build.*;
import org.junit.jupiter.api.Test;

class DCommandsSchemaTest
{
    @Test void requiredAutocompleteAndChannelTypesCauseChanges()
    {
        OptionData optional = new OptionData(OptionType.STRING, "value", "Value");
        Command.Option existing = new Command.Option(optional.toData());
        assertTrue(DCommands.equals_option(optional, existing));
        assertFalse(DCommands.equals_option(new OptionData(OptionType.STRING, "value", "Value", true), existing));
        assertFalse(DCommands.equals_option(new OptionData(OptionType.STRING, "value", "Value").setAutoComplete(true), existing));
        OptionData text = new OptionData(OptionType.CHANNEL, "channel", "Channel").setChannelTypes(ChannelType.TEXT);
        assertFalse(DCommands.equals_option(new OptionData(OptionType.CHANNEL, "channel", "Channel").setChannelTypes(ChannelType.VOICE), new Command.Option(text.toData())));
        assertTrue(DCommands.equals_option(text, new Command.Option(text.toData())));
    }

    @Test void changedChoiceTranslationsAreDetected()
    {
        Command.Choice current = new Command.Choice("First", "first").setNameLocalization(DiscordLocale.GERMAN, "Erste");
        Command.Choice changed = new Command.Choice("First", "first").setNameLocalization(DiscordLocale.GERMAN, "Erster");
        assertFalse(DCommands.equals_choice(changed, current));
        assertTrue(DCommands.equals_choice(current, current));
    }

    static Command existing(CommandData data)
    {
        return (Command) Proxy.newProxyInstance(Command.class.getClassLoader(), new Class<?>[]{Command.class}, (p, m, a) -> {
            switch (m.getName()) {
            case "getType": return data.getType();
            case "getName": case "getFullCommandName": return data.getName();
            case "getContexts": return java.util.EnumSet.copyOf(data.getContexts());
            case "getIntegrationTypes": return java.util.EnumSet.copyOf(data.getIntegrationTypes());
            case "getDefaultPermissions": return data.getDefaultPermissions();
            case "isNSFW": return data.isNSFW();
            case "getNameLocalizations": return data.getNameLocalizations();
            case "getDescription": return ((SlashCommandData)data).getDescription();
            case "getDescriptionLocalizations": return ((SlashCommandData)data).getDescriptionLocalizations();
            default: throw new AssertionError(m.getName());
            }
        });
    }

    @Test void contextCommandPermissionChangesRequestExactlyOneEdit()
    {
        CommandData old = Commands.user("Inspect").setDefaultPermissions(DefaultMemberPermissions.ENABLED);
        CommandData changed = Commands.user("Inspect").setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));
        AtomicInteger edits = new AtomicInteger();
        DCommands.delta(List.of(existing(old)), List.of(changed), a -> fail("Unexpected add"), a -> fail("Unexpected remove"),
            (a,b) -> edits.incrementAndGet(), (a,b) -> fail("Incorrectly skipped permission update"));
        assertEquals(1, edits.get());
        assertTrue(DCommands.equals_command(existing(changed), changed));
    }

    @Test void installationScopeChangesAreDetected()
    {
        CommandData guild = Commands.user("Inspect").setIntegrationTypes(IntegrationType.GUILD_INSTALL);
        CommandData user = Commands.user("Inspect").setIntegrationTypes(IntegrationType.USER_INSTALL);
        assertFalse(DCommands.equals_command(existing(guild), user));
    }
}

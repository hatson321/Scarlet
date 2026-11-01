package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletVRChatBanEntryPointsTest.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.sybyline.scarlet.server.discord.DPerms;
import net.sybyline.scarlet.server.discord.DPerms.PermType;

/** Storage failures use synthetic temp files only; no bot, credentials, preferences or network. */
class ScarletDiscordStorageSafetyTest
{
    @TempDir Path temp;

    @Test void damagedDiscordConfigNeverPromptsOrOverwritesTheOriginal() throws Exception
    {
        int index = 0;
        for (String damaged : new String[]{"", "null", "[]", "{\"guildSf\":\"100\"", "{} trailing",
            "{\"auditType2channelSf\":{\"group.user.ban\":null}}", "{\"queuedActions\":[null]}"})
        {
            Path file = temp.resolve("discord-" + index++ + ".json");
            Files.writeString(file, damaged);
            byte[] original = Files.readAllBytes(file);
            ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
            put(discord, "discordBotFile", file.toFile());
            // Scarlet/token are deliberately absent: invalid storage must fail before any prompt or login.
            assertThrows(IllegalStateException.class, discord::load);
            assertThrows(IllegalStateException.class, () -> discord.save(new ScarletDiscordJDA.JDASettingsSpec()));
            assertThrows(IllegalStateException.class, discord::save);
            assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    @Test void nonFileDiscordConfigAndFailedWritesRemainVisibleAndPreserved() throws Exception
    {
        Path directory = Files.createDirectory(temp.resolve("discord.json"));
        Path marker = directory.resolve("marker.txt");
        Files.writeString(marker, "synthetic-preserved");
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        put(discord, "discordBotFile", directory.toFile());
        assertThrows(IllegalStateException.class, discord::load);
        assertThrows(IllegalStateException.class, () -> discord.save(new ScarletDiscordJDA.JDASettingsSpec()));
        ScarletDiscordJDA saveFailure = inert(ScarletDiscordJDA.class);
        put(saveFailure, "discordBotFile", directory.toFile());
        assertThrows(IllegalStateException.class, () -> saveFailure.save(new ScarletDiscordJDA.JDASettingsSpec()));
        assertEquals("synthetic-preserved", Files.readString(marker));
    }

    @Test void validDiscordRoutingAndBoundQueueRoundTripWithoutLosingContext() throws Exception
    {
        Path file = temp.resolve("valid-discord.json");
        ScarletDiscordJDA.JDASettingsSpec spec = new ScarletDiscordJDA.JDASettingsSpec();
        spec.guildSf = GUILD;
        spec.auditType2secretChannelSf.put("group.user.ban", "300");
        spec.queuedActions.add(new ScarletDiscord.Action("unban", ACTOR, TARGET, null, REQUESTER, GUILD, GROUP));
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        put(discord, "discordBotFile", file.toFile());
        discord.save(spec);
        ScarletDiscordJDA.JDASettingsSpec reopened = ScarletDiscordJDA.readDiscordSettings(file.toFile());
        assertEquals("300", reopened.auditType2secretChannelSf.get("group.user.ban"));
        assertEquals(GROUP, reopened.queuedActions.get(0).vrchatGroupId);
        assertEquals(REQUESTER, reopened.queuedActions.get(0).requesterDiscordId);
    }

    @Test void corruptPermissionsBlockDefaultAndAdminAuthorityAndCannotBeSaved() throws Exception
    {
        Fixture f = new Fixture(temp.resolve("fixture"));
        int index = 0;
        for (String damaged : new String[]{"", "null", "[]", "{", "{} trailing",
            "{\"UNKNOWN_TYPE\":{}}", "{\"BUTTON_PRESS\":null}",
            "{\"BUTTON_PRESS\":{\"byUser\":{\"200\":null}}}",
            "{\"BUTTON_PRESS\":{\"byUser\":{\"200\":{\"event-unredact\":\"true\"}}}}"})
        {
            Path file = temp.resolve("permissions-" + index++ + ".json");
            Files.writeString(file, damaged);
            byte[] original = Files.readAllBytes(file);
            DPerms permissions = new DPerms(file.toFile());
            permissions.getGuildSnowflakesMutable().add(GUILD);
            assertThrows(IllegalStateException.class, permissions::load);
            f.discord.admin = false;
            assertFalse(permissions.checkOperation(f.member, PermType.BUTTON_PRESS, "event-unredact"));
            f.discord.admin = true;
            assertFalse(permissions.checkOperation(f.member, PermType.BUTTON_PRESS, "event-unredact"));
            assertThrows(IllegalStateException.class, permissions::save);
            assertThrows(IllegalStateException.class, () -> permissions.set(PermType.BUTTON_PRESS, f.member, "event-unredact", true));
            assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    @Test void permissionsRecoverOnlyAfterAValidReloadAndNewStoresStillWork() throws Exception
    {
        Fixture f = new Fixture(temp.resolve("fixture"));
        Path file = temp.resolve("permissions.json");
        DPerms permissions = new DPerms(file.toFile());
        permissions.getGuildSnowflakesMutable().add(GUILD);
        permissions.load(); // Definitively missing first-run store.
        permissions.set(PermType.BUTTON_PRESS, f.member, "event-unredact", false);
        permissions.save();
        String valid = Files.readString(file);
        Files.writeString(file, "null");
        assertThrows(IllegalStateException.class, permissions::load);
        assertThrows(IllegalStateException.class, permissions::save);
        Files.writeString(file, valid);
        permissions.load();
        assertFalse(permissions.checkOperation(f.member, PermType.BUTTON_PRESS, "event-unredact"));
        permissions.save();
        DPerms reopened = new DPerms(file.toFile());
        reopened.load();
        assertEquals(Boolean.FALSE, reopened.get(PermType.BUTTON_PRESS, f.member, "event-unredact"));
    }

    @Test void failedPermissionCommandSaveRollsBackGrantAndDoesNotClaimSuccess() throws Exception
    {
        Fixture f = new Fixture(temp.resolve("fixture"));
        Path directory = Files.createDirectory(temp.resolve("permission-save-target"));
        DPerms permissions = new DPerms(directory.toFile());
        List<String> messages = new ArrayList<>();
        gnu.trove.map.hash.TLongObjectHashMap<Object> resolved = new gnu.trove.map.hash.TLongObjectHashMap<>();
        resolved.put(200L, f.member);
        Map<String, OptionMapping> options = new HashMap<>();
        options.put("target", new OptionMapping(DataObject.empty().put("name", "target").put("type", 9).put("value", "200"), resolved, null, null));
        for (String[] pair : new String[][]{{"type", "slash-command"}, {"name", "settings edit"}, {"value", "true"}})
            options.put(pair[0], record(OptionMapping.class, DataObject.empty().put("name", pair[0]).put("type", 3).put("value", pair[1])));
        SlashCommandInteractionEvent event = ScarletConfigCommandMappingsTest.event(SlashCommandInteractionEvent.class, (method, args) -> {
            if (method.equals("getSubcommandName")) return "set";
            if (method.equals("getOptions")) return new ArrayList<>(options.values());
            if (method.equals("getOption")) {
                OptionMapping option = options.get((String)args[0]);
                if (args.length == 2) return ((java.util.function.Function<OptionMapping, ?>)args[1]).apply(option);
                return option;
            }
            if (method.equals("isAcknowledged")) return true;
            return null;
        }, messages);
        permissions.internal_handle(event);
        assertNull(permissions.get(PermType.SLASH_COMMAND, f.member, "settings edit"));
        assertTrue(messages.stream().anyMatch(message -> message.contains("could not be saved and was not applied")));
        assertFalse(messages.stream().anyMatch(message -> message.startsWith("Set ")));
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.*;
import static net.sybyline.scarlet.ScarletVRChatBanEntryPointsTest.proxy;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.sybyline.scarlet.server.discord.DPerms;
import net.sybyline.scarlet.server.discord.DPerms.PermType;

class ScarletExitPermissionsTest
{
    @TempDir Path temp;
    boolean admin, owner, visible = true;
    String guildId = "100", channelId = "300";
    @Test void dedicatedPermissionAndCorrectVisibleOpsChannelAreRequired() throws Exception
    {
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        discord.guildSf = "100"; discord.opsAlertChannelSf = "300";
        Guild guild = proxy(Guild.class, (m, a) -> m.equals("getId") ? guildId : null);
        Member member = proxy(Member.class, (m, a) -> {
            if (m.equals("getId")) return "200";
            if (m.equals("getGuild")) return guild;
            if (m.equals("getRoles")) return Collections.emptyList();
            if (m.equals("isOwner")) return owner;
            if (m.equals("hasPermission")) return a.length == 2 ? visible : admin;
            return null;
        });
        TextChannel channel = proxy(TextChannel.class, (m, a) -> {
            if (m.equals("getGuild")) return guild;
            return m.equals("getId") ? channelId : null;
        });
        Path file = temp.resolve("permissions.json");
        DPerms perms = new DPerms(file.toFile());
        perms.getGuildSnowflakesMutable().add("100");
        put(discord, "perms", perms);
        ScarletExitOperatorDiscord controls = new ScarletExitOperatorDiscord(discord);
        assertFalse(controls.allowed(member, channel));
        perms.set(PermType.BUTTON_PRESS, member, "*", true);
        assertFalse(controls.allowed(member, channel), "Ordinary button permission is not an exit override");
        perms.set(PermType.OTHER, member, ScarletExitOperatorDiscord.PERMISSION, true);
        assertTrue(controls.allowed(member, channel));
        perms.set(PermType.OTHER, member, ScarletExitOperatorDiscord.PERMISSION, false);
        assertFalse(controls.allowed(member, channel));
        admin = true; assertTrue(controls.allowed(member, channel));
        admin = false; owner = true; assertTrue(controls.allowed(member, channel));
        visible = false; assertFalse(controls.allowed(member, channel)); visible = true;
        channelId = "301"; assertFalse(controls.allowed(member, channel)); channelId = "300";
        guildId = "101"; assertFalse(controls.allowed(member, channel)); guildId = "100";
        assertFalse(controls.allowed(null, channel)); assertFalse(controls.allowed(member, null));
        Files.writeString(file, "null"); assertThrows(IllegalStateException.class, perms::load);
        assertFalse(controls.allowed(member, channel), "Corrupt permissions fail closed even for owner");
    }
}

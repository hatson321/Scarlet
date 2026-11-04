package net.sybyline.scarlet;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;

final class DiscordModerationAuthorization
{
    private DiscordModerationAuthorization() {}

    static boolean allowed(Member requester, Member target, Permission permission)
    {
        return requester != null && target != null
            && requester.getGuild().getId().equals(target.getGuild().getId())
            && !requester.getId().equals(target.getId()) && !target.isOwner()
            && requester.hasPermission(permission) && requester.canInteract(target);
    }
}

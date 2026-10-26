package net.sybyline.scarlet;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Supplier;

import io.github.vrchatapi.model.PublicProfile;
import net.dv8tion.jda.api.EmbedBuilder;
import net.sybyline.scarlet.util.VrcIds;

/** Cache-only, optional Discord thumbnails; never fetches or decodes an image. */
final class ScarletJoinLeaveThumbnail
{
    private ScarletJoinLeaveThumbnail() { }

    static boolean isUserId(String value)
    {
        return value != null && VrcIds.id_user.matcher(value).matches();
    }

    static EmbedBuilder apply(EmbedBuilder builder, boolean enabled, Supplier<PublicProfile> cachedProfile)
    {
        if (!enabled) return builder;
        try
        {
            PublicProfile profile = cachedProfile.get();
            if (profile == null) return builder;
            // The current-avatar image also carries VRChat's profile-picture override.
            // Discord's thumbnail slot remains small regardless of source image dimensions.
            for (String candidate : new String[] { profile.getCurrentAvatarImageUrl(),
                profile.getCurrentAvatarThumbnailImageUrl(), profile.getIconUrl(), profile.getUserIcon() })
            {
                String url = safeImageUrl(candidate);
                if (url == null) continue;
                builder.setThumbnail(url);
                break;
            }
        }
        catch (RuntimeException ex)
        {
            // Missing/corrupt optional profile metadata must not suppress the event itself.
        }
        return builder;
    }

    static String safeImageUrl(String value)
    {
        if (value == null) return null;
        String url = value.trim();
        if (url.isEmpty() || url.length() > 2000) return null;
        try
        {
            URI parsed = new URI(url);
            String scheme = parsed.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && parsed.getHost() != null && parsed.getRawUserInfo() == null
                && parsed.getRawFragment() == null ? url : null;
        }
        catch (URISyntaxException ex)
        {
            return null;
        }
    }
}

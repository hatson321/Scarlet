package net.sybyline.scarlet;

import io.github.vrchatapi.model.PublicProfile;
import io.github.vrchatapi.model.User;
import io.github.vrchatapi.model.UserStatus;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

/** Optional public-profile context. No network requests, image decoding or moderation actions. */
final class ScarletVoteKickProfile
{
    static final int BIO_LIMIT = 240;
    static final int PRONOUN_LIMIT = 80;
    static final int STATUS_MESSAGE_LIMIT = 160;

    private ScarletVoteKickProfile() { }

    static MessageEmbed enrich(MessageEmbed base, String targetId, User user, PublicProfile profile)
    {
        if (base == null || !ScarletJoinLeaveThumbnail.isUserId(targetId)
            || ScarletSimulation.isTrainingId(targetId)) return base;
        try
        {
            // Old cache records may omit id; an explicit different identity is never usable.
            if (user != null && user.getId() != null && !targetId.equals(user.getId())) user = null;
            if (profile != null && profile.getId() != null && !targetId.equals(profile.getId())) profile = null;
            if (user == null && profile == null) return base;
            EmbedBuilder builder = new EmbedBuilder(base);
            if (profile != null)
            {
                PublicProfile picture = profile;
                ScarletJoinLeaveThumbnail.apply(builder, true, () -> picture);
                addText(builder, "Bio (excerpt)", profile.getBio(), BIO_LIMIT, false);
            }
            // A returned public profile is authoritative even when a field was cleared.
            // Do not resurrect old cached pronouns/status text after the user removes them.
            addText(builder, "Pronouns", profile != null ? profile.getPronouns() : user.getPronouns(), PRONOUN_LIMIT, true);
            UserStatus status = profile != null ? profile.getStatus() : user.getStatus();
            if (status != null) builder.addField("Status (profile snapshot)", statusLabel(status), true);
            addText(builder, "Status message", profile != null ? profile.getStatusDescription() : user.getStatusDescription(),
                STATUS_MESSAGE_LIMIT, false);
            return builder.build();
        }
        catch (RuntimeException ex)
        {
            // Optional/corrupt metadata must not prevent delivery of the original vote alert.
            return base;
        }
    }

    private static void addText(EmbedBuilder builder, String label, String value, int maximum, boolean inline)
    {
        String text = text(value, maximum);
        if (text != null) builder.addField(label, text, inline);
    }

    static String text(String value, int maximum)
    {
        if (value == null || maximum < 1) return null;
        StringBuilder normalized = new StringBuilder();
        int index = 0, count = 0;
        boolean space = false, truncated = false;
        // Bound processing as well as output, including pathological whitespace/control-only input.
        while (index < value.length() && index < 4096 && count < maximum)
        {
            int cp = value.codePointAt(index);
            index += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp))
            {
                space = normalized.length() > 0;
                continue;
            }
            if (Character.isISOControl(cp) || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2066 && cp <= 0x2069) || cp == 0x061C || cp == 0x200E || cp == 0x200F) continue;
            if (space)
            {
                normalized.append(' ');
                count++;
                space = false;
                if (count >= maximum) { truncated = true; break; }
            }
            if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;
            normalized.appendCodePoint(cp);
            count++;
        }
        String plain = normalized.toString().trim();
        if (plain.isEmpty()) return null;
        if (truncated || index < value.length()) plain += "…";
        // Render user-supplied content as text, not Markdown or Discord mention syntax.
        String escaped = MarkdownSanitizer.escape(plain.replace('<', '‹').replace('>', '›'))
            .replace("@", "@\u200B");
        return AuditEmbedBuilder.clip(escaped, Math.min(1024, maximum * 2 + 16));
    }

    private static String statusLabel(UserStatus status)
    {
        switch (status)
        {
        case ACTIVE: return "Online";
        case JOIN_ME: return "Join Me";
        case ASK_ME: return "Ask Me";
        case BUSY: return "Do Not Disturb";
        case OFFLINE: return "Offline";
        default: return "Unavailable";
        }
    }
}

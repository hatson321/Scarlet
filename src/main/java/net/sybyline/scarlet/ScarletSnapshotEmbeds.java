package net.sybyline.scarlet;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.sybyline.scarlet.util.TrustRank;
import net.sybyline.scarlet.util.VrcIds;

/** Presentation of already projected snapshot data; no live lookups or file access. */
final class ScarletSnapshotEmbeds
{
    static final int GROUPS_PER_PAGE = 5;
    private ScarletSnapshotEmbeds() {}

    static MessageEmbed[] user(JsonObject safe)
    {
        String id = value(safe, "id");
        if (id == null || !VrcIds.id_user.matcher(id).matches()) return new MessageEmbed[0];
        String url = "https://vrchat.com/home/user/" + id;
        EmbedBuilder card = new EmbedBuilder().setTitle(or(text(value(safe, "displayName"), 160), "User profile"), url)
            .setAuthor("User profile snapshot", url, image(safe, "userIcon", "iconUrl"))
            .setThumbnail(image(safe, "profilePicOverride", "currentAvatarImageUrl", "currentAvatarThumbnailImageUrl", "iconUrl", "userIcon"))
            .setDescription(savedText(safe, "bio", 3600, "No bio set at capture.", "Bio was not captured in this snapshot."))
            .addField("User ID", "`" + id + "`", false)
            .addField("Account created", savedText(safe, "date_joined", 80, "Not set", "Not captured"), true)
            .addField("Pronouns", savedText(safe, "pronouns", 160, "Not set", "Not captured"), true)
            .addField("Trust rank", rank(safe), true)
            .setFooter("Saved snapshot · details reflect the time of capture");
        addText(card, safe, "currentAvatarName", "Avatar");
        addText(card, safe, "currentAvatarAuthorName", "Avatar author");
        String banner = image(safe, "bannerCustomUrl", "bannerUrl");
        if (banner != null) card.setImage(banner);

        List<MessageEmbed> cards = new ArrayList<>();
        List<String> linkPages = linkPages(safe);
        if (!linkPages.isEmpty() && linkPages.get(0).length() <= 900)
            card.addField("Profile links", linkPages.remove(0), false);
        cards.add(card.build());
        for (String links : linkPages)
            cards.add(new EmbedBuilder().setTitle("Profile links", url).setDescription(links)
                .setFooter("Saved profile links").build());
        return cards.toArray(new MessageEmbed[0]);
    }

    static MessageEmbed[] groups(JsonArray safe)
    {
        List<MessageEmbed> cards = new ArrayList<>();
        if (safe != null) for (JsonElement entry : safe)
        {
            JsonObject group = entry.getAsJsonObject();
            String description = description(group);
            EmbedBuilder card = groupCard(group).setDescription(clip(description, 900));
            if (description.length() > 900) card.setFooter("Description shortened · open the group for full text");
            // 160 title + 80 author + 900 description + 50 footer keeps five cards below 6000.
            cards.add(card.build());
        }
        return cards.toArray(new MessageEmbed[0]);
    }

    static MessageEmbed[] representedGroup(JsonObject safe)
    {
        if (safe == null) return new MessageEmbed[0];
        List<MessageEmbed> cards = new ArrayList<>();
        List<String> descriptions = split(description(safe), 4000);
        for (int i = 0; i < descriptions.size(); i++)
        {
            EmbedBuilder card = groupCard(safe).setDescription(descriptions.get(i))
                .setFooter("Represented group snapshot" + (descriptions.size() > 1 ? " · description " + (i + 1) + "/" + descriptions.size() : ""));
            if (i == 0) card.addField("Group ID", "`" + value(safe, "groupId") + "`", false);
            cards.add(card.build());
        }
        return cards.toArray(new MessageEmbed[0]);
    }

    private static EmbedBuilder groupCard(JsonObject safe)
    {
        String id = value(safe, "groupId"), url = "https://vrchat.com/home/group/" + id;
        String code = value(safe, "shortCode"), discriminator = value(safe, "discriminator");
        String author = code == null || code.trim().isEmpty() ? "Public group" : code
            + (discriminator == null || discriminator.trim().isEmpty() ? "" : "." + discriminator);
        return new EmbedBuilder().setTitle(or(text(value(safe, "name"), 160), "Public group"), url)
            .setAuthor(text(author, 80), url, image(safe, "iconUrl"))
            .setThumbnail(image(safe, "bannerUrl"));
    }

    private static String description(JsonObject safe)
    {
        String value = value(safe, "description");
        return value == null ? "Description was not captured in this snapshot."
            : or(text(value, 20000), "No description set at capture.");
    }

    private static String rank(JsonObject safe)
    {
        JsonElement tags = safe.get("trustTags");
        if (tags == null || !tags.isJsonArray()) return "Not captured";
        List<String> values = new ArrayList<>();
        for (JsonElement tag : tags.getAsJsonArray()) values.add(tag.getAsString());
        return TrustRank.of(values).toString();
    }

    private static List<String> linkPages(JsonObject safe)
    {
        List<String> pages = new ArrayList<>();
        JsonElement links = safe.get("bioLinks");
        if (links == null || !links.isJsonArray()) return pages;
        StringBuilder page = new StringBuilder();
        for (JsonElement link : links.getAsJsonArray())
        {
            String url = ScarletJoinLeaveThumbnail.safeImageUrl(link.getAsString());
            if (url == null) continue;
            try { url = new URI(url).toASCIIString(); }
            catch (Exception invalid) { continue; }
            if (url.length() > 2900) continue;
            String line = "<" + url + ">";
            int maximum = pages.isEmpty() ? 900 : 3000;
            if (page.length() > 0 && page.length() + 1 + line.length() > maximum)
            { pages.add(page.toString()); page.setLength(0); }
            if (page.length() > 0) page.append('\n');
            page.append(line);
        }
        if (page.length() > 0) pages.add(page.toString());
        return pages;
    }

    private static void addText(EmbedBuilder card, JsonObject safe, String key, String label)
    {
        String content = text(value(safe, key), 160);
        if (content != null) card.addField(label, content, true);
    }

    private static String savedText(JsonObject safe, String key, int maximum, String empty, String absent)
    {
        String content = value(safe, key);
        return content == null ? absent : or(text(content, maximum), empty);
    }

    private static String image(JsonObject safe, String... keys)
    {
        for (String key : keys)
        {
            String image = ScarletJoinLeaveThumbnail.safeImageUrl(value(safe, key));
            if (image != null) return image;
        }
        return null;
    }

    private static String value(JsonObject object, String key)
    { return object == null ? null : ScarletSnapshotPrivacy.text(object, key); }

    private static String or(String value, String fallback)
    { return value == null || value.trim().isEmpty() ? fallback : value; }

    static String text(String value, int maximum)
    {
        if (value == null) return null;
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < value.length() && i < 16384;)
        {
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            if (cp == '\r') continue;
            if (Character.isISOControl(cp) && cp != '\n' && cp != '\t') continue;
            if ((cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)
                || cp == 0x061C || cp == 0x200E || cp == 0x200F) continue;
            if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;
            plain.appendCodePoint(cp == '<' ? '‹' : cp == '>' ? '›' : cp);
        }
        String content = MarkdownSanitizer.escape(plain.toString().trim()).replace("@", "@\u200B");
        return content.isEmpty() ? null : clip(content, maximum);
    }

    private static String clip(String text, int maximum)
    {
        if (text.length() <= maximum) return text;
        int end = maximum - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }

    private static List<String> split(String text, int maximum)
    {
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < text.length();)
        {
            int end = Math.min(start + maximum, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            parts.add(text.substring(start, end)); start = end;
        }
        return parts;
    }
}

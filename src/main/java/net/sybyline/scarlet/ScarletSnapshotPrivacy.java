package net.sybyline.scarlet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.sybyline.scarlet.util.VrcIds;

/** Explicit public projections for new captures and untrusted, older saved snapshots. */
final class ScarletSnapshotPrivacy
{
    private ScarletSnapshotPrivacy() {}

    static final int MAX_TEXT = 2000, MAX_LINKS = 32, MAX_GROUPS = 200;

    static JsonObject user(JsonObject raw, String targetId)
    {
        if (raw == null || targetId == null || !VrcIds.id_user.matcher(targetId).matches()
            || !targetId.equals(text(raw, "id"))) return null;
        JsonObject safe = strings(raw, "id", "displayName", "bio", "date_joined", "userIcon",
            "profilePicOverride", "currentAvatarImageUrl", "currentAvatarThumbnailImageUrl");
        JsonElement links = raw.get("bioLinks");
        if (links != null && links.isJsonArray())
        {
            JsonArray safeLinks = new JsonArray();
            for (JsonElement link : links.getAsJsonArray())
            {
                if (safeLinks.size() >= MAX_LINKS) break;
                if (link.isJsonPrimitive() && link.getAsJsonPrimitive().isString())
                    safeLinks.add(limit(link.getAsString()));
            }
            safe.add("bioLinks", safeLinks);
        }
        return safe;
    }

    static JsonArray groups(JsonArray raw)
    {
        if (raw == null) return null;
        JsonArray safe = new JsonArray();
        for (JsonElement item : raw)
        {
            if (safe.size() >= MAX_GROUPS) break;
            JsonObject group = item.isJsonObject() ? group(item.getAsJsonObject()) : null;
            if (group != null) safe.add(group);
        }
        return safe;
    }

    static JsonObject group(JsonObject raw)
    {
        if (raw == null) return null;
        String id = text(raw, "groupId");
        // Self-account responses can include hidden/friends-only memberships and private groups.
        // Missing or future privacy values are not permission to disclose them.
        if (id == null || !VrcIds.id_group.matcher(id).matches()
            || !"visible".equals(text(raw, "memberVisibility"))
            || !"default".equals(text(raw, "privacy"))) return null;
        return strings(raw, "groupId", "name", "shortCode", "discriminator", "description",
            "iconUrl", "bannerUrl", "memberVisibility", "privacy");
    }

    private static JsonObject strings(JsonObject raw, String... names)
    {
        JsonObject safe = new JsonObject();
        for (String name : names)
        {
            String value = text(raw, name);
            if (value != null) safe.addProperty(name, limit(value));
        }
        return safe;
    }

    static String text(JsonObject object, String name)
    {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    private static String limit(String value)
    {
        if (value.length() <= MAX_TEXT) return value;
        int end = MAX_TEXT - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "\u2026";
    }

    static MessageEmbed[] embeds(JsonObject safe)
    {
        List<MessageEmbed> result = new ArrayList<>();
        if (safe != null) for (Map.Entry<String, JsonElement> field : safe.entrySet())
        {
            String value = field.getValue().isJsonArray() ? field.getValue().toString() : field.getValue().getAsString();
            // One bounded embed per page avoids Discord's aggregate 6000-character limit.
            result.add(new EmbedBuilder().setTitle(field.getKey())
                .setDescription(limit(MarkdownSanitizer.escape(value.isEmpty() ? "(empty)" : value))).build());
        }
        return result.toArray(new MessageEmbed[0]);
    }

    static MessageEmbed[] groupEmbeds(JsonArray safe)
    {
        List<MessageEmbed> result = new ArrayList<>();
        if (safe != null) for (JsonElement item : safe)
        {
            JsonObject group = item.getAsJsonObject();
            String name = text(group, "name"), description = text(group, "description");
            result.add(new EmbedBuilder().setTitle("Public group")
                .setDescription(limit(MarkdownSanitizer.escape((name == null ? "" : name + "\n")
                    + text(group, "groupId") + (description == null ? "" : "\n" + description)))).build());
        }
        return result.toArray(new MessageEmbed[0]);
    }
}

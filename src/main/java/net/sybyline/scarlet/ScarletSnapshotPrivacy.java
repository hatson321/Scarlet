package net.sybyline.scarlet;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.sybyline.scarlet.util.VrcIds;

/** Explicit public projections for new captures and untrusted, older saved snapshots. */
final class ScarletSnapshotPrivacy
{
    private ScarletSnapshotPrivacy() {}

    static final int MAX_TEXT = 2000, MAX_LINKS = 32, MAX_GROUPS = 200;
    static final int MAX_DESCRIPTION = 8192;
    private static final Set<String> TRUST_TAGS = new HashSet<>(Arrays.asList(
        "system_troll", "system_probable_troll", "system_trust_basic", "system_trust_known",
        "system_trust_trusted", "system_trust_veteran"));

    static JsonObject user(JsonObject raw, String targetId)
    {
        if (raw == null || targetId == null || !VrcIds.id_user.matcher(targetId).matches()
            || !targetId.equals(text(raw, "id"))) return null;
        JsonObject safe = strings(raw, "id", "displayName", "bio", "date_joined", "pronouns", "userIcon", "iconUrl",
            "profilePicOverride", "currentAvatarImageUrl", "currentAvatarThumbnailImageUrl", "bannerUrl", "bannerCustomUrl",
            "currentAvatarName", "currentAvatarAuthorName");
        JsonElement tags = raw.has("trustTags") ? raw.get("trustTags") : raw.get("tags");
        if (tags != null && tags.isJsonArray())
        {
            JsonArray publicTags = new JsonArray();
            int inspected = 0;
            for (JsonElement tag : tags.getAsJsonArray())
            {
                if (inspected++ >= 128) break;
                if (tag.isJsonPrimitive() && tag.getAsJsonPrimitive().isString()
                    && TRUST_TAGS.contains(tag.getAsString()) && !publicTags.contains(tag)) publicTags.add(tag.getAsString());
            }
            safe.add("trustTags", publicTags);
        }
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

    static JsonObject mergeUser(JsonObject account, JsonObject profile)
    {
        if (profile == null) return account;
        JsonObject result = profile.deepCopy();
        // The public profile is authoritative for fields that may have been cleared.
        if (account != null && text(result, "id").equals(text(account, "id")) && account.has("date_joined"))
            result.add("date_joined", account.get("date_joined").deepCopy());
        return result;
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
        JsonObject safe = strings(raw, "groupId", "name", "shortCode", "discriminator",
            "iconUrl", "bannerUrl", "memberVisibility", "privacy");
        String description = text(raw, "description");
        if (description != null) safe.addProperty("description", limit(description, MAX_DESCRIPTION));
        return safe;
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
        return limit(value, MAX_TEXT);
    }

    private static String limit(String value, int maximum)
    {
        if (value.length() <= maximum) return value;
        int end = maximum - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "\u2026";
    }

}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.sybyline.scarlet.server.discord.DInteractions;

class ScarletSnapshotLayoutTest
{
    @TempDir Path temp;
    static final String USER = ScarletSnapshotPrivacyTest.USER;
    static final String GROUP = ScarletSnapshotPrivacyTest.GROUP;

    static JsonObject profile()
    {
        JsonObject p = new JsonObject();
        p.addProperty("id", USER); p.addProperty("displayName", "Synthetic profile");
        p.addProperty("bio", "A public biography with two lines.\nSecond line.");
        p.addProperty("pronouns", "they/them");
        p.addProperty("currentAvatarImageUrl", "https://example.invalid/avatar.png");
        p.addProperty("userIcon", "https://example.invalid/icon.png");
        p.addProperty("bannerUrl", "https://example.invalid/banner.png");
        p.addProperty("currentAvatarName", "Synthetic avatar");
        p.addProperty("currentAvatarAuthorName", "Synthetic creator");
        p.add("trustTags", JsonParser.parseString("[\"system_trust_veteran\",\"PRIVATE_UNKNOWN_TAG\"]"));
        p.add("bioLinks", JsonParser.parseString("[\"https://example.invalid/about\"]"));
        p.addProperty("email", "PRIVATE_EMAIL_MARKER");
        p.addProperty("location", "PRIVATE_LOCATION_MARKER");
        p.add("groups", JsonParser.parseString("[{\"secret\":\"PRIVATE_GROUP_MARKER\"}]"));
        return p;
    }

    static JsonObject minimal()
    {
        JsonObject p = new JsonObject();
        p.addProperty("id", USER); p.addProperty("displayName", "Synthetic account");
        p.addProperty("date_joined", "2020-01-02"); return p;
    }

    static JsonObject group(int index)
    {
        JsonObject g = ScarletSnapshotPrivacyTest.group();
        g.addProperty("groupId", String.format("grp_10000000-0000-0000-0000-%012d", index + 1));
        g.addProperty("name", "Example group " + (index + 1));
        g.addProperty("iconUrl", "https://example.invalid/group-icon.png");
        g.addProperty("bannerUrl", "https://example.invalid/group-banner.png");
        return g;
    }

    static void limits(MessageEmbed embed)
    {
        assertTrue(embed.getLength() <= 6000);
        if (embed.getTitle() != null) assertTrue(embed.getTitle().length() <= 256);
        if (embed.getDescription() != null) assertTrue(embed.getDescription().length() <= 4096);
        assertTrue(embed.getFields().size() <= 25);
        for (MessageEmbed.Field field : embed.getFields())
        {
            assertTrue(field.getName().length() <= 256);
            assertTrue(field.getValue().length() <= 1024);
        }
        assertFalse(embed.toData().toString().contains("PRIVATE_"));
    }

    @Test void captureCombinesMinimalAccountWithCurrentPublicProfileWithoutPrivateFields() throws Exception
    {
        try (var f = new ScarletSnapshotTransportTest.Fixture(temp))
        {
            f.reply(minimal().toString()); f.reply("[]"); f.reply(group(0).toString()); f.reply(profile().toString());
            var metadata = f.metadata(); assertNotNull(f.vrc.snapshot(metadata));
            assertEquals(4, f.requests.size()); assertEquals(4, f.passedLimiter.get());
            assertEquals("2020-01-02", metadata.snapshotTargetUser.get("date_joined").getAsString());
            assertEquals("they/them", metadata.snapshotTargetUser.get("pronouns").getAsString());
            assertTrue(metadata.snapshotTargetUser.has("currentAvatarImageUrl"));
            assertFalse(metadata.snapshotTargetUser.toString().contains("PRIVATE_"));
            MessageEmbed[] cards = ScarletSnapshotEmbeds.user(ScarletSnapshotPrivacy.user(metadata.snapshotTargetUser, USER));
            assertEquals(1, cards.length); limits(cards[0]);
            assertEquals("https://vrchat.com/home/user/" + USER, cards[0].getUrl());
            assertEquals("https://example.invalid/avatar.png", cards[0].getThumbnail().getUrl());
            assertTrue(cards[0].getDescription().contains("public biography"));
            assertTrue(cards[0].getFields().stream().anyMatch(x -> x.getName().equals("Trust rank") && x.getValue().equals("Trusted")));
            assertTrue(cards[0].getFields().size() >= 7);
        }
    }

    @Test void clearedPublicFieldsDoNotResurrectOldAccountProfileValues()
    {
        JsonObject account = ScarletSnapshotPrivacy.user(ScarletSnapshotPrivacyTest.self(), USER);
        JsonObject profile = minimal(); profile.addProperty("bio", ""); profile.addProperty("pronouns", "");
        JsonObject merged = ScarletSnapshotPrivacy.mergeUser(account, ScarletSnapshotPrivacy.user(profile, USER));
        MessageEmbed card = ScarletSnapshotEmbeds.user(merged)[0];
        assertEquals("No bio set at capture.", card.getDescription());
        assertFalse(card.toData().toString().contains("Public profile biography"));
        assertTrue(card.getFields().stream().anyMatch(x -> x.getName().equals("Pronouns") && x.getValue().equals("Not set")));
    }

    @Test void unavailableOrMismatchedPublicProfileKeepsOnlyTheMatchingSavedAccount() throws Exception
    {
        for (boolean mismatch : new boolean[]{false, true}) try (var f = new ScarletSnapshotTransportTest.Fixture(temp.resolve(Boolean.toString(mismatch))))
        {
            f.reply(minimal().toString()); f.reply("[]"); f.reply(group(0).toString());
            JsonObject profile = profile(); profile.addProperty("id", "usr_10000000-0000-0000-0000-000000000099");
            f.reply(mismatch ? 200 : 403, mismatch ? profile.toString() : "{}");
            var metadata = f.metadata(); assertNotNull(f.vrc.snapshot(metadata));
            assertEquals(USER, metadata.snapshotTargetUser.get("id").getAsString());
            assertFalse(metadata.snapshotTargetUser.has("bio"));
            MessageEmbed[] cards = ScarletSnapshotEmbeds.user(metadata.snapshotTargetUser);
            assertEquals(1, cards.length);
            assertTrue(cards[0].getDescription().contains("not captured"));
            assertEquals(4, f.requests.size());
        }
    }

    @Test void actualGroupButtonShowsFiveCardsPerPageForSeventyOneGroups() throws Exception
    {
        var privacy = new ScarletSnapshotPrivacyTest(); privacy.temp = temp;
        var f = privacy.new Fixture();
        JsonArray groups = new JsonArray(); for (int i = 0; i < 71; i++) groups.add(group(i));
        f.metadata.snapshotTargetUserGroups = groups; f.save(); f.click(1);
        assertTrue(f.denials.isEmpty()); assertEquals(5, f.embeds.size());
        DInteractions.Pagination pagination = f.pagination.get("300");
        Field pages = pagination.getClass().getDeclaredField("pages"); pages.setAccessible(true);
        int count = java.lang.reflect.Array.getLength(pages.get(pagination)); assertEquals(15, count);
        Method action = pagination.getClass().getDeclaredMethod("action", int.class); action.setAccessible(true);
        int total = 0;
        for (int i = 1; i <= count; i++)
        {
            f.embeds.clear(); action.invoke(pagination, i);
            assertEquals(i == count ? 1 : 5, f.embeds.size()); total += f.embeds.size();
            assertTrue(f.embeds.stream().mapToInt(MessageEmbed::getLength).sum() <= 6000);
            for (MessageEmbed card : f.embeds) limits(card);
        }
        assertEquals(71, total); assertTrue(f.pageAllowed("50")); assertFalse(f.pageAllowed("51"));
        f.channelAccess = false; assertFalse(f.pageAllowed("50"));
    }

    @Test void groupCardsMatchLinkedAuthorTitleIconAndBannerLayout()
    {
        JsonArray groups = new JsonArray(); groups.add(group(0));
        MessageEmbed card = ScarletSnapshotEmbeds.groups(ScarletSnapshotPrivacy.groups(groups))[0];
        assertEquals("TEST.0001", card.getAuthor().getName());
        assertEquals("Example group 1", card.getTitle());
        assertEquals("https://vrchat.com/home/group/" + group(0).get("groupId").getAsString(), card.getUrl());
        assertEquals(card.getUrl(), card.getAuthor().getUrl());
        assertEquals("https://example.invalid/group-icon.png", card.getAuthor().getIconUrl());
        assertEquals("https://example.invalid/group-banner.png", card.getThumbnail().getUrl());
        assertEquals("Public group description", card.getDescription()); limits(card);
    }

    @Test void representedGroupPreservesFullLongDescriptionOnlySplittingAtDiscordLimits()
    {
        JsonObject group = group(0); String description = "A full saved description.\n".repeat(210) + "Final sentence 😀";
        group.addProperty("description", description);
        JsonObject safe = ScarletSnapshotPrivacy.group(group);
        assertEquals(description, safe.get("description").getAsString());
        MessageEmbed[] cards = ScarletSnapshotEmbeds.representedGroup(safe);
        assertEquals(2, cards.length);
        StringBuilder all = new StringBuilder();
        for (MessageEmbed card : cards) { limits(card); all.append(card.getDescription()); assertNotNull(card.getUrl()); }
        assertEquals(ScarletSnapshotEmbeds.text(description, 20000), all.toString());
        assertEquals(1, cards[0].getFields().size());
        assertTrue(cards[0].getFields().get(0).getValue().contains(safe.get("groupId").getAsString()));
    }

    @Test void fiveWorstCaseGroupCardsStayBelowAggregateLimitAndNeutralizeMentions()
    {
        JsonArray groups = new JsonArray();
        for (int i = 0; i < 5; i++)
        {
            JsonObject g = group(i); g.addProperty("name", "@everyone *".repeat(300));
            g.addProperty("shortCode", "*".repeat(2000)); g.addProperty("description", "<@123> *😀".repeat(2000)); groups.add(g);
        }
        MessageEmbed[] cards = ScarletSnapshotEmbeds.groups(ScarletSnapshotPrivacy.groups(groups));
        int length = 0;
        for (MessageEmbed card : cards)
        {
            limits(card); length += card.getLength(); assertNotNull(card.getFooter());
            assertFalse(card.toData().toString().contains("@everyone"));
            assertFalse(card.toData().toString().contains("<@123>"));
        }
        assertEquals(5, cards.length); assertTrue(length <= 6000, "Actual aggregate: " + length);
    }

    @Test void missingAndUnsafeImagesDoNotBreakProfilesAndPublicLinksRemainClickable()
    {
        JsonObject p = profile();
        for (String key : new String[]{"currentAvatarImageUrl", "userIcon", "bannerUrl"}) p.addProperty(key, "javascript:alert(1)");
        p.addProperty("currentAvatarThumbnailImageUrl", "https://user:password@example.invalid/image.png");
        p.add("bioLinks", JsonParser.parseString("[\"file:///private\",\"javascript:alert(1)\",\"https://example.invalid/public\"]"));
        MessageEmbed card = ScarletSnapshotEmbeds.user(ScarletSnapshotPrivacy.user(p, USER))[0];
        assertNull(card.getThumbnail()); assertNull(card.getImage()); assertNull(card.getAuthor().getIconUrl());
        String json = card.toData().toString();
        assertFalse(json.contains("javascript:")); assertFalse(json.contains("file:")); assertFalse(json.contains("password"));
        assertTrue(json.contains("https://example.invalid/public")); limits(card);
    }

    @Test void longProfileFieldsAndLinkListsStayWithinLimitsWithoutOneFieldPerPage()
    {
        JsonObject p = profile();
        for (String key : new String[]{"displayName", "bio", "pronouns", "currentAvatarName", "currentAvatarAuthorName"})
            p.addProperty(key, "*😀".repeat(10000));
        JsonArray links = new JsonArray();
        for (int i = 0; i < 32; i++) links.add("https://example.invalid/" + i + "/" + "a".repeat(1700));
        p.add("bioLinks", links);
        MessageEmbed[] cards = ScarletSnapshotEmbeds.user(ScarletSnapshotPrivacy.user(p, USER));
        List<String> descriptions = new ArrayList<>();
        for (MessageEmbed card : cards) { limits(card); descriptions.add(card.toData().toString()); }
        assertTrue(cards[0].getFields().size() >= 6);
        for (int i = 0; i < 32; i++)
        {
            String expected = "example.invalid/" + i + "/";
            assertTrue(descriptions.stream().anyMatch(x -> x.contains(expected)));
        }
    }
}

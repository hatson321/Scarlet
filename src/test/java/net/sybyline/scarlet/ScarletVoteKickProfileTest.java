package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.PublicProfile;
import io.github.vrchatapi.model.User;
import io.github.vrchatapi.model.UserStatus;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

/** Pure synthetic formatter coverage: no application construction, files, accounts or network. */
class ScarletVoteKickProfileTest
{
    private static final String TARGET = "usr_00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "usr_00000000-0000-0000-0000-000000000002";
    private static final String IMAGE = "https://example.invalid/profile.png";
    private static final String THUMBNAIL = "https://example.invalid/thumbnail.png";
    private static final String ICON = "https://example.invalid/icon.png";
    private static final OffsetDateTime TIME = OffsetDateTime.of(2026, 10, 28, 12, 0, 0, 0, ZoneOffset.UTC);

    private static MessageEmbed base()
    {
        return new EmbedBuilder().setTitle("Vote-to-kick initiated", "https://example.invalid/event")
            .setDescription("Vote against [Synthetic target](https://vrchat.com/home/user/" + TARGET + ")")
            .setAuthor("Synthetic initiator", "https://example.invalid/initiator", ICON)
            .setFooter("Original event metadata", ICON).setColor(0xAA1122).setTimestamp(TIME)
            .addField("Target ID", TARGET, false).build();
    }

    private static MessageEmbed render(User user, PublicProfile profile)
    {
        return ScarletVoteKickProfile.enrich(base(), TARGET, user, profile);
    }

    private static MessageEmbed.Field field(MessageEmbed embed, String name)
    {
        return embed.getFields().stream().filter(value -> name.equals(value.getName())).findFirst().orElse(null);
    }

    private static String value(MessageEmbed embed, String name)
    {
        MessageEmbed.Field field = field(embed, name);
        assertNotNull(field, "Expected field: " + name);
        return field.getValue();
    }

    private static void assertBasePreserved(MessageEmbed original, MessageEmbed enriched)
    {
        assertEquals(original.getTitle(), enriched.getTitle());
        assertEquals(original.getUrl(), enriched.getUrl());
        assertEquals(original.getDescription(), enriched.getDescription());
        assertEquals(original.getTimestamp(), enriched.getTimestamp());
        assertEquals(original.getColorRaw(), enriched.getColorRaw());
        assertEquals(original.getAuthor().getName(), enriched.getAuthor().getName());
        assertEquals(original.getAuthor().getUrl(), enriched.getAuthor().getUrl());
        assertEquals(original.getAuthor().getIconUrl(), enriched.getAuthor().getIconUrl());
        assertEquals(original.getFooter().getText(), enriched.getFooter().getText());
        assertEquals(original.getFooter().getIconUrl(), enriched.getFooter().getIconUrl());
        assertEquals(TARGET, value(enriched, "Target ID"));
        assertFalse(field(enriched, "Target ID").isInline());
    }

    @Test void allPublicFieldsAndSmallThumbnailPreserveOriginalAlertContext()
    {
        MessageEmbed original = base();
        PublicProfile profile = new PublicProfile().id(TARGET).bio("Synthetic public biography")
            .pronouns("they/them").status(UserStatus.JOIN_ME).statusDescription("Open to conversation")
            .currentAvatarImageUrl(IMAGE);
        MessageEmbed result = ScarletVoteKickProfile.enrich(original, TARGET, null, profile);

        assertNotSame(original, result);
        assertBasePreserved(original, result);
        assertEquals("Synthetic public biography", value(result, "Bio (excerpt)"));
        assertEquals("they/them", value(result, "Pronouns"));
        assertEquals("Join Me", value(result, "Status (profile snapshot)"));
        assertEquals("Open to conversation", value(result, "Status message"));
        assertFalse(field(result, "Bio (excerpt)").isInline());
        assertTrue(field(result, "Pronouns").isInline());
        assertTrue(field(result, "Status (profile snapshot)").isInline());
        assertFalse(field(result, "Status message").isInline());
        assertEquals(IMAGE, result.getThumbnail().getUrl());
        assertNull(result.getImage(), "Profile pictures must not create a large embed image");
        assertEquals(5, result.getFields().size());
    }

    @Test void enrichingDoesNotMutateTheSourceEmbedOrExistingFields()
    {
        MessageEmbed original = base();
        MessageEmbed.Field originalField = original.getFields().get(0);
        ScarletVoteKickProfile.enrich(original, TARGET, null,
            new PublicProfile().id(TARGET).bio("Added context").currentAvatarImageUrl(IMAGE));

        assertEquals(1, original.getFields().size());
        assertSame(originalField, original.getFields().get(0));
        assertEquals(TARGET, originalField.getValue());
        assertNull(original.getThumbnail());
        assertNull(original.getImage());
        assertEquals(TIME, original.getTimestamp());
        assertEquals("Original event metadata", original.getFooter().getText());
    }

    @Test void everySdkStatusHasAnExplicitHumanReadableProfileSnapshotLabel()
    {
        Map<UserStatus, String> expected = new EnumMap<>(UserStatus.class);
        expected.put(UserStatus.ACTIVE, "Online");
        expected.put(UserStatus.JOIN_ME, "Join Me");
        expected.put(UserStatus.ASK_ME, "Ask Me");
        expected.put(UserStatus.BUSY, "Do Not Disturb");
        expected.put(UserStatus.OFFLINE, "Offline");
        assertEquals(UserStatus.values().length, expected.size(), "Review labels if the SDK adds a status");
        for (UserStatus status : UserStatus.values())
        {
            MessageEmbed result = render(null, new PublicProfile().id(TARGET).status(status));
            assertEquals(expected.get(status), value(result, "Status (profile snapshot)"), status.name());
        }
    }

    @Test void publicProfileOverridesCachedUserPronounsAndStatus()
    {
        User stale = new User().id(TARGET).pronouns("old pronouns").status(UserStatus.OFFLINE)
            .statusDescription("old status message");
        PublicProfile current = new PublicProfile().id(TARGET).pronouns("new pronouns")
            .status(UserStatus.ACTIVE).statusDescription("new status message");
        MessageEmbed result = render(stale, current);
        assertEquals("new pronouns", value(result, "Pronouns"));
        assertEquals("Online", value(result, "Status (profile snapshot)"));
        assertEquals("new status message", value(result, "Status message"));
    }

    @Test void clearedPublicFieldsNeverResurrectCachedUserValues()
    {
        User stale = new User().id(TARGET).pronouns("old pronouns").status(UserStatus.ACTIVE)
            .statusDescription("old status message");
        for (String cleared : Arrays.asList(null, "", " \t\n\u00A0 "))
        {
            PublicProfile current = new PublicProfile().id(TARGET).bio(cleared).pronouns(cleared)
                .status(null).statusDescription(cleared);
            MessageEmbed result = render(stale, current);
            assertNull(field(result, "Bio (excerpt)"));
            assertNull(field(result, "Pronouns"));
            assertNull(field(result, "Status (profile snapshot)"));
            assertNull(field(result, "Status message"));
            assertEquals(1, result.getFields().size());
        }
    }

    @Test void unavailablePublicProfileFallsBackOnlyToFieldsActuallyPresentOnUser()
    {
        MessageEmbed result = render(new User().id(TARGET).pronouns("he/him").status(UserStatus.ASK_ME)
            .statusDescription("Ask before joining"), null);
        assertEquals("he/him", value(result, "Pronouns"));
        assertEquals("Ask Me", value(result, "Status (profile snapshot)"));
        assertEquals("Ask before joining", value(result, "Status message"));
        assertNull(field(result, "Bio (excerpt)"));
        assertNull(result.getThumbnail());
        assertNull(result.getImage());
    }

    @Test void absentProfilesReturnTheOriginalWithoutInventingOffline()
    {
        MessageEmbed original = base();
        assertSame(original, ScarletVoteKickProfile.enrich(original, TARGET, null, null));
        assertNull(field(original, "Status (profile snapshot)"));
    }

    @Test void missingStatusRemainsAbsentForEitherMetadataSource()
    {
        assertNull(field(render(new User().id(TARGET).status(null), null), "Status (profile snapshot)"));
        assertNull(field(render(null, new PublicProfile().id(TARGET).status(null)), "Status (profile snapshot)"));
    }

    @Test void explicitIdentityMismatchesRejectBothMetadataSources()
    {
        MessageEmbed original = base();
        assertSame(original, ScarletVoteKickProfile.enrich(original, TARGET,
            new User().id(OTHER).pronouns("wrong user"),
            new PublicProfile().id(OTHER).bio("wrong profile").currentAvatarImageUrl(IMAGE)));
    }

    @Test void mismatchedPublicProfileCannotHideAnOtherwiseValidUserFallback()
    {
        MessageEmbed result = render(new User().id(TARGET).pronouns("they/them"),
            new PublicProfile().id(OTHER).pronouns("wrong user").bio("wrong bio").currentAvatarImageUrl(IMAGE));
        assertEquals("they/them", value(result, "Pronouns"));
        assertNull(field(result, "Bio (excerpt)"));
        assertNull(result.getThumbnail());
    }

    @Test void mismatchedUserCannotContaminateAValidPublicProfile()
    {
        MessageEmbed result = render(new User().id(OTHER).pronouns("wrong user").status(UserStatus.OFFLINE),
            new PublicProfile().id(TARGET).bio("right bio").pronouns("right pronouns").status(null));
        assertEquals("right bio", value(result, "Bio (excerpt)"));
        assertEquals("right pronouns", value(result, "Pronouns"));
        assertNull(field(result, "Status (profile snapshot)"));
    }

    @Test void oldCacheEntriesWithoutAnExplicitIdRemainUsable()
    {
        assertEquals("cached pronouns", value(render(new User().pronouns("cached pronouns"), null), "Pronouns"));
        assertEquals("cached bio", value(render(null, new PublicProfile().bio("cached bio")), "Bio (excerpt)"));
    }

    @Test void invalidAndTrainingTargetsDoNotEvenReadMetadata()
    {
        User forbiddenUser = new User() {
            @Override public String getId() { throw new AssertionError("Invalid targets must not read user metadata"); }
        };
        PublicProfile forbiddenProfile = new PublicProfile() {
            @Override public String getId() { throw new AssertionError("Invalid targets must not read profile metadata"); }
        };
        MessageEmbed original = base();
        for (String invalid : Arrays.asList(null, "", "../private", TARGET + "/other", "usr_training-synthetic"))
            assertSame(original, ScarletVoteKickProfile.enrich(original, invalid, forbiddenUser, forbiddenProfile), invalid);
    }

    @Test void legacyConcreteUserIdsCanStillBeEnriched()
    {
        String legacy = "AbCd123456";
        MessageEmbed result = ScarletVoteKickProfile.enrich(base(), legacy, null,
            new PublicProfile().id(legacy).bio("legacy cached bio"));
        assertEquals("legacy cached bio", value(result, "Bio (excerpt)"));
    }

    @Test void nullBaseRemainsNullWithoutReadingMetadata()
    {
        PublicProfile forbidden = new PublicProfile() {
            @Override public String getId() { throw new AssertionError("Null alerts must not read optional metadata"); }
        };
        assertNull(ScarletVoteKickProfile.enrich(null, TARGET, null, forbidden));
    }

    @Test void imageFallbackOrderMatchesExistingSmallThumbnailPolicy()
    {
        PublicProfile profile = new PublicProfile().id(TARGET).currentAvatarImageUrl(IMAGE)
            .currentAvatarThumbnailImageUrl(THUMBNAIL).iconUrl(ICON).userIcon("https://example.invalid/user-icon.png");
        assertEquals(IMAGE, render(null, profile).getThumbnail().getUrl());
        profile.setCurrentAvatarImageUrl(null);
        assertEquals(THUMBNAIL, render(null, profile).getThumbnail().getUrl());
        profile.setCurrentAvatarThumbnailImageUrl(null);
        assertEquals(ICON, render(null, profile).getThumbnail().getUrl());
        profile.setIconUrl(null);
        assertEquals("https://example.invalid/user-icon.png", render(null, profile).getThumbnail().getUrl());
    }

    @Test void unsafeImagesFallBackWithoutDroppingSafeProfileText()
    {
        for (String unsafe : Arrays.asList("file:///private/image.png", "data:image/png;base64,abc",
            "javascript:alert(1)", "https://token@example.invalid/image.png", "https://example.invalid/image.png#fragment",
            "https://example.invalid/bad image.png", "https:///no-host", "//example.invalid/image.png",
            "https://example.invalid/" + "x".repeat(2000)))
        {
            MessageEmbed result = render(null, new PublicProfile().id(TARGET).bio("Safe public text")
                .currentAvatarImageUrl(unsafe).currentAvatarThumbnailImageUrl(THUMBNAIL));
            assertEquals(THUMBNAIL, result.getThumbnail().getUrl(), unsafe);
            assertEquals("Safe public text", value(result, "Bio (excerpt)"));
            assertNull(result.getImage());
        }
    }

    @Test void entirelyUnsafeImageMetadataProducesNoImage()
    {
        MessageEmbed result = render(null, new PublicProfile().id(TARGET).bio("Still useful")
            .currentAvatarImageUrl("file:///private/a").currentAvatarThumbnailImageUrl("data:image/png;base64,abc")
            .iconUrl("https://token@example.invalid/a").userIcon("javascript:alert(1)"));
        assertNull(result.getThumbnail());
        assertNull(result.getImage());
        assertEquals("Still useful", value(result, "Bio (excerpt)"));
    }

    @Test void eachPublicTextFieldHasItsOwnLimitAndTruncationMarker()
    {
        MessageEmbed result = render(null, new PublicProfile().id(TARGET).bio("b".repeat(300))
            .pronouns("p".repeat(100)).statusDescription("s".repeat(200)));
        assertEquals("b".repeat(240) + "\u2026", value(result, "Bio (excerpt)"));
        assertEquals("p".repeat(80) + "\u2026", value(result, "Pronouns"));
        assertEquals("s".repeat(160) + "\u2026", value(result, "Status message"));
    }

    @Test void astralUnicodeIsCountedAsCodePointsAndNeverSplit()
    {
        String emoji = "\uD83D\uDE42";
        for (int maximum : new int[]{80, 160, 240})
        {
            String result = ScarletVoteKickProfile.text(emoji.repeat(maximum + 1), maximum);
            assertEquals(emoji.repeat(maximum) + "\u2026", result);
            assertEquals(maximum + 1, result.codePointCount(0, result.length()));
            assertTrue(result.length() <= maximum * 2 + 16);
            assertWellFormedUtf16(result);
        }
    }

    @Test void whitespaceControlsAndBidiControlsAreNormalizedWithoutHiddenText()
    {
        String unsafe = " \tAlpha\u0000\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u202E"
            + "\u2066\u2067\u2068\u2069 \n\r\u00A0 Beta \t ";
        assertEquals("Alpha Beta", ScarletVoteKickProfile.text(unsafe, 240));
    }

    @Test void markdownAndMentionSyntaxAreRenderedAsText()
    {
        String unsafe = "**bold** _italics_ `code` ~~strike~~ ||spoiler|| @everyone @here <@123> <@&456> <#789>";
        String safe = ScarletVoteKickProfile.text(unsafe, 240);
        assertTrue(safe.contains("\\*"));
        assertTrue(safe.contains("\\_"));
        assertTrue(safe.contains("\\`"));
        assertFalse(safe.contains("**bold**"));
        assertFalse(safe.contains("_italics_"));
        assertFalse(safe.contains("`code`"));
        assertFalse(safe.contains("@everyone"));
        assertFalse(safe.contains("@here"));
        assertFalse(safe.contains("<"));
        assertFalse(safe.contains(">"));
        assertTrue(safe.contains("@\u200B"));
        assertTrue(safe.contains("\u2039"));
        assertTrue(safe.length() <= 496);
    }

    @Test void escapeHeavyInputStillFitsTheFieldOutputBudget()
    {
        for (int maximum : new int[]{80, 160, 240})
        {
            String safe = ScarletVoteKickProfile.text("*@_`".repeat(200), maximum);
            assertTrue(safe.length() <= maximum * 2 + 16);
            assertTrue(safe.length() <= 1024);
            assertTrue(safe.endsWith("\u2026"));
            assertWellFormedUtf16(safe);
        }
    }

    @Test void unpairedSurrogatesAreReplacedRatherThanPublished()
    {
        String result = ScarletVoteKickProfile.text("before\uD800after\uDC00", 80);
        assertEquals("before\uFFFDafter\uFFFD", result);
        assertWellFormedUtf16(result);
    }

    @Test void emptyValuesAndNonpositiveBudgetsAreOmitted()
    {
        for (String blank : Arrays.asList(null, "", " \t\n\r\u00A0", "\u0000\u202E"))
            assertNull(ScarletVoteKickProfile.text(blank, 80));
        assertNull(ScarletVoteKickProfile.text("not empty", 0));
        assertNull(ScarletVoteKickProfile.text("not empty", -1));
    }

    @Test void processingBudgetStopsBeforePathologicalWhitespaceOrControlsExposeLateContent()
    {
        assertNull(ScarletVoteKickProfile.text(" ".repeat(4096) + "must not be scanned", 80));
        assertEquals("x\u2026", ScarletVoteKickProfile.text("x" + "\u0000".repeat(4096) + "must not be scanned", 80));
    }

    @Test void truncationAtAPendingWhitespaceBoundaryStillHasAMarker()
    {
        assertEquals("ab\u2026", ScarletVoteKickProfile.text("ab c", 3));
        assertEquals("abc", ScarletVoteKickProfile.text("abc", 3));
        assertEquals("ab", ScarletVoteKickProfile.text("ab ", 3));
    }

    @Test void corruptMetadataReturnsTheOriginalAndDoesNotInspectPrivateExceptionText()
    {
        RuntimeException privateFailure = new RuntimeException() {
            private static final long serialVersionUID = 1L;
            @Override public String getMessage() { throw new AssertionError("Do not read arbitrary metadata failure text"); }
            @Override public String toString() { throw new AssertionError("Do not render arbitrary metadata failures"); }
        };
        MessageEmbed original = base();
        User brokenUser = new User() {
            @Override public UserStatus getStatus() { throw privateFailure; }
        };
        PublicProfile brokenProfile = new PublicProfile() {
            @Override public String getBio() { throw privateFailure; }
        };
        brokenProfile.setCurrentAvatarImageUrl(IMAGE);
        assertSame(original, ScarletVoteKickProfile.enrich(original, TARGET, brokenUser, null));
        assertSame(original, ScarletVoteKickProfile.enrich(original, TARGET, null, brokenProfile));
        assertNull(original.getThumbnail());
        assertEquals(1, original.getFields().size());
    }

    @Test void corruptIdentityMetadataAlsoReturnsTheOriginal()
    {
        MessageEmbed original = base();
        PublicProfile broken = new PublicProfile() {
            @Override public String getId() { throw new IllegalStateException("Synthetic identity getter failure"); }
        };
        assertSame(original, ScarletVoteKickProfile.enrich(original, TARGET, null, broken));
    }

    @Test void insufficientDiscordEmbedBudgetFallsBackToTheOriginalAlert()
    {
        MessageEmbed full = new EmbedBuilder().setDescription("d".repeat(4096))
            .addField("One", "a".repeat(900), false).addField("Two", "b".repeat(900), false).build();
        assertSame(full, ScarletVoteKickProfile.enrich(full, TARGET, null,
            new PublicProfile().id(TARGET).bio("x".repeat(240))));
        assertEquals(2, full.getFields().size());
    }

    private static void assertWellFormedUtf16(String text)
    {
        for (int index = 0; index < text.length(); index++)
        {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value))
            {
                assertTrue(index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1)));
                index++;
            }
            else assertFalse(Character.isLowSurrogate(value));
        }
    }
}

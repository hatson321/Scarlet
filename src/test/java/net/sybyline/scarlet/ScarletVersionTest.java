package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.sybyline.scarlet.util.MiscUtils;

class ScarletVersionTest
{
    private static String read(String file) throws Exception
    {
        return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
    }

    private static String buildVersion() throws Exception
    {
        Matcher version = Pattern.compile("<version>([^<]+)</version>\\s*<!--scarlet-version-->")
            .matcher(read("pom.xml"));
        assertTrue(version.find(), "The build must expose its Scarlet version marker");
        return version.group(1);
    }

    @Test void runtimeVersionAndOptionalManifestMatchBuildVersion() throws Exception
    {
        // Maven exercises loose classes; the release-candidate runner exercises the shaded JAR.
        String manifestVersion = Scarlet.class.getPackage().getImplementationVersion();
        if (Scarlet.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"))
            assertEquals(buildVersion(), manifestVersion);
        else
            assertNull(manifestVersion);
        assertEquals(buildVersion(), Scarlet.VERSION);
    }

    @Test void launcherMatchesBuildAndPublishedFeedDoesNotAdvertiseALocalPreview() throws Exception
    {
        String version = buildVersion();
        JsonObject metadata = JsonParser.parseString(read("meta.json")).getAsJsonObject();
        String release = metadata.get("latest_release").getAsString();
        String publishedBuild = metadata.get("latest_build").getAsString();
        assertTrue(MiscUtils.isValidVersion(release));
        assertTrue(MiscUtils.isValidVersion(publishedBuild));
        assertFalse(MiscUtils.isPreviewVersion(release));
        assertTrue(MiscUtils.compareScarletVersion(publishedBuild, release) >= 0);
        if (MiscUtils.isPreviewVersion(version))
            assertTrue(MiscUtils.compareScarletVersion(version, publishedBuild) >= 0,
                "Local previews may be newer than the published update feed");
        else
        {
            assertEquals(version, release);
            assertEquals(version, publishedBuild);
        }
        assertEquals(version, read("scarlet.version").trim());
    }

    @Test void minorReleaseOrdersAfterPreviousReleaseAndIterations()
    {
        for (String previous : new String[] {"0.4.19", "0.4.20", "0.4.20-b1", "0.4.999"})
        {
            assertTrue(MiscUtils.compareScarletVersion("0.5.0", previous) > 0);
            assertTrue(MiscUtils.compareScarletVersion(previous, "0.5.0") < 0);
        }
        assertEquals(0, MiscUtils.compareScarletVersion("0.5.0", "0.5.0"));
        assertTrue(MiscUtils.compareScarletVersion("0.5.1", "0.5.0") > 0);
    }

    @Test void nextBuildOrdersAfterThePreviousDeliveredBuild()
    {
        assertTrue(MiscUtils.isValidVersion("0.5.0-a6"));
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a6", "0.5.0-a5") > 0);
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a5", "0.5.0-a6") < 0);
        assertTrue(MiscUtils.isValidVersion("0.5.0-a7"));
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a7", "0.5.0-a6") > 0);
        assertTrue(MiscUtils.isValidVersion("0.5.0-a8"));
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a8", "0.5.0-a7") > 0);
        assertTrue(MiscUtils.isValidVersion("0.5.0-a9"));
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a9", "0.5.0-a8") > 0);
        assertTrue(MiscUtils.isValidVersion("0.5.0-a10"));
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a10", "0.5.0-a9") > 0);
        assertTrue(MiscUtils.isValidVersion("0.5.0-a11"));
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a11", "0.5.0-a10") > 0);
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a12", "0.5.0-a11") > 0);
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a13", "0.5.0-a12") > 0);
        assertTrue(MiscUtils.isValidVersion("0.6.0"));
        assertFalse(MiscUtils.isPreviewVersion("0.6.0"));
        assertTrue(MiscUtils.compareScarletVersion("0.6.0", "0.5.0-a13") > 0);
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a13", "0.6.0") < 0);
        assertTrue(MiscUtils.isValidVersion("0.6.1-a1"));
        assertTrue(MiscUtils.isPreviewVersion("0.6.1-a1"));
        assertTrue(MiscUtils.compareScarletVersion("0.6.1-a1", "0.6.0") > 0);
        assertTrue(MiscUtils.isValidVersion("0.6.1-a2"));
        assertTrue(MiscUtils.isPreviewVersion("0.6.1-a2"));
        assertTrue(MiscUtils.compareScarletVersion("0.6.1-a2", "0.6.1-a1") > 0);
        assertTrue(MiscUtils.isValidVersion("0.6.1-a3"));
        assertTrue(MiscUtils.isPreviewVersion("0.6.1-a3"));
        assertTrue(MiscUtils.compareScarletVersion("0.6.1-a3", "0.6.1-a2") > 0);
        assertTrue(MiscUtils.isValidVersion("0.6.1-a4"));
        assertTrue(MiscUtils.isPreviewVersion("0.6.1-a4"));
        assertTrue(MiscUtils.compareScarletVersion("0.6.1-a4", "0.6.1-a3") > 0);
        assertTrue(MiscUtils.isValidVersion("0.6.1-a5"));
        assertTrue(MiscUtils.isPreviewVersion("0.6.1-a5"));
        assertTrue(MiscUtils.compareScarletVersion("0.6.1-a5", "0.6.1-a4") > 0);
        assertTrue(MiscUtils.isValidVersion("0.6.1-a6"));
        assertTrue(MiscUtils.isPreviewVersion("0.6.1-a6"));
        assertTrue(MiscUtils.compareScarletVersion("0.6.1-a6", "0.6.1-a5") > 0);
        assertTrue(MiscUtils.isValidVersion("0.6.1-a7"));
        assertTrue(MiscUtils.isPreviewVersion("0.6.1-a7"));
        assertTrue(MiscUtils.compareScarletVersion("0.6.1-a7", "0.6.1-a6") > 0);
        assertTrue(MiscUtils.isValidVersion("0.6.1-a8"));
        assertTrue(MiscUtils.isPreviewVersion("0.6.1-a8"));
        assertTrue(MiscUtils.compareScarletVersion("0.6.1-a8", "0.6.1-a7") > 0);
        assertEquals("0.6.1-a9", Scarlet.VERSION);
    }

    @Test void publicAlphaCandidateOrdersAfterDeliveredAlphasAndBeforeStable() throws Exception
    {
        String candidate = "0.6.1-a9";
        assertTrue(MiscUtils.isValidVersion(candidate));
        assertTrue(MiscUtils.isPreviewVersion(candidate));
        assertTrue(MiscUtils.compareScarletVersion(candidate, "0.6.0") > 0);
        for (int iteration = 1; iteration <= 8; iteration++)
        {
            String previous = "0.6.1-a" + iteration;
            assertTrue(MiscUtils.compareScarletVersion(candidate, previous) > 0);
            assertTrue(MiscUtils.compareScarletVersion(previous, candidate) < 0);
        }
        assertTrue(MiscUtils.compareScarletVersion(candidate, "0.6.1-a10") < 0);
        assertTrue(MiscUtils.compareScarletVersion(candidate, "0.6.1") < 0);
        assertTrue(MiscUtils.compareScarletVersion("0.6.1", candidate) > 0);
        Matcher accepted = Pattern.compile("-cnotmatch '([^']+)'")
            .matcher(read("scripts/windows/build-and-verify.ps1"));
        assertTrue(accepted.find());
        assertTrue(candidate.matches(accepted.group(1)), "Windows packaging must accept lowercase a9");
    }

    @Test void testBuildsSortAfterAlphaBuildsAndBeforeTheStableRelease()
    {
        String[] ordered = {"0.6.0", "0.6.1-a8", "0.6.1-T1", "0.6.1-T2", "0.6.1-T9", "0.6.1-T10", "0.6.1", "0.6.2-T1"};
        for (int i = 0; i < ordered.length; i++)
        {
            assertTrue(MiscUtils.isValidVersion(ordered[i]));
            for (int j = i + 1; j < ordered.length; j++)
            {
                assertTrue(MiscUtils.compareScarletVersion(ordered[i], ordered[j]) < 0);
                assertTrue(MiscUtils.compareScarletVersion(ordered[j], ordered[i]) > 0);
            }
        }
        assertTrue(MiscUtils.isPreviewVersion("0.6.1-T1"));
        assertFalse(MiscUtils.isPreviewVersion("0.6.1"));
    }

    @Test void iterationNumbersOrderNumericallyAcrossDigitBoundaries()
    {
        for (String[] pair : new String[][] {
            {"a9", "a10"}, {"a99", "a100"}, {"a100", "a101"},
            {"alpha9", "alpha10"}, {"b9", "b10"},
            {"a999999999999999999999", "a1000000000000000000000"}})
        {
            String older = "0.5.0-" + pair[0], newer = "0.5.0-" + pair[1];
            assertTrue(MiscUtils.compareScarletVersion(older, newer) < 0, older + " < " + newer);
            assertTrue(MiscUtils.compareScarletVersion(newer, older) > 0, newer + " > " + older);
            assertEquals(0, MiscUtils.compareScarletVersion(newer, newer));
        }
        assertEquals(0, MiscUtils.compareScarletVersion("0.5.0-a09", "0.5.0-a9"));
    }

    @Test void iterationComparisonRetainsScarletReleaseAndLabelConventions()
    {
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a10", "0.5.0") > 0);
        assertTrue(MiscUtils.compareScarletVersion("0.5.1", "0.5.0-a999") > 0);
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-b1", "0.5.0-a999") > 0);
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a.10", "0.5.0-a.9") > 0);
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a0", "0.5.0-a") > 0);
    }

    @Test void mixedSuffixLabelsKeepAnAntisymmetricTransitiveOrder()
    {
        String[] labels = {"", "-a", "-a0", "-a00", "-a1", "-a1x", "-a2", "-a9", "-a10", "-a99", "-a100", "-alpha10", "-b1"};
        for (String left : labels)
            for (String middle : labels)
            {
                int lm = MiscUtils.compareScarletVersion("0.5.0" + left, "0.5.0" + middle);
                assertEquals(Integer.signum(lm), -Integer.signum(MiscUtils.compareScarletVersion("0.5.0" + middle, "0.5.0" + left)));
                for (String right : labels)
                    if (lm <= 0 && MiscUtils.compareScarletVersion("0.5.0" + middle, "0.5.0" + right) <= 0)
                        assertTrue(MiscUtils.compareScarletVersion("0.5.0" + left, "0.5.0" + right) <= 0,
                            "Non-transitive version labels: " + left + ", " + middle + ", " + right);
            }
    }

    @Test void releaseAnnouncementMatchesThePublishedReleaseAndReplacesTheObsoleteNotice() throws Exception
    {
        ScarletMeta metadata = Scarlet.GSON_PRETTY.fromJson(read("meta.json"), ScarletMeta.class);
        ScarletAnnouncement announcement = metadata.announcement;
        assertNotNull(announcement);
        assertNotNull(announcement.id);
        assertFalse(announcement.id.trim().isEmpty());
        assertNotEquals("20260926-000000-0420-tagging", announcement.id);
        // The feed also supports non-release advisories; do not force those to name a version.
        if (announcement.id.startsWith("release-"))
        {
            String version = metadata.latest_release;
            assertTrue(announcement.id.startsWith("release-" + version + "-"));
            assertTrue(announcement.title.startsWith("Scarlet " + version + " "));
            assertTrue(announcement.message.startsWith("Scarlet " + version + " "));
            assertEquals("info", announcement.severity);
        }
    }

    @Test void announcementIsReadableWithoutObsoletePermissionWorkarounds() throws Exception
    {
        ScarletAnnouncement announcement = Scarlet.GSON_PRETTY.fromJson(read("meta.json"), ScarletMeta.class).announcement;
        assertNotNull(announcement);
        assertNotNull(announcement.title);
        assertFalse(announcement.title.trim().isEmpty());
        assertNotNull(announcement.message);
        assertFalse(announcement.message.trim().isEmpty());
        assertFalse(announcement.message.contains("Can't update yet?"));
        assertFalse(announcement.message.contains("/scarlet-discord-permissions set target:@Moderators"));
        // Plain-text JOptionPane messages need short lines to avoid a very wide popup.
        for (String line : announcement.message.split("\\R"))
            assertTrue(line.length() <= 90, "Announcement lines must remain readable on smaller screens");
        if (announcement.expires != null && !announcement.expires.trim().isEmpty())
            assertDoesNotThrow(() -> OffsetDateTime.parse(announcement.expires));
    }
}

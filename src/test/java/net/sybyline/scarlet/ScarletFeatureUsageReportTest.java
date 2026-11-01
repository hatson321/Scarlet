package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.sybyline.scarlet.ScarletFeatureUsageReport.FeatureStatus;
import net.sybyline.scarlet.ScarletFeatureUsageReport.RawConfiguration;
import net.sybyline.scarlet.ScarletFeatureUsageReport.Snapshot;
import net.sybyline.scarlet.util.UniqueStrings;

class ScarletFeatureUsageReportTest
{
    private static final String[] BASE_FEATURE_NAMES = {
        "Discord action logging",
        "Discord member-join logging",
        "Discord account-age alerts",
        "Operations and health alerts",
        "Training-mode Discord output",
        "Discord voice announcements",
        "Ticket Tool auto-response",
        "Verification-complete announcements",
        "Automatic group invite after verification",
        "Discord moderation warnings",
        "Discord moderation kicks",
        "Discord moderation bans",
        "Timed bans",
        "Evidence submission",
        "Calendar and scheduled Discord events",
        "Avatar search commands",
        "Watched-avatar commands",
        "Animated emoji commands",
        "VRChat report tools",
        "Template footer on Discord messages",
        "Bundle instance kicks with user bans",
        "Moderation summary: staff with activity only",
        "Ping on instance warning",
        "Ping on instance kick",
        "Ping on member removal",
        "Ping on user ban",
        "Ping on user unban",
        "Ping on outstanding instance warning",
        "Ping on outstanding instance kick",
        "Ping on outstanding member removal",
        "Ping on outstanding user ban",
        "Ping on outstanding user unban"
    };

    private static final GroupAuditType[] MODERATION_TYPES = {
        GroupAuditType.INSTANCE_WARN,
        GroupAuditType.INSTANCE_KICK,
        GroupAuditType.MEMBER_REMOVE,
        GroupAuditType.USER_BAN,
        GroupAuditType.USER_UNBAN
    };

    private static final String[] DIRECT_PING_NAMES = {
        "Ping on instance warning",
        "Ping on instance kick",
        "Ping on member removal",
        "Ping on user ban",
        "Ping on user unban"
    };

    @Test
    void reportsExactInventoryAndEveryResolvedRouteFamily()
    {
        RawConfiguration spec = availableConfiguration();
        spec.auxiliaryWebhooksFeatureEnabled = true;

        int index = 0;
        for (GroupAuditType type : GroupAuditType.values())
        {
            String normal = "private-normal-" + index;
            String secret = "private-secret-" + index;
            String alias = "private-alias-" + index;
            spec.auditType2channelSf.put(type.id, normal);
            spec.auditType2secretChannelSf.put(type.id, secret);
            spec.routableTextChannels.add(normal);
            spec.routableTextChannels.add(secret);
            spec.auditType2scarletAuxWh.put(type.id, new UniqueStrings(alias));
            spec.scarletAuxWh2webhookUrl.put(alias, "https://private.invalid/webhook/" + index);
            spec.usableAuxWebhookAliases.add(alias);
            index++;
        }
        index = 0;
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
        {
            String normal = "private-ex-normal-" + index;
            String secret = "private-ex-secret-" + index;
            spec.auditExType2channelSf.put(type.id, normal);
            spec.auditExType2secretChannelSf.put(type.id, secret);
            spec.routableTextChannels.add(normal);
            spec.routableTextChannels.add(secret);
            index++;
        }

        List<FeatureStatus> statuses = statuses(spec);
        Map<String, Boolean> byName = statusMap(statuses);
        Set<String> expectedNames = expectedInventory();
        Set<String> actualNames = statuses.stream()
            .map(status -> status.name)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(expectedNames, actualNames, "The report must expose the exact public feature inventory");
        assertEquals(expectedNames.size(), statuses.size(), "Every feature name must be unique");
        for (GroupAuditType type : GroupAuditType.values())
        {
            assertTrue(byName.get("Discord audit — " + type.title), type.id);
            assertTrue(byName.get("Secret-staff audit — " + type.title), type.id);
            assertTrue(byName.get("Auxiliary webhook — " + type.title), type.id);
        }
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
        {
            assertTrue(byName.get("Discord extended audit — " + type.title), type.id);
            assertEquals(type.supportsSecretRouting(), byName.get("Secret-staff extended audit — " + type.title), type.id);
        }
    }

    @Test
    void configuredButUnresolvedRoutesAndUnusableWebhooksRemainDisabled()
    {
        RawConfiguration spec = availableConfiguration();
        spec.auxiliaryWebhooksFeatureEnabled = true;

        int index = 0;
        for (GroupAuditType type : GroupAuditType.values())
        {
            String alias = "private-unusable-alias-" + index;
            spec.auditType2channelSf.put(type.id, "private-unresolved-normal-" + index);
            spec.auditType2secretChannelSf.put(type.id, "private-unresolved-secret-" + index);
            spec.auditType2scarletAuxWh.put(type.id, new UniqueStrings(alias));
            if ((index & 1) == 0)
                spec.scarletAuxWh2webhookUrl.put(alias, "https://private.invalid/not-a-client/" + index);
            else
                spec.usableAuxWebhookAliases.add(alias);
            index++;
        }
        index = 0;
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
        {
            spec.auditExType2channelSf.put(type.id, "private-unresolved-ex-normal-" + index);
            spec.auditExType2secretChannelSf.put(type.id, "private-unresolved-ex-secret-" + index);
            index++;
        }

        Map<String, Boolean> byName = statusMap(statuses(spec));
        for (GroupAuditType type : GroupAuditType.values())
        {
            assertFalse(byName.get("Discord audit — " + type.title), type.id);
            assertFalse(byName.get("Secret-staff audit — " + type.title), type.id);
            assertFalse(byName.get("Auxiliary webhook — " + type.title),
                "Both a configured URL and a usable webhook client are required for " + type.id);
        }
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
        {
            assertFalse(byName.get("Discord extended audit — " + type.title), type.id);
            assertFalse(byName.get("Secret-staff extended audit — " + type.title), type.id);
        }
    }

    @Test
    void sharedLivePlayerListChannelDoesNotClaimAnUnfilteredSecretView()
    {
        RawConfiguration spec = availableConfiguration();
        spec.auditExType2channelSf.put(GroupAuditTypeEx.LIVE_PLAYERLIST.id, "same-channel");
        spec.auditExType2secretChannelSf.put(GroupAuditTypeEx.LIVE_PLAYERLIST.id, "same-channel");
        spec.routableTextChannels.add("same-channel");
        Map<String, Boolean> byName = statusMap(statuses(spec));
        assertTrue(byName.get("Discord extended audit — Live Player List"));
        assertFalse(byName.get("Secret-staff extended audit — Live Player List"));
    }

    @Test
    void resolvedDiscordAvailabilityIsAGlobalPrerequisite()
    {
        RawConfiguration spec = switchedOnConfiguration();
        spec.discordAvailable = false;

        assertTrue(statuses(spec).stream().noneMatch(status -> status.enabled),
            "No row can be enabled without a live JDA and resolved primary guild");
    }

    @Test
    void baseOutputRoutesMustResolveAndVoiceMustTargetDiscord()
    {
        RawConfiguration spec = availableConfiguration();
        spec.discordActionLogChannelSf = "private-action";
        spec.discordAccountAgeAlertMinutes = 60;
        spec.opsAlertChannelSf = "private-ops";
        spec.trainingChannelSf = "private-training";
        spec.trainingMode = true;
        spec.audioChannelSf = "private-voice";
        spec.ttsUsesDefaultAudioDevice = true;

        assertFalse(enabled(spec, "Discord action logging"));
        assertFalse(enabled(spec, "Discord member-join logging"));
        assertFalse(enabled(spec, "Discord account-age alerts"));
        assertFalse(enabled(spec, "Operations and health alerts"));
        assertFalse(enabled(spec, "Training-mode Discord output"));
        assertFalse(enabled(spec, "Discord voice announcements"));

        spec.routableTextChannels.add(spec.discordActionLogChannelSf);
        spec.routableTextChannels.add(spec.opsAlertChannelSf);
        spec.routableTextChannels.add(spec.trainingChannelSf);
        spec.routableVoiceChannels.add(spec.audioChannelSf);

        assertTrue(enabled(spec, "Discord action logging"));
        assertTrue(enabled(spec, "Discord member-join logging"));
        assertTrue(enabled(spec, "Discord account-age alerts"));
        assertTrue(enabled(spec, "Operations and health alerts"));
        assertTrue(enabled(spec, "Training-mode Discord output"));
        assertFalse(enabled(spec, "Discord voice announcements"),
            "Local/default-device TTS cannot produce Discord output");

        spec.ttsUsesDefaultAudioDevice = false;
        assertTrue(enabled(spec, "Discord voice announcements"));

        spec.discordAccountAgeAlertMinutes = 0;
        spec.trainingMode = false;
        assertFalse(enabled(spec, "Discord account-age alerts"));
        assertFalse(enabled(spec, "Training-mode Discord output"));
    }

    @Test
    void ticketAutoResponseRequiresToggleAndUsableCategoryOrCompiledPattern()
    {
        RawConfiguration spec = availableConfiguration();
        spec.ticketToolAutoResponseEnabled = true;
        spec.ticketToolCategorySf = "private-category";
        spec.ticketToolChannelNameRegex = "[";

        assertFalse(enabled(spec, "Ticket Tool auto-response"));

        spec.routableCategories.add(spec.ticketToolCategorySf);
        assertTrue(enabled(spec, "Ticket Tool auto-response"));

        spec.routableCategories.clear();
        spec.ticketToolChannelNameRegex = "ticket-[0-9]+";
        assertTrue(enabled(spec, "Ticket Tool auto-response"));

        spec.ticketToolAutoResponseEnabled = false;
        assertFalse(enabled(spec, "Ticket Tool auto-response"));
    }

    @Test
    void autoInviteAndVerificationAnnouncementUseAllRuntimePrerequisites()
    {
        RawConfiguration spec = availableConfiguration();
        spec.autoInviteOnVerificationEnabled = true;
        spec.verifiedRoleSf = "private-verified-role";
        spec.routableRoles.add(spec.verifiedRoleSf);
        spec.autoInviteGroupId = Scarlet.DEFAULT_AUTO_INVITE_GROUP_ID;

        assertFalse(enabled(spec, "Automatic group invite after verification"));

        spec.fallbackAutoInviteGroupId = "grp_private-fallback";
        assertTrue(enabled(spec, "Automatic group invite after verification"),
            "The runtime group fallback is an effective configured route");

        spec.routableRoles.clear();
        assertFalse(enabled(spec, "Automatic group invite after verification"));
        spec.routableRoles.add(spec.verifiedRoleSf);
        spec.autoInviteGroupId = "grp_private-explicit";
        spec.fallbackAutoInviteGroupId = null;
        assertTrue(enabled(spec, "Automatic group invite after verification"));

        spec.announceVerificationCompleteEnabled = true;
        assertFalse(enabled(spec, "Verification-complete announcements"),
            "An announcement still needs a Discord destination");

        spec.discordActionLogChannelSf = "private-action";
        spec.routableTextChannels.add(spec.discordActionLogChannelSf);
        assertTrue(enabled(spec, "Verification-complete announcements"));

        spec.routableTextChannels.clear();
        spec.ticketToolAutoResponseEnabled = true;
        spec.ticketToolChannelNameRegex = "ticket-.+";
        assertTrue(enabled(spec, "Verification-complete announcements"),
            "A usable Ticket Tool route is an alternative announcement destination");

        spec.autoInviteOnVerificationEnabled = false;
        assertFalse(enabled(spec, "Verification-complete announcements"));
    }

    @Test
    void moderationDependentFeaturesRequireOneOfTheFiveRoutedEvents()
    {
        RawConfiguration noRoute = moderationConfiguration();
        assertFalse(enabled(noRoute, "Timed bans"));
        assertFalse(enabled(noRoute, "Evidence submission"));
        assertFalse(enabled(noRoute, "Template footer on Discord messages"));
        for (String pingName : DIRECT_PING_NAMES)
            assertFalse(enabled(noRoute, pingName));

        addNormalRoute(noRoute, GroupAuditType.INSTANCE_CREATE);
        assertFalse(enabled(noRoute, "Timed bans"), "A non-moderation audit route is not sufficient");

        for (int routeIndex = 0; routeIndex < MODERATION_TYPES.length; routeIndex++)
        {
            RawConfiguration oneRoute = moderationConfiguration();
            addNormalRoute(oneRoute, MODERATION_TYPES[routeIndex]);

            assertTrue(enabled(oneRoute, "Timed bans"), MODERATION_TYPES[routeIndex].id);
            assertTrue(enabled(oneRoute, "Evidence submission"), MODERATION_TYPES[routeIndex].id);
            assertTrue(enabled(oneRoute, "Template footer on Discord messages"), MODERATION_TYPES[routeIndex].id);
            for (int pingIndex = 0; pingIndex < DIRECT_PING_NAMES.length; pingIndex++)
            {
                assertEquals(routeIndex == pingIndex, enabled(oneRoute, DIRECT_PING_NAMES[pingIndex]),
                    DIRECT_PING_NAMES[pingIndex] + " must follow only its corresponding route");
            }
        }

        RawConfiguration evidence = moderationConfiguration();
        addSecretRoute(evidence, GroupAuditType.USER_BAN);
        assertTrue(enabled(evidence, "Timed bans"));
        assertTrue(enabled(evidence, "Evidence submission"));
        assertTrue(enabled(evidence, "Ping on user ban"));
        evidence.evidenceRoot = null;
        assertFalse(enabled(evidence, "Evidence submission"));
        evidence.evidenceRoot = "private-evidence-root";
        evidence.evidenceFeatureEnabled = false;
        assertFalse(enabled(evidence, "Evidence submission"));
        evidence.evidenceFeatureEnabled = true;
        evidence.evidenceEnabled = false;
        assertFalse(enabled(evidence, "Evidence submission"));

        RawConfiguration footer = moderationConfiguration();
        addNormalRoute(footer, GroupAuditType.INSTANCE_WARN);
        footer.vrchatReportsFeatureEnabled = false;
        assertFalse(enabled(footer, "Template footer on Discord messages"));
        footer.vrchatReportsFeatureEnabled = true;
        footer.appendTemplateFooterEnabled = false;
        assertFalse(enabled(footer, "Template footer on Discord messages"));
    }

    @Test
    void bundledBanAndKickMustShareTheSameRouteFamily()
    {
        RawConfiguration crossFamily = moderationConfiguration();
        crossFamily.bundleInstanceKicksEnabled = true;
        addNormalRoute(crossFamily, GroupAuditType.USER_BAN);
        addSecretRoute(crossFamily, GroupAuditType.INSTANCE_KICK);
        assertFalse(enabled(crossFamily, "Bundle instance kicks with user bans"));

        addNormalRoute(crossFamily, GroupAuditType.INSTANCE_KICK);
        assertTrue(enabled(crossFamily, "Bundle instance kicks with user bans"));

        RawConfiguration secretFamily = moderationConfiguration();
        secretFamily.bundleInstanceKicksEnabled = true;
        addSecretRoute(secretFamily, GroupAuditType.USER_BAN);
        addSecretRoute(secretFamily, GroupAuditType.INSTANCE_KICK);
        assertTrue(enabled(secretFamily, "Bundle instance kicks with user bans"));
    }

    @Test
    void scheduledEventsRequireFeatureActiveMirrorAndResolvedTarget()
    {
        for (int mask = 0; mask < 8; mask++)
        {
            RawConfiguration spec = availableConfiguration();
            spec.calendarFeatureEnabled = (mask & 1) != 0;
            spec.activeMirroredSchedule = (mask & 2) != 0;
            spec.scheduleTargetGuildAvailable = (mask & 4) != 0;
            assertEquals(mask == 7, enabled(spec, "Calendar and scheduled Discord events"),
                "calendar dependency mask " + mask);
        }
    }

    @Test
    void commandAndSelectorRowsNeedOnlyAvailableDiscordAndTheirOwnGate()
    {
        RawConfiguration spec = availableConfiguration();
        spec.discordModerationFeatureEnabled = true;
        spec.discordModerationCommandsEnabled = true;
        spec.avatarSearchFeatureEnabled = true;
        spec.watchedAvatarsFeatureEnabled = true;
        spec.animatedEmojiFeatureEnabled = true;
        spec.vrchatReportsFeatureEnabled = true;
        spec.moderationSummaryOnlyActivityEnabled = true;
        spec.pingOutstandingInstanceWarnEnabled = true;
        spec.pingOutstandingInstanceKickEnabled = true;
        spec.pingOutstandingMemberRemoveEnabled = true;
        spec.pingOutstandingUserBanEnabled = true;
        spec.pingOutstandingUserUnbanEnabled = true;

        assertTrue(enabled(spec, "Discord moderation warnings"));
        assertTrue(enabled(spec, "Discord moderation kicks"));
        assertTrue(enabled(spec, "Discord moderation bans"));
        assertTrue(enabled(spec, "Avatar search commands"));
        assertTrue(enabled(spec, "Watched-avatar commands"));
        assertTrue(enabled(spec, "Animated emoji commands"));
        assertTrue(enabled(spec, "VRChat report tools"));
        assertTrue(enabled(spec, "Moderation summary: staff with activity only"));
        assertTrue(enabled(spec, "Ping on outstanding instance warning"));
        assertTrue(enabled(spec, "Ping on outstanding instance kick"));
        assertTrue(enabled(spec, "Ping on outstanding member removal"));
        assertTrue(enabled(spec, "Ping on outstanding user ban"));
        assertTrue(enabled(spec, "Ping on outstanding user unban"));
        assertFalse(enabled(spec, "Template footer on Discord messages"),
            "A routed moderation message is still required for its footer");
    }

    @Test
    void rawPrivateConfigurationCannotCrossSnapshotOrReachRenderedReport() throws Exception
    {
        RawConfiguration spec = switchedOnConfiguration();
        Map<String, String> privateValues = new HashMap<>();
        privateValues.put("token", "PRIVATE_TOKEN_4b0d09f3");
        privateValues.put("guild", "PRIVATE_GUILD_902104");
        privateValues.put("channel", "PRIVATE_CHANNEL_438291");
        privateValues.put("voice", "PRIVATE_VOICE_923810");
        privateValues.put("ops", "PRIVATE_OPS_328100");
        privateValues.put("training", "PRIVATE_TRAINING_582930");
        privateValues.put("path", "PRIVATE_PATH_evidence_root");
        privateValues.put("category", "PRIVATE_CATEGORY_192830");
        privateValues.put("regex", "PRIVATE_REGEX_[0-9]+");
        privateValues.put("role", "PRIVATE_ROLE_682910");
        privateValues.put("group", "PRIVATE_GROUP_220184");
        privateValues.put("message", "PRIVATE_MESSAGE_551029");
        privateValues.put("alias", "PRIVATE_ALIAS_109283");
        privateValues.put("webhook", "https://private.invalid/PRIVATE_WEBHOOK_440192");

        spec.token = privateValues.get("token");
        spec.guildSf = privateValues.get("guild");
        spec.audioChannelSf = privateValues.get("voice");
        spec.discordActionLogChannelSf = privateValues.get("channel");
        spec.opsAlertChannelSf = privateValues.get("ops");
        spec.trainingChannelSf = privateValues.get("training");
        spec.evidenceRoot = privateValues.get("path");
        spec.ticketToolCategorySf = privateValues.get("category");
        spec.ticketToolChannelNameRegex = privateValues.get("regex");
        spec.ticketToolNotifyRoleSf = privateValues.get("role");
        spec.ticketToolAgeVerificationMessage = privateValues.get("message");
        spec.verifiedRoleSf = privateValues.get("role");
        spec.autoInviteGroupId = privateValues.get("group");
        spec.routableTextChannels.add(privateValues.get("channel"));
        spec.routableTextChannels.add(privateValues.get("ops"));
        spec.routableTextChannels.add(privateValues.get("training"));
        spec.routableVoiceChannels.add(privateValues.get("voice"));
        spec.routableCategories.add(privateValues.get("category"));
        spec.routableRoles.add(privateValues.get("role"));
        spec.scarletPermission2roleSf.put("private-permission", new UniqueStrings(privateValues.get("role")));
        spec.scarletAuxWh2webhookUrl.put(privateValues.get("alias"), privateValues.get("webhook"));
        spec.usableAuxWebhookAliases.add(privateValues.get("alias"));
        spec.auditType2scarletAuxWh.put(GroupAuditType.INSTANCE_KICK.id,
            new UniqueStrings(privateValues.get("alias")));
        spec.auditType2channelSf.put(GroupAuditType.INSTANCE_KICK.id, privateValues.get("channel"));
        spec.auditType2secretChannelSf.put(GroupAuditType.INSTANCE_KICK.id, privateValues.get("channel"));
        spec.auditExType2channelSf.put(GroupAuditTypeEx.USER_AVATAR.id, privateValues.get("channel"));
        spec.auditExType2secretChannelSf.put(GroupAuditTypeEx.USER_AVATAR.id, privateValues.get("channel"));

        Snapshot snapshot = ScarletFeatureUsageReport.captureForTesting(spec);
        Set<String> allowedRouteKeys = new HashSet<>();
        for (GroupAuditType type : GroupAuditType.values())
            allowedRouteKeys.add(type.id);
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
            allowedRouteKeys.add(type.id);
        for (Field field : Snapshot.class.getDeclaredFields())
        {
            assertTrue(field.getType() == boolean.class || Set.class.isAssignableFrom(field.getType()),
                "Snapshot gained a non-boolean/private-data-capable field: " + field.getName());
            if (Set.class.isAssignableFrom(field.getType()))
            {
                @SuppressWarnings("unchecked")
                Set<String> values = (Set<String>)field.get(snapshot);
                assertTrue(allowedRouteKeys.containsAll(values),
                    "Snapshot route sets may contain only public event IDs");
            }
        }

        String html = ScarletFeatureUsageReport.renderHtml(snapshot);
        for (String privateValue : privateValues.values())
            assertFalse(html.contains(privateValue), "Report leaked a private configuration value");
        assertFalse(html.contains("https://"));
        assertFalse(html.contains("http://"));
        assertFalse(html.toLowerCase().contains("<script"));
        assertFalse(html.toLowerCase().contains("url("));
    }

    @Test
    void everyFeatureRowContainsExactlyNameAndBinaryStatus()
    {
        String html = ScarletFeatureUsageReport.renderHtml(
            ScarletFeatureUsageReport.captureForTesting(new RawConfiguration()));
        Matcher rowMatcher = Pattern.compile(
            "<tr><td>(.*?)</td><td class=\"status (?:enabled|disabled)\">(Enabled|Disabled)</td></tr>")
            .matcher(html);
        int rows = 0;
        while (rowMatcher.find())
        {
            rows++;
            assertFalse(rowMatcher.group(1).trim().isEmpty());
            assertTrue("Enabled".equals(rowMatcher.group(2)) || "Disabled".equals(rowMatcher.group(2)));
        }
        int expected = expectedInventory().size();
        assertEquals(expected, rows);
        assertEquals(expected * 2, countOccurrences(html, "<td"));
    }

    @Test
    void writesCompleteUtf8DocumentToRequestedPath(@TempDir Path tempDir) throws Exception
    {
        String html = ScarletFeatureUsageReport.renderHtml(
            ScarletFeatureUsageReport.captureForTesting(new RawConfiguration()));
        Path destination = tempDir.resolve("feature-report.html");

        ScarletFeatureUsageReport.writeHtml(destination, html);

        assertTrue(Files.isRegularFile(destination));
        assertEquals(html, new String(Files.readAllBytes(destination), StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.list(tempDir))
        {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".scarlet-feature-report-")));
        }
    }

    private static RawConfiguration availableConfiguration()
    {
        RawConfiguration spec = new RawConfiguration();
        spec.discordAvailable = true;
        return spec;
    }

    private static RawConfiguration switchedOnConfiguration()
    {
        RawConfiguration spec = availableConfiguration();
        spec.auxiliaryWebhooksFeatureEnabled = true;
        spec.ticketToolAutoResponseEnabled = true;
        spec.ticketToolChannelNameRegex = "ticket-.+";
        spec.announceVerificationCompleteEnabled = true;
        spec.autoInviteOnVerificationEnabled = true;
        spec.verifiedRoleSf = "private-role";
        spec.routableRoles.add(spec.verifiedRoleSf);
        spec.autoInviteGroupId = "grp_private";
        spec.discordModerationFeatureEnabled = true;
        spec.discordModerationCommandsEnabled = true;
        spec.timedBansEnabled = true;
        spec.evidenceFeatureEnabled = true;
        spec.evidenceEnabled = true;
        spec.evidenceRoot = "private-root";
        spec.calendarFeatureEnabled = true;
        spec.activeMirroredSchedule = true;
        spec.scheduleTargetGuildAvailable = true;
        spec.avatarSearchFeatureEnabled = true;
        spec.watchedAvatarsFeatureEnabled = true;
        spec.animatedEmojiFeatureEnabled = true;
        spec.vrchatReportsFeatureEnabled = true;
        spec.appendTemplateFooterEnabled = true;
        spec.bundleInstanceKicksEnabled = true;
        spec.moderationSummaryOnlyActivityEnabled = true;
        spec.pingInstanceWarnEnabled = true;
        spec.pingInstanceKickEnabled = true;
        spec.pingMemberRemoveEnabled = true;
        spec.pingUserBanEnabled = true;
        spec.pingUserUnbanEnabled = true;
        spec.pingOutstandingInstanceWarnEnabled = true;
        spec.pingOutstandingInstanceKickEnabled = true;
        spec.pingOutstandingMemberRemoveEnabled = true;
        spec.pingOutstandingUserBanEnabled = true;
        spec.pingOutstandingUserUnbanEnabled = true;
        return spec;
    }

    private static RawConfiguration moderationConfiguration()
    {
        RawConfiguration spec = availableConfiguration();
        spec.timedBansEnabled = true;
        spec.evidenceFeatureEnabled = true;
        spec.evidenceEnabled = true;
        spec.evidenceRoot = "private-evidence-root";
        spec.vrchatReportsFeatureEnabled = true;
        spec.appendTemplateFooterEnabled = true;
        spec.pingInstanceWarnEnabled = true;
        spec.pingInstanceKickEnabled = true;
        spec.pingMemberRemoveEnabled = true;
        spec.pingUserBanEnabled = true;
        spec.pingUserUnbanEnabled = true;
        return spec;
    }

    private static void addNormalRoute(RawConfiguration spec, GroupAuditType type)
    {
        String channel = "private-normal-" + type.id;
        spec.auditType2channelSf.put(type.id, channel);
        spec.routableTextChannels.add(channel);
    }

    private static void addSecretRoute(RawConfiguration spec, GroupAuditType type)
    {
        String channel = "private-secret-" + type.id;
        spec.auditType2secretChannelSf.put(type.id, channel);
        spec.routableTextChannels.add(channel);
    }

    private static boolean enabled(RawConfiguration spec, String name)
    {
        Boolean value = statusMap(statuses(spec)).get(name);
        if (value == null)
            throw new AssertionError("Unknown feature row: " + name);
        return value.booleanValue();
    }

    private static List<FeatureStatus> statuses(RawConfiguration spec)
    {
        return ScarletFeatureUsageReport.statuses(
            ScarletFeatureUsageReport.captureForTesting(spec));
    }

    private static Map<String, Boolean> statusMap(List<FeatureStatus> statuses)
    {
        return statuses.stream()
            .collect(Collectors.toMap(status -> status.name, status -> status.enabled));
    }

    private static Set<String> expectedInventory()
    {
        Set<String> names = new LinkedHashSet<>();
        for (String name : BASE_FEATURE_NAMES)
            names.add(name);
        for (GroupAuditType type : GroupAuditType.values())
        {
            names.add("Discord audit — " + type.title);
            names.add("Secret-staff audit — " + type.title);
            names.add("Auxiliary webhook — " + type.title);
        }
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
        {
            names.add("Discord extended audit — " + type.title);
            names.add("Secret-staff extended audit — " + type.title);
        }
        return names;
    }

    private static int countOccurrences(String text, String needle)
    {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0)
        {
            count++;
            from += needle.length();
        }
        return count;
    }
}

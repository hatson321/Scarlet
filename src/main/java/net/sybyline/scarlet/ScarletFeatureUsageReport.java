package net.sybyline.scarlet;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.dv8tion.jda.api.entities.Guild;

import net.sybyline.scarlet.util.UniqueStrings;

/**
 * Produces a privacy-safe inventory of Scarlet features that can emit to, or
 * be operated through, Discord.
 *
 * <p>Configuration is deliberately reduced to booleans before HTML rendering.
 * The renderer therefore never receives channel IDs, guild IDs, webhook URLs,
 * roles, account details, paths, tokens, or other private configuration values.
 */
public final class ScarletFeatureUsageReport
{
    public static final String DEFAULT_FILENAME = "scarlet-feature-usage-report.html";

    static final class FeatureStatus
    {
        final String name;
        final boolean enabled;

        FeatureStatus(String name, boolean enabled)
        {
            this.name = name;
            this.enabled = enabled;
        }
    }

    /** A privacy boundary: this object contains only public feature keys and booleans. */
    static final class Snapshot
    {
        final Set<String> auditRoutes;
        final Set<String> auditSecretRoutes;
        final Set<String> auditAuxRoutes;
        final Set<String> auditExRoutes;
        final Set<String> auditExSecretRoutes;

        final boolean auxiliaryWebhooksEnabled;
        final boolean actionLog;
        final boolean accountAgeAlerts;
        final boolean operationsAlerts;
        final boolean trainingOutput;
        final boolean voiceAnnouncements;
        final boolean ticketToolAutoResponse;
        final boolean announceVerificationComplete;
        final boolean autoInviteOnVerification;
        final boolean discordModerationCommands;
        final boolean timedBans;
        final boolean evidenceSubmission;
        final boolean scheduledEvents;
        final boolean avatarSearchCommands;
        final boolean watchedAvatarCommands;
        final boolean animatedEmojiCommands;
        final boolean vrchatReportTools;
        final boolean appendTemplateFooter;
        final boolean bundleInstanceKicks;
        final boolean moderationSummaryOnlyActivity;
        final boolean pingInstanceWarn;
        final boolean pingInstanceKick;
        final boolean pingMemberRemove;
        final boolean pingUserBan;
        final boolean pingUserUnban;
        final boolean pingOutstandingInstanceWarn;
        final boolean pingOutstandingInstanceKick;
        final boolean pingOutstandingMemberRemove;
        final boolean pingOutstandingUserBan;
        final boolean pingOutstandingUserUnban;

        Snapshot(Set<String> auditRoutes,
                 Set<String> auditSecretRoutes,
                 Set<String> auditAuxRoutes,
                 Set<String> auditExRoutes,
                 Set<String> auditExSecretRoutes,
                 boolean auxiliaryWebhooksEnabled,
                 boolean actionLog,
                 boolean accountAgeAlerts,
                 boolean operationsAlerts,
                 boolean trainingOutput,
                 boolean voiceAnnouncements,
                 boolean ticketToolAutoResponse,
                 boolean announceVerificationComplete,
                 boolean autoInviteOnVerification,
                 boolean discordModerationCommands,
                 boolean timedBans,
                 boolean evidenceSubmission,
                 boolean scheduledEvents,
                 boolean avatarSearchCommands,
                 boolean watchedAvatarCommands,
                 boolean animatedEmojiCommands,
                 boolean vrchatReportTools,
                 boolean appendTemplateFooter,
                 boolean bundleInstanceKicks,
                 boolean moderationSummaryOnlyActivity,
                 boolean pingInstanceWarn,
                 boolean pingInstanceKick,
                 boolean pingMemberRemove,
                 boolean pingUserBan,
                 boolean pingUserUnban,
                 boolean pingOutstandingInstanceWarn,
                 boolean pingOutstandingInstanceKick,
                 boolean pingOutstandingMemberRemove,
                 boolean pingOutstandingUserBan,
                 boolean pingOutstandingUserUnban)
        {
            this.auditRoutes = immutableCopy(auditRoutes);
            this.auditSecretRoutes = immutableCopy(auditSecretRoutes);
            this.auditAuxRoutes = immutableCopy(auditAuxRoutes);
            this.auditExRoutes = immutableCopy(auditExRoutes);
            this.auditExSecretRoutes = immutableCopy(auditExSecretRoutes);
            this.auxiliaryWebhooksEnabled = auxiliaryWebhooksEnabled;
            this.actionLog = actionLog;
            this.accountAgeAlerts = accountAgeAlerts;
            this.operationsAlerts = operationsAlerts;
            this.trainingOutput = trainingOutput;
            this.voiceAnnouncements = voiceAnnouncements;
            this.ticketToolAutoResponse = ticketToolAutoResponse;
            this.announceVerificationComplete = announceVerificationComplete;
            this.autoInviteOnVerification = autoInviteOnVerification;
            this.discordModerationCommands = discordModerationCommands;
            this.timedBans = timedBans;
            this.evidenceSubmission = evidenceSubmission;
            this.scheduledEvents = scheduledEvents;
            this.avatarSearchCommands = avatarSearchCommands;
            this.watchedAvatarCommands = watchedAvatarCommands;
            this.animatedEmojiCommands = animatedEmojiCommands;
            this.vrchatReportTools = vrchatReportTools;
            this.appendTemplateFooter = appendTemplateFooter;
            this.bundleInstanceKicks = bundleInstanceKicks;
            this.moderationSummaryOnlyActivity = moderationSummaryOnlyActivity;
            this.pingInstanceWarn = pingInstanceWarn;
            this.pingInstanceKick = pingInstanceKick;
            this.pingMemberRemove = pingMemberRemove;
            this.pingUserBan = pingUserBan;
            this.pingUserUnban = pingUserUnban;
            this.pingOutstandingInstanceWarn = pingOutstandingInstanceWarn;
            this.pingOutstandingInstanceKick = pingOutstandingInstanceKick;
            this.pingOutstandingMemberRemove = pingOutstandingMemberRemove;
            this.pingOutstandingUserBan = pingOutstandingUserBan;
            this.pingOutstandingUserUnban = pingOutstandingUserUnban;
        }

        private static Set<String> immutableCopy(Set<String> source)
        {
            return Collections.unmodifiableSet(new HashSet<>(source));
        }
    }

    /** Raw test input used to prove that private values are discarded at the capture boundary. */
    static final class RawConfiguration
    {
        String token;
        String guildSf;
        String audioChannelSf;
        String discordActionLogChannelSf;
        String opsAlertChannelSf;
        String trainingChannelSf;
        String evidenceRoot;
        String ticketToolCategorySf;
        String ticketToolChannelNameRegex;
        String ticketToolNotifyRoleSf;
        String ticketToolAgeVerificationMessage;
        String verifiedRoleSf;
        String autoInviteGroupId;
        String fallbackAutoInviteGroupId;
        boolean discordAvailable;
        boolean auxiliaryWebhooksFeatureEnabled;
        boolean ticketToolAutoResponseEnabled;
        boolean trainingMode;
        boolean ttsUsesDefaultAudioDevice;
        boolean announceVerificationCompleteEnabled;
        boolean autoInviteOnVerificationEnabled;
        boolean discordModerationFeatureEnabled;
        boolean discordModerationCommandsEnabled;
        boolean timedBansEnabled;
        boolean evidenceFeatureEnabled;
        boolean evidenceEnabled;
        boolean calendarFeatureEnabled;
        boolean activeMirroredSchedule;
        boolean scheduleTargetGuildAvailable;
        boolean avatarSearchFeatureEnabled;
        boolean watchedAvatarsFeatureEnabled;
        boolean animatedEmojiFeatureEnabled;
        boolean vrchatReportsFeatureEnabled;
        boolean appendTemplateFooterEnabled;
        boolean bundleInstanceKicksEnabled;
        boolean moderationSummaryOnlyActivityEnabled;
        boolean pingInstanceWarnEnabled;
        boolean pingInstanceKickEnabled;
        boolean pingMemberRemoveEnabled;
        boolean pingUserBanEnabled;
        boolean pingUserUnbanEnabled;
        boolean pingOutstandingInstanceWarnEnabled;
        boolean pingOutstandingInstanceKickEnabled;
        boolean pingOutstandingMemberRemoveEnabled;
        boolean pingOutstandingUserBanEnabled;
        boolean pingOutstandingUserUnbanEnabled;
        int discordAccountAgeAlertMinutes;
        final Set<String> routableTextChannels = new HashSet<>();
        final Set<String> routableVoiceChannels = new HashSet<>();
        final Set<String> routableCategories = new HashSet<>();
        final Set<String> routableRoles = new HashSet<>();
        final Set<String> usableAuxWebhookAliases = new HashSet<>();
        final Map<String, UniqueStrings> scarletPermission2roleSf = new HashMap<>();
        final Map<String, String> scarletAuxWh2webhookUrl = new HashMap<>();
        final Map<String, String> auditType2channelSf = new HashMap<>();
        final Map<String, String> auditExType2channelSf = new HashMap<>();
        final Map<String, UniqueStrings> auditType2scarletAuxWh = new HashMap<>();
        final Map<String, String> auditType2secretChannelSf = new HashMap<>();
        final Map<String, String> auditExType2secretChannelSf = new HashMap<>();
    }

    /** Fully resolved booleans and public route keys; no private values cross this boundary. */
    private static final class EffectiveConfiguration
    {
        Set<String> auditRoutes = Collections.emptySet();
        Set<String> auditSecretRoutes = Collections.emptySet();
        Set<String> auditAuxRoutes = Collections.emptySet();
        Set<String> auditExRoutes = Collections.emptySet();
        Set<String> auditExSecretRoutes = Collections.emptySet();

        boolean auxiliaryWebhooksFeatureEnabled;
        boolean actionLogRoute;
        int accountAgeAlertMinutes;
        boolean operationsAlertRoute;
        boolean trainingMode;
        boolean trainingRoute;
        boolean voiceRoute;
        boolean ttsUsesDefaultAudioDevice;
        boolean ticketToolAutoResponseEnabled;
        boolean ticketToolRoute;
        boolean announceVerificationCompleteEnabled;
        boolean autoInviteOnVerificationEnabled;
        boolean verifiedRoleAvailable;
        boolean autoInviteGroupConfigured;
        boolean discordModerationFeatureEnabled;
        boolean discordModerationCommandsEnabled;
        boolean timedBansEnabled;
        boolean evidenceFeatureEnabled;
        boolean evidenceEnabled;
        boolean evidenceRootConfigured;
        boolean calendarFeatureEnabled;
        boolean activeMirroredSchedule;
        boolean scheduleTargetGuildAvailable;
        boolean avatarSearchFeatureEnabled;
        boolean watchedAvatarsFeatureEnabled;
        boolean animatedEmojiFeatureEnabled;
        boolean vrchatReportsFeatureEnabled;
        boolean appendTemplateFooterEnabled;
        boolean bundleInstanceKicksEnabled;
        boolean moderationSummaryOnlyActivityEnabled;
        boolean pingInstanceWarnEnabled;
        boolean pingInstanceKickEnabled;
        boolean pingMemberRemoveEnabled;
        boolean pingUserBanEnabled;
        boolean pingUserUnbanEnabled;
        boolean pingOutstandingInstanceWarnEnabled;
        boolean pingOutstandingInstanceKickEnabled;
        boolean pingOutstandingMemberRemoveEnabled;
        boolean pingOutstandingUserBanEnabled;
        boolean pingOutstandingUserUnbanEnabled;

        Snapshot snapshot()
        {
            boolean ticketTool = this.ticketToolAutoResponseEnabled && this.ticketToolRoute;
            boolean autoInvite = this.autoInviteOnVerificationEnabled
                && this.verifiedRoleAvailable
                && this.autoInviteGroupConfigured;
            boolean moderationRoute = hasAnyModerationRoute(this.auditRoutes, this.auditSecretRoutes);

            return new Snapshot(
                this.auditRoutes,
                this.auditSecretRoutes,
                this.auditAuxRoutes,
                this.auditExRoutes,
                this.auditExSecretRoutes,
                this.auxiliaryWebhooksFeatureEnabled,
                this.actionLogRoute,
                this.actionLogRoute && this.accountAgeAlertMinutes > 0,
                this.operationsAlertRoute,
                this.trainingMode && this.trainingRoute,
                this.voiceRoute && !this.ttsUsesDefaultAudioDevice,
                ticketTool,
                this.announceVerificationCompleteEnabled
                    && autoInvite
                    && (this.actionLogRoute || ticketTool),
                autoInvite,
                this.discordModerationFeatureEnabled && this.discordModerationCommandsEnabled,
                this.timedBansEnabled && moderationRoute,
                this.evidenceFeatureEnabled
                    && this.evidenceEnabled
                    && this.evidenceRootConfigured
                    && moderationRoute,
                this.calendarFeatureEnabled
                    && this.activeMirroredSchedule
                    && this.scheduleTargetGuildAvailable,
                this.avatarSearchFeatureEnabled,
                this.watchedAvatarsFeatureEnabled,
                this.animatedEmojiFeatureEnabled,
                this.vrchatReportsFeatureEnabled,
                this.vrchatReportsFeatureEnabled
                    && this.appendTemplateFooterEnabled
                    && moderationRoute,
                this.bundleInstanceKicksEnabled
                    && hasPairedModerationRoute(this.auditRoutes, this.auditSecretRoutes,
                        GroupAuditType.USER_BAN, GroupAuditType.INSTANCE_KICK),
                this.moderationSummaryOnlyActivityEnabled,
                this.pingInstanceWarnEnabled
                    && hasModerationRoute(this.auditRoutes, this.auditSecretRoutes, GroupAuditType.INSTANCE_WARN),
                this.pingInstanceKickEnabled
                    && hasModerationRoute(this.auditRoutes, this.auditSecretRoutes, GroupAuditType.INSTANCE_KICK),
                this.pingMemberRemoveEnabled
                    && hasModerationRoute(this.auditRoutes, this.auditSecretRoutes, GroupAuditType.MEMBER_REMOVE),
                this.pingUserBanEnabled
                    && hasModerationRoute(this.auditRoutes, this.auditSecretRoutes, GroupAuditType.USER_BAN),
                this.pingUserUnbanEnabled
                    && hasModerationRoute(this.auditRoutes, this.auditSecretRoutes, GroupAuditType.USER_UNBAN),
                this.pingOutstandingInstanceWarnEnabled,
                this.pingOutstandingInstanceKickEnabled,
                this.pingOutstandingMemberRemoveEnabled,
                this.pingOutstandingUserBanEnabled,
                this.pingOutstandingUserUnbanEnabled);
        }
    }

    public static void write(Scarlet scarlet, File destination) throws IOException
    {
        if (destination == null)
            throw new IllegalArgumentException("Destination is required");
        writeHtml(destination.toPath(), renderHtml(capture(scarlet)));
    }

    static Snapshot capture(Scarlet scarlet)
    {
        if (scarlet == null || !(scarlet.discord instanceof ScarletDiscordJDA))
            return disabledSnapshot();

        ScarletDiscordJDA discord = (ScarletDiscordJDA)scarlet.discord;
        Guild guild = resolveGuild(discord, discord.guildSf);
        if (guild == null)
            return disabledSnapshot();

        Predicate<String> textChannelResolver = channelSf -> hasTextChannel(guild, channelSf);
        EffectiveConfiguration effective = new EffectiveConfiguration();
        effective.auditRoutes = enabledAuditRoutes(discord.auditType2channelSf, textChannelResolver);
        effective.auditSecretRoutes = enabledAuditRoutes(discord.auditType2secretChannelSf, textChannelResolver);
        effective.auditAuxRoutes = enabledAuxRoutes(discord.auditType2scarletAuxWh,
            alias -> hasUsableAuxWebhook(discord, alias));
        effective.auditExRoutes = enabledAuditExRoutes(discord.auditExType2channelSf, textChannelResolver);
        effective.auditExSecretRoutes = enabledSecretAuditExRoutes(discord.auditExType2secretChannelSf, discord.auditExType2channelSf, textChannelResolver);
        effective.auxiliaryWebhooksFeatureEnabled = Features.AUX_WEBHOOKS_ENABLED;
        effective.actionLogRoute = hasTextChannel(guild, discord.discordActionLogChannelSf);
        effective.accountAgeAlertMinutes = discord.discordAccountAgeAlertMinutes;
        effective.operationsAlertRoute = hasTextChannel(guild, discord.opsAlertChannelSf);
        effective.trainingMode = Boolean.TRUE.equals(scarlet.trainingMode.get());
        effective.trainingRoute = hasTextChannel(guild, discord.trainingChannelSf);
        effective.voiceRoute = hasVoiceChannel(guild, discord.audioChannelSf);
        effective.ttsUsesDefaultAudioDevice = usesDefaultAudioDeviceOrUnavailable(scarlet.eventListener);
        effective.ticketToolAutoResponseEnabled = discord.ticketToolAutoResponseEnabled;
        effective.ticketToolRoute = discord.ticketToolChannelNamePattern != null
            || hasCategory(guild, discord.ticketToolCategorySf);
        effective.announceVerificationCompleteEnabled = Boolean.TRUE.equals(scarlet.announceVerifyComplete.get());
        effective.autoInviteOnVerificationEnabled = Boolean.TRUE.equals(scarlet.autoInviteOnVerify.get());
        effective.verifiedRoleAvailable = hasRole(guild, scarlet.verifiedRoleSf.get());
        effective.autoInviteGroupConfigured = hasEffectiveAutoInviteGroup(
            scarlet.autoInviteGroupId.get(), scarlet.vrc == null ? null : scarlet.vrc.groupId);
        effective.discordModerationFeatureEnabled = Features.DISCORD_KICK_BAN_ENABLED;
        effective.discordModerationCommandsEnabled = Boolean.TRUE.equals(scarlet.discordKickBanEnabled.get());
        effective.timedBansEnabled = Boolean.TRUE.equals(scarlet.timedBansEnabled.get());
        effective.evidenceFeatureEnabled = Features.EVIDENCE_ENABLED;
        effective.evidenceEnabled = Boolean.TRUE.equals(discord.evidenceEnabled.get());
        effective.evidenceRootConfigured = configured(discord.evidenceRoot);
        effective.calendarFeatureEnabled = Features.CALENDAR_ENABLED;
        effective.activeMirroredSchedule = hasActiveMirroredSchedule(scarlet.calendar);
        effective.scheduleTargetGuildAvailable = hasScheduleTargetGuild(scarlet.calendar, discord, guild);
        effective.avatarSearchFeatureEnabled = Features.AVATAR_SEARCH_ENABLED;
        effective.watchedAvatarsFeatureEnabled = Features.WATCHED_AVATARS_ENABLED;
        effective.animatedEmojiFeatureEnabled = Features.ANIMATED_EMOJI_ENABLED;
        effective.vrchatReportsFeatureEnabled = Features.VRCHAT_REPORTS_ENABLED;
        effective.appendTemplateFooterEnabled = Boolean.TRUE.equals(discord.appendTemplateFooter.get());
        effective.bundleInstanceKicksEnabled = Boolean.TRUE.equals(discord.bundleModerations_instanceKick2userBan.get());
        effective.moderationSummaryOnlyActivityEnabled = Boolean.TRUE.equals(discord.moderationSummary_onlyActivity.get());
        effective.pingInstanceWarnEnabled = Boolean.TRUE.equals(discord.pingOnModeration_instanceWarn.get());
        effective.pingInstanceKickEnabled = Boolean.TRUE.equals(discord.pingOnModeration_instanceKick.get());
        effective.pingMemberRemoveEnabled = Boolean.TRUE.equals(discord.pingOnModeration_memberRemove.get());
        effective.pingUserBanEnabled = Boolean.TRUE.equals(discord.pingOnModeration_userBan.get());
        effective.pingUserUnbanEnabled = Boolean.TRUE.equals(discord.pingOnModeration_userUnban.get());
        effective.pingOutstandingInstanceWarnEnabled = Boolean.TRUE.equals(discord.pingOnOutstandingModeration_instanceWarn.get());
        effective.pingOutstandingInstanceKickEnabled = Boolean.TRUE.equals(discord.pingOnOutstandingModeration_instanceKick.get());
        effective.pingOutstandingMemberRemoveEnabled = Boolean.TRUE.equals(discord.pingOnOutstandingModeration_memberRemove.get());
        effective.pingOutstandingUserBanEnabled = Boolean.TRUE.equals(discord.pingOnOutstandingModeration_userBan.get());
        effective.pingOutstandingUserUnbanEnabled = Boolean.TRUE.equals(discord.pingOnOutstandingModeration_userUnban.get());
        return effective.snapshot();
    }

    /** Test seam that proves raw values are reduced without starting Scarlet's runtime. */
    static Snapshot captureForTesting(RawConfiguration raw)
    {
        if (raw == null || !raw.discordAvailable)
            return disabledSnapshot();

        Predicate<String> textChannelResolver = raw.routableTextChannels::contains;
        EffectiveConfiguration effective = new EffectiveConfiguration();
        effective.auditRoutes = enabledAuditRoutes(raw.auditType2channelSf, textChannelResolver);
        effective.auditSecretRoutes = enabledAuditRoutes(raw.auditType2secretChannelSf, textChannelResolver);
        effective.auditAuxRoutes = enabledAuxRoutes(raw.auditType2scarletAuxWh,
            alias -> configured(raw.scarletAuxWh2webhookUrl.get(alias))
                && raw.usableAuxWebhookAliases.contains(alias));
        effective.auditExRoutes = enabledAuditExRoutes(raw.auditExType2channelSf, textChannelResolver);
        effective.auditExSecretRoutes = enabledSecretAuditExRoutes(raw.auditExType2secretChannelSf, raw.auditExType2channelSf, textChannelResolver);
        effective.auxiliaryWebhooksFeatureEnabled = raw.auxiliaryWebhooksFeatureEnabled;
        effective.actionLogRoute = resolved(raw.discordActionLogChannelSf, textChannelResolver);
        effective.accountAgeAlertMinutes = raw.discordAccountAgeAlertMinutes;
        effective.operationsAlertRoute = resolved(raw.opsAlertChannelSf, textChannelResolver);
        effective.trainingMode = raw.trainingMode;
        effective.trainingRoute = resolved(raw.trainingChannelSf, textChannelResolver);
        effective.voiceRoute = resolved(raw.audioChannelSf, raw.routableVoiceChannels::contains);
        effective.ttsUsesDefaultAudioDevice = raw.ttsUsesDefaultAudioDevice;
        effective.ticketToolAutoResponseEnabled = raw.ticketToolAutoResponseEnabled;
        effective.ticketToolRoute = resolved(raw.ticketToolCategorySf, raw.routableCategories::contains)
            || compiles(raw.ticketToolChannelNameRegex);
        effective.announceVerificationCompleteEnabled = raw.announceVerificationCompleteEnabled;
        effective.autoInviteOnVerificationEnabled = raw.autoInviteOnVerificationEnabled;
        effective.verifiedRoleAvailable = hasConfiguredRole(raw.verifiedRoleSf, raw.routableRoles::contains);
        effective.autoInviteGroupConfigured = hasEffectiveAutoInviteGroup(
            raw.autoInviteGroupId, raw.fallbackAutoInviteGroupId);
        effective.discordModerationFeatureEnabled = raw.discordModerationFeatureEnabled;
        effective.discordModerationCommandsEnabled = raw.discordModerationCommandsEnabled;
        effective.timedBansEnabled = raw.timedBansEnabled;
        effective.evidenceFeatureEnabled = raw.evidenceFeatureEnabled;
        effective.evidenceEnabled = raw.evidenceEnabled;
        effective.evidenceRootConfigured = configured(raw.evidenceRoot);
        effective.calendarFeatureEnabled = raw.calendarFeatureEnabled;
        effective.activeMirroredSchedule = raw.activeMirroredSchedule;
        effective.scheduleTargetGuildAvailable = raw.scheduleTargetGuildAvailable;
        effective.avatarSearchFeatureEnabled = raw.avatarSearchFeatureEnabled;
        effective.watchedAvatarsFeatureEnabled = raw.watchedAvatarsFeatureEnabled;
        effective.animatedEmojiFeatureEnabled = raw.animatedEmojiFeatureEnabled;
        effective.vrchatReportsFeatureEnabled = raw.vrchatReportsFeatureEnabled;
        effective.appendTemplateFooterEnabled = raw.appendTemplateFooterEnabled;
        effective.bundleInstanceKicksEnabled = raw.bundleInstanceKicksEnabled;
        effective.moderationSummaryOnlyActivityEnabled = raw.moderationSummaryOnlyActivityEnabled;
        effective.pingInstanceWarnEnabled = raw.pingInstanceWarnEnabled;
        effective.pingInstanceKickEnabled = raw.pingInstanceKickEnabled;
        effective.pingMemberRemoveEnabled = raw.pingMemberRemoveEnabled;
        effective.pingUserBanEnabled = raw.pingUserBanEnabled;
        effective.pingUserUnbanEnabled = raw.pingUserUnbanEnabled;
        effective.pingOutstandingInstanceWarnEnabled = raw.pingOutstandingInstanceWarnEnabled;
        effective.pingOutstandingInstanceKickEnabled = raw.pingOutstandingInstanceKickEnabled;
        effective.pingOutstandingMemberRemoveEnabled = raw.pingOutstandingMemberRemoveEnabled;
        effective.pingOutstandingUserBanEnabled = raw.pingOutstandingUserBanEnabled;
        effective.pingOutstandingUserUnbanEnabled = raw.pingOutstandingUserUnbanEnabled;
        return effective.snapshot();
    }

    static List<FeatureStatus> statuses(Snapshot snapshot)
    {
        List<FeatureStatus> result = new ArrayList<>();
        add(result, "Discord action logging", snapshot.actionLog);
        add(result, "Discord member-join logging", snapshot.actionLog);
        add(result, "Discord account-age alerts", snapshot.accountAgeAlerts);
        add(result, "Operations and health alerts", snapshot.operationsAlerts);
        add(result, "Training-mode Discord output", snapshot.trainingOutput);
        add(result, "Discord voice announcements", snapshot.voiceAnnouncements);
        add(result, "Ticket Tool auto-response", snapshot.ticketToolAutoResponse);
        add(result, "Verification-complete announcements", snapshot.announceVerificationComplete);
        add(result, "Automatic group invite after verification", snapshot.autoInviteOnVerification);
        add(result, "Discord moderation warnings", snapshot.discordModerationCommands);
        add(result, "Discord moderation kicks", snapshot.discordModerationCommands);
        add(result, "Discord moderation bans", snapshot.discordModerationCommands);
        add(result, "Timed bans", snapshot.timedBans);
        add(result, "Evidence submission", snapshot.evidenceSubmission);
        add(result, "Calendar and scheduled Discord events", snapshot.scheduledEvents);
        add(result, "Avatar search commands", snapshot.avatarSearchCommands);
        add(result, "Watched-avatar commands", snapshot.watchedAvatarCommands);
        add(result, "Animated emoji commands", snapshot.animatedEmojiCommands);
        add(result, "VRChat report tools", snapshot.vrchatReportTools);
        add(result, "Template footer on Discord messages", snapshot.appendTemplateFooter);
        add(result, "Bundle instance kicks with user bans", snapshot.bundleInstanceKicks);
        add(result, "Moderation summary: staff with activity only", snapshot.moderationSummaryOnlyActivity);
        add(result, "Ping on instance warning", snapshot.pingInstanceWarn);
        add(result, "Ping on instance kick", snapshot.pingInstanceKick);
        add(result, "Ping on member removal", snapshot.pingMemberRemove);
        add(result, "Ping on user ban", snapshot.pingUserBan);
        add(result, "Ping on user unban", snapshot.pingUserUnban);
        add(result, "Ping on outstanding instance warning", snapshot.pingOutstandingInstanceWarn);
        add(result, "Ping on outstanding instance kick", snapshot.pingOutstandingInstanceKick);
        add(result, "Ping on outstanding member removal", snapshot.pingOutstandingMemberRemove);
        add(result, "Ping on outstanding user ban", snapshot.pingOutstandingUserBan);
        add(result, "Ping on outstanding user unban", snapshot.pingOutstandingUserUnban);

        for (GroupAuditType type : GroupAuditType.values())
        {
            add(result, "Discord audit — " + type.title, snapshot.auditRoutes.contains(type.id));
            add(result, "Secret-staff audit — " + type.title, snapshot.auditSecretRoutes.contains(type.id));
            add(result, "Auxiliary webhook — " + type.title,
                snapshot.auxiliaryWebhooksEnabled && snapshot.auditAuxRoutes.contains(type.id));
        }
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
        {
            add(result, "Discord extended audit — " + type.title, snapshot.auditExRoutes.contains(type.id));
            add(result, "Secret-staff extended audit — " + type.title, snapshot.auditExSecretRoutes.contains(type.id));
        }
        return Collections.unmodifiableList(result);
    }

    static String renderHtml(Snapshot snapshot)
    {
        StringBuilder html = new StringBuilder(32_768);
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
            .append("<meta charset=\"utf-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
            .append("<title>Scarlet Feature Usage Report</title>\n")
            .append("<style>\n")
            .append(":root{color-scheme:dark;--bg:#07111f;--panel:#101d2f;--line:#263a52;--text:#e8f0fa;--muted:#a8b7c9;--on:#45d483;--off:#f19a67}")
            .append("*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}")
            .append("main{width:min(960px,calc(100% - 32px));margin:40px auto}h1{margin:0 0 8px;font-size:clamp(1.7rem,4vw,2.4rem)}")
            .append("p{margin:0 0 24px;color:var(--muted)}.table-wrap{overflow:auto;border:1px solid var(--line);border-radius:14px;background:var(--panel);box-shadow:0 18px 50px #0006}")
            .append("table{width:100%;border-collapse:collapse}th,td{padding:12px 16px;text-align:left;border-bottom:1px solid var(--line)}")
            .append("th{position:sticky;top:0;background:#15253a;color:#cfe0f4}th:last-child,td:last-child{width:150px}")
            .append("tr:last-child td{border-bottom:0}.status{font-weight:750}.enabled{color:var(--on)}.disabled{color:var(--off)}")
            .append("@media(max-width:560px){main{width:min(100% - 16px,960px);margin:18px auto}th,td{padding:10px 12px}th:last-child,td:last-child{width:112px}}")
            .append("@media print{:root{color-scheme:light;--bg:#fff;--panel:#fff;--line:#bac4cf;--text:#111827;--muted:#374151;--on:#087a3e;--off:#a63f16}body{background:#fff}main{width:100%;margin:0}.table-wrap{box-shadow:none}th{position:static;background:#eef2f7}}")
            .append("</style>\n</head>\n<body>\n<main>\n")
            .append("<h1>Scarlet Feature Usage Report</h1>\n")
            .append("<p>This local report lists feature names and whether each feature is enabled. It contains no private configuration details.</p>\n")
            .append("<div class=\"table-wrap\"><table>\n<thead><tr><th scope=\"col\">Feature</th><th scope=\"col\">Status</th></tr></thead>\n<tbody>\n");
        for (FeatureStatus status : statuses(snapshot))
        {
            html.append("<tr><td>").append(escapeHtml(status.name)).append("</td><td class=\"status ")
                .append(status.enabled ? "enabled\">Enabled" : "disabled\">Disabled")
                .append("</td></tr>\n");
        }
        html.append("</tbody>\n</table></div>\n</main>\n</body>\n</html>\n");
        return html.toString();
    }

    static void writeHtml(Path destination, String html) throws IOException
    {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null)
            throw new IOException("Destination has no parent directory");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".scarlet-feature-report-", ".tmp");
        try
        {
            Files.write(temp, html.getBytes(StandardCharsets.UTF_8));
            try
            {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ex)
            {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(temp);
        }
    }

    private static Snapshot disabledSnapshot()
    {
        Set<String> none = Collections.emptySet();
        return new Snapshot(none, none, none, none, none,
            false,
            false, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false,
            false, false);
    }

    private static Set<String> enabledAuditRoutes(Map<String, String> routes,
                                                   Predicate<String> routeResolver)
    {
        Set<String> result = new HashSet<>();
        if (routes == null || routeResolver == null)
            return result;
        for (GroupAuditType type : GroupAuditType.values())
            if (resolved(routes.get(type.id), routeResolver))
                result.add(type.id);
        return result;
    }

    private static Set<String> enabledAuditExRoutes(Map<String, String> routes,
                                                     Predicate<String> routeResolver)
    {
        Set<String> result = new HashSet<>();
        if (routes == null || routeResolver == null)
            return result;
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
            if (resolved(routes.get(type.id), routeResolver))
                result.add(type.id);
        return result;
    }

    private static Set<String> enabledSecretAuditExRoutes(Map<String, String> routes,
                                                         Map<String, String> ordinaryRoutes,
                                                         Predicate<String> routeResolver)
    {
        Set<String> result = enabledAuditExRoutes(routes, routeResolver);
        result.removeIf(id -> !GroupAuditTypeEx.of(id).supportsSecretRouting());
        if (routes != null && ordinaryRoutes != null && java.util.Objects.equals(
            routes.get(GroupAuditTypeEx.LIVE_PLAYERLIST.id), ordinaryRoutes.get(GroupAuditTypeEx.LIVE_PLAYERLIST.id)))
            result.remove(GroupAuditTypeEx.LIVE_PLAYERLIST.id);
        return result;
    }

    private static Set<String> enabledAuxRoutes(Map<String, UniqueStrings> eventAliases,
                                                Predicate<String> usableAlias)
    {
        Set<String> result = new HashSet<>();
        if (eventAliases == null || usableAlias == null)
            return result;
        for (GroupAuditType type : GroupAuditType.values())
        {
            UniqueStrings aliases = eventAliases.get(type.id);
            if (aliases == null)
                continue;
            String[] copy;
            try
            {
                copy = aliases.toArray();
            }
            catch (RuntimeException ex)
            {
                continue;
            }
            for (String alias : copy)
            {
                if (resolved(alias, usableAlias))
                {
                    result.add(type.id);
                    break;
                }
            }
        }
        return result;
    }

    private static Guild resolveGuild(ScarletDiscordJDA discord, String guildSf)
    {
        if (discord == null || discord.jda == null || !configured(guildSf))
            return null;
        try
        {
            return discord.jda.getGuildById(guildSf);
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private static boolean hasTextChannel(Guild guild, String channelSf)
    {
        return guild != null && resolved(channelSf, value -> guild.getTextChannelById(value) != null);
    }

    private static boolean hasVoiceChannel(Guild guild, String channelSf)
    {
        return guild != null && resolved(channelSf, value -> guild.getVoiceChannelById(value) != null);
    }

    private static boolean usesDefaultAudioDeviceOrUnavailable(ScarletEventListener eventListener)
    {
        if (eventListener == null)
            return true;
        try
        {
            return eventListener.getTtsUseDefaultAudioDevice();
        }
        catch (RuntimeException ex)
        {
            return true;
        }
    }

    private static boolean hasCategory(Guild guild, String categorySf)
    {
        return guild != null && resolved(categorySf, value -> guild.getCategoryById(value) != null);
    }

    private static boolean hasRole(Guild guild, String roleSf)
    {
        return guild != null && hasConfiguredRole(roleSf, value -> guild.getRoleById(value) != null);
    }

    private static boolean hasConfiguredRole(String roleSf, Predicate<String> roleResolver)
    {
        return configured(roleSf)
            && !"1234567890".equals(roleSf)
            && resolved(roleSf, roleResolver);
    }

    private static boolean hasUsableAuxWebhook(ScarletDiscordJDA discord, String alias)
    {
        if (discord == null || !configured(alias)
            || discord.scarletAuxWh2webhookUrl == null
            || !configured(discord.scarletAuxWh2webhookUrl.get(alias)))
            return false;
        try
        {
            return discord.getOrCreateIWC(alias) != null;
        }
        catch (RuntimeException ex)
        {
            return false;
        }
    }

    private static boolean hasEffectiveAutoInviteGroup(String configuredGroupId, String fallbackGroupId)
    {
        String groupId = configuredGroupId;
        if (!configured(groupId) || Scarlet.DEFAULT_AUTO_INVITE_GROUP_ID.equals(groupId))
            groupId = fallbackGroupId;
        return configured(groupId);
    }

    private static boolean hasActiveMirroredSchedule(ScarletCalendar calendar)
    {
        if (calendar == null || calendar.eventSpecs == null)
            return false;
        try
        {
            for (ScarletCalendar.EventSpec spec : calendar.eventSpecs.values())
                if (spec != null && spec.active && spec.mirrorOnDiscord)
                    return true;
        }
        catch (RuntimeException ex)
        {
            return false;
        }
        return false;
    }

    private static boolean hasScheduleTargetGuild(ScarletCalendar calendar,
                                                   ScarletDiscordJDA discord,
                                                   Guild primaryGuild)
    {
        if (calendar == null || discord == null || primaryGuild == null)
            return false;
        try
        {
            String guildSf = configured(calendar.alternateGuildSf)
                ? calendar.alternateGuildSf
                : discord.guildSf;
            if (!configured(guildSf))
                return false;
            if (guildSf.equals(primaryGuild.getId()))
                return true;
            return resolveGuild(discord, guildSf) != null;
        }
        catch (RuntimeException ex)
        {
            return false;
        }
    }

    private static boolean hasModerationRoute(Set<String> routes,
                                               Set<String> secretRoutes,
                                               GroupAuditType type)
    {
        return type != null
            && (routes.contains(type.id) || secretRoutes.contains(type.id));
    }

    private static boolean hasAnyModerationRoute(Set<String> routes, Set<String> secretRoutes)
    {
        return hasModerationRoute(routes, secretRoutes, GroupAuditType.INSTANCE_WARN)
            || hasModerationRoute(routes, secretRoutes, GroupAuditType.INSTANCE_KICK)
            || hasModerationRoute(routes, secretRoutes, GroupAuditType.MEMBER_REMOVE)
            || hasModerationRoute(routes, secretRoutes, GroupAuditType.USER_BAN)
            || hasModerationRoute(routes, secretRoutes, GroupAuditType.USER_UNBAN);
    }

    private static boolean hasPairedModerationRoute(Set<String> routes,
                                                     Set<String> secretRoutes,
                                                     GroupAuditType first,
                                                     GroupAuditType second)
    {
        return (routes.contains(first.id) && routes.contains(second.id))
            || (secretRoutes.contains(first.id) && secretRoutes.contains(second.id));
    }

    private static boolean resolved(String value, Predicate<String> resolver)
    {
        if (!configured(value) || resolver == null)
            return false;
        try
        {
            return resolver.test(value);
        }
        catch (RuntimeException ex)
        {
            return false;
        }
    }

    private static boolean compiles(String regex)
    {
        if (!configured(regex))
            return false;
        try
        {
            Pattern.compile(regex);
            return true;
        }
        catch (PatternSyntaxException ex)
        {
            return false;
        }
    }

    private static boolean configured(String value)
    {
        return value != null && !value.trim().isEmpty();
    }

    private static void add(List<FeatureStatus> statuses, String name, boolean enabled)
    {
        statuses.add(new FeatureStatus(name, enabled));
    }

    private static String escapeHtml(String text)
    {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private ScarletFeatureUsageReport() {}
}

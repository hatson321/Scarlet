package net.sybyline.scarlet;

import java.awt.BorderLayout;
import net.sybyline.scarlet.util.FileBackups;
import java.awt.GraphicsEnvironment;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.vrchatapi.JSON;
import io.github.vrchatapi.api.GroupsApi;
import io.github.vrchatapi.model.Avatar;
import io.github.vrchatapi.model.CreateInstanceRequest;
import io.github.vrchatapi.model.CreateInstanceRequestRegion;
import io.github.vrchatapi.model.GroupAccessType;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.GroupRole;
import io.github.vrchatapi.model.InstanceContentSettings;
import io.github.vrchatapi.model.InstanceRegion;
import io.github.vrchatapi.model.InstanceType;
import io.github.vrchatapi.model.InventoryItem;
import io.github.vrchatapi.model.LimitedUserGroups;
import io.github.vrchatapi.model.ModelFile;
import io.github.vrchatapi.model.PerformanceRatings;
import io.github.vrchatapi.model.Print;
import io.github.vrchatapi.model.Prop;
import io.github.vrchatapi.model.User;
import io.github.vrchatapi.model.World;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IncomingWebhookClient;
import net.dv8tion.jda.api.entities.Invite;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.WebhookClient;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.ICommandReference;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.FluentRestAction;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AbstractWebhookMessageAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.AttachedFile;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.MarkdownUtil;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.messages.MessageCreateRequest;
import net.sybyline.scarlet.ScarletData.AuditEntryMetadata;
import net.sybyline.scarlet.ScarletData.InstanceEmbedMessage;
import net.sybyline.scarlet.ext.AvatarSearch;
import net.sybyline.scarlet.ext.VrcLaunch;
import net.sybyline.scarlet.ext.ManagedVrcClient;
import net.sybyline.scarlet.server.discord.LDaveSessionFactory;
import net.sybyline.scarlet.server.discord.DCommands;
import net.sybyline.scarlet.server.discord.DInteractions;
import net.sybyline.scarlet.server.discord.DPerms;
import net.sybyline.scarlet.util.LRUMap;
import net.sybyline.scarlet.util.Location;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.Pacer;
import net.sybyline.scarlet.util.UniqueStrings;
import net.sybyline.scarlet.util.VRChatHelpDeskURLs;
import net.sybyline.scarlet.util.VersionedFile;

public class ScarletDiscordJDA implements ScarletDiscord
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/JDA");
    static final String BLOCKED_AVATAR_SEARCH_HOST = blockedAvatarSearchHost();
    static final int DISCORD_ACCOUNT_AGE_ALERT_MAX_MINUTES = 365 * 24 * 60;
    static final Duration TICKET_TOOL_AUTO_RESPONSE_MAX_CHANNEL_AGE = Duration.ofMinutes(30L);
    static final String DEFAULT_TICKET_TOOL_CHANNEL_NAME_REGEX = "(?i).*ticket.*";
    static final String DEFAULT_TICKET_TOOL_NOTIFY_ROLE_SF = "1479385524786696242";
    static final String DEFAULT_TICKET_TOOL_AGE_VERIFICATION_MESSAGE =
        "If your ticket is regarding age verification;\n\n" +
        "You have 2 options.\n\n" +
        "Take a photo of your ID with everything but your birthday covered, with a piece of paper next to it with todays date, the server name, your Discord username, and your VRChat username.\n\n" +
        "If you're an NSFW content creator and have a verified Fansly account, you can write within your bio the server name. Once that is done, take a screenshot and also send the link of your profile here.\n\n" +
        "Please run `/link-vrchat-account` in this ticket to link your VRChat account to get a group invite after you've verified.\n\n" +
        "Note: you won't receive a VRChat group invite unless you've been verified and have the verified member role.";

    static String blockedAvatarSearchHost()
    {
        return new String(new char[] { 'a', 'p', 'i', '.', 'a', 'v', 'a', 't', 'a', 'r', 'r', 'e', 'c', 'o', 'v', 'e', 'r', 'y', '.', 'c', 'o', 'm' });
    }

    public ScarletDiscordJDA(Scarlet scarlet, File discordBotFile, File permsFile)
    {
        scarlet.splash.splashSubtext("Configuring Discord");
        this.scarlet = scarlet;
        this.token = scarlet.settings.new RegistryStringEncrypted("discord.token", true);
        this.discordBotFile = discordBotFile;
        this.audio = new JDAAudioSendingHandler();
        this.requestingEmail = scarlet.settings.new FileValuedStringPattern("vrchat_report_email", "VRChat Help Desk report email", "", ".+@.+", false);
        this.appendTemplateFooter = scarlet.settings.new FileValuedBoolean("vrchat_report_template_footer", "Append footer to template", true);
        this.bundleModerations_instanceKick2userBan = scarlet.settings.new FileValuedBoolean("discord_bundle_instance_kick_with_user_ban", "Discord: Bundle Instance Kicks with causing User Ban", true);
        this.moderationSummary_onlyActivity = scarlet.settings.new FileValuedBoolean("moderation_summary_only_activity", "Moderation summary: only list staff with activity", true);
        this.instanceJoinLeaveThumbnails = scarlet.settings.new FileValuedBoolean("discord_instance_join_leave_thumbnails", "Discord: Small profile thumbnails in instance join/leave logs", true);
        this.pingOnModeration_instanceWarn = scarlet.settings.new FileValuedBoolean("discord_ping_instance_warn", "Discord: Ping on Instance Warn", false);
        this.pingOnModeration_instanceKick = scarlet.settings.new FileValuedBoolean("discord_ping_instance_kick", "Discord: Ping on Instance Kick", false);
        this.pingOnModeration_memberRemove = scarlet.settings.new FileValuedBoolean("discord_ping_member_remove", "Discord: Ping on Member Remove", true);
        this.pingOnModeration_userBan = scarlet.settings.new FileValuedBoolean("discord_ping_user_ban", "Discord: Ping on User Ban", true);
        this.pingOnModeration_userUnban = scarlet.settings.new FileValuedBoolean("discord_ping_user_unban", "Discord: Ping on User Unban", false);
        this.pingOnOutstandingModeration_instanceWarn = scarlet.settings.new FileValuedBoolean("discord_ping_outstanding_instance_warn", "Discord: Ping on outstanding Instance Warn", false);
        this.pingOnOutstandingModeration_instanceKick = scarlet.settings.new FileValuedBoolean("discord_ping_outstanding_instance_kick", "Discord: Ping on outstanding Instance Kick", true);
        this.pingOnOutstandingModeration_memberRemove = scarlet.settings.new FileValuedBoolean("discord_ping_outstanding_member_remove", "Discord: Ping on outstanding Member Remove", false);
        this.pingOnOutstandingModeration_userBan = scarlet.settings.new FileValuedBoolean("discord_ping_outstanding_user_ban", "Discord: Ping on outstanding User Ban", true);
        this.pingOnOutstandingModeration_userUnban = scarlet.settings.new FileValuedBoolean("discord_ping_outstanding_user_unban", "Discord: Ping on outstanding User Unban", false);
        this.vrchatClient_launchOnInstanceCreate = scarlet.settings.new FileValuedBoolean("vrchat_client_launch_on_instance_create", "VRChat Client: Launch on Instance Create / Startup", false);
        this.vrchatClient_recoverStalledStartup = scarlet.settings.new FileValuedBoolean("vrchat_client_recover_stalled_startup", "VRChat Client: Recover stalled auto-launch", false);
        this.vrchatClient_forceStalledStartup = scarlet.settings.new FileValuedBoolean("vrchat_client_force_stalled_startup", "VRChat Client: Force-close stalled startup if normal close times out (Windows)", false);
        this.evidenceEnabled = scarlet.settings.new FileValuedBoolean("evidence_enabled", "Evidence submission", false);
        this.selectEvidenceRoot = scarlet.settings.new FileValuedVoid("Evidence root folder", "Select", this::selectEvidenceRoot);
        this.evidenceFilePathFormat = scarlet.settings.new FileValuedStringPattern("evidence_file_path_format", "Evidence file path format", "", ".+", false);
        this.avatarSearchProvidersEnabled = scarlet.settings.new FileValuedBoolean("custom_avatar_search_providers_enabled", "Use custom avatar search providers", false);
        this.avatarSearchExternalLookupsEnabled = scarlet.settings.new FileValuedConsentBoolean(AvatarSearch.IDENTIFIED_LOOKUP_CONSENT_KEY,
            "Allow external avatar database lookups");
        this.avatarSearchProviders = scarlet.settings.new FileValuedStringArrayPattern("custom_avatar_search_providers", "VRCX-compatible avatar search providers", AvatarSearch.URL_ROOTS.clone(), "https?://.+", false);
        this.resetAvatarSearchProviders = scarlet.settings.new FileValuedVoid("Avatar search providers", "Manage...", this::manageAvatarSearchProviders);
        this.load();
        // Stored denials must be readable before connecting a bot that can receive interactions.
        this.perms = new DPerms(permsFile);
        this.perms.registerOther(ScarletPermission.GROUPEX_BANS_MANAGE.id, ScarletPermission.GROUPEX_TAGS_EDIT.id,
            ScarletExitOperatorDiscord.PERMISSION);
        this.perms.load();
        this.interactions = new DInteractions(false, scarlet.exec);
        this.interactions.setModalAuthorization(this::authorizeModalSession);
        if (Features.DAVE_ENABLED)
            LDaveSessionFactory.configureLoggingIfAvailable();
        restShutdown = false;
        // Install our shutdown-aware failure handler before any RestAction can fire.
        // See handleRestFailure() for the rationale.  Set once, globally — JDA's
        // default is static and applies to every RestAction that doesn't pass its
        // own failure callback to .queue().
        RestAction.setDefaultFailure(ScarletDiscordJDA::handleRestFailure);
        JDA jda = null;
        String token0 = normalizeDiscordBotToken(this.token.getOrNull());
        if (!Objects.equals(token0, this.token.getOrNull()))
            this.token.set(token0);
        if (token0 != null && !token0.isEmpty()) try
        {
            logDiscordStartupContext(token0);
            AudioModuleConfig audioModuleConfig = new AudioModuleConfig();
            if (Features.DAVE_ENABLED && LDaveSessionFactory.isAvailable())
                audioModuleConfig = audioModuleConfig.withDaveSessionFactory(LDaveSessionFactory.getInstance());
            else if (Features.DAVE_ENABLED)
                LOG.warn("DAVE session factory unavailable on {}; continuing without Discord voice E2EE support",
                    net.sybyline.scarlet.util.Platform.describe());
            JDABuilder jdaBuilder = JDABuilder
            .createDefault(token0)
            .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
            .addEventListeners(new JDAEvents())
            .enableCache(CacheFlag.VOICE_STATE)
            .setAudioModuleConfig(audioModuleConfig);
            if (net.sybyline.scarlet.util.Platform.isPowerPC() && net.sybyline.scarlet.util.Platform.isPpcEdition())
            {
                // PowerPC edition (e.g. a Wii, no JIT): every gateway event is parsed in the bytecode
                // interpreter, and a busy server starves the heartbeat. Stop Discord sending event
                // types Scarlet never handles (no listeners exist for typing, reactions, emoji/sticker
                // updates, AutoMod or polls). Nothing Scarlet uses is removed.
                jdaBuilder
                    .disableIntents(
                        GatewayIntent.GUILD_MESSAGE_TYPING, GatewayIntent.DIRECT_MESSAGE_TYPING,
                        GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.DIRECT_MESSAGE_REACTIONS,
                        GatewayIntent.GUILD_EXPRESSIONS,
                        GatewayIntent.AUTO_MODERATION_CONFIGURATION, GatewayIntent.AUTO_MODERATION_EXECUTION,
                        GatewayIntent.GUILD_MESSAGE_POLLS, GatewayIntent.DIRECT_MESSAGE_POLLS)
                    .disableCache(CacheFlag.EMOJI, CacheFlag.STICKER);
                LOG.warn("PowerPC edition: not subscribing to Discord typing/reaction/emoji/sticker/AutoMod/poll events (Scarlet has no handlers for them)");
            }
            jda = jdaBuilder.build();
        }
        catch (InvalidTokenException ex)
        {
            LOG.error("Discord rejected the bot token during startup (length={}, fingerprint={}, platform={})",
                Integer.valueOf(token0.length()), fingerprintDiscordToken(token0), net.sybyline.scarlet.util.Platform.describe(), ex);
            if (this.scarlet.settings.requireConfirmYesNo("You can reset the bot token in the Settings page.", "Invalid bot token"))
            {
                ; // noop
            }
        }
        catch (IllegalArgumentException ex)
        {
            LOG.error("Discord JDA rejected startup configuration: {}", summarizeDiscordStartupFailure(ex), ex);
            if (this.scarlet.settings.requireConfirmYesNo(
                "Discord startup failed before login completed. The token may still be valid. See logs for the exact configuration error.",
                "Discord startup error"))
            {
                ; // noop
            }
        }
        this.jda = jda;
        this.livePlayerlist = new ScarletLivePlayerlist(scarlet);
        this.init();
    }

    @Override
    public void close() throws IOException
    {
        this.closeClientLaunchWorker();
        this.closeSummaryWorker();
        ScarletAuditFollowupWorker followup;
        synchronized (this)
        {
            this.auditFollowupClosed = true;
            followup = this.auditFollowupWorker;
        }
        if (followup != null) try { followup.close(); }
        catch (IOException ex)
        {
            LOG.error("Audit follow-up shutdown failed; offline migration was not started", ex);
            throw ex;
        }
        if (this.livePlayerlist != null)
            this.livePlayerlist.shutdown();
        restShutdown = true;
        if (this.scarlet.shouldPersistOnShutdown())
            this.save();
        else
            LOG.info("Skipping Discord settings save after migration bundle import");
        if (this.jda == null)
            return;
        this.jda.shutdown();
        try
        {
            // awaitShutdown returns TRUE when shutdown completed, FALSE on timeout.
            // We only want to force shutdownNow() when the graceful shutdown didn't
            // finish within the timeout — i.e. the negated branch.
            if (!this.jda.awaitShutdown(10_000L, TimeUnit.MILLISECONDS))
            {
                LOG.warn("JDA did not shut down gracefully within 10s; forcing shutdownNow()");
                this.jda.shutdownNow();
                this.jda.awaitShutdown();
            }
        }
        catch (InterruptedException iex)
        {
            LOG.error("Interrupted awaiting JDA shutdown");
            this.jda.shutdownNow();
            // Preserve the interrupt status for callers further up the stack.
            Thread.currentThread().interrupt();
        }
    }

    // Snapshot of JDA's shipped default failure handler — we delegate to it for
    // anything that isn't a shutdown-interrupt, so normal error handling (log at
    // ERROR with the stack trace) is preserved for real failures.  Captured once
    // at class-load time, before we overwrite the default via setDefaultFailure.
    private static final Consumer<? super Throwable> DEFAULT_REST_FAILURE_DELEGATE = RestAction.getDefaultFailure();
    private static volatile boolean restShutdown;

    /**
     * Custom default failure handler installed via {@link RestAction#setDefaultFailure}.
     * <p>
     * Filters out the noisy shutdown-time race where JDA's OkHttp client
     * interrupts in-flight HTTP/2 streams during {@code jda.shutdown()}, which
     * surfaces as {@code ErrorResponseException(-1, InterruptedIOException)}.
     * That's expected behaviour, not an error — but the shipped default handler
     * logs it at ERROR with a full stack trace, which users interpret as a bug.
     * <p>
     * Interruptions are quiet only after shutdown has actually started. Ordinary
     * socket timeouts still reach the normal failure handler.
     */
    private static void handleRestFailure(Throwable err)
    {
        handleRestFailure(err, restShutdown, DEFAULT_REST_FAILURE_DELEGATE);
    }

    static void handleRestFailure(Throwable err, boolean shuttingDown, Consumer<? super Throwable> delegate)
    {
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable t = err; t != null; t = t.getCause())
        {
            if (!visited.add(t)) break;
            if (shuttingDown && t instanceof InterruptedIOException)
            {
                LOG.debug("JDA REST call interrupted during confirmed shutdown");
                return;
            }
            // Guard against pathological cause-chain cycles.
            if (t.getCause() == t) break;
        }
        if (delegate != null)
        {
            delegate.accept(err);
        }
        else
        {
            LOG.error("JDA REST call failed", err);
        }
    }

    private static void logDiscordStartupContext(String token)
    {
        LOG.info("Discord startup context: platform={}, headless={}, tokenLength={}, tokenFingerprint={}, java={}, os.arch={}",
            net.sybyline.scarlet.util.Platform.describe(),
            Boolean.valueOf(GraphicsEnvironment.isHeadless()),
            Integer.valueOf(token.length()),
            fingerprintDiscordToken(token),
            System.getProperty("java.runtime.version"),
            System.getProperty("os.arch"));
    }

    private static String summarizeDiscordStartupFailure(Throwable err)
    {
        for (Throwable t = err; t != null; t = t.getCause())
        {
            if (t instanceof InvalidTokenException)
                return "invalid token";
            if (t instanceof UnknownHostException)
                return "DNS resolution failed for Discord (" + t.getMessage() + ")";
            if (t instanceof SSLHandshakeException)
                return "TLS handshake failed (" + t.getMessage() + ")";
            if (t instanceof SSLException)
                return "TLS/SSL failure (" + t.getMessage() + ")";
            if (t instanceof SocketTimeoutException)
                return "network timeout while contacting Discord (" + t.getMessage() + ")";
            if (t instanceof ConnectException)
                return "TCP connection to Discord failed (" + t.getMessage() + ")";
            if (t.getCause() == t)
                break;
        }
        String message = err.getMessage();
        return message != null && !message.trim().isEmpty()
            ? message
            : err.getClass().getName();
    }

    private static String normalizeDiscordBotToken(String token)
    {
        if (token == null)
            return null;
        String normalized = token
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\u2060", "")
            .replace("\r", "")
            .replace("\n", "")
            .trim();
        if (normalized.regionMatches(true, 0, "Bot ", 0, 4))
            normalized = normalized.substring(4).trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
            || (normalized.startsWith("'") && normalized.endsWith("'")))
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        normalized = normalized.replaceAll("\\p{Cntrl}", "");
        return normalized.isEmpty() ? null : normalized;
    }

    private static String fingerprintDiscordToken(String token)
    {
        if (token == null || token.isEmpty())
            return "empty";
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6 && i < digest.length; i++)
                sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        }
        catch (Exception ex)
        {
            return "sha256-error";
        }
    }

    final Scarlet scarlet;
    final File discordBotFile;
    private boolean configLoadFailed;
    final JDAAudioSendingHandler audio;
    final JDA jda;
    /** Public accessor for ScarletLivePlayerlist which lives in the same package. */
    public JDA jda() { return this.jda; }
    final ScarletLivePlayerlist livePlayerlist;
    final ScarletSettings.RegistryStringEncrypted token;
    volatile String guildSf;
    String audioChannelSf, discordActionLogChannelSf, opsAlertChannelSf, trainingChannelSf, evidenceRoot,
           ticketToolCategorySf, ticketToolChannelNameRegex, ticketToolNotifyRoleSf, ticketToolAgeVerificationMessage;
    boolean ticketToolAutoResponseEnabled;
    int discordAccountAgeAlertMinutes;
    Pattern ticketToolChannelNamePattern;
    final ScarletSettings.FileValued<String> requestingEmail,
                                    evidenceFilePathFormat;
    final ScarletSettings.FileValued<Boolean> appendTemplateFooter,
                                     bundleModerations_instanceKick2userBan,
                                     moderationSummary_onlyActivity,
                                     instanceJoinLeaveThumbnails,
                                     pingOnModeration_instanceWarn,
                                     pingOnModeration_instanceKick,
                                     pingOnModeration_memberRemove,
                                     pingOnModeration_userBan,
                                     pingOnModeration_userUnban,
                                     pingOnOutstandingModeration_instanceWarn,
                                     pingOnOutstandingModeration_instanceKick,
                                     pingOnOutstandingModeration_memberRemove,
                                     pingOnOutstandingModeration_userBan,
                                     pingOnOutstandingModeration_userUnban,
                                     vrchatClient_launchOnInstanceCreate,
                                     vrchatClient_recoverStalledStartup,
                                     vrchatClient_forceStalledStartup,
                                     evidenceEnabled,
                                     avatarSearchProvidersEnabled;
    final ScarletSettings.FileValuedConsentBoolean avatarSearchExternalLookupsEnabled;
    final ScarletSettings.FileValued<Void> selectEvidenceRoot,
                                  resetAvatarSearchProviders;
    final ScarletSettings.FileValued<String[]> avatarSearchProviders;
    final DInteractions interactions;
    void warnNotDefaultAllowed(DPerms.PermType permType, String... ids)
    {
        for (String id : this.perms.notDefaultAllowed(permType, ids))
            LOG.warn("Discord interaction `"+id+"` ("+permType+") is not in DPerms' default allow-list: it will be refused for everyone unless an admin grants it with /scarlet-discord-permissions");
    }
    final DPerms perms;
    final Map<String, InstanceCreation> instanceCreation = new ConcurrentHashMap<>();
    final Map<String, Map<String, Integer>> guildInviteUses = new ConcurrentHashMap<>();
    final Set<String> ticketToolAutoRespondedChannels = ConcurrentHashMap.newKeySet();
    final Set<String> verifyLinkPromptedChannels = ConcurrentHashMap.newKeySet();
    final Map<String, Command.Choice> userSf2lastEdited_groupId = new ConcurrentHashMap<>();
    List<Command> currentCommands = new ArrayList<>();
    Map<String, ICommandReference> currentSlashCommandsMap = new HashMap<>();
    Map<String, UniqueStrings> scarletPermission2roleSf = new HashMap<>();
    Map<String, String> scarletAuxWh2webhookUrl = new ConcurrentHashMap<>();
    Map<String, IncomingWebhookClient> scarletAuxWh2incomingWebhookClient = new ConcurrentHashMap<>();
    Map<String, String> auditType2channelSf = new ConcurrentHashMap<>();
    Map<String, String> auditExType2channelSf = new ConcurrentHashMap<>();
    Map<String, UniqueStrings> auditType2scarletAuxWh = new ConcurrentHashMap<>();
    Map<String, String> auditType2secretChannelSf = new ConcurrentHashMap<>();
    Map<String, String> auditExType2secretChannelSf = new ConcurrentHashMap<>();
    Map<String, Integer> auditType2color = new ConcurrentHashMap<>();
    List<Action> queuedActions = Collections.synchronizedList(new LinkedList<>());

    ScarletDiscordCommands discordCommands = null;
    ScarletDiscordUI discordUI = null;

    void init()
    {
        if (this.jda != null && (this.guildSf == null || this.guildSf.trim().isEmpty()))
            throw new IllegalStateException("Discord bot token is set but no guild snowflake is configured: set \"guildSf\" in discord_bot.json to your server ID "
                + "(or clear the bot token for staff mode). Previously this failed later with JDA's cryptic 'ID may not be empty'.");
        this.perms.getGuildSnowflakesMutable().add(this.guildSf);
        this.discordCommands = new ScarletDiscordCommands(this);
        this.discordUI = new ScarletDiscordUI(this);
        {
            this.perms.registerSuggestion(DPerms.PermType.BUTTON_PRESS, this.interactions.getButtonClickIds());
            this.perms.registerSuggestion(DPerms.PermType.STRING_SELECT, this.interactions.getStringSelectIds());
            this.perms.registerSuggestion(DPerms.PermType.ENTITY_SELECT, this.interactions.getEntitySelectIds());
            this.perms.registerSuggestion(DPerms.PermType.MODAL_SUBMIT, this.interactions.getModalSubmitIds());
            // Any registered interaction missing from DPerms' default allow-list is silently refused for
            // everyone without an explicit grant. Surface it loudly so it's caught before a release.
            this.warnNotDefaultAllowed(DPerms.PermType.BUTTON_PRESS, this.interactions.getButtonClickIds());
            this.warnNotDefaultAllowed(DPerms.PermType.STRING_SELECT, this.interactions.getStringSelectIds());
            this.warnNotDefaultAllowed(DPerms.PermType.ENTITY_SELECT, this.interactions.getEntitySelectIds());
            this.warnNotDefaultAllowed(DPerms.PermType.MODAL_SUBMIT, this.interactions.getModalSubmitIds());
        }
        if (this.jda == null)
        {
            this.setStaffMode();
            return;
        }
        try
        {
            this.jda.awaitReady();
        }
        catch (Exception ex)
        {
            if (ex instanceof IllegalStateException && JDA.Status.FAILED_TO_LOGIN == this.jda.getStatus() && "".equals(ScarletDiscordJDA.this.token.getOrNull()))
            {
                this.setStaffMode();
                return;
            }
            if (JDA.Status.FAILED_TO_LOGIN == this.jda.getStatus())
            {
                LOG.error("Discord login failed after JDA startup: {}", summarizeDiscordStartupFailure(ex), ex);
                throw new RuntimeException("Discord login failed after startup; check scarlet.log for the exact cause", ex);
            }
            throw new RuntimeException("Awaiting JDA", ex);
        }
        
        // Calling getGuildById fixes bot sometimes not rejoining voice channel on start
        Guild guild = ScarletDiscordJDA.this.jda.getGuildById(this.guildSf);
        if (guild == null)
        {
            LOG.error("Guild returned null for snowflake "+this.guildSf);
        }
        else
        {
            LOG.warn("Guild "+this.guildSf+": "+guild.getName()); 
            this.refreshGuildInviteCache(guild);
        }
        
        this.jda.retrieveCommands().queue($ ->
        {
            this.setCurrentCommands($);
            // Always reconcile: DCommands.delta only sends commands that are new or changed (identical
            // ones are skipped), so this is cheap. Gating it on a version bump meant a build that added
            // a command without changing the version number (e.g. /tag during 0.4.20 testing) never
            // registered it with Discord.
            this.updateCommandList();
        });
        this.jda.getPresence().setPresence(OnlineStatus.ONLINE, Activity.playing(Scarlet.NAME+" \u2014 Hatson321").withState(Scarlet.FORK_GITHUB_URL));
        this.audio.init();
        this.scarlet.exec.scheduleAtFixedRate(this::clearDeadPagination, 30_000L, 30_000L, TimeUnit.MILLISECONDS);
    }
    @Deprecated
    void scheduledEventsTest()
    {
        ScarletDiscordJDA
            .this
            .jda
            .getGuildById(this.guildSf)
//            .getScheduledEvents()
//            .stream()
//            .findFirst()
//            .get()
            .createScheduledEvent("name", "location", null, null)
            ;
    }

    @Override
    public void updateCommandList()
    {
        try
        {
            List<CommandData> datas = new ArrayList<>();
            datas.addAll(this.interactions.getCommandDataMutable());
            datas.add(DPerms.generateCommand("scarlet-discord-permissions"));
            DCommands.delta(
                this.currentCommands,
                datas,
                (     data) -> this.jda.upsertCommand(data).queue($ ->                      LOG.info("Upserted "+ $.getType()+" command "+  $.getName())),
                (cmd      ) -> this.jda.deleteCommandById(cmd.getId()).queue($ ->           LOG.info("Deleted "+cmd.getType()+" command "+cmd.getName())), 
                (cmd, data) -> this.jda.editCommandById(cmd.getType(), cmd.getId()).apply(data).queue($ -> LOG.info("Edited "+   $.getType()+" command "+  $.getName())),
                (cmd, data) -> {}
            );
            LOG.info("Queued commands update "+datas);
        }
        catch (Exception ex)
        {
            LOG.error("Exception queuing commands update", ex);
        }
    }

    @Override
    public long pollQueuedAction()
    {
        try
        {
            if (!this.queuedActions.isEmpty())
            {
                Action queuedAction = this.queuedActions.remove(0);
                switch (queuedAction.action)
                {
                case "ban":
                    return this.pollQueuedAction_ban(queuedAction);
                case "unban":
                    return this.pollQueuedAction_unban(queuedAction);
                }
            }
        }
        catch (Exception ex)
        {
            LOG.error("Exception polling queued action", ex);
        }
        return 10L;
    }
    long pollQueuedAction_ban(Action queuedAction)
    {
        if (queuedAction.requesterDiscordId == null || queuedAction.actorId == null
            || queuedAction.discordGuildId == null || queuedAction.vrchatGroupId == null
            || !queuedAction.discordGuildId.equals(this.guildSf)
            || !queuedAction.vrchatGroupId.equals(this.scarlet.vrc.groupId))
        {
            this.emitActionFailure(this.scarlet, queuedAction,
                "Queued ban was refused because its original requester or group context cannot be verified. Submit a new request.");
            return 30L;
        }
        VRChatBanAttempt attempt = this.executeVRChatBan(queuedAction.requesterDiscordId, queuedAction.actorId,
            queuedAction.targetId, DPerms.PermType.MODAL_SUBMIT, "vrchat-user-ban-multi");
        if (!attempt.succeeded())
        {
            this.emitActionFailure(this.scarlet, queuedAction, "%s", attempt.message);
            return 30L;
        }
        return 60L;
    }

    /** Retrieve instead of trusting the Member captured when a command or modal was opened. */
    Member retrieveBanRequester(String requesterDiscordId)
    {
        Guild guild = this.jda == null || this.guildSf == null ? null : this.jda.getGuildById(this.guildSf);
        return guild == null || requesterDiscordId == null ? null
            : guild.retrieveMemberById(requesterDiscordId).useCache(false).complete();
    }

    boolean authorizeModalSession(ModalInteractionEvent event, String operation)
    {
        // Discord supplies the submitting member's current roles in this interaction.
        // Do not reuse the Member captured when the form was opened or an older guild cache entry.
        Member member = event.getMember();
        return member != null && event.getGuild() != null && Objects.equals(this.guildSf, event.getGuild().getId())
            && Objects.equals(event.getUser().getId(), member.getId())
            && Objects.equals(this.guildSf, member.getGuild().getId()) && this.perms.checkSlash(member, operation);
    }

    static final class VRChatBanAccess
    {
        final String actorId, message;
        final boolean allowPermissionOverride;

        VRChatBanAccess(String actorId, boolean allowPermissionOverride, String message)
        {
            this.actorId = actorId;
            this.allowPermissionOverride = allowPermissionOverride;
            this.message = message;
        }

        boolean allowed() { return this.message == null; }
    }

    VRChatBanAccess resolveVRChatBanRequester(String requesterDiscordId, String expectedActorId,
        DPerms.PermType type, String operation)
    {
        try
        {
            Member requester = this.retrieveBanRequester(requesterDiscordId);
            if (requester == null || !requester.getId().equals(requesterDiscordId)
                || !this.guildSf.equals(requester.getGuild().getId()))
                return new VRChatBanAccess(null, false, "Cannot verify the requesting Discord member; no moderation action was performed.");
            boolean permitted = type == DPerms.PermType.SLASH_COMMAND
                ? this.perms.checkSlash(requester, operation) : this.perms.checkOperation(requester, type, operation);
            // A confirmation must not retain authority after its originating command is revoked.
            if ("immediate-ban-confirm".equals(operation))
                permitted &= this.perms.checkSlash(requester, "vrchat-user-ban");
            if (type == DPerms.PermType.MODAL_SUBMIT && "vrchat-user-ban-multi".equals(operation))
                permitted &= this.perms.checkSlash(requester, "vrchat-user-ban-multi");
            if (type == DPerms.PermType.MODAL_SUBMIT && "vrchat-user-unban-multi".equals(operation))
                permitted &= this.perms.checkSlash(requester, "vrchat-user-unban-multi");
            if (type == DPerms.PermType.MODAL_SUBMIT && "timed-ban-custom".equals(operation))
                permitted &= this.perms.checkOperation(requester, DPerms.PermType.BUTTON_PRESS, "timed-ban-custom");
            if (!permitted)
                return new VRChatBanAccess(null, false, "You no longer have access to this moderation command or interaction.");
            String actorId = this.scarlet.data.globalMetadata_getSnowflakeId(requesterDiscordId);
            if (actorId == null || (expectedActorId != null && !expectedActorId.equals(actorId)))
                return new VRChatBanAccess(null, false, "Your linked VRChat account is missing or changed; submit a new moderation request.");
            boolean override = this.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, requester, false);
            return new VRChatBanAccess(actorId, override, null);
        }
        catch (RuntimeException ex)
        {
            LOG.warn("Cannot refresh Discord ban-requester authorization; refusing the action");
            return new VRChatBanAccess(null, false, "Current requester permissions could not be verified; no moderation action was performed.");
        }
    }

    VRChatBanAccess checkVRChatBanAccess(String requesterDiscordId, String expectedActorId, String targetId,
        DPerms.PermType type, String operation)
    {
        if (targetId == null || ScarletSimulation.isTrainingId(targetId))
            return new VRChatBanAccess(null, false, "No real ban was performed: a valid non-training target is required.");
        VRChatBanAccess access = this.resolveVRChatBanRequester(requesterDiscordId, expectedActorId, type, operation);
        if (!access.allowed()) return access;
        try
        {
            ScarletBanAuthorization.Decision decision = this.scarlet.vrc.checkBanAuthorization(
                access.actorId, targetId, access.allowPermissionOverride);
            return decision.allowed() ? access : new VRChatBanAccess(access.actorId, false, decision.message());
        }
        catch (RuntimeException ex)
        {
            LOG.warn("Cannot verify current VRChat ban authorization; refusing the action");
            return new VRChatBanAccess(access.actorId, false, ScarletBanAuthorization.Decision.unverified().message());
        }
    }

    static final class VRChatBanAttempt
    {
        final String actorId, message;
        final ScarletVRChat.GroupModerationResult outcome;

        VRChatBanAttempt(String actorId, ScarletVRChat.GroupModerationResult outcome, String message)
        {
            this.actorId = actorId;
            this.outcome = outcome;
            this.message = message;
        }

        boolean succeeded() { return this.outcome == ScarletVRChat.GroupModerationResult.SUCCEEDED; }
    }

    /** All Discord-originated real bans pass this gate again at actual execution. */
    VRChatBanAttempt executeVRChatBan(String requesterDiscordId, String expectedActorId, String targetId,
        DPerms.PermType type, String operation)
    {
        return this.executeVRChatBan(requesterDiscordId, expectedActorId, targetId, type, operation, 0L);
    }

    VRChatBanAttempt executeVRChatBan(String requesterDiscordId, String expectedActorId, String targetId,
        DPerms.PermType type, String operation, long timedExpiry)
    {
        VRChatBanAccess access = this.checkVRChatBanAccess(requesterDiscordId, expectedActorId, targetId, type, operation);
        if (!access.allowed()) return new VRChatBanAttempt(access.actorId, null, access.message);
        String groupId = this.scarlet.vrc.groupId;
        ScarletPendingModActions.PendingReservation reservation;
        try
        {
            reservation = this.scarlet.pendingModActions.reservePending(GroupAuditType.USER_BAN, targetId, access.actorId, groupId);
        }
        catch (RuntimeException ex)
        {
            LOG.warn("Cannot persist ban reservation; refusing to submit the ban", ex);
            return new VRChatBanAttempt(access.actorId, null, "The moderation request could not be saved. No ban was submitted.");
        }
        if (reservation == null)
            return new VRChatBanAttempt(access.actorId, null, "This VRChat user has moderation pending or an automatic-unban record requiring review. Resolve it before retrying.");
        ScarletPendingModActions.TimedBan timer = null;
        if (timedExpiry > 0L)
        {
            try { timer = this.scarlet.pendingModActions.prepareTimedBan(groupId, targetId, timedExpiry, access.actorId); }
            catch (RuntimeException ex)
            {
                this.releaseBanReservation(reservation);
                return new VRChatBanAttempt(access.actorId, null, "The automatic-unban intent could not be saved. No ban was submitted.");
            }
        }
        ScarletVRChat.GroupBanResult result;
        try
        {
            result = this.scarlet.vrc.banFromGroupAs(groupId, access.actorId, targetId, access.allowPermissionOverride);
        }
        catch (RuntimeException ex)
        {
            LOG.warn("Ban request ended without a confirmed result; retaining its bounded pending reservation");
            return new VRChatBanAttempt(access.actorId, ScarletVRChat.GroupModerationResult.UNKNOWN,
                timer == null ? "The ban outcome is unknown. Check the group/audit log before retrying; the pending request will expire."
                    : "The ban outcome is unknown. No automatic unban is confirmed; manually review the group ban and retained timer before retrying.");
        }
        if (!result.authorization.allowed())
        {
            String retainedIntent = this.discardUnsubmittedTimer(targetId, timer);
            this.releaseBanReservation(reservation);
            return new VRChatBanAttempt(access.actorId, null, result.authorization.message() + retainedIntent);
        }
        if (result.outcome == ScarletVRChat.GroupModerationResult.REJECTED)
        {
            String retainedIntent = this.discardUnsubmittedTimer(targetId, timer);
            this.releaseBanReservation(reservation);
            return new VRChatBanAttempt(access.actorId, result.outcome, "VRChat rejected the ban request." + retainedIntent);
        }
        if (result.outcome != ScarletVRChat.GroupModerationResult.SUCCEEDED)
            return new VRChatBanAttempt(access.actorId, ScarletVRChat.GroupModerationResult.UNKNOWN,
                timer == null ? "The ban outcome is unknown. Check the group/audit log before retrying; the pending request will expire."
                    : "The ban outcome is unknown. No automatic unban is confirmed; manually review the group ban and retained timer before retrying.");
        if (timer != null && !this.scarlet.pendingModActions.activateTimedBan(targetId, timer, result.serverBanAt, result.serverActorId))
            return new VRChatBanAttempt(access.actorId, null,
                "The ban succeeded, but automatic-unban activation could not be safely verified or saved. Manually review or lift the ban; no automatic unban is guaranteed.");
        return new VRChatBanAttempt(access.actorId, result.outcome, null);
    }

    private void releaseBanReservation(ScarletPendingModActions.PendingReservation reservation)
    {
        try { this.scarlet.pendingModActions.releasePending(reservation); }
        catch (RuntimeException ex) { LOG.warn("Completed ban reservation could not be cleared; retained for recovery", ex); }
    }

    private String discardUnsubmittedTimer(String targetId, ScarletPendingModActions.TimedBan timer)
    {
        if (timer == null) return "";
        if (!java.util.Objects.equals(timer.groupId, this.scarlet.vrc.groupId))
            return " The group changed; the original inactive automatic-unban intent was retained for manual review.";
        if (!this.scarlet.pendingModActions.removeTimedBan(targetId, timer))
        {
            LOG.warn("Unsubmitted timed-ban intent could not be cleared; retained inactive for manual review");
            return " The inactive automatic-unban intent could not be cleared; it was retained for retry or manual review.";
        }
        return "";
    }

    long pollQueuedAction_unban(Action queuedAction)
    {
        if (!this.queuedUnbanContextMatches(queuedAction))
        {
            this.emitActionFailure(this.scarlet, queuedAction,
                "Queued unban was refused because its original requester, linked account or group context cannot be verified. Legacy/unbound requests must be submitted again.");
            return 30L;
        }
        ScarletPendingModActions.PendingReservation reservation = null;
        boolean submitted = false;
        try
        {
            VRChatBanAccess access = this.resolveVRChatBanRequester(queuedAction.requesterDiscordId, queuedAction.actorId,
                DPerms.PermType.MODAL_SUBMIT, "vrchat-user-unban-multi");
            if (!access.allowed()) throw new IllegalStateException(access.message);
            String groupId = queuedAction.vrchatGroupId, botId = this.scarlet.vrc.currentUserId;
            Group group = ScarletBanSnapshot.group(this.scarlet.vrc.readBanGroup(groupId), groupId);
            GroupMember actor = ScarletBanSnapshot.member(this.scarlet.vrc.readBanMember(groupId, queuedAction.actorId), groupId, queuedAction.actorId);
            GroupMember bot = ScarletBanSnapshot.member(this.scarlet.vrc.readBanMember(groupId, botId), groupId, botId);
            GroupMember target = ScarletBanSnapshot.member(this.scarlet.vrc.readBanMember(groupId, queuedAction.targetId), groupId, queuedAction.targetId);
            // Refresh Discord again after the blocking VRChat reads. A revoked override cannot survive them.
            access = this.resolveVRChatBanRequester(queuedAction.requesterDiscordId, queuedAction.actorId,
                DPerms.PermType.MODAL_SUBMIT, "vrchat-user-unban-multi");
            if (!access.allowed()) throw new IllegalStateException(access.message);
            ScarletBanAuthorization.Decision decision = ScarletBanAuthorization.evaluateUnban(group,
                queuedAction.actorId, botId, actor, bot, access.allowPermissionOverride);
            if (!decision.allowed()) throw new IllegalStateException(decision.message());
            if (target == null || target.getMembershipStatus() != GroupMemberStatus.BANNED)
                throw new IllegalStateException("Queued unban was not sent: the target is not currently verified as banned.");
            if (!this.queuedUnbanContextMatches(queuedAction) || !Objects.equals(botId, this.scarlet.vrc.currentUserId))
                throw new IllegalStateException("Queued unban context changed during verification. Submit a new request.");
            reservation = this.scarlet.pendingModActions.reservePending(GroupAuditType.USER_UNBAN,
                queuedAction.targetId, queuedAction.actorId, groupId);
            if (reservation == null)
                throw new IllegalStateException("This user already has a pending moderation request. No unban was sent.");
            access = this.resolveVRChatBanRequester(queuedAction.requesterDiscordId, queuedAction.actorId,
                DPerms.PermType.MODAL_SUBMIT, "vrchat-user-unban-multi");
            if (!access.allowed()) throw new IllegalStateException(access.message);
            decision = ScarletBanAuthorization.evaluateUnban(group, queuedAction.actorId, botId, actor, bot,
                access.allowPermissionOverride);
            if (!decision.allowed()) throw new IllegalStateException(decision.message());
            // Persistence can block too. Never send to a replacement group/account after it returns.
            if (!this.queuedUnbanContextMatches(queuedAction) || !Objects.equals(botId, this.scarlet.vrc.currentUserId))
                throw new IllegalStateException("Queued unban context changed before submission. Submit a new request.");
            submitted = true;
            ScarletVRChat.GroupModerationResult result = this.scarlet.vrc.unbanFromGroupDetailed(groupId, queuedAction.targetId);
            if (result == ScarletVRChat.GroupModerationResult.SUCCEEDED) return 60L;
            if (result == ScarletVRChat.GroupModerationResult.REJECTED)
            {
                submitted = false;
                throw new IllegalStateException("VRChat rejected the queued unban request.");
            }
            throw new IllegalStateException("The queued unban outcome is unknown. Check the group/audit log before retrying; its pending reservation was retained.");
        }
        catch (Exception ex)
        {
            if (!submitted && reservation != null) this.releaseBanReservation(reservation);
            String message = ex instanceof IllegalStateException ? ex.getMessage()
                : submitted ? "The queued unban outcome is unknown; its pending reservation was retained for reconciliation."
                    : "Current queued-unban authority could not be verified. No unban was sent; submit a new request when verification is available.";
            this.emitActionFailure(this.scarlet, queuedAction, "%s", message);
            return 30L;
        }
    }

    private boolean queuedUnbanContextMatches(Action action)
    {
        return action != null && !MiscUtils.blank(action.requesterDiscordId) && !MiscUtils.blank(action.actorId)
            && !MiscUtils.blank(action.discordGuildId) && !MiscUtils.blank(action.vrchatGroupId)
            && !MiscUtils.blank(action.targetId) && !ScarletSimulation.isTrainingId(action.targetId)
            && action.discordGuildId.equals(this.guildSf) && action.vrchatGroupId.equals(this.scarlet.vrc.groupId)
            && action.actorId.equals(this.scarlet.data.globalMetadata_getSnowflakeId(action.requesterDiscordId));
    }

    String help_staffListAdd_mention = "`/staff-list add`";
    String help_scarletDiscordPermissions_mention = "`/scarlet-discord-permissions`";
    String linkedIdsInfo(ScarletData.AuditEntryMetadata auditEntryMeta)
    {
        String id = auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorId : auditEntryMeta.entry.getActorId(),
               display = auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorDisplayName : auditEntryMeta.entry.getActorDisplayName(),
               kind = auditEntryMeta.hasAuxActor() ? "aux actor" : "actor";
        return "Unknown Discord id for "+kind+" "+display+"\n(Use "+this.help_staffListAdd_mention+" `vrchat-user`:*"+id+"* `discord-user`:*<your Discord user>* to accomplish this)";
    }
    String linkedIdsReply(UserSnowflake member)
    {
        return "You must have linked ids to perform this action.\n(Use "+this.help_staffListAdd_mention+" `vrchat-user`:*<your VRChat user ID>* `discord-user`:"+member.getAsMention()+" to accomplish this)";
    }
    String permsReply(UserSnowflake member)
    {
        return "You do not have permission to perform this action.\n(Have your admin use "+this.help_scarletDiscordPermissions_mention+" to assign permissions)";
    }
    static final Predicate<ICommandReference> IS_SLASH = $ -> !($ instanceof Command) || Command.Type.SLASH == ((Command)$).getType();
    void setCurrentCommands(List<Command> currentCommands)
    {
        this.perms.buildSuggestions(currentCommands);
        this.currentCommands = currentCommands;
        this.currentSlashCommandsMap = this.streamCommandTree().filter(IS_SLASH).collect(Collectors.toMap(ICommandReference::getFullCommandName, Function.identity()));
        this.help_staffListAdd_mention = this.getCommandTreeSlash("staff-list add").map(ICommandReference::getAsMention).orElse("`/staff-list add`");
    }
    public Stream<ICommandReference> streamCommandTree()
    {
        return this.currentCommands.stream().flatMap($ -> Stream.concat(Stream.concat(Stream.of($), $.getSubcommands().stream()), $.getSubcommandGroups().stream().flatMap($$ -> Stream.concat(Stream.of($$), $$.getSubcommands().stream()))));
    }
    public Optional<ICommandReference> getCommandTreeSlash(String fullCommandName)
    {
        return Optional.ofNullable(this.currentSlashCommandsMap.get(fullCommandName));
    }

    void setStaffMode()
    {
        LOG.warn("No Discord bot token: entering staff mode");
        this.scarlet.staffMode = true;
        this.scarlet.ui.jframe(jframe -> jframe.setTitle(Scarlet.NAME+" (staff mode)"));
    }

    void resetAvatarSearchProviders()
    {
        this.avatarSearchProviders.set(null, "discord");
    }

    /** Human-readable Discord connection status for the diagnostics view. */
    public String connectionStatus()
    {
        return this.jda == null ? "not started" : String.valueOf(this.jda.getStatus());
    }

    /**
     * Settings dialog listing every known avatar search provider (built-in defaults
     * plus stored customs) as checkboxes, with add-custom and restore-defaults.
     * Runs on the modal executor thread like other Settings button actions.
     * On save, provider backoffs are cleared and only players whose avatars still
     * lack data get rescanned, keeping network usage and rate limits down.
     */
    void manageAvatarSearchProviders()
    {
        String[] effective = AvatarSearch.selectedProviders(this.avatarSearchProvidersEnabled.get(),
            this.avatarSearchProviders.get(), BLOCKED_AVATAR_SEARCH_HOST);
        java.util.Set<String> effectiveSet = new java.util.LinkedHashSet<>(java.util.Arrays.asList(effective));
        java.util.LinkedHashSet<String> known = new java.util.LinkedHashSet<>(java.util.Arrays.asList(AvatarSearch.URL_ROOTS));
        String[] stored = this.avatarSearchProviders.get();
        if (stored != null)
            for (String url : stored)
                if (url != null && !url.trim().isEmpty())
                    known.add(url);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel headerLabel = new JLabel("<html><body style='width: 440px'>External avatar databases are off until you allow them. "
            + "When enabled, manual searches and automatic avatar-change, image-match, missing-info and rescan lookups "
            + "can send avatar names and creator/file IDs to the selected services. Services also see your IP address "
            + "and Scarlet version. The User-Agent also includes the configured group ID, signed-in primary "
            + "VRChat account ID and the maintainer's public support contact. Login credentials are not included. "
            + "Explicit permission is required even if you enabled the earlier checkbox. "
            + "Uncheck permission or every provider to stop future lookups (requests already in flight may finish). "
            + "Only public Internet destinations are allowed. Saving may rescan missing avatar information.</body></html>");
        headerLabel.setAlignmentX(0.0F);
        panel.add(headerLabel);
        panel.add(Box.createVerticalStrut(10));
        JCheckBox allowLookups = new JCheckBox("Allow external avatar lookups and group/account identification", Boolean.TRUE.equals(this.avatarSearchExternalLookupsEnabled.get()));
        allowLookups.setAlignmentX(0.0F);
        panel.add(allowLookups);
        panel.add(Box.createVerticalStrut(10));
        JPanel checksPanel = new JPanel();
        checksPanel.setLayout(new BoxLayout(checksPanel, BoxLayout.Y_AXIS));
        checksPanel.setAlignmentX(0.0F);
        java.util.Map<String, JCheckBox> checks = new java.util.LinkedHashMap<>();
        for (String url : known)
        {
            JCheckBox check = new JCheckBox(url, effectiveSet.contains(url));
            check.setAlignmentX(0.0F);
            checks.put(url, check);
            checksPanel.add(check);
        }
        panel.add(checksPanel);
        panel.add(Box.createVerticalStrut(10));
        JPanel addRow = new JPanel(new BorderLayout(8, 0));
        addRow.setAlignmentX(0.0F);
        JTextField addField = new JTextField();
        JButton addButton = new JButton("Add");
        addRow.add(new JLabel("Custom provider URL "), BorderLayout.WEST);
        addRow.add(addField, BorderLayout.CENTER);
        addRow.add(addButton, BorderLayout.EAST);
        panel.add(addRow);
        panel.add(Box.createVerticalStrut(6));
        JButton restoreDefaults = new JButton("Restore defaults");
        restoreDefaults.setAlignmentX(0.0F);
        panel.add(restoreDefaults);
        addButton.addActionListener($ ->
        {
            String url = addField.getText().trim();
            if (!url.matches("https?://.+"))
            {
                JOptionPane.showMessageDialog(panel, "Enter a full http(s) URL.", "Avatar search providers", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!checks.containsKey(url))
            {
                JCheckBox check = new JCheckBox(url, true);
                check.setAlignmentX(0.0F);
                checks.put(url, check);
                checksPanel.add(check);
                checksPanel.revalidate();
                java.awt.Window window = SwingUtilities.getWindowAncestor(panel);
                if (window != null)
                    window.pack();
            }
            addField.setText("");
        });
        restoreDefaults.addActionListener($ ->
        {
            java.util.Set<String> defaults = new java.util.HashSet<>(java.util.Arrays.asList(AvatarSearch.URL_ROOTS));
            checks.forEach((url, check) -> check.setSelected(defaults.contains(url)));
        });

        if (JOptionPane.showConfirmDialog(null, panel, "Avatar search providers",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;

        java.util.List<String> selected = new java.util.ArrayList<>();
        checks.forEach((url, check) ->
        {
            if (check.isSelected())
                selected.add(url);
        });
        if (allowLookups.isSelected() && selected.isEmpty())
            JOptionPane.showMessageDialog(panel,
                "No avatar providers are selected. Watched-avatar matching will remain unavailable until you select a provider.",
                "Avatar search providers", JOptionPane.WARNING_MESSAGE);
        java.util.Set<String> defaults = new java.util.LinkedHashSet<>(java.util.Arrays.asList(AvatarSearch.URL_ROOTS));
        // Keep requests disabled while committing the selected destinations, then apply permission last.
        this.avatarSearchExternalLookupsEnabled.set(Boolean.FALSE, "manage-providers");
        boolean isDefaultSelection = selected.size() == defaults.size() && defaults.containsAll(selected);
        if (isDefaultSelection)
        {
            this.avatarSearchProvidersEnabled.set(Boolean.FALSE, "manage-providers");
            this.avatarSearchProviders.set(null, "manage-providers");
        }
        else
        {
            this.avatarSearchProviders.set(selected.toArray(new String[0]), "manage-providers");
            this.avatarSearchProvidersEnabled.set(Boolean.TRUE, "manage-providers");
        }
        this.avatarSearchExternalLookupsEnabled.setWithConsent(Boolean.valueOf(allowLookups.isSelected()),
            "manage-providers", net.sybyline.scarlet.ui.AvatarProviderConsentDialog::confirm);
        // A deliberate settings change: forget prior provider backoffs, then rescan
        // only the players whose avatars still lack data (per-provider caching means
        // unchanged providers answer from cache, so this costs almost nothing).
        AvatarSearch.clearProviderBackoffs();
        this.scarlet.eventListener.rescanAvatarInfo();
    }

    String[] getAvatarSearchProviders()
    {
        return AvatarSearch.effectiveProviders(Features.AVATAR_SEARCH_ENABLED, this.avatarSearchExternalLookupsEnabled.get(),
            this.avatarSearchProvidersEnabled.get(), this.avatarSearchProviders.get(), BLOCKED_AVATAR_SEARCH_HOST);
    }

    void selectEvidenceRoot()
    {
        if (GraphicsEnvironment.isHeadless())
        {
            this.scarlet.settings.requireInputAsync("Enter new evidence root folder", false, string ->
            {
                File file = new File(string);
                if (file.isDirectory())
                {
                    this.evidenceRoot = file.getAbsolutePath();
                }
            });
        }
        else this.scarlet.execModal.submit(() ->
        {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select a Folder");
            chooser.setDialogType(JFileChooser.SAVE_DIALOG);
            chooser.setMultiSelectionEnabled(false);
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (this.evidenceRoot != null && !this.evidenceRoot.trim().isEmpty())
            {
                chooser.setSelectedFile(new File(this.evidenceRoot));
            }
            int result = chooser.showOpenDialog(null);
            if (result == JFileChooser.APPROVE_OPTION)
            {
                this.evidenceRoot = chooser.getSelectedFile().getAbsolutePath();
            }
        });
    }

    IncomingWebhookClient getOrCreateIWC(String scarletAuxWh)
    {
        if (scarletAuxWh == null)
            return null;
        IncomingWebhookClient iwc = this.scarletAuxWh2incomingWebhookClient.get(scarletAuxWh);
        if (iwc != null)
            return iwc;
        String iwcUrl = this.scarletAuxWh2webhookUrl.get(scarletAuxWh);
        if (iwcUrl != null) try
        {
            iwc = WebhookClient.createClient(this.jda, iwcUrl);
            this.scarletAuxWh2incomingWebhookClient.put(scarletAuxWh, iwc);
        }
        catch (Exception ex)
        {
            LOG.error("Invalid Discord webhook configuration; credential value omitted. Destination is unavailable.");
        }
        return iwc;
    }

    @Override
    public boolean submitAudio(File file)
    {
        return this.audio.submitAudio(file);
    }

    static final int BYTES_PER_20MS = 3840; // 20ms * (48000 frames/second) * (2 samples/frame) * (2 bytes/sample)
    class JDAAudioSendingHandler implements AudioSendHandler
    {
        boolean submitAudio(File file)
        {
            AudioManager audioManager = this.audioManager;
            if (audioManager == null || !audioManager.isConnected())
            {
                LOG.warn("TTS: Cannot submit audio - AudioManager is null or not connected. audioManager={}, isConnected={}",
                    audioManager, audioManager != null ? audioManager.isConnected() : "N/A");
                return false;
            }
            List<ByteBuffer> buffersToAdd = new ArrayList<>();
            try (InputStream fis = new FileInputStream(file))
            {
                try (AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(fis)))
                {
                    AudioInputStream ais0 = null;
                    try
                    {
                        if (AudioSendHandler.INPUT_FORMAT.matches(ais.getFormat()))
                        {
                            ais0 = ais;
                        }
                        else if (AudioSystem.isConversionSupported(AudioSendHandler.INPUT_FORMAT, ais.getFormat()))
                        {
                            ais0 = AudioSystem.getAudioInputStream(AudioSendHandler.INPUT_FORMAT, ais);
                        }
                        else
                        {
                            LOG.warn("TTS: Audio format conversion not supported: source={}, target={}", ais.getFormat(), AudioSendHandler.INPUT_FORMAT);
                            return false;
                        }
                        // FIX 1: Use read() return value instead of available() to detect EOF.
                        // available() is unreliable on converted AudioInputStreams and can return 0
                        // even when data remains, causing the entire audio to be silently skipped.
                        // FIX 2: Track how many bytes were actually read into each buffer so that
                        // the last (partial) chunk is zero-padded correctly instead of being
                        // submitted as a full buffer of uninitialized/garbage bytes.
                        while (true)
                        {
                            byte[] buffer = new byte[BYTES_PER_20MS];
                            int total = 0;
                            int read;
                            while (total < BYTES_PER_20MS && (read = ais0.read(buffer, total, BYTES_PER_20MS - total)) != -1)
                            {
                                total += read;
                            }
                            if (total == 0)
                                break; // true EOF - no more data
                            // If total < BYTES_PER_20MS, the buffer is already zero-padded (new byte[])
                            // so the partial last frame is safe to submit as-is.
                            buffersToAdd.add(ByteBuffer.wrap(buffer));
                            if (total < BYTES_PER_20MS)
                                break; // partial read means EOF was reached
                        }
                    }
                    finally
                    {
                        if (ais0 != ais)
                            MiscUtils.close(ais0);
                    }
                }
            }
            catch (Exception ex)
            {
                LOG.error("Exception loading TTS audio", ex);
                return false;
            }
            if (buffersToAdd.isEmpty())
            {
                LOG.warn("TTS: No audio buffers loaded from file: {}", file);
                return true;
            }
            LOG.info("TTS: Queuing {} audio buffers (~{}ms) from file: {}", buffersToAdd.size(), buffersToAdd.size() * 20, file);
            synchronized (this)
            {
                this.buffers.addAll(buffersToAdd);
            }
            return true;
        }

        void init()
        {
            String guildSf = ScarletDiscordJDA.this.guildSf;
            Guild guild = ScarletDiscordJDA.this.jda.getGuildById(guildSf);
            if (guild == null)
            {
                LOG.warn("Audio: Guild returned null for snowflake "+guildSf);
                return;
            }
            AudioManager audioManager = guild.getAudioManager();
            audioManager.setAutoReconnect(true);
            audioManager.setConnectionListener(null);
            audioManager.setConnectTimeout(10_000L);
            audioManager.setReceivingHandler(null);
            audioManager.setSelfDeafened(true);
            audioManager.setSelfMuted(false);
            audioManager.setSendingHandler(this);
            // audioManager.setSpeakingMode(SpeakingMode.);
            this.audioManager = audioManager;
            this.updateChannel();
        }

        void updateChannel()
        {
            String guildSf = ScarletDiscordJDA.this.guildSf,
                   audioChannelSf = ScarletDiscordJDA.this.audioChannelSf;
            Guild guild = ScarletDiscordJDA.this.jda.getGuildById(guildSf);
            AudioManager audioManager = this.audioManager;
            LOG.info("TTS Audio: updateChannel called - guildSf={}, audioChannelSf={}, audioManager={}, guild={}", 
                guildSf, audioChannelSf, audioManager != null ? "present" : "null", guild != null ? "found" : "null");
            if (audioChannelSf == null)
            {
                this.audioChannel = null;
                if (audioManager != null)
                {
                    LOG.info("TTS Audio: Closing audio connection (audioChannelSf is null)");
                    audioManager.closeAudioConnection();
                    this.buffers.clear();
                }
            }
            else
            {
                AudioChannel audioChannel = guild.getVoiceChannelById(audioChannelSf);
                this.audioChannel = audioChannel;
                LOG.info("TTS Audio: AudioChannel lookup result - audioChannel={}", audioChannel != null ? audioChannel.getId() : "null");
                if (audioManager != null)
                {
                    if (audioChannel != null)
                    {
                        LOG.info("TTS Audio: Opening audio connection to channel {}", audioChannel.getId());
                        audioManager.openAudioConnection(audioChannel);
                        this.buffers.clear();
                    }
                    else
                    {
                        LOG.warn("TTS Audio: AudioChannel not found for sf={}, closing connection", audioChannelSf);
                        audioManager.closeAudioConnection();
                        this.buffers.clear();
                    }
                }
            }
        }

        AudioManager audioManager;
        AudioChannel audioChannel;
        final Queue<ByteBuffer> buffers = new ConcurrentLinkedQueue<>();

        @Override
        public boolean canProvide()
        {
            return !this.buffers.isEmpty();
        }

        @Override
        public ByteBuffer provide20MsAudio()
        {
            return this.buffers.poll();
        }

        @Override
        public boolean isOpus()
        {
            return false;
        }

    }

    public static class JDASettingsSpec
    {
        @Deprecated
        public String token = null;
        public String guildSf = null,
                      audioChannelSf = null,
                      discordActionLogChannelSf = null,
                      opsAlertChannelSf = null,
                      trainingChannelSf = null,
                      evidenceRoot = null,
                      ticketToolCategorySf = null,
                      ticketToolChannelNameRegex = DEFAULT_TICKET_TOOL_CHANNEL_NAME_REGEX,
                      ticketToolNotifyRoleSf = DEFAULT_TICKET_TOOL_NOTIFY_ROLE_SF,
                      ticketToolAgeVerificationMessage = DEFAULT_TICKET_TOOL_AGE_VERIFICATION_MESSAGE;
        public boolean ticketToolAutoResponseEnabled = false;
        public int discordAccountAgeAlertMinutes = 0;
        public Map<String, UniqueStrings> scarletPermission2roleSf = new HashMap<>();
        public Map<String, String> scarletAuxWh2webhookUrl = new HashMap<>();
        public Map<String, String> auditType2channelSf = new HashMap<>();
        public Map<String, String> auditExType2channelSf = new HashMap<>();
        public Map<String, UniqueStrings> auditType2scarletAuxWh = new HashMap<>();
        public Map<String, String> auditType2secretChannelSf = new HashMap<>();
        public Map<String, String> auditExType2secretChannelSf = new HashMap<>();
        public Map<String, String> auditType2color = new HashMap<>();
        public List<Action> queuedActions = new ArrayList<>();

        /** Blanks the Discord channel/webhook routing (audit->channel maps, the named channel
         *  fields, evidence root, ticket category, aux webhooks). Keeps the bot token, guild,
         *  roles, colours, and other non-channel settings. Used when cloning or importing into
         *  a fresh Discord setup so channels are re-pointed rather than inherited by mistake. */
        public void scrubChannels()
        {
            this.audioChannelSf = null;
            this.discordActionLogChannelSf = null;
            this.opsAlertChannelSf = null;
            this.trainingChannelSf = null;
            this.evidenceRoot = null;
            this.ticketToolCategorySf = null;
            this.auditType2channelSf = new ConcurrentHashMap<>();
            this.auditExType2channelSf = new ConcurrentHashMap<>();
            this.auditType2secretChannelSf = new ConcurrentHashMap<>();
            this.auditExType2secretChannelSf = new ConcurrentHashMap<>();
            this.scarletAuxWh2webhookUrl = new ConcurrentHashMap<>();
            this.auditType2scarletAuxWh = new ConcurrentHashMap<>();
        }
    }

    // Loads a discord config file, blanks its channel/webhook routing, and saves it back.
    // Used by migration import (when "carry over channels" is declined) and group cloning.
    // A safe no-op if the file is missing or unreadable.
    public static void scrubDiscordChannels(File discordConfigFile)
    {
        if (discordConfigFile == null || !discordConfigFile.isFile())
            return;
        JDASettingsSpec spec;
        try (java.io.Reader fr = java.nio.file.Files.newBufferedReader(discordConfigFile.toPath(), java.nio.charset.StandardCharsets.UTF_8))
        {
            spec = Scarlet.GSON_PRETTY.fromJson(fr, JDASettingsSpec.class);
        }
        catch (Exception ex)
        {
            LOG.error("Could not read discord config to scrub channels: {}", discordConfigFile, ex);
            return;
        }
        if (spec == null)
            return;
        spec.scrubChannels();
        try
        {
            FileBackups.write(discordConfigFile, fw -> Scarlet.GSON_PRETTY.toJson(spec, JDASettingsSpec.class, fw));
        }
        catch (Exception ex)
        {
            LOG.error("Could not write scrubbed discord config: {}", discordConfigFile, ex);
        }
    }

    public synchronized void load()
    {
        JDASettingsSpec spec;
        try
        {
            if (ScarletData.existingRecord(this.discordBotFile))
                spec = readDiscordSettings(this.discordBotFile);
            else
            {
                if (this.configLoadFailed)
                    throw new IOException("A previously unreadable Discord config must be restored before loading again");
                spec = new JDASettingsSpec();
            }
        }
        catch (Exception ex)
        {
            this.configLoadFailed = true;
            throw new IllegalStateException("Discord configuration could not be read. The existing file was preserved; restore or repair it before restarting Discord.", ex);
        }
        this.configLoadFailed = false;
        
        boolean save = false;
        
        String token0 = normalizeDiscordBotToken(spec.token);
        if (token0 == null)
        {
            token0 = normalizeDiscordBotToken(this.token.getOrNull());
            if (token0 == null)
            {
                token0 = normalizeDiscordBotToken(this.scarlet.settings.requireInput("Discord bot token (leave empty for staff mode)", true));
            }
            else
            {
                spec.token = null;
                save = true;
            }
        }
        this.scarlet.settings.new FileValuedVoid("Discord bot token", "Reset", () -> 
            this.scarlet.settings.requireConfirmYesNoAsync("Are you sure you want to reset the bot token?", "Reset bot token", () -> {
                this.token.set(normalizeDiscordBotToken(this.scarlet.settings.requireInput("Discord bot token (leave empty for staff mode)", true)));
                this.save();
            }, null)
        );
        
        if (spec.guildSf == null || (spec.guildSf.trim().isEmpty() && token0 != null && !token0.isEmpty()))
        {
            // A blank guild only makes sense in staff mode (no token). With a token set, ask again
            // instead of letting JDA fail later with "ID may not be empty" (headless has no Reset button).
            spec.guildSf = this.scarlet.settings.requireInput(token0 != null && !token0.isEmpty()
                ? "Discord guild snowflake (required: a bot token is set)"
                : "Discord guild snowflake (leave empty for staff mode)", false);
            if (spec.guildSf != null) spec.guildSf = spec.guildSf.trim();
            save = true;
        }
        this.scarlet.settings.new FileValuedVoid("Discord guild snowflake", "Reset", () -> 
            this.scarlet.settings.requireConfirmYesNoAsync("Are you sure you want to reset the guild snowflake?", "Reset guild snowflake", () -> {
                this.guildSf = this.scarlet.settings.requireInput("Discord guild snowflake (leave empty for staff mode)", false);
                this.save();
            }, null)
        );
        
        if (save) this.save(spec);
        
        this.token.set(token0);
//        this.token = spec.token;
        this.guildSf = spec.guildSf;
        this.audioChannelSf = spec.audioChannelSf;
        this.discordActionLogChannelSf = spec.discordActionLogChannelSf;
        this.opsAlertChannelSf = spec.opsAlertChannelSf;
        this.trainingChannelSf = spec.trainingChannelSf;
        this.evidenceRoot = spec.evidenceRoot;
        this.ticketToolAutoResponseEnabled = spec.ticketToolAutoResponseEnabled;
        this.ticketToolCategorySf = nonBlankOrNull(spec.ticketToolCategorySf);
        this.ticketToolChannelNameRegex = nonBlankOrNull(spec.ticketToolChannelNameRegex);
        if (this.ticketToolChannelNameRegex == null)
            this.ticketToolChannelNameRegex = DEFAULT_TICKET_TOOL_CHANNEL_NAME_REGEX;
        this.ticketToolAgeVerificationMessage = nonBlankOrNull(spec.ticketToolAgeVerificationMessage);
        if (this.ticketToolAgeVerificationMessage == null)
            this.ticketToolAgeVerificationMessage = DEFAULT_TICKET_TOOL_AGE_VERIFICATION_MESSAGE;
        this.ticketToolNotifyRoleSf = nonBlankOrNull(spec.ticketToolNotifyRoleSf);
        if (this.ticketToolNotifyRoleSf == null)
            this.ticketToolNotifyRoleSf = DEFAULT_TICKET_TOOL_NOTIFY_ROLE_SF;
        this.ticketToolChannelNamePattern = compileTicketToolChannelNamePattern(this.ticketToolChannelNameRegex);
        this.discordAccountAgeAlertMinutes = clampDiscordAccountAgeAlertMinutes(spec.discordAccountAgeAlertMinutes);
        this.scarletPermission2roleSf = spec.scarletPermission2roleSf == null ? new HashMap<>() : new HashMap<>(spec.scarletPermission2roleSf);
        this.scarletAuxWh2webhookUrl = spec.scarletAuxWh2webhookUrl == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(spec.scarletAuxWh2webhookUrl);
        this.auditType2channelSf = spec.auditType2channelSf == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(spec.auditType2channelSf);
        this.auditExType2channelSf = spec.auditExType2channelSf == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(spec.auditExType2channelSf);
        this.auditType2scarletAuxWh = spec.auditType2scarletAuxWh == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(spec.auditType2scarletAuxWh);
        this.auditType2secretChannelSf = spec.auditType2secretChannelSf == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(spec.auditType2secretChannelSf);
        this.auditExType2secretChannelSf = spec.auditExType2secretChannelSf == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(spec.auditExType2secretChannelSf);
        Map<String, Integer> auditType2color = new ConcurrentHashMap<>();
        if (spec.auditType2color != null && !spec.auditType2color.isEmpty())
            spec.auditType2color.forEach((auditType, colorString) -> {
                try
                {
                    int argb = Integer.parseUnsignedInt(colorString, 16);
                    auditType2color.put(auditType, argb);
                }
                catch (Exception ex)
                {
                    LOG.warn("Exception whilst parsing colors", ex);
                }
            });
        this.auditType2color = auditType2color;
        this.queuedActions = Collections.synchronizedList(spec.queuedActions == null ? new LinkedList<>() : new LinkedList<>(spec.queuedActions));
    }
    public synchronized void save()
    {
        this.requireWritableConfig();
        this.perms.save();
        JDASettingsSpec spec = new JDASettingsSpec();
//        spec.token = this.token;
        spec.guildSf = this.guildSf;
        spec.audioChannelSf = this.audioChannelSf;
        spec.discordActionLogChannelSf = this.discordActionLogChannelSf;
        spec.opsAlertChannelSf = this.opsAlertChannelSf;
        spec.trainingChannelSf = this.trainingChannelSf;
        spec.evidenceRoot = this.evidenceRoot;
        spec.ticketToolAutoResponseEnabled = this.ticketToolAutoResponseEnabled;
        spec.ticketToolCategorySf = this.ticketToolCategorySf;
        spec.ticketToolChannelNameRegex = this.ticketToolChannelNameRegex;
        spec.ticketToolNotifyRoleSf = this.ticketToolNotifyRoleSf;
        spec.ticketToolAgeVerificationMessage = this.ticketToolAgeVerificationMessage;
        spec.discordAccountAgeAlertMinutes = this.discordAccountAgeAlertMinutes;
        spec.auditType2channelSf = new HashMap<>(this.auditType2channelSf);
        spec.auditExType2channelSf = new HashMap<>(this.auditExType2channelSf);
        spec.auditType2scarletAuxWh = new HashMap<>(this.auditType2scarletAuxWh);
        spec.auditType2secretChannelSf = new HashMap<>(this.auditType2secretChannelSf);
        spec.auditExType2secretChannelSf = new HashMap<>(this.auditExType2secretChannelSf);
        spec.scarletPermission2roleSf = new HashMap<>(this.scarletPermission2roleSf);
        spec.scarletAuxWh2webhookUrl = new HashMap<>(this.scarletAuxWh2webhookUrl);
        Map<String, String> auditType2color = new HashMap<>();
        if (this.auditType2color != null && !this.auditType2color.isEmpty())
            this.auditType2color.forEach((auditType, color) -> auditType2color.put(auditType, Integer.toHexString(color)));
        spec.auditType2color = auditType2color;
        spec.queuedActions = new ArrayList<>(this.queuedActions);
        this.save(spec);
    }
    synchronized void save(JDASettingsSpec spec)
    {
        this.requireWritableConfig();
        try
        {
            FileBackups.write(this.discordBotFile, fw -> Scarlet.GSON_PRETTY.toJson(spec, JDASettingsSpec.class, fw));
        }
        catch (Exception ex)
        {
            LOG.error("Exception saving discord bot settings", ex);
            throw new IllegalStateException("Discord configuration could not be saved. The previous file was preserved; check storage and retry.", ex);
        }
    }

    private void requireWritableConfig()
    {
        if (this.configLoadFailed)
            throw new IllegalStateException("Discord configuration is unreadable; refusing to overwrite it with defaults. Restore or repair the original file first.");
    }

    static JDASettingsSpec readDiscordSettings(File file) throws IOException
    {
        try (java.io.Reader input = java.nio.file.Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
            com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(input))
        {
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            JsonElement json = new com.google.gson.Gson().getAdapter(JsonElement.class).read(reader);
            if (json == null || !json.isJsonObject() || reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT)
                throw new IOException("Discord configuration must contain exactly one JSON object");
            JDASettingsSpec spec = Scarlet.GSON_PRETTY.fromJson(json, JDASettingsSpec.class);
            for (Map<?, ?> map : new Map<?, ?>[]{spec.scarletPermission2roleSf, spec.scarletAuxWh2webhookUrl,
                spec.auditType2channelSf, spec.auditExType2channelSf, spec.auditType2scarletAuxWh,
                spec.auditType2secretChannelSf, spec.auditExType2secretChannelSf, spec.auditType2color})
                if (map != null && (map.containsKey(null) || map.containsValue(null)))
                    throw new IOException("Discord configuration contains an invalid null routing entry");
            if (spec.queuedActions != null && spec.queuedActions.contains(null))
                throw new IOException("Discord configuration contains an invalid null queued action");
            return spec;
        }
    }

    static String nonBlankOrNull(String string)
    {
        if (string == null)
            return null;
        string = string.trim();
        return string.isEmpty() ? null : string;
    }

    static int clampDiscordAccountAgeAlertMinutes(int minutes)
    {
        return Math.max(0, Math.min(minutes, DISCORD_ACCOUNT_AGE_ALERT_MAX_MINUTES));
    }

    static Pattern compileTicketToolChannelNamePattern(String regex)
    {
        String normalized = nonBlankOrNull(regex);
        if (normalized == null)
            return null;
        try
        {
            return Pattern.compile(normalized);
        }
        catch (PatternSyntaxException ex)
        {
            LOG.warn("Invalid Ticket Tool channel-name regex `{}`; ticket auto-response channel-name matching disabled", regex, ex);
            return null;
        }
    }

    public int setDiscordAccountAgeAlertMinutes(int minutes)
    {
        this.discordAccountAgeAlertMinutes = clampDiscordAccountAgeAlertMinutes(minutes);
        this.save();
        return this.discordAccountAgeAlertMinutes;
    }

    public boolean setTicketToolAutoResponse(Boolean enabled, Channel category, Role notifyRole, String channelNameRegex)
    {
        String regex = this.ticketToolChannelNameRegex;
        Pattern pattern = this.ticketToolChannelNamePattern;
        if (channelNameRegex != null)
        {
            regex = nonBlankOrNull(channelNameRegex);
            pattern = compileTicketToolChannelNamePattern(regex);
            if (regex != null && pattern == null)
                return false;
        }
        if (enabled != null)
            this.ticketToolAutoResponseEnabled = enabled.booleanValue();
        if (category != null)
            this.ticketToolCategorySf = category.getId();
        if (notifyRole != null)
            this.ticketToolNotifyRoleSf = notifyRole.getId();
        this.ticketToolChannelNameRegex = regex;
        this.ticketToolChannelNamePattern = pattern;
        this.save();
        return true;
    }

    /**
     * Sets (or clears, if message is null/blank) the custom Ticket Tool age-verification
     * auto-response message. When cleared, falls back to DEFAULT_TICKET_TOOL_AGE_VERIFICATION_MESSAGE.
     */
    public void setTicketToolAgeVerificationMessage(String message)
    {
        String trimmed = nonBlankOrNull(message);
        this.ticketToolAgeVerificationMessage = trimmed == null ? DEFAULT_TICKET_TOOL_AGE_VERIFICATION_MESSAGE : trimmed;
        this.save();
    }

    void clearDeadPagination()
    {
        this.interactions.clearDeadPagination();
    }

    static class InstanceCreation
    {
        InstanceCreation(String ictoken, String worldId, String groupId, String ownerSnowflake)
        {
            this.ictoken = ictoken;
            this.worldId = worldId;
            this.groupId = groupId;
            this.ownerSnowflake = ownerSnowflake;
        }
        final String ictoken;
        final String worldId;
        final String groupId;
        final String ownerSnowflake;
        InstanceRegion region = InstanceRegion.US;
        List<String> roleIds = null;
        GroupAccessType groupAccessType = GroupAccessType.PUBLIC;
        OffsetDateTime closedAt = null;
        Boolean queueEnabled = Boolean.TRUE;
        Boolean hardClose = Boolean.FALSE;
        Boolean ageGate = Boolean.FALSE;
        Boolean inviteOnly = null;
        Boolean canRequestInvite = null;
        Boolean playerPersistenceEnabled = null;
        Boolean instancePersistenceEnabled = null;
        String displayName = null;
        String calendarEntryId = null;
        PerformanceRatings minimumAvatarPerformance = null;
        
        boolean contentSettings_drones = true;
        boolean contentSettings_emoji = true;
        boolean contentSettings_props = true;
        boolean contentSettings_pedestals = true;
        boolean contentSettings_prints = true;
        boolean contentSettings_stickers = true;
        CreateInstanceRequest createRequest()
        {
            CreateInstanceRequest cir = new CreateInstanceRequest();
            cir.setWorldId(this.worldId);
            cir.setOwnerId(this.groupId);
            cir.setType(InstanceType.GROUP);
            cir.setRegion(CreateInstanceRequestRegion.fromValue(this.region.getValue()));
            if (this.groupAccessType == GroupAccessType.MEMBERS && this.roleIds != null) cir.setRoleIds(this.roleIds);
            cir.setGroupAccessType(this.groupAccessType);
            cir.setClosedAt(this.closedAt);
            cir.setQueueEnabled(this.queueEnabled);
            cir.setHardClose(this.hardClose);
//            cir.setPlayerPersistenceEnabled(this.playerPersistenceEnabled);
            cir.setInstancePersistenceEnabled(this.instancePersistenceEnabled);
            cir.setDisplayName(this.displayName);
//            cir.setCalendarEntryId(this.calendarEntryId);
            cir.setAgeGate(this.ageGate);
            cir.setInviteOnly(this.inviteOnly);
            cir.setCanRequestInvite(this.canRequestInvite);
            InstanceContentSettings contentSettings = new InstanceContentSettings();
            contentSettings.setDrones(this.contentSettings_drones);
            contentSettings.setEmoji(this.contentSettings_emoji);
            contentSettings.setProps(this.contentSettings_props);
            contentSettings.setPedestals(this.contentSettings_pedestals);
            contentSettings.setPrints(this.contentSettings_prints);
            contentSettings.setStickers(this.contentSettings_stickers);
            cir.setContentSettings(contentSettings);
            return cir;
        }
        JsonObject createRequestEx()
        {
            JsonObject cir = JSON.getGson().toJsonTree(this.createRequest()).getAsJsonObject();
            if (this.playerPersistenceEnabled != null) cir.addProperty("playerPersistenceEnabled", this.playerPersistenceEnabled);
            if (this.calendarEntryId != null) cir.addProperty("calendarEntryId", this.calendarEntryId);
            if (this.minimumAvatarPerformance != null) cir.addProperty("minimumAvatarPerformance", this.minimumAvatarPerformance.getValue());
            return cir;
        }
    }

    class JDAEvents extends ListenerAdapter
    {
        JDAEvents()
        {
        }

        void interactionPerms(Interaction interaction)
        {
            if (interaction instanceof IReplyCallback)
            {
                IReplyCallback replyCallback = (IReplyCallback)interaction;
                String reply = ScarletDiscordJDA.this.permsReply(interaction.getMember());
                (replyCallback.isAcknowledged()
                    ? replyCallback.getHook().sendMessage(reply)
                    : replyCallback.reply(reply)
                ).queue();
            }
        }

        void interactionError(Interaction interaction, Throwable err)
        {
            LOG.error("Error processing interaction of type "+interaction.getClass().getSimpleName(), err);
            if (interaction instanceof IReplyCallback)
            {
                IReplyCallback replyCallback = (IReplyCallback)interaction;
                String reply = "Error processing interaction:\n`"+err+"`";
                (replyCallback.isAcknowledged()
                    ? replyCallback.getHook().sendMessage(reply)
                    : replyCallback.reply(reply)
                ).queue();
            }
        }

        @Override
        public void onShutdown(ShutdownEvent event)
        {
            if (CloseCode.DISALLOWED_INTENTS == event.getCloseCode())
            {
                ScarletDiscordJDA.this.scarlet.stop();
                LOG.error("You must enable the `Message Content` intent in the `Privileged Gateway Intents` are of your application's `Bot` tab.");
            }
        }

        @Override
        public void onSessionResume(SessionResumeEvent event)
        {
            if (ScarletDiscordJDA.this.livePlayerlist != null)
                ScarletDiscordJDA.this.livePlayerlist.reconcile();
        }

        @Override
        public void onSessionRecreate(SessionRecreateEvent event)
        {
            if (ScarletDiscordJDA.this.livePlayerlist != null)
                ScarletDiscordJDA.this.livePlayerlist.reconcile();
        }

        @Override
        public void onChannelCreate(ChannelCreateEvent event)
        {
            ScarletDiscordJDA.this.handleChannelCreate(event);
        }

        @Override
        public void onMessageReceived(MessageReceivedEvent event)
        {
            ScarletDiscordJDA.this.handleMessageReceived(event);
        }

        @Override
        public void onMessageContextInteraction(MessageContextInteractionEvent event)
        {
            if (!ScarletDiscordJDA.this.perms.check(event))
            {
                this.interactionPerms(event);
                return;
            }
            if (ScarletDiscordJDA.this.interactions.handle(event))
                return;
        }

        @Override
        public void onUserContextInteraction(UserContextInteractionEvent event)
        {
            if (!ScarletDiscordJDA.this.perms.check(event))
            {
                this.interactionPerms(event);
                return;
            }
            if (ScarletDiscordJDA.this.interactions.handle(event))
                return;
        }

        @Override
        public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event)
        {
            if (!ScarletDiscordJDA.this.perms.check(event))
            {
                this.interactionPerms(event);
                return;
            }
            if (ScarletDiscordJDA.this.interactions.handle(event))
                return;
            if ("scarlet-discord-permissions".equals(event.getName())) try
            {
                ScarletDiscordJDA.this.perms.internal_autocomplete(event);
                return;
            }
            catch (Exception ex)
            {
                this.interactionError(event, ex);
            }
        }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event)
        {
            final boolean ppcTiming = net.sybyline.scarlet.util.Platform.isPowerPC() && net.sybyline.scarlet.util.Platform.isPpcEdition();
            final long ppcArrived = System.currentTimeMillis();
            if (ppcTiming)
                LOG.info("PPC timing: /{} reached Scarlet {} ms after Discord created it (Discord allows 3000 ms to acknowledge; local clock skew not corrected)",
                    event.getName(), Long.valueOf(ppcArrived - event.getTimeCreated().toInstant().toEpochMilli()));
            if (!ScarletDiscordJDA.this.perms.check(event))
            {
                this.interactionPerms(event);
                return;
            }
            if (ppcTiming)
                LOG.info("PPC timing: /{} permission check took {} ms", event.getName(), Long.valueOf(System.currentTimeMillis() - ppcArrived));
            if (ScarletDiscordJDA.this.interactions.handle(event))
                return;
            if ("scarlet-discord-permissions".equals(event.getName())) try
            {
                ScarletDiscordJDA.this.perms.internal_handle(event);
                return;
            }
            catch (Exception ex)
            {
                this.interactionError(event, ex);
            }
        }

        @Override
        public void onButtonInteraction(ButtonInteractionEvent event)
        {
            if (ScarletDiscordJDA.this.exitOperator.handle(event)) return;
            if (!ScarletDiscordJDA.this.perms.check(event))
            {
                this.interactionPerms(event);
                return;
            }
            if (ScarletDiscordJDA.this.interactions.handle(event))
                return;
        }

        @Override
        public void onGuildMemberJoin(GuildMemberJoinEvent event)
        {
            ScarletDiscordJDA.this.handleGuildMemberJoin(event);
        }

        @Override
        public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event)
        {
            ScarletDiscordJDA.this.handleGuildMemberRoleAdd(event);
        }

        @Override
        public void onModalInteraction(ModalInteractionEvent event)
        {
            if (ScarletDiscordJDA.this.interactions.isImmediateModalFlow(event))
            {
                if (ScarletDiscordJDA.this.interactions.handle(event))
                    return;
            }
            if (!ScarletDiscordJDA.this.perms.check(event))
            {
                this.interactionPerms(event);
                return;
            }
            if (ScarletDiscordJDA.this.interactions.handle(event))
                return;
        }

        @Override
        public void onStringSelectInteraction(StringSelectInteractionEvent event)
        {
            if (!ScarletDiscordJDA.this.perms.check(event))
            {
                this.interactionPerms(event);
                return;
            }
            if (ScarletDiscordJDA.this.interactions.handle(event))
                return;
        }

        @Override
        public void onEntitySelectInteraction(EntitySelectInteractionEvent event)
        {
            if (!ScarletDiscordJDA.this.perms.check(event))
            {
                this.interactionPerms(event);
                return;
            }
            if (ScarletDiscordJDA.this.interactions.handle(event))
                return;
        }

    }

    @Override
    public boolean isEmitting(GroupAuditType auditType)
    {
        return this.jda != null && auditType != null && this.auditType2channelSf.get(auditType.id) != null;
    }

    @Override
    public boolean isEmitting(GroupAuditTypeEx auditTypeEx)
    {
        return this.jda != null && auditTypeEx != null
            && (this.auditExType2channelSf.get(auditTypeEx.id) != null
                || (auditTypeEx == GroupAuditTypeEx.LIVE_PLAYERLIST
                    && this.auditExType2secretChannelSf.get(auditTypeEx.id) != null));
    }

    @Override
    public void updateLivePlayerList(String location, List<ScarletLivePlayerlist.PlayerSnapshot> roster)
    {
        this.livePlayerlist.reconcile(location, roster);
    }

    @Override
    public void endLivePlayerList(String location)
    {
        this.livePlayerlist.onLocalSessionEnded(location);
    }
    
    boolean shouldRedact(String infoId, String requesterSf)
    {
        return ScarletDiscordJDA.this.scarlet.secretStaffList.isSecretStaffId(infoId)
           && !ScarletDiscordJDA.this.scarlet.secretStaffList.isSecretStaffId(ScarletDiscordJDA.this.scarlet.data.globalMetadata_getSnowflakeId(requesterSf));
    }

    boolean auditVisibleTo(AuditEntryMetadata metadata, String requesterSf)
    {
        if (requesterSf == null) return false;
        boolean secretAudience = this.scarlet.secretStaffList.isSecretStaffId(
            this.scarlet.data.globalMetadata_getSnowflakeId(requesterSf));
        return ScarletAuditPrivacy.visible(metadata, this.scarlet.secretStaffList::isSecretStaffId, secretAudience);
    }

    String auditHistoryLine(GroupAuditLogEntryUnknown entry, AuditEntryMetadata metadata, String requesterSf)
    {
        boolean secretAudience = requesterSf != null && this.scarlet.secretStaffList.isSecretStaffId(
            this.scarlet.data.globalMetadata_getSnowflakeId(requesterSf));
        return ScarletAuditPrivacy.historyLine(entry, metadata, this.scarlet.secretStaffList::isSecretStaffId, secretAudience);
    }

    String approvedAuditChannel(AuditEntryMetadata metadata)
    {
        if (metadata == null || metadata.entry == null || metadata.entryRedacted)
            return null;
        boolean confidential = ScarletAuditPrivacy.confidential(metadata, this.scarlet.secretStaffList::isSecretStaffId);
        boolean training = metadata.entry.getId() != null && metadata.entry.getId().startsWith(ScarletSimulation.TRAINING_AUDIT_ID_PREFIX);
        // The training channel is not a separately approved confidential destination.
        return training ? (confidential ? null : this.trainingChannelSf)
            : (confidential ? this.auditType2secretChannelSf : this.auditType2channelSf).get(metadata.entry.getEventType());
    }

    String auditThreadProblem(AuditEntryMetadata metadata, Message message, String requesterSf)
    {
        if (message == null || !message.isFromGuild() || this.jda == null
            || !ScarletAuditPrivacy.recordedMessage(metadata, this.guildSf, this.approvedAuditChannel(metadata),
                this.jda.getSelfUser().getId(), message.getAuthor().getId(), message.isWebhookMessage(),
                message.getGuild().getId(), message.getChannel().getId(), message.getId()))
            return "The original Scarlet audit message or its approved destination cannot be verified";
        String accessProblem = this.auditThreadRequesterProblem(metadata, message.getGuild(), message.getGuildChannel(), requesterSf);
        if (accessProblem != null) return accessProblem;
        if (metadata.threadSnowflake != null && !metadata.messageSnowflake.equals(metadata.threadSnowflake))
            return "The saved thread is not attached to the original audit message";
        ThreadChannel started = message.getStartedThread();
        return started == null ? null : this.auditThreadDestinationProblem(metadata, started);
    }

    String auditThreadRequesterProblem(AuditEntryMetadata metadata, Guild guild,
        net.dv8tion.jda.api.entities.channel.middleman.GuildChannel channel, String requesterSf)
    {
        // Automatic delivery has no human requester. Repairs must retain the current command,
        // channel and confidential-audit permissions when queued work is actually performed.
        if (requesterSf == null)
        {
            if (ScarletAuditOutbox.isFollowup(metadata)
                && (!this.auditFollowupGroupCurrent(metadata)
                    || !sameAuditFollowupContext(metadata, this.scarlet.data.auditEntryMetadata(metadata.entry.getId()))))
                return "The recorded audit event changed while its follow-up was queued";
            return null;
        }
        AuditEntryMetadata current = metadata == null || metadata.entry == null ? null
            : this.scarlet.data.auditEntryMetadata(metadata.entry.getId());
        if (!ScarletAuditPrivacy.sameRecordedAudit(metadata, current))
            return "The recorded audit event changed; submit a new repair request";
        Member requester = guild == null ? null : guild.getMemberById(requesterSf);
        return requester != null && this.perms != null
            && this.perms.checkOperation(requester, DPerms.PermType.MESSAGE_COMMAND, "fix-audit-thread")
            && channel != null && requester.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)
            && this.auditVisibleTo(metadata, requesterSf)
            ? null : "The requester is no longer authorized for this audit event or destination";
    }

    String auditThreadDestinationProblem(AuditEntryMetadata metadata, ThreadChannel thread)
    {
        return thread != null && ScarletAuditPrivacy.recordedThread(metadata, this.guildSf, this.approvedAuditChannel(metadata),
            thread.getGuild().getId(), thread.getParentChannel().getId(), thread.getId())
            ? null : "The thread is not attached to the original audit message in its currently approved channel";
    }

    String auditThreadAccessProblem(AuditEntryMetadata metadata, ThreadChannel thread, String requesterSf)
    {
        String problem = this.auditThreadDestinationProblem(metadata, thread);
        return problem != null ? problem : this.auditThreadRequesterProblem(metadata, thread.getGuild(), thread, requesterSf);
    }

    static final class PendingDiscordModeration
    {
        final String owner, target, reason;
        final net.dv8tion.jda.api.Permission permission;
        final long expiresAt = System.currentTimeMillis() + 5 * 60_000L;
        PendingDiscordModeration(String owner, String target, String reason, net.dv8tion.jda.api.Permission permission)
        {
            this.owner = owner;
            this.target = target;
            this.reason = reason;
            this.permission = permission;
        }
    }

    private final Map<String, PendingDiscordModeration> pendingDiscordModeration = new java.util.concurrent.ConcurrentHashMap<>();

    String prepareDiscordModeration(String owner, String target, String reason, net.dv8tion.jda.api.Permission permission)
    {
        this.pendingDiscordModeration.values().removeIf(p -> p.expiresAt < System.currentTimeMillis());
        String token = java.util.UUID.randomUUID().toString();
        this.pendingDiscordModeration.put(token, new PendingDiscordModeration(owner, target, reason, permission));
        return token;
    }

    PendingDiscordModeration consumeDiscordModeration(String token, String owner, net.dv8tion.jda.api.Permission permission)
    {
        PendingDiscordModeration pending = this.pendingDiscordModeration.get(token);
        if (pending == null || !pending.owner.equals(owner) || pending.permission != permission
            || pending.expiresAt < System.currentTimeMillis())
            return null;
        return this.pendingDiscordModeration.remove(token, pending) ? pending : null;
    }

    public void setAuditChannel(GroupAuditType auditType, Channel channel)
    {
        if (channel != null)
        {
            this.auditType2channelSf.put(auditType.id, channel.getId());
            LOG.info(String.format("Setting audit channel for %s (%s) to %s (%s)", auditType.title, auditType.id, channel.getName(), "<#"+channel.getId()+">"));
        }
        else
        {
            this.auditType2channelSf.remove(auditType.id);
            LOG.info(String.format("Unsetting audit channel for %s (%s)", auditType.title, auditType.id));
        }
        this.save();
    }

    public void setAuditExChannel(GroupAuditTypeEx auditExType, Channel channel)
    {
        if (channel != null)
        {
            this.auditExType2channelSf.put(auditExType.id, channel.getId());
            LOG.info(String.format("Setting audit channel for %s (%s) to %s (%s)", auditExType.title, auditExType.id, channel.getName(), "<#"+channel.getId()+">"));
        }
        else
        {
            this.auditExType2channelSf.remove(auditExType.id);
            LOG.info(String.format("Unsetting audit channel for %s (%s)", auditExType.title, auditExType.id));
        }
        this.save();
        if (auditExType == GroupAuditTypeEx.LIVE_PLAYERLIST && this.livePlayerlist != null)
            this.livePlayerlist.reconcile();
    }

    public void setAuditSecretChannel(GroupAuditType auditType, Channel channel)
    {
        if (channel != null)
        {
            this.auditType2secretChannelSf.put(auditType.id, channel.getId());
            LOG.info(String.format("Setting audit secret channel for %s (%s) to %s (%s)", auditType.title, auditType.id, channel.getName(), "<#"+channel.getId()+">"));
        }
        else
        {
            this.auditType2secretChannelSf.remove(auditType.id);
            LOG.info(String.format("Unsetting audit secret channel for %s (%s)", auditType.title, auditType.id));
        }
        this.save();
    }

    public void setAuditExSecretChannel(GroupAuditTypeEx auditExType, Channel channel)
    {
        if (channel != null && !auditExType.supportsSecretRouting())
            throw new IllegalArgumentException("This extended event does not support a secret channel destination");
        if (channel != null)
        {
            this.auditExType2secretChannelSf.put(auditExType.id, channel.getId());
            LOG.info(String.format("Setting audit secret channel for %s (%s) to %s (%s)", auditExType.title, auditExType.id, channel.getName(), "<#"+channel.getId()+">"));
        }
        else
        {
            this.auditExType2secretChannelSf.remove(auditExType.id);
            LOG.info(String.format("Unsetting audit secret channel for %s (%s)", auditExType.title, auditExType.id));
        }
        this.save();
        if (auditExType == GroupAuditTypeEx.LIVE_PLAYERLIST && this.livePlayerlist != null)
            this.livePlayerlist.reconcile();
    }

    public void setDiscordActionLogChannel(Channel channel)
    {
        if (channel != null)
        {
            this.discordActionLogChannelSf = channel.getId();
            LOG.info("Setting Discord action log channel to {} ({})", channel.getName(), "<#"+channel.getId()+">");
        }
        else
        {
            this.discordActionLogChannelSf = null;
            LOG.info("Unsetting Discord action log channel");
        }
        this.save();
    }

    public void setOpsAlertChannel(Channel channel)
    {
        if (channel != null)
        {
            this.opsAlertChannelSf = channel.getId();
            LOG.info("Setting ops alert channel to "+channel.getName()+" ("+channel.getId()+")");
        }
        else
        {
            this.opsAlertChannelSf = null;
            LOG.info("Removing ops alert channel");
        }
        this.save();
    }

    public void setTrainingChannel(Channel channel)
    {
        if (channel != null)
        {
            this.trainingChannelSf = channel.getId();
            LOG.info("Setting training channel to "+channel.getName()+" ("+channel.getId()+")");
        }
        else
        {
            this.trainingChannelSf = null;
            LOG.info("Removing training channel");
        }
        this.save();
    }

    TextChannel getOpsAlertChannel()
    {
        if (this.jda == null || this.opsAlertChannelSf == null || this.opsAlertChannelSf.trim().isEmpty())
            return null;
        Guild guild = this.jda.getGuildById(this.guildSf);
        return guild == null ? null : guild.getTextChannelById(this.opsAlertChannelSf);
    }

    @Override
    public void emitOpsAlert(String title, String detail, int color)
    {
        try
        {
            TextChannel channel = this.getOpsAlertChannel();
            if (channel == null)
                return;
            EmbedBuilder embed = new EmbedBuilder()
                .setTitle(MiscUtils.maybeEllipsis(256, title))
                .setColor(color)
                .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
            if (detail != null && !detail.trim().isEmpty())
                embed.setDescription(MiscUtils.maybeEllipsis(4096, detail.trim()));
            embed.setFooter("Scarlet health");
            channel.sendMessageEmbeds(embed.build()).queue(
                $ -> {},
                error -> LOG.warn("Failed to emit ops alert to {}: {}", this.opsAlertChannelSf, error.getMessage())
            );
        }
        catch (Exception ex)
        {
            // Ops alerts are best-effort; never let them break the caller.
            LOG.warn("Exception emitting ops alert", ex);
        }
    }

    final ScarletExitOperatorDiscord exitOperator = new ScarletExitOperatorDiscord(this);

    @Override public void emitClientExitConflict(String token, String counts)
    {
        try { this.exitOperator.emit(token, counts); }
        catch (RuntimeException ex) { LOG.warn("Client-exit operator alert unavailable"); }
    }

    TextChannel getDiscordActionLogChannel()
    {
        if (this.jda == null || this.discordActionLogChannelSf == null || this.discordActionLogChannelSf.trim().isEmpty())
            return null;
        Guild guild = this.jda.getGuildById(this.guildSf);
        return guild == null ? null : guild.getTextChannelById(this.discordActionLogChannelSf);
    }

    void emitDiscordActionLog(String action, Member actor, String targetName, String targetId, String reason, boolean success, String detail, int color)
    {
        TextChannel channel = this.getDiscordActionLogChannel();
        if (channel == null)
            return;
        String actorText = actor == null
            ? "Unknown"
            : MarkdownSanitizer.escape(actor.getEffectiveName()) + " (" + actor.getUser().getAsMention() + ", `" + actor.getId() + "`)";
        String targetText = (targetName == null || targetName.trim().isEmpty())
            ? "`" + targetId + "`"
            : MarkdownSanitizer.escape(targetName) + " (`" + targetId + "`)";
        EmbedBuilder embed = new EmbedBuilder()
            .setTitle(action)
            .setColor(color)
            .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
            .addField("Status", success ? "Completed" : "Failed", true)
            .addField("Moderator", actorText, false)
            .addField("Target", targetText, false)
            .addField("Reason", reason == null || reason.trim().isEmpty() ? "No reason provided" : MiscUtils.maybeEllipsis(1024, reason.trim()), false);
        if (detail != null && !detail.trim().isEmpty())
            embed.addField("Details", MiscUtils.maybeEllipsis(1024, detail.trim()), false);
        channel.sendMessageEmbeds(embed.build()).queue(
            $ -> {},
            error -> LOG.warn("Failed to emit Discord action log to {}: {}", this.discordActionLogChannelSf, error.getMessage())
        );
    }

    void emitDiscordJoinLog(Member member, Invite invite, String detail)
    {
        TextChannel channel = this.getDiscordActionLogChannel();
        if (channel == null || member == null)
            return;
        OffsetDateTime accountCreated = member.getUser().getTimeCreated();
        Duration accountAge = Duration.between(accountCreated, OffsetDateTime.now(ZoneOffset.UTC));
        long accountAgeMinutes = Math.max(0L, accountAge.toMinutes());
        boolean accountAgeAlert = this.discordAccountAgeAlertMinutes > 0
            && accountAgeMinutes < this.discordAccountAgeAlertMinutes;
        String createdEpoch = Long.toUnsignedString(accountCreated.toEpochSecond());
        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("Discord Member Joined")
            .setColor(accountAgeAlert ? 0xED4245 : 0x3BA55D)
            .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
            .addField("Member", MarkdownSanitizer.escape(member.getEffectiveName()) + " (" + member.getUser().getAsMention() + ", `" + member.getId() + "`)", false)
            .addField("Account Created", "<t:" + createdEpoch + ":f> (<t:" + createdEpoch + ":R>)", true)
            .addField("Account Age", formatDurationHuman(accountAge), true)
            .addField("IP Address", "Unavailable. Discord does not expose member IP addresses to bots.", false);
        if (accountAgeAlert)
        {
            embed.addField("Account Age Alert",
                "Account is under the configured threshold of " + formatDurationHuman(Duration.ofMinutes(this.discordAccountAgeAlertMinutes)) + ".",
                false);
        }
        if (invite != null)
        {
            String inviter = invite.getInviter() == null
                ? "Unknown"
                : MarkdownSanitizer.escape(invite.getInviter().getName()) + " (`" + invite.getInviter().getId() + "`)";
            String inviteChannel = invite.getChannel() == null
                ? "Unknown"
                : invite.getChannel().getName();
            embed.addField("Invite", "https://discord.gg/" + invite.getCode(), false);
            embed.addField("Invite Channel", inviteChannel, true);
            embed.addField("Inviter", inviter, true);
            embed.addField("Invite Uses", Integer.toString(invite.getUses()), true);
        }
        else
        {
            embed.addField("Invite", "Unknown", false);
        }
        if (detail != null && !detail.trim().isEmpty())
            embed.addField("Details", MiscUtils.maybeEllipsis(1024, detail.trim()), false);
        channel.sendMessageEmbeds(embed.build()).queue(
            $ -> {},
            error -> LOG.warn("Failed to emit Discord join log to {}: {}", this.discordActionLogChannelSf, error.getMessage())
        );
    }

    static String formatDurationHuman(Duration duration)
    {
        long minutes = Math.max(0L, duration.toMinutes());
        if (minutes < 60L)
            return minutes + " minute" + (minutes == 1L ? "" : "s");
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        if (hours < 48L)
            return remainingMinutes == 0L
                ? hours + " hour" + (hours == 1L ? "" : "s")
                : hours + " hour" + (hours == 1L ? "" : "s") + ", " + remainingMinutes + " minute" + (remainingMinutes == 1L ? "" : "s");
        long days = hours / 24L;
        long remainingHours = hours % 24L;
        if (days < 365L)
            return remainingHours == 0L
                ? days + " day" + (days == 1L ? "" : "s")
                : days + " day" + (days == 1L ? "" : "s") + ", " + remainingHours + " hour" + (remainingHours == 1L ? "" : "s");
        long years = days / 365L;
        long remainingDays = days % 365L;
        return remainingDays == 0L
            ? years + " year" + (years == 1L ? "" : "s")
            : years + " year" + (years == 1L ? "" : "s") + ", " + remainingDays + " day" + (remainingDays == 1L ? "" : "s");
    }

    void refreshGuildInviteCache(Guild guild)
    {
        if (guild == null)
            return;
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_SERVER))
        {
            LOG.warn("Discord invite tracking needs the Manage Server permission in guild {} ({})", guild.getName(), guild.getId());
            return;
        }
        guild.retrieveInvites().queue(
            invites -> this.guildInviteUses.put(guild.getId(), snapshotInviteUses(invites)),
            error -> LOG.warn("Failed to refresh Discord invite cache for guild {} ({}): {}", guild.getName(), guild.getId(), error.getMessage())
        );
    }

    void handleGuildMemberJoin(GuildMemberJoinEvent event)
    {
        Guild guild = event.getGuild();
        if (!Objects.equals(guild.getId(), this.guildSf))
            return;
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_SERVER))
        {
            this.emitDiscordJoinLog(event.getMember(), null, "Invite tracking unavailable: the bot needs Manage Server permission.");
            return;
        }
        Map<String, Integer> before = this.guildInviteUses.getOrDefault(guild.getId(), Collections.emptyMap());
        guild.retrieveInvites().queue(
            invites -> {
                Invite usedInvite = findUsedInvite(before, invites);
                this.guildInviteUses.put(guild.getId(), snapshotInviteUses(invites));
                this.emitDiscordJoinLog(event.getMember(), usedInvite,
                    usedInvite == null ? "Invite could not be determined from invite use counts." : null);
            },
            error -> {
                LOG.warn("Failed to retrieve invites after member join in guild {} ({}): {}", guild.getName(), guild.getId(), error.getMessage());
                this.emitDiscordJoinLog(event.getMember(), null, "Invite lookup failed: " + error.getMessage());
            }
        );
    }

    void handleGuildMemberRoleAdd(GuildMemberRoleAddEvent event)
    {
        if (!Objects.equals(event.getGuild().getId(), this.guildSf))
            return;
        if (!this.scarlet.autoInviteOnVerify.get())
            return;
        String verifiedRoleSf = this.scarlet.verifiedRoleSf.get();
        if (verifiedRoleSf == null || verifiedRoleSf.isEmpty() || "1234567890".equals(verifiedRoleSf))
            return;
        boolean justVerified = event.getRoles().stream().anyMatch(role -> Objects.equals(role.getId(), verifiedRoleSf));
        if (!justVerified)
            return;
        Member member = event.getMember();
        String userSf = member.getId();
        String vrcId = this.scarlet.data.globalMetadata_getSnowflakeId(userSf);
        if (vrcId == null || vrcId.isEmpty())
        {
            LOG.info("Member {} ({}) was given the verified role but has no linked VRChat account; prompting them to link.", member.getEffectiveName(), userSf);
            this.scarlet.exec.execute(() -> this.promptMemberToLinkVrchatAccount(member));
            return;
        }
        this.scarlet.exec.execute(() -> this.tryAutoInviteToGroup(member, vrcId));
    }

    /**
     * Called when a member receives the verified role but Scarlet has no Discord-to-VRChat
     * account link on file for them (e.g. they were verified manually via a ticket, or via
     * a separate bot like VRCLinker that doesn't share its link data with Scarlet). Finds
     * their open ticket channel, if any, and asks them to self-link their VRChat account.
     */
    void promptMemberToLinkVrchatAccount(Member member)
    {
        Guild guild = member.getGuild();
        GuildMessageChannel channel = this.findOpenTicketChannelFor(guild, member);
        if (channel == null)
        {
            LOG.debug("No open ticket channel found for {} ({}); cannot prompt to link VRChat account.", member.getEffectiveName(), member.getId());
            return;
        }
        String membersRoleSf = this.scarlet.membersRoleSf.get();
        if (membersRoleSf != null && !membersRoleSf.isEmpty() && !"1234567890".equals(membersRoleSf))
        {
            if (!member.getRoles().stream().anyMatch(role -> Objects.equals(role.getId(), membersRoleSf)))
            {
                LOG.debug("Member {} ({}) does not have the configured members role; not prompting to link VRChat account.", member.getEffectiveName(), member.getId());
                return;
            }
        }
        if (!this.verifyLinkPromptedChannels.add(channel.getId()))
            return;
        String message = member.getUser().getAsMention() + "\n\n" +
            "Thanks for getting verified! To get invited to our VRChat group, please link your VRChat account by running " +
            "`/link-vrchat-account vrchat-user:<your VRChat user ID>` anywhere in this server. " +
            "You can find your user ID on your VRChat profile page (it looks like `usr_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX`).";
        channel.sendMessage(message).queue(
            sent -> LOG.info("Prompted {} ({}) to link their VRChat account in {} ({}).", member.getEffectiveName(), member.getId(), channel.getName(), channel.getId()),
            error -> {
                this.verifyLinkPromptedChannels.remove(channel.getId());
                LOG.warn("Failed to prompt {} ({}) to link their VRChat account in {} ({}): {}", member.getEffectiveName(), member.getId(), channel.getName(), channel.getId(), error.getMessage());
            }
        );
    }

    /**
     * Finds an open ticket-tool channel that the given member can view, based on the
     * configured ticket category/channel-name pattern and per-member permission overrides.
     */
    GuildMessageChannel findOpenTicketChannelFor(Guild guild, Member member)
    {
        for (GuildChannel guildChannel : guild.getChannels())
        {
            if (!(guildChannel instanceof GuildMessageChannel))
                continue;
            GuildMessageChannel channel = (GuildMessageChannel)guildChannel;
            if (!this.isTicketToolAutoResponseCandidate(channel))
                continue;
            if (!(channel instanceof IPermissionContainer))
                continue;
            PermissionOverride override = ((IPermissionContainer)channel).getPermissionOverride(member);
            if (override != null && override.getAllowed().contains(Permission.VIEW_CHANNEL))
                return channel;
        }
        return null;
    }

    /**
     * Called when a member self-links their VRChat account via slash command. If they
     * already hold the verified role, attempts the group auto-invite immediately and
     * returns a short status message describing the result. Returns null if no invite
     * attempt was applicable (e.g. auto-invite disabled, or member not yet verified).
     */
    String onSelfLinkedVrchatAccount(Member member, String vrcId)
    {
        if (!this.scarlet.autoInviteOnVerify.get())
            return null;
        String verifiedRoleSf = this.scarlet.verifiedRoleSf.get();
        if (verifiedRoleSf == null || verifiedRoleSf.isEmpty() || "1234567890".equals(verifiedRoleSf))
            return null;
        if (!member.getRoles().stream().anyMatch(role -> Objects.equals(role.getId(), verifiedRoleSf)))
            return null;
        return this.tryAutoInviteToGroup(member, vrcId);
    }

    /**
     * Attempts to invite the given VRChat user to the configured auto-invite group,
     * skipping if they're already a member/invited/banned/blocking. Returns a short
     * message describing the outcome, suitable for sending back to the Discord member.
     */
    String tryAutoInviteToGroup(Member member, String vrcId)
    {
        String groupId = this.scarlet.autoInviteGroupId.get();
        if (groupId == null || groupId.isEmpty() || Scarlet.DEFAULT_AUTO_INVITE_GROUP_ID.equals(groupId))
            groupId = this.scarlet.vrc.groupId;
        if (groupId == null || groupId.isEmpty())
        {
            LOG.warn("Cannot auto-invite {} to VRChat group: no group configured.", vrcId);
            return "Could not send a VRChat group invite: no group is configured. Please contact staff.";
        }
        // Eligibility (VRChat age-verification OR the Discord member role) is already
        // enforced upstream at /link-vrchat-account, so by the time we reach here the user
        // has earned an invite. We deliberately do NOT pre-check their VRChat group
        // membership: the invite result classifies already-members, pending invites,
        // permission issues, and setup errors so the Discord reply can be precise.
        ScarletVRChat.GroupInviteResult invite = this.scarlet.vrc.inviteToGroupDetailed(groupId, vrcId, Boolean.FALSE);
        if (invite.sent)
        {
            LOG.info("Auto-invited Discord member {} (VRChat {}) to VRChat group {} after verification.", member.getId(), vrcId, groupId);
            if (this.scarlet.announceVerifyComplete.get())
                this.announceVerificationComplete(member, vrcId, groupId);
            return "You've been sent an invite to our VRChat group - check your VRChat notifications!";
        }
        LOG.warn("Auto-invite to VRChat group {} for {} (VRChat {}) was not sent: {}", groupId, member.getId(), vrcId, invite.logSummary);
        switch (invite.status)
        {
        case INVITER_NOT_MEMBER:
            return "I couldn't send a VRChat group invite because Scarlet's VRChat account is not a member of the configured group. Staff need to update the auto-invite group or log Scarlet into a VRChat account that can invite there.";
        case GROUP_NOT_FOUND:
            return "I couldn't send a VRChat group invite because the configured VRChat group could not be found. Staff need to update the auto-invite group setting.";
        case FORBIDDEN:
            return "I couldn't send a VRChat group invite because VRChat rejected Scarlet's group permissions. Staff need to make sure Scarlet's VRChat account has permission to invite members.";
        case TARGET_ALREADY_IN_GROUP_OR_INVITED:
            return "I couldn't send a VRChat group invite - you may already be in the group or have a pending invite. Check your VRChat notifications, and contact staff if you still need access.";
        case FAILED:
        default:
            return "I couldn't send a VRChat group invite because VRChat rejected the request. Please contact staff if you still need access.";
        }
    }

    /**
     * On a successful post-verification auto-invite, tells both sides the verification landed:
     * a staff embed in the Discord action-log channel, and a confirmation in the member's open
     * ticket channel (if one exists). Gated by {@link Scarlet#announceVerifyComplete}. Best-effort
     * and fully self-contained — it never throws back into the invite flow.
     */
    void announceVerificationComplete(Member member, String vrcId, String groupId)
    {
        try
        {
            if (member == null)
                return;
            String vrcDisplayName = null;
            String vrcAgeStatusText = null;
            try
            {
                User vrcUser = this.scarlet.vrc.getUser(vrcId, System.currentTimeMillis() - 86400_000L);
                if (vrcUser != null)
                {
                    vrcDisplayName = vrcUser.getDisplayName();
                    Object ageStatus = vrcUser.getAgeVerificationStatus();
                    if (ageStatus != null)
                        vrcAgeStatusText = ageStatus.toString();
                }
            }
            catch (Exception resolveEx)
            {
                LOG.debug("Could not resolve VRChat profile for {} while announcing verification: {}", vrcId, resolveEx.getMessage());
            }
            String vrcLabel = (vrcDisplayName == null ? "" : MarkdownSanitizer.escape(vrcDisplayName) + " ")
                + "([`" + vrcId + "`](https://vrchat.com/home/user/" + vrcId + "))";

            // Staff-facing: action-log channel
            TextChannel logChannel = this.getDiscordActionLogChannel();
            if (logChannel != null)
            {
                EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("Age Verification Complete")
                    .setColor(0x3BA55D)
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                    .addField("Member", MarkdownSanitizer.escape(member.getEffectiveName()) + " (" + member.getUser().getAsMention() + ", `" + member.getId() + "`)", false)
                    .addField("VRChat", vrcLabel, false)
                    .addField("Age verification", "`" + (vrcAgeStatusText == null ? "unknown" : vrcAgeStatusText) + "`", true)
                    .addField("Group", "`" + groupId + "`", true)
                    .addField("Outcome", "Verified and auto-invited to the group.", false);
                logChannel.sendMessageEmbeds(embed.build()).queue(
                    $ -> {},
                    error -> LOG.warn("Failed to emit verification-complete log to {}: {}", this.discordActionLogChannelSf, error.getMessage()));
            }

            // User-facing: their open ticket channel, if any
            GuildMessageChannel ticket = this.findOpenTicketChannelFor(member.getGuild(), member);
            if (ticket != null)
            {
                String message = member.getUser().getAsMention()
                    + " you're verified \u2014 a VRChat group invite has been sent to your account. "
                    + "Check your VRChat notifications to accept it and receive your role.";
                ticket.sendMessage(message).queue(
                    $ -> {},
                    error -> LOG.warn("Failed to post verification-complete message in ticket {}: {}", ticket.getId(), error.getMessage()));
            }
        }
        catch (Exception ex)
        {
            LOG.warn("Exception announcing verification completion for {} ({})", member == null ? "?" : member.getId(), vrcId, ex);
        }
    }

    void handleChannelCreate(ChannelCreateEvent event)
    {
        if (!event.isFromGuild())
            return;
        if (!Objects.equals(event.getGuild().getId(), this.guildSf))
            return;
        GuildMessageChannel messageChannel;
        try
        {
            messageChannel = event.getChannel().asGuildMessageChannel();
        }
        catch (RuntimeException ex)
        {
            return;
        }
        if (!this.isTicketToolAutoResponseCandidate(messageChannel))
            return;
        this.scarlet.exec.schedule(
            () -> this.tryTicketToolAutoResponse(messageChannel, null, "channel create"),
            2_000L,
            TimeUnit.MILLISECONDS);
    }

    void handleMessageReceived(MessageReceivedEvent event)
    {
        if (!event.isFromGuild())
            return;
        if (this.jda != null && Objects.equals(event.getAuthor().getId(), this.jda.getSelfUser().getId()))
            return;
        if (!Objects.equals(event.getGuild().getId(), this.guildSf))
            return;
        // A moderator posting context in a moderation thread counts as a valid log: capture the first
        // such message as the entry's reason so the outstanding-moderation re-ping treats it as logged.
        if (event.getChannelType().isThread() && !event.getAuthor().isBot())
        {
            String moderationAuditId = this.moderationThreadSf2auditId.get(event.getChannel().getId());
            if (moderationAuditId != null)
            {
                ScarletData.AuditEntryMetadata em = this.scarlet.data.auditEntryMetadata(moderationAuditId);
                if (em != null && !em.hasTags() && !em.hasDescription())
                {
                    String content = event.getMessage().getContentDisplay();
                    if (content != null && !content.trim().isEmpty())
                        this.scarlet.data.auditEntryMetadata_setDescription(moderationAuditId, MiscUtils.maybeEllipsis(1024, content.trim()));
                }
            }
        }
        GuildMessageChannel channel = event.getGuildChannel();
        if (Duration.between(channel.getTimeCreated(), OffsetDateTime.now(ZoneOffset.UTC)).compareTo(TICKET_TOOL_AUTO_RESPONSE_MAX_CHANNEL_AGE) > 0)
            return;
        if (!this.isTicketToolAutoResponseCandidate(channel))
            return;
        Member opener = this.inferTicketToolMemberFromMessage(event.getMessage());
        if (opener == null)
            opener = event.getMember();
        this.tryTicketToolAutoResponse(channel, opener, "message received");
    }

    boolean isTicketToolAutoResponseCandidate(GuildMessageChannel channel)
    {
        if (!this.ticketToolAutoResponseEnabled || channel == null)
            return false;
        if (!Objects.equals(channel.getGuild().getId(), this.guildSf))
            return false;
        boolean categoryMatch = this.ticketToolCategorySf != null
            && channel instanceof ICategorizableChannel
            && Objects.equals(((ICategorizableChannel)channel).getParentCategoryId(), this.ticketToolCategorySf);
        boolean nameMatch = this.ticketToolChannelNamePattern != null
            && this.ticketToolChannelNamePattern.matcher(channel.getName()).matches();
        return categoryMatch || nameMatch;
    }

    void tryTicketToolAutoResponse(GuildMessageChannel channel, Member opener, String source)
    {
        if (!this.isTicketToolAutoResponseCandidate(channel))
            return;
        if (this.ticketToolAutoRespondedChannels.contains(channel.getId()))
            return;
        if (opener != null && opener.getUser().isBot())
            opener = null;
        if (opener == null)
            opener = this.inferTicketToolMemberFromOverrides(channel);
        if (opener != null)
        {
            this.sendTicketToolAutoResponse(channel, opener, source);
            return;
        }
        channel.getHistoryFromBeginning(10).queue(
            history -> {
                Member historyOpener = this.inferTicketToolMemberFromMessages(history.getRetrievedHistory());
                if (historyOpener != null)
                    this.sendTicketToolAutoResponse(channel, historyOpener, source + " history");
                else
                    LOG.debug("Ticket Tool auto-response skipped in {} ({}): opener could not be inferred", channel.getName(), channel.getId());
            },
            error -> LOG.debug("Ticket Tool auto-response could not inspect history for {} ({}): {}", channel.getName(), channel.getId(), error.getMessage())
        );
    }

    void sendTicketToolAutoResponse(GuildMessageChannel channel, Member opener, String source)
    {
        if (channel == null || opener == null || opener.getUser().isBot())
            return;
        if (!this.ticketToolAutoRespondedChannels.add(channel.getId()))
            return;
        String notifyRoleMention = this.ticketToolNotifyRoleSf == null || this.ticketToolNotifyRoleSf.trim().isEmpty()
            ? ""
            : " <@&" + this.ticketToolNotifyRoleSf.trim() + ">";
        String message = opener.getUser().getAsMention() + notifyRoleMention + "\n\n" + this.ticketToolAgeVerificationMessage;
        channel.sendMessage(message).queue(
            sent -> LOG.info("Ticket Tool auto-response sent in {} ({}) for opener {} ({}) via {}",
                channel.getName(), channel.getId(), opener.getEffectiveName(), opener.getId(), source),
            error -> {
                this.ticketToolAutoRespondedChannels.remove(channel.getId());
                LOG.warn("Ticket Tool auto-response failed in {} ({}): {}", channel.getName(), channel.getId(), error.getMessage());
            }
        );
    }

    Member inferTicketToolMemberFromOverrides(GuildMessageChannel channel)
    {
        if (!(channel instanceof IPermissionContainer))
            return null;
        List<Member> candidates = new ArrayList<>();
        for (PermissionOverride override : ((IPermissionContainer)channel).getMemberPermissionOverrides())
        {
            if (override == null || !override.isMemberOverride())
                continue;
            Member member = override.getMember();
            if (member == null || member.getUser().isBot())
                continue;
            if (!override.getAllowed().contains(Permission.VIEW_CHANNEL))
                continue;
            candidates.add(member);
        }
        if (candidates.size() == 1)
            return candidates.get(0);
        if (!candidates.isEmpty())
            return candidates.get(0);
        return null;
    }

    Member inferTicketToolMemberFromMessages(List<Message> messages)
    {
        if (messages == null || messages.isEmpty())
            return null;
        Set<String> seen = new HashSet<>();
        for (Message message : messages)
        {
            Member member = this.inferTicketToolMemberFromMessage(message);
            if (member != null && seen.add(member.getId()))
                return member;
        }
        return null;
    }

    Member inferTicketToolMemberFromMessage(Message message)
    {
        if (message == null)
            return null;
        for (Member member : message.getMentions().getMembers())
        {
            if (member != null && !member.getUser().isBot())
                return member;
        }
        Member author = message.getMember();
        return author != null && !author.getUser().isBot() ? author : null;
    }

    static Map<String, Integer> snapshotInviteUses(List<Invite> invites)
    {
        Map<String, Integer> uses = new HashMap<>();
        if (invites != null)
            for (Invite invite : invites)
                if (invite != null && invite.getCode() != null)
                    uses.put(invite.getCode(), Integer.valueOf(invite.getUses()));
        return uses;
    }

    static Invite findUsedInvite(Map<String, Integer> before, List<Invite> after)
    {
        if (before == null || after == null)
            return null;
        for (Invite invite : after)
        {
            if (invite == null || invite.getCode() == null)
                continue;
            Integer previousUses = before.get(invite.getCode());
            if (previousUses != null && invite.getUses() > previousUses.intValue())
                return invite;
        }
        return null;
    }

    public boolean checkMemberHasVRChatPermission(GroupPermissions vrchatPermission, Member member)
    {
        if (vrchatPermission == null)
            return true;
        String userId = this.scarlet.data.globalMetadata_getSnowflakeId(member.getId());
        return this.scarlet.vrc.checkUserHasVRChatPermission(vrchatPermission, userId);
    }
    public boolean checkMemberRespondVrcPerms(GroupPermissions perms, InteractionHook hook, Member member)
    {
        if (this.checkMemberHasVRChatPermission(perms, member))
            return true;
        hook.sendMessage(this.scarlet.vrc.messageNeedPerms(perms)).setEphemeral(true).queue();
        return false;
    }
    public boolean checkSelfRespondVrcPerms(GroupPermissions perms, InteractionHook hook)
    {
        if (this.scarlet.vrc.checkSelfUserHasVRChatPermission(perms))
            return true;
        hook.sendMessage(this.scarlet.vrc.messageNeedPerms(perms)).setEphemeral(true).queue();
        return false;
    }
    public boolean checkSelfRespondVrcPerms(GroupPermissions perms, IReplyCallback event)
    {
        if (this.scarlet.vrc.checkSelfUserHasVRChatPermission(perms))
            return true;
        event.reply(this.scarlet.vrc.messageNeedPerms(perms)).setEphemeral(true).queue();
        return false;
    }

    public boolean checkMemberHasScarletPermission(ScarletPermission scarletPermission, Member member, boolean fallback)
    {
        if (scarletPermission == null)
            return fallback;
        return this.perms.check(member, scarletPermission.id, fallback);
    }

    @FunctionalInterface interface CondEmit { Message emit(String channelSf, Guild guild, GuildMessageChannel channel); }
    boolean condEmit(ScarletData.AuditEntryMetadata entryMeta, CondEmit condEmit)
    {
        if (entryMeta.hasMessage()) return true; // A retry must not duplicate an acknowledged primary send.
        boolean isSecretStaff = this.scarlet.secretStaffList.isSecretStaffId(entryMeta.entry.getActorId())
                || this.scarlet.secretStaffList.isSecretStaffId(entryMeta.auxActorId);
        // Simulated (training) moderation entries go ONLY to the dedicated training channel, never
        // the real moderation log; with no training channel configured they are dropped.
        boolean training = entryMeta.entry.getId() != null && entryMeta.entry.getId().startsWith(ScarletSimulation.TRAINING_AUDIT_ID_PREFIX);
        String channelSf = training ? this.trainingChannelSf
                         : isSecretStaff ? this.auditType2secretChannelSf.get(entryMeta.entry.getEventType())
                                         : this.auditType2channelSf.get(entryMeta.entry.getEventType());
        if (channelSf == null || channelSf.trim().isEmpty())
            return false;
        if (this.jda == null)
            throw new IllegalStateException("Discord is not connected; configured audit delivery remains pending");
        Guild guild = this.jda.getGuildById(this.guildSf);
        if (guild == null)
            throw new IllegalStateException("Configured audit guild is unavailable");
        TextChannel channel = guild.getTextChannelById(channelSf);
        if (channel == null)
            throw new IllegalStateException("Configured audit channel is unavailable");
        
        entryMeta.guildSnowflake = this.guildSf;
        entryMeta.channelSnowflake = channelSf;
        Message message = condEmit.emit(channelSf, guild, channel);
        entryMeta.messageSnowflake = message.getId();
        entryMeta.setAuxData("deliveryAcknowledged", true);
        this.saveAuditDeliveryProgress(this.scarlet, entryMeta);
        return true;
    }
    void condEmitEx(GroupAuditTypeEx auditExType, boolean log, boolean isSecretStaff, String location, CondEmit condEmit)
    {
        this.condEmitEx(false, auditExType, log, isSecretStaff, location, condEmit);
    }

    void condEmitEx(boolean training, GroupAuditTypeEx auditExType, boolean log, boolean isSecretStaff, String location, CondEmit condEmit)
    {
        if (this.jda == null)
            return;
        if (auditExType == null)
            return;
        String channelSf;
        if (training)
        {
            // Simulated (training) events go only to the dedicated training channel, so
            // drills never mix into the real moderation log. If no training channel is
            // configured, drop silently rather than spilling into a real audit channel.
            channelSf = this.trainingChannelSf;
        }
        else
        {
            channelSf = isSecretStaff ? this.auditExType2secretChannelSf.get(auditExType.id)
                                      : this.auditExType2channelSf.get(auditExType.id);
        }
        String guildSf = this.guildSf;
        if (channelSf == null || channelSf.trim().isEmpty())
            return;
        String threadSf = null;
        if (!training && location != null)
        {
            ScarletData.InstanceEmbedMessage instanceEmbedMessage = this.scarlet.data.liveInstancesMetadata_getLocationInstanceEmbedMessage(location, false);
            if (instanceEmbedMessage != null && Objects.equals(channelSf, instanceEmbedMessage.channelSnowflake))
            {
                guildSf = instanceEmbedMessage.guildSnowflake;
                threadSf = instanceEmbedMessage.threadSnowflake;
            }
        }
        Guild guild = this.jda.getGuildById(guildSf);
        if (guild == null)
            return;
        GuildMessageChannel channel = null;
        if (threadSf != null)
            channel = guild.getThreadChannelById(threadSf);
        if (channel == null)
            channel = guild.getTextChannelById(channelSf);
        else
            channelSf = threadSf;
        if (channel == null)
            return;
        
        Message message = condEmit.emit(channelSf, guild, channel);
        
        if (log)
        {
            LOG.info(String.format("%s (%s/%s/%s)", auditExType.id.replace('.', ' '), this.guildSf, channelSf, message == null ? null : message.getId()));
        }
    }
    EmbedBuilder embed(ScarletData.AuditEntryMetadata entryMeta, boolean addTargetIdField)
    {
        EmbedBuilder embed = new AuditEmbedBuilder()
            .setDescription(AuditEmbedBuilder.clip(entryMeta.entry.getDescription(), 3000))
            .setColor(GroupAuditType.color(this.auditType2color, entryMeta.entry.getEventType()))
            .setTimestamp(entryMeta.entry.getCreatedAt())
            .setAuthor(AuditEmbedBuilder.clip(entryMeta.hasAuxActor() ? entryMeta.auxActorDisplayName : entryMeta.entry.getActorDisplayName(), 256), "https://vrchat.com/home/user/"+(entryMeta.hasAuxActor() ? entryMeta.auxActorId : entryMeta.entry.getActorId()))
            .setFooter(ScarletDiscord.FOOTER_PREFIX + entryMeta.entry.getId())
        ;
        if (addTargetIdField)
            embed.addField("Target id", entryMeta.entry.getTargetId(), false);
        return embed;
    }

    @FunctionalInterface interface CondEmitEmbed { void emitEmbed(String channelSf, Guild guild, TextChannel channel, EmbedBuilder embed); }
    // Debug Event Console: one-line summary of what an event would post, mirrored to the console
    // sink so a tester can see output without a live Discord bot/channel.
    static String debugEventSummary(String kind, ScarletData.AuditEntryMetadata entryMeta, String title)
    {
        String actor = entryMeta.hasAuxActor() ? entryMeta.auxActorDisplayName : entryMeta.entry.getActorDisplayName();
        String desc = entryMeta.entry.getDescription();
        return String.format("[%s] %s%s | actor=%s target=%s%s",
            kind,
            entryMeta.entry.getEventType(),
            title != null && !title.isEmpty() ? " “" + title + "”" : "",
            String.valueOf(actor),
            String.valueOf(entryMeta.entry.getTargetId()),
            desc != null && !desc.isEmpty() ? " | " + desc : "");
    }

    void condEmitEmbed(ScarletData.AuditEntryMetadata entryMeta, boolean addTargetIdField, String title, String url, Map<String, GroupAuditType.UpdateSubComponent> updates, Consumer<EmbedBuilder> condEmitEmbed)
    {
        EmbedBuilder embed = this.embed(entryMeta, addTargetIdField);
        if (title != null)
            embed.setTitle(title, url);
        // Capture the summary before any component-dependent embed decoration, so the Event Console
        // still shows the event even if that decoration needs live data we don't have offline.
        if (net.sybyline.scarlet.Debug.ENABLED)
            net.sybyline.scarlet.Debug.emit(debugEventSummary("EMBED", entryMeta, title));
        if (condEmitEmbed != null)
            condEmitEmbed.accept(embed);
        this.condEmit(entryMeta, (channelSf, guild, channel) ->
        {
            if (updates != null && !updates.isEmpty())
                updates.forEach((key, sub) -> {
                    String oldValue = String.valueOf(sub.oldValue),
                           newValue = String.valueOf(sub.newValue);
                    if (oldValue.length() > 480) oldValue = oldValue.substring(0, 480).concat("...");
                    if (newValue.length() > 480) newValue = newValue.substring(0, 480).concat("...");
                    embed.addField("`"+key+"`", "old: `"+oldValue+"`\nnew :`"+newValue+"`", false);
                });
            return channel.sendMessageEmbeds(embed.build()).complete();
        });
    }

    void emitAuxWh(AuditEntryMetadata entryMeta, Function<IncomingWebhookClient, AbstractWebhookMessageAction<?, ?>> function)
    {
        this.emitAuxWh(entryMeta, null, (client, state) -> function.apply(client));
    }
    <State> void emitAuxWh(AuditEntryMetadata entryMeta, Supplier<State> state, BiFunction<IncomingWebhookClient, State, AbstractWebhookMessageAction<?, ?>> bifunction)
    {
        if (!Features.AUX_WEBHOOKS_ENABLED)
            return;
        if (!ScarletAuditPrivacy.auxiliaryAllowed(entryMeta, this.scarlet.secretStaffList::isSecretStaffId))
            return;
        String type = entryMeta.entry.getEventType();
        UniqueStrings scarletAuxWhs = this.auditType2scarletAuxWh.get(type);
        if (scarletAuxWhs == null || scarletAuxWhs.isEmpty())
            return;
        State payload = null;
        boolean prepared = false;
        for (String scarletAuxWh : scarletAuxWhs.toArray())
        {
            String acknowledgement = "deliveryAux:" + scarletAuxWh;
            if (entryMeta.getAuxData(acknowledgement, false, e -> e.getAsBoolean())) continue;
            IncomingWebhookClient client = this.getOrCreateIWC(scarletAuxWh);
            if (client == null)
            {
                throw new IllegalStateException("Configured auxiliary audit destination is unavailable");
            }
            if (!prepared)
            {
                payload = state == null ? null : state.get();
                prepared = true;
            }
            AbstractWebhookMessageAction<?, ?> action = bifunction.apply(client, payload);
            if (action != null)
            {
                // Await acknowledgement so the outbox can retry failed destinations after restart.
                action.completeAfter(3_000L, TimeUnit.MILLISECONDS);
                entryMeta.setAuxData(acknowledgement, true);
                entryMeta.setAuxData("deliveryAcknowledged", true);
                this.saveAuditDeliveryProgress(this.scarlet, entryMeta);
            }
        }
    }

    final Pacer userModerationLimiter = new Pacer(3_000L);
    private ScarletAuditFollowupWorker auditFollowupWorker;
    private boolean auditFollowupClosed;

    @Override public boolean requiresAuditFollowup(AuditEntryMetadata metadata)
    {
        GroupAuditType type = GroupAuditType.of(metadata.entry.getEventType());
        return (type == GroupAuditType.INSTANCE_WARN || type == GroupAuditType.INSTANCE_KICK
            || type == GroupAuditType.MEMBER_REMOVE || type == GroupAuditType.USER_BAN || type == GroupAuditType.USER_UNBAN)
            && metadata.hasMessage() && !metadata.hasParentEvent()
            && !metadata.getAuxData("deliveryModerationThread", false, e -> e.getAsBoolean());
    }

    @Override public synchronized void scheduleAuditFollowup(Scarlet scarlet, String id)
    {
        if (this.auditFollowupClosed) return;
        if (this.auditFollowupWorker == null)
            this.auditFollowupWorker = new ScarletAuditFollowupWorker(this::runAuditWork);
        this.auditFollowupWorker.submit(id);
    }

    void runAuditWork(String id)
    {
        try
        {
            AuditEntryMetadata receipt;
            synchronized (this)
            {
                ScarletAuditOutbox outbox = new ScarletAuditOutbox(this.scarlet.data.dir);
                if (!outbox.exists(id)) return;
                receipt = outbox.read(id);
            }
            // A queued wakeup carries no destination or mutable payload. The ordinary
            // serialized path rereads the receipt, current policy and backoff before sending.
            if (ScarletAuditOutbox.isDependency(receipt)) this.process(this.scarlet, receipt.entry);
            else this.runAuditFollowup(id);
        }
        catch (RuntimeException ex)
        {
            LOG.error("Unable to dispatch queued audit {} ({}); durable receipt retained", id, ex.getClass().getSimpleName());
        }
    }

    // Thread ids can advance after an acknowledged create; the event identity, audience and
    // original parent must not change. User edits are read separately at every short commit.
    static boolean sameAuditFollowupContext(AuditEntryMetadata queued, AuditEntryMetadata current)
    {
        if (queued == null || current == null) return false;
        if (queued.entry == null || current.entry == null
            || !Objects.equals(queued.entry.getGroupId(), current.entry.getGroupId())) return false;
        if (queued.threadSnowflake != null && !Objects.equals(queued.threadSnowflake, queued.messageSnowflake)) return false;
        if (current.threadSnowflake != null && !Objects.equals(current.threadSnowflake, queued.messageSnowflake)) return false;
        AuditEntryMetadata comparison = Scarlet.GSON.fromJson(Scarlet.GSON.toJson(queued), AuditEntryMetadata.class);
        comparison.threadSnowflake = current.threadSnowflake;
        return ScarletAuditPrivacy.sameRecordedAudit(comparison, current);
    }

    boolean auditFollowupGroupCurrent(AuditEntryMetadata metadata)
    {
        return metadata != null && metadata.entry != null && this.scarlet.vrc != null
            && metadata.entry.getGroupId() != null
            && Objects.equals(metadata.entry.getGroupId(), this.scarlet.vrc.groupId);
    }

    void runAuditFollowup(String id)
    {
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(this.scarlet.data.dir);
        try
        {
            AuditEntryMetadata receipt;
            synchronized (this)
            {
                if (!outbox.exists(id)) return;
                receipt = outbox.read(id);
                if (!ScarletAuditOutbox.isFollowup(receipt) || !ScarletAuditOutbox.due(receipt, System.currentTimeMillis())) return;
                // A disk failure during foreground hand-off may leave only the durable receipt.
                if (!this.scarlet.data.auditEntryMetadataExists(id)) this.scarlet.data.auditEntryMetadata(id, receipt);
            }
            AuditEntryMetadata metadata = this.scarlet.data.auditEntryMetadata(id);
            if (!this.auditFollowupGroupCurrent(metadata) || !sameAuditFollowupContext(receipt, metadata))
                throw new IllegalStateException("Audit follow-up security context changed");
            metadata.setAuxData("deliveryState", "followup_pending");
            if (metadata.threadSnowflake == null && receipt.threadSnowflake != null) metadata.threadSnowflake = receipt.threadSnowflake;
            if (metadata.auxMessageSnowflake == null && receipt.auxMessageSnowflake != null) metadata.auxMessageSnowflake = receipt.auxMessageSnowflake;
            Guild guild = this.jda == null ? null : this.jda.getGuildById(metadata.guildSnowflake);
            TextChannel channel = guild == null ? null : guild.getTextChannelById(metadata.channelSnowflake);
            if (channel == null) throw new IllegalStateException("Moderation thread destination is unavailable");
            Message message = channel.retrieveMessageById(metadata.messageSnowflake).complete();
            String actorId = metadata.hasAuxActor() ? metadata.auxActorId : metadata.entry.getActorId();
            this.emitUserModeration_thread(this.scarlet, metadata, actorId, message);
            // The per-event operation may have adopted a manual repair's newer receipt while
            // waiting. Complete that committed record, never the older outer snapshot.
            metadata = this.scarlet.data.auditEntryMetadata(id);
            if (metadata == null) throw new IllegalStateException("Moderation follow-up record disappeared");
            metadata.setAuxData("deliveryState", "followup_pending");
            metadata.setAuxData("deliveryModerationThread", true);
            this.persistModerationProgress(metadata, true);
        }
        catch (Exception ex)
        {
            synchronized (this)
            {
                if (outbox.exists(id))
                {
                    AuditEntryMetadata pending = outbox.read(id);
                    if (!ScarletAuditOutbox.complete(pending))
                    {
                        int attempts = pending.getAuxData("deliveryAttempts", 0, e -> e.getAsInt()) + 1;
                        pending.setAuxData("deliveryAttempts", attempts);
                        pending.setAuxData("deliveryRetryAt", System.currentTimeMillis() + ScarletAuditOutbox.retryDelay(attempts));
                        outbox.save(pending);
                        if (attempts == 1 || (attempts & (attempts - 1)) == 0)
                            this.emitOpsAlert("Audit thread delivery pending", "An audit embed was delivered, but its moderation thread could not be completed. Scarlet retained the follow-up and will retry. Check local logs and thread permissions.", 0xFEE75C);
                    }
                }
            }
            LOG.warn("Moderation thread follow-up remains pending for {} ({}); the parent embed is already delivered", id, ex.getClass().getSimpleName());
        }
    }

    void persistModerationProgress(AuditEntryMetadata metadata, boolean complete)
    {
        if (!ScarletAuditOutbox.isFollowup(metadata))
        {
            this.saveAuditDeliveryProgress(this.scarlet, metadata);
            this.scarlet.data.auditEntryMetadata(metadata.entry.getId(), metadata);
            if (metadata.threadSnowflake != null)
                this.wakeAuditDependents(this.scarlet, metadata.entry.getId());
            return;
        }
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(this.scarlet.data.dir);
        // Serialize only local receipt transitions with polling, never the network operations.
        // In particular, a poll must not replay an intermediate terminal receipt while it is
        // being committed and removed by this worker.
        synchronized (this)
        {
        this.scarlet.data.auditEntryMetadata(metadata.entry.getId(), current -> {
            if (!sameAuditFollowupContext(metadata, current)
                || !this.auditFollowupGroupCurrent(current)
                || !Objects.equals(current.guildSnowflake, this.guildSf)
                || !Objects.equals(current.channelSnowflake, this.approvedAuditChannel(current)))
                throw new IllegalStateException("Audit follow-up context changed before saving");
            if (current.auxMessageSnowflake != null && metadata.auxMessageSnowflake != null
                && !Objects.equals(current.auxMessageSnowflake, metadata.auxMessageSnowflake))
                throw new IllegalStateException("A newer moderation-button acknowledgement must not be overwritten");
            if (metadata.threadSnowflake != null) current.threadSnowflake = metadata.threadSnowflake;
            if (metadata.auxMessageSnowflake != null) current.auxMessageSnowflake = metadata.auxMessageSnowflake;
            current.setAuxData("deliveryState", complete ? "delivered" : "followup_pending");
            if (complete) current.setAuxData("deliveryModerationThread", true);
            if (complete) outbox.finish(this.scarlet, current);
            else outbox.save(current);
            return current;
        });
        if (metadata.threadSnowflake != null)
            this.wakeAuditDependents(this.scarlet, metadata.entry.getId());
        }
    }

    AuditEntryMetadata currentBundledBan(AuditEntryMetadata kick, AuditEntryMetadata candidate)
    {
        String dependency = ScarletAuditOutbox.dependency(kick);
        String id = dependency != null ? dependency : candidate == null || candidate.entry == null ? null : candidate.entry.getId();
        if (id == null) return null;
        AuditEntryMetadata current = this.scarlet.data.auditEntryMetadata(id);
        if (current == null || current.entry == null || kick.entry == null
            || current.entryRedacted || kick.entryRedacted || current.hasParentEvent()
            || !"group.user.ban".equals(current.entry.getEventType()) || !"group.instance.kick".equals(kick.entry.getEventType())
            || !this.auditFollowupGroupCurrent(current)
            || !Objects.equals(kick.entry.getGroupId(), current.entry.getGroupId())
            || !Objects.equals(kick.entry.getActorId(), current.entry.getActorId())
            || !Objects.equals(kick.entry.getTargetId(), current.entry.getTargetId())
            || (kick.entry.getId() != null && kick.entry.getId().startsWith(ScarletSimulation.TRAINING_AUDIT_ID_PREFIX))
                != (current.entry.getId() != null && current.entry.getId().startsWith(ScarletSimulation.TRAINING_AUDIT_ID_PREFIX))
            || current.entry.getCreatedAt() == null || kick.entry.getCreatedAt() == null
            || !kick.entry.getCreatedAt().minusMinutes(5L).isBefore(current.entry.getCreatedAt())
            || current.entry.getCreatedAt().isAfter(kick.entry.getCreatedAt())
            || ScarletAuditPrivacy.confidential(current, this.scarlet.secretStaffList::isSecretStaffId)
                != ScarletAuditPrivacy.confidential(kick, this.scarlet.secretStaffList::isSecretStaffId))
            throw new IllegalStateException("The associated ban or its current approved audience cannot be verified");
        // A deliberately unconfigured ban must not prevent its separately configured kick
        // from posting. This fallback is only for a first attempt, never a recorded parent.
        if (dependency == null && !current.hasMessage()) return null;
        if (this.approvedAuditChannel(current) == null
            || !Objects.equals(this.guildSf, current.guildSnowflake)
            || !Objects.equals(this.approvedAuditChannel(current), current.channelSnowflake))
            throw new IllegalStateException("The associated ban or its current approved audience cannot be verified");
        return current;
    }

    AuditEntryMetadata preparedBundledBan(AuditEntryMetadata kick, AuditEntryMetadata candidate)
    {
        AuditEntryMetadata parent = this.currentBundledBan(kick, candidate);
        if (parent == null || parent.threadSnowflake != null) return parent;
        ScarletAuditOutbox pendingThreads = new ScarletAuditOutbox(this.scarlet.data.dir);
        if (pendingThreads.exists(parent.entry.getId())
            && ScarletAuditOutbox.isFollowup(pendingThreads.read(parent.entry.getId())))
            throw new ScarletAuditDependencyPending(parent.entry.getId());
        if (ScarletAuditOutbox.dependency(kick) != null)
            throw new IllegalStateException("The associated ban has no acknowledged moderation thread");
        // Older completed/suppressed records can have no thread. Preserve the independent
        // kick route only when this kick has never been bound to a pending/bundled parent.
        return null;
    }

    void captureModerationBanInfo(AuditEntryMetadata metadata)
    {
        if (!"group.user.ban".equals(metadata.entry.getEventType())
            || metadata.getAuxData("deliveryBanInfoCaptured", false, e -> e.getAsBoolean())) return;
        ScarletPendingModActions.BanInfo pending = this.scarlet.pendingModActions.pollBanInfo(metadata.entry.getTargetId());
        if (pending != null)
        {
            if (pending.tags != null) metadata.entryTags.addAll(pending.tags);
            if (pending.description != null && !pending.description.trim().isEmpty()) metadata.entryDescription = pending.description;
        }
        metadata.setAuxData("deliveryBanInfoCaptured", true);
        this.saveAuditDeliveryProgress(this.scarlet, metadata);
    }

    @Override
    public void emitUserModeration(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, User actor, User target, ScarletData.UserMetadata actorMeta, ScarletData.UserMetadata targetMeta, String history, String recent, ScarletData.AuditEntryMetadata parentEntryMeta, boolean reactiveKickFromBan)
    {
        long moderationStarted = System.nanoTime();
        if (net.sybyline.scarlet.Debug.ENABLED)
            net.sybyline.scarlet.Debug.emit(debugEventSummary("MODERATION", entryMeta, GroupAuditType.of(entryMeta.entry.getEventType()) != null ? GroupAuditType.of(entryMeta.entry.getEventType()).title : null));
        this.condEmit(entryMeta, (channelSf, guild, channel) ->
        {
            // The VRChat API does not always resolve the target user (rate limits, private profiles,
            // transient failures). Fall back to the audit entry's target id so the log and report links
            // still post instead of throwing and dropping the whole moderation event.
            String targetId = target != null ? target.getId() : entryMeta.entry.getTargetId();
            String targetName = target != null ? target.getDisplayName() : (targetId != null ? targetId : "Unknown user");
            if (targetId != null)
            {
                OffsetDateTime targetJoined = this.scarlet.eventListener.getJoinedOrNull(targetId);
                if (targetJoined != null)
                {
                    entryMeta.setAuxData("targetJoined", targetJoined.toEpochSecond());
                }
            }
            if (ScarletAuditOutbox.dependency(entryMeta) != null && !this.bundleModerations_instanceKick2userBan.get())
                throw new IllegalStateException("Bundling was disabled while this kick was awaiting its recorded ban thread");
            // Re-read the parent instead of routing a queued kick using stale history metadata.
            AuditEntryMetadata bundledParent = this.bundleModerations_instanceKick2userBan.get()
                && (reactiveKickFromBan || ScarletAuditOutbox.dependency(entryMeta) != null)
                ? this.preparedBundledBan(entryMeta, parentEntryMeta) : null;
            this.userModerationLimiter.await();
            
            if (bundledParent != null)
            {
                // The pacing wait can overlap policy edits or a manual thread repair.
                bundledParent = this.currentBundledBan(entryMeta, bundledParent);
                ThreadChannel threadChannel = guild.getThreadChannelById(bundledParent.threadSnowflake);
                if (threadChannel == null)
                    throw new IllegalStateException("The associated ban thread is currently unavailable");
                String problem = this.auditThreadAccessProblem(bundledParent, threadChannel, null);
                if (problem != null) throw new IllegalStateException(problem);
                if (threadChannel != null)
                {
                    entryMeta.setAuxData("deliveryDependencyEvent", bundledParent.entry.getId());
                    entryMeta.parentEventId = bundledParent.entry.getId();
                    entryMeta.auxActorId = bundledParent.auxActorId;
                    entryMeta.auxActorDisplayName = bundledParent.auxActorDisplayName;
                    entryMeta.guildSnowflake = bundledParent.guildSnowflake;
                    entryMeta.channelSnowflake = bundledParent.channelSnowflake;
                    entryMeta.threadSnowflake = bundledParent.threadSnowflake;
                    Message message = threadChannel
                        .sendMessageEmbeds(this.embed(entryMeta, true)
                            .setTitle(MarkdownSanitizer.escape(targetName), targetId != null ? "https://vrchat.com/home/user/"+targetId : null)
                            .build())
                        .setMessageReference(bundledParent.messageSnowflake)
                        .failOnInvalidReply(false)
                        .mentionRepliedUser(false)
                        .complete();
                    return message;
                }
            }
            
            EmbedBuilder embed = this.embed(entryMeta, true)
                .setTitle(MarkdownSanitizer.escape(targetName), targetId != null ? "https://vrchat.com/home/user/"+targetId : null)
            ;
            boolean trainingTarget = ScarletSimulation.isTrainingId(targetId);
            if (targetId != null && !trainingTarget)
                embed.setImage(this.scarlet.vrc.getUserImageUrl(targetId));
            
            // Simulated (training) users don't exist on VRChat - don't spend API calls on guaranteed 404s.
            List<LimitedUserGroups> lugs = trainingTarget ? null : this.scarlet.vrc.snapshot(entryMeta);
            
            if (target != null)
            {
                if (target.getDateJoined() != null)
                {
                    String epochJoined = Long.toUnsignedString(target.getDateJoined().toEpochDay() * 86400L);
                    embed.addField("Account age", "<t:"+epochJoined+":D> (<t:"+epochJoined+":R>)", false);
                }
                if (target.getAgeVerificationStatus() != null)
                    embed.addField("Age verification", "`"+target.getAgeVerificationStatus()+"`", false);
                if (target.getPronouns() != null && !target.getPronouns().trim().isEmpty())
                    embed.addField("Pronouns", "`"+MarkdownSanitizer.escape(target.getPronouns())+"`", false);
                if (target.getStatusDescription() != null && !target.getStatusDescription().trim().isEmpty())
                    embed.addField("Status description", "`"+MarkdownSanitizer.escape(target.getStatusDescription())+"`", false);
            }
            else
            {
                embed.addField("Note", "VRChat did not return full profile data for this user; some fields are omitted.", false);
            }
            if (history != null)
                embed.addField("History", history, false);
            if (recent != null)
                embed.addField("Most recent", recent, false);
            if (lugs != null && !lugs.isEmpty())
            {
                List<LimitedUserGroups> wgs = lugs.stream()
                    .filter(lug -> this.scarlet.watchedGroups.getWatchedGroup(lug.getGroupId()) != null)
                    .collect(Collectors.toList());
                if (!wgs.isEmpty())
                {
                    StringBuilder sb = new StringBuilder();
                    wgs.forEach(wg -> sb.append(wg.getName()).append('\n'));
                    embed.addField("Watched group membership", sb.toString(), false);
                }
            }
            if (entryMeta.hasAuxActor())
                embed.addField("Action taken through automation/assistance", "", false);
            
            // Pre-filled VRChat T&S report links for the account-level content types that have no
            // in-instance spawn event to hang a link off of (a profile picture / user icon). Sticker,
            // emoji and print reports are surfaced on their own spawn embeds. A human files the report.
            if (Features.VRCHAT_REPORTS_ENABLED && targetId != null)
            {
                String reportEmail = this.requestingEmail.get(), tid = targetId;
                embed.addField("Report profile picture", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_account_profile(reportEmail, tid, "Profile", null)), true);
                embed.addField("Report user icon", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_account_user_icon(reportEmail, tid, "User Icon", null)), true);
            }
            
            Message message = channel
                .sendMessageEmbeds(embed.build())
                .complete();
            
            return message;
        });
        if (entryMeta.hasMessage()) LOG.info("Moderation parent embed acknowledged for {} after {} ms in the embed stage",
            entryMeta.entry.getId(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - moderationStarted));
        this.captureModerationBanInfo(entryMeta);
        if (target != null)
        switch (entryMeta.entry.getEventType())
        {
        case "group.instance.kick":
            this.tryEmitExtendedSuggestedModeration(scarlet, target);
            // fallthrough
        case "group.instance.warn":
            this.tryEmitExtendedWatchedModeration(scarlet, target);
        }
        this.scarlet.desktopNotifications.notifyModeration(entryMeta, actor, target);
    }
    // Maps a moderation thread's snowflake to its audit entry id, so a moderator's message in the
    // thread can be captured as that entry's reason (making it count as a logged moderation). Bounded
    // LRU keyed by recency: only recent moderations matter for the outstanding re-ping, and the map is
    // repopulated as new moderations are posted, so it need not survive restarts.
    private final Map<String, String> moderationThreadSf2auditId = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>(1024, 0.75f, true)
    {
        private static final long serialVersionUID = 1L;
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest)
        {
            return this.size() > 5000;
        }
    });
    private static final Object[] MODERATION_THREAD_LOCKS = java.util.stream.IntStream.range(0, 256)
        .mapToObj(index -> new Object()).toArray(Object[]::new);

    ThreadChannel emitUserModeration_thread(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, String actorId, Message message)
    {
        return this.emitUserModeration_thread(scarlet, entryMeta, actorId, message, null);
    }
    ThreadChannel emitUserModeration_thread(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, String actorId, Message message, String requesterSf)
    {
        String id = entryMeta == null || entryMeta.entry == null ? "" : entryMeta.entry.getId();
        Object lock = MODERATION_THREAD_LOCKS[(Objects.hashCode(id) & Integer.MAX_VALUE) % MODERATION_THREAD_LOCKS.length];
        synchronized (lock)
        {
            // A manual repair may have finished while this automatic job waited. Reuse its
            // acknowledged thread/buttons and current user edits, never the queued snapshot.
            if (requesterSf == null && entryMeta != null && ScarletAuditOutbox.isFollowup(entryMeta))
            {
                AuditEntryMetadata latest = scarlet.data.auditEntryMetadata(id);
                if (!sameAuditFollowupContext(entryMeta, latest))
                    throw new IllegalStateException("Audit follow-up changed while waiting for its event lock");
                if (latest.threadSnowflake == null) latest.threadSnowflake = entryMeta.threadSnowflake;
                if (latest.auxMessageSnowflake == null) latest.auxMessageSnowflake = entryMeta.auxMessageSnowflake;
                latest.setAuxData("deliveryState", "followup_pending");
                entryMeta = latest;
            }
            return this.emitUserModerationThreadLocked(scarlet, entryMeta, actorId, message, requesterSf);
        }
    }

    private ThreadChannel emitUserModerationThreadLocked(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, String actorId, Message message, String requesterSf)
    {
        String problem = this.auditThreadProblem(entryMeta, message, requesterSf);
        if (problem != null) throw new IllegalStateException(problem);
        ThreadChannel threadChannel = message.getStartedThread();
        if (threadChannel == null)
        {
            if (entryMeta.threadSnowflake != null)
            {
                threadChannel = message
                    .getGuild()
                    .getThreadChannelById(entryMeta.threadSnowflake);
            }
            if (threadChannel == null)
            {
                problem = this.auditThreadProblem(entryMeta, message, requesterSf);
                if (problem != null) throw new IllegalStateException(problem);
                threadChannel = message
                    .getChannel()
                    .asTextChannel()
                    .createThreadChannel(entryMeta.entry.getDescription(), message.getId())
                    .setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.TIME_1_HOUR)
                    .complete();
            }
        }

        problem = this.auditThreadProblem(entryMeta, message, requesterSf);
        if (problem == null) problem = this.auditThreadAccessProblem(entryMeta, threadChannel, requesterSf);
        if (problem != null) throw new IllegalStateException(problem);

        this.emitUserModeration_auxMessage(scarlet, entryMeta, actorId, threadChannel, requesterSf);

        problem = this.auditThreadProblem(entryMeta, message, requesterSf);
        if (problem == null) problem = this.auditThreadAccessProblem(entryMeta, threadChannel, requesterSf);
        if (problem != null) throw new IllegalStateException(problem);
        entryMeta.threadSnowflake = threadChannel.getId();
        this.persistModerationProgress(entryMeta, false);
        this.moderationThreadSf2auditId.put(threadChannel.getId(), entryMeta.entry.getId());
        
        return threadChannel;
    }
    Message emitUserModeration_auxMessage(Scarlet scarlet, ScarletData.AuditEntryMetadata entryMeta, String actorId, ThreadChannel threadChannel, String requesterSf)
    {
        String problem = this.auditThreadAccessProblem(entryMeta, threadChannel, requesterSf);
        if (problem != null) throw new IllegalStateException(problem);
        if (entryMeta.auxMessageSnowflake != null)
        {
            Message auxMessage = threadChannel.retrieveMessageById(entryMeta.auxMessageSnowflake).complete();
            problem = this.auditThreadAccessProblem(entryMeta, threadChannel, requesterSf);
            if (problem != null) throw new IllegalStateException(problem);
            if (auxMessage != null)
                return auxMessage;
        }
        
        boolean mention;
        String contentExtra = "\nUnclaimed";
        switch (GroupAuditType.of(entryMeta.entry.getEventType()))
        {
        case INSTANCE_WARN: mention = this.pingOnModeration_instanceWarn.get(); break;
        case INSTANCE_KICK: mention = this.pingOnModeration_instanceKick.get(); break;
        case MEMBER_REMOVE: mention = this.pingOnModeration_memberRemove.get(); break;
        case USER_BAN:      mention = this.pingOnModeration_userBan     .get();
        this.captureModerationBanInfo(entryMeta);
        if (entryMeta.hasTags() || entryMeta.hasDescription())
        {
            contentExtra = "";
            if (entryMeta.hasTags())
            {
                contentExtra = contentExtra + "\n### Tags:\n" + entryMeta.entryTags.strings().stream().map(this.scarlet.moderationTags::getTagLabel).collect(Collectors.joining(", "));
            }
            if (entryMeta.hasDescription())
            {
                contentExtra = contentExtra + "\n### Description:\n" + entryMeta.entryDescription;
            }
        }
        break;
        case USER_UNBAN:    mention = this.pingOnModeration_userUnban   .get(); break;
        default:            mention = false;                                    break;
        }
        
        String timeext = entryMeta.getAuxData("targetJoined", "", $->':'+Long.toUnsignedString($.getAsLong()));
        
        ScarletData.UserMetadata actorMeta = scarlet.data.userMetadata(actorId);
        String content = actorMeta == null || actorMeta.userSnowflake == null
                ? ScarletDiscordJDA.this.linkedIdsInfo(entryMeta)
                : (mention
                    ? ("<@"+actorMeta.userSnowflake+">")
                    : (entryMeta.hasAuxActor() ? entryMeta.auxActorDisplayName : entryMeta.entry.getActorDisplayName()));
        MessageCreateAction auxAction = threadChannel.sendMessage(content)
            .addContent(contentExtra)
            .addComponents(ActionRow.of(
                Button.primary("edit-tags:"+entryMeta.entry.getId(), "Edit tags"),
                Button.primary("edit-desc:"+entryMeta.entry.getId(), "Edit description"),
                Button.primary("vrchat-report:"+entryMeta.entry.getId()+timeext, "Get report link")
            ))
            .addComponents(ActionRow.of(
                Button.secondary("view-snapshot-user:"+entryMeta.entry.getId(), "Snapshot: user"),
                Button.secondary("view-snapshot-user-groups:"+entryMeta.entry.getId(), "Snapshot: user groups"),
                Button.secondary("view-snapshot-user-represented-group:"+entryMeta.entry.getId(), "Snapshot: user represented group")
            ))
            .addComponents(ActionRow.of(
                Button.danger("vrchat-user-ban:"+entryMeta.entry.getTargetId(), "Ban user"),
                Button.success("vrchat-user-unban:"+entryMeta.entry.getTargetId(), "Unban user"),
//                Button.secondary("vrchat-user-edit-manager-notes:"+entryMeta.entry.getTargetId(), "Edit manager notes"),
                Button.primary("event-redact:"+entryMeta.entry.getId(), "Redact event"),
                Button.secondary("event-unredact:"+entryMeta.entry.getId(), "Unredact event")
            ));
        // Optional timed-ban row, gated by Settings → Moderation → "show timed-ban buttons".
        if (Boolean.TRUE.equals(scarlet.timedBansEnabled.get()))
            auxAction = auxAction.addComponents(ActionRow.of(
                Button.secondary("timed-ban:6:"+entryMeta.entry.getTargetId(), "Timed ban 6h"),
                Button.secondary("timed-ban:24:"+entryMeta.entry.getTargetId(), "Timed ban 24h"),
                Button.secondary("timed-ban:72:"+entryMeta.entry.getTargetId(), "Timed ban 3d"),
                Button.secondary("timed-ban:168:"+entryMeta.entry.getTargetId(), "Timed ban 7d"),
                Button.secondary("timed-ban-custom:"+entryMeta.entry.getTargetId(), "Timed ban…")
            ));
        // Recheck after payload construction: staff confidentiality or routing may have changed.
        problem = this.auditThreadAccessProblem(entryMeta, threadChannel, requesterSf);
        if (problem != null) throw new IllegalStateException(problem);
        Message auxMessage = auxAction.complete();

        problem = this.auditThreadAccessProblem(entryMeta, threadChannel, requesterSf);
        if (problem != null) throw new IllegalStateException(problem);
        entryMeta.auxMessageSnowflake = auxMessage.getId();
        this.persistModerationProgress(entryMeta, false);
        
        return auxMessage;
    }

    String getLocationImage(String location)
    {
        try
        {
            int colon = location.indexOf(':');
            String worldId = location.substring(0, colon >= 0 ? colon : location.length());
            long within1day = System.currentTimeMillis() - 86400_000L;
            World world = this.scarlet.vrc.getWorld(worldId, within1day);
            if (world != null)
                return world.getImageUrl();
        }
        catch (Exception ex)
        {
            LOG.error("Exception getting world image", ex);
        }
        return null;
    }
    String getLocationName(String location)
    {
        try
        {
            int colon = location.indexOf(':');
            String worldId = location.substring(0, colon >= 0 ? colon : location.length());
            long within1day = System.currentTimeMillis() - 86400_000L;
            World world = this.scarlet.vrc.getWorld(worldId, within1day);
            if (world != null)
                return world.getName();
        }
        catch (Exception ex)
        {
            LOG.error("Exception getting world name", ex);
        }
        return null;
    }

    @Override
    public void emitInstanceCreate(Scarlet scarlet, AuditEntryMetadata entryMeta, String location)
    {
        String worldImageUrl = this.getLocationImage(location),
               worldName = this.getLocationName(location);
        Location locationModel = Location.of(location);
        
        scarlet.data.liveInstancesMetadata_setLocationAudit(location, entryMeta.entry.getId());
        boolean confidential = ScarletAuditPrivacy.confidential(entryMeta, scarlet.secretStaffList::isSecretStaffId);
        String monitorChannel = (confidential ? this.auditExType2secretChannelSf : this.auditExType2channelSf).get(GroupAuditTypeEx.INSTANCE_MONITOR.id);
        if (monitorChannel != null && !monitorChannel.trim().isEmpty()
            && (this.jda == null || this.jda.getGuildById(this.guildSf) == null
                || this.jda.getGuildById(this.guildSf).getTextChannelById(monitorChannel) == null))
            throw new IllegalStateException("Configured instance monitor destination is unavailable");
        if (!entryMeta.getAuxData("deliveryInstanceMonitor", false, e -> e.getAsBoolean()))
        this.condEmitEx(GroupAuditTypeEx.INSTANCE_MONITOR, false,
            ScarletAuditPrivacy.confidential(entryMeta, scarlet.secretStaffList::isSecretStaffId), null, (channelSf, guild, channel) ->
        {
            String title = MarkdownSanitizer.escape(worldName)+" ("+MarkdownSanitizer.escape(locationModel.name)+")";
            EmbedBuilder embed = new EmbedBuilder()
                .setTitle(MarkdownSanitizer.escape(worldName)+" ("+MarkdownSanitizer.escape(locationModel.name)+")", "https://vrchat.com/home/launch?worldId="+location.replaceFirst(":", "&instanceId="))
                .setImage(worldImageUrl)
                .setColor(GroupAuditTypeEx.INSTANCE_MONITOR.color)
                .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
            String previousMessage = entryMeta.getAuxData("deliveryInstanceMonitorMessage", (String)null, e -> e.getAsString());
            Message message = previousMessage == null ? channel.sendMessageEmbeds(embed.build()).complete()
                : channel.retrieveMessageById(previousMessage).complete();
            entryMeta.setAuxData("deliveryInstanceMonitorMessage", message.getId());
            entryMeta.setAuxData("deliveryAcknowledged", true);
            this.saveAuditDeliveryProgress(scarlet, entryMeta);
            String previousThread = entryMeta.getAuxData("deliveryInstanceMonitorThread", (String)null, e -> e.getAsString());
            ThreadChannel threadChannel = previousThread == null ? message.getStartedThread() : guild.getThreadChannelById(previousThread);
            if (threadChannel == null)
                threadChannel = ((TextChannel)channel).createThreadChannel(title, message.getId())
                    .setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.TIME_1_HOUR).completeAfter(1_000L, TimeUnit.MILLISECONDS);
            entryMeta.setAuxData("deliveryInstanceMonitorThread", threadChannel.getId());
            this.saveAuditDeliveryProgress(scarlet, entryMeta);
            String previousAux = entryMeta.getAuxData("deliveryInstanceMonitorAux", (String)null, e -> e.getAsString());
            Message auxMessage = previousAux == null ? threadChannel.sendMessage("### Instance log").completeAfter(1_000L, TimeUnit.MILLISECONDS)
                : threadChannel.retrieveMessageById(previousAux).complete();
            entryMeta.setAuxData("deliveryInstanceMonitorAux", auxMessage.getId());
            this.saveAuditDeliveryProgress(scarlet, entryMeta);
            scarlet.data.liveInstancesMetadata_setLocationInstanceEmbedMessage(location, guild.getId(), channelSf, message.getId(), threadChannel.getId(), auxMessage.getId(), entryMeta.entry.getCreatedAt());
            entryMeta.setAuxData("deliveryInstanceMonitor", true);
            this.saveAuditDeliveryProgress(scarlet, entryMeta);
            return message;
        });
        this.condEmitEmbed(entryMeta, true, "Instance Open", "https://vrchat.com/home/launch?worldId="+location.replaceFirst(":", "&instanceId="), null, embed ->
        {
            this.emitAuxWh(entryMeta, embed.setImage(worldImageUrl)::build, IncomingWebhookClient::sendMessageEmbeds);
        });
        this.followInstanceCreation(location, entryMeta.entry.getCreatedAt());
    }

    // Dedup guard shared by the audit-driven path (emitInstanceCreate) and the fast
    // group-instance detection path (Scarlet.maybeFastDetectNewInstance), so a newly
    // created instance is only cold-booted into once even when both paths observe it.
    private final java.util.Map<String, Long> recentlyLaunchedLocations = new java.util.concurrent.ConcurrentHashMap<>();
    static final long RELAUNCH_SUPPRESS_MILLIS = 5L * 60L * 1000L;
    private ScarletClientLaunchWorker clientLaunchWorker;
    private volatile boolean clientLaunchClosed;
    private volatile ScarletStartupInstanceJoin startupInstanceJoin;
    private ScarletStartupInstanceJoin.Environment startupInstanceEnvironment;
    private final ScarletDeferredInstanceFollow startupDeferredFollow = new ScarletDeferredInstanceFollow();
    private final ScarletStartupRecovery startupRecovery = new ScarletStartupRecovery();

    synchronized void followInstanceCreation(String location, OffsetDateTime createdAt)
    {
        ScarletStartupInstanceJoin startup = this.startupInstanceJoin;
        // Deliver historical messages normally, but never replay their launch side-effect.
        if (startup == null || !startup.isAfterCutoff(createdAt)) return;
        if (this.startupRecovery != null) this.startupRecovery.cancelForDifferentDestination(location);
        if (startup.isPending() || this.startupDeferredFollow.peek() != null)
        {
            this.startupDeferredFollow.record(location, createdAt);
            if (!startup.isPending()) this.drainStartupFollow();
        }
        else if (!this.queueClientLaunch(location, true))
            this.startupDeferredFollow.record(location, createdAt);
    }

    private void drainStartupFollow()
    {
        ScarletDeferredInstanceFollow.Event event = this.startupDeferredFollow.peek();
        if (event == null) return;
        if (!this.startupInstanceEnvironment.allowed() || !this.startupInstanceJoin.shouldFollowCreationAudit(event.createdAt)
            || this.queueClientLaunch(event.location, false))
            this.startupDeferredFollow.clearIfSame(event);
    }

    @Override
    public synchronized void maybeJoinActiveInstanceOnStartup()
    {
        if (this.clientLaunchClosed) return;
        if (this.startupInstanceJoin == null)
        {
            final String group = this.scarlet.vrc.groupId, user = this.scarlet.vrc.currentUserId;
            final long generation = net.sybyline.scarlet.ext.ExtendedUserAgent.currentAuthenticationGeneration();
            this.startupInstanceJoin = new ScarletStartupInstanceJoin(group == null ? "" : group, OffsetDateTime.now(ZoneOffset.UTC));
            java.util.function.BooleanSupplier allowed = () -> !this.clientLaunchClosed && this.scarlet.running
                && !this.scarlet.staffMode && this.vrchatClient_launchOnInstanceCreate.get()
                && group != null && !group.isEmpty() && user != null && Objects.equals(user, this.scarlet.vrc.currentUserId)
                && Objects.equals(group, this.scarlet.vrc.groupId)
                && net.sybyline.scarlet.ext.ExtendedUserAgent.isPrimaryAuthenticationStable(generation)
                && !Thread.currentThread().isInterrupted();
            this.startupInstanceEnvironment = new ScarletStartupInstanceJoin.Environment() {
                public boolean allowed() { return allowed.getAsBoolean(); }
                public boolean clientRunning() { return VrcLaunch.isVrChatRunning(); }
                public java.util.List<String> activeLocations() throws Exception {
                    return ScarletDiscordJDA.this.scarlet.vrc.getStartupGroupInstances(group, allowed);
                }
                public ScarletStartupInstanceJoin.AuditPage creationPage(OffsetDateTime from, OffsetDateTime to, int offset) throws Exception {
                    return ScarletDiscordJDA.this.scarlet.vrc.getStartupInstanceCreationPage(group, from, to, offset, allowed);
                }
                public void launch(String location) throws Exception {
                    if (!allowed()) return;
                    ScarletDiscordJDA.this.runStartupClientLaunch(location, allowed);
                }
                public void log(String message) { LOG.info("Startup instance join: {}", message); }
            };
            if (!allowed.getAsBoolean()) this.startupInstanceJoin.cancel();
        }
        if (!this.startupInstanceEnvironment.allowed()) this.startupInstanceJoin.cancel();
        if (!this.startupInstanceJoin.isPending())
        {
            this.drainStartupFollow();
            if (this.startupRecovery != null && this.startupRecovery.active() && this.clientLaunchWorker != null)
                this.clientLaunchWorker.submit(this.startupRecovery::tick);
            return;
        }
        long now = System.currentTimeMillis();
        if (!this.startupInstanceJoin.isDue(now)) return;
        if (this.clientLaunchWorker == null) this.clientLaunchWorker = new ScarletClientLaunchWorker();
        this.clientLaunchWorker.submit(() -> this.startupInstanceJoin.attempt(this.startupInstanceEnvironment, now));
    }

    @Override
    public boolean isStartupInstanceJoinPending()
    {
        ScarletStartupInstanceJoin startup = this.startupInstanceJoin;
        return startup == null || startup.isPending();
    }

    private void closeClientLaunchWorker() throws IOException
    {
        ScarletClientLaunchWorker worker;
        synchronized (this)
        {
            this.clientLaunchClosed = true;
            if (this.startupRecovery != null) this.startupRecovery.cancel();
            if (this.startupInstanceJoin != null) this.startupInstanceJoin.cancel();
            worker = this.clientLaunchWorker;
        }
        if (worker != null) worker.close();
    }

    @Override
    public boolean isLaunchOnInstanceCreateEnabled()
    {
        return this.vrchatClient_launchOnInstanceCreate.get();
    }

    @Override
    public synchronized void launchClientIntoInstanceIfEnabled(String location)
    {
        this.queueClientLaunch(location, true);
    }

    /** Returns true only once this request was accepted or deliberately suppressed, never while busy. */
    synchronized boolean queueClientLaunch(String location, boolean reportBusy)
    {
        if (location == null || this.clientLaunchClosed || this.isStartupInstanceJoinPending()
            || !this.vrchatClient_launchOnInstanceCreate.get())
            return true;
        long now = System.currentTimeMillis();
        Long prev = this.recentlyLaunchedLocations.get(location);
        this.recentlyLaunchedLocations.values().removeIf(t -> now - t.longValue() > RELAUNCH_SUPPRESS_MILLIS);
        if (prev != null && now - prev.longValue() < RELAUNCH_SUPPRESS_MILLIS)
            return true; // already cold-booted into this instance very recently
        if (this.clientLaunchWorker == null)
            this.clientLaunchWorker = new ScarletClientLaunchWorker();
        ScarletClientLaunchWorker.Submission result = this.clientLaunchWorker.submit(() -> this.runClientLaunch(location));
        if (result == ScarletClientLaunchWorker.Submission.ACCEPTED)
            this.recentlyLaunchedLocations.put(location, now);
        else if (reportBusy)
            LOG.info("VRChat auto-launch skipped: launch worker is {} (no launch queued)", result);
        return result != ScarletClientLaunchWorker.Submission.BUSY;
    }

    private void runStartupClientLaunch(String location, java.util.function.BooleanSupplier allowed) throws Exception
    {
        if (!allowed.getAsBoolean()) return;
        // Known pre-launch lookup failures may retry; uncertain OS launches must never be replayed.
        String shortName = this.scarlet.vrc.getStartupInstanceSecureName(this.scarlet.vrc.groupId, location, allowed);
        if (shortName == null) throw new ScarletStartupFailure(ScarletStartupFailure.Kind.SECURE_NAME_MISSING);
        if (!allowed.getAsBoolean()) return;
        this.recentlyLaunchedLocations.put(location, System.currentTimeMillis());
        ScarletStartupRecovery.LogState before = this.scarlet.logs.startupLogState();
        try
        {
            VrcLaunch.launchIfNotRunning(this.scarlet.vrc.currentUserId, location, shortName, VrcLaunch.LaunchMode.DESKTOP, allowed);
            this.watchAutomaticStartup(location, before, allowed);
        }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw ex; }
        catch (Exception ex)
        {
            LOG.warn("Startup VRChat launch status: {} No automatic launch retry was made.", ScarletStartupFailure.describe(ex));
        }
    }

    private void runClientLaunch(String location)
    {
        if (this.startupRecovery != null) this.startupRecovery.cancel();
        if (this.clientLaunchClosed || Thread.currentThread().isInterrupted() || !this.vrchatClient_launchOnInstanceCreate.get())
            return;
        java.util.function.BooleanSupplier allowed = this.automaticLaunchContext(false);
        if (!allowed.getAsBoolean()) return;
        try
        {
            // Resolve the instance's short/secure name so non-public (group,
            // friends, private) instances deep-link correctly \u2014 without it
            // VRChat opens to the error world instead of the new instance.
            String shortName = null;
            try
            {
                Location locModel = Location.of(location);
                if (locModel != null && locModel.world != null && locModel.instance != null)
                {
                    // The dedicated /shortName endpoint generates the secure join token the
                    // client uses on a manual join; restricted (group+/age-gated) instances
                    // are rejected into the error world with only the plain shortName.
                    shortName = this.scarlet.vrc.getInstanceSecureName(locModel.world, locModel.instance);
                    if (shortName == null)
                    {
                        io.github.vrchatapi.model.Instance inst = this.scarlet.vrc.getInstance(locModel.world, locModel.instance);
                        if (inst != null)
                        {
                            if (inst.getSecureName() != null && !inst.getSecureName().trim().isEmpty())
                                shortName = inst.getSecureName();
                            else if (inst.getShortName() != null && !inst.getShortName().trim().isEmpty())
                                shortName = inst.getShortName();
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                LOG.warn("Could not resolve secure join information for VRChat auto-launch: {}", ScarletStartupFailure.describe(ex));
            }
            if (this.clientLaunchClosed || Thread.currentThread().isInterrupted() || !this.vrchatClient_launchOnInstanceCreate.get())
                return;
            ScarletStartupRecovery.LogState before = this.scarlet.logs.startupLogState();
            VrcLaunch.launchAutomatic(this.scarlet.vrc.currentUserId, location, shortName, allowed);
            this.watchAutomaticStartup(location, before, allowed);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            LOG.info("VRChat auto-launch cancelled during shutdown");
        }
        catch (Exception ex)
        {
            LOG.warn("VRChat auto-launch status: {}", ScarletStartupFailure.describe(ex));
        }
    }

    private java.util.function.BooleanSupplier automaticLaunchContext(boolean recovery)
    {
        final String group = this.scarlet.vrc.groupId, user = this.scarlet.vrc.currentUserId;
        final long generation = net.sybyline.scarlet.ext.ExtendedUserAgent.currentAuthenticationGeneration();
        return () -> !this.clientLaunchClosed && this.scarlet.running && !this.scarlet.staffMode
            && !this.scarlet.trainingMode.get() && this.vrchatClient_launchOnInstanceCreate.get()
            && (!recovery || this.vrchatClient_recoverStalledStartup.get())
            && group != null && user != null && Objects.equals(group, this.scarlet.vrc.groupId)
            && Objects.equals(user, this.scarlet.vrc.currentUserId)
            && net.sybyline.scarlet.ext.ExtendedUserAgent.isPrimaryAuthenticationStable(generation)
            && !Thread.currentThread().isInterrupted();
    }

    private void watchAutomaticStartup(String location, ScarletStartupRecovery.LogState before,
        java.util.function.BooleanSupplier originalContext)
    {
        if (this.startupRecovery == null || !this.vrchatClient_recoverStalledStartup.get()) return;
        final java.util.function.BooleanSupplier recoveryContext = this.automaticLaunchContext(true);
        final java.util.function.BooleanSupplier context = () -> originalContext.getAsBoolean() && recoveryContext.getAsBoolean();
        final String group = this.scarlet.vrc.groupId, user = this.scarlet.vrc.currentUserId;
        ManagedVrcClient.Client client = VrcLaunch.managedClient();
        if (client == null || !Objects.equals(client.requestedLocation(), location)) return;
        this.startupRecovery.start(new ScarletStartupRecovery.Environment() {
            public boolean allowed() { return context.getAsBoolean(); }
            public String user() { return user; }
            public long now() { return System.nanoTime(); }
            public ScarletStartupRecovery.LogState logs() { return ScarletDiscordJDA.this.scarlet.logs.startupLogState(); }
            private void verifyTarget(java.util.function.BooleanSupplier allowed) throws Exception {
                ScarletStartupInstanceJoin.parseLocation(location, group);
                if (!allowed.getAsBoolean() || !ScarletDiscordJDA.this.scarlet.vrc.getStartupGroupInstances(group, allowed).contains(location))
                    throw new IOException("Startup recovery target is no longer active or allowed");
            }
            public ScarletStartupRecovery.Launch recover(ManagedVrcClient.Client expected,
                java.util.function.BooleanSupplier allowed, java.util.function.BooleanSupplier closeAllowed) throws Exception {
                verifyTarget(allowed);
                java.util.concurrent.atomic.AtomicReference<ScarletStartupRecovery.LogState> baseline = new java.util.concurrent.atomic.AtomicReference<>();
                VrcLaunch.recoverManagedClient(expected, user, allowed, closeAllowed,
                    () -> ScarletDiscordJDA.this.vrchatClient_forceStalledStartup.get(), () -> {
                    verifyTarget(allowed);
                    String secureName = ScarletDiscordJDA.this.scarlet.vrc.getStartupInstanceSecureName(group, location, allowed);
                    baseline.set(logs());
                    return secureName;
                });
                return new ScarletStartupRecovery.Launch(VrcLaunch.managedClient(), baseline.get());
            }
            public void log(String reason) { LOG.info("VRChat startup recovery: {}", reason); }
        }, new ScarletStartupRecovery.Launch(client, before));
    }

    @Override
    public void emitInstanceClose(Scarlet scarlet, AuditEntryMetadata entryMeta, String location)
    {
        String worldImageUrl = this.getLocationImage(location);
        
        String prevAuditEntryId = scarlet.data.liveInstancesMetadata_getLocationAudit(location, false);
        AuditEntryMetadata previous = prevAuditEntryId == null ? null : scarlet.data.auditEntryMetadata(prevAuditEntryId);
        AuditEntryMetadata prevEntryMeta = ScarletAuditPrivacy.visible(previous, scarlet.secretStaffList::isSecretStaffId,
            ScarletAuditPrivacy.confidential(entryMeta, scarlet.secretStaffList::isSecretStaffId)) ? previous : null;
        ScarletData.InstanceEmbedMessage instanceEmbedMessage = scarlet.data.liveInstancesMetadata_getLocationInstanceEmbedMessage(location, false);
        if (instanceEmbedMessage != null)
        {
            instanceEmbedMessage.closedAt = entryMeta.entry.getCreatedAt();
        }
        this.condEmit(entryMeta, (channelSf, guild, channel) ->
        {
            boolean hasPrev = prevEntryMeta != null && prevEntryMeta.hasMessage();
            MessageCreateAction mca = channel
                .sendMessageEmbeds(this
                    .embed(entryMeta, true)
                    .setImage(worldImageUrl)
                    .setTitle("Instance Close", hasPrev ? prevEntryMeta.getMessageUrl() : null)
                    .build());
            if (hasPrev && Objects.equals(channelSf, prevEntryMeta.channelSnowflake))
                mca.mentionRepliedUser(false).setMessageReference(prevEntryMeta.messageSnowflake);
            return mca.complete();
        });
        this.emitAuxWh(entryMeta,
            () -> this.embed(entryMeta, true)
                      .setImage(worldImageUrl)
                      .setTitle("Instance Close")
                      .build(),
            IncomingWebhookClient::sendMessageEmbeds);
    }

    @Override
    public void emitMemberJoin(Scarlet scarlet, AuditEntryMetadata entryMeta)
    {
        this.condEmitEmbed(entryMeta, true, "Member Join", "https://vrchat.com/home/user/"+entryMeta.entry.getTargetId(), null, null);
    }

    @Override
    public void emitMemberLeave(Scarlet scarlet, AuditEntryMetadata entryMeta)
    {
        this.condEmitEmbed(entryMeta, true, "Member Leave", "https://vrchat.com/home/user/"+entryMeta.entry.getTargetId(), null, null);
    }

    @Override
    public void emitAnnouncement(
        Scarlet scarlet,
        AuditEntryMetadata entryMeta,
        GroupAuditType.AnnouncementComponent announcement
    ) {
        this.condEmitEmbed(
            entryMeta,
            false,
            MarkdownSanitizer.escape(announcement.title),
            "https://vrchat.com/home/user/" + entryMeta.entry.getActorId(),
            null,
            embed -> {
                embed
                    .setDescription(AuditEmbedBuilder.clip(announcement.message, 3000))
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
            }
        );
    }

    @Override
    public void emitPostCreate(Scarlet scarlet, AuditEntryMetadata entryMeta, GroupAuditType.PostCreateComponent post)
    {
        this.condEmitEmbed(entryMeta, false, MarkdownSanitizer.escape(post.title), "https://vrchat.com/home/group/"+entryMeta.entry.getGroupId()+"/posts", null, embed ->
        {
            embed.setDescription(null) // override description
                .setDescription(AuditEmbedBuilder.clip(post.text, 3000))
                .setImage(MiscUtils.latestContentUrlOrNull(post.imageId));
            this.emitAuxWh(entryMeta, embed::build, IncomingWebhookClient::sendMessageEmbeds);
        });
    }

    @Override
    public void emitMemberRoleAssign(Scarlet scarlet, AuditEntryMetadata entryMeta, User target, GroupAuditType.RoleRefComponent role)
    {
        this.condEmitEmbed(entryMeta, false, "Assigned Role to "+MarkdownSanitizer.escape(target.getDisplayName()), "https://vrchat.com/home/user/"+target.getId(), null, embed ->
            embed.setDescription(String.format(
                "[%s](%s) assigned the role \"[%s](https://vrchat.com/home/group/%s/settings/roles/%s)\" to [%s](https://vrchat.com/home/user/%s)",
                entryMeta.entry.getActorDisplayName(), entryMeta.entry.getActorId(),
                role.roleName, entryMeta.entry.getGroupId(), role.roleId,
                target.getDisplayName(), "https://vrchat.com/home/user/"+target.getId()
            )));
    }

    @Override
    public void emitMemberRoleUnassign(Scarlet scarlet, AuditEntryMetadata entryMeta, User target, GroupAuditType.RoleRefComponent role)
    {
        this.condEmitEmbed(entryMeta, false, "Unassigned Role from "+MarkdownSanitizer.escape(target.getDisplayName()), "https://vrchat.com/home/user/"+target.getId(), null, embed ->
            embed.setDescription(String.format(
                "[%s](%s) unassigned the role \"[%s](https://vrchat.com/home/group/%s/settings/roles/%s)\" from [%s](https://vrchat.com/home/user/%s)",
                entryMeta.entry.getActorDisplayName(), entryMeta.entry.getActorId(),
                role.roleName, entryMeta.entry.getGroupId(), role.roleId,
                target.getDisplayName(), "https://vrchat.com/home/user/"+target.getId()
            )));
    }

    @Override
    public void emitMemberUserUpdate(Scarlet scarlet, AuditEntryMetadata entryMeta, Map<String, GroupAuditType.UpdateSubComponent> updates)
    {
        this.condEmitEmbed(entryMeta, true, "Updated Member User", null, updates, null);
    }

    @Override
    public void emitRoleCreate(Scarlet scarlet, AuditEntryMetadata entryMeta, GroupAuditType.RoleCreateComponent role)
    {
        this.condEmitEmbed(entryMeta, false, "Created Role "+MarkdownSanitizer.escape(role.name), "https://vrchat.com/home/group/"+entryMeta.entry.getGroupId()+"/settings/roles/"+entryMeta.entry.getTargetId(), null, null);
    }

    @Override
    public void emitRoleDelete(Scarlet scarlet, AuditEntryMetadata entryMeta, GroupAuditType.RoleDeleteComponent role)
    {
        this.condEmitEmbed(entryMeta, false, "Deleted Role "+MarkdownSanitizer.escape(role.name), "https://vrchat.com/home/group/"+entryMeta.entry.getGroupId()+"/settings/roles/"+entryMeta.entry.getTargetId(), null, null);
    }

    @Override
    public void emitRoleUpdate(Scarlet scarlet, AuditEntryMetadata entryMeta, Map<String, GroupAuditType.UpdateSubComponent> updates)
    {
        String roleId = entryMeta.entry.getTargetId();
        String roleName = roleId;
        try
        {
            roleName = new GroupsApi(this.scarlet.vrc.client).getGroupRoles(entryMeta.entry.getGroupId()).stream().filter($ -> roleId.equals($.getId())).findAny().map(GroupRole::getName).orElse(roleId);
        }
        catch (Exception ex)
        {
            roleName = roleId;
        }
        this.condEmitEmbed(entryMeta, false, "Updated Role "+MarkdownSanitizer.escape(roleName), "https://vrchat.com/home/group/"+entryMeta.entry.getGroupId()+"/settings/roles/"+entryMeta.entry.getTargetId(), updates, null);
    }

    @Override
    public void emitInviteCreate(Scarlet scarlet, AuditEntryMetadata entryMeta)
    {
        this.condEmitEmbed(entryMeta, false, "Invited User", "https://vrchat.com/home/user/"+entryMeta.entry.getTargetId(), null, null);
    }

    @Override
    public void emitRequestBlock(Scarlet scarlet, AuditEntryMetadata entryMeta)
    {
        this.condEmitEmbed(entryMeta, false, "Blocked Request", "https://vrchat.com/home/user/"+entryMeta.entry.getTargetId(), null, null);
    }

    @Override
    public void emitRequestCreate(Scarlet scarlet, AuditEntryMetadata entryMeta)
    {
        // A "request to join" only happens when the group is set to require approval, so this
        // post is where a moderator handles it. Attach Accept / Reject / Block buttons that act
        // on the requesting user directly from the moderation channel.
        String targetId = entryMeta.entry.getTargetId();
        EmbedBuilder embed = this.embed(entryMeta, false);
        embed.setTitle("Created Request", "https://vrchat.com/home/user/"+targetId);
        this.condEmit(entryMeta, (channelSf, guild, channel) ->
            channel.sendMessageEmbeds(embed.build())
                .addComponents(ActionRow.of(
                    Button.success("group-request-accept:"+targetId, "Accept"),
                    Button.secondary("group-request-reject:"+targetId, "Reject"),
                    Button.danger("group-request-block:"+targetId, "Block")
                ))
                .complete());
    }

    @Override
    public void emitRequestReject(Scarlet scarlet, AuditEntryMetadata entryMeta)
    {
        this.condEmitEmbed(entryMeta, false, "Rejected Request", "https://vrchat.com/home/user/"+entryMeta.entry.getTargetId(), null, null);
    }

    @Override
    public void emitUpdate(Scarlet scarlet, AuditEntryMetadata entryMeta, Map<String, GroupAuditType.UpdateSubComponent> updates)
    {
        this.condEmitEmbed(entryMeta, false, "Updated Group", "https://vrchat.com/home/group/"+entryMeta.entry.getGroupId(), updates, null);
    }

    @Override
    public void emitDefault(Scarlet scarlet, AuditEntryMetadata entryMeta)
    {
        this.condEmitEmbed(entryMeta, true, GroupAuditType.title(entryMeta.entry.getEventType()), null, null, embed ->
        {
            if (entryMeta.hasNonEmptyData())
            {
                JsonObject data = entryMeta.getData(JsonObject.class);
                for (String key : data.keySet())
                {
                    String valueString = null;
                    JsonElement value = data.get(key);
                    if (value != null && value.isJsonObject())
                    {
                        JsonObject valueObject = value.getAsJsonObject();
                        if (valueObject.size() == 2 && valueObject.has("old") && valueObject.has("new"))
                        {
                             String oldValue = String.valueOf(valueObject.get("old")),
                                    newValue = String.valueOf(valueObject.get("new"));
                             if (oldValue.length() > 480) oldValue = oldValue.substring(0, 480).concat("...");
                             if (newValue.length() > 480) newValue = newValue.substring(0, 480).concat("...");
                             valueString = new StringBuilder()
                                 .append("old: `").append(oldValue)
                                 .append("`\nnew: `").append(newValue).append("`")
                                 .toString();
                        }
                    }
                    if (valueString == null)
                    {
                        valueString = "`"+value+"`";
                    }
                    embed.addField("`"+key+"`", valueString, false);
                }
            }
        });
    }

    @Override
    public void emitExtendedInstanceInactive(Scarlet scarlet, String location, String auditEntryId, ScarletData.InstanceEmbedMessage instanceEmbedMessage)
    {
        this.condEmitEx(GroupAuditTypeEx.INSTANCE_INACTIVE, false, false, location, (channelSf, guild, channel) ->
        {
            return channel.sendMessageEmbeds(new EmbedBuilder()
                    .setTitle("Instance Inactive")
                    .addField("Location", "`"+location+"`", false)
                    .setColor(GroupAuditTypeEx.INSTANCE_INACTIVE.color)
                    .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                    .build())
                .complete();
        });
    }

    EmbedBuilder instanceJoinLeaveEmbed(String userId)
    {
        // Optional decoration must never turn a join/leave log into a blocking API lookup.
        return ScarletJoinLeaveThumbnail.apply(new EmbedBuilder(),
            this.instanceJoinLeaveThumbnails != null && this.instanceJoinLeaveThumbnails.get()
                && ScarletJoinLeaveThumbnail.isUserId(userId),
            () -> this.scarlet.vrc.cachedPublicProfiles.get(userId));
    }

    @Override
    public void emitExtendedStaffJoin(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName)
    {
        this.condEmitEx(GroupAuditTypeEx.STAFF_JOIN, false, false, location, (channelSf, guild, channel) ->
        {
            channel.sendMessageEmbeds(this.instanceJoinLeaveEmbed(userId)
                    .setDescription(String.format("### [%s](https://vrchat.com/home/user/%s) joined [a group instance](https://vrchat.com/home/launch?worldId=%s)",
                            MarkdownSanitizer.escape(displayName), userId, location.replace(":", "&instanceId=")))
//                    .setTitle(MarkdownSanitizer.escape(displayName)+" joined a group instance", "https://vrchat.com/home/user/"+userId)
//                    .addField("Location", "`"+location+"`", false)
                    .setColor(GroupAuditTypeEx.STAFF_JOIN.color)
                    .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                    .build())
                .queue(ignored -> {}, failure -> LOG.warn("A staff join log could not be delivered to Discord; local player tracking continues"));
            return null;
        });
    }

    @Override
    public void emitExtendedStaffLeave(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName)
    {
        this.condEmitEx(GroupAuditTypeEx.STAFF_LEAVE, false, false, location, (channelSf, guild, channel) ->
        {
            channel.sendMessageEmbeds(this.instanceJoinLeaveEmbed(userId)
                    .setDescription(String.format("### [%s](https://vrchat.com/home/user/%s) left [a group instance](https://vrchat.com/home/launch?worldId=%s)",
                            MarkdownSanitizer.escape(displayName), userId, location.replace(":", "&instanceId=")))
//                    .setTitle(MarkdownSanitizer.escape(displayName)+" left a group instance", "https://vrchat.com/home/user/"+userId)
//                    .addField("Location", "`"+location+"`", false)
                    .setColor(GroupAuditTypeEx.STAFF_LEAVE.color)
                    .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                    .build())
                .queue(ignored -> {}, failure -> LOG.warn("A staff leave log could not be delivered to Discord; local player tracking continues"));
            return null;
        });
    }

    @Override
    public void emitExtendedUserJoin(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName)
    {
        this.condEmitEx(ScarletSimulation.isTrainingId(userId), GroupAuditTypeEx.USER_JOIN, false, false, location, (channelSf, guild, channel) ->
        {
            channel.sendMessageEmbeds(this.instanceJoinLeaveEmbed(userId)
                    .setDescription(String.format("### [%s](https://vrchat.com/home/user/%s) joined [a group instance](https://vrchat.com/home/launch?worldId=%s)",
                            MarkdownSanitizer.escape(displayName), userId, location.replace(":", "&instanceId=")))
                    .addField("User ID", "`"+userId+"`", false)
//                    .setTitle(MarkdownSanitizer.escape(displayName)+" joined a group instance", "https://vrchat.com/home/user/"+userId)
//                    .addField("Location", "`"+location+"`", false)
                    .setColor(GroupAuditTypeEx.USER_JOIN.color)
                    .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                    .build())
                .addComponents(ActionRow.of(
                    Button.danger("vrchat-user-ban:"+userId, "Ban user"),
                    Button.success("vrchat-user-unban:"+userId, "Unban user")
                ))
                .queue(ignored -> {}, failure -> LOG.warn("A user join log could not be delivered to Discord; local player tracking continues"));
            return null;
        });
    }

    @Override
    public void emitExtendedUserLeave(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName)
    {
        this.condEmitEx(ScarletSimulation.isTrainingId(userId), GroupAuditTypeEx.USER_LEAVE, false, false, location, (channelSf, guild, channel) ->
        {
            channel.sendMessageEmbeds(this.instanceJoinLeaveEmbed(userId)
                    .setDescription(String.format("### [%s](https://vrchat.com/home/user/%s) left [a group instance](https://vrchat.com/home/launch?worldId=%s)",
                            MarkdownSanitizer.escape(displayName), userId, location.replace(":", "&instanceId=")))
                    .addField("User ID", "`"+userId+"`", false)
//                    .setTitle(MarkdownSanitizer.escape(displayName)+" left a group instance", "https://vrchat.com/home/user/"+userId)
//                    .addField("Location", "`"+location+"`", false)
                    .setColor(GroupAuditTypeEx.USER_LEAVE.color)
                    .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                    .build())
                .addComponents(ActionRow.of(
                    Button.danger("vrchat-user-ban:"+userId, "Ban user"),
                    Button.success("vrchat-user-unban:"+userId, "Unban user")
                ))
                .queue(ignored -> {}, failure -> LOG.warn("A user leave log could not be delivered to Discord; local player tracking continues"));
            return null;
        });
    }

    @Override
    public void emitExtendedUserAvatar(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String avatarDisplayName, String[] potentialIds0)
    {
        this.condEmitEx(ScarletSimulation.isTrainingId(userId), GroupAuditTypeEx.USER_AVATAR, false, false, location, (channelSf, guild, channel) ->
        {
            String[] potentialIds = potentialIds0;
            EmbedBuilder builder = new EmbedBuilder()
                    .setTitle(MarkdownSanitizer.escape(displayName)+" switched avatars", "https://vrchat.com/home/user/"+userId)
                    .addField("User ID", "`"+userId+"`", false)
                    .addField("Location", "`"+location+"`", false)
                    .addField("Avatar Name", MarkdownSanitizer.escape(avatarDisplayName), false)
                    .setDescription(Arrays.stream(potentialIds).limit(32).collect(Collectors.joining("`\n`", "Potential ids:\n`", "`")))
                    .setColor(GroupAuditTypeEx.USER_AVATAR.color)
                    .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
            ;
            String aviThumbnail = this.scarlet.vrc.getUserImageUrlOrNull(userId);
            if (aviThumbnail != null && !aviThumbnail.isEmpty())
                builder.setThumbnail(aviThumbnail);
            VersionedFile versionedFile = this.avatarName2Bundle.get(avatarDisplayName);
            if (versionedFile != null)
            {
                builder.addField("Bundle ID", "`"+versionedFile.id+"`", false);
                ModelFile modelFile = this.scarlet.vrc.getModelFile(versionedFile.id, ScarletJsonCache.ALWAYS_PREFER_CACHED);
                if (modelFile != null)
                {
                    potentialIds = this.filterAvatarIdsByBundleOwner(modelFile, potentialIds);
                    builder.setDescription(Arrays.stream(potentialIds).limit(32).collect(Collectors.joining("`\n`", "Potential ids:\n`", "`")));
                }
            }
            Message message = channel
                .sendMessageEmbeds(builder.build())
                .addComponents(ActionRow.of(
                    Button.secondary("view-potential-avatar-matches", "View avatars")
                ))
                .addComponents(ActionRow.of(
                    Button.danger("vrchat-user-ban:"+userId, "Ban user"),
                    Button.success("vrchat-user-unban:"+userId, "Unban user")
                ))
                .complete();
            if (versionedFile != null)
                return message;
            List<AviSwitch> list = this.avatarName2Switches.get(avatarDisplayName);
            if (list == null)
                this.avatarName2Switches.put(avatarDisplayName, list = new ArrayList<>());
            list.add(new AviSwitch(message, builder, potentialIds0));
            return message;
        });
    }

    @Override
    public void emitExtendedUserVideo(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String videoUrl, String videoTitle)
    {
        this.condEmitEx(GroupAuditTypeEx.USER_VIDEO, false, false, location, (channelSf, guild, channel) ->
        {
            return channel.sendMessageEmbeds(new EmbedBuilder()
                    .setTitle(MarkdownSanitizer.escape(displayName)+" loaded video", "https://vrchat.com/home/user/"+userId)
                    .addField("User ID", "`"+userId+"`", false)
                    .addField("Location", "`"+location+"`", false)
                    .addField("Video URL", "`"+videoUrl+"`", false)
                    .addField("Video Title", MarkdownSanitizer.escape(videoTitle), false)
                    .setColor(GroupAuditTypeEx.USER_VIDEO.color)
                    .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                    .build())
                .complete();
        });
    }

    @Override
    public void emitExtendedVtkInitiated(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String optActorId, String optActorDisplayName)
    {
        this.condEmitEx(ScarletSimulation.isTrainingId(userId), GroupAuditTypeEx.VTK_START, true, false, location, (channelSf, guild, channel) ->
        {
            EmbedBuilder builder = new EmbedBuilder()
                    .setDescription(String.format("### [%s](https://vrchat.com/home/user/%s) was targeted by a vote-to-kick in [a group instance](https://vrchat.com/home/launch?worldId=%s)",
                            MarkdownSanitizer.escape(displayName), userId, location.replace(":", "&instanceId=")))
                    .addField("Target User ID", "`"+userId+"`", false)
//                    .setTitle(MarkdownSanitizer.escape(displayName)+" was targeted by a vote-to-kick", "https://vrchat.com/home/user/"+userId)
//                    .addField("Location", "`"+location+"`", false)
                    .setColor(GroupAuditTypeEx.VTK_START.color)
                    .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
            if (optActorId != null)
            {
                builder.setAuthor(MarkdownSanitizer.escape(optActorDisplayName), "https://vrchat.com/home/user/"+optActorId);
                builder.addField("Initiating User ID", "`"+optActorId+"`", false);
            }
            return channel.sendMessageEmbeds(builder
                    .build())
                .addComponents(ActionRow.of(
                    Button.danger("vrchat-user-ban:"+userId, "Ban user"),
                    Button.success("vrchat-user-unban:"+userId, "Unban user")
                ))
                .complete();
        });
    }

    @Override
    public void emitExtendedUserSpawnPedestal(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String contentType, String contentId)
    {
        this.condEmitEx(GroupAuditTypeEx.SPAWN_PEDESTAL, false, false, location, (channelSf, guild, channel) ->
        {
            VRChatHelpDeskURLs.ModerationReportAccountContentType act = null;
            String contentTypeLowerCase = contentType.toLowerCase();
            switch (contentTypeLowerCase)
            {
            case "image":   act = VRChatHelpDeskURLs.ModerationReportAccountContentType.GALLERY;  break;
            case "emoji":   act = VRChatHelpDeskURLs.ModerationReportAccountContentType.EMOJI;    break;
            case "sticker": act = VRChatHelpDeskURLs.ModerationReportAccountContentType.STICKERS; break;
            case "palette": act = VRChatHelpDeskURLs.ModerationReportAccountContentType.OTHER;    break;
            }
            return channel.sendMessageEmbeds(new EmbedBuilder()
                .setTitle(MarkdownSanitizer.escape(displayName)+("aeiou".indexOf(Character.toLowerCase(contentType.charAt(0)))<0?" spawned a ":" spawned an ")+contentTypeLowerCase+" pedestal", "https://vrchat.com/home/user/"+userId)
                .addField("User ID", "`"+userId+"`", false)
                .addField("Location", "`"+location+"`", false)
                .addField(contentType+" ID", "`"+contentId+"`", false)
                .addField("Report "+contentTypeLowerCase, MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_account(this.requestingEmail.get(), act, userId, contentType, contentId)), false)
                .setImage(!contentId.startsWith("file_") ? null : ("https://api.vrchat.cloud/api/1/file/"+contentId+"/1/file"))
                .setColor(GroupAuditTypeEx.SPAWN_PEDESTAL.color)
                .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build())
            .addComponents(ActionRow.of(
                Button.danger("vrchat-user-ban:"+userId, "Ban user"),
                Button.success("vrchat-user-unban:"+userId, "Unban user")
            ))
            .complete();
        });
    }

    @Override
    public void emitExtendedUserSpawnSticker(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String stickerId)
    {
        this.condEmitEx(GroupAuditTypeEx.SPAWN_STICKER, false, false, location, (channelSf, guild, channel) ->
        {
            String stickerFileId = null;
            try
            {
                stickerFileId = this.scarlet.vrc.getStickerFileId(userId, stickerId);
            }
            catch (Exception ex)
            {
            }
            String desc = stickerId;
            if (stickerFileId != null)
                desc = desc + " (" + stickerFileId + ")";
            return channel.sendMessageEmbeds(new EmbedBuilder()
                .setTitle(MarkdownSanitizer.escape(displayName)+" spawned a sticker", "https://vrchat.com/home/user/"+userId)
                .addField("User ID", "`"+userId+"`", false)
                .addField("Location", "`"+location+"`", false)
                .addField("Sticker ID", "`"+stickerId+"`", false)
                .addField("File ID", "`"+stickerFileId+"`", false)
                .addField("Report sticker", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_account_stickers(this.requestingEmail.get(), userId, "Sticker", desc)), false)
                .setImage(stickerFileId == null ? null : ("https://api.vrchat.cloud/api/1/file/"+stickerFileId+"/1/file"))
                .setColor(GroupAuditTypeEx.SPAWN_STICKER.color)
                .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build())
            .addComponents(ActionRow.of(
                Button.danger("vrchat-user-ban:"+userId, "Ban user"),
                      Button.success("vrchat-user-unban:"+userId, "Unban user")
            ))
            .complete();
        });
    }

    @Override
    public void emitExtendedUserSpawnPrint(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String printId, Print print)
    {
        this.condEmitEx(GroupAuditTypeEx.SPAWN_PRINT, false, false, location, (channelSf, guild, channel) ->
        {
            
            String fileId = print.getFiles().getFileId(),
                   image = print.getFiles().getImage();
            return channel.sendMessageEmbeds(new EmbedBuilder()
                .setTitle(MarkdownSanitizer.escape(displayName)+" spawned a print", "https://vrchat.com/home/user/"+userId)
                .addField("User ID", "`"+userId+"`", false)
                .addField("Location", "`"+location+"`", false)
                .addField("Print ID", "`"+printId+"`", false)
                .addField("File ID", "`"+fileId+"`", false)
                .addField("Report print", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_account_prints(this.requestingEmail.get(), userId, "Print", printId)), false)
                .setImage(image != null ? image : ("https://api.vrchat.cloud/api/1/file/"+fileId+"/1/file"))
                .setColor(GroupAuditTypeEx.SPAWN_PRINT.color)
                .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build())
            .addComponents(ActionRow.of(
                Button.danger("vrchat-user-ban:"+userId, "Ban user"),
                Button.success("vrchat-user-unban:"+userId, "Unban user")
            ))
            .complete();
        });
    }

    @Override
    public void emitExtendedUserSpawnEmoji(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String emojiId, InventoryItem emoji)
    {
        this.condEmitEx(GroupAuditTypeEx.SPAWN_EMOJI, false, false, location, (channelSf, guild, channel) ->
        {
            String fileId = emoji.getMetadata().getFileId(),
                   image = emoji.getImageUrl();
            return channel.sendMessageEmbeds(new EmbedBuilder()
                .setTitle(MarkdownSanitizer.escape(displayName)+" spawned an emoji", "https://vrchat.com/home/user/"+userId)
                .addField("User ID", "`"+userId+"`", false)
                .addField("Location", "`"+location+"`", false)
                .addField("Emoji ID", "`"+emojiId+"`", false)
                .addField("File ID", "`"+fileId+"`", false)
                .addField("Report emoji", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_account_emoji(this.requestingEmail.get(), userId, "Emoji", emojiId)), false)
                .setImage(image != null ? image : ("https://api.vrchat.cloud/api/1/file/"+fileId+"/1/file"))
                .setColor(GroupAuditTypeEx.SPAWN_EMOJI.color)
                .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build())
            .addComponents(ActionRow.of(
                Button.danger("vrchat-user-ban:"+userId, "Ban user"),
                      Button.success("vrchat-user-unban:"+userId, "Unban user")
            ))
            .complete();
        });
    }

    @Override
    public void emitExtendedUserSpawnProp(Scarlet scarlet, LocalDateTime timestamp, String location, String userId, String displayName, String propId, Prop prop)
    {
        this.condEmitEx(GroupAuditTypeEx.SPAWN_PROP, false, false, location, (channelSf, guild, channel) ->
        {
            return channel.sendMessageEmbeds(propSpawnEmbed(location, userId, displayName, propId, prop, this.requestingEmail.get()).build())
            .addComponents(ActionRow.of(
                Button.danger("vrchat-user-ban:"+userId, "Ban user"),
                Button.success("vrchat-user-unban:"+userId, "Unban user")
            ))
            .complete();
        });
    }

    static EmbedBuilder propSpawnEmbed(String location, String userId, String displayName, String propId, Prop prop, String reportEmail)
    {
        EmbedBuilder embed = new AuditEmbedBuilder()
            .setTitle(AuditEmbedBuilder.clip(MarkdownSanitizer.escape(displayName)+" spawned a prop", 256), "https://vrchat.com/home/user/"+userId)
            .addField("User ID", "`"+userId+"`", false)
            .addField("Location", "`"+location+"`", false)
            .addField("Prop ID", "`"+propId+"`", false)
            .setColor(GroupAuditTypeEx.SPAWN_PROP.color)
            .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
            .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
        // A logged spawn remains valid when optional content metadata is private or unavailable.
        String authorId = prop == null ? null : prop.getAuthorId();
        if (authorId != null && !authorId.isBlank())
        {
            String authorName = prop.getAuthorName();
            if (authorName == null || authorName.isBlank()) authorName = authorId;
            embed.addField("Prop Author", MarkdownUtil.maskedLink(MarkdownSanitizer.escape(authorName), "https://vrchat.com/home/user/"+authorId), false);
            embed.addField("Report prop", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_account_other(reportEmail, authorId, "Prop", propId)), false);
        }
        else
            embed.addField("Prop Author", "Unavailable; content author could not be verified.", false);
        if (prop != null)
            embed.setImage(MiscUtils.nonBlankOrNull(prop.getThumbnailImageUrl(), prop.getImageUrl()));
        return embed;
    }

    String[] filterAvatarIdsByBundleOwner(ModelFile modelFile, String[] potentialIds)
    {
        return Arrays.stream(potentialIds).filter(id ->
        {
            Avatar avatar = this.scarlet.vrc.getAvatar(id, ScarletJsonCache.ALWAYS_PREFER_CACHED);
            return avatar == null || Objects.equals(modelFile.getOwnerId(), avatar.getAuthorId());
        }).toArray(String[]::new);
    }
    private final LRUMap<String, VersionedFile> avatarName2Bundle = LRUMap.of();
    private final LRUMap<String, List<AviSwitch>> avatarName2Switches = LRUMap.of();
    class AviSwitch
    {
        AviSwitch(Message message, EmbedBuilder builder, String[] potentialIds)
        {
            this.message = message;
            this.builder = builder;
            this.potentialIds = potentialIds;
        }
        final Message message;
        final EmbedBuilder builder;
        String[] potentialIds;
        void update(ModelFile modelFile, VersionedFile versionedFile)
        {
            this.potentialIds = ScarletDiscordJDA.this.filterAvatarIdsByBundleOwner(modelFile, this.potentialIds);
            try
            {
                this.message.editMessageEmbeds(this.builder.setDescription(Arrays.stream(this.potentialIds).limit(32).collect(Collectors.joining("`\n`", "Potential ids:\n`", "`"))).addField("Bundle ID", "`"+versionedFile.id+"`", false).build()).completeAfter(1_000L, TimeUnit.MILLISECONDS);
            }
            catch (RuntimeException rex)
            {
            }
        }
    }

    @Override
    public void tryEmitExtendedAvatarBundles(Scarlet scarlet, LocalDateTime timestamp, String location, String name, ModelFile modelFile, VersionedFile file)
    {
        this.avatarName2Bundle.put(name, file);
        List<AviSwitch> aviSwitchs = this.avatarName2Switches.remove(name);
        if (aviSwitchs != null)
        {
            this.scarlet.exec.execute(() -> aviSwitchs.forEach($ -> $.update(modelFile, file)));
        }
    }

    @Override
    public void emitExtendedInstanceMonitor(Scarlet scarlet, String location, InstanceEmbedMessage instanceEmbedMessage)
    {
        if (Objects.equals(location, scarlet.eventListener.clientLocation) && instanceEmbedMessage != null)
        {
            if (instanceEmbedMessage.guildSnowflake != null && instanceEmbedMessage.channelSnowflake != null && instanceEmbedMessage.messageSnowflake != null)
            {
                if (this.jda == null)
                    return;
                Guild guild = this.jda.getGuildById(instanceEmbedMessage.guildSnowflake);
                if (guild == null)
                    return;
                TextChannel channel = guild.getTextChannelById(instanceEmbedMessage.channelSnowflake);
                if (channel == null)
                    return;
                
                String worldImageUrl = this.getLocationImage(location),
                       worldName = this.getLocationName(location);
                Location locationModel = Location.of(location);
                
                EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(MarkdownSanitizer.escape(worldName)+" ("+MarkdownSanitizer.escape(locationModel.name)+")", "https://vrchat.com/home/launch?worldId="+location.replaceFirst(":", "&instanceId="))
                    .setImage(worldImageUrl)
                    .setColor(GroupAuditTypeEx.INSTANCE_MONITOR.color)
                    .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                    .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));

                StringBuilder sb = new StringBuilder();
                for (String userId : scarlet.eventListener.clientLocation_userIdsJoinOrder)
                {
                    String displayName = scarlet.eventListener.roster.displayNameOr(userId, userId);
                    OffsetDateTime joinedAt = scarlet.eventListener.getJoinedOrNull(userId);
                    String joinedEpoch = joinedAt == null ? "0" : Long.toUnsignedString(joinedAt.toEpochSecond());
                    String avatarName = scarlet.eventListener.clientLocation_userDisplayName2avatarDisplayName.get(displayName);
                    sb.append(MarkdownUtil.maskedLink(MarkdownSanitizer.escape(displayName), "https://vrchat.com/home/user/"+userId))
                        .append("<t:").append(joinedEpoch).append(":D> (<t:").append(joinedEpoch).append(":R>): ")
                        .append(avatarName).append("\n");
                }
                
                if (sb.length() > 4096)
                {
                    channel.editMessageEmbedsById(instanceEmbedMessage.messageSnowflake, embed.setDescription("See `players.txt` for connected users").build()).setAttachments(AttachedFile.fromData(sb.toString().getBytes(StandardCharsets.UTF_8), "players.txt")).complete();
                    return;
                }
                
                MessageEmbed[] embeds = {embed.setDescription(sb).build()};// Stream.concat(Stream.of(embed.build()), MiscUtils.paginateOnLines(sb, 4000).stream().limit(9L).map($ -> new EmbedBuilder().setDescription($).build())).toArray(MessageEmbed[]::new);
                
                channel.editMessageEmbedsById(instanceEmbedMessage.messageSnowflake, embeds).setAttachments().complete();
            }
        }
    }

    @Override
    public void emitExtendedInstanceEnforcement(Scarlet scarlet, String location, String worldName, String reason)
    {
        this.condEmitEx(GroupAuditTypeEx.INSTANCE_ENFORCE, false, false, location, (channelSf, guild, channel) ->
        {
            return channel.sendMessageEmbeds(new EmbedBuilder()
                .setTitle("Instance Enforcement")
                .addField("Location", "`"+location+"`", false)
                .addField("World", MarkdownSanitizer.sanitize(worldName), false)
                .addField("Reason", reason, false)
                .setColor(GroupAuditTypeEx.INSTANCE_ENFORCE.color)
                .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build())
            .complete();
        });
    }

    private final Object summaryWorkerLock = new Object();
    private ScarletSummaryWorker summaryWorker;
    private volatile boolean summaryWorkerClosed;
    private volatile long summaryPrivacyRevision;
    private int summaryPrivacyMutations;

    synchronized void invalidateLocalSummaryPrivacy()
    {
        this.summaryPrivacyRevision++;
    }

    synchronized void beginLocalSummaryPrivacyChange()
    {
        this.summaryPrivacyMutations++;
        this.summaryPrivacyRevision++;
    }

    synchronized void endLocalSummaryPrivacyChange()
    {
        if (this.summaryPrivacyMutations <= 0)
            throw new IllegalStateException("No local summary privacy change is active");
        this.summaryPrivacyMutations--;
        this.summaryPrivacyRevision++;
    }

    private synchronized long currentSummaryPrivacyRevision()
    {
        if (this.summaryPrivacyMutations != 0)
            throw new IllegalStateException("A local history privacy change is still being saved");
        if (this.scarlet.data.hasPendingAuditMetadataWrites())
            throw new IllegalStateException("Saved audit history has uncommitted changes; retry after persistence recovers");
        return this.summaryPrivacyRevision;
    }

    private ScarletSummaryWorker.Submission submitSummary(Runnable task)
    {
        synchronized (this.summaryWorkerLock)
        {
            if (this.summaryWorkerClosed) return ScarletSummaryWorker.Submission.CLOSED;
            if (this.summaryWorker == null) this.summaryWorker = new ScarletSummaryWorker();
            return this.summaryWorker.submit(task);
        }
    }

    void closeSummaryWorker() throws IOException
    {
        ScarletSummaryWorker worker;
        synchronized (this.summaryWorkerLock)
        {
            this.summaryWorkerClosed = true;
            worker = this.summaryWorker;
        }
        if (worker != null) worker.close();
    }

    @Override
    public void emitModSummary(Scarlet scarlet, OffsetDateTime endOfDay)
    {
        String group = scarlet.vrc.groupId, guildId = this.guildSf;
        ScarletSummaryWorker.Submission result = this.submitSummary(() -> {
            try
            {
                this.requireSummaryContext(group, guildId);
                this.condEmitEx(GroupAuditTypeEx.MOD_SUMMARY, true, false, null,
                    (channelSf, guild, channel) -> this.emitModSummary(scarlet, endOfDay,
                        scarlet.settings.heuristicPeriodDays.get() * 24L, channel::sendMessageEmbeds, group, guildId));
            }
            catch (RuntimeException ex) { LOG.warn("Scheduled local moderation summary did not complete ({})", ex.getClass().getSimpleName()); }
        });
        if (result != ScarletSummaryWorker.Submission.ACCEPTED)
            LOG.info("Scheduled moderation summary not started: {}", result);
    }

    <MCR extends MessageCreateRequest<MCR> & FluentRestAction<Message, MCR>> ScarletSummaryWorker.Submission
        requestModSummary(OffsetDateTime end, long hours, Function<MessageEmbed, MCR> send, Consumer<String> status)
    {
        if (hours <= 0L) throw new IllegalArgumentException("hours-back must be greater than zero");
        String group = this.scarlet.vrc.groupId, guild = this.guildSf;
        return this.submitSummary(() -> {
            try
            {
                this.requireSummaryContext(group, guild);
                status.accept("Generating a summary from saved local history in the background. Scarlet's normal logging remains active.");
                this.emitModSummary(this.scarlet, end, hours, send, group, guild);
            }
            catch (RuntimeException ex)
            {
                LOG.warn("Local moderation summary did not complete ({})", ex.getClass().getSimpleName());
                if (!this.summaryWorkerClosed && !Thread.currentThread().isInterrupted()
                    && Objects.equals(group, this.scarlet.vrc.groupId) && Objects.equals(guild, this.guildSf))
                    status.accept("The local summary could not finish. No further totals will be sent. Check Scarlet's local log; no VRChat history download was started.");
            }
        });
    }

    private void requireSummaryContext(String group, String guild)
    {
        if (Thread.currentThread().isInterrupted() || this.summaryWorkerClosed
            || this.scarlet.vrc == null || group == null || group.trim().isEmpty()
            || !Objects.equals(group, this.scarlet.vrc.groupId) || !Objects.equals(guild, this.guildSf))
            throw new IllegalStateException("Local summary cancelled or its group/server context changed");
    }
    <MCR extends MessageCreateRequest<MCR> & FluentRestAction<Message, MCR>> Message emitModSummary(Scarlet scarlet, OffsetDateTime endOfDay, long hoursBack, Function<MessageEmbed, MCR> mca)
    {
        return this.emitModSummary(scarlet, endOfDay, hoursBack, mca, scarlet.vrc.groupId, this.guildSf);
    }

    private <MCR extends MessageCreateRequest<MCR> & FluentRestAction<Message, MCR>> Message emitModSummary(
        Scarlet scarlet, OffsetDateTime endOfDay, long hoursBack, Function<MessageEmbed, MCR> mca,
        final String summaryGroup, final String summaryGuild)
    {
        if (hoursBack <= 0L) throw new IllegalArgumentException("hours-back must be greater than zero");
        OffsetDateTime startOfDay = endOfDay.minusHours(hoursBack);
        this.requireSummaryContext(summaryGroup, summaryGuild);
        final long privacyRevision = this.currentSummaryPrivacyRevision();
        final Set<String> secretIds = new HashSet<>(Arrays.asList(scarlet.secretStaffList.getSecretStaffIds()));
        Runnable checkPublication = () -> {
            this.requireSummaryContext(summaryGroup, summaryGuild);
            if (privacyRevision != this.currentSummaryPrivacyRevision()
                || !secretIds.equals(new HashSet<>(Arrays.asList(scarlet.secretStaffList.getSecretStaffIds()))))
                throw new IllegalStateException("Summary privacy settings changed; request a fresh summary");
        };
        Function<MessageEmbed, MCR> checkedSend = embed -> {
            checkPublication.run();
            return mca.apply(embed).setCheck(() -> {
                checkPublication.run();
                return true;
            });
        };
        
        String epochStart = Long.toUnsignedString(startOfDay.toEpochSecond()),
               epochEnd = Long.toUnsignedString(endOfDay.toEpochSecond());
        
        ScarletLocalAuditHistory.Result local = ScarletLocalAuditHistory.read(scarlet.data.dir, summaryGroup,
            startOfDay, endOfDay, new HashSet<>(Arrays.asList("group.instance.kick", "group.instance.warn",
                "group.user.ban", "group.invite.create", "group.member.join", "group.member.leave", "group.member.remove")));
        
        if (local.hasFatalProblems)
        {
            LOG.warn("Local moderation summary withheld: unsafe/incomplete scan or conflicting privacy metadata after {} directory entries", local.filesScanned);
            return checkedSend.apply(new EmbedBuilder()
                    .setTitle("Moderation Summary (incomplete)")
                    .setDescription("Spanning <t:"+epochStart+":f> through <t:"+epochEnd+":f>\nThe local scan could not finish safely, reached its resource limit, or found conflicting privacy metadata. No totals are shown. Check Scarlet's local logs for details. No VRChat history download was started.")
                    .setColor(GroupAuditTypeEx.MOD_SUMMARY.color)
                    .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                    .setTimestamp(endOfDay)
                    .build()).complete();
        }
        
        Map<String, List<GroupAuditType>> map = new HashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        Map<String, int[]> staffJoins = new HashMap<>();
        List<GroupAuditLogEntryUnknown> visibleEntries = new ArrayList<>();
        for (ScarletData.AuditEntryMetadata entryMeta : local.records)
        {
            if (!ScarletAuditPrivacy.visible(entryMeta, secretIds::contains, false))
                continue;
            GroupAuditLogEntryUnknown entry = entryMeta.entry;
            visibleEntries.add(entry);
            String actorId = entryMeta.hasAuxActor() ? entryMeta.auxActorId : entry.getActorId();
            String displayName = entryMeta.hasAuxActor() ? entryMeta.auxActorDisplayName : entry.getActorDisplayName();
            map.computeIfAbsent(actorId, $ -> new ArrayList<>()).add(GroupAuditType.of(entry.getEventType()));
            if (displayName != null)
                displayNames.put(actorId, displayName);
        }
        long instanceJoins = 0L;
        for (ScarletData.CustomEvent exJoinEvent : local.customEvents)
        {
            if (secretIds.contains(exJoinEvent.actorId)) continue;
            if (GroupAuditTypeEx.USER_JOIN.id.equals(exJoinEvent.typeEx)) instanceJoins++;
            if (GroupAuditTypeEx.STAFF_JOIN.id.equals(exJoinEvent.typeEx))
            {
                staffJoins.computeIfAbsent(exJoinEvent.actorId, $->new int[1])[0]++;
                if (exJoinEvent.actorDisplayName != null)
                    displayNames.putIfAbsent(exJoinEvent.actorId, exJoinEvent.actorDisplayName);
            }
        }
        
        
        StringBuilder sb = new StringBuilder();
        {
            long gjoins = 0L,
                 gleaves = 0L,
                 gremoves = 0L,
                 ginvites = 0L;
            for (GroupAuditLogEntryUnknown entry : visibleEntries) switch (entry.getEventType())
            {
            case "group.member.join": {
                gjoins++;
            } break;
            case "group.member.leave": {
                gleaves++;
            } break;
            case "group.member.remove": {
                gremoves++;
            } break;
            case "group.invite.create": {
                ginvites++;
            } break;
            }
            sb.append("### "+Long.toUnsignedString(gjoins)+"/"+Long.toUnsignedString(gleaves)+"/"+Long.toUnsignedString(gremoves)+"/"+Long.toUnsignedString(ginvites)+" group joins/leaves/kicks/invites\n");
        }
        {
            sb.append("### "+Long.toUnsignedString(instanceJoins)+" recorded instance joins\n");
        }
        List<GroupAuditType> vtkEntries = map.get("vrc_admin");
        if (vtkEntries != null)
        {
            long vtks = vtkEntries.stream().filter(GroupAuditType.INSTANCE_KICK::equals).count();
            if (vtks > 0)
            {
                sb.append(String.format("### Successful Votes-to-Kick: %d\n", vtks));
            }
        }
        long twarns = 0L,
             tkicks = 0L,
             tbans = 0L,
             tinvites = 0L,
             tjoins = 0L;
        sb.append("### Staff moderation (warns/kicks/bans/invites/joins)\n");
        boolean onlyActivity = this.moderationSummary_onlyActivity.get();
        int skippedForInactivity = 0;
        for (String staffUserId : this.scarlet.staffList.getStaffIds())
        {
            if (secretIds.contains(staffUserId)) continue;
            List<GroupAuditType> staffEntries = map.get(staffUserId);
            int[] staffJoin = staffJoins.get(staffUserId);
            long warns = 0L,
                 kicks = 0L,
                 bans = 0L,
                 invites = 0L,
                 joins = 0L;
            if (staffEntries != null)
            {
                warns = staffEntries.stream().filter(GroupAuditType.INSTANCE_WARN::equals).count();
                kicks = staffEntries.stream().filter(GroupAuditType.INSTANCE_KICK::equals).count();
                bans = staffEntries.stream().filter(GroupAuditType.USER_BAN::equals).count();
                invites = staffEntries.stream().filter(GroupAuditType.INVITE_CREATE::equals).count();
            }
            if (staffJoin != null)
            {
                joins = staffJoin[0];
            }
            twarns += warns;
            tkicks += kicks;
            tbans += bans;
            tinvites += invites;
            tjoins += joins;
            if (onlyActivity && warns == 0L && kicks == 0L && bans == 0L && invites == 0L && joins == 0L)
            {
                skippedForInactivity++;
            }
            else
            {
                String displayName = displayNames.get(staffUserId);
                if (displayName == null)
                {
                    displayName = this.scarlet.staffList.getStaffName(null, staffUserId);
                }
                sb.append(String.format("[%s](https://vrchat.com/home/user/%s): %d / %d / %d / %d / %d\n",
                    net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(displayName == null ? staffUserId : displayName),
                    staffUserId, warns, kicks, bans, invites, joins));
            }
        }
        if (onlyActivity)
        {
            sb.append(String.format("(skipped %d for inactivity)\n", skippedForInactivity));
        }
        sb.append(String.format("Staff totals: %d / %d / %d / %d / %d\n", twarns, tkicks, tbans, tinvites, tjoins));
        if (local.unreadableFileCount > 0)
        {
            sb.append(String.format("\n%d file%s could not be read and %s excluded from these totals. Check Scarlet's local logs for details.\n",
                local.unreadableFileCount, local.unreadableFileCount == 1 ? "" : "s", local.unreadableFileCount == 1 ? "was" : "were"));
        }
        
        Message message = checkedSend.apply(new EmbedBuilder()
            .setTitle(local.unreadableFileCount > 0 ? "Moderation Summary (partial saved local history)" : "Moderation Summary (saved local history)")
            .setDescription("Spanning <t:"+epochStart+":f> through <t:"+epochEnd+":f>\nBased only on locally saved, visible records for this group. Missing, deleted, not-yet-processed or never-collected events are not included; coverage of the entire period cannot be guaranteed. No VRChat history download was used."
                + (local.unreadableFileCount > 0 ? "\nFiles that could not be read are excluded from all moderation counts. See the final notice for their file count." : "")
                + (local.hasUnprocessedEvents ? "\nSome saved events are still awaiting processing and are not counted yet." : "")
                + (visibleEntries.isEmpty() && instanceJoins == 0L && staffJoins.isEmpty() ? "\nNo usable visible records were found for this period; zero totals do not prove that no activity occurred." : ""))
            .setColor(GroupAuditTypeEx.MOD_SUMMARY.color)
            .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
            .setTimestamp(endOfDay)
            .build()).complete();
        List<String> pages = MiscUtils.paginateOnLines(sb, 4096);
        for (String page : pages)
        {
            try { Thread.sleep(1000L); }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Local summary publication cancelled", ex);
            }
            checkedSend.apply(new EmbedBuilder().setDescription(page).build()).complete();
        }
        return message;
    }

    @Override
    public void emitOutstandingMod(Scarlet scarlet, OffsetDateTime endOfDay)
    {
        this.condEmitEx(GroupAuditTypeEx.OUTSTANDING_MODERATION, true, false, null, (channelSf, guild, channel) ->  this.emitOutstandingMod(scarlet, endOfDay, this.scarlet.settings.outstandingPeriodDays.get() * 24L, channel::sendMessageEmbeds));
    }
    <MCR extends MessageCreateRequest<MCR> & FluentRestAction<Message, MCR>> Message emitOutstandingMod(Scarlet scarlet, OffsetDateTime endOfDay, long hoursBack, Function<MessageEmbed, MCR> mca)
    {
        List<String> list = new ArrayList<>();
        if (this.pingOnOutstandingModeration_instanceWarn.get()) list.add("group.instance.warn");
        if (this.pingOnOutstandingModeration_instanceKick.get()) list.add("group.instance.kick");
        if (this.pingOnOutstandingModeration_memberRemove.get()) list.add("group.member.remove");
        if (this.pingOnOutstandingModeration_userBan.get()) list.add("group.user.ban");
        if (this.pingOnOutstandingModeration_userUnban.get()) list.add("group.user.unban");
        if (list.isEmpty())
            return null;
        OffsetDateTime startOfDay = endOfDay.minusHours(hoursBack);
        
        String epochStart = Long.toUnsignedString(startOfDay.toEpochSecond()),
               epochEnd = Long.toUnsignedString(endOfDay.toEpochSecond());
        
        List<GroupAuditLogEntryUnknown> entries = this.scarlet.vrc.auditQuery(startOfDay, endOfDay, null, list.stream().collect(Collectors.joining(",")), null);
        
        if (entries == null)
            return null;
        
        Message message = mca.apply(new EmbedBuilder()
            .setTitle("Outstanding Moderation")
            .setDescription("Spanning <t:"+epochStart+":f> through <t:"+epochEnd+":f>")
            .setColor(GroupAuditTypeEx.OUTSTANDING_MODERATION.color)
            .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
            .setTimestamp(endOfDay)
            .build()).complete();
        
        this.scarlet.exec.submit(() ->
        {
            Map<String, List<ScarletData.AuditEntryMetadata>> map = new HashMap<>();
            Map<String, String> displayNames = new HashMap<>();
            for (GroupAuditLogEntryUnknown entry : entries)
            {
                if (this.scarlet.secretStaffList.isSecretStaffId(entry.getActorId()))
                    continue;
                ScarletData.AuditEntryMetadata entryMeta = this.scarlet.data.auditEntryMetadata(entry.getId());
                if (entryMeta.hasAuxActor() && this.scarlet.secretStaffList.isSecretStaffId(entryMeta.auxActorId))
                    continue;
                String actorId = entryMeta != null && entryMeta.hasAuxActor() ? entryMeta.auxActorId : entry.getActorId();
                String displayName = entryMeta != null && entryMeta.hasAuxActor() ? entryMeta.auxActorDisplayName : entry.getActorDisplayName();
                if (displayName != null)
                    displayNames.put(actorId, displayName);
                if (entryMeta != null
                 && !entryMeta.hasTags()
                 && !entryMeta.hasDescription()
                 && !entryMeta.entryRedacted
                 && !entryMeta.hasParentEvent())
                    map.computeIfAbsent(actorId, $ -> new ArrayList<>()).add(entryMeta);
            }
            
            MessageChannel messageChannel = message.getChannel().getType().isThread()
                ? message.getChannel().asThreadChannel()
                : message.isEphemeral()
                    ? message.getChannel()
                    : message.createThreadChannel("Outstanding Moderation as of "+endOfDay).completeAfter(1000L, TimeUnit.MILLISECONDS);
            
            map.forEach((actorId, metas) ->
            {
                StringBuilder sb = new StringBuilder();
                String actorName = displayNames.getOrDefault(actorId, actorId);
                String actorSf = this.scarlet.data.userMetadata_getSnowflake(actorId);
                if (actorSf == null)
                {
                    sb.append("Unknown Discord id for [").append(actorName).append("](<https://vrchat.com/home/user/").append(actorId).append(">)\n");
                }
                else
                {
                    sb.append("<@").append(actorSf).append(">:\n");
                }
                for (ScarletData.AuditEntryMetadata entryMeta : metas)
                {
                    sb.append(entryMeta.hasTags() ? "[Description](<" : entryMeta.hasDescription() ? "[Tags](<" : "[Tags, Description](<")
                    .append(entryMeta.hasSomeUrl() ? entryMeta.getSomeUrl() : entryMeta.getChannelUrl())
                    .append(">): ")
                    .append(entryMeta.entry.getDescription())
                    .append("\n")
                    ;
                }
                List<String> pages = MiscUtils.paginateOnLines(sb, 2000);
                for (String page : pages)
                {
                    messageChannel.sendMessage(page).completeAfter(1500L, TimeUnit.MILLISECONDS);
                }
            });
        });
        
        return message;
    }

    @Override
    public void tryEmitExtendedSuggestedModeration(Scarlet scarlet, User target)
    {
        int periodDays = this.scarlet.settings.heuristicPeriodDays.get(),
            kickThreshold = this.scarlet.settings.heuristicKickCount.get();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC),
                       before = now.minusDays(periodDays);
        List<GroupAuditLogEntryUnknown> entries = this.scarlet.vrc.auditQuery(before, now, null, "group.instance.kick", target.getId());
        if (entries == null)
            return;
        int kicks = 0;
        for (GroupAuditLogEntryUnknown entry : entries)
        {
            ScarletData.AuditEntryMetadata entryMeta = this.scarlet.data.auditEntryMetadata(entry.getId());
            if (entryMeta == null || !entryMeta.entryRedacted)
            {
                kicks++;
            }
        }
        if (kicks < kickThreshold)
            return;
        int kicks0 = kicks;
        this.condEmitEx(GroupAuditTypeEx.SUGGESTED_MODERATION, false, false, null, (channelSf, guild, channel) ->
        {
            return channel.sendMessageEmbeds(new EmbedBuilder()
                .setTitle(MarkdownSanitizer.escape(target.getDisplayName()), "https://vrchat.com/home/user/"+target.getId())
                .setDescription(String.format("%s has been kicked from group instances %s time%s in the last %s hours", MarkdownSanitizer.escape(target.getDisplayName()), kicks0, kicks0==1?"":"s", kickThreshold*24))
                .addField("User ID", "`"+target.getId()+"`", false)
                .setColor(GroupAuditTypeEx.SUGGESTED_MODERATION.color)
                .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build())
            .addComponents(ActionRow.of(
                Button.danger("vrchat-user-ban:"+target.getId(), "Ban user"),
                Button.success("vrchat-user-unban:"+target.getId(), "Unban user")
            ))
            .complete();
        });
    }

    @Override
    public void tryEmitExtendedWatchedModeration(Scarlet scarlet, User target)
    {
        ScarletWatchedEntities.WatchedEntity watchedPlayer = this.scarlet.watchedUsers.getWatchedEntity(target.getId());
        if (watchedPlayer == null) return;
        this.condEmitEx(GroupAuditTypeEx.WATCHED_MODERATION, false, false, null, (channelSf, guild, channel) ->
        {
            return channel.sendMessageEmbeds(new EmbedBuilder()
                .setTitle(MarkdownSanitizer.escape(target.getDisplayName()), "https://vrchat.com/home/user/"+target.getId())
                .setDescription(watchedPlayer.message)
                .addField("User ID", "`"+target.getId()+"`", false)
                .addField("Type", "`"+watchedPlayer.type+"`", false)
                .addField("Tags", watchedPlayer.tags.stream().map(this.scarlet.moderationTags::getTagLabel).collect(Collectors.joining(", ")), false)
                .addField("Message", watchedPlayer.message, false)
                .addField("Critical", "`"+watchedPlayer.critical+"`", true)
                .addField("Priority", "`"+watchedPlayer.priority+"`", true)
                .addField("Silent", "`"+watchedPlayer.silent+"`", true)
                .setColor(GroupAuditTypeEx.WATCHED_MODERATION.color)
                .setFooter(ScarletDiscord.FOOTER_PREFIX+"Extended event")
                .setTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build())
            .addComponents(ActionRow.of(
                Button.danger("vrchat-user-ban:"+target.getId(), "Ban user"),
                Button.success("vrchat-user-unban:"+target.getId(), "Unban user")
            ))
            .complete();
        });
    }

    @Override
    public void emitActionFailure(Scarlet scarlet, Action action, String format, Object... args)
    {
        this.condEmitEx(GroupAuditTypeEx.ACTION_FAIL, true, this.scarlet.secretStaffList.isSecretStaffId(action.actorId), action.location, (channelSf, guild, channel) -> channel.sendMessageFormat(format, args).complete());
    }

}

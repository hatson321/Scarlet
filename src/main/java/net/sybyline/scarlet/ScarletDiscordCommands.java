package net.sybyline.scarlet;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.vrchatapi.api.GroupsApi;
import io.github.vrchatapi.api.InstancesApi;
import io.github.vrchatapi.api.UsersApi;
import io.github.vrchatapi.api.WorldsApi;
import io.github.vrchatapi.model.Avatar;
import io.github.vrchatapi.model.CalendarEventAccess;
import io.github.vrchatapi.model.CalendarEventPlatform;
import io.github.vrchatapi.model.CreateCalendarEventRequest;
import io.github.vrchatapi.model.CreateGroupAnnouncementRequest;
import io.github.vrchatapi.model.CreateGroupPostRequest;
import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupAnnouncement;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.GroupGallery;
import io.github.vrchatapi.model.GroupGalleryImage;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberLimitedUser;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.GroupPost;
import io.github.vrchatapi.model.GroupPostVisibility;
import io.github.vrchatapi.model.GroupRole;
import io.github.vrchatapi.model.GroupTransferable;
import io.github.vrchatapi.model.GroupTransferableRequirements;
import io.github.vrchatapi.model.Instance;
import io.github.vrchatapi.model.LimitedUserSearch;
import io.github.vrchatapi.model.LimitedWorld;
import io.github.vrchatapi.model.PerformanceRatings;
import io.github.vrchatapi.model.User;
import io.github.vrchatapi.model.World;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.ModalTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.MarkdownUtil;
import net.sybyline.scarlet.ScarletSettings.FileValued;
import net.sybyline.scarlet.ScarletSettings.FileValuedVisitor;
import net.sybyline.scarlet.ext.AvatarSearch;
import net.sybyline.scarlet.log.ScarletLogger;
import net.sybyline.scarlet.server.discord.DEnum;
import net.sybyline.scarlet.server.discord.DInteractions;
import net.sybyline.scarlet.server.discord.DInteractions.ButtonClk;
import net.sybyline.scarlet.server.discord.DInteractions.DefaultPerms;
import net.sybyline.scarlet.server.discord.DInteractions.Desc;
import net.sybyline.scarlet.server.discord.DInteractions.Ephemeral;
import net.sybyline.scarlet.server.discord.DInteractions.FeatureGate;
import net.sybyline.scarlet.server.discord.DInteractions.ModalFlowOption;
import net.sybyline.scarlet.server.discord.DInteractions.ModalSub;
import net.sybyline.scarlet.server.discord.DInteractions.MsgCmd;
import net.sybyline.scarlet.server.discord.DInteractions.Required;
import net.sybyline.scarlet.server.discord.DInteractions.SlashCmd;
import net.sybyline.scarlet.server.discord.DInteractions.SlashOpt;
import net.sybyline.scarlet.server.discord.DInteractions.SlashOption;
import net.sybyline.scarlet.server.discord.DInteractions.SlashOptionStrings;
import net.sybyline.scarlet.server.discord.DInteractions.SlashOptionsChoicesUnsanitized;
import net.sybyline.scarlet.server.discord.DInteractions.StringSel;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.sybyline.scarlet.server.discord.DOptionEnum;
import net.sybyline.scarlet.util.Func.F1;
import net.sybyline.scarlet.util.tts.TtsProvider;
import net.sybyline.scarlet.util.Gifs;
import net.sybyline.scarlet.util.HttpURLInputStream;
import net.sybyline.scarlet.util.Location;
import net.sybyline.scarlet.util.Maths;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.UniqueStrings;
import net.sybyline.scarlet.util.VRChatHelpDeskURLs;
import net.sybyline.scarlet.util.VrcIds;
import net.sybyline.scarlet.util.VrcWeb;

public class ScarletDiscordCommands
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/JDA/CMDS");

    public ScarletDiscordCommands(ScarletDiscordJDA discord)
    {
        this.discord = discord;
        discord.interactions.register(this);
        Map<String, String> languages = discord.scarlet.vrc.getConfigLanguageOptions();
        this.languages = languages == null || languages.isEmpty() ? new HashMap<>() : languages;
        this.spokenLanguages = languages == null || languages.isEmpty()
            ? new Command.Choice[0] 
            : languages
                .entrySet()
                .stream()
                .sequential()
                .sorted(Map.Entry.comparingByKey())
                .map($ -> new Command.Choice($.getValue(), $.getKey()))
                .toArray(Command.Choice[]::new);
    }

    final ScarletDiscordJDA discord;
    Map<String, String> languages;
    Command.Choice[] spokenLanguages;

    // Commands

    public static enum DiscordAccountAgeThreshold implements DEnum.DEnumLong<DiscordAccountAgeThreshold>
    {
        DISABLED("Disabled", 0L),
        ONE_MINUTE("1 minute", 1L),
        FIVE_MINUTES("5 minutes", 5L),
        TEN_MINUTES("10 minutes", 10L),
        FIFTEEN_MINUTES("15 minutes", 15L),
        THIRTY_MINUTES("30 minutes", 30L),
        ONE_HOUR("1 hour", 60L),
        TWO_HOURS("2 hours", 120L),
        SIX_HOURS("6 hours", 360L),
        TWELVE_HOURS("12 hours", 720L),
        ONE_DAY("1 day", 1_440L),
        TWO_DAYS("2 days", 2_880L),
        THREE_DAYS("3 days", 4_320L),
        ONE_WEEK("1 week", 10_080L),
        TWO_WEEKS("2 weeks", 20_160L),
        ONE_MONTH("1 month", 43_200L),
        THREE_MONTHS("3 months", 129_600L),
        SIX_MONTHS("6 months", 259_200L),
        ONE_YEAR("1 year", 525_600L);

        DiscordAccountAgeThreshold(String display, Long minutes)
        {
            this.display = display;
            this.minutes = minutes;
        }
        final String display;
        final Long minutes;
        @Override public Long value() { return this.minutes; }
        @Override public String display() { return this.display; }
    }

    public final SlashOption<io.github.vrchatapi.model.User> _vrchatUser = SlashOption.ofString("vrchat-user", "The VRChat user id (usr_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX)", true, null, this::_vrchatUser, true, this::_vrchatUser);
    io.github.vrchatapi.model.User _vrchatUser(String id) { return this.discord.scarlet.vrc.getUser(id); }
    void _vrchatUser(CommandAutoCompleteInteractionEvent event) {
        String value = VrcIds.resolveUserId(event.getFocusedOption().getValue());
        if (value.isEmpty())
        {
            event.replyChoices().queue();
            return;
        }
        if (VrcIds.id_user.matcher(value).matches())
        {
            User user = this.discord.scarlet.vrc.getUser(value, Long.MIN_VALUE);
            if (user != null)
            {
                event.replyChoice(user.getId()+": "+user.getDisplayName(), user.getId()).queue();
                return;
            }
        }
        List<LimitedUserSearch> lusers = this.discord.scarlet.vrc.searchUsers(value, 25, 0);
        if (lusers == null)
        {
            event.replyChoices().queue();
            return;
        }
        event.replyChoices(lusers.stream().map($ -> new Command.Choice($.getId()+": "+$.getDisplayName(), $.getId())).limit(25L).toArray(Command.Choice[]::new)).queue();
    }
    public final SlashOption<io.github.vrchatapi.model.World> _vrchatWorld = SlashOption.ofString("vrchat-world", "The VRChat world id (wrld_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX)", true, null, this::_vrchatWorld, true, this::_vrchatWorld);
    io.github.vrchatapi.model.World _vrchatWorld(String id) { return this.discord.scarlet.vrc.getWorld(id); }
    void _vrchatWorld(CommandAutoCompleteInteractionEvent event) {
        String value = VrcIds.resolveWorldId(event.getFocusedOption().getValue());
        if (value.isEmpty())
        {
            event.replyChoices().queue();
            return;
        }
        if (VrcIds.id_world.matcher(value).matches())
        {
            World world = this.discord.scarlet.vrc.getWorld(value, Long.MIN_VALUE);
            if (world != null)
            {
                event.replyChoice(world.getId()+": "+world.getName()+" by "+world.getAuthorName(), world.getId()).queue();
                return;
            }
        }
        List<LimitedWorld> lworlds = this.discord.scarlet.vrc.searchWorlds(value, 25, 0);
        if (lworlds == null)
        {
            event.replyChoices().queue();
            return;
        }
        event.replyChoices(lworlds.stream().map($ -> new Command.Choice($.getId()+": "+$.getName()+" by "+$.getAuthorName(), $.getId())).limit(25L).toArray(Command.Choice[]::new)).queue();
    }
    public final SlashOption<io.github.vrchatapi.model.GroupRole> _vrchatRoleOpt = SlashOption.ofString("vrchat-role", "The VRChat role id (grol_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX)", false, null, this::_vrchatRole, true, this::_vrchatRole);
    public final SlashOption<io.github.vrchatapi.model.GroupRole> _vrchatRole = SlashOption.ofString("vrchat-role", "The VRChat role id (grol_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX)", true, null, this::_vrchatRole, true, this::_vrchatRole);
    io.github.vrchatapi.model.GroupRole _vrchatRole(String id) { return this.discord.scarlet.vrc.group.getRoles().stream().filter($ -> Objects.equals(id, $.getId())).findFirst().orElse(null); }
    void _vrchatRole(CommandAutoCompleteInteractionEvent event) {
        DInteractions.SlashOptionsChoicesUnsanitized.autocomplete(event, ScarletDiscordCommands.this.discord.scarlet.vrc.group.getRoles().stream().map($ -> new Command.Choice($.getName(), $.getId())).toArray(Command.Choice[]::new), false);
    }
    public final SlashOption<GroupAuditType> _auditEventType = SlashOption.ofEnum("audit-event-type", "The VRChat Group Audit Log event type", true, GroupAuditType.class);
    public final SlashOption<GroupAuditTypeEx> _auditExEventType = SlashOption.ofEnum("audit-ex-event-type", "The extended audit event type", true, GroupAuditTypeEx.class);
    public final SlashOption<GroupAuditTypeEx> _auditSecretExEventType = secretAuditExOption();
    SlashOption<GroupAuditTypeEx> secretAuditExOption()
    {
        return SlashOption.ofString("secret-audit-ex-event-type",
            "A supported secret event, or a saved legacy entry to clear by omitting the channel", true, null,
            ScarletDiscordCommands::parseSecretAuditExType, false,
            event -> DInteractions.SlashOptionsChoicesUnsanitized.autocomplete(event,
                secretAuditExChoices(this.discord.auditExType2secretChannelSf), false));
    }
    static GroupAuditTypeEx parseSecretAuditExType(String value)
    {
        for (GroupAuditTypeEx type : GroupAuditTypeEx.values())
            if (type.id.equals(value)) return type;
        throw new DInteractions.InvalidSlashOptionException();
    }
    static Command.Choice[] secretAuditExChoices(Map<String, String> saved)
    {
        return Arrays.stream(GroupAuditTypeEx.values())
            .filter(type -> type.supportsSecretRouting() || saved.containsKey(type.id))
            .map(type -> new Command.Choice(type.supportsSecretRouting() ? type.title : "Clear legacy entry: " + type.title, type.id))
            .toArray(Command.Choice[]::new);
    }
    public final SlashOption<Member> _discordUser = SlashOption.ofMember("discord-user", "The Discord user", true);
    public final SlashOption<Role> _discordRole = SlashOption.ofRole("discord-role", "The Discord role", true);
    public final SlashOption<Channel> _textChannel = SlashOption.ofChannel("discord-text-channel", "The Discord text channel to use, or omit to remove entry", false, ChannelType.TEXT);
    public final SlashOption<Channel> _voiceChannel = SlashOption.ofChannel("discord-voice-channel", "The Discord voice channel to use, or omit to remove entry", false, ChannelType.VOICE);
    public final SlashOption<Channel> _categoryChannel = SlashOption.ofChannel("discord-category", "The Discord category to use", false, ChannelType.CATEGORY);
    public final SlashOption<DiscordAccountAgeThreshold> _discordAccountAgeThreshold = SlashOption.ofEnum("threshold", "Minimum Discord account age before flagging joins", true, DiscordAccountAgeThreshold.DISABLED);
    public final SlashOption<Boolean> _ticketToolAutoResponseEnabled = SlashOption.ofBool("enabled", "Whether the bot should auto-respond in Ticket Tool channels", false, null);
    public final SlashOption<Role> _ticketToolNotifyRole = SlashOption.ofRole("notify-role", "Role to ping with the ticket opener", false);
    public final SlashOption<Boolean> _autoInviteEnabled = SlashOption.ofBool("enabled", "Whether to auto-invite newly-verified members to the VRChat group", false, null);
    public final SlashOption<Role> _autoInviteRole = SlashOption.ofRole("verified-role", "The Discord role that, once given to a member, triggers the VRChat group invite", false);
    public final SlashOption<Role> _membersRole = SlashOption.ofRole("members-role", "The Discord role members must have before being prompted to link their VRChat account", false);
    public final SlashOption<String> _autoInviteGroupId = SlashOption.ofString("vrchat-group-id", "The VRChat group id to invite verified members to (grp_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX)", false, null);
    public final SlashOption<String> _ticketToolChannelNameRegex = SlashOption.ofString("channel-name-regex", "Optional Java regex for ticket channel names; empty clears it", false, null);
    public final SlashOption<Integer> _daysBack = SlashOption.ofInt("days-back", "The number of days into the past to search for events", false, 4).with($->$.setRequiredRange(1L, 1000L));
    public final SlashOption<Integer> _hoursBack = SlashOption.ofInt("hours-back", "The number of hours into the past to search for events", false, 24).with($->$.setRequiredRange(1L, 24_000L));
    public final SlashOption<Integer> _pagination = SlashOption.ofInt("entries-per-page", "The number of entries to show per page", false, 4).with($->$.setRequiredRange(1L, 10L));
    public final SlashOption<Boolean> _tagImmediately = SlashOption.ofBool("tag-immediately", "Whether to submit tags and description now", false, false);
    public final SlashOption<Message.Attachment> _importedFile = SlashOption.ofAttachment("import-file", "Accepts: JSON, CSV", true);

    public final SlashOption<ScarletWatchedEntities.WatchedEntity.Type> _entityType = SlashOption.ofEnum("entity-type", "The type of watched entity", true, ScarletWatchedEntities.WatchedEntity.Type.UNKNOWN);
    public final SlashOption<String> _entityTags = SlashOption.ofString("entity-tags", "A list of tags, separated by one of ',', ';', '/'", false, null);
    public final SlashOption<String> _entityTag = SlashOption.ofString("entity-tag", "A tag", true, null);
    public final SlashOption<Integer> _entityPriority = SlashOption.ofInt("entity-priority", "The priority of this entity", false, 0).with($->$.setRequiredRange(-100L, 100L));
    public final SlashOption<String> _entityMessage = SlashOption.ofString("entity-message", "A message to announce with TTS", false, null);
    public final SlashOption<Boolean> _entityCritical = SlashOption.ofBool("entity-critical", "The critical status of the entity", true, null);
    public final SlashOption<Boolean> _entitySilent = SlashOption.ofBool("entity-silent", "The silent status of the entity", true, null);
    public final SlashOption<String> _actionReason = SlashOption.ofString("reason", "Reason shown to the user and recorded in the audit log", false, null);

    // vrchat-search

    @SlashCmd("vrchat-search")
    @Desc("Searches for VRChat things")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class VrchatSearch
    {
        public final SlashOption<String> _searchQuery = SlashOption.ofString("search-query", "The search query", true, null);
        @SlashCmd("world")
        @Desc("Searches for worlds")
        public void world(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("search-query") String searchQuery, @SlashOpt("entries-per-page") int entriesPerPage) throws Exception
        {
            MessageEmbed[] embeds = new WorldsApi(ScarletDiscordCommands.this.discord.scarlet.vrc.client)
                .searchWorlds(null, null, null, null, 50, null, 0, searchQuery, null, null, null, null, null, null, null, null, null)
                .stream()
                .map($ -> new EmbedBuilder()
                    .setAuthor($.getAuthorName(), VrcWeb.Home.user($.getAuthorId()), null)
                    .setTitle(MiscUtils.maybeEllipsis(256, $.getName()), VrcWeb.Home.world($.getId()))
                    .setThumbnail($.getThumbnailImageUrl() == null || $.getThumbnailImageUrl().isEmpty() ? null : $.getThumbnailImageUrl())
                    .addField("Report world", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_content_world(ScarletDiscordCommands.this.discord.requestingEmail.get(), $.getId(), "World", null)), false)
                    .build())
                .toArray(MessageEmbed[]::new);
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }
        @SlashCmd("user")
        @Desc("Searches for users")
        public void user(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("search-query") String searchQuery, @SlashOpt("entries-per-page") int entriesPerPage) throws Exception
        {
            MessageEmbed[] embeds = new UsersApi(ScarletDiscordCommands.this.discord.scarlet.vrc.client)
                .searchUsers(searchQuery, null, null, null, 50, 0, null)
                .stream()
                .map($ -> new EmbedBuilder()
                    .setTitle(MiscUtils.maybeEllipsis(256, $.getDisplayName()), VrcWeb.Home.user($.getId()))
                    .setThumbnail($.getIconUrl() == null || $.getIconUrl().isEmpty() ? null : $.getIconUrl())
                    .addField("Report account", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_account(ScarletDiscordCommands.this.discord.requestingEmail.get(), null, $.getId(), "Account", null)), false)
                    .build())
                .toArray(MessageEmbed[]::new);
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }
        @SlashCmd("group")
        @Desc("Searches for groups")
        public void group(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("search-query") String searchQuery, @SlashOpt("entries-per-page") int entriesPerPage) throws Exception
        {
            MessageEmbed[] embeds = new GroupsApi(ScarletDiscordCommands.this.discord.scarlet.vrc.client)
                .searchGroups(searchQuery, 0, 50)
                .stream()
                .map($ -> new EmbedBuilder()
                    .setAuthor($.getShortCode()+"."+$.getDiscriminator(), null, $.getIconUrl())
                    .setTitle(MiscUtils.maybeEllipsis(256, $.getName()), VrcWeb.Home.group($.getId()))
                    .setThumbnail($.getBannerUrl() == null || $.getBannerUrl().isEmpty() ? null : $.getBannerUrl())
                    .setDescription($.getDescription() == null || $.getDescription().isEmpty() ? null : $.getDescription())
                    .addField("Report group", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_content_group(ScarletDiscordCommands.this.discord.requestingEmail.get(), $.getId(), "Group", null)), false)
                    .build())
                .toArray(MessageEmbed[]::new);
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }
        @SlashCmd("avatar")
        @Desc("Searches for avatars")
        @FeatureGate("avatar_search.enabled")
        public void avatar(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("search-query") String searchQuery, @SlashOpt("entries-per-page") int entriesPerPage) throws Exception
        {
            java.util.function.BooleanSupplier current = ScarletDiscordCommands.this.discord.avatarProviderRequestGuard(() -> true);
            MessageEmbed[] embeds = ScarletDiscordCommands.this.discord.searchAvatarProviders(searchQuery, AvatarSearch.SEARCH_N, current)
                .map($ -> new EmbedBuilder()
                    .setAuthor($.authorName, VrcWeb.Home.user($.authorId), null)
                    .setTitle(MiscUtils.maybeEllipsis(256, $.name), VrcWeb.Home.avatar($.id))
                    .setThumbnail($.imageUrl == null || $.imageUrl.isEmpty() ? null : $.imageUrl)
                    .setDescription($.description == null || $.description.isEmpty() ? null : $.description)
                    .addField("Report avatar", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_content_avatar(ScarletDiscordCommands.this.discord.requestingEmail.get(), $.id, "Avatar", null)), false)
                    .build())
                .toArray(MessageEmbed[]::new);
            if (!current.getAsBoolean())
            {
                hook.sendMessage("Avatar search unavailable or cancelled because the account or provider permissions changed.")
                    .setEphemeral(true).queue();
                return;
            }
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }
    }
    // moderation-tags

    @SlashCmd("moderation-tags")
    @Desc("Configures custom moderation tags")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class ModerationTags
    {
        @SlashCmd("list")
        @Desc("Lists all custom moderation tags")
        public void list(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            MessageEmbed[] embeds = ScarletDiscordCommands.this.discord.scarlet.moderationTags.getTags().stream().map(tag -> new EmbedBuilder().setAuthor(tag.value).setTitle(MiscUtils.maybeEllipsis(256, tag.label)).setDescription(MiscUtils.maybeEllipsis(4096, tag.description)).build()).toArray(MessageEmbed[]::new);
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }
        public final SlashOption<String> _valueNA = SlashOption.ofString("value", "The internal id of the tag (prefer only `a-zA-Z`, `0-9`, `-`, `_`)", true, null).with($ -> $.setRequiredLength(1, 100));
        public final SlashOption<String> _label = SlashOption.ofString("label", "The internal display name of the tag", true, null).with($ -> $.setRequiredLength(1, 100));
        public final SlashOption<String> _description = SlashOption.ofString("description", "The internal description of the tag", false, null);
        @SlashCmd("add")
        @Desc("Adds a custom moderation tag")
        public void add(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("_valueNA") String value, @SlashOpt("label") String label, @SlashOpt("description") String description)
        {
            switch (ScarletDiscordCommands.this.discord.scarlet.moderationTags.addOrUpdateTag(value, label, description))
            {
            default: {
                hook.sendMessage("Failed to add moderation tag: list == null").setEphemeral(true).queue();
            } break;
            case -2: {
                hook.sendMessage("Failed to add moderation tag: there are already the maximum of 125 moderation tags").setEphemeral(true).queue();
            } break;
            case -1: {
                hook.sendMessage("Failed to add moderation tag: list.add returned false").setEphemeral(true).queue();
            } break;
            case 0: {
                StringBuilder sb = new StringBuilder();
                sb.append("Created moderation tag `").append(value).append("`:");
                if (label != null)
                    sb.append(" label=`").append(label).append("`");
                if (description != null)
                    sb.append(" description=`").append(description).append("`");
                String msg = sb.toString();
                LOG.info(msg);
                hook.sendMessage(msg).setEphemeral(true).queue();
            } break;
            case 1: {
                StringBuilder sb = new StringBuilder();
                sb.append("Updated moderation tag `").append(value).append("`:");
                if (label != null)
                    sb.append(" label=`").append(label).append("`");
                if (description != null)
                    sb.append(" description=`").append(description).append("`");
                String msg = sb.toString();
                LOG.info(msg);
                hook.sendMessage(msg).setEphemeral(true).queue();
            } break;
            }
        }
        public final SlashOption<String> _value = SlashOption.ofString("value", "The internal id of the tag", true, null, this::_value);
        void _value(CommandAutoCompleteInteractionEvent event) {
            DInteractions.SlashOptionsChoicesUnsanitized.autocomplete(event, ScarletDiscordCommands.this.discord.scarlet.moderationTags.getTagChoices().toArray(new Command.Choice[0]), false);
        }
        @SlashCmd("delete")
        @Desc("Removes a custom moderation tag")
        public void list(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("_value") String value)
        {
            switch (ScarletDiscordCommands.this.discord.scarlet.moderationTags.removeTag(value))
            {
            default: {
                hook.sendMessage("Failed to delete moderation tag: list == null").setEphemeral(true).queue();
            } break;
            case -3: {
                hook.sendMessageFormat("Failed to add the tag: the tag `%s` does not exist", value).setEphemeral(true).queue();
            } break;
            case -2: {
                hook.sendMessage("Failed to delete moderation tag: there are no moderation tags").setEphemeral(true).queue();
            } break;
            case -1: {
                hook.sendMessage("Failed to delete moderation tag: list.remove returned false").setEphemeral(true).queue();
            } break;
            case 0: {
                String msg = "Deleted moderation tag `"+value+"`";
                LOG.info(msg);
                hook.sendMessage(msg).setEphemeral(true).queue();
            } break;
            }
        }
    }

    // tag

    /**
     * {@code /tag add|remove} inside a moderation event's thread. Each tag option autocompletes
     * live across EVERY moderation tag (label, id and description, typo-tolerant), so a moderator
     * can type "harass" and see only the harassment tags - no 25-per-dropdown limit in the way.
     */
    @SlashCmd("tag")
    @Desc("Tag the moderation event this thread belongs to")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class Tag_
    {
        public final SlashOption<String> _tag1 = SlashOption.ofString("tag", "Start typing to search every moderation tag", true, null, this::_complete);
        public final SlashOption<String> _tag2 = SlashOption.ofString("tag-2", "Another tag (optional)", false, null, this::_complete);
        public final SlashOption<String> _tag3 = SlashOption.ofString("tag-3", "Another tag (optional)", false, null, this::_complete);
        public final SlashOption<String> _tag4 = SlashOption.ofString("tag-4", "Another tag (optional)", false, null, this::_complete);
        public final SlashOption<String> _tag5 = SlashOption.ofString("tag-5", "Another tag (optional)", false, null, this::_complete);

        void _complete(CommandAutoCompleteInteractionEvent event)
        {
            String typing = event.getFocusedOption().getValue();
            // Don't suggest a tag already picked in one of the other tag options.
            java.util.Set<String> picked = new java.util.HashSet<>();
            for (net.dv8tion.jda.api.interactions.commands.OptionMapping om : event.getOptions())
                if (!om.getName().equals(event.getFocusedOption().getName()))
                    picked.add(om.getAsString());
            List<Command.Choice> choices = new ArrayList<>();
            for (ScarletModerationTags.Tag tag : ScarletDiscordCommands.this.discord.scarlet.moderationTags.searchTags(typing, 125))
            {
                if (picked.contains(tag.value) || tag.value == null || tag.value.isEmpty() || tag.value.length() > 100)
                    continue;
                String label = tag.label != null && !tag.label.isEmpty() ? tag.label : tag.value;
                String shown = tag.description == null || tag.description.isEmpty() ? label : label + " — " + tag.description;
                choices.add(new Command.Choice(MiscUtils.maybeEllipsis(100, shown), tag.value));
                if (choices.size() >= 25)
                    break;
            }
            event.replyChoices(choices).queue();
        }

        @SlashCmd("add")
        @Desc("Add tags to this thread's moderation event (type to search all tags)")
        @Ephemeral
        public void add(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("_tag1") String tag1, @SlashOpt("_tag2") String tag2, @SlashOpt("_tag3") String tag3, @SlashOpt("_tag4") String tag4, @SlashOpt("_tag5") String tag5)
        {
            this._apply(event, hook, true, tag1, tag2, tag3, tag4, tag5);
        }

        @SlashCmd("remove")
        @Desc("Remove tags from this thread's moderation event")
        @Ephemeral
        public void remove(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("_tag1") String tag1, @SlashOpt("_tag2") String tag2, @SlashOpt("_tag3") String tag3, @SlashOpt("_tag4") String tag4, @SlashOpt("_tag5") String tag5)
        {
            this._apply(event, hook, false, tag1, tag2, tag3, tag4, tag5);
        }

        void _apply(SlashCommandInteractionEvent event, InteractionHook hook, boolean add, String... inputs)
        {
            String auditEntryId = ScarletDiscordCommands.this.discord.discordUI.auditEntryIdForThread(event.getChannel());
            if (auditEntryId == null)
            {
                hook.sendMessage("Run `/tag` inside the thread of the kick, ban or warn you want to tag.").setEphemeral(true).queue();
                return;
            }
            if (!ScarletDiscordCommands.this.discord.discordUI.checkAuditEntryTagAccess(event.getMember(), event.getMessageChannel(), hook, auditEntryId))
                return;
            ScarletModerationTags tagsDb = ScarletDiscordCommands.this.discord.scarlet.moderationTags;
            List<String> values = new ArrayList<>(), unknown = new ArrayList<>();
            for (String input : inputs)
            {
                if (input == null || input.trim().isEmpty())
                    continue;
                ScarletModerationTags.Tag tag = tagsDb.getTag(input.trim());
                if (tag == null) // typed a label instead of picking a suggestion
                    tag = tagsDb.getTags().stream().filter($ -> $.label != null && $.label.equalsIgnoreCase(input.trim())).findFirst().orElse(null);
                if (tag == null)
                    unknown.add(input.trim());
                else if (!values.contains(tag.value))
                    values.add(tag.value);
            }
            if (!unknown.isEmpty())
            {
                hook.sendMessage("Unknown tag" + (unknown.size() == 1 ? "" : "s") + ": `" + String.join("`, `", unknown) + "` - pick from the suggestions as you type.").setEphemeral(true).queue();
                return;
            }
            String[] arr = values.toArray(new String[0]);
            ScarletData.AuditEntryMetadata meta = add
                ? ScarletDiscordCommands.this.discord.scarlet.data.auditEntryMetadata_editTags(auditEntryId, new String[0], arr)
                : ScarletDiscordCommands.this.discord.scarlet.data.auditEntryMetadata_editTags(auditEntryId, arr, new String[0]);
            String now = meta.entryTags == null || meta.entryTags.size() == 0
                ? "*(none)*"
                : meta.entryTags.strings().stream().map(tagsDb::getTagLabel).collect(Collectors.joining(", "));
            hook.sendMessage((add ? "Added. " : "Removed. ") + "Tags on this event now: " + now).setEphemeral(true).queue();
            ScarletDiscordCommands.this.discord.discordUI.updateAuxMessage(event.getChannel(), meta);
        }
    }

    // watched-group

    @SlashCmd("watched-group")
    @Desc("Configures watched groups")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class WatchedGroup_
    {
        @SlashCmd("list")
        @Desc("Lists all watched groups")
        public void list(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            MessageEmbed[] embeds = ScarletDiscordCommands.this.discord.scarlet
                .watchedGroups
                .watchedGroups
                .values()
                .stream()
                .map($ -> $.embed(ScarletDiscordCommands.this.discord.scarlet.vrc.getGroup($.id)).build())
                .toArray(MessageEmbed[]::new)
            ;
            
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }
        @SlashCmd("remove-menu")
        @Desc("Remove watched groups from a dropdown of their names")
        public void removeMenu(SlashCommandInteractionEvent event, InteractionHook hook)
        {
            ScarletDiscordCommands.this.emitWatchedRemoveMenu(hook, "group", "groups",
                new java.util.ArrayList<>(ScarletDiscordCommands.this.discord.scarlet.watchedGroups.watchedGroups.keySet()),
                id ->
                {
                    Group g = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroup(id);
                    return g == null ? null : g.getName();
                });
        }
        @SlashCmd("export")
        @Desc("Exports watched groups as a JSON file")
        public void export(SlashCommandInteractionEvent event, InteractionHook hook)
        {
            LOG.info("Exporting watched groups JSON");
            hook.sendFiles(FileUpload.fromData(ScarletDiscordCommands.this.discord.scarlet.watchedGroups.watchedGroupsFile)).setEphemeral(false).queue();
        }
        @SlashCmd("import")
        @Desc("Imports watched groups from an attached file")
        public void import_(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("import-file") Message.Attachment importedFile)
        {
            String fileName = importedFile.getFileName(),
                    attachmentUrl = importedFile.getUrl();
             
             if (fileName.endsWith(".csv"))
             {
                 LOG.info("Importing watched groups legacy CSV from attachment: "+fileName);
                 try (Reader reader = new InputStreamReader(HttpURLInputStream.get(attachmentUrl, HttpURLInputStream.PUBLIC_ONLY)))
                 {
                     if (ScarletDiscordCommands.this.discord.scarlet.watchedGroups.importLegacyCSV(reader, true))
                     {
                         LOG.info("Successfully imported watched groups legacy CSV");
                         hook.sendMessageFormat("Successfully imported watched groups legacy CSV").setEphemeral(true).queue();
                     }
                     else
                     {
                         LOG.warn("Failed to import watched groups legacy CSV with unknown reason");
                         hook.sendMessageFormat("Failed to import watched groups legacy CSV with unknown reason").setEphemeral(true).queue();
                     }
                 }
                 catch (Exception ex)
                 {
                     LOG.error("Exception importing watched groups legacy CSV from attachment: "+fileName, ex);
                     hook.sendMessageFormat("Exception while importing %s: %s", fileName, ex).setEphemeral(true).queue();
                 }
             }
             else if (fileName.endsWith(".json"))
             {
                 LOG.info("Importing watched groups JSON from attachment: "+fileName);
                 try (Reader reader = new InputStreamReader(HttpURLInputStream.get(attachmentUrl, HttpURLInputStream.PUBLIC_ONLY)))
                 {
                     if (ScarletDiscordCommands.this.discord.scarlet.watchedGroups.importJson(reader, true))
                     {
                         LOG.info("Successfully imported watched groups JSON");
                         hook.sendMessageFormat("Successfully imported watched groups JSON").setEphemeral(true).queue();
                     }
                     else
                     {
                         LOG.warn("Failed to import watched groups JSON with unknown reason");
                         hook.sendMessageFormat("Failed to import watched groups JSON with unknown reason").setEphemeral(true).queue();
                     }
                 }
                 catch (Exception ex)
                 {
                     LOG.error("Exception importing watched groups JSON from attachment: "+fileName, ex);
                     hook.sendMessageFormat("Exception while importing %s: %s", fileName, ex).setEphemeral(true).queue();
                 }
             }
             else
             {
                 LOG.warn("Skipping attachment: "+fileName);
                 hook.sendMessageFormat("File '%s' is not importable.", fileName).setEphemeral(true).queue();
             }
        }
        public final SlashOption<String> _vrchatGroup = SlashOption.ofString("vrchat-group", "The VRChat group id (grp_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX)", true, null, VrcIds::resolveGroupId, true, this::_vrchatGroup);
        void _vrchatGroup(CommandAutoCompleteInteractionEvent event) {
            AutoCompleteCallbackAction action = event.replyChoices();
            Command.Choice groupChoice = ScarletDiscordCommands.this.discord.userSf2lastEdited_groupId.get(event.getUser().getId());
            long choicesLeft = 25L;
            if (groupChoice != null)
            {
                action.addChoices(groupChoice);
                choicesLeft--;
            }
            String typing = event.getFocusedOption().getValue();
            if (typing != null && !(typing = typing.trim()).isEmpty())
            {
                if (VrcIds.id_group.matcher(typing).matches() || VrcIds.id_group_code.matcher(typing).matches())
                {
                    String groupId = VrcIds.resolveGroupId(typing);
                    Group group = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroup(groupId, ScarletJsonCache.ALWAYS_PREFER_CACHED);
                    if (group != null)
                    {
                        action.addChoice(group.getName(), typing);
                        choicesLeft--;
                    }
                }
                else
                {
                    action.addChoices(ScarletDiscordCommands.this.discord.scarlet.watchedGroups
                        .watchedGroups
                        .keySet()
                        .stream()
                        .map(id -> {
                            Group group0 = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroup(id, ScarletJsonCache.ALWAYS_PREFER_CACHED);
                            return new Command.Choice(group0 != null ? group0.getName() : id, id);
                        })
                        .sorted(DInteractions.choicesByLevenshtein(typing))
                        .limit(choicesLeft)
                        .collect(Collectors.toList())
                    );
                }
            }
            else
            {
                action.addChoices(ScarletDiscordCommands.this.discord.scarlet.watchedGroups
                    .watchedGroups
                    .keySet()
                    .stream()
                    .map(id -> {
                        Group group0 = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroup(id, ScarletJsonCache.ALWAYS_PREFER_CACHED);
                        return new Command.Choice(group0 != null ? group0.getName() : id, id);
                    })
                    .limit(choicesLeft)
                    .collect(Collectors.toList())
                );
            }
            action.queue();
        }
        io.github.vrchatapi.model.Group vrchatGroup(SlashCommandInteractionEvent event, String groupId)
        {
            if (groupId != null && !(groupId = groupId.trim()).isEmpty())
            {
                Group group = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroup(groupId);
                ScarletDiscordCommands.this.discord.userSf2lastEdited_groupId.put(event.getUser().getId(), new Command.Choice(group != null ? group.getName() : groupId, groupId));
                return group;
            }
            return null;
        }
        
        public final SlashOption<ScarletWatchedGroups.WatchedGroup.Type> _groupType = SlashOption.ofEnum("group-type", "The type of watched group", true, ScarletWatchedGroups.WatchedGroup.Type.UNKNOWN);
        public final SlashOption<String> _groupTags = SlashOption.ofString("group-tags", "A list of tags, separated by one of ',', ';', '/'", false, null);
        public final SlashOption<Integer> _groupPriority = SlashOption.ofInt("group-priority", "The priority of this group", false, 0).with($->$.setRequiredRange(-100L, 100L));
        public final SlashOption<String> _message = SlashOption.ofString("message", "A message to announce with TTS", false, null);
        
        @SlashCmd("add")
        @Desc("Adds a watched group")
        public void add(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId,
            @SlashOpt("group-type") ScarletWatchedGroups.WatchedGroup.Type groupType,
            @SlashOpt("group-tags") String groupTags,
            @SlashOpt("group-priority") int groupPriority,
            @SlashOpt("message") String message)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup != null)
            {
                hook.sendMessage("That group is already watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            Group group = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroup(groupId);
            if (group == null)
            {
                hook.sendMessage("That group doesn't seem to exist").setEphemeral(true).queue();
                return;
            }
            watchedGroup = new ScarletWatchedGroups.WatchedGroup();
            watchedGroup.id = groupId;
            watchedGroup.type = groupType;
            if (groupTags != null)
                watchedGroup.tags.clear().addAll(Arrays.stream(groupTags.split("[,;/]")).map(String::trim).toArray(String[]::new));
            if (message != null)
                watchedGroup.message = message;
            watchedGroup.priority = groupPriority;
            ScarletDiscordCommands.this.discord.scarlet.watchedGroups.addWatchedGroup(groupId, watchedGroup);
            hook.sendMessageFormat("Added group [%s](%s)", group.getName(), VrcWeb.Home.group(group.getId())).setEphemeral(true).queue();
        }
        @SlashCmd("delete-watched-group")
        @Desc("Removes a watched group")
        public void deleteWatchedGroup(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId)
        {
            if (ScarletDiscordCommands.this.discord.scarlet.watchedGroups.removeWatchedGroup(groupId))
            {
                Group group = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroup(groupId);
                hook.sendMessageFormat("Removed group [%s](%s)", group == null ? groupId : group.getName(), VrcWeb.Home.group(groupId)).setEphemeral(true).queue();
            }
            else
            {
                hook.sendMessage("That group is not watched").setEphemeral(true).queue();
            }
        }
        @SlashCmd("view")
        @Desc("Views a group's watch information")
        public void view(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup == null)
            {
                hook.sendMessage("That group is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            Group group = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroup(groupId);
            hook.sendMessageEmbeds(watchedGroup.embed(group).build()).setEphemeral(true).queue();
        }
        public final SlashOption<String> _groupTags_R = SlashOption.ofString("group-tags", "A list of tags, separated by one of ',', ';', '/'", true, null);
        public final SlashOption<String> _groupTag_R = SlashOption.ofString("group-tag", "A tag", true, null);
        public final SlashOption<Integer> _groupPriority_R = SlashOption.ofInt("group-priority", "The priority of this group", true, 0).with($->$.setRequiredRange(-100L, 100L));
        public final SlashOption<Boolean> _critical_R = SlashOption.ofBool("critical", "The critical status of the group", true, null);
        public final SlashOption<Boolean> _silent_R = SlashOption.ofBool("silent", "The silent status of the group", true, null);
        public final SlashOption<String> _message_R = SlashOption.ofString("message", "A message to announce with TTS", true, null);
        @SlashCmd("set-critical")
        @Desc("Sets a group's critical status")
        public void setCritical(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId, @SlashOpt("_critical_R") boolean critical)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup == null)
            {
                hook.sendMessage("That group is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            if (critical)
            {
                if (watchedGroup.critical)
                {
                    hook.sendMessage("That group is already flagged as critical").setEphemeral(true).queue();
                    return;
                }
                hook.sendMessage("Flagged group as critical").setEphemeral(true).queue();
            }
            else
            {
                if (!watchedGroup.critical)
                {
                    hook.sendMessage("That group is already not flagged as critical").setEphemeral(true).queue();
                    return;
                }
                hook.sendMessage("Unflagged group as critical").setEphemeral(true).queue();
            }
            watchedGroup.critical = critical;
            ScarletDiscordCommands.this.discord.scarlet.watchedGroups.save();
        }
        @SlashCmd("set-silent")
        @Desc("Sets a group's silent status")
        public void setSilent(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId, @SlashOpt("_silent_R") boolean silent)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup == null)
            {
                hook.sendMessage("That group is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            if (silent)
            {
                if (watchedGroup.silent)
                {
                    hook.sendMessage("That group is already flagged as silent").setEphemeral(true).queue();
                    return;
                }
                hook.sendMessage("Flagged group as silent").setEphemeral(true).queue();
            }
            else
            {
                if (!watchedGroup.silent)
                {
                    hook.sendMessage("That group is already not flagged as silent").setEphemeral(true).queue();
                    return;
                }
                hook.sendMessage("Unflagged group as silent").setEphemeral(true).queue();
            }
            watchedGroup.silent = silent;
            ScarletDiscordCommands.this.discord.scarlet.watchedGroups.save();
        }
        @SlashCmd("set-type")
        @Desc("Sets a group's watch type")
        public void setType(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId, @SlashOpt("group-type") ScarletWatchedGroups.WatchedGroup.Type groupType)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup == null)
            {
                hook.sendMessage("That group is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            if (watchedGroup.type == groupType)
            {
                hook.sendMessage("That group is already marked as "+groupType).setEphemeral(true).queue();
                return;
            }
            watchedGroup.type = groupType;
            hook.sendMessage("Marking group as "+groupType).setEphemeral(true).queue();
            ScarletDiscordCommands.this.discord.scarlet.watchedGroups.save();
        }
        @SlashCmd("set-priority")
        @Desc("Sets a group's priority")
        public void setPriority(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId, @SlashOpt("_groupPriority_R") int groupPriority)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup == null)
            {
                hook.sendMessage("That group is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            watchedGroup.priority = groupPriority;
            hook.sendMessage("Setting group priority to "+groupPriority).setEphemeral(true).queue();
            ScarletDiscordCommands.this.discord.scarlet.watchedGroups.save();
        }
        @SlashCmd("set-message")
        @Desc("Sets a group's TTS announcement message")
        public void setMessage(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId, @SlashOpt("_message_R") String message)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup == null)
            {
                hook.sendMessage("That group is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            if (message == null)
            {
                if (watchedGroup.message == null)
                {
                    hook.sendMessage("That group already has no message").setEphemeral(true).queue();
                    return;
                }
                else
                {
                    hook.sendMessageFormat("Removing group's message (was `%s`)", watchedGroup.message).setEphemeral(true).queue();
                }
            }
            else
            {
                if (message.equals(watchedGroup.message))
                {
                    hook.sendMessage("That group's message is already exactly that").setEphemeral(true).queue();
                    return;
                }
                else
                {
                    hook.sendMessageFormat("Setting group TTS announcement to `%s` (was `%s`)", message, watchedGroup.message).setEphemeral(true).queue();
                }
            }
            watchedGroup.message = message;
            ScarletDiscordCommands.this.discord.scarlet.watchedGroups.save();
        }
        @SlashCmd("set-notes")
        @Desc("Sets a group's notes")
        public void setNotes(SlashCommandInteractionEvent event, @SlashOpt("vrchat-group") String groupId)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup == null)
            {
                event.reply("That group is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            event.replyModal(Modal.create("watched-group-set-notes:"+groupId, "Edit notes")
                .addComponents(Label.of("Notes", TextInput.create("notes", TextInputStyle.PARAGRAPH)
                    .setValue(MiscUtils.blank(watchedGroup.notes) ? null : watchedGroup.notes)
                    .setRequiredRange(0, 1024)
                    .build()))
                .build())
            .queue();
        }
        @SlashCmd("set-tags")
        @Desc("Sets a group's tags")
        public void setTags(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId, @SlashOpt("_groupTags_R") String groupTags)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup == null)
            {
                hook.sendMessage("That group is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            if (groupTags == null)
            {
                if (watchedGroup.tags.isEmpty())
                {
                    hook.sendMessage("That group already has no tags").setEphemeral(true).queue();
                    return;
                }
                else
                {
                    hook.sendMessageFormat("Removing group's tags (was `%s`)",
                            watchedGroup.tags.strings().stream().filter(Objects::nonNull).collect(Collectors.joining("`, `")))
                        .setEphemeral(true)
                        .queue();
                    watchedGroup.tags.clear();
                }
            }
            else
            {
                String[] newTags = Arrays.stream(groupTags.split("[,;/]")).map(String::trim).distinct().toArray(String[]::new);
                if (watchedGroup.tags.strings().equals(new HashSet<>(Arrays.asList(newTags))))
                {
                    hook.sendMessage("That group already has those exact tags").setEphemeral(true).queue();
                    return;
                }
                else
                {
                    hook.sendMessageFormat("Setting group tags to `%s` (was `%s`)",
                            Arrays.stream(newTags).filter(Objects::nonNull).collect(Collectors.joining("`, `")),
                            watchedGroup.tags.strings().stream().filter(Objects::nonNull).collect(Collectors.joining("`, `")))
                        .setEphemeral(true)
                        .queue();
                    watchedGroup.tags.clear().addAll(newTags);
                }
            }
            ScarletDiscordCommands.this.discord.scarlet.watchedGroups.save();
        }
        @SlashCmd("add-tag")
        @Desc("Adds a tag for a group")
        public void addTag(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId, @SlashOpt("_groupTag_R") String groupTag)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup == null)
            {
                hook.sendMessage("That group is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            String groupTag0 = groupTag.trim();
            if (watchedGroup.tags.isEmpty())
            {
                watchedGroup.tags.add(groupTag0);
                hook.sendMessageFormat("Added tag `%s` (was empty)", groupTag0).setEphemeral(true).queue();
            }
            else if (watchedGroup.tags.add(groupTag0))
            {
                hook.sendMessageFormat("Added tag `%s` (was `%s`)",
                        groupTag0,
                        watchedGroup.tags.strings().stream().filter(Objects::nonNull).collect(Collectors.joining("`, `")))
                    .setEphemeral(true)
                    .queue();
            }
            else
            {
                hook.sendMessage("That group already has that").setEphemeral(true).queue();
            }
            ScarletDiscordCommands.this.discord.scarlet.watchedGroups.save();
        }
        @SlashCmd("remove-tag")
        @Desc("Removes a tag from a group")
        public void removeTag(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-group") String groupId, @SlashOpt("_groupTag_R") String groupTag)
        {
            ScarletWatchedGroups.WatchedGroup watchedGroup = ScarletDiscordCommands.this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
            if (watchedGroup == null)
            {
                hook.sendMessage("That group is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatGroup(event, groupId);
            if (watchedGroup.tags.isEmpty())
            {
                hook.sendMessage("That group already has no tags").setEphemeral(true).queue();
                return;
            }
            if (watchedGroup.tags.remove(groupTag))
            {
                hook.sendMessageFormat("Removed group tag `%s`)", groupTag).setEphemeral(true).queue();
            }
            else
            {
                hook.sendMessage("That group doesn't have that tag").setEphemeral(true).queue();
                return;
            }
            ScarletDiscordCommands.this.discord.scarlet.watchedGroups.save();
        }
    }

    // ── Bulk remove-from-watch-list via dropdown ────────────────────────────────
    // A convenience for pruning large watch lists (e.g. deleted/banned avatars): posts
    // multi-select dropdowns of the watched entries by resolved name, and removing the
    // ticked ones in one action instead of one /...-remove per id. Discord caps a single
    // dropdown at 25 options and a message at 5 rows, so up to 125 entries are shown; if
    // there are more, remove some and run it again for the rest.
    static final int WATCH_REMOVE_PER_MENU = 25, WATCH_REMOVE_MAX_MENUS = 5;

    void emitWatchedRemoveMenu(InteractionHook hook, String kind, String plural, List<String> ids, Function<String, String> nameOf)
    {
        if (ids == null || ids.isEmpty())
        {
            hook.sendMessageFormat("There are no watched %s to remove.", plural).setEphemeral(true).queue();
            return;
        }
        // Stable, name-sorted order so the same entry stays in the same place run to run.
        Map<String, String> names = new HashMap<>();
        for (String id : ids)
        {
            String n = null;
            try { n = nameOf.apply(id); } catch (Throwable ignored) {}
            names.put(id, n != null && !n.trim().isEmpty() ? n.trim() : id);
        }
        ids.sort(java.util.Comparator.comparing(id -> names.get(id).toLowerCase(java.util.Locale.ROOT)));

        List<ActionRow> rows = new java.util.ArrayList<>();
        int cap = WATCH_REMOVE_PER_MENU * WATCH_REMOVE_MAX_MENUS;
        for (int menu = 0; menu < WATCH_REMOVE_MAX_MENUS && menu * WATCH_REMOVE_PER_MENU < ids.size(); menu++)
        {
            int start = menu * WATCH_REMOVE_PER_MENU, end = Math.min(start + WATCH_REMOVE_PER_MENU, ids.size());
            StringSelectMenu.Builder builder = StringSelectMenu.create("watched-remove:"+kind+":"+menu)
                .setPlaceholder(String.format("Remove watched %s (%d–%d of %d)", plural, start + 1, end, ids.size()))
                .setMinValues(0)
                .setMaxValues(end - start);
            for (int i = start; i < end; i++)
            {
                String id = ids.get(i);
                builder.addOption(MiscUtils.maybeEllipsis(100, names.get(id)), id, MiscUtils.maybeEllipsis(100, id));
            }
            rows.add(ActionRow.of(builder.build()));
        }
        String note = ids.size() > cap
            ? String.format("%nShowing the first %d of %d; remove some and run this again for the rest.", cap, ids.size())
            : "";
        hook.sendMessageFormat("Select watched %s to remove — they'll be deleted from the list immediately.%s", plural, note)
            .addComponents(rows)
            .setEphemeral(true)
            .queue();
    }

    boolean removeWatchedByKind(String kind, String id)
    {
        switch (kind)
        {
        case "avatar": return this.discord.scarlet.watchedAvatars.removeWatchedEntity(id);
        case "user":   return this.discord.scarlet.watchedUsers.removeWatchedEntity(id);
        case "group":  return this.discord.scarlet.watchedGroups.removeWatchedGroup(id);
        default:       return false;
        }
    }

    @StringSel("watched-remove")
    public void watchedRemoveSelect(StringSelectInteractionEvent event)
    {
        String kind = event.getSelectMenu().getCustomId().split(":")[1];
        List<String> selected = event.getValues();
        if (selected == null || selected.isEmpty())
        {
            event.deferEdit().queue();
            return;
        }
        int removed = 0;
        for (String id : selected)
            if (this.removeWatchedByKind(kind, id))
                removed++;
        String plural = "group".equals(kind) ? "groups" : kind + "s";
        event.reply(String.format("Removed %d watched %s from the list.", removed, plural)).setEphemeral(true).queue();
    }

    final Map<String, WatchedEntity_<?>> watchedEntityCommands = new HashMap<>();
    protected abstract class WatchedEntity_<E>
    {
        {
            ScarletDiscordCommands.this.watchedEntityCommands.put(this._singular(), this);
        }
        protected abstract String _singular();
        protected abstract String _plural();
        protected abstract ScarletWatchedEntities<E> _watchedEntities();
        protected abstract E _getEntity(String id, long minEpoch);
        protected abstract String _getEntityName(E entity);
        protected abstract boolean _isValidEntityId(String id);
        final Map<String, Command.Choice> userSf2lastEdited_entityId = new ConcurrentHashMap<>();
//        @SlashCmd("list")
//        @Desc("Lists all watched groups")
        protected void _list(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            MessageEmbed[] embeds = this._watchedEntities()
                .watchedEntities
                .values()
                .stream()
                .map($ -> $.embed(this._watchedEntities(), this._getEntity($.id, ScarletJsonCache.ALWAYS_PREFER_CACHED)).build())
                .toArray(MessageEmbed[]::new)
            ;
            
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }
        protected void _removeMenu(SlashCommandInteractionEvent event, InteractionHook hook)
        {
            ScarletDiscordCommands.this.emitWatchedRemoveMenu(hook, this._singular(), this._plural(),
                new java.util.ArrayList<>(this._watchedEntities().watchedEntities.keySet()),
                id ->
                {
                    E entity = this._getEntity(id, ScarletJsonCache.ALWAYS_PREFER_CACHED);
                    return entity == null ? null : this._getEntityName(entity);
                });
        }
//        @SlashCmd("export")
//        @Desc("Exports watched groups as a JSON file")
        protected void _export(SlashCommandInteractionEvent event, InteractionHook hook)
        {
            LOG.info("Exporting watched "+this._plural()+" JSON");
            hook.sendFiles(FileUpload.fromData(this._watchedEntities().watchedEntitiesFile)).setEphemeral(false).queue();
        }
//        @SlashCmd("import")
//        @Desc("Imports watched groups from an attached file")
        protected void _import_(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("import-file") Message.Attachment importedFile)
        {
            String fileName = importedFile.getFileName(),
                   attachmentUrl = importedFile.getUrl();
             
             if (fileName.endsWith(".csv"))
             {
                 LOG.info("Importing watched "+this._plural()+" legacy CSV from attachment: "+fileName);
                 try (Reader reader = new InputStreamReader(HttpURLInputStream.get(attachmentUrl, HttpURLInputStream.PUBLIC_ONLY)))
                 {
                     if (this._watchedEntities().importLegacyCSV(reader, true))
                     {
                         LOG.info("Successfully imported watched "+this._plural()+" legacy CSV");
                         hook.sendMessageFormat("Successfully imported watched "+this._plural()+" legacy CSV").setEphemeral(true).queue();
                     }
                     else
                     {
                         LOG.warn("Failed to import watched "+this._plural()+" legacy CSV with unknown reason");
                         hook.sendMessageFormat("Failed to import watched "+this._plural()+" legacy CSV with unknown reason").setEphemeral(true).queue();
                     }
                 }
                 catch (Exception ex)
                 {
                     LOG.error("Exception importing watched "+this._plural()+" legacy CSV from attachment: "+fileName, ex);
                     hook.sendMessageFormat("Exception while importing %s: %s", fileName, ex).setEphemeral(true).queue();
                 }
             }
             else if (fileName.endsWith(".json"))
             {
                 LOG.info("Importing watched "+this._plural()+" JSON from attachment: "+fileName);
                 try (Reader reader = new InputStreamReader(HttpURLInputStream.get(attachmentUrl, HttpURLInputStream.PUBLIC_ONLY)))
                 {
                     if (this._watchedEntities().importJson(reader, true))
                     {
                         LOG.info("Successfully imported watched "+this._plural()+" JSON");
                         hook.sendMessageFormat("Successfully imported watched "+this._plural()+" JSON").setEphemeral(true).queue();
                     }
                     else
                     {
                         LOG.warn("Failed to import watched "+this._plural()+" JSON with unknown reason");
                         hook.sendMessageFormat("Failed to import watched "+this._plural()+" JSON with unknown reason").setEphemeral(true).queue();
                     }
                 }
                 catch (Exception ex)
                 {
                     LOG.error("Exception importing watched "+this._plural()+" JSON from attachment: "+fileName, ex);
                     hook.sendMessageFormat("Exception while importing %s: %s", fileName, ex).setEphemeral(true).queue();
                 }
             }
             else
             {
                 LOG.warn("Skipping attachment: "+fileName);
                 hook.sendMessageFormat("File '%s' is not importable.", fileName).setEphemeral(true).queue();
             }
        }
        void _vrchatEntity(CommandAutoCompleteInteractionEvent event) {
            AutoCompleteCallbackAction action = event.replyChoices();
            Command.Choice entityChoice = this.userSf2lastEdited_entityId.get(event.getUser().getId());
            long choicesLeft = 25L;
            if (entityChoice != null)
            {
                action.addChoices(entityChoice);
                choicesLeft--;
            }
            String typing = event.getFocusedOption().getValue();
            if (typing != null && !(typing = typing.trim()).isEmpty())
            {
                if (this._isValidEntityId(typing))
                {
                    if (this._watchedEntities().getWatchedEntity(typing) != null)
                    {
                        E entity = this._getEntity(typing, ScarletJsonCache.ALWAYS_PREFER_CACHED);
                        String name;
                        if (entity != null && (name = this._getEntityName(entity)) != null)
                        {
                            action.addChoice(name, typing);
                        }
                        else
                        {
                            action.addChoiceStrings(typing);
                        }
                        choicesLeft--;
                    }
                }
                else
                {
                    action.addChoices(this._watchedEntities()
                        .watchedEntities
                        .keySet()
                        .stream()
                        .map(id -> {
                            E entity0 = this._getEntity(id, ScarletJsonCache.ALWAYS_PREFER_CACHED);
                            return new Command.Choice(entity0 != null ? this._getEntityName(entity0) : id, id);
                        })
                        .sorted(DInteractions.choicesByLevenshtein(typing))
                        .limit(choicesLeft)
                        .collect(Collectors.toList())
                    );
                }
            }
            else
            {
                action.addChoices(this._watchedEntities()
                    .watchedEntities
                    .keySet()
                    .stream()
                    .map(id -> {
                        E entity0 = this._getEntity(id, ScarletJsonCache.ALWAYS_PREFER_CACHED);
                        return new Command.Choice(entity0 != null ? this._getEntityName(entity0) : id, id);
                    })
                    .limit(choicesLeft)
                    .collect(Collectors.toList())
                );
            }
            action.queue();
        }
        E vrchatEntity(SlashCommandInteractionEvent event, String entityId)
        {
            if (entityId != null && !(entityId = entityId.trim()).isEmpty())
            {
                E entity = this._getEntity(entityId, ScarletJsonCache.ALWAYS_PREFER_CACHED);
                this.userSf2lastEdited_entityId.put(event.getUser().getId(), new Command.Choice(entity != null ? this._getEntityName(entity) : entityId, entityId));
                return entity;
            }
            return null;
        }
        
//        @SlashCmd("add")
//        @Desc("Exports watched groups as a JSON file")
        protected void _add(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId,
            @SlashOpt("entity-type") ScarletWatchedEntities.WatchedEntity.Type entityType,
            @SlashOpt("entity-tags") String entityTags,
            @SlashOpt("entity-priority") int entityPriority,
            @SlashOpt("message") String message)
        {
            ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
            if (watchedEntity != null)
            {
                hook.sendMessage("That "+this._singular()+" is already watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatEntity(event, entityId);
            E entity = this._getEntity(entityId, ScarletJsonCache.ALWAYS_FETCH);
            if (entity == null)
            {
                hook.sendMessage("That "+this._singular()+" doesn't seem to exist").setEphemeral(true).queue();
                return;
            }
            watchedEntity = new ScarletWatchedEntities.WatchedEntity();
            watchedEntity.id = entityId;
            watchedEntity.type = entityType;
            if (entityTags != null)
                watchedEntity.tags.clear().addAll(Arrays.stream(entityTags.split("[,;/]")).map(String::trim).toArray(String[]::new));
            if (message != null)
                watchedEntity.message = message;
            watchedEntity.priority = entityPriority;
            this._watchedEntities().addWatchedEntity(entityId, watchedEntity);
            hook.sendMessageFormat("Added %s [%s]("+VrcWeb.Home.HOME+"/%s/%s)", this._singular(), this._getEntityName(entity), this._singular(), entityId).setEphemeral(true).queue();
        }
//        @SlashCmd("remove")
//        @Desc("Removes a watched group")
        protected void _remove(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId)
        {
            if (this._watchedEntities().removeWatchedEntity(entityId))
            {
                E entity = this._getEntity(entityId, ScarletJsonCache.ALWAYS_PREFER_CACHED);
                hook.sendMessageFormat("Removed %s [%s]("+VrcWeb.Home.HOME+"/%s/%s)", this._singular(), entity == null ? entityId : this._getEntityName(entity), this._singular(), entityId).setEphemeral(true).queue();
            }
            else
            {
                hook.sendMessage("That "+this._singular()+" is not watched").setEphemeral(true).queue();
            }
        }
//        @SlashCmd("view")
//        @Desc("Views a group's watch information")
        protected void _view(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId)
        {
            ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
            if (watchedEntity == null)
            {
                hook.sendMessage("That "+this._singular()+" is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatEntity(event, entityId);
            E entity = this._getEntity(entityId, ScarletJsonCache.ALWAYS_PREFER_CACHED);
            hook.sendMessageEmbeds(watchedEntity.embed(this._watchedEntities(), entity).build()).setEphemeral(true).queue();
        }
//        @SlashCmd("set-critical")
//        @Desc("Sets a group's critical status")
        protected void _setCritical(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId, @SlashOpt("_critical_R") boolean critical)
        {
            ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
            if (watchedEntity == null)
            {
                hook.sendMessage("That "+this._singular()+" is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatEntity(event, entityId);
            if (critical)
            {
                if (watchedEntity.critical)
                {
                    hook.sendMessage("That "+this._singular()+" is already flagged as critical").setEphemeral(true).queue();
                    return;
                }
                hook.sendMessage("Flagged "+this._singular()+" as critical").setEphemeral(true).queue();
            }
            else
            {
                if (!watchedEntity.critical)
                {
                    hook.sendMessage("That "+this._singular()+" is already not flagged as critical").setEphemeral(true).queue();
                    return;
                }
                hook.sendMessage("Unflagged "+this._singular()+" as critical").setEphemeral(true).queue();
            }
            watchedEntity.critical = critical;
            this._watchedEntities().save();
        }
//        @SlashCmd("set-silent")
//        @Desc("Sets a group's silent status")
        protected void _setSilent(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId, @SlashOpt("_silent_R") boolean silent)
        {
            ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
            if (watchedEntity == null)
            {
                hook.sendMessage("That "+this._singular()+" is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatEntity(event, entityId);
            if (silent)
            {
                if (watchedEntity.silent)
                {
                    hook.sendMessage("That "+this._singular()+" is already flagged as silent").setEphemeral(true).queue();
                    return;
                }
                hook.sendMessage("Flagged "+this._singular()+" as silent").setEphemeral(true).queue();
            }
            else
            {
                if (!watchedEntity.silent)
                {
                    hook.sendMessage("That "+this._singular()+" is already not flagged as silent").setEphemeral(true).queue();
                    return;
                }
                hook.sendMessage("Unflagged "+this._singular()+" as silent").setEphemeral(true).queue();
            }
            watchedEntity.silent = silent;
            this._watchedEntities().save();
        }
//        @SlashCmd("set-type")
//        @Desc("Sets a group's watch type")
        protected void _setType(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId, @SlashOpt("entity-type") ScarletWatchedEntities.WatchedEntity.Type entityType)
        {
            ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
            if (watchedEntity == null)
            {
                hook.sendMessage("That "+this._singular()+" is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatEntity(event, entityId);
            if (watchedEntity.type == entityType)
            {
                hook.sendMessage("That "+this._singular()+" is already marked as "+entityType).setEphemeral(true).queue();
                return;
            }
            watchedEntity.type = entityType;
            hook.sendMessage("Marking "+this._singular()+" as "+entityType).setEphemeral(true).queue();
            this._watchedEntities().save();
        }
//        @SlashCmd("set-priority")
//        @Desc("Sets a group's priority")
        protected void _setPriority(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId, @SlashOpt("_entityPriority_R") int entityPriority)
        {
            ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
            if (watchedEntity == null)
            {
                hook.sendMessage("That "+this._singular()+" is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatEntity(event, entityId);
            watchedEntity.priority = entityPriority;
            hook.sendMessage("Setting "+this._singular()+" priority to "+entityPriority).setEphemeral(true).queue();
            this._watchedEntities().save();
        }
//        @SlashCmd("set-message")
//        @Desc("Sets a group's TTS announcement message")
        protected void _setMessage(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId, @SlashOpt("_message_R") String message)
        {
            ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
            if (watchedEntity == null)
            {
                hook.sendMessage("That "+this._singular()+" is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatEntity(event, entityId);
            if (message == null)
            {
                if (watchedEntity.message == null)
                {
                    hook.sendMessage("That "+this._singular()+" already has no message").setEphemeral(true).queue();
                    return;
                }
                else
                {
                    hook.sendMessageFormat("Removing "+this._singular()+"'s message (was `%s`)", watchedEntity.message).setEphemeral(true).queue();
                }
            }
            else
            {
                if (message.equals(watchedEntity.message))
                {
                    hook.sendMessage("That "+this._singular()+"'s message is already exactly that").setEphemeral(true).queue();
                    return;
                }
                else
                {
                    hook.sendMessageFormat("Setting "+this._singular()+" TTS announcement to `%s` (was `%s`)", message, watchedEntity.message).setEphemeral(true).queue();
                }
            }
            watchedEntity.message = message;
            this._watchedEntities().save();
        }
//      @SlashCmd("set-notes")
//      @Desc("Sets a group's notes")
      protected void _setNotes(SlashCommandInteractionEvent event, @SlashOpt("vrchat-entity") String entityId)
      {
          ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
          if (watchedEntity == null)
          {
              event.reply("That "+this._singular()+" is not watched").setEphemeral(true).queue();
              return;
          }
          this.vrchatEntity(event, entityId);
          event.replyModal(Modal.create("watched-entity-set-notes:"+this._singular()+":"+entityId, "Edit notes")
              .addComponents(Label.of("Notes", TextInput.create("notes", TextInputStyle.PARAGRAPH)
                  .setValue(MiscUtils.blank(watchedEntity.notes) ? null : watchedEntity.notes)
                  .setRequiredRange(0, 1024)
                  .build()))
              .build())
          .queue();
      }
      protected void _setNotes(ModalInteractionEvent event, @SlashOpt("vrchat-entity") String entityId)
      {
          ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
          if (watchedEntity == null)
          {
              event.reply("That "+this._singular()+" is not watched").setEphemeral(true).queue();
              return;
          }
          event.reply("Set notes for "+this._singular()).setEphemeral(true).queue();
          watchedEntity.notes = event.getValue("notes").getAsString();
          this._watchedEntities().save();
      }
//        @SlashCmd("set-tags")
//        @Desc("Sets a group's tags")
        protected void _setTags(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId, @SlashOpt("_entityTags_R") String entityTags)
        {
            ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
            if (watchedEntity == null)
            {
                hook.sendMessage("That "+this._singular()+" is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatEntity(event, entityId);
            if (entityTags == null)
            {
                if (watchedEntity.tags.isEmpty())
                {
                    hook.sendMessage("That "+this._singular()+" already has no tags").setEphemeral(true).queue();
                    return;
                }
                else
                {
                    hook.sendMessageFormat("Removing "+this._singular()+"'s tags (was `%s`)",
                            watchedEntity.tags.strings().stream().filter(Objects::nonNull).collect(Collectors.joining("`, `")))
                        .setEphemeral(true)
                        .queue();
                    watchedEntity.tags.clear();
                }
            }
            else
            {
                String[] newTags = Arrays.stream(entityTags.split("[,;/]")).map(String::trim).distinct().toArray(String[]::new);
                if (watchedEntity.tags.strings().equals(new HashSet<>(Arrays.asList(newTags))))
                {
                    hook.sendMessage("That "+this._singular()+" already has those exact tags").setEphemeral(true).queue();
                    return;
                }
                else
                {
                    hook.sendMessageFormat("Setting "+this._singular()+" tags to `%s` (was `%s`)",
                            Arrays.stream(newTags).filter(Objects::nonNull).collect(Collectors.joining("`, `")),
                            watchedEntity.tags.strings().stream().filter(Objects::nonNull).collect(Collectors.joining("`, `")))
                        .setEphemeral(true)
                        .queue();
                    watchedEntity.tags.clear().addAll(newTags);
                }
            }
            this._watchedEntities().save();
        }
//        @SlashCmd("add-tag")
//        @Desc("Adds a tag for a group")
        protected void _addTag(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId, @SlashOpt("_entityTag_R") String entityTag)
        {
            ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
            if (watchedEntity == null)
            {
                hook.sendMessage("That "+this._singular()+" is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatEntity(event, entityId);
            String entityTag0 = entityTag.trim();
            if (watchedEntity.tags.isEmpty())
            {
                watchedEntity.tags.add(entityTag0);
                hook.sendMessageFormat("Added tag `%s` (was empty)", entityTag0).setEphemeral(true).queue();
            }
            else if (watchedEntity.tags.add(entityTag0))
            {
                hook.sendMessageFormat("Added tag `%s` (was `%s`)",
                        entityTag0,
                        watchedEntity.tags.strings().stream().filter(Objects::nonNull).collect(Collectors.joining("`, `")))
                    .setEphemeral(true)
                    .queue();
            }
            else
            {
                hook.sendMessage("That "+this._singular()+" already has that tag").setEphemeral(true).queue();
            }
            this._watchedEntities().save();
        }
//        @SlashCmd("remove-tag")
//        @Desc("Removes a tag from a group")
        protected void _removeTag(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-entity") String entityId, @SlashOpt("_entityTag_R") String entityTag)
        {
            ScarletWatchedEntities.WatchedEntity watchedEntity = this._watchedEntities().getWatchedEntity(entityId);
            if (watchedEntity == null)
            {
                hook.sendMessage("That "+this._singular()+" is not watched").setEphemeral(true).queue();
                return;
            }
            this.vrchatEntity(event, entityId);
            if (watchedEntity.tags.isEmpty())
            {
                hook.sendMessage("That "+this._singular()+" already has no tags").setEphemeral(true).queue();
                return;
            }
            if (watchedEntity.tags.remove(entityTag))
            {
                hook.sendMessageFormat("Removed "+this._singular()+" tag `%s`)", entityTag).setEphemeral(true).queue();
            }
            else
            {
                hook.sendMessage("That "+this._singular()+" doesn't have that tag").setEphemeral(true).queue();
                return;
            }
            this._watchedEntities().save();
        }
    }

    // watched-user

    @SlashCmd("watched-user")
    @Desc("Configures watched users")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class WatchedUser_ extends WatchedEntity_<User>
    {
        @Override
        protected String _singular()
        { return "user"; }
        @Override
        protected String _plural()
        { return "users"; }
        @Override
        protected ScarletWatchedEntities<User> _watchedEntities()
        { return ScarletDiscordCommands.this.discord.scarlet.watchedUsers; }
        @Override
        protected User _getEntity(String id, long minEpoch)
        { return ScarletDiscordCommands.this.discord.scarlet.vrc.getUser(id, minEpoch); }
        @Override
        protected String _getEntityName(User entity)
        { return entity.getDisplayName(); }
        @Override
        protected boolean _isValidEntityId(String id)
        { return VrcIds.id_user.matcher(id).matches(); }
        @SlashCmd("list")
        @Desc("Lists all watched users")
        public void list(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("entries-per-page") int entriesPerPage)
        { super._list(event, hook, entriesPerPage); }
        @SlashCmd("remove-menu")
        @Desc("Remove watched users from a dropdown of their names")
        public void removeMenu(SlashCommandInteractionEvent event, InteractionHook hook)
        { super._removeMenu(event, hook); }
        @SlashCmd("export")
        @Desc("Exports watched users as a JSON file")
        public void export(SlashCommandInteractionEvent event, InteractionHook hook)
        { super._export(event, hook); }
        @SlashCmd("import")
        @Desc("Imports watched users from an attached file")
        public void import_(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("import-file") Message.Attachment importedFile)
        { super._import_(event, hook, importedFile); }
        public final SlashOption<String> _vrchatUser = SlashOption.ofString("vrchat-user", "The VRChat user id (usr_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX)", true, null, VrcIds::resolveUserId, true, this::_vrchatEntity);
        @SlashCmd("add")
        @Desc("Adds a watched user")
        public void add(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId,
            @SlashOpt("entity-type") ScarletWatchedEntities.WatchedEntity.Type entityType,
            @SlashOpt("entity-tags") String entityTags,
            @SlashOpt("entity-priority") int entityPriority,
            @SlashOpt("entity-message") String message)
        { super._add(event, hook, userId, entityType, entityTags, entityPriority, message); }
        @SlashCmd("remove")
        @Desc("Removes a watched user")
        public void remove(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId)
        { super._remove(event, hook, userId); }
        @SlashCmd("view")
        @Desc("Views a user's watch information")
        public void view(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId)
        { super._view(event, hook, userId); }
        @SlashCmd("set-critical")
        @Desc("Sets a user's critical status")
        public void setCritical(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId, @Required@SlashOpt("entity-critical") boolean critical)
        { super._setCritical(event, hook, userId, critical); }
        @SlashCmd("set-silent")
        @Desc("Sets a user's silent status")
        public void setSilent(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId, @Required@SlashOpt("entity-silent") boolean silent)
        { super._setSilent(event, hook, userId, silent); }
        @SlashCmd("set-type")
        @Desc("Sets a user's watch type")
        public void setType(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId, @Required@SlashOpt("entity-type") ScarletWatchedEntities.WatchedEntity.Type entityType)
        { super._setType(event, hook, userId, entityType); }
        @SlashCmd("set-priority")
        @Desc("Sets a user's priority")
        public void setPriority(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId, @Required@SlashOpt("entity-priority") int entityPriority)
        { super._setPriority(event, hook, userId, entityPriority); }
        @SlashCmd("set-message")
        @Desc("Sets a user's TTS announcement message")
        public void setMessage(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId, @Required@SlashOpt("entity-message") String message)
        { super._setMessage(event, hook, userId, message); }
        @SlashCmd("set-notes")
        @Desc("Sets a user's notes")
        public void setNotes(SlashCommandInteractionEvent event, @SlashOpt("vrchat-user") String userId)
        { super._setNotes(event, userId); }
        @SlashCmd("set-tags")
        @Desc("Sets a user's tags")
        public void setTags(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId, @Required@SlashOpt("entity-tags") String entityTags)
        { super._setTags(event, hook, userId, entityTags); }
        @SlashCmd("add-tag")
        @Desc("Adds a tag for a user")
        public void addTag(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId, @Required@SlashOpt("entity-tag") String entityTag)
        { super._addTag(event, hook, userId, entityTag); }
        @SlashCmd("remove-tag")
        @Desc("Removes a tag from a user")
        public void removeTag(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") String userId, @Required@SlashOpt("entity-tag") String entityTag)
        { super._removeTag(event, hook, userId, entityTag); }
    }

    // watched-avatar

    @FeatureGate("watched.avatars.enabled")
    @SlashCmd("watched-avatar")
    @Desc("Configures watched avatars")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class WatchedAvatar_ extends WatchedEntity_<Avatar>
    {
        @Override
        protected String _singular()
        { return "avatar"; }
        @Override
        protected String _plural()
        { return "avatars"; }
        @Override
        protected ScarletWatchedEntities<Avatar> _watchedEntities()
        { return ScarletDiscordCommands.this.discord.scarlet.watchedAvatars; }
        @Override
        protected Avatar _getEntity(String id, long minEpoch)
        { return ScarletDiscordCommands.this.discord.scarlet.vrc.getAvatar(id, minEpoch); }
        @Override
        protected String _getEntityName(Avatar entity)
        { return entity.getName(); }
        @Override
        protected boolean _isValidEntityId(String id)
        { return VrcIds.id_avatar.matcher(id).matches(); }
        @SlashCmd("list")
        @Desc("Lists all watched avatars")
        public void list(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("entries-per-page") int entriesPerPage)
        { super._list(event, hook, entriesPerPage); }
        @SlashCmd("remove-menu")
        @Desc("Remove watched avatars from a dropdown of their names")
        public void removeMenu(SlashCommandInteractionEvent event, InteractionHook hook)
        { super._removeMenu(event, hook); }
        @SlashCmd("export")
        @Desc("Exports watched avatars as a JSON file")
        public void export(SlashCommandInteractionEvent event, InteractionHook hook)
        { super._export(event, hook); }
        @SlashCmd("import")
        @Desc("Imports watched avatars from an attached file")
        public void import_(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("import-file") Message.Attachment importedFile)
        { super._import_(event, hook, importedFile); }
        public final SlashOption<String> _vrchatAvatar = SlashOption.ofString("vrchat-avatar", "The VRChat avatar id (avtr_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX)", true, null, VrcIds::resolveAvatarId, true, this::_vrchatEntity);
        @SlashCmd("add")
        @Desc("Adds a watched avatar")
        public void add(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId,
            @SlashOpt("entity-type") ScarletWatchedEntities.WatchedEntity.Type entityType,
            @SlashOpt("entity-tags") String entityTags,
            @SlashOpt("entity-priority") int entityPriority,
            @SlashOpt("entity-message") String message)
        { super._add(event, hook, avatarId, entityType, entityTags, entityPriority, message); }
        @SlashCmd("remove")
        @Desc("Removes a watched avatar")
        public void remove(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId)
        { super._remove(event, hook, avatarId); }
        @SlashCmd("view")
        @Desc("Views an avatar's watch information")
        public void view(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId)
        { super._view(event, hook, avatarId); }
        @SlashCmd("set-critical")
        @Desc("Sets an avatar's critical status")
        public void setCritical(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId, @Required@SlashOpt("entity-critical") boolean critical)
        { super._setCritical(event, hook, avatarId, critical); }
        @SlashCmd("set-silent")
        @Desc("Sets an avatar's silent status")
        public void setSilent(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId, @Required@SlashOpt("entity-silent") boolean silent)
        { super._setSilent(event, hook, avatarId, silent); }
        @SlashCmd("set-type")
        @Desc("Sets an avatar's watch type")
        public void setType(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId, @Required@SlashOpt("entity-type") ScarletWatchedEntities.WatchedEntity.Type entityType)
        { super._setType(event, hook, avatarId, entityType); }
        @SlashCmd("set-priority")
        @Desc("Sets an avatar's priority")
        public void setPriority(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId, @Required@SlashOpt("entity-priority") int entityPriority)
        { super._setPriority(event, hook, avatarId, entityPriority); }
        @SlashCmd("set-message")
        @Desc("Sets an avatar's TTS announcement message")
        public void setMessage(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId, @Required@SlashOpt("entity-message") String message)
        { super._setMessage(event, hook, avatarId, message); }
        @SlashCmd("set-notes")
        @Desc("Sets an avatar's notes")
        public void setNotes(SlashCommandInteractionEvent event, @SlashOpt("vrchat-avatar") String avatarId)
        { super._setNotes(event, avatarId); }
        @SlashCmd("set-tags")
        @Desc("Sets an avatar's tags")
        public void setTags(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId, @Required@SlashOpt("entity-tags") String entityTags)
        { super._setTags(event, hook, avatarId, entityTags); }
        @SlashCmd("add-tag")
        @Desc("Adds a tag for an avatar")
        public void addTag(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId, @Required@SlashOpt("entity-tag") String entityTag)
        { super._addTag(event, hook, avatarId, entityTag); }
        @SlashCmd("remove-tag")
        @Desc("Removes a tag from an avatar")
        public void removeTag(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-avatar") String avatarId, @Required@SlashOpt("entity-tag") String entityTag)
        { super._removeTag(event, hook, avatarId, entityTag); }
    }
    
    // staff-list

    @SlashCmd("staff-list")
    @Desc("Configures the staff list")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class StaffList extends _StaffList
    {
        @Override
        protected String _infoName()
        {
            return "staff";
        }
        @Override
        protected String[] _getIds()
        {
            return ScarletDiscordCommands.this.discord.scarlet.staffList.getStaffIds();
        }
        @Override
        protected boolean _addId(String vrcId)
        {
            return ScarletDiscordCommands.this.discord.scarlet.staffList.addStaffId(vrcId);
        }
        @Override
        protected boolean _removeId(String vrcId)
        {
            return ScarletDiscordCommands.this.discord.scarlet.staffList.removeStaffId(vrcId);
        }
        @SlashCmd("list")
        @Desc("Lists all staff users")
        public void list(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            this._list(event, hook, entriesPerPage);
        }
        @SlashCmd("add")
        @Desc("Adds a user to the staff list")
        public void add(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser, @SlashOpt("discord-user") Member discordUser, @SlashOpt("_vrchatRoleOpt") io.github.vrchatapi.model.GroupRole vrchatRoleOpt)
        {
            this._add(event, hook, vrchatUser, discordUser, vrchatRoleOpt);
        }
        @SlashCmd("delete")
        @Desc("Removes a user from the staff list")
        public void remove(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser, @SlashOpt("_vrchatRoleOpt") io.github.vrchatapi.model.GroupRole vrchatRoleOpt)
        {
            this._remove(event, hook, vrchatUser, vrchatRoleOpt);
        }
    }

    abstract class _StaffList
    {
        protected abstract String _infoName();
        protected abstract String[] _getIds();
        protected abstract boolean _addId(String vrcId);
        protected abstract boolean _removeId(String vrcId);
        protected void _list(SlashCommandInteractionEvent event, InteractionHook hook, int entriesPerPage)
        {
            long within1day = System.currentTimeMillis() - 86400_000L;
            List<GroupRole> roleList = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupRoles(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId);
            Map<String, GroupRole> roles = roleList == null || roleList.isEmpty() ? Collections.emptyMap() : roleList.stream().collect(Collectors.toMap(GroupRole::getId, Function.identity()));
            MessageEmbed[] embeds = Arrays.stream(this._getIds())
                .filter(id -> "secret staff".equals(this._infoName()) || !ScarletDiscordCommands.this.discord.scarlet.secretStaffList.isSecretStaffId(id))
                .map($ -> {
                    User sc = ScarletDiscordCommands.this.discord.scarlet.vrc.getUser($, within1day);
                    GroupMember member = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupMembership(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, $);
                    ScarletData.UserMetadata userMeta = ScarletDiscordCommands.this.discord.scarlet.data.userMetadata($);
                    EmbedBuilder builder = new EmbedBuilder();
                    builder.setTitle(sc == null ? $ : sc.getDisplayName(), VrcWeb.Home.user($));
                    if (sc != null)
                    {
                        String thumb = ScarletDiscordCommands.this.discord.scarlet.vrc.getUserImageUrlOrNull(sc.getId());
                        if (thumb == null || thumb.trim().isEmpty())
                            thumb = null;
                        builder.setThumbnail(thumb);
                        
                        String statusDesc = sc.getStatusDescription();
                        if (statusDesc == null || statusDesc.trim().isEmpty())
                            statusDesc = null;
                        builder.setDescription(statusDesc);
                    }
                    if (member != null)
                    {
                        if (member.getJoinedAt() != null)
                        {
                            String epochJoined = Long.toUnsignedString(member.getJoinedAt().toEpochSecond());
                            builder.addField("Joined", "<t:"+epochJoined+":D> (<t:"+epochJoined+":R>)", false);
                        }
                        if (member.getRoleIds() != null && !member.getRoleIds().isEmpty())
                        {
                            builder.addField("Roles", member.getRoleIds().stream().map(roles::get).filter(Objects::nonNull).map(GroupRole::getName).collect(Collectors.joining(", ")), false);
                        }
                    }
                    if (userMeta != null && userMeta.userSnowflake != null)
                    {
                        builder.addField("Linked Discord", "<@"+userMeta.userSnowflake+">)", false);
                    }
                    return builder.build();
                })
                .toArray(MessageEmbed[]::new)
            ;
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }
        protected void _add(SlashCommandInteractionEvent event, InteractionHook hook, io.github.vrchatapi.model.User vrchatUser, Member discordUser, io.github.vrchatapi.model.GroupRole vrchatRoleOpt)
        {
            String vrcId = VrcIds.getAsString_user(event.getOption("vrchat-user"));
            if (vrchatUser == null)
            {
                hook.sendMessageFormat("No VRChat user found with id %s", vrcId).setEphemeral(true).queue();
                return;
            }
            
            boolean added;
            try { added = this._addId(vrcId); }
            catch (ScarletSecretStaffList.PersistenceException ex)
            {
                hook.sendMessage("The secret-staff list could not be saved. No staff-list, account-link, or VRChat-role changes were applied.").setEphemeral(true).queue();
                return;
            }
            if (!added)
            {
                hook.sendMessageFormat("VRChat user [%s](%s) is already on the "+this._infoName()+" list", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId)).setEphemeral(true).queue();
            }
            else
            {
                LOG.info(String.format("Adding VRChat user %s (%s) to the "+this._infoName()+" list", vrchatUser.getDisplayName(), vrcId));
                hook.sendMessageFormat("Adding VRChat user [%s](%s) to the "+this._infoName()+" list", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId)).setEphemeral(true).queue();
            }

            if (discordUser != null)
            {
                ScarletDiscordCommands.this.discord.scarlet.data.linkIdToSnowflake(vrcId, discordUser.getId());
                LOG.info(String.format("Linking VRChat user %s (%s) to Discord user %s (<@%s>)", vrchatUser.getDisplayName(), vrcId, discordUser.getEffectiveName(), discordUser.getId()));
                hook.sendMessageFormat("Associating %s with VRChat user [%s](%s)", discordUser.getEffectiveName(), vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId)).setEphemeral(true).queue();
            }
            
            if (vrchatRoleOpt == null)
                return;
            GroupMember glm = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupMembership(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrcId);
            if (glm != null)
            {
                List<String> roleIds = glm.getRoleIds();
                if (roleIds != null && roleIds.contains(vrchatRoleOpt.getId()))
                {
                    hook.sendMessageFormat("VRChat user [%s](%s) is already has the role [%s](%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId), vrchatRoleOpt.getName(), VrcWeb.Home.groupSettingsRoles(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatRoleOpt.getId())).setEphemeral(true).queue();
                    return;
                }
            }
            
            ScarletDiscordCommands.this.discord.scarlet.vrc.addGroupRole(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrcId, vrchatRoleOpt.getId());
            hook.sendMessageFormat("Adding the role [%s](%s) to [%s](%s)", vrchatRoleOpt.getName(), VrcWeb.Home.groupSettingsRoles(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatRoleOpt.getId()), vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId)).setEphemeral(true).queue();
        }
        protected void _remove(SlashCommandInteractionEvent event, InteractionHook hook, io.github.vrchatapi.model.User vrchatUser, io.github.vrchatapi.model.GroupRole vrchatRoleOpt)
        {
            String vrcId = VrcIds.getAsString_user(event.getOption("vrchat-user"));
            String prefix = vrchatUser != null ? "" : ("No VRChat user found with id %s"+vrcId+"\n");
            String displayName = vrchatUser != null ? vrchatUser.getDisplayName() : vrcId;
            
            boolean removed;
            try { removed = this._removeId(vrcId); }
            catch (ScarletSecretStaffList.PersistenceException ex)
            {
                hook.sendMessage("The secret-staff list could not be saved. No staff-list or VRChat-role changes were applied.").setEphemeral(true).queue();
                return;
            }
            if (!removed)
            {
                hook.sendMessageFormat("%sVRChat user [%s](%s) is not on the "+this._infoName()+" list", prefix, displayName, VrcWeb.Home.user(vrcId)).setEphemeral(true).queue();
            }
            else
            {
                LOG.info(String.format("Removing VRChat user %s (%s) from the "+this._infoName()+" list", displayName, vrcId));
                hook.sendMessageFormat("Removing VRChat user [%s](%s) from the "+this._infoName()+" list", displayName, VrcWeb.Home.user(vrcId)).setEphemeral(true).queue();
            }

            if (vrchatRoleOpt == null)
                return;
            GroupMember glm = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupMembership(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrcId);
            if (glm != null)
            {
                List<String> roleIds = glm.getRoleIds();
                if (roleIds != null && !roleIds.contains(vrchatRoleOpt.getId()))
                {
                    hook.sendMessageFormat("VRChat user [%s](%s) is already lacks the role [%s](%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId), vrchatRoleOpt.getName(), VrcWeb.Home.groupSettingsRoles(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatRoleOpt.getId())).setEphemeral(true).queue();
                    return;
                }
            }
            
            ScarletDiscordCommands.this.discord.scarlet.vrc.removeGroupRole(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrcId, vrchatRoleOpt.getId());
            hook.sendMessageFormat("Removing the role [%s](%s) from [%s](%s)", vrchatRoleOpt.getName(), VrcWeb.Home.groupSettingsRoles(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatRoleOpt.getId()), vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId)).setEphemeral(true).queue();
        }
    }

    // secret-staff-list

    @SlashCmd("secret-staff-list")
    @Desc("Configures the secret staff list")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class SecretStaffList extends _StaffList
    {
        @Override
        protected String _infoName()
        {
            return "secret staff";
        }
        @Override
        protected String[] _getIds()
        {
            return ScarletDiscordCommands.this.discord.scarlet.secretStaffList.getSecretStaffIds();
        }
        @Override
        protected boolean _addId(String vrcId)
        {
            return ScarletDiscordCommands.this.discord.scarlet.secretStaffList.addSecretStaffId(vrcId);
        }
        @Override
        protected boolean _removeId(String vrcId)
        {
            return ScarletDiscordCommands.this.discord.scarlet.secretStaffList.removeSecretStaffId(vrcId);
        }
        @SlashCmd("list")
        @Desc("Lists all secret staff users")
        @Ephemeral
        public void list(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            this._list(event, hook, entriesPerPage);
        }
        @SlashCmd("add")
        @Desc("Adds a user to the secret staff list")
        public void add(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser, @SlashOpt("discord-user") Member discordUser, @SlashOpt("_vrchatRoleOpt") io.github.vrchatapi.model.GroupRole vrchatRoleOpt)
        {
            this._add(event, hook, vrchatUser, discordUser, vrchatRoleOpt);
        }
        @SlashCmd("delete")
        @Desc("Removes a user from the secret staff list")
        public void remove(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser, @SlashOpt("_vrchatRoleOpt") io.github.vrchatapi.model.GroupRole vrchatRoleOpt)
        {
            this._remove(event, hook, vrchatUser, vrchatRoleOpt);
        }
    }

    // vrchat-user-info

    @SlashCmd("vrchat-user-info")
    @Desc("Lists internal and audit information for a specific VRChat user")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    @Ephemeral
    public void vrchatUserInfo(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser) throws Exception
    {
        String vrcId = VrcIds.getAsString_user(event.getOption("vrchat-user"));
        if (vrchatUser == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcId).setEphemeral(true).queue();
            return;
        }

        ScarletData.UserMetadata userMeta = this.discord.scarlet.data.userMetadata(vrcId);
        if (userMeta == null)
        {
            hook.sendMessageFormat("No VRChat user metadata found for [%s](%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId)).setEphemeral(true).queue();
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("VRChat user metadata for [").append(vrchatUser.getDisplayName()).append("](<").append(VrcWeb.Home.user).append(vrcId).append(">):");
        
        if (userMeta.auditEntryIds != null && userMeta.auditEntryIds.length > 0)
        {
            sb.append("\n### Moderation events:");
            for (String auditEntryId : userMeta.auditEntryIds)
            {
                if (auditEntryId != null)
                {
                    ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
                    if (this.discord.auditVisibleTo(auditEntryMeta, event.getUser().getId()) && !auditEntryMeta.hasParentEvent())
                    {
                        sb.append("\n`").append(auditEntryId).append("`");
                        String messageLink = auditEntryMeta.getMessageUrl();
                        sb.append(" <t:").append(Long.toUnsignedString(auditEntryMeta.entry.getCreatedAt().toEpochSecond())).append(":f>: ");
                        if (messageLink == null)
                        {
                            sb.append(auditEntryMeta.entry.getDescription());
                        }
                        else
                        {
                            sb.append("[").append(auditEntryMeta.entry.getDescription()).append("](").append(messageLink).append(")");
                        }
                    }
                }
            }
        }
        
        if (userMeta.evidenceSubmissions != null && userMeta.evidenceSubmissions.length > 0)
        {
            sb.append("\n### Evidence submissions:");
            for (ScarletData.EvidenceSubmission evidenceSubmission : userMeta.evidenceSubmissions)
            {
                if (evidenceSubmission != null)
                {
                    ScarletData.AuditEntryMetadata evidenceEntry = this.discord.scarlet.data.auditEntryMetadata(evidenceSubmission.auditEntryId);
                    if (!this.discord.auditVisibleTo(evidenceEntry, event.getUser().getId()))
                        continue;
                    sb.append("\n[`").append(evidenceSubmission.fileName).append("`](<").append(evidenceSubmission.url).append(">)");
                    sb.append(" [(proxy)](<").append(evidenceSubmission.proxyUrl).append(">)");
                    
                    ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(evidenceSubmission.auditEntryId);
                    if (auditEntryMeta != null && auditEntryMeta.hasSomeUrl())
                    {
                        sb.append(" for [`").append(evidenceSubmission.auditEntryId).append("`](<").append(auditEntryMeta.getSomeUrl()).append(">)");
                    }
                    else
                    {
                        sb.append(" for `").append(evidenceSubmission.auditEntryId).append("`");
                    }
                    
                    sb.append(" from ").append(evidenceSubmission.submitterDisplayName).append(" (<@").append(evidenceSubmission.submitterSnowflake).append(">)");
                    sb.append(" at <t:").append(Long.toUnsignedString(evidenceSubmission.submissionTime.toEpochSecond())).append(":f>");
                }
            }
        }

        this.discord.interactions.new Pagination(event.getId(), sb).queue(hook);
    }

    @SlashCmd("moderation-log")
    @Desc("Consolidate a VRChat user's moderation history (counts + links) into one post")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    @Ephemeral
    public void moderationLog(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser) throws Exception
    {
        String vrcId = VrcIds.getAsString_user(event.getOption("vrchat-user"));
        if (vrchatUser == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcId).setEphemeral(true).queue();
            return;
        }

        ScarletData.UserMetadata userMeta = this.discord.scarlet.data.userMetadata(vrcId);
        String[] auditEntryIds = userMeta == null ? null : userMeta.auditEntryIds;
        if (auditEntryIds == null || auditEntryIds.length == 0)
        {
            hook.sendMessageFormat("No moderation history recorded for [%s](%s%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user, vrcId).setEphemeral(true).queue();
            return;
        }

        int lookbackDays = this.discord.scarlet.moderationLogLookbackDays.get();
        java.time.OffsetDateTime cutoff = lookbackDays > 0 ? java.time.OffsetDateTime.now().minusDays(lookbackDays) : null;

        java.util.List<ScarletData.AuditEntryMetadata> metas = new java.util.ArrayList<>();
        for (String auditEntryId : auditEntryIds)
        {
            if (auditEntryId == null)
                continue;
            ScarletData.AuditEntryMetadata m = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
            if (!this.discord.auditVisibleTo(m, event.getUser().getId()) || m.hasParentEvent())
                continue;
            if (cutoff != null && m.entry.getCreatedAt().isBefore(cutoff))
                continue;
            metas.add(m);
        }
        if (metas.isEmpty())
        {
            hook.sendMessageFormat("No moderation history for [%s](%s%s) within the configured window (%d day(s)).", vrchatUser.getDisplayName(), VrcWeb.Home.user, vrcId, Integer.valueOf(lookbackDays)).setEphemeral(true).queue();
            return;
        }
        metas.sort((a, b) -> b.entry.getCreatedAt().compareTo(a.entry.getCreatedAt())); // newest first

        int warns = 0, mutes = 0, ikicks = 0, gremoves = 0, bans = 0, unbans = 0, other = 0;
        for (ScarletData.AuditEntryMetadata m : metas)
        {
            switch (m.entry.getEventType() == null ? "" : m.entry.getEventType())
            {
            case "group.instance.warn":  warns++;    break;
            case "group.instance.mute":  mutes++;    break;
            case "group.instance.kick":  ikicks++;   break;
            case "group.member.remove":  gremoves++; break;
            case "group.user.ban":       bans++;     break;
            case "group.user.unban":     unbans++;   break;
            default:                     other++;    break;
            }
        }

        // Summary line (counts + any active timed ban)
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (warns > 0)    parts.add(warns    + " warn"          + (warns == 1 ? "" : "s"));
        if (mutes > 0)    parts.add(mutes    + " mute"          + (mutes == 1 ? "" : "s"));
        if (ikicks > 0)   parts.add(ikicks   + " instance kick" + (ikicks == 1 ? "" : "s"));
        if (gremoves > 0) parts.add(gremoves + " group removal" + (gremoves == 1 ? "" : "s"));
        if (bans > 0)     parts.add(bans     + " ban"           + (bans == 1 ? "" : "s"));
        if (unbans > 0)   parts.add(unbans   + " unban"         + (unbans == 1 ? "" : "s"));
        if (other > 0)    parts.add(other    + " other");
        StringBuilder summary = new StringBuilder();
        summary.append("**").append(metas.size()).append("** action(s): ").append(parts.isEmpty() ? "none" : String.join(", ", parts));
        summary.append(lookbackDays > 0 ? " _(past " + lookbackDays + " day(s))_" : " _(all recorded history)_");
        ScarletPendingModActions.TimedBan timedBan = this.discord.scarlet.pendingModActions.getTimedBan(vrcId);
        if (timedBan != null && timedBan.isActiveFor(this.discord.scarlet.vrc.groupId))
            summary.append("\n:hourglass: Active timed ban — automatic unban <t:").append(Long.toUnsignedString(timedBan.expiresAt / 1000L)).append(":R>");
        else if (timedBan != null)
            summary.append("\n:warning: An automatic-unban record needs review; no automatic unban is currently confirmed for this group.");

        // One line per action, newest first (masked links render inside embeds)
        int maxListed = 150;
        java.util.List<String> actionLines = new java.util.ArrayList<>();
        for (int i = 0; i < metas.size() && i < maxListed; i++)
        {
            ScarletData.AuditEntryMetadata m = metas.get(i);
            GroupAuditType type = GroupAuditType.of(m.entry.getEventType());
            String title = type != null ? type.title : String.valueOf(m.entry.getEventType());
            String actor = m.hasAuxActor() ? m.auxActorDisplayName : m.entry.getActorDisplayName();
            String desc = m.hasDescription() ? m.entryDescription : m.entry.getDescription();
            String url = m.getSomeUrl();
            StringBuilder line = new StringBuilder();
            line.append("<t:").append(Long.toUnsignedString(m.entry.getCreatedAt().toEpochSecond())).append(":f> **").append(title).append("**");
            if (actor != null && !actor.isEmpty())
                line.append(" by ").append(actor);
            if (desc != null && !desc.trim().isEmpty())
                line.append(url != null ? ": [" + desc.trim() + "](" + url + ")" : ": " + desc.trim());
            else if (url != null)
                line.append(" ([post](").append(url).append("))");
            actionLines.add(line.toString());
        }
        if (metas.size() > maxListed)
            actionLines.add("_… and " + (metas.size() - maxListed) + " older action(s) not listed._");

        String label = MiscUtils.maybeEllipsis(200, "Moderation log — " + vrchatUser.getDisplayName());
        MessageEmbed summaryEmbed = new EmbedBuilder()
            .setTitle(MiscUtils.maybeEllipsis(256, "Moderation log — " + vrchatUser.getDisplayName()), VrcWeb.Home.user(vrcId))
            .setDescription(summary.toString())
            .build();

        // Never copy requester-authorized confidential history into a public thread.
        StringBuilder sb = new StringBuilder("## ").append(label).append('\n').append(summary);
        for (String line : actionLines)
            sb.append('\n').append(line);
        this.discord.interactions.new Pagination(event.getId(), sb).queue(hook);
    }

    // vrchat-user-ban

    @SlashCmd("vrchat-user-ban")
    @Desc("Ban a specific VRChat user")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void vrchatUserBan(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser, @SlashOpt("tag-immediately") boolean tagImmediately) throws Exception
    {
        String vrcTargetId = VrcIds.getAsString_user(event.getOption("vrchat-user"));
        if (ScarletSimulation.isTrainingId(vrcTargetId))
        {
            hook.sendMessage("Banned the training user. *(Training simulation — no real ban occurred.)*").setEphemeral(false).queue();
            return;
        }
        if (vrchatUser == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return;
        }
        
        String requestedGroupId = this.discord.scarlet.vrc.groupId, requestedGuildId = this.discord.guildSf;
        ScarletDiscordJDA.VRChatBanAccess access = this.discord.checkVRChatBanAccess(event.getUser().getId(), null,
            vrcTargetId, net.sybyline.scarlet.server.discord.DPerms.PermType.SLASH_COMMAND, "vrchat-user-ban");
        if (!access.allowed())
        {
            hook.sendMessage(access.message).setEphemeral(true).queue();
            return;
        }
        
        GroupMemberStatus status = this.discord.scarlet.vrc.getGroupMembershipStatus(this.discord.scarlet.vrc.groupId, vrcTargetId);
        if (status == GroupMemberStatus.BANNED)
        {
            hook.sendMessage("This VRChat user is already banned").setEphemeral(true).queue();
        }
        else if (tagImmediately)
        {
            List<ScarletModerationTags.Tag> tags = this.discord.scarlet.moderationTags.getTags();
            
            if (tags == null || tags.isEmpty())
            {
                hook.sendMessage("No moderation tags!").setEphemeral(true).queue();
                return;
            }
            if (!this.discord.scarlet.pendingModActions.addBanInfo(vrcTargetId, event.getUser().getId(), access.actorId,
                requestedGroupId, requestedGuildId))
            {
                hook.sendMessage("This VRChat user currently has automated/assisted moderation pending, please retry later").setEphemeral(true).queue();
                return;
            }
            StringSelectMenu.Builder builder = StringSelectMenu
                .create("immediate-ban-select-tags:"+vrcTargetId)
                .setMinValues(0)
                .setMaxValues(Math.min(25, tags.size()))
                .setPlaceholder(tags.size() > 25 ? "Select tags (first 25 shown)" : "Select tags")
                ;
            
            // Discord select menus allow at most 25 options; cap here (Edit-tags has search for the full set).
            int addedTags = 0;
            for (ScarletModerationTags.Tag tag : tags)
            {
                if (addedTags++ >= 25)
                    break;
                String value = tag.value,
                       label = tag.label != null ? tag.label : tag.value,
                       desc = tag.description;
                if (desc == null)
                    builder.addOption(label, MiscUtils.maybeEllipsis(100, value));
                else
                    builder.addOption(label, MiscUtils.maybeEllipsis(100, value), MiscUtils.maybeEllipsis(50, desc));
            }
            
            hook.sendMessageComponents(
                    ActionRow.of(builder.build()),
                    ActionRow.of(Button.primary("immediate-ban-edit-desc:"+vrcTargetId, "Set description"),
                                 Button.secondary("immediate-ban-cancel:"+vrcTargetId, "Cancel"),
                                 Button.danger("immediate-ban-confirm:"+vrcTargetId, "Ban"))
                )
                .setEphemeral(true)
                .queue();
        }
        else
        {
            ScarletDiscordJDA.VRChatBanAttempt attempt = this.discord.executeVRChatBan(event.getUser().getId(), access.actorId,
                vrcTargetId, net.sybyline.scarlet.server.discord.DPerms.PermType.SLASH_COMMAND, "vrchat-user-ban");
            if (attempt.succeeded())
                hook.sendMessageFormat("Banned %s", vrchatUser.getDisplayName()).setEphemeral(false).queue();
            else
                hook.sendMessage(attempt.message).setEphemeral(true).queue();
        }
        
    }

    // vrchat-user-ban-multi

    @SlashCmd("vrchat-user-ban-multi")
    @Desc("Ban several VRChat users")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void vrchatUserBanMulti(SlashCommandInteractionEvent event) throws Exception
    {
        // Opening a modal makes no moderation changes; refresh permissions on submission/execution.
        if (!this.discord.perms.checkSlash(event.getMember(), "vrchat-user-ban-multi")
            || this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId()) == null)
        {
            event.reply("This command requires current access and a linked VRChat account.").setEphemeral(true).queue();
            return;
        }
        
        event.replyModal(Modal.create("vrchat-user-ban-multi", "Ban Multiple VRChat Users")
            .addComponents(Label.of("Target VRChat User IDs", TextInput.create("target-ids", TextInputStyle.PARAGRAPH)
                .setPlaceholder("User IDs separated by something that isn't 0-9, a-z, A-Z, '-', or '_' (e.g., newline, space, comma)")
                .setRequiredRange(10, 4000)
                .build()))
            .build())
        .queue();
    }

    // vrchat-user-unban

    @SlashCmd("vrchat-user-unban")
    @Desc("Unban a specific VRChat user")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void vrchatUserUnban(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser) throws Exception
    {
        String requestedGroupId = this.discord.scarlet.vrc.groupId, requestedGuildId = this.discord.guildSf;
        String vrcTargetId = VrcIds.getAsString_user(event.getOption("vrchat-user"));
        if (ScarletSimulation.isTrainingId(vrcTargetId))
        {
            hook.sendMessage("Unbanned the training user. *(Training simulation — no real action occurred.)*").setEphemeral(false).queue();
            return;
        }
        if (vrchatUser == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return;
        }
        
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        
        if (!this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, event.getMember()))
        {
            if (!this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, event.getMember(), false))
            {
                hook.sendMessage("You do not have permission to unban users.\n||(Your admin can enable this by giving your associated VRChat user ban management permissions in the group or with the command `/scarlet-discord-permissions type:Other name:groupex-bans-manage value:Allow`)||").setEphemeral(true).queue();
                return;
            }
        }
        ScarletDiscordJDA.VRChatBanAttempt attempt = this.discord.executeVRChatUnban(
            new ScarletDiscord.Action("unban", vrcActorId, vrcTargetId, null, event.getUser().getId(), requestedGuildId, requestedGroupId),
            net.sybyline.scarlet.server.discord.DPerms.PermType.SLASH_COMMAND, "vrchat-user-unban");
        if (!attempt.succeeded())
        {
            hook.sendMessage(attempt.message).setEphemeral(true).queue();
        }
        else
        {
            hook.sendMessageFormat("Unbanned %s", vrchatUser.getDisplayName()).setEphemeral(false).queue();
        }
        
    }

    // vrchat-user-unban-multi

    @SlashCmd("vrchat-user-unban-multi")
    @Desc("Unban several VRChat users")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void vrchatUserUnbanMulti(SlashCommandInteractionEvent event) throws Exception
    {
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            event.reply(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        
        if (!this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, event.getMember()))
        {
            if (!this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, event.getMember(), false))
            {
                event.reply("You do not have permission to unban users.\n||(Your admin can enable this by giving your associated VRChat user ban management permissions in the group or with the command `/scarlet-discord-permissions type:Other name:groupex-bans-manage value:Allow`)||").setEphemeral(true).queue();
                return;
            }
        }
        if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_bans_manage, event))
            return;
        
        event.replyModal(Modal.create("vrchat-user-unban-multi", "Unban Multiple VRChat Users")
            .addComponents(Label.of("Target VRChat User IDs", TextInput.create("target-ids", TextInputStyle.PARAGRAPH)
                .setPlaceholder("User IDs separated by something that isn't 0-9, a-z, A-Z, '-', or '_' (e.g., newline, space, comma)")
                .setRequiredRange(10, 4000)
                .build()))
            .build())
        .queue();
    }

    // vrchat-group

    @SlashCmd("vrchat-group")
    @Desc("Manages groups")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class VrchatGroups
    {
        public final SlashOption<String> _memberSearch = SlashOption.ofString("member-search", "Name or user id to search within this group", true, null).with($ -> $.setRequiredLength(1, 100));
        public final SlashOption<Integer> _resultLimit = SlashOption.ofInt("result-limit", "Maximum results to fetch", false, 25).with($ -> $.setRequiredRange(1L, 100L));
        public final SlashOption<Boolean> _blockedRequests = SlashOption.ofBool("blocked-requests", "Show blocked requests instead of pending requests", false, Boolean.FALSE);
        public final SlashOption<Boolean> _publicOnly = SlashOption.ofBool("public-only", "Only show public posts", false, Boolean.FALSE);
        public final SlashOption<String> _announcementTitle = SlashOption.ofString("announcement-title", "Announcement title", true, null).with($ -> $.setRequiredLength(1, 100));
        public final SlashOption<String> _announcementText = SlashOption.ofString("announcement-text", "Announcement body", true, null).with($ -> $.setRequiredLength(1, 1000));
        public final SlashOption<String> _postTitle = SlashOption.ofString("post-title", "Post title", true, null).with($ -> $.setRequiredLength(1, 100));
        public final SlashOption<String> _postText = SlashOption.ofString("post-text", "Post body", true, null).with($ -> $.setRequiredLength(1, 2000));
        public final SlashOption<Boolean> _sendNotification = SlashOption.ofBool("send-notification", "Notify group members", false, Boolean.FALSE);
        public final SlashOption<String> _imageFileId = SlashOption.ofString("image-file-id", "Optional VRChat file id for an image", false, null);
        public final SlashOption<GroupPostVisibility> _postVisibility = SlashOption.ofDOptionEnum(DOptionEnum.of("post-visibility", "Who can see the post", GroupPostVisibility.class, GroupPostVisibility::getValue, "Group Members", "Public"), true);
        public final SlashOption<String> _postRoleId = SlashOption.ofString("post-role", "Optional role that can see a group-only post", false, null, this::_postRoleId);
        public final SlashOption<String> _postId = SlashOption.ofString("post-id", "The group post id", true, null, this::_postId);
        public final SlashOption<String> _confirmDelete = SlashOption.ofString("confirm-delete", "Type DELETE to confirm", true, null).with($ -> $.setRequiredLength(6, 6));
        public final SlashOption<String> _confirmGroupId = SlashOption.ofString("confirm-group-id", "Type the group id to confirm this owner-level action", true, null);

        void _postRoleId(CommandAutoCompleteInteractionEvent event)
        {
            DInteractions.SlashOptionsChoicesUnsanitized.autocomplete(event, ScarletDiscordCommands.this.discord.scarlet.vrc.groupRoles
                .values()
                .stream()
                .map(role -> new Command.Choice(role.getName(), role.getId()))
                .toArray(Command.Choice[]::new), false);
        }

        void _postId(CommandAutoCompleteInteractionEvent event)
        {
            List<GroupPost> posts = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupPosts(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, false, 25);
            if (posts == null || posts.isEmpty())
            {
                event.replyChoices().queue();
                return;
            }
            event.replyChoices(posts.stream().map(post -> new Command.Choice(this.choiceLabel(post.getTitle(), post.getId()), post.getId())).limit(25L).collect(Collectors.toList())).queue();
        }

        private io.github.vrchatapi.model.User actor(SlashCommandInteractionEvent event, InteractionHook hook)
        {
            long within1day = System.currentTimeMillis() - 86400_000L;
            
            String vrcActorId = ScarletDiscordCommands.this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
            if (vrcActorId == null)
            {
                hook.sendMessage(ScarletDiscordCommands.this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
                return null;
            }
            io.github.vrchatapi.model.User sc = ScarletDiscordCommands.this.discord.scarlet.vrc.getUser(vrcActorId, within1day);
            if (sc == null)
            {
                hook.sendMessageFormat("No VRChat user found with id %s", vrcActorId).setEphemeral(true).queue();
                return null;
            }
            return sc;
        }

        private String linkedActorId(SlashCommandInteractionEvent event, InteractionHook hook)
        {
            String vrcActorId = ScarletDiscordCommands.this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
            if (vrcActorId == null)
            {
                hook.sendMessage(ScarletDiscordCommands.this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
                return null;
            }
            return vrcActorId;
        }

        private boolean checkMemberAndSelfPerms(SlashCommandInteractionEvent event, InteractionHook hook, GroupPermissions permission, String action)
        {
            String vrcActorId = this.linkedActorId(event, hook);
            if (vrcActorId == null)
                return false;
            boolean memberAllowed = Objects.equals(vrcActorId, ScarletDiscordCommands.this.discord.scarlet.vrc.groupOwnerId)
                || ScarletDiscordCommands.this.discord.checkMemberHasVRChatPermission(permission, event.getMember());
            if (!memberAllowed)
            {
                hook.sendMessage("You need the VRChat group permission `"+permission.getValue()+"` to "+action+".").setEphemeral(true).queue();
                return false;
            }
            return ScarletDiscordCommands.this.discord.checkSelfRespondVrcPerms(permission, hook);
        }

        private String choiceLabel(String title, String id)
        {
            String label = MiscUtils.blank(title) ? id : title + " (" + id + ")";
            return MiscUtils.maybeEllipsis(100, label);
        }

        private String displayName(GroupMember member)
        {
            GroupMemberLimitedUser user = member == null ? null : member.getUser();
            String displayName = user == null ? null : user.getDisplayName();
            return MiscUtils.blank(displayName) ? this.userId(member) : displayName;
        }

        private String userId(GroupMember member)
        {
            if (member == null)
                return "unknown";
            GroupMemberLimitedUser user = member.getUser();
            if (user != null && !MiscUtils.blank(user.getId()))
                return user.getId();
            if (!MiscUtils.blank(member.getUserId()))
                return member.getUserId();
            if (!MiscUtils.blank(member.getId()) && member.getId().startsWith("usr_"))
                return member.getId();
            return "unknown";
        }

        private String avatarUrl(GroupMember member)
        {
            GroupMemberLimitedUser user = member == null ? null : member.getUser();
            if (user == null)
                return null;
            // API 1.21.0: GroupMemberLimitedUser exposes only iconUrl now.
            return MiscUtils.blank(user.getIconUrl()) ? null : user.getIconUrl();
        }

        private String when(OffsetDateTime time)
        {
            if (time == null)
                return "Unknown";
            String epoch = Long.toUnsignedString(time.toEpochSecond());
            return "<t:"+epoch+":f> (<t:"+epoch+":R>)";
        }

        private String roleNames(List<String> roleIds)
        {
            if (roleIds == null || roleIds.isEmpty())
                return "None";
            return MiscUtils.maybeEllipsis(1024, roleIds.stream().map(roleId -> {
                GroupRole role = ScarletDiscordCommands.this.discord.scarlet.vrc.groupRoles.get(roleId);
                return role == null || MiscUtils.blank(role.getName()) ? roleId : role.getName();
            }).collect(Collectors.joining(", ")));
        }

        private MessageEmbed memberEmbed(String heading, GroupMember member)
        {
            String userId = this.userId(member);
            String displayName = this.displayName(member);
            EmbedBuilder builder = new EmbedBuilder()
                .setTitle(MiscUtils.maybeEllipsis(256, heading + ": " + displayName), "unknown".equals(userId) ? null : VrcWeb.Home.user(userId))
                .setThumbnail(this.avatarUrl(member))
                .addField("User ID", "`"+userId+"`", false)
                .addField("Status", member.getMembershipStatus() == null ? "Unknown" : member.getMembershipStatus().getValue(), true)
                .addField("Roles", this.roleNames(member.getRoleIds()), false);
            if (member.getJoinedAt() != null)
                builder.addField("Joined", this.when(member.getJoinedAt()), true);
            if (member.getCreatedAt() != null)
                builder.addField("Created", this.when(member.getCreatedAt()), true);
            if (member.getBannedAt() != null)
                builder.addField("Banned", this.when(member.getBannedAt()), true);
            if (!MiscUtils.blank(member.getAcceptedByDisplayName()))
                builder.addField("Accepted By", MarkdownSanitizer.escape(member.getAcceptedByDisplayName()), true);
            if (!MiscUtils.blank(member.getManagerNotes()))
                builder.addField("Manager Notes", MiscUtils.maybeEllipsis(1024, MarkdownSanitizer.escape(member.getManagerNotes())), false);
            return builder.build();
        }

        private MessageEmbed announcementEmbed(GroupAnnouncement announcement)
        {
            if (announcement == null)
                return new EmbedBuilder().setTitle("No group announcement").setDescription("This group does not currently have an announcement.").build();
            EmbedBuilder builder = new EmbedBuilder()
                .setTitle(MiscUtils.maybeEllipsis(256, MiscUtils.blank(announcement.getTitle()) ? "Group Announcement" : announcement.getTitle()))
                .setDescription(MiscUtils.blank(announcement.getText()) ? null : MiscUtils.maybeEllipsis(4096, announcement.getText()))
                .setImage(MiscUtils.blank(announcement.getImageUrl()) ? null : announcement.getImageUrl());
            if (!MiscUtils.blank(announcement.getVisibility()))
                builder.addField("Visibility", announcement.getVisibility(), true);
            if (announcement.getCreatedAt() != null)
                builder.addField("Created", this.when(announcement.getCreatedAt()), true);
            if (announcement.getUpdatedAt() != null)
                builder.addField("Updated", this.when(announcement.getUpdatedAt()), true);
            if (!MiscUtils.blank(announcement.getAuthorId()))
                builder.addField("Author", MarkdownUtil.maskedLink(announcement.getAuthorId(), VrcWeb.Home.user(announcement.getAuthorId())), false);
            // Only surface the editor when it differs from the author, so unedited
            // announcements do not carry a redundant duplicate field.
            if (!MiscUtils.blank(announcement.getEditorId())
             && !announcement.getEditorId().equals(announcement.getAuthorId()))
                builder.addField("Edited by", MarkdownUtil.maskedLink(announcement.getEditorId(), VrcWeb.Home.user(announcement.getEditorId())), false);
            if (announcement.getRoleIds() != null && !announcement.getRoleIds().isEmpty())
                builder.addField("Roles", this.roleNames(announcement.getRoleIds()), false);
            return builder.build();
        }

        private MessageEmbed postEmbed(GroupPost post)
        {
            EmbedBuilder builder = new EmbedBuilder()
                .setTitle(MiscUtils.maybeEllipsis(256, MiscUtils.blank(post.getTitle()) ? post.getId() : post.getTitle()), VrcWeb.Home.groupPosts(post.getGroupId()))
                .setDescription(MiscUtils.blank(post.getText()) ? null : MiscUtils.maybeEllipsis(4096, post.getText()))
                .setImage(MiscUtils.blank(post.getImageUrl()) ? null : post.getImageUrl())
                .addField("Post ID", "`"+post.getId()+"`", false)
                .addField("Visibility", post.getVisibility() == null ? "Unknown" : post.getVisibility().getValue(), true);
            if (post.getCreatedAt() != null)
                builder.addField("Created", this.when(post.getCreatedAt()), true);
            if (post.getUpdatedAt() != null)
                builder.addField("Updated", this.when(post.getUpdatedAt()), true);
            if (!MiscUtils.blank(post.getAuthorId()))
                builder.addField("Author", MarkdownUtil.maskedLink(post.getAuthorId(), VrcWeb.Home.user(post.getAuthorId())), false);
            if (post.getRoleIds() != null && !post.getRoleIds().isEmpty())
                builder.addField("Roles", this.roleNames(post.getRoleIds()), false);
            return builder.build();
        }

        private void paginateMembers(SlashCommandInteractionEvent event, InteractionHook hook, List<GroupMember> members, String emptyMessage, String heading, int entriesPerPage)
        {
            if (members == null)
            {
                hook.sendMessage("VRChat did not return results for that request.").setEphemeral(true).queue();
                return;
            }
            if (members.isEmpty())
            {
                hook.sendMessage(emptyMessage).setEphemeral(true).queue();
                return;
            }
            MessageEmbed[] embeds = members.stream().map(member -> this.memberEmbed(heading, member)).toArray(MessageEmbed[]::new);
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }

        @SlashCmd("audit-types")
        @Desc("Lists audit event types currently present in this group")
        public void auditTypes(SlashCommandInteractionEvent event, InteractionHook hook)
        {
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_audit_view, "view audit event types"))
                return;
            List<String> types = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupAuditLogEntryTypes(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId);
            if (types == null)
            {
                hook.sendMessage("Failed to fetch audit event types from VRChat.").setEphemeral(true).queue();
                return;
            }
            if (types.isEmpty())
            {
                hook.sendMessage("No audit event types were returned for this group.").setEphemeral(true).queue();
                return;
            }
            StringBuilder sb = new StringBuilder("### Group audit event types\n");
            types.stream().sorted().forEach(type -> sb.append("\n- `").append(type).append("` - ").append(GroupAuditType.title(type)));
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), sb).queue(hook);
        }

        @SlashCmd("search-members")
        @Desc("Searches this group's members")
        public void searchMembers(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("member-search") String memberSearch, @SlashOpt("result-limit") int resultLimit, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_members_viewall, "search group members"))
                return;
            List<GroupMember> members = ScarletDiscordCommands.this.discord.scarlet.vrc.searchGroupMembers(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, memberSearch, resultLimit);
            this.paginateMembers(event, hook, members, "No group members matched that search.", "Group Member", entriesPerPage);
        }

        @SlashCmd("list-bans")
        @Desc("Lists banned users in this group")
        public void listBans(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("result-limit") int resultLimit, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_bans_manage, "list group bans"))
                return;
            List<GroupMember> bans = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupBans(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, resultLimit);
            this.paginateMembers(event, hook, bans, "No banned users were returned.", "Group Ban", entriesPerPage);
        }

        @SlashCmd("list-invites")
        @Desc("Lists pending invites sent by this group")
        public void listInvites(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("result-limit") int resultLimit, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_invites_manage, "list group invites"))
                return;
            List<GroupMember> invites = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupInvites(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, resultLimit);
            this.paginateMembers(event, hook, invites, "No pending group invites were returned.", "Group Invite", entriesPerPage);
        }

        @SlashCmd("list-join-requests")
        @Desc("Lists pending or blocked join requests for this group")
        public void listJoinRequests(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("blocked-requests") boolean blockedRequests, @SlashOpt("result-limit") int resultLimit, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_invites_manage, "list group join requests"))
                return;
            List<GroupMember> requests = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupJoinRequests(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, blockedRequests, resultLimit);
            this.paginateMembers(event, hook, requests, blockedRequests ? "No blocked join requests were returned." : "No pending join requests were returned.", blockedRequests ? "Blocked Join Request" : "Join Request", entriesPerPage);
        }

        @SlashCmd("view-announcement")
        @Desc("Shows the current group announcement")
        public void viewAnnouncement(SlashCommandInteractionEvent event, InteractionHook hook)
        {
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_announcement_manage, "view group announcements"))
                return;
            GroupAnnouncement announcement = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupAnnouncement(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId);
            hook.sendMessageEmbeds(this.announcementEmbed(announcement)).setEphemeral(true).queue();
        }

        @SlashCmd("create-announcement")
        @Desc("Creates or replaces the group announcement")
        public void createAnnouncement(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("announcement-title") String announcementTitle,
                @SlashOpt("announcement-text") String announcementText,
                @SlashOpt("send-notification") boolean sendNotification,
                @SlashOpt("image-file-id") String imageFileId)
        {
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_announcement_manage, "create group announcements"))
                return;
            CreateGroupAnnouncementRequest request = new CreateGroupAnnouncementRequest()
                .title(announcementTitle)
                .text(announcementText)
                .sendNotification(Boolean.valueOf(sendNotification));
            if (!MiscUtils.blank(imageFileId))
                request.imageId(imageFileId);
            GroupAnnouncement announcement = ScarletDiscordCommands.this.discord.scarlet.vrc.createGroupAnnouncement(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, request);
            if (announcement == null)
            {
                hook.sendMessage("Failed to create the group announcement.").setEphemeral(true).queue();
                return;
            }
            hook.sendMessageEmbeds(this.announcementEmbed(announcement)).setContent("Created group announcement.").setEphemeral(true).queue();
        }

        @SlashCmd("delete-announcement")
        @Desc("Deletes the current group announcement")
        public void deleteAnnouncement(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("confirm-delete") String confirmDelete)
        {
            if (!"DELETE".equals(confirmDelete))
            {
                hook.sendMessage("Deletion cancelled. Type `DELETE` exactly in the confirmation option to delete the announcement.").setEphemeral(true).queue();
                return;
            }
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_announcement_manage, "delete group announcements"))
                return;
            boolean deleted = ScarletDiscordCommands.this.discord.scarlet.vrc.deleteGroupAnnouncement(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId);
            hook.sendMessage(deleted ? "Deleted the group announcement." : "Failed to delete the group announcement.").setEphemeral(true).queue();
        }

        @SlashCmd("list-posts")
        @Desc("Lists recent group posts")
        public void listPosts(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("public-only") boolean publicOnly, @SlashOpt("result-limit") int resultLimit, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_announcement_manage, "list group posts"))
                return;
            List<GroupPost> posts = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupPosts(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, publicOnly, resultLimit);
            if (posts == null)
            {
                hook.sendMessage("Failed to fetch group posts from VRChat.").setEphemeral(true).queue();
                return;
            }
            if (posts.isEmpty())
            {
                hook.sendMessage("No group posts were returned.").setEphemeral(true).queue();
                return;
            }
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), posts.stream().map(this::postEmbed).toArray(MessageEmbed[]::new), entriesPerPage).queue(hook);
        }

        @SlashCmd("create-post")
        @Desc("Creates a group post")
        public void createPost(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("post-title") String postTitle,
                @SlashOpt("post-text") String postText,
                @SlashOpt("post-visibility") GroupPostVisibility postVisibility,
                @SlashOpt("send-notification") boolean sendNotification,
                @SlashOpt("image-file-id") String imageFileId,
                @SlashOpt("post-role") String postRoleId)
        {
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_announcement_manage, "create group posts"))
                return;
            CreateGroupPostRequest request = new CreateGroupPostRequest()
                .title(postTitle)
                .text(postText)
                .visibility(postVisibility)
                .sendNotification(Boolean.valueOf(sendNotification));
            if (!MiscUtils.blank(imageFileId))
                request.imageId(imageFileId);
            if (!MiscUtils.blank(postRoleId))
                request.roleIds(Collections.singletonList(postRoleId));
            GroupPost post = ScarletDiscordCommands.this.discord.scarlet.vrc.createGroupPost(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, request);
            if (post == null)
            {
                hook.sendMessage("Failed to create the group post.").setEphemeral(true).queue();
                return;
            }
            hook.sendMessageEmbeds(this.postEmbed(post)).setContent("Created group post.").setEphemeral(true).queue();
        }

        @SlashCmd("delete-post")
        @Desc("Deletes a group post")
        public void deletePost(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("post-id") String postId, @SlashOpt("confirm-delete") String confirmDelete)
        {
            if (!"DELETE".equals(confirmDelete))
            {
                hook.sendMessage("Deletion cancelled. Type `DELETE` exactly in the confirmation option to delete the post.").setEphemeral(true).queue();
                return;
            }
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_announcement_manage, "delete group posts"))
                return;
            boolean deleted = ScarletDiscordCommands.this.discord.scarlet.vrc.deleteGroupPost(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, postId);
            hook.sendMessage(deleted ? "Deleted group post `" + postId + "`." : "Failed to delete group post `" + postId + "`.").setEphemeral(true).queue();
        }

        @SlashCmd("transfer-check")
        @Desc("Checks whether a user can receive group ownership")
        public void transferCheck(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser)
        {
            if (vrchatUser == null)
            {
                hook.sendMessage("No VRChat user found for that input.").setEphemeral(true).queue();
                return;
            }
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_all, "check group transfer eligibility"))
                return;
            GroupTransferable transferable = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupTransferability(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatUser.getId());
            if (transferable == null || transferable.getRequirements() == null)
            {
                hook.sendMessage("VRChat did not return transfer eligibility details.").setEphemeral(true).queue();
                return;
            }
            GroupTransferableRequirements req = transferable.getRequirements();
            EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Group Transfer Check", VrcWeb.Home.user(vrchatUser.getId()))
                .setDescription("Target: ["+MarkdownSanitizer.escape(vrchatUser.getDisplayName())+"]("+VrcWeb.Home.user(vrchatUser.getId())+")")
                .addField("Group not monetized", this.yesNo(req.getGroupNotMonetized()), true)
                .addField("Target has VRC+", this.yesNo(req.getHasVRCPlus()), true)
                .addField("Target has verified email", this.yesNo(req.getHasVerifiedEmail()), true)
                .addField("Target can own more groups", this.yesNo(req.getTargetCanOwnMoreGroups()), true)
                .addField("Target is group member", this.yesNo(req.getTargetIsGroupMember()), true);
            hook.sendMessageEmbeds(builder.build()).setEphemeral(true).queue();
        }

        private String yesNo(Boolean value)
        {
            return value == null ? "Unknown" : value.booleanValue() ? "Yes" : "No";
        }

        @SlashCmd("transfer-start")
        @Desc("Starts or accepts group ownership transfer")
        public void transferStart(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser, @SlashOpt("confirm-group-id") String confirmGroupId)
        {
            if (vrchatUser == null)
            {
                hook.sendMessage("No VRChat user found for that input.").setEphemeral(true).queue();
                return;
            }
            String groupId = ScarletDiscordCommands.this.discord.scarlet.vrc.groupId;
            if (!Objects.equals(groupId, confirmGroupId))
            {
                hook.sendMessage("Transfer cancelled. The confirmation must exactly match this group id: `"+groupId+"`").setEphemeral(true).queue();
                return;
            }
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_all, "transfer group ownership"))
                return;
            boolean transferred = ScarletDiscordCommands.this.discord.scarlet.vrc.initiateOrAcceptGroupTransfer(groupId, vrchatUser.getId());
            hook.sendMessage(transferred ? "Submitted the group transfer request for " + MarkdownSanitizer.escape(vrchatUser.getDisplayName()) + "." : "Failed to submit the group transfer request.").setEphemeral(true).queue();
        }

        @SlashCmd("transfer-cancel")
        @Desc("Cancels an active group ownership transfer")
        public void transferCancel(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("confirm-group-id") String confirmGroupId)
        {
            String groupId = ScarletDiscordCommands.this.discord.scarlet.vrc.groupId;
            if (!Objects.equals(groupId, confirmGroupId))
            {
                hook.sendMessage("Transfer cancellation skipped. The confirmation must exactly match this group id: `"+groupId+"`").setEphemeral(true).queue();
                return;
            }
            if (!this.checkMemberAndSelfPerms(event, hook, GroupPermissions.group_all, "cancel group ownership transfer"))
                return;
            boolean cancelled = ScarletDiscordCommands.this.discord.scarlet.vrc.cancelGroupTransfer(groupId);
            hook.sendMessage(cancelled ? "Cancelled the active group transfer." : "Failed to cancel the group transfer.").setEphemeral(true).queue();
        }

        @SlashCmd("open-instance")
        @Desc("Opens an instance")
        public void openInstance(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-world") io.github.vrchatapi.model.World vrchatWorld) throws Exception
        {
            io.github.vrchatapi.model.User vrcActor = this.actor(event, hook);
            if (vrcActor == null)
                return;
            String groupId = ScarletDiscordCommands.this.discord.scarlet.vrc.groupId;

            if (vrchatWorld == null)
            {
                hook.sendMessageFormat("No VRChat world found with id %s", event.getOption("vrchat-world", OptionMapping::getAsString)).setEphemeral(true).queue();
                return;
            }
            
            String ictoken;
            do ictoken = UUID.randomUUID().toString();
            while (ScarletDiscordCommands.this.discord.instanceCreation.putIfAbsent(ictoken, new ScarletDiscordJDA.InstanceCreation(ictoken, vrchatWorld.getId(), groupId, event.getUser().getId())) != null);
            
            List<GroupRole> roles = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupRoles(groupId);
            
            hook.sendMessageFormat("Create a new [%s](%s) instance:\nLeave avatar gate unchecked for no limit; select one Poor, Medium, or Good preset to set the minimum avatar performance.", vrchatWorld.getName(), VrcWeb.Home.world(vrchatWorld.getId()))
                .addComponents(ActionRow.of(StringSelectMenu.create("new-instance-access-type:"+ictoken)
                    .addOption("Group Public", "public")
                    .addOption("Group Plus", "plus")
                    .addOption("Group Members", "members")
                    .setDefaultValues("public")
                    .build()))
                .addComponents(ActionRow.of(StringSelectMenu.create("new-instance-roles:"+ictoken)
                    .setPlaceholder("Select roles (Group Members)")
                    .addOptions(roles.stream().map($ -> SelectOption.of($.getName(), $.getId())).limit(25L).toArray(SelectOption[]::new))
                    .setRequiredRange(1, Math.min(25, roles.size()))
                    .build()))
                .addComponents(ActionRow.of(StringSelectMenu.create("new-instance-region:"+ictoken)
                    .addOption("Region: US", "us")
                    .addOption("Region: US East", "use")
                    .addOption("Region: Europe", "eu")
                    .addOption("Region: Japan", "jp")
                    .setDefaultValues("us")
                    .build()))
                .addComponents(ActionRow.of(StringSelectMenu.create("new-instance-flags:"+ictoken)
                    .setPlaceholder("Options & Content Settings")
                    .addOption("Join queue (users wait to connect when full)", "queueEnabled")
                    .addOption("Hard close (kick connected users on close)", "hardClose")
                    .addOption("Age gate (Age Verified 18+ users only)", "ageGate")
                    .addOption("Invite Only", "inviteOnly")
                    .addOption("Invite Requests", "canRequestInvite")
//                    .addOption("Player persistence (data persists between instances)", "playerPersistenceEnabled")
//                    .addOption("Instance persistence (state persists when empty)", "instancePersistenceEnabled")
                    .addOption("Content: drones", "contentSettings.drones")
                    .addOption("Content: emoji", "contentSettings.emoji")
                    .addOption("Content: items", "contentSettings.props")
                    .addOption("Content: pedestals", "contentSettings.pedestals")
                    .addOption("Content: prints", "contentSettings.prints")
                    .addOption("Content: stickers", "contentSettings.stickers")
                    .addOption("Avatar gate: Poor or better", "minimumAvatarPerformance."+PerformanceRatings.POOR.getValue())
                    .addOption("Avatar gate: Medium or better", "minimumAvatarPerformance."+PerformanceRatings.MEDIUM.getValue())
                    .addOption("Avatar gate: Good or better", "minimumAvatarPerformance."+PerformanceRatings.GOOD.getValue())
                    .setRequiredRange(0, 14)
                    .setDefaultValues("queueEnabled", "contentSettings.drones", "contentSettings.emoji", "contentSettings.props", "contentSettings.pedestals", "contentSettings.prints", "contentSettings.stickers")
                    .build()))
                .addComponents(ActionRow.of(
                    Button.success("new-instance-create:"+ictoken, "Create"),
                    Button.danger("new-instance-cancel:"+ictoken, "Cancel"),
                    Button.secondary("new-instance-modal:"+ictoken, "Additional options...")
                ))
                .setEphemeral(true)
                .queue();
        }
        public final SlashOption<String> _vrchatLocation = SlashOption.ofString("vrchat-location", "Full VRChat instance launch link or worldId:instanceId location", true, null);
        public final SlashOption<Boolean> _closeHard = SlashOption.ofBool("close-hard", "Whether to kick users currently in the instance", false, null);
        public final SlashOption<Integer> _closeInMinutes = SlashOption.ofInt("close-in-minutes", "The number of minutes to wait before closing the instance", false, null).with($->$.setRequiredRange(1L, 600L));
        @SlashCmd("close-instance")
        @Desc("Closes an instance")
        public void closeInstance(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-location") String vrchatLocation, @SlashOpt("close-hard") Boolean closeHard, @SlashOpt("close-in-minutes") Integer closeInMinutes) throws Exception
        {
            io.github.vrchatapi.model.User vrcActor = this.actor(event, hook);
            if (vrcActor == null)
                return;
            
            if (!ScarletDiscordCommands.this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_instance_manage, event.getMember()))
            {
                hook.sendMessage("You do not have permission to close instances").setEphemeral(true).queue();
                return;
            }
            
            Location closeLocationModel;
            try
            {
                closeLocationModel = ScarletCloseInstanceInput.parse(vrchatLocation, ScarletDiscordCommands.this.discord.scarlet.vrc.groupId);
            }
            catch (IllegalArgumentException ex)
            {
                hook.sendMessage(ex.getMessage()).setEphemeral(true).queue();
                return;
            }
            OffsetDateTime closeAt = closeInMinutes == null ? null : OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(closeInMinutes.longValue());
            
            ScarletInstanceClose.Result result = ScarletInstanceClose.submit(
                ScarletDiscordCommands.this.discord.scarlet.vrc.client,
                closeLocationModel.world, closeLocationModel.instance, closeHard, closeAt);
            // Correlate with safe request/response diagnostics without logging a launch link or nonce.
            LOG.info("Instance-close outcome [{}]: {}, requestedHardClose={}, requestedClosedAt={}, effectiveClosedAt={}",
                result.diagnosticId, result.status,
                ScarletInstanceClose.hardCloseOption(result.requestedHardClose),
                ScarletInstanceClose.closeAtOption(result.requestedClosedAt), result.closedAt);
            hook.sendMessage(result.message()).setEphemeral(true).queue();
        }
        @SlashCmd("add-role")
        @Desc("Adds a VRChat Group Role")
        public void addRole(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser, @SlashOpt("vrchat-role") io.github.vrchatapi.model.GroupRole vrchatRole) throws Exception
        {
            if (vrchatUser == null)
            {
                hook.sendMessage("No VRChat user found for that input.").setEphemeral(true).queue();
                return;
            }
            GroupMember glm = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupMembership(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatUser.getId());
            if (glm != null)
            {
                List<String> roleIds = glm.getRoleIds();
                if (roleIds != null && roleIds.contains(vrchatRole.getId()))
                {
                    hook.sendMessageFormat("VRChat user [%s](%s) is already has the role [%s](%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrchatUser.getId()), vrchatRole.getName(), VrcWeb.Home.groupSettingsRoles(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatRole.getId())).setEphemeral(true).queue();
                    return;
                }
            }
            
            ScarletDiscordCommands.this.discord.scarlet.vrc.addGroupRole(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatUser.getId(), vrchatRole.getId());
            hook.sendMessageFormat("Adding the role [%s](%s) to [%s](%s)", vrchatRole.getName(), VrcWeb.Home.groupSettingsRoles(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatRole.getId()), vrchatUser.getDisplayName(), VrcWeb.Home.user(vrchatUser.getId())).setEphemeral(true).queue();
        }
        @SlashCmd("remove-role")
        @Desc("Removes a VRChat Group Role")
        public void removeRole(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser, @SlashOpt("vrchat-role") io.github.vrchatapi.model.GroupRole vrchatRole) throws Exception
        {
            if (vrchatUser == null)
            {
                hook.sendMessage("No VRChat user found for that input.").setEphemeral(true).queue();
                return;
            }
            GroupMember glm = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupMembership(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatUser.getId());
            if (glm != null)
            {
                List<String> roleIds = glm.getRoleIds();
                if (roleIds != null && !roleIds.contains(vrchatRole.getId()))
                {
                    hook.sendMessageFormat("VRChat user [%s](%s) is already lacks the role [%s](%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrchatUser.getId()), vrchatRole.getName(), VrcWeb.Home.groupSettingsRoles(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatRole.getId())).setEphemeral(true).queue();
                    return;
                }
            }
            
            ScarletDiscordCommands.this.discord.scarlet.vrc.removeGroupRole(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatUser.getId(), vrchatRole.getId());
            hook.sendMessageFormat("Removing the role [%s](%s) from [%s](%s)", vrchatRole.getName(), VrcWeb.Home.groupSettingsRoles(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, vrchatRole.getId()), vrchatUser.getDisplayName(), VrcWeb.Home.user(vrchatUser.getId())).setEphemeral(true).queue();
        }
    }

    // discord-user-info

    @SlashCmd("discord-user-info")
    @Ephemeral
    @Desc("Lists internal information for a specific Discord user")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void discordUserInfo(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("discord-user") Member discordUser) throws Exception
    {
        StringBuilder sb = new StringBuilder();
        
        String vrcId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(discordUser.getId());
        if (vrcId != null)
        {
            long within1day = System.currentTimeMillis() - 86400_000L;
            User sc = this.discord.scarlet.vrc.getUser(vrcId, within1day);
            if (sc != null && !this.discord.shouldRedact(vrcId, event.getMember().getId()))
            {
                sb.append(String.format("### Linked VRChat user:\n[%s](%s) `%s`\n", sc.getDisplayName(), VrcWeb.Home.user(vrcId), vrcId));
            }
        }
        
        sb.append("### Scarlet permissions:");
        for (ScarletPermission permission : ScarletPermission.values())
        {
            UniqueStrings roleSfs = this.discord.scarletPermission2roleSf.get(permission.id);
            sb.append('\n').append(permission.title).append(": from ");
            if (roleSfs == null || roleSfs.isEmpty())
            {
                sb.append("no roles");
            }
            else
            {
                String roleMentions = event
                    .getMember()
                    .getRoles()
                    .stream()
                    .filter(role -> roleSfs.contains(role.getId()))
                    .map(Role::getAsMention)
                    .collect(Collectors.joining(", "));
                if (roleMentions.isEmpty())
                {
                    sb.append("no roles");
                }
                else
                {
                    sb.append(roleMentions);
                }
            }
        }
        sb.append('\n');

        this.discord.interactions.new Pagination(event.getId(), sb).queue(hook);
    }

    // query-target-history

    @SlashCmd("query-target-history")
    @Ephemeral
    @Desc("Queries audit events targeting a specific VRChat user")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void queryTargetHistory(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser, @SlashOpt("days-back") int daysBack) throws Exception
    {
        String vrcId = VrcIds.getAsString_user(event.getOption("vrchat-user"));
        if (vrchatUser == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcId).setEphemeral(true).queue();
            return;
        }
        
        List<GroupAuditLogEntryUnknown> entries = this.discord.scarlet.vrc.auditQueryTargeting(vrcId, daysBack);
        if (entries == null)
        {
            hook.sendMessageFormat("Error querying audit target history for [%s](<%s>) (%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId), vrcId).setEphemeral(true).queue();
            return;
        }
        else if (entries.isEmpty())
        {
            hook.sendMessageFormat("No audit target history for [%s](<%s>) (%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId), vrcId).setEphemeral(true).queue();
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("VRChat audit target history for [").append(vrchatUser.getDisplayName()).append("](<").append(VrcWeb.Home.user).append(vrcId).append(">):");
        
        ScarletData.UserMetadata userMeta = this.discord.scarlet.data.userMetadata(vrcId);
        if (userMeta != null && userMeta.userSnowflake != null && !this.discord.shouldRedact(vrcId, event.getMember().getId()))
        {
            sb.append("\n### Linked Discord id:\n`").append(userMeta.userSnowflake).append("`");
        }
        
        sb.append("\n### Audit target events:");
        for (GroupAuditLogEntryUnknown entry : entries)
        {
            ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(entry.getId());
            String line = this.discord.auditHistoryLine(entry, auditEntryMeta, event.getMember().getId());
            if (line != null) sb.append(line);
        }
        sb.append("\nOnly locally verified events visible to you are included.");

        this.discord.interactions.new Pagination(event.getId(), sb).queue(hook);
    }

    // query-actor-history

    @SlashCmd("query-actor-history")
    @Ephemeral
    @Desc("Queries audit events actored by a specific VRChat user")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void queryActorHistory(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser, @SlashOpt("days-back") int daysBack) throws Exception
    {
        String vrcId = VrcIds.getAsString_user(event.getOption("vrchat-user"));
        if (vrchatUser == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcId).setEphemeral(true).queue();
            return;
        }
        
        List<GroupAuditLogEntryUnknown> entries = this.discord.scarlet.vrc.auditQueryActored(vrcId, daysBack);
        
        if (entries == null)
        {
            hook.sendMessageFormat("Error querying audit actor history for [%s](<%s>) (%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId), vrcId).setEphemeral(true).queue();
            return;
        }
        else if (entries.isEmpty()
            // Check is here to avoid side chain vulnerabilities
            || this.discord.shouldRedact(vrcId, event.getMember().getId()))
        {
            hook.sendMessageFormat("No audit actor history for [%s](<%s>) (%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId), vrcId).setEphemeral(true).queue();
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("VRChat audit actor history for [").append(vrchatUser.getDisplayName()).append("](<").append(VrcWeb.Home.user).append(vrcId).append(">):");
        
        ScarletData.UserMetadata userMeta = this.discord.scarlet.data.userMetadata(vrcId);
        if (userMeta != null && userMeta.userSnowflake != null)
        {
            sb.append("\n### Linked Discord id:\n`").append(userMeta.userSnowflake).append("`");
        }
        
        sb.append("\n### Audit actor events:");
        for (GroupAuditLogEntryUnknown entry : entries)
        {
            ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(entry.getId());
            String line = this.discord.auditHistoryLine(entry, auditEntryMeta, event.getMember().getId());
            if (line != null) sb.append(line);
        }
        sb.append("\nOnly locally verified events visible to you are included.");

        this.discord.interactions.new Pagination(event.getId(), sb).queue(hook);
    }

    // actor-moderation-summary

    @SlashCmd("actor-moderation-summary")
    @Ephemeral
    @Desc("Generates a summary of certain moderation actions by a specific user")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void actorModerationSummary(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser)
    {
        if (vrchatUser == null)
        {
            hook.sendMessage("No VRChat user found for that input.").setEphemeral(true).queue();
            return;
        }
        String vrcId = vrchatUser.getId();
        // Redaction gate (mirrors query-actor-history): do not expose another protected
        // actor's moderation counts to a requester who should not see them.
        if (this.discord.shouldRedact(vrcId, event.getMember().getId()))
        {
            hook.sendMessageFormat("No audit actor summary for [%s](<%s>) (%s)", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrcId), vrcId).setEphemeral(true).queue();
            return;
        }
        OffsetDateTime to = OffsetDateTime.now(),
                       from = to.minusYears(10L);
        
        Integer kicks = this.discord.scarlet.vrc.auditQueryCount(from, to, vrcId, "group.instance.kick", null),
                warns = this.discord.scarlet.vrc.auditQueryCount(from, to, vrcId, "group.instance.warn", null),
                bans = this.discord.scarlet.vrc.auditQueryCount(from, to, vrcId, "group.user.ban", null);
        
        StringBuilder sb = new StringBuilder();
        sb.append("VRChat audit actor summary for [").append(vrchatUser.getDisplayName()).append("](<").append(VrcWeb.Home.user).append(vrcId).append(">):");
        
        ScarletData.UserMetadata userMeta = this.discord.scarlet.data.userMetadata(vrcId);
        if (userMeta != null && userMeta.userSnowflake != null)
        {
            sb.append("\n### Linked Discord id:\n`").append(userMeta.userSnowflake).append("`");
        }
        
        sb.append("\nKicks: ").append(kicks == null ? "??? (query failed)" : kicks.toString())
            .append("\nWarns: ").append(warns == null ? "??? (query failed)" : warns.toString())
            .append("\nBans: ").append(bans == null ? "??? (query failed)" : bans.toString());
        
        hook.sendMessage(sb.toString()).setEphemeral(true).queue();
    }

    // moderation-summary

    @SlashCmd("moderation-summary")
    @Desc("Summarizes saved local moderation history in the background")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void moderationSummary(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("hours-back") int hoursBack)
    {
        if (hoursBack <= 0)
        {
            hook.sendMessage("hours-back must be greater than zero.").queue();
            return;
        }
        ScarletSummaryWorker.Submission result = this.discord.requestModSummary(OffsetDateTime.now(ZoneOffset.UTC),
            hoursBack, hook::sendMessageEmbeds, text -> hook.sendMessage(text).queue());
        if (result == ScarletSummaryWorker.Submission.BUSY)
            hook.sendMessage("A moderation summary is already running. Please wait for it to finish before requesting another.").queue();
        else if (result == ScarletSummaryWorker.Submission.CLOSED)
            hook.sendMessage("Scarlet is shutting down; the moderation summary was not started.").queue();
    }

    // outstanding-moderation

    @SlashCmd("outstanding-moderation")
    @Desc("Generates a list of outstanding moderation actions")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void outstandingModeration(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("hours-back") int hoursBack)
    {
        Message message = this.discord.emitOutstandingMod(this.discord.scarlet, OffsetDateTime.now(ZoneOffset.UTC), hoursBack, hook::sendMessageEmbeds);
        if (message == null)
            hook.sendMessage("No moderation events watched").queue();
    }

    // submit-attachments

    @MsgCmd("submit-attachments")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void submitAttachments(MessageContextInteractionEvent event)
    {
        Message message = event.getTarget();

        List<Button> buttons = new ArrayList<>();
        if (Features.EVIDENCE_ENABLED)
            buttons.add(Button.primary("submit-evidence:"+message.getId(), "Submit moderation evidence"));
        buttons.add(Button.primary("import-watched-groups:"+message.getId(), "Import watched groups"));

        event.reply("Select submission type")
            .addComponents(ActionRow.of(buttons))
            .setEphemeral(true)
            .queue();
    }

    public final SlashOption<Message.Attachment>
        _evidenceSubmission = SlashOption.ofAttachment("evidence-submission", "The submitted file", true),
        _evidenceSubmission2 = SlashOption.ofAttachment("evidence-submission-2", "Another submitted file", false),
        _evidenceSubmission3 = SlashOption.ofAttachment("evidence-submission-3", "Another submitted file", false),
        _evidenceSubmission4 = SlashOption.ofAttachment("evidence-submission-4", "Another submitted file", false),
        _evidenceSubmission5 = SlashOption.ofAttachment("evidence-submission-5", "Another submitted file", false);
    @FeatureGate("evidence.enabled")
    @SlashCmd("submit-evidence")
    @Desc("Submit attachments for evidence")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void submitEvidence(SlashCommandInteractionEvent event, InteractionHook hook,
        @SlashOpt("evidence-submission") Message.Attachment evidenceSubmission,
        @SlashOpt("evidence-submission-2") Message.Attachment evidenceSubmission2,
        @SlashOpt("evidence-submission-3") Message.Attachment evidenceSubmission3,
        @SlashOpt("evidence-submission-4") Message.Attachment evidenceSubmission4,
        @SlashOpt("evidence-submission-5") Message.Attachment evidenceSubmission5
    )
    {
        List<Message.Attachment> submissions = new ArrayList<>();
                                         submissions.add(evidenceSubmission);
        if (evidenceSubmission2 != null) submissions.add(evidenceSubmission2);
        if (evidenceSubmission3 != null) submissions.add(evidenceSubmission3);
        if (evidenceSubmission4 != null) submissions.add(evidenceSubmission4);
        if (evidenceSubmission5 != null) submissions.add(evidenceSubmission5);
        this.discord.discordUI.submitEvidence(event, hook, () -> submissions, "You must run this command in the relevant thread of an audit event message.");
    }

    // fix-audit-thread

    @MsgCmd("fix-audit-thread")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void fixAuditThread(MessageContextInteractionEvent event)
    {
        Message message = event.getTarget();
        String auditEntryId = message
            .getEmbeds()
            .stream()
            .map(MessageEmbed::getFooter)
            .filter(Objects::nonNull)
            .map(MessageEmbed.Footer::getText)
            .map(VrcIds.id_groupaudit::matcher)
            .filter(Matcher::find)
            .map(Matcher::group)
            .findFirst()
            .orElse(null)
            ;
        if (auditEntryId == null)
        {
            event.reply("Message does not appear to be an audit entry")
                .setEphemeral(true)
                .queue($ -> $.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
            return;
        }
        
        ScarletData.AuditEntryMetadata entryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        String requesterSf = event.getMember() == null ? null : event.getMember().getId();
        if (requesterSf == null || this.discord.auditThreadProblem(entryMeta, message, requesterSf) != null)
        {
            event.reply("This is not a currently approved Scarlet audit message you may repair. Nothing changed.")
                .setEphemeral(true)
                .queue($ -> $.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
            return;
        }
        
        String actorId = entryMeta.hasAuxActor() ? entryMeta.auxActorId : entryMeta.entry.getActorId();
        
        event.replyFormat("Attempting to fix entry `%s`", auditEntryId)
            .setEphemeral(true)
            .queue($ -> $.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
        this.discord.scarlet.exec.execute(() -> this.discord.emitUserModeration_thread(this.discord.scarlet, entryMeta, actorId, message, requesterSf));
    }

    // config-info

    @SlashCmd("config-info")
    @Desc("Shows information about the current configuration")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void configInfo(SlashCommandInteractionEvent event, InteractionHook hook) throws Exception
    {//event.deferReply().queue();
        StringBuilder sb = new StringBuilder();
        sb.append("Configuration Information:");
        
        String lastQueryEpoch = Long.toUnsignedString(this.discord.scarlet.settings.lastAuditQuery.getOrSupply().toEpochSecond()),
             lastRefreshEpoch = Long.toUnsignedString(this.discord.scarlet.settings.lastAuthRefresh.getOrSupply().toEpochSecond());
        
        sb.append("\n### Last Audit Query:")
            .append("\n<t:").append(lastQueryEpoch).append(":F>")
            .append(" (<t:").append(lastQueryEpoch).append(":R>)");

        sb.append("\n### Last Auth Refresh:")
            .append("\n<t:").append(lastRefreshEpoch).append(":F>")
            .append(" (<t:").append(lastRefreshEpoch).append(":R>)");
        
        OffsetDateTime nms = this.discord.scarlet.settings.nextModSummary.getOrNull();
        if (nms != null)
        {
            String nmsEpoch = Long.toUnsignedString(nms.toEpochSecond());
            sb.append("\n### Next Moderation Summary:")
                .append("\n<t:").append(nmsEpoch).append(":F>")
                .append(" (<t:").append(nmsEpoch).append(":R>)");
        }
        
        sb.append("\n### Auditing Channels:");
        for (GroupAuditType auditType : GroupAuditType.values())
        {
            String channelSf = this.discord.auditType2channelSf.get(auditType.id);
            sb.append("\n")
                .append(auditType.title)
                .append(" (")
                .append(auditType.id)
                .append("): ")
                .append(channelSf == null ? "unassigned" : ("<#"+channelSf+">"));
            UniqueStrings auxWhs = this.discord.auditType2scarletAuxWh.get(auditType.id);
            if (auxWhs != null && !auxWhs.isEmpty())
            {
                sb.append(auxWhs.stream().collect(Collectors.joining(", ", " (Auxiliary webhooks: ", ")")));
            }
        }
        
        sb.append("\n### Extended auditing Channels:");
        for (GroupAuditTypeEx auditTypeEx : GroupAuditTypeEx.values())
        {
            String channelSf = this.discord.auditExType2channelSf.get(auditTypeEx.id);
            sb.append("\n")
                .append(auditTypeEx.title)
                .append(" (")
                .append(auditTypeEx.id)
                .append("): ")
                .append(channelSf == null ? "unassigned" : ("<#"+channelSf+">"));
        }
        List<String> pages = MiscUtils.paginateOnLines(sb, 2000);
        for (String page : pages)
        {
//            event.getHook().sendMessage(page).setEphemeral(false).queue();
            hook.sendMessage(page).setEphemeral(false).queue();
        }
    }

    // settings

    static Modal avatarProviderConsentModal(String settingId)
    {
        return Modal.create("settings:" + settingId, net.sybyline.scarlet.ui.AvatarProviderConsentDialog.TITLE)
            .addComponents(TextDisplay.of(net.sybyline.scarlet.ui.AvatarProviderConsentDialog.DISCLOSURE),
                Label.of("Permission for selected avatar databases", StringSelectMenu.create(settingId)
                    .addOption("Keep disabled", "false")
                    .addOption("I agree to share group/account IDs and Scarlet version", "agree")
                    .setDefaultValues("false")
                    .setRequired(true)
                    .build()))
            .build();
    }

    static boolean applyAvatarProviderConsent(ScarletSettings.FileValuedConsentBoolean setting, String value)
    {
        if ("false".equals(value))
            return setting.set(Boolean.FALSE, "discord");
        return "agree".equals(value) && setting.setWithConsent(Boolean.TRUE, "discord", () -> true);
    }

    static String settingResult(FileValued<?> setting, boolean accepted)
    {
        if (!accepted)
            return "Invalid or unavailable value for "+setting.name()+". Nothing changed.";
        Object current = setting.get();
        return "Set "+setting.name()+" to "+(current instanceof String[] ? Arrays.toString((String[])current) : String.valueOf(current));
    }

    static StringSelectMenu settingChoiceMenu(String id, SelectOption[] options, String currentValue, boolean required)
    {
        StringSelectMenu.Builder menu = StringSelectMenu.create(id).addOptions(options)
            .setMinValues(required ? 1 : 0).setMaxValues(1).setRequired(required);
        for (SelectOption option : options)
            if (option.getValue().equals(currentValue))
            {
                menu.setDefaultValues(currentValue);
                break;
            }
        return menu.build();
    }

    @SlashCmd("settings")
    @Desc("Configures settings")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class Settings
    {
        void submitSettingModal(SlashCommandInteractionEvent event, FileValued<?> setting, Modal modal,
            DInteractions.ModalFlowImmediate immediate)
        {
            String groupId = ScarletDiscordCommands.this.discord.scarlet.vrc.groupId;
            ScarletDiscordCommands.this.discord.interactions.submitModalFlow(event, modal, immediate,
                interaction -> Objects.equals(groupId, ScarletDiscordCommands.this.discord.scarlet.vrc.groupId)
                    && ScarletDiscordCommands.this.discord.scarlet.settings.fileValuedSettings.get(setting.id()) == setting).queue();
        }

        public final SlashOption<ScarletSettings.FileValued<?>> _settingId = SlashOption.ofString("setting-id", "The setting", true, null, ScarletDiscordCommands.this.discord.scarlet.settings.fileValuedSettings::get, false, new SlashOptionsChoicesUnsanitized(() -> ScarletDiscordCommands.this.discord.scarlet.settings.fileValuedSettings.values().stream().map($ -> new Command.Choice($.name(), $.id())).toArray(Command.Choice[]::new), false));
        @SlashCmd("edit")
        @Desc("Configure a setting")
        public void edit(SlashCommandInteractionEvent event, @SlashOpt("setting-id") ScarletSettings.FileValued<?> settingId) throws Exception
        {
            if (settingId == null)
            {
                event.reply("That setting is no longer available. Choose a current setting from the suggestions. Nothing changed.").setEphemeral(true).queue();
                return;
            }
            if (settingId.visit(new FileValuedVisitor<Boolean>()
            {
                @Override
                public Boolean visitBasic(FileValued<?> fileValued)
                {
                    return Boolean.FALSE;
                }
                @Override
                public Boolean visitBoolean(FileValued<Boolean> fileValued, boolean defaultValue)
                {
                    boolean requiresConsent = fileValued instanceof ScarletSettings.FileValuedConsentBoolean;
                    Modal modal = requiresConsent ? avatarProviderConsentModal(fileValued.id()) : Modal.create("settings:"+fileValued.id(), fileValued.name())
                        .addComponents(Label.of(fileValued.name(), StringSelectMenu.create(fileValued.id())
                            .addOption("True", "true")
                            .addOption("False", "false")
                            .setDefaultValues(fileValued.get().toString())
                            .setRequired(true)
                            .build()))
                        .build();
                    DInteractions.ModalFlowImmediate immediate = interaction ->
                    {
                        String[] selected = interaction.getValues().stream().map(ModalMapping::getAsStringList).flatMap(List::stream).toArray(String[]::new);
                        if (selected.length != 1)
                        {
                            interaction.replyFormat("Must select exactly 1 value for %s", fileValued.name).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                            return;
                        }
                        String value = selected[0];
                        boolean accepted = requiresConsent
                            ? applyAvatarProviderConsent((ScarletSettings.FileValuedConsentBoolean)fileValued, value)
                            : ("true".equals(value) || "false".equals(value))
                                && fileValued.set(Boolean.parseBoolean(value), "discord");
                        interaction.reply(settingResult(fileValued, accepted)).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                    };
                    Settings.this.submitSettingModal(event, fileValued, modal, immediate);
                    return Boolean.TRUE;
                }
                @Override
                public Boolean visitIntegerRange(FileValued<Integer> fileValued, int defaultValue, int minimum, int maximum)
                {
                    Modal modal = Modal.create("settings:"+fileValued.id(), fileValued.name())
                        .addComponents(Label.of(String.format("An integer between %d and %d, inclusive", minimum, maximum), TextInput.create(fileValued.id(), TextInputStyle.SHORT)
                            .setValue(fileValued.get().toString())
                            .setMinLength(1)
                            .setMaxLength(32)
                            .setRequired(true)
                            .build()))
                        .build();
                    DInteractions.ModalFlowImmediate immediate = interaction ->
                    {
                        ModalMapping submitted = interaction.getValue(fileValued.id());
                        if (submitted == null)
                        {
                            interaction.replyFormat("Missing value for %s", fileValued.name).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                            return;
                        }
                        String value = submitted.getAsString();
                        int intValue;
                        try
                        {
                            intValue = Maths.clamp(Integer.parseInt(value), minimum, maximum);
                        }
                        catch (Exception ex)
                        {
                            interaction.replyFormat("%s must be an integer between %d and %d", fileValued.name, minimum, maximum).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                            return;
                        }
                        boolean accepted = fileValued.set(intValue, "discord");
                        interaction.reply(settingResult(fileValued, accepted)).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                    };
                    Settings.this.submitSettingModal(event, fileValued, modal, immediate);
                    return Boolean.TRUE;
                }
                @Override
                public <E extends Enum<E>> Boolean visitEnum(FileValued<E> fileValued, E defaultValue)
                {
                    E[] values = defaultValue.getDeclaringClass().getEnumConstants();
                    SelectOption[] options;
                    Function<String, E> parser;
                    Function<E, String> stringifier;
                    if (defaultValue instanceof DEnum.DEnumString || defaultValue instanceof DEnum.DEnumWrapper)
                    {
                        Class<E> clazz = defaultValue.getDeclaringClass();
                        options = DEnum.optionsRaw(clazz);
                        parser = $ -> DEnum.ofRaw(clazz, $);
                        stringifier = $ -> ((DEnum<?, String>)$).value();
                    }
                    else
                    {
                        Class<E> clazz = defaultValue.getDeclaringClass();
                        options = Stream.of(values).map($ -> SelectOption.of($.toString(), $.name())).toArray(SelectOption[]::new);
                        parser = $ -> Enum.valueOf(clazz, $);
                        stringifier = Enum::name;
                    }
                    if (values.length <= 125)
                    {
                        Modal modal;
                        DInteractions.ModalFlowImmediate immediate;
                        if (values.length <= 25)
                        {
                            modal = Modal.create("settings:"+fileValued.id(), fileValued.name())
                                .addComponents(Label.of(fileValued.name(), StringSelectMenu.create(fileValued.id())
                                    .addOptions(options)
                                    .setDefaultValues(stringifier.apply(fileValued.get()))
                                    .setRequired(true)
                                    .build()))
                                .build();
                            immediate = interaction ->
                            {
                                String[] selected = interaction.getValues().stream().map(ModalMapping::getAsStringList).flatMap(List::stream).toArray(String[]::new);
                                if (selected.length != 1)
                                {
                                    interaction.replyFormat("Must select exactly 1 value for %s", fileValued.name).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                                    return;
                                }
                                String value = selected[0];
                                E enumValue;
                                try
                                {
                                    enumValue = parser.apply(value);
                                }
                                catch (Exception ex)
                                {
                                    interaction.replyFormat("Invalid value %s for %s", value, fileValued.name).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                                    return;
                                }
                                boolean accepted = enumValue != null && fileValued.set(enumValue, "discord");
                                interaction.reply(settingResult(fileValued, accepted)).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                            };
                        }
                        else
                        {
                            Modal.Builder builder = Modal.create("settings:"+fileValued.id(), fileValued.name());
                            final int pages = (options.length + 24) / 25;
                            for (int page = 0; page < pages; page++)
                            {
                                SelectOption[] pageOptions = Arrays.copyOfRange(options, page * 25, Math.min(options.length, (page + 1) * 25));
                                int ordinal = page * 25 + 1;
                                String currentValue = stringifier.apply(fileValued.get());
                                StringSelectMenu.Builder pageMenu = StringSelectMenu.create(fileValued.id()+":"+page)
                                    .addOptions(pageOptions)
                                    .setMinValues(0)
                                    .setMaxValues(1)
                                    .setRequired(false);
                                for (SelectOption pageOption : pageOptions)
                                    if (pageOption.getValue().equals(currentValue))
                                    {
                                        pageMenu.setDefaultValues(currentValue);
                                        break;
                                    }
                                builder.addComponents(Label.of(String.format("%s (%d thru %d)", fileValued.name(), ordinal, ordinal + pageOptions.length - 1), pageMenu.build()));
                            }
                            modal = builder.build();
                            immediate = interaction ->
                            {
                                String[] selected = interaction.getValues().stream().map(ModalMapping::getAsStringList).flatMap(List::stream).toArray(String[]::new);
                                if (selected.length != 1)
                                {
                                    interaction.replyFormat("Must select exactly 1 value for %s", fileValued.name).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                                    return;
                                }
                                E enumValue;
                                try
                                {
                                    enumValue = parser.apply(selected[0]);
                                }
                                catch (Exception ex)
                                {
                                    interaction.replyFormat("Invalid value %s for %s", selected[0], fileValued.name).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                                    return;
                                }
                                boolean accepted = enumValue != null && fileValued.set(enumValue, "discord");
                                interaction.reply(settingResult(fileValued, accepted)).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                            };
                        }
                        Settings.this.submitSettingModal(event, fileValued, modal, immediate);
                    }
                    else
                    {
                        MessageEmbed[] embeds = Stream.of(options).map($ -> new EmbedBuilder().setTitle($.getLabel()).setDescription($.getValue()).build()).toArray(MessageEmbed[]::new);
                        event.deferReply(true).queue(hook -> ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, options, 25, (interaction, value) ->
                        {
                            E enumValue;
                            try
                            {
                                enumValue = parser.apply(value);
                            }
                            catch (Exception ex)
                            {
                                interaction.replyFormat("Invalid value %s for %s", value, fileValued.name).setEphemeral(true).queue(hook2 -> hook2.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                                return;
                            }
                            boolean accepted = enumValue != null && fileValued.set(enumValue, "discord");
                            interaction.reply(settingResult(fileValued, accepted)).setEphemeral(true).queue(hook2 -> hook2.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                        }).restrictTo(event.getUser().getId(), interaction -> ScarletDiscordCommands.this.discord.perms.checkSlash(
                            interaction.getGuild() == null ? null : interaction.getGuild().getMemberById(interaction.getUser().getId()), "settings edit")).queue(hook));
                    }
                    return Boolean.TRUE;
                }
                @Override
                public Boolean visitStringChoice(FileValued<String> fileValued, Supplier<Collection<String>> validValues)
                {
                    String[] values = validValues.get().toArray(new String[0]);
                    SelectOption[] options = Stream.of(values).map($ -> SelectOption.of($, $)).toArray(SelectOption[]::new);
                    if (values.length == 0)
                    {
                        event.replyFormat("No values are currently available for %s. Nothing changed.", fileValued.name()).setEphemeral(true).queue();
                        return Boolean.TRUE;
                    }
                    if (values.length <= 125)
                    {
                        Modal modal;
                        DInteractions.ModalFlowImmediate immediate;
                        if (values.length <= 25)
                        {
                            modal = Modal.create("settings:"+fileValued.id(), fileValued.name())
                                .addComponents(Label.of(fileValued.name(), settingChoiceMenu(fileValued.id(), options, fileValued.get(), true)))
                                .build();
                            immediate = interaction ->
                            {
                                String[] selected = interaction.getValues().stream().map(ModalMapping::getAsStringList).flatMap(List::stream).toArray(String[]::new);
                                if (selected.length != 1)
                                {
                                    interaction.replyFormat("Must select exactly 1 value for %s", fileValued.name).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                                    return;
                                }
                                String value = selected[0];
                                boolean accepted = fileValued.set(value, "discord");
                                interaction.reply(settingResult(fileValued, accepted)).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                            };
                        }
                        else
                        {
                            Modal.Builder builder = Modal.create("settings:"+fileValued.id(), fileValued.name());
                            final int pages = (options.length + 24) / 25;
                            for (int page = 0; page < pages; page++)
                            {
                                SelectOption[] pageOptions = Arrays.copyOfRange(options, page * 25, Math.min(options.length, (page + 1) * 25));
                                int ordinal = page * 25 + 1;
                                builder.addComponents(Label.of(String.format("%s (%d thru %d)", fileValued.name(), ordinal, ordinal + pageOptions.length - 1),
                                    settingChoiceMenu(fileValued.id()+":"+page, pageOptions, fileValued.get(), false)));
                            }
                            modal = builder.build();
                            immediate = interaction ->
                            {
                                String[] selected = interaction.getValues().stream().map(ModalMapping::getAsStringList).flatMap(List::stream).toArray(String[]::new);
                                if (selected.length != 1)
                                {
                                    interaction.replyFormat("Must select exactly 1 value for %s", fileValued.name).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                                    return;
                                }
                                boolean accepted = fileValued.set(selected[0], "discord");
                                interaction.reply(settingResult(fileValued, accepted)).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                            };
                        }
                        Settings.this.submitSettingModal(event, fileValued, modal, immediate);
                    }
                    else
                    {
                        MessageEmbed[] embeds = Stream.of(values).map($ -> new EmbedBuilder().setTitle($).setDescription($).build()).toArray(MessageEmbed[]::new);
                        event.deferReply(true).queue(hook -> ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, options, 25, (interaction, value) ->
                        {
                            boolean accepted = fileValued.set(value, "discord");
                            interaction.reply(settingResult(fileValued, accepted)).setEphemeral(true).queue(hook2 -> hook2.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                        }).restrictTo(event.getUser().getId(), interaction -> ScarletDiscordCommands.this.discord.perms.checkSlash(
                            interaction.getGuild() == null ? null : interaction.getGuild().getMemberById(interaction.getUser().getId()), "settings edit")).queue(hook));
                    }
                    return Boolean.TRUE;
                }
                @Override
                public Boolean visitStringPattern(FileValued<String> fileValued, String pattern, boolean lenient)
                {
                    Modal modal = Modal.create("settings:"+fileValued.id(), fileValued.name())
                        .addComponents(Label.of(pattern != null ? "A string of the pattern "+pattern : fileValued.name(), TextInput.create(fileValued.id(), TextInputStyle.SHORT)
                            .setValue(fileValued.get())
                            .setRequired(true)
                            .build()))
                        .build();
                    DInteractions.ModalFlowImmediate immediate = interaction ->
                    {
                        String value = interaction.getValue(fileValued.id()).getAsString();
                        if (fileValued.set(value, "discord"))
                        {
                            interaction.reply(settingResult(fileValued, true)).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                        }
                        else
                        {
                            interaction.replyFormat("Invalid value %s for %s", value, fileValued.name).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                        }
                    };
                    Settings.this.submitSettingModal(event, fileValued, modal, immediate);
                    return Boolean.TRUE;
                }
                @Override
                public Boolean visitStringArrayPattern(FileValued<String[]> fileValued, String pattern, boolean lenient)
                {
                    String[] currentValues = fileValued.get();
                    Modal modal = Modal.create("settings:"+fileValued.id(), fileValued.name())
                        .addComponents(Label.of(pattern != null ? "Strings of the pattern "+pattern : fileValued.name(), TextInput.create(fileValued.id(), TextInputStyle.PARAGRAPH)
                            .setValue(String.join("\n", currentValues))
                            .setRequired(true)
                            .build()))
                        .build();
                    DInteractions.ModalFlowImmediate immediate = interaction ->
                    {
                        String value = interaction.getValue(fileValued.id()).getAsString();
                        if (fileValued.set(value.split("\\R"), "discord"))
                        {
                            interaction.reply(settingResult(fileValued, true)).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                        }
                        else
                        {
                            interaction.replyFormat("Invalid value %s for %s", value, fileValued.name).setEphemeral(true).queue(hook -> hook.deleteOriginal().queueAfter(5_000L, TimeUnit.MILLISECONDS));
                        }
                    };
                    Settings.this.submitSettingModal(event, fileValued, modal, immediate);
                    return Boolean.TRUE;
                }
                @Override
                public Boolean visitVoid(FileValued<Void> fileValued, Runnable task)
                {
                    return Boolean.FALSE;
                }
            }).booleanValue())
            {
                ; // noop
            }
            else
            {
                event.replyFormat("The setting %s currently can't be configured via Discord.", settingId.name()).setEphemeral(true).queue();
            }
        }
    }

    // config-set

    static OffsetDateTime nextSummaryTime(ZoneId zone, LocalTime time, ZonedDateTime now)
    {
        ZonedDateTime localNow = now.withZoneSameInstant(zone);
        ZonedDateTime next = localNow.toLocalDate().atTime(time).atZone(zone);
        if (next.isBefore(localNow))
            next = localNow.toLocalDate().plusDays(1L).atTime(time).atZone(zone);
        return next.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    static SlashOption<Integer> integerSettingOption(String id, String description, ScarletSettings.FileValuedIntRange setting)
    {
        return SlashOption.ofInt(id, description, true, setting.def, setting.min, setting.max);
    }

    @SlashCmd("config-set")
    @Desc("Configures miscellaneous settings")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class ConfigSet
    {
        public final SlashOption<ZoneId> _timeZoneId = SlashOption.ofString("time-zone-id", "The time zone", true, null, ZoneId::of, true, new SlashOptionStrings(ZoneId.getAvailableZoneIds().stream().sorted(), true));
        public final SlashOption<String> _timeOfDay = SlashOption.ofString("time-of-day", "The time of day", true, null, this::_timeOfDay);
        void _timeOfDay(CommandAutoCompleteInteractionEvent event)
        {
            String value = event.getFocusedOption().getValue().trim();
            if (value.isEmpty())
            {
                event.replyChoiceStrings(IntStream.range(0, 24).mapToObj($ -> String.format("%02d:00", $)).toArray(String[]::new)).queue();
                return;
            }
            Matcher m = Pattern.compile("(?<h>\\d\\d)(:(?<m>\\d\\d)?)?").matcher(value);
            if (m.matches())
            {
                String h = m.group("h");
                List<String> strings = IntStream.range(0, 12).mapToObj($ -> String.format("%s:%02d", h, $*5)).collect(Collectors.toList());
                if (!strings.contains(value))
                    strings.add(0, value);
                event.replyChoiceStrings(strings).queue();
                return;
            }
            event.replyChoiceStrings().queue();
        }
        OffsetDateTime nextTimeOfDay(SlashCommandInteractionEvent event, @SlashOpt("time-zone-id") ZoneId timeZoneId, @SlashOpt("time-of-day") String timeOfDay)
        {

            if (timeZoneId == null)
                try { timeZoneId = ZoneOffset.of(event.getOption("time-zone-id", OptionMapping::getAsString)); }
                catch (Exception zoneEx) { timeZoneId = null; }
            if (timeZoneId == null)
            {
                event.replyFormat("Invalid time zone/offset `%s`. Nothing changed.", event.getOption("time-zone-id", OptionMapping::getAsString)).setEphemeral(true).queue();
                return null;
            }
            LocalTime time;
            try
            {
                time = LocalTime.parse(timeOfDay);
            }
            catch (DateTimeParseException | NullPointerException dtpex)
            {
                event.replyFormat("Invalid time of day `%s`. Nothing changed.", timeOfDay).setEphemeral(true).queue();
                return null;
            }
            return nextSummaryTime(timeZoneId, time, ZonedDateTime.now(timeZoneId));
        }
        @SlashCmd("moderation-summary")
        @Desc("Moderation summary settings")
        public class ModSummary
        {
            @SlashCmd("time-of-day")
            @Desc("Set what time of day to generate a summary of moderation activity")
            public void modSummaryTimeOfDay(SlashCommandInteractionEvent event, @SlashOpt("time-zone-id") ZoneId timeZoneId, @SlashOpt("time-of-day") String timeOfDay) throws Exception
            {
                OffsetDateTime offset = nextTimeOfDay(event, timeZoneId, timeOfDay);
                if (offset == null)
                    return;
                ScarletDiscordCommands.this.discord.scarlet.settings.nextModSummary.set(offset);
                String epochNext = Long.toUnsignedString(offset.toEpochSecond());
                event.replyFormat("Set mod summary generation time: next summary at <t:%s:f>", epochNext).setEphemeral(true).queue();
            }
        }
        @SlashCmd("suggested-moderation")
        @Desc("Suggested moderation settings")
        public class SuggestedMod
        {
            public final SlashOption<Integer> _kickCount = integerSettingOption("kick-count", "The number of instance kicks to trigger suggested moderation", ScarletDiscordCommands.this.discord.scarlet.settings.heuristicKickCount);
            public final SlashOption<Integer> _periodDays = integerSettingOption("period-days", "The number of days of history for which to consider instance kicks", ScarletDiscordCommands.this.discord.scarlet.settings.heuristicPeriodDays);
            @SlashCmd("kick-count")
            @Desc("The kick count")
            public void kickCount(SlashCommandInteractionEvent event, @SlashOpt("kick-count") int kickCount) throws Exception
            {
                FileValued<Integer> setting = ScarletDiscordCommands.this.discord.scarlet.settings.heuristicKickCount;
                event.reply(settingResult(setting, setting.set(kickCount, "discord"))).setEphemeral(true).queue();
            }
            @SlashCmd("period-days")
            @Desc("The period")
            public void periodDays(SlashCommandInteractionEvent event, @SlashOpt("period-days") int periodDays) throws Exception
            {
                FileValued<Integer> setting = ScarletDiscordCommands.this.discord.scarlet.settings.heuristicPeriodDays;
                event.reply(settingResult(setting, setting.set(periodDays, "discord"))).setEphemeral(true).queue();
            }
        }
        @SlashCmd("outstanding-moderation")
        @Desc("Outstanding moderation settings")
        public class OutstandingMod
        {
            public final SlashOption<Integer> _periodDays = integerSettingOption("period-days", "The number of days of history for which to consider moderation events", ScarletDiscordCommands.this.discord.scarlet.settings.outstandingPeriodDays);
            @SlashCmd("period-days")
            @Desc("The period")
            public void periodDays(SlashCommandInteractionEvent event, @SlashOpt("period-days") int periodDays) throws Exception
            {
                FileValued<Integer> setting = ScarletDiscordCommands.this.discord.scarlet.settings.outstandingPeriodDays;
                event.reply(settingResult(setting, setting.set(periodDays, "discord"))).setEphemeral(true).queue();
            }
            @SlashCmd("time-of-day")
            @Desc("Set what time of day to generate a summary of outstanding moderation activity")
            public void modSummaryTimeOfDay(SlashCommandInteractionEvent event, @SlashOpt("time-zone-id") ZoneId timeZoneId, @SlashOpt("time-of-day") String timeOfDay) throws Exception
            {
                OffsetDateTime offset = nextTimeOfDay(event, timeZoneId, timeOfDay);
                if (offset == null)
                    return;
                ScarletDiscordCommands.this.discord.scarlet.settings.nextOutstandingMod.set(offset);
                String epochNext = Long.toUnsignedString(offset.toEpochSecond());
                event.replyFormat("Set outstanding mod summary generation time: next summary at <t:%s:f>", epochNext).setEphemeral(true).queue();
            }
        }
        @SlashCmd("report-template")
        @Desc("Report template settings")
        @FeatureGate("vrchat_reports.enabled")
        public class ReportTemplate
        {
            @SlashCmd("view-report-template")
            @Desc("View the report template")
            public void viewReportTemplate(SlashCommandInteractionEvent event) throws Exception
            {
                String contents = ScarletDiscordCommands.this.discord.scarlet.vrcReport.get();
                event.reply(MiscUtils.maybeEllipsis(4000, contents))
                    .setEphemeral(true)
                    .queue();
            }
            @SlashCmd("download-report-template")
            @Desc("Download the report template")
            public void downloadReportTemplate(SlashCommandInteractionEvent event) throws Exception
            {
                File templateFile = ScarletDiscordCommands.this.discord.scarlet.vrcReport.templateFile();
                event.replyFiles(FileUpload.fromData(templateFile))
                    .setEphemeral(true)
                    .queue();
            }
            @SlashCmd("view-report-template-format")
            @Desc("View the report template format parameters")
            public void viewReportTemplateFormat(SlashCommandInteractionEvent event) throws Exception
            {
                event.reply(MiscUtils.maybeEllipsis(4000, ScarletVRChatReportTemplate.HELP))
                    .setEphemeral(true)
                    .queue();
            }
            @SlashCmd("edit-report-template")
            @Desc("Edit the report template")
            public void editReportTemplate(SlashCommandInteractionEvent event) throws Exception
            {
                String content = ScarletDiscordCommands.this.discord.scarlet.vrcReport.get();
                ModalTopLevelComponent[] modalComponents;
                if (4000 - content.length() >= ScarletVRChatReportTemplate.HELP.length())
                {
                    modalComponents = new ModalTopLevelComponent[2];
                    modalComponents[1] = TextDisplay.of(ScarletVRChatReportTemplate.HELP);
                }
                else
                {
                    modalComponents = new ModalTopLevelComponent[1];
                }
                modalComponents[0] = Label.of("Report Template", TextInput.create("report-template", TextInputStyle.PARAGRAPH)
                    .setValue(MiscUtils.blank(content) ? null : MiscUtils.maybeEllipsis(4000, content))
                    .build());
                event.replyModal(Modal.create("edit-report-template", "Edit report template")
                    .addComponents(modalComponents)
                    .build())
                .queue();
            }
            public final SlashOption<Message.Attachment> reportTemplate = SlashOption.ofAttachment("report-template", "The updated report template", true);
            @SlashCmd("upload-report-template")
            @Desc("Upload the report template")
            public void uploadReportTemplate(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("report-template") Message.Attachment reportTemplate)
            {
                String requesterSf = event.getUser().getId(),
                       requesterDisplayName = event.getUser().getEffectiveName(),
                       fileName = reportTemplate.getFileName(),
                       attachmentUrl = reportTemplate.getUrl();
                
                LOG.info(String.format("%s (<@%s>) Uploading report template: %s", requesterDisplayName, requesterSf, fileName));
                try (Reader reader = new InputStreamReader(HttpURLInputStream.get(attachmentUrl, HttpURLInputStream.PUBLIC_ONLY)))
                {
                    String contents = new BufferedReader(reader).lines().collect(Collectors.joining("\n"));
                    if (ScarletDiscordCommands.this.discord.scarlet.vrcReport.trySet(contents))
                    {
                        LOG.info("Successfully uploaded report template");
                        hook.sendMessageFormat("Successfully uploaded report template").setEphemeral(true).queue();
                    }
                    else
                    {
                        LOG.warn("Failed to upload report template: empty content");
                        hook.sendMessageFormat("Failed to upload report template: empty content").setEphemeral(true).queue();
                    }
                }
                catch (Exception ex)
                {
                    LOG.error("Exception uploading report template from attachment: "+fileName, ex);
                    hook.sendMessageFormat("Exception while uploading %s: %s", fileName, ex).setEphemeral(true).queue();
                }
            }
        }
    }

    // server-restart

    @SlashCmd("server-restart")
    @Desc("Restarts the Scarlet application")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class ServerRestart
    {
        @SlashCmd("restart-now")
        @Desc("Restarts the application now")
        public void restartNow(SlashCommandInteractionEvent event)
        {
            String feedback;
            if (ScarletDiscordCommands.this.discord.scarlet.restart())
            {
                feedback = "Restarting...";
            }
            else
            {
                feedback = "Shutdown already queued";
            }
            event.reply(feedback).setEphemeral(false).queue();
        }
        public final SlashOption<String> _targetVersion = SlashOption.ofString("target-version", "The version of Scarlet to which to update", true, null, this::_targetVersion);
        void _targetVersion(CommandAutoCompleteInteractionEvent event) {
            DInteractions.SlashOptionStringsUnsanitized.autocomplete(event, ScarletDiscordCommands.this.discord.scarlet.allVersions, true);
        }
        @SlashCmd("update-now")
        @Desc("Updates the application now")
        public void updateNow(SlashCommandInteractionEvent event, @SlashOpt("target-version") String targetVersion)
        {
            String feedback = "Unknown error updating";
            switch (ScarletDiscordCommands.this.discord.scarlet.update(targetVersion))
            {
            case 1: {
                feedback = String.format("Invalid version syntax `%s`", targetVersion);
            } break;
            case 2: {
                feedback = String.format("Nonexistant version `%s`, manually set the contents of `scarlet.version` to this version if you want to actually do this", targetVersion);
            } break;
            case 3: {
                feedback = String.format("Local IO failed for target version `%s`", targetVersion);
            } break;
            case 0: {
                feedback = "Updating...";
            } break;
            case -1: {
                feedback = "Shutdown already queued!";
            } break;
            }
            event.reply(feedback).setEphemeral(true).queue();
            
        }
    }

    // export-log

    public final SlashOption<String> _fileName = SlashOption.ofString("file-name", "The name of the log file", false, null, this::_fileName);
    void _fileName(CommandAutoCompleteInteractionEvent event) { event.replyChoiceStrings(this.discord.scarlet.last25logs).queue(); }
    @SlashCmd("export-log")
    @Desc("Attaches a log file")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void exportLog(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("file-name") String fileName) throws Exception
    {
        if (fileName == null)
        {
            String[] last25logs = this.discord.scarlet.last25logs;
            if (last25logs.length == 0)
            {
                hook.sendMessage("No log files detected, strange...").setEphemeral(false).queue();
                return;
            }
            fileName = last25logs[0];
        }
        
        if (!ScarletLogger.lfpattern.matcher(fileName).matches())
        {
            hook.sendMessage("Invalid log file name").setEphemeral(false).queue();
            return;
        }
        
        File logs = new File(Scarlet.dir, "logs"),
             target = new File(logs, fileName);
        // Defense in depth against path traversal: the resolved target must stay inside logs/.
        try
        {
            if (!target.getCanonicalPath().startsWith(logs.getCanonicalPath() + File.separator))
            {
                hook.sendMessage("Invalid log file name").setEphemeral(false).queue();
                return;
            }
        }
        catch (java.io.IOException pathEx)
        {
            hook.sendMessage("Invalid log file name").setEphemeral(false).queue();
            return;
        }
        
        if (!target.isFile())
        {
            hook.sendMessage("That log file does not exist").setEphemeral(false).queue();
            return;
        }
        
        hook.sendMessageFormat("`%s`", fileName).addFiles(FileUpload.fromData(target)).setEphemeral(false).queue();
    }

    // set-audit-channel

    static String textChannelConfigurationProblem(Channel channel, String guildId)
    {
        if (channel == null)
            return null;
        if (!(channel instanceof TextChannel) || channel.getType() != ChannelType.TEXT)
            return "Choose a standard Discord text channel. Other channel types are not supported by this log destination. Nothing changed.";
        TextChannel text = (TextChannel)channel;
        if (text.getGuild() == null || !Objects.equals(guildId, text.getGuild().getId()))
            return "The channel must belong to Scarlet's configured Discord server. Nothing changed.";
        Member bot = text.getGuild().getSelfMember();
        if (bot == null || !bot.hasPermission(text, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS))
            return "Scarlet needs View Channel, Send Messages, and Embed Links in the selected channel. Fix those Discord permissions, then try again. Nothing changed.";
        return null;
    }

    boolean checkTextChannelConfiguration(Channel channel, InteractionHook hook)
    {
        String problem = textChannelConfigurationProblem(channel, this.discord.guildSf);
        if (problem == null)
            return true;
        hook.sendMessage(problem).setEphemeral(true).queue();
        return false;
    }

    @SlashCmd("set-audit-channel")
    @Desc("Sets a given text channel as the channel certain audit event types use")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void setAuditChannel(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("audit-event-type") GroupAuditType auditEventType, @SlashOpt("discord-text-channel") Channel textChannel) throws Exception
    {
        if (!this.checkTextChannelConfiguration(textChannel, hook))
            return;
        if (textChannel == null)
        {
            this.discord.setAuditChannel(auditEventType, null);
            hook.sendMessageFormat("Unassociating VRChat group audit log event type %s (%s) from any channels", auditEventType.title, auditEventType.id).setEphemeral(true).queue();
        }
        else
        {
            this.discord.setAuditChannel(auditEventType, textChannel);
            hook.sendMessageFormat("Associating VRChat group audit log event type %s (%s) with channel <#%s>", auditEventType.title, auditEventType.id, textChannel.getId()).setEphemeral(true).queue();
        }
    }

    // set-audit-aux-webhooks

    @FeatureGate("aux_webhooks.enabled")
    @SlashCmd("set-audit-aux-webhooks")
    @Desc("Sets the given webhooks as the webhooks certain audit event types use")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void setAuditAuxWebhooks(SlashCommandInteractionEvent event, @SlashOpt("audit-event-type") GroupAuditType auditEventType) throws Exception
    {
        if (this.discord.scarletAuxWh2webhookUrl.isEmpty())
        {
            event.reply("No auxiliary webhooks are configured. Add one with /aux-webhooks add first. Nothing changed.").setEphemeral(true).queue();
            return;
        }
        if (this.discord.scarletAuxWh2webhookUrl.size() > 25
            || this.discord.scarletAuxWh2webhookUrl.keySet().stream().anyMatch(id -> id == null || id.isEmpty() || id.length() > 100))
        {
            event.reply("This selector supports at most 25 configured webhook names, each 1–100 characters. Remove unused or invalid names with /aux-webhooks remove first. No routes were changed.").setEphemeral(true).queue();
            return;
        }
        SelectOption[] options = this.discord.scarletAuxWh2webhookUrl
            .keySet()
            .stream()
            .sorted()
            .map($ -> SelectOption.of($, $))
            .toArray(SelectOption[]::new);
        UniqueStrings scarletAuxWhs = this.discord.auditType2scarletAuxWh.get(auditEventType.id);
        String[] defaultOptions = scarletAuxWhs == null ? new String[0] : scarletAuxWhs
            .strings()
            .stream()
            .filter(this.discord.scarletAuxWh2webhookUrl::containsKey)
            .toArray(String[]::new);
        
        event.replyComponents(ActionRow.of(StringSelectMenu.create("set-audit-aux-webhooks:"+auditEventType.id)
            .addOptions(options)
            .setDefaultValues(defaultOptions)
            .setMinValues(0)
            .setMaxValues(options.length)
            .build())).setEphemeral(true).queue();
    }

    // aux-webhooks

    @FeatureGate("aux_webhooks.enabled")
    @SlashCmd("aux-webhooks")
    @Desc("Configures auxiliary webhooks")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class AuxWebhooks
    {
        @SlashCmd("list")
        @Desc("Lists all auxiliary webhooks")
        @Ephemeral
        public void list(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            MessageEmbed[] embeds = ScarletDiscordCommands.this.discord.scarletAuxWh2webhookUrl
                .entrySet()
                .stream()
                .map($ -> new EmbedBuilder()
                    .setTitle($.getKey())
                    .addField("Webhook", ScarletAuditPrivacy.redactedWebhook($.getValue()), false)
                    .build())
                .toArray(MessageEmbed[]::new)
            ;
            
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }
        public final SlashOption<String> _auxWebhookIdNA = SlashOption.ofString("aux-webhook-id", "The internal id to give the webhook", true, null).with(data -> data.setRequiredLength(1, 100));
        public final SlashOption<String> _auxWebhookUrl = SlashOption.ofString("aux-webhook-url", "The url of the webhook", true, null);
        @SlashCmd("add")
        @Desc("Adds an auxiliary webhook")
        public void add(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("_auxWebhookIdNA") String auxWebhookId, @SlashOpt("aux-webhook-url") String auxWebhookUrl)
        {
            if (auxWebhookId == null || auxWebhookId.trim().isEmpty() || auxWebhookId.length() > 100)
            {
                hook.sendMessage("Webhook names must be 1–100 characters and cannot be blank. Nothing changed.").setEphemeral(true).queue();
                return;
            }
            String prevWebhookUrl = ScarletDiscordCommands.this.discord.scarletAuxWh2webhookUrl.get(auxWebhookId);
            if (prevWebhookUrl != null)
            {
                hook.sendMessage("There is already an auxiliary webhook with that id").setEphemeral(true).queue();
                return;
            }
            
            if (!Webhook.WEBHOOK_URL.matcher(auxWebhookUrl).matches())
            {
                hook.sendMessage("Invalid webhook url").setEphemeral(true).queue();
                return;
            }
            
            ScarletDiscordCommands.this.discord.scarletAuxWh2webhookUrl.put(auxWebhookId, auxWebhookUrl);
            ScarletDiscordCommands.this.discord.save();
            hook.sendMessage("Added auxiliary webhook").setEphemeral(true).queue();
        }
        public final SlashOption<String> _auxWebhookId = SlashOption.ofString("aux-webhook-id", "The internal id of the webhook", true, null, this::_auxWebhookId);
        void _auxWebhookId(CommandAutoCompleteInteractionEvent event) {
            DInteractions.SlashOptionStringsUnsanitized.autocomplete(event, ScarletDiscordCommands.this.discord.scarletAuxWh2webhookUrl.keySet().toArray(new String[0]), true);
        }
        @SlashCmd("remove")
        @Desc("Removes an auxiliary webhook")
        public void remove(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("_auxWebhookId") String auxWebhookId)
        {
            String prevWebhookUrl = ScarletDiscordCommands.this.discord.scarletAuxWh2webhookUrl.remove(auxWebhookId);
            if (prevWebhookUrl == null)
            {
                hook.sendMessage("There is no auxiliary webhook with that id").setEphemeral(true).queue();
                return;
            }
            
            ScarletDiscordCommands.this.discord.auditType2scarletAuxWh.values().forEach(names -> names.remove(auxWebhookId));
            ScarletDiscordCommands.this.discord.save();
            hook.sendMessage("Removed auxiliary webhook").setEphemeral(true).queue();
        }
    }

    // set-audit-ex-channel

    @SlashCmd("set-audit-ex-channel")
    @Desc("Sets a given text channel as the channel certain extended event types use")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void setAuditExChannel(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("audit-ex-event-type") GroupAuditTypeEx auditEventTypeEx, @SlashOpt("discord-text-channel") Channel textChannel) throws Exception
    {
        if (!this.checkTextChannelConfiguration(textChannel, hook))
            return;
        if (textChannel == null)
        {
            this.discord.setAuditExChannel(auditEventTypeEx, null);
            hook.sendMessageFormat("Unassociating VRChat extended group audit event type %s (%s) from any channels", auditEventTypeEx.title, auditEventTypeEx.id).setEphemeral(true).queue();
        }
        else
        {
            this.discord.setAuditExChannel(auditEventTypeEx, textChannel);
            hook.sendMessageFormat("Associating VRChat extended group audit event type %s (%s) with channel <#%s>", auditEventTypeEx.title, auditEventTypeEx.id, textChannel.getId()).setEphemeral(true).queue();
        }
    }

    // set-audit-secret-channel

    @SlashCmd("set-audit-secret-channel")
    @Desc("Sets a given text channel as the secret channel certain audit event types use")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void setAuditSecretChannel(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("audit-event-type") GroupAuditType auditEventType, @SlashOpt("discord-text-channel") Channel textChannel) throws Exception
    {
        if (!this.checkTextChannelConfiguration(textChannel, hook))
            return;
        if (textChannel == null)
        {
            this.discord.setAuditSecretChannel(auditEventType, null);
            hook.sendMessageFormat("Unassociating VRChat group audit log event type %s (%s) from any secret channels", auditEventType.title, auditEventType.id).setEphemeral(true).queue();
        }
        else
        {
            this.discord.setAuditSecretChannel(auditEventType, textChannel);
            hook.sendMessageFormat("Associating VRChat group audit log event type %s (%s) with secret channel <#%s>", auditEventType.title, auditEventType.id, textChannel.getId()).setEphemeral(true).queue();
        }
    }

    // set-audit-secret-ex-channel

    @SlashCmd("set-audit-secret-ex-channel")
    @Desc("Sets a given text channel as the secret channel certain extended event types use")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void setAuditSecretExChannel(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("_auditSecretExEventType") GroupAuditTypeEx auditEventTypeEx, @SlashOpt("discord-text-channel") Channel textChannel) throws Exception
    {
        if (auditEventTypeEx == null || (textChannel != null && !auditEventTypeEx.supportsSecretRouting()))
        {
            hook.sendMessage("Nothing changed: this event does not support a separate secret destination. "
                + "Choose Instance Monitor, Action Failure, or Live Player List. "
                + "To clear an old unsupported assignment, select its legacy entry and omit the channel.").setEphemeral(true).queue();
            return;
        }
        if (!this.checkTextChannelConfiguration(textChannel, hook))
            return;
        if (textChannel == null)
        {
            this.discord.setAuditExSecretChannel(auditEventTypeEx, null);
            hook.sendMessageFormat("Unassociating VRChat extended group audit event type %s (%s) from any secret channels", auditEventTypeEx.title, auditEventTypeEx.id).setEphemeral(true).queue();
        }
        else
        {
            this.discord.setAuditExSecretChannel(auditEventTypeEx, textChannel);
            hook.sendMessageFormat("Associating VRChat extended group audit event type %s (%s) with secret channel <#%s>", auditEventTypeEx.title, auditEventTypeEx.id, textChannel.getId()).setEphemeral(true).queue();
        }
    }

    // set-voice-channel

    @SlashCmd("set-voice-channel")
    @Desc("Sets a given voice channel as the channel in which to announce TTS messages")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void setVoiceChannel(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("discord-voice-channel") Channel voiceChannel) throws Exception
    {
        if (voiceChannel == null)
        {
            this.discord.audioChannelSf = null;
            hook.sendMessage("Disabling/disconnecting from voice channel").setEphemeral(true).queue();
            this.discord.audio.updateChannel();
            this.discord.save();
        }
        else if (voiceChannel.getType() != ChannelType.VOICE)
        {
            hook.sendMessageFormat("The channel %s isn't a voice channel", voiceChannel.getName()).setEphemeral(true).queue();
        }
        else
        {
            this.discord.audioChannelSf = voiceChannel.getId();
            hook.sendMessageFormat("Enabling/connecting from voice channel <#%s>", voiceChannel.getId()).setEphemeral(true).queue();
            this.discord.audio.updateChannel();
            this.discord.save();
        }
    }

    // set-tts-voice

    public final SlashOption<String> _voiceName = SlashOption.ofString("voice-name", "The name of the installed voice to use", true, null, new DInteractions.SlashOptionStringsUnsanitized(this::_voiceName, true));
    String[] _voiceName() { List<String> names = this.discord.scarlet.getTtsService().getInstalledVoices(); return names.toArray(new String[names.size()]); }
    @SlashCmd("set-tts-voice")
    @Desc("Selects which TTS voice is used to make announcements")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void setTtsVoice(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("voice-name") String voiceName) throws Exception
    {
        if (!this.discord.scarlet.getTtsService().getInstalledVoices().stream().anyMatch(voiceName::equals))
        {
            hook.sendMessageFormat("TTS voice `%s` is not installed on this system", voiceName).setEphemeral(true).queue();
            return;
        }
        
        this.discord.scarlet.eventListener.ttsVoiceName.set(voiceName, "discord");
        
        hook.sendMessageFormat("Setting TTS voice to `%s`", voiceName).setEphemeral(true).queue();
    }

    // vrchat-animated-emoji

    @FeatureGate("animated_emoji.enabled")
    @SlashCmd("vrchat-animated-emoji")
    @Desc("Generates a VRChat animated emoji spritesheet from a gif")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class VrchatAnimatedEmoji
    {
        public final SlashOption<String> gifUrl = SlashOption.ofString("gif-url", "The URL of the gif", true, null);
        public final SlashOption<Message.Attachment> gifFile = SlashOption.ofAttachment("gif-file", "The gif file", true);
        @SlashCmd("from-url")
        @Desc("Generates a VRChat animated emoji spritesheet from the gif from this URL")
        public void fromUrl(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("gif-url") String gifUrl) throws Exception
        {
            this.emoji(hook, gifUrl, Gifs.VrcSpriteSheet::decode);
        }
        @SlashCmd("from-file")
        @Desc("Generates a VRChat animated emoji spritesheet from the gif from this file")
        public void fromFile(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("gif-file") Message.Attachment gifFile) throws Exception
        {
            this.emoji(hook, gifFile, $ -> Gifs.VrcSpriteSheet.decode($.getFileName(), $.getUrl()));
        }
        private <T> void emoji(InteractionHook hook, T ctx, F1<Exception, Gifs.VrcSpriteSheet, T> decoder)
        {
            String info;
            FileUpload fu;
            try
            {
                Gifs.VrcSpriteSheet vrcss = decoder.invoke(ctx);
                info = vrcss.getInfo();
                fu = vrcss.toDiscordAttachment();
            }
            catch (Exception ex)
            {
                hook.sendMessageFormat("Exception generating spritesheet: %s", ex.getMessage()).queue();
                LOG.warn("Exception generating spritesheet", ex);
                return;
            }
            hook.sendMessage(info).addFiles(fu).queue();
        }
    }

    @FeatureGate("animated_emoji.enabled")
    @MsgCmd("vrchat-animated-emoji")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public void vrchatAnimatedEmoji(MessageContextInteractionEvent event) throws Exception
    {
        String raw = event.getTarget().getContentRaw();
        if (!raw.startsWith("https://tenor.com/view/"))
        {
            event.reply("Message doesn't contain a Tenor gif.").setEphemeral(true).queue();
            return;
        }
        
        event.deferReply(false).queue(hook ->
        {
            try
            {
                Gifs.VrcSpriteSheet vrcss = Gifs.VrcSpriteSheet.decode(raw);
                hook.sendMessage(vrcss.getInfo()).addFiles(vrcss.toDiscordAttachment()).queue();
            }
            catch (Exception ex)
            {
                hook.sendMessageFormat("Exception generating spritesheet: %s", ex).queue();
            }
        });
    }

    // schedule

    @FeatureGate("calendar.enabled")
    @SlashCmd("schedule")
    @Desc("Configures event schedules")
    @DefaultPerms(Permission.USE_APPLICATION_COMMANDS)
    public class Schedule
    {
        static final String UNSAVED = "The schedule was updated in memory, but could not be saved to disk. Retry before restarting Scarlet; the previous saved configuration may otherwise return.";

        boolean saveSchedule(InteractionHook hook)
        {
            if (ScarletDiscordCommands.this.discord.scarlet.calendar.save())
                return true;
            hook.sendMessage(UNSAVED).setEphemeral(true).queue();
            return false;
        }

        boolean saveSchedule(ModalInteractionEvent event)
        {
            if (ScarletDiscordCommands.this.discord.scarlet.calendar.save())
                return true;
            event.reply(UNSAVED).setEphemeral(true).queue();
            return false;
        }

        boolean currentModalSchedule(ModalInteractionEvent event, ScarletCalendar.EventSpec spec, String groupId)
        {
            if (Objects.equals(groupId, ScarletDiscordCommands.this.discord.scarlet.vrc.groupId)
                && spec != null && ScarletDiscordCommands.this.discord.scarlet.calendar.eventSpecs.get(spec.id) == spec)
                return true;
            event.reply("The group or schedule changed while this form was open. Reopen the command. No changes were applied.")
                .setEphemeral(true).queue();
            return false;
        }

        public final SlashOption<ScarletCalendar.EventSpec> _eventSpec = SlashOption.ofString("scarlet-event-spec", "The scheduled event", true, null, this::_scarletEventSpec, true, this::_scarletEventSpec);
        ScarletCalendar.EventSpec _scarletEventSpec(String id) { return ScarletDiscordCommands.this.discord.scarlet.calendar.eventSpecs.get(id); }
        void _scarletEventSpec(CommandAutoCompleteInteractionEvent event) {
            DInteractions.SlashOptionsChoicesUnsanitized.autocomplete(event, ScarletDiscordCommands.this.discord.scarlet.calendar.eventSpecs.values().stream().map($ -> new Command.Choice($.vrcCalendarEventParameters.getTitle(), $.id)).toArray(Command.Choice[]::new), false);
        }
        @SlashCmd("list")
        @Desc("Lists all event schedules")
        public void list(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            MessageEmbed[] embeds = ScarletDiscordCommands.this.discord.scarlet.calendar.eventSpecs
                .values()
                .stream()
                .map(spec -> spec.embed(new EmbedBuilder()).build())
                .toArray(MessageEmbed[]::new)
            ;
            
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), embeds, entriesPerPage).queue(hook);
        }
        public final SlashOption<Boolean> _eventActive = SlashOption.ofBool("event-active", "Whether this event should actively be scheduled", true, false);
        public final SlashOption<Boolean> _eventMirrorOnDiscord = SlashOption.ofBool("event-mirror-on-discord", "Whether this event should mirror the VRChat Event with a Discord Event", true, false);
        public final SlashOption<Integer> _eventMaxPending = SlashOption.ofInt("event-max-pending", "The maximum number of future events to post", true, 3, 1, 30);
        public final SlashOption<String> _eventId = SlashOption.ofString("event-id", "Event ID", true, "").with(data -> data.setRequiredLength(5, 32));
        public final SlashOption<String> _eventTitle = SlashOption.ofString("event-title", "Event Title", true, "").with(data -> data.setRequiredLength(5, 100));
        public final SlashOption<String> _eventDescription = SlashOption.ofString("event-description", "Event Description", true, "").with(data -> data.setRequiredLength(5, 1000));
        public final SlashOption<ZoneId> _timeZoneId = SlashOption.ofZoneId("time-zone-id", "Event time zone", true, null);
        public final SlashOption<LocalDate> _eventDate = SlashOption.ofLocalDate("event-date", "Event date", true, null);
        public final SlashOption<LocalTime> _timeOfDay = SlashOption.ofLocalTime("time-of-day", "Event time of day", true, null);
        public final SlashOption<Duration> _eventDuration = SlashOption.ofDuration("event-duration", "Event Duration", true, Duration.ofHours(1L));
        public final SlashOption<ScarletCalendar.Frequency> _eventFrequency = SlashOption.ofEnum("event-frequency", "Event Frequency", true, ScarletCalendar.Frequency.ONE_OFF);
        public final SlashOption<GroupEventCategory> _eventCategory = SlashOption.ofEnum("event-category", "Event Category", true, GroupEventCategory.OTHER);
        @SlashCmd("add")
        @Desc("Adds an event schedule")
        public void add(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("event-id") String eventId,
                @SlashOpt("event-title") String eventTitle,
                @SlashOpt("event-description") String eventDescription,
                @SlashOpt("event-date") LocalDate eventDate,
                @SlashOpt("time-zone-id") ZoneId timeZoneId,
                @SlashOpt("time-of-day") LocalTime timeOfDay,
                @SlashOpt("event-duration") Duration eventDuration,
                @SlashOpt("event-frequency") ScarletCalendar.Frequency eventFrequency,
                @SlashOpt("event-category") GroupEventCategory eventCategory)
        {
            if (!ScarletDiscordCommands.this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_calendar_manage, hook))
                return;
            if (ScarletDiscordCommands.this.discord.scarlet.calendar.eventSpecs.containsKey(eventId))
            {
                hook.sendMessage("An event schedule with that id already exists").setEphemeral(true).queue();
                return;
            }
            OffsetTime eventTime = timeOfDay.atOffset(timeZoneId.getRules().getOffset(Instant.now()));
            ScarletCalendar.EventSpec eventSpec = new ScarletCalendar.EventSpec();
            eventSpec.id = eventId;
            eventSpec.vrcCalendarEventParameters.setTitle(eventTitle);
            eventSpec.vrcCalendarEventParameters.setDescription(eventDescription);
            eventSpec.date = eventDate;
            eventSpec.time = eventTime;
            eventSpec.duration = eventDuration;
            eventSpec.frequency = eventFrequency;
            eventSpec.vrcCalendarEventParameters.setCategory(eventCategory.value);
            eventSpec.vrcCalendarEventParameters.setAccessType(CalendarEventAccess.PUBLIC);
            eventSpec.vrcCalendarEventParameters.setSendCreationNotification(Boolean.TRUE);
            ScarletDiscordCommands.this.discord.scarlet.calendar.eventSpecs.put(eventId, eventSpec);
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Added event schedule").setEphemeral(true).queue();
        }
        @SlashCmd("set-active")
        @Desc("Activate or deactivate an event schedule")
        public void setActive(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-active") boolean eventActive)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.active = eventActive;
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule active to "+eventActive).setEphemeral(true).queue();
        }
        @SlashCmd("set-mirror-on-discord")
        @Desc("Set whether a complementary Discord Event should be created")
        public void setMirrorOnDiscord(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-mirror-on-discord") boolean eventMirrorOnDiscord)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.mirrorOnDiscord = eventMirrorOnDiscord;
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule mirror on discord to "+eventMirrorOnDiscord).setEphemeral(true).queue();
        }
        @SlashCmd("set-max-pending")
        @Desc("Set the maximum number of future events to post")
        public void setMaxPending(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-max-pending") int eventMaxPending)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.maxPending = eventMaxPending;
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule maximum pending to "+eventMaxPending).setEphemeral(true).queue();
        }
        @SlashCmd("set-title")
        @Desc("Set an event schedule's title")
        public void setTitle(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-title") String eventTitle)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setTitle(eventTitle);
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule title").setEphemeral(true).queue();
        }
        @SlashCmd("set-description")
        @Desc("Set an event schedule's description")
        public void setDescription(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-description") String eventDescription)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setDescription(eventDescription);
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule description").setEphemeral(true).queue();
        }
        @SlashCmd("set-date")
        @Desc("Set an event schedule's date")
        public void setDate(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-date") LocalDate eventDate)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.date = eventDate;
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule date").setEphemeral(true).queue();
        }
        @SlashCmd("set-time")
        @Desc("Set an event schedule's time")
        public void setTime(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("time-zone-id") ZoneId timeZoneId,
                @SlashOpt("time-of-day") LocalTime timeOfDay)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            OffsetTime eventTime = timeOfDay.atOffset(timeZoneId.getRules().getOffset(Instant.now()));
            eventSpec.time = eventTime;
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule time").setEphemeral(true).queue();
        }
        @SlashCmd("set-duration")
        @Desc("Set an event schedule's duration")
        public void setDuration(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-duration") Duration eventDuration)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.duration = eventDuration;
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule duration").setEphemeral(true).queue();
        }
        @SlashCmd("set-frequency")
        @Desc("Set an event schedule's frequency")
        public void setFrequency(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-frequency") ScarletCalendar.Frequency eventFrequency)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.frequency = eventFrequency;
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule frequency").setEphemeral(true).queue();
        }
        @SlashCmd("set-category")
        @Desc("Set an event schedule's category")
        public void setCategory(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-category") GroupEventCategory eventCategory)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setCategory(eventCategory.value);
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule category").setEphemeral(true).queue();
        }
        public final SlashOption<CalendarEventAccess> _eventAccess = SlashOption.ofDOptionEnum(DOptionEnum.of("event-access", "Event Access", CalendarEventAccess.class, CalendarEventAccess::getValue, "Public", "Group"), true);
        @SlashCmd("set-access")
        @Desc("Set an event schedule's access")
        public void setAccess(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-access") CalendarEventAccess eventAccess)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setAccessType(eventAccess);
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule access").setEphemeral(true).queue();
        }
        public final SlashOption<Boolean> _eventNotify = SlashOption.ofBool("event-notify", "Event Notification upon creation", true, Boolean.TRUE);
        @SlashCmd("set-notify-create")
        @Desc("Set an event schedule's notification upon creation")
        public void setNotifyCreate(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-notify") Boolean eventNotify)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setSendCreationNotification(eventNotify);
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule notification usage").setEphemeral(true).queue();
        }
        public final SlashOption<String> _eventTag1 = SlashOption.ofString("event-tag-1", "Event tag 1", false, null),
                                         _eventTag2 = SlashOption.ofString("event-tag-2", "Event tag 2", false, null),
                                         _eventTag3 = SlashOption.ofString("event-tag-3", "Event tag 3", false, null),
                                         _eventTag4 = SlashOption.ofString("event-tag-4", "Event tag 4", false, null),
                                         _eventTag5 = SlashOption.ofString("event-tag-5", "Event tag 5", false, null);
        @SlashCmd("set-tags")
        @Desc("Set an event schedule's tags")
        public void setTags(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-tag-1") String eventTag1,
                @SlashOpt("event-tag-2") String eventTag2,
                @SlashOpt("event-tag-3") String eventTag3,
                @SlashOpt("event-tag-4") String eventTag4,
                @SlashOpt("event-tag-5") String eventTag5)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            List<String> eventTagsa = Stream
                .of(eventTag1, eventTag2, eventTag3, eventTag4, eventTag5)
                .filter(Objects::nonNull)
                .filter($->!$.isEmpty())
                .collect(Collectors.toList());
            if (eventTagsa.isEmpty())
            {
                eventSpec.vrcCalendarEventParameters.setTags(null);
                if (!this.saveSchedule(hook)) return;
                hook.sendMessage("Cleared event schedule tags").setEphemeral(true).queue();
                return;
            }
            if (eventTagsa.contains(GroupAdminTag.VRC_EVENT_GROUP_FAIR_TAG) && !ScarletDiscordCommands.this.discord.scarlet.vrc.checkGroupHasAdminTag(GroupAdminTag.VRC_EVENT_GROUP_FAIR_ENABLED))
            {
                hook.sendMessage("This group does not have Event Group Fair enabled, so you may not use that tag").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setTags(new ArrayList<>(eventTagsa));
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule tags").setEphemeral(true).queue();
        }
        @SlashCmd("set-image")
        @Desc("Set an event schedule's image")
        @Ephemeral
        public void setImage(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec, @SlashOpt("entries-per-page") int entriesPerPage)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            class GalleryImageInfo
            {
                GalleryImageInfo(GroupGalleryImage image, GroupGallery gallery, int index)
                {
                    this.image = image;
                    this.gallery = gallery;
                    this.index = index;
                    this.submittingUserDisplayName = ScarletDiscordCommands.this.discord.scarlet.vrc.getUserDisplayName(image.getSubmittedByUserId());
                }
                final GroupGalleryImage image;
                final GroupGallery gallery;
                final int index;
                final String submittingUserDisplayName;
                MessageEmbed embed()
                {
                    return new EmbedBuilder()
                        .setAuthor(MarkdownSanitizer.escape(this.submittingUserDisplayName), VrcWeb.Home.user(this.image.getSubmittedByUserId()))
                        .setTitle(MiscUtils.maybeEllipsis(MessageEmbed.TITLE_MAX_LENGTH, MarkdownSanitizer.escape(this.gallery.getName())+" image #"+(this.index+1)),
                                  VrcWeb.Home.groupGalleries(this.image.getGroupId())+"#"+this.gallery.getId()+"["+this.index+"]"
                        )                                                      // Give embeds different URLs so they don't combine
                        .setImage(this.image.getImageUrl().toString())
                        .setTimestamp(this.image.getCreatedAt())
                        .build();
                }
                SelectOption option()
                {
                    return SelectOption.of(MiscUtils.maybeEllipsis(SelectOption.LABEL_MAX_LENGTH, MarkdownSanitizer.escape(this.gallery.getName())+" image #"+(this.index+1)), this.image.getFileId());
                }
            }
            List<GalleryImageInfo> images = new ArrayList<>();
            try
            {
                List<GroupGallery> galleries = ScarletDiscordCommands.this.discord.scarlet.vrc.group.getGalleries();
//                LOG.info("Galleries for "+ScarletDiscordCommands.this.discord.scarlet.vrc.groupId+": "+galleries);
                if (galleries != null)
                    for (GroupGallery gallery : galleries)
                    {
                        List<GroupGalleryImage> inGallery = ScarletDiscordCommands.this.discord.scarlet.vrc.getGroupGalleryImages(ScarletDiscordCommands.this.discord.scarlet.vrc.groupId, gallery.getId(), null);
//                        LOG.info("Images for "+gallery.getId()+": "+inGallery);
                        if (inGallery != null && !inGallery.isEmpty())
                        {
                            int index = 0;
                            for (GroupGalleryImage image : inGallery)
                                images.add(new GalleryImageInfo(image, gallery, index++));
                        }
                    }
            }
            catch (Exception ex)
            {
                LOG.error("Exception listing gallery", ex);
            }
            ScarletDiscordCommands.this.discord.interactions.new Pagination(event.getId(), images, GalleryImageInfo::embed, GalleryImageInfo::option, entriesPerPage, (submitEvent, fileId) ->
            {
                if (eventSpec != null)
                {
                    eventSpec.vrcCalendarEventParameters.setImageId(fileId);
                    if (!this.saveSchedule(hook)) return;
                }
                hook.sendMessageEmbeds(new EmbedBuilder().setTitle(fileId).setImage(VrcWeb.file1Data(fileId)).build()).setContent("Set the scheduled event image").setEphemeral(true).queue();
            }).queue(hook);
        }
        public final ModalFlowOption<GroupRole[]> _eventRolesModal1 = ModalFlowOption.ofUniqueGroupRoles("event-roles-1-25", "Roles 1-25", null, false, "<event roles>", 0, 25, ScarletDiscordCommands.this.discord.scarlet.vrc.groupRoles);
        public final ModalFlowOption<GroupRole[]> _eventRolesModal2 = ModalFlowOption.ofUniqueGroupRolesNext25("event-roles-26-50", "Roles 26-50", null, false, "<event roles>", 0, 25, ScarletDiscordCommands.this.discord.scarlet.vrc.groupRoles);
        @ModalSub("schedule-set-roles")
        @Desc("Set Event roles")
        public class SetRolesModal implements DInteractions.ModalFlow<SetRolesModal>
        {
            SetRolesModal(ScarletCalendar.EventSpec eventSpec)
            {
                this.eventSpec = eventSpec;
                this.groupId = ScarletDiscordCommands.this.discord.scarlet.vrc.groupId;
                List<String> roleIds = eventSpec.vrcCalendarEventParameters.getRoleIds();
                if (roleIds != null && !roleIds.isEmpty())
                {
                    GroupRole[] eventRoles = roleIds.stream().map(ScarletDiscordCommands.this.discord.scarlet.vrc.groupRoles::get).filter(Objects::nonNull).toArray(GroupRole[]::new);
                    if (eventRoles.length != 0)
                    {
                        this.eventRoles_1_25 = eventRoles;
                        this.eventRoles_26_50 = eventRoles;
                    }
                }
            }
            final ScarletCalendar.EventSpec eventSpec;
            final String groupId;
            @StringSel("event-roles-1-25")
            public GroupRole[] eventRoles_1_25;
            @StringSel("event-roles-26-50")
            public GroupRole[] eventRoles_26_50;
            @Override
            public void handle(ModalInteractionEvent event)
            {
                if (!Schedule.this.currentModalSchedule(event, this.eventSpec, this.groupId)) return;
                GroupRole[] eventRoles = MiscUtils.append(this.eventRoles_1_25, this.eventRoles_26_50);
                if (eventRoles == null || eventRoles.length == 0)
                {
                    this.eventSpec.vrcCalendarEventParameters.setRoleIds(null);
                    if (!Schedule.this.saveSchedule(event)) return;
                    event.reply("Cleared event roles").setEphemeral(true).queue();
                    return;
                }
                
                this.eventSpec.vrcCalendarEventParameters.setRoleIds(new ArrayList<>(Arrays.stream(eventRoles).filter(Objects::nonNull).map(GroupRole::getId).distinct().collect(Collectors.toList())));
                if (!Schedule.this.saveSchedule(event)) return;
                event.reply("Set event roles").setEphemeral(true).queue();
            }
        }
        @SlashCmd("set-roles")
        @Desc("Set an event schedule's roles")
        public void setRoles(SlashCommandInteractionEvent event, @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec)
        {
            if (eventSpec == null)
            {
                event.reply("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            ScarletDiscordCommands.this.discord.interactions.submitModalFlow(event, new SetRolesModal(eventSpec)).queue();
        }
        public final ModalFlowOption<GroupEventPlatform[]> _eventPlatformsModal = ModalFlowOption.ofUniqueEnums("event-platforms", "Event platforms", null, false, "<none>", 0, 3, GroupEventPlatform.class, new GroupEventPlatform[]{GroupEventPlatform.WINDOWS});
        @ModalSub("schedule-set-platforms")
        @Desc("Set Event platforms")
        public class SetPlatformsModal implements DInteractions.ModalFlow<SetPlatformsModal>
        {
            SetPlatformsModal(ScarletCalendar.EventSpec eventSpec)
            {
                this.eventSpec = eventSpec;
                this.groupId = ScarletDiscordCommands.this.discord.scarlet.vrc.groupId;
                List<CalendarEventPlatform> platforms = eventSpec.vrcCalendarEventParameters.getPlatforms();
                if (platforms != null && !platforms.isEmpty())
                {
                    GroupEventPlatform[] eventPlatforms = platforms.stream().map(GroupEventPlatform::of).filter(Objects::nonNull).toArray(GroupEventPlatform[]::new);
                    if (eventPlatforms.length != 0)
                        this.eventPlatforms = eventPlatforms;
                }
            }
            final ScarletCalendar.EventSpec eventSpec;
            final String groupId;
            @StringSel("event-platforms")
            public GroupEventPlatform[] eventPlatforms;
            @Override
            public void handle(ModalInteractionEvent event)
            {
                if (!Schedule.this.currentModalSchedule(event, this.eventSpec, this.groupId)) return;
                if (this.eventPlatforms == null || this.eventPlatforms.length == 0)
                {
                    this.eventSpec.vrcCalendarEventParameters.setPlatforms(null);
                    if (!Schedule.this.saveSchedule(event)) return;
                    event.reply("Cleared event platforms").setEphemeral(true).queue();
                    return;
                }
                
                this.eventSpec.vrcCalendarEventParameters.setPlatforms(new ArrayList<>(Arrays.stream(this.eventPlatforms).filter(Objects::nonNull).map(GroupEventPlatform::model).collect(Collectors.toList())));
                if (!Schedule.this.saveSchedule(event)) return;
                event.reply("Set event platforms").setEphemeral(true).queue();
            }
        }
        @SlashCmd("set-platforms")
        @Desc("Set an event schedule's platforms")
        public void setPlatforms(SlashCommandInteractionEvent event, @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec)
        {
            if (eventSpec == null)
            {
                event.reply("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            ScarletDiscordCommands.this.discord.interactions.submitModalFlow(event, new SetPlatformsModal(eventSpec)).queue();
        }
        public final SlashOption<String> _eventLanguage1 = SlashOption.ofString("event-language-1", "Event language 1", false, null, this::_eventLanguage),
                                         _eventLanguage2 = SlashOption.ofString("event-language-2", "Event language 2", false, null, this::_eventLanguage),
                                         _eventLanguage3 = SlashOption.ofString("event-language-3", "Event language 3", false, null, this::_eventLanguage);
        void _eventLanguage(CommandAutoCompleteInteractionEvent event) {
            String value = event.getFocusedOption().getValue();
            if (value.isEmpty())
            {
                event.replyChoices(Arrays.stream(ScarletDiscordCommands.this.spokenLanguages).limit(25L).collect(Collectors.toList())).queue();
                return;
            }
            event.replyChoices(Arrays.stream(ScarletDiscordCommands.this.spokenLanguages).sorted(DInteractions.choicesByLevenshtein(value)).limit(25L).collect(Collectors.toList())).queue();
        }
        @SlashCmd("set-languages")
        @Desc("Set an event schedule's languages")
        public void setLanguages(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-language-1") String eventLanguage1,
                @SlashOpt("event-language-2") String eventLanguage2,
                @SlashOpt("event-language-3") String eventLanguage3)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            List<String> eventLanguagesa = Stream
                .of(eventLanguage1, eventLanguage2, eventLanguage3)
                .filter(Objects::nonNull)
                .filter($->!$.isEmpty())
                .distinct()
                .collect(Collectors.toList());
            if (eventLanguagesa.isEmpty())
            {
                eventSpec.vrcCalendarEventParameters.setLanguages(null);
                if (!this.saveSchedule(hook)) return;
                hook.sendMessage("Cleared event schedule languages").setEphemeral(true).queue();
                return;
            }
            List<String> eventLanguagesa_invalid = eventLanguagesa.stream().filter($->!ScarletDiscordCommands.this.languages.containsKey($)).collect(Collectors.toList());
            if (!eventLanguagesa_invalid.isEmpty())
            {
                hook.sendMessage("Invalid or unknown languages: "+eventLanguagesa_invalid).setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setLanguages(new ArrayList<>(eventLanguagesa));
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule languages").setEphemeral(true).queue();
        }
        public final SlashOption<Integer> _eventHostEarly = SlashOption.ofInt("event-host-early", "Event host early join minutes", false, null).with(data -> data.setRequiredRange(1L, 60L));
        @SlashCmd("set-host-join-early")
        @Desc("Set an event schedule's host early join minutes")
        public void setHostJoin(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-host-early") Integer eventHostEarly)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setHostEarlyJoinMinutes(eventHostEarly);
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event host early join minutes").setEphemeral(true).queue();
        }
        public final SlashOption<Integer> _eventGuestEarly = SlashOption.ofInt("event-guest-early", "Event guest early join minutes", false, null).with(data -> data.setRequiredRange(1L, 60L));
        @SlashCmd("set-guest-join-early")
        @Desc("Set an event schedule's guest early join minutes")
        public void setGuestJoin(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-guest-early") Integer eventGuestEarly)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setGuestEarlyJoinMinutes(eventGuestEarly);
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event guest early join minutes").setEphemeral(true).queue();
        }
        public final SlashOption<Integer> _eventCloseAfter = SlashOption.ofInt("event-close-after", "Event close Instance after end minutes", false, null).with(data -> data.setRequiredRange(1L, 60L));
        @SlashCmd("set-close-after")
        @Desc("Set an event schedule's close Instance after end minutes")
        public void setCloseAfter(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-close-after") Integer eventCloseAfter)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setCloseInstanceAfterEndMinutes(eventCloseAfter);
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event close Instance after end minutes").setEphemeral(true).queue();
        }
        public final SlashOption<Boolean> _eventOverflow = SlashOption.ofBool("event-overflow", "Event uses Instance Overflow", false, Boolean.TRUE);
        @SlashCmd("set-overflow")
        @Desc("Set an event schedule's overflow usage")
        public void setOverflow(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-overflow") Boolean eventOverflow)
        {
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setUsesInstanceOverflow(eventOverflow);;
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule overflow usage").setEphemeral(true).queue();
        }
        public final SlashOption<Boolean> _eventFeatured = SlashOption.ofBool("event-featured", "Event has featured", false, null);
        @SlashCmd("set-featured")
        @Desc("Set an event schedule's featured status")
        public void setFeatured(SlashCommandInteractionEvent event, InteractionHook hook,
                @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec,
                @SlashOpt("event-featured") Boolean eventFeatured)
        {
            if (!ScarletDiscordCommands.this.discord.scarlet.vrc.checkGroupHasAdminTag(GroupAdminTag.FEATURED_EVENTS_ENABLED))
            {
                hook.sendMessage("The group does not have Featured Events enabled by VRChat, this is not something you can simply change yourself").setEphemeral(true).queue();
                return;
            }
            if (eventSpec == null)
            {
                hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                return;
            }
            eventSpec.vrcCalendarEventParameters.setFeatured(eventFeatured);
            if (!this.saveSchedule(hook)) return;
            hook.sendMessage("Set event schedule featured status").setEphemeral(true).queue();
        }
        @SlashCmd("remove")
        @Desc("Removes an event schedule")
        public void remove(SlashCommandInteractionEvent event, InteractionHook hook, @SlashOpt("scarlet-event-spec") ScarletCalendar.EventSpec eventSpec)
        {
            try
            {
                if (eventSpec == null || !ScarletDiscordCommands.this.discord.scarlet.calendar.removeAndSave(eventSpec))
                {
                    hook.sendMessage("There is no event schedule with that id").setEphemeral(true).queue();
                    return;
                }
                hook.sendMessage("Removed event schedule and saved the deletion").setEphemeral(true).queue();
            }
            catch (java.io.IOException ex)
            {
                hook.sendMessage("The schedule could not be saved. Deletion was rolled back; the schedule remains configured.").setEphemeral(true).queue();
            }
        }
    }

    // set-training-channel

    @SlashCmd("set-training-channel")
    @Desc("Set the Discord channel that receives simulated [TRAINING] events (keeps drills out of the real log)")
    @DefaultPerms(Permission.MANAGE_SERVER)
    @Ephemeral
    public void setTrainingChannel(SlashCommandInteractionEvent event, InteractionHook hook,
                                   @SlashOpt("discord-text-channel") Channel channel)
    {
        if (!this.checkTextChannelConfiguration(channel, hook))
            return;
        ((ScarletDiscordJDA)this.discord).setTrainingChannel(channel);
        if (channel == null)
        {
            hook.sendMessage("Training channel removed. Simulated events will no longer be posted to Discord.").queue();
        }
        else
        {
            hook.sendMessageFormat("Simulated [TRAINING] events will be sent to <#%s>.", channel.getId()).queue();
        }
    }

    // set-ops-alert-channel

    @SlashCmd("set-ops-alert-channel")
    @Desc("Set the Discord channel that receives Scarlet health alerts (auth, log tailer, rate limits, updates)")
    @DefaultPerms(Permission.MANAGE_SERVER)
    @Ephemeral
    public void setOpsAlertChannel(SlashCommandInteractionEvent event, InteractionHook hook,
                                   @SlashOpt("discord-text-channel") Channel channel)
    {
        if (!this.checkTextChannelConfiguration(channel, hook))
            return;
        ((ScarletDiscordJDA)this.discord).setOpsAlertChannel(channel);
        if (channel == null)
        {
            hook.sendMessage("Ops alert channel removed.").queue();
        }
        else
        {
            hook.sendMessageFormat("Scarlet health alerts will be sent to <#%s>.", channel.getId()).queue();
        }
    }

    // set-discord-action-log-channel

    @SlashCmd("set-discord-action-log-channel")
    @Desc("Set the Discord channel used for Scarlet moderation action logs")
    @DefaultPerms(Permission.MANAGE_SERVER)
    @Ephemeral
    public void setDiscordActionLogChannel(SlashCommandInteractionEvent event, InteractionHook hook,
                                           @SlashOpt("discord-text-channel") Channel channel)
    {
        if (!this.checkTextChannelConfiguration(channel, hook))
            return;
        this.discord.setDiscordActionLogChannel(channel);
        if (channel == null)
        {
            hook.sendMessage("Discord action logging channel removed.").queue();
        }
        else
        {
            hook.sendMessageFormat("Discord action logs will be sent to <#%s>.", channel.getId()).queue();
        }
    }

    // set-discord-account-age-alert

    @SlashCmd("set-discord-account-age-alert")
    @Desc("Set the Discord account-age alert threshold")
    @DefaultPerms(Permission.MANAGE_SERVER)
    @Ephemeral
    public void setDiscordAccountAgeAlert(SlashCommandInteractionEvent event, InteractionHook hook,
                                          @SlashOpt("threshold") DiscordAccountAgeThreshold threshold)
    {
        int minutes = this.discord.setDiscordAccountAgeAlertMinutes(threshold.value().intValue());
        if (minutes <= 0)
        {
            hook.sendMessage("Discord account-age alerts disabled.").queue();
        }
        else
        {
            hook.sendMessage("Discord account-age alerts will flag joins under "
                + ScarletDiscordJDA.formatDurationHuman(Duration.ofMinutes(minutes)) + ".").queue();
        }
    }

    // set-ticket-tool-auto-response

    @SlashCmd("set-ticket-tool-auto-response")
    @Desc("Configure the Ticket Tool age-verification auto-response")
    @DefaultPerms(Permission.MANAGE_SERVER)
    @Ephemeral
    public void setTicketToolAutoResponse(SlashCommandInteractionEvent event, InteractionHook hook,
                                          @SlashOpt("enabled") Boolean enabled,
                                          @SlashOpt("discord-category") Channel category,
                                          @SlashOpt("notify-role") Role notifyRole,
                                          @SlashOpt("channel-name-regex") String channelNameRegex)
    {
        if (!this.discord.setTicketToolAutoResponse(enabled, category, notifyRole, channelNameRegex))
        {
            hook.sendMessage("Invalid channel-name regex. Nothing changed.").queue();
            return;
        }
        String categoryText = this.discord.ticketToolCategorySf == null
            ? "not set"
            : "<#" + this.discord.ticketToolCategorySf + ">";
        String regexText = this.discord.ticketToolChannelNameRegex == null
            ? "not set"
            : "`" + this.discord.ticketToolChannelNameRegex + "`";
        String notifyRoleText = this.discord.ticketToolNotifyRoleSf == null
            ? "not set"
            : "<@&" + this.discord.ticketToolNotifyRoleSf + ">";
        hook.sendMessage(
            "Ticket Tool auto-response is " + (this.discord.ticketToolAutoResponseEnabled ? "enabled" : "disabled") + ".\n" +
            "Category: " + categoryText + "\n" +
            "Notify role: " + notifyRoleText + "\n" +
            "Channel-name regex: " + regexText).queue();
    }

    // set-ticket-tool-age-verification-message

    @SlashCmd("set-ticket-age-verify-message")
    @Desc("Set (or reset) the Ticket Tool age-verification auto-response message")
    @DefaultPerms(Permission.MANAGE_SERVER)
    public void setTicketToolAgeVerificationMessage(SlashCommandInteractionEvent event) throws Exception
    {
        Modal modal = Modal.create("settings:ticketToolAgeVerificationMessage", "Ticket age-verification message")
            .addComponents(Label.of("Message (leave empty to reset to default)", TextInput.create("message", TextInputStyle.PARAGRAPH)
                .setValue(this.discord.ticketToolAgeVerificationMessage)
                .setRequired(false)
                .setMaxLength(4000)
                .build()))
            .build();
        DInteractions.ModalFlowImmediate immediate = interaction ->
        {
            String value = interaction.getValue("message").getAsString();
            this.discord.setTicketToolAgeVerificationMessage(value);
            interaction.replyFormat("Ticket Tool age-verification auto-response message updated:\n\n%s", this.discord.ticketToolAgeVerificationMessage).setEphemeral(true).queue();
        };
        String groupId = this.discord.scarlet.vrc.groupId;
        ScarletDiscordCommands.this.discord.interactions.submitModalFlow(event, modal, immediate,
            interaction -> Objects.equals(groupId, this.discord.scarlet.vrc.groupId)).queue();
    }

    // set-verification-auto-invite

    @SlashCmd("set-verification-auto-invite")
    @Desc("Configure auto-inviting newly-verified members to a VRChat group")
    @DefaultPerms(Permission.MANAGE_SERVER)
    @Ephemeral
    public void setVerificationAutoInvite(SlashCommandInteractionEvent event, InteractionHook hook,
                                          @SlashOpt("enabled") Boolean enabled,
                                          @SlashOpt("verified-role") Role verifiedRole,
                                          @SlashOpt("members-role") Role membersRole,
                                          @SlashOpt("vrchat-group-id") String groupId) throws Exception
    {
        Scarlet scarlet = ScarletDiscordCommands.this.discord.scarlet;
        List<String> errors = new ArrayList<>();

        if (enabled != null)
        {
            scarlet.autoInviteOnVerify.set(enabled, "discord");
        }
        if (verifiedRole != null)
        {
            if (!scarlet.verifiedRoleSf.set(verifiedRole.getId(), "discord"))
                errors.add("Could not save the verified role.");
        }
        if (membersRole != null)
        {
            if (!scarlet.membersRoleSf.set(membersRole.getId(), "discord"))
                errors.add("Could not save the members role.");
        }
        if (groupId != null)
        {
            if (!VrcIds.id_group.matcher(groupId).matches())
                errors.add("`"+groupId+"` doesn't look like a VRChat group id (expected `grp_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX`).");
            else if (!scarlet.autoInviteGroupId.set(groupId, "discord"))
                errors.add("Could not save the VRChat group id.");
        }

        boolean nowEnabled = scarlet.autoInviteOnVerify.get();
        String roleSf = scarlet.verifiedRoleSf.get();
        String membersRoleSf = scarlet.membersRoleSf.get();
        String currentGroupId = scarlet.autoInviteGroupId.get();
        if (Scarlet.DEFAULT_AUTO_INVITE_GROUP_ID.equals(currentGroupId))
            currentGroupId = null;
        String effectiveGroupId = currentGroupId == null || currentGroupId.isEmpty() ? scarlet.vrc.groupId : currentGroupId;

        StringBuilder sb = new StringBuilder();
        if (!errors.isEmpty())
        {
            sb.append("**Some changes could not be applied:**\n");
            for (String error : errors)
                sb.append("- ").append(error).append('\n');
            sb.append('\n');
        }
        sb.append("**Verification auto-invite settings**\n");
        sb.append("Enabled: ").append(nowEnabled ? "Yes" : "No").append('\n');
        sb.append("Verified role: ").append(roleSf == null || roleSf.isEmpty() || "1234567890".equals(roleSf) ? "Not set" : "<@&"+roleSf+">").append('\n');
        sb.append("Members role (required before link prompt): ").append(membersRoleSf == null || membersRoleSf.isEmpty() || "1234567890".equals(membersRoleSf) ? "Not set (no restriction)" : "<@&"+membersRoleSf+">").append('\n');
        sb.append("VRChat group: ").append(effectiveGroupId == null || effectiveGroupId.isEmpty() ? "Not set" : MarkdownUtil.maskedLink(effectiveGroupId, VrcWeb.Home.group(effectiveGroupId)));
        if (currentGroupId == null || currentGroupId.isEmpty())
            sb.append(" (default)");

        hook.sendMessage(sb.toString()).queue();
    }

    // link-vrchat-account

    @SlashCmd("link-vrchat-account")
    @Desc("Link your Discord account to your VRChat account")
    @Ephemeral
    public void linkVrchatAccount(SlashCommandInteractionEvent event, InteractionHook hook,
                                  @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser) throws Exception
    {
        String vrcId = VrcIds.getAsString_user(event.getOption("vrchat-user"));
        if (vrchatUser == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcId).queue();
            return;
        }
        Member self = event.getMember();
        if (self == null)
        {
            hook.sendMessage("Could not resolve your Discord membership.").queue();
            return;
        }
        // Everyone must prove they own the VRChat account before it's linked. Members who
        // aren't verified yet will additionally be granted the member role once they pass.
        boolean hadMemberRole = ScarletDiscordCommands.this.memberHasMembersRole(self);
        if (!hadMemberRole)
        {
            // Not a member yet: require visible VRChat age verification before self-verifying.
            io.github.vrchatapi.model.AgeVerificationStatus ageStatus = vrchatUser.getAgeVerificationStatus();
            String ageValue = ageStatus == null ? null : ageStatus.getValue();
            boolean ageVerified = "18+".equals(ageValue) || "verified".equals(ageValue);
            if (!ageVerified)
            {
                LOG.info("Self-link: Discord user {} ({}) is not a member and VRChat {} age status is {}; directing to manual verification.", self.getEffectiveName(), self.getId(), vrchatUser.getId(), ageValue);
                String manual = ScarletDiscordCommands.this.discord.scarlet.notVerifiedLinkMessage.get();
                String body = (manual == null || manual.trim().isEmpty())
                    ? "I couldn't verify you automatically. Please contact staff for manual verification."
                    : "I couldn't verify you automatically.\n\n" + manual;
                hook.sendMessage(body).setEphemeral(false).queue();
                return;
            }
        }
        String code = newVrcLinkCode();
        ScarletDiscordCommands.this.pendingVrcLinks.put(self.getId(), new PendingVrcLink(vrchatUser.getId(), vrchatUser.getDisplayName(), code, System.currentTimeMillis() + VRC_LINK_CODE_TTL_MS, hadMemberRole));
        LOG.info("Self-link: Discord user {} ({}) requested to link VRChat user {} ({}); awaiting bio-code verification (member={}).", self.getEffectiveName(), self.getId(), vrchatUser.getDisplayName(), vrchatUser.getId(), hadMemberRole);
        String intro = hadMemberRole
            ? String.format("To link [%s](%s) to your Discord account, confirm you own it.", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrchatUser.getId()))
            : String.format("You're not a verified member yet. To verify and join the VRChat group, confirm you own [%s](%s).", vrchatUser.getDisplayName(), VrcWeb.Home.user(vrchatUser.getId()));
        hook.sendMessageFormat(
                "%s\n\nAdd this code anywhere in your VRChat bio:\n```\n%s\n```\n"
              + "Edit your bio at <https://vrchat.com/home/profile>, **save** it, then press the button below. You can remove the code afterward.\n"
              + "This request expires in 30 minutes.",
                intro, code)
            .setComponents(ActionRow.of(Button.primary("linkvrc-verify:" + vrchatUser.getId(), "I've added the code — verify")))
            .queue();
    }

    static final long VRC_LINK_CODE_TTL_MS = 30L * 60L * 1000L;

    static String newVrcLinkCode()
    {
        return "VRC-VERIFY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    static final class PendingVrcLink
    {
        final String vrcId, displayName, code;
        final long expiresAtEpochMs;
        final boolean hadMemberRole;
        PendingVrcLink(String vrcId, String displayName, String code, long expiresAtEpochMs, boolean hadMemberRole)
        {
            this.vrcId = vrcId;
            this.displayName = displayName;
            this.code = code;
            this.expiresAtEpochMs = expiresAtEpochMs;
            this.hadMemberRole = hadMemberRole;
        }
    }

    final Map<String, PendingVrcLink> pendingVrcLinks = new ConcurrentHashMap<>();

    boolean memberHasMembersRole(Member member)
    {
        String membersRoleSf = ScarletDiscordCommands.this.discord.scarlet.membersRoleSf.get();
        if (membersRoleSf == null || membersRoleSf.isEmpty() || "1234567890".equals(membersRoleSf))
            return false;
        return member.getRoles().stream().anyMatch(role -> role.getId().equals(membersRoleSf));
    }

    /**
     * Grants the configured members role to the given member if they don't already have it.
     * Returns true if the member ends up with the role (already had it, or it was granted),
     * false if the role isn't configured/resolvable or granting failed (e.g. missing perms).
     */
    boolean ensureMembersRole(Member member)
    {
        String membersRoleSf = ScarletDiscordCommands.this.discord.scarlet.membersRoleSf.get();
        if (membersRoleSf == null || membersRoleSf.isEmpty() || "1234567890".equals(membersRoleSf))
            return false;
        net.dv8tion.jda.api.entities.Guild guild = member.getGuild();
        Role role = guild.getRoleById(membersRoleSf);
        if (role == null)
            return false;
        if (member.getRoles().contains(role))
            return true;
        try
        {
            guild.addRoleToMember(member, role).complete();
            LOG.info("Granted members role {} to {} ({}) after VRChat bio verification.", role.getId(), member.getEffectiveName(), member.getId());
            return true;
        }
        catch (RuntimeException ex)
        {
            LOG.error("Failed to grant members role {} to {} ({}): {}", membersRoleSf, member.getEffectiveName(), member.getId(), ex.getMessage());
            return false;
        }
    }

    @ButtonClk("linkvrc-verify")
    public void linkVrchatVerify(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String vrcId = parts.length > 1 ? parts[1] : null;
        Member self = event.getMember();
        if (self == null)
        {
            event.editMessage("Could not resolve your Discord membership.").setComponents().queue();
            return;
        }
        PendingVrcLink pending = ScarletDiscordCommands.this.pendingVrcLinks.get(self.getId());
        if (pending == null || vrcId == null || !vrcId.equals(pending.vrcId))
        {
            event.editMessage("This link request is no longer active. Please run `/link-vrchat-account` again.").setComponents().queue();
            return;
        }
        if (System.currentTimeMillis() > pending.expiresAtEpochMs)
        {
            ScarletDiscordCommands.this.pendingVrcLinks.remove(self.getId());
            event.editMessage("This verification code has expired. Please run `/link-vrchat-account` again.").setComponents().queue();
            return;
        }
        // Acknowledge immediately, then re-fetch the bio off-thread (forces a fresh API read).
        event.deferEdit().queue();
        ScarletDiscordCommands.this.discord.scarlet.exec.execute(() ->
        {
            io.github.vrchatapi.model.User fresh = ScarletDiscordCommands.this.discord.scarlet.vrc.getUserFresh(pending.vrcId);
            // API 1.21.0 removed bio from User; it now lives on the public profile.
            String bio = fresh == null ? null : ScarletDiscordCommands.this.discord.scarlet.vrc.getUserBio(pending.vrcId);
            if (bio == null || !bio.contains(pending.code))
            {
                // Leave the button so they can fix their bio and retry; also offer manual verification.
                String manual = ScarletDiscordCommands.this.discord.scarlet.notVerifiedLinkMessage.get();
                String suffix = (manual == null || manual.trim().isEmpty()) ? "" : ("\n\n" + manual);
                event.getHook().editOriginalFormat("I couldn't find the code `%s` in your VRChat bio. Double-check you **saved** your bio, then press the button again.%s", pending.code, suffix).queue();
                return;
            }
            ScarletDiscordCommands.this.pendingVrcLinks.remove(self.getId());
            ScarletDiscordCommands.this.discord.scarlet.data.linkIdToSnowflake(pending.vrcId, self.getId());
            LOG.info("Self-link verified: Discord user {} ({}) linked to VRChat user {} ({}) via bio code.", self.getEffectiveName(), self.getId(), pending.displayName, pending.vrcId);
            // Verified: grant the member role (if they don't have it yet), then invite to the group.
            boolean roleOk = pending.hadMemberRole || ScarletDiscordCommands.this.ensureMembersRole(self);
            String inviteResult = ScarletDiscordCommands.this.discord.tryAutoInviteToGroup(self, pending.vrcId);
            StringBuilder sb = new StringBuilder();
            sb.append("Verified! Linked your Discord account to VRChat user [").append(pending.displayName).append("](").append(VrcWeb.Home.user(pending.vrcId)).append(").");
            if (!pending.hadMemberRole)
                sb.append(roleOk ? "\nYou've been given the member role." : "\nI couldn't assign your member role automatically. Please contact staff.");
            if (inviteResult != null)
                sb.append('\n').append(inviteResult);
            event.getHook().editOriginal(sb.toString()).setComponents().queue();
        });
    }

    // unlink-vrchat-account

    @SlashCmd("unlink-vrchat-account")
    @Desc("Unlink your Discord account from your linked VRChat account")
    @Ephemeral
    public void unlinkVrchatAccount(SlashCommandInteractionEvent event, InteractionHook hook)
    {
        // Reply in the invoking user's own Discord language.
        java.util.Locale userLocale = net.sybyline.scarlet.util.I18n.ofDiscordTag(event.getUserLocale().getLocale());
        Member self = event.getMember();
        if (self == null)
        {
            hook.sendMessage(net.sybyline.scarlet.util.I18n.tr(userLocale, "discord.noMembership")).queue();
            return;
        }
        String linkedVrcId = ScarletDiscordCommands.this.discord.scarlet.data.globalMetadata_getSnowflakeId(self.getId());
        if (linkedVrcId == null || linkedVrcId.isEmpty())
        {
            hook.sendMessage(net.sybyline.scarlet.util.I18n.tr(userLocale, "discord.unlink.notLinked")).queue();
            return;
        }
        // Drop any in-flight verification, then clear both sides of the link.
        ScarletDiscordCommands.this.pendingVrcLinks.remove(self.getId());
        ScarletDiscordCommands.this.discord.scarlet.data.unlinkBySnowflake(self.getId());
        LOG.info("Self-unlink: Discord user {} ({}) unlinked from VRChat user {}.", self.getEffectiveName(), self.getId(), linkedVrcId);
        hook.sendMessageFormat(
                "Unlinked your Discord account from VRChat user [%s](%s).\n"
              + "This only removes the link record - it doesn't change your roles or VRChat group membership.\n"
              + "You can run `/link-vrchat-account` again to re-link and test.",
                linkedVrcId, VrcWeb.Home.user(linkedVrcId))
            .queue();
    }

    // unlink-vrchat-account-for (staff)

    @SlashCmd("unlink-vrchat-account-for")
    @Desc("Staff: unlink another member's Discord/VRChat link (by Discord user and/or VRChat user)")
    @DefaultPerms(Permission.MODERATE_MEMBERS)
    @Ephemeral
    public void unlinkVrchatAccountFor(SlashCommandInteractionEvent event, InteractionHook hook,
                                       @Required(false) @SlashOpt("discord-user") Member discordUser,
                                       @Required(false) @SlashOpt("vrchat-user") io.github.vrchatapi.model.User vrchatUser)
    {
        Member actor = event.getMember();
        if (actor == null)
        {
            hook.sendMessage("Could not resolve your Discord membership.").queue();
            return;
        }
        String targetSnowflake = discordUser == null ? null : discordUser.getId();
        String targetVrcId = vrchatUser != null
            ? vrchatUser.getId()
            : (event.getOption("vrchat-user") == null ? null : VrcIds.getAsString_user(event.getOption("vrchat-user")));
        if (targetSnowflake == null && (targetVrcId == null || targetVrcId.isEmpty()))
        {
            hook.sendMessage("Specify who to unlink: provide `discord-user` and/or `vrchat-user`.").queue();
            return;
        }
        ScarletData data = ScarletDiscordCommands.this.discord.scarlet.data;
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        if (targetSnowflake != null)
        {
            String vrc = data.unlinkBySnowflake(targetSnowflake);
            ScarletDiscordCommands.this.pendingVrcLinks.remove(targetSnowflake);
            if (vrc != null)
            {
                any = true;
                LOG.info("Staff-unlink: {} ({}) unlinked Discord {} from VRChat {}.", actor.getEffectiveName(), actor.getId(), targetSnowflake, vrc);
                sb.append(String.format("Unlinked <@%s> from VRChat user [%s](%s).\n", targetSnowflake, vrc, VrcWeb.Home.user(vrc)));
            }
            else
                sb.append(String.format("<@%s> wasn't linked to a VRChat account.\n", targetSnowflake));
        }
        if (targetVrcId != null && !targetVrcId.isEmpty())
        {
            String sf = data.unlinkById(targetVrcId);
            if (sf != null)
            {
                any = true;
                ScarletDiscordCommands.this.pendingVrcLinks.remove(sf);
                LOG.info("Staff-unlink: {} ({}) unlinked VRChat {} from Discord {}.", actor.getEffectiveName(), actor.getId(), targetVrcId, sf);
                sb.append(String.format("Unlinked VRChat user [%s](%s) from <@%s>.\n", targetVrcId, VrcWeb.Home.user(targetVrcId), sf));
            }
            else
                sb.append(String.format("VRChat user [%s](%s) wasn't linked to a Discord account.\n", targetVrcId, VrcWeb.Home.user(targetVrcId)));
        }
        sb.append(any
            ? "This only removes the link record - it doesn't change roles or VRChat group membership. They can run `/link-vrchat-account` to re-link."
            : "Nothing was linked, so nothing changed.");
        hook.sendMessage(sb.toString()).queue();
    }

    // discord-warn

    @FeatureGate("discord.kick_ban.enabled")
    @SlashCmd("discord-warn")
    @Desc("Warn a Discord server member")
    @DefaultPerms(Permission.MODERATE_MEMBERS)
    @Ephemeral
    public void discordWarn(SlashCommandInteractionEvent event, InteractionHook hook,
                            @SlashOpt("discord-user") Member discordUser,
                            @SlashOpt("reason") String reason)
    {
        if (!this.discord.scarlet.discordKickBanEnabled.get())
        {
            hook.sendMessage("The built-in Discord moderation commands are not enabled.\n" +
                "Enable them under **Settings -> Discord** in Scarlet.").queue();
            return;
        }
        if (discordUser == null)
        {
            hook.sendMessage("Please select a valid server member.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Guild guild =
            this.discord.jda.getGuildById(this.discord.guildSf);
        if (guild == null)
        {
            hook.sendMessage("Could not find the configured Discord server.").queue();
            return;
        }

        Member actorMember = event.getMember();
        if (actorMember == null || !actorMember.hasPermission(Permission.MODERATE_MEMBERS))
        {
            hook.sendMessage("You do not have permission to warn members.").queue();
            return;
        }

        if (discordUser.isOwner())
        {
            hook.sendMessageFormat("Cannot warn **%s** - they are the server owner.",
                MarkdownSanitizer.escape(discordUser.getEffectiveName())).queue();
            return;
        }

        if (!actorMember.canInteract(discordUser))
        {
            hook.sendMessageFormat("Cannot warn **%s** - their highest role is equal to or above yours.",
                MarkdownSanitizer.escape(discordUser.getEffectiveName())).queue();
            return;
        }

        String actor = actorMember.getEffectiveName();
        String targetName = discordUser.getEffectiveName();
        String targetId = discordUser.getId();
        String reasonText = reason != null && !reason.trim().isEmpty() ? reason.trim() : "No reason provided";
        String dmMessage = String.format(
            "You have received a warning in **%s** from %s.\n**Reason:** %s",
            guild.getName(), actor, reasonText);

        discordUser.getUser().openPrivateChannel().queue(
            channel -> channel.sendMessage(dmMessage).queue(
                sent -> {
                    LOG.info("Discord warning: {} ({}) warned in {} ({}) by {}. Reason: {}",
                        targetName, targetId, guild.getName(), guild.getId(), actor, reasonText);
                    this.discord.emitDiscordActionLog("Discord Warning", actorMember, targetName, targetId,
                        reasonText, true, "Warning DM delivered.", 0xFEE75C);
                    hook.sendMessageFormat("Warned **%s**.",
                        MarkdownSanitizer.escape(targetName)).queue();
                },
                error -> {
                    LOG.warn("Failed to send warning DM to {} ({}): {}",
                        targetName, targetId, error.getMessage());
                    this.discord.emitDiscordActionLog("Discord Warning", actorMember, targetName, targetId,
                        reasonText, false, "Failed to deliver warning DM: " + error.getMessage(), 0xED4245);
                    hook.sendMessageFormat("Could not DM **%s**; warning was not delivered: %s",
                        MarkdownSanitizer.escape(targetName), error.getMessage()).queue();
                }
            ),
            error -> {
                LOG.warn("Could not open DM channel for warning target {} ({}): {}",
                    targetName, targetId, error.getMessage());
                this.discord.emitDiscordActionLog("Discord Warning", actorMember, targetName, targetId,
                    reasonText, false, "Could not open DM channel: " + error.getMessage(), 0xED4245);
                hook.sendMessageFormat("Could not DM **%s**; warning was not delivered: %s",
                    MarkdownSanitizer.escape(targetName), error.getMessage()).queue();
            }
        );
    }

    // discord-ban

    @FeatureGate("discord.kick_ban.enabled")
    @SlashCmd("discord-ban")
    @Desc("Ban a Discord server member")
    @DefaultPerms(Permission.BAN_MEMBERS)
    @Ephemeral
    public void discordBan(SlashCommandInteractionEvent event, InteractionHook hook,
                           @SlashOpt("discord-user") Member discordUser,
                           @SlashOpt("reason") String reason)
    {
        if (!this.discord.scarlet.discordKickBanEnabled.get())
        {
            hook.sendMessage("The built-in Discord moderation commands are not enabled.\n" +
                "Enable them under **Settings -> Discord** in Scarlet.").queue();
            return;
        }
        if (discordUser == null)
        {
            hook.sendMessage("Please select a valid server member.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Guild guild =
            this.discord.jda.getGuildById(this.discord.guildSf);
        if (guild == null)
        {
            hook.sendMessage("Could not find the configured Discord server.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Member self = guild.getSelfMember();

        if (!self.hasPermission(Permission.BAN_MEMBERS))
        {
            hook.sendMessage("The bot does not have the **Ban Members** permission in this server.").queue();
            return;
        }

        if (discordUser.isOwner())
        {
            hook.sendMessageFormat("Cannot ban **%s** — they are the server owner.",
                MarkdownSanitizer.escape(discordUser.getEffectiveName())).queue();
            return;
        }

        if (!self.canInteract(discordUser))
        {
            hook.sendMessageFormat("Cannot ban **%s** — their highest role is equal to or above mine.",
                MarkdownSanitizer.escape(discordUser.getEffectiveName())).queue();
            return;
        }

        if (!DiscordModerationAuthorization.allowed(event.getMember(), discordUser, Permission.BAN_MEMBERS))
        {
            hook.sendMessage("You need Ban Members permission and a role above the target member.").queue();
            return;
        }
        String confirmId = "discord-ban-confirm:" + this.discord.prepareDiscordModeration(
            event.getUser().getId(), discordUser.getId(), reason, Permission.BAN_MEMBERS);

        String reasonLine = reason != null && !reason.trim().isEmpty()
            ? "\n**Reason:** " + MarkdownSanitizer.escape(reason.trim())
            : "";
        hook.sendMessageFormat(
            "Ban **%s** (`%s`) from the server?%s",
            MarkdownSanitizer.escape(discordUser.getEffectiveName()),
            discordUser.getUser().getName(),
            reasonLine)
            .setComponents(ActionRow.of(
                Button.danger(confirmId, "Ban"),
                Button.secondary("discord-ban-cancel", "Cancel")))
            .queue();
    }

    // discord-kick

    @FeatureGate("discord.kick_ban.enabled")
    @SlashCmd("discord-kick")
    @Desc("Kick a Discord server member")
    @DefaultPerms(Permission.KICK_MEMBERS)
    @Ephemeral
    public void discordKick(SlashCommandInteractionEvent event, InteractionHook hook,
                            @SlashOpt("discord-user") Member discordUser,
                            @SlashOpt("reason") String reason)
    {
        if (!this.discord.scarlet.discordKickBanEnabled.get())
        {
            hook.sendMessage("The built-in Discord moderation commands are not enabled.\n" +
                "Enable them under **Settings -> Discord** in Scarlet.").queue();
            return;
        }
        if (discordUser == null)
        {
            hook.sendMessage("Please select a valid server member.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Guild guild =
            this.discord.jda.getGuildById(this.discord.guildSf);
        if (guild == null)
        {
            hook.sendMessage("Could not find the configured Discord server.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Member self = guild.getSelfMember();

        if (!self.hasPermission(Permission.KICK_MEMBERS))
        {
            hook.sendMessage("The bot does not have the **Kick Members** permission in this server.").queue();
            return;
        }

        if (discordUser.isOwner())
        {
            hook.sendMessageFormat("Cannot kick **%s** — they are the server owner.",
                MarkdownSanitizer.escape(discordUser.getEffectiveName())).queue();
            return;
        }

        if (!self.canInteract(discordUser))
        {
            hook.sendMessageFormat("Cannot kick **%s** — their highest role is equal to or above mine.",
                MarkdownSanitizer.escape(discordUser.getEffectiveName())).queue();
            return;
        }

        if (!DiscordModerationAuthorization.allowed(event.getMember(), discordUser, Permission.KICK_MEMBERS))
        {
            hook.sendMessage("You need Kick Members permission and a role above the target member.").queue();
            return;
        }
        String confirmId = "discord-kick-confirm:" + this.discord.prepareDiscordModeration(
            event.getUser().getId(), discordUser.getId(), reason, Permission.KICK_MEMBERS);

        String reasonLine = reason != null && !reason.trim().isEmpty()
            ? "\n**Reason:** " + MarkdownSanitizer.escape(reason.trim())
            : "";
        hook.sendMessageFormat(
            "Kick **%s** (`%s`) from the server?%s",
            MarkdownSanitizer.escape(discordUser.getEffectiveName()),
            discordUser.getUser().getName(),
            reasonLine)
            .setComponents(ActionRow.of(
                Button.danger(confirmId, "Kick"),
                Button.secondary("discord-kick-cancel", "Cancel")))
            .queue();
    }

}

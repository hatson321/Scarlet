package net.sybyline.scarlet;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;

import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.model.GroupAccessType;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupJoinRequestAction;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.Instance;
import io.github.vrchatapi.model.InstanceRegion;
import io.github.vrchatapi.model.PerformanceRatings;
import io.github.vrchatapi.model.User;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.MarkdownUtil;
import net.sybyline.scarlet.ScarletDiscordJDA.InstanceCreation;
import net.sybyline.scarlet.server.discord.DInteractions.ButtonClk;
import net.sybyline.scarlet.server.discord.DInteractions.Ephemeral;
import net.sybyline.scarlet.server.discord.DInteractions.FeatureGate;
import net.sybyline.scarlet.server.discord.DInteractions.ModalSub;
import net.sybyline.scarlet.server.discord.DInteractions.StringSel;
import net.sybyline.scarlet.server.discord.DPerms;
import net.sybyline.scarlet.util.HttpURLInputStream;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.UniqueStrings;
import net.sybyline.scarlet.util.VRChatHelpDeskURLs;
import net.sybyline.scarlet.util.VrcIds;
import net.sybyline.scarlet.util.VrcWeb;

public class ScarletDiscordUI
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/JDA/UI");

    public ScarletDiscordUI(ScarletDiscordJDA discord)
    {
        this.discord = discord;
        discord.interactions.register(this);
    }

    final ScarletDiscordJDA discord;

    @ModalSub("watched-group-set-notes")
    public void watchedGroupSetNotes(ModalInteractionEvent event)
    {
        if (!this.checkConfigAccess(event, "edit watched group notes"))
            return;
        String[] parts = event.getModalId().split(":");
        String groupId = parts[1];
        ScarletWatchedGroups.WatchedGroup watchedGroup = this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
        if (watchedGroup == null)
        {
            event.reply("That group is not watched").setEphemeral(true).queue();
            return;
        }
        event.reply("Set notes for group").setEphemeral(true).queue();
        watchedGroup.notes = event.getValue("notes").getAsString();
        this.discord.scarlet.watchedGroups.save();
    }

    @ModalSub("watched-entity-set-notes")
    public void watchedEntitySetNotes(ModalInteractionEvent event)
    {
        if (!this.checkConfigAccess(event, "edit watched entity notes"))
            return;
        String[] parts = event.getModalId().split(":");
        String entityKind = parts[1],
               entityId = parts[2];
        ScarletDiscordCommands.WatchedEntity_<?> watchedEntityCommand = this.discord.discordCommands.watchedEntityCommands.get(entityKind);
        if (watchedEntityCommand == null)
        {
            LOG.error("@ModalSub(watched-entity-set-notes): Unknown watched entity kind `"+entityKind+"`: `"+entityId+"`");
            return;
        }
        watchedEntityCommand._setNotes(event, entityId);
    }

    @ButtonClk("edit-tags")
    @Ephemeral
    public void editTags(ButtonInteractionEvent event, InteractionHook hook)
    {
        // Every tag in dropdowns (25 per dropdown, up to five), like the original Edit tags. Each
        // dropdown filters as you type in it; `/tag add` in the thread searches ALL tags at once.
        this.sendAllTagMenus(event, hook);
    }

    /**
     * Optional browse-first moderation-tag picker. Based on Chloethecat's
     * contribution in https://github.com/Chloethecat/Scarlet (commit 62dc2d4).
     * The existing search-first picker above remains the default flow.
     */
    @ButtonClk("browse-tags")
    @Ephemeral
    public void browseTags(ButtonInteractionEvent event, InteractionHook hook)
    {
        // Kept so the Browse tags button on older posts still works; same as Edit tags now.
        this.sendAllTagMenus(event, hook);
    }

    void sendAllTagMenus(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryTagAccess(event.getMember(), event.getMessageChannel(), hook, auditEntryId))
            return;

        List<ScarletModerationTags.Tag> tags = this.discord.scarlet.moderationTags.getTags();
        if (tags == null || tags.isEmpty())
        {
            hook.sendMessage("No moderation tags!").setEphemeral(true).queue();
            return;
        }

        String tip = tags.size() > 25
            ? "Type in a dropdown to filter it. To search **all " + tags.size() + " tags** at once, use `/tag add` in this thread."
            : "Type in the dropdown to filter it. You can also use `/tag add` in this thread.";
        hook.sendMessage(tip)
            .addComponents(this.allTagMenus(tags, auditEntryId))
            .setEphemeral(true)
            .queue();
    }

    /** The audit entry a moderation thread belongs to, read from the Edit tags button on the thread's first message (works across restarts). */
    String auditEntryIdForThread(net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel)
    {
        if (channel == null || !channel.getType().isThread())
            return null;
        try
        {
            return channel.getHistoryFromBeginning(3).complete().getRetrievedHistory().stream()
                .map(Message::getComponentTree)
                .map($ -> $.findAll(Button.class))
                .flatMap(List::stream)
                .map(Button::getCustomId)
                .filter($ -> $ != null && $.startsWith("edit-tags:"))
                .map($ -> $.split(":")[1])
                .findFirst()
                .orElse(null);
        }
        catch (Exception ex)
        {
            LOG.warn("Could not read moderation thread "+channel.getId()+" to find its audit entry", ex);
            return null;
        }
    }

    /** Every tag, split across Discord's 25-option menus (max five rows = 125 tags, Scarlet's tag cap), with existing tags pre-ticked. */
    List<ActionRow> allTagMenus(List<ScarletModerationTags.Tag> tags, String auditEntryId)
    {
        if (tags.size() > 125)
            tags = tags.subList(0, 125);
        // Discord supports up to 25 options per menu and five action rows.
        StringSelectMenu.Builder[] builders = new StringSelectMenu.Builder[(tags.size() - 1) / 25 + 1];
        for (int i = 0; i < builders.length; i++)
            builders[i] = StringSelectMenu.create((i == 0 ? "select-tags:" : "select-tags-"+i+":") + auditEntryId);

        for (int i = 0; i < tags.size(); i++)
        {
            ScarletModerationTags.Tag tag = tags.get(i);
            String label = tag.label != null ? tag.label : tag.value;
            if (tag.description == null || tag.description.isEmpty())
                builders[i / 25].addOption(MiscUtils.maybeEllipsis(100, label), MiscUtils.maybeEllipsis(100, tag.value));
            else
                builders[i / 25].addOption(MiscUtils.maybeEllipsis(100, label), MiscUtils.maybeEllipsis(100, tag.value), MiscUtils.maybeEllipsis(100, tag.description));
        }

        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        for (int i = 0; i < builders.length; i++)
        {
            StringSelectMenu.Builder builder = builders[i];
            builder.setMinValues(0).setMaxValues(builder.getOptions().size())
                .setPlaceholder("Select tags ("+(i * 25 + 1)+"-"+(i * 25 + builder.getOptions().size())+")");
            if (auditEntryMeta != null && auditEntryMeta.hasTags())
            {
                List<String> optionValues = builder.getOptions().stream().map(SelectOption::getValue).collect(Collectors.toList());
                builder.setDefaultValues(auditEntryMeta.entryTags.stream().filter(optionValues::contains).collect(Collectors.toList()));
            }
        }

        return new ArrayList<>(Arrays.asList(MiscUtils.map(builders, ActionRow[]::new, $ -> ActionRow.of($.build()))));
    }

    @StringSel("select-tags-search")
    @Ephemeral
    public void selectTagsSearch(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }

    @StringSel("select-tags")
    @Ephemeral
    public void selectTags(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }
    @StringSel("select-tags-1")
    @Ephemeral
    public void selectTags1(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }
    @StringSel("select-tags-2")
    @Ephemeral
    public void selectTags2(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }
    @StringSel("select-tags-3")
    @Ephemeral
    public void selectTags3(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }
    @StringSel("select-tags-4")
    @Ephemeral
    public void selectTags4(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }
    private void selectTags_(StringSelectInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryTagAccess(event.getMember(), event.getMessageChannel(), hook, auditEntryId))
            return;
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata_editTags(auditEntryId,
                event.getSelectMenu().getOptions().stream().map(SelectOption::getValue).filter($ -> !event.getValues().contains($)).toArray(String[]::new),
                event.getValues().toArray(new String[0]));
        
        String joined = auditEntryMeta.entryTags.stream().map(this.discord.scarlet.moderationTags::getTagLabel).collect(Collectors.joining(", ", "### Setting tags:\n", ""));
        
        MessageEmbed[] embeds = auditEntryMeta.entryTags.stream().map(this.discord.scarlet.moderationTags::getTag).filter(Objects::nonNull).map(tag -> new EmbedBuilder().setAuthor(MiscUtils.maybeEllipsis(256, tag.label)).setDescription(MiscUtils.maybeEllipsis(4096, tag.description)).build()).toArray(MessageEmbed[]::new);
        
        this.discord.interactions.new Pagination(event.getId(), embeds, 10).withAdditional((action, page) -> action.setContent(joined)).queue(hook);
        
        this.updateAuxMessage(event.getChannel(), auditEntryMeta);
    }

    @ButtonClk("vrchat-user-edit-manager-notes")
    public void vrchatUserEditManagerNotes(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");

        String vrcTargetId = parts[1];
        if (!this.checkGroupModerationAccess(event.getMember(), event, "edit manager notes"))
            return;
        
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            event.reply(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        
        long within1day = System.currentTimeMillis() - 86400_000L;
        User sc = this.discord.scarlet.vrc.getUser(vrcTargetId, within1day);
        if (sc == null)
        {
            event.replyFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return;
        }
        
        GroupMember glm = this.discord.scarlet.vrc.getGroupMembership(this.discord.scarlet.vrc.groupId, vrcTargetId);
        String value = glm == null ? null : glm.getManagerNotes();
        
        event.replyModal(Modal.create("vrchat-user-edit-manager-notes:"+vrcTargetId, "Manager notes for "+MarkdownSanitizer.escape(sc.getDisplayName()))
                .addComponents(Label.of("Notes", TextInput.create("manager-notes:"+vrcTargetId, TextInputStyle.PARAGRAPH)
                    .setValue(value).build()))
            .build())
            .queue();
    }

    @ModalSub("vrchat-user-edit-manager-notes")
    public void vrchatUserEditManagerNotesModal(ModalInteractionEvent event)
    {
        String[] parts = event.getModalId().split(":");

        String vrcTargetId = parts[1];
        if (!this.checkGroupModerationAccess(event.getMember(), event, "edit manager notes"))
            return;
        
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            event.reply(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        
        long within1day = System.currentTimeMillis() - 86400_000L;
        User sc = this.discord.scarlet.vrc.getUser(vrcTargetId, within1day);
        if (sc == null)
        {
            event.replyFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return;
        }
        
        String value = event.getValue("manager-notes:"+vrcTargetId).getAsString();
        this.discord.scarlet.vrc.updateGroupMembershipNotes(this.discord.scarlet.vrc.groupId, vrcTargetId, value);
        event.reply("Updated manager notes.").queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
    }

    @ButtonClk("vrchat-user-ban")
    public void vrchatUserBan(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");

        String vrcTargetId = parts[1];
        
        this._vrchatUserBan(hook, event.getMember(), vrcTargetId);
    }

    boolean _vrchatUserBan(InteractionHook hook, Member member, String vrcTargetId)
    {
        return this._vrchatUserBan(hook, member, vrcTargetId, "vrchat-user-ban");
    }

    boolean _vrchatUserBan(InteractionHook hook, Member member, String vrcTargetId, String operation)
    {
        if (member == null)
        {
            hook.sendMessage("Cannot verify the requesting Discord member.").setEphemeral(true).queue();
            return false;
        }
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(member.getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(member)).setEphemeral(true).queue();
            return false;
        }
        if ("immediate-ban-confirm".equals(operation))
        {
            ScarletPendingModActions.BanInfo original = this.discord.scarlet.pendingModActions.getBanInfo(vrcTargetId);
            if (original == null || !member.getId().equals(original.ownerSnowflake)
                || !vrcActorId.equals(original.actorUserId)
                || original.vrchatGroupId == null || !original.vrchatGroupId.equals(this.discord.scarlet.vrc.groupId)
                || original.discordGuildId == null || !original.discordGuildId.equals(this.discord.guildSf))
            {
                hook.sendMessage("This ban confirmation has missing or changed requester/group information. Cancel it and submit a new request.")
                    .setEphemeral(true).queue();
                return false;
            }
        }
        
        long within1day = System.currentTimeMillis() - 86400_000L;
        boolean training = ScarletSimulation.isTrainingId(vrcTargetId);
        User sc = training ? null : this.discord.scarlet.vrc.getUser(vrcTargetId, within1day);
        if (sc == null && !training)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return false;
        }

        if (training)
        {
            if (!this.checkTrainingBanPermission(hook, member)) return false;
            hook.sendMessage("Banned the training user. *(Training simulation — no real ban occurred.)*").setEphemeral(false).queue();
            return true;
        }
        ScarletDiscordJDA.VRChatBanAccess access = this.discord.checkVRChatBanAccess(member.getId(), vrcActorId, vrcTargetId,
            net.sybyline.scarlet.server.discord.DPerms.PermType.BUTTON_PRESS, operation);
        if (!access.allowed())
        {
            hook.sendMessage(access.message).setEphemeral(true).queue();
            return false;
        }
        
        GroupMemberStatus status = this.discord.scarlet.vrc.getGroupMembershipStatus(this.discord.scarlet.vrc.groupId, vrcTargetId);
        
        if (status == GroupMemberStatus.BANNED)
        {
            hook.sendMessage("This VRChat user is already banned").setEphemeral(true).queue();
            return false;
        }
        
        ScarletDiscordJDA.VRChatBanAttempt attempt = this.discord.executeVRChatBan(member.getId(), access.actorId, vrcTargetId,
            net.sybyline.scarlet.server.discord.DPerms.PermType.BUTTON_PRESS, operation);
        if (!attempt.succeeded())
        {
            hook.sendMessage(attempt.message).setEphemeral(true).queue();
            return false;
        }
        
        hook.sendMessageFormat("Banned %s", sc.getDisplayName()).setEphemeral(false).queue();
        return true;
    }

    // ── Timed bans ───────────────────────────────────────────────────────────────
    // Ban a user now and schedule an automatic unban after a fixed window. The expiry sweep
    // (Scarlet.processTimedBans) performs the unban later, attributed to the original actor.
    // Requires the same group ban-management permission as a normal ban.
    @ButtonClk("timed-ban")
    public void timedBan(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        long hours;
        try
        {
            hours = Long.parseLong(parts[1]);
        }
        catch (NumberFormatException ex)
        {
            hook.sendMessage("Invalid timed-ban duration").setEphemeral(true).queue();
            return;
        }
        this._timedBan(hook, event.getMember(), parts[2], hours * 3600_000L);
    }

    @ButtonClk("timed-ban-custom")
    public void timedBanCustom(ButtonInteractionEvent event)
    {
        if (!Boolean.TRUE.equals(this.discord.scarlet.timedBansEnabled.get()))
        {
            event.reply("Timed bans are disabled in Scarlet's settings.").setEphemeral(true).queue();
            return;
        }
        String targetUserId = event.getButton().getCustomId().split(":")[1];
        Modal.Builder m = Modal.create("timed-ban-custom:"+targetUserId, "Timed ban")
            .addComponents(Label.of("Duration (e.g. 30m, 6h, 3d, 2w, or a number of hours)", TextInput
                    .create("input-duration:"+targetUserId, TextInputStyle.SHORT)
                    .setRequired(true)
                    .setPlaceholder("7d").build()));
        event.replyModal(m.build()).queue();
    }

    @ModalSub("timed-ban-custom")
    public void timedBanCustom(ModalInteractionEvent event)
    {
        String targetUserId = event.getModalId().split(":")[1];
        long durationMillis = parseDurationMillis(event.getValue("input-duration:"+targetUserId).getAsString());
        if (durationMillis <= 0L)
        {
            event.reply("Couldn't read that duration. Try e.g. 30m, 6h, 3d, 2w, or a plain number of hours.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        this._timedBan(event.getHook(), event.getMember(), targetUserId, durationMillis,
            net.sybyline.scarlet.server.discord.DPerms.PermType.MODAL_SUBMIT, "timed-ban-custom");
    }

    boolean _timedBan(InteractionHook hook, Member member, String vrcTargetId, long durationMillis)
    {
        return this._timedBan(hook, member, vrcTargetId, durationMillis,
            net.sybyline.scarlet.server.discord.DPerms.PermType.BUTTON_PRESS, "timed-ban");
    }

    boolean _timedBan(InteractionHook hook, Member member, String vrcTargetId, long durationMillis,
        net.sybyline.scarlet.server.discord.DPerms.PermType type, String operation)
    {
        if (!Boolean.TRUE.equals(this.discord.scarlet.timedBansEnabled.get()))
        {
            hook.sendMessage("Timed bans are disabled in Scarlet's settings.").setEphemeral(true).queue();
            return false;
        }
        if (member == null)
        {
            hook.sendMessage("Cannot verify the requesting Discord member.").setEphemeral(true).queue();
            return false;
        }
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(member.getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(member)).setEphemeral(true).queue();
            return false;
        }

        long within1day = System.currentTimeMillis() - 86400_000L;
        boolean training = ScarletSimulation.isTrainingId(vrcTargetId);
        User sc = training ? null : this.discord.scarlet.vrc.getUser(vrcTargetId, within1day);
        if (sc == null && !training)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return false;
        }

        if (training)
        {
            if (!this.checkTrainingBanPermission(hook, member)) return false;
            hook.sendMessageFormat("Banned the training user for %s. *(Training simulation — no real ban or unban timer was created.)*", formatDuration(durationMillis)).setEphemeral(false).queue();
            return true;
        }
        ScarletDiscordJDA.VRChatBanAccess access = this.discord.checkVRChatBanAccess(member.getId(), vrcActorId, vrcTargetId,
            type, operation);
        if (!access.allowed())
        {
            hook.sendMessage(access.message).setEphemeral(true).queue();
            return false;
        }

        long expiresAt = System.currentTimeMillis() + durationMillis;
        long unbanEpochSec = expiresAt / 1000L;
        String human = formatDuration(durationMillis);

        // Only a newly authorized successful ban can create an automatic-unban timer.
        // Existing bans are refused by the policy: their previous rank is not verifiable.
        ScarletDiscordJDA.VRChatBanAttempt attempt = this.discord.executeVRChatBan(member.getId(), access.actorId, vrcTargetId,
            type, operation, expiresAt);
        if (!attempt.succeeded())
        {
            hook.sendMessage(attempt.message).setEphemeral(true).queue();
            return false;
        }
        hook.sendMessageFormat("Banned %s for %s — automatic unban <t:%d:R>", sc.getDisplayName(), human, Long.valueOf(unbanEpochSec)).setEphemeral(false).queue();
        return true;
    }

    private boolean checkTrainingBanPermission(InteractionHook hook, Member member)
    {
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false))
            return true;
        hook.sendMessage("You do not have permission to ban users, including training actions.").setEphemeral(true).queue();
        return false;
    }

    /** Parse a duration like "30m", "6h", "3d", "2w", or a bare number (interpreted as hours). Returns millis, or -1 if unparseable. */
    static long parseDurationMillis(String s)
    {
        if (s == null)
            return -1L;
        s = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty())
            return -1L;
        Matcher mm = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*([smhdw]?)$").matcher(s);
        if (!mm.matches())
            return -1L;
        double value;
        try
        {
            value = Double.parseDouble(mm.group(1));
        }
        catch (NumberFormatException ex)
        {
            return -1L;
        }
        long unitMillis;
        switch (mm.group(2))
        {
        case "s": unitMillis = 1000L; break;
        case "m": unitMillis = 60_000L; break;
        case "d": unitMillis = 86_400_000L; break;
        case "w": unitMillis = 604_800_000L; break;
        case "h": default: unitMillis = 3_600_000L; break; // bare number = hours
        }
        long millis = (long) (value * unitMillis);
        return millis > 0L ? millis : -1L;
    }

    static String formatDuration(long millis)
    {
        long totalMinutes = millis / 60_000L;
        long days = totalMinutes / 1440L;
        long hours = (totalMinutes % 1440L) / 60L;
        long minutes = totalMinutes % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0L)
            sb.append(days).append('d');
        if (hours > 0L)
            sb.append(hours).append('h');
        if (minutes > 0L && days == 0L)
            sb.append(minutes).append('m');
        if (sb.length() == 0)
            sb.append(totalMinutes).append('m');
        return sb.toString();
    }

    // ── Group join-request actions (Accept / Reject / Block) ─────────────────────
    // Buttons on the "Created Request" moderation post let a moderator respond to a request
    // to join a request-gated (private) group without leaving Discord. All require the VRChat
    // group's invite-management permission.
    @ButtonClk("group-request-accept")
    public void groupRequestAccept(ButtonInteractionEvent event, InteractionHook hook)
    {
        this._groupRequestRespond(hook, event.getMember(), event.getButton().getCustomId().split(":")[1], GroupJoinRequestAction.ACCEPT, null, "Accepted");
    }

    @ButtonClk("group-request-reject")
    public void groupRequestReject(ButtonInteractionEvent event, InteractionHook hook)
    {
        this._groupRequestRespond(hook, event.getMember(), event.getButton().getCustomId().split(":")[1], GroupJoinRequestAction.REJECT, Boolean.FALSE, "Rejected");
    }

    @ButtonClk("group-request-block")
    public void groupRequestBlock(ButtonInteractionEvent event, InteractionHook hook)
    {
        this._groupRequestRespond(hook, event.getMember(), event.getButton().getCustomId().split(":")[1], GroupJoinRequestAction.REJECT, Boolean.TRUE, "Blocked");
    }

    boolean _groupRequestRespond(InteractionHook hook, Member member, String vrcTargetId, GroupJoinRequestAction action, Boolean block, String pastTense)
    {
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(member.getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(member)).setEphemeral(true).queue();
            return false;
        }
        if (!this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_invites_manage, member))
        {
            hook.sendMessage("You do not have permission to manage group join requests.").setEphemeral(true).queue();
            return false;
        }
        if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_invites_manage, hook))
            return false;

        User sc = this.discord.scarlet.vrc.getUser(vrcTargetId, System.currentTimeMillis() - 86400_000L);
        String name = sc != null ? sc.getDisplayName() : vrcTargetId;
        if (!this.discord.scarlet.vrc.respondToGroupJoinRequest(vrcTargetId, action, block))
        {
            hook.sendMessageFormat("Failed to respond to %s's join request", name).setEphemeral(true).queue();
            return false;
        }
        hook.sendMessageFormat("%s %s's join request", pastTense, name).setEphemeral(false).queue();
        return true;
    }

    @StringSel("immediate-ban-select-tags")
    public void immediateBanSelectTags(StringSelectInteractionEvent event)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String targetUserId = parts[1];
        if (!this.checkPendingBanOwnership(event, targetUserId))
            return;
        if (this.discord.scarlet.pendingModActions.setBanInfoTags(targetUserId, event.getValues().toArray(new String[0])))
        {
            event.reply("No pending ban").setEphemeral(true).queue();
            return;
        }
        event.deferEdit().queue();
    }

    @ButtonClk("immediate-ban-edit-desc")
    public void immediateBanEditDesc(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String targetUserId = parts[1];
        if (!this.checkPendingBanOwnership(event, targetUserId))
            return;
        
        Modal.Builder m = Modal.create("immediate-ban-edit-desc:"+targetUserId, "Edit description")
            .addComponents(Label.of("Input description", TextInput
                    .create("input-desc:"+targetUserId, TextInputStyle.PARAGRAPH)
                    .setRequired(true)
                    .setPlaceholder("Event description").build()))
            ;
        
        event.replyModal(m.build()).queue();
    }

    @ModalSub("immediate-ban-edit-desc")
    public void immediateBanEditDesc(ModalInteractionEvent event)
    {
        String[] parts = event.getModalId().split(":");
        String targetUserId = parts[1];
        if (!this.checkPendingBanOwnership(event, targetUserId))
            return;
        if (this.discord.scarlet.pendingModActions.setBanInfoDescription(targetUserId, event.getValue("input-desc:"+targetUserId).getAsString()))
        {
            event.reply("No pending ban").setEphemeral(true).queue();
            return;
        }
        event.deferEdit().queue();
    }

    @ButtonClk("immediate-ban-cancel")
    public void immediateBanCancel(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String targetUserId = parts[1];
        if (!this.checkPendingBanOwnership(event, targetUserId))
            return;
        event.deferEdit().queue();
        this.discord.scarlet.pendingModActions.pollBanInfo(targetUserId);
        event.getMessage().delete().queue();
    }

    @ButtonClk("immediate-ban-confirm")
    public void immediateBanConfirm(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String targetUserId = parts[1];
        if (!this.checkPendingBanOwnership(event.getMember(), hook, event.getUser().getId(), targetUserId))
            return;
        if (this._vrchatUserBan(hook, event.getMember(), targetUserId, "immediate-ban-confirm"))
        {
            event.getMessage().delete().queue();
        }
    }

    static final Pattern FIND_USER_IDS = Pattern.compile("\\W*(?<id>"+VrcIds.P_ID_USER+")\\W*");
    @ModalSub("vrchat-user-ban-multi")
    public void vrchatUserBanMulti(ModalInteractionEvent event)
    {
        String requestedGroupId = this.discord.scarlet.vrc.groupId, requestedGuildId = this.discord.guildSf;
        // Fresh checks for every requested target can exceed Discord's acknowledgement window.
        event.deferReply(true).queue();
        InteractionHook hook = event.getHook();
        ScarletDiscordJDA.VRChatBanAccess requester = this.discord.resolveVRChatBanRequester(event.getUser().getId(), null,
            net.sybyline.scarlet.server.discord.DPerms.PermType.MODAL_SUBMIT, "vrchat-user-ban-multi");
        if (!requester.allowed())
        {
            hook.sendMessage(requester.message).setEphemeral(true).queue();
            return;
        }
        List<ScarletDiscordJDA.Action> banActions = new ArrayList<>();
        
        for (Matcher m = FIND_USER_IDS.matcher(event.getValue("target-ids").getAsString()); m.find();)
        {
            String targetId = m.group("id");
            ScarletDiscordJDA.VRChatBanAccess access = this.discord.checkVRChatBanAccess(event.getUser().getId(), requester.actorId,
                targetId, net.sybyline.scarlet.server.discord.DPerms.PermType.MODAL_SUBMIT, "vrchat-user-ban-multi");
            if (!access.allowed())
            {
                hook.sendMessage("No bans were queued: " + access.message).setEphemeral(true).queue();
                return;
            }
            banActions.add(new ScarletDiscordJDA.Action("ban", access.actorId, targetId, null, event.getUser().getId(),
                requestedGuildId, requestedGroupId));
        }
        
        if (banActions.isEmpty())
        {
            hook.sendMessage("No user ids found!").setEphemeral(true).queue();
            return;
        }
        
        this.discord.queuedActions.addAll(banActions);
        
        hook.sendMessageFormat("Queuing %s user ban(s)", banActions.size()).setEphemeral(true).queue();
    }

    @ButtonClk("vrchat-user-unban")
    @Ephemeral
    public void vrchatUserUnban(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");

        String vrcTargetId = parts[1];
        
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        
        long within1day = System.currentTimeMillis() - 86400_000L;
        boolean training = ScarletSimulation.isTrainingId(vrcTargetId);
        User sc = training ? null : this.discord.scarlet.vrc.getUser(vrcTargetId, within1day);
        if (sc == null && !training)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
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
        if (training)
        {
            // Training user: the permission checks above are real; the unban itself is simulated.
            hook.sendMessage("Unbanned the training user. *(Training simulation — no real action occurred.)*").setEphemeral(false).queue();
            return;
        }
        if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_bans_manage, hook))
            return;
        
        GroupMemberStatus status = this.discord.scarlet.vrc.getGroupMembershipStatus(this.discord.scarlet.vrc.groupId, vrcTargetId);
        
        if (status != GroupMemberStatus.BANNED)
        {
            hook.sendMessage("This VRChat user is not banned").setEphemeral(true).queue();
            return;
        }
        
        if (this.discord.scarlet.pendingModActions.addPending(GroupAuditType.USER_UNBAN, vrcTargetId, vrcActorId) != null)
        {
            hook.sendMessage("This VRChat user currently has automated/assisted moderation pending, please retry later").setEphemeral(true).queue();
            return;
        }
        
        if (!this.discord.scarlet.vrc.unbanFromGroup(vrcTargetId))
        {
            this.discord.scarlet.pendingModActions.pollPending(GroupAuditType.USER_UNBAN, vrcTargetId);
            hook.sendMessageFormat("Failed to unban %s", sc.getDisplayName()).setEphemeral(true).queue();
            return;
        }
        
        hook.sendMessageFormat("Unbanned %s", sc.getDisplayName()).setEphemeral(false).queue();
    }

    @ModalSub("vrchat-user-unban-multi")
    public void vrchatUserUnbanMulti(ModalInteractionEvent event)
    {
        String requestedGroupId = this.discord.scarlet.vrc.groupId, requestedGuildId = this.discord.guildSf;
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
        
        if (!java.util.Objects.equals(requestedGroupId, this.discord.scarlet.vrc.groupId)
            || !java.util.Objects.equals(requestedGuildId, this.discord.guildSf))
        {
            event.reply("The configured group changed during verification. Reopen the command; no unbans were queued.").setEphemeral(true).queue();
            return;
        }
        List<ScarletDiscordJDA.Action> unbanActions = new ArrayList<>();
        
        for (Matcher m = FIND_USER_IDS.matcher(event.getValue("target-ids").getAsString()); m.find();)
            unbanActions.add(new ScarletDiscordJDA.Action("unban", vrcActorId, m.group("id"), null,
                event.getUser().getId(), requestedGuildId, requestedGroupId));
        
        if (unbanActions.isEmpty())
        {
            event.reply("No user ids found!").setEphemeral(true).queue();
            return;
        }
        
        this.discord.queuedActions.addAll(unbanActions);
        
        event.replyFormat("Queuing %s user unban(s)", unbanActions.size()).setEphemeral(true).queue();
    }

    @ButtonClk("event-redact")
    @Ephemeral
    public void eventRedact(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryModerationAccess(event.getMember(), hook, auditEntryId))
            return;
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta == null)
        {
            hook.sendMessage("Failed to find the audit event.").setEphemeral(true).queue();
            return;
        }
        
        if (auditEntryMeta.entryRedacted)
        {
            hook.sendMessage("Event is already redacted.").setEphemeral(true).queue();
            return;
        }
        
        this.discord.beginLocalSummaryPrivacyChange();
        try
        {
            auditEntryMeta = this.discord.scarlet.data.updateAuditEntryMetadata(auditEntryId, current -> {
                current.entryRedacted = true;
                return current;
            });
        }
        finally { this.discord.endLocalSummaryPrivacyChange(); }
        if (auditEntryMeta == null)
        {
            hook.sendMessage("The audit event is no longer available. Nothing changed.").setEphemeral(true).queue();
            return;
        }
        hook.sendMessage("Event redacted.").setEphemeral(false).queue();
        this.updateAuxMessage(event.getChannel(), auditEntryMeta);
    }
    
    void updateAuxMessage(MessageChannel channel, ScarletData.AuditEntryMetadata auditEntryMeta)
    {
        if (auditEntryMeta.hasMessage() && auditEntryMeta.auxMessageSnowflake != null && auditEntryMeta.entry != null)
        {
            String actorId = auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorId : auditEntryMeta.entry.getActorId();
            ScarletData.UserMetadata actorMeta = this.discord.scarlet.data.userMetadata(actorId);
            String content = auditEntryMeta.entryRedacted ? "# **__REDACTED__**\n\n" : "";
            // An old message can outlive routing changes or a newly confidential staff identity.
            // Edits must not introduce (or reintroduce) a confidential name/mention.
            boolean confidential = ScarletAuditPrivacy.confidential(auditEntryMeta, this.discord.scarlet.secretStaffList::isSecretStaffId);
            content = content + (confidential ? "Confidential staff action" : actorMeta == null || actorMeta.userSnowflake == null
                ? "Unknown Discord id for actor " + (auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorDisplayName : auditEntryMeta.entry.getActorDisplayName())
                : "<@" + actorMeta.userSnowflake + ">");
            if (auditEntryMeta.entryTags != null && auditEntryMeta.entryTags.size() > 0)
            {
                String joined = auditEntryMeta.entryTags.strings().stream().map(this.discord.scarlet.moderationTags::getTagLabel).collect(Collectors.joining(", "));
                content = content + "\n### Tags:\n" + joined;
            }
            if (!confidential && auditEntryMeta.entryDescription != null && !auditEntryMeta.entryDescription.trim().isEmpty())
            {
                content = content + "\n### Description:\n" + auditEntryMeta.entryDescription;
            }
            channel.editMessageById(auditEntryMeta.auxMessageSnowflake, content).queue();
        }
    }

    @ButtonClk("event-unredact")
    @Ephemeral
    public void eventUnredact(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryModerationAccess(event.getMember(), hook, auditEntryId))
            return;
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta == null)
        {
            hook.sendMessage("Failed to find the audit event.").setEphemeral(true).queue();
            return;
        }
        
        if (!auditEntryMeta.entryRedacted)
        {
            hook.sendMessage("Event isn't yet redacted.").setEphemeral(true).queue();
            return;
        }
        
        this.discord.beginLocalSummaryPrivacyChange();
        try
        {
            auditEntryMeta = this.discord.scarlet.data.updateAuditEntryMetadata(auditEntryId, current -> {
                current.entryRedacted = false;
                return current;
            });
        }
        finally { this.discord.endLocalSummaryPrivacyChange(); }
        if (auditEntryMeta == null)
        {
            hook.sendMessage("The audit event is no longer available. Nothing changed.").setEphemeral(true).queue();
            return;
        }
        hook.sendMessage("Event unredacted.").setEphemeral(false).queue();
        this.updateAuxMessage(event.getChannel(), auditEntryMeta);
    }

    @ButtonClk("new-instance-create")
    @Ephemeral
    public void newInstanceCreate(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        long within1day = System.currentTimeMillis() - 86400_000L;
        
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        User sc = this.discord.scarlet.vrc.getUser(vrcActorId, within1day);
        if (sc == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcActorId).setEphemeral(true).queue();
            return;
        }
        String vrcActorDisplayName = sc == null ? vrcActorId : sc.getDisplayName();
        
        String ictoken = parts[1];
        
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event.getMember(), hook, event.getUser().getId(), ic))
            return;
        
        if (ic == null)
        {
            hook.deleteMessageById(event.getMessageId()).queue();
            hook.sendMessage("This interaction timed out.").queue();
            hook.deleteOriginal().delay(5_000L, TimeUnit.MILLISECONDS).queue();
            return;
        }
        
        switch (ic.groupAccessType)
        {
        case PUBLIC: {
            if (!this.discord.checkMemberRespondVrcPerms(GroupPermissions.group_instance_public_create, hook, event.getMember()))
                return;
        } break;
        case PLUS: {
            if (!this.discord.checkMemberRespondVrcPerms(GroupPermissions.group_instance_plus_create, hook, event.getMember()))
                return;
        } break;
        case MEMBERS: {
            if (!this.discord.checkMemberRespondVrcPerms(GroupPermissions.group_instance_open_create, hook, event.getMember()))
                return;
            if (ic.roleIds != null && !this.discord.checkMemberRespondVrcPerms(GroupPermissions.group_instance_restricted_create, hook, event.getMember()))
                return;
        } break;
        }

        if (ic.ageGate != null && ic.ageGate.booleanValue() && !this.discord.checkMemberRespondVrcPerms(GroupPermissions.group_instance_age_gated_create, hook, event.getMember()))
        {
            hook.sendMessage("You do not have permission to create age gated instances.").setEphemeral(true).queue();
            return;
        }
        
        hook.deleteMessageById(event.getMessageId()).queue();

        switch (ic.groupAccessType)
        {
        case PUBLIC: {
            if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_instance_public_create, hook))
                return;
        } break;
        case PLUS: {
            if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_instance_plus_create, hook))
                return;
        } break;
        case MEMBERS: {
            if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_instance_open_create, hook))
                return;
            if (ic.roleIds != null && !this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_instance_restricted_create, hook))
                return;
        } break;
        }
        
        if (ic.ageGate != null && ic.ageGate.booleanValue() && !this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_instance_age_gated_create, hook))
            return;
        
        Instance instance;
        try
        {
//            instance = new InstancesApi(this.discord.scarlet.vrc.client).createInstance(ic.createRequest());
            instance = this.discord.scarlet.vrc.createInstanceEx(ic.createRequestEx());
        }
        catch (ApiException apiex)
        {
            String message = apiex.getResponseBody();
            try
            {
                message = JSON.<io.github.vrchatapi.model.Error>deserialize(message, io.github.vrchatapi.model.Error.class).getError().getMessage();
            }
            catch (Exception ex)
            {
            }
            hook.sendMessageFormat("Failed to create new instance: %s", message).setEphemeral(true).queue();
            return;
        }
        
        this.discord.scarlet.pendingModActions.addPending(GroupAuditType.INSTANCE_CREATE, instance.getId(), vrcActorId);
        String worldName = this.discord.getLocationName(instance.getWorldId());
        
        LOG.info(String.format("%s (%s) opened an instance of %s: %s", vrcActorDisplayName, vrcActorId, worldName, instance.getId()));
        hook.sendMessageFormat("Created [new %s instance](%s)", worldName, VrcWeb.Home.instance(instance.getWorldId(), instance.getInstanceId())).setEphemeral(true).queue();
        
    }

    @ButtonClk("new-instance-cancel")
    @Ephemeral
    public void newInstanceCancel(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event.getMember(), hook, event.getUser().getId(), ic))
            return;
        this.discord.instanceCreation.remove(ictoken);
        hook.deleteMessageById(event.getMessageId()).queue();
        hook.deleteOriginal().queue();
    }

    @ButtonClk("new-instance-modal")
    public void newInstanceModal(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        event.replyModal(Modal.create("new-instance-modal:"+ictoken, "Additional options")
                .addComponents(Label.of("Display name (unknown purpose)", TextInput.create("display-name:"+ictoken, TextInputStyle.SHORT)
                    .setValue(ic == null ? null : ic.displayName)
                    .build()))
                .build())
            .queue();
    }

    @ModalSub("new-instance-modal")
    public void newInstanceModal(ModalInteractionEvent event)
    {
        String[] parts = event.getModalId().split(":");
        String ictoken = parts[1];
        String displayName = event.getValue("display-name:"+parts[1]).getAsString();
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        if (ic == null)
        {
            event.reply("This interaction has timed out.").queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
            return;
        }
        ic.displayName = displayName;
        event.deferEdit().queue();
    }

    @StringSel("new-instance-region")
    public void newInstanceRegion(StringSelectInteractionEvent event)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        if (ic != null) try
        {
            ic.region = event.getValues().stream().findFirst().map(InstanceRegion::fromValue).get();
            event.deferEdit().queue();
            return;
        }
        catch (RuntimeException rex)
        {
        }
        event.reply("Interaction timed out").setEphemeral(true).queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
    }

    @StringSel("new-instance-access-type")
    public void newInstanceAccessType(StringSelectInteractionEvent event)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        if (ic != null) try
        {
            ic.groupAccessType = event.getValues().stream().findFirst().map(GroupAccessType::fromValue).get();
            event.deferEdit().queue();
            return;
        }
        catch (RuntimeException rex)
        {
        }
        event.reply("Interaction timed out").setEphemeral(true).queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
    }

    @StringSel("new-instance-roles")
    public void newInstanceRoles(StringSelectInteractionEvent event)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        if (ic != null) try
        {
            ic.roleIds = new ArrayList<>(event.getValues());
            event.deferEdit().queue();
            return;
        }
        catch (RuntimeException rex)
        {
        }
        event.reply("Interaction timed out").setEphemeral(true).queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
    }

    @StringSel("new-instance-flags")
    public void newInstanceFlags(StringSelectInteractionEvent event)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        if (ic != null) try
        {
            ic.queueEnabled = event.getValues().contains("queueEnabled");
            ic.hardClose = event.getValues().contains("hardClose");
            ic.ageGate = event.getValues().contains("ageGate");
            ic.inviteOnly = event.getValues().contains("inviteOnly");
            ic.canRequestInvite = event.getValues().contains("canRequestInvite");
//            ic.playerPersistenceEnabled = event.getValues().contains("playerPersistenceEnabled");
//            ic.instancePersistenceEnabled = event.getValues().contains("instancePersistenceEnabled");
            ic.contentSettings_drones = event.getValues().contains("contentSettings.drones");
            ic.contentSettings_emoji = event.getValues().contains("contentSettings.emoji");
            ic.contentSettings_props = event.getValues().contains("contentSettings.props");
            ic.contentSettings_pedestals = event.getValues().contains("contentSettings.pedestals");
            ic.contentSettings_prints = event.getValues().contains("contentSettings.prints");
            ic.contentSettings_stickers = event.getValues().contains("contentSettings.stickers");
            ic.minimumAvatarPerformance = this.minimumAvatarPerformance(event.getValues());
            event.deferEdit().queue();
            return;
        }
        catch (RuntimeException rex)
        {
        }
        event.reply("Interaction timed out").setEphemeral(true).queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
    }

    PerformanceRatings minimumAvatarPerformance(List<String> values)
    {
        if (values.contains("minimumAvatarPerformance."+PerformanceRatings.GOOD.getValue()))
            return PerformanceRatings.GOOD;
        if (values.contains("minimumAvatarPerformance."+PerformanceRatings.MEDIUM.getValue()))
            return PerformanceRatings.MEDIUM;
        if (values.contains("minimumAvatarPerformance."+PerformanceRatings.POOR.getValue()))
            return PerformanceRatings.POOR;
        return null;
    }

    @ButtonClk("edit-desc")
    public void editDesc(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryTagAccess(event.getMember(), event, auditEntryId))
            return;
        TextInput.Builder ti = TextInput
            .create("input-desc:"+auditEntryId, TextInputStyle.PARAGRAPH)
            .setRequired(true)
            .setPlaceholder("Event description")
            ;
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta != null)
            ti.setValue(auditEntryMeta.entryDescription);
        
        Modal.Builder m = Modal.create("edit-desc:"+auditEntryId, "Edit description")
            .addComponents(Label.of("Input description", ti.build()))
            ;
        
        event.replyModal(m.build()).queue();
    }

    @ModalSub("edit-desc")
    public void editDesc(ModalInteractionEvent event)
    {
        String[] parts = event.getModalId().split(":");
        if (!this.checkAuditEntryTagAccess(event.getMember(), event, parts[1]))
            return;
        String desc = event.getValue("input-desc:"+parts[1]).getAsString();
        event.replyFormat("### Setting description:\n%s", desc).setEphemeral(true).queue();
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata_setDescription(parts[1], desc);
        this.updateAuxMessage(event.getChannel(), auditEntryMeta);
    }

    @ButtonClk("vrchat-report")
    @Ephemeral
    @FeatureGate("vrchat_reports.enabled")
    public void vrchatReport(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        
        String auditEntryId = parts[1];
        if (auditEntryId.endsWith("null")) auditEntryId = auditEntryId.substring(0, auditEntryId.length() - 4); // bugfix
        
        String targetUserId = null,
               targetDisplayName = null,
               actorUserId = null,
               actorDisplayName = null,
               metaDescription = null,
               metaTags[] = null;
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        // Old public report buttons can survive an actor becoming confidential or an event being redacted.
        // An ephemeral response protects its audience, but does not itself authorize the requester.
        if (event.getMember() == null || !this.discord.auditVisibleTo(auditEntryMeta, event.getMember().getId()))
        {
            hook.sendMessage("This audit event is unavailable or you do not have access to its report details.")
                .setEphemeral(true).queue();
            return;
        }
        String rawActorId = auditEntryMeta.entry.getActorId();
        if (auditEntryMeta != null && auditEntryMeta.entry != null)
        {
            targetUserId = auditEntryMeta.entry.getTargetId();
            actorUserId = auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorId : auditEntryMeta.entry.getActorId();
            actorDisplayName = auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorDisplayName : auditEntryMeta.entry.getActorDisplayName();
            metaDescription = auditEntryMeta.entryDescription;
            metaTags = auditEntryMeta.entryTags.toArray();

            long within1day = System.currentTimeMillis() - 86400_000L;
            User targetUser = this.discord.scarlet.vrc.getUser(targetUserId, within1day);
            if (targetUser != null)
            {
                targetDisplayName = targetUser.getDisplayName();
            }
        }
        
        String reportSubject = targetDisplayName;
        Long targetJoined = parts.length < 3 ? (auditEntryMeta != null ? auditEntryMeta.getAuxData("targetJoined", JsonElement::getAsLong) : null) : Long.parseUnsignedLong(parts[2]);
        
        String location = auditEntryMeta == null ? null : auditEntryMeta.getData("location");
        
        ScarletVRChatReportTemplate.FormatParams params = this.discord.scarlet.vrcReport.new FormatParams()
            .group(this.discord.scarlet.vrc, this.discord.scarlet.vrc.groupId, this.discord.scarlet.vrc.group)
            .location(this.discord.scarlet.vrc, location)
            .actor(this.discord.scarlet.vrc, actorUserId, actorDisplayName)
            .target(this.discord.scarlet.vrc, targetUserId, targetDisplayName)
            .targetEx(
                targetJoined == null ? null : OffsetDateTime.ofInstant(Instant.ofEpochSecond(targetJoined.longValue()), ZoneOffset.UTC).format(ScarletVRChatReportTemplate.DTF),
                auditEntryMeta == null ? null : auditEntryMeta.entry.getCreatedAt().format(ScarletVRChatReportTemplate.DTF)
            )
            .audit(
                auditEntryId,
                metaDescription,
                metaTags == null ? null : Arrays.stream(metaTags).map(this.discord.scarlet.moderationTags::getTagLabel).filter(Objects::nonNull).toArray(String[]::new)
            )
            ;
        
        StringBuilder reportDesc = new StringBuilder();
        if (metaDescription != null)
        {
            reportDesc.append(metaDescription);
        }
        if (metaTags != null && metaTags.length > 0)
        {
            if (reportDesc.length() != 0)
            {
                reportDesc.append("<br><br>");
            }
            reportDesc.append("User's Group internal moderation tags:<ul>");
            for (String metaTag : metaTags)
            {
                reportDesc.append("<li>")
                          .append(this.discord.scarlet.moderationTags.getTagLabel(metaTag))
                          .append("</li>");
            }
            reportDesc.append("</ul>");
        }
        // Footer
        {
            if (reportDesc.length() != 0)
            {
                reportDesc.append("<br><br>");
            }
            reportDesc.append("Partially autofilled with ")
                      .append(Scarlet.APP_NAME)
                      .append(" version ")
                      .append(Scarlet.VERSION)
                      .append("<br>")
                      .append(Scarlet.GITHUB_URL)
                      .append("<br>")
                      .append("Group ID: ")
                      .append(this.discord.scarlet.vrc.groupId)
                      .append("<br>")
                      .append("Audit ID: ")
                      .append(auditEntryId)
                      ;
        }
        
        String requestingEmail = this.discord.requestingEmail.get();
        // Use the same configured template for the restored autofill link and the copyable report.
        // An unconfigured template falls back to the event's notes, tags, and identifying footer.
        String templateHtml = this.discord.scarlet.vrcReport.get().trim().isEmpty() ? null
            : params.format(this.discord.appendTemplateFooter.get());
        // Lookups and template expansion may take time. Recheck both the latest event policy and
        // the identities captured in this payload before returning an old button's report.
        if (!this.discord.auditVisibleTo(this.discord.scarlet.data.auditEntryMetadata(auditEntryId), event.getMember().getId())
            || this.discord.shouldRedact(rawActorId, event.getMember().getId())
            || this.discord.shouldRedact(actorUserId, event.getMember().getId()))
        {
            hook.sendMessage("This audit event is unavailable or you do not have access to its report details.")
                .setEphemeral(true).queue();
            return;
        }
        hook.sendMessage(ScarletVRChatReportMessage.create(
                requestingEmail, targetUserId, reportSubject, reportDesc.toString(), templateHtml))
            .setEphemeral(true)
            .queue();
    }

    @ButtonClk("view-potential-avatar-matches")
    @Ephemeral
    @FeatureGate("avatar_search.enabled")
    public void viewPotentialAvatarMatches(ButtonInteractionEvent event, InteractionHook hook)
    {
        long withinOneHour = System.currentTimeMillis() - 3600_000L;
        MessageEmbed[] embeds = event
            .getMessage()
            .getEmbeds()
            .stream()
            .map(MessageEmbed::getDescription)
            .filter(Objects::nonNull)
            .flatMap($ -> {
                Matcher m = VrcIds.id_avatar.matcher($);
                if (!m.find())
                    return Stream.empty();
                List<String> ids = new ArrayList<>();
                do ids.add(m.group());
                while (m.find());
                return ids.stream();
            })
            .map($ -> this.discord.scarlet.vrc.getAvatar($, withinOneHour))
            .filter(Objects::nonNull)
            .map($ -> 
                new EmbedBuilder()
                .setAuthor($.getAuthorName(), VrcWeb.Home.user($.getAuthorId()), null)
                .setTitle($.getName(), VrcWeb.Home.avatar($.getId()))
                .setThumbnail($.getImageUrl() == null || $.getImageUrl().isEmpty() ? null : $.getImageUrl())
                .setDescription($.getDescription() == null || $.getDescription().isEmpty() ? null : $.getDescription())
                .addField("Report avatar", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_content_avatar(this.discord.requestingEmail.get(), $.getId(), null, null)), false)
                .build()
            )
            .toArray(MessageEmbed[]::new)
            ;
        this.discord.interactions.new Pagination(event.getId(), embeds, 4).queue(hook);
    }

    @ButtonClk("view-snapshot-user")
    @Ephemeral
    public void viewSnapshotUser(ButtonInteractionEvent event, InteractionHook hook)
    {
        this.viewSnapshot(event, hook, "view-snapshot-user", 0);
    }

    @ButtonClk("view-snapshot-user-groups")
    @Ephemeral
    public void viewSnapshotUserGroups(ButtonInteractionEvent event, InteractionHook hook)
    {
        this.viewSnapshot(event, hook, "view-snapshot-user-groups", 1);
    }

    @ButtonClk("view-snapshot-user-represented-group")
    @Ephemeral
    public void viewSnapshotUserRepresentedGroup(ButtonInteractionEvent event, InteractionHook hook)
    {
        this.viewSnapshot(event, hook, "view-snapshot-user-represented-group", 2);
    }

    private void viewSnapshot(ButtonInteractionEvent event, InteractionHook hook, String operation, int kind)
    {
        String customId = event.getButton().getCustomId();
        String prefix = operation + ":";
        String auditId = customId != null && customId.startsWith(prefix) ? customId.substring(prefix.length()) : "";
        ScarletData.AuditEntryMetadata metadata = this.snapshotMetadata(auditId);
        String requester = event.getMember() == null ? null : event.getMember().getId();
        Message origin = event.getMessage();
        if (!this.snapshotAllowed(metadata, requester, origin, operation))
        {
            hook.sendMessage("This snapshot is unavailable or you no longer have access to its audit event.")
                .setEphemeral(true).queue();
            return;
        }
        // Project again: old files may still contain the original unfiltered self-account response.
        MessageEmbed[] embeds = kind == 0 ? ScarletSnapshotPrivacy.embeds(ScarletSnapshotPrivacy.user(
            metadata.snapshotTargetUser, metadata.entry.getTargetId()))
            : kind == 1 ? ScarletSnapshotPrivacy.groupEmbeds(ScarletSnapshotPrivacy.groups(metadata.snapshotTargetUserGroups))
            : ScarletSnapshotPrivacy.embeds(ScarletSnapshotPrivacy.group(metadata.snapshotTargetUserRepresentedGroup));
        if (!this.snapshotAllowed(metadata, requester, origin, operation))
        {
            hook.sendMessage("This snapshot is unavailable or you no longer have access to its audit event.")
                .setEphemeral(true).queue();
            return;
        }
        if (embeds.length == 0)
        {
            hook.sendMessage("This event has no verified public snapshot information available.")
                .setEphemeral(true).queue();
            return;
        }
        this.discord.interactions.new Pagination(event.getId(), embeds, 1)
            .restrictTo(requester, interaction -> this.snapshotAllowed(metadata,
                interaction.getMember() == null ? null : interaction.getMember().getId(), origin, operation))
            .queue(hook);
    }

    private ScarletData.AuditEntryMetadata snapshotMetadata(String auditId)
    {
        if (auditId == null || !VrcIds.id_groupaudit.matcher(auditId).matches()) return null;
        try
        {
            ScarletData.AuditEntryMetadata metadata = this.discord.scarlet.data.auditEntryMetadata(auditId);
            return metadata != null && metadata.entry != null && auditId.equals(metadata.entry.getId()) ? metadata : null;
        }
        catch (RuntimeException unavailable)
        {
            return null;
        }
    }

    boolean snapshotAllowed(ScarletData.AuditEntryMetadata snapshot, String requester, Message origin, String operation)
    {
        try
        {
            ScarletData.AuditEntryMetadata current = snapshot == null || snapshot.entry == null ? null
                : this.snapshotMetadata(snapshot.entry.getId());
            if (!ScarletAuditPrivacy.sameRecordedAudit(snapshot, current) || requester == null || origin == null
                || current.entry.getActorId() == null || current.entry.getActorId().trim().isEmpty()
                || current.entry.getTargetId() == null || !VrcIds.id_user.matcher(current.entry.getTargetId()).matches()
                || !origin.isFromGuild() || origin.isWebhookMessage() || this.discord.jda == null
                || this.discord.guildSf == null || !this.discord.guildSf.equals(origin.getGuild().getId())
                || !this.discord.guildSf.equals(current.guildSnowflake)
                || !this.discord.jda.getSelfUser().getId().equals(origin.getAuthor().getId())
                || current.auxMessageSnowflake == null || !current.auxMessageSnowflake.equals(origin.getId())
                || !Objects.equals(snapshot.auxMessageSnowflake, current.auxMessageSnowflake)
                || current.threadSnowflake == null || !current.threadSnowflake.equals(origin.getChannel().getId())) return false;
            Member member = origin.getGuild().getMemberById(requester);
            ThreadChannel thread = origin.getGuild().getThreadChannelById(current.threadSnowflake);
            return member != null && thread != null && this.discord.perms != null
                && this.discord.perms.checkOperation(member, DPerms.PermType.BUTTON_PRESS, operation)
                && member.hasPermission(thread, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)
                && this.discord.auditVisibleTo(current, requester)
                && this.discord.auditThreadDestinationProblem(current, thread) == null;
        }
        catch (RuntimeException unavailable)
        {
            // A missing/corrupt record, policy or Discord cache must never authorize disclosure.
            return false;
        }
    }

    @ButtonClk("submit-evidence")
    @Ephemeral
    public void submitEvidence(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String messageSnowflake = parts[1];
        Message message = event.getChannel().retrieveMessageById(messageSnowflake).complete();
        this.submitEvidence(event, hook, message::getAttachments, "You must reply to an audit event message in the relevant thread.");
    }
    public void submitEvidence(GenericInteractionCreateEvent event, InteractionHook hook, Supplier<List<Message.Attachment>> getAttachments, String notThreadFeedback)
    {
        MessageChannel channel = event.getMessageChannel();
        
        if (!this.discord.evidenceEnabled.get())
        {
            hook.sendMessage("This feature is not enabled.").setEphemeral(true).queue();
            return;
        }
        
        String evidenceRoot = this.discord.evidenceRoot;
        
        if (evidenceRoot == null || (evidenceRoot = evidenceRoot.trim()).isEmpty())
        {
            hook.sendMessage("The evidence folder hasn't been specified.").setEphemeral(true).queue();
            return;
        }
        
        if (!channel.getType().isThread())
        {
            hook.sendMessage(notThreadFeedback).setEphemeral(true).queue();
            return;
        }
        
        String[] partsRef = channel
            .getHistoryFromBeginning(2)
            .complete()
            .getRetrievedHistory()
            .stream()
            .map(Message::getComponentTree)
            .map($->$.findAll(Button.class))
            .flatMap(List::stream)
            .filter($ -> $.getCustomId().startsWith("edit-tags:"))
            .findFirst()
            .map($ -> $.getCustomId().split(":"))
            .orElse(null)
            ;
        
        if (partsRef == null)
        {
            hook.sendMessage("Could not determine audit event id.").setEphemeral(true).queue();
            return;
        }
        
        List<Message.Attachment> attachments = getAttachments.get();//message.getAttachments();
        
        if (attachments.isEmpty())
        {
            hook.sendMessage("No attachments.").setEphemeral(true).queue();
            return;
        }
        
        String auditEntryId = partsRef[1];
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta == null || auditEntryMeta.entry == null)
        {
            hook.sendMessage("Could not load audit event.").setEphemeral(true).queue();
            return;
        }
        
        String auditEntryTargetId = auditEntryMeta.entry.getTargetId();
        
        String requesterSf = event.getUser().getId(),
               requesterDisplayName = event.getUser().getEffectiveName();
        OffsetDateTime timestamp = event.getTimeCreated();
        
        // TODO : better model for avoiding race conditions
        synchronized (auditEntryTargetId.intern())
        {
            
            ScarletData.UserMetadata auditEntryTargetUserMeta = this.discord.scarlet.data.userMetadata(auditEntryTargetId);
            if (auditEntryTargetUserMeta == null)
                auditEntryTargetUserMeta = new ScarletData.UserMetadata();
            
            // TODO : async ?
            
            for (Message.Attachment attachment : attachments)
            {
                String fileName = attachment.getFileName(),
                       attachmentUrl = attachment.getUrl(),
                       attachmentProxyUrl = attachment.getProxyUrl();
                
                ScarletEvidence.FormatParams filePath = new ScarletEvidence.FormatParams()
                    .group(this.discord.scarlet.vrc, auditEntryMeta.entry.getGroupId(), null)
                    .actor(this.discord.scarlet.vrc,
                           auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorId : auditEntryMeta.entry.getActorId(),
                           auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorDisplayName : auditEntryMeta.entry.getActorDisplayName())
                    .target(this.discord.scarlet.vrc, auditEntryMeta.entry.getTargetId(), null)
                    .file(fileName)
                    .audit(auditEntryId)
                    .index(auditEntryTargetUserMeta.getUserCaseEvidenceCount())
                    ;
                try
                {
                    File dest = filePath.nextFile(evidenceRoot, this.discord.evidenceFilePathFormat.get());
                    if (dest.isFile())
                    {
                        hook.sendMessageFormat("File '%s' already exists, skipping.", fileName).setEphemeral(true).queue();
                        continue;
                    }
                    if (!dest.getParentFile().isDirectory())
                        dest.getParentFile().mkdirs();
                    attachment.getProxy().downloadToFile(dest).join();
                    
                    ScarletData.EvidenceSubmission evidence = new ScarletData.EvidenceSubmission(auditEntryId, requesterSf, requesterDisplayName, timestamp, fileName, filePath.prevFormat(), attachmentUrl, attachmentProxyUrl);
                    auditEntryTargetUserMeta = this.discord.scarlet.data.updateUserMetadata(auditEntryTargetId,
                        metadata -> { metadata.addUserCaseEvidence(evidence); return metadata; });
                    
                    hook.sendMessageFormat("Saved '%s'.", filePath.prevFormat()).setEphemeral(true).queue();
                    LOG.info(String.format("%s (<@%s>) saved evidence to '%s'.", requesterDisplayName, requesterSf, filePath.prevFormat()));
                }
                catch (Exception ex)
                {
                    hook.sendMessageFormat("Exception saving '%s' as '%s'.", attachment.getFileName(), filePath.prevFormat()).setEphemeral(true).queue();
                    LOG.error("Exception whilst saving attachment", ex);
                }
            }
            
        }
    }

    @ButtonClk("import-watched-groups")
    @Ephemeral
    public void importWatchedGroups(ButtonInteractionEvent event, InteractionHook hook)
    {
        if (!this.checkConfigAccess(event.getMember(), hook, "import watched groups"))
            return;
        String[] parts = event.getButton().getCustomId().split(":");
        String messageSnowflake = parts[1];
        
        Message message = event.getChannel().retrieveMessageById(messageSnowflake).complete();
        
        List<Message.Attachment> attachments = message.getAttachments();
        
        if (attachments.isEmpty())
        {
            hook.sendMessage("No attachments.").setEphemeral(true).queue();
            return;
        }
        
        String requesterSf = event.getUser().getId(),
               requesterDisplayName = event.getUser().getEffectiveName();
        
        LOG.info(String.format("%s (<@%s>) Importing watched groups", requesterDisplayName, requesterSf));
        
        for (Message.Attachment attachment : attachments)
        {
            String fileName = attachment.getFileName(),
                   attachmentUrl = attachment.getUrl();
            
            if (fileName.endsWith(".csv"))
            {
                LOG.info("Importing watched groups legacy CSV from attachment: "+fileName);
                try (Reader reader = new InputStreamReader(HttpURLInputStream.get(attachmentUrl, HttpURLInputStream.PUBLIC_ONLY)))
                {
                    if (this.discord.scarlet.watchedGroups.importLegacyCSV(reader, true))
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
                    if (this.discord.scarlet.watchedGroups.importJson(reader, true))
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
    }

    @ModalSub("edit-report-template")
    public void editReportTemplate(ModalInteractionEvent event)
    {
        if (!this.checkConfigAccess(event, "edit the report template"))
            return;
        String contents = event.getValue("report-template").getAsString();
        try
        {
            if (this.discord.scarlet.vrcReport.trySet(contents))
            {
                LOG.info("Successfully edited report template");
                event.replyFormat("Successfully edited report template").setEphemeral(true).queue();
            }
            else
            {
                LOG.warn("Failed to edited report template: empty content");
                event.replyFormat("Failed to edited report template: empty content").setEphemeral(true).queue();
            }
        }
        catch (Exception ex)
        {
            LOG.error("Exception editing report template", ex);
            event.replyFormat("Exception while editing report template: %s", ex).setEphemeral(true).queue();
        }
    }

    // ── discord-kick interactions ─────────────────────────────────────────────

    @ButtonClk("discord-kick-confirm")
    @Ephemeral
    public void discordKickConfirm(ButtonInteractionEvent event, InteractionHook hook)
    {
        if (!this.discord.scarlet.discordKickBanEnabled.get())
        {
            hook.sendMessage("Discord moderation is disabled.").queue();
            return;
        }
        if (event.getMember() == null || !event.getMember().hasPermission(net.dv8tion.jda.api.Permission.KICK_MEMBERS))
        {
            hook.sendMessage("You do not have permission to kick members.").setEphemeral(true).queue();
            return;
        }
        String[] parts = event.getButton().getCustomId().split(":", 2);
        ScarletDiscordJDA.PendingDiscordModeration pending = parts.length == 2
            ? this.discord.consumeDiscordModeration(parts[1], event.getUser().getId(), net.dv8tion.jda.api.Permission.KICK_MEMBERS) : null;
        if (pending == null)
        {
            hook.sendMessage("This confirmation expired, was already used, or belongs to another requester. Run the command again.").queue();
            return;
        }
        String targetId = pending.target;
        String reason = pending.reason;

        net.dv8tion.jda.api.entities.Guild guild =
            this.discord.jda.getGuildById(this.discord.guildSf);
        if (guild == null)
        {
            hook.sendMessage("Could not find the configured Discord server.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Member self = guild.getSelfMember();

        // Verify the bot has permission to kick at all
        if (!self.hasPermission(net.dv8tion.jda.api.Permission.KICK_MEMBERS))
        {
            hook.sendMessage("The bot does not have the **Kick Members** permission in this server.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Member target;
        try
        {
            target = guild.retrieveMemberById(targetId).complete();
        }
        catch (Exception ex)
        {
            hook.sendMessage("Could not retrieve that member — they may have already left.").queue();
            return;
        }

        if (target == null)
        {
            hook.sendMessage("Member not found — they may have already left.").queue();
            return;
        }

        if (target.isOwner())
        {
            hook.sendMessageFormat("Cannot kick **%s** — they are the server owner.",
                MarkdownSanitizer.escape(target.getEffectiveName())).queue();
            return;
        }

        if (!self.canInteract(target))
        {
            hook.sendMessageFormat("Cannot kick **%s** — their highest role is equal to or above mine.",
                MarkdownSanitizer.escape(target.getEffectiveName())).queue();
            return;
        }

        final Member actorMember;
        try { actorMember = guild.retrieveMemberById(event.getUser().getId()).complete(); }
        catch (Exception ex) { hook.sendMessage("Unable to verify your current permissions. No action taken.").queue(); return; }
        if (!DiscordModerationAuthorization.allowed(actorMember, target, net.dv8tion.jda.api.Permission.KICK_MEMBERS))
        {
            hook.sendMessage("Your current permission or role hierarchy does not allow this kick.").queue();
            return;
        }
        String name = target.getEffectiveName();
        String actor = actorMember != null
            ? actorMember.getEffectiveName()
            : event.getUser().getName();

        // Build audit log reason
        String auditReason = reason != null && !reason.trim().isEmpty()
            ? String.format("Kicked via Scarlet by %s. Reason: %s", actor, reason)
            : "Kicked via Scarlet by " + actor;

        // Build DM message
        String dmMessage = reason != null && !reason.trim().isEmpty()
            ? String.format("You have been kicked from **%s** by %s.\n**Reason:** %s",
                guild.getName(), actor, reason)
            : String.format("You have been kicked from **%s** by %s.",
                guild.getName(), actor);

        // Capture finals for use inside lambdas
        final net.dv8tion.jda.api.entities.Member finalTarget = target;
        final String finalName = name;
        final String finalAuditReason = auditReason;
        final String finalTargetId = targetId;

        final String finalGuildName = guild.getName();
        final String finalGuildId = guild.getId();

        // Helper runnable that performs the actual kick
        Runnable doKick = () -> {
            if (!this.discord.scarlet.discordKickBanEnabled.get()
                || !DiscordModerationAuthorization.allowed(guild.getMemberById(actorMember.getId()), guild.getMemberById(finalTargetId), net.dv8tion.jda.api.Permission.KICK_MEMBERS))
            {
                hook.sendMessage("Permissions or member hierarchy changed while confirming; no kick performed.").queue();
                return;
            }
            guild.kick(finalTarget)
            .reason(finalAuditReason)
            .queue(
                success -> {
                    LOG.info("Discord kick: {} ({}) kicked from {} ({}) by {}. Reason: {}",
                        finalName, finalTargetId, finalGuildName, finalGuildId, actor, finalAuditReason);
                    this.discord.emitDiscordActionLog("Discord Kick", actorMember, finalName, finalTargetId,
                        reason, true, "Member was kicked from " + finalGuildName + ".", 0xFEE75C);
                    hook.sendMessageFormat("**%s** has been kicked from the server.",
                        MarkdownSanitizer.escape(finalName)).queue();
                },
                error -> {
                    LOG.error("Failed to kick member {} ({}) from {} ({}): {}",
                        finalName, finalTargetId, finalGuildName, finalGuildId, error.getMessage());
                    this.discord.emitDiscordActionLog("Discord Kick", actorMember, finalName, finalTargetId,
                        reason, false, "Kick failed: " + error.getMessage(), 0xED4245);
                    hook.sendMessageFormat("Failed to kick **%s**: %s",
                        MarkdownSanitizer.escape(finalName), error.getMessage()).queue();
                });
        };

        // DM the user FIRST; the kick only fires after the DM attempt has fully
        // settled (success or failure). This ensures the user still shares a mutual
        // guild with the bot at the moment the DM is sent.
        target.getUser().openPrivateChannel().queue(
            channel -> channel.sendMessage(dmMessage).queue(
                sent -> {
                    LOG.debug("Sent kick DM to {} ({})", finalName, finalTargetId);
                    doKick.run();
                },
                error -> {
                    LOG.warn("Failed to send kick DM to {} ({}): {} — kicking anyway",
                        finalName, finalTargetId, error.getMessage());
                    doKick.run();
                }
            ),
            error -> {
                LOG.warn("Could not open DM channel for {} ({}): {} — kicking anyway",
                    finalName, finalTargetId, error.getMessage());
                doKick.run();
            }
        );
    }

    @ButtonClk("discord-kick-cancel")
    @Ephemeral
    public void discordKickCancel(ButtonInteractionEvent event, InteractionHook hook)
    {
        hook.sendMessage("Kick cancelled.").queue();
        hook.deleteOriginal().queue();
    }

    // ── discord-ban interactions ──────────────────────────────────────────────

    @ButtonClk("discord-ban-confirm")
    @Ephemeral
    public void discordBanConfirm(ButtonInteractionEvent event, InteractionHook hook)
    {
        if (!this.discord.scarlet.discordKickBanEnabled.get())
        {
            hook.sendMessage("Discord moderation is disabled.").queue();
            return;
        }
        if (event.getMember() == null || !event.getMember().hasPermission(net.dv8tion.jda.api.Permission.BAN_MEMBERS))
        {
            hook.sendMessage("You do not have permission to ban members.").setEphemeral(true).queue();
            return;
        }
        String[] parts = event.getButton().getCustomId().split(":", 2);
        ScarletDiscordJDA.PendingDiscordModeration pending = parts.length == 2
            ? this.discord.consumeDiscordModeration(parts[1], event.getUser().getId(), net.dv8tion.jda.api.Permission.BAN_MEMBERS) : null;
        if (pending == null)
        {
            hook.sendMessage("This confirmation expired, was already used, or belongs to another requester. Run the command again.").queue();
            return;
        }
        String targetId = pending.target;
        String reason = pending.reason;

        net.dv8tion.jda.api.entities.Guild guild =
            this.discord.jda.getGuildById(this.discord.guildSf);
        if (guild == null)
        {
            hook.sendMessage("Could not find the configured Discord server.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Member self = guild.getSelfMember();

        if (!self.hasPermission(net.dv8tion.jda.api.Permission.BAN_MEMBERS))
        {
            hook.sendMessage("The bot does not have the **Ban Members** permission in this server.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Member target;
        try
        {
            target = guild.retrieveMemberById(targetId).complete();
        }
        catch (Exception ex)
        {
            hook.sendMessage("Unable to verify the member's current role hierarchy. No ban performed.").queue();
            return;
        }

        String name;
        if (target != null)
        {
            if (target.isOwner())
            {
                hook.sendMessageFormat("Cannot ban **%s** — they are the server owner.",
                    MarkdownSanitizer.escape(target.getEffectiveName())).queue();
                return;
            }
            if (!self.canInteract(target))
            {
                hook.sendMessageFormat("Cannot ban **%s** — their highest role is equal to or above mine.",
                    MarkdownSanitizer.escape(target.getEffectiveName())).queue();
                return;
            }
            name = target.getEffectiveName();
        }
        else
        {
            // User left — ban by raw ID (Discord allows banning non-members)
            name = targetId;
        }

        final Member actorMember;
        try { actorMember = guild.retrieveMemberById(event.getUser().getId()).complete(); }
        catch (Exception ex) { hook.sendMessage("Unable to verify your current permissions. No action taken.").queue(); return; }
        if (!DiscordModerationAuthorization.allowed(actorMember, target, net.dv8tion.jda.api.Permission.BAN_MEMBERS))
        {
            hook.sendMessage("Your current permission or role hierarchy does not allow this ban.").queue();
            return;
        }
        String actor = actorMember != null
            ? actorMember.getEffectiveName()
            : event.getUser().getName();

        // Build audit log reason
        String auditReason = reason != null && !reason.trim().isEmpty()
            ? String.format("Banned via Scarlet by %s. Reason: %s", actor, reason)
            : "Banned via Scarlet by " + actor;

        // Build DM message
        String dmMessage = reason != null && !reason.trim().isEmpty()
            ? String.format("You have been banned from **%s**.\n**Reason:** %s",
                guild.getName(), reason)
            : String.format("You have been banned from **%s**.",
                guild.getName());

        final net.dv8tion.jda.api.entities.Member finalTarget = target;
        final String finalName = name;
        final String finalAuditReason = auditReason;
        final String finalTargetId = targetId;
        final String finalGuildName = guild.getName();
        final String finalGuildId = guild.getId();

        net.dv8tion.jda.api.entities.UserSnowflake snowflake =
            target != null ? target : net.dv8tion.jda.api.entities.UserSnowflake.fromId(targetId);

        Runnable doBan = () -> {
            if (!this.discord.scarlet.discordKickBanEnabled.get()
                || !DiscordModerationAuthorization.allowed(guild.getMemberById(actorMember.getId()), guild.getMemberById(finalTargetId), net.dv8tion.jda.api.Permission.BAN_MEMBERS))
            {
                hook.sendMessage("Permissions or member hierarchy changed while confirming; no ban performed.").queue();
                return;
            }
            guild.ban(snowflake, 0, java.util.concurrent.TimeUnit.DAYS)
            .reason(finalAuditReason)
            .queue(
                success -> {
                    LOG.info("Discord ban: {} ({}) banned from {} ({}) by {}. Reason: {}",
                        finalName, finalTargetId, finalGuildName, finalGuildId, actor, finalAuditReason);
                    this.discord.emitDiscordActionLog("Discord Ban", actorMember, finalName, finalTargetId,
                        reason, true, "Member was banned from " + finalGuildName + ".", 0xED4245);
                    hook.sendMessageFormat("**%s** has been banned from the server.",
                        MarkdownSanitizer.escape(finalName)).queue();
                },
                error -> {
                    LOG.error("Failed to ban member {} ({}) from {} ({}): {}",
                        finalName, finalTargetId, finalGuildName, finalGuildId, error.getMessage());
                    this.discord.emitDiscordActionLog("Discord Ban", actorMember, finalName, finalTargetId,
                        reason, false, "Ban failed: " + error.getMessage(), 0xED4245);
                    hook.sendMessageFormat("Failed to ban **%s**: %s",
                        MarkdownSanitizer.escape(finalName), error.getMessage()).queue();
                });
        };

        if (target != null)
        {
            // DM the user first — ban only fires after the attempt settles
            target.getUser().openPrivateChannel().queue(
                channel -> channel.sendMessage(dmMessage).queue(
                    sent -> {
                        LOG.debug("Sent ban DM to {} ({})", finalName, finalTargetId);
                        doBan.run();
                    },
                    error -> {
                        LOG.warn("Could not send ban DM to {} ({}): {} — banning anyway",
                            finalName, finalTargetId, error.getMessage());
                        doBan.run();
                    }
                ),
                error -> {
                    LOG.warn("Could not open DM channel for {} ({}): {} — banning anyway",
                        finalName, finalTargetId, error.getMessage());
                    doBan.run();
                }
            );
        }
        else
        {
            // Target already left — no DM possible, ban by ID
            LOG.debug("Target {} left before ban — skipping DM, banning by ID", finalTargetId);
            doBan.run();
        }
    }

    @ButtonClk("discord-ban-cancel")
    @Ephemeral
    public void discordBanCancel(ButtonInteractionEvent event, InteractionHook hook)
    {
        hook.sendMessage("Ban cancelled.").queue();
        hook.deleteOriginal().queue();
    }

    // ── end discord-ban interactions ──────────────────────────────────────────

    // ── end discord-kick interactions ─────────────────────────────────────────

    @StringSel("set-audit-aux-webhooks")
    public void setAuditAuxWebhooks(StringSelectInteractionEvent event)
    {
        if (!this.checkConfigAccess(event, "set audit auxiliary webhooks"))
            return;
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String auditType0 = parts[1];
        GroupAuditType auditType = GroupAuditType.of(auditType0);
        if (auditType == null)
        {
            event.replyFormat("%s isn't a valid audit log event type", auditType0).setEphemeral(true).queue();
            return;
        }
        if (event.getValues().stream().anyMatch(name -> !this.discord.scarletAuxWh2webhookUrl.containsKey(name)))
        {
            event.reply("One of the selected webhooks no longer exists. Open a new selector and try again. Nothing changed.").setEphemeral(true).queue();
            return;
        }
        if (event.getValues().isEmpty())
        {
            this.discord.auditType2scarletAuxWh.remove(auditType0);
            this.discord.save();
            event.replyFormat("Removing auxiliary webhooks for %s", auditType0).setEphemeral(true).queue();
        }
        else
        {
            this.discord.auditType2scarletAuxWh.put(auditType0, new UniqueStrings(event.getValues()));
            this.discord.save();
            event.replyFormat("Setting auxiliary webhooks for %s:\n%s", auditType0,
                event.getValues().stream().collect(Collectors.joining(", "))).setEphemeral(true).queue();
        }
    }

    /**
     * Who may TAG / DESCRIBE a moderation event (looser than redacting it). Instance hosts who can warn
     * and kick but not ban must be able to tag the actions they take, so on top of the full moderation
     * check this also allows: the moderator who performed the action (linked VRChat account), anyone
     * with VRChat instance-moderation permission in the group, and the groupex-tags-edit override for
     * roles without linked VRChat accounts. Returns null if allowed, else the reason to show.
     */
    String auditEntryTagDenial(Member member, net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel, String auditEntryId)
    {
        if (member == null)
            return "Could not determine your guild permissions.";
        // Staff are whoever the server lets post in its moderation threads: if your role can send
        // messages in the thread (or channel) the event lives in, you can tag it. This follows the
        // server's own Discord role/channel setup, so nothing extra has to be granted in Scarlet.
        if (channel instanceof net.dv8tion.jda.api.entities.channel.middleman.GuildChannel)
        {
            net.dv8tion.jda.api.entities.channel.middleman.GuildChannel gc = (net.dv8tion.jda.api.entities.channel.middleman.GuildChannel)channel;
            net.dv8tion.jda.api.Permission post = channel.getType().isThread()
                ? net.dv8tion.jda.api.Permission.MESSAGE_SEND_IN_THREADS
                : net.dv8tion.jda.api.Permission.MESSAGE_SEND;
            if (member.hasPermission(gc, net.dv8tion.jda.api.Permission.VIEW_CHANNEL, post))
                return null;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_TAGS_EDIT, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return null;
        String vrcId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(member.getId());
        if (vrcId != null)
        {
            if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_instance_moderate, member))
                return null;
            ScarletData.AuditEntryMetadata meta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
            if (meta != null && meta.entry != null && (vrcId.equals(meta.entry.getActorId()) || vrcId.equals(meta.auxActorId)))
                return null;
        }
        LOG.warn("Rejected tag/description edit by {} for audit entry {}", member.getId(), auditEntryId);
        return vrcId == null
            ? "You can't tag this event: your role can't post in this moderation thread. Ask an admin to give your role permission to send messages here, or link your VRChat account with `/link-vrchat-account` to tag actions you took yourself."
            : "You can't tag this event: your role can't post in this moderation thread, and it isn't an action you took. Ask an admin to give your role permission to send messages here.";
    }
    boolean checkAuditEntryTagAccess(Member member, net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel, InteractionHook hook, String auditEntryId)
    {
        String denial = this.auditEntryTagDenial(member, channel, auditEntryId);
        if (denial == null)
            return true;
        hook.sendMessage(denial).setEphemeral(true).queue();
        return false;
    }
    boolean checkAuditEntryTagAccess(Member member, net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, String auditEntryId)
    {
        String denial = this.auditEntryTagDenial(member, event.getMessageChannel(), auditEntryId);
        if (denial == null)
            return true;
        event.reply(denial).setEphemeral(true).queue();
        return false;
    }
    boolean checkAuditEntryModerationAccess(Member member, InteractionHook hook, String auditEntryId)
    {
        if (member == null)
        {
            hook.sendMessage("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return true;
        hook.sendMessage("You do not have permission to modify audit event moderation state.").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized moderation interaction by {} for audit entry {}", member.getId(), auditEntryId);
        return false;
    }
    boolean checkAuditEntryModerationAccess(Member member, ButtonInteractionEvent event, String auditEntryId)
    {
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return true;
        event.reply("You do not have permission to modify audit event moderation state.").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized moderation interaction by {} for audit entry {}", member.getId(), auditEntryId);
        return false;
    }
    boolean checkAuditEntryModerationAccess(Member member, ModalInteractionEvent event, String auditEntryId)
    {
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return true;
        event.reply("You do not have permission to modify audit event moderation state.").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized moderation interaction by {} for audit entry {}", member.getId(), auditEntryId);
        return false;
    }
    boolean checkConfigAccess(Member member, InteractionHook hook, String action)
    {
        if (member == null)
        {
            hook.sendMessage("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER)
            || member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR))
            return true;
        hook.sendMessage("You do not have permission to " + action + ".").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized configuration interaction by {} while trying to {}", member.getId(), action);
        return false;
    }
    boolean checkConfigAccess(ModalInteractionEvent event, String action)
    {
        Member member = event.getMember();
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER)
            || member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR))
            return true;
        event.reply("You do not have permission to " + action + ".").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized configuration interaction by {} while trying to {}", member.getId(), action);
        return false;
    }
    boolean checkConfigAccess(StringSelectInteractionEvent event, String action)
    {
        Member member = event.getMember();
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER)
            || member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR))
            return true;
        event.reply("You do not have permission to " + action + ".").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized configuration interaction by {} while trying to {}", member.getId(), action);
        return false;
    }
    boolean checkGroupModerationAccess(Member member, ButtonInteractionEvent event, String action)
    {
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return true;
        event.reply("You do not have permission to " + action + ".").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized moderation interaction by {} while trying to {}", member.getId(), action);
        return false;
    }
    boolean checkGroupModerationAccess(Member member, ModalInteractionEvent event, String action)
    {
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return true;
        event.reply("You do not have permission to " + action + ".").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized moderation interaction by {} while trying to {}", member.getId(), action);
        return false;
    }
    boolean checkPendingBanOwnership(String requesterSnowflake, String targetUserId)
    {
        return this.discord.scarlet.pendingModActions.isBanInfoOwner(targetUserId, requesterSnowflake);
    }
    boolean checkPendingBanOwnership(StringSelectInteractionEvent event, String targetUserId)
    {
        if (this.checkPendingBanOwnership(event.getUser().getId(), targetUserId))
            return true;
        event.reply("That moderation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkPendingBanOwnership(ButtonInteractionEvent event, String targetUserId)
    {
        if (this.checkPendingBanOwnership(event.getUser().getId(), targetUserId))
            return true;
        event.reply("That moderation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkPendingBanOwnership(ModalInteractionEvent event, String targetUserId)
    {
        if (this.checkPendingBanOwnership(event.getUser().getId(), targetUserId))
            return true;
        event.reply("That moderation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkPendingBanOwnership(Member member, InteractionHook hook, String requesterSnowflake, String targetUserId)
    {
        if (!this.checkPendingBanOwnership(requesterSnowflake, targetUserId))
        {
            hook.sendMessage("That moderation flow belongs to a different user or has expired.").setEphemeral(true).queue();
            return false;
        }
        return member != null;
    }
    boolean checkInstanceCreationOwnership(String requesterSnowflake, InstanceCreation ic)
    {
        return ic != null && requesterSnowflake.equals(ic.ownerSnowflake);
    }
    boolean checkInstanceCreationOwnership(ButtonInteractionEvent event, String requesterSnowflake, InstanceCreation ic)
    {
        if (this.checkInstanceCreationOwnership(requesterSnowflake, ic))
            return true;
        event.reply("That instance creation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkInstanceCreationOwnership(ModalInteractionEvent event, String requesterSnowflake, InstanceCreation ic)
    {
        if (this.checkInstanceCreationOwnership(requesterSnowflake, ic))
            return true;
        event.reply("That instance creation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkInstanceCreationOwnership(StringSelectInteractionEvent event, String requesterSnowflake, InstanceCreation ic)
    {
        if (this.checkInstanceCreationOwnership(requesterSnowflake, ic))
            return true;
        event.reply("That instance creation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkInstanceCreationOwnership(Member member, InteractionHook hook, String requesterSnowflake, InstanceCreation ic)
    {
        if (ic == null)
        {
            hook.sendMessage("This interaction timed out.").setEphemeral(true).queue();
            return false;
        }
        if (!requesterSnowflake.equals(ic.ownerSnowflake))
        {
            hook.sendMessage("That instance creation flow belongs to a different user or has expired.").setEphemeral(true).queue();
            return false;
        }
        return member != null;
    }

}

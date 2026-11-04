package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.GroupRole;
import io.github.vrchatapi.model.User;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.sybyline.scarlet.server.discord.DPerms;
import net.sybyline.scarlet.server.discord.DPerms.PermType;

/** Real command/helper control flow, synthetic members and API models; no application/client startup. */
class ScarletVRChatBanEntryPointsTest
{
    static final String GUILD = "100", REQUESTER = "200";
    static final String GROUP = "grp_10000000-0000-0000-0000-000000000001";
    static final String ACTOR = "usr_10000000-0000-0000-0000-000000000002";
    static final String TARGET = "usr_10000000-0000-0000-0000-000000000003";
    static final String BOT = "usr_10000000-0000-0000-0000-000000000004";
    static final String OTHER = "usr_10000000-0000-0000-0000-000000000005";
    @TempDir Path temp;

    @Test void slashBanRefusesEqualAndHigherRanksBeforeReservingAnything() throws Exception
    {
        Fixture f = fixture();
        for (int rank : new int[]{40, 10})
        {
            f.vrc.targetOrder = rank;
            f.commands.vrchatUserBan(f.event(SlashCommandInteractionEvent.class, TARGET, "vrchat-user-ban"), f.hook,
                new User().id(TARGET).displayName("Synthetic target"), false);
            assertEquals(0, f.vrc.submissions);
            assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
        }
        assertTrue(f.messages.stream().anyMatch(s -> s.contains("equal or higher")));
    }

    @Test void successfulSlashUsesActorAwareServiceAndFreshRequesterTwice() throws Exception
    {
        Fixture f = fixture();
        f.commands.vrchatUserBan(f.event(SlashCommandInteractionEvent.class, TARGET, "vrchat-user-ban"), f.hook,
            new User().id(TARGET).displayName("Synthetic target"), false);
        assertEquals(1, f.vrc.submissions);
        assertEquals(ACTOR, f.vrc.submittedActor);
        assertTrue(f.discord.retrievals >= 2);
        assertTrue(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
    }

    @Test void buttonAndTimedBanRefuseHigherTargetWithoutTimerOrReservation() throws Exception
    {
        Fixture f = fixture();
        f.vrc.targetOrder = 20;
        assertFalse(f.ui._vrchatUserBan(f.hook, f.member, TARGET));
        assertFalse(f.ui._timedBan(f.hook, f.member, TARGET, 60_000L));
        assertNull(f.pending.getTimedBan(TARGET));
        assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
        assertEquals(0, f.vrc.submissions);
    }

    @Test void successfulTimedBanCreatesTimerOnlyAfterConfirmedSubmission() throws Exception
    {
        Fixture f = fixture();
        assertTrue(f.ui._timedBan(f.hook, f.member, TARGET, 60_000L));
        assertEquals(1, f.vrc.submissions);
        assertNotNull(f.pending.getTimedBan(TARGET));
        assertEquals(ACTOR, f.pending.getTimedBan(TARGET).actorUserId);
        assertTrue(f.pending.getTimedBan(TARGET).isActiveFor(GROUP));
    }

    @Test void failedTimerPreparationRefusesSubmissionAndReportsNoBan() throws Exception
    {
        Fixture f = fixture();
        TimerFailingPending pending = timerStore(f);
        pending.failState = ScarletPendingModActions.TimedBanState.PREPARING;
        assertFalse(f.ui._timedBan(f.hook, f.member, TARGET, 60_000L));
        assertEquals(0, f.vrc.submissions);
        assertNull(pending.getTimedBan(TARGET));
        assertFalse(pending.hasPending(GroupAuditType.USER_BAN, TARGET));
        assertTrue(f.messages.stream().anyMatch(s -> s.contains("No ban was submitted")));
        assertFalse(f.messages.stream().anyMatch(s -> s.contains("automatic unban <t:")));
    }

    @Test void failedTimerActivationReportsBanWithoutPromisingExpiry() throws Exception
    {
        Fixture f = fixture();
        TimerFailingPending pending = timerStore(f);
        pending.failState = ScarletPendingModActions.TimedBanState.ACTIVE;
        assertFalse(f.ui._timedBan(f.hook, f.member, TARGET, 60_000L));
        assertEquals(1, f.vrc.submissions);
        assertEquals(ScarletPendingModActions.TimedBanState.PREPARING, pending.getTimedBan(TARGET).state);
        ScarletPendingModActions restarted = new ScarletPendingModActions(null, temp.resolve("timed-failure.json").toFile());
        assertEquals(ScarletPendingModActions.TimedBanState.PREPARING, restarted.getTimedBan(TARGET).state);
        assertTrue(f.messages.stream().anyMatch(s -> s.contains("ban succeeded") && s.contains("no automatic unban is guaranteed")));
        assertFalse(f.messages.stream().anyMatch(s -> s.contains("automatic unban <t:")));
    }

    @Test void unknownTimedBanOutcomeRetainsInactiveIntentForReview() throws Exception
    {
        Fixture f = fixture();
        f.vrc.nextOutcome = ScarletVRChat.GroupModerationResult.UNKNOWN;
        assertFalse(f.ui._timedBan(f.hook, f.member, TARGET, 60_000L));
        assertEquals(1, f.vrc.submissions);
        assertEquals(ScarletPendingModActions.TimedBanState.PREPARING, f.pending.getTimedBan(TARGET).state);
        assertTrue(f.messages.stream().anyMatch(s -> s.contains("No automatic unban is confirmed")));
        assertFalse(f.messages.stream().anyMatch(s -> s.contains("automatic unban <t:")));
    }

    @Test void rejectedTimedBanRemovesUnsubmittedIntentWithoutPromisingExpiry() throws Exception
    {
        Fixture f = fixture();
        f.vrc.nextOutcome = ScarletVRChat.GroupModerationResult.REJECTED;
        assertFalse(f.ui._timedBan(f.hook, f.member, TARGET, 60_000L));
        assertEquals(1, f.vrc.submissions);
        assertNull(f.pending.getTimedBan(TARGET));
        assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
        assertTrue(f.messages.stream().anyMatch(s -> s.contains("rejected")));
    }

    @Test void rejectedTimedBanReportsFailedIntentCleanup() throws Exception
    {
        Fixture f = fixture();
        TimerFailingPending pending = timerStore(f);
        pending.failState = ScarletPendingModActions.TimedBanState.RETIRED;
        f.vrc.nextOutcome = ScarletVRChat.GroupModerationResult.REJECTED;
        assertFalse(f.ui._timedBan(f.hook, f.member, TARGET, 60_000L));
        assertEquals(1, f.vrc.submissions);
        assertEquals(ScarletPendingModActions.TimedBanState.PREPARING, pending.getTimedBan(TARGET).state);
        assertTrue(f.messages.stream().anyMatch(s -> s.contains("rejected") && s.contains("intent could not be cleared")));
        assertFalse(f.messages.stream().anyMatch(s -> s.contains("automatic unban <t:")));
    }

    @Test void groupChangeBeforeSubmissionRetainsOriginalInactiveIntent() throws Exception
    {
        Fixture f = fixture();
        f.vrc.changeGroupAtFinal = true;
        assertFalse(f.ui._timedBan(f.hook, f.member, TARGET, 60_000L));
        assertEquals(0, f.vrc.submissions);
        assertEquals(GROUP, f.pending.getTimedBan(TARGET).groupId);
        assertEquals(ScarletPendingModActions.TimedBanState.PREPARING, f.pending.getTimedBan(TARGET).state);
        assertTrue(f.messages.stream().anyMatch(s -> s.contains("group changed") && s.contains("retained for manual review")));
    }

    TimerFailingPending timerStore(Fixture fixture) throws Exception
    {
        TimerFailingPending pending = new TimerFailingPending(temp.resolve("timed-failure.json").toFile());
        put(fixture.scarlet, "pendingModActions", pending);
        return pending;
    }

    static final class TimerFailingPending extends ScarletPendingModActions
    {
        TimedBanState failState;
        TimerFailingPending(File file) { super(null, file); }
        @Override public synchronized boolean save()
        {
            if (this.failState != null && this.timedBans.values().stream().anyMatch(timer -> timer.state == this.failState))
                return false;
            return super.save();
        }
    }

    @Test void immediateConfirmationRechecksOriginalCommandAndCurrentRank() throws Exception
    {
        Fixture f = fixture();
        f.pending.addBanInfo(TARGET, REQUESTER, ACTOR, GROUP, GUILD);
        f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-ban", false);
        f.ui.immediateBanConfirm(f.event(ButtonInteractionEvent.class, TARGET, "immediate-ban-confirm"), f.hook);
        assertEquals(0, f.vrc.submissions);
        assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
        f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-ban", true);
        f.vrc.targetOrder = 20;
        f.ui.immediateBanConfirm(f.event(ButtonInteractionEvent.class, TARGET, "immediate-ban-confirm"), f.hook);
        assertEquals(0, f.vrc.submissions);
    }

    @Test void immediateConfirmationRejectsMissingOrChangedOriginalContext() throws Exception
    {
        Fixture f = fixture();
        f.pending.addBanInfo(TARGET, REQUESTER);
        assertFalse(f.ui._vrchatUserBan(f.hook, f.member, TARGET, "immediate-ban-confirm"));
        f.pending.pollBanInfo(TARGET);
        f.pending.addBanInfo(TARGET, REQUESTER, ACTOR, GROUP, GUILD);
        f.scarlet.data.globalMetadata_setSnowflakeId(REQUESTER, OTHER);
        assertFalse(f.ui._vrchatUserBan(f.hook, f.member, TARGET, "immediate-ban-confirm"));
        f.scarlet.data.globalMetadata_setSnowflakeId(REQUESTER, ACTOR);
        f.vrc.groupId = "grp_changed";
        assertFalse(f.ui._vrchatUserBan(f.hook, f.member, TARGET, "immediate-ban-confirm"));
        f.vrc.groupId = GROUP;
        f.discord.guildSf = "999";
        assertFalse(f.ui._vrchatUserBan(f.hook, f.member, TARGET, "immediate-ban-confirm"));
        assertEquals(0, f.vrc.submissions);
        assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
    }

    @Test void queuedAndTimedModalActionsRecheckTheirOriginatingPermissions() throws Exception
    {
        Fixture f = fixture();
        f.perms.set(PermType.MODAL_SUBMIT, f.member, "vrchat-user-ban-multi", false);
        f.discord.pollQueuedAction_ban(f.action());
        assertEquals(0, f.vrc.submissions);
        f.perms.set(PermType.MODAL_SUBMIT, f.member, "timed-ban-custom", true);
        f.perms.set(PermType.BUTTON_PRESS, f.member, "timed-ban-custom", false);
        assertFalse(f.ui._timedBan(f.hook, f.member, TARGET, 60_000L, PermType.MODAL_SUBMIT, "timed-ban-custom"));
        assertEquals(0, f.vrc.submissions);
        assertNull(f.pending.getTimedBan(TARGET));
    }

    @Test void tagImmediatelyDoesNotCreateFlowForDisallowedTarget() throws Exception
    {
        Fixture f = fixture();
        f.vrc.targetOrder = 40;
        f.commands.vrchatUserBan(f.event(SlashCommandInteractionEvent.class, TARGET, "vrchat-user-ban"), f.hook,
            new User().id(TARGET).displayName("Synthetic target"), true);
        assertTrue(f.pending.pendingBanInfo.isEmpty());
        assertEquals(0, f.vrc.submissions);
    }

    @Test void bulkValidatesWholeBatchAndPersistsOriginalRequesterAndGroup() throws Exception
    {
        Fixture f = fixture();
        f.vrc.denyTarget = OTHER;
        f.ui.vrchatUserBanMulti(f.event(ModalInteractionEvent.class, TARGET + "\n" + OTHER, "vrchat-user-ban-multi"));
        assertTrue(f.discord.queuedActions.isEmpty());
        assertEquals(0, f.vrc.submissions);
        f.vrc.denyTarget = null;
        f.ui.vrchatUserBanMulti(f.event(ModalInteractionEvent.class, TARGET, "vrchat-user-ban-multi"));
        assertEquals(1, f.discord.queuedActions.size());
        ScarletDiscord.Action queued = f.discord.queuedActions.get(0);
        ScarletDiscord.Action reloaded = Scarlet.GSON.fromJson(Scarlet.GSON.toJson(queued), ScarletDiscord.Action.class);
        assertEquals(REQUESTER, reloaded.requesterDiscordId);
        assertEquals(GROUP, reloaded.vrchatGroupId);
        assertEquals(GUILD, reloaded.discordGuildId);
        f.discord.pollQueuedAction_ban(reloaded);
        assertEquals(1, f.vrc.submissions);
    }

    @Test void queuedBanRejectsRevokedAccessChangedLinkMissingMemberAndChangedGroup() throws Exception
    {
        Fixture f = fixture();
        ScarletDiscord.Action queued = f.action();
        f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-ban-multi", false);
        f.discord.pollQueuedAction_ban(queued);
        f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-ban-multi", true);
        f.scarlet.data.globalMetadata_setSnowflakeId(REQUESTER, OTHER);
        f.discord.pollQueuedAction_ban(queued);
        f.scarlet.data.globalMetadata_setSnowflakeId(REQUESTER, ACTOR);
        f.discord.freshMember = null;
        f.discord.pollQueuedAction_ban(queued);
        f.discord.freshMember = f.member;
        f.vrc.groupId = "grp_other";
        f.discord.pollQueuedAction_ban(queued);
        f.vrc.groupId = GROUP;
        f.discord.guildSf = "999";
        f.discord.pollQueuedAction_ban(queued);
        assertEquals(0, f.vrc.submissions);
        assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
    }

    @Test void queuedBanRechecksRankAndRejectsLegacyItems() throws Exception
    {
        Fixture f = fixture();
        f.discord.pollQueuedAction_ban(new ScarletDiscord.Action("ban", ACTOR, TARGET, null));
        f.vrc.targetOrder = 20;
        f.discord.pollQueuedAction_ban(f.action());
        assertEquals(0, f.vrc.submissions);
        assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
    }

    @Test void overrideAndDiscordAdministratorNeverBypassVRChatRank() throws Exception
    {
        Fixture f = fixture();
        f.discord.admin = true;
        f.vrc.actorCanBan = false;
        f.vrc.targetOrder = 40;
        assertFalse(f.discord.executeVRChatBan(REQUESTER, ACTOR, TARGET, PermType.SLASH_COMMAND, "vrchat-user-ban").succeeded());
        assertEquals(0, f.vrc.submissions);
        f.vrc.targetOrder = 900;
        assertTrue(f.discord.executeVRChatBan(REQUESTER, ACTOR, TARGET, PermType.SLASH_COMMAND, "vrchat-user-ban").succeeded());
        assertTrue(f.vrc.sawOverride);
    }

    @Test void finalPolicyRecheckReleasesOnlyItsReservationWhenAuthorityChanges() throws Exception
    {
        Fixture f = fixture();
        f.vrc.denyAtFinal = true;
        assertFalse(f.discord.executeVRChatBan(REQUESTER, ACTOR, TARGET, PermType.SLASH_COMMAND, "vrchat-user-ban").succeeded());
        assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
        assertEquals(0, f.vrc.submissions);
    }

    @Test void unknownBanResultRetainsPendingAndQuarantinesTimedIntent() throws Exception
    {
        Fixture f = fixture();
        f.vrc.nextOutcome = ScarletVRChat.GroupModerationResult.UNKNOWN;
        assertFalse(f.ui._timedBan(f.hook, f.member, TARGET, 60_000L));
        assertTrue(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
        ScarletPendingModActions.TimedBan intent = f.pending.getTimedBan(TARGET);
        assertNotNull(intent);
        assertEquals(ScarletPendingModActions.TimedBanState.PREPARING, intent.state);
        assertEquals(GROUP, intent.groupId);
        assertFalse(intent.isActiveFor(GROUP));
        ScarletPendingModActions restarted = new ScarletPendingModActions(null, temp.resolve("pending.json").toFile());
        assertEquals(intent.recordId, restarted.getTimedBan(TARGET).recordId);
        assertEquals(ScarletPendingModActions.TimedBanState.PREPARING, restarted.getTimedBan(TARGET).state);
        ScarletTimedBanProcessor.process(restarted, () -> GROUP, () -> Long.MAX_VALUE,
            (g, u) -> { fail("Unknown ban intent must not query membership after restart"); return null; },
            (g, u) -> { fail("Unknown ban intent must not submit an automatic unban"); return null; });
    }

    @Test void rejectedBanReleasesPendingAndUnavailablePolicyFailsClosed() throws Exception
    {
        Fixture f = fixture();
        f.vrc.nextOutcome = ScarletVRChat.GroupModerationResult.REJECTED;
        assertFalse(f.discord.executeVRChatBan(REQUESTER, ACTOR, TARGET, PermType.SLASH_COMMAND, "vrchat-user-ban").succeeded());
        assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
        f.vrc.unavailable = true;
        assertFalse(f.discord.executeVRChatBan(REQUESTER, ACTOR, TARGET, PermType.SLASH_COMMAND, "vrchat-user-ban").succeeded());
        assertEquals(1, f.vrc.submissions);
    }

    @Test void trainingDoesNotReachRealBanPolicyOrCreateTimers() throws Exception
    {
        Fixture f = fixture();
        String training = ScarletSimulation.TRAINING_ID_PREFIX + "synthetic";
        assertTrue(f.ui._vrchatUserBan(f.hook, f.member, training));
        assertTrue(f.ui._timedBan(f.hook, f.member, training, 60_000L));
        assertFalse(f.discord.executeVRChatBan(REQUESTER, ACTOR, training, PermType.SLASH_COMMAND, "vrchat-user-ban").succeeded());
        assertEquals(0, f.vrc.policyChecks);
        assertEquals(0, f.vrc.submissions);
        assertTrue(f.pending.timedBansSnapshot().isEmpty());
    }

    Fixture fixture() throws Exception { return new Fixture(temp); }

    static final class Fixture
    {
        final Scarlet scarlet;
        final FakeVrc vrc;
        final FakeDiscord discord;
        final ScarletPendingModActions pending;
        final ScarletDiscordCommands commands;
        final ScarletDiscordUI ui;
        final DPerms perms;
        final Member member;
        final InteractionHook hook;
        final List<String> messages = new ArrayList<>();

        Fixture(Path temp) throws Exception
        {
            scarlet = inert(Scarlet.class);
            vrc = inert(FakeVrc.class);
            vrc.targetOrder = 900;
            vrc.actorCanBan = true;
            vrc.nextOutcome = ScarletVRChat.GroupModerationResult.SUCCEEDED;
            vrc.groupId = GROUP;
            discord = inert(FakeDiscord.class);
            put(discord, "scarlet", scarlet);
            discord.guildSf = GUILD;
            discord.queuedActions = new ArrayList<>();
            Guild guild = proxy(Guild.class, (m, a) -> m.equals("getId") ? GUILD : null);
            member = proxy(Member.class, (m, a) -> {
                if (m.equals("getId")) return REQUESTER;
                if (m.equals("getGuild")) return guild;
                if (m.equals("getRoles")) return Collections.emptyList();
                if (m.equals("hasPermission")) return discord.admin;
                return null;
            });
            discord.freshMember = member;
            perms = new DPerms(temp.resolve("permissions.json").toFile());
            perms.getGuildSnowflakesMutable().add(GUILD);
            for (String command : new String[]{"vrchat-user-ban", "vrchat-user-ban-multi"})
                perms.set(PermType.SLASH_COMMAND, member, command, true);
            perms.set(PermType.BUTTON_PRESS, member, "timed-ban", true);
            put(discord, "perms", perms);
            put(scarlet, "vrc", vrc);
            put(scarlet, "data", new ScarletData(temp.toFile()));
            scarlet.data.globalMetadata_setSnowflakeId(REQUESTER, ACTOR);
            pending = new ScarletPendingModActions(null, temp.resolve("pending.json").toFile());
            put(scarlet, "pendingModActions", pending);
            ScarletSettings.FileValuedBoolean enabled = inert(ScarletSettings.FileValuedBoolean.class);
            put(enabled, "cached", Boolean.TRUE);
            put(scarlet, "timedBansEnabled", enabled);
            commands = inert(ScarletDiscordCommands.class);
            put(commands, "discord", discord);
            ui = inert(ScarletDiscordUI.class);
            put(ui, "discord", discord);
            hook = fluent(InteractionHook.class, messages);
        }

        ScarletDiscord.Action action()
        {
            return new ScarletDiscord.Action("ban", ACTOR, TARGET, null, REQUESTER, GUILD, GROUP);
        }

        <T> T event(Class<T> type, String target, String operation) throws Exception
        {
            net.dv8tion.jda.api.entities.User user = proxy(net.dv8tion.jda.api.entities.User.class,
                (m, a) -> m.equals("getId") ? REQUESTER : null);
            for (Constructor<?> constructor : type.getConstructors())
            {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length != 3 || !parameters[2].isInterface()) continue;
                Object interaction = proxy(parameters[2], (m, a) -> {
                    if (m.equals("getUser")) return user;
                    if (m.equals("getMember")) return member;
                    if (m.equals("getHook")) return hook;
                    if (m.equals("getModalId")) return operation + ":" + target;
                    if (m.equals("getButton")) return Button.danger(operation + ":" + target, "Ban");
                    if (m.equals("getOption")) return record(OptionMapping.class,
                        DataObject.empty().put("name", "vrchat-user").put("type", 3).put("value", target));
                    if (m.equals("getOptions") || m.equals("getOptionsByName")) return Collections.singletonList(record(OptionMapping.class,
                        DataObject.empty().put("name", "vrchat-user").put("type", 3).put("value", target)));
                    if (m.equals("getValue")) return record(ModalMapping.class,
                        DataObject.empty().put("id", 1).put("custom_id", "target-ids").put("type", 4).put("value", target));
                    if (m.equals("getValues")) return Collections.singletonList(record(ModalMapping.class,
                        DataObject.empty().put("id", 1).put("custom_id", "target-ids").put("type", 4).put("value", target)));
                    if (m.equals("getName")) return operation;
                    return null;
                }, messages);
                return type.cast(constructor.newInstance(fluent(parameters[0], messages), 0L, interaction));
            }
            throw new AssertionError("Unexpected interaction constructor");
        }
    }

    static class FakeDiscord extends ScarletDiscordJDA
    {
        FakeDiscord() { super(null, null, null); }
        Member freshMember;
        int retrievals;
        boolean admin;
        @Override Member retrieveBanRequester(String id) { retrievals++; return freshMember; }
        @Override public void emitActionFailure(Scarlet scarlet, Action action, String format, Object... args) {}
    }

    static class FakeVrc extends ScarletVRChat
    {
        FakeVrc() { super(null, null, null); }
        int targetOrder, policyChecks, submissions;
        boolean actorCanBan, denyAtFinal, unavailable, sawOverride, changeGroupAtFinal;
        String denyTarget, submittedActor;
        GroupModerationResult nextOutcome;

        @Override public ScarletBanAuthorization.Decision checkBanAuthorization(String actor, String target, boolean override)
        {
            policyChecks++;
            sawOverride = override;
            if (unavailable) return ScarletBanAuthorization.Decision.unverified();
            if (target.equals(denyTarget)) return ScarletBanAuthorization.Decision.deny("Synthetic higher-ranked target");
            GroupRole botRole = new GroupRole().id("bot").groupId(GROUP).order(1).defaultRole(false)
                .permissions(new ArrayList<>(Arrays.asList(GroupPermissions.group_bans_manage)));
            GroupRole actorRole = new GroupRole().id("actor").groupId(GROUP).order(40).defaultRole(false)
                .permissions(actorCanBan ? new ArrayList<>(Arrays.asList(GroupPermissions.group_bans_manage)) : new ArrayList<>());
            GroupRole targetRole = new GroupRole().id("target").groupId(GROUP).order(targetOrder).defaultRole(false)
                .permissions(new ArrayList<>());
            Group group = new Group().id(GROUP).ownerId(OTHER).roles(Arrays.asList(botRole, actorRole, targetRole));
            return ScarletBanAuthorization.evaluate(group, actor, target, BOT,
                membership(actor, "actor"), membership(target, "target"), membership(BOT, "bot"), override);
        }

        @Override public GroupBanResult banFromGroupAs(String actor, String target, boolean override)
        {
            return this.banFromGroupAs(this.groupId, actor, target, override);
        }
        @Override public GroupBanResult banFromGroupAs(String group, String actor, String target, boolean override)
        {
            if (this.changeGroupAtFinal) this.groupId = "grp_synthetic_other";
            if (!GROUP.equals(group) || !group.equals(this.groupId))
                return new GroupBanResult(ScarletBanAuthorization.Decision.unverified(), null);
            ScarletBanAuthorization.Decision decision = denyAtFinal ? ScarletBanAuthorization.Decision.unverified()
                : checkBanAuthorization(actor, target, override);
            if (!decision.allowed()) return new GroupBanResult(decision, null);
            submissions++;
            submittedActor = actor;
            return new GroupBanResult(decision, nextOutcome, java.time.OffsetDateTime.now(), BOT);
        }
        @Override public boolean banFromGroup(String target) { throw new AssertionError("Raw ban path must never be used"); }
        @Override public boolean checkUserHasVRChatPermission(GroupPermissions permission, String user) { return actorCanBan; }
        @Override public User getUser(String id, long epoch) { return new User().id(id).displayName("Synthetic target"); }
        @Override public GroupMemberStatus getGroupMembershipStatus(String group, String user) { return GroupMemberStatus.MEMBER; }
        static GroupMember membership(String user, String role)
        {
            return new GroupMember().groupId(GROUP).userId(user).membershipStatus(GroupMemberStatus.MEMBER)
                .roleIds(new ArrayList<>(Arrays.asList(role))).mRoleIds(new ArrayList<>());
        }
    }

    interface Answer { Object answer(String method, Object[] args) throws Exception; }
    static <T> T proxy(Class<T> type, Answer answer) { return proxy(type, answer, new ArrayList<>()); }
    static <T> T proxy(Class<T> type, Answer answer, List<String> messages)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            Object result = answer.answer(m.getName(), a);
            if (result != null) return result;
            if (m.getName().equals("sendMessage") || m.getName().equals("sendMessageFormat") || m.getName().equals("reply"))
                if (a != null && a.length > 0) messages.add(String.valueOf(a[0]));
            if (m.getReturnType().isInstance(p)) return p;
            if (m.getReturnType().isInterface()) return fluent(m.getReturnType(), messages);
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType() == long.class) return 0L;
            if (m.getReturnType() == int.class) return 0;
            return null;
        }));
    }
    static <T> T fluent(Class<T> type, List<String> messages) { return proxy(type, (m, a) -> null, messages); }
    static <T> T record(Class<T> type, DataObject data) throws Exception
    {
        for (Constructor<?> constructor : type.getConstructors())
        {
            Object[] args = new Object[constructor.getParameterCount()];
            Class<?>[] types = constructor.getParameterTypes();
            boolean containsData = false;
            for (int index = 0; index < types.length; index++)
                if (types[index] == DataObject.class) { args[index] = data; containsData = true; }
            if (!containsData) continue;
            try { return type.cast(constructor.newInstance(args)); }
            catch (ReflectiveOperationException ignored) {}
        }
        throw new AssertionError("Cannot construct synthetic " + type.getSimpleName());
    }
    static <T> T inert(Class<T> type) throws Exception { return ScarletDiscordSafetyTest.inert(type); }
    static void put(Object object, String field, Object value) throws Exception { ScarletDiscordSafetyTest.put(object, field, value); }
}

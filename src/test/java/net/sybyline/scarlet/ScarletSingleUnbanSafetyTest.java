package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletVRChatBanEntryPointsTest.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vrchatapi.model.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.sybyline.scarlet.server.discord.DPerms.PermType;

/** Both real entry points execute only against synthetic API responses and temporary stores. */
class ScarletSingleUnbanSafetyTest
{
    @TempDir Path temp;

    Fixture fixture(String suffix) throws Exception
    {
        Fixture f = new Fixture(temp.resolve(suffix));
        SingleVrc vrc = inert(SingleVrc.class);
        vrc.groupId = GROUP;
        vrc.currentUserId = BOT;
        vrc.actorCanBan = vrc.botCanBan = vrc.actorPresent = vrc.targetBanned = true;
        vrc.outcome = ScarletVRChat.GroupModerationResult.SUCCEEDED;
        put(f.scarlet, "vrc", vrc);
        f.perms.set(PermType.SLASH_COMMAND, f.member, "vrchat-user-unban", true);
        f.perms.set(PermType.BUTTON_PRESS, f.member, "vrchat-user-unban", true);
        return f;
    }

    void invoke(Fixture f, boolean button) throws Exception
    {
        if (button)
            f.ui.vrchatUserUnban(f.event(ButtonInteractionEvent.class, TARGET, "vrchat-user-unban"), f.hook);
        else
            f.commands.vrchatUserUnban(f.event(SlashCommandInteractionEvent.class, TARGET, "vrchat-user-unban"), f.hook,
                new User().id(TARGET).displayName("Synthetic target"));
    }

    @Test void bothRoutesRefuseOppositeReservationInsteadOfSubmittingUnreserved() throws Exception
    {
        for (boolean button : new boolean[]{false, true})
        {
            Fixture f = fixture("opposite-" + button);
            f.pending.reservePending(GroupAuditType.USER_BAN, TARGET, ACTOR, GROUP);
            invoke(f, button);
            assertEquals(0, ((SingleVrc)f.scarlet.vrc).unbans);
            assertTrue(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
            assertFalse(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
            assertTrue(f.messages.stream().anyMatch(m -> m.contains("pending moderation")));
        }
    }

    @Test void bothRoutesRetainUnknownAndDoNotImmediatelyRepeat() throws Exception
    {
        for (boolean button : new boolean[]{false, true})
        {
            Fixture f = fixture("unknown-" + button);
            SingleVrc vrc = (SingleVrc)f.scarlet.vrc;
            vrc.outcome = ScarletVRChat.GroupModerationResult.UNKNOWN;
            invoke(f, button);
            assertEquals(1, vrc.unbans);
            assertTrue(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
            assertTrue(f.messages.stream().anyMatch(m -> m.contains("outcome is unknown")));
            ScarletPendingModActions restarted = new ScarletPendingModActions(null, temp.resolve("unknown-" + button).resolve("pending.json").toFile());
            assertTrue(restarted.hasPending(GroupAuditType.USER_UNBAN, TARGET));
            invoke(f, button);
            assertEquals(1, vrc.unbans);
        }
    }

    @Test void bothRoutesReleaseOnlyDefiniteRejectionAndSuccessKeepsAttribution() throws Exception
    {
        for (boolean button : new boolean[]{false, true})
        {
            Fixture f = fixture("result-" + button);
            SingleVrc vrc = (SingleVrc)f.scarlet.vrc;
            vrc.outcome = ScarletVRChat.GroupModerationResult.REJECTED;
            invoke(f, button);
            assertEquals(1, vrc.unbans);
            assertFalse(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
            vrc.outcome = ScarletVRChat.GroupModerationResult.SUCCEEDED;
            invoke(f, button);
            assertEquals(2, vrc.unbans);
            assertTrue(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
            assertEquals(6, f.discord.retrievals, "Each attempt refreshes Discord authority three times");
        }
    }

    @Test void bothRoutesRecheckPermissionAfterReservationPersistence() throws Exception
    {
        for (boolean button : new boolean[]{false, true})
        {
            Fixture f = fixture("revoked-" + button);
            ScarletQueuedUnbanAuthorizationTest.ChangingPending pending = new ScarletQueuedUnbanAuthorizationTest.ChangingPending(
                temp.resolve("revoked-pending-" + button + ".json").toFile());
            pending.afterReserve = () -> f.perms.set(button ? PermType.BUTTON_PRESS : PermType.SLASH_COMMAND,
                f.member, "vrchat-user-unban", false);
            put(f.scarlet, "pendingModActions", pending);
            invoke(f, button);
            assertEquals(1, pending.reservations);
            assertEquals(0, ((SingleVrc)f.scarlet.vrc).unbans);
            assertFalse(pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
        }
    }

    @Test void bothRoutesFailClosedWhenReservationCannotBeSaved() throws Exception
    {
        for (boolean button : new boolean[]{false, true})
        {
            Fixture f = fixture("save-" + button);
            ScarletPendingModActions failing = new ScarletPendingModActions(null, temp.resolve("fail-" + button + ".json").toFile())
            {
                @Override public synchronized boolean save() { return false; }
            };
            put(f.scarlet, "pendingModActions", failing);
            invoke(f, button);
            assertEquals(0, ((SingleVrc)f.scarlet.vrc).unbans);
            assertFalse(failing.hasPending(GroupAuditType.USER_UNBAN, TARGET));
        }
    }

    @Test void exceptionAfterSubmissionRetainsUnknownProtectionOnBothRoutes() throws Exception
    {
        for (boolean button : new boolean[]{false, true})
        {
            Fixture f = fixture("throw-" + button);
            SingleVrc vrc = (SingleVrc)f.scarlet.vrc;
            vrc.throwAfterSubmit = true;
            invoke(f, button);
            assertEquals(1, vrc.unbans);
            assertTrue(f.pending.hasPending(GroupAuditType.USER_UNBAN, TARGET));
            assertTrue(f.messages.stream().anyMatch(m -> m.contains("outcome is unknown")));
        }
    }

    static class SingleVrc extends ScarletQueuedUnbanAuthorizationTest.UnbanVrc
    {
        boolean throwAfterSubmit;
        @Override public User getUser(String id, long epoch) { return new User().id(id).displayName("Synthetic target"); }
        @Override public GroupModerationResult unbanFromGroupDetailed(String group, String user)
        {
            GroupModerationResult result = super.unbanFromGroupDetailed(group, user);
            if (throwAfterSubmit) throw new IllegalStateException("Synthetic transport failure after submit");
            return result;
        }
    }
}

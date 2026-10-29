package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletVRChatBanEntryPointsTest.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletModerationQueueTest
{
    @TempDir Path temp;

    ScarletDiscord.Action action()
    {
        return new ScarletDiscord.Action("ban", ACTOR, TARGET, "not-to-be-persisted", REQUESTER, GUILD, GROUP);
    }

    @Test void acceptanceIsDurableAndSnapshotsCannotMutateTheReceipt() throws Exception
    {
        File file = temp.resolve("queue.json").toFile();
        ScarletModerationQueue queue = new ScarletModerationQueue(file);
        ScarletDiscord.Action action = action();
        queue.accept(Collections.singletonList(action));
        action.actorId = OTHER;
        queue.pending().get(0).targetId = OTHER;
        assertEquals(ACTOR, queue.receipts().get(0).action.actorId);
        assertEquals(TARGET, queue.receipts().get(0).action.targetId);
        assertFalse(Files.readString(file.toPath()).contains("not-to-be-persisted"));
        ScarletModerationQueue restarted = new ScarletModerationQueue(file);
        assertEquals(1, restarted.receipts().size());
        assertEquals(ScarletModerationQueue.State.REVIEW, restarted.receipts().get(0).state);
        assertNull(restarted.claim(), "Restarted old intent requires review, not automatic replay");
    }

    @Test void failedBatchAcceptanceCannotBeAcknowledgedOrPartiallyAccepted()
    {
        FaultQueue queue = new FaultQueue(temp.resolve("queue.json").toFile());
        queue.fail = true;
        assertThrows(IllegalStateException.class, () -> queue.accept(Arrays.asList(action(), action())));
        assertTrue(queue.receipts().isEmpty());
        assertFalse(temp.resolve("queue.json").toFile().exists());
        queue.fail = false;
        ScarletDiscord.Action bad = action();
        bad.requesterDiscordId = null;
        assertThrows(IllegalArgumentException.class, () -> queue.accept(Arrays.asList(action(), bad)));
        assertTrue(queue.receipts().isEmpty());
    }

    @Test void claimMustPersistBeforeWorkerMayRunAndInterruptedClaimNeverReplays()
    {
        File file = temp.resolve("queue.json").toFile();
        FaultQueue queue = new FaultQueue(file);
        queue.accept(Collections.singletonList(action()));
        queue.fail = true;
        assertThrows(IllegalStateException.class, queue::claim);
        assertEquals(1, queue.pending().size());
        queue.fail = false;
        ScarletModerationQueue.Receipt claimed = queue.claim();
        assertEquals(ScarletModerationQueue.State.CLAIMED, claimed.state);
        assertNull(queue.claim());
        ScarletModerationQueue restarted = new ScarletModerationQueue(file);
        assertEquals(1, restarted.reviewCount());
        assertNull(restarted.claim());
    }

    @Test void successfulAndRejectedReceiptsStayTerminalAcrossRestart()
    {
        for (ScarletVRChat.GroupModerationResult result : new ScarletVRChat.GroupModerationResult[]{
            ScarletVRChat.GroupModerationResult.SUCCEEDED, ScarletVRChat.GroupModerationResult.REJECTED})
        {
            File file = temp.resolve(result.name() + ".json").toFile();
            ScarletModerationQueue queue = new ScarletModerationQueue(file);
            queue.accept(Collections.singletonList(action()));
            String id = queue.claim().id;
            queue.finish(id, result);
            ScarletModerationQueue restarted = new ScarletModerationQueue(file);
            assertEquals(result == ScarletVRChat.GroupModerationResult.SUCCEEDED ? ScarletModerationQueue.State.SUCCEEDED
                : ScarletModerationQueue.State.REJECTED, restarted.receipts().get(0).state);
            assertNull(restarted.claim());
        }
    }

    @Test void unknownAndFailedResultSaveCannotReplay()
    {
        File file = temp.resolve("queue.json").toFile();
        FaultQueue queue = new FaultQueue(file);
        queue.accept(Collections.singletonList(action()));
        String id = queue.claim().id;
        queue.fail = true;
        assertThrows(IllegalStateException.class, () -> queue.finish(id, ScarletVRChat.GroupModerationResult.SUCCEEDED));
        assertNull(queue.claim());
        assertEquals(ScarletModerationQueue.State.CLAIMED, queue.receipts().get(0).state);
        queue.fail = false;
        queue.finish(id, ScarletVRChat.GroupModerationResult.UNKNOWN);
        assertEquals(ScarletModerationQueue.State.REVIEW, queue.receipts().get(0).state);
        assertNull(new ScarletModerationQueue(file).claim());
    }

    @Test void legacyImportIsRetainedIdempotentlyAndNeverExecuted()
    {
        File file = temp.resolve("queue.json").toFile();
        ScarletModerationQueue queue = new ScarletModerationQueue(file);
        ScarletDiscord.Action legacy = new ScarletDiscord.Action("unban", ACTOR, TARGET, null);
        queue.importLegacy(Arrays.asList(legacy, legacy));
        queue.importLegacy(Arrays.asList(legacy, legacy));
        assertEquals(2, queue.reviewCount());
        assertNull(queue.claim());
        ScarletModerationQueue restarted = new ScarletModerationQueue(file);
        restarted.importLegacy(Arrays.asList(legacy, legacy));
        assertEquals(2, restarted.reviewCount());
        assertNull(restarted.claim());
    }

    @Test void corruptOrUnsupportedFilesFailClosedWithoutOverwrite() throws Exception
    {
        File file = temp.resolve("queue.json").toFile();
        for (String json : new String[]{"{", "{}", "null", "{\"version\":99,\"receipts\":[]}",
            "{\"version\":1,\"receipts\":[null]}"})
        {
            Files.writeString(file.toPath(), json);
            assertThrows(IllegalStateException.class, () -> new ScarletModerationQueue(file));
            assertEquals(json, Files.readString(file.toPath()));
        }
    }

    @Test void actualWorkerNeverCallsApiWhenClaimCannotPersistAndNeverRepeatsCompletedAction() throws Exception
    {
        Fixture f = new Fixture(temp);
        FaultQueue queue = new FaultQueue(temp.resolve("queue.json").toFile());
        f.discord.moderationQueue = queue;
        f.discord.enqueueModerationActions(Collections.singletonList(f.action()));
        queue.fail = true;
        assertEquals(10L, f.discord.pollQueuedAction());
        assertEquals(0, f.vrc.submissions);
        queue.fail = false;
        assertEquals(60L, f.discord.pollQueuedAction());
        assertEquals(1, f.vrc.submissions);
        assertEquals(ScarletModerationQueue.State.SUCCEEDED, queue.receipts().get(0).state);
        f.discord.pollQueuedAction();
        assertEquals(1, f.vrc.submissions);
    }

    @Test void acceptedModalRequestsHaveReceiptAndFailedPersistenceDoesNotClaimSuccess() throws Exception
    {
        Fixture f = new Fixture(temp);
        FaultQueue queue = new FaultQueue(temp.resolve("queue.json").toFile());
        f.discord.moderationQueue = queue;
        queue.fail = true;
        f.ui.vrchatUserBanMulti(f.event(net.dv8tion.jda.api.events.interaction.ModalInteractionEvent.class, TARGET, "vrchat-user-ban-multi"));
        assertTrue(f.messages.stream().anyMatch(m -> m.contains("No bans were accepted")));
        assertTrue(queue.receipts().isEmpty());
        assertEquals(0, f.vrc.submissions);
        f.messages.clear();
        queue.fail = false;
        f.ui.vrchatUserBanMulti(f.event(net.dv8tion.jda.api.events.interaction.ModalInteractionEvent.class, TARGET, "vrchat-user-ban-multi"));
        assertEquals(1, queue.receipts().size());
        assertTrue(f.messages.stream().anyMatch(m -> m.contains("Queuing")));
    }

    @Test void workerResultSaveFailureRetainsClaimWithoutSubmittingAgain() throws Exception
    {
        Fixture f = new Fixture(temp);
        FaultQueue queue = new FaultQueue(temp.resolve("queue.json").toFile());
        f.discord.moderationQueue = queue;
        f.discord.enqueueModerationActions(Collections.singletonList(f.action()));
        queue.failState = ScarletModerationQueue.State.SUCCEEDED;
        f.discord.pollQueuedAction();
        assertEquals(1, f.vrc.submissions);
        assertEquals(ScarletModerationQueue.State.CLAIMED, queue.receipts().get(0).state);
        f.pending.pollPending(GroupAuditType.USER_BAN, TARGET); // Even after its audit consumes attribution.
        f.discord.pollQueuedAction();
        assertEquals(1, f.vrc.submissions);
        f.discord.moderationQueue = new ScarletModerationQueue(temp.resolve("queue.json").toFile());
        f.discord.pollQueuedAction();
        assertEquals(1, f.vrc.submissions);
    }

    @Test void workerUnknownAndLegacyActionsAreRetainedWithoutReplay() throws Exception
    {
        Fixture f = new Fixture(temp);
        f.vrc.nextOutcome = ScarletVRChat.GroupModerationResult.UNKNOWN;
        f.discord.enqueueModerationActions(Collections.singletonList(f.action()));
        f.discord.pollQueuedAction();
        assertEquals(1, f.vrc.submissions);
        assertEquals(ScarletModerationQueue.State.REVIEW, f.discord.moderationQueue.receipts().get(0).state);
        f.pending.pollPending(GroupAuditType.USER_BAN, TARGET);
        f.discord.moderationQueue.importLegacy(Collections.singletonList(f.action()));
        f.discord.pollQueuedAction();
        assertEquals(1, f.vrc.submissions);
        assertEquals(2, f.discord.moderationQueue.reviewCount());
    }

    @Test void queuedBanCannotFollowAGroupChangeDuringRequesterRefresh() throws Exception
    {
        Fixture f = new Fixture(temp);
        ContextChangingDiscord discord = inert(ContextChangingDiscord.class);
        put(discord, "scarlet", f.scarlet);
        put(discord, "perms", f.perms);
        discord.guildSf = GUILD;
        discord.freshMember = f.member;
        discord.changeAtRetrieval = 1;
        discord.change = () -> f.vrc.groupId = "grp_20000000-0000-0000-0000-000000000001";
        discord.enqueueModerationActions(Collections.singletonList(f.action()));
        discord.pollQueuedAction();
        assertEquals(1, f.vrc.policyChecks, "The group changes during the blocking fresh requester lookup");
        assertEquals(0, f.vrc.submissions);
        assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
        assertEquals(ScarletModerationQueue.State.REJECTED, discord.moderationQueue.receipts().get(0).state);
    }

    @Test void queuedBanRechecksGuildAfterDurableReservationAndReleasesOnlyItsAttempt() throws Exception
    {
        Fixture f = new Fixture(temp);
        ContextChangingDiscord discord = inert(ContextChangingDiscord.class);
        put(discord, "scarlet", f.scarlet);
        put(discord, "perms", f.perms);
        discord.guildSf = GUILD;
        discord.freshMember = f.member;
        discord.changeAtRetrieval = 2;
        discord.change = () -> {
            assertTrue(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
            discord.guildSf = "another-guild";
        };
        discord.enqueueModerationActions(Collections.singletonList(f.action()));
        discord.pollQueuedAction();
        assertEquals(2, discord.retrievals);
        assertEquals(0, f.vrc.submissions);
        assertFalse(f.pending.hasPending(GroupAuditType.USER_BAN, TARGET));
        assertEquals(ScarletModerationQueue.State.REJECTED, discord.moderationQueue.receipts().get(0).state);
    }

    static class ContextChangingDiscord extends FakeDiscord
    {
        int changeAtRetrieval;
        Runnable change;
        @Override net.dv8tion.jda.api.entities.Member retrieveBanRequester(String requester)
        {
            net.dv8tion.jda.api.entities.Member member = super.retrieveBanRequester(requester);
            if (retrievals == changeAtRetrieval) change.run();
            return member;
        }
    }

    static class FaultQueue extends ScarletModerationQueue
    {
        boolean fail;
        State failState;
        FaultQueue(File file) { super(file); }
        @Override void persist(Store next) throws IOException
        {
            if (fail || failState != null && next.receipts.stream().anyMatch(receipt -> receipt.state == failState))
                throw new IOException("Synthetic storage failure");
            super.persist(next);
        }
    }
}

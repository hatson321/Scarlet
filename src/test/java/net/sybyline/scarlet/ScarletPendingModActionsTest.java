package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vrchatapi.model.GroupMemberStatus;
import net.sybyline.scarlet.ScarletPendingModActions.PendingReservation;
import net.sybyline.scarlet.ScarletVRChat.GroupModerationResult;

class ScarletPendingModActionsTest
{
    @TempDir Path temp;
    private final AtomicLong clock = new AtomicLong(1_900_000_000_000L);

    private ScarletPendingModActions store()
    {
        return new ScarletPendingModActions(null, temp.resolve("pending.json").toFile(), clock::get);
    }

    @Test
    void rejectedBanAndUnbanCanBeRetriedImmediately()
    {
        ScarletPendingModActions pending = store();
        for (GroupAuditType type : new GroupAuditType[] {GroupAuditType.USER_BAN, GroupAuditType.USER_UNBAN})
        {
            PendingReservation attempt = pending.reservePending(type, "target", "actor");
            assertNotNull(attempt);
            assertEquals(GroupModerationResult.REJECTED, ScarletManualModeration.perform(pending, attempt,
                type == GroupAuditType.USER_BAN, () -> GroupModerationResult.REJECTED,
                () -> { fail("Known rejection must not require reconciliation"); return null; }));
            assertFalse(pending.hasPending(type, "target"));
            PendingReservation retry = pending.reservePending(type, "target", "actor");
            assertNotNull(retry);
            GroupAuditType opposite = type == GroupAuditType.USER_BAN ? GroupAuditType.USER_UNBAN : GroupAuditType.USER_BAN;
            assertNull(pending.reservePending(opposite, "target", "actor"), "An outstanding retry must block the opposite action");
            assertTrue(pending.releasePending(retry));
            assertFalse(store().hasPending(type, "target"), "Release must be durable before testing the next action type");
        }
    }

    @Test
    void successKeepsAttributionUntilAuditObservation()
    {
        ScarletPendingModActions pending = store();
        PendingReservation attempt = pending.reservePending(GroupAuditType.USER_BAN, "target", "actor");
        assertEquals(GroupModerationResult.SUCCEEDED, ScarletManualModeration.perform(pending, attempt, true,
            () -> GroupModerationResult.SUCCEEDED, () -> null));
        assertEquals("actor", pending.pollPending(GroupAuditType.USER_BAN, "target"));
        assertFalse(pending.hasPending(GroupAuditType.USER_BAN, "target"));
        assertFalse(pending.releasePending(attempt));
    }

    @Test
    void unknownOutcomeWaitsForReconciliationOrBoundedExpiry()
    {
        ScarletPendingModActions pending = store();
        PendingReservation attempt = pending.reservePending(GroupAuditType.USER_UNBAN, "target", "actor");
        assertEquals(GroupModerationResult.UNKNOWN, ScarletManualModeration.perform(pending, attempt, false,
            () -> GroupModerationResult.UNKNOWN, () -> null));
        assertNull(pending.reservePending(GroupAuditType.USER_UNBAN, "target", "other"));
        clock.addAndGet(ScarletPendingModActions.PENDING_TIMEOUT_MILLIS - 1);
        assertTrue(pending.hasPending(GroupAuditType.USER_UNBAN, "target"));
        clock.incrementAndGet();
        assertFalse(pending.hasPending(GroupAuditType.USER_UNBAN, "target"));
        assertNotNull(pending.reservePending(GroupAuditType.USER_UNBAN, "target", "other"));
    }

    @Test
    void uncertainBanCanReconcileAgainstConfirmedMembership()
    {
        ScarletPendingModActions pending = store();
        PendingReservation attempt = pending.reservePending(GroupAuditType.USER_BAN, "target", "actor");
        assertEquals(GroupModerationResult.SUCCEEDED, ScarletManualModeration.perform(pending, attempt, true,
            () -> GroupModerationResult.UNKNOWN, () -> GroupMemberStatus.BANNED));
        assertTrue(pending.hasPending(GroupAuditType.USER_BAN, "target"));
    }

    @Test
    void uncertainUnbanCanReconcileButOppositeStateRemainsUnknown()
    {
        ScarletPendingModActions pending = store();
        PendingReservation attempt = pending.reservePending(GroupAuditType.USER_UNBAN, "target", "actor");
        assertEquals(GroupModerationResult.SUCCEEDED, ScarletManualModeration.perform(pending, attempt, false,
            () -> GroupModerationResult.UNKNOWN, () -> GroupMemberStatus.INACTIVE));
        assertEquals(GroupModerationResult.UNKNOWN, ScarletManualModeration.perform(pending, attempt, false,
            () -> GroupModerationResult.UNKNOWN, () -> GroupMemberStatus.BANNED));
    }

    @Test
    void unexpectedExceptionKeepsOnlyABoundedUnknownReservation()
    {
        ScarletPendingModActions pending = store();
        PendingReservation attempt = pending.reservePending(GroupAuditType.USER_BAN, "target", "actor");
        assertEquals(GroupModerationResult.UNKNOWN, ScarletManualModeration.perform(pending, attempt, true,
            () -> { throw new IllegalStateException("synthetic uncertain failure"); }, () -> null));
        assertTrue(pending.hasPending(GroupAuditType.USER_BAN, "target"));
        clock.addAndGet(ScarletPendingModActions.PENDING_TIMEOUT_MILLIS);
        assertFalse(pending.hasPending(GroupAuditType.USER_BAN, "target"));
    }

    @Test
    void oldResponseCannotClearANewerAttemptEvenForTheSameActor()
    {
        ScarletPendingModActions pending = store();
        PendingReservation first = pending.reservePending(GroupAuditType.USER_BAN, "target", "actor");
        clock.addAndGet(ScarletPendingModActions.PENDING_TIMEOUT_MILLIS);
        PendingReservation second = pending.reservePending(GroupAuditType.USER_BAN, "target", "actor");
        assertNotNull(second);
        assertEquals(GroupModerationResult.REJECTED, ScarletManualModeration.perform(pending, first, true,
            () -> GroupModerationResult.REJECTED, () -> null));
        assertTrue(pending.hasPending(GroupAuditType.USER_BAN, "target"));
        assertTrue(pending.releasePending(second));
    }

    @Test
    void concurrentReservationsHaveOnlyOneWinner() throws Exception
    {
        ScarletPendingModActions pending = store();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            List<Future<PendingReservation>> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                requests.add(executor.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return pending.reservePending(GroupAuditType.USER_BAN, "target", "actor");
                }));
            start.countDown();
            int winners = 0;
            for (Future<PendingReservation> request : requests)
                if (request.get(10, TimeUnit.SECONDS) != null) winners++;
            assertEquals(1, winners);
            assertEquals("actor", store().getPending(GroupAuditType.USER_BAN, "target"));
        }
        finally
        {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void pendingAgeSurvivesRestartAndDoesNotRemoveTimedBan()
    {
        ScarletPendingModActions pending = store();
        pending.setTimedBan("target", clock.get() + 1000L, "actor");
        assertNull(pending.addPending(GroupAuditType.USER_UNBAN, "target", "actor"));
        clock.addAndGet(ScarletPendingModActions.PENDING_TIMEOUT_MILLIS - 1);
        assertTrue(store().hasPending(GroupAuditType.USER_UNBAN, "target"));
        clock.incrementAndGet();
        ScarletPendingModActions reloaded = store();
        assertFalse(reloaded.hasPending(GroupAuditType.USER_UNBAN, "target"));
        assertNotNull(reloaded.getTimedBan("target"), "Expired request must not discard the required unban timer");
        assertNull(reloaded.addPending(GroupAuditType.USER_UNBAN, "target", "actor"));
    }

    @Test
    void legacyUntimedMarkerGetsOnePersistedGraceWindow() throws Exception
    {
        Files.write(temp.resolve("pending.json"),
            "{\"pendingModActions\":{\"group.user.unban:target\":\"actor\"}}".getBytes(StandardCharsets.UTF_8));
        assertTrue(store().hasPending(GroupAuditType.USER_UNBAN, "target"));
        clock.addAndGet(ScarletPendingModActions.PENDING_TIMEOUT_MILLIS - 1);
        assertTrue(store().hasPending(GroupAuditType.USER_UNBAN, "target"));
        clock.incrementAndGet();
        assertFalse(store().hasPending(GroupAuditType.USER_UNBAN, "target"));
    }

    @Test
    void failedReservationPersistenceDoesNotAuthorizeTheRemoteAction() throws Exception
    {
        Path blocked = temp.resolve("not-a-directory");
        Files.write(blocked, new byte[] {1});
        ScarletPendingModActions pending = new ScarletPendingModActions(null, blocked.resolve("pending.json").toFile(), clock::get);
        assertThrows(IllegalStateException.class, () -> pending.reservePending(GroupAuditType.USER_BAN, "target", "actor"));
        assertFalse(pending.hasPending(GroupAuditType.USER_BAN, "target"));
    }

    @Test
    void onlyDefiniteHttpRejectionsReleaseReservations()
    {
        for (int status : new int[] {400, 401, 403, 404, 405, 409, 422, 429})
            assertEquals(GroupModerationResult.REJECTED, ScarletVRChat.groupModerationFailure(status));
        for (int status : new int[] {0, 408, 500, 502, 503, 504})
            assertEquals(GroupModerationResult.UNKNOWN, ScarletVRChat.groupModerationFailure(status));
    }
}

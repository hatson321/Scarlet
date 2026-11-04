package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.vrchatapi.model.GroupMemberStatus;
import net.sybyline.scarlet.ScarletPendingModActions.TimedBan;
import net.sybyline.scarlet.ScarletPendingModActions.TimedBanState;
import net.sybyline.scarlet.ScarletVRChat.GroupModerationResult;

/** Synthetic storage, clocks and API callbacks only; never starts Scarlet or a client. */
class ScarletTimedBanPersistenceTest
{
    private static final String GROUP_A = "grp_synthetic_a", GROUP_B = "grp_synthetic_b";
    private static final String TARGET = "usr_synthetic_target", ACTOR = "usr_synthetic_actor";
    @TempDir Path temp;
    final AtomicLong clock = new AtomicLong(1000L);

    static final class FailingStore extends ScarletPendingModActions
    {
        int writes, failAt = -1;
        boolean failAll;
        FailingStore(File file, AtomicLong clock) { super(null, file, clock::get); }
        @Override public synchronized boolean save()
        {
            this.writes++;
            return !this.failAll && this.writes != this.failAt && super.save();
        }
        void failNext(int offset) { this.failAt = this.writes + offset; }
    }

    FailingStore store() { return new FailingStore(this.temp.resolve("pending.json").toFile(), this.clock); }

    TimedBan active(ScarletPendingModActions store)
    {
        TimedBan timer = store.prepareTimedBan(GROUP_A, TARGET, this.clock.get() + 1000L, ACTOR);
        assertTrue(store.activateTimedBan(TARGET, timer, java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(this.clock.get()), java.time.ZoneOffset.UTC), ACTOR));
        assertTrue(store.confirmTimedBanAudit(TARGET, timer, "gaud_synthetic_original"));
        this.clock.addAndGet(2000L);
        return store.getTimedBan(TARGET);
    }

    void noCalls(ScarletPendingModActions store, String group)
    {
        ScarletTimedBanProcessor.process(store, () -> group, this.clock::get,
            (g, u) -> { fail("Unexpected membership request"); return null; },
            (g, u) -> { fail("Unexpected unban request"); return null; });
    }

    void process(ScarletPendingModActions store, GroupModerationResult result, AtomicInteger calls)
    {
        ScarletTimedBanProcessor.process(store, () -> GROUP_A, this.clock::get,
            (g, u) -> { assertEquals(GROUP_A, g); assertEquals(TARGET, u); return GroupMemberStatus.BANNED; },
            (g, u, t) -> new ScarletTimedBanContinuity.Proof(ScarletTimedBanContinuity.Verdict.VERIFIED, "gaud_synthetic_original"),
            (g, u) -> { assertEquals(GROUP_A, g); assertEquals(TARGET, u); calls.incrementAndGet(); return result; });
    }

    @Test void confirmedTimerRoundTripsAndExpiresInItsOriginalGroup()
    {
        FailingStore first = store();
        TimedBan timer = active(first);
        FailingStore restarted = store();
        assertEquals(timer.recordId, restarted.getTimedBan(TARGET).recordId);
        assertTrue(restarted.getTimedBan(TARGET).isActiveFor(GROUP_A));
        AtomicInteger calls = new AtomicInteger();
        process(restarted, GroupModerationResult.SUCCEEDED, calls);
        assertEquals(1, calls.get());
        assertNull(store().getTimedBan(TARGET));
    }

    @ParameterizedTest @EnumSource(value = GroupMemberStatus.class, names = {"BANNED", "MEMBER"})
    void changingGroupsNeverQueriesOrDeletesOriginalTimer(GroupMemberStatus irrelevantOtherGroupStatus)
    {
        TimedBan timer = active(store());
        FailingStore restarted = store();
        noCalls(restarted, GROUP_B);
        assertEquals(timer.recordId, restarted.getTimedBan(TARGET).recordId);
        assertEquals(timer.recordId, store().getTimedBan(TARGET).recordId);
        assertTrue(restarted.timedBanReviewSummary(GROUP_B).contains("1 for another group"));
        assertNull(restarted.reservePending(GroupAuditType.USER_BAN, TARGET, ACTOR, GROUP_B));
    }

    @Test void legacyTimerIsRetainedUnboundAndSurfacedForReview() throws Exception
    {
        Files.writeString(this.temp.resolve("pending.json"), "{\"timedBans\":{\"" + TARGET
            + "\":{\"expiresAt\":1,\"actorUserId\":\"" + ACTOR + "\"}}}", StandardCharsets.UTF_8);
        FailingStore restarted = store();
        noCalls(restarted, GROUP_A);
        noCalls(restarted, GROUP_B);
        assertNull(restarted.getTimedBan(TARGET).groupId);
        assertNull(restarted.getTimedBan(TARGET).recordId);
        assertTrue(restarted.timedBanReviewSummary(GROUP_A).contains("1 legacy/unbound"));
        assertNull(restarted.reservePending(GroupAuditType.USER_BAN, TARGET, ACTOR, GROUP_A));
        assertNull(store().getTimedBan(TARGET).groupId);
    }

    @Test void failedPreparationRefusesTheBanAndDoesNotPromiseATimer()
    {
        FailingStore store = store();
        store.failNext(1);
        assertThrows(IllegalStateException.class, () -> store.prepareTimedBan(GROUP_A, TARGET, 2000L, ACTOR));
        assertNull(store.getTimedBan(TARGET));
        assertNull(store().getTimedBan(TARGET));
    }

    @Test void failedActivationRetainsIntentAndCanRetryInTheSameProcess()
    {
        FailingStore store = store();
        TimedBan timer = store.prepareTimedBan(GROUP_A, TARGET, 2000L, ACTOR);
        store.failNext(1);
        assertFalse(store.activateTimedBan(TARGET, timer, java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock.get()), java.time.ZoneOffset.UTC), ACTOR));
        assertEquals(TimedBanState.PREPARING, store.getTimedBan(TARGET).state);
        assertEquals(TimedBanState.PREPARING, store().getTimedBan(TARGET).state);
        noCalls(store, GROUP_B);
        assertEquals(TimedBanState.PREPARING, store.getTimedBan(TARGET).state);
        noCalls(store, GROUP_A); // The restored deadline is still in the future.
        assertTrue(store().getTimedBan(TARGET).isActiveFor(GROUP_A));
    }

    @Test void restartAfterFailedActivationQuarantinesTheUnconfirmedIntent()
    {
        FailingStore first = store();
        TimedBan timer = first.prepareTimedBan(GROUP_A, TARGET, 2000L, ACTOR);
        first.failNext(1);
        assertFalse(first.activateTimedBan(TARGET, timer, java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock.get()), java.time.ZoneOffset.UTC), ACTOR));
        this.clock.set(3000L);
        FailingStore restarted = store();
        noCalls(restarted, GROUP_A);
        assertEquals(TimedBanState.PREPARING, restarted.getTimedBan(TARGET).state);
        assertTrue(restarted.timedBanReviewSummary(GROUP_A).contains("1 unconfirmed"));
    }

    @Test void failedWriteAheadMarkerSendsNoUnbanAndRemainsRetryable()
    {
        FailingStore store = store();
        active(store);
        store.failNext(2); // Reservation succeeds; write-ahead submission marker fails.
        AtomicInteger calls = new AtomicInteger();
        process(store, GroupModerationResult.SUCCEEDED, calls);
        assertEquals(0, calls.get());
        FailingStore restarted = store();
        assertTrue(restarted.getTimedBan(TARGET).isActiveFor(GROUP_A));
        process(restarted, GroupModerationResult.SUCCEEDED, calls);
        assertEquals(1, calls.get());
    }

    @Test void failedCleanupCannotReplayAfterRestartAndRetriesDeletion()
    {
        FailingStore store = store();
        active(store);
        store.failNext(4); // Reservation, submission marker, tombstone, deletion.
        AtomicInteger calls = new AtomicInteger();
        process(store, GroupModerationResult.SUCCEEDED, calls);
        assertEquals(1, calls.get());
        assertEquals(TimedBanState.RETIRED, store.getTimedBan(TARGET).state);
        FailingStore restarted = store();
        assertEquals(TimedBanState.RETIRED, restarted.getTimedBan(TARGET).state);
        noCalls(restarted, GROUP_B);
        assertNotNull(restarted.getTimedBan(TARGET));
        noCalls(restarted, GROUP_A);
        assertNull(restarted.getTimedBan(TARGET));
        assertNull(store().getTimedBan(TARGET));
    }

    @Test void failedTombstoneAfterUnbanRetainsNonReplayableIntent()
    {
        FailingStore store = store();
        active(store);
        store.failNext(3);
        AtomicInteger calls = new AtomicInteger();
        process(store, GroupModerationResult.SUCCEEDED, calls);
        assertEquals(1, calls.get());
        FailingStore restarted = store();
        noCalls(restarted, GROUP_A);
        assertEquals(TimedBanState.UNBAN_SUBMITTED, restarted.getTimedBan(TARGET).state);
        noCalls(store, GROUP_A); // This process remembers the confirmed result and retries cleanup.
        assertNull(store.getTimedBan(TARGET));
        assertNull(store().getTimedBan(TARGET));
    }

    @Test void removingAnActiveTimerPersistsATombstoneBeforeDeletingIt()
    {
        FailingStore store = store();
        TimedBan timer = active(store);
        store.failNext(2);
        assertFalse(store.removeTimedBan(TARGET, timer));
        FailingStore restarted = store();
        assertEquals(TimedBanState.RETIRED, restarted.getTimedBan(TARGET).state);
        noCalls(restarted, GROUP_A);
        assertNull(restarted.getTimedBan(TARGET));
    }

    @Test void failedInitialCancellationWriteRefusesCancellationAndRetainsTheOriginalSchedule()
    {
        FailingStore store = store();
        TimedBan original = active(store);
        store.failAll = true;
        assertFalse(store.removeTimedBan(TARGET, original), "A cancellation must never be acknowledged without a durable marker");
        FailingStore restarted = store();
        assertEquals(original.recordId, restarted.getTimedBan(TARGET).recordId);
        assertTrue(restarted.getTimedBan(TARGET).isActiveFor(GROUP_A));
        store.failAll = false;
        noCalls(store, GROUP_A); // The live process retries the known cancellation before any unban.
        assertNull(store().getTimedBan(TARGET));
    }

    @Test void nonBannedTargetCleanupFailureCannotReactivateAnOldTimer()
    {
        FailingStore store = store();
        active(store);
        store.failNext(3); // Retirement marker, tombstone, deletion.
        ScarletTimedBanProcessor.process(store, () -> GROUP_A, this.clock::get,
            (g, u) -> GroupMemberStatus.MEMBER, (g, u) -> { fail("Must not unban a member"); return null; });
        FailingStore restarted = store();
        assertEquals(TimedBanState.RETIRED, restarted.getTimedBan(TARGET).state);
        noCalls(restarted, GROUP_A);
        assertNull(restarted.getTimedBan(TARGET));
    }

    @Test void unknownUnbanOutcomeIsNeverReplayedAfterRestart()
    {
        FailingStore store = store();
        active(store);
        AtomicInteger calls = new AtomicInteger();
        process(store, GroupModerationResult.UNKNOWN, calls);
        assertEquals(1, calls.get());
        this.clock.addAndGet(ScarletPendingModActions.PENDING_TIMEOUT_MILLIS + 1L);
        FailingStore restarted = store();
        noCalls(restarted, GROUP_A);
        assertEquals(TimedBanState.UNBAN_SUBMITTED, restarted.getTimedBan(TARGET).state);
        assertNull(restarted.reservePending(GroupAuditType.USER_BAN, TARGET, ACTOR, GROUP_A));
    }

    @Test void definiteRejectionCanRetryButFailedRetrySaveCannotReplayBlindly()
    {
        FailingStore store = store();
        active(store);
        store.failNext(3); // Restoring ACTIVE after a definite rejection.
        AtomicInteger calls = new AtomicInteger();
        process(store, GroupModerationResult.REJECTED, calls);
        FailingStore restarted = store();
        noCalls(restarted, GROUP_A);
        assertEquals(TimedBanState.UNBAN_SUBMITTED, restarted.getTimedBan(TARGET).state);
        process(store, GroupModerationResult.SUCCEEDED, calls);
        assertEquals(2, calls.get());
        assertNull(store().getTimedBan(TARGET));
    }

    @Test void groupChangeDuringMembershipCheckRetainsTimerWithoutUnban()
    {
        FailingStore store = store();
        TimedBan timer = active(store);
        AtomicReference<String> group = new AtomicReference<>(GROUP_A);
        ScarletTimedBanProcessor.process(store, group::get, this.clock::get,
            (g, u) -> { group.set(GROUP_B); return GroupMemberStatus.BANNED; },
            (g, u) -> { fail("Group changed before submission"); return null; });
        assertEquals(timer.recordId, store().getTimedBan(TARGET).recordId);
        assertTrue(store.getTimedBan(TARGET).isActiveFor(GROUP_A));
    }

    @Test void groupChangeDuringUnbanDoesNotRemoveOriginalGroupRecord()
    {
        FailingStore store = store();
        TimedBan timer = active(store);
        AtomicReference<String> group = new AtomicReference<>(GROUP_A);
        ScarletTimedBanProcessor.process(store, group::get, this.clock::get,
            (g, u) -> GroupMemberStatus.BANNED,
            (g, u, t) -> new ScarletTimedBanContinuity.Proof(ScarletTimedBanContinuity.Verdict.VERIFIED, "gaud_synthetic_original"),
            (g, u) -> { group.set(GROUP_B); return GroupModerationResult.SUCCEEDED; });
        assertEquals(timer.recordId, store().getTimedBan(TARGET).recordId);
        assertEquals(TimedBanState.UNBAN_SUBMITTED, store.getTimedBan(TARGET).state);
        noCalls(store, GROUP_B);
    }

    @Test void staleSweepCannotUnbanOrDeleteAReplacementTimer()
    {
        FailingStore store = store();
        TimedBan old = active(store);
        ScarletTimedBanProcessor.process(store, () -> GROUP_A, this.clock::get,
            (g, u) -> {
                assertTrue(store.removeTimedBan(TARGET, old));
                TimedBan replacement = store.prepareTimedBan(GROUP_A, TARGET, clock.get() + 5000L, ACTOR);
                assertTrue(store.activateTimedBan(TARGET, replacement, java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock.get()), java.time.ZoneOffset.UTC), ACTOR));
                return GroupMemberStatus.BANNED;
            }, (g, u) -> { fail("Old sweep must not act on replacement"); return null; });
        assertNotEquals(old.recordId, store.getTimedBan(TARGET).recordId);
        assertTrue(store.getTimedBan(TARGET).isActiveFor(GROUP_A));
        assertFalse(store.removeTimedBan(TARGET, old));
    }

    @Test void pendingNewBanDurablyRetiresOldTimerAndOldSnapshotCannotClaimIt()
    {
        FailingStore store = store();
        TimedBan old = active(store);
        assertNotNull(store.reservePending(GroupAuditType.USER_BAN, TARGET, ACTOR, GROUP_A));
        assertNull(store().getTimedBan(TARGET));
        assertFalse(store.beginTimedUnban(TARGET, old));
        noCalls(store, GROUP_A);
    }

    @Test void failedNewBanReservationPreservesOldTimerAndRefusesTheNewAction()
    {
        FailingStore store = store();
        TimedBan old = active(store);
        store.failNext(1);
        assertThrows(IllegalStateException.class,
            () -> store.reservePending(GroupAuditType.USER_BAN, TARGET, ACTOR, GROUP_A));
        assertEquals(old.recordId, store().getTimedBan(TARGET).recordId);
        assertFalse(store.hasPending(GroupAuditType.USER_BAN, TARGET));
    }

    @Test void concurrentNewBanWaitsForExpirySubmissionAndCannotOvertakeIt() throws Exception
    {
        FailingStore store = store();
        active(store);
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1), contender = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> sweep = workers.submit(() -> ScarletTimedBanProcessor.process(store, () -> GROUP_A, clock::get,
                (g, u) -> GroupMemberStatus.BANNED,
                (g, u, t) -> new ScarletTimedBanContinuity.Proof(ScarletTimedBanContinuity.Verdict.VERIFIED, "gaud_synthetic_original"),
                (g, u) -> {
                    entered.countDown();
                    try { assertTrue(finish.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { throw new AssertionError(ex); }
                    return GroupModerationResult.SUCCEEDED;
                }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> ban = workers.submit(() -> {
                contender.countDown();
                return store.reservePending(GroupAuditType.USER_BAN, TARGET, ACTOR, GROUP_A);
            });
            assertTrue(contender.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> ban.get(100, TimeUnit.MILLISECONDS));
            finish.countDown();
            sweep.get(5, TimeUnit.SECONDS);
            assertNull(ban.get(5, TimeUnit.SECONDS), "Pending unban still awaits audit confirmation");
            assertNull(store().getTimedBan(TARGET));
        }
        finally
        {
            finish.countDown();
            workers.shutdownNow();
        }
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;

/** Synthetic callbacks only: no game processes, game files, accounts or network calls. */
class ScarletStartupInstanceJoinTest
{
    private static final String GROUP = "grp_11111111-1111-1111-1111-111111111111";
    private static final String OTHER_GROUP = "grp_22222222-2222-2222-2222-222222222222";
    private static final String WORLD = "wrld_11111111-1111-1111-1111-111111111111";
    private static final String A = WORLD + ":1~group(" + GROUP + ")~groupAccessType(members)~region(us)";
    private static final String B = WORLD + ":2~group(" + GROUP + ")~groupAccessType(plus)~ageGate~region(eu)";
    private static final String C = WORLD + ":3~group(" + GROUP + ")~groupAccessType(public)~region(jp)";
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-23T12:00:00Z");
    private static final long NOW = START.plusSeconds(10).toInstant().toEpochMilli();
    private static final String[] FAILURE_STAGES = {
        "checking the VRChat client state", "reading active group instances", "checking instance creation history",
        "rechecking active group instances", "rechecking the VRChat client state", "preparing the secure join and launch"
    };

    private static ScarletStartupInstanceJoin helper()
    {
        return new ScarletStartupInstanceJoin(GROUP, START);
    }

    private static GroupAuditLogEntryUnknown entry(String location, OffsetDateTime when)
    {
        return new GroupAuditLogEntryUnknown().groupId(GROUP).eventType("group.instance.create")
            .targetId(location).createdAt(when);
    }

    private static ScarletStartupInstanceJoin.AuditPage page(boolean more, GroupAuditLogEntryUnknown... entries)
    {
        return new ScarletStartupInstanceJoin.AuditPage(Arrays.asList(entries), more);
    }

    static class Fake implements ScarletStartupInstanceJoin.Environment
    {
        boolean allowed = true, running, runningOnRecheck;
        List<String> active = Collections.singletonList(A), rechecked;
        final List<ScarletStartupInstanceJoin.AuditPage> pages = new ArrayList<>();
        final List<String> launched = new ArrayList<>(), logs = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        int activeCalls, runningCalls, pageCalls;
        long clock = NOW;
        Exception activeFailure, recheckFailure, runningFailure, runningRecheckFailure, pageFailure, launchFailure;
        Runnable afterActive = () -> { }, afterPage = () -> { };

        @Override public boolean allowed() { return this.allowed; }
        @Override public boolean clientRunning() throws Exception
        {
            if (this.runningFailure != null) throw this.runningFailure;
            if (this.runningCalls > 0 && this.runningRecheckFailure != null) throw this.runningRecheckFailure;
            return this.running || (++this.runningCalls > 1 && this.runningOnRecheck);
        }
        @Override public List<String> activeLocations() throws Exception
        {
            this.activeCalls++;
            if (this.activeCalls > 1 && this.recheckFailure != null) throw this.recheckFailure;
            if (this.activeFailure != null) throw this.activeFailure;
            this.afterActive.run();
            return this.activeCalls > 1 && this.rechecked != null ? this.rechecked : this.active;
        }
        @Override public ScarletStartupInstanceJoin.AuditPage creationPage(OffsetDateTime from, OffsetDateTime to, int offset) throws Exception
        {
            assertEquals(30L, java.time.Duration.between(from, to).toDays());
            assertEquals(START.plusSeconds(10), to);
            this.offsets.add(offset);
            this.pageCalls++;
            if (this.pageFailure != null) throw this.pageFailure;
            this.afterPage.run();
            return this.pages.size() < this.pageCalls ? null : this.pages.get(this.pageCalls - 1);
        }
        @Override public void launch(String location) throws Exception
        {
            if (this.launchFailure != null) throw this.launchFailure;
            this.launched.add(location);
        }
        @Override public void log(String message) { this.logs.add(message); }
        @Override public long nowMillis() { return this.clock; }
    }

    @Test void oneActiveInstanceUsesNoAuditRequestsAndLaunchesOnlyOnce()
    {
        ScarletStartupInstanceJoin join = helper();
        Fake env = new Fake();
        join.attempt(env, NOW);
        join.attempt(env, NOW + 120_000L);
        assertEquals(Collections.singletonList(A), env.launched);
        assertEquals(2, env.activeCalls);
        assertEquals(0, env.pageCalls);
        assertFalse(join.isPending());
        assertFalse(join.isDue(NOW + 120_000L));
    }

    @Test void duplicateLocationsAreOneInstance()
    {
        Fake env = new Fake();
        env.active = Arrays.asList(A, A, A);
        helper().attempt(env, NOW);
        assertEquals(Collections.singletonList(A), env.launched);
        assertEquals(0, env.pageCalls);
    }

    @Test void zeroActiveInstancesCompletesWithoutLaunchOrAudit()
    {
        ScarletStartupInstanceJoin join = helper();
        Fake env = new Fake();
        env.active = Collections.emptyList();
        join.attempt(env, NOW);
        assertFalse(join.isPending());
        assertEquals(1, env.activeCalls);
        assertEquals(0, env.pageCalls);
        assertTrue(env.launched.isEmpty());
    }

    @Test void newestUsesAuditCreationTimeNotListOrderOrInstanceNumber()
    {
        Fake env = new Fake();
        env.active = Arrays.asList(C, A, B);
        env.pages.add(page(false, entry(C, START.minusHours(3)), entry(A, START.minusMinutes(1)), entry(B, START.minusHours(2))));
        helper().attempt(env, NOW);
        assertEquals(Collections.singletonList(A), env.launched);
    }

    @Test void readsCompleteBoundedPagesAndChoosesLatestDuplicateCreationTime()
    {
        Fake env = new Fake();
        env.active = Arrays.asList(B, A);
        env.pages.add(page(true, entry(A, START.minusHours(2)), entry(B, START.minusHours(1))));
        env.pages.add(page(false, entry(A, START.minusMinutes(1))));
        helper().attempt(env, NOW);
        assertEquals(Arrays.asList(0, 100), env.offsets);
        assertEquals(Collections.singletonList(A), env.launched);
    }

    @Test void equalCreationTimesHaveStableTieBreak()
    {
        Fake env = new Fake();
        env.active = Arrays.asList(B, A);
        env.pages.add(page(false, entry(B, START), entry(A, START)));
        helper().attempt(env, NOW);
        assertEquals(Collections.singletonList(A), env.launched);
    }

    @Test void missingTimestampForAnyActiveInstanceNeverGuessesNewest()
    {
        ScarletStartupInstanceJoin join = helper();
        Fake env = new Fake();
        env.active = Arrays.asList(A, B);
        env.pages.add(page(false, entry(A, START)));
        join.attempt(env, NOW);
        assertTrue(join.isPending());
        assertTrue(env.launched.isEmpty());
    }

    @Test void futureOutsideWindowWrongGroupWrongTypeAndNullEntriesCannotProveNewest()
    {
        List<GroupAuditLogEntryUnknown> invalid = Arrays.asList(null,
            entry(B, null), entry(B, START.plusDays(1)), entry(B, START.minusDays(31)),
            entry(B, START).groupId(OTHER_GROUP), entry(B, START).eventType("group.instance.close"));
        for (GroupAuditLogEntryUnknown bad : invalid)
        {
            Fake env = new Fake();
            env.active = Arrays.asList(A, B);
            env.pages.add(page(false, entry(A, START), bad));
            ScarletStartupInstanceJoin join = helper();
            join.attempt(env, NOW);
            assertTrue(join.isPending());
            assertTrue(env.launched.isEmpty());
        }
    }

    @Test void unrelatedInactiveEntriesDoNotAffectSelection()
    {
        Fake env = new Fake();
        env.active = Arrays.asList(A, B);
        env.pages.add(page(false, entry(A, START.minusHours(2)), entry(B, START.minusHours(1)), entry(C, START)));
        helper().attempt(env, NOW);
        assertEquals(Collections.singletonList(B), env.launched);
    }

    @Test void malformedOrForeignActiveLocationInvalidatesWholeSnapshot()
    {
        for (String invalid : Arrays.asList(null, "", "offline", "private", " " + A,
                WORLD, WORLD + ":1", A.replace(GROUP, OTHER_GROUP),
                "https://vrchat.com/home/launch?worldId=" + WORLD + "&instanceId=1"))
        {
            Fake env = new Fake();
            env.active = Arrays.asList(A, invalid);
            ScarletStartupInstanceJoin join = helper();
            join.attempt(env, NOW);
            assertTrue(join.isPending());
            assertEquals(0, env.pageCalls);
            assertTrue(env.launched.isEmpty());
        }
    }

    @Test void nullListIsFailureNotProofThatNoInstancesExist()
    {
        Fake env = new Fake();
        env.active = null;
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        assertTrue(join.isPending());
        assertTrue(env.launched.isEmpty());
    }

    @Test void failureRetriesOnlyThreeTimesWithCooldownAfterCompletion()
    {
        Fake env = new Fake();
        env.activeFailure = new IOException("synthetic token must not leak");
        env.clock = NOW + 90_000L;
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        assertFalse(join.isDue(NOW + 149_999L));
        assertTrue(join.isDue(NOW + 150_000L));
        join.attempt(env, NOW + 149_999L);
        assertEquals(1, env.activeCalls);
        join.attempt(env, NOW + 150_000L);
        join.attempt(env, NOW + 210_000L);
        join.attempt(env, NOW + 270_000L);
        assertFalse(join.isPending());
        assertEquals(3, env.activeCalls);
        assertTrue(env.logs.stream().noneMatch(text -> text.contains("token")));
        assertTrue(env.logs.get(env.logs.size() - 1).contains("three attempts"));
    }

    private static Fake failingStage(String stage, Exception failure)
    {
        Fake env = new Fake();
        switch (stage)
        {
            case "checking the VRChat client state": env.runningFailure = failure; break;
            case "reading active group instances": env.activeFailure = failure; break;
            case "checking instance creation history":
                env.active = Arrays.asList(A, B);
                env.pageFailure = failure;
                break;
            case "rechecking active group instances": env.recheckFailure = failure; break;
            case "rechecking the VRChat client state": env.runningRecheckFailure = failure; break;
            case "preparing the secure join and launch": env.launchFailure = failure; break;
            default: throw new AssertionError("Unknown synthetic stage");
        }
        return env;
    }

    @Test void safeHttpFailureIdentifiesEveryStartupStageWithoutClaimingJoinSuccess()
    {
        for (String stage : FAILURE_STAGES)
        {
            Fake env = failingStage(stage, ScarletStartupFailure.http(429));
            ScarletStartupInstanceJoin join = helper();
            join.attempt(env, NOW);
            String diagnostic = env.logs.get(env.logs.size() - 1);
            assertTrue(diagnostic.contains(stage), diagnostic);
            assertTrue(diagnostic.contains("HTTP 429"), diagnostic);
            assertTrue(diagnostic.contains("retry"), diagnostic);
            assertFalse(diagnostic.contains(A), diagnostic);
            assertFalse(diagnostic.contains(GROUP), diagnostic);
            assertFalse(diagnostic.contains("successfully joined"), diagnostic);
            assertTrue(env.launched.isEmpty(), stage);
            assertTrue(join.isPending(), stage);
            assertFalse(join.isDue(NOW + 59_999L), stage);
            assertTrue(join.isDue(NOW + 60_000L), stage);
        }
    }

    @Test void rawFailureDetailsRemainPrivateAtEveryStartupStage()
    {
        String secret = "synthetic-private-token https://private.invalid/?shortName=private-token " + A;
        for (String stage : FAILURE_STAGES)
        {
            Fake env = failingStage(stage, new IOException(secret, new IllegalStateException(secret)));
            helper().attempt(env, NOW);
            String diagnostic = env.logs.get(env.logs.size() - 1);
            assertTrue(diagnostic.contains(stage), diagnostic);
            assertFalse(diagnostic.contains("synthetic-private-token"), diagnostic);
            assertFalse(diagnostic.contains("private.invalid"), diagnostic);
            assertFalse(diagnostic.contains("shortName"), diagnostic);
            assertFalse(diagnostic.contains(A), diagnostic);
            assertFalse(diagnostic.contains(GROUP), diagnostic);
            assertFalse(diagnostic.contains("IllegalStateException"), diagnostic);
            assertTrue(env.launched.isEmpty());
        }
    }

    @Test void safeDiagnosticsDoNotChangeThreeAttemptBudgetOrInventPermissionCause()
    {
        Fake env = new Fake();
        env.activeFailure = ScarletStartupFailure.http(403);
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        join.attempt(env, NOW + 59_999L);
        join.attempt(env, NOW + 60_000L);
        join.attempt(env, NOW + 120_000L);
        join.attempt(env, NOW + 180_000L);
        assertEquals(3, env.activeCalls);
        assertFalse(join.isPending());
        assertTrue(env.launched.isEmpty());
        for (String log : env.logs)
        {
            assertTrue(log.contains("HTTP 403"), log);
            assertTrue(log.contains("reading active group instances"), log);
            assertFalse(log.toLowerCase(java.util.Locale.ROOT).contains("missing permission"), log);
            assertFalse(log.toLowerCase(java.util.Locale.ROOT).contains("account banned"), log);
        }
        assertTrue(env.logs.get(2).contains("three attempts"));
    }

    @Test void disabledBeforeStartDoesNoLookupAndCompletes()
    {
        Fake env = new Fake();
        env.allowed = false;
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        assertFalse(join.isPending());
        assertEquals(0, env.activeCalls);
        assertEquals(0, env.runningCalls);
    }

    @Test void toggleOffDuringLookupCancelsWithoutLaunch()
    {
        Fake env = new Fake();
        env.afterActive = () -> env.allowed = false;
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        assertFalse(join.isPending());
        assertEquals(1, env.activeCalls);
        assertTrue(env.launched.isEmpty());
    }

    @Test void toggleOffDuringAuditStopsBeforeFurtherNetworkOrLaunch()
    {
        Fake env = new Fake();
        env.active = Arrays.asList(A, B);
        env.pages.add(page(true, entry(A, START)));
        env.afterPage = () -> env.allowed = false;
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        assertFalse(join.isPending());
        assertEquals(1, env.pageCalls);
        assertEquals(1, env.activeCalls);
        assertTrue(env.launched.isEmpty());
    }

    @Test void explicitCancellationDuringLookupPreventsLaunch()
    {
        Fake env = new Fake();
        ScarletStartupInstanceJoin join = helper();
        env.afterActive = join::cancel;
        join.attempt(env, NOW);
        assertFalse(join.isPending());
        assertTrue(env.launched.isEmpty());
    }

    @Test void alreadyRunningClientDoesNotQueryOrLaunch()
    {
        Fake env = new Fake();
        env.running = true;
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        assertFalse(join.isPending());
        assertEquals(0, env.activeCalls);
        assertTrue(env.launched.isEmpty());
    }

    @Test void clientStartedDuringLookupIsLeftAlone()
    {
        Fake env = new Fake();
        env.runningOnRecheck = true;
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        assertFalse(join.isPending());
        assertEquals(2, env.activeCalls);
        assertTrue(env.launched.isEmpty());
    }

    @Test void changedActiveSetRetriesInsteadOfJoiningStaleSelection()
    {
        for (List<String> changed : Arrays.asList(Collections.<String>emptyList(), Arrays.asList(A, B), Collections.singletonList(B)))
        {
            Fake env = new Fake();
            env.rechecked = changed;
            ScarletStartupInstanceJoin join = helper();
            join.attempt(env, NOW);
            assertTrue(join.isPending());
            assertTrue(env.launched.isEmpty());
        }
    }

    @Test void pageLimitDoesNotAcceptPartialHistoryEvenWhenAllLocationsAppear()
    {
        Fake env = new Fake();
        env.active = Arrays.asList(A, B);
        for (int i = 0; i < 6; i++) env.pages.add(page(true, entry(A, START), entry(B, START)));
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        assertEquals(5, env.pageCalls);
        assertTrue(join.isPending());
        assertTrue(env.launched.isEmpty());
    }

    @Test void emptyContinuingPageIsIncompleteRatherThanInfiniteLoop()
    {
        Fake env = new Fake();
        env.active = Arrays.asList(A, B);
        env.pages.add(page(true));
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        assertEquals(1, env.pageCalls);
        assertTrue(join.isPending());
    }

    @Test void missingOrOversizedPageIsRejected()
    {
        for (ScarletStartupInstanceJoin.AuditPage malformed : Arrays.asList(null,
                new ScarletStartupInstanceJoin.AuditPage(null, false),
                new ScarletStartupInstanceJoin.AuditPage(Collections.nCopies(101, entry(A, START)), false)))
        {
            Fake env = new Fake();
            env.active = Arrays.asList(A, B);
            env.pages.add(malformed);
            ScarletStartupInstanceJoin join = helper();
            join.attempt(env, NOW);
            assertTrue(join.isPending());
            assertTrue(env.launched.isEmpty());
        }
    }

    @Test void catchupAuditNeverRedirectsStartupChoiceButLaterCreationCanFollow()
    {
        ScarletStartupInstanceJoin join = helper();
        assertFalse(join.shouldFollowCreationAudit(START.minusSeconds(1)));
        assertFalse(join.shouldFollowCreationAudit(START.plusSeconds(30)));
        join.attempt(new Fake(), NOW);
        assertFalse(join.shouldFollowCreationAudit(null));
        assertFalse(join.shouldFollowCreationAudit(START.minusSeconds(1)));
        assertFalse(join.shouldFollowCreationAudit(START.plusSeconds(10)));
        assertTrue(join.shouldFollowCreationAudit(START.plusSeconds(11)));
    }

    @Test void pendingStateStillAllowsRecognizingNewCreationsForDeferredFollow()
    {
        ScarletStartupInstanceJoin join = helper();
        assertFalse(join.isAfterCutoff(null));
        assertFalse(join.isAfterCutoff(START));
        assertTrue(join.isAfterCutoff(START.plusSeconds(1)));
        assertFalse(join.shouldFollowCreationAudit(START.plusSeconds(1)));
        Fake env = new Fake();
        env.active = null;
        join.attempt(env, NOW);
        assertTrue(join.isPending());
        assertFalse(join.isAfterCutoff(START.plusSeconds(10)));
        assertTrue(join.isAfterCutoff(START.plusSeconds(11)));
        assertFalse(join.shouldFollowCreationAudit(START.plusSeconds(11)));
    }

    @Test void finalFailureAdvancesAuditWatermarkToLastAttempt()
    {
        Fake env = new Fake();
        env.active = null;
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        join.attempt(env, NOW + 60_000L);
        join.attempt(env, NOW + 120_000L);
        assertFalse(join.isPending());
        assertFalse(join.shouldFollowCreationAudit(START.plusSeconds(130)));
        assertTrue(join.shouldFollowCreationAudit(START.plusSeconds(131)));
    }

    @Test void launchFailureIsNotClaimedAsSuccessfulAndCanRetry()
    {
        Fake env = new Fake();
        env.launchFailure = new IOException("synthetic");
        ScarletStartupInstanceJoin join = helper();
        join.attempt(env, NOW);
        assertTrue(join.isPending());
        env.launchFailure = null;
        join.attempt(env, NOW + 60_000L);
        assertEquals(Collections.singletonList(A), env.launched);
        assertFalse(join.isPending());
    }

    @Test void interruptionCancelsAndPreservesInterruptFlag()
    {
        Fake env = new Fake();
        env.activeFailure = new InterruptedException();
        ScarletStartupInstanceJoin join = helper();
        try
        {
            join.attempt(env, NOW);
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(join.isPending());
            assertTrue(env.launched.isEmpty());
        }
        finally { Thread.interrupted(); }
    }

    @Test void concurrentAttemptDoesNotDuplicateCallsOrBlockCancellation() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        Fake env = new Fake()
        {
            @Override public List<String> activeLocations() throws Exception
            {
                requests.incrementAndGet();
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return Collections.singletonList(A);
            }
        };
        ScarletStartupInstanceJoin join = helper();
        Thread worker = new Thread(() -> join.attempt(env, NOW));
        worker.start();
        try
        {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(join.isDue(NOW));
            join.attempt(env, NOW);
            join.cancel();
            assertFalse(join.isPending());
        }
        finally { release.countDown(); worker.join(5_000L); }
        assertFalse(worker.isAlive());
        assertEquals(1, requests.get());
        assertTrue(env.launched.isEmpty());
    }
}

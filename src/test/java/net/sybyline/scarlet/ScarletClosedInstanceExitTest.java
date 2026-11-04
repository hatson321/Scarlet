package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure synthetic lifecycle observations; never accesses or closes a real game. */
class ScarletClosedInstanceExitTest
{
    static final String GROUP = "grp_00000000-0000-0000-0000-000000000001";
    static final String OTHER_GROUP = "grp_00000000-0000-0000-0000-000000000002";
    static final String SELF = "usr_00000000-0000-0000-0000-000000000001";
    static final String OTHER = "usr_00000000-0000-0000-0000-000000000002";
    static final String LOCATION = "wrld_00000000-0000-0000-0000-000000000001:12345~group(" + GROUP + ")~groupAccessType(members)";
    static final Instant START = Instant.parse("2026-10-12T12:00:00Z"), CLOSED = START.plusSeconds(10), OBSERVED = START.plusSeconds(20);
    static final long WAIT = ScarletClosedInstanceExit.GRACE_NANOS;
    final ScarletClosedInstanceExit policy = new ScarletClosedInstanceExit();
    long generation;

    void start()
    {
        policy.configure(true, GROUP);
        generation = policy.beginSession(LOCATION, START);
        track(SELF);
    }
    void track(String... users) { policy.updateTracking(generation, SELF, Arrays.asList(users), true); }
    void close() { assertTrue(policy.confirmClosed(GROUP, LOCATION, CLOSED, OBSERVED)); }
    ScarletClosedInstanceExit.Attempt ready()
    {
        assertNull(policy.poll(0));
        ScarletClosedInstanceExit.Attempt attempt = policy.poll(WAIT);
        assertNotNull(attempt);
        return attempt;
    }
    void blocked()
    {
        assertNull(policy.poll(0));
        assertNull(policy.poll(WAIT * 10));
    }

    @Test void disabledByDefaultCannotStartOrClose()
    {
        generation = policy.beginSession(LOCATION, START);
        track(SELF);
        assertFalse(policy.confirmClosed(GROUP, LOCATION, CLOSED, OBSERVED));
        blocked();
    }

    @Test void openAndSoleSelfNeverExits() { start(); blocked(); }
    @Test void closedButOccupiedNeverExits() { start(); track(SELF, OTHER); close(); blocked(); }
    @Test void emptyRosterIsUnknownNotAnEmptyInstance() { start(); close(); track(); blocked(); }
    @Test void missingSelfNeverExits() { start(); close(); track(OTHER); blocked(); }

    @Test void closedAndSoleSelfWaitsFullGraceThenClosesExactlyOnce()
    {
        start(); close();
        assertNull(policy.poll(100));
        assertNull(policy.poll(100 + WAIT - 1));
        ScarletClosedInstanceExit.Attempt attempt = policy.poll(100 + WAIT);
        assertNotNull(attempt);
        assertEquals(LOCATION, attempt.location);
        assertEquals(GROUP, attempt.groupId);
        assertEquals(SELF, attempt.selfUserId);
        assertEquals(START, attempt.sessionStartedAt);
        AtomicInteger closes = new AtomicInteger();
        assertTrue(policy.commit(attempt, 100 + WAIT, closes::incrementAndGet));
        assertFalse(policy.commit(attempt, 100 + WAIT, closes::incrementAndGet));
        assertNull(policy.poll(100 + WAIT * 2));
        assertEquals(1, closes.get());
    }

    @Test void repeatedTrackingAndClosureDoNotRestartGrace()
    {
        start(); close();
        assertNull(policy.poll(0));
        track(SELF); close();
        policy.configure(true, GROUP);
        assertNotNull(policy.poll(WAIT));
    }

    @Test void duplicateAndLaterClosureNeverRepeatAnAttempt()
    {
        start(); close();
        assertTrue(policy.commit(ready(), WAIT, () -> {}));
        close();
        assertTrue(policy.confirmClosed(GROUP, LOCATION, CLOSED.plusSeconds(1), OBSERVED));
        track(SELF);
        blocked();
    }

    @Test void callbackExceptionStillConsumesAttempt()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt attempt = ready();
        assertThrows(IllegalStateException.class, () -> policy.commit(attempt, WAIT, () -> { throw new IllegalStateException("Synthetic denied request"); }));
        assertNull(policy.poll(WAIT * 2));
        assertFalse(policy.commit(attempt, WAIT * 2, () -> fail("No retry")));
    }

    @Test void joinCancelsPreparedAttemptAndLeaveStartsNewGrace()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt attempt = ready();
        track(SELF, OTHER);
        assertFalse(policy.commit(attempt, WAIT, () -> fail("Roster changed")));
        blocked();
        track(SELF);
        assertNull(policy.poll(WAIT * 11));
        assertNull(policy.poll(WAIT * 12 - 1));
        assertNotNull(policy.poll(WAIT * 12));
    }

    @Test void leaveThenJoinEvenBeforePollingInvalidatesOldProposal()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt attempt = ready();
        track(SELF, OTHER); track(SELF);
        assertFalse(policy.commit(attempt, WAIT * 2, () -> fail("New eligibility episode")));
        assertNull(policy.poll(WAIT * 2));
    }

    @Test void unavailableTrackingDiscardsClosureProof()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt attempt = ready();
        policy.updateTracking(generation, SELF, Collections.singleton(SELF), false);
        assertFalse(policy.commit(attempt, WAIT, () -> fail("Not live")));
        track(SELF); blocked();
        close(); assertNotNull(ready());
    }

    @Test void initialLogCatchupCannotSupplyClosureProof()
    {
        start();
        policy.updateTracking(generation, SELF, Collections.singleton(SELF), false);
        assertFalse(policy.confirmClosed(GROUP, LOCATION, CLOSED, OBSERVED));
        track(SELF); blocked();
    }

    @Test void accountChangeDiscardsClosureProof()
    {
        start(); close();
        policy.updateTracking(generation, OTHER, Collections.singleton(OTHER), true);
        blocked();
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"unknown", "usr_invalid", "usr_00000000-0000-0000-0000-000000000001\n"})
    void invalidSelfBlocks(String self)
    {
        start(); close();
        policy.updateTracking(generation, self, Collections.singleton(SELF), true);
        blocked();
    }

    @Test void malformedOrDuplicateRosterBlocks()
    {
        start(); close(); track(SELF, null); blocked();
        track(SELF); close(); track(SELF, "unknown"); blocked();
        track(SELF); close(); track(SELF, SELF); blocked();
        track(SELF); close(); policy.updateTracking(generation, SELF, null, true); blocked();
    }

    @Test void wrongGroupOrLocationCannotClose()
    {
        start();
        assertFalse(policy.confirmClosed(OTHER_GROUP, LOCATION, CLOSED, OBSERVED));
        assertFalse(policy.confirmClosed(GROUP, LOCATION.replace(":12345", ":54321"), CLOSED, OBSERVED));
        assertFalse(policy.confirmClosed(null, LOCATION, CLOSED, OBSERVED));
        assertFalse(policy.confirmClosed(GROUP, null, CLOSED, OBSERVED));
        blocked();
    }

    @Test void oldFutureAndMissingClosureTimesFailClosed()
    {
        start();
        assertFalse(policy.confirmClosed(GROUP, LOCATION, START.minusNanos(1), OBSERVED));
        assertFalse(policy.confirmClosed(GROUP, LOCATION, OBSERVED.plusNanos(1), OBSERVED));
        assertFalse(policy.confirmClosed(GROUP, LOCATION, null, OBSERVED));
        assertFalse(policy.confirmClosed(GROUP, LOCATION, CLOSED, null));
        blocked();
    }

    @Test void rejoinSameLocationRejectsPreviousSessionClosureAndOldRoster()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt old = ready();
        long previous = generation;
        generation = policy.beginSession(LOCATION, OBSERVED);
        policy.updateTracking(previous, SELF, Collections.singleton(SELF), true);
        assertFalse(policy.commit(old, WAIT, () -> fail("Old session")));
        track(SELF);
        assertFalse(policy.confirmClosed(GROUP, LOCATION, CLOSED, OBSERVED));
        blocked();
        assertTrue(policy.confirmClosed(GROUP, LOCATION, OBSERVED.plusSeconds(1), OBSERVED.plusSeconds(2)));
        assertNotNull(ready());
    }

    @Test void differentWorldAndResetInvalidateReadyAttempt()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt old = ready();
        generation = policy.beginSession(LOCATION.replace(":12345", ":777"), START);
        track(SELF);
        assertFalse(policy.commit(old, WAIT, () -> fail("Wrong world")));
        assertFalse(policy.confirmClosed(GROUP, LOCATION, CLOSED, OBSERVED));
        blocked();
        policy.reset();
        assertNotEquals(generation, policy.generation());
        blocked();
    }

    @Test void disableAndReenableDoesNotRecoverPreviousProof()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt old = ready();
        policy.configure(false, GROUP);
        assertFalse(policy.commit(old, WAIT, () -> fail("Disabled")));
        policy.configure(true, GROUP); track(SELF); blocked();
    }

    @Test void configuredGroupChangeResetsSession()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt old = ready();
        policy.configure(true, OTHER_GROUP);
        assertFalse(policy.commit(old, WAIT, () -> fail("Group changed")));
        assertFalse(policy.confirmClosed(GROUP, LOCATION, CLOSED, OBSERVED));
        blocked();
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"group", "grp_00000000-0000-0000-0000-000000000001\n"})
    void invalidConfiguredGroupCannotEnable(String group)
    {
        policy.configure(true, group);
        generation = policy.beginSession(LOCATION, START);
        track(SELF); blocked();
    }

    @Test void malformedUngroupedOtherGroupAndAmbiguousLocationsCannotStart()
    {
        policy.configure(true, GROUP);
        for (String location : Arrays.asList(null, "", "invalid", "wrld_00000000-0000-0000-0000-000000000001:1",
            LOCATION.replace(GROUP, OTHER_GROUP), LOCATION + "~group(" + OTHER_GROUP + ")", LOCATION + "\n"))
        {
            generation = policy.beginSession(location, START);
            track(SELF);
            assertFalse(policy.confirmClosed(GROUP, location, CLOSED, OBSERVED));
            blocked();
        }
    }

    @Test void missingSessionTimeCannotStart()
    {
        policy.configure(true, GROUP);
        generation = policy.beginSession(LOCATION, null);
        track(SELF); assertFalse(policy.confirmClosed(GROUP, LOCATION, CLOSED, OBSERVED)); blocked();
    }

    @Test void newerOrSameTimeCreateInvalidatesClosure()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt attempt = ready();
        policy.invalidateReopened(GROUP, LOCATION, CLOSED, OBSERVED);
        assertFalse(policy.commit(attempt, WAIT, () -> fail("Ambiguous same-time creation")));
        blocked();
        assertFalse(policy.confirmClosed(GROUP, LOCATION, CLOSED, OBSERVED));
        assertTrue(policy.confirmClosed(GROUP, LOCATION, CLOSED.plusSeconds(1), OBSERVED));
        policy.invalidateReopened(GROUP, LOCATION, CLOSED.plusSeconds(2), OBSERVED);
        blocked();
    }

    @Test void delayedCloseBeforeKnownCreateCannotAuthorize()
    {
        start();
        policy.invalidateReopened(GROUP, LOCATION, CLOSED.plusSeconds(1), OBSERVED);
        assertFalse(policy.confirmClosed(GROUP, LOCATION, CLOSED, OBSERVED));
        blocked();
    }

    @Test void earlierOrUnrelatedCreateDoesNotEraseLaterClosure()
    {
        start(); close();
        assertNull(policy.poll(0));
        policy.invalidateReopened(GROUP, LOCATION, START, OBSERVED);
        policy.invalidateReopened(OTHER_GROUP, LOCATION, OBSERVED, OBSERVED);
        policy.invalidateReopened(GROUP, LOCATION, OBSERVED.plusSeconds(1), OBSERVED);
        assertNotNull(policy.poll(WAIT));
    }

    @Test void commitCannotBypassGraceOrUseMissingProposal()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt attempt = ready();
        assertFalse(policy.commit(attempt, WAIT - 1, () -> fail("Too soon")));
        assertFalse(policy.commit(null, WAIT, () -> fail("Missing proposal")));
        assertFalse(policy.commit(attempt, WAIT, null));
        assertTrue(policy.commit(attempt, WAIT, () -> {}));
    }

    @Test void monotonicClockWrapIsSafe()
    {
        start(); close();
        long initial = Long.MAX_VALUE - WAIT / 2;
        assertNull(policy.poll(initial));
        assertNull(policy.poll(initial + WAIT - 1));
        assertNotNull(policy.poll(initial + WAIT));
    }

    @Test void proposalFromAnotherPolicyCannotAuthorize()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt attempt = ready();
        ScarletClosedInstanceExit other = new ScarletClosedInstanceExit();
        other.configure(true, GROUP);
        long otherGeneration = other.beginSession(LOCATION, START);
        other.updateTracking(otherGeneration, SELF, Collections.singleton(SELF), true);
        assertTrue(other.confirmClosed(GROUP, LOCATION, CLOSED, OBSERVED));
        assertNull(other.poll(0));
        assertNotNull(other.poll(WAIT));
        assertFalse(other.commit(attempt, WAIT, () -> fail("Different policy instance")));
    }

    @Test void backwardsMonotonicSampleRestartsGrace()
    {
        start(); close();
        assertNull(policy.poll(WAIT));
        assertNull(policy.poll(0));
        assertNull(policy.poll(WAIT - 1));
        assertNotNull(policy.poll(WAIT));
    }

    @Test void temporaryHealthSuspensionPreservesProofButRestartsGrace()
    {
        start(); close();
        assertNull(policy.poll(0));
        policy.suspendCountdown();
        assertNull(policy.poll(WAIT));
        assertNull(policy.poll(WAIT * 2 - 1));
        assertNotNull(policy.poll(WAIT * 2));
    }

    @Test void suspensionInvalidatesPreviouslyReadyProposal()
    {
        start(); close();
        ScarletClosedInstanceExit.Attempt attempt = ready();
        policy.suspendCountdown();
        assertFalse(policy.commit(attempt, WAIT, () -> fail("Health interrupted grace")));
        assertNull(policy.poll(WAIT));
        ScarletClosedInstanceExit.Attempt fresh = policy.poll(WAIT * 2);
        assertNotNull(fresh);
        assertFalse(policy.commit(attempt, WAIT * 2, () -> fail("Old token remains invalid")));
        assertTrue(policy.commit(fresh, WAIT * 2, () -> {}));
    }

    @Test void suspensionCannotRetryConsumedCloseAttempt()
    {
        start(); close();
        assertTrue(policy.commit(ready(), WAIT, () -> {}));
        policy.suspendCountdown();
        policy.suspendCountdown();
        assertNull(policy.poll(WAIT * 2));
        assertNull(policy.poll(WAIT * 3));
    }
}

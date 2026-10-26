package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

/** Synthetic workers only: no app, provider, game, filesystem, or network access. */
class ScarletBackgroundWorkTest
{
    static void await(CountDownLatch latch)
    {
        ScarletBackgroundPlayerTrackingTest.waitFor(latch);
    }

    @Test void slowLookupNeverRunsOnOrBlocksSubmittingThread()
    {
        ScarletBackgroundWork work = new ScarletBackgroundWork("synthetic-slow", 4, ex -> fail(ex));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> worker = new AtomicReference<>();
        try
        {
            assertTrue(work.submit("avatar", 0L, () -> true, allowed -> {
                worker.set(Thread.currentThread()); entered.countDown(); await(release); finished.countDown();
            }));
            await(entered);
            assertNotSame(caller, worker.get());
            assertEquals(1L, finished.getCount(), "caller progressed while lookup remains blocked");
        }
        finally { release.countDown(); assertTrue(work.close(3_000L)); }
    }

    @Test void newestPendingValueReplacesOlderValueWithoutQueueGrowth()
    {
        ScarletBackgroundWork work = new ScarletBackgroundWork("synthetic-replace", 4, ex -> fail(ex));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger value = new AtomicInteger();
        try
        {
            work.submit("blocker", 0L, () -> true, allowed -> { entered.countDown(); await(release); }); await(entered);
            for (int i = 0; i < 500; i++)
            {
                final int latest = i;
                work.submit("same-player", 0L, () -> true, allowed -> { value.set(latest); finished.countDown(); });
            }
            assertEquals(1, work.pendingCount());
            release.countDown(); await(finished); assertEquals(499, value.get());
        }
        finally { release.countDown(); assertTrue(work.close(3_000L)); }
    }

    @Test void supersedingRunningWorkInvalidatesItsResults()
    {
        ScarletBackgroundWork work = new ScarletBackgroundWork("synthetic-supersede", 4, ex -> fail(ex));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicBoolean staleApplied = new AtomicBoolean();
        try
        {
            work.submit("player", 0L, () -> true, allowed -> { entered.countDown(); await(release); staleApplied.set(allowed.getAsBoolean()); });
            await(entered);
            work.submit("player", 0L, () -> true, allowed -> finished.countDown());
            release.countDown(); await(finished); assertFalse(staleApplied.get());
        }
        finally { release.countDown(); assertTrue(work.close(3_000L)); }
    }

    @Test void queueIsBoundedAndDropsOldestOptionalWaitingLookup()
    {
        ScarletBackgroundWork work = new ScarletBackgroundWork("synthetic-capacity", 3, ex -> fail(ex));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(3);
        List<Integer> values = Collections.synchronizedList(new ArrayList<>());
        try
        {
            work.submit("blocker", 0L, () -> true, allowed -> { entered.countDown(); await(release); }); await(entered);
            for (int i = 0; i < 20; i++)
            {
                final int value = i;
                work.submit("player-"+i, 0L, () -> true, allowed -> { values.add(value); finished.countDown(); });
                assertTrue(work.pendingCount() <= 3);
            }
            release.countDown(); await(finished); assertEquals(List.of(17, 18, 19), values);
        }
        finally { release.countDown(); assertTrue(work.close(3_000L)); }
    }

    @Test void sessionResetDiscardsPendingAndInFlightResults()
    {
        ScarletBackgroundWork work = new ScarletBackgroundWork("synthetic-reset", 4, ex -> fail(ex));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger stale = new AtomicInteger();
        try
        {
            work.submit("running", 0L, () -> true, allowed -> { entered.countDown(); await(release); if (allowed.getAsBoolean()) stale.incrementAndGet(); });
            await(entered); work.submit("pending", 0L, () -> true, allowed -> stale.incrementAndGet());
            work.reset(); assertEquals(0, work.pendingCount());
            work.submit("new-session", 0L, () -> true, allowed -> finished.countDown());
            release.countDown(); await(finished); assertEquals(0, stale.get());
        }
        finally { release.countDown(); assertTrue(work.close(3_000L)); }
    }

    @Test void revokedPermissionIsCheckedAgainAfterBlockedRequest()
    {
        ScarletBackgroundWork work = new ScarletBackgroundWork("synthetic-consent", 4, ex -> fail(ex));
        AtomicBoolean consent = new AtomicBoolean(true), emitted = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        try
        {
            work.submit("lookup", 0L, consent::get, allowed -> {
                entered.countDown(); await(release); emitted.set(allowed.getAsBoolean()); finished.countDown();
            });
            await(entered); consent.set(false); release.countDown(); await(finished); assertFalse(emitted.get());
        }
        finally { release.countDown(); assertTrue(work.close(3_000L)); }
    }

    @Test void futureJobDoesNotHoldUpReadyJob()
    {
        ScarletBackgroundWork work = new ScarletBackgroundWork("synthetic-delay", 4, ex -> fail(ex));
        CountDownLatch immediate = new CountDownLatch(1);
        AtomicBoolean delayed = new AtomicBoolean();
        try
        {
            work.submit("grace", 60_000L, () -> true, allowed -> delayed.set(true));
            work.submit("ready", 0L, () -> true, allowed -> immediate.countDown());
            await(immediate); assertFalse(delayed.get()); assertEquals(1, work.pendingCount());
        }
        finally { assertTrue(work.close(3_000L)); }
    }

    @Test void closedWorkerRejectsNewWorkAndInvalidatesCapturedGuard()
    {
        ScarletBackgroundWork work = new ScarletBackgroundWork("synthetic-close", 4, ex -> fail(ex));
        CountDownLatch captured = new CountDownLatch(1);
        AtomicReference<BooleanSupplier> guard = new AtomicReference<>();
        work.submit("one", 0L, () -> true, allowed -> { guard.set(allowed); captured.countDown(); }); await(captured);
        assertTrue(work.close(3_000L)); assertFalse(guard.get().getAsBoolean());
        assertFalse(work.submit("after-close", 0L, () -> true, allowed -> fail("closed")));
    }

    @Test void failureIsReportedAndNextJobStillRuns()
    {
        AtomicInteger failures = new AtomicInteger();
        ScarletBackgroundWork work = new ScarletBackgroundWork("synthetic-error", 4, ex -> failures.incrementAndGet());
        CountDownLatch finished = new CountDownLatch(1);
        try
        {
            work.submit("bad", 0L, () -> true, allowed -> { throw new IllegalStateException("synthetic"); });
            work.submit("good", 0L, () -> true, allowed -> finished.countDown());
            await(finished); assertEquals(1, failures.get());
        }
        finally { assertTrue(work.close(3_000L)); }
    }

    @Test void undrainedRequestReportsFailureUntilItActuallyStops()
    {
        ScarletBackgroundWork work = new ScarletBackgroundWork("synthetic-undrained", 4, ex -> fail(ex));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<BooleanSupplier> guard = new AtomicReference<>();
        try
        {
            work.submit("uninterruptible-request", 0L, () -> true, allowed -> {
                guard.set(allowed); entered.countDown(); await(release);
            });
            await(entered);
            assertFalse(work.close(10L), "migration must not proceed while a request is still running");
            assertFalse(guard.get().getAsBoolean());
            assertFalse(work.submit("new", 0L, () -> true, allowed -> fail("closed")));
            release.countDown(); assertTrue(work.close(3_000L));
        }
        finally { release.countDown(); assertTrue(work.close(3_000L)); }
    }
}

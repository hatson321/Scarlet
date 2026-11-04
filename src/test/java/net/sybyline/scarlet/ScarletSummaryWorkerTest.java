package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Synthetic jobs only: no profiles, Discord requests, or VRChat requests. */
class ScarletSummaryWorkerTest
{
    private static void await(CountDownLatch latch) throws InterruptedException
    {
        assertTrue(latch.await(3L, TimeUnit.SECONDS), "Background job did not reach the expected state");
    }

    private static void block(CountDownLatch latch)
    {
        try { latch.await(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private static ScarletSummaryWorker.Submission submitAfterCompletion(ScarletSummaryWorker worker, Runnable action)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        ScarletSummaryWorker.Submission result;
        do
        {
            result = worker.submit(action);
            if (result != ScarletSummaryWorker.Submission.BUSY) return result;
            Thread.sleep(1L);
        }
        while (System.nanoTime() < deadline);
        return result;
    }

    @Test void submissionUsesDedicatedDaemonThreadAndDoesNotWaitForTheJob() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> actual = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        try (ScarletSummaryWorker worker = new ScarletSummaryWorker())
        {
            assertTimeout(Duration.ofSeconds(1L), () -> assertEquals(ScarletSummaryWorker.Submission.ACCEPTED,
                worker.submit(() -> {
                    actual.set(Thread.currentThread());
                    started.countDown();
                    block(release);
                })));
            await(started);
            assertNotSame(caller, actual.get());
            assertTrue(actual.get().isDaemon());
            assertEquals("Scarlet-ModerationSummary", actual.get().getName());
            release.countDown();
        }
    }

    @Test void activeJobRejectsExtraWorkWithoutQueuingOrBlocking() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger rejectedJobsRun = new AtomicInteger();
        try (ScarletSummaryWorker worker = new ScarletSummaryWorker())
        {
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED, worker.submit(() -> {
                started.countDown();
                block(release);
            }));
            await(started);
            assertTimeout(Duration.ofSeconds(1L), () -> {
                for (int i = 0; i < 100; i++)
                    assertEquals(ScarletSummaryWorker.Submission.BUSY, worker.submit(rejectedJobsRun::incrementAndGet));
            });
            release.countDown();
            CountDownLatch next = new CountDownLatch(1);
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED, submitAfterCompletion(worker, next::countDown));
            await(next);
            assertEquals(0, rejectedJobsRun.get());
        }
    }

    @Test void completedJobReleasesTheSlotForAnotherJob() throws Exception
    {
        AtomicInteger count = new AtomicInteger();
        try (ScarletSummaryWorker worker = new ScarletSummaryWorker())
        {
            for (int i = 1; i <= 3; i++)
            {
                CountDownLatch finished = new CountDownLatch(1);
                assertEquals(ScarletSummaryWorker.Submission.ACCEPTED, submitAfterCompletion(worker, () -> {
                    count.incrementAndGet();
                    finished.countDown();
                }));
                await(finished);
                assertEquals(i, count.get());
            }
        }
    }

    @Test void jobFailureStillReleasesTheSlot() throws Exception
    {
        CountDownLatch failed = new CountDownLatch(1), second = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ScarletSummaryWorker worker = new ScarletSummaryWorker())
        {
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED, worker.submit(() -> {
                Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
                    failure.set(error);
                    failed.countDown();
                });
                throw new IllegalStateException("Synthetic summary failure");
            }));
            await(failed);
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED, submitAfterCompletion(worker, second::countDown));
            await(second);
        }
    }

    @Test void shutdownInterruptsAnActiveJobAndRejectsFurtherSubmission() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ScarletSummaryWorker worker = new ScarletSummaryWorker();
        try
        {
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED, worker.submit(() -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException ex) { interrupted.set(true); }
                finally { stopped.countDown(); }
            }));
            await(started);
            worker.close();
            await(stopped);
            assertTrue(interrupted.get());
            assertEquals(ScarletSummaryWorker.Submission.CLOSED, worker.submit(() -> fail("Closed worker ran a job")));
            worker.close();
        }
        finally { worker.close(); }
    }

    @Test void unusedWorkerCanCloseAndCannotRestart() throws Exception
    {
        ScarletSummaryWorker worker = new ScarletSummaryWorker();
        worker.close();
        assertEquals(ScarletSummaryWorker.Submission.CLOSED, worker.submit(() -> fail("Closed worker restarted")));
    }

    @Test void uncooperativeJobMakesShutdownFailInsteadOfAllowingMigration() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        ScarletSummaryWorker worker = new ScarletSummaryWorker(30L);
        try
        {
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED, worker.submit(() -> {
                started.countDown();
                while (release.getCount() != 0L)
                {
                    try { release.await(); }
                    catch (InterruptedException ex) { /* Deliberately simulate an uncooperative job. */ }
                }
                finished.countDown();
            }));
            await(started);
            IOException failure = assertThrows(IOException.class, worker::close);
            assertTrue(failure.getMessage().contains("offline migration cannot proceed"));
            assertEquals(ScarletSummaryWorker.Submission.CLOSED, worker.submit(() -> fail("Timed-out worker accepted work")));
        }
        finally
        {
            release.countDown();
            await(finished);
            worker.close();
        }
    }

    @Test void interruptedShutdownPreservesTheInterruptAndFailsClosed() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        ScarletSummaryWorker worker = new ScarletSummaryWorker();
        try
        {
            worker.submit(() -> {
                started.countDown();
                while (release.getCount() != 0L)
                {
                    try { release.await(); }
                    catch (InterruptedException ex) { /* Keep running until the test releases the job. */ }
                }
                finished.countDown();
            });
            await(started);
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, worker::close);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(ScarletSummaryWorker.Submission.CLOSED, worker.submit(() -> fail("Interrupted close reopened worker")));
        }
        finally
        {
            Thread.interrupted();
            release.countDown();
            await(finished);
            worker.close();
        }
    }

    @Test void invalidInputsAreRejectedWithoutOccupyingTheSlot() throws Exception
    {
        assertThrows(IllegalArgumentException.class, () -> new ScarletSummaryWorker(0L));
        try (ScarletSummaryWorker worker = new ScarletSummaryWorker())
        {
            assertThrows(NullPointerException.class, () -> worker.submit(null));
            CountDownLatch finished = new CountDownLatch(1);
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED, worker.submit(finished::countDown));
            await(finished);
        }
    }
}

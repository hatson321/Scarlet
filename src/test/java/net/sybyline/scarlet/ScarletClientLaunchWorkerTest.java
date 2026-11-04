package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Synthetic jobs: does not query, start, signal, or open any game process/file. */
class ScarletClientLaunchWorkerTest
{
    @Test void launchWaitRunsOffCallerAndExtraRequestsAreNotQueued() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> actual = new AtomicReference<>();
        AtomicInteger extras = new AtomicInteger();
        try (ScarletClientLaunchWorker worker = new ScarletClientLaunchWorker())
        {
            assertTimeout(Duration.ofSeconds(1), () ->
                assertEquals(ScarletClientLaunchWorker.Submission.ACCEPTED, worker.submit(() -> {
                    actual.set(Thread.currentThread());
                    started.countDown();
                    try { release.await(); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                })));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), actual.get());
            assertTrue(actual.get().isDaemon());
            assertEquals("Scarlet-ClientLaunch", actual.get().getName());
            for (int i = 0; i < 100; i++)
                assertEquals(ScarletClientLaunchWorker.Submission.BUSY, worker.submit(extras::incrementAndGet));
            release.countDown();
        }
        assertEquals(0, extras.get());
    }

    @Test void closingInterruptsWaitAndRejectsLaterLaunches() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ScarletClientLaunchWorker worker = new ScarletClientLaunchWorker();
        try
        {
            assertEquals(ScarletClientLaunchWorker.Submission.ACCEPTED, worker.submit(() -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException ex) { interrupted.set(true); Thread.currentThread().interrupt(); }
                finally { finished.countDown(); }
            }));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            worker.close();
            assertEquals(0L, finished.getCount());
            assertTrue(interrupted.get());
            assertEquals(ScarletClientLaunchWorker.Submission.CLOSED, worker.submit(() -> fail("closed worker ran")));
        }
        finally { worker.close(); }
    }

    @Test void completedLaunchReleasesWorkerSlot() throws Exception
    {
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1);
        try (ScarletClientLaunchWorker worker = new ScarletClientLaunchWorker())
        {
            assertEquals(ScarletClientLaunchWorker.Submission.ACCEPTED, worker.submit(first::countDown));
            assertTrue(first.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            ScarletClientLaunchWorker.Submission result;
            do
            {
                result = worker.submit(second::countDown);
                if (result != ScarletClientLaunchWorker.Submission.BUSY) break;
                Thread.sleep(1);
            }
            while (System.nanoTime() < deadline);
            assertEquals(ScarletClientLaunchWorker.Submission.ACCEPTED, result);
            assertTrue(second.await(3, TimeUnit.SECONDS));
        }
    }
}

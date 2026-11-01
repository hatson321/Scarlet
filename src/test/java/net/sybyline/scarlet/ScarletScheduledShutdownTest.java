package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class ScarletScheduledShutdownTest
{
    private static ScheduledThreadPoolExecutor scheduler(int threads)
    {
        return Scarlet.newScheduler(threads, runnable -> new Thread(runnable, "Synthetic shutdown test"));
    }

    @Test void futureTimersDoNotForceAnOtherwiseIdleShutdownToFail() throws Exception
    {
        // Worker, modal and IPC pools all use the same shutdown-safe factory.
        for (int threads : new int[] { 4, 1, 1 })
        {
            ScheduledThreadPoolExecutor scheduler = scheduler(threads);
            AtomicBoolean ran = new AtomicBoolean();
            try
            {
                ScheduledFuture<?> delayed = scheduler.schedule(() -> ran.set(true), 1, TimeUnit.DAYS);
                ScheduledFuture<?> periodic = scheduler.scheduleAtFixedRate(() -> ran.set(true), 1, 1, TimeUnit.DAYS);
                scheduler.shutdown();
                assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(delayed.isCancelled());
                assertTrue(periodic.isCancelled());
                assertFalse(ran.get());
            }
            finally
            {
                scheduler.shutdownNow();
                assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test void alreadyRunningWorkDrainsWithoutInterruptionBeforeTermination() throws Exception
    {
        ScheduledThreadPoolExecutor scheduler = scheduler(1);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(), saved = new AtomicBoolean();
        try
        {
            Future<?> work = scheduler.submit(() -> {
                started.countDown();
                try { finish.await(); saved.set(true); }
                catch (InterruptedException failure) { interrupted.set(true); Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            ScheduledFuture<?> retry = scheduler.schedule(() -> fail("A future retry ran during shutdown"), 1, TimeUnit.DAYS);
            scheduler.shutdown();
            assertFalse(scheduler.awaitTermination(50, TimeUnit.MILLISECONDS));
            assertFalse(work.isDone());
            assertTrue(retry.isCancelled());
            finish.countDown();
            work.get(2, TimeUnit.SECONDS);
            assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(saved.get());
            assertFalse(interrupted.get());
        }
        finally
        {
            finish.countDown();
            scheduler.shutdownNow();
            assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test void stuckRunningWorkStillFailsTheGracefulTerminationCheck() throws Exception
    {
        ScheduledThreadPoolExecutor scheduler = scheduler(1);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        try
        {
            scheduler.execute(() -> {
                started.countDown();
                try { finish.await(); }
                catch (InterruptedException failure) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            scheduler.shutdown();
            assertFalse(scheduler.awaitTermination(50, TimeUnit.MILLISECONDS),
                "The caller must still detect incomplete running work and abort offline handoff");
            scheduler.shutdownNow();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
        }
        finally
        {
            finish.countDown();
            scheduler.shutdownNow();
            assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}

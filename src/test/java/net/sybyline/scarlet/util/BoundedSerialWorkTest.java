package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class BoundedSerialWorkTest
{
    @Test void importantWarningsReplaceQueuedRoutineWorkWithoutExceedingCapacity() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (BoundedSerialWork queue = new BoundedSerialWork("synthetic priority worker", 2, 60000))
        {
            queue.submit("playing", current -> { entered.countDown(); release.await(); });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var routine = queue.submit("routine", current -> fail());
            var warning = queue.submit("warning", current -> {}, true);
            assertTrue(routine.isCancelled()); assertEquals(2, queue.size());
            release.countDown(); warning.get(2, TimeUnit.SECONDS);
        }
        finally { release.countDown(); }
    }

    @Test void blockedWorkerCoalescesAndRejectsExcessWithoutBlockingCaller() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (BoundedSerialWork queue = new BoundedSerialWork("synthetic bounded worker", 2, 60000))
        {
            var first = queue.submit("one", current -> { entered.countDown(); release.await(); });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertSame(first, queue.submit("one", current -> fail()));
            var second = queue.submit("two", current -> assertTrue(current.getAsBoolean()));
            var third = queue.submit("three", current -> fail());
            assertTrue(third.isCompletedExceptionally()); assertEquals(2, queue.size());
            release.countDown(); first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
        }
        finally { release.countDown(); }
    }

    @Test void ageAndGenerationCancelQueuedWorkAndGuardAnAlreadyRunningResult() throws Exception
    {
        AtomicLong now = new AtomicLong(); AtomicInteger published = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (BoundedSerialWork queue = new BoundedSerialWork("synthetic stale worker", 4, 100, now::get))
        {
            var active = queue.submit("active", current -> { entered.countDown(); release.await(); if (current.getAsBoolean()) published.incrementAndGet(); });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var stale = queue.submit("stale", current -> published.incrementAndGet());
            now.set(101); release.countDown();
            assertThrows(CancellationException.class, () -> active.get(2, TimeUnit.SECONDS));
            assertThrows(CancellationException.class, () -> stale.get(2, TimeUnit.SECONDS));
            assertEquals(0, published.get());
            CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
            var old = queue.submit("old", current -> { started.countDown(); finish.await(); if (current.getAsBoolean()) published.incrementAndGet(); });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = queue.submit("queued", current -> fail());
            queue.invalidate(); finish.countDown();
            assertTrue(old.isCancelled()); assertTrue(queued.isCancelled());
            queue.submit("new", current -> published.incrementAndGet()).get(2, TimeUnit.SECONDS);
            assertEquals(1, published.get());
        }
    }
}

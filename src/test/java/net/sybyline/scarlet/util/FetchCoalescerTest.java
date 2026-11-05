package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class FetchCoalescerTest
{
    @Test void overlappingReadsShareOneResultButCompletedAndDifferentKeysDoNot() throws Exception
    {
        FetchCoalescer cache = new FetchCoalescer();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> waiting = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger();
        try
        {
            Future<Integer> first = pool.submit(() -> cache.fetch("key", () -> { calls.incrementAndGet(); started.countDown(); await(release); return 1; }));
            assertTrue(started.await(2,TimeUnit.SECONDS));
            Future<Integer> second = pool.submit(() -> { waiting.set(Thread.currentThread()); return cache.fetch("key", () -> { calls.incrementAndGet(); return 2; }); });
            awaitWaiting(waiting);
            assertEquals(3, cache.fetch("other", () -> 3));
            release.countDown(); assertEquals(1,first.get(2,TimeUnit.SECONDS)); assertEquals(1,second.get(2,TimeUnit.SECONDS)); assertEquals(1,calls.get());
            assertEquals(4,cache.fetch("key", () -> 4));
        }
        finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void invalidationReleasesWaitersAndDiscardsOldCompletion() throws Exception
    {
        FetchCoalescer cache = new FetchCoalescer(); ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1); AtomicReference<Thread> waiting = new AtomicReference<>();
        try {
            Future<String> first = pool.submit(() -> cache.fetch("same", () -> { started.countDown(); await(release); return "old"; }));
            assertTrue(started.await(2,TimeUnit.SECONDS));
            Future<String> second = pool.submit(() -> { waiting.set(Thread.currentThread()); return cache.fetch("same", () -> "bad"); });
            awaitWaiting(waiting); cache.invalidate(); assertNull(second.get(2,TimeUnit.SECONDS));
            assertEquals("new",cache.fetch("same", () -> "new")); release.countDown(); assertNull(first.get(2,TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void capacityTimeoutAndReentryStayBounded() throws Exception
    {
        FetchCoalescer cache = new FetchCoalescer(1,50); ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> cache.fetch("held", () -> { started.countDown(); await(release); return "ok"; }));
            assertTrue(started.await(2,TimeUnit.SECONDS));
            assertEquals("overflow",cache.fetch("other", () -> "overflow"));
            assertNull(cache.fetch("held", () -> { fail("A timed-out waiter must not create a duplicate request"); return null; }));
            release.countDown(); assertEquals("ok",first.get(2,TimeUnit.SECONDS));
            assertEquals("nested",cache.fetch("same", () -> cache.fetch("same", () -> "nested")));
            assertThrows(IllegalStateException.class, () -> cache.fetch("same", () -> { throw new IllegalStateException("synthetic"); }));
            assertEquals("retry",cache.fetch("same", () -> "retry"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    private static void await(CountDownLatch latch) { try { assertTrue(latch.await(3,TimeUnit.SECONDS)); } catch(InterruptedException ex) { throw new AssertionError(ex); } }
    private static void awaitWaiting(AtomicReference<Thread> thread) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<until) { Thread t=thread.get(); if(t!=null && t.getState()==Thread.State.TIMED_WAITING) return; Thread.sleep(1); }
        fail("Follower did not wait for the in-flight request");
    }
}

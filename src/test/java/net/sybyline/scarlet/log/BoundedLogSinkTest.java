package net.sybyline.scarlet.log;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BoundedLogSinkTest
{
    @Test void blockedConsoleDoesNotBlockFileAndQueueHasCountAndByteBounds() throws Exception
    {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1), persisted = new CountDownLatch(1);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        BoundedLogSink console = new BoundedLogSink("synthetic console", 4, 256, entry -> {
            blocked.countDown(); try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            output.add(entry.text);
        }, () -> {});
        BoundedLogSink file = new BoundedLogSink("synthetic file", 4, 256, entry -> persisted.countDown(), () -> {});
        try
        {
            console.offer("first", false, false); assertTrue(blocked.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) console.offer("verbose entry " + i, false, false);
            console.offer("important failure", true, true);
            file.offer("independent durable entry", true, true);
            assertTrue(persisted.await(2, TimeUnit.SECONDS));
            assertTrue(console.size() <= 4); assertTrue(console.characters() <= 256); assertTrue(console.dropped() > 990);
            assertFalse(console.awaitIdle(20));
            release.countDown(); assertTrue(console.close(2000)); assertTrue(file.close(2000));
            assertTrue(console.awaitIdle(20));
            assertTrue(output.contains("important failure"));
        }
        finally { release.countDown(); console.close(2000); file.close(2000); }
    }

    @Test void oversizeEntriesAreClippedAndShutdownDoesNotWaitForBlockedDestination() throws Exception
    {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        BoundedLogSink sink = new BoundedLogSink("synthetic blocked sink", 4, 65536, entry -> {
            blocked.countDown(); try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }, () -> {});
        try
        {
            sink.offer("x".repeat(100000), true, false); assertTrue(blocked.await(2, TimeUnit.SECONDS));
            assertEquals(1, sink.clipped()); sink.offer("queued", false, false);
            assertTimeout(java.time.Duration.ofSeconds(1), () -> assertFalse(sink.close(30)));
            assertEquals(0, sink.size()); assertTrue(sink.dropped() >= 1);
        }
        finally { release.countDown(); sink.close(2000); }
    }
}

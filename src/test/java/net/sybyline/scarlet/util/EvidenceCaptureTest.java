package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Uses fake key sinks only; never injects OS input. */
class EvidenceCaptureTest
{
    @Test void allFunctionKeysUseTheirDeclaredAwtCodes() throws Exception
    {
        for (int i = 1; i <= 24; i++)
            assertArrayEquals(new int[]{KeyEvent.VK_CONTROL, KeyEvent.class.getField("VK_F" + i).getInt(null)},
                EvidenceCapture.parse("CTRL+F" + i));
        assertNull(EvidenceCapture.parse("CTRL+F25"));
        assertNull(EvidenceCapture.parse("SHIFT"));
    }

    @Test void partialPressAndReleaseFailuresStillReleaseAllAttemptedKeys()
    {
        List<String> calls = new ArrayList<>();
        IllegalStateException original = new IllegalStateException("press failed");
        EvidenceCapture.KeySink sink = new EvidenceCapture.KeySink() {
            public void press(int key) { calls.add("p" + key); if (key == 3) throw original; }
            public void release(int key) { calls.add("r" + key); if (key == 3) throw new IllegalArgumentException("release failed"); }
        };
        Throwable failure = assertThrows(IllegalStateException.class,
            () -> EvidenceCapture.pressAndRelease(new int[]{1, 2, 3, 4}, 1, sink, ms -> fail("Must not hold failed chord")));
        assertSame(original, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of("p1", "p2", "p3", "r3", "r2", "r1"), calls);
    }

    @Test void interruptionReleasesKeysAndPreservesInterruptFlag()
    {
        List<Integer> released = new ArrayList<>();
        try
        {
            assertThrows(InterruptedException.class, () -> EvidenceCapture.pressAndRelease(new int[]{1, 2}, 1,
                new EvidenceCapture.KeySink() {
                    public void press(int key) { }
                    public void release(int key) { released.add(key); }
                }, ms -> { throw new InterruptedException(); }));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of(2, 1), released);
        }
        finally { Thread.interrupted(); }
    }

    @Test void simultaneousCapturesCannotInterleaveChords() throws Exception
    {
        List<String> calls = new CopyOnWriteArrayList<>();
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        EvidenceCapture.KeySink sink = new EvidenceCapture.KeySink() {
            public void press(int key) { calls.add("p" + key); }
            public void release(int key) { calls.add("r" + key); }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> first = executor.submit(() -> {
                try { EvidenceCapture.pressAndRelease(new int[]{1}, 1, sink, ms -> { held.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }); }
                catch (Throwable ex) { throw new AssertionError(ex); }
            });
            assertTrue(held.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                try { EvidenceCapture.pressAndRelease(new int[]{2}, 1, sink, ms -> {}); }
                catch (Throwable ex) { throw new AssertionError(ex); }
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("p1"), calls);
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("p1", "r1", "p2", "r2"), calls);
        }
        finally { release.countDown(); executor.shutdownNow(); }
    }
}

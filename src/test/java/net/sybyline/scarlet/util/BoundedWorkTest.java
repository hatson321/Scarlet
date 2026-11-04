package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BoundedWorkTest {
    static class Child extends Process {
        boolean alive = true;
        int stops;
        final InputStream input;
        Child(byte[] output) { input = new ByteArrayInputStream(output); }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public boolean waitFor(long duration, TimeUnit unit) throws InterruptedException {
            if (alive) Thread.sleep(Math.min(10L, unit.toMillis(duration)));
            return !alive;
        }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { stops++; alive = false; }
    }
    @Test void hangingChildHasWholeProcessDeadline() throws Exception {
        Child child = new Child(new byte[0]);
        BoundedProcess.Result result = BoundedProcess.await(child, 60, 1024, () -> false, null);
        assertEquals(BoundedProcess.TIMED_OUT, result.exitCode);
        assertTrue(result.terminated); assertTrue(child.stops > 0);
    }
    @Test void cancellationStopsOwnedChildAndCannotLookLikeStartFailure() throws Exception {
        Child child = new Child(new byte[0]);
        BoundedProcess.Result result = BoundedProcess.await(child, 1000, 1024, () -> true, null);
        assertEquals(BoundedProcess.CANCELLED, result.exitCode);
        assertNotEquals(-1, result.exitCode); assertTrue(result.terminated);
    }
    @Test void outputBudgetIsEnforced() throws Exception {
        Child child = new Child(new byte[4096]);
        BoundedProcess.Result result = BoundedProcess.await(child, 1000, 100, () -> false, null);
        assertEquals(BoundedProcess.OUTPUT_LIMIT, result.exitCode);
        assertTrue(result.output.length() <= 100); assertTrue(result.terminated);
    }
    @Test void initializationExceptionsAndTimeoutCannotStrandFuture() throws Exception {
        ExecutorService failed = Executors.newSingleThreadExecutor();
        assertThrows(ExecutionException.class, () -> BoundedInitialization.run(failed, () -> { throw new IllegalStateException("synthetic"); }, 1000));
        assertTrue(failed.awaitTermination(1, TimeUnit.SECONDS));
        ExecutorService blocked = Executors.newSingleThreadExecutor();
        assertThrows(ExecutionException.class, () -> BoundedInitialization.run(blocked, () -> {
            try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        }, 50));
        assertTrue(blocked.awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test void brokenCancellationCallbackStillStopsTheOwnedChild() {
        Child child = new Child(new byte[0]);
        IllegalStateException failure = new IllegalStateException("Synthetic callback failure");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> BoundedProcess.await(child, 1000, 1024, () -> { throw failure; }, null)));
        assertFalse(child.alive);
        assertTrue(child.stops > 0);
    }

    @Test void brokenWaitAndGracefulStopStillAttemptForcedCleanup() {
        Child child = new Child(new byte[0]) {
            @Override public boolean waitFor(long duration, TimeUnit unit) { throw new IllegalStateException("Synthetic wait failure"); }
            @Override public void destroy() { throw new IllegalStateException("Synthetic graceful-stop failure"); }
            @Override public Process destroyForcibly() { stops++; alive = false; return this; }
        };
        assertThrows(IllegalStateException.class, () -> BoundedProcess.await(child, 1000, 1024, () -> false, null));
        assertFalse(child.alive);
        assertTrue(child.stops > 0);
    }

    @Test void interruptedWaitCleansUpBeforePropagatingTheInterruption() {
        Child child = new Child(new byte[0]);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> BoundedProcess.await(child, 1000, 1024, () -> false, null));
            assertFalse(child.alive);
            assertTrue(child.stops > 0);
        } finally { Thread.interrupted(); }
    }

    @Test void interruptionDuringGracefulCleanupDoesNotSkipForceStop() {
        Child child = new Child(new byte[0]) {
            boolean interrupted;
            @Override public void destroy() { }
            @Override public Process destroyForcibly() { stops++; alive = false; return this; }
            @Override public boolean waitFor(long duration, TimeUnit unit) throws InterruptedException {
                if (!interrupted) { interrupted = true; throw new InterruptedException("Synthetic cleanup interruption"); }
                return !alive;
            }
        };
        assertThrows(InterruptedException.class, () -> BoundedProcess.await(child, 1000, 1024, () -> true, null));
        assertFalse(child.alive);
        assertTrue(child.stops > 0);
    }

    @Test void unknownChildStatusCannotBeReportedAsConfirmedTermination() throws Exception {
        Child child = new Child(new byte[0]) {
            @Override public boolean isAlive() { throw new IllegalStateException("Synthetic status failure"); }
        };
        BoundedProcess.Result result = BoundedProcess.await(child, 1000, 1024, () -> true, null);
        assertFalse(result.terminated);
        assertTrue(child.stops > 0);
    }

    @Test void runtimeFailureRetainsAnInterruptionDuringItsCleanup() {
        Child child = new Child(new byte[0]) {
            boolean interrupted;
            @Override public boolean waitFor(long duration, TimeUnit unit) throws InterruptedException {
                if (!interrupted) { interrupted = true; throw new InterruptedException("Synthetic cleanup interruption"); }
                return !alive;
            }
        };
        try {
            assertThrows(IllegalStateException.class, () -> BoundedProcess.await(child, 1000, 1024,
                () -> { throw new IllegalStateException("Synthetic callback failure"); }, null));
            assertFalse(child.alive);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void uninterruptibleInitializationBoundsCallerButRequiresEventualNativeReturn() throws Exception {
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        ExecutorService blocked = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "synthetic-native-initializer");
            thread.setDaemon(true);
            return thread;
        });
        try {
            assertThrows(ExecutionException.class, () -> BoundedInitialization.run(blocked, () -> {
                entered.countDown();
                try {
                    while (release.getCount() != 0L) {
                        try { release.await(); }
                        catch (InterruptedException ignored) { /* Simulate a native call ignoring cancellation. */ }
                    }
                } finally { cleaned.countDown(); }
            }, 100));
            assertEquals(0L, entered.getCount());
            assertEquals(1L, cleaned.getCount(), "A timeout cannot claim native cleanup already completed");
            assertFalse(blocked.isTerminated());
        } finally {
            release.countDown();
            blocked.shutdownNow();
            assertTrue(blocked.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
        }
    }
}

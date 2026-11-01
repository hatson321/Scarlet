package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

class PkexecInstallerLifecycleTest
{
    private static class FakeProcess extends Process
    {
        final AtomicBoolean alive = new AtomicBoolean(true);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger destroys = new AtomicInteger();
        InputStream output = new ByteArrayInputStream(new byte[0]);
        int exitCode;

        void finish(int code) { exitCode = code; alive.set(false); finished.countDown(); }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public InputStream getInputStream() { return output; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() throws InterruptedException { finished.await(); return exitCode; }
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return finished.await(timeout, unit); }
        public int exitValue() { if (alive.get()) throw new IllegalThreadStateException(); return exitCode; }
        public void destroy() { destroys.incrementAndGet(); finish(143); }
        public Process destroyForcibly() { destroy(); return this; }
        public boolean isAlive() { return alive.get(); }
        public ProcessHandle toHandle() { throw new UnsupportedOperationException("Synthetic child has no OS process"); }
    }

    @Test
    void cancelBeforeStartingNeverLaunchesAChild()
    {
        AtomicInteger starts = new AtomicInteger();
        PkexecInstaller.InstallSession session = new PkexecInstaller.InstallSession(() -> {
            starts.incrementAndGet(); return new FakeProcess();
        }, 1000);
        session.cancel();
        assertEquals(BoundedProcess.CANCELLED, session.run(null, child -> { }));
        assertEquals(0, starts.get());
    }

    @Test
    void sharedButtonAndWindowCancelActionStopsTheOwnedChild()
    {
        for (String source : new String[] {"button", "window close"})
        {
            FakeProcess process = new FakeProcess();
            PkexecInstaller.InstallSession session = new PkexecInstaller.InstallSession(() -> process, 1000);
            Runnable cancelOrClose = session::cancel;
            assertEquals(BoundedProcess.CANCELLED, session.run(null, child -> cancelOrClose.run()), source);
            assertFalse(process.isAlive(), source);
            assertTrue(process.destroys.get() > 0, source);
        }
    }

    @Test
    void onlyFailedStartReturnsFallbackSentinel()
    {
        PkexecInstaller.InstallSession failed = new PkexecInstaller.InstallSession(() -> {
            throw new IOException("Synthetic start failure");
        }, 1000);
        assertEquals(PkexecInstaller.NOT_STARTED, failed.run(null, child -> fail("No child may be published")));
        FakeProcess finished = new FakeProcess();
        finished.finish(-1);
        assertNotEquals(PkexecInstaller.NOT_STARTED,
            new PkexecInstaller.InstallSession(() -> finished, 1000).run(null, child -> { }));
    }

    @Test
    void timeoutStopsProcessAndCannotLaunchFallback()
    {
        FakeProcess process = new FakeProcess();
        int result = new PkexecInstaller.InstallSession(() -> process, 100).run(null, child -> { });
        assertEquals(BoundedProcess.TIMED_OUT, result);
        assertFalse(process.isAlive());
        assertNotEquals(PkexecInstaller.NOT_STARTED, result);
    }

    @Test
    void failedOutputAfterStartCannotLaunchFallback()
    {
        FakeProcess process = new FakeProcess();
        process.output = new InputStream() {
            public int read() throws IOException { throw new IOException("Synthetic stream failure"); }
        };
        int result = new PkexecInstaller.InstallSession(() -> process, 1000).run(null, child -> { });
        assertEquals(BoundedProcess.OUTPUT_LIMIT, result);
        assertFalse(process.isAlive());
    }

    @Test
    void cancellationDuringStartupKillsLatePublishedProcess() throws Exception
    {
        exerciseLateStart(true);
    }

    @Test
    void deadlineAlsoIncludesStalledStartupAndKillsLatePublishedProcess() throws Exception
    {
        exerciseLateStart(false);
    }

    private void exerciseLateStart(boolean cancel) throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeProcess process = new FakeProcess();
        PkexecInstaller.InstallSession session = new PkexecInstaller.InstallSession(() -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return process;
        }, cancel ? 5000 : 100);
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try
        {
            Future<Integer> result = runner.submit(() -> session.run(null, child -> { }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (cancel) session.cancel();
            assertEquals(cancel ? BoundedProcess.CANCELLED : BoundedProcess.TIMED_OUT, result.get(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(process.finished.await(5, TimeUnit.SECONDS), "Late process must be terminated, not orphaned");
            assertFalse(process.isAlive());
        }
        finally
        {
            release.countDown();
            runner.shutdownNow();
            assertTrue(runner.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void allProcessWorkRunsOutsideSwingEventThread() throws Exception
    {
        AtomicBoolean startedOnEdt = new AtomicBoolean(true);
        FakeProcess finished = new FakeProcess();
        finished.finish(0);
        assertEquals(0, new PkexecInstaller.InstallSession(() -> {
            startedOnEdt.set(SwingUtilities.isEventDispatchThread()); return finished;
        }, 1000).run(null, child -> assertFalse(SwingUtilities.isEventDispatchThread())));
        assertFalse(startedOnEdt.get());
        SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class,
            () -> new PkexecInstaller.InstallSession(() -> { fail("Must not start on EDT"); return null; }, 1000)
                .run(null, child -> { })));
    }

    @Test
    void runtimeFailureAfterStartStillTerminatesOwnedProcess()
    {
        FakeProcess process = new FakeProcess();
        int code = new PkexecInstaller.InstallSession(() -> process, 1000).run(null, child -> {
            throw new IllegalStateException("Synthetic progress failure");
        });
        assertNotEquals(PkexecInstaller.NOT_STARTED, code);
        assertFalse(process.isAlive());
    }
}

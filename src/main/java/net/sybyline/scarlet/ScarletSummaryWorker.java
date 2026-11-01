package net.sybyline.scarlet;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One isolated background summary at a time, with no waiting-job backlog. */
final class ScarletSummaryWorker implements Closeable
{
    enum Submission { ACCEPTED, BUSY, CLOSED }

    private final ThreadPoolExecutor executor;
    private final long shutdownMillis;
    private boolean busy;
    private boolean closed;

    ScarletSummaryWorker()
    {
        this(5_000L);
    }

    ScarletSummaryWorker(long shutdownMillis)
    {
        if (shutdownMillis <= 0L)
            throw new IllegalArgumentException("Shutdown timeout must be positive");
        this.shutdownMillis = shutdownMillis;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), action -> {
                Thread thread = new Thread(action, "Scarlet-ModerationSummary");
                thread.setDaemon(true);
                return thread;
            });
    }

    /** Never waits for another summary or runs the action on the submitting thread. */
    synchronized Submission submit(Runnable action)
    {
        Objects.requireNonNull(action, "action");
        if (this.closed) return Submission.CLOSED;
        if (this.busy) return Submission.BUSY;
        this.busy = true;
        try
        {
            this.executor.execute(() -> {
                try { action.run(); }
                finally
                {
                    synchronized (ScarletSummaryWorker.this)
                    {
                        ScarletSummaryWorker.this.busy = false;
                    }
                }
            });
            return Submission.ACCEPTED;
        }
        catch (RejectedExecutionException ex)
        {
            this.busy = false;
            // The previous job may have finished just before its thread became idle.
            // Do not queue another job during that brief hand-off window.
            return Submission.BUSY;
        }
    }

    @Override public void close() throws IOException
    {
        synchronized (this)
        {
            this.closed = true;
            this.executor.shutdownNow();
        }
        try
        {
            if (!this.executor.awaitTermination(this.shutdownMillis, TimeUnit.MILLISECONDS))
                throw new IOException("Moderation summary worker did not stop; offline migration cannot proceed");
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping moderation summary worker", ex);
        }
    }
}

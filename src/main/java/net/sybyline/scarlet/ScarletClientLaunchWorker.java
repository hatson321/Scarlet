package net.sybyline.scarlet;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Launch/exit waits must not occupy the audit-delivery or UI thread. No backlog. */
final class ScarletClientLaunchWorker implements Closeable
{
    enum Submission { ACCEPTED, BUSY, CLOSED }

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L,
        TimeUnit.MILLISECONDS, new SynchronousQueue<>(), action -> {
            Thread thread = new Thread(action, "Scarlet-ClientLaunch");
            thread.setDaemon(true);
            return thread;
        });
    private boolean busy, closed;

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
                    synchronized (ScarletClientLaunchWorker.this)
                    {
                        ScarletClientLaunchWorker.this.busy = false;
                    }
                }
            });
            return Submission.ACCEPTED;
        }
        catch (RejectedExecutionException ex)
        {
            this.busy = false;
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
            if (!this.executor.awaitTermination(5L, TimeUnit.SECONDS))
                throw new IOException("VRChat launch worker did not stop; offline migration cannot proceed");
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping VRChat launch worker", ex);
        }
    }
}

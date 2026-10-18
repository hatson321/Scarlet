package net.sybyline.scarlet;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded, identifier-only background delivery; the outbox remains the durable queue. */
final class ScarletAuditFollowupWorker implements Closeable
{
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Consumer<String> action;
    private final ThreadPoolExecutor executor;

    ScarletAuditFollowupWorker(Consumer<String> action)
    {
        this.action = action;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "Scarlet-AuditFollowup");
                thread.setDaemon(true);
                return thread;
            });
    }

    boolean submit(String id)
    {
        if (!this.pending.add(id)) return false;
        try
        {
            this.executor.execute(() -> {
                try { this.action.accept(id); }
                finally { this.pending.remove(id); }
            });
            return true;
        }
        catch (RejectedExecutionException ex)
        {
            this.pending.remove(id);
            return false; // A later audit poll/restart will find the still-persisted record.
        }
    }

    @Override public void close() throws IOException
    {
        this.executor.shutdown();
        try
        {
            if (!this.executor.awaitTermination(10, TimeUnit.SECONDS))
            {
                this.executor.shutdownNow();
                if (!this.executor.awaitTermination(10, TimeUnit.SECONDS))
                    throw new IOException("Audit follow-up worker did not stop; offline migration cannot proceed");
            }
        }
        catch (InterruptedException ex)
        {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping audit follow-up worker", ex);
        }
    }
}

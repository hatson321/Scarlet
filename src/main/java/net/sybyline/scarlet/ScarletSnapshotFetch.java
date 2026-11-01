package net.sybyline.scarlet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Bounded, cancellable public-snapshot preparation; never owns audit metadata. */
final class ScarletSnapshotFetch
{
    static final long DEADLINE_MILLIS = 8_000L;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(3, 3, 30L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(6), task -> {
            Thread thread = new Thread(task, "Scarlet snapshot lookup");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final Set<Batch> active = new HashSet<>();
    private boolean stopped;

    interface Read
    {
        JsonElement read() throws Exception;
        void cancel();
    }

    static final class Result
    {
        final JsonObject user, representedGroup;
        final JsonArray groups;
        Result(JsonObject user, JsonArray groups, JsonObject representedGroup)
        {
            this.user = user;
            this.groups = groups;
            this.representedGroup = representedGroup;
        }
    }

    Result fetch(String targetId, BooleanSupplier allowed, Read... reads)
    {
        return this.fetch(targetId, allowed, DEADLINE_MILLIS, reads);
    }

    Result fetch(String targetId, BooleanSupplier allowed, long timeoutMillis, Read... reads)
    {
        if (reads.length != 3) throw new IllegalArgumentException("Three snapshot reads required");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Batch batch = new Batch(reads);
        try
        {
            synchronized (this)
            {
                // At most three batches (nine calls) may run or wait; reject excess work
                // instead of making the audit loop wait behind an unbounded work queue.
                if (this.stopped || this.active.size() >= 3 || !allowed.getAsBoolean()) return null;
                this.active.add(batch);
                for (int i = 0; i < reads.length; i++)
                {
                    final int kind = i;
                    batch.futures.add(this.executor.submit(() -> {
                        try
                        {
                            if (Thread.currentThread().isInterrupted() || !allowed.getAsBoolean()) return null;
                            JsonElement raw = reads[kind].read();
                            // Futures must never retain raw API responses or exceptions containing
                            // response bodies. Only explicitly public projections leave the worker.
                            if (!allowed.getAsBoolean()) return null;
                            if (kind == 0) return raw != null && raw.isJsonObject()
                                ? ScarletSnapshotPrivacy.user(raw.getAsJsonObject(), targetId) : null;
                            if (kind == 1) return raw != null && raw.isJsonArray()
                                ? ScarletSnapshotPrivacy.groups(raw.getAsJsonArray()) : null;
                            return raw != null && raw.isJsonObject()
                                ? ScarletSnapshotPrivacy.group(raw.getAsJsonObject()) : null;
                        }
                        catch (Exception ex)
                        {
                            // Missing optional snapshots must not delay or expose the response
                            // body while the actual moderation embed can still be delivered.
                            return null;
                        }
                    }));
                }
            }
            List<JsonElement> safe = new ArrayList<>(3);
            for (Future<JsonElement> future : batch.futures)
            {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return null;
                safe.add(future.get(remaining, TimeUnit.NANOSECONDS));
            }
            if (!allowed.getAsBoolean() || Thread.currentThread().isInterrupted()) return null;
            return new Result((JsonObject)safe.get(0), (JsonArray)safe.get(1), (JsonObject)safe.get(2));
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (Exception ex)
        {
            return null;
        }
        finally
        {
            batch.cancel();
            synchronized (this) { this.active.remove(batch); }
            this.executor.purge();
        }
    }

    boolean shutdown()
    {
        synchronized (this)
        {
            this.stopped = true;
            // Cancel sockets before interrupts: an interrupted rate-limit wait must
            // never accidentally release a live request past the global backoff.
            for (Batch batch : this.active) batch.cancel();
            this.executor.shutdownNow();
        }
        try { return this.executor.awaitTermination(5L, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
    }

    private static final class Batch
    {
        final Read[] reads;
        final List<Future<JsonElement>> futures = Collections.synchronizedList(new ArrayList<>());
        Batch(Read[] reads) { this.reads = reads; }
        void cancel()
        {
            for (Read read : this.reads) read.cancel();
            synchronized (this.futures)
            {
                for (Future<JsonElement> future : this.futures) future.cancel(true);
            }
        }
    }
}

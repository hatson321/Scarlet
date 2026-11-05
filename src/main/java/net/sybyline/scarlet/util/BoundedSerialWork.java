package net.sybyline.scarlet.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Serial work with bounded admission, keyed coalescing and stale-work cancellation. */
public final class BoundedSerialWork implements AutoCloseable
{
    @FunctionalInterface public interface Work { void run(BooleanSupplier current) throws Exception; }
    private final ThreadPoolExecutor executor;
    private final Map<String, Job> pending = new LinkedHashMap<>();
    private final int capacity;
    private final long maximumAge;
    private final LongSupplier clock;
    private long generation;
    private boolean closed;

    public BoundedSerialWork(String name, int capacity, long maximumAgeMillis)
    { this(name, capacity, maximumAgeMillis, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime())); }

    public BoundedSerialWork(String name, int capacity, long maximumAgeMillis, LongSupplier clock)
    {
        if (capacity < 1 || maximumAgeMillis < 1) throw new IllegalArgumentException();
        this.capacity = capacity; this.maximumAge = maximumAgeMillis; this.clock = clock;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity), runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; });
    }

    public synchronized CompletableFuture<Void> submit(String key, Work work)
    { return this.submit(key, work, false); }

    public synchronized CompletableFuture<Void> submit(String key, Work work, boolean important)
    {
        Job existing = this.pending.get(key);
        if (existing != null) return existing.future;
        if (!this.closed && important && this.pending.size() >= this.capacity)
        {
            for (java.util.Iterator<Job> iterator = this.pending.values().iterator(); iterator.hasNext(); )
            {
                Job candidate = iterator.next();
                if (!candidate.important && this.executor.remove(candidate))
                { iterator.remove(); candidate.future.cancel(false); break; }
            }
        }
        if (this.closed || this.pending.size() >= this.capacity)
        {
            CompletableFuture<Void> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new RejectedExecutionException("Work queue is full or closed")); return rejected;
        }
        Job job = new Job(key, work, important); this.pending.put(key, job);
        try { this.executor.execute(job); }
        catch (RejectedExecutionException failure) { this.pending.remove(key); job.future.completeExceptionally(failure); }
        return job.future;
    }

    public synchronized int size() { return this.pending.size(); }
    public synchronized void invalidate()
    {
        this.generation++;
        for (Job job : this.pending.values()) job.future.cancel(false);
        this.pending.clear(); this.executor.getQueue().clear();
    }
    @Override public synchronized void close()
    { this.closed = true; this.invalidate(); this.executor.shutdownNow(); }

    private final class Job implements Runnable
    {
        final String key;
        final Work work;
        final boolean important;
        final long epoch = generation, submitted = clock.getAsLong();
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Job(String key, Work work, boolean important) { this.key = key; this.work = work; this.important = important; }
        boolean current()
        {
            synchronized (BoundedSerialWork.this)
            {
                long age = clock.getAsLong() - this.submitted;
                return !closed && generation == this.epoch && !this.future.isCancelled() && age >= 0 && age < maximumAge;
            }
        }
        @Override public void run()
        {
            try
            {
                if (!this.current()) { this.future.cancel(false); return; }
                this.work.run(this::current);
                if (this.current()) this.future.complete(null); else this.future.cancel(false);
            }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); this.future.cancel(false); }
            catch (Exception failure) { this.future.completeExceptionally(failure); }
            finally { synchronized (BoundedSerialWork.this) { pending.remove(this.key, this); } }
        }
    }
}

package net.sybyline.scarlet;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded, latest-per-key optional work. Never executes a lookup on its caller. */
final class ScarletBackgroundWork
{
    private final int capacity;
    private final Consumer<Throwable> errors;
    private final Map<String, Job> pending = new LinkedHashMap<>();
    private final Map<String, Job> current = new LinkedHashMap<>();
    private final Thread worker;
    private boolean closed;

    ScarletBackgroundWork(String name, int capacity, Consumer<Throwable> errors)
    {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
        this.errors = errors;
        this.worker = new Thread(this::run, name);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    synchronized boolean submit(String key, long delayMillis, BooleanSupplier allowed, Consumer<BooleanSupplier> action)
    {
        if (this.closed) return false;
        if (!this.pending.containsKey(key) && this.pending.size() >= this.capacity)
        {
            // Enrichment is optional. Drop the oldest waiting lookup, never a roster event.
            Iterator<Map.Entry<String, Job>> oldest = this.pending.entrySet().iterator();
            Map.Entry<String, Job> entry = oldest.next();
            this.current.remove(entry.getKey(), entry.getValue());
            oldest.remove();
        }
        Job job = new Job(key, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, delayMillis)), allowed, action);
        this.current.put(key, job); // invalidates a running predecessor without waiting for it
        this.pending.put(key, job);
        this.notifyAll();
        return true;
    }

    synchronized void invalidate(String key)
    {
        this.current.remove(key);
        this.pending.remove(key);
        this.notifyAll();
    }

    /** A silent refresh must not replace an already queued/running live alert. */
    synchronized boolean submitUnlessCurrent(String key, long delayMillis, BooleanSupplier allowed, Consumer<BooleanSupplier> action)
    {
        return !this.current.containsKey(key) && this.submit(key, delayMillis, allowed, action);
    }

    synchronized void reset()
    {
        this.current.clear();
        this.pending.clear();
        this.notifyAll();
    }

    synchronized int pendingCount() { return this.pending.size(); }

    boolean close(long timeoutMillis)
    {
        synchronized (this)
        {
            this.closed = true;
            this.reset();
        }
        this.worker.interrupt();
        if (Thread.currentThread() == this.worker) return false;
        try { this.worker.join(Math.max(1L, timeoutMillis)); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        return !this.worker.isAlive();
    }

    private void run()
    {
        while (true)
        {
            Job job = null;
            synchronized (this)
            {
                while (!this.closed && job == null)
                {
                    long now = System.nanoTime(), wait = Long.MAX_VALUE;
                    for (Job candidate : this.pending.values())
                    {
                        long remaining = candidate.due - now;
                        if (remaining <= 0L) { job = candidate; break; }
                        wait = Math.min(wait, remaining);
                    }
                    if (job != null) this.pending.remove(job.key);
                    else try
                    {
                        if (wait == Long.MAX_VALUE) this.wait();
                        else TimeUnit.NANOSECONDS.timedWait(this, wait);
                    }
                    catch (InterruptedException ex) { if (this.closed) return; }
                }
                if (this.closed) return;
            }
            try
            {
                if (job.getAsBoolean()) job.action.accept(job);
            }
            catch (Exception ex)
            {
                if (this.errors != null) this.errors.accept(ex);
            }
            finally
            {
                synchronized (this) { this.current.remove(job.key, job); }
            }
        }
    }

    private final class Job implements BooleanSupplier
    {
        final String key;
        final long due;
        final BooleanSupplier allowed;
        final Consumer<BooleanSupplier> action;
        Job(String key, long due, BooleanSupplier allowed, Consumer<BooleanSupplier> action)
        { this.key = key; this.due = due; this.allowed = allowed; this.action = action; }
        public boolean getAsBoolean()
        {
            synchronized (ScarletBackgroundWork.this)
            {
                if (closed || current.get(this.key) != this) return false;
            }
            return !Thread.currentThread().isInterrupted() && this.allowed.getAsBoolean();
        }
    }
}

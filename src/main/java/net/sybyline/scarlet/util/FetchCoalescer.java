package net.sybyline.scarlet.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Shares overlapping identical reads only; completed results still belong to the caller's cache. */
public final class FetchCoalescer
{
    private static final class Flight
    {
        final Thread owner = Thread.currentThread();
        final CompletableFuture<Object> result = new CompletableFuture<>();
    }
    private final Map<Object, Flight> active = new HashMap<>();
    private final int limit;
    private final long waitMillis;
    private long generation;

    public FetchCoalescer() { this(64, 30_000); }
    FetchCoalescer(int limit, long waitMillis)
    {
        if (limit < 1 || waitMillis < 1) throw new IllegalArgumentException("Invalid fetch bounds");
        this.limit = limit; this.waitMillis = waitMillis;
    }

    public synchronized void invalidate()
    {
        ++this.generation;
        for (Flight flight : this.active.values()) flight.result.complete(null);
        this.active.clear();
    }

    @SuppressWarnings("unchecked")
    public <T> T fetch(Object key, Supplier<T> request)
    {
        Flight flight;
        boolean leader = false, bypass = false;
        long observed;
        synchronized (this)
        {
            observed = this.generation;
            flight = this.active.get(key);
            if (flight == null)
            {
                if (this.active.size() >= this.limit) bypass = true;
                else { flight = new Flight(); this.active.put(key, flight); leader = true; }
            }
            else if (flight.owner == Thread.currentThread()) bypass = true;
        }
        if (bypass)
        {
            T value = request.get();
            synchronized (this) { return observed == this.generation ? value : null; }
        }
        if (!leader)
        {
            try
            {
                T value = (T)flight.result.get(this.waitMillis, TimeUnit.MILLISECONDS);
                synchronized (this) { return observed == this.generation ? value : null; }
            }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
            catch (TimeoutException timeout) { return null; }
            catch (ExecutionException failure)
            {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException)cause;
                if (cause instanceof Error) throw (Error)cause;
                throw new IllegalStateException(cause);
            }
        }
        try
        {
            T value = request.get();
            synchronized (this)
            {
                if (observed != this.generation) value = null;
                flight.result.complete(value);
                return value;
            }
        }
        catch (RuntimeException | Error failure) { flight.result.completeExceptionally(failure); throw failure; }
        finally { synchronized (this) { this.active.remove(key, flight); } }
    }
}

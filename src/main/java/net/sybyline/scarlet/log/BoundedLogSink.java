package net.sybyline.scarlet.log;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Consumer;

/** One independently draining output; slow destinations never block log producers. */
final class BoundedLogSink
{
    static final int MAX_ENTRY = 32768;
    static final class Entry
    {
        final String text;
        final boolean important, error;
        final java.io.PrintStream stream;
        Entry(String text, boolean important, boolean error) { this(text, important, error, null); }
        Entry(String text, boolean important, boolean error, java.io.PrintStream stream)
        { this.text = text; this.important = important; this.error = error; this.stream = stream; }
    }
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final int capacity, characterBudget;
    private final Thread worker;
    private int characters;
    private long dropped, reported, clipped, written, outputFailures, lastSuccessNanos, lastFailureNanos;
    private boolean stopping, writing;

    BoundedLogSink(String name, int capacity, int characterBudget, Consumer<Entry> output, Runnable finish)
    {
        this.capacity = capacity; this.characterBudget = characterBudget;
        this.worker = new Thread(() -> {
            try
            {
                while (true)
                {
                    Entry entry; long lost;
                    synchronized (this)
                    {
                        while (this.queue.isEmpty() && !this.stopping) this.wait();
                        if (this.queue.isEmpty()) break;
                        entry = this.queue.removeFirst(); this.characters -= entry.text.length();
                        this.writing = true;
                        lost = this.dropped - this.reported;
                    }
                    try
                    {
                        if (lost != 0) try
                        {
                            output.accept(new Entry("[Scarlet logging] " + lost + " entries dropped due to output backlog.\n", true, true));
                            synchronized (this) { this.reported += lost; }
                        }
                        catch (RuntimeException failure) { this.outputFailed(); }
                        try
                        {
                            output.accept(entry);
                            synchronized (this) { this.written++; this.lastSuccessNanos = System.nanoTime(); }
                        }
                        catch (RuntimeException failure) { this.outputFailed(); }
                    }
                    finally { synchronized (this) { this.writing = false; this.notifyAll(); } }
                }
            }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { finish.run(); }
        }, name);
        this.worker.setDaemon(true); this.worker.start();
    }

    synchronized void offer(String text, boolean important, boolean error)
    { this.offer(text, important, error, null); }

    synchronized void offer(String text, boolean important, boolean error, java.io.PrintStream stream)
    {
        if (this.stopping) { this.dropped++; return; }
        int limit = Math.min(MAX_ENTRY, this.characterBudget);
        if (text.length() > limit) { text = text.substring(0, Math.max(0, limit - 32)) + "\n[log entry truncated]\n"; this.clipped++; }
        while (this.queue.size() >= this.capacity || this.characters + text.length() > this.characterBudget)
        {
            Entry removed = null;
            if (important)
            {
                for (Iterator<Entry> it = this.queue.iterator(); it.hasNext(); )
                { Entry candidate = it.next(); if (!candidate.important) { removed = candidate; it.remove(); break; } }
                if (removed == null) removed = this.queue.pollFirst();
            }
            if (removed == null) { this.dropped++; return; }
            this.characters -= removed.text.length(); this.dropped++;
        }
        this.queue.addLast(new Entry(text, important, error, stream)); this.characters += text.length(); this.notifyAll();
    }
    synchronized int size() { return this.queue.size(); }
    synchronized int characters() { return this.characters; }
    synchronized long dropped() { return this.dropped; }
    synchronized long clipped() { return this.clipped; }
    private synchronized void outputFailed()
    {
        this.outputFailures++;
        this.lastFailureNanos = System.nanoTime();
    }
    synchronized Status status()
    {
        return new Status(this.queue.size(), this.written, this.dropped, this.outputFailures,
            this.lastSuccessNanos, this.lastFailureNanos);
    }
    static final class Status
    {
        final int queuedEntries;
        final long writtenEntries, backlogDroppedEntries, outputFailures, lastSuccessNanos, lastFailureNanos;
        Status(int queuedEntries, long writtenEntries, long backlogDroppedEntries, long outputFailures,
            long lastSuccessNanos, long lastFailureNanos)
        {
            this.queuedEntries = queuedEntries;
            this.writtenEntries = writtenEntries;
            this.backlogDroppedEntries = backlogDroppedEntries;
            this.outputFailures = outputFailures;
            this.lastSuccessNanos = lastSuccessNanos;
            this.lastFailureNanos = lastFailureNanos;
        }
    }
    synchronized boolean awaitIdle(long timeoutMillis)
    {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (this.writing || !this.queue.isEmpty())
        {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try { this.wait(Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }
    boolean close(long timeoutMillis)
    {
        synchronized (this) { this.stopping = true; this.notifyAll(); }
        try { this.worker.join(Math.max(1, timeoutMillis)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (!this.worker.isAlive()) return true;
        synchronized (this) { this.dropped += this.queue.size(); this.queue.clear(); this.characters = 0; }
        return false;
    }
}

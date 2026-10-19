package net.sybyline.scarlet;

import java.io.File;
import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Console-only import observations. Does not touch data, credentials, or Swing. */
final class ScarletImportConsole implements ScarletMigration.ImportProgress, AutoCloseable
{
    ScarletImportConsole(PrintStream output)
    {
        this(output, System::nanoTime, true);
    }

    /** Clock and optional timer keep reporting tests independent of wall-clock waits. */
    ScarletImportConsole(PrintStream output, LongSupplier clock, boolean startTimer)
    {
        this.output = Objects.requireNonNull(output);
        this.clock = Objects.requireNonNull(clock);
        this.started = this.lastActivity = this.lastPrinted = clock.getAsLong();
        this.timer = startTimer ? Executors.newSingleThreadScheduledExecutor(task ->
        {
            Thread thread = new Thread(task, "Scarlet-Import-Progress");
            thread.setDaemon(true);
            return thread;
        }) : null;
        if (this.timer != null) this.timer.scheduleAtFixedRate(this::heartbeat, 5, 5, TimeUnit.SECONDS);
    }

    private final PrintStream output;
    private final LongSupplier clock;
    private final ScheduledExecutorService timer;
    private final long started;
    private long lastActivity, lastPrinted;
    private String phase;
    private long completed = -1, total = -1;
    private boolean closed;

    @Override public synchronized void update(String phase, long completed, long total)
    {
        if (this.closed) return;
        long now = this.clock.getAsLong();
        boolean changedPhase = !Objects.equals(this.phase, phase);
        boolean changedCount = this.completed != completed || this.total != total;
        this.phase = phase;
        this.completed = completed;
        this.total = total;
        if (changedPhase || changedCount) this.lastActivity = now;
        boolean finishedStage = changedCount && total >= 0 && completed == total;
        if (changedPhase || finishedStage || now - this.lastPrinted >= TimeUnit.SECONDS.toNanos(1))
            this.print(this.currentStage(), now);
    }

    synchronized void heartbeat()
    {
        if (this.closed || this.phase == null) return;
        long now = this.clock.getAsLong();
        if (now - this.lastActivity < TimeUnit.SECONDS.toNanos(5)
                || now - this.lastPrinted < TimeUnit.SECONDS.toNanos(5)) return;
        long quietSeconds = TimeUnit.NANOSECONDS.toSeconds(now - this.lastActivity);
        this.print(this.currentStage() + " (no new progress update for " + quietSeconds
            + "s; import has not reported completion)", now);
    }

    private String currentStage()
    {
        return this.phase + (this.completed >= 0 && this.total >= 0
            ? ": " + this.completed + " / " + this.total : "");
    }

    private void print(String message, long now)
    {
        this.output.println("[Import] " + message);
        this.output.flush();
        this.lastPrinted = now;
    }

    synchronized void complete(String summary, File backup)
    {
        if (this.closed) return;
        this.close();
        long elapsed = TimeUnit.NANOSECONDS.toSeconds(Math.max(0L, this.clock.getAsLong() - this.started));
        this.output.println("[Import] IMPORT COMPLETE (" + elapsed + "s): " + summary);
        this.output.println("[Import] Safety backup: " + backup);
        this.output.println("[Import] Restart Scarlet to load imported state.");
        this.output.flush();
    }

    synchronized void failed()
    {
        if (this.closed) return;
        this.close();
        this.output.println("[Import] IMPORT FAILED. Keep your backup and check the error details before retrying.");
        this.output.flush();
    }

    @Override public synchronized void close()
    {
        this.closed = true;
        if (this.timer != null) this.timer.shutdownNow();
    }
}

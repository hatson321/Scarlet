package net.sybyline.scarlet;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.scalasbt.ipcsocket.UnixDomainSocket;

/** Owns listener/connection closure, including publication racing with shutdown. */
final class ScarletIpcLifecycle implements AutoCloseable
{
    interface WakeAccept { void wake() throws IOException; }

    private boolean stopped;
    private ServerSocket server;
    private Socket client;
    private WakeAccept wakeAccept;
    private boolean accepting;
    private final long idleMillis, totalMillis;
    private final java.util.concurrent.ScheduledThreadPoolExecutor deadlines;

    ScarletIpcLifecycle() { this(3_000L, 10_000L); }
    ScarletIpcLifecycle(long idleMillis, long totalMillis)
    {
        if (idleMillis <= 0 || totalMillis <= 0) throw new IllegalArgumentException("Invalid IPC deadline");
        this.idleMillis = idleMillis; this.totalMillis = totalMillis;
        this.deadlines = new java.util.concurrent.ScheduledThreadPoolExecutor(1, work -> {
            Thread thread = new Thread(work, "Scarlet IPC deadline"); thread.setDaemon(true); return thread;
        });
        this.deadlines.setRemoveOnCancelPolicy(true);
    }

    synchronized boolean isStopped() { return this.stopped; }

    Socket accept(ServerSocket listener) throws IOException
    {
        synchronized (this)
        {
            if (this.stopped || this.server != listener) throw new java.net.SocketException("IPC stopped");
            this.accepting = true;
        }
        try
        {
            Socket accepted = listener.accept();
            if (!this.publishClient(accepted)) throw new java.net.SocketException("IPC stopped");
            return accepted;
        }
        finally { synchronized (this) { this.accepting = false; this.notifyAll(); } }
    }

    private synchronized boolean isAccepting() { return this.accepting; }

    boolean publishServer(ServerSocket value, WakeAccept wake) throws IOException
    {
        synchronized (this)
        {
            if (!this.stopped)
            {
                this.server = value;
                this.wakeAccept = wake;
                return true;
            }
        }
        value.close();
        return false;
    }

    boolean publishClient(Socket value) throws IOException
    {
        synchronized (this)
        {
            if (!this.stopped) { this.client = value; return true; }
        }
        value.close();
        return false;
    }

    void releaseClient(Socket value) throws IOException
    {
        synchronized (this)
        {
            if (this.client != value) return;
            this.client = null;
        }
        closeConnection(value);
    }

    byte[] readCommandBytes(Socket connection, int maximumBytes) throws IOException
    {
        final long started = System.nanoTime();
        java.util.concurrent.atomic.AtomicLong progress = new java.util.concurrent.atomic.AtomicLong(started);
        java.util.concurrent.atomic.AtomicBoolean reading = new java.util.concurrent.atomic.AtomicBoolean(true), expired = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ScheduledFuture<?> deadline;
        try
        {
            deadline = this.deadlines.scheduleAtFixedRate(() -> {
                long now = System.nanoTime();
                if ((now - started >= TimeUnit.MILLISECONDS.toNanos(this.totalMillis)
                    || now - progress.get() >= TimeUnit.MILLISECONDS.toNanos(this.idleMillis))
                    && reading.compareAndSet(true, false))
                {
                    expired.set(true);
                    try { this.releaseClient(connection); }
                    catch (IOException ignored) { /* The reader also observes expiry before dispatch. */ }
                }
            }, Math.min(50L, this.idleMillis), Math.min(50L, this.idleMillis), TimeUnit.MILLISECONDS);
        }
        catch (java.util.concurrent.RejectedExecutionException stopped) { return null; }
        try
        {
        byte[] buffer = new byte[maximumBytes];
        int length = 0;
        InputStream input = connection.getInputStream();
        // Some native adapters return a zero byte instead of EOF after local close.
        // Never issue another read once shutdown has begun, even on those adapters.
        while (reading.get() && !this.isStopped() && !connection.isClosed() && length < buffer.length)
        {
            final int value;
            try { value = input.read(); }
            catch (IOException failure)
            {
                if (expired.get() || this.isStopped() || connection.isClosed()) return null;
                if ("ReadFile() failed: 109".equals(failure.getMessage())) break;
                throw failure;
            }
            if (value < 0) break;
            buffer[length++] = (byte)value;
            progress.set(System.nanoTime());
        }
        // Claim completion before dispatch; a deadline that won this race owns the request.
        boolean completed = reading.compareAndSet(true, false);
        return !completed || expired.get() || this.isStopped() || connection.isClosed() ? null : Arrays.copyOf(buffer, length);
        }
        finally { reading.set(false); deadline.cancel(false); }
    }

    private static void closeConnection(Socket connection) throws IOException
    {
        if (connection instanceof UnixDomainSocket)
            try { connection.shutdownInput(); }
            catch (IOException ignored) { }
        connection.close();
    }

    @Override public void close() throws IOException
    {
        final ServerSocket listener;
        final Socket connection;
        final WakeAccept wake;
        final boolean pendingAccept;
        synchronized (this)
        {
            if (this.stopped) return;
            this.stopped = true;
            listener = this.server;
            connection = this.client;
            wake = this.wakeAccept;
            pendingAccept = this.accepting;
            this.server = null;
            this.client = null;
            this.wakeAccept = null;
        }
        IOException failure = null;
        this.deadlines.shutdownNow();
        if (connection != null)
        {
            // The native Unix socket retains the descriptor while read() is pending;
            // close() alone therefore does not wake that read. shutdownInput() does.
            try { closeConnection(connection); }
            catch (IOException ex) { failure = ex; }
        }
        if (listener != null)
        {
            // Wake before closing: native accept can publish its handle after close's drain.
            // An empty connection carries neither a token nor a command.
            if (connection == null && wake != null && pendingAccept)
                try { wakeBounded(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    do
                    {
                        try { wake.wake(); return; }
                        catch (IOException failureToConnect)
                        {
                            if (!this.isAccepting()) return;
                            if (System.nanoTime() >= deadline) throw failureToConnect;
                            try { Thread.sleep(10L); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                        }
                    } while (true);
                }); }
                catch (IOException ex) { failure = append(failure, ex); }
            try { listener.close(); }
            catch (IOException ex) { failure = append(failure, ex); }
        }
        if (failure != null) throw failure;
    }

    private static IOException append(IOException first, IOException next)
    {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static void wakeBounded(WakeAccept wake) throws IOException
    {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { wake.wake(); }
            catch (IOException ex) { failure.set(ex); }
            catch (RuntimeException ex) { failure.set(new IOException("IPC wake failed", ex)); }
            finally { finished.countDown(); }
        }, "Scarlet IPC shutdown wake");
        thread.setDaemon(true);
        thread.start();
        try
        {
            if (!finished.await(1_500L, TimeUnit.MILLISECONDS))
                throw new IOException("Timed out waking Scarlet IPC for shutdown");
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waking Scarlet IPC for shutdown", ex);
        }
        if (failure.get() != null) throw failure.get();
    }
}

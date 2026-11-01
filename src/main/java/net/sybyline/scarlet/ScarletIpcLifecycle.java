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

    synchronized boolean isStopped() { return this.stopped; }

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
        value.close();
    }

    byte[] readCommandBytes(Socket connection, int maximumBytes) throws IOException
    {
        byte[] buffer = new byte[maximumBytes];
        int length = 0;
        InputStream input = connection.getInputStream();
        // Some native adapters return a zero byte instead of EOF after local close.
        // Never issue another read once shutdown has begun, even on those adapters.
        while (!this.isStopped() && !connection.isClosed() && length < buffer.length)
        {
            final int value;
            try { value = input.read(); }
            catch (IOException failure)
            {
                if ("ReadFile() failed: 109".equals(failure.getMessage())) break;
                throw failure;
            }
            if (value < 0) break;
            buffer[length++] = (byte)value;
        }
        return this.isStopped() || connection.isClosed() ? null : Arrays.copyOf(buffer, length);
    }

    @Override public void close() throws IOException
    {
        final ServerSocket listener;
        final Socket connection;
        final WakeAccept wake;
        synchronized (this)
        {
            if (this.stopped) return;
            this.stopped = true;
            listener = this.server;
            connection = this.client;
            wake = this.wakeAccept;
            this.server = null;
            this.client = null;
            this.wakeAccept = null;
        }
        IOException failure = null;
        if (connection != null)
        {
            // The native Unix socket retains the descriptor while read() is pending;
            // close() alone therefore does not wake that read. shutdownInput() does.
            if (connection instanceof UnixDomainSocket)
                try { connection.shutdownInput(); }
                catch (IOException ignored) { /* A disconnected peer may already have ended the read. */ }
            try { connection.close(); }
            catch (IOException ex) { failure = ex; }
        }
        if (listener != null)
        {
            // Native Unix accept() is not interrupted by close(fd) on another thread.
            // An empty local connection wakes it; no token or command is ever sent.
            if (connection == null && wake != null)
                try { wakeBounded(wake); }
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

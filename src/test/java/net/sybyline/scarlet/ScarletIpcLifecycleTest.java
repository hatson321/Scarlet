package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scalasbt.ipcsocket.UnixDomainServerSocket;
import org.scalasbt.ipcsocket.UnixDomainSocket;

class ScarletIpcLifecycleTest
{
    @TempDir Path temp;

    interface Connect { Socket open() throws Exception; }
    static final class Endpoint
    {
        final ServerSocket server;
        final Connect connect;
        final ScarletIpcLifecycle.WakeAccept wake;
        Endpoint(ServerSocket server, Connect connect, ScarletIpcLifecycle.WakeAccept wake)
        { this.server = server; this.connect = connect; this.wake = wake; }
    }

    private Endpoint tcp() throws IOException
    {
        ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        return new Endpoint(server, () -> new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort()), null);
    }

    private Endpoint nativeEndpoint() throws Exception
    {
        if (System.getProperty("os.name").startsWith("Windows"))
        {
            String pipe = "\\\\.\\pipe\\ScarletLifecycleTest-" + UUID.randomUUID();
            Class<?> serverType = Class.forName("org.scalasbt.ipcsocket.Win32NamedPipeServerSocket");
            int security = Class.forName("org.scalasbt.ipcsocket.Win32SecurityLevel").getField("OWNER_DACL").getInt(null);
            ServerSocket server = (ServerSocket)serverType.getConstructor(String.class, boolean.class, int.class)
                .newInstance(pipe, false, security);
            return new Endpoint(server, () -> (Socket)Class.forName("org.scalasbt.ipcsocket.Win32NamedPipeSocket")
                .getConstructor(String.class, boolean.class).newInstance(pipe, false), null);
        }
        String path = temp.resolve("ipc.sock").toString();
        ServerSocket server = new UnixDomainServerSocket(path, false);
        return new Endpoint(server, () -> new UnixDomainSocket(path, false),
            () -> { try (Socket wake = new UnixDomainSocket(path, false)) { } });
    }

    @Test void tcpBlockingAcceptStops() throws Exception { blockingAccept(tcp()); }
    @Test void actualPlatformNativeBlockingAcceptStops() throws Exception { blockingAccept(nativeEndpoint()); }
    @Test void tcpPartialReadStopsWithoutDispatch() throws Exception { partialRead(tcp()); }
    @Test void actualPlatformNativePartialReadStopsWithoutDispatch() throws Exception { partialRead(nativeEndpoint()); }

    private void blockingAccept(Endpoint endpoint) throws Exception
    {
        ScarletIpcLifecycle owner = new ScarletIpcLifecycle();
        assertTrue(owner.publishServer(endpoint.server, endpoint.wake));
        AtomicInteger dispatched = new AtomicInteger();
        FutureTask<Void> task = new FutureTask<>(() -> {
            Socket accepted = null;
            try
            {
                accepted = endpoint.server.accept();
                if (owner.publishClient(accepted) && !owner.isStopped()) dispatched.incrementAndGet();
            }
            catch (IOException | IllegalStateException failure) { if (!owner.isStopped()) throw failure; }
            finally { if (accepted != null) owner.releaseClient(accepted); }
            return null;
        });
        Thread thread = start(task);
        try
        {
            awaitStack(thread, "accept");
            owner.close();
            task.get(3, TimeUnit.SECONDS);
            assertEquals(0, dispatched.get(), "The empty shutdown wake must never become an IPC command");
        }
        finally { owner.close(); }
    }

    private void partialRead(Endpoint endpoint) throws Exception
    {
        ScarletIpcLifecycle owner = new ScarletIpcLifecycle();
        assertTrue(owner.publishServer(endpoint.server, endpoint.wake));
        AtomicInteger dispatched = new AtomicInteger();
        CountDownLatch published = new CountDownLatch(1);
        FutureTask<Void> task = new FutureTask<>(() -> {
            Socket accepted = null;
            try
            {
                accepted = endpoint.server.accept();
                if (!owner.publishClient(accepted)) return null;
                published.countDown();
                byte[] command = owner.readCommandBytes(accepted, 65536);
                assertNull(command, "The production reader must discard the partial command when stopped");
                if (command != null) dispatched.incrementAndGet();
            }
            catch (IOException | IllegalStateException failure) { if (!owner.isStopped()) throw failure; }
            finally { if (accepted != null) owner.releaseClient(accepted); }
            return null;
        });
        Thread thread = start(task);
        try (Socket peer = connectWhenListening(endpoint, thread))
        {
            assertTrue(published.await(3, TimeUnit.SECONDS));
            peer.getOutputStream().write("synthetic partial command".getBytes(StandardCharsets.UTF_8));
            peer.getOutputStream().flush();
            awaitStack(thread, "read");
            owner.close();
            task.get(3, TimeUnit.SECONDS);
            assertEquals(0, dispatched.get(), "Stopping a partial read must not dispatch the incomplete command");
        }
        finally { owner.close(); }
    }

    private static Socket connectWhenListening(Endpoint endpoint, Thread listener) throws Exception
    {
        awaitStack(listener, "accept");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true)
        {
            try { return endpoint.connect.open(); }
            catch (Exception failure)
            {
                Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException
                    ? failure.getCause() : failure;
                // The native Windows server creates its pipe inside accept(), after
                // entering that method. ERROR_FILE_NOT_FOUND (2) is a startup race,
                // not a shutdown defect; all other connection errors remain failures.
                boolean notCreatedYet = System.getProperty("os.name").startsWith("Windows")
                    && cause instanceof IOException
                    && String.valueOf(cause.getMessage()).contains("Couldn't open pipe (2)");
                if (!notCreatedYet || !listener.isAlive() || System.nanoTime() >= deadline) throw failure;
                Thread.sleep(10L);
            }
        }
    }

    @Test void latePublicationAndRepeatedCloseNeverLeakOrDoubleCloseResources() throws Exception
    {
        ScarletIpcLifecycle owner = new ScarletIpcLifecycle();
        owner.close();
        TrackingServer server = new TrackingServer();
        TrackingSocket socket = new TrackingSocket();
        assertFalse(owner.publishServer(server, null));
        assertFalse(owner.publishClient(socket));
        owner.releaseClient(socket);
        owner.close();
        assertEquals(1, server.closed.get());
        assertEquals(1, socket.closed.get());
    }

    @Test void stopRacingPublicationClosesEachResourceExactlyOnce() throws Exception
    {
        for (int i = 0; i < 30; i++)
        {
            ScarletIpcLifecycle owner = new ScarletIpcLifecycle();
            TrackingServer server = new TrackingServer();
            TrackingSocket socket = new TrackingSocket();
            CountDownLatch start = new CountDownLatch(1);
            FutureTask<Void> publisher = new FutureTask<>(() -> {
                start.await(); owner.publishServer(server, null); owner.publishClient(socket); return null;
            });
            FutureTask<Void> stopper = new FutureTask<>(() -> { start.await(); owner.close(); return null; });
            start(publisher); start(stopper); start.countDown();
            publisher.get(3, TimeUnit.SECONDS); stopper.get(3, TimeUnit.SECONDS);
            owner.releaseClient(socket); owner.close();
            assertTrue(owner.isStopped());
            assertEquals(1, server.closed.get());
            assertEquals(1, socket.closed.get());
        }
    }

    private static Thread start(FutureTask<Void> task)
    {
        Thread thread = new Thread(task, "Synthetic IPC lifecycle test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void awaitStack(Thread thread, String method) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline)
        {
            if (Arrays.stream(thread.getStackTrace()).anyMatch(frame -> method.equals(frame.getMethodName()))) return;
            if (!thread.isAlive()) break;
            Thread.sleep(5L);
        }
        fail("Synthetic IPC worker did not enter blocking " + method);
    }

    private static final class TrackingServer extends ServerSocket
    {
        final AtomicInteger closed = new AtomicInteger();
        TrackingServer() throws IOException { }
        @Override public void close() throws IOException { closed.incrementAndGet(); super.close(); }
    }
    private static final class TrackingSocket extends Socket
    {
        final AtomicInteger closed = new AtomicInteger();
        @Override public void close() throws IOException { closed.incrementAndGet(); super.close(); }
    }
}

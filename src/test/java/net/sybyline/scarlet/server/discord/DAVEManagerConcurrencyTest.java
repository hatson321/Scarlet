package net.sybyline.scarlet.server.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;

import org.junit.jupiter.api.Test;
import moe.kyokobot.libdave.*;
import moe.kyokobot.libdave.jda.internal.DAVEManager;

/** Exercises the preferred adapter using Java-only handles, never loading native libraries. */
class DAVEManagerConcurrencyTest
{
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler)
    {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    static final class Fixture
    {
        final AtomicInteger creations = new AtomicInteger(), sessionCloses = new AtomicInteger(),
            encryptorCloses = new AtomicInteger(), decryptorCloses = new AtomicInteger(), postCloseCalls = new AtomicInteger();
        volatile boolean closed;
        boolean failSessionClose;
        final Session session = proxy(Session.class, (p, m, a) -> {
            if (m.getName().equals("close")) {
                sessionCloses.incrementAndGet(); closed = true;
                if (failSessionClose) throw new IllegalStateException("Synthetic session cleanup failure");
                return null;
            }
            if (closed) postCloseCalls.incrementAndGet();
            if (m.getReturnType() == int.class) return 0;
            if (m.getReturnType() == boolean.class) return false;
            return null;
        });
        final Encryptor encryptor = proxy(Encryptor.class, (p, m, a) -> {
            if (m.getName().equals("close")) { encryptorCloses.incrementAndGet(); return null; }
            if (closed) postCloseCalls.incrementAndGet();
            if (m.getReturnType() == int.class) return 0;
            return null;
        });
        final DaveFactory factory = proxy(DaveFactory.class, (p, m, a) -> {
            switch (m.getName()) {
            case "createSession": return session;
            case "createEncryptor": return encryptor;
            case "maxSupportedProtocolVersion": return 1;
            case "createDecryptor":
                creations.incrementAndGet();
                if (closed) postCloseCalls.incrementAndGet();
                return proxy(Decryptor.class, (d, operation, values) -> {
                    if (operation.getName().equals("close")) { decryptorCloses.incrementAndGet(); return null; }
                    if (closed) postCloseCalls.incrementAndGet();
                    if (operation.getReturnType() == int.class) return 0;
                    return null;
                });
            default: throw new AssertionError(m.getName());
            }
        });
    }

    static class GateLock extends StampedLock
    {
        private static final long serialVersionUID = 1L;
        final CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public long writeLock()
        {
            if (Thread.currentThread().getName().equals("synthetic-delayed-user"))
            {
                waiting.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test lock release timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
            }
            return super.writeLock();
        }
    }

    @Test void userThatPassedOuterClosedCheckCannotAllocateAfterCloseWinsLock() throws Exception
    {
        Fixture fixture = new Fixture();
        DAVEManager manager = new DAVEManager(fixture.factory, 1, 2, null);
        GateLock lock = new GateLock();
        Field field = DAVEManager.class.getDeclaredField("sessionLock");
        field.setAccessible(true);
        field.set(manager, lock);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread delayed = new Thread(() -> {
            try { manager.addUser(3); }
            catch (Throwable ex) { failure.set(ex); }
        }, "synthetic-delayed-user");
        delayed.start();
        try
        {
            assertTrue(lock.waiting.await(5, TimeUnit.SECONDS));
            manager.close();
        }
        finally { lock.release.countDown(); delayed.join(5000); }
        assertFalse(delayed.isAlive());
        assertNull(failure.get());
        assertEquals(0, fixture.creations.get());
        assertEquals(0, fixture.postCloseCalls.get());
        assertEquals(1, fixture.sessionCloses.get());
        assertEquals(1, fixture.encryptorCloses.get());
    }

    @Test void repeatedCloseAndLateCallbacksNeverReuseClosedNativeObjects() throws Exception
    {
        Fixture fixture = new Fixture();
        DAVEManager manager = new DAVEManager(fixture.factory, 1, 2, null);
        manager.addUser(3);
        manager.close();
        manager.close();
        manager.addUser(4);
        manager.removeUser(3);
        manager.onSelectProtocolAck(1);
        manager.onDaveProtocolPrepareTransition(1, 1);
        manager.onDaveProtocolExecuteTransition(1);
        manager.onDaveProtocolPrepareEpoch(1, 1);
        manager.onDaveProtocolMLSExternalSenderPackage(java.nio.ByteBuffer.allocate(0));
        manager.onMLSProposals(java.nio.ByteBuffer.allocate(0));
        manager.onMLSPrepareCommitTransition(1, java.nio.ByteBuffer.allocate(0));
        manager.onMLSWelcome(1, java.nio.ByteBuffer.allocate(0));
        assertEquals(1, fixture.creations.get());
        assertEquals(1, fixture.decryptorCloses.get());
        assertEquals(1, fixture.encryptorCloses.get());
        assertEquals(1, fixture.sessionCloses.get());
        assertEquals(0, fixture.postCloseCalls.get());
    }

    @Test void aFailingSessionCleanupDoesNotSkipOtherResourcesOrRepeatDestruction()
    {
        Fixture fixture = new Fixture();
        DAVEManager manager = new DAVEManager(fixture.factory, 1, 2, null);
        manager.addUser(3);
        fixture.failSessionClose = true;
        assertThrows(IllegalStateException.class, manager::close);
        assertDoesNotThrow(manager::close);
        manager.addUser(4);
        assertEquals(1, fixture.sessionCloses.get());
        assertEquals(1, fixture.encryptorCloses.get());
        assertEquals(1, fixture.decryptorCloses.get());
        assertEquals(1, fixture.creations.get());
        assertEquals(0, fixture.postCloseCalls.get());
    }
}

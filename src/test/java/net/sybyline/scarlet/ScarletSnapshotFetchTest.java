package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

class ScarletSnapshotFetchTest
{
    static final String USER = ScarletSnapshotPrivacyTest.USER;

    static ScarletSnapshotFetch.Read read(JsonElement value)
    {
        return new ScarletSnapshotFetch.Read() {
            public JsonElement read() { return value; }
            public void cancel() {}
        };
    }

    @Test void allThreeReadsOverlapAndOnlyPublicFieldsLeaveWorkers() throws Exception
    {
        ScarletSnapshotFetch fetch = new ScarletSnapshotFetch();
        CountDownLatch started = new CountDownLatch(3);
        AtomicInteger peak = new AtomicInteger(), active = new AtomicInteger();
        JsonArray groups = new JsonArray(); groups.add(ScarletSnapshotPrivacyTest.group());
        JsonElement[] payloads = {ScarletSnapshotPrivacyTest.self(), groups, ScarletSnapshotPrivacyTest.group()};
        ScarletSnapshotFetch.Read[] reads = new ScarletSnapshotFetch.Read[3];
        for (int i = 0; i < 3; i++)
        {
            JsonElement payload = payloads[i];
            reads[i] = new ScarletSnapshotFetch.Read() {
                public JsonElement read() throws Exception {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    started.countDown();
                    try { assertTrue(started.await(2, TimeUnit.SECONDS), "All lookups must overlap"); return payload; }
                    finally { active.decrementAndGet(); }
                }
                public void cancel() {}
            };
        }
        try
        {
            ScarletSnapshotFetch.Result result = fetch.fetch(USER, () -> true, reads);
            assertNotNull(result);
            assertEquals(3, peak.get());
            ScarletSnapshotPrivacyTest.assertPublic(result.user);
            assertEquals(1, result.groups.size());
            assertFalse(result.groups.toString().contains("PRIVATE_"));
            assertFalse(result.representedGroup.toString().contains("PRIVATE_"));
        }
        finally { assertTrue(fetch.shutdown()); }
    }

    @Test void endpointFailureKeepsOtherProjectedDataWithoutRetainingExceptionBody()
    {
        ScarletSnapshotFetch fetch = new ScarletSnapshotFetch();
        try
        {
            ScarletSnapshotFetch.Result result = fetch.fetch(USER, () -> true,
                read(ScarletSnapshotPrivacyTest.self()), new ScarletSnapshotFetch.Read() {
                    public JsonElement read() throws Exception { throw new IOException("PRIVATE_RESPONSE_BODY"); }
                    public void cancel() {}
                }, read(ScarletSnapshotPrivacyTest.group()));
            assertNotNull(result);
            ScarletSnapshotPrivacyTest.assertPublic(result.user);
            assertNull(result.groups);
            assertNotNull(result.representedGroup);
        }
        finally { assertTrue(fetch.shutdown()); }
    }

    @Test void aggregateDeadlineCancelsCallsBeforeInterruptingWorkers() throws Exception
    {
        ScarletSnapshotFetch fetch = new ScarletSnapshotFetch();
        AtomicInteger cancelledBeforeInterrupt = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(3);
        ScarletSnapshotFetch.Read[] reads = new ScarletSnapshotFetch.Read[3];
        for (int i = 0; i < 3; i++) reads[i] = new ScarletSnapshotFetch.Read() {
            final AtomicBoolean cancelled = new AtomicBoolean();
            public JsonElement read() throws Exception {
                try { new CountDownLatch(1).await(); return ScarletSnapshotPrivacyTest.self(); }
                catch (InterruptedException ex) { if (cancelled.get()) cancelledBeforeInterrupt.incrementAndGet(); throw ex; }
                finally { finished.countDown(); }
            }
            public void cancel() { cancelled.set(true); }
        };
        long before = System.nanoTime();
        try
        {
            assertNull(fetch.fetch(USER, () -> true, 200L, reads));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2_000L);
            assertTrue(finished.await(2L, TimeUnit.SECONDS));
            assertEquals(3, cancelledBeforeInterrupt.get());
        }
        finally { assertTrue(fetch.shutdown()); }
    }

    @Test void interruptionAndShutdownDoNotAllowLateResultsOrNewWork() throws Exception
    {
        ScarletSnapshotFetch fetch = new ScarletSnapshotFetch();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ScarletSnapshotFetch.Result> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        ScarletSnapshotFetch.Read hanging = new ScarletSnapshotFetch.Read() {
            public JsonElement read() throws Exception { started.countDown(); new CountDownLatch(1).await(); return null; }
            public void cancel() {}
        };
        Thread caller = new Thread(() -> {
            result.set(fetch.fetch(USER, () -> true, hanging, hanging, hanging));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertTrue(started.await(2L, TimeUnit.SECONDS));
        caller.interrupt(); caller.join(2_000L);
        assertFalse(caller.isAlive()); assertTrue(interrupted.get()); assertNull(result.get());
        assertTrue(fetch.shutdown());
        AtomicInteger calls = new AtomicInteger();
        ScarletSnapshotFetch.Read unexpected = new ScarletSnapshotFetch.Read() {
            public JsonElement read() { calls.incrementAndGet(); return null; }
            public void cancel() {}
        };
        assertNull(fetch.fetch(USER, () -> true, unexpected, unexpected, unexpected));
        assertEquals(0, calls.get());
        assertTrue(fetch.shutdown());
    }

    @Test void admissionIsBoundedToThreeBatchesAndThreeWorkers() throws Exception
    {
        ScarletSnapshotFetch fetch = new ScarletSnapshotFetch();
        CountDownLatch started = new CountDownLatch(3), release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger();
        ScarletSnapshotFetch.Read hanging = new ScarletSnapshotFetch.Read() {
            public JsonElement read() throws Exception {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max); started.countDown();
                try { release.await(); return null; } finally { running.decrementAndGet(); }
            }
            public void cancel() {}
        };
        Thread[] callers = new Thread[3];
        try
        {
            for (int i = 0; i < 3; i++)
            {
                callers[i] = new Thread(() -> fetch.fetch(USER, () -> true, hanging, hanging, hanging));
                callers[i].start();
            }
            assertTrue(started.await(2, TimeUnit.SECONDS));
            // Every competing request either has bounded admission or returns promptly.
            AtomicInteger fourthCalls = new AtomicInteger();
            ScarletSnapshotFetch.Read fourth = new ScarletSnapshotFetch.Read() {
                public JsonElement read() { fourthCalls.incrementAndGet(); return null; }
                public void cancel() {}
            };
            long before = System.nanoTime();
            assertNull(fetch.fetch(USER, () -> true, 100L, fourth, fourth, fourth));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1_000L);
            assertEquals(0, fourthCalls.get()); assertEquals(3, peak.get());
        }
        finally
        {
            release.countDown(); assertTrue(fetch.shutdown());
            for (Thread caller : callers) if (caller != null) caller.join(2_000L);
        }
    }
}

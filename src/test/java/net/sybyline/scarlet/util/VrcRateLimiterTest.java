package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VrcRateLimiterTest
{
    static final class Clock
    {
        final AtomicLong now = new AtomicLong(10_000L);
        final List<Long> sleeps = new ArrayList<>();

        void sleep(long milliseconds)
        {
            sleeps.add(milliseconds);
            now.addAndGet(milliseconds);
        }

        VrcRateLimiter limiter(double rate, double burst)
        {
            return new VrcRateLimiter(rate, burst, now::get, this::sleep);
        }
    }

    @Test void normalBucketStillAllowsItsConfiguredBurst() throws Exception
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(2, 2);
        limiter.acquire();
        limiter.acquire();
        assertEquals(10_000L, clock.now.get());
        limiter.acquire();
        assertEquals(10_500L, clock.now.get());
    }

    @Test void historicalAuditRateIsOnePermitEveryTwoSeconds() throws Exception
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(0.5, 1);
        limiter.acquire();
        limiter.acquire();
        limiter.acquire();
        assertEquals(14_000L, clock.now.get());
        assertTrue(clock.sleeps.stream().allMatch(ms -> ms <= 1_000L));
    }

    @Test void missingZeroNegativeAndShortHintsAlwaysWaitAtLeastThirtySeconds()
    {
        for (long hint : new long[] {-1L, 0L, 1L, 1_000L, 29_999L})
        {
            Clock clock = new Clock();
            VrcRateLimiter limiter = clock.limiter(10, 20);
            assertEquals(30_000L, limiter.penalize(hint));
            assertEquals(30_000L, limiter.backoffRemainingMs());
            assertTrue(limiter.isBackingOff());
            assertEquals(clock.now.get(), limiter.lastPenaltyMillis());
        }
    }

    @Test void repeatedPenaltiesEscalateButPolicyStopsAtFiveMinutes()
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(10, 20);
        for (long expected : new long[] {30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L})
            assertEquals(expected, limiter.penalize(0));
    }

    @Test void tenQuietMinutesAfterCooldownResetEscalation()
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(10, 20);
        limiter.penalize(0);
        clock.now.addAndGet(30_000L + 600_000L);
        assertEquals(30_000L, limiter.penalize(0));
    }

    @Test void serverHintsAreNotCappedAndShorterPenaltiesCannotShortenThem()
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(10, 20);
        assertEquals(900_000L, limiter.penalize(900_000L));
        clock.now.addAndGet(1_000L);
        assertEquals(899_000L, limiter.penalize(0));
    }

    @Test void hugeServerHintCannotOverflowIntoAnExpiredCooldown()
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(10, 20);
        assertEquals(Long.MAX_VALUE - clock.now.get(), limiter.penalize(Long.MAX_VALUE));
        assertTrue(limiter.isBackingOff());
    }

    @Test void cooldownDoesNotRefillATwentyRequestBurst() throws Exception
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(10, 20);
        limiter.penalize(0);
        clock.now.addAndGet(30_000L);
        limiter.acquire();
        assertEquals(40_100L, clock.now.get());
        limiter.acquire();
        assertEquals(40_200L, clock.now.get());
        assertFalse(limiter.isBackingOff());
    }

    @Test void interruptionOfTokenWaitFailsInsteadOfAllowingTheRequest() throws Exception
    {
        AtomicLong now = new AtomicLong(10_000L);
        VrcRateLimiter limiter = new VrcRateLimiter(1, 1, now::get,
            ms -> { throw new InterruptedException("Synthetic wait cancellation"); });
        limiter.acquire();
        try
        {
            InterruptedIOException failure = assertThrows(InterruptedIOException.class, limiter::acquire);
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally { Thread.interrupted(); }
    }

    @Test void firstLateCallerAfterCooldownCannotSpendARefilledBurst() throws Exception
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(10, 20);
        limiter.penalize(0);
        clock.now.addAndGet(90_000L);
        limiter.acquire();
        assertEquals(100_100L, clock.now.get());
        limiter.acquire();
        assertEquals(100_200L, clock.now.get());
    }

    @Test void interruptionOfCooldownWaitFailsInsteadOfAllowingTheRequest()
    {
        AtomicLong now = new AtomicLong(10_000L);
        VrcRateLimiter limiter = new VrcRateLimiter(10, 20, now::get,
            ms -> { throw new InterruptedException("Synthetic cooldown cancellation"); });
        limiter.penalize(0);
        try
        {
            assertThrows(InterruptedIOException.class, limiter::acquire);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(30_000L, limiter.backoffRemainingMs());
        }
        finally { Thread.interrupted(); }
    }

    @Test void alreadyInterruptedThreadCannotUseEvenAnAvailablePermit()
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(10, 20);
        Thread.currentThread().interrupt();
        try
        {
            assertThrows(InterruptedIOException.class, limiter::acquire);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(clock.sleeps.isEmpty());
        }
        finally { Thread.interrupted(); }
    }

    @Test void activeWaitNoticesAnExtendedCooldown() throws Exception
    {
        Clock clock = new Clock();
        VrcRateLimiter[] holder = new VrcRateLimiter[1];
        holder[0] = new VrcRateLimiter(10, 20, clock.now::get, ms -> {
            if (clock.sleeps.isEmpty())
                holder[0].penalize(120_000L);
            clock.sleep(ms);
        });
        holder[0].penalize(0);
        holder[0].acquire();
        assertEquals(130_100L, clock.now.get());
    }

    @Test void concurrentPenaltiesRetainTheLongestDeadline() throws Exception
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(10, 20);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> pending = new ArrayList<>();
        try
        {
            for (int i = 0; i < 32; i++)
            {
                final long hint = i == 0 ? 900_000L : 1_000L;
                pending.add(workers.submit(() -> {
                    start.await();
                    limiter.penalize(hint);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : pending)
                task.get(5, TimeUnit.SECONDS);
            assertEquals(900_000L, limiter.backoffRemainingMs());
        }
        finally
        {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void expiredDeadlineDoesNotConsumeAnAvailablePermit() throws Exception
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(1, 1);
        assertThrows(InterruptedIOException.class, () -> limiter.acquireUntil(clock.now.get()));
        assertFalse(Thread.currentThread().isInterrupted());
        limiter.acquire();
        assertTrue(clock.sleeps.isEmpty());
    }

    @Test void deadlineBoundsTheLastSleepWithoutOvershoot() throws Exception
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(0.5, 1);
        limiter.acquire();
        assertThrows(InterruptedIOException.class, () -> limiter.acquireUntil(11_250L));
        assertEquals(11_250L, clock.now.get());
        assertEquals(java.util.Arrays.asList(1_000L, 250L), clock.sleeps);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test void callerDeadlineDoesNotShortenAServerCooldown()
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(10, 20);
        limiter.penalize(900_000L);
        assertThrows(InterruptedIOException.class, () -> limiter.acquireUntil(250_000L));
        assertEquals(250_000L, clock.now.get());
        assertEquals(660_000L, limiter.backoffRemainingMs());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test void permitCanStillBeObtainedBeforeTheDeadline() throws Exception
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(0.5, 1);
        limiter.acquire();
        limiter.acquireUntil(12_001L);
        assertEquals(12_000L, clock.now.get());
    }

    @Test void cancellationDuringLongCooldownStopsWithinOneSleepChunk()
    {
        Clock clock = new Clock();
        AtomicBoolean cancelled = new AtomicBoolean();
        VrcRateLimiter limiter = new VrcRateLimiter(10, 20, clock.now::get, ms -> {
            clock.sleep(ms);
            cancelled.set(true);
        });
        limiter.penalize(900_000L);
        assertThrows(InterruptedIOException.class,
            () -> limiter.acquireUntil(Long.MAX_VALUE, cancelled::get));
        assertEquals(11_000L, clock.now.get());
        assertEquals(899_000L, limiter.backoffRemainingMs());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test void cancellationDoesNotGrantOrConsumeAnAvailablePermit() throws Exception
    {
        Clock clock = new Clock();
        VrcRateLimiter limiter = clock.limiter(0.5, 1);
        assertThrows(InterruptedIOException.class,
            () -> limiter.acquireUntil(Long.MAX_VALUE, () -> true));
        assertFalse(Thread.currentThread().isInterrupted());
        limiter.acquire();
        assertTrue(clock.sleeps.isEmpty());
    }
}

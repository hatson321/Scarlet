package net.sybyline.scarlet.util;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Synthetic clock bridge for transport tests; never included in the release JAR. */
public final class RateLimiterTestClock
{
    private RateLimiterTestClock() { }

    public static VrcRateLimiter create(double rate, double burst, LongSupplier clock, LongConsumer advance)
    {
        return new VrcRateLimiter(rate, burst, clock, advance::accept);
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScarletStartupFailureLoopTest
{
    static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0);
    static long seconds(long seconds) { return TimeUnit.SECONDS.toNanos(seconds); }
    static void cycle(ScarletStartupFailureLoop loop, long second)
    {
        loop.accept(false, BASE.plusSeconds(second), "Debug", ScarletStartupFailureLoop.FAILURE, seconds(second));
        loop.accept(false, BASE.plusSeconds(second), "Debug", ScarletStartupFailureLoop.RESET, seconds(second));
    }
    @Test void threeDistinctCyclesAreRequiredAndEvidenceExpires()
    {
        ScarletStartupFailureLoop loop = new ScarletStartupFailureLoop();
        cycle(loop, 10); cycle(loop, 45); assertFalse(loop.snapshot().repeated(seconds(45)));
        cycle(loop, 80); assertTrue(loop.snapshot().repeated(seconds(80)));
        assertTrue(loop.snapshot().repeated(seconds(125)));
        assertFalse(loop.snapshot().repeated(seconds(126)));
        assertFalse(loop.snapshot().repeated(seconds(79)));
    }
    @Test void duplicatedResetUnpairedFailureAndGenericErrorDoNotCount()
    {
        ScarletStartupFailureLoop loop = new ScarletStartupFailureLoop();
        loop.accept(false, BASE, "Debug", ScarletStartupFailureLoop.RESET, 0);
        loop.accept(false, BASE, "Error", "Failed to connect to network region", 0);
        assertEquals(0, loop.snapshot().count);
        cycle(loop, 10);
        loop.accept(false, BASE.plusSeconds(40), "Debug", ScarletStartupFailureLoop.RESET, seconds(40));
        assertEquals(1, loop.snapshot().count);
    }
    @Test void preambleWrongLevelAndAlteredMessagesNeverCount()
    {
        for (int kind = 0; kind < 4; kind++)
        {
            ScarletStartupFailureLoop loop = new ScarletStartupFailureLoop();
            for (int second : new int[]{10,45,80})
            {
                String prefix = kind == 2 ? "[Udon] " : "";
                String suffix = kind == 3 ? " extra" : "";
                loop.accept(kind == 0, BASE.plusSeconds(second), kind == 1 ? "Error" : "Debug", prefix + ScarletStartupFailureLoop.FAILURE + suffix, seconds(second));
                loop.accept(kind == 0, BASE.plusSeconds(second), kind == 1 ? "Error" : "Debug", prefix + ScarletStartupFailureLoop.RESET + suffix, seconds(second));
            }
            assertFalse(loop.snapshot().repeated(seconds(80)), "kind=" + kind);
        }
    }
    @Test void replayedTimestampsOrRapidBacklogCannotLookLikeLiveCycles()
    {
        for (boolean replay : new boolean[]{false,true})
        {
            ScarletStartupFailureLoop loop = new ScarletStartupFailureLoop();
            for (int n = 0; n < 3; n++)
            {
                LocalDateTime stamp = BASE.plusSeconds(replay ? 0 : n * 35);
                long now = seconds(replay ? n * 35 : 1);
                loop.accept(false, stamp, "Debug", ScarletStartupFailureLoop.FAILURE, now);
                loop.accept(false, stamp, "Debug", ScarletStartupFailureLoop.RESET, now);
            }
            assertEquals(1, loop.snapshot().count);
        }
    }
    @Test void distantOrReversedPairsDoNotCount()
    {
        for (int kind = 0; kind < 4; kind++)
        {
            ScarletStartupFailureLoop loop = new ScarletStartupFailureLoop();
            loop.accept(false, BASE.plusSeconds(10), "Debug", ScarletStartupFailureLoop.FAILURE, seconds(10));
            loop.accept(false, BASE.plusSeconds(kind == 0 ? 9 : kind == 1 ? 16 : 10), "Debug", ScarletStartupFailureLoop.RESET,
                seconds(kind == 2 ? 9 : kind == 3 ? 16 : 10));
            assertEquals(0, loop.snapshot().count);
        }
    }
    @Test void staleCyclesFallOutOfBoundedWindow()
    {
        ScarletStartupFailureLoop loop = new ScarletStartupFailureLoop();
        cycle(loop, 10); cycle(loop, 90); cycle(loop, 170);
        assertFalse(loop.snapshot().repeated(seconds(170)));
        cycle(loop, 205); assertTrue(loop.snapshot().repeated(seconds(205)));
        cycle(loop, 400); assertEquals(1, loop.snapshot().count);
    }
    @Test void positiveConnectionProgressClearsLoop()
    {
        for (String message : new String[]{"[Behaviour] OnConnectedToMaster", "[Behaviour] Successfully joined room", "[Behaviour] Finished entering world."})
        {
            ScarletStartupFailureLoop loop = new ScarletStartupFailureLoop();
            cycle(loop, 10); cycle(loop, 45); cycle(loop, 80);
            loop.accept(false, BASE.plusSeconds(81), "Debug", message, seconds(81));
            assertFalse(loop.snapshot().repeated(seconds(81)));
            cycle(loop, 115); assertEquals(1, loop.snapshot().count);
        }
    }
    @Test void ordinaryBackgroundMessagesDoNotHideConfirmedFailures()
    {
        ScarletStartupFailureLoop loop = new ScarletStartupFailureLoop();
        cycle(loop, 10); cycle(loop, 45); cycle(loop, 80);
        loop.accept(false, BASE.plusSeconds(81), "Debug", "[Behaviour] Loading scene asynchronously", seconds(81));
        assertTrue(loop.snapshot().repeated(seconds(81)));
    }
}

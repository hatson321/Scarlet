package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletTailerHealth.Change.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ScarletTailerHealthTest
{
    private static final long START = 1_800_000_000_000L;
    private static final long T = TimeUnit.HOURS.toNanos(1);
    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);
    private final Object log = new Object();
    private final ScarletTailerHealth.Clients clients = clients("17", START);
    private final ScarletTailerHealth health = new ScarletTailerHealth();

    private static ScarletTailerHealth.Clients clients(String pid, long start)
    {
        return new ScarletTailerHealth.Clients(Map.of(pid, Instant.ofEpochMilli(start)));
    }

    private ScarletTailerHealth.Reader healthy(long time)
    {
        return new ScarletTailerHealth.Reader(this.log, START, time, null);
    }

    private ScarletTailerHealth.Reader failed(String reason)
    {
        return new ScarletTailerHealth.Reader(this.log, START, T, reason);
    }

    @Test void healthyQuietManualClientDoesNotNeedAnInstanceOrNewEntries()
    {
        for (int minute = 0; minute < 180; minute++)
        {
            long now = T + minute * MINUTE;
            assertEquals(NONE, this.health.observe(now, this.clients, healthy(now)));
        }
    }

    @Test void hoursWithoutClientDoNotCountAgainstNextStartup()
    {
        assertEquals(NONE, this.health.observe(T, clients("17", START), null));
        assertEquals(NONE, this.health.observe(T + MINUTE, new ScarletTailerHealth.Clients(Map.of()), null));
        long launch = T + 302 * MINUTE;
        assertEquals(NONE, this.health.observe(launch, this.clients, null));
        assertEquals(NONE, this.health.observe(launch + 4 * MINUTE, this.clients, null));
        assertEquals(STALLED, this.health.observe(launch + 5 * MINUTE, this.clients, null));
        assertEquals(5, this.health.unhealthyMinutes());
    }

    @Test void failureBeforeAuthenticationOrJoinStillAlertsOnce()
    {
        assertEquals(NONE, this.health.observe(T, this.clients, null));
        assertEquals(STALLED, this.health.observe(T + 5 * MINUTE, this.clients, null));
        assertEquals(NONE, this.health.observe(T + 6 * MINUTE, this.clients, null));
        assertEquals("tracking-log-unavailable", this.health.reason());
    }

    @Test void transientAppendOrShortSchedulingGapDoesNotAlert()
    {
        assertEquals(NONE, this.health.observe(T, this.clients, healthy(T)));
        assertEquals(NONE, this.health.observe(T + MINUTE, this.clients, healthy(T)));
        assertEquals(NONE, this.health.observe(T + 2 * MINUTE, this.clients, healthy(T + 2 * MINUTE)));
        assertEquals(NONE, this.health.observe(T + 3 * MINUTE, this.clients, null));
        assertEquals(NONE, this.health.observe(T + 7 * MINUTE, this.clients, null));
        assertEquals(STALLED, this.health.observe(T + 8 * MINUTE, this.clients, null));
    }

    @Test void staleCompletedLoopCannotHideDeadReader()
    {
        this.health.observe(T, this.clients, healthy(T));
        assertEquals(NONE, this.health.observe(T + MINUTE, this.clients, healthy(T)));
        assertEquals(STALLED, this.health.observe(T + 6 * MINUTE, this.clients, healthy(T)));
        assertEquals("tracking-reader-heartbeat-stale", this.health.reason());
    }

    @Test void sustainedHealthyChecksWithAdvancingLoopsRecoverOnce()
    {
        this.health.observe(T, this.clients, null);
        this.health.observe(T + 5 * MINUTE, this.clients, null);
        assertEquals(NONE, this.health.observe(T + 6 * MINUTE, this.clients, healthy(T + 6 * MINUTE)));
        assertEquals(RECOVERED, this.health.observe(T + 7 * MINUTE, this.clients, healthy(T + 7 * MINUTE)));
        assertEquals(NONE, this.health.observe(T + 8 * MINUTE, this.clients, healthy(T + 8 * MINUTE)));
    }

    @Test void oneEntryOrOneLoopCannotAnnounceRecovery()
    {
        this.health.observe(T, this.clients, null);
        this.health.observe(T + 5 * MINUTE, this.clients, null);
        long first = T + 6 * MINUTE;
        assertEquals(NONE, this.health.observe(first, this.clients, healthy(first)));
        assertEquals(NONE, this.health.observe(first + ScarletTailerHealth.RECOVERY_NANOS, this.clients, healthy(first)));
        assertEquals(NONE, this.health.observe(first + MINUTE, this.clients, healthy(first)));
        assertEquals(NONE, this.health.observe(first + 2 * MINUTE, this.clients, healthy(first + 2 * MINUTE)));
        assertEquals(RECOVERED, this.health.observe(first + 3 * MINUTE, this.clients, healthy(first + 3 * MINUTE)));
    }

    @Test void invalidatedParserCannotRecoverEvenWithRecentHeartbeat()
    {
        this.health.observe(T, this.clients, failed("tracking-session-invalidated"));
        assertEquals(STALLED, this.health.observe(T + 5 * MINUTE, this.clients, failed("tracking-session-invalidated")));
        assertEquals(NONE, this.health.observe(T + 6 * MINUTE, this.clients,
            new ScarletTailerHealth.Reader(this.log, START, T + 6 * MINUTE, "tracking-session-invalidated")));
    }

    @Test void previousClientFileCannotProveHealthOrRecovery()
    {
        ScarletTailerHealth.Clients restarted = clients("18", START + 600_000);
        this.health.observe(T, restarted, healthy(T));
        assertEquals(STALLED, this.health.observe(T + 5 * MINUTE, restarted, healthy(T + 5 * MINUTE)));
        assertEquals("tracking-log-session-unconfirmed", this.health.reason());
        assertEquals(NONE, this.health.observe(T + 6 * MINUTE, restarted, healthy(T + 6 * MINUTE)));
    }

    @Test void logTimestampRoundingHasSmallBoundedTolerance()
    {
        assertEquals(NONE, this.health.observe(T, clients("17", START + 999), healthy(T)));
        assertNull(this.health.reason());
        this.health.observe(T + MINUTE, clients("17", START + 5_001), healthy(T + MINUTE));
        assertEquals("tracking-log-session-unconfirmed", this.health.reason());
    }

    @Test void missingFileIdentityOrFutureHeartbeatDoesNotProveRecovery()
    {
        this.health.observe(T, this.clients, new ScarletTailerHealth.Reader(this.log, null, T, null));
        assertEquals("tracking-log-session-unconfirmed", this.health.reason());
        assertEquals(STALLED, this.health.observe(T + 5 * MINUTE, this.clients, healthy(T + 6 * MINUTE)));
        assertEquals("tracking-reader-heartbeat-stale", this.health.reason());
    }

    @Test void samePidWithNewStartGetsFreshGraceAndNoFalseRecovery()
    {
        this.health.observe(T, this.clients, null);
        this.health.observe(T + 5 * MINUTE, this.clients, null);
        ScarletTailerHealth.Clients reused = clients("17", START + 600_000);
        assertEquals(NONE, this.health.observe(T + 6 * MINUTE, reused, null));
        assertEquals(NONE, this.health.observe(T + 10 * MINUTE, reused, null));
        assertEquals(STALLED, this.health.observe(T + 11 * MINUTE, reused, null));
    }

    @Test void readerReplacementCannotResetPersistentFailureTimer()
    {
        for (int minute = 0; minute <= 5; minute++)
            assertEquals(minute == 5 ? STALLED : NONE, this.health.observe(T + minute * MINUTE, this.clients,
                new ScarletTailerHealth.Reader(new Object(), START, 0L, "tracking-initial-catchup")));
    }

    @Test void readerReplacementMustEstablishItsOwnRecovery()
    {
        this.health.observe(T, this.clients, null);
        this.health.observe(T + 5 * MINUTE, this.clients, null);
        this.health.observe(T + 6 * MINUTE, this.clients, healthy(T + 6 * MINUTE));
        Object replacement = new Object();
        assertEquals(NONE, this.health.observe(T + 7 * MINUTE, this.clients,
            new ScarletTailerHealth.Reader(replacement, START, T + 7 * MINUTE, null)));
        assertEquals(RECOVERED, this.health.observe(T + 8 * MINUTE, this.clients,
            new ScarletTailerHealth.Reader(replacement, START, T + 8 * MINUTE, null)));
    }

    @Test void clientExitOrUnknownInventoryDoesNotSendRecovery()
    {
        for (ScarletTailerHealth.Clients unavailable : new ScarletTailerHealth.Clients[]{null, new ScarletTailerHealth.Clients(Map.of())})
        {
            ScarletTailerHealth h = new ScarletTailerHealth();
            h.observe(T, this.clients, null); h.observe(T + 5 * MINUTE, this.clients, null);
            assertEquals(NONE, h.observe(T + 6 * MINUTE, unavailable, healthy(T + 6 * MINUTE)));
            assertEquals(NONE, h.observe(T + 7 * MINUTE, this.clients, healthy(T + 7 * MINUTE)));
        }
        assertNull(ScarletTailerHealth.captureClients(List.of("not-a-pid")));
    }

    @Test void backwardsMonotonicClockRestartsGraceWithoutRecovery()
    {
        this.health.observe(T, this.clients, null);
        assertEquals(NONE, this.health.observe(T - MINUTE, this.clients, healthy(T)));
        assertEquals(NONE, this.health.observe(T, this.clients, null));
        assertEquals(NONE, this.health.observe(T + 4 * MINUTE, this.clients, null));
        assertEquals(STALLED, this.health.observe(T + 5 * MINUTE, this.clients, null));
    }

    @Test void clientInventoryOrderDoesNotResetFailureGrace()
    {
        ScarletTailerHealth.Clients pair = new ScarletTailerHealth.Clients(Map.of("17", Instant.ofEpochMilli(START), "18", Instant.ofEpochMilli(START)));
        this.health.observe(T, pair, null);
        ScarletTailerHealth.Clients reversed = new ScarletTailerHealth.Clients(Map.of("18", Instant.ofEpochMilli(START), "17", Instant.ofEpochMilli(START)));
        assertEquals(STALLED, this.health.observe(T + 5 * MINUTE, reversed, null));
    }
}

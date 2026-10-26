package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import net.sybyline.scarlet.ext.ManagedVrcClient.Client;
import net.sybyline.scarlet.ScarletStartupRecovery.*;

/** Fake clocks, observations and launcher only. No real logs, process access or network. */
class ScarletStartupRecoveryTest
{
    static long seconds(long n) { return TimeUnit.SECONDS.toNanos(n); }
    static Client client(long token) { return new Client(token, "synthetic-location", Instant.EPOCH, token, Instant.EPOCH, "test-os-user"); }
    static final class Fixture implements Environment
    {
        final ScarletStartupRecovery recovery = new ScarletStartupRecovery();
        final List<String> messages = new ArrayList<>();
        long now, progress, joins, auth;
        Object session;
        LocalDateTime fileTime;
        ScarletStartupFailureLoop.Evidence failures;
        boolean enabled = true, healthy = true, denied, nullResult, sameClient;
        String user = "test-user";
        int replacements;
        Runnable beforeClose = () -> {}, beforeLaunch = () -> {};
        Client current = client(1);
        public boolean allowed() { return enabled; }
        public String user() { return "test-user"; }
        public long now() { return now; }
        public LogState logs() { return new LogState(progress, joins, auth, user, healthy, session, fileTime, failures); }
        public void log(String reason) { messages.add(reason); }
        public Launch recover(Client expected, BooleanSupplier allowed, BooleanSupplier closeAllowed) throws Exception
        {
            assertSame(current, expected);
            beforeClose.run();
            if (!allowed.getAsBoolean() || !closeAllowed.getAsBoolean() || denied) throw new IllegalStateException();
            beforeLaunch.run();
            if (!allowed.getAsBoolean()) throw new InterruptedException();
            ++replacements;
            if (nullResult) return null;
            if (!sameClient) current = client(replacements + 1);
            return new Launch(current, logs());
        }
        void start() { recovery.start(this, new Launch(current, logs())); }
        void at(long seconds) { now = seconds(seconds); recovery.tick(); }
        void loop()
        {
            session = new Object(); auth++;
            fileTime = LocalDateTime.ofInstant(current.requestedAt(), ZoneId.systemDefault()).plusSeconds(10);
            failures = new ScarletStartupFailureLoop.Evidence(3, now + seconds(100), now + seconds(170));
            progress++;
        }
    }

    @Test void waitsThreeMinutesAndNeverMoreThanTwoReplacementLaunches()
    {
        Fixture f = new Fixture(); f.start(); f.at(179); assertEquals(0, f.replacements);
        f.at(180); assertEquals(1, f.replacements); f.at(359); assertEquals(1, f.replacements);
        f.at(360); assertEquals(2, f.replacements); f.at(540); f.at(1000);
        assertEquals(2, f.replacements); assertFalse(f.recovery.active());
    }
    @Test void activityResetsQuietTimerButCannotExtendTenMinuteWatch()
    {
        Fixture f = new Fixture(); f.start(); f.progress++; f.at(170); f.at(289); assertEquals(0, f.replacements);
        f.at(290); assertEquals(1, f.replacements);
        Fixture g = new Fixture(); g.start();
        for (int n = 1; n <= 6; n++) { g.progress++; g.at(n * 100); }
        assertEquals(0, g.replacements); assertFalse(g.recovery.active());
    }
    @Test void anyNewWorldJoinPermanentlyDisarmsIncludingPreamble()
    {
        Fixture f = new Fixture(); f.joins = 8; f.start(); f.joins++; f.at(10); f.at(200);
        assertFalse(f.recovery.active()); assertEquals(0, f.replacements);
    }
    @Test void oldJoinBaselineIsNotMistakenForThisStartup()
    {
        Fixture f = new Fixture(); f.joins = 20; f.auth = 20; f.user = "old-account"; f.start();
        f.at(180); assertEquals(1, f.replacements);
    }
    @Test void changedClientAuthenticationCancelsButExpectedLoginDoesNot()
    {
        Fixture f = new Fixture(); f.start(); f.auth++; f.user = "another-account"; f.at(180);
        assertEquals(0, f.replacements); assertFalse(f.recovery.active());
        Fixture g = new Fixture(); g.start(); g.auth++; g.at(180); assertEquals(1, g.replacements);
    }
    @Test void readerFailureNeverAuthorizesRestartAndDiagnosticsAreBounded()
    {
        Fixture f = new Fixture(); f.start(); f.healthy = false; f.at(180); f.at(190); f.at(200);
        assertEquals(0, f.replacements);
        assertEquals(1, f.messages.stream().filter(s -> s.startsWith("waiting-log-reader")).count());
        f.healthy = true; f.at(201); assertEquals(1, f.replacements);
    }
    @Test void changedSettingsCancelEvenIfEnabledAgainLater()
    {
        Fixture f = new Fixture(); f.start(); f.enabled = false; f.at(5); f.enabled = true; f.at(180);
        assertEquals(0, f.replacements); assertFalse(f.recovery.active());
    }
    @Test void cancellationOrNewJoinAtCloseBoundaryCannotDispatch()
    {
        for (int kind = 0; kind < 4; kind++)
        {
            Fixture f = new Fixture(); f.start();
            if (kind == 0) f.beforeClose = () -> f.joins++;
            if (kind == 1) f.beforeClose = () -> f.progress++;
            if (kind == 2) f.beforeClose = () -> f.healthy = false;
            if (kind == 3) f.beforeClose = f.recovery::cancel;
            f.at(180); f.at(400); assertEquals(0, f.replacements); assertFalse(f.recovery.active());
        }
    }
    @Test void failedOrUncertainTransportIsNeverReplayed()
    {
        Fixture f = new Fixture(); f.start(); f.denied = true; f.at(180); f.denied = false; f.at(400);
        assertEquals(0, f.replacements); assertFalse(f.recovery.active());
        for (int kind = 0; kind < 2; kind++)
        {
            Fixture g = new Fixture(); g.start(); g.nullResult = kind == 0; g.sameClient = kind == 1;
            g.at(180); g.at(400); assertEquals(1, g.replacements); assertFalse(g.recovery.active());
        }
    }
    @Test void clockReversalAndExplicitCancellationStopWork()
    {
        Fixture f = new Fixture(); f.start(); f.at(-1); assertFalse(f.recovery.active());
        Fixture g = new Fixture(); g.start(); g.recovery.cancel(); g.at(180); assertEquals(0, g.replacements);
    }
    @Test void observedJoinAfterReplacementStopsAllFurtherRecovery()
    {
        Fixture f = new Fixture(); f.start(); f.at(180); f.joins++; f.at(181); f.at(400);
        assertEquals(1, f.replacements); assertFalse(f.recovery.active());
    }
    @Test void disabledOrUnverifiedLaunchNeverArms()
    {
        Fixture f = new Fixture(); f.enabled = false; f.start(); assertFalse(f.recovery.active());
        f.enabled = true; f.recovery.start(f, new Launch(null, f.logs())); assertFalse(f.recovery.active());
    }
    @Test void duplicateCreationAuditKeepsWatchButAnotherDestinationCancels()
    {
        Fixture f = new Fixture(); f.start();
        f.recovery.cancelForDifferentDestination("synthetic-location");
        assertTrue(f.recovery.active()); f.at(180); assertEquals(1, f.replacements);
        f.recovery.cancelForDifferentDestination("another-location");
        assertFalse(f.recovery.active()); f.at(400); assertEquals(1, f.replacements);
    }

    @Test void failureLoopRecoversDespiteContinuousOutputButKeepsStartupGrace()
    {
        Fixture f = new Fixture(); f.start(); f.loop(); f.at(179); assertEquals(0, f.replacements);
        f.progress++; f.at(180); assertEquals(1, f.replacements);
        assertTrue(f.messages.contains("repeated-region-connection-failure-loop-confirmed"));
    }
    @Test void loopRequiresNewLaunchLogExpectedAuthenticationAndFreshSpacedCycles()
    {
        for (int kind = 0; kind < 9; kind++)
        {
            Fixture f = new Fixture(); f.session = new Object(); Object old = f.session; f.start(); f.loop();
            if (kind == 0) f.session = old;
            if (kind == 1) f.fileTime = f.fileTime.minusYears(1);
            if (kind == 2) f.fileTime = null;
            if (kind == 3) f.auth = 0;
            if (kind == 4) f.user = "different-user";
            if (kind == 5) f.failures = new ScarletStartupFailureLoop.Evidence(2, seconds(100), seconds(170));
            if (kind == 6) f.failures = new ScarletStartupFailureLoop.Evidence(3, seconds(1), seconds(80));
            if (kind == 7) f.failures = new ScarletStartupFailureLoop.Evidence(3, seconds(160), seconds(170));
            if (kind == 8) f.failures = new ScarletStartupFailureLoop.Evidence(3, -seconds(10), seconds(170));
            f.progress++; f.at(180); assertEquals(0, f.replacements, "kind=" + kind);
        }
    }
    @Test void loopStillRequiresHealthyCaughtUpReader()
    {
        Fixture f = new Fixture(); f.start(); f.loop(); f.healthy = false; f.at(180);
        assertEquals(0, f.replacements); f.healthy = true; f.progress++; f.at(181); assertEquals(1, f.replacements);
    }
    @Test void finalLoopChecksRejectJoinAccountSessionSettingsAndPositiveConnectionChanges()
    {
        for (int kind = 0; kind < 8; kind++)
        {
            Fixture f = new Fixture(); f.start(); f.loop();
            if (kind == 0) f.beforeClose = () -> f.joins++;
            if (kind == 1) f.beforeClose = () -> { f.auth++; f.user = "changed"; };
            if (kind == 2) f.beforeClose = () -> f.session = new Object();
            if (kind == 3) f.beforeClose = () -> f.enabled = false;
            if (kind == 4) f.beforeClose = () -> f.failures = null;
            if (kind == 5) f.beforeClose = () -> f.healthy = false;
            if (kind == 6) f.beforeClose = () -> f.now += seconds(50);
            if (kind == 7) f.beforeClose = f.recovery::cancel;
            f.at(180); assertEquals(0, f.replacements, "kind=" + kind); assertFalse(f.recovery.active());
        }
    }
    @Test void backgroundOutputAtFinalLoopCheckIsNotMistakenForARecovery()
    {
        Fixture f = new Fixture(); f.start(); f.loop(); f.beforeClose = () -> f.progress++;
        f.at(180); assertEquals(1, f.replacements);
    }
    @Test void newSessionAndLossAfterBindingCancelWatch()
    {
        for (boolean missing : new boolean[]{false,true})
        {
            Fixture f = new Fixture(); f.start(); f.loop(); f.at(179);
            f.session = missing ? null : new Object(); f.at(180);
            assertEquals(0, f.replacements); assertFalse(f.recovery.active());
        }
    }
    @Test void newJoinOrDestinationBeforeLoopAttemptDisarms()
    {
        for (boolean joined : new boolean[]{false,true})
        {
            Fixture f = new Fixture(); f.start(); f.loop();
            if (joined) f.joins++; else f.recovery.cancelForDifferentDestination("another");
            f.at(180); assertFalse(f.recovery.active()); assertEquals(0, f.replacements);
        }
    }
    @Test void loopingReplacementCannotReusePreviousFailuresAndBudgetRemainsTwo()
    {
        Fixture f = new Fixture(); f.start(); f.loop(); f.at(180); assertEquals(1, f.replacements);
        f.progress++; f.at(360); assertEquals(1, f.replacements); // Old session/evidence cannot authorize another close.
        f.loop(); f.progress++; f.at(540); assertEquals(2, f.replacements);
        f.loop(); f.progress++; f.at(720); assertEquals(2, f.replacements); assertFalse(f.recovery.active());
    }
    @Test void loopCannotExtendWatchOrReplayDeniedTransport()
    {
        Fixture f = new Fixture(); f.start(); f.loop();
        f.failures = new ScarletStartupFailureLoop.Evidence(3, seconds(500), seconds(570));
        f.at(600); assertFalse(f.recovery.active()); assertEquals(0, f.replacements);
        Fixture g = new Fixture(); g.start(); g.loop(); g.denied = true; g.at(180);
        g.denied = false; g.at(181); assertFalse(g.recovery.active()); assertEquals(0, g.replacements);
    }
    @Test void sessionChangeAfterNormalClosePreventsReplacementLaunch()
    {
        Fixture f = new Fixture(); f.start(); f.loop(); f.beforeLaunch = () -> f.session = new Object();
        f.at(180); assertEquals(0, f.replacements); assertFalse(f.recovery.active());
        Thread.interrupted(); // Fixture transport reports cancellation through InterruptedException.
    }
}

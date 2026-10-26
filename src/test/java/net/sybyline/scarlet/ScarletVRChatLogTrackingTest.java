package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic text and temporary fixture files only; no real client or log directories. */
class ScarletVRChatLogTrackingTest
{
    @TempDir Path temporary;
    private static final String USER = "usr_00000000-0000-0000-0000-000000000001";
    private static final String AUTH = "Synthetic User (Guest) (" + USER + ")";
    private static final String PREFIX = "2026.10.12 12:34:56 Debug      -  ";

    static final class TestLogs extends ScarletVRChatLogs
    {
        long now = TimeUnit.SECONDS.toNanos(100);
        boolean alive;
        File target;
        int pauses;
        Runnable onPause = () -> {};
        Runnable onLocate = () -> {};
        TestLogs(Listener listener) { super(listener); }
        @Override long trackingNowNanos() { return this.now; }
        @Override boolean trackingThreadAlive() { return this.alive; }
        @Override File locateTarget() { this.onLocate.run(); return this.target; }
        @Override void trackingPause() throws InterruptedException
        {
            this.pauses++;
            this.now += TimeUnit.MILLISECONDS.toNanos(20);
            this.onPause.run();
        }
    }

    final class Fixture implements AutoCloseable
    {
        final List<Object[]> authentication = new ArrayList<>();
        final TestLogs logs;
        final ScarletVRChatLogs.VRChatLogTail tail;
        final File file;
        boolean failListener;
        Fixture() throws Exception
        { this(null); }
        Fixture(String logName) throws Exception
        {
            this.file = logName == null ? Files.createTempFile(temporary, "synthetic-log-", ".txt").toFile()
                : Files.createFile(Files.createTempDirectory(temporary, "synthetic-session-").resolve(logName)).toFile();
            ScarletVRChatLogs.Listener listener = (ScarletVRChatLogs.Listener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ScarletVRChatLogs.Listener.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("log_userAuthenticated"))
                    {
                        if (this.failListener) throw new IllegalStateException("synthetic listener failure");
                        this.authentication.add(args.clone());
                    }
                    return null;
                });
            this.logs = new TestLogs(listener);
            this.logs.target = this.file;
            this.tail = this.logs.new VRChatLogTail(this.file);
            set(this.logs, "currentTarget", this.file);
            set(this.logs, "currentTail", this.tail);
        }
        void accept(boolean preamble, String text)
        {
            this.tail.on_line(preamble, PREFIX + text);
            this.tail.on_eof();
        }
        void healthy()
        {
            this.logs.alive = true;
            accept(false, "[Behaviour] User Authenticated: " + AUTH);
            this.tail.on_loop();
            assertTrue(this.logs.clientExitTrackingHealthy());
        }
        @Override public void close() { this.logs.close(); }
    }

    static void set(Object target, String name, Object value) throws Exception
    {
        Field field = ScarletVRChatLogs.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test void startupObservationCountsAllLinesAndJoinsWithoutUsingOldWallClockTimes() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.healthy(); ScarletStartupRecovery.LogState before = f.logs.startupLogState();
            assertTrue(before.healthy); assertEquals(USER, before.user);
            f.tail.on_line(false, "plain continuation text"); f.tail.on_eof(); f.tail.on_loop();
            assertTrue(f.logs.startupLogState().progress > before.progress);
            f.accept(true, "[Behaviour] Joining wrld_synthetic:1"); f.tail.on_loop();
            assertTrue(f.logs.startupLogState().joins > before.joins);
            f.tail.invalidateTracking(); assertFalse(f.logs.startupLogState().healthy);
        }
    }

    @Test void emptyDirectoryObservationRequiresFreshSuccessfulScanAndLiveReader() throws Exception
    {
        TestLogs logs = new TestLogs((ScarletVRChatLogs.Listener) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ScarletVRChatLogs.Listener.class}, (p,m,a) -> null));
        try
        {
            logs.alive = true;
            assertFalse(logs.startupLogState().healthy);
            set(logs, "startupTargetScan", logs.now); assertTrue(logs.startupLogState().healthy);
            logs.now += TimeUnit.SECONDS.toNanos(13); assertFalse(logs.startupLogState().healthy);
            set(logs, "startupTargetScan", logs.now); logs.alive = false; assertFalse(logs.startupLogState().healthy);
        }
        finally { logs.close(); }
    }

    @Test void livePairedRegionResetsReachRecoverySnapshotButPreambleDoesNot() throws Exception
    {
        for (boolean preamble : new boolean[]{true,false}) try (Fixture f = new Fixture())
        {
            f.healthy();
            for (int second : new int[]{0,35,70})
            {
                f.logs.now = TimeUnit.SECONDS.toNanos(100 + second);
                String prefix = "2026.10.12 " + java.time.LocalTime.of(12, 0).plusSeconds(second)
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + " Debug      -  ";
                f.tail.isInPreamble = preamble;
                f.tail.on_line(preamble, prefix + ScarletStartupFailureLoop.FAILURE);
                f.tail.on_line(preamble, prefix + ScarletStartupFailureLoop.RESET);
                f.tail.on_eof(); f.tail.on_loop();
            }
            ScarletStartupRecovery.LogState state = f.logs.startupLogState();
            assertSame(f.tail, state.session);
            assertEquals(!preamble, state.failures.repeated(f.logs.now));
        }
    }
    @Test void successfulRoomMarkersAlsoDisarmRecovery() throws Exception
    {
        for (String marker : new String[]{"[Behaviour] Successfully joined room", "[Behaviour] Finished entering world."})
            try (Fixture f = new Fixture())
            {
                f.healthy(); long before = f.logs.startupLogState().joins;
                f.accept(false, marker); f.tail.on_loop();
                assertEquals(before + 1, f.logs.startupLogState().joins);
            }
    }
    @Test void syntheticLiveParserDrivesRecoveryOnlyWithoutSuccess() throws Exception
    {
        for (boolean connected : new boolean[]{false,true}) try (Fixture f = new Fixture("output_log_2026-10-12_12-00-00.txt"))
        {
            ScarletStartupRecovery recovery = new ScarletStartupRecovery();
            java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
            java.time.Instant requested = LocalDateTime.of(2026, 10, 12, 11, 59, 50)
                .atZone(java.time.ZoneId.systemDefault()).toInstant();
            net.sybyline.scarlet.ext.ManagedVrcClient.Client client = new net.sybyline.scarlet.ext.ManagedVrcClient.Client(
                1, "synthetic-destination", requested, 1, requested, "synthetic-os-user");
            recovery.start(new ScarletStartupRecovery.Environment() {
                public boolean allowed() { return true; }
                public String user() { return USER; }
                public long now() { return f.logs.now; }
                public ScarletStartupRecovery.LogState logs() { return f.logs.startupLogState(); }
                public void log(String reason) {}
                public ScarletStartupRecovery.Launch recover(net.sybyline.scarlet.ext.ManagedVrcClient.Client expected,
                    java.util.function.BooleanSupplier allowed, java.util.function.BooleanSupplier closeAllowed)
                {
                    assertSame(client, expected); assertTrue(allowed.getAsBoolean()); assertTrue(closeAllowed.getAsBoolean());
                    attempts.incrementAndGet(); return null; // No process or real launch transport.
                }
            }, new ScarletStartupRecovery.Launch(client, new ScarletStartupRecovery.LogState(0, 0, 0, null, true)));
            f.healthy();
            for (int second : new int[]{110,145,180})
            {
                f.logs.now = TimeUnit.SECONDS.toNanos(100 + second);
                String prefix = LocalDateTime.of(2026, 10, 12, 12, 0).plusSeconds(second)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")) + " Debug      -  ";
                f.tail.on_line(false, prefix + ScarletStartupFailureLoop.FAILURE);
                f.tail.on_line(false, prefix + ScarletStartupFailureLoop.RESET);
                if (connected && second == 180) f.tail.on_line(false, prefix + "[Behaviour] OnConnectedToMaster");
                f.tail.on_eof(); f.tail.on_loop(); recovery.tick();
            }
            assertEquals(connected ? 0 : 1, attempts.get());
        }
    }
    @Test void startupFileTimestampRequiresCanonicalLogName()
    {
        assertEquals(LocalDateTime.of(2026, 10, 25, 20, 10, 53),
            ScarletVRChatLogs.startupFileTime(new File("output_log_2026-10-25_20-10-53.txt")));
        assertNull(ScarletVRChatLogs.startupFileTime(new File("other.txt")));
        assertNull(ScarletVRChatLogs.startupFileTime(new File("output_log_2026-99-25_20-10-53.txt")));
    }

    @Test void boundedCatchupWaitAllowsPendingReadButNeverInventsHealth() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.healthy();
            f.tail.on_line(false, PREFIX + "ordinary background log text");
            assertFalse(f.logs.clientExitTrackingHealthy());
            f.logs.onPause = () -> { f.tail.on_eof(); f.tail.on_loop(); };
            f.logs.awaitClientExitTracking();
            assertEquals(1, f.logs.pauses);
            assertTrue(f.logs.clientExitTrackingHealthy());
        }
    }

    @Test void appendFinishingAfterOldHalfSecondBudgetCanRecoverWithFreshProof() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.healthy(); f.tail.on_line(false, PREFIX + "ordinary pending line");
            f.logs.onPause = () -> { if (f.logs.pauses == 50) { f.tail.on_eof(); f.tail.on_loop(); } };
            f.logs.awaitClientExitTracking();
            assertEquals(50, f.logs.pauses); assertTrue(f.logs.clientExitTrackingHealthy());
        }
    }

    @Test void longerWaitStillAbortsAsSoonAsPriorHeartbeatBecomesStale() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.healthy(); f.tail.on_line(false, PREFIX + "pending line");
            f.logs.now += TimeUnit.MILLISECONDS.toNanos(1800);
            f.logs.awaitClientExitTracking();
            assertTrue(f.logs.pauses <= 11); assertFalse(f.logs.clientExitTrackingHealthy());
            assertEquals("tracking-awaiting-completed-loop", f.logs.clientExitTrackingFailure());
        }
    }

    @Test void healthyLoopTimestampIsTakenAfterSlowTargetDiscovery() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.healthy(); f.logs.now += TimeUnit.SECONDS.toNanos(11);
            f.logs.onLocate = () -> f.logs.now += TimeUnit.SECONDS.toNanos(3);
            assertFalse(f.logs.clientExitTrackingHealthy());
            f.tail.on_loop();
            assertEquals(f.logs.now, f.tail.completedLoopNanos);
            assertTrue(f.logs.clientExitTrackingHealthy());
        }
    }

    @Test void slowTargetDiscoveryCannotHideAnUnreadAppend() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.healthy(); f.logs.now += TimeUnit.SECONDS.toNanos(11);
            f.logs.onLocate = () -> {
                f.logs.now += TimeUnit.SECONDS.toNanos(3);
                try { Files.write(f.file.toPath(), new byte[]{65}, StandardOpenOption.APPEND); }
                catch (IOException ex) { throw new AssertionError(ex); }
            };
            f.tail.on_loop();
            assertFalse(f.logs.clientExitTrackingHealthy());
            assertEquals("tracking-unread-bytes-or-file-unavailable", f.logs.clientExitTrackingFailure());
        }
    }

    @Test void failureDetailsDistinguishReadHeartbeatAndInvalidationWithoutPaths() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.healthy(); f.tail.readInProgress = true;
            assertEquals("tracking-read-in-progress", f.logs.clientExitTrackingFailure());
            f.tail.readInProgress = false; f.tail.completeRead = false;
            assertEquals("tracking-incomplete-line-or-read", f.logs.clientExitTrackingFailure());
            f.tail.completeRead = true; f.logs.now += TimeUnit.SECONDS.toNanos(3);
            assertEquals("tracking-reader-heartbeat-stale", f.logs.clientExitTrackingFailure());
            f.tail.invalidateTracking();
            assertEquals("tracking-session-invalidated", f.logs.clientExitTrackingFailure());
            assertFalse(f.logs.clientExitTrackingHealthy());
        }
    }

    @Test void unresolvedReadWaitIsBoundedAndRemainsUnhealthy() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.healthy();
            f.tail.on_line(false, PREFIX + "pending text");
            f.logs.awaitClientExitTracking();
            assertEquals(75, f.logs.pauses);
            assertFalse(f.logs.clientExitTrackingHealthy());
        }
    }

    @Test void staleFailedStoppedAndPreambleReadersDoNotGetCatchupAllowance() throws Exception
    {
        for (int kind = 0; kind < 6; kind++) try (Fixture f = new Fixture())
        {
            f.healthy();
            if (kind == 0) f.logs.now += TimeUnit.SECONDS.toNanos(2) + 1;
            if (kind == 1) f.tail.invalidateTracking();
            if (kind == 2) f.tail.stop();
            if (kind == 3) f.tail.isInPreamble = true;
            if (kind == 4) f.logs.alive = false;
            if (kind == 5) f.logs.now--;
            f.logs.awaitClientExitTracking();
            assertEquals(0, f.logs.pauses, "kind=" + kind);
            assertFalse(f.logs.clientExitTrackingHealthy());
        }
    }

    @Test void tailReplacementDuringCatchupStopsWaiting() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.healthy(); f.tail.on_line(false, PREFIX + "pending text");
            f.logs.onPause = () -> {
                try { set(f.logs, "currentTail", f.logs.new VRChatLogTail(f.file)); }
                catch (Exception ex) { throw new AssertionError(ex); }
            };
            f.logs.awaitClientExitTracking();
            assertEquals(1, f.logs.pauses);
            assertFalse(f.logs.clientExitTrackingHealthy());
        }
    }

    @Test void interruptedCatchupNeverWaits() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.healthy(); f.tail.on_line(false, PREFIX + "pending text");
            Thread.currentThread().interrupt();
            try { f.logs.awaitClientExitTracking(); assertTrue(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
            assertEquals(0, f.logs.pauses);
            assertFalse(f.logs.clientExitTrackingHealthy());
        }
    }

    @Test void knownAuthenticationPrefixesDispatchStrictIdentityAndTimestamp() throws Exception
    {
        for (String text : new String[]{"User Authenticated: " + AUTH, "[Behaviour] User Authenticated: " + AUTH,
            "[User Authenticated: " + AUTH + "]", "[Behaviour] [User Authenticated: " + AUTH + "]"})
        {
            try (Fixture fixture = new Fixture())
            {
                fixture.accept(false, text);
                assertEquals(1, fixture.authentication.size(), text);
                assertArrayEquals(new Object[]{false, LocalDateTime.of(2026, 10, 12, 12, 34, 56),
                    "Synthetic User (Guest)", USER}, fixture.authentication.get(0));
            }
        }
    }

    @Test void authenticationPreservesPreambleThenLiveState() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.accept(true, "[Behaviour] User Authenticated: " + AUTH);
            fixture.accept(false, "[Behaviour] User Authenticated: " + AUTH);
            assertEquals(2, fixture.authentication.size());
            assertEquals(true, fixture.authentication.get(0)[0]);
            assertEquals(false, fixture.authentication.get(1)[0]);
        }
    }

    @Test void malformedAuthenticationNeverInventsAnIdentityAndInvalidatesTracking() throws Exception
    {
        for (String text : new String[]{
            "User Authenticated: Synthetic (usr_invalid)",
            "User Authenticated: Synthetic (grp_00000000-0000-0000-0000-000000000001)",
            "User Authenticated: Synthetic (" + USER + ") trailing text",
            "User Authenticated: (" + USER + ")",
            "User Authenticated:   (" + USER + ")",
            "User Authenticated: Synthetic " + USER,
            "[Behaviour] User Authenticated: Synthetic (usr_invalid)",
            "[Behaviour] User Authenticated: Synthetic (" + USER + ") trailing text",
            "[Behaviour] User Authenticated: (" + USER + ")",
            "[Behaviour] User Authenticated:   (" + USER + ")",
            "[Behaviour] User Authenticated: Synthetic " + USER,
            "[User Authenticated: " + AUTH,
            "[User Authenticated: Synthetic (" + USER + ")] trailing text",
            "[User Authenticated: Synthetic (grp_00000000-0000-0000-0000-000000000001)]"})
        {
            try (Fixture fixture = new Fixture())
            {
                fixture.logs.alive = true;
                fixture.accept(false, text);
                fixture.tail.on_loop();
                assertTrue(fixture.authentication.isEmpty(), text);
                assertFalse(fixture.tail.trackingValid, text);
                assertFalse(fixture.logs.clientExitTrackingHealthy(), text);
            }
        }
    }

    @Test void unrelatedEmbeddedOrAlteredAuthenticationPrefixIsNotTrusted() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.accept(false, "Some text User Authenticated: " + AUTH);
            fixture.accept(false, "[API] User Authenticated: " + AUTH);
            fixture.accept(false, "[Behaviour] Some text [User Authenticated: " + AUTH + "]");
            fixture.accept(false, "[Behaviour] User Authenticated:Fake " + AUTH);
            assertTrue(fixture.authentication.isEmpty());
        }
    }

    @Test void plainAuthenticationPreservesPreambleAndEnablesHealthyLiveTracking() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.logs.alive = true;
            fixture.accept(true, "User Authenticated: " + AUTH);
            fixture.accept(false, "User Authenticated: " + AUTH);
            fixture.tail.on_loop();
            assertEquals(2, fixture.authentication.size());
            assertEquals(true, fixture.authentication.get(0)[0]);
            assertEquals(false, fixture.authentication.get(1)[0]);
            assertEquals(USER, fixture.authentication.get(1)[3]);
            assertTrue(fixture.logs.clientExitTrackingHealthy());
        }
    }

    @Test void malformedPlainAuthenticationCannotPreserveEarlierTrustedTracking() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.healthy();
            fixture.accept(false, "User Authenticated: Synthetic (usr_not-a-uuid)");
            fixture.accept(false, "User Authenticated: " + AUTH);
            fixture.tail.on_loop();
            assertEquals(2, fixture.authentication.size());
            assertFalse(fixture.tail.trackingValid);
            assertFalse(fixture.logs.clientExitTrackingHealthy());
        }
    }

    @Test void continuationOnlyTextDoesNotReplayLastAuthenticationEvent() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.accept(false, "[Behaviour] User Authenticated: " + AUTH);
            fixture.tail.on_line(false, "   synthetic continuation text");
            fixture.tail.on_eof();
            fixture.tail.on_eof();
            assertEquals(1, fixture.authentication.size());
            assertTrue(fixture.tail.trackingValid);
        }
    }

    @Test void unstructuredPreambleHeadersDoNotBreakSubsequentValidParsing() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.tail.on_line(true, "Synthetic log header without a timestamp");
            fixture.accept(false, "[Behaviour] User Authenticated: " + AUTH);
            assertEquals(1, fixture.authentication.size());
            assertTrue(fixture.tail.trackingValid);
        }
    }

    @Test void healthyQuietTailUsesLoopHeartbeatRatherThanEntryTime() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.healthy();
            long lastEntry = fixture.logs.lastEntryMillis();
            fixture.logs.now += TimeUnit.SECONDS.toNanos(1);
            fixture.tail.on_loop();
            fixture.logs.now += TimeUnit.SECONDS.toNanos(1);
            fixture.tail.on_loop();
            fixture.logs.now += TimeUnit.SECONDS.toNanos(1);
            fixture.tail.on_loop();
            assertEquals(lastEntry, fixture.logs.lastEntryMillis());
            assertTrue(fixture.logs.clientExitTrackingHealthy());
        }
    }

    @Test void staleOrBackwardsMonotonicHeartbeatFailsClosed() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.healthy();
            fixture.logs.now += TimeUnit.SECONDS.toNanos(2) + 1;
            assertFalse(fixture.logs.clientExitTrackingHealthy());
            fixture.tail.on_loop();
            assertTrue(fixture.logs.clientExitTrackingHealthy());
            fixture.logs.now -= 1;
            assertFalse(fixture.logs.clientExitTrackingHealthy());
        }
    }

    @Test void pendingEntriesCannotReuseEarlierHealthyHeartbeat() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.healthy();
            fixture.tail.on_line(false, PREFIX + "[Behaviour] OnPlayerJoined New Player (" + USER + ")");
            assertFalse(fixture.logs.clientExitTrackingHealthy());
            fixture.tail.on_loop();
            assertFalse(fixture.logs.clientExitTrackingHealthy());
            fixture.tail.on_eof();
            fixture.tail.on_loop();
            assertTrue(fixture.logs.clientExitTrackingHealthy());
        }
    }

    @Test void idleUnstartedPreambleOrStoppedThreadIsNotHealthy() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.logs.alive = true;
            fixture.tail.on_loop();
            assertFalse(fixture.logs.clientExitTrackingHealthy());
            fixture.healthy();
            fixture.logs.alive = false;
            assertFalse(fixture.logs.clientExitTrackingHealthy());
            fixture.logs.alive = true;
            fixture.tail.stop();
            assertFalse(fixture.logs.clientExitTrackingHealthy());
            fixture.tail.on_loop();
            assertFalse(fixture.logs.clientExitTrackingHealthy());
        }
    }

    @Test void actualUnstartedThreadCannotClaimHealthyTracking() throws Exception
    {
        ScarletVRChatLogs.Listener listener = (ScarletVRChatLogs.Listener) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ScarletVRChatLogs.Listener.class}, (proxy, method, args) -> null);
        try (ScarletVRChatLogs logs = new ScarletVRChatLogs(listener))
        {
            File file = Files.createTempFile(this.temporary, "synthetic-unstarted-", ".txt").toFile();
            ScarletVRChatLogs.VRChatLogTail tail = logs.new VRChatLogTail(file);
            set(logs, "currentTarget", file);
            set(logs, "currentTail", tail);
            tail.on_eof();
            tail.on_loop();
            assertFalse(logs.clientExitTrackingHealthy());
        }
    }

    @Test void failureRotationAndMissingFilePermanentlyInvalidateCurrentTail() throws Exception
    {
        for (int kind = 0; kind < 3; kind++)
        {
            try (Fixture fixture = new Fixture())
            {
                fixture.healthy();
                if (kind == 0) fixture.tail.on_exception(new IOException("synthetic read failure"));
                if (kind == 1) fixture.tail.on_rotated();
                if (kind == 2) fixture.tail.on_fnf(new FileNotFoundException("synthetic missing file"));
                fixture.accept(false, "[Behaviour] User Authenticated: " + AUTH);
                fixture.tail.on_loop();
                assertFalse(fixture.logs.clientExitTrackingHealthy());
                assertFalse(fixture.tail.trackingValid);
            }
        }
    }

    @Test void listenerOrMalformedEntryFailureCannotSilentlyHealRosterHealth() throws Exception
    {
        for (boolean failListener : new boolean[]{false, true})
        {
            try (Fixture fixture = new Fixture())
            {
                fixture.healthy();
                fixture.failListener = failListener;
                fixture.accept(false, failListener ? "[Behaviour] User Authenticated: " + AUTH : "[Behaviour] OnPlayerJoined malformed");
                fixture.failListener = false;
                fixture.accept(false, "[Behaviour] User Authenticated: " + AUTH);
                fixture.tail.on_loop();
                assertFalse(fixture.logs.clientExitTrackingHealthy());
            }
        }
    }

    @Test void switchingLogTargetImmediatelyInvalidatesOldTail() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.healthy();
            fixture.logs.target = Files.createTempFile(this.temporary, "synthetic-next-", ".txt").toFile();
            fixture.logs.now += TimeUnit.SECONDS.toNanos(11);
            fixture.tail.on_loop();
            assertFalse(fixture.logs.clientExitTrackingHealthy());
            assertFalse(fixture.tail.trackingValid);
            assertEquals(fixture.logs.target, fixture.logs.currentTarget());
        }
    }

    @Test void parsedEntryStateIsResetWhenReadFails() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.healthy();
            RandomAccessFile reader = new RandomAccessFile(fixture.file, "r");
            reader.close();
            assertThrows(IOException.class, () -> fixture.tail.readLines(0, reader));
            assertFalse(fixture.tail.trackingValid);
            assertFalse(fixture.logs.clientExitTrackingHealthy());
        }
    }

    @Test void partialTrailingLineAndUnprocessedAppendBlockExitUntilReadCompletely() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.logs.alive = true;
            String line = PREFIX + "[Behaviour] User Authenticated: " + AUTH;
            Files.write(fixture.file.toPath(), line.getBytes(StandardCharsets.UTF_8));
            try (RandomAccessFile reader = new RandomAccessFile(fixture.file, "r"))
            {
                fixture.tail.readLines(0, reader);
                fixture.tail.on_loop();
                assertFalse(fixture.logs.clientExitTrackingHealthy());
                assertTrue(fixture.authentication.isEmpty());
                Files.write(fixture.file.toPath(), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                fixture.tail.readLines(0, reader);
                fixture.tail.on_loop();
                assertTrue(fixture.logs.clientExitTrackingHealthy());
                assertEquals(1, fixture.authentication.size());
                Files.write(fixture.file.toPath(), (PREFIX + "[Behaviour] OnPlayerJoined Other Player (" + USER + ")\n")
                    .getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                assertFalse(fixture.logs.clientExitTrackingHealthy());
                fixture.tail.readLines(0, reader);
                fixture.tail.on_loop();
                assertTrue(fixture.logs.clientExitTrackingHealthy());
            }
        }
    }

    @Test void missingCurrentTargetAndClosedOwnerAreNeverHealthy() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.healthy();
            set(fixture.logs, "currentTarget", null);
            assertFalse(fixture.logs.clientExitTrackingHealthy());
            set(fixture.logs, "currentTarget", fixture.file);
            fixture.logs.close();
            fixture.tail.on_loop();
            assertFalse(fixture.logs.clientExitTrackingHealthy());
        }
    }
}

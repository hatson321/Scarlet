package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic world diagnostics, log files and process callbacks only. */
class ScarletUdonLogTest
{
    @TempDir Path temporary;
    private static final String ERROR = "[UdonBehaviour] An exception occurred during Udon execution, this UdonBehaviour will be halted.";
    private static final String STAMP = LocalDateTime.ofInstant(ScarletClientExitMonitorTest.START, ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm:ss"));
    private static final String DEBUG = STAMP + " Debug      -  ";
    private static final String ERROR_LINE = STAMP + " Error      -  " + ERROR;

    final class Fixture implements AutoCloseable
    {
        final ScarletClientExitMonitorTest.Fixture exit = new ScarletClientExitMonitorTest.Fixture(
            ScarletClientExitMonitor.EvidenceMode.LIVE_LOG_ROSTER);
        final ScarletVRChatLogTrackingTest.TestLogs logs;
        final ScarletVRChatLogs.VRChatLogTail tail;
        final File file;
        int joins, leaves;

        Fixture() throws Exception
        {
            this.file = Files.createTempFile(temporary, "synthetic-udon-", ".txt").toFile();
            ScarletVRChatLogs.Listener listener = (ScarletVRChatLogs.Listener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ScarletVRChatLogs.Listener.class}, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "log_userAuthenticated": this.exit.monitor.authenticated((String) args[3]); break;
                    case "log_userJoined": this.exit.monitor.joined((String) args[2],
                        ((LocalDateTime) args[1]).atZone(ZoneId.systemDefault()).toInstant(), (Boolean) args[0]); break;
                    case "log_playerJoined": this.joins++; this.exit.monitor.playerJoined((String) args[3]); break;
                    case "log_playerLeft": this.leaves++; this.exit.monitor.playerLeft((String) args[3]); break;
                    default: break;
                    }
                    return null;
                });
            this.logs = new ScarletVRChatLogTrackingTest.TestLogs(listener);
            this.logs.target = this.file;
            this.logs.alive = true;
            this.tail = this.logs.new VRChatLogTail(this.file);
            ScarletVRChatLogTrackingTest.set(this.logs, "currentTarget", this.file);
            ScarletVRChatLogTrackingTest.set(this.logs, "currentTail", this.tail);
            this.exit.settle = () -> this.exit.healthy.set(this.logs.clientExitTrackingHealthy());
            this.exit.monitor.newLog();
            accept("[Behaviour] User Authenticated: Synthetic Self (" + ScarletClientExitMonitorTest.SELF + ")");
            accept("[Behaviour] Joining " + ScarletClientExitMonitorTest.LOCATION);
            accept("[Behaviour] OnPlayerJoined Synthetic Self (" + ScarletClientExitMonitorTest.SELF + ")");
            assertTrue(this.logs.clientExitTrackingHealthy());
        }

        void complete()
        {
            this.tail.on_eof();
            this.tail.on_loop();
            this.exit.healthy.set(this.logs.clientExitTrackingHealthy());
        }

        void accept(String text) { this.tail.on_line(false, DEBUG + text); complete(); }

        void dump(int lines)
        {
            this.tail.on_line(false, ERROR_LINE);
            for (int i = 1; i < lines; i++) this.tail.on_line(false, "  0x" + Integer.toHexString(i) + ": False");
            complete();
        }

        void assertBlocked()
        {
            assertFalse(this.logs.clientExitTrackingHealthy());
            this.exit.evidence(); this.exit.monitor.tick(); this.exit.due();
            assertEquals(0, this.exit.closeCalls.get());
            assertEquals(0, this.exit.forced.get());
        }

        @Override public void close() { this.logs.close(); }
    }

    @Test void worldErrorDumpAllowsVerifiedSoleSelfClosureAfterFullGrace() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.dump(1302);
            assertTrue(f.logs.clientExitTrackingHealthy());
            f.exit.evidence(); f.exit.monitor.tick();
            f.exit.nanos.set(ScarletClientExitMonitorTest.WAIT - 1); f.exit.monitor.tick();
            assertEquals(0, f.exit.requested.get());
            f.exit.nanos.incrementAndGet(); f.exit.monitor.tick();
            assertEquals(1, f.exit.requested.get());
            assertEquals(0, f.exit.queries.get());
            assertEquals(0, f.exit.forced.get());
        }
    }

    @Test void playerEventsAfterDumpStillPreventExitWhileAnotherPlayerRemains() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.dump(1302);
            f.accept("[Behaviour] OnPlayerJoined Synthetic Other (" + ScarletClientExitMonitorTest.OTHER + ")");
            f.exit.evidence(); f.exit.monitor.tick(); f.exit.due();
            assertEquals(2, f.joins); assertEquals(0, f.exit.requested.get());
            f.accept("[Behaviour] OnPlayerLeft Synthetic Other (" + ScarletClientExitMonitorTest.OTHER + ")");
            assertEquals(1, f.leaves);
            f.exit.monitor.tick();
            f.exit.nanos.addAndGet(ScarletClientExitMonitorTest.WAIT); f.exit.monitor.tick();
            assertEquals(1, f.exit.requested.get());
        }
    }

    @Test void bodyRemainsBoundedAcrossEofWhileTimestampedEventsStillDispatch() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.tail.on_line(false, ERROR_LINE); f.complete();
            String body = "x".repeat(4096);
            for (int i = 0; i < 1500; i++)
            {
                f.tail.on_line(false, body);
                assertTrue(f.tail.buffer.size() <= 1);
                assertTrue(f.tail.bufferedChars <= ERROR_LINE.length());
                if (i % 113 == 0) f.complete();
            }
            f.complete();
            assertTrue(f.logs.clientExitTrackingHealthy());
            f.accept("[Behaviour] OnPlayerJoined Synthetic Other (" + ScarletClientExitMonitorTest.OTHER + ")");
            assertEquals(2, f.joins);
        }
    }

    @Test void knownDumpDoesNotRestoreAnAlreadyInvalidReader() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.tail.on_exception(new IOException("synthetic read failure"));
            f.dump(1302);
            f.accept("[Behaviour] OnPlayerJoined Synthetic Other (" + ScarletClientExitMonitorTest.OTHER + ")");
            f.assertBlocked();
        }
    }

    @Test void nextRecordLosesDiagnosticExemption() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.dump(1302);
            f.tail.on_line(false, DEBUG + "[Behaviour] OnPlayerJoined Synthetic Other (" + ScarletClientExitMonitorTest.OTHER + ")");
            for (int i = 0; i < 1024; i++) f.tail.on_line(false, "continuation");
            f.complete(); f.assertBlocked();
            assertEquals(1, f.joins, "An oversized tracking record must not be dispatched");
        }
    }

    @Test void similarHeadersAndOtherLevelsKeepNormalRecordLimits() throws Exception
    {
        for (String header : new String[]{DEBUG + ERROR, STAMP + " Error      -  " + ERROR + " extra",
            STAMP + " Error      -  [UdonBehaviour] Different error"})
            try (Fixture f = new Fixture())
            {
                f.tail.on_line(false, header);
                for (int i = 0; i < 1024; i++) f.tail.on_line(false, "continuation");
                f.complete(); f.assertBlocked();
            }
    }

    @Test void invalidTimestampAfterDumpStillInvalidatesTracking() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.dump(1302);
            f.tail.on_line(false, "2026.99.09 18:26:33 Error      -  " + ERROR);
            f.complete(); f.assertBlocked();
        }
    }

    @Test void oversizedPhysicalLineInsideDumpStillInvalidatesTracking() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            Files.writeString(f.file.toPath(), ERROR_LINE + "\n" + "x".repeat(256 * 1024 + 1) + "\n", StandardCharsets.UTF_8);
            try (RandomAccessFile reader = new RandomAccessFile(f.file, "r")) { f.tail.readLines(0, reader); }
            f.complete(); f.assertBlocked();
        }
    }

    @Test void fileAppendWithPartialPlayerLineCannotAuthorizePrematureExit() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            StringBuilder body = new StringBuilder(ERROR_LINE).append('\n');
            for (int i = 1; i < 1302; i++) body.append("  0x").append(Integer.toHexString(i)).append(": False\n");
            body.append(DEBUG).append("[Behaviour] OnPlayerJoined Synthetic Other (").append(ScarletClientExitMonitorTest.OTHER).append(')');
            Files.writeString(f.file.toPath(), body, StandardCharsets.UTF_8);
            try (RandomAccessFile reader = new RandomAccessFile(f.file, "r"))
            {
                f.tail.readLines(0, reader); f.tail.on_loop();
                assertFalse(f.logs.clientExitTrackingHealthy(), "An incomplete line remains an exit blocker");
                f.exit.evidence(); f.exit.monitor.tick(); f.exit.due();
                assertEquals(0, f.exit.requested.get());
                Files.writeString(f.file.toPath(), "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                f.tail.readLines(0, reader); f.tail.on_loop();
                assertTrue(f.logs.clientExitTrackingHealthy());
                assertEquals(2, f.joins);
                f.exit.monitor.tick(); f.exit.nanos.addAndGet(ScarletClientExitMonitorTest.WAIT); f.exit.monitor.tick();
                assertEquals(0, f.exit.requested.get());
            }
        }
    }

    @Test void newReaderCannotInheritDiagnosticExemption() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.dump(1302);
            ScarletVRChatLogs.VRChatLogTail replacement = f.logs.new VRChatLogTail(f.file);
            for (int i = 0; i < 1025; i++) replacement.on_line(false, "unclassified continuation");
            assertFalse(replacement.trackingValid);
            replacement.stop();
        }
    }
}

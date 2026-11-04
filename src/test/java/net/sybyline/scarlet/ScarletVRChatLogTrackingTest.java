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
        TestLogs(Listener listener) { super(listener); }
        @Override long trackingNowNanos() { return this.now; }
        @Override boolean trackingThreadAlive() { return this.alive; }
        @Override File locateTarget() { return this.target; }
    }

    final class Fixture implements AutoCloseable
    {
        final List<Object[]> authentication = new ArrayList<>();
        final TestLogs logs;
        final ScarletVRChatLogs.VRChatLogTail tail;
        final File file;
        boolean failListener;
        Fixture() throws Exception
        {
            this.file = Files.createTempFile(temporary, "synthetic-log-", ".txt").toFile();
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

    @Test void knownAuthenticationPrefixesDispatchStrictIdentityAndTimestamp() throws Exception
    {
        for (String text : new String[]{"[Behaviour] User Authenticated: " + AUTH,
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

    @Test void unrelatedEmbeddedOrUnprefixedAuthenticationIsNotTrusted() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.accept(false, "User Authenticated: " + AUTH);
            fixture.accept(false, "[API] User Authenticated: " + AUTH);
            fixture.accept(false, "[Behaviour] Some text [User Authenticated: " + AUTH + "]");
            fixture.accept(false, "[Behaviour] User Authenticated:Fake " + AUTH);
            assertTrue(fixture.authentication.isEmpty());
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

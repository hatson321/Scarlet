package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ScarletImportConsoleTest
{
    @Test void firstUpdateAndStageChangesAppearImmediately() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.console.update("Validating and staging archive entries", 0, 20);
            assertTrue(fixture.text().contains("Validating and staging archive entries"));
            String first = fixture.text();
            fixture.console.update("Applying and verifying imported files", 0, 10);
            assertTrue(fixture.text().length() > first.length());
            assertTrue(fixture.text().contains("Applying and verifying imported files"));
        }
    }

    @Test void repeatedSameStageIsThrottledButOneSecondUpdateAppears() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.console.update("Applying and verifying imported files", 0, 100);
            String first = fixture.text();
            fixture.clock.set(TimeUnit.MILLISECONDS.toNanos(250));
            fixture.console.update("Applying and verifying imported files", 1, 100);
            fixture.clock.set(TimeUnit.MILLISECONDS.toNanos(999));
            fixture.console.update("Applying and verifying imported files", 2, 100);
            assertEquals(first, fixture.text());
            fixture.clock.set(TimeUnit.SECONDS.toNanos(1));
            fixture.console.update("Applying and verifying imported files", 3, 100);
            assertTrue(fixture.text().length() > first.length());
            assertTrue(fixture.text().matches("(?s).*3\\s*/\\s*100.*"));
        }
    }

    @Test void completedTotalBypassesThrottleWithoutClaimingWholeImportSuccess() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.console.update("Applying and verifying imported files", 0, 2);
            String first = fixture.text();
            fixture.console.update("Applying and verifying imported files", 2, 2);
            assertTrue(fixture.text().length() > first.length());
            assertTrue(fixture.text().matches("(?s).*2\\s*/\\s*2.*"));
            assertFalse(fixture.text().contains("IMPORT COMPLETE"));
        }
    }

    @Test void unknownTotalDoesNotDisplayBogusPercentageOrNegativeCounter() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.console.update("Preparing selected configuration", -1, -1);
            assertTrue(fixture.text().contains("Preparing selected configuration"));
            assertFalse(fixture.text().contains("-1"));
            assertFalse(fixture.text().contains("NaN"));
            assertFalse(fixture.text().contains("Infinity"));
        }
    }

    @Test void heartbeatClearlyStatesNoNewProgressWithoutDeclaringFailure() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.console.update("Applying credentials", -1, -1);
            String initial = fixture.text();
            fixture.clock.set(TimeUnit.SECONDS.toNanos(4));
            fixture.console.heartbeat();
            assertEquals(initial, fixture.text());
            fixture.clock.set(TimeUnit.SECONDS.toNanos(6));
            fixture.console.heartbeat();
            assertTrue(fixture.text().toLowerCase(Locale.ROOT).contains("no new progress update"));
            assertFalse(fixture.text().contains("IMPORT FAILED"));
            assertFalse(fixture.text().contains("IMPORT COMPLETE"));
        }
    }

    @Test void freshUpdatesResetHeartbeatSilenceEvenWhenConsoleOutputWasThrottled() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.console.update("Applying and verifying imported files", 0, 10);
            fixture.clock.set(TimeUnit.MILLISECONDS.toNanos(500));
            fixture.console.update("Applying and verifying imported files", 1, 10);
            String before = fixture.text();
            fixture.clock.set(TimeUnit.MILLISECONDS.toNanos(5100));
            fixture.console.heartbeat();
            assertEquals(before, fixture.text(), "Silence must be measured from the newest actual progress update");
            fixture.clock.set(TimeUnit.SECONDS.toNanos(6));
            fixture.console.heartbeat();
            assertTrue(fixture.text().toLowerCase(Locale.ROOT).contains("no new progress update"));
        }
    }

    @Test void completePrintsSummaryBackupAndRestartInstructionsThenStopsHeartbeat() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.console.update("Cleaning temporary import files", -1, -1);
            fixture.console.complete("3 data files imported successfully", new File("synthetic-pre-import-backup.zip"));
            String complete = fixture.text();
            assertTrue(complete.contains("IMPORT COMPLETE"));
            assertTrue(complete.contains("3 data files imported successfully"));
            assertTrue(complete.contains("synthetic-pre-import-backup.zip"));
            assertTrue(complete.toLowerCase(Locale.ROOT).contains("restart scarlet"));
            fixture.clock.set(TimeUnit.MINUTES.toNanos(1));
            fixture.console.heartbeat();
            assertEquals(complete, fixture.text());
        }
    }

    @Test void failedPrintsClearFailureWithoutSuccessAndStopsHeartbeat() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.console.update("Import failed; restoring original files", 0, 2);
            fixture.console.failed();
            String failed = fixture.text();
            assertTrue(failed.contains("IMPORT FAILED"));
            assertFalse(failed.contains("IMPORT COMPLETE"));
            fixture.clock.set(TimeUnit.MINUTES.toNanos(1));
            fixture.console.heartbeat();
            assertEquals(failed, fixture.text());
        }
    }

    @Test void closeDoesNotInventAnOutcomeAndDisablesHeartbeat() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.console.update("Validating and staging archive entries", 0, 3);
            fixture.console.close();
            String closed = fixture.text();
            fixture.clock.set(TimeUnit.MINUTES.toNanos(1));
            fixture.console.heartbeat();
            assertEquals(closed, fixture.text());
            assertFalse(closed.contains("IMPORT COMPLETE"));
            assertFalse(closed.contains("IMPORT FAILED"));
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        final AtomicLong clock = new AtomicLong();
        final ScarletImportConsole console = new ScarletImportConsole(stream, clock::get, false);
        String text() { return output.toString(StandardCharsets.UTF_8); }
        @Override public void close() { console.close(); stream.close(); }
    }
}

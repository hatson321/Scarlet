package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Temporary synthetic schedule files only; no live clients, APIs or scheduling actions. */
class ScarletCalendarPersistenceTest
{
    @TempDir Path directory;

    static ScarletCalendar.EventSpec event(String id)
    {
        ScarletCalendar.EventSpec spec = new ScarletCalendar.EventSpec();
        spec.id = id; spec.date = LocalDate.of(2026, 10, 17); spec.time = OffsetTime.parse("12:00:00Z");
        ScarletCalendar.Scheduled receipt = new ScarletCalendar.Scheduled();
        receipt.start = spec.date.atTime(spec.time); receipt.end = receipt.start.plusHours(1);
        receipt.vrchatGroupId = "grp_synthetic"; receipt.vrchatEventId = "cal_synthetic";
        receipt.instanceCreationAttempted = true;
        spec.pending.put(spec.date, receipt);
        return spec;
    }

    static String document(String id)
    {
        ScarletCalendar.DataSpec data = new ScarletCalendar.DataSpec();
        data.alternateGuildSf = "123456789012345678"; data.eventSpecs = new HashMap<>();
        data.eventSpecs.put(id, event(id)); return Scarlet.GSON_PRETTY.toJson(data);
    }

    @Test void genuineFirstRunAndExplicitEmptySchedulesRemainWritable() throws Exception
    {
        Path file = directory.resolve("calendar.json");
        ScarletCalendar calendar = new ScarletCalendar(null, file.toFile());
        assertTrue(Files.isRegularFile(file)); assertTrue(calendar.eventSpecs.isEmpty());
        calendar.eventSpecs.put("one", event("one")); assertTrue(calendar.save());
        ScarletCalendar restored = new ScarletCalendar(null, file.toFile());
        assertEquals("cal_synthetic", restored.eventSpecs.get("one").pending.values().iterator().next().vrchatEventId);
        assertTrue(restored.eventSpecs.get("one").pending.values().iterator().next().instanceCreationAttempted);
        assertTrue(restored.removeAndSave(restored.eventSpecs.get("one")));
        ScarletCalendar empty = new ScarletCalendar(null, file.toFile());
        assertTrue(empty.eventSpecs.isEmpty()); assertTrue(empty.save());
    }

    @Test void failedAndIncompleteExistingDocumentsNeverBecomeEmptyReplacements() throws Exception
    {
        Path file = directory.resolve("calendar.json");
        String[] invalid = { "{", "null", "", "   ", "[]", "{}", "{\"eventSpecs\":null}",
            "{\"eventSpecs\":[]}", "{\"eventSpecs\":{\"one\":null}}", "{\"eventSpecs\":{\"one\":{}}}",
            "{\"eventSpecs\":{},\"alternateGuildSf\":42}", "{\"eventSpecs\":{}} {}",
            "{eventSpecs:{}}", "{\"eventSpecs\":{\"one\":{\"id\":\"other\",\"pending\":{}}}}",
            "{\"eventSpecs\":{\"one\":{\"id\":\"one\",\"pending\":{\"2026-10-17\":null}}}}" };
        for (String original : invalid)
        {
            Files.writeString(file, original);
            ScarletCalendar calendar = new ScarletCalendar(null, file.toFile());
            calendar.eventSpecs.put("replacement", event("replacement"));
            assertFalse(calendar.save(), original);
            assertFalse(calendar.load(), original); assertFalse(calendar.save(), original);
            assertEquals(original, Files.readString(file), original);
            assertFalse(Files.exists(directory.resolve("backups")), "Refused saves must not rotate the damaged original into backups");
        }
    }

    @Test void failedReloadKeepsWholePreviousStateButBlocksActionsUntilExplicitValidRecovery() throws Exception
    {
        Path file = directory.resolve("calendar.json"); String intact = document("retained");
        Files.writeString(file, intact); ScarletCalendar calendar = new ScarletCalendar(null, file.toFile());
        ScarletCalendar.EventSpec original = calendar.eventSpecs.get("retained"); assertNotNull(original);
        String invalid = intact.substring(0, intact.length() - 1); Files.writeString(file, invalid);
        assertFalse(calendar.load()); assertSame(original, calendar.eventSpecs.get("retained"));
        assertEquals("123456789012345678", calendar.alternateGuildSf);
        assertFalse(calendar.save()); assertEquals(invalid, Files.readString(file));
        assertDoesNotThrow(calendar::update, "Disabled calendar must not reach the null API fixture");
        original.active = true;
        assertDoesNotThrow(() -> original.update(calendar, null, null, null, OffsetDateTime.parse("2026-10-17T12:00:00Z")));
        assertDoesNotThrow(() -> original.makeEvent(calendar, null, null, null, LocalDate.of(2026, 10, 18)));
        assertThrows(IOException.class, () -> calendar.removeAndSave(original));
        assertSame(original, calendar.eventSpecs.get("retained"));
        Files.writeString(file, intact); assertTrue(calendar.load());
        assertEquals("cal_synthetic", calendar.eventSpecs.get("retained").pending.values().iterator().next().vrchatEventId);
        assertTrue(calendar.save());
    }

    @Test void existingDirectoryIsNotFirstRunAndItsContentsRemainUntouched() throws Exception
    {
        Path file = Files.createDirectory(directory.resolve("calendar.json"));
        Path sentinel = Files.writeString(file.resolve("keep.txt"), "synthetic sentinel");
        ScarletCalendar calendar = new ScarletCalendar(null, file.toFile());
        assertFalse(calendar.load()); assertFalse(calendar.save());
        assertTrue(Files.isDirectory(file)); assertEquals("synthetic sentinel", Files.readString(sentinel));
    }

    static final class UnreadableCalendar extends ScarletCalendar
    {
        UnreadableCalendar(File file) { super(null, file); }
        @Override Reader openCalendarReader() throws IOException { throw new IOException("Synthetic read denied"); }
    }

    @Test void ioFailureCannotAuthorizeSaveAndNeverChangesOriginalBytes() throws Exception
    {
        Path file = directory.resolve("calendar.json"); String intact = document("one"); Files.writeString(file, intact);
        ScarletCalendar calendar = new UnreadableCalendar(file.toFile());
        calendar.eventSpecs.put("replacement", event("replacement"));
        assertFalse(calendar.save()); assertEquals(intact, Files.readString(file));
    }

    @Test void removingFailedOrPreviouslyLoadedPrimaryDoesNotResetTheFailureLatch() throws Exception
    {
        Path file = directory.resolve("calendar.json"); Files.writeString(file, "null");
        ScarletCalendar failed = new ScarletCalendar(null, file.toFile()); Files.delete(file);
        assertFalse(failed.load()); assertFalse(failed.save()); assertFalse(Files.exists(file));
        Files.writeString(file, document("one")); ScarletCalendar previouslyLoaded = new ScarletCalendar(null, file.toFile());
        Files.delete(file); assertFalse(previouslyLoaded.load()); assertFalse(previouslyLoaded.save());
        assertTrue(previouslyLoaded.eventSpecs.containsKey("one")); assertFalse(Files.exists(file));
    }

    @Test void missingPrimaryWithBackupRequiresManualRecoveryAndDoesNotActivateOldSchedules() throws Exception
    {
        Path file = directory.resolve("calendar.json");
        Path backup = Files.createDirectories(directory.resolve("backups")).resolve("calendar.json.20261017-120000.bak");
        String intact = document("old-receipt"); Files.writeString(backup, intact);
        ScarletCalendar calendar = new ScarletCalendar(null, file.toFile());
        assertTrue(calendar.eventSpecs.isEmpty()); assertFalse(calendar.save()); assertFalse(Files.exists(file));
        assertEquals(intact, Files.readString(backup));
        Files.writeString(file, intact); assertTrue(calendar.load());
        assertTrue(calendar.eventSpecs.containsKey("old-receipt"));
    }
}

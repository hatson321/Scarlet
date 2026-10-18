package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.model.CalendarEvent;
import io.github.vrchatapi.model.CreateCalendarEventRequest;

/** Captured fake API operations and temporary files only; no network or live scheduling. */
class ScarletCalendarReceiptTest
{
    @TempDir Path directory;
    static final String GROUP = "grp_00000000-0000-0000-0000-000000000001";
    static final String EVENT = "cal_00000000-0000-0000-0000-000000000001";
    static final LocalDate DATE = LocalDate.of(2026, 10, 18);

    static final class ScriptedCalendar extends ScarletCalendar
    {
        int saves;
        int failAt = -1;
        ScriptedCalendar(File file) { super(null, file); this.saves = 0; }
        @Override public synchronized boolean save()
        {
            ++this.saves;
            return this.saves != this.failAt && super.save();
        }
    }

    static final class FakeVrc extends ScarletVRChat
    {
        int calls;
        boolean unknown;
        Runnable before;
        FakeVrc() { super(null, null, null); throw new AssertionError("Do not construct the app"); }
        @Override public CalendarEvent createCalendarEvent(String group, CreateCalendarEventRequest request) throws ApiException
        {
            ++this.calls; assertEquals(GROUP, group); assertEquals(Boolean.FALSE, request.getIsDraft());
            if (this.before != null) this.before.run();
            if (this.unknown) throw new ApiException("Synthetic response lost");
            return new CalendarEvent().id(EVENT);
        }
    }

    static ScarletCalendar.EventSpec spec()
    {
        ScarletCalendar.EventSpec spec = new ScarletCalendar.EventSpec();
        spec.id = "synthetic"; spec.date = DATE; spec.time = OffsetTime.parse("12:00:00Z");
        spec.active = true; spec.mirrorOnDiscord = false;
        return spec;
    }

    @Test void nonMirroredEventPersistsIntentBeforeApiAndReceiptImmediatelyAfter() throws Exception
    {
        File file = directory.resolve("calendar.json").toFile();
        ScriptedCalendar calendar = new ScriptedCalendar(file); ScarletCalendar.EventSpec spec = spec();
        calendar.eventSpecs.put(spec.id, spec); FakeVrc vrc = ScarletDiscordSafetyTest.inert(FakeVrc.class);
        vrc.before = () -> {
            ScarletCalendar.Scheduled intent = new ScarletCalendar(null, file).eventSpecs.get(spec.id).pending.get(DATE);
            assertTrue(intent.eventCreationAttempted); assertEquals(GROUP, intent.vrchatGroupId); assertNull(intent.vrchatEventId);
        };
        spec.makeEvent(calendar, vrc, GROUP, null, DATE);
        assertEquals(1, vrc.calls); assertEquals(2, calendar.saves);
        ScarletCalendar restored = new ScarletCalendar(null, file);
        ScarletCalendar.Scheduled receipt = restored.eventSpecs.get(spec.id).pending.get(DATE);
        assertTrue(receipt.eventCreationAttempted); assertEquals(GROUP, receipt.vrchatGroupId); assertEquals(EVENT, receipt.vrchatEventId);
        restored.eventSpecs.get(spec.id).makeEvent(restored, vrc, GROUP, null, DATE);
        assertEquals(1, vrc.calls, "Restart cannot submit the same event again");
    }

    @Test void failedIntentSavePreventsAnyExternalCreation() throws Exception
    {
        ScriptedCalendar calendar = new ScriptedCalendar(directory.resolve("calendar.json").toFile());
        ScarletCalendar.EventSpec spec = spec(); calendar.eventSpecs.put(spec.id, spec); calendar.failAt = 1;
        FakeVrc vrc = ScarletDiscordSafetyTest.inert(FakeVrc.class);
        spec.makeEvent(calendar, vrc, GROUP, null, DATE);
        assertEquals(0, vrc.calls); assertFalse(spec.active);
        assertFalse(spec.pending.get(DATE).eventCreationAttempted);
    }

    @Test void failedReceiptSaveRetainsMemoryReceiptAndDurableNoReplayIntent() throws Exception
    {
        File file = directory.resolve("calendar.json").toFile(); ScriptedCalendar calendar = new ScriptedCalendar(file);
        ScarletCalendar.EventSpec spec = spec(); calendar.eventSpecs.put(spec.id, spec); calendar.failAt = 2;
        FakeVrc vrc = ScarletDiscordSafetyTest.inert(FakeVrc.class);
        spec.makeEvent(calendar, vrc, GROUP, null, DATE);
        assertEquals(1, vrc.calls); assertFalse(spec.active); assertEquals(EVENT, spec.pending.get(DATE).vrchatEventId);
        ScarletCalendar restarted = new ScarletCalendar(null, file);
        ScarletCalendar.EventSpec unresolved = restarted.eventSpecs.get(spec.id);
        assertTrue(unresolved.pending.get(DATE).eventCreationAttempted); assertNull(unresolved.pending.get(DATE).vrchatEventId);
        unresolved.makeEvent(restarted, vrc, GROUP, null, DATE);
        unresolved.update(restarted, null, GROUP, null, DATE.plusDays(30).atTime(spec.time));
        assertTrue(unresolved.pending.containsKey(DATE), "Unknown receipt is retained for manual reconciliation, not expired away");
        assertEquals(1, vrc.calls);
        // The original process still has the confirmed receipt and can retry persistence only.
        spec.update(calendar, null, GROUP, null, DATE.atTime(spec.time));
        assertEquals(EVENT, new ScarletCalendar(null, file).eventSpecs.get(spec.id).pending.get(DATE).vrchatEventId);
        assertEquals(1, vrc.calls);
    }

    @Test void unknownRemoteResultKeepsIntentAndCannotAutomaticallyReplayAfterRestart() throws Exception
    {
        File file = directory.resolve("calendar.json").toFile(); ScriptedCalendar calendar = new ScriptedCalendar(file);
        ScarletCalendar.EventSpec spec = spec(); calendar.eventSpecs.put(spec.id, spec);
        FakeVrc vrc = ScarletDiscordSafetyTest.inert(FakeVrc.class); vrc.unknown = true;
        spec.makeEvent(calendar, vrc, GROUP, null, DATE);
        assertEquals(1, vrc.calls); assertFalse(spec.active); assertTrue(spec.pending.get(DATE).eventCreationAttempted);
        ScarletCalendar restored = new ScarletCalendar(null, file); ScarletCalendar.EventSpec pending = restored.eventSpecs.get(spec.id);
        pending.active = true; pending.makeEvent(restored, vrc, GROUP, null, DATE);
        pending.update(restored, null, GROUP, null, DATE.plusDays(30).atTime(spec.time));
        assertEquals(1, vrc.calls); assertTrue(pending.pending.containsKey(DATE));
    }

    @Test void legacyPendingDateWithoutReceiptIsQuarantinedRatherThanReplayed() throws Exception
    {
        File file = directory.resolve("calendar.json").toFile(); ScriptedCalendar calendar = new ScriptedCalendar(file);
        ScarletCalendar.EventSpec spec = spec(); calendar.eventSpecs.put(spec.id, spec);
        spec.getOrCreatePending(DATE, DATE.atTime(spec.time), DATE.atTime(spec.time).plusHours(1));
        assertTrue(calendar.save());
        ScarletCalendar restored = new ScarletCalendar(null, file); ScarletCalendar.EventSpec legacy = restored.eventSpecs.get(spec.id);
        assertTrue(legacy.pending.get(DATE).eventCreationAttempted);
        FakeVrc vrc = ScarletDiscordSafetyTest.inert(FakeVrc.class);
        legacy.makeEvent(restored, vrc, GROUP, null, DATE);
        legacy.update(restored, null, GROUP, null, DATE.plusDays(30).atTime(spec.time));
        assertEquals(0, vrc.calls); assertTrue(legacy.pending.containsKey(DATE));
    }
}

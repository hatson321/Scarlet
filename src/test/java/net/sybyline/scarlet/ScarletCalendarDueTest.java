package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ScarletCalendarDueTest {
    static class Calendar extends ScarletCalendar {
        int saves;
        Calendar() { super(null, null); }
        @Override public boolean load() { return true; }
        @Override public boolean save() { saves++; return true; }
    }
    static class Spec extends ScarletCalendar.EventSpec {
        int creates;
        @Override public synchronized void makeInstance(ScarletCalendar calendar, ScarletVRChat vrc,
                ScarletCalendar.Scheduled scheduled) {
            creates++;
            scheduled.instanceCreationAttempted = true;
        }
    }
    private ScarletCalendar.Scheduled event() {
        ScarletCalendar.Scheduled s = new ScarletCalendar.Scheduled();
        s.start = OffsetDateTime.parse("2026-10-02T23:30:00Z");
        s.end = s.start.plusHours(2);
        return s;
    }

    @Test void createsOnlyInTheDueWindowIncludingAfterMidnight() {
        ScarletCalendar.Scheduled s = event();
        assertFalse(ScarletCalendar.EventSpec.isInstanceDue(s, s.start.minusDays(1)));
        assertFalse(ScarletCalendar.EventSpec.isInstanceDue(s, s.start.minusNanos(1)));
        assertTrue(ScarletCalendar.EventSpec.isInstanceDue(s, s.start));
        assertTrue(ScarletCalendar.EventSpec.isInstanceDue(s, s.start.plusMinutes(1)));
        assertTrue(ScarletCalendar.EventSpec.isInstanceDue(s, s.start.plusHours(1)));
        assertFalse(ScarletCalendar.EventSpec.isInstanceDue(s, s.end));
        assertFalse(ScarletCalendar.EventSpec.isInstanceDue(s, s.end.plusDays(1)));
    }

    @Test void earlyJoinDefinesDueTimeRatherThanASecondIndependentCondition() {
        ScarletCalendar.Scheduled s = event();
        s.needsCreate = s.start.minusMinutes(15);
        assertFalse(ScarletCalendar.EventSpec.isInstanceDue(s, s.needsCreate.minusNanos(1)));
        assertTrue(ScarletCalendar.EventSpec.isInstanceDue(s, s.needsCreate));
        assertTrue(ScarletCalendar.EventSpec.isInstanceDue(s, s.start.plusMinutes(1)));
        assertTrue(ScarletCalendar.EventSpec.isInstanceDue(s, s.start.withOffsetSameInstant(java.time.ZoneOffset.ofHours(5))));
    }

    @Test void persistedAttemptsAndLocationsPreventDuplicateCreationAfterRestart() {
        ScarletCalendar.Scheduled s = event();
        s.instanceCreationAttempted = true;
        ScarletCalendar.Scheduled restored = Scarlet.GSON_PRETTY.fromJson(
            Scarlet.GSON_PRETTY.toJson(s), ScarletCalendar.Scheduled.class);
        assertFalse(ScarletCalendar.EventSpec.isInstanceDue(restored, s.start));
        s.instanceCreationAttempted = false;
        s.vrchatLocation = "synthetic-location";
        assertFalse(ScarletCalendar.EventSpec.isInstanceDue(s, s.start));
    }

    @Test void actualUpdateDoesNotCreateEarlyOrRepeatAndRetainsActiveOvernightEvent() {
        ScarletCalendar.Scheduled s = event();
        Spec spec = new Spec();
        spec.active = true;
        spec.pending.put(s.start.toLocalDate(), s);
        Calendar calendar = new Calendar();
        spec.update(calendar, null, null, null, s.start.minusDays(1));
        assertEquals(0, spec.creates);
        spec.update(calendar, null, null, null, s.start.plusMinutes(1));
        spec.update(calendar, null, null, null, s.start.plusHours(1));
        assertEquals(1, spec.creates);
        assertEquals(1, spec.pending.size());
        spec.update(calendar, null, null, null, s.end);
        assertTrue(spec.pending.isEmpty());
    }

    @Test void deletionIsDurableIncludingLastSchedule(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        ScarletCalendar calendar = new ScarletCalendar(null, dir.resolve("calendar.json").toFile());
        ScarletCalendar.EventSpec s = new ScarletCalendar.EventSpec();
        s.id = "synthetic";
        s.active = true;
        calendar.eventSpecs.put(s.id, s);
        assertTrue(calendar.save());
        assertTrue(calendar.removeAndSave(s));
        ScarletCalendar restored = new ScarletCalendar(null, dir.resolve("calendar.json").toFile());
        assertTrue(restored.eventSpecs.isEmpty());
    }

    @Test void failedDeletionRestoresOriginalScheduleAndCannotDeleteAReplacement() {
        Calendar calendar = new Calendar() { @Override public boolean save() { return false; } };
        ScarletCalendar.EventSpec s = new ScarletCalendar.EventSpec();
        s.id = "synthetic";
        calendar.eventSpecs.put(s.id, s);
        assertThrows(java.io.IOException.class, () -> calendar.removeAndSave(s));
        assertSame(s, calendar.eventSpecs.get(s.id));
        ScarletCalendar.EventSpec replacement = new ScarletCalendar.EventSpec();
        replacement.id = s.id;
        calendar.eventSpecs.put(s.id, replacement);
        assertDoesNotThrow(() -> assertFalse(calendar.removeAndSave(s)));
        assertSame(replacement, calendar.eventSpecs.get(s.id));
    }
}

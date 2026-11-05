package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class ScarletCalendarZoneTest
{
    @Test void recurringWallTimeFollowsDstAndSurvivesSerialization()
    {
        var event = new ScarletCalendar.EventSpec();
        event.time = OffsetTime.parse("18:00-06:00"); event.timeZoneId = "America/Chicago";
        event = Scarlet.GSON_PRETTY.fromJson(Scarlet.GSON_PRETTY.toJson(event), ScarletCalendar.EventSpec.class);
        assertEquals(ZoneOffset.ofHours(-6), event.startOn(LocalDate.of(2026, 1, 12)).getOffset());
        assertEquals(ZoneOffset.ofHours(-5), event.startOn(LocalDate.of(2026, 7, 12)).getOffset());
        assertEquals(LocalTime.of(18, 0), event.startOn(LocalDate.of(2026, 7, 12)).toLocalTime());
    }
    @Test void gapsAndOverlapsResolveOnceAndLegacyOffsetsRemainFixed()
    {
        var event = new ScarletCalendar.EventSpec(); event.timeZoneId = "America/Chicago";
        event.time = OffsetTime.parse("02:30-06:00");
        assertEquals(OffsetDateTime.parse("2026-03-08T03:30-05:00"), event.startOn(LocalDate.of(2026, 3, 8)));
        event.time = OffsetTime.parse("01:30-06:00");
        assertEquals(OffsetDateTime.parse("2026-11-01T01:30-05:00"), event.startOn(LocalDate.of(2026, 11, 1)));
        event.timeZoneId = null;
        assertEquals(ZoneOffset.ofHours(-6), event.startOn(LocalDate.of(2026, 7, 12)).getOffset());
    }
}

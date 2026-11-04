package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class GroupAuditTypeTest
{
    @Test
    void everyApiIdRoundTripsWithoutChangingWireIds()
    {
        for (GroupAuditType type : GroupAuditType.values())
        {
            assertSame(type, GroupAuditType.of(type.id));
            assertSame(type, GroupAuditType.of(type.id.toUpperCase(Locale.ROOT)));
        }
        assertEquals("group.calendarEvent.create", GroupAuditType.EVENT_CREATE.id);
        assertEquals("group.calendarEvent.delete", GroupAuditType.EVENT_DELETE.id);
        assertNull(GroupAuditType.of(null));
        assertNull(GroupAuditType.of("group.unknown.event"));
    }

    @Test
    void lookupDoesNotDependOnHostLocale()
    {
        Locale original = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            for (GroupAuditType type : GroupAuditType.values())
                assertSame(type, GroupAuditType.of(type.id.toUpperCase(Locale.ROOT)));
        }
        finally
        {
            Locale.setDefault(original);
        }
    }
}

package net.sybyline.scarlet.util;

public interface ScarletURLs
{
    static String vrchatCalendarEvent(String groupId, String eventId)
    {
        return VrcWeb.Home.groupCalendar(groupId, eventId);
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Synthetic log text only: no VRChat installation, log file, or network is accessed. */
class ScarletVRChatSpawnLogTest
{
    private static final String PROP = "prop_00000000-0000-0000-0000-000000000010";
    private static final String USER = "usr_00000000-0000-0000-0000-000000000002";
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 4, 11, 12, 30);

    static class Event
    {
        final String name;
        final Object[] args;
        Event(String name, Object[] args) { this.name = name; this.args = args; }
    }

    static class Fixture implements AutoCloseable
    {
        final List<Event> events = new ArrayList<>();
        final ScarletVRChatLogs logs;
        final ScarletVRChatLogs.VRChatLogTail tail;

        Fixture()
        {
            ScarletVRChatLogs.Listener listener = (ScarletVRChatLogs.Listener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ScarletVRChatLogs.Listener.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("log_playerSpawn"))
                        events.add(new Event(method.getName(), args.clone()));
                    return null;
                });
            logs = new ScarletVRChatLogs(listener);
            // Tail callbacks parse text in memory. Never call run() or start().
            tail = logs.new VRChatLogTail(new File("synthetic-output-log-not-opened.txt"));
        }

        void accept(boolean preamble, String text)
        {
            tail.on_line(preamble, "2026.10.04 11:12:30 Debug      -  " + text);
            tail.on_eof();
        }

        @Override public void close() { logs.close(); }
    }

    static void assertProp(Event event, boolean preamble)
    {
        assertEquals("log_playerSpawnProp", event.name);
        assertArrayEquals(new Object[]{preamble, TIME, USER, PROP}, event.args);
    }

    @Test void currentItemLogDispatchesOnePropEvent() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.accept(false, "[VRCItems] Item " + PROP + " spawned by " + USER);
            assertEquals(1, fixture.events.size());
            assertProp(fixture.events.get(0), false);
        }
    }

    @Test void legacyPropLogRemainsSupported() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.accept(false, "[VRCProps] Prop " + PROP + " spawned by " + USER);
            assertEquals(1, fixture.events.size());
            assertProp(fixture.events.get(0), false);
        }
    }

    @Test void catchUpFlagIsPreservedThenNewEventsBecomeLive() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.accept(true, "[VRCItems] Item " + PROP + " spawned by " + USER);
            fixture.accept(false, "[VRCItems] Item " + PROP + " spawned by " + USER);
            assertEquals(2, fixture.events.size());
            assertProp(fixture.events.get(0), true);
            assertProp(fixture.events.get(1), false);
        }
    }

    @Test void malformedIdsAndUnrelatedMessagesNeverInventSpawnEvents() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            for (String text : new String[]{
                "[VRCItems] Item spawned by " + USER,
                "[VRCItems] Item prop_invalid spawned by " + USER,
                "[VRCItems] Item " + PROP + " spawned by usr_invalid",
                "[VRCItems] Item " + PROP + " spawned by " + USER + " trailing text",
                "[VRCItems] Item " + USER + " spawned by " + PROP,
                "[VRCItems] Item " + PROP + " spawned by ",
                "[VRCItems] Item " + PROP + " despawned by " + USER,
                "[VRCProps] Prop prop_invalid spawned by " + USER,
                "[VRCItems] Prop " + PROP + " spawned by " + USER,
                "[VRCProps] Item " + PROP + " spawned by " + USER,
                "[Behaviour] [VRCItems] Item " + PROP + " spawned by " + USER,
                "[API] Loaded " + PROP
            }) fixture.accept(false, text);
            assertTrue(fixture.events.isEmpty());
            fixture.accept(false, "[VRCItems] Item " + PROP + " spawned by " + USER);
            assertEquals(1, fixture.events.size(), "Malformed entries must not stop later valid events");
            assertProp(fixture.events.get(0), false);
        }
    }

    @Test void existingSharingPedestalFormatStillDispatchesWithoutGuessingOtherEvents() throws Exception
    {
        // Format regressions only: these synthetic rows do not claim the current client emits them.
        for (String type : new String[]{"Image", "Emoji", "Sticker", "Group"})
        {
            try (Fixture fixture = new Fixture())
            {
                String content = (type.equals("Group") ? "grp_" : "file_")
                    + "00000000-0000-0000-0000-000000000020";
                fixture.accept(false, "[SharingManager] User " + USER
                    + " (Synthetic User (Guest)) created sharing pedestal for " + type + " " + content);
                assertEquals(1, fixture.events.size(), type);
                Event event = fixture.events.get(0);
                assertEquals("log_playerSpawnPedestal", event.name);
                assertArrayEquals(new Object[]{false, TIME, "Synthetic User (Guest)", USER, type, content}, event.args);
            }
        }
    }

    @Test void currentItemAndLegacyPropEventsDoNotBecomePedestalEvents() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.accept(false, "[VRCItems] Item " + PROP + " spawned by " + USER);
            fixture.accept(false, "[VRCProps] Prop " + PROP + " spawned by " + USER);
            assertEquals(2, fixture.events.size());
            assertTrue(fixture.events.stream().allMatch(event -> event.name.equals("log_playerSpawnProp")));
        }
    }

    @Test void sharingPedestalParserDoesNotRestrictEventsToOneActor() throws Exception
    {
        // A synthetic received entry proves parser behavior, not that VRChat emits remote spawns.
        try (Fixture fixture = new Fixture())
        {
            String other = "usr_00000000-0000-0000-0000-000000000099";
            fixture.accept(false, "[SharingManager] User " + USER
                + " (First Actor) created sharing pedestal for Group grp_00000000-0000-0000-0000-000000000020");
            fixture.accept(false, "[SharingManager] User " + other
                + " (Other Actor) created sharing pedestal for Emoji inv_00000000-0000-0000-0000-000000000030");
            assertEquals(2, fixture.events.size());
            assertEquals(USER, fixture.events.get(0).args[3]);
            assertEquals(other, fixture.events.get(1).args[3]);
            assertEquals("inv_00000000-0000-0000-0000-000000000030", fixture.events.get(1).args[5]);
        }
    }
}

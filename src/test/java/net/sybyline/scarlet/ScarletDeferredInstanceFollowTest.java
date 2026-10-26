package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

/** Pure event retention only: never queries, starts or signals a client. */
class ScarletDeferredInstanceFollowTest
{
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-23T12:00:00Z");

    @Test void emptyQueueHasNoEvent()
    {
        ScarletDeferredInstanceFollow follow = new ScarletDeferredInstanceFollow();
        assertNull(follow.peek());
        follow.clearIfSame(null);
        assertNull(follow.peek());
    }

    @Test void nullAndBlankInputsDoNotCreateOrReplaceAnEvent()
    {
        ScarletDeferredInstanceFollow follow = new ScarletDeferredInstanceFollow();
        follow.record(null, NOW);
        follow.record("", NOW);
        follow.record(" \t ", NOW);
        follow.record("synthetic-a", null);
        assertNull(follow.peek());
        follow.record("synthetic-a", NOW);
        ScarletDeferredInstanceFollow.Event original = follow.peek();
        follow.record(null, NOW.plusDays(1));
        follow.record("synthetic-b", null);
        assertSame(original, follow.peek());
    }

    @Test void newestTimestampReplacesOlderEventWithoutBuildingBacklog()
    {
        ScarletDeferredInstanceFollow follow = new ScarletDeferredInstanceFollow();
        follow.record("synthetic-a", NOW);
        follow.record("synthetic-b", NOW.plusSeconds(1));
        follow.record("synthetic-c", NOW.minusSeconds(1));
        assertEquals("synthetic-b", follow.peek().location);
        assertEquals(NOW.plusSeconds(1), follow.peek().createdAt);
        follow.clearIfSame(follow.peek());
        assertNull(follow.peek());
    }

    @Test void equalTimestampsPreserveFirstEventDeterministically()
    {
        ScarletDeferredInstanceFollow follow = new ScarletDeferredInstanceFollow();
        follow.record("synthetic-a", NOW);
        ScarletDeferredInstanceFollow.Event original = follow.peek();
        follow.record("synthetic-b", NOW);
        follow.record("synthetic-a", NOW.withOffsetSameInstant(java.time.ZoneOffset.ofHours(2)));
        assertSame(original, follow.peek());
    }

    @Test void newCreationOfSameLocationReplacesItsOldEvent()
    {
        ScarletDeferredInstanceFollow follow = new ScarletDeferredInstanceFollow();
        follow.record("synthetic-a", NOW);
        ScarletDeferredInstanceFollow.Event original = follow.peek();
        follow.record("synthetic-a", NOW.plusSeconds(1));
        assertNotSame(original, follow.peek());
        assertEquals(NOW.plusSeconds(1), follow.peek().createdAt);
    }

    @Test void clearingStaleHandoffDoesNotDropNewerCreation()
    {
        ScarletDeferredInstanceFollow follow = new ScarletDeferredInstanceFollow();
        follow.record("synthetic-a", NOW);
        ScarletDeferredInstanceFollow.Event submitted = follow.peek();
        follow.record("synthetic-b", NOW.plusSeconds(1));
        follow.clearIfSame(submitted);
        assertEquals("synthetic-b", follow.peek().location);
        follow.clearIfSame(null);
        assertEquals("synthetic-b", follow.peek().location);
    }

    @Test void retainedEventIsImmutableWhileNewerOneArrives()
    {
        ScarletDeferredInstanceFollow follow = new ScarletDeferredInstanceFollow();
        follow.record("synthetic-a", NOW);
        ScarletDeferredInstanceFollow.Event snapshot = follow.peek();
        follow.record("synthetic-b", NOW.plusSeconds(1));
        assertEquals("synthetic-a", snapshot.location);
        assertEquals(NOW, snapshot.createdAt);
        assertEquals("synthetic-b", follow.peek().location);
    }
}

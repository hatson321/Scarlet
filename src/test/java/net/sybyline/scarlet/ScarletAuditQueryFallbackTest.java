package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.PaginatedGroupAuditLogEntryList;

class ScarletAuditQueryFallbackTest
{
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2030-01-01T00:00:00Z");
    private static final OffsetDateTime TO = FROM.plusDays(1);

    private static GroupAuditLogEntryUnknown entry(String id, String actor, String event, String target, OffsetDateTime time)
    {
        return new GroupAuditLogEntryUnknown().id(id).actorId(actor).eventType(event).targetId(target).createdAt(time);
    }

    private static PaginatedGroupAuditLogEntryList page(boolean next, GroupAuditLogEntryUnknown... entries)
    {
        return new PaginatedGroupAuditLogEntryList().results(Arrays.stream(entries)
            .map(entry -> entry == null ? null : new io.github.vrchatapi.model.GroupAuditLogEntry(entry))
            .collect(java.util.stream.Collectors.toList())).hasNext(next).totalCount(999);
    }

    @Test
    void reAppliesEveryFilterAndInclusiveDateBoundsAcrossAllPages() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        List<GroupAuditLogEntryUnknown> result = ScarletAuditQueryFallback.collect((limit, offset) -> {
            assertEquals(100, limit);
            calls.incrementAndGet();
            if (offset == 0)
                return page(true,
                    entry("too-old", "a", "kick", "t", FROM.minusNanos(1)),
                    entry("actor-mismatch", "b", "kick", "t", FROM),
                    entry("event-mismatch", "a", "warn", "t", FROM),
                    entry("target-mismatch", "a", "kick", "u", FROM),
                    entry("end", "a", "kick", "t", TO));
            assertEquals(100, offset);
            return page(false,
                entry("start", "a", "kick", "t", FROM.withOffsetSameInstant(java.time.ZoneOffset.ofHours(2))),
                entry("too-new", "a", "kick", "t", TO.plusNanos(1)));
        }, FROM, TO, "a", "kick", "t");
        assertEquals(2, calls.get());
        assertEquals(Arrays.asList("start", "end"), Arrays.asList(result.get(0).getId(), result.get(1).getId()));
        assertEquals(2, result.size(), "Count must use filtered records, never the server's 999 total");
    }

    @Test
    void commaSeparatedAlternativesAreOrWithinAFilterAndAndAcrossFilters() throws Exception
    {
        List<GroupAuditLogEntryUnknown> result = ScarletAuditQueryFallback.collect((n, offset) -> page(false,
            entry("keep", "b", "warn", "u", FROM),
            entry("drop", "b", "ban", "u", FROM)), null, null, " a, b ", "kick,warn", "t,u");
        assertEquals(1, result.size());
        assertEquals("keep", result.get(0).getId());
    }

    @Test
    void noFiltersAndEmptyCompletedResponseAreValid() throws Exception
    {
        assertEquals(1, ScarletAuditQueryFallback.collect((n, o) -> page(false,
            entry("one", null, null, null, FROM)), null, null, null, "", null).size());
        assertEquals(Collections.emptyList(), ScarletAuditQueryFallback.collect((n, o) -> page(false),
            FROM, TO, "a", "kick", "t"));
    }

    @Test
    void missingPagesAndNetworkFailuresDoNotReturnPartialHistory()
    {
        assertThrows(IllegalStateException.class, () -> ScarletAuditQueryFallback.collect((n, offset) ->
            offset == 0 ? page(true, entry("one", "a", "kick", "t", FROM)) : null,
            FROM, TO, "a", "kick", "t"));
        assertThrows(ApiException.class, () -> ScarletAuditQueryFallback.collect((n, offset) -> {
            if (offset > 0) throw new ApiException(503, "synthetic failure");
            return page(true, entry("one", "a", "kick", "t", FROM));
        }, FROM, TO, "a", "kick", "t"));
    }

    @Test
    void rejectsMalformedAndRepeatedPagesRatherThanMisleadingCounts()
    {
        GroupAuditLogEntryUnknown valid = entry("one", "a", "kick", "t", FROM);
        assertThrows(IllegalStateException.class, () -> ScarletAuditQueryFallback.collect((n, o) -> page(true, valid),
            FROM, TO, "a", "kick", "t"));
        assertThrows(IllegalStateException.class, () -> ScarletAuditQueryFallback.collect((n, o) -> page(true),
            FROM, TO, "a", "kick", "t"));
        assertThrows(IllegalStateException.class, () -> ScarletAuditQueryFallback.collect((n, o) ->
            page(false, entry("missing-actor", null, "kick", "t", FROM)), FROM, TO, "a", "kick", "t"));
        assertThrows(IllegalStateException.class, () -> ScarletAuditQueryFallback.collect((n, o) ->
            new PaginatedGroupAuditLogEntryList(), FROM, TO, null, null, null));
    }

    @Test
    void enforcesSafetyLimitWithoutReturningAnUndercount()
    {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ScarletAuditQueryFallback.collect((n, offset) -> {
            calls.incrementAndGet();
            return page(true, entry("id-" + offset, "a", "kick", "t", FROM));
        }, FROM, TO, "a", "kick", "t"));
        assertEquals(ScarletAuditQueryFallback.MAX_PAGES, calls.get());
    }

    @Test
    void rejectsInvalidQueriesBeforeFetching()
    {
        assertThrows(IllegalArgumentException.class, () -> ScarletAuditQueryFallback.collect((n, o) -> {
            fail("Must not fetch a reversed date range"); return null;
        }, TO, FROM, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ScarletAuditQueryFallback.collect((n, o) -> {
            fail("Must not fetch an invalid filter"); return null;
        }, FROM, TO, "a,", null, null));
    }
}

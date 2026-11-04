package net.sybyline.scarlet;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.PaginatedGroupAuditLogEntryList;

/** Bounded, all-or-failure collection of an audit endpoint's compatibility response. */
final class ScarletAuditQueryFallback
{
    static final int BATCH_SIZE = 100;
    static final int MAX_PAGES = 100;

    @FunctionalInterface
    interface Pages
    {
        PaginatedGroupAuditLogEntryList get(int limit, int offset) throws ApiException;
    }

    private ScarletAuditQueryFallback()
    {
    }

    static List<GroupAuditLogEntryUnknown> collect(Pages pages, OffsetDateTime from, OffsetDateTime to,
            String actorIds, String eventTypes, String targetIds) throws ApiException
    {
        if (from != null && to != null && from.isAfter(to))
            throw new IllegalArgumentException("Audit date range is reversed");
        Set<String> actors = filters(actorIds), events = filters(eventTypes), targets = filters(targetIds);
        List<GroupAuditLogEntryUnknown> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < MAX_PAGES; index++)
        {
            if (Thread.currentThread().isInterrupted())
                throw new IllegalStateException("Audit fallback interrupted before completion");
            PaginatedGroupAuditLogEntryList page = pages.get(BATCH_SIZE, index * BATCH_SIZE);
            if (page == null || page.getResults() == null || page.getHasNext() == null)
                throw new IllegalStateException("Incomplete audit fallback page");
            List<GroupAuditLogEntryUnknown> entries = ScarletAuditData.entries(page);
            if (entries.size() > BATCH_SIZE || (entries.isEmpty() && Boolean.TRUE.equals(page.getHasNext())))
                throw new IllegalStateException("Invalid audit fallback pagination");
            for (GroupAuditLogEntryUnknown entry : entries)
            {
                // Missing fields cannot establish exact filter semantics; do not return a
                // plausible-looking partial count or history to a moderation caller.
                if (entry == null || entry.getId() == null || entry.getCreatedAt() == null
                        || (!actors.isEmpty() && entry.getActorId() == null)
                        || (!events.isEmpty() && entry.getEventType() == null)
                        || (!targets.isEmpty() && entry.getTargetId() == null))
                    throw new IllegalStateException("Audit fallback entry lacks required fields");
                if (!seen.add(entry.getId()))
                    throw new IllegalStateException("Audit fallback repeated an entry across pages");
                if ((from == null || !entry.getCreatedAt().isBefore(from))
                        && (to == null || !entry.getCreatedAt().isAfter(to))
                        && (actors.isEmpty() || actors.contains(entry.getActorId()))
                        && (events.isEmpty() || events.contains(entry.getEventType()))
                        && (targets.isEmpty() || targets.contains(entry.getTargetId())))
                    matches.add(entry);
            }
            if (!Boolean.TRUE.equals(page.getHasNext()))
            {
                matches.sort(ScarletVRChat.OLDEST_TO_NEWEST);
                return matches;
            }
        }
        throw new IllegalStateException("Audit fallback exceeded the 100-page safety limit; no partial result returned");
    }

    private static Set<String> filters(String value)
    {
        if (value == null || value.trim().isEmpty())
            return java.util.Collections.emptySet();
        Set<String> values = new HashSet<>();
        Arrays.stream(value.split(",", -1)).map(String::trim).forEach(part -> {
            if (part.isEmpty())
                throw new IllegalArgumentException("Empty audit filter component");
            values.add(part);
        });
        return values;
    }
}

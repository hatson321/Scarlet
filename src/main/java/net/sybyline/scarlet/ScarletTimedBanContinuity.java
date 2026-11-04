package net.sybyline.scarlet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.vrchatapi.api.GroupsApi;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.PaginatedGroupAuditLogEntryList;
import net.sybyline.scarlet.ScarletPendingModActions.TimedBan;

/** Fresh server evidence, never the possibly incomplete local audit cache. */
final class ScarletTimedBanContinuity
{
    static final long AUDIT_TIME_TOLERANCE_SECONDS = 5L;
    private static final int PAGE_SIZE = 100, MAX_ROWS = 10_000;

    enum Verdict { VERIFIED, SUPERSEDED, UNAVAILABLE }

    static final class Proof
    {
        final Verdict verdict;
        final String originalAuditId;
        Proof(Verdict verdict, String originalAuditId) { this.verdict = verdict; this.originalAuditId = originalAuditId; }
    }

    @FunctionalInterface interface Check { Proof check(String group, String user, TimedBan timer); }
    @FunctionalInterface interface MemberLookup { GroupMember get() throws Exception; }
    @FunctionalInterface interface HistoryPage { PaginatedGroupAuditLogEntryList get(int count, int offset) throws Exception; }

    static Proof unavailable() { return new Proof(Verdict.UNAVAILABLE, null); }
    static Proof superseded() { return new Proof(Verdict.SUPERSEDED, null); }

    static Proof check(ScarletVRChat vrc, String group, String user, TimedBan timer)
    {
        if (!timer.hasBanIdentity()) return unavailable();
        Instant start = Instant.parse(timer.originalBanAt).minusSeconds(AUDIT_TIME_TOLERANCE_SECONDS);
        Instant end = Instant.now();
        GroupsApi api = new GroupsApi(vrc.getClient());
        return verify(group, user, timer, start, end,
            () -> api.getGroupMember(group, user),
            (count, offset) -> api.getGroupAuditLogs(group, count, offset,
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC), OffsetDateTime.ofInstant(end, ZoneOffset.UTC),
                null, null, user));
    }

    static Proof verify(String group, String user, TimedBan timer, Instant from, Instant to,
        MemberLookup membership, HistoryPage pages)
    {
        if (!timer.hasBanIdentity() || !group.equals(timer.groupId) || from.isAfter(to)) return unavailable();
        try
        {
            Proof memberCheck = checkMember(membership.get(), group, user, timer);
            if (memberCheck != null) return memberCheck;
            List<GroupAuditLogEntryUnknown> history = collect(group, user, from, to, pages);
            if (history == null) return unavailable();
            GroupAuditLogEntryUnknown original = null;
            for (GroupAuditLogEntryUnknown entry : history)
            {
                if (!isOriginalCandidate(timer, entry)) continue;
                if (original != null) return superseded(); // Same-second events are not ordered by arbitrary IDs.
                original = entry;
            }
            if (original == null) return unavailable(); // Retention gaps or delayed audit publication are not proof.
            if (timer.originalAuditId != null && !timer.originalAuditId.equals(original.getId())) return superseded();
            Instant boundary = Instant.parse(timer.originalBanAt).minusSeconds(AUDIT_TIME_TOLERANCE_SECONDS);
            for (GroupAuditLogEntryUnknown entry : history)
            {
                if (original.getId().equals(entry.getId())) continue;
                if ((GroupAuditType.USER_BAN.id.equals(entry.getEventType()) || GroupAuditType.USER_UNBAN.id.equals(entry.getEventType()))
                    && !entry.getCreatedAt().toInstant().isBefore(boundary)) return superseded();
            }
            // Close changes occurring while history pages were read. VRChat does not expose
            // a conditional-unban operation: the final read/write interval cannot be atomic.
            memberCheck = checkMember(membership.get(), group, user, timer);
            return memberCheck == null ? new Proof(Verdict.VERIFIED, original.getId()) : memberCheck;
        }
        catch (Exception ex)
        {
            return unavailable(); // Includes permission loss, network failure and malformed history.
        }
    }

    private static Proof checkMember(GroupMember member, String group, String user, TimedBan timer)
    {
        if (member == null || !group.equals(member.getGroupId()) || !user.equals(member.getUserId())
            || member.getMembershipStatus() == null || member.getBannedAt() == null) return unavailable();
        return member.getMembershipStatus() != GroupMemberStatus.BANNED
            || !Instant.parse(timer.originalBanAt).equals(member.getBannedAt().toInstant()) ? superseded() : null;
    }

    static boolean isOriginalCandidate(TimedBan timer, GroupAuditLogEntryUnknown entry)
    {
        if (!timer.hasBanIdentity() || entry == null || entry.getCreatedAt() == null || entry.getId() == null
            || !GroupAuditType.USER_BAN.id.equals(entry.getEventType())
            || !timer.originalBanActorId.equals(entry.getActorId())) return false;
        Instant ban = Instant.parse(timer.originalBanAt), audit = entry.getCreatedAt().toInstant();
        return !audit.isBefore(ban.minusSeconds(AUDIT_TIME_TOLERANCE_SECONDS))
            && !audit.isAfter(ban.plusSeconds(AUDIT_TIME_TOLERANCE_SECONDS));
    }

    private static List<GroupAuditLogEntryUnknown> collect(String group, String user, Instant from, Instant to, HistoryPage pages) throws Exception
    {
        List<GroupAuditLogEntryUnknown> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Integer total = null;
        for (int offset = 0; offset <= MAX_ROWS;)
        {
            PaginatedGroupAuditLogEntryList page = pages.get(PAGE_SIZE, offset);
            if (page == null || page.getTotalCount() == null || page.getHasNext() == null || page.getResults() == null) return null;
            if (total == null) total = page.getTotalCount();
            if (!total.equals(page.getTotalCount()) || total < 0 || total > MAX_ROWS) return null;
            List<GroupAuditLogEntryUnknown> rows = ScarletAuditData.entries(page);
            if (rows == null || rows.size() > PAGE_SIZE || rows.size() != page.getResults().size()) return null;
            for (GroupAuditLogEntryUnknown row : rows)
            {
                if (row == null || row.getId() == null || row.getId().isEmpty() || !ids.add(row.getId())
                    || !group.equals(row.getGroupId()) || !user.equals(row.getTargetId()) || row.getEventType() == null
                    || row.getCreatedAt() == null || row.getCreatedAt().toInstant().isBefore(from)
                    || row.getCreatedAt().toInstant().isAfter(to)) return null;
                entries.add(row);
            }
            offset += rows.size();
            if (offset > total) return null;
            if (!page.getHasNext()) return offset == total ? entries : null;
            if (rows.isEmpty() || offset >= total) return null;
        }
        return null;
    }
}

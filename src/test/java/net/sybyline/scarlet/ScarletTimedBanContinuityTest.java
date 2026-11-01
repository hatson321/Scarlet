package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.vrchatapi.model.GroupAuditLogEntry;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.PaginatedGroupAuditLogEntryList;
import net.sybyline.scarlet.ScarletPendingModActions.TimedBan;
import net.sybyline.scarlet.ScarletPendingModActions.TimedBanState;
import net.sybyline.scarlet.ScarletTimedBanContinuity.Proof;
import net.sybyline.scarlet.ScarletTimedBanContinuity.Verdict;

/** Synthetic history and disk reloads only. No live clients, accounts or moderation. */
class ScarletTimedBanContinuityTest
{
    static final String GROUP = ScarletVRChatBanEntryPointsTest.GROUP;
    static final String USER = ScarletVRChatBanEntryPointsTest.TARGET;
    static final String ACTOR = ScarletVRChatBanEntryPointsTest.ACTOR;
    static final String BOT = ScarletVRChatBanEntryPointsTest.BOT;
    static final OffsetDateTime BAN = OffsetDateTime.parse("2026-10-12T12:00:00Z");
    static final Instant FROM = BAN.toInstant().minusSeconds(5), TO = BAN.toInstant().plusSeconds(3600);
    @TempDir Path temp;
    final AtomicLong clock = new AtomicLong(BAN.toInstant().toEpochMilli());

    ScarletPendingModActions store() { return new ScarletPendingModActions(null, temp.resolve("pending.json").toFile(), clock::get); }

    TimedBan active(ScarletPendingModActions pending)
    {
        TimedBan timer = pending.prepareTimedBan(GROUP, USER, clock.get() + 1000, ACTOR);
        assertTrue(pending.activateTimedBan(USER, timer, BAN, BOT));
        clock.addAndGet(2000);
        return pending.getTimedBan(USER);
    }

    static GroupMember member() { return new GroupMember().groupId(GROUP).userId(USER).membershipStatus(GroupMemberStatus.BANNED).bannedAt(BAN); }
    static GroupAuditLogEntryUnknown event(String id, String type, int seconds)
    {
        return new GroupAuditLogEntryUnknown().id("gaud_" + id).eventType(type).groupId(GROUP).targetId(USER)
            .actorId(BOT).actorDisplayName("Synthetic operator").description("Synthetic event").createdAt(BAN.plusSeconds(seconds));
    }
    static GroupAuditLogEntryUnknown original() { return event("original", "group.user.ban", 1); }
    static PaginatedGroupAuditLogEntryList page(List<GroupAuditLogEntryUnknown> events, int total, boolean more)
    {
        List<GroupAuditLogEntry> rows = new ArrayList<>();
        for (GroupAuditLogEntryUnknown event : events) rows.add(new GroupAuditLogEntry(event));
        return new PaginatedGroupAuditLogEntryList().results(rows).totalCount(total).hasNext(more);
    }
    static Proof proof(TimedBan timer, List<GroupAuditLogEntryUnknown> events)
    {
        return ScarletTimedBanContinuity.verify(GROUP, USER, timer, FROM, TO, ScarletTimedBanContinuityTest::member,
            (count, offset) -> page(events, events.size(), false));
    }
    void process(ScarletPendingModActions pending, ScarletTimedBanContinuity.Check proof, AtomicInteger calls)
    {
        ScarletTimedBanProcessor.process(pending, () -> GROUP, clock::get, (g, u) -> GroupMemberStatus.BANNED, proof,
            (g, u) -> { calls.incrementAndGet(); return ScarletVRChat.GroupModerationResult.SUCCEEDED; });
    }

    @Test void confirmedOriginalBanExpiresAfterRestartWithFreshCompleteProof()
    {
        active(store());
        ScarletPendingModActions restarted = store();
        AtomicInteger calls = new AtomicInteger();
        process(restarted, (g, u, t) -> proof(t, Collections.singletonList(original())), calls);
        assertEquals(1, calls.get());
        assertNull(store().getTimedBan(USER));
    }

    @Test void sameGroupExternalUnbanAndPermanentRebanCancelsBeforeDiscordDeliveryAndSurvivesReload() throws Exception
    {
        ScarletPendingModActions pending = store();
        active(pending);
        ScarletVRChatBanEntryPointsTest.Fixture f = new ScarletVRChatBanEntryPointsTest.Fixture(temp.resolve("audit"));
        ScarletDiscordSafetyTest.put(f.scarlet, "pendingModActions", pending);
        f.vrc.currentUserId = BOT;
        ScarletSecretStaffList secret = ScarletDiscordSafetyTest.inert(ScarletSecretStaffList.class);
        ScarletDiscordSafetyTest.put(secret, "secretStaffUserIds", new HashSet<String>());
        ScarletDiscordSafetyTest.put(f.scarlet, "secretStaffList", secret);
        AtomicInteger emitted = new AtomicInteger();
        ScarletDiscord discord = ScarletDiscordSafetyTest.discord((proxy, method, values) -> {
            if (method.getName().equals("emitUserModeration")) emitted.incrementAndGet();
            return null;
        });
        discord.process(f.scarlet, event("manual_unban", "group.user.unban", 20).actorId(ACTOR));
        discord.process(f.scarlet, event("permanent_reban", "group.user.ban", 30).actorId(ACTOR));
        assertEquals(2, emitted.get(), "Timer reconciliation must not drop moderation logging");
        assertEquals(TimedBanState.QUARANTINED, store().getTimedBan(USER).state);
        AtomicInteger calls = new AtomicInteger();
        process(store(), (g, u, t) -> { fail("Quarantined timers cannot be claimed"); return null; }, calls);
        assertEquals(0, calls.get());
    }

    @Test void missedAuditDuringDowntimeCannotUnbanReplacementEvenIfLocalCacheHasOnlyOriginal()
    {
        TimedBan timer = active(store());
        assertEquals(Verdict.SUPERSEDED, ScarletTimedBanContinuity.verify(GROUP, USER, timer, FROM, TO,
            () -> member().bannedAt(BAN.plusMinutes(2)), (n, o) -> { fail("Different current ban needs no history"); return null; }).verdict);
        AtomicInteger calls = new AtomicInteger();
        process(store(), (g, u, t) -> proof(t, Arrays.asList(original(), event("unban", "group.user.unban", 20), event("reban", "group.user.ban", 30))), calls);
        assertEquals(0, calls.get());
        assertEquals(TimedBanState.QUARANTINED, store().getTimedBan(USER).state);
    }

    @Test void legacyActiveTimerWithoutIdentityIsDurablyQuarantined() throws Exception
    {
        ScarletPendingModActions pending = store();
        TimedBan timer = active(pending);
        pending.timedBans.get(USER).originalBanAt = null;
        assertTrue(pending.save());
        assertEquals(TimedBanState.QUARANTINED, store().getTimedBan(USER).state);
        assertEquals(timer.recordId, store().getTimedBan(USER).recordId);
        assertTrue(store().timedBanReviewSummary(GROUP).contains("1 unconfirmed"));
    }

    @Test void legacyActiveTimerIsIncludedInReviewNoticeBeforeExpiry()
    {
        ScarletPendingModActions pending = store();
        active(pending);
        TimedBan legacy = pending.timedBans.get(USER);
        legacy.originalBanAt = null;
        legacy.expiresAt = clock.get() + 3_600_000;
        assertTrue(legacy.isActiveFor(GROUP));
        assertNull(legacy.reviewReason);
        String summary = pending.timedBanReviewSummary(GROUP);
        assertNotNull(summary);
        assertTrue(summary.contains("1 unconfirmed"));
        assertTrue(summary.contains("Automatic unbans are currently blocked pending verification or manual review"));
    }

    @Test void missingServerIdentityCannotActivateNewTimer()
    {
        ScarletPendingModActions pending = store();
        TimedBan timer = pending.prepareTimedBan(GROUP, USER, clock.get() + 1000, ACTOR);
        assertFalse(pending.activateTimedBan(USER, timer, null, BOT));
        assertEquals(TimedBanState.QUARANTINED, store().getTimedBan(USER).state);
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "exception", "truncated", "missing_total", "missing_next", "duplicate", "wrong_group", "wrong_user", "wrong_time", "missing_id", "missing_results", "too_many"})
    void missingMalformedOrPartialEvidenceNeverAuthorizesUnban(String fault)
    {
        TimedBan timer = active(store());
        Proof result = ScarletTimedBanContinuity.verify(GROUP, USER, timer, FROM, TO, ScarletTimedBanContinuityTest::member, (n, o) -> {
            if (fault.equals("exception")) throw new java.io.IOException("Synthetic timeout");
            List<GroupAuditLogEntryUnknown> rows = new ArrayList<>(Collections.singletonList(original()));
            if (fault.equals("empty")) rows.clear();
            if (fault.equals("duplicate")) rows.add(original());
            if (fault.equals("wrong_group")) rows.get(0).setGroupId("grp_other");
            if (fault.equals("wrong_user")) rows.get(0).setTargetId("usr_other");
            if (fault.equals("wrong_time")) rows.get(0).setCreatedAt(BAN.minusDays(1));
            if (fault.equals("missing_id")) rows.get(0).setId(null);
            PaginatedGroupAuditLogEntryList page = page(rows, rows.size(), false);
            if (fault.equals("truncated")) page.setTotalCount(2);
            if (fault.equals("missing_total")) page.setTotalCount(null);
            if (fault.equals("missing_next")) page.setHasNext(null);
            if (fault.equals("missing_results")) page.setResults(null);
            if (fault.equals("too_many")) page.setTotalCount(10_001);
            return page;
        });
        assertEquals(Verdict.UNAVAILABLE, result.verdict);
        AtomicInteger calls = new AtomicInteger();
        process(store(), (g, u, t) -> result, calls);
        assertEquals(0, calls.get());
        assertNotNull(store().getTimedBan(USER));
    }

    @Test void transientHistoryFailureDefersWithoutPermanentCancellationAndFreshProofCanRecover()
    {
        ScarletPendingModActions pending = store();
        active(pending);
        AtomicInteger calls = new AtomicInteger();
        process(pending, (g, u, t) -> ScarletTimedBanContinuity.unavailable(), calls);
        assertEquals(TimedBanState.ACTIVE, pending.getTimedBan(USER).state);
        assertTrue(pending.getTimedBan(USER).nextVerificationAt > clock.get());
        String summary = pending.timedBanReviewSummary(GROUP);
        assertNotNull(summary);
        assertTrue(summary.contains("currently blocked pending verification or manual review"));
        assertFalse(summary.contains("will not send automatic unbans"));
        process(pending, (g, u, t) -> { fail("Backoff must avoid repeated history reads"); return null; }, calls);
        clock.addAndGet(301_000);
        process(pending, (g, u, t) -> proof(t, Collections.singletonList(original())), calls);
        assertEquals(1, calls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"group.user.ban", "group.user.unban"})
    void sameTimestampAndOutOfOrderHistoryCannotHideReplacement(String type)
    {
        TimedBan timer = active(store());
        List<GroupAuditLogEntryUnknown> rows = Arrays.asList(event("replacement", type, 1), original());
        assertEquals(Verdict.SUPERSEDED, proof(timer, rows).verdict);
    }

    @Test void changedAuditIdentityCannotBeRebound()
    {
        ScarletPendingModActions pending = store();
        TimedBan timer = active(pending);
        assertTrue(pending.confirmTimedBanAudit(USER, timer, "gaud_different_original"));
        assertEquals(Verdict.SUPERSEDED, proof(store().getTimedBan(USER), Collections.singletonList(original())).verdict);
    }

    @Test void lastMembershipReadDetectsReplacementDuringHistoryFetch()
    {
        TimedBan timer = active(store());
        AtomicInteger reads = new AtomicInteger();
        Proof result = ScarletTimedBanContinuity.verify(GROUP, USER, timer, FROM, TO,
            () -> reads.incrementAndGet() == 1 ? member() : member().bannedAt(BAN.plusSeconds(30)),
            (n, o) -> page(Collections.singletonList(original()), 1, false));
        assertEquals(Verdict.SUPERSEDED, result.verdict);
        assertEquals(2, reads.get());
    }

    @Test void multipleCompletePagesAreCheckedIncludingInterveningEventsOnLaterPage()
    {
        TimedBan timer = active(store());
        List<GroupAuditLogEntryUnknown> rows = new ArrayList<>();
        rows.add(original());
        for (int i = 1; i < 101; i++) rows.add(event("other_" + i, "group.instance.warn", i));
        AtomicInteger pages = new AtomicInteger();
        ScarletTimedBanContinuity.HistoryPage fetch = (n, o) -> {
            pages.incrementAndGet();
            return page(rows.subList(o, Math.min(rows.size(), o + n)), rows.size(), o + n < rows.size());
        };
        assertEquals(Verdict.VERIFIED, ScarletTimedBanContinuity.verify(GROUP, USER, timer, FROM, TO, ScarletTimedBanContinuityTest::member, fetch).verdict);
        assertEquals(2, pages.get());
        rows.set(100, event("later_unban", "group.user.unban", 200));
        assertEquals(Verdict.SUPERSEDED, ScarletTimedBanContinuity.verify(GROUP, USER, timer, FROM, TO, ScarletTimedBanContinuityTest::member, fetch).verdict);
    }

    @Test void changingTotalsAndEmptyNextPageFailClosed()
    {
        TimedBan timer = active(store());
        for (boolean empty : new boolean[]{true, false})
            assertEquals(Verdict.UNAVAILABLE, ScarletTimedBanContinuity.verify(GROUP, USER, timer, FROM, TO, ScarletTimedBanContinuityTest::member,
                (n, o) -> o == 0 ? page(Collections.singletonList(original()), 2, true)
                    : page(Collections.emptyList(), empty ? 2 : 3, true)).verdict);
    }

    @Test void oldAuditEventsAreIgnoredButNewerUnbanPersistsQuarantineEvenWhenDeliveryIsRepeated()
    {
        ScarletPendingModActions pending = store();
        TimedBan timer = active(pending);
        pending.observeTimedBanAudit(event("old", "group.user.unban", -20));
        pending.observeTimedBanAudit(original());
        assertTrue(pending.getTimedBan(USER).isActiveFor(GROUP));
        pending.observeTimedBanAudit(event("new", "group.user.unban", 20));
        pending.observeTimedBanAudit(original());
        assertEquals(TimedBanState.QUARANTINED, store().getTimedBan(USER).state);
        assertEquals(timer.recordId, store().getTimedBan(USER).recordId);
    }

    @Test void quarantineSaveFailureBlocksLiveTimerAndReloadStillRequiresServerProof()
    {
        ScarletTimedBanPersistenceTest.FailingStore pending = new ScarletTimedBanPersistenceTest.FailingStore(temp.resolve("pending.json").toFile(), clock);
        active(pending);
        pending.failAll = true;
        pending.observeTimedBanAudit(event("unban", "group.user.unban", 20));
        assertEquals(TimedBanState.QUARANTINED, pending.getTimedBan(USER).state);
        AtomicInteger calls = new AtomicInteger();
        process(pending, (g, u, t) -> { fail("Live quarantine cannot be reversed by failed writes"); return null; }, calls);
        process(store(), (g, u, t) -> proof(t, Arrays.asList(original(), event("unban", "group.user.unban", 20))), calls);
        assertEquals(0, calls.get());
        pending.failAll = false;
        process(pending, (g, u, t) -> null, calls);
        assertEquals(TimedBanState.QUARANTINED, store().getTimedBan(USER).state);
    }

    @Test void proofSaveFailureAndReplacementDuringProofSendNoUnban()
    {
        ScarletTimedBanPersistenceTest.FailingStore pending = new ScarletTimedBanPersistenceTest.FailingStore(temp.resolve("pending.json").toFile(), clock);
        active(pending);
        pending.failNext(1);
        AtomicInteger calls = new AtomicInteger();
        process(pending, (g, u, t) -> proof(t, Collections.singletonList(original())), calls);
        assertEquals(0, calls.get());
        clock.addAndGet(301_000);
        process(pending, (g, u, t) -> {
            pending.observeTimedBanAudit(event("replacement", "group.user.ban", 30));
            return proof(t, Collections.singletonList(original()));
        }, calls);
        assertEquals(0, calls.get());
        assertEquals(TimedBanState.QUARANTINED, store().getTimedBan(USER).state);
    }
}

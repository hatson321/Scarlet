package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.User;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonInteraction;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

/** Offline local summaries: isolated synthetic files, no application startup or real profile. */
class ScarletModerationSummaryFailureTest
{
    @TempDir Path temp;
    static final OffsetDateTime END = OffsetDateTime.parse("2026-10-15T12:00:00Z");
    static final String GROUP = "grp_test";

    static class OfflineVRChat extends ScarletVRChat
    {
        private OfflineVRChat() { super(null, null, null); }

        @Override public List<GroupAuditLogEntryUnknown> auditQueryHistorical(OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds)
        { throw new AssertionError("A saved-history summary must not query remote audit history"); }

        @Override public List<GroupAuditLogEntryUnknown> auditQuery(OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds)
        { throw new AssertionError("A saved-history summary must not query remote audit history"); }

        @Override public User getUser(String userId)
        { throw new AssertionError("Summary display names must not trigger profile lookups"); }

        @Override public User getUser(String userId, long minEpoch)
        { throw new AssertionError("Even cache-preferred profile lookups can contact VRChat"); }
    }

    class Fixture
    {
        final Scarlet scarlet = inert(Scarlet.class);
        final ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        final OfflineVRChat vrc = inert(OfflineVRChat.class);
        final ScarletData data = new ScarletData(temp.toFile());
        final ScarletStaffList staff = inert(ScarletStaffList.class);
        final ScarletSecretStaffList secrets = inert(ScarletSecretStaffList.class);
        final List<MessageEmbed> embeds = new ArrayList<>();
        final Message sent = (Message) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Message.class},
            (proxy, method, arguments) -> null);
        int completions;

        Fixture() throws Exception
        {
            put(this.vrc, "groupId", GROUP);
            put(this.scarlet, "vrc", this.vrc);
            put(this.scarlet, "data", this.data);
            put(this.staff, "staffUserIds", new HashSet<String>());
            put(this.staff, "staffNames", new HashMap<String, String>());
            put(this.scarlet, "staffList", this.staff);
            put(this.secrets, "secretStaffUserIds", new HashSet<String>());
            put(this.scarlet, "secretStaffList", this.secrets);
            put(this.discord, "scarlet", this.scarlet);
            put(this.discord, "summaryWorkerLock", new Object());
            ScarletSettings.FileValued<?> onlyActivity = inert(ScarletSettings.FileValued.class);
            put(onlyActivity, "cached", true);
            put(this.discord, "moderationSummary_onlyActivity", onlyActivity);
        }

        Fixture staff(String... ids) { this.staff.staffUserIds.addAll(Arrays.asList(ids)); return this; }
        Fixture secret(String... ids) { this.secrets.secretStaffUserIds.addAll(Arrays.asList(ids)); return this; }

        ScarletData.AuditEntryMetadata event(String id, String type, String actor, OffsetDateTime time)
        {
            ScarletData.AuditEntryMetadata metadata = new ScarletData.AuditEntryMetadata();
            metadata.entry = new GroupAuditLogEntryUnknown().id(id).groupId(GROUP)
                .eventType(type).actorId(actor).actorDisplayName(actor + " display")
                .targetId("usr_target").createdAt(time);
            this.save(metadata);
            return metadata;
        }

        void save(ScarletData.AuditEntryMetadata metadata)
        { this.data.auditEntryMetadata(metadata.entry.getId(), metadata); }

        MessageCreateAction send(MessageEmbed embed)
        {
            this.embeds.add(embed);
            return (MessageCreateAction) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MessageCreateAction.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("complete") || method.getName().equals("completeAfter"))
                    {
                        this.completions++;
                        return this.sent;
                    }
                    if (method.getReturnType().isInstance(proxy)) return proxy;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        }

        Message run() { return this.run(28L * 24L); }
        Message run(long hours) { return this.discord.emitModSummary(this.scarlet, END, hours, this::send); }
        String text()
        {
            StringBuilder all = new StringBuilder();
            for (MessageEmbed embed : this.embeds)
                all.append(embed.getTitle()).append('\n').append(embed.getDescription()).append('\n');
            return all.toString();
        }

        void assertIncomplete()
        {
            assertSame(this.sent, this.run());
            assertEquals(1, this.embeds.size(), "Unsafe or aborted local history scans must not publish result pages");
            assertEquals(1, this.completions);
            assertEquals("Moderation Summary (incomplete)", this.embeds.get(0).getTitle());
            assertFalse(this.text().contains("Staff totals:"));
            assertFalse(this.text().contains("0/0/0/0"));
            assertEquals(END, this.embeds.get(0).getTimestamp());
        }

        void corrupt(String path) throws Exception
        {
            Path file = temp.resolve(path);
            Files.createDirectories(file.getParent());
            Files.write(file, "{broken-json".getBytes(StandardCharsets.UTF_8));
        }

        void assertPartial(int unreadable)
        {
            assertEquals("Moderation Summary (partial saved local history)", this.embeds.get(0).getTitle());
            assertTrue(this.embeds.get(0).getDescription().contains(
                "Files that could not be read are excluded from all moderation counts. See the final notice for their file count."), this.text());
            String notice = unreadable + " file" + (unreadable == 1 ? "" : "s")
                + " could not be read and " + (unreadable == 1 ? "was" : "were")
                + " excluded from these totals. Check Scarlet's local logs for details.";
            assertTrue(this.embeds.get(this.embeds.size() - 1).getDescription().trim().endsWith(notice), this.text());
            assertTrue(this.text().lastIndexOf("Staff totals:") < this.text().lastIndexOf(notice), this.text());
            for (MessageEmbed embed : this.embeds)
                assertTrue(embed.getDescription().length() <= 4096, "Partial notice must respect Discord's description limit");
            assertEquals(this.embeds.size(), this.completions);
        }
    }

    @Test void fourWeeksOfSavedEventsWorkWithoutRemoteAuditOrProfileCalls() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        f.event("gaud_old", "group.user.ban", "usr_staff", END.minusDays(21));
        f.event("gaud_recent", "group.instance.warn", "usr_staff", END.minusHours(2));
        f.event("gaud_outside", "group.user.ban", "usr_staff", END.minusDays(29));
        assertSame(f.sent, f.run());
        assertEquals("Moderation Summary (saved local history)", f.embeds.get(0).getTitle());
        assertTrue(f.text().contains("Staff totals: 1 / 0 / 1 / 0 / 0"), f.text());
        assertTrue(f.text().toLowerCase().contains("local"));
    }

    @Test void inclusiveRequestedBoundariesDoNotCountEventsOutsideThePeriod() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        f.event("gaud_start", "group.instance.warn", "usr_staff", END.minusDays(28));
        f.event("gaud_end", "group.instance.warn", "usr_staff", END);
        f.event("gaud_before", "group.instance.warn", "usr_staff", END.minusDays(28).minusNanos(1));
        f.event("gaud_after", "group.instance.warn", "usr_staff", END.plusNanos(1));
        f.run();
        assertTrue(f.text().contains("Staff totals: 2 / 0 / 0 / 0 / 0"), f.text());
    }

    @Test void membershipHeadlineIncludesSavedInvitesAsWellAsStaffInvites() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        f.event("gaud_join", "group.member.join", "usr_member", END.minusHours(1));
        f.event("gaud_leave", "group.member.leave", "usr_member", END.minusHours(1));
        f.event("gaud_remove", "group.member.remove", "usr_staff", END.minusHours(1));
        f.event("gaud_invite1", "group.invite.create", "usr_staff", END.minusHours(1));
        f.event("gaud_invite2", "group.invite.create", "usr_staff", END.minusHours(1));
        f.run();
        assertTrue(f.text().contains("1/1/1/2 group joins/leaves/kicks/invites"), f.text());
        assertTrue(f.text().contains("Staff totals: 0 / 0 / 0 / 2 / 0"), f.text());
    }

    @Test void secretOriginalOrAuxiliaryActorsRedactedTrainingAndForeignEventsStayExcluded() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff", "usr_bot", "usr_secret").secret("usr_secret");
        ScarletData.AuditEntryMetadata credited = f.event("gaud_credited", "group.user.ban", "usr_bot", END.minusDays(1));
        credited.auxActorId = "usr_staff";
        credited.auxActorDisplayName = "Visible staff";
        f.save(credited);
        f.event("gaud_secret", "group.user.ban", "usr_secret", END.minusDays(1));
        ScarletData.AuditEntryMetadata secretAux = f.event("gaud_secret_aux", "group.user.ban", "usr_bot", END.minusDays(1));
        secretAux.auxActorId = "usr_secret";
        secretAux.auxActorDisplayName = "Never disclose me";
        f.save(secretAux);
        ScarletData.AuditEntryMetadata redacted = f.event("gaud_redacted", "group.user.ban", "usr_staff", END.minusDays(1));
        redacted.entryRedacted = true;
        f.save(redacted);
        f.event("gaud_training-test", "group.user.ban", "usr_staff", END.minusDays(1));
        ScarletData.AuditEntryMetadata foreign = f.event("gaud_foreign", "group.user.ban", "usr_staff", END.minusDays(1));
        foreign.entry.setGroupId("grp_other");
        f.save(foreign);
        f.run();
        assertTrue(f.text().contains("Staff totals: 0 / 0 / 1 / 0 / 0"), f.text());
        assertTrue(f.text().contains("Visible staff"), f.text());
        assertFalse(f.text().contains("usr_secret"));
        assertFalse(f.text().contains("Never disclose me"));
    }

    @Test void bundledKickAndBanRemainSeparateEventsButDuplicateReceiptIsCountedOnlyOnce() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        ScarletData.AuditEntryMetadata ban = f.event("gaud_ban", "group.user.ban", "usr_staff", END.minusHours(1));
        ban.setAuxData("deliveryState", "delivered");
        f.save(ban);
        new ScarletAuditOutbox(temp.toFile()).save(ban);
        ScarletData.AuditEntryMetadata kick = f.event("gaud_kick", "group.instance.kick", "usr_staff", END.minusHours(1));
        kick.parentEventId = ban.entry.getId();
        f.save(kick);
        f.run();
        assertTrue(f.text().contains("Staff totals: 0 / 1 / 1 / 0 / 0"), f.text());
    }

    @Test void locallySavedStaffJoinNameAndUserIdFallbackNeedNoProfileLookup() throws Exception
    {
        Fixture f = new Fixture().staff("usr_joiner", "usr_no_name");
        f.data.customEvent_new(GroupAuditTypeEx.STAFF_JOIN, END.minusDays(15), "usr_joiner", "Saved joiner",
            "wrld_test:123~group(" + GROUP + ")~groupAccessType(members)", null);
        ScarletData.AuditEntryMetadata noName = f.event("gaud_no_name", "group.instance.warn", "usr_no_name", END.minusDays(1));
        noName.entry.setActorDisplayName(null);
        f.save(noName);
        f.run();
        assertTrue(f.text().contains("Saved joiner"), f.text());
        assertTrue(f.text().contains("usr_no_name"), f.text());
        assertTrue(f.text().contains("Staff totals: 1 / 0 / 0 / 0 / 1"), f.text());
    }

    @Test void localJoinsAreGroupScopedAndExcludeTrainingAndSecretStaff() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff", "usr_secret").secret("usr_secret");
        String local = "wrld_test:123~group(" + GROUP + ")";
        f.data.customEvent_new(GroupAuditTypeEx.USER_JOIN, END.minusHours(1), "usr_member", "Member", local, null);
        f.data.customEvent_new(GroupAuditTypeEx.USER_JOIN, END.minusHours(1), "usr_other", "Other", "wrld_test:123~group(grp_other)", null);
        f.data.customEvent_new(GroupAuditTypeEx.USER_JOIN, END.minusHours(1), "usr_training-test", "[TRAINING] Test", local, null);
        f.data.customEvent_new(GroupAuditTypeEx.STAFF_JOIN, END.minusHours(1), "usr_staff", "Visible", local, null);
        f.data.customEvent_new(GroupAuditTypeEx.STAFF_JOIN, END.minusHours(1), "usr_secret", "Secret", local, null);
        f.data.customEvent_new(GroupAuditTypeEx.STAFF_JOIN, END.minusHours(1), "usr_staff", "Other group", "wrld_test:123~group(grp_other)", null);
        f.run();
        assertTrue(f.text().contains("1 recorded instance joins"), f.text());
        assertTrue(f.text().contains("Staff totals: 0 / 0 / 0 / 0 / 1"), f.text());
        assertFalse(f.text().contains("usr_secret"));
    }

    @Test void emptyLocalHistoryIsExplicitlyNotAClaimOfCompleteRemoteHistory() throws Exception
    {
        Fixture f = new Fixture();
        f.run();
        assertEquals("Moderation Summary (saved local history)", f.embeds.get(0).getTitle());
        String text = f.text().toLowerCase();
        assertTrue(text.contains("local"), text);
        assertTrue(text.contains("not collected") || text.contains("never collected") || text.contains("missing") || text.contains("not prove"), text);
        assertTrue(f.text().contains("Staff totals: 0 / 0 / 0 / 0 / 0"), f.text());
    }

    @Test void corruptLocalAuditsAreSkippedWithReadableTotalsAndATrailingFileCount() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        f.event("gaud_good", "group.user.ban", "usr_staff", END.minusDays(1));
        f.event("gaud_good_warn", "group.instance.warn", "usr_staff", END.minusDays(20));
        f.corrupt("gaud/gaud_corrupt");
        f.corrupt("audit-outbox/gaud_broken_receipt.json");
        assertSame(f.sent, f.run());
        assertTrue(f.text().contains("Staff totals: 1 / 0 / 1 / 0 / 0"), f.text());
        f.assertPartial(2);
        assertFalse(f.text().contains("gaud_corrupt"), "File identifiers belong in local logs, not the Discord summary");
        assertFalse(f.text().contains("gaud_broken_receipt"));
        assertFalse(f.text().contains(temp.toString()));
    }

    @Test void corruptAuthoritativePrivacyRecordCannotBeReplacedWithItsReadableOutboxReceipt() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff", "usr_bot").secret("usr_secret");
        f.event("gaud_good", "group.instance.warn", "usr_staff", END.minusDays(1));
        ScarletData.AuditEntryMetadata record = f.event("gaud_privacy", "group.user.ban", "usr_bot", END.minusDays(1));
        record.setAuxData("deliveryState", "delivered");
        new ScarletAuditOutbox(temp.toFile()).save(record);
        record.auxActorId = "usr_secret";
        record.entryRedacted = true;
        f.save(record);
        f.corrupt("gaud/gaud_privacy");
        f.run();
        assertTrue(f.text().contains("Staff totals: 1 / 0 / 0 / 0 / 0"), f.text());
        assertFalse(f.text().contains("usr_bot"));
        assertFalse(f.text().contains("usr_secret"));
        f.assertPartial(1);
    }

    @Test void allUnreadableHistoryStillProducesZeroTotalsClearlyMarkedPartial() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        f.corrupt("gaud/gaud_bad_one");
        f.corrupt("gaud/gaud_bad_two");
        f.run();
        assertTrue(f.text().contains("Staff totals: 0 / 0 / 0 / 0 / 0"), f.text());
        assertTrue(f.text().contains("zero totals do not prove that no activity occurred"), f.text());
        f.assertPartial(2);
    }

    @Test void skippedCorruptFileDoesNotRelaxSecretRedactedOrPendingPrivacyRules() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff", "usr_bot", "usr_secret").secret("usr_secret");
        f.event("gaud_good", "group.instance.warn", "usr_staff", END.minusDays(1));
        f.event("gaud_secret", "group.user.ban", "usr_secret", END.minusDays(1));
        ScarletData.AuditEntryMetadata redacted = f.event("gaud_redacted", "group.user.ban", "usr_staff", END.minusDays(1));
        redacted.entryRedacted = true;
        f.save(redacted);
        ScarletData.AuditEntryMetadata pending = new ScarletData.AuditEntryMetadata();
        pending.entry = new GroupAuditLogEntryUnknown().id("gaud_pending").groupId(GROUP)
            .eventType("group.user.ban").actorId("usr_bot").actorDisplayName("Do not disclose")
            .targetId("usr_target").createdAt(END.minusDays(1));
        pending.setAuxData("deliveryState", "pending");
        new ScarletAuditOutbox(temp.toFile()).save(pending);
        f.corrupt("gaud/gaud_corrupt");
        f.run();
        assertTrue(f.text().contains("Staff totals: 1 / 0 / 0 / 0 / 0"), f.text());
        assertFalse(f.text().contains("usr_secret"));
        assertFalse(f.text().contains("usr_bot"));
        assertFalse(f.text().contains("Do not disclose"));
        assertTrue(f.text().contains("Some saved events are still awaiting processing and are not counted yet."), f.text());
        f.assertPartial(1);
    }

    @Test void conflictingReadablePrivacyReceiptsStillSuppressAllTotals() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        f.event("gaud_good", "group.instance.warn", "usr_staff", END.minusDays(1));
        ScarletData.AuditEntryMetadata record = f.event("gaud_conflict", "group.user.ban", "usr_staff", END.minusDays(1));
        record.setAuxData("deliveryState", "delivered");
        new ScarletAuditOutbox(temp.toFile()).save(record);
        record.entryRedacted = true;
        f.save(record);
        f.assertIncomplete();
    }

    @Test void paginatedPartialSummaryKeepsTheUnreadableNoticeAfterAllStaffCounts() throws Exception
    {
        Fixture f = new Fixture();
        for (int i = 0; i < 70; i++)
        {
            String actor = "usr_staff_" + i;
            f.staff(actor);
            ScarletData.AuditEntryMetadata record = f.event("gaud_staff_" + i, "group.user.ban", actor, END.minusDays(1));
            record.entry.setActorDisplayName("Synthetic long display name for pagination staff member " + i);
            f.save(record);
        }
        f.corrupt("gaud/gaud_corrupt");
        f.run();
        assertTrue(f.embeds.size() > 2, "Fixture must exercise multiple counts pages");
        assertTrue(f.text().contains("Staff totals: 0 / 0 / 70 / 0 / 0"), f.text());
        f.assertPartial(1);
    }

    @Test void unscannableHistoryDirectoryStillSuppressesAllTotals() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        f.event("gaud_good", "group.user.ban", "usr_staff", END.minusDays(1));
        Files.write(temp.resolve("audit-outbox"), "not a directory".getBytes(StandardCharsets.UTF_8));
        f.assertIncomplete();
    }

    @Test void pendingUnattributedOutboxRecordCannotExposeASecretStaffAction() throws Exception
    {
        Fixture f = new Fixture().staff("usr_bot");
        ScarletData.AuditEntryMetadata pending = new ScarletData.AuditEntryMetadata();
        pending.entry = new GroupAuditLogEntryUnknown().id("gaud_pending").groupId(GROUP)
            .eventType("group.user.ban").actorId("usr_bot").actorDisplayName("Bot")
            .targetId("usr_target").createdAt(END.minusDays(1));
        pending.setAuxData("deliveryState", "pending");
        new ScarletAuditOutbox(temp.toFile()).save(pending);
        f.run();
        assertTrue(f.text().contains("Staff totals: 0 / 0 / 0 / 0 / 0"), f.text());
        String text = f.text().toLowerCase();
        assertTrue(text.contains("pending") || text.contains("processing") || text.contains("unprocessed"), text);
    }

    @Test void nonpositiveHoursDoNotScanOrPublishTotals() throws Exception
    {
        for (long hours : new long[]{0L, -1L})
        {
            Fixture f = new Fixture();
            assertThrows(IllegalArgumentException.class, () -> f.run(hours));
            assertTrue(f.embeds.isEmpty(), "Invalid periods must not scan or publish totals");
        }
    }

    @Test void backgroundRequestReturnsWhileWorkIsBlockedAndRejectsAnotherSummary() throws Exception
    {
        Fixture f = new Fixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean ranOnCaller = new AtomicBoolean();
        Thread caller = Thread.currentThread();
        try
        {
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED,
                f.discord.requestModSummary(END, 28 * 24, f::send, status -> {
                    ranOnCaller.set(Thread.currentThread() == caller);
                    entered.countDown();
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(ranOnCaller.get(), "Summary work must not occupy the caller/normal polling thread");
            assertTrue(f.embeds.isEmpty());
            assertEquals(ScarletSummaryWorker.Submission.BUSY,
                f.discord.requestModSummary(END, 24, f::send, status -> fail("Busy requests must not be queued")));
            release.countDown();
            awaitIdle(f.discord);
            assertEquals("Moderation Summary (saved local history)", f.embeds.get(0).getTitle());
            assertTrue(f.text().contains("Staff totals: 0 / 0 / 0 / 0 / 0"));
        }
        finally { release.countDown(); f.discord.closeSummaryWorker(); }
    }

    @Test void groupChangeDuringBackgroundStatusCannotRetargetTheSummary() throws Exception
    {
        Fixture f = new Fixture();
        try
        {
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED,
                f.discord.requestModSummary(END, 24, f::send, status -> f.vrc.groupId = "grp_other"));
            awaitIdle(f.discord);
            assertTrue(f.embeds.isEmpty(), "Do not recapture a different group after sending the background status");
        }
        finally { f.discord.closeSummaryWorker(); }
    }

    @Test void shutdownInterruptsBackgroundWorkAndPreventsFurtherSendsOrRequests() throws Exception
    {
        Fixture f = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try
        {
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED,
                f.discord.requestModSummary(END, 24, f::send, status -> {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException stop) { interrupted.set(true); Thread.currentThread().interrupt(); }
                }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            f.discord.closeSummaryWorker();
            assertTrue(interrupted.get());
            assertTrue(f.embeds.isEmpty(), "A stopped worker must not send a summary or an error afterward");
            assertEquals(ScarletSummaryWorker.Submission.CLOSED,
                f.discord.requestModSummary(END, 24, f::send, status -> fail("No work after shutdown")));
        }
        finally { f.discord.closeSummaryWorker(); }
    }

    @Test void secretStaffChangeBeforeResultPagesCancelsRemainingPublication() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        f.event("gaud_visible", "group.user.ban", "usr_staff", END.minusDays(1));
        assertThrows(IllegalStateException.class, () ->
            f.discord.emitModSummary(f.scarlet, END, 24 * 28, embed -> {
                MessageCreateAction action = f.send(embed);
                f.secrets.secretStaffUserIds.add("usr_staff");
                return action;
            }));
        assertEquals(1, f.embeds.size());
        assertFalse(f.text().contains("Staff totals:"));
        assertFalse(f.text().contains("usr_staff"));
    }

    @Test void overlappingPrivacyEditsBlockNewScansUntilEveryEditHasFinished() throws Exception
    {
        Fixture f = new Fixture();
        f.discord.beginLocalSummaryPrivacyChange();
        f.discord.beginLocalSummaryPrivacyChange();
        assertThrows(IllegalStateException.class, f::run);
        f.discord.endLocalSummaryPrivacyChange();
        assertThrows(IllegalStateException.class, f::run);
        f.discord.endLocalSummaryPrivacyChange();
        assertTrue(f.embeds.isEmpty());
        f.run();
        assertEquals("Moderation Summary (saved local history)", f.embeds.get(0).getTitle());
    }

    @Test void completedPrivacyEditInvalidatesAlreadyAssembledResultPages() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        f.event("gaud_visible", "group.user.ban", "usr_staff", END.minusDays(1));
        assertThrows(IllegalStateException.class, () ->
            f.discord.emitModSummary(f.scarlet, END, 24 * 28, embed -> {
                MessageCreateAction action = f.send(embed);
                f.discord.beginLocalSummaryPrivacyChange();
                f.discord.endLocalSummaryPrivacyChange();
                return action;
            }));
        assertEquals(1, f.embeds.size());
        assertFalse(f.text().contains("Staff totals:"));
    }

    static class AllowedRedactionUI extends ScarletDiscordUI
    {
        AllowedRedactionUI() { super(null); }
        @Override boolean checkAuditEntryModerationAccess(Member member, InteractionHook hook, String auditId)
        { return true; }
    }

    @Test void actualRedactAndUnredactHandlersKeepSummaryReadsBlockedThroughTheSave() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        f.event("gaud_toggle", "group.user.ban", "usr_staff", END.minusDays(1));
        ScarletData checkingData = new ScarletData(temp.toFile()) {
            @Override public void auditEntryMetadata(String id, AuditEntryMetadata metadata)
            {
                assertThrows(IllegalStateException.class, f::run,
                    "A summary starting during a privacy save must not read the previous disk state");
                super.auditEntryMetadata(id, metadata);
            }
        };
        put(f.scarlet, "data", checkingData);
        AllowedRedactionUI ui = inert(AllowedRedactionUI.class);
        put(ui, "discord", f.discord);
        InteractionHook hook = ScarletDiscordReadinessTest.proxy(InteractionHook.class, (proxy, method, args) -> {
            if (method.getName().equals("sendMessage"))
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{method.getReturnType()},
                    (action, call, arguments) -> call.getReturnType().isInstance(action) ? action : null);
            return null;
        });
        ButtonInteraction interaction = ScarletDiscordReadinessTest.proxy(ButtonInteraction.class, (proxy, method, args) ->
            method.getName().equals("getButton") ? Button.primary("event-redact:gaud_toggle", "Toggle") : null);
        ButtonInteractionEvent event = new ButtonInteractionEvent(null, 0L, interaction);
        ui.eventRedact(event, hook);
        assertTrue(checkingData.auditEntryMetadata("gaud_toggle").entryRedacted);
        ui.eventUnredact(event, hook);
        assertFalse(checkingData.auditEntryMetadata("gaud_toggle").entryRedacted);
        assertTrue(f.embeds.isEmpty());
        f.run();
        assertTrue(f.text().contains("Staff totals: 0 / 0 / 1 / 0 / 0"), f.text());
    }

    @Test void failedRedactionPersistenceBlocksSavedHistoryUntilRetrySucceeds() throws Exception
    {
        Fixture f = new Fixture().staff("usr_staff");
        ScarletData.AuditEntryMetadata record = f.event("gaud_failed_save", "group.user.ban", "usr_staff", END.minusDays(1));
        byte[] before = Files.readAllBytes(temp.resolve("gaud/gaud_failed_save"));
        assertFalse(f.data.hasPendingAuditMetadataWrites());
        f.discord.beginLocalSummaryPrivacyChange();
        try
        {
            record.entryRedacted = true;
            record.setAuxData("syntheticWriteFailure", new com.google.gson.JsonPrimitive(new Number() {
                private static final long serialVersionUID = 1L;
                @Override public int intValue() { return 0; }
                @Override public long longValue() { return 0L; }
                @Override public float floatValue() { return 0F; }
                @Override public double doubleValue() { return 0D; }
                @Override public String toString() { throw new IllegalStateException("Synthetic write failure"); }
            }));
            assertThrows(java.io.UncheckedIOException.class, () -> f.save(record));
        }
        finally { f.discord.endLocalSummaryPrivacyChange(); }
        assertArrayEquals(before, Files.readAllBytes(temp.resolve("gaud/gaud_failed_save")),
            "Failed atomic serialization must retain the old disk record");
        assertTrue(f.data.hasPendingAuditMetadataWrites());
        assertTrue(f.data.auditEntryMetadata("gaud_failed_save").entryRedacted,
            "The pending in-memory privacy change must not be ignored by a disk-only summary");
        assertThrows(IllegalStateException.class, f::run);
        assertTrue(f.embeds.isEmpty());
        record.auxData.remove("syntheticWriteFailure");
        f.data.saveDirty();
        assertFalse(f.data.hasPendingAuditMetadataWrites());
        f.run();
        assertTrue(f.text().contains("Staff totals: 0 / 0 / 0 / 0 / 0"), f.text());
    }

    private static void awaitIdle(ScarletDiscordJDA discord) throws Exception
    {
        java.lang.reflect.Field workerField = ScarletDiscordJDA.class.getDeclaredField("summaryWorker");
        workerField.setAccessible(true);
        ScarletSummaryWorker worker = (ScarletSummaryWorker) workerField.get(discord);
        java.lang.reflect.Field busyField = ScarletSummaryWorker.class.getDeclaredField("busy");
        busyField.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline)
        {
            synchronized (worker)
            {
                if (!busyField.getBoolean(worker)) return;
            }
            Thread.sleep(5);
        }
        fail("Background summary did not finish within the isolated test deadline");
    }
}

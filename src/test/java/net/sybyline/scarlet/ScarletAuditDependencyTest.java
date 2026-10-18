package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline dependency transitions; no account, application constructor or network is used. */
class ScarletAuditDependencyTest
{
    @TempDir Path temp;
    static final String PARENT = "gaud_dependency_parent", CHILD = "gaud_dependency_child";

    class Fixture
    {
        final Scarlet scarlet = context();
        final ScarletAuditOutbox outbox = new ScarletAuditOutbox(temp.toFile());
        final ScarletData.AuditEntryMetadata parent = ScarletDiscordSafetyTest.metadata(PARENT, "actor");
        final ScarletData.AuditEntryMetadata child = ScarletDiscordSafetyTest.metadata(CHILD, "actor");
        final AtomicInteger attempts = new AtomicInteger(), sends = new AtomicInteger(), alerts = new AtomicInteger();
        final List<String> scheduled = new ArrayList<>();
        boolean actualFailure, rejectSchedule;
        final ScarletDiscord discord;

        Fixture() throws Exception
        {
            this.parent.entry.setEventType("group.user.ban");
            this.parent.setAuxData("deliveryState", "followup_pending");
            this.scarlet.data.auditEntryMetadata(PARENT, this.parent);
            this.outbox.save(this.parent);
            this.child.entry.setEventType("group.instance.kick");
            this.discord = (ScarletDiscord) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ScarletDiscord.class}, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "emitOpsAlert": this.alerts.incrementAndGet(); return null;
                    case "scheduleAuditFollowup":
                        if (!this.rejectSchedule) this.scheduled.add((String) args[1]);
                        return null;
                    case "processAuditEntry":
                        this.attempts.incrementAndGet();
                        if (this.actualFailure) throw new IllegalStateException("Synthetic Discord permission failure");
                        if (this.scarlet.data.auditEntryMetadata(PARENT).threadSnowflake == null)
                            throw new ScarletAuditDependencyPending(PARENT);
                        ScarletData.AuditEntryMetadata metadata = (ScarletData.AuditEntryMetadata) args[1];
                        metadata.messageSnowflake = "200";
                        metadata.setAuxData("deliveryAcknowledged", true);
                        this.sends.incrementAndGet();
                        return null;
                    }
                    if (!method.isDefault()) return null;
                    return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                        .unreflectSpecial(method, method.getDeclaringClass()).bindTo(proxy)
                        .invokeWithArguments(args == null ? new Object[0] : args);
                });
        }

        void ready()
        {
            this.parent.threadSnowflake = "100";
            this.scarlet.data.auditEntryMetadata(PARENT, this.parent);
        }

        void process() { this.discord.process(this.scarlet, this.child.entry); }
    }

    Scarlet context() throws Exception
    {
        Scarlet scarlet = inert(Scarlet.class);
        put(scarlet, "data", new ScarletData(this.temp.toFile()));
        return scarlet;
    }

    @Test void normalWaitIsDurableWithoutAlertOrFailureAttempt() throws Exception
    {
        Fixture f = new Fixture();
        f.process();
        var waiting = f.outbox.read(CHILD);
        assertTrue(ScarletAuditOutbox.isDependency(waiting));
        assertEquals(PARENT, ScarletAuditOutbox.dependency(waiting));
        assertEquals(0, waiting.getAuxData("deliveryAttempts", 0, e -> e.getAsInt()));
        assertFalse(ScarletAuditOutbox.due(waiting, System.currentTimeMillis()));
        assertEquals(0, f.alerts.get());
        f.process();
        assertEquals(1, f.attempts.get(), "Polling must not busy-loop while the parent is pending");
        assertEquals(0, f.sends.get());
    }

    @Test void readyNotificationMakesKickEligibleWithoutWaitingForRetryTimerOrDuplicating() throws Exception
    {
        Fixture f = new Fixture();
        f.process();
        f.ready();
        f.discord.wakeAuditDependents(f.scarlet, PARENT);
        assertEquals(java.util.Collections.singletonList(CHILD), f.scheduled);
        assertTrue(ScarletAuditOutbox.due(f.outbox.read(CHILD), System.currentTimeMillis()));
        assertEquals(0, f.sends.get(), "Wakeup must not deliver inline under a parent operation's locks");
        f.process();
        f.process();
        f.discord.retryPending(f.scarlet);
        assertEquals(1, f.sends.get());
        assertFalse(f.outbox.exists(CHILD));
        assertEquals(0, f.alerts.get());
    }

    @Test void parentReadyBeforeWakeAndRestartBothRecoverFromDiskWithoutTimerDelay() throws Exception
    {
        Fixture f = new Fixture();
        f.process();
        f.ready();
        Scarlet restarted = context();
        assertTrue(new ScarletAuditOutbox(temp.toFile()).dependencyMayProceed(restarted, f.outbox.read(CHILD)));
        f.discord.retryPending(restarted);
        assertEquals(1, f.sends.get());
        assertFalse(f.outbox.exists(CHILD));
    }

    @Test void rejectedMemoryWakeupLeavesDurableReceiptForPollRecovery() throws Exception
    {
        Fixture f = new Fixture();
        f.process();
        f.ready();
        f.rejectSchedule = true;
        f.discord.wakeAuditDependents(f.scarlet, PARENT);
        assertTrue(f.scheduled.isEmpty());
        assertTrue(f.outbox.exists(CHILD));
        f.discord.retryPending(f.scarlet);
        assertEquals(1, f.sends.get());
    }

    @Test void actualFailureAfterReadyStillAlertsAndUsesFailureBackoff() throws Exception
    {
        Fixture f = new Fixture();
        f.process();
        f.ready();
        f.actualFailure = true;
        f.discord.wakeAuditDependents(f.scarlet, PARENT);
        f.process();
        var failed = f.outbox.read(CHILD);
        assertFalse(ScarletAuditOutbox.isDependency(failed));
        assertEquals(1, failed.getAuxData("deliveryAttempts", 0, e -> e.getAsInt()));
        assertEquals(1, f.alerts.get());
        assertFalse(f.outbox.dependencyMayProceed(f.scarlet, failed));
        f.discord.wakeAuditDependents(f.scarlet, PARENT);
        f.process();
        assertEquals(2, f.attempts.get());
        assertEquals(1, f.alerts.get(), "A retained parent id must not bypass actual failure backoff");
    }

    @Test void waitDoesNotConsumePreviouslyRecordedRealFailureAttempts() throws Exception
    {
        Fixture f = new Fixture();
        f.child.setAuxData("deliveryAttempts", 3);
        f.outbox.save(f.child);
        f.process();
        assertEquals(3, f.outbox.read(CHILD).getAuxData("deliveryAttempts", 0, e -> e.getAsInt()));
        assertEquals(0, f.alerts.get());
    }

    @Test void actualParentCompletionWakesOnlyMatchingDependentsAfterDurableCommit() throws Exception
    {
        ScarletAuditFollowupPrivacyTest existing = new ScarletAuditFollowupPrivacyTest();
        existing.temp = temp;
        ScarletAuditFollowupPrivacyTest.Fixture f = existing.new Fixture();
        var child = ScarletDiscordSafetyTest.metadata(CHILD, "ordinary");
        ScarletAuditOutbox.waitForDependency(child, ScarletAuditFollowupPrivacyTest.AUDIT, System.currentTimeMillis());
        f.outbox.save(child);
        var unrelated = ScarletDiscordSafetyTest.metadata("gaud_unrelated_dependency", "ordinary");
        ScarletAuditOutbox.waitForDependency(unrelated, "gaud_another_parent", System.currentTimeMillis());
        f.outbox.save(unrelated);
        CountDownLatch woken = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        ScarletAuditFollowupWorker worker = new ScarletAuditFollowupWorker(id -> {
            assertEquals(CHILD, id);
            assertNotNull(f.saved().threadSnowflake);
            assertEquals(0L, f.outbox.read(CHILD).getAuxData("deliveryRetryAt", -1L, e -> e.getAsLong()));
            dispatches.incrementAndGet();
            woken.countDown();
        });
        put(f.f.discord, "auditFollowupWorker", worker);
        try
        {
            f.run();
            assertTrue(woken.await(3, TimeUnit.SECONDS));
            assertTrue(dispatches.get() >= 1);
            assertFalse(ScarletAuditOutbox.due(f.outbox.read("gaud_unrelated_dependency"), System.currentTimeMillis()));
        }
        finally { worker.close(); }
    }

    class ParentFixture
    {
        final ScarletAuditPrivacyEntryPointsTest existing = new ScarletAuditPrivacyEntryPointsTest();
        final ScarletAuditPrivacyEntryPointsTest.Fixture f;
        final ScarletData.AuditEntryMetadata kick = ScarletDiscordSafetyTest.metadata(CHILD, "ordinary");
        ParentFixture() throws Exception
        {
            this.existing.temp = temp;
            this.f = this.existing.new Fixture();
            ScarletVRChat vrc = inert(ScarletVRChat.class);
            put(vrc, "groupId", ScarletAuditFollowupPrivacyTest.GROUP);
            put(this.f.scarlet, "vrc", vrc);
            this.f.metadata.entry.setGroupId(ScarletAuditFollowupPrivacyTest.GROUP);
            this.f.metadata.entry.setEventType("group.user.ban");
            this.f.metadata.entry.setCreatedAt(OffsetDateTime.parse("2026-10-14T06:00:00Z"));
            this.f.routes.put("group.user.ban", "20");
            this.f.secretRoutes.put("group.user.ban", "30");
            this.kick.entry.setGroupId(ScarletAuditFollowupPrivacyTest.GROUP);
            this.kick.entry.setEventType("group.instance.kick");
            this.kick.entry.setCreatedAt(OffsetDateTime.parse("2026-10-14T06:00:01Z"));
            this.f.scarlet.data.auditEntryMetadata(this.f.metadata.entry.getId(), this.f.metadata);
            ScarletAuditOutbox.waitForDependency(this.kick, this.f.metadata.entry.getId(), System.currentTimeMillis());
        }
    }

    @Test void rememberedParentWinsOverNewerHistoryAndUsesFreshMetadata() throws Exception
    {
        ParentFixture p = new ParentFixture();
        var unrelated = ScarletDiscordSafetyTest.metadata("gaud_newer_ban", "ordinary");
        var current = p.f.discord.currentBundledBan(p.kick, unrelated);
        assertEquals(p.f.metadata.entry.getId(), current.entry.getId());
        p.f.scarlet.data.auditEntryMetadata(current.entry.getId(), value -> { value.threadSnowflake = "100"; return value; });
        assertEquals("100", p.f.discord.currentBundledBan(p.kick, p.f.metadata).threadSnowflake);
    }

    @Test void initialSuppressedBanDoesNotBlockSeparatelyConfiguredKick() throws Exception
    {
        ParentFixture p = new ParentFixture();
        var freshKick = Scarlet.GSON.fromJson(Scarlet.GSON.toJson(p.kick), ScarletData.AuditEntryMetadata.class);
        freshKick.setAuxData().remove("deliveryDependencyEvent");
        p.f.metadata.messageSnowflake = null;
        p.f.routes.remove("group.user.ban");
        p.f.scarlet.data.auditEntryMetadata(p.f.metadata.entry.getId(), p.f.metadata);
        assertNull(p.f.discord.preparedBundledBan(freshKick, p.f.metadata));
        assertThrows(IllegalStateException.class, () -> p.f.discord.preparedBundledBan(p.kick, p.f.metadata));
    }

    @Test void initialLegacyBanWithoutThreadUsesStandaloneButRecordedDependencyDoesNot() throws Exception
    {
        ParentFixture p = new ParentFixture();
        var freshKick = Scarlet.GSON.fromJson(Scarlet.GSON.toJson(p.kick), ScarletData.AuditEntryMetadata.class);
        freshKick.setAuxData().remove("deliveryDependencyEvent");
        assertNull(p.f.discord.preparedBundledBan(freshKick, p.f.metadata));
        assertThrows(IllegalStateException.class, () -> p.f.discord.preparedBundledBan(p.kick, p.f.metadata));
    }

    @Test void realPendingParentProducesExpectedTypedWaitAndReadyParentIsReused() throws Exception
    {
        ParentFixture p = new ParentFixture();
        p.f.metadata.setAuxData("deliveryState", "followup_pending");
        new ScarletAuditOutbox(temp.toFile()).save(p.f.metadata);
        ScarletAuditDependencyPending waiting = assertThrows(ScarletAuditDependencyPending.class,
            () -> p.f.discord.preparedBundledBan(p.kick, p.f.metadata));
        assertEquals(p.f.metadata.entry.getId(), waiting.parentEventId);
        p.f.scarlet.data.auditEntryMetadata(p.f.metadata.entry.getId(), value -> { value.threadSnowflake = "100"; return value; });
        assertEquals("100", p.f.discord.preparedBundledBan(p.kick, p.f.metadata).threadSnowflake);
    }

    @Test void redactedOrMissingParentCannotFallBackToPublicDelivery() throws Exception
    {
        ParentFixture p = new ParentFixture();
        p.f.scarlet.data.auditEntryMetadata(p.f.metadata.entry.getId(), value -> { value.entryRedacted = true; return value; });
        assertThrows(IllegalStateException.class, () -> p.f.discord.currentBundledBan(p.kick, p.f.metadata));
        p.kick.setAuxData("deliveryDependencyEvent", "gaud_missing_parent");
        assertThrows(IllegalStateException.class, () -> p.f.discord.currentBundledBan(p.kick, p.f.metadata));
        assertEquals(0, p.f.sends.get());
    }

    @Test void changedSecretStaffAudienceOrRouteRejectsOldThread() throws Exception
    {
        ParentFixture p = new ParentFixture();
        p.f.secret.add("ordinary");
        assertThrows(IllegalStateException.class, () -> p.f.discord.currentBundledBan(p.kick, p.f.metadata));
        p.f.secret.clear();
        p.f.routes.put("group.user.ban", "40");
        assertThrows(IllegalStateException.class, () -> p.f.discord.currentBundledBan(p.kick, p.f.metadata));
        assertEquals(0, p.f.sends.get());
    }

    @Test void changedParentGroupTargetOrActorIsRejected() throws Exception
    {
        ParentFixture p = new ParentFixture();
        p.f.metadata.entry.setGroupId(ScarletAuditFollowupPrivacyTest.OTHER_GROUP);
        p.f.scarlet.data.auditEntryMetadata(p.f.metadata.entry.getId(), p.f.metadata);
        assertThrows(IllegalStateException.class, () -> p.f.discord.currentBundledBan(p.kick, p.f.metadata));
        p.f.metadata.entry.setGroupId(ScarletAuditFollowupPrivacyTest.GROUP);
        p.f.metadata.entry.setTargetId("changed-target");
        p.f.scarlet.data.auditEntryMetadata(p.f.metadata.entry.getId(), p.f.metadata);
        assertThrows(IllegalStateException.class, () -> p.f.discord.currentBundledBan(p.kick, p.f.metadata));
    }

    @Test void legacyFailedBundledSendRetainsItsOriginalParentWithoutNewMarker() throws Exception
    {
        ParentFixture p = new ParentFixture();
        p.kick.setAuxData().remove("deliveryDependencyEvent");
        p.kick.parentEventId = p.f.metadata.entry.getId();
        assertEquals(p.f.metadata.entry.getId(), ScarletAuditOutbox.dependency(p.kick));
        var unrelated = ScarletDiscordSafetyTest.metadata("gaud_later_ban", "ordinary");
        assertEquals(p.f.metadata.entry.getId(), p.f.discord.currentBundledBan(p.kick, unrelated).entry.getId());
    }

    @Test void trainingKickCannotBorrowARealAuditThread() throws Exception
    {
        ParentFixture p = new ParentFixture();
        p.kick.entry.setId(ScarletSimulation.TRAINING_AUDIT_ID_PREFIX + "dependency");
        assertThrows(IllegalStateException.class, () -> p.f.discord.currentBundledBan(p.kick, p.f.metadata));
        assertEquals(0, p.f.sends.get());
    }
}

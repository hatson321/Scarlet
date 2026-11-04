package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletAuditPrivacyEntryPointsTest.primitive;
import static net.sybyline.scarlet.ScarletAuditPrivacyEntryPointsTest.proxy;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

/** Actual JDA follow-up code with synthetic Discord entities and a temporary data home only. */
class ScarletAuditFollowupPrivacyTest
{
    @TempDir Path temp;
    static final String AUDIT = ScarletAuditPrivacyEntryPointsTest.AUDIT;
    static final String GROUP = "grp_00000000-0000-0000-0000-000000000001";
    static final String OTHER_GROUP = "grp_00000000-0000-0000-0000-000000000002";

    class Fixture
    {
        final ScarletAuditPrivacyEntryPointsTest base = new ScarletAuditPrivacyEntryPointsTest();
        final ScarletAuditPrivacyEntryPointsTest.Fixture f;
        final ScarletAuditOutbox outbox;
        final AtomicInteger parentLookups = new AtomicInteger();
        final AtomicInteger auxLookups = new AtomicInteger();
        final AtomicInteger parentSends = new AtomicInteger();
        Runnable onParentLookup = () -> {};
        Runnable onAuxLookup = () -> {};

        Fixture() throws Exception
        {
            this.base.temp = temp;
            this.f = this.base.new Fixture();
            this.outbox = new ScarletAuditOutbox(this.f.scarlet.data.dir);
            ScarletVRChat vrc = inert(ScarletVRChat.class);
            put(vrc, "groupId", GROUP);
            put(this.f.scarlet, "vrc", vrc);
            this.f.metadata.entry.setGroupId(GROUP);
            this.f.metadata.entry.setEventType("group.user.ban");
            this.f.metadata.setAuxData("deliveryState", "followup_pending");
            this.f.metadata.setAuxData("deliveryAcknowledged", true);
            this.f.metadata.setAuxData("deliveryBanInfoCaptured", true);
            this.f.routes.put("group.user.ban", "20");
            this.f.secretRoutes.put("group.user.ban", "30");
            TextChannel channel = proxy(TextChannel.class, (p, m, a) -> {
                if (m.getName().equals("getId")) return this.f.channelId;
                if (m.getName().equals("getGuild")) return this.f.guild;
                if (m.getName().equals("sendMessageEmbeds")) {
                    this.parentSends.incrementAndGet();
                    fail("Follow-up must never resend the primary embed");
                }
                if (m.getName().equals("retrieveMessageById"))
                    return proxy(m.getReturnType(), (action, method, values) -> {
                        if (method.getName().equals("complete")) {
                            this.parentLookups.incrementAndGet();
                            this.onParentLookup.run();
                            return this.f.message;
                        }
                        return primitive(method);
                    });
                return primitive(m);
            });
            Guild guild = proxy(Guild.class, (p, m, a) -> {
                if (m.getName().equals("getId")) return this.f.guildId;
                if (m.getName().equals("getTextChannelById")) return channel;
                if (m.getName().equals("getThreadChannelById")) return this.f.thread;
                if (m.getName().equals("getMemberById")) return this.f.member;
                return primitive(m);
            });
            put(this.f, "guild", guild);
            JDA initialJda = this.f.jda;
            JDA jda = proxy(JDA.class, (p, m, a) -> {
                if (m.getName().equals("getSelfUser")) return initialJda.getSelfUser();
                if (m.getName().equals("getGuildById")) return guild;
                return primitive(m);
            });
            put(this.f.discord, "jda", jda);
            this.f.onRetrieve = () -> { this.auxLookups.incrementAndGet(); this.onAuxLookup.run(); };
            this.f.scarlet.data.auditEntryMetadata(AUDIT, this.f.metadata);
            this.outbox.save(this.f.metadata);
        }

        void run() { this.f.discord.runAuditFollowup(AUDIT); }

        void edit(Consumer<ScarletData.AuditEntryMetadata> edit)
        {
            this.f.scarlet.data.auditEntryMetadata(AUDIT, current -> { edit.accept(current); return current; });
        }

        ScarletData.AuditEntryMetadata saved() { return this.f.scarlet.data.auditEntryMetadata(AUDIT); }

        void assertPendingWithoutSends()
        {
            assertTrue(this.outbox.exists(AUDIT));
            assertTrue(ScarletAuditOutbox.isFollowup(this.outbox.read(AUDIT)));
            assertFalse(this.saved().getAuxData("deliveryModerationThread", false, e -> e.getAsBoolean()));
            assertEquals(0, this.parentSends.get());
            assertEquals(0, this.f.sends.get());
            assertTrue(this.f.mappings.isEmpty());
        }
    }

    @Test void acknowledgedThreadAndAuxiliaryMessageAreRecoveredOnceWithoutResendingParent() throws Exception
    {
        Fixture f = new Fixture();
        f.run();
        assertFalse(f.outbox.exists(AUDIT));
        assertEquals("100", f.saved().threadSnowflake);
        assertEquals("101", f.saved().auxMessageSnowflake);
        assertTrue(f.saved().getAuxData("deliveryModerationThread", false, e -> e.getAsBoolean()));
        assertTrue(ScarletAuditOutbox.complete(f.saved()));
        assertEquals(AUDIT, f.f.mappings.get("100"));
        assertEquals(1, f.parentLookups.get());
        assertEquals(1, f.auxLookups.get());
        f.run();
        assertEquals(1, f.parentLookups.get());
        assertEquals(1, f.auxLookups.get());
        assertEquals(0, f.parentSends.get());
        assertEquals(0, f.f.sends.get());
    }

    @Test void freshRedactionBeforeExecutionBlocksAllDiscordLookups() throws Exception
    {
        Fixture f = new Fixture();
        f.edit(current -> current.entryRedacted = true);
        f.run();
        f.assertPendingWithoutSends();
        assertTrue(f.saved().entryRedacted);
        assertEquals(0, f.parentLookups.get());
    }

    @Test void redactionDuringParentLookupIsNotRevertedOrSentToThread() throws Exception
    {
        Fixture f = new Fixture();
        f.onParentLookup = () -> f.edit(current -> current.entryRedacted = true);
        f.run();
        f.assertPendingWithoutSends();
        assertTrue(f.saved().entryRedacted);
        assertEquals(1, f.parentLookups.get());
        assertEquals(0, f.auxLookups.get());
    }

    @Test void redactionDuringAuxiliaryLookupIsNotRevertedByAcknowledgement() throws Exception
    {
        Fixture f = new Fixture();
        f.onAuxLookup = () -> f.edit(current -> current.entryRedacted = true);
        f.run();
        f.assertPendingWithoutSends();
        assertTrue(f.saved().entryRedacted);
        assertNull(f.saved().threadSnowflake);
        assertEquals(1, f.auxLookups.get());
    }

    @Test void routingChangeWhileParentLookupRunsBlocksThreadDelivery() throws Exception
    {
        Fixture f = new Fixture();
        f.onParentLookup = () -> f.f.routes.put("group.user.ban", "21");
        f.run();
        f.assertPendingWithoutSends();
        assertEquals(0, f.auxLookups.get());
    }

    @Test void confidentialActorChangeWhileParentLookupRunsBlocksOldPublicDestination() throws Exception
    {
        Fixture f = new Fixture();
        f.onParentLookup = () -> f.f.secret.add("ordinary");
        f.run();
        f.assertPendingWithoutSends();
        assertEquals(0, f.auxLookups.get());
    }

    @Test void configuredGuildChangeBlocksPreviouslyRecordedDestination() throws Exception
    {
        Fixture f = new Fixture();
        put(f.f.discord, "guildSf", "2");
        f.run();
        f.assertPendingWithoutSends();
        assertEquals(0, f.auxLookups.get());
    }

    @Test void changedPersistedEventGroupCannotReuseQueuedReceipt() throws Exception
    {
        Fixture f = new Fixture();
        f.edit(current -> current.entry.setGroupId(OTHER_GROUP));
        f.run();
        f.assertPendingWithoutSends();
        assertEquals(OTHER_GROUP, f.saved().entry.getGroupId());
        assertEquals(0, f.parentLookups.get());
    }

    @Test void changedConfiguredGroupCannotResumePreviousGroupsFollowup() throws Exception
    {
        Fixture f = new Fixture();
        put(f.f.scarlet.vrc, "groupId", OTHER_GROUP);
        f.run();
        f.assertPendingWithoutSends();
        assertEquals(0, f.parentLookups.get());
    }

    @Test void concurrentTagsDescriptionAndOtherMetadataSurviveThreadReceiptMerge() throws Exception
    {
        Fixture f = new Fixture();
        f.onAuxLookup = () -> f.edit(current -> {
            current.entryTags.clear().addAll("reviewed", "synthetic-tag");
            current.entryDescription = "Moderator revised this while the thread was being prepared";
            current.setAuxData("syntheticIndependentEdit", "retained");
        });
        f.run();
        ScarletData.AuditEntryMetadata saved = f.saved();
        assertIterableEquals(Arrays.asList("reviewed", "synthetic-tag"), saved.entryTags.strings());
        assertEquals("Moderator revised this while the thread was being prepared", saved.entryDescription);
        assertEquals("retained", saved.getAuxData("syntheticIndependentEdit", "", e -> e.getAsString()));
        assertEquals("100", saved.threadSnowflake);
        assertEquals("101", saved.auxMessageSnowflake);
        assertTrue(ScarletAuditOutbox.complete(saved));
        assertFalse(f.outbox.exists(AUDIT));
        assertEquals(0, f.parentSends.get());
    }

    @Test void receiptAloneRecoversInterruptedForegroundHandoffWithoutResendingParent() throws Exception
    {
        Fixture f = new Fixture();
        Files.delete(f.f.scarlet.data.dir.toPath().resolve("gaud").resolve(AUDIT));
        assertFalse(f.f.scarlet.data.auditEntryMetadataExists(AUDIT));
        f.run();
        assertTrue(ScarletAuditOutbox.complete(f.saved()));
        assertEquals("100", f.saved().messageSnowflake);
        assertEquals("100", f.saved().threadSnowflake);
        assertEquals("101", f.saved().auxMessageSnowflake);
        assertFalse(f.outbox.exists(AUDIT));
        assertEquals(0, f.parentSends.get());
    }

    @Test void transientRestFailureRetainsRetryReceiptAndRecoversUsingOriginalMessage() throws Exception
    {
        Fixture f = new Fixture();
        f.onParentLookup = () -> { throw new IllegalStateException("Synthetic transient REST failure"); };
        f.run();
        f.assertPendingWithoutSends();
        ScarletData.AuditEntryMetadata retry = f.outbox.read(AUDIT);
        assertEquals("100", retry.messageSnowflake);
        assertEquals(1, retry.getAuxData("deliveryAttempts", 0, e -> e.getAsInt()));
        assertTrue(retry.getAuxData("deliveryRetryAt", 0L, e -> e.getAsLong()) > System.currentTimeMillis());
        f.run();
        assertEquals(1, f.parentLookups.get(), "Not-yet-due retry must not perform REST work");
        retry.setAuxData("deliveryRetryAt", 0L);
        f.outbox.save(retry);
        f.onParentLookup = () -> {};
        f.run();
        assertTrue(ScarletAuditOutbox.complete(f.saved()));
        assertFalse(f.outbox.exists(AUDIT));
        assertEquals(2, f.parentLookups.get());
        assertEquals(1, f.auxLookups.get());
        assertEquals(0, f.parentSends.get());
    }

    @Test void newerAuxiliaryAcknowledgementIsNotOverwrittenByOlderWorkerCompletion() throws Exception
    {
        Fixture f = new Fixture();
        f.onParentLookup = () -> f.edit(current -> current.auxMessageSnowflake = "102");
        f.run();
        assertTrue(ScarletAuditOutbox.complete(f.saved()));
        assertEquals("102", f.saved().auxMessageSnowflake);
        assertFalse(f.outbox.exists(AUDIT));
        assertEquals(0, f.parentSends.get());
    }

    @Test void automaticFollowupWaitsForSameEventRepairAndKeepsItsAcknowledgement() throws Exception
    {
        Fixture f = new Fixture();
        CountDownLatch repairEntered = new CountDownLatch(1), releaseRepair = new CountDownLatch(1);
        CountDownLatch automaticRetrievedParent = new CountDownLatch(1);
        f.onAuxLookup = () -> {
            if (f.auxLookups.get() == 1) {
                repairEntered.countDown();
                try { assertTrue(releaseRepair.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
                // Repair is verifying the already-acknowledged auxiliary message (101).
                // Retrieving it cannot change its Discord identifier.
            }
        };
        f.onParentLookup = automaticRetrievedParent::countDown;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> repair = workers.submit(() -> f.f.discord.emitUserModeration_thread(
                f.f.scarlet, f.f.metadata, "ordinary", f.f.message, "50"));
            assertTrue(repairEntered.await(5, TimeUnit.SECONDS));
            Future<?> automatic = workers.submit(f::run);
            assertTrue(automaticRetrievedParent.await(5, TimeUnit.SECONDS));
            assertEquals(1, f.auxLookups.get(), "Automatic work must not enter the same event's REST section yet");
            releaseRepair.countDown();
            repair.get(5, TimeUnit.SECONDS);
            automatic.get(5, TimeUnit.SECONDS);
            assertEquals("101", f.saved().auxMessageSnowflake);
            assertTrue(ScarletAuditOutbox.complete(f.saved()));
            assertFalse(f.outbox.exists(AUDIT));
            assertEquals(0, f.parentSends.get());
            assertEquals(0, f.f.sends.get());
        }
        finally
        {
            releaseRepair.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void terminalReceiptRecoveryPreservesLaterRedactionEditsAndAcknowledgements() throws Exception
    {
        Fixture f = new Fixture();
        ScarletData.AuditEntryMetadata terminal = f.saved();
        terminal.setAuxData("deliveryState", "delivered");
        terminal.setAuxData("deliveryModerationThread", true);
        terminal.threadSnowflake = "100";
        f.outbox.save(terminal);
        f.edit(current -> {
            current.entryRedacted = true;
            current.entryTags.add("reviewed-after-delivery");
            current.entryDescription = "Updated after acknowledgement";
            current.setAuxData("independentUpdate", "keep");
        });
        f.outbox.finish(f.f.scarlet, terminal);
        ScarletData.AuditEntryMetadata saved = f.saved();
        assertTrue(saved.entryRedacted);
        assertTrue(saved.entryTags.contains("reviewed-after-delivery"));
        assertEquals("Updated after acknowledgement", saved.entryDescription);
        assertEquals("keep", saved.getAuxData("independentUpdate", "", e -> e.getAsString()));
        assertEquals("100", saved.threadSnowflake);
        assertEquals("101", saved.auxMessageSnowflake);
        assertTrue(ScarletAuditOutbox.complete(saved));
        assertTrue(saved.getAuxData("deliveryModerationThread", false, e -> e.getAsBoolean()));
        assertFalse(f.outbox.exists(AUDIT));
        assertEquals(0, f.parentLookups.get());
        assertEquals(0, f.parentSends.get());
    }

    @Test void terminalReceiptCannotReplaceChangedAuditIdentity() throws Exception
    {
        Fixture f = new Fixture();
        ScarletData.AuditEntryMetadata terminal = f.saved();
        terminal.setAuxData("deliveryState", "delivered");
        terminal.setAuxData("deliveryModerationThread", true);
        terminal.threadSnowflake = "100";
        f.outbox.save(terminal);
        f.edit(current -> current.entry.setTargetId("usr_other-synthetic-target"));
        String currentBefore = Scarlet.GSON.toJson(f.saved());
        assertThrows(RuntimeException.class, () -> f.outbox.finish(f.f.scarlet, terminal));
        assertEquals(currentBefore, Scarlet.GSON.toJson(f.saved()));
        assertTrue(f.outbox.exists(AUDIT));
        assertEquals("usr_training-target", f.outbox.read(AUDIT).entry.getTargetId());
        assertEquals(0, f.parentLookups.get());
        assertEquals(0, f.parentSends.get());
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline delivery-phase tests; no application constructors or network clients run. */
class ScarletAuditFollowupTest
{
    @TempDir Path temp;

    Scarlet context() throws Exception
    {
        Scarlet scarlet = inert(Scarlet.class);
        put(scarlet, "data", new ScarletData(this.temp.toFile()));
        return scarlet;
    }

    ScarletDiscord delivery(AtomicInteger primary, Consumer<String> schedule)
    {
        return (ScarletDiscord) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{ScarletDiscord.class}, (proxy, method, arguments) -> {
                switch (method.getName())
                {
                case "requiresAuditFollowup":
                    ScarletData.AuditEntryMetadata meta = (ScarletData.AuditEntryMetadata) arguments[0];
                    return meta.hasMessage() && !meta.getAuxData("deliveryModerationThread", false, e -> e.getAsBoolean());
                case "scheduleAuditFollowup":
                    schedule.accept((String) arguments[1]);
                    return null;
                case "processAuditEntry":
                    primary.incrementAndGet();
                    meta = (ScarletData.AuditEntryMetadata) arguments[1];
                    meta.guildSnowflake = "1";
                    meta.channelSnowflake = "2";
                    meta.messageSnowflake = "3";
                    meta.setAuxData("deliveryAcknowledged", true);
                    return null;
                }
                if (!method.isDefault()) return null;
                return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                    .unreflectSpecial(method, method.getDeclaringClass()).bindTo(proxy)
                    .invokeWithArguments(arguments == null ? new Object[0] : arguments);
            });
    }

    @Test void primaryReceiptAndMetadataAreDurableBeforeBackgroundScheduling() throws Exception
    {
        Scarlet scarlet = context();
        AtomicInteger primary = new AtomicInteger(), scheduled = new AtomicInteger();
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(this.temp.toFile());
        ScarletDiscord discord = delivery(primary, id -> {
            ScarletData.AuditEntryMetadata pending = outbox.read(id);
            assertTrue(pending.hasMessage());
            assertTrue(ScarletAuditOutbox.isFollowup(pending));
            assertFalse(ScarletAuditOutbox.complete(pending));
            assertEquals("3", scarlet.data.auditEntryMetadata(id).messageSnowflake);
            scheduled.incrementAndGet();
        });
        var entry = ScarletDiscordSafetyTest.metadata("gaud_background", "actor").entry;
        discord.process(scarlet, entry);
        assertEquals(1, primary.get());
        assertEquals(1, scheduled.get());
        assertFalse(discord.isAuditProcessed(scarlet, entry.getId()), "Unfinished thread must retain a durable receipt");
        discord.process(scarlet, entry);
        discord.retryPending(scarlet);
        assertEquals(1, primary.get(), "Replay must not refetch snapshots or repost the primary message");
        assertTrue(scheduled.get() >= 2);
    }

    @Test void onlyAcknowledgedStandaloneModerationsRequireAThread() throws Exception
    {
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        var metadata = ScarletDiscordSafetyTest.metadata("gaud_followup_types", "actor");
        metadata.guildSnowflake = "1";
        metadata.channelSnowflake = "2";
        metadata.messageSnowflake = "3";
        for (String event : new String[]{"group.instance.warn", "group.instance.kick", "group.member.remove",
            "group.user.ban", "group.user.unban"})
        {
            metadata.entry.setEventType(event);
            assertTrue(discord.requiresAuditFollowup(metadata), event);
        }
        metadata.entry.setEventType("group.instance.close");
        assertFalse(discord.requiresAuditFollowup(metadata));
        metadata.entry.setEventType("group.user.ban");
        metadata.parentEventId = "gaud_parent";
        assertFalse(discord.requiresAuditFollowup(metadata));
        metadata.parentEventId = null;
        metadata.setAuxData("deliveryModerationThread", true);
        assertFalse(discord.requiresAuditFollowup(metadata));
        metadata.setAuxData("deliveryModerationThread", false);
        metadata.messageSnowflake = null;
        assertFalse(discord.requiresAuditFollowup(metadata));
    }

    @Test void restartRequeuesOnlyUnfinishedFollowup() throws Exception
    {
        Scarlet scarlet = context();
        AtomicInteger primary = new AtomicInteger(), scheduled = new AtomicInteger();
        var entry = ScarletDiscordSafetyTest.metadata("gaud_restart_followup", "actor").entry;
        delivery(primary, id -> {}).process(scarlet, entry);
        Scarlet restarted = context();
        ScarletDiscord discord = delivery(primary, id -> scheduled.incrementAndGet());
        discord.retryPending(restarted);
        assertEquals(1, primary.get());
        assertEquals(1, scheduled.get());
    }

    @Test void rejectedScheduleRetainsTheAcknowledgedPrimaryAndCanRetry() throws Exception
    {
        Scarlet scarlet = context();
        AtomicInteger primary = new AtomicInteger(), scheduled = new AtomicInteger();
        AtomicBoolean reject = new AtomicBoolean(true);
        ScarletDiscord discord = delivery(primary, id -> {
            if (reject.get()) throw new java.util.concurrent.RejectedExecutionException("Synthetic shutdown");
            scheduled.incrementAndGet();
        });
        var entry = ScarletDiscordSafetyTest.metadata("gaud_rejected_followup", "actor").entry;
        discord.process(scarlet, entry);
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(this.temp.toFile());
        ScarletData.AuditEntryMetadata pending = outbox.read(entry.getId());
        assertTrue(pending.hasMessage());
        assertTrue(ScarletAuditOutbox.isFollowup(pending));
        assertFalse(ScarletAuditOutbox.complete(pending));
        reject.set(false);
        pending.setAuxData("deliveryRetryAt", 0L);
        outbox.save(pending);
        discord.retryPending(scarlet);
        assertEquals(1, primary.get());
        assertEquals(1, scheduled.get());
    }

    @Test void completedFollowupReceiptCommitsWithoutRepostingAnything() throws Exception
    {
        Scarlet scarlet = context();
        AtomicInteger primary = new AtomicInteger();
        var entry = ScarletDiscordSafetyTest.metadata("gaud_followup_terminal", "actor").entry;
        delivery(primary, id -> {}).process(scarlet, entry);
        ScarletAuditOutbox outbox = new ScarletAuditOutbox(this.temp.toFile());
        ScarletData.AuditEntryMetadata pending = outbox.read(entry.getId());
        pending.setAuxData("deliveryModerationThread", true);
        pending.setAuxData("deliveryState", "delivered");
        outbox.save(pending);
        ScarletDiscord discord = delivery(primary, id -> fail("Terminal receipt must not schedule work"));
        discord.retryPending(scarlet);
        assertEquals(1, primary.get());
        assertTrue(discord.isAuditProcessed(scarlet, entry.getId()));
        assertFalse(outbox.exists(entry.getId()));
    }

    @Test void blockedBackgroundThreadDoesNotBlockTheNextPrimaryEmbed() throws Exception
    {
        Scarlet scarlet = context();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger primary = new AtomicInteger();
        ScarletAuditFollowupWorker worker = new ScarletAuditFollowupWorker(id -> {
            started.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS), "Test must release the blocked follow-up"); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        try
        {
            ScarletDiscord discord = delivery(primary, worker::submit);
            discord.process(scarlet, ScarletDiscordSafetyTest.metadata("gaud_blocked_thread", "actor").entry);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
                discord.process(scarlet, ScarletDiscordSafetyTest.metadata("gaud_next_embed", "actor").entry));
            assertEquals(2, primary.get(), "Primary delivery must continue while the first thread is blocked");
        }
        finally
        {
            release.countDown();
            worker.close();
        }
    }

    @Test void runningAndQueuedIdentifiersAreCoalescedAndCloseRejectsNewWork() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        ScarletAuditFollowupWorker worker = new ScarletAuditFollowupWorker(id -> {
            started.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            completed.incrementAndGet();
        });
        try
        {
            assertTrue(worker.submit("gaud_running"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(worker.submit("gaud_running"));
            assertTrue(worker.submit("gaud_queued"));
            assertFalse(worker.submit("gaud_queued"));
        }
        finally { release.countDown(); worker.close(); }
        assertEquals(2, completed.get());
        assertFalse(worker.submit("gaud_after_shutdown"));
    }

    @Test void failedWorkerActionReleasesItsIdentifierForRetry() throws Exception
    {
        CountDownLatch failed = new CountDownLatch(1), retried = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        ScarletAuditFollowupWorker worker = new ScarletAuditFollowupWorker(id -> {
            if (attempts.incrementAndGet() == 1)
            {
                Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> failed.countDown());
                throw new IllegalStateException("Synthetic worker failure");
            }
            retried.countDown();
        });
        try
        {
            assertTrue(worker.submit("gaud_failure"));
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            assertTrue(worker.submit("gaud_failure"));
            assertTrue(retried.await(2, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        }
        finally { worker.close(); }
    }

    @Test void fullMemoryQueueRejectsWithoutRetainingAnUnscheduledIdentifier() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        ScarletAuditFollowupWorker worker = new ScarletAuditFollowupWorker(id -> {
            started.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        try
        {
            assertTrue(worker.submit("gaud_active"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 256; index++) assertTrue(worker.submit("gaud_queued_" + index));
            assertFalse(worker.submit("gaud_overflow"));
        }
        finally { release.countDown(); worker.close(); }
    }

    @Test void interruptedShutdownReportsFailureRatherThanAllowingOfflineMigration() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);
        ScarletAuditFollowupWorker worker = new ScarletAuditFollowupWorker(id -> {
            started.countDown();
            try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { stopped.countDown(); }
        });
        try
        {
            worker.submit("gaud_interrupt");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            assertThrows(java.io.IOException.class, worker::close);
            assertTrue(Thread.interrupted(), "Shutdown preserves the interruption for its caller");
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
        }
        finally { Thread.interrupted(); worker.close(); }
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

/** Exercises the real JDA summary worker with synthetic local records and offline send proxies. */
class ScarletSummaryBackgroundIntegrationTest
{
    @TempDir Path temp;

    private ScarletModerationSummaryFailureTest.Fixture fixture() throws Exception
    {
        ScarletModerationSummaryFailureTest owner = new ScarletModerationSummaryFailureTest();
        owner.temp = Files.createDirectory(this.temp.resolve("history"));
        ScarletModerationSummaryFailureTest.Fixture fixture = owner.new Fixture();
        put(fixture.discord, "summaryWorkerLock", new Object());
        return fixture;
    }

    private static void await(CountDownLatch latch) throws InterruptedException
    { assertTrue(latch.await(3L, TimeUnit.SECONDS), "Worker did not reach expected state"); }

    private static void awaitIdle(ScarletDiscordJDA discord) throws Exception
    {
        Field field = ScarletDiscordJDA.class.getDeclaredField("summaryWorker");
        field.setAccessible(true);
        ScarletSummaryWorker worker = (ScarletSummaryWorker)field.get(discord);
        CountDownLatch reached = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (worker.submit(reached::countDown) == ScarletSummaryWorker.Submission.BUSY)
        {
            assertTrue(System.nanoTime() < deadline, "Summary worker did not become idle");
            Thread.sleep(1L);
        }
        await(reached);
    }

    @Test void guildSwitchAfterAcceptanceDoesNotPublishToTheOldInteraction() throws Exception
    {
        ScarletModerationSummaryFailureTest.Fixture f = this.fixture();
        try
        {
            assertEquals(ScarletSummaryWorker.Submission.ACCEPTED,
                f.discord.requestModSummary(ScarletModerationSummaryFailureTest.END, 24L, f::send, status -> {
                    try { put(f.discord, "guildSf", "changed-server"); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }));
            awaitIdle(f.discord);
            assertTrue(f.embeds.isEmpty(), "A new server context must not reuse an earlier interaction destination");
        }
        finally { f.discord.closeSummaryWorker(); }
    }

    @Test void privacyIsRecheckedWhenAQueuedMessageActuallyDispatches() throws Exception
    {
        ScarletModerationSummaryFailureTest.Fixture f = this.fixture().staff("usr_staff");
        f.event("gaud_visible", "group.user.ban", "usr_staff", ScarletModerationSummaryFailureTest.END.minusDays(1));
        AtomicReference<BooleanSupplier> guard = new AtomicReference<>();
        AtomicInteger dispatched = new AtomicInteger();
        assertThrows(RuntimeException.class,
            () -> f.discord.emitModSummary(f.scarlet, ScarletModerationSummaryFailureTest.END, 48L, embed ->
                (MessageCreateAction)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{MessageCreateAction.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("setCheck"))
                        {
                            guard.set((BooleanSupplier)arguments[0]);
                            return proxy;
                        }
                        if (method.getName().equals("complete") || method.getName().equals("completeAfter"))
                        {
                            f.secret("usr_staff");
                            if (guard.get() != null && !guard.get().getAsBoolean())
                                throw new IllegalStateException("Dispatch cancelled by summary privacy guard");
                            dispatched.incrementAndGet();
                            return f.sent;
                        }
                        if (method.getReturnType().isInstance(proxy)) return proxy;
                        return null;
                    })));
        assertNotNull(guard.get(), "Privacy must be rechecked after any Discord queue or artificial delay");
        assertEquals(0, dispatched.get());
    }
}

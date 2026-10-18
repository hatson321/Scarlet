package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.Instance;
import io.github.vrchatapi.model.LimitedUserInstance;
import net.sybyline.scarlet.ext.ManagedVrcClient;
import net.sybyline.scarlet.ext.ManagedVrcClient.Client;
import net.sybyline.scarlet.ext.ManagedVrcClient.CloseResult;

/** Synthetic log/audit/API/process inputs only: no network, native process access or game files. */
class ScarletClientExitMonitorTest
{
    static final String GROUP = ScarletClosedInstanceExitTest.GROUP, OTHER_GROUP = ScarletClosedInstanceExitTest.OTHER_GROUP;
    static final String SELF = ScarletClosedInstanceExitTest.SELF, OTHER = ScarletClosedInstanceExitTest.OTHER;
    static final String LOCATION = ScarletClosedInstanceExitTest.LOCATION;
    static final String WORLD = LOCATION.substring(0, LOCATION.indexOf(':')), INSTANCE = LOCATION.substring(LOCATION.indexOf(':') + 1);
    static final Instant START = ScarletClosedInstanceExitTest.START, CLOSED = START.plusSeconds(10), NOW = START.plusSeconds(120);
    static final long WAIT = ScarletClosedInstanceExit.GRACE_NANOS, RETRY = TimeUnit.SECONDS.toNanos(60);
    @TempDir Path temp;

    static OffsetDateTime at(Instant time) { return time == null ? null : OffsetDateTime.ofInstant(time, ZoneOffset.UTC); }
    static Instance closed() { return new Instance().id(LOCATION).worldId(WORLD).instanceId(INSTANCE).closedAt(at(CLOSED)).nUsers(1).userCount(1); }
    static GroupAuditLogEntryUnknown event(String type, Instant time)
    {
        return new GroupAuditLogEntryUnknown().eventType(type).groupId(GROUP).targetId(LOCATION).createdAt(at(time));
    }
    static Client client(String location, Instant requested, Instant started)
    {
        return new Client(1, location, requested, 456789, started, "synthetic-user");
    }

    static final class Fixture implements ScarletClientExitMonitor.Processes
    {
        final AtomicBoolean enabled = new AtomicBoolean(true), healthy = new AtomicBoolean(true);
        final AtomicReference<String> group = new AtomicReference<>(GROUP);
        final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
        final AtomicLong nanos = new AtomicLong();
        final AtomicInteger queries = new AtomicInteger(), currentCalls = new AtomicInteger(), closeCalls = new AtomicInteger(), requested = new AtomicInteger();
        final AtomicReference<CloseResult> result = new AtomicReference<>();
        final AtomicReference<Client> current = new AtomicReference<>(client(LOCATION, START.minusSeconds(30), START.minusSeconds(20)));
        Instance response = closed();
        Runnable duringQuery = () -> {}, beforeClose = () -> {};
        CloseResult closeResult = CloseResult.REQUESTED;
        final ScarletClientExitMonitor monitor = new ScarletClientExitMonitor(enabled::get, group::get, healthy::get,
            this, location -> {
                assertEquals(LOCATION, location);
                queries.incrementAndGet();
                duringQuery.run();
                return response;
            }, result::set, clock::get, nanos::get);

        @Override public Client current() { currentCalls.incrementAndGet(); return current.get(); }
        @Override public CloseResult close(Client expected, BooleanSupplier revalidate)
        {
            closeCalls.incrementAndGet();
            beforeClose.run();
            if (expected != current.get()) return CloseResult.IDENTITY_CHANGED;
            if (!revalidate.getAsBoolean()) return CloseResult.CANCELLED;
            if (closeResult == CloseResult.REQUESTED) requested.incrementAndGet();
            return closeResult;
        }
        void live()
        {
            monitor.newLog();
            monitor.authenticated(SELF);
            monitor.joined(LOCATION, START, false);
            monitor.playerJoined(SELF);
        }
        void evidence() { monitor.audit(event("group.instance.close", CLOSED)); }
        void arm() { live(); evidence(); monitor.tick(); }
        void due() { nanos.set(WAIT); monitor.tick(); }
        void retry() { nanos.addAndGet(RETRY); monitor.tick(); }
        void unchangedTicks()
        {
            for (int i = 0; i < 5; ++i) { nanos.addAndGet(RETRY); monitor.tick(); }
        }
    }

    @Test void exactManagedClosedSoleSelfSessionRequestsOneCloseAfterGrace()
    {
        Fixture f = new Fixture(); f.arm();
        assertEquals(0, f.queries.get());
        f.nanos.set(WAIT - 1); f.monitor.tick(); assertEquals(0, f.closeCalls.get());
        f.due();
        assertEquals(1, f.queries.get()); assertEquals(1, f.requested.get()); assertEquals(CloseResult.REQUESTED, f.result.get());
        f.evidence(); f.monitor.playerJoined(SELF); f.unchangedTicks();
        assertEquals(1, f.queries.get()); assertEquals(1, f.closeCalls.get());
    }

    @Test void openOrMissingAuditNeverQueriesOrCloses()
    {
        Fixture f = new Fixture(); f.live(); f.monitor.tick(); f.due(); f.unchangedTicks();
        assertEquals(0, f.queries.get()); assertEquals(0, f.closeCalls.get());
    }

    @Test void closedOccupiedOrMissingLocalSelfNeverCloses()
    {
        Fixture f = new Fixture(); f.live(); f.monitor.playerJoined(OTHER); f.evidence(); f.monitor.tick(); f.due();
        assertEquals(0, f.queries.get());
        f.monitor.playerLeft(SELF); f.unchangedTicks(); assertEquals(0, f.closeCalls.get());
    }

    @Test void waitingForLastOtherPlayerThenRestartsThirtySecondGrace()
    {
        Fixture f = new Fixture(); f.live(); f.monitor.playerJoined(OTHER); f.evidence(); f.monitor.tick(); f.due();
        f.monitor.playerLeft(OTHER); f.monitor.tick();
        assertEquals(0, f.queries.get());
        f.nanos.addAndGet(WAIT - 1); f.monitor.tick(); assertEquals(0, f.queries.get());
        f.nanos.incrementAndGet(); f.monitor.tick(); assertEquals(1, f.requested.get());
    }

    static Stream<Arguments> invalidServerStates()
    {
        return Stream.of(
            Arguments.of("missing response / 404", (UnaryOperator<Instance>) i -> null),
            Arguments.of("no actual closure timestamp", (UnaryOperator<Instance>) i -> i.closedAt(null)),
            Arguments.of("future scheduled closure", (UnaryOperator<Instance>) i -> i.closedAt(at(NOW.plusNanos(1)))),
            Arguments.of("closure from prior session", (UnaryOperator<Instance>) i -> i.closedAt(at(START.minusNanos(1)))),
            Arguments.of("wrong full ID", (UnaryOperator<Instance>) i -> i.id(LOCATION + "~nonce(other)")),
            Arguments.of("missing full ID", (UnaryOperator<Instance>) i -> i.id(null)),
            Arguments.of("wrong world", (UnaryOperator<Instance>) i -> i.worldId("wrld_00000000-0000-0000-0000-000000000002")),
            Arguments.of("missing world", (UnaryOperator<Instance>) i -> i.worldId(null)),
            Arguments.of("wrong instance ID", (UnaryOperator<Instance>) i -> i.instanceId(INSTANCE + "~nonce(other)")),
            Arguments.of("missing instance ID", (UnaryOperator<Instance>) i -> i.instanceId(null)),
            Arguments.of("nUsers occupied", (UnaryOperator<Instance>) i -> i.nUsers(2)),
            Arguments.of("nUsers invalid", (UnaryOperator<Instance>) i -> i.nUsers(-1)),
            Arguments.of("userCount occupied", (UnaryOperator<Instance>) i -> i.userCount(2)),
            Arguments.of("userCount invalid", (UnaryOperator<Instance>) i -> i.userCount(-1)),
            Arguments.of("two reported users", (UnaryOperator<Instance>) i -> i.users(Arrays.asList(new LimitedUserInstance().id(SELF), new LimitedUserInstance().id(OTHER)))),
            Arguments.of("different sole reported user", (UnaryOperator<Instance>) i -> i.users(Collections.singletonList(new LimitedUserInstance().id(OTHER)))),
            Arguments.of("unidentified reported user", (UnaryOperator<Instance>) i -> i.users(Collections.singletonList(new LimitedUserInstance()))),
            Arguments.of("null reported user", (UnaryOperator<Instance>) i -> i.users(Collections.singletonList(null)))
        );
    }

    @ParameterizedTest(name = "{0}") @MethodSource("invalidServerStates")
    void inconsistentUnknownOpenOrFutureServerStateLeavesClientRunning(String name, UnaryOperator<Instance> mutate)
    {
        Fixture f = new Fixture(); f.response = mutate.apply(closed()); f.arm(); f.due();
        assertEquals(1, f.queries.get(), name); assertEquals(0, f.closeCalls.get(), name);
    }

    @Test void unknownSupplementalCountsDoNotReplaceKnownSoleSelfLogRoster()
    {
        Fixture f = new Fixture(); f.response = closed().nUsers(null).userCount(null).users(null); f.arm(); f.due();
        assertEquals(1, f.requested.get());
    }

    @Test void matchingServerSoleSelfIsAllowed()
    {
        Fixture f = new Fixture(); f.response = closed().users(Collections.singletonList(new LimitedUserInstance().id(SELF))); f.arm(); f.due();
        assertEquals(1, f.requested.get());
    }

    static Stream<Arguments> mismatchedProcesses()
    {
        return Stream.of(
            Arguments.of("unmanaged", (Client) null),
            Arguments.of("different launch location", client(LOCATION + "~nonce(other)", START.minusSeconds(30), START.minusSeconds(20))),
            Arguments.of("unknown requested location", client(null, START.minusSeconds(30), START.minusSeconds(20))),
            Arguments.of("unknown request time", client(LOCATION, null, START.minusSeconds(20))),
            Arguments.of("unknown process start", client(LOCATION, START.minusSeconds(30), null)),
            Arguments.of("log before requested launch", client(LOCATION, START.plusNanos(1), START.minusSeconds(20))),
            Arguments.of("log before process start", client(LOCATION, START.minusSeconds(30), START.plusNanos(1)))
        );
    }

    @ParameterizedTest(name = "{0}") @MethodSource("mismatchedProcesses")
    void uncertainProcessSessionCorrelationNeverQueriesOrCloses(String name, Client client)
    {
        Fixture f = new Fixture(); f.current.set(client); f.arm(); f.due(); f.unchangedTicks();
        assertEquals(0, f.queries.get(), name); assertEquals(0, f.closeCalls.get(), name);
    }

    @Test void oldWholeSecondLogCannotBeRoundedForwardPastSubsecondLaunch()
    {
        Fixture f = new Fixture(); f.current.set(client(LOCATION, START.plusMillis(100), START.plusMillis(500)));
        f.arm(); f.due(); assertEquals(0, f.queries.get());
    }

    @Test void futureOrUnknownLogTimestampNeverCloses()
    {
        Fixture f = new Fixture(); f.live(); f.monitor.joined(LOCATION, NOW.plusSeconds(1), false); f.monitor.playerJoined(SELF); f.evidence();
        f.monitor.tick(); f.due(); assertEquals(0, f.queries.get());
        f.monitor.joined(LOCATION, null, false); f.monitor.playerJoined(SELF); f.evidence(); f.unchangedTicks(); assertEquals(0, f.closeCalls.get());
    }

    @Test void processReplacedAfterBindingCannotInheritClosure()
    {
        Fixture f = new Fixture(); f.arm(); f.current.set(client(LOCATION, START.minusSeconds(30), START.minusSeconds(20)));
        f.due(); assertEquals(0, f.queries.get()); assertEquals(0, f.closeCalls.get());
    }

    @Test void delayedProcessDiscoveryRequiresFreshClosureEvidence()
    {
        Fixture f = new Fixture(); Client actual = f.current.get(); f.current.set(null); f.arm();
        f.current.set(actual); f.due(); f.retry(); assertEquals(0, f.closeCalls.get());
        f.evidence(); f.monitor.tick(); f.nanos.addAndGet(WAIT); f.monitor.tick(); assertEquals(1, f.requested.get());
    }

    @Test void preambleCannotActUntilCaughtUpAndFreshEvidence()
    {
        Fixture f = new Fixture(); f.monitor.newLog(); f.monitor.authenticated(SELF); f.monitor.joined(LOCATION, START, true);
        f.monitor.playerJoined(SELF); f.evidence(); f.monitor.tick(); f.due(); assertEquals(0, f.queries.get());
        f.monitor.caughtUp(); f.monitor.tick(); f.retry(); assertEquals(0, f.closeCalls.get());
        f.evidence(); f.monitor.tick(); f.nanos.addAndGet(WAIT); f.monitor.tick(); assertEquals(1, f.requested.get());
    }

    @Test void busyTrackingPreservesExactClosureButRequiresNewHealthyGrace()
    {
        Fixture f = new Fixture(); f.arm(); f.healthy.set(false); f.due(); assertEquals(0, f.queries.get());
        f.healthy.set(true); f.retry(); assertEquals(0, f.closeCalls.get());
        f.nanos.addAndGet(WAIT - 1); f.monitor.tick(); assertEquals(0, f.closeCalls.get());
        f.nanos.incrementAndGet(); f.monitor.tick(); assertEquals(1, f.requested.get());
        f.evidence(); f.monitor.tick(); f.nanos.addAndGet(WAIT); f.monitor.tick(); assertEquals(1, f.requested.get());
    }

    @Test void disabledAndChangedGroupBlockClose()
    {
        Fixture f = new Fixture(); f.arm(); f.enabled.set(false); f.due(); assertEquals(0, f.queries.get());
        f.enabled.set(true); f.retry(); assertEquals(0, f.closeCalls.get());
        f.evidence(); f.monitor.tick(); f.group.set(OTHER_GROUP); f.nanos.addAndGet(WAIT); f.monitor.tick(); assertEquals(0, f.closeCalls.get());
    }

    @Test void newLogQuitAndRejoinInvalidatePriorClosure()
    {
        Fixture f = new Fixture(); f.arm(); f.monitor.newLog(); f.due(); assertEquals(0, f.queries.get());
        f.live(); f.evidence(); f.monitor.tick(); f.monitor.left(); f.retry(); assertEquals(0, f.closeCalls.get());
        f.monitor.joined(LOCATION, CLOSED.plusSeconds(1), false); f.monitor.playerJoined(SELF); f.evidence(); f.unchangedTicks(); assertEquals(0, f.closeCalls.get());
    }

    @Test void wrongGroupLocationOldFutureAndUnrelatedAuditEventsCannotAuthorize()
    {
        Fixture f = new Fixture(); f.live();
        f.monitor.audit(event("group.instance.close", CLOSED).groupId(OTHER_GROUP));
        f.monitor.audit(event("group.instance.close", CLOSED).targetId(LOCATION + "~nonce(other)"));
        f.monitor.audit(event("group.instance.close", START.minusNanos(1)));
        f.monitor.audit(event("group.instance.close", NOW.plusNanos(1)));
        f.monitor.audit(event("group.instance.warn", CLOSED));
        f.monitor.audit(event("group.instance.close", null));
        f.monitor.audit(null); f.monitor.tick(); f.due(); assertEquals(0, f.queries.get());
    }

    @Test void newerCreateInvalidatesReadyClosure()
    {
        Fixture f = new Fixture(); f.arm(); f.monitor.audit(event("group.instance.create", CLOSED.plusSeconds(1))); f.due();
        assertEquals(0, f.queries.get());
    }

    @Test void rosterChangeDuringLookupCancelsThenNeedsFreshGrace()
    {
        Fixture f = new Fixture(); f.arm(); f.duringQuery = () -> f.monitor.playerJoined(OTHER); f.due();
        assertEquals(1, f.queries.get()); assertEquals(0, f.closeCalls.get());
        f.duringQuery = () -> {}; f.monitor.playerLeft(OTHER); f.retry();
        assertEquals(0, f.closeCalls.get());
        f.nanos.addAndGet(WAIT); f.monitor.tick(); assertEquals(1, f.requested.get());
    }

    @Test void joinAndLeaveDuringLookupStillInvalidatesProposal()
    {
        Fixture f = new Fixture(); f.arm(); f.duringQuery = () -> { f.monitor.playerJoined(OTHER); f.monitor.playerLeft(OTHER); }; f.due();
        assertEquals(0, f.closeCalls.get());
    }

    @Test void newLogDuringLookupCancelsProposal()
    {
        Fixture f = new Fixture(); f.arm(); f.duringQuery = f.monitor::newLog; f.due(); assertEquals(0, f.closeCalls.get());
    }

    @Test void worldTransitionDuringLookupCancelsProposal()
    {
        Fixture f = new Fixture(); f.arm(); f.duringQuery = () -> f.monitor.joined(LOCATION + "~nonce(new)", CLOSED.plusSeconds(1), false);
        f.due(); assertEquals(0, f.closeCalls.get());
    }

    @Test void disableGroupChangeAndHealthLossDuringLookupCancel()
    {
        Fixture disabled = new Fixture(); disabled.arm(); disabled.duringQuery = () -> disabled.enabled.set(false); disabled.due(); assertEquals(0, disabled.closeCalls.get());
        Fixture group = new Fixture(); group.arm(); group.duringQuery = () -> group.group.set(OTHER_GROUP); group.due(); assertEquals(0, group.closeCalls.get());
        Fixture unhealthy = new Fixture(); unhealthy.arm(); unhealthy.duringQuery = () -> unhealthy.healthy.set(false); unhealthy.due(); assertEquals(0, unhealthy.closeCalls.get());
    }

    @Test void processReplacedDuringLookupCannotReceiveClose()
    {
        Fixture f = new Fixture(); f.arm(); f.duringQuery = () -> f.current.set(client(LOCATION, START.minusSeconds(30), START.minusSeconds(20)));
        f.due(); assertEquals(0, f.requested.get());
    }

    @Test void slowLookupCannotAuthorizeClose()
    {
        Fixture f = new Fixture(); f.arm(); f.duringQuery = () -> f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(10) + 1); f.due();
        assertEquals(0, f.closeCalls.get());
    }

    @Test void backwardsQueryTimerCannotAuthorizeClose()
    {
        Fixture f = new Fixture(); f.arm(); f.duringQuery = () -> f.nanos.decrementAndGet(); f.due(); assertEquals(0, f.closeCalls.get());
    }

    @Test void missingOrOpenServerStateRetriesAtMostOncePerMinute()
    {
        Fixture f = new Fixture(); f.response = null; f.arm(); f.due(); assertEquals(1, f.queries.get());
        f.monitor.tick(); f.nanos.addAndGet(RETRY - 1); f.monitor.tick(); assertEquals(1, f.queries.get());
        f.response = closed(); f.nanos.incrementAndGet(); f.monitor.tick();
        assertEquals(2, f.queries.get()); assertEquals(1, f.requested.get());
    }

    @Test void thrownLookupReleasesConcurrencyGuardAndRetainsBackoff()
    {
        Fixture f = new Fixture(); f.arm(); f.duringQuery = () -> { throw new IllegalStateException("Synthetic API failure"); };
        assertThrows(IllegalStateException.class, f::due);
        f.duringQuery = () -> {}; f.monitor.tick(); assertEquals(1, f.queries.get());
        f.retry(); assertEquals(2, f.queries.get()); assertEquals(1, f.requested.get());
    }

    @Test void nestedTickCannotStartConcurrentLookup()
    {
        Fixture f = new Fixture(); f.arm(); f.duringQuery = f.monitor::tick; f.due();
        assertEquals(1, f.queries.get()); assertEquals(1, f.requested.get());
    }

    @Test void anotherThreadCanDeliverJoinWhileNetworkLookupIsBlocked() throws Exception
    {
        Fixture f = new Fixture(); f.arm();
        CountDownLatch querying = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        f.duringQuery = () -> {
            querying.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
        };
        try
        {
            Future<?> lookup = workers.submit(f::due);
            assertTrue(querying.await(5, TimeUnit.SECONDS));
            Future<?> join = workers.submit(() -> f.monitor.playerJoined(OTHER));
            join.get(2, TimeUnit.SECONDS);
            f.monitor.tick(); // A second tick is ignored while the existing network call is in flight.
            assertEquals(1, f.queries.get());
            release.countDown();
            lookup.get(5, TimeUnit.SECONDS);
            assertEquals(0, f.closeCalls.get());
        }
        finally
        {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void finalProcessCallbackRevalidatesEnabledAndHealth()
    {
        Fixture f = new Fixture(); f.arm(); f.beforeClose = () -> f.enabled.set(false); f.due();
        assertEquals(0, f.requested.get()); assertEquals(CloseResult.CANCELLED, f.result.get());
        Fixture unhealthy = new Fixture(); unhealthy.arm(); unhealthy.beforeClose = () -> unhealthy.healthy.set(false); unhealthy.due();
        assertEquals(0, unhealthy.requested.get()); assertEquals(CloseResult.CANCELLED, unhealthy.result.get());
    }

    @Test void deniedCloseIsReportedOnceWithoutRetryStorm()
    {
        Fixture f = new Fixture(); f.closeResult = CloseResult.UNSUPPORTED_OR_DENIED; f.arm(); f.due(); f.unchangedTicks();
        assertEquals(1, f.closeCalls.get()); assertEquals(0, f.requested.get()); assertEquals(CloseResult.UNSUPPORTED_OR_DENIED, f.result.get());
    }

    @Test void disabledSettingDoesNotEvenDiscoverProcesses()
    {
        Fixture f = new Fixture(); f.enabled.set(false); f.arm(); f.due(); f.unchangedTicks();
        assertEquals(0, f.currentCalls.get()); assertEquals(0, f.queries.get()); assertEquals(0, f.closeCalls.get());
    }

    Scarlet inertScarlet(Fixture f) throws Exception
    {
        Scarlet scarlet = ScarletDiscordSafetyTest.inert(Scarlet.class);
        ScarletEventListener listener = ScarletDiscordSafetyTest.inert(ScarletEventListener.class);
        ScarletDiscordSafetyTest.put(listener, "clientExitMonitor", f.monitor);
        ScarletDiscordSafetyTest.put(scarlet, "eventListener", listener);
        ScarletDiscordSafetyTest.put(scarlet, "data", new ScarletData(temp.toFile()));
        return scarlet;
    }

    @Test void deliveredDiscordAuditStillFeedsClosureBeforeDeduplication() throws Exception
    {
        Fixture f = new Fixture(); f.live();
        Scarlet scarlet = inertScarlet(f);
        GroupAuditLogEntryUnknown event = event("group.instance.close", CLOSED).id("gaud_synthetic_completed_close");
        ScarletData.AuditEntryMetadata metadata = new ScarletData.AuditEntryMetadata(); metadata.entry = event;
        metadata.setAuxData("deliveryState", "delivered");
        scarlet.data.auditEntryMetadata(event.getId(), metadata);
        ScarletDiscord discord = ScarletDiscordSafetyTest.discord((proxy, method, values) -> {
            fail("Delivered event must not emit again: " + method.getName()); return null;
        });
        discord.process(scarlet, event);
        f.monitor.tick(); f.due(); assertEquals(1, f.requested.get());
    }

    @Test void suppressedOutboxReceiptStillFeedsClosureBeforeTerminalReturn() throws Exception
    {
        Fixture f = new Fixture(); f.live();
        Scarlet scarlet = inertScarlet(f);
        GroupAuditLogEntryUnknown event = event("group.instance.close", CLOSED).id("gaud_synthetic_suppressed_close");
        ScarletData.AuditEntryMetadata metadata = new ScarletData.AuditEntryMetadata(); metadata.entry = event;
        metadata.entryRedacted = true; metadata.setAuxData("deliveryState", "suppressed");
        new ScarletAuditOutbox(scarlet.data.dir).save(metadata);
        ScarletDiscord discord = ScarletDiscordSafetyTest.discord((proxy, method, values) -> {
            fail("Suppressed terminal receipt must not emit: " + method.getName()); return null;
        });
        discord.process(scarlet, event);
        f.monitor.tick(); f.due(); assertEquals(1, f.requested.get());
    }
}

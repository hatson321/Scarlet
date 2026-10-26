package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletClientExitMonitorTest.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.vrchatapi.model.Instance;
import io.github.vrchatapi.model.LimitedUserInstance;

/** Diagnostic-only tests: no network, processes, credentials or real logs. */
class ScarletServerExitDiagnosticsTest
{
    static Set<String> reasons(Instance response)
    { return ScarletClientExitMonitor.serverCheckFailures(response, LOCATION, SELF, START, NOW); }

    static String expectedReason(String name)
    {
        switch (name)
        {
        case "missing response / 404": return "response-unavailable";
        case "no actual closure timestamp": return "closedAt-missing";
        case "future scheduled closure": return "closedAt-in-future";
        case "closure from prior session": return "closedAt-before-session";
        case "wrong full ID": return "id-mismatch";
        case "missing full ID": return "id-missing";
        case "wrong world": return "worldId-mismatch";
        case "missing world": return "worldId-missing";
        case "wrong instance ID": return "instanceId-mismatch";
        case "missing instance ID": return "instanceId-missing";
        case "nUsers occupied": return "nUsers-above-one";
        case "nUsers invalid": return "nUsers-negative";
        case "userCount occupied": return "userCount-above-one";
        case "userCount invalid": return "userCount-negative";
        case "two reported users": return "users-more-than-one";
        case "different sole reported user": return "users-not-self";
        case "unidentified reported user": return "users-id-missing";
        case "null reported user": return "users-null-entry";
        default: throw new AssertionError("Unmapped test case: " + name);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("net.sybyline.scarlet.ScarletClientExitMonitorTest#invalidServerStates")
    void eachRejectedServerFieldIsExplainedWithoutChangingDecision(String name, UnaryOperator<Instance> mutate)
    {
        Fixture f = new Fixture(); f.response = mutate.apply(closed()); f.arm(); f.due();
        String code = "server-check-" + expectedReason(name);
        assertEquals(Collections.singleton(code), reasons(f.response));
        assertTrue(f.diagnostics.contains(code), f.diagnostics.toString());
        assertEquals(1, f.queries.get()); assertEquals(0, f.closeCalls.get());
        assertFalse(ScarletClientExitMonitor.confirmedClosed(f.response, LOCATION, SELF, START, NOW));
    }

    @Test void severalFailuresAppearTogetherWithoutLeakingResponseValues()
    {
        String secret = "private-synthetic-value-do-not-log";
        Fixture f = new Fixture();
        f.response = closed().id(secret).worldId(secret).instanceId(secret).closedAt(null)
            .nUsers(9).userCount(-7).users(Collections.singletonList(new LimitedUserInstance().id(secret)));
        f.arm(); f.due();
        Set<String> expected = new HashSet<>(Arrays.asList("server-check-id-mismatch", "server-check-worldId-mismatch",
            "server-check-instanceId-mismatch", "server-check-closedAt-missing", "server-check-nUsers-above-one",
            "server-check-userCount-negative", "server-check-users-not-self"));
        assertEquals(expected, reasons(f.response));
        assertTrue(f.diagnostics.containsAll(expected));
        for (String diagnostic : f.diagnostics)
        {
            assertFalse(diagnostic.contains(secret)); assertFalse(diagnostic.contains(LOCATION));
            assertFalse(diagnostic.contains(SELF)); assertFalse(diagnostic.contains(GROUP));
            assertFalse(diagnostic.contains(CLOSED.toString()));
        }
        assertEquals(0, f.closeCalls.get());
    }

    @Test void repeatedRejectionKeepsSixtySecondApiBackoffAndPerReasonThrottle()
    {
        Fixture f = new Fixture(); f.response = closed().closedAt(null); f.arm(); f.due();
        for (int seconds = 1; seconds < 60; seconds++)
        { f.nanos.set(WAIT + TimeUnit.SECONDS.toNanos(seconds)); f.monitor.tick(); }
        assertEquals(1, f.queries.get());
        assertEquals(1, Collections.frequency(f.diagnostics, "server-check-closedAt-missing"));
        f.nanos.set(WAIT + RETRY); f.monitor.tick();
        assertEquals(2, f.queries.get());
        assertEquals(2, Collections.frequency(f.diagnostics, "server-check-closedAt-missing"));
        assertEquals(0, f.closeCalls.get());
    }

    @Test void acceptedStatesHaveNoFailureReasonsAndCloseOnlyOnce()
    {
        for (Integer count : Arrays.asList(null, 0, 1))
        {
            Fixture f = new Fixture(); f.response = closed().nUsers(count).userCount(count);
            assertTrue(reasons(f.response).isEmpty());
            f.arm(); f.due(); f.unchangedTicks();
            assertEquals(1, f.queries.get()); assertEquals(1, f.requested.get());
            assertTrue(f.diagnostics.stream().noneMatch(s -> s.startsWith("server-check-")));
        }
    }

    @Test void diagnosticMatrixAgreesWithUnchangedAcceptancePredicate()
    {
        for (Integer n : Arrays.asList(null, -1, 0, 1, 2))
        for (Integer count : Arrays.asList(null, -1, 0, 1, 2))
        for (Instant closedAt : Arrays.asList(null, START.minusNanos(1), START, CLOSED, NOW, NOW.plusNanos(1)))
        for (int users = 0; users < 6; users++)
        {
            Instance response = closed().nUsers(n).userCount(count).closedAt(at(closedAt));
            if (users == 1) response.users(Collections.emptyList());
            if (users == 2) response.users(Collections.singletonList(new LimitedUserInstance().id(SELF)));
            if (users == 3) response.users(Collections.singletonList(new LimitedUserInstance().id(OTHER)));
            if (users == 4) response.users(Collections.singletonList(null));
            if (users == 5) response.users(Arrays.asList(new LimitedUserInstance().id(SELF), new LimitedUserInstance().id(OTHER)));
            assertEquals(ScarletClientExitMonitor.confirmedClosed(response, LOCATION, SELF, START, NOW), reasons(response).isEmpty());
        }
    }

    @Test void missingLocalProofIsExplainedWithoutInventingServerState()
    {
        Set<String> missing = ScarletClientExitMonitor.serverCheckFailures(null, null, null, null, null);
        assertEquals(new HashSet<>(Arrays.asList("server-check-response-unavailable", "server-check-expected-location-missing",
            "server-check-expected-self-missing", "server-check-session-start-missing", "server-check-current-time-missing")), missing);
        for (String location : Arrays.asList("private", "offline", "invalid", ""))
        {
            assertFalse(ScarletClientExitMonitor.confirmedClosed(closed(), location, SELF, START, NOW));
            assertTrue(ScarletClientExitMonitor.serverCheckFailures(closed(), location, SELF, START, NOW)
                .contains("server-check-expected-location-invalid"));
        }
    }

    @Test void explanationFailureCannotChangeRejectionOrExposeExceptionText()
    {
        Fixture f = new Fixture();
        f.response = new Instance() {
            @Override public String getWorldId() { throw new IllegalStateException("private-response-content"); }
        }.id("wrong-synthetic-id");
        f.arm(); f.due();
        assertEquals(1, f.queries.get()); assertEquals(0, f.closeCalls.get());
        assertTrue(f.diagnostics.contains("server-check-details-unavailable"));
        assertTrue(f.diagnostics.stream().noneMatch(s -> s.contains("private-response-content")));
    }
}

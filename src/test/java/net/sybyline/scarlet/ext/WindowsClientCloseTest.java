package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import net.sybyline.scarlet.ext.ManagedVrcClient.CloseResult;

/** Synthetic windows only; never calls native APIs or sends a real close request. */
class WindowsClientCloseTest
{
    static final class Fake implements WindowsClientClose.Windows<Integer>
    {
        List<Integer> windows = Collections.singletonList(7);
        int ownershipChecks, posts, error = 5, reported = -1;
        boolean owner = true, post = true;
        RuntimeException enumerationFailure, postFailure, errorFailure;
        BooleanSupplier identity = () -> true, guard = () -> true;
        public List<Integer> visible(long pid) { if (enumerationFailure != null) throw enumerationFailure; return windows; }
        public boolean belongsTo(Integer window, long pid) { ++ownershipChecks; assertEquals(42, pid); return owner; }
        public boolean postClose(Integer window) { ++posts; if (postFailure != null) throw postFailure; return post; }
        public int lastError() { if (errorFailure != null) throw errorFailure; return error; }
        CloseResult run() { return WindowsClientClose.request(42, this, identity, guard, n -> reported = n); }
    }

    @Test void missingFailedAndAmbiguousEnumerationNeverDispatch()
    {
        Fake f = new Fake(); f.windows = null; assertEquals(CloseResult.WINDOW_ENUMERATION_FAILED, f.run());
        f.windows = Collections.emptyList(); assertEquals(CloseResult.WINDOW_NOT_FOUND, f.run());
        f.windows = Arrays.asList(7, 8); assertEquals(CloseResult.WINDOW_AMBIGUOUS, f.run());
        f.enumerationFailure = new IllegalStateException(); assertEquals(CloseResult.WINDOW_ENUMERATION_FAILED, f.run());
        assertEquals(0, f.posts); assertEquals(0, f.ownershipChecks);
    }

    @Test void unknownOrReplacedWindowAndProcessNeverDispatch()
    {
        Fake f = new Fake(); f.windows = Collections.singletonList(null); assertEquals(CloseResult.WINDOW_IDENTITY_CHANGED, f.run());
        f.windows = Collections.singletonList(7); f.owner = false; assertEquals(CloseResult.WINDOW_IDENTITY_CHANGED, f.run());
        f.owner = true; f.identity = () -> false; assertEquals(CloseResult.IDENTITY_CHANGED, f.run());
        assertEquals(0, f.posts);
    }

    @Test void windowOwnershipIsRecheckedAfterSlowFinalGuard()
    {
        Fake f = new Fake(); f.guard = () -> { f.owner = false; return true; };
        assertEquals(CloseResult.WINDOW_IDENTITY_CHANGED, f.run()); assertEquals(2, f.ownershipChecks); assertEquals(0, f.posts);
    }

    @Test void explicitVetoIsDefinitelyUnsentButThrownCheckIsTerminal()
    {
        Fake f = new Fake(); f.guard = () -> false; assertEquals(CloseResult.FINAL_GUARD_VETO, f.run());
        f.guard = () -> { throw new IllegalStateException(); }; assertEquals(CloseResult.WINDOW_CHECK_UNAVAILABLE, f.run());
        assertEquals(0, f.posts);
    }

    @Test void successfulPostOccursExactlyOnce()
    {
        Fake f = new Fake(); assertEquals(CloseResult.REQUESTED, f.run()); assertEquals(1, f.posts);
        assertEquals(2, f.ownershipChecks); assertEquals(-1, f.reported);
    }

    @Test void windowsDenialAndRejectionIncludeNumericErrorWithoutRetry()
    {
        Fake f = new Fake(); f.post = false;
        assertEquals(CloseResult.WINDOWS_ACCESS_DENIED, f.run()); assertEquals(5, f.reported);
        f.error = 1400; assertEquals(CloseResult.WINDOWS_POST_REJECTED, f.run()); assertEquals(1400, f.reported);
        f.error = 0; assertEquals(CloseResult.WINDOWS_POST_REJECTED, f.run());
    }

    @Test void exceptionDuringOrAfterDispatchIsUncertainNeverRetryable()
    {
        Fake f = new Fake(); f.postFailure = new IllegalStateException();
        assertEquals(CloseResult.DISPATCH_UNCERTAIN, f.run()); assertEquals(1, f.posts);
        f.postFailure = null; f.post = false; f.errorFailure = new IllegalStateException();
        assertEquals(CloseResult.DISPATCH_UNCERTAIN, f.run()); assertEquals(2, f.posts);
    }

    @Test void diagnosticFailureCannotTurnDenialIntoRetry()
    {
        Fake f = new Fake(); f.post = false;
        assertEquals(CloseResult.WINDOWS_ACCESS_DENIED, WindowsClientClose.request(42, f, f.identity, f.guard,
            n -> { throw new IllegalStateException(); }));
    }

    @ParameterizedTest @EnumSource(CloseResult.class)
    void onlyExplicitPreDispatchTransientResultsAllowRetry(CloseResult result)
    {
        assertEquals(EnumSet.of(CloseResult.WINDOW_ENUMERATION_FAILED, CloseResult.WINDOW_NOT_FOUND,
            CloseResult.WINDOW_AMBIGUOUS, CloseResult.FINAL_GUARD_VETO).contains(result), result.retryableBeforeDispatch());
    }

    @Test void typedTransportVetoDistinguishesLateHealthFromIdentityLoss()
    {
        for (int kind = 0; kind < 3; kind++)
        {
            ManagedVrcClientTest.MutableClock clock = new ManagedVrcClientTest.MutableClock();
            ManagedVrcClientTest.FakeProcesses data = new ManagedVrcClientTest.FakeProcesses();
            boolean[] allowed = {true};
            ManagedVrcClient.Processes typed = new ManagedVrcClient.Processes() {
                public String currentUser() { return data.currentUser(); }
                public List<ManagedVrcClient.ProcessInfo> snapshot() { return data.snapshot(); }
                public ManagedVrcClient.ProcessInfo find(long pid) { return data.find(pid); }
                public boolean requestClose(ManagedVrcClient.ProcessInfo p, BooleanSupplier guard) { throw new AssertionError(); }
                public CloseResult requestCloseDetailed(ManagedVrcClient.ProcessInfo p, BooleanSupplier guard) {
                    data.beforeClose.run();
                    if (!guard.getAsBoolean()) return CloseResult.FINAL_GUARD_VETO;
                    ++data.signals; return CloseResult.REQUESTED;
                }
            };
            ManagedVrcClient.Tracker tracker = new ManagedVrcClient.Tracker(typed, clock);
            long token = tracker.beginLaunch("wrld_test:123~group(grp_test)"); assertTrue(token > 0);
            data.launcher = new ManagedVrcClient.ProcessInfo(100, clock.instant(), data.user, "/synthetic/launch.exe", Collections.emptyList());
            tracker.launchCompleted(token, data.launcher); clock.advance(1);
            data.entries.add(new ManagedVrcClient.ProcessInfo(41, clock.instant(), data.user, "/synthetic/VRChat.exe", Collections.emptyList(), 100));
            assertNull(tracker.current()); clock.advance(3); ManagedVrcClient.Client client = tracker.current(); assertNotNull(client);
            if (kind == 0) data.beforeClose = () -> allowed[0] = false;
            if (kind == 1) data.beforeClose = () -> data.user = "changed-user";
            if (kind == 2) data.beforeClose = tracker::stopLaunching;
            assertEquals(kind == 0 ? CloseResult.FINAL_GUARD_VETO : kind == 1 ? CloseResult.IDENTITY_CHANGED : CloseResult.CANCELLED,
                tracker.close(client, () -> allowed[0]));
            assertEquals(0, data.signals);
        }
    }
}

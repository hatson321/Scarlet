package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletIdleInstanceTransportTest.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.vrchatapi.model.Instance;

/** Synthetic interceptor responses only; no live API or credentials. */
class ScarletExitTransportTest
{
    @TempDir Path temp;
    @Test void exitReadsExactPrimaryEndpointWithoutCacheOrFallbackAndPreservesRawCounts() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            f.body = "{\"n_users\":7,\"userCount\":2,\"users\":[]}";
            Instance result = f.vrc.readClientExitInstance(LOCATION, () -> true);
            assertNotNull(result); assertEquals(7, result.getnUsers()); assertEquals(2, result.getUserCount());
            assertEquals(1, f.requests.size());
            assertEquals("GET", f.requests.get(0).method());
            assertEquals("/api/1/instances/" + LOCATION, f.requests.get(0).url().encodedPath());
            assertEquals("no-cache, no-store", f.requests.get(0).header("Cache-Control"));
            assertNull(f.requests.get(0).url().query());
            f.status = 404;
            assertNull(f.vrc.readClientExitInstance(LOCATION, () -> true));
            assertEquals(2, f.requests.size(), "No world endpoint fallback or retry");
        }
    }
    @Test void rejectedMalformedOrUnsafeResponsesNeverBecomeProof() throws Exception
    {
        for (String kind : new String[]{"forbidden", "rate-limit", "redirect", "bad-count", "malformed", "trailing", "guard"})
            try (Fixture f = new Fixture(temp.resolve(kind)))
            {
                AtomicBoolean allowed = new AtomicBoolean(true);
                if (kind.equals("forbidden")) f.status = 403;
                if (kind.equals("rate-limit")) f.status = 429;
                if (kind.equals("redirect")) f.status = 302;
                if (kind.equals("bad-count")) f.body = "{\"n_users\":\"invalid\"}";
                if (kind.equals("malformed")) f.body = "{";
                if (kind.equals("trailing")) f.body += " {}";
                if (kind.equals("guard")) f.duringResponse = () -> allowed.set(false);
                assertNull(f.vrc.readClientExitInstance(LOCATION, allowed::get), kind);
                assertEquals(1, f.requests.size(), kind);
            }
    }
    @Test void disabledOrDifferentGroupCannotDispatch() throws Exception
    {
        try (Fixture f = new Fixture(temp))
        {
            assertNull(f.vrc.readClientExitInstance(LOCATION, () -> false));
            f.vrc.groupId = ScarletIdleInstanceCloseTest.OTHER_GROUP;
            assertNull(f.vrc.readClientExitInstance(LOCATION, () -> true));
            assertTrue(f.requests.isEmpty());
        }
    }
}

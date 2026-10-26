package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Locale;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;

import net.sybyline.scarlet.ext.ManagedVrcClient.LaunchFailure;

/** Synthetic exception values only; diagnostics must never echo raw remote or OS details. */
class ScarletStartupFailureTest
{
    private static final String PRIVATE_DETAIL = "synthetic-secret-token https://private.invalid/join?shortName=private-value "
        + "wrld_11111111-1111-1111-1111-111111111111:123~group(grp_11111111-1111-1111-1111-111111111111) "
        + "C:\\Users\\PrivatePerson\\AppData\\private-file";

    private static void assertSafe(String description)
    {
        assertNotNull(description);
        assertFalse(description.trim().isEmpty());
        for (String privateText : new String[]{"synthetic-secret", "private.invalid", "private-value", "wrld_", "grp_",
            "PrivatePerson", "private-file", "SecretsInTypeNameFailure"})
            assertFalse(description.contains(privateText), description);
        assertFalse(description.contains("\n"));
        assertFalse(description.contains("\r"));
    }

    @Test void allKnownKindsHaveStableSafeDescriptionsAndIgnoreAttachedCauses()
    {
        for (ScarletStartupFailure.Kind kind : ScarletStartupFailure.Kind.values())
        {
            ScarletStartupFailure first = new ScarletStartupFailure(kind);
            ScarletStartupFailure second = new ScarletStartupFailure(kind);
            second.initCause(new IOException(PRIVATE_DETAIL));
            second.addSuppressed(new SecretsInTypeNameFailure(PRIVATE_DETAIL));
            String description = ScarletStartupFailure.describe(first);
            assertSafe(description);
            assertEquals(description, ScarletStartupFailure.describe(second), kind.name());
        }
    }

    @Test void validHttpStatusIsUsefulWithoutResponseMessageOrBody()
    {
        for (int status : new int[]{100, 200, 302, 403, 429, 500, 599})
        {
            ScarletStartupFailure failure = ScarletStartupFailure.http(status);
            failure.initCause(new IOException(PRIVATE_DETAIL));
            String description = ScarletStartupFailure.describe(failure);
            assertSafe(description);
            assertTrue(description.contains("HTTP " + status), description);
        }
    }

    @Test void invalidHttpNumbersNeverBecomeInventedStatusDiagnostics()
    {
        for (int status : new int[]{Integer.MIN_VALUE, -1, 0, 99, 600, 999, Integer.MAX_VALUE})
        {
            String description = ScarletStartupFailure.describe(ScarletStartupFailure.http(status));
            assertSafe(description);
            assertFalse(description.contains("HTTP " + status), description);
            assertFalse(description.contains(Integer.toString(status)), description);
        }
    }

    @Test void timeoutAndTlsCategoriesKeepUsefulFixedTextWithoutRawMessages()
    {
        String timeout = ScarletStartupFailure.describe(new SocketTimeoutException(PRIVATE_DETAIL));
        assertSafe(timeout);
        assertTrue(timeout.toLowerCase(Locale.ROOT).contains("tim"), timeout);
        assertEquals(timeout, ScarletStartupFailure.describe(new SocketTimeoutException("different private value")));
        assertEquals(timeout, ScarletStartupFailure.describe(new InterruptedIOException(PRIVATE_DETAIL)));

        String tls = ScarletStartupFailure.describe(new SSLHandshakeException(PRIVATE_DETAIL));
        assertSafe(tls);
        assertEquals("The secure connection could not be established.", tls);
        assertEquals(tls, ScarletStartupFailure.describe(new SSLException("different private value")));
        assertNotEquals(timeout, tls);
    }

    @Test void genericIoAndUnknownTypesNeverRevealMessageCauseOrClassName()
    {
        String io = ScarletStartupFailure.describe(new IOException(PRIVATE_DETAIL));
        assertSafe(io);
        assertEquals(io, ScarletStartupFailure.describe(new FileNotFoundException("different private path")));
        assertEquals(io, ScarletStartupFailure.describe(new IOException(PRIVATE_DETAIL, new SSLException(PRIVATE_DETAIL))),
            "Unknown exception causes must not be traversed or surfaced");

        String unknown = ScarletStartupFailure.describe(new SecretsInTypeNameFailure(PRIVATE_DETAIL));
        assertSafe(unknown);
        assertEquals(unknown, ScarletStartupFailure.describe(new IllegalArgumentException("different private value")));
        assertEquals(unknown, ScarletStartupFailure.describe(null));
    }

    @Test void untrustedExceptionAccessorsAreNeverEvaluated()
    {
        IOException failure = new IOException()
        {
            private static final long serialVersionUID = 1L;
            @Override public String getMessage() { throw new AssertionError("Never inspect arbitrary exception messages"); }
            @Override public String toString() { throw new AssertionError("Never render arbitrary exceptions"); }
            @Override public synchronized Throwable getCause() { throw new AssertionError("Never traverse arbitrary causes"); }
        };
        assertSafe(ScarletStartupFailure.describe(failure));
    }

    @Test void launchOwnershipDiagnosticsAreSpecificSafeAndDoNotAssertWorldJoinFailure()
    {
        for (LaunchFailure.Kind kind : LaunchFailure.Kind.values())
        {
            LaunchFailure failure = new LaunchFailure(kind);
            failure.initCause(new IOException(PRIVATE_DETAIL));
            failure.addSuppressed(new SecretsInTypeNameFailure(PRIVATE_DETAIL));
            String description = ScarletStartupFailure.describe(failure);
            assertSafe(description);
            assertEquals(failure.description(), description);
            assertTrue(description.contains("process ownership"));
            assertTrue(description.contains("left running"));
            assertTrue(description.contains("startup recovery will not close or restart it"));
            assertTrue(description.contains("closed/empty-instance exit setting is independent"));
            assertFalse(description.contains("failed to join"));
            assertFalse(description.contains("successfully joined"));
            if (kind == LaunchFailure.Kind.REQUESTED_BUT_UNVERIFIED)
            {
                assertTrue(description.startsWith("VRChat launch was requested"));
                assertTrue(description.contains("does not say whether an instance was joined"));
            }
            else
                assertFalse(description.contains("launch was requested"));
        }
    }

    @Test void trustedLaunchFailureCannotAcceptArbitraryDiagnosticText()
    {
        assertTrue(java.lang.reflect.Modifier.isFinal(LaunchFailure.class.getModifiers()));
        for (java.lang.reflect.Constructor<?> constructor : LaunchFailure.class.getDeclaredConstructors())
        {
            if (java.lang.reflect.Modifier.isPublic(constructor.getModifiers()))
                assertArrayEquals(new Class<?>[]{LaunchFailure.Kind.class}, constructor.getParameterTypes());
            else if (constructor.isSynthetic())
            {
                // Java 8 bytecode uses a package-private bridge for the enclosing
                // tracker's call to the private constructor, plus a synthetic marker.
                assertFalse(java.lang.reflect.Modifier.isProtected(constructor.getModifiers()));
                Class<?>[] parameters = constructor.getParameterTypes();
                assertEquals(3, parameters.length);
                assertEquals(LaunchFailure.Kind.class, parameters[0]);
                assertTrue(parameters[1].isEnum());
                assertEquals("net.sybyline.scarlet.ext.ManagedVrcClient$DiscoveryIssue", parameters[1].getName());
                assertTrue(parameters[2].isSynthetic(), "Only the compiler's synthetic access marker is allowed");
                assertTrue(parameters[2].getName().startsWith("net.sybyline.scarlet.ext.ManagedVrcClient$"));
            }
            else
            {
                assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
                for (Class<?> parameter : constructor.getParameterTypes())
                    assertTrue(parameter.isEnum(), "Internal tracking diagnostics must also use closed enums, never arbitrary text");
            }
        }
        assertThrows(NullPointerException.class, () -> new LaunchFailure(null));
    }

    @Test void trustedFailureCannotBeSubclassedOrConstructedWithArbitraryText()
    {
        assertTrue(java.lang.reflect.Modifier.isFinal(ScarletStartupFailure.class.getModifiers()));
        for (java.lang.reflect.Constructor<?> constructor : ScarletStartupFailure.class.getDeclaredConstructors())
            for (Class<?> parameter : constructor.getParameterTypes())
                assertTrue(parameter == ScarletStartupFailure.Kind.class || parameter == Integer.TYPE,
                    "Only closed failure kinds or numeric HTTP statuses may supply trusted diagnostics");
    }

    static final class SecretsInTypeNameFailure extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        SecretsInTypeNameFailure(String message) { super(message); }
    }
}

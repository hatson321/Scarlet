package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;

import io.github.vrchatapi.ApiException;

/** Synthetic error bodies only. No live network, credentials, or tester records. */
class ScarletCloseDiagnosticsTest
{
    private static final String CLOSED = "You're not allowed to close an already closed instance.";

    @Test void alreadyClosedClassificationRequiresExactNestedReasonAndHttp403()
    {
        assertTrue(ScarletCloseDiagnostics.isAlreadyClosed(exception(403, closedBody(null))));
        assertTrue(ScarletCloseDiagnostics.isAlreadyClosed(exception(403, closedBody("403"))));
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(exception(401, closedBody("403"))));
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(exception(500, closedBody("403"))));
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(exception(0, closedBody("403"))));
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(null));
        JsonObject root = new JsonObject();
        root.addProperty("message", CLOSED);
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(exception(403, root.toString())));
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(exception(403,
            closedBody("403").replace("already closed", "not allowed"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"403\"", "403.0", "4.03e2", "401", "true", "{}", "[]"})
    void alreadyClosedDoesNotAcceptWrongTypedOrContradictoryStatus(String status)
    {
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(exception(403, closedBody(status))));
    }

    @Test void alreadyClosedRejectsDeepDuplicateAndOversizedErrorBodies()
    {
        String valid = closedBody("403");
        StringBuilder deep = new StringBuilder(valid.substring(0, valid.length() - 1)).append(",\"data\":");
        for (int index = 0; index < 1000; index++) deep.append('[');
        deep.append('0');
        for (int index = 0; index < 1000; index++) deep.append(']');
        deep.append('}');
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(exception(403, deep.toString())));
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(exception(403,
            valid.replace("\"status_code\":403", "\"status_code\":401,\"status_code\":403"))));
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(exception(403, valid + repeat(' ', 16384))));
        assertFalse(ScarletCloseDiagnostics.isAlreadyClosed(exception(403, null)));
    }

    @Test void ordinaryPermissionReasonAndAllowedCodesRemainUseful()
    {
        assertEquals("error.message=not allowed to close this instance; status_code=403; waf_code=27033",
            describe("{\"error\":{\"message\":\"Not allowed to close this instance.\",\"status_code\":403,\"waf_code\":27033}}"));
    }

    @Test void unknownWordsAreMaskedRatherThanCopiedVerbatim()
    {
        assertEquals("error.message=instance [redacted] permission denied", message("Instance SyntheticPerson permission denied"));
    }

    @Test void topLevelMessageIsExplicitlyAFallback()
    {
        assertEquals("top-level message (fallback)=too many requests; status_code=429",
            describe("{\"message\":\"Too many requests.\",\"status_code\":429}"));
    }

    @Test void errorMessageTakesPrecedenceOverRootMessage()
    {
        String result = describe("{\"message\":\"SYNTHETIC_ROOT_PRIVATE\",\"error\":{\"message\":\"Permission denied\",\"status_code\":403}}");
        assertEquals("error.message=permission denied; status_code=403", result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password=permission denied", "token: owner instance", "Bearer group close",
        "Authorization: Basic allowed request", "Cookie: session=owner", "api_key=permission",
        "refresh-token: group", "client_secret=closed", "nonce=already closed", "authCookie=allowed",
        "credentials=permission denied", "session: owner", "secret is permitted", "key=group",
        "username=owner", "display_name=group", "user-id=instance", "groupId=owner", "email=group",
        "private_key=permission denied", "display name=owner", "private key=instance", "\"username\"=owner",
        "`display_name` = owner group", "access_key=private public", "secretKey=private public",
        "auth: permission denied", "auth_token=private public"})
    void credentialSpansAreRemovedEvenWhenValueWordsAreAllowlisted(String credential)
    {
        assertEquals("error.message=invalid [redacted]", message("Invalid " + credential));
    }

    @Test void ordinaryOwnershipAndPolicyReasonsRemainReadable()
    {
        assertEquals("error.message=this instance belongs to the group owner and is blocked by security policy",
            message("This instance belongs to the group owner and is blocked by security policy."));
        assertEquals("error.message=cannot close permanent private instance created by creator",
            message("Cannot close permanent private instance created by creator."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"usr_10000000-0000-0000-0000-000000000001", "grp_10000000-0000-0000-0000-000000000002",
        "192.0.2.1", "2001:db8::1", "user@example.invalid", "https://example.invalid/private?value=abc",
        "eyJhbGciOiJub25lIn0.eyJzdWIiOiJzeW50aGV0aWMifQ.signature", "123456789012345678",
        "\"owner\"", "'group'", "[owner]", "`instance`", "<@123456789012345678>",
        "`owner group instance`", "\u201cowner group\u201d", "\u2018owner group\u2019"})
    void identifiersAndQuotedValuesAreRedactedAsWholeTokens(String identifier)
    {
        assertEquals("error.message=instance [redacted] not found", message("Instance " + identifier + " not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"owner group", "'owner group", "`owner group", "\u201cowner group", "\u2018owner group"})
    void unmatchedQuotesDoNotExposeLaterDictionaryWords(String value)
    {
        assertEquals("error.message=instance [redacted]", message("Instance " + value + " not found"));
    }

    @Test void ordinaryAuthWordDoesNotDiscardTheFollowingReason()
    {
        assertEquals("error.message=[redacted] failed", message("Auth failed"));
    }

    @Test void headersExceptionMessagesAndUnselectedJsonFieldsNeverEnterDiagnostics()
    {
        String body = "{\"error\":{\"message\":\"Permission denied\",\"private\":\"SYNTHETIC_NESTED_PRIVATE\"},"
            + "\"headers\":{\"Set-Cookie\":\"SYNTHETIC_BODY_COOKIE\"},\"data\":\"SYNTHETIC_EXTRA_PRIVATE\"}";
        ApiException exception = new ApiException("SYNTHETIC_THROWABLE_PRIVATE", 403,
            Collections.singletonMap("Set-Cookie", Collections.singletonList("SYNTHETIC_HEADER_PRIVATE")), body);
        assertEquals("error.message=permission denied", ScarletCloseDiagnostics.describe(exception));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"error\":", "{\"error\":{\"message\":\"Permission denied\"}} trailing",
        "<html>Private server failure</html>", "[]", "null", "\"SYNTHETIC_PRIVATE\"",
        "{\"error\":{\"message\":\"Permission denied\",\"message\":\"SYNTHETIC_PRIVATE\"}}"})
    void malformedOrUnsupportedBodiesProduceSafeNonemptyMarkers(String body)
    {
        String result = describe(body);
        assertTrue(result.contains("omitted"), result);
        assertFalse(result.contains("SYNTHETIC"));
        assertSafe(result);
    }

    @Test void deeplyNestedUnselectedDataCannotExhaustTheStack()
    {
        StringBuilder body = new StringBuilder("{\"error\":{\"message\":\"Permission denied\"},\"data\":");
        for (int index = 0; index < 1000; index++) body.append('[');
        body.append('0');
        for (int index = 0; index < 1000; index++) body.append(']');
        body.append('}');
        assertEquals("error body omitted: structure limit exceeded", describe(body.toString()));
    }

    @Test void tooManySmallTokensAreRejected()
    {
        StringBuilder body = new StringBuilder("{\"data\":[0");
        for (int index = 0; index < 600; index++) body.append(",0");
        body.append("]}");
        assertEquals("error body omitted: structure limit exceeded", describe(body.toString()));
    }

    @Test void oversizedBodyAndMessageAreExplicitlyOmitted()
    {
        assertEquals("error body omitted: size limit exceeded", describe(repeat('x', 16385)));
        assertEquals("error body omitted: size limit exceeded", describe(repeat('\u20ac', 6000)));
        assertEquals("error.message=[message omitted: size limit exceeded]", message(repeat('x', 2049)));
    }

    @Test void largeAllowedMessageIsBoundedWithoutSplittingUnsafeTokens()
    {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < 200; index++) value.append("instance ");
        String result = message(value.toString());
        assertTrue(result.endsWith("[truncated]"));
        assertSafe(result);
    }

    @Test void controlBidiAndTerminalInjectionCannotCreateAnotherLogLine()
    {
        String result = message("Permission\r\ndenied\u0000\tinstance\u001b[31m \u202eSYNTHETIC_PRIVATE\u2066");
        assertTrue(result.contains("permission denied"), result);
        assertFalse(result.contains("SYNTHETIC"));
        assertSafe(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"403\"", "403.0", "4.03e2", "-403", "999", "null", "{}", "[]"})
    void statusCodesMustBeSmallNumericHttpIntegers(String status)
    {
        assertEquals("error.message=permission denied",
            describe("{\"error\":{\"message\":\"Permission denied\",\"status_code\":" + status + "}}"));
    }

    @Test void wafCodesCannotBecomeAnUnboundedNumericIdentifier()
    {
        assertEquals("error.message=permission denied", describe(
            "{\"error\":{\"message\":\"Permission denied\",\"waf_code\":123456789012345678}}"));
    }

    @Test void missingNullAndWrongTypeMessagesAreExplicit()
    {
        assertEquals("error response absent", ScarletCloseDiagnostics.describe(null));
        assertEquals("structured error body absent", describe(null));
        assertEquals("structured error body absent", describe(" "));
        for (String value : new String[]{"null", "123", "true", "{}", "[]"})
            assertEquals("structured error message absent or unsupported; status_code=403",
                describe("{\"error\":{\"message\":" + value + ",\"status_code\":403}}"));
    }

    private static String message(String value)
    {
        JsonObject root = new JsonObject(), error = new JsonObject();
        error.addProperty("message", value);
        root.add("error", error);
        return describe(root.toString());
    }

    private static String describe(String body)
    {
        String result = ScarletCloseDiagnostics.describe(exception(403, body));
        assertSafe(result);
        return result;
    }

    private static ApiException exception(int status, String body)
    {
        return new ApiException(status, Collections.emptyMap(), body);
    }

    private static String closedBody(String status)
    {
        return "{\"error\":{\"message\":\"" + CLOSED + "\""
            + (status == null ? "" : ",\"status_code\":" + status) + "}}";
    }

    private static void assertSafe(String result)
    {
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.length() <= 500, result);
        assertTrue(result.matches("[ -~]+"), "Only single-line printable ASCII is permitted");
    }

    private static String repeat(char value, int count)
    {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}

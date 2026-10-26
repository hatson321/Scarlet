package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class ScarletExitInstanceEvidenceTest
{
    static Response response(String age, String status)
    {
        return new Response.Builder().request(new Request.Builder().url("https://example.invalid/secret").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("Age", age).header("CF-Cache-Status", status).build();
    }
    @Test void recordsOnlyCountsAndAllowlistedCacheMetadata()
    {
        String detail = ScarletExitInstanceEvidence.describe(JsonParser.parseString(
            "{\"n_users\":2,\"userCount\":1,\"users\":[{\"id\":\"private-user\"}],\"id\":\"private-location\"}"), response("42", "HIT"));
        assertTrue(detail.contains("n_users=2")); assertTrue(detail.contains("userCount=1"));
        assertTrue(detail.contains("listedUsers=1")); assertTrue(detail.contains("cacheAgeSeconds=42"));
        assertTrue(detail.contains("edgeCache=HIT"));
        assertFalse(detail.contains("private")); assertFalse(detail.contains("secret"));
    }
    @Test void arbitraryResponseStringsAndHeadersNeverAppearInDiagnostics()
    {
        String detail = ScarletExitInstanceEvidence.describe(JsonParser.parseString(
            "{\"n_users\":\"secret\",\"userCount\":{},\"users\":\"secret\"}"), response("secret", "secret"));
        assertFalse(detail.contains("secret")); assertTrue(detail.contains("n_users=invalid"));
        assertTrue(detail.contains("userCount=invalid"));
    }
    @Test void missingCountsAreNotInventedAsZero()
    {
        String detail = ScarletExitInstanceEvidence.describe(JsonParser.parseString("{}"), response("0", "DYNAMIC"));
        assertTrue(detail.contains("n_users=not-supplied")); assertTrue(detail.contains("userCount=not-supplied"));
    }
}

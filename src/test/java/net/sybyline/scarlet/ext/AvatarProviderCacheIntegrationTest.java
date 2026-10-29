package net.sybyline.scarlet.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.sybyline.scarlet.ScarletAvatarProviderCache;
import net.sybyline.scarlet.ext.AvatarSearch.VrcxAvatar;

/** Memory-only caches, in-memory response streams and hard backoff sentinels; no provider traffic. */
class AvatarProviderCacheIntegrationTest
{
    static final String PROVIDER = "https://synthetic-provider.invalid/search";
    static final String OTHER = "https://other-synthetic-provider.invalid/search";
    final ScarletAvatarProviderCache cache = new ScarletAvatarProviderCache(null);

    @BeforeEach void preventUnexpectedNetworkCalls()
    {
        for (String provider : new String[]{PROVIDER, OTHER, AvatarSearch.URL_ROOT_AVTRDB})
        {
            AvatarSearch.providerBlockedUntil.put(provider, Long.MAX_VALUE);
            AvatarSearch.providerBlockedUntil.put(AvatarSearch.ByImage.authorStateKey(provider), Long.MAX_VALUE);
        }
    }

    @AfterEach void cleanup()
    {
        cache.close();
        AvatarSearch.LegacyCacheHolder.CACHE.clear();
        AvatarSearch.clearProviderBackoffs();
    }

    static VrcxAvatar avatar(String name)
    {
        VrcxAvatar avatar = new VrcxAvatar();
        avatar.id = "avtr_" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        avatar.name = name;
        avatar.imageUrl = "https://images.invalid/file_synthetic/1/image";
        return avatar;
    }

    void seed(String provider, String mode, int n, String query, String name)
    {
        assertNotNull(cache.get(provider, mode, n, query, () -> true, () -> new VrcxAvatar[]{avatar(name)}));
    }

    static List<String> names(Stream<VrcxAvatar> values)
    {
        return values.map(VrcxAvatar::name).collect(Collectors.toList());
    }

    @Test void repeatedTextLookupReusesTheExactCachedResponseWithoutLoadingAProvider()
    {
        AtomicInteger loads = new AtomicInteger();
        cache.get(PROVIDER, "text", 50, "same query", () -> true, () -> {
            loads.incrementAndGet(); return new VrcxAvatar[]{avatar("cached")};
        });
        for (int attempt = 0; attempt < 3; attempt++)
            assertEquals(List.of("cached"), names(AvatarSearch.vrcxSearchAllCached(cache,
                new String[]{PROVIDER}, 50, "same query", () -> true)));
        cache.get(PROVIDER, "text", 50, "same query", () -> true, () -> {
            loads.incrementAndGet(); return new VrcxAvatar[]{avatar("unexpected")};
        });
        assertEquals(1, loads.get());
        assertFalse(AvatarSearch.providerAvailable(PROVIDER), "Cache hits must not clear provider backoff");
    }

    @Test void providerModeCountAndExactQueryRemainSeparateCacheKeys()
    {
        seed(PROVIDER, "text", 1, "query", "text-one");
        seed(PROVIDER, "text", 2, "query", "text-two");
        seed(PROVIDER, "text", 1, "Query", "case-sensitive");
        seed(OTHER, "text", 1, "query", "other-provider");
        seed(PROVIDER, "image", 1, "query", "image");
        seed(PROVIDER, "author", 1, "query", "author");
        assertEquals("text-one", AvatarSearch.vrcxSearchCached(cache, PROVIDER, 1, "query", () -> true)[0].name);
        assertEquals("text-two", AvatarSearch.vrcxSearchCached(cache, PROVIDER, 2, "query", () -> true)[0].name);
        assertEquals("case-sensitive", AvatarSearch.vrcxSearchCached(cache, PROVIDER, 1, "Query", () -> true)[0].name);
        assertEquals("other-provider", AvatarSearch.vrcxSearchCached(cache, OTHER, 1, "query", () -> true)[0].name);
        assertEquals("image", AvatarSearch.ByImage.vrcxSearchCachedByImage(cache, PROVIDER, 1, "query", () -> true)[0].name);
        assertEquals("author", AvatarSearch.ByImage.vrcxSearchCachedByAuthor(cache, PROVIDER, 1, "query", () -> true)[0].name);
        assertNull(AvatarSearch.vrcxSearchCached(cache, PROVIDER, 3, "query", () -> true));
        assertNull(AvatarSearch.vrcxSearchCached(cache, PROVIDER, 1, "query ", () -> true));
    }

    @Test void explicitEmptyOrNullSelectionNeverFallsBackToDefaultProviders()
    {
        seed(PROVIDER, "text", 50, "query", "not-selected");
        seed(PROVIDER, "author", AvatarSearch.SEARCH_N, "author", "not-selected");
        for (String[] selection : new String[][]{null, new String[0], new String[]{null, " ", ""}})
        {
            assertEquals(0L, AvatarSearch.vrcxSearchAllCached(cache, selection, 50, "query", () -> true).count());
            assertEquals(0L, AvatarSearch.ByImage.vrcxSearchAllCachedByImage(cache, selection,
                "file_synthetic", "author", () -> true).count());
        }
        assertEquals(List.of("not-selected"), names(AvatarSearch.vrcxSearchAllCached(cache,
            new String[]{" " + PROVIDER + " ", PROVIDER}, 50, "query", () -> true)));
    }

    @Test void imageFallbackReusesAuthorResultsButStillFiltersTheRequestedPictureAndSelectedProviders()
    {
        seed(PROVIDER, "author", AvatarSearch.SEARCH_N, "author", "chosen");
        seed(OTHER, "author", AvatarSearch.SEARCH_N, "author", "not-selected");
        seed(AvatarSearch.URL_ROOT_AVTRDB, "author", AvatarSearch.SEARCH_N, "author", "name-only");
        assertEquals(List.of("chosen"), names(AvatarSearch.ByImage.vrcxSearchAllCachedByImage(cache,
            new String[]{PROVIDER, AvatarSearch.URL_ROOT_AVTRDB}, "file_synthetic", "author", () -> true)));
        assertEquals(0L, AvatarSearch.ByImage.vrcxSearchAllCachedByImage(cache,
            new String[]{PROVIDER}, "file_different", "author", () -> true).count());
        assertEquals(0L, AvatarSearch.ByImage.vrcxSearchAllCachedByImage(cache,
            new String[]{PROVIDER}, "file_synthetic", null, () -> true).count());
    }

    @Test void cachedResultsCannotBypassRevokedRuntimePermissionIncludingLazyConsumption()
    {
        seed(PROVIDER, "text", 50, "query", "cached");
        seed(PROVIDER, "author", AvatarSearch.SEARCH_N, "author", "cached");
        assertEquals(0L, AvatarSearch.vrcxSearchAllCached(cache, new String[]{PROVIDER}, 50, "query", () -> false).count());
        assertEquals(0L, AvatarSearch.ByImage.vrcxSearchAllCachedByImage(cache, new String[]{PROVIDER},
            "file_synthetic", "author", () -> false).count());
        AtomicBoolean allowed = new AtomicBoolean(true);
        Stream<VrcxAvatar> delayed = AvatarSearch.vrcxSearchAllCached(cache, new String[]{PROVIDER}, 50, "query", allowed::get);
        allowed.set(false);
        assertEquals(0L, delayed.count());
        assertNull(AvatarSearch.vrcxSearchCached(cache, PROVIDER, 50, "query", null));
    }

    @Test void blockedOrInvalidProviderFailuresAreNotSavedAsSuccessfulEmptyResults()
    {
        assertNull(AvatarSearch.vrcxSearchCached(cache, PROVIDER, 50, "query", () -> true));
        AtomicInteger loads = new AtomicInteger();
        assertEquals(1, cache.get(PROVIDER, "text", 50, "query", () -> true, () -> {
            loads.incrementAndGet(); return new VrcxAvatar[]{avatar("recovered")};
        }).length);
        assertEquals(1, loads.get(), "The failed/backed-off transport must not create a negative cache entry");
        assertNull(AvatarSearch.vrcxSearch0("file:///synthetic", 1, "query"));
        assertNull(AvatarSearch.ByImage.vrcxSearch0ByImage(null, 1, "image"));
        assertNull(AvatarSearch.ByImage.vrcxSearch0ByAuthor("invalid", 1, "author"));
    }

    @Test void genuineEmptySuccessCanBeReusedWithoutCallingTheProvider()
    {
        cache.get(PROVIDER, "text", 50, "empty", () -> true, () -> new VrcxAvatar[0]);
        VrcxAvatar[] result = AvatarSearch.vrcxSearchCached(cache, PROVIDER, 50, "empty", () -> true);
        assertNotNull(result); assertEquals(0, result.length);
    }

    @Test void legacySearchWrappersUseTheBoundedMemoryBackend()
    {
        AvatarSearch.LegacyCacheHolder.CACHE.get(PROVIDER, "text", 50, "legacy", () -> true,
            () -> new VrcxAvatar[]{avatar("legacy-text")});
        AvatarSearch.LegacyCacheHolder.CACHE.get(PROVIDER, "image", 50, "legacy", () -> true,
            () -> new VrcxAvatar[]{avatar("legacy-image")});
        AvatarSearch.LegacyCacheHolder.CACHE.get(PROVIDER, "author", 50, "legacy", () -> true,
            () -> new VrcxAvatar[]{avatar("legacy-author")});
        assertEquals("legacy-text", AvatarSearch.vrcxSearchCached(PROVIDER, 50, "legacy")[0].name);
        assertEquals("legacy-image", AvatarSearch.ByImage.vrcxSearchCachedByImage(PROVIDER, 50, "legacy")[0].name);
        assertEquals("legacy-author", AvatarSearch.ByImage.vrcxSearchCachedByAuthor(PROVIDER, 50, "legacy")[0].name);
    }

    @Test void invalidCountsAreRejectedBeforeProviderOrParserWork()
    {
        for (int n : new int[]{0, -1, Integer.MIN_VALUE})
        {
            assertThrows(IllegalArgumentException.class, () -> AvatarSearch.vrcxSearch0(PROVIDER, n, "query"));
            assertThrows(IllegalArgumentException.class, () -> AvatarSearch.ByImage.vrcxSearch0ByImage(PROVIDER, n, "image"));
            assertThrows(IllegalArgumentException.class, () -> AvatarSearch.ByImage.vrcxSearch0ByAuthor(PROVIDER, n, "author"));
            assertThrows(IllegalArgumentException.class, () -> AvatarSearch.vrcxSearchAllCached(cache,
                new String[0], n, "query", () -> false));
            assertThrows(IllegalArgumentException.class, () -> AvatarSearch.parseVrcxResults(new JsonArray(), n));
        }
    }

    static JsonArray records(int count)
    {
        JsonArray records = new JsonArray();
        for (int index = 0; index < count; index++)
        {
            JsonObject record = new JsonObject();
            record.addProperty("id", "avtr_" + UUID.nameUUIDFromBytes(("record-" + index).getBytes(StandardCharsets.UTF_8)));
            record.addProperty("name", "Synthetic " + index);
            records.add(record);
        }
        return records;
    }

    @Test void parserNeverReturnsMoreThanRequestedOrTheFiveThousandHardCeiling()
    {
        JsonArray records = records(5_002);
        assertEquals(3, AvatarSearch.parseVrcxResults(records, 3).length);
        assertEquals(5_000, AvatarSearch.parseVrcxResults(records, Integer.MAX_VALUE).length);
        assertEquals(5_000, AvatarSearch.parseVrcxResults(records).length);
        for (String key : List.of("avatars", "results", "data", "items", "records"))
        {
            JsonObject wrapper = new JsonObject(); wrapper.add(key, records);
            assertEquals(2, AvatarSearch.parseVrcxResults(wrapper, 2).length, key);
        }
    }

    @Test void parserKeepsValidRecordsAndNestedAuthorCompatibilityButRejectsMalformedOnlyResponses()
    {
        JsonArray mixed = JsonParser.parseString("[null, 42, {}, {\"id\":\"avtr_synthetic\",\"author\":{\"vrc_id\":\"usr_synthetic\",\"name\":\"Author\"}}]").getAsJsonArray();
        VrcxAvatar[] parsed = AvatarSearch.parseVrcxResults(mixed, 1);
        assertEquals(1, parsed.length); assertEquals("usr_synthetic", parsed[0].authorId); assertEquals("Author", parsed[0].authorName);
        for (String body : List.of("null", "{}", "[null,42,{}]", "[{\"id\":\" \"}]", "{\"error\":\"maintenance\"}"))
            assertThrows(JsonSyntaxException.class, () -> AvatarSearch.parseVrcxResults(JsonParser.parseString(body), 5), body);
        assertEquals(0, AvatarSearch.parseVrcxResults(new JsonArray(), 5).length);
    }

    static byte[] paddedEmptyResponse(int length)
    {
        byte[] data = new byte[length]; Arrays.fill(data, (byte)' '); data[0] = '['; data[1] = ']'; return data;
    }

    @Test void exactByteCeilingIsAcceptedAndOneExtraByteIsRejectedBeforeGson() throws Exception
    {
        byte[] maximum = paddedEmptyResponse(AvatarSearch.MAX_PROVIDER_RESPONSE_BYTES);
        assertEquals(0, AvatarSearch.readVrcxResults(new ByteArrayInputStream(maximum), null, 5).length);
        byte[] oversized = Arrays.copyOf(maximum, maximum.length + 1); oversized[oversized.length - 1] = ' ';
        JsonSyntaxException failure = assertThrows(JsonSyntaxException.class,
            () -> AvatarSearch.readVrcxResults(new ByteArrayInputStream(oversized), null, 5));
        assertTrue(failure.getMessage().contains("byte limit"));
    }

    @Test void compressedResponsesAreLimitedAfterDecodingAndOrdinaryGzipAndDeflateStillWork() throws Exception
    {
        byte[] normal = records(3).toString().getBytes(StandardCharsets.UTF_8);
        for (boolean gzip : new boolean[]{true, false})
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (java.io.OutputStream compressor = gzip ? new GZIPOutputStream(bytes) : new DeflaterOutputStream(bytes))
            { compressor.write(normal); }
            assertEquals(2, AvatarSearch.readVrcxResults(new ByteArrayInputStream(bytes.toByteArray()), gzip ? "gzip" : "deflate", 2).length);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream compressor = new GZIPOutputStream(bytes))
        { compressor.write(paddedEmptyResponse(AvatarSearch.MAX_PROVIDER_RESPONSE_BYTES + 1)); }
        assertTrue(bytes.size() < AvatarSearch.MAX_PROVIDER_RESPONSE_BYTES);
        assertThrows(JsonSyntaxException.class, () -> AvatarSearch.readVrcxResults(new ByteArrayInputStream(bytes.toByteArray()), "gzip", 5));
    }

    @Test void unsupportedEncodingAndMalformedBodiesFailInsteadOfProducingCacheableEmptySuccess()
    {
        assertThrows(JsonSyntaxException.class,
            () -> AvatarSearch.readVrcxResults(new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)), "unsupported", 5));
        assertThrows(JsonSyntaxException.class,
            () -> AvatarSearch.readVrcxResults(new ByteArrayInputStream("not JSON".getBytes(StandardCharsets.UTF_8)), null, 5));
        assertThrows(IOException.class,
            () -> AvatarSearch.readVrcxResults(new ByteArrayInputStream(new byte[]{1, 2, 3}), "gzip", 5));
    }
}

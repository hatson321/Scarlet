package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
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
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.sybyline.scarlet.ext.AvatarSearch.VrcxAvatar;

/** Only synthetic metadata and isolated temporary files; no application, provider, account or game access. */
class ScarletAvatarProviderCacheTest
{
    @TempDir Path directory;
    private static final String PROVIDER = "https://provider.example.invalid/vrcx";
    private static final BooleanSupplier ALLOWED = () -> true;
    private final AtomicLong clock = new AtomicLong(100_000);

    private ScarletAvatarProviderCache cache(Path path)
    {
        return this.cache(path, 256, 1024 * 1024, 64 * 1024, 5000, 16);
    }

    private ScarletAvatarProviderCache cache(Path path, int entries, int bytes, int entryBytes, int results, int flights)
    {
        return new ScarletAvatarProviderCache(path, this.clock::get, entries, bytes, entryBytes, results, flights, 1000, 100);
    }

    private static VrcxAvatar avatar(String name)
    {
        VrcxAvatar avatar = new VrcxAvatar();
        avatar.id = "avtr_00000000-0000-0000-0000-000000000001";
        avatar.name = name;
        return avatar;
    }

    private static VrcxAvatar[] result(String name) { return new VrcxAvatar[]{avatar(name)}; }
    private static VrcxAvatar[] get(ScarletAvatarProviderCache cache, String query, Supplier<VrcxAvatar[]> loader)
    { return cache.get(PROVIDER, "text", 50, query, ALLOWED, loader); }
    private static VrcxAvatar[] mustHit()
    { throw new AssertionError("A fresh cache hit must not invoke a provider loader"); }

    @Test void constructorAndDeniedLookupsDoNotCreateOrInspectStorage() throws Exception
    {
        Path parent = this.directory.resolve("not-created"), file = parent.resolve("avatar-provider-cache.json");
        try (ScarletAvatarProviderCache cache = this.cache(file))
        {
            assertFalse(Files.exists(parent));
            assertNull(cache.get(PROVIDER, "text", 50, "query", () -> false, ScarletAvatarProviderCacheTest::mustHit));
            assertFalse(Files.exists(parent));
        }
        assertFalse(Files.exists(parent));
    }

    @Test void positiveResultsAreReusedAndEveryReturnIsADefensiveDecode()
    {
        VrcxAvatar[] supplied = result("original");
        supplied[0].performance = new VrcxAvatar.Performance();
        supplied[0].performance.pcRating = "Good";
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            VrcxAvatar[] first = get(cache, "same", () -> supplied);
            assertNotSame(supplied, first);
            assertNotSame(supplied[0], first[0]);
            first[0].name = "changed consumer";
            supplied[0].performance.pcRating = "changed supplier";
            VrcxAvatar[] second = get(cache, "same", ScarletAvatarProviderCacheTest::mustHit);
            assertEquals("original", second[0].name);
            assertEquals("Good", second[0].performance.pcRating);
            assertNotSame(first[0], second[0]);
        }
    }

    @Test void exactProviderModeCountAndQueryAreSeparateKeys()
    {
        AtomicInteger calls = new AtomicInteger();
        Supplier<VrcxAvatar[]> loader = () -> result("result-" + calls.incrementAndGet());
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            assertEquals("result-1", cache.get(PROVIDER, "text", 50, "Case", ALLOWED, loader)[0].name);
            assertEquals("result-2", cache.get(PROVIDER + "/other", "text", 50, "Case", ALLOWED, loader)[0].name);
            assertEquals("result-3", cache.get(PROVIDER, "author", 50, "Case", ALLOWED, loader)[0].name);
            assertEquals("result-4", cache.get(PROVIDER, "image", 50, "Case", ALLOWED, loader)[0].name);
            assertEquals("result-5", cache.get(PROVIDER, "text", 5000, "Case", ALLOWED, loader)[0].name);
            assertEquals("result-6", cache.get(PROVIDER, "text", 50, "case", ALLOWED, loader)[0].name);
            assertEquals("result-1", cache.get(PROVIDER, "text", 50, "Case", ALLOWED, ScarletAvatarProviderCacheTest::mustHit)[0].name);
            assertEquals(6, calls.get());
        }
    }

    @Test void positiveTtlDoesNotSlideWhenAResultIsRead()
    {
        AtomicInteger calls = new AtomicInteger();
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            Supplier<VrcxAvatar[]> loader = () -> result("result-" + calls.incrementAndGet());
            get(cache, "q", loader);
            this.clock.addAndGet(999);
            assertEquals("result-1", get(cache, "q", ScarletAvatarProviderCacheTest::mustHit)[0].name);
            this.clock.incrementAndGet();
            assertEquals("result-2", get(cache, "q", loader)[0].name);
        }
    }

    @Test void successfulEmptyResultsHaveTheirOwnShorterTtl()
    {
        AtomicInteger calls = new AtomicInteger();
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            Supplier<VrcxAvatar[]> empty = () -> { calls.incrementAndGet(); return new VrcxAvatar[0]; };
            assertEquals(0, get(cache, "q", empty).length);
            this.clock.addAndGet(99);
            assertEquals(0, get(cache, "q", ScarletAvatarProviderCacheTest::mustHit).length);
            this.clock.incrementAndGet();
            assertEquals(0, get(cache, "q", empty).length);
            assertEquals(2, calls.get());
        }
    }

    @Test void nullAndExceptionalLoadsAreNeverNegativeCached()
    {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException sensitive = new RuntimeException() {
            private static final long serialVersionUID = 1L;
            @Override public String getMessage() { throw new AssertionError("Do not inspect private error details"); }
            @Override public String toString() { throw new AssertionError("Do not log private error details"); }
        };
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            assertNull(get(cache, "q", () -> { calls.incrementAndGet(); return null; }));
            assertNull(get(cache, "q", () -> { calls.incrementAndGet(); throw sensitive; }));
            assertEquals("success", get(cache, "q", () -> { calls.incrementAndGet(); return result("success"); })[0].name);
            assertEquals(3, calls.get());
        }
    }

    @Test void restartRestoresPublicMetadataAndPreservesTheOriginalFetchTime() throws Exception
    {
        Path file = this.directory.resolve("avatar-provider-cache.json");
        try (ScarletAvatarProviderCache first = this.cache(file)) { get(first, "q", () -> result("saved")); }
        assertTrue(Files.isRegularFile(file));
        ScarletPrivateFilesTest.assertPrivate(file);
        this.clock.addAndGet(900);
        try (ScarletAvatarProviderCache second = this.cache(file))
        {
            assertEquals("saved", get(second, "q", ScarletAvatarProviderCacheTest::mustHit)[0].name);
            this.clock.addAndGet(100);
            assertEquals("fresh", get(second, "q", () -> result("fresh"))[0].name);
        }
    }

    @Test void emptyResultsSurviveRestartOnlyUntilTheirNegativeTtl()
    {
        Path file = this.directory.resolve("empty.json");
        try (ScarletAvatarProviderCache first = this.cache(file)) { get(first, "q", () -> new VrcxAvatar[0]); }
        this.clock.addAndGet(99);
        try (ScarletAvatarProviderCache second = this.cache(file))
        {
            assertEquals(0, get(second, "q", ScarletAvatarProviderCacheTest::mustHit).length);
            this.clock.incrementAndGet();
            assertEquals("now found", get(second, "q", () -> result("now found"))[0].name);
        }
    }

    @Test void profilesDoNotShareTheirMemoryOrSavedEntries()
    {
        try (ScarletAvatarProviderCache a = this.cache(this.directory.resolve("a.json"));
             ScarletAvatarProviderCache b = this.cache(this.directory.resolve("b.json")))
        {
            get(a, "q", () -> result("profile A"));
            assertEquals("profile B", get(b, "q", () -> result("profile B"))[0].name);
            assertEquals("profile A", get(a, "q", ScarletAvatarProviderCacheTest::mustHit)[0].name);
        }
    }

    @Test void savedEnvelopeHasHashesAndPublicMetadataButNoRawQueryOrProvider() throws Exception
    {
        Path file = this.directory.resolve("fixed-cache-name.json");
        try (ScarletAvatarProviderCache cache = this.cache(file))
        { get(cache, "operator-query-not-for-disk", () -> result("public avatar name")); }
        String saved = Files.readString(file);
        assertFalse(saved.contains(PROVIDER));
        assertFalse(saved.contains("operator-query-not-for-disk"));
        JsonObject envelope = JsonParser.parseString(saved).getAsJsonObject();
        assertEquals(4, envelope.size());
        JsonObject entry = envelope.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals(4, entry.size());
        assertTrue(entry.get("key").getAsString().matches("[a-f0-9]{64}"));
        String metadata = new String(Base64.getDecoder().decode(entry.get("metadata").getAsString()), StandardCharsets.UTF_8);
        assertTrue(metadata.contains("public avatar name"));
        try (java.util.stream.Stream<Path> children = Files.list(this.directory))
        { assertEquals(1, children.count(), "No staging files may remain after atomic publication"); }
    }

    @Test void allWhitelistedMetadataRoundTripsWithoutRetainingImageBlobs()
    {
        VrcxAvatar avatar = avatar("Public name");
        avatar.authorId = "usr_synthetic"; avatar.authorName = "Public author"; avatar.description = "Public description";
        avatar.imageUrl = "https://images.example.invalid/avatar.png";
        avatar.thumbnailImageUrl = "data:image/png;base64,not-an-image-to-store";
        avatar.releaseStatus = "public";
        avatar.createdAt = OffsetDateTime.parse("2026-10-01T10:00:00Z");
        avatar.updatedAt = OffsetDateTime.parse("2026-10-28T11:00:00Z");
        avatar.performance = new VrcxAvatar.Performance();
        avatar.performance.pcRating = "Good"; avatar.performance.androidRating = "Medium"; avatar.performance.iosRating = "Poor";
        avatar.performance.hasImpostor = true; avatar.performance.hasSecurityVariant = Boolean.FALSE;
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            VrcxAvatar copy = get(cache, "q", () -> new VrcxAvatar[]{avatar})[0];
            assertEquals(avatar.authorId, copy.authorId); assertEquals(avatar.authorName, copy.authorName);
            assertEquals(avatar.description, copy.description); assertEquals(avatar.imageUrl, copy.imageUrl);
            assertNull(copy.thumbnailImageUrl); assertEquals(avatar.releaseStatus, copy.releaseStatus);
            assertEquals(avatar.createdAt, copy.createdAt); assertEquals(avatar.updatedAt, copy.updatedAt);
            assertEquals("Good", copy.performance.pcRating); assertEquals("Medium", copy.performance.androidRating);
            assertEquals("Poor", copy.performance.iosRating); assertTrue(copy.performance.hasImpostor);
            assertEquals(Boolean.FALSE, copy.performance.hasSecurityVariant);
        }
    }

    @Test void futureSavedSnapshotIsNotReusedAfterClockRollback()
    {
        Path file = this.directory.resolve("future.json");
        try (ScarletAvatarProviderCache first = this.cache(file)) { get(first, "q", () -> result("future")); }
        this.clock.decrementAndGet();
        try (ScarletAvatarProviderCache second = this.cache(file))
        { assertEquals("refetched", get(second, "q", () -> result("refetched"))[0].name); }
    }

    @Test void memoryClockRollbackInvalidatesPreviouslyFetchedEntries()
    {
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            get(cache, "q", () -> result("old"));
            this.clock.decrementAndGet();
            assertEquals("refetched", get(cache, "q", () -> result("refetched"))[0].name);
        }
    }

    @Test void corruptChecksumLeavesTheOriginalDiskFileUntouchedAndUsesMemory() throws Exception
    {
        Path file = this.directory.resolve("corrupt.json");
        try (ScarletAvatarProviderCache first = this.cache(file)) { get(first, "q", () -> result("old")); }
        JsonObject envelope = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        envelope.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("checksum", "0".repeat(64));
        Files.writeString(file, envelope.toString());
        byte[] corrupt = Files.readAllBytes(file);
        try (ScarletAvatarProviderCache second = this.cache(file))
        {
            assertEquals("fresh", get(second, "q", () -> result("fresh"))[0].name);
            assertEquals("fresh", get(second, "q", ScarletAvatarProviderCacheTest::mustHit)[0].name);
        }
        assertArrayEquals(corrupt, Files.readAllBytes(file));
    }

    @Test void unsupportedFormatAndTruncatedJsonAreRejectedWithoutOverwriting() throws Exception
    {
        for (String contents : Arrays.asList("{\"format\":\"other\",\"version\":1,\"savedAt\":0,\"entries\":[]}",
            "{\"format\":\"scarlet-avatar-provider-cache\",\"version\":99,\"savedAt\":0,\"entries\":[]}", "{\"entries\":["))
        {
            Path file = ScarletPrivateFiles.file(this.directory, "invalid-", ".json");
            Files.writeString(file, contents);
            try (ScarletAvatarProviderCache cache = this.cache(file))
            { assertEquals("network result", get(cache, "q", () -> result("network result"))[0].name); }
            assertEquals(contents, Files.readString(file));
        }
    }

    @Test void oversizedFileIsRejectedBeforeItCanBecomeCacheState() throws Exception
    {
        Path file = ScarletPrivateFiles.file(this.directory, "oversized-", ".json");
        Files.write(file, new byte[4097]);
        try (ScarletAvatarProviderCache cache = this.cache(file, 10, 4096, 512, 50, 4))
        { assertEquals("fresh", get(cache, "q", () -> result("fresh"))[0].name); }
        assertEquals(4097, Files.size(file));
    }

    @Test void resultAndEntryLimitsBoundWhatCanBeRetained()
    {
        AtomicInteger calls = new AtomicInteger();
        try (ScarletAvatarProviderCache cache = this.cache(null, 10, 4096, 128, 2, 4))
        {
            VrcxAvatar[] capped = cache.get(PROVIDER, "text", 9000, "many", ALLOWED,
                () -> new VrcxAvatar[]{avatar("one"), avatar("two"), avatar("three")});
            assertEquals(2, capped.length);
            Supplier<VrcxAvatar[]> oversized = () -> { calls.incrementAndGet(); VrcxAvatar a = avatar("large"); a.description = "d".repeat(500); return new VrcxAvatar[]{a}; };
            assertEquals(500, get(cache, "too large", oversized)[0].description.length());
            get(cache, "too large", oversized);
            assertEquals(2, calls.get(), "Oversized successful responses must remain usable but not cached");
            assertTrue(cache.retainedByteCount() <= 4096);
        }
    }

    @Test void oversizedPublicFieldRemainsAnUncachedSuccessfulResult()
    {
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            VrcxAvatar large = avatar("x".repeat(1025));
            assertEquals(1025, get(cache, "q", () -> new VrcxAvatar[]{large})[0].name.length());
            assertEquals(0, cache.retainedEntryCount());
        }
    }

    @Test void lruAndSerializedByteBudgetsBothEvictBoundedly()
    {
        try (ScarletAvatarProviderCache cache = this.cache(null, 2, 4096, 1024, 50, 4))
        {
            get(cache, "a", () -> result("A")); get(cache, "b", () -> result("B"));
            get(cache, "a", ScarletAvatarProviderCacheTest::mustHit);
            get(cache, "c", () -> result("C"));
            assertEquals(2, cache.retainedEntryCount());
            assertEquals("B-refetched", get(cache, "b", () -> result("B-refetched"))[0].name);
        }
        try (ScarletAvatarProviderCache cache = this.cache(null, 20, 1024, 512, 50, 4))
        {
            for (int i = 0; i < 6; i++)
            {
                VrcxAvatar large = avatar("large"); large.description = "d".repeat(256);
                get(cache, "q" + i, () -> new VrcxAvatar[]{large});
            }
            assertTrue(cache.retainedByteCount() <= 1024);
            assertTrue(cache.retainedEntryCount() < 6);
        }
    }

    @Test void identicalConcurrentMissesShareOneLoaderButDifferentKeysDoNotBlock() throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), followerWaiting = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            Future<VrcxAvatar[]> first = pool.submit(() -> get(cache, "same", () -> {
                calls.incrementAndGet(); started.countDown(); await(release); return result("shared");
            }));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            AtomicInteger guardCalls = new AtomicInteger();
            Future<VrcxAvatar[]> second = pool.submit(() -> cache.get(PROVIDER, "text", 50, "same", () -> {
                if (guardCalls.incrementAndGet() >= 3) followerWaiting.countDown(); return true;
            }, () -> { calls.incrementAndGet(); return result("duplicate"); }));
            assertTrue(followerWaiting.await(3, TimeUnit.SECONDS));
            Future<VrcxAvatar[]> different = pool.submit(() -> get(cache, "different", () -> result("independent")));
            assertEquals("independent", different.get(3, TimeUnit.SECONDS)[0].name);
            release.countDown();
            VrcxAvatar[] a = first.get(3, TimeUnit.SECONDS), b = second.get(3, TimeUnit.SECONDS);
            assertEquals("shared", a[0].name); assertEquals("shared", b[0].name);
            assertNotSame(a[0], b[0]); assertEquals(1, calls.get());
        }
        finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void concurrentFirstLookupWaitsForDiskImportBeforeTestingTheCache() throws Exception
    {
        Path file = this.directory.resolve("initialization.json");
        try (ScarletAvatarProviderCache original = this.cache(file)) { get(original, "q", () -> result("saved")); }
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        AtomicBoolean firstClockRead = new AtomicBoolean(true);
        AtomicInteger loaders = new AtomicInteger();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (ScarletAvatarProviderCache cache = new ScarletAvatarProviderCache(file, () -> {
            if (firstClockRead.getAndSet(false)) { reading.countDown(); await(release); }
            return this.clock.get();
        }, 256, 1024 * 1024, 64 * 1024, 5000, 16, 1000, 100))
        {
            Future<VrcxAvatar[]> first = pool.submit(() -> get(cache, "q", () -> { loaders.incrementAndGet(); return result("unexpected"); }));
            assertTrue(reading.await(3, TimeUnit.SECONDS));
            Future<VrcxAvatar[]> second = pool.submit(() -> {
                secondThread.set(Thread.currentThread()); secondEntered.countDown();
                return get(cache, "q", () -> { loaders.incrementAndGet(); return result("fresh overwrite"); });
            });
            assertTrue(secondEntered.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (secondThread.get().getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
                Thread.yield();
            assertEquals(Thread.State.BLOCKED, secondThread.get().getState(), "Second caller must wait for the current disk import");
            assertEquals(0, loaders.get(), "Do not fetch fresh data while old disk data is still being imported");
            release.countDown();
            assertEquals("saved", first.get(3, TimeUnit.SECONDS)[0].name);
            assertEquals("saved", second.get(3, TimeUnit.SECONDS)[0].name);
            assertEquals(0, loaders.get());
        }
        finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void flightCapacityDoesNotCreateAnUnboundedQueue() throws Exception
    {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ScarletAvatarProviderCache cache = this.cache(null, 10, 4096, 1024, 50, 1))
        {
            Future<VrcxAvatar[]> first = pool.submit(() -> get(cache, "blocked", () -> { started.countDown(); await(release); return result("first"); }));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals(1, cache.pendingCount());
            assertNull(get(cache, "second", ScarletAvatarProviderCacheTest::mustHit));
            release.countDown(); assertEquals("first", first.get(3, TimeUnit.SECONDS)[0].name);
            assertEquals(0, cache.pendingCount());
        }
        finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void revocationDuringLoadPreventsMemoryAndDiskPublication() throws Exception
    {
        AtomicBoolean allowed = new AtomicBoolean(true);
        Path file = this.directory.resolve("revoked.json");
        try (ScarletAvatarProviderCache cache = this.cache(file))
        {
            assertNull(cache.get(PROVIDER, "text", 50, "q", allowed::get,
                () -> { allowed.set(false); return result("must not publish"); }));
            assertEquals(0, cache.retainedEntryCount()); assertFalse(Files.exists(file));
            allowed.set(true);
            assertEquals("fresh", get(cache, "q", () -> result("fresh"))[0].name);
        }
    }

    @Test void deniedCacheHitsAndBrokenPermissionSuppliersNeverReturnMetadata()
    {
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            get(cache, "q", () -> result("saved"));
            assertNull(cache.get(PROVIDER, "text", 50, "q", () -> false, ScarletAvatarProviderCacheTest::mustHit));
            assertNull(cache.get(PROVIDER, "text", 50, "q", () -> { throw new IllegalStateException(); }, ScarletAvatarProviderCacheTest::mustHit));
            assertEquals("saved", get(cache, "q", ScarletAvatarProviderCacheTest::mustHit)[0].name);
        }
    }

    @Test void clearInvalidatesPendingLoadsAndSavedEntriesAcrossRestart() throws Exception
    {
        Path file = this.directory.resolve("clear.json");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ScarletAvatarProviderCache cache = this.cache(file))
        {
            get(cache, "saved", () -> result("old"));
            Future<VrcxAvatar[]> pending = pool.submit(() -> get(cache, "late", () -> { started.countDown(); await(release); return result("late"); }));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(cache.clear()); assertEquals(0, cache.retainedEntryCount());
            release.countDown(); assertNull(pending.get(3, TimeUnit.SECONDS));
            assertEquals(0, cache.retainedEntryCount());
        }
        finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
        try (ScarletAvatarProviderCache restarted = this.cache(file))
        {
            assertEquals("new saved", get(restarted, "saved", () -> result("new saved"))[0].name);
            assertEquals("new late", get(restarted, "late", () -> result("new late"))[0].name);
        }
    }

    @Test void clearBeforeFirstGetDoesNotImportOldDiskEntries()
    {
        Path file = this.directory.resolve("clear-before-read.json");
        try (ScarletAvatarProviderCache first = this.cache(file)) { get(first, "q", () -> result("old")); }
        try (ScarletAvatarProviderCache second = this.cache(file)) { assertTrue(second.clear()); }
        try (ScarletAvatarProviderCache third = this.cache(file))
        { assertEquals("new", get(third, "q", () -> result("new"))[0].name); }
    }

    @Test void closeDoesNotWaitForNetworkAndLateResultsCannotWrite() throws Exception
    {
        Path file = this.directory.resolve("closed.json");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        ScarletAvatarProviderCache cache = this.cache(file);
        try
        {
            Future<VrcxAvatar[]> pending = pool.submit(() -> get(cache, "q", () -> { started.countDown(); await(release); return result("late"); }));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            pool.submit(cache::close).get(3, TimeUnit.SECONDS);
            release.countDown(); assertNull(pending.get(3, TimeUnit.SECONDS));
            assertFalse(Files.exists(file)); assertEquals(0, cache.pendingCount());
            assertNull(get(cache, "q", ScarletAvatarProviderCacheTest::mustHit));
            assertFalse(cache.clear());
        }
        finally { release.countDown(); cache.close(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void missingStorageParentFallsBackWithoutLosingSuccessfulResults()
    {
        Path file = this.directory.resolve("missing-parent").resolve("cache.json");
        try (ScarletAvatarProviderCache cache = this.cache(file))
        {
            assertEquals("works", get(cache, "q", () -> result("works"))[0].name);
            assertEquals("works", get(cache, "q", ScarletAvatarProviderCacheTest::mustHit)[0].name);
            assertFalse(cache.clear(), "Caller must know old disk data could not be safely cleared");
            assertFalse(Files.exists(file.getParent()));
        }
    }

    @Test void aFilesystemWithoutPrivatePermissionsUsesMemoryOnly() throws Exception
    {
        Path archive = this.directory.resolve("unsupported.zip");
        try (java.nio.file.FileSystem filesystem = java.nio.file.FileSystems.newFileSystem(
                java.net.URI.create("jar:" + archive.toUri()), Collections.singletonMap("create", "true")))
        {
            Path file = filesystem.getPath("/cache.json");
            try (ScarletAvatarProviderCache cache = this.cache(file))
            {
                assertEquals("works", get(cache, "q", () -> result("works"))[0].name);
                assertEquals("works", get(cache, "q", ScarletAvatarProviderCacheTest::mustHit)[0].name);
                assertFalse(Files.exists(file));
            }
        }
    }

    @Test void symbolicLinkCacheLeafIsNeverReadOverwrittenOrDeleted() throws Exception
    {
        Path outside = this.directory.resolve("outside.json");
        Files.writeString(outside, "synthetic outside sentinel");
        Path link = this.directory.resolve("alias.json");
        this.symbolicLink(link, outside);
        try (ScarletAvatarProviderCache cache = this.cache(link))
        {
            assertEquals("memory", get(cache, "q", () -> result("memory"))[0].name);
            assertFalse(cache.clear());
        }
        assertTrue(Files.isSymbolicLink(link)); assertEquals("synthetic outside sentinel", Files.readString(outside));
    }

    @Test void symbolicLinkParentCannotRedirectCachePublication() throws Exception
    {
        Path outside = Files.createDirectory(this.directory.resolve("outside"));
        Path alias = this.directory.resolve("parent-alias");
        this.symbolicLink(alias, outside);
        try (ScarletAvatarProviderCache cache = this.cache(alias.resolve("cache.json")))
        { assertEquals("memory", get(cache, "q", () -> result("memory"))[0].name); }
        assertFalse(Files.exists(outside.resolve("cache.json")));
    }

    @Test void replacingParentAfterFirstSaveDisablesFurtherDiskWrites() throws Exception
    {
        Path parent = Files.createDirectory(this.directory.resolve("original-parent"));
        Path file = parent.resolve("cache.json");
        try (ScarletAvatarProviderCache cache = this.cache(file))
        {
            get(cache, "q1", () -> result("old parent"));
            Path moved = this.directory.resolve("moved-parent");
            Files.move(parent, moved); Files.createDirectory(parent);
            assertEquals("memory", get(cache, "q2", () -> result("memory"))[0].name);
            assertFalse(Files.exists(file)); assertTrue(Files.exists(moved.resolve("cache.json")));
        }
    }

    @Test void invalidCountsAndOversizedKeysNeverInvokeTheLoader()
    {
        try (ScarletAvatarProviderCache cache = this.cache(null))
        {
            for (int invalid : new int[]{0, -1})
                assertNull(cache.get(PROVIDER, "text", invalid, "q", ALLOWED, ScarletAvatarProviderCacheTest::mustHit));
            assertNull(cache.get(PROVIDER, "text", 50, "x".repeat(4097), ALLOWED, ScarletAvatarProviderCacheTest::mustHit));
            assertNull(cache.get(null, "text", 50, "q", ALLOWED, ScarletAvatarProviderCacheTest::mustHit));
        }
    }

    private void symbolicLink(Path link, Path target)
    {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable)
        { assumeTrue(false, "This test host cannot create symbolic links"); }
    }

    private static void await(CountDownLatch latch)
    {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Synthetic loader release timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}

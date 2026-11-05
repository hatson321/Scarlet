package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

class ScarletJsonCacheFreshnessTest
{
    @TempDir Path directory;

    private ScarletJsonCache<String> strings()
    { return new ScarletJsonCache<>(directory.toFile(), "users", TypeToken.get(String.class)); }

    @Test void warmEntryHonorsFreshnessWithoutDiscardingReusableData()
    {
        ScarletJsonCache<String> cache = strings();
        cache.put("user", "cached");
        long saved = cache.getEntry("user").modified();
        assertEquals("cached", cache.get("user", saved));
        assertNull(cache.get("user", saved + 1));
        assertNull(cache.get("user", ScarletJsonCache.ALWAYS_FETCH));
        assertEquals("cached", cache.get("user", ScarletJsonCache.ALWAYS_PREFER_CACHED));
    }

    @Test void freshDiskEntryDoesNotInvokeAnUnneededPopulator()
    {
        ScarletJsonCache<String> cache = strings();
        cache.put("user", "disk");
        long saved = cache.getEntry("user").modified();
        AtomicInteger calls = new AtomicInteger();
        assertEquals("disk", strings().get("user", saved, key -> { calls.incrementAndGet(); return "network"; }));
        assertEquals(0, calls.get());
    }

    @Test void failedRefreshDoesNotPassOffStaleDataAsFresh()
    {
        ScarletJsonCache<String> cache = strings();
        cache.put("user", "old");
        assertNull(cache.get("user", ScarletJsonCache.ALWAYS_FETCH, key -> null));
        assertNull(cache.get("user", ScarletJsonCache.ALWAYS_FETCH, key -> { throw new IOException("synthetic failure"); }));
        assertEquals("old", cache.get("user"));
        assertEquals("new", cache.get("user", ScarletJsonCache.ALWAYS_FETCH, key -> "new"));
        assertEquals("new", cache.get("user"));
    }

    @Test void actualApiGetterRefetchesAfterItsMemoryEntryExpires() throws Exception
    {
        try (ScarletFetchCoalescingTest.Fixture f = new ScarletFetchCoalescingTest.Fixture(directory))
        {
            f.release.countDown();
            assertEquals("User 1", f.vrc.getUser("synthetic").getDisplayName());
            assertEquals("User 2", f.vrc.getUser("synthetic").getDisplayName());
            assertEquals(2, f.calls.get());
            assertEquals("User 2", f.vrc.getUser("synthetic", ScarletJsonCache.ALWAYS_PREFER_CACHED).getDisplayName());
            assertEquals(2, f.calls.get());
        }
    }

    @Test void newMissDiscardsExpiredUnvisitedMisses() throws Exception
    {
        AtomicLong clock = new AtomicLong(1_000_000);
        ScarletJsonCache<String> cache = new ScarletJsonCache<>(directory.toFile(), "users", TypeToken.get(String.class), clock::get);
        cache.add404("old_one"); cache.add404("old_two");
        clock.addAndGet(ScarletJsonCache.NEGATIVE_TTL_MILLIS);
        cache.add404("current");
        var saved = JsonParser.parseString(Files.readString(directory.resolve("users/known_404s.json"))).getAsJsonObject();
        assertEquals(java.util.Set.of("current"), saved.keySet());
        assertTrue(cache.is404("current"));
        clock.set(1);
        cache.add404("after_clock_reset");
        saved = JsonParser.parseString(Files.readString(directory.resolve("users/known_404s.json"))).getAsJsonObject();
        assertEquals(java.util.Set.of("after_clock_reset"), saved.keySet());
    }

    @JsonAdapter(BlockingValueAdapter.class)
    static final class BlockingValue
    {
        final String text;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release;
        BlockingValue(String text, boolean block) { this.text = text; this.release = new CountDownLatch(block ? 1 : 0); }
    }

    public static final class BlockingValueAdapter extends TypeAdapter<BlockingValue>
    {
        @Override public void write(JsonWriter writer, BlockingValue value) throws IOException
        {
            writer.beginObject().name("text").value(value.text); writer.flush();
            value.entered.countDown();
            try { if (!value.release.await(3, TimeUnit.SECONDS)) throw new IOException("Synthetic writer timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            writer.endObject();
        }
        @Override public BlockingValue read(JsonReader reader) throws IOException
        { reader.beginObject(); reader.nextName(); String value = reader.nextString(); reader.endObject(); return new BlockingValue(value, false); }
    }

    @Test void overlappingSameEntryWritesCannotTruncateEachOther() throws Exception
    {
        ScarletJsonCache<BlockingValue> cache = new ScarletJsonCache<>(directory.toFile(), "users", TypeToken.get(BlockingValue.class));
        BlockingValue first = new BlockingValue("A".repeat(16000), true), second = new BlockingValue("B", false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> one = pool.submit(() -> cache.put("user", first));
            assertTrue(first.entered.await(2, TimeUnit.SECONDS));
            CountDownLatch attempted = new CountDownLatch(1);
            Future<?> two = pool.submit(() -> { attempted.countDown(); cache.put("user", second); });
            assertTrue(attempted.await(2, TimeUnit.SECONDS));
            assertFalse(second.entered.await(200, TimeUnit.MILLISECONDS), "A second truncating writer entered before the first completed");
            first.release.countDown();
            one.get(2, TimeUnit.SECONDS); two.get(2, TimeUnit.SECONDS);
            assertEquals("B", JsonParser.parseString(Files.readString(directory.resolve("users/user.json"))).getAsJsonObject().get("text").getAsString());
            assertSame(second, cache.get("user"));
        }
        finally { first.release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }
}

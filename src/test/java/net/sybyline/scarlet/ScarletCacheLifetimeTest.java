package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletCacheLifetimeTest
{
    @TempDir Path temp;
    @Test void negativeEntriesExpireAcrossRestartsAndFreshRequestsBypassThem() throws Exception
    {
        AtomicLong clock = new AtomicLong(1_000_000);
        var cache = new ScarletJsonCache<String>(temp.toFile(), "users", TypeToken.get(String.class), clock::get);
        assertTrue(cache.add404("usr_test"));
        assertTrue(cache.is404("usr_test"));
        assertFalse(cache.is404("usr_test", ScarletJsonCache.ALWAYS_FETCH));
        assertFalse(cache.is404("usr_test", clock.get() + 1));
        var restored = new ScarletJsonCache<String>(temp.toFile(), "users", TypeToken.get(String.class), clock::get);
        assertTrue(restored.is404("usr_test"));
        clock.addAndGet(ScarletJsonCache.NEGATIVE_TTL_MILLIS);
        assertFalse(restored.is404("usr_test"));
        assertFalse(new ScarletJsonCache<String>(temp.toFile(), "users", TypeToken.get(String.class), clock::get).is404("usr_test"));
        cache.add404("usr_test"); cache.put("usr_test", "recovered");
        assertFalse(cache.is404("usr_test"));
        cache.add404("usr_other"); cache.clear404s();
        assertFalse(new ScarletJsonCache<String>(temp.toFile(), "users", TypeToken.get(String.class), clock::get).is404("usr_other"));
        Files.writeString(temp.resolve("users/known_404s.json"), "[\"usr_legacy\"]");
        assertFalse(new ScarletJsonCache<String>(temp.toFile(), "users", TypeToken.get(String.class), clock::get).is404("usr_legacy"));
    }
    @Test void cleanupUsesRecentActivityAndLiveProcessesNotRememberedLogNames()
    {
        var idle = new ScarletTailerHealth.Clients(Map.of());
        long now = 1_000_000;
        assertFalse(ScarletCacheCleanup.activeForCleanup(now, 1, idle));
        assertFalse(ScarletCacheCleanup.activeForCleanup(now, 0, idle));
        assertTrue(ScarletCacheCleanup.activeForCleanup(now, now - 1, idle));
        assertTrue(ScarletCacheCleanup.activeForCleanup(now, now + 1, idle));
        assertTrue(ScarletCacheCleanup.activeForCleanup(now, 1, null));
        assertTrue(ScarletCacheCleanup.activeForCleanup(now, 1,
            new ScarletTailerHealth.Clients(Map.of("synthetic", Instant.ofEpochMilli(1)))));
    }
}

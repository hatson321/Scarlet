package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.reflect.TypeToken;

class ScarletJsonCachePathTest
{
    @TempDir Path directory;

    private ScarletJsonCache<String> cache(String kind)
    {
        return new ScarletJsonCache<>(this.directory.resolve("caches").toFile(), kind, TypeToken.get(String.class));
    }

    @Test void traversalAbsoluteDeviceAndMetadataKeysCannotReadOrWrite() throws Exception
    {
        Path root = Files.createDirectory(this.directory.resolve("caches"));
        Files.createDirectory(root.resolve("usr"));
        Path outside = root.resolve("canary.json");
        Files.writeString(outside, "\"private-synthetic-canary\"");
        ScarletJsonCache<String> cache = this.cache("usr");
        String[] rejected = {"../canary", "..\\canary", "usr_a/../../canary", "/canary", "C:\\canary",
            "C:canary", "usr_a:stream", "", ".", "..", "NUL", "con", "COM1", "LPT9.txt", "known_404s",
            "KNOWN_404S", "usr_%2f..", "x".repeat(201)};
        for (String id : rejected)
        {
            AtomicBoolean populated = new AtomicBoolean();
            assertNull(cache.get(id, Long.MIN_VALUE, key -> { populated.set(true); return "bad"; }), id);
            cache.put(id, "overwritten");
            assertFalse(populated.get(), id);
            assertFalse(cache.add404(id), id);
            assertTrue(cache.is404(id), id);
            assertNull(cache.getEntry(id), id);
        }
        assertEquals("\"private-synthetic-canary\"", Files.readString(outside));
        try (java.util.stream.Stream<Path> files = Files.list(root.resolve("usr")))
        { assertEquals(0, files.count()); }
    }

    @Test void validResourceCompositeKeysAndNegativeCacheSurviveReload() throws Exception
    {
        ScarletJsonCache<String> cache = this.cache("file.analysis");
        String composite = "file_00000000-0000-0000-0000-000000000001#2#security";
        cache.put(composite, "synthetic");
        assertEquals("synthetic", this.cache("file.analysis").get(composite));
        assertTrue(cache.add404("file_missing"));
        assertTrue(this.cache("file.analysis").is404("file_missing"));
        assertTrue(cache.remove404("file_missing"));
        assertFalse(this.cache("file.analysis").is404("file_missing"));
        assertThrows(IllegalArgumentException.class, () -> this.cache("../outside"));
    }

    @Test void fileAndDirectoryAliasesAreNotReadOrOverwritten() throws Exception
    {
        Path root = Files.createDirectory(this.directory.resolve("caches"));
        Path usr = Files.createDirectory(root.resolve("usr"));
        Path outside = this.directory.resolve("outside.json");
        Files.writeString(outside, "\"outside\"");
        this.link(usr.resolve("usr_alias.json"), outside);
        ScarletJsonCache<String> cache = this.cache("usr");
        assertNull(cache.get("usr_alias"));
        cache.put("usr_alias", "changed");
        assertEquals("\"outside\"", Files.readString(outside));

        Path other = Files.createDirectory(this.directory.resolve("other"));
        Files.writeString(other.resolve("usr_sentinel.json"), "\"sentinel\"");
        this.link(root.resolve("alias"), other);
        ScarletJsonCache<String> aliased = this.cache("alias");
        assertNull(aliased.get("usr_sentinel"));
        aliased.put("usr_sentinel", "changed");
        assertEquals("\"sentinel\"", Files.readString(other.resolve("usr_sentinel.json")));
    }

    private void link(Path link, Path target) throws IOException
    {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException unavailable)
        { assumeTrue(false, "This test host cannot create symbolic links"); }
    }
}

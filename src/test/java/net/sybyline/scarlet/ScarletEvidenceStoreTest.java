package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletEvidenceStoreTest
{
    @TempDir Path temp;
    final byte[] bytes = new byte[]{1, 2, 3, 4};
    String key() throws Exception { return ScarletEvidenceStore.key("synthetic attachment"); }
    InputStream input() { return new ByteArrayInputStream(bytes); }

    @Test void metadataFailureRetainsPublishedFileAndRetryDoesNotDownloadOrDuplicate() throws Exception
    {
        AtomicInteger downloads = new AtomicInteger(), records = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ScarletEvidenceStore.save(temp, key(), "one.bin",
            () -> { downloads.incrementAndGet(); return input(); }, () -> true,
            path -> { throw new IllegalStateException("synthetic metadata failure"); }));
        assertArrayEquals(bytes, Files.readAllBytes(temp.resolve("one.bin")));
        assertEquals("one.bin", ScarletEvidenceStore.save(temp, key(), "two.bin",
            () -> { downloads.incrementAndGet(); return input(); }, () -> true, path -> records.incrementAndGet()));
        assertEquals(1, downloads.get()); assertEquals(1, records.get());
        assertFalse(Files.exists(temp.resolve("two.bin")));
        assertEquals("one.bin", ScarletEvidenceStore.save(temp, key(), "three.bin", this::input, () -> true, path -> {}));
        try (var files = Files.list(temp.resolve(".scarlet-evidence")))
        { assertEquals(1, files.count(), "Only the durable receipt remains after metadata was saved"); }
    }

    @Test void partialDownloadsAndRevokedAuthorizationNeverPublish() throws Exception
    {
        InputStream broken = new InputStream() {
            int count;
            @Override public int read() throws IOException { if (count++ < 2) return 1; throw new IOException("interrupted download"); }
        };
        assertThrows(IOException.class, () -> ScarletEvidenceStore.save(temp, key(), "one.bin", () -> broken, () -> true, path -> fail()));
        assertFalse(Files.exists(temp.resolve("one.bin")));
        AtomicBoolean allowed = new AtomicBoolean(true);
        assertThrows(IOException.class, () -> ScarletEvidenceStore.save(temp, key(), "one.bin",
            () -> { allowed.set(false); return input(); }, allowed::get, path -> fail()));
        assertFalse(Files.exists(temp.resolve("one.bin")));
        try (var files = Files.list(temp.resolve(".scarlet-evidence"))) { assertEquals(0, files.count()); }
    }

    @Test void concurrentDestinationAndOversizeAttachmentsFailWithoutOverwrite() throws Exception
    {
        Path dest = temp.resolve("one.bin");
        assertThrows(IOException.class, () -> ScarletEvidenceStore.save(temp, key(), "one.bin",
            () -> { Files.write(dest, new byte[]{9}); return input(); }, () -> true, path -> fail()));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(dest));
        InputStream huge = new InputStream() {
            long left = ScarletEvidenceStore.MAX_BYTES + 1;
            @Override public int read() { return left-- > 0 ? 0 : -1; }
            @Override public int read(byte[] data, int off, int len) {
                if (left <= 0) return -1; int count = (int)Math.min(left, len); left -= count; return count;
            }
        };
        assertThrows(IOException.class, () -> ScarletEvidenceStore.save(temp, ScarletEvidenceStore.key("large"), "large.bin", () -> huge, () -> true, path -> fail()));
        assertFalse(Files.exists(temp.resolve("large.bin")));
    }

    @Test void parentTraversalAndChangedPublishedFileAreRejected() throws Exception
    {
        assertThrows(IOException.class, () -> ScarletEvidenceStore.save(temp, key(), "../escape.bin", this::input, () -> true, path -> fail()));
        ScarletEvidenceStore.save(temp, key(), "one.bin", this::input, () -> true, path -> {});
        Files.write(temp.resolve("one.bin"), new byte[]{8});
        assertThrows(IOException.class, () -> ScarletEvidenceStore.save(temp, key(), "one.bin", this::input, () -> true, path -> fail()));
    }
}

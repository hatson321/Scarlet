package net.sybyline.scarlet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Exclusive evidence publication with a durable, retryable metadata receipt. */
final class ScarletEvidenceStore
{
    static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final Object[] LOCKS = new Object[32];
    static { for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object(); }
    static final class Receipt
    {
        String key, relative, stage, digest;
        boolean published;
    }

    static String save(Path root, String key, String relative, Callable<InputStream> download,
        BooleanSupplier authorized, Consumer<String> record) throws Exception
    {
        if (key == null || !key.matches("[a-f0-9]{64}")) throw new IOException("Invalid evidence receipt key");
        synchronized (LOCKS[(key.hashCode() & Integer.MAX_VALUE) % LOCKS.length])
        {
            root = root.toAbsolutePath().normalize();
            directory(root);
            Path receipts = root.resolve(".scarlet-evidence");
            directory(receipts); ScarletPrivateFiles.protect(receipts);
            Path marker = receipts.resolve(key + ".json");
            Receipt receipt;
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
            {
                receipt = Scarlet.GSON.fromJson(new String(ScarletPrivateFiles.readSecret(marker), java.nio.charset.StandardCharsets.UTF_8), Receipt.class);
                if (receipt == null || !key.equals(receipt.key) || receipt.stage == null
                    || !receipt.stage.matches("evidence-[A-Za-z0-9.-]+") || receipt.digest == null
                    || !receipt.digest.matches("[a-f0-9]{64}")) throw new IOException("Invalid evidence receipt");
            }
            else
            {
                Path destination = destination(root, relative);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.FileAlreadyExistsException(destination.toString());
                requireAuthorized(authorized);
                Path stage = ScarletPrivateFiles.file(receipts, "evidence-", ".data");
                boolean retained = false;
                try
                {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long bytes = 0, started = System.nanoTime();
                    try (InputStream input = download.call(); java.nio.channels.FileChannel output = java.nio.channels.FileChannel.open(stage, StandardOpenOption.WRITE))
                    {
                        byte[] buffer = new byte[16384]; int count;
                        while ((count = input.read(buffer)) != -1)
                        {
                            bytes += count;
                            if (bytes > MAX_BYTES || System.nanoTime() - started > TimeUnit.SECONDS.toNanos(60))
                                throw new IOException("Evidence exceeds the size or download time limit");
                            digest.update(buffer, 0, count);
                            java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(buffer, 0, count);
                            while (data.hasRemaining()) output.write(data);
                        }
                        output.force(true);
                    }
                    requireAuthorized(authorized);
                    receipt = new Receipt(); receipt.key = key; receipt.relative = root.relativize(destination).toString();
                    receipt.stage = stage.getFileName().toString(); receipt.digest = hex(digest.digest());
                    ScarletPrivateFiles.writeSecret(marker, Scarlet.GSON.toJson(receipt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    retained = true;
                }
                finally { if (!retained) Files.deleteIfExists(stage); }
            }
            Path destination = destination(root, receipt.relative), stage = receipts.resolve(receipt.stage);
            requireAuthorized(authorized);
            if (!receipt.published)
            {
                regular(stage);
                if (!receipt.digest.equals(hash(stage))) throw new IOException("Staged evidence changed");
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                {
                    regular(destination);
                    if (!Files.isSameFile(stage, destination)) throw new IOException("Evidence destination belongs to another file");
                }
                else Files.createLink(destination, stage); // Atomic CREATE_NEW; never replace an existing file.
                receipt.published = true;
                Path updated = ScarletPrivateFiles.file(receipts, "receipt-", ".tmp");
                try
                {
                    Files.write(updated, Scarlet.GSON.toJson(receipt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(updated, StandardOpenOption.WRITE)) { channel.force(true); }
                    Files.move(updated, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                finally { Files.deleteIfExists(updated); }
            }
            regular(destination);
            if (!receipt.digest.equals(hash(destination))) throw new IOException("Published evidence changed; review required");
            requireAuthorized(authorized);
            record.accept(receipt.relative); // On failure retain both the file and receipt for an idempotent retry.
            Files.deleteIfExists(stage);
            return receipt.relative;
        }
    }

    static String key(String value) throws Exception
    { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }

    private static String hash(Path path) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))
        { byte[] buffer = new byte[16384]; int count; long bytes = 0; while ((count = in.read(buffer)) != -1) {
            if ((bytes += count) > MAX_BYTES) throw new IOException("Evidence too large"); digest.update(buffer, 0, count);
        } }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes)
    { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString(); }
    private static void requireAuthorized(BooleanSupplier gate) throws IOException
    { if (!gate.getAsBoolean()) throw new IOException("Evidence authorization or destination changed"); }
    private static void regular(Path path) throws IOException
    {
        ScarletPrivateFiles.requireExactParent(path);
        java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Evidence must be an exact regular file");
    }
    private static void directory(Path path) throws IOException
    {
        ScarletPrivateFiles.requireExactParent(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath())) throw new IOException("Evidence directory must not be an alias");
    }
    private static Path destination(Path root, String relative) throws IOException
    {
        if (relative == null || relative.length() > 2048 || Path.of(relative).isAbsolute()) throw new IOException("Invalid evidence path");
        Path dest = root.resolve(relative).normalize();
        if (!dest.startsWith(root) || dest.equals(root) || dest.startsWith(root.resolve(".scarlet-evidence"))) throw new IOException("Evidence outside its destination");
        Path current = root;
        for (Path component : root.relativize(dest.getParent())) { current = current.resolve(component); directory(current); }
        return dest;
    }
}

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Fetch and verify the exact, unmodified upstream SDK source used by this build. */
public final class BootstrapVrchatSdk {
    private static final String ARCHIVE = "vrchatapi-java-1.23.0-source.zip";
    private static final String PREFIX = "vrchatapi-java-1.23.0/";
    private static final String SHA256 = "34e70212f1b0069bc9e406005d59fc7d245e24ad1027a5cfbde4410d6e8b9dd7";
    private static final URI URL = URI.create("https://github.com/vrchatapi/vrchatapi-java/archive/refs/tags/1.23.0.zip");
    private static final long MAX_ARCHIVE = 16L * 1024 * 1024;
    private static final long MAX_EXPANDED = 128L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21)
            throw new IllegalStateException("Use JDK 21 to prepare the release dependencies");
        if (args.length > 1 || (args.length == 1 && !"--offline".equals(args[0])))
            throw new IllegalArgumentException("Run from the Scarlet source root: java scripts/BootstrapVrchatSdk.java [--offline]");
        Path root = Path.of("").toRealPath();
        if (!Files.isRegularFile(root.resolve("pom.xml")) || !Files.isDirectory(root.resolve("libdave-maven")))
            throw new IllegalArgumentException("Run from the Scarlet source root");
        Path cache = root.resolve(".build-cache/vrchat-sdk");
        safeDirectories(root, cache);
        Path archive = cache.resolve(ARCHIVE);
        if (!Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            if (args.length != 0)
                throw new IOException("Offline SDK archive missing: " + archive);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URL)
                .timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (response.statusCode() != 200) throw new IOException("SDK download HTTP " + response.statusCode());
                byte[] bytes = stream.readNBytes((int) MAX_ARCHIVE + 1);
                if (bytes.length > MAX_ARCHIVE) throw new IOException("SDK archive exceeds size limit");
                verifyArchive(bytes);
                Files.write(archive, bytes, StandardOpenOption.CREATE_NEW);
            }
        }
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.size(archive) > MAX_ARCHIVE)
            throw new IOException("SDK archive is not a bounded regular file");
        verifyArchive(Files.readAllBytes(archive));
        Path source = cache.resolve("source");
        safeDirectories(root, source);
        Set<Path> entries = new HashSet<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (!name.startsWith(PREFIX) || name.contains("\\"))
                    throw new IOException("Unexpected SDK archive entry");
                Path destination = source.resolve(name).normalize();
                if (!destination.startsWith(source) || !entries.add(destination))
                    throw new IOException("Unsafe or duplicate SDK archive entry");
                if (entry.isDirectory()) {
                    safeDirectories(root, destination);
                    continue;
                }
                byte[] bytes = zip.readNBytes(16 * 1024 * 1024 + 1);
                total += bytes.length;
                if (bytes.length > 16 * 1024 * 1024 || total > MAX_EXPANDED)
                    throw new IOException("SDK expanded content exceeds size limit");
                safeDirectories(root, destination.getParent());
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(destination) != bytes.length
                            || !MessageDigest.isEqual(digest(bytes), digest(Files.readAllBytes(destination))))
                        throw new IOException("Cached SDK source differs from the pinned archive: " + destination);
                } else Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
            }
        }
        if (!Files.isRegularFile(source.resolve(PREFIX + "pom.xml")))
            throw new IOException("Pinned SDK source has no pom.xml");
        Path buildOutput = source.resolve(PREFIX + "target");
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("SDK cache contains a directory alias");
                // Maven clean removes this generated directory before every dependency build.
                if (path.equals(buildOutput)) return FileVisitResult.SKIP_SUBTREE;
                if (!path.equals(source) && !entries.contains(path)) throw new IOException("Extra cached SDK source directory: " + path);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                        || !entries.contains(path)) throw new IOException("Extra or aliased cached SDK source file: " + path);
                return FileVisitResult.CONTINUE;
            }
        });
        System.out.println("Verified upstream VRChat SDK 1.23.0 source SHA-256: " + SHA256);
        System.out.println("Upstream commit: a5fef92cb430a1dd6bacd4133b4046d7d35d2b4e");
        System.out.println("Build the unmodified upstream POM at " + source.resolve(PREFIX + "pom.xml"));
    }

    private static void verifyArchive(byte[] bytes) throws Exception {
        if (!SHA256.equals(HexFormat.of().formatHex(digest(bytes))))
            throw new IOException("SDK source SHA-256 mismatch; refusing to use it");
    }

    private static byte[] digest(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static void safeDirectories(Path root, Path directory) throws IOException {
        if (!directory.startsWith(root)) throw new IOException("Path is outside the source checkout");
        Path current = root;
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("Dependency cache must contain only real directories: " + current);
        }
    }
}

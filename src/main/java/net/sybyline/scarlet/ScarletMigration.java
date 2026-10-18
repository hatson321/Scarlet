package net.sybyline.scarlet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.prefs.BackingStoreException;
import java.util.prefs.InvalidPreferencesFormatException;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.security.MessageDigest;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

import net.sybyline.scarlet.util.FileBackups;
import net.sybyline.scarlet.util.ProcLock;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves an entire Scarlet install between machines or operating systems as a single
 * portable bundle. Driven from the desktop UI (Settings -&gt; Backup &amp; Migration).
 *
 * <p>Two things hold a Scarlet identity: the data folder ({@link Scarlet#dir}, which
 * contains {@code global-prefs.key}, the Discord bot config and all metadata) and the
 * encrypted credentials Java stores in {@link Preferences} (the Windows registry under
 * {@code HKCU\Software\JavaSoft\Prefs\net\sybyline\scarlet}, or {@code ~/.java/.userPrefs}
 * on Linux/macOS). The credential encryption key is the {@code global-prefs.key} file in
 * the data folder rather than anything machine-bound, so once both pieces move together
 * the data decrypts on any OS.
 *
 * <p>{@link #exportBundle(File)} writes a {@code .zip} containing the {@link Preferences}
 * subtree exported as portable XML plus a copy of the data folder.
 * {@link #importBundle(File)} restores the data folder into this machine's (OS-correct)
 * {@link Scarlet#dir} and imports the preferences XML; Scarlet must then be restarted to
 * pick up the imported credentials.
 *
 * <p>The bundle contains the bot token, VRChat login, 2FA secret and session cookie in a
 * directly decryptable form. Treat the file like a password: keep it on trusted media and
 * delete it after importing.
 */
public final class ScarletMigration
{
    static final Logger LOG = LoggerFactory.getLogger("ScarletMigration");

    static final String PREFS_ENTRY = "preferences.xml",
                        DATA_PREFIX = "data/",
                        GLOBAL_PREFS_KEY = "global-prefs.key",
                        SECURE_PREFS_FILE = "secure-prefs.dat",
                        SECURE_PREFS_BACKUP_FILE = "secure-prefs.dat.bak",
                        LEGACY_COOKIE_FILE = "store.bin",
                        MANIFEST_ENTRY = "scarlet-migration.txt";
    static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // Optional PIN encryption — same primitives Scarlet uses for stored credentials
    // (PBKDF2WithHmacSHA256 -> AES-256-GCM). The GCM tag also authenticates the bundle,
    // so a PIN-protected bundle cannot be read or silently tampered with.
    static final byte[] ENC_MAGIC = { 'S', 'C', 'R', 'L', 'T', 'B', 'N', 'D' };
    static final int ENC_VERSION = 1, ENC_SALT_LEN = 16, ENC_IV_LEN = 12, ENC_TAG_BITS = 128, ENC_KEY_BITS = 256, ENC_PBKDF2_ITERS = 100_000;
    static final SecureRandom RAND = new SecureRandom();

    // Limits guarding against malicious/zip-bomb bundles on import.
    static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;      // 512 MiB per file
    static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024; // 4 GiB total
    static final int  MAX_ENTRIES = 500_000;
    static final int  MAX_PREFS_XML_BYTES = 64 * 1024 * 1024;    // 64 MiB
    static final int  MAX_BACKUPS_KEPT = 8;

    /** Message of the {@link IOException} thrown when a {@link Progress} callback cancels an export. */
    public static final String CANCELLED_MESSAGE = "Export cancelled by user";

    /**
     * Receives live export progress, Windows-Explorer style. Called from the export
     * thread: on each phase change, before each file, and repeatedly while large files
     * stream. {@code totalFiles}/{@code totalBytes} are 0 when unknown. Return
     * {@code false} to cancel: the export stops, the partial bundle is deleted, and
     * {@link #exportBundle} throws an {@link IOException} with {@link #CANCELLED_MESSAGE}.
     */
    public interface Progress
    {
        boolean progress(String phase, String detail, int filesDone, int totalFiles, long bytesDone, long totalBytes);
    }

    /** Mutable per-export progress accounting shared down the zip recursion. */
    static final class ProgressState
    {
        ProgressState(Progress callback, int totalFiles, long totalBytes)
        {
            this.callback = callback;
            this.totalFiles = totalFiles;
            this.totalBytes = totalBytes;
        }
        final Progress callback;
        final int totalFiles;
        final long totalBytes;
        int filesDone = 0;
        long bytesDone = 0L;
        String phase = "Preparing", detail = "";
        void update(String phase, String detail) throws IOException
        {
            this.phase = phase;
            this.detail = detail;
            this.report();
        }
        void addBytes(int n) throws IOException
        {
            this.bytesDone += n;
            this.report();
        }
        void fileDone() throws IOException
        {
            this.filesDone++;
            this.report();
        }
        void report() throws IOException
        {
            if (this.callback != null && !this.callback.progress(this.phase, this.detail, this.filesDone, this.totalFiles, this.bytesDone, this.totalBytes))
                throw new IOException(CANCELLED_MESSAGE);
        }
    }

    /**
     * Writes a portable migration bundle (data folder + encrypted credentials) to the
     * given file. Returns a short human-readable summary; throws on failure.
     */
    public static String exportBundle(File out) throws IOException
    {
        return exportBundle(out, null);
    }

    /**
     * Writes a portable migration bundle. When {@code pin} is non-empty the whole bundle is
     * encrypted with AES-256-GCM using a PBKDF2-derived key; the GCM tag also authenticates
     * it, so without the PIN it can neither be read nor silently altered.
     */
    public static String exportBundle(File out, char[] pin) throws IOException
    {
        return exportBundle(out, pin, null);
    }

    /**
     * As {@link #exportBundle(File, char[])}, additionally reporting live progress to
     * {@code progress} (may be null). If the callback cancels, the partial bundle is
     * deleted and an {@link IOException} with {@link #CANCELLED_MESSAGE} is thrown.
     */
    public static String exportBundle(File out, char[] pin, Progress progress) throws IOException
    {
        try (ExportSession session = openExportSession())
        {
            return session.exportBundle(out, pin, progress);
        }
    }

    /** Exclusive for the complete offline cleanup/export/verification/optional-wipe workflow. */
    public static ExportSession openExportSession() throws IOException
    {
        return openExportSession(Scarlet.dir, ProcLock.preferencesLockDirectory(),
            ScarletMigration::snapshotPreferences, ScarletMigration::wipeLocalInstall);
    }

    @FunctionalInterface interface PreferenceExport { byte[] snapshot() throws Exception; }
    @FunctionalInterface interface WipeOperation { String wipe() throws IOException; }

    /** Synthetic callers inject every preference and wipe operation; no real user state is touched. */
    static ExportSession openExportSession(File dataDir, File preferenceLockDirectory,
            PreferenceExport preferences, WipeOperation wipe) throws IOException
    {
        File canonical = dataDir.getCanonicalFile();
        ProcLock.Lease data = ProcLock.acquireDataDirectory(canonical);
        try
        {
            ProcLock.Lease prefs = ProcLock.acquirePreferences(preferenceLockDirectory, false);
            return new ExportSession(canonical, data, prefs, preferences, wipe);
        }
        catch (IOException | RuntimeException failure)
        {
            try { data.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public static final class ExportSession implements AutoCloseable
    {
        private final File dataDir;
        private final ProcLock.Lease dataLease, preferenceLease;
        private final PreferenceExport preferences;
        private final WipeOperation wipe;
        private boolean closed;
        private File published, verified;
        private byte[] publishedDigest, verifiedDigest;

        private ExportSession(File dataDir, ProcLock.Lease dataLease, ProcLock.Lease preferenceLease,
                PreferenceExport preferences, WipeOperation wipe)
        {
            this.dataDir = dataDir; this.dataLease = dataLease; this.preferenceLease = preferenceLease;
            this.preferences = preferences; this.wipe = wipe;
        }

        public File dataDirectory() { return this.dataDir; }

        public synchronized String exportBundle(File out, char[] pin, Progress progress) throws IOException
        {
            return this.exportBundle(out, pin, progress, true);
        }

        /** Claims a new destination without replacing an existing or concurrently created file. */
        public synchronized String exportNewBundle(File out, char[] pin, Progress progress) throws IOException
        {
            return this.exportBundle(out, pin, progress, false);
        }

        private String exportBundle(File out, char[] pin, Progress progress, boolean replaceExisting) throws IOException
        {
            this.requireOpen();
            this.published = this.verified = null;
            this.publishedDigest = this.verifiedDigest = null;
            File destination = checkedExportDestination(out, this.dataDir);
            ExportWriteResult result = exportBundleAtomic(destination, pin, progress, this.dataDir, this.preferences, replaceExisting);
            this.publishedDigest = result.digest;
            this.published = destination;
            return result.summary;
        }

        public synchronized void verifyBundle(File out, char[] pin, Progress progress) throws IOException
        {
            this.requireOpen();
            this.verified = null; this.verifiedDigest = null;
            File candidate = checkedExportDestination(out, this.dataDir);
            if (!candidate.equals(this.published) || !MessageDigest.isEqual(this.publishedDigest, fileDigest(candidate)))
                throw new IOException("Export changed or was not created by this session; local deletion is disabled.");
            verifyBundleContents(candidate, pin, progress);
            byte[] after = fileDigest(candidate);
            if (!MessageDigest.isEqual(this.publishedDigest, after))
                throw new IOException("Export changed during verification; local deletion is disabled.");
            this.verified = candidate; this.verifiedDigest = after;
        }

        public synchronized String wipeLocalInstall() throws IOException
        {
            this.requireOpen();
            if (this.verified == null || !MessageDigest.isEqual(this.verifiedDigest, fileDigest(this.verified)))
                throw new IOException("A complete unchanged export must be verified before local deletion.");
            // Verification authorizes a single attempt, not an indefinitely reusable deletion capability.
            this.verified = null; this.verifiedDigest = null;
            return this.wipe.wipe();
        }

        private void requireOpen() throws IOException
        {
            if (this.closed) throw new IOException("Offline export session is closed");
        }

        @Override public synchronized void close() throws IOException
        {
            if (this.closed) return;
            this.closed = true;
            try { this.preferenceLease.close(); }
            finally { this.dataLease.close(); }
        }
    }

    private static byte[] snapshotPreferences() throws IOException
    {
        Preferences prefs = Preferences.userNodeForPackage(Scarlet.class);
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        try { prefs.sync(); prefs.exportSubtree(xml); }
        catch (Exception ex) { throw new IOException("Failed to export the Scarlet Preferences subtree", ex); }
        return xml.toByteArray();
    }

    private static File checkedExportDestination(File out, File dataDir) throws IOException
    {
        if (out == null || dataDir == null) throw new IOException("Export destination or data directory is unavailable");
        if (Files.isSymbolicLink(out.toPath())) throw new IOException("Export destination must not be a symbolic link");
        File target = out.getCanonicalFile(), root = dataDir.getCanonicalFile();
        if (target.toPath().startsWith(root.toPath()))
            throw new IOException("Choose an export destination outside the Scarlet data directory");
        if (target.exists() && !target.isFile()) throw new IOException("Export destination is not a regular file");
        return target;
    }

    /** Caller already holds both exclusive leases; used also by the pre-import safety backup. */
    private static final class ExportWriteResult
    {
        final String summary;
        final byte[] digest;
        ExportWriteResult(String summary, byte[] digest) { this.summary = summary; this.digest = digest; }
    }

    private static ExportWriteResult exportBundleAtomic(File out, char[] pin, Progress progress, File dataDir,
            PreferenceExport preferences) throws IOException
    {
        return exportBundleAtomic(out, pin, progress, dataDir, preferences, true);
    }

    private static ExportWriteResult exportBundleAtomic(File out, char[] pin, Progress progress, File dataDir,
            PreferenceExport preferences, boolean replaceExisting) throws IOException
    {
        return exportBundleAtomic(out, pin, progress, dataDir, preferences, replaceExisting, false);
    }

    private static ExportWriteResult exportBundleAtomic(File out, char[] pin, Progress progress, File dataDir,
            PreferenceExport preferences, boolean replaceExisting, boolean allowMissingDataDirectory) throws IOException
    {
        File destination = checkedExportDestination(out, dataDir);
        if (!dataDir.isDirectory() && (!allowMissingDataDirectory || dataDir.exists()))
            throw new IOException("Scarlet data directory does not exist or is not a directory");
        Files.createDirectories(destination.toPath().getParent());
        Path encrypted = null;
        boolean published = false;
        ScarletPrivateFiles.Workspace workspace = ScarletPrivateFiles.workspace(".scarlet-export-");
        try
        {
            Path plain = ScarletPrivateFiles.file(workspace.path, "plain-", ".zip.tmp");
            byte[] xml;
            try { xml = normalizeScarletOnlyPrefs(preferences.snapshot()); }
            catch (Exception ex) { throw new IOException("Could not capture validated Scarlet Preferences", ex); }
            String summary = writeBundle(plain.toFile(), progress, dataDir, xml);
            Path ready = plain;
            if (pin != null && pin.length > 0)
            {
                encrypted = Files.createTempFile(destination.toPath().getParent(), ".scarlet-export-", ".enc.tmp");
                encryptFile(plain.toFile(), encrypted.toFile(), pin, progress);
                ready = encrypted;
                // Do not publish an encrypted export while leaving its unencrypted staging copy.
                Files.delete(plain);
            }
            if (progress != null && !progress.progress("Publishing", destination.getName(), 0, 0, 0, 0))
                throw new IOException(CANCELLED_MESSAGE);
            final Path complete = ready;
            byte[] digest = fileDigest(complete.toFile());
            if (replaceExisting)
            {
                // The existing destination is replaced only after the complete ZIP/GCM output exists.
                // A failed callback, sync or atomic replacement leaves its previous bytes intact.
                if (pin != null && pin.length > 0)
                    FileBackups.writeBytes(destination, 0, output -> Files.copy(complete, output));
                else publishPrivateReplacement(complete, destination.toPath());
            }
            else
            {
                publishNewExport(complete, destination.toPath(), pin == null || pin.length == 0);
            }
            published = true;
            return new ExportWriteResult(summary, digest);
        }
        finally
        {
            IOException cleanupFailure = null;
            try { workspace.close(); } catch (IOException ex) { cleanupFailure = ex; }
            try { if (encrypted != null) Files.deleteIfExists(encrypted); }
            catch (IOException ex) { if (cleanupFailure == null) cleanupFailure = ex; else cleanupFailure.addSuppressed(ex); }
            if (cleanupFailure != null)
            {
                if (!published) throw cleanupFailure;
                // Publication has succeeded; cleanup must not misreport it as a failed replacement.
                System.err.println("Export completed, but a temporary export file could not be cleaned up: " + cleanupFailure);
            }
        }
    }

    static void publishNewExport(Path complete, Path destination) throws IOException
    {
        publishNewExport(complete, destination, true);
    }

    private static void publishPrivateReplacement(Path complete, Path destination) throws IOException
    {
        Files.createDirectories(destination.getParent());
        // The adjacent workspace preserves same-filesystem atomic publication, including when
        // the private OS staging directory and the selected destination are on different drives.
        ScarletPrivateFiles.Workspace publication = ScarletPrivateFiles.directory(destination.getParent(), ".scarlet-publish-");
        boolean published = false;
        try
        {
            Path staged = ScarletPrivateFiles.file(publication.path, "plain-", ".tmp");
            try (FileOutputStream output = new FileOutputStream(staged.toFile()))
            { Files.copy(complete, output); output.flush(); output.getFD().sync(); }
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            // Match ordinary atomic saves: persist the destination rename where directories
            // support fsync. Windows does not expose directory fsync through this API.
            try (FileChannel directory = FileChannel.open(destination.getParent(), StandardOpenOption.READ))
            { directory.force(true); }
            catch (Exception unsupportedDirectorySync) { }
        }
        finally
        {
            try { publication.close(); }
            catch (IOException cleanup)
            {
                if (!published) throw cleanup;
                System.err.println("Private file publication completed, but its empty temporary directory could not be removed: " + cleanup);
            }
        }
    }

    private static void publishNewExport(Path complete, Path destination, boolean privateContent) throws IOException
    {
        if (!Files.isRegularFile(complete)) throw new IOException("Complete export staging file is unavailable");
        // CREATE_NEW is atomic even on removable filesystems without links/atomic rename.
        // The new file is visible while copied: callers must wait for successful verification.
        boolean claimed = false;
        java.nio.file.attribute.FileAttribute<?>[] attributes = privateContent
            ? ScarletPrivateFiles.fileAttributes(complete, destination.getParent()) : new java.nio.file.attribute.FileAttribute<?>[0];
        try (FileChannel output = FileChannel.open(destination,
            EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes))
        {
            claimed = true;
            if (privateContent) ScarletPrivateFiles.protect(destination);
            try (InputStream input = Files.newInputStream(complete))
            {
                byte[] bytes = new byte[8192];
                for (int count; (count = input.read(bytes)) >= 0;)
                {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count);
                    while (buffer.hasRemaining()) output.write(buffer);
                }
            }
            output.force(true);
        }
        catch (IOException failure)
        {
            if (!claimed) throw failure;
            // Portable Java cannot prove path ownership after concurrent replacement on every
            // supported filesystem. Never risk deleting another process's replacement file.
            throw new IOException("Export publication failed. A partial output may remain at " + destination
                + "; do not use it. Local data was not removed. Choose a different new filename to retry.", failure);
        }
    }

    private static byte[] fileDigest(File file) throws IOException
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(new FileInputStream(file)))
            {
                byte[] buffer = new byte[8192];
                for (int n; (n = input.read(buffer)) >= 0;) if (n > 0) digest.update(buffer, 0, n);
            }
            return digest.digest();
        }
        catch (GeneralSecurityException ex) { throw new IOException("SHA-256 unavailable", ex); }
    }

    static void verifyBundleContents(File bundle, char[] pin, Progress progress) throws IOException
    {
        try (PreparedBundle prepared = prepareBundle(bundle, pin, progress))
        {
            boolean preferencesSeen = false, manifestSeen = false, credentialKeySeen = false;
            long total = 0L;
            Set<String> names = new HashSet<>();
            try (ZipFile zip = new ZipFile(prepared.file))
            {
                if (zip.size() == 0 || zip.size() > MAX_ENTRIES) throw new IOException("Invalid export entry count");
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements())
                {
                    ZipEntry entry = entries.nextElement();
                    String raw = entry.getName();
                    String name = normalizeEntryName(entry.isDirectory() && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
                    if (name == null || !names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Unsafe or duplicate export entry");
                    if (progress != null && !progress.progress("Verifying", name, names.size() - 1, zip.size(), total, 0))
                        throw new IOException(CANCELLED_MESSAGE);
                    CRC32 crc = new CRC32(); long size = 0L;
                    ByteArrayOutputStream xml = PREFS_ENTRY.equals(name) ? new ByteArrayOutputStream() : null;
                    try (InputStream input = zip.getInputStream(entry))
                    {
                        byte[] buffer = new byte[8192];
                        for (int n; (n = input.read(buffer)) >= 0;)
                        {
                            if (n == 0) continue;
                            size += n; total += n;
                            if (size > (xml != null ? MAX_PREFS_XML_BYTES : MAX_ENTRY_BYTES) || total > MAX_TOTAL_BYTES)
                                throw new IOException("Export exceeds verification size limits");
                            crc.update(buffer, 0, n);
                            if (xml != null) xml.write(buffer, 0, n);
                            if (progress != null && !progress.progress("Verifying", name, names.size() - 1, zip.size(), total, 0))
                                throw new IOException(CANCELLED_MESSAGE);
                        }
                    }
                    if (entry.getCrc() != crc.getValue() || entry.getSize() != size)
                        throw new IOException("Export failed complete ZIP CRC/size verification");
                    if (xml != null) { normalizeScarletOnlyPrefs(xml.toByteArray()); preferencesSeen = true; }
                    if (MANIFEST_ENTRY.equals(name)) manifestSeen = true;
                    if ((DATA_PREFIX + GLOBAL_PREFS_KEY).equals(name) && size > 0) credentialKeySeen = true;
                }
            }
            if (!preferencesSeen || !manifestSeen || !credentialKeySeen)
                throw new IOException("Export is missing its required preferences, manifest, or nonempty credential key; local deletion is disabled.");
        }
    }

    static final class PreparedBundle implements AutoCloseable
    {
        final File file;
        private final ScarletPrivateFiles.Workspace workspace;
        private PreparedBundle(File file, ScarletPrivateFiles.Workspace workspace)
        { this.file = file; this.workspace = workspace; }
        @Override public void close() throws IOException { if (this.workspace != null) this.workspace.close(); }
    }

    /** Decrypted credentials never enter the directory containing a shared encrypted bundle. */
    static PreparedBundle prepareBundle(File bundle, char[] pin, Progress progress) throws IOException
    {
        if (!isEncryptedBundle(bundle)) return new PreparedBundle(bundle, null);
        if (pin == null || pin.length == 0) throw new IOException("The bundle PIN is required for decryption");
        ScarletPrivateFiles.Workspace workspace = ScarletPrivateFiles.workspace(".scarlet-decrypt-");
        try
        {
            Path plain = ScarletPrivateFiles.file(workspace.path, "plain-", ".zip.tmp");
            decryptFile(bundle, plain.toFile(), pin, progress);
            return new PreparedBundle(plain.toFile(), workspace);
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { workspace.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static String writeBundle(File outAbs, Progress progress, File dataDir, byte[] prefsXml) throws IOException
    {
        // Pre-scan the data folder so progress is reported against real totals
        // (file counts and bytes), Windows-Explorer style.
        File dataRoot = null;
        File skip = outAbs.getCanonicalFile();
        long[] measured = { 0L, 0L }; // { file count, total bytes }
        if (dataDir != null && dataDir.isDirectory())
        {
            dataRoot = dataDir.getCanonicalFile();
            measureDir(dataRoot, dataRoot, skip, measured);
        }
        ProgressState state = new ProgressState(progress, (int) measured[0], measured[1]);
        state.update("Preparing", "");

        int[] count = {0};
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outAbs))))
        {
            zip.putNextEntry(new ZipEntry(PREFS_ENTRY));
            zip.write(prefsXml);
            zip.closeEntry();

            StringBuilder manifest = new StringBuilder();
            manifest.append("Scarlet migration bundle\n")
                    .append("preferences node: /net/sybyline/scarlet\n")
                    .append("data folder: ").append(dataDir == null ? "(none)" : dataDir.getAbsolutePath()).append('\n')
                    .append("exported: ").append(Instant.now()).append('\n');
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            if (dataRoot != null)
            {
                zipDir(zip, dataRoot, dataRoot, DATA_PREFIX, skip, count, state);
            }
        }
        return count[0] + " data file(s) and your encrypted credentials";
    }

    /**
     * Restores a migration bundle: data folder into this machine's {@link Scarlet#dir} and
     * the credentials into Java {@link Preferences}. Before writing anything, Scarlet creates
     * an automatic backup bundle of this machine's current data and credentials. Scarlet must
     * be restarted afterward.
     */
    public static ImportResult importBundle(File in) throws IOException
    {
        return importBundle(in, ImportOptions.all(), null);
    }

    public static ImportResult importBundle(File in, ImportOptions options) throws IOException
    {
        return importBundle(in, options, null);
    }

    public static ImportResult importBundle(File in, ImportOptions options, char[] pin) throws IOException
    {
        try (net.sybyline.scarlet.util.ProcLock.Lease ignored = net.sybyline.scarlet.util.ProcLock.acquireDataDirectory(Scarlet.dir);
             net.sybyline.scarlet.util.ProcLock.Lease preferences = net.sybyline.scarlet.util.ProcLock.acquirePreferences(
                 net.sybyline.scarlet.util.ProcLock.preferencesLockDirectory(), false))
        {
            return importBundleOffline(in, options, pin);
        }
    }

    private static ImportResult importBundleOffline(File in, ImportOptions options, char[] pin) throws IOException
    {
        ImportOptions importOptions = options == null ? ImportOptions.all() : options;
        if (!importOptions.importDataFiles && !importOptions.importCredentials)
            throw new IOException("Nothing selected to import.");
        if (in == null || !in.isFile())
            throw new FileNotFoundException("Bundle not found: " + in);

        // A PIN-protected bundle is decrypted (and authenticated) to a temp file first, so a
        // wrong PIN or tampered bundle fails before we create a backup or touch local data.
        try (PreparedBundle prepared = prepareBundle(in, pin, null))
        {
            File backup = createPreImportBackup();
            try
            {
                String summary = importBundleContents(prepared.file, importOptions);
                return new ImportResult(summary, backup);
            }
            catch (IOException ex)
            {
                throw new IOException("Import failed after creating a pre-import backup at "
                    + backup.getAbsolutePath() + ": " + ex.getMessage(), ex);
            }
            catch (RuntimeException ex)
            {
                throw new IOException("Import failed after creating a pre-import backup at "
                    + backup.getAbsolutePath(), ex);
            }
        }
    }

    /**
     * Removes this machine's Scarlet identity: clears the Scarlet Java {@link Preferences}
     * node (credentials) and recursively deletes the data folder. Intended for the
     * "I'm moving to another computer" flow, run after the bundle has been exported and
     * verified. Symbolic links are deleted without following them. Returns a short summary.
     */
    public static String wipeLocalInstall() throws IOException
    {
        // Validate before changing Preferences, not after credentials have already been removed.
        Path dataRoot = checkedWipeRoot(Scarlet.dir);
        int[] removed = { 0 };
        int preferenceNodesRemoved = 0;
        List<String> failures = new java.util.ArrayList<>();
        Preferences root = Preferences.userRoot();
        for (String path : preferenceNodePathsForWipe())
        {
            try
            {
                if (root.nodeExists(path))
                {
                    root.node(path).removeNode();
                    preferenceNodesRemoved++;
                }
            }
            catch (Exception ex)
            {
                failures.add("could not remove Preferences node " + path + ": " + ex.getMessage());
                LOG.warn("Could not clear Scarlet Java Preferences node {} during wipe", path, ex);
            }
        }
        try
        {
            root.flush();
            for (String path : preferenceNodePathsForWipe())
                if (root.nodeExists(path))
                    failures.add("Preferences node still exists after wipe: " + path);
        }
        catch (Exception ex)
        {
            failures.add("could not flush or verify Java Preferences: " + ex.getMessage());
            LOG.warn("Could not flush or verify Scarlet Java Preferences during wipe", ex);
        }

        try { deleteDataDirectory(dataRoot.toFile(), removed, failures); }
        catch (IOException ex) { failures.add("could not safely remove the data directory: " + ex.getMessage()); }

        if (!failures.isEmpty())
        {
            String detail = String.join("; ", failures);
            LOG.error("Scarlet local wipe was incomplete: {}", detail);
            throw new IOException("Scarlet local wipe was incomplete after removing " + removed[0]
                + " filesystem item(s) and " + preferenceNodesRemoved + " Preferences node(s): " + detail);
        }
        LOG.info("Wiped local Scarlet install: {} filesystem item(s) and {} Preferences node(s) removed",
            removed[0], preferenceNodesRemoved);
        return removed[0] + " filesystem item(s) and " + preferenceNodesRemoved + " Preferences node(s) removed";
    }

    static String[] preferenceNodePathsForWipe()
    {
        String current = "/" + Scarlet.class.getPackage().getName().replace('.', '/');
        // Do not initialize ScarletSettings here: its static encryption fallback can create files.
        return new String[] { current, "/net/" + interimPreferencesOwnerName() + "/scarlet" };
    }

    private static Path checkedWipeRoot(File dataDir) throws IOException
    {
        return checkedWipeRoot(dataDir, configuredDirectory(System.getProperty("user.home")),
            System.getenv(), configuredDirectory(System.getProperty("java.io.tmpdir")));
    }

    /** Injected directory identities keep protection tests independent of real user folders. */
    static Path checkedWipeRoot(File dataDir, Path userHome, Map<String, String> environment, Path tempDirectory) throws IOException
    {
        if (dataDir == null) throw new IOException("Scarlet data directory is unavailable");
        Path root = dataDir.toPath().toAbsolutePath().normalize();
        if (root.getParent() == null) throw new IOException("Refusing to remove a filesystem root");
        if (userHome == null) throw new IOException("Cannot verify the user-home boundary; local removal is disabled");
        Path home = userHome.toAbsolutePath().normalize();
        // A misplaced SCARLET_HOME must not turn an explicit move-out into deleting a home
        // or one of its ancestors. Dedicated subdirectories remain valid.
        if (home.startsWith(root)) throw new IOException("Refusing to remove a user home or its parent directory");
        for (String directory : new String[] { "Desktop", "Documents", "Downloads", "Pictures", "Videos", "Music", "Public",
                "AppData", "AppData/Local", "AppData/Roaming", "AppData/LocalLow",
                ".config", ".local", ".local/share", ".local/state", ".cache", ".java", ".java/.userPrefs" })
            if (root.equals(home.resolve(directory))) throw new IOException("Refusing to remove a shared user directory");
        if (tempDirectory != null && root.equals(tempDirectory.toAbsolutePath().normalize()))
            throw new IOException("Refusing to remove the shared temporary directory");

        Path filesystemRoot = root.getRoot();
        for (String directory : new String[] { "home", "root", "usr", "usr/local", "usr/share", "usr/local/share",
                "opt", "var", "var/lib", "var/log", "var/cache", "var/tmp", "etc", "bin", "sbin", "lib", "lib64",
                "tmp", "run", "mnt", "media", "srv", "boot", "dev", "proc", "sys", "Users", "Documents and Settings",
                "Windows", "Windows/System32", "Windows/SysWOW64", "Program Files", "Program Files (x86)", "ProgramData" })
            if (root.equals(filesystemRoot.resolve(directory))) throw new IOException("Refusing to remove a common system directory");
        // Other accounts' immediate home directories are just as broad as this account's.
        for (String directory : new String[] { "home", "Users", "Documents and Settings" })
            if (root.getParent().equals(filesystemRoot.resolve(directory)))
                throw new IOException("Refusing to remove a user-profile directory");

        Set<String> sharedEnvironmentDirectories = new HashSet<>(Arrays.asList("userprofile", "home", "allusersprofile",
            "appdata", "localappdata", "programdata", "programfiles", "programfiles(x86)", "programw6432",
            "commonprogramfiles", "commonprogramfiles(x86)", "systemroot", "windir", "public",
            "xdg_config_home", "xdg_data_home", "xdg_cache_home", "xdg_state_home", "xdg_runtime_dir", "tmp", "temp", "tmpdir"));
        for (Map.Entry<String, String> entry : environment.entrySet())
        {
            if (!sharedEnvironmentDirectories.contains(entry.getKey().toLowerCase(Locale.ROOT))) continue;
            Path shared = configuredDirectory(entry.getValue());
            if (shared != null && root.equals(shared.toAbsolutePath().normalize()))
                throw new IOException("Refusing to remove a shared system or user directory");
        }
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.equals(root.toRealPath())))
            throw new IOException("Refusing to remove a data root that is not an exact directory or is a link/junction alias");
        return root;
    }

    private static Path configuredDirectory(String value)
    {
        if (value == null || value.isBlank()) return null;
        try { return Path.of(value); }
        catch (java.nio.file.InvalidPathException invalid) { return null; }
    }

    /** Exact-root, no-follow deletion; injected synthetic directories need no Preferences access. */
    static void deleteDataDirectory(File dataDir, int[] removed, List<String> failures) throws IOException
    {
        Path root = checkedWipeRoot(dataDir);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>()
        {
            private boolean exactPath(Path path) throws IOException
            {
                Path absolute = path.toAbsolutePath().normalize();
                return absolute.startsWith(root) && absolute.equals(path.toRealPath());
            }

            private void remove(Path path)
            {
                try { if (Files.deleteIfExists(path)) removed[0]++; }
                catch (IOException ex) { failures.add("could not delete " + path + ": " + ex.getMessage()); }
            }

            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs)
            {
                try
                {
                    // NOFOLLOW_LINKS alone is insufficient for every Windows reparse directory.
                    // Refuse aliases (including internal cycles) before enumerating their children.
                    if (this.exactPath(path)) return FileVisitResult.CONTINUE;
                    failures.add("refused linked or junction directory " + path);
                }
                catch (IOException ex) { failures.add("could not verify directory " + path + ": " + ex.getMessage()); }
                return FileVisitResult.SKIP_SUBTREE;
            }

            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
            {
                try
                {
                    if (attrs.isSymbolicLink() || this.exactPath(path)) this.remove(path);
                    else failures.add("refused aliased filesystem entry " + path);
                }
                catch (IOException ex) { failures.add("could not verify file " + path + ": " + ex.getMessage()); }
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path path, IOException failure)
            {
                failures.add("could not enumerate " + path + ": " + failure.getMessage());
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path path, IOException failure)
            {
                if (failure != null) failures.add("could not enumerate directory " + path + ": " + failure.getMessage());
                else
                {
                    try
                    {
                        if (this.exactPath(path)) this.remove(path);
                        else failures.add("directory changed to a link or junction during removal: " + path);
                    }
                    catch (IOException ex) { failures.add("could not verify directory " + path + ": " + ex.getMessage()); }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String importBundleContents(File in, ImportOptions options) throws IOException
    {
        File dataDir = Scarlet.dir;
        if (dataDir == null)
            throw new IOException("Scarlet data directory is unavailable; cannot import.");
        return importBundleContents(in, options, dataDir, new PreferenceTransaction()
        {
            @Override public byte[] backup() throws Exception
            {
                ByteArrayOutputStream snapshot = new ByteArrayOutputStream();
                Preferences.userNodeForPackage(Scarlet.class).exportSubtree(snapshot);
                return snapshot.toByteArray();
            }
            @Override public void replace(byte[] xml) throws Exception
            {
                replaceScarletPreferences(xml);
            }
        });
    }

    /** A seam for hermetic failure tests; production uses only Scarlet's Preferences subtree. */
    interface PreferenceTransaction
    {
        byte[] backup() throws Exception;
        void replace(byte[] xml) throws Exception;
    }

    static String importBundleContents(File in, ImportOptions options, File dataDir,
                                      PreferenceTransaction preferences) throws IOException
    {
        if (in == null || !in.isFile()) throw new FileNotFoundException("Bundle not found: " + in);
        // ZipInputStream alone accepts archives with a missing/truncated central directory.
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(in))
        { if (archive.size() == 0 || archive.size() > MAX_ENTRIES) throw new IOException("Invalid bundle entry count"); }
        File root = dataDir.getCanonicalFile();
        Files.createDirectories(root.toPath());
        ScarletPrivateFiles.Workspace privateWorkspace = ScarletPrivateFiles.workspace(".scarlet-import-");
        Path workspace = privateWorkspace.path;
        Map<String, Path> staged = new LinkedHashMap<>();
        Map<String, Path> originals = new LinkedHashMap<>();
        List<String> applied = new ArrayList<>();
        byte[] incomingPrefs = null, originalPrefs = null;
        boolean prefsAttempted = false, preserveRecovery = false;
        try
        {
            Set<String> seen = new HashSet<>();
            int count = 0;
            long[] total = { 0L };
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(in))))
            {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null)
                {
                    if (++count > MAX_ENTRIES) throw new IOException("Bundle contains too many entries");
                    String raw = entry.getName();
                    String name = normalizeEntryName(entry.isDirectory() && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
                    if (name == null || !seen.add(name.toLowerCase(Locale.ROOT)))
                        throw new IOException("Unsafe or duplicate bundle destination");
                    String relative = name.startsWith(DATA_PREFIX) ? name.substring(DATA_PREFIX.length()) : null;
                    // This active config has one portable spelling; case aliases must not evade
                    // credential preservation on Windows or create a second latent config on Linux.
                    if (relative != null && "discord_bot.json".equalsIgnoreCase(relative)) relative = "discord_bot.json";
                    if (relative != null && "settings.json".equalsIgnoreCase(relative)) relative = "settings.json";
                    if (!entry.isDirectory() && PREFS_ENTRY.equals(name) && options.importCredentials)
                    {
                        ByteArrayOutputStream xml = new ByteArrayOutputStream();
                        copyCapped(zip, xml, MAX_PREFS_XML_BYTES, total, MAX_TOTAL_BYTES);
                        incomingPrefs = normalizeScarletOnlyPrefs(xml.toByteArray());
                    }
                    else if (!entry.isDirectory() && relative != null && shouldImportDataEntry(relative, options))
                    {
                        checkedDestination(root, relative);
                        Path stagedFile = ScarletPrivateFiles.file(workspace, "staged-", ".dat");
                        try (OutputStream output = Files.newOutputStream(stagedFile))
                        { copyCapped(zip, output, MAX_ENTRY_BYTES, total, MAX_TOTAL_BYTES); }
                        staged.put(relative, stagedFile);
                    }
                    else
                    {
                        // Even unselected/unknown entries consume the same decompression budget.
                        copyCapped(zip, OutputStream.nullOutputStream(), MAX_ENTRY_BYTES, total, MAX_TOTAL_BYTES);
                    }
                    zip.closeEntry(); // Checks ZIP CRC before any live mutation.
                }
            }
            if (options.importCredentials && (incomingPrefs == null || !staged.containsKey(GLOBAL_PREFS_KEY)
                    || Files.size(staged.get(GLOBAL_PREFS_KEY)) == 0))
                throw new IOException("Credential imports require validated preferences.xml and nonempty data/global-prefs.key");
            if (options.importDataFiles && staged.containsKey("discord_bot.json"))
            {
                Path config = staged.get("discord_bot.json");
                JsonObject incoming = readConfigurationObject(config);
                if (!options.keepChannels) scrubDiscordRouting(incoming);
                if (!options.importCredentials)
                {
                    // Old builds accepted a plaintext bot token from this ordinary data file.
                    // Remove incoming credentials and preserve the existing local values verbatim.
                    for (String key : new ArrayList<>(incoming.keySet()))
                        if (isDiscordCredentialField(key)) incoming.remove(key);
                    Path local = checkedDestination(root, "discord_bot.json");
                    if (Files.exists(local))
                    {
                        JsonObject current = readConfigurationObject(local);
                        for (Map.Entry<String, JsonElement> entry : current.entrySet())
                            if (isDiscordCredentialField(entry.getKey())) incoming.add(entry.getKey(), entry.getValue().deepCopy());
                    }
                }
                FileBackups.write(config.toFile(), 0, writer -> new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(incoming, writer));
                ScarletPrivateFiles.protect(config);
            }
            if (options.importDataFiles && !options.importCredentials && staged.containsKey("settings.json"))
            {
                Path config = staged.get("settings.json");
                JsonObject incoming = readConfigurationObject(config);
                // Legacy login migrates these plaintext values into secure storage at startup.
                // Data-only imports must neither adopt source values nor erase local values.
                for (String key : new ArrayList<>(incoming.keySet()))
                    if (isLegacyLoginField(key)) incoming.remove(key);
                Path local = checkedDestination(root, "settings.json");
                if (Files.exists(local))
                {
                    for (Map.Entry<String, JsonElement> entry : readConfigurationObject(local).entrySet())
                        if (isLegacyLoginField(entry.getKey())) incoming.add(entry.getKey(), entry.getValue().deepCopy());
                }
                FileBackups.write(config.toFile(), 0, writer -> new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(incoming, writer));
                ScarletPrivateFiles.protect(config);
            }
            // Capture every replacement before applying the first file.
            for (String relative : staged.keySet())
            {
                Path destination = checkedDestination(root, relative);
                Path original = null;
                if (Files.exists(destination))
                {
                    if (!Files.isRegularFile(destination)) throw new IOException("Import destination is not a regular file");
                    original = ScarletPrivateFiles.file(workspace, "original-", ".dat");
                    // Keep the already protected file rather than replacing its ACL with a
                    // newly copied directory entry. The enclosing workspace is private too.
                    try (OutputStream output = Files.newOutputStream(original, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
                    { Files.copy(destination, output); }
                    ScarletPrivateFiles.protect(original);
                    if (!sameBytes(original, destination)) throw new IOException("Failed to verify staged original data");
                }
                originals.put(relative, original);
            }
            if (options.importCredentials)
            {
                originalPrefs = preferences.backup();
                byte[] recoveryPrefs = originalPrefs;
                FileBackups.writeBytes(workspace.resolve("original-preferences.xml").toFile(), 0, output -> output.write(recoveryPrefs));
                ScarletPrivateFiles.protect(workspace.resolve("original-preferences.xml"));
            }
            for (Map.Entry<String, Path> replacement : staged.entrySet())
            {
                Path destination = checkedDestination(root, replacement.getKey());
                applied.add(replacement.getKey());
                publishPrivateReplacement(replacement.getValue(), destination);
                if (!sameBytes(replacement.getValue(), destination)) throw new IOException("Imported file failed read-back verification");
            }
            if (options.importCredentials)
            {
                prefsAttempted = true;
                preferences.replace(incomingPrefs);
            }
            int credentialFiles = (int) staged.keySet().stream().filter(ScarletMigration::isCredentialDataEntry).count();
            return importSummary(options, staged.size(), credentialFiles, options.importCredentials);
        }
        catch (Exception failure)
        {
            IOException result = new IOException("Import failed; restoring the original configuration", failure);
            java.util.Collections.reverse(applied);
            for (String relative : applied)
            {
                try
                {
                    Path destination = checkedDestination(root, relative), original = originals.get(relative);
                    if (original == null) Files.deleteIfExists(destination);
                    else
                    {
                        publishPrivateReplacement(original, destination);
                        if (!sameBytes(original, destination)) throw new IOException("Restored file failed verification");
                    }
                }
                catch (Exception rollback) { result.addSuppressed(rollback); preserveRecovery = true; }
            }
            if (prefsAttempted)
            {
                try { preferences.replace(originalPrefs); }
                catch (Exception rollback) { result.addSuppressed(rollback); preserveRecovery = true; }
            }
            if (preserveRecovery)
                throw new IOException("Import failed and rollback could not complete. Recovery files retained at " + workspace, result);
            throw result;
        }
        finally
        {
            if (preserveRecovery) privateWorkspace.retain();
            try { privateWorkspace.close(); }
            catch (IOException cleanup) { LOG.warn("Could not remove private temporary import staging files", cleanup); }
        }
    }

    private static boolean sameBytes(Path first, Path second) throws IOException
    {
        if (Files.size(first) != Files.size(second)) return false;
        try (InputStream left = Files.newInputStream(first); InputStream right = Files.newInputStream(second))
        {
            byte[] a = new byte[8192], b = new byte[8192];
            int count;
            while ((count = left.read(a)) >= 0)
                if (right.readNBytes(b, 0, count) != count || !Arrays.equals(Arrays.copyOf(a, count), Arrays.copyOf(b, count))) return false;
            return right.read() == -1;
        }
    }

    private static Path checkedDestination(File root, String relative) throws IOException
    {
        String name = normalizeEntryName(relative);
        if (name == null) throw new IOException("Unsafe import destination");
        Path destination = root.toPath().resolve(name).normalize();
        if (!destination.startsWith(root.toPath()) || destination.equals(root.toPath())) throw new IOException("Unsafe import destination");
        for (Path current = destination; current != null && !current.equals(root.toPath()); current = current.getParent())
        {
            if (Files.isSymbolicLink(current)) throw new IOException("Import does not replace or traverse symbolic links");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS))
            {
                Path real = current.toRealPath();
                // Directory junctions are not necessarily symbolic links on Windows. Resolve
                // every existing ancestor before staging/applying, including internal aliases.
                if (!real.startsWith(root.toPath()) || !real.equals(current.toAbsolutePath().normalize()))
                    throw new IOException("Import does not replace or traverse linked or junction path aliases");
            }
        }
        return destination;
    }

    private static void replaceScarletPreferences(byte[] prefsXml) throws IOException, BackingStoreException, InvalidPreferencesFormatException
    {
        // Refuse any preferences XML that targets nodes outside Scarlet's own subtree, so a
        // malicious bundle can't inject/overwrite arbitrary Java Preferences for this user.
        Preferences imported = replaceScarletPreferences(prefsXml, Preferences.userRoot());
        if (imported.getBoolean(ScarletSettings.INTERIM_NAMESPACE_RECONCILED_KEY, false))
        {
            String importedGlobalPassword = ScarletSettings.currentImportedGlobalPassword();
            if (importedGlobalPassword == null
                || !ScarletSettings.ensureImportedInterimSeedIsRewrapped(imported, importedGlobalPassword))
                throw new IOException("Imported interim-namespace credentials could not be verified and re-wrapped for the restored Preferences namespace.");
        }
        Preferences.userRoot().flush();
    }

    // The injected root permits hermetic tests; XML can never choose a root or a path.
    static Preferences replaceScarletPreferences(byte[] prefsXml, Preferences userRoot) throws IOException, BackingStoreException
    {
        Document doc = validatedScarletPreferences(prefsXml);
        if (!userRoot.isUserNode() || !"/".equals(userRoot.absolutePath()))
            throw new IOException("Scarlet preferences require a user preferences root.");
        Element root = firstChildElement(doc.getDocumentElement(), "root");
        Element net = onlyChildNode(root, "net", "/");
        Element owner = onlyChildNode(net, "sybyline", "/net");
        Element scarlet = onlyChildNode(owner, "scarlet", "/net/sybyline");
        Preferences existing = userRoot.node("net/sybyline/scarlet");
        try
        {
            existing.removeNode();
            userRoot.flush();
        }
        catch (IllegalStateException ignored)
        {
            // Already removed by another Preferences handle in this process.
        }
        Preferences imported = userRoot.node("net/sybyline/scarlet");
        copyValidatedPreferenceNode(scarlet, imported);
        imported.flush();
        userRoot.flush();
        return imported;
    }

    private static void copyValidatedPreferenceNode(Element source, Preferences destination)
    {
        NodeList children = source.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            if (!(children.item(i) instanceof Element)) continue;
            Element child = (Element) children.item(i);
            if ("map".equals(child.getTagName()))
            {
                NodeList entries = child.getChildNodes();
                for (int j = 0; j < entries.getLength(); j++)
                    if (entries.item(j) instanceof Element)
                    {
                        Element entry = (Element) entries.item(j);
                        destination.put(entry.getAttribute("key"), entry.getAttribute("value"));
                    }
            }
            else copyValidatedPreferenceNode(child, destination.node(child.getAttribute("name")));
        }
    }

    static boolean shouldImportDataEntry(String relative, ImportOptions options)
    {
        if (normalizeEntryName(relative) == null) return false;
        boolean credentialData = isCredentialDataEntry(relative);
        if (options.importDataFiles)
            return options.importCredentials || !credentialData;
        return options.importCredentials && credentialData;
    }

    // Blanks Discord channel/webhook routing in the freshly-imported config (bot token,
    // guild, roles, and cosmetics kept). No-op when there is no imported config.
    private static void scrubImportedDiscordChannels()
    {
        File dataDir = Scarlet.dir;
        if (dataDir == null)
            return;
        File discordConfig = new File(dataDir, "discord_bot.json");
        if (discordConfig.isFile())
            ScarletDiscordJDA.scrubDiscordChannels(discordConfig);
    }

    static boolean isCredentialDataEntry(String relative)
    {
        String normalized = normalizeEntryName(relative);
        if (normalized == null) return true; // Invalid names always fail closed.
        normalized = normalized.toLowerCase(Locale.ROOT);
        return GLOBAL_PREFS_KEY.equals(normalized)
            || LEGACY_COOKIE_FILE.equals(normalized)
            || SECURE_PREFS_FILE.equals(normalized)
            || SECURE_PREFS_BACKUP_FILE.equals(normalized)
            || (SECURE_PREFS_FILE + ".tmp").equals(normalized)
            || normalized.startsWith(SECURE_PREFS_FILE + ".")
            || normalized.startsWith("secure-prefs.dat.backup-")
            || normalized.startsWith("secure-prefs.registry-backup-")
            || normalized.startsWith("secure-prefs.quarantine-")
            || normalized.startsWith("preference-namespace-backups/")
            || isCredentialBearingConfigurationBackup(normalized);
    }

    private static boolean isCredentialBearingConfigurationBackup(String normalized)
    {
        if (!normalized.startsWith("backups/") || !normalized.endsWith(".bak")) return false;
        String name = normalized.substring("backups/".length());
        // Settings automatically falls back to these files. A data-only import must not
        // plant source login values in a future recovery path, or replace local recovery copies.
        for (String base : new String[] { "settings.json", "discord_bot.json", GLOBAL_PREFS_KEY,
                SECURE_PREFS_FILE, LEGACY_COOKIE_FILE })
            if (name.startsWith(base + ".")) return true;
        return false;
    }

    private static String normalizeEntryName(String name)
    {
        if (name == null || name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0)
            return null;
        for (String part : name.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) return null;
        return name;
    }

    private static String importSummary(ImportOptions options, int dataFiles, int credentialDataFiles, boolean prefsImported)
    {
        StringBuilder summary = new StringBuilder();
        if (options.importDataFiles)
            summary.append(dataFiles).append(" data/config file(s) restored");
        else
            summary.append("data/config files left unchanged");
        summary.append("; ");
        if (options.importCredentials)
            summary.append("credentials imported")
                   .append(" (").append(credentialDataFiles).append(" credential support file(s) restored)");
        else
            summary.append("credentials left unchanged");
        return summary.toString();
    }

    private static File createPreImportBackup() throws IOException
    {
        File backup = nextPreImportBackupFile();
        // Import already owns both exclusive leases. Never enter the public export wrapper here.
        String summary = createPreImportBackup(Scarlet.dir, backup, ScarletMigration::snapshotPreferences);
        pruneOldBackups(backup.getParentFile());
        LOG.info("Created pre-import migration backup at {} ({})", backup, summary);
        return backup;
    }

    /** Under the import's existing leases, capture even an absent first-install data home. */
    static String createPreImportBackup(File dataDir, File backup, PreferenceExport preferences) throws IOException
    {
        return exportBundleAtomic(backup, null, null, dataDir, preferences, true, true).summary;
    }

    private static File nextPreImportBackupFile() throws IOException
    {
        File dataDir = Scarlet.dir;
        if (dataDir == null)
            throw new IOException("Scarlet data directory is unavailable; cannot create pre-import backup.");
        File parent = dataDir.getAbsoluteFile().getParentFile();
        File backupDir = new File(parent != null ? parent : dataDir, dataDir.getName() + "-migration-backups");
        Files.createDirectories(backupDir.toPath());
        restrictToOwner(backupDir.toPath());
        String stamp = BACKUP_TIMESTAMP.format(LocalDateTime.now());
        for (int i = 0; i < 1000; i++)
        {
            String suffix = i == 0 ? "" : "-" + i;
            File candidate = new File(backupDir, "scarlet-pre-import-" + stamp + suffix + ".zip");
            if (!candidate.exists())
                return candidate;
        }
        throw new IOException("Could not find an unused pre-import backup filename in " + backupDir.getAbsolutePath());
    }

    public static final class ImportResult
    {
        ImportResult(String summary, File backupFile)
        {
            this.summary = summary;
            this.backupFile = backupFile;
        }

        public final String summary;
        public final File backupFile;
    }

    public static final class ImportOptions
    {
        public ImportOptions(boolean importDataFiles, boolean importCredentials)
        {
            this(importDataFiles, importCredentials, true);
        }
        public ImportOptions(boolean importDataFiles, boolean importCredentials, boolean keepChannels)
        {
            this.importDataFiles = importDataFiles;
            this.importCredentials = importCredentials;
            this.keepChannels = keepChannels;
        }

        static ImportOptions all()
        {
            return new ImportOptions(true, true, true);
        }

        public final boolean importDataFiles;
        public final boolean importCredentials;
        /** When false, the imported Discord config has its channel/webhook routing blanked
         *  (bot token, guild, roles, and everything else kept). Defaults to true, so existing
         *  callers and legacy bundles import exactly as before. */
        public final boolean keepChannels;
    }

    private static void zipDir(ZipOutputStream zip, File root, File dir, String prefix, File skip, int[] count, ProgressState state) throws IOException
    {
        File[] files = dir.listFiles();
        if (files == null)
            throw new IOException("Cannot enumerate the complete data directory for export");
        byte[] buffer = new byte[8192];
        for (File f : files)
        {
            if (skipExportEntry(f)) continue;
            if (Files.isSymbolicLink(f.toPath()))
            {
                continue;
            }
            File fCanon = f.getCanonicalFile();
            if (fCanon.equals(skip))
                continue;
            if (!isUnder(root, fCanon))
            {
                LOG.warn("Skipping data entry outside Scarlet data folder during migration export: {}", f);
                continue;
            }
            String entryName = prefix + f.getName();
            if (normalizeEntryName(entryName) == null) throw new IOException("Data filename cannot be represented in a portable migration bundle");
            if (f.isDirectory())
            {
                zipDir(zip, root, fCanon, entryName + "/", skip, count, state);
            }
            else if (f.isFile())
            {
                state.update("Compressing", entryName);
                zip.putNextEntry(new ZipEntry(entryName));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f)))
                {
                    int n;
                    while ((n = in.read(buffer)) > 0)
                    {
                        zip.write(buffer, 0, n);
                        state.addBytes(n);
                    }
                }
                zip.closeEntry();
                count[0]++;
                state.fileDone();
            }
        }
    }

    /** Counts regular files and bytes under dir with the same filters {@link #zipDir} applies. */
    private static void measureDir(File root, File dir, File skip, long[] filesBytes) throws IOException
    {
        File[] files = dir.listFiles();
        if (files == null)
            throw new IOException("Cannot enumerate the complete data directory for export");
        for (File f : files)
        {
            if (skipExportEntry(f)) continue;
            if (Files.isSymbolicLink(f.toPath()))
                continue;
            File fCanon = f.getCanonicalFile();
            if (fCanon.equals(skip))
                continue;
            if (!isUnder(root, fCanon))
                continue;
            if (f.isDirectory())
            {
                measureDir(root, fCanon, skip, filesBytes);
            }
            else if (f.isFile())
            {
                filesBytes[0]++;
                filesBytes[1] += f.length();
            }
        }
    }

    private static boolean skipExportEntry(File file)
    {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".tmp") || name.endsWith(".scarlet-data.lock")
            || name.equals(".scarlet-preferences.lock") || name.equals("ipc.token")
            || name.startsWith(".scarlet-export-") || name.startsWith(".scarlet-verify-")
            || name.startsWith(".scarlet-import-") || name.startsWith(".scarlet-decrypt-")
            || name.startsWith(".scarlet-publish-");
    }

    private static JsonObject readConfigurationObject(Path file) throws IOException
    {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_PREFS_XML_BYTES)
            throw new IOException("Imported or existing configuration is not a bounded regular file");
        try (java.io.Reader input = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            JsonElement parsed = JsonParser.parseReader(input);
            if (!parsed.isJsonObject()) throw new IOException("Imported or existing configuration must be a JSON object");
            return parsed.getAsJsonObject();
        }
        catch (RuntimeException ex) { throw new IOException("Imported or existing configuration is invalid; no import was applied", ex); }
    }

    private static boolean isLegacyLoginField(String field)
    {
        String key = field.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
        return key.equals("vrcusername") || key.equals("vrcpassword") || key.equals("vrcsecret");
    }

    private static boolean isDiscordCredentialField(String field)
    {
        String key = field.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
        return key.equals("token") || key.equals("bottoken") || key.equals("discordtoken")
            || key.equals("encryptedtoken") || key.equals("tokenencrypted")
            || key.equals("clientsecret") || key.equals("scarletauxwh2webhookurl");
    }

    private static void scrubDiscordRouting(JsonObject config)
    {
        for (String field : new String[] { "audioChannelSf", "discordActionLogChannelSf", "opsAlertChannelSf",
            "trainingChannelSf", "evidenceRoot", "ticketToolCategorySf" }) config.add(field, com.google.gson.JsonNull.INSTANCE);
        for (String field : new String[] { "auditType2channelSf", "auditExType2channelSf", "auditType2secretChannelSf",
            "auditExType2secretChannelSf", "scarletAuxWh2webhookUrl", "auditType2scarletAuxWh" }) config.add(field, new JsonObject());
    }

    /** Total bytes of all regular files under dir (symlinks not followed); 0 if dir is missing. */
    public static long directorySize(File dir)
    {
        if (dir == null || !dir.isDirectory())
            return 0L;
        long[] filesBytes = { 0L, 0L };
        try
        {
            File canon = dir.getCanonicalFile();
            measureDir(canon, canon, null, filesBytes);
        }
        catch (IOException ex)
        {
            LOG.warn("Could not measure directory size of {}", dir, ex);
        }
        return filesBytes[1];
    }

    private static boolean isUnder(File baseDir, File file) throws IOException
    {
        String basePath = baseDir.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.equals(basePath) || filePath.startsWith(basePath + File.separator);
    }

    /** Resolves a bundle-relative path under baseDir, returning null on path-traversal attempts. */
    private static File safeResolve(File baseDir, String relative) throws IOException
    {
        if (relative == null || relative.isEmpty())
            return null;
        File dest = new File(baseDir, relative).getCanonicalFile();
        String basePath = baseDir.getPath() + File.separator;
        if (dest.getPath().equals(baseDir.getPath()) || dest.getPath().startsWith(basePath))
            return dest;
        return null;
    }

    private static byte[] readAll(InputStream in, long maxBytes) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0)
        {
            if ((long) out.size() + n > maxBytes)
                throw new IOException("Bundle preferences entry exceeds size limit (possible zip bomb).");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void copyCapped(InputStream in, OutputStream out, long perEntryMax, long[] runningTotal, long totalMax) throws IOException
    {
        byte[] buffer = new byte[8192];
        long entryBytes = 0L;
        int n;
        while ((n = in.read(buffer)) > 0)
        {
            entryBytes += n;
            runningTotal[0] += n;
            if (entryBytes > perEntryMax)
                throw new IOException("Bundle entry exceeds size limit (possible zip bomb).");
            if (runningTotal[0] > totalMax)
                throw new IOException("Bundle exceeds total size limit (possible zip bomb).");
            out.write(buffer, 0, n);
        }
    }

    /** Plaintext recovery backups must receive verified protection on every supported OS. */
    private static void restrictToOwner(Path path) throws IOException
    {
        ScarletPrivateFiles.protect(path);
    }

    private static void pruneOldBackups(File backupDir)
    {
        if (backupDir == null || !backupDir.isDirectory())
            return;
        File[] files = backupDir.listFiles((d, n) -> n.startsWith("scarlet-pre-import-") && n.endsWith(".zip"));
        if (files == null || files.length <= MAX_BACKUPS_KEPT)
            return;
        List<File> sorted = new java.util.ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (int i = MAX_BACKUPS_KEPT; i < sorted.size(); i++)
        {
            try { Files.deleteIfExists(sorted.get(i).toPath()); }
            catch (IOException ex) { LOG.warn("Could not prune old migration backup {}", sorted.get(i), ex); }
        }
    }

    // ── Optional PIN encryption (AES-256-GCM, PBKDF2-SHA256) ────────────────────

    static boolean isEncryptedBundle(File file) throws IOException
    {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file)))
        {
            byte[] magic = new byte[ENC_MAGIC.length];
            int read = 0;
            while (read < magic.length)
            {
                int r = in.read(magic, read, magic.length - read);
                if (r < 0)
                    break;
                read += r;
            }
            return read == magic.length && Arrays.equals(magic, ENC_MAGIC);
        }
    }

    private static void encryptFile(File plain, File out, char[] pin) throws IOException
    {
        encryptFile(plain, out, pin, null);
    }

    private static void encryptFile(File plain, File out, char[] pin, Progress progress) throws IOException
    {
        byte[] salt = new byte[ENC_SALT_LEN];
        byte[] iv = new byte[ENC_IV_LEN];
        RAND.nextBytes(salt);
        RAND.nextBytes(iv);
        try
        {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(pin, salt), new GCMParameterSpec(ENC_TAG_BITS, iv));
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
                 InputStream is = new BufferedInputStream(new FileInputStream(plain)))
            {
                os.write(ENC_MAGIC);
                os.write(ENC_VERSION);
                os.write(salt);
                os.write(iv);
                long encTotal = plain.length(), encDone = 0L;
                byte[] buffer = new byte[8192];
                int n;
                while ((n = is.read(buffer)) > 0)
                {
                    byte[] enc = cipher.update(buffer, 0, n);
                    if (enc != null)
                        os.write(enc);
                    encDone += n;
                    if (progress != null && !progress.progress("Encrypting", out.getName(), 0, 0, encDone, encTotal))
                        throw new IOException(CANCELLED_MESSAGE);
                }
                byte[] fin = cipher.doFinal();
                if (fin != null)
                    os.write(fin);
            }
        }
        catch (GeneralSecurityException ex)
        {
            try { Files.deleteIfExists(out.toPath()); } catch (IOException ignored) { }
            throw new IOException("Failed to encrypt migration bundle", ex);
        }
    }

    private static void decryptFile(File in, File outPlain, char[] pin) throws IOException
    {
        decryptFile(in, outPlain, pin, null);
    }

    private static void decryptFile(File in, File outPlain, char[] pin, Progress progress) throws IOException
    {
        try (InputStream is = new BufferedInputStream(new FileInputStream(in)))
        {
            byte[] magic = new byte[ENC_MAGIC.length];
            readFully(is, magic);
            if (!Arrays.equals(magic, ENC_MAGIC))
                throw new IOException("Not a PIN-protected Scarlet bundle.");
            int version = is.read();
            if (version != ENC_VERSION)
                throw new IOException("Unsupported encrypted bundle version: " + version);
            byte[] salt = new byte[ENC_SALT_LEN];
            byte[] iv = new byte[ENC_IV_LEN];
            readFully(is, salt);
            readFully(is, iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(pin, salt), new GCMParameterSpec(ENC_TAG_BITS, iv));
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outPlain)))
            {
                byte[] buffer = new byte[8192];
                long done = 0L;
                int n;
                while ((n = is.read(buffer)) > 0)
                {
                    byte[] dec = cipher.update(buffer, 0, n);
                    if (dec != null)
                        os.write(dec);
                    done += n;
                    if (progress != null && !progress.progress("Verifying encryption", in.getName(), 0, 0, done, in.length()))
                        throw new IOException(CANCELLED_MESSAGE);
                }
                byte[] fin = cipher.doFinal();   // verifies the GCM tag; throws on wrong PIN / tamper
                if (fin != null)
                    os.write(fin);
            }
        }
        catch (AEADBadTagException ex)
        {
            try { Files.deleteIfExists(outPlain.toPath()); } catch (IOException ignored) { }
            throw new IOException("Incorrect PIN, or the bundle has been altered or corrupted.", ex);
        }
        catch (GeneralSecurityException ex)
        {
            try { Files.deleteIfExists(outPlain.toPath()); } catch (IOException ignored) { }
            throw new IOException("Failed to decrypt migration bundle", ex);
        }
    }

    private static SecretKey deriveKey(char[] pin, byte[] salt) throws GeneralSecurityException
    {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, ENC_PBKDF2_ITERS, ENC_KEY_BITS);
        try
        {
            byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        }
        finally
        {
            spec.clearPassword();
        }
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException
    {
        int read = 0;
        while (read < buffer.length)
        {
            int r = in.read(buffer, read, buffer.length - read);
            if (r < 0)
                throw new IOException("Truncated encrypted bundle header.");
            read += r;
        }
    }

    // ── Preferences-XML validation (XXE-hardened) ──────────────────────────────

    static byte[] normalizeScarletOnlyPrefs(byte[] prefsXml) throws IOException
    {
        Document doc = validatedScarletPreferences(prefsXml);
        try
        {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, "http://java.sun.com/dtd/preferences.dtd");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        }
        catch (Exception ex)
        {
            throw new IOException("Bundle preferences could not be normalized for the maintained namespace.", ex);
        }
    }

    private static Document validatedScarletPreferences(byte[] prefsXml) throws IOException
    {
        if (prefsXml == null || prefsXml.length == 0 || prefsXml.length > MAX_PREFS_XML_BYTES)
            throw new IOException("Bundle preferences XML is empty or exceeds its size limit.");
        try
        {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setExpandEntityReferences(false);
            dbf.setXIncludeAware(false);
            dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder db = dbf.newDocumentBuilder();
            // Never fetch the (dead) preferences DTD URL or any external entity.
            db.setEntityResolver((publicId, systemId) -> new InputSource(new java.io.StringReader("")));
            Document doc = db.parse(new ByteArrayInputStream(prefsXml));
            if (doc.getDoctype() != null && (doc.getDoctype().getInternalSubset() != null
                || !"preferences".equals(doc.getDoctype().getName())
                || !"http://java.sun.com/dtd/preferences.dtd".equals(doc.getDoctype().getSystemId())
                || doc.getDoctype().getPublicId() != null))
                throw new IOException("Bundle preferences contain an unsupported document type.");
            Element prefs = doc.getDocumentElement();
            if (!"preferences".equals(prefs.getTagName()) || !"1.0".equals(prefs.getAttribute("EXTERNAL_XML_VERSION")))
                throw new IOException("Bundle preferences require the supported preferences document format.");
            requirePreferenceAttributes(prefs, "EXTERNAL_XML_VERSION");
            java.util.List<Element> top = preferenceElements(prefs);
            if (top.size() != 1 || !"root".equals(top.get(0).getTagName()))
                throw new IOException("Bundle preferences require exactly one root.");
            Element root = firstChildElement(prefs, "root");
            requirePreferenceAttributes(root, "type");
            if (!"user".equals(root.getAttribute("type")))
                throw new IOException("Bundle preferences cannot target system preferences.");
            validatePreferenceNode(root, 0, new int[2]);
            requireEmptyMap(root, "/");
            Element net = onlyChildNode(root, "net", "/");
            requireEmptyMap(net, "/net");
            Element owner = singleChildNode(net, "/net");
            String ownerName = owner.getAttribute("name");
            if (!"sybyline".equals(ownerName) && !interimPreferencesOwnerName().equals(ownerName))
                throw new IOException("Bundle preferences target an unexpected owner namespace; refusing import.");
            requireEmptyMap(owner, "/net/<owner>");
            Element scarlet = onlyChildNode(owner, "scarlet", "/net/<owner>");
            if (interimPreferencesOwnerName().equals(ownerName))
            {
                owner.setAttribute("name", "sybyline");
                putMapEntry(doc, scarlet, ScarletSettings.INTERIM_NAMESPACE_RECONCILED_KEY, "true");
            }
            return doc;
        }
        catch (IOException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new IOException("Bundle preferences failed validation: " + ex.getMessage(), ex);
        }
    }

    private static void validatePreferenceNode(Element node, int depth, int[] counts) throws IOException
    {
        if (depth > 32 || ++counts[0] > 4096)
            throw new IOException("Bundle preferences exceed the node/depth limit.");
        java.util.List<Element> children = preferenceElements(node);
        if (children.isEmpty() || !"map".equals(children.get(0).getTagName()))
            throw new IOException("Each preferences node must start with exactly one map.");
        Element map = children.get(0);
        requirePreferenceAttributes(map);
        java.util.Set<String> keys = new java.util.HashSet<>(), names = new java.util.HashSet<>();
        for (Element entry : preferenceElements(map))
        {
            if (!"entry".equals(entry.getTagName())) throw new IOException("Invalid preferences map content.");
            requirePreferenceAttributes(entry, "key", "value");
            if (!preferenceElements(entry).isEmpty() || !entry.hasAttribute("key") || !entry.hasAttribute("value")
                || entry.getAttribute("key").length() > Preferences.MAX_KEY_LENGTH
                || entry.getAttribute("value").length() > Preferences.MAX_VALUE_LENGTH
                || !keys.add(entry.getAttribute("key")) || ++counts[1] > 65536)
                throw new IOException("Invalid, duplicate, or excessive preferences entries.");
        }
        for (int i = 1; i < children.size(); i++)
        {
            Element child = children.get(i);
            if (!"node".equals(child.getTagName())) throw new IOException("Invalid preferences child node.");
            requirePreferenceAttributes(child, "name");
            String name = child.getAttribute("name");
            if (name.isEmpty() || name.length() > Preferences.MAX_NAME_LENGTH || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || ".".equals(name) || "..".equals(name) || !names.add(name))
                throw new IOException("Preferences node names must be unique single relative names.");
            validatePreferenceNode(child, depth + 1, counts);
        }
    }

    private static java.util.List<Element> preferenceElements(Element parent) throws IOException
    {
        java.util.List<Element> elements = new java.util.ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if (child instanceof Element) elements.add((Element) child);
            else if (child.getNodeType() != Node.COMMENT_NODE
                && !(child.getNodeType() == Node.TEXT_NODE && child.getNodeValue().trim().isEmpty()))
                throw new IOException("Unexpected preferences XML content.");
        }
        return elements;
    }

    private static void requirePreferenceAttributes(Element element, String... allowed) throws IOException
    {
        java.util.Set<String> names = new java.util.HashSet<>(Arrays.asList(allowed));
        for (int i = 0; i < element.getAttributes().getLength(); i++)
            if (!names.contains(element.getAttributes().item(i).getNodeName()))
                throw new IOException("Unexpected preferences XML attribute.");
    }

    private static String interimPreferencesOwnerName()
    {
        return new String(new char[] { 'h', 'a', 't', 's', 'o', 'n', '3', '2', '1' });
    }

    private static Element firstChildElement(Element parent, String tag)
    {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName()))
                return (Element) n;
        }
        return null;
    }

    private static void putMapEntry(Document doc, Element node, String key, String value)
    {
        Element map = firstChildElement(node, "map");
        if (map == null)
        {
            map = doc.createElement("map");
            node.insertBefore(map, node.getFirstChild());
        }
        NodeList entries = map.getChildNodes();
        for (int i = 0; i < entries.getLength(); i++)
        {
            Node child = entries.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                && "entry".equals(child.getNodeName())
                && key.equals(((Element) child).getAttribute("key")))
            {
                ((Element) child).setAttribute("value", value);
                return;
            }
        }
        Element entry = doc.createElement("entry");
        entry.setAttribute("key", key);
        entry.setAttribute("value", value);
        map.appendChild(entry);
    }

    /** Returns the single child {@code <node>} of parent, requiring its name attribute to equal expectedName. */
    private static Element onlyChildNode(Element parent, String expectedName, String pathForError) throws IOException
    {
        Element only = null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"node".equals(n.getNodeName()))
                continue;
            if (only != null)
                throw new IOException("Bundle preferences contain unexpected extra nodes under " + pathForError + "; refusing import.");
            only = (Element) n;
        }
        if (only == null)
            throw new IOException("Bundle preferences are missing the " + expectedName + " node under " + pathForError + ".");
        if (!expectedName.equals(only.getAttribute("name")))
            throw new IOException("Bundle preferences reference node '" + only.getAttribute("name") + "' outside the Scarlet subtree; refusing import.");
        return only;
    }

    /** Returns the single child {@code <node>} without constraining its name attribute. */
    private static Element singleChildNode(Element parent, String pathForError) throws IOException
    {
        Element only = null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"node".equals(n.getNodeName()))
                continue;
            if (only != null)
                throw new IOException("Bundle preferences contain unexpected extra nodes under " + pathForError + "; refusing import.");
            only = (Element) n;
        }
        if (only == null)
            throw new IOException("Bundle preferences are missing the owner node under " + pathForError + ".");
        return only;
    }

    /** Requires the {@code <map>} child of parent to contain no {@code <entry>} elements. */
    private static void requireEmptyMap(Element parent, String pathForError) throws IOException
    {
        Element map = firstChildElement(parent, "map");
        if (map == null)
            return;
        NodeList children = map.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "entry".equals(n.getNodeName()))
                throw new IOException("Bundle preferences set values at " + pathForError + " outside the Scarlet subtree; refusing import.");
        }
    }

    private ScarletMigration()
    {
    }

}

package net.sybyline.scarlet;

import java.awt.GraphicsEnvironment;
import net.sybyline.scarlet.util.FileBackups;
import java.awt.Rectangle;
import java.io.Console;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import net.sybyline.scarlet.util.ChangeListener;
import net.sybyline.scarlet.util.EncryptedPrefs;
import net.sybyline.scarlet.util.Maths;
import net.sybyline.scarlet.util.MiscUtils;

public class ScarletSettings
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/Settings");
    private static final String BACKUP_WARNING_ACK_FILENAME = "backup-warning-ack-v1.flag";
    static final String INTERIM_NAMESPACE_RECONCILED_KEY = "__scarlet_interim_namespace_reconciled_v1";
    private static final String globalPW = Optional.ofNullable(System.getenv("SCARLET_GLOBAL_PW")).orElseGet(() -> System.getProperty("scarlet.global.pw", fallbackGlobalPw()));

    private static String fallbackGlobalPw()
    {
        try
        {
            File dir = Scarlet.dir;
            if (dir == null)
                throw new IllegalStateException("Scarlet data directory is not available");
            Path secretPath = new File(dir, "global-prefs.key").toPath();
            if (Files.isRegularFile(secretPath))
            {
                String existing = new String(Files.readAllBytes(secretPath), StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty())
                    return existing;
            }
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            String encoded = Base64.getEncoder().encodeToString(secret);
            if (!Files.isDirectory(secretPath.getParent()))
                Files.createDirectories(secretPath.getParent());
            try
            {
                writeOwnerOnlyNewFile(secretPath, encoded.getBytes(StandardCharsets.UTF_8));
            }
            catch (FileAlreadyExistsException ignored)
            {
                String existing = new String(Files.readAllBytes(secretPath), StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty())
                    return existing;
                throw ignored;
            }
            return encoded;
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("Failed to initialize local fallback encryption password", ex);
        }
    }

    private static void writeOwnerOnlyNewFile(Path path, byte[] bytes) throws java.io.IOException
    {
        try
        {
            FileAttribute<java.util.Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
            try (SeekableByteChannel channel = Files.newByteChannel(path, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attr))
            {
                channel.write(ByteBuffer.wrap(bytes));
            }
        }
        catch (UnsupportedOperationException ex)
        {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    public ScarletSettings(Scarlet scarlet, File settingsFile)
    {
        this(scarlet, settingsFile, true);
    }

    /** JSON-only construction for hermetic validation tests; never opens Preferences. */
    ScarletSettings(Scarlet scarlet, File settingsFile, boolean initializePreferences)
    {
        this.scarlet = scarlet;
        this.settingsFile = settingsFile;
        this.settingsFileLastModified = settingsFile.lastModified();
        this.hasVersionChangedSinceLastRun = null;
        if (initializePreferences) this.warnBackupBeforeSecureStoreChanges();
        // Preferences.userNodeForPackage() touches the Windows Registry, and
        // EncryptedPrefs runs PBKDF2 with 100k iterations — both are slow on
        // Windows. We kick them off immediately on a background thread so they
        // run in parallel with the rest of startup (UI build, settings file
        // load, etc.) and only block when the first actual prefs read/write
        // occurs (typically at cookie load time, well into the startup sequence).
        Thread prefsInitThread = new Thread(() ->
        {
            try
            {
                ScarletSettings.this.prefsInitStage = "opening Preferences user node";
                Preferences prefs = Preferences.userNodeForPackage(Scarlet.class);
                migrateInterimPreferencesIfSafe(prefs);
                ScarletSettings.this.globalPreferences = prefs;
                ScarletSettings.this.preferences = prefs;
                ScarletSettings.this.prefsInitStage = "opening encrypted preference wrapper";
                EncryptedPrefs enc = ScarletSettings.this.createSecurePrefs(prefs, "");
                ScarletSettings.this.prefsInitStage = "publishing preference handles";
                ScarletSettings.this.globalPreferences = prefs;
                ScarletSettings.this.globalEncrypted = enc;
                ScarletSettings.this.preferences = prefs;
                ScarletSettings.this.encrypted = enc;
            }
            catch (Throwable t)
            {
                LOG.error("Exception initializing Preferences on background thread", t);
                try
                {
                    if (ScarletSettings.this.globalPreferences == null)
                    {
                        Preferences prefs = Preferences.userNodeForPackage(Scarlet.class);
                        ScarletSettings.this.globalPreferences = prefs;
                        ScarletSettings.this.preferences = prefs;
                    }
                }
                catch (Throwable preferencesError)
                {
                    LOG.error("Exception opening Java Preferences fallback", preferencesError);
                }
                try
                {
                    if (ScarletSettings.this.globalEncrypted == null)
                    {
                        EncryptedPrefs fallback = ScarletSettings.this.createSecurePrefs(ScarletSettings.this.globalPreferences, "");
                        ScarletSettings.this.globalEncrypted = fallback;
                        ScarletSettings.this.encrypted = fallback;
                    }
                }
                catch (Throwable fallbackError)
                {
                    LOG.error("Exception initializing compatibility preference fallback", fallbackError);
                }
            }
            finally
            {
                ScarletSettings.this.prefsInitStage = "ready";
                ScarletSettings.this.prefsReady.countDown();
            }
        }, "Scarlet Prefs Init");
        prefsInitThread.setDaemon(true);
        this.prefsInitThread = prefsInitThread;
        LOG.info("Initializing secure preference store in background");
        if (initializePreferences) prefsInitThread.start();
        else this.prefsReady.countDown();
        this.json = null;
        this.lastRunVersion = new RegistryString("lastRunVersion");
        this.lastRunTime = new RegistryOffsetDateTime("lastRunTime");
        this.lastAuditQuery = new RegistryOffsetDateTime("lastAuditQuery");
        this.lastInstancesCheck = new RegistryOffsetDateTime("lastInstancesCheck");
        this.lastAuthRefresh = new RegistryOffsetDateTime("lastAuthRefresh");
        this.lastUpdateCheck = new RegistryOffsetDateTime("lastUpdateCheck");
        this.lastAnnouncementCheck = new RegistryOffsetDateTime("lastAnnouncementCheck");
        this.lastAnnouncementId = new RegistryString("lastAnnouncementId");
        this.nextPollAction = new RegistryOffsetDateTime("nextPollAction");
        this.nextModSummary = new RegistryOffsetDateTime("nextModSummary");
        this.nextOutstandingMod = new RegistryOffsetDateTime("nextOutstandingMod");
        this.lastInstanceJoined = new RegistryLocalDateTime("lastInstanceJoined");
        this.uiBounds = new RegistryRectangle("uiBounds");
        this.heuristicKickCount = new FileValuedIntRange("heuristicKickCount", "Heuristic Kick Count", 3, 1, 10);
        this.heuristicPeriodDays = new FileValuedIntRange("heuristicPeriodDays", "Heuristic Period (days)", 3, 1, 30);
        this.outstandingPeriodDays = new FileValuedIntRange("outstandingPeriodDays", "Outstanding Period (days)", 3, 1, 30);
        this.cacheCleanupEnabled = new FileValuedBoolean("cache_cleanup_enabled", "Auto-delete old cached files", true);
        this.cacheCleanupDays = new FileValuedIntRange("cache_cleanup_days", "Delete files older than (days)", 7, 1, 30);
        this.lastCacheCleanup = new RegistryOffsetDateTime("lastCacheCleanup");
    }

    private void warnBackupBeforeSecureStoreChanges()
    {
        File dataDir = Scarlet.dir;
        if (isBackupWarningAcknowledged(dataDir))
            return;
        File settingsParent = this.settingsFile != null ? this.settingsFile.getParentFile() : null;
        File settingsFile = this.settingsFile;
        File storeBin = dataDir != null ? new File(dataDir, "store.bin") : null;
        File discordBotFile = dataDir != null ? new File(dataDir, "discord_bot.json") : null;
        File watchedGroupsFile = dataDir != null ? new File(dataDir, "watched_groups.json") : null;
        File watchedAvatarsFile = dataDir != null ? new File(dataDir, "watched_avatars.json") : null;
        File goodPronounsFile = dataDir != null ? new File(dataDir, "good_pronoun.json") : null;
        File badPronounsFile = dataDir != null ? new File(dataDir, "bad_pronoun.json") : null;
        String legacyPrefsStore = "Earlier Scarlet Java Preferences node (retained unchanged)";
        String dataDirText = dataDir != null ? dataDir.getAbsolutePath() : "(unavailable)",
               settingsDirText = settingsParent != null ? settingsParent.getAbsolutePath() : dataDirText;
        String message =
            "Back up your Hatson321/Scarlet Logger data before startup continues.\n\n" +
            "Scarlet Logger may reinitialize or lose access to previously stored secure settings and credentials during initialization.\n" +
            "If something goes wrong, a backup is the safest way to recover.\n\n" +
            "Previously stored secure credentials are copied only when Scarlet can verify their encryption seed safely.\n" +
            "If that verification cannot be completed, the earlier store is left unchanged and you may need to enter secure values again.\n\n" +
            "Data that could be reset, reverted, or need recovery includes:\n" +
            " - Discord bot token\n" +
            " - VRChat usernames, passwords, TOTP secrets, and cookie/session data\n" +
            " - Other secure values stored in the Java Preferences credential store\n\n" +
            "The early secure-store initialization path does not rewrite Scarlet Logger's JSON data files.\n" +
            "That means files like watched groups, pronoun lists, watched avatars, and most other JSON-backed data should remain in place during this step.\n" +
            "You should still back them up first as a fail-safe.\n\n" +
            "Recommended current files/folders to copy now:\n" +
            " - " + dataDirText + "\n" +
            " - " + settingsDirText + "\n" +
            " - " + pathOrUnavailable(settingsFile) + "\n" +
            " - " + pathOrUnavailable(storeBin) + "\n" +
            " - " + pathOrUnavailable(discordBotFile) + "\n" +
            " - " + pathOrUnavailable(watchedGroupsFile) + "\n" +
            " - " + pathOrUnavailable(watchedAvatarsFile) + "\n" +
            " - " + pathOrUnavailable(goodPronounsFile) + "\n" +
            " - " + pathOrUnavailable(badPronounsFile) + "\n\n" +
            "Legacy and secure credential storage to back up as well:\n" +
            " - " + legacyPrefsStore + "\n" +
            " - Legacy plain-text settings keys may also exist in " + pathOrUnavailable(settingsFile) + " as vrc_username / vrc_password / vrc_secret\n\n" +
            "Startup will continue after this reminder is acknowledged.";
        LOG.warn("Back up Hatson321/Scarlet Logger data before initialization continues. Secure settings are migrated only when the existing encryption seed can be verified; otherwise they may need to be re-entered. JSON-backed files are not expected to be rewritten during early secure-store initialization. Recommended backup path(s): {}, {}. Earlier credential storage: {}",
            dataDirText,
            settingsDirText,
            legacyPrefsStore);
        System.err.println();
        System.err.println("===========================================");
        System.err.println("  BACK UP YOUR HATSON321/SCARLET LOGGER DATA BEFORE CONTINUING");
        System.err.println("  Secure credentials migrate only when their encryption seed can be verified.");
        System.err.println("  If verification fails, you may need to enter secure values again.");
        System.err.println("  This can affect Discord bot token and VRChat credentials/cookies.");
        System.err.println("  The early secure-store initialization path is not expected");
        System.err.println("  to rewrite Scarlet Logger's JSON data files, but back them up anyway.");
        System.err.println("  Recommended current backup path(s):");
        System.err.println("   - " + dataDirText);
        System.err.println("   - " + settingsDirText);
        System.err.println("   - " + pathOrUnavailable(settingsFile));
        System.err.println("   - " + pathOrUnavailable(storeBin));
        System.err.println("   - " + pathOrUnavailable(discordBotFile));
        System.err.println("   - " + pathOrUnavailable(watchedGroupsFile));
        System.err.println("   - " + pathOrUnavailable(watchedAvatarsFile));
        System.err.println("   - " + pathOrUnavailable(goodPronounsFile));
        System.err.println("   - " + pathOrUnavailable(badPronounsFile));
        System.err.println("  Legacy credential storage:");
        System.err.println("   - " + legacyPrefsStore);
        System.err.println("   - " + pathOrUnavailable(settingsFile) + " (legacy keys vrc_username / vrc_password)");
        System.err.println("===========================================");
        System.err.println();
        if (this.scarlet != null && this.scarlet.splash != null)
        {
            this.scarlet.splash.splashSubtext("Back up Hatson321/Scarlet Logger data before continuing");
        }
        if (!GraphicsEnvironment.isHeadless())
        {
            JOptionPane.showMessageDialog(
                null,
                net.sybyline.scarlet.ui.Swing.dialogMessage(message),
                "Back Up Hatson321/Scarlet Logger Data",
                JOptionPane.WARNING_MESSAGE);
        }
        acknowledgeBackupWarning(dataDir);
    }

    private static boolean isBackupWarningAcknowledged(File dataDir)
    {
        File ackFile = backupWarningAckFile(dataDir);
        return ackFile != null && ackFile.isFile();
    }

    private static void acknowledgeBackupWarning(File dataDir)
    {
        File ackFile = backupWarningAckFile(dataDir);
        if (ackFile == null)
            return;
        try
        {
            File parent = ackFile.getParentFile();
            if (parent != null && !parent.isDirectory())
                parent.mkdirs();
            Files.write(ackFile.toPath(), Collections.singletonList("acknowledged=" + System.currentTimeMillis()), StandardCharsets.UTF_8);
        }
        catch (Exception ex)
        {
            LOG.warn("Failed to persist backup warning acknowledgement at {}", ackFile, ex);
        }
    }

    private static File backupWarningAckFile(File dataDir)
    {
        return dataDir == null ? null : new File(dataDir, BACKUP_WARNING_ACK_FILENAME);
    }

    private static boolean isWindows()
    {
        String osName = System.getProperty("os.name");
        return osName != null && osName.toLowerCase().contains("win");
    }

    private static String pathOrUnavailable(File file)
    {
        return file == null ? "(unavailable)" : file.getAbsolutePath();
    }

    /**
     * Blocks until the background Preferences + EncryptedPrefs initialisation
     * is complete. Must be called before any access to globalPreferences,
     * globalEncrypted, preferences, or encrypted.
     */
    void awaitPrefs()
    {
        this.awaitPrefs("startup");
    }

    void awaitPrefs(String reason)
    {
        if (this.prefsReady.getCount() == 0)
            return;
        try
        {
            long started = System.currentTimeMillis();
            while (!this.prefsReady.await(5L, TimeUnit.SECONDS))
            {
                long waitedMillis = System.currentTimeMillis() - started;
                LOG.warn("Still waiting {} ms for secure preference store during {} (stage: {})",
                    waitedMillis,
                    reason,
                    this.prefsInitStage);
                Thread worker = this.prefsInitThread;
                if (worker != null)
                {
                    StackTraceElement[] stack = worker.getStackTrace();
                    if (stack.length > 0)
                    {
                        LOG.warn("Secure preference worker thread is currently at {}", stack[0]);
                    }
                    else
                    {
                        LOG.warn("Secure preference worker thread state is {}", worker.getState());
                    }
                }
                if (this.scarlet != null && this.scarlet.splash != null)
                {
                    this.scarlet.splash.splashSubtext("Opening secure credential store");
                }
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Preferences init", e);
        }
    }

    private EncryptedPrefs createSecurePrefs(Preferences prefs, String namespace)
    {
        // Pass the data directory so EncryptedPrefs keeps a redundant, self-healing copy of every
        // secret in a file there (dual-write + verified read fallback + reconstruction), instead of
        // trusting the Windows-registry-backed Preferences store alone.
        return new EncryptedPrefs(prefs, globalPW, Scarlet.dir);
    }

    /**
     * Reconciles Java Preferences written by the short-lived interim package namespace back into
     * the restored Sybyline namespace. The interim encryption seed must verify. Before any write,
     * the source and any populated destination are exported to owner-only backup files. If both
     * nodes contain data, the interim state is selected only when it has a demonstrably newer
     * last-run timestamp. A durable marker makes a completed reconciliation idempotent. The
     * interim source node is never changed or removed.
     */
    static void migrateInterimPreferencesIfSafe(Preferences current)
    {
        if (current == null)
            return;
        try
        {
            Preferences root = Preferences.userRoot();
            String interimPath = interimPreferencesNodePath();
            if (current.absolutePath().equals(interimPath))
                return;
            if (current.getBoolean(INTERIM_NAMESPACE_RECONCILED_KEY, false))
            {
                if (EncryptedPrefs.tryReadLocalPassword(current, Scarlet.dir, globalPW) == null
                    && !ensureImportedInterimSeedIsRewrapped(current, globalPW))
                    LOG.error("Restored Scarlet Preferences are marked as namespace-reconciled, but their encryption seed could not be verified or re-wrapped; the retained interim node will not overwrite them");
                return;
            }
            boolean interimNodeExists = root.nodeExists(interimPath);
            Preferences interim = interimNodeExists ? root.node(interimPath) : null;
            boolean interimHasPreferenceData = interim != null && preferenceNodeHasData(interim);
            // A completed secure-store cutover intentionally leaves the Preferences node empty and
            // keeps the only seed copy in secure-prefs.dat. Use the current node merely as a record
            // reader in that case; the helper still derives and looks up the interim-path seed.
            Preferences seedRecords = interim != null ? interim : current;
            String localPassword = EncryptedPrefs.tryReadLocalPasswordForPath(
                seedRecords,
                Scarlet.dir,
                interimPath,
                globalPW);
            if (localPassword == null)
            {
                if (interimNodeExists)
                    LOG.warn("Interim Scarlet secure preferences were detected, but their encryption seed could not be verified; no preference data was copied");
                return;
            }
            boolean currentHasData = preferenceNodeHasData(current);
            if (currentHasData)
            {
                String interimLastRun = interimHasPreferenceData ? interim.get("lastRunTime", null) : null;
                if (!shouldPreferInterimState(current.get("lastRunTime", null), interimLastRun))
                {
                    LOG.warn("Both restored and interim Scarlet Preferences contain data, but the interim state was not demonstrably newer; both nodes were left untouched");
                    return;
                }
            }

            if (interimHasPreferenceData && !backupPreferenceNode(interim, "interim-hatson321"))
            {
                LOG.warn("Interim Scarlet Preferences could not be backed up; namespace reconciliation was cancelled");
                return;
            }
            if (currentHasData && !backupPreferenceNode(current, "restored-sybyline"))
            {
                LOG.warn("Restored Scarlet Preferences could not be backed up; namespace reconciliation was cancelled");
                return;
            }

            clearPreferenceTree(current);
            if (interimHasPreferenceData)
                copyPreferenceTree(interim, current);
            EncryptedPrefs.installLocalPassword(current, globalPW, localPassword);
            current.putBoolean(INTERIM_NAMESPACE_RECONCILED_KEY, true);
            current.flush();
            LOG.info("Safely reconciled interim Scarlet Preferences into the restored namespace; owner-only backups were written where applicable and the interim node was retained unchanged");
        }
        catch (Exception ex)
        {
            LOG.warn("Could not safely copy interim Scarlet Preferences; the interim node was retained unchanged", ex);
        }
    }

    static String interimPreferencesNodePath()
    {
        return new String(new char[] {
            '/', 'n', 'e', 't', '/', 'h', 'a', 't', 's', 'o', 'n', '3', '2', '1', '/',
            's', 'c', 'a', 'r', 'l', 'e', 't'
        });
    }

    static boolean shouldPreferInterimState(String currentLastRun, String interimLastRun)
    {
        OffsetDateTime current = parsePreferenceTimestamp(currentLastRun);
        OffsetDateTime interim = parsePreferenceTimestamp(interimLastRun);
        // A shared seed proves only that both stores are decryptable; it says nothing about which
        // state is newer. Requiring two valid timestamps and a strict ordering prevents the retained
        // interim node from rolling the restored node back on every subsequent startup.
        return current != null && interim != null && interim.isAfter(current);
    }

    /**
     * Ensures that Preferences normalized from the interim package namespace have a local-password
     * record wrapped for the restored namespace. The imported XML and file mirror retain the
     * interim-path record, so it can be verified and re-wrapped without modifying that record.
     */
    static boolean ensureImportedInterimSeedIsRewrapped(Preferences current, String activeGlobalPassword)
    {
        if (current == null || activeGlobalPassword == null)
            return false;
        if (EncryptedPrefs.tryReadLocalPassword(current, Scarlet.dir, activeGlobalPassword) != null)
            return true;
        String localPassword = EncryptedPrefs.tryReadLocalPasswordForPath(
            current,
            Scarlet.dir,
            interimPreferencesNodePath(),
            activeGlobalPassword);
        if (localPassword == null)
            return false;
        try
        {
            if (preferenceNodeHasData(current) && !backupPreferenceNode(current, "imported-interim-before-seed-rewrap"))
                return false;
            EncryptedPrefs.installLocalPassword(current, activeGlobalPassword, localPassword);
            current.putBoolean(INTERIM_NAMESPACE_RECONCILED_KEY, true);
            current.flush();
            return EncryptedPrefs.tryReadLocalPassword(current, Scarlet.dir, activeGlobalPassword) != null;
        }
        catch (Exception ex)
        {
            LOG.warn("Could not safely re-wrap the imported interim Scarlet preference seed", ex);
            return false;
        }
    }

    /** Returns the active imported-store password without creating or changing a key file. */
    static String currentImportedGlobalPassword()
    {
        String environment = System.getenv("SCARLET_GLOBAL_PW");
        if (environment != null)
            return environment;
        String property = System.getProperty("scarlet.global.pw");
        if (property != null)
            return property;
        try
        {
            File dir = Scarlet.dir;
            if (dir == null)
                return null;
            Path key = new File(dir, "global-prefs.key").toPath();
            if (!Files.isRegularFile(key))
                return null;
            String value = new String(Files.readAllBytes(key), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        }
        catch (Exception ex)
        {
            LOG.warn("Could not read the imported Scarlet preference encryption key", ex);
            return null;
        }
    }

    private static OffsetDateTime parsePreferenceTimestamp(String value)
    {
        if (value == null)
            return null;
        try
        {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private static boolean preferenceNodeHasData(Preferences node) throws BackingStoreException
    {
        return node.keys().length != 0 || node.childrenNames().length != 0;
    }

    private static void clearPreferenceTree(Preferences node) throws BackingStoreException
    {
        for (String childName : node.childrenNames())
            node.node(childName).removeNode();
        for (String key : node.keys())
            node.remove(key);
        node.flush();
    }

    private static void copyPreferenceTree(Preferences source, Preferences destination) throws BackingStoreException
    {
        for (String key : source.keys())
        {
            String value = source.get(key, null);
            if (value != null)
                destination.put(key, value);
        }
        for (String childName : source.childrenNames())
            copyPreferenceTree(source.node(childName), destination.node(childName));
    }

    private static boolean backupPreferenceNode(Preferences node, String label)
    {
        File dataDir = Scarlet.dir;
        if (dataDir == null)
            return false;
        try
        {
            Path directory = new File(dataDir, "preference-namespace-backups").toPath();
            Files.createDirectories(directory);
            try
            {
                Files.setPosixFilePermissions(directory, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            }
            catch (UnsupportedOperationException ignored)
            {
            }
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(ZoneOffset.UTC)
                .format(java.time.Instant.now());
            Path backup = directory.resolve(timestamp + "-" + label + ".xml");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            node.exportSubtree(bytes);
            writeOwnerOnlyNewFile(backup, bytes.toByteArray());
            return true;
        }
        catch (Exception ex)
        {
            LOG.warn("Could not create owner-only backup for Preferences node {}", node.absolutePath(), ex);
            return false;
        }
    }

    /**
     * Reconcile the registry and the data-folder secure-store file without destroying anything, and
     * return a plain report. When {@code cutover} is true and the file already byte-matches every
     * registry entry, the registry is snapshotted and then emptied so the file becomes the primary
     * store. Safe to call at any time.
     */
    public String repairSecureStore(boolean cutover)
    {
        this.awaitPrefs("repairing secure store");
        EncryptedPrefs enc = this.globalEncrypted != null ? this.globalEncrypted : this.encrypted;
        if (enc == null)
            return "Secure store is not initialized yet.";
        return enc.repair(cutover);
    }

    private static void flushPreferences(Preferences prefs, String reason)
    {
        if (prefs == null)
            return;
        try
        {
            prefs.flush();
        }
        catch (BackingStoreException | RuntimeException ex)
        {
            LOG.warn("Failed to flush Java Preferences while {}", reason, ex);
        }
    }

    final Scarlet scarlet;
    final File settingsFile;
    final long settingsFileLastModified;
    Boolean hasVersionChangedSinceLastRun;
    // These are initialised on a background thread (see constructor). All
    // access must go through awaitPrefs() before touching them.
    final CountDownLatch prefsReady = new CountDownLatch(1);
    volatile String prefsInitStage = "starting";
    volatile Thread prefsInitThread;
    Preferences globalPreferences;
    EncryptedPrefs globalEncrypted;
    Preferences preferences;
    EncryptedPrefs encrypted;
    private JsonObject json;
    private boolean jsonLoadFailed;
    public final RegistryString lastRunVersion;
    public final RegistryString lastAnnouncementId;
    public final RegistryOffsetDateTime lastRunTime, lastAuditQuery, lastInstancesCheck, lastAuthRefresh, lastUpdateCheck, lastAnnouncementCheck, nextPollAction, nextModSummary, nextOutstandingMod;
    public final RegistryLocalDateTime lastInstanceJoined;
    public final RegistryRectangle uiBounds;
    public final FileValuedIntRange heuristicKickCount, heuristicPeriodDays, outstandingPeriodDays;
    public final FileValuedBoolean cacheCleanupEnabled;
    public final FileValuedIntRange cacheCleanupDays;
    public final RegistryOffsetDateTime lastCacheCleanup;

    public boolean checkHasVersionChangedSinceLastRun()
    {
        Boolean hasVersionChangedSinceLastRun = this.hasVersionChangedSinceLastRun;
        if (hasVersionChangedSinceLastRun != null)
            return hasVersionChangedSinceLastRun.booleanValue();
        OffsetDateTime lastRunTime = this.lastRunTime.getOrNull();
        if (lastRunTime == null)
        {
            this.hasVersionChangedSinceLastRun = Boolean.TRUE;
            return true;
        }
        if (this.settingsFileLastModified > lastRunTime.toInstant().plusMillis(1_000L).toEpochMilli())
        {
            this.hasVersionChangedSinceLastRun = Boolean.TRUE;
            return true;
        }
        if (!Objects.equals(Scarlet.VERSION, this.lastRunVersion.getOrNull()))
        {
            this.hasVersionChangedSinceLastRun = Boolean.TRUE;
            return true;
        }
        this.hasVersionChangedSinceLastRun = Boolean.FALSE;
        return false;
    }

    public void updateRunVersionAndTime()
    {
        this.lastRunVersion.set(Scarlet.VERSION);
        this.lastRunTime.set(OffsetDateTime.now(ZoneOffset.UTC));
    }

    public interface FileValuedVisitor<T>
    {
        T visitBasic(FileValued<?> fileValued);
        T visitBoolean(FileValued<Boolean> fileValued, boolean defaultValue);
        T visitIntegerRange(FileValued<Integer> fileValued, int defaultValue, int minimum, int maximum);
        <E extends Enum<E>> T visitEnum(FileValued<E> fileValued, E defaultValue);
        T visitStringChoice(FileValued<String> fileValued, Supplier<Collection<String>> validValues);
        T visitStringPattern(FileValued<String> fileValued, String pattern, boolean lenient);
        T visitStringArrayPattern(FileValued<String[]> fileValued, String pattern, boolean lenient);
        T visitVoid(FileValued<Void> fileValued, Runnable task);
    }

    final Map<String, FileValued<?>> fileValuedSettings = Collections.synchronizedMap(new LinkedHashMap<>());
    public class FileValued<T>
    {
        FileValued(String id, String name, Class<T> type, UnaryOperator<T> validate, T ifNull)
        {
            this(id, name, type, validate, () -> ifNull);
        }
        FileValued(String id, String name, Class<T> type, UnaryOperator<T> validate, Supplier<T> ifNull)
        {
            if (ScarletSettings.this.fileValuedSettings.putIfAbsent(id, this) != null)
                throw new IllegalArgumentException("Duplicate setting: "+id);
            this.id = id;
            this.name = name;
            this.type = type;
            this.validate = validate != null ? validate : UnaryOperator.identity();
            this.ifNull = ifNull != null ? ifNull : () -> null;
            this.cached = null;
            this.listeners = ChangeListener.newListenerList();
        }
        final String id, name;
        final Class<T> type;
        final UnaryOperator<T> validate;
        final Supplier<T> ifNull;
        T cached;
        final ChangeListener.ListenerList<T> listeners;
        public String id()
        {
            return this.id;
        }
        public String name()
        {
            return this.name;
        }
        public Class<T> getType()
        {
            return this.type;
        }
        public synchronized T get()
        {
            T cached_ = this.cached;
            if (cached_ == null)
            {
                T stored = ScarletSettings.this.getObject(this.id, this.type);
                cached_ = stored == null ? null : this.validate.apply(stored);
                if (cached_ == null)
                {
                    T fallback = this.ifNull.get();
                    cached_ = fallback == null ? null : this.validate.apply(fallback);
                }
                if (cached_ != null && !Objects.deepEquals(stored, cached_))
                    ScarletSettings.this.setObject(this.id, this.type, cached_);
                this.cached = cached_;
            }
            return cached_;
        }
        public synchronized boolean set(T value_, String source)
        {
            T prev = this.cached;
            if (value_ == null)
                value_ = this.ifNull.get();
            T validated = value_ == null ? null : this.validate.apply(value_);
            boolean valid = validated != null;
            if (valid)
            {
                this.cached = validated;
                ScarletSettings.this.setObject(this.id, this.type, validated);
            }
            this.listeners.onMaybeChange(prev, valid ? validated : value_, valid, source);
            return valid;
        }
        protected <TT> TT visit(FileValuedVisitor<TT> visitor)
        {
            return visitor.visitBasic(this);
        }
    }
    public class FileValuedBoolean extends FileValued<Boolean>
    {
        public FileValuedBoolean(String id, String name, boolean defaultValue)
        {
            super(id, name, Boolean.class, null, defaultValue);
            this.defaultValue = defaultValue;
        }
        final boolean defaultValue;
        @Override
        protected <TT> TT visit(FileValuedVisitor<TT> visitor)
        {
            return visitor.visitBoolean(this, this.defaultValue);
        }
    }
    /** A default-off permission that can only be newly enabled by an explicit confirmation. */
    public class FileValuedConsentBoolean extends FileValuedBoolean
    {
        public FileValuedConsentBoolean(String id, String name)
        {
            super(id, name, false);
        }
        private long consentGeneration;

        /** Generic settings writers may revoke permission, but cannot grant it. */
        @Override
        public synchronized boolean set(Boolean value, String source)
        {
            this.consentGeneration++;
            if (Boolean.TRUE.equals(value))
                return Boolean.TRUE.equals(this.get());
            return super.set(Boolean.FALSE, source);
        }

        /**
         * Prompts outside the setting lock so a concurrent revoke can take effect.
         * A revoke or newer attempt invalidates any earlier pending approval.
         */
        public boolean setWithConsent(Boolean value, String source, BooleanSupplier confirmation)
        {
            if (!Boolean.TRUE.equals(value))
                return this.set(Boolean.FALSE, source);
            final long generation;
            synchronized (this)
            {
                if (Boolean.TRUE.equals(this.get()))
                    return true;
                generation = ++this.consentGeneration;
            }
            boolean approved = false;
            try
            {
                approved = confirmation != null && confirmation.getAsBoolean();
            }
            catch (RuntimeException ex)
            {
                LOG.warn("Consent confirmation failed for setting {}; permission was not granted", this.id);
            }
            synchronized (this)
            {
                if (generation != this.consentGeneration)
                    return false;
                this.consentGeneration++;
                return approved && super.set(Boolean.TRUE, source);
            }
        }
    }
    public class FileValuedIntRange extends FileValued<Integer>
    {
        public FileValuedIntRange(String id, String name, int defaultValue, int min, int max)
        {
            super(id, name, Integer.class, value -> Maths.clamp(value, min, max), defaultValue);
            this.def = defaultValue;
            this.min = min;
            this.max = max;
        }
        final int def, min, max;
        @Override
        protected <TT> TT visit(FileValuedVisitor<TT> visitor)
        {
            return visitor.visitIntegerRange(this, this.def, this.min, this.max);
        }
    }
    static UnaryOperator<String> patternOne(String pattern, boolean lenient)
    {
        if (pattern == null)
            return UnaryOperator.identity();
        Pattern p = Pattern.compile(pattern);
        if (lenient)
            return value ->
            {
                Matcher m = p.matcher(value);
                return m.find() ? m.group() : null;
            };
        return value -> p.matcher(value).matches() ? value : null;
    }
    public class FileValuedEnum<E extends Enum<E>> extends FileValued<E>
    {
        public FileValuedEnum(String id, String name, E defaultValue)
        {
            super(id, name, defaultValue.getDeclaringClass(), UnaryOperator.identity(), defaultValue);
            this.defaultValue = defaultValue;
        }
        final E defaultValue;
        @Override
        protected <TT> TT visit(FileValuedVisitor<TT> visitor)
        {
            return visitor.visitEnum(this, this.defaultValue);
        }
    }
    public class FileValuedStringChoice extends FileValued<String>
    {
        public FileValuedStringChoice(String id, String name, String defaultValue, Supplier<Collection<String>> validValues)
        {
            super(id, name, String.class, value -> validValues.get().contains(value) ? value : null, defaultValue);
            this.validValues = validValues;
        }
        final Supplier<Collection<String>> validValues;
        @Override
        protected <TT> TT visit(FileValuedVisitor<TT> visitor)
        {
            return visitor.visitStringChoice(this, this.validValues);
        }
    }
    public class FileValuedStringPattern extends FileValued<String>
    {
        public FileValuedStringPattern(String id, String name, String defaultValue, String pattern, boolean lenient)
        {
            super(id, name, String.class, patternOne(pattern, lenient), defaultValue);
            this.pattern = pattern;
            this.lenient = lenient;
        }
        final String pattern;
        final boolean lenient;
        @Override
        protected <TT> TT visit(FileValuedVisitor<TT> visitor)
        {
            return visitor.visitStringPattern(this, this.pattern, this.lenient);
        }
    }
    static UnaryOperator<String[]> patternAll(String pattern, boolean lenient)
    {
        if (pattern == null)
            return UnaryOperator.identity();
        Pattern p = Pattern.compile(pattern);
        if (lenient)
            return values ->
            {
                if (values != null)
                {
                    values = values.clone();
                    for (int i = 0; i < values.length; i++)
                    {
                        Matcher m = p.matcher(values[i]);
                        if (!m.find())
                            return null;
                        values[i] = m.group();
                    }
                }
                return values;
            };
        return values ->
        {
            if (values != null)
                for (String value : values)
                    if (!p.matcher(value).matches())
                        return null;
            return values;
        };
    }
    public class FileValuedStringArrayPattern extends FileValued<String[]>
    {
        public FileValuedStringArrayPattern(String id, String name, String[] defaultValue, String pattern, boolean lenient)
        {
            super(id, name, String[].class, patternAll(pattern, lenient), defaultValue);
            this.pattern = pattern;
            this.lenient = lenient;
        }
        final String pattern;
        final boolean lenient;
        @Override
        protected <TT> TT visit(FileValuedVisitor<TT> visitor)
        {
            return visitor.visitStringArrayPattern(this, this.pattern, this.lenient);
        }
    }
    public class FileValuedVoid extends FileValued<Void>
    {
        public FileValuedVoid(String id, String name, Runnable task)
        {
            super(id, name, Void.class, null, (Void)null);
            this.task = task;
        }
        final Runnable task;
        @Override
        protected <TT> TT visit(FileValuedVisitor<TT> visitor)
        {
            return visitor.visitVoid(this, this.task);
        }
    }

    public class RegistryStringValued<T>
    {
        RegistryStringValued(String name, Supplier<T> ifNull, Function<String, T> parse, Function<T, String> stringify)
        {
            this.name = name;
            this.ifNull = ifNull != null ? ifNull : () -> null;
            this.parse = parse;
            this.stringify = stringify;
            this.cached = null;
        }
        final String name;
        final Supplier<T> ifNull;
        final Function<String, T> parse;
        final Function<T, String> stringify;
        T cached;
        public T getOrNull()
        {
            return this.get(false);
        }
        public T getOrSupply()
        {
            return this.get(true);
        }
        private T get(boolean orNow)
        {
            T cached_ = this.cached;
            if (cached_ != null)
                return cached_;
            ScarletSettings.this.awaitPrefs("resolving preference " + this.name);
            synchronized (ScarletSettings.this)
            {
                String string = this.read();
                if (string != null) try
                {
                    cached_ = this.parse.apply(string);
                    this.cached = cached_;
                    this.write(string);
                    return cached_;
                }
                catch (RuntimeException ex)
                {
                }
                if (orNow)
                {
                    cached_ = this.ifNull.get();
                    this.cached = cached_;
                    this.write(this.stringify.apply(cached_));
                }
                return cached_;
            }
        }
        protected String read()
        {
            ScarletSettings.this.awaitPrefs("reading preference " + this.name);
            String string = ScarletSettings.this.preferences.get(this.name, null);
            if (string == null) string = ScarletSettings.this.globalPreferences.get(this.name, null);
            return string;
        }
        protected void write(String string)
        {
            ScarletSettings.this.awaitPrefs("writing preference " + this.name);
            ScarletSettings.this.preferences.put(this.name, string);
            flushPreferences(ScarletSettings.this.preferences, "writing preference " + this.name);
        }
        public void set(T value_)
        {
            if (value_ == null)
                return;
            this.cached = value_;
            ScarletSettings.this.awaitPrefs("writing preference " + this.name);
            synchronized (ScarletSettings.this)
            {
                this.write(this.stringify.apply(value_));
            }
        }
        /**
         * Removes this value from storage and clears the in-memory cache.
         * Safe to call even if no value has been stored.
         */
        public void clear()
        {
            this.cached = null;
            ScarletSettings.this.awaitPrefs("clearing preference " + this.name);
            synchronized (ScarletSettings.this)
            {
                ScarletSettings.this.preferences.remove(this.name);
                ScarletSettings.this.globalPreferences.remove(this.name);
                flushPreferences(ScarletSettings.this.preferences, "clearing preference " + this.name);
                if (ScarletSettings.this.globalPreferences != ScarletSettings.this.preferences)
                    flushPreferences(ScarletSettings.this.globalPreferences, "clearing global preference " + this.name);
            }
        }
    }
    public class RegistryStringValuedEncrypted<T> extends RegistryStringValued<T>
    {
        RegistryStringValuedEncrypted(String name, boolean globalOnly, Supplier<T> ifNull, Function<String, T> parse, Function<T, String> stringify)
        {
            super(name, ifNull, parse, stringify);
            this.globalOnly = globalOnly;
        }
        protected final boolean globalOnly;
        @Override
        protected String read()
        {
            ScarletSettings.this.awaitPrefs("reading encrypted preference " + this.name);
            String string = this.globalOnly ? null : ScarletSettings.this.encrypted.get(this.name);
            if (string == null) string = ScarletSettings.this.globalEncrypted.get(this.name);
            return string;
        }
        @Override
        protected void write(String string)
        {
            ScarletSettings.this.awaitPrefs("writing encrypted preference " + this.name);
            (this.globalOnly ? ScarletSettings.this.globalEncrypted : ScarletSettings.this.encrypted).putVerified(this.name, string);
        }
        /**
         * Removes this encrypted value from both the global and namespace
         * encrypted stores, and clears the in-memory cache.
         */
        @Override
        public void clear()
        {
            this.cached = null;
            ScarletSettings.this.awaitPrefs("clearing encrypted preference " + this.name);
            synchronized (ScarletSettings.this)
            {
                ScarletSettings.this.globalEncrypted.remove(this.name);
                if (ScarletSettings.this.encrypted != null && ScarletSettings.this.encrypted != ScarletSettings.this.globalEncrypted)
                    ScarletSettings.this.encrypted.remove(this.name);
            }
        }
    }

    public class RegistryOffsetDateTime extends RegistryStringValued<OffsetDateTime>
    {
        RegistryOffsetDateTime(String name)
        {
            super(name,
                () -> OffsetDateTime.now(ZoneOffset.UTC),
                string -> OffsetDateTime.parse(string, DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME::format);
        }
    }

    public class RegistryLocalDateTime extends RegistryStringValued<LocalDateTime>
    {
        RegistryLocalDateTime(String name)
        {
            super(name,
                () -> LocalDateTime.now(),
                string -> LocalDateTime.parse(string, DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME::format);
        }
    }

    public class RegistryRectangle extends RegistryStringValued<Rectangle>
    {
        RegistryRectangle(String name)
        {
            super(name, null,
                string ->
                {
                    String[] values = string.split(",");
                    return new Rectangle(
                        Integer.parseInt(values[0]),
                        Integer.parseInt(values[1]),
                        Integer.parseInt(values[2]),
                        Integer.parseInt(values[3]));
                },
                value -> String.format("%d,%d,%d,%d", value.x, value.y, value.width, value.height));
        }
    }

    public class RegistryString extends RegistryStringValued<String>
    {
        RegistryString(String name)
        {
            super(name, null, Function.identity(), Function.identity());
        }
    }
    public class RegistryStringEncrypted extends RegistryStringValuedEncrypted<String>
    {
        RegistryStringEncrypted(String name, boolean globalOnly)
        {
            super(name, globalOnly, null, Function.identity(), Function.identity());
        }
    }
    public class RegistryJsonEncrypted<T> extends RegistryStringValuedEncrypted<T>
    {
        RegistryJsonEncrypted(String name, boolean globalOnly, Supplier<T> ifNull, Type type)
        {
            super(name, globalOnly, ifNull, $->Scarlet.GSON.fromJson($, type), $->Scarlet.GSON.toJson($, type));
        }
    }

    synchronized JsonObject getJson()
    {
        JsonObject json = this.json;
        if (json != null)
            return json;
        try
        {
            if (ScarletData.existingRecord(this.settingsFile))
            {
                try (Reader r = this.openSettingsReader())
                {
                    json = Scarlet.GSON_PRETTY.fromJson(r, JsonObject.class);
                }
                catch (Exception ex)
                {
                    // An inaccessible newer primary cannot safely be replaced by an older
                    // backup: the original bytes may not even be readable for preservation.
                    if (physicalReadFailure(ex)) throw ex;
                    LOG.error("Exception loading settings", ex);
                }
                // Keep the existing valid-backup recovery, but never invent an empty config
                // when an existing primary and every available backup cannot be read.
                if (json == null) json = loadNewestGoodBackup();
                if (json == null) throw new java.io.IOException("No readable settings object or valid backup");
            }
            else
            {
                if (this.jsonLoadFailed)
                    throw new java.io.IOException("Previously unreadable settings were removed; restore or repair them before reloading");
                json = new JsonObject();
            }
            this.jsonLoadFailed = false;
            this.json = json;
            return json;
        }
        catch (Exception ex)
        {
            this.jsonLoadFailed = true;
            throw new java.io.UncheckedIOException("Settings could not be read; saved files were preserved. Restore or repair settings.json before retrying.",
                ex instanceof java.io.IOException ? (java.io.IOException) ex : new java.io.IOException(ex));
        }
    }

    Reader openSettingsReader() throws java.io.IOException { return MiscUtils.reader(this.settingsFile); }

    private static boolean physicalReadFailure(Throwable failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SecurityException || (cause instanceof java.io.IOException
                && !(cause instanceof com.google.gson.stream.MalformedJsonException)
                && !(cause instanceof java.io.EOFException))) return true;
        return false;
    }

    private JsonObject loadNewestGoodBackup()
    {
        for (File bak : FileBackups.backupsNewestFirst(this.settingsFile))
        {
            try
            {
                if (!ScarletData.existingRecord(bak)) continue;
                try (Reader r = MiscUtils.reader(bak))
                {
                    JsonObject recovered = Scarlet.GSON_PRETTY.fromJson(r, JsonObject.class);
                    if (recovered != null && recovered.size() > 0)
                    {
                        LOG.warn("settings.json was empty or corrupt (likely an unclean shutdown); recovered {} setting(s) from backup {}", recovered.size(), bak.getName());
                        return recovered;
                    }
                }
            }
            catch (Exception ex)
            {
                LOG.debug("Settings backup {} not usable: {}", bak, ex.toString());
            }
        }
        return null;
    }

    public synchronized void saveJson()
    {
        if (this.jsonLoadFailed)
            throw new java.io.UncheckedIOException(new java.io.IOException("Settings save refused because the existing file could not be read. Restore or repair it first."));
        JsonObject json = this.json;
        if (json == null)
            return;
        if (!this.settingsFile.getParentFile().isDirectory())
            this.settingsFile.getParentFile().mkdirs();
        try (FileBackups.AtomicWriter w = FileBackups.writer(this.settingsFile))
        {
            Scarlet.GSON_PRETTY.toJson(json, JsonObject.class, w);
            w.commit();
        }
        catch (Exception ex)
        {
            LOG.error("Exception saving settings", ex);
            throw new java.io.UncheckedIOException("Settings were not saved. In-memory changes remain available for retry; the previous file was not deliberately replaced.",
                ex instanceof java.io.IOException ? (java.io.IOException) ex : new java.io.IOException(ex));
        }
    }

    synchronized void clearCachedJson()
    {
        this.json = null;
    }

    public synchronized String getString(String key)
    {
        JsonObject json = this.getJson();
        if (json.has(key))
        {
            JsonElement elem = json.get(key);
            if (!elem.isJsonNull())
                return elem.getAsString();
        }
        String value = System.getProperty("net.sybyline.scarlet.setting."+key);
        return value != null ? value : System.getProperty("net.hatson321.scarlet.setting."+key);
    }
    public synchronized void setString(String key, String value)
    {
        JsonObject json = this.getJson();
        json.addProperty(key, value);
        this.saveJson();
    }

    public <T> T getObject(String key, TypeToken<T> type)
    {
        return this.getObject(key, type.getType());
    }
    public <T> T getObject(String key, Class<T> type)
    {
        return this.getObject(key, (Type)type);
    }
    public synchronized <T> T getObject(String key, Type type)
    {
        JsonObject json = this.getJson();
        if (json.has(key)) try
        {
            return Scarlet.GSON_PRETTY.fromJson(json.get(key), type);
        }
        catch (Exception ex)
        {
            LOG.error("Exception deserializing setting `"+key+"` of type `"+type+"`", ex);
        }
        return null;
    }
    public <T> void setObject(String key, TypeToken<T> type, T value)
    {
        this.setObject(key, type.getType(), value);
    }
    public <T> void setObject(String key, Class<T> type, T value)
    {
        this.setObject(key, (Type)type, value);
    }
    public synchronized <T> void setObject(String key, Type type, T value)
    {
        JsonObject json = this.getJson();
        JsonElement element;
        try
        {
            element = Scarlet.GSON_PRETTY.toJsonTree(value, type);
        }
        catch (Exception ex)
        {
            LOG.error("Exception serializing setting `"+key+"` of type `"+type+"`", ex);
            return;
        }
        json.add(key, element);
        this.saveJson();
    }
    public synchronized void setElementNoSave(String key, JsonElement element)
    {
        this.getJson().add(key, element);
    }
    public synchronized JsonElement getElement(String key)
    {
        return this.getJson().get(key);
    }

    public synchronized String getStringOrRequireInput(String key, String display, boolean sensitive)
    {
        String value = this.getString(key);
        if (value == null)
        {
            value = this.requireInput(display, sensitive);
            this.setString(key, value);
        }
        return value;
    }

    public String requireInput(String display, boolean sensitive)
    {
        if (!GraphicsEnvironment.isHeadless())
        {
            JPopupMenu cpm = new JPopupMenu();
            if (sensitive)
            {
                JPasswordField jpf = new JPasswordField(32);
                    cpm.add("Paste").addActionListener($ -> Optional.ofNullable(MiscUtils.AWTToolkit.get()).ifPresent(jpf::setText));
                    jpf.setComponentPopupMenu(cpm);
                int res = JOptionPane.showConfirmDialog(null, jpf, display, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                // Cancelling/closing the dialog returns null. It must NOT fall through to the console
                // fallback below: on a GUI (X11/Windows) launch there is usually no attached console,
                // so that read blocks forever on the EDT and freezes the entire UI (the "cancel the
                // import-groups URL prompt and nothing is clickable" bug). Console input is for
                // genuinely headless runs only.
                return res == JOptionPane.OK_OPTION ? new String(jpf.getPassword()) : null;
            }
            else
            {
                JTextField jtf = new JTextField(32);
                    cpm.add("Paste").addActionListener($ -> Optional.ofNullable(MiscUtils.AWTToolkit.get()).ifPresent(jtf::setText));
                    jtf.setComponentPopupMenu(cpm);
                int res = JOptionPane.showConfirmDialog(null, jtf, display, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                return res == JOptionPane.OK_OPTION ? jtf.getText() : null;
            }
        }
        return requireConsoleInput(display, sensitive);
    }
    private String requireConsoleInput(String display, boolean sensitive)
    {
        Console console = System.console();
        if (console != null)
            return sensitive
                ? new String(console.readPassword(display+": "))
                : console.readLine(display+": ");
        System.out.print(display+": ");
        @SuppressWarnings("resource")
        Scanner s = new Scanner(System.in);
        return s.nextLine();
    }
    public void requireInputAsync(String display, boolean sensitive, Consumer<String> then)
    {
        this.scarlet.execModal.execute(() -> then.accept(this.requireInput(display, sensitive)));
    }

    public boolean requireConfirmYesNo(String message, String title)
    {
        if (!GraphicsEnvironment.isHeadless())
        {
            return JOptionPane.showConfirmDialog(null, net.sybyline.scarlet.ui.Swing.dialogMessage(message), title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
        }
        Console console = System.console();
        if (console != null)
            return "y".equalsIgnoreCase(console.readLine("%s%n%s (y/n): ", title, message).trim());
        System.out.print(title+"\n"+message+" (y/n): ");
        @SuppressWarnings("resource")
        Scanner s = new Scanner(System.in);
        return "y".equalsIgnoreCase(s.nextLine().trim());
    }
    public void requireConfirmYesNoAsync(String message, String title, Runnable then, Runnable otherwise)
    {
        Runnable task = () ->
        {
            boolean confirmed;
            try
            {
                confirmed = this.requireConfirmYesNo(message, title);
            }
            catch (Throwable throwable)
            {
                LOG.error("Exception showing confirmation dialog `{}`: {}", title, message, throwable);
                confirmed = false;
            }
            Optional.ofNullable(confirmed ? then : otherwise).ifPresent(Runnable::run);
        };
        try
        {
            if (this.scarlet.execModal.isShutdown())
                throw new RejectedExecutionException("Modal executor is shut down");
            this.scarlet.execModal.execute(task);
        }
        catch (RejectedExecutionException rejected)
        {
            LOG.debug("Ignoring confirmation dialog `{}` because Scarlet is shutting down", title, rejected);
            Optional.ofNullable(otherwise).ifPresent(Runnable::run);
        }
    }

    public <T> void requireSelect(String message, String title, T[] selectionValues, T initialSelectionValue, Consumer<T> then)
    {
        if (!GraphicsEnvironment.isHeadless())
        {
            @SuppressWarnings("unchecked")
            T selected = (T)JOptionPane.showInputDialog(null, message, title, JOptionPane.WARNING_MESSAGE, null, selectionValues, initialSelectionValue);
            then.accept(selected);
            return;
        }
        StringBuilder sb = new StringBuilder();
        int len = selectionValues.length;
        for (int i = 0; i < len; i++)
            sb.append(i).append(':').append(' ').append(selectionValues[i]).append('\n');
        T selected = initialSelectionValue;
        Console console = System.console();
        if (console != null) try
        {
            selected = selectionValues[Integer.parseInt(console.readLine("%s%n%s%s: ", title, sb, message).trim())];
        }
        catch (Exception ex)
        {
        }
        else try
        {
            System.out.print(title+"\n"+sb+message+": ");
            @SuppressWarnings("resource")
            Scanner s = new Scanner(System.in);
            selected = selectionValues[s.nextInt()];
        }
        catch (Exception ex)
        {
        }
        then.accept(selected);
    }
    public <T> void requireSelectAsync(String message, String title, T[] selectionValues, T initialSelectionValue, Consumer<T> then)
    {
        this.scarlet.execModal.execute(() -> this.requireSelect(message, title, selectionValues, initialSelectionValue, then));
    }

}

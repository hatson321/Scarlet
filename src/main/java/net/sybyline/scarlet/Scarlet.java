package net.sybyline.scarlet;

import java.awt.GraphicsEnvironment;
import java.io.Closeable;
import java.io.Console;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.swing.JOptionPane;

import org.scalasbt.ipcsocket.UnixDomainServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.vrchatapi.model.Avatar;
import io.github.vrchatapi.model.GroupAuditLogEntry;
import io.github.vrchatapi.model.GroupInstance;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.User;

import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.sybyline.scarlet.log.ScarletLogger;
import net.sybyline.scarlet.ui.Swing;
import net.sybyline.scarlet.util.GithubApi;
import net.sybyline.scarlet.util.HttpURLInputStream;
import net.sybyline.scarlet.util.I18n;
import net.sybyline.scarlet.util.JsonAdapters;
import net.sybyline.scarlet.util.Location;
import net.sybyline.scarlet.util.MavenDepsLoader;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.Platform;
import net.sybyline.scarlet.util.ProcLock;
import net.sybyline.scarlet.util.SecurityRegressionChecks;
import net.sybyline.scarlet.util.VrcIds;
import net.sybyline.scarlet.util.VrchatApiVersionChecker;
import net.sybyline.scarlet.util.tts.TtsPackageInstallDialogs;
import net.sybyline.scarlet.util.tts.TtsService;
import net.sybyline.scarlet.util.EnforcementAgeState;
import net.sybyline.scarlet.util.EnforcementListState;

public class Scarlet implements Closeable
{
    private static final String DATA_FOLDER_NOTICE_ACK_FILENAME = "kozyblake-data-folder-notice-v1.flag";
    static final String DEFAULT_AUTO_INVITE_GROUP_ID = "grp_abc12345-6789-0abc-def0-123456789abc";
    private static final long META_CACHE_BUSTER_INTERVAL_MILLIS = 5L * 60L * 1_000L;
    private static final Pattern SAFE_RELEASE_TAG = Pattern.compile("^[A-Za-z0-9._+-]+$");

    public static final int JVM_DATA_MODEL;
    public static final int JAVA_SPEC;
    public static final boolean IS_DEV_ENV;

    static
    {
        // ── Windows HiDPI fix ─────────────────────────────────────────────────
        // On Java 9+ Windows JREs, Java2D applies the OS display scale (125%,
        // 150%, ...) on top of the scaling FlatLaf / Swing.scaleAll already do.
        // That double scaling causes overlapping components, duplicated window
        // artifacts, and miscolored popups. Disable Java2D's scaling and let
        // FlatLaf own the scale instead — equivalent to launching with
        // -Dsun.java2d.uiScale=1 -Dsun.java2d.uiScale.enabled=false.
        // Must run before ANY AWT/Java2D class initializes, hence this static
        // block in the main class. Users who pass either property explicitly on
        // the command line keep their value.
        if (Platform.CURRENT.isNT()
         && System.getProperty("sun.java2d.uiScale") == null
         && System.getProperty("sun.java2d.uiScale.enabled") == null)
        {
            System.setProperty("sun.java2d.uiScale", "1");
            System.setProperty("sun.java2d.uiScale.enabled", "false");
            // With Java2D scaling off, let FlatLaf compute its scale from the
            // system font instead (same path it uses on Java 8), so HiDPI
            // displays still get a readable UI. String literal used instead of
            // FlatSystemProperties.UI_SCALE_ENABLED to avoid loading any UI
            // classes this early.
            if (System.getProperty("flatlaf.uiScale.enabled") == null)
                System.setProperty("flatlaf.uiScale.enabled", "true");
        }

        String dataModel = System.getProperty("sun.arch.data.model");
        if (dataModel == null)
            System.err.println("System property 'sun.arch.data.model' is missing?!?!?!");
        else if (!"64".equals(dataModel))
            System.err.println("This application prefers a 64-bit JVM, but this is JVM is "+dataModel+"-bit");
        JVM_DATA_MODEL = Integer.getInteger("sun.arch.data.model", -1).intValue();
        
        String javaVersion = System.getProperty("java.specification.version");
        if (javaVersion == null)
            System.err.println("System property 'java.specification.version' is missing?!?!?!");
        else if (!"1.8".equals(javaVersion))
            System.err.println("Compiled on Java 8, running on Java "+javaVersion);
        JAVA_SPEC = javaVersion == null ? 0 : Integer.parseInt(javaVersion.startsWith("1.") ? javaVersion.substring(2) : javaVersion);
        
        IS_DEV_ENV =
            Boolean.getBoolean("IS_DEV_ENV")
            &&
            Stream.of(System.getProperty("java.class.path")
                .split(Pattern.quote(System.getProperty("path.separator"))))
                .map(String::trim)
                .filter($->!$.isEmpty())
                .map(File::new)
                .anyMatch(File::isDirectory)
//            &&
//            MavenDepsLoader.jarPath() == null
            ;
    }

    public static final String
        GROUP = "KozyBlake",
        LEGACY_GROUP = legacyGroupName(),
        NAME = "Scarlet",
        VERSION = appVersion(),
        APP_NAME = GROUP+"/"+NAME,
        FORK_GROUP = GROUP,
        FORK_REPOSITORY = NAME,
        FORK_NOTE = "Fork by KozyBlake \u2014 Windows & Linux",
        DEV_DISCORD = "Discord:@vinyarion/Vinyarion#0292/393412191547555841",
        SCARLET_DISCORD_URL = "https://discord.gg/CP3AyhypBF",

        // ── Fork repository (KozyBlake) ────────────────────────────────────────
        // This is the maintained fork. GITHUB_URL points here so update checks,
        // licence links, and help menu entries reflect the active repository.
        FORK_GITHUB_URL = "https://github.com/"+FORK_GROUP+"/"+FORK_REPOSITORY,
        GITHUB_URL = FORK_GITHUB_URL,

        // ── VRChat groups ──────────────────────────────────────────────────────
        SCARLET_VRCHAT_GROUP_ID = "grp_eb2eb120-67bd-404e-9665-498f567cb56d",
        SCARLET_VRCHAT_GROUP_URL = "https://vrchat.com/home/group/"+SCARLET_VRCHAT_GROUP_ID,

        USER_AGENT_NAME = GROUP+"-"+NAME,
        USER_AGENT = USER_AGENT_NAME+"/"+VERSION+" "+DEV_DISCORD+"; "+SCARLET_DISCORD_URL+"; "+GITHUB_URL,
        LICENSE_URL = GITHUB_URL+"?tab=MIT-1-ov-file",
        // Single endpoint that carries both update-version metadata and the
        // optional broadcast announcement. See {@link ScarletMeta}.
        META_URL = "https://raw.githubusercontent.com/"+FORK_GROUP+"/"+FORK_REPOSITORY+"/main/meta.json",
        
        COMMUNITY_URL = "https://vrchat.community/",
        COMMUNITY_GITHUB_URL = "https://github.com/vrchatapi",
        VRCHAT_API_RELEASES_URL = VrchatApiVersionChecker.PROJECT_URL,
        
        API_VERSION = "api/1",
        API_HOST_0 = "vrchat.com",
        API_URL_0  = "https://"+API_HOST_0+"/",
        API_BASE_0 = API_URL_0+API_VERSION,
        API_HOST_1 = "api.vrchat.com",
        API_URL_1  = "https://"+API_HOST_1+"/",
        API_BASE_1 = API_URL_1+API_VERSION,
        API_HOST_2 = "api.vrchat.cloud",
        API_URL_2  = "https://"+API_HOST_2+"/",
        API_BASE_2 = API_URL_2+API_VERSION;

    private static String appVersion()
    {
        Package pkg = Scarlet.class.getPackage();
        if (pkg != null)
        {
            String implementationVersion = pkg.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.trim().isEmpty())
                return implementationVersion.trim();
        }
        return "0.4.20";
    }

    public static void main(String[] args) throws Exception
    {
        launchArgs = args == null ? new String[0] : args.clone();
        Thread.setDefaultUncaughtExceptionHandler(Scarlet::uncaughtException);
        int exitCode = 0;
        try
        {
            try
            {
                if (Platform.isPowerPC() && Platform.isPpcEdition())
                    LOG.warn("PowerPC edition on a PowerPC CPU: skipping startup security self-tests (their PBKDF2 derivations take many minutes without a JIT). Real encryption is unchanged.");
                else
                    SecurityRegressionChecks.runAll();
                try (Scarlet scarlet = new Scarlet())
                {
                    scarlet.run();
                    exitCode = scarlet.exitCode;
                }
            }
            catch (Throwable t)
            {
                exitCode = -1;
                LOG.error("Exception in main", t);
            }
        }
        finally
        {
            if (exitCode == 69 && !Boolean.getBoolean("scarlet.restart.externalOnly") && relaunchSelf())
                exitCode = 0;
            System.exit(exitCode);
        }
    }

    private static volatile String[] launchArgs = new String[0];

    private static boolean relaunchSelf()
    {
        List<String> command;
        try
        {
            command = buildRelaunchCommand();
        }
        catch (Exception ex)
        {
            LOG.error("Could not build Scarlet restart command", ex);
            return false;
        }
        try
        {
            ProcLock.releaseAll();
            new ProcessBuilder(command)
                .directory(new File(System.getProperty("user.dir")))
                .inheritIO()
                .start();
            LOG.info("Started Scarlet restart process");
            return true;
        }
        catch (Exception ex)
        {
            LOG.error("Could not start Scarlet restart process: {}", command, ex);
            return false;
        }
    }

    private static List<String> buildRelaunchCommand()
    {
        List<String> command = new ArrayList<>();
        command.add(javaExecutableForRelaunch());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());

        Path jar = MavenDepsLoader.jarPath();
        if (jar != null && Files.isRegularFile(jar))
        {
            command.add("-jar");
            command.add(jar.toAbsolutePath().toString());
        }
        else
        {
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(mainClassForRelaunch());
        }
        command.addAll(Arrays.asList(launchArgs));
        return command;
    }

    private static String javaExecutableForRelaunch()
    {
        File bin = new File(System.getProperty("java.home"), "bin");
        String exe = "java";
        if (Platform.CURRENT == Platform.NT)
            exe = !GraphicsEnvironment.isHeadless() && System.console() == null ? "javaw.exe" : "java.exe";
        File java = new File(bin, exe);
        if (!java.isFile() && exe.endsWith("w.exe"))
            java = new File(bin, "java.exe");
        if (!java.isFile())
            java = new File(bin, "java");
        return java.getAbsolutePath();
    }

    private static String mainClassForRelaunch()
    {
        String command = System.getProperty("sun.java.command");
        if (command != null)
        {
            command = command.trim();
            int space = command.indexOf(' ');
            String main = space < 0 ? command : command.substring(0, space);
            if (!main.isEmpty() && !main.toLowerCase().endsWith(".jar"))
                return main;
        }
        return Scarlet.class.getName();
    }


    public static final File user_home = new File(System.getProperty("user.home"));
    public static final File dir, LEGACY_DIR;
    static final DataFolderMigrationResult DATA_FOLDER_MIGRATION;
    static
    {
        String scarletHome = System.getenv("SCARLET_HOME"),
               localappdata = System.getenv("LOCALAPPDATA"),
               xdgDataHome = System.getenv("XDG_DATA_HOME");
        scarletHome = System.getProperty("SCARLET_HOME", scarletHome);
        
        File dir0;
        File legacyDir0 = null;
        DataFolderMigrationResult migration0 = DataFolderMigrationResult.notNeeded();
        boolean explicitHome = scarletHome != null && !scarletHome.trim().isEmpty() && !";".equals(scarletHome.trim());
        if (explicitHome)
        {
            // SCARLET_HOME is explicitly set
            dir0 = new File(scarletHome).getAbsoluteFile();
        }
        else if (";".equals(scarletHome != null ? scarletHome.trim() : null) && MavenDepsLoader.jarPath() != null)
        {
            // SCARLET_HOME=";" means use jar directory
            dir0 = MavenDepsLoader.jarPath().getParent().toFile();
        }
        else
        {
            dir0 = defaultDataDir(GROUP, NAME, localappdata, xdgDataHome);
            legacyDir0 = defaultDataDir(LEGACY_GROUP, NAME, localappdata, xdgDataHome);
            migration0 = copyLegacyDataDir(legacyDir0, dir0);
        }

        // Data-folder auto-discovery: if the resolved folder has no usable settings.json but a
        // real config exists in another known data location (an older default, the legacy-group
        // folder, an old Windows-style path, or the jar dir), adopt THAT folder instead of
        // silently booting a blank config. This is what makes Scarlet "find what works" when the
        // data dir has drifted between versions or launchers. An explicit SCARLET_HOME path is
        // always respected and never overridden. Fully guarded so a bad candidate cannot break
        // static initialisation.
        if (!explicitHome && !settingsFileUsable(dir0))
        {
            java.util.List<File> discovered = findAllUsableDataDirs(dir0, localappdata, xdgDataHome);
            if (!discovered.isEmpty())
            {
                // One usable folder: adopt it silently. More than one: ask the user which to load,
                // so a config split across data locations isn't silently resolved to the wrong one.
                File chosen = discovered.size() == 1 ? discovered.get(0) : chooseDataDir(discovered);
                if (chosen != null && !chosen.equals(dir0))
                {
                    System.out.println("[Scarlet] Data folder " + dir0 + " has no usable settings.json; using config at " + chosen
                        + (discovered.size() > 1 ? " (chosen from " + discovered.size() + " candidates)" : ""));
                    dir0 = chosen;
                }
            }
        }

        if (!dir0.isDirectory())
        {
            if (!dir0.mkdirs())
            {
                System.err.println("Failed to create directory: " + dir0);
            }
        }
        dir = dir0;
        LEGACY_DIR = legacyDir0;
        DATA_FOLDER_MIGRATION = migration0;
        System.out.println("[Scarlet] Data folder resolved to: " + dir0.getAbsolutePath());
    }

    static File defaultDataDir(String group, String name, String localappdata, String xdgDataHome)
    {
        if (Platform.CURRENT == Platform.$NIX)
        {
            if (xdgDataHome != null && !xdgDataHome.trim().isEmpty())
                return new File(xdgDataHome, group+"/"+name);
            return new File(user_home, ".local/share/"+group+"/"+name);
        }
        if (localappdata != null)
            return new File(localappdata, group+"/"+name);
        if (Platform.CURRENT == Platform.NT)
            return new File(user_home, "AppData/Local/"+group+"/"+name);
        return new File(user_home, "."+group+"/"+name);
    }

    /**
     * True when {@code dir/settings.json} exists and parses to a non-empty JSON object, i.e. it
     * holds a real, loadable config (not missing, not empty, not corrupt). Used by data-folder
     * auto-discovery. Never throws. Uses JsonParser directly so it is independent of static field
     * init order.
     */
    static boolean settingsFileUsable(File dir)
    {
        if (dir == null)
            return false;
        File f = new File(dir, "settings.json");
        if (!f.isFile() || f.length() <= 0L)
            return false;
        try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))
        {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseReader(r);
            return el != null && el.isJsonObject() && el.getAsJsonObject().size() > 0;
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    /**
     * Scans the known Scarlet data locations (the current group, every platform-native
     * variant, and the jar directory) for the first one holding a usable settings.json, so a
     * config that ended up in a different folder than the one currently resolved still gets used.
     * Returns null if none is found. Never throws.
     */
    static File findUsableDataDir(File primary, String localappdata, String xdgDataHome)
    {
        try
        {
            java.util.List<File> candidates = new java.util.ArrayList<>();
            // Current group only: the legacy group is handled by the consent-gated migration
            // prompt (copyLegacyDataDir), so silent discovery must not override a "Start fresh"
            // choice by re-adopting the legacy folder. Discovery's job is same-group drift
            // (e.g. an old Windows-style path used on Linux by earlier builds).
            for (String g : new String[] { GROUP })
            {
                if (xdgDataHome != null && !xdgDataHome.trim().isEmpty())
                    candidates.add(new File(xdgDataHome, g+"/"+NAME));
                candidates.add(new File(user_home, ".local/share/"+g+"/"+NAME));
                if (localappdata != null)
                    candidates.add(new File(localappdata, g+"/"+NAME));
                candidates.add(new File(user_home, "AppData/Local/"+g+"/"+NAME));
                candidates.add(new File(user_home, "."+g+"/"+NAME));
            }
            // Bare ~/Scarlet, used by older SCARLET_HOME layouts / early builds.
            candidates.add(new File(user_home, NAME));
            try
            {
                if (MavenDepsLoader.jarPath() != null)
                    candidates.add(MavenDepsLoader.jarPath().getParent().toFile());
            }
            catch (Exception ignore) {}
            for (File c : candidates)
                if (c != null && !c.equals(primary) && settingsFileUsable(c))
                    return c;
        }
        catch (Exception ex)
        {
            System.err.println("[Scarlet] Data folder discovery failed: " + ex);
        }
        return null;
    }

    /**
     * Like {@link #findUsableDataDir}, but returns EVERY known data location holding a usable
     * settings.json (deduplicated by canonical path, excluding {@code primary}). Used to offer the
     * user a choice when a config is split across more than one data folder.
     */
    static java.util.List<File> findAllUsableDataDirs(File primary, String localappdata, String xdgDataHome)
    {
        java.util.LinkedHashMap<String, File> byPath = new java.util.LinkedHashMap<>();
        try
        {
            String primaryKey = canonicalKey(primary);
            java.util.List<File> candidates = new java.util.ArrayList<>();
            for (String g : new String[] { GROUP })
            {
                if (xdgDataHome != null && !xdgDataHome.trim().isEmpty())
                    candidates.add(new File(xdgDataHome, g+"/"+NAME));
                candidates.add(new File(user_home, ".local/share/"+g+"/"+NAME));
                if (localappdata != null)
                    candidates.add(new File(localappdata, g+"/"+NAME));
                candidates.add(new File(user_home, "AppData/Local/"+g+"/"+NAME));
                candidates.add(new File(user_home, "."+g+"/"+NAME));
            }
            // Bare ~/Scarlet, used by older SCARLET_HOME layouts / early builds.
            candidates.add(new File(user_home, NAME));
            try
            {
                if (MavenDepsLoader.jarPath() != null)
                    candidates.add(MavenDepsLoader.jarPath().getParent().toFile());
            }
            catch (Exception ignore) {}
            for (File c : candidates)
            {
                if (c == null || !settingsFileUsable(c))
                    continue;
                String key = canonicalKey(c);
                if (key.equals(primaryKey))
                    continue;
                byPath.putIfAbsent(key, c);
            }
        }
        catch (Exception ex)
        {
            System.err.println("[Scarlet] Data folder discovery failed: " + ex);
        }
        return new java.util.ArrayList<>(byPath.values());
    }

    static String canonicalKey(File f)
    {
        if (f == null)
            return "";
        try { return f.getCanonicalPath(); } catch (Exception ex) { return f.getAbsolutePath(); }
    }

    /**
     * Prompts the user to pick which data folder to load when more than one usable config exists.
     * Falls back to the first candidate when headless / no input / cancelled, so startup never blocks.
     */
    static File chooseDataDir(java.util.List<File> candidates)
    {
        if (candidates == null || candidates.isEmpty())
            return null;
        if (candidates.size() == 1)
            return candidates.get(0);
        String message = "Scarlet found more than one data folder with a saved config.\n\nChoose which one to load:";
        try
        {
            if (!GraphicsEnvironment.isHeadless())
            {
                String[] labels = new String[candidates.size()];
                for (int i = 0; i < labels.length; i++)
                    labels[i] = candidates.get(i).getAbsolutePath();
                Object sel = JOptionPane.showInputDialog(
                    null,
                    Swing.dialogMessage(message),
                    "Multiple Scarlet data folders",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    labels,
                    labels[0]);
                if (sel != null)
                    for (File c : candidates)
                        if (c.getAbsolutePath().equals(sel))
                            return c;
                return candidates.get(0);
            }
            StringBuilder sb = new StringBuilder(message.replace("\n", System.lineSeparator())).append(System.lineSeparator());
            for (int i = 0; i < candidates.size(); i++)
                sb.append("  ").append(i + 1).append(") ").append(candidates.get(i).getAbsolutePath()).append(System.lineSeparator());
            Console console = System.console();
            String answer = null;
            if (console != null)
            {
                answer = console.readLine("%sSelect [1-%d] (default 1): ", sb.toString(), candidates.size());
            }
            else
            {
                // No interactive console (e.g. a headless service with stdin detached or
                // piped from /dev/null): never risk a blocking stdin read here \u2014 log the
                // folders and deterministically default to the first candidate.
                System.out.println(sb + "No interactive console; defaulting to the first data folder: " + candidates.get(0).getAbsolutePath());
            }
            if (answer != null)
            {
                int idx = Integer.parseInt(answer.trim()) - 1;
                if (idx >= 0 && idx < candidates.size())
                    return candidates.get(idx);
            }
        }
        catch (Exception ex)
        {
            System.err.println("[Scarlet] Data folder chooser failed, defaulting to first: " + ex);
        }
        return candidates.get(0);
    }

    static String legacyGroupName()
    {
        return new String(new char[] { 'S', 'y', 'b', 'y', 'l', 'i', 'n', 'e', 'N', 'e', 't', 'w', 'o', 'r', 'k' });
    }

    static DataFolderMigrationResult copyLegacyDataDir(File legacyDir, File newDir)
    {
        if (legacyDir == null || newDir == null || legacyDir.equals(newDir) || !legacyDir.isDirectory())
            return DataFolderMigrationResult.notNeeded();
        if (isDataFolderNoticeAcknowledged(newDir))
            return DataFolderMigrationResult.notNeeded();
        try
        {
            if (newDir.exists())
            {
                if (!newDir.isDirectory())
                    return DataFolderMigrationResult.failed(legacyDir, newDir, "The KozyBlake data path already exists but is not a directory.");
                if (!isDirectoryEmpty(newDir))
                    return DataFolderMigrationResult.skippedExistingTarget(legacyDir, newDir);
            }
            else
            {
                Files.createDirectories(newDir.toPath());
            }
            if (!confirmLegacyDataTransfer(legacyDir, newDir))
            {
                DataFolderMigrationResult declined = DataFolderMigrationResult.declined(legacyDir, newDir);
                acknowledgeDataFolderNotice(newDir, declined);
                return declined;
            }
            copyDirectoryTree(legacyDir.toPath(), newDir.toPath());
            DataFolderMigrationResult copied = DataFolderMigrationResult.copied(legacyDir, newDir);
            acknowledgeDataFolderNotice(newDir, copied);
            return copied;
        }
        catch (Exception ex)
        {
            DataFolderMigrationResult failed = DataFolderMigrationResult.failed(legacyDir, newDir, ex.toString());
            showDataFolderTransferFailure(failed);
            acknowledgeDataFolderNotice(newDir, failed);
            return failed;
        }
    }

    static boolean confirmLegacyDataTransfer(File legacyDir, File newDir)
    {
        return confirmLegacyDataTransfer(legacyDir, newDir, false);
    }

    static boolean confirmLegacyDataTransfer(File legacyDir, File newDir, boolean dryRun)
    {
        String message = dataFolderTransferPromptMessage(legacyDir, newDir, dryRun);
        if (!GraphicsEnvironment.isHeadless())
        {
            Object[] options = { "Transfer data", "Start fresh" };
            int result = JOptionPane.showOptionDialog(
                null,
                Swing.dialogMessage(message),
                "Transfer Data to KozyBlake/Scarlet?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);
            return result == JOptionPane.YES_OPTION;
        }
        Console console = System.console();
        if (console != null)
            return "y".equalsIgnoreCase(console.readLine("%s%nTransfer data? (y/n): ", message).trim());
        System.out.println(message);
        System.out.print("Transfer data? (y/n): ");
        @SuppressWarnings("resource")
        Scanner scanner = new Scanner(System.in);
        return "y".equalsIgnoreCase(scanner.nextLine().trim());
    }

    static String dataFolderTransferPromptMessage(File legacyDir, File newDir)
    {
        return dataFolderTransferPromptMessage(legacyDir, newDir, false);
    }

    static String dataFolderTransferPromptMessage(File legacyDir, File newDir, boolean dryRun)
    {
        return (dryRun ? "[CLI popup test: no data will be copied and no choice will be saved.]\n\n" : "") +
            "KozyBlake/Scarlet uses a separate data folder from the original repo.\n\n" +
            "Would you like to transfer your existing original-repo data into the KozyBlake/Scarlet folder before startup continues?\n\n" +
            "Existing data folder:\n" +
            pathOrUnavailable(legacyDir) + "\n\n" +
            "KozyBlake/Scarlet data folder:\n" +
            pathOrUnavailable(newDir) + "\n\n" +
            "This will copy your data into the KozyBlake/Scarlet folder. The existing folder will not be moved, renamed, or deleted.\n\n" +
            "Heads up: transferring can cause future issues because this fork and the original repo will use different data folders. " +
            "If you switch back to the original repo later, changes made in the KozyBlake/Scarlet folder will not sync back automatically, " +
            "and you will more than likely need to set everything up again there.\n\n" +
            (dryRun ? "This test will only report your choice." : "KozyBlake/Scarlet will remember this choice and will not ask again.");
    }

    static void showDataFolderTransferFailure(DataFolderMigrationResult migration)
    {
        String message =
            "KozyBlake/Scarlet could not transfer your data into the KozyBlake/Scarlet folder.\n\n" +
            "Existing data folder:\n" +
            pathOrUnavailable(migration.legacyDir) + "\n\n" +
            "KozyBlake/Scarlet data folder:\n" +
            pathOrUnavailable(migration.newDir) + "\n\n" +
            "Error:\n" +
            (migration.error == null ? "unknown" : migration.error) + "\n\n" +
            "Startup will continue using the KozyBlake/Scarlet folder.";
        if (!GraphicsEnvironment.isHeadless())
        {
            JOptionPane.showMessageDialog(null, Swing.dialogMessage(message), "KozyBlake/Scarlet Data Transfer Failed", JOptionPane.WARNING_MESSAGE);
        }
        else
        {
            System.err.println();
            System.err.println("KozyBlake/Scarlet Data Transfer Failed");
            System.err.println(message);
            System.err.println();
        }
    }

    static void copyDirectoryTree(Path sourceRoot, Path targetRoot) throws IOException
    {
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
            {
                Path target = targetRoot.resolve(sourceRoot.relativize(dir));
                if (!Files.isDirectory(target))
                    Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Path target = targetRoot.resolve(sourceRoot.relativize(file));
                copyFileKeepingAttributesWhenPossible(file, target);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void copyFileKeepingAttributesWhenPossible(Path source, Path target) throws IOException
    {
        try
        {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        catch (UnsupportedOperationException ex)
        {
            Files.copy(source, target);
        }
    }

    static boolean isDirectoryEmpty(File dir) throws IOException
    {
        try (Stream<Path> children = Files.list(dir.toPath()))
        {
            return !children.findAny().isPresent();
        }
    }

    static final class DataFolderMigrationResult
    {
        enum Kind
        {
            NOT_NEEDED,
            COPIED,
            DECLINED,
            SKIPPED_EXISTING_TARGET,
            FAILED
        }

        static DataFolderMigrationResult notNeeded()
        {
            return new DataFolderMigrationResult(Kind.NOT_NEEDED, null, null, null);
        }

        static DataFolderMigrationResult copied(File legacyDir, File newDir)
        {
            return new DataFolderMigrationResult(Kind.COPIED, legacyDir, newDir, null);
        }

        static DataFolderMigrationResult declined(File legacyDir, File newDir)
        {
            return new DataFolderMigrationResult(Kind.DECLINED, legacyDir, newDir, null);
        }

        static DataFolderMigrationResult skippedExistingTarget(File legacyDir, File newDir)
        {
            return new DataFolderMigrationResult(Kind.SKIPPED_EXISTING_TARGET, legacyDir, newDir, null);
        }

        static DataFolderMigrationResult failed(File legacyDir, File newDir, String error)
        {
            return new DataFolderMigrationResult(Kind.FAILED, legacyDir, newDir, error);
        }

        DataFolderMigrationResult(Kind kind, File legacyDir, File newDir, String error)
        {
            this.kind = kind;
            this.legacyDir = legacyDir;
            this.newDir = newDir;
            this.error = error;
        }

        final Kind kind;
        final File legacyDir, newDir;
        final String error;

        boolean shouldNotify()
        {
            return this.kind != Kind.NOT_NEEDED;
        }
    }
    public static final Logger LOG = LoggerFactory.getLogger("Scarlet");

    static void uncaughtException(Thread t, Throwable e)
    {
        LOG.error("Uncaught exception in thread: "+t, e);
    }

    public static final Gson GSON, GSON_PRETTY;
    static
    {
        GsonBuilder gb = JsonAdapters.gson();
        GSON = gb.create();
        GSON_PRETTY = gb.setPrettyPrinting().create();
        LOG.info(String.format("App: %s %s", APP_NAME, VERSION));
        LOG.info(String.format("OS: %s (%s)", System.getProperty("os.name"), System.getProperty("os.arch")));
        LOG.info(String.format("VM: %s %s (%s) %s-bit", System.getProperty("java.version"), System.getProperty("java.vendor"), System.getProperty("java.vm.name"), System.getProperty("sun.arch.data.model")));
        LOG.info(String.format("Platform: %s", Platform.describe()));
    }

    public Scarlet() throws IOException
    {
    }

    {
        // absolute initialization, even before explicit constructor body
    }

    public boolean stop()
    {
        return this.stop("shutdown", 0);
    }
    public boolean stopAfterMigrationImport()
    {
        this.skipShutdownPersistence = true;
        return this.stop("migration import", 0);
    }
    public boolean requestDataWipeOnShutdown()
    {
        this.wipeDataOnShutdown = true;
        this.skipShutdownPersistence = true;
        return this.stop("migration move-out wipe", 0);
    }
    public boolean restart()
    {
        try (FileWriter fw = new FileWriter("scarlet.version"))
        {
            fw.append(VERSION).flush();
        }
        catch (IOException ioex)
        {
            LOG.error("Exception writing to scarlet.version for version `"+VERSION+"`", ioex);
        }
        return this.stop("restart", 69);
    }
    public int update(String targetVersion)
    {
        if (!MiscUtils.isValidVersion(targetVersion))
            return 1;
        if (this.allVersions.length == 0)
            this.refreshAllVersions();
        if (Arrays.stream(this.allVersions).noneMatch(targetVersion::equals))
            return 2;
        try (FileWriter fw = new FileWriter("scarlet.version.target"))
        {
            fw.append(targetVersion).flush();
        }
        catch (IOException ioex)
        {
            LOG.error("Exception writing to scarlet.version.target for target verstion `"+targetVersion+"`", ioex);
            return 3;
        }
        return this.stop("update", 70) ? 0 : -1;
    }
    public synchronized boolean stop(String kind, int exitCode)
    {
        if (!this.running)
            return false;
        LOG.info("Queuing "+kind+"...");
        this.running = false;
        this.exitCode = exitCode;
        return true;
    }
    boolean shouldPersistOnShutdown()
    {
        return !this.skipShutdownPersistence;
    }

    @Override
    public void close() throws IOException
    {
        this.stop();
        this.exec.shutdown();
        this.execModal.shutdown();
        this.execIPC.shutdown();
        try
        {
            if (!this.exec.awaitTermination(3_000L, TimeUnit.MILLISECONDS))
            {
                int unstarted = this.exec.shutdownNow().size();
                LOG.error("Forcibly terminated executor service, "+unstarted+" unstarted task(s)");
            }
        }
        catch (InterruptedException iex)
        {
        }
        try
        {
            if (!this.execModal.awaitTermination(3_000L, TimeUnit.MILLISECONDS))
            {
                int unstarted = this.execModal.shutdownNow().size();
                LOG.error("Forcibly terminated modal executor service, "+unstarted+" unstarted task(s)");
            }
        }
        catch (InterruptedException iex)
        {
        }
        try
        {
            if (!this.execIPC.awaitTermination(3_000L, TimeUnit.MILLISECONDS))
            {
                int unstarted = this.execIPC.shutdownNow().size();
                LOG.error("Forcibly terminated modal executor service, "+unstarted+" unstarted task(s)");
            }
        }
        catch (InterruptedException iex)
        {
        }
        if (this.ttsService != null)
            MiscUtils.close(this.ttsService);
        MiscUtils.close(this.mobile);
        MiscUtils.close(this.discord);
        MiscUtils.close(this.logs);
        MiscUtils.close(this.ui);
        if (this.shouldPersistOnShutdown())
        {
            this.data.saveAll();
            this.settings.updateRunVersionAndTime();
        }
        else
        {
            LOG.warn("Skipping shutdown persistence because a migration bundle was imported and must remain intact for restart");
        }
        // Move-out wipe runs last: executors are drained and logs are closed, so nothing can
        // re-create the data we delete. Uses stderr/stdout since the logging system is closed.
        if (this.wipeDataOnShutdown)
        {
            try
            {
                String summary = ScarletMigration.wipeLocalInstall();
                System.out.println("Scarlet move-out: removed this computer's data and credentials (" + summary + ").");
            }
            catch (Exception ex)
            {
                System.err.println("Scarlet move-out wipe failed: " + ex);
            }
        }
        LOG.info("Finished shutdown flow");
    }

    final IScarletUISplash splash = IScarletUISplash.create(this);

    volatile boolean running = true;
    volatile int exitCode = 0;
    volatile boolean skipShutdownPersistence = false;
    volatile boolean wipeDataOnShutdown = false;
    boolean staffMode = false;
    final String ipcAuthToken = this.loadOrCreateIpcAuthToken();
    // Whether System.in is a usable, readable handle.  Set to false the first
    // time FileInputStream.available() throws IOException — happens on Windows
    // when Scarlet is launched without an attached console (javaw.exe, double-
    // clicked JAR, Windows shortcut to javaw, Task Scheduler without an
    // interactive session, any "start /b" or detached launch).  Once flipped,
    // spin() skips the CLI reader entirely so the 100ms polling loop doesn't
    // spam "The handle is invalid" into the log ten times a second.
    private volatile boolean stdinUsable = true;
    final Runnable explicitGC = MiscUtils.withMinimumInterval(3600_000L, System::gc);
    final AtomicInteger threadidx = new AtomicInteger();
    public final ScheduledExecutorService exec = Executors.newScheduledThreadPool(4, runnable -> new Thread(runnable, "KozyBlake/Scarlet Worker Thread "+this.threadidx.incrementAndGet())),
                                          execModal = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "KozyBlake/Scarlet Modal UI Thread "+this.threadidx.incrementAndGet())),
                                          execIPC = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "KozyBlake/Scarlet IPC Thread "+this.threadidx.incrementAndGet()));
    
    // ── Multi-core data directory ───────────────────────────────────────────────
    /** Scarlet's data directory (settings, watch lists, tags, session store, Discord config, etc.). */
    public final File dataDir = dir;

    final ScarletSettings settings = new ScarletSettings(this, new File(dataDir, "settings.json"));
    {
        if (!Platform.forceHeadlessUi())
        {
            Float uiScale = this.settings.getObject("ui_scale", Float.class);
            if (uiScale != null)
            {
                Swing.scaleAll(uiScale.floatValue());
            }
            else
            {
                // No manual override — try to auto-detect from desktop environment on Linux
                Float autoScale = Swing.detectLinuxUIScale();
                if (autoScale != null)
                    Swing.scaleAll(autoScale);
            }
        }
        String groupId = this.resolveStartupGroupLockId();
        if (groupId != null)
        {
            if (!ProcLock.tryLock(new File(user_home, ".scarlet."+groupId+".lock")))
                throw new IllegalStateException("Duplicate processes detected for group "+groupId);
        }
    }
    private String resolveStartupGroupLockId()
    {
        String configuredGroupId = this.settings.getString("vrchat_group_id");
        if (configuredGroupId == null)
            return null;
        String resolvedGroupId = VrcIds.resolveGroupId(configuredGroupId);
        if (resolvedGroupId == null)
            return null;
        resolvedGroupId = resolvedGroupId.trim();
        if (resolvedGroupId.isEmpty())
            return null;
        if (VrcIds.id_group.matcher(resolvedGroupId).matches())
            return resolvedGroupId;
        LOG.warn("Ignoring invalid VRChat group id setting while creating the startup process lock: `{}` resolved to `{}`", configuredGroupId, resolvedGroupId);
        this.settings.getJson().remove("vrchat_group_id");
        this.settings.saveJson();
        return null;
    }
    // Set up translations before any UI is built. The lang/ folder lets translators
    // add or fix languages (dropping in messages_<lang>.properties) without a rebuild,
    // and receives an up-to-date English template. Then apply the language: blank/
    // "system" follows the OS language (I18n's default); a specific code overrides it.
    // Read from the settings JSON directly because this runs ahead of the FileValued
    // uiLanguage field, which is initialized later.
    {
        net.sybyline.scarlet.util.I18n.setExternalDir(new File(dir, "lang"));
        // Validate community translation files immediately so a broken {0} placeholder
        // or bad '' escaping is a startup log line, not a runtime surprise in a
        // language the maintainer can't read.
        net.sybyline.scarlet.util.I18n.lintExternalAndLog();
        String lang = this.settings.getString("ui_language");
        if (lang != null && !lang.trim().isEmpty() && !"system".equalsIgnoreCase(lang.trim()))
            net.sybyline.scarlet.util.I18n.setLocale(new java.util.Locale(lang.trim().toLowerCase(java.util.Locale.ROOT)));
        Swing.applyLocaleFonts(net.sybyline.scarlet.util.I18n.getLocale());
    }
    final IScarletUI ui = IScarletUI.create(this);
    final ScarletEventListener eventListener = new ScarletEventListener(this);
    final ScarletPendingModActions pendingModActions = new ScarletPendingModActions(this, new File(dataDir, "pending_moderation_actions.json"));
    final ScarletModerationTags moderationTags = new ScarletModerationTags(new File(dataDir, "moderation_tags.json"));
    final ScarletWatchedGroups watchedGroups = new ScarletWatchedGroups(new File(dataDir, "watched_groups.json"));
    /** Shared pronoun allow/deny lists — read by PronounValidator on every check. Public so the validator can access it statically. */
    public static ScarletPronounLists pronounLists;
    {
        // Initialise the static reference so PronounValidator can reach it without
        // needing a Scarlet instance passed through the call chain. Skipped when
        // the pronoun-validation subsystem is disabled.
        if (Features.PRONOUNS_ENABLED)
        {
            Scarlet.pronounLists = new ScarletPronounLists(
                new File(dir, "good_pronoun.json"),
                new File(dir, "bad_pronoun.json")
            );
        }
    }
    final ScarletWatchedEntities<User> watchedUsers = new ScarletWatchedEntities<>(new File(dataDir, "watched_users.json"), VrcIds.id_user, (user, id, embed) ->
    {
        if (user == null)
        {
            embed.setAuthor(id, "https://vrchat.com/home/user/"+id);
            return;
        }
        String userDisplayName = MarkdownSanitizer.escape(user.getDisplayName()),
               userIcon = MiscUtils.nonBlankOrNull(user.getIconUrl()),
               userThumbnail = MiscUtils.nonBlankOrNull(user.getIconUrl());
        embed.setAuthor(userDisplayName, "https://vrchat.com/home/user/"+id, userIcon);
        if (userThumbnail != null)
        {
            embed.setThumbnail(userThumbnail);
        }
    });
    final ScarletWatchedEntities<Avatar> watchedAvatars = new ScarletWatchedEntities<>(new File(dataDir, "watched_avatars.json"), VrcIds.id_avatar, (avatar, id, embed) ->
    {
        if (avatar == null)
        {
            embed.setTitle(id, "https://vrchat.com/home/avatar/"+id);
            return;
        }
        embed.setTitle(MarkdownSanitizer.escape(avatar.getName()), "https://vrchat.com/home/avatar/"+id);
        User author = this.vrc.getUser(avatar.getAuthorId());
        String authorName = MarkdownSanitizer.escape(author != null ? author.getDisplayName() : avatar.getAuthorName()),
               authorIcon = author != null && !MiscUtils.blank(author.getIconUrl()) ? author.getIconUrl() : null;
        embed.setAuthor(authorName, "https://vrchat.com/home/user/"+avatar.getAuthorId(), authorIcon);
        if (!MiscUtils.blank(avatar.getThumbnailImageUrl()))
        {
            embed.setThumbnail(avatar.getThumbnailImageUrl());
        }
    });
    final ScarletStaffList staffList = new ScarletStaffList(new File(dataDir, "staff_list.json"));
    final ScarletSecretStaffList secretStaffList = new ScarletSecretStaffList(new File(dataDir, "secret_staff_list.json"));
    final ScarletVRChatReportTemplate vrcReport = new ScarletVRChatReportTemplate(new File(dataDir, "report_template.txt"));
    final ScarletData data = new ScarletData(new File(dataDir, "data"));
    final ScarletVRChat vrc = new ScarletVRChat(this, "global", new File(dataDir, "store.bin"));
    final ScarletMobile mobile = new ScarletMobile(this, new File(dataDir, "mobile_devices.json"));
    final ScarletDiscord discord = new ScarletDiscordJDA(this, new File(dataDir, "discord_bot.json"), new File(dataDir, "discord_perms.json"));
    private TtsService ttsService = null;
    final ScarletCalendar calendar = new ScarletCalendar(this, new File(dataDir, "event_schedule.json"));
    final ScarletVRChatLogs logs = new ScarletVRChatLogs(this.eventListener);
    final ScarletCacheCleanup cacheCleanup = new ScarletCacheCleanup(this);
    String[] last25logs = new String[0];
    final ScarletSettings.FileValued<Boolean> confirmGroupInvite = this.settings.new FileValuedBoolean("ui_confirm_group_invite", I18n.tr("setting.ui_confirm_group_invite"), false),
                                     alertForUpdates = this.settings.new FileValuedBoolean("ui_alert_update", I18n.tr("setting.ui_alert_update"), true),
                                     alertForPreviewUpdates = this.settings.new FileValuedBoolean("ui_alert_update_preview", I18n.tr("setting.ui_alert_update_preview"), true),
                                     alertForAnnouncements = this.settings.new FileValuedBoolean("ui_alert_announcement", I18n.tr("setting.ui_alert_announcement"), true),
                                     showUiDuringLoad = this.settings.new FileValuedBoolean("ui_show_during_load", I18n.tr("setting.ui_show_during_load"), false),
                                     showCliTab = this.settings.new FileValuedBoolean("ui_show_cli_tab", I18n.tr("setting.ui_show_cli_tab"), true),
                                     discordKickBanEnabled = this.settings.new FileValuedBoolean("discord_kick_ban_enabled", I18n.tr("setting.discord_kick_ban_enabled"), false),
                                     discordKickBanPrompted = this.settings.new FileValuedBoolean("discord_kick_ban_prompted", I18n.tr("setting.discord_kick_ban_prompted"), false),
                                     autoInviteOnVerify = this.settings.new FileValuedBoolean("auto_invite_group_on_verify", I18n.tr("setting.auto_invite_group_on_verify"), true),
                                     announceVerifyComplete = this.settings.new FileValuedBoolean("announce_verify_complete", I18n.tr("setting.announce_verify_complete"), true),
                                     trainingMode = this.settings.new FileValuedBoolean("training_mode_enabled", I18n.tr("setting.training_mode_enabled"), false),
                                     uiAccentHeaders = this.settings.new FileValuedBoolean("ui_accent_headers", I18n.tr("setting.ui_accent_headers"), false);
    /** One-time first-launch flag: the user has seen and accepted the "vibe-coded / AI-assisted" disclosure. */
    final ScarletSettings.FileValued<Boolean> vibecodedNoticeAcknowledged = this.settings.new FileValuedBoolean("vibecoded_notice_acknowledged", I18n.tr("setting.vibecoded_notice_acknowledged"), false);
    /** Desktop UI language override; blank/"system" follows the operating system language. Applied at startup (restart to change). */
    final ScarletSettings.FileValued<String> uiLanguage = this.settings.new FileValuedStringChoice("ui_language", I18n.tr("setting.ui_language"), "system", () ->
    {
        // "system" (follow the OS language) plus every language we have a translation for.
        java.util.List<String> langs = new java.util.ArrayList<>();
        langs.add("system");
        langs.addAll(I18n.availableLanguages());
        return langs;
    });
    final ScarletSettings.FileValued<String> verifiedRoleSf = this.settings.new FileValuedStringPattern("verified_role_snowflake", I18n.tr("setting.verified_role_snowflake"), "1234567890", "\\d{1,20}", true);
    final ScarletSettings.FileValued<String> membersRoleSf = this.settings.new FileValuedStringPattern("members_role_snowflake", I18n.tr("setting.members_role_snowflake"), "1234567890", "\\d{1,20}", true);
    final ScarletSettings.FileValued<String> notVerifiedLinkMessage = this.settings.new FileValuedStringPattern("link_vrchat_manual_verify_message", I18n.tr("setting.link_vrchat_manual_verify_message"), "Please open a ticket so an administrator can verify you manually.", null, true);
    final ScarletSettings.FileValued<String> autoInviteGroupId = this.settings.new FileValuedStringPattern("auto_invite_group_id", I18n.tr("setting.auto_invite_group_id"), DEFAULT_AUTO_INVITE_GROUP_ID, VrcIds.P_ID_GROUP, true);
    final ScarletSettings.FileValued<EnforcementAgeState> enforceInstances18plus = this.settings.new FileValuedEnum<>("enforce_instances_18_plus", I18n.tr("setting.enforce_instances_18_plus"), EnforcementAgeState.DISABLED);
    final ScarletSettings.FileValued<EnforcementListState> enforceInstancesWorlds = this.settings.new FileValuedEnum<>("enforce_instances_worlds", I18n.tr("setting.enforce_instances_worlds"), EnforcementListState.DISABLED);
    final ScarletSettings.FileValued<String[]> enforceInstancesWorldList = this.settings.new FileValuedStringArrayPattern("enforce_instances_world_list", I18n.tr("setting.enforce_instances_world_list"), new String[0], VrcIds.P_ID_WORLD, true);
    final ScarletSettings.FileValued<Integer> autoCloseBotInstanceMinutes = this.settings.new FileValuedIntRange("auto_close_bot_instance_minutes", I18n.tr("setting.auto_close_bot_instance_minutes"), 0, 0, 1440);
    final ScarletSettings.FileValued<Integer> auditPollingInterval = this.settings.new FileValuedIntRange("audit_polling_interval", I18n.tr("setting.audit_polling_interval"), 60, 10, 300);
    final ScarletSettings.FileValued<Integer> auditCatchupMaxDays = this.settings.new FileValuedIntRange("audit_catchup_max_days", I18n.tr("setting.audit_catchup_max_days"), 14, 1, 90);
    // How often (seconds) to poll the group's live open instances so the follow-into-new-instance
    // feature reacts without waiting out the audit-log cadence + ingest lag. 0 disables the fast path
    // (falls back to audit-driven detection). Only polls at all when launch-on-instance-create is on.
    final ScarletSettings.FileValued<Integer> instanceFollowFastPollSeconds = this.settings.new FileValuedIntRange("instance_follow_fast_poll_seconds", I18n.tr("setting.instance_follow_fast_poll_seconds"), 30, 0, 300);
    // How far back /moderation-log reaches when consolidating a user's history. 0 = all recorded history.
    final ScarletSettings.FileValued<Integer> moderationLogLookbackDays = this.settings.new FileValuedIntRange("moderation_log_lookback_days", I18n.tr("setting.moderation_log_lookback_days"), 0, 0, 3650);
    /** How much to fade players who have left the instance, as a percent blended toward the background (0 = no dimming). */
    final ScarletSettings.FileValued<Integer> uiLeftPlayerDim = this.settings.new FileValuedIntRange("ui_left_player_dim_percent", I18n.tr("setting.ui_left_player_dim_percent"), 35, 0, 80);
    final ScarletSettings.FileValued<Void> addAltCreds = this.settings.new FileValuedVoid("Add alternate credentials", "Add", this.vrc::addAlternateCredentials),
                                  removeAltCreds = this.settings.new FileValuedVoid("Remove alternate credentials", "Remove", this.vrc::removeAlternateCredentials),
                                  listAltCreds = this.settings.new FileValuedVoid("List alternate credentials", "List", this.vrc::listAlternateCredentials),
                                  clearCreds = this.settings.new FileValuedVoid("Reset VRChat credentials", "Reset", this.vrc::clearCredentials),
                                  uiScale = this.settings.new FileValuedVoid("UI scale", "Set", this.ui::setUIScale),
                                  accentColor = this.settings.new FileValuedVoid("Accent colour", "Pick", () ->
                                  {
                                      net.sybyline.scarlet.ui.Swing.invokeWait(() ->
                                          net.sybyline.scarlet.ui.Swing.pickAccentColor(
                                              this.ui.getParentComponent(), this));
                                  }),
                                  runCacheCleanupNow = this.settings.new FileValuedVoid("Run cache cleanup now", "Run now", () ->
                                  {
                                      // Show a confirmation dialog listing eligible files before
                                      // deleting anything. Bypasses the active-use guard so it
                                      // works even while Scarlet is running with logs active.
                                      this.execModal.execute(this.cacheCleanup::promptAndCleanup);
                                  }),
                                  editGoodPronouns = this.settings.new FileValuedVoid("Edit good_pronoun.json", "Edit", () ->
                                  {
                                      MiscUtils.AWTDesktop.edit(Scarlet.pronounLists.goodFile);
                                  }),
                                  editBadPronouns = this.settings.new FileValuedVoid("Edit bad_pronoun.json", "Edit", () ->
                                  {
                                      MiscUtils.AWTDesktop.edit(Scarlet.pronounLists.badFile);
                                  }),
                                  reloadPronounLists = this.settings.new FileValuedVoid("Reload pronoun lists", "Reload", () ->
                                  {
                                      Scarlet.pronounLists.load();
                                      this.splash.queueFeedbackPopup(null, 3_000L,
                                          "Pronoun lists reloaded",
                                          pronounLists.goodSet.size() + " good, " + pronounLists.badSet.size() + " bad entries");
                                  }),
                                  installLinuxTtsVoices = this.settings.new FileValuedVoid("Install Linux TTS voices", "Install...", () ->
                                  {
                                      this.execModal.execute(() ->
                                          new TtsPackageInstallDialogs(Platform.$NIX, this.ui.getParentComponent())
                                              .showOptionalPackageInstallDialog());
                                  }),
                                  runCliCommand = this.settings.new FileValuedVoid("Run CLI command", "Run", () ->
                                  {
                                      this.settings.requireInputAsync("CLI command (type 'help' to list all commands)", false, cmd ->
                                      {
                                          if (cmd != null && !cmd.trim().isEmpty())
                                              this.exec.execute(() -> this.rawCommand(cmd.trim()));
                                      });
                                  });

    // Advisory translation — an optional, free, opt-in way to translate the advisory text of shared
    // watched-group lists (usually written in English) into the moderator's own language. Blank
    // endpoint = feature off; group names/ids/tags are never translated, and originals are kept so it
    // can be restored. Uses a LibreTranslate-compatible endpoint (self-hostable for full privacy).
    final ScarletSettings.FileValued<String> advisoryTranslateEndpoint = this.settings.new FileValuedStringPattern("advisory_translate_endpoint", I18n.tr("setting.advisory_translate_endpoint"), "", null, true);
    final ScarletSettings.FileValued<String> advisoryTranslateApiKey = this.settings.new FileValuedStringPattern("advisory_translate_api_key", I18n.tr("setting.advisory_translate_api_key"), "", null, true);
    final ScarletSettings.FileValued<Void> advisoryTranslateNow = this.settings.new FileValuedVoid("advisory_translate_now", I18n.tr("setting.advisory_translate_now.btn"), this::uiTranslateAdvisories);
    final ScarletSettings.FileValued<Void> advisoryRestore = this.settings.new FileValuedVoid("advisory_restore", I18n.tr("setting.advisory_restore.btn"), this::uiRestoreAdvisories);

    // Automatic evidence capture: on a kick/ban audit event, synthesize a global hotkey press so an
    // external capture tool (OBS "Save Replay Buffer", Medal, …) clips the moment. Off by default;
    // bind the same hotkey here that you set in the capture tool.
    final ScarletSettings.FileValued<Boolean> timedBansEnabled = this.settings.new FileValuedBoolean("timed_bans_enabled", I18n.tr("setting.timed_bans_enabled"), true);
    final ScarletSettings.FileValued<Boolean> evidenceCaptureEnabled = this.settings.new FileValuedBoolean("evidence_capture_enabled", I18n.tr("setting.evidence_capture_enabled"), false);
    final ScarletSettings.FileValued<String> evidenceCaptureHotkey = this.settings.new FileValuedStringPattern("evidence_capture_hotkey", I18n.tr("setting.evidence_capture_hotkey"), "F9", null, true);

    // Fires the capture hotkey for kick/ban moderation events when enabled. Called from the audit
    // processing loop; runs off-thread so a slow key injection never stalls audit handling.
    /**
     * Expiry sweep for timed group bans. Runs periodically: for every ban whose window has
     * elapsed, confirm the user is still banned and then lift the ban, attributing the unban to
     * the moderator who set it. Non-destructive on failure — a null/unknown membership status or a
     * failed unban leaves the record in place to retry on the next tick, so a transient VRChat API
     * hiccup can never silently strand a user as banned or drop the timer.
     */
    void processTimedBans()
    {
        ScarletPendingModActions pma = this.pendingModActions;
        ScarletVRChat vrc = this.vrc;
        if (pma == null || vrc == null)
            return;
        String groupId = vrc.groupId;
        if (groupId == null || groupId.isEmpty())
            return;
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, ScarletPendingModActions.TimedBan> e : pma.timedBansSnapshot().entrySet())
        {
            String userId = e.getKey();
            ScarletPendingModActions.TimedBan tb = e.getValue();
            if (tb == null || tb.expiresAt > now)
                continue; // still within the ban window

            io.github.vrchatapi.model.GroupMemberStatus status;
            try
            {
                status = vrc.getGroupMembershipStatus(groupId, userId);
            }
            catch (Exception ex)
            {
                LOG.warn("Timed-ban sweep: membership lookup failed for {}; will retry next tick", userId, ex);
                continue;
            }
            if (status == null)
                continue; // transient/unknown — do NOT drop the record; retry next tick
            if (status != io.github.vrchatapi.model.GroupMemberStatus.BANNED)
            {
                // The ban was already lifted (e.g. manually); the timer is moot.
                pma.removeTimedBan(userId);
                continue;
            }

            String actorId = tb.actorUserId;
            if (pma.addPending(GroupAuditType.USER_UNBAN, userId, actorId) != null)
                continue; // another assisted/automated action is already pending; retry next tick
            if (vrc.unbanFromGroup(userId))
            {
                pma.removeTimedBan(userId);
                LOG.info("Timed ban expired: unbanned {} (attributed to actor {})", userId, actorId);
            }
            else
            {
                pma.pollPending(GroupAuditType.USER_UNBAN, userId);
                LOG.warn("Timed ban for {} expired but the unban call failed; will retry next tick", userId);
            }
        }
    }

    void captureEvidenceIfKickBan(String eventType)
    {
        if (!Boolean.TRUE.equals(this.evidenceCaptureEnabled.get()))
            return;
        switch (eventType == null ? "" : eventType)
        {
        case "group.instance.kick":  // INSTANCE_KICK
        case "group.member.remove":  // MEMBER_REMOVE (removed from group)
        case "group.user.ban":       // USER_BAN
        {
            String hotkey = this.evidenceCaptureHotkey.get();
            this.exec.execute(() -> net.sybyline.scarlet.util.EvidenceCapture.fireHotkey(hotkey, 250));
        } break;
        default: break;
        }
    }

    // Translates every watched group's advisory into the current UI language via the configured
    // endpoint, off the EDT (it makes network calls), then reports how many changed. Group names,
    // ids and tags are untouched. If no endpoint is set, explains how to enable it.
    // Guards against overlapping translation runs. Without it, repeated button presses (with no
    // visible progress) each launch a full translation pass at once, hammering the service and
    // tripping its rate limit — which is exactly what an impatient click-again would cause.
    private final java.util.concurrent.atomic.AtomicBoolean advisoryTranslating = new java.util.concurrent.atomic.AtomicBoolean(false);

    void uiTranslateAdvisories()
    {
        java.awt.Component parent = this.ui.getParentComponent();
        String title = I18n.tr("advisory.translateTitle");
        // If a run is already going, say so instead of starting another (the cause of the error
        // storm from mashing the button).
        if (!this.advisoryTranslating.compareAndSet(false, true))
        {
            javax.swing.JOptionPane.showMessageDialog(parent, I18n.tr("advisory.translateBusy"),
                title, javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // Blank endpoint uses the built-in free MyMemory service, so translation always works
        // with no setup; a configured endpoint uses LibreTranslate instead.
        String endpoint = this.advisoryTranslateEndpoint.get();
        String apiKey = this.advisoryTranslateApiKey.get();
        String target = net.sybyline.scarlet.util.I18n.getLocale().getLanguage();
        // A modal "please wait" dialog with an indeterminate bar: it shows the work is happening
        // and blocks the button until it finishes, so there is no invisible in-progress state.
        final javax.swing.JDialog[] progress = new javax.swing.JDialog[1];
        net.sybyline.scarlet.ui.Swing.invokeLater(() ->
        {
            javax.swing.JDialog dlg = new javax.swing.JDialog(
                javax.swing.SwingUtilities.getWindowAncestor(parent), title,
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
            javax.swing.JProgressBar bar = new javax.swing.JProgressBar();
            bar.setIndeterminate(true);
            javax.swing.JPanel p = new javax.swing.JPanel(new java.awt.BorderLayout(10, 10));
            p.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 20, 16, 20));
            p.add(new javax.swing.JLabel(I18n.tr("advisory.translating")), java.awt.BorderLayout.NORTH);
            p.add(bar, java.awt.BorderLayout.CENTER);
            dlg.setContentPane(p);
            dlg.pack();
            dlg.setLocationRelativeTo(parent);
            progress[0] = dlg;
            dlg.setVisible(true); // starts a nested event loop; ends when disposed below
        });
        this.exec.execute(() ->
        {
            String result;
            try
            {
                // Every moderation data file with safe human-readable advisory text: watched
                // groups, watched users, watched avatars, and moderation-tag descriptions.
                int n = this.watchedGroups.translateAdvisories(endpoint, apiKey, target)
                      + this.watchedUsers.translateAdvisories(endpoint, apiKey, target)
                      + this.watchedAvatars.translateAdvisories(endpoint, apiKey, target)
                      + this.moderationTags.translateAdvisories(endpoint, apiKey, target);
                result = I18n.tr("advisory.translateDone", Integer.valueOf(n), target);
            }
            catch (Exception ex)
            {
                LOG.error("Advisory translation failed", ex);
                result = I18n.tr("advisory.translateFailed", String.valueOf(ex.getMessage()));
            }
            finally
            {
                this.advisoryTranslating.set(false);
            }
            final String msg = result;
            net.sybyline.scarlet.ui.Swing.invokeLater(() ->
            {
                if (progress[0] != null)
                    progress[0].dispose();
                javax.swing.JOptionPane.showMessageDialog(parent, msg, title,
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    // Restores the original (pre-translation) advisory text for every watched group.
    void uiRestoreAdvisories()
    {
        int n = this.watchedGroups.restoreAdvisories()
              + this.watchedUsers.restoreAdvisories()
              + this.watchedAvatars.restoreAdvisories()
              + this.moderationTags.restoreAdvisories();
        javax.swing.JOptionPane.showMessageDialog(this.ui.getParentComponent(),
            I18n.tr("advisory.restoreDone", Integer.valueOf(n)),
            I18n.tr("advisory.translateTitle"), javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Initialize the TTS service with user consent dialogs.
     * This method blocks until the user responds to any dialogs.
     */
    private synchronized void initTtsService()
    {
        if (this.ttsService != null)
            return;
        
        try
        {
            // Get the parent component for dialogs
            java.awt.Component parentComponent = this.ui.getParentComponent();
            this.ttsService = new TtsService(new File(dir, "tts"), this.eventListener, this.discord, parentComponent);
            this.eventListener.onTtsServiceInitialized();
        }
        catch (Exception ex)
        {
            LOG.error("Failed to initialize TTS service", ex);
            this.ttsService = new TtsService(new File(dir, "tts"), this.eventListener, this.discord, null);
            this.eventListener.onTtsServiceInitialized();
        }
    }

    /**
     * Get the TTS service, initializing it if necessary.
     * @return The TTS service instance
     */
    /** Persists the accent colour choice to settings.json so it survives restart. */
    public void saveAccentColor(int r, int g, int b)
    {
        this.settings.setObject(net.sybyline.scarlet.ui.Swing.ACCENT_SETTING_KEY, int[].class, new int[]{ r, g, b });
        this.settings.saveJson();
        // Show the same style of info dialog as UI scale uses
        this.execModal.execute(() ->
            javax.swing.JOptionPane.showMessageDialog(
                this.ui.getParentComponent(),
                "Accent colour will fully take effect on restart",
                "Accent colour updated",
                javax.swing.JOptionPane.INFORMATION_MESSAGE));
    }

    public TtsService getTtsService()
    {
        if (this.ttsService == null)
        {
            this.initTtsService();
        }
        return this.ttsService;
    }

    public TtsService getTtsServiceIfInitialized()
    {
        return this.ttsService;
    }

    private void maybeShowDataFolderMigrationNotice()
    {
        DataFolderMigrationResult migration = DATA_FOLDER_MIGRATION;
        if (migration == null || !migration.shouldNotify() || isDataFolderNoticeAcknowledged())
            return;
        this.showDataFolderMigrationNotice(migration, false, null);
    }

    private void showDataFolderMigrationNotice(DataFolderMigrationResult migration, boolean dryRun, java.util.function.Consumer<String> out)
    {
        switch (migration.kind)
        {
            case COPIED:
                LOG.info("KozyBlake/Scarlet data folder initialized from existing legacy original-repo data. Active data directory: {}", dir);
                break;
            case DECLINED:
                LOG.info("KozyBlake/Scarlet data folder transfer was declined. Active data directory: {}", dir);
                break;
            case SKIPPED_EXISTING_TARGET:
                LOG.info("KozyBlake/Scarlet data folder already had data; legacy original-repo data was left untouched. Active data directory: {}", dir);
                break;
            case FAILED:
                LOG.warn("KozyBlake/Scarlet data folder copy did not complete. Active data directory: {}. Error: {}", dir, migration.error);
                break;
            default:
                break;
        }
        String message = dataFolderMigrationMessage(migration);
        if (dryRun)
            message = "[CLI popup test: no acknowledgement will be saved.]\n\n" + message;
        final String dialogMessage = message;
        this.splash.splashSubtext(I18n.tr("splash.confirmingDataFolder"));
        try
        {
            if (!GraphicsEnvironment.isHeadless())
            {
                Swing.invokeWait(() -> JOptionPane.showMessageDialog(
                    this.ui.getParentComponent(),
                    dialogMessage,
                    "KozyBlake/Scarlet Data Folder",
                    JOptionPane.INFORMATION_MESSAGE));
            }
            else
            {
                System.out.println();
                System.out.println("KozyBlake/Scarlet Data Folder");
                System.out.println(dialogMessage);
                System.out.println();
            }
        }
        finally
        {
            if (dryRun)
            {
                String msg = "Popup test displayed: data-folder-notice " + migration.kind.name().toLowerCase();
                LOG.info(msg);
                if (out != null) out.accept(msg);
            }
            else
            {
                acknowledgeDataFolderNotice(migration);
            }
        }
    }

    private static String dataFolderMigrationMessage(DataFolderMigrationResult migration)
    {
        StringBuilder message = new StringBuilder();
        message
            .append("KozyBlake/Scarlet now uses a KozyBlake/Scarlet data folder.\n\n")
            .append("Active data folder:\n")
            .append(pathOrUnavailable(dir))
            .append("\n\n")
            .append("Going forward, this fork reads and writes data in that active folder instead of the legacy upstream folder.\n\n")
            .append("Legacy data folder kept for rollback:\n")
            .append(pathOrUnavailable(migration.legacyDir))
            .append("\n\n");
        switch (migration.kind)
        {
            case COPIED:
                message.append("KozyBlake/Scarlet copied your existing data into the active KozyBlake/Scarlet folder because that folder was empty or missing.\n\n");
                break;
            case DECLINED:
                message.append("You chose to start fresh in the active KozyBlake/Scarlet folder. No legacy data was copied.\n\n");
                break;
            case SKIPPED_EXISTING_TARGET:
                message.append("KozyBlake/Scarlet found legacy data, but the active KozyBlake/Scarlet folder already contains data, so nothing was copied automatically.\n\n");
                break;
            case FAILED:
                message
                    .append("KozyBlake/Scarlet tried to copy your existing data into the active KozyBlake/Scarlet folder, but the copy did not complete.\n")
                    .append("Error: ")
                    .append(migration.error == null ? "unknown" : migration.error)
                    .append("\n\n");
                break;
            default:
                break;
        }
        message
            .append("The legacy folder is not moved, renamed, or deleted. If you go back to the upstream build, it can keep using that folder.\n\n")
            .append("This notice will only be shown once.");
        return message.toString();
    }

    private static boolean isDataFolderNoticeAcknowledged()
    {
        return isDataFolderNoticeAcknowledged(dir);
    }

    private static boolean isDataFolderNoticeAcknowledged(File dataDir)
    {
        File ackFile = dataFolderNoticeAckFile(dataDir);
        return ackFile != null && ackFile.isFile();
    }

    private static void acknowledgeDataFolderNotice(DataFolderMigrationResult migration)
    {
        acknowledgeDataFolderNotice(dir, migration);
    }

    private static void acknowledgeDataFolderNotice(File dataDir, DataFolderMigrationResult migration)
    {
        File ackFile = dataFolderNoticeAckFile(dataDir);
        if (ackFile == null)
            return;
        try
        {
            File parent = ackFile.getParentFile();
            if (parent != null && !parent.isDirectory())
                parent.mkdirs();
            Files.write(ackFile.toPath(), Arrays.asList(
                "acknowledged=" + System.currentTimeMillis(),
                "result=" + (migration == null ? "unknown" : migration.kind.name()),
                "active=" + pathOrUnavailable(dir)
            ), StandardCharsets.UTF_8);
        }
        catch (Exception ex)
        {
            System.err.println("Failed to persist KozyBlake/Scarlet data folder notice acknowledgement at " + ackFile + ": " + ex);
        }
    }

    private static File dataFolderNoticeAckFile()
    {
        return dataFolderNoticeAckFile(dir);
    }

    private static File dataFolderNoticeAckFile(File dataDir)
    {
        return dataDir == null ? null : new File(dataDir, DATA_FOLDER_NOTICE_ACK_FILENAME);
    }

    private static String pathOrUnavailable(File file)
    {
        return file == null ? "(unavailable)" : file.getAbsolutePath();
    }

    /**
     * First-launch disclosure. Scarlet (this fork) is maintained by KozyBlake and developed largely
     * with AI assistance -- a "vibe-coded" project. Shown once; the user may decline and close.
     * Headless runs log the notice once and continue, since a scheduled bot has no one to click.
     */
    void showVibecodedNoticeIfNeeded()
    {
        if (Boolean.TRUE.equals(this.vibecodedNoticeAcknowledged.get()))
            return;
        String title = "Scarlet \u2014 a note before you start";
        String plain = "This fork of Scarlet is maintained by KozyBlake and developed largely with AI"
            + " assistance \u2014 a \"vibe-coded\" project. Code is written and reasoned through with an"
            + " AI pair, then reviewed and built by KozyBlake.\n\n"
            + "That isn't for everyone, and that's completely fair. If you would rather not run a"
            + " vibe-coded project, you can close Scarlet now \u2014 no hard feelings.";
        if (Platform.forceHeadlessUi() || GraphicsEnvironment.isHeadless())
        {
            LOG.warn("{}: {}", title, plain.replace("\n\n", " "));
            LOG.warn("(Headless: continuing. Shown once; remove 'vibecoded_notice_acknowledged' from settings.json to see it again.)");
            this.vibecodedNoticeAcknowledged.set(Boolean.TRUE, "first-run-notice");
            return;
        }
        int result = JOptionPane.showOptionDialog(
            null,
            Swing.dialogMessage(plain),
            title,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.INFORMATION_MESSAGE,
            null,
            new Object[]{ "Continue", "Close Scarlet" },
            "Continue");
        if (result != 0) // index 0 == "Continue"; "Close Scarlet" (1) or dismissed (-1) both exit
        {
            LOG.info("User declined the vibe-coded notice; exiting.");
            System.exit(0);
        }
        this.vibecodedNoticeAcknowledged.set(Boolean.TRUE, "first-run-notice");
    }

    public void run()
    {
        System.out.println("===========================================");
        System.out.println("  " + APP_NAME + " " + VERSION);
        System.out.println("  Type 'help' for available CLI commands.");
        System.out.println("===========================================");
        this.showVibecodedNoticeIfNeeded();
        this.ui.loadSettings();
        this.maybeShowDataFolderMigrationNotice();
        this.eventListener.settingsLoaded();
        this.mobile.settingsLoaded();
        this.checkVrchatApiPreflight();
        // Initialize TTS after UI is ready (for dialog parent component)
        this.splash.splashSubtext(I18n.tr("splash.initTts"));
        this.initTtsService();
        // Run cache cleanup before any network login so neither VRChat nor Discord
        // are connected yet and no log file is being tailed — nothing is "active".
        this.exec.execute(this.cacheCleanup::maybeCleanup);
        this.cacheCleanup.schedulePeriodicCleanup();
        this.splash.splashSubtext(I18n.tr("splash.loginVrchat"));
        try
        {
            this.vrc.login();
            this.staffList.populateStaffNames(this.vrc);
            this.secretStaffList.populateSecretStaffNames(this.vrc);
        }
        catch (Throwable ex)
        {
            LOG.error("Failed to authenticate with VRChat", ex);
            return;
        }
        if (!this.vrc.hasValidGroupId())
        {
            this.vrc.modalNeedGroupId();
        }
        else if (!this.vrc.checkSelfUserHasVRChatPermission(GroupPermissions.group_audit_view))
        {
            this.vrc.modalNeedPerms(GroupPermissions.group_audit_view);
        }
        this.splash.splashSubtext(I18n.tr("splash.checkingUpdates"));
        this.checkUpdate();
        this.checkAnnouncement();
        // One-time opt-in for built-in Discord moderation commands
        if (Features.DISCORD_KICK_BAN_ENABLED && !this.discordKickBanPrompted.get())
        {
            this.settings.requireConfirmYesNoAsync(
                "KozyBlake/Scarlet includes built-in Discord slash commands for warning, kicking, and banning server members\n" +
                "(/discord-warn, /discord-kick, and /discord-ban).\n\n" +
                "Many users prefer to use a dedicated moderation bot for this instead.\n\n" +
                "Would you like to enable KozyBlake/Scarlet's built-in Discord moderation commands?",
                "Discord Moderation Commands",
                () -> {
                    this.discordKickBanEnabled.set(true, "user-prompt");
                    this.discordKickBanPrompted.set(true, "user-prompt");
                },
                () -> {
                    this.discordKickBanPrompted.set(true, "user-prompt");
                }
            );
        }
        this.logs.start();
        this.execIPC.execute(this::runIPC);
        // Health watchdog: converts Scarlet's silent failure modes (dead log tailer,
        // expired VRChat session) into visible, recoverable ones. Runs off the worker
        // pool; each tick is cheap and failures in the watchdog itself are swallowed.
        this.healthWatchdogStartedMillis = System.currentTimeMillis();
        this.exec.scheduleWithFixedDelay(this::healthWatchdogTick, 90L, 60L, TimeUnit.SECONDS);
        // Lift expired timed group bans (see processTimedBans). Cheap and failure-tolerant.
        this.exec.scheduleWithFixedDelay(this::processTimedBans, 60L, 60L, TimeUnit.SECONDS);
        try
        {
            long filecheck = 3;
            for (long now, lastIter = 0L; this.running; lastIter = now)
            {
                // spin
                long currentPollInterval = Math.min(Math.max(this.auditPollingInterval.get().longValue(), 10L), 300L) * 1_000L;
                while ((now = System.currentTimeMillis()) - lastIter < currentPollInterval && this.running)
                {
                    // Burst polling: a moderation action was just initiated through
                    // Scarlet (Discord command or UI), so poll the audit log every
                    // AUDIT_FAST_POLL_PERIOD_MILLIS instead of waiting out the full
                    // interval. This gets the moderation thread posted to Discord as
                    // soon as VRChat's audit endpoint exposes the entry.
                    if (now < this.auditFastPollUntilMillis && now - lastIter >= AUDIT_FAST_POLL_PERIOD_MILLIS)
                        break;
                    this.spin();
                    this.maybeFastDetectNewInstance();
                }
                // maybe refresh
                try
                {
                    this.maybeRefresh();
                }
                catch (Exception ex)
                {
                    this.running = false;
                    LOG.error("Exception maybe refreshing", ex);
                    return;
                }
                // query & emit
                if (!this.staffMode) try
                {
                    this.queryEmit(currentPollInterval);
                }
                catch (Exception ex)
                {
                    this.running = false;
                    LOG.error("Exception emitting query", ex);
                    return;
                }
                // log names
                if (filecheck --> 0L) try
                {
                    File logs = new File(dir, "logs");
                    String[] names = logs.list(($, name) -> ScarletLogger.lfpattern.matcher(name).find());
                    names = Arrays
                        .stream(names)
                        .sorted(Comparator.reverseOrder())
                        .limit(OptionData.MAX_CHOICES)
                        .toArray(String[]::new);
                    this.last25logs = names;
                }
                catch (Exception ex)
                {
                    this.running = false;
                    LOG.error("Exception enumerating log files", ex);
                    return;
                }
                // maybe check instances
                if (!this.staffMode) try
                {
                    this.maybeCheckInstances();
                    this.maybeEnforceInstances();
                    this.maybeAutoCloseBotInstance();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe checking instances", ex);
                }
                // maybe update calendar
                if (!this.staffMode) try
                {
                    this.maybeUpdateCalendar();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe updating calendar", ex);
                }
                // maybe mod summary
                if (!this.staffMode) try
                {
                    this.maybeModSummary();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe mod summary", ex);
                }
                // maybe outstanding-moderation re-ping (was never invoked before)
                if (!this.staffMode) try
                {
                    this.maybeOutstandingMod();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe outstanding mod", ex);
                }
                // maybe poll action
                try
                {
                    this.maybePollAction();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe saving data", ex);
                }
                // maybe check update
                try
                {
                    this.maybeCheckUpdate();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe checking for update", ex);
                }
                // maybe check announcement
                try
                {
                    this.maybeCheckAnnouncement();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe checking for announcement", ex);
                }
                // maybe save data
                try
                {
                    this.maybeSaveData();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe saving data", ex);
                }
                this.explicitGC.run();
            }
        }
        finally
        {
            ;
        }
    }

    void spin()
    {
        MiscUtils.sleep(100L);
        // Custom builds can disable the interactive CLI entirely. The JIT folds
        // this branch away when CLI_COMMANDS_ENABLED is false, so the rest of
        // this method becomes dead code.
        if (!Features.CLI_COMMANDS_ENABLED)
            return;
        // Short-circuit once we've learned stdin has no valid handle (e.g.
        // launched via javaw.exe / double-clicked JAR on Windows).  No point
        // calling available() again — it will throw on every tick.
        if (!this.stdinUsable)
            return;
        try
        {
            while (System.in.available() > 0)
            {
                @SuppressWarnings("resource")
                Scanner s = new Scanner(System.in);
                String line = s.nextLine().trim();
                this.rawCommand(line);
            }
        }
        catch (IOException ioex)
        {
            // Windows without a console: FileInputStream.available0() throws
            // "The handle is invalid" (Win32 ERROR_INVALID_HANDLE).  POSIX
            // equivalents surface as "Bad file descriptor" when stdin has been
            // closed or redirected from a dead source.  In all of these cases
            // there is no interactive console to read commands from, so we
            // permanently disable the CLI reader for this session and log it
            // once at INFO — not ERROR — because this is expected behaviour
            // for a detached launch, not a fault.
            this.stdinUsable = false;
            LOG.info("Console stdin unavailable ("+ioex.getMessage()+"); CLI commands disabled for this session");
        }
        catch (Exception ex)
        {
            LOG.error("Exception in spin", ex);
        }
    }

/*
function Send-ScarletIPC
{
    param
    (
    [String]$GroupID,
    [String]$Message
    )
    $request = [System.Text.Encoding]::UTF8.GetBytes($Message);
    $stream = New-Object -TypeName System.IO.Pipes.NamedPipeClientStream -ArgumentList '.',"ScarletIPC-$GroupID",([System.IO.Pipes.PipeDirection]::Out),([System.IO.Pipes.PipeOptions]::WriteThrough),([System.Security.Principal.TokenImpersonationLevel]::Impersonation)
    $stream.Connect($1000);
    $stream.Write($request, 0, $request.Length);
    $stream.Dispose();
}
Send-ScarletIPC -GroupID 'grp_00000000-0000-0000-0000-000000000000' -Message 'stop'
*/
    void runIPC()
    {
        try (ServerSocket ipcServer = createIpcServer())
        {
            try
            {
                LOG.info("Listening ipc: "+ipcServer);
                byte[] buf = new byte[65536];
                String line;
                while (this.running)
                {
                    line = null;
                    try (Socket socket =  ipcServer.accept())
                    {
                        InputStream in = socket.getInputStream();
                        int i = 0;
                        try
                        {
                            int b;
                            for (; i < 65536 && (b = in.read()) != -1; i++) buf[i] = (byte) b;
                        }
                        catch (IOException ioex)
                        {
                            if (!"ReadFile() failed: 109".equals(ioex.getMessage()))
                                LOG.error("Exception in ipc read: ", ioex);
                        }
                        line = new String(buf, 0, i, StandardCharsets.UTF_8);
                        LOG.info("CLI from ipc ("+i+" bytes)");
                    }
                    catch (IOException ioex)
                    {
                        // Ignore exception caused by intentional shutdown
                        if (this.running || !"GetOverlappedResult() failed for connect operation: 0".equals(ioex.getMessage()))
                            LOG.error("Exception in ipc loop", ioex);
                    }
                    this.rawCommandFromIpc(line);
                }
            }
            finally
            {
                LOG.info("Ignoring ipc: "+ipcServer);
            }
        }
        catch (Exception ex)
        {
            LOG.error("Exception in ipc", ex);
        }
    }

    /**
     * Creates the IPC server using platform-specific implementation.
     * Uses reflection on Windows to avoid ClassNotFoundException when loading Windows-specific classes on Linux.
     */
    private ServerSocket createIpcServer() throws Exception
    {
        if (Platform.CURRENT.isNT())
        {
            // Use reflection to avoid loading Windows-specific classes on non-Windows platforms.
            // We discover the constructor dynamically because the ipcsocket API has changed across
            // versions — hardcoding a specific signature causes NoSuchMethodException when the
            // library is updated.
            Class<?> socketClass        = Class.forName("org.scalasbt.ipcsocket.Win32NamedPipeServerSocket");
            Class<?> securityLevelClass = Class.forName("org.scalasbt.ipcsocket.Win32SecurityLevel");
            Object   localSecurity      = this.resolveWin32PipeSecurityLevel(securityLevelClass);
            String   pipeName           = "\\\\.\\pipe\\ScarletIPC-" + this.vrc.groupId;

            // Try known constructor signatures from newest to oldest, logging which one matched.
            // (String pipeName, boolean isInheritable, Win32SecurityLevel security)  — 1.6.x
            try
            {
                java.lang.reflect.Constructor<?> ctor = socketClass.getConstructor(String.class, boolean.class, securityLevelClass);
                LOG.info("IPC: using Win32NamedPipeServerSocket(String, boolean, Win32SecurityLevel)");
                return (ServerSocket) ctor.newInstance(pipeName, false, localSecurity);
            }
            catch (NoSuchMethodException ignored) {}

            // (String pipeName, boolean isInheritable)  — some intermediate versions
            try
            {
                java.lang.reflect.Constructor<?> ctor = socketClass.getConstructor(String.class, boolean.class);
                LOG.info("IPC: using Win32NamedPipeServerSocket(String, boolean)");
                return (ServerSocket) ctor.newInstance(pipeName, false);
            }
            catch (NoSuchMethodException ignored) {}

            // (int backlog, String pipeName, boolean inheritHandle, boolean isInheritable, Win32SecurityLevel security)  — 1.5.x
            try
            {
                java.lang.reflect.Constructor<?> ctor = socketClass.getConstructor(int.class, String.class, boolean.class, boolean.class, securityLevelClass);
                LOG.info("IPC: using Win32NamedPipeServerSocket(int, String, boolean, boolean, Win32SecurityLevel)");
                return (ServerSocket) ctor.newInstance(255, pipeName, false, false, localSecurity);
            }
            catch (NoSuchMethodException ignored) {}

            // Last resort: log all available constructors to help diagnose future version changes
            java.lang.reflect.Constructor<?>[] ctors = socketClass.getConstructors();
            StringBuilder sb = new StringBuilder("IPC: No known Win32NamedPipeServerSocket constructor matched. Available constructors:\n");
            for (java.lang.reflect.Constructor<?> c : ctors)
                sb.append("  ").append(c).append("\n");
            LOG.error(sb.toString());
            throw new NoSuchMethodException("No compatible Win32NamedPipeServerSocket constructor found in ipcsocket on classpath");
        }
        else
        {
            // Clean up any leftover socket file from a previous instance
            // This handles the case where the previous instance didn't shut down cleanly
            String socketPath = "/tmp/ScarletIPC-"+this.vrc.groupId+".sock";
            Path socketFilePath = Paths.get(socketPath);
            try
            {
                Files.deleteIfExists(socketFilePath);
            }
            catch (IOException ex)
            {
                LOG.warn("Failed to delete existing socket file: " + socketPath, ex);
            }
            UnixDomainServerSocket server = new UnixDomainServerSocket(socketPath, false);
            this.hardenUnixIpcSocket(socketFilePath);
            return server;
        }
    }

    private Object resolveWin32PipeSecurityLevel(Class<?> securityLevelClass) throws Exception
    {
        for (String fieldName : new String[]{"OWNER_DACL", "LOCAL_SYSTEM", "AUTHENTICATED_CLIENT", "LOCAL_ONLY", "RESTRICTED", "DEFAULT"})
        {
            try
            {
                return securityLevelClass.getField(fieldName).get(null);
            }
            catch (NoSuchFieldException ignored)
            {
            }
        }
        throw new NoSuchFieldException("No secure Win32SecurityLevel field is available");
    }

    private void hardenUnixIpcSocket(Path socketFilePath)
    {
        try
        {
            Files.setPosixFilePermissions(socketFilePath, java.util.EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        }
        catch (UnsupportedOperationException ignored)
        {
        }
        catch (IOException ioex)
        {
            LOG.warn("Failed to tighten IPC socket permissions for {}", socketFilePath, ioex);
        }
    }

    private String loadOrCreateIpcAuthToken()
    {
        Path tokenPath = dir.toPath().resolve("ipc.token");
        try
        {
            if (Files.isRegularFile(tokenPath))
            {
                return new String(Files.readAllBytes(tokenPath), StandardCharsets.UTF_8).trim();
            }
            byte[] bytes = new byte[24];
            new java.security.SecureRandom().nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try
            {
                writeOwnerOnlyNewFile(tokenPath, token.getBytes(StandardCharsets.UTF_8));
            }
            catch (FileAlreadyExistsException ignored)
            {
                return new String(Files.readAllBytes(tokenPath), StandardCharsets.UTF_8).trim();
            }
            return token;
        }
        catch (IOException ioex)
        {
            throw new IllegalStateException("Failed to initialize IPC auth token", ioex);
        }
    }

    private static void writeOwnerOnlyNewFile(Path path, byte[] bytes) throws IOException
    {
        try
        {
            FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(EnumSet.of(
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

    void rawCommandFromIpc(String line)
    {
        if (Boolean.getBoolean("scarlet.ipc.insecure"))
        {
            this.rawCommand(line);
            return;
        }
        if (line == null)
            return;
        String trimmed = line.trim();
        if (trimmed.isEmpty())
            return;
        int space = trimmed.indexOf(' ');
        String token = space < 0 ? trimmed : trimmed.substring(0, space);
        String command = space < 0 ? "" : trimmed.substring(space + 1).trim();
        if (!Objects.equals(this.ipcAuthToken, token))
        {
            LOG.warn("Rejected unauthenticated IPC command");
            return;
        }
        this.rawCommand(command);
    }

    void rawCommand(String line)
    {
        rawCommand(line, null);
    }

    // The English command words the CLI switch handles directly; these always work, in every
    // language, so scripts and documentation never break.
    private static final java.util.Set<String> CLI_CANONICAL = new java.util.HashSet<>(java.util.Arrays.asList(
        "info", "help", "logout", "exit", "halt", "quit", "stop", "reboot", "restart", "langlint",
        "simulate", "explore", "tts", "link", "importgroups", "importgroupsjson",
        "translate-advisories", "restore-advisories", "repair-secure-store",
        "vrchatapi-test", "popup", "popups", "popup-test"));

    // Canonical command → i18n key whose value is a comma/space-separated list of that command's
    // aliases in the active UI language. Lets a native speaker type, say, "ayuda" or "salir"
    // instead of "help"/"exit". English keeps working regardless (see CLI_CANONICAL).
    private static final String[][] CLI_ALIAS_KEYS = {
        { "help",             "cli.alias.help" },
        { "logout",           "cli.alias.logout" },
        { "exit",             "cli.alias.exit" },
        { "reboot",           "cli.alias.reboot" },
        { "langlint",         "cli.alias.langlint" },
        { "simulate",         "cli.alias.simulate" },
        { "explore",          "cli.alias.explore" },
        { "tts",              "cli.alias.tts" },
        { "link",             "cli.alias.link" },
        { "importgroups",     "cli.alias.importgroups" },
        { "importgroupsjson", "cli.alias.importgroupsjson" },
    };

    // Resolves a possibly-localized command word to its canonical English form. Canonical words
    // resolve to themselves; a localized alias for the active language resolves to its command;
    // anything else is returned lower-cased so the switch reports it as unknown. Case-insensitive.
    static String canonicalizeCliCommand(String op)
    {
        if (op == null)
            return "";
        String low = op.trim().toLowerCase(java.util.Locale.ROOT);
        if (low.isEmpty() || CLI_CANONICAL.contains(low))
            return low;
        for (String[] pair : CLI_ALIAS_KEYS)
        {
            String aliases = I18n.tr(pair[1]);
            if (aliases == null || aliases.isEmpty() || aliases.equals(pair[1]))
                continue; // no localized aliases defined for this command
            for (String alias : aliases.split("[,\\s]+"))
                if (!alias.isEmpty() && alias.trim().toLowerCase(java.util.Locale.ROOT).equals(low))
                    return pair[0];
        }
        return low;
    }

    void rawCommand(String line, java.util.function.Consumer<String> out)
    {
        if (line == null || line.isEmpty())
            return;
        Scanner ls = new Scanner(new StringReader(line));
        try
        {
            String op = canonicalizeCliCommand(ls.next());
            switch (op)
            {
            default: {
                String msg = I18n.tr("cli.unknown", op);
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } break;
            case "info":
            case "help": {
                StringBuilder sb = new StringBuilder("KozyBlake/Scarlet CLI commands:");
                sb.append("\n  help               — show this list (alternate: info)");
                sb.append("\n  logout             — log out of VRChat");
                sb.append("\n  exit               — stop KozyBlake/Scarlet (alternate: halt, quit, stop)");
                sb.append("\n  explore            — open the KozyBlake/Scarlet data folder");
                sb.append("\n  tts <text>         — submit text to the TTS service");
                sb.append("\n  link <usr_id> <sf> — link a VRChat user to a Discord snowflake");
                sb.append("\n  importgroups <file|URL>     — import watched groups (legacy CSV)");
                sb.append("\n  importgroupsjson <file|URL> — import watched groups (JSON)");
                sb.append("\n  reboot             - fully restart KozyBlake/Scarlet (alternate: restart)");
                sb.append("\n  translate-advisories        — translate watched-group advisories to your UI language");
                sb.append("\n  restore-advisories          — restore original (untranslated) advisories");
                sb.append("\n  repair-secure-store         — reconcile the registry + data-folder secure store (report only)");
                sb.append("\n  repair-secure-store cutover — after a clean report, snapshot & empty the registry (file becomes primary)");
                // If the active UI language defines command aliases, list them so a native
                // speaker can discover the words they can type instead of the English ones.
                StringBuilder localized = new StringBuilder();
                for (String[] pair : CLI_ALIAS_KEYS)
                {
                    String aliases = I18n.tr(pair[1]);
                    if (aliases == null || aliases.isEmpty() || aliases.equals(pair[1]))
                        continue;
                    localized.append(localized.length() > 0 ? "\n  " : "\n  ")
                             .append(pair[0]).append(" → ").append(aliases.trim());
                }
                if (localized.length() > 0)
                    sb.append("\n").append(I18n.tr("cli.help.localizedHeader")).append(localized);
                String msg = sb.toString();
                System.out.println(msg);
                if (out != null) out.accept(msg);
            } break;
            case "logout": {
                String msg = "Logout success: " + this.vrc.logout();
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } // fallthrough
            case "exit":
            case "halt":
            case "quit":
            case "stop": {
                this.running = false;
                String msg = "Stopping KozyBlake/Scarlet...";
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } break;
            case "reboot":
            case "restart": {
                String msg = this.restart()
                    ? "Restarting KozyBlake/Scarlet..."
                    : "Restart is already queued";
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } break;
            case "langlint": {
                for (String lintLine : net.sybyline.scarlet.util.I18n.lintExternal())
                {
                    LOG.info(lintLine);
                    if (out != null) out.accept(lintLine);
                }
            } break;
            case "translate-advisories": {
                if (!this.advisoryTranslating.compareAndSet(false, true))
                {
                    if (out != null) out.accept(I18n.tr("advisory.translateBusy"));
                    break;
                }
                String endpoint = this.advisoryTranslateEndpoint.get();
                String apiKey = this.advisoryTranslateApiKey.get();
                String target = I18n.getLocale().getLanguage();
                try
                {
                    if (out != null) out.accept(I18n.tr("advisory.translating"));
                    int n = this.watchedGroups.translateAdvisories(endpoint, apiKey, target)
                          + this.watchedUsers.translateAdvisories(endpoint, apiKey, target)
                          + this.watchedAvatars.translateAdvisories(endpoint, apiKey, target)
                          + this.moderationTags.translateAdvisories(endpoint, apiKey, target);
                    String msg = I18n.tr("advisory.translateDone", Integer.valueOf(n), target);
                    LOG.info(msg);
                    if (out != null) out.accept(msg);
                }
                catch (Exception ex)
                {
                    String msg = I18n.tr("advisory.translateFailed", String.valueOf(ex.getMessage()));
                    LOG.error(msg, ex);
                    if (out != null) out.accept("[error] " + msg);
                }
                finally
                {
                    this.advisoryTranslating.set(false);
                }
            } break;
            case "restore-advisories": {
                int n = this.watchedGroups.restoreAdvisories()
                      + this.watchedUsers.restoreAdvisories()
                      + this.watchedAvatars.restoreAdvisories()
                      + this.moderationTags.restoreAdvisories();
                String msg = I18n.tr("advisory.restoreDone", Integer.valueOf(n));
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } break;
            case "repair-secure-store": {
                boolean cutover = false;
                while (ls.hasNext())
                {
                    String arg = ls.next();
                    if ("cutover".equalsIgnoreCase(arg) || "migrate".equalsIgnoreCase(arg) || "commit".equalsIgnoreCase(arg))
                        cutover = true;
                }
                String report = this.settings.repairSecureStore(cutover);
                LOG.info("secure store repair (cutover={}):\n{}", Boolean.valueOf(cutover), report);
                if (out != null) out.accept(report);
            } break;
            case "simulate": {
                if (this.trainingMode == null || !this.trainingMode.get())
                {
                    String msg = I18n.tr("sim.disabled");
                    if (out != null) out.accept(msg);
                    break;
                }
                String kindAlias = ls.hasNext() ? ls.next() : "";
                String simName = ls.hasNextLine() ? ls.nextLine().trim() : "";
                ScarletSimulation.Kind kind = ScarletSimulation.Kind.byAlias(kindAlias);
                if (kind == null)
                {
                    StringBuilder kinds = new StringBuilder();
                    for (ScarletSimulation.Kind k : ScarletSimulation.Kind.values())
                        kinds.append(kinds.length() > 0 ? ", " : "").append(k.cliAlias);
                    String msg = "Usage: simulate <kind> [name]  -  kinds: " + kinds;
                    if (out != null) out.accept(msg);
                }
                else
                {
                    String msg = ScarletSimulation.trigger(this, kind, simName, null);
                    LOG.info(msg);
                    if (out != null) out.accept(msg);
                }
            } break;
            case "explore": {
                MiscUtils.AWTDesktop.browse(dir.toURI());
                if (out != null) out.accept("Opening data folder: " + dir.getAbsolutePath());
            } break;
            case "tts": {
                String text = ls.nextLine().trim();
                if (!text.isEmpty())
                {
                    TtsService tts = this.getTtsService();
                    if (tts != null)
                    {
                        Object result = tts.submit("cli-"+Long.toUnsignedString(System.nanoTime()), text);
                        String msg = "Submitting TTS: `" + text + "`, result: " + result;
                        LOG.info(msg);
                        if (out != null) out.accept(msg);
                    }
                    else
                    {
                        String msg = "TTS service not available";
                        LOG.warn(msg);
                        if (out != null) out.accept("[warn] " + msg);
                    }
                }
            } break;
            case "link": {
                String userId = ls.next();
                String userSnowflake = ls.next();

                User user = this.vrc.getUser(userId);
                if (user == null)
                {
                    String msg = "Unknown VRChat user: " + userId;
                    LOG.warn(msg);
                    if (out != null) out.accept("[warn] " + msg);
                }
                else
                {
                    this.data.linkIdToSnowflake(userId, userSnowflake);
                    String msg = "Linking VRChat user " + user.getDisplayName() + " (" + userId + ") to Discord user <@" + userSnowflake + ">";
                    LOG.info(msg);
                    if (out != null) out.accept(msg);
                }
            } break;
            case "importgroups": {
                String from = ls.nextLine().trim();
                boolean isUrl = from.startsWith("http://") || from.startsWith("https://");

                String srcLabel = (isUrl ? "URL: " : "file: ") + from;
                String startMsg = "Importing watched groups legacy CSV from " + srcLabel;
                LOG.info(startMsg);
                if (out != null) out.accept(startMsg);
                try (Reader reader = isUrl ? new InputStreamReader(HttpURLInputStream.get(from, HttpURLInputStream.PUBLIC_ONLY), StandardCharsets.UTF_8) : MiscUtils.reader(new File(from)))
                {
                    if (this.watchedGroups.importLegacyCSV(reader, true))
                    {
                        String msg = "Successfully imported watched groups legacy CSV";
                        LOG.info(msg);
                        if (out != null) out.accept(msg);
                    }
                    else
                    {
                        String msg = "Failed to import watched groups legacy CSV with unknown reason";
                        LOG.warn(msg);
                        if (out != null) out.accept("[warn] " + msg);
                    }
                }
                catch (Exception ex)
                {
                    String msg = "Exception importing watched groups legacy CSV from " + srcLabel;
                    LOG.error(msg, ex);
                    if (out != null) out.accept("[error] " + msg + ": " + ex.getMessage());
                }
            } break;
            case "importgroupsjson": {
                String from = ls.nextLine().trim();
                boolean isUrl = from.startsWith("http://") || from.startsWith("https://");

                String srcLabel = (isUrl ? "URL: " : "file: ") + from;
                String startMsg = "Importing watched groups JSON from " + srcLabel;
                LOG.info(startMsg);
                if (out != null) out.accept(startMsg);
                try (Reader reader = isUrl ? new InputStreamReader(HttpURLInputStream.get(from, HttpURLInputStream.PUBLIC_ONLY), StandardCharsets.UTF_8) : MiscUtils.reader(new File(from)))
                {
                    if (this.watchedGroups.importJson(reader, true))
                    {
                        String msg = "Successfully imported watched groups JSON";
                        LOG.info(msg);
                        if (out != null) out.accept(msg);
                    }
                    else
                    {
                        String msg = "Failed to import watched groups JSON with unknown reason";
                        LOG.warn(msg);
                        if (out != null) out.accept("[warn] " + msg);
                    }
                }
                catch (Exception ex)
                {
                    String msg = "Exception importing watched groups JSON from " + srcLabel;
                    LOG.error(msg, ex);
                    if (out != null) out.accept("[error] " + msg + ": " + ex.getMessage());
                }
            } break;
            case "vrchatapi-test": {
                VrchatApiVersionChecker.Report report = VrchatApiVersionChecker.createTestUpdateAvailableReport();
                this.vrchatApiPreflightReport = report;
                this.ui.refreshVrchatApiStatus();
                this.showVrchatApiPreflightWarning(report);
                String msg = "Triggered hidden VRChat API update warning test";
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } break;
            case "popup":
            case "popups":
            case "popup-test": {
                this.rawPopupTestCommand(ls, out);
            } break;
            }
        }
        catch (Exception ex)
        {
            LOG.error("Exception handling CLI command: "+line, ex);
            if (out != null) out.accept("[error] Exception handling command: " + ex.getMessage());
        }
    }

    void rawPopupTestCommand(Scanner ls, java.util.function.Consumer<String> out)
    {
        String popup = ls.hasNext() ? ls.next().trim().toLowerCase() : "";
        if (popup.isEmpty() || "help".equals(popup) || "list".equals(popup))
        {
            String msg = "Hidden popup test commands:"
                + "\n  popup-test data-transfer"
                + "\n  popup-test data-folder-notice <copied|declined|skipped|failed>"
                + "\nThese commands only display/test popups; they do not copy data, write acknowledgement flags, or perform the confirmed action.";
            LOG.info(msg);
            if (out != null) out.accept(msg);
            else System.out.println(msg);
            return;
        }
        switch (popup)
        {
            case "data-transfer": {
                File legacyDir = LEGACY_DIR != null ? LEGACY_DIR : defaultDataDir(LEGACY_GROUP, NAME, System.getenv("LOCALAPPDATA"), System.getenv("XDG_DATA_HOME"));
                boolean transfer = confirmLegacyDataTransfer(legacyDir, dir, true);
                String msg = "Popup test result: data-transfer selected `" + (transfer ? "Transfer data" : "Start fresh") + "`. No data was copied and no acknowledgement flag was written.";
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } break;
            case "data-folder-notice": {
                String kind = ls.hasNext() ? ls.next().trim().toLowerCase() : "copied";
                DataFolderMigrationResult migration = popupTestMigrationResult(kind);
                if (migration == null)
                {
                    String msg = "Unknown data-folder-notice state: " + kind + " (expected copied, declined, skipped, or failed)";
                    LOG.warn(msg);
                    if (out != null) out.accept("[warn] " + msg);
                    return;
                }
                this.showDataFolderMigrationNotice(migration, true, out);
            } break;
            default: {
                String msg = "Unknown popup test: " + popup;
                LOG.warn(msg);
                if (out != null) out.accept("[warn] " + msg);
            } break;
        }
    }

    DataFolderMigrationResult popupTestMigrationResult(String kind)
    {
        File legacyDir = LEGACY_DIR != null ? LEGACY_DIR : defaultDataDir(LEGACY_GROUP, NAME, System.getenv("LOCALAPPDATA"), System.getenv("XDG_DATA_HOME"));
        if ("copied".equals(kind) || "transfer".equals(kind) || "transferred".equals(kind))
            return DataFolderMigrationResult.copied(legacyDir, dir);
        if ("declined".equals(kind) || "fresh".equals(kind) || "start-fresh".equals(kind))
            return DataFolderMigrationResult.declined(legacyDir, dir);
        if ("skipped".equals(kind) || "existing".equals(kind))
            return DataFolderMigrationResult.skippedExistingTarget(legacyDir, dir);
        if ("failed".equals(kind) || "error".equals(kind))
            return DataFolderMigrationResult.failed(legacyDir, dir, "CLI popup test failure; no data was copied.");
        return null;
    }

    void maybePollAction()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC),
                       next = this.settings.nextPollAction.getOrNull();
        if (next == null || now.isAfter(next))
        {
            long seconds = this.discord.pollQueuedAction();
            this.settings.nextPollAction.set(now.plusSeconds(Math.max(60L, Math.min(seconds, 3600L))));
        }
    }

    void maybeCheckUpdate()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(this.settings.lastUpdateCheck.getOrSupply().plusHours(1)))
        {
            this.settings.lastUpdateCheck.set(now);
            this.checkUpdate();
        }
    }

    void maybeCheckAnnouncement()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(this.settings.lastAnnouncementCheck.getOrSupply().plusMinutes(10L)))
        {
            this.settings.lastAnnouncementCheck.set(now);
            this.checkAnnouncement();
        }
    }

    String newerVersion = null,
           allVersions[] = {};
    private final Object metaCacheLock = new Object();
    private ScarletMeta cachedMeta;
    private OffsetDateTime cachedMetaFetchedAt;
    private String cachedMetaEtag,
                   cachedMetaLastModified;
    VrchatApiVersionChecker.Report vrchatApiPreflightReport = null;

    void checkVrchatApiPreflight()
    {
        this.splash.splashSubtext(I18n.tr("splash.checkingApiStatus"));
        // Run off the startup path: this pings JitPack (which can be slow to cold-build a
        // fresh artifact), and it's non-critical, so it must never hold up launch. It
        // still catches an upstream vrchatapi-java bump — it just reports as soon as it
        // finishes rather than blocking, and updates the status / warning when ready.
        this.exec.execute(() ->
        {
            VrchatApiVersionChecker.Report report = VrchatApiVersionChecker.check();
            this.vrchatApiPreflightReport = report;
            if (report.failure != null)
                this.logVrchatApiCheckFailure("VRChat API preflight check", report.failure);
            switch (report.level)
            {
            case OK:
            case INFO:
                LOG.info(report.message);
                this.ui.refreshVrchatApiStatus();
                return;
            case WARNING:
            default:
                LOG.warn(report.message);
                this.showVrchatApiPreflightWarning(report);
                this.discord.emitOpsAlert("VRChat API version mismatch", report.message, 0xE67E22);
                this.ui.refreshVrchatApiStatus();
                return;
            }
        });
    }

    // ── Health watchdog ────────────────────────────────────────────────────
    // Scarlet's dangerous failures are silent: the log tailer dying or the VRChat
    // session expiring both just make moderation quietly stop. The watchdog makes
    // them loud (popup + ops channel) and, for the session, self-healing.

    long healthWatchdogStartedMillis = 0L;
    private int healthTickCounter = 0;
    private boolean tailerStallAlerted = false,
                    sessionLostAlerted = false;
    private long lastSessionRecoveryAttemptMillis = 0L;
    /** How long the VRChat log may be silent (while VRChat runs) before alerting. */
    static final long TAILER_STALL_MILLIS = 5L * 60_000L;
    /** Minimum spacing between unattended re-login attempts. */
    static final long SESSION_RECOVERY_RETRY_MILLIS = 10L * 60_000L;

    void healthWatchdogTick()
    {
        try
        {
            this.checkTailerHealth();
        }
        catch (Throwable t)
        {
            LOG.debug("Tailer health check failed", t);
        }
        // The session probe is a real API call; run it on every 5th tick (~5 min).
        if (++this.healthTickCounter % 5 == 0) try
        {
            this.checkSessionHealth();
        }
        catch (Throwable t)
        {
            LOG.debug("Session health check failed", t);
        }
    }

    private void checkTailerHealth()
    {
        if (ScarletUI.findVRChatPids().isEmpty())
        {
            // VRChat not running: a quiet log is expected; clear any stall latch so the
            // next session starts fresh.
            this.tailerStallAlerted = false;
            return;
        }
        long lastEntry = this.logs.lastEntryMillis(),
             reference = lastEntry > 0L ? lastEntry : this.healthWatchdogStartedMillis,
             quietMillis = System.currentTimeMillis() - reference;
        if (quietMillis > TAILER_STALL_MILLIS)
        {
            if (!this.tailerStallAlerted)
            {
                this.tailerStallAlerted = true;
                long quietMinutes = quietMillis / 60_000L;
                LOG.warn("VRChat is running but no log lines have been seen for {} minutes - the log tailer may be stalled", quietMinutes);
                this.splash.queueFeedbackPopup(null, 10_000L,
                    I18n.tr("health.tailerStalled"),
                    I18n.tr("health.tailerStalledDetail", quietMinutes),
                    java.awt.Color.ORANGE, java.awt.Color.ORANGE);
                this.discord.emitOpsAlert("VRChat log tailer stalled",
                    "VRChat is running, but Scarlet has not seen a log line in " + quietMinutes
                    + " minutes. Instance monitoring (joins/leaves, watched groups, TTS) may be down."
                    + " Restarting Scarlet usually recovers this.", 0xE74C3C);
            }
        }
        else if (this.tailerStallAlerted && lastEntry > 0L)
        {
            this.tailerStallAlerted = false;
            LOG.info("VRChat log lines are flowing again");
            this.splash.queueFeedbackPopup(null, 6_000L,
                I18n.tr("health.tailerRecovered"), null,
                java.awt.Color.GREEN, java.awt.Color.GREEN);
            this.discord.emitOpsAlert("VRChat log tailer recovered",
                "Log lines are flowing again; instance monitoring has resumed.", 0x2ECC71);
        }
    }

    private void checkSessionHealth()
    {
        if (!this.vrc.isSessionValid())
            return; // never logged in this run; startup/login flow owns that state
        Boolean probe = this.vrc.probeSession();
        if (probe == null)
            return; // inconclusive (network trouble is not an auth verdict)
        if (probe.booleanValue())
        {
            if (this.sessionLostAlerted)
            {
                this.sessionLostAlerted = false;
                LOG.info("VRChat session is working again");
                this.splash.queueFeedbackPopup(null, 8_000L,
                    I18n.tr("health.sessionRecovered"), null,
                    java.awt.Color.GREEN, java.awt.Color.GREEN);
                this.discord.emitOpsAlert("VRChat session recovered",
                    "Scarlet's VRChat session is authorized again; moderation has resumed.", 0x2ECC71);
            }
            return;
        }
        // Session is dead. Alert once, then retry unattended recovery with spacing.
        if (!this.sessionLostAlerted)
        {
            this.sessionLostAlerted = true;
            LOG.warn("VRChat session is no longer authorized; attempting unattended re-login");
            this.splash.queueFeedbackPopup(null, 10_000L,
                I18n.tr("health.sessionLost"),
                I18n.tr("health.sessionLostDetail"),
                java.awt.Color.PINK, java.awt.Color.PINK);
            this.discord.emitOpsAlert("VRChat session lost",
                "VRChat rejected Scarlet's session as unauthorized. Audit polling and moderation"
                + " actions are failing. Scarlet will attempt unattended re-login with the stored"
                + " credentials.", 0xE74C3C);
        }
        long now = System.currentTimeMillis();
        if (now - this.lastSessionRecoveryAttemptMillis < SESSION_RECOVERY_RETRY_MILLIS)
            return;
        this.lastSessionRecoveryAttemptMillis = now;
        if (this.vrc.tryRecoverSession())
        {
            this.sessionLostAlerted = false;
            LOG.info("Unattended re-login succeeded");
            this.splash.queueFeedbackPopup(null, 8_000L,
                I18n.tr("health.sessionRecovered"), null,
                java.awt.Color.GREEN, java.awt.Color.GREEN);
            this.discord.emitOpsAlert("VRChat session recovered",
                "Unattended re-login succeeded; moderation has resumed.", 0x2ECC71);
        }
        else
        {
            this.discord.emitOpsAlert("VRChat re-login failed",
                "Unattended re-login did not succeed; will retry every "
                + (SESSION_RECOVERY_RETRY_MILLIS / 60_000L) + " minutes. If this persists,"
                + " restart Scarlet and check the stored VRChat credentials.", 0xE74C3C);
        }
    }

    void logVrchatApiCheckFailure(String context, Throwable failure)
    {
        String summary = VrchatApiVersionChecker.summarizeFailure(failure);
        if (VrchatApiVersionChecker.isExpectedUnavailable(failure))
            LOG.debug("{} unavailable: {}", context, summary);
        else
            LOG.debug(context + " issue: " + summary, failure);
    }

    void showVrchatApiPreflightWarning(VrchatApiVersionChecker.Report report)
    {
        if (Platform.forceHeadlessUi() || GraphicsEnvironment.isHeadless())
            return;
        Swing.invokeWait(() ->
        {
            StringBuilder text = new StringBuilder(report.message);
            if (report.updateAvailable)
            {
                text.append("\n\nKozyBlake/Scarlet will continue starting, but if VRChat features behave oddly, you may need a newer KozyBlake/Scarlet build with an updated API adapter.");
                text.append("\n\nIf this is causing problems, please open a ticket in the KozyBlake/Scarlet Discord server and ping BlakeBelladonna or Vinyarion.");
                int choice = JOptionPane.showOptionDialog(
                    this.ui.getParentComponent(),
                    Swing.dialogMessage(text.toString()),
                    "VRChat API Update Available",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    new Object[] { "Continue", "Open API Page", "Open Discord" },
                    "Continue"
                );
                if (choice == 1)
                    MiscUtils.AWTDesktop.browse(URI.create(VRCHAT_API_RELEASES_URL));
                else if (choice == 2)
                    MiscUtils.AWTDesktop.browse(URI.create(SCARLET_DISCORD_URL));
                return;
            }
            JOptionPane.showMessageDialog(
                this.ui.getParentComponent(),
                Swing.dialogMessage(text.toString()),
                "VRChat API Status",
                JOptionPane.WARNING_MESSAGE
            );
        });
    }

    /**
     * Result of an update probe against {@link #META_URL}. Returned by
     * {@link #pollMeta()} so both the periodic background check and the manual
     * "Check for updates" UI control can share the same logic.
     */
    public static final class UpdateCheckResult
    {
        /** Version reported by meta.json — null when the check failed. */
        public final String latestVersion;
        /** Non-null when the meta probe failed (network, parse, etc.). */
        public final String error;
        /** True when {@link #latestVersion} is strictly newer than {@link #VERSION}. */
        public final boolean updateAvailable;
        UpdateCheckResult(String latestVersion, String error, boolean updateAvailable)
        {
            this.latestVersion = latestVersion;
            this.error = error;
            this.updateAvailable = updateAvailable;
        }
    }

    private static final class MetaCheckResult
    {
        final ScarletMeta meta;
        final String error;
        final boolean notModified;
        MetaCheckResult(ScarletMeta meta, String error, boolean notModified)
        {
            this.meta = meta;
            this.error = error;
            this.notModified = notModified;
        }
    }

    private MetaCheckResult fetchMeta(boolean force)
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        synchronized (this.metaCacheLock)
        {
            if (!force && this.cachedMeta != null && this.cachedMetaFetchedAt != null && now.isBefore(this.cachedMetaFetchedAt.plusSeconds(30L)))
                return new MetaCheckResult(this.cachedMeta, null, false);
        }

        HttpURLConnection connection = null;
        try
        {
            String metaUrl = metaRequestUrl(force);
            String etag;
            String lastModified;
            ScarletMeta fallbackMeta;
            synchronized (this.metaCacheLock)
            {
                etag = this.cachedMetaEtag;
                lastModified = this.cachedMetaLastModified;
                fallbackMeta = this.cachedMeta;
            }
            connection = HttpURLInputStream.openConnection(metaUrl, "GET", conn ->
            {
                conn.setRequestProperty("Accept", "application/json");
                if (fallbackMeta != null && etag != null && !etag.trim().isEmpty())
                    conn.setRequestProperty("If-None-Match", etag);
                if (fallbackMeta != null && lastModified != null && !lastModified.trim().isEmpty())
                    conn.setRequestProperty("If-Modified-Since", lastModified);
            });

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && fallbackMeta != null)
            {
                synchronized (this.metaCacheLock)
                {
                    this.cachedMetaFetchedAt = now;
                }
                LOG.debug("meta.json unchanged (HTTP 304); reusing cached metadata");
                return new MetaCheckResult(fallbackMeta, null, true);
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND)
                throw new FileNotFoundException(metaUrl);
            if (status < 200 || status >= 300)
                throw new IOException("HTTP " + status + " from " + metaUrl);

            ScarletMeta meta;
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))
            {
                meta = GSON_PRETTY.fromJson(reader, ScarletMeta.class);
            }
            synchronized (this.metaCacheLock)
            {
                this.cachedMeta = meta;
                this.cachedMetaFetchedAt = now;
                this.cachedMetaEtag = connection.getHeaderField("ETag");
                this.cachedMetaLastModified = connection.getHeaderField("Last-Modified");
            }
            return new MetaCheckResult(meta, null, false);
        }
        catch (FileNotFoundException ex)
        {
            LOG.warn("Update metadata not found at {}; skipping update notice", META_URL);
            return new MetaCheckResult(null, "Update metadata not found at " + META_URL, false);
        }
        catch (Exception ex)
        {
            LOG.error("Failed to download meta", ex);
            return new MetaCheckResult(null, "Failed to download meta: " + ex, false);
        }
        finally
        {
            if (connection != null)
                connection.disconnect();
        }
    }

    private static String metaRequestUrl(boolean force)
    {
        long now = System.currentTimeMillis();
        long bucket = force ? now : now / META_CACHE_BUSTER_INTERVAL_MILLIS;
        return META_URL + "?scarlet_meta=" + bucket;
    }

    public static boolean isSafeReleaseTag(String value)
    {
        return value != null && SAFE_RELEASE_TAG.matcher(value).matches();
    }

    public static URI releaseUri(String releaseTag)
    {
        if (!isSafeReleaseTag(releaseTag))
            throw new IllegalArgumentException("Unsafe Scarlet release tag: " + releaseTag);
        return URI.create(GITHUB_URL + "/releases/tag/" + releaseTag);
    }

    UpdateCheckResult pollMeta()
    {
        return this.pollMeta(false);
    }
    UpdateCheckResult pollMeta(boolean force)
    {
        try
        {
            MetaCheckResult metaResult = this.fetchMeta(force);
            if (metaResult.error != null)
                return new UpdateCheckResult(null, metaResult.error, false);
            ScarletMeta meta = metaResult.meta;
            if (meta == null)
                return new UpdateCheckResult(null, "meta.json was empty or could not be parsed", false);
            String cmp_version = this.alertForPreviewUpdates.get() || MiscUtils.isPreviewVersion(VERSION) ? meta.latest_build : meta.latest_release;
            if (cmp_version == null)
                return new UpdateCheckResult(null, "meta.json did not include a usable version field", false);
            if (!isSafeReleaseTag(cmp_version))
                return new UpdateCheckResult(null, "meta.json included an unsafe release tag", false);
            // Use Scarlet's flipped comparator: suffixed builds are treated as
            // iterations ahead of the bare release, not pre-releases.
            boolean newer = MiscUtils.compareScarletVersion(VERSION, cmp_version) < 0;
            return new UpdateCheckResult(cmp_version, null, newer);
        }
        catch (Exception ex)
        {
            LOG.error("Failed to check update metadata", ex);
            return new UpdateCheckResult(null, "Failed to check update metadata: " + ex, false);
        }
    }

    void refreshAllVersions()
    {
        String[] release_names = GithubApi.release_names(FORK_GROUP, FORK_REPOSITORY);
        if (release_names == null)
        {
            LOG.error("Failed to fetch release names from {}/{}", FORK_GROUP, FORK_REPOSITORY);
        }
        else
        {
            // Sort with the Scarlet-flavoured comparator so suffixed builds
            // sort newer than the bare release.
            Arrays.sort(release_names, MiscUtils.SCARLET_VERSION_CMP_NEWEST_FIRST);
            this.allVersions = release_names;
        }
    }

    void checkUpdate()
    {
        UpdateCheckResult result = this.pollMeta();
        if (result.updateAvailable && !Objects.equals(this.newerVersion, result.latestVersion))
        {
            String latest = result.latestVersion;
            LOG.info(NAME+" version "+latest+" available");
            if (this.alertForUpdates.get())
            {
                this.settings.requireConfirmYesNoAsync(
                    I18n.tr("ui.updateAvailableMsg1", VERSION, latest) + "\n\n" + I18n.tr("ui.updateAvailableMsg2"),
                    I18n.tr("ui.updateAvailableTitle"),
                    () -> MiscUtils.AWTDesktop.browse(releaseUri(latest)), null);
            }
            this.discord.emitOpsAlert("Scarlet update available",
                "Running " + VERSION + "; " + latest + " has been released.", 0x3498DB);
            this.newerVersion = latest;
        }
        if (result.updateAvailable)
            this.refreshAllVersions();
    }

    /**
     * Manual update check entry point invoked by the UI. Runs the full
     * meta + GitHub-releases probe synchronously on the calling thread and
     * returns the result so the UI can display it. Callers should dispatch
     * this onto {@link #exec} instead of running it on the EDT.
     */
    public UpdateCheckResult checkUpdateNow()
    {
        UpdateCheckResult result = this.pollMeta(true);
        this.refreshAllVersions();
        if (result.updateAvailable)
            this.newerVersion = result.latestVersion;
        return result;
    }

    // ── Announcement broadcast system ──────────────────────────────────────
    // Mirrors the update-check shape and reads the *same* meta.json file
    // — the optional `announcement` sub-object inside it. Maintainers edit
    // that sub-object on the fork repo's main branch to push notices
    // ("VRChat API change tomorrow, expect breakage") to every running
    // Scarlet instance; remove the sub-object (or set it to null) to
    // clear it. The `id` field is the dedup key so the same notice
    // doesn't re-prompt; bump the id to broadcast a new one. Older
    // Scarlet builds without the announcement code silently ignore the
    // sub-object (Gson is lenient about unknown fields).

    /**
     * Result of an announcement probe against the {@code announcement}
     * sub-object inside {@link #META_URL}.
     * Shared by the periodic background check and the manual UI control.
     */
    public static final class AnnouncementCheckResult
    {
        /** Parsed announcement — null when no announcement, the file is empty, or the probe failed. */
        public final ScarletAnnouncement announcement;
        /** Non-null when the probe failed (network, parse, etc.). */
        public final String error;
        /** True when {@link #announcement} is non-null AND its id differs from the last one the user saw AND it hasn't expired. */
        public final boolean isNew;
        AnnouncementCheckResult(ScarletAnnouncement announcement, String error, boolean isNew)
        {
            this.announcement = announcement;
            this.error = error;
            this.isNew = isNew;
        }
    }

    AnnouncementCheckResult pollAnnouncement()
    {
        return this.pollAnnouncement(false);
    }
    AnnouncementCheckResult pollAnnouncement(boolean force)
    {
        try
        {
            MetaCheckResult metaResult = this.fetchMeta(force);
            if (metaResult.error != null)
                return new AnnouncementCheckResult(null, metaResult.error, false);
            ScarletMeta meta = metaResult.meta;
            ScarletAnnouncement ann = meta == null ? null : meta.announcement;
            // Missing / empty announcement sub-object → no current notice. Stay silent.
            if (ann == null || ann.message == null || ann.message.trim().isEmpty())
                return new AnnouncementCheckResult(null, null, false);
            // Expired announcement → ignore silently so a forgotten one self-cleans.
            if (ann.expires != null && !ann.expires.trim().isEmpty())
            {
                try
                {
                    OffsetDateTime expiresAt = OffsetDateTime.parse(ann.expires.trim());
                    if (OffsetDateTime.now(ZoneOffset.UTC).isAfter(expiresAt))
                        return new AnnouncementCheckResult(null, null, false);
                }
                catch (Exception ex)
                {
                    LOG.warn("Announcement {} has an unparseable 'expires' field ({}); treating as never-expiring", ann.id, ann.expires);
                }
            }
            String seenId = this.settings.lastAnnouncementId.getOrNull();
            boolean isNew = ann.id == null || !Objects.equals(seenId, ann.id);
            return new AnnouncementCheckResult(ann, null, isNew);
        }
        catch (Exception ex)
        {
            LOG.error("Failed to check announcement metadata", ex);
            return new AnnouncementCheckResult(null, "Failed to check announcement metadata: " + ex, false);
        }
    }

    void checkAnnouncement()
    {
        AnnouncementCheckResult result = this.pollAnnouncement();
        if (result.isNew && result.announcement != null && this.alertForAnnouncements.get())
        {
            ScarletAnnouncement ann = result.announcement;
            LOG.info("Announcement {} received: {}", ann.id, ann.title != null ? ann.title : ann.message);
            this.ui.showAnnouncement(ann);
            if (ann.id != null)
                this.settings.lastAnnouncementId.set(ann.id);
        }
    }

    /**
     * Manual announcement check entry point invoked by the UI. Runs the
     * probe on the calling thread and returns the result so the UI can
     * report success/up-to-date/no-announcement explicitly. Callers should
     * dispatch this onto {@link #exec} instead of running it on the EDT.
     * <p>
     * Unlike the periodic check, this does NOT update
     * {@code lastAnnouncementId} — the caller decides whether the user
     * actually acknowledged the announcement.
     */
    public AnnouncementCheckResult checkAnnouncementNow()
    {
        return this.pollAnnouncement(true);
    }

    /**
     * Record that the user has acknowledged the announcement with the given
     * id, so it won't re-prompt on the next poll. Called from the manual
     * dialog after the user dismisses it.
     */
    public void acknowledgeAnnouncement(String id)
    {
        if (id != null)
            this.settings.lastAnnouncementId.set(id);
    }

    // ---- Fast follow-into-new-instance detection ------------------------------------------------
    // The audit-driven launch (emitInstanceCreate) waits for the group.instance.create audit entry,
    // which is bounded by the audit poll cadence AND VRChat's audit ingest lag — together ~90s.
    // getGroupInstances reflects live open instances with far less lag, so when the follow feature is
    // enabled we poll it on a short, self-limiting cadence and cold-boot the client the moment a new
    // group instance appears. The launch is deduped (ScarletDiscord.launchClientIntoInstanceIfEnabled)
    // so the later audit entry never double-launches.
    private final java.util.Set<String> knownGroupInstanceLocations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean fastInstanceBaselineSeeded = false;
    private long lastFastInstancePollMillis = 0L;
    private long fastInstancePollBackoffUntilMillis = 0L;
    void maybeFastDetectNewInstance()
    {
        // Only worth an API call when the follow-into-new-instance feature is actually on.
        if (this.staffMode || !this.discord.isLaunchOnInstanceCreateEnabled())
            return;
        int secs = this.instanceFollowFastPollSeconds.get();
        if (secs <= 0)
            return; // fast path disabled by the user
        String groupId = this.vrc.groupId;
        if (groupId == null || groupId.isEmpty())
            return;
        long period = Math.max(15L, (long) secs) * 1000L; // never faster than 15s, whatever the setting
        long now = System.currentTimeMillis();
        if (now < this.fastInstancePollBackoffUntilMillis || now - this.lastFastInstancePollMillis < period)
            return;
        this.lastFastInstancePollMillis = now;
        List<GroupInstance> instances = this.vrc.getGroupInstances(groupId);
        if (instances == null)
        {
            // Error or possible rate-limit: stand off a full minute rather than retry aggressively.
            this.fastInstancePollBackoffUntilMillis = now + 60_000L;
            return;
        }
        java.util.Set<String> current = new java.util.HashSet<>();
        for (GroupInstance gi : instances)
        {
            String loc = gi == null ? null : gi.getLocation();
            if (loc != null && !loc.isEmpty())
                current.add(loc);
        }
        if (!this.fastInstanceBaselineSeeded)
        {
            // First successful poll: adopt whatever is already open as the baseline so pre-existing
            // instances are never mistaken for freshly created ones.
            this.knownGroupInstanceLocations.addAll(current);
            this.fastInstanceBaselineSeeded = true;
            return;
        }
        String botLocation = this.eventListener == null ? null : this.eventListener.clientLocation;
        String newInstance = null;
        for (String loc : current)
        {
            if (this.knownGroupInstanceLocations.contains(loc) || loc.equals(botLocation))
                continue;
            newInstance = loc; // act on one new instance per cycle
        }
        // Record everything currently open (so each fires at most once) and prune closed ones.
        this.knownGroupInstanceLocations.addAll(current);
        this.knownGroupInstanceLocations.retainAll(current);
        if (newInstance != null)
        {
            LOG.info("Fast instance detection: new group instance {} — following", newInstance);
            this.discord.launchClientIntoInstanceIfEnabled(newInstance);
            // Nudge the audit poll so the Discord instance embed/thread still posts promptly.
            this.pollAuditSoon();
        }
    }

    void maybeCheckInstances()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(this.settings.lastInstancesCheck.getOrSupply().plusMinutes(5L)))
        {
            this.settings.lastInstancesCheck.set(now);
            this.checkInstances();
        }
    }

    OffsetDateTime lastInstanceEnforce = OffsetDateTime.now(ZoneOffset.UTC);
    void maybeEnforceInstances()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(this.lastInstanceEnforce.plusMinutes(1L)))
        {
            this.lastInstanceEnforce = now;
            this.enforceInstances();
        }
    }

    // Auto-close the bot's OWN instance once everyone else has left and the configured idle time
    // elapses. Deliberately uses the live log-tailed roster (Scarlet's own presence data), NOT the
    // group-instance API, so a genuinely active instance is never mistaken for empty. Only ever
    // touches the one instance the bot is currently in.
    private String autoCloseTrackedLocation = null;
    private OffsetDateTime autoCloseIdleSince = null; // when it last became bot-only; null while others are present
    void maybeAutoCloseBotInstance()
    {
        int minutes = this.autoCloseBotInstanceMinutes.get();
        String loc = this.eventListener.clientLocation;
        if (minutes <= 0 || loc == null)
        {
            // Feature off, or the bot isn't in an instance (e.g. a host/mod already closed it): stand down.
            this.autoCloseTrackedLocation = null;
            this.autoCloseIdleSince = null;
            return;
        }
        if (!loc.equals(this.autoCloseTrackedLocation))
        {
            // Bot moved to a different instance: start fresh on this one.
            this.autoCloseTrackedLocation = loc;
            this.autoCloseIdleSince = null;
        }
        if (this.countOtherUsersInClientInstance() > 0)
        {
            // Someone else is here: keep it open, reset the idle timer to the full duration.
            this.autoCloseIdleSince = null;
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (this.autoCloseIdleSince == null)
        {
            // Just became bot-only: begin counting.
            this.autoCloseIdleSince = now;
            return;
        }
        if (now.isBefore(this.autoCloseIdleSince.plusMinutes(minutes)))
            return; // Idle, but not long enough yet.
        // Timer elapsed: re-check the roster one last time before acting, then close.
        if (this.countOtherUsersInClientInstance() > 0)
        {
            this.autoCloseIdleSince = null; // Someone showed up at the last moment.
            return;
        }
        this.autoCloseIdleBotInstance(loc, minutes);
        this.autoCloseTrackedLocation = null;
        this.autoCloseIdleSince = null;
    }
    /** Users in the bot's current instance other than the bot itself, from the live join/leave roster. */
    int countOtherUsersInClientInstance()
    {
        Set<String> roster = this.eventListener.clientLocation_userIdsJoinOrder;
        if (roster == null)
            return 0;
        String self = this.vrc.currentUserId;
        int count = 0;
        synchronized (roster)
        {
            for (String userId : roster)
                if (userId != null && !userId.equals(self))
                    count++;
        }
        return count;
    }
    void autoCloseIdleBotInstance(String location, int minutes)
    {
        Location locationModel = Location.of(location);
        if (locationModel == null || !locationModel.isConcrete())
        {
            LOG.warn("Auto-close: could not parse the bot's instance location '"+location+"'; not closing.");
            return;
        }
        String worldName;
        try
        {
            io.github.vrchatapi.model.World world = this.vrc.getWorld(locationModel.world);
            worldName = world == null ? locationModel.world : world.getName();
        }
        catch (Exception ex)
        {
            worldName = locationModel.world;
        }
        if (this.vrc.closeInstance(locationModel.world, locationModel.instance, Boolean.TRUE, null) != null)
        {
            LOG.info("Auto-closed idle bot instance "+location+" ("+worldName+") after "+minutes+" min bot-only.");
            try { this.discord.emitExtendedInstanceEnforcement(this, location, worldName, "Auto-closed: idle ("+minutes+" min, bot only)"); }
            catch (Exception ex) { LOG.warn("Auto-close: failed to emit close notice", ex); }
        }
        else
        {
            LOG.warn("Auto-close: failed to close idle bot instance "+location+" ("+worldName+").");
        }
    }

    OffsetDateTime lastCalendarUpdate = OffsetDateTime.now(ZoneOffset.UTC);
    void maybeUpdateCalendar()
    {
        if (!Features.CALENDAR_ENABLED)
            return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(this.lastCalendarUpdate.plusMinutes(1L)))
        {
            this.lastCalendarUpdate = now;
            this.calendar.update();
        }
    }

    void checkInstances()
    {
        List<GroupInstance> groupInstances = this.vrc.getGroupInstances(this.vrc.groupId);
        if (groupInstances == null || groupInstances.isEmpty())
            return;
        Set<String> locations = this.data.liveInstancesMetadata_getLocations();
        if (locations == null || locations.isEmpty())
            return;
        locations = new HashSet<>(locations);
        for (GroupInstance groupInstance : groupInstances)
        {
            String location = groupInstance.getLocation();
            locations.remove(location);
            ScarletData.InstanceEmbedMessage instanceEmbedMessage = this.data.liveInstancesMetadata_getLocationInstanceEmbedMessage(location, false);
            this.discord.emitExtendedInstanceMonitor(this, location, instanceEmbedMessage);
            
        }
        if (locations.isEmpty())
            return;
        for (String location : locations)
        {
            String auditEntryId = this.data.liveInstancesMetadata_getLocationAudit(location, true);
            ScarletData.InstanceEmbedMessage instanceEmbedMessage = this.data.liveInstancesMetadata_getLocationInstanceEmbedMessage(location, true);
            this.discord.emitExtendedInstanceInactive(this, location, auditEntryId, instanceEmbedMessage);
        }
    }

    void enforceInstances()
    {
        EnforcementAgeState enforceAge = this.enforceInstances18plus.get();
        EnforcementListState enforceWorlds = this.enforceInstancesWorlds.get();
        if (enforceAge == EnforcementAgeState.DISABLED && enforceWorlds == EnforcementListState.DISABLED)
            return;
        List<GroupInstance> groupInstances = this.vrc.getGroupInstances(this.vrc.groupId);
        if (groupInstances == null || groupInstances.isEmpty())
            return;
        String[] enforceWorldsList = this.enforceInstancesWorldList.get();
//        this.vrc.getWorld(API_BASE_0)t
        for (GroupInstance groupInstance : groupInstances)
        {
            String worldId = groupInstance.getWorld().getId(),
                   worldName = groupInstance.getWorld().getName(),
                   instanceId = groupInstance.getInstanceId(),
                   location = groupInstance.getLocation();
            switch (enforceWorlds)
            {
            case ENABLED_WHITELIST:
            {
                if (0 > MiscUtils.indexOf(worldId, enforceWorldsList))
                {
                    if (this.vrc.closeInstance(worldId, instanceId, true, null) != null)
                    {
                        this.discord.emitExtendedInstanceEnforcement(this, location, worldName, "World not whitelisted");
                        LOG.info("Instance enforcement: "+location+" ("+worldName+"): World not whitelisted");
                    }
                    else
                    {
                        LOG.warn("Instance enforcement failure: "+location+" ("+worldName+"): World not whitelisted");
                    }
                    continue;
                }
            }
            break;
            case ENABLED_BLACKLIST:
            {
                if (0 <= MiscUtils.indexOf(groupInstance.getWorld().getId(), enforceWorldsList))
                {
                    if (this.vrc.closeInstance(worldId, instanceId, true, null) != null)
                    {
                        this.discord.emitExtendedInstanceEnforcement(this, location, worldName, "World blacklisted");
                        LOG.info("Instance enforcement: "+location+" ("+worldName+"): World blacklisted");
                    }
                    else
                    {
                        LOG.warn("Instance enforcement failure: "+location+" ("+worldName+"): World blacklisted");
                    }
                    continue;
                }
            }
            default:
            }
            switch (enforceAge)
            {
            case ENABLED_NEVER_18_PLUS:
            {
                Location locationModel = Location.of(worldId, instanceId);
                if (locationModel.isConcrete() && locationModel.ageGate)
                {
                    if (this.vrc.closeInstance(worldId, instanceId, true, null) != null)
                    {
                        this.discord.emitExtendedInstanceEnforcement(this, location, worldName, "Age-gating disallowed");
                        LOG.info("Instance enforcement: "+location+" ("+worldName+"): Age-gating disallowed");
                    }
                    else
                    {
                        LOG.warn("Instance enforcement failure: "+location+" ("+worldName+"): Age-gating disallowed");
                    }
                    continue;
                }
            }
            break;
            case ENABLED_ONLY_18_PLUS:
            {
                Location locationModel = Location.of(worldId, instanceId);
                if (locationModel.isConcrete() && !locationModel.ageGate)
                {
                    if (this.vrc.closeInstance(worldId, instanceId, true, null) != null)
                    {
                        this.discord.emitExtendedInstanceEnforcement(this, location, worldName, "Age-gating mandatory");
                        LOG.info("Instance enforcement: "+location+" ("+worldName+"): Age-gating mandatory");
                    }
                    else
                    {
                        LOG.warn("Instance enforcement failure: "+location+" ("+worldName+"): Age-gating mandatory");
                    }
                    continue;
                }
            }
            default:
            }
        }
    }

    void maybeModSummary()
    {
        if (!Features.CALENDAR_ENABLED)
            return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC),
                       next = this.settings.nextModSummary.getOrNull();
        if (next == null)
        {
            next = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
            while (now.isBefore(next))
                next = next.minusHours(24L);
        }
        if (now.isAfter(next))
        {
            this.settings.nextModSummary.set(next.plusHours(this.settings.heuristicPeriodDays.get() * 24L));
            this.modSummary(next);
        }
    }

    void modSummary(OffsetDateTime endOfDay)
    {
        this.discord.emitModSummary(this, endOfDay);
    }

    void maybeOutstandingMod()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC),
                       next = this.settings.nextOutstandingMod.getOrNull();
        if (next == null)
        {
            next = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
            while (now.isBefore(next))
                next = next.minusHours(24L);
        }
        if (now.isAfter(next))
        {
            this.settings.nextOutstandingMod.set(next.plusHours(this.settings.outstandingPeriodDays.get() * 24L));
            this.outstandingMod(next);
        }
    }

    void outstandingMod(OffsetDateTime endOfDay)
    {
        this.discord.emitOutstandingMod(this, endOfDay);
    }

    void maybeSaveData()
    {
        this.data.saveDirty();
    }

    void maybeRefresh()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (this.wantsVrcRefresh || now.isAfter(this.settings.lastAuthRefresh.getOrSupply().plusHours(72)))
        {
            this.settings.lastAuthRefresh.set(now);
            this.vrc.refresh();
            this.wantsVrcRefresh = false;
        }
    }

    boolean wantsVrcRefresh = false;
    public void queueVrcRefresh()
    {
        this.wantsVrcRefresh = true;
    }
    /** How much already-covered time each audit query re-fetches, so out-of-order-ingested entries are never missed. */
    static final long AUDIT_QUERY_OVERLAP_MINUTES = 5L;
    /** Minimum spacing between audit polls while burst polling is active. */
    static final long AUDIT_FAST_POLL_PERIOD_MILLIS = 10_000L;
    /** How long burst polling stays active after a Scarlet-initiated moderation action. */
    static final long AUDIT_FAST_POLL_WINDOW_MILLIS = 120_000L;
    volatile long auditFastPollUntilMillis = 0L;
    /**
     * Temporarily polls the group audit log at a faster rate (every 10 seconds
     * for up to 2 minutes) so that moderation actions initiated through Scarlet
     * show up in the moderation Discord channel without waiting out the full
     * audit polling interval. The window is generous because VRChat's audit
     * endpoint has its own server-side ingest lag before entries appear.
     */
    public void pollAuditSoon()
    {
        this.auditFastPollUntilMillis = System.currentTimeMillis() + AUDIT_FAST_POLL_WINDOW_MILLIS;
    }
    /** Ends the burst polling window early, e.g. once all pending moderation actions have been observed. */
    public void endAuditFastPoll()
    {
        this.auditFastPollUntilMillis = 0L;
    }
    public boolean checkVrcRefresh(Exception ex)
    {
        String vrcRefreshMsg = ex.getMessage();
        if (vrcRefreshMsg == null || !vrcRefreshMsg.contains("HTTP response code: 401"))
            return false;
        this.queueVrcRefresh();
        return true;
    }
    void queryEmit(long currentPollInterval)
    {
        long offsetMillis = this.vrc.getLocalDriftMillis();
        if (currentPollInterval < 30_000L)
        {
            offsetMillis += (30_000L - currentPollInterval) / 10L;
        }
        OffsetDateTime cursor = this.settings.lastAuditQuery.getOrSupply();
        // Bound the backlog after a long time offline: without this, a months-old cursor meant
        // walking forward one day per poll for hours (and re-posting months-old moderation)
        // before live events appeared again.
        {
            int maxDays = Math.max(1, this.auditCatchupMaxDays.get().intValue());
            OffsetDateTime oldest = OffsetDateTime.now(ZoneOffset.UTC).minusDays(maxDays);
            if (cursor.isBefore(oldest))
            {
                LOG.warn("Audit log position is "+Duration.between(cursor, oldest).toDays()+" day(s) older than the catch-up limit; skipping ahead to "+oldest+" (setting audit_catchup_max_days="+maxDays+")");
                cursor = oldest;
                this.settings.lastAuditQuery.set(cursor);
            }
        }
        OffsetDateTime
                       // Re-query a little already-covered time on every poll: VRChat can
                       // ingest audit entries out of order, so an entry created just before
                       // the cursor may only become visible in the API after newer entries
                       // have already been seen. Without the overlap such entries would fall
                       // behind the query window and never show. Re-seen entries are
                       // deduplicated by entry ID in ScarletDiscord.process, so this cannot
                       // double-post.
                       from = cursor.minusMinutes(AUDIT_QUERY_OVERLAP_MINUTES),
                       to = OffsetDateTime.now(ZoneOffset.UTC).minusNanos(offsetMillis * 1_000_000L),
                       // The cursor itself must not be rewound by the overlap, otherwise
                       // repeated empty polls would walk it backwards indefinitely.
                       lastAuditQuery = cursor,
                       latest = from.plusHours(24);
        boolean catchupSkip = false;
        if (catchupSkip)
        {
            OffsetDateTime earliest = to.minusHours(24);
            if (from.isBefore(earliest))
            {
                LOG.info("Catching up: Skipping from "+from+" to "+earliest+" ("+Duration.between(from, earliest)+" total)");
                from = earliest;
                lastAuditQuery = to;
            }
            else
            {
                to = null;
            }
        }
        else
        {
            if (latest.isBefore(to))
            {
                LOG.info("Catching up: Only querying a 24-hour period");
                to = latest;
                lastAuditQuery = to;
                // Still behind: keep polling at the burst rate (every 10s) until caught up,
                // rather than one day per full polling interval.
                this.pollAuditSoon();
            }
            else
            {
                to = null;
            }
        }
        
        LOG.debug("Querying from "+from+" to "+(to!=null?to:"now"));
        List<GroupAuditLogEntry> entries = this.vrc.auditQuery(from, to);
        
        if (entries == null)
        {
            // The query failed (possibly a 429 rate limit): stop burst polling
            // so we fall back to the normal audit polling interval instead of
            // hammering the API. Nothing is skipped: lastAuditQuery was not
            // advanced, so the next successful poll resumes from the same point.
            this.endAuditFastPoll();
            LOG.warn("Failed to get entries from "+from+" to "+(to!=null?to:"now"));
            return;
        }
        
        for (GroupAuditLogEntry entry : entries) try
        {
            if (lastAuditQuery.isBefore(entry.getCreatedAt()))
            {
                lastAuditQuery = entry.getCreatedAt();
            }
            if (this.data.auditEntryMetadataExists(entry.getId()))
            {
                // Already seen (re-fetched via the query overlap window):
                // ScarletDiscord.process would dedupe this by ID anyway, but
                // skipping here also avoids re-triggering the updateGroupInfo
                // API call below for re-seen group/role update entries.
                continue;
            }
            switch (entry.getEventType())
            {
            case "group.update": // GroupAuditType.UPDATE
            case "group.transfer.accept": // GroupAuditType.TRANSFER_ACCEPT
            case "group.role.create": // GroupAuditType.ROLE_CREATE
            case "group.role.delete": // GroupAuditType.ROLE_DELETE
            case "group.role.update": // GroupAuditType.ROLE_UPDATE
                this.vrc.updateGroupInfo();
            default:
                this.discord.process(this, entry);
            }
            switch (entry.getEventType())
            {
            case "group.instance.warn": // GroupAuditType.INSTANCE_WARN
            case "group.instance.kick": // GroupAuditType.INSTANCE_KICK
            case "group.member.remove": // GroupAuditType.MEMBER_REMOVE
            case "group.user.ban": // GroupAuditType.USER_BAN
            case "group.user.unban": // GroupAuditType.USER_UNBAN
                // Flurry burst: in-game moderation tends to come in waves, so
                // after observing one moderation event, temporarily poll faster
                // to pick up follow-up warns/kicks/bans within seconds instead
                // of waiting out the full audit polling interval. (Placed after
                // process() so it re-arms the window that pollPending may have
                // just ended.)
                this.pollAuditSoon();
            }
            // Automatic evidence capture: trigger an external clip on kick/ban events.
            this.captureEvidenceIfKickBan(entry.getEventType());
        }
        catch (Exception ex)
        {
            LOG.error("Exception processing audit entry "+entry.getId()+" of type "+entry.getEventType()+": `"+entry.toJson()+"`", ex);
            ex.printStackTrace();
        }

        this.settings.lastAuditQuery.set(lastAuditQuery);
    }

}

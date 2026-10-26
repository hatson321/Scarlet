package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fresh JVMs exercise pre-UI decisions using only isolated synthetic data homes. */
class ScarletRenderingStartupTest
{
    @TempDir Path temp;
    private static final String PROPERTY = "sun.java2d.d3d";
    private static final String KEY = "windows_rendering_compatibility";
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    @Test void explicitDataHomeAppliesSavedCompatibilityBeforeAnyUi() throws Exception
    {
        Path root = fixture("explicit-home"), home = settings(root.resolve("chosen-home"), true);
        Map<String, String> before = snapshot(root);
        ProbeResult result = run(root, home, null, "resolve");
        assertEquals(0, result.exitCode, result.output);
        assertProperty(result, WINDOWS ? "false" : "<unset>");
        assertTrue(result.output.contains("RESOLVED=" + home.toFile().getAbsolutePath()), result.output);
        assertFalse(result.output.contains("explicit Java Direct3D option"), result.output);
        assertUnchanged(root, before);
    }

    @Test void absentOrDisabledSettingsDoNotSetThePropertyOrCreateNormalState() throws Exception
    {
        for (boolean exists : new boolean[]{false, true})
        {
            Path root = fixture(exists ? "disabled" : "absent"), home = root.resolve("chosen-home");
            if (exists) settings(home, false);
            Map<String, String> before = snapshot(root);
            ProbeResult result = run(root, home, null, "resolve");
            assertEquals(0, result.exitCode, result.output);
            assertProperty(result, "<unset>");
            assertUnchanged(root, before);
        }
    }

    @Test void explicitJvmValuesRemainAuthoritativeAcrossResolution() throws Exception
    {
        for (String value : new String[]{"true", "false"})
        {
            Path root = fixture("explicit-" + value), home = settings(root.resolve("chosen-home"), true);
            Map<String, String> before = snapshot(root);
            ProbeResult result = run(root, home, value, "resolve");
            assertEquals(0, result.exitCode, result.output);
            assertProperty(result, value);
            if (WINDOWS) assertEquals(1, occurrences(result.output, "explicit Java Direct3D option"), result.output);
            assertUnchanged(root, before);
        }
    }

    @Test void unambiguousDiscoveredHomeSuppliesItsOwnPreference() throws Exception
    {
        Path root = fixture("discovered"), home = settings(root.resolve("user/Scarlet Logger"), true);
        Map<String, String> before = snapshot(root);
        ProbeResult result = run(root, null, null, "resolve");
        assertEquals(0, result.exitCode, result.output);
        assertProperty(result, WINDOWS ? "false" : "<unset>");
        assertTrue(result.output.contains("RESOLVED=" + home.toFile().getAbsolutePath()), result.output);
        assertUnchanged(root, before);
    }

    @Test void multipleHomeChooserUsesCompatibilityIfAnyOfferedHomeRequestsIt() throws Exception
    {
        Path root = fixture("multiple-enabled");
        Path first = settings(root.resolve("first-home"), false), second = settings(root.resolve("second-home"), true);
        Map<String, String> before = snapshot(root);
        ProbeResult result = run(root, root.resolve("unused-home"), null, "choose", first.toString(), second.toString());
        assertEquals(0, result.exitCode, result.output);
        assertProperty(result, WINDOWS ? "false" : "<unset>");
        assertTrue(result.output.contains("CHOSEN=" + first.toFile().getAbsolutePath()), result.output);
        if (WINDOWS)
            assertTrue(result.output.indexOf("Windows rendering compatibility enabled") < result.output.indexOf("No interactive console"), result.output);
        assertUnchanged(root, before);
    }

    @Test void multipleHomeChooserDoesNotEnableCompatibilityWithoutARequest() throws Exception
    {
        Path root = fixture("multiple-disabled");
        Path first = settings(root.resolve("first-home"), false), second = settings(root.resolve("second-home"), false);
        Map<String, String> before = snapshot(root);
        ProbeResult result = run(root, root.resolve("unused-home"), null, "choose", first.toString(), second.toString());
        assertEquals(0, result.exitCode, result.output);
        assertProperty(result, "<unset>");
        assertUnchanged(root, before);
    }

    @Test void offlineChoosersConfigureRenderingBeforeDesktopValidationWithoutStartingTheLogger() throws Exception
    {
        for (String mode : Arrays.asList("--export", "--restore"))
        {
            Path root = fixture(mode.substring(2)), home = settings(root.resolve("chosen-home"), true);
            Map<String, String> before = snapshot(root);
            ProbeResult result = run(root, home, null, "offline", mode);
            assertEquals(1, result.exitCode, result.output);
            assertTrue(result.output.contains("chooser needs a desktop"), result.output);
            if (WINDOWS)
            {
                int configured = result.output.indexOf("Windows rendering compatibility enabled");
                assertTrue(configured >= 0 && configured < result.output.indexOf("chooser needs a desktop"), result.output);
            }
            assertFalse(result.output.contains("App: Hatson321/Scarlet Logger"), result.output);
            assertUnchanged(root, before);
        }
    }

    @Test void invalidConsoleImportRemainsReadOnlyWithCompatibilityEnabled() throws Exception
    {
        Path root = fixture("invalid-import"), home = settings(root.resolve("chosen-home"), true);
        Map<String, String> before = snapshot(root);
        ProbeResult result = run(root, home, null, "offline", "--import-bundle");
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("Usage: --import-bundle"), result.output);
        if (WINDOWS) assertTrue(result.output.contains("Windows rendering compatibility enabled"), result.output);
        assertUnchanged(root, before);
    }

    @Test void appliedPreferenceIsNotMislabeledAsAnExplicitOptionOnLaterBootstrapCalls() throws Exception
    {
        Path root = fixture("repeated"), enabled = settings(root.resolve("enabled-home"), true);
        Path disabled = settings(root.resolve("disabled-home"), false);
        Map<String, String> before = snapshot(root);
        ProbeResult result = run(root, enabled, null, "repeat", disabled.toString());
        assertEquals(0, result.exitCode, result.output);
        assertProperty(result, WINDOWS ? "false" : "<unset>");
        assertEquals(WINDOWS ? 1 : 0, occurrences(result.output, "Windows rendering compatibility enabled"), result.output);
        assertFalse(result.output.contains("explicit Java Direct3D option"), result.output);
        assertUnchanged(root, before);
    }

    @Test void relaunchRetainsOnlyExplicitRenderingArgumentsAndOfflineChildPinsTheHome() throws Exception
    {
        for (String explicit : new String[]{null, "false"})
        {
            Path root = fixture(explicit == null ? "saved-relaunch" : "explicit-relaunch");
            Path home = settings(root.resolve("chosen-home"), true);
            Map<String, String> before = snapshot(root);
            ProbeResult result = run(root, home, explicit, "relaunch");
            assertEquals(0, result.exitCode, result.output);
            assertTrue(result.output.contains("RELAUNCH_EXPLICIT=" + (explicit != null)), result.output);
            assertTrue(result.output.contains("OFFLINE_PINNED=true"), result.output);
            assertUnchanged(root, before);
        }
    }

    @Test void ineligibleLegacyHomeDoesNotOverrideTheActuallyDiscoveredHome() throws Exception
    {
        Path root = fixture("ineligible-legacy");
        Path platformRoot = root.resolve(WINDOWS ? "local-app-data" : "xdg-data");
        Path primary = Files.createDirectories(platformRoot.resolve("Hatson321/Scarlet Logger"));
        Files.write(primary.resolve("not-a-config.txt"), new byte[]{1});
        settings(platformRoot.resolve("SybylineNetwork/Scarlet"), true);
        Path chosen = settings(root.resolve("user/Scarlet Logger"), false);
        Files.write(primary.resolveSibling("Scarlet Logger.scarlet-data.lock"), new byte[0]);
        Files.write(root.resolve(WINDOWS ? "local-app-data" : "user").resolve(".scarlet-preferences.lock"), new byte[0]);
        Map<String, String> before = snapshot(root);
        ProbeResult result = run(root, null, null, "resolve");
        assertEquals(0, result.exitCode, result.output);
        assertProperty(result, "<unset>");
        assertTrue(result.output.contains("RESOLVED=" + chosen.toFile().getAbsolutePath()), result.output);
        assertFalse(result.output.contains("Transfer data?"), result.output);
        assertUnchanged(root, before);
    }

    @Test void eligibleTransferPromptUsesCompatibilityEvenWhenTheUserDeclines() throws Exception
    {
        Path root = fixture("transfer-prompt"), legacy = settings(root.resolve("legacy-home"), true);
        Path target = root.resolve("uncreated-home");
        Map<String, String> before = snapshot(root);
        ProbeResult result = run(root, target, null, "transfer", legacy.toString(), target.toString());
        assertEquals(0, result.exitCode, result.output);
        assertProperty(result, WINDOWS ? "false" : "<unset>");
        assertTrue(result.output.contains("TRANSFER=false"), result.output);
        if (WINDOWS)
            assertTrue(result.output.indexOf("Windows rendering compatibility enabled") < result.output.indexOf("Transfer data?"), result.output);
        assertUnchanged(root, before);
    }

    @Test void appearanceMetadataHasWindowsOnlyVisibilityAndRestartInstructions() throws Exception
    {
        Path root = fixture("ui-metadata"), home = settings(root.resolve("chosen-home"), false);
        // Linux Java initializes its own font cache when Swing metadata is loaded.
        // Warm only JDK fonts before taking the baseline, rather than excluding
        // any paths from the assertion that Scarlet must not mutate user data.
        if (!WINDOWS)
        {
            ProbeResult fonts = run(root, home, null, "jdk-fonts");
            assertEquals(0, fonts.exitCode, fonts.output);
            assertTrue(fonts.output.contains("JDK_FONTS_READY"), fonts.output);
        }
        Map<String, String> before = snapshot(root);
        ProbeResult result = run(root, home, null, "metadata");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("UI_METADATA_OK"), result.output);
        assertUnchanged(root, before);
    }

    @Test void typedTogglePersistsBooleansWithoutChangingAnAlreadyRunningRenderingPipeline() throws Exception
    {
        Path root = fixture("typed-persistence"), home = settings(root.resolve("chosen-home"), false);
        ProbeResult result = run(root, home, null, "persist");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("PERSISTENCE_OK"), result.output);
        assertProperty(result, "<unset>");
        try (java.io.InputStream input = Files.newInputStream(home.resolve("settings.json")))
        { assertFalse(ScarletRenderingCompatibility.readEnabled(input)); }
        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.collect(Collectors.toList()))
                assertFalse(Arrays.asList("global-prefs.key", "logs", "user-prefs", "system-prefs").contains(path.getFileName().toString()), path.toString());
        }
    }

    private Path fixture(String name) throws Exception
    {
        Path root = Files.createDirectory(temp.resolve(name));
        for (String namePart : Arrays.asList("user", "local-app-data", "xdg-data", "java-temp"))
            Files.createDirectory(root.resolve(namePart));
        return root;
    }

    private static Path settings(Path home, boolean enabled) throws Exception
    {
        Files.createDirectories(home);
        Files.write(home.resolve("settings.json"), ("{\"" + KEY + "\":" + enabled + ",\"synthetic\":true}").getBytes(StandardCharsets.UTF_8));
        return home;
    }

    private static Map<String, String> snapshot(Path root) throws Exception
    {
        Map<String, String> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.collect(Collectors.toList()))
            {
                if (path.equals(root.resolve("probe-output.txt"))) continue;
                result.put(root.relativize(path).toString(), Files.isDirectory(path) ? "directory" : Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    private static void assertUnchanged(Path root, Map<String, String> before) throws Exception
    {
        assertEquals(before, snapshot(root), "Bootstrap must not create or change settings, keys, logs, preferences, locks or migration data");
    }

    private static void assertProperty(ProbeResult result, String value)
    {
        assertTrue(result.output.contains("PROPERTY=" + value), result.output);
    }

    private static int occurrences(String text, String fragment)
    {
        int count = 0, start = 0;
        while ((start = text.indexOf(fragment, start)) >= 0) { count++; start += fragment.length(); }
        return count;
    }

    private static ProbeResult run(Path root, Path home, String explicit, String... args) throws Exception
    {
        String executable = Paths.get(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        classpath = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator), -1))
            .map(entry -> new File(entry.isEmpty() ? "." : entry).getAbsolutePath()).collect(Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>(Arrays.asList(executable, "-Djava.awt.headless=true",
            "-Duser.home=" + root.resolve("user"), "-Djava.io.tmpdir=" + root.resolve("java-temp"),
            "-Djava.util.prefs.userRoot=" + root.resolve("user-prefs"), "-Djava.util.prefs.systemRoot=" + root.resolve("system-prefs")));
        if (home != null) command.add("-DSCARLET_HOME=" + home);
        if (explicit != null) command.add("-D" + PROPERTY + "=" + explicit);
        command.addAll(Arrays.asList("-cp", classpath, StartupProbe.class.getName()));
        command.addAll(Arrays.asList(args));
        Path output = root.resolve("probe-output.txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        Map<String, String> environment = builder.environment();
        for (String name : Arrays.asList("SCARLET_HOME", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "SCARLET_GLOBAL_PW"))
            environment.remove(name);
        environment.put("HOME", root.resolve("user").toString());
        environment.put("LOCALAPPDATA", root.resolve("local-app-data").toString());
        environment.put("APPDATA", root.resolve("local-app-data").toString());
        environment.put("XDG_DATA_HOME", root.resolve("xdg-data").toString());
        // Avoid key-file fallback entirely for the deliberately JSON-only settings probe.
        if ("persist".equals(args[0])) environment.put("SCARLET_GLOBAL_PW", "synthetic-rendering-test-only");
        Process child = builder.start();
        try
        {
            if ("transfer".equals(args[0])) child.getOutputStream().write("n\n".getBytes(StandardCharsets.UTF_8));
            child.getOutputStream().close();
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Synthetic rendering bootstrap probe exceeded its deadline");
            return new ProbeResult(child.exitValue(), new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
        }
        finally
        {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private static final class ProbeResult
    {
        final int exitCode;
        final String output;
        ProbeResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    public static final class StartupProbe
    {
        public static void main(String[] args) throws Exception
        {
            if ("jdk-fonts".equals(args[0]))
            {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts();
                new javax.swing.JLabel("Synthetic font warm-up").getPreferredSize();
                System.out.println("JDK_FONTS_READY");
                return;
            }
            if ("offline".equals(args[0]))
            {
                Scarlet.main(Arrays.copyOfRange(args, 1, args.length));
                throw new AssertionError("Invalid synthetic offline operation unexpectedly returned");
            }
            if ("transfer".equals(args[0]))
            {
                System.out.println("TRANSFER=" + Scarlet.confirmLegacyDataTransfer(new File(args[1]), new File(args[2]), true));
            }
            else if ("choose".equals(args[0]))
            {
                File chosen = Scarlet.chooseDataDir(Arrays.asList(new File(args[1]), new File(args[2])));
                System.out.println("CHOSEN=" + chosen.getAbsolutePath());
            }
            else
            {
                Method resolve = Scarlet.class.getDeclaredMethod("resolveDataDirectory", boolean.class);
                resolve.setAccessible(true);
                resolve.invoke(null, true);
                System.out.println("RESOLVED=" + Scarlet.dir.getAbsolutePath());
                if ("repeat".equals(args[0])) Scarlet.configureRendering(new File(args[1]));
                if ("relaunch".equals(args[0]))
                {
                    Method relaunch = Scarlet.class.getDeclaredMethod("buildRelaunchCommand");
                    relaunch.setAccessible(true);
                    @SuppressWarnings("unchecked") List<String> command = (List<String>) relaunch.invoke(null);
                    System.out.println("RELAUNCH_EXPLICIT=" + command.contains("-D" + PROPERTY + "=false"));
                    System.out.println("OFFLINE_PINNED=" + Scarlet.buildOfflineCommand("--export").contains("-DSCARLET_HOME=" + Scarlet.dir.getAbsolutePath()));
                }
                if ("metadata".equals(args[0]))
                {
                    net.sybyline.scarlet.log.ScarletLogger.setConsoleOnly(true);
                    Field sectionsField = ScarletUI.class.getDeclaredField("SETTINGS_SECTIONS");
                    sectionsField.setAccessible(true);
                    String[][] sections = (String[][]) sectionsField.get(null);
                    String[] appearance = Arrays.stream(sections).filter(row -> "Appearance".equals(row[0])).findFirst().orElseThrow(AssertionError::new);
                    if (!Arrays.asList(appearance).contains(KEY)) throw new AssertionError("Missing appearance setting");
                    if (!"General".equals(ScarletUI.categoryForSection("Appearance"))) throw new AssertionError("Incorrect appearance category");
                    Method hidden = ScarletUI.class.getDeclaredMethod("isFeatureHiddenSettingId", String.class);
                    hidden.setAccessible(true);
                    boolean isHidden = (Boolean) hidden.invoke(null, KEY);
                    if (isHidden == WINDOWS) throw new AssertionError("Incorrect platform visibility");
                    String label = net.sybyline.scarlet.util.I18n.tr(java.util.Locale.ENGLISH, "setting.windows_rendering_compatibility");
                    String tip = net.sybyline.scarlet.util.I18n.tr(java.util.Locale.ENGLISH, "setting.windows_rendering_compatibility.tooltip");
                    if (!label.contains("restart required") || !tip.contains("Save Settings") || !tip.contains("Explicit Java launch options"))
                        throw new AssertionError("Missing restart or explicit-option guidance");
                    System.out.println("UI_METADATA_OK");
                }
                if ("persist".equals(args[0]))
                {
                    net.sybyline.scarlet.log.ScarletLogger.setConsoleOnly(true);
                    File settingsFile = new File(Scarlet.dir, "settings.json");
                    ScarletSettings settings = new ScarletSettings(null, settingsFile, false);
                    ScarletSettings.FileValuedBoolean toggle = settings.new FileValuedBoolean(KEY, "Synthetic compatibility", false);
                    if (toggle.get()) throw new AssertionError("Expected default-off preference");
                    if (!toggle.set(true, "synthetic")) throw new AssertionError("Could not enable typed preference");
                    try (java.io.InputStream input = Files.newInputStream(settingsFile.toPath()))
                    { if (!ScarletRenderingCompatibility.readEnabled(input)) throw new AssertionError("Enabled boolean was not persisted"); }
                    ScarletSettings reread = new ScarletSettings(null, settingsFile, false);
                    ScarletSettings.FileValuedBoolean restored = reread.new FileValuedBoolean(KEY, "Synthetic compatibility", false);
                    if (!restored.get()) throw new AssertionError("Enabled preference did not survive reload");
                    if (!Boolean.TRUE.equals(reread.getObject("synthetic", Boolean.class))) throw new AssertionError("Unrelated setting was lost");
                    if (!restored.set(false, "synthetic")) throw new AssertionError("Could not disable typed preference");
                    try (java.io.InputStream input = Files.newInputStream(settingsFile.toPath()))
                    { if (ScarletRenderingCompatibility.readEnabled(input)) throw new AssertionError("Disabled boolean was not persisted"); }
                    if (System.getProperty(PROPERTY) != null) throw new AssertionError("Changing saved preference must not mutate the live rendering option");
                    System.out.println("PERSISTENCE_OK");
                }
            }
            System.out.println("PROPERTY=" + System.getProperty(PROPERTY, "<unset>"));
        }
    }
}

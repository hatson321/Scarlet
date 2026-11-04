package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.sybyline.scarlet.log.ScarletLogger;
import net.sybyline.scarlet.util.ProcLock;

/** Subprocess checks keep first class initialization away from actual user state. */
public class ScarletOfflineDispatchTest
{
    @TempDir Path temp;

    @Test void headlessOfflineChoosersDoNotMigrateLegacyDataOrStartTheLogger() throws Exception
    {
        for (String mode : Arrays.asList("--export", "--restore"))
        {
            Path root = fixture(mode.substring(2));
            Set<String> before = tree(root);
            ProbeResult result = run(root, false, "net.sybyline.scarlet.Scarlet", mode);
            assertEquals(1, result.exitCode, result.output);
            assertTrue(result.output.contains("chooser needs a desktop"), result.output);
            assertFalse(result.output.contains("Transfer data?"), result.output);
            assertFalse(result.output.contains("Login"), result.output);
            assertFalse(result.output.contains("App: Hatson321/Scarlet Logger"), result.output);
            assertUnchanged(root, before);
        }
    }

    @Test void recognizingImportBeforeArgumentValidationDoesNotCreateDataOrCopyLegacyState() throws Exception
    {
        Path root = fixture("import");
        Set<String> before = tree(root);
        ProbeResult result = run(root, false, "net.sybyline.scarlet.Scarlet", "--import-bundle");
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("Usage: --import-bundle"), result.output);
        assertUnchanged(root, before);
    }

    @Test void consoleOnlyLoggingDoesNotCreateADataHomeOrFileLogger() throws Exception
    {
        for (boolean property : new boolean[] { false, true })
        {
            Path root = fixture(property ? "console-property" : "console-setter");
            Set<String> before = tree(root);
            ProbeResult result = run(root, property, ConsoleProbe.class.getName(), Boolean.toString(property));
            assertEquals(0, result.exitCode, result.output);
            assertTrue(result.output.contains("synthetic offline information"), result.output);
            assertTrue(result.output.contains("synthetic offline failure"), result.output);
            assertUnchanged(root, before);
        }
    }

    @Test void blockedNormalMigrationReportsToConsoleWithoutCreatingFilesInTheProtectedHome() throws Exception
    {
        Path root = fixture("blocked-normal-start");
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path preferenceLocks = root.resolve(windows ? "local-app-data" : "user");
        try (ProcLock.Lease importing = ProcLock.acquirePreferences(preferenceLocks.toFile(), false))
        {
            ProbeResult result = run(root, false, "net.sybyline.scarlet.Scarlet");
            assertNotEquals(0, result.exitCode, result.output);
            assertTrue(result.output.contains("could not obtain safe storage access"), result.output);
            assertFalse(Files.exists(root.resolve("local-app-data/Hatson321/Scarlet Logger")));
            assertFalse(Files.exists(root.resolve("xdg-data/Hatson321/Scarlet Logger")));
            assertFalse(Files.exists(root.resolve("user/.local/share/Hatson321/Scarlet Logger")));
        }
    }

    private Path fixture(String name) throws Exception
    {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.createDirectory(root.resolve("user"));
        for (String base : Arrays.asList("local-app-data", "xdg-data"))
        {
            Path legacy = Files.createDirectories(root.resolve(base).resolve("SybylineNetwork/Scarlet"));
            Files.write(legacy.resolve("settings.json"), "{\"synthetic_dispatch_sentinel\":true}".getBytes(StandardCharsets.UTF_8));
            Files.write(legacy.resolve("sentinel.txt"), "legacy data must remain untouched".getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    private static Set<String> tree(Path root) throws Exception
    {
        try (Stream<Path> files = Files.walk(root))
        {
            return files.map(root::relativize).map(Path::toString).collect(Collectors.toSet());
        }
    }

    private static void assertUnchanged(Path root, Set<String> before) throws Exception
    {
        Set<String> after = tree(root);
        after.remove("probe-output.txt");
        assertEquals(before, after, "An offline entry point must not create settings, migration acknowledgements, locks, logs or preference storage before validation");
        for (String base : Arrays.asList("local-app-data", "xdg-data"))
        {
            Path legacy = root.resolve(base).resolve("SybylineNetwork/Scarlet");
            assertEquals("{\"synthetic_dispatch_sentinel\":true}", new String(Files.readAllBytes(legacy.resolve("settings.json")), StandardCharsets.UTF_8));
            assertEquals("legacy data must remain untouched", new String(Files.readAllBytes(legacy.resolve("sentinel.txt")), StandardCharsets.UTF_8));
        }
    }

    private static ProbeResult run(Path root, boolean consoleProperty, String main, String... args) throws Exception
    {
        String executable = Paths.get(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        // Working in a synthetic directory must not invalidate a custom launcher's relative classpath.
        classpath = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator), -1))
            .map(entry -> new File(entry.isEmpty() ? "." : entry).getAbsolutePath())
            .collect(Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>(Arrays.asList(executable, "-Djava.awt.headless=true",
            "-Duser.home=" + root.resolve("user"), "-Djava.util.prefs.userRoot=" + root.resolve("user-prefs"),
            "-Djava.util.prefs.systemRoot=" + root.resolve("system-prefs"),
            "-Dnet.sybyline.scarlet.log.level=DEBUG"));
        if (consoleProperty) command.add("-Dscarlet.offline=true");
        command.addAll(Arrays.asList("-cp", classpath, main));
        command.addAll(Arrays.asList(args));
        Path output = root.resolve("probe-output.txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile())
            .redirectErrorStream(true).redirectOutput(output.toFile());
        Map<String, String> environment = builder.environment();
        for (String name : Arrays.asList("SCARLET_HOME", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"))
            environment.remove(name);
        environment.put("HOME", root.resolve("user").toString());
        environment.put("LOCALAPPDATA", root.resolve("local-app-data").toString());
        environment.put("APPDATA", root.resolve("local-app-data").toString());
        environment.put("XDG_DATA_HOME", root.resolve("xdg-data").toString());
        Process child = builder.start();
        try
        {
            child.getOutputStream().close();
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Synthetic offline probe exceeded its deadline");
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

    public static final class ConsoleProbe
    {
        public static void main(String[] args)
        {
            if (!Boolean.parseBoolean(args[0])) ScarletLogger.setConsoleOnly(true);
            ScarletLogger logger = new ScarletLogger("OfflineDispatchProbe");
            logger.info("synthetic offline information");
            logger.error("synthetic offline failure", new IllegalStateException("synthetic probe only"));
            if (ScarletLogger.errorCount() != 1) throw new AssertionError("Offline error count was not preserved");
            if (ScarletLogger.getActiveLogFile() != null) throw new AssertionError("Offline logging must not initialize a file logger");
        }
    }
}

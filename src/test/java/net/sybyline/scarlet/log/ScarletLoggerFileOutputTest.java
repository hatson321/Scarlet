package net.sybyline.scarlet.log;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletLoggerFileOutputTest
{
    @TempDir Path temporary;

    @Test void openingAnExistingSessionFilePreservesItsBytes() throws Exception
    {
        this.probe("existing-file");
    }

    @Test void failedStreamIsDiscardedAndRealRecordsResumeInAppendMode() throws Exception
    {
        this.probe("write-failure");
    }

    @Test void disabledFileStatusDoesNotInitializeTheFileLogger() throws Exception
    {
        this.probe("status-only");
    }

    @Test void enabledFileStatusDoesNotStartAWriterBeforeTheFirstLog() throws Exception
    {
        this.probe("status-enabled-only");
    }

    private void probe(String mode) throws Exception
    {
        Path root = Files.createDirectory(this.temporary.resolve(mode));
        String executable = Paths.get(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        classpath = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator), -1))
            .map(entry -> new File(entry.isEmpty() ? "." : entry).getAbsolutePath())
            .collect(Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>(Arrays.asList(executable, "-Djava.awt.headless=true",
            "-Dscarlet.offline=true", "-DSCARLET_HOME=" + root.resolve("data"),
            "-Djava.io.tmpdir=" + root, "-Duser.home=" + root.resolve("user"),
            "-Djava.util.prefs.PreferencesFactory=net.sybyline.scarlet.TestPreferencesFactory",
            "-Djava.util.prefs.userRoot=" + root.resolve("preferences"),
            "-Djava.util.prefs.systemRoot=" + root.resolve("system-preferences"),
            "-cp", classpath, FileOutputProbe.class.getName(), mode));
        Path output = root.resolve("probe-output.txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile())
            .redirectErrorStream(true).redirectOutput(output.toFile());
        Map<String, String> environment = builder.environment();
        for (String name : Arrays.asList("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) environment.remove(name);
        environment.put("SCARLET_HOME", root.resolve("data").toString());
        environment.put("HOME", root.resolve("user").toString());
        environment.put("LOCALAPPDATA", root.resolve("local").toString());
        environment.put("APPDATA", root.resolve("roaming").toString());
        environment.put("XDG_DATA_HOME", root.resolve("xdg").toString());
        Process child = builder.start();
        try
        {
            child.getOutputStream().close();
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Synthetic file-output probe exceeded its deadline");
            assertEquals(0, child.exitValue(), new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
        }
        finally
        {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    public static final class FileOutputProbe
    {
        public static void main(String[] args) throws Exception
        {
            if ("status-only".equals(args[0]) || "status-enabled-only".equals(args[0]))
            {
                boolean enabled = "status-enabled-only".equals(args[0]);
                if (enabled) ScarletLogger.setConsoleOnly(false);
                ScarletLogger.FileOutputStatus status = ScarletLogger.fileOutputStatus();
                if (status.enabled != enabled || status.queuedEntries != 0 || status.writtenEntries != 0L || status.outputFailures != 0L)
                    throw new AssertionError("Uninitialized file logging returned populated output status");
                if (Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> "Scarlet file logging".equals(thread.getName())))
                    throw new AssertionError("Status started the file writer before the first log");
                if (Files.exists(Paths.get(System.getProperty("SCARLET_HOME"))))
                    throw new AssertionError("Status initialized the data folder");
                return;
            }
            Path log = ScarletLogger.FileOutput.logFile.toPath();
            Files.createDirectories(log.getParent());
            byte[] original = "preserved synthetic bytes\n".getBytes(StandardCharsets.UTF_8);
            Files.write(log, original);
            ScarletLogger.setConsoleOnly(false);
            if ("write-failure".equals(args[0]))
            {
                OutputStream append = Files.newOutputStream(log, StandardOpenOption.APPEND);
                ScarletLogger.FileOutput.stream = new PrintStream(new OutputStream() {
                    boolean failed;
                    @Override public void write(int value) throws IOException
                    {
                        if (!this.failed) { this.failed = true; throw new IOException("synthetic write failure"); }
                        append.write(value);
                    }
                    @Override public void flush() throws IOException { append.flush(); }
                    @Override public void close() throws IOException { append.close(); }
                }, false, "UTF-8");
                ScarletLogger.FileOutput.sink.offer("failed record\n", true, false);
                if (!ScarletLogger.FileOutput.sink.awaitIdle(3000)) throw new AssertionError("Failed write did not finish");
                ScarletLogger.FileOutputStatus status = ScarletLogger.fileOutputStatus();
                if (!status.enabled || status.outputFailures != 1L || status.backlogDroppedEntries != 0L || status.writtenEntries != 0L)
                    throw new AssertionError("The failed file output was not observable independently of logging");
            }
            ScarletLogger.FileOutput.sink.offer("resumed real record\n", true, false);
            if (!ScarletLogger.FileOutput.sink.awaitIdle(3000)) throw new AssertionError("Recovery write did not finish");
            ScarletLogger.FileOutput.sink.offer("following real record\n", false, false);
            if (!ScarletLogger.FileOutput.sink.close(3000)) throw new AssertionError("Synthetic file writer did not stop");
            ScarletLogger.FileOutputStatus status = ScarletLogger.fileOutputStatus();
            if (status.writtenEntries != 2L || status.queuedEntries != 0 || status.lastSuccessNanos == 0L)
                throw new AssertionError("Successful real output was not reflected in its status");
            byte[] actual = Files.readAllBytes(log);
            if (actual.length < original.length || !Arrays.equals(original, Arrays.copyOf(actual, original.length)))
                throw new AssertionError("Opening or recovering the logger erased existing log bytes");
            String text = new String(actual, StandardCharsets.UTF_8);
            if (!text.contains("resumed real record\n") || !text.contains("following real record\n"))
                throw new AssertionError("Real records did not resume after the output was available");
        }
    }
}

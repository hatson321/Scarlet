package net.sybyline.scarlet.util.tts;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.sybyline.scarlet.util.BoundedProcess;

class LinuxTtsProcessLifecycleTest
{
    @TempDir Path temporary;

    @Test void discoveryRequiresARegularExecutableFileWithoutLaunchingIt() throws Exception
    {
        Path engine = Files.createFile(temporary.resolve("synthetic-engine"));
        String path = temporary.toString();
        assertEquals(Files.isExecutable(engine), LinuxCommandTtsProvider.isCommandAvailable("synthetic-engine", path));
        Files.createDirectory(temporary.resolve("synthetic-directory"));
        assertFalse(LinuxCommandTtsProvider.isCommandAvailable("synthetic-directory", path));
        assertFalse(LinuxCommandTtsProvider.isCommandAvailable("missing-engine", path));
        if (Files.getFileStore(engine).supportsFileAttributeView("posix"))
        {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> original = Files.getPosixFilePermissions(engine);
            try
            {
                Files.setPosixFilePermissions(engine, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                assertFalse(LinuxCommandTtsProvider.isCommandAvailable("synthetic-engine", path));
                Files.setPosixFilePermissions(engine, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                assertTrue(LinuxCommandTtsProvider.isCommandAvailable("synthetic-engine", path));
            }
            finally { Files.setPosixFilePermissions(engine, original); }
        }
    }

    @Test void discoveryRejectsCommandSyntaxAndSkipsMalformedPathEntries() throws Exception
    {
        Path engine = Files.createFile(temporary.resolve("synthetic-engine"));
        String path = "invalid" + (char)0 + "entry" + java.io.File.pathSeparator + temporary;
        assertEquals(Files.isExecutable(engine), LinuxCommandTtsProvider.isCommandAvailable("synthetic-engine", path));
        assertFalse(LinuxCommandTtsProvider.isCommandAvailable("../synthetic-engine", path));
        assertFalse(LinuxCommandTtsProvider.isCommandAvailable("synthetic-engine; command", path));
        assertFalse(LinuxCommandTtsProvider.isCommandAvailable(null, path));
        assertFalse(LinuxCommandTtsProvider.isCommandAvailable("synthetic-engine", null));
    }

    private static class Child extends Process
    {
        final InputStream output;
        boolean alive = true;
        Child(byte[] output) { this.output = new ByteArrayInputStream(output); }
        public InputStream getInputStream() { return output; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public int waitFor() { alive = false; return 0; }
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
        {
            if (alive) Thread.sleep(Math.min(10L, unit.toMillis(timeout)));
            return !alive;
        }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public boolean isAlive() { return alive; }
        public void destroy() { alive = false; }
        public ProcessHandle toHandle() { throw new UnsupportedOperationException("Synthetic child only"); }
    }

    @Test void completedSynthesisKeepsItsDiagnosticLog() throws Exception
    {
        Child child = new Child("synthetic output\n".getBytes(StandardCharsets.UTF_8));
        child.alive = false;
        Path log = temporary.resolve("completed.log");
        assertEquals(0, LinuxCommandTtsProvider.waitFor(child, log.toFile(), 1000));
        assertEquals("synthetic output\n", new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    }

    @Test void stalledSynthesisStopsWithinTheDeadline() throws Exception
    {
        Child child = new Child(new byte[0]);
        assertEquals(BoundedProcess.TIMED_OUT,
            LinuxCommandTtsProvider.waitFor(child, temporary.resolve("stalled.log").toFile(), 50));
        assertFalse(child.alive);
    }

    @Test void diagnosticOutputCannotGrowWithoutBound() throws Exception
    {
        byte[] output = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(output, (byte)'x');
        Child child = new Child(output);
        Path log = temporary.resolve("large.log");
        assertEquals(BoundedProcess.OUTPUT_LIMIT, LinuxCommandTtsProvider.waitFor(child, log.toFile(), 1000));
        assertFalse(child.alive);
        assertEquals(1024 * 1024, Files.size(log));
    }

    @Test void interruptedSynthesisStopsBeforePropagatingInterruption() throws Exception
    {
        Child child = new Child(new byte[0]);
        Thread.currentThread().interrupt();
        try
        {
            assertThrows(InterruptedException.class,
                () -> LinuxCommandTtsProvider.waitFor(child, temporary.resolve("interrupted.log").toFile(), 1000));
            assertFalse(child.alive);
        }
        finally { Thread.interrupted(); }
    }

    @Test void failedTerminationCannotMasqueradeAsSuccessfulSynthesis() throws Exception
    {
        Child child = new Child(new byte[0]) {
            public boolean waitFor(long timeout, TimeUnit unit) { return true; }
            public int exitValue() { return 0; }
            public void destroy() { }
            public Process destroyForcibly() { return this; }
        };
        assertEquals(-1, LinuxCommandTtsProvider.waitFor(child, temporary.resolve("unstopped.log").toFile(), 1000));
        assertTrue(child.alive);
    }
}

package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DirectoryOpenerTest
{
    static class FakeProcess extends Process
    {
        boolean exited = true, interrupted; int code = 3, exitReads;
        public java.io.OutputStream getOutputStream() { throw new AssertionError(); }
        public java.io.InputStream getInputStream() { throw new AssertionError(); }
        public java.io.InputStream getErrorStream() { throw new AssertionError(); }
        public int waitFor() { throw new AssertionError("Must use bounded wait"); }
        public boolean waitFor(long time, TimeUnit unit) throws InterruptedException
        {
            assertEquals(750, unit.toMillis(time));
            if (interrupted) throw new InterruptedException("Synthetic cancellation");
            return exited;
        }
        public int exitValue() { exitReads++; return code; }
        public void destroy() { throw new AssertionError("Do not close a resident file manager"); }
    }

    static class FakeOpener implements MiscUtils.AWTDesktop.DirectoryOpener
    {
        final List<String> calls = new ArrayList<>();
        final List<List<String>> commands = new ArrayList<>();
        final Map<String, FakeProcess> processes = new HashMap<>();
        boolean desktopResult, desktopFailure, browseResult;
        URI browsed;
        File opened;
        String deniedCommand;

        FakeProcess command(String name, int exitCode)
        {
            FakeProcess process = new FakeProcess();
            process.code = exitCode;
            processes.put(name, process);
            return process;
        }
        @Override public boolean desktopOpen(File directory) throws IOException
        {
            calls.add("desktop");
            opened = directory;
            if (desktopFailure) throw new IOException("Synthetic desktop failure");
            return desktopResult;
        }
        @Override public Process start(String... command) throws IOException
        {
            calls.add(command[0]);
            commands.add(Arrays.asList(command.clone()));
            if (command[0].equals(deniedCommand)) throw new SecurityException("Synthetic denied command");
            FakeProcess process = processes.get(command[0]);
            if (process == null) throw new IOException("Synthetic missing command");
            return process;
        }
        @Override public boolean browse(URI uri)
        {
            calls.add("browse");
            browsed = uri;
            return browseResult;
        }
    }

    @Test void quickFailureAllowsFallbackAndResidentManagerDoesNotBlock() throws Exception
    {
        FakeProcess process = new FakeProcess();
        assertFalse(MiscUtils.AWTDesktop.openerAccepted(process));
        process.code = 0;
        assertTrue(MiscUtils.AWTDesktop.openerAccepted(process));
        process.exited = false;
        assertTrue(MiscUtils.AWTDesktop.openerAccepted(process));
        assertEquals(2, process.exitReads);
    }

    @Test void linuxUsesDesktopSessionLauncherBeforeAwtAndKeepsPathOneLiteralArgument()
    {
        File directory = new File("-folder with spaces; & 'quotes' # % and unicode-☃");
        FakeOpener opener = new FakeOpener();
        opener.command("xdg-open", 0);
        opener.desktopResult = true;
        assertTrue(MiscUtils.AWTDesktop.browseDirectory(directory, Platform.$NIX, opener));
        assertEquals(List.of("xdg-open"), opener.calls);
        assertEquals(List.of(List.of("xdg-open", directory.getAbsolutePath())), opener.commands);
        assertNull(opener.opened);
        assertNull(opener.browsed);
    }

    @Test void quicklyFailedSessionLauncherFallsBackToAwt()
    {
        File directory = new File("test folder");
        FakeOpener opener = new FakeOpener();
        opener.command("xdg-open", 3);
        opener.desktopResult = true;
        assertTrue(MiscUtils.AWTDesktop.browseDirectory(directory, Platform.$NIX, opener));
        assertEquals(List.of("xdg-open", "desktop"), opener.calls);
        assertSame(directory, opener.opened);
    }

    @Test void missingOrDeniedSessionLauncherStillAllowsAwt()
    {
        for (String denied : new String[]{null, "xdg-open"})
        {
            FakeOpener opener = new FakeOpener();
            opener.deniedCommand = denied;
            opener.desktopResult = true;
            assertTrue(MiscUtils.AWTDesktop.browseDirectory(new File("folder"), Platform.$NIX, opener));
            assertEquals(List.of("xdg-open", "desktop"), opener.calls);
        }
    }

    @Test void unavailableDesktopAndFailedManagerReachNextManagerWithoutRetryingXdg()
    {
        FakeOpener opener = new FakeOpener();
        opener.desktopFailure = true;
        opener.command("nautilus", 2);
        opener.command("dolphin", 4).exited = false;
        assertTrue(MiscUtils.AWTDesktop.browseDirectory(new File("folder"), Platform.$NIX, opener));
        assertEquals(List.of("xdg-open", "desktop", "nautilus", "dolphin"), opener.calls);
        assertEquals(0, opener.processes.get("dolphin").exitReads);
    }

    @Test void allUnavailableManagersReachEscapedFileUriAndPropagateBrowseResult()
    {
        File directory = new File("folder with spaces # %");
        for (boolean accepted : new boolean[]{false, true})
        {
            FakeOpener opener = new FakeOpener();
            opener.browseResult = accepted;
            assertEquals(accepted, MiscUtils.AWTDesktop.browseDirectory(directory, Platform.$NIX, opener));
            assertEquals(List.of("xdg-open", "desktop", "nautilus", "dolphin", "thunar", "nemo", "pcmanfm", "caja", "browse"), opener.calls);
            assertEquals(directory.toURI(), opener.browsed);
            assertEquals("file", opener.browsed.getScheme());
            assertNull(opener.browsed.getFragment());
            assertTrue(opener.browsed.toASCIIString().contains("%23"));
        }
    }

    @Test void otherPlatformsStillPreferAwt()
    {
        for (Platform platform : new Platform[]{Platform.NT, Platform.XNU, Platform.SUN, Platform.OTHER})
        {
            FakeOpener opener = new FakeOpener();
            opener.desktopResult = true;
            assertTrue(MiscUtils.AWTDesktop.browseDirectory(new File("folder"), platform, opener));
            assertEquals(List.of("desktop"), opener.calls);
        }
    }

    @Test void windowsRetainsExplorerFallbackAndMacRetainsBrowserFallback()
    {
        FakeOpener windows = new FakeOpener();
        windows.command("explorer.exe", 1);
        assertTrue(MiscUtils.AWTDesktop.browseDirectory(new File("folder"), Platform.NT, windows));
        assertEquals(List.of("desktop", "explorer.exe"), windows.calls);
        assertEquals(0, windows.processes.get("explorer.exe").exitReads);
        FakeOpener mac = new FakeOpener();
        mac.browseResult = true;
        assertTrue(MiscUtils.AWTDesktop.browseDirectory(new File("folder"), Platform.XNU, mac));
        assertEquals(List.of("desktop", "browse"), mac.calls);
    }

    @Test void interruptedWaitStopsDispatchAndRestoresCancellation()
    {
        FakeOpener opener = new FakeOpener();
        opener.command("xdg-open", 0).interrupted = true;
        try
        {
            assertFalse(MiscUtils.AWTDesktop.browseDirectory(new File("folder"), Platform.$NIX, opener));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("xdg-open"), opener.calls);
        }
        finally { Thread.interrupted(); }
    }

    @Test void alreadyCancelledRequestDoesNotLaunchAnything()
    {
        FakeOpener opener = new FakeOpener();
        Thread.currentThread().interrupt();
        try
        {
            assertFalse(MiscUtils.AWTDesktop.browseDirectory(new File("folder"), Platform.$NIX, opener));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(opener.calls.isEmpty());
        }
        finally { Thread.interrupted(); }
    }
}

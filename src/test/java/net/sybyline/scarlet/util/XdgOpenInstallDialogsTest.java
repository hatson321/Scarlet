package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.sybyline.scarlet.util.tts.LinuxPackageManagerDetector.PackageManager;

class XdgOpenInstallDialogsTest
{
    static class Runner implements XdgOpenInstallDialogs.Installer
    {
        int exit, fallbackExit, starts, fallbacks; boolean privileged; String command;
        public int run(boolean elevated, String text) { starts++; privileged = elevated; command = text; return exit; }
        public int terminal(String text) { fallbacks++; assertEquals(command, text); return fallbackExit; }
    }
    PackageManager manager(boolean privileged)
    { return new PackageManager("synthetic", "Synthetic", "install {pkg}", "search", "other", privileged, true, "test"); }
    @Test void successRequiresExecutableAvailability()
    {
        Runner runner = new Runner();
        assertFalse(XdgOpenInstallDialogs.performInstallation(manager(true), runner, () -> false));
        assertTrue(XdgOpenInstallDialogs.performInstallation(manager(true), runner, () -> true));
        assertTrue(runner.privileged); assertEquals("sudo install xdg-utils", runner.command);
    }
    @Test void unavailablePrivilegeToolFallsBackToBoundedTerminal()
    {
        for (int exit : new int[] {-1, 127})
        {
            Runner runner = new Runner(); runner.exit = exit;
            assertTrue(XdgOpenInstallDialogs.performInstallation(manager(true), runner, () -> true));
            assertEquals(1, runner.fallbacks);
        }
    }
    @Test void cancellationTimeoutAndFailureNeverRetryInstallation()
    {
        for (int exit : new int[] {126, BoundedProcess.CANCELLED, BoundedProcess.TIMED_OUT, 1})
        {
            Runner runner = new Runner(); runner.exit = exit;
            assertFalse(XdgOpenInstallDialogs.performInstallation(manager(true), runner, () -> true));
            assertEquals(0, runner.fallbacks);
        }
    }
    @Test void userPackageManagerDoesNotElevateOrRetryCommandNotFound()
    {
        Runner runner = new Runner(); runner.exit = 127;
        assertFalse(XdgOpenInstallDialogs.performInstallation(manager(false), runner, () -> true));
        assertFalse(runner.privileged); assertEquals("install xdg-utils", runner.command); assertEquals(0, runner.fallbacks);
    }
    @Test void noManagerNeverStartsAnInstaller()
    {
        Runner runner = new Runner(); assertFalse(XdgOpenInstallDialogs.performInstallation(null, runner, () -> true)); assertEquals(0, runner.starts);
    }
}

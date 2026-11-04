package net.sybyline.scarlet.util.tts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class LinuxTtsInstallPolicyTest
{
    private static LinuxPackageManagerDetector.PackageManager manager(String name)
    {
        return new LinuxPackageManagerDetector.PackageManager(name, name, name + " install {pkg}",
            name + " search {pkg}", "espeak-ng", true, true, "full match");
    }

    @Test void atomicHostDoesNotUseDnfYumOrUnrelatedPackageManagers()
    {
        List<LinuxPackageManagerDetector.PackageManager> selected = LinuxPackageManagerDetector.selectTtsPackageManagers(
            Arrays.asList(manager("dnf"), manager("yum"), manager("apt"), manager("flatpak")),
            true, "/home/linuxbrew/.linuxbrew/bin/brew");
        assertEquals(1, selected.size());
        assertEquals("brew", selected.get(0).name);
        assertFalse(selected.get(0).requiresSudo);
        assertEquals("'/home/linuxbrew/.linuxbrew/bin/brew' install espeak-ng", selected.get(0).getInstallCommand());
    }

    @Test void missingHomebrewOnAtomicHostFailsClosedInsteadOfRunningDnf()
    {
        assertTrue(LinuxPackageManagerDetector.selectTtsPackageManagers(
            Arrays.asList(manager("dnf"), manager("yum")), true, null).isEmpty());
    }

    @Test void mutableFedoraRetainsNativeManagerPriority()
    {
        List<LinuxPackageManagerDetector.PackageManager> selected = LinuxPackageManagerDetector.selectTtsPackageManagers(
            Arrays.asList(manager("dnf"), manager("yum")), false, "/home/linuxbrew/.linuxbrew/bin/brew");
        assertEquals(Arrays.asList("dnf", "yum", "brew"),
            selected.stream().map(pm -> pm.name).collect(Collectors.toList()));
        assertTrue(selected.get(0).requiresSudo);
    }

    @Test void mutableDebianRetainsNativeManagerPriority()
    {
        assertEquals("apt", LinuxPackageManagerDetector.selectTtsPackageManagers(
            Arrays.asList(manager("apt"), manager("apt-get")), false, null).get(0).name);
    }

    @Test void homebrewIsResolvedOnceAndDoesNotCreateDuplicateMenuItems()
    {
        List<LinuxPackageManagerDetector.PackageManager> selected = LinuxPackageManagerDetector.selectTtsPackageManagers(
            Arrays.asList(manager("brew"), manager("brew")), false, "/custom brew/bin/brew");
        assertEquals(1, selected.size());
        assertEquals("'/custom brew/bin/brew' install espeak-ng", selected.get(0).getInstallCommand());
        assertFalse(selected.get(0).requiresSudo);
    }

    @Test void brewOnlyOffersEnginesThatTheCurrentProviderRegisters()
    {
        LinuxPackageManagerDetector.PackageManager brew = LinuxPackageManagerDetector.selectTtsPackageManagers(
            Collections.emptyList(), true, "/usr/local/bin/brew").get(0);
        List<LinuxPackageManagerDetector.TtsPackageOption> options = LinuxPackageManagerDetector.getTtsPackageOptions(brew);
        assertEquals(Arrays.asList("espeak-ng", "flite"),
            options.stream().map(option -> option.commandName).collect(Collectors.toList()));
        assertEquals("'/usr/local/bin/brew' install espeak-ng flite",
            LinuxPackageManagerDetector.buildTtsInstallCommand(brew, options));
    }

    @Test void shellCommandQuotesBrewPathAsOneArgument()
    {
        assertEquals("'/some user'\"'\"'s directory/$literal/bin/brew'",
            LinuxPackageManagerDetector.quoteShellArgument("/some user's directory/$literal/bin/brew"));
    }

    @Test void ostreeMarkerDoesNotMisclassifyAContainerAsTheAtomicHost()
    {
        assertTrue(LinuxPackageManagerDetector.isAtomicHost(true, false));
        assertFalse(LinuxPackageManagerDetector.isAtomicHost(true, true));
        assertFalse(LinuxPackageManagerDetector.isAtomicHost(false, false));
        assertFalse(LinuxPackageManagerDetector.isAtomicHost(false, true));
    }

    @Test void guidanceDisclosesManualSetupAndRebootBoundary()
    {
        String atomic = LinuxPackageManagerDetector.getTtsInstallGuidance(true);
        assertTrue(atomic.contains("Homebrew"));
        assertTrue(atomic.contains("reboot"));
        assertTrue(atomic.contains("will not perform that operation automatically"));
        assertFalse(atomic.contains("sudo apt"));
        assertTrue(LinuxPackageManagerDetector.getTtsInstallGuidance(false).contains("will not guess an APT command"));
    }

    @Test void successfulInstallRequiresAnAvailableEngine()
    {
        assertTrue(TtsPackageInstallDialogs.installationSucceeded(0, () -> true));
        assertFalse(TtsPackageInstallDialogs.installationSucceeded(0, () -> false));
    }

    @Test void failedOrCancelledInstallNeverPollsOrReportsSuccess()
    {
        AtomicBoolean verified = new AtomicBoolean();
        for (int code : new int[]{-1, 1, 126, 127, 130})
            assertFalse(TtsPackageInstallDialogs.installationSucceeded(code, () -> {
                verified.set(true);
                return true;
            }));
        assertFalse(verified.get());
    }

    @Test void commandPreviewEscapesHtmlRatherThanInterpretingPathCharacters()
    {
        assertEquals("&#39;/brew&lt;test&gt;&amp;name&#39; &quot;arg&quot;",
            TtsPackageInstallDialogs.html("'/brew<test>&name' \"arg\""));
    }
}

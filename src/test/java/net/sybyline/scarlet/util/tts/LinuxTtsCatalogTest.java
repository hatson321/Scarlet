package net.sybyline.scarlet.util.tts;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LinuxTtsCatalogTest
{
    @Test void everyOfferedPackageHasAnImplementedCommandEngine()
    {
        Set<String> engines = Set.of("flite", "pico2wave", "espeak-ng", "espeak");
        for (String manager : List.of("apt", "apt-get", "dnf", "yum", "pacman", "yay", "paru", "pamac", "zypper", "apk", "xbps-install", "emerge", "eopkg", "swupd", "nix-env", "nix-shell", "nix", "snap", "brew", "flatpak"))
        {
            LinuxPackageManagerDetector.PackageManager pm = new LinuxPackageManagerDetector.PackageManager(manager, manager, manager + " {pkg}", "", "", false, true, "synthetic");
            List<LinuxPackageManagerDetector.TtsPackageOption> options = LinuxPackageManagerDetector.getTtsPackageOptions(pm);
            for (LinuxPackageManagerDetector.TtsPackageOption option : options) assertTrue(engines.contains(option.commandName), manager + ": " + option.commandName);
            if (manager.equals("snap") || manager.equals("flatpak")) assertTrue(options.isEmpty());
            else assertFalse(options.isEmpty(), manager);
        }
        assertFalse(LinuxPackageManagerDetector.getEspeakAlternatives().containsKey("snap"));
    }
}

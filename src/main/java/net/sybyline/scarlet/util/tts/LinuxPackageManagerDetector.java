package net.sybyline.scarlet.util.tts;

import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive Linux Package Manager Detection and Management.
 * 
 * This class detects available package managers on Linux systems and provides
 * appropriate installation commands for Scarlet's Linux TTS engine packages.
 * 
 * Supported package managers:
 * - apt (Debian/Ubuntu)
 * - apt-get (Debian/Ubuntu legacy)
 * - pacman (Arch Linux)
 * - dnf (Fedora)
 * - yum (RHEL/CentOS)
 * - zypper (openSUSE)
 * - apk (Alpine Linux)
 * - xbps-install (Void Linux)
 * - emerge (Gentoo)
 * - eopkg (Solus)
 * - swupd (Clear Linux)
 * - nix-env / nix-shell (NixOS)
 * - flatpak (Universal)
 * - snap (Universal)
 * - brew (Homebrew Linux)
 * - yay (AUR helper)
 * - paru (AUR helper)
 * - pamac (Manjaro/Arch)
 */
public class LinuxPackageManagerDetector {

    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/TTS/PackageManager");

    /**
     * Represents a detected package manager with its capabilities.
     */
    public static class PackageManager {
        public final String name;
        public final String displayName;
        public final String installCommand;
        public final String searchCommand;
        public final String packageName;
        public final boolean requiresSudo;
        public final boolean isAvailable;
        public final String detectionMethod;

        public PackageManager(String name, String displayName, String installCommand, 
                             String searchCommand, String packageName, boolean requiresSudo, 
                             boolean isAvailable, String detectionMethod) {
            this.name = name;
            this.displayName = displayName;
            this.installCommand = installCommand;
            this.searchCommand = searchCommand;
            this.packageName = packageName;
            this.requiresSudo = requiresSudo;
            this.isAvailable = isAvailable;
            this.detectionMethod = detectionMethod;
        }

        public String getFullInstallCommand() {
            if (requiresSudo) {
                return "sudo " + installCommand;
            }
            return installCommand;
        }

        public String getInstallCommand() {
            return getFullInstallCommand().replace("{pkg}", packageName);
        }

        @Override
        public String toString() {
            return String.format("PackageManager[%s, available=%s, cmd=%s]", 
                name, isAvailable, getInstallCommand());
        }
    }

    /**
     * A command-line TTS engine package Scarlet knows how to use directly.
     */
    public static class TtsPackageOption {
        public final String id;
        public final String displayName;
        public final String commandName;
        public final String packageName;
        public final String description;

        public TtsPackageOption(String id, String displayName, String commandName,
                                String packageName, String description) {
            this.id = id;
            this.displayName = displayName;
            this.commandName = commandName;
            this.packageName = packageName;
            this.description = description;
        }

        public boolean isInstalled() {
            return LinuxCommandTtsProvider.isCommandAvailable(this.commandName);
        }

        public String getInstallCommand(PackageManager pm) {
            return pm.getFullInstallCommand().replace("{pkg}", this.packageName);
        }

        @Override
        public String toString() {
            return String.format("%s (%s)", this.displayName, this.packageName);
        }
    }

    // Cache for detected package managers
    private static List<PackageManager> detectedManagers = null;
    private static PackageManager primaryManager = null;

    /**
     * Definition of known package managers and how to use them.
     */
    private static final Map<String, String[]> PACKAGE_MANAGER_DEFINITIONS = new LinkedHashMap<>();
    
    static {
        // Format: name -> {install_cmd_format, search_cmd_format, default_tts_package_name, requires_sudo}
        // The install command format uses {pkg} as placeholder for package names.
        
        // Debian/Ubuntu family
        PACKAGE_MANAGER_DEFINITIONS.put("apt", new String[]{
            "apt install -y {pkg}", "apt search {pkg}", "espeak-ng", "true"
        });
        PACKAGE_MANAGER_DEFINITIONS.put("apt-get", new String[]{
            "apt-get install -y {pkg}", "apt-cache search {pkg}", "espeak-ng", "true"
        });
        
        // Red Hat family
        PACKAGE_MANAGER_DEFINITIONS.put("dnf", new String[]{
            "dnf install -y {pkg}", "dnf search {pkg}", "espeak-ng", "true"
        });
        PACKAGE_MANAGER_DEFINITIONS.put("yum", new String[]{
            "yum install -y {pkg}", "yum search {pkg}", "espeak-ng", "true"
        });
        
        // Arch Linux family
        PACKAGE_MANAGER_DEFINITIONS.put("pacman", new String[]{
            "pacman -S --noconfirm --needed {pkg}", "pacman -Ss {pkg}", "espeak-ng", "true"
        });
        PACKAGE_MANAGER_DEFINITIONS.put("yay", new String[]{
            "yay -S --noconfirm --needed {pkg}", "yay -Ss {pkg}", "espeak-ng", "false"
        });
        PACKAGE_MANAGER_DEFINITIONS.put("paru", new String[]{
            "paru -S --noconfirm --needed {pkg}", "paru -Ss {pkg}", "espeak-ng", "false"
        });
        PACKAGE_MANAGER_DEFINITIONS.put("pamac", new String[]{
            "pamac install --no-confirm {pkg}", "pamac search {pkg}", "espeak-ng", "false"
        });
        
        // openSUSE
        PACKAGE_MANAGER_DEFINITIONS.put("zypper", new String[]{
            "zypper install -y {pkg}", "zypper search {pkg}", "espeak-ng", "true"
        });
        
        // Alpine Linux
        PACKAGE_MANAGER_DEFINITIONS.put("apk", new String[]{
            "apk add {pkg}", "apk search {pkg}", "espeak-ng", "true"
        });
        
        // Void Linux
        PACKAGE_MANAGER_DEFINITIONS.put("xbps-install", new String[]{
            "xbps-install -y {pkg}", "xbps-query -Rs {pkg}", "espeak-ng", "true"
        });
        
        // Gentoo
        PACKAGE_MANAGER_DEFINITIONS.put("emerge", new String[]{
            "emerge --ask=n {pkg}", "emerge --search {pkg}", "app-accessibility/espeak-ng", "true"
        });
        
        // Solus
        PACKAGE_MANAGER_DEFINITIONS.put("eopkg", new String[]{
            "eopkg install -y {pkg}", "eopkg search {pkg}", "espeak-ng", "true"
        });
        
        // Clear Linux
        PACKAGE_MANAGER_DEFINITIONS.put("swupd", new String[]{
            "swupd bundle-add {pkg}", "swupd search {pkg}", "espeak", "false"
        });
        
        // Nix family
        PACKAGE_MANAGER_DEFINITIONS.put("nix-env", new String[]{
            "nix-env -iA {pkg}", "nix-env -qaP {pkg}", "nixpkgs.espeak", "false"
        });
        PACKAGE_MANAGER_DEFINITIONS.put("nix-shell", new String[]{
            "nix-shell -p {pkg}", "nix-env -qaP {pkg}", "espeak", "false"
        });
        PACKAGE_MANAGER_DEFINITIONS.put("nix", new String[]{
            "nix profile install {pkg}", "nix search nixpkgs {pkg}", "nixpkgs#espeak", "false"
        });
        
        // Universal package managers
        PACKAGE_MANAGER_DEFINITIONS.put("flatpak", new String[]{
            "flatpak install -y flathub {pkg}", "flatpak search {pkg}", "", "false"
        });
        PACKAGE_MANAGER_DEFINITIONS.put("snap", new String[]{
            "snap install {pkg}", "snap find {pkg}", "mimic --beta", "true"
        });
        
        // Homebrew Linux
        PACKAGE_MANAGER_DEFINITIONS.put("brew", new String[]{
            "brew install {pkg}", "brew search {pkg}", "espeak-ng", "false"
        });
    }

    private static final Map<String, String[][]> TTS_PACKAGE_OPTIONS = new LinkedHashMap<>();
    private static final Map<String, String> DESKTOP_NOTIFICATION_PACKAGES = new LinkedHashMap<>();

    static {
        // Format: {id, display_name, command_name, package_names, description}
        String[][] debianLike = new String[][]{
            opt("espeak-ng", "eSpeak NG", "espeak-ng", "espeak-ng", "Broad language support and the default Linux fallback"),
            opt("flite", "Flite", "flite", "flite", "Lightweight CMU voices that are often clearer than eSpeak"),
            opt("festival", "Festival", "text2wave", "festival", "Classic Festival/text2wave voices"),
            opt("mimic", "Mimic", "mimic", "mimic", "Mycroft's Flite-derived voices with better English output"),
            opt("pico2wave", "Pico TTS", "pico2wave", "libttspico-utils", "SVOX Pico voices via pico2wave"),
            opt("espeak", "Legacy eSpeak", "espeak", "espeak", "Older eSpeak package for compatibility")
        };
        TTS_PACKAGE_OPTIONS.put("apt", debianLike);
        TTS_PACKAGE_OPTIONS.put("apt-get", debianLike);

        String[][] fedoraLike = new String[][]{
            opt("espeak-ng", "eSpeak NG", "espeak-ng", "espeak-ng", "Broad language support and the default Linux fallback"),
            opt("flite", "Flite", "flite", "flite", "Lightweight CMU voices that are often clearer than eSpeak"),
            opt("festival", "Festival", "text2wave", "festival", "Classic Festival/text2wave voices"),
            opt("mimic", "Mimic", "mimic", "mimic", "Mycroft's Flite-derived voices with better English output")
        };
        TTS_PACKAGE_OPTIONS.put("dnf", fedoraLike);
        TTS_PACKAGE_OPTIONS.put("yum", fedoraLike);

        String[][] archOfficial = new String[][]{
            opt("espeak-ng", "eSpeak NG", "espeak-ng", "espeak-ng", "Broad language support and the default Linux fallback"),
            opt("flite", "Flite", "flite", "flite", "Lightweight CMU voices that are often clearer than eSpeak"),
            opt("festival", "Festival", "text2wave", "festival festival-english", "Classic Festival/text2wave voices")
        };
        String[][] archAur = new String[][]{
            opt("espeak-ng", "eSpeak NG", "espeak-ng", "espeak-ng", "Broad language support and the default Linux fallback"),
            opt("flite", "Flite", "flite", "flite", "Lightweight CMU voices that are often clearer than eSpeak"),
            opt("festival", "Festival", "text2wave", "festival festival-english", "Classic Festival/text2wave voices"),
            opt("mimic", "Mimic", "mimic", "mimic", "Mycroft's Flite-derived voices with better English output")
        };
        TTS_PACKAGE_OPTIONS.put("pacman", archOfficial);
        TTS_PACKAGE_OPTIONS.put("yay", archAur);
        TTS_PACKAGE_OPTIONS.put("paru", archAur);
        TTS_PACKAGE_OPTIONS.put("pamac", archAur);

        String[][] suseLike = new String[][]{
            opt("espeak-ng", "eSpeak NG", "espeak-ng", "espeak-ng", "Broad language support and the default Linux fallback"),
            opt("flite", "Flite", "flite", "flite", "Lightweight CMU voices that are often clearer than eSpeak"),
            opt("festival", "Festival", "text2wave", "festival", "Classic Festival/text2wave voices")
        };
        TTS_PACKAGE_OPTIONS.put("zypper", suseLike);

        String[][] alpineLike = new String[][]{
            opt("espeak-ng", "eSpeak NG", "espeak-ng", "espeak-ng", "Broad language support and the default Linux fallback"),
            opt("flite", "Flite", "flite", "flite", "Lightweight CMU voices that are often clearer than eSpeak"),
            opt("festival", "Festival", "text2wave", "festival", "Classic Festival/text2wave voices"),
            opt("mimic", "Mimic", "mimic", "mimic", "Mycroft's Flite-derived voices with better English output"),
            opt("pico2wave", "Pico TTS", "pico2wave", "svox-pico", "SVOX Pico voices via pico2wave")
        };
        TTS_PACKAGE_OPTIONS.put("apk", alpineLike);

        String[][] voidLike = new String[][]{
            opt("espeak-ng", "eSpeak NG", "espeak-ng", "espeak-ng", "Broad language support and the default Linux fallback"),
            opt("flite", "Flite", "flite", "flite", "Lightweight CMU voices that are often clearer than eSpeak"),
            opt("mimic", "Mimic", "mimic", "mimic", "Mycroft's Flite-derived voices with better English output")
        };
        TTS_PACKAGE_OPTIONS.put("xbps-install", voidLike);

        TTS_PACKAGE_OPTIONS.put("emerge", new String[][]{
            opt("espeak-ng", "eSpeak NG", "espeak-ng", "app-accessibility/espeak-ng", "Broad language support and the default Linux fallback"),
            opt("flite", "Flite", "flite", "app-accessibility/flite", "Lightweight CMU voices that are often clearer than eSpeak")
        });

        TTS_PACKAGE_OPTIONS.put("eopkg", new String[][]{
            opt("espeak-ng", "eSpeak NG", "espeak-ng", "espeak-ng", "Broad language support and the default Linux fallback")
        });
        TTS_PACKAGE_OPTIONS.put("swupd", new String[][]{
            opt("espeak", "eSpeak", "espeak", "espeak", "Clear Linux bundle fallback")
        });
        TTS_PACKAGE_OPTIONS.put("nix-env", new String[][]{
            opt("espeak", "eSpeak NG", "espeak-ng", "nixpkgs.espeak", "Nixpkgs eSpeak NG alias")
        });
        TTS_PACKAGE_OPTIONS.put("nix-shell", new String[][]{
            opt("espeak", "eSpeak NG", "espeak-ng", "espeak", "Nixpkgs eSpeak NG alias")
        });
        TTS_PACKAGE_OPTIONS.put("nix", new String[][]{
            opt("espeak", "eSpeak NG", "espeak-ng", "nixpkgs#espeak", "Nixpkgs eSpeak NG alias")
        });
        TTS_PACKAGE_OPTIONS.put("snap", new String[][]{
            opt("mimic", "Mimic", "mimic", "mimic --beta", "Mycroft's Flite-derived voices with better English output")
        });
        TTS_PACKAGE_OPTIONS.put("brew", new String[][]{
            opt("espeak-ng", "eSpeak NG", "espeak-ng", "espeak-ng", "Broad language support and the default Linux fallback"),
            opt("flite", "Flite", "flite", "flite", "Lightweight CMU voices supported by Scarlet")
        });
        TTS_PACKAGE_OPTIONS.put("flatpak", new String[0][0]);

        DESKTOP_NOTIFICATION_PACKAGES.put("apt", "libnotify-bin");
        DESKTOP_NOTIFICATION_PACKAGES.put("apt-get", "libnotify-bin");
        DESKTOP_NOTIFICATION_PACKAGES.put("dnf", "libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("yum", "libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("pacman", "libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("yay", "libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("paru", "libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("pamac", "libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("zypper", "libnotify-tools");
        DESKTOP_NOTIFICATION_PACKAGES.put("apk", "libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("xbps-install", "libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("emerge", "x11-libs/libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("eopkg", "libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("nix-env", "nixpkgs.libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("nix", "nixpkgs#libnotify");
        DESKTOP_NOTIFICATION_PACKAGES.put("brew", "libnotify");
    }

    private static String[] opt(String id, String displayName, String commandName, String packageName, String description) {
        return new String[] { id, displayName, commandName, packageName, description };
    }

    /**
     * Detection commands for each package manager.
     * These commands return success (exit 0) if the package manager is available.
     */
    private static final Map<String, String[]> DETECTION_COMMANDS = new LinkedHashMap<>();
    
    static {
        // Format: name -> {binary_check_command, distro_check_command}
        // binary_check: Check if the binary exists
        // distro_check: Additional check specific to distro (optional)
        
        DETECTION_COMMANDS.put("apt", new String[]{"which apt", "test -f /etc/debian_version"});
        DETECTION_COMMANDS.put("apt-get", new String[]{"which apt-get", "test -f /etc/debian_version"});
        DETECTION_COMMANDS.put("dnf", new String[]{"which dnf", "test -f /etc/fedora-release"});
        DETECTION_COMMANDS.put("yum", new String[]{"which yum", "test -f /etc/redhat-release"});
        DETECTION_COMMANDS.put("pacman", new String[]{"which pacman", "test -f /etc/arch-release"});
        DETECTION_COMMANDS.put("yay", new String[]{"which yay", "test -f /etc/arch-release"});
        DETECTION_COMMANDS.put("paru", new String[]{"which paru", "test -f /etc/arch-release"});
        DETECTION_COMMANDS.put("pamac", new String[]{"which pamac", "test -f /etc/arch-release"});
        DETECTION_COMMANDS.put("zypper", new String[]{"which zypper", "grep -qi opensuse /etc/os-release 2>/dev/null"});
        DETECTION_COMMANDS.put("apk", new String[]{"which apk", "test -f /etc/alpine-release"});
        DETECTION_COMMANDS.put("xbps-install", new String[]{"which xbps-install", "test -d /var/db/xbps"});
        DETECTION_COMMANDS.put("emerge", new String[]{"which emerge", "test -d /etc/portage"});
        DETECTION_COMMANDS.put("eopkg", new String[]{"which eopkg", "test -d /var/lib/eopkg"});
        DETECTION_COMMANDS.put("swupd", new String[]{"which swupd", "test -d /usr/share/clear/bundles"});
        DETECTION_COMMANDS.put("nix-env", new String[]{"which nix-env", "test -d /nix"});
        DETECTION_COMMANDS.put("nix-shell", new String[]{"which nix-shell", "test -d /nix"});
        DETECTION_COMMANDS.put("nix", new String[]{"which nix", "test -d /nix"});
        DETECTION_COMMANDS.put("flatpak", new String[]{"which flatpak", null});
        DETECTION_COMMANDS.put("snap", new String[]{"which snap", "test -d /snap"});
        DETECTION_COMMANDS.put("brew", new String[]{"which brew", null});
    }

    /**
     * Priority order for package managers when multiple are available.
     * Lower number = higher priority.
     */
    private static final Map<String, Integer> PRIORITY_ORDER = new LinkedHashMap<>();
    
    static {
        // Native package managers get highest priority
        PRIORITY_ORDER.put("apt", 10);
        PRIORITY_ORDER.put("apt-get", 11);
        PRIORITY_ORDER.put("dnf", 20);
        PRIORITY_ORDER.put("yum", 21);
        PRIORITY_ORDER.put("pacman", 30);
        PRIORITY_ORDER.put("zypper", 40);
        PRIORITY_ORDER.put("apk", 50);
        PRIORITY_ORDER.put("xbps-install", 60);
        PRIORITY_ORDER.put("emerge", 70);
        PRIORITY_ORDER.put("eopkg", 80);
        PRIORITY_ORDER.put("swupd", 90);
        
        // AUR helpers (for Arch-based systems)
        PRIORITY_ORDER.put("yay", 31);
        PRIORITY_ORDER.put("paru", 32);
        PRIORITY_ORDER.put("pamac", 33);
        
        // Nix
        PRIORITY_ORDER.put("nix", 100);
        PRIORITY_ORDER.put("nix-env", 101);
        PRIORITY_ORDER.put("nix-shell", 102);
        
        // Universal package managers (lower priority)
        PRIORITY_ORDER.put("flatpak", 200);
        PRIORITY_ORDER.put("snap", 201);
        PRIORITY_ORDER.put("brew", 210);
    }

    /**
     * Detect all available package managers on the system.
     * Results are cached for subsequent calls.
     */
    public static List<PackageManager> detectAllPackageManagers() {
        if (detectedManagers != null) {
            return detectedManagers;
        }

        LOG.info("Detecting available package managers...");
        detectedManagers = new ArrayList<>();

        for (Map.Entry<String, String[]> entry : PACKAGE_MANAGER_DEFINITIONS.entrySet()) {
            String name = entry.getKey();
            String[] definition = entry.getValue();
            String[] detectionCmds = DETECTION_COMMANDS.get(name);

            if (detectionCmds == null) {
                LOG.debug("No detection command for package manager: {}", name);
                continue;
            }

            // Check if binary exists
            boolean binaryExists = checkCommand(detectionCmds[0]);
            if (!binaryExists) {
                LOG.debug("Package manager binary not found: {}", name);
                continue;
            }

            // Additional distro-specific check if available
            boolean distroMatch = true;
            if (detectionCmds.length > 1 && detectionCmds[1] != null) {
                distroMatch = checkCommand(detectionCmds[1]);
            }

            String detectionMethod = distroMatch ? "full match" : "binary only";

            // Create PackageManager instance
            String displayName = getDisplayName(name);
            String installCommand = definition[0];
            String searchCommand = definition[1];
            String packageName = definition[2];
            boolean requiresSudo = Boolean.parseBoolean(definition[3]);

            PackageManager pm = new PackageManager(
                name, displayName, installCommand, searchCommand,
                packageName, requiresSudo, true, detectionMethod
            );

            detectedManagers.add(pm);
            LOG.info("Detected package manager: {} ({})", name, detectionMethod);
        }

        // Sort by priority
        detectedManagers.sort((a, b) -> {
            int priorityA = PRIORITY_ORDER.getOrDefault(a.name, 999);
            int priorityB = PRIORITY_ORDER.getOrDefault(b.name, 999);
            // Prefer distro-specific matches over binary-only
            if (a.detectionMethod.equals("full match") && !b.detectionMethod.equals("full match")) {
                return -1;
            }
            if (!a.detectionMethod.equals("full match") && b.detectionMethod.equals("full match")) {
                return 1;
            }
            return Integer.compare(priorityA, priorityB);
        });

        if (detectedManagers.isEmpty()) {
            LOG.warn("No package managers detected on this system!");
        }

        return detectedManagers;
    }

    /**
     * Get the primary (best) package manager for this system.
     */
    public static PackageManager getPrimaryPackageManager() {
        if (primaryManager != null) {
            return primaryManager;
        }

        List<PackageManager> managers = detectAllPackageManagers();
        if (managers.isEmpty()) {
            LOG.warn("No package manager available");
            return null;
        }

        // Prefer the first one (highest priority, distro-matched)
        primaryManager = managers.get(0);
        LOG.info("Selected primary package manager: {}", primaryManager.name);
        return primaryManager;
    }

    /**
     * Get a specific package manager by name.
     */
    public static PackageManager getPackageManager(String name) {
        List<PackageManager> managers = detectAllPackageManagers();
        for (PackageManager pm : managers) {
            if (pm.name.equals(name)) {
                return pm;
            }
        }
        return null;
    }

    /**
     * Detect package managers that can install a usable command-line TTS engine.
     */
    public static List<PackageManager> detectAllTtsPackageManagers() {
        boolean atomic = isAtomicHost();
        // Atomic hosts can ship deliberately failing DNF/YUM compatibility wrappers.
        // Do not offer those, or silently stage an OS deployment for a speech engine.
        return selectTtsPackageManagers(atomic ? Collections.emptyList() : detectAllPackageManagers(),
            atomic, LinuxTtsExecutables.find("brew"));
    }

    public static boolean isAtomicHost() {
        return isAtomicHost(new File("/run/ostree-booted").exists(),
            new File("/run/.containerenv").exists() || new File("/.dockerenv").exists());
    }

    static boolean isAtomicHost(boolean ostreeBooted, boolean container) {
        return ostreeBooted && !container;
    }

    static List<PackageManager> selectTtsPackageManagers(List<PackageManager> managers,
        boolean atomic, String brewExecutable) {
        List<PackageManager> result = new ArrayList<>();
        PackageManager brew = brewExecutable == null ? null : new PackageManager(
            "brew", "Homebrew (Linux, current user)", quoteShellArgument(brewExecutable) + " install {pkg}",
            quoteShellArgument(brewExecutable) + " search {pkg}", "espeak-ng", false, true,
            "resolved executable");
        if (atomic) {
            if (brew != null) result.add(brew);
            return result;
        }
        boolean addedBrew = false;
        for (PackageManager pm : managers) {
            if ("brew".equals(pm.name)) {
                if (brew != null && !addedBrew) {
                    result.add(brew);
                    addedBrew = true;
                }
            } else if (!getTtsPackageOptions(pm).isEmpty()) {
                result.add(pm);
            }
        }
        if (brew != null && !addedBrew) result.add(brew);
        return result;
    }

    static String quoteShellArgument(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /** Guidance only; never passed to an installer as a shell command. */
    public static String getTtsInstallGuidance() {
        return getTtsInstallGuidance(isAtomicHost());
    }

    static String getTtsInstallGuidance(boolean atomic) {
        if (atomic) {
            return "Atomic/immutable Linux detected. TTS setup uses an existing Homebrew installation as the current user, "
                + "not the host DNF/YUM wrappers. Scarlet does not install Homebrew, layer OS packages, or reboot the device. "
                + "If Homebrew is unavailable or not writable by your account, follow your distribution's Homebrew setup "
                + "instructions, then retry. Manual Homebrew command: brew install espeak-ng. "
                + "Bazzite guidance: https://docs.bazzite.gg/Installing_and_Managing_Software/Homebrew/ . "
                + "If you independently choose rpm-ostree package layering, a reboot is normally required before "
                + "the engine is usable; Scarlet will not perform that operation automatically.";
        }
        return "Install a supported command-line speech engine, such as espeak-ng or flite, using your distribution's "
            + "documented package manager, then retry. Scarlet will not guess an APT command on an unrecognized system.";
    }

    /**
     * Get the primary package manager that can install Scarlet's default TTS engine.
     */
    public static PackageManager getPrimaryTtsPackageManager() {
        List<PackageManager> managers = detectAllTtsPackageManagers();
        if (managers.isEmpty()) {
            LOG.warn("No TTS-capable package manager available");
            return null;
        }
        return managers.get(0);
    }

    /**
     * Detect package managers that can install the host notify-send command.
     */
    public static List<PackageManager> detectAllDesktopNotificationPackageManagers() {
        List<PackageManager> managers = detectAllPackageManagers();
        List<PackageManager> result = new ArrayList<>();
        for (PackageManager pm : managers) {
            if (getDesktopNotificationPackageName(pm) != null) {
                result.add(pm);
            }
        }
        return result;
    }

    /**
     * Get the primary package manager that can install notify-send.
     */
    public static PackageManager getPrimaryDesktopNotificationPackageManager() {
        List<PackageManager> managers = detectAllDesktopNotificationPackageManagers();
        if (managers.isEmpty()) {
            LOG.warn("No notify-send-capable package manager available");
            return null;
        }
        return managers.get(0);
    }

    /**
     * Get the package name that provides notify-send for a package manager.
     */
    public static String getDesktopNotificationPackageName(PackageManager pm) {
        if (pm == null) {
            return null;
        }
        String packageName = DESKTOP_NOTIFICATION_PACKAGES.get(pm.name);
        if (packageName == null || packageName.trim().isEmpty()) {
            return null;
        }
        return packageName;
    }

    /**
     * Build an install command for the package that provides notify-send.
     */
    public static String getDesktopNotificationInstallCommand(PackageManager pm) {
        String packageName = getDesktopNotificationPackageName(pm);
        if (pm == null || packageName == null) {
            return "";
        }
        return pm.getFullInstallCommand().replace("{pkg}", packageName);
    }

    /**
     * Get the install command for Scarlet's default Linux TTS engine using the
     * primary package manager.
     */
    public static String getTtsInstallCommand() {
        PackageManager pm = getPrimaryTtsPackageManager();
        if (pm == null) {
            return ""; // Unknown host: guidance is separate from executable commands.
        }
        return pm.getInstallCommand();
    }

    /**
     * Backward-compatible wrapper for callers that still use the older eSpeak name.
     */
    public static String getEspeakInstallCommand() {
        return getTtsInstallCommand();
    }

    /**
     * Get all available install commands for Scarlet's default Linux TTS engine.
     * Useful for showing a menu to the user.
     */
    public static List<String> getAllTtsInstallCommands() {
        List<PackageManager> managers = detectAllTtsPackageManagers();
        List<String> commands = new ArrayList<>();
        
        for (PackageManager pm : managers) {
            commands.add(pm.getInstallCommand());
        }
        
        return commands;
    }

    /**
     * Backward-compatible wrapper for callers that still use the older eSpeak name.
     */
    public static List<String> getAllEspeakInstallCommands() {
        return getAllTtsInstallCommands();
    }

    /**
     * Get the TTS packages Scarlet can offer for the given package manager.
     */
    public static List<TtsPackageOption> getTtsPackageOptions(PackageManager pm) {
        List<TtsPackageOption> options = new ArrayList<>();
        if (pm == null) {
            return options;
        }
        String[][] defs = TTS_PACKAGE_OPTIONS.get(pm.name);
        if (defs == null) {
            return options;
        }
        for (String[] def : defs) {
            if (def.length >= 5 && def[3] != null && !def[3].trim().isEmpty()) {
                options.add(new TtsPackageOption(def[0], def[1], def[2], def[3], def[4]));
            }
        }
        return options;
    }

    /**
     * Build one install command for the selected optional TTS packages.
     */
    public static String buildTtsInstallCommand(PackageManager pm, List<TtsPackageOption> options) {
        if (pm == null || options == null || options.isEmpty()) {
            return "";
        }
        if ("snap".equals(pm.name)) {
            List<String> commands = new ArrayList<>();
            for (TtsPackageOption option : options) {
                commands.add(option.getInstallCommand(pm));
            }
            return join(commands, " && ");
        }
        StringBuilder packages = new StringBuilder();
        for (TtsPackageOption option : options) {
            if (option.packageName == null || option.packageName.trim().isEmpty()) {
                continue;
            }
            if (packages.length() > 0) {
                packages.append(' ');
            }
            packages.append(option.packageName.trim());
        }
        if (packages.length() == 0) {
            return "";
        }
        return pm.getFullInstallCommand().replace("{pkg}", packages.toString());
    }

    private static String join(List<String> strings, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (String string : strings) {
            if (string == null || string.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(delimiter);
            }
            sb.append(string);
        }
        return sb.toString();
    }

    /**
     * Check if a command executes successfully.
     */
    private static boolean checkCommand(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try {
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return false;
                }
                return p.exitValue() == 0;
            } finally {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            LOG.debug("Command check failed for '{}': {}", command, e.getMessage());
            return false;
        }
    }

    /**
     * Get a user-friendly display name for a package manager.
     */
    private static String getDisplayName(String name) {
        switch (name) {
            case "apt": return "APT (Debian/Ubuntu)";
            case "apt-get": return "APT-GET (Debian/Ubuntu Legacy)";
            case "dnf": return "DNF (Fedora)";
            case "yum": return "YUM (RHEL/CentOS)";
            case "pacman": return "Pacman (Arch Linux)";
            case "yay": return "Yay (AUR Helper)";
            case "paru": return "Paru (AUR Helper)";
            case "pamac": return "Pamac (Manjaro)";
            case "zypper": return "Zypper (openSUSE)";
            case "apk": return "APK (Alpine Linux)";
            case "xbps-install": return "XBPS (Void Linux)";
            case "emerge": return "Portage/Emerge (Gentoo)";
            case "eopkg": return "Eopkg (Solus)";
            case "swupd": return "Swupd (Clear Linux)";
            case "nix-env": return "Nix Env (NixOS)";
            case "nix-shell": return "Nix Shell (NixOS)";
            case "nix": return "Nix (NixOS)";
            case "flatpak": return "Flatpak (Universal)";
            case "snap": return "Snap (Universal)";
            case "brew": return "Homebrew (Linux)";
            default: return name.substring(0, 1).toUpperCase() + name.substring(1);
        }
    }

    /**
     * Check if espeak is available in the package manager's repositories.
     */
    public static boolean isEspeakAvailableInRepo(PackageManager pm) {
        return isTtsPackageAvailableInRepo(pm);
    }

    /**
     * Check if the default TTS package is available in the package
     * manager's repositories.
     */
    public static boolean isTtsPackageAvailableInRepo(PackageManager pm) {
        if (pm.searchCommand == null || pm.searchCommand.isEmpty()) {
            return true; // Assume available if we can't check
        }
        String searchPackage = firstSearchablePackage(pm.packageName);
        if (searchPackage.isEmpty()) {
            return false;
        }

        try {
            String searchCmd = pm.searchCommand.replace("{pkg}", searchPackage);
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", searchCmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().contains(searchPackage.toLowerCase())) {
                        return true;
                    }
                }
                if (!p.waitFor(15, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return true; // Assume available if the search stalled (e.g. package db locked)
                }
                return p.exitValue() == 0;
            } finally {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            LOG.debug("Repository search failed for {}: {}", pm.name, e.getMessage());
            return true; // Assume available on error
        }
    }

    private static String firstSearchablePackage(String packageNames) {
        if (packageNames == null) {
            return "";
        }
        String[] parts = packageNames.trim().split("\\s+");
        for (String part : parts) {
            if (part == null || part.isEmpty() || part.startsWith("-")) {
                continue;
            }
            if (part.startsWith("nixpkgs#")) {
                return part.substring("nixpkgs#".length());
            }
            if (part.startsWith("nixpkgs.")) {
                return part.substring("nixpkgs.".length());
            }
            int slash = part.indexOf('/');
            if (slash >= 0 && slash + 1 < part.length()) {
                return part.substring(slash + 1);
            }
            return part;
        }
        return "";
    }

    /**
     * Get recommended fallback package names for older callers.
     */
    public static Map<String, String> getEspeakAlternatives() {
        Map<String, String> alternatives = new LinkedHashMap<>();
        
        // For Arch-based systems, espeak-ng is the maintained eSpeak implementation.
        alternatives.put("yay", "espeak-ng");
        alternatives.put("paru", "espeak-ng");
        alternatives.put("pamac", "espeak-ng");
        
        // Snap currently has Mimic as the usable command-line TTS option.
        alternatives.put("snap", "mimic --beta");
        
        return alternatives;
    }

    /**
     * Clear the detection cache (useful for testing).
     */
    public static void clearCache() {
        detectedManagers = null;
        primaryManager = null;
    }

    /**
     * Print detected package managers (for debugging).
     */
    public static void printDetectedManagers() {
        List<PackageManager> managers = detectAllPackageManagers();
        LOG.info("=== Detected Package Managers ===");
        for (int i = 0; i < managers.size(); i++) {
            PackageManager pm = managers.get(i);
            LOG.info("{}. {} - {} (sudo: {})",
                i + 1, pm.displayName, pm.getInstallCommand(),
                pm.requiresSudo);
        }
        LOG.info("================================");
    }
}

package net.sybyline.scarlet.util.tts;

import java.awt.Component;
import java.awt.GraphicsEnvironment;

import javax.swing.JLabel;
import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.util.PkexecInstaller;
import net.sybyline.scarlet.util.Sys;
import net.sybyline.scarlet.ui.Swing;

/**
 * Prompts the user to install a command-line audio player when Scarlet needs to
 * play TTS locally on Linux but none of {@code pw-play} / {@code paplay} /
 * {@code aplay} is available on PATH.
 *
 * <p>Mirrors {@link net.sybyline.scarlet.util.XdgOpenInstallDialogs} and
 * {@link TtsPackageInstallDialogs}: detect, ask for consent, then install via the
 * detected package manager ({@link LinuxPackageManagerDetector}). The target
 * package is {@code alsa-utils}, which provides {@code aplay} and exists under
 * that name on essentially every distro. {@code pw-play} (PipeWire) and
 * {@code paplay} (PulseAudio) already ship with their respective sound servers,
 * so this prompt only appears on minimal setups that have neither.
 */
public class LinuxAudioPlayerInstallDialogs
{

    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/TTS/AudioPlayer");

    public static final String PLAYER_PACKAGE      = "alsa-utils";
    public static final String PLAYER_DISPLAY_NAME = "ALSA utilities (aplay)";
    public static final String PLAYER_DESCRIPTION  =
        "Scarlet plays text-to-speech through a small command-line player so the\n" +
        "audio is routed by PipeWire or PulseAudio and shows up in your volume\n" +
        "mixer. None of pw-play, paplay, or aplay was found on this system.\n\n" +
        "alsa-utils provides the 'aplay' player and is available on every distro.";

    /** The players Scarlet knows how to drive, in preference order. */
    private static final String[] KNOWN_PLAYERS = { "pw-play", "paplay", "aplay" };

    /** Result of the install dialog flow. */
    public enum InstallDialogResult
    {
        ALREADY_INSTALLED,
        INSTALL_APPROVED_SUCCESS,
        INSTALL_APPROVED_FAILED,
        INSTALL_DECLINED,
        HEADLESS_MODE
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** True if any supported CLI audio player is already on PATH. */
    public static boolean isAudioPlayerInstalled()
    {
        for (String player : KNOWN_PLAYERS)
            if (isCommandAvailable(player))
                return true;
        return false;
    }

    /**
     * Runs the full check-and-prompt flow. Safe to call from a background thread;
     * all Swing work is marshalled onto the EDT via {@link Swing}.
     *
     * @param parent optional parent component for dialogs (may be null)
     * @return the outcome of the flow
     */
    public static InstallDialogResult showInstallFlowIfNeeded(Component parent)
    {
        if (isAudioPlayerInstalled())
            return InstallDialogResult.ALREADY_INSTALLED;

        LOG.warn("No CLI audio player (pw-play/paplay/aplay) found on this system");

        if (GraphicsEnvironment.isHeadless())
            return handleHeadlessMode();

        if (!showConsentDialog(parent))
        {
            showDeclineDialog(parent);
            return InstallDialogResult.INSTALL_DECLINED;
        }

        if (performInstallation(parent))
        {
            showSuccessDialog(parent);
            return InstallDialogResult.INSTALL_APPROVED_SUCCESS;
        }
        showFailureDialog(parent);
        return InstallDialogResult.INSTALL_APPROVED_FAILED;
    }

    // -------------------------------------------------------------------------
    // Installation
    // -------------------------------------------------------------------------

    private static boolean performInstallation(Component parent)
    {
        LinuxPackageManagerDetector.PackageManager pm =
            LinuxPackageManagerDetector.getPrimaryPackageManager();
        if (pm == null)
        {
            LOG.error("No package manager detected - cannot install an audio player");
            return false;
        }
        String cmd = pm.getFullInstallCommand().replace("{pkg}", PLAYER_PACKAGE);
        LOG.info("Installing audio player via: {}", cmd);

        // Preferred: run as Scarlet's own child process with a progress dialog and reliable exit
        // code. Root package managers use pkexec; user-space managers stay as the user.
        int installExit = pm.requiresSudo
            ? PkexecInstaller.run(parent, "Installing " + PLAYER_DISPLAY_NAME, cmd)
            : PkexecInstaller.runUserCommand(parent, "Installing " + PLAYER_DISPLAY_NAME, cmd);
        if (installExit == 0)
            return isAudioPlayerInstalled();
        if (!shouldFallbackToTerminal(pm, installExit))
            return false;

        try
        {
            int exit = PkexecInstaller.runTerminalCommand(parent, "Installing " + PLAYER_DISPLAY_NAME, cmd);
            LOG.info("Audio player embedded terminal install exited with code {}", exit);
        }
        catch (Exception ex)
        {
            LOG.error("Exception while installing audio player", ex);
            return false;
        }
        // Verify the player exists before reporting success.
        boolean ok = Sys.waitForCondition(LinuxAudioPlayerInstallDialogs::isAudioPlayerInstalled, 180_000L, 1000L);
        if (!ok)
            LOG.warn("Audio player still not found after installation attempt");
        return ok;
    }

    // -------------------------------------------------------------------------
    // Dialogs
    // -------------------------------------------------------------------------

    private static boolean showConsentDialog(Component parent)
    {
        final String installCmd = getInstallCommand();
        final boolean[] result = {false};
        Swing.invokeWait(() ->
        {
            String message =
                "<html><div style='width:430px;'>" +
                "<h3>No audio player found</h3>" +
                "<p>" + PLAYER_DESCRIPTION.replace("\n", "<br>") + "</p>" +
                "<p style='margin-top:8px;'><b>The following command will be run:</b></p>" +
                "<pre style='background:#2d2d2d;padding:8px;border-radius:4px;font-family:monospace;'>" +
                installCmd + "</pre>" +
                "<p style='margin-top:8px;color:#FF9800;'>&#9888; Scarlet will show progress and may ask for your password.</p>" +
                "<p style='margin-top:8px;'>Install it now? Until then, Scarlet falls back to direct ALSA output.</p>" +
                "</div></html>";
            int choice = JOptionPane.showConfirmDialog(
                parent,
                // High-DPI-safe wrap: see Swing.fitToScreen javadoc.
                Swing.fitToScreen(new JLabel(message)),
                "Install audio player?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
            result[0] = (choice == JOptionPane.YES_OPTION);
        });
        return result[0];
    }

    private static void showDeclineDialog(Component parent)
    {
        final String installCmd = getInstallCommand();
        Swing.invokeWait(() ->
            JOptionPane.showMessageDialog(
                parent,
                Swing.fitToScreen(new JLabel(
                    "<html><div style='width:400px;'>" +
                    "<p>No audio player will be installed.</p>" +
                    "<p style='margin-top:8px;'>Scarlet will try direct ALSA output via Java Sound, which may not " +
                    "appear in your volume mixer or reach your selected device. You can install a player later:</p>" +
                    "<pre style='background:#2d2d2d;padding:6px;border-radius:4px;font-family:monospace;'>" +
                    installCmd + "</pre>" +
                    "</div></html>")),
                "Audio player not installed",
                JOptionPane.WARNING_MESSAGE));
    }

    private static void showSuccessDialog(Component parent)
    {
        Swing.invokeWait(() ->
            JOptionPane.showMessageDialog(
                parent,
                Swing.fitToScreen(new JLabel(
                    "<html><div style='width:340px;'>" +
                    "<h3 style='color:#4CAF50;'>&#10003; Installed</h3>" +
                    "<p>An audio player is now available. The next TTS announcement will use it.</p>" +
                    "</div></html>")),
                "Audio player installed",
                JOptionPane.INFORMATION_MESSAGE));
    }

    private static void showFailureDialog(Component parent)
    {
        final String installCmd = getInstallCommand();
        Swing.invokeWait(() ->
            JOptionPane.showMessageDialog(
                parent,
                Swing.fitToScreen(new JLabel(
                    "<html><div style='width:420px;'>" +
                    "<h3 style='color:#F44336;'>&#10007; Installation failed</h3>" +
                    "<p>Could not install an audio player automatically. You can install one manually:</p>" +
                    "<pre style='background:#2d2d2d;padding:6px;border-radius:4px;font-family:monospace;'>" +
                    installCmd + "</pre>" +
                    "</div></html>")),
                "Audio player installation failed",
                JOptionPane.ERROR_MESSAGE));
    }

    // -------------------------------------------------------------------------
    // Headless / helpers
    // -------------------------------------------------------------------------

    private static InstallDialogResult handleHeadlessMode()
    {
        System.out.println("\n========================================");
        System.out.println("[Hatson321/Scarlet Logger/TTS] No audio player found");
        System.out.println("========================================");
        System.out.println("Scarlet needs pw-play, paplay, or aplay to play TTS on your selected output device.");
        System.out.println("Install one, e.g.:");
        System.out.println("  " + getInstallCommand());
        System.out.println("Until then, Scarlet falls back to direct ALSA output.");
        System.out.println("========================================\n");
        return InstallDialogResult.HEADLESS_MODE;
    }

    private static boolean shouldFallbackToTerminal(LinuxPackageManagerDetector.PackageManager pm, int exit)
    {
        if (pm != null && !pm.requiresSudo)
            return exit == -1;
        return exit == -1 || exit == 127;
    }

    private static String getInstallCommand()
    {
        LinuxPackageManagerDetector.PackageManager pm =
            LinuxPackageManagerDetector.getPrimaryPackageManager();
        if (pm == null)
            return "sudo apt-get install -y " + PLAYER_PACKAGE; // safe default
        return pm.getFullInstallCommand().replace("{pkg}", PLAYER_PACKAGE);
    }

    private static boolean isCommandAvailable(String command)
    {
        return Sys.hasInPath(command);
    }
}

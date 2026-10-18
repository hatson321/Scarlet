package net.sybyline.scarlet.util;

import java.awt.Component;
import java.awt.GraphicsEnvironment;

import javax.swing.JLabel;
import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.ui.Swing;
import net.sybyline.scarlet.util.tts.LinuxPackageManagerDetector;

/**
 * Prompts Linux desktop users to install notify-send when desktop notifications
 * are enabled but no usable notifier is available.
 */
public final class LinuxNotificationInstallDialogs
{
    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/Notifications");

    public static final String NOTIFIER_COMMAND = "notify-send";
    public static final String NOTIFIER_DISPLAY_NAME = "notify-send";
    public static final String NOTIFIER_DESCRIPTION =
        "Scarlet uses notify-send for Linux desktop notifications because it is\n" +
        "the standard libnotify command understood by GNOME, KDE, XFCE, and\n" +
        "most other desktop notification daemons.";

    public enum InstallDialogResult
    {
        ALREADY_INSTALLED,
        INSTALL_APPROVED_SUCCESS,
        INSTALL_APPROVED_FAILED,
        INSTALL_DECLINED,
        HEADLESS_MODE,
        UNSUPPORTED_PLATFORM
    }

    private LinuxNotificationInstallDialogs()
    {
    }

    public static boolean isNotifierInstalled()
    {
        return Sys.hasInPath(NOTIFIER_COMMAND);
    }

    public static InstallDialogResult showInstallFlowIfNeeded(Component parent)
    {
        if (isNotifierInstalled())
            return InstallDialogResult.ALREADY_INSTALLED;
        if (Platform.CURRENT != Platform.$NIX)
            return InstallDialogResult.UNSUPPORTED_PLATFORM;

        LOG.warn("notify-send not found on this system");

        LinuxPackageManagerDetector.PackageManager pm =
            LinuxPackageManagerDetector.getPrimaryDesktopNotificationPackageManager();
        if (pm == null)
        {
            if (isHeadlessMode())
                return handleHeadlessMode(null);
            showUnsupportedDialog(parent);
            return InstallDialogResult.UNSUPPORTED_PLATFORM;
        }

        if (isHeadlessMode())
            return handleHeadlessMode(pm);

        if (!showConsentDialog(parent, pm))
        {
            showDeclineDialog(parent, pm);
            return InstallDialogResult.INSTALL_DECLINED;
        }

        if (performInstallation(parent, pm))
        {
            showSuccessDialog(parent);
            return InstallDialogResult.INSTALL_APPROVED_SUCCESS;
        }
        showFailureDialog(parent, pm);
        return InstallDialogResult.INSTALL_APPROVED_FAILED;
    }

    private static boolean isHeadlessMode()
    {
        return Platform.forceHeadlessUi() || GraphicsEnvironment.isHeadless();
    }

    private static boolean performInstallation(Component parent, LinuxPackageManagerDetector.PackageManager pm)
    {
        String cmd = LinuxPackageManagerDetector.getDesktopNotificationInstallCommand(pm);
        if (cmd == null || cmd.trim().isEmpty())
            return false;

        LOG.info("Installing notify-send via: {}", cmd);

        // Preferred: run as Scarlet's own child process with a progress dialog and reliable exit
        // code. Root package managers use pkexec; user-space managers stay as the user.
        int installExit = pm.requiresSudo
            ? PkexecInstaller.run(parent, "Installing " + NOTIFIER_DISPLAY_NAME, cmd)
            : PkexecInstaller.runUserCommand(parent, "Installing " + NOTIFIER_DISPLAY_NAME, cmd);
        if (installExit == 0)
        {
            ScarletToast.resetAvailabilityCache();
            return isNotifierInstalled();
        }
        if (!shouldFallbackToTerminal(pm, installExit))
            return false;

        try
        {
            int exit = PkexecInstaller.runTerminalCommand(parent, "Installing " + NOTIFIER_DISPLAY_NAME, cmd);
            LOG.info("notify-send embedded terminal install exited with code {}", exit);
        }
        catch (Exception ex)
        {
            LOG.error("Exception while installing notify-send", ex);
            return false;
        }

        // Verify notify-send exists before reporting success.
        boolean ok = Sys.waitForCondition(LinuxNotificationInstallDialogs::isNotifierInstalled, 180_000L, 1000L);
        ScarletToast.resetAvailabilityCache();
        if (!ok)
            LOG.warn("notify-send still not found after installation attempt");
        return ok;
    }

    private static boolean showConsentDialog(Component parent, LinuxPackageManagerDetector.PackageManager pm)
    {
        final String installCmd = LinuxPackageManagerDetector.getDesktopNotificationInstallCommand(pm);
        final boolean[] result = {false};
        Swing.invokeWait(() ->
        {
            String message =
                "<html><div style='width:430px;'>" +
                "<h3>No Linux desktop notifier found</h3>" +
                "<p>" + NOTIFIER_DESCRIPTION.replace("\n", "<br>") + "</p>" +
                "<p style='margin-top:8px;'><b>The following command will be run:</b></p>" +
                "<pre style='background:#2d2d2d;padding:8px;border-radius:4px;font-family:monospace;'>" +
                installCmd + "</pre>" +
                "<p style='margin-top:8px;color:#FF9800;'>&#9888; Scarlet will show progress and may ask for your password.</p>" +
                "<p style='margin-top:8px;'>Install it now?</p>" +
                "</div></html>";
            int choice = JOptionPane.showConfirmDialog(
                parent,
                Swing.fitToScreen(new JLabel(message)),
                "Install desktop notifier?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
            result[0] = (choice == JOptionPane.YES_OPTION);
        });
        return result[0];
    }

    private static void showDeclineDialog(Component parent, LinuxPackageManagerDetector.PackageManager pm)
    {
        final String installCmd = LinuxPackageManagerDetector.getDesktopNotificationInstallCommand(pm);
        Swing.invokeWait(() ->
            JOptionPane.showMessageDialog(
                parent,
                Swing.fitToScreen(new JLabel(
                    "<html><div style='width:400px;'>" +
                    "<p>Desktop notification support will not be installed.</p>" +
                    "<p style='margin-top:8px;'>Scarlet will keep trying other notification paths, but most Linux " +
                    "desktops need notify-send for reliable toasts. You can install it later:</p>" +
                    "<pre style='background:#2d2d2d;padding:6px;border-radius:4px;font-family:monospace;'>" +
                    installCmd + "</pre>" +
                    "</div></html>")),
                "Desktop notifier not installed",
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
                    "<p>notify-send is now available. Scarlet can show Linux desktop notifications.</p>" +
                    "</div></html>")),
                "Desktop notifier installed",
                JOptionPane.INFORMATION_MESSAGE));
    }

    private static void showFailureDialog(Component parent, LinuxPackageManagerDetector.PackageManager pm)
    {
        final String installCmd = LinuxPackageManagerDetector.getDesktopNotificationInstallCommand(pm);
        Swing.invokeWait(() ->
            JOptionPane.showMessageDialog(
                parent,
                Swing.fitToScreen(new JLabel(
                    "<html><div style='width:420px;'>" +
                    "<h3 style='color:#F44336;'>&#10007; Installation failed</h3>" +
                    "<p>Could not install notify-send automatically. You can install it manually:</p>" +
                    "<pre style='background:#2d2d2d;padding:6px;border-radius:4px;font-family:monospace;'>" +
                    installCmd + "</pre>" +
                    "</div></html>")),
                "Desktop notifier installation failed",
                JOptionPane.ERROR_MESSAGE));
    }

    private static void showUnsupportedDialog(Component parent)
    {
        Swing.invokeWait(() ->
            JOptionPane.showMessageDialog(
                parent,
                Swing.fitToScreen(new JLabel(
                    "<html><div style='width:410px;'>" +
                    "<h3>No supported installer found</h3>" +
                    "<p>Scarlet could not find a package manager mapping that can install notify-send automatically.</p>" +
                    "<p style='margin-top:8px;'>Install your distro's libnotify/notify-send package manually, then try the notification test again.</p>" +
                    "</div></html>")),
                "Desktop notifier unavailable",
                JOptionPane.WARNING_MESSAGE));
    }

    private static InstallDialogResult handleHeadlessMode(LinuxPackageManagerDetector.PackageManager pm)
    {
        System.out.println("\n========================================");
        System.out.println("[Hatson321/Scarlet Logger] Linux desktop notifier required");
        System.out.println("========================================");
        System.out.println("Scarlet needs notify-send to show Linux desktop notifications.");
        if (pm != null)
        {
            System.out.println("Install it with:");
            System.out.println("  " + LinuxPackageManagerDetector.getDesktopNotificationInstallCommand(pm));
        }
        else
        {
            System.out.println("Install your distro's libnotify/notify-send package manually.");
        }
        System.out.println("Desktop notifications will be unavailable until notify-send is installed.");
        System.out.println("========================================\n");
        return InstallDialogResult.HEADLESS_MODE;
    }

    private static boolean shouldFallbackToTerminal(LinuxPackageManagerDetector.PackageManager pm, int exit)
    {
        if (pm != null && !pm.requiresSudo)
            return exit == -1;
        return exit == -1 || exit == 127;
    }
}

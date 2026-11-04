package net.sybyline.scarlet.util.tts;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.util.Platform;
import net.sybyline.scarlet.util.PkexecInstaller;
import net.sybyline.scarlet.util.Sys;
import net.sybyline.scarlet.ui.Swing;

/**
 * Manages TTS package installation dialogs with user consent.
 * Handles detection, prompting, and installation of Linux TTS engines.
 */
public class TtsPackageInstallDialogs
{

    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/TTS/Dialogs");

    public static final String LINUX_PACKAGE_NAME         = "espeak-ng";
    public static final String LINUX_PACKAGE_DISPLAY_NAME = "Linux TTS engine";
    public static final String LINUX_PACKAGE_DESCRIPTION  =
        "Scarlet needs one command-line speech engine to generate announcements on Linux.\n\n" +
        "The automatic setup installs only the default engine for your package manager.\n" +
        "More voices can be installed later from Settings -> Text-to-Speech.";

    private final Platform  platform;
    private final Component parentComponent;

    public TtsPackageInstallDialogs(Platform platform, Component parentComponent)
    {
        this.platform        = platform;
        this.parentComponent = parentComponent;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static boolean isPackageInstalled(Platform platform)
    {
        if (platform == Platform.$NIX) return isAnyLinuxTtsEngineInstalled();
        if (platform == Platform.NT)   return true; // Windows uses built-in SAPI
        return false;
    }

    public static String getLinuxInstallCommand()
    {
        return LinuxPackageManagerDetector.getTtsInstallCommand();
    }

    public enum InstallDialogResult
    {
        ALREADY_INSTALLED,
        INSTALL_APPROVED_SUCCESS,
        INSTALL_APPROVED_FAILED,
        INSTALL_UNAVAILABLE,
        INSTALL_DECLINED,
        HEADLESS_MODE
    }

    public InstallDialogResult showInstallFlow()
    {
        if (isPackageInstalled(this.platform))
        {
            showInfo("TTS Package Status",
                "<h2 style='color:#4CAF50;'>&#10003; Package Already Installed</h2>" +
                "<p style='margin-top:10px;'>" + getPackageDisplayName() + " is already installed.</p>" +
                "<p style='margin-top:10px;color:#888;'>Hatson321/Scarlet Logger's text-to-speech functionality is ready to use.</p>");
            return InstallDialogResult.ALREADY_INSTALLED;
        }

        if (GraphicsEnvironment.isHeadless())
            return handleHeadlessMode();

        if (LinuxPackageManagerDetector.getPrimaryTtsPackageManager() == null)
        {
            showNoPackageManagerDialog();
            return InstallDialogResult.INSTALL_UNAVAILABLE;
        }

        if (!showDownloadConsentDialog())
        {
            showDeclineAcknowledgmentDialog();
            return InstallDialogResult.INSTALL_DECLINED;
        }

        if (performInstallationWithTerminal())
            return InstallDialogResult.INSTALL_APPROVED_SUCCESS;

        return handleInstallationFailure();
    }

    public void showPackageAlreadyInstalledDialog()
    {
        showInfo("TTS Package Status",
            "<h2 style='color:#4CAF50;'>&#10003; Package Already Installed</h2>" +
            "<p style='margin-top:10px;'>" + getPackageDisplayName() + " is already installed.</p>" +
            "<p style='margin-top:10px;color:#888;'>Hatson321/Scarlet Logger's text-to-speech functionality is ready to use.</p>");
    }

    public void showOptionalPackageInstallDialog()
    {
        if (this.platform != Platform.$NIX)
        {
            showInfo("Linux TTS Packages",
                "<h3>Linux-only feature</h3>" +
                "<p style='margin-top:10px;'>Extra command-line TTS packages are only installed through Linux package managers.</p>");
            return;
        }
        if (GraphicsEnvironment.isHeadless())
        {
            handleHeadlessOptionalPackages();
            return;
        }

        List<LinuxPackageManagerDetector.PackageManager> managers =
            LinuxPackageManagerDetector.detectAllTtsPackageManagers();
        if (managers.isEmpty())
        {
            showNoPackageManagerDialog();
            return;
        }

        AtomicReference<LinuxPackageManagerDetector.PackageManager> selectedManager =
            new AtomicReference<>(managers.get(0));
        AtomicReference<List<LinuxPackageManagerDetector.TtsPackageOption>> selectedOptions =
            new AtomicReference<>(new ArrayList<>());

        boolean accepted = Swing.getWait(() ->
        {
            JPanel panel = new JPanel(new BorderLayout(10, 10));
            panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            JLabel header = new JLabel("<html><div style='width:520px;'>" +
                "<h3>Install extra Linux TTS voices</h3>" +
                "<p>Choose exactly which additional command-line TTS engines Scarlet should ask your package manager to install. Nothing here is installed automatically.</p>" +
                atomicInstallNoticeHtml() +
                "</div></html>");
            panel.add(header, BorderLayout.NORTH);

            JComboBox<String> managerCombo = new JComboBox<>();
            for (LinuxPackageManagerDetector.PackageManager pm : managers)
                managerCombo.addItem(pm.displayName);

            JPanel optionsPanel = new JPanel();
            optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
            List<JCheckBox> checkboxes = new ArrayList<>();

            Runnable[] refreshOptions = new Runnable[1];
            refreshOptions[0] = () ->
            {
                optionsPanel.removeAll();
                checkboxes.clear();
                LinuxPackageManagerDetector.PackageManager pm = selectedManager.get();
                List<LinuxPackageManagerDetector.TtsPackageOption> options =
                    LinuxPackageManagerDetector.getTtsPackageOptions(pm);
                for (LinuxPackageManagerDetector.TtsPackageOption option : options)
                {
                    boolean installed = option.isInstalled();
                    JCheckBox box = new JCheckBox(option.displayName + "  [" + option.packageName + "]"
                        + (installed ? " (installed)" : ""));
                    box.setEnabled(!installed);
                    box.setSelected(false);
                    box.setToolTipText(option.description);
                    box.putClientProperty("ttsPackageOption", option);
                    checkboxes.add(box);
                    optionsPanel.add(box);
                }
                if (options.isEmpty())
                    optionsPanel.add(new JLabel("No installable TTS packages are known for this package manager."));
                optionsPanel.revalidate();
                optionsPanel.repaint();
            };
            refreshOptions[0].run();

            managerCombo.addActionListener(e ->
            {
                int idx = managerCombo.getSelectedIndex();
                if (idx >= 0 && idx < managers.size())
                {
                    selectedManager.set(managers.get(idx));
                    refreshOptions[0].run();
                }
            });

            JPanel center = new JPanel(new BorderLayout(8, 8));
            center.add(managerCombo, BorderLayout.NORTH);
            JScrollPane scroll = new JScrollPane(optionsPanel);
            scroll.setPreferredSize(new Dimension(560, 220));
            center.add(scroll, BorderLayout.CENTER);
            panel.add(center, BorderLayout.CENTER);

            int choice = JOptionPane.showConfirmDialog(
                this.parentComponent,
                Swing.fitToScreen(panel),
                "Install Linux TTS voices",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION)
                return false;

            List<LinuxPackageManagerDetector.TtsPackageOption> chosen = new ArrayList<>();
            for (JCheckBox box : checkboxes)
            {
                if (!box.isEnabled() || !box.isSelected())
                    continue;
                Object value = box.getClientProperty("ttsPackageOption");
                if (value instanceof LinuxPackageManagerDetector.TtsPackageOption)
                    chosen.add((LinuxPackageManagerDetector.TtsPackageOption)value);
            }
            selectedOptions.set(chosen);
            return true;
        });

        if (!accepted)
            return;

        List<LinuxPackageManagerDetector.TtsPackageOption> options = selectedOptions.get();
        if (options == null || options.isEmpty())
        {
            showInfo("No TTS Packages Selected",
                "<p>Select one or more uninstalled TTS packages to install.</p>");
            return;
        }

        installOptionalPackages(selectedManager.get(), options);
    }

    public boolean showDownloadConsentDialog()
    {
        if (GraphicsEnvironment.isHeadless())
            return handleHeadlessConsent();

        List<LinuxPackageManagerDetector.PackageManager> managers = LinuxPackageManagerDetector.detectAllTtsPackageManagers();
        LinuxPackageManagerDetector.PackageManager primary = managers.isEmpty() ? null : managers.get(0);
        if (primary == null)
        {
            showNoPackageManagerDialog();
            return false;
        }
        String installCmd = primary.getInstallCommand();

        StringBuilder pmInfo = new StringBuilder(atomicInstallNoticeHtml());
        if (managers.size() > 1)
        {
            pmInfo.append("<p style='margin-top:10px;'><b>Detected package managers:</b><br>");
            for (int i = 0; i < Math.min(5, managers.size()); i++)
                pmInfo.append("&bull; ").append(managers.get(i).displayName).append("<br>");
            if (managers.size() > 5)
                pmInfo.append("&bull; and ").append(managers.size() - 5).append(" more...<br>");
            pmInfo.append("</p>");
        }

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JPanel header = new JPanel(new BorderLayout(10, 0));
        JLabel icon = new JLabel("&#9888;");
        icon.setFont(icon.getFont().deriveFont(24f));
        header.add(icon, BorderLayout.WEST);
        header.add(new JLabel("<html><b style='font-size:14px;'>TTS Package Required</b></html>"), BorderLayout.CENTER);

        panel.add(header, BorderLayout.NORTH);
        panel.add(new JLabel(String.format(
            "<html><div style='width:450px;padding:5px;'>" +
            "<p style='margin-bottom:10px;'>Hatson321/Scarlet Logger needs a local <b>%s</b> package for Linux text-to-speech.</p>" +
            "<p style='margin-bottom:10px;'>%s</p>" +
            "<p style='margin-bottom:10px;'><b>The following command will be executed:</b></p>" +
            "<pre style='background-color:#2d2d2d;padding:10px;border-radius:5px;font-family:monospace;'>%s</pre>" +
            "%s" +
            "<p style='margin-top:10px;color:#FF9800;'>&#9888; This will download and install software from your system's package manager.</p>" +
            "</div></html>",
            getPackageDisplayName(), LINUX_PACKAGE_DESCRIPTION.replace("\n", "<br>"), html(installCmd), pmInfo
        )), BorderLayout.CENTER);
        panel.add(new JLabel("<html><div style='margin-top:10px;padding-top:10px;border-top:1px solid #444;'>" +
            "<b>Yes</b> - Install the package (a progress window will show output; password prompt if needed)<br>" +
            "<b>No</b> - Skip installation (TTS features will be disabled)</div></html>"), BorderLayout.SOUTH);

        return Swing.getWait(() -> JOptionPane.showConfirmDialog(
            this.parentComponent,
            // High-DPI-safe wrap: keeps the Yes/No buttons on screen when
            // Windows display scale is above 100% (see Swing.fitToScreen).
            Swing.fitToScreen(panel),
            "TTS Package Installation",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION);
    }

    public void showDeclineAcknowledgmentDialog()
    {
        if (GraphicsEnvironment.isHeadless())
        {
            System.out.println("[Hatson321/Scarlet Logger/TTS] TTS package installation declined. TTS features will be disabled.");
            System.out.println("[Hatson321/Scarlet Logger/TTS] You can manually install " + getPackageDisplayName() + " later to enable TTS functionality.");
            return;
        }

        List<String> commands = LinuxPackageManagerDetector.getAllTtsInstallCommands();
        StringBuilder cmdsHtml = new StringBuilder();
        if (!commands.isEmpty())
        {
            cmdsHtml.append("<p style='margin-top:15px;'><b>You can install the package later by running one of:</b></p>")
                    .append("<pre style='background-color:#2d2d2d;padding:8px;border-radius:5px;font-family:monospace;'>");
            for (int i = 0; i < Math.min(3, commands.size()); i++)
                cmdsHtml.append(html(commands.get(i))).append("\n");
            cmdsHtml.append("</pre>");
        }

        Swing.invokeWait(() ->
        {
            JLabel msg = new JLabel(String.format(
                "<html><div style='width:450px;padding:5px;'>" +
                "<h3 style='color:#FF9800;'>&#9888; TTS Features Disabled</h3>" +
                "<p style='margin-top:10px;'>You have chosen not to install <b>%s</b>.</p>" +
                "<ul style='margin-top:5px;'>" +
                "<li>Text-to-speech notifications will not be available</li>" +
                "<li>Audio announcements in Discord will be disabled</li>" +
                "<li>Other Hatson321/Scarlet Logger features will continue to work normally</li>" +
                "</ul>%s</div></html>",
                getPackageDisplayName(), cmdsHtml
            ));
            JButton ok = new JButton("I understand");
            JOptionPane pane = new JOptionPane(
                Swing.fitToScreen(msg),
                JOptionPane.WARNING_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, new Object[]{ok});
            JDialog dialog = pane.createDialog(this.parentComponent, "TTS Installation Declined");
            ok.addActionListener(e -> dialog.dispose());
            dialog.setVisible(true);
        });
    }

    public boolean performInstallationWithTerminal()
    {
        if (this.platform != Platform.$NIX)
        {
            LOG.warn("Package installation not supported on platform: {}", this.platform);
            return false;
        }
        LinuxPackageManagerDetector.PackageManager pm = LinuxPackageManagerDetector.getPrimaryTtsPackageManager();
        if (pm == null)
        {
            LOG.error("No package manager detected");
            showNoPackageManagerDialog();
            return false;
        }
        return performInstallationWithManager(pm);
    }

    // -------------------------------------------------------------------------
    // Installation failure handling
    // -------------------------------------------------------------------------

    private InstallDialogResult handleInstallationFailure()
    {
        List<LinuxPackageManagerDetector.PackageManager> managers = LinuxPackageManagerDetector.detectAllTtsPackageManagers();
        if (managers.size() <= 1)
        {
            showInstallationFailedDialog();
            return InstallDialogResult.INSTALL_APPROVED_FAILED;
        }
        while (true)
        {
            LinuxPackageManagerDetector.PackageManager selected = showPackageManagerSelectionDialog();
            if (selected == null)
            {
                showDeclineAcknowledgmentDialog();
                return InstallDialogResult.INSTALL_DECLINED;
            }
            if (performInstallationWithManager(selected))
                return InstallDialogResult.INSTALL_APPROVED_SUCCESS;
            if (!showRetryDialog())
            {
                showDeclineAcknowledgmentDialog();
                return InstallDialogResult.INSTALL_APPROVED_FAILED;
            }
        }
    }

    private LinuxPackageManagerDetector.PackageManager showPackageManagerSelectionDialog()
    {
        List<LinuxPackageManagerDetector.PackageManager> managers = LinuxPackageManagerDetector.detectAllTtsPackageManagers();
        AtomicReference<LinuxPackageManagerDetector.PackageManager> result = new AtomicReference<>(null);

        Swing.invokeWait(() ->
        {
            JComboBox<String> combo = new JComboBox<>();
            for (LinuxPackageManagerDetector.PackageManager pm : managers)
                combo.addItem(pm.displayName + ": " + pm.getInstallCommand());

            JTextArea preview = new JTextArea(3, 40);
            preview.setEditable(false);
            preview.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
            preview.setBackground(new java.awt.Color(45, 45, 45));
            preview.setForeground(java.awt.Color.WHITE);
            updatePreview(preview, managers.get(0));
            combo.addActionListener(e -> {
                int i = combo.getSelectedIndex();
                if (i >= 0 && i < managers.size()) updatePreview(preview, managers.get(i));
            });

            JPanel center = new JPanel(new BorderLayout(5, 5));
            center.add(new JLabel("<html><div style='width:450px;'>" +
                "<p style='margin-top:10px;'>The installation with the default package manager failed.</p>" +
                "<p style='margin-top:10px;'>Select an alternative package manager below:</p></div></html>"),
                BorderLayout.NORTH);
            center.add(combo, BorderLayout.CENTER);
            center.add(new JScrollPane(preview) {{ setPreferredSize(new Dimension(450, 80)); }}, BorderLayout.SOUTH);

            JPanel panel = new JPanel(new BorderLayout(10, 10));
            panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
            panel.add(new JLabel("<html><b style='font-size:14px;'>&#9888; Installation Failed - Select Alternative</b></html>"), BorderLayout.NORTH);
            panel.add(center, BorderLayout.CENTER);

            JButton install = new JButton("Install with Selected");
            JButton cancel  = new JButton("Cancel");
            JOptionPane pane = new JOptionPane(
                Swing.fitToScreen(panel),
                JOptionPane.WARNING_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, new Object[]{install, cancel});
            JDialog dialog = pane.createDialog(this.parentComponent, "Package Manager Selection");
            install.addActionListener(e -> {
                int i = combo.getSelectedIndex();
                if (i >= 0 && i < managers.size()) result.set(managers.get(i));
                dialog.dispose();
            });
            cancel.addActionListener(e -> dialog.dispose());
            dialog.setVisible(true);
        });

        return result.get();
    }

    private void updatePreview(JTextArea area, LinuxPackageManagerDetector.PackageManager pm)
    {
        area.setText(String.format("Package Manager: %s\nPackage Name: %s\nCommand: %s",
            pm.displayName, pm.packageName,
            pm.getInstallCommand()));
    }

    private boolean showRetryDialog()
    {
        if (GraphicsEnvironment.isHeadless()) return false;
        return Swing.getWait(() -> JOptionPane.showConfirmDialog(
            this.parentComponent,
            Swing.fitToScreen(new JLabel(
                "<html><div style='width:400px;'><h3 style='color:#F44336;'>&#10007; Installation Failed</h3>" +
                "<p style='margin-top:10px;'>The installation did not complete successfully.</p>" +
                "<p style='margin-top:10px;'>Would you like to try a different package manager?</p></div></html>"
            )),
            "Installation Failed", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION);
    }

    private boolean performInstallationWithManager(LinuxPackageManagerDetector.PackageManager pm)
    {
        String installCmd = pm.getInstallCommand();
        LOG.info("Attempting to install {} using {}", LINUX_PACKAGE_DISPLAY_NAME, pm.displayName);

        // Preferred path: run as Scarlet's own child process with a progress dialog and reliable
        // exit code. Root package managers use pkexec; user-space managers stay as the user.
        int installExit = runInstaller(pm, installCmd);
        if (installationSucceeded(installExit, TtsPackageInstallDialogs::isAnyLinuxTtsEngineInstalled))
        {
            showInfo("Installation Complete",
                "<h2 style='color:#4CAF50;'>&#10003; Installation Successful</h2>" +
                "<p style='margin-top:10px;'>" + getPackageDisplayName() + " has been installed successfully!</p>" +
                "<p style='margin-top:10px;'>Hatson321/Scarlet Logger's text-to-speech functionality is now ready to use.</p>");
            return true;
        }
        // pkexec unavailable (-1) or no polkit agent (127) can fall through to a terminal. For
        // user-space managers, only a start failure falls back; normal non-zero exits are real.
        if (!shouldFallbackToTerminal(pm, installExit))
            return false;

        try
        {
            int exit = PkexecInstaller.runTerminalCommand(this.parentComponent,
                "Installing " + getPackageDisplayName(), installCmd);
            LOG.info("Embedded terminal install exited with code: {}", exit);
            // The embedded terminal has a reliable exit code, but still verify the engine Scarlet
            // actually needs before declaring the install complete.
            if (installationSucceeded(exit,
                () -> Sys.waitForCondition(TtsPackageInstallDialogs::isAnyLinuxTtsEngineInstalled, 180_000L, 1000L)))
            {
                showInfo("Installation Complete",
                    "<h2 style='color:#4CAF50;'>&#10003; Installation Successful</h2>" +
                    "<p style='margin-top:10px;'>" + getPackageDisplayName() + " has been installed successfully!</p>" +
                    "<p style='margin-top:10px;'>Hatson321/Scarlet Logger's text-to-speech functionality is now ready to use.</p>");
                return true;
            }
            showInstallationFailedDialog();
            return false;
        }
        catch (Exception ex)
        {
            LOG.error("Exception during terminal installation", ex);
            showError("Installation Error",
                "<h3 style='color:#F44336;'>&#10007; Installation Error</h3>" +
                "<p style='margin-top:10px;'>An error occurred during installation:</p>" +
                "<pre style='background-color:#2d2d2d;padding:8px;border-radius:5px;font-family:monospace;'>" + ex.getMessage() + "</pre>" +
                "<p style='margin-top:10px;'>Please try installing manually:</p>" +
                "<pre style='background-color:#2d2d2d;padding:8px;border-radius:5px;font-family:monospace;'>" + html(getLinuxInstallCommand()) + "</pre>");
            return false;
        }
    }

    private boolean installOptionalPackages(LinuxPackageManagerDetector.PackageManager pm,
        List<LinuxPackageManagerDetector.TtsPackageOption> options)
    {
        String installCmd = LinuxPackageManagerDetector.buildTtsInstallCommand(pm, options);
        if (installCmd == null || installCmd.trim().isEmpty())
        {
            showError("Installation Error",
                "<h3 style='color:#F44336;'>&#10007; No Install Command</h3>" +
                "<p style='margin-top:10px;'>Could not build an install command for the selected TTS packages.</p>");
            return false;
        }

        StringBuilder names = new StringBuilder();
        for (LinuxPackageManagerDetector.TtsPackageOption option : options)
        {
            if (names.length() > 0)
                names.append(", ");
            names.append(option.displayName);
        }

        boolean confirm = Swing.getWait(() -> JOptionPane.showConfirmDialog(
            this.parentComponent,
            Swing.fitToScreen(new JLabel(
                "<html><div style='width:520px;'>" +
                "<p>Scarlet will ask <b>" + pm.displayName + "</b> to install:</p>" +
                "<p><b>" + names + "</b></p>" +
                "<p style='margin-top:8px;'>Command:</p>" +
                "<pre style='background-color:#2d2d2d;padding:8px;border-radius:5px;font-family:monospace;'>" +
                html(installCmd) + "</pre>" +
                atomicInstallNoticeHtml() +
                "<p style='margin-top:8px;color:#FF9800;'>&#9888; Scarlet will show progress and may ask for your password.</p>" +
                "</div></html>")),
            "Install selected TTS packages?",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION);
        if (!confirm)
            return false;

        LOG.info("Installing optional Linux TTS packages via: {}", installCmd);
        boolean commandStarted = runInstallCommand(pm, installCmd);
        if (!commandStarted)
            return false;

        if (!areTtsOptionsInstalled(options))
            Sys.waitForCondition(() -> areTtsOptionsInstalled(options), 180_000L, 1000L);

        List<String> missing = new ArrayList<>();
        for (LinuxPackageManagerDetector.TtsPackageOption option : options)
            if (!option.isInstalled())
                missing.add(option.displayName);

        if (missing.isEmpty())
        {
            showInfo("TTS Packages Installed",
                "<h2 style='color:#4CAF50;'>&#10003; Installation Successful</h2>" +
                "<p style='margin-top:10px;'>Selected TTS packages are now installed.</p>" +
                "<p style='margin-top:10px;'>Restart Scarlet if the new voices do not appear in the voice list immediately.</p>");
            return true;
        }

        showError("Installation Incomplete",
            "<h3 style='color:#F44336;'>&#10007; Some packages are still unavailable</h3>" +
            "<p style='margin-top:10px;'>Scarlet could not find these TTS commands after installation: " + missing + "</p>" +
            "<p style='margin-top:10px;'>The package manager may have failed, or the selected package may not provide the command Scarlet needs. Check the terminal output above for details.</p>");
        return false;
    }

    private static boolean areTtsOptionsInstalled(List<LinuxPackageManagerDetector.TtsPackageOption> options)
    {
        for (LinuxPackageManagerDetector.TtsPackageOption option : options)
            if (!option.isInstalled())
                return false;
        return true;
    }

    private boolean runInstallCommand(LinuxPackageManagerDetector.PackageManager pm, String installCmd)
    {
        int installExit = runInstaller(pm, installCmd);
        if (installExit == 0)
            return true;
        if (!shouldFallbackToTerminal(pm, installExit))
            return false;
        // Installer unavailable -> fall back to a terminal.

        try
        {
            int exit = PkexecInstaller.runTerminalCommand(this.parentComponent,
                "Installing " + getPackageDisplayName(), installCmd);
            LOG.info("Optional TTS package embedded terminal exited with code: {}", exit);
            return exit == 0;
        }
        catch (Exception ex)
        {
            LOG.error("Exception during optional TTS package installation", ex);
            showError("Installation Error",
                "<h3 style='color:#F44336;'>&#10007; Installation Error</h3>" +
                "<p style='margin-top:10px;'>An error occurred during installation:</p>" +
                "<pre style='background-color:#2d2d2d;padding:8px;border-radius:5px;font-family:monospace;'>" + ex.getMessage() + "</pre>" +
                "<p style='margin-top:10px;'>Please try installing manually:</p>" +
                "<pre style='background-color:#2d2d2d;padding:8px;border-radius:5px;font-family:monospace;'>" + html(installCmd) + "</pre>");
            return false;
        }
    }

    /**
     * Installs with a live-output progress dialog (see {@link PkexecInstaller}). Root package
     * managers use {@code pkexec}; user-space managers run as the current user.
     */
    private int runInstaller(LinuxPackageManagerDetector.PackageManager pm, String installCmd)
    {
        if (pm != null && !pm.requiresSudo)
            return PkexecInstaller.runUserCommand(this.parentComponent, "Installing " + getPackageDisplayName(), installCmd);
        return runWithPkexec(installCmd);
    }

    private boolean shouldFallbackToTerminal(LinuxPackageManagerDetector.PackageManager pm, int exit)
    {
        if (pm != null && !pm.requiresSudo)
            return exit == -1;
        return exit == -1 || exit == 127;
    }

    private int runWithPkexec(String installCmd)
    {
        return PkexecInstaller.run(this.parentComponent, "Installing " + getPackageDisplayName(), installCmd);
    }

    private void showInstallationFailedDialog()
    {
        List<String> commands = LinuxPackageManagerDetector.getAllTtsInstallCommands();
        StringBuilder cmds = new StringBuilder();
        for (int i = 0; i < Math.min(3, commands.size()); i++)
            cmds.append(html(commands.get(i))).append("\n");
        showError("Installation Failed",
            "<h3 style='color:#F44336;'>&#10007; Installation Failed</h3>" +
            "<p style='margin-top:10px;'>The installation of <b>" + getPackageDisplayName() + "</b> did not complete successfully.</p>" +
            "<p style='margin-top:10px;'>Please try installing manually:</p>" +
            "<pre style='background-color:#2d2d2d;padding:8px;border-radius:5px;font-family:monospace;'>" + cmds + "</pre>" +
            "<p>" + html(LinuxPackageManagerDetector.getTtsInstallGuidance()) + "</p>");
    }

    // -------------------------------------------------------------------------
    // Headless
    // -------------------------------------------------------------------------

    private InstallDialogResult handleHeadlessMode()
    {
        List<LinuxPackageManagerDetector.PackageManager> managers = LinuxPackageManagerDetector.detectAllTtsPackageManagers();
        System.out.println("\n========================================");
        System.out.println("[Hatson321/Scarlet Logger/TTS] TTS Package Required");
        System.out.println("========================================");
        System.out.println("Hatson321/Scarlet Logger requires " + getPackageDisplayName() + " for text-to-speech functionality.");
        System.out.println("\nDetected package managers:");
        for (LinuxPackageManagerDetector.PackageManager pm : managers)
            System.out.println("  \u2022 " + pm.displayName + ": " + pm.getInstallCommand());
        System.out.println(LinuxPackageManagerDetector.getTtsInstallGuidance());
        System.out.println("\nTTS features will be disabled until the package is installed.");
        System.out.println("========================================\n");
        return InstallDialogResult.HEADLESS_MODE;
    }

    private boolean handleHeadlessConsent()
    {
        List<LinuxPackageManagerDetector.PackageManager> managers = LinuxPackageManagerDetector.detectAllTtsPackageManagers();
        System.out.println("\n[Hatson321/Scarlet Logger/TTS] " + getPackageDisplayName() + " is not installed.");
        System.out.println("[Hatson321/Scarlet Logger/TTS] Detected package managers:");
        for (LinuxPackageManagerDetector.PackageManager pm : managers)
            System.out.println("  " + pm.displayName + ": " + pm.getInstallCommand());
        System.out.println(LinuxPackageManagerDetector.getTtsInstallGuidance());
        System.out.println("[Hatson321/Scarlet Logger/TTS] TTS features will be disabled until the package is installed.\n");
        return false;
    }

    private void handleHeadlessOptionalPackages()
    {
        List<LinuxPackageManagerDetector.PackageManager> managers = LinuxPackageManagerDetector.detectAllTtsPackageManagers();
        System.out.println("\n========================================");
        System.out.println("[Hatson321/Scarlet Logger/TTS] Optional Linux TTS Packages");
        System.out.println("========================================");
        System.out.println(LinuxPackageManagerDetector.getTtsInstallGuidance());
        if (managers.isEmpty())
        {
            System.out.println("No package manager with known TTS packages was detected.");
        }
        for (LinuxPackageManagerDetector.PackageManager pm : managers)
        {
            System.out.println("\n" + pm.displayName + ":");
            for (LinuxPackageManagerDetector.TtsPackageOption option : LinuxPackageManagerDetector.getTtsPackageOptions(pm))
            {
                System.out.println("  " + (option.isInstalled() ? "[installed] " : "[available] ")
                    + option.displayName + " -> " + option.getInstallCommand(pm));
            }
        }
        System.out.println("========================================\n");
    }

    // -------------------------------------------------------------------------
    // Shared dialog helpers
    // -------------------------------------------------------------------------

    private void showNoPackageManagerDialog()
    {
        showInfo("Manual TTS setup needed",
            "<h3>No supported automatic TTS installer is available</h3><p>" +
            html(LinuxPackageManagerDetector.getTtsInstallGuidance()) + "</p>");
    }

    private static String atomicInstallNoticeHtml()
    {
        return LinuxPackageManagerDetector.isAtomicHost()
            ? "<p><b>Atomic Linux:</b> Uses existing Homebrew as your user. No host DNF/YUM, " +
              "OS package layering, or automatic reboot. Homebrew must be writable by your account.</p>"
            : "";
    }

    static String html(String text)
    {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static boolean installationSucceeded(int exit, BooleanSupplier verifyEngine)
    {
        return exit == 0 && verifyEngine.getAsBoolean();
    }

    /** Show an informational JOptionPane, or print to stdout in headless mode. */
    private void showInfo(String title, String bodyHtml)
    {
        if (GraphicsEnvironment.isHeadless())
        {
            System.out.println("[Hatson321/Scarlet Logger/TTS] " + title);
            return;
        }
        Swing.invokeWait(() -> JOptionPane.showMessageDialog(
            this.parentComponent,
            Swing.fitToScreen(new JLabel(
                "<html><div style='width:350px;padding:5px;'>" + bodyHtml + "</div></html>"
            )),
            title, JOptionPane.INFORMATION_MESSAGE));
    }

    /** Show an error JOptionPane, or print to stderr in headless mode. */
    private void showError(String title, String bodyHtml)
    {
        if (GraphicsEnvironment.isHeadless())
        {
            System.err.println("[Hatson321/Scarlet Logger/TTS] " + title);
            return;
        }
        Swing.invokeWait(() -> JOptionPane.showMessageDialog(
            this.parentComponent,
            Swing.fitToScreen(new JLabel(
                "<html><div style='width:350px;padding:5px;'>" + bodyHtml + "</div></html>"
            )),
            title, JOptionPane.ERROR_MESSAGE));
    }

    private String getPackageDisplayName()
    {
        return this.platform == Platform.$NIX ? LINUX_PACKAGE_DISPLAY_NAME : "TTS Package";
    }

    private static boolean isAnyLinuxTtsEngineInstalled()
    {
        return LinuxCommandTtsProvider.hasAnyEngineInstalled();
    }

}

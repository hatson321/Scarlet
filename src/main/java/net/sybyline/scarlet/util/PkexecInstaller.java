package net.sybyline.scarlet.util;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.ui.Swing;

/**
 * Runs install commands while showing a small progress dialog with a live-output terminal pane
 * so the user can see it working. Privileged installs use {@code pkexec} first - a graphical
 * polkit password prompt, no terminal emulator, run as Scarlet's own child process so the exit
 * code is meaningful. Commands that need terminal semantics run in an in-process PTY-backed
 * terminal instead of opening an external terminal emulator.
 *
 * <p>{@link #run(Component, String, String)} returns the process exit code, or {@code -1} if
 * {@code pkexec} is unavailable or could not be started, so the caller can fall back to a terminal.
 * A user cancel returns {@code 126}. In a headless JVM it streams to stdout with no dialog.
 */
public final class PkexecInstaller
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/PkexecInstaller");
    public static final int NOT_STARTED = -1;
    static final long INSTALL_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    private PkexecInstaller()
    {
    }

    public static boolean isAvailable()
    {
        return Sys.hasInPath("pkexec");
    }

    public static int run(Component parent, String title, String installCmd)
    {
        if (!Sys.hasInPath("pkexec"))
            return -1;
        String inner = installCmd == null ? "" : installCmd.trim();
        if (inner.regionMatches(true, 0, "sudo ", 0, 5))
            inner = inner.substring(5).trim();
        if (inner.isEmpty())
            return -1;
        if (GraphicsEnvironment.isHeadless())
            return runHeadless("[install]", "pkexec", "sh", "-c", inner);
        return runWithDialog(parent, title == null || title.trim().isEmpty() ? "Installing" : title,
            "install", false, "pkexec", "sh", "-c", inner);
    }

    public static int runUserCommand(Component parent, String title, String installCmd)
    {
        String inner = installCmd == null ? "" : installCmd.trim();
        if (inner.isEmpty())
            return -1;
        if (GraphicsEnvironment.isHeadless())
            return runHeadless("[install]", "sh", "-c", inner);
        return runTerminalCommand(parent, title, inner);
    }

    public static int runTerminalCommand(Component parent, String title, String installCmd)
    {
        String inner = installCmd == null ? "" : installCmd.trim();
        if (inner.isEmpty())
            return -1;
        String wrapped = wrapInstallCommand(inner);
        String dialogTitle = title == null || title.trim().isEmpty() ? "Installing" : title;
        if (GraphicsEnvironment.isHeadless())
            return runHeadless("[install]", "sh", "-lc", wrapped);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INSTALL_TIMEOUT_MILLIS);
        int exit = runWithDialog(parent, dialogTitle, "install", true, INSTALL_TIMEOUT_MILLIS, "sh", "-lc", wrapped);
        if (exit != NOT_STARTED || !Sys.hasInPath("script"))
            return exit;
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0)
            return BoundedProcess.TIMED_OUT;
        LOG.warn("PTY installer failed to start; trying embedded script(1) fallback");
        return runWithDialog(parent, dialogTitle, "install", false, remaining,
            "script", "-q", "-f", "-e", "-c", wrapped, "/dev/null");
    }

    private static String wrapInstallCommand(String installCmd)
    {
        return installCmd + "; status=$?; echo ''; " +
            "if [ \"$status\" -eq 0 ]; then echo 'Installation finished.'; " +
            "else echo \"Installation failed (exit code $status).\"; fi; " +
            "exit \"$status\"";
    }

    private static int runHeadless(String prefix, String... command)
    {
        InstallSession session = new InstallSession(() -> new ProcessBuilder(command).redirectErrorStream(true).start(), INSTALL_TIMEOUT_MILLIS);
        return session.run(text -> System.out.print(prefixLines(prefix, text)), process -> { });
    }

    private static int runWithDialog(Component parent, String title, String action, boolean usePty,
        String... command)
    {
        return runWithDialog(parent, title, action, usePty, INSTALL_TIMEOUT_MILLIS, command);
    }

    private static int runWithDialog(Component parent, String title, String action, boolean usePty,
        long timeoutMillis, String... command)
    {
        final AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        final AtomicReference<OutputStream> inputRef = new AtomicReference<>();
        final AtomicInteger enterKeyRef = new AtomicInteger('\n');
        final InstallSession session = new InstallSession(() -> usePty
            ? startPty(command, enterKeyRef) : new ProcessBuilder(command).redirectErrorStream(true).start(), timeoutMillis);
        final boolean privileged = command != null && command.length > 0 && "pkexec".equals(command[0]);
        final boolean terminal = usePty || (command != null && command.length > 0 && "script".equals(command[0]));

        final JDialog[] dialogRef = new JDialog[1];
        final JTextArea[] outputRef = new JTextArea[1];
        final JProgressBar[] barRef = new JProgressBar[1];
        final JLabel[] statusRef = new JLabel[1];
        final JButton[] buttonRef = new JButton[1];
        final JTextField[] inputFieldRef = new JTextField[1];
        final JButton[] sendRef = new JButton[1];

        Swing.invokeWait(() ->
        {
            Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
            JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);

            JLabel status = new JLabel(privileged
                ? "Installing... authenticate in the system password prompt when it appears."
                : terminal ? "Installing... answer package-manager prompts below if they appear."
                : "Installing... please wait while the package manager runs.");
            JProgressBar bar = new JProgressBar();
            bar.setIndeterminate(true);
            JPanel top = new JPanel(new BorderLayout(6, 6));
            top.add(status, BorderLayout.NORTH);
            top.add(bar, BorderLayout.CENTER);

            JTextArea output = new JTextArea(12, 64);
            output.setEditable(false);
            output.setLineWrap(false);
            output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scroll = new JScrollPane(output);

            JButton button = new JButton("Cancel");
            JPasswordField input = new JPasswordField();
            JCheckBox showInput = new JCheckBox("Show");
            final char hiddenEchoChar = input.getEchoChar();
            JButton send = new JButton("Send");
            input.setToolTipText("Send a line of input to the package manager");
            send.setToolTipText("Send input to the package manager");
            input.setEnabled(false);
            send.setEnabled(false);
            showInput.setToolTipText("Show typed input instead of masking it");

            input.addActionListener(e -> sendInput(inputRef, enterKeyRef, input, outputRef[0]));
            send.addActionListener(e -> sendInput(inputRef, enterKeyRef, input, outputRef[0]));
            showInput.addActionListener(e -> input.setEchoChar(showInput.isSelected() ? (char)0 : hiddenEchoChar));
            Runnable cancelOrClose = () ->
            {
                if (exitCode.get() == Integer.MIN_VALUE)
                {
                    session.cancel();
                    status.setText("Stopping installer... waiting for the owned process to finish.");
                    button.setEnabled(false);
                    input.setEnabled(false);
                    send.setEnabled(false);
                }
                else
                    dialog.dispose();
            };
            button.addActionListener(e -> cancelOrClose.run());
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { cancelOrClose.run(); }
            });

            JPanel south = new JPanel(new BorderLayout());
            JPanel inputPanel = new JPanel(new BorderLayout(6, 0));
            inputPanel.add(new JLabel("Input:"), BorderLayout.WEST);
            inputPanel.add(input, BorderLayout.CENTER);
            JPanel inputRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            inputRight.add(showInput);
            inputRight.add(send);
            inputPanel.add(inputRight, BorderLayout.EAST);
            JPanel southRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            southRight.add(button);
            south.add(inputPanel, BorderLayout.CENTER);
            south.add(southRight, BorderLayout.SOUTH);

            JPanel rootPanel = new JPanel(new BorderLayout(8, 8));
            rootPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            rootPanel.add(top, BorderLayout.NORTH);
            rootPanel.add(scroll, BorderLayout.CENTER);
            rootPanel.add(south, BorderLayout.SOUTH);

            dialog.setContentPane(rootPanel);
            dialog.pack();
            dialog.setLocationRelativeTo(parent);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

            dialogRef[0] = dialog;
            outputRef[0] = output;
            barRef[0] = bar;
            statusRef[0] = status;
            buttonRef[0] = button;
            inputFieldRef[0] = input;
            sendRef[0] = send;
        });

        Thread worker = new Thread(() ->
        {
            int code = session.run(text -> SwingUtilities.invokeLater(() ->
            {
                JTextArea o = outputRef[0];
                if (o != null)
                {
                    o.append(text);
                    o.setCaretPosition(o.getDocument().getLength());
                }
            }), p ->
            {
                inputRef.set(p.getOutputStream());
                SwingUtilities.invokeLater(() -> {
                    if (!session.cancelled.get())
                    {
                        inputFieldRef[0].setEnabled(true);
                        sendRef[0].setEnabled(true);
                    }
                });
            });
            inputRef.set(null);
            exitCode.set(code);
            final int finalCode = code;
            SwingUtilities.invokeLater(() ->
            {
                if (barRef[0] != null)
                {
                    barRef[0].setIndeterminate(false);
                    barRef[0].setValue(100);
                }
                if (statusRef[0] != null)
                    statusRef[0].setText(finalCode == 0
                        ? "Installation finished. You can close this window."
                        : finalCode == NOT_STARTED ? "Could not start the installer; no child was launched."
                        : finalCode == BoundedProcess.CANCELLED ? "Installation cancelled; verify package-manager state before retrying."
                        : finalCode == BoundedProcess.TIMED_OUT ? "Installation timed out; verify package-manager state before retrying."
                        : "Installation failed (exit code " + finalCode + ").");
                if (buttonRef[0] != null)
                {
                    buttonRef[0].setText("Close");
                    buttonRef[0].setEnabled(true);
                }
                if (inputFieldRef[0] != null)
                    inputFieldRef[0].setEnabled(false);
                if (sendRef[0] != null)
                    sendRef[0].setEnabled(false);
            });
        }, "scarlet-install");
        worker.setDaemon(true);
        worker.start();

        // Modal dialog: blocks this (background) thread until the user closes it, while the EDT
        // pumps the streamed output. The worker fills it in concurrently.
        Swing.invokeWait(() -> dialogRef[0].setVisible(true));

        int code = exitCode.get();
        if (code == Integer.MIN_VALUE)
        {
            // Owner disposal must not be mistaken for a failed start/fallback.
            session.cancel();
            return BoundedProcess.CANCELLED;
        }
        return code;
    }

    @FunctionalInterface
    interface ProcessStarter
    {
        Process start() throws Exception;
    }

    /** Child lifecycle has no Swing dependencies and is exercised with fake processes. */
    static final class InstallSession
    {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean abandonedStart = new AtomicBoolean(), cleanupClaimed = new AtomicBoolean();
        final AtomicReference<Process> process = new AtomicReference<>();
        final ProcessStarter starter;
        final long timeoutMillis;

        InstallSession(ProcessStarter starter, long timeoutMillis)
        {
            if (timeoutMillis <= 0) throw new IllegalArgumentException("Invalid install deadline");
            this.starter = starter;
            this.timeoutMillis = timeoutMillis;
        }

        void cancel()
        {
            this.cancelled.set(true);
        }

        int run(Consumer<String> output, Consumer<Process> started)
        {
            if (SwingUtilities.isEventDispatchThread())
                throw new IllegalStateException("Installer process work must not run on the Swing event thread");
            if (this.cancelled.get()) return BoundedProcess.CANCELLED;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.timeoutMillis);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch startFinished = new CountDownLatch(1);
            Thread start = new Thread(() -> {
                try
                {
                    if (!this.cancelled.get())
                    {
                        this.process.set(this.starter.start());
                        this.cleanupAbandonedStart();
                    }
                }
                catch (Throwable ex) { failure.set(ex); }
                finally { startFinished.countDown(); }
            }, "scarlet-install-start");
            start.setDaemon(true);
            start.start();
            try
            {
                while (!startFinished.await(25L, TimeUnit.MILLISECONDS))
                {
                    if (this.cancelled.get() || System.nanoTime() >= deadline)
                    {
                        this.abandonedStart.set(true);
                        this.cleanupAbandonedStart();
                        return this.cancelled.get() ? BoundedProcess.CANCELLED : BoundedProcess.TIMED_OUT;
                    }
                }
                Process child = this.process.get();
                if (child == null)
                {
                    if (this.cancelled.get()) return BoundedProcess.CANCELLED;
                    if (failure.get() != null) LOG.warn("Installer process could not be started", failure.get());
                    return NOT_STARTED;
                }
                this.cleanupClaimed.set(true);
                started.accept(child);
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0)
                {
                    BoundedProcess.await(child, 1L, MAX_OUTPUT_BYTES, () -> true, null);
                    return BoundedProcess.TIMED_OUT;
                }
                BoundedProcess.Result result = BoundedProcess.await(child, remaining, MAX_OUTPUT_BYTES, this.cancelled::get, output);
                if (!result.terminated)
                {
                    LOG.error("Installer termination could not be confirmed; no automatic fallback will be started");
                    return result.exitCode == 0 || result.exitCode == NOT_STARTED ? 1 : result.exitCode;
                }
                return result.exitCode == NOT_STARTED ? 1 : result.exitCode;
            }
            catch (InterruptedException ex)
            {
                this.cancel();
                this.abandonedStart.set(true);
                this.cleanupAbandonedStart();
                Thread.currentThread().interrupt();
                return BoundedProcess.CANCELLED;
            }
            catch (RuntimeException ex)
            {
                this.cancel();
                LOG.error("Installer failed after start; no automatic fallback will be started", ex);
                Process child = this.process.get();
                if (child != null) try { BoundedProcess.await(child, 1L, MAX_OUTPUT_BYTES, () -> true, null); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return 1;
            }
        }

        private void cleanupAbandonedStart()
        {
            Process child = this.process.get();
            if (this.abandonedStart.get() && child != null && this.cleanupClaimed.compareAndSet(false, true))
            {
                try
                {
                    if (!BoundedProcess.await(child, 1L, MAX_OUTPUT_BYTES, () -> true, null).terminated)
                        LOG.error("Late installer child termination could not be confirmed; check package-manager state before retrying");
                }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private static Process startPty(String[] command, AtomicInteger enterKeyRef) throws IOException
    {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (!env.containsKey("TERM") || env.get("TERM") == null || env.get("TERM").trim().isEmpty())
            env.put("TERM", "xterm-256color");
        PtyProcess process = new PtyProcessBuilder(command)
            .setEnvironment(env)
            .setDirectory(System.getProperty("user.dir"))
            .setRedirectErrorStream(true)
            .setInitialColumns(120)
            .setInitialRows(30)
            .start();
        enterKeyRef.set(process.getEnterKeyCode());
        return process;
    }

    private static void sendInput(AtomicReference<OutputStream> inputRef, AtomicInteger enterKeyRef,
        JTextField input, JTextArea output)
    {
        String text = readAndClearInput(input);
        OutputStream out = inputRef.get();
        if (out == null)
        {
            appendOutput(output, "[input unavailable]\n");
            return;
        }
        Thread sender = new Thread(() ->
        {
            try
            {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.write(enterKeyRef.get());
                out.flush();
                SwingUtilities.invokeLater(() -> appendOutput(output, text.isEmpty()
                    ? "[input sent: Enter]\n"
                    : "[input sent]\n"));
            }
            catch (IOException ex)
            {
                LOG.warn("Could not send input to installer", ex);
                SwingUtilities.invokeLater(() -> appendOutput(output, "[input failed]\n"));
            }
        }, "scarlet-install-input");
        sender.setDaemon(true);
        sender.start();
    }

    private static String readAndClearInput(JTextField input)
    {
        if (input == null)
            return "";
        if (input instanceof JPasswordField)
        {
            JPasswordField password = (JPasswordField)input;
            char[] chars = password.getPassword();
            password.setText("");
            try
            {
                return new String(chars);
            }
            finally
            {
                java.util.Arrays.fill(chars, '\0');
            }
        }
        String text = input.getText();
        input.setText("");
        return text == null ? "" : text;
    }

    private static void appendOutput(JTextArea output, String text)
    {
        if (output == null || text == null || text.isEmpty())
            return;
        output.append(text);
        output.setCaretPosition(output.getDocument().getLength());
    }

    private static String prefixLines(String prefix, String text)
    {
        if (text == null || text.isEmpty())
            return "";
        return prefix + " " + text.replace("\n", "\n" + prefix + " ");
    }

}

package net.sybyline.scarlet;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;

import net.sybyline.scarlet.ui.Swing;

/** Desktop export tool. Runs only after the normal Scarlet process has exited. */
public final class ScarletOfflineExport
{
    static final long CLEANUP_PROMPT_BYTES = 1L << 30;
    static final long WIPE_DELAY_NANOS = TimeUnit.SECONDS.toNanos(10L);

    private ScarletOfflineExport() { }

    public static void run() throws IOException
    {
        if (GraphicsEnvironment.isHeadless())
            throw new IOException("The offline export tool requires a desktop session.");
        Swing.init();
        char[] pin = null;
        boolean cleaned = false;
        boolean exportVerified = false;
        try (ScarletMigration.ExportSession session = ScarletMigration.openExportSession())
        {
            File data = session.dataDirectory();
            if (!data.isDirectory())
                throw new IOException("There is no Scarlet data folder to export.");
            File output = Swing.getWait(() -> chooseDestination(data));
            if (output == null)
                return;
            PinChoice choice = Swing.getWait(ScarletOfflineExport::choosePin);
            if (choice == null)
                return;
            pin = choice.pin;
            // All dialogs, cleanup, export, verification and optional wipe share the same leases.
            long cleanupBytes = cleanupSize(data.toPath());
            if (cleanupBytes > CLEANUP_PROMPT_BYTES)
            {
                Integer cleanupChoice = Swing.getWait(() -> JOptionPane.showOptionDialog(null,
                    Swing.dialogMessage("Scarlet is stopped. Logs, caches and TTS files occupy "
                        + humanBytes(cleanupBytes) + ".\n\n"
                        + "Optional cleanup removes caches, generated TTS files and older Scarlet logs; "
                        + "the newest Scarlet log is kept.\n\n"
                        + "Cleanup is a separate deletion. Cancelling or failing the export afterward does not undo it."),
                    "Optional cleanup before export", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, new Object[] { "Clean up and export", "Export without cleanup", "Cancel" }, "Export without cleanup"));
                if (cleanupChoice == null || cleanupChoice < 0 || cleanupChoice == 2)
                    return;
                if (cleanupChoice == 0)
                {
                    cleaned = true;
                    CleanupResult result = cleanup(data.toPath());
                    show("Cleanup complete", "Removed " + humanBytes(result.bytes) + ". "
                        + result.failures + " item(s) could not be removed and will remain in the export.\n\n"
                        + "Scarlet remains stopped.", JOptionPane.INFORMATION_MESSAGE);
                }
            }

            validateDestination(data.toPath(), output.toPath());
            ProgressWindow progress = Swing.getWait(ProgressWindow::new);
            if (progress == null)
                throw new IOException("The export window could not be opened.");
            try
            {
                session.exportNewBundle(output, pin, progress::update);
                if (progress.cancelled.get())
                    throw new IOException(ScarletMigration.CANCELLED_MESSAGE);
                session.verifyBundle(output, pin, progress::update);
                if (progress.cancelled.get())
                    throw new IOException(ScarletMigration.CANCELLED_MESSAGE);
                exportVerified = true;
            }
            finally
            {
                Swing.invokeWait(progress.dialog::dispose);
            }
            // Never offer removal until authenticated decryption and full archive checks succeed.
            if (exportVerified && choice.moveOut && Boolean.TRUE.equals(Swing.getWait(() -> confirmWipe(output))))
            {
                session.wipeLocalInstall();
                show("Move-out complete", "The verified export was saved to:\n" + output.getAbsolutePath()
                    + "\n\nThis computer's Scarlet data and stored credentials were removed. "
                    + "Keep the export and its PIN. Scarlet remains stopped.", JOptionPane.INFORMATION_MESSAGE);
            }
            else
            {
                show("Export verified", "The export was saved and its encryption and archive contents were verified:\n"
                    + output.getAbsolutePath() + "\n\nYour remaining local Scarlet data and credentials were kept. "
                    + "Start Scarlet again when you are ready.", JOptionPane.INFORMATION_MESSAGE);
            }
        }
        catch (Exception failure)
        {
            boolean cancelled = ScarletMigration.CANCELLED_MESSAGE.equals(failure.getMessage());
            show(cancelled ? "Export cancelled" : "Export could not complete",
                (cancelled ? "The export was cancelled." : "The export or requested removal did not complete: " + failure.getMessage())
                    + (exportVerified ? "\n\nThe export passed verification before this later error."
                        : "\n\nA new incomplete export file may remain. This attempt did not pass verification; do not use that file for restoration.")
                    + (cleaned ? "\n\nThe cleanup you confirmed earlier is not undone." : "")
                    + "\n\nScarlet remains stopped. Keep your existing backups and check the result before trying again.",
                cancelled ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
        }
        finally
        {
            if (pin != null) Arrays.fill(pin, '\0');
        }
    }

    private static File chooseDestination(File data)
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Scarlet is stopped — choose a new export filename");
        chooser.setFileFilter(new FileNameExtensionFilter("Encrypted Scarlet bundle (*.zip)", "zip"));
        chooser.setSelectedFile(new File("scarlet-migration.zip"));
        while (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION)
        {
            File selected = chooser.getSelectedFile();
            File output = selected.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? selected
                : new File(selected.getParentFile(), selected.getName() + ".zip");
            try
            {
                validateDestination(data.toPath(), output.toPath());
                return output;
            }
            catch (IOException failure)
            {
                JOptionPane.showMessageDialog(null, Swing.dialogMessage(failure.getMessage()),
                    "Choose another destination", JOptionPane.WARNING_MESSAGE);
            }
        }
        return null;
    }

    static void validateDestination(Path data, Path output) throws IOException
    {
        Path source = data.toFile().getCanonicalFile().toPath();
        Path destination = output.toFile().getCanonicalFile().toPath();
        if (destination.startsWith(source))
            throw new IOException("Choose a destination outside Scarlet's data folder.");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("That filename already exists. Choose a new filename to preserve your previous export.");
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("Choose an existing destination folder outside Scarlet's data folder.");
    }

    private static PinChoice choosePin()
    {
        JPasswordField first = new JPasswordField(24), second = new JPasswordField(24);
        JCheckBox moving = new JCheckBox("I'm moving to another computer");
        JPanel panel = new JPanel(new GridLayout(0, 1, 5, 6));
        panel.add(new JLabel("Scarlet is stopped. Keep the export PIN for restoration."));
        panel.add(new JLabel("PIN (at least 4 characters)"));
        panel.add(first);
        panel.add(new JLabel("Confirm PIN"));
        panel.add(second);
        panel.add(moving);
        first.addAncestorListener(new javax.swing.event.AncestorListener()
        {
            public void ancestorAdded(javax.swing.event.AncestorEvent e) { first.requestFocusInWindow(); }
            public void ancestorRemoved(javax.swing.event.AncestorEvent e) { }
            public void ancestorMoved(javax.swing.event.AncestorEvent e) { }
        });
        try
        {
            while (true)
            {
                if (JOptionPane.showConfirmDialog(null, panel, "Protect your export",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
                    return null;
                char[] entered = first.getPassword(), confirmation = second.getPassword();
                boolean accepted = false;
                try
                {
                    accepted = entered.length >= 4 && Arrays.equals(entered, confirmation);
                    if (accepted)
                        return new PinChoice(entered, moving.isSelected());
                    JOptionPane.showMessageDialog(null, "Enter matching PINs of at least 4 characters.",
                        "Check the PIN", JOptionPane.WARNING_MESSAGE);
                }
                finally
                {
                    Arrays.fill(confirmation, '\0');
                    if (!accepted) Arrays.fill(entered, '\0');
                }
            }
        }
        finally
        {
            first.setText("");
            second.setText("");
        }
    }

    private static boolean confirmWipe(File output)
    {
        JButton keep = new JButton("Keep local data"), remove = new JButton("Remove all local data — 10");
        remove.setEnabled(false);
        JOptionPane pane = new JOptionPane(Swing.dialogMessage("The export passed authenticated decryption and archive verification:\n"
            + output.getAbsolutePath() + "\n\nRemove this computer's entire Scarlet data folder and stored credentials? "
            + "This is separate from optional log/cache cleanup. Keep the export and its PIN before continuing."),
            JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[] { keep, remove }, keep);
        JDialog dialog = pane.createDialog(null, "Remove this computer's Scarlet data");
        AtomicBoolean confirmed = new AtomicBoolean();
        long start = System.nanoTime();
        keep.addActionListener(e -> dialog.dispose());
        remove.addActionListener(e ->
        {
            if (wipeDelayElapsed(start, System.nanoTime()))
            {
                confirmed.set(true);
                dialog.dispose();
            }
        });
        Timer timer = new Timer(200, e ->
        {
            long remaining = Math.max(0L, WIPE_DELAY_NANOS - (System.nanoTime() - start));
            if (remaining == 0L)
            {
                remove.setText("Remove all local data");
                remove.setEnabled(true);
                remove.setForeground(new Color(226, 100, 104));
            }
            else remove.setText("Remove all local data — " + ((remaining + 999_999_999L) / 1_000_000_000L));
        });
        timer.start();
        try
        {
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setVisible(true);
            return confirmed.get();
        }
        finally { timer.stop(); dialog.dispose(); }
    }

    static boolean wipeDelayElapsed(long start, long now)
    {
        return now - start >= WIPE_DELAY_NANOS;
    }

    static long cleanupSize(Path data) throws IOException
    {
        long[] bytes = { 0L };
        for (String name : new String[] { "logs", "caches", "tts" })
        {
            Path root = data.resolve(name);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
            Files.walkFileTree(root, new SimpleFileVisitor<Path>()
            {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                {
                    if (attributes.isRegularFile()) bytes[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return bytes[0];
    }

    static CleanupResult cleanup(Path data) throws IOException
    {
        Path root = data.toRealPath();
        CleanupResult result = new CleanupResult();
        for (String name : new String[] { "caches", "tts" })
        {
            Path directory = root.resolve(name);
            if (Files.isSymbolicLink(directory)) { result.failures++; continue; }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>()
            {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                {
                    deleteCleanupItem(file, attributes.isRegularFile() ? attributes.size() : 0L, result);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure)
                { result.failures++; return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException failure)
                {
                    if (!path.equals(directory)) deleteCleanupItem(path, 0L, result);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Path logs = root.resolve("logs");
        if (Files.isSymbolicLink(logs)) { result.failures++; return result; }
        if (Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS))
        {
            List<Path> oldLogs = new ArrayList<>();
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(logs))
            {
                for (Path file : entries)
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && file.getFileName().toString().matches("scarlet_log_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.txt"))
                        oldLogs.add(file);
            }
            oldLogs.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
            for (int i = 1; i < oldLogs.size(); i++)
                deleteCleanupItem(oldLogs.get(i), Files.size(oldLogs.get(i)), result);
        }
        return result;
    }

    private static void deleteCleanupItem(Path path, long bytes, CleanupResult result)
    {
        try { if (Files.deleteIfExists(path)) result.bytes += bytes; }
        catch (IOException failure) { result.failures++; }
    }

    private static void show(String title, String message, int kind)
    {
        Swing.invokeWait(() -> JOptionPane.showMessageDialog(null, Swing.dialogMessage(message), title, kind));
    }

    private static String humanBytes(long bytes)
    {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = { "KiB", "MiB", "GiB", "TiB" };
        int unit = -1;
        do { value /= 1024.0; unit++; } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static final class PinChoice
    {
        final char[] pin;
        final boolean moveOut;
        PinChoice(char[] pin, boolean moveOut) { this.pin = pin; this.moveOut = moveOut; }
    }

    static final class CleanupResult
    {
        long bytes;
        int failures;
    }

    private static final class ProgressWindow
    {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final JDialog dialog = new JDialog((Frame) null, "Exporting — Scarlet logging is stopped", false);
        final JLabel phase = new JLabel("Preparing export"), detail = new JLabel(" "), stats = new JLabel(" ");
        final JProgressBar bar = new JProgressBar(0, 1000);
        long lastUpdate;
        String lastOperation;

        ProgressWindow()
        {
            JPanel labels = new JPanel(new GridLayout(0, 1, 4, 8));
            detail.putClientProperty("html.disable", Boolean.TRUE);
            labels.add(new JLabel("Scarlet is stopped. Its data and credentials are locked for this export."));
            labels.add(phase); labels.add(detail); labels.add(bar); labels.add(stats);
            JButton cancel = new JButton("Cancel export");
            Runnable requestCancel = () ->
            {
                cancelled.set(true); cancel.setEnabled(false); cancel.setText("Cancelling…");
            };
            cancel.addActionListener(e -> requestCancel.run());
            dialog.addWindowListener(new WindowAdapter()
            {
                @Override public void windowClosing(WindowEvent e) { requestCancel.run(); }
            });
            JPanel panel = new JPanel(new BorderLayout(8, 12));
            panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
            panel.add(labels, BorderLayout.CENTER); panel.add(cancel, BorderLayout.SOUTH);
            dialog.setContentPane(panel); dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            dialog.setSize(600, 260); dialog.setLocationRelativeTo(null); dialog.setVisible(true);
        }

        boolean update(String operation, String currentFile, int done, int total, long bytes, long size)
        {
            if (cancelled.get()) return false;
            long now = System.nanoTime();
            boolean complete = (size > 0L && bytes >= size) || (total > 0 && done >= total);
            if (java.util.Objects.equals(operation, lastOperation) && !complete
                && now - lastUpdate < TimeUnit.MILLISECONDS.toNanos(100)) return !cancelled.get();
            lastUpdate = now;
            lastOperation = operation;
            SwingUtilities.invokeLater(() ->
            {
                phase.setText(operation + (total > 0 ? " — " + done + " / " + total + " files" : ""));
                String text = currentFile == null ? " " : currentFile;
                detail.setText(text.length() > 85 ? "…" + text.substring(text.length() - 84) : text);
                detail.setToolTipText(text);
                bar.setIndeterminate(size <= 0L);
                if (size > 0L) bar.setValue((int) Math.min(1000.0, bytes * 1000.0 / size));
                stats.setText(size > 0L ? humanBytes(bytes) + " / " + humanBytes(size) : " ");
            });
            return !cancelled.get();
        }
    }
}

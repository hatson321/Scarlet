package net.sybyline.scarlet;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;
import net.sybyline.scarlet.ui.Swing;

/** Dispatches migration tools without constructing the live logger. */
final class ScarletOfflineTools
{
    private ScarletOfflineTools() { }

    static boolean isOfflineCommand(String[] args)
    {
        return args != null && args.length > 0 && ("--restore".equals(args[0])
            || "--export".equals(args[0]) || "--import-bundle".equals(args[0]));
    }

    static void run(String[] args) throws IOException
    {
        if (!isOfflineCommand(args)) throw new IOException("Unknown offline operation");
        if ("--import-bundle".equals(args[0]))
        {
            ScarletOfflineImport.run(args);
            return;
        }
        if (args.length == 3 && "--after-process".equals(args[1]))
        {
            final long pid;
            try { pid = Long.parseLong(args[2]); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid previous Scarlet process ID", invalid); }
            waitForExit(pid, 60_000L);
        }
        else if (args.length != 1) throw new IOException("Unexpected offline operation arguments");
        if (GraphicsEnvironment.isHeadless())
            throw new IOException("The restore/export chooser needs a desktop. For console import, use --import-bundle <bundle.zip>.");
        if ("--restore".equals(args[0])) ScarletStartupRestore.restoreFromChooser();
        else ScarletOfflineExport.run();
    }

    static void waitForExit(long pid, long timeoutMillis) throws IOException
    {
        if (pid <= 0 || pid == ProcessHandle.current().pid())
            throw new IOException("Invalid previous Scarlet process ID");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
        {
            if (System.nanoTime() >= deadline)
                throw new IOException("The previous Scarlet process has not closed. No export or restore was started.");
            try { Thread.sleep(100L); }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Scarlet to close", interrupted);
            }
        }
    }

    static void showFailure(Exception failure)
    {
        String message = "Scarlet's offline operation could not complete.\n" + failure.getMessage()
            + "\n\nDo not delete your existing backup. Close other Scarlet instances before retrying.";
        System.err.println(message);
        if (!GraphicsEnvironment.isHeadless())
            JOptionPane.showMessageDialog(null, Swing.dialogMessage(message), "Scarlet restore/export", JOptionPane.ERROR_MESSAGE);
    }
}

package net.sybyline.scarlet;

import java.awt.GraphicsEnvironment;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import net.sybyline.scarlet.ui.Swing;

/** Offline migration entry point: never constructs Scarlet or starts logging workers. */
public final class ScarletOfflineImport
{
    private ScarletOfflineImport() { }

    public static void run(String[] args) throws IOException
    {
        if (args == null || args.length < 2 || !"--import-bundle".equals(args[0]))
            throw new IOException("Usage: --import-bundle <bundle.zip> [--data-only|--credentials-only] [--clear-channels]");
        boolean data = true, credentials = true, channels = true;
        for (int i = 2; i < args.length; i++)
        {
            if ("--data-only".equals(args[i])) credentials = false;
            else if ("--credentials-only".equals(args[i])) data = false;
            else if ("--clear-channels".equals(args[i])) channels = false;
            else throw new IOException("Unknown import option: " + args[i]);
        }
        if (!data && !credentials) throw new IOException("Choose data-only OR credentials-only, not both");
        File bundle = new File(args[1]);
        restore(bundle, new ScarletMigration.ImportOptions(data, credentials, channels));
    }

    static boolean restore(File bundle, ScarletMigration.ImportOptions options) throws IOException
    {
        Console console = System.console();
        String warning = "This offline import replaces selected Scarlet configuration. Close all Scarlet versions first.\n"
            + "A pre-import backup will be created. Restart Scarlet after import.\n"
            + "Destination: " + Scarlet.dir.getAbsolutePath() + "\n"
            + "Data files: " + options.importDataFiles + "; credentials: " + options.importCredentials
            + "; keep channel routes: " + options.keepChannels;
        boolean confirmed;
        if (GraphicsEnvironment.isHeadless())
        {
            if (console == null) throw new IOException("Offline import requires an interactive console or desktop confirmation");
            confirmed = "IMPORT".equals(console.readLine("%s%nType IMPORT to continue: ", warning));
        }
        else confirmed = JOptionPane.showConfirmDialog(null, Swing.dialogMessage(warning), "Offline Scarlet import",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
        if (!confirmed) return false;
        char[] pin = null;
        try
        {
            if (ScarletMigration.isEncryptedBundle(bundle))
            {
                if (GraphicsEnvironment.isHeadless()) pin = console.readPassword("Bundle PIN: ");
                else
                {
                    JPasswordField field = new JPasswordField(24);
                    if (JOptionPane.showConfirmDialog(null, field, "Bundle PIN", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return false;
                    pin = field.getPassword();
                }
                if (pin == null || pin.length == 0) throw new IOException("A bundle PIN is required");
            }
            ScarletMigration.ImportResult result = ScarletMigration.importBundle(bundle,
                options, pin);
            String message = "Import complete: " + result.summary + "\nBackup: " + result.backupFile + "\nRestart Scarlet to load imported state.";
            if (GraphicsEnvironment.isHeadless()) console.printf("%s%n", message);
            else JOptionPane.showMessageDialog(null, Swing.dialogMessage(message), "Offline import complete", JOptionPane.INFORMATION_MESSAGE);
            return true;
        }
        finally { if (pin != null) Arrays.fill(pin, '\0'); }
    }
}

package net.sybyline.scarlet;

import java.io.File;
import java.io.IOException;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.sybyline.scarlet.ui.Swing;

/** First-run restore is offered before any login, worker or credential-store initialization. */
final class ScarletStartupRestore
{
    private ScarletStartupRestore() { }

    enum Choice { RESTORE, SETUP, CLOSE }

    interface Interaction
    {
        Choice choose();
        File chooseBundle();
        boolean restore(File bundle) throws IOException;
        void failed(IOException failure);
    }

    static boolean shouldOffer(File home, boolean headless)
    {
        return !headless && !Scarlet.settingsFileUsable(home);
    }

    /** Only an explicit setup choice permits normal initialization. */
    static boolean offer(Interaction interaction)
    {
        while (true)
        {
            Choice choice = interaction.choose();
            if (choice == Choice.SETUP) return true;
            if (choice != Choice.RESTORE) return false;
            File file = interaction.chooseBundle();
            if (file == null) continue;
            try { if (interaction.restore(file)) return false; }
            catch (IOException failure) { interaction.failed(failure); }
        }
    }

    static boolean offer()
    {
        return offer(desktop());
    }

    static void restoreFromChooser()
    {
        Interaction interaction = desktop();
        while (true)
        {
            File file = interaction.chooseBundle();
            if (file == null) return;
            try { if (interaction.restore(file)) return; }
            catch (IOException failure) { interaction.failed(failure); }
        }
    }

    private static Interaction desktop()
    {
        return new Interaction()
        {
            public Choice choose()
            {
                String message = "Welcome to Scarlet Logger " + Scarlet.VERSION + ".\n\n"
                    + "No usable settings were found in:\n" + Scarlet.dir.getAbsolutePath() + "\n\n"
                    + "Restore a Scarlet export or automatic pre-import backup before signing in.\n"
                    + "You only need the export PIN if the bundle is encrypted.\n"
                    + "After restoration, launch Scarlet normally to use the restored settings.\n\n"
                    + "Continue setup starts the usual setup without deleting existing files.";
                Object[] buttons = { "Restore from export / backup", "Continue setup", "Close" };
                int selected = JOptionPane.showOptionDialog(null, Swing.dialogMessage(message),
                    "Welcome to Scarlet Logger", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, buttons, buttons[0]);
                return selected == 0 ? Choice.RESTORE : selected == 1 ? Choice.SETUP : Choice.CLOSE;
            }

            public File chooseBundle()
            {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Restore a Scarlet export or pre-import backup ZIP");
                chooser.setFileFilter(new FileNameExtensionFilter("Scarlet export / pre-import backup (*.zip)", "zip"));
                chooser.setAcceptAllFileFilterUsed(false);
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
            }

            public boolean restore(File bundle) throws IOException
            {
                return ScarletOfflineImport.restore(bundle, ScarletMigration.ImportOptions.all());
            }

            public void failed(IOException failure)
            {
                ScarletOfflineTools.showFailure(failure);
            }
        };
    }
}

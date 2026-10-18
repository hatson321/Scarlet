package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.sybyline.scarlet.ScarletStartupRestore.Choice;
import net.sybyline.scarlet.util.MiscUtils;

class ScarletStartupRestoreTest
{
    @TempDir Path temp;

    @Test void freshAndDamagedSettingsOfferRestoreButHeadlessAndConfiguredHomesDoNot() throws Exception
    {
        Path home = temp.resolve("new-home");
        assertTrue(ScarletStartupRestore.shouldOffer(home.toFile(), false));
        assertFalse(Files.exists(home), "Detecting a fresh home must not initialize it");
        assertFalse(ScarletStartupRestore.shouldOffer(home.toFile(), true));
        Files.createDirectories(home);
        Files.write(home.resolve("settings.json"), "broken".getBytes("UTF-8"));
        assertTrue(ScarletStartupRestore.shouldOffer(home.toFile(), false));
        Files.write(home.resolve("settings.json"), "{}".getBytes("UTF-8"));
        assertTrue(ScarletStartupRestore.shouldOffer(home.toFile(), false));
        Files.write(home.resolve("settings.json"), "{\"configured\":true}".getBytes("UTF-8"));
        assertFalse(ScarletStartupRestore.shouldOffer(home.toFile(), false));
    }

    @Test void onlyExplicitSetupStartsNormalLogger()
    {
        assertTrue(ScarletStartupRestore.offer(new Fake(Choice.SETUP)));
        assertFalse(ScarletStartupRestore.offer(new Fake(Choice.CLOSE)));
    }

    @Test void successfulRestoreExitsBeforeNormalInitialization()
    {
        Fake ui = new Fake(Choice.RESTORE);
        ui.bundle = new File("synthetic.zip");
        ui.success = true;
        assertFalse(ScarletStartupRestore.offer(ui));
        assertEquals(1, ui.restores);
        assertEquals(0, ui.failures);
    }

    @Test void cancellingChooserDoesNotRestoreOrFallThroughToLogin()
    {
        Fake ui = new Fake(Choice.RESTORE, Choice.CLOSE);
        assertFalse(ScarletStartupRestore.offer(ui));
        assertEquals(0, ui.restores);
    }

    @Test void cancellingConfirmationOrPinReturnsToWelcome()
    {
        Fake ui = new Fake(Choice.RESTORE, Choice.CLOSE);
        ui.bundle = new File("synthetic.zip");
        assertFalse(ScarletStartupRestore.offer(ui));
        assertEquals(1, ui.restores);
    }

    @Test void failedRestoreStaysBeforeLoginAndReportsFailure()
    {
        Fake ui = new Fake(Choice.RESTORE, Choice.CLOSE);
        ui.bundle = new File("synthetic.zip");
        ui.fail = true;
        assertFalse(ScarletStartupRestore.offer(ui));
        assertEquals(1, ui.failures);
    }

    @Test void offlineCommandRecognitionIsExplicit()
    {
        for (String mode : Arrays.asList("--restore", "--export", "--import-bundle"))
            assertTrue(ScarletOfflineTools.isOfflineCommand(new String[] { mode }));
        assertFalse(ScarletOfflineTools.isOfflineCommand(null));
        assertFalse(ScarletOfflineTools.isOfflineCommand(new String[0]));
        assertFalse(ScarletOfflineTools.isOfflineCommand(new String[] { "--something-else" }));
    }

    @Test void childCommandPinsHomeAndWaitsForParentInsteadOfReusingNormalArguments()
    {
        List<String> command = Scarlet.buildOfflineCommand("--export");
        assertEquals("--export", command.get(command.size() - 3));
        assertEquals("--after-process", command.get(command.size() - 2));
        assertEquals(Long.toString(ProcessHandle.current().pid()), command.get(command.size() - 1));
        assertTrue(command.contains("-DSCARLET_HOME=" + Scarlet.dir.getAbsolutePath()));
        assertTrue(command.contains("-Dscarlet.offline=true"));
        assertThrows(IllegalArgumentException.class, () -> Scarlet.buildOfflineCommand("--unknown"));
    }

    @Test void refusingAnInvalidOrStillRunningParentCannotProceed() throws Exception
    {
        assertThrows(IOException.class, () -> ScarletOfflineTools.waitForExit(0, 0));
        assertThrows(IOException.class, () -> ScarletOfflineTools.waitForExit(ProcessHandle.current().pid(), 0));
        long parent = ProcessHandle.current().parent().get().pid();
        assertThrows(IOException.class, () -> ScarletOfflineTools.waitForExit(parent, 0));
    }

    @Test void iterationVersionIsAcceptedAndOrdersAfterPriorScarletBuild()
    {
        assertTrue(MiscUtils.isValidVersion("0.5.0-a1"));
        assertTrue(MiscUtils.compareScarletVersion("0.5.0-a1", "0.5.0") > 0);
    }

    private static final class Fake implements ScarletStartupRestore.Interaction
    {
        final ArrayDeque<Choice> choices;
        File bundle;
        boolean success, fail;
        int restores, failures;
        Fake(Choice... choices) { this.choices = new ArrayDeque<>(Arrays.asList(choices)); }
        public Choice choose() { return this.choices.remove(); }
        public File chooseBundle() { return this.bundle; }
        public boolean restore(File bundle) throws IOException
        {
            this.restores++;
            if (this.fail) throw new IOException("Synthetic rejected bundle");
            return this.success;
        }
        public void failed(IOException failure) { this.failures++; }
    }
}

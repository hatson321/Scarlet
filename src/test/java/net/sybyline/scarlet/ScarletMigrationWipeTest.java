package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletMigrationWipeTest
{
    @TempDir Path directory;

    @Test void deletesOnlyTheExactSyntheticRootAndItsRegularChildren() throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Files.createDirectory(root.resolve("nested"));
        Files.writeString(root.resolve("nested/file.txt"), "synthetic data");
        Path outside = directory.resolve("outside.txt");
        Files.writeString(outside, "must remain");
        List<String> failures = new ArrayList<>();
        int[] removed = { 0 };
        ScarletMigration.deleteDataDirectory(root.toFile(), removed, failures);
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(3, removed[0]);
        assertFalse(Files.exists(root));
        assertEquals("must remain", Files.readString(outside));
    }

    @Test void refusesFilesystemRootBeforeAnyDeletion() throws Exception
    {
        assertThrows(IOException.class, () -> ScarletMigration.deleteDataDirectory(
            directory.toAbsolutePath().getRoot().toFile(), new int[1], new ArrayList<>()));
    }

    @Test void misplacedDataHomeCannotSelectTheUserHomeOrItsAncestors() throws Exception
    {
        Path home = Files.createDirectories(directory.resolve("synthetic-users/owner"));
        Path sentinel = home.resolve("personal-file.txt");
        Files.writeString(sentinel, "must remain");
        for (Path broad : new Path[] { home, home.resolve("child/.."), home.getParent(), directory })
            assertThrows(IOException.class, () -> ScarletMigration.checkedWipeRoot(broad.toFile(), home, Map.of(), null));
        assertEquals("must remain", Files.readString(sentinel));
        assertThrows(IOException.class, () -> ScarletMigration.checkedWipeRoot(home.resolve("scarlet").toFile(), null, Map.of(), null));
    }

    @Test void commonUserAndRedirectedSystemDirectoriesCannotBeRemovalRoots() throws Exception
    {
        Path home = directory.resolve("synthetic-user");
        for (String name : new String[] { "Documents", "Downloads", "Desktop", "Pictures", "AppData",
                "AppData/Local", "AppData/Roaming", ".config", ".local/share", ".java/.userPrefs" })
            assertThrows(IOException.class, () -> ScarletMigration.checkedWipeRoot(home.resolve(name).toFile(), home, Map.of(), null));
        for (String variable : new String[] { "LOCALAPPDATA", "ProgramData", "ProgramFiles", "SystemRoot", "PUBLIC",
                "XDG_DATA_HOME", "XDG_CONFIG_HOME", "TMPDIR" })
        {
            Path redirected = directory.resolve("redirected-" + variable);
            assertThrows(IOException.class, () -> ScarletMigration.checkedWipeRoot(redirected.toFile(), home,
                Map.of(variable, redirected.toString()), null));
        }
        Path temporary = directory.resolve("shared-temp");
        assertThrows(IOException.class, () -> ScarletMigration.checkedWipeRoot(temporary.toFile(), home, Map.of(), temporary));
    }

    @Test void commonFilesystemDirectoriesAndOtherProfileRootsAreRejectedLexically() throws Exception
    {
        Path home = directory.resolve("synthetic-user"), filesystem = directory.toAbsolutePath().getRoot();
        for (String name : new String[] { "home", "home/other-user", "Users", "Users/other-user", "usr", "usr/local",
                "etc", "tmp", "var/lib", "Windows", "Windows/System32", "Program Files", "ProgramData" })
            assertThrows(IOException.class, () -> ScarletMigration.checkedWipeRoot(filesystem.resolve(name).toFile(), home, Map.of(), null));
    }

    @Test void dedicatedScarletDirectoriesWithinProtectedParentsRemainAllowed() throws Exception
    {
        Path home = Files.createDirectory(directory.resolve("synthetic-user"));
        Path redirected = Files.createDirectory(directory.resolve("redirected-appdata"));
        for (Path dedicated : new Path[] { home.resolve("Scarlet"), home.resolve(".local/share/scarlet"),
                home.resolve("AppData/Local/Hatson321/Scarlet Logger"), redirected.resolve("Scarlet") })
        {
            Files.createDirectories(dedicated);
            assertEquals(dedicated.toAbsolutePath().normalize(), ScarletMigration.checkedWipeRoot(dedicated.toFile(), home,
                Map.of("LOCALAPPDATA", redirected.toString()), directory.resolve("shared-temp")));
        }
    }

    @Test void externalSymlinkTargetIsNeverRemoved() throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path external = Files.createDirectory(directory.resolve("outside"));
        Path sentinel = external.resolve("sentinel.txt");
        Files.writeString(sentinel, "must remain");
        Path link = root.resolve("linked-directory");
        createSymlinkOrSkip(link, external);
        try
        {
            List<String> failures = new ArrayList<>();
            ScarletMigration.deleteDataDirectory(root.toFile(), new int[1], failures);
            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals("must remain", Files.readString(sentinel));
            assertFalse(Files.exists(root));
        }
        finally { Files.deleteIfExists(link); }
    }

    @Test void linkedRootIsRefusedRatherThanDeletingItsTarget() throws Exception
    {
        Path actual = Files.createDirectory(directory.resolve("actual-data"));
        Path sentinel = actual.resolve("sentinel.txt");
        Files.writeString(sentinel, "must remain");
        Path alias = directory.resolve("root-alias");
        createSymlinkOrSkip(alias, actual);
        try
        {
            assertThrows(IOException.class, () -> ScarletMigration.deleteDataDirectory(alias.toFile(), new int[1], new ArrayList<>()));
            assertEquals("must remain", Files.readString(sentinel));
        }
        finally { Files.deleteIfExists(alias); }
    }

    @Test void externalDirectoryAliasAndInternalCycleAreNeverTraversed() throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path external = Files.createDirectory(directory.resolve("outside"));
        Path sentinel = external.resolve("sentinel.txt");
        Files.writeString(sentinel, "must remain");
        Path outsideLink = root.resolve("outside-junction"), cycle = root.resolve("cycle-junction");
        createDirectoryAliasOrSkip(outsideLink, external);
        try
        {
            createDirectoryAliasOrSkip(cycle, root);
            List<String> failures = new ArrayList<>();
            ScarletMigration.deleteDataDirectory(root.toFile(), new int[1], failures);
            assertEquals("must remain", Files.readString(sentinel));
            // Refusing the junctions may leave the root and is explicitly reported as incomplete.
            assertTrue(!Files.exists(root) || !failures.isEmpty());
        }
        finally
        {
            Files.deleteIfExists(cycle);
            Files.deleteIfExists(outsideLink);
        }
    }

    @Test void directoryAliasRootIsRefusedBeforeDeletion() throws Exception
    {
        Path actual = Files.createDirectory(directory.resolve("actual-data"));
        Path sentinel = actual.resolve("sentinel.txt");
        Files.writeString(sentinel, "must remain");
        Path alias = directory.resolve("junction-root");
        createDirectoryAliasOrSkip(alias, actual);
        try
        {
            assertThrows(IOException.class, () -> ScarletMigration.deleteDataDirectory(alias.toFile(), new int[1], new ArrayList<>()));
            assertEquals("must remain", Files.readString(sentinel));
        }
        finally { Files.deleteIfExists(alias); }
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws IOException
    {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException failure)
        { assumeTrue(false, "Synthetic symbolic links unavailable: " + failure.getClass().getSimpleName()); }
    }

    private static void createJunctionOrSkip(Path link, Path target) throws Exception
    {
        Process command = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true).start();
        boolean finished = command.waitFor(10L, TimeUnit.SECONDS);
        if (!finished) command.destroyForcibly();
        assumeTrue(finished && command.exitValue() == 0, "Synthetic directory junctions unavailable on this filesystem");
    }

    private static void createDirectoryAliasOrSkip(Path link, Path target) throws Exception
    {
        if (System.getProperty("os.name", "").startsWith("Windows")) createJunctionOrSkip(link, target);
        else createSymlinkOrSkip(link, target);
    }
}

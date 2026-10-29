package net.sybyline.scarlet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Fail-closed boundaries shared by the two cache cleanup entry points. */
final class ScarletCleanupPaths
{
    private ScarletCleanupPaths() { }

    static Path checkedRoot(Path data) throws IOException
    {
        Path root = data.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root) || !root.equals(root.toRealPath()))
            throw new IOException("Cleanup requires an exact data directory, not a linked or junction alias");
        return root;
    }

    static boolean exact(Path root, Path entry)
    {
        try
        {
            Path path = entry.toAbsolutePath().normalize();
            // Recheck the root too: it may have been replaced since collection.
            return path.startsWith(root) && root.equals(root.toRealPath())
                && !Files.isSymbolicLink(path) && path.equals(path.toRealPath());
        }
        catch (IOException | SecurityException unavailable) { return false; }
    }

    static boolean directory(Path root, Path entry)
    {
        return exact(root, entry) && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
    }

    static boolean regularFile(Path root, Path entry)
    {
        return exact(root, entry) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS);
    }
}

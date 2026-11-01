package net.sybyline.scarlet.util.tts;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/** Resolves installed TTS tools without running a shell or the candidate executable. */
public final class LinuxTtsExecutables
{
    private LinuxTtsExecutables() { }

    /** Keeps PATH precedence, then checks Linuxbrew's standard Linux installation. */
    public static String find(String command)
    {
        try
        {
            String searchPath = System.getenv("PATH");
            if (searchPath == null)
                searchPath = "";
            if ("Linux".equalsIgnoreCase(System.getProperty("os.name", "")))
                return find(command, searchPath, Paths.get("/home/linuxbrew/.linuxbrew/bin"));
            return find(command, searchPath);
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    static String find(String command, String searchPath, Path... fallbackDirectories)
    {
        if (command == null || !command.matches("[A-Za-z0-9_.+-]+")
            || ".".equals(command) || "..".equals(command) || searchPath == null)
            return null;
        for (String directory : searchPath.split(Pattern.quote(File.pathSeparator), -1))
        {
            try
            {
                if (directory.isEmpty())
                    continue;
                String executable = findIn(Paths.get(directory), command);
                if (executable != null)
                    return executable;
            }
            catch (RuntimeException ignored)
            {
                // A malformed or inaccessible PATH entry must not hide later valid tools.
            }
        }
        if (fallbackDirectories != null)
        {
            for (Path directory : fallbackDirectories)
            {
                String executable = findIn(directory, command);
                if (executable != null)
                    return executable;
            }
        }
        return null;
    }

    private static String findIn(Path directory, String command)
    {
        try
        {
            // Do not pick up commands from the process's current working directory.
            if (directory == null || !directory.isAbsolute())
                return null;
            Path candidate = directory.resolve(command).normalize();
            return Files.isRegularFile(candidate) && Files.isExecutable(candidate)
                ? candidate.toString() : null;
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }
}

package net.sybyline.scarlet.util.tts;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxTtsExecutablesTest
{
    @TempDir Path temporary;

    private Path executable(Path directory, String name) throws Exception
    {
        Files.createDirectories(directory);
        Path result = Files.write(directory.resolve(name),
            "not an actual executable: discovery must not run this file".getBytes(StandardCharsets.UTF_8));
        if (Files.getFileStore(result).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(result, PosixFilePermissions.fromString("rwx------"));
        assertTrue(Files.isExecutable(result), "Test fixture must pass the platform's executable check");
        return result.toAbsolutePath().normalize();
    }

    @Test void keepsPathOrderAndPrefersPathOverFallback() throws Exception
    {
        Path first = executable(temporary.resolve("first"), "espeak-ng");
        Path second = executable(temporary.resolve("second"), "espeak-ng");
        Path fallback = executable(temporary.resolve("brew"), "espeak-ng");
        String searchPath = first.getParent() + File.pathSeparator + second.getParent();
        assertEquals(first.toString(), LinuxTtsExecutables.find("espeak-ng", searchPath, fallback.getParent()));
        assertEquals(second.toString(), LinuxTtsExecutables.find("espeak-ng", second.getParent().toString(), fallback.getParent()));
    }

    @Test void resolvesFallbackWithSpacesAndQuotesToAnAbsolutePath() throws Exception
    {
        Path engine = executable(temporary.resolve("user's tools with spaces"), "espeak-ng");
        String found = LinuxTtsExecutables.find("espeak-ng", "", temporary.resolve("missing"), engine.getParent());
        assertEquals(engine.toString(), found);
        assertTrue(Paths.get(found).isAbsolute());
    }

    @Test void malformedPathEntriesDoNotHideLaterValidExecutables() throws Exception
    {
        Path engine = executable(temporary.resolve("bin"), "flite");
        String searchPath = "invalid" + (char)0 + "entry" + File.pathSeparator + engine.getParent();
        assertEquals(engine.toString(), LinuxTtsExecutables.find("flite", searchPath));
    }

    @Test void rejectsRelativeSearchEntriesEvenIfTheyResolveToAnExecutable() throws Exception
    {
        Path engine = executable(temporary.resolve("bin"), "flite");
        Path working = Paths.get("").toAbsolutePath();
        if (working.getRoot().equals(engine.getRoot()))
        {
            Path relativeDirectory = working.relativize(engine.getParent());
            assertFalse(relativeDirectory.isAbsolute());
            assertNull(LinuxTtsExecutables.find("flite", relativeDirectory.toString()));
            assertNull(LinuxTtsExecutables.find("flite", "", relativeDirectory));
        }
        assertNull(LinuxTtsExecutables.find("unlikely-scarlet-test-engine", File.pathSeparator + "." + File.pathSeparator));
    }

    @Test void ignoresDirectoriesAndNonExecutableFiles() throws Exception
    {
        Files.createDirectory(temporary.resolve("directory-engine"));
        assertNull(LinuxTtsExecutables.find("directory-engine", temporary.toString(), temporary));
        Path engine = executable(temporary, "synthetic-engine");
        if (Files.getFileStore(engine).supportsFileAttributeView("posix"))
        {
            Files.setPosixFilePermissions(engine, PosixFilePermissions.fromString("rw-------"));
            assertNull(LinuxTtsExecutables.find("synthetic-engine", temporary.toString(), temporary));
        }
        assertNull(LinuxTtsExecutables.find("missing-engine", temporary.toString(), temporary));
    }

    @Test void rejectsCommandSyntaxAndMissingExplicitPath() throws Exception
    {
        Path engine = executable(temporary, "brew");
        for (String command : Arrays.asList(null, "", ".", "..", "../brew", "sub/brew", "sub\\brew",
            "brew;id", "brew --version", "$(brew)", "brew\n", engine.toString()))
            assertNull(LinuxTtsExecutables.find(command, temporary.toString(), temporary), String.valueOf(command));
        assertNull(LinuxTtsExecutables.find("brew", null, temporary));
        assertFalse(LinuxCommandTtsProvider.isCommandAvailable("brew", null));
    }

    @Test void explicitAvailabilityDoesNotAddAnImplicitFallback() throws Exception
    {
        executable(temporary, "espeak-ng");
        assertTrue(LinuxCommandTtsProvider.isCommandAvailable("espeak-ng", temporary.toString()));
        assertFalse(LinuxCommandTtsProvider.isCommandAvailable("espeak-ng", ""));
    }

    @Test void parsesEspeakVoiceLanguagesWithoutAShellOrHeaderTokens()
    {
        assertEquals(Arrays.asList("en", "en-us", "zh-cmn"), LinuxCommandTtsProvider.parseEspeakVoices(Arrays.asList(
            "Pty Language Age/Gender VoiceName File Other Languages",
            " 5 en --/M English gmw/en (en-gb 5)",
            " 2 en-us --/M English_US gmw/en-US",
            " 5 zh-cmn --/M Chinese sit/cmn",
            " 7 en --/M AnotherEnglish variant",
            "error opening voice data", "", null, " 5 bad/token")));
        assertEquals(Collections.emptyList(), LinuxCommandTtsProvider.parseEspeakVoices(Collections.emptyList()));
    }
}

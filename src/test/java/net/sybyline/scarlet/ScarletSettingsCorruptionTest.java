package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletSettingsCorruptionTest
{
    @TempDir Path directory;

    @Test void badExistingSettingsNeverBecomeAnEmptyWritableConfig() throws Exception
    {
        for (String invalid : new String[] { "", " ", "null", "[]", "{\"saved\":\"keep\"" })
        {
            Path folder = Files.createTempDirectory(directory, "invalid-");
            Path file = folder.resolve("settings.json");
            Files.writeString(file, invalid);
            ScarletSettings settings = new ScarletSettings(null, file.toFile(), false);
            for (int attempt = 0; attempt < 12; attempt++)
            {
                assertThrows(UncheckedIOException.class, () -> settings.setString("replacement", "must-not-save"));
                assertThrows(UncheckedIOException.class, settings::saveJson);
                assertEquals(invalid, Files.readString(file));
            }
            assertFalse(Files.exists(folder.resolve("backups")), "rejected saves must not prune recovery evidence");
        }
    }

    @Test void missingFirstRunFileInitializesNormally() throws Exception
    {
        Path file = directory.resolve("settings.json");
        ScarletSettings settings = new ScarletSettings(null, file.toFile(), false);
        settings.setString("synthetic", "first-run");
        assertEquals("first-run", new ScarletSettings(null, file.toFile(), false).getString("synthetic"));
    }

    @Test void validBackupRecoveryRemainsAvailable() throws Exception
    {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, "null");
        Path backups = Files.createDirectory(directory.resolve("backups"));
        Path good = backups.resolve("settings.json.20260101-000000.bak");
        String original = "{\"synthetic\":\"recovered\",\"preserved\":7}";
        Files.writeString(good, original);
        Files.writeString(backups.resolve("settings.json.20260102-000000.bak"), "{");
        ScarletSettings settings = new ScarletSettings(null, file.toFile(), false);
        assertEquals("recovered", settings.getString("synthetic"));
        settings.setString("new", "value");
        assertEquals(7, new ScarletSettings(null, file.toFile(), false).getObject("preserved", Integer.class));
        assertEquals(original, Files.readString(good));
    }

    @Test void removingFailedFileDoesNotClearTheGuardButAValidRepairDoes() throws Exception
    {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, "{");
        ScarletSettings settings = new ScarletSettings(null, file.toFile(), false);
        assertThrows(UncheckedIOException.class, settings::getJson);
        Files.delete(file);
        assertThrows(UncheckedIOException.class, () -> settings.setString("new", "value"));
        assertFalse(Files.exists(file));
        Files.writeString(file, "{\"repaired\":true}");
        assertTrue(settings.getObject("repaired", Boolean.class));
        settings.setString("new", "value");
        assertTrue(Files.readString(file).contains("repaired"));
    }

    @Test void physicalReadFailureDoesNotReplaceNewerPrimaryWithOldBackup() throws Exception
    {
        Path file = directory.resolve("settings.json");
        String original = "{\"newer\":\"preserve\"}";
        Files.writeString(file, original);
        Path backups = Files.createDirectory(directory.resolve("backups"));
        Files.writeString(backups.resolve("settings.json.20260101-000000.bak"), "{\"old\":true}");
        ScarletSettings settings = new ScarletSettings(null, file.toFile(), false) {
            @Override java.io.Reader openSettingsReader() {
                return new java.io.Reader() {
                    @Override public int read(char[] buffer, int offset, int length) throws java.io.IOException {
                        throw new java.io.IOException("Synthetic read failure");
                    }
                    @Override public void close() { }
                };
            }
        };
        assertThrows(UncheckedIOException.class, () -> settings.setString("replacement", "must-not-save"));
        assertThrows(UncheckedIOException.class, settings::saveJson);
        assertEquals(original, Files.readString(file));
    }

    @Test void nonFileIsNotTreatedAsMissing() throws Exception
    {
        Path file = Files.createDirectory(directory.resolve("settings.json"));
        Path sentinel = file.resolve("synthetic-keep.txt");
        Files.writeString(sentinel, "keep");
        ScarletSettings settings = new ScarletSettings(null, file.toFile(), false);
        assertThrows(UncheckedIOException.class, () -> settings.setString("new", "value"));
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test void failedSaveIsNotReportedAsSuccessAndCanBeRetried() throws Exception
    {
        Path file = directory.resolve("settings.json");
        ScarletSettings settings = new ScarletSettings(null, file.toFile(), false);
        settings.setString("saved", "original");
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(UncheckedIOException.class, () -> settings.setString("saved", "pending"));
        assertTrue(Files.isDirectory(file));
        Files.delete(file);
        settings.saveJson();
        assertEquals("pending", new ScarletSettings(null, file.toFile(), false).getString("saved"));
    }
}

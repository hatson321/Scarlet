package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.sybyline.scarlet.util.ProcLock;

class ScarletExportTransactionTest
{
    @TempDir Path directory;
    private static final String PREFS = "<preferences EXTERNAL_XML_VERSION=\"1.0\"><root type=\"user\"><map/>"
        + "<node name=\"net\"><map/><node name=\"sybyline\"><map/><node name=\"scarlet\"><map/>"
        + "</node></node></node></root></preferences>";
    private static final byte[] ORIGINAL = "original destination must survive".getBytes(StandardCharsets.UTF_8);

    private Path data() throws IOException
    {
        Path data = Files.createDirectories(directory.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "{\"synthetic\":true}");
        Files.writeString(data.resolve("global-prefs.key"), "synthetic-key-not-a-real-credential");
        return data;
    }

    private ScarletMigration.ExportSession open(Path data, AtomicInteger wipe) throws IOException
    {
        return ScarletMigration.openExportSession(data.toFile(), directory.resolve("synthetic-user").toFile(),
            () -> PREFS.getBytes(StandardCharsets.UTF_8), () -> { wipe.incrementAndGet(); return "synthetic wipe"; });
    }

    private void noTemporaryExports() throws IOException
    {
        try (java.util.stream.Stream<Path> paths = Files.list(directory))
        { assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"))); }
    }

    @Test void cancellationNeverChangesAnExistingDestinationAtAnyExportPhase() throws Exception
    {
        Path data = data(), output = directory.resolve("bundle.zip");
        for (String phase : new String[] { "Preparing", "Compressing", "Encrypting", "Publishing" })
        {
            Files.write(output, ORIGINAL);
            try (ScarletMigration.ExportSession session = open(data, new AtomicInteger()))
            {
                char[] pin = "Encrypting".equals(phase) ? new char[] { '1', '2', '3', '4' } : null;
                IOException failure = assertThrows(IOException.class, () -> session.exportBundle(output.toFile(), pin,
                    (current, detail, done, total, bytes, size) -> {
                        try { assertArrayEquals(ORIGINAL, Files.readAllBytes(output)); }
                        catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
                        return !phase.equals(current);
                    }));
                assertEquals(ScarletMigration.CANCELLED_MESSAGE, failure.getMessage());
                assertArrayEquals(ORIGINAL, Files.readAllBytes(output));
            }
            noTemporaryExports();
        }
    }

    @Test void failedPreferencesAndRuntimeCancellationPreserveTheOldDestination() throws Exception
    {
        Path data = data(), output = directory.resolve("bundle.zip");
        Files.write(output, ORIGINAL);
        try (ScarletMigration.ExportSession session = ScarletMigration.openExportSession(data.toFile(),
            directory.resolve("synthetic-user").toFile(), () -> { throw new IOException("synthetic snapshot failure"); }, () -> "not called"))
        {
            assertThrows(IOException.class, () -> session.exportBundle(output.toFile(), null, null));
            assertArrayEquals(ORIGINAL, Files.readAllBytes(output));
        }
        try (ScarletMigration.ExportSession session = open(data, new AtomicInteger()))
        {
            assertThrows(IllegalStateException.class, () -> session.exportBundle(output.toFile(), null,
                (phase, detail, done, total, bytes, size) -> { throw new IllegalStateException("synthetic callback failure"); }));
            assertArrayEquals(ORIGINAL, Files.readAllBytes(output));
        }
        noTemporaryExports();
    }

    @Test void cancelledNewExportDoesNotLeaveAPartialDestination() throws Exception
    {
        Path data = data(), output = directory.resolve("new.zip");
        try (ScarletMigration.ExportSession session = open(data, new AtomicInteger()))
        {
            assertThrows(IOException.class, () -> session.exportBundle(output.toFile(), null,
                (phase, detail, done, total, bytes, size) -> false));
            assertFalse(Files.exists(output));
        }
        noTemporaryExports();
    }

    @Test void completeZipPublishesOnceAndContainsNoTemporaryOrEphemeralFiles() throws Exception
    {
        Path data = data(), output = directory.resolve("bundle.zip");
        Files.write(output, ORIGINAL);
        Files.writeString(data.resolve("settings.json.abcd.tmp"), "unfinished");
        Files.writeString(data.resolve("ipc.token"), "synthetic session token");
        Path staging = Files.createDirectory(data.resolve(".scarlet-import-stale"));
        Files.writeString(staging.resolve("original-prefs.xml"), "staging must not travel");
        AtomicInteger wipes = new AtomicInteger();
        try (ScarletMigration.ExportSession session = open(data, wipes))
        {
            session.exportBundle(output.toFile(), null, null);
            ScarletPrivateFilesTest.assertPrivate(output);
            try (ZipFile zip = new ZipFile(output.toFile()))
            {
                assertNotNull(zip.getEntry("data/settings.json"));
                assertNotNull(zip.getEntry("preferences.xml"));
                assertNotNull(zip.getEntry("scarlet-migration.txt"));
                assertNull(zip.getEntry("data/settings.json.abcd.tmp"));
                assertNull(zip.getEntry("data/ipc.token"));
                assertFalse(zip.stream().anyMatch(entry -> entry.getName().contains(".scarlet-import-")));
            }
            assertThrows(IOException.class, session::wipeLocalInstall);
            session.verifyBundle(output.toFile(), null, null);
            assertEquals("synthetic wipe", session.wipeLocalInstall());
            assertEquals(1, wipes.get());
            assertThrows(IOException.class, session::wipeLocalInstall);
        }
        noTemporaryExports();
    }

    @Test void newFilenameExportNeverReplacesExistingOrConcurrentlyCreatedFile() throws Exception
    {
        Path data = data(), output = directory.resolve("new-only.zip");
        try (ScarletMigration.ExportSession session = open(data, new AtomicInteger()))
        {
            Files.write(output, ORIGINAL);
            assertThrows(IOException.class, () -> session.exportNewBundle(output.toFile(), null, null));
            assertArrayEquals(ORIGINAL, Files.readAllBytes(output));
            Files.delete(output);
            assertThrows(IOException.class, () -> session.exportNewBundle(output.toFile(), null,
                (phase, detail, done, total, bytes, size) -> {
                    if ("Publishing".equals(phase))
                        try { Files.write(output, ORIGINAL, java.nio.file.StandardOpenOption.CREATE_NEW); }
                        catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
                    return true;
                }));
            assertArrayEquals(ORIGINAL, Files.readAllBytes(output));
            assertThrows(IOException.class, session::wipeLocalInstall);
            Files.delete(output);
            session.exportNewBundle(output.toFile(), null, null);
            ScarletPrivateFilesTest.assertPrivate(output);
            session.verifyBundle(output.toFile(), null, null);
        }
        noTemporaryExports();
    }

    @Test void missingOrEmptyCredentialKeyNeverAuthorizesLocalRemoval() throws Exception
    {
        Path data = data(), output = directory.resolve("bundle.zip"), key = data.resolve("global-prefs.key");
        AtomicInteger wipes = new AtomicInteger();
        try (ScarletMigration.ExportSession session = open(data, wipes))
        {
            for (boolean empty : new boolean[] { false, true })
            {
                if (empty) Files.write(key, new byte[0]); else Files.delete(key);
                session.exportBundle(output.toFile(), null, null);
                assertThrows(IOException.class, () -> session.verifyBundle(output.toFile(), null, null));
                assertThrows(IOException.class, session::wipeLocalInstall);
                assertEquals(0, wipes.get());
            }
        }
    }

    @Test void missingCompletePayloadFailsBeforeClaimingAnOutput() throws Exception
    {
        Path output = directory.resolve("partial.zip");
        IOException failure = assertThrows(IOException.class,
            () -> ScarletMigration.publishNewExport(directory.resolve("missing-complete-stage"), output));
        assertTrue(failure.getMessage().contains("staging file is unavailable"));
        assertFalse(Files.exists(output));
    }

    @Test void ordinaryExportRejectsAnAbsentSourceHome() throws Exception
    {
        Path absent = directory.resolve("absent-home");
        try (ScarletMigration.ExportSession session = open(absent, new AtomicInteger()))
        {
            assertThrows(IOException.class, () -> session.exportNewBundle(directory.resolve("absent.zip").toFile(), null, null));
            assertFalse(Files.exists(absent));
        }
    }

    @Test void encryptedExportAuthenticatesCorrectPinAndNeverAuthorizesWrongPin() throws Exception
    {
        Path data = data(), output = directory.resolve("bundle.encrypted");
        AtomicInteger wipes = new AtomicInteger();
        try (ScarletMigration.ExportSession session = open(data, wipes))
        {
            session.exportNewBundle(output.toFile(), "synthetic-pin".toCharArray(), null);
            assertTrue(ScarletMigration.isEncryptedBundle(output.toFile()));
            assertThrows(IOException.class, () -> session.verifyBundle(output.toFile(), "wrong-pin".toCharArray(), null));
            assertThrows(IOException.class, session::wipeLocalInstall);
            session.verifyBundle(output.toFile(), "synthetic-pin".toCharArray(), null);
            session.wipeLocalInstall();
            assertEquals(1, wipes.get());
        }
        noTemporaryExports();
    }

    @Test void encryptedExportAndVerificationNeverStagePlaintextBesideSharedBundle() throws Exception
    {
        Path data = data();
        Path shared = Files.createDirectory(directory.resolve("shared-transfer"));
        Path output = shared.resolve("bundle.encrypted");
        ScarletMigration.Progress inspect = (phase, detail, done, total, bytes, size) -> {
            try (java.util.stream.Stream<Path> paths = Files.list(shared))
            {
                for (Path path : (Iterable<Path>) paths::iterator)
                {
                    assertTrue(Files.isRegularFile(path), "No plaintext staging directory belongs beside an encrypted bundle");
                    if (Files.size(path) >= ScarletMigration.ENC_MAGIC.length)
                        assertTrue(ScarletMigration.isEncryptedBundle(path.toFile()), "Only encrypted content may enter the shared directory");
                }
            }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return true;
        };
        try (ScarletMigration.ExportSession session = open(data, new AtomicInteger()))
        {
            session.exportNewBundle(output.toFile(), "synthetic-pin".toCharArray(), inspect);
            session.verifyBundle(output.toFile(), "synthetic-pin".toCharArray(), inspect);
            Path plaintext;
            try (ScarletMigration.PreparedBundle prepared = ScarletMigration.prepareBundle(output.toFile(), "synthetic-pin".toCharArray(), inspect))
            {
                plaintext = prepared.file.toPath();
                assertFalse(plaintext.startsWith(shared));
                ScarletPrivateFilesTest.assertPrivate(plaintext);
                ScarletPrivateFilesTest.assertPrivate(plaintext.getParent());
                try (ZipFile zip = new ZipFile(prepared.file)) { assertNotNull(zip.getEntry("preferences.xml")); }
            }
            assertFalse(Files.exists(plaintext));
            assertFalse(Files.exists(plaintext.getParent()));
        }
    }

    @Test void verificationCancellationDisablesWipeAndRetainsCompleteExport() throws Exception
    {
        Path data = data(), output = directory.resolve("bundle.zip");
        AtomicInteger wipes = new AtomicInteger();
        try (ScarletMigration.ExportSession session = open(data, wipes))
        {
            session.exportBundle(output.toFile(), null, null);
            byte[] complete = Files.readAllBytes(output);
            assertThrows(IOException.class, () -> session.verifyBundle(output.toFile(), null,
                (phase, detail, done, total, bytes, size) -> false));
            assertThrows(IOException.class, session::wipeLocalInstall);
            assertArrayEquals(complete, Files.readAllBytes(output));
            assertEquals(0, wipes.get());
        }
        noTemporaryExports();
    }

    @Test void replacementDuringConfirmationCannotReuseEarlierVerification() throws Exception
    {
        Path data = data(), output = directory.resolve("bundle.zip");
        AtomicInteger wipes = new AtomicInteger();
        try (ScarletMigration.ExportSession session = open(data, wipes))
        {
            session.exportBundle(output.toFile(), null, null);
            session.verifyBundle(output.toFile(), null, null);
            Files.writeString(output, "replacement after verification");
            assertThrows(IOException.class, session::wipeLocalInstall);
            assertEquals(0, wipes.get());
        }
    }

    @Test void invalidPinEncryptedPayloadAndCorruptedZipCrcAreRejected() throws Exception
    {
        Path data = data(), output = directory.resolve("bundle.zip");
        try (ScarletMigration.ExportSession session = open(data, new AtomicInteger()))
        {
            session.exportBundle(output.toFile(), "pin".toCharArray(), null);
            byte[] altered = Files.readAllBytes(output);
            altered[altered.length - 1] ^= 1;
            Files.write(output, altered);
            assertThrows(IOException.class, () -> ScarletMigration.verifyBundleContents(output.toFile(), "pin".toCharArray(), null));
            session.exportBundle(output.toFile(), null, null);
            byte[] bytes = Files.readAllBytes(output);
            boolean modified = false;
            for (int i = 0; i + 20 < bytes.length; i++)
                if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2)
                { bytes[i + 16] ^= 1; modified = true; break; }
            assertTrue(modified);
            Files.write(output, bytes);
            assertThrows(IOException.class, () -> ScarletMigration.verifyBundleContents(output.toFile(), null, null));
        }
        noTemporaryExports();
    }

    @Test void outputInsideSourceIncludingDotAliasesIsRejectedWithoutDamage() throws Exception
    {
        Path data = data();
        try (ScarletMigration.ExportSession session = open(data, new AtomicInteger()))
        {
            for (Path output : Arrays.asList(data.resolve("export.zip"), data.resolve("nested/../export.zip"), data))
                assertThrows(IOException.class, () -> session.exportBundle(output.toFile(), null, null));
            assertFalse(Files.exists(data.resolve("export.zip")));
            assertTrue(Files.isRegularFile(data.resolve("settings.json")));
        }
    }

    @Test void exclusiveSessionExcludesOtherDataAndPreferencesWriters() throws Exception
    {
        Path data = data(), user = directory.resolve("synthetic-user");
        try (ScarletMigration.ExportSession session = open(data, new AtomicInteger()))
        {
            assertThrows(IOException.class, () -> ProcLock.acquireDataDirectory(data.toFile()));
            assertThrows(IOException.class, () -> ProcLock.acquirePreferences(user.toFile(), true));
        }
        try (ProcLock.Lease dataLease = ProcLock.acquireDataDirectory(data.toFile());
             ProcLock.Lease preferences = ProcLock.acquirePreferences(user.toFile(), true))
        { assertNotNull(dataLease); }
    }

    @Test void failedSecondLeaseReleasesFirstWithoutReadingPreferences() throws Exception
    {
        Path data = data(), user = directory.resolve("synthetic-user");
        AtomicInteger reads = new AtomicInteger();
        try (ProcLock.Lease running = ProcLock.acquirePreferences(user.toFile(), true))
        {
            assertThrows(IOException.class, () -> ScarletMigration.openExportSession(data.toFile(), user.toFile(),
                () -> { reads.incrementAndGet(); return new byte[0]; }, () -> "not called"));
            try (ProcLock.Lease released = ProcLock.acquireDataDirectory(data.toFile())) { assertNotNull(released); }
            assertEquals(0, reads.get());
        }
    }

    @Test void closedSessionCannotExportVerifyOrWipe() throws Exception
    {
        ScarletMigration.ExportSession session = open(data(), new AtomicInteger());
        session.close(); session.close();
        assertThrows(IOException.class, () -> session.exportBundle(directory.resolve("bundle.zip").toFile(), null, null));
        assertThrows(IOException.class, () -> session.exportNewBundle(directory.resolve("bundle.zip").toFile(), null, null));
        assertThrows(IOException.class, () -> session.verifyBundle(directory.resolve("bundle.zip").toFile(), null, null));
        assertThrows(IOException.class, session::wipeLocalInstall);
    }
}

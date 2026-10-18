package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic files only: no Scarlet session, Discord client, or real data directory is opened. */
class ScarletSecretStaffPersistenceTest
{
    private static final String ALPHA = "usr_00000000-0000-0000-0000-000000000001";
    private static final String BRAVO = "usr_00000000-0000-0000-0000-000000000002";
    private static final String LEGACY = "aB12345678";
    @TempDir Path temp;

    private Path policy(String json) throws IOException
    {
        return Files.writeString(this.temp.resolve("secret_staff_list.json"), json, StandardCharsets.UTF_8);
    }

    private static Set<String> ids(ScarletSecretStaffList list)
    {
        return new HashSet<>(Arrays.asList(list.getSecretStaffIds()));
    }

    private static class TestStorage implements ScarletSecretStaffList.Storage
    {
        boolean failRead, failWrite;
        @Override public Reader read(File file) throws IOException
        {
            if (this.failRead) throw new AccessDeniedException(file.toString(), null, "Synthetic unreadable policy");
            return ScarletSecretStaffList.FILE_STORAGE.read(file);
        }
        @Override public void write(File file, String[] ids) throws IOException
        {
            if (this.failWrite) throw new IOException("Synthetic unwritable policy");
            ScarletSecretStaffList.FILE_STORAGE.write(file, ids);
        }
    }

    @Test void firstUseCreatesAnExplicitEmptyPolicyAndRestarts() throws Exception
    {
        File file = this.temp.resolve("new-data/secret_staff_list.json").toFile();
        assertEquals(Set.of(), ids(new ScarletSecretStaffList(file)));
        assertEquals("[]", Files.readString(file.toPath()));
        assertEquals(Set.of(), ids(new ScarletSecretStaffList(file)));
    }

    @Test void validCurrentAndLegacyIdsRoundTripWithDuplicatesDeduplicated() throws Exception
    {
        Path file = this.policy("[\"" + ALPHA + "\", \"" + LEGACY + "\", \"" + ALPHA + "\"]");
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile());
        assertEquals(Set.of(ALPHA, LEGACY), ids(list));
        assertTrue(list.addSecretStaffId(BRAVO));
        assertFalse(list.addSecretStaffId(BRAVO));
        assertEquals(Set.of(ALPHA, BRAVO, LEGACY), ids(new ScarletSecretStaffList(file.toFile())));
        assertTrue(list.removeSecretStaffId(ALPHA));
        assertFalse(list.removeSecretStaffId(ALPHA));
        assertEquals(Set.of(BRAVO, LEGACY), ids(new ScarletSecretStaffList(file.toFile())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "{}", "[", "[null]", "[42]", "[true]", "[{}]", "[[]]",
        "[\"\"]", "[\"invalid-id\"]", "[\"usr_bad\"]", "[] []", "[] trailing", "/*comment*/[]", "[\"aB12345678\",]",
        "[\"aB12345678\"", "[\"aB12345678\",null]", "[\"aB12345678\",\"invalid-id\"]"})
    void invalidPolicyAbortsWithoutReplacingItOrUsingABackup(String json) throws Exception
    {
        Path file = this.policy(json);
        Path backups = Files.createDirectory(this.temp.resolve("backups"));
        Path old = Files.writeString(backups.resolve("secret_staff_list.json.20000101-000000.bak"), "[]");
        byte[] original = Files.readAllBytes(file);
        ScarletSecretStaffList.PersistenceException failure = assertThrows(ScarletSecretStaffList.PersistenceException.class,
            () -> new ScarletSecretStaffList(file.toFile()));
        assertTrue(failure.getMessage().contains("older backup"));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertEquals("[]", Files.readString(old));
        try (java.util.stream.Stream<Path> entries = Files.list(backups))
        {
            assertEquals(1, entries.count(), "No backup was replaced or generated from broken policy");
        }
    }

    @Test void invalidUtf8AbortsWithoutReplacingBytes() throws Exception
    {
        Path file = this.temp.resolve("secret_staff_list.json");
        byte[] original = {(byte) '[', (byte) '"', (byte) 0xC3, (byte) 0x28, (byte) '"', (byte) ']'};
        Files.write(file, original);
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> new ScarletSecretStaffList(file.toFile()));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void directoryAtPolicyPathAbortsAndRemainsIntact() throws Exception
    {
        Path file = Files.createDirectory(this.temp.resolve("secret_staff_list.json"));
        Path marker = Files.writeString(file.resolve("preserve.txt"), "synthetic original");
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> new ScarletSecretStaffList(file.toFile()));
        assertEquals("synthetic original", Files.readString(marker));
    }

    @Test void symbolicPolicyIsRejectedWithoutModifyingItsTarget() throws Exception
    {
        Path original = Files.writeString(this.temp.resolve("original.json"), "[]");
        Path link = this.temp.resolve("secret_staff_list.json");
        try { Files.createSymbolicLink(link, original); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported)
        {
            assumeTrue(false, "This environment does not permit symbolic links");
        }
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> new ScarletSecretStaffList(link.toFile()));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("[]", Files.readString(original));
    }

    @Test void unreadableExistingPolicyAbortsWithoutWriting() throws Exception
    {
        Path file = this.policy("[\"" + ALPHA + "\"]");
        String original = Files.readString(file);
        TestStorage storage = new TestStorage();
        storage.failRead = true;
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> new ScarletSecretStaffList(file.toFile(), storage));
        assertEquals(original, Files.readString(file));
        assertFalse(Files.exists(this.temp.resolve("backups")));
    }

    @Test void initializationFailureAbortsInsteadOfPretendingAnEmptyPolicyWasSaved() throws Exception
    {
        File file = this.temp.resolve("secret_staff_list.json").toFile();
        TestStorage storage = new TestStorage();
        storage.failWrite = true;
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> new ScarletSecretStaffList(file, storage));
        assertFalse(file.exists());
    }

    @Test void disappearanceBetweenAttributeCheckAndReadCannotBecomeFirstUse() throws Exception
    {
        Path file = this.policy("[\"" + ALPHA + "\"]");
        String original = Files.readString(file);
        TestStorage storage = new TestStorage()
        {
            @Override public Reader read(File value) throws IOException
            {
                throw new NoSuchFileException(value.toString());
            }
        };
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> new ScarletSecretStaffList(file.toFile(), storage));
        assertEquals(original, Files.readString(file));
        assertFalse(Files.exists(this.temp.resolve("backups")));
    }

    @Test void missingPrimaryWithOlderBackupRequiresManualVerifiedRecovery() throws Exception
    {
        Path backups = Files.createDirectory(this.temp.resolve("backups"));
        Path old = Files.writeString(backups.resolve("secret_staff_list.json.20000101-000000.bak"), "[]");
        File missing = this.temp.resolve("secret_staff_list.json").toFile();
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> new ScarletSecretStaffList(missing));
        assertFalse(missing.exists());
        assertEquals("[]", Files.readString(old));
    }

    @Test void inaccessibleBackupEvidenceIsNotTreatedAsFreshFirstUse() throws Exception
    {
        Path backupFile = Files.writeString(this.temp.resolve("backups"), "not a directory");
        File missing = this.temp.resolve("secret_staff_list.json").toFile();
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> new ScarletSecretStaffList(missing));
        assertFalse(missing.exists());
        assertEquals("not a directory", Files.readString(backupFile));
    }

    @Test void failedAdditionReportsFailureRetainsSessionProtectionAndCanBeRetried() throws Exception
    {
        Path file = this.policy("[]");
        TestStorage storage = new TestStorage();
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile(), storage);
        storage.failWrite = true;
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.addSecretStaffId(ALPHA));
        assertTrue(list.isSecretStaffId(ALPHA));
        assertEquals("[]", Files.readString(file));
        assertFalse(list.load(), "Reload must not discard an unsaved confidential addition");
        assertTrue(list.isSecretStaffId(ALPHA));
        storage.failWrite = false;
        assertTrue(list.addSecretStaffId(ALPHA), "Retry must save the pending addition, not say already saved");
        assertEquals(Set.of(ALPHA), ids(new ScarletSecretStaffList(file.toFile())));
    }

    @Test void failedRemovalKeepsProtectionInMemoryAndAfterRestart() throws Exception
    {
        Path file = this.policy("[\"" + ALPHA + "\"]");
        TestStorage storage = new TestStorage();
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile(), storage);
        storage.failWrite = true;
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.removeSecretStaffId(ALPHA));
        assertTrue(list.isSecretStaffId(ALPHA));
        assertEquals(Set.of(ALPHA), ids(new ScarletSecretStaffList(file.toFile())));
        storage.failWrite = false;
        assertTrue(list.removeSecretStaffId(ALPHA));
        assertEquals(Set.of(), ids(new ScarletSecretStaffList(file.toFile())));
    }

    @Test void explicitFailedSaveKeepsPendingAdditionUntilSuccessfulSave() throws Exception
    {
        Path file = this.policy("[\"" + ALPHA + "\"]");
        TestStorage storage = new TestStorage();
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile(), storage);
        storage.failWrite = true;
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.addSecretStaffId(BRAVO));
        assertFalse(list.save());
        assertFalse(list.load(), "A failed explicit save must not clear the pending addition");
        assertEquals(Set.of(ALPHA, BRAVO), ids(list));
        assertEquals(Set.of(ALPHA), ids(new ScarletSecretStaffList(file.toFile())));
        storage.failWrite = false;
        assertTrue(list.save());
        assertTrue(list.load(), "A committed save clears the pending state so reload is safe");
        assertFalse(list.addSecretStaffId(BRAVO));
        assertEquals(Set.of(ALPHA, BRAVO), ids(new ScarletSecretStaffList(file.toFile())));
    }

    @Test void successfulRemovalAlsoCommitsPendingAdditions() throws Exception
    {
        Path file = this.policy("[\"" + ALPHA + "\"]");
        TestStorage storage = new TestStorage();
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile(), storage);
        storage.failWrite = true;
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.addSecretStaffId(BRAVO));
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.removeSecretStaffId(ALPHA));
        assertEquals(Set.of(ALPHA, BRAVO), ids(list));
        assertFalse(list.load());
        storage.failWrite = false;
        assertTrue(list.removeSecretStaffId(ALPHA));
        assertTrue(list.load());
        assertEquals(Set.of(BRAVO), ids(new ScarletSecretStaffList(file.toFile())));
    }

    @Test void invalidReloadStopsPolicyUseAndCannotOverwriteBrokenFile() throws Exception
    {
        Path file = this.policy("[\"" + ALPHA + "\"]");
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile());
        Files.writeString(file, "null");
        assertFalse(list.load());
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.isSecretStaffId(BRAVO));
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.isSecretStaffId(null));
        assertThrows(ScarletSecretStaffList.PersistenceException.class, list::getSecretStaffIds);
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.getSecretStaffName(null, ALPHA));
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.getSecretStaffNames(null));
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.populateSecretStaffNames(null));
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.addSecretStaffId(BRAVO));
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.removeSecretStaffId(ALPHA));
        assertFalse(list.save());
        assertEquals("null", Files.readString(file));
        Files.writeString(file, "[\"" + ALPHA + "\"]");
        assertTrue(list.load());
        assertTrue(list.isSecretStaffId(ALPHA));
        assertFalse(list.isSecretStaffId(BRAVO));
    }

    @Test void disappearanceDuringSessionCannotBecomeEmptyPolicy() throws Exception
    {
        Path file = this.policy("[\"" + ALPHA + "\"]");
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile());
        Files.delete(file);
        assertFalse(list.load());
        assertFalse(Files.exists(file));
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.isSecretStaffId(ALPHA));
    }

    @Test void disappearanceOfPreviouslyLoadedEmptyPolicyCannotBecomeFirstUse() throws Exception
    {
        Path file = this.policy("[]");
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile());
        Files.delete(file);
        assertFalse(list.load());
        assertFalse(Files.exists(file));
        assertThrows(ScarletSecretStaffList.PersistenceException.class, () -> list.isSecretStaffId(ALPHA));
        assertFalse(list.save());
        assertFalse(Files.exists(file));
    }

    @Test void disappearanceOfNewlyInitializedPolicyCannotBecomeFirstUseAgain() throws Exception
    {
        Path file = this.temp.resolve("secret_staff_list.json");
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile());
        Files.delete(file);
        assertFalse(list.load());
        assertFalse(Files.exists(file));
        assertThrows(ScarletSecretStaffList.PersistenceException.class, list::getSecretStaffIds);
    }

    @Test void concurrentAdditionsCommitInMutationOrderAndBothSurviveRestart() throws Exception
    {
        Path file = this.policy("[]");
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        TestStorage storage = blockingStorage(writing, release, first);
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile(), storage);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try
        {
            Future<Boolean> older = threads.submit(() -> list.addSecretStaffId(ALPHA));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<Boolean> newer = threads.submit(() -> { secondStarted.countDown(); return list.addSecretStaffId(BRAVO); });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> newer.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(older.get(5, TimeUnit.SECONDS));
            assertTrue(newer.get(5, TimeUnit.SECONDS));
            assertEquals(Set.of(ALPHA, BRAVO), ids(new ScarletSecretStaffList(file.toFile())));
        }
        finally
        {
            release.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void snapshotSaveCannotOverwriteANewerRemovalAfterRestart() throws Exception
    {
        Path file = this.policy("[\"" + ALPHA + "\",\"" + BRAVO + "\"]");
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1), removeStarted = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile(), blockingStorage(writing, release, first));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try
        {
            Future<Boolean> older = threads.submit(list::save);
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<Boolean> newer = threads.submit(() -> { removeStarted.countDown(); return list.removeSecretStaffId(ALPHA); });
            assertTrue(removeStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> newer.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(older.get(5, TimeUnit.SECONDS));
            assertTrue(newer.get(5, TimeUnit.SECONDS));
            assertEquals(Set.of(BRAVO), ids(new ScarletSecretStaffList(file.toFile())));
        }
        finally
        {
            release.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void overlappingSaveAdditionAndRemovalAllSurviveRestart() throws Exception
    {
        Path file = this.policy("[\"" + ALPHA + "\"]");
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1), mutationsStarted = new CountDownLatch(2);
        AtomicBoolean first = new AtomicBoolean(true);
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile(), blockingStorage(writing, release, first));
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try
        {
            Future<Boolean> older = threads.submit(list::save);
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<Boolean> addition = threads.submit(() -> { mutationsStarted.countDown(); return list.addSecretStaffId(BRAVO); });
            Future<Boolean> removal = threads.submit(() -> { mutationsStarted.countDown(); return list.removeSecretStaffId(ALPHA); });
            assertTrue(mutationsStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> addition.get(100, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> removal.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(older.get(5, TimeUnit.SECONDS));
            assertTrue(addition.get(5, TimeUnit.SECONDS));
            assertTrue(removal.get(5, TimeUnit.SECONDS));
            assertEquals(Set.of(BRAVO), ids(list));
            assertEquals(Set.of(BRAVO), ids(new ScarletSecretStaffList(file.toFile())));
        }
        finally
        {
            release.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static TestStorage blockingStorage(CountDownLatch writing, CountDownLatch release, AtomicBoolean first)
    {
        return new TestStorage()
        {
            @Override public void write(File file, String[] ids) throws IOException
            {
                if (first.getAndSet(false))
                {
                    writing.countDown();
                    try
                    {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Synthetic write gate timed out");
                    }
                    catch (InterruptedException interrupted)
                    {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                }
                super.write(file, ids);
            }
        };
    }
}

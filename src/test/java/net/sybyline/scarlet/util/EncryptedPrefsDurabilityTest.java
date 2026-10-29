package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedPrefsDurabilityTest
{
    @TempDir Path directory;

    @Test void failedRecoveryStorageAbortsReseedAndPreservesEveryRegistryValue() throws Exception
    {
        Path blocker = Files.writeString(directory.resolve("not-a-directory"), "blocker");
        MemoryPreferences registry = new MemoryPreferences(null, "");
        String key = EncryptedPrefs.masterPasswordKey(registry.absolutePath(), "synthetic-password");
        registry.putByteArray(key, new byte[] { 1, 2, 3, 4 });
        registry.put("synthetic-credential-record", "preserve-exact-record");
        Map<String, String> before = new HashMap<>(registry.values);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new EncryptedPrefs(registry, "synthetic-password", blocker.toFile()));
        assertTrue(failure.getMessage().contains("original seed was preserved"));
        assertEquals(before, registry.values);
        assertEquals("blocker", Files.readString(blocker));
    }

    @Test void malformedBase64SeedStillRequiresVerifiedRecoveryBeforeReseed() throws Exception
    {
        Path blocker = Files.writeString(directory.resolve("not-a-directory"), "blocker");
        MemoryPreferences registry = new MemoryPreferences(null, "");
        registry.put(EncryptedPrefs.masterPasswordKey(registry.absolutePath(), "synthetic-password"), "not-base64!");
        Map<String, String> before = new HashMap<>(registry.values);
        assertThrows(IllegalStateException.class, () -> new EncryptedPrefs(registry, "synthetic-password", blocker.toFile()));
        assertEquals(before, registry.values);
    }

    @Test void failedRegistrySnapshotEnumerationAbortsReseed() throws Exception
    {
        MemoryPreferences registry = new MemoryPreferences(null, "");
        registry.putByteArray(EncryptedPrefs.masterPasswordKey(registry.absolutePath(), "synthetic-password"), new byte[] { 5, 6 });
        Map<String, String> before = new HashMap<>(registry.values);
        registry.failKeysAfter = 0;
        assertThrows(IllegalStateException.class, () -> new EncryptedPrefs(registry, "synthetic-password", directory.toFile()));
        assertEquals(before, registry.values);
        assertFalse(Files.exists(directory.resolve("secure-prefs.dat")));
    }

    @Test void reseedRequiresAndPreservesAnExactReadableRegistryRecoverySnapshot() throws Exception
    {
        MemoryPreferences registry = new MemoryPreferences(null, "");
        String key = EncryptedPrefs.masterPasswordKey(registry.absolutePath(), "synthetic-password");
        registry.putByteArray(key, new byte[] { 1, 2, 3, 4 });
        registry.put("synthetic-credential-record", "preserve-exact-record");
        registry.put("lastRunVersion", "synthetic-version");
        Map<String, String> before = new HashMap<>(registry.values);
        new EncryptedPrefs(registry, "synthetic-password", directory.toFile());
        java.util.List<Path> snapshots;
        try (java.util.stream.Stream<Path> files = Files.list(directory))
        {
            snapshots = files.filter(path -> path.getFileName().toString().startsWith("secure-prefs.registry-backup-"))
                .collect(java.util.stream.Collectors.toList());
        }
        assertEquals(1, snapshots.size());
        java.util.Properties recovery = new java.util.Properties();
        try (java.io.InputStream input = Files.newInputStream(snapshots.get(0))) { recovery.load(input); }
        assertEquals(before.size(), recovery.size());
        before.forEach((name, value) -> assertEquals(value, recovery.getProperty("s:" + name)));
        assertNotEquals(before.get(key), registry.values.get(key));
        assertNotNull(EncryptedPrefs.tryReadLocalPassword(registry, "synthetic-password"));
    }

    @Test void failedMirrorStorageNeverDeletesRegistryCredentials() throws Exception
    {
        Path blocker = directory.resolve("not-a-directory");
        Files.writeString(blocker, "blocker");
        MemoryPreferences registry = new MemoryPreferences(null, "");
        EncryptedPrefs store = new EncryptedPrefs(registry, "synthetic-password", blocker.toFile());
        store.put("synthetic-account", "synthetic-secret");
        Map<String, String> before = new HashMap<>(registry.values);
        assertTrue(store.repair(true).contains("SKIPPED"));
        assertEquals(before, registry.values);
        assertEquals("synthetic-secret", store.get("synthetic-account"));
    }

    @Test void truncatedPrimaryRecoversValidatedBackupWithoutReseeding() throws Exception
    {
        MemoryPreferences registry = new MemoryPreferences(null, "");
        EncryptedPrefs store = new EncryptedPrefs(registry, "synthetic-password", directory.toFile());
        store.put("synthetic-account", "synthetic-secret");
        assertTrue(store.repair(true).contains("COMPLETE"));
        Path primary = directory.resolve("secure-prefs.dat"), backup = directory.resolve("secure-prefs.dat.bak");
        Files.copy(primary, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.write(primary, new byte[0]);
        EncryptedPrefs recovered = new EncryptedPrefs(registry, "synthetic-password", directory.toFile());
        assertEquals("synthetic-secret", recovered.get("synthetic-account"));
        assertTrue(Files.size(backup) > 0);
        assertTrue(Files.size(primary) > 0);
    }

    @Test void unrecoverableExistingStoresFailClosedWithoutOverwritingEvidence() throws Exception
    {
        Path primary = directory.resolve("secure-prefs.dat"), backup = directory.resolve("secure-prefs.dat.bak");
        Files.writeString(primary, "broken=primary"); Files.writeString(backup, "broken=backup");
        assertThrows(IllegalStateException.class, () -> new EncryptedPrefs(new MemoryPreferences(null, ""), "synthetic-password", directory.toFile()));
        assertEquals("broken=primary", Files.readString(primary));
        assertEquals("broken=backup", Files.readString(backup));
    }

    @Test void successfulCutoverSurvivesReopeningWithoutRegistry() throws Exception
    {
        MemoryPreferences registry = new MemoryPreferences(null, "");
        EncryptedPrefs store = new EncryptedPrefs(registry, "synthetic-password", directory.toFile());
        store.put("synthetic-account", "synthetic-secret");
        assertTrue(store.repair(true).contains("COMPLETE"));
        assertEquals(0, registry.keys().length);
        assertEquals("synthetic-secret", new EncryptedPrefs(new MemoryPreferences(null, ""), "synthetic-password", directory.toFile()).get("synthetic-account"));
    }

    @Test void ordinaryMetadataDoesNotBlockCredentialCutoverOrGetDeleted() throws Exception
    {
        MemoryPreferences registry = new MemoryPreferences(null, "");
        registry.put("lastRunVersion", "0.5.0");
        EncryptedPrefs store = new EncryptedPrefs(registry, "synthetic-password", directory.toFile());
        store.put("synthetic-account", "synthetic-secret");
        assertTrue(store.repair(true).contains("COMPLETE"));
        assertEquals("0.5.0", registry.get("lastRunVersion", null));
        assertEquals(1, registry.keys().length);
    }

    @Test void deletedCredentialDoesNotReturnFromRecoveryBackup() throws Exception
    {
        MemoryPreferences registry = new MemoryPreferences(null, "");
        EncryptedPrefs store = new EncryptedPrefs(registry, "synthetic-password", directory.toFile());
        store.putVerified("synthetic-account", "synthetic-secret");
        store.repair(true); store.remove("synthetic-account");
        Files.write(directory.resolve("secure-prefs.dat"), new byte[0]);
        assertNull(new EncryptedPrefs(registry, "synthetic-password", directory.toFile()).get("synthetic-account"));
    }

    @Test void snapshotFailureAbortsCutoverEvenWhenMirrorWasSaved() throws Exception
    {
        MemoryPreferences registry = new MemoryPreferences(null, "");
        EncryptedPrefs store = new EncryptedPrefs(registry, "synthetic-password", directory.toFile());
        store.put("synthetic-account", "synthetic-secret");
        Map<String, String> before = new HashMap<>(registry.values);
        registry.failKeysAfter = registry.keyEnumerations + 2;
        assertTrue(store.repair(true).contains("SKIPPED"));
        assertTrue(Files.size(directory.resolve("secure-prefs.dat")) > 0);
        assertEquals(before, registry.values);
    }

    @Test void truncationAfterIntactSeedRecoversWholeStoreFromBackup() throws Exception
    {
        MemoryPreferences registry = new MemoryPreferences(null, "");
        EncryptedPrefs store = new EncryptedPrefs(registry, "synthetic-password", directory.toFile());
        store.putVerified("synthetic-account", "synthetic-secret");
        store.repair(true);
        Path primary = directory.resolve("secure-prefs.dat"), backup = directory.resolve("secure-prefs.dat.bak");
        Files.copy(primary, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.util.Properties damaged = new java.util.Properties();
        try (java.io.InputStream input = Files.newInputStream(primary)) { damaged.load(input); }
        String seed = EncryptedPrefs.masterPasswordKey(registry.absolutePath(), "synthetic-password");
        for (String key : new java.util.HashSet<>(damaged.stringPropertyNames()))
            if (key.matches("[0-9a-f]{64}") && !key.equals(seed)) damaged.remove(key);
        try (java.io.OutputStream output = Files.newOutputStream(primary)) { damaged.store(output, "synthetic truncation after seed"); }
        assertEquals("synthetic-secret", new EncryptedPrefs(registry, "synthetic-password", directory.toFile()).get("synthetic-account"));
    }

    @Test void ambiguousLegacyPartialStorePreservesBothCopiesForExplicitRecovery() throws Exception
    {
        MemoryPreferences registry = new MemoryPreferences(null, "");
        EncryptedPrefs store = new EncryptedPrefs(registry, "synthetic-password", directory.toFile());
        store.putVerified("synthetic-account", "synthetic-secret"); store.repair(true);
        Path primary = directory.resolve("secure-prefs.dat"), backup = directory.resolve("secure-prefs.dat.bak");
        Files.copy(primary, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.util.Properties damaged = new java.util.Properties();
        try (java.io.InputStream input = Files.newInputStream(primary)) { damaged.load(input); }
        String seed = EncryptedPrefs.masterPasswordKey(registry.absolutePath(), "synthetic-password");
        for (String key : new java.util.HashSet<>(damaged.stringPropertyNames()))
            if (!key.equals(seed) && !key.contains("primary")) damaged.remove(key);
        try (java.io.OutputStream output = Files.newOutputStream(primary)) { damaged.store(output, "legacy partial fixture"); }
        byte[] beforePrimary = Files.readAllBytes(primary), beforeBackup = Files.readAllBytes(backup);
        assertThrows(IllegalStateException.class, () -> new EncryptedPrefs(registry, "synthetic-password", directory.toFile()));
        assertArrayEquals(beforePrimary, Files.readAllBytes(primary)); assertArrayEquals(beforeBackup, Files.readAllBytes(backup));
    }

    @Test void verifiedWriteDoesNotRotateAwayThePreviousGeneration() throws Exception
    {
        MemoryPreferences registry = new MemoryPreferences(null, "");
        EncryptedPrefs store = new EncryptedPrefs(registry, "synthetic-password", directory.toFile());
        store.putVerified("synthetic-account", "original-secret"); store.repair(true);
        store.putVerified("synthetic-account", "replacement-secret");
        Files.write(directory.resolve("secure-prefs.dat"), new byte[0]);
        assertEquals("original-secret", new EncryptedPrefs(registry, "synthetic-password", directory.toFile()).get("synthetic-account"));
    }

    static final class MemoryPreferences extends AbstractPreferences
    {
        final Map<String, String> values = new HashMap<>();
        int keyEnumerations, failKeysAfter = Integer.MAX_VALUE;
        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { values.put(key, value); }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected void removeNodeSpi() { values.clear(); }
        protected String[] keysSpi() throws java.util.prefs.BackingStoreException
        {
            if (++keyEnumerations > failKeysAfter) throw new java.util.prefs.BackingStoreException("synthetic snapshot enumeration failure");
            return values.keySet().toArray(new String[0]);
        }
        protected String[] childrenNamesSpi() { return new String[0]; }
        protected AbstractPreferences childSpi(String name) { return new MemoryPreferences(this, name); }
        protected void syncSpi() { }
        protected void flushSpi() { }
    }
}

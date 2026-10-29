package net.sybyline.scarlet.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encrypted key/value store.
 *
 * <p>Historically this wrapped a {@link Preferences} node only, which on Windows means the
 * registry (HKCU\...\JavaSoft\Prefs). That backing store is prone to periodic corruption
 * (antivirus locking NTUSER.DAT, roaming/synced profiles, OneDrive-redirected AppData,
 * unclean shutdowns mid-flush), which surfaced as {@code AEADBadTagException: Tag mismatch}
 * on read. A single failed read of the internal "local password" record used to trigger a
 * silent reseed that orphaned <em>every</em> stored secret at once.
 *
 * <p>When constructed with a data directory (see {@link #EncryptedPrefs(Preferences, String, File)})
 * this class now keeps a redundant encrypted copy of every entry in a file inside that folder and:
 * <ul>
 *   <li><b>dual-writes</b> every value to the registry and the file;</li>
 *   <li>on read, tries the primary store, and only if its copy is missing or fails AES-GCM
 *       integrity does it fall back to the other store, then <b>reconstructs</b> the bad copy
 *       from the verified-good one;</li>
 *   <li><b>never destroys</b> data it cannot verify: unrecoverable bytes are quarantined to a
 *       timestamped file and, before any forced reseed, a full snapshot is written to disk;</li>
 *   <li>offers {@link #repair(boolean)} to reconcile the two stores and, once the file store
 *       byte-matches every registry entry, optionally snapshot-and-empty the registry so the
 *       data-folder file becomes the primary store.</li>
 * </ul>
 *
 * <p>The two-argument constructor keeps the old registry-only behaviour unchanged, so unit
 * tests and any caller without a data directory are unaffected.
 */
public final class EncryptedPrefs
{

    private static final Logger LOG = LoggerFactory.getLogger("EncryptedPrefs");

    private static final String DIGEST_METHOD = "SHA-256",
                                CIPHER_METHOD = "AES/GCM/NoPadding",
                                KEYGEN_METHOD = "PBKDF2WithHmacSHA256",
                                LEGACY_KEYGEN_METHOD = "PBKDF2WithHmacSHA1",
                                FALLBACK_PREFIX = "plain:";
    private static final String MIRROR_FILENAME = "secure-prefs.dat",
                                MIRROR_TEMP_SUFFIX = ".tmp",
                                MIRROR_BACKUP_SUFFIX = ".bak",
                                PRIMARY_MARKER = "__scarlet_secure_store_primary__",
                                INTEGRITY_MARKER = "__scarlet_secure_store_integrity_v1__";
    private static final int    PREFERRED_KEY_SIZE = 256,
                                KEYGEN_ITERATIONS = 100_000,
                                IV_LENGTH = 12,
                                GCM_TAG_SIZE = 128;
    private static final int    SUPPORTED_KEY_SIZE = detectSupportedKeySize();
    private static final SecureRandom rand;
    static
    {
        SecureRandom srand;
        try
        {
            srand = SecureRandom.getInstanceStrong();
        }
        catch (NoSuchAlgorithmException e)
        {
            srand = new SecureRandom();
        }
        rand = srand;
    }
    private static final ThreadLocal<Cipher> threadLocalCipher = ThreadLocal.withInitial(EncryptedPrefs::createCipher);
    private static int detectSupportedKeySize()
    {
        try
        {
            int max = Cipher.getMaxAllowedKeyLength("AES");
            if (max >= PREFERRED_KEY_SIZE)
                return PREFERRED_KEY_SIZE;
            if (max >= 128)
                return 128;
            return Math.max(0, max);
        }
        catch (Throwable e)
        {
            LOG.warn("Could not detect maximum AES key size; encrypted preferences will fall back to plain storage", e);
            return 0;
        }
    }
    private static Cipher createCipher()
    {
        try
        {
            return Cipher.getInstance(CIPHER_METHOD);
        }
        catch (Throwable e)
        {
            LOG.warn("Cipher {} is unavailable; encrypted preferences will fall back to plain storage", CIPHER_METHOD, e);
            return null;
        }
    }
    private static byte[] crypt(int opmode, SecretKey key, byte[] iv, byte[] text) throws GeneralSecurityException
    {
        Cipher cipher = threadLocalCipher.get();
        if (cipher == null)
            throw new GeneralSecurityException("Cipher unavailable: " + CIPHER_METHOD);
        cipher.init(opmode, key, new GCMParameterSpec(GCM_TAG_SIZE, iv));
        return cipher.doFinal(text);
    }

    /** Registry-only constructor (unchanged legacy behaviour; used by tests and any dir-less caller). */
    public EncryptedPrefs(Preferences prefs, String globalPassword)
    {
        this(prefs, globalPassword, null);
    }

    /**
     * @param dataDir when non-null (and prefs non-null), enables the redundant data-folder file store,
     *                dual-writing, verified read fallback, reconstruction, and {@link #repair(boolean)}.
     */
    public EncryptedPrefs(Preferences prefs, String globalPassword, File dataDir)
    {
        this.prefs = prefs;
        this.validationPassword = globalPassword;
        this.dataDir = dataDir;
        this.mirrorEnabled = dataDir != null && prefs != null;
        this.validationKey = this.mirrorEnabled && globalPassword != null && SUPPORTED_KEY_SIZE >= 128
            ? derive(globalPassword.toCharArray(), prefs.absolutePath()) : null;
        this.mirrorFile = this.mirrorEnabled ? new File(dataDir, MIRROR_FILENAME) : null;
        this.mirror = new Properties();
        this.keyCache = new ConcurrentHashMap<>();
        this.plaintextFallback = prefs == null || globalPassword == null || SUPPORTED_KEY_SIZE < 128;
        if (this.mirrorEnabled)
            this.loadMirror();
        this.filePrimary = this.mirrorEnabled && "file".equals(this.mirrorGetString(PRIMARY_MARKER));
        this.localPassword = this.plaintextFallback ? copyOrEmpty(globalPassword) : this.resolveLocalPassword(prefs, globalPassword);
        if (this.mirrorEnabled)
        {
            try
            {
                this.importRegistryIntoMirror();
            }
            catch (Throwable t)
            {
                LOG.warn("secure store: initial registry->file mirror import failed (non-fatal)", t);
            }
        }
        if (this.plaintextFallback)
            LOG.warn("Encrypted preferences unavailable; falling back to plain Java Preferences storage");
        else if (SUPPORTED_KEY_SIZE < PREFERRED_KEY_SIZE)
            LOG.warn("Encrypted preferences are using AES-{} compatibility mode instead of AES-{}", SUPPORTED_KEY_SIZE, PREFERRED_KEY_SIZE);
    }
    public static String tryReadLocalPassword(Preferences prefs, String globalPassword)
    {
        if (prefs == null || globalPassword == null)
            return null;
        return tryReadLocalPasswordForPath(prefs, null, prefs.absolutePath(), globalPassword);
    }

    /**
     * Reads a local-password seed whose encrypted record may have been moved to a different
     * Preferences node during a package-namespace migration. The record is looked up in
     * {@code prefs} (and, when supplied, the data-folder mirror), but its key and wrapping key are
     * derived from {@code sourceAbsolutePath}. This lets callers verify and re-wrap the seed for the
     * destination node without importing or mutating the earlier node.
     */
    public static String tryReadLocalPasswordForPath(Preferences prefs, File dataDir, String sourceAbsolutePath, String globalPassword)
    {
        if (prefs == null || sourceAbsolutePath == null || globalPassword == null)
            return null;
        try
        {
            String hash = masterPasswordKey(sourceAbsolutePath, globalPassword);
            String localPassword = readLocalPasswordAt(prefs, globalPassword, sourceAbsolutePath, hash);
            if (localPassword != null)
                return localPassword;
            localPassword = readLocalPasswordAt(prefs, globalPassword, sourceAbsolutePath, legacyMasterPasswordKey(sourceAbsolutePath, globalPassword));
            if (localPassword != null || dataDir == null)
                return localPassword;
        }
        catch (Exception ex)
        {
            // A broken Preferences backing store must not prevent recovery from the file mirror.
        }

        if (dataDir == null)
            return null;

        File primary = new File(dataDir, MIRROR_FILENAME);
        File backup = new File(dataDir, MIRROR_FILENAME + MIRROR_BACKUP_SUFFIX);
        for (File candidate : new File[] { primary, backup })
        {
            if (!candidate.isFile())
                continue;
            try (InputStream in = Files.newInputStream(candidate.toPath()))
            {
                Properties stored = new Properties();
                stored.load(in);
                String[] hashes = {
                    masterPasswordKey(sourceAbsolutePath, globalPassword),
                    legacyMasterPasswordKey(sourceAbsolutePath, globalPassword)
                };
                for (String hash : hashes)
                {
                    String plain = stored.getProperty(FALLBACK_PREFIX + hash);
                    if (plain != null)
                        return plain;
                    String encoded = stored.getProperty(hash);
                    if (encoded == null)
                        continue;
                    byte[] encrypted = Base64.getDecoder().decode(encoded);
                    String decrypted = decryptQuiet(derive(globalPassword.toCharArray(), sourceAbsolutePath), encrypted);
                    if (decrypted != null)
                        return decrypted;
                }
            }
            catch (Exception ex)
            {
                LOG.warn("secure store: could not verify an earlier local-password seed from {}", candidate, ex);
            }
        }
        return null;
    }

    /**
     * Reads a verified local-password seed from either the supplied Preferences node or its
     * data-folder mirror.
     */
    public static String tryReadLocalPassword(Preferences prefs, File dataDir, String globalPassword)
    {
        return prefs == null ? null : tryReadLocalPasswordForPath(prefs, dataDir, prefs.absolutePath(), globalPassword);
    }

    private static String readLocalPasswordAt(Preferences prefs, String globalPassword, String absolutePath, String hash)
    {
        try
        {
            if (SUPPORTED_KEY_SIZE < 128)
                return prefs.get(FALLBACK_PREFIX + hash, null);
            byte[] localPasswordBytes = prefs.getByteArray(hash, null);
            if (localPasswordBytes == null)
                return null;
            SecretKey initKey = derive(globalPassword.toCharArray(), absolutePath);
            return decryptQuiet(initKey, localPasswordBytes);
        }
        catch (Exception ex)
        {
            return null;
        }
    }
    public static void installLocalPassword(Preferences prefs, String globalPassword, String localPassword)
    {
        if (prefs == null || globalPassword == null || localPassword == null)
            throw new IllegalArgumentException("prefs, globalPassword, and localPassword must be non-null");
        if (SUPPORTED_KEY_SIZE < 128)
        {
            prefs.put(FALLBACK_PREFIX + masterPasswordKey(prefs.absolutePath(), globalPassword), localPassword);
            flushPrefs(prefs, "installing local fallback password");
            return;
        }
        String absolutePath = prefs.absolutePath(),
               hash = masterPasswordKey(absolutePath, globalPassword);
        SecretKey initKey = derive(globalPassword.toCharArray(), absolutePath);
        prefs.putByteArray(hash, encrypt(initKey, localPassword));
        flushPrefs(prefs, "installing local encrypted password");
    }
    public static String masterPasswordKey(String absolutePath, String globalPassword)
    {
        return hash("local-password:" + absolutePath);
    }
    static String legacyMasterPasswordKey(String absolutePath, String globalPassword)
    {
        return hash(absolutePath + ":" + globalPassword);
    }

    /**
     * Registry-only local-password resolution, retained verbatim for the dir-less (legacy/test) path.
     * The mirror-aware, fail-safe variant lives in {@link #resolveLocalPassword(Preferences, String)}.
     */
    private static char[] init(Preferences prefs, String globalPassword)
    {
        String absolutePath = prefs.absolutePath(),
               hash = masterPasswordKey(absolutePath, globalPassword);
        if (SUPPORTED_KEY_SIZE < 128)
        {
            String existing = prefs.get(FALLBACK_PREFIX + hash, null);
            if (existing != null)
                return existing.toCharArray();
            String legacyHash = legacyMasterPasswordKey(absolutePath, globalPassword);
            existing = prefs.get(FALLBACK_PREFIX + legacyHash, null);
            if (existing != null)
            {
                prefs.put(FALLBACK_PREFIX + hash, existing);
                prefs.remove(FALLBACK_PREFIX + legacyHash);
                flushPrefs(prefs, "migrating plain local password key");
                return existing.toCharArray();
            }
            byte[] bytes = new byte[32];
            rand.nextBytes(bytes);
            String localPassword = new String(Base64.getUrlEncoder().encode(bytes), StandardCharsets.UTF_8);
            prefs.put(FALLBACK_PREFIX + hash, localPassword);
            flushPrefs(prefs, "initializing local fallback password");
            return localPassword.toCharArray();
        }
        byte[] localPasswordBytes = prefs.getByteArray(hash, null);
        SecretKey initKey = derive(globalPassword.toCharArray(), absolutePath);
        if (localPasswordBytes != null)
        {
            String decrypted = decrypt(initKey, localPasswordBytes);
            if (decrypted != null)
                return decrypted.toCharArray();
        }
        String legacyHash = legacyMasterPasswordKey(absolutePath, globalPassword);
        localPasswordBytes = prefs.getByteArray(legacyHash, null);
        if (localPasswordBytes != null)
        {
            String decrypted = decrypt(initKey, localPasswordBytes);
            if (decrypted != null)
            {
                prefs.putByteArray(hash, localPasswordBytes);
                prefs.remove(legacyHash);
                flushPrefs(prefs, "migrating encrypted local password key");
                return decrypted.toCharArray();
            }
        }
        byte[] bytes = new byte[32];
        rand.nextBytes(bytes);
        String localPassword = new String(Base64.getUrlEncoder().encode(bytes), StandardCharsets.UTF_8);
        byte[] encrypted = encrypt(initKey, localPassword);
        if (encrypted == null)
        {
            LOG.warn("Initial secure preference seed could not be encrypted; falling back to plain Java Preferences storage");
            prefs.put(FALLBACK_PREFIX + hash, localPassword);
        }
        else
        {
            prefs.putByteArray(hash, encrypted);
        }
        flushPrefs(prefs, "initializing local encrypted password");
        return localPassword.toCharArray();
    }

    /**
     * Mirror-aware, non-destructive local-password resolution. Tries the primary store, the file
     * mirror, then legacy keys, before ever reseeding; and if it must reseed (no copy decrypts),
     * it first snapshots and quarantines the undecryptable records so nothing is lost.
     */
    private char[] resolveLocalPassword(Preferences prefs, String globalPassword)
    {
        if (!this.mirrorEnabled)
            return init(prefs, globalPassword);

        String absolutePath = prefs.absolutePath();
        String hash = masterPasswordKey(absolutePath, globalPassword);
        String legacyHash = legacyMasterPasswordKey(absolutePath, globalPassword);
        this.localPwKey = hash;

        if (SUPPORTED_KEY_SIZE < 128)
        {
            String plainHash = FALLBACK_PREFIX + hash, plainLegacy = FALLBACK_PREFIX + legacyHash;
            String v = firstNonNull(this.primaryString(plainHash), this.secondaryString(plainHash));
            if (v != null) { this.storeString(plainHash, v); return v.toCharArray(); }
            String legacy = firstNonNull(this.primaryString(plainLegacy), this.secondaryString(plainLegacy));
            if (legacy != null) { this.storeString(plainHash, legacy); this.removeBoth(plainLegacy); return legacy.toCharArray(); }
            byte[] seed = new byte[32]; rand.nextBytes(seed);
            String lp = new String(Base64.getUrlEncoder().encode(seed), StandardCharsets.UTF_8);
            this.storeString(plainHash, lp);
            return lp.toCharArray();
        }

        SecretKey initKey = derive(globalPassword.toCharArray(), absolutePath);
        byte[] cand; String dec;

        cand = this.primaryBytes(hash);       dec = decryptQuiet(initKey, cand);
        if (dec != null) { this.storeBytes(hash, cand); return dec.toCharArray(); }
        cand = this.secondaryBytes(hash);     dec = decryptQuiet(initKey, cand);
        if (dec != null) { this.storeBytes(hash, cand); return dec.toCharArray(); }
        cand = this.primaryBytes(legacyHash); dec = decryptQuiet(initKey, cand);
        if (dec != null) { this.storeBytes(hash, cand); this.removeBoth(legacyHash); return dec.toCharArray(); }
        cand = this.secondaryBytes(legacyHash); dec = decryptQuiet(initKey, cand);
        if (dec != null) { this.storeBytes(hash, cand); this.removeBoth(legacyHash); return dec.toCharArray(); }

        String plain = firstNonNull(this.primaryString(FALLBACK_PREFIX + hash), this.secondaryString(FALLBACK_PREFIX + hash));
        if (plain != null) { this.storeString(FALLBACK_PREFIX + hash, plain); return plain.toCharArray(); }

        // A malformed Base64 value is still recovery evidence; byte decoding must not hide it.
        boolean anyExisting = this.getRegString(hash) != null || this.mirrorGetString(hash) != null
                           || this.getRegString(legacyHash) != null || this.mirrorGetString(legacyHash) != null;
        if (anyExisting)
        {
            LOG.error("secure store: a local-password record exists but no copy could be decrypted. "
                    + "A verified recovery snapshot is required before reseeding; existing credentials "
                    + "will need to be re-entered if recovery is unavailable.");
            try { this.snapshotEverythingVerified("localpw-undecryptable"); }
            catch (IOException | BackingStoreException | RuntimeException failed)
            {
                throw new IllegalStateException("Credential reseeding was stopped because a recovery snapshot "
                    + "could not be saved and verified. The original seed was preserved. Restore a verified "
                    + "backup or repair the data-folder storage before continuing.", failed);
            }
            byte[] q;
            if ((q = this.getRegBytes(hash)) != null)    this.quarantine("localpw-registry", q);
            if ((q = this.mirrorGetBytes(hash)) != null) this.quarantine("localpw-file", q);
        }
        byte[] seed = new byte[32]; rand.nextBytes(seed);
        String lp = new String(Base64.getUrlEncoder().encode(seed), StandardCharsets.UTF_8);
        byte[] enc = encrypt(initKey, lp);
        if (enc == null) this.storeString(FALLBACK_PREFIX + hash, lp);
        else             this.storeBytes(hash, enc);
        return lp.toCharArray();
    }

    private final Preferences prefs;
    private final String validationPassword;
    private final SecretKey validationKey;
    private final char[] localPassword;
    private final Map<String, SecretKey> keyCache;
    private final boolean plaintextFallback;

    // Redundant data-folder file store (null/false when disabled).
    private final File dataDir;
    private final File mirrorFile;
    private final boolean mirrorEnabled;
    private final Properties mirror;
    private final Object mirrorLock = new Object();
    private volatile boolean filePrimary;
    private String localPwKey;

    public synchronized void put(String key, String value)
    {
        if (value == null)
        {
            this.remove(key);
            return;
        }
        String bk = hash(key), fk = FALLBACK_PREFIX + bk;
        if (this.plaintextFallback)
        {
            if (this.prefs == null && !this.mirrorEnabled)
                return;
            this.storeString(fk, value);
            return;
        }
        byte[] encrypted = encrypt(this.getOrDerive(key), value);
        if (encrypted == null)
            this.storeString(fk, value);
        else
            this.storeBytes(bk, encrypted);
    }

    public synchronized String get(String key)
    {
        String bk = hash(key), fk = FALLBACK_PREFIX + bk;
        if (this.plaintextFallback)
        {
            if (this.prefs == null && !this.mirrorEnabled)
                return null;
            return this.readStringWithFallback(fk);
        }
        String plain = this.readStringWithFallback(fk);
        if (plain != null)
            return plain;
        return this.readEncrypted(bk, key);
    }

    public synchronized void remove(String key)
    {
        if (this.prefs == null && !this.mirrorEnabled)
            return;
        String bk = hash(key), fk = FALLBACK_PREFIX + bk;
        try
        {
            if (this.mirrorEnabled)
            {
                synchronized (this.mirrorLock) { this.mirror.remove(bk); this.mirror.remove(fk); }
                Properties verified = this.saveMirrorVerified();
                // Recovery must not resurrect a deliberately deleted active credential.
                FileBackups.writeBytes(this.backupFile(), 0, output -> verified.store(output, "Scarlet secure store verified recovery copy"));
                if (!verified.equals(readProperties(this.backupFile()))) throw new IOException("Credential recovery copy failed verification");
            }
            if (this.prefs != null)
            {
                this.prefs.remove(bk); this.prefs.remove(fk); this.prefs.flush();
                if (this.prefs.get(bk, null) != null || this.prefs.get(fk, null) != null)
                    throw new IOException("Credential registry deletion failed verification");
            }
        }
        catch (IOException | BackingStoreException ex)
        { throw new java.io.UncheckedIOException("Credential removal was not fully persisted", ex instanceof IOException ? (IOException) ex : new IOException(ex)); }
    }

    public synchronized void putVerified(String key, String value)
    {
        this.put(key, value);
        try
        {
            if (this.mirrorEnabled)
            {
                synchronized (this.mirrorLock)
                {
                    Properties reopened = readProperties(this.mirrorFile);
                    if (!this.mirror.equals(reopened) || !this.isValidMirror(reopened))
                        throw new IOException("Saved credential mirror failed durable read-back verification");
                }
            }
            if (!this.filePrimary && this.prefs != null) this.prefs.flush();
            if (!java.util.Objects.equals(value, this.get(key))) throw new IOException("Saved credential failed verification");
        }
        catch (IOException | BackingStoreException ex)
        { throw new java.io.UncheckedIOException("Credential write was not fully persisted", ex instanceof IOException ? (IOException) ex : new IOException(ex)); }
    }

    public boolean contains(String key)
    {
        if (this.prefs == null && !this.mirrorEnabled)
            return false;
        String bk = hash(key), fk = FALLBACK_PREFIX + bk;
        return this.getRegBytes(bk) != null || this.mirrorGetBytes(bk) != null
            || this.getRegString(fk) != null || this.mirrorGetString(fk) != null;
    }

    // ---- read/write plumbing that keeps registry and file in sync -------------------------------

    private String readStringWithFallback(String fk)
    {
        String primary = this.filePrimary ? this.mirrorGetString(fk) : this.getRegString(fk);
        if (primary != null)
        {
            if (!this.filePrimary && this.mirrorGetString(fk) == null)
                this.mirrorPutString(fk, primary);
            return primary;
        }
        String secondary = this.filePrimary ? this.getRegString(fk) : this.mirrorGetString(fk);
        if (secondary != null)
        {
            this.healPrimaryString(fk, secondary);
            return secondary;
        }
        return null;
    }

    private String readEncrypted(String bk, String originalKey)
    {
        SecretKey ck = this.getOrDerive(originalKey);
        byte[] p = this.primaryBytes(bk);
        String dec = decryptQuiet(ck, p);
        if (dec != null)
        {
            if (this.secondaryBytes(bk) == null)
                this.healSecondaryBytes(bk, p);
            return dec;
        }
        byte[] s = this.secondaryBytes(bk);
        dec = decryptQuiet(ck, s);
        if (dec != null)
        {
            this.healPrimaryBytes(bk, s);
            return dec;
        }
        if (p != null) this.quarantine(bk + ".primary", p);
        if (s != null) this.quarantine(bk + ".secondary", s);
        if (p != null || s != null)
            LOG.warn("secure store: entry {} is unreadable from both the registry and the file store; "
                   + "the corrupt bytes were preserved for manual recovery and null was returned.", bk);
        return null;
    }

    // Direction-aware accessors: "primary" is the registry until cutover, the file afterwards.
    private byte[] primaryBytes(String k)   { return this.filePrimary ? this.mirrorGetBytes(k) : this.getRegBytes(k); }
    private byte[] secondaryBytes(String k) { return this.filePrimary ? this.getRegBytes(k)    : this.mirrorGetBytes(k); }
    private String primaryString(String k)   { return this.filePrimary ? this.mirrorGetString(k) : this.getRegString(k); }
    private String secondaryString(String k) { return this.filePrimary ? this.getRegString(k)    : this.mirrorGetString(k); }

    private void storeBytes(String k, byte[] v)
    {
        this.mirrorPutBytes(k, v);
        if (!this.filePrimary)
        {
            this.putRegBytes(k, v);
            flushPrefs(this.prefs, "writing " + k);
        }
    }
    private void storeString(String k, String v)
    {
        this.mirrorPutString(k, v);
        if (!this.filePrimary)
        {
            this.putRegString(k, v);
            flushPrefs(this.prefs, "writing " + k);
        }
    }
    private void removeBoth(String k)
    {
        this.mirrorRemove(k);
        this.removeReg(k);
        flushPrefs(this.prefs, "removing " + k);
    }
    // Reconstruct the primary copy from a verified-good secondary copy.
    private void healPrimaryBytes(String k, byte[] v)
    {
        if (this.filePrimary) this.mirrorPutBytes(k, v);
        else { this.putRegBytes(k, v); flushPrefs(this.prefs, "reconstructing " + k); }
    }
    private void healPrimaryString(String k, String v)
    {
        if (this.filePrimary) this.mirrorPutString(k, v);
        else { this.putRegString(k, v); flushPrefs(this.prefs, "reconstructing " + k); }
    }
    // Keep the (non-primary) file backup fresh; never repopulate the registry once the file is primary.
    private void healSecondaryBytes(String k, byte[] v)
    {
        if (!this.filePrimary)
            this.mirrorPutBytes(k, v);
    }

    // ---- registry accessors (null-safe, never throw) --------------------------------------------

    private byte[] getRegBytes(String key)   { try { return this.prefs == null ? null : this.prefs.getByteArray(key, null); } catch (Throwable t) { return null; } }
    private String getRegString(String key)  { try { return this.prefs == null ? null : this.prefs.get(key, null); } catch (Throwable t) { return null; } }
    private void putRegBytes(String key, byte[] v)  { try { if (this.prefs != null && v != null) this.prefs.putByteArray(key, v); } catch (Throwable t) { LOG.warn("secure store: registry write failed for {}", key, t); } }
    private void putRegString(String key, String v) { try { if (this.prefs != null && v != null) this.prefs.put(key, v); } catch (Throwable t) { LOG.warn("secure store: registry write failed for {}", key, t); } }
    private void removeReg(String key)              { try { if (this.prefs != null) this.prefs.remove(key); } catch (Throwable t) { /* ignore */ } }

    // ---- file mirror accessors ------------------------------------------------------------------

    private byte[] mirrorGetBytes(String key)
    {
        if (this.mirrorFile == null) return null;
        String s;
        synchronized (this.mirrorLock) { s = this.mirror.getProperty(key); }
        if (s == null) return null;
        try { return Base64.getDecoder().decode(s); } catch (Throwable t) { return null; }
    }
    private String mirrorGetString(String key)
    {
        if (this.mirrorFile == null && !PRIMARY_MARKER.equals(key)) return null;
        if (this.mirror == null) return null;
        synchronized (this.mirrorLock) { return this.mirror.getProperty(key); }
    }
    private void mirrorPutBytes(String key, byte[] v)
    {
        if (this.mirrorFile == null || v == null) return;
        synchronized (this.mirrorLock) { this.mirror.setProperty(key, Base64.getEncoder().encodeToString(v)); }
        this.saveMirror("writing " + key);
    }
    private void mirrorPutString(String key, String v)
    {
        if (this.mirrorFile == null || v == null) return;
        synchronized (this.mirrorLock) { this.mirror.setProperty(key, v); }
        this.saveMirror("writing " + key);
    }
    private void mirrorRemove(String key)
    {
        if (this.mirrorFile == null) return;
        boolean changed;
        synchronized (this.mirrorLock) { changed = this.mirror.remove(key) != null; }
        if (changed) this.saveMirror("removing " + key);
    }

    private void loadMirror()
    {
        synchronized (this.mirrorLock)
        {
            boolean existing = false;
            for (File candidate : new File[] { this.mirrorFile, this.backupFile() })
            {
                if (candidate == null || !candidate.exists()) continue;
                existing = true;
                try
                {
                    Properties loaded = readProperties(candidate);
                    if (!this.isValidMirror(loaded))
                        throw new IOException("Credential store seed or whole-file integrity failed validation");
                    if (candidate.equals(this.mirrorFile) && !loaded.containsKey(INTEGRITY_MARKER) && this.backupFile().isFile())
                    {
                        Properties recovery = readProperties(this.backupFile());
                        if (this.isValidMirror(recovery))
                            for (String key : recovery.stringPropertyNames())
                                if (isCredentialKey(key) && !loaded.containsKey(key))
                                    throw new AmbiguousLegacyMirrorException();
                    }
                    this.mirror.putAll(loaded);
                    if (!candidate.equals(this.mirrorFile))
                    {
                        // Preserve the damaged primary as evidence, and do not copy it over .bak.
                        if (this.mirrorFile.isFile())
                            Files.copy(this.mirrorFile.toPath(), new File(this.dataDir,
                                MIRROR_FILENAME + ".quarantine-" + java.util.UUID.randomUUID()).toPath());
                        LOG.warn("secure store: recovered validated credential mirror from backup");
                    }
                    return;
                }
                catch (AmbiguousLegacyMirrorException ambiguous) { throw ambiguous; }
                catch (IOException | RuntimeException ex)
                {
                    LOG.warn("secure store: credential mirror candidate failed validation; checking recovery copy", ex);
                }
            }
            if (existing && tryReadLocalPassword(this.prefs, this.validationPassword) == null)
            {
                throw new IllegalStateException("Credential storage is damaged and no valid recovery seed is available. "
                    + "Existing files were preserved; restore a verified backup before continuing.");
            }
        }
    }

    private static Properties readProperties(File file) throws IOException
    {
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file.toPath())) { values.load(input); }
        return values;
    }

    private static final class AmbiguousLegacyMirrorException extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;
        AmbiguousLegacyMirrorException()
        { super("Legacy credential primary omits entries present in its recovery copy. Both were preserved; explicit backup recovery is required before continuing."); }
    }

    private boolean isValidMirror(Properties values)
    {
        String expected = values.getProperty(INTEGRITY_MARKER);
        return this.hasValidSeed(values) && (expected == null || expected.equals(mirrorIntegrity(values)));
    }

    /** Detect accidental truncation anywhere in new stores, including after a valid seed. */
    private static String mirrorIntegrity(Properties values)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_METHOD);
            for (String key : new TreeSet<>(values.stringPropertyNames()))
            {
                if (INTEGRITY_MARKER.equals(key)) continue;
                for (String component : new String[] { key, values.getProperty(key) })
                {
                    byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private boolean hasValidSeed(Properties values)
    {
        if (values.isEmpty()) return false;
        if (this.validationPassword == null || SUPPORTED_KEY_SIZE < 128)
            return values.stringPropertyNames().stream().anyMatch(k -> k.startsWith(FALLBACK_PREFIX));
        String path = this.prefs.absolutePath();
        SecretKey wrappingKey = this.validationKey;
        for (String key : new String[] { masterPasswordKey(path, this.validationPassword), legacyMasterPasswordKey(path, this.validationPassword) })
        {
            if (values.getProperty(FALLBACK_PREFIX + key) != null) return true;
            try
            {
                String encoded = values.getProperty(key);
                if (encoded != null && decryptQuiet(wrappingKey, Base64.getDecoder().decode(encoded)) != null)
                    return true;
            }
            catch (IllegalArgumentException ignored) { }
        }
        return false;
    }

    private void saveMirror(String reason)
    {
        try { this.saveMirrorVerified(); }
        catch (IOException | RuntimeException ex)
        {
            LOG.warn("secure store: failed to save mirror file while {}", reason, ex);
        }
    }

    /** Publish only durable output, then verify a newly opened disk copy before cutover. */
    private Properties saveMirrorVerified() throws IOException
    {
        if (this.mirrorFile == null) throw new IOException("Credential mirror is unavailable");
        synchronized (this.mirrorLock)
        {
            Properties snapshot = new Properties();
            snapshot.putAll(this.mirror);
            if (!this.hasValidSeed(snapshot)) throw new IOException("Refusing to save a credential mirror without a valid seed");
            snapshot.setProperty(INTEGRITY_MARKER, mirrorIntegrity(snapshot));
            if (this.mirrorFile.isFile())
            {
                try
                {
                    Properties previous = readProperties(this.mirrorFile);
                    if (this.isValidMirror(previous))
                        FileBackups.writeBytes(this.backupFile(), 0,
                            output -> previous.store(output, "Scarlet secure store verified recovery copy"));
                }
                catch (IllegalArgumentException ex)
                {
                    LOG.warn("secure store: malformed primary was not copied over the recovery backup");
                }
            }
            FileBackups.writeBytes(this.mirrorFile, 0,
                output -> snapshot.store(output, "Scarlet secure store mirror - encrypted; do not edit by hand"));
            Properties reopened = readProperties(this.mirrorFile);
            if (!snapshot.equals(reopened) || !this.isValidMirror(reopened))
                throw new IOException("Credential mirror failed disk read-back verification");
            this.mirror.setProperty(INTEGRITY_MARKER, snapshot.getProperty(INTEGRITY_MARKER));
            return reopened;
        }
    }

    private File backupFile()
    {
        return this.mirrorFile == null ? null : new File(this.mirrorFile.getParentFile(), MIRROR_FILENAME + MIRROR_BACKUP_SUFFIX);
    }

    private void importRegistryIntoMirror()
    {
        if (!this.mirrorEnabled) return;
        boolean changed = false;
        synchronized (this.mirrorLock)
        {
            for (String k : this.safeKeys())
            {
                if (this.mirror.containsKey(k)) continue;
                byte[] b = this.getRegBytes(k);
                if (b != null) { this.mirror.setProperty(k, Base64.getEncoder().encodeToString(b)); changed = true; continue; }
                String s = this.getRegString(k);
                if (s != null) { this.mirror.setProperty(k, s); changed = true; }
            }
        }
        if (changed)
        {
            this.saveMirror("importing registry into file mirror");
            LOG.info("secure store: mirrored registry entries into {}", this.mirrorFile);
        }
    }

    private String[] safeKeys()
    {
        try { return this.prefs == null ? new String[0] : this.prefs.keys(); }
        catch (Throwable t) { LOG.warn("secure store: could not enumerate registry keys", t); return new String[0]; }
    }

    // ---- snapshots & quarantine (never destroy) -------------------------------------------------

    private void snapshotRegistryVerified(String reason) throws IOException, BackingStoreException
    {
        Properties dump = new Properties();
        for (String key : this.prefs.keys())
        {
            // Preserve Preferences' exact stored string representation, including metadata.
            String value = this.prefs.get(key, null);
            if (value == null) throw new IOException("Registry changed while preparing its snapshot");
            dump.setProperty("s:" + key, value);
        }
        File snapshot = new File(this.dataDir, "secure-prefs.registry-backup-" + ts() + "-" + java.util.UUID.randomUUID() + ".dat");
        FileBackups.writeBytes(snapshot, 0, output -> dump.store(output, "Scarlet registry snapshot (" + reason + ")"));
        if (!dump.equals(readProperties(snapshot))) throw new IOException("Registry snapshot failed read-back verification");
    }

    private void snapshotEverythingVerified(String reason) throws IOException, BackingStoreException
    {
        this.snapshotRegistryVerified(reason);
        if (this.mirrorFile != null && Files.exists(this.mirrorFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        {
            byte[] original = Files.readAllBytes(this.mirrorFile.toPath());
            File snapshot = new File(this.dataDir, MIRROR_FILENAME + ".backup-" + ts() + "-" + java.util.UUID.randomUUID() + ".dat");
            FileBackups.writeBytes(snapshot, 0, output -> output.write(original));
            if (!Arrays.equals(original, Files.readAllBytes(snapshot.toPath())))
                throw new IOException("Credential mirror recovery snapshot failed read-back verification");
        }
    }

    private void quarantine(String name, byte[] bytes)
    {
        if (this.dataDir == null || bytes == null) return;
        try
        {
            File f = new File(this.dataDir, "secure-prefs.quarantine-" + ts() + "-" + safeName(name) + ".b64");
            Files.write(f.toPath(), Base64.getEncoder().encode(bytes));
            LOG.warn("secure store: quarantined unreadable record to {}", f);
        }
        catch (Throwable t)
        {
            LOG.warn("secure store: failed to quarantine record {}", name, t);
        }
    }

    private static String safeName(String s)
    {
        return s == null ? "entry" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
    private static String ts()
    {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    // ---- repair / migration / cutover -----------------------------------------------------------

    /**
     * Reconcile the registry and the file store without destroying anything, and return a plain
     * report. When {@code cutover} is true and the file store already byte-matches every registry
     * entry, snapshot the registry and then remove its entries, making the data-folder file the
     * primary store. If any entry differs or is not yet covered, the cutover is skipped and nothing
     * is removed.
     */
    public synchronized String repair(boolean cutover)
    {
        if (!this.mirrorEnabled)
            return "Secure store: file mirror is disabled (no data directory) - nothing to repair.";

        Set<String> keys = new TreeSet<>();
        for (String k : this.safeKeys()) keys.add(k);
        synchronized (this.mirrorLock)
        {
            for (Object o : this.mirror.keySet())
            {
                String k = String.valueOf(o);
                if (!PRIMARY_MARKER.equals(k)) keys.add(k);
            }
        }

        int matched = 0, copiedToFile = 0, reconstructedRegistry = 0;
        List<String> mismatched = new ArrayList<>();
        for (String k : keys)
        {
            if (!isCredentialKey(k)) continue;
            if (k.startsWith(FALLBACK_PREFIX))
            {
                String r = this.getRegString(k), m = this.mirrorGetString(k);
                if (r != null && m == null)      { this.mirrorPutString(k, r); copiedToFile++; }
                else if (m != null && r == null) { if (!this.filePrimary) this.putRegString(k, m); reconstructedRegistry++; }
                else if (r != null)              { if (r.equals(m)) matched++; else mismatched.add(k); }
            }
            else
            {
                byte[] r = this.getRegBytes(k), m = this.mirrorGetBytes(k);
                if (r != null && m == null)      { this.mirrorPutBytes(k, r); copiedToFile++; }
                else if (m != null && r == null) { if (!this.filePrimary) this.putRegBytes(k, m); reconstructedRegistry++; }
                else if (r != null)              { if (Arrays.equals(r, m)) matched++; else mismatched.add(k); }
            }
        }
        if (!this.filePrimary) flushPrefs(this.prefs, "repair reconciliation");

        StringBuilder sb = new StringBuilder();
        sb.append("Secure store repair report\n");
        sb.append("  entries seen ............... ").append(keys.size()).append('\n');
        sb.append("  already consistent ......... ").append(matched).append('\n');
        sb.append("  copied registry -> file .... ").append(copiedToFile).append('\n');
        sb.append("  reconstructed file -> reg .. ").append(reconstructedRegistry).append('\n');
        sb.append("  conflicting (left as-is) ... ").append(mismatched.size());
        if (!mismatched.isEmpty()) sb.append(' ').append(mismatched);
        sb.append('\n');
        sb.append("  file store ................. ").append(this.mirrorFile).append('\n');
        sb.append("  primary store .............. ").append(this.filePrimary ? "file" : "registry").append('\n');

        if (!cutover)
        {
            sb.append("No changes to the registry were made. Re-run with cutover once you're satisfied.");
            return sb.toString();
        }
        if (this.filePrimary)
        {
            sb.append("Cutover: already complete - the file is the primary store and the registry is no longer used.");
            return sb.toString();
        }
        if (!mismatched.isEmpty())
        {
            sb.append("Cutover SKIPPED: ").append(mismatched.size())
              .append(" entr(ies) differ between registry and file. Nothing was removed. ")
              .append("Read those settings once (they self-heal on a successful read) or investigate, then re-run.");
            return sb.toString();
        }

        String[] regKeys;
        try { regKeys = this.prefs.keys(); }
        catch (Throwable t)
        {
            sb.append("Cutover SKIPPED: the registry could not be enumerated right now, so it isn't safe to clear it. Nothing was removed.");
            return sb.toString();
        }
        List<String> uncovered = new ArrayList<>();
        for (String k : regKeys)
        {
            if (!isCredentialKey(k)) continue;
            if (k.startsWith(FALLBACK_PREFIX))
            {
                String r = this.getRegString(k), m = this.mirrorGetString(k);
                if (m == null || !m.equals(r)) uncovered.add(k);
            }
            else
            {
                byte[] r = this.getRegBytes(k), m = this.mirrorGetBytes(k);
                if (m == null || !Arrays.equals(r, m)) uncovered.add(k);
            }
        }
        if (!uncovered.isEmpty())
        {
            sb.append("Cutover SKIPPED: the file store does not byte-match every registry entry yet: ").append(uncovered)
              .append(". Nothing was removed.");
            return sb.toString();
        }

        try
        {
            Properties durable = this.saveMirrorVerified();
            for (String key : regKeys)
            {
                if (!isCredentialKey(key)) continue;
                String registry = this.prefs.get(key, null);
                // Byte-array Preferences use the same base64 representation as the mirror.
                if (registry == null || !registry.equals(durable.getProperty(key)))
                    throw new IOException("Registry changed or durable mirror does not match");
            }
            this.snapshotRegistryVerified("pre-cutover");
            synchronized (this.mirrorLock) { this.mirror.setProperty(PRIMARY_MARKER, "file"); }
            this.saveMirrorVerified();
        }
        catch (Exception ex)
        {
            synchronized (this.mirrorLock) { this.mirror.remove(PRIMARY_MARKER); }
            LOG.warn("secure store: cutover stopped before registry deletion", ex);
            sb.append("Cutover SKIPPED: durable mirror/snapshot verification failed. No registry entries were removed.");
            return sb.toString();
        }
        this.filePrimary = true;
        int removed = 0;
        try
        {
            for (String k : regKeys) if (isCredentialKey(k)) { this.prefs.remove(k); removed++; }
            this.prefs.flush();
        }
        catch (Exception ex)
        {
            LOG.warn("secure store: verified file-primary cutover completed but registry cleanup failed", ex);
            sb.append("File store verified and primary; registry cleanup incomplete. Credentials remain in the verified file and snapshot.");
            return sb.toString();
        }
        sb.append("Cutover COMPLETE: a registry snapshot was saved, ").append(removed)
          .append(" registry entr(ies) were removed, and the data-folder file is now the primary store.");
        return sb.toString();
    }

    private static boolean isCredentialKey(String key)
    {
        String hashKey = key.startsWith(FALLBACK_PREFIX) ? key.substring(FALLBACK_PREFIX.length()) : key;
        return hashKey.matches("[0-9a-f]{64}");
    }

    private static String firstNonNull(String a, String b)
    {
        return a != null ? a : b;
    }

    private void flushPrefs(String reason)
    {
        flushPrefs(this.prefs, reason);
    }

    private static void flushPrefs(Preferences prefs, String reason)
    {
        if (prefs == null)
            return;
        try
        {
            prefs.flush();
        }
        catch (BackingStoreException | RuntimeException ex)
        {
            LOG.warn("Failed to flush Java Preferences while {}", reason, ex);
        }
    }

    /**
     * PowerPC edition only: PBKDF2 at 100k rounds takes many minutes per secret on a
     * JIT-less PowerPC JVM (e.g. a Wii), and every secret derives its own key. When the
     * password is Scarlet's auto-generated 256-bit random key file (no SCARLET_GLOBAL_PW /
     * scarlet.global.pw override), key stretching adds no meaningful protection, so the
     * PowerPC edition uses a much lower round count there. Secrets written this way can
     * only be read back by the PowerPC edition on PowerPC (keys differ from other builds).
     */
    private static final int PPC_KEYGEN_ITERATIONS = 1_000;
    private static volatile boolean ppcIterationsLogged;
    static int keygenIterations()
    {
        if (Platform.isPowerPC() && Platform.isPpcEdition()
            && System.getenv("SCARLET_GLOBAL_PW") == null
            && System.getProperty("scarlet.global.pw") == null)
        {
            if (!ppcIterationsLogged)
            {
                ppcIterationsLogged = true;
                LOG.warn("PowerPC edition: using {} PBKDF2 rounds for the local credential store (random key file; stretching adds nothing there). Secrets saved here are only readable by the PowerPC edition.", PPC_KEYGEN_ITERATIONS);
            }
            return PPC_KEYGEN_ITERATIONS;
        }
        return KEYGEN_ITERATIONS;
    }

    private SecretKey getOrDerive(String salt)
    {
        return this.keyCache.computeIfAbsent(salt, $ -> derive(this.localPassword, $));
    }

    private static SecretKey derive(char[] password, String salt)
    {
        try
        {
            return new SecretKeySpec(SecretKeyFactory.getInstance(KEYGEN_METHOD).generateSecret(new PBEKeySpec(password, salt.getBytes(StandardCharsets.UTF_8), keygenIterations(), SUPPORTED_KEY_SIZE)).getEncoded(), "AES");
        }
        catch (GeneralSecurityException e)
        {
            try
            {
                return new SecretKeySpec(SecretKeyFactory.getInstance(LEGACY_KEYGEN_METHOD).generateSecret(new PBEKeySpec(password, salt.getBytes(StandardCharsets.UTF_8), keygenIterations(), SUPPORTED_KEY_SIZE)).getEncoded(), "AES");
            }
            catch (GeneralSecurityException legacy)
            {
                throw new IllegalStateException("Derivation failed", legacy);
            }
        }
    }

    private static byte[] encrypt(SecretKey key, String value)
    {
        try
        {
            byte[] iv = new byte[IV_LENGTH];
            rand.nextBytes(iv);
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, key, iv, value.getBytes(StandardCharsets.UTF_8)),
                   combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encode(combined);
        }
        catch (Exception e)
        {
            LOG.warn("Exception encrypting", e);
            return null;
        }
    }

    private static String decryptQuiet(SecretKey key, byte[] combined)
    {
        return decrypt(key, combined, false);
    }
    private static String decrypt(SecretKey key, byte[] combined)
    {
        return decrypt(key, combined, true);
    }
    private static String decrypt(SecretKey key, byte[] combined, boolean logFailure)
    {
        try
        {
            if (combined == null)
                return null;
            combined = Base64.getDecoder().decode(combined);
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH),
                   ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length),
                   plaintext = crypt(Cipher.DECRYPT_MODE, key, iv, ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            if (logFailure)
                LOG.warn("Exception decrypting", e);
            return null;
        }
    }

    private static String hash(String key)
    {
        try
        {
            byte[] bytes = MessageDigest.getInstance(DIGEST_METHOD).digest(key.getBytes(StandardCharsets.UTF_8));
            char[] chars = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++)
            {
                byte b = bytes[i];
                chars[i * 2] = Character.forDigit((b >>> 4) & 0xF, 16);
                chars[i * 2 + 1] = Character.forDigit(b & 0xF, 16);
            }
            return new String(chars);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new AssertionError(e);
        }
    }

    private static char[] copyOrEmpty(String value)
    {
        return value == null ? new char[0] : value.toCharArray();
    }

}

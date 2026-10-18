package net.sybyline.scarlet;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.vrchatapi.model.User;

import net.sybyline.scarlet.util.FileBackups;
import net.sybyline.scarlet.util.VrcIds;

public class ScarletSecretStaffList
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/SecretStaffList");

    public ScarletSecretStaffList(File secretStaffListFile)
    {
        this(secretStaffListFile, FILE_STORAGE);
    }

    ScarletSecretStaffList(File secretStaffListFile, Storage storage)
    {
        this.secretStaffListFile = Objects.requireNonNull(secretStaffListFile, "secret staff file");
        this.storage = Objects.requireNonNull(storage, "secret staff storage");
        this.secretStaffUserIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.secretStaffNames = new ConcurrentHashMap<>();
        if (!this.load())
            throw new PersistenceException(RECOVERY_GUIDANCE);
    }

    private static final String RECOVERY_GUIDANCE = "Secret-staff privacy policy could not be loaded or saved. "
        + "Scarlet must not continue with an empty replacement. Preserve secret_staff_list.json and its backups; "
        + "verify and restore the complete current secret-staff list before restarting. "
        + "An older backup alone may omit newer confidential staff.";
    final File secretStaffListFile;
    final Set<String> secretStaffUserIds;
    final Map<String, String> secretStaffNames;
    private final Storage storage;
    private final Set<String> pendingAdditions = new HashSet<>();
    private boolean initialized;
    private boolean unavailable;

    public synchronized String[] getSecretStaffIds()
    {
        this.requireAvailable();
        return this.secretStaffUserIds.toArray(new String[0]);
    }

    public String getSecretStaffName(ScarletVRChat vrc, String userId)
    {
        if (!this.isSecretStaffId(userId))
            return null;
        String name = this.secretStaffNames.get(userId);
        if (name == null && vrc != null)
        {
            User user = vrc.getUser(userId);
            if (user != null)
            {
                name = user.getDisplayName();
            }
        }
        if (name == null)
        {
            name = userId;
        }
        return name;
    }

    public Map<String, String> getSecretStaffNames(ScarletVRChat vrc)
    {
        Map<String, String> ret = new HashMap<>();
        for (String userId : this.getSecretStaffIds())
        {
            ret.put(userId, this.getSecretStaffName(vrc, userId));
        }
        return ret;
    }

    public void populateSecretStaffNames(ScarletVRChat vrc)
    {
        for (String userId : this.getSecretStaffIds())
        {
            this.getSecretStaffName(vrc, userId);
        }
    }

    public synchronized boolean isSecretStaffId(String userId)
    {
        this.requireAvailable();
        return userId != null && this.secretStaffUserIds.contains(userId);
    }

    public synchronized boolean addSecretStaffId(String userId)
    {
        this.requireAvailable();
        if (userId == null)
            return false;
        requireUserId(userId);
        if (!this.secretStaffUserIds.add(userId) && !this.pendingAdditions.contains(userId))
            return false;
        // A failed addition stays confidential in this process, but is never reported as saved.
        this.pendingAdditions.add(userId);
        if (!this.save())
            throw new PersistenceException("The secret-staff addition could not be saved. It remains protected "
                + "for this session only. Fix the storage problem and retry before restarting Scarlet.");
        return true;
    }

    public synchronized boolean removeSecretStaffId(String userId)
    {
        this.requireAvailable();
        if (userId == null || !this.secretStaffUserIds.contains(userId))
            return false;
        Set<String> remaining = new HashSet<>(this.secretStaffUserIds);
        remaining.remove(userId);
        // Only relax privacy after the replacement was committed successfully.
        if (!this.persist(remaining))
            throw new PersistenceException("The secret-staff removal could not be saved. The user remains "
                + "protected. Fix the storage problem and retry.");
        this.secretStaffUserIds.remove(userId);
        this.secretStaffNames.remove(userId);
        this.pendingAdditions.clear();
        return true;
    }

    public synchronized boolean load()
    {
        if (!this.pendingAdditions.isEmpty())
        {
            LOG.error("Cannot reload secret-staff policy while confidential additions are not yet saved");
            return false;
        }
        try
        {
            Set<String> ids;
            Path path = this.secretStaffListFile.toPath();
            BasicFileAttributes attributes;
            try
            {
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }
            catch (NoSuchFileException missing)
            {
                if (this.initialized || this.hasPreviousPolicyBackups())
                    throw new IOException("Secret-staff policy is missing but previous policy evidence exists", missing);
                // A genuinely missing first-use file is the only empty-policy fallback.
                ids = Collections.emptySet();
                if (!this.persist(ids))
                    throw new IOException("Could not initialize the secret-staff privacy policy");
                this.initialized = true;
                this.unavailable = false;
                return true;
            }
            if (!attributes.isRegularFile() || attributes.isSymbolicLink())
                throw new IOException("Secret-staff privacy policy is not a regular file");
            try (Reader input = this.storage.read(this.secretStaffListFile);
                 JsonReader reader = new JsonReader(input))
            {
                reader.setStrictness(Strictness.STRICT);
                if (reader.peek() != JsonToken.BEGIN_ARRAY)
                    throw new IOException("Secret-staff policy must contain a JSON array of user IDs");
                ids = new HashSet<>();
                reader.beginArray();
                while (reader.hasNext())
                {
                    if (reader.peek() != JsonToken.STRING)
                        throw new IOException("Every secret-staff policy entry must be a user ID string");
                    String id = reader.nextString();
                    requireUserId(id);
                    ids.add(id);
                }
                reader.endArray();
                if (reader.peek() != JsonToken.END_DOCUMENT)
                    throw new IOException("Unexpected content after the secret-staff policy");
            }
            this.secretStaffUserIds.clear();
            this.secretStaffUserIds.addAll(ids);
            this.secretStaffNames.keySet().retainAll(ids);
            this.initialized = true;
            this.unavailable = false;
            return true;
        }
        catch (Exception ex)
        {
            this.unavailable = true;
            LOG.error(RECOVERY_GUIDANCE, ex);
            return false;
        }
    }

    public synchronized boolean save()
    {
        if (this.unavailable)
            return false;
        if (!this.persist(this.secretStaffUserIds))
            return false;
        this.pendingAdditions.clear();
        return true;
    }

    private boolean persist(Set<String> values)
    {
        String[] ids = values.toArray(new String[0]);
        Arrays.sort(ids);
        try
        {
            this.storage.write(this.secretStaffListFile, ids);
        }
        catch (Exception ex)
        {
            LOG.error("Exception saving secret staff list", ex);
            return false;
        }
        return true;
    }

    private boolean hasPreviousPolicyBackups() throws IOException
    {
        Path backupDirectory = this.secretStaffListFile.getAbsoluteFile().toPath().getParent().resolve("backups");
        try
        {
            BasicFileAttributes attributes = Files.readAttributes(backupDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink())
                throw new IOException("Cannot inspect secret-staff backup evidence safely");
        }
        catch (NoSuchFileException missing)
        {
            return false;
        }
        try (DirectoryStream<Path> backups = Files.newDirectoryStream(backupDirectory,
            this.secretStaffListFile.getName() + ".*.bak"))
        {
            return backups.iterator().hasNext();
        }
    }

    private static void requireUserId(String userId)
    {
        if (!VrcIds.id_user.matcher(userId).matches())
            throw new IllegalArgumentException("Secret-staff policy contains an invalid VRChat user ID");
    }

    private void requireAvailable()
    {
        if (this.unavailable)
            throw new PersistenceException(RECOVERY_GUIDANCE);
    }

    public static final class PersistenceException extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;
        PersistenceException(String message) { super(message); }
    }

    interface Storage
    {
        Reader read(File file) throws IOException;
        void write(File file, String[] ids) throws IOException;
    }

    static final Storage FILE_STORAGE = new Storage()
    {
        @Override public Reader read(File file) throws IOException
        {
            return Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
        }
        @Override public void write(File file, String[] ids) throws IOException
        {
            FileBackups.write(file, output -> Scarlet.GSON_PRETTY.toJson(ids, String[].class, output));
        }
    };

}

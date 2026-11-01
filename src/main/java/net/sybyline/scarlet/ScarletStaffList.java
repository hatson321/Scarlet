package net.sybyline.scarlet;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.vrchatapi.model.User;

import net.sybyline.scarlet.util.MiscUtils;

public class ScarletStaffList
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/StaffList");

    public ScarletStaffList(File staffListFile)
    {
        this.staffListFile = staffListFile;
        this.staffUserIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.staffNames = new ConcurrentHashMap<>();
        if (!this.load())
            throw new IllegalStateException("Staff list could not be loaded. Original data was preserved; restore or repair the file before restarting.");
    }

    final File staffListFile;
    final Set<String> staffUserIds;
    final Map<String, String> staffNames;
    private boolean loadFailed;

    public String[] getStaffIds()
    {
        return this.staffUserIds.toArray(new String[this.staffUserIds.size()]);
    }

    public String getStaffName(ScarletVRChat vrc, String userId)
    {
        if (!this.isStaffId(userId))
            return null;
        String name = this.staffNames.get(userId);
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

    public Map<String, String> getStaffNames(ScarletVRChat vrc)
    {
        Map<String, String> ret = new HashMap<>();
        for (String userId : this.getStaffIds())
        {
            ret.put(userId, this.getStaffName(vrc, userId));
        }
        return ret;
    }

    public void populateStaffNames(ScarletVRChat vrc)
    {
        for (String userId : this.getStaffIds())
        {
            this.getStaffName(vrc, userId);
        }
    }

    public boolean isStaffId(String userId)
    {
        return userId != null && this.staffUserIds.contains(userId);
    }

    public boolean addStaffId(String userId)
    {
        if (userId == null || !this.staffUserIds.add(userId))
            return false;
        this.save();
        return true;
    }

    public boolean removeStaffId(String userId)
    {
        if (userId == null || !this.staffUserIds.remove(userId))
            return false;
        this.save();
        return true;
    }

    public synchronized boolean load()
    {
        boolean failedBefore = this.loadFailed;
        this.loadFailed = true;
        try
        {
            if (!ScarletData.existingRecord(this.staffListFile))
            {
                if (failedBefore) throw new java.io.IOException("Previously unreadable staff list must be restored or repaired before reload");
                this.loadFailed = false;
                return this.save();
            }
        }
        catch (Exception ex) { LOG.error("Could not inspect staff list storage", ex); return false; }
        List<String> ids;
        try (Reader r = MiscUtils.reader(this.staffListFile))
        {
            String[] loaded = Scarlet.GSON_PRETTY.fromJson(r, String[].class);
            if (loaded == null) throw new java.io.IOException("Existing staff list must contain a JSON array");
            ids = new java.util.ArrayList<>(Arrays.asList(loaded));
        }
        catch (Exception ex)
        {
            LOG.error("Exception loading staff list", ex);
            return false;
        }
        ids.removeIf(Objects::isNull);
        this.staffUserIds.clear();
        this.staffUserIds.addAll(ids);
        this.loadFailed = false;
        return true;
    }

    public synchronized boolean save()
    {
        if (this.loadFailed)
        {
            LOG.error("Staff list storage was not loaded; refusing to overwrite the original data");
            return false;
        }
        String[] ids = this.getStaffIds();
        try (net.sybyline.scarlet.util.FileBackups.AtomicWriter w = net.sybyline.scarlet.util.FileBackups.writer(this.staffListFile))
        {
            Scarlet.GSON_PRETTY.toJson(ids, String[].class, w);
            w.commit();
        }
        catch (Exception ex)
        {
            LOG.error("Exception saving staff list", ex);
            return false;
        }
        return true;
    }

}

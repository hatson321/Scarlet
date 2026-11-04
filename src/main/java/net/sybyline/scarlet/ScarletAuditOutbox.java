package net.sybyline.scarlet;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import net.sybyline.scarlet.util.FileBackups;

/** Disk-backed delivery receipts. Failed events survive both cursor advancement and restart. */
final class ScarletAuditOutbox
{
    final File directory;
    ScarletAuditOutbox(File dataDirectory) { this.directory = new File(dataDirectory, "audit-outbox"); }

    File file(String id)
    {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IllegalArgumentException("Invalid audit identifier");
        return new File(this.directory, id + ".json");
    }

    boolean exists(String id) { return this.file(id).isFile(); }

    ScarletData.AuditEntryMetadata read(String id)
    {
        try (Reader reader = Files.newBufferedReader(this.file(id).toPath(), StandardCharsets.UTF_8))
        {
            ScarletData.AuditEntryMetadata metadata = Scarlet.GSON.fromJson(reader, ScarletData.AuditEntryMetadata.class);
            if (metadata == null || metadata.entry == null || !id.equals(metadata.entry.getId()))
                throw new IOException("Invalid audit outbox record");
            return metadata;
        }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    void save(ScarletData.AuditEntryMetadata metadata)
    {
        try
        {
            Files.createDirectories(this.directory.toPath());
            FileBackups.write(this.file(metadata.entry.getId()), 0,
                writer -> Scarlet.GSON.toJson(metadata, ScarletData.AuditEntryMetadata.class, writer));
        }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    List<String> ids()
    {
        List<String> ids = new ArrayList<>();
        File[] files = this.directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null)
            for (File file : files) ids.add(file.getName().substring(0, file.getName().length() - 5));
        java.util.Collections.sort(ids);
        return ids;
    }

    static boolean complete(ScarletData.AuditEntryMetadata metadata)
    {
        String state = metadata.getAuxData("deliveryState", "pending", e -> e.getAsString());
        return "delivered".equals(state) || "suppressed".equals(state);
    }

    static boolean isFollowup(ScarletData.AuditEntryMetadata metadata)
    {
        return "followup_pending".equals(metadata.getAuxData("deliveryState", "pending", e -> e.getAsString()));
    }

    static boolean isDependency(ScarletData.AuditEntryMetadata metadata)
    {
        return "dependency_pending".equals(metadata.getAuxData("deliveryState", "pending", e -> e.getAsString()));
    }

    static String dependency(ScarletData.AuditEntryMetadata metadata)
    {
        String id = metadata.getAuxData("deliveryDependencyEvent", (String) null, e -> e.getAsString());
        // A5 could persist a failed bundled send after assigning parentEventId, before
        // dependency receipts existed. Keep that original binding on an upgraded retry.
        if (id == null && metadata.entry != null && "group.instance.kick".equals(metadata.entry.getEventType()))
            id = metadata.parentEventId;
        if (id != null && !id.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IllegalArgumentException("Invalid audit dependency identifier");
        return id;
    }

    static void waitForDependency(ScarletData.AuditEntryMetadata metadata, String parentId, long now)
    {
        metadata.setAuxData("deliveryState", "dependency_pending");
        metadata.setAuxData("deliveryDependencyEvent", parentId);
        // A lost notification, a full worker queue or a restart must not strand the event.
        // This safety recheck is independent of genuine delivery-failure backoff/attempts.
        metadata.setAuxData("deliveryRetryAt", now + 30_000L);
    }

    boolean dependencyMayProceed(Scarlet scarlet, ScarletData.AuditEntryMetadata metadata)
    {
        if (!isDependency(metadata)) return false;
        String parentId = dependency(metadata);
        if (parentId == null) return true; // Dispatch will report a malformed dependency.
        ScarletData.AuditEntryMetadata parent = scarlet.data.auditEntryMetadata(parentId);
        return parent != null && parent.threadSnowflake != null || !this.exists(parentId);
    }

    static boolean due(ScarletData.AuditEntryMetadata metadata, long now)
    {
        return metadata.getAuxData("deliveryRetryAt", 0L, e -> e.getAsLong()) <= now;
    }

    static long retryDelay(int attempts)
    {
        return Math.min(300_000L, 5_000L << Math.min(6, Math.max(0, attempts - 1)));
    }

    void finish(Scarlet scarlet, ScarletData.AuditEntryMetadata metadata)
    {
        // Save a terminal receipt before the metadata write. If that write fails, the receipt
        // prevents replay; retryPending will retry the local commit without sending again.
        this.save(metadata);
        if (metadata.getAuxData("deliveryModerationThread", false, e -> e.getAsBoolean())
            && scarlet.data.auditEntryMetadataExists(metadata.entry.getId()))
        {
            // A completed background receipt can survive a failed local commit. User edits
            // may occur before recovery: merge acknowledgements, never restore the older
            // receipt's tags, description, redaction or captured profile data.
            scarlet.data.auditEntryMetadata(metadata.entry.getId(), current -> {
                if (current.entry == null
                    || !java.util.Objects.equals(current.entry.getId(), metadata.entry.getId())
                    || !java.util.Objects.equals(current.entry.getGroupId(), metadata.entry.getGroupId())
                    || !java.util.Objects.equals(current.entry.getEventType(), metadata.entry.getEventType())
                    || !java.util.Objects.equals(current.entry.getActorId(), metadata.entry.getActorId())
                    || !java.util.Objects.equals(current.entry.getTargetId(), metadata.entry.getTargetId())
                    || !java.util.Objects.equals(current.auxActorId, metadata.auxActorId)
                    || !java.util.Objects.equals(current.parentEventId, metadata.parentEventId)
                    || !java.util.Objects.equals(current.guildSnowflake, metadata.guildSnowflake)
                    || !java.util.Objects.equals(current.channelSnowflake, metadata.channelSnowflake)
                    || !java.util.Objects.equals(current.messageSnowflake, metadata.messageSnowflake))
                    throw new IllegalStateException("Completed audit follow-up no longer matches its recorded parent");
                if (current.threadSnowflake == null) current.threadSnowflake = metadata.threadSnowflake;
                if (current.auxMessageSnowflake == null) current.auxMessageSnowflake = metadata.auxMessageSnowflake;
                current.setAuxData("deliveryState", metadata.getAuxData("deliveryState", "delivered", e -> e.getAsString()));
                current.setAuxData("deliveryModerationThread", true);
                return current;
            });
        }
        else scarlet.data.auditEntryMetadata(metadata.entry.getId(), metadata);
        File persisted = new File(new File(scarlet.data.dir, "gaud"), metadata.entry.getId());
        try (Reader reader = Files.newBufferedReader(persisted.toPath(), StandardCharsets.UTF_8))
        {
            ScarletData.AuditEntryMetadata check = Scarlet.GSON.fromJson(reader, ScarletData.AuditEntryMetadata.class);
            if (check != null && check.entry != null && metadata.entry.getId().equals(check.entry.getId()) && complete(check))
                Files.deleteIfExists(this.file(metadata.entry.getId()).toPath());
        }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
}

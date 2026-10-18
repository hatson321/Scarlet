package net.sybyline.scarlet;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;

/** Read-only, bounded summary input. No downloads; snapshot values are skipped, not retained. */
final class ScarletLocalAuditHistory
{
    private ScarletLocalAuditHistory() {}
    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/LocalAuditHistory");

    static final class Result
    {
        final List<ScarletData.AuditEntryMetadata> records;
        final List<ScarletData.CustomEvent> customEvents;
        final boolean hasReadProblems;
        final boolean hasFatalProblems;
        final int unreadableFileCount;
        final boolean hasUnprocessedEvents;
        final int filesScanned;

        Result(Loader loader)
        {
            this.records = Collections.unmodifiableList(new ArrayList<>(loader.records.values()));
            this.customEvents = Collections.unmodifiableList(new ArrayList<>(loader.customEvents));
            this.hasFatalProblems = loader.fatal;
            this.unreadableFileCount = loader.unreadableFiles.size();
            this.hasReadProblems = this.hasFatalProblems || this.unreadableFileCount != 0;
            this.hasUnprocessedEvents = loader.unprocessed;
            this.filesScanned = loader.filesScanned;
        }
    }

    static final class Limits
    {
        final int files, records;
        final long fileBytes, totalBytes, milliseconds;
        Limits(int files, int records, long fileBytes, long totalBytes, long milliseconds)
        {
            this.files = files; this.records = records; this.fileBytes = fileBytes;
            this.totalBytes = totalBytes; this.milliseconds = milliseconds;
        }
    }

    static Result read(File dataDirectory, String groupId, OffsetDateTime from, OffsetDateTime to, Set<String> eventTypes)
    {
        return read(dataDirectory, groupId, from, to, eventTypes,
            new Limits(250_000, 100_000, 8L * 1024 * 1024, 2L * 1024 * 1024 * 1024, 300_000L));
    }

    static Result read(File dataDirectory, String groupId, OffsetDateTime from, OffsetDateTime to,
                       Set<String> eventTypes, Limits limits)
    {
        Loader loader = new Loader(dataDirectory, groupId, from, to, eventTypes, limits);
        try
        {
            if (groupId == null || groupId.trim().isEmpty() || from == null || to == null || from.isAfter(to))
                throw new Fatal("invalid history scope");
            loader.exactDirectory(loader.root);
            loader.scan("gaud");
            loader.scan("audit-outbox");
            loader.scan("ex");
        }
        catch (IOException | RuntimeException failure)
        {
            loader.fatal = true;
            LOG.warn("Local moderation summary stopped: {}. Totals must not be used.",
                failure instanceof Fatal ? failure.getMessage() : "history directory could not be scanned safely");
        }
        return new Result(loader);
    }

    private static class Fatal extends IOException
    {
        private static final long serialVersionUID = 1L;
        Fatal(String reason) { super(reason); }
    }
    private static final class Stop extends Fatal
    {
        private static final long serialVersionUID = 1L;
        Stop(String reason) { super(reason); }
    }
    private static final class FileProblem extends IOException
    {
        private static final long serialVersionUID = 1L;
        FileProblem(String reason) { super(reason); }
    }

    private static final class Loader
    {
        final Path root;
        final String group;
        final OffsetDateTime from, to;
        final Set<String> types;
        final Limits limits;
        final long started = System.nanoTime();
        final Map<String, ScarletData.AuditEntryMetadata> records = new LinkedHashMap<>();
        final List<ScarletData.CustomEvent> customEvents = new ArrayList<>();
        final Set<Path> unreadableFiles = new HashSet<>();
        final Set<String> blockedAuditIds = new HashSet<>();
        boolean fatal, unprocessed;
        int filesScanned;
        long bytesRead;

        Loader(File directory, String group, OffsetDateTime from, OffsetDateTime to, Set<String> types, Limits limits)
        {
            this.root = directory.toPath().toAbsolutePath().normalize();
            this.group = group; this.from = from; this.to = to;
            this.types = new HashSet<>(types); this.limits = limits;
        }

        void check() throws Stop
        {
            if (Thread.currentThread().isInterrupted()) throw new Stop("scan cancelled");
            if (System.nanoTime() - this.started >= TimeUnit.MILLISECONDS.toNanos(this.limits.milliseconds))
                throw new Stop("scan time limit reached");
        }

        void exactDirectory(Path directory) throws IOException
        {
            this.check();
            try
            {
                BasicFileAttributes attrs = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!directory.startsWith(this.root) || !attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()
                    || !directory.equals(directory.toRealPath()))
                    throw new Fatal("unsafe history directory");
            }
            catch (Fatal failure) { throw failure; }
            catch (IOException | RuntimeException failure) { throw new Fatal("history directory unavailable"); }
        }

        boolean inRange(OffsetDateTime time)
        {
            return time != null && !time.isBefore(this.from) && !time.isAfter(this.to);
        }

        void scan(String subdirectory) throws IOException
        {
            Path directory = this.root.resolve(subdirectory);
            if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return;
            this.exactDirectory(directory);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory))
            {
                for (Path path : stream)
                {
                    this.check();
                    if (++this.filesScanned > this.limits.files) throw new Stop("scan file-count limit reached");
                    String name = path.getFileName().toString();
                    if (ignored(name)) continue;
                    String id = "audit-outbox".equals(subdirectory) && name.endsWith(".json")
                        ? name.substring(0, name.length() - 5) : name;
                    try
                    {
                        if ("ex".equals(subdirectory)) this.readCustom(path, id);
                        else
                        {
                            if (!id.matches("[A-Za-z0-9_-]{1,160}")
                                || "audit-outbox".equals(subdirectory) && !name.endsWith(".json"))
                                throw new FileProblem("unexpected record filename");
                            ScarletData.AuditEntryMetadata metadata = this.readAuditSafely(path, id);
                            // An unreadable copy may contain a redaction or secret attribution.
                            // No readable receipt may revive that event during this scan.
                            if (metadata == null || this.blockedAuditIds.contains(id)) continue;
                            if ("audit-outbox".equals(subdirectory))
                            {
                                Path committed = this.root.resolve("gaud").resolve(id);
                                if (!Files.notExists(committed, LinkOption.NOFOLLOW_LINKS))
                                {
                                    ScarletData.AuditEntryMetadata authoritative = this.readAuditSafely(committed, id);
                                    if (authoritative == null) continue;
                                    if (!sameIdentityAndPrivacy(authoritative, metadata))
                                    {
                                        this.records.remove(id);
                                        throw new Fatal("conflicting audit identity or privacy receipts");
                                    }
                                    metadata = authoritative;
                                }
                                else if (!processed(metadata))
                                {
                                    if (this.group.equals(metadata.entry.getGroupId()) && this.inRange(metadata.entry.getCreatedAt())
                                        && this.types.contains(metadata.entry.getEventType()) && !training(metadata))
                                        this.unprocessed = true;
                                    continue;
                                }
                            }
                            if (this.group.equals(metadata.entry.getGroupId()) && this.inRange(metadata.entry.getCreatedAt())
                                && this.types.contains(metadata.entry.getEventType()) && !training(metadata))
                                this.records.put(id, metadata);
                        }
                        if ((long)this.records.size() + this.customEvents.size() > this.limits.records)
                            throw new Stop("scan record-count limit reached");
                    }
                    catch (Fatal failure) { throw failure; }
                    catch (IOException | RuntimeException failure)
                    {
                        this.unreadable(path, "ex".equals(subdirectory) ? null : id, failure);
                    }
                }
            }
            this.exactDirectory(directory);
        }

        void unreadable(Path path, String auditId, Throwable failure)
        {
            if (auditId != null)
            {
                this.blockedAuditIds.add(auditId);
                this.records.remove(auditId);
            }
            if (this.unreadableFiles.add(path.toAbsolutePath().normalize()))
                LOG.warn("Local moderation summary skipped unreadable file {}: {}. Other readable files will still be counted.",
                    diagnosticPath(path), diagnosticReason(failure));
        }

        ScarletData.AuditEntryMetadata readAuditSafely(Path path, String id) throws IOException
        {
            if (this.unreadableFiles.contains(path.toAbsolutePath().normalize())) return null;
            try { return this.readAudit(path, id); }
            catch (Fatal failure) { throw failure; }
            catch (IOException | RuntimeException failure)
            {
                this.unreadable(path, id, failure);
                return null;
            }
        }

        JsonReader open(Path path) throws IOException
        {
            this.check();
            this.exactDirectory(this.root);
            this.exactDirectory(path.getParent());
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || !path.equals(path.toRealPath()))
                throw new FileProblem("unsafe record type or link; not followed");
            if (attributes.size() > this.limits.fileBytes)
                throw new FileProblem("record exceeds per-file size limit");
            InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            return new JsonReader(new InputStreamReader(new FilterInputStream(input)
            {
                long count;
                void consumed(int n) throws IOException
                {
                    Loader.this.check();
                    if (n <= 0) return;
                    this.count += n;
                    Loader.this.bytesRead += n;
                    if (this.count > Loader.this.limits.fileBytes) throw new FileProblem("record exceeds per-file size limit");
                    if (Loader.this.bytesRead > Loader.this.limits.totalBytes) throw new Stop("scan total-byte limit reached");
                }
                @Override public int read() throws IOException
                {
                    Loader.this.check();
                    int value = this.in.read(); this.consumed(value < 0 ? -1 : 1); return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException
                {
                    Loader.this.check();
                    int count = this.in.read(bytes, offset, length); this.consumed(count); return count;
                }
            }, StandardCharsets.UTF_8));
        }

        ScarletData.AuditEntryMetadata readAudit(Path path, String id) throws IOException
        {
            ScarletData.AuditEntryMetadata metadata = new ScarletData.AuditEntryMetadata();
            try (JsonReader reader = this.open(path))
            {
                reader.beginObject();
                Set<String> fields = new HashSet<>();
                while (reader.hasNext())
                {
                    String field = field(reader, fields);
                    switch (field)
                    {
                    case "entry": metadata.entry = entry(reader); break;
                    case "entryRedacted": metadata.entryRedacted = reader.nextBoolean(); break;
                    case "auxActorId": metadata.auxActorId = string(reader); break;
                    case "auxActorDisplayName": metadata.auxActorDisplayName = string(reader); break;
                    case "parentEventId": metadata.parentEventId = string(reader); break;
                    case "auxData": metadata.auxData = deliveryState(reader); break;
                    default: reader.skipValue(); break;
                    }
                }
                reader.endObject();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing history data");
            }
            this.exactDirectory(path.getParent());
            if (metadata.entry == null || !id.equals(metadata.entry.getId()) || blank(metadata.entry.getGroupId())
                || blank(metadata.entry.getEventType()) || metadata.entry.getCreatedAt() == null)
                throw new IOException("Incomplete history record");
            return metadata;
        }

        void readCustom(Path path, String id) throws IOException
        {
            UUID encoded = UUID.fromString(id);
            if (!id.equals(encoded.toString())) throw new IOException("Invalid custom event identifier");
            int type = (int)encoded.getLeastSignificantBits();
            if (type != GroupAuditTypeEx.STAFF_JOIN.id.hashCode() && type != GroupAuditTypeEx.USER_JOIN.id.hashCode()) return;
            long time = encoded.getMostSignificantBits();
            if (time < this.from.toInstant().toEpochMilli() || time > this.to.toInstant().toEpochMilli()) return;
            ScarletData.CustomEvent event = new ScarletData.CustomEvent();
            try (JsonReader reader = this.open(path))
            {
                reader.beginObject();
                Set<String> fields = new HashSet<>();
                while (reader.hasNext())
                {
                    switch (field(reader, fields))
                    {
                    case "id": event.id = string(reader); break;
                    case "typeEx": event.typeEx = string(reader); break;
                    case "actorId": event.actorId = string(reader); break;
                    case "actorDisplayName": event.actorDisplayName = string(reader); break;
                    case "targetId": event.targetId = string(reader); break;
                    case "timestamp": event.timestamp = OffsetDateTime.parse(string(reader)); break;
                    default: reader.skipValue(); break;
                    }
                }
                reader.endObject();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing history data");
            }
            this.exactDirectory(path.getParent());
            if (!id.equals(event.id) || event.typeEx == null || event.typeEx.hashCode() != type
                || event.timestamp == null || event.timestamp.toInstant().toEpochMilli() != time || blank(event.actorId))
                throw new IOException("Incomplete custom event");
            if (!GroupAuditTypeEx.STAFF_JOIN.id.equals(event.typeEx) && !GroupAuditTypeEx.USER_JOIN.id.equals(event.typeEx)) return;
            if (trainingUser(event.actorId)) return;
            // Old unscoped local events cannot be assigned to the currently configured group.
            if (event.targetId == null || !event.targetId.contains("~group(")) return;
            String marker = "~group(" + this.group + ")";
            int at = event.targetId.indexOf(marker);
            if (at >= 0 && (at + marker.length() == event.targetId.length() || event.targetId.charAt(at + marker.length()) == '~')
                && event.targetId.indexOf("~group(") == at && event.targetId.indexOf("~group(", at + 1) < 0
                && this.inRange(event.timestamp))
                this.customEvents.add(event);
        }
    }

    // Diagnostic values are deliberately limited to local record names and fixed categories.
    // Never pass JSON content, exception messages, absolute paths or account data to the log.
    static String diagnosticPath(Path path)
    {
        String folder = path.getParent() == null ? "history" : path.getParent().getFileName().toString();
        if (!"gaud".equals(folder) && !"audit-outbox".equals(folder) && !"ex".equals(folder)) folder = "history";
        String name = path.getFileName().toString();
        if (!(name.matches("gaud_[A-Za-z0-9_-]{1,155}(?:\\.json)?")
            || "ex".equals(folder) && name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            name = "[unrecognized-entry-" + Integer.toHexString(name.hashCode()) + "]";
        return folder + "/" + name;
    }

    static String diagnosticReason(Throwable failure)
    {
        if (failure instanceof FileProblem) return failure.getMessage();
        if (failure instanceof NoSuchFileException) return "record disappeared before it could be read";
        if (failure instanceof AccessDeniedException || failure instanceof SecurityException) return "record access denied";
        return "record could not be read or contains malformed/incomplete data";
    }

    private static boolean ignored(String name)
    {
        return "backups".equals(name) || name.matches("[A-Za-z0-9_-]{1,160}(?:\\.json)?\\.[0-9]+\\.tmp")
            || name.matches("[A-Za-z0-9_-]{1,160}(?:\\.json)?\\.[0-9]{8}-[0-9]{6}\\.bak");
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static boolean trainingUser(String value) { return value != null && value.startsWith(ScarletSimulation.TRAINING_ID_PREFIX); }
    private static boolean training(ScarletData.AuditEntryMetadata metadata)
    {
        return metadata.entry.getId().startsWith(ScarletSimulation.TRAINING_AUDIT_ID_PREFIX)
            || trainingUser(metadata.entry.getActorId()) || trainingUser(metadata.auxActorId) || trainingUser(metadata.entry.getTargetId());
    }
    private static boolean processed(ScarletData.AuditEntryMetadata metadata)
    {
        String state = metadata.getAuxData("deliveryState", "pending", element -> element.getAsString());
        return "delivered".equals(state) || "suppressed".equals(state) || "followup_pending".equals(state);
    }

    private static boolean sameIdentityAndPrivacy(ScarletData.AuditEntryMetadata a, ScarletData.AuditEntryMetadata b)
    {
        return Objects.equals(a.entry.getId(), b.entry.getId()) && Objects.equals(a.entry.getGroupId(), b.entry.getGroupId())
            && Objects.equals(a.entry.getEventType(), b.entry.getEventType())
            && a.entry.getCreatedAt().isEqual(b.entry.getCreatedAt())
            && Objects.equals(a.entry.getActorId(), b.entry.getActorId()) && Objects.equals(a.entry.getTargetId(), b.entry.getTargetId())
            && Objects.equals(a.auxActorId, b.auxActorId) && a.entryRedacted == b.entryRedacted;
    }

    private static String field(JsonReader reader, Set<String> seen) throws IOException
    {
        String name = reader.nextName();
        if (!seen.add(name) || seen.size() > 64) throw new IOException("Duplicate or excessive history fields");
        return name;
    }

    private static String string(JsonReader reader) throws IOException
    {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null; }
        if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid history string");
        String value = reader.nextString();
        if (value.length() > 4096) throw new IOException("Oversized history field");
        return value;
    }

    private static GroupAuditLogEntryUnknown entry(JsonReader reader) throws IOException
    {
        GroupAuditLogEntryUnknown entry = new GroupAuditLogEntryUnknown();
        reader.beginObject();
        Set<String> fields = new HashSet<>();
        while (reader.hasNext())
        {
            switch (field(reader, fields))
            {
            case "id": entry.setId(string(reader)); break;
            case "groupId": entry.setGroupId(string(reader)); break;
            case "eventType": entry.setEventType(string(reader)); break;
            case "created_at": entry.setCreatedAt(OffsetDateTime.parse(string(reader))); break;
            case "actorId": entry.setActorId(string(reader)); break;
            case "actorDisplayName": entry.setActorDisplayName(string(reader)); break;
            case "targetId": entry.setTargetId(string(reader)); break;
            default: reader.skipValue(); break;
            }
        }
        reader.endObject();
        return entry;
    }

    private static JsonObject deliveryState(JsonReader reader) throws IOException
    {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null; }
        JsonObject result = new JsonObject();
        reader.beginObject();
        Set<String> fields = new HashSet<>();
        while (reader.hasNext())
        {
            String field = field(reader, fields);
            if ("deliveryState".equals(field)) result.addProperty(field, string(reader));
            else reader.skipValue();
        }
        reader.endObject();
        return result;
    }
}

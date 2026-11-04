package net.sybyline.scarlet;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.ToNumberPolicy;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import io.github.vrchatapi.model.GroupAuditLogEntry;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.PaginatedGroupAuditLogEntryList;

/**
 * Retains audit event data exactly as received, including unknown fields and explicit nulls.
 * The SDK's event union is normalized to its open-ended entry model for Scarlet's
 * processing and persistence. Scarlet interprets each event's data only after its type
 * is known, without losing fields absent from the generated event-specific models.
 */
public final class ScarletAuditData extends AbstractMap<String, Object>
{
    private final transient JsonObject raw;

    private ScarletAuditData(JsonObject raw)
    {
        this.raw = raw.deepCopy();
    }

    public static ScarletAuditData fromJson(JsonObject raw)
    {
        return raw == null ? null : new ScarletAuditData(raw);
    }

    public static JsonObject json(Object data)
    {
        if (data == null)
            return null;
        if (data instanceof ScarletAuditData)
            return ((ScarletAuditData)data).raw.deepCopy();
        // Also support explicitly constructed SDK data objects. Received data always
        // uses the raw representation and never takes this generated-model fallback.
        return Scarlet.GSON.newBuilder().serializeNulls().create().toJsonTree(data).getAsJsonObject();
    }

    @Override public Set<Map.Entry<String, Object>> entrySet()
    {
        Map<String, Object> values = Scarlet.GSON.newBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create().fromJson(this.raw,
            new TypeToken<Map<String, Object>>(){}.getType());
        return Collections.unmodifiableMap(values).entrySet();
    }

    /** Converts the SDK union at the HTTP boundary without coupling processors to its branches. */
    public static GroupAuditLogEntryUnknown normalize(GroupAuditLogEntry entry)
    {
        if (entry == null || entry.getActualInstance() == null)
            return null;
        Object actual = entry.getActualInstance();
        if (actual instanceof GroupAuditLogEntryUnknown)
            return (GroupAuditLogEntryUnknown)actual;
        return Scarlet.GSON.fromJson(json(actual), GroupAuditLogEntryUnknown.class);
    }

    public static List<GroupAuditLogEntryUnknown> entries(PaginatedGroupAuditLogEntryList page)
    {
        if (page == null || page.getResults() == null)
            return null;
        List<GroupAuditLogEntryUnknown> entries = new ArrayList<>();
        for (GroupAuditLogEntry entry : page.getResults())
            entries.add(normalize(entry));
        return entries;
    }

    /** Extra envelope fields are kept only when received; known fields remain mutable. */
    private static final class ExtendedEntry extends GroupAuditLogEntryUnknown
    {
        final transient JsonObject extra;
        ExtendedEntry(JsonObject extra) { this.extra = extra; }

        @Override public boolean equals(Object other)
        {
            return other instanceof ExtendedEntry && super.equals(other)
                && this.extra.equals(((ExtendedEntry)other).extra);
        }

        @Override public int hashCode()
        {
            return 31 * super.hashCode() + this.extra.hashCode();
        }
    }

    /** Registered in both the HTTP client and the local persistence Gson instances. */
    public static final class Factory implements TypeAdapterFactory
    {
        private static final TypeToken<List<GroupAuditLogEntry>> AUDIT_LIST = new TypeToken<List<GroupAuditLogEntry>>(){};
        private static final TypeToken<List<GroupAuditLogEntryUnknown>> FLAT_LIST = new TypeToken<List<GroupAuditLogEntryUnknown>>(){};
        private final boolean clientMetadata;

        public Factory() { this(false); }
        public Factory(boolean clientMetadata) { this.clientMetadata = clientMetadata; }

        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type)
        {
            Class<?> rawType = type.getRawType();
            // Client-side metadata copies must retain the same local format as disk
            // persistence, without the client's generic JSON-tree null filtering.
            if (this.clientMetadata && rawType == ScarletData.AuditEntryMetadata.class)
                return Scarlet.GSON.getAdapter(type);
            boolean auditList = AUDIT_LIST.equals(type), flatList = FLAT_LIST.equals(type);
            boolean flatEntry = GroupAuditLogEntryUnknown.class.isAssignableFrom(rawType);
            if (rawType != ScarletAuditData.class && !flatEntry
                && rawType != GroupAuditLogEntry.class && rawType != PaginatedGroupAuditLogEntryList.class && !auditList && !flatList)
                return null;
            TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
            TypeAdapter<?> adapter;
            if (rawType == PaginatedGroupAuditLogEntryList.class)
            {
                TypeAdapter<List<GroupAuditLogEntry>> entries = gson.getAdapter(AUDIT_LIST);
                TypeAdapter<Boolean> booleans = gson.getAdapter(Boolean.class);
                TypeAdapter<Integer> integers = gson.getAdapter(Integer.class);
                adapter = new TypeAdapter<PaginatedGroupAuditLogEntryList>() {
                    @Override public PaginatedGroupAuditLogEntryList read(JsonReader in) throws IOException
                    {
                        JsonObject obj = object(elements.read(in));
                        return new PaginatedGroupAuditLogEntryList()
                            .hasNext(readField(obj, "hasNext", booleans))
                            .results(readField(obj, "results", entries))
                            .totalCount(readField(obj, "totalCount", integers));
                    }

                    @Override public void write(JsonWriter out, PaginatedGroupAuditLogEntryList value) throws IOException
                    {
                        // Write nested entries directly: a generic JSON-tree rewrite with
                        // serializeNulls=false would erase explicit nulls inside audit data.
                        out.beginObject();
                        if (value.getHasNext() != null) out.name("hasNext").value(value.getHasNext());
                        if (value.getResults() != null) { out.name("results"); entries.write(out, value.getResults()); }
                        if (value.getTotalCount() != null) out.name("totalCount").value(value.getTotalCount());
                        out.endObject();
                    }
                };
            }
            else if (auditList || flatList)
            {
                @SuppressWarnings("unchecked") TypeAdapter<Object> entries = (TypeAdapter<Object>)(TypeAdapter<?>)(auditList
                    ? gson.getAdapter(GroupAuditLogEntry.class) : gson.getAdapter(GroupAuditLogEntryUnknown.class));
                adapter = new TypeAdapter<List<Object>>() {
                    @Override public List<Object> read(JsonReader in) throws IOException
                    {
                        List<Object> values = new ArrayList<>();
                        in.beginArray();
                        while (in.hasNext()) values.add(entries.read(in));
                        in.endArray();
                        return values;
                    }

                    @Override public void write(JsonWriter out, List<Object> values) throws IOException
                    {
                        out.beginArray();
                        for (Object value : values) entries.write(out, value);
                        out.endArray();
                    }
                };
            }
            else if (rawType == GroupAuditLogEntry.class)
            {
                TypeAdapter<GroupAuditLogEntryUnknown> entries = gson.getAdapter(GroupAuditLogEntryUnknown.class);
                adapter = new TypeAdapter<GroupAuditLogEntry>() {
                    @Override public GroupAuditLogEntry read(JsonReader in) throws IOException
                    {
                        GroupAuditLogEntryUnknown entry = entries.read(in);
                        return entry == null ? null : new GroupAuditLogEntry(entry);
                    }

                    @Override public void write(JsonWriter out, GroupAuditLogEntry entry) throws IOException
                    {
                        entries.write(out, normalize(entry));
                    }
                };
            }
            else if (flatEntry)
            {
                adapter = new TypeAdapter<GroupAuditLogEntryUnknown>() {
                    final TypeAdapter<String> strings = gson.getAdapter(String.class);
                    final TypeAdapter<OffsetDateTime> dates = gson.getAdapter(OffsetDateTime.class);
                    final TypeAdapter<ScarletAuditData> data = gson.getAdapter(ScarletAuditData.class);

                    @Override public GroupAuditLogEntryUnknown read(JsonReader in) throws IOException
                    {
                        JsonObject obj = object(elements.read(in));
                        JsonObject extra = obj.deepCopy();
                        for (String key : new String[]{"actorDisplayName", "actorId", "created_at", "description", "eventType", "groupId", "id", "targetId", "data"})
                            extra.remove(key);
                        GroupAuditLogEntryUnknown entry = extra.entrySet().isEmpty()
                            ? new GroupAuditLogEntryUnknown() : new ExtendedEntry(extra);
                        entry.setActorDisplayName(readField(obj, "actorDisplayName", strings));
                        entry.setActorId(readField(obj, "actorId", strings));
                        entry.setCreatedAt(readField(obj, "created_at", dates));
                        entry.setDescription(readField(obj, "description", strings));
                        entry.setEventType(readField(obj, "eventType", strings));
                        entry.setGroupId(readField(obj, "groupId", strings));
                        entry.setId(readField(obj, "id", strings));
                        entry.setTargetId(readField(obj, "targetId", strings));
                        entry.setData(readField(obj, "data", data));
                        return entry;
                    }

                    @Override public void write(JsonWriter out, GroupAuditLogEntryUnknown entry) throws IOException
                    {
                        JsonObject obj = entry instanceof ExtendedEntry ? ((ExtendedEntry)entry).extra.deepCopy() : new JsonObject();
                        put(obj, "actorDisplayName", strings.toJsonTree(entry.getActorDisplayName()));
                        put(obj, "actorId", strings.toJsonTree(entry.getActorId()));
                        put(obj, "created_at", dates.toJsonTree(entry.getCreatedAt()));
                        put(obj, "description", strings.toJsonTree(entry.getDescription()));
                        put(obj, "eventType", strings.toJsonTree(entry.getEventType()));
                        put(obj, "groupId", strings.toJsonTree(entry.getGroupId()));
                        put(obj, "id", strings.toJsonTree(entry.getId()));
                        put(obj, "targetId", strings.toJsonTree(entry.getTargetId()));
                        put(obj, "data", json(entry.getData()));
                        writeRaw(elements, out, obj);
                    }
                };
            }
            else
            {
                adapter = new TypeAdapter<ScarletAuditData>() {
                    @Override public ScarletAuditData read(JsonReader in) throws IOException
                    {
                        return new ScarletAuditData(object(elements.read(in)));
                    }

                    @Override public void write(JsonWriter out, ScarletAuditData value) throws IOException
                    {
                        writeRaw(elements, out, value.raw);
                    }
                };
            }
            @SuppressWarnings("unchecked") TypeAdapter<T> result = (TypeAdapter<T>)adapter.nullSafe();
            return result;
        }

        private static JsonObject object(JsonElement element)
        {
            if (element == null || !element.isJsonObject())
                throw new JsonParseException("VRChat audit entry/data must be a JSON object");
            return element.getAsJsonObject();
        }

        private static void put(JsonObject object, String name, JsonElement value)
        {
            if (value != null && !value.isJsonNull())
                object.add(name, value);
        }

        private static <T> T readField(JsonObject object, String name, TypeAdapter<T> adapter)
        {
            JsonElement value = object.get(name);
            return value == null || value.isJsonNull() ? null : adapter.fromJsonTree(value);
        }

        private static void writeRaw(TypeAdapter<JsonElement> adapter, JsonWriter out, JsonElement value) throws IOException
        {
            boolean serializeNulls = out.getSerializeNulls();
            try
            {
                out.setSerializeNulls(true);
                adapter.write(out, value);
            }
            finally
            {
                out.setSerializeNulls(serializeNulls);
            }
        }
    }
}

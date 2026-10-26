package net.sybyline.scarlet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Reads only the rendering preference, before any AWT, UI or normal settings initialization. */
final class ScarletRenderingCompatibility
{
    static final String SETTING_KEY = "windows_rendering_compatibility";
    static final String PROPERTY_KEY = "sun.java2d.d3d";
    static final int MAX_SETTINGS_BYTES = 4 * 1024 * 1024;
    static final int MAX_JSON_DEPTH = 32;

    private ScarletRenderingCompatibility() { }

    enum Result
    {
        NOT_WINDOWS(null),
        DISABLED(null),
        ENABLED("[Scarlet] Windows rendering compatibility enabled: Java Direct3D rendering is disabled for this launch."),
        EXPLICIT_JVM_OPTION("[Scarlet] An explicit Java Direct3D option is in effect; the saved rendering compatibility setting does not override it."),
        SETTINGS_UNAVAILABLE("[Scarlet] The rendering compatibility setting could not be read safely; default rendering options were retained."),
        PROPERTY_UNAVAILABLE("[Scarlet] Rendering compatibility could not be applied; existing Java rendering options were retained.");

        private final String diagnostic;

        Result(String diagnostic) { this.diagnostic = diagnostic; }

        String diagnostic() { return this.diagnostic; }
    }

    interface PropertyAccess
    {
        String get();
        void disableDirect3D();
    }

    interface SettingsSource
    {
        /** Returns null only when there is no saved settings file. */
        InputStream open() throws IOException;
    }

    static Result apply(File resolvedDataDirectory)
    {
        final boolean windows;
        try { windows = isWindows(System.getProperty("os.name", "")); }
        catch (SecurityException denied) { return Result.PROPERTY_UNAVAILABLE; }
        return apply(windows, new PropertyAccess()
        {
            public String get() { return System.getProperty(PROPERTY_KEY); }
            public void disableDirect3D() { System.setProperty(PROPERTY_KEY, "false"); }
        }, () -> openSettings(resolvedDataDirectory));
    }

    static boolean isWindows(String osName)
    {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static Result apply(boolean windows, PropertyAccess property, SettingsSource source)
    {
        if (!windows) return Result.NOT_WINDOWS;
        try
        {
            // Any explicit value, including an empty or unfamiliar one, belongs to the user.
            if (property.get() != null) return Result.EXPLICIT_JVM_OPTION;
        }
        catch (RuntimeException denied) { return Result.PROPERTY_UNAVAILABLE; }

        final boolean enabled;
        try (InputStream input = source.open())
        {
            if (input == null) return Result.DISABLED;
            enabled = readEnabled(input);
        }
        catch (IOException | RuntimeException invalid) { return Result.SETTINGS_UNAVAILABLE; }

        if (!enabled) return Result.DISABLED;
        try
        {
            // Do not overwrite an option supplied while the bounded settings read was running.
            if (property.get() != null) return Result.EXPLICIT_JVM_OPTION;
            property.disableDirect3D();
            return Result.ENABLED;
        }
        catch (RuntimeException denied) { return Result.PROPERTY_UNAVAILABLE; }
    }

    static InputStream openSettings(File resolvedDataDirectory) throws IOException
    {
        if (resolvedDataDirectory == null) throw new IOException("Data directory unavailable");
        Path root = resolvedDataDirectory.toPath();
        final BasicFileAttributes directory;
        try { directory = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { return null; }
        if (!directory.isDirectory() || directory.isSymbolicLink())
            throw new IOException("Unsupported data directory");

        Path settings = root.resolve("settings.json");
        final BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(settings, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { return null; }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() > MAX_SETTINGS_BYTES)
            throw new IOException("Unsupported settings file");
        // NOFOLLOW also covers replacement by a link between the attribute check and open.
        return Channels.newInputStream(Files.newByteChannel(settings, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    static boolean readEnabled(InputStream input) throws IOException
    {
        byte[] bytes = readBounded(input);
        try (JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))))
        {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("Settings must be an object");
            reader.beginObject();
            Set<String> names = new HashSet<>();
            boolean enabled = false;
            while (reader.hasNext())
            {
                String name = reader.nextName();
                if (!names.add(name)) throw new IOException("Duplicate setting");
                if (SETTING_KEY.equals(name))
                {
                    if (reader.peek() != JsonToken.BOOLEAN) throw new IOException("Invalid rendering preference");
                    enabled = reader.nextBoolean();
                }
                else consumeValue(reader, 1);
            }
            reader.endObject();
            // Validate the entire document before applying even a previously read true value.
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Unexpected settings content");
            return enabled;
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (;;)
        {
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_SETTINGS_BYTES - total + 1));
            if (count < 0) return bytes.toByteArray();
            if (count == 0)
            {
                int single = input.read();
                if (single < 0) return bytes.toByteArray();
                buffer[0] = (byte) single;
                count = 1;
            }
            total += count;
            if (total > MAX_SETTINGS_BYTES) throw new IOException("Settings exceed rendering-reader limit");
            bytes.write(buffer, 0, count);
        }
    }

    private static void consumeValue(JsonReader reader, int depth) throws IOException
    {
        if (depth > MAX_JSON_DEPTH) throw new IOException("Settings nesting exceeds rendering-reader limit");
        switch (reader.peek())
        {
        case BEGIN_OBJECT:
            reader.beginObject();
            Set<String> names = new HashSet<>();
            while (reader.hasNext())
            {
                if (!names.add(reader.nextName())) throw new IOException("Duplicate object field");
                consumeValue(reader, depth + 1);
            }
            reader.endObject();
            break;
        case BEGIN_ARRAY:
            reader.beginArray();
            while (reader.hasNext()) consumeValue(reader, depth + 1);
            reader.endArray();
            break;
        case STRING:
        case NUMBER:
            reader.nextString();
            break;
        case BOOLEAN:
            reader.nextBoolean();
            break;
        case NULL:
            reader.nextNull();
            break;
        default:
            throw new IOException("Unexpected settings value");
        }
    }
}

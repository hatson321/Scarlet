package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.sybyline.scarlet.ScarletRenderingCompatibility.Result;

/** Synthetic settings and properties only; never reads a user's config or initializes the UI. */
class ScarletRenderingCompatibilityTest
{
    private static final String KEY = ScarletRenderingCompatibility.SETTING_KEY;
    private static final String ENABLED = "{\"" + KEY + "\":true}";
    private static final String PRIVATE_DETAIL = "private-token C:\\Users\\PrivatePerson\\settings.json";

    static final class FakeProperty implements ScarletRenderingCompatibility.PropertyAccess
    {
        String value;
        int gets, sets;
        boolean failRead, failWrite;
        public String get()
        {
            this.gets++;
            if (this.failRead) throw new SecurityException(PRIVATE_DETAIL);
            return this.value;
        }
        public void disableDirect3D()
        {
            if (this.failWrite) throw new SecurityException(PRIVATE_DETAIL);
            this.sets++;
            this.value = "false";
        }
    }

    static InputStream json(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }

    static Result apply(String value, FakeProperty property)
    {
        return ScarletRenderingCompatibility.apply(true, property, () -> json(value));
    }

    @Test void missingFileAndMissingSettingKeepDefaultWithoutSettingAnyProperty()
    {
        FakeProperty property = new FakeProperty();
        assertEquals(Result.DISABLED, ScarletRenderingCompatibility.apply(true, property, () -> null));
        assertEquals(Result.DISABLED, apply("{}", property));
        assertEquals(Result.DISABLED, apply("{\"other\":true}", property));
        assertNull(property.value);
        assertEquals(0, property.sets);
    }

    @Test void savedFalseKeepsDefaultAndTrueDisablesOnlyDirect3D()
    {
        FakeProperty property = new FakeProperty();
        assertEquals(Result.DISABLED, apply("{\"" + KEY + "\":false}", property));
        assertEquals(Result.ENABLED, apply(ENABLED, property));
        assertEquals("false", property.value);
        assertEquals(1, property.sets);
    }

    @Test void nonWindowsDoesNotReadSettingsOrProperties()
    {
        FakeProperty property = new FakeProperty();
        property.failRead = true;
        assertEquals(Result.NOT_WINDOWS, ScarletRenderingCompatibility.apply(false, property,
            () -> { throw new AssertionError("Non-Windows must not open settings"); }));
        assertEquals(0, property.gets);
        assertEquals(0, property.sets);
    }

    @Test void windowsDetectionDoesNotMistakeDarwinForWindows()
    {
        for (String name : new String[]{"Windows 10", "Windows 11", "WINDOWS Server"})
            assertTrue(ScarletRenderingCompatibility.isWindows(name), name);
        for (String name : new String[]{"Linux", "Mac OS X", "Darwin", "", null})
            assertFalse(ScarletRenderingCompatibility.isWindows(name), name);
    }

    @Test void everyExplicitJvmValueWinsWithoutOpeningSettings()
    {
        for (String value : new String[]{"true", "false", "", "custom-value"})
        {
            FakeProperty property = new FakeProperty();
            property.value = value;
            assertEquals(Result.EXPLICIT_JVM_OPTION, ScarletRenderingCompatibility.apply(true, property,
                () -> { throw new AssertionError("Explicit option must win before settings read"); }));
            assertEquals(value, property.value);
            assertEquals(0, property.sets);
        }
    }

    @Test void optionSetDuringSettingsReadIsNotOverwritten()
    {
        FakeProperty property = new FakeProperty();
        assertEquals(Result.EXPLICIT_JVM_OPTION, ScarletRenderingCompatibility.apply(true, property,
            () -> { property.value = "true"; return json(ENABLED); }));
        assertEquals("true", property.value);
        assertEquals(0, property.sets);
    }

    @Test void repeatedBootstrapDoesNotMutateOrClearAnExistingOption()
    {
        FakeProperty property = new FakeProperty();
        assertEquals(Result.ENABLED, apply(ENABLED, property));
        assertEquals(Result.EXPLICIT_JVM_OPTION, apply("{\"" + KEY + "\":false}", property));
        assertEquals("false", property.value);
        assertEquals(1, property.sets);
    }

    @Test void nonBooleanPreferencesAreNotCoerced()
    {
        for (String value : new String[]{"\"true\"", "\"false\"", "1", "0", "null", "[]", "{}"})
        {
            FakeProperty property = new FakeProperty();
            assertEquals(Result.SETTINGS_UNAVAILABLE, apply("{\"" + KEY + "\":" + value + "}", property), value);
            assertEquals(0, property.sets);
        }
    }

    @Test void entireStrictDocumentMustBeValidBeforeEnabling()
    {
        for (String value : new String[]{"", "null", "[]", "true", "{", ENABLED + "{}", ENABLED + "garbage",
            "{\"" + KEY + "\":true,}", "{\"" + KEY + "\":true,\"broken\":[}",
            "{/*comment*/\"" + KEY + "\":true}", "{\"" + KEY + "\":True}",
            "{'" + KEY + "':true}", "{" + KEY + ":true}",
            "{\"" + KEY + "\":true,\"bad\":NaN}", "{\"" + KEY + "\":true,\"bad\":01}"})
        {
            FakeProperty property = new FakeProperty();
            assertEquals(Result.SETTINGS_UNAVAILABLE, apply(value, property), value);
            assertEquals(0, property.sets);
        }
    }

    @Test void duplicateRootAndNestedFieldsAreRejected()
    {
        for (String value : new String[]{"{\"" + KEY + "\":true,\"" + KEY + "\":false}",
            "{\"" + KEY + "\":true,\"other\":1,\"other\":2}",
            "{\"" + KEY + "\":true,\"other\":{\"field\":1,\"field\":2}}"})
        {
            FakeProperty property = new FakeProperty();
            assertEquals(Result.SETTINGS_UNAVAILABLE, apply(value, property));
            assertEquals(0, property.sets);
        }
    }

    @Test void unknownNestedValuesAreValidatedWithoutBeingApplied()
    {
        FakeProperty property = new FakeProperty();
        String value = "{\"other\":[{\"text\":\"hello \\u2603\",\"number\":-1.5e+2},true,false,null,[]],\"" + KEY + "\":true}";
        assertEquals(Result.ENABLED, apply(value, property));
        assertEquals(1, property.sets);
    }

    @Test void excessiveNestingIsRejectedWithoutStackOverflow()
    {
        StringBuilder value = new StringBuilder("{\"").append(KEY).append("\":true,\"nested\":");
        for (int i = 0; i < ScarletRenderingCompatibility.MAX_JSON_DEPTH + 2; i++) value.append('[');
        value.append('0');
        for (int i = 0; i < ScarletRenderingCompatibility.MAX_JSON_DEPTH + 2; i++) value.append(']');
        value.append('}');
        FakeProperty property = new FakeProperty();
        assertEquals(Result.SETTINGS_UNAVAILABLE, apply(value.toString(), property));
        assertEquals(0, property.sets);
    }

    @Test void byteLimitAppliesEvenWhenSourceMetadataWouldHaveReportedSomethingSmaller()
    {
        AtomicInteger consumed = new AtomicInteger();
        InputStream oversized = new InputStream()
        {
            public int read() { consumed.incrementAndGet(); return ' '; }
            public int read(byte[] bytes, int offset, int count)
            {
                Arrays.fill(bytes, offset, offset + count, (byte)' ');
                consumed.addAndGet(count);
                return count;
            }
        };
        FakeProperty property = new FakeProperty();
        assertEquals(Result.SETTINGS_UNAVAILABLE, ScarletRenderingCompatibility.apply(true, property, () -> oversized));
        assertEquals(ScarletRenderingCompatibility.MAX_SETTINGS_BYTES + 1, consumed.get());
        assertEquals(0, property.sets);
    }

    @Test void exactByteLimitIsAcceptedWhenDocumentIsValid()
    {
        byte[] bytes = new byte[ScarletRenderingCompatibility.MAX_SETTINGS_BYTES];
        Arrays.fill(bytes, (byte)' ');
        byte[] json = ENABLED.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(json, 0, bytes, 0, json.length);
        FakeProperty property = new FakeProperty();
        assertEquals(Result.ENABLED, ScarletRenderingCompatibility.apply(true, property, () -> new ByteArrayInputStream(bytes)));
    }

    @Test void malformedUtf8IsNotSilentlyReplaced()
    {
        byte[] prefix = ("{\"" + KEY + "\":true,\"text\":\"").getBytes(StandardCharsets.UTF_8);
        byte[] bytes = Arrays.copyOf(prefix, prefix.length + 4);
        bytes[prefix.length] = (byte)0xc3;
        bytes[prefix.length + 1] = (byte)0x28;
        bytes[prefix.length + 2] = '"';
        bytes[prefix.length + 3] = '}';
        FakeProperty property = new FakeProperty();
        assertEquals(Result.SETTINGS_UNAVAILABLE, ScarletRenderingCompatibility.apply(true, property, () -> new ByteArrayInputStream(bytes)));
        assertEquals(0, property.sets);
    }

    @Test void streamsAreClosedOnSuccessAndParseFailure()
    {
        for (String value : new String[]{ENABLED, "{"})
        {
            AtomicBoolean closed = new AtomicBoolean();
            InputStream stream = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))
            {
                public void close() throws IOException { closed.set(true); super.close(); }
            };
            ScarletRenderingCompatibility.apply(true, new FakeProperty(), () -> stream);
            assertTrue(closed.get());
        }
    }

    @Test void openReadAndCloseFailuresDoNotApplyTheSettingOrExposePrivateText()
    {
        FakeProperty property = new FakeProperty();
        assertEquals(Result.SETTINGS_UNAVAILABLE, ScarletRenderingCompatibility.apply(true, property,
            () -> { throw new IOException(PRIVATE_DETAIL); }));
        assertEquals(Result.SETTINGS_UNAVAILABLE, ScarletRenderingCompatibility.apply(true, property,
            () -> new InputStream() { public int read() throws IOException { throw new IOException(PRIVATE_DETAIL); } }));
        assertEquals(Result.SETTINGS_UNAVAILABLE, ScarletRenderingCompatibility.apply(true, property,
            () -> new ByteArrayInputStream(ENABLED.getBytes(StandardCharsets.UTF_8))
            {
                public void close() throws IOException { throw new IOException(PRIVATE_DETAIL); }
            }));
        assertEquals(0, property.sets);
    }

    @Test void propertyAccessFailuresAreSafeAndDoNotEscape()
    {
        FakeProperty property = new FakeProperty();
        property.failRead = true;
        assertEquals(Result.PROPERTY_UNAVAILABLE, apply(ENABLED, property));
        property.failRead = false;
        property.failWrite = true;
        assertEquals(Result.PROPERTY_UNAVAILABLE, apply(ENABLED, property));
        assertEquals(0, property.sets);
        assertNull(property.value);
    }

    @Test void everyDiagnosticUsesFixedSingleLineTextWithoutSettingsOrExceptionContent()
    {
        for (Result result : Result.values())
        {
            String text = result.diagnostic();
            if (text == null) continue;
            assertFalse(text.contains("private-token"));
            assertFalse(text.contains("PrivatePerson"));
            assertFalse(text.contains("settings.json"));
            assertFalse(text.contains("\n"));
            assertFalse(text.contains("\r"));
        }
    }

    @Test void localReaderUsesOnlyTheProvidedDataDirectoryWithoutMutatingSettings(@TempDir Path root) throws Exception
    {
        Path home = Files.createDirectory(root.resolve("synthetic-home"));
        Path sibling = Files.createDirectory(root.resolve("other-home"));
        byte[] saved = ENABLED.getBytes(StandardCharsets.UTF_8);
        Files.write(home.resolve("settings.json"), saved);
        Files.write(sibling.resolve("settings.json"), "{\"other\":true}".getBytes(StandardCharsets.UTF_8));
        FakeProperty property = new FakeProperty();
        assertEquals(Result.ENABLED, ScarletRenderingCompatibility.apply(true, property,
            () -> ScarletRenderingCompatibility.openSettings(home.toFile())));
        assertArrayEquals(saved, Files.readAllBytes(home.resolve("settings.json")));
        assertEquals(Result.DISABLED, ScarletRenderingCompatibility.apply(true, new FakeProperty(),
            () -> ScarletRenderingCompatibility.openSettings(sibling.toFile())));
    }

    @Test void localReaderTreatsAbsentFolderOrFileAsDisabledAndRefusesDirectories(@TempDir Path root) throws Exception
    {
        assertNull(ScarletRenderingCompatibility.openSettings(root.resolve("missing").toFile()));
        assertNull(ScarletRenderingCompatibility.openSettings(root.toFile()));
        Files.createDirectory(root.resolve("settings.json"));
        assertThrows(IOException.class, () -> ScarletRenderingCompatibility.openSettings(root.toFile()));
        Path regular = Files.write(root.resolve("not-a-directory"), new byte[]{1});
        assertThrows(IOException.class, () -> ScarletRenderingCompatibility.openSettings(regular.toFile()));
        assertThrows(IOException.class, () -> ScarletRenderingCompatibility.openSettings(null));
    }

    @Test void helperClassDoesNotDependOnUiNormalSettingsOrLoggerInitialization() throws Exception
    {
        try (InputStream input = ScarletRenderingCompatibility.class.getResourceAsStream("ScarletRenderingCompatibility.class"))
        {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) bytes.write(buffer, 0, count);
            String constantPool = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
            for (String forbidden : new String[]{"java/awt/", "javax/swing/", "org/slf4j/", "ScarletSettings", "ScarletLogger"})
                assertFalse(constantPool.contains(forbidden), forbidden);
        }
    }
}

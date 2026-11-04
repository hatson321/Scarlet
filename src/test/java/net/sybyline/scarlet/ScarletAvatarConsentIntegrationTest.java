package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.dv8tion.jda.api.modals.Modal;
import net.sybyline.scarlet.ext.AvatarSearch;
import net.sybyline.scarlet.ui.AvatarProviderConsentDialog;

/** Offline integration checks: no application constructors, GUI windows or provider requests. */
class ScarletAvatarConsentIntegrationTest
{
    @TempDir Path directory;

    private static <T> T inert(Class<T> type) throws Exception
    {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe)field.get(null)).allocateInstance(type));
    }

    private static void put(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private ScarletSettings settings() throws Exception
    {
        ScarletSettings settings = inert(ScarletSettings.class);
        put(settings, "fileValuedSettings", new HashMap<>());
        put(settings, "settingsFile", this.directory.resolve("settings.json").toFile());
        return settings;
    }

    private ScarletSettings.FileValuedConsentBoolean permission(ScarletSettings settings)
    {
        return settings.new FileValuedConsentBoolean(AvatarSearch.IDENTIFIED_LOOKUP_CONSENT_KEY, "Avatar permission");
    }

    private ScarletDiscordJDA discord(ScarletSettings settings, ScarletSettings.FileValuedConsentBoolean consent) throws Exception
    {
        ScarletDiscordJDA discord = inert(ScarletDiscordJDA.class);
        put(discord, "avatarSearchExternalLookupsEnabled", consent);
        put(discord, "avatarSearchProvidersEnabled", settings.new FileValuedBoolean("custom_avatar_search_providers_enabled", "Custom providers", false));
        put(discord, "avatarSearchProviders", settings.new FileValuedStringArrayPattern("custom_avatar_search_providers", "Providers", AvatarSearch.URL_ROOTS.clone(), "https?://.+", false));
        return discord;
    }

    @Test void actualOldConsentKeyDoesNotAuthorizeTheNewGate() throws Exception
    {
        Files.writeString(this.directory.resolve("settings.json"), "{\"avatar_search_identified_lookups_enabled\":true}");
        assertEquals("avatar_search_identified_lookups_consent_v2", AvatarSearch.IDENTIFIED_LOOKUP_CONSENT_KEY);
        ScarletSettings settings = this.settings();
        ScarletSettings.FileValuedConsentBoolean consent = this.permission(settings);
        ScarletDiscordJDA discord = this.discord(settings, consent);
        assertFalse(consent.get());
        assertEquals(0, discord.getAvatarSearchProviders().length);
        assertFalse(consent.set(true, "discord"));
        assertEquals(0, discord.getAvatarSearchProviders().length);
    }

    @Test void actualDiscordAgreementEnablesAndRevocationDisablesSelectedProviders() throws Exception
    {
        ScarletSettings settings = this.settings();
        ScarletSettings.FileValuedConsentBoolean consent = this.permission(settings);
        ScarletDiscordJDA discord = this.discord(settings, consent);
        discord.avatarSearchProviders.set(new String[]{"https://selected.invalid/search"}, "test");
        discord.avatarSearchProvidersEnabled.set(true, "test");
        assertEquals(0, discord.getAvatarSearchProviders().length);
        assertTrue(ScarletDiscordCommands.applyAvatarProviderConsent(consent, "agree"));
        assertArrayEquals(new String[]{"https://selected.invalid/search"}, discord.getAvatarSearchProviders());
        assertTrue(ScarletDiscordCommands.applyAvatarProviderConsent(consent, "false"));
        assertFalse(consent.get());
        assertEquals(0, discord.getAvatarSearchProviders().length);
    }

    @Test void malformedAndGenericTrueDiscordValuesCannotGrantConsent() throws Exception
    {
        ScarletSettings.FileValuedConsentBoolean consent = this.permission(this.settings());
        for (String value : new String[]{null, "", "true", "True", "TRUE", "1", "Agree", "agree ", "yes"})
        {
            assertFalse(ScarletDiscordCommands.applyAvatarProviderConsent(consent, value));
            assertFalse(consent.get());
        }
        assertTrue(ScarletDiscordCommands.applyAvatarProviderConsent(consent, "false"));
        assertFalse(consent.get());
    }

    @Test void providerChangesAndRestoreDefaultsNeverGrantConsent() throws Exception
    {
        ScarletSettings settings = this.settings();
        ScarletSettings.FileValuedConsentBoolean consent = this.permission(settings);
        ScarletDiscordJDA discord = this.discord(settings, consent);
        discord.avatarSearchProvidersEnabled.set(true, "discord");
        discord.avatarSearchProviders.set(new String[]{"https://selected.invalid/search"}, "discord");
        assertEquals(0, discord.getAvatarSearchProviders().length);
        discord.resetAvatarSearchProviders();
        assertFalse(consent.get());
        assertEquals(0, discord.getAvatarSearchProviders().length);
        discord.avatarSearchProvidersEnabled.set(false, "discord");
        assertEquals(0, discord.getAvatarSearchProviders().length);
    }

    @Test void newConsentPersistsWithoutReusingTheOldPermissionKey() throws Exception
    {
        ScarletSettings.FileValuedConsentBoolean consent = this.permission(this.settings());
        assertTrue(ScarletDiscordCommands.applyAvatarProviderConsent(consent, "agree"));
        ScarletSettings reopened = this.settings();
        assertTrue(this.permission(reopened).get());
        assertEquals(Boolean.TRUE, reopened.getObject(AvatarSearch.IDENTIFIED_LOOKUP_CONSENT_KEY, Boolean.class));
        assertNull(reopened.getObject("avatar_search_identified_lookups_enabled", Boolean.class));
    }

    @Test void discordModalDisclosesSharingAndDefaultsToDisabled()
    {
        Modal modal = ScarletDiscordCommands.avatarProviderConsentModal(AvatarSearch.IDENTIFIED_LOOKUP_CONSENT_KEY);
        JsonObject data = JsonParser.parseString(modal.toData().toString()).getAsJsonObject();
        assertEquals(AvatarProviderConsentDialog.TITLE, data.get("title").getAsString());
        assertTrue(containsStringField(data, "content", AvatarProviderConsentDialog.DISCLOSURE));
        JsonObject menu = findByCustomId(data.get("components"), AvatarSearch.IDENTIFIED_LOOKUP_CONSENT_KEY);
        assertNotNull(menu);
        JsonArray options = menu.getAsJsonArray("options");
        assertEquals(2, options.size());
        JsonObject disabled = options.get(0).getAsJsonObject();
        JsonObject agreement = options.get(1).getAsJsonObject();
        assertEquals("false", disabled.get("value").getAsString());
        assertTrue(disabled.get("default").getAsBoolean());
        assertEquals("agree", agreement.get("value").getAsString());
        assertFalse(agreement.has("default") && agreement.get("default").getAsBoolean());
        assertTrue(agreement.get("label").getAsString().contains("IDs and Scarlet version"));
    }

    @Test void sharedDisclosureExplainsTheIdentifiersPurposeAndChoice()
    {
        String disclosure = AvatarProviderConsentDialog.DISCLOSURE;
        for (String required : new String[]{"Scarlet's version", "VRChat group ID", "primary VRChat account's user ID",
                "not the person being searched", "database abuse or excessive traffic", "selected avatar database providers",
                "Passwords", "login cookies", "Discord bot tokens", "Cancelling does not enable lookups"})
            assertTrue(disclosure.contains(required), "Missing disclosure: " + required);
    }

    @Test void headlessConfirmationCannotGrantPermission() throws Exception
    {
        String previous = System.getProperty("java.awt.headless");
        System.setProperty("java.awt.headless", "true");
        try
        {
            assertFalse(AvatarProviderConsentDialog.confirm());
            ScarletSettings.FileValuedConsentBoolean consent = this.permission(this.settings());
            assertFalse(consent.setWithConsent(true, "ui", AvatarProviderConsentDialog::confirm));
            assertFalse(consent.get());
        }
        finally
        {
            if (previous == null) System.clearProperty("java.awt.headless");
            else System.setProperty("java.awt.headless", previous);
        }
    }

    @Test void avatarSearchSectionIncludesConsentAndCurrentManageControl() throws Exception
    {
        Field field = ScarletUI.class.getDeclaredField("SETTINGS_SECTIONS");
        field.setAccessible(true);
        String[][] sections = (String[][])field.get(null);
        String[] section = Arrays.stream(sections).filter(row -> "Avatar Search".equals(row[0])).findFirst().orElseThrow(AssertionError::new);
        assertTrue(Arrays.asList(section).contains(AvatarSearch.IDENTIFIED_LOOKUP_CONSENT_KEY));
        assertTrue(Arrays.asList(section).contains("Avatar search providers"));
        assertFalse(Arrays.asList(section).contains("Reset avatar search providers to default"));
    }

    @Test void realDesktopCheckboxCannotBypassHeadlessDenialAndCanRevoke() throws Exception
    {
        String previous = System.getProperty("java.awt.headless");
        System.setProperty("java.awt.headless", "true");
        try
        {
            ScarletSettings.FileValuedConsentBoolean consent = this.permission(this.settings());
            ScarletUI ui = inert(ScarletUI.class);
            put(ui, "ssettings", new java.util.ArrayList<>());
            Class<?> controlType = Class.forName("net.sybyline.scarlet.ScarletUI$BoolSetting");
            java.lang.reflect.Constructor<?> constructor = controlType.getDeclaredConstructor(ScarletUI.class, ScarletSettings.FileValued.class);
            constructor.setAccessible(true);
            java.util.concurrent.atomic.AtomicReference<javax.swing.JCheckBox> checkbox = new java.util.concurrent.atomic.AtomicReference<>();
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                try
                {
                    Object control = constructor.newInstance(ui, consent);
                    Field render = controlType.getSuperclass().getDeclaredField("render");
                    render.setAccessible(true);
                    checkbox.set((javax.swing.JCheckBox)render.get(control));
                    checkbox.get().doClick();
                    assertFalse(checkbox.get().isSelected());
                }
                catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
            });
            assertFalse(consent.get());
            assertTrue(consent.setWithConsent(true, "test", () -> true));
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                assertTrue(checkbox.get().isSelected());
                checkbox.get().doClick();
                assertFalse(checkbox.get().isSelected());
            });
            assertFalse(consent.get());
        }
        finally
        {
            if (previous == null) System.clearProperty("java.awt.headless");
            else System.setProperty("java.awt.headless", previous);
        }
    }

    private static JsonObject findByCustomId(JsonElement value, String id)
    {
        if (value == null) return null;
        if (value.isJsonObject())
        {
            JsonObject object = value.getAsJsonObject();
            if (object.has("custom_id") && id.equals(object.get("custom_id").getAsString())) return object;
            for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet())
            {
                JsonObject found = findByCustomId(entry.getValue(), id);
                if (found != null) return found;
            }
        }
        else if (value.isJsonArray())
            for (JsonElement child : value.getAsJsonArray())
            {
                JsonObject found = findByCustomId(child, id);
                if (found != null) return found;
            }
        return null;
    }

    private static boolean containsStringField(JsonElement value, String key, String expected)
    {
        if (value == null) return false;
        if (value.isJsonObject())
        {
            JsonObject object = value.getAsJsonObject();
            if (object.has(key) && object.get(key).isJsonPrimitive() && expected.equals(object.get(key).getAsString())) return true;
            for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet())
                if (containsStringField(entry.getValue(), key, expected)) return true;
        }
        else if (value.isJsonArray())
            for (JsonElement child : value.getAsJsonArray())
                if (containsStringField(child, key, expected)) return true;
        return false;
    }
}

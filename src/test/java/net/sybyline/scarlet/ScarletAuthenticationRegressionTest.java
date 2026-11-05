package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.put;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.vrchatapi.*;
import net.sybyline.scarlet.util.EncryptedPrefs;
import okhttp3.*;

/** Production primary/alternate login flow, fake HTTP and in-memory preferences only. */
class ScarletAuthenticationRegressionTest
{
    static final String ID = ScarletVRChatApi122CompatibilityTest.USER;
    static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    @TempDir Path directory;

    static class Settings extends ScarletSettings
    {
        int prompts;
        Settings(Path file) { super(null, file.toFile(), false); }
        @Override public String requireInput(String label, boolean sensitive)
        {
            assertEquals("Totp code", label);
            if (++prompts > 1) throw new AssertionError("Unexpected repeated prompt");
            return "123456";
        }
    }
    static class Client extends ScarletVRChat
    {
        Client() { super(null, null, null); throw new AssertionError("No real client construction"); }
        @Override public void save() { }
        @Override void updateGroupInfo() { }
    }
    final class Fixture implements AutoCloseable
    {
        final Client vrc = inert(Client.class);
        final Settings settings = new Settings(directory.resolve(UUID.randomUUID() + ".json"));
        final Preferences prefs = new TestPreferencesFactory().userRoot().node("authentication-" + UUID.randomUUID());
        final EncryptedPrefs encrypted = new EncryptedPrefs(prefs, "synthetic-test-password", directory.resolve(UUID.randomUUID().toString()).toFile());
        final com.google.gson.Gson previousGson;
        final ApiClient api;
        boolean challenged, hasSecret, rejectAutomatic;
        int verifications;

        Fixture(boolean secret, boolean reject) throws Exception
        {
            hasSecret = secret; rejectAutomatic = reject;
            put(settings, "globalPreferences", prefs); put(settings, "preferences", prefs);
            put(settings, "globalEncrypted", encrypted); put(settings, "encrypted", encrypted);
            if (secret)
            {
                encrypted.putVerified("totpsecret", SECRET);
                encrypted.putVerified("alt:" + ID + ":totpsecret", SECRET);
            }
            encrypted.putVerified("alt:" + ID + ":username", "saved-user");
            encrypted.putVerified("alt:" + ID + ":password", "synthetic-saved-password");
            Configuration.getDefaultApiClient(); previousGson = JSON.getGson();
            OkHttpClient http = new OkHttpClient.Builder().dns(host -> { throw new AssertionError("No network permitted"); })
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    assertEquals("authentication.invalid", request.url().host());
                    String body;
                    switch (request.url().encodedPath()) {
                    case "/api/1/time": body = "\"" + java.time.Instant.now() + "\""; break;
                    case "/api/1/auth": body = "{\"ok\":false,\"token\":\"synthetic\"}"; break;
                    case "/api/1/auth/user":
                        if (!challenged) { challenged = true; body = "{\"requiresTwoFactorAuth\":[\"totp\"]}"; }
                        else body = ScarletVRChatApi122CompatibilityTest.currentUser("[]");
                        break;
                    case "/api/1/auth/twofactorauth/totp/verify":
                        verifications++;
                        body = "{\"verified\":" + (!rejectAutomatic || settings.prompts > 0) + "}";
                        break;
                    default: throw new AssertionError("Unexpected synthetic request: " + request.url().encodedPath());
                    }
                    return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                        .header("Content-Type", "application/json").body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
                }).build();
            api = new ApiClient(http).setBasePath("https://authentication.invalid/api/1");
            ScarletVRChat.initJson();
            Scarlet scarlet = inert(Scarlet.class); put(scarlet, "settings", settings); put(scarlet, "running", true);
            put(vrc, "scarlet", scarlet); put(vrc, "client", api);
            put(vrc, "totpsecret", settings.new RegistryStringEncrypted("totpsecret", true));
            put(vrc, "cookies", new ScarletVRChatCookieJar(directory.resolve("unused-cookies.txt").toFile(), new ScarletCookieMigrationTest.Stores()));
        }
        void login(boolean alternate) throws Exception
        {
            Method method = ScarletVRChat.class.getDeclaredMethod(alternate ? "loginAlt" : "login", alternate ? new Class<?>[]{String.class, boolean.class} : new Class<?>[]{boolean.class});
            method.setAccessible(true);
            Object result = method.invoke(vrc, alternate ? new Object[]{ID, false} : new Object[]{false});
            assertEquals(ID, alternate ? result : vrc.currentUserId);
        }
        public void close() throws Exception
        {
            JSON.setGson(previousGson); prefs.removeNode();
            api.getHttpClient().connectionPool().evictAll(); api.getHttpClient().dispatcher().executorService().shutdownNow();
        }
    }

    @Test void automaticSuccessSkipsManualPromptForPrimaryAndAlternate() throws Exception
    {
        for (boolean alternate : new boolean[]{false, true}) try (Fixture f = new Fixture(true, false))
        {
            f.login(alternate);
            assertEquals(1, f.verifications); assertEquals(0, f.settings.prompts);
            assertEquals("saved-user", f.encrypted.get("alt:" + ID + ":username"));
            assertEquals("synthetic-saved-password", f.encrypted.get("alt:" + ID + ":password"));
        }
    }
    @Test void missingSecretStillPromptsAndVerifiesForBothAccounts() throws Exception
    {
        for (boolean alternate : new boolean[]{false, true}) try (Fixture f = new Fixture(false, false))
        {
            f.login(alternate); assertEquals(1, f.verifications); assertEquals(1, f.settings.prompts);
        }
    }
    @Test void rejectedAutomaticCodesAreBoundedAndFallBackToManualVerification() throws Exception
    {
        for (boolean alternate : new boolean[]{false, true}) try (Fixture f = new Fixture(true, true))
        {
            f.login(alternate); assertEquals(3, f.verifications); assertEquals(1, f.settings.prompts);
        }
    }
    @Test void savedAlternateCredentialsLoadAndCannotBeReboundToAnotherAccount() throws Exception
    {
        try (Fixture f = new Fixture(true, false); ScarletVRChat.AltCred credentials = f.vrc.new AltCred(ID))
        {
            assertFalse(credentials.isNew);
            assertEquals("saved-user", credentials.getU());
            assertEquals("synthetic-saved-password", credentials.getP());
            assertEquals(SECRET, credentials.getS());
            assertFalse(credentials.init("usr_00000000-0000-0000-0000-000000000099"));
            credentials.updateUP(ID, null, null);
            assertEquals("saved-user", credentials.getU());
        }
    }
}

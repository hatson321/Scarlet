package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

class ScarletAlternateCredentialRemovalTest
{
    @TempDir Path temp;

    private static class MemoryStores implements ScarletVRChatCookieJar.Stores
    {
        String primary = "";
        Map<String, List<String>> alternates = new HashMap<>();
        final Map<String, String> loginValues = new HashMap<>();
        boolean failDelete;
        boolean failSave;

        public String primary() { return primary; }
        public void primary(String value) { primary = value; }
        public Map<String, List<String>> alternates() { return alternates; }
        public void alternates(Map<String, List<String>> value)
        {
            if (failSave) throw new IllegalStateException("synthetic storage failure");
            alternates = new HashMap<>();
            value.forEach((key, cookies) -> alternates.put(key, new ArrayList<>(cookies)));
        }
        public void removeLoginValue(String key)
        {
            if (failDelete) throw new IllegalStateException("synthetic deletion failure");
            loginValues.remove(key);
        }
        public boolean persistOnShutdown() { return true; }
    }

    private ScarletVRChatCookieJar jar(MemoryStores stores)
    {
        return new ScarletVRChatCookieJar(temp.resolve("unused-legacy-cookie-file").toFile(), stores);
    }

    private static Cookie cookie(String value)
    {
        return new Cookie.Builder().name("auth").value(value).hostOnlyDomain(HttpUrl.parse(Scarlet.API_URL_2).host())
            .path("/").expiresAt(2_200_000_000_000L).build();
    }

    private static void loginValues(MemoryStores stores, String id)
    {
        for (String suffix : new String[] {"username", "password", "totpsecret"})
            stores.loginValues.put("alt:"+id+":"+suffix, "synthetic");
    }

    @Test
    void lastAlternateAndAllItsLoginValuesStayRemovedAfterReload()
    {
        MemoryStores stores = new MemoryStores();
        ScarletVRChatCookieJar jar = jar(stores);
        jar.add(cookie("main"));
        jar.addAlt("alternate", cookie("alternate"));
        loginValues(stores, "alternate");
        jar.save();
        String mainBefore = stores.primary;
        assertTrue(jar.removeAlt("alternate"));
        assertTrue(stores.alternates.isEmpty());
        assertTrue(stores.loginValues.isEmpty());
        assertEquals(mainBefore, stores.primary);
        ScarletVRChatCookieJar reloaded = jar(stores);
        reloaded.load();
        assertTrue(reloaded.alts().isEmpty());
        assertEquals(1, reloaded.copy().size());
        assertEquals("main", reloaded.copy().get(0).value());
    }

    @Test
    void removingOneAlternateLeavesOtherAndMainCredentialsUntouched()
    {
        MemoryStores stores = new MemoryStores();
        ScarletVRChatCookieJar jar = jar(stores);
        jar.addAlt("remove", cookie("remove"));
        jar.addAlt("keep", cookie("keep"));
        loginValues(stores, "remove");
        loginValues(stores, "keep");
        stores.loginValues.put("username", "main-account");
        jar.save();
        jar.removeAlt("remove");
        ScarletVRChatCookieJar reloaded = jar(stores);
        reloaded.load();
        assertEquals(java.util.Collections.singleton("keep"), reloaded.alts());
        assertEquals(4, stores.loginValues.size());
        assertEquals("main-account", stores.loginValues.get("username"));
    }

    @Test
    void lateCookieResponseCannotRecreateRemovedAlternateOrUseMainCookies()
    {
        MemoryStores stores = new MemoryStores();
        ScarletVRChatCookieJar jar = jar(stores);
        jar.add(cookie("main"));
        jar.addAlt("removed", cookie("removed"));
        jar.removeAlt("removed");
        assertFalse(jar.addAlt("removed", cookie("late-response")));
        ScarletVRChatCookieJar.contextRun("removed", () ->
            assertTrue(jar.loadForRequest(HttpUrl.parse(Scarlet.API_URL_2)).isEmpty()));
        jar.save();
        assertTrue(stores.alternates.isEmpty());
        assertThrows(IllegalStateException.class, () -> jar.new AltCredContext("removed"));
    }

    @Test
    void explicitNewLoginCanReAddAnAccount() throws Exception
    {
        MemoryStores stores = new MemoryStores();
        ScarletVRChatCookieJar jar = jar(stores);
        jar.addAlt("readd", cookie("old"));
        jar.removeAlt("readd");
        try (ScarletVRChatCookieJar.AltCredContext login = jar.new AltCredContext(null))
        {
            jar.add(cookie("new"));
            assertTrue(login.transferTo("readd"));
        }
        assertFalse(jar.isRemoved("readd"));
        jar.save();
        assertTrue(stores.alternates.containsKey("readd"));
    }

    @Test
    void failedStorageDeletionIsReportedAndRemainsAvailableForRetry()
    {
        MemoryStores stores = new MemoryStores();
        ScarletVRChatCookieJar jar = jar(stores);
        jar.addAlt("retry", cookie("old"));
        loginValues(stores, "retry");
        jar.save();
        stores.failDelete = true;
        assertThrows(IllegalStateException.class, () -> jar.removeAlt("retry"));
        assertTrue(jar.alts().contains("retry"));
        assertTrue(jar.isRemoved("retry"));
        stores.failDelete = false;
        jar.removeAlt("retry");
        assertTrue(stores.alternates.isEmpty());
        assertTrue(stores.loginValues.isEmpty());
    }

    @Test
    void failedCookieMapSaveIsNotReportedAsSuccessfulRemoval()
    {
        MemoryStores stores = new MemoryStores();
        ScarletVRChatCookieJar jar = jar(stores);
        jar.addAlt("retry", cookie("old"));
        jar.save();
        stores.failSave = true;
        assertThrows(IllegalStateException.class, () -> jar.removeAlt("retry"));
        stores.failSave = false;
        jar.removeAlt("retry");
        assertTrue(stores.alternates.isEmpty());
    }
}

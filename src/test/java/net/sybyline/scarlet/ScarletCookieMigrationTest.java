package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletCookieMigrationTest
{
    @TempDir Path temp;
    static class Stores implements ScarletVRChatCookieJar.Stores
    {
        String value = "";
        boolean failRead, failWrite, failAlternates, dropWrite;
        public String primary() { if (failRead) throw new IllegalStateException("synthetic read failure"); return value; }
        public void primary(String next) { if (failWrite) throw new IllegalStateException("synthetic write failure"); if (!dropWrite) value = next; }
        public Map<String,List<String>> alternates() { return new HashMap<>(); }
        public void alternates(Map<String,List<String>> next) { if (failAlternates) throw new IllegalStateException("synthetic alternate failure"); }
        public void removeLoginValue(String key) { }
        public boolean persistOnShutdown() { return false; }
    }
    Path legacy(String text) throws Exception
    {
        Path file = temp.resolve("legacy.txt"); Files.writeString(file, text); return file;
    }
    @Test void successPersistsBeforeRemovingLegacyAndReloads() throws Exception
    {
        Path file = legacy("auth=synthetic; path=/\n"); Stores stores = new Stores();
        new ScarletVRChatCookieJar(file.toFile(), stores).load();
        assertFalse(Files.exists(file));
        ScarletVRChatCookieJar reloaded = new ScarletVRChatCookieJar(file.toFile(), stores); reloaded.load();
        assertEquals("synthetic", reloaded.copy().get(0).value());
    }
    @Test void readWriteAndAlternateFailuresPreserveOriginal() throws Exception
    {
        for (int mode = 0; mode < 3; mode++)
        {
            Path file = legacy("auth=synthetic; path=/\n"); Stores stores = new Stores();
            stores.failRead = mode == 0; stores.failWrite = mode == 1; stores.failAlternates = mode == 2;
            assertThrows(IllegalStateException.class, () -> new ScarletVRChatCookieJar(file.toFile(), stores).load());
            assertEquals("auth=synthetic; path=/\n", Files.readString(file));
        }
    }
    @Test void silentWriteFailureDoesNotDeleteLegacy() throws Exception
    {
        Path file = legacy("auth=synthetic; path=/\n"); Stores stores = new Stores(); stores.dropWrite = true;
        assertThrows(IllegalStateException.class, () -> new ScarletVRChatCookieJar(file.toFile(), stores).load());
        assertTrue(Files.exists(file));
    }
    @Test void malformedLegacyIsRetainedWithoutPartiallyImportingIt() throws Exception
    {
        Path file = legacy("auth=synthetic; path=/\ninvalid-cookie\n"); Stores stores = new Stores();
        ScarletVRChatCookieJar jar = new ScarletVRChatCookieJar(file.toFile(), stores); jar.load();
        assertTrue(Files.exists(file)); assertTrue(jar.copy().isEmpty()); assertEquals("", stores.value);
    }
    @Test void retryPreservesNewerEncryptedCookie() throws Exception
    {
        Path file = legacy("auth=older; path=/\n"); Stores stores = new Stores(); stores.value = "auth=newer; path=/\n";
        ScarletVRChatCookieJar jar = new ScarletVRChatCookieJar(file.toFile(), stores); jar.load();
        assertFalse(Files.exists(file)); assertEquals("newer", jar.copy().get(0).value());
    }
    @Test void malformedEncryptedStoreDoesNotRemoveLegacy() throws Exception
    {
        Path file = legacy("auth=synthetic; path=/\n"); Stores stores = new Stores(); stores.value = "invalid-cookie";
        new ScarletVRChatCookieJar(file.toFile(), stores).load();
        assertTrue(Files.exists(file)); assertEquals("invalid-cookie", stores.value);
    }
    @Test void migrationAlwaysLoadsPrimaryAccount() throws Exception
    {
        Path file = legacy("auth=primary; path=/\n"); Stores stores = new Stores();
        ScarletVRChatCookieJar jar = new ScarletVRChatCookieJar(file.toFile(), stores);
        ScarletVRChatCookieJar.contextRun("alternate", jar::load);
        assertEquals("primary", jar.copy().get(0).value());
        assertTrue(stores.value.contains("auth=primary"));
    }

}

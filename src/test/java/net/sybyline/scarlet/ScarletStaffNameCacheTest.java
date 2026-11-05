package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.vrchatapi.model.User;

class ScarletStaffNameCacheTest
{
    static final String ID = "usr_00000000-0000-0000-0000-000000000001";
    @TempDir Path directory;
    static class Api extends ScarletVRChat
    {
        Api() { super(null, null, null); }
        int calls;
        String name;
        @Override public User getUser(String id) { ++calls; return name == null ? null : new User().displayName(name); }
    }
    @Test void ordinaryStaffCachesRefreshesAndEvicts() throws Exception
    {
        long[] clock = {1L};
        ScarletStaffList list = new ScarletStaffList(directory.resolve("staff.json").toFile()) {
            @Override long nameCacheNow() { return clock[0]; }
        };
        Api api = ScarletDiscordSafetyTest.inert(Api.class); api.name = "First";
        list.addStaffId(ID); list.populateStaffNames(api);
        assertEquals("First", list.getStaffName(null, ID));
        assertEquals("First", list.getStaffName(api, ID)); assertEquals(1, api.calls);
        clock[0] += 3_600_000L; api.name = "Second";
        assertEquals("Second", list.getStaffName(api, ID)); assertEquals(2, api.calls);
        clock[0] += 3_600_000L; api.name = null;
        assertEquals("Second", list.getStaffName(api, ID));
        list.removeStaffId(ID); assertNull(list.getStaffName(null, ID));
        list.addStaffId(ID); assertEquals(ID, list.getStaffName(null, ID));
    }
    @Test void confidentialStaffHasSameBoundedCacheWithoutPersistingNames() throws Exception
    {
        long[] clock = {1L}; Path file = directory.resolve("secret.json");
        ScarletSecretStaffList list = new ScarletSecretStaffList(file.toFile()) {
            @Override long nameCacheNow() { return clock[0]; }
        };
        Api api = ScarletDiscordSafetyTest.inert(Api.class); api.name = "Private display name";
        list.addSecretStaffId(ID); list.populateSecretStaffNames(api);
        assertEquals(api.name, list.getSecretStaffName(null, ID));
        assertEquals(api.name, list.getSecretStaffName(api, ID)); assertEquals(1, api.calls);
        assertFalse(java.nio.file.Files.readString(file).contains(api.name));
        clock[0] += 3_600_000L; api.name = "Updated";
        assertEquals("Updated", list.getSecretStaffName(api, ID)); assertEquals(2, api.calls);
        list.removeSecretStaffId(ID); assertNull(list.getSecretStaffName(null, ID));
        list.addSecretStaffId(ID); assertEquals(ID, list.getSecretStaffName(null, ID));
    }
}

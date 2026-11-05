package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletWatchedPersistenceTest
{
    @TempDir Path temp;
    static class Groups extends ScarletWatchedGroups
    {
        boolean refuse;
        Groups(File file) { super(file); }
        @Override public synchronized boolean save() { return !refuse && super.save(); }
    }
    static class Entities extends ScarletWatchedEntities<Object>
    {
        boolean refuse;
        Entities(File file) { super(file, "usr_", null); }
        @Override public synchronized boolean save() { return !refuse && super.save(); }
    }
    static class Staff extends ScarletStaffList
    {
        boolean refuse;
        Staff(File file) { super(file); }
        @Override public synchronized boolean save() { return !refuse && super.save(); }
    }

    @Test void groupsRollbackFailedImportsEditsRemovalsAndRestores()
    {
        File file = temp.resolve("groups.json").toFile(); Groups store = new Groups(file);
        var group = new ScarletWatchedGroups.WatchedGroup(); group.id = "grp_test";
        group.message = "translated"; group.messageOriginal = "original";
        store.addWatchedGroup(group.id, group);
        var old = store.getWatchedGroup(group.id);
        var edit = store.getWatchedGroup(group.id); edit.notes = "saved note";
        store.updateWatchedGroup(edit);
        assertThrows(IllegalStateException.class, () -> store.updateWatchedGroup(old));
        store.refuse = true;
        edit = store.getWatchedGroup(group.id); edit.notes = "lost";
        final var rejected = edit;
        assertThrows(IllegalStateException.class, () -> store.updateWatchedGroup(rejected));
        assertEquals("saved note", store.getWatchedGroup(group.id).notes);
        assertThrows(IllegalStateException.class, store::restoreAdvisories);
        assertEquals("translated", store.getWatchedGroup(group.id).message);
        assertThrows(IllegalStateException.class, () -> store.removeWatchedGroup(group.id));
        var imported = new ScarletWatchedGroups.WatchedGroup(); imported.id = "grp_new";
        assertThrows(IllegalStateException.class, () -> store.importWatchedGroups(List.of(imported), true));
        assertNull(store.getWatchedGroup(imported.id));
        assertEquals("saved note", new Groups(file).getWatchedGroup(group.id).notes);
    }

    @Test void entitiesRejectFailedWritesWithoutLeakingMutableReferences()
    {
        File file = temp.resolve("entities.json").toFile(); Entities store = new Entities(file);
        var entry = new ScarletWatchedEntities.WatchedEntity(); entry.id = "usr_test"; entry.notes = "saved";
        store.addWatchedEntity(entry.id, entry);
        entry.notes = "outside mutation";
        assertEquals("saved", store.getWatchedEntity(entry.id).notes);
        store.refuse = true;
        var edit = store.getWatchedEntity(entry.id); edit.notes = "lost";
        assertThrows(IllegalStateException.class, () -> store.updateWatchedEntity(edit));
        assertThrows(IllegalStateException.class, () -> store.removeWatchedEntity(entry.id));
        var imported = new ScarletWatchedEntities.WatchedEntity(); imported.id = "usr_new";
        assertThrows(IllegalStateException.class, () -> store.importWatchedEntities(List.of(imported), true));
        assertNull(store.getWatchedEntity(imported.id));
        assertEquals("saved", new Entities(file).getWatchedEntity(entry.id).notes);
    }

    @Test void staffPermissionsFollowDurableMembership()
    {
        File file = temp.resolve("staff.json").toFile(); Staff store = new Staff(file);
        store.addStaffId("usr_saved"); store.refuse = true;
        assertThrows(IllegalStateException.class, () -> store.addStaffId("usr_unsaved"));
        assertFalse(store.isStaffId("usr_unsaved"));
        assertThrows(IllegalStateException.class, () -> store.removeStaffId("usr_saved"));
        assertTrue(store.isStaffId("usr_saved"));
        assertArrayEquals(new String[]{"usr_saved"}, new Staff(file).getStaffIds());
    }
}

package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletCorruptListPreservationTest
{
    @TempDir Path directory;

    @Test void corruptExistingListsStopStartupWithoutReplacingTheirBytes() throws Exception
    {
        assertStartupPreserves("groups.json", file -> new ScarletWatchedGroups(file.toFile()));
        assertStartupPreserves("entities.json", file -> new ScarletWatchedEntities<Object>(file.toFile(), "usr_", null));
        assertStartupPreserves("staff.json", file -> new ScarletStaffList(file.toFile()));
        assertFalse(Files.exists(directory.resolve("backups")), "A failed read must not generate replacement backups");
    }

    private void assertStartupPreserves(String name, Function<Path, Object> create) throws Exception
    {
        Path file = directory.resolve(name);
        for (String malformed : new String[] { "[", "null", "", "  \r\n" })
        {
            Files.writeString(file, malformed);
            assertThrows(IllegalStateException.class, () -> create.apply(file), name);
            assertEquals(malformed, Files.readString(file));
        }
        Files.writeString(file, "[]");
        assertNotNull(create.apply(file), "An explicitly empty JSON array remains a valid list");
    }

    @Test void failedReloadBlocksSavesUntilAValidFileIsLoaded() throws Exception
    {
        Path groupsFile = directory.resolve("groups.json"), entitiesFile = directory.resolve("entities.json"), staffFile = directory.resolve("staff.json");
        ScarletWatchedGroups groups = new ScarletWatchedGroups(groupsFile.toFile());
        ScarletWatchedEntities<Object> entities = new ScarletWatchedEntities<>(entitiesFile.toFile(), "usr_", null);
        ScarletStaffList staff = new ScarletStaffList(staffFile.toFile());
        String broken = "[\"synthetic-recoverable-record\"";
        for (Path file : new Path[] { groupsFile, entitiesFile, staffFile }) Files.writeString(file, broken);
        assertFalse(groups.load()); assertFalse(entities.load()); assertFalse(staff.load());
        for (int attempt = 0; attempt < 12; attempt++)
        { assertFalse(groups.save()); assertFalse(entities.save()); assertFalse(staff.save()); }
        for (Path file : new Path[] { groupsFile, entitiesFile, staffFile }) assertEquals(broken, Files.readString(file));
        for (Path file : new Path[] { groupsFile, entitiesFile, staffFile }) Files.delete(file);
        assertFalse(groups.load()); assertFalse(entities.load()); assertFalse(staff.load());
        for (Path file : new Path[] { groupsFile, entitiesFile, staffFile }) assertFalse(Files.exists(file));
        Files.writeString(groupsFile, "[{\"id\":\"grp_saved\",\"notes\":\"preserved group\"}]");
        Files.writeString(entitiesFile, "[{\"id\":\"usr_saved\",\"notes\":\"preserved entity\"}]");
        Files.writeString(staffFile, "[\"usr_saved\"]");
        assertTrue(groups.load()); assertTrue(entities.load()); assertTrue(staff.load());
        assertEquals("preserved group", groups.getWatchedGroup("grp_saved").notes);
        assertEquals("preserved entity", entities.getWatchedEntity("usr_saved").notes);
        assertTrue(staff.isStaffId("usr_saved"));
        assertTrue(groups.save()); assertTrue(entities.save()); assertTrue(staff.save());
    }

    @Test void existingDirectoryIsNotTreatedAsAnAbsentList() throws Exception
    {
        Path blocked = Files.createDirectory(directory.resolve("blocked.json"));
        Path sentinel = Files.writeString(blocked.resolve("keep.txt"), "synthetic preserved child");
        assertThrows(IllegalStateException.class, () -> new ScarletWatchedGroups(blocked.toFile()));
        assertThrows(IllegalStateException.class, () -> new ScarletWatchedEntities<Object>(blocked.toFile(), "usr_", null));
        assertThrows(IllegalStateException.class, () -> new ScarletStaffList(blocked.toFile()));
        assertEquals("synthetic preserved child", Files.readString(sentinel));
    }

    @Test void corruptModerationTagsCannotBeOverwrittenByAnEdit() throws Exception
    {
        Path file = directory.resolve("tags.json");
        for (String malformed : new String[] { "[", "null", "", "[null]" })
        {
            Files.writeString(file, malformed);
            ScarletModerationTags tags = new ScarletModerationTags(file.toFile());
            for (int attempt = 0; attempt < 12; attempt++)
            {
                assertThrows(java.io.UncheckedIOException.class, () -> tags.addOrUpdateTag("new", "New", "must not replace"));
                assertThrows(java.io.UncheckedIOException.class, tags::saveJson);
                assertEquals(malformed, Files.readString(file));
            }
            Files.delete(file);
            assertThrows(java.io.UncheckedIOException.class, tags::getTags);
            assertFalse(Files.exists(file));
            Files.writeString(file, "[{\"value\":\"old\",\"label\":\"Saved\",\"description\":\"Preserve\"}]");
            assertEquals(0, tags.addOrUpdateTag("new", "New", "added after repair"));
            assertEquals(2, new ScarletModerationTags(file.toFile()).getTags().size());
        }
    }
}

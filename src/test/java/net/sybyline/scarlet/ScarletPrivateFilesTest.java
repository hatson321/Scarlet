package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScarletPrivateFilesTest
{
    @TempDir Path directory;

    @Test void unsupportedProtectionFailsClosed()
    {
        IOException failure = assertThrows(IOException.class, () -> ScarletPrivateFiles.protect(true, null, null));
        assertTrue(failure.getMessage().contains("cannot enforce private migration storage"));
    }

    @Test void filesystemWithoutOwnerPermissionsRejectsStagingAndPlaintextPublication() throws Exception
    {
        Path archive = directory.resolve("synthetic-no-permissions.zip");
        java.net.URI uri = java.net.URI.create("jar:" + archive.toUri());
        try (java.nio.file.FileSystem filesystem = java.nio.file.FileSystems.newFileSystem(uri, Collections.singletonMap("create", "true")))
        {
            Path root = filesystem.getPath("/");
            assertThrows(IOException.class, () -> ScarletPrivateFiles.directory(root, ".scarlet-private-"));
            Path complete = Files.writeString(directory.resolve("synthetic-complete"), "synthetic private content");
            assertThrows(IOException.class, () -> ScarletMigration.publishNewExport(complete, root.resolve("must-not-publish")));
            try (java.util.stream.Stream<Path> children = Files.list(root))
            { assertEquals(0, children.count(), "Protection failure must precede sensitive writes"); }
        }
    }

    @Test void windowsAclRemovesOtherReadersAndProtectsNewChildren() throws Exception
    {
        MemoryAcl acl = new MemoryAcl();
        ScarletPrivateFiles.protect(true, null, acl);
        assertEquals(1, acl.entries.size());
        AclEntry entry = acl.entries.get(0);
        assertEquals(acl.owner, entry.principal());
        assertEquals(AclEntryType.ALLOW, entry.type());
        assertTrue(entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class)));
        assertEquals(EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT), entry.flags());
    }

    @Test void deniedIgnoredOrChangedOwnerAclCannotAuthorizeSensitiveWrites()
    {
        MemoryAcl denied = new MemoryAcl(); denied.deny = true;
        assertThrows(IOException.class, () -> ScarletPrivateFiles.protect(true, null, denied));
        MemoryAcl ignored = new MemoryAcl(); ignored.ignore = true;
        assertThrows(IOException.class, () -> ScarletPrivateFiles.protect(true, null, ignored));
        MemoryAcl changed = new MemoryAcl(); changed.changeOwner = true;
        assertThrows(IOException.class, () -> ScarletPrivateFiles.protect(true, null, changed));
    }

    @Test void actualPrivateWorkspaceProtectsFilesBeforeContentAndRemovesThem() throws Exception
    {
        Path workspace, plaintext;
        try (ScarletPrivateFiles.Workspace privateDirectory = ScarletPrivateFiles.directory(directory, ".scarlet-private-test-"))
        {
            workspace = privateDirectory.path;
            assertPrivate(workspace);
            plaintext = ScarletPrivateFiles.file(workspace, "synthetic-", ".dat");
            assertEquals(0, Files.size(plaintext));
            assertPrivate(plaintext);
            Files.writeString(plaintext, "synthetic private staging only");
        }
        assertFalse(Files.exists(workspace));
        assertFalse(Files.exists(plaintext));
    }

    static void assertPrivate(Path path) throws IOException
    {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null)
        {
            Set<PosixFilePermission> expected = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) expected.add(PosixFilePermission.OWNER_EXECUTE);
            assertEquals(expected, posix.readAttributes().permissions());
        }
        else
        {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            assertNotNull(acl, "Plaintext storage must support enforceable permissions");
            List<AclEntry> entries = acl.getAcl();
            assertEquals(1, entries.size(), "No other principal may receive an allow entry");
            assertEquals(acl.getOwner(), entries.get(0).principal());
            assertEquals(AclEntryType.ALLOW, entries.get(0).type());
        }
    }

    private static final class MemoryAcl implements AclFileAttributeView
    {
        UserPrincipal owner = () -> "synthetic-owner";
        List<AclEntry> entries = Collections.singletonList(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
            .setPrincipal((UserPrincipal) () -> "synthetic-other-reader").setPermissions(AclEntryPermission.READ_DATA).build());
        boolean deny, ignore, changeOwner;
        @Override public String name() { return "acl"; }
        @Override public List<AclEntry> getAcl() { return new ArrayList<>(entries); }
        @Override public void setAcl(List<AclEntry> value) throws IOException
        {
            if (deny) throw new IOException("Synthetic ACL write rejection");
            if (!ignore) entries = new ArrayList<>(value);
            if (changeOwner) owner = () -> "synthetic-different-owner";
        }
        @Override public UserPrincipal getOwner() { return owner; }
        @Override public void setOwner(UserPrincipal value) { owner = value; }
    }
}

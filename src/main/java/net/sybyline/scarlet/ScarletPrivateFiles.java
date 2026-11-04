package net.sybyline.scarlet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Private migration scratch. An empty path is secured and verified before any secrets enter it. */
final class ScarletPrivateFiles
{
    private ScarletPrivateFiles() { }

    static Workspace workspace(String prefix) throws IOException
    {
        return directory(Path.of(System.getProperty("java.io.tmpdir")), prefix);
    }

    static Workspace directory(Path parent, String prefix) throws IOException
    {
        Path directory = Files.createTempDirectory(parent.toRealPath(), prefix);
        try { protect(directory); return new Workspace(directory); }
        catch (IOException | RuntimeException failure)
        {
            try { Files.deleteIfExists(directory); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static final class Workspace implements AutoCloseable
    {
        final Path path;
        private boolean retained;
        private Workspace(Path path) { this.path = path; }
        void retain() { this.retained = true; }
        @Override public void close() throws IOException
        {
            if (this.retained) return;
            // Staging is deliberately flat. Never recursively follow a newly introduced alias.
            try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(this.path))
            { for (Path file : files) Files.deleteIfExists(file); }
            Files.deleteIfExists(this.path);
        }
    }

    static Path file(Path parent, String prefix, String suffix) throws IOException
    {
        Path file = Files.createTempFile(parent, prefix, suffix);
        try { protect(file); return file; }
        catch (IOException | RuntimeException failure)
        {
            try { Files.deleteIfExists(file); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static void protect(Path path) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if ((!attributes.isRegularFile() && !attributes.isDirectory()) || attributes.isSymbolicLink() || attributes.isOther())
            throw new IOException("Private migration storage must be an exact regular file or directory");
        protect(attributes.isDirectory(),
            Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS),
            Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS));
    }

    /** Attribute-view seam lets tests verify rejection and read-back without accessing user ACLs. */
    static void protect(boolean directory, PosixFileAttributeView posix, AclFileAttributeView acl) throws IOException
    {
        if (posix != null)
        {
            Set<PosixFilePermission> expected = permissions(directory);
            posix.setPermissions(expected);
            if (!expected.equals(posix.readAttributes().permissions()))
                throw new IOException("Owner-only migration permissions failed verification");
        }
        else if (acl != null)
        {
            UserPrincipal owner = acl.getOwner();
            List<AclEntry> expected = ownerAcl(owner, directory);
            acl.setAcl(expected);
            if (!owner.equals(acl.getOwner()) || !expected.equals(acl.getAcl()))
                throw new IOException("Owner-only migration ACL failed verification");
        }
        else throw new IOException("This filesystem cannot enforce private migration storage; choose a private temporary folder with ACL or POSIX permission support");
    }

    /** Initial creation attributes prevent a plaintext publication handle from ever being public. */
    static FileAttribute<?>[] fileAttributes(Path privateTemplate, Path destinationParent) throws IOException
    {
        if (Files.getFileAttributeView(destinationParent, PosixFileAttributeView.class) != null)
            return new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(permissions(false)) };
        if (Files.getFileAttributeView(destinationParent, AclFileAttributeView.class) != null)
        {
            final List<AclEntry> value = ownerAcl(Files.getOwner(privateTemplate, LinkOption.NOFOLLOW_LINKS), false);
            return new FileAttribute<?>[] { new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return value; }
            } };
        }
        throw new IOException("Plaintext exports require a destination that can enforce owner-only permissions");
    }

    private static Set<PosixFilePermission> permissions(boolean directory)
    {
        Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE);
        return permissions;
    }

    private static List<AclEntry> ownerAcl(UserPrincipal owner, boolean directory)
    {
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        return Collections.singletonList(entry.build());
    }
}

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
        requireExactParent(path);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if ((!attributes.isRegularFile() && !attributes.isDirectory()) || attributes.isSymbolicLink() || attributes.isOther())
            throw new IOException("Private migration storage must be an exact regular file or directory");
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        protect(attributes.isDirectory(),
            Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS),
            acl, acl == null ? null : currentWindowsUser(path));
    }

    /** Attribute-view seam lets tests verify rejection and read-back without accessing user ACLs. */
    static void protect(boolean directory, PosixFileAttributeView posix, AclFileAttributeView acl) throws IOException
    {
        protect(directory, posix, acl, acl == null ? null : acl.getOwner());
    }

    static void protect(boolean directory, PosixFileAttributeView posix, AclFileAttributeView acl, UserPrincipal reader) throws IOException
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
            List<AclEntry> expected = ownerAcl(reader, directory);
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
            final List<AclEntry> value = ownerAcl(currentWindowsUser(destinationParent), false);
            return new FileAttribute<?>[] { new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return value; }
            } };
        }
        throw new IOException("Plaintext exports require a destination that can enforce owner-only permissions");
    }

    static UserPrincipal currentWindowsUser(Path path) throws IOException
    {
        // Windows may assign Administrators as owner of files created elevated.
        com.sun.security.auth.module.NTSystem identity = new com.sun.security.auth.module.NTSystem();
        String domain = identity.getDomain();
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(
            domain == null || domain.isEmpty() ? identity.getName() : domain + "\\" + identity.getName());
    }

    static void requireExactParent(Path path) throws IOException
    {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null || !parent.equals(parent.toRealPath())) throw new IOException("Private storage parent must not be an alias");
    }

    static byte[] readSecret(Path path) throws IOException
    {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Secret must be a regular file");
        protect(path);
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(path,
            java.util.Set.of(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             java.io.InputStream input = java.nio.channels.Channels.newInputStream(channel))
        {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) throw new IOException("Secret file exceeds its size limit");
            return bytes;
        }
    }

    static void writeSecret(Path path, byte[] bytes) throws IOException
    {
        if (bytes.length > 4096) throw new IOException("Secret file exceeds its size limit");
        requireExactParent(path);
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(path,
            java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            fileAttributes(path, path.toAbsolutePath().getParent())))
        {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            if (channel instanceof java.nio.channels.FileChannel) ((java.nio.channels.FileChannel)channel).force(true);
        }
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

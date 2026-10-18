package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URL;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenDepsLoaderPathTest {
    @Test void absolutePathsPreserveSpacesPlusAndUnicode(@TempDir Path dir) throws Exception {
        Path jar = Files.createFile(dir.resolve("Scarlet + café 0.5.0.jar"));
        assertEquals(jar.toAbsolutePath(), MavenDepsLoader.owningJar(jar.toUri().toURL()));
        URL manifest = new URL("jar:" + jar.toUri().toASCIIString() + "!/META-INF/MANIFEST.MF");
        assertEquals(jar.toAbsolutePath(), MavenDepsLoader.owningJar(manifest));
        assertTrue(MavenDepsLoader.owningJar(manifest).isAbsolute());
        assertEquals(dir.toAbsolutePath(), MavenDepsLoader.owningJar(manifest).getParent());
    }
    @Test void looseClassesAndNonFileOwnersAreNotJars(@TempDir Path dir) throws Exception {
        assertNull(MavenDepsLoader.owningJar(dir.toUri().toURL()));
        assertNull(MavenDepsLoader.owningJar(new URL("https://example.invalid/library.jar")));
        assertNull(MavenDepsLoader.owningJar(null));
    }
}

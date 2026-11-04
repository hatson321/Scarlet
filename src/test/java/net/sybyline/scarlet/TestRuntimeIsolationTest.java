package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies the actual JVM temporary-directory cache, not just the property value. */
class TestRuntimeIsolationTest
{
    @Test void defaultTemporaryFilesUseTheExplicitTestDirectory() throws Exception
    {
        Path configured = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path temporary = Files.createTempDirectory("scarlet-isolation-check-");
        try { assertEquals(configured, temporary.toRealPath().getParent()); }
        finally { Files.delete(temporary); }
    }

    @Test void testsUseInMemoryPreferencesAndAnExplicitDataHome()
    {
        assertEquals(TestPreferencesFactory.class.getName(), System.getProperty("java.util.prefs.PreferencesFactory"));
        assertNotNull(System.getProperty("SCARLET_HOME"));
        assertEquals("true", System.getProperty("java.awt.headless"));
        assertNotEquals(System.getenv("USERPROFILE"), System.getProperty("user.home"));
    }
}

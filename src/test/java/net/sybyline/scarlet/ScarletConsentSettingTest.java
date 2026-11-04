package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Consent policy tests use temporary JSON only, never a GUI, credential store or network client. */
class ScarletConsentSettingTest
{
    @TempDir Path directory;

    private ScarletSettings settings(String filename) throws Exception
    {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        ScarletSettings settings = (ScarletSettings)((sun.misc.Unsafe)field.get(null)).allocateInstance(ScarletSettings.class);
        put(settings, "fileValuedSettings", new HashMap<>());
        put(settings, "settingsFile", this.directory.resolve(filename).toFile());
        return settings;
    }

    private static void put(Object target, String name, Object value) throws Exception
    {
        Field field = ScarletSettings.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private ScarletSettings.FileValuedConsentBoolean permission() throws Exception
    {
        return this.settings("settings.json").new FileValuedConsentBoolean("consent-v2", "Synthetic consent");
    }

    @Test void missingConsentDefaultsOffAndGenericWritersCannotGrantIt() throws Exception
    {
        ScarletSettings.FileValuedConsentBoolean setting = this.permission();
        assertFalse(setting.get());
        for (String source : new String[] {"ui", "discord", "manage-providers", "confirmed", null})
        {
            assertFalse(setting.set(true, source));
            assertFalse(setting.get());
        }
        assertFalse(this.settings("settings.json").getObject("consent-v2", Boolean.class));
    }

    @Test void explicitApprovalIsPersistedAndNotPromptedAgain() throws Exception
    {
        ScarletSettings.FileValuedConsentBoolean setting = this.permission();
        AtomicInteger prompts = new AtomicInteger();
        assertTrue(setting.setWithConsent(true, "ui", () -> { prompts.incrementAndGet(); return true; }));
        assertTrue(setting.get());
        assertTrue(setting.setWithConsent(true, "ui", () -> { prompts.incrementAndGet(); return false; }));
        assertTrue(setting.set(true, "discord"));
        assertEquals(1, prompts.get());
        ScarletSettings.FileValuedConsentBoolean reopened = this.settings("settings.json")
            .new FileValuedConsentBoolean("consent-v2", "Synthetic consent");
        assertTrue(reopened.get());
        assertTrue(reopened.setWithConsent(true, "ui", () -> { fail("Persisted permission must not prompt repeatedly"); return false; }));
    }

    @Test void declineCloseAndConfirmationFailureNeverGrantPermission() throws Exception
    {
        ScarletSettings.FileValuedConsentBoolean setting = this.permission();
        assertFalse(setting.setWithConsent(true, "ui", () -> false));
        assertFalse(setting.get());
        assertFalse(setting.setWithConsent(true, "ui", null));
        assertFalse(setting.get());
        assertFalse(setting.setWithConsent(true, "ui", () -> { throw new IllegalStateException("Synthetic callback failure"); }));
        assertFalse(setting.get());
        assertFalse(this.settings("settings.json").getObject("consent-v2", Boolean.class));
    }

    @Test void falseAndResetRevokeWithoutPromptingAndRequireFreshApproval() throws Exception
    {
        ScarletSettings.FileValuedConsentBoolean setting = this.permission();
        assertTrue(setting.setWithConsent(true, "ui", () -> true));
        assertTrue(setting.setWithConsent(false, "ui", () -> { fail("Revocation must not prompt"); return true; }));
        assertFalse(setting.get());
        assertTrue(setting.setWithConsent(true, "ui", () -> true));
        assertTrue(setting.set(null, "reset"));
        assertFalse(setting.get());
        assertFalse(setting.set(true, "ui"));
        assertTrue(setting.setWithConsent(true, "ui", () -> true));
        assertTrue(setting.get());
    }

    @Test void oldPermissionDoesNotGrantNewDisclosureConsent() throws Exception
    {
        Files.writeString(this.directory.resolve("settings.json"), "{\"consent-v1\":true}");
        ScarletSettings.FileValuedConsentBoolean setting = this.permission();
        assertFalse(setting.get());
        assertFalse(setting.set(true, "ui"));
    }

    @Test void revocationDuringPromptInvalidatesLateApprovalWithoutBlocking() throws Exception
    {
        ScarletSettings.FileValuedConsentBoolean setting = this.permission();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            Future<Boolean> approval = executor.submit(() -> setting.setWithConsent(true, "ui", () -> {
                entered.countDown();
                try { return release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertTrue(setting.set(false, "discord")));
            release.countDown();
            assertFalse(approval.get(5, TimeUnit.SECONDS));
            assertFalse(setting.get());
        }
        finally
        {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void aNewerDeclinedAttemptInvalidatesEarlierApproval() throws Exception
    {
        ScarletSettings.FileValuedConsentBoolean setting = this.permission();
        assertFalse(setting.setWithConsent(true, "first", () -> {
            assertFalse(setting.setWithConsent(true, "second", () -> false));
            return true;
        }));
        assertFalse(setting.get());
    }

    @Test void listenersOnlyObserveActualConsentStateChanges() throws Exception
    {
        ScarletSettings.FileValuedConsentBoolean setting = this.permission();
        setting.get();
        AtomicInteger changes = new AtomicInteger();
        AtomicReference<Boolean> observed = new AtomicReference<>();
        setting.listeners.register("observer", 0, true, (before, after, valid, source) -> {
            assertTrue(valid);
            assertEquals(after, setting.get());
            changes.incrementAndGet();
            observed.set(after);
        });
        assertFalse(setting.set(true, "ui"));
        assertFalse(setting.setWithConsent(true, "ui", () -> false));
        assertEquals(0, changes.get());
        assertTrue(setting.setWithConsent(true, "ui", () -> true));
        assertEquals(Boolean.TRUE, observed.get());
        assertTrue(setting.set(false, "discord"));
        assertEquals(Boolean.FALSE, observed.get());
        assertEquals(2, changes.get());
    }
}

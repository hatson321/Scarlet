package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScarletPreferenceNamespaceMigrationTest
{
    @Test
    void recognizesInterimPreferenceNamespace()
    {
        assertEquals("/net/hatson321/scarlet", ScarletSettings.interimPreferencesNodePath());
    }

    @Test
    void matchingEncryptionSeedDoesNotLetOlderInterimStateWin()
    {
        assertFalse(ScarletSettings.shouldPreferInterimState(
            "2026-10-01T12:00:00Z",
            "2026-10-01T11:00:00Z"));
    }

    @Test
    void strictlyNewerInterimStateWins()
    {
        assertTrue(ScarletSettings.shouldPreferInterimState(
            "2026-10-01T11:00:00Z",
            "2026-10-01T12:00:00Z"));
    }

    @Test
    void olderOrUnverifiableInterimStateDoesNotOverwriteRestoredState()
    {
        assertFalse(ScarletSettings.shouldPreferInterimState(
            "2026-10-01T12:00:00Z",
            "2026-10-01T11:00:00Z"));
        assertFalse(ScarletSettings.shouldPreferInterimState(
            "2026-10-01T12:00:00Z",
            "not-a-timestamp"));
        assertFalse(ScarletSettings.shouldPreferInterimState(
            null,
            "2026-10-01T12:00:00Z"));
        assertFalse(ScarletSettings.shouldPreferInterimState(
            "2026-10-01T12:00:00Z",
            "2026-10-01T12:00:00Z"));
    }

    @Test
    void durableMarkerAndWipePathsCoverBothNamespaces()
    {
        assertEquals("__scarlet_interim_namespace_reconciled_v1", ScarletSettings.INTERIM_NAMESPACE_RECONCILED_KEY);
        assertEquals("/net/sybyline/scarlet", ScarletMigration.preferenceNodePathsForWipe()[0]);
        assertEquals("/net/hatson321/scarlet", ScarletMigration.preferenceNodePathsForWipe()[1]);
    }
}

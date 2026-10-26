package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import io.github.vrchatapi.model.User;
import net.sybyline.scarlet.util.I18n;
import net.sybyline.scarlet.util.PropsTable;

/** Real row/status methods, but no application startup, windows, accounts or network clients. */
class ScarletPlayerListUiTest
{
    static final String USER = "usr_test-player";
    static final String TRAINING_USER = "usr_training-example";
    static final LocalDateTime JOINED = LocalDateTime.of(2026, 1, 1, 12, 0);

    static <T> T inert(Class<T> type) throws Exception
    {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe)field.get(null)).allocateInstance(type));
    }

    static void put(Object target, String name, Object value) throws Exception
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            }
            catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    static final class Listener extends ScarletEventListener
    {
        Listener() { super(null); } // Never called: the fixture has no Scarlet services.
        @Override public boolean getShowSuspiciousPronounAdvisory() { return false; }
    }

    static final class Fixture
    {
        final ScarletUI ui = inert(ScarletUI.class);
        final Map<String, ScarletUI.ConnectedPlayer> rows = new HashMap<>();
        final Map<String, ScarletUI.ConnectedPlayer> parked = new HashMap<>();
        final JLabel status = new JLabel();
        Fixture() throws Exception
        {
            Scarlet scarlet = inert(Scarlet.class);
            Listener listener = inert(Listener.class);
            put(listener, "clientLocation_userDisplayName2avatarBundleInfo", new ConcurrentHashMap<>());
            put(scarlet, "eventListener", listener);
            // scarlet.vrc intentionally remains null: any profile fetch on the row path fails this test.
            put(ui, "scarlet", scarlet);
            put(ui, "connectedPlayers", rows);
            put(ui, "parkedPlayers", parked);
            put(ui, "pendingUpdates", new HashMap<>());
            put(ui, "propstable", new PropsTable<ScarletUI.ConnectedPlayer>());
            put(ui, "jlabel_status", status);
        }
        void join(boolean preamble)
        {
            ui.playerJoin(preamble, USER, "Synthetic Player", JOINED, null, null, 0, false);
        }
        String status() throws Exception
        {
            SwingUtilities.invokeAndWait(() -> {});
            return status.getText().trim();
        }
    }

    @Test void observedJoinCreatesRowWithoutProfileNetworkAccess() throws Exception
    {
        Fixture f = new Fixture();
        f.join(false);
        assertTrue(f.rows.containsKey(USER));
        assertNull(f.rows.get(USER).acctdays);
        assertTrue(f.ui.hasActivePlayers());
        assertFalse(f.status().contains(I18n.tr("ui.statusNoInstance")));
    }

    @Test void detectedWorldIsNotReportedAsMissingWhileWaitingForRows() throws Exception
    {
        Fixture f = new Fixture();
        f.ui.instanceLocation("synthetic-instance");
        assertEquals(I18n.tr("ui.statusWaitingForPlayers"), f.status());
        f.ui.instanceLocation(null);
        assertEquals(I18n.tr("ui.statusNoInstance"), f.status());
    }

    @Test void catchupSortRefreshesStatusAfterSilentPreambleRows() throws Exception
    {
        Fixture f = new Fixture();
        f.ui.instanceLocation("synthetic-instance");
        f.join(true);
        f.ui.fireSort();
        assertFalse(f.status().contains(I18n.tr("ui.statusWaitingForPlayers")));
        assertFalse(f.status().contains(I18n.tr("ui.statusNoInstance")));
    }

    @Test void backgroundDetailsCannotCreateOrResurrectAPlayer() throws Exception
    {
        Fixture f = new Fixture();
        f.ui.playerEnrich(USER, null, "late", Color.RED, 10, () -> true);
        assertTrue(f.rows.isEmpty());
        f.join(false);
        f.ui.playerLeave(false, USER, "Synthetic Player", JOINED.plusMinutes(1));
        f.ui.playerEnrich(USER, null, "late", Color.RED, 10, () -> true);
        assertNotNull(f.rows.get(USER).left);
        assertNull(f.rows.get(USER).baseAdvisory);
    }

    @Test void invalidatedBackgroundResultCannotChangeCurrentRow() throws Exception
    {
        Fixture f = new Fixture();
        f.join(false);
        f.ui.playerEnrich(USER, null, "stale", Color.RED, 10, () -> false);
        assertNull(f.rows.get(USER).baseAdvisory);
        f.ui.playerEnrich(USER, null, "current", Color.GREEN, 10, () -> true);
        assertEquals("current", f.rows.get(USER).baseAdvisory);
    }

    @Test void profileWithoutJoinDateDoesNotBreakEnrichment() throws Exception
    {
        Fixture f = new Fixture();
        f.join(false);
        assertDoesNotThrow(() -> f.ui.playerEnrich(USER, new User().pronouns("they/them"), null, null, 0, () -> true));
        assertNull(f.rows.get(USER).acctdays);
        assertEquals("they/them", f.rows.get(USER).pronouns);
    }

    @Test void clearedRowsDoNotReceiveOldPendingUpdates() throws Exception
    {
        Fixture f = new Fixture();
        f.ui.playerUpdate(false, USER, p -> p.avatarName = "Old avatar");
        f.ui.clearInstance();
        f.join(false);
        assertNull(f.rows.get(USER).avatarName);
    }

    @Test void trainingEnrichmentUpdatesParkedRealPlayerWithoutLeakingIntoSimulation() throws Exception
    {
        Fixture f = new Fixture();
        f.join(false);
        f.ui.enterTrainingView();
        f.ui.playerJoin(false, TRAINING_USER, "Simulated Player", JOINED, "Training advisory", Color.ORANGE, 1, false);
        f.ui.playerEnrich(USER, new User().pronouns("they/them"), "Real advisory", Color.GREEN, 2, () -> true);
        assertFalse(f.rows.containsKey(USER));
        assertEquals("Training advisory", f.rows.get(TRAINING_USER).baseAdvisory);
        assertEquals("Real advisory", f.parked.get(USER).baseAdvisory);
        assertEquals("they/them", f.parked.get(USER).pronouns);
        assertTrue(f.ui.hasActivePlayers(), "Real presence remains available while training is visible");
        f.ui.exitTrainingView();
        assertEquals(1, f.rows.size());
        assertEquals("Real advisory", f.rows.get(USER).baseAdvisory);
        assertTrue(f.parked.isEmpty());
    }

    @Test void realInstanceChangeDuringTrainingDropsOnlyRealPendingUpdates() throws Exception
    {
        Fixture f = new Fixture();
        f.ui.enterTrainingView();
        f.ui.playerUpdate(false, USER, p -> p.avatarName = "Stale real avatar");
        f.ui.playerUpdate(false, TRAINING_USER, p -> p.avatarName = "Current simulated avatar");
        f.ui.clearInstance();
        f.join(false);
        f.ui.playerJoin(false, TRAINING_USER, "Simulated Player", JOINED, null, null, 0, false);
        assertNull(f.parked.get(USER).avatarName);
        assertEquals("Current simulated avatar", f.rows.get(TRAINING_USER).avatarName);
        assertFalse(f.rows.containsKey(USER));
    }

    @Test void manualTrainingClearPreservesParkedRealRowsAndPendingRealDetails() throws Exception
    {
        Fixture f = new Fixture();
        f.join(false);
        f.ui.enterTrainingView();
        f.ui.playerEnrich(USER, null, "Current real advisory", Color.GREEN, 2, () -> true);
        String lateReal = USER + "-late";
        f.ui.playerUpdate(false, lateReal, p -> p.avatarName = "Pending real avatar");
        f.ui.playerUpdate(false, TRAINING_USER, p -> p.avatarName = "Stale simulated avatar");
        f.ui.playerJoin(false, TRAINING_USER + "-other", "Other simulation", JOINED, null, null, 0, false);
        f.ui.clearInstanceManual();
        assertTrue(f.rows.isEmpty());
        assertEquals("Current real advisory", f.parked.get(USER).baseAdvisory);
        f.ui.playerJoin(false, lateReal, "Real late player", JOINED, null, null, 0, false);
        f.ui.playerJoin(false, TRAINING_USER, "Simulated Player", JOINED, null, null, 0, false);
        assertEquals("Pending real avatar", f.parked.get(lateReal).avatarName);
        assertNull(f.rows.get(TRAINING_USER).avatarName);
        assertTrue(f.ui.hasActivePlayers());
    }
}

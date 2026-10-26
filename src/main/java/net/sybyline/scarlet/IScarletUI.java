package net.sybyline.scarlet;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.io.Closeable;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import io.github.vrchatapi.model.User;

import javax.swing.JFrame;

import net.sybyline.scarlet.ScarletUI.ConnectedPlayer;
import net.sybyline.scarlet.util.Platform;
import net.sybyline.scarlet.util.Func;
import net.sybyline.scarlet.util.Func.V1.NE;

public interface IScarletUI extends Closeable
{

    static IScarletUI create(Scarlet scarlet)
    {
        if (Platform.forceHeadlessUi() || GraphicsEnvironment.isHeadless())
            return new ScarletUIHeadless();
        return new ScarletUI(scarlet);
    }

    /**
     * Get the parent component for dialogs.
     * @return The parent component, or null if not available
     */
    java.awt.Component getParentComponent();
    
    void jframe(Consumer<JFrame> edit);
    /** Show this UI's main window. */
    default void showMainWindow() { this.jframe(f -> f.setVisible(true)); }
    void setUIScale();
    void loadSettings();
    void refreshVrchatApiStatus();
    void showAnnouncement(ScarletAnnouncement announcement);

    void fireSort();
    void clearInstance();
    /** Observed client location, independent of whether player rows have arrived yet. */
    default void instanceLocation(String location) {}
    void playerJoin(boolean initialPreamble, String id, String name, LocalDateTime joined, String advisory, Color text_color, int priority, boolean isRejoinFromPrev);
    /** Applies background details only to the still-current, present player; never creates a row. */
    default void playerEnrich(String id, User user, String advisory, Color textColor, int priority, BooleanSupplier stillCurrent) {}
    void playerUpdate(boolean initialPreamble, String id, Func.V1.NE<ConnectedPlayer> update);
    void playerLeave(boolean initialPreamble, String id, String name, LocalDateTime left);

    /** Returns true if at least one player is currently present in the instance table. */
    boolean hasActivePlayers();

}

class ScarletUIHeadless implements IScarletUI
{
    public java.awt.Component getParentComponent() { return null; }
    public void close() {}
    public void jframe(Consumer<JFrame> edit) {}
    public void setUIScale() {}
    public void loadSettings() {}
    public void refreshVrchatApiStatus() {}
    public void showAnnouncement(ScarletAnnouncement announcement) {}
    public void fireSort() {}
    public void clearInstance() {}
    public void playerJoin(boolean initialPreamble, String id, String name, LocalDateTime joined, String advisory, Color text_color, int priority, boolean isRejoinFromPrev) {}
    public void playerUpdate(boolean initialPreamble, String id, NE<ConnectedPlayer> update) {}
    public void playerLeave(boolean initialPreamble, String id, String name, LocalDateTime left) {}
    public boolean hasActivePlayers() { return false; }
}

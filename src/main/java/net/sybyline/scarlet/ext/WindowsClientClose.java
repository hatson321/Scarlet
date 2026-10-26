package net.sybyline.scarlet.ext;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import net.sybyline.scarlet.ext.ManagedVrcClient.CloseResult;

/** Separates definitely-unsent window checks from native dispatch and uncertain outcomes. */
final class WindowsClientClose
{
    interface Windows<W>
    {
        List<W> visible(long pid);
        boolean belongsTo(W window, long pid);
        boolean postClose(W window);
        int lastError();
    }

    static <W> CloseResult request(long pid, Windows<W> windows, BooleanSupplier identity,
        BooleanSupplier revalidate, IntConsumer error)
    {
        final List<W> visible;
        try { visible = windows.visible(pid); }
        catch (RuntimeException | LinkageError ex) { return CloseResult.WINDOW_ENUMERATION_FAILED; }
        if (visible == null) return CloseResult.WINDOW_ENUMERATION_FAILED;
        if (visible.isEmpty()) return CloseResult.WINDOW_NOT_FOUND;
        if (visible.size() != 1) return CloseResult.WINDOW_AMBIGUOUS;
        W window = visible.get(0);
        try {
            if (window == null || !windows.belongsTo(window, pid)) return CloseResult.WINDOW_IDENTITY_CHANGED;
            if (!identity.getAsBoolean()) return CloseResult.IDENTITY_CHANGED;
            if (!revalidate.getAsBoolean()) return CloseResult.FINAL_GUARD_VETO;
            // Process inventory in revalidate can take time; recheck the HWND owner last.
            if (!windows.belongsTo(window, pid)) return CloseResult.WINDOW_IDENTITY_CHANGED;
        }
        catch (RuntimeException | LinkageError ex) { return CloseResult.WINDOW_CHECK_UNAVAILABLE; }
        try {
            if (windows.postClose(window)) return CloseResult.REQUESTED;
            int code = windows.lastError();
            try { error.accept(code); } catch (RuntimeException ignored) { }
            return code == 5 ? CloseResult.WINDOWS_ACCESS_DENIED : CloseResult.WINDOWS_POST_REJECTED;
        }
        // Dispatch might have happened before an exception: never automatically replay.
        catch (RuntimeException | LinkageError ex) { return CloseResult.DISPATCH_UNCERTAIN; }
    }
}

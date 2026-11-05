package net.sybyline.scarlet.util;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synthesises a global hotkey press to trigger an external video-capture tool (OBS, Medal, …) when a
 * moderation event is detected — automatic evidence capture, as popularised by the old BanLogger.
 *
 * <p>The key is injected at the OS level via {@link Robot}, so a tool listening for a <em>global</em>
 * hotkey (e.g. OBS's "Save Replay Buffer" or Medal's clip key) fires even when it isn't focused.
 * Configure the same combo here that you bound in the capture tool. Does nothing when headless or the
 * hotkey is blank/unparseable.
 */
public final class EvidenceCapture
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/EvidenceCapture");

    private static volatile Robot robot;

    private EvidenceCapture() {}

    /**
     * Presses {@code hotkeySpec} (e.g. {@code "CTRL+SHIFT+F9"}, {@code "F9"}, {@code "ALT+M"}), holds it
     * for {@code holdMillis}, and releases it. Modifiers are pressed first and released last. Best-effort:
     * logs and returns on any problem rather than throwing, so a capture failure never disrupts moderation.
     */
    public static void fireHotkey(String hotkeySpec, int holdMillis)
    {
        if (hotkeySpec == null || hotkeySpec.trim().isEmpty())
            return;
        if (GraphicsEnvironment.isHeadless())
        {
            LOG.warn("Evidence capture requested but the environment is headless; cannot send a key press.");
            return;
        }
        int[] codes = parse(hotkeySpec);
        if (codes == null)
        {
            LOG.warn("Evidence capture hotkey '{}' could not be parsed; expected e.g. CTRL+SHIFT+F9.", hotkeySpec);
            return;
        }
        try
        {
            Robot r = robot;
            if (r == null)
                synchronized (EvidenceCapture.class) { if ((r = robot) == null) r = robot = new Robot(); }
            final Robot target = r;
            pressAndRelease(codes, holdMillis, new KeySink() {
                public void press(int code) { target.keyPress(code); }
                public void release(int code) { target.keyRelease(code); }
            }, Thread::sleep);
            LOG.info("Fired evidence-capture hotkey '{}' ({}ms).", hotkeySpec, holdMillis);
        }
        catch (Throwable ex)
        {
            LOG.error("Failed to fire evidence-capture hotkey '{}'", hotkeySpec, ex);
        }
    }

    // Modifiers first (so they're held while the main key is pressed), then the rest, in spec order.
    static int[] parse(String spec)
    {
        List<Integer> mods = new ArrayList<>();
        List<Integer> keys = new ArrayList<>();
        for (String raw : spec.trim().split("[+\\-\\s]+"))
        {
            String t = raw.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty())
                continue;
            Integer mod = modifier(t);
            if (mod != null) { mods.add(mod); continue; }
            Integer key = key(t);
            if (key == null)
                return null;
            keys.add(key);
        }
        if (keys.isEmpty())
            return null;
        int[] out = new int[mods.size() + keys.size()];
        int i = 0;
        for (int m : mods) out[i++] = m;
        for (int k : keys) out[i++] = k;
        return out;
    }

    private static Integer modifier(String t)
    {
        switch (t)
        {
        case "CTRL": case "CONTROL":       return KeyEvent.VK_CONTROL;
        case "SHIFT":                      return KeyEvent.VK_SHIFT;
        case "ALT":                        return KeyEvent.VK_ALT;
        case "ALTGR": case "ALT_GRAPH":    return KeyEvent.VK_ALT_GRAPH;
        case "META": case "WIN": case "WINDOWS": case "CMD": case "COMMAND": return KeyEvent.VK_META;
        default: return null;
        }
    }

    private static Integer key(String t)
    {
        // Function keys F1..F24
        if (t.length() >= 2 && t.charAt(0) == 'F')
        {
            try
            {
                int n = Integer.parseInt(t.substring(1));
                if (n >= 1 && n <= 12)
                    return KeyEvent.VK_F1 + (n - 1);
                if (n >= 13 && n <= 24)
                    return KeyEvent.VK_F13 + (n - 13);
            }
            catch (NumberFormatException ignored) {}
        }
        // Single letter or digit
        if (t.length() == 1)
        {
            char c = t.charAt(0);
            if (c >= 'A' && c <= 'Z') return KeyEvent.VK_A + (c - 'A');
            if (c >= '0' && c <= '9') return KeyEvent.VK_0 + (c - '0');
        }
        // A few named keys people commonly bind
        switch (t)
        {
        case "SPACE":     return KeyEvent.VK_SPACE;
        case "ENTER": case "RETURN": return KeyEvent.VK_ENTER;
        case "TAB":       return KeyEvent.VK_TAB;
        case "INSERT": case "INS": return KeyEvent.VK_INSERT;
        case "DELETE": case "DEL": return KeyEvent.VK_DELETE;
        case "HOME":      return KeyEvent.VK_HOME;
        case "END":       return KeyEvent.VK_END;
        case "PAGEUP": case "PGUP":   return KeyEvent.VK_PAGE_UP;
        case "PAGEDOWN": case "PGDN": return KeyEvent.VK_PAGE_DOWN;
        default: return null;
        }
    }

    interface KeySink
    {
        void press(int code);
        void release(int code);
    }

    @FunctionalInterface interface Hold { void sleep(long millis) throws InterruptedException; }

    // Serialize complete chords: another capture must not release a modifier still in use.
    static synchronized void pressAndRelease(int[] codes, int holdMillis, KeySink sink, Hold hold) throws Throwable
    {
        int attempted = 0;
        Throwable failure = null;
        try
        {
            for (int code : codes)
            {
                attempted++; // A native press may take effect before reporting an error.
                sink.press(code);
            }
            hold.sleep(Math.max(1, holdMillis));
        }
        catch (Throwable ex)
        {
            failure = ex;
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        finally
        {
            for (int i = attempted - 1; i >= 0; i--)
                try { sink.release(codes[i]); }
                catch (Throwable ex)
                {
                    if (failure == null) failure = ex;
                    else if (failure != ex) failure.addSuppressed(ex);
                }
        }
        if (failure != null) throw failure;
    }
}

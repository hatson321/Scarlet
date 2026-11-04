package net.sybyline.scarlet.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatSystemProperties;

import net.sybyline.scarlet.util.Platform;
import net.sybyline.scarlet.util.Throwables;

public class Swing
{

    /**
     * Whether the current Linux session is a native Wayland session.
     * True when XDG_SESSION_TYPE=wayland or WAYLAND_DISPLAY is set.
     * False on X11, Windows, macOS, or headless environments.
     */
    public static final boolean IS_WAYLAND_SESSION;

    static
    {
        boolean wayland = false;
        if (Platform.CURRENT.is$nix())
        {
            String sessionType    = System.getenv("XDG_SESSION_TYPE");
            String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
            wayland = "wayland".equalsIgnoreCase(sessionType)
                   || (waylandDisplay != null && !waylandDisplay.isEmpty());
        }
        IS_WAYLAND_SESSION = wayland;
    }

    public static void init()
    {
        if (!Platform.CURRENT.is$nix())
            return;

        if (IS_WAYLAND_SESSION)
        {
            LOG.info("Wayland session detected (XDG_SESSION_TYPE={}, WAYLAND_DISPLAY={}). "
                + "Java AWT/Swing uses XWayland on Java 8-17; native Wayland toolkit requires Java 21+.",
                System.getenv("XDG_SESSION_TYPE"),
                System.getenv("WAYLAND_DISPLAY"));

            // Enable subpixel-accurate font rendering — XWayland composites at 1:1
            // so grayscale AA looks better than subpixel under Wayland.
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("swing.aatext", "true");

            // Tell FlatLaf to not assume integer DPI ratios; fractional scaling
            // (e.g. 125%, 150%) is common on Wayland desktops.
            System.setProperty(FlatSystemProperties.USE_WINDOW_DECORATIONS, "true");

            // Suppress XWayland's bogus screen resolution report: Wayland compositors
            // typically lie to XWayland and report 96 DPI regardless of actual DPI.
            // Setting this prevents FlatLaf from over-scaling based on a false screen DPI.
            if (System.getProperty(FlatSystemProperties.UI_SCALE_ENABLED) == null)
                System.setProperty(FlatSystemProperties.UI_SCALE_ENABLED, "true");
        }
        else
        {
            LOG.info("X11 session detected on Linux (XDG_SESSION_TYPE={}).",
                System.getenv("XDG_SESSION_TYPE"));
        }
    }

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/Swing");
    public static final Font MONOSPACED = Font.decode(Font.MONOSPACED);

    // ── Public palette fields — used by ScarletUISplash, ScarletUI, etc. ──────
    // These are set once in the static block from the saved setting (or the
    // default crimson-red), and again whenever the user changes the accent.
    public static volatile Color ACCENT;
    public static volatile Color ACCENT_DARK;
    public static volatile Color ACCENT_GLOW;
    public static volatile Color SEL_BG;

    /**
     * The fixed dark palette shared by the FlatLaf theme keys and every place
     * that styles components by hand. Hand-styled UI must reference these
     * instead of inlining color literals, so the shades can never drift apart.
     */
    public static final Color BG_BASE  = new Color( 28,  28,  36),
                              BG_PANEL = new Color( 34,  34,  44),
                              BG_INPUT = new Color( 22,  22,  30),
                              FG_MAIN  = new Color(220, 220, 232),
                              FG_SOFT  = new Color(180, 180, 195),
                              FG_DIM   = new Color(140, 140, 155),
                              BORDER   = new Color( 55,  55,  72);

    // ── Accent-change listeners ───────────────────────────────────────────────
    // Fired by pickAccentColor() after the user confirms a new colour via the
    // JColorChooser dialog. Components that mirror the accent (e.g. the theme
    // preset combo) register here so they can reset their display state.
    private static final java.util.List<Runnable> ACCENT_CHANGE_LISTENERS =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addAccentChangeListener(Runnable listener)
    {
        if (listener != null)
            ACCENT_CHANGE_LISTENERS.add(listener);
    }

    public static void removeAccentChangeListener(Runnable listener)
    {
        ACCENT_CHANGE_LISTENERS.remove(listener);
    }

    // Settings key — must match what Scarlet uses for read/write
    public static final String ACCENT_SETTING_KEY = "ui_accent_color";

    // ── Default accent ────────────────────────────────────────────────────────
    static final Color DEFAULT_ACCENT = new Color(200, 55, 65);

    /** True when FlatLaf could not be installed and Swing fell back to a classic look and feel. */
    public static final boolean CLASSIC_MODE;

    static
    {
        boolean classic = false;
        try
        {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        }
        catch (Throwable ex)
        {
            LOG.error("Exception setting FlatLaf look and feel (falling back to system L&F)", ex);
            // FlatLaf may throw Error subclasses (NoClassDefFoundError,
            // UnsatisfiedLinkError) on platforms where its native components
            // cannot load. Fall back gracefully.
            classic = true;
            try
            {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            }
            catch (Exception fallbackEx)
            {
                // Metal (cross-platform) is the ultimate fallback and always works
            }
        }
        CLASSIC_MODE = classic;

        if (!CLASSIC_MODE)
        {
            // Read saved accent from settings.json before building the theme.
            // Scarlet.dir is a static final set before any Scarlet instance is
            // constructed, so it is safe to read here.
            Color savedAccent = loadSavedAccent();
            applyTheme(savedAccent != null ? savedAccent : DEFAULT_ACCENT);
        }
    }

    /**
     * Reads the saved accent colour from settings.json, or returns null if
     * none is saved or the file cannot be read.
     */
    static Color loadSavedAccent()
    {
        try
        {
            // Scarlet.dir is available as a static final
            File settingsFile = new File(net.sybyline.scarlet.Scarlet.dir, "settings.json");
            if (!settingsFile.isFile())
                return null;
            String json;
            try (java.io.FileReader fr = new java.io.FileReader(settingsFile, java.nio.charset.StandardCharsets.UTF_8))
            {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = fr.read(buf)) != -1)
                    sb.append(buf, 0, n);
                json = sb.toString();
            }
            // Simple extraction — look for "ui_accent_color": [r, g, b]
            // Using Gson would create a circular dependency at static-init time.
            int idx = json.indexOf("\"" + ACCENT_SETTING_KEY + "\"");
            if (idx < 0)
                return null;
            int start = json.indexOf('[', idx);
            int end   = json.indexOf(']', start);
            if (start < 0 || end < 0)
                return null;
            String[] parts = json.substring(start + 1, end).split(",");
            if (parts.length < 3)
                return null;
            int r = Integer.parseInt(parts[0].trim());
            int g = Integer.parseInt(parts[1].trim());
            int b = Integer.parseInt(parts[2].trim());
            return new Color(
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, b)));
        }
        catch (Exception ex)
        {
            LOG.warn("Could not load saved accent colour: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Applies a complete FlatLaf theme using the given accent colour.
     * All derived colours (dark variant, selection background, scroll thumb,
     * glow, etc.) are computed automatically so only the accent needs to be
     * chosen by the user.
     *
     * <p>Safe to call from the static initialiser or from the EDT after
     * the user picks a new colour. After calling this, call
     * {@code FlatLaf.updateUI()} on all windows for live changes.
     */
    public static void applyTheme(Color accent)
    {
        // Skip theming in classic mode because these UIManager keys are FlatLaf-specific.
        if (CLASSIC_MODE)
            return;
        // Derive companion colours from the accent
        Color accentDark = new Color(
            Math.max(0, accent.getRed()   - 50),
            Math.max(0, accent.getGreen() - 20),
            Math.max(0, accent.getBlue()  - 20));
        Color selBg = new Color(
            Math.max(0, Math.min(255, accent.getRed()   / 2 + 20)),
            Math.max(0, Math.min(255, accent.getGreen() / 4 + 20)),
            Math.max(0, Math.min(255, accent.getBlue()  / 4 + 20)));
        Color accentGlow = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80);

        // Update public fields so other classes (ScarletUISplash, ScarletUI) read them
        ACCENT      = accent;
        ACCENT_DARK = accentDark;
        ACCENT_GLOW = accentGlow;
        SEL_BG      = selBg;

        // ── Apply to UIManager (palette constants are the class-level statics) ─
        UIManager.put("@accentColor",                  accent);
        UIManager.put("Panel.background",              BG_PANEL);
        UIManager.put("RootPane.background",           BG_BASE);
        UIManager.put("window",                        BG_BASE);
        UIManager.put("Label.foreground",              FG_MAIN);
        UIManager.put("Label.disabledForeground",      FG_DIM);
        UIManager.put("Component.focusColor",          accent);
        UIManager.put("Component.borderColor",         BORDER);
        UIManager.put("Component.disabledBorderColor", BORDER);

        UIManager.put("Button.arc",                    999);
        UIManager.put("Button.background",             BG_PANEL);
        UIManager.put("Button.foreground",             FG_MAIN);
        UIManager.put("Button.hoverBackground",        new Color(50, 50, 66));
        UIManager.put("Button.pressedBackground",      accentDark);
        UIManager.put("Button.borderColor",            BORDER);
        UIManager.put("Button.hoverBorderColor",       accent);
        UIManager.put("Button.focusedBorderColor",     accent);
        UIManager.put("Button.innerFocusWidth",        0);

        UIManager.put("TextField.background",          BG_INPUT);
        UIManager.put("TextField.foreground",          FG_MAIN);
        UIManager.put("TextField.caretForeground",     accent);
        UIManager.put("TextField.selectionBackground", selBg);
        UIManager.put("FormattedTextField.background", BG_INPUT);

        UIManager.put("ComboBox.background",           BG_INPUT);
        UIManager.put("ComboBox.foreground",           FG_MAIN);
        UIManager.put("ComboBox.selectionBackground",  selBg);
        UIManager.put("ComboBox.buttonBackground",     BG_INPUT);
        UIManager.put("ComboBox.arc",                  6);

        UIManager.put("CheckBox.icon.selectedBackground",  accent);
        UIManager.put("CheckBox.icon.selectedBorderColor", accentDark);
        UIManager.put("CheckBox.icon.checkmarkColor",      Color.WHITE);
        UIManager.put("CheckBox.icon.focusedBackground",   BG_INPUT);
        UIManager.put("CheckBox.icon.hoverBackground",
            new Color(Math.max(0,accent.getRed()-30), Math.max(0,accent.getGreen()-25), Math.max(0,accent.getBlue()-25)));
        UIManager.put("CheckBox.icon.arc",             4);

        UIManager.put("Table.background",              BG_BASE);
        UIManager.put("Table.foreground",              FG_MAIN);
        UIManager.put("Table.selectionBackground",     selBg);
        UIManager.put("Table.selectionForeground",     Color.WHITE);
        UIManager.put("Table.gridColor",               new Color(42, 42, 55));
        UIManager.put("Table.rowHeight",               26);
        UIManager.put("Table.showHorizontalLines",     Boolean.TRUE);
        UIManager.put("Table.showVerticalLines",       Boolean.FALSE);
        UIManager.put("Table.intercellSpacing",        new java.awt.Dimension(0, 1));
        UIManager.put("TableHeader.background",        BG_PANEL);
        UIManager.put("TableHeader.foreground",        FG_DIM);
        UIManager.put("TableHeader.separatorColor",    BORDER);
        UIManager.put("TableHeader.bottomSeparatorColor", accent);

        UIManager.put("TabbedPane.tabHeight",          32);
        UIManager.put("TabbedPane.selectedBackground", BG_BASE);
        UIManager.put("TabbedPane.underlineColor",     accent);
        UIManager.put("TabbedPane.inactiveUnderlineColor",
            new Color(Math.max(0,accent.getRed()/2+20), Math.max(0,accent.getGreen()/4+20), Math.max(0,accent.getBlue()/4+20)));
        UIManager.put("TabbedPane.background",         BG_PANEL);
        UIManager.put("TabbedPane.foreground",         FG_DIM);
        UIManager.put("TabbedPane.selectedForeground", FG_MAIN);
        UIManager.put("TabbedPane.contentAreaColor",   BG_BASE);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", Boolean.TRUE);
        // Prevent the tab strip from bleeding into components above it.
        // FlatLaf's default contentBorderInsets can be negative which causes overlap.
        UIManager.put("TabbedPane.contentBorderInsets", new java.awt.Insets(0, 0, 0, 0));
        UIManager.put("TabbedPane.tabInsets",           new java.awt.Insets(4, 12, 4, 12));

        UIManager.put("ScrollBar.width",               10);
        UIManager.put("ScrollBar.thumbArc",            999);
        UIManager.put("ScrollBar.thumb",               selBg);
        UIManager.put("ScrollBar.thumbDarkShadow",     new Color(0, 0, 0, 0));
        UIManager.put("ScrollBar.thumbHighlight",      accent);
        UIManager.put("ScrollBar.hoverThumbColor",     accent);
        UIManager.put("ScrollBar.track",               BG_BASE);
        UIManager.put("ScrollBar.trackArc",            999);

        UIManager.put("Menu.background",               BG_PANEL);
        UIManager.put("Menu.foreground",               FG_MAIN);
        UIManager.put("MenuItem.background",           BG_PANEL);
        UIManager.put("MenuItem.foreground",           FG_MAIN);
        UIManager.put("MenuItem.selectionBackground",  selBg);
        UIManager.put("MenuItem.selectionForeground",  Color.WHITE);
        UIManager.put("PopupMenu.background",          BG_PANEL);
        UIManager.put("PopupMenu.border",              BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 1, 1, 1, BORDER),
            BorderFactory.createEmptyBorder(2, 0, 2, 0)));
        UIManager.put("MenuBar.background",            BG_PANEL);
        UIManager.put("MenuBar.foreground",            FG_MAIN);
        UIManager.put("MenuBar.borderColor",           BORDER);

        UIManager.put("Separator.foreground",          BORDER);

        UIManager.put("ToolTip.background",            BG_PANEL);
        UIManager.put("ToolTip.foreground",            FG_MAIN);
        UIManager.put("ToolTip.border",                BorderFactory.createMatteBorder(1, 1, 1, 1, accent));

        // ── Dialogs / popups ───────────────────────────────────────────────────
        // Without these, JOptionPane popups and their text components fall back to
        // FlatLaf's lighter default grey, which clashes with the darker custom palette
        // used by the main window. Match them to the main UI so popups look consistent.
        UIManager.put("OptionPane.background",         BG_BASE);
        UIManager.put("OptionPane.foreground",         FG_MAIN);
        UIManager.put("OptionPane.messageForeground",  FG_MAIN);

        UIManager.put("TextArea.background",           BG_INPUT);
        UIManager.put("TextArea.foreground",           FG_MAIN);
        UIManager.put("TextArea.caretForeground",      accent);
        UIManager.put("TextArea.selectionBackground",  selBg);
        UIManager.put("TextArea.selectionForeground",  Color.WHITE);
        UIManager.put("TextArea.inactiveForeground",   FG_DIM);

        UIManager.put("TextPane.background",           BG_INPUT);
        UIManager.put("TextPane.foreground",           FG_MAIN);
        UIManager.put("TextPane.caretForeground",      accent);
        UIManager.put("TextPane.selectionBackground",  selBg);
        UIManager.put("TextPane.selectionForeground",  Color.WHITE);

        UIManager.put("EditorPane.background",         BG_INPUT);
        UIManager.put("EditorPane.foreground",         FG_MAIN);
        UIManager.put("EditorPane.caretForeground",    accent);
        UIManager.put("EditorPane.selectionBackground", selBg);
        UIManager.put("EditorPane.selectionForeground", Color.WHITE);

        UIManager.put("List.background",               BG_INPUT);
        UIManager.put("List.foreground",               FG_MAIN);
        UIManager.put("List.selectionBackground",      selBg);
        UIManager.put("List.selectionForeground",      Color.WHITE);

        // ── Remaining FlatLaf fallbacks ────────────────────────────────────────
        // Components without explicit keys fall back to FlatLaf's stock grey,
        // which clashes with the darker custom palette and shows up as mismatched
        // patches ("miscoloration") wherever those components appear. Cover them.
        UIManager.put("ScrollPane.background",         BG_BASE);
        UIManager.put("Viewport.background",           BG_BASE);

        UIManager.put("CheckBox.background",           BG_PANEL);
        UIManager.put("CheckBox.foreground",           FG_MAIN);
        UIManager.put("RadioButton.background",        BG_PANEL);
        UIManager.put("RadioButton.foreground",        FG_MAIN);
        UIManager.put("RadioButton.icon.selectedBackground",  accent);
        UIManager.put("RadioButton.icon.selectedBorderColor", accentDark);
        UIManager.put("ToggleButton.background",       BG_PANEL);
        UIManager.put("ToggleButton.foreground",       FG_MAIN);
        UIManager.put("ToggleButton.selectedBackground", accentDark);

        UIManager.put("PasswordField.background",      BG_INPUT);
        UIManager.put("PasswordField.foreground",      FG_MAIN);
        UIManager.put("PasswordField.caretForeground", accent);
        UIManager.put("PasswordField.selectionBackground", selBg);
        UIManager.put("TextField.disabledBackground",  BG_PANEL);
        UIManager.put("TextArea.disabledBackground",   BG_PANEL);

        UIManager.put("Spinner.background",            BG_INPUT);
        UIManager.put("Spinner.foreground",            FG_MAIN);
        UIManager.put("Spinner.buttonBackground",      BG_INPUT);

        UIManager.put("Slider.thumbColor",             accent);
        UIManager.put("Slider.trackColor",             BORDER);
        UIManager.put("Slider.trackValueColor",        accent);
        UIManager.put("Slider.focusedColor",           accentGlow);

        UIManager.put("ProgressBar.background",        BG_INPUT);
        UIManager.put("ProgressBar.foreground",        accent);
        UIManager.put("ProgressBar.selectionBackground", FG_MAIN);
        UIManager.put("ProgressBar.selectionForeground", Color.WHITE);
        UIManager.put("ProgressBar.arc",               999);

        UIManager.put("Tree.background",               BG_INPUT);
        UIManager.put("Tree.foreground",               FG_MAIN);
        UIManager.put("Tree.selectionBackground",      selBg);
        UIManager.put("Tree.selectionForeground",      Color.WHITE);

        UIManager.put("TitledBorder.titleColor",       FG_DIM);
        UIManager.put("SplitPane.background",          BG_BASE);
        UIManager.put("SplitPaneDivider.draggingColor", accent);
        UIManager.put("ToolBar.background",            BG_PANEL);
    }

    /**
     * Opens a {@link javax.swing.JColorChooser} dialog for the user to pick an
     * accent colour, then saves it to {@code settings.json} and re-applies the
     * theme live so the change takes effect without a restart.
     *
     * <p>Must be called from the EDT (via {@code Swing.invokeWait} or similar).
     *
     * @param parentComponent parent for the dialog, may be null
     * @param scarlet         used to persist the setting
     */
    public static void pickAccentColor(java.awt.Component parentComponent,
                                       net.sybyline.scarlet.Scarlet scarlet)
    {
        if (CLASSIC_MODE)
            return;
        Color current = ACCENT != null ? ACCENT : DEFAULT_ACCENT;
        Color chosen = javax.swing.JColorChooser.showDialog(
            parentComponent,
            "Choose accent colour",
            current);
        if (chosen == null)
            return;  // user cancelled

        // Save via the public method on Scarlet — settings is package-private
        scarlet.saveAccentColor(chosen.getRed(), chosen.getGreen(), chosen.getBlue());

        // Re-apply the full theme with the new accent
        applyTheme(chosen);

        // Refresh all open windows so they pick up the new UIManager values
        for (java.awt.Window w : java.awt.Window.getWindows())
        {
            com.formdev.flatlaf.FlatLaf.updateUI();
            w.repaint();
        }

        // Notify listeners (e.g. the theme-preset combo) that the accent changed
        for (Runnable listener : ACCENT_CHANGE_LISTENERS)
            listener.run();
    }

    /**
     * Detects the appropriate UI scale on Linux by reading environment variables
     * set by the desktop environment. Returns null if no scaling hint is found
     * (i.e. the caller should not apply any automatic scaling, and FlatLaf's own
     * DPI detection should be allowed to run).
     *
     * <p>On Wayland sessions, GDK_SCALE and GDK_DPI_SCALE are often not exported to
     * child processes because the compositor handles scaling internally. In that case
     * this method returns null, which correctly defers to FlatLaf's DPI detection
     * (which reads from the GraphicsDevice reported DPI). Callers should not interpret
     * a null return as "no scaling" on Wayland -- it means "let FlatLaf decide".
     */
    public static Float detectLinuxUIScale()
    {
        if (!Platform.CURRENT.is$nix())
            return null;

        // GDK_SCALE — integer multiplier (e.g. "2")
        String gdkScale = System.getenv("GDK_SCALE");
        if (gdkScale != null && !gdkScale.isEmpty())
        {
            try
            {
                float scale = Float.parseFloat(gdkScale);
                LOG.info("Detected HiDPI scale from GDK_SCALE={}: {}", gdkScale, scale);
                return scale;
            }
            catch (NumberFormatException ex)
            {
                LOG.warn("Could not parse GDK_SCALE='{}': {}", gdkScale, ex.getMessage());
            }
        }

        // GDK_DPI_SCALE — fractional multiplier (e.g. "1.5")
        String gdkDpiScale = System.getenv("GDK_DPI_SCALE");
        if (gdkDpiScale != null && !gdkDpiScale.isEmpty())
        {
            try
            {
                float scale = Float.parseFloat(gdkDpiScale);
                LOG.info("Detected HiDPI scale from GDK_DPI_SCALE={}: {}", gdkDpiScale, scale);
                return scale;
            }
            catch (NumberFormatException ex)
            {
                LOG.warn("Could not parse GDK_DPI_SCALE='{}': {}", gdkDpiScale, ex.getMessage());
            }
        }

        // QT_SCALE_FACTOR — KDE/Qt fractional multiplier (e.g. "1.5")
        String qtScale = System.getenv("QT_SCALE_FACTOR");
        if (qtScale != null && !qtScale.isEmpty())
        {
            try
            {
                float scale = Float.parseFloat(qtScale);
                LOG.info("Detected HiDPI scale from QT_SCALE_FACTOR={}: {}", qtScale, scale);
                return scale;
            }
            catch (NumberFormatException ex)
            {
                LOG.warn("Could not parse QT_SCALE_FACTOR='{}': {}", qtScale, ex.getMessage());
            }
        }

        // QT_AUTO_SCREEN_SCALE_FACTOR — KDE HiDPI enable flag; "1" means 2x
        String qtAuto = System.getenv("QT_AUTO_SCREEN_SCALE_FACTOR");
        if ("1".equals(qtAuto))
        {
            LOG.info("Detected HiDPI from QT_AUTO_SCREEN_SCALE_FACTOR=1, applying 2.0x scale");
            return 2.0f;
        }

        // No explicit scale env var found. On Wayland sessions the compositor owns
        // scaling and doesn't always export GDK_SCALE/QT_SCALE_FACTOR to child
        // processes. Returning null here lets FlatLaf's built-in DPI detection run,
        // which is the correct behavior for Wayland.
        if (IS_WAYLAND_SESSION)
        {
            LOG.info("Wayland session: no explicit scale env var found; "
                + "deferring HiDPI scale to FlatLaf automatic DPI detection.");
        }

        return null;
    }

    public static void scaleAll(float scale)
    {
        scale = (float)Math.sqrt(scale);
        System.setProperty(FlatSystemProperties.UI_SCALE, Float.toString(scale));
        UIDefaults defaults = UIManager.getDefaults();
        for (Enumeration<Object> e = defaults.keys(); e.hasMoreElements();)
        {
            Object key = e.nextElement();
            Object value = defaults.get(key);
            if (value instanceof Font)
            {
                Font font = (Font)value;
                int newSize = Math.round(font.getSize2D() * scale);
                if (value instanceof FontUIResource)
                {
                    defaults.put(key, new FontUIResource(font.getName(), font.getStyle(), newSize));
                }
                else
                {
                    defaults.put(key, new Font(font.getName(), font.getStyle(), newSize));
                }
            }
        }
    }

    /**
     * Selects a UIResource font family that can render the active UI language.
     * This covers ordinary Swing labels, menus, buttons, tabs, and dialogs before
     * they are constructed, avoiding missing-glyph boxes for CJK locales.
     */
    public static void applyLocaleFonts(Locale locale)
    {
        if (java.awt.GraphicsEnvironment.isHeadless())
            return;
        String sample = localeFontSample(locale);
        if (sample == null || sample.isEmpty())
            return;

        Font base = UIManager.getFont("defaultFont");
        if (base == null)
            base = UIManager.getFont("Label.font");
        if (base == null)
            base = new Font(Font.DIALOG, Font.PLAIN, 12);
        if (fontCovers(base, sample))
            return;

        Font localeFont = findFontForText(sample, base, localePreferredFamilies(locale));
        if (localeFont == null || !fontCovers(localeFont, sample))
            localeFont = fontForText(sample, base);
        // Last resort: Java's logical composite fonts (Dialog/SansSerif/Serif) aggregate the
        // platform's physical fonts, so on any system that has *some* font for this script
        // known to the font subsystem they render it even when no named physical family
        // matched above. This is what saves a minimal Linux desktop from all-boxes text.
        if (localeFont == null || !fontCovers(localeFont, sample))
            for (String logical : new String[]{ Font.DIALOG, Font.SANS_SERIF, Font.SERIF })
            {
                Font candidate = new Font(logical, base.getStyle(), base.getSize());
                if (fontCovers(candidate, sample))
                {
                    localeFont = candidate;
                    break;
                }
            }
        if (localeFont == null || !fontCovers(localeFont, sample))
        {
            // Nothing installed can draw this language; leave the default so at least ASCII
            // shows, and tell the user how to fix it instead of failing silently to boxes.
            LOG.warn("No installed font can render the '{}' UI language — text may appear as empty boxes. "
                   + "Install a font that covers it (on Linux: the 'fonts-noto-cjk' package for Japanese/Chinese/Korean), then restart.",
                     locale == null ? Locale.getDefault() : locale);
            return;
        }

        String family = localeFont.getFamily();
        UIDefaults defaults = UIManager.getDefaults();
        for (Enumeration<Object> e = defaults.keys(); e.hasMoreElements();)
        {
            Object key = e.nextElement();
            Object value = defaults.get(key);
            if (value instanceof FontUIResource)
            {
                Font font = (Font)value;
                defaults.put(key, new FontUIResource(family, font.getStyle(), font.getSize()));
            }
        }
        UIManager.put("defaultFont", new FontUIResource(family, base.getStyle(), base.getSize()));
        LOG.info("Applied UI font '{}' for locale {}", family, locale == null ? Locale.getDefault() : locale);
    }

    private static String localeFontSample(Locale locale)
    {
        String lang = locale == null ? Locale.getDefault().getLanguage() : locale.getLanguage();
        if (lang == null)
            return "";
        switch (lang.toLowerCase(Locale.ROOT))
        {
        case "ja": return "日本語ひらがなカタカナ漢字";
        case "zh": return "中文简体繁體漢字";
        case "ko": return "한국어한글漢字";
        case "ru": return "Русский";
        case "el": return "Ελληνικά";
        case "he": return "עברית";
        case "ar": return "العربية";
        case "th": return "ภาษาไทย";
        case "hi": return "हिन्दी";
        default:   return "";
        }
    }

    private static java.util.List<String> localePreferredFamilies(Locale locale)
    {
        String lang = locale == null ? Locale.getDefault().getLanguage() : locale.getLanguage();
        if (lang == null)
            lang = "";
        switch (lang.toLowerCase(Locale.ROOT))
        {
        case "ja":
            return java.util.Arrays.asList(
                "Yu Gothic UI", "Yu Gothic", "Meiryo", "MS PGothic", "MS Gothic",
                "Noto Sans JP", "Noto Sans CJK JP", "Hiragino Sans",
                "Hiragino Kaku Gothic ProN", "TakaoPGothic", "IPAPGothic");
        case "zh":
            return java.util.Arrays.asList(
                "Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "SimHei",
                "Noto Sans CJK SC", "Noto Sans CJK TC", "PingFang SC",
                "PingFang TC", "WenQuanYi Micro Hei", "WenQuanYi Zen Hei");
        case "ko":
            return java.util.Arrays.asList(
                "Malgun Gothic", "Malgun Gothic Semilight", "Noto Sans CJK KR",
                "Apple SD Gothic Neo", "Gulim", "Batang", "Baekmuk Gulim");
        default:
            return java.util.Collections.emptyList();
        }
    }

    public static void invokeLater(Runnable func)
    {
        SwingUtilities.invokeLater(func);
    }
    public static void invokeOrLater(Runnable func)
    {
        if (SwingUtilities.isEventDispatchThread())
            func.run();
        else
            SwingUtilities.invokeLater(func);
    }
    public static void invoke(Runnable func) throws InterruptedException
    {
        if (SwingUtilities.isEventDispatchThread())
            func.run();
        else try
        {
            SwingUtilities.invokeAndWait(func);
        }
        catch (InvocationTargetException itex)
        {
            throw Throwables.yeetCause(itex);
        }
    }
    public static <T> T get(Supplier<T> func) throws InterruptedException
    {
        Object[] value = new Object[1];
        invoke(() -> value[0] = func.get());
        @SuppressWarnings("unchecked")
        T ret = (T)value[0];
        return ret;
    }
    public static void invokeWait(Runnable func)
    {
        try
        {
            invoke(func);
        }
        catch (InterruptedException iex)
        {
            Thread.currentThread().interrupt();
        }
    }
    public static <T> T getWait(Supplier<T> func)
    {
        Object[] value = new Object[1];
        invokeWait(() -> value[0] = func.get());
        @SuppressWarnings("unchecked")
        T ret = (T)value[0];
        return ret;
    }
    public static <T> T getWait_x(Supplier<T> func)
    {
        return func.get();
    }

    // ── High-DPI dialog sizing ─────────────────────────────────────────────────

    /**
     * Cap a dialog content component so that {@link javax.swing.JOptionPane}-
     * produced dialogs fit on the user's screen, wrapping the component in a
     * {@link JScrollPane} when its preferred height exceeds a safe fraction of
     * the screen.
     *
     * <p><b>Why this exists:</b>
     * {@code JOptionPane.showConfirmDialog} / {@code showMessageDialog} produce
     * non-resizable dialogs sized to the preferred height of the content.  On
     * Windows at 125%/150%/200% display scale, tall HTML-label panels (e.g. the
     * eSpeak / xdg-open installer consent dialogs) end up taller than the
     * usable screen height.  The JOptionPane's auto-generated Yes/No button
     * row is rendered BELOW the content, so it ends up below the bottom edge of
     * the screen — invisible and un-clickable.  This helper wraps tall content
     * in a scroll pane whose preferred size is capped, so the dialog fits on
     * screen and the buttons stay visible.</p>
     *
     * <p><b>No-op when content fits.</b>
     * If {@code content}'s preferred size is already within the screen
     * threshold, it is returned unchanged — ordinary short dialogs keep their
     * original appearance with no scrollbars.</p>
     *
     * <p><b>Caller usage:</b>
     * <pre>{@code
     * JPanel panel = ...;
     * JOptionPane.showConfirmDialog(parent,
     *     Swing.fitToScreen(panel),
     *     title, JOptionPane.YES_NO_OPTION);
     * }</pre>
     * </p>
     *
     * @param content the dialog content; may be {@code null}
     * @return {@code content} unchanged if it already fits, otherwise a
     *         {@code JScrollPane} wrapping it with a screen-aware preferred
     *         size.  Returns {@code null} when {@code content} is {@code null}.
     */
    public static JComponent fitToScreen(JComponent content)
    {
        if (content == null)
            return null;
        // getPreferredSize() forces a layout pass for the component, which is
        // what drives HTML JLabel sizing off the style='width:…px' in the source.
        Dimension pref = content.getPreferredSize();
        Dimension screen;
        try
        {
            screen = Toolkit.getDefaultToolkit().getScreenSize();
        }
        catch (java.awt.HeadlessException hex)
        {
            // Shouldn't happen — callers already checked GraphicsEnvironment.isHeadless().
            return content;
        }
        // Leave room for:
        //   • the title bar at the top of the dialog,
        //   • the button row added BELOW the content by JOptionPane,
        //   • the Windows/GNOME/KDE task bar.
        // 70% height / 85% width is a comfortable target on 720p-and-up displays.
        int maxH = Math.max(300, (int) (screen.height * 0.70));
        int maxW = Math.max(400, (int) (screen.width  * 0.85));

        if (pref.height <= maxH && pref.width <= maxW)
            return content;

        JScrollPane scroll = new JScrollPane(content,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        // A borderless, transparent scroll pane — so the wrapped content still
        // blends into the JOptionPane's own background and doesn't grow a
        // chrome line between content and dialog frame.
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        // Make the mouse wheel feel normal on HTML-heavy content (default is 1px).
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(
            Math.min(pref.width,  maxW),
            Math.min(pref.height, maxH)
        ));
        return scroll;
    }

    // ── Unicode-aware font fallback ────────────────────────────────────────────

    /**
     * Cache: Unicode script → best available fallback Font at the size we last
     * asked for. We only cache by script (not per-codepoint) since all codepoints
     * in a script will be covered by the same font family.
     *
     * <p>Key: "{script.name()}:{size}:{style}" e.g. "CYRILLIC:13:0"
     * Value: a Font that can display that script at that size/style, or null if
     * no system font covers it (in which case we stop trying for that script).
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Font>>
        FALLBACK_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Sorted list of candidate fallback font family names, tried in preference
     * order when the primary font cannot display a character.  Lazily populated
     * from {@link java.awt.GraphicsEnvironment#getAvailableFontFamilyNames()} the
     * first time it is needed.
     */
    private static volatile java.util.List<String> FALLBACK_FAMILIES = null;

    private static java.util.List<String> fallbackFamilies()
    {
        java.util.List<String> f = FALLBACK_FAMILIES;
        if (f != null)
            return f;
        synchronized (Swing.class)
        {
            if (FALLBACK_FAMILIES != null)
                return FALLBACK_FAMILIES;

            // Pan-Unicode fonts that cover the widest range of scripts.
            // Listed in preference order — if the user has any of these installed
            // they will be tried first before scanning the full font list.
            java.util.List<String> preferred = java.util.Arrays.asList(
                "Noto Sans",
                "Noto Sans CJK SC",
                "Noto Sans CJK TC",
                "Noto Sans CJK JP",
                "Noto Sans CJK KR",
                "Unifont",
                "GNU Unifont",
                "Arial Unicode MS",
                "Code2000",
                "Segoe UI",          // Windows — covers Cyrillic, Arabic, Hebrew, etc.
                "Microsoft YaHei",   // Windows — CJK
                "SimSun",            // Windows — CJK
                "Yu Gothic",         // Windows — Japanese
                "Malgun Gothic",     // Windows — Korean
                "Gulim",             // Windows — Korean
                "Batang",            // Windows — Korean
                "MS Gothic",         // Windows — Japanese
                "MingLiU",           // Windows — Traditional Chinese
                "DejaVu Sans",       // Linux — broad Unicode coverage
                "Liberation Sans",   // Linux — broad coverage
                "FreeSans",          // Linux
                "Droid Sans Fallback",// Linux/Android
                "WenQuanYi Micro Hei",// Linux — CJK
                "WenQuanYi Zen Hei",  // Linux — CJK
                "AR PL UMing CN",     // Linux — CJK
                "TakaoPGothic",       // Linux — Japanese
                "IPAPGothic",         // Linux — Japanese
                "Baekmuk Gulim"       // Linux — Korean
            );

            java.util.Set<String> available = new java.util.HashSet<>(
                java.util.Arrays.asList(
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames()));

            // Build the final list: preferred first (if present), then everything else
            java.util.List<String> result = new java.util.ArrayList<>(preferred.size() + available.size());
            for (String pref : preferred)
                if (available.contains(pref))
                    result.add(pref);
            for (String name : available)
                if (!result.contains(name))
                    result.add(name);

            FALLBACK_FAMILIES = result;
            LOG.debug("Font fallback chain has {} candidate families.", result.size());
            return result;
        }
    }

    /**
     * Returns a font capable of displaying every codepoint in {@code text} at
     * the same size and style as {@code baseFont}.  If the base font already
     * covers the entire string the base font is returned unchanged.  If a
     * fallback is found it is returned; if no installed font covers all
     * characters the best partial match is returned so at least some characters
     * are rendered correctly.
     *
     * <p>Results are cached by Unicode script so repeated calls for the same
     * kind of text are O(1).
     *
     * @param text     the string to be rendered (may contain any Unicode)
     * @param baseFont the font currently in use for this cell
     * @return {@code baseFont} if adequate, otherwise the best fallback
     */
    /**
     * Keeps short plain-text messages unchanged when the dialog font covers every
     * character. Short messages needing font fallback use an HTML-escaped label.
     * Long messages use bounded, scrollable plain text with the appropriate font,
     * so wrapping cannot push the dialog's button row out of view.
     */
    public static Object dialogMessage(String message)
    {
        if (message == null || message.isEmpty())
            return message;
        Font base = UIManager.getFont("OptionPane.messageFont");
        if (base == null)
            base = UIManager.getFont("Label.font");
        if (base == null)
            return message;
        boolean allCovered = true;
        for (int i = 0; i < message.length(); )
        {
            int cp = message.codePointAt(i);
            i += Character.charCount(cp);
            if (cp != '\n' && cp != '\r' && cp != '\t' && !base.canDisplay(cp))
            {
                allCovered = false;
                break;
            }
        }
        if (allCovered)
            return fitLongMessage(message, base);
        Font fallback = fontForText(message, base);
        Object longMessage = fitLongMessage(message, fallback);
        if (longMessage instanceof JScrollPane)
            return longMessage;
        String html = "<html>" + message
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\r\n", "<br>").replace("\n", "<br>") + "</html>";
        JLabel label = new JLabel(html);
        label.setFont(fallback);
        return fitToScreen(label);
    }

    /**
     * Plain-text dialog content sizing. Short, few-line messages are returned unchanged (as a
     * String) so ordinary dialogs keep JOptionPane's native rendering. Long or many-line messages
     * always use a bounded scrolling text area, leaving the JOptionPane button row outside
     * the scrollable content. Wrapped text must be measured at its intended width: measuring
     * a zero-width JTextArea can underestimate the height before the dialog is packed.
     */
    private static Object fitLongMessage(String message, Font base)
    {
        if (message == null || message.isEmpty())
            return message;
        int lines = 1;
        for (int i = 0; i < message.length(); i++)
            if (message.charAt(i) == '\n')
                lines++;
        if (message.length() < 600 && lines <= 12)
            return message;

        Dimension available;
        try
        {
            java.awt.GraphicsConfiguration configuration = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            java.awt.Rectangle bounds = configuration.getBounds();
            java.awt.Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            available = new Dimension(Math.max(1, bounds.width - insets.left - insets.right),
                Math.max(1, bounds.height - insets.top - insets.bottom));
        }
        catch (java.awt.HeadlessException unavailable)
        {
            available = new Dimension(1024, 768);
        }
        return scrollableMessage(message, base, available);
    }

    /** Screen dimensions are logical pixels; the remaining space is reserved for dialog chrome. */
    static JScrollPane scrollableMessage(String message, Font base, Dimension availableScreen)
    {
        javax.swing.JTextArea area = new javax.swing.JTextArea(message);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(null);
        area.setColumns(52);
        if (base != null)
            area.setFont(base);
        area.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(area,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setViewportBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(Math.max(1, area.getFontMetrics(area.getFont()).getHeight()));

        int maxWidth = Math.max(1, (int) (availableScreen.width * 0.80));
        int maxHeight = Math.max(1, (int) (availableScreen.height * 0.65));
        int scrollbarWidth = scroll.getVerticalScrollBar().getPreferredSize().width;
        int width = Math.min(maxWidth, area.getPreferredSize().width + scrollbarWidth);
        // Reserve scrollbar space before measuring; when it appears, wrapping must not
        // grow the content beyond a bare component's previously measured preferred size.
        area.setSize(Math.max(1, width - scrollbarWidth), maxHeight);
        int height = Math.min(maxHeight, Math.max(1, area.getPreferredSize().height));
        scroll.setPreferredSize(new Dimension(width, height));
        return scroll;
    }

    public static Font fontForText(String text, Font baseFont)
    {
        if (text == null || text.isEmpty())
            return baseFont;

        int firstMissing = firstMissingCodePoint(text, baseFont);
        if (firstMissing < 0)
            return baseFont;

        // Find the first codepoint the base font cannot display and derive
        // a cache key from its Unicode script
        Character.UnicodeScript needScript = null;
        try { needScript = Character.UnicodeScript.of(firstMissing); }
        catch (IllegalArgumentException ex) { needScript = Character.UnicodeScript.UNKNOWN; }
        if (needScript == null)
            return baseFont;

        String cacheKey = needScript.name() + ":" + baseFont.getSize() + ":" + baseFont.getStyle();
        java.util.Optional<Font> cached = FALLBACK_CACHE.get(cacheKey);
        if (cached != null)
            return cached.orElse(baseFont);

        // Search fallback families for one that covers the script
        Font found = null;
        for (String family : fallbackFamilies())
        {
            Font candidate = new Font(family, baseFont.getStyle(), baseFont.getSize());
            if (candidate.canDisplay(firstMissing))
            {
                // Quick check passed: verify every codepoint the base font lacks.
                boolean covers = true;
                for (int i = 0; i < text.length(); )
                {
                    int cp = text.codePointAt(i);
                    i += Character.charCount(cp);
                    if (!baseFont.canDisplay(cp) && !candidate.canDisplay(cp))
                    {
                        covers = false;
                        break;
                    }
                }
                if (covers)
                {
                    found = candidate;
                    break;
                }
            }
        }

        java.util.Optional<Font> result = java.util.Optional.ofNullable(found);
        FALLBACK_CACHE.put(cacheKey, result);
        if (found != null)
            LOG.debug("Font fallback: {} for script {} → {}", baseFont.getFamily(), needScript, found.getFamily());
        else
            LOG.warn("No font fallback found for script {} (text: {})", needScript, text);
        return found != null ? found : baseFont;
    }

    private static boolean fontCovers(Font font, String text)
    {
        return firstMissingCodePoint(text, font) < 0;
    }

    private static int firstMissingCodePoint(String text, Font font)
    {
        if (text == null || text.isEmpty() || font == null)
            return -1;
        for (int i = 0; i < text.length(); )
        {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r' || cp == '\t')
                continue;
            if (!font.canDisplay(cp))
                return cp;
        }
        return -1;
    }

    private static Font findFontForText(String text, Font baseFont, java.util.List<String> preferredFamilies)
    {
        Font preferred = findFontForTextInFamilies(text, baseFont, preferredFamilies);
        return preferred != null ? preferred : findFontForTextInFamilies(text, baseFont, fallbackFamilies());
    }

    private static Font findFontForTextInFamilies(String text, Font baseFont, java.util.List<String> families)
    {
        if (text == null || text.isEmpty() || baseFont == null || families == null || families.isEmpty())
            return null;
        int firstMissing = firstMissingCodePoint(text, baseFont);
        if (firstMissing < 0)
            return baseFont;
        for (String family : families)
        {
            Font candidate = new Font(family, baseFont.getStyle(), baseFont.getSize());
            if (candidate.canDisplay(firstMissing) && fontCovers(candidate, text))
                return candidate;
        }
        return null;
    }

    private Swing()
    {
        throw new UnsupportedOperationException();
    }

}

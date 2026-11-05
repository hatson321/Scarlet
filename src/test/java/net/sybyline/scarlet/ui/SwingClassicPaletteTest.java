package net.sybyline.scarlet.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

class SwingClassicPaletteTest
{
    @Test void classicFallbackHasCompletePaletteWithoutReplacingSystemTheme()
    {
        Color previous = Swing.ACCENT;
        Object panel = UIManager.get("Panel.background"), focus = UIManager.get("Component.focusColor");
        try
        {
            Color selected = new Color(30, 170, 230);
            Swing.applyTheme(selected, true);
            assertEquals(selected, Swing.ACCENT);
            assertNotNull(Swing.ACCENT_DARK);
            assertNotNull(Swing.ACCENT_GLOW);
            assertNotNull(Swing.SEL_BG);
            assertEquals(80, Swing.ACCENT_GLOW.getAlpha());
            assertEquals(panel, UIManager.get("Panel.background"));
            assertEquals(focus, UIManager.get("Component.focusColor"));
        }
        finally { Swing.applyTheme(previous); }
    }
}

package net.sybyline.scarlet.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

class SwingDialogMessageTest
{
    private static final String PROSE = "This synthetic startup notice explains an optional setup step. "
        + "Read the complete notice before selecting the confirmation button. ";

    @Test void shortAsciiMessageKeepsNativeOptionPaneRendering() throws Exception
    {
        onEdt(() -> {
            String message = "Startup setup is complete.\nPress OK to continue.";
            assertSame(message, Swing.dialogMessage(message));
        });
    }

    @Test void nullAndEmptyMessagesRemainUnchanged() throws Exception
    {
        onEdt(() -> {
            assertNull(Swing.dialogMessage(null));
            assertSame("", Swing.dialogMessage(""));
        });
    }

    @Test void publicDialogMessageWrapsLongProseAndManyShortLines() throws Exception
    {
        onEdt(() -> {
            String[] messages = {PROSE.repeat(12), "Startup notice\n".repeat(14)};
            for (String message : messages)
            {
                Object content = Swing.dialogMessage(message);
                assertTrue(content instanceof JScrollPane, "Long messages must always be scrollable content");
                assertMessageArea((JScrollPane) content, message);
            }
        });
    }

    @Test void longProseFitsScreenAtNormalAndLargeFontsAndCanScrollToEnd() throws Exception
    {
        onEdt(() -> {
            Dimension[] screens = {
                new Dimension(1920, 1080), new Dimension(1366, 768), new Dimension(800, 600)
            };
            for (Dimension screen : screens)
                for (int fontSize : new int[] {14, 36})
                {
                    String message = PROSE.repeat(120) + "FINAL NOTICE";
                    Font font = new Font(Font.DIALOG, Font.PLAIN, fontSize);
                    JScrollPane scroll = Swing.scrollableMessage(message, font, screen);
                    JTextArea area = assertMessageArea(scroll, message);
                    assertEquals(font, area.getFont());
                    assertFitsScreen(scroll, screen);
                    assertCanScrollToEnd(scroll, area);
                }
        });
    }

    @Test void longMessageNeedingFontFallbackStillUsesScrollableText() throws Exception
    {
        onEdt(() -> {
            // U+0378 is unassigned, so ordinary dialog fonts cannot cover this character.
            // Exercise the fallback branch without depending on installed language fonts.
            String message = ("\u0378 " + PROSE).repeat(12);
            Object content = Swing.dialogMessage(message);
            assertTrue(content instanceof JScrollPane,
                "Unicode font fallback must not bypass long-message sizing");
            assertMessageArea((JScrollPane) content, message);
        });
    }

    @Test void explicitNewlinesStayIntactAndRemainReachable() throws Exception
    {
        onEdt(() -> {
            String message = "A synthetic setup notice.\n\n".repeat(100) + "FINAL LINE";
            Dimension screen = new Dimension(800, 600);
            JScrollPane scroll = Swing.scrollableMessage(message,
                new Font(Font.DIALOG, Font.PLAIN, 28), screen);
            JTextArea area = assertMessageArea(scroll, message);
            assertFitsScreen(scroll, screen);
            assertCanScrollToEnd(scroll, area);
        });
    }

    @Test void verySmallScreenDoesNotAcquireAnOversizedMinimum() throws Exception
    {
        onEdt(() -> {
            Dimension screen = new Dimension(320, 240);
            String message = PROSE.repeat(12);
            JScrollPane scroll = Swing.scrollableMessage(message,
                new Font(Font.DIALOG, Font.PLAIN, 24), screen);
            JTextArea area = assertMessageArea(scroll, message);
            assertFitsScreen(scroll, screen);
            assertCanScrollToEnd(scroll, area);
        });
    }

    @Test void wrappedHeightIsMeasuredAtTheDisplayWidth() throws Exception
    {
        onEdt(() -> {
            String message = PROSE.repeat(6);
            JScrollPane scroll = Swing.scrollableMessage(message,
                new Font(Font.DIALOG, Font.PLAIN, 16), new Dimension(800, 600));
            JTextArea area = assertMessageArea(scroll, message);
            layout(scroll);
            int lineHeight = area.getFontMetrics(area.getFont()).getHeight();
            assertTrue(area.getPreferredSize().height > lineHeight * 2,
                "The prose must occupy multiple wrapped lines at the viewport width");
            assertTrue(scroll.getViewport().getExtentSize().height > lineHeight * 2,
                "Initial sizing must reserve the wrapped height instead of a single unwrapped line");
        });
    }

    private static JTextArea assertMessageArea(JScrollPane scroll, String message)
    {
        assertTrue(SwingUtilities.isEventDispatchThread());
        assertTrue(scroll.getViewport().getView() instanceof JTextArea);
        JTextArea area = (JTextArea) scroll.getViewport().getView();
        assertEquals(message, area.getText(), "The full original notice must be retained");
        assertFalse(area.isEditable());
        assertTrue(area.getLineWrap());
        assertTrue(area.getWrapStyleWord());
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            scroll.getVerticalScrollBarPolicy());
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            scroll.getHorizontalScrollBarPolicy());
        return area;
    }

    private static void assertFitsScreen(JScrollPane scroll, Dimension screen)
    {
        Dimension preferred = scroll.getPreferredSize();
        assertTrue(preferred.width > 0 && preferred.height > 0);
        assertTrue(preferred.width <= (int) (screen.width * 0.80),
            "Message width must leave space around the dialog on " + screen);
        assertTrue(preferred.height <= (int) (screen.height * 0.65),
            "Message height must leave space for the confirmation buttons on " + screen);
    }

    private static void layout(JScrollPane scroll)
    {
        scroll.setSize(scroll.getPreferredSize());
        scroll.doLayout();
        scroll.getViewport().doLayout();
        // The viewport's wrapped content height can cause the vertical bar to appear.
        scroll.doLayout();
        scroll.getViewport().doLayout();
    }

    private static void assertCanScrollToEnd(JScrollPane scroll, JTextArea area)
    {
        layout(scroll);
        JViewport viewport = scroll.getViewport();
        JScrollBar vertical = scroll.getVerticalScrollBar();
        assertTrue(vertical.isVisible(), "Overflowing notices must expose a vertical scrollbar");
        assertFalse(scroll.getHorizontalScrollBar().isVisible());
        assertTrue(viewport.getViewSize().height > viewport.getExtentSize().height);
        assertEquals(viewport.getExtentSize().width, area.getWidth(),
            "Text must reflow to the visible viewport width");
        assertEquals(0, viewport.getViewPosition().y, "The notice must initially show its beginning");

        vertical.setValue(vertical.getMaximum());
        assertTrue(viewport.getViewPosition().y > 0, "The scrollbar must move the text viewport");
        Rectangle end = assertDoesNotThrow(() -> area.modelToView(area.getDocument().getLength()));
        assertNotNull(end);
        Rectangle visible = viewport.getViewRect();
        assertTrue(end.y >= visible.y && end.y + end.height <= visible.y + visible.height,
            "Scrolling to the bottom must reveal the final line of the notice");
    }

    private static void onEdt(Runnable assertions) throws Exception
    {
        SwingUtilities.invokeAndWait(assertions);
    }
}

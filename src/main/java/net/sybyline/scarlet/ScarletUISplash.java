package net.sybyline.scarlet;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import net.sybyline.scarlet.ui.Swing;

public class ScarletUISplash implements IScarletUISplash
{

    // ── Palette ────────────────────────────────────────────────────────────────
    static final Color BG          = new Color( 22,  22,  30);
    static final Color BG_CARD     = new Color( 30,  30,  42);
    static final Color BG_FOOTER   = new Color( 18,  18,  26);
    // Swing.ACCENT and Swing.ACCENT_GLOW are read from Swing at paint time so they always
    // reflect the user's chosen colour without requiring a restart.
    static final Color DIVIDER     = new Color( 55,  55,  72);
    static final Color TEXT_MAIN   = new Color(225, 225, 238);
    static final Color TEXT_DIM    = new Color(130, 130, 148);

    // ── Popup icons ────────────────────────────────────────────────────────────
    private static final String ICON_OK   = "\u2713 ";
    private static final String ICON_WARN = "\u26A0 ";
    private static final String ICON_INFO = "\u2022 ";

    // ──────────────────────────────────────────────────────────────────────────

    public ScarletUISplash(Scarlet scarlet)
    {
        this.scarlet = scarlet;
        if (SwingUtilities.isEventDispatchThread())
            this.buildSplash();
        else try
        {
            SwingUtilities.invokeAndWait(this::buildSplash);
        }
        catch (Exception ex)
        {
            throw new RuntimeException("Failed to create splash on EDT", ex);
        }
    }

    final Scarlet scarlet;

    JWindow splash;
    JLabel splashText;
    JLabel splashSubtext;
    Timer   dotTimer;

    // ── Feedback popup ─────────────────────────────────────────────────────────

    public void queueFeedbackPopup(Component component, long durationMillis,
                                   String text, String subtext,
                                   Color textcolor, Color subtextcolor)
    {
        SwingUtilities.invokeLater(() -> this.showFeedbackPopup(component, durationMillis, text, subtext, textcolor, subtextcolor));
    }

    private void showFeedbackPopup(Component component, long durationMillis, String text, String subtext, Color textcolor, Color subtextcolor)
    {
        if (component == null)
        {
            Component[] ref = new Component[1];
            this.scarlet.ui.jframe(jframe -> ref[0] = jframe);
            component = ref[0];
        }
        durationMillis = Math.max(500L, Math.min(30_000L, durationMillis));
        if (textcolor    == null) textcolor    = TEXT_MAIN;
        if (subtextcolor == null) subtextcolor = TEXT_DIM;

        final Color tc = textcolor;
        boolean isError   = tc.getRed() > 160 && tc.getGreen() < 120;
        boolean isSuccess = tc.getGreen() > 160 && tc.getRed() < 180;
        String  icon      = isError ? ICON_WARN : isSuccess ? ICON_OK : ICON_INFO;
        Color   stripe    = isError ? new Color(180, 40, 50) : isSuccess ? new Color(40, 160, 80) : Swing.ACCENT;

        boolean hasSubtext = subtext != null && !subtext.isEmpty();
        int     height     = hasSubtext ? 68 : 46;

        // Custom-painted popup panel
        final Color stripeColor = stripe;
        JPanel root = new JPanel(new BorderLayout())
        {
            private static final long serialVersionUID = 1L;
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Background
                g2.setColor(BG_CARD);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                // Left accent stripe
                g2.setColor(stripeColor);
                g2.fillRoundRect(0, 0, 4, getHeight(), 4, 4);
                // Subtle border
                g2.setColor(DIVIDER);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8);
            }
        };
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 14));

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);

        JLabel lbl_text = new JLabel(icon + text);
        // Popup text can carry user display names — apply Unicode font fallback
        // so fancy-glyph names don't render as missing-glyph squares.
        lbl_text.setFont(net.sybyline.scarlet.ui.Swing.fontForText(icon + text, new Font("Dialog", Font.BOLD, 13)));
        lbl_text.setForeground(tc);
        lbl_text.setAlignmentX(Component.LEFT_ALIGNMENT);
        textPanel.add(lbl_text);

        if (hasSubtext)
        {
            textPanel.add(Box.createVerticalStrut(3));
            JLabel lbl_sub = new JLabel("   " + subtext);
            lbl_sub.setFont(net.sybyline.scarlet.ui.Swing.fontForText(subtext, new Font("Dialog", Font.PLAIN, 11)));
            lbl_sub.setForeground(subtextcolor);
            lbl_sub.setAlignmentX(Component.LEFT_ALIGNMENT);
            textPanel.add(lbl_sub);
        }
        root.add(textPanel, BorderLayout.CENTER);

        JWindow feedback = new JWindow();
        Timer dismiss = new Timer((int)durationMillis, event -> { feedback.setVisible(false); feedback.dispose(); });
        dismiss.setRepeats(false); dismiss.start();

        feedback.setBackground(new Color(0, 0, 0, 0));
        feedback.setContentPane(root);
        feedback.setSize(260, height);
        feedback.setAlwaysOnTop(true);
        feedback.setFocusable(false);
        feedback.setLocationRelativeTo(component);
        feedback.setVisible(true);
    }

    // ── Splash window ──────────────────────────────────────────────────────────

    private void buildSplash()
    {
        this.splash = new JWindow();
        this.splashText = new JLabel(net.sybyline.scarlet.util.I18n.tr("splash.loading"), SwingConstants.CENTER);
        this.splashSubtext = new JLabel("", SwingConstants.CENTER);
        // Logo is 1024×1024 — display it square so it is never stretched
        final int LOGO_SIZE = 300;  // px — fits comfortably in a 420-wide window
        final int W         = 420;

        Image logoImg = Toolkit.getDefaultToolkit().createImage(
            ScarletUI.class.getResource("sybyline_scarlet.png"));

        // Pre-scale on this (EDT) call so the ImageIcon has final dimensions
        Image logoScaled = logoImg.getScaledInstance(LOGO_SIZE, LOGO_SIZE, Image.SCALE_SMOOTH);

        // ── Animated dot counter ───────────────────────────────────────────────
        AtomicInteger dotCount = new AtomicInteger(0);

        // ── Logo panel — square, centred, with a subtle radial glow behind ─────
        JPanel logoPanel = new JPanel(new java.awt.GridBagLayout())
        {
            private static final long serialVersionUID = 1L;
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Dark background
                g2.setColor(BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                // Soft radial glow centred behind the logo
                int cx = getWidth() / 2, cy = getHeight() / 2;
                int gR = LOGO_SIZE / 2 + 40;
                for (int i = gR; i > 0; i -= 4)
                {
                    float alpha = 0.18f * (1f - (float) i / gR);
                    g2.setColor(new Color(
                        Swing.ACCENT.getRed(), Swing.ACCENT.getGreen(), Swing.ACCENT.getBlue(),
                        Math.max(0, Math.min(255, (int)(alpha * 255)))));
                    g2.fillOval(cx - i, cy - i, i * 2, i * 2);
                }
            }
        };
        logoPanel.setOpaque(false);
        logoPanel.setPreferredSize(new Dimension(W, LOGO_SIZE + 40));
        logoPanel.add(new JLabel(new ImageIcon(logoScaled)));

        // ── Custom footer panel ────────────────────────────────────────────────
        JPanel footer = new JPanel(new BorderLayout(0, 0))
        {
            private static final long serialVersionUID = 1L;
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, BG_FOOTER, 0, getHeight(), BG));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(DIVIDER);
                g2.fillRect(0, 0, getWidth(), 1);
                g2.setColor(Swing.ACCENT_GLOW);
                g2.fillRect(0, 0, getWidth(), 3);
            }
        };
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(14, 20, 16, 20));

        JPanel textCol = new JPanel();
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        textCol.setOpaque(false);

        JLabel nameLabel = new JLabel(Scarlet.NAME, SwingConstants.LEFT);
        nameLabel.setFont(new Font("Dialog", Font.BOLD, 18));
        nameLabel.setForeground(TEXT_MAIN);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(nameLabel);

        JLabel verLabel = new JLabel(Scarlet.VERSION, SwingConstants.LEFT);
        verLabel.setFont(new Font("Dialog", Font.PLAIN, 11));
        verLabel.setForeground(TEXT_DIM);
        verLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(verLabel);

        textCol.add(Box.createVerticalStrut(10));

        this.splashText.setFont(new Font("Dialog", Font.PLAIN, 12));
        this.splashText.setForeground(TEXT_MAIN);
        this.splashText.setHorizontalAlignment(SwingConstants.LEFT);
        this.splashText.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(this.splashText);

        textCol.add(Box.createVerticalStrut(2));

        this.splashSubtext.setFont(new Font("Dialog", Font.PLAIN, 10));
        this.splashSubtext.setForeground(TEXT_DIM);
        this.splashSubtext.setHorizontalAlignment(SwingConstants.LEFT);
        this.splashSubtext.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(this.splashSubtext);

        footer.add(textCol, BorderLayout.CENTER);

        // ── Animated dot indicator ─────────────────────────────────────────────
        JPanel dotPanel = new JPanel()
        {
            private static final long serialVersionUID = 1L;
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int n = 3, r = 5, gap = 10;
                int totalW = n * r * 2 + (n - 1) * gap;
                int startX = (getWidth() - totalW) / 2;
                int cy = getHeight() / 2;
                int active = dotCount.get() % n;
                for (int i = 0; i < n; i++)
                {
                    int cx = startX + i * (r * 2 + gap) + r;
                    if (i == active)
                    {
                        // Glowing active dot
                        g2.setColor(new Color(Swing.ACCENT.getRed(), Swing.ACCENT.getGreen(), Swing.ACCENT.getBlue(), 60));
                        g2.fillOval(cx - r - 3, cy - r - 3, (r + 3) * 2, (r + 3) * 2);
                        g2.setColor(Swing.ACCENT);
                        g2.fillOval(cx - r, cy - r, r * 2, r * 2);
                    }
                    else
                    {
                        g2.setColor(DIVIDER);
                        g2.fillOval(cx - r, cy - r, r * 2, r * 2);
                    }
                }
            }
            @Override
            public Dimension getPreferredSize() { return new Dimension(60, 40); }
        };
        dotPanel.setOpaque(false);
        footer.add(dotPanel, BorderLayout.EAST);

        this.dotTimer = new Timer(400, e ->
        {
            dotCount.incrementAndGet();
            dotPanel.repaint();
        });
        this.dotTimer.start();

        // ── Root panel ─────────────────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout(0, 0))
        {
            private static final long serialVersionUID = 1L;
            @Override
            protected void paintComponent(Graphics g)
            {
                g.setColor(BG);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        root.setOpaque(true);
        root.add(logoPanel, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);

        // ── Window ─────────────────────────────────────────────────────────────
        this.splash.setContentPane(root);
        this.splash.pack();
        this.splash.setLocationRelativeTo(null);
        this.splash.setFocusable(false);
        this.splash.setBackground(BG);
        this.splash.setVisible(true);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void close()
    {
        Swing.invokeWait(this::closeOnEdt);
    }

    private void closeOnEdt()
    {
        JWindow s = this.splash;
        if (s == null)
            return;
        if (this.dotTimer != null)
        {
            this.dotTimer.stop();
            this.dotTimer = null;
        }
        this.splash       = null;
        this.splashText   = null;
        this.splashSubtext = null;
        s.setVisible(false);
        s.dispose();
        this.scarlet.ui.showMainWindow();
    }

    public void splashText(String text)
    {
        JLabel lbl = this.splashText;
        if (lbl != null)
            SwingUtilities.invokeLater(() -> lbl.setText(text));
        this.splashSubtext("");
    }

    public void splashSubtext(String text)
    {
        JLabel lbl = this.splashSubtext;
        if (lbl != null)
            SwingUtilities.invokeLater(() -> lbl.setText(text));
    }

}

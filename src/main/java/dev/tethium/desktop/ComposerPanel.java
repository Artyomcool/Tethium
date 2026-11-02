package dev.tethium.desktop;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;

/** One focused surface for text and its actions, without nested field borders. */
final class ComposerPanel extends JPanel {
    private final JTextArea input;
    ComposerPanel(JTextArea input) {
        super(new BorderLayout(0, Theme.UNIT)); this.input = input;
        setOpaque(false); setBorder(new EmptyBorder(Theme.GAP, Theme.GAP, Theme.GAP, Theme.GAP));
        input.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent e) { repaint(); }
            public void focusLost(FocusEvent e) { repaint(); }
        });
    }
    @Override protected void paintComponent(Graphics g) {
        var paint = (Graphics2D) g.create(); paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        paint.setColor(Theme.FIELD); paint.fillRoundRect(1, 1, getWidth()-3, getHeight()-3, 14, 14);
        paint.setColor(input.hasFocus() ? Theme.PRIMARY : Theme.BORDER); paint.drawRoundRect(1, 1, getWidth()-3, getHeight()-3, 14, 14); paint.dispose();
        super.paintComponent(g);
    }
}

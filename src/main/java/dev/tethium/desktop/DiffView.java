package dev.tethium.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A unified diff drawn the way GitHub shows it: old and new line numbers in a gutter, added and removed lines on full-width
 * green and red bands with the text in its normal colour, hunk headers on their own band and one bar per file instead of the
 * index/---/+++ lines. Only visible lines are painted, so large patches stay fast.
 */
final class DiffView extends JComponent implements Scrollable {
    private enum Kind { FILE, META, HUNK, CONTEXT, ADDED, REMOVED, NOTE }
    private record Row(Kind kind, int oldNumber, int newNumber, String text) {}
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@ ?(.*)$");
    private static final Color ADDED = new Color(0x1f3a2c), ADDED_GUTTER = new Color(0x26503a), REMOVED = new Color(0x41242a), REMOVED_GUTTER = new Color(0x5a2e36),
        HUNK_BAND = new Color(0x1e2a42), FILE_BAR = new Color(0x2a2f3a), GUTTER = new Color(0x1b1d22), LINE = new Color(0x30343d);
    private List<Row> rows = List.of();
    private String raw = "";
    private int lineHeight, ascent, charWidth, gutterDigits = 4, widest;

    DiffView() {
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13)); setOpaque(true); setBackground(Theme.BACKGROUND); setFocusable(true); measure();
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "copy-diff");
        getActionMap().put("copy-diff", new AbstractAction() { public void actionPerformed(ActionEvent e) { copy(); } });
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });
    }
    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger() || raw.isEmpty()) return;
        var menu = new JPopupMenu(); var copy = new JMenuItem("Копировать дифф"); copy.addActionListener(a -> copy()); menu.add(copy); menu.show(this, e.getX(), e.getY());
    }
    private void copy() { if (!raw.isEmpty()) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(raw), null); }

    void setText(String text) {
        raw = text == null ? "" : text;
        var parsed = new ArrayList<Row>(); int oldLine = 0, newLine = 0; boolean inHunk = false; int maxNumber = 0;
        for (String line : raw.replace("\t", "    ").split("\n", -1)) {
            if (line.startsWith("diff --git ")) { int b = line.lastIndexOf(" b/"); parsed.add(new Row(Kind.FILE, 0, 0, b > 0 ? line.substring(b + 3) : line.substring(11))); inHunk = false; continue; }
            if (!inHunk && (line.startsWith("index ") || line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("similarity index") || line.startsWith("old mode") || line.startsWith("new mode"))) continue;
            if (!inHunk && (line.startsWith("new file mode") || line.startsWith("deleted file mode") || line.startsWith("rename from") || line.startsWith("rename to"))) {
                String note = line.startsWith("new file") ? "новый файл" : line.startsWith("deleted") ? "файл удалён" : line.startsWith("rename from") ? "переименован из " + line.substring(12) : "в " + line.substring(10);
                parsed.add(new Row(Kind.NOTE, 0, 0, note)); continue;
            }
            var hunk = HUNK.matcher(line);
            if (hunk.matches()) { oldLine = Integer.parseInt(hunk.group(1)); newLine = Integer.parseInt(hunk.group(2)); inHunk = true; parsed.add(new Row(Kind.HUNK, 0, 0, line)); continue; }
            if (inHunk && line.startsWith("+")) { parsed.add(new Row(Kind.ADDED, 0, newLine, line.substring(1))); maxNumber = Math.max(maxNumber, newLine++); continue; }
            if (inHunk && line.startsWith("-")) { parsed.add(new Row(Kind.REMOVED, oldLine, 0, line.substring(1))); maxNumber = Math.max(maxNumber, oldLine++); continue; }
            if (inHunk && line.startsWith(" ")) { parsed.add(new Row(Kind.CONTEXT, oldLine, newLine, line.substring(1))); maxNumber = Math.max(maxNumber, Math.max(oldLine++, newLine++)); continue; }
            if (inHunk && line.startsWith("\\")) { parsed.add(new Row(Kind.NOTE, 0, 0, line.substring(1).strip())); continue; }
            inHunk = false; parsed.add(new Row(Kind.META, 0, 0, line));
        }
        while (!parsed.isEmpty() && parsed.getLast().kind == Kind.META && parsed.getLast().text.isBlank()) parsed.removeLast();
        rows = parsed; gutterDigits = Math.max(3, String.valueOf(maxNumber).length());
        measure();
        widest = 0; for (var row : rows) widest = Math.max(widest, row.text.length());
        revalidate(); repaint();
        if (getParent() instanceof JViewport viewport) viewport.setViewPosition(new Point(0, 0));
    }

    private void measure() { var metrics = getFontMetrics(getFont()); lineHeight = metrics.getHeight() + 4; ascent = metrics.getAscent() + 2; charWidth = metrics.charWidth('0'); }
    private int gutterWidth() { return (gutterDigits * charWidth + 16) * 2; }
    private int textX() { return gutterWidth() + 2 * charWidth + 8; }
    @Override public Dimension getPreferredSize() { return new Dimension(textX() + widest * charWidth + 40, Math.max(1, rows.size()) * Math.max(1, lineHeight) + 8); }

    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); g.setFont(getFont());
            var clip = g.getClipBounds(); g.setColor(getBackground()); g.fillRect(clip.x, clip.y, clip.width, clip.height);
            int width = Math.max(getWidth(), getPreferredSize().width), gutter = gutterWidth(), half = gutter / 2;
            g.setColor(GUTTER); g.fillRect(0, clip.y, gutter, clip.height);
            int first = Math.max(0, clip.y / lineHeight), last = Math.min(rows.size() - 1, (clip.y + clip.height) / lineHeight);
            var bold = getFont().deriveFont(Font.BOLD);
            for (int i = first; i <= last; i++) {
                var row = rows.get(i); int y = i * lineHeight;
                switch (row.kind) {
                    case FILE -> {
                        g.setColor(FILE_BAR); g.fillRect(0, y, width, lineHeight); g.setColor(LINE); g.drawLine(0, y, width, y);
                        g.setFont(bold); g.setColor(Theme.TEXT); g.drawString(row.text, 12, y + ascent); g.setFont(getFont()); continue;
                    }
                    case HUNK -> { g.setColor(HUNK_BAND); g.fillRect(0, y, width, lineHeight); g.setColor(Theme.ACCENT); g.drawString(row.text, textX() - 2 * charWidth, y + ascent); continue; }
                    case ADDED -> { g.setColor(ADDED); g.fillRect(gutter, y, width, lineHeight); g.setColor(ADDED_GUTTER); g.fillRect(0, y, gutter, lineHeight); }
                    case REMOVED -> { g.setColor(REMOVED); g.fillRect(gutter, y, width, lineHeight); g.setColor(REMOVED_GUTTER); g.fillRect(0, y, gutter, lineHeight); }
                    default -> {}
                }
                g.setColor(Theme.MUTED);
                if (row.oldNumber > 0) { String n = String.valueOf(row.oldNumber); g.drawString(n, half - 8 - n.length() * charWidth, y + ascent); }
                if (row.newNumber > 0) { String n = String.valueOf(row.newNumber); g.drawString(n, gutter - 8 - n.length() * charWidth, y + ascent); }
                String sign = row.kind == Kind.ADDED ? "+" : row.kind == Kind.REMOVED ? "−" : "";
                if (!sign.isEmpty()) { g.setColor(row.kind == Kind.ADDED ? Theme.GREEN : Theme.RED); g.drawString(sign, gutter + 6, y + ascent); }
                g.setColor(row.kind == Kind.META || row.kind == Kind.NOTE ? Theme.MUTED : Theme.TEXT);
                g.drawString(row.text, row.kind == Kind.META ? 12 : textX(), y + ascent);
            }
        } finally { g.dispose(); }
    }

    public Dimension getPreferredScrollableViewportSize() { return new Dimension(800, 600); }
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return orientation == SwingConstants.VERTICAL ? Math.max(1, lineHeight) * 3 : Math.max(8, charWidth * 4); }
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return orientation == SwingConstants.VERTICAL ? visible.height - lineHeight : visible.width / 2; }
    public boolean getScrollableTracksViewportWidth() { return getParent() instanceof JViewport viewport && viewport.getWidth() > getPreferredSize().width; }
    public boolean getScrollableTracksViewportHeight() { return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height; }
}

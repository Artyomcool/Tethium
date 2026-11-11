package dev.tethium.desktop;

import org.xhtmlrenderer.extend.TextRenderer;
import org.xhtmlrenderer.render.Box;
import org.xhtmlrenderer.render.FSFont;
import org.xhtmlrenderer.render.InlineLayoutBox;
import org.xhtmlrenderer.render.InlineText;
import org.xhtmlrenderer.simple.XHTMLPanel;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * A Flying Saucer panel whose text can be selected and copied. The renderer lays the text out in pieces; a selection runs from one
 * character of a piece to another in document order, is painted over the page and copied with line breaks where lines change.
 */
final class SelectablePanel extends XHTMLPanel {
    private record Piece(String text, int x, int y, int height, FSFont font) {}
    private record Point(int piece, int offset) implements Comparable<Point> {
        public int compareTo(Point other) { return piece != other.piece ? Integer.compare(piece, other.piece) : Integer.compare(offset, other.offset); }
    }
    private List<Piece> pieces = List.of();
    private Point anchor, focus;
    private java.awt.Point pressed;

    SelectablePanel() {
        setFocusable(true);
        var mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                requestFocusInWindow(); pieces = collect(); pressed = e.getPoint(); anchor = hit(e.getPoint()); focus = anchor; repaint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (pressed == null || anchor == null) return;
                focus = hit(e.getPoint()); repaint();
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) menu(e); pressed = null; }
        };
        addMouseListener(mouse); addMouseMotionListener(mouse);
        addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) menu(e); } });
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcut), "copy-selection");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut), "select-all");
        getActionMap().put("copy-selection", new AbstractAction() { public void actionPerformed(ActionEvent e) { copy(); } });
        getActionMap().put("select-all", new AbstractAction() { public void actionPerformed(ActionEvent e) { selectAll(); } });
    }
    private void menu(MouseEvent e) {
        var menu = new JPopupMenu();
        var copy = new JMenuItem("Копировать"); copy.setEnabled(!selectedText().isEmpty()); copy.addActionListener(a -> copy()); menu.add(copy);
        var all = new JMenuItem("Выделить всё"); all.addActionListener(a -> selectAll()); menu.add(all);
        menu.show(this, e.getX(), e.getY());
    }
    void selectAll() { pieces = collect(); if (pieces.isEmpty()) return; anchor = new Point(0, 0); focus = new Point(pieces.size() - 1, pieces.getLast().text.length()); repaint(); }
    private void copy() { String text = selectedText(); if (!text.isEmpty()) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); }

    /** Every piece of text with where it stands on the page, in document order. */
    private List<Piece> collect() {
        var root = getRootBox(); if (root == null) return List.of();
        var result = new ArrayList<Piece>(); var seen = Collections.newSetFromMap(new IdentityHashMap<Box, Boolean>());
        walk(root, result, seen); return result;
    }
    private void walk(Box box, List<Piece> out, Set<Box> seen) {
        if (!seen.add(box)) return;
        if (box instanceof InlineLayoutBox inline) {
            for (var child : inline.getInlineChildren()) {
                if (child instanceof InlineText text) { String value = text.getSubstring(); if (value != null && !value.isEmpty()) out.add(new Piece(value, inline.getAbsX() + text.getX(), inline.getAbsY(), Math.max(1, inline.getHeight()), inline.getStyle().getFSFont(getLayoutContext()))); }
                else if (child instanceof Box nested) walk(nested, out, seen);
            }
        }
        for (int i = 0; i < box.getChildCount(); i++) walk(box.getChild(i), out, seen);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private int width(Piece piece, int characters) {
        if (characters <= 0) return 0;
        TextRenderer renderer = getSharedContext().getTextRenderer();
        return renderer.getWidth(getLayoutContext().getFontContext(), piece.font, piece.text.substring(0, Math.min(characters, piece.text.length())));
    }
    /** The character under a point; outside the text, the nearest end of its line or of the page. */
    private Point hit(java.awt.Point point) {
        if (pieces.isEmpty()) return null;
        Point best = null;
        for (int i = 0; i < pieces.size(); i++) {
            var piece = pieces.get(i);
            if (point.y < piece.y || point.y >= piece.y + piece.height) continue;
            if (point.x < piece.x) { if (best == null) best = new Point(i, 0); continue; }
            int right = piece.x + width(piece, piece.text.length());
            if (point.x >= right) { best = new Point(i, piece.text.length()); continue; }
            int offset = 0; while (offset < piece.text.length() && piece.x + (width(piece, offset) + width(piece, offset + 1)) / 2 < point.x) offset++;
            return new Point(i, offset);
        }
        if (best != null) return best;
        for (int i = pieces.size() - 1; i >= 0; i--) if (pieces.get(i).y <= point.y) return new Point(i, pieces.get(i).text.length());
        return new Point(0, 0);
    }
    String selectedText() {
        if (anchor == null || focus == null || anchor.equals(focus)) return "";
        var start = anchor.compareTo(focus) <= 0 ? anchor : focus; var end = start == anchor ? focus : anchor;
        var out = new StringBuilder(); int lastY = Integer.MIN_VALUE;
        for (int i = start.piece; i <= end.piece && i < pieces.size(); i++) {
            var piece = pieces.get(i);
            int from = i == start.piece ? start.offset : 0, to = i == end.piece ? end.offset : piece.text.length();
            if (lastY != Integer.MIN_VALUE && piece.y != lastY) out.append('\n');
            out.append(piece.text, Math.max(0, from), Math.max(from, Math.min(to, piece.text.length()))); lastY = piece.y;
        }
        return out.toString().strip();
    }
    @Override public void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (anchor == null || focus == null || anchor.equals(focus)) return;
        var start = anchor.compareTo(focus) <= 0 ? anchor : focus; var end = start == anchor ? focus : anchor;
        var g = (Graphics2D) graphics.create();
        try {
            g.setColor(new Color(Theme.ACCENT.getRed(), Theme.ACCENT.getGreen(), Theme.ACCENT.getBlue(), 90));
            for (int i = start.piece; i <= end.piece && i < pieces.size(); i++) {
                var piece = pieces.get(i);
                int from = i == start.piece ? start.offset : 0, to = i == end.piece ? end.offset : piece.text.length();
                int x0 = piece.x + width(piece, from), x1 = piece.x + width(piece, to);
                if (x1 > x0) g.fillRect(x0, piece.y, x1 - x0, piece.height);
            }
        } finally { g.dispose(); }
    }
}

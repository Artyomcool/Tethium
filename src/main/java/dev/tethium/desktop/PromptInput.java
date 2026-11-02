package dev.tethium.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.undo.UndoManager;

/** JTextArea has no native placeholder; keep the hint outside its editable document. */
final class PromptInput extends JTextArea {
    private final UndoManager undo = new UndoManager();
    PromptInput() {
        super(2, 20); getAccessibleContext().setAccessibleName("Сообщение Codex");
        getDocument().addUndoableEditListener(undo);
        bind(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK, "undo", () -> { if (isEditable() && undo.canUndo()) undo.undo(); });
        bind(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, "redo", () -> { if (isEditable() && undo.canRedo()) undo.redo(); });
        bind(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK, "redo", () -> { if (isEditable() && undo.canRedo()) undo.redo(); });
    }
    private void bind(int key, int modifiers, String name, Runnable action) {
        getInputMap().put(KeyStroke.getKeyStroke(key, modifiers), name);
        getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent event) { action.run(); } });
    }
    /** Loading a different draft must never undo into another session or resend a submitted message. */
    @Override public void setText(String text) { super.setText(text); if (undo != null) undo.discardAllEdits(); }
    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!getText().isEmpty()) return;
        var paint = (Graphics2D) g.create(); paint.setFont(getFont()); paint.setColor(Theme.MUTED);
        paint.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        var insets = getInsets(); paint.drawString("Сообщение Codex…", insets.left, insets.top + paint.getFontMetrics().getAscent()); paint.dispose();
    }
}

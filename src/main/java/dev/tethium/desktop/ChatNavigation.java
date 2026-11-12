package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pinned request navigation and a scroll overview; anchors add no content to the conversation. */
final class ChatNavigation extends JPanel {
    record Task(long sequence, int number, String title) {
        String anchor() { return "task-" + sequence; }
        @Override public String toString() { return number + " · " + title; }
    }
    record Position(boolean tail, int absolute, long sequence, int offset, long navigation) {}
    private final ChatLayoutTrace trace;
    ChatLayoutTrace trace(){return trace;}
    private final ChatTranscript transcript;
    private final JScrollPane history;
    private final JComboBox<Task> picker = new JComboBox<>();
    private final JButton previous, next, end;
    private final RequestMap requestMap = new RequestMap();
    private java.util.function.LongToIntFunction virtualPosition;
    void virtualPositions(java.util.function.LongToIntFunction positions){virtualPosition=positions;}
    private List<Task> tasks = List.of();
    private int[] positions = new int[0];
    private boolean changing, restoring, rendering, followTail = true;
    private long navigation;
    private int lastMaximum,lastExtent;
    private Long requestedTask;
    private Position renderingPosition;

    ChatNavigation(ChatTranscript transcript, JScrollPane history) {
        super(new BorderLayout(Theme.GAP, 0)); this.transcript = transcript; this.history = history;trace=new ChatLayoutTrace(transcript,history);
        setName("chat.navigation"); setBorder(new EmptyBorder(Theme.UNIT, Theme.GUTTER, Theme.UNIT, Theme.GUTTER));
        previous = Theme.button("", "Предыдущий запрос · Alt+PageUp", () -> relative(-1)); previous.setIcon(arrow(-1));
        next = Theme.button("", "Следующий запрос · Alt+PageDown", () -> relative(1)); next.setIcon(arrow(1));
        end = Theme.button("В конец", "К последнему сообщению и новым ответам · Ctrl+End в истории", this::toEnd);
        previous.setName("chat.previousTask"); next.setName("chat.nextTask"); end.setName("chat.toEnd");
        for (var button : List.of(previous, next, end)) { Theme.toolbar(button); button.getAccessibleContext().setAccessibleName(button.getToolTipText()); }
        var arrows = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); arrows.add(previous); arrows.add(next); add(arrows, BorderLayout.WEST);
        picker.setName("chat.tasks"); picker.setMaximumRowCount(8); picker.setEditable(false);
        picker.getAccessibleContext().setAccessibleName("Текущий запрос · выбрать другой"); picker.setPreferredSize(new Dimension(100, 36)); picker.setMinimumSize(new Dimension(40, 36));
        picker.setRenderer(new RequestRenderer());
        picker.addActionListener(e -> { if (!changing && picker.getSelectedItem() instanceof Task task) jump(task.sequence); });
        add(picker); add(end, BorderLayout.EAST);
        history.getVerticalScrollBar().addAdjustmentListener(e -> {
            var bar=history.getVerticalScrollBar();boolean resized=lastMaximum!=bar.getMaximum()||lastExtent!=bar.getVisibleAmount();lastMaximum=bar.getMaximum();lastExtent=bar.getVisibleAmount();
            if (restoring) return;
            if(virtualPosition!=null&&resized&&followTail&&!rendering){restoring=true;try{setBottom();}finally{restoring=false;}updateSelection();return;}
            if (!rendering) { followTail = atEnd(); requestedTask = null; navigation++; updateSelection(); }
        });
        bind(history, KeyEvent.VK_PAGE_UP, InputEvent.ALT_DOWN_MASK, "previousTask", () -> previous.doClick());
        bind(history, KeyEvent.VK_PAGE_DOWN, InputEvent.ALT_DOWN_MASK, "nextTask", () -> next.doClick());
        bind(history, KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK, "chatEnd", this::toEnd);
        bind(transcript, KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK, "chatEnd", this::toEnd);
        transcript.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK), "chatEnd");
        bind(this, KeyEvent.VK_PAGE_UP, InputEvent.ALT_DOWN_MASK, "previousTask", () -> previous.doClick());
        bind(this, KeyEvent.VK_PAGE_DOWN, InputEvent.ALT_DOWN_MASK, "nextTask", () -> next.doClick());
        history.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { SwingUtilities.invokeLater(() -> { if (!rendering) { indexPositions(); updateSelection(); } }); }
        });
        updateSelection();
    }
    void reset() { trace.operation("navigation.reset");navigation++; requestedTask = null; renderingPosition = null; rendering = false; followTail = true; setTasks(List.of()); positions = new int[0]; updateSelection(); }
    static List<Task> tasks(JsonNode messages) {
        var result = new ArrayList<Task>();
        for (var message : messages) if (message.path("role").asText().equals("user")) {
            String title = message.path("text").asText().strip().replaceAll("\\s+", " ");
            if (title.codePointCount(0, title.length()) > 320) title = title.substring(0, title.offsetByCodePoints(0, 320)) + "…";
            result.add(new Task(message.path("sequence").asLong(), result.size() + 1, title));
        }
        return List.copyOf(result);
    }
    JComponent requestMap() { return requestMap; }
    void setTasks(List<Task> updated) {
        if (tasks.equals(updated)) return;
        tasks = updated; changing = true;
        try { picker.setModel(new DefaultComboBoxModel<>(tasks.toArray(Task[]::new))); }
        finally { changing = false; }
    }
    private void indexPositions() {
        if(virtualPosition!=null){positions=new int[tasks.size()];for(int i=0;i<tasks.size();i++)positions[i]=Math.max(0,virtualPosition.applyAsInt(tasks.get(i).sequence));return;}
        boolean wasRestoring = restoring; restoring = true;
        try {
        var viewport = history.getViewport(); int width = viewport.getExtentSize().width;
        // Swing recalculates wrapped HTML height after the viewport width has been applied.
        if (width > 0) {
            transcript.setSize(width, Math.max(viewport.getHeight(), transcript.getPreferredSize().height));
            transcript.setSize(width, Math.max(viewport.getHeight(), transcript.getPreferredSize().height));
            viewport.doLayout();
        }
        positions = new int[tasks.size()];
        if (!(transcript.getDocument() instanceof HTMLDocument document)) return;
        for (int i = 0; i < tasks.size(); i++) {
            var element = document.getElement(tasks.get(i).anchor());
            try { var bounds = element == null ? null : transcript.modelToView2D(element.getStartOffset()); positions[i] = bounds == null ? 0 : Math.max(0, (int) bounds.getY() - 16); }
            catch (javax.swing.text.BadLocationException ignored) { positions[i] = 0; }
        }
        } finally { restoring = wasRestoring; }
    }
    Position beforeChange(boolean forceTail) {
        trace.operation("navigation.before","forceTail",forceTail,"followTail",followTail,"rendering",rendering,"navigation",navigation,"stack",ChatLayoutTrace.stack());
        if (forceTail) { followTail = true; requestedTask = null; navigation++; }
        if (!rendering || renderingPosition == null || forceTail) renderingPosition = position();
        rendering = true; return renderingPosition;
    }
    void afterChange(Position saved) { afterChange(saved, false); }
    /** hold: keep the given position even while following the tail; the next real layout moves to the end. */
    void afterChange(Position saved, boolean hold) {
        trace.operation("navigation.after","savedY",saved.absolute,"savedTask",saved.sequence,"savedOffset",saved.offset,"savedTail",saved.tail,"savedNavigation",saved.navigation,"navigation",navigation,"followTail",followTail);
        indexPositions(); restoring = true;
        try {
            if (hold && followTail) history.getVerticalScrollBar().setValue(saved.absolute);
            else if (followTail && (saved.tail || saved.navigation != navigation)) setBottom();
            else if (saved.navigation != navigation && requestedTask != null) setTaskPosition(requestedTask, 0);
            else if (saved.navigation == navigation && !saved.tail) {
                if (!setTaskPosition(saved.sequence, saved.offset)) history.getVerticalScrollBar().setValue(saved.absolute);
            }
            else if (saved.navigation == navigation && saved.tail) setBottom();
        } finally { restoring = false; rendering = false; renderingPosition = null; }
        updateSelection();
    }
    private Position position() {
        int y = history.getVerticalScrollBar().getValue(), index = currentIndex(y);
        return new Position(followTail, y, index < 0 ? -1 : tasks.get(index).sequence, index < 0 ? 0 : y - positions[index], navigation);
    }
    JsonNode savePosition() {
        var p = rendering && renderingPosition != null ? renderingPosition : position();
        return Json.object("tail", followTail, "scroll", p.absolute, "task", p.sequence, "offset", p.offset);
    }
    void restorePosition(JsonNode saved) {
        trace.operation("navigation.restore","savedY",saved.path("scroll").asInt(),"savedTask",saved.path("task").asLong(-1),"savedOffset",saved.path("offset").asInt(),"savedTail",saved.path("tail").asBoolean());
        indexPositions(); navigation++; requestedTask = null; followTail = saved.path("tail").asBoolean(); restoring = true;
        try {
            if (followTail) setBottom();
            else if (!setTaskPosition(saved.path("task").asLong(-1), saved.path("offset").asInt())) history.getVerticalScrollBar().setValue(saved.path("scroll").asInt());
        } finally { restoring = false; }
        updateSelection();
    }
    void toEnd() {
        trace.operation("navigation.toEnd");
        transcript.select(transcript.getDocument().getLength(), transcript.getDocument().getLength());
        followTail = true; requestedTask = null; navigation++; restoring = true;
        try { setBottom(); } finally { restoring = false; }
        updateSelection();
    }
    private void setBottom() { trace.operation("navigation.setBottom","stack",ChatLayoutTrace.stack());var bar = history.getVerticalScrollBar(); bar.setValue(bar.getMaximum()); history.getViewport().doLayout(); bar.setValue(bar.getMaximum()); }
    private void relative(int delta) {
        int index = picker.getSelectedIndex(); if (index + delta >= 0 && index + delta < tasks.size()) jump(tasks.get(index + delta).sequence);
    }
    private void jump(long sequence) {
        trace.operation("navigation.jump","task",sequence);

        transcript.select(0, 0);
        followTail = false; requestedTask = sequence; navigation++;
        if (!rendering) { indexPositions(); restoring = true; try { setTaskPosition(sequence, 0); } finally { restoring = false; } }
        updateSelection();
    }
    private boolean setTaskPosition(long sequence, int offset) {
        for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).sequence == sequence && i < positions.length) { history.getVerticalScrollBar().setValue(Math.max(0, positions[i] + offset)); return true; }
        return false;
    }
    private boolean atEnd() { var bar = history.getVerticalScrollBar(); return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4; }
    private int currentIndex(int y) {
        if (tasks.isEmpty() || positions.length != tasks.size()) return -1;
        int low = 0, high = positions.length - 1;
        while (low < high) { int mid = (low + high + 1) / 2; if (positions[mid] <= y + 18) low = mid; else high = mid - 1; }
        return low;
    }
    private void updateSelection() {
        int index = currentIndex(history.getVerticalScrollBar().getValue());
        if (requestedTask != null) for (int i = 0; i < tasks.size(); i++) if (Objects.equals(tasks.get(i).sequence, requestedTask)) index = i;
        if (followTail && !tasks.isEmpty()) index = tasks.size() - 1;
        changing = true; try { if (picker.getSelectedIndex() != index) picker.setSelectedIndex(index); } finally { changing = false; }
        picker.setEnabled(!tasks.isEmpty()); picker.setToolTipText(index < 0 ? "Запросы пользователя в этой сессии" : tooltip(tasks.get(index)));
        previous.setEnabled(index > 0); next.setEnabled(index >= 0 && index + 1 < tasks.size());
        end.setEnabled(!atEnd() || !followTail); end.setForeground(followTail ? Theme.MUTED : Theme.ACCENT);
        requestMap.repaint();
    }
    private String tooltip(Task task) { return "<html><div style='width:360px'><b>Запрос " + task.number + " из " + tasks.size() + "</b><br>" + Theme.escape(task.title) + "</div></html>"; }
    private final class RequestRenderer extends JPanel implements ListCellRenderer<Task> {
        private final JLabel number = new JLabel();
        private final JTextArea preview = new JTextArea();
        RequestRenderer() {
            super(new BorderLayout(Theme.GAP, 0)); setBorder(new EmptyBorder(Theme.UNIT, Theme.GAP, Theme.UNIT, Theme.GAP));
            number.setVerticalAlignment(SwingConstants.TOP); number.setFont(number.getFont().deriveFont(11f));
            preview.setLineWrap(true); preview.setWrapStyleWord(true); preview.setEditable(false); preview.setOpaque(false);
            preview.setFont(number.getFont().deriveFont(12f)); preview.setBorder(null); add(number, BorderLayout.WEST); add(preview);
        }
        @Override protected void paintComponent(Graphics graphics) {
            // The same renderer is reused by the narrow pinned control and the wider popup.
            // CellRendererPane can paint it without validating its children first.
            doLayout(); super.paintComponent(graphics);
        }
        @Override public Component getListCellRendererComponent(JList<? extends Task> list, Task task, int index, boolean selected, boolean focus) {
            boolean pinned = index < 0; setBackground(selected && !pinned ? list.getSelectionBackground() : list.getBackground());
            number.setText(task == null ? "" : task.number + (pinned ? "/" + tasks.size() : "")); number.setForeground(Theme.ACCENT);
            preview.setForeground(selected && !pinned ? list.getSelectionForeground() : list.getForeground());
            int width = pinned ? Math.max(40, picker.getWidth() - 30) : Math.max(320, Math.min(520, picker.getWidth()));
            int textWidth = Math.max(20, width - number.getPreferredSize().width - Theme.GAP * 3);
            preview.setText(shortPreview(task == null ? "Нет запросов" : task.title, textWidth, pinned ? 2 : 3, preview.getFontMetrics(preview.getFont()))); preview.setCaretPosition(0);
            setPreferredSize(new Dimension(width, pinned ? 32 : 50));
            return this;
        }
    }
    private static String shortPreview(String text, int width, int lines, FontMetrics metrics) {
        var result = new StringBuilder();
        for (int line = 0; line < lines && !text.isEmpty(); line++) {
            if (line > 0) result.append('\n');
            if (metrics.stringWidth(text) <= width) { result.append(text); break; }
            boolean last = line == lines - 1; int fit = 0;
            while (fit < text.length()) {
                int next = text.offsetByCodePoints(fit, 1);
                if (metrics.stringWidth(text.substring(0, next) + (last ? "…" : "")) > width) break;
                fit = next;
            }
            if (fit == 0) { result.append('…'); break; }
            int space = text.lastIndexOf(' ', fit); if (!last && space > 0) fit = space;
            result.append(text, 0, fit); if (last) result.append('…');
            text = text.substring(fit).stripLeading();
        }
        return result.toString();
    }
    private final class RequestMap extends JPanel {
        RequestMap() {
            setName("chat.requestMap"); setPreferredSize(new Dimension(18, 0)); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName("Карта запросов · нажмите метку для перехода"); setToolTipText("");
            addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent event) { int index = nearest(event.getY()); if (SwingUtilities.isLeftMouseButton(event) && index >= 0) jump(tasks.get(index).sequence); } });
            addMouseWheelListener(event -> history.dispatchEvent(SwingUtilities.convertMouseEvent(this, event, history)));
        }
        private int mapY(int documentY) {
            int height = Math.max(1, getHeight() - 12), total = Math.max(1, history.getVerticalScrollBar().getMaximum());
            return 6 + (int) Math.round((double) documentY / total * height);
        }
        private int nearest(int y) {
            int nearest = -1, distance = Integer.MAX_VALUE;
            for (int i = 0; i < positions.length; i++) { int delta = Math.abs(y - mapY(positions[i])); if (delta < distance) { nearest = i; distance = delta; } }
            return nearest;
        }
        @Override public String getToolTipText(MouseEvent event) { int index = nearest(event.getY()); return index < 0 || index >= tasks.size() ? null : tooltip(tasks.get(index)); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var bar = history.getVerticalScrollBar(); int top = mapY(bar.getValue()), bottom = mapY(bar.getValue() + bar.getVisibleAmount());
            g.setColor(Theme.FIELD); g.fillRoundRect(2, top, getWidth() - 4, Math.max(5, bottom - top), 4, 4);
            g.setColor(Theme.MUTED); for (int position : positions) g.fillRoundRect(5, mapY(position) - 1, getWidth() - 10, 2, 2, 2);
            int selected = picker.getSelectedIndex(); if (selected >= 0 && selected < positions.length) { g.setColor(Theme.ACCENT); g.fillRoundRect(2, mapY(positions[selected]) - 2, getWidth() - 4, 4, 4, 4); }
            g.dispose();
        }
    }
    private static void bind(JComponent component, int key, int modifiers, String name, Runnable action) {
        component.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key, modifiers), name);
        component.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
    }
    private static Icon arrow(int direction) {
        return new Icon() {
            public int getIconWidth() { return 14; } public int getIconHeight() { return 14; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                var g = (Graphics2D) graphics.create(); g.translate(x, y); if (direction > 0) g.rotate(Math.PI, 7, 7);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(c.isEnabled() ? Theme.TEXT : Theme.BORDER); g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(9, 3, 5, 7); g.drawLine(5, 7, 9, 11); g.dispose();
            }
        };
    }
}

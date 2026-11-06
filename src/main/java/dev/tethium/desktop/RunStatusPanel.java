package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Compact, live feedback beside the composer, independent of chat scroll position. */
final class RunStatusPanel extends JPanel {
    private final JPanel activity = new JPanel(new BorderLayout(Theme.GAP, Theme.UNIT)), queue = new JPanel(new BorderLayout(Theme.GAP, Theme.UNIT));
    private final JProgressBar spinner = new JProgressBar();
    private final JLabel phase = new JLabel(), detail = new JLabel(), queueTitle = new JLabel();
    private final JTextArea recent = new JTextArea();
    private final JButton details, resume, remove, queueToggle, up, down, take, group;
    private final JPanel queueActions = new JPanel(new BorderLayout());
    private final JLabel partsLabel = new JLabel();
    private boolean editingAvailable, groupingAvailable, taking;
    private final DefaultListModel<Queued> queueModel = new DefaultListModel<>();
    private final JList<Queued> queueList = new JList<>(queueModel) { @Override public boolean getScrollableTracksViewportWidth() { return true; } };
    private final JScrollPane queueScroll = new JScrollPane(queueList);
    private boolean expanded, queueExpanded;
    private String queueSignature = "";
    private String pendingFocus;
    private String sessionId;
    private boolean paused;
    private record Queued(String id, String text, int partsCount, boolean urgent) { @Override public String toString() { return text; } }

    RunStatusPanel(Runnable pauseQueue, Runnable resumeQueue, Consumer<String> removeQueued, BiConsumer<String, String> moveQueued, BiConsumer<String, String> takeQueued, BiConsumer<Boolean,JsonNode> groupQueued) {
        super(new BorderLayout(0, Theme.GAP)); setName("run.status"); setBorder(new EmptyBorder(Theme.GAP, Theme.GUTTER, 0, Theme.GUTTER));
        var row = new JPanel(new BorderLayout(Theme.GAP, 0));
        spinner.setPreferredSize(new Dimension(28, 4)); spinner.setBorderPainted(false);
        var pulse = new JPanel(new GridBagLayout()); pulse.add(spinner); row.add(pulse, BorderLayout.WEST);
        phase.setForeground(Theme.ACCENT); phase.setName("run.phase"); row.add(phase);
        details = Theme.button("Действия", "Последние завершённые действия", this::toggleDetails); details.setIcon(Theme.icon("action-history")); Theme.toolbar(details);
        row.add(details, BorderLayout.EAST); activity.add(row, BorderLayout.NORTH);
        detail.setForeground(Theme.MUTED); detail.setName("run.detail"); activity.add(detail);
        recent.setEditable(false); recent.setFocusable(false); recent.setLineWrap(true); recent.setWrapStyleWord(true);
        recent.setFont(recent.getFont().deriveFont(11f)); recent.setForeground(Theme.MUTED); recent.setVisible(false); activity.add(recent, BorderLayout.SOUTH);
        add(activity, BorderLayout.NORTH);
        var queueHeader = new JPanel(new BorderLayout(Theme.UNIT, 0)); queueTitle.setName("queue.title"); queueTitle.setFont(queueTitle.getFont().deriveFont(11f)); queueTitle.setForeground(Theme.MUTED); queueHeader.add(queueTitle);
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.UNIT, 0));
        resume = Theme.button("Продолжить", "Приостановить следующие сообщения, не прерывая текущий ответ", () -> { if(paused) resumeQueue.run(); else pauseQueue.run(); });
        resume.setIcon(Theme.icon("play")); Theme.toolbar(resume);
        resume.setName("queue.resume"); buttons.add(resume);
        queueToggle = Theme.button("Раскрыть", "Полный текст сообщений · двойной клик или Enter в списке", this::toggleQueue);
        queueToggle.setName("queue.expand"); Theme.toolbar(queueToggle); buttons.add(queueToggle);
        remove = Theme.iconButton("queue-delete", "Удалить только выбранное сообщение из очереди", () -> {
            var chosen = queueList.getSelectedValue(); if (chosen != null) removeQueued.accept(chosen.id);
        }); remove.setName("queue.remove"); queueHeader.add(buttons, BorderLayout.EAST); queue.add(queueHeader, BorderLayout.NORTH);
        up = Theme.button("↑", "Выше в очереди · Alt+↑", () -> moveSelection(moveQueued, "up"));
        down = Theme.button("↓", "Ниже в очереди · Alt+↓", () -> moveSelection(moveQueued, "down"));
        take = Theme.button("↶", "Вернуть последний добавленный фрагмент в поле ввода · Alt+Backspace", () -> {
            var chosen = queueList.getSelectedValue(); if (chosen != null && !taking) takeQueued.accept(chosen.id, chosen.text);
        });
        up.setName("queue.up"); down.setName("queue.down"); take.setName("queue.take");
        up.setText(""); down.setText(""); take.setText("");
        up.setIcon(Theme.icon("queue-up")); down.setIcon(Theme.icon("queue-down")); take.setIcon(Theme.icon("queue-return-last"));
        var editButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.UNIT, 0));
        for (var button : java.util.List.of(up, down, take)) { Theme.toolbar(button); button.setPreferredSize(new Dimension(28, 26)); button.getAccessibleContext().setAccessibleName(button.getToolTipText()); editButtons.add(button); }
        editButtons.add(remove);
        group=Theme.button("Склеить","Ctrl/Shift — выбрать любые сообщения; склеить выбранные или разбить одно на исходные",()-> {
            var chosen=queueList.getSelectedValuesList(); if(chosen.isEmpty())return;
            groupQueued.accept(chosen.size()>1,dev.tethium.json.Json.MAPPER.valueToTree(chosen.stream().map(item->dev.tethium.json.Json.object("id",item.id,"prompt",item.text)).toList()));
        });group.setName("queue.group");Theme.toolbar(group);editButtons.add(group);queueActions.add(editButtons, BorderLayout.WEST);
        partsLabel.setForeground(Theme.MUTED); partsLabel.setFont(partsLabel.getFont().deriveFont(11f)); queueActions.add(partsLabel, BorderLayout.EAST);
        queue.add(queueActions, BorderLayout.SOUTH);
        queueActions.setVisible(false);
        queueList.setName("queue.messages"); queueList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); queueList.setFixedCellHeight(28);
        queueList.setCellRenderer(new QueueRenderer());
        ToolTipManager.sharedInstance().registerComponent(queueList);
        queueList.addListSelectionListener(e -> updateQueueActions());
        queueList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { context(e); }
            @Override public void mouseReleased(MouseEvent e) { context(e); }
            private void context(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int index = queueList.locationToIndex(e.getPoint());
                if (index >= 0 && !queueList.isSelectedIndex(index)) queueList.setSelectedIndex(index);
                if (!queueExpanded) toggleQueue();
            }
            @Override public void mouseClicked(MouseEvent e) {
                int index = queueList.locationToIndex(e.getPoint());
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && index >= 0 && queueList.getCellBounds(index, index).contains(e.getPoint())) toggleQueue();
            }
        });
        queueList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "expandQueue");
        queueList.getActionMap().put("expandQueue", new AbstractAction() { public void actionPerformed(ActionEvent e) { toggleQueue(); } });
        bindQueueKey(KeyEvent.VK_UP, "queueUp", () -> up.doClick());
        bindQueueKey(KeyEvent.VK_DOWN, "queueDown", () -> down.doClick());
        bindQueueKey(KeyEvent.VK_BACK_SPACE, "queueTake", () -> take.doClick());
        bindQueueKey(KeyEvent.VK_HOME, "queueFirst", () -> { if (up.isEnabled()) moveSelection(moveQueued, "first"); });
        bindQueueKey(KeyEvent.VK_END, "queueLast", () -> { if (down.isEnabled()) moveSelection(moveQueued, "last"); });
        queueList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copyMessage");
        queueList.getActionMap().put("copyMessage", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                var chosen = queueList.getSelectedValue();
                if (chosen != null) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(chosen.text), null);
            }
        });
        queueScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { sizeQueue(); }
        });
        queueScroll.getVerticalScrollBar().setUnitIncrement(20);
        queueScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); queueScroll.setBorder(null); queue.add(queueScroll);
        add(queue, BorderLayout.CENTER); setVisible(false);
    }
    void focusJob(String id) { pendingFocus = id; applyPendingFocus(); }
    private void applyPendingFocus() {
        if (pendingFocus == null) return;
        for (int i=0;i<queueModel.size();i++) if (queueModel.get(i).id.equals(pendingFocus)) {
            queueList.setSelectedIndex(i); queueList.ensureIndexIsVisible(i); queueList.requestFocusInWindow(); pendingFocus=null; return;
        }
    }
    private void bindQueueKey(int key, String name, Runnable action) {
        queueList.getInputMap().put(KeyStroke.getKeyStroke(key, InputEvent.ALT_DOWN_MASK), name);
        queueList.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
    }
    private void moveSelection(BiConsumer<String, String> callback, String direction) {
        var chosen = queueList.getSelectedValue(); if (chosen != null && editingAvailable && !taking) callback.accept(chosen.id, direction);
    }
    void setQueueEditing(boolean available, boolean pending) { editingAvailable = available; taking = pending; updateQueueActions(); }
    void setGroupingAvailable(boolean available) { groupingAvailable=available; updateQueueActions(); }
    private void updateQueueActions() {
        int index = queueList.getSelectedIndex(); boolean selected = index >= 0 && queueList.getSelectedIndices().length==1;
        remove.setEnabled(selected && !taking); up.setEnabled(selected && index > 0 && editingAvailable && !taking);
        down.setEnabled(selected && index < queueModel.size() - 1 && editingAvailable && !taking);
        take.setEnabled(selected && editingAvailable && !taking);
        var item = queueList.getSelectedValue();
        var chosen=queueList.getSelectedValuesList(); boolean merging=chosen.size()>1;
        group.setText(merging?"Склеить":"Разбить");group.setEnabled(groupingAvailable&&!taking&&!chosen.isEmpty()&&chosen.stream().noneMatch(Queued::urgent)&&(merging||item.partsCount>1));
        partsLabel.setText(taking ? "Операция…" : merging ? "Выбрано: "+chosen.size() : item == null ? "" : "Частей: " + item.partsCount);
        if(item!=null && item.urgent()) {up.setEnabled(false);down.setEnabled(false);take.setEnabled(false);}
        partsLabel.setToolTipText("Каждая отдельная отправка — одна часть. ↶ возвращает последнюю, остальные остаются в очереди.");
    }

    private void toggleDetails() {
        expanded = !expanded; recent.setVisible(expanded); details.setText(expanded ? "Свернуть" : "Действия"); revalidate();
    }
    private void toggleQueue() {
        if (queueModel.isEmpty()) return;
        queueExpanded = !queueExpanded; queueToggle.setText(queueExpanded ? "Свернуть" : "Раскрыть");
        queueToggle.getAccessibleContext().setAccessibleDescription(queueExpanded ? "Очередь раскрыта" : "Очередь свёрнута");
        queueActions.setVisible(queueExpanded); queueList.setFixedCellHeight(queueExpanded ? -1 : 28); sizeQueue(); revalidate();
        queueList.repaint();
    }
    boolean queueExpanded() { return queueExpanded; }
    void setUpdating(boolean value) { if (value) resume.setEnabled(false); }
    void restoreQueueExpanded(boolean value) { if (queueExpanded != value && !queueModel.isEmpty()) toggleQueue(); }
    private void sizeQueue() {
        int width = Math.max(1, queueScroll.getViewport().getExtentSize().width);
        if (queueList.getFixedCellWidth() != width) queueList.setFixedCellWidth(width);
        var window = SwingUtilities.getWindowAncestor(this);
        int maxHeight = window == null ? 200 : Math.max(100, Math.min(240, window.getHeight() / 3));
        int height = queueExpanded ? Math.min(maxHeight, queueList.getPreferredSize().height) : Math.min(3, queueModel.size()) * 28;
        var size = new Dimension(0, height);
        if (!size.equals(queueScroll.getPreferredSize())) { queueScroll.setPreferredSize(size); revalidate(); }
    }
    private final class QueueRenderer implements ListCellRenderer<Queued> {
        private final DefaultListCellRenderer compact = new DefaultListCellRenderer();
        private final JPanel compactRow = new JPanel(new BorderLayout());
        private final JLabel count = new JLabel();
        private final JPanel card = new JPanel(new BorderLayout(0, Theme.UNIT));
        private final JLabel number = new JLabel();
        private final JTextArea text = new JTextArea();
        QueueRenderer() {
            card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), new EmptyBorder(Theme.GAP, Theme.GAP, Theme.GAP, Theme.GAP)));
            number.setFont(number.getFont().deriveFont(11f)); number.setForeground(Theme.MUTED);
            text.setName("queue.fullText"); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setBorder(null); text.setOpaque(false);
            card.add(number, BorderLayout.NORTH); card.add(text);
        }
        @Override public Component getListCellRendererComponent(JList<? extends Queued> list, Queued value, int index, boolean selected, boolean focus) {
            if (!queueExpanded) {
                var label = (JLabel) compact.getListCellRendererComponent(list, value, index, selected, focus);
                label.setBorder(new EmptyBorder(Theme.UNIT, Theme.GAP, Theme.UNIT, Theme.GAP));
                // A newline is itself a reason to mark this as a preview, even if the first line fits.
                String[] lines = value.text.strip().split("\\R", 2);
                label.setText((value.urgent ? "! " : (index + 1) + ". ") + lines[0] + (lines.length > 1 ? " …" : ""));
                label.setToolTipText("Раскрыть очередь: кнопка «Раскрыть», двойной клик или Enter. Ctrl+C — скопировать сообщение целиком.");
                compactRow.removeAll(); compactRow.setBackground(label.getBackground()); compactRow.add(label);
                count.setText(value.partsCount > 1 ? "[" + value.partsCount + "]" : "");
                count.setForeground(label.getForeground()); count.setBorder(new EmptyBorder(0, Theme.GAP, 0, Theme.GAP));
                compactRow.add(count, BorderLayout.EAST); compactRow.setToolTipText(label.getToolTipText()); return compactRow;
            }
            card.setBackground(selected ? Theme.SELECTED : Theme.SURFACE); number.setText("Сообщение " + (index + 1) + (value.partsCount > 1 ? "   [" + value.partsCount + "]" : ""));
            text.setText(value.text); text.setFont(list.getFont()); text.setForeground(Theme.TEXT);
            int width = Math.max(1, list.getFixedCellWidth());
            var insets = card.getInsets(); text.setSize(Math.max(1, width - insets.left - insets.right), Short.MAX_VALUE);
            card.setPreferredSize(new Dimension(width, insets.top + insets.bottom + number.getPreferredSize().height + Theme.UNIT + text.getPreferredSize().height));
            card.setToolTipText("Ctrl+C — скопировать выбранное сообщение целиком. Enter — свернуть очередь.");
            return card;
        }
    }
    void update(JsonNode session, JsonNode conversation) {
        String id=session.path("id").asText();
        if(!java.util.Objects.equals(id,sessionId)){sessionId=id;pendingFocus=null;queueSignature="";}
        boolean busy = session.path("running").asBoolean(); paused = session.path("queuePaused").asBoolean();
        boolean held = !busy && session.path("hold").isObject();
        activity.setVisible(busy || held); spinner.setIndeterminate(busy); spinner.setVisible(busy);
        if (held) {
            String holder = session.path("hold").path("title").asText();
            phase.setText("Очередь ждёт"); phase.setToolTipText(phase.getText());
            String text = "Ждёт срочной правки в сессии «" + holder + "». Ваше «Сейчас» в этой сессии стартует без ожидания.";
            detail.setText(text); detail.setToolTipText(text);
        }
        if (busy) {
            var progress = session.path("progress"); long now = System.currentTimeMillis();
            long elapsed = Math.max(0, (now - progress.path("startedAt").asLong(now)) / 1000);
            long silence = Math.max(0, (now - progress.path("updatedAt").asLong(now)) / 1000);
            phase.setText(session.path("phase").asText("Работает") + " · " + duration(elapsed) + " · действий: " + progress.path("completedActions").asInt());
            phase.setToolTipText(phase.getText());
            String text = progress.path("detail").asText("Подключаем исполнителя");
            if (silence >= 20) text = "Без новых событий " + duration(silence) + " · " + text;
            detail.setText(text); detail.setToolTipText(text);
            var lines = new StringBuilder(); for (var item : progress.path("recent")) { if (!lines.isEmpty()) lines.append('\n'); lines.append(item.asText()); }
            String updated = lines.isEmpty() ? "Завершённых действий пока нет" : lines.toString();
            if (!recent.getText().equals(updated)) { recent.setText(updated); recent.setRows(Math.min(4, Math.max(1, progress.path("recent").size()))); }
        }
        String signature = conversation.path("queued").toString();
        if (!signature.equals(queueSignature)) {
            queueSignature = signature; var previous = queueList.getSelectedValuesList().stream().map(Queued::id).toList(); int previousIndex = queueList.getSelectedIndex(); queueModel.clear();
            for (var job : conversation.path("queued")) queueModel.addElement(new Queued(job.path("id").asText(), job.path("prompt").asText(), job.path("partsCount").asInt(1),job.path("urgent").asBoolean()));
            for (int i = 0; i < queueModel.size(); i++) if (previous.contains(queueModel.get(i).id)) queueList.addSelectionInterval(i,i);
            if (queueList.getSelectedIndex() < 0 && !queueModel.isEmpty()) queueList.setSelectedIndex(Math.max(0, Math.min(previousIndex, queueModel.size() - 1)));
            if (previousIndex != queueList.getSelectedIndex()) SwingUtilities.invokeLater(() -> queueList.ensureIndexIsVisible(queueList.getSelectedIndex()));
        }
        queueTitle.setText((paused ? "Пауза · " : session.path("quotaWait").path("waiting").asBoolean()?"Ждём квоту · ":"Очередь · ") + queueModel.size());
        queueTitle.setToolTipText(paused ? "Следующие сообщения ждут. Снять паузу можно и во время текущего ответа." : "Сообщения выполняются по порядку");
        resume.setVisible(true); resume.setEnabled(session.path("active").asBoolean());
        resume.setText(paused ? "Продолжить" : "Пауза"); resume.setIcon(Theme.icon(paused ? "play" : "queue-pause"));
        resume.setToolTipText(paused ? "Снять паузу, в том числе при пустой очереди" : "Не запускать следующие сообщения; текущий ответ продолжится");
        queueActions.setVisible(queueExpanded && !queueModel.isEmpty()); updateQueueActions();
        queueToggle.setVisible(!queueModel.isEmpty()); queueScroll.setVisible(!queueModel.isEmpty()); sizeQueue();
        queue.setVisible(session.path("active").asBoolean() || paused || !queueModel.isEmpty()); setVisible(session.path("active").asBoolean() || busy || paused || !queueModel.isEmpty()); revalidate(); applyPendingFocus();
    }
    private static String duration(long seconds) { return seconds >= 60 ? seconds / 60 + "м " + seconds % 60 + "с" : seconds + "с"; }
}

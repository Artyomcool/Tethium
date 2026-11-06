package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** "История и сброс": executor context, chat history, Claude memory and the session's own state, each with a confirmation. */
final class SessionResetDialog {
    private final Component owner; private final HostClient host; private final String id;
    private final DefaultListModel<JsonNode> history = new DefaultListModel<>(), memory = new DefaultListModel<>();
    private final JLabel memoryPath = new JLabel(), historyInfo = new JLabel();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private SessionResetDialog(Component owner, HostClient host, String id) { this.owner = owner; this.host = host; this.id = id; }

    static void show(Component owner, HostClient host, String id, String title, boolean running) {
        JOptionPane.showOptionDialog(owner, build(owner, host, id, running), "История и сброс · " + title, JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new String[]{"Закрыть"}, "Закрыть");
    }
    static JTabbedPane build(Component owner, HostClient host, String id, boolean running) {
        var dialog = new SessionResetDialog(owner, host, id);
        var tabs = new JTabbedPane(); tabs.setName("reset.tabs");
        tabs.addTab("Контекст", dialog.contextTab(running)); tabs.addTab("История", dialog.historyTab(running));
        tabs.addTab("Память Claude", dialog.memoryTab()); tabs.addTab("Состояние", dialog.stateTab());
        tabs.setPreferredSize(new Dimension(640, 420));
        dialog.reloadHistory(); dialog.reloadMemory(); return tabs;
    }
    private JComponent contextTab(boolean running) {
        var panel = new JPanel(new BorderLayout(0, Theme.GAP)); panel.setBorder(BorderFactory.createEmptyBorder(Theme.INSET, Theme.INSET, Theme.INSET, Theme.INSET));
        panel.add(text("Исполнитель (Codex или Claude) ведёт собственный разговор и помнит всё, что было в нём, независимо от чата Tethium.\n\n"
                + "Сброс контекста отвязывает этот разговор: следующий запуск начнётся с чистого листа, увидит только новое сообщение и файлы проекта. "
                + "История чата в Tethium остаётся, в неё добавится отметка о сбросе." + (running ? "\n\nСессия сейчас выполняется: сначала остановите её." : "")), BorderLayout.CENTER);
        var reset = new JButton("Сбросить контекст исполнителя"); reset.setEnabled(!running);
        reset.addActionListener(e -> confirmAndRun("Начать следующий запуск с чистого контекста исполнителя?", () -> host.post("/sessions/" + id + "/reset-context", Json.object()), "Контекст исполнителя сброшен."));
        panel.add(buttons(reset), BorderLayout.SOUTH); return panel;
    }
    private JComponent historyTab(boolean running) {
        var panel = new JPanel(new BorderLayout(0, Theme.GAP)); panel.setBorder(BorderFactory.createEmptyBorder(Theme.INSET, Theme.INSET, Theme.INSET, Theme.INSET));
        var list = new JList<>(history); list.setName("reset.history"); list.setCellRenderer(entryRenderer());
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        historyInfo.setForeground(Theme.MUTED); historyInfo.setFont(historyInfo.getFont().deriveFont(11f));
        var also = new JCheckBox("Заодно сбросить контекст исполнителя", true);
        var top = new JPanel(new BorderLayout()); top.setOpaque(false); top.add(historyInfo, BorderLayout.WEST); top.add(also, BorderLayout.EAST); panel.add(top, BorderLayout.NORTH);
        JButton selected = new JButton("Удалить выбранные"), after = new JButton("Удалить всё после выбранного");
        selected.setEnabled(!running); after.setEnabled(!running);
        selected.addActionListener(e -> {
            var picked = list.getSelectedValuesList(); if (picked.isEmpty()) { JOptionPane.showMessageDialog(owner, "Выберите сообщения в списке.", "История", JOptionPane.INFORMATION_MESSAGE); return; }
            var sequences = new ArrayList<Long>(); for (var entry : picked) sequences.add(entry.path("sequence").asLong());
            confirmAndRun("Удалить " + sequences.size() + " сообщений из истории Tethium без возможности восстановления?",
                    () -> host.post("/sessions/" + id + "/history-delete", Json.object("sequences", sequences, "resetContext", also.isSelected())), "Сообщения удалены.");
        });
        after.addActionListener(e -> {
            var pick = list.getSelectedValue(); if (pick == null) { JOptionPane.showMessageDialog(owner, "Выберите сообщение, после которого удалить остальное.", "История", JOptionPane.INFORMATION_MESSAGE); return; }
            long sequence = pick.path("sequence").asLong(); int index = list.getSelectedIndex(); int count = history.size() - index - 1;
            if (count <= 0) { JOptionPane.showMessageDialog(owner, "После выбранного сообщения ничего нет.", "История", JOptionPane.INFORMATION_MESSAGE); return; }
            confirmAndRun("Удалить " + count + " сообщений после выбранного без возможности восстановления?",
                    () -> host.post("/sessions/" + id + "/history-delete", Json.object("after", sequence, "resetContext", also.isSelected())), "Сообщения удалены.");
        });
        panel.add(buttons(selected, after), BorderLayout.SOUTH); return panel;
    }
    private JComponent memoryTab() {
        var panel = new JPanel(new BorderLayout(0, Theme.GAP)); panel.setBorder(BorderFactory.createEmptyBorder(Theme.INSET, Theme.INSET, Theme.INSET, Theme.INSET));
        var list = new JList<>(memory); list.setName("reset.memory"); list.setCellRenderer(memoryRenderer());
        memoryPath.setForeground(Theme.MUTED); memoryPath.setFont(memoryPath.getFont().deriveFont(11f));
        panel.add(memoryPath, BorderLayout.NORTH); panel.add(new JScrollPane(list), BorderLayout.CENTER);
        var remove = new JButton("Удалить выбранные файлы памяти");
        remove.addActionListener(e -> {
            var picked = list.getSelectedValuesList(); if (picked.isEmpty()) { JOptionPane.showMessageDialog(owner, "Выберите файлы памяти.", "Память Claude", JOptionPane.INFORMATION_MESSAGE); return; }
            var names = new ArrayList<String>(); for (var entry : picked) names.add(entry.path("name").asText());
            confirmAndRun("Убрать " + names.size() + " файлов из памяти Claude этого проекта? Файлы переедут в корзину Tethium, строки индекса будут удалены.",
                    () -> host.post("/sessions/" + id + "/memory", Json.object("action", "delete", "names", names)), "Файлы памяти перемещены в корзину.");
        });
        panel.add(buttons(remove), BorderLayout.SOUTH); return panel;
    }
    private JComponent stateTab() {
        var panel = new JPanel(new BorderLayout(0, Theme.GAP)); panel.setBorder(BorderFactory.createEmptyBorder(Theme.INSET, Theme.INSET, Theme.INSET, Theme.INSET));
        panel.add(text("Сброс состояния одним действием:\n\n• выключает автономную работу и периодическую задачу;\n• снимает паузу очереди;\n• снимает отметку «требует внимания»;\n• очищает черновик и приложенные к нему изображения.\n\nОчередь сообщений, история и контекст исполнителя не затрагиваются."), BorderLayout.CENTER);
        var reset = new JButton("Сбросить состояние сессии");
        reset.addActionListener(e -> confirmAndRun("Сбросить автономию, расписание, паузу, отметку и черновик этой сессии?", () -> host.post("/sessions/" + id + "/reset-state", Json.object()), "Состояние сессии сброшено."));
        panel.add(buttons(reset), BorderLayout.SOUTH); return panel;
    }
    private void reloadHistory() {
        history.clear();
        try { var index = host.get("/history/index?session=" + id); for (var entry : index.path("entries")) if (entry.path("offset").asInt() == 0) history.addElement(entry); historyInfo.setText("Сообщений: " + history.size()); }
        catch (Exception e) { historyInfo.setText("История недоступна: " + e.getMessage()); }
    }
    private void reloadMemory() {
        memory.clear();
        try { var state = host.post("/sessions/" + id + "/memory", Json.object("action", "list")); for (var file : state.path("files")) memory.addElement(file);
            memoryPath.setText(state.path("exists").asBoolean() ? state.path("directory").asText() + " · файлов: " + memory.size() : "Памяти Claude для этого проекта пока нет: " + state.path("directory").asText()); }
        catch (Exception e) { memoryPath.setText("Память недоступна: " + e.getMessage()); }
    }
    private void confirmAndRun(String question, Action action, String done) {
        if (JOptionPane.showConfirmDialog(owner, question, "Подтверждение", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        try { action.run(); reloadHistory(); reloadMemory(); JOptionPane.showMessageDialog(owner, done, "Готово", JOptionPane.INFORMATION_MESSAGE); }
        catch (Exception e) { JOptionPane.showMessageDialog(owner, e.getMessage(), "Не выполнено", JOptionPane.ERROR_MESSAGE); }
    }
    private interface Action { void run() throws Exception; }
    private static JComponent text(String value) { var area = new JTextArea(value); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setOpaque(false); area.setFocusable(false); return area; }
    private static JComponent buttons(JButton... items) { var row = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0)); row.setOpaque(false); for (var b : items) row.add(b); return row; }
    private static ListCellRenderer<JsonNode> entryRenderer() {
        return (list, entry, index, selected, focus) -> {
            long at = entry.path("timestamp").asLong(); String role = entry.path("role").asText(), who = role.equals("user") ? "Вы" : role.equals("assistant") ? entry.path("provider").asText("агент") : role;
            String title = entry.path("title").asText().replaceAll("\\s+", " ").strip(); if (title.length() > 110) title = title.substring(0, 107) + "…";
            if (title.isEmpty()) title = String.format("%,d символов · %d строк", entry.path("length").asLong(), Math.max(1, entry.path("lines").asLong() + 1));
            var label = new JLabel("#" + entry.path("sequence").asLong() + " · " + (at > 0 ? TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())) + " · " : "") + who + " · " + title);
            label.setOpaque(true); label.setBackground(selected ? Theme.SELECTED : list.getBackground()); label.setForeground(role.equals("user") ? Theme.ACCENT : Theme.TEXT); label.setBorder(BorderFactory.createEmptyBorder(2, Theme.GAP, 2, Theme.GAP)); return label;
        };
    }
    private static ListCellRenderer<JsonNode> memoryRenderer() {
        return (list, file, index, selected, focus) -> {
            String description = file.path("description").asText(); String line = file.path("name").asText() + (file.path("index").asBoolean() ? " · индекс" : "") + (description.isEmpty() ? "" : " · " + description);
            var label = new JLabel("<html>" + line.replace("<", "&lt;") + "<br><font color='#a3a7b3'>" + file.path("type").asText("") + " · " + TIME.format(Instant.ofEpochMilli(file.path("modified").asLong()).atZone(ZoneId.systemDefault())) + " · " + file.path("size").asLong() + " Б</font></html>");
            label.setOpaque(true); label.setBackground(selected ? Theme.SELECTED : list.getBackground()); label.setForeground(Theme.TEXT); label.setBorder(BorderFactory.createEmptyBorder(3, Theme.GAP, 3, Theme.GAP)); return label;
        };
    }
    static List<Long> sequences(List<JsonNode> entries) { var out = new ArrayList<Long>(); for (var e : entries) out.add(e.path("sequence").asLong()); return out; }
}

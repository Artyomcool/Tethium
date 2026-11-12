package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.host.HostClient;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * What the agents have written in a project, read-only: uncommitted changes file by file and recent commits, each with a
 * coloured diff. git runs on the laptop, so it works from Remote too.
 */
final class GitDialog extends JDialog {
    private static final int SHOWN = 400_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());
    private final HostClient host;
    private final String project;
    private final JLabel summary = new JLabel(" ");
    private final DefaultListModel<JsonNode> files = new DefaultListModel<>(), commits = new DefaultListModel<>();
    private final JList<JsonNode> fileList = new JList<>(files), commitList = new JList<>(commits);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextPane diff = new JTextPane();
    private long request;

    static void open(Window owner, HostClient host, String project, String title) {
        var dialog = new GitDialog(owner, host, project, title); dialog.setVisible(true); dialog.refresh();
    }

    private GitDialog(Window owner, HostClient host, String project, String title) {
        super(owner, "Git · " + title, ModalityType.MODELESS);
        this.host = host; this.project = project;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        diff.setEditable(false); diff.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13)); diff.setBackground(Theme.BACKGROUND); diff.setBorder(new EmptyBorder(8, 10, 8, 10));
        fileList.setCellRenderer(new FileRenderer()); commitList.setCellRenderer(new CommitRenderer());
        fileList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && fileList.getSelectedValue() != null) showFile(fileList.getSelectedValue()); });
        commitList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && commitList.getSelectedValue() != null) showCommit(commitList.getSelectedValue()); });
        tabs.addTab("Изменения", new JScrollPane(fileList)); tabs.addTab("Коммиты", new JScrollPane(commitList));
        var refresh = Theme.button("Обновить", "Перечитать состояние репозитория", this::refresh);
        var top = new JPanel(new BorderLayout(Theme.GAP, 0)); top.setBorder(new EmptyBorder(8, 12, 8, 12)); summary.setForeground(Theme.MUTED);
        top.add(summary); top.add(refresh, BorderLayout.EAST);
        var diffScroll = new JScrollPane(diff); diffScroll.getVerticalScrollBar().setUnitIncrement(24);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, diffScroll); split.setDividerLocation(340); split.setBorder(null);
        add(top, BorderLayout.NORTH); add(split);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        var screen = owner == null ? new Rectangle(0, 0, 1400, 900) : owner.getGraphicsConfiguration().getBounds();
        setSize(Math.min(1300, screen.width - 80), Math.min(850, screen.height - 80)); setLocationRelativeTo(owner);
    }

    private void refresh() {
        summary.setText("Читаем репозиторий…");
        load("/git?project=" + project + "&op=status", state -> {
            if (!state.path("repository").asBoolean()) { summary.setText("Папка проекта не является git-репозиторием"); return; }
            Object selectedFile = fileList.getSelectedValue() == null ? null : fileList.getSelectedValue().path("path").asText();
            files.clear(); state.path("files").forEach(files::addElement);
            commits.clear(); state.path("commits").forEach(commits::addElement);
            tabs.setTitleAt(0, "Изменения · " + files.size());
            var parts = new ArrayList<String>(); parts.add("Ветка " + state.path("branch").asText("?"));
            if (!state.path("upstream").asText().isEmpty()) parts.add(state.path("upstream").asText());
            parts.add(files.isEmpty() ? "без незакоммиченных изменений" : state.path("stat").asText(files.size() + " файлов изменено"));
            summary.setText(String.join(" · ", parts));
            int index = -1; for (int i = 0; i < files.size(); i++) if (files.get(i).path("path").asText().equals(selectedFile)) index = i;
            if (index >= 0) fileList.setSelectedIndex(index);
            else if (!files.isEmpty()) { tabs.setSelectedIndex(0); fileList.setSelectedIndex(0); }
            else if (!commits.isEmpty()) { tabs.setSelectedIndex(1); commitList.setSelectedIndex(0); }
            else show("");
        });
    }
    private void showFile(JsonNode file) {
        show("Загружаем дифф " + file.path("path").asText() + "…");
        load("/git?project=" + project + "&op=diff&path=" + URLEncoder.encode(file.path("path").asText(), StandardCharsets.UTF_8), result -> show(result.path("text").asText()));
    }
    private void showCommit(JsonNode commit) {
        show("Загружаем коммит " + commit.path("short").asText() + "…");
        load("/git?project=" + project + "&op=show&commit=" + commit.path("hash").asText(), result -> show(result.path("text").asText()));
    }
    private void load(String path, java.util.function.Consumer<JsonNode> done) {
        long serial = ++request;
        new SwingWorker<JsonNode, Void>() {
            protected JsonNode doInBackground() throws Exception { return host.get(path); }
            protected void done() {
                if (serial != request || !isDisplayable()) return;
                try { done.accept(get()); }
                catch (Exception error) { var cause = error.getCause() == null ? error : error.getCause(); show("Ошибка: " + cause.getMessage()); summary.setText("Ошибка git"); }
            }
        }.execute();
    }

    /** Diff lines coloured by kind; very long output is cut so the window stays responsive. */
    private void show(String text) {
        var document = new DefaultStyledDocument();
        Style plain = style(document, Theme.TEXT, false), added = style(document, Theme.GREEN, false), removed = style(document, Theme.RED, false),
            hunk = style(document, Theme.ACCENT, false), header = style(document, Theme.MUTED, true);
        String shown = text.length() > SHOWN ? text.substring(0, SHOWN) + "\n… показано " + SHOWN / 1000 + " КБ из " + text.length() / 1000 : text;
        try {
            for (String line : shown.split("\n", -1)) {
                AttributeSet look = line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("commit ") || line.startsWith("new file") || line.startsWith("deleted file") ? header
                    : line.startsWith("@@") ? hunk : line.startsWith("+") ? added : line.startsWith("-") ? removed : plain;
                document.insertString(document.getLength(), line + "\n", look);
            }
        } catch (BadLocationException ignored) {}
        diff.setDocument(document); diff.setCaretPosition(0);
    }
    private static Style style(StyledDocument document, Color color, boolean bold) {
        var style = document.addStyle(null, null); StyleConstants.setForeground(style, color); StyleConstants.setBold(style, bold); return style;
    }

    /** Status, file name and folder as separate labels: the name first, since long paths are cut at the right. */
    private static final class FileRenderer extends JPanel implements ListCellRenderer<JsonNode> {
        private final JLabel mark = new JLabel(), name = new JLabel(), folder = new JLabel();
        FileRenderer() {
            super(new FlowLayout(FlowLayout.LEFT, 6, 0)); setBorder(new EmptyBorder(3, 4, 3, 4));
            name.setFont(name.getFont().deriveFont(Font.BOLD)); folder.setForeground(Theme.MUTED); add(mark); add(name); add(folder);
        }
        @Override public Component getListCellRendererComponent(JList<? extends JsonNode> list, JsonNode file, int index, boolean selected, boolean focus) {
            String kind = file.path("kind").asText(), path = file.path("path").asText(); int slash = path.lastIndexOf('/');
            mark.setText(switch (kind) { case "untracked" -> "новый"; case "added" -> "добавлен"; case "deleted" -> "удалён"; case "renamed" -> "переименован"; default -> "изменён"; });
            mark.setForeground(switch (kind) { case "untracked", "added" -> Theme.GREEN; case "deleted" -> Theme.RED; case "renamed" -> Theme.AMBER; default -> Theme.MUTED; });
            name.setText(slash < 0 ? path : path.substring(slash + 1)); folder.setText(slash < 0 ? "" : path.substring(0, slash));
            name.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setToolTipText(file.path("from").asText().isEmpty() ? path : file.path("from").asText() + " → " + path);
            return this;
        }
    }
    private static final class CommitRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            var commit = (JsonNode) value;
            setText("<html><b>" + Theme.escape(commit.path("subject").asText()) + "</b><br><span style='color:#9aa0aa'>" + commit.path("short").asText() + " · "
                + Theme.escape(commit.path("author").asText()) + " · " + TIME.format(Instant.ofEpochMilli(commit.path("time").asLong())) + "</span></html>");
            setBorder(new EmptyBorder(4, 8, 4, 8)); return this;
        }
    }
}

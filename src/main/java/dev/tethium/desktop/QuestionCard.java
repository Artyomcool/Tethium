package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Inline form for an agent question: each question shows its options as radio buttons (checkboxes when several may be picked),
 * an "Другое" field for free text, and one "Ответить" button. Digits pick options, Enter in the text field answers.
 */
final class QuestionCard extends JPanel {
    private final List<Entry> entries = new ArrayList<>();
    private final JButton submit;
    private record Entry(String id, List<JToggleButton> options, List<String> labels, JTextField other, boolean multi) {}
    QuestionCard(JsonNode request, Consumer<JsonNode> answer) {
        super(new BorderLayout(0, Theme.GAP));
        setBackground(Theme.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, Theme.AMBER), new EmptyBorder(Theme.UNIT, Theme.GUTTER, Theme.UNIT, Theme.GUTTER)));
        var questions = request.path("params").path("questions");
        var column = new JPanel(); column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS)); column.setOpaque(false);
        int number = 0;
        for (var q : questions) {
            number++;
            String header = q.path("header").asText("").strip();
            var title = new JLabel("<html><body style='width:520px'>" + (questions.size() > 1 ? "<span style='color:" + Theme.hex(Theme.MUTED) + "'>" + number + "/" + questions.size() + " · </span>" : "")
                    + (header.isEmpty() ? "" : "<b>" + Theme.escape(header) + "</b> · ") + Theme.escape(q.path("question").asText()) + "</body></html>");
            title.setAlignmentX(LEFT_ALIGNMENT); column.add(title); column.add(Box.createVerticalStrut(Theme.GAP));
            boolean multi = q.path("multiSelect").asBoolean(false);
            var group = new ButtonGroup(); var buttons = new ArrayList<JToggleButton>(); var labels = new ArrayList<String>();
            int index = 0;
            for (var o : q.path("options")) {
                index++;
                String label = o.path("label").asText(), description = o.path("description").asText("").strip();
                String text = "<html><body style='width:480px'>" + (index <= 9 ? "<span style='color:" + Theme.hex(Theme.MUTED) + "'>" + index + "</span>&nbsp; " : "") + Theme.escape(label)
                        + (description.isEmpty() ? "" : "<br><span style='color:" + Theme.hex(Theme.MUTED) + "'>" + Theme.escape(description) + "</span>") + "</body></html>";
                JToggleButton button = multi ? new JCheckBox(text) : new JRadioButton(text);
                button.setOpaque(false); button.setFocusPainted(false); button.setAlignmentX(LEFT_ALIGNMENT); button.setVerticalTextPosition(SwingConstants.TOP);
                if (!multi) group.add(button);
                buttons.add(button); labels.add(label); column.add(button);
            }
            var other = new JTextField(); other.putClientProperty("JTextField.placeholderText", buttons.isEmpty() ? "Ваш ответ" : "Другое: свой вариант");
            other.setAlignmentX(LEFT_ALIGNMENT); other.setMaximumSize(new Dimension(Integer.MAX_VALUE, other.getPreferredSize().height + 6));
            other.addActionListener(e -> submitIfComplete());
            other.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                private void typed() { if (!other.getText().isBlank() && !multi) group.clearSelection(); }
                public void insertUpdate(javax.swing.event.DocumentEvent e) { typed(); } public void removeUpdate(javax.swing.event.DocumentEvent e) { } public void changedUpdate(javax.swing.event.DocumentEvent e) { typed(); }
            });
            for (var button : buttons) button.addActionListener(e -> { if (button.isSelected() && !multi) other.setText(""); });
            column.add(Box.createVerticalStrut(2)); column.add(other); column.add(Box.createVerticalStrut(Theme.GAP * 2));
            entries.add(new Entry(q.path("id").asText(), buttons, labels, other, multi));
        }
        var scroll = new JScrollPane(column); scroll.setBorder(null); scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        int height = Math.min(360, column.getPreferredSize().height + 4); scroll.setPreferredSize(new Dimension(10, height));
        var head = new JLabel("Вопрос агента"); head.setForeground(Theme.AMBER); head.setFont(head.getFont().deriveFont(Font.BOLD, 11f));
        add(head, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0)); actions.setOpaque(false);
        submit = Theme.button("Ответить", "Отправить ответ агенту · Enter в поле «Другое»", this::submitIfComplete); Theme.primary(submit);
        var hint = new JLabel("Цифры: вариант · Enter: ответить"); hint.setForeground(Theme.MUTED); hint.setFont(hint.getFont().deriveFont(11f));
        actions.add(hint); actions.add(submit); add(actions, BorderLayout.SOUTH);
        this.answer = answer;
        // Digit keys pick an option of the first unanswered question while the card has focus.
        var digits = new KeyAdapter() { @Override public void keyPressed(KeyEvent e) { int d = e.getKeyChar() - '1'; if (d < 0 || d > 8) return; for (var entry : entries) { if (answered(entry)) continue; if (d < entry.options().size()) { var b = entry.options().get(d); b.setSelected(!entry.multi() || !b.isSelected()); b.requestFocusInWindow(); } return; } } };
        for (var entry : entries) { for (var b : entry.options()) b.addKeyListener(digits); entry.other().addKeyListener(new KeyAdapter() { @Override public void keyPressed(KeyEvent e) { if (entry.other().getText().isEmpty()) digits.keyPressed(e); } }); }
    }
    private final Consumer<JsonNode> answer;
    private static boolean answered(Entry entry) { return !entry.other().getText().isBlank() || entry.options().stream().anyMatch(AbstractButton::isSelected); }
    /** Gives keyboard focus to the first option or field so digits and Enter work right away. */
    void focusFirst() { for (var entry : entries) { if (!entry.options().isEmpty()) { entry.options().getFirst().requestFocusInWindow(); return; } entry.other().requestFocusInWindow(); return; } }
    private void submitIfComplete() {
        for (var entry : entries) if (!answered(entry)) { if (!entry.options().isEmpty()) entry.options().getFirst().requestFocusInWindow(); else entry.other().requestFocusInWindow(); Toolkit.getDefaultToolkit().beep(); return; }
        var answers = Json.object();
        for (var entry : entries) {
            var values = new ArrayList<String>();
            for (int i = 0; i < entry.options().size(); i++) if (entry.options().get(i).isSelected()) values.add(entry.labels().get(i));
            if (!entry.other().getText().isBlank()) values.add(entry.other().getText().strip());
            answers.set(entry.id(), Json.object("answers", values));
        }
        submit.setEnabled(false); for (var entry : entries) { entry.options().forEach(b -> b.setEnabled(false)); entry.other().setEnabled(false); }
        answer.accept(Json.object("answers", answers));
    }
}

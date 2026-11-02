package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Account-wide quota: actual server windows, never inferred numbers of remaining requests. */
final class QuotaPanel extends JPanel {
    private final JLabel title = new JLabel("Квота Codex"), notice = new JLabel(), timestamp = new JLabel("—");
    private final JButton refresh;
    private final javax.swing.Timer animation;
    private double angle;
    private final JPanel windows = new JPanel();
    private final JComboBox<Bucket> selector = new JComboBox<>();
    private JsonNode state = Json.object();
    private String signature = "";
    private boolean applying;
    private final Runnable resetAction;
    private record Bucket(String id, String label, JsonNode value) { @Override public String toString() { return label; } }
    private static final DateTimeFormatter RESET = DateTimeFormatter.ofPattern("dd.MM, HH:mm");

    QuotaPanel(Runnable refreshAction) {
        this(refreshAction,()->{},()->{});
    }
    QuotaPanel(Runnable refreshAction,Runnable resetAction,Runnable usageAction) {
        super(new BorderLayout(0, Theme.UNIT)); setName("quota.panel"); setOpaque(false);
        this.resetAction=resetAction;
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), new EmptyBorder(0, Theme.GUTTER, Theme.INSET, Theme.INSET)));
        var header = new JPanel(new BorderLayout(Theme.GAP, 0)); header.setOpaque(false);
        title.setFont(title.getFont().deriveFont(Font.PLAIN, 11f)); title.setForeground(Theme.MUTED); title.setIcon(Theme.icon("quota-gauge")); title.setIconTextGap(Theme.GAP); header.add(title);
        title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));title.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){usageAction.run();}});
        refresh = Theme.iconButton("quota-refresh", "Обновить квоту аккаунта Codex", refreshAction);
        refresh.setName("quota.refresh"); refresh.getAccessibleContext().setAccessibleName("Обновить квоту");
        Icon normal = Theme.icon("quota-refresh");
        refresh.setIcon(new Icon() {
            public int getIconWidth() { return normal.getIconWidth(); } public int getIconHeight() { return normal.getIconHeight(); }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                var g = (Graphics2D) graphics.create(); g.rotate(angle, x + getIconWidth()/2.0, y + getIconHeight()/2.0); normal.paintIcon(c, g, x, y); g.dispose();
            }
        });
        refresh.setDisabledIcon(refresh.getIcon());
        animation = new javax.swing.Timer(60, e -> { angle += Math.PI / 12; refresh.repaint(); });
        timestamp.setName("quota.updatedAt"); timestamp.setForeground(Theme.MUTED); timestamp.setFont(timestamp.getFont().deriveFont(10f));
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.UNIT, 0)); actions.setOpaque(false); actions.add(timestamp); actions.add(refresh); header.add(actions, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        windows.setOpaque(false); windows.setLayout(new BoxLayout(windows, BoxLayout.Y_AXIS)); add(windows);
        notice.setName("quota.notice"); notice.setFont(notice.getFont().deriveFont(11f)); notice.setForeground(Theme.MUTED);
        selector.setName("quota.selector"); selector.setMaximumRowCount(8);
        selector.addActionListener(e -> { if (!applying) render(); });
        update(Json.object("state", "loading", "buckets", java.util.List.of()));
    }
    void update(JsonNode value) {
        state = value;
        boolean stale = stale(); String next = value.toString() + stale;
        if (signature.equals(next)) return; signature = next;
        String previous = selector.getSelectedItem() instanceof Bucket b ? b.id : "codex";
        applying = true; selector.removeAllItems();
        for (var bucket : value.path("buckets")) selector.addItem(new Bucket(bucket.path("id").asText(), bucket.path("label").asText(), bucket));
        for (int i = 0; i < selector.getItemCount(); i++) if (selector.getItemAt(i).id.equals(previous)) { selector.setSelectedIndex(i); break; }
        applying = false;
        render();
    }
    private boolean stale() { return state.path("state").asText().equals("error") || state.path("updatedAt").asLong() > 0 && System.currentTimeMillis() - state.path("updatedAt").asLong() > 6 * 60_000; }
    private void render() {
        windows.removeAll(); boolean refreshing = state.path("refreshing").asBoolean();
        refresh.setEnabled(!refreshing); refresh.setToolTipText(refreshing ? "Обновляем квоту…" : "Обновить квоту аккаунта Codex");
        if (refreshing && isDisplayable()) animation.start(); else { animation.stop(); angle = 0; }
        if (selector.getItemCount() > 1) { selector.setAlignmentX(LEFT_ALIGNMENT); windows.add(selector); windows.add(Box.createVerticalStrut(3)); }
        if (selector.getSelectedItem() instanceof Bucket bucket) {
            title.setText("Квота " + (selector.getItemCount() == 1 ? bucket.label : "Codex"));
            for (var window : bucket.value.path("windows")) {
                int left = window.path("remainingPercent").asInt();
                Color color = stale() ? Theme.MUTED : left <= 5 ? Theme.RED : left <= 20 ? Theme.AMBER : Theme.ACCENT;
                var labels = new JPanel(new BorderLayout()); labels.setOpaque(false); labels.setAlignmentX(LEFT_ALIGNMENT);
                var count=state.path("resetCredits").path("availableCount");
                var period = new JLabel(windowName(window)+(window.path("durationMinutes").asLong()==10080&&count.isNumber()?" ["+count.asInt()+"]":"")); period.setFont(period.getFont().deriveFont(11f)); labels.add(period);
                if(window.path("durationMinutes").asLong()==10080&&count.isNumber())period.setToolTipText("Доступно сбросов: "+count.asInt()+". Сброс — при остатке ≤5%, с подтверждением.");
                if(left<=5&&count.asInt()>0&&!stale()&&state.path("resetSupported").asBoolean()){
                    period.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));period.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){var menu=new JPopupMenu();menu.add(new AbstractAction("Использовать один сброс…"){public void actionPerformed(java.awt.event.ActionEvent event){resetAction.run();}});menu.show(period,period.getWidth(),0);}});
                }
                var amount = new JLabel(left + "%"); amount.setName("quota.remaining"); amount.setForeground(color); amount.setFont(amount.getFont().deriveFont(Font.BOLD, 12f)); labels.add(amount, BorderLayout.EAST); windows.add(labels);
                var bar = new JProgressBar(0, 100); bar.setValue(left); bar.setForeground(color); bar.setBackground(Theme.BORDER); bar.setBorderPainted(false);
                bar.setAlignmentX(LEFT_ALIGNMENT); bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.UNIT)); bar.setPreferredSize(new Dimension(0, Theme.UNIT)); windows.add(Box.createVerticalStrut(Theme.UNIT)); windows.add(bar); windows.add(Box.createVerticalStrut(Theme.UNIT));
                var reset = new JLabel(resetText(window)); reset.setFont(reset.getFont().deriveFont(11f)); reset.setForeground(Theme.MUTED); reset.setAlignmentX(LEFT_ALIGNMENT); windows.add(reset);
            }
            if (bucket.value.path("windows").isEmpty()) {
                var credits = bucket.value.path("credits");
                windows.add(new JLabel(credits.path("unlimited").asBoolean() ? "Кредиты: без лимита" : credits.hasNonNull("balance") ? "Кредиты: " + credits.path("balance").asText() : "Процент квоты недоступен"));
            }
            notice.setText(stale() ? "Устаревшие данные" : "");
            notice.setForeground(stale() ? Theme.AMBER : Theme.MUTED);
        } else {
            title.setText("Квота Codex");
            notice.setText(state.path("state").asText().equals("loading") ? "Получаем квоту…" : "Квота недоступна");
            notice.setForeground(Theme.MUTED);
            windows.add(notice);
        }
        long updatedAt = state.path("updatedAt").asLong();
        timestamp.setText(updatedAt > 0 ? DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault())) : "—");
        timestamp.setForeground(stale() ? Theme.AMBER : Theme.MUTED);
        String tooltip = "Нажмите заголовок: токены проектов и сессий, график и прошлые периоды. Автоматическое обновление квоты раз в 5 минут.";
        if (updatedAt > 0) tooltip += " Получено " + RESET.format(Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault())) + ".";
        if (!state.path("error").asText().isBlank()) tooltip += " " + state.path("error").asText();
        title.setToolTipText(tooltip); timestamp.setToolTipText(tooltip); notice.setToolTipText(tooltip); setToolTipText(tooltip); revalidate(); repaint();
    }
    @Override public void addNotify() { super.addNotify(); if (state.path("refreshing").asBoolean()) animation.start(); }
    @Override public void removeNotify() { animation.stop(); super.removeNotify(); }
    static String windowName(JsonNode window) {
        long minutes = window.path("durationMinutes").asLong();
        if (minutes == 10080) return "Неделя";
        if (minutes >= 1440 && minutes % 1440 == 0) return minutes / 1440 + " д";
        if (minutes >= 60 && minutes % 60 == 0) return minutes / 60 + " ч";
        if (minutes > 0) return minutes + " мин";
        return window.path("kind").asText().equals("secondary") ? "Дополнительный лимит" : "Основной лимит";
    }
    static String resetText(JsonNode window) {
        if (!window.path("resetsAt").isNumber()) return "Время сброса не указано";
        try {
            var reset = Instant.ofEpochSecond(window.path("resetsAt").asLong());
            if (!reset.isAfter(Instant.now())) return "Сброс ожидается · обновите данные";
            return "Сброс " + RESET.format(reset.atZone(ZoneId.systemDefault()));
        } catch (DateTimeException e) { return "Время сброса не указано"; }
    }
}

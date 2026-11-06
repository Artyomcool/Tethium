package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Account-wide quota: every account window as one compact row (name · bar · percent); details live in tooltips. */
final class QuotaPanel extends JPanel {
    private final JLabel title = new JLabel("Квота"), notice = new JLabel(), timestamp = new JLabel("—");
    private final JButton refresh;
    private final javax.swing.Timer animation;
    private double angle;
    private final JPanel windows = new JPanel(new GridBagLayout());
    private final MachineUsagePanel machine=new MachineUsagePanel();
    void machineUsage(JsonNode value){machine.update(value);}
    private JsonNode state = Json.object();
    private String signature = "";
    private final Runnable resetAction;
    private static final DateTimeFormatter RESET = DateTimeFormatter.ofPattern("dd.MM, HH:mm");

    QuotaPanel(Runnable refreshAction) {
        this(refreshAction,()->{},()->{});
    }
    QuotaPanel(Runnable refreshAction,Runnable resetAction,Runnable usageAction) {
        super(new BorderLayout(0, 2)); setName("quota.panel"); setOpaque(false);
        this.resetAction=resetAction;
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), new EmptyBorder(0, Theme.GUTTER, Theme.GAP, Theme.INSET)));
        var header = new JPanel(new BorderLayout(Theme.GAP, 0)); header.setOpaque(false);
        title.setFont(title.getFont().deriveFont(Font.PLAIN, 11f)); title.setForeground(Theme.MUTED); title.setIcon(Theme.icon("quota-gauge")); title.setIconTextGap(Theme.GAP); header.add(title);
        title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));title.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){usageAction.run();}});
        refresh = Theme.iconButton("quota-refresh", "Обновить квоты аккаунтов Codex и Claude", refreshAction);
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
        add(header, BorderLayout.NORTH);add(machine,BorderLayout.SOUTH);
        windows.setOpaque(false); windows.setName("quota.windows"); add(windows);
        notice.setName("quota.notice"); notice.setFont(notice.getFont().deriveFont(11f)); notice.setForeground(Theme.MUTED);
        update(Json.object("state", "loading", "buckets", java.util.List.of()));
    }
    void update(JsonNode value) {
        state = value;
        boolean stale = stale(); String next = value.toString() + stale;
        if (signature.equals(next)) return; signature = next;
        render();
    }
    private boolean stale() { return state.path("state").asText().equals("error") || state.path("updatedAt").asLong() > 0 && System.currentTimeMillis() - state.path("updatedAt").asLong() > 6 * 60_000; }
    private void render() {
        windows.removeAll(); boolean refreshing = state.path("refreshing").asBoolean();
        refresh.setEnabled(!refreshing); refresh.setToolTipText(refreshing ? "Обновляем квоту…" : "Обновить квоты аккаунтов Codex и Claude");
        if (refreshing && isDisplayable()) animation.start(); else { animation.stop(); angle = 0; }
        int row = 0; boolean stale = stale();
        var count = state.path("resetCredits").path("availableCount");
        for (var bucket : state.path("buckets")) {
            String id = bucket.path("id").asText(); boolean codex = id.equals("codex");
            for (var window : bucket.path("windows")) {
                int left = window.path("remainingPercent").asInt();
                boolean week = window.path("durationMinutes").asLong() == 10080, credits = codex && week && count.isNumber();
                Color color = stale ? Theme.MUTED : left <= 5 ? Theme.RED : left <= 20 ? Theme.AMBER : Theme.ACCENT;
                var name = new JLabel(rowName(bucket, window) + (credits ? " [" + count.asInt() + "]" : "")); name.setName("quota.window"); name.setFont(name.getFont().deriveFont(11f));
                var bar = new JProgressBar(0, 100); bar.setValue(left); bar.setForeground(color); bar.setBackground(Theme.BORDER); bar.setBorderPainted(false);
                bar.setPreferredSize(new Dimension(0, Theme.UNIT + 1)); bar.setMinimumSize(new Dimension(0, Theme.UNIT + 1));
                var amount = new JLabel(left + "%"); amount.setName("quota.remaining"); amount.setForeground(color); amount.setFont(amount.getFont().deriveFont(Font.BOLD, 11f)); amount.setHorizontalAlignment(SwingConstants.RIGHT);
                String tooltip = "<html><b>" + bucket.path("label").asText() + " · " + windowName(window).toLowerCase() + "</b><br>Осталось " + left + "% · " + resetText(window)
                        + (credits ? "<br>Доступно сбросов: " + count.asInt() + ". Сброс — при остатке ≤5%, с подтверждением." : "")
                        + (stale ? "<br><font color='#e4bd82'>Устаревшие данные</font>" : "") + "</html>";
                name.setToolTipText(tooltip); bar.setToolTipText(tooltip); amount.setToolTipText(tooltip);
                if (credits && left <= 5 && count.asInt() > 0 && !stale && state.path("resetSupported").asBoolean()) {
                    name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    name.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){var menu=new JPopupMenu();menu.add(new AbstractAction("Использовать один сброс…"){public void actionPerformed(java.awt.event.ActionEvent event){resetAction.run();}});menu.show(name,name.getWidth(),0);}});
                }
                windows.add(name, cell(0, row, 0)); windows.add(bar, cell(1, row, 1)); windows.add(amount, cell(2, row, 0)); row++;
            }
            if (bucket.path("windows").isEmpty()) {
                var credit = bucket.path("credits");
                var line = new JLabel(shortLabel(bucket) + " · " + (credit.path("unlimited").asBoolean() ? "кредиты без лимита" : credit.hasNonNull("balance") ? "кредиты: " + credit.path("balance").asText() : "процент недоступен"));
                line.setName("quota.window"); line.setFont(line.getFont().deriveFont(11f)); line.setForeground(Theme.MUTED);
                var c = cell(0, row, 1); c.gridwidth = 3; windows.add(line, c); row++;
            }
        }
        if (row == 0) {
            notice.setText(state.path("state").asText().equals("loading") ? "Получаем квоту…" : "Квота недоступна");
            notice.setForeground(Theme.MUTED);
            var c = cell(0, 0, 1); c.gridwidth = 3; windows.add(notice, c);
        } else { notice.setText(stale ? "Устаревшие данные" : ""); notice.setForeground(stale ? Theme.AMBER : Theme.MUTED); }
        title.setText("Квота");
        long updatedAt = state.path("updatedAt").asLong();
        timestamp.setText(updatedAt > 0 ? DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault())) : "—");
        timestamp.setForeground(stale ? Theme.AMBER : Theme.MUTED);
        String tooltip = "Нажмите заголовок: токены проектов и сессий, график и прошлые периоды. Автоматическое обновление квоты раз в 5 минут.";
        if (updatedAt > 0) tooltip += " Получено " + RESET.format(Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault())) + ".";
        if (stale) tooltip += " Устаревшие данные.";
        if (!state.path("error").asText().isBlank()) tooltip += " " + state.path("error").asText();
        title.setToolTipText(tooltip); timestamp.setToolTipText(tooltip); notice.setToolTipText(tooltip); setToolTipText(tooltip); revalidate(); repaint();
    }
    private static GridBagConstraints cell(int column, int row, double weight) {
        var c = new GridBagConstraints(); c.gridx = column; c.gridy = row; c.weightx = weight; c.anchor = column == 2 ? GridBagConstraints.EAST : GridBagConstraints.WEST;
        c.fill = weight > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        c.insets = new Insets(row > 0 ? -3 : 0, column == 1 ? Theme.GAP : 0, 0, column == 1 ? Theme.GAP : 0); return c;
    }
    @Override public void addNotify() { super.addNotify(); if (state.path("refreshing").asBoolean()) animation.start(); }
    @Override public void removeNotify() { animation.stop(); super.removeNotify(); }
    /** "Codex", "Claude" or the scoped model without the shared "Claude · " prefix (e.g. "Fable"). */
    static String shortLabel(JsonNode bucket) {
        String label = bucket.path("label").asText(bucket.path("id").asText());
        return label.startsWith("Claude · ") ? label.substring("Claude · ".length()) : label;
    }
    /** One row per window: "Codex 5 ч", "Codex нед", "Claude 5 ч", "Claude нед", "Fable нед". */
    static String rowName(JsonNode bucket, JsonNode window) {
        long minutes = window.path("durationMinutes").asLong();
        String span = minutes == 10080 ? "нед" : minutes > 0 ? windowName(window) : window.path("kind").asText().equals("secondary") ? "доп" : "осн";
        return shortLabel(bucket) + " " + span;
    }
    static String windowName(JsonNode window) {
        long minutes = window.path("durationMinutes").asLong();
        if (minutes == 10080) return "Неделя";
        if (minutes >= 1440 && minutes % 1440 == 0) return minutes / 1440 + " д";
        if (minutes >= 60 && minutes % 60 == 0) return minutes / 60 + " ч";
        if (minutes > 0) return minutes + " мин";
        return window.path("kind").asText().equals("secondary") ? "Дополнительный лимит" : "Основной лимит";
    }
    static String resetText(JsonNode window) {
        if (!window.path("resetsAt").isNumber()) return "время сброса не указано";
        try {
            var reset = Instant.ofEpochSecond(window.path("resetsAt").asLong());
            if (!reset.isAfter(Instant.now())) return "сброс ожидается, обновите данные";
            return "сброс " + RESET.format(reset.atZone(ZoneId.systemDefault()));
        } catch (DateTimeException e) { return "время сброса не указано"; }
    }
}

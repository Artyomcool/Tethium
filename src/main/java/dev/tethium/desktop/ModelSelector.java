package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import java.awt.*;
import java.util.*;

/** Two keyboard-accessible pickers populated from Codex, never a hard-coded model list. */
public final class ModelSelector extends JPanel {
    private record Choice(String id, String label, String description) { public String toString() { return label; } }
    private final JComboBox<Choice> model = new JComboBox<>(), effort = new JComboBox<>();
    private JsonNode catalog = Json.object();
    private String modelId, effortId;
    private boolean changing, sessionEnabled;
    private final Runnable changed;

    public ModelSelector(Runnable changed) {
        super(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 0)); this.changed = changed; setOpaque(false);
        model.setName("session.model"); effort.setName("session.effort");
        model.getAccessibleContext().setAccessibleName("Модель Codex"); effort.getAccessibleContext().setAccessibleName("Усилие рассуждения");
        model.setPreferredSize(new Dimension(182, 30)); effort.setPreferredSize(new Dimension(166, 30));
        for (var combo : java.util.List.of(model, effort)) {
            combo.setMaximumRowCount(12); combo.setEditable(false);
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                    var label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                    if (value instanceof Choice choice) label.setToolTipText(choice.description);
                    return label;
                }
            }); add(combo);
        }
        rebuild();
        model.addActionListener(e -> {
            if (changing) return; modelId = selected(model);
            var definition = definition();
            if (effortId != null && !supports(definition, effortId)) effortId = null;
            rebuildEffort(); changed.run();
        });
        effort.addActionListener(e -> { if (!changing) { effortId = selected(effort); updateTooltips(); changed.run(); } });
    }
    public String model() { return modelId; }
    public String effort() { return effortId; }
    public void selection(String model, String effort) {
        modelId = normalize(model); effortId = normalize(effort); rebuild();
    }
    public void catalog(JsonNode value) { catalog = value; rebuild(); }
    public void loading() { catalog = Json.object("error", "Загружаем модели Codex…"); rebuild(); }
    public void sessionEnabled(boolean enabled) { sessionEnabled = enabled; updateEnabled(); }
    private static String normalize(String value) { return value == null || value.isBlank() ? null : value; }
    private static String selected(JComboBox<Choice> combo) { return combo.getSelectedItem() instanceof Choice c ? c.id : null; }
    private void rebuild() {
        changing = true;
        try {
            String defaultId = catalog.path("configuredModel").asText(null), defaultName = defaultId;
            for (var entry : catalog.path("data")) {
                if (defaultId != null && defaultId.equals(entry.path("model").asText()) || defaultId == null && entry.path("isDefault").asBoolean()) defaultName = entry.path("displayName").asText(entry.path("model").asText());
            }
            model.removeAllItems(); model.addItem(new Choice(null, defaultName == null ? "Из настроек Codex" : "Codex: " + defaultName, "Модель из настроек Codex для каталога этой сессии"));
            for (var entry : catalog.path("data")) {
                String id = entry.path("model").asText(entry.path("id").asText());
                if (!id.isBlank()) model.addItem(new Choice(id, entry.path("displayName").asText(id), id + " — " + entry.path("description").asText("Модель Codex")));
            }
            select(model, modelId, modelId, "Сохранённая модель; отсутствует в загруженном каталоге");
        } finally { changing = false; }
        rebuildEffort();
    }
    private JsonNode definition() {
        String resolved = modelId == null ? normalize(catalog.path("configuredModel").asText(null)) : modelId;
        for (var entry : catalog.path("data")) {
            if (resolved != null && resolved.equals(entry.path("model").asText(entry.path("id").asText())) || resolved == null && entry.path("isDefault").asBoolean()) return entry;
        }
        return Json.object();
    }
    private static boolean supports(JsonNode definition, String id) {
        for (var value : definition.path("supportedReasoningEfforts")) if (id.equals(value.path("reasoningEffort").asText())) return true;
        return false;
    }
    private void rebuildEffort() {
        changing = true;
        try {
            String configured = catalog.path("configuredEffort").asText(definition().path("defaultReasoningEffort").asText(null));
            effort.removeAllItems(); effort.addItem(new Choice(null, configured == null ? "Усилие: из Codex" : "Усилие: " + configured + " · авто", "Усилие из настроек Codex; при отсутствии настройки — стандартное для модели"));
            for (var entry : definition().path("supportedReasoningEfforts")) {
                String id = entry.path("reasoningEffort").asText();
                if (!id.isBlank()) effort.addItem(new Choice(id, "Усилие: " + label(id), entry.path("description").asText(id)));
            }
            select(effort, effortId, "Усилие: " + label(effortId), "Сохранённое усилие; отсутствует в загруженном каталоге");
        } finally { changing = false; }
        updateEnabled(); updateTooltips();
    }
    private static void select(JComboBox<Choice> combo, String id, String label, String description) {
        for (int i = 0; i < combo.getItemCount(); i++) if (Objects.equals(combo.getItemAt(i).id, id)) { combo.setSelectedIndex(i); return; }
        var saved = new Choice(id, label, description); combo.addItem(saved); combo.setSelectedItem(saved);
    }
    private void updateEnabled() {
        model.setEnabled(sessionEnabled && catalog.path("available").asBoolean());
        effort.setEnabled(sessionEnabled && catalog.path("available").asBoolean() && !definition().path("supportedReasoningEfforts").isEmpty());
    }
    private void updateTooltips() {
        String unavailable = catalog.path("available").asBoolean() ? null : catalog.path("error").asText("Каталог моделей пока недоступен");
        model.setToolTipText(unavailable != null ? unavailable : "Модель: " + (modelId == null ? catalog.path("configuredModel").asText("из настроек Codex") : modelId) + ". Применяется к следующему запуску.");
        effort.setToolTipText(unavailable != null ? unavailable : "Усилие: " + (effortId == null ? catalog.path("configuredEffort").asText("по умолчанию") : effortId) + ". Применяется к следующему запуску.");
    }
    private static String label(String id) {
        if (id == null) return "из Codex";
        return switch (id) {
            case "none" -> "нет"; case "minimal" -> "минимум"; case "low" -> "низкое";
            case "medium" -> "среднее"; case "high" -> "высокое"; case "xhigh" -> "xhigh";
            case "max" -> "максимум"; case "ultra" -> "ультра"; default -> id;
        };
    }
}

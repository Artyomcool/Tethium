package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import java.awt.*;
import java.util.*;

/** Two keyboard-accessible pickers. The model list merges every executor's catalog, so choosing a model also
 * chooses the executor; nothing is hard-coded beyond the executor names. Effort belongs to the chosen model. */
public final class ModelSelector extends JPanel {
    private record Choice(String provider, String id, String label, String description) { public String toString() { return label; } }
    private static final java.util.List<String> ORDER = java.util.List.of("CODEX", "CLAUDE");
    private final JComboBox<Choice> model = new JComboBox<>(), effort = new JComboBox<>();
    private JsonNode catalog = Json.object();
    private String providerId = "CODEX", modelId, effortId;
    private boolean changing, sessionEnabled;
    private final Runnable changed;

    public ModelSelector(Runnable changed) {
        super(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 0)); this.changed = changed; setOpaque(false);
        model.setName("session.model"); effort.setName("session.effort");
        model.getAccessibleContext().setAccessibleName("Исполнитель и модель"); effort.getAccessibleContext().setAccessibleName("Усилие рассуждения");
        model.setPreferredSize(new Dimension(212, 30)); effort.setPreferredSize(new Dimension(166, 30));
        for (var combo : java.util.List.of(model, effort)) {
            combo.setMaximumRowCount(14); combo.setEditable(false);
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
            if (changing || !(model.getSelectedItem() instanceof Choice choice)) return;
            boolean switched = !choice.provider.equals(providerId);
            providerId = choice.provider; modelId = choice.id;
            if (switched || effortId != null && !supports(definition(), effortId)) effortId = null;
            rebuildEffort(); changed.run();
        });
        effort.addActionListener(e -> { if (!changing) { effortId = selected(effort); updateTooltips(); changed.run(); } });
    }
    public String provider() { return providerId; }
    public String model() { return modelId; }
    public String effort() { return effortId; }
    /** Keeps the current executor; used by callers that only know model and effort. */
    public void selection(String model, String effort) { selection(providerId, model, effort); }
    public void selection(String provider, String model, String effort) {
        providerId = normalize(provider) == null ? "CODEX" : provider.strip().toUpperCase(Locale.ROOT);
        modelId = normalize(model); effortId = normalize(effort); rebuild();
    }
    public void catalog(JsonNode value) { catalog = value; rebuild(); }
    public void loading() { catalog = Json.object("error", "Загружаем модели…"); rebuild(); }
    public void sessionEnabled(boolean enabled) { sessionEnabled = enabled; updateEnabled(); }
    private static String title(String provider) { return provider.equals("CLAUDE") ? "Claude" : provider.equals("CODEX") ? "Codex" : provider; }
    private static String normalize(String value) { return value == null || value.isBlank() || value.strip().equals("null") ? null : value.strip(); }
    private static String selected(JComboBox<Choice> combo) { return combo.getSelectedItem() instanceof Choice c ? c.id : null; }
    /** Executors present in the catalog, in a stable order; a legacy single-executor catalog belongs to the current executor. */
    private java.util.List<String> providers() {
        var present = new ArrayList<String>();
        if (catalog.path("providers").isObject()) { for (String id : ORDER) if (catalog.path("providers").has(id)) present.add(id); catalog.path("providers").fieldNames().forEachRemaining(id -> { if (!present.contains(id)) present.add(id); }); }
        if (present.isEmpty()) present.add(providerId);
        return present;
    }
    private JsonNode info(String provider) {
        var providers = catalog.path("providers");
        return providers.isObject() ? providers.path(provider) : Json.object("available", catalog.path("available"), "error", catalog.path("error"), "configuredModel", catalog.path("configuredModel"), "configuredEffort", catalog.path("configuredEffort"));
    }
    private boolean belongs(JsonNode entry, String provider) {
        String owner = normalize(entry.path("provider").asText(null));
        return owner == null ? provider.equals(providerId) || !catalog.path("providers").isObject() : owner.equals(provider);
    }
    private void rebuild() {
        changing = true;
        try {
            model.removeAllItems();
            for (String provider : providers()) {
                var info = info(provider); String executor = title(provider);
                String defaultId = normalize(info.path("configuredModel").asText(null)), defaultName = defaultId;
                for (var entry : catalog.path("data")) {
                    if (!belongs(entry, provider)) continue;
                    if (defaultId != null && defaultId.equals(entry.path("model").asText()) || defaultId == null && entry.path("isDefault").asBoolean()) defaultName = entry.path("displayName").asText(entry.path("model").asText());
                }
                model.addItem(new Choice(provider, null, defaultName == null ? executor + ": из настроек" : executor + ": " + defaultName, "Модель из настроек " + executor + " для этой сессии"));
                for (var entry : catalog.path("data")) {
                    if (!belongs(entry, provider)) continue;
                    String id = entry.path("model").asText(entry.path("id").asText());
                    if (!id.isBlank()) model.addItem(new Choice(provider, id, executor + " · " + entry.path("displayName").asText(id), id + " — " + entry.path("description").asText("Модель " + executor)));
                }
            }
            select(model, providerId, modelId, title(providerId) + " · " + modelId, "Сохранённая модель; отсутствует в загруженном каталоге");
        } finally { changing = false; }
        rebuildEffort();
    }
    private JsonNode definition() {
        String resolved = modelId == null ? normalize(info(providerId).path("configuredModel").asText(null)) : modelId;
        JsonNode first = null;
        for (var entry : catalog.path("data")) {
            if (!belongs(entry, providerId)) continue;
            if (first == null) first = entry;
            if (resolved != null && resolved.equals(entry.path("model").asText(entry.path("id").asText())) || resolved == null && entry.path("isDefault").asBoolean()) return entry;
        }
        // Claude aliases share one effort scale; a configured full model name still gets the common levels.
        if (modelId == null && providerId.equals("CLAUDE") && first != null) return first;
        return Json.object();
    }
    private static boolean supports(JsonNode definition, String id) {
        for (var value : definition.path("supportedReasoningEfforts")) if (id.equals(value.path("reasoningEffort").asText())) return true;
        return false;
    }
    private void rebuildEffort() {
        changing = true;
        try {
            String executor = title(providerId);
            String configured = normalize(info(providerId).path("configuredEffort").asText(null));
            if (configured == null) configured = normalize(definition().path("defaultReasoningEffort").asText(null));
            effort.removeAllItems(); effort.addItem(new Choice(providerId, null, configured == null ? "Усилие: из " + executor : "Усилие: " + configured + " · авто", "Усилие из настроек " + executor + "; при отсутствии настройки — стандартное для модели"));
            for (var entry : definition().path("supportedReasoningEfforts")) {
                String id = entry.path("reasoningEffort").asText();
                if (!id.isBlank()) effort.addItem(new Choice(providerId, id, "Усилие: " + label(id), entry.path("description").asText(id)));
            }
            select(effort, providerId, effortId, "Усилие: " + label(effortId), "Сохранённое усилие; отсутствует в загруженном каталоге");
        } finally { changing = false; }
        updateEnabled(); updateTooltips();
    }
    private static void select(JComboBox<Choice> combo, String provider, String id, String label, String description) {
        for (int i = 0; i < combo.getItemCount(); i++) { var item = combo.getItemAt(i); if (item.provider.equals(provider) && Objects.equals(item.id, id)) { combo.setSelectedIndex(i); return; } }
        var saved = new Choice(provider, id, label, description); combo.addItem(saved); combo.setSelectedItem(saved);
    }
    private boolean anyAvailable() {
        for (String provider : providers()) if (info(provider).path("available").asBoolean()) return true;
        return false;
    }
    private void updateEnabled() {
        model.setEnabled(sessionEnabled && anyAvailable());
        effort.setEnabled(sessionEnabled && info(providerId).path("available").asBoolean() && !definition().path("supportedReasoningEfforts").isEmpty());
    }
    private void updateTooltips() {
        var problems = new StringJoiner(" ");
        for (String provider : providers()) if (!info(provider).path("available").asBoolean()) problems.add(info(provider).path("error").asText(title(provider) + ": каталог моделей пока недоступен"));
        String executor = title(providerId), unavailable = anyAvailable() ? null : problems.length() > 0 ? problems.toString() : catalog.path("error").asText("Каталог моделей пока недоступен");
        model.setToolTipText(unavailable != null ? unavailable : "Исполнитель: " + executor + ". Модель: " + (modelId == null ? info(providerId).path("configuredModel").asText("из настроек " + executor) : modelId) + ". Применяется к следующему запуску; история сессии передаётся новому исполнителю." + (problems.length() > 0 ? " " + problems : ""));
        effort.setToolTipText(unavailable != null ? unavailable : "Усилие: " + (effortId == null ? info(providerId).path("configuredEffort").asText("по умолчанию") : effortId) + ". Применяется к следующему запуску.");
    }
    private String label(String id) {
        if (id == null) return "из " + title(providerId);
        return switch (id) {
            case "none" -> "нет"; case "minimal" -> "минимум"; case "low" -> "низкое";
            case "medium" -> "среднее"; case "high" -> "высокое"; case "xhigh" -> "xhigh";
            case "max" -> "максимум"; case "ultra" -> "ультра"; default -> id;
        };
    }
}

package dev.tethium.desktop;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelSelectorTest {
    @Test void legacyLiteralNullDisplaysAutoWithoutWritingSettings() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var changes=new AtomicInteger();var selector=new ModelSelector(changes::incrementAndGet);
            selector.selection("null","null","null");selector.catalog(Json.object("available",true,"configuredEffort",null,"data",List.of(Json.object("model","m","isDefault",true,"defaultReasoningEffort","high"))));
            assertEquals("CODEX",selector.provider());assertNull(selector.model());assertNull(selector.effort());assertEquals(0,changes.get());
            assertFalse(((JComboBox<?>)selector.getComponent(1)).getSelectedItem().toString().contains("null"));
        });
    }
    @Test void changingModelsKeepsOnlySupportedEffortAndRefreshNeverErasesSavedChoices() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var changes = new AtomicInteger(); var selector = new ModelSelector(changes::incrementAndGet);
            var catalog = Json.object("available", true, "configuredModel", "deep", "data", List.of(
                    Json.object("model", "deep", "displayName", "Deep", "supportedReasoningEfforts", List.of(Json.object("reasoningEffort", "high"), Json.object("reasoningEffort", "ultra"))),
                    Json.object("model", "fast", "displayName", "Fast", "supportedReasoningEfforts", List.of(Json.object("reasoningEffort", "low")))));
            selector.selection("deep", "ultra"); selector.catalog(catalog); selector.sessionEnabled(true);
            var models = (JComboBox<?>) selector.getComponent(0); var efforts = (JComboBox<?>) selector.getComponent(1);
            assertFalse(models.isEditable()); assertFalse(efforts.isEditable()); assertEquals("ultra", selector.effort());
            models.setSelectedIndex(2); assertEquals("fast", selector.model()); assertNull(selector.effort());
            assertEquals(2, efforts.getItemCount()); efforts.setSelectedIndex(1); assertEquals("low", selector.effort()); assertEquals(2, changes.get());
            selector.selection("custom-saved", "xhigh"); selector.catalog(catalog);
            assertEquals("custom-saved", selector.model()); assertEquals("xhigh", selector.effort()); assertFalse(efforts.isEnabled());
            selector.catalog(Json.object("available", false, "error", "offline"));
            assertEquals("custom-saved", selector.model()); assertEquals("xhigh", selector.effort()); assertFalse(models.isEnabled());
            assertEquals(2, changes.get(), "Programmatic updates must not overwrite saved preferences");
        });
    }
    @Test void mergedCatalogListsBothExecutorsAndChoosingAClaudeModelSwitchesExecutorAndResetsEffort() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var changes = new AtomicInteger(); var selector = new ModelSelector(changes::incrementAndGet); selector.sessionEnabled(true);
            var merged = Json.object("available", true, "provider", "CODEX",
                    "providers", Json.object(
                            "CODEX", Json.object("title", "Codex", "available", true, "configuredModel", "deep", "configuredEffort", "high"),
                            "CLAUDE", Json.object("title", "Claude", "available", true, "configuredModel", null, "configuredEffort", null)),
                    "data", List.of(
                            Json.object("provider", "CODEX", "model", "deep", "displayName", "Deep", "supportedReasoningEfforts", List.of(Json.object("reasoningEffort", "high"))),
                            Json.object("provider", "CLAUDE", "model", "fable", "displayName", "Fable", "supportedReasoningEfforts", List.of(Json.object("reasoningEffort", "low"), Json.object("reasoningEffort", "max")))));
            selector.selection("CODEX", null, "high"); selector.catalog(merged);
            var models = (JComboBox<?>) selector.getComponent(0); var efforts = (JComboBox<?>) selector.getComponent(1);
            assertEquals(List.of("Codex: Deep", "Codex · Deep", "Claude: из настроек", "Claude · Fable"), List.of(models.getItemAt(0).toString(), models.getItemAt(1).toString(), models.getItemAt(2).toString(), models.getItemAt(3).toString()));
            assertEquals(0, models.getSelectedIndex()); assertEquals("high", selector.effort()); assertEquals(0, changes.get());
            models.setSelectedIndex(3);
            assertEquals("CLAUDE", selector.provider()); assertEquals("fable", selector.model()); assertNull(selector.effort(), "Effort belongs to the previous executor"); assertEquals(1, changes.get());
            assertEquals(3, efforts.getItemCount()); efforts.setSelectedIndex(2); assertEquals("max", selector.effort());
            models.setSelectedIndex(2);
            assertEquals("CLAUDE", selector.provider()); assertNull(selector.model()); assertEquals("max", selector.effort(), "Alias efforts stay valid for the Claude default");
            assertTrue(efforts.isEnabled());
            selector.selection("CODEX", "deep", "high"); selector.catalog(merged);
            assertEquals(1, models.getSelectedIndex()); assertEquals("high", selector.effort()); assertEquals(3, changes.get(), "Restoring saved preferences is silent");
            selector.catalog(Json.object("available", false, "provider", "CODEX", "providers", Json.object("CODEX", Json.object("title", "Codex", "available", false, "error", "offline"), "CLAUDE", Json.object("title", "Claude", "available", true)), "data", List.of(Json.object("provider", "CLAUDE", "model", "fable", "displayName", "Fable"))));
            assertTrue(models.isEnabled(), "Claude stays selectable while the Codex catalog is offline"); assertEquals("deep", selector.model()); assertEquals("CODEX", selector.provider());
            assertTrue(models.getToolTipText().contains("offline"));
        });
    }
}

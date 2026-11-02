package dev.tethium.desktop;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelSelectorTest {
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
}

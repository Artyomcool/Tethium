package dev.tethium.desktop;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QuotaPanelTest {
    @Test void refreshDoesNotAddARowOrChangePanelDimensions() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            Theme.install(); var panel=new QuotaPanel(()->{});
            var state=Json.object("state","ready","updatedAt",System.currentTimeMillis(),"refreshing",false,"buckets",List.of(Json.object("id","codex","label","Codex","windows",List.of(Json.object("remainingPercent",54,"durationMinutes",300,"resetsAt",2000000000)))));
            panel.update(state);var before=panel.getPreferredSize();
            state.put("refreshing",true);panel.update(state);assertEquals(before,panel.getPreferredSize());
            state.put("refreshing",false);state.put("state","error");panel.update(state);assertEquals(before,panel.getPreferredSize());
        });
    }
}

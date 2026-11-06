package dev.tethium.desktop;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
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
    @Test void everyAccountWindowIsItsOwnCompactRowWithoutASelector() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            Theme.install(); var panel=new QuotaPanel(()->{});
            var codex=Json.object("id","codex","label","Codex","windows",List.of(
                Json.object("kind","primary","remainingPercent",54,"durationMinutes",300,"resetsAt",2000000000),
                Json.object("kind","secondary","remainingPercent",3,"durationMinutes",10080,"resetsAt",2000000000)));
            var claude=Json.object("id","claude","label","Claude","windows",List.of(
                Json.object("kind","primary","remainingPercent",80,"durationMinutes",300,"resetsAt",2000000000),
                Json.object("kind","secondary","remainingPercent",97,"durationMinutes",10080,"resetsAt",2000000000)));
            var fable=Json.object("id","claude-fable","label","Claude · Fable","windows",List.of(Json.object("kind","secondary","remainingPercent",94,"durationMinutes",10080,"resetsAt",2000000000)));
            var state=Json.object("state","ready","updatedAt",System.currentTimeMillis(),"refreshing",false,"resetSupported",true,
                "resetCredits",Json.object("availableCount",2),"buckets",List.of(codex,claude,fable));
            panel.update(state);
            assertTrue(collect(panel,JComboBox.class).isEmpty(),"no selector");
            var names=collect(panel,JLabel.class).stream().filter(l->"quota.window".equals(l.getName())).map(JLabel::getText).toList();
            assertEquals(List.of("Codex 5 ч","Codex нед [2]","Claude 5 ч","Claude нед","Fable нед"),names);
            var amounts=collect(panel,JLabel.class).stream().filter(l->"quota.remaining".equals(l.getName())).map(JLabel::getText).toList();
            assertEquals(List.of("54%","3%","80%","97%","94%"),amounts);
            assertEquals(5,collect(panel,JProgressBar.class).size());
            var week=collect(panel,JLabel.class).stream().filter(l->"Codex нед [2]".equals(l.getText())).findFirst().orElseThrow();
            assertTrue(week.getToolTipText().contains("Codex · неделя")&&week.getToolTipText().contains("Осталось 3%")&&week.getToolTipText().contains("Доступно сбросов: 2"),week.getToolTipText());
            assertEquals(Cursor.HAND_CURSOR,week.getCursor().getType(),"reset credit menu available at ≤5%");
            panel.setSize(220,400);panel.doLayout();
            var rows=panel.getPreferredSize().height;
            assertTrue(rows<150,"five windows must stay compact, got "+rows);
        });
    }
    private static <T extends Component> List<T> collect(Container root,Class<T> type){
        var out=new ArrayList<T>();
        for(var c:root.getComponents()){if(type.isInstance(c))out.add(type.cast(c));if(c instanceof Container child)out.addAll(collect(child,type));}
        return out;
    }
}

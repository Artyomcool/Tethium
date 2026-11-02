package dev.tethium.desktop;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChatNavigationTest {
    @Test void boundariesOnlyUseRequestsAndKeepStableSourceIds() {
        var tasks = ChatNavigation.tasks(Json.MAPPER.valueToTree(List.of(
                Json.object("sequence", 12, "role", "user", "text", "First\n\nparagraph"),
                Json.object("sequence", 18, "role", "assistant", "text", "Answer"),
                Json.object("sequence", 21, "role", "session", "text", "Message from neighbour"),
                Json.object("sequence", 500, "role", "user", "provider", "IMPORTED", "text", "Second"))));
        assertEquals(2, tasks.size()); assertEquals("task-12", tasks.get(0).anchor()); assertEquals("First paragraph", tasks.get(0).title());
        assertEquals(2, tasks.get(1).number()); assertEquals("task-500", tasks.get(1).anchor());
    }
    @Test void oldTaskStaysVisibleWhileStreamingAndLatestButtonWinsOverPendingRender() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install(); var text = new ChatTranscript(); var scroll = new JScrollPane(text); var nav = new ChatNavigation(text, scroll);
            var tasks = List.of(new ChatNavigation.Task(10, 1, "First task"), new ChatNavigation.Task(300, 2, "Second task"), new ChatNavigation.Task(999, 3, "Third task"));
            render(text, scroll, nav, tasks, "", true, 600);
            var picker = (JComboBox<?>) named(nav, "chat.tasks");
            assertEquals(2, picker.getSelectedIndex()); assertBottom(scroll);
            ((JButton) named(nav, "chat.previousTask")).doClick(); assertEquals(1, picker.getSelectedIndex());
            int readingPosition = scroll.getVerticalScrollBar().getValue(); assertTrue(readingPosition > 200);
            render(text, scroll, nav, tasks, "<p>New streamed text</p>".repeat(50), false, 600);
            assertEquals(readingPosition, scroll.getVerticalScrollBar().getValue(), 2); assertEquals(1, picker.getSelectedIndex());
            var beforeResize = nav.savePosition(); render(text, scroll, nav, tasks, "", false, 380);
            assertEquals(beforeResize.path("task").asLong(), nav.savePosition().path("task").asLong());
            var pending = nav.beforeChange(false); text.setText(page(tasks, "<p>More stream</p>".repeat(60))); layout(text, scroll, 380);
            ((JButton) named(nav, "chat.toEnd")).doClick(); nav.afterChange(pending); assertBottom(scroll); assertEquals(2, picker.getSelectedIndex());
            picker.setSelectedIndex(0); assertEquals(0, picker.getSelectedIndex()); assertTrue(scroll.getVerticalScrollBar().getValue() < 80);
            ((JButton) named(nav, "chat.nextTask")).doClick(); assertEquals(1, picker.getSelectedIndex());
            int secondRequestY = scroll.getVerticalScrollBar().getValue();
            picker.setSelectedIndex(2); int thirdRequestY = scroll.getVerticalScrollBar().getValue();
            // Manual scrollbar movement must update the pinned request without jumping back to an anchor.
            scroll.getVerticalScrollBar().setValue(secondRequestY + 160);
            assertEquals(1, picker.getSelectedIndex()); assertTrue(picker.getToolTipText().contains("Second task"));
            assertEquals(secondRequestY + 160, scroll.getVerticalScrollBar().getValue());
            scroll.getVerticalScrollBar().setValue(thirdRequestY + 160); assertEquals(2, picker.getSelectedIndex());
            var map = nav.requestMap(); map.setSize(18, 420);
            int markerY = 6 + (int) Math.round((double) secondRequestY / scroll.getVerticalScrollBar().getMaximum() * 408);
            var click = new java.awt.event.MouseEvent(map, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, 9, markerY, 1, false, java.awt.event.MouseEvent.BUTTON1);
            assertTrue(map.getToolTipText(click).contains("Second task")); map.dispatchEvent(click);
            assertEquals(1, picker.getSelectedIndex()); assertEquals(secondRequestY, scroll.getVerticalScrollBar().getValue(), 2);
            var saved = nav.savePosition(); nav.toEnd(); nav.restorePosition(saved); assertEquals(300, nav.savePosition().path("task").asLong());
            assertNotNull(((HTMLDocument) text.getDocument()).getElement("task-999"));
            nav.reset(); assertEquals(0, picker.getItemCount()); assertFalse(picker.isEnabled());
        });
    }
    private static void render(ChatTranscript text, JScrollPane scroll, ChatNavigation nav, List<ChatNavigation.Task> tasks, String live, boolean tail, int width) {
        var p = nav.beforeChange(tail); text.setText(page(tasks, live)); nav.setTasks(tasks); layout(text, scroll, width); nav.afterChange(p);
    }
    private static String page(List<ChatNavigation.Task> tasks, String live) {
        var html = new StringBuilder();
        for (var task : tasks) html.append("<div class='user' id='").append(task.anchor()).append("'><p>Request</p></div><div class='message'>").append("<p>Long answer with details and enough text for multiple lines in the narrow viewport. Keep this history visible while the next response streams.</p>".repeat(18)).append("</div>");
        return ChatTheme.page(html + live);
    }
    private static void layout(ChatTranscript text, JScrollPane scroll, int width) { scroll.setSize(width, 420); scroll.doLayout(); scroll.getViewport().doLayout(); text.setSize(scroll.getViewport().getWidth(), text.getPreferredSize().height); scroll.getViewport().doLayout(); }
    private static void assertBottom(JScrollPane pane) { var bar = pane.getVerticalScrollBar(); assertTrue(bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4, "value=" + bar.getValue() + " extent=" + bar.getVisibleAmount() + " max=" + bar.getMaximum() + " view=" + pane.getViewport().getViewSize()); }
    private static Component named(Container root, String name) { for (var c : root.getComponents()) { if (name.equals(c.getName())) return c; if (c instanceof Container child) { var found = named(child, name); if (found != null) return found; } } return null; }
}

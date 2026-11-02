package dev.tethium.probe;

import dev.tethium.agent.*;
import dev.tethium.desktop.*;
import dev.tethium.host.*;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** End-to-end desktop checks with an isolated host and deterministic agent, without model calls. */
public final class UxProbe {
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(".local", "ux", UUID.randomUUID().toString()).toAbsolutePath());
        System.setProperty("tethium.data",root.resolve("data").toString());
        Path workspace = Files.createDirectories(root.resolve("Workspace"));
        Path imagePath = Files.copy(Path.of("design/image-demo/tethium-workspace-v1.png"), workspace.resolve("image.png"));
        Theme.install(); var frame = new WorkspaceFrame[1]; int exit = 0;
        AgentAdapter adapter = new AgentAdapter() {
            public Provider provider() { return Provider.CODEX; }
            public Result run(Request request, Consumer<Event> events, Cancellation cancel) throws Exception {
                require("gpt-6-astra".equals(request.model()) && "high".equals(request.reasoningEffort()), "Picker settings did not reach the adapter");
                events.accept(new Event("conversation", "ux-thread", Json.object()));
                events.accept(new Event("text", "Проверяю просмотр результатов и перенос длинных строк.", Json.object()));
                for (int i = 0; i < 3; i++) {
                    var raw = Json.object("params", Json.object("item", Json.object("id", "cmd" + i, "type", "commandExecution", "command", "gradlew test --tests ChatTranscriptTest", "exitCode", 0)));
                    events.accept(new Event("item/started", "", raw)); events.accept(new Event("item/completed", "", raw));
                }
                if (request.prompt().endsWith("start")) while (!cancel.isCancelled()) Thread.sleep(30);
                return new Result("ux-thread", cancel.isCancelled() ? Status.CANCELLED : Status.COMPLETED, "Готово");
            }
        };
        var modelCatalog = new CodexModelCatalog(directory -> Json.object("available", true, "configuredModel", "gpt-6-astra", "configuredEffort", "high", "data", List.of(
                Json.object("model", "gpt-6-astra", "displayName", "GPT-6-Astra", "supportedReasoningEfforts", List.of(Json.object("reasoningEffort", "low"), Json.object("reasoningEffort", "high"))))));
        try (var host = new HostService(root.resolve("data"), adapter, new CodexQuotaService(null), false, modelCatalog); var client = new HostClient(host.descriptor())) {
            String text = "Сессии работают в общем каталоге, а результаты открываются рядом с разговором.\n\n".repeat(12)
                    + "Длинный путь: C:/git/" + "very-long-directory/".repeat(20) + "File.java\n\n```java\n    String value = \"" + "x".repeat(200) + "\";\n\n    finish();\n```";
            String id = client.post("/imports/conversation", Json.object("projectPath", workspace.toString(), "title", "Проверка интерфейса Tethium", "sourceKey", "ux", "messages", List.of(
                    Json.object("sourceId", "1", "role", "user", "text", "Проверь результаты и очередь"),
                    Json.object("sourceId", "2", "role", "assistant", "text", text)))).path("id").asText();
            client.post("/sessions/" + id + "/artifact", Json.object("path", imagePath.toString(), "title", "Рабочее место Tethium"));
            SwingUtilities.invokeAndWait(() -> {
                frame[0] = new WorkspaceFrame(new HostClient(host.descriptor()), () -> {}, id);
                frame[0].addNotify(); frame[0].validate();
            });
            await(() -> id.equals(frame[0].selectedSession()), "session selected"); Thread.sleep(700);
            await(() -> named(frame[0], "session.model").isEnabled(), "model catalog loaded");
            SwingUtilities.invokeAndWait(()->{
                var transcript=(JEditorPane)read(frame[0],"transcript");
                try{String rendered=transcript.getDocument().getText(0,transcript.getDocument().getLength());int begin=rendered.indexOf("Сессии работают");require(begin>=0,"Quote source missing");transcript.select(begin,begin+"Сессии работают".length());frame[0].inputArea().setText("Мой черновик\n\n");frame[0].inputArea().setCaretPosition(frame[0].inputArea().getText().length());call(frame[0],"quoteSelection",new Class<?>[]{});require(frame[0].inputArea().getText().contains("> Сессии работают"),"Selected text was not quoted");require(frame[0].inputArea().getText().startsWith("Мой черновик"),"Quote replaced the draft");transcript.select(0,0);}catch(Exception failure){throw new RuntimeException(failure);}
            });
            SwingUtilities.invokeAndWait(() -> {
                ((JComboBox<?>) named(frame[0], "session.model")).setSelectedIndex(1);
                ((JComboBox<?>) named(frame[0], "session.effort")).setSelectedIndex(2);
                var input = frame[0].inputArea(); input.setText("Проверка"); input.setCaretPosition(input.getText().length());
                press(input, InputEvent.CTRL_DOWN_MASK);
                require(input.getText().equals("Проверка\n"), "Ctrl+Enter did not insert a newline");
                input.append("start"); press(input, 0);
                require(input.getText().isEmpty(), "Enter did not send the message");
                press(input, 0); // Empty Enter must not create another queued message.
            });
            await(() -> state(client, id).path("sessions").get(0).path("progress").path("completedActions").asInt() == 3, "keyboard submission");
            require(state(client, id).path("conversation").path("queued").isEmpty(), "Empty Enter queued a message");
            SwingUtilities.invokeAndWait(() -> {
                var input = frame[0].inputArea(); input.setText("Проверить HTML"); press(input, 0);
                input.setText("После выхода из полноэкранного просмотра"); press(input, 0);
                input.setText("Сохранить результат проверки"); press(input, InputEvent.ALT_GRAPH_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
                input.setText("В отчёт"); press(input, 0);
            });
            await(() -> {
                var queued = state(client, id).path("conversation").path("queued");
                return queued.size() == 2 && queued.get(1).path("prompt").asText().endsWith("В отчёт");
            }, "append and separate submission");
            var queued = state(client, id).path("conversation").path("queued");
            require(queued.get(0).path("prompt").asText().equals("Проверить HTML\n\n---\n\nПосле выхода из полноэкранного просмотра"), "Enter did not append a part");
            require(queued.get(1).path("prompt").asText().equals("Сохранить результат проверки\n\n---\n\nВ отчёт"), "Alt+Enter did not create a separate queue entry");
            await(() -> state(client, id).path("sessions").get(0).path("progress").path("completedActions").asInt() == 3, "activity");
            SwingUtilities.invokeAndWait(() -> call(frame[0], "openArtifact", new Class<?>[]{Path.class}, imagePath));
            await(() -> image(frame[0]) != null, "image loaded"); Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                require(((JButton) named(frame[0], "composer.send")).getText().equals("Дополнить"), "Append action is not visible");
                checkLayout(frame[0], 1280, 860); capture(frame[0], root.resolve("working-1280.png"));
                checkLayout(frame[0], 920, 600); capture(frame[0], root.resolve("working-920.png"));
                var panel = image(frame[0]);
                panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 70, 70, 1, false, MouseEvent.BUTTON1));
                var full = (JDialog) read(frame[0], "fullScreenPreview");
                require(full != null && full.isVisible(), "Image click did not open fullscreen");
                require(full.getBounds().equals(frame[0].getGraphicsConfiguration().getBounds()), "Fullscreen bounds");
                require(image(frame[0]) == panel, "Image reloaded on fullscreen entry");
                var key = full.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
                full.getRootPane().getActionMap().get(key).actionPerformed(new ActionEvent(full, 0, "escape"));
                require(read(frame[0], "fullScreenPreview") == null, "Escape did not restore preview");
                require(image(frame[0]) == panel, "Image reloaded on return");
                int divider = ((JSplitPane) read(frame[0], "content")).getDividerLocation();
                call(frame[0], "showPreview", new Class<?>[]{boolean.class}, false);
                call(frame[0], "showPreview", new Class<?>[]{boolean.class}, true);
                require(((JSplitPane) read(frame[0], "content")).getDividerLocation() == divider, "Divider not preserved");
                require(image(frame[0]) == panel, "Preview reopening reloaded image");
                call(frame[0], "stop", new Class<?>[]{});
            });
            await(() -> !state(client, id).path("sessions").get(0).path("running").asBoolean(), "stop"); Thread.sleep(700);
            String pid=state(client,id).path("sessions").get(0).path("projectId").asText();
            String other=client.post("/sessions",Json.object("projectId",pid,"title","Preview restoration")).path("id").asText();
            Path manual=Files.copy(imagePath,workspace.resolve("unpublished image.png"));
            SwingUtilities.invokeAndWait(()->call(frame[0],"openArtifact",new Class<?>[]{Path.class},manual));
            await(()->image(frame[0])!=null,"manual preview");Thread.sleep(700);
            SwingUtilities.invokeAndWait(()->call(frame[0],"selectSession",new Class<?>[]{String.class},other));
            await(()->other.equals(frame[0].selectedSession())&&read(frame[0],"restoreViewFor")==null,"other session loaded");
            SwingUtilities.invokeAndWait(()->{require(!Boolean.TRUE.equals(read(frame[0],"previewOpen")),"New session inherited old preview");call(frame[0],"selectSession",new Class<?>[]{String.class},id);});
            await(()->Boolean.TRUE.equals(read(frame[0],"previewOpen"))&&image(frame[0])!=null,"preview restored after session switch");
            SwingUtilities.invokeAndWait(()->{require(((JComboBox<?>)read(frame[0],"artifacts")).getSelectedItem().toString().contains("unpublished"),"Manual file selection lost");call(frame[0],"showPreview",new Class<?>[]{boolean.class},false);call(frame[0],"selectSession",new Class<?>[]{String.class},other);});
            await(()->read(frame[0],"restoreViewFor")==null,"second switch");
            SwingUtilities.invokeAndWait(()->call(frame[0],"selectSession",new Class<?>[]{String.class},id));
            await(()->read(frame[0],"restoreViewFor")==null,"closed preview restored");
            SwingUtilities.invokeAndWait(()->{require(!Boolean.TRUE.equals(read(frame[0],"previewOpen")),"Closed preview reopened");call(frame[0],"showPreview",new Class<?>[]{boolean.class},true);});
            var stopped = state(client, id);
            require(stopped.path("conversation").path("queued").size() == 2, "Stop lost queue");
            String longAddition = "Сохрани абзацы, чтобы было понятно, где заканчивается одна мысль и начинается следующая. ".repeat(8)
                    + "\n\nДлинный путь: C:/project/" + "directory/".repeat(24) + "result.html\n\nПоследний абзац сообщения.";
            client.post("/sessions/" + id + "/run", Json.object("prompt", longAddition));
            String fullQueuedText = "Сохранить результат проверки\n\n---\n\nВ отчёт\n\n---\n\n" + longAddition;
            Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                require(((JList<?>) named(frame[0], "queue.messages")).getModel().getSize() == 2, "Queue not visible");
                var list = (JList<?>) named(frame[0], "queue.messages"); list.setSelectedIndex(1);
                ((JButton) named(frame[0], "queue.expand")).doClick();
                require(list.getFixedCellHeight() == -1, "Expanded queue is still single-line");
                checkLayout(frame[0], 920, 600);
            });
            Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                var list = (JList<?>) named(frame[0], "queue.messages");
                require(list.getSelectedIndex() == 1, "Expansion changed selection");
                require(list.getCellBounds(1, 1).height > 100, "Full message is clipped to a short row");
                require(expandedText(list, 1).getText().equals(fullQueuedText), "Expanded message lost text or paragraphs");
                var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, list);
                require(!scroll.getHorizontalScrollBar().isVisible(), "Expanded queue scrolls horizontally");
                require(scroll.getHeight() <= 204, "Queue took over the window");
                list.ensureIndexIsVisible(1); capture(frame[0], root.resolve("queue-expanded-920.png"));
            });
            client.post("/sessions/" + id + "/run", Json.object("prompt", "Дополнение во время просмотра."));
            Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                var list = (JList<?>) named(frame[0], "queue.messages");
                require(list.getFixedCellHeight() == -1, "Polling collapsed the queue");
                require(expandedText(list, 1).getText().equals(fullQueuedText + "\n\n---\n\nДополнение во время просмотра."), "Expanded queue did not refresh its text");
                ((JButton) named(frame[0], "queue.expand")).doClick();
                require(list.getFixedCellHeight() == 28, "Queue did not collapse");
                require(list.getSelectedIndex() == 1, "Collapse changed selection");
                checkLayout(frame[0], 1280, 860); capture(frame[0], root.resolve("paused.png"));
                ((JButton) named(frame[0], "queue.up")).doClick();
            });
            await(() -> state(client, id).path("conversation").path("queued").get(0).path("prompt").asText().startsWith("Сохранить результат"), "priority move up"); Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                var list = (JList<?>) named(frame[0], "queue.messages"); require(list.getSelectedIndex() == 0, "Moved selection lost");
                ((JButton) named(frame[0], "queue.down")).doClick();
            });
            await(() -> state(client, id).path("conversation").path("queued").get(1).path("prompt").asText().startsWith("Сохранить результат"), "priority move down"); Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                var list = (JList<?>) named(frame[0], "queue.messages"); list.getActionMap().get("queueUp").actionPerformed(new ActionEvent(list, 0, "keyboard"));
            });
            await(() -> state(client, id).path("conversation").path("queued").get(0).path("prompt").asText().startsWith("Сохранить результат"), "keyboard priority move"); Thread.sleep(700);
            String existingDraft = "Уже набранный черновик\n\nЕго второй абзац";
            SwingUtilities.invokeAndWait(() -> { frame[0].inputArea().setText(existingDraft); ((JButton) named(frame[0], "queue.take")).doClick(); });
            String restored = existingDraft + "\n\n---\n\nДополнение во время просмотра.";
            await(() -> frame[0].inputArea().getText().equals(restored) && frame[0].inputArea().isEditable(), "return last addition");
            require(state(client, id).path("conversation").path("queued").get(0).path("prompt").asText().equals(fullQueuedText), "Return removed older parts");
            Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> ((JButton) named(frame[0], "queue.take")).doClick());
            String restoredParagraphs = restored + "\n\n---\n\n" + longAddition;
            await(() -> frame[0].inputArea().getText().equals(restoredParagraphs) && frame[0].inputArea().isEditable(), "return complete multi-paragraph addition");
            require(state(client, id).path("sessions").get(0).path("preferences").path("draft").asText().equals(restoredParagraphs), "Returned draft was not persisted");
            require(state(client, id).path("conversation").path("queued").get(0).path("partsCount").asInt() == 2, "Return split the original message into paragraphs");
            SwingUtilities.invokeAndWait(() -> { checkLayout(frame[0], 920, 600); capture(frame[0], root.resolve("queue-editing-920.png")); ((JButton) named(frame[0], "queue.resume")).doClick(); });
            await(() -> { var s = state(client, id); return !s.path("sessions").get(0).path("running").asBoolean() && s.path("conversation").path("queued").isEmpty(); }, "queue resume");
            await(() -> ((JComboBox<?>) named(frame[0], "chat.tasks")).getItemCount() == 4, "task list includes executed requests");
            SwingUtilities.invokeAndWait(() -> {
                var picker = (JComboBox<?>) named(frame[0], "chat.tasks"); picker.setSelectedIndex(0);
                ((JButton) named(frame[0], "chat.nextTask")).doClick(); require(picker.getSelectedIndex() == 1, "Next task navigation");
                var document = (javax.swing.text.html.HTMLDocument) ((JEditorPane) named(frame[0], "chat.transcript")).getDocument();
                long sequence = state(client, id).path("conversation").path("messages").get(0).path("sequence").asLong();
                require(document.getElement("task-" + sequence) != null, "Missing request anchor");
                var transcript = (JEditorPane) named(frame[0], "chat.transcript");
                require(!transcript.getText().contains("task-divider") && !transcript.getText().contains("ЗАДАЧА"), "Numbered dividers remain in transcript");
                var bar = ((JScrollPane) named(frame[0], "chat.history")).getVerticalScrollBar();
                bar.setValue(bar.getValue() + 50); require(picker.getSelectedIndex() == 1, "Pinned request lost while scrolling its answer");
                bar.setValue(0); require(picker.getSelectedIndex() == 0, "Pinned request did not follow manual scrolling");
                var map = (JComponent) named(frame[0], "chat.requestMap");
                var click = new java.awt.event.MouseEvent(map, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, 9, 6, 1, false, java.awt.event.MouseEvent.BUTTON1);
                picker.setSelectedIndex(1); map.dispatchEvent(click); require(picker.getSelectedIndex() == 0, "Request map click did not navigate");
                require(map.getToolTipText(click).contains("Запрос 1"), "Request map lacks readable preview");
                picker.setSelectedIndex(1);
            });
            Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                var picker = (JComboBox<?>) named(frame[0], "chat.tasks"); require(picker.getSelectedIndex() == 1, "Deferred caret/layout changed the selected task");
                require(((JScrollPane) named(frame[0], "chat.history")).getVerticalScrollBar().getValue() > 100, "Task navigation was undone by a deferred scroll");
                ((JButton) named(frame[0], "chat.previousTask")).doClick(); require(picker.getSelectedIndex() == 0, "Previous task navigation");
            });
            Thread.sleep(700);
            client.post("/sessions/" + id + "/run", Json.object("prompt", "start"));
            await(() -> ((JComboBox<?>) named(frame[0], "chat.tasks")).getItemCount() == 5, "new streamed task");
            SwingUtilities.invokeAndWait(() -> {
                var picker = (JComboBox<?>) named(frame[0], "chat.tasks"); require(picker.getSelectedIndex() == 0, "Stream moved away from old task");
                checkLayout(frame[0], 920, 600); capture(frame[0], root.resolve("task-navigation-920.png"));
                captureRequestChoices(picker, root.resolve("request-choices.png"));
                ((JButton) named(frame[0], "chat.toEnd")).doClick();
                var bar = ((JScrollPane) named(frame[0], "chat.history")).getVerticalScrollBar();
                require(bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4, "Latest button did not reach the end");
                require(picker.getSelectedIndex() == 4, "Latest task not selected");
                capture(frame[0], root.resolve("task-latest-920.png"));
            });
            Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                var bar = ((JScrollPane) named(frame[0], "chat.history")).getVerticalScrollBar();
                require(bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4, "Latest position was undone after layout");
            });
            client.post("/sessions/" + id + "/stop", Json.object());
            await(()->!state(client,id).path("sessions").get(0).path("running").asBoolean(),"final stop");
            verifyComposerAndQueue(frame[0],client,id);
            SwingUtilities.invokeAndWait(()->{frame[0].inputArea().setText("urgent start");press(frame[0].inputArea(),InputEvent.SHIFT_DOWN_MASK);});
            await(()->state(client,id).path("sessions").get(0).path("running").asBoolean(),"Shift+Enter starts from pause");Thread.sleep(600);
            SwingUtilities.invokeAndWait(()->{frame[0].inputArea().setText("later");press(frame[0].inputArea(),0);});
            await(()->state(client,id).path("conversation").path("queued").get(2).path("partsCount").asInt()==2,"Enter stays in queue during priority run");
            SwingUtilities.invokeAndWait(()->((JButton)named(frame[0],"composer.stop")).doClick());
            await(()->!state(client,id).path("sessions").get(0).path("running").asBoolean(),"Stop button cancels execution");
            require(state(client,id).path("conversation").path("queued").size()==3,"Stop button lost queue");
            Thread.sleep(600);SwingUtilities.invokeAndWait(()->{frame[0].inputArea().setText("fix-now");press(frame[0].inputArea(),InputEvent.SHIFT_DOWN_MASK);});
            await(()->state(client,id).path("sessions").get(0).path("status").asText().equals("COMPLETED"),"priority message completes");
            require(state(client,id).path("sessions").get(0).path("queuePaused").asBoolean()&&state(client,id).path("conversation").path("queued").size()==3,"Priority message resumed or lost old queue");
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(root.resolve("report.json").toFile(), Json.object("passed", true, "pinnedRequestTracksScroll", true, "requestMapClickAndPreview", true, "streamKeepsReadingPosition", true, "queuePriorityAndPartReturn", true, "modelAndEffortReachAdapter", true, "enterSendsCtrlEnterNewline", true, "altGrQueuesSeparately",true,"selectionQuotePreservesDraft",true, "enterAppendsAltEnterQueuesSeparately", true, "expandQueuePreservesFullTextAndLiveUpdates", true, "fullscreenClickAndEscape", true, "previewStatePreserved", true, "widths", List.of(920, 1280), "stopPreservesQueue", true, "progressVisible", true));
            System.out.println("UX_PROBE_PASSED " + root);
        } catch (Throwable failure) { failure.printStackTrace(); exit = 1; }
        finally { if (frame[0] != null) SwingUtilities.invokeAndWait(frame[0]::closeWindow); }
        System.exit(exit);
    }
    private static JsonNode state(HostClient client, String id) { try { return client.get("/state?session=" + id); } catch (Exception e) { throw new RuntimeException(e); } }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static void verifyComposerAndQueue(WorkspaceFrame frame,HostClient client,String id)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var input=frame.inputArea();input.setText("copy me");input.append(" plus");
            var undo=input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK));input.getActionMap().get(undo).actionPerformed(new ActionEvent(input,0,"undo"));require(input.getText().equals("copy me"),"Composer undo");
            var redo=input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK));input.getActionMap().get(redo).actionPerformed(new ActionEvent(input,0,"redo"));require(input.getText().equals("copy me plus"),"Composer redo");
            var clipboard=new java.awt.datatransfer.Clipboard("probe");input.selectAll();input.getTransferHandler().exportToClipboard(input,clipboard,TransferHandler.COPY);
            try{require(clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor).equals("copy me plus"),"Custom image handler broke copy");}catch(Exception failure){throw new RuntimeException(failure);}
            for(String text:List.of("merge A","keep B","merge C")){input.setText(text);press(input,InputEvent.ALT_DOWN_MASK);}
        });
        await(()->state(client,id).path("conversation").path("queued").size()==3,"three queue rows");Thread.sleep(700);
        SwingUtilities.invokeAndWait(()->{
            var list=(JList)named(frame,"queue.messages");require(list.getSelectedIndex()==2,"Submitted queue row was not selected");
            require(!named(frame,"queue.up").getParent().getParent().isVisible(),"Queue actions permanently visible");
            ((JButton)named(frame,"queue.actions")).doClick(); list.setSelectedIndices(new int[]{0,2});((JButton)named(frame,"queue.group")).doClick();
        });
        await(()->state(client,id).path("conversation").path("queued").size()==2,"merge nonadjacent rows");Thread.sleep(700);
        SwingUtilities.invokeAndWait(()->{
            var list=(JList)named(frame,"queue.messages");list.setSelectedIndex(0);
            var rendered=(Container)list.getCellRenderer().getListCellRendererComponent(list,list.getModel().getElementAt(0),0,true,false);
            require(hasLabel(rendered,"[2]"),"Missing part count at end of row");((JButton)named(frame,"queue.group")).doClick();
        });
        await(()->state(client,id).path("conversation").path("queued").size()==3,"split back");Thread.sleep(700);
        require(state(client,id).path("conversation").path("queued").get(1).path("prompt").asText().equals("keep B"),"Split reordered unselected row");
        SwingUtilities.invokeAndWait(()->{
            var timer=new javax.swing.Timer(150,e->{for(var w:Window.getWindows())if(w instanceof JDialog d&&d.isVisible()){var option=findOption(d);if(option!=null)option.setValue(JOptionPane.NO_OPTION);}});timer.setRepeats(false);timer.start();
            ((JButton)named(frame,"queue.remove")).doClick();
        });
        require(state(client,id).path("conversation").path("queued").size()==3,"Cancel still removed queued message");
        SwingUtilities.invokeAndWait(()->{
            var input=frame.inputArea();input.setText("image draft");var pixels=new BufferedImage(128,72,BufferedImage.TYPE_INT_ARGB);
            var clipboardImage=new java.awt.datatransfer.Transferable(){
                public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors(){return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor};}
                public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f){return f.equals(java.awt.datatransfer.DataFlavor.imageFlavor);}
                public Object getTransferData(java.awt.datatransfer.DataFlavor f){return pixels;}
            };
            require(input.getTransferHandler().importData(new TransferHandler.TransferSupport(input,clipboardImage)),"Screenshot paste rejected");
        });
        await(()->state(client,id).path("sessions").get(0).path("preferences").path("draftImages").size()==1,"screenshot persisted in draft");
        var path=Path.of(state(client,id).path("sessions").get(0).path("preferences").path("draftImages").get(0).asText());require(ImageIO.read(path.toFile()).getWidth()==128,"Screenshot downscaled");
        SwingUtilities.invokeAndWait(()->call(frame,"removeImage",new Class<?>[]{Path.class},path));
    }
    private static boolean hasLabel(Container c,String text){for(var child:c.getComponents()){if(child instanceof JLabel label&&text.equals(label.getText()))return true;if(child instanceof Container nested&&hasLabel(nested,text))return true;}return false;}
    private static JOptionPane findOption(Container c){if(c instanceof JOptionPane p)return p;for(var child:c.getComponents())if(child instanceof Container nested){var p=findOption(nested);if(p!=null)return p;}return null;}
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void captureRequestChoices(JComboBox<?> picker, Path target) {
        var list = new JList(picker.getModel()); list.setCellRenderer(picker.getRenderer()); list.setSelectedIndex(picker.getSelectedIndex());
        list.setSize(400, picker.getItemCount() * 58); list.doLayout();
        try { var image = new BufferedImage(list.getWidth(), list.getHeight(), BufferedImage.TYPE_INT_RGB); var g = image.createGraphics(); list.printAll(g); g.dispose(); ImageIO.write(image, "png", target.toFile()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void press(JTextArea input, int modifiers) {
        var key = input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifiers));
        input.getActionMap().get(key).actionPerformed(new ActionEvent(input, ActionEvent.ACTION_PERFORMED, "enter"));
    }
    private static Component image(WorkspaceFrame frame) { return named((Container) read(frame, "viewer"), "artifact.image"); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JTextArea expandedText(JList<?> list, int index) {
        var cell = ((ListCellRenderer) list.getCellRenderer()).getListCellRendererComponent(list, list.getModel().getElementAt(index), index, true, false);
        return (JTextArea) named((Container) cell, "queue.fullText");
    }
    private static Component named(Container root, String name) {
        if (name.equals(root.getName())) return root;
        for (var c : root.getComponents()) { if (name.equals(c.getName())) return c; if (c instanceof Container child) { var found = named(child, name); if (found != null) return found; } }
        return null;
    }
    private static Object read(Object object, String name) { try { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); } catch (Exception e) { throw new RuntimeException(e); } }
    private static void call(Object object, String name, Class<?>[] types, Object... args) { try { var m = object.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(object, args); } catch (Exception e) { throw new RuntimeException(e); } }
    private static void checkLayout(WorkspaceFrame frame, int width, int height) {
        frame.setSize(width, height); frame.validate();
        var scroll = (JScrollPane) named(frame, "chat.history"); scroll.getViewport().doLayout();
        require(!scroll.getHorizontalScrollBar().isVisible(), "Horizontal chat scrollbar");
        require(scroll.getViewport().getView().getWidth() == scroll.getViewport().getWidth(), "Chat wider than viewport");
        require(frame.inputArea().getWidth() >= 90, "Composer collapsed");
        require(named(frame, "artifact.fullscreen").getWidth() > 10, "Fullscreen control clipped");
        require(named(frame, "chat.toEnd").getWidth() > 55 && named(frame, "chat.tasks").getWidth() > 70, "Chat navigation clipped");
        for (String picker : List.of("session.model", "session.effort")) {
            var control = (JComboBox<?>) named(frame, picker);
            var bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), frame.getContentPane());
            require(new Rectangle(0, 0, frame.getContentPane().getWidth(), frame.getContentPane().getHeight()).contains(bounds), "Picker clipped: " + picker + " " + bounds);
            require(control.getWidth() >= 160 && !control.isEditable(), "Picker width or editor: " + picker);
        }
    }
    private static void capture(WorkspaceFrame frame, Path path) {
        try {
            var surface = (JComponent) frame.getContentPane(); var image = new BufferedImage(surface.getWidth(), surface.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics(); surface.printAll(g); g.dispose(); ImageIO.write(image, "png", path.toFile());
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void await(java.util.function.BooleanSupplier predicate, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { if (predicate.getAsBoolean()) return; Thread.sleep(80); }
        throw new AssertionError("Timed out: " + description);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

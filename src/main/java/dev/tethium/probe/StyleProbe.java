package dev.tethium.probe;

import dev.tethium.agent.*;
import dev.tethium.core.SessionStore;
import dev.tethium.desktop.*;
import dev.tethium.host.*;
import dev.tethium.json.Json;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Screenshots of the real Swing window with isolated sample projects; never runs a model. */
public final class StyleProbe {
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(".local/style", UUID.randomUUID().toString()).toAbsolutePath());
        Path data = Files.createDirectories(root.resolve("data"));
        Path project = Files.createDirectories(root.resolve("Tethium")); String session;
        Path image = Files.copy(Path.of("design/image-demo/tethium-workspace-v1.png"), project.resolve("workspace.png"));
        try (var store = new SessionStore(data.resolve("tethium.db"))) {
            String p = store.project(project); session = store.create(p, null, "Визуальный стиль", project).id();
            store.create(p, null, "Сессии и очередь", project); store.create(p, null, "Android · подключение", project);
            var archived = store.create(p, null, "Первый прототип", project); store.setActive(archived.id(), false);
            for (String name : List.of("Workspace", "Документация")) {
                Path folder = Files.createDirectories(root.resolve(name)); String id = store.project(folder);
                store.create(id, null, name.equals("Workspace") ? "Главный экран" : "Руководство пользователя", folder);
                store.create(id, null, name.equals("Workspace") ? "Проверка компонентов" : "Заметки о релизе", folder);
            }
            store.append(session, "user", "USER", "Приведи интерфейс к одному стилю. Хочется аккуратно, компактно и живо.");
            store.append(session, "assistant", "CODEX", "Собрал основу нового оформления.\n\n- Графитовые поверхности и один синий акцент.\n- Монохромные иконки для привычных действий.\n- Общие отступы для панелей, текста и полей.\n\n```java\nTheme.GAP    = 8;\nTheme.INSET  = 12;\nTheme.GUTTER = 16;\n```\n\nОчередь остаётся над полем ввода, результаты открываются справа.");
            store.artifact(session, image, "Рабочее место Tethium");
        }
        AgentAdapter adapter = new AgentAdapter() {
            public Provider provider() { return Provider.CODEX; }
            public Result run(Request request, Consumer<Event> events, Cancellation cancellation) throws Exception {
                events.accept(new Event("conversation", "style-fixture", Json.object()));
                events.accept(new Event("text", "Проверяю поля ввода, переносы текста и просмотр изображений.", Json.object()));
                var operation = Json.object("params", Json.object("item", Json.object("id", "layout", "type", "commandExecution", "command", "gradlew uxProbe", "exitCode", 0)));
                events.accept(new Event("item/started", "", operation)); events.accept(new Event("item/completed", "", operation));
                while (!cancellation.isCancelled()) Thread.sleep(50);
                return new Result("style-fixture", Status.CANCELLED, "");
            }
        };
        var limits = Json.object("rateLimits", Json.object("limitId", "codex", "primary", Json.object("usedPercent", 28, "windowDurationMins", 300, "resetsAt", System.currentTimeMillis()/1000 + 7200)));
        Theme.install(); var frame = new WorkspaceFrame[1]; int exit = 0;
        try (var host = new HostService(data, adapter, new CodexQuotaService(() -> limits)); var client = new HostClient(host.descriptor())) {
            SwingUtilities.invokeAndWait(() -> { frame[0] = new WorkspaceFrame(new HostClient(host.descriptor()), () -> {}, session); frame[0].addNotify(); frame[0].validate(); });
            Thread.sleep(900);
            client.post("/sessions/" + session + "/run", Json.object("prompt", "Проверь узкое окно и состояния кнопок."));
            Thread.sleep(300);
            client.post("/sessions/" + session + "/run", Json.object("prompt", "Проверь тёмный фон и читаемость иконок."));
            client.post("/sessions/" + session + "/run", Json.object("prompt", "Сохрани снимки интерфейса.", "separate", true));
            Thread.sleep(900);
            SwingUtilities.invokeAndWait(() -> invoke(frame[0], "openArtifact", new Class<?>[]{Path.class}, image));
            Thread.sleep(900);
            for (int width : new int[]{1280, 920}) {
                SwingUtilities.invokeAndWait(() -> { frame[0].setSize(width, width == 920 ? 600 : 860); frame[0].validate(); });
                Thread.sleep(200);
                SwingUtilities.invokeAndWait(() -> {
                    var scroll = (JScrollPane) find(frame[0], "chat.history"); scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                    capture(frame[0], root.resolve("workspace-" + width + ".png"));
                });
            }
            SwingUtilities.invokeAndWait(() -> { invoke(frame[0], "showPreview", new Class<?>[]{boolean.class}, false); frame[0].setSize(1280, 860); frame[0].validate(); });
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> capture(frame[0], root.resolve("conversation.png")));
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(root.resolve("report.json").toFile(), Json.object("sampleData", true, "modelCalls", 0, "widths", List.of(1280, 920), "icons", 16));
            System.out.println("STYLE_PROBE_PASSED " + root);
        } catch (Throwable failure) { failure.printStackTrace(); exit = 1; }
        finally { if (frame[0] != null) SwingUtilities.invokeAndWait(frame[0]::closeWindow); }
        System.exit(exit);
    }
    private static Component find(Container root, String name) {
        for (var c : root.getComponents()) { if (name.equals(c.getName())) return c; if (c instanceof Container child) { var found = find(child, name); if (found != null) return found; } } return null;
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(target, args); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void capture(WorkspaceFrame frame, Path path) {
        try {
            var surface = (JComponent) frame.getContentPane(); var image = new BufferedImage(surface.getWidth(), surface.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics(); surface.printAll(g); g.dispose(); ImageIO.write(image, "png", path.toFile());
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

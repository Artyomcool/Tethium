package dev.tethium.probe;

import dev.tethium.agent.*;
import dev.tethium.desktop.*;
import dev.tethium.host.*;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Explicit desktop integration probe. `real` spends CLI quota and edits only its disposable workspace. */
public final class DesktopProbe {
    public static void main(String[] args) throws Exception {
        boolean real = args.length > 0 && args[0].equals("real");
        Path root = Path.of(".local", "desktop", UUID.randomUUID().toString()).toAbsolutePath();
        Path workspace = Files.createDirectories(root.resolve("Workspace"));
        Theme.install();
        AgentAdapter adapter = real ? new CodexAdapter() : new AgentAdapter() {
            public Provider provider() { return Provider.CODEX; }
            public Result run(Request request, Consumer<Event> events, Cancellation cancel) throws Exception {
                events.accept(new Event("conversation", "desktop-test", Json.object()));
                String text = "Сессии остаются активными после завершения ответа.\n\n- Проекты раскрываются независимо.\n- История и черновики сохраняются.\n- Работа продолжается при закрытом окне.\n\n```java\nvar session = project.createSession();\n```\n\nМожно продолжать работу.";
                for (String word : text.split(" ")) { if (cancel.isCancelled()) break; events.accept(new Event("text", word + " ", Json.object())); Thread.sleep(15); }
                return new Result("desktop-test", Status.COMPLETED, text);
            }
        };
        try (var host = new HostService(root.resolve("data"), adapter); var client = new HostClient(host.descriptor())) {
            String project = client.post("/projects", Json.object("path", workspace.toString())).path("id").asText();
            String id = client.post("/sessions", Json.object("projectId", project, "title", "Рабочий интерфейс Tethium")).path("id").asText();
            var archive = client.post("/sessions", Json.object("projectId", project, "title", "Предыдущая архитектура"));
            client.post("/sessions/" + archive.path("id").asText() + "/active", Json.object("active", false));
            client.post("/sessions", Json.object("projectId", project, "title", "Соседняя сессия"));
            var frame = new WorkspaceFrame[1];
            SwingUtilities.invokeAndWait(() -> { frame[0] = new WorkspaceFrame(new HostClient(host.descriptor()), () -> {}); frame[0].addNotify(); frame[0].validate(); });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!id.equals(frame[0].selectedSession()) && System.nanoTime() < deadline) Thread.sleep(50);
            if (!id.equals(frame[0].selectedSession())) throw new AssertionError("No session selection");
            String prompt = real ? "Это проверка Tethium GUI в изолированном тестовом каталоге. Используй session_list, чтобы увидеть соседние сессии. Создай только здесь файл ready.txt с точным содержимым TETHIUM_GUI_OK и файл result.html с короткой HTML-страницей, собственным CSS и кнопкой JavaScript, меняющей текст на OK. Затем вызови artifact_publish для result.html. Не используй подсессии, ничего не устанавливай. Ответь кратко по-русски." : "Проверь компактный интерфейс проектов и сессий.";
            SwingUtilities.invokeAndWait(() -> { frame[0].inputArea().setText(prompt); frame[0].send(); });
            JsonNode finalState = await(client, id, real ? 240 : 20);
            if (!finalState.path("sessions").get(0).path("status").asText().equals("COMPLETED")) throw new AssertionError(finalState.path("conversation").toPrettyString());
            if (real) {
                if (!Files.readString(workspace.resolve("ready.txt")).strip().equals("TETHIUM_GUI_OK")) throw new AssertionError("Write failed");
                if (finalState.path("conversation").path("artifacts").isEmpty()) throw new AssertionError("Artifact was not published");
                client.post("/sessions/" + id + "/run", Json.object("prompt", "Продолжение той же сессии после переподключения. Прочитай ready.txt и снова вызови session_list. Ничего не меняй. Ответь точным содержимым файла и количеством сессий."));
                finalState = await(client, id, 180);
                if (!finalState.path("sessions").get(0).path("status").asText().equals("COMPLETED")) throw new AssertionError("Resume failed: " + finalState.path("conversation"));
            }
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(root.resolve("report.json").toFile(), Json.object("passed", true, "realCodex", real, "state", finalState));
            Thread.sleep(800);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    frame[0].validate(); var surface = frame[0].getContentPane();
                    var image = new BufferedImage(surface.getWidth(), surface.getHeight(), BufferedImage.TYPE_INT_RGB);
                    var g = image.createGraphics(); ((JComponent) surface).print(g); g.dispose(); ImageIO.write(image, "png", root.resolve("desktop.png").toFile());
                } catch (Exception e) { throw new RuntimeException(e); }
                frame[0].closeWindow();
            });
            System.out.println("DESKTOP_PROBE_PASSED " + root);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
    private static JsonNode await(HostClient client, String id, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds); boolean started = false;
        while (System.nanoTime() < deadline) {
            var state = client.get("/state?session=" + id); var session = state.path("sessions").get(0);
            if (session.path("running").asBoolean()) started = true;
            for (var request : state.path("conversation").path("requests")) {
                // Tests do not silently grant elevated permissions. An unexpected approval is a failed probe.
                throw new AssertionError("Unexpected approval: " + request);
            }
            if (started && !session.path("running").asBoolean() && session.path("queued").asInt() == 0) return state;
            Thread.sleep(200);
        }
        throw new AssertionError("Desktop run timed out");
    }
}

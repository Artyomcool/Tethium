package dev.tethium.desktop;

import dev.tethium.host.*;
import dev.tethium.json.Json;
import javax.swing.*;
import java.nio.file.*;

public final class DesktopMain {
    public static void main(String[] args) {
        String data = option(args, "--data="); if (data != null) System.setProperty("tethium.data", data);
        String instance = option(args, "--instance="); if (instance == null) instance = java.util.UUID.randomUUID().toString();
        String instanceId = instance;
        boolean hidden = java.util.Arrays.asList(args).contains("--hidden");
        Theme.install();
        Thread.ofPlatform().name("tethium-connect").start(() -> {
            try {
                var host = HostClient.connect();
                String directory = java.util.Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst().orElse(null);
                if (directory != null && Files.isDirectory(Path.of(directory)) && host.get("/state").path("projects").isEmpty()) {
                    var project = host.post("/projects", Json.object("path", Path.of(directory).toAbsolutePath().toString()));
                    host.post("/sessions", Json.object("projectId", project.path("id").asText(), "title", "Новая сессия"));
                }
                String selected = java.util.Arrays.stream(args).filter(a -> a.startsWith("--session=")).map(a -> a.substring(10)).findFirst().orElse(null);
                SwingUtilities.invokeLater(() -> {
                    var control = new DesktopControl[1];
                    var frame = new WorkspaceFrame(host, () -> { if (control[0] != null) control[0].close(); System.exit(0); }, selected);
                    try {
                        String appPath = option(args, "--app-path=");
                        if (appPath == null) appPath = System.getProperty("jpackage.app-path", ProcessHandle.current().info().command().orElse(""));
                        control[0] = new DesktopControl(frame, instanceId, appPath);
                        if (hidden) { frame.addNotify(); frame.validate(); } else frame.setVisible(true);
                        if(!hidden)Thread.ofVirtual().start(()->dev.tethium.update.UpdateRequests.resume(AppPaths.data()));
                    } catch (Exception e) { fail(instanceId, hidden, e); }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> fail(instanceId, hidden, e));
            }
        });
    }
    private static String option(String[] args, String prefix) { return java.util.Arrays.stream(args).filter(a -> a.startsWith(prefix)).map(a -> a.substring(prefix.length())).findFirst().orElse(null); }
    private static void fail(String instance, boolean hidden, Exception error) {
        try { Path directory = Files.createDirectories(AppPaths.data().resolve("clients")); Json.MAPPER.writeValue(directory.resolve(instance + ".error.json").toFile(), Json.object("error", error.toString())); } catch (Exception ignored) {}
        if (!hidden) JOptionPane.showMessageDialog(null, error.getMessage(), "Не удалось открыть Tethium", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }
}

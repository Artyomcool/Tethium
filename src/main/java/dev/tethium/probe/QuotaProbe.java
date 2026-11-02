package dev.tethium.probe;

import dev.tethium.agent.CodexAccountClient;
import dev.tethium.agent.CodexAdapter;
import dev.tethium.desktop.*;
import dev.tethium.host.HostClient;
import dev.tethium.host.HostService;
import dev.tethium.host.CodexQuotaService;
import dev.tethium.json.Json;
import java.nio.file.*;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.UUID;

/** Fetches account limits only; no thread or model turn is created. */
public final class QuotaProbe {
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(".local", "quota").toAbsolutePath());
        var response = new CodexAccountClient(root).readRateLimits();
        var buckets = CodexQuotaService.normalize(response);
        var result = Json.object("checkedAt", System.currentTimeMillis(), "buckets", buckets);
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(root.resolve("live.json").toFile(), result);
        Path fixture = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        Theme.install(); var frame = new WorkspaceFrame[1];
        try (var host = new HostService(fixture, new CodexAdapter(), new CodexQuotaService(() -> response)); var client = new HostClient(host.descriptor())) {
            long deadline = System.currentTimeMillis() + 5000;
            while (!client.get("/state").path("quota").path("state").asText().equals("ready") && System.currentTimeMillis() < deadline) Thread.sleep(30);
            if (!client.get("/state").path("quota").path("buckets").equals(buckets)) throw new AssertionError("Quota not exposed through host state");
            SwingUtilities.invokeAndWait(() -> { frame[0] = new WorkspaceFrame(new HostClient(host.descriptor()), () -> {}); frame[0].setSize(920, 600); frame[0].addNotify(); frame[0].validate(); });
            Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    frame[0].validate(); var panel = find(frame[0], "quota.panel");
                    if (panel == null || panel.getWidth() < 190 || panel.getHeight() < 40) throw new AssertionError("Quota sidebar is not visible");
                    var surface = (JComponent) frame[0].getContentPane();
                    var screenshot = new BufferedImage(surface.getWidth(), surface.getHeight(), BufferedImage.TYPE_INT_RGB);
                    var g = screenshot.createGraphics(); surface.printAll(g); g.dispose(); ImageIO.write(screenshot, "png", root.resolve("sidebar.png").toFile());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally { if (frame[0] != null) SwingUtilities.invokeAndWait(frame[0]::closeWindow); }
        System.out.println(result.toPrettyString());
        System.out.println("QUOTA_SIDEBAR_PASSED " + root);
        System.exit(0);
    }
    private static Component find(Container root, String name) {
        for (var c : root.getComponents()) { if (name.equals(c.getName())) return c; if (c instanceof Container child) { var found = find(child, name); if (found != null) return found; } }
        return null;
    }
}

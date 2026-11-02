package dev.tethium.probe;

import dev.tethium.artifact.ArtifactServer;
import dev.tethium.json.Json;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.*;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.*;

/** Hidden Swing peer + Chromium: HTML, JS, CSS, fetch, and image checks on Java 25. */
public final class PreviewProbe {
    public static void main(String[] args) throws Exception {
        try { run(); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    private static void run() throws Exception {
        Path output = Path.of(".local", "preview").toAbsolutePath();
        Path bundle = output.resolve("bundle"); Files.createDirectories(bundle);
        for (String file : new String[]{"index.html", "preview.js", "preview.css", "sample.svg", "data.json"}) {
            try (var input = PreviewProbe.class.getResourceAsStream("/preview/" + file)) {
                if (input == null) throw new IllegalStateException("Missing fixture: " + file);
                Files.copy(input, bundle.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        var ready = new CompletableFuture<String>();
        var terminated = new CompletableFuture<Void>();
        var builder = new CefAppBuilder();
        builder.setInstallDir(output.resolve("jcef").toFile());
        builder.getCefSettings().windowless_rendering_enabled = false;
        builder.getCefSettings().log_file = output.resolve("chromium.log").toString();
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override public void stateHasChanged(CefApp.CefAppState state) {
                if (state == CefApp.CefAppState.TERMINATED) terminated.complete(null);
            }
        });
        builder.addJcefArgs("--disable-gpu");
        try (var server = new ArtifactServer(bundle)) {
            CefApp app = builder.build();
            var client = app.createClient();
            client.addDisplayHandler(new CefDisplayHandlerAdapter() {
                @Override public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
                    if (message.startsWith("TETHIUM_READY:")) ready.complete(message.substring("TETHIUM_READY:".length()));
                    if (message.startsWith("TETHIUM_ERROR:")) ready.completeExceptionally(new IllegalStateException(message));
                    return false;
                }
            });
            var browser = client.createBrowser(server.uri("index.html").toString(), false, false);
            var frame = new JFrame("Tethium preview verification");
            try {
                SwingUtilities.invokeAndWait(() -> {
                    frame.setSize(720, 480);
                    frame.getContentPane().add(browser.getUIComponent());
                    frame.addNotify();
                    frame.validate();
                    browser.createImmediately();
                });
                String evidence = ready.get(60, TimeUnit.SECONDS);
                var devTools = browser.getDevToolsClient();
                devTools.executeDevToolsMethod("Emulation.setDeviceMetricsOverride", "{\"width\":720,\"height\":480,\"deviceScaleFactor\":1,\"mobile\":false}").get(10, TimeUnit.SECONDS);
                String capture = devTools.executeDevToolsMethod("Page.captureScreenshot", "{\"format\":\"png\",\"captureBeyondViewport\":true}").get(20, TimeUnit.SECONDS);
                byte[] pixels = Base64.getDecoder().decode(Json.parse(capture).path("data").asText());
                var screenshot = ImageIO.read(new java.io.ByteArrayInputStream(pixels));
                if (screenshot.getWidth() < 200 || screenshot.getHeight() < 100) throw new IllegalStateException("Unexpected screenshot size");
                ImageIO.write(screenshot, "png", output.resolve("preview.png").toFile());
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(), Json.object(
                        "passed", true, "java", Runtime.version().toString(), "chromium", app.getVersion().toString(),
                        "evidence", Json.parse(evidence), "width", screenshot.getWidth(), "height", screenshot.getHeight()));
                System.out.println("PASS: " + output.resolve("report.json"));
            } finally {
                SwingUtilities.invokeAndWait(frame::dispose);
                browser.close(true); app.dispose();
                try { terminated.get(15, TimeUnit.SECONDS); }
                catch (TimeoutException ignored) { System.err.println("Chromium shutdown exceeded 15 seconds"); }
            }
        }
    }
}


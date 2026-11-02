package dev.tethium.probe;

import dev.tethium.desktop.*;
import dev.tethium.json.Json;
import org.cef.browser.CefBrowser;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs the actual GUI artifact component, including its navigation policy, with the packaged Java runtime. */
public final class ArtifactViewProbe {
    public static void main(String[] args) throws Exception {
        int exit = 0;
        Path root = Files.createDirectories(Path.of(".local", "desktop-preview").toAbsolutePath());
        System.setProperty("tethium.data", root.toString());
        Theme.install();
        var frame = new JFrame("Tethium artifact verification");
        var view = new ArtifactView();
        try {
            for (String file : new String[]{"index.html", "preview.js", "preview.css", "sample.svg", "data.json"}) {
                try (var stream = ArtifactViewProbe.class.getResourceAsStream("/preview/" + file)) {
                    Files.copy(Objects.requireNonNull(stream), root.resolve(file.equals("index.html") ? "result #1.html" : file), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            SwingUtilities.invokeAndWait(() -> { frame.setSize(720, 480); frame.add(view); frame.addNotify(); frame.validate(); view.open(root.resolve("result #1.html")); });
            var browserField = ArtifactView.class.getDeclaredField("browser"); browserField.setAccessible(true);
            CefBrowser browser = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (browser == null && System.nanoTime() < deadline) {
                CefBrowser[] value = new CefBrowser[1]; SwingUtilities.invokeAndWait(() -> value[0] = (CefBrowser) read(browserField, view)); browser = value[0]; Thread.sleep(100);
            }
            if (browser == null) throw new AssertionError("Browser did not initialize");
            CefBrowser readyBrowser = browser;
            SwingUtilities.invokeAndWait(() -> { frame.validate(); readyBrowser.createImmediately(); });
            String status = "";
            while (System.nanoTime() < deadline) {
                try {
                    if (browser.getDevToolsClient() == null) { Thread.sleep(200); continue; }
                    String result = browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate", "{\"expression\":\"document.querySelector('#status')?.textContent\",\"returnByValue\":true}").get(3, TimeUnit.SECONDS);
                    status = Json.parse(result).path("result").path("value").asText();
                    if (status.equals("All preview checks passed.")) break;
                } catch (TimeoutException | ExecutionException ignored) { }
                Thread.sleep(200);
            }
            if (!status.equals("All preview checks passed.")) throw new AssertionError("Preview resources failed: " + status);
            boolean focusChecked=java.util.Arrays.asList(args).contains("--focus");
            if(focusChecked) {
                var composer=new JTextArea(3,30); composer.setName("probe.composer");
                composer.addMouseListener(new java.awt.event.MouseAdapter(){@Override public void mousePressed(java.awt.event.MouseEvent e){view.releaseFocus();composer.requestFocusInWindow();}});
                composer.addFocusListener(new java.awt.event.FocusAdapter(){@Override public void focusGained(java.awt.event.FocusEvent e){view.releaseFocus();}});
                SwingUtilities.invokeAndWait(()->{frame.add(composer,java.awt.BorderLayout.SOUTH);frame.setLocationRelativeTo(null);frame.setAlwaysOnTop(true);frame.setVisible(true);frame.toFront();frame.validate();});
                browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate",Json.object("expression","let field=document.createElement('input');field.id='focus-probe';field.style='position:fixed;left:12px;top:12px;width:160px;height:34px;z-index:99999';document.body.append(field);field.focus();").toString()).get(3,TimeUnit.SECONDS);
                var robot=new java.awt.Robot();robot.setAutoDelay(80);
                Thread.sleep(400);
                if(!frame.isShowing())throw new AssertionError("Focus probe window is not visible");
                var point=new java.awt.Point[1]; SwingUtilities.invokeAndWait(()->point[0]=readyBrowser.getUIComponent().getLocationOnScreen());
                robot.mouseMove(point[0].x+50,point[0].y+26);robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);robot.keyPress(java.awt.event.KeyEvent.VK_B);robot.keyRelease(java.awt.event.KeyEvent.VK_B);
                Thread.sleep(200);
                var fieldValue=Json.parse(browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate",Json.object("expression","document.getElementById('focus-probe').value","returnByValue",true).toString()).get(3,TimeUnit.SECONDS)).path("result").path("value").asText();
                javax.imageio.ImageIO.write(robot.createScreenCapture(frame.getBounds()),"png",root.resolve("focus-screen.png").toFile());
                if(fieldValue.length()!=1)throw new AssertionError("Explicit browser typing failed: "+fieldValue);
                SwingUtilities.invokeAndWait(()->point[0]=composer.getLocationOnScreen());
                robot.mouseMove(point[0].x+40,point[0].y+25);robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(400);
                robot.keyPress(java.awt.event.KeyEvent.VK_C);robot.keyRelease(java.awt.event.KeyEvent.VK_C);Thread.sleep(250);
                javax.imageio.ImageIO.write(robot.createScreenCapture(frame.getBounds()),"png",root.resolve("focus-after.png").toFile());
                SwingUtilities.invokeAndWait(()->{if(composer.getText().length()!=1)throw new AssertionError("HTML stole composer typing");frame.setVisible(false);frame.remove(composer);frame.validate();});
            }
            browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate", "{\"expression\":\"window.tethiumRetained = 42\"}").get(3, TimeUnit.SECONDS);
            var expanded = new JDialog(frame);
            SwingUtilities.invokeAndWait(() -> {
                expanded.setUndecorated(true); expanded.setBounds(frame.getGraphicsConfiguration().getBounds()); expanded.addNotify();
                expanded.add(view); view.setFullScreen(true); expanded.validate();
            });
            Thread.sleep(400);
            String retained = browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate", "{\"expression\":\"window.tethiumRetained\",\"returnByValue\":true}").get(3, TimeUnit.SECONDS);
            if (Json.parse(retained).path("result").path("value").asInt() != 42) throw new AssertionError("HTML state lost on fullscreen entry");
            SwingUtilities.invokeAndWait(() -> { frame.add(view); view.setFullScreen(false); frame.validate(); expanded.dispose(); });
            Thread.sleep(400);
            retained = browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate", "{\"expression\":\"window.tethiumRetained\",\"returnByValue\":true}").get(3, TimeUnit.SECONDS);
            if (Json.parse(retained).path("result").path("value").asInt() != 42) throw new AssertionError("HTML state lost on fullscreen exit");
            String capture = browser.getDevToolsClient().executeDevToolsMethod("Page.captureScreenshot", "{\"format\":\"png\"}").get(10, TimeUnit.SECONDS);
            Files.write(root.resolve("preview.png"), Base64.getDecoder().decode(Json.parse(capture).path("data").asText()));
            Json.MAPPER.writeValue(root.resolve("report.json").toFile(), Json.object("passed", true, "javaHome", System.getProperty("java.home"), "status", status, "fullscreenRetainsState", true,"nativeFocusChecked",focusChecked));
            System.out.println("ARTIFACT_VIEW_PASSED " + root);
        } catch (Throwable failure) { failure.printStackTrace(); exit = 1; }
        finally { SwingUtilities.invokeAndWait(() -> { view.close(); frame.dispose(); }); }
        System.exit(exit);
    }
    private static Object read(java.lang.reflect.Field field, Object instance) {
        try { return field.get(instance); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
    }
}

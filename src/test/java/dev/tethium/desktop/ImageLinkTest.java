package dev.tethium.desktop;

import dev.tethium.agent.*;
import dev.tethium.host.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import javax.swing.text.html.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class ImageLinkTest {
    @TempDir Path root;
    @Test void activatingChatPngLinkLoadsTheClickedImageAndOpensVisiblePreview() throws Exception {
        Theme.install();
        Path png = root.resolve("test + картинка.png");
        var image = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(new Color(0x73cfb6)); g.fillRect(0, 0, 320, 200); g.dispose(); ImageIO.write(image, "png", png.toFile());
        AgentAdapter unused = new AgentAdapter() {
            public Provider provider() { return Provider.CODEX; }
            public Result run(Request request, Consumer<Event> events, Cancellation cancel) { throw new AssertionError("No model call expected"); }
        };
        try (var host = new HostService(root.resolve("data"), unused); var client = new HostClient(host.descriptor())) {
            String originalLink = png.toString().replace('\\', '/');
            var session = client.post("/imports/conversation", Json.object("projectPath", root.toString(), "title", "PNG link", "sourceKey", "png-link-test",
                    "messages", List.of(Json.object("sourceId", "1", "role", "assistant", "text", "[PNG](<" + originalLink + ">)"))));
            String id = session.path("id").asText();
            var frame = new AtomicReference<WorkspaceFrame>();
            var closed = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> { frame.set(new WorkspaceFrame(new HostClient(host.descriptor()), closed::countDown, id)); frame.get().addNotify(); frame.get().validate(); });
            try {
                var href = new AtomicReference<String>();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (href.get() == null && System.nanoTime() < deadline) {
                    SwingUtilities.invokeAndWait(() -> {
                        var transcript = (JEditorPane) field(frame.get(), "transcript");
                        var anchors = ((HTMLDocument) transcript.getDocument()).getIterator(HTML.Tag.A);
                        if (anchors.isValid()) href.set((String) anchors.getAttributes().getAttribute(HTML.Attribute.HREF));
                    });
                    Thread.sleep(30);
                }
                assertNotNull(href.get()); assertFalse(href.get().isBlank(), "Markdown erased the local file path");
                SwingUtilities.invokeAndWait(() -> {
                    var transcript = (JEditorPane) field(frame.get(), "transcript");
                    var anchor = ((HTMLDocument) transcript.getDocument()).getIterator(HTML.Tag.A);
                    try {
                        frame.get().validate();
                        var position = transcript.modelToView2D(anchor.getStartOffset());
                        assertNotNull(position);
                        transcript.dispatchEvent(new java.awt.event.MouseEvent(transcript, java.awt.event.MouseEvent.MOUSE_CLICKED,
                                System.currentTimeMillis(), 0, (int) position.getX() + 2, (int) position.getCenterY(), 1, false, java.awt.event.MouseEvent.BUTTON1));
                    } catch (javax.swing.text.BadLocationException e) { throw new RuntimeException(e); }
                });
                var loaded = new AtomicReference<Boolean>(false);
                while (!loaded.get() && System.nanoTime() < deadline) {
                    SwingUtilities.invokeAndWait(() -> {
                        var viewer = (ArtifactView) field(frame.get(), "viewer"); frame.get().validate();
                        loaded.set(viewer.getComponentCount() == 1 && viewer.getComponent(0).getClass().getSimpleName().equals("ImagePanel"));
                    }); Thread.sleep(30);
                }
                assertTrue(loaded.get(), "PNG was not decoded into the preview");
                SwingUtilities.invokeAndWait(() -> {
                    var viewer = (ArtifactView) field(frame.get(), "viewer");
                    assertEquals(png.toAbsolutePath().normalize(), ((Path) field(viewer, "current")).toAbsolutePath().normalize());
                    assertTrue((Boolean) field(frame.get(), "previewOpen"));
                    assertTrue(viewer.getWidth() > 100 && viewer.getHeight() > 100, "Preview has no usable size");
                });
            } finally { SwingUtilities.invokeAndWait(frame.get()::closeWindow); assertTrue(closed.await(5, TimeUnit.SECONDS)); }
        }
    }
    private static Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}

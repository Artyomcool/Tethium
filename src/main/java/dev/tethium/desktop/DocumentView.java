package dev.tethium.desktop;

import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.xhtmlrenderer.simple.FSScrollPane;
import org.xhtmlrenderer.swing.BasicPanel;
import org.xhtmlrenderer.swing.LinkListener;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.*;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Documents drawn in pure Java (Flying Saucer, CSS 2.1): no browser to download, no native libraries, no GPU. The page the laptop
 * renders is turned into strict XHTML by jsoup; images and styles load from the same address the page came from. Inline images,
 * SVG charts among them, are drawn to PNG files first: the renderer reads neither data: addresses nor SVG.
 */
final class DocumentView {
    private DocumentView() {}
    static JComponent create(String html, URI base, Consumer<URI> openLink) {
        var page = Jsoup.parse(html, base.toString());
        for (var image : page.select("img[src^=data:image/]")) {
            try { image.attr("src", inline(image.attr("src")).toUri().toString()); } catch (Exception failure) { image.attr("alt", "[изображение]").removeAttr("src"); }
        }
        var document = new W3CDom().fromJsoup(page);
        var panel = new SelectablePanel();
        for (var listener : panel.getMouseTrackingListeners()) if (listener instanceof LinkListener) panel.removeMouseTrackingListener(listener);
        panel.addMouseTrackingListener(new LinkListener() {
            @Override public void linkClicked(BasicPanel source, String uri) { if (!panel.selectedText().isEmpty()) return; try { openLink.accept(URI.create(base.resolve(uri).toString())); } catch (Exception ignored) {} }
        });
        panel.setDocument(document, base.toString());
        var scroll = new FSScrollPane(panel); scroll.setBorder(null); scroll.getVerticalScrollBar().setUnitIncrement(24);
        return scroll;
    }
    private static Path inline(String data) throws Exception {
        int comma = data.indexOf(','); String head = data.substring(5, comma);
        byte[] bytes = head.endsWith(";base64") ? Base64.getDecoder().decode(data.substring(comma + 1)) : java.net.URLDecoder.decode(data.substring(comma + 1), java.nio.charset.StandardCharsets.UTF_8).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = Files.createTempFile("tethium-document-", head.startsWith("image/svg") ? ".png" : "." + head.replaceAll("^image/([a-z0-9+]+).*$", "$1").replace("jpeg", "jpg"));
        file.toFile().deleteOnExit();
        if (!head.startsWith("image/svg")) { Files.write(file, bytes); return file; }
        var svg = new SVGLoader().load(new ByteArrayInputStream(bytes), null, LoaderContext.builder().build());
        if (svg == null) throw new IllegalArgumentException("SVG");
        var size = svg.size(); int width = Math.max(1, Math.min(4096, Math.round(size.width))), height = Math.max(1, Math.min(4096, Math.round(size.height)));
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        svg.render(null, g); g.dispose();
        ImageIO.write(image, "png", file.toFile()); return file;
    }
}

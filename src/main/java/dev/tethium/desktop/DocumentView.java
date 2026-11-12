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
    /**
     * Builds and lays out the page; safe off the event thread, since nothing is on screen yet. A link to a place in the same page
     * scrolls there, any other link goes to openLink.
     */
    static JComponent create(String html, URI base, Consumer<URI> openLink, int width) {
        var page = Jsoup.parse(html, base.toString());
        // SVG is kept as vectors and drawn at the scale it is painted at; other inline images become files the renderer can read.
        var vectors = new java.util.HashMap<String, com.github.weisj.jsvg.SVGDocument>();
        for (var image : page.select("img[src]")) {
            String src = image.attr("src");
            try {
                byte[] svg = null;
                if (src.startsWith("data:image/svg")) svg = decode(src);
                else if (src.toLowerCase(java.util.Locale.ROOT).matches("[^?#]*\\.svg([?#].*)?")) try (var in = base.resolve(src).toURL().openStream()) { svg = in.readAllBytes(); }
                if (svg != null) {
                    var parsed = SvgElements.parse(svg); if (parsed == null) throw new IllegalArgumentException("SVG");
                    String key = "svg-" + vectors.size(); vectors.put(key, parsed); image.attr(SvgElements.ATTRIBUTE, key).removeAttr("src");
                } else if (src.startsWith("data:image/")) image.attr("src", inline(src).toUri().toString());
            } catch (Exception failure) { image.attr("alt", "[изображение]").removeAttr("src"); }
        }
        // Headings get GitHub's ids, so links like [раздел](#раздел) written for GitHub land in the same place here.
        var used = new java.util.HashMap<String, Integer>();
        for (var heading : page.select("h1, h2, h3, h4, h5, h6")) {
            if (heading.hasAttr("id")) continue;
            String slug = heading.text().toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s_-]", "").trim().replace(' ', '-');
            int seen = used.merge(slug, 1, Integer::sum);
            heading.attr("id", seen == 1 ? slug : slug + "-" + (seen - 1));
        }
        var document = new W3CDom().fromJsoup(page);
        var panel = new SelectablePanel();
        panel.getSharedContext().setReplacedElementFactory(new SvgElements(panel, vectors));
        for (var listener : panel.getMouseTrackingListeners()) if (listener instanceof LinkListener) panel.removeMouseTrackingListener(listener);
        panel.addMouseTrackingListener(new LinkListener() {
            @Override public void linkClicked(BasicPanel source, String uri) {
                if (!panel.selectedText().isEmpty()) return;
                try {
                    URI target = URI.create(base.resolve(uri).toString());
                    if (target.getFragment() != null && java.util.Objects.equals(target.getPath(), base.getPath()) && java.util.Objects.equals(target.getHost(), base.getHost())) { scrollTo(panel, target.getFragment()); return; }
                    openLink.accept(target);
                } catch (Exception ignored) {}
            }
        });
        panel.setDocument(document, base.toString());
        var scroll = new FSScrollPane(panel); scroll.setBorder(null); scroll.getVerticalScrollBar().setUnitIncrement(24);
        if (width > 0) {
            // The first layout is the slow part; done here it keeps the window responsive. Resizing later lays out again.
            int inner = Math.max(100, width - scroll.getVerticalScrollBar().getPreferredSize().width);
            scroll.setSize(width, 800); panel.setSize(inner, 800);
            var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
            try { panel.doDocumentLayout(g); } finally { g.dispose(); }
        }
        return scroll;
    }
    private static void scrollTo(SelectablePanel panel, String id) {
        var box = panel.getSharedContext().getBoxById(id);
        if (box != null) panel.scrollTo(new java.awt.Point(0, box.getAbsY()));
    }
    private static byte[] decode(String data) {
        int comma = data.indexOf(','); String head = data.substring(5, comma);
        return head.endsWith(";base64") ? Base64.getDecoder().decode(data.substring(comma + 1)) : java.net.URLDecoder.decode(data.substring(comma + 1), java.nio.charset.StandardCharsets.UTF_8).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static Path inline(String data) throws Exception {
        String head = data.substring(5, data.indexOf(','));
        Path file = Files.createTempFile("tethium-document-", "." + head.replaceAll("^image/([a-z0-9+]+).*$", "$1").replace("jpeg", "jpg"));
        file.toFile().deleteOnExit(); Files.write(file, decode(data)); return file;
    }
}

package dev.tethium.desktop;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import org.xhtmlrenderer.extend.ReplacedElement;
import org.xhtmlrenderer.extend.UserAgentCallback;
import org.xhtmlrenderer.layout.LayoutContext;
import org.xhtmlrenderer.render.BlockBox;
import org.xhtmlrenderer.swing.ImageReplacedElement;
import org.xhtmlrenderer.swing.RepaintListener;
import org.xhtmlrenderer.swing.SwingReplacedElementFactory;
import java.awt.*;
import java.awt.image.AbstractMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.List;

/**
 * SVG in documents drawn in Java stays vector: the renderer gets an image with a resolution variant for whatever scale it is
 * painted at, drawn from the SVG on demand, so charts are sharp on scaled screens instead of a stretched fixed-size bitmap.
 */
final class SvgElements extends SwingReplacedElementFactory {
    static final String ATTRIBUTE = "data-tethium-svg";
    private final Map<String, SVGDocument> documents;
    SvgElements(RepaintListener listener, Map<String, SVGDocument> documents) { super(listener); this.documents = documents; }

    static SVGDocument parse(byte[] bytes) {
        return new SVGLoader().load(new ByteArrayInputStream(bytes), null, LoaderContext.builder().build());
    }

    @Override public ReplacedElement createReplacedElement(LayoutContext context, BlockBox box, UserAgentCallback agent, int cssWidth, int cssHeight) {
        var element = box.getElement();
        if (element != null && element.hasAttribute(ATTRIBUTE)) {
            var svg = documents.get(element.getAttribute(ATTRIBUTE));
            if (svg != null) return new Element(svg, cssWidth, cssHeight);
        }
        return super.createReplacedElement(context, box, agent, cssWidth, cssHeight);
    }

    private static final class Element implements ImageReplacedElement {
        private final Vector image;
        private final Point location = new Point();
        Element(SVGDocument svg, int cssWidth, int cssHeight) {
            var size = svg.size(); double ratio = size.height <= 0 ? 1 : size.width / size.height;
            int width = cssWidth > 0 ? cssWidth : cssHeight > 0 ? (int) Math.round(cssHeight * ratio) : Math.round(size.width);
            int height = cssHeight > 0 ? cssHeight : (int) Math.round(width / Math.max(.01, ratio));
            image = new Vector(svg, Math.max(1, Math.min(8192, width)), Math.max(1, Math.min(8192, height)));
        }
        @Override public Image getImage() { return image; }
        @Override public int getIntrinsicWidth() { return image.width; }
        @Override public int getIntrinsicHeight() { return image.height; }
        @Override public Point getLocation() { return new Point(location); }
        @Override public void setLocation(int x, int y) { location.setLocation(x, y); }
    }

    /** Variants are drawn when first asked for and kept; a document is painted at one or two scales. */
    private static final class Vector extends AbstractMultiResolutionImage {
        private final SVGDocument svg;
        final int width, height;
        private final Map<Long, BufferedImage> variants = new LinkedHashMap<>();
        Vector(SVGDocument svg, int width, int height) { this.svg = svg; this.width = width; this.height = height; }
        @Override protected Image getBaseImage() { return variant(width, height); }
        @Override public Image getResolutionVariant(double destWidth, double destHeight) {
            return variant((int) Math.min(8192, Math.ceil(destWidth)), (int) Math.min(8192, Math.ceil(destHeight)));
        }
        @Override public List<Image> getResolutionVariants() { synchronized (variants) { return List.copyOf(variants.values()); } }
        private BufferedImage variant(int w, int h) {
            w = Math.max(1, w); h = Math.max(1, h); long key = ((long) w << 32) | h;
            synchronized (variants) {
                var cached = variants.get(key); if (cached != null) return cached;
                var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); var g = image.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    var size = svg.size(); g.scale(w / Math.max(1e-3, size.width), h / Math.max(1e-3, size.height));
                    svg.render(null, g);
                } finally { g.dispose(); }
                if (variants.size() > 6) variants.remove(variants.keySet().iterator().next());
                variants.put(key, image); return image;
            }
        }
    }
}

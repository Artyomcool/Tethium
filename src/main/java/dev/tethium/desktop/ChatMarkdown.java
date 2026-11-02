package dev.tethium.desktop;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.DefaultUrlSanitizer;
import org.commonmark.renderer.html.HtmlRenderer;

final class ChatMarkdown {
    private final java.util.List<org.commonmark.Extension> extensions=java.util.List.of(org.commonmark.ext.gfm.tables.TablesExtension.create());
    private final Parser parser = Parser.builder().extensions(extensions).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true)
            .urlSanitizer(new DefaultUrlSanitizer() {
                @Override public String sanitizeLinkUrl(String url) {
                    // Markdown treats C:/ as a URI scheme. In this desktop chat it is a local file link.
                    if (url.matches("(?i)^[a-z]:[/\\\\].*")) return url.replace('\\', '/');
                    if (url.regionMatches(true, 0, "file:/", 0, 6)) return url;
                    return super.sanitizeLinkUrl(url);
                }
            }).build();

    String render(String text) {
        return renderer.render(parser.parse(text)).replaceAll("<img[^>]*>", "<em>[изображение — откройте в результатах]</em>");
    }
}

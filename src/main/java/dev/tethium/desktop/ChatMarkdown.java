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

    /** Thoughts of the live answer are shown muted when on, dropped when off; action lines are always muted. */
    static volatile boolean showThoughts = true;
    String render(String text) {
        String html = renderer.render(parser.parse(text)).replaceAll("<img[^>]*>", "<em>[изображение — откройте в результатах]</em>");
        // A run of actions arrives as one paragraph with hard breaks; each becomes its own muted line without a marker.
        html = html.replaceAll("(?s)<p>— (.*?)</p>", "<p class='action'>$1</p>").replaceAll("<br />\\s*— ", "</p>\n<p class='action'>");
        var thought = java.util.regex.Pattern.compile("(?s)<pre><code class=\"language-thought\">(.*?)</code></pre>").matcher(html);
        var out = new StringBuilder();
        while (thought.find()) { String body = thought.group(1).strip(); thought.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(showThoughts && !body.isEmpty() ? "<div class='thought'>" + body.replace("\n", "<br>") + "</div>" : "")); }
        thought.appendTail(out);
        return out.toString();
    }
}

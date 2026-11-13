package dev.tethium.desktop;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.DefaultUrlSanitizer;
import org.commonmark.renderer.html.HtmlRenderer;

final class ChatMarkdown {
    private static final java.util.regex.Pattern VISUALIZE=java.util.regex.Pattern.compile("\\uE200visualize\\uE202(.*?)\\uE201",java.util.regex.Pattern.DOTALL);
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
        text=visualizeLinks(text);
        String html = renderer.render(parser.parse(text)).replaceAll("<img[^>]*>", "<em>[изображение — откройте в результатах]</em>");
        // A run of actions arrives as one paragraph with hard breaks; each becomes its own muted line without a marker.
        html = html.replaceAll("(?s)<p>—\u2063 (.*?)</p>", "<p class='action'>$1</p>").replaceAll("<br />\\s*—\u2063 ", "</p>\n<p class='action'>");
        var thought = java.util.regex.Pattern.compile("(?s)<pre><code class=\"language-thought\">(.*?)</code></pre>").matcher(html);
        var out = new StringBuilder();
        while (thought.find()) { String body = thought.group(1).strip(); thought.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(showThoughts && !body.isEmpty() ? "<div class='thought'>" + body.replace("\n", "<br>") + "</div>" : "")); }
        thought.appendTail(out);
        return out.toString();
    }
    /** Codex-native rich output markers have no renderer in Tethium; retain their published file as a normal chat link. */
    static String visualizeLinks(String text){
        var matcher=VISUALIZE.matcher(text);var out=new StringBuffer();
        while(matcher.find()){
            String replacement="Визуализация";
            try{var value=dev.tethium.json.Json.parse(matcher.group(1));String path=value.path("path").asText(),title=value.path("title").asText("Визуализация");if(!path.isBlank())replacement="["+label(title)+"]("+destination(path)+")";}catch(Exception ignored){}
            matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);return out.toString();
    }
    private static String label(String value){return value.replace("\\","\\\\").replace("[","\\[").replace("]","\\]");}
    private static String destination(String value){return value.replace('\\','/').replace("%","%25").replace(" ","%20").replace("(","%28").replace(")","%29").replace("<","%3C").replace(">","%3E");}
}

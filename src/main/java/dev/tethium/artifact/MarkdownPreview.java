package dev.tethium.artifact;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.Locale;

/** Markdown is a document: raw HTML is escaped, relative links stay in its preview bundle. */
public final class MarkdownPreview {
    private MarkdownPreview() {}
    public static boolean supports(Path path) { return path.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown)$"); }
    public static byte[] render(Path path) throws IOException {
        if (Files.size(path)>8_000_000) throw new IOException("Markdown-файл больше 8 МБ");
        var extensions=java.util.List.of(org.commonmark.ext.gfm.tables.TablesExtension.create());
        String html=HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build().render(Parser.builder().extensions(extensions).build().parse(Files.readString(path)));
        return ("<!doctype html><html lang=ru><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Markdown</title><style>"
            +"*{box-sizing:border-box}body{background:#111318;color:#edf0f7;font:16px/1.6 system-ui,sans-serif;max-width:960px;margin:auto;padding:24px}a{color:#b8adff}img{max-width:100%;height:auto}pre{background:#1c2028;padding:16px;border-radius:8px;overflow:auto}code{font-size:.9em}blockquote{border-left:3px solid #b8adff;margin-left:0;padding-left:16px;color:#a4adbf}h1,h2,h3{line-height:1.25}table{border-collapse:collapse;display:block;overflow:auto;max-width:100%;margin:16px 0}th,td{border:1px solid #3b424e;padding:8px 12px;text-align:left}th{background:#252b36}tr:nth-child(even){background:#191d25}hr{border:0;border-top:1px solid #333a48}</style>"+html+"</html>").getBytes(StandardCharsets.UTF_8);
    }
}

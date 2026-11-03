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
        String html=body(Files.readString(path),path);
        return document(html);
    }
    public static String body(String text){return body(text,null);}
    private static String body(String text,Path document){
        var extensions=java.util.List.of(org.commonmark.ext.gfm.tables.TablesExtension.create());
        var tree=Parser.builder().extensions(extensions).build().parse(text);
        if(document!=null)tree.accept(new org.commonmark.node.AbstractVisitor(){long embedded;
            @Override public void visit(org.commonmark.node.Image image){
                String link=image.getDestination();try{
                    if(link.matches("(?i)^(https?:|data:).*"))return;
                    Path file=link.startsWith("file:")?Path.of(java.net.URI.create(link)):Path.of(java.net.URLDecoder.decode(link.replace("+","%2B"),StandardCharsets.UTF_8));
                    if(!file.isAbsolute())file=document.toAbsolutePath().getParent().resolve(file);file=file.normalize();String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
                    String mime=name.endsWith(".svg")?"image/svg+xml":name.endsWith(".png")?"image/png":name.matches(".*\\.jpe?g$")?"image/jpeg":name.endsWith(".gif")?"image/gif":name.endsWith(".webp")?"image/webp":name.endsWith(".avif")?"image/avif":"";
                    if(!mime.isEmpty()&&Files.isRegularFile(file)&&Files.size(file)<=16_000_000&&embedded+Files.size(file)<=48_000_000){byte[] bytes=Files.readAllBytes(file);embedded+=bytes.length;image.setDestination("data:"+mime+";base64,"+java.util.Base64.getEncoder().encodeToString(bytes));}
                }catch(Exception ignored){}
            }
        });
        return HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).urlSanitizer(new org.commonmark.renderer.html.DefaultUrlSanitizer(){@Override public String sanitizeImageUrl(String url){return url.startsWith("data:image/")?url:super.sanitizeImageUrl(url);}}).build().render(tree);
    }
    public static byte[] document(String html){
        return ("<!doctype html><html lang=ru><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Markdown</title><style>"
            +"*{box-sizing:border-box}html{scrollbar-gutter:stable}body{background:#111318;color:#edf0f7;font:14px/1.5 system-ui,sans-serif;max-width:960px;margin:auto;padding:16px}a{color:#b8adff}img{max-width:100%;height:auto;display:block;margin:10px 0}pre{background:#1c2028;padding:16px;border-radius:8px;overflow:auto}code{font-size:.9em}blockquote{border-left:3px solid #b8adff;margin-left:0;padding-left:16px;color:#a4adbf}h1,h2,h3{line-height:1.25}table{border-collapse:collapse;display:block;overflow:auto;max-width:100%;margin:16px 0}th,td{border:1px solid #3b424e;padding:6px 10px;text-align:left;vertical-align:top}th{background:#252b36}tr:nth-child(even){background:#191d25}hr{border:0;border-top:1px solid #333a48}</style>"+html+"</html>").getBytes(StandardCharsets.UTF_8);
    }
}

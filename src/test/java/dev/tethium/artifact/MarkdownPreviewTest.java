package dev.tethium.artifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
class MarkdownPreviewTest {
    @TempDir Path root;
    @Test void localImagesSurviveDocumentBundlesAndEncodedPaths()throws Exception{
        Path image=Files.write(root.resolve("picture # one.png"),new byte[]{1,2,3});Path folder=Files.createDirectories(root.resolve("docs"));
        Path md=Files.writeString(folder.resolve("images.md"),"![relative](../picture%20%23%20one.png)\n\n![absolute]("+image.toUri()+")");
        String html=new String(MarkdownPreview.render(md),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(2,html.split("src=\"data:image/png;base64,AQID\"",-1).length-1);
        assertTrue(html.contains("max-width:100%"));
    }
    @Test void uppercaseMarkdownRendersInPreviewAndEscapesRawScripts()throws Exception{
        Path md=Files.writeString(root.resolve("Notes.MD"),"# Title\n\n**bold**\n\n| Name | Value |\n| --- | ---: |\n| **Alpha** | 42 |\n\n<script>alert(1)</script>\n\n[picture](image.png)");
        assertTrue(MarkdownPreview.supports(md));try(var server=new ArtifactServer(root);var client=HttpClient.newHttpClient()){
            var response=client.send(HttpRequest.newBuilder(server.uri("Notes.MD")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));assertTrue(response.body().contains("<h1>Title</h1>"));assertTrue(response.body().contains("<strong>bold</strong>"));assertTrue(response.body().contains("<table>"));assertTrue(response.body().contains("<strong>Alpha</strong>"));assertTrue(response.body().contains("align=\"right\""));assertFalse(response.body().contains("<script>"));assertTrue(response.body().contains("href=\"image.png\""));
        }
    }
}

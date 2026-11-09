package dev.tethium.host;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectPanelsTest {
    @TempDir Path root;
    private void config(Object value) throws Exception {
        Files.createDirectories(root.resolve(".tethium"));
        Files.writeString(root.resolve(".tethium/panel.json"), Json.MAPPER.writeValueAsString(value));
    }
    @Test void discoversProjectHtmlAndLoopbackServiceWithoutRunningThem() throws Exception {
        assertTrue(ProjectPanels.readManifest(root).isNull());
        config(Json.object("title","CAD panel","entry","ui/index.html"));
        Files.createDirectories(root.resolve(".tethium/ui"));
        Files.writeString(root.resolve(".tethium/ui/index.html"),"<canvas></canvas>");
        var file=ProjectPanels.readManifest(root);
        assertFalse(file.has("error"));assertEquals("CAD panel",file.path("title").asText());
        assertEquals(root.resolve(".tethium/ui/index.html").toRealPath().toString(),file.path("entry").asText());
        config(Json.object("url","http://127.0.0.1:17761/capability/index.html"));
        assertFalse(ProjectPanels.readManifest(root).has("error"));
        config(Json.object("disabled",true));assertTrue(ProjectPanels.readManifest(root).isNull());
    }
    @Test void invalidManifestStaysVisibleWithErrorAndCannotEscapeProject() throws Exception {
        for(String url:new String[]{"https://example.com:443/","http://127.0.0.1.evil:123/","http://user@localhost:123/","file:///C:/Windows/"}) {
            config(Json.object("url",url));assertTrue(ProjectPanels.readManifest(root).has("error"),url);
        }
        Path outside=Files.createTempFile(root.getParent(),"outside-",".html");
        try {config(Json.object("entry",outside.toString()));assertTrue(ProjectPanels.readManifest(root).has("error"));}
        finally {Files.delete(outside);}
        config(Json.object("entry","ui.html","url","http://127.0.0.1:1/"));assertTrue(ProjectPanels.readManifest(root).has("error"));
    }
}

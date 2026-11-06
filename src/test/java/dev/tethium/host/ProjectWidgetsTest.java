package dev.tethium.host;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class ProjectWidgetsTest {
    @TempDir Path root;
    @Test void projectOwnsBothIndependentSvgSlots()throws Exception{
        String svg="<svg xmlns='http://www.w3.org/2000/svg' width='120' height='16'><title>My metric</title><text x='0' y='12' fill='#fff'>21.58%</text></svg>";
        Files.createDirectories(root.resolve(".tethium"));Files.writeString(root.resolve(ProjectWidgets.BOTTOM),svg);
        var reader=new ProjectWidgets();var first=reader.read(root);assertFalse(first.path("bottom").path("png").asText().isEmpty());assertEquals(svg,first.path("bottom").path("svg").asText());assertEquals("My metric",first.path("bottom").path("description").asText());assertTrue(first.path("right").isNull());
        Files.writeString(root.resolve(ProjectWidgets.RIGHT),svg.replace("120","40").replace("16","40"));var next=reader.read(root);assertFalse(next.path("right").path("png").asText().isEmpty());assertNotEquals(next.path("bottom").path("version"),next.path("right").path("version"));
        assertThrows(Exception.class,()->ProjectWidgets.render("<svg width='20' height='20'><image href='file:///secret.png'/></svg>".getBytes(StandardCharsets.UTF_8),0));
        assertThrows(Exception.class,()->ProjectWidgets.render("<!DOCTYPE svg SYSTEM 'file:///secret'><svg/>".getBytes(StandardCharsets.UTF_8),0));
    }
}

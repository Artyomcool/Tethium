package dev.tethium.mobile;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AppCatalogTest {
    @TempDir Path root;
    @Test void independentProjectsAppearTogetherAndOneBrokenProjectDoesNotHideTheOthers()throws Exception{
        Path one=Files.createDirectory(root.resolve("one")),two=Files.createDirectory(root.resolve("two"));
        Files.writeString(one.resolve("game.apk"),"one");Files.writeString(two.resolve("game.apk"),"two");
        for(var dir:List.of(one,two))Json.MAPPER.writeValue(dir.resolve(".tethium-apps.json").toFile(),Json.object("apps",List.of(Json.object("packageName","our."+dir.getFileName(),"versionCode",1,"apk","game.apk"))));
        var projects=Json.MAPPER.valueToTree(List.of(Json.object("id","1","title","One","root",one.toString()),Json.object("id","2","title","Two","root",two.toString())));var catalog=new AppCatalog();
        var both=catalog.read(projects);assertEquals(2,both.path("apps").size());assertEquals("1",both.path("apps").get(0).path("projectId").asText());
        Files.writeString(one.resolve(".tethium-apps.json"),"invalid");var remaining=catalog.read(projects);assertEquals(1,remaining.path("apps").size());assertEquals("our.two",remaining.path("apps").get(0).path("packageName").asText());assertEquals(1,remaining.path("errors").size());
    }
    @Test void projectPublicationUpdatesAndCannotExposeExternalFiles()throws Exception{
        Path project=Files.createDirectory(root.resolve("project"));Path apk=Files.writeString(project.resolve("game.apk"),"release-one");
        var projects=Json.MAPPER.valueToTree(List.of(Json.object("id","p","title","Game","root",project.toString())));
        var catalog=new AppCatalog();var app=Json.object("packageName","our.game","title","Game","versionCode",1,"versionName","1.0","apk","game.apk");
        Json.MAPPER.writeValue(project.resolve(".tethium-apps.json").toFile(),Json.object("apps",List.of(app)));
        var first=catalog.read(projects).path("apps").get(0);assertEquals("our.game",first.path("packageName").asText());assertEquals(apk.toRealPath(),catalog.file(first.path("sha256").asText()));
        Files.writeString(apk,"release-two-more-bytes");var second=catalog.read(projects).path("apps").get(0);assertNotEquals(first.path("sha256"),second.path("sha256"));
        assertThrows(IllegalArgumentException.class,()->catalog.file(first.path("sha256").asText()));
        Files.writeString(root.resolve("private.apk"),"secret");app.put("apk","../private.apk");Json.MAPPER.writeValue(project.resolve(".tethium-apps.json").toFile(),Json.object("apps",List.of(app)));
        var denied=catalog.read(projects);assertTrue(denied.path("apps").isEmpty());assertEquals(1,denied.path("errors").size());
        assertThrows(IllegalArgumentException.class,()->catalog.file(second.path("sha256").asText()));
    }
}

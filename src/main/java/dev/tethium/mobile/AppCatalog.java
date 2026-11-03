package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import dev.tethium.remote.RemoteClient;
import java.nio.file.*;
import java.util.*;

/** Project build scripts publish .tethium-apps.json; only paired devices can fetch its APKs. */
final class AppCatalog {
    private record Release(Path file, long size, long modified, JsonNode info) {}
    private final Map<String,Release> releases = new HashMap<>();
    synchronized JsonNode read(JsonNode projects) {
        var apps=Json.MAPPER.createArrayNode(); var errors=Json.MAPPER.createArrayNode();
        var live=new HashSet<String>();
        for(var project:projects) {
            try {
                Path root=Path.of(project.path("root").asText()).toRealPath(), manifest=root.resolve(".tethium-apps.json");
                if(!Files.isRegularFile(manifest))continue;
                if(Files.size(manifest)>262144)throw new IllegalArgumentException("Каталог APK слишком большой");
                var list=Json.MAPPER.readTree(manifest.toFile()).path("apps");
                if(!list.isArray()||list.size()>50)throw new IllegalArgumentException("Нужен массив apps (до 50 приложений)");
                for(var app:list) {
                    String pkg=app.path("packageName").asText();
                    if(!pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")||pkg.equals("dev.tethium.mobile")||app.path("versionCode").asLong()<=0)throw new IllegalArgumentException("Некорректный пакет или versionCode");
                    Path file=root.resolve(app.path("apk").asText()).toRealPath();
                    if(!file.startsWith(root)||!file.toString().toLowerCase(Locale.ROOT).endsWith(".apk")||!Files.isRegularFile(file))throw new IllegalArgumentException("APK должен находиться в проекте");
                    long size=Files.size(file), modified=Files.getLastModifiedTime(file).toMillis();
                    if(size<=0||size>1_000_000_000L)throw new IllegalArgumentException("Нужен APK до 1 ГБ");
                    String key=project.path("id").asText()+":"+pkg;
                    var cached=releases.get(key);
                    String hash=cached!=null&&cached.file.equals(file)&&cached.size==size&&cached.modified==modified?cached.info.path("sha256").asText():RemoteClient.sha(file);
                    var info=Json.object("packageName",pkg,"title",app.path("title").asText(pkg),"project",project.path("title").asText(),"projectId",project.path("id").asText(),"versionCode",app.path("versionCode").asLong(),"versionName",app.path("versionName").asText(),"publishedAt",app.path("publishedAt").asLong(modified),"sha256",hash,"size",size,"path","/apps/"+hash+".apk");
                    releases.put(key,new Release(file,size,modified,info)); live.add(key); apps.add(info);
                }
            } catch(Exception error) { errors.add(Json.object("project",project.path("title").asText(),"error",Objects.toString(error.getMessage(),"Ошибка каталога"))); }
        }
        releases.keySet().retainAll(live);
        return Json.object("apps",apps,"errors",errors);
    }
    synchronized Path file(String hash) {
        return releases.values().stream().filter(r->r.info.path("sha256").asText().equals(hash)).map(Release::file).findFirst().orElseThrow(()->new IllegalArgumentException("Обновите каталог приложений"));
    }
}

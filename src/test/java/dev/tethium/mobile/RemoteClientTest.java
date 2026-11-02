package dev.tethium.mobile;

import dev.tethium.agent.*;
import dev.tethium.host.*;
import dev.tethium.remote.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.http.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.*;
import java.util.zip.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RemoteClientTest {
    @TempDir Path root;
    @Test void pinnedRemoteClientPreviewsDocumentsPreservesHostDraftAndRunsDownloadedJarLocally()throws Exception{
        Path project=Files.createDirectories(root.resolve("project")),clientData=Files.createDirectories(root.resolve("client"));Path md=Files.writeString(project.resolve("readme.MD"),"# Remote document\n\n**works**");
        Path source=Files.writeString(project.resolve("Hello.java"),"public class Hello { public static void main(String[] args) throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(\"ran-here.txt\"),\"ok\"); } }");assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,source.toString()));
        var manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class","Hello");Path jar=project.resolve("hello.jar");try(var out=new JarOutputStream(Files.newOutputStream(jar),manifest)){out.putNextEntry(new JarEntry("Hello.class"));Files.copy(project.resolve("Hello.class"),out);out.closeEntry();}
        var adapter=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> e,Cancellation c){throw new AssertionError("No local or remote model calls");}};
        try(var host=new HostService(root.resolve("host"),adapter);var local=new HostClient(host.descriptor());var gateway=new MobileGateway(root.resolve("host"),new HostClient(host.descriptor()),0,0)){
            String p=local.post("/projects",Json.object("path",project.toString())).path("id").asText(),s=local.post("/sessions",Json.object("projectId",p)).path("id").asText();local.post("/sessions/"+s+"/stop",Json.object());local.post("/sessions/"+s+"/draft",Json.object("draft","Laptop draft","images",List.of()));
            local.post("/sessions/"+s+"/artifact",Json.object("path",jar.toString(),"title","Build"));local.post("/sessions/"+s+"/artifact",Json.object("path",md.toString(),"title","Markdown"));
            var pairing=PinnedConnection.pairing(gateway.pairInfo().path("pairing").asText());String address="127.0.0.1:"+gateway.port();var connection=new PinnedConnection(address,gateway.pin(),null);String token=connection.json("/pair",Json.object("code",pairing.get("code"))).path("token").asText();
            assertThrows(Exception.class,()->new PinnedConnection(address,"00".repeat(32),token).json("/state",null));
            try(var remote=new RemoteClient(clientData,Json.object("address",address,"pin",gateway.pin(),"token",token));var http=HttpClient.newHttpClient()){
                remote.get("/state?session="+s);remote.post("/sessions/"+s+"/draft",Json.object("draft","Remote draft","images",List.of()));assertEquals("Laptop draft",local.get("/state?session="+s).path("sessions").get(0).path("preferences").path("draft").asText());assertEquals("Remote draft",remote.get("/state?session="+s).path("sessions").get(0).path("preferences").path("draft").asText());
                var response=http.send(HttpRequest.newBuilder(remote.preview(md)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertTrue(response.body().contains("<h1>Remote document</h1>"));
                Path received=remote.download(jar);assertTrue(received.startsWith(clientData));assertEquals(RemoteClient.sha(jar),RemoteClient.sha(received));var process=ArtifactLauncher.launch(received);assertTrue(process.waitFor(8,TimeUnit.SECONDS));assertEquals(0,process.exitValue());assertTrue(Files.isRegularFile(received.getParent().resolve("ran-here.txt")));assertFalse(Files.exists(project.resolve("ran-here.txt")));
                remote.post("/sessions/"+s+"/run",Json.object("prompt","queued","images",List.of(),"requestId","remote-test"));assertEquals("Laptop draft",local.get("/state?session="+s).path("sessions").get(0).path("preferences").path("draft").asText());assertThrows(HostClient.ApiException.class,()->remote.post("/shutdown",Json.object()));
            }
        }
    }
    @Test void archiveCannotWriteOutsideItsDownloadFolder()throws Exception{
        Path zip=root.resolve("bad.zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))){out.putNextEntry(new ZipEntry("../escape.jar"));out.write(new byte[]{1});out.closeEntry();}assertThrows(java.io.IOException.class,()->ArtifactLauncher.entries(zip));assertFalse(Files.exists(root.resolve("escape.jar")));
    }
}

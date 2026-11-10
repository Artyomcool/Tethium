package dev.tethium.mobile;

import dev.tethium.agent.*;
import dev.tethium.host.*;
import dev.tethium.files.FileManager;
import dev.tethium.json.Json;
import dev.tethium.remote.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class FileManagerTest {
    @TempDir Path temp;
    private static final AgentAdapter NONE=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> e,Cancellation c){throw new AssertionError("No agents in file transfers");}};
    private String token(MobileGateway gateway)throws Exception{
        String qr=gateway.pairInfo().path("pairing").asText(),code=FileManager.query(URI.create(qr),"code");
        return new LanConnection("127.0.0.1:"+gateway.httpPort(),null).json("/pair",Json.object("code",code,"name","file test")).path("token").asText();
    }
    @Test void phoneAndRemoteDesktopResumeAfterCoreRestartAndDownloadIdenticalBytes()throws Exception{
        Path data=temp.resolve("data"),project=Files.createDirectories(temp.resolve("project")),source=temp.resolve("phone.bin");byte[] bytes=new byte[2*FileManager.CHUNK+123];new Random(7).nextBytes(bytes);Files.write(source,bytes);
        String root,device;AtomicBoolean cancelled=new AtomicBoolean();var progress=new ArrayList<Long>();
        try(var host=new HostService(data,NONE);var local=new HostClient(host.descriptor());var gateway=new MobileGateway(data,host.feed(),0,0)){
            String p=local.post("/projects",Json.object("path",project.toString())).path("id").asText();root="project-"+p;device=token(gateway);
            try(var remote=new RemoteClient(temp.resolve("client"),Json.object("address","127.0.0.1:"+gateway.httpPort(),"token",device))){
                assertTrue(remote.get("/file-manager/roots").path("roots").size()>=2);
                assertThrows(InterruptedIOException.class,()->new FileTransfers(remote,cancelled).upload(source,root,"phone.bin","",(n,total)->{if(n>=FileManager.CHUNK)cancelled.set(true);}));
                assertFalse(Files.exists(project.resolve("phone.bin")),"an interrupted upload never publishes an incomplete target");assertEquals(false,local.get("/health").path("draining").asBoolean());
            }
        }
        try(var host=new HostService(data,NONE);var local=new HostClient(host.descriptor());var gateway=new MobileGateway(data,host.feed(),0,0);var remote=new RemoteClient(temp.resolve("client"),Json.object("address","127.0.0.1:"+gateway.httpPort(),"token",device))){
            var transfers=new FileTransfers(remote,new AtomicBoolean());transfers.upload(source,root,"phone.bin","",(n,total)->progress.add(n));assertTrue(progress.getFirst()>=FileManager.CHUNK,"continues from durable offset");assertArrayEquals(bytes,Files.readAllBytes(project.resolve("phone.bin")));
            var download=remote.post("/file-manager/download",Json.object("root",root,"path","phone.bin"));Path saved=temp.resolve("second-laptop.bin");transfers.download(download,saved,false,(n,total)->{});assertArrayEquals(bytes,Files.readAllBytes(saved));
            var unauth=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+gateway.httpPort()+"/file-manager/download/"+download.path("id").asText())).build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,unauth.statusCode());
            assertThrows(HostClient.ApiException.class,()->remote.post("/file-manager/share",Json.object("path",temp.toString())));
            Path fromHost=Files.writeString(project.resolve("host.txt"),"с рабочего ноутбука");var info=local.post("/file-manager/download",Json.object("root",root,"path","host.txt"));new FileTransfers(local,new AtomicBoolean()).download(info,temp.resolve("local-copy.txt"),false,(n,total)->{});assertEquals(Files.readString(fromHost),Files.readString(temp.resolve("local-copy.txt")));
        }
    }
    @Test void refusesEscapesAndUnconfirmedOverwriteAndDetectsConcurrentEdits()throws Exception{
        Path project=Files.createDirectories(temp.resolve("shared")),outside=Files.writeString(temp.resolve("outside.txt"),"private");
        var manager=new FileManager(temp.resolve("data"),()->Json.MAPPER.createArrayNode().add(Json.object("id","p","title","project","root",project.toString())));
        assertThrows(IOException.class,()->manager.api("GET",URI.create("/file-manager/list?root=project-p&path=.."),null));
        assertThrows(IOException.class,()->manager.api("POST",URI.create("/file-manager/mkdir"),Json.object("root","project-p","path","","name","../other")));
        Path file=Files.writeString(project.resolve("keep.txt"),"original");var entry=manager.api("GET",URI.create("/file-manager/list?root=project-p"),null).path("entries").get(0);
        assertThrows(IOException.class,()->manager.api("POST",URI.create("/file-manager/upload"),Json.object("id",UUID.randomUUID().toString(),"root","project-p","path","keep.txt","size",1,"sha256","0".repeat(64))));
        assertThrows(IOException.class,()->manager.api("POST",URI.create("/file-manager/delete"),Json.object("root","project-p","path","keep.txt","revision",entry.path("revision").asText())));
        Files.writeString(file,"new content");assertThrows(IOException.class,()->manager.api("POST",URI.create("/file-manager/rename"),Json.object("root","project-p","path","keep.txt","name","other.txt","revision",entry.path("revision").asText())));
        assertEquals("new content",Files.readString(file));assertEquals("private",Files.readString(outside));
    }
    @Test void verifiesUploadHashAndKeepsNewerDestinationWhenReplacementWasAlreadyApproved()throws Exception{
        Path data=temp.resolve("data"),project=Files.createDirectories(temp.resolve("project")),source=Files.writeString(temp.resolve("source.txt"),"replacement"),target=Files.writeString(project.resolve("target.txt"),"old");
        try(var h=new HostService(data,NONE);var local=new HostClient(h.descriptor())){
            String root="project-"+local.post("/projects",Json.object("path",project.toString())).path("id").asText();String revision=local.get("/file-manager/list?root="+root).path("entries").get(0).path("revision").asText();
            assertThrows(HostClient.ApiException.class,()->new FileTransfers(local,new AtomicBoolean()).upload(source,root,"target.txt",revision,(n,total)->{if(n==total&&n>0)try{Files.writeString(target,"an agent saved a newer version");}catch(Exception e){throw new RuntimeException(e);}}));
            assertEquals("an agent saved a newer version",Files.readString(target));
            var body=Json.object("id",UUID.randomUUID().toString(),"root",root,"path","bad.txt","size",0,"sha256","0".repeat(64));local.post("/file-manager/upload",body);
            assertThrows(HostClient.ApiException.class,()->local.post("/file-manager/upload/"+body.path("id").asText()+"/finish",Json.object()));assertFalse(Files.exists(project.resolve("bad.txt")));
            Path empty=Files.createFile(temp.resolve("empty"));new FileTransfers(local,new AtomicBoolean()).upload(empty,root,"empty","",(n,total)->{});assertEquals(0,Files.size(project.resolve("empty")));
        }
    }
    @Test void transfersDirectoryTreesAndDownloadsFoldersAsZip()throws Exception{
        Path project=Files.createDirectories(temp.resolve("project")),source=Files.createDirectories(temp.resolve("folder"));Files.createDirectories(source.resolve("nested/empty"));Files.writeString(source.resolve("nested/текст.md"),"# Привет");Files.writeString(source.resolve("readme.txt"),"files");
        try(var h=new HostService(temp.resolve("data"),NONE);var local=new HostClient(h.descriptor())){
            String root="project-"+local.post("/projects",Json.object("path",project.toString())).path("id").asText();var transfers=new FileTransfers(local,new AtomicBoolean());transfers.uploadDirectory(source,root,"folder",false,(n,total)->{});
            assertEquals("# Привет",Files.readString(project.resolve("folder/nested/текст.md")));assertTrue(Files.isDirectory(project.resolve("folder/nested/empty")));
            assertThrows(IOException.class,()->transfers.uploadDirectory(source,root,"folder",false,(n,total)->{}));
            var info=local.post("/file-manager/download",Json.object("root",root,"path","folder"));assertTrue(info.path("archive").asBoolean());Path zip=temp.resolve("folder.zip");transfers.download(info,zip,false,(n,total)->{});
            try(var archive=new java.util.zip.ZipFile(zip.toFile())){assertNotNull(archive.getEntry("nested/empty/"));try(var in=archive.getInputStream(archive.getEntry("nested/текст.md"))){assertEquals("# Привет",new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}}
            assertFalse(Files.exists(temp.resolve("data/file-manager/downloads/"+info.path("id").asText()+".zip")),"completed snapshot is released");
        }
    }
}

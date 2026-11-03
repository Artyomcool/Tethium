package dev.tethium.mobile;

import dev.tethium.agent.*;
import dev.tethium.host.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class MobileGatewayTest {
    @TempDir Path root;
    private static final AgentAdapter ADAPTER=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> events,Cancellation c){throw new AssertionError("No model calls");}};
    @Test void appCatalogRequiresPairingAndServesOnlyPublishedApksAndQueuePause()throws Exception {
        Path project=Files.createDirectories(root.resolve("game"));Files.writeString(project.resolve("game.apk"),"signed-apk-fixture");
        Json.MAPPER.writeValue(project.resolve(".tethium-apps.json").toFile(),Json.object("apps",List.of(Json.object("packageName","our.game","title","Game","versionCode",1,"versionName","1.0","apk","game.apk"))));
        try(var h=new HostService(root.resolve("data"),ADAPTER);var local=new HostClient(h.descriptor());var m=new MobileGateway(root.resolve("data"),new HostClient(h.descriptor()),0,0);var client=client()){
            String projectId=local.post("/projects",Json.object("path",project.toString())).path("id").asText();
            String session=local.post("/sessions",Json.object("projectId",projectId)).path("id").asText();
            assertEquals(401,call(client,m,"/apps",null,null).statusCode());
            String code=URI.create(m.pairInfo().path("pairing").asText()).getQuery().split("code=")[1];String token=Json.parse(call(client,m,"/pair",Json.object("code",code),null).body()).path("token").asText();
            var response=call(client,m,"/apps",null,token);assertEquals(200,response.statusCode(),response.body());
            var apps=Json.parse(response.body()).path("apps");assertEquals("dev.tethium.mobile",apps.get(0).path("packageName").asText());
            String path=apps.get(1).path("path").asText();assertEquals(401,call(client,m,path,null,null).statusCode());
            assertEquals("signed-apk-fixture",call(client,m,path,null,token).body());assertNotEquals(200,call(client,m,"/apps/"+"0".repeat(64)+".apk",null,token).statusCode());
            assertEquals(200,call(client,m,"/sessions/"+session+"/queue-pause",Json.object(),token).statusCode());
            assertTrue(local.get("/state").path("sessions").get(0).path("queuePaused").asBoolean());
            assertEquals(200,call(client,m,"/sessions/"+session+"/queue-resume",Json.object(),token).statusCode());
            assertFalse(local.get("/state").path("sessions").get(0).path("queuePaused").asBoolean());
        }
    }
    @Test void pairingAuthenticationRevocationAndNarrowApiSurviveRestart()throws Exception{
        String token,pin;
        try(var h=new HostService(root,ADAPTER);var local=new HostClient(h.descriptor());var mobile=new MobileGateway(root,new HostClient(h.descriptor()),0,0);var client=client()){
            pin=mobile.pin();var info=mobile.pairInfo();String code=URI.create(info.path("pairing").asText()).getQuery().split("code=")[1];
            assertEquals(401,call(client,mobile,"/state",null,null).statusCode());
            assertEquals(401,call(client,mobile,"/pair",Json.object("code","wrong"),null).statusCode());
            var response=call(client,mobile,"/pair",Json.object("code",code,"name","probe"),null);assertEquals(200,response.statusCode());token=Json.MAPPER.readTree(response.body()).path("token").asText();
            assertEquals(401,call(client,mobile,"/pair",Json.object("code",code),null).statusCode());
            assertEquals(200,call(client,mobile,"/state",null,token).statusCode());
            for(String path:List.of("/shutdown","/updates/shutdown","/mobile/pair","/imports/conversation","/projects"))assertEquals(404,call(client,mobile,path,Json.object(),token).statusCode(),path);
            var req=HttpRequest.newBuilder(URI.create("https://localhost:"+mobile.port()+"/state")).header("Authorization","Bearer "+token).header("Origin","https://evil.invalid").GET().build();assertEquals(401,client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200,local.get("/health").has("version")?200:0);
        }
        try(var h=new HostService(root,ADAPTER);var m=new MobileGateway(root,new HostClient(h.descriptor()),0,0);var client=client()){
            assertEquals(pin,m.pin());assertEquals(200,call(client,m,"/state",null,token).statusCode());m.revoke();assertEquals(401,call(client,m,"/state",null,token).statusCode());
        }
    }
    @Test void previewOnlyServesPublishedBundleAndDownloadNeverExposesApi()throws Exception{
        Path bundle=Files.createDirectories(root.resolve("bundle"));Path page=Files.writeString(bundle.resolve("index.html"),"<h1>preview</h1>");Files.writeString(root.resolve("secret.txt"),"PRIVATE");
        try(var h=new HostService(root,ADAPTER);var local=new HostClient(h.descriptor());var m=new MobileGateway(root,new HostClient(h.descriptor()),0,0);var client=client()){
            String p=local.post("/projects",Json.object("path",root.toString())).path("id").asText();String s=local.post("/sessions",Json.object("projectId",p)).path("id").asText();
            String artifact=local.post("/sessions/"+s+"/artifact",Json.object("path",page.toString(),"title","preview")).path("id").asText();
            String code=URI.create(m.pairInfo().path("pairing").asText()).getQuery().split("code=")[1];String t=Json.MAPPER.readTree(call(client,m,"/pair",Json.object("code",code),null).body()).path("token").asText();
            assertEquals(400,call(client,m,"/sessions/"+s+"/run",Json.object("prompt","read","images",List.of(root.resolve("secret.txt").toString())),t).statusCode());
            String path=Json.MAPPER.readTree(call(client,m,"/preview",Json.object("session",s,"artifact",artifact),t).body()).path("path").asText();
            assertEquals("<h1>preview</h1>",call(client,m,path,null,null).body());
            assertNotEquals(200,call(client,m,path.replace("index.html","%2e%2e/secret.txt"),null,null).statusCode());
            var http=HttpRequest.newBuilder(URI.create("http://localhost:"+m.downloadPort()+"/state")).GET().build();assertEquals(404,client.send(http,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void pinnedDocumentCanBeOpenedRemotelyWithoutAnAgentSession()throws Exception{
        Path project=Files.createDirectories(root.resolve("project"));Files.writeString(project.resolve("readme.md"),"| A | B |\n|---|---|\n| one | two |\n");
        try(var h=new HostService(root.resolve("data"),ADAPTER);var local=new HostClient(h.descriptor());var m=new MobileGateway(root.resolve("data"),new HostClient(h.descriptor()),0,0);var client=client()){
            String p=local.post("/projects",Json.object("path",project.toString())).path("id").asText();
            String code=URI.create(m.pairInfo().path("pairing").asText()).getQuery().split("code=")[1];String t=Json.MAPPER.readTree(call(client,m,"/pair",Json.object("code",code),null).body()).path("token").asText();
            var pin=call(client,m,"/projects/"+p+"/files",Json.object("path","readme.md"),t);assertEquals(200,pin.statusCode(),pin.body());String id=Json.parse(pin.body()).path("id").asText();
            var preview=call(client,m,"/preview",Json.object("fileId",id),t);assertEquals(200,preview.statusCode(),preview.body());
            String rendered=call(client,m,Json.parse(preview.body()).path("path").asText(),null,null).body();assertTrue(rendered.contains("<table>"));assertTrue(local.get("/state").path("sessions").isEmpty());
            assertEquals(200,call(client,m,"/files/"+id+"/remove",Json.object(),t).statusCode());assertNotEquals(200,call(client,m,"/preview",Json.object("fileId",id),t).statusCode());
        }
    }
    @Test void retryingQueuedSubmissionDoesNotAppendItTwiceEvenAfterRestart()throws Exception{
        String id,job;
        try(var h=new HostService(root,ADAPTER);var c=new HostClient(h.descriptor())){
            String p=c.post("/projects",Json.object("path",root.toString())).path("id").asText();id=c.post("/sessions",Json.object("projectId",p)).path("id").asText();
            c.post("/sessions/"+id+"/stop",Json.object());
            job=c.post("/sessions/"+id+"/run",Json.object("prompt","old","requestId","first")).path("jobId").asText();
            var request=Json.object("prompt","new","requestId","second");assertEquals(job,c.post("/sessions/"+id+"/run",request).path("jobId").asText());c.post("/sessions/"+id+"/run",request);
            assertEquals(2,c.get("/state?session="+id).path("conversation").path("queued").get(0).path("partsCount").asInt());
        }
        try(var h=new HostService(root,ADAPTER);var c=new HostClient(h.descriptor())){
            assertEquals(job,c.post("/sessions/"+id+"/run",Json.object("prompt","new","requestId","second")).path("jobId").asText());
            assertEquals("old\n\n---\n\nnew",c.get("/state?session="+id).path("conversation").path("queued").get(0).path("prompt").asText());
        }
    }
    @Test void uploadedImagesAndQueueReturnAreRetryableAndPreserveDesktopDraft()throws Exception{
        try(var h=new HostService(root,ADAPTER);var local=new HostClient(h.descriptor());var m=new MobileGateway(root,new HostClient(h.descriptor()),0,0);var client=client()){
            String p=local.post("/projects",Json.object("path",root.toString())).path("id").asText();String s=local.post("/sessions",Json.object("projectId",p)).path("id").asText();
            local.post("/sessions/"+s+"/stop",Json.object());local.post("/sessions/"+s+"/draft",Json.object("draft","laptop draft"));
            String code=URI.create(m.pairInfo().path("pairing").asText()).getQuery().split("code=")[1];String token=Json.MAPPER.readTree(call(client,m,"/pair",Json.object("code",code),null).body()).path("token").asText();
            var image=new java.awt.image.BufferedImage(32,24,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
            var body=Json.object("data",Base64.getEncoder().encodeToString(bytes.toByteArray()));
            assertEquals(401,call(client,m,"/attachment",body,null).statusCode());
            var uploaded=call(client,m,"/attachment",body,token);assertEquals(200,uploaded.statusCode());String attachment=Json.MAPPER.readTree(uploaded.body()).path("id").asText();
            assertEquals(attachment,Json.MAPPER.readTree(call(client,m,"/attachment",body,token).body()).path("id").asText());
            assertEquals(400,call(client,m,"/attachment",Json.object("data",Base64.getEncoder().encodeToString("not PNG".getBytes())),token).statusCode());
            assertEquals(400,call(client,m,"/sessions/"+s+"/run",Json.object("prompt","bad","attachments",List.of("../secret")),token).statusCode());
            var send=Json.object("prompt","look","attachments",List.of(attachment),"requestId","image-once");
            assertEquals(200,call(client,m,"/sessions/"+s+"/run",send,token).statusCode());assertEquals(200,call(client,m,"/sessions/"+s+"/run",send,token).statusCode());
            var state=local.get("/state?session="+s);var job=state.path("conversation").path("queued").get(0);assertEquals(1,job.path("partsCount").asInt());assertTrue(job.path("prompt").asText().contains("[Скриншот"));
            assertEquals("laptop draft",state.path("sessions").get(0).path("preferences").path("draft").asText());
            var take=Json.object("jobId",job.path("id").asText(),"expectedPrompt",job.path("prompt").asText(),"draft","phone draft","requestId","take-once");
            var returned=call(client,m,"/sessions/"+s+"/queue-take",take,token);assertEquals(200,returned.statusCode());assertTrue(Json.MAPPER.readTree(returned.body()).path("draft").asText().startsWith("phone draft"));
            assertEquals(returned.body(),call(client,m,"/sessions/"+s+"/queue-take",take,token).body());
            state=local.get("/state?session="+s);assertEquals(0,state.path("conversation").path("queued").size());assertEquals("laptop draft",state.path("sessions").get(0).path("preferences").path("draft").asText());
        }
    }
    @Test void mobilePriorityCorrectionKeepsTheLaptopDraft()throws Exception{
        try(var store=new dev.tethium.core.SessionStore(root.resolve("isolated.db"))){
            String project=store.project(root);var session=store.create(project,null,"phone",root);
            store.draft(session.id(),"laptop work in progress");
            var first=store.priorityCorrection(session.id(),List.of(session.id()),"urgent from phone","mobile-urgent",false);
            assertEquals(first,store.priorityCorrection(session.id(),List.of(session.id()),"urgent from phone","mobile-urgent",false));
            assertEquals("laptop work in progress",store.preferences(session.id()).path("draft").asText());
            assertEquals(1,store.queued(session.id()).size());
        }
    }
    private static HttpClient client()throws Exception{
        // Test client only. Production Android validates the certificate fingerprint from the QR.
        var tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{new X509TrustManager(){public X509Certificate[]getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[]c,String a){}public void checkServerTrusted(X509Certificate[]c,String a){}}},null);
        return HttpClient.newBuilder().sslContext(tls).build();
    }
    private static HttpResponse<String> call(HttpClient c,MobileGateway m,String path,Object body,String token)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("https://localhost:"+m.port()+path));if(token!=null)r.header("Authorization","Bearer "+token);if(body==null)r.GET();else r.POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)));return c.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
}

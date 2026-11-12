package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.*;
import dev.tethium.host.*;
import dev.tethium.json.Json;
import dev.tethium.link.Frames;
import dev.tethium.link.Tbin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class MobileGatewayTest {
    @TempDir Path root;
    private static final AgentAdapter ADAPTER=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> events,Cancellation c){throw new AssertionError("No model calls");}};
    @Test void pairedPhoneReordersProjectsAndSessionsAndReceivesOrderOnlyPushes()throws Exception{
        Path data=root.resolve("data");
        try(var h=new HostService(data,ADAPTER);var local=new HostClient(h.descriptor());var m=new MobileGateway(data,h.feed(),0,0)){
            String p1=local.post("/projects",Json.object("path",root.resolve("one").toString())).path("id").asText();
            String p2=local.post("/projects",Json.object("path",root.resolve("two").toString())).path("id").asText();
            String a=local.post("/sessions",Json.object("projectId",p1,"title","A")).path("id").asText();
            String b=local.post("/sessions",Json.object("projectId",p1,"title","B")).path("id").asText();
            String other=local.post("/sessions",Json.object("projectId",p2,"title","Other")).path("id").asText();
            String token=pairOverLink(m);
            try(var phone=hello(m,token)){
                var full=phone.expect(Frames.STATE,f->f.path("full").asBoolean());assertEquals(Json.MAPPER.valueToTree(List.of(a,b,other)),full.path("sessionOrder"));
                assertEquals(200,phone.request("/projects/order",Json.object("ids",List.of(p2,p1))).path("status").asInt());
                phone.expect(Frames.STATE,f->f.has("projects")&&f.path("projects").get(0).path("id").asText().equals(p2));
                assertEquals(p2,local.get("/state").path("projects").get(0).path("id").asText());
                assertEquals(200,phone.request("/projects/"+p1+"/sessions-order",Json.object("ids",List.of(b,a))).path("status").asInt());
                var delta=phone.expect(Frames.STATE,f->f.path("sessionOrder").equals(Json.MAPPER.valueToTree(List.of(other,b,a))));
                assertFalse(delta.has("sessions"),"Reordering must not resend unchanged session objects");
                assertEquals(b,local.get("/state").path("sessions").get(1).path("id").asText());
                assertEquals(400,phone.request("/projects/"+p1+"/sessions-order",Json.object("ids",List.of(other))).path("status").asInt());
                local.post("/projects/"+p1+"/sessions-order",Json.object("ids",List.of(a,b)));
                phone.expect(Frames.STATE,f->f.path("sessionOrder").equals(Json.MAPPER.valueToTree(List.of(other,a,b))));
            }
            try(var phone=hello(m,token)){
                var full=phone.expect(Frames.STATE,f->f.path("full").asBoolean());assertEquals(Json.MAPPER.valueToTree(List.of(other,a,b)),full.path("sessionOrder"));
                assertEquals(other,full.path("sessions").get(0).path("id").asText());
            }
        }
    }
    @Test void uacStatusIsPushedToPairedClientsAndActionsAreNotHostCommands()throws Exception{
        try(var h=new HostService(root,ADAPTER);var local=new HostClient(h.descriptor());var m=new MobileGateway(root,h.feed(),0,0)){
            var unauthorized=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+m.httpPort()+"/uac")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,unauthorized.statusCode());
            try(var phone=hello(m,pairOverLink(m))){
                var state=phone.expect(Frames.STATE,f->f.path("full").asBoolean());assertTrue(state.path("uac").has("available"));assertFalse(state.path("uac").has("token"));
                assertEquals(200,phone.request("/uac",null).path("status").asInt());
                assertEquals(400,phone.request("/uac/answer",Json.object("id","expired","action","unknown")).path("status").asInt());
            }
            assertThrows(HostClient.ApiException.class,()->local.post("/uac/answer",Json.object("id","expired","action","unknown")));
        }
    }
    @Test void pairedPhoneCreatesProjectAndInitialSessionWithoutStartingAnAgent()throws Exception{
        Path data=root.resolve("data"),directory=root.resolve("new-project");
        try(var h=new HostService(data,ADAPTER);var m=new MobileGateway(data,h.feed(),0,0);var local=new HostClient(h.descriptor())){
            String token=pairOverLink(m);var phone=new dev.tethium.remote.LanConnection("127.0.0.1:"+m.httpPort(),token);
            var body=Json.object("path",directory.toString(),"initialSession","Разработка");
            var made=phone.json("/projects",body);assertFalse(made.path("id").asText().isBlank());assertFalse(made.path("sessionId").asText().isBlank());assertTrue(Files.isDirectory(directory));
            assertEquals(made,phone.json("/projects",body),"retry reuses the project and initial session");
            var state=local.get("/state");assertEquals(1,state.path("projects").size());assertEquals(1,state.path("sessions").size());assertEquals("Разработка",state.path("sessions").get(0).path("title").asText());assertFalse(state.path("sessions").get(0).path("running").asBoolean());
            assertThrows(HostClient.ApiException.class,()->phone.json("/projects",Json.object("path"," ")));
            var plain=phone.json("/projects",Json.object("path",root.resolve("without-session").toString()));assertTrue(plain.path("sessionId").isNull());
        }
    }
    /** Streams a few words, then waits for Stop: enough to observe live text and its completion over the link. */
    private static final AgentAdapter STREAMING=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> events,Cancellation cancel)throws Exception{
        events.accept(new Event("text","Работаю",Json.object()));Thread.sleep(150);events.accept(new Event("text"," над задачей…",Json.object()));
        while(!cancel.isCancelled())Thread.sleep(20);return new Result("probe",Status.CANCELLED,"Работаю над задачей…");
    }};

    /** Minimal phone-side client for tests: same framing and encoding as the Android app. */
    private static final class LinkClient implements AutoCloseable {
        final Socket socket;final DataInputStream in;final DataOutputStream out;final Tbin.Writer writer=new Tbin.Writer();final Tbin.Reader reader=new Tbin.Reader();final Deque<Map.Entry<Integer,JsonNode>> frames=new ArrayDeque<>();int ids,pings;
        LinkClient(int port)throws IOException{socket=new Socket("127.0.0.1",port);socket.setSoTimeout(15_000);in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));}
        void send(int kind,JsonNode body)throws IOException{Frames.write(out,writer.encode(kind,body));}
        Map.Entry<Integer,JsonNode> read()throws IOException{byte[] raw=Frames.read(in);return Map.entry(raw[0]&255,reader.decode(raw,1));}
        /** Waits for the first frame of a kind matching the predicate; other frames stay in order for later checks. */
        JsonNode expect(int kind,Predicate<JsonNode> test)throws IOException{
            for(var it=frames.iterator();it.hasNext();){var f=it.next();if(f.getKey()==kind&&test.test(f.getValue())){it.remove();return f.getValue();}}
            long until=System.currentTimeMillis()+15_000;
            while(System.currentTimeMillis()<until){var f=read();if(f.getKey()==kind&&test.test(f.getValue()))return f.getValue();if(f.getKey()==Frames.PING)pings++;else frames.addLast(f);}
            throw new AssertionError("No frame of kind "+kind);
        }
        JsonNode request(String path,JsonNode body)throws IOException{int id=++ids;var frame=Json.object("id",id,"path",path,"post",body!=null);if(body!=null)frame.set("body",body);send(Frames.REQUEST,frame);return expect(Frames.RESPONSE,r->r.path("id").asInt()==id);}
        public void close()throws IOException{socket.close();}
    }
    private static String code(MobileGateway m)throws Exception{return URI.create(m.pairInfo().path("pairing").asText()).getQuery().split("code=")[1];}
    private static String pairOverLink(MobileGateway m)throws Exception{
        try(var c=new LinkClient(m.port())){c.send(Frames.PAIR,Json.object("protocol",Frames.PROTOCOL,"code",code(m),"name","test phone"));var reply=c.read();assertEquals(Frames.PAIRED,reply.getKey(),reply.getValue().toString());assertEquals(m.httpPort(),reply.getValue().path("httpPort").asInt());return reply.getValue().path("token").asText();}
    }
    private static LinkClient hello(MobileGateway m,String token)throws Exception{var c=new LinkClient(m.port());c.send(Frames.HELLO,Json.object("protocol",Frames.PROTOCOL,"token",token,"name","test phone"));var reply=c.read();assertEquals(Frames.WELCOME,reply.getKey(),reply.getValue().toString());return c;}

    @Test void linkPushesCatalogConversationAndLiveTextWithoutPolling()throws Exception{
        try(var h=new HostService(root,STREAMING);var local=new HostClient(h.descriptor());var m=new MobileGateway(root,h.feed(),0,0)){
            String p=local.post("/projects",Json.object("path",root.toString())).path("id").asText();String s=local.post("/sessions",Json.object("projectId",p,"title","Телефон")).path("id").asText();
            try(var rejected=new LinkClient(m.port())){rejected.send(Frames.HELLO,Json.object("protocol",Frames.PROTOCOL,"token","nope","name","x"));assertEquals(Frames.BYE,rejected.read().getKey());}
            String token=pairOverLink(m);
            try(var phone=hello(m,token)){
                var full=phone.expect(Frames.STATE,f->f.path("full").asBoolean());
                assertEquals(s,full.path("sessions").get(0).path("id").asText());assertTrue(full.has("projects")&&full.has("quota")&&full.has("revision"));
                phone.send(Frames.WATCH,Json.object("session",s,"reset",false));
                var conversation=phone.expect(Frames.CONVERSATION,f->f.path("reset").asBoolean());
                assertEquals(0,conversation.path("messages").size());assertFalse(conversation.path("hasMore").asBoolean());assertTrue(conversation.has("queued"));
                var started=phone.request("/sessions/"+s+"/run",Json.object("prompt","long","requestId","one"));assertEquals(200,started.path("status").asInt(),started.toString());
                var running=phone.expect(Frames.STATE,f->{for(var x:f.path("sessions"))if(x.path("id").asText().equals(s)&&x.path("running").asBoolean())return true;return false;});
                assertFalse(running.has("full"));assertFalse(running.has("projects"),"unchanged catalog keys are not resent");
                var live=phone.expect(Frames.LIVE,f->f.path("run").asLong()!=0&&f.path("text").asText().startsWith("Работаю"));
                var tail=phone.expect(Frames.LIVE,f->f.path("run").asLong()==live.path("run").asLong()&&f.path("offset").asInt()>0);
                assertEquals("Работаю".length(),tail.path("offset").asInt());assertEquals(" над задачей…",tail.path("text").asText());
                var userMessage=phone.expect(Frames.CONVERSATION,f->f.path("messages").size()>0&&f.path("messages").get(0).path("role").asText().equals("user"));
                assertFalse(userMessage.path("reset").asBoolean());
                assertEquals(200,phone.request("/sessions/"+s+"/stop",Json.object()).path("status").asInt());
                phone.expect(Frames.LIVE,f->f.path("run").asLong()==0);
                var page=phone.request("/messages?session="+s+"&before=9223372036854775807&count=10",null);assertEquals(200,page.path("status").asInt());assertTrue(page.path("result").path("messages").size()>=1);
                assertEquals(404,phone.request("/shutdown",Json.object()).path("status").asInt());
                assertEquals(400,phone.request("/sessions/"+s+"/run",Json.object("prompt","bad","images",List.of(root.resolve("secret").toString()))).path("status").asInt());
                assertEquals(200,phone.request("/sessions/"+s+"/rename",Json.object("title","Переименовано")).path("status").asInt());
                phone.expect(Frames.STATE,f->{for(var x:f.path("sessions"))if(x.path("title").asText().equals("Переименовано"))return true;return false;});
                // A busy host keeps pinging on its cadence; a phone that only answers pings must not be dropped by the reader timeout.
                long until=System.currentTimeMillis()+11_500;int i=0;phone.pings=0;
                while(System.currentTimeMillis()<until&&phone.pings==0){phone.request("/sessions/"+s+"/rename",Json.object("title","Занято "+(i++)));Thread.sleep(200);}
                assertTrue(phone.pings>0,"ping within 10 s while deltas flow");
                // A phone that reconnects with the tail it already holds gets only the newer part, not the whole tail again.
                long lastSequence=0;for(var mm:phone.request("/messages?session="+s+"&before=9223372036854775807&count=100",null).path("result").path("messages"))lastSequence=Math.max(lastSequence,mm.path("sequence").asLong());
                try(var again=hello(m,token)){
                    again.expect(Frames.STATE,f->f.path("full").asBoolean());
                    again.send(Frames.WATCH,Json.object("session",s,"reset",false,"after",lastSequence,"messageRevision",0));
                    var resumed=again.expect(Frames.CONVERSATION,f->f.path("session").asText().equals(s));
                    assertFalse(resumed.path("reset").asBoolean(),resumed.toString());assertEquals(0,resumed.path("messages").size(),resumed.toString());assertTrue(resumed.has("queued"));
                }
                try(var stale=hello(m,token)){
                    stale.expect(Frames.STATE,f->f.path("full").asBoolean());
                    stale.send(Frames.WATCH,Json.object("session",s,"reset",false,"after",lastSequence,"messageRevision",999,"tail",1));
                    var fresh=stale.expect(Frames.CONVERSATION,f->f.path("reset").asBoolean());assertTrue(fresh.path("messages").size()>0&&fresh.path("messages").size()<=10,"stale revision forces a fresh tail, clamped to at least 10: "+fresh);
                }
            }
        }
    }
    @Test void appCatalogRequiresPairingAndServesOnlyPublishedApksAndQueuePause()throws Exception {
        Path project=Files.createDirectories(root.resolve("game"));Files.writeString(project.resolve("game.apk"),"signed-apk-fixture");
        Json.MAPPER.writeValue(project.resolve(".tethium-apps.json").toFile(),Json.object("apps",List.of(Json.object("packageName","our.game","title","Game","versionCode",1,"versionName","1.0","apk","game.apk"))));
        try(var h=new HostService(root.resolve("data"),ADAPTER);var local=new HostClient(h.descriptor());var m=new MobileGateway(root.resolve("data"),h.feed(),0,0);var client=HttpClient.newHttpClient()){
            String projectId=local.post("/projects",Json.object("path",project.toString())).path("id").asText();
            String session=local.post("/sessions",Json.object("projectId",projectId)).path("id").asText();
            assertEquals(401,call(client,m,"/apps",null,null).statusCode());
            String token=Json.parse(call(client,m,"/pair",Json.object("code",code(m)),null).body()).path("token").asText();
            var response=call(client,m,"/apps",null,token);assertEquals(200,response.statusCode(),response.body());
            var apps=Json.parse(response.body()).path("apps");assertEquals("dev.tethium.mobile",apps.get(0).path("packageName").asText());
            String path=apps.get(1).path("path").asText();assertEquals(401,call(client,m,path,null,null).statusCode());
            assertEquals("signed-apk-fixture",call(client,m,path,null,token).body());assertNotEquals(200,call(client,m,"/apps/"+"0".repeat(64)+".apk",null,token).statusCode());
            try(var phone=hello(m,token)){var catalog=phone.request("/apps",null);assertEquals("our.game",catalog.path("result").path("apps").get(1).path("packageName").asText());}
            assertEquals(200,call(client,m,"/sessions/"+session+"/queue-pause",Json.object(),token).statusCode());
            assertTrue(local.get("/state").path("sessions").get(0).path("queuePaused").asBoolean());
            assertEquals(200,call(client,m,"/sessions/"+session+"/queue-resume",Json.object(),token).statusCode());
            assertFalse(local.get("/state").path("sessions").get(0).path("queuePaused").asBoolean());
        }
    }
    @Test void pairingAuthenticationRevocationAndNarrowApiSurviveRestart()throws Exception{
        String token;
        try(var h=new HostService(root,ADAPTER);var local=new HostClient(h.descriptor());var mobile=new MobileGateway(root,h.feed(),0,0);var client=HttpClient.newHttpClient()){
            String code=code(mobile);
            assertEquals(401,call(client,mobile,"/state",null,null).statusCode());
            assertEquals(401,call(client,mobile,"/pair",Json.object("code","wrong"),null).statusCode());
            var response=call(client,mobile,"/pair",Json.object("code",code,"name","probe"),null);assertEquals(200,response.statusCode());token=Json.MAPPER.readTree(response.body()).path("token").asText();
            assertEquals(401,call(client,mobile,"/pair",Json.object("code",code),null).statusCode());
            assertEquals(200,call(client,mobile,"/state",null,token).statusCode());
            for(String path:List.of("/shutdown","/updates/shutdown","/mobile/pair","/imports/conversation","/projects","/projects/any/cross-project"))assertEquals(404,call(client,mobile,path,Json.object(),token).statusCode(),path);
            var req=HttpRequest.newBuilder(URI.create("http://localhost:"+mobile.httpPort()+"/state")).header("Authorization","Bearer "+token).header("Origin","http://evil.invalid").GET().build();assertEquals(401,client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(local.get("/health").has("version"));
        }
        try(var h=new HostService(root,ADAPTER);var m=new MobileGateway(root,h.feed(),0,0);var client=HttpClient.newHttpClient()){
            assertEquals(200,call(client,m,"/state",null,token).statusCode());
            try(var phone=hello(m,token)){phone.expect(Frames.STATE,f->f.path("full").asBoolean());m.revoke();assertThrows(IOException.class,()->{while(true)phone.read();});}
            assertEquals(401,call(client,m,"/state",null,token).statusCode());
        }
    }
    @Test void previewOnlyServesPublishedBundleAndApiNeedsTheToken()throws Exception{
        Path bundle=Files.createDirectories(root.resolve("bundle"));Path page=Files.writeString(bundle.resolve("index.html"),"<h1>preview</h1>");Files.writeString(root.resolve("secret.txt"),"PRIVATE");
        try(var h=new HostService(root,ADAPTER);var local=new HostClient(h.descriptor());var m=new MobileGateway(root,h.feed(),0,0);var client=HttpClient.newHttpClient()){
            String p=local.post("/projects",Json.object("path",root.toString())).path("id").asText();String s=local.post("/sessions",Json.object("projectId",p)).path("id").asText();
            String artifact=local.post("/sessions/"+s+"/artifact",Json.object("path",page.toString(),"title","preview")).path("id").asText();
            String t=Json.MAPPER.readTree(call(client,m,"/pair",Json.object("code",code(m)),null).body()).path("token").asText();
            assertEquals(400,call(client,m,"/sessions/"+s+"/run",Json.object("prompt","read","images",List.of(root.resolve("secret.txt").toString())),t).statusCode());
            String path;try(var phone=hello(m,t)){var r=phone.request("/preview",Json.object("session",s,"artifact",artifact));assertEquals(200,r.path("status").asInt(),r.toString());path=r.path("result").path("path").asText();}
            assertEquals("<h1>preview</h1>",call(client,m,path,null,null).body());
            assertNotEquals(200,call(client,m,path.replace("index.html","%2e%2e/secret.txt"),null,null).statusCode());
            assertEquals(401,call(client,m,"/state",null,null).statusCode());assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+m.httpPort()+"/")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void pinnedDocumentCanBeOpenedRemotelyWithoutAnAgentSession()throws Exception{
        Path project=Files.createDirectories(root.resolve("project"));Files.writeString(project.resolve("readme.md"),"| A | B |\n|---|---|\n| one | two |\n");
        try(var h=new HostService(root.resolve("data"),ADAPTER);var local=new HostClient(h.descriptor());var m=new MobileGateway(root.resolve("data"),h.feed(),0,0);var client=HttpClient.newHttpClient()){
            String p=local.post("/projects",Json.object("path",project.toString())).path("id").asText();
            String t=Json.MAPPER.readTree(call(client,m,"/pair",Json.object("code",code(m)),null).body()).path("token").asText();
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
        try(var h=new HostService(root,ADAPTER);var local=new HostClient(h.descriptor());var m=new MobileGateway(root,h.feed(),0,0);var client=HttpClient.newHttpClient()){
            String p=local.post("/projects",Json.object("path",root.toString())).path("id").asText();String s=local.post("/sessions",Json.object("projectId",p)).path("id").asText();
            local.post("/sessions/"+s+"/stop",Json.object());local.post("/sessions/"+s+"/draft",Json.object("draft","laptop draft"));
            String token=pairOverLink(m);
            var image=new java.awt.image.BufferedImage(32,24,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
            var body=Json.object("data",Base64.getEncoder().encodeToString(bytes.toByteArray()));
            assertEquals(401,call(client,m,"/attachment",body,null).statusCode());
            try(var phone=hello(m,token)){
                var uploaded=phone.request("/attachment",body);assertEquals(200,uploaded.path("status").asInt());String attachment=uploaded.path("result").path("id").asText();
                assertEquals(attachment,phone.request("/attachment",body).path("result").path("id").asText());
                assertEquals(400,phone.request("/attachment",Json.object("data",Base64.getEncoder().encodeToString("not PNG".getBytes()))).path("status").asInt());
                assertEquals(400,phone.request("/sessions/"+s+"/run",Json.object("prompt","bad","attachments",List.of("../secret"))).path("status").asInt());
                var send=Json.object("prompt","look","attachments",List.of(attachment),"requestId","image-once");
                assertEquals(200,phone.request("/sessions/"+s+"/run",send).path("status").asInt());assertEquals(200,phone.request("/sessions/"+s+"/run",send).path("status").asInt());
                var state=local.get("/state?session="+s);var job=state.path("conversation").path("queued").get(0);assertEquals(1,job.path("partsCount").asInt());assertTrue(job.path("prompt").asText().contains("[Скриншот"));
                assertEquals("laptop draft",state.path("sessions").get(0).path("preferences").path("draft").asText());
                var take=Json.object("jobId",job.path("id").asText(),"expectedPrompt",job.path("prompt").asText(),"draft","phone draft","requestId","take-once");
                var returned=phone.request("/sessions/"+s+"/queue-take",take);assertEquals(200,returned.path("status").asInt());assertTrue(returned.path("result").path("draft").asText().startsWith("phone draft"));
                assertEquals(returned.path("result"),phone.request("/sessions/"+s+"/queue-take",take).path("result"));
                state=local.get("/state?session="+s);assertEquals(0,state.path("conversation").path("queued").size());assertEquals("laptop draft",state.path("sessions").get(0).path("preferences").path("draft").asText());
            }
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
    private static HttpResponse<String> call(HttpClient c,MobileGateway m,String path,Object body,String token)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("http://localhost:"+m.httpPort()+path));if(token!=null)r.header("Authorization","Bearer "+token);if(body==null)r.GET();else r.POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)));return c.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
}

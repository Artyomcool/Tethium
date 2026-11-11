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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/** A reminder written in a chat reaches the phone over the framed link before it is due, and the phone's answer reaches the core. */
@Timeout(60)
class ReminderLinkTest {
    @TempDir Path root;

    /** The agent does what a model would after «напомни через 2 часа позвонить маме»: one reminder_add call. */
    private static final class ReminderAgent implements AgentAdapter {
        final AtomicReference<JsonNode> result=new AtomicReference<>();
        public Provider provider(){return Provider.CODEX;}
        public Result run(Request r,Consumer<Event> events,Cancellation c)throws Exception{
            try(var http=HttpClient.newHttpClient()){
                var body=Json.object("jsonrpc","2.0","id",1,"method","tools/call","params",Json.object("name","reminder_add","arguments",Json.object("text","Позвонить маме","delayMinutes",120)));
                var response=http.send(HttpRequest.newBuilder(r.toolsUrl()).header("Authorization","Bearer "+r.toolsToken()).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
                result.set(Json.parse(response.body()).path("result"));
            }
            return new Result("reminder-thread",Status.COMPLETED,"Напомню через 2 часа");
        }
    }

    @Test void reminderIsDeliveredAheadAndAnsweredFromThePhone()throws Exception{
        var agent=new ReminderAgent();Path data=root.resolve("data");
        try(var h=new HostService(data,agent);var local=new HostClient(h.descriptor());var m=new MobileGateway(data,h.feed(),0,0)){
            String project=local.post("/projects",Json.object("path",root.resolve("p").toString())).path("id").asText();
            String session=local.post("/sessions",Json.object("projectId",project,"title","Личное")).path("id").asText();
            String token=pair(m);
            try(var phone=hello(m,token)){
                phone.expect(Frames.STATE,f->f.path("full").asBoolean());
                long before=System.currentTimeMillis();
                local.post("/sessions/"+session+"/run",Json.object("prompt","напомни через 2 часа позвонить маме"));
                // The phone learns the time in advance, without asking, so it can alert while out of reach.
                var pushed=phone.expect(Frames.STATE,f->f.path("reminders").size()==1);
                var reminder=pushed.path("reminders").get(0);
                assertFalse(agent.result.get().path("isError").asBoolean(),agent.result.get().toString());
                assertEquals("Позвонить маме",reminder.path("text").asText());assertEquals("Личное",reminder.path("sessionTitle").asText());
                long due=reminder.path("dueAt").asLong();
                assertTrue(due>=before+120*60_000L&&due<=System.currentTimeMillis()+120*60_000L);
                assertFalse(reminder.path("firing").asBoolean());
                String id=reminder.path("id").asText();

                // An answer given offline arrives later with the time it was given and the firing it answered.
                long answeredAt=System.currentTimeMillis()-60_000;
                var snoozed=phone.request("/reminders/"+id+"/snooze",Json.object("minutes",10,"at",answeredAt,"dueAt",due,"by","phone"));
                assertEquals(200,snoozed.path("status").asInt(),snoozed.toString());
                assertEquals(answeredAt+600_000,snoozed.path("result").path("reminder").path("dueAt").asLong());
                phone.expect(Frames.STATE,f->f.path("reminders").size()==1&&f.path("reminders").get(0).path("dueAt").asLong()==answeredAt+600_000);
                var wrong=phone.request("/reminders/"+id+"/snooze",Json.object("minutes",5));
                assertEquals(400,wrong.path("status").asInt());
                assertEquals(404,phone.request("/reminders/"+id+"/dismiss",Json.object()).path("status").asInt());

                // Done on the desktop takes it off the phone at once.
                local.post("/reminders/"+id+"/done",Json.object("by","desktop"));
                phone.expect(Frames.STATE,f->f.has("reminders")&&f.path("reminders").isEmpty());
                assertEquals(0,local.get("/reminders").path("reminders").size());
            }
        }
    }

    private static final class LinkClient implements AutoCloseable {
        final Socket socket;final DataInputStream in;final DataOutputStream out;final Tbin.Writer writer=new Tbin.Writer();final Tbin.Reader reader=new Tbin.Reader();final Deque<Map.Entry<Integer,JsonNode>> frames=new ArrayDeque<>();int ids;
        LinkClient(int port)throws IOException{socket=new Socket("127.0.0.1",port);socket.setSoTimeout(15_000);in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));}
        void send(int kind,JsonNode body)throws IOException{Frames.write(out,writer.encode(kind,body));}
        Map.Entry<Integer,JsonNode> read()throws IOException{byte[] raw=Frames.read(in);return Map.entry(raw[0]&255,reader.decode(raw,1));}
        JsonNode expect(int kind,Predicate<JsonNode> test)throws IOException{
            for(var it=frames.iterator();it.hasNext();){var f=it.next();if(f.getKey()==kind&&test.test(f.getValue())){it.remove();return f.getValue();}}
            long until=System.currentTimeMillis()+15_000;
            while(System.currentTimeMillis()<until){var f=read();if(f.getKey()==kind&&test.test(f.getValue()))return f.getValue();if(f.getKey()!=Frames.PING)frames.addLast(f);}
            throw new AssertionError("No frame of kind "+kind);
        }
        JsonNode request(String path,JsonNode body)throws IOException{int id=++ids;var frame=Json.object("id",id,"path",path,"post",body!=null);if(body!=null)frame.set("body",body);send(Frames.REQUEST,frame);return expect(Frames.RESPONSE,r->r.path("id").asInt()==id);}
        public void close()throws IOException{socket.close();}
    }
    private static String pair(MobileGateway m)throws Exception{
        String code=URI.create(m.pairInfo().path("pairing").asText()).getQuery().split("code=")[1];
        try(var c=new LinkClient(m.port())){c.send(Frames.PAIR,Json.object("protocol",Frames.PROTOCOL,"code",code,"name","test phone"));var reply=c.read();assertEquals(Frames.PAIRED,reply.getKey(),reply.getValue().toString());return reply.getValue().path("token").asText();}
    }
    private static LinkClient hello(MobileGateway m,String token)throws Exception{var c=new LinkClient(m.port());c.send(Frames.HELLO,Json.object("protocol",Frames.PROTOCOL,"token",token,"name","test phone"));var reply=c.read();assertEquals(Frames.WELCOME,reply.getKey(),reply.getValue().toString());return c;}
}

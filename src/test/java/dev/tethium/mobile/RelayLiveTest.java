package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import dev.tethium.link.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly targeted smoke test for the configured workers.dev room; skipped on machines without relay.json. */
class RelayLiveTest {
    @TempDir Path root;
    @Test void realWorkerCarriesStateAndHttpWhileCloudflareOnlySeesCiphertext()throws Exception{
        Path configured=Path.of(System.getenv("LOCALAPPDATA"),"Tethium","mobile","relay.json");Assumptions.assumeTrue(Files.isRegularFile(configured));
        Path mobile=Files.createDirectories(root.resolve("mobile"));Files.copy(configured,mobile.resolve("relay.json"));
        Path project=Files.createDirectories(root.resolve("ah"));var service=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);service.createContext("/ping",exchange->{byte[] value="ah-ok".getBytes();exchange.sendResponseHeaders(200,value.length);exchange.getResponseBody().write(value);exchange.close();});service.start();
        PhoneServices.publish(project,"ahchecklist",service.getAddress().getPort(),"ah.mobile");String serviceToken=MobileGateway.randomToken();Json.MAPPER.writeValue(mobile.resolve("service-tokens.json").toFile(),Map.of(MobileGateway.sha(serviceToken.getBytes()),"ahchecklist"));
        var feed=new MobileGateway.Feed(){public long revision(){return 1;}public void addListener(Runnable r){}public void removeListener(Runnable r){}public JsonNode get(String path){return Json.object();}public JsonNode post(String path,JsonNode body){return Json.object();}public JsonNode catalog(){return Json.object("revision",1,"projects",List.of(),"sessions",List.of());}public JsonNode conversationMeta(String s){return Json.object();}public JsonNode messagesAfter(String s,long a,int l){return Json.object();}public JsonNode messagesBefore(String s,long b,int l){return Json.object();}public JsonNode live(String s,long r,int o){return Json.object();}};
        var feedWithProject=new MobileGateway.Feed(){public long revision(){return feed.revision();}public void addListener(Runnable r){}public void removeListener(Runnable r){}public JsonNode get(String path){return Json.object();}public JsonNode post(String path,JsonNode body){return Json.object();}public JsonNode catalog(){return Json.object("revision",1,"projects",List.of(Json.object("root",project.toString(),"title","AH")),"sessions",List.of());}public JsonNode conversationMeta(String s){return Json.object();}public JsonNode messagesAfter(String s,long a,int l){return Json.object();}public JsonNode messagesBefore(String s,long b,int l){return Json.object();}public JsonNode live(String s,long r,int o){return Json.object();}};
        try(var gateway=new MobileGateway(root,feedWithProject,0,0)){
            JsonNode paired=pair(gateway);String token=paired.path("token").asText();JsonNode relay=paired.path("relay");assertTrue(relay.path("url").asText().startsWith("wss://"));
            try(var phone=RemotePhone.connect(relay)){
                phone.send(Frames.HELLO,Json.object("protocol",Frames.PROTOCOL,"token",token,"name","relay probe"));JsonNode welcome=phone.expect(Frames.WELCOME);assertEquals("cloudflare",welcome.path("transport").asText());
                phone.send(Frames.REQUEST,Json.object("id",1,"path","/gateway/status","post",false));JsonNode response=phone.expect(Frames.RESPONSE);assertEquals(200,response.path("status").asInt(),response.toString());assertTrue(response.path("result").path("traffic").path("estimatedWorkerRequests").asLong()>=2);assertEquals(1,response.path("result").path("relay").path("connectedRooms").asInt());
                phone.send(Frames.HTTP_REQUEST,Json.object("id",2,"path","/gateway/status","method","GET","headers",Json.object(),"approved",false));var body=new ByteArrayOutputStream();JsonNode part;do{part=phone.expect(Frames.HTTP_RESPONSE);if(part.path("id").asInt()!=2)continue;if(part.path("body").isBinary())body.write(part.path("body").binaryValue());}while(!part.path("done").asBoolean());assertTrue(Json.MAPPER.readTree(body.toByteArray()).path("traffic").has("today"));
                phone.send(Frames.HTTP_REQUEST,Json.object("id",3,"path","/apk","method","GET","headers",Json.object(),"approved",false));do{part=phone.expect(Frames.HTTP_RESPONSE);}while(part.path("id").asInt()!=3);assertEquals(428,part.path("status").asInt());
                phone.send(Frames.HTTP_REQUEST,Json.object("id",4,"path","/svc/ahchecklist/ping","method","GET","headers",Json.object("Authorization","Bearer "+serviceToken),"approved",false));body.reset();do{part=phone.expect(Frames.HTTP_RESPONSE);if(part.path("id").asInt()!=4)continue;if(part.path("body").isBinary())body.write(part.path("body").binaryValue());}while(!part.path("done").asBoolean());assertEquals("ah-ok",body.toString());
            }
        }finally{service.stop(0);}
    }
    private static JsonNode pair(MobileGateway gateway)throws Exception{
        String code=URI.create(gateway.pairInfo().path("pairing").asText()).getQuery().split("code=")[1];
        try(var socket=new Socket("127.0.0.1",gateway.port());var in=new DataInputStream(socket.getInputStream());var out=new DataOutputStream(socket.getOutputStream())){var writer=new Tbin.Writer();var reader=new Tbin.Reader();Frames.write(out,writer.encode(Frames.PAIR,Json.object("protocol",Frames.PROTOCOL,"code",code,"name","relay probe")));byte[] raw=Frames.read(in);assertEquals(Frames.PAIRED,raw[0]&255);return reader.decode(raw,1);}
    }
    private static final class RemotePhone implements AutoCloseable,WebSocket.Listener {
        final RelayCrypto crypto;final Tbin.Writer writer=new Tbin.Writer();final Tbin.Reader reader=new Tbin.Reader();final BlockingQueue<byte[]> messages=new LinkedBlockingQueue<>();final ByteArrayOutputStream partial=new ByteArrayOutputStream();WebSocket socket;
        RemotePhone(RelayCrypto crypto){this.crypto=crypto;}
        static RemotePhone connect(JsonNode info)throws Exception{
            var crypto=new RelayCrypto(RelayCrypto.decode(info.path("secret").asText()));URI uri=URI.create(info.path("url").asText()+"/v1/connect/"+crypto.room());Exception last=null;
            for(int attempt=0;attempt<20;attempt++){var phone=new RemotePhone(crypto);try{phone.socket=HttpClient.newHttpClient().newWebSocketBuilder().header("x-tethium-ticket",info.path("ticket").asText()).header("x-tethium-role","phone").header("x-tethium-auth",crypto.auth()).buildAsync(uri,phone).join();return phone;}catch(Exception e){last=e;Thread.sleep(250);}}
            throw last;
        }
        void send(int kind,JsonNode body){socket.sendBinary(ByteBuffer.wrap(crypto.seal(Frames.pack(writer.encode(kind,body)))),true).join();}
        JsonNode expect(int kind)throws Exception{long until=System.currentTimeMillis()+15_000;while(System.currentTimeMillis()<until){byte[] envelope=messages.poll(1,TimeUnit.SECONDS);if(envelope==null)continue;byte[] raw=Frames.unpack(crypto.open(envelope));int actual=raw[0]&255;JsonNode body=reader.decode(raw,1);if(actual==Frames.PING){send(Frames.PONG,Json.object());continue;}if(actual==kind)return body;}throw new AssertionError("No frame "+kind);}
        public void onOpen(WebSocket socket){socket.request(1);}
        public CompletionStage<?> onBinary(WebSocket socket,ByteBuffer data,boolean last){byte[] bytes=new byte[data.remaining()];data.get(bytes);partial.write(bytes,0,bytes.length);if(last){messages.offer(partial.toByteArray());partial.reset();}socket.request(1);return null;}
        public CompletionStage<?> onClose(WebSocket socket,int status,String reason){return null;}
        public void onError(WebSocket socket,Throwable error){}
        public void close(){if(socket!=null)socket.sendClose(WebSocket.NORMAL_CLOSURE,"done");}
    }
}

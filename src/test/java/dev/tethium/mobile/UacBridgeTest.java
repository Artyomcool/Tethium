package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class UacBridgeTest {
    @TempDir Path root;
    private static final String ID="a".repeat(32), ACTION="b".repeat(32), NEXT="c".repeat(32), TOKEN="d".repeat(64);
    @Test void forwardsOnlyCurrentKnownActionsAndNeverRetriesAnUncertainAnswer()throws Exception{
        var state=new AtomicReference<JsonNode>(Json.object("available",true,"pending",true,"id",ID,"texts",new String[]{"PowerShell","Издатель: Microsoft"},"actions",new JsonNode[]{Json.object("id",ACTION,"label","Да")}));
        var answers=new AtomicInteger();var fail=new AtomicBoolean();var seen=new AtomicReference<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{try(e){
            if(!("Bearer "+TOKEN).equals(e.getRequestHeaders().getFirst("Authorization"))){e.sendResponseHeaders(403,-1);return;}
            JsonNode reply=state.get();int status=200;
            if(e.getRequestURI().getPath().equals("/answer")){answers.incrementAndGet();seen.set(Json.MAPPER.readTree(e.getRequestBody()));if(fail.get()){reply=Json.object("error","Lost response");status=500;}else{reply=Json.object("ok",true);state.set(Json.object("available",true,"pending",false));}}
            byte[] bytes=reply.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);
        }});server.start();
        Path descriptor=root.resolve("bridge.json");Json.MAPPER.writeValue(descriptor.toFile(),Json.object("protocol",1,"port",server.getAddress().getPort(),"token",TOKEN));
        try(var bridge=new UacBridge(descriptor,()->{})){
            bridge.refresh();assertEquals("Да",bridge.snapshot().path("actions").get(0).path("label").asText());assertFalse(bridge.snapshot().has("token"));
            assertThrows(IllegalStateException.class,()->bridge.answer(Json.object("id",NEXT,"action",ACTION)));
            assertThrows(IllegalArgumentException.class,()->bridge.answer(Json.object("id",ID,"action",NEXT)));assertEquals(0,answers.get());
            assertTrue(bridge.answer(Json.object("id",ID,"action",ACTION,"command","arbitrary")).path("ok").asBoolean());
            assertEquals(Json.object("id",ID,"action",ACTION),seen.get());assertEquals(1,answers.get());assertFalse(bridge.snapshot().path("pending").asBoolean());
            assertThrows(IllegalStateException.class,()->bridge.answer(Json.object("id",ID,"action",ACTION)));assertEquals(1,answers.get());
            state.set(Json.object("available",true,"pending",true,"id",NEXT,"actions",new JsonNode[]{Json.object("id",ACTION,"label","Нет")}));bridge.refresh();fail.set(true);
            assertThrows(java.io.IOException.class,()->bridge.answer(Json.object("id",NEXT,"action",ACTION)));assertEquals(2,answers.get(),"uncertain answer is sent once");
            server.stop(0);bridge.refresh();assertFalse(bridge.snapshot().path("available").asBoolean());assertFalse(bridge.snapshot().path("pending").asBoolean());
        }finally{server.stop(0);}
    }
    @Test void absentHelperIsUnavailableAndRejectsAnswers(){
        try(var bridge=new UacBridge(null,()->{})){bridge.refresh();assertEquals("not-installed",bridge.snapshot().path("reason").asText());assertThrows(IllegalStateException.class,()->bridge.answer(Json.object("id",ID,"action",ACTION)));}
    }
}

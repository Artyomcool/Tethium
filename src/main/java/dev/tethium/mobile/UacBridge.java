package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Read-only snapshots plus explicit human actions, exposed only to paired clients by MobileGateway. */
public final class UacBridge implements AutoCloseable {
    private final Path descriptor;
    private final Runnable changed;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("uac-bridge").factory());
    private volatile JsonNode state=Json.object("available",false,"pending",false,"reason","not-installed");

    public UacBridge(Runnable changed){this(defaultDescriptor(),changed);}
    UacBridge(Path descriptor,Runnable changed){
        this.descriptor=descriptor;this.changed=changed;
        if(descriptor!=null)monitor.scheduleWithFixedDelay(this::refresh,0,750,TimeUnit.MILLISECONDS);
    }
    private static Path defaultDescriptor(){
        if(Boolean.getBoolean("tethium.probe")||!System.getProperty("os.name","").startsWith("Windows"))return null;
        String data=System.getenv("ProgramData");return data==null?null:Path.of(data,"Tethium","Uac","bridge.json");
    }
    public JsonNode snapshot(){return state.deepCopy();}
    private void set(JsonNode next){if(!next.equals(state)){state=next;changed.run();}}
    synchronized void refresh(){
        if(descriptor==null||!Files.isRegularFile(descriptor)){set(Json.object("available",false,"pending",false,"reason","not-installed"));return;}
        try{set(exchange("/state",null));}
        catch(Exception e){set(Json.object("available",false,"pending",false,"reason","disconnected"));}
    }
    /** Never retried. The worker independently checks the prompt fingerprint and consumes the action. */
    public synchronized JsonNode answer(JsonNode body)throws Exception{
        String id=body.path("id").asText(),action=body.path("action").asText();
        var seen=state;
        if(!seen.path("pending").asBoolean()||!id.matches("[a-f0-9]{32}")||!id.equals(seen.path("id").asText()))throw new IllegalStateException("Запрос уже закрыт или изменился");
        boolean known=false;for(var button:seen.path("actions"))if(action.equals(button.path("id").asText()))known=true;
        if(!known)throw new IllegalArgumentException("Кнопка недоступна в текущем запросе");
        try{
            var result=exchange("/answer",Json.object("id",id,"action",action));
            if(!result.path("ok").asBoolean())throw new IllegalStateException(result.path("error").asText("Запрос изменился"));
            return result;
        }finally{refresh();}
    }
    private JsonNode exchange(String path,JsonNode body)throws Exception{
        if(descriptor==null||Files.size(descriptor)>4096)throw new IOException("UAC helper not installed");
        var config=Json.MAPPER.readTree(Files.readString(descriptor));int port=config.path("port").asInt();String token=config.path("token").asText();
        if(config.path("protocol").asInt()!=1||port<1||port>65535||!token.matches("[a-f0-9]{64}"))throw new IOException("Invalid UAC descriptor");
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(body==null?3:7)).header("Authorization","Bearer "+token);
        if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        if(response.body().length()>262144)throw new IOException("Invalid UAC response");
        var result=Json.MAPPER.readTree(response.body());
        if(response.statusCode()!=200)throw new IOException(result.path("error").asText("Помощник UAC недоступен"));
        if(!result.isObject())throw new IOException("Invalid UAC response");return result;
    }
    public void close(){monitor.shutdownNow();client.close();}
}

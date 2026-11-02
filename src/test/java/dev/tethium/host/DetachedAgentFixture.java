package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.io.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Deterministic local stand-in for the agent protocol. No provider or network account is used. */
public final class DetachedAgentFixture {
    private static final BlockingQueue<JsonNode> answers=new LinkedBlockingQueue<>();
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);String endpoint=null;
        for(String arg:args)if(arg.startsWith("mcp_servers.tethium.url="))endpoint=arg.substring(arg.indexOf('=')+1);
        final String tools=endpoint;
        try(var input=new BufferedReader(new InputStreamReader(System.in))){
            String line;while((line=input.readLine())!=null){
                var request=Json.parse(line);var id=request.get("id");
                if(!request.has("method")){answers.add(request.path("result"));continue;}
                switch(request.path("method").asText()){
                    case "initialize" -> out(Json.object("id",id,"result",Json.object()));
                    case "account/read","account/rateLimits/read","model/list","config/read" -> out(Json.object("id",id,"result",Json.object("data",java.util.List.of(),"rateLimits",Json.object())));
                    case "thread/start","thread/resume" -> out(Json.object("id",id,"result",Json.object("thread",Json.object("id","durable-thread"))));
                    case "turn/start" -> {
                        Files.writeString(root.resolve("submissions"),"start\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                        out(Json.object("id",id,"result",Json.object("turn",Json.object("id","durable-turn"))));
                        delta("before ");
                        Thread.ofVirtual().start(()->{try{
                            waitFor(root.resolve("gap"));
                            if(Files.exists(root.resolve("watch-host-pid"))){long pid=Long.parseLong(Files.readString(root.resolve("watch-host-pid")).trim());while(ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent())Thread.sleep(50);}
                            delta("during ");
                            if(Files.exists(root.resolve("target"))){
                                var call=Json.object("jsonrpc","2.0","id",1,"method","tools/call","params",Json.object("name","session_message","arguments",Json.object("session_id",Files.readString(root.resolve("target")),"text","exactly once")));
                                try(var http=HttpClient.newHttpClient()){
                                    var response=http.send(HttpRequest.newBuilder(URI.create(tools)).timeout(Duration.ofSeconds(90)).header("Authorization","Bearer "+System.getenv("TETHIUM_MCP_TOKEN")).POST(HttpRequest.BodyPublishers.ofString(call.toString())).build(),HttpResponse.BodyHandlers.ofString());
                                    Files.writeString(root.resolve("tool-result"),response.body());
                                }
                            }
                            if(Files.exists(root.resolve("ask"))){
                                out(Json.object("id","question","method","item/tool/requestUserInput","params",Json.object("questions",java.util.List.of(Json.object("id","q","header","Fixture","question","Continue?","options",java.util.List.of())))));
                                Files.writeString(root.resolve("answer"),answers.take().toString());delta("answered ");
                            }
                            waitFor(root.resolve("finish"));delta("after");complete("completed");
                        }catch(Exception e){e.printStackTrace();System.exit(3);}});
                    }
                    case "turn/interrupt" -> {out(Json.object("id",id,"result",Json.object()));complete("interrupted");}
                }
            }
        }
    }
    private static void waitFor(Path file)throws Exception{while(!Files.exists(file))Thread.sleep(50);}
    private static void delta(String value){out(Json.object("method","item/agentMessage/delta","params",Json.object("threadId","durable-thread","itemId","message","delta",value)));}
    private static void complete(String status){out(Json.object("method","turn/completed","params",Json.object("threadId","durable-thread","turn",Json.object("id","durable-turn","status",status))));}
    private static synchronized void out(Object value){System.out.println(value);System.out.flush();}
}

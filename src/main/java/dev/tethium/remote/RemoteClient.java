package dev.tethium.remote;

import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/** Remote transport only. It cannot start or discover a local executor. */
public final class RemoteClient extends HostClient {
    private final PinnedConnection connection;private final Path data;private final Map<String,JsonNode> drafts=new ConcurrentHashMap<>();
    private final Map<String,String> artifactIds=new ConcurrentHashMap<>();private volatile String session="";private HttpServer preview;private ExecutorService previewWorkers;
    public RemoteClient(Path data,JsonNode credentials)throws Exception{super(Json.object());this.data=Files.createDirectories(data);connection=new PinnedConnection(credentials.path("address").asText(),credentials.path("pin").asText(),credentials.path("token").asText());Path file=data.resolve("drafts.json");if(Files.exists(file))Json.MAPPER.readTree(file.toFile()).properties().forEach(e->drafts.put(e.getKey(),e.getValue()));}
    @Override public boolean remote(){return true;}
    @Override public long hostPid(){return 0;}
    @Override public JsonNode get(String path)throws Exception{
        var value=connection.json(path,null);
        if(path.startsWith("/state")){
            for(var s:value.path("sessions")){var d=drafts.get(s.path("id").asText());var prefs=(ObjectNode)s.path("preferences");prefs.put("draft",d==null?"":d.path("draft").asText());prefs.set("draftImages",d==null?Json.MAPPER.createArrayNode():d.path("images"));}
            if(path.contains("?session=")){session=path.substring(path.indexOf("?session=")+9);artifactIds.clear();for(var a:value.path("conversation").path("artifacts"))artifactIds.put(a.path("path").asText(),a.path("id").asText());}
        }return value;
    }
    private synchronized void saveDraft(String id,JsonNode value)throws IOException{drafts.put(id,value);Path temp=Files.createTempFile(data,"draft-",".tmp");try{Json.MAPPER.writeValue(temp.toFile(),drafts);Files.move(temp,data.resolve("drafts.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}
    @Override public JsonNode post(String path,Object body)throws Exception{
        var value=(ObjectNode)Json.MAPPER.valueToTree(body);String[] parts=path.split("/");String id=parts.length>2?parts[2]:"";
        if(path.endsWith("/draft")){saveDraft(id,value);return Json.object("ok",true);}
        if(path.endsWith("/run")||path.endsWith("/interrupt")){
            var attachments=Json.MAPPER.createArrayNode();for(var p:value.path("images")){var upload=connection.json("/attachment",Json.object("data",Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(p.asText())))));attachments.add(upload.path("id").asText());}value.remove("images");value.set("attachments",attachments);value.put("preserveDesktopDraft",true);
        }
        var result=connection.json(path,value);if(path.endsWith("/queue-take"))saveDraft(id,Json.object("draft",result.path("draft").asText(),"images",drafts.getOrDefault(id,Json.object()).path("images")));if(path.endsWith("/run")||path.endsWith("/interrupt"))saveDraft(id,Json.object("draft","","images",List.of()));return result;
    }
    public synchronized URI preview(Path path)throws Exception{
        if(preview!=null)preview.stop(0);if(previewWorkers!=null)previewWorkers.shutdownNow();String id=artifactIds.get(path.toString());var response=connection.json("/preview",id==null?Json.object("session",session,"file",path.toString()):Json.object("session",session,"artifact",id));
        String remote=response.path("path").asText(),remoteBase=remote.substring(0,remote.lastIndexOf('/')+1),prefix="/"+UUID.randomUUID()+"/";
        preview=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);previewWorkers=Executors.newVirtualThreadPerTaskExecutor();preview.setExecutor(previewWorkers);
        preview.createContext("/",e->{try(e){String request=e.getRequestURI().getRawPath();if(!e.getRequestMethod().equals("GET")||!request.startsWith(prefix)){e.sendResponseHeaders(404,-1);return;}try{var c=connection.open(remoteBase+request.substring(prefix.length())+(e.getRequestURI().getRawQuery()==null?"":"?"+e.getRequestURI().getRawQuery()));try{int status=c.getResponseCode();if(status!=200){e.sendResponseHeaders(status,-1);return;}String type=c.getContentType();e.getResponseHeaders().set("Content-Type",type==null?"application/octet-stream":type);e.getResponseHeaders().set("Content-Security-Policy","default-src 'self' data:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; object-src 'none'; frame-src 'none'; base-uri 'none'");e.sendResponseHeaders(200,0);try(var in=c.getInputStream()){in.transferTo(e.getResponseBody());}}finally{c.disconnect();}}catch(Exception failure){try{e.sendResponseHeaders(502,-1);}catch(Exception ignored){}}}});preview.start();return URI.create("http://127.0.0.1:"+preview.getAddress().getPort()+prefix+remote.substring(remote.lastIndexOf('/')+1));
    }
    public Path download(Path artifact)throws Exception{
        String id=artifactIds.get(artifact.toString());if(id==null)throw new IOException("Сначала опубликуйте сборку через artifact_publish");var info=connection.json("/artifact/download",Json.object("session",session,"artifact",id));String name=info.path("name").asText(),hash=info.path("sha256").asText();if(!hash.matches("[a-f0-9]{64}")||!Path.of(name).getFileName().toString().equals(name))throw new IOException("Некорректный результат");Path folder=Files.createDirectories(data.resolve("downloads").resolve(hash)),target=folder.resolve(name);
        if(Files.isRegularFile(target)&&sha(target).equals(hash))return target;var c=connection.open(info.path("path").asText());Path temp=Files.createTempFile(folder,"download-",".tmp");try{if(c.getResponseCode()!=200)throw new IOException("Не удалось получить сборку");long count=0;try(var in=c.getInputStream();var out=Files.newOutputStream(temp)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1){count+=n;if(count>2_000_000_000L)throw new IOException("Сборка больше 2 ГБ");out.write(bytes,0,n);}}if(count!=info.path("size").asLong()||!sha(temp).equals(hash))throw new IOException("Сборка изменилась при скачивании. Повторите получение.");Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);return target;}finally{c.disconnect();Files.deleteIfExists(temp);}
    }
    public static String sha(Path path)throws Exception{var hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}return HexFormat.of().formatHex(hash.digest());}
    @Override public synchronized void close(){if(preview!=null)preview.stop(0);if(previewWorkers!=null)previewWorkers.shutdownNow();super.close();}
}

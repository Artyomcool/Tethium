package dev.tethium.remote;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Desktop transfer engine, also shipped in the client-only distribution. */
public final class FileTransfers {
    private final HostClient host;private final AtomicBoolean cancelled;
    public interface Progress {void update(long completed,long total);}
    public FileTransfers(HostClient host,AtomicBoolean cancelled){this.host=host;this.cancelled=cancelled;}
    private void check()throws InterruptedIOException{if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Передача остановлена. Повторите её для продолжения.");}
    public void uploadDirectory(Path source,String root,String target,boolean replaceExisting,Progress progress)throws Exception{
        check();host.post("/file-manager/ensure-directory",Json.object("root",root,"path",target));
        var directories=new HashMap<String,Map<String,JsonNode>>();
        try(var paths=Files.walk(source)){
            var iterator=paths.iterator();while(iterator.hasNext()){
                check();Path file=iterator.next();if(file.equals(source)||Files.isSymbolicLink(file))continue;
                String relative=source.relativize(file).toString().replace('\\','/'),destination=target+"/"+relative;
                if(Files.isDirectory(file)){host.post("/file-manager/ensure-directory",Json.object("root",root,"path",destination));continue;}
                String parent=destination.substring(0,destination.lastIndexOf('/'));var listing=directories.get(parent);
                if(listing==null){listing=new HashMap<>();int offset=0;do{var page=host.get("/file-manager/list?root="+encode(root)+"&path="+encode(parent)+"&offset="+offset);for(var entry:page.path("entries"))listing.put(entry.path("name").asText(),entry);offset=page.path("next").asInt(-1);}while(offset>=0);directories.put(parent,listing);}
                var old=listing.get(file.getFileName().toString());if(old!=null&&!replaceExisting)throw new IOException("Файл уже есть: "+destination+". Подтвердите объединение папки.");upload(file,root,destination,old==null?"":old.path("revision").asText(),progress);
            }
        }
    }
    public void upload(Path source,String root,String path,String replaceRevision,Progress progress)throws Exception{
        check();long size=Files.size(source),modified=Files.getLastModifiedTime(source).toMillis();String hash=RemoteClient.sha(source);
        var body=Json.object("root",root,"path",path,"size",size,"sha256",hash,"replaceRevision",Objects.toString(replaceRevision,""));
        String id=UUID.nameUUIDFromBytes(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();body.put("id",id);
        var result=host.post("/file-manager/upload",body);long offset=result.path("offset").asLong();progress.update(offset,size);
        if(!result.path("complete").asBoolean())try(var input=new RandomAccessFile(source.toFile(),"r")){
            byte[] chunk=new byte[1024*1024];while(offset<size){check();input.seek(offset);int count=input.read(chunk,0,(int)Math.min(chunk.length,size-offset));if(count<=0)throw new IOException("Исходный файл изменился");
                final long position=offset;final int length=count;
                offset=retry(()->{var c=host.fileConnection("/file-manager/upload/"+id+"/chunk?offset="+position);try{c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(length);try(var output=c.getOutputStream()){output.write(chunk,0,length);}return response(c).path("offset").asLong();}finally{c.disconnect();}});
                if(offset<=position||offset>size)throw new IOException("Некорректный ответ передачи");progress.update(offset,size);
            }
            if(Files.size(source)!=size||Files.getLastModifiedTime(source).toMillis()!=modified)throw new IOException("Исходный файл изменился. Передача не завершена.");
        }
        check();host.post("/file-manager/upload/"+id+"/finish",Json.object());progress.update(size,size);
    }
    public void download(JsonNode info,Path target,boolean replace,Progress progress)throws Exception{
        check();long size=info.path("size").asLong();String hash=info.path("sha256").asText();if(!hash.matches("[a-f0-9]{64}"))throw new IOException("Некорректный файл");
        target=target.toAbsolutePath();Files.createDirectories(target.getParent());String before=Files.exists(target)?revision(target):null;if(before!=null&&!replace)throw new FileAlreadyExistsException(target.toString());
        Path part=target.resolveSibling(".tethium-transfer-download-"+hash+".part");long offset=Files.exists(part)?Files.size(part):0;if(offset>size)throw new IOException("Некорректный незавершённый файл");
        progress.update(offset,size);
        try(var output=new RandomAccessFile(part.toFile(),"rw")){
            while(offset<size){check();final long position=offset;int count=(int)Math.min(1024*1024,size-offset);
                byte[] chunk=retry(()->{var c=host.fileConnection("/file-manager/download/"+info.path("id").asText()+"?offset="+position+"&length="+count);try{if(c.getResponseCode()!=200){response(c);throw new IOException("Ошибка скачивания");}try(var input=c.getInputStream()){byte[] bytes=input.readNBytes(count+1);if(bytes.length!=count)throw new IOException("Неполная порция файла");return bytes;}}finally{c.disconnect();}});
                output.seek(offset);output.write(chunk);offset+=chunk.length;progress.update(offset,size);
            }output.getFD().sync();
        }
        check();if(!RemoteClient.sha(part).equals(hash)){Files.delete(part);throw new IOException("Контрольная сумма не совпала. Повторите скачивание.");}
        if(!Objects.equals(before,Files.exists(target)?revision(target):null))throw new IOException("Файл назначения изменился. Подтвердите замену заново.");
        if(replace)Files.move(part,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);else Files.move(part,target);
        try{host.post("/file-manager/download/"+info.path("id").asText()+"/release",Json.object());}catch(Exception ignored){}
        progress.update(size,size);
    }
    private static String revision(Path file)throws IOException{return Files.size(file)+":"+Files.getLastModifiedTime(file).toMillis();}
    private interface Attempt<T>{T run()throws Exception;}
    private <T>T retry(Attempt<T> action)throws Exception{for(int n=0;;n++){check();try{return action.run();}catch(HostClient.ApiException e){throw e;}catch(IOException e){if(n>=2)throw e;Thread.sleep(250L*(n+1));}}}
    private static JsonNode response(HttpURLConnection c)throws Exception{
        int status=c.getResponseCode();try(var input=status==200?c.getInputStream():c.getErrorStream()){
            var body=input==null?Json.object():Json.MAPPER.readTree(input.readNBytes(1_048_576));if(status!=200)throw new HostClient.ApiException(status,body.path("error").asText("Ошибка передачи: "+status));return body;
        }
    }
    public static String encode(String s){return URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8);}
}

package dev.tethium.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import dev.tethium.json.Json;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** Shared folders and resumable binary transfers. No file data travels through the chat link. */
public final class FileManager {
    public static final String PREFIX="/file-manager";
    public static final int CHUNK=1024*1024;
    private final Path state,received;
    private final Supplier<JsonNode> projects;
    private final Object locks=new Object();
    private final Map<String,Object> uploadLocks=new HashMap<>();
    public FileManager(Path data,Supplier<JsonNode> projects)throws IOException{
        state=Files.createDirectories(data.resolve("file-manager"));received=Files.createDirectories(data.resolve("received"));this.projects=projects;
        Files.createDirectories(state.resolve("uploads"));Files.createDirectories(state.resolve("downloads"));
        // Completed downloads are released by clients; abandoned ZIP snapshots expire after a week.
        try(var old=Files.list(state.resolve("downloads"))){long cutoff=System.currentTimeMillis()-7L*24*3600_000;for(var path:old.toList())try{if(Files.getLastModifiedTime(path).toMillis()<cutoff)Files.deleteIfExists(path);}catch(IOException ignored){}}
    }
    private record Root(String id,String title,Path path){}
    private List<Root> roots()throws IOException{
        var result=new ArrayList<Root>();result.add(new Root("received","Полученные файлы",received.toRealPath()));
        for(var p:projects.get())try{result.add(new Root("project-"+p.path("id").asText(),p.path("title").asText(),Path.of(p.path("root").asText()).toRealPath()));}catch(IOException ignored){}
        Path file=state.resolve("folders.json");if(Files.exists(file))for(var p:Json.MAPPER.readTree(file.toFile()))try{result.add(new Root(p.path("id").asText(),p.path("title").asText(),Path.of(p.path("path").asText()).toRealPath()));}catch(IOException ignored){}
        return result;
    }
    public synchronized JsonNode share(Path directory)throws Exception{
        directory=directory.toRealPath();if(!Files.isDirectory(directory))throw new IOException("Нужна папка");
        for(var root:roots())if(root.path.equals(directory))return Json.object("id",root.id);
        Path file=state.resolve("folders.json");var rows=Files.exists(file)?(com.fasterxml.jackson.databind.node.ArrayNode)Json.MAPPER.readTree(file.toFile()):Json.MAPPER.createArrayNode();
        String id=UUID.randomUUID().toString();rows.add(Json.object("id",id,"title",directory.getFileName()==null?directory.toString():directory.getFileName().toString(),"path",directory.toString()));save(file,rows);return Json.object("id",id);
    }
    private Root root(String id)throws IOException{for(var root:roots())if(root.id.equals(id))return root;throw new IOException("Папка больше не доступна для обмена");}
    private Path resolve(String id,String relative,boolean existing)throws IOException{
        Path base=root(id).path;Path rel=Path.of(relative==null?"":relative);
        if(rel.isAbsolute()||relative!=null&&relative.contains("\\"))throw new IOException("Нужен путь внутри папки");
        Path candidate=base.resolve(rel).normalize();if(!candidate.startsWith(base))throw new IOException("Путь вне общей папки");
        if(existing){candidate=candidate.toRealPath();if(!candidate.startsWith(base))throw new IOException("Ссылка ведёт вне общей папки");}
        else{if(candidate.equals(base))throw new IOException("Укажите имя файла");Path parent=candidate.getParent().toRealPath();if(!parent.startsWith(base))throw new IOException("Путь вне общей папки");candidate=parent.resolve(candidate.getFileName());if(Files.isSymbolicLink(candidate))throw new IOException("Нельзя заменять символическую ссылку");}
        return candidate;
    }
    private static String name(String value)throws IOException{
        if(value==null||value.isBlank()||value.equals(".")||value.equals("..")||value.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")||value.endsWith(".")||value.endsWith(" ")||value.startsWith(".tethium-transfer-"))throw new IOException("Некорректное имя");return value;
    }
    private static String rev(Path p)throws IOException{var a=Files.readAttributes(p,BasicFileAttributes.class);return a.size()+":"+a.lastModifiedTime().toMillis()+":"+Objects.toString(a.fileKey(),"");}
    private static void check(Path target,JsonNode meta)throws IOException{
        String expected=meta.path("replaceRevision").asText("");
        if(Files.exists(target)){if(expected.isEmpty())throw new FileAlreadyExistsException("Файл уже существует. Подтвердите замену.");if(!Files.isRegularFile(target)||!expected.equals(rev(target)))throw new IOException("Файл изменился. Обновите список и подтвердите замену заново.");}
        else if(!expected.isEmpty())throw new IOException("Файл для замены больше не существует. Обновите список.");
    }
    public JsonNode api(String method,URI uri,JsonNode body)throws Exception{
        String action=uri.getPath().substring(PREFIX.length());if(body==null)body=Json.object();
        if(method.equals("GET")&&action.equals("/roots"))return Json.object("roots",roots().stream().map(r->Json.object("id",r.id,"title",r.title,"path",r.path.toString())).toList());
        if(method.equals("GET")&&action.equals("/list")){
            String id=query(uri,"root"),relative=Objects.toString(query(uri,"path"),"");Path dir=resolve(id,relative,true);if(!Files.isDirectory(dir))throw new IOException("Нужна папка");
            int offset=Math.max(0,Integer.parseInt(Objects.toString(query(uri,"offset"),"0")));
            Path sharedRoot=root(id).path;var all=new ArrayList<JsonNode>();try(var files=Files.newDirectoryStream(dir)){for(var file:files){
                if(file.getFileName().toString().startsWith(".tethium-transfer-"))continue;
                try{Path real=file.toRealPath();boolean link=Files.isSymbolicLink(file),accessible=real.startsWith(sharedRoot);var a=Files.readAttributes(file,BasicFileAttributes.class);all.add(Json.object("name",file.getFileName().toString(),"directory",a.isDirectory(),"size",a.size(),"modified",a.lastModifiedTime().toMillis(),"revision",rev(file),"accessible",accessible&&!link,"link",link));}catch(IOException ignored){}
            }}
            all.sort(Comparator.<JsonNode,Boolean>comparing(n->!n.path("directory").asBoolean()).thenComparing(n->n.path("name").asText(),String.CASE_INSENSITIVE_ORDER));
            int end=Math.min(all.size(),offset+500);return Json.object("root",id,"path",root(id).path.relativize(dir).toString().replace('\\','/'),"entries",all.subList(Math.min(offset,all.size()),end),"next",end<all.size()?end:-1);
        }
        if(!method.equals("POST"))throw new IOException("Неизвестная операция с файлами");
        if(action.equals("/ensure-directory")){
            String id=body.path("root").asText(),relative=body.path("path").asText();Path current=root(id).path;
            for(String piece:relative.split("/")){name(piece);current=current.resolve(piece);try{Files.createDirectory(current);}catch(FileAlreadyExistsException exists){if(!Files.isDirectory(current)||Files.isSymbolicLink(current))throw new IOException("Вместо папки уже существует файл или ссылка");}if(!current.toRealPath().startsWith(root(id).path))throw new IOException("Путь вне общей папки");}return Json.object("ok",true);
        }
        if(action.equals("/mkdir")){Path parent=resolve(body.path("root").asText(),body.path("path").asText(),true);Path child=parent.resolve(name(body.path("name").asText()));Files.createDirectory(child);return Json.object("ok",true);}
        if(action.equals("/rename")||action.equals("/delete")){
            String id=body.path("root").asText();Path source=resolve(id,body.path("path").asText(),true);if(source.equals(root(id).path))throw new IOException("Общую папку менять нельзя");
            if(!rev(source).equals(body.path("revision").asText()))throw new IOException("Файл изменился. Обновите список.");
            if(action.equals("/rename"))Files.move(source,source.resolveSibling(name(body.path("name").asText())));
            else{if(!body.path("confirmed").asBoolean())throw new IOException("Нужно подтверждение удаления");Files.delete(source);}return Json.object("ok",true);
        }
        if(action.equals("/download")){
            Path file=resolve(body.path("root").asText(),body.path("path").asText(),true);
            if(Files.isDirectory(file))return archive(body,file);
            if(!Files.isRegularFile(file))throw new IOException("Выберите файл");
            String revision=rev(file),hash=sha(file);if(!revision.equals(rev(file)))throw new IOException("Файл изменяется. Повторите скачивание после записи.");
            String id=UUID.randomUUID().toString();var meta=Json.object("id",id,"root",body.path("root").asText(),"path",body.path("path").asText(),"name",file.getFileName().toString(),"size",Files.size(file),"sha256",hash,"revision",revision,"created",System.currentTimeMillis());save(state.resolve("downloads/"+id+".json"),meta);return meta;
        }
        if(action.equals("/upload"))return beginUpload(body);
        if(action.matches("/download/[a-f0-9-]{36}/release")){String id=action.split("/")[2];var meta=metadata("downloads",id);if(meta.path("archive").asBoolean())Files.deleteIfExists(state.resolve("downloads/"+id+".zip"));Files.deleteIfExists(state.resolve("downloads/"+id+".json"));return Json.object("ok",true);}
        if(action.matches("/upload/[a-f0-9-]{36}/finish"))return finish(action.split("/")[2]);
        throw new IOException("Неизвестная операция с файлами");
    }
    private JsonNode archive(JsonNode body,Path folder)throws Exception{
        String id=UUID.randomUUID().toString();Path zip=state.resolve("downloads/"+id+".zip");int skipped=0,count=0;
        try{
            try(var output=new java.util.zip.ZipOutputStream(Files.newOutputStream(zip,StandardOpenOption.CREATE_NEW));var paths=Files.walk(folder)){
                output.setLevel(java.util.zip.Deflater.BEST_SPEED);
                var iterator=paths.iterator();while(iterator.hasNext()){
                    Path file=iterator.next();if(file.equals(folder)||file.getFileName().toString().startsWith(".tethium-transfer-"))continue;
                    if(Files.isSymbolicLink(file)||!file.toRealPath().startsWith(folder)){skipped++;continue;}
                    if(++count>100000)throw new IOException("В папке больше 100 000 элементов; выберите меньшую папку");
                    String relative=folder.relativize(file).toString().replace('\\','/');boolean directory=Files.isDirectory(file);var entry=new java.util.zip.ZipEntry(relative+(directory?"/":""));entry.setTime(Files.getLastModifiedTime(file).toMillis());output.putNextEntry(entry);
                    if(!directory){String before=rev(file);Files.copy(file,output);if(!before.equals(rev(file)))throw new IOException("Файл изменился при упаковке: "+relative);}output.closeEntry();
                }
            }
            var meta=Json.object("id",id,"root",body.path("root").asText(),"path",body.path("path").asText(),"name",(folder.getFileName()==null?"files":folder.getFileName().toString())+".zip","size",Files.size(zip),"sha256",sha(zip),"archive",true,"skippedLinks",skipped,"created",System.currentTimeMillis());save(state.resolve("downloads/"+id+".json"),meta);return meta;
        }catch(Exception e){Files.deleteIfExists(zip);throw e;}
    }
    private Object lock(String id){synchronized(locks){return uploadLocks.computeIfAbsent(id,k->new Object());}}
    private JsonNode metadata(String kind,String id)throws IOException{if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Некорректная передача");return Json.MAPPER.readTree(state.resolve(kind+"/"+id+".json").toFile());}
    private Path part(JsonNode meta)throws IOException{Path target=resolve(meta.path("root").asText(),meta.path("path").asText(),false);return target.resolveSibling(".tethium-transfer-"+meta.path("id").asText()+".part");}
    private JsonNode beginUpload(JsonNode body)throws Exception{
        String id=body.path("id").asText();if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Нужен идентификатор передачи");
        synchronized(lock(id)){
            String hash=body.path("sha256").asText();long size=body.path("size").asLong(-1);if(size<0||!hash.matches("[a-f0-9]{64}"))throw new IOException("Нужны размер и SHA-256 файла");
            String path=body.path("path").asText();name(Path.of(path).getFileName().toString());Path target=resolve(body.path("root").asText(),path,false),record=state.resolve("uploads/"+id+".json");
            JsonNode meta;
            if(Files.exists(record)){
                meta=metadata("uploads",id);for(String key:List.of("root","path","sha256","size","replaceRevision"))if(!Objects.equals(body.path(key).asText(""),meta.path(key).asText("")))throw new IOException("Идентификатор уже относится к другому файлу");
                if(meta.path("complete").asBoolean()){
                    if(Files.isRegularFile(target)&&Files.size(target)==size&&sha(target).equals(hash))return Json.object("id",id,"offset",size,"complete",true);
                    check(target,body);meta=body.deepCopy();save(record,meta);
                }
            }else{
                check(target,body);if(size>Files.getFileStore(target.getParent()).getUsableSpace())throw new IOException("На ноутбуке недостаточно места");
                meta=body.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)meta).put("created",System.currentTimeMillis());save(record,meta);
            }
            Path part=part(meta);if(!Files.exists(part))Files.createFile(part);return Json.object("id",id,"offset",Files.size(part),"complete",false);
        }
    }
    private JsonNode finish(String id)throws Exception{synchronized(lock(id)){
        var meta=metadata("uploads",id);if(meta.path("complete").asBoolean())return Json.object("ok",true);
        Path part=part(meta),target=resolve(meta.path("root").asText(),meta.path("path").asText(),false);
        if(!Files.exists(part)&&Files.isRegularFile(target)&&Files.size(target)==meta.path("size").asLong()&&sha(target).equals(meta.path("sha256").asText())){ /* recovery after the atomic move */ }
        else{
            if(Files.size(part)!=meta.path("size").asLong()||!sha(part).equals(meta.path("sha256").asText()))throw new IOException("Контрольная сумма не совпала; передача не завершена");
            check(target,meta);Files.move(part,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode)meta).put("complete",true);save(state.resolve("uploads/"+id+".json"),meta);return Json.object("ok",true);
    }}
    /** Called only after the host/device bearer has been checked. Streams at most one MiB per request. */
    public boolean binary(HttpExchange exchange)throws Exception{
        String path=exchange.getRequestURI().getPath();
        if(path.matches(PREFIX+"/download/[a-f0-9-]{36}")&&exchange.getRequestMethod().equals("GET")){
            String id=path.substring(path.lastIndexOf('/')+1);var meta=metadata("downloads",id);Path file=meta.path("archive").asBoolean()?state.resolve("downloads/"+id+".zip"):resolve(meta.path("root").asText(),meta.path("path").asText(),true);
            if(!meta.path("archive").asBoolean()&&!rev(file).equals(meta.path("revision").asText()))throw new IOException("Файл изменился во время скачивания");
            long offset=Long.parseLong(Objects.toString(query(exchange.getRequestURI(),"offset"),"0"));int length=Integer.parseInt(Objects.toString(query(exchange.getRequestURI(),"length"),"1048576"));
            if(offset<0||offset>Files.size(file)||length<1||length>CHUNK)throw new IOException("Некорректный диапазон");
            byte[] bytes;try(var input=FileChannel.open(file,StandardOpenOption.READ)){input.position(offset);var buffer=java.nio.ByteBuffer.allocate((int)Math.min(length,Files.size(file)-offset));while(buffer.hasRemaining()&&input.read(buffer)!=-1){}bytes=Arrays.copyOf(buffer.array(),buffer.position());}
            exchange.getResponseHeaders().set("Content-Type","application/octet-stream");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,bytes.length==0?-1:bytes.length);exchange.getResponseBody().write(bytes);return true;
        }
        if(path.matches(PREFIX+"/upload/[a-f0-9-]{36}/chunk")&&exchange.getRequestMethod().equals("POST")){
            String id=path.split("/")[3];long offset=Long.parseLong(Objects.toString(query(exchange.getRequestURI(),"offset"),"-1"));byte[] bytes=exchange.getRequestBody().readNBytes(CHUNK+1);if(bytes.length==0||bytes.length>CHUNK)throw new IOException("Некорректный размер порции");
            synchronized(lock(id)){
                var meta=metadata("uploads",id);if(meta.path("complete").asBoolean())throw new IOException("Передача уже завершена");Path part=part(meta);
                try(var output=FileChannel.open(part,StandardOpenOption.WRITE)){
                    long current=output.size();if(offset<0||offset>current||offset+bytes.length>meta.path("size").asLong())throw new IOException("Некорректное смещение передачи");
                    // A repeated chunk after a lost acknowledgement safely overwrites the same range.
                    output.position(offset);var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(false);
                }
                reply(exchange,Json.object("offset",Files.size(part)));return true;
            }
        }
        return false;
    }
    private static void reply(HttpExchange e,Object body)throws IOException{byte[] bytes=Json.MAPPER.writeValueAsBytes(body);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}
    private static void save(Path path,Object data)throws IOException{Path temporary=Files.createTempFile(path.getParent(),"record-",".tmp");try{Json.MAPPER.writeValue(temporary.toFile(),data);Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temporary);}}
    public static String query(URI uri,String key){String raw=uri.getRawQuery();if(raw!=null)for(String piece:raw.split("&")){var p=piece.split("=",2);if(URLDecoder.decode(p[0],java.nio.charset.StandardCharsets.UTF_8).equals(key))return p.length==1?"":URLDecoder.decode(p[1],java.nio.charset.StandardCharsets.UTF_8);}return null;}
    public static String sha(Path path)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());}
}

package dev.tethium.resources;

import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Keeps named job handles open across host handoff and between turns while daemons remain alive. */
public final class JobKeeper {
    private JobKeeper(){}
    public static void ensure(Path data,Collection<String> names)throws Exception{
        Path folder=Files.createDirectories(data.toAbsolutePath().resolve("resource-keeper-v2"));var list=names.stream().sorted().toList();String signature=signature(list);
        write(folder.resolve("request.json"),Json.object("namespace",ResourcePolicy.namespace(data),"names",list,"signature",signature,"ownerPid",ProcessHandle.current().pid(),"ownerStart",ProcessHandle.current().info().startInstant().orElseThrow().toString()));
        JsonNode status=read(folder.resolve("ready.json"));
        if(!alive(status,"pid","start")){
            String cp=String.join(java.io.File.pathSeparator,Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).map(p->Path.of(p).toAbsolutePath().toString()).toList());
            new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javaw.exe").toString(),"--enable-native-access=ALL-UNNAMED","-cp",cp,JobKeeper.class.getName(),folder.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(folder.resolve("keeper.log").toFile())).start();
        }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do{status=read(folder.resolve("ready.json"));if(alive(status,"pid","start")&&signature.equals(status.path("signature").asText()))return;Thread.sleep(40);}while(System.nanoTime()<deadline);
        throw new IllegalStateException("Не запустился хранитель CPU-групп: "+folder.resolve("keeper.log"));
    }
    private static String signature(List<String> names){return UUID.nameUUIDFromBytes(String.join("\n",names).getBytes(StandardCharsets.UTF_8)).toString();}
    private static JsonNode read(Path file){try{return Json.MAPPER.readTree(file.toFile());}catch(Exception ignored){return Json.object();}}
    private static boolean alive(JsonNode value,String pid,String start){return value.path(pid).asLong()>0&&ProcessHandle.of(value.path(pid).asLong()).filter(ProcessHandle::isAlive).flatMap(p->p.info().startInstant()).map(t->t.toString().equals(value.path(start).asText())).orElse(false);}
    private static void write(Path file,JsonNode value)throws Exception{
        Path temp=Files.createTempFile(file.getParent(),"job-",".tmp");try{Json.MAPPER.writeValue(temp.toFile(),value);for(int i=0;;i++)try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);break;}catch(java.io.IOException e){if(i>=8)throw e;Thread.sleep(30);}}finally{Files.deleteIfExists(temp);}
    }
    public static void main(String[] args)throws Exception{
        Path folder=Path.of(args[0]);try(var file=FileChannel.open(folder.resolve("keeper.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=file.tryLock()){
            if(lock==null)return;var request=read(folder.resolve("request.json"));String namespace=request.path("namespace").asText();if(namespace.isBlank())return;
            try(var jobs=new WindowsJobs(namespace)){
                // Anchor the hierarchy in the host's existing Windows job before adopting other trees.
                // A single foreign daemon must never determine the parent of the entire CPU budget.
                jobs.retain("all");System.setProperty("tethium.worker.resources","true");jobs.attach(ProcessHandle.current().pid(),"all");
                String current="";int orphaned=0;
                while(true){request=read(folder.resolve("request.json"));if(request.path("namespace").asText().equals(namespace)){
                    String next=request.path("signature").asText();if(!next.equals(current)){for(var name:request.path("names"))jobs.retain(name.asText());current=next;write(folder.resolve("ready.json"),Json.object("pid",ProcessHandle.current().pid(),"start",ProcessHandle.current().info().startInstant().orElseThrow().toString(),"signature",current));}
                    if(!alive(request,"ownerPid","ownerStart")&&jobs.members("all").stream().allMatch(pid->pid==ProcessHandle.current().pid())){if(++orphaned>=10)return;}else orphaned=0;
                }Thread.sleep(500);}
            }
        }
    }
}

package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded asynchronous flight recorder. Never blocks Swing on disk or a full queue. */
final class ChatTraceLog implements AutoCloseable {
    private final ArrayBlockingQueue<JsonNode> queue=new ArrayBlockingQueue<>(2048);
    final AtomicLong dropped=new AtomicLong();
    private final Thread writer;
    private volatile boolean closing;
    ChatTraceLog(Path directory,String instance,long segmentBytes){
        writer=Thread.ofVirtual().name("chat-layout-log").start(()->write(directory,instance,segmentBytes));
    }
    void offer(JsonNode event){if(!closing&&!queue.offer(event))dropped.incrementAndGet();}
    private void write(Path directory,String instance,long segmentBytes){
        BufferedWriter output=null;long bytes=0;int segment=0;
        try{
            Files.createDirectories(directory);prune(directory);
            while(!closing||!queue.isEmpty()){
                JsonNode event=queue.poll(500,TimeUnit.MILLISECONDS);
                if(event==null){if(output!=null)output.flush();continue;}
                String line=event.toString();int size=line.getBytes(StandardCharsets.UTF_8).length+1;
                if(output==null||bytes+size>segmentBytes){if(output!=null)output.close();prune(directory);output=Files.newBufferedWriter(directory.resolve(instance+"-"+String.format(Locale.ROOT,"%04d",segment++)+".jsonl"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);bytes=0;}
                output.write(line);output.newLine();bytes+=size;
                if(queue.isEmpty())output.flush();
            }
        }catch(Exception e){System.err.println("Chat layout diagnostics: "+e.getClass().getSimpleName());}
        finally{if(output!=null)try{output.close();}catch(IOException ignored){}}
    }
    private static void prune(Path directory)throws IOException{
        try(var files=Files.list(directory)){
            var sorted=files.filter(p->p.getFileName().toString().endsWith(".jsonl")).sorted(Comparator.comparing(p->p.getFileName().toString())).toList();
            long total=0;for(var file:sorted)total+=Files.size(file);
            long oldest=System.currentTimeMillis()-7L*86400_000;
            for(var file:sorted){long size=Files.size(file);if(total<64L*1024*1024&&Files.getLastModifiedTime(file).toMillis()>=oldest)break;try{Files.delete(file);total-=size;}catch(IOException ignored){}}
        }
    }
    @Override public void close(){closing=true;}
    boolean awaitClosed(long millis)throws InterruptedException{writer.join(millis);return !writer.isAlive();}
}

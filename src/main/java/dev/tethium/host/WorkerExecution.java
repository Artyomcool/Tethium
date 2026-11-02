package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.*;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import dev.tethium.worker.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Reconnectable host-side observer. Closing it never terminates the worker. */
final class WorkerExecution implements AutoCloseable {
    interface Listener {
        void event(AgentAdapter.Event event);
        Object tool(JsonNode params) throws Exception;
        void question(WorkerExecution worker,long sequence,JsonNode question) throws Exception;
    }
    final SessionStore.WorkerRun run;
    private final SessionStore store;
    private final Path directory;
    private final WorkerJournal journal;
    private final Listener listener;
    private final Object hostGate;
    private final StringBuilder output=new StringBuilder();
    private String conversation;
    private long readThrough, committed;
    private Process launched;
    private int deadPolls;
    WorkerExecution(Path data,SessionStore store,SessionStore.WorkerRun run,Object hostGate,Listener listener)throws Exception{
        UUID.fromString(run.runId());
        this.store=store;this.run=run;this.listener=listener;this.hostGate=hostGate;this.committed=run.cursor();
        directory=data.resolve("workers").resolve(run.runId());
        journal=new WorkerJournal(directory);
        Path request=directory.resolve("request.json");
        if(!Files.exists(request)){
            Path temporary=directory.resolve("request.json.tmp");
            Json.MAPPER.writeValue(temporary.toFile(),run.spec());
            Files.move(temporary,request,StandardCopyOption.ATOMIC_MOVE);
        }
    }
    private void launch()throws Exception{
        if(journal.state("claimed")!=null || launched!=null&&launched.isAlive())return;
        String classpath=System.getProperty("tethium.test.classpath",System.getProperty("java.class.path"));
        classpath=String.join(File.pathSeparator,Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))).map(p->Path.of(p).toAbsolutePath().toString()).toList());
        launched=new ProcessBuilder(AppPaths.javaExecutable(true),"--enable-native-access=ALL-UNNAMED","-cp",classpath,WorkerMain.class.getName(),directory.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("worker.log").toFile())).start();
    }
    /** Returns null when the host detaches; the durable run remains active for its successor. */
    AgentAdapter.Result await(BooleanSupplier detach,BooleanSupplier mayLaunch)throws Exception{
        while(!detach.getAsBoolean()){
            if(mayLaunch.getAsBoolean())launch();
            var page=journal.after(readThrough);
            for(var entry:page){
                if(detach.getAsBoolean())return null;
                var value=entry.value();long sequence=entry.sequence();
                switch(entry.kind()){
                    case "event" -> {
                        var event=new AgentAdapter.Event(value.path("type").asText(),value.path("text").asText(null),value.path("raw"));
                        if(sequence>committed){store.transaction(()->{store.recordEvent(run.runId(),run.sessionId(),event);store.workerCursor(run.runId(),sequence);return null;});committed=sequence;}
                        if(event.type().equals("text"))output.append(event.text());
                        if(event.type().equals("conversation"))conversation=event.text();
                        listener.event(event);
                    }
                    case "tool" -> {
                        synchronized(hostGate){
                        JsonNode reply=store.workerReply(run.runId(),sequence);
                        if(reply==null){
                            try{reply=store.transaction(()->{var result=Json.MAPPER.valueToTree(listener.tool(value));store.workerReply(run.runId(),sequence,result);return result;});}
                            catch(Exception e){
                                reply=store.workerReply(run.runId(),sequence);
                                if(reply==null){reply=Json.object("content",List.of(Json.object("type","text","text",Objects.toString(e.getMessage(),e.toString()))),"isError",true);store.workerReply(run.runId(),sequence,reply);}
                            }
                        }
                        journal.reply(sequence,reply);
                        }
                    }
                    case "question" -> {
                        var reply=store.workerReply(run.runId(),sequence);
                        if(reply!=null)journal.reply(sequence,reply);else listener.question(this,sequence,value);
                    }
                    case "result" -> {return new AgentAdapter.Result(value.path("conversationId").asText(null),AgentAdapter.Status.valueOf(value.path("status").asText()),value.path("text").asText());}
                    case "failure" -> {return failed(value.path("message").asText(),value.path("cancelled").asBoolean());}
                    default -> throw new IllegalStateException("Unsupported worker journal event: "+entry.kind());
                }
                readThrough=sequence;
            }
            if(page.isEmpty()&&journal.state("claimed")!=null&&!journal.alive()){
                // Re-read after observing process death: its final commit may follow our previous read.
                if(++deadPolls>=3)return failed("Процесс исполнителя завершился без результата. Повторного запуска не было.","true".equals(journal.state("cancel")));
            }else deadPolls=0;
            Thread.sleep(100);
        }return null;
    }
    private AgentAdapter.Result failed(String message,boolean cancelled){
        return new AgentAdapter.Result(conversation,cancelled?AgentAdapter.Status.CANCELLED:AgentAdapter.Status.FAILED,
                output+(output.isEmpty()?"":"\n\n")+message);
    }
    void answer(long sequence,JsonNode answer)throws Exception{
        store.workerReply(run.runId(),sequence,answer);
        journal.reply(sequence,store.workerReply(run.runId(),sequence));
    }
    CpuSampler.Root cpuRoot(String project)throws Exception{
        String pid=journal.state("pid"),start=journal.state("processStart");
        return pid==null||start==null?null:new CpuSampler.Root(Long.parseLong(pid),java.time.Instant.parse(start).toEpochMilli(),new CpuSampler.Owner(run.sessionId(),project));
    }
    void cancel()throws Exception{journal.state("cancel","true");}
    @Override public void close()throws Exception{journal.close();}
}

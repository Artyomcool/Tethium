package dev.tethium.host;

import dev.tethium.core.UsageLedger;
import dev.tethium.json.Json;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Samples cumulative process CPU, including descendants and connected reusable Gradle daemons. */
final class CpuSampler implements AutoCloseable {
    record Owner(String session,String project) {}
    record Root(long pid,long started,Owner owner) {}
    record Sample(long pid,long parent,long started,long nanos,String command) {
        String key(){return pid+":"+started;}
        boolean daemon(){return command.contains("org.gradle.launcher.daemon.bootstrap.GradleDaemon");}
        boolean client(){return command.contains("GradleWrapperMain")||command.contains("gradle-wrapper.jar")||command.contains("org.gradle.launcher.GradleMain");}
    }
    record Link(long left,long right) {}
    record Assignment(Owner owner,boolean shared) {}
    private final UsageLedger ledger;
    private final Supplier<List<Root>> roots;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final Map<String,Assignment> retained=new HashMap<>();
    private long previous=System.currentTimeMillis();
    CpuSampler(UsageLedger ledger,Supplier<List<Root>> roots)throws Exception{
        this.ledger=ledger;this.roots=roots;
        for(var p:ledger.cpuProcesses())if(!p.path("session").isNull())retained.put(p.path("key").asText(),new Assignment(new Owner(p.path("session").asText(),p.path("project").asText()),p.path("kind").asText().equals("shared")));
        timer.scheduleWithFixedDelay(()->{try{sample();}catch(Exception e){System.err.println("CPU sampling: "+e.getMessage());}},0,2,TimeUnit.SECONDS);
    }
    static Map<Long,Assignment> attribute(List<Sample> samples,List<Root> roots,Map<String,Assignment> retained,List<Link> links){
        var byPid=new HashMap<Long,Sample>();samples.forEach(p->byPid.put(p.pid,p));var owners=new HashMap<Long,Assignment>();var shared=new HashSet<Long>();
        for(var p:samples){if(p.daemon())shared.add(p.pid);var old=retained.get(p.key());if(old!=null){if(old.shared)shared.add(p.pid);else owners.put(p.pid,old);}}
        boolean changed;do{changed=false;for(var p:samples)if(shared.contains(p.parent))changed|=shared.add(p.pid);}while(changed);
        shared.forEach(owners::remove);
        for(var root:roots){var p=byPid.get(root.pid);if(p!=null&&p.started==root.started)owners.put(p.pid,new Assignment(root.owner,false));}
        propagate(samples,owners,shared);
        for(var daemon:samples)if(daemon.daemon()){
            var candidates=new HashSet<Owner>();
            for(var link:links){long other=link.left==daemon.pid?link.right:link.right==daemon.pid?link.left:-1;var client=byPid.get(other);var owner=owners.get(other);if(client!=null&&client.client()&&owner!=null)candidates.add(owner.owner);}
            if(candidates.size()==1)owners.put(daemon.pid,new Assignment(candidates.iterator().next(),true));
        }
        var daemons=new HashSet<Long>();for(var p:samples)if(p.daemon())daemons.add(p.pid);propagate(samples,owners,daemons);return owners;
    }
    private static void propagate(List<Sample> samples,Map<Long,Assignment> owners,Set<Long> excluded){
        boolean changed;do{changed=false;for(var p:samples)if(!excluded.contains(p.pid)&&!owners.containsKey(p.pid)&&owners.containsKey(p.parent)){owners.put(p.pid,owners.get(p.parent));changed=true;}}while(changed);
    }
    private void sample()throws Exception{
        var samples=snapshot();long now=System.currentTimeMillis();
        var links=samples.stream().anyMatch(Sample::daemon)?connections(command("netstat.exe","-ano","-p","tcp")):List.<Link>of();
        var assigned=attribute(samples,roots.get(),retained,links);var liveKeys=new HashSet<String>();
        for(var p:samples){liveKeys.add(p.key());var a=assigned.get(p.pid);if(a!=null||p.daemon()||retained.containsKey(p.key()))ledger.cpuSample(p.key(),p.pid,p.started,a==null?null:a.owner.session,a==null?null:a.owner.project,p.nanos,now,a!=null&&a.shared||p.daemon()?"shared":"child",p.started>=previous);}
        retained.clear();for(var p:samples){var a=assigned.get(p.pid);if(a!=null)retained.put(p.key(),a);else if(p.daemon())retained.put(p.key(),new Assignment(null,true));}
        previous=now;
    }
    private static List<Sample> snapshot()throws Exception{
        var samples=new ArrayList<Sample>();
        if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")){
            // One native WMI snapshot avoids hundreds of slow per-process command-line lookups on Windows.
            // Only Java command lines are inspected, and none are persisted or logged.
            String output=command("powershell.exe","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-Command",
                "[Console]::OutputEncoding=[Text.UTF8Encoding]::new(); @(Get-CimInstance Win32_Process | Where-Object {$null -ne $_.CreationDate} | Select-Object @{n='pid';e={$_.ProcessId}},@{n='parent';e={$_.ParentProcessId}},@{n='started';e={([DateTimeOffset]$_.CreationDate).ToUnixTimeMilliseconds()}},@{n='cpu';e={([long]$_.KernelModeTime+[long]$_.UserModeTime)*100}},@{n='command';e={if($_.Name -match '^javaw?\\.exe$'){$_.CommandLine}else{$_.Name}}}) | ConvertTo-Json -Compress");
            if(output.isBlank())throw new IllegalStateException("Windows process snapshot timed out");
            for(var row:Json.parse(output))samples.add(new Sample(row.path("pid").asLong(),row.path("parent").asLong(),row.path("started").asLong(),row.path("cpu").asLong(),row.path("command").asText("")));
        }else{
            for(var p:ProcessHandle.allProcesses().toList()){var info=p.info();if(info.startInstant().isPresent()&&info.totalCpuDuration().isPresent())samples.add(new Sample(p.pid(),p.parent().map(ProcessHandle::pid).orElse(-1L),info.startInstant().get().toEpochMilli(),info.totalCpuDuration().get().toNanos(),info.commandLine().orElse("")));}
        }
        return samples;
    }
    static List<Link> connections(String output){
        record Socket(String local,String remote,long pid){}var sockets=new HashMap<String,Socket>();var links=new ArrayList<Link>();
        for(String line:output.lines().toList()){var a=line.strip().split("\\s+");if(a.length<5||!a[0].equalsIgnoreCase("TCP")||!(a[1].startsWith("127.")||a[1].startsWith("[::1]"))||!(a[2].startsWith("127.")||a[2].startsWith("[::1]")))continue;try{var socket=new Socket(a[1],a[2],Long.parseLong(a[4]));var reverse=sockets.get(socket.remote+"|"+socket.local);if(reverse!=null&&reverse.pid!=socket.pid)links.add(new Link(reverse.pid,socket.pid));sockets.put(socket.local+"|"+socket.remote,socket);}catch(NumberFormatException ignored){}}
        return links;
    }
    private static String command(String...args)throws Exception{
        var process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();var read=CompletableFuture.supplyAsync(()->{try{return new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){return "";}});
        try{if(!process.waitFor(4,TimeUnit.SECONDS))return "";return read.get(1,TimeUnit.SECONDS);}finally{if(process.isAlive())process.destroyForcibly();}
    }
    @Override public void close()throws InterruptedException{timer.shutdownNow();timer.awaitTermination(6,TimeUnit.SECONDS);}
}

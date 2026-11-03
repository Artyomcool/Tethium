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
    record Sample(long pid,long parent,long started,long nanos,String command,long memory) {
        Sample(long pid,long parent,long started,long nanos,String command){this(pid,parent,started,nanos,command,-1);}
        String key(){return pid+":"+started;}
        boolean daemon(){return command.contains("org.gradle.launcher.daemon.bootstrap.GradleDaemon");}
        boolean client(){return command.contains("GradleWrapperMain")||command.contains("gradle-wrapper.jar")||command.contains("org.gradle.launcher.GradleMain");}
    }
    record Link(long left,long right) {}
    record Assignment(Owner owner,boolean shared) {}
    private final UsageLedger ledger;
    private final Object writeGate;
    private final ResourceController resources;
    private final Supplier<List<Root>> roots;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final Map<String,Assignment> retained=new HashMap<>();
    private volatile List<com.fasterxml.jackson.databind.JsonNode> restartInventory=List.of();
    List<com.fasterxml.jackson.databind.JsonNode> restartInventory(){return restartInventory;}
    private long previous=System.currentTimeMillis();
    private final MachineUsage machine=new MachineUsage();
    com.fasterxml.jackson.databind.JsonNode machineUsage(){return machine.snapshot();}
    CpuSampler(UsageLedger ledger,Supplier<List<Root>> roots)throws Exception{this(ledger,roots,new Object());}
    CpuSampler(UsageLedger ledger,Supplier<List<Root>> roots,Object writeGate)throws Exception{
        this(ledger,roots,writeGate,null);
    }
    CpuSampler(UsageLedger ledger,Supplier<List<Root>> roots,Object writeGate,ResourceController resources)throws Exception{
        this.ledger=ledger;this.roots=roots;this.writeGate=writeGate;this.resources=resources;
        for(var p:ledger.cpuProcesses())if(!p.path("session").isNull())retained.put(p.path("key").asText(),new Assignment(new Owner(p.path("session").asText(),p.path("project").asText()),p.path("kind").asText().equals("shared")));
        timer.scheduleWithFixedDelay(()->{try{sample();}catch(Exception e){System.err.println("CPU sampling: "+e.getMessage());}},0,2,TimeUnit.SECONDS);
    }
    static Map<Long,Assignment> attribute(List<Sample> samples,List<Root> roots,Map<String,Assignment> retained,List<Link> links){
        samples=workloads(samples,roots);
        var byPid=new HashMap<Long,Sample>();samples.forEach(p->byPid.put(p.pid,p));var owners=new HashMap<Long,Assignment>();var shared=new HashSet<Long>();
        for(var p:samples){if(p.daemon())shared.add(p.pid);var old=retained.get(p.key());if(old!=null){if(old.shared)shared.add(p.pid);else owners.put(p.pid,old);}}
        boolean changed;do{changed=false;for(var p:samples)if(shared.contains(p.parent)&&byPid.containsKey(p.parent)&&byPid.get(p.parent).started<=p.started)changed|=shared.add(p.pid);}while(changed);
        shared.forEach(owners::remove);
        var fixed=new HashSet<Long>();for(var root:roots){var p=byPid.get(root.pid);if(p!=null&&p.started==root.started){owners.put(p.pid,new Assignment(root.owner,false));fixed.add(p.pid);shared.remove(p.pid);}}
        propagate(samples,owners,shared);
        for(var daemon:samples)if(daemon.daemon()&&!fixed.contains(daemon.pid)){
            var candidates=new HashSet<Owner>();
            for(var link:links){long other=link.left==daemon.pid?link.right:link.right==daemon.pid?link.left:-1;var client=byPid.get(other);var owner=owners.get(other);if(client!=null&&client.client()&&owner!=null)candidates.add(owner.owner);}
            if(candidates.size()==1)owners.put(daemon.pid,new Assignment(candidates.iterator().next(),true));
        }
        var daemons=new HashSet<Long>();for(var p:samples)if(p.daemon())daemons.add(p.pid);propagate(samples,owners,daemons);return owners;
    }
    // Updaters, GUI and the host must not inherit the requesting session's accounting or limits.
    private static List<Sample> workloads(List<Sample> samples,List<Root> roots){
        var explicit=new HashSet<Long>();for(var root:roots)for(var sample:samples)if(sample.pid==root.pid&&sample.started==root.started)explicit.add(sample.pid);
        var excluded=new HashSet<Long>();for(var sample:samples){String c=sample.command.toLowerCase(Locale.ROOT);if(c.equals("tethium.exe")||c.contains("dev.tethium.host.hostmain")||c.contains("dev.tethium.desktop.desktopmain")||c.contains("dev.tethium.resources.jobkeeper")||c.contains("request_desktop_update.ps1")||c.contains("reload_desktop.ps1"))excluded.add(sample.pid);}
        boolean changed;do{changed=false;for(var sample:samples)if(excluded.contains(sample.parent)&&!explicit.contains(sample.pid))changed|=excluded.add(sample.pid);}while(changed);
        return samples.stream().filter(s->!excluded.contains(s.pid)).toList();
    }
    private static void propagate(List<Sample> samples,Map<Long,Assignment> owners,Set<Long> excluded){
        var parents=new HashMap<Long,Sample>();samples.forEach(p->parents.put(p.pid,p));
        boolean changed;do{changed=false;for(var p:samples)if(parents.containsKey(p.parent)&&parents.get(p.parent).started<=p.started&&!excluded.contains(p.pid)&&!owners.containsKey(p.pid)&&owners.containsKey(p.parent)){owners.put(p.pid,owners.get(p.parent));changed=true;}}while(changed);
    }
    private void sample()throws Exception{
        var samples=snapshot();long now=System.currentTimeMillis();
        var links=samples.stream().anyMatch(Sample::daemon)?connections(command("netstat.exe","-ano")):List.<Link>of();
        var allRoots=new ArrayList<>(roots.get());if(resources!=null)allRoots.addAll(resources.roots(samples));var assigned=attribute(samples,allRoots,retained,links);if(resources!=null)resources.observe(samples,assigned);
        var os=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if(os instanceof com.sun.management.OperatingSystemMXBean system)machine.sample(samples,assigned,system.getCpuLoad(),system.getTotalMemorySize(),system.getFreeMemorySize(),now,system.getAvailableProcessors());
        var inventory=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();for(var p:samples){var a=assigned.get(p.pid);if(a!=null&&a.owner()!=null)inventory.add(Json.object("pid",p.pid,"started",p.started,"session",a.owner().session(),"command",p.command));}restartInventory=List.copyOf(inventory);
        var liveKeys=new HashSet<String>();
        for(var p:samples){liveKeys.add(p.key());var a=assigned.get(p.pid);if(a!=null||p.daemon()||retained.containsKey(p.key()))synchronized(writeGate){ledger.cpuSample(p.key(),p.pid,p.started,a==null?null:a.owner.session,a==null?null:a.owner.project,p.nanos,now,a!=null?a.shared?"shared":"child":p.daemon()?"shared":"child",p.started>=previous);}}
        retained.clear();for(var p:samples){var a=assigned.get(p.pid);if(a!=null)retained.put(p.key(),a);else if(p.daemon())retained.put(p.key(),new Assignment(null,true));}
        previous=now;
    }
    private static List<Sample> snapshot()throws Exception{
        var samples=new ArrayList<Sample>();
        if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")){
            // One native WMI snapshot avoids hundreds of slow per-process command-line lookups on Windows.
            // Only Java command lines are inspected, and none are persisted or logged.
            String output=command("powershell.exe","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-Command",
                "[Console]::OutputEncoding=[Text.UTF8Encoding]::new(); $mem=@{}; Get-CimInstance Win32_PerfFormattedData_PerfProc_Process | ForEach-Object {$mem[[long]$_.IDProcess]=[long]$_.WorkingSetPrivate}; @(Get-CimInstance Win32_Process | Where-Object {$null -ne $_.CreationDate} | Select-Object @{n='pid';e={$_.ProcessId}},@{n='parent';e={$_.ParentProcessId}},@{n='started';e={([DateTimeOffset]$_.CreationDate).ToUnixTimeMilliseconds()}},@{n='cpu';e={([long]$_.KernelModeTime+[long]$_.UserModeTime)*100}},@{n='memory';e={if($mem.ContainsKey([long]$_.ProcessId)){$mem[[long]$_.ProcessId]}else{-1}}},@{n='command';e={if($_.Name -match '^(javaw?|powershell|pwsh)\\.exe$'){$_.CommandLine}else{$_.Name}}}) | ConvertTo-Json -Compress");
            if(output.isBlank())throw new IllegalStateException("Windows process snapshot timed out");
            for(var row:Json.parse(output))samples.add(new Sample(row.path("pid").asLong(),row.path("parent").asLong(),row.path("started").asLong(),row.path("cpu").asLong(),row.path("command").asText(""),row.path("memory").asLong(-1)));
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

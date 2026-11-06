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
    private long previous=System.currentTimeMillis(),flushed=previous;
    private record Seen(long nanos,Owner owner){}
    private Map<String,Seen> seen=new HashMap<>();
    private final Map<Owner,Long> pending=new HashMap<>();
    private final WindowsProcesses windows=WindowsProcesses.supported()?new WindowsProcesses():null;
    private Map<String,String> commands=new HashMap<>();
    private static final int INTERVAL_SECONDS=5;
    private static final long FLUSH_MILLIS=30_000;
    private static final java.util.regex.Pattern INSPECTED=java.util.regex.Pattern.compile("(javaw?|powershell|pwsh)\\.exe",java.util.regex.Pattern.CASE_INSENSITIVE);
    private final MachineUsage machine=new MachineUsage();
    com.fasterxml.jackson.databind.JsonNode machineUsage(){return machine.snapshot();}
    CpuSampler(UsageLedger ledger,Supplier<List<Root>> roots)throws Exception{this(ledger,roots,new Object());}
    CpuSampler(UsageLedger ledger,Supplier<List<Root>> roots,Object writeGate)throws Exception{
        this(ledger,roots,writeGate,null);
    }
    CpuSampler(UsageLedger ledger,Supplier<List<Root>> roots,Object writeGate,ResourceController resources)throws Exception{
        this.ledger=ledger;this.roots=roots;this.writeGate=writeGate;this.resources=resources;
        timer.scheduleWithFixedDelay(()->{try{sample();}catch(Exception e){System.err.println("CPU sampling: "+e.getMessage());}},0,INTERVAL_SECONDS,TimeUnit.SECONDS);
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
    synchronized void sample()throws Exception{
        var samples=snapshot();long now=System.currentTimeMillis();
        var links=windows!=null&&samples.stream().anyMatch(Sample::daemon)?connections(windows.loopback()):List.<Link>of();
        var allRoots=new ArrayList<>(roots.get());if(resources!=null)allRoots.addAll(resources.roots(samples));var assigned=attribute(samples,allRoots,retained,links);if(resources!=null)resources.observe(samples,assigned);
        var os=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if(os instanceof com.sun.management.OperatingSystemMXBean system)machine.sample(samples,assigned,system.getCpuLoad(),system.getTotalMemorySize(),system.getFreeMemorySize(),now,system.getAvailableProcessors());
        var inventory=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();for(var p:samples){var a=assigned.get(p.pid);if(a!=null&&a.owner()!=null)inventory.add(Json.object("pid",p.pid,"started",p.started,"session",a.owner().session(),"command",p.command));}restartInventory=List.copyOf(inventory);
        var next=new HashMap<String,Seen>(samples.size()*2);
        synchronized(pending){for(var p:samples){
            var a=assigned.get(p.pid);var owner=a==null?null:a.owner;var old=seen.get(p.key());next.put(p.key(),new Seen(p.nanos,owner));
            // A shared daemon changing owners between observations charges nobody rather than guessing the boundary.
            long delta=old==null?p.started>=previous?p.nanos:0:old.owner==null||old.owner.equals(owner)?Math.max(0,p.nanos-old.nanos):0;
            if(owner!=null&&delta>0)pending.merge(owner,delta,Long::sum);
        }}
        seen=next;
        retained.clear();for(var p:samples){var a=assigned.get(p.pid);if(a!=null)retained.put(p.key(),a);else if(p.daemon())retained.put(p.key(),new Assignment(null,true));}
        previous=now;
        if(now-flushed>=FLUSH_MILLIS){flushed=now;flush();}
    }
    /** Sums reach the database in small batches: a finer view is only needed for the live header. */
    void flush(){
        Map<Owner,Long> batch;synchronized(pending){batch=new HashMap<>(pending);pending.clear();}
        for(var e:batch.entrySet())try{synchronized(writeGate){ledger.cpuAdd(e.getKey().session,e.getKey().project,e.getValue());}}catch(Exception failure){synchronized(pending){pending.merge(e.getKey(),e.getValue(),Long::sum);}System.err.println("CPU totals: "+failure.getMessage());}
    }
    private List<Sample> snapshot(){
        var samples=new ArrayList<Sample>();
        if(windows!=null){
            // Only Java and PowerShell command lines are read, once per process; none are persisted or logged.
            var known=new HashMap<String,String>();
            for(var row:windows.snapshot()){
                String command=row.image(),key=row.pid()+":"+row.started();
                if(INSPECTED.matcher(command).matches()){String line=commands.get(key);if(line==null){line=windows.commandLine(row.pid());if(line==null)line=command;}known.put(key,line);command=line;}
                samples.add(new Sample(row.pid(),row.parent(),row.started(),row.nanos(),command,row.memory()));
            }
            commands=known;
        }else{
            for(var p:ProcessHandle.allProcesses().toList()){var info=p.info();if(info.startInstant().isPresent()&&info.totalCpuDuration().isPresent())samples.add(new Sample(p.pid(),p.parent().map(ProcessHandle::pid).orElse(-1L),info.startInstant().get().toEpochMilli(),info.totalCpuDuration().get().toNanos(),info.commandLine().orElse("")));}
        }
        return samples;
    }
    static List<Link> connections(List<WindowsProcesses.Socket> sockets){
        var open=new HashMap<String,WindowsProcesses.Socket>();var links=new ArrayList<Link>();
        for(var socket:sockets){var reverse=open.get(socket.remote()+"|"+socket.local());if(reverse!=null&&reverse.pid()!=socket.pid())links.add(new Link(reverse.pid(),socket.pid()));open.put(socket.local()+"|"+socket.remote(),socket);}
        return links;
    }
    @Override public void close()throws InterruptedException{timer.shutdownNow();timer.awaitTermination(6,TimeUnit.SECONDS);flush();}
}

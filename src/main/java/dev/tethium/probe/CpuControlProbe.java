package dev.tethium.probe;

import dev.tethium.resources.*;
import dev.tethium.json.Json;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A short isolated native cap/handoff check. Does not attach or terminate application processes. */
public final class CpuControlProbe {
    private static volatile long blackHole;
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("burn")){
            System.out.println("READY");System.out.flush();System.in.read();var active=new AtomicBoolean(true);var threads=new ArrayList<Thread>();for(int i=0;i<2;i++)threads.add(Thread.ofPlatform().start(()->{long n=1;while(active.get()){n=n*1664525+1013904223;blackHole=n;}}));System.in.read();active.set(false);for(var thread:threads)thread.join();return;
        }
        Path data=Files.createDirectories(Path.of(".local/cpu-control/native-"+UUID.randomUUID()).toAbsolutePath());String namespace=ResourcePolicy.namespace(data);JobKeeper.ensure(data,List.of("all","project","session"));String javaExe=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        var child=new ProcessBuilder(javaExe,"-cp",System.getProperty("java.class.path"),CpuControlProbe.class.getName(),"burn").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try{
            if(!child.inputReader().readLine().equals("READY"))throw new AssertionError("Child not ready");
            long before,after;
            try(var jobs=new WindowsJobs(namespace)){jobs.configure("disabled",0,0x20,false);if(jobs.rate("disabled")!=0)throw new AssertionError("Disabled empty job");jobs.configure("all",500,-1,false);jobs.configure("project",5000,-1,false);jobs.configure("session",2000,0x4000,false);jobs.attach(child.pid(),"all","project","session");if(!jobs.members("all").contains(child.pid())||jobs.members("all").size()<2)throw new AssertionError("Child cannot share keeper hierarchy");before=jobs.cpuNanos("session");}
            // No controller handles remain. The worker and its limits must survive this handoff.
            if(!child.isAlive())throw new AssertionError("Closing controller killed worker");
            try(var jobs=new WindowsJobs(namespace)){
                if(jobs.rate("all")!=500||jobs.rate("session")!=2000)throw new AssertionError("Limits lost across handoff");
                long start=System.nanoTime();child.getOutputStream().write(1);child.getOutputStream().flush();Thread.sleep(2500);after=jobs.cpuNanos("session");double elapsed=(System.nanoTime()-start)/1e9,seconds=(after-before)/1e9;
                double ceiling=elapsed*Runtime.getRuntime().availableProcessors()*.005;
                if(seconds>ceiling*2+.2||seconds<.01)throw new AssertionError("CPU cap outside tolerance: "+seconds+", expected <= "+ceiling);
                jobs.configure("all",0,-1,false);jobs.configure("project",0,-1,false);jobs.configure("session",0,0x20,false);
                child.getOutputStream().write(1);child.getOutputStream().flush();if(!child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Child did not stop");
                Path out=Files.createDirectories(Path.of(".local/cpu-control"));Json.MAPPER.writeValue(out.resolve("native-report.json").toFile(),Json.object("passed",true,"cpuSeconds",seconds,"wallSeconds",elapsed,"expectedCpuSeconds",ceiling,"handoffPreserved",true));System.out.println("CPU_CONTROL_PASSED "+seconds+" CPU s / "+elapsed+" wall s");
            }
        }finally{if(child.isAlive())child.destroyForcibly();}
    }
}

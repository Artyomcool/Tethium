package dev.tethium.host;

import dev.tethium.core.UsageLedger;
import dev.tethium.json.Json;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Two real builds, one reused daemon, separate session accounting. No agent/model calls. */
public final class GradleCpuProbe {
    public static void main(String[] args)throws Exception{
        Path out=Files.createDirectories(Path.of(".local/gradle-cpu-probe/"+UUID.randomUUID()).toAbsolutePath());
        Path project=Files.createDirectories(out.resolve("project"));
        Files.writeString(project.resolve("settings.gradle"),"rootProject.name='cpu-probe'\n");
        Files.writeString(project.resolve("build.gradle"),"tasks.register('burn') { doLast { println('DAEMON_PID='+ProcessHandle.current().pid()); def end=System.nanoTime()+12_000_000_000L; double n=1; while(System.nanoTime()<end){n=Math.sin(n)+1}; println(n) } }\n");
        var roots=new AtomicReference<List<CpuSampler.Root>>(List.of());ProcessHandle daemon=null;java.time.Instant daemonStart=null;
        try(var ledger=new UsageLedger(out.resolve("cpu.db"));var sampler=new CpuSampler(ledger,roots::get)){
            long expected=-1;var totals=new LinkedHashMap<String,Long>();
            for(String session:List.of("session-a","session-b")){
                Path log=out.resolve(session+".log");
                var process=new ProcessBuilder(AppPaths.javaExecutable(false),"-cp",Path.of("gradle/wrapper/gradle-wrapper.jar").toAbsolutePath().toString(),"org.gradle.wrapper.GradleWrapperMain","-p",project.toString(),"burn","--daemon","--console=plain","-Dorg.gradle.jvmargs=-Xmx256m -Dtethium.cpu.probe="+out.getFileName()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                roots.set(List.of(new CpuSampler.Root(process.pid(),process.info().startInstant().orElseThrow().toEpochMilli(),new CpuSampler.Owner(session,"probe-project"))));
                try{if(!process.waitFor(90,TimeUnit.SECONDS))throw new AssertionError("Gradle timed out");if(process.exitValue()!=0)throw new AssertionError(Files.readString(log));}finally{roots.set(List.of());if(process.isAlive())process.destroyForcibly();}
                var matcher=java.util.regex.Pattern.compile("DAEMON_PID=(\\d+)").matcher(Files.readString(log));if(!matcher.find())throw new AssertionError("Missing daemon PID");long pid=Long.parseLong(matcher.group(1));
                if(expected<0){expected=pid;daemon=ProcessHandle.of(pid).orElseThrow();daemonStart=daemon.info().startInstant().orElseThrow();}else if(expected!=pid)throw new AssertionError("Gradle did not reuse its daemon");
                try(var db=DriverManager.getConnection("jdbc:sqlite:"+out.resolve("cpu.db"));var q=db.prepareStatement("SELECT COALESCE(SUM(nanos),0) FROM cpu_totals WHERE session_id=?")){q.setString(1,session);try(var r=q.executeQuery()){r.next();long cpu=r.getLong(1);if(cpu<=0)throw new AssertionError("Reused daemon CPU not attributed to "+session);totals.put(session,cpu);}}
                Thread.sleep(2600);
            }
            Json.MAPPER.writeValue(out.resolve("report.json").toFile(),Json.object("passed",true,"sameDaemonPid",expected,"daemonCpuNanosBySession",totals));System.out.println("GRADLE_CPU_PROBE_PASSED "+out);
        }finally{if(daemon!=null&&daemon.isAlive()&&daemon.info().startInstant().filter(daemonStart::equals).isPresent())daemon.destroy();}
    }
}

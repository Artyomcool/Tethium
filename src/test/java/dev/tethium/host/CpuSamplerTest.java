package dev.tethium.host;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CpuSamplerTest {
    public static void main(String[] args){long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);double value=1;while(System.nanoTime()<end)value=Math.sin(value)+1;System.out.println(value);}
    @Test void measuresRealProcessCpuAndContinuesFromPersistedBaseline(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception{
        var process=new ProcessBuilder(AppPaths.javaExecutable(false),"-cp",System.getProperty("tethium.test.classpath"),CpuSamplerTest.class.getName()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try(var ledger=new dev.tethium.core.UsageLedger(dir.resolve("cpu.db"))){
            var root=new CpuSampler.Root(process.pid(),process.info().startInstant().orElseThrow().toEpochMilli(),new CpuSampler.Owner("s","p"));
            try(var sampler=new CpuSampler(ledger,()->List.of(root))){awaitCpu(ledger,0,process);}
            long first=ledger.report("all","","").path("cpu").path("totalNanos").asLong();assertTrue(first>0,"No real CPU observed");
            try(var sampler=new CpuSampler(ledger,()->List.of(root))){awaitCpu(ledger,first,process);}
            long second=ledger.report("all","","").path("cpu").path("totalNanos").asLong();assertTrue(second>first);assertTrue(second<=process.info().totalCpuDuration().orElseThrow().toNanos(),"CPU counted twice across sampler replacement");
        }finally{process.destroyForcibly();process.waitFor();}
    }
    private static void awaitCpu(dev.tethium.core.UsageLedger ledger,long previous,Process process)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){assertTrue(process.isAlive(),"CPU fixture failed: "+new String(process.getErrorStream().available()>0?process.getErrorStream().readNBytes(process.getErrorStream().available()):new byte[0]));if(ledger.report("all","","").path("cpu").path("totalNanos").asLong()>previous)return;Thread.sleep(100);}throw new AssertionError("No CPU delta: "+ledger.cpuProcesses());}
    @Test void descendantsAndReusedDaemonFollowCurrentClientNotOriginalParent(){
        var a=new CpuSampler.Owner("a","p");var b=new CpuSampler.Owner("b","q");
        var rootA=new CpuSampler.Sample(1,0,1,10,"worker");var rootB=new CpuSampler.Sample(2,0,2,10,"worker");
        var client=new CpuSampler.Sample(3,2,3,10,"GradleWrapperMain");var daemon=new CpuSampler.Sample(4,1,4,10,"org.gradle.launcher.daemon.bootstrap.GradleDaemon");var test=new CpuSampler.Sample(5,4,5,10,"java test");
        var samples=List.of(rootA,rootB,client,daemon,test);var roots=List.of(new CpuSampler.Root(1,1,a),new CpuSampler.Root(2,2,b));
        var owners=CpuSampler.attribute(samples,roots,Map.of("4:4",new CpuSampler.Assignment(a,true)),List.of(new CpuSampler.Link(3,4)));
        assertEquals(b,owners.get(4L).owner());assertEquals(b,owners.get(5L).owner());assertTrue(owners.get(5L).shared());
        owners=CpuSampler.attribute(samples,roots,Map.of("5:5",new CpuSampler.Assignment(b,true)),List.of());assertFalse(owners.containsKey(4L));assertFalse(owners.containsKey(5L));
        assertTrue(CpuSampler.attribute(List.of(rootA),List.of(new CpuSampler.Root(1,999,a)),Map.of(),List.of()).isEmpty());
    }
    @Test void reusedParentPidDoesNotCaptureAnOlderOrphan(){
        var parent=new CpuSampler.Sample(1,0,100,10,"worker");var orphan=new CpuSampler.Sample(2,1,50,10,"java test");
        var owners=CpuSampler.attribute(List.of(parent,orphan),List.of(new CpuSampler.Root(1,100,new CpuSampler.Owner("s","p"))),Map.of(),List.of());assertFalse(owners.containsKey(2L));
    }
    @Test void ipv6GradleConnectionIsRecognized(){
        assertEquals(List.of(new CpuSampler.Link(3,4)),CpuSampler.connections(" TCP [::1]:123 [::1]:456 ESTABLISHED 3\n TCP [::1]:456 [::1]:123 ESTABLISHED 4"));
    }
    @Test void loopbackConnectionsRequireMatchingEndpoints(){
        var edges=CpuSampler.connections(" TCP 127.0.0.1:123 127.0.0.1:456 ESTABLISHED 3\n TCP 127.0.0.1:456 127.0.0.1:123 ESTABLISHED 4\n TCP 10.0.0.1:42 10.0.0.2:43 ESTABLISHED 8");assertEquals(List.of(new CpuSampler.Link(3,4)),edges);
    }
}

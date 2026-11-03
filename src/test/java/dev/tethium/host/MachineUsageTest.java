package dev.tethium.host;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MachineUsageTest {
    @Test void countsOwnedProcessesAndGuiChildrenOnceAndSeparatesRest(){
        var usage=new MachineUsage();var initial=List.of(new CpuSampler.Sample(1,0,10,0,"worker",100),new CpuSampler.Sample(2,0,10,0,"java dev.tethium.desktop.DesktopMain",200),new CpuSampler.Sample(3,2,20,0,"jcef",300),new CpuSampler.Sample(4,0,10,0,"other",400));
        var assigned=Map.of(1L,assignment("s1","p"),3L,assignment("s2","p"));
        usage.sample(initial,assigned,.8,2000,500,1000,2);assertTrue(usage.snapshot().path("cpuOurs").isNull());
        var next=initial.stream().map(p->new CpuSampler.Sample(p.pid(),p.parent(),p.started(),p.pid()<=2?500_000_000:0,p.command(),p.memory())).toList();
        usage.sample(next,assigned,.8,2000,500,2000,2);var state=usage.snapshot();assertEquals(50,state.path("cpuOurs").asDouble(),.01);assertEquals(30,state.path("cpuOther").asDouble(),.01);assertEquals(600,state.path("memoryOurs").asLong());assertEquals(900,state.path("memoryOther").asLong());
        var memory=state.path("memory");assertEquals(400,memory.path("totalBytes").asLong());assertEquals(2,memory.path("processes").asInt());
        assertEquals(400,memory.path("projects").path("p").path("bytes").asLong());assertEquals(100,memory.path("sessions").path("s1").path("bytes").asLong());
    }
    @Test void liveMemoryDropsFinishedProcessesAndReportsMissingMeasurements(){
        var usage=new MachineUsage();var assigned=Map.of(1L,assignment("a","p"),2L,assignment("b","q"));
        usage.sample(List.of(new CpuSampler.Sample(1,0,10,0,"worker",100),new CpuSampler.Sample(2,0,10,0,"worker",-1)),assigned,.2,2000,500,1000,2);
        var memory=usage.snapshot().path("memory");assertEquals(100,memory.path("totalBytes").asLong());assertEquals(1,memory.path("unmeasuredProcesses").asInt());
        assertEquals(1,memory.path("sessions").path("b").path("unmeasuredProcesses").asInt());assertEquals(100,usage.snapshot().path("memoryOurs").asLong());assertEquals(1,usage.snapshot().path("memoryUnmeasuredProcesses").asInt());
        // Same PID, different birth/owner: no retained memory from the finished session.
        usage.sample(List.of(new CpuSampler.Sample(1,0,1500,0,"worker",75)),Map.of(1L,assignment("c","q")),.2,2000,500,2000,2);
        memory=usage.snapshot().path("memory");assertEquals(75,memory.path("totalBytes").asLong());assertEquals(0,memory.path("unmeasuredProcesses").asInt());
        assertFalse(memory.path("sessions").has("a"));assertFalse(memory.path("projects").has("p"));assertEquals(75,memory.path("projects").path("q").path("bytes").asLong());
        usage.sample(List.of(),Map.of(),0,2000,2000,3000,2);assertEquals(0,usage.snapshot().path("memory").path("totalBytes").asLong());assertTrue(usage.snapshot().path("memory").path("sessions").isEmpty());
    }
    private static CpuSampler.Assignment assignment(String session,String project){return new CpuSampler.Assignment(new CpuSampler.Owner(session,project),false);}
}

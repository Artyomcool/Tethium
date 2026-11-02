package dev.tethium.host;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CpuSamplerTest {
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
    @Test void loopbackConnectionsRequireMatchingEndpoints(){
        var edges=CpuSampler.connections(" TCP 127.0.0.1:123 127.0.0.1:456 ESTABLISHED 3\n TCP 127.0.0.1:456 127.0.0.1:123 ESTABLISHED 4\n TCP 10.0.0.1:42 10.0.0.2:43 ESTABLISHED 8");assertEquals(List.of(new CpuSampler.Link(3,4)),edges);
    }
}

package dev.tethium.host;

import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceControlTest {
    @Test void limitsAndPriorityInheritPersistAndConvertToNestedRates(@TempDir Path dir)throws Exception{
        String project,session,sibling;
        try(var store=new SessionStore(dir.resolve("settings.db"))){
            project=store.project(dir);session=store.create(project,null,"A",dir).id();sibling=store.create(project,null,"B",dir).id();
            store.cpuControl("global","",Json.object("cap",80,"priority","normal"));
            store.cpuControl("project",project,Json.object("cap",40,"priority","background"));
            store.cpuControl("session",session,Json.object("cap",10,"priority","important"));
            assertThrows(IllegalArgumentException.class,()->store.cpuControl("global","",Json.object("cap",0,"priority","normal")));
            try(var controller=new ResourceController(store,dir,false)){
                var spec=controller.spec(session);assertEquals(8000,spec.path("globalRate").asInt());assertEquals(5000,spec.path("projectRate").asInt());assertEquals(2500,spec.path("sessionRate").asInt());assertEquals(0x20,spec.path("nativePriority").asInt());
                assertEquals(10000,controller.spec(sibling).path("sessionRate").asInt());assertEquals(0x40,controller.spec(sibling).path("nativePriority").asInt());
            }
        }
        try(var store=new SessionStore(dir.resolve("settings.db"));var controller=new ResourceController(store,dir,false)){
            assertEquals(10,controller.snapshot().path("sessions").path(session).path("effectiveCap").asInt());
            store.cpuControl("global","",Json.object("enabled",false,"cap",80,"priority","normal"));controller.refresh();
            assertEquals(0,controller.spec(session).path("sessionRate").asInt());assertEquals(0,controller.spec(session).path("globalRate").asInt());
            assertEquals(100,controller.snapshot().path("sessions").path(session).path("effectiveCap").asInt());
        }
    }

    @Test void privateDaemonRetainsOwnerAndUpdaterCannotCaptureControlPlane(){
        var a=new CpuSampler.Owner("a","p");var b=new CpuSampler.Owner("b","q");
        var samples=List.of(new CpuSampler.Sample(1,0,1,1,"worker"),new CpuSampler.Sample(2,1,2,1,"org.gradle.launcher.daemon.bootstrap.GradleDaemon"),
            new CpuSampler.Sample(3,0,3,1,"GradleWrapperMain"),new CpuSampler.Sample(4,2,4,1,"test"),
            new CpuSampler.Sample(5,1,5,1,"powershell request_desktop_update.ps1"),new CpuSampler.Sample(6,5,6,1,"Tethium.exe"),
            new CpuSampler.Sample(7,6,7,1,"dev.tethium.host.HostMain"),new CpuSampler.Sample(8,7,8,1,"new worker"),new CpuSampler.Sample(9,7,9,1,"helper"));
        var roots=List.of(new CpuSampler.Root(1,1,a),new CpuSampler.Root(2,2,a),new CpuSampler.Root(3,3,b),new CpuSampler.Root(8,8,b));
        var attributed=CpuSampler.attribute(samples,roots,Map.of("9:9",new CpuSampler.Assignment(a,false)),List.of(new CpuSampler.Link(3,2)));
        assertEquals(a,attributed.get(2L).owner());assertFalse(attributed.get(2L).shared());assertEquals(a,attributed.get(4L).owner());assertEquals(b,attributed.get(8L).owner());
        for(long id:new long[]{5,6,7,9})assertFalse(attributed.containsKey(id),"Control process "+id+" captured");
    }
}

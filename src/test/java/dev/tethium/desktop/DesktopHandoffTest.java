package dev.tethium.desktop;
import dev.tethium.host.*;
import dev.tethium.agent.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopHandoffTest {
    @TempDir Path data;
    @Test void readyWindowReceivesDraftAndSessionWithoutStoppingActiveWork() throws Exception {
        String previous=System.getProperty("tethium.data"); System.setProperty("tethium.data",data.toString()); Theme.install();
        var windows=new WorkspaceFrame[2]; var endpoints=new DesktopControl[2]; var started=new CountDownLatch(1);
        AgentAdapter adapter=new AgentAdapter() {
            public Provider provider(){return Provider.CODEX;}
            public Result run(Request r,Consumer<Event> events,Cancellation cancel)throws Exception {started.countDown();while(!cancel.isCancelled()) Thread.sleep(20);return new Result("test",Status.CANCELLED,"");}
        };
        try (var host=new HostService(data,adapter); var client=new HostClient(host.descriptor())) {
            String p=client.post("/projects",Json.object("path",data.toString())).path("id").asText();
            String id=client.post("/sessions",Json.object("projectId",p,"title","Handoff")).path("id").asText();
            client.post("/sessions/"+id+"/settings",Json.object("model","saved-model","reasoningEffort","ultra"));
            client.post("/sessions/"+id+"/run",Json.object("prompt","working")); assertTrue(started.await(5,TimeUnit.SECONDS));
            String[] names={UUID.randomUUID().toString(),UUID.randomUUID().toString()};
            for(int i=0;i<2;i++){int index=i;SwingUtilities.invokeAndWait(()->{
                windows[index]=new WorkspaceFrame(new HostClient(host.descriptor()),()->{},id); windows[index].addNotify(); windows[index].validate();
                try{endpoints[index]=new DesktopControl(windows[index],names[index],"test-app");}catch(Exception e){throw new RuntimeException(e);}
            });}
            try(var first=new HostClient(Json.MAPPER.readTree(data.resolve("clients/"+names[0]+".json").toFile())); var second=new HostClient(Json.MAPPER.readTree(data.resolve("clients/"+names[1]+".json").toFile()))) {
                ready(first);ready(second);
                SwingUtilities.invokeAndWait(()->windows[0].inputArea().setText("Незавершённый черновик\n\nВторой абзац"));
                var snapshot=first.post("/prepare",Json.object()); second.post("/adopt",snapshot); ready(second);
                assertEquals("saved-model",snapshot.path("model").asText()); assertEquals("ultra",snapshot.path("reasoningEffort").asText());
                var adopted=second.post("/prepare",Json.object()); assertEquals("ultra",adopted.path("reasoningEffort").asText()); second.post("/activate",Json.object());
                SwingUtilities.invokeAndWait(()->{assertEquals("Незавершённый черновик\n\nВторой абзац",windows[1].inputArea().getText());assertEquals(id,windows[1].selectedSession());windows[0].closeWindow();});
                assertTrue(client.get("/state").path("sessions").get(0).path("running").asBoolean());
                assertEquals(snapshot.path("drafts").path(id).asText(),client.get("/state").path("sessions").get(0).path("preferences").path("draft").asText());
            }
        } finally {
            for(var endpoint:endpoints) if(endpoint!=null) endpoint.close();
            SwingUtilities.invokeAndWait(()->{for(var window:windows)if(window!=null)window.closeWindow();});
            if(previous==null)System.clearProperty("tethium.data");else System.setProperty("tethium.data",previous);
        }
    }
    private static void ready(HostClient client)throws Exception{long deadline=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<deadline){if(client.get("/state").path("ready").asBoolean())return;Thread.sleep(30);}fail("Window never became ready");}
}

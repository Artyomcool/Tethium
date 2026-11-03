package dev.tethium.host;
import dev.tethium.agent.*;
import dev.tethium.json.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class PinnedFilesTest {
    @TempDir Path root;
    static final AgentAdapter NO_AGENT=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> e,Cancellation c){throw new AssertionError("A pinned file must never execute an agent");}};
    @Test void fileEntriesArePersistentIndependentOfSessionsAndNeverDeleteFiles()throws Exception{
        Path project=Files.createDirectory(root.resolve("project")),file=Files.writeString(project.resolve("Plan.md"),"# Plan\n");String id,pid,sid;
        try(var h=new HostService(root.resolve("host"),NO_AGENT);var c=new HostClient(h.descriptor())){
            pid=c.post("/projects",Json.object("path",project.toString())).path("id").asText();
            id=c.post("/projects/"+pid+"/files",Json.object("path","Plan.md")).path("id").asText();
            assertEquals(id,c.post("/projects/"+pid+"/files",Json.object("path",file.toString())).path("id").asText());
            assertEquals(1,c.get("/state").path("files").size());assertTrue(c.get("/state").path("sessions").isEmpty());
            c.post("/files/"+id+"/rename",Json.object("title","Roadmap"));
            sid=c.post("/sessions",Json.object("projectId",pid)).path("id").asText();c.post("/sessions/"+sid+"/pinned-file",Json.object("path","Plan.md"));
            Path outside=Files.writeString(root.resolve("outside.md"),"outside");
            assertThrows(Exception.class,()->c.post("/projects/"+pid+"/files",Json.object("path",outside.toString())));
            assertThrows(Exception.class,()->c.post("/sessions/"+sid+"/pinned-file",Json.object("path",outside.toString())));
        }
        try(var h=new HostService(root.resolve("host"),NO_AGENT);var c=new HostClient(h.descriptor())){
            var state=c.get("/state");assertEquals("Roadmap",state.path("files").get(0).path("title").asText());assertEquals(file.toRealPath().toString(),state.path("sessions").get(0).path("pinnedFile").asText());
            c.post("/files/"+id+"/remove",Json.object());assertTrue(c.get("/state").path("files").isEmpty());assertTrue(Files.exists(file));
            c.post("/sessions/"+sid+"/pinned-file",Json.object("path",""));assertTrue(c.get("/state").path("sessions").get(0).path("pinnedFile").isNull());assertTrue(Files.exists(file));
        }
    }
}

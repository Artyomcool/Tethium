package dev.tethium.host;

import dev.tethium.core.SessionStore;
import dev.tethium.agent.AgentAdapter;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentChangesTest {
    @TempDir Path root;
    @Test void interruptPromotesTheExistingGroupedHeadAndDoesNotLoseQueue()throws Exception{
        try(var store=new SessionStore(root.resolve("test.db"))){
            String id=store.create(store.project(root),null,"Test",root).id();
            String first=store.submit(id,"First",true);store.submit(id,"Second part",false);String last=store.submit(id,"Later",true);
            store.draft(id,"Saved draft");var result=store.interruptQueueHead(id,List.of(id),"once");
            assertEquals(first,result.path("jobId").asText());assertEquals(2,store.job(first).partsCount());assertTrue(store.job(first).urgent());
            assertEquals(List.of(first,last),store.queued(id).stream().map(SessionStore.Job::id).toList());assertFalse(store.queuePaused(id));
            assertEquals(result,store.interruptQueueHead(id,List.of(id),"once"));assertEquals("Saved draft",store.preferences(id).path("draft").asText());
            store.marked(id,true);store.seen(id,Long.MAX_VALUE);assertTrue(store.marked(id));
            store.claimProcess(id,ProcessHandle.current().pid());assertEquals(id,store.claimedProcesses().getFirst().path("session").asText());
        }
    }
    @Test void imageToolsKeepSnapshotsAndAttachToTheirAnswer()throws Exception{
        Path source=root.resolve("image.png");ImageIO.write(new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB),"png",source.toFile());byte[] original=Files.readAllBytes(source);
        try(var store=new SessionStore(root.resolve("test.db"))){
            String id=store.create(store.project(root),null,"Images",root).id();store.append(id,"user","USER","Look");
            String run=store.beginRun(id,AgentAdapter.Provider.CODEX);
            store.recordEvent(run,id,new AgentAdapter.Event("text","Before",Json.object()));
            var viewed=Json.object("params",Json.object("item",Json.object("type","imageView","id","view-1","path",source.toString())));
            store.recordEvent(run,id,new AgentAdapter.Event("item/completed","",viewed));store.recordEvent(run,id,new AgentAdapter.Event("item/completed","",viewed));
            store.recordEvent(run,id,new AgentAdapter.Event("item/completed","",Json.object("params",Json.object("item",Json.object("type","imageGeneration","id","gen-1","status","completed","result",Base64.getEncoder().encodeToString(original))))));
            store.completeRun(run,id,AgentAdapter.Provider.CODEX,new AgentAdapter.Result("thread",AgentAdapter.Status.COMPLETED,"Before and after"),store.lastSequence(id));long sequence=store.lastSequence(id);
            try(var media=new MediaCapture(store,root)){
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(store.mediaHistory(id).size()<2&&System.nanoTime()<until)Thread.sleep(25);
                var images=store.mediaHistory(id);assertEquals(2,images.size());
                Files.delete(source);
                for(var image:images){assertEquals(sequence,image.path("anchor").asLong());assertArrayEquals(original,Files.readAllBytes(Path.of(image.path("file").asText())));assertTrue(image.path("width").asInt()<=200);assertTrue(image.path("height").asInt()<=120);assertNotNull(ImageIO.read(Path.of(image.path("thumbnailFile").asText()).toFile()));}
                assertEquals(2,store.artifacts(id).size());assertTrue(store.mediaJobs().isEmpty());
            }
        }
    }
}

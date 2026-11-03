package dev.tethium.desktop;

import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatTraceLogTest {
    @TempDir Path root;
    @Test void recordsFlushAndRotateIntoReadableSegments()throws Exception{
        var log=new ChatTraceLog(root,"test",256);
        for(int i=0;i<40;i++)log.offer(Json.object("seq",i,"event","scroll","dy",12,"time",i));
        log.close();assertTrue(log.awaitClosed(3000));assertEquals(0,log.dropped.get());
        int count=0;try(var files=Files.list(root)){var parts=files.sorted().toList();assertTrue(parts.size()>1);for(var file:parts)for(var line:Files.readAllLines(file)){assertEquals(count++,Json.parse(line).path("seq").asInt());}}
        assertEquals(40,count);
    }
}

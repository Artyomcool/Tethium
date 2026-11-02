package dev.tethium.host;

import dev.tethium.agent.CodexAdapter;
import dev.tethium.json.Json;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class DetachedHostFixture {
    public static void main(String[] args)throws Exception{
        Path data=Path.of(args[0]);Files.createDirectories(data);
        try(var file=FileChannel.open(data.resolve("host.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=file.tryLock()){
            if(lock==null)throw new IllegalStateException("Another test host owns the store");
            var adapter=new CodexAdapter(List.of(AppPaths.javaExecutable(true),"-cp",System.getProperty("java.class.path"),DetachedAgentFixture.class.getName(),args[1]));
            try(var host=new HostService(data,adapter,new CodexQuotaService(null),args.length>2,new CodexModelCatalog(null))){
                var done=new CountDownLatch(1);host.onShutdown(done::countDown);
                Json.MAPPER.writeValue(data.resolve("host.json").toFile(),host.descriptor());
                done.await();
            }
        }
    }
}

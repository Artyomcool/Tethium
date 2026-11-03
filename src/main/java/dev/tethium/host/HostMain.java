package dev.tethium.host;

import dev.tethium.json.Json;
import dev.tethium.agent.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;

public final class HostMain {
    public static void main(String[] args) throws Exception {
        Path data = AppPaths.data(); Files.createDirectories(data);
        try (var channel = FileChannel.open(data.resolve("host.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) return;
            var account=new CodexAccountClient(data);
            var quota = new CodexQuotaService(Boolean.getBoolean("tethium.probe") ? null : account::readRateLimits,Boolean.getBoolean("tethium.probe")?null:account::consumeReset,Boolean.getBoolean("tethium.probe")?null:new ClaudeAccountClient()::readUsage);
            try (var host = new HostService(data, java.util.List.of(new CodexAdapter(), new ClaudeAdapter()), quota, Boolean.getBoolean("tethium.standby"), null)) {
                var done = new CountDownLatch(1); host.onShutdown(done::countDown);
                Path temporary = data.resolve("host.json.tmp");
                Json.MAPPER.writeValue(temporary.toFile(), host.descriptor());
                Files.move(temporary, data.resolve("host.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Runtime.getRuntime().addShutdownHook(new Thread(done::countDown));
                done.await();
            } finally { Files.deleteIfExists(data.resolve("host.json")); }
        }
    }
}

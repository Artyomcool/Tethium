package dev.tethium.update;

import dev.tethium.json.Json;
import java.nio.file.*;
import java.util.Set;

/** Resume a durable, user-requested installation when the desktop is started after a reboot. */
public final class UpdateRequests {
    private UpdateRequests() {}
    public static void resume(Path data) {
        try { launch(data); }catch(Exception e){System.err.println("Pending update: "+e.getMessage());}
    }
    public static long launch(Path data)throws Exception {
            Path request=data.resolve("desktop-update-request.json");
            if(!Files.isRegularFile(request))return 0;
            var value=Json.MAPPER.readTree(request.toFile());
            if(!Set.of("queued","installing").contains(value.path("phase").asText()))return 0;
            Path script=Path.of(value.path("coordinatorPath").asText()).toRealPath();
            if(!script.startsWith(data.resolve("updates/requests").toRealPath()))throw new IllegalArgumentException("Updater must belong to the saved request directory");
            return new ProcessBuilder("powershell.exe","-WindowStyle","Hidden","-NoProfile","-ExecutionPolicy","Bypass","-File",script.toString(),"-Apply","-DataPath",data.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(data.resolve("update-resume.log").toFile())).start().pid();
    }
}

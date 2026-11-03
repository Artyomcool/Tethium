package dev.tethium.probe;

import dev.tethium.resources.SessionResources;
import dev.tethium.json.Json;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Two personal daemon pools share the normal Gradle caches; only this probe's daemons are stopped. */
public final class SessionDaemonProbe {
    static long build(Path root,String session)throws Exception{
        var builder=new ProcessBuilder("cmd.exe","/c",Path.of("gradlew.bat").toAbsolutePath().toString(),"-p",root.resolve("project").toString(),"--console=plain","-q","identity");
        builder.environment().putAll(SessionResources.environment(root,session));builder.environment().put("JAVA_HOME",System.getProperty("java.home"));
        Path log=root.resolve(session+"-"+UUID.randomUUID()+".log");var p=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!p.waitFor(55,TimeUnit.SECONDS)){p.destroyForcibly();throw new AssertionError("Gradle timed out: "+log);}
        String text=Files.readString(log);if(p.exitValue()!=0)throw new AssertionError(text);
        Path expected=root.resolve("session-resources").resolve(session).resolve("tmp");String tmp=text.lines().filter(line->line.startsWith("TMP=")).findFirst().orElseThrow().substring(4);if(!Path.of(tmp).normalize().equals(expected.normalize()))throw new AssertionError("Wrong daemon temp: "+tmp);
        return Long.parseLong(text.lines().filter(line->line.startsWith("PID=")).findFirst().orElseThrow().substring(4));
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createDirectories(Path.of(".local/cpu-control/gradle-"+UUID.randomUUID()).toAbsolutePath()),project=Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("settings.gradle"),"rootProject.name='session-daemon-probe'\n");
        Files.writeString(project.resolve("gradle.properties"),"org.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=256m\n");
        Files.writeString(project.resolve("build.gradle"),"tasks.register('identity') { doLast { println('PID=' + ProcessHandle.current().pid()); println('TMP=' + System.getProperty('java.io.tmpdir')); println('HOME=' + gradle.gradleUserHomeDir) } }\n");
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();var pids=new HashSet<Long>();
        try{long first=build(root,a);pids.add(first);long second=build(root,b);pids.add(second);long again=build(root,a);pids.add(again);if(first==second||first!=again)throw new AssertionError("Daemon ownership/reuse: "+first+" / "+second+" / "+again);
            Json.MAPPER.writeValue(root.resolve("report.json").toFile(),Json.object("passed",true,"first",first,"second",second,"reused",again));System.out.println("SESSION_DAEMONS_PASSED "+root);
        }finally{for(long pid:pids)ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(p->p.destroy());}
    }
}

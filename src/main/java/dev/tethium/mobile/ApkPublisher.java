package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import dev.tethium.remote.RemoteClient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Freeze a signed project build before exposing it to the paired-device store. */
public final class ApkPublisher {
    private static final Map<Path,Object> LOCKS=new ConcurrentHashMap<>();
    private ApkPublisher() {}
    public static JsonNode publish(Path project,Path source,String title)throws Exception{
        Path root=project.toRealPath(),apk=source.toRealPath();
        if(!apk.startsWith(root)||!Files.isRegularFile(apk)||!apk.toString().toLowerCase(Locale.ROOT).endsWith(".apk"))throw new IllegalArgumentException("APK должен находиться в проекте");
        if(Files.size(apk)<=0||Files.size(apk)>1_000_000_000L)throw new IllegalArgumentException("Нужен APK до 1 ГБ");
        Path tools=findBuildTools(root),folder=Files.createDirectories(root.resolve(".tethium/apps")).toRealPath();
        if(!folder.startsWith(root))throw new IllegalArgumentException("Каталог публикации вне проекта");
        if(!Files.exists(folder.resolve(".gitignore")))Files.writeString(folder.resolve(".gitignore"),"*\n",StandardOpenOption.CREATE_NEW);
        Path staged=Files.createTempFile(folder,"publish-",".apk");
        try{
            Files.copy(apk,staged,StandardCopyOption.REPLACE_EXISTING);
            if(Files.size(staged)>1_000_000_000L)throw new IllegalArgumentException("APK больше 1 ГБ");
            String badging=execute(List.of(tools.resolve("aapt2.exe").toString(),"dump","badging",staged.toString()));
            var metadata=parseBadging(badging,title);
            String signature=execute(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-jar",tools.resolve("lib/apksigner.jar").toString(),"verify","--print-certs",staged.toString()));
            var cert=Pattern.compile("certificate SHA-256 digest: ([a-fA-F0-9]{64})").matcher(signature);
            if(!cert.find())throw new IllegalArgumentException("Не удалось проверить подпись APK");
            String signer=cert.group(1).toLowerCase(Locale.ROOT),hash=RemoteClient.sha(staged);
            metadata.put("sha256",hash);metadata.put("signerSha256",signer);metadata.put("publishedAt",System.currentTimeMillis());metadata.put("apk",".tethium/apps/"+hash+".apk");
            synchronized(LOCKS.computeIfAbsent(root,k->new Object())){
                Path manifest=root.resolve(".tethium-apps.json");
                var existing=Files.exists(manifest)?Json.MAPPER.readTree(Files.readString(manifest,StandardCharsets.UTF_8)):Json.object("apps",List.of());
                if(!existing.path("apps").isArray())throw new IllegalArgumentException("В .tethium-apps.json нужен массив apps");
                var apps=Json.MAPPER.createArrayNode();
                for(var old:existing.path("apps")){
                    if(!old.path("packageName").equals(metadata.path("packageName"))){apps.add(old);continue;}
                    if(old.path("versionCode").asLong()>metadata.path("versionCode").asLong())throw new IllegalArgumentException("В магазине уже более новая версия; увеличьте versionCode сборки");
                    if(old.hasNonNull("signerSha256")&&!old.path("signerSha256").asText().equals(signer))throw new IllegalArgumentException("Ключ подписи изменился; существующее приложение нельзя обновить этим APK");
                    if(old.path("sha256").asText().equals(hash))metadata.put("publishedAt",old.path("publishedAt").asLong(System.currentTimeMillis()));
                }
                if(apps.size()>=50)throw new IllegalArgumentException("В проекте уже 50 приложений");
                Path target=folder.resolve(hash+".apk");
                if(!Files.exists(target))Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE);
                apps.add(metadata);var catalog=existing.isObject()?(com.fasterxml.jackson.databind.node.ObjectNode)existing.deepCopy():Json.object();catalog.set("apps",apps);
                Path tmp=Files.createTempFile(root,".tethium-apps-",".tmp");
                try{Files.writeString(tmp,Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(catalog)+"\n",StandardCharsets.UTF_8);Files.move(tmp,manifest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}
                return metadata;
            }
        }finally{Files.deleteIfExists(staged);}
    }
    static com.fasterxml.jackson.databind.node.ObjectNode parseBadging(String data,String title){
        var packageLine=Pattern.compile("package: name='([^']+)' versionCode='(\\d+)' versionName='([^']*)'").matcher(data);
        if(!packageLine.find())throw new IllegalArgumentException("Не удалось прочитать пакет APK");
        String pkg=packageLine.group(1);long version=Long.parseLong(packageLine.group(2));
        if(pkg.equals("dev.tethium.mobile")||version<=0||!pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))throw new IllegalArgumentException("Некорректный пакет или versionCode; Tethium обновляется отдельно");
        if(Pattern.compile("package: [^\\r\\n]*split='").matcher(data).find())throw new IllegalArgumentException("Нужен самостоятельный APK, не split APK");
        var label=Pattern.compile("(?m)^application-label:'([^']*)'").matcher(data);
        String name=title==null||title.isBlank()?label.find()?label.group(1):pkg:title.strip();
        return Json.object("packageName",pkg,"title",name,"versionCode",version,"versionName",packageLine.group(3));
    }
    private static Path findBuildTools(Path root)throws IOException{
        var sdks=new LinkedHashSet<Path>();
        for(String env:List.of("ANDROID_HOME","ANDROID_SDK_ROOT")){String value=System.getenv(env);if(value!=null&&!value.isBlank())sdks.add(Path.of(value));}
        for(Path file:List.of(root.resolve("local.properties"),root.resolve("android/local.properties"))){if(Files.isRegularFile(file)){var props=new Properties();try(var in=Files.newInputStream(file)){props.load(in);}if(props.containsKey("sdk.dir"))sdks.add(Path.of(props.getProperty("sdk.dir")));}}
        String local=System.getenv("LOCALAPPDATA");if(local!=null)sdks.add(Path.of(local,"Android/Sdk"));
        for(Path sdk:sdks){Path folder=sdk.resolve("build-tools");if(!Files.isDirectory(folder))continue;try(var dirs=Files.list(folder)){var found=dirs.filter(p->Files.isRegularFile(p.resolve("aapt2.exe"))&&Files.isRegularFile(p.resolve("lib/apksigner.jar"))).max(Comparator.comparing(p->p.getFileName().toString()));if(found.isPresent())return found.get();}}
        throw new IOException("Нужны Android SDK build-tools: укажите sdk.dir в local.properties или ANDROID_HOME");
    }
    private static String execute(List<String> args)throws Exception{
        Path output=Files.createTempFile("tethium-apk-",".log");Process process=null;
        try{
            process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if(!process.waitFor(30,TimeUnit.SECONDS))throw new IOException("Проверка APK заняла больше 30 секунд");
            if(Files.size(output)>1_000_000)throw new IOException("Слишком большой ответ Android SDK");
            String text=Files.readString(output,StandardCharsets.UTF_8);
            if(process.exitValue()!=0)throw new IOException("Android SDK: "+text.substring(0,Math.min(text.length(),1200)));
            return text;
        }finally{if(process!=null&&process.isAlive())process.destroyForcibly();Files.deleteIfExists(output);}
    }
}

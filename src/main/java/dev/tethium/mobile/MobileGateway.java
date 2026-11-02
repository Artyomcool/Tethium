package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** A narrow, paired LAN API. The desktop's administrative bearer never leaves loopback. */
public final class MobileGateway implements AutoCloseable {
    private final Path data;
    private final HostClient host;
    private final HttpsServer api;
    private final HttpServer download;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String,String> devices = new ConcurrentHashMap<>();
    private final Map<String,Preview> previews = new ConcurrentHashMap<>();
    private final Map<String,Preview> downloads = new ConcurrentHashMap<>();
    private record Preview(Path root,long expires) {}
    private final String pin;
    private String code;
    private long expires;
    public MobileGateway(Path data, HostClient host, int apiPort, int downloadPort) throws Exception {
        this.data = Files.createDirectories(data.resolve("mobile")); this.host = host;
        Path keys = this.data.resolve("identity.p12"); char[] password = "tethium-local-identity".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        if (Files.exists(keys)) { try(var input=Files.newInputStream(keys)){store.load(input,password);} }
        else {
            store.load(null,password); var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
            var name=new X500Name("CN=Tethium LAN");var now=Instant.now();
            var builder=new JcaX509v3CertificateBuilder(name,new BigInteger(128,new SecureRandom()),Date.from(now.minusSeconds(3600)),Date.from(now.plusSeconds(10L*365*86400)),name,pair.getPublic());
            builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,false,new org.bouncycastle.asn1.x509.GeneralNames(new org.bouncycastle.asn1.x509.GeneralName[]{new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName,"localhost"),new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.iPAddress,"127.0.0.1")}));
            var cert=new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
            store.setKeyEntry("tethium",pair.getPrivate(),password,new java.security.cert.Certificate[]{cert});
            try(var output=Files.newOutputStream(keys,StandardOpenOption.CREATE_NEW)){store.store(output,password);}
        }
        pin=sha(store.getCertificate("tethium").getEncoded());
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,password);
        var tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),null,null);
        Path deviceFile=this.data.resolve("devices.json");
        if(Files.exists(deviceFile))Json.MAPPER.readTree(deviceFile.toFile()).properties().forEach(e->devices.put(e.getKey(),e.getValue().asText()));
        api=HttpsServer.create(new InetSocketAddress("0.0.0.0",apiPort),32);api.setHttpsConfigurator(new HttpsConfigurator(tls));api.setExecutor(workers);api.createContext("/",this::serve);
        try {download=HttpServer.create(new InetSocketAddress("0.0.0.0",downloadPort),16);}
        catch(Exception e){api.stop(0);workers.close();throw e;}
        download.setExecutor(workers);download.createContext("/",this::download);api.start();download.start();
    }
    public int port(){return api.getAddress().getPort();}
    public int downloadPort(){return download.getAddress().getPort();}
    public String pin(){return pin;}
    public synchronized JsonNode pairInfo() throws Exception {
        code=random();expires=System.currentTimeMillis()+300_000;
        var addresses=new ArrayList<String>();
        for(var n:Collections.list(NetworkInterface.getNetworkInterfaces()))if(n.isUp()&&!n.isLoopback())for(var a:Collections.list(n.getInetAddresses()))if(a instanceof Inet4Address&&a.isSiteLocalAddress())addresses.add(a.getHostAddress());
        // Prefer the interface used by the default route over virtual adapters.
        try(var socket=new DatagramSocket()){socket.connect(InetAddress.getByName("1.1.1.1"),53);String preferred=socket.getLocalAddress().getHostAddress();if(addresses.remove(preferred))addresses.addFirst(preferred);}catch(Exception ignored){}
        if(addresses.isEmpty())addresses.add("127.0.0.1");
        String address=addresses.getFirst();
        return Json.object("address",address,"addresses",addresses,"port",port(),"downloadPort",downloadPort(),"download","http://"+address+":"+downloadPort()+"/","pairing","tethium://pair?address="+address+":"+port()+"&pin="+pin+"&code="+code,"expires",expires,"devices",devices.size());
    }
    public synchronized JsonNode revoke()throws IOException{devices.clear();code=null;previews.clear();saveDevices();return Json.object("ok",true);}
    private synchronized JsonNode pair(JsonNode body) throws Exception {
        if(code==null||System.currentTimeMillis()>expires||!equal(code,body.path("code").asText()))throw new SecurityException("QR истёк. Откройте новый на ноутбуке.");
        String token=random();devices.put(sha(token.getBytes(StandardCharsets.UTF_8)),body.path("name").asText("Android"));saveDevices();code=null;
        return Json.object("token",token);
    }
    private void saveDevices()throws IOException{Path tmp=data.resolve("devices.tmp");Json.MAPPER.writeValue(tmp.toFile(),devices);Files.move(tmp,data.resolve("devices.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private void serve(HttpExchange e) throws IOException {
        try(e){try{
            String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
            if(e.getRequestHeaders().containsKey("Origin"))throw new SecurityException("Browser API access is disabled");
            if(path.startsWith("/preview/")&&method.equals("GET")){previewFile(e,path);return;}
            if(path.equals("/pair")&&method.equals("POST")){json(e,200,pair(body(e)));return;}
            String auth=e.getRequestHeaders().getFirst("Authorization");
            if(auth==null||!auth.startsWith("Bearer ")||!devices.containsKey(sha(auth.substring(7).getBytes(StandardCharsets.UTF_8))))throw new SecurityException("Подключите телефон по QR на ноутбуке");
            if(path.equals("/update")&&method.equals("GET")){resource(e,"/mobile/release.json","application/json");return;}
            if(path.equals("/apk")&&method.equals("GET")){resource(e,"/mobile/tethium.apk","application/vnd.android.package-archive");return;}
            if(path.equals("/attachment")&&method.equals("POST")){json(e,200,upload(body(e,17_000_000)));return;}
            if(path.equals("/artifact/download")&&method.equals("POST")){
                var b=body(e);var snapshot=host.get("/state?session="+URLEncoder.encode(b.path("session").asText(),StandardCharsets.UTF_8));JsonNode artifact=null;for(var a:snapshot.path("conversation").path("artifacts"))if(a.path("id").asText().equals(b.path("artifact").asText()))artifact=a;
                if(artifact==null)throw new IllegalArgumentException("Сборка не опубликована в этой сессии");Path file=Path.of(artifact.path("path").asText()).toRealPath();if(!Files.isRegularFile(file)||Files.size(file)>2_000_000_000L)throw new IllegalArgumentException("Нужен файл до 2 ГБ");
                String key=random();downloads.entrySet().removeIf(x->x.getValue().expires<System.currentTimeMillis());downloads.put(key,new Preview(file,System.currentTimeMillis()+3600_000));
                json(e,200,Json.object("name",file.getFileName().toString(),"size",Files.size(file),"sha256",dev.tethium.remote.RemoteClient.sha(file),"path","/artifact/file/"+key));return;
            }
            if(path.startsWith("/artifact/file/")&&method.equals("GET")){
                var download=downloads.get(path.substring("/artifact/file/".length()));if(download==null||download.expires<System.currentTimeMillis())throw new IllegalArgumentException("Ссылка загрузки истекла");e.getResponseHeaders().set("Content-Type","application/octet-stream");e.sendResponseHeaders(200,Files.size(download.root));Files.copy(download.root,e.getResponseBody());return;
            }
            if(path.equals("/preview")&&method.equals("POST")){
                var b=body(e); var snapshot=host.get("/state?session="+URLEncoder.encode(b.path("session").asText(),StandardCharsets.UTF_8));
                JsonNode artifact=null;for(var a:snapshot.path("conversation").path("artifacts"))if(a.path("id").asText().equals(b.path("artifact").asText()))artifact=a;
                Path file;
                if(artifact!=null)file=Path.of(artifact.path("path").asText()).toRealPath();
                else {
                    JsonNode session=null;for(var value:snapshot.path("sessions"))if(value.path("id").asText().equals(b.path("session").asText()))session=value;
                    if(session==null)throw new IllegalArgumentException("Сессия не найдена");
                    String link=b.path("file").asText();if(link.isBlank())throw new IllegalArgumentException("Результат не найден");
                    link=link.replaceFirst(":\\d+(?::\\d+)?$", "");
                    if(link.matches("^/[A-Za-z]:/.*"))link=link.substring(1);
                    Path rawPath=link.startsWith("file:")?Path.of(URI.create(link)):Path.of(URLDecoder.decode(link.replace("+","%2B"),StandardCharsets.UTF_8));
                    Path directory=Path.of(session.path("directory").asText()).toRealPath();file=directory.resolve(rawPath).normalize().toRealPath();
                    Path project=directory;for(var value:snapshot.path("projects"))if(value.path("id").asText().equals(session.path("projectId").asText()))project=Path.of(value.path("root").asText()).toRealPath();
                    if(!file.startsWith(project)&&!file.startsWith(directory))throw new IllegalArgumentException("Файл вне проекта");
                    if(!file.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|html?|svg|png|jpe?g|gif|webp)$"))throw new IllegalArgumentException("Этот формат не поддерживается в просмотре");
                }
                String key=random();
                previews.entrySet().removeIf(x->x.getValue().expires<System.currentTimeMillis());previews.put(key,new Preview(file.getParent(),System.currentTimeMillis()+3600_000));
                json(e,200,Json.object("path","/preview/"+key+"/"+new URI(null,null,file.getFileName().toString(),null).toASCIIString()));return;
            }
            String raw=e.getRequestURI().getRawQuery();String target=path+(raw==null?"":"?"+raw);
            if(method.equals("GET")&&(path.equals("/state")||path.equals("/models")||path.equals("/usage"))){json(e,200,host.get(target));return;}
            if(method.equals("POST")&&allowed(path)){
                var b=(com.fasterxml.jackson.databind.node.ObjectNode)body(e);
                // A phone can control sessions, not supply paths to arbitrary laptop files.
                if(b.has("images")&&!b.path("images").isEmpty())throw new IllegalArgumentException("Используйте загруженные вложения");
                var images=Json.MAPPER.createArrayNode();
                if(b.path("attachments").size()>12)throw new IllegalArgumentException("Не больше 12 изображений в сообщении");
                for(var attachment:b.path("attachments")){
                    String id=attachment.asText();if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Некорректное вложение");
                    Path file=data.resolve("attachments").resolve(id+".png").toRealPath();
                    if(!file.startsWith(data.resolve("attachments").toRealPath())||!Files.isRegularFile(file))throw new IllegalArgumentException("Вложение не найдено");
                    images.add(file.toString());
                }
                b.set("images",images);b.put("preserveDesktopDraft",true);
                if(b.path("scope").asText().equals("all"))throw new IllegalArgumentException("Выберите конкретную сессию");
                json(e,200,host.post(path,b));return;
            }
            json(e,404,Json.object("error","Unknown endpoint"));
        }catch(SecurityException denied){json(e,401,Json.object("error",denied.getMessage()));}
        catch(Exception error){json(e,400,Json.object("error",Objects.toString(error.getMessage(),"Ошибка подключения")));}}
    }
    private static boolean allowed(String path){return path.equals("/quota/refresh")||path.equals("/quota/reset")||path.equals("/sessions")||path.equals("/answer")||path.matches("/sessions/[a-zA-Z0-9-]+/(run|interrupt|stop|queue-resume|queue-remove|queue-move|queue-take|queue-merge|queue-split|active|rename|settings|seen|quota-auto|autonomy)");}
    private JsonNode upload(JsonNode body)throws Exception{
        byte[] bytes=Base64.getDecoder().decode(body.path("data").asText());
        if(bytes.length==0||bytes.length>12_000_000)throw new IllegalArgumentException("Изображение должно быть меньше 12 МБ");
        int width,height;
        try(var in=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=javax.imageio.ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IllegalArgumentException("Не удалось прочитать изображение");
            var reader=readers.next();try{reader.setInput(in);width=reader.getWidth(0);height=reader.getHeight(0);
                if(!reader.getFormatName().equalsIgnoreCase("png")||width<1||height<1||width>8192||height>8192||(long)width*height>24_000_000)throw new IllegalArgumentException("Нужен PNG до 24 мегапикселей");
                if(reader.read(0)==null)throw new IllegalArgumentException("Повреждённое изображение");
            }finally{reader.dispose();}
        }
        String id=sha(bytes);Path folder=Files.createDirectories(data.resolve("attachments")),file=folder.resolve(id+".png");
        if(!Files.isRegularFile(file)||Files.size(file)!=bytes.length||!sha(Files.readAllBytes(file)).equals(id)){
            Path temp=Files.createTempFile(folder,"upload-",".tmp");
            try{Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            finally{Files.deleteIfExists(temp);}
        }
        return Json.object("id",id,"width",width,"height",height);
    }
    private void previewFile(HttpExchange e,String path)throws Exception{
        String[] parts=path.split("/",4);var preview=parts.length==4?previews.get(parts[2]):null;
        if(preview==null||preview.expires<System.currentTimeMillis()){e.sendResponseHeaders(404,-1);return;}
        Path file=preview.root.resolve(parts[3]).normalize().toRealPath();
        if(!file.startsWith(preview.root)||!Files.isRegularFile(file)){e.sendResponseHeaders(403,-1);return;}
        byte[] rendered=dev.tethium.artifact.MarkdownPreview.supports(file)?dev.tethium.artifact.MarkdownPreview.render(file):null;
        String name=file.getFileName().toString().toLowerCase(Locale.ROOT);String type=rendered!=null?"text/html; charset=utf-8":name.endsWith(".js")?"text/javascript":name.endsWith(".css")?"text/css":name.endsWith(".html")?"text/html; charset=utf-8":name.endsWith(".svg")?"image/svg+xml":Files.probeContentType(file);
        e.getResponseHeaders().set("Content-Type",type==null?"application/octet-stream":type);
        e.getResponseHeaders().set("Content-Security-Policy","sandbox allow-scripts; default-src 'self' data:; connect-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'");
        e.getResponseHeaders().set("X-Content-Type-Options","nosniff");e.sendResponseHeaders(200,rendered==null?Files.size(file):rendered.length);if(rendered==null)Files.copy(file,e.getResponseBody());else e.getResponseBody().write(rendered);
    }
    private void download(HttpExchange e)throws IOException{
        try(e){if(!e.getRequestMethod().equals("GET")){e.sendResponseHeaders(405,-1);return;}
            if(e.getRequestURI().getPath().equals("/tethium-remote.zip")){Path zip=data.getParent().resolve("releases/tethium-remote.zip");if(!Files.isRegularFile(zip)){e.sendResponseHeaders(404,-1);return;}e.getResponseHeaders().set("Content-Type","application/zip");e.getResponseHeaders().set("Content-Disposition","attachment; filename=Tethium-Remote.zip");e.sendResponseHeaders(200,Files.size(zip));Files.copy(zip,e.getResponseBody());return;}
            if(e.getRequestURI().getPath().equals("/tethium.apk")){resource(e,"/mobile/tethium.apk","application/vnd.android.package-archive");return;}
            if(!e.getRequestURI().getPath().equals("/")){e.sendResponseHeaders(404,-1);return;}
            byte[] page=("<!doctype html><html lang=ru><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Tethium · клиенты</title><style>body{background:#171b22;color:#e9eef6;font:17px system-ui;max-width:500px;margin:60px auto;padding:24px;line-height:1.6}a{display:block;background:#bcd4fb;color:#152034;padding:16px;border-radius:12px;text-decoration:none;text-align:center}</style><h1>Tethium</h1><p>Телефон и ноутбук должны быть в одной сети Wi-Fi.</p><a href=/tethium.apk>Скачать APK для Android</a><p>Установите приложение. Затем на ноутбуке нажмите «Телефон» и отсканируйте второй QR для привязки.</p><a href=/tethium-remote.zip>Скачать Tethium Remote для Windows</a><p>Распакуйте ZIP и запустите Tethium Remote.exe. Вставьте ссылку привязки из окна «Телефон» на ноутбуке.</p><p>Вся работа остаётся на ноутбуке. После привязки приложение само проверяет обновления.</p></html>").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");e.sendResponseHeaders(200,page.length);e.getResponseBody().write(page);}
    }
    private static JsonNode body(HttpExchange e)throws IOException{return body(e,2_000_000);}
    private static JsonNode body(HttpExchange e,int limit)throws IOException{byte[] b=e.getRequestBody().readNBytes(limit+1);if(b.length>limit)throw new IOException("Сообщение слишком большое");return Json.MAPPER.readTree(b);}
    private static void resource(HttpExchange e,String path,String type)throws IOException{try(var in=MobileGateway.class.getResourceAsStream(path)){if(in==null){e.sendResponseHeaders(404,-1);return;}byte[] bytes=in.readAllBytes();e.getResponseHeaders().set("Content-Type",type);e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}}
    private static void json(HttpExchange e,int status,Object value)throws IOException{byte[] bytes=Json.MAPPER.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);}
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static boolean equal(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    public void close(){api.stop(0);download.stop(0);workers.shutdownNow();host.close();}
}

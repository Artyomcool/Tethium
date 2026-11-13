package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import dev.tethium.link.Frames;
import dev.tethium.link.RelayCrypto;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Keeps one outbound, end-to-end encrypted relay room per paired phone. Cloudflare only forwards opaque envelopes. */
final class RelayLinkManager implements AutoCloseable {
    private static final byte[] CLOSED=new byte[0];
    private final Path devicesFile;
    private final TrafficLedger traffic;
    private final Consumer<LinkChannel> accepted;
    private final ExecutorService loops=Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String,String> secrets=new ConcurrentHashMap<>();
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    private final Set<RelayListener> active=ConcurrentHashMap.newKeySet();
    private final String url,admission;
    private volatile boolean closed;
    private volatile String lastError="";
    private volatile long lastConnectedAt;

    RelayLinkManager(Path data,TrafficLedger traffic,Consumer<LinkChannel> accepted){
        this.devicesFile=data.resolve("relay-devices.json");this.traffic=traffic;this.accepted=accepted;
        String configuredUrl="",configuredAdmission="";
        try{
            Path config=data.resolve("relay.json");if(Files.isRegularFile(config)){JsonNode n=Json.MAPPER.readTree(config.toFile());configuredUrl=n.path("url").asText();configuredAdmission=n.path("admission").asText();}
            if(Files.isRegularFile(devicesFile))Json.MAPPER.readTree(devicesFile.toFile()).properties().forEach(e->secrets.put(e.getKey(),e.getValue().asText()));
        }catch(Exception e){System.err.println("Relay configuration: "+e.getMessage());}
        url=configuredUrl.replaceFirst("/+$","");admission=configuredAdmission;
        if(enabled())for(String hash:secrets.keySet())start(hash);
    }
    boolean enabled(){return url.startsWith("wss://")&&!admission.isBlank();}
    synchronized JsonNode device(String hash)throws IOException{
        String secret=secrets.get(hash);
        if(secret==null){secret=RelayCrypto.encode(RelayCrypto.secret());secrets.put(hash,secret);save();}
        if(enabled())start(hash);
        if(!enabled())return Json.object();var crypto=new RelayCrypto(RelayCrypto.decode(secret));
        return Json.object("url",url,"ticket",ticket(crypto.room(),"phone"),"secret",secret,"protocol",1);
    }
    synchronized void clear()throws IOException{secrets.clear();save();}
    JsonNode status(){return Json.object("enabled",enabled(),"url",enabled()?url:"","rooms",secrets.size(),"connectedRooms",active.size(),"lastConnectedAt",lastConnectedAt,"lastError",lastError);}
    private void start(String hash){if(!enabled()||closed||!running.add(hash))return;loops.execute(()->loop(hash));}
    private void loop(String hash){
        int delay=1_000;
        try{while(!closed&&secrets.containsKey(hash)){
            if(!traffic.allowRelay()){try{Thread.sleep(60_000);}catch(InterruptedException e){return;}continue;}
            try{
                var crypto=new RelayCrypto(RelayCrypto.decode(secrets.get(hash)));
                var listener=new RelayListener(crypto,traffic);
                URI endpoint=URI.create(url+"/v1/connect/"+crypto.room());
                WebSocket socket=client.newWebSocketBuilder().header("x-tethium-ticket",ticket(crypto.room(),"host")).header("x-tethium-role","host").header("x-tethium-auth",crypto.auth()).connectTimeout(Duration.ofSeconds(10)).buildAsync(endpoint,listener).join();
                listener.socket=socket;active.add(listener);lastConnectedAt=System.currentTimeMillis();lastError="";
                try{
                    traffic.relayConnection();accepted.accept(listener.channel());delay=1_000;
                    while(!closed&&!listener.closed.isDone()){
                        long probe=listener.probe();
                        try{listener.closed.get(10,TimeUnit.SECONDS);}catch(TimeoutException ignored){}
                        if(!listener.closed.isDone()&&listener.lastProbeAck<probe)throw new IOException("Cloudflare relay probe timed out");
                    }
                    listener.closed.join();
                }
                finally{active.remove(listener);listener.abort();}
            }catch(Exception e){if(closed)return;lastError=message(e);System.err.println("Cloudflare host relay: "+lastError);}
            if(!closed)try{Thread.sleep(delay);}catch(InterruptedException e){return;}delay=Math.min(30_000,delay*2);
        }}finally{running.remove(hash);}
    }
    private synchronized void save()throws IOException{Files.createDirectories(devicesFile.getParent());Path tmp=devicesFile.resolveSibling(devicesFile.getFileName()+".tmp");Json.MAPPER.writeValue(tmp.toFile(),secrets);Files.move(tmp,devicesFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private static String message(Throwable error){Throwable current=error;while(current.getCause()!=null&&current.getCause()!=current)current=current.getCause();String value=current.getMessage();if(value==null||value.isBlank())value=current.getClass().getSimpleName();return value.length()>300?value.substring(0,300):value;}
    private String ticket(String room,String role){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(admission.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((room+"\n"+role).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public void close(){closed=true;for(var listener:List.copyOf(active))listener.abort();loops.shutdownNow();client.close();}

    private static final class RelayListener implements WebSocket.Listener {
        private final RelayCrypto crypto;
        private final TrafficLedger traffic;
        private final BlockingQueue<byte[]> incoming=new LinkedBlockingQueue<>();
        private final CompletableFuture<Void> closed=new CompletableFuture<>();
        private ByteArrayOutputStream message=new ByteArrayOutputStream();
        private volatile WebSocket socket;
        private volatile long lastProbeAck;
        RelayListener(RelayCrypto crypto,TrafficLedger traffic){this.crypto=crypto;this.traffic=traffic;}
        LinkChannel channel(){return new LinkChannel(){
            public byte[] read()throws IOException{try{byte[] value=incoming.take();if(value==CLOSED)throw new EOFException("Relay closed");return value;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new EOFException("Relay interrupted");}}
            public void send(byte[] raw)throws IOException{WebSocket current=socket;if(current==null||current.isOutputClosed())throw new EOFException("Relay closed");if(!traffic.allowRelay()){current.sendClose(4008,"daily budget");throw new IOException("Cloudflare daily safety budget reached");}byte[] envelope=crypto.seal(Frames.pack(raw));traffic.bytes(true,true,envelope.length);traffic.relayMessage();try{current.sendBinary(ByteBuffer.wrap(envelope),true).join();}catch(Exception e){throw new IOException("Relay send failed",e);}}
            public boolean open(){WebSocket current=socket;return current!=null&&!current.isInputClosed()&&!current.isOutputClosed();}
            public boolean remote(){return true;}
            public String peer(){return "Cloudflare relay";}
            public void close(){WebSocket current=socket;if(current!=null&&!current.isOutputClosed())current.sendClose(WebSocket.NORMAL_CLOSURE,"closed");finish();}
        };}
        public void onOpen(WebSocket socket){this.socket=socket;socket.request(1);}
        long probe()throws IOException{
            WebSocket current=socket;if(current==null||current.isOutputClosed())throw new EOFException("Relay closed");
            long sent=System.currentTimeMillis();
            try{current.sendText("@tethium-host-probe",true).join();return sent;}catch(Exception e){throw new IOException("Relay probe failed",e);}
        }
        public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){if(last&&data.toString().equals("@tethium-host-ok"))lastProbeAck=System.currentTimeMillis();socket.request(1);return null;}
        public CompletionStage<?> onBinary(WebSocket socket,ByteBuffer data,boolean last){byte[] part=new byte[data.remaining()];data.get(part);message.write(part,0,part.length);if(last){byte[] envelope=message.toByteArray();message=new ByteArrayOutputStream();try{traffic.bytes(true,false,envelope.length);traffic.relayMessage();if(!traffic.allowRelay()){socket.sendClose(4008,"daily budget");finish();return null;}incoming.add(Frames.unpack(crypto.open(envelope)));}catch(Exception e){socket.abort();finish();}}socket.request(1);return null;}
        public CompletionStage<?> onClose(WebSocket socket,int status,String reason){finish();return null;}
        public void onError(WebSocket socket,Throwable error){finish();}
        private void abort(){WebSocket current=socket;if(current!=null&&!current.isInputClosed()&&!current.isOutputClosed())current.abort();finish();}
        private void finish(){if(closed.complete(null))incoming.offer(CLOSED);}
    }
}

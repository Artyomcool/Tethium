package dev.tethium.mobile;

import android.net.Network;
import android.os.Build;
import com.github.luben.zstd.Zstd;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One binary link to the laptop over the home LAN: pushes arrive on the reader thread, requests are answered by id.
 * Framing mirrors dev.tethium.link.Frames; plain HTTP on the laptop's second port is used only for files.
 */
final class Connection {
    static final int PROTOCOL=1;
    static final int HELLO=1,WELCOME=2,PAIR=3,PAIRED=4,BYE=5,REQUEST=6,RESPONSE=7,WATCH=8,STATE=9,CONVERSATION=10,LIVE=11,PING=12,PONG=13;
    static final int MAX_FRAME=32<<20;
    static final class ApiException extends IOException { final int status; ApiException(int status,String text){super(text);this.status=status;} }
    interface Listener { void state(JSONObject delta,boolean full); void conversation(JSONObject frame); void live(JSONObject frame); void online(boolean online,String error); }
    static final class Frame { final int kind; final JSONObject body; Frame(int kind,JSONObject body){this.kind=kind;this.body=body;} }
    final String address,token; final int httpPort;
    private final String host; private final int port; private final Network network;
    private volatile Listener listener; private volatile boolean started; private volatile String watched=""; private volatile JSONObject watchBody;
    private Thread worker; private Socket socket; private DataOutputStream out; private Tbin.Writer writer; private final Object writeLock=new Object();
    private volatile CountDownLatch ready=new CountDownLatch(1);
    private final ExecutorService sender=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"tethium-link-send");t.setDaemon(true);return t;});
    private final AtomicInteger ids=new AtomicInteger(); private final ConcurrentHashMap<Integer,CompletableFuture<JSONObject>> pending=new ConcurrentHashMap<>();
    Connection(String address,String token,int httpPort)throws IOException{this(address,token,httpPort,null);}
    Connection(String address,String token,int httpPort,Network network)throws IOException{
        if(address==null||!address.matches("[a-zA-Z0-9.\\-]+:[0-9]{1,5}"))throw new IOException("Некорректная ссылка подключения");
        this.address=address;this.token=token==null?"":token;this.httpPort=httpPort<=0?8766:httpPort;this.network=network;
        int colon=address.lastIndexOf(':');host=address.substring(0,colon);port=Integer.parseInt(address.substring(colon+1));
    }
    /** One-time pairing with the code from the laptop's QR; returns the device token and the HTTP port. */
    static JSONObject pair(String address,String code,String name,Network network)throws Exception{
        var c=new Connection(address,"",0,network);
        try(var s=c.openSocket()){
            var in=new DataInputStream(new BufferedInputStream(s.getInputStream()));var o=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));var w=new Tbin.Writer();var r=new Tbin.Reader();
            write(o,w,PAIR,json("protocol",PROTOCOL,"code",code,"name",name));var frame=read(in,r);
            if(frame.kind==PAIRED&&!frame.body.optString("token").isEmpty())return frame.body;
            throw new ApiException(401,frame.body.optString("error","Ноутбук отклонил привязку"));
        }
    }
    private Socket openSocket()throws IOException{Socket s=network==null?new Socket():network.getSocketFactory().createSocket();s.connect(new InetSocketAddress(host,port),5000);s.setTcpNoDelay(true);s.setSoTimeout(30_000);return s;}
    /** Keeps the link up with reconnects while the screen is open. */
    synchronized void start(Listener l){listener=l;started=true;ensure();}
    synchronized void stop(){started=false;Socket s=socket;if(s!=null)sender.execute(()->{try{s.close();}catch(IOException ignored){}});}
    void close(){stop();}
    boolean connected(){return out!=null;}
    /** Chooses the session whose conversation the laptop streams; reset asks for the tail again. Safe to call from the UI thread. */
    static final int TAIL=10;
    void watch(String session,boolean reset){watch(session,reset,-1,-1,-1,0);}
    /** after/messageRevision let the laptop continue the conversation from what the phone already holds instead of resending the tail. */
    void watch(String session,boolean reset,long after,long messageRevision,long liveRun,int liveOffset){var body=json("session",session,"reset",reset,"tail",TAIL);if(after>=0){try{body.put("after",after);body.put("messageRevision",messageRevision);if(liveRun>0){body.put("liveRun",liveRun);body.put("liveOffset",liveOffset);}}catch(JSONException ignored){}}watched=session;watchBody=body;sender.execute(()->{try{if(out!=null)send(WATCH,body);}catch(IOException ignored){}});}
    private synchronized void ensure(){if(worker!=null&&worker.isAlive())return;worker=new Thread(this::loop,"tethium-link");worker.setDaemon(true);worker.start();}
    private void loop(){
        int delay=500;
        while(true){
            try{session();delay=500;}catch(Exception e){var l=listener;if(l!=null&&started)l.online(false,e.getMessage());}
            if(!started)return;
            try{Thread.sleep(delay);}catch(InterruptedException e){return;}
            delay=Math.min(5000,delay*2);
        }
    }
    private void session()throws Exception{
        Socket s=openSocket();var w=new Tbin.Writer();var r=new Tbin.Reader();
        try{
            var in=new DataInputStream(new BufferedInputStream(s.getInputStream()));var o=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            write(o,w,HELLO,json("protocol",PROTOCOL,"token",token,"name",Build.MANUFACTURER+" "+Build.MODEL));
            var hello=read(in,r);if(hello.kind!=WELCOME)throw new ApiException(401,hello.body.optString("error","Ноутбук отклонил подключение"));
            s.setSoTimeout(45_000);
            synchronized(writeLock){socket=s;out=o;writer=w;}
            ready.countDown();
            var l=listener;if(l!=null)l.online(true,null);
            var resend=watchBody;if(resend!=null&&!watched.isEmpty())send(WATCH,resend);
            while(true){
                var f=read(in,r);
                switch(f.kind){
                    case RESPONSE:{var future=pending.remove(f.body.optInt("id"));if(future!=null)future.complete(f.body);break;}
                    case STATE:if(l!=null)l.state(f.body,f.body.optBoolean("full"));break;
                    case CONVERSATION:if(l!=null)l.conversation(f.body);break;
                    case LIVE:if(l!=null)l.live(f.body);break;
                    case PING:send(PONG,json());break;
                    default:break;
                }
            }
        }finally{
            synchronized(writeLock){if(socket==s){socket=null;out=null;writer=null;}}
            ready=new CountDownLatch(1);
            try{s.close();}catch(IOException ignored){}
            for(var future:pending.values())future.completeExceptionally(new IOException("Нет связи с ноутбуком"));pending.clear();
        }
    }
    /** Request/response over the link; GET when body is null, POST otherwise. Results are objects. */
    JSONObject request(String path,JSONObject body)throws Exception{
        ensure();
        if(!ready.await(8,TimeUnit.SECONDS)||out==null)throw new IOException("Нет связи с ноутбуком");
        int id=ids.incrementAndGet();var future=new CompletableFuture<JSONObject>();pending.put(id,future);
        var frame=json("id",id,"path",path,"post",body!=null);if(body!=null)frame.put("body",body);
        try{send(REQUEST,frame);}catch(IOException e){pending.remove(id);throw new IOException("Нет связи с ноутбуком");}
        JSONObject response;
        try{response=future.get(path.startsWith("/quota/reset")?70:15,TimeUnit.SECONDS);}
        catch(TimeoutException t){pending.remove(id);throw new IOException("Ноутбук не ответил");}
        catch(ExecutionException x){throw x.getCause() instanceof Exception cause?cause:new IOException(x.getMessage());}
        int status=response.optInt("status",500);if(status!=200)throw new ApiException(status,response.optString("error","Ошибка "+status));
        var result=response.opt("result");return result instanceof JSONObject o?o:new JSONObject();
    }
    String httpBase(){return "http://"+host+":"+httpPort;}
    /** One authorized POST over plain HTTP for callers that do not hold the link open. */
    JSONObject post(String path,JSONObject body)throws Exception{
        var c=open(path);try{
            if(path.startsWith("/file-manager/"))c.setReadTimeout(300_000);
            else if(path.equals("/projects"))c.setReadTimeout(120_000);
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
            try(var o=c.getOutputStream()){o.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();var stream=status<400?c.getInputStream():c.getErrorStream();
            var result=new JSONObject(stream==null?"{}":new String(read(stream),java.nio.charset.StandardCharsets.UTF_8));
            if(status!=200)throw new ApiException(status,result.optString("error","Ошибка "+status));return result;
        }finally{c.disconnect();}
    }
    /** Files over plain HTTP: APKs and preview assets. */
    HttpURLConnection open(String path)throws IOException{
        if(!path.startsWith("/")||path.startsWith("//"))throw new IOException("Invalid path");
        var url=new URL("http://"+host+":"+httpPort+path);var c=(HttpURLConnection)(network==null?url.openConnection():network.openConnection(url));
        c.setConnectTimeout(5000);c.setReadTimeout(30_000);c.setInstanceFollowRedirects(false);if(!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);return c;
    }
    private void send(int kind,JSONObject body)throws IOException{synchronized(writeLock){if(out==null)throw new IOException("Нет связи с ноутбуком");write(out,writer,kind,body);}}
    private static void write(DataOutputStream o,Tbin.Writer w,int kind,JSONObject body)throws IOException{byte[] raw=w.encode(kind,body);byte[] data=pack(raw);o.writeInt(data.length);o.write(data);o.flush();}
    private static Frame read(DataInputStream in,Tbin.Reader r)throws IOException{
        int size=in.readInt();if(size<2||size>MAX_FRAME+5)throw new IOException("Bad frame length");byte[] data=new byte[size];in.readFully(data);byte[] raw=unpack(data);
        if(raw.length<2)throw new IOException("Empty frame");
        try{Object body=r.decode(raw,1);return new Frame(raw[0]&255,body instanceof JSONObject o?o:new JSONObject());}catch(JSONException e){throw new IOException("Повреждённый кадр");}
    }
    static byte[] pack(byte[] raw){
        if(raw.length>=64){byte[] packed=Zstd.compress(raw,3);if(packed.length+4<raw.length){var data=new byte[5+packed.length];data[0]=1;data[1]=(byte)(raw.length>>>24);data[2]=(byte)(raw.length>>>16);data[3]=(byte)(raw.length>>>8);data[4]=(byte)raw.length;System.arraycopy(packed,0,data,5,packed.length);return data;}}
        var data=new byte[1+raw.length];System.arraycopy(raw,0,data,1,raw.length);return data;
    }
    static byte[] unpack(byte[] data)throws IOException{
        if(data.length<2)throw new IOException("Bad frame");
        if((data[0]&1)==0)return Arrays.copyOfRange(data,1,data.length);
        if(data.length<6)throw new IOException("Bad frame");
        int size=((data[1]&255)<<24)|((data[2]&255)<<16)|((data[3]&255)<<8)|(data[4]&255);if(size<1||size>MAX_FRAME)throw new IOException("Bad frame size");
        byte[] raw=Zstd.decompress(Arrays.copyOfRange(data,5,data.length),size);if(raw.length!=size)throw new IOException("Corrupt frame");return raw;
    }
    static JSONObject json(Object... pairs){var j=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);}catch(Exception e){throw new IllegalArgumentException(e);}return j;}
    static byte[] read(InputStream in)throws IOException{var out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)out.write(b,0,n);return out.toByteArray();}
}

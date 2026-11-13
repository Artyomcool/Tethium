package dev.tethium.mobile;

import android.net.Network;
import android.util.Log;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

interface FrameChannel {
    byte[] read()throws IOException;
    default byte[] read(long timeoutMillis)throws IOException{return read();}
    void send(byte[] raw)throws IOException;
    boolean open();
    boolean remote();
    void close();
}

final class LanFrameChannel implements FrameChannel {
    private final Socket socket;private final DataInputStream in;private final DataOutputStream out;
    LanFrameChannel(String host,int port,Network network)throws IOException{socket=network==null?new Socket():network.getSocketFactory().createSocket();socket.connect(new InetSocketAddress(host,port),5_000);socket.setTcpNoDelay(true);socket.setSoTimeout(30_000);in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));}
    public byte[] read()throws IOException{int size=in.readInt();if(size<2||size>Connection.MAX_FRAME+5)throw new IOException("Bad frame length");byte[] data=new byte[size];in.readFully(data);return Connection.unpack(data);}
    public synchronized void send(byte[] raw)throws IOException{byte[] data=Connection.pack(raw);out.writeInt(data.length);out.write(data);out.flush();}
    public boolean open(){return !socket.isClosed();}public boolean remote(){return false;}
    void timeout(int millis)throws SocketException{socket.setSoTimeout(millis);}
    public void close(){try{socket.close();}catch(IOException ignored){}}
}

final class RelayFrameChannel extends WebSocketClient implements FrameChannel {
    private static final String TAG="TethiumLink";
    private static final byte[] CLOSED=new byte[0];
    private final RelayCrypto crypto;private final BlockingQueue<byte[]> incoming=new LinkedBlockingQueue<>();private volatile IOException failure;
    RelayFrameChannel(String base,String ticket,String secret,Network network)throws Exception{
        this(new RelayCrypto(secret),base,ticket,network);
    }
    private RelayFrameChannel(RelayCrypto crypto,String base,String ticket,Network network)throws Exception{
        super(URI.create(base.replaceFirst("/+$","")+"/v1/connect/"+crypto.room),Map.of("x-tethium-ticket",ticket,"x-tethium-role","phone","x-tethium-auth",crypto.auth));this.crypto=crypto;
        if(network!=null)setSocketFactory(network.getSocketFactory());
        if(!connectBlocking(12,TimeUnit.SECONDS))throw new IOException("Cloudflare relay did not connect");
    }
    public byte[] read()throws IOException{return read(0);}
    public byte[] read(long timeoutMillis)throws IOException{try{byte[] value=timeoutMillis<=0?incoming.take():incoming.poll(timeoutMillis,TimeUnit.MILLISECONDS);if(value==null){close();throw new SocketTimeoutException("Cloudflare relay did not answer");}if(value==CLOSED)throw failure==null?new EOFException("Relay closed"):failure;return value;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new EOFException("Relay interrupted");}}
    public void send(byte[] raw){if(!isOpen())throw new IllegalStateException("Relay closed");try{super.send(crypto.seal(Connection.pack(raw)));}catch(Exception e){throw new IllegalStateException("Relay send failed",e);}}
    public boolean open(){return isOpen();}public boolean remote(){return true;}public void close(){super.close();finish(null);}
    public void onOpen(ServerHandshake handshake){Log.i(TAG,"relay websocket open status="+handshake.getHttpStatus()+" room="+crypto.room.substring(0,8));}
    public void onMessage(String message){}
    public void onMessage(ByteBuffer bytes){byte[] envelope=new byte[bytes.remaining()];bytes.get(envelope);try{incoming.offer(Connection.unpack(crypto.open(envelope)));}catch(IOException e){closeConnection(1002,"bad envelope");finish(e);}}
    public void onClose(int code,String reason,boolean remote){Log.w(TAG,"relay websocket closed code="+code+" reason="+reason+" remote="+remote);finish(reason==null||reason.isBlank()?null:new IOException("Cloudflare "+code+": "+reason));}
    public void onError(Exception error){Log.w(TAG,"relay websocket error",error);finish(error instanceof IOException e?e:new IOException(error));}
    private void finish(IOException error){if(error!=null)failure=error;incoming.offer(CLOSED);}
}

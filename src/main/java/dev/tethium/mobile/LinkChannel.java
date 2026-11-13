package dev.tethium.mobile;

import dev.tethium.link.Frames;
import java.io.*;
import java.net.Socket;

/** Same Tbin frame stream over either the LAN socket or the encrypted relay WebSocket. */
interface LinkChannel extends AutoCloseable {
    byte[] read() throws IOException;
    void send(byte[] raw) throws IOException;
    boolean open();
    boolean remote();
    String peer();
    default void idleTimeout(int millis) throws IOException {}
    default String lanAddress(){return null;}
    void close();
}

final class LanLinkChannel implements LinkChannel {
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final TrafficLedger traffic;
    LanLinkChannel(Socket socket,TrafficLedger traffic)throws IOException{
        this.socket=socket;this.traffic=traffic;
        in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }
    public byte[] read()throws IOException{int size=in.readInt();if(size<2||size>Frames.MAX_FRAME+5)throw new IOException("Bad frame length");byte[] data=new byte[size];in.readFully(data);traffic.bytes(false,false,size+4L);return Frames.unpack(data);}
    public synchronized void send(byte[] raw)throws IOException{byte[] data=Frames.pack(raw);out.writeInt(data.length);out.write(data);out.flush();traffic.bytes(false,true,data.length+4L);}
    public boolean open(){return !socket.isClosed();}
    public boolean remote(){return false;}
    public String peer(){return socket.getRemoteSocketAddress().toString();}
    public String lanAddress(){return socket.getInetAddress()==null?null:socket.getInetAddress().getHostAddress();}
    public void idleTimeout(int millis)throws IOException{socket.setSoTimeout(millis);}
    public void close(){try{socket.close();}catch(IOException ignored){}}
}

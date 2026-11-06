package dev.tethium.host;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** What the CPU sampler needs from Windows, asked directly: one system call per snapshot and no child processes. */
final class WindowsProcesses {
    static boolean supported(){return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")&&ADDRESS.byteSize()==8;}
    record Row(long pid,long parent,long started,long nanos,long memory,String image){}
    record Socket(String local,String remote,long pid){}
    private static MethodHandle fn(String library,String name,MemoryLayout result,MemoryLayout...args){
        return Linker.nativeLinker().downcallHandle(SymbolLookup.libraryLookup(library,Arena.global()).find(name).orElseThrow(),FunctionDescriptor.of(result,args));
    }
    private static final class Nt {
        static final MethodHandle SYSTEM=fn("ntdll.dll","NtQuerySystemInformation",JAVA_INT,JAVA_INT,ADDRESS,JAVA_INT,ADDRESS);
        static final MethodHandle PROCESS=fn("ntdll.dll","NtQueryInformationProcess",JAVA_INT,ADDRESS,JAVA_INT,ADDRESS,JAVA_INT,ADDRESS);
        static final MethodHandle OPEN=fn("kernel32.dll","OpenProcess",ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT);
        static final MethodHandle CLOSE=fn("kernel32.dll","CloseHandle",JAVA_INT,ADDRESS);
    }
    private static final class Ip {
        static final MethodHandle TCP=fn("iphlpapi.dll","GetExtendedTcpTable",JAVA_INT,ADDRESS,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT);
    }
    private static final int SYSTEM_PROCESS_INFORMATION=5,PROCESS_COMMAND_LINE=60,QUERY_LIMITED=0x1000,LENGTH_MISMATCH=0xC0000004,INSUFFICIENT_BUFFER=122,TCP_OWNER_PID_CONNECTIONS=4,AF_INET=2,AF_INET6=23;
    private static final long FILETIME_TO_UNIX_MS=11_644_473_600_000L;
    private MemorySegment buffer=Arena.ofAuto().allocate(1<<20,8);

    /** SYSTEM_PROCESS_INFORMATION holds pid, parent, start, CPU, private memory and name for every process, whoever owns it. */
    List<Row> snapshot(){
        try(var arena=Arena.ofConfined()){
            var needed=arena.allocate(JAVA_INT);
            while(true){
                int status=(int)Nt.SYSTEM.invokeExact(SYSTEM_PROCESS_INFORMATION,buffer,(int)buffer.byteSize(),needed);
                if(status==0)break;
                if(status!=LENGTH_MISMATCH)throw new IllegalStateException("NtQuerySystemInformation: "+Integer.toHexString(status));
                buffer=Arena.ofAuto().allocate(Math.max(buffer.byteSize()*2,needed.get(JAVA_INT,0)*5L/4),8);
            }
        }catch(RuntimeException e){throw e;}catch(Throwable t){throw new IllegalStateException(t);}
        var rows=new ArrayList<Row>();long base=buffer.address();
        for(long at=0;;){
            long pid=buffer.get(JAVA_LONG,at+0x50);
            if(pid!=0){
                int length=Short.toUnsignedInt(buffer.get(JAVA_SHORT,at+0x38));long name=buffer.get(JAVA_LONG,at+0x40);
                rows.add(new Row(pid,buffer.get(JAVA_LONG,at+0x58),buffer.get(JAVA_LONG,at+0x20)/10_000-FILETIME_TO_UNIX_MS,(buffer.get(JAVA_LONG,at+0x28)+buffer.get(JAVA_LONG,at+0x30))*100,buffer.get(JAVA_LONG,at+0x08),length==0||name==0?"":text(buffer,name-base,length)));
            }
            int next=buffer.get(JAVA_INT,at);if(next==0)return rows;at+=next;
        }
    }
    /** Null when Windows refuses, as it does for protected processes. */
    String commandLine(long pid){
        try(var arena=Arena.ofConfined()){
            var handle=(MemorySegment)Nt.OPEN.invokeExact(QUERY_LIMITED,0,(int)pid);
            if(handle.address()==0)return null;
            try{
                var out=arena.allocate(70_000,8);var needed=arena.allocate(JAVA_INT);
                int status=(int)Nt.PROCESS.invokeExact(handle,PROCESS_COMMAND_LINE,out,(int)out.byteSize(),needed);
                int length=Short.toUnsignedInt(out.get(JAVA_SHORT,0));
                return status!=0||length==0?null:text(out,out.get(JAVA_LONG,8)-out.address(),length);
            }finally{int ignored=(int)Nt.CLOSE.invokeExact(handle);}
        }catch(RuntimeException e){throw e;}catch(Throwable t){throw new IllegalStateException(t);}
    }
    /** Loopback TCP connections with their owners. */
    List<Socket> loopback(){var rows=new ArrayList<Socket>();read(AF_INET,rows);read(AF_INET6,rows);return rows;}
    private static void read(int family,List<Socket> rows){
        try(var arena=Arena.ofConfined()){
            var size=arena.allocate(JAVA_INT);size.set(JAVA_INT,0,1<<16);MemorySegment table;
            while(true){
                table=arena.allocate(size.get(JAVA_INT,0),8);
                int status=(int)Ip.TCP.invokeExact(table,size,0,family,TCP_OWNER_PID_CONNECTIONS,0);
                if(status==0)break;
                if(status!=INSUFFICIENT_BUFFER)throw new IllegalStateException("GetExtendedTcpTable: "+status);
            }
            boolean v4=family==AF_INET;int count=table.get(JAVA_INT,0),width=v4?24:56;
            for(int i=0;i<count;i++){
                long at=4L+i*width;
                String local=endpoint(table,at+(v4?4:0),v4),remote=endpoint(table,at+(v4?12:24),v4);
                if(local!=null&&remote!=null)rows.add(new Socket(local,remote,Integer.toUnsignedLong(table.get(JAVA_INT,at+(v4?20:52)))));
            }
        }catch(RuntimeException e){throw e;}catch(Throwable t){throw new IllegalStateException(t);}
    }
    private static String endpoint(MemorySegment table,long at,boolean v4){
        long portAt=at+(v4?4:20);int port=(table.get(JAVA_BYTE,portAt)&255)<<8|table.get(JAVA_BYTE,portAt+1)&255;
        if(v4)return dotted(table,at,port);
        boolean zeros=true;for(int i=0;i<10;i++)zeros&=table.get(JAVA_BYTE,at+i)==0;if(!zeros)return null;
        int b10=table.get(JAVA_BYTE,at+10)&255,b11=table.get(JAVA_BYTE,at+11)&255;
        if(b10==255&&b11==255)return dotted(table,at+12,port); // A dual-stack socket talking to an IPv4 peer.
        boolean one=b10==0&&b11==0&&table.get(JAVA_BYTE,at+15)==1;for(int i=12;i<15;i++)one&=table.get(JAVA_BYTE,at+i)==0;
        return one?"[::1]:"+port:null;
    }
    private static String dotted(MemorySegment table,long at,int port){
        if(table.get(JAVA_BYTE,at)!=127)return null;
        return "127."+(table.get(JAVA_BYTE,at+1)&255)+"."+(table.get(JAVA_BYTE,at+2)&255)+"."+(table.get(JAVA_BYTE,at+3)&255)+":"+port;
    }
    private static String text(MemorySegment segment,long offset,int bytes){return new String(segment.asSlice(offset,bytes).toArray(JAVA_CHAR_UNALIGNED));}
}

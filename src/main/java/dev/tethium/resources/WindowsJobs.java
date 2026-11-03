package dev.tethium.resources;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** Named job objects survive host handoff. Deliberately never enables KILL_ON_JOB_CLOSE. */
public final class WindowsJobs implements AutoCloseable {
    public static boolean supported(){return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")&&ADDRESS.byteSize()==8;}
    private static final class Api {
        static final SymbolLookup LIB=SymbolLookup.libraryLookup("Kernel32.dll",Arena.global());
        static MethodHandle fn(String name,MemoryLayout result,MemoryLayout...args){return Linker.nativeLinker().downcallHandle(LIB.find(name).orElseThrow(),FunctionDescriptor.of(result,args),Linker.Option.captureCallState("GetLastError"));}
        static final MethodHandle CREATE=fn("CreateJobObjectW",ADDRESS,ADDRESS,ADDRESS),SET=fn("SetInformationJobObject",JAVA_INT,ADDRESS,JAVA_INT,ADDRESS,JAVA_INT),QUERY=fn("QueryInformationJobObject",JAVA_INT,ADDRESS,JAVA_INT,ADDRESS,JAVA_INT,ADDRESS),OPEN=fn("OpenProcess",ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT),ASSIGN=fn("AssignProcessToJobObject",JAVA_INT,ADDRESS,ADDRESS),IN=fn("IsProcessInJob",JAVA_INT,ADDRESS,ADDRESS,ADDRESS),CLOSE=fn("CloseHandle",JAVA_INT,ADDRESS);
        private static final ThreadLocal<Integer> ERROR=ThreadLocal.withInitial(()->0);
        static Object call(MethodHandle fn,Object...args)throws Throwable{
            try(var arena=Arena.ofConfined()){
                var state=arena.allocate(Linker.Option.captureStateLayout());var values=new ArrayList<Object>();values.add(state);Collections.addAll(values,args);
                var result=fn.invokeWithArguments(values);ERROR.set(state.get(JAVA_INT,Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))));return result;
            }
        }
        static int error(){return ERROR.get();}
    }
    private final String prefix;
    private final Map<String,MemorySegment> handles=new LinkedHashMap<>();
    private final Set<String> fresh=new HashSet<>();
    public WindowsJobs(String namespace){if(!supported())throw new UnsupportedOperationException("CPU limits require 64-bit Windows");prefix="Local\\TethiumCPU-"+namespace+"-";}
    private static RuntimeException failed(String op)throws Throwable{return new IllegalStateException(op+": Windows "+Api.error());}
    private static RuntimeException failure(Throwable t){return t instanceof RuntimeException r?r:new IllegalStateException(t);}
    private MemorySegment job(String id){return handles.computeIfAbsent(id,key->{try(var arena=Arena.ofConfined()){
        byte[] name=(prefix+key+'\0').getBytes(StandardCharsets.UTF_16LE);var bytes=arena.allocate(name.length,2);bytes.copyFrom(MemorySegment.ofArray(name));
        var handle=(MemorySegment)Api.call(Api.CREATE,MemorySegment.NULL,bytes);int error=Api.error();if(handle.address()==0)throw new IllegalStateException("CreateJobObject: Windows "+error);if(error!=183)fresh.add(key);return handle;
    }catch(Throwable t){throw failure(t);}});}
    public synchronized void retain(String id){job(id);}
    public synchronized void configure(String id,int rate,int priority,boolean onlyNew){
        var handle=job(id);if(onlyNew&&!fresh.contains(id))return;
        try(var arena=Arena.ofConfined()){
            var cpu=arena.allocate(8,4);cpu.set(JAVA_INT,0,rate<=0?0:5);cpu.set(JAVA_INT,4,rate<=0?10000:Math.clamp(rate,1,10000));
            if((rate>0||rate(id)>0)&&(int)Api.call(Api.SET,handle,15,cpu,8)==0)throw failed("CPU limit");
            if(priority>=0){var basic=arena.allocate(64,8);basic.set(JAVA_INT,16,0x20);basic.set(JAVA_INT,56,priority);if((int)Api.call(Api.SET,handle,2,basic,64)==0)throw failed("Priority");}
            fresh.remove(id);
        }catch(Throwable t){throw failure(t);}
    }
    public synchronized void attach(long pid,String...chain){
        if(pid==ProcessHandle.current().pid()&&!Boolean.getBoolean("tethium.worker.resources"))throw new IllegalArgumentException("The controller must remain outside worker limits");
        try(var arena=Arena.ofConfined()){
            var process=(MemorySegment)Api.call(Api.OPEN,0x0100|0x0001|0x0400,0,(int)pid);if(process.address()==0)throw failed("OpenProcess "+pid);
            try{for(String id:chain){var handle=job(id);var inside=arena.allocate(JAVA_INT);if((int)Api.call(Api.IN,process,handle,inside)==0)throw failed("IsProcessInJob");if(inside.get(JAVA_INT,0)==0&&(int)Api.call(Api.ASSIGN,handle,process)==0)throw failed("AssignProcessToJobObject "+pid+" → "+id);}}
            finally{int ignored=(int)Api.call(Api.CLOSE,process);}
        }catch(Throwable t){throw failure(t);}
    }
    public synchronized Set<Long> members(String id){
        try(var arena=Arena.ofConfined()){
            for(int size=256;size<=1_048_576;size*=2){var data=arena.allocate(size,8);int ok=(int)Api.call(Api.QUERY,job(id),3,data,size,MemorySegment.NULL);if(ok!=0){var result=new HashSet<Long>();int count=data.get(JAVA_INT,4);for(int i=0;i<count;i++)result.add(data.get(JAVA_LONG,8L+8L*i));return result;}if(Api.error()!=234)throw failed("Job process list");}
            throw new IllegalStateException("Too many job processes");
        }catch(Throwable t){throw failure(t);}
    }
    public synchronized long cpuNanos(String id){try(var arena=Arena.ofConfined()){
        var data=arena.allocate(48,8);if((int)Api.call(Api.QUERY,job(id),1,data,48,MemorySegment.NULL)==0)throw failed("Job CPU accounting");return (data.get(JAVA_LONG,0)+data.get(JAVA_LONG,8))*100;
    }catch(Throwable t){throw failure(t);}}
    public synchronized int rate(String id){try(var arena=Arena.ofConfined()){var data=arena.allocate(8,4);if((int)Api.call(Api.QUERY,job(id),15,data,8,MemorySegment.NULL)==0)throw failed("Query CPU limit");return data.get(JAVA_INT,0)==0?0:data.get(JAVA_INT,4);}catch(Throwable t){throw failure(t);}}
    @Override public synchronized void close(){for(var handle:handles.values())try{int ignored=(int)Api.call(Api.CLOSE,handle);}catch(Throwable ignored){}handles.clear();fresh.clear();}
}

package dev.tethium.host;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Prevent idle system sleep while an explicitly autonomous goal is active. Display sleep is unaffected. */
final class AwakeGuard implements AutoCloseable {
    private static final int CONTINUOUS=0x80000000,SYSTEM_REQUIRED=1;
    private final ScheduledExecutorService thread=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("autonomy-awake").factory());
    private final MethodHandle call;
    private boolean held;
    AwakeGuard(BooleanSupplier needed){
        MethodHandle nativeCall=null;
        if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))try{nativeCall=Linker.nativeLinker().downcallHandle(SymbolLookup.libraryLookup("Kernel32.dll",Arena.global()).find("SetThreadExecutionState").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT));}catch(Throwable e){System.err.println("Autonomy wake lock unavailable: "+e);}
        call=nativeCall;
        if(call!=null)thread.scheduleWithFixedDelay(()->{try{set(needed.getAsBoolean());}catch(Throwable e){System.err.println("Autonomy wake lock: "+e);}},0,5,TimeUnit.SECONDS);
    }
    private void set(boolean value)throws Throwable{if(held==value||call==null)return;int result=(int)call.invokeExact(CONTINUOUS|(value?SYSTEM_REQUIRED:0));if(result==0)throw new IllegalStateException("SetThreadExecutionState failed");held=value;}
    @Override public void close()throws Exception{try{thread.submit(()->{try{set(false);}catch(Throwable e){System.err.println(e);}}).get(6,TimeUnit.SECONDS);}finally{thread.shutdownNow();}}
}

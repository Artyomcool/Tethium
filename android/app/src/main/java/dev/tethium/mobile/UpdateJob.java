package dev.tethium.mobile;

import android.app.job.*;
import java.util.concurrent.Future;

public final class UpdateJob extends JobService {
    private Future<?> work;
    @Override public boolean onStartJob(JobParameters p){
        work=AppUpdates.IO.submit(()->{try{AppUpdates.check(this,AppUpdates.connection(this,android.os.Build.VERSION.SDK_INT>=28?p.getNetwork():null),true);}catch(Exception ignored){}finally{if(!Thread.currentThread().isInterrupted())jobFinished(p,false);}});return true;
    }
    @Override public boolean onStopJob(JobParameters p){if(work!=null)work.cancel(true);return true;}
}

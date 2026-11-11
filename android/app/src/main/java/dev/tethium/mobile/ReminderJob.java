package dev.tethium.mobile;

import android.app.job.*;
import java.util.concurrent.Future;

/** With any network: delivers answers given offline and picks up reminders made while the app was closed. */
public final class ReminderJob extends JobService {
    private Future<?> work;
    @Override public boolean onStartJob(JobParameters p){
        work=Reminders.IO.submit(()->{boolean retry=false;try{Reminders.flush(this,android.os.Build.VERSION.SDK_INT>=28?p.getNetwork():null);}catch(Exception e){retry=p.getJobId()!=7031;}finally{if(!Thread.currentThread().isInterrupted())jobFinished(p,retry);}});
        return true;
    }
    @Override public boolean onStopJob(JobParameters p){if(work!=null)work.cancel(true);return true;}
}

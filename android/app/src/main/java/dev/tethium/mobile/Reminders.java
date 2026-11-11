package dev.tethium.mobile;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * Reminders on the phone. The laptop sends the active ones in its state ahead of time, so the phone raises a silent notification
 * at the due time by itself, out of reach of the laptop too. The notification cannot be swiped away and has no buttons: a tap
 * opens the reminder in the app, where the five answers are. An answer is applied here at once and kept until the laptop has it.
 */
final class Reminders {
    static final String CHANNEL="reminders",EXTRA="reminder",LIST="reminderList";
    static final String ALARM="dev.tethium.mobile.REMINDER_ALARM",REPOST="dev.tethium.mobile.REMINDER_REPOST";
    static final int[] SNOOZES={10,60,120,1440};
    static final String[] SNOOZE_LABELS={"10 минут","1 час","2 часа","Сутки"};
    private static final int SYNC_JOB=7031,DELIVER_JOB=7032;
    static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"tethium-reminders");t.setDaemon(true);return t;});
    /** Shown screens refresh themselves when the local list changes. */
    static volatile Runnable changed;
    private Reminders(){}

    static SharedPreferences prefs(Context c){return c.getSharedPreferences("reminders",0);}
    static synchronized JSONArray list(Context c){try{return new JSONArray(prefs(c).getString("list","[]"));}catch(JSONException e){return new JSONArray();}}
    private static synchronized JSONArray answers(Context c){try{return new JSONArray(prefs(c).getString("answers","[]"));}catch(JSONException e){return new JSONArray();}}
    static JSONObject find(Context c,String id){var all=list(c);for(int i=0;i<all.length();i++){var r=all.optJSONObject(i);if(r!=null&&id.equals(r.optString("id")))return r;}return null;}
    static boolean firing(JSONObject r){return r.optLong("dueAt")<=System.currentTimeMillis();}
    static int firingCount(Context c){int n=0;var all=list(c);for(int i=0;i<all.length();i++)if(firing(all.optJSONObject(i)))n++;return n;}

    static void init(Context c){
        var channel=new NotificationChannel(CHANNEL,"Напоминания",NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Не исчезают, пока не ответите: отложить или «Готово»");channel.setSound(null,null);channel.enableVibration(false);channel.setVibrationPattern(null);channel.setShowBadge(true);channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        c.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        var scheduler=c.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(SYNC_JOB)==null)scheduler.schedule(new JobInfo.Builder(SYNC_JOB,new ComponentName(c,ReminderJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60_000L).setPersisted(true).build());
        reschedule(c);
    }

    /** The laptop's active reminders, with the answers it has not received yet applied on top. */
    static synchronized void sync(Context c,JSONArray server){
        if(server==null)return;
        var pending=answers(c);var result=new JSONArray();
        for(int i=0;i<server.length();i++){
            var r=server.optJSONObject(i);if(r==null)continue;String id=r.optString("id");boolean gone=false;
            for(int j=0;j<pending.length();j++){var a=pending.optJSONObject(j);if(!id.equals(a.optString("id")))continue;
                String action=a.optString("action");
                if(!action.equals("snooze"))gone=true;
                else if(r.optLong("dueAt")==a.optLong("dueAt"))try{r.put("dueAt",a.optLong("at")+a.optInt("minutes")*60_000L);r.put("snoozes",r.optInt("snoozes")+1);}catch(JSONException ignored){}
            }
            if(!gone)result.put(r);
        }
        prefs(c).edit().putString("list",result.toString()).putLong("synced",System.currentTimeMillis()).apply();
        reschedule(c);
    }

    /** One of the five answers, or removal from the list. Works offline: applied here first, delivered when the laptop is reachable. */
    static synchronized void answer(Context c,String id,String action,int minutes){
        var r=find(c,id);if(r==null)return;long now=System.currentTimeMillis();
        try{
            var answer=new JSONObject().put("id",id).put("action",action).put("at",now).put("dueAt",r.optLong("dueAt"));if(minutes>0)answer.put("minutes",minutes);
            var pending=answers(c);pending.put(answer);
            var all=list(c);var kept=new JSONArray();
            for(int i=0;i<all.length();i++){var x=all.optJSONObject(i);if(!id.equals(x.optString("id"))){kept.put(x);continue;}if(action.equals("snooze")){x.put("dueAt",now+minutes*60_000L);x.put("snoozes",x.optInt("snoozes")+1);kept.put(x);}}
            prefs(c).edit().putString("answers",pending.toString()).putString("list",kept.toString()).apply();
        }catch(JSONException ignored){}
        c.getSystemService(NotificationManager.class).cancel(CHANNEL,id.hashCode());
        reschedule(c);deliver(c);
    }

    /** Sends the answers in order; a refused one is dropped, a lost link leaves the rest for a job that waits for the network. */
    static void deliver(Context c){
        var app=c.getApplicationContext();
        IO.execute(()->{try{flush(app,null);}catch(Exception e){retryLater(app);}});
    }
    static void flush(Context c,android.net.Network network)throws Exception{
        if(answers(c).length()==0&&network==null)return;
        var connection=AppUpdates.connection(c,network);
        try{
            while(true){
                var pending=answers(c);if(pending.length()==0)break;var a=pending.getJSONObject(0);
                var body=new JSONObject().put("by","phone").put("at",a.optLong("at")).put("dueAt",a.optLong("dueAt"));if(a.has("minutes"))body.put("minutes",a.optInt("minutes"));
                try{connection.request("/reminders/"+a.getString("id")+"/"+a.getString("action"),body);}
                catch(Connection.ApiException refused){if(refused.status>=500)throw refused;}
                synchronized(Reminders.class){var now=answers(c);var rest=new JSONArray();boolean dropped=false;for(int i=0;i<now.length();i++){var x=now.optJSONObject(i);if(!dropped&&x.toString().equals(a.toString())){dropped=true;continue;}rest.put(x);}prefs(c).edit().putString("answers",rest.toString()).apply();}
            }
            sync(c,connection.request("/reminders",null).optJSONArray("reminders"));
        }finally{connection.close();}
    }
    private static void retryLater(Context c){
        c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(DELIVER_JOB,new ComponentName(c,ReminderJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build());
    }

    /** Raises the notifications that are due, drops those answered elsewhere, and sets one alarm for the next due time. */
    static synchronized void reschedule(Context c){
        var notifications=c.getSystemService(NotificationManager.class);var all=list(c);long now=System.currentTimeMillis(),next=Long.MAX_VALUE;
        var shown=new HashSet<>(prefs(c).getStringSet("shown",Set.of()));var keep=new HashSet<String>();
        for(int i=0;i<all.length();i++){
            var r=all.optJSONObject(i);String id=r.optString("id");long due=r.optLong("dueAt");
            if(due<=now){keep.add(id);notifications.notify(CHANNEL,id.hashCode(),notification(c,r));}
            else{notifications.cancel(CHANNEL,id.hashCode());next=Math.min(next,due);}
        }
        for(String id:shown)if(!keep.contains(id))notifications.cancel(CHANNEL,id.hashCode());
        prefs(c).edit().putStringSet("shown",keep).apply();
        var alarms=c.getSystemService(AlarmManager.class);var tick=PendingIntent.getBroadcast(c,7033,new Intent(c,ReminderReceiver.class).setAction(ALARM),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        if(next==Long.MAX_VALUE)alarms.cancel(tick);
        else if(Build.VERSION.SDK_INT<31||alarms.canScheduleExactAlarms())alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,tick);
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,tick);
        try{ReminderWidget.refresh(c);}catch(RuntimeException ignored){}
        var listener=changed;if(listener!=null)listener.run();
    }
    /** Silent, no buttons, comes back when swiped; a tap opens this reminder in the app. */
    private static Notification notification(Context c,JSONObject r){
        String id=r.optString("id");
        var open=PendingIntent.getActivity(c,id.hashCode(),new Intent(c,MainActivity.class).putExtra(EXTRA,id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        var back=PendingIntent.getBroadcast(c,id.hashCode(),new Intent(c,ReminderReceiver.class).setAction(REPOST).putExtra(EXTRA,id),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(r.optString("text")).setContentText("Напоминание · откройте, чтобы отложить или отметить «Готово»")
            .setStyle(new Notification.BigTextStyle().bigText(r.optString("text")+"\n"+when(r.optLong("dueAt"))+" · откройте, чтобы ответить")).setCategory(Notification.CATEGORY_REMINDER)
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setShowWhen(true).setWhen(r.optLong("dueAt")).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open).setDeleteIntent(back).setGroup(CHANNEL).build();
    }
    static String when(long at){
        var day=Calendar.getInstance();day.setTimeInMillis(at);var today=Calendar.getInstance();
        String time=new SimpleDateFormat("HH:mm",Locale.forLanguageTag("ru")).format(new Date(at));
        int diff=(day.get(Calendar.YEAR)-today.get(Calendar.YEAR))*400+day.get(Calendar.DAY_OF_YEAR)-today.get(Calendar.DAY_OF_YEAR);
        if(diff==0)return "сегодня в "+time;if(diff==1)return "завтра в "+time;if(diff==-1)return "вчера в "+time;
        return new SimpleDateFormat("d MMMM, HH:mm",Locale.forLanguageTag("ru")).format(new Date(at));
    }
}

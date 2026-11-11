package dev.tethium.mobile;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

/** Home-screen agenda of reminders, like a calendar's schedule view. It reads the phone's own list, so it works out of reach of the laptop. */
public final class ReminderWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){for(int id:ids)manager.updateAppWidget(id,views(c,id));manager.notifyAppWidgetViewDataChanged(ids,R.id.widget_list);}
    @Override public void onEnabled(Context c){Reminders.deliver(c);}

    /** Called whenever the local list changes or a reminder becomes due. */
    static void refresh(Context c){
        var manager=AppWidgetManager.getInstance(c);if(manager==null)return;
        int[] ids=manager.getAppWidgetIds(new ComponentName(c,ReminderWidget.class));if(ids.length==0)return;
        for(int id:ids)manager.updateAppWidget(id,views(c,id));
        manager.notifyAppWidgetViewDataChanged(ids,R.id.widget_list);
    }
    private static RemoteViews views(Context c,int widget){
        var v=new RemoteViews(c.getPackageName(),R.layout.widget_reminders);
        int firing=Reminders.firingCount(c),total=Reminders.list(c).length();
        v.setTextViewText(R.id.widget_summary,firing>0?"Ждут ответа: "+firing:total==0?"Нет активных":"Активных: "+total);
        v.setTextColor(R.id.widget_summary,firing>0?0xFFFFCD89:0xFFA4ADBF);
        var service=new Intent(c,ReminderWidgetService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widget);service.setData(Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(R.id.widget_list,service);v.setEmptyView(R.id.widget_list,R.id.widget_empty);
        int mutable=Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0;
        v.setPendingIntentTemplate(R.id.widget_list,PendingIntent.getActivity(c,7040,new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),mutable|PendingIntent.FLAG_UPDATE_CURRENT));
        var open=PendingIntent.getActivity(c,7041,new Intent(c,MainActivity.class).putExtra(Reminders.LIST,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.widget_header,open);v.setOnClickPendingIntent(R.id.widget_empty,open);
        v.setOnClickPendingIntent(R.id.widget_add,PendingIntent.getActivity(c,7042,new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }
}

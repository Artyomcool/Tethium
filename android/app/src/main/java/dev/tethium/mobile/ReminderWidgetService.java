package dev.tethium.mobile;

import android.content.*;
import android.widget.*;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;

/** Rows of the agenda: a day heading over the first reminder of each day; due ones are grouped first. */
public final class ReminderWidgetService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent){return new Factory(getApplicationContext());}
    private record Row(String day,JSONObject reminder){}
    private static final class Factory implements RemoteViewsFactory {
        private final Context c;private List<Row> rows=List.of();
        Factory(Context c){this.c=c;}
        public void onCreate(){}
        public void onDestroy(){}
        public void onDataSetChanged(){
            var all=Reminders.list(c);var items=new ArrayList<JSONObject>();for(int i=0;i<all.length();i++)items.add(all.optJSONObject(i));
            items.sort(Comparator.comparingLong(r->r.optLong("dueAt")));
            var next=new ArrayList<Row>();String last=null;
            for(var r:items){String day=Reminders.firing(r)?"Ждут ответа":day(r.optLong("dueAt"));next.add(new Row(day.equals(last)?null:day,r));last=day;}
            rows=next;
        }
        private static String day(long at){
            var d=Calendar.getInstance();d.setTimeInMillis(at);var t=Calendar.getInstance();
            int diff=(d.get(Calendar.YEAR)-t.get(Calendar.YEAR))*400+d.get(Calendar.DAY_OF_YEAR)-t.get(Calendar.DAY_OF_YEAR);
            String date=new SimpleDateFormat("EEE, d MMMM",Locale.forLanguageTag("ru")).format(new Date(at));
            return diff==0?"Сегодня · "+date:diff==1?"Завтра · "+date:date.substring(0,1).toUpperCase(Locale.ROOT)+date.substring(1);
        }
        public int getCount(){return rows.size();}
        public RemoteViews getViewAt(int position){
            var v=new RemoteViews(c.getPackageName(),R.layout.widget_reminder_item);if(position>=rows.size())return v;
            var row=rows.get(position);var r=row.reminder();boolean firing=Reminders.firing(r);long due=r.optLong("dueAt");
            v.setViewVisibility(R.id.item_day,row.day()==null?android.view.View.GONE:android.view.View.VISIBLE);
            if(row.day()!=null){v.setTextViewText(R.id.item_day,row.day());v.setTextColor(R.id.item_day,firing?0xFFFFCD89:0xFFB8ADFF);}
            v.setTextViewText(R.id.item_time,new SimpleDateFormat(firing&&!sameDay(due)?"d.MM":"HH:mm",Locale.ROOT).format(new Date(due)));
            v.setTextViewText(R.id.item_text,r.optString("text"));
            v.setInt(R.id.item_bar,"setBackgroundColor",firing?0xFFFFCD89:0xFFB8ADFF);
            v.setTextColor(R.id.item_time,firing?0xFFFFCD89:0xFFA4ADBF);
            v.setOnClickFillInIntent(R.id.item_row,new Intent().putExtra(Reminders.EXTRA,r.optString("id")));
            return v;
        }
        private static boolean sameDay(long at){var d=Calendar.getInstance();d.setTimeInMillis(at);var t=Calendar.getInstance();return d.get(Calendar.YEAR)==t.get(Calendar.YEAR)&&d.get(Calendar.DAY_OF_YEAR)==t.get(Calendar.DAY_OF_YEAR);}
        public RemoteViews getLoadingView(){return null;}
        public int getViewTypeCount(){return 1;}
        public long getItemId(int position){return position<rows.size()?rows.get(position).reminder().optString("id").hashCode():position;}
        public boolean hasStableIds(){return true;}
    }
}

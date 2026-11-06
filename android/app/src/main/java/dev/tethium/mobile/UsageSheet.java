package dev.tethium.mobile;

import android.app.*;
import android.view.*;
import android.widget.*;
import android.os.*;
import org.json.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class UsageSheet {
    private final Activity activity;private final Ui ui;private final Connection connection;private final JSONObject state;
    private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private final Spinner periods,projects,sessions;private final TextView summary;private final LinearLayout rows;private Dialog dialog;
    private final ArrayList<String> periodIds=new ArrayList<>(),projectIds=new ArrayList<>(),sessionIds=new ArrayList<>();private boolean filling=true,loading;private long generation;
    static void show(Activity a,Ui ui,Connection c,JSONObject state){new UsageSheet(a,ui,c,state);}
    private UsageSheet(Activity a,Ui u,Connection c,JSONObject snapshot){activity=a;ui=u;connection=c;state=snapshot;
        var content=ui.col();ui.pad(content,16,12);periods=new Spinner(a);projects=new Spinner(a);sessions=new Spinner(a);for(var spinner:new Spinner[]{periods,projects,sessions}){spinner.setMinimumHeight(ui.dp(48));content.addView(spinner,new LinearLayout.LayoutParams(-1,ui.dp(48)));}
        summary=ui.label("Получаем статистику…",14,Ui.TEXT);ui.gap(content,12);content.addView(summary);rows=ui.col();var scroll=new ScrollView(a);scroll.addView(rows);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));content.addView(ui.label("Доля учтённых токенов Tethium; процент квоты аккаунта показан отдельно.",12,Ui.MUTED));dialog=ui.sheet("Токены и CPU",content);dialog.setOnDismissListener(d->{generation++;io.shutdownNow();});
        var names=new ArrayList<String>();projectIds.add("");names.add("Все проекты");var ps=state.optJSONArray("projects");if(ps!=null)for(int i=0;i<ps.length();i++){var p=ps.optJSONObject(i);projectIds.add(p.optString("id"));names.add(p.optString("title"));}adapter(projects,names);fillSessions();
        periods.setOnItemSelectedListener(listener(()->load()));projects.setOnItemSelectedListener(listener(()->{filling=true;fillSessions();filling=false;load();}));sessions.setOnItemSelectedListener(listener(()->load()));filling=false;load();
    }
    private AdapterView.OnItemSelectedListener listener(Runnable action){return new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!filling)action.run();}public void onNothingSelected(AdapterView<?> p){}};}
    private void adapter(Spinner spinner,List<String> values){spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values));}
    private void fillSessions(){sessionIds.clear();sessionIds.add("");var names=new ArrayList<String>();names.add("Все сессии");String pid=projectIds.get(Math.max(0,projects.getSelectedItemPosition()));var ss=state.optJSONArray("sessions");if(ss!=null)for(int i=0;i<ss.length();i++){var s=ss.optJSONObject(i);if(pid.isEmpty()||pid.equals(s.optString("projectId"))){sessionIds.add(s.optString("id"));names.add(s.optString("title"));}}adapter(sessions,names);}
    private String choice(List<String> ids,Spinner spinner){int n=spinner.getSelectedItemPosition();return n<0||n>=ids.size()?"":ids.get(n);}
    private void load(){
        if(!dialog.isShowing()||io.isShutdown()||loading)return;loading=true;setLoading(true);long version=++generation;
        String period=choice(periodIds,periods),pid=choice(projectIds,projects),sid=choice(sessionIds,sessions);
        io.execute(()->{try{var data=connection.request("/usage?period="+enc(period)+"&project="+enc(pid)+"&session="+enc(sid),null);main.post(()->{if(version!=generation||!dialog.isShowing())return;loading=false;setLoading(false);render(data,pid,sid);});}catch(Exception e){main.post(()->{if(version!=generation||!dialog.isShowing())return;loading=false;setLoading(false);summary.setText("Не удалось получить статистику: "+e.getMessage());});}});
    }
    private void setLoading(boolean busy){periods.setEnabled(!busy);projects.setEnabled(!busy);sessions.setEnabled(!busy);if(busy)summary.setText("Получаем статистику…");}
    private void render(JSONObject data,String pid,String sid){
        String chosen=data.optString("period");var list=data.optJSONArray("periods");var nextIds=new ArrayList<String>();var names=new ArrayList<String>();nextIds.add("all");names.add("Всё время");if(list!=null)for(int i=0;i<list.length();i++){var p=list.optJSONObject(i);nextIds.add(p.optString("id"));names.add(p.optString("label")+" · "+date(p.optLong("start"))+" — "+date(p.optLong("end")));}
        if(!nextIds.equals(periodIds)){filling=true;periodIds.clear();periodIds.addAll(nextIds);adapter(periods,names);periods.setSelection(Math.max(0,periodIds.indexOf(chosen)));filling=false;}
        summary.setText(String.format(Locale.forLanguageTag("ru"),"%,d токенов · %.2f%%\nВход %,d · кэш %,d · выход %,d",data.optLong("tokens"),data.optDouble("share"),data.optLong("input"),data.optLong("cached"),data.optLong("output")));rows.removeAllViews();boolean projectRows=pid.isEmpty()&&sid.isEmpty();var counts=data.optJSONObject(projectRows?"projects":"sessions");var all=state.optJSONArray(projectRows?"projects":"sessions");long total=data.optLong("total");if(all!=null&&counts!=null)for(int i=0;i<all.length();i++){var item=all.optJSONObject(i);String id=item.optString("id");if(!counts.has(id)||!pid.isEmpty()&&!projectRows&&!pid.equals(item.optString("projectId"))||!sid.isEmpty()&&!sid.equals(id))continue;long n=counts.optLong(id);rows.addView(ui.label(item.optString("title"),15,Ui.TEXT));rows.addView(ui.label(String.format(Locale.forLanguageTag("ru"),"%,d · %.2f%%",n,total==0?0:100.0*n/total),13,Ui.ACCENT));ui.gap(rows,16);}
    }
    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String date(long at){return new java.text.SimpleDateFormat("dd.MM HH:mm",Locale.ROOT).format(new Date(at));}
}

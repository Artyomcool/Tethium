package dev.tethium.mobile;

import android.app.*;
import android.view.View;
import android.widget.*;
import org.json.*;

/** The reminders inside the app: a banner while one is due, its screen with the five answers, and the list of all active ones. */
final class ReminderCard implements AutoCloseable {
    private final Activity activity;private final Ui ui;private final Button banner;
    private String server="";
    private Dialog screen,listDialog;private LinearLayout screenContent,listContent;private String screenId="";
    ReminderCard(Activity activity,Ui ui){
        this.activity=activity;this.ui=ui;
        banner=ui.button("Напоминание",this::openFirst);banner.setTextColor(Ui.AMBER);banner.setSingleLine();banner.setEllipsize(android.text.TextUtils.TruncateAt.END);banner.setVisibility(View.GONE);
        Reminders.changed=()->activity.runOnUiThread(this::render);
        render();
    }
    View banner(){return banner;}
    /** The laptop's list from the state; null when this state carries none (an older core). */
    void update(JSONArray reminders){
        if(reminders!=null){String text=reminders.toString();if(!text.equals(server)){server=text;Reminders.sync(activity,reminders);}}
        render();
    }
    private void render(){
        if(activity.isDestroyed())return;
        var all=Reminders.list(activity);JSONObject first=null;int firing=0;
        for(int i=0;i<all.length();i++){var r=all.optJSONObject(i);if(Reminders.firing(r)){firing++;if(first==null)first=r;}}
        banner.setVisibility(firing>0?View.VISIBLE:View.GONE);
        if(first!=null)banner.setText("Напоминание: "+first.optString("text")+(firing>1?" · и ещё "+(firing-1):"")+" · ответить");
        if(screen!=null)renderScreen();
        if(listDialog!=null)renderList();
    }
    private void openFirst(){var all=Reminders.list(activity);for(int i=0;i<all.length();i++){var r=all.optJSONObject(i);if(Reminders.firing(r)){open(r.optString("id"));return;}}}
    /** The screen a tap on the notification opens. */
    void open(String id){
        if(Reminders.find(activity,id)==null){Toast.makeText(activity,"На это напоминание уже ответили",Toast.LENGTH_SHORT).show();return;}
        screenId=id;
        if(screen==null){screenContent=ui.col();var scroll=new ScrollView(activity);scroll.addView(screenContent);var holder=ui.col();holder.addView(scroll,new LinearLayout.LayoutParams(-1,-1));screen=ui.sheet("Напоминание",holder);ui.compact(screen,520);screen.setOnDismissListener(d->{screen=null;screenContent=null;});}
        renderScreen();
    }
    private void renderScreen(){
        var r=Reminders.find(activity,screenId);
        if(r==null){if(screen!=null)screen.dismiss();return;}
        screenContent.removeAllViews();ui.pad(screenContent,20,12);
        var text=ui.title(r.optString("text"));text.setTextSize(22);text.setTextIsSelectable(true);screenContent.addView(text);ui.gap(screenContent,10);
        String session=r.optString("sessionTitle");int snoozes=r.optInt("snoozes");
        screenContent.addView(ui.label((Reminders.firing(r)?"Пора · ":"Сработает ")+Reminders.when(r.optLong("dueAt"))+(session.isEmpty()?"":" · из «"+session+"»")+(snoozes>0?" · откладывалось "+snoozes:""),14,Ui.MUTED));
        ui.gap(screenContent,20);screenContent.addView(ui.label("Отложить на",13,Ui.MUTED));ui.gap(screenContent,8);
        for(int row=0;row<2;row++){
            var line=ui.row();
            for(int k=0;k<2;k++){int i=row*2+k;int minutes=Reminders.SNOOZES[i];var b=ui.button(Reminders.SNOOZE_LABELS[i],()->answer(r.optString("id"),"snooze",minutes));var lp=new LinearLayout.LayoutParams(0,ui.dp(52),1);lp.setMargins(k==0?0:ui.dp(4),0,k==0?ui.dp(4):0,0);line.addView(b,lp);}
            screenContent.addView(line);ui.gap(screenContent,8);
        }
        ui.gap(screenContent,8);
        screenContent.addView(ui.primary("Готово",()->answer(r.optString("id"),"done",0)),new LinearLayout.LayoutParams(-1,ui.dp(56)));
    }
    private void answer(String id,String action,int minutes){
        Reminders.answer(activity,id,action,minutes);
        Toast.makeText(activity,action.equals("snooze")?"Отложено":action.equals("done")?"Готово":"Удалено",Toast.LENGTH_SHORT).show();
        if(screen!=null&&id.equals(screenId))screen.dismiss();
    }
    /** Every active reminder: done or delete. New ones are written in a chat. */
    void showList(){
        if(listDialog!=null){renderList();return;}
        listContent=ui.col();var scroll=new ScrollView(activity);scroll.addView(listContent);var holder=ui.col();holder.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        listDialog=ui.sheet("Напоминания",holder);ui.compact(listDialog,640);listDialog.setOnDismissListener(d->{listDialog=null;listContent=null;});renderList();
        Reminders.deliver(activity);
    }
    private void renderList(){
        listContent.removeAllViews();ui.pad(listContent,16,8);var all=Reminders.list(activity);
        if(all.length()==0)listContent.addView(ui.label("Активных напоминаний нет. Напишите в любой сессии, например: «напомни через 2 часа позвонить маме».",15,Ui.MUTED));
        for(int i=0;i<all.length();i++){
            var r=all.optJSONObject(i);String id=r.optString("id");
            var card=ui.col();card.setBackground(ui.shape(Ui.PANEL,16));ui.pad(card,14,12);
            var text=ui.label(r.optString("text"),16,Ui.TEXT);card.addView(text);ui.gap(card,6);
            card.addView(ui.label((Reminders.firing(r)?"Ждёт ответа · ":"")+Reminders.when(r.optLong("dueAt"))+(r.optInt("snoozes")>0?" · откладывалось "+r.optInt("snoozes"):""),13,Reminders.firing(r)?Ui.AMBER:Ui.MUTED));
            ui.gap(card,8);var actions=ui.row();
            if(Reminders.firing(r))actions.addView(ui.button("Ответить",()->open(id)),new LinearLayout.LayoutParams(0,ui.dp(48),1));
            actions.addView(ui.button("Удалить",()->new AlertDialog.Builder(activity).setMessage("Удалить напоминание «"+r.optString("text")+"»?").setPositiveButton("Удалить",(d,w)->answer(id,"remove",0)).setNegativeButton("Отмена",null).show()),new LinearLayout.LayoutParams(0,ui.dp(48),1));
            actions.addView(ui.primary("Готово",()->answer(id,"done",0)),new LinearLayout.LayoutParams(0,ui.dp(48),1));
            card.addView(actions);
            var lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,ui.dp(10));listContent.addView(card,lp);
        }
    }
    @Override public void close(){if(Reminders.changed!=null)Reminders.changed=null;if(screen!=null)screen.dismiss();if(listDialog!=null)listDialog.dismiss();}
}

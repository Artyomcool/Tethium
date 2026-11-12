package dev.tethium.mobile;

import android.app.*;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.concurrent.Executor;

/** Native presentation of the actual secure-desktop control tree; no decisions or generated labels. */
final class UacSheet implements AutoCloseable {
    private final Activity activity;
    private final Ui ui;
    private final Connection connection;
    private final Executor io;
    private final Button banner;
    private JSONObject state=new JSONObject();
    private Dialog dialog;
    private LinearLayout content;
    private String shown="";
    private boolean online,busy;
    UacSheet(Activity activity,Ui ui,Connection connection,Executor io){
        this.activity=activity;this.ui=ui;this.connection=connection;this.io=io;
        banner=ui.button("Запрос прав администратора · открыть",this::show);
        banner.setTextColor(Ui.AMBER);banner.setVisibility(View.GONE);
    }
    View banner(){return banner;}
    void online(boolean value){online=value;render();}
    void update(JSONObject value,boolean connected){state=value;online=connected;render();}
    void show(){
        if(dialog!=null){render();return;}
        content=ui.col();var scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.addView(content);var container=ui.col();container.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        dialog=ui.sheet("Права администратора",container);ui.compact(dialog,560);dialog.setOnDismissListener(d->{dialog=null;content=null;shown="";});render();
        refresh();
    }
    private void refresh(){
        if(connection==null)return;
        ui.async("uac-state","Проверяем запрос…",io,()->connection.request("/uac",null),next->{if(!activity.isDestroyed()){state=next;online=true;render();}});
    }
    private void render(){
        boolean pending=state.optBoolean("pending");
        banner.setVisibility(pending?View.VISIBLE:View.GONE);banner.setEnabled(online);
        banner.setText(online?"Запрос прав администратора · открыть":"Запрос UAC · ждём связь с ноутбуком");
        if(content==null)return;
        String signature=state.toString()+online+busy;if(signature.equals(shown))return;shown=signature;
        content.removeAllViews();ui.pad(content,20,16);
        if(!online){content.addView(ui.label("Нет связи с ноутбуком. Дождитесь подключения, чтобы ответить на текущий запрос.",16,Ui.AMBER));return;}
        if(!state.optBoolean("available")){
            content.addView(ui.label(state.optString("reason").equals("not-installed")?"Помощник UAC ещё не установлен или не запущен на ноутбуке.":"Помощник UAC переподключается.",16,Ui.MUTED));
            ui.gap(content,16);content.addView(ui.button("Проверить снова",this::refresh));return;
        }
        if(!pending){content.addView(ui.label("Сейчас ноутбук не запрашивает права администратора.",16,Ui.MUTED));return;}
        String title=state.optString("title");if(!title.isBlank()){content.addView(ui.title(title));ui.gap(content,16);}
        var texts=state.optJSONArray("texts");
        if(texts!=null)for(int i=0;i<texts.length();i++){String text=texts.optString(i);if(text.isBlank()||text.equals(title))continue;var label=ui.label(text,16,Ui.TEXT);label.setTextIsSelectable(true);content.addView(label);ui.gap(content,12);}
        if(state.optBoolean("credentials")){content.addView(ui.label("Windows запрашивает учётные данные. Ответьте на ноутбуке.",15,Ui.AMBER));return;}
        String request=state.optString("id");var actions=state.optJSONArray("actions");
        if(actions==null||actions.length()==0){content.addView(ui.label("Кнопки этого окна недоступны. Ответьте на ноутбуке.",15,Ui.AMBER));return;}
        ui.gap(content,8);
        for(int i=0;i<actions.length();i++){
            var action=actions.optJSONObject(i);if(action==null)continue;String id=action.optString("id"),label=action.optString("label");
            var button=ui.button(label,()->answer(request,id));button.setEnabled(!busy);content.addView(button,new LinearLayout.LayoutParams(-1,ui.dp(52)));ui.gap(content,8);
        }
    }
    private void answer(String request,String action){
        if(busy||!online||connection==null||!request.equals(state.optString("id")))return;
        busy=true;render();
        // A tap is sent once. On a lost reply, the prompt is refreshed; the answer is never queued or replayed.
        ui.async("uac-answer","Передаём ответ…",io,()->connection.request("/uac/answer",Connection.json("id",request,"action",action)),result->refresh(),()->{busy=false;shown="";render();});
    }
    @Override public void close(){if(dialog!=null)dialog.dismiss();}
}

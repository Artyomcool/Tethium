package dev.tethium.mobile;

import android.app.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** The tasks put off for better times: what waits for what, adding a task and the general rules of starting them. */
final class BacklogSheet {
    private static final String[] PRIORITY_IDS={"high","normal","low"},PRIORITY_NAMES={"Высокий","Обычный","Низкий"};
    private final Activity activity;private final Ui ui;private final Connection connection;private final JSONObject state;private final String project,current;
    private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private final LinearLayout rows;private final TextView summary;private final Dialog dialog;private JSONObject settings=new JSONObject();
    static void show(Activity a,Ui ui,Connection c,JSONObject state,String project,String current){new BacklogSheet(a,ui,c,state,project,current);}
    private BacklogSheet(Activity a,Ui u,Connection c,JSONObject snapshot,String projectId,String currentSession){
        activity=a;ui=u;connection=c;state=snapshot;project=projectId==null?"":projectId;current=currentSession==null?"":currentSession;
        var content=ui.col();ui.pad(content,16,8);
        summary=ui.label("Загружаем…",13,Ui.MUTED);content.addView(summary);ui.gap(content,8);
        rows=ui.col();var scroll=new ScrollView(a);scroll.addView(rows);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        var actions=ui.row();
        actions.addView(ui.button("Настройки",this::editSettings),new LinearLayout.LayoutParams(0,ui.dp(48),1));
        var add=ui.primary("Отложить",this::add);var addParams=new LinearLayout.LayoutParams(0,ui.dp(48),1);addParams.leftMargin=ui.dp(8);actions.addView(add,addParams);
        content.addView(actions);
        dialog=ui.sheet(project.isEmpty()?"Отложенные задачи":"Отложено · "+projectTitle(project),content);dialog.setOnDismissListener(d->io.shutdownNow());
        load();
    }
    private String projectTitle(String id){var list=state.optJSONArray("projects");if(list!=null)for(int i=0;i<list.length();i++){var p=list.optJSONObject(i);if(id.equals(p.optString("id")))return p.optString("title");}return "";}
    private String sessionProject(String id){var list=state.optJSONArray("sessions");if(list!=null)for(int i=0;i<list.length();i++){var s=list.optJSONObject(i);if(id.equals(s.optString("id")))return s.optString("projectId");}return "";}
    private void load(){
        if(io.isShutdown())return;
        io.execute(()->{try{var data=connection.request("/backlog"+(project.isEmpty()?"":"?project="+project),null);main.post(()->{if(dialog.isShowing())render(data);});}catch(Exception e){main.post(()->{if(dialog.isShowing())summary.setText("Не удалось получить список: "+e.getMessage());});}});
    }
    private static String priorityName(String id){for(int i=0;i<PRIORITY_IDS.length;i++)if(PRIORITY_IDS[i].equals(id))return PRIORITY_NAMES[i];return id;}
    private void render(JSONObject data){
        var s=data.optJSONObject("settings");settings=s==null?new JSONObject():s;
        summary.setText(String.format(Locale.ROOT,"Идёт %d из %d · по умолчанию квота от %d%%, CPU от %d%%%s",data.optInt("running"),settings.optInt("maxRunning"),settings.optInt("minQuota"),settings.optInt("minFreeCpu"),settings.optBoolean("paused")?" · автозапуск на паузе":""));
        rows.removeAllViews();var tasks=data.optJSONArray("tasks");
        if(tasks==null||tasks.length()==0){rows.addView(ui.label("Отложенных задач нет",15,Ui.MUTED));return;}
        for(int i=0;i<tasks.length();i++){
            var task=tasks.optJSONObject(i);var row=ui.col();ui.pad(row,12,10);row.setBackground(ui.ripple(Ui.PANEL,12));row.setClickable(true);row.setOnClickListener(v->actions(task));
            row.addView(ui.label(task.optString("title"),15,Ui.TEXT));ui.gap(row,4);
            String where=(project.isEmpty()?projectTitle(task.optString("projectId"))+" · ":"")+priorityName(task.optString("priority"))+" · "+task.optString("sessionName");
            row.addView(ui.label(where,12,Ui.MUTED));ui.gap(row,2);
            row.addView(ui.label(task.optString("executorLabel")+(task.optBoolean("executorSwitched")?" · замена по квоте":""),12,Ui.MUTED));
            row.addView(ui.label(task.optString("why"),12,task.optString("state").equals("running")?Ui.ACCENT:Ui.AMBER));
            var params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=ui.dp(6);rows.addView(row,params);
        }
    }
    private void post(String key,String label,String path,JSONObject body){ui.async(key,label,io,()->connection.request(path,body),result->load());}
    private void actions(JSONObject task){
        String id=task.optString("id");boolean running=task.optString("state").equals("running");
        var names=new ArrayList<String>();var work=new ArrayList<Runnable>();
        if(!running){
            names.add("Исполнитель…");work.add(()->editExecutor(task));
            names.add("Запустить сейчас");work.add(()->post("backlog-start:"+id,"Запускаем…","/backlog/"+id+"/start",new JSONObject()));
            for(int i=0;i<PRIORITY_IDS.length;i++){String priority=PRIORITY_IDS[i];if(priority.equals(task.optString("priority")))continue;names.add("Приоритет: "+PRIORITY_NAMES[i].toLowerCase(Locale.ROOT));work.add(()->{try{post("backlog-update:"+id,"Сохраняем…","/backlog/"+id+"/update",new JSONObject(task.toString()).put("priority",priority));}catch(JSONException e){throw new IllegalStateException(e);}});}
        }
        names.add("Удалить");work.add(()->new AlertDialog.Builder(activity).setMessage("Удалить задачу «"+task.optString("title")+"»?").setPositiveButton("Удалить",(d,w)->post("backlog-remove:"+id,"Удаляем…","/backlog/"+id+"/remove",new JSONObject())).setNegativeButton("Отмена",null).show());
        var content=ui.col();ui.pad(content,16,8);var text=ui.label(task.optString("prompt"),14,Ui.TEXT);text.setTextIsSelectable(true);var scroll=new ScrollView(activity);scroll.addView(text);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));ui.gap(content,8);
        var sheet=ui.sheet(task.optString("title"),content);
        for(int i=0;i<names.size();i++){var action=work.get(i);var params=new LinearLayout.LayoutParams(-1,ui.dp(48));params.topMargin=ui.dp(6);content.addView(ui.button(names.get(i),()->{sheet.dismiss();action.run();}),params);}
    }
    private void spinner(Spinner spinner,List<String> values){spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values));}
    private EditText number(String hint){var e=ui.edit(hint);e.setInputType(InputType.TYPE_CLASS_NUMBER);return e;}
    private static Integer percent(EditText field){String text=field.getText().toString().strip();if(text.isEmpty())return null;try{return Math.max(0,Math.min(100,Integer.parseInt(text)));}catch(NumberFormatException e){return null;}}
    private final class ExecutorFields {
        final CheckBox automatic=new CheckBox(activity);
        final Spinner model=new Spinner(activity);
        final List<String> ids=new ArrayList<>(List.of("","CODEX|gpt-6-astra","CLAUDE|fable","CODEX|gpt-6.1-sol","CLAUDE|opus"));
        ExecutorFields(LinearLayout form,JSONObject task){
            var names=new ArrayList<>(List.of("Из выбранной сессии","Закрепить Codex · Astra","Закрепить Claude · Fable","Закрепить Codex · Sol","Закрепить Claude · Opus"));
            String provider=task==null||task.isNull("pinnedProvider")?"":task.optString("pinnedProvider");
            String fixed=provider.isEmpty()?"":provider+"|"+(task.isNull("pinnedModel")?"":task.optString("pinnedModel"));
            if(!ids.contains(fixed)){ids.add(fixed);names.add("Закрепить "+fixed.replace('|',' '));}
            form.addView(ui.label("Исполнитель задачи",13,Ui.MUTED));spinner(model,names);model.setSelection(ids.indexOf(fixed));form.addView(model,new LinearLayout.LayoutParams(-1,ui.dp(48)));
            automatic.setText("Автозамена по квоте: Astra ↔ Fable, Sol ↔ Opus");automatic.setTextColor(Ui.TEXT);automatic.setChecked(task==null||task.optBoolean("autoSwitch",true));form.addView(automatic);
            automatic.setEnabled(fixed.isEmpty());if(!fixed.isEmpty())automatic.setChecked(false);
            model.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){automatic.setEnabled(ids.get(position).isEmpty());if(!ids.get(position).isEmpty())automatic.setChecked(false);}public void onNothingSelected(AdapterView<?> p){}});
        }
        void apply(JSONObject body)throws JSONException{
            String fixed=ids.get(model.getSelectedItemPosition());String[] parts=fixed.split("\\|",-1);
            body.put("autoSwitch",automatic.isChecked()).put("pinnedProvider",fixed.isEmpty()?JSONObject.NULL:parts[0]).put("pinnedModel",fixed.isEmpty()?JSONObject.NULL:parts[1]);
        }
    }
    private void editExecutor(JSONObject task){
        var form=ui.col();ui.pad(form,16,8);var fields=new ExecutorFields(form,task);var sheet=ui.sheet("Исполнитель · "+task.optString("title"),form);
        form.addView(ui.primary("Сохранить",()->{try{var body=new JSONObject(task.toString());fields.apply(body);ui.async("backlog-executor:"+task.optString("id"),"Сохраняем…",io,()->connection.request("/backlog/"+task.optString("id")+"/update",body),result->{sheet.dismiss();load();});}catch(JSONException e){throw new IllegalStateException(e);}}));
    }
    private void add(){
        String target=!project.isEmpty()?project:sessionProject(current);
        if(target.isEmpty()){Toast.makeText(activity,"Откройте сессию проекта",Toast.LENGTH_SHORT).show();return;}
        boolean inCurrent=!current.isEmpty()&&target.equals(sessionProject(current));
        var form=ui.col();ui.pad(form,16,8);var inner=ui.col();var scroll=new ScrollView(activity);scroll.addView(inner);form.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        var title=ui.edit("Название");title.setSingleLine();inner.addView(title);ui.gap(inner,8);
        var prompt=ui.edit("Что сделать: агент не помнит разговор");prompt.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);prompt.setMinLines(4);prompt.setGravity(Gravity.TOP);inner.addView(prompt);ui.gap(inner,8);
        var priority=new Spinner(activity);spinner(priority,List.of(PRIORITY_NAMES));priority.setSelection(1);inner.addView(priority,new LinearLayout.LayoutParams(-1,ui.dp(48)));
        var kinds=new ArrayList<String>();var kindIds=new ArrayList<String>();if(inCurrent){kinds.add("В текущей сессии");kindIds.add("session");}kinds.add("В общей сессии по имени");kindIds.add("named");kinds.add("В новой сессии");kindIds.add("new");
        var kind=new Spinner(activity);spinner(kind,kinds);inner.addView(kind,new LinearLayout.LayoutParams(-1,ui.dp(48)));
        var name=ui.edit("Название общей сессии");name.setSingleLine();name.setText("Мелкие задачи");inner.addView(name);
        kind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){name.setVisibility(kindIds.get(position).equals("named")?View.VISIBLE:View.GONE);}public void onNothingSelected(AdapterView<?> p){}});
        ui.gap(inner,8);
        var quota=number("Квота от, %: по умолчанию "+settings.optInt("minQuota"));inner.addView(quota);ui.gap(inner,8);
        var cpu=number("Свободно CPU от, %: по умолчанию "+settings.optInt("minFreeCpu"));inner.addView(cpu);ui.gap(inner,8);
        var execution=new ExecutorFields(inner,null);
        var sheet=ui.sheet("Отложить задачу",form);
        form.addView(ui.primary("Отложить",()->{
            if(title.getText().toString().isBlank()){title.requestFocus();return;}
            try{
                String chosen=kindIds.get(kind.getSelectedItemPosition());
                var body=new JSONObject().put("projectId",target).put("title",title.getText().toString()).put("prompt",prompt.getText().toString()).put("priority",PRIORITY_IDS[priority.getSelectedItemPosition()]).put("target",chosen);
                if(chosen.equals("session"))body.put("sessionId",current);
                if(chosen.equals("named"))body.put("sessionTitle",name.getText().toString());
                if(percent(quota)!=null)body.put("minQuota",percent(quota));
                if(percent(cpu)!=null)body.put("minFreeCpu",percent(cpu));
                if(!current.isEmpty())body.put("sourceId",current);
                execution.apply(body);
                ui.async("backlog-add","Сохраняем…",io,()->connection.request("/backlog",body),result->{sheet.dismiss();load();});
            }catch(JSONException e){throw new IllegalStateException(e);}
        }));
    }
    private void editSettings(){
        var form=ui.col();ui.pad(form,16,8);
        var max=number("Одновременно задач");max.setText(String.valueOf(settings.optInt("maxRunning",3)));form.addView(ui.label("Одновременно задач",13,Ui.MUTED));form.addView(max);ui.gap(form,8);
        var quota=number("%");quota.setText(String.valueOf(settings.optInt("minQuota",80)));form.addView(ui.label("По умолчанию квота от, %",13,Ui.MUTED));form.addView(quota);ui.gap(form,8);
        var cpu=number("%");cpu.setText(String.valueOf(settings.optInt("minFreeCpu",40)));form.addView(ui.label("По умолчанию свободно CPU от, %",13,Ui.MUTED));form.addView(cpu);ui.gap(form,8);
        var paused=new CheckBox(activity);paused.setText("Пауза автозапуска");paused.setTextColor(Ui.TEXT);paused.setChecked(settings.optBoolean("paused"));form.addView(paused);ui.gap(form,8);
        var sheet=ui.sheet("Общие настройки",form);
        form.addView(ui.primary("Сохранить",()->{
            try{
                var body=new JSONObject().put("paused",paused.isChecked());
                try{body.put("maxRunning",Integer.parseInt(max.getText().toString().strip()));}catch(NumberFormatException ignored){}
                if(percent(quota)!=null)body.put("minQuota",percent(quota));
                if(percent(cpu)!=null)body.put("minFreeCpu",percent(cpu));
                ui.async("backlog-settings","Сохраняем…",io,()->connection.request("/backlog-settings",body),result->{sheet.dismiss();load();});
            }catch(JSONException e){throw new IllegalStateException(e);}
        }));
    }
}

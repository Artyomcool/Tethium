package dev.tethium.mobile;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/**
 * What an agent is waiting for, answered in place above the input: questions with their options, an approval, or a tool's form.
 * The same answers as the desktop card, so a session can go on from the phone alone.
 */
final class QuestionCard {
    interface Send{void send(JSONObject answer,Runnable failed);}
    private static final class Entry{
        final String id;final boolean multi;final List<CompoundButton> options=new ArrayList<>();final List<String> labels=new ArrayList<>();EditText other;
        Entry(String id,boolean multi){this.id=id;this.multi=multi;}
        boolean answered(){if(!other.getText().toString().isBlank())return true;for(var option:options)if(option.isChecked())return true;return false;}
    }
    private final Activity activity;private final Ui ui;private final Send send;private final LinearLayout card;
    private final List<View> controls=new ArrayList<>();
    static View of(Activity activity,Ui ui,JSONObject request,Send send){return new QuestionCard(activity,ui,request,send).card;}
    private QuestionCard(Activity activity,Ui ui,JSONObject request,Send send){
        this.activity=activity;this.ui=ui;this.send=send;card=ui.col();ui.pad(card,16,12);card.setBackground(ui.shape(Ui.PANEL,16));
        var margins=new LinearLayout.LayoutParams(-1,-2);int gap=ui.dp(8);margins.setMargins(gap,gap/2,gap,gap/2);card.setLayoutParams(margins);
        String method=request.optString("method");var params=request.optJSONObject("params");if(params==null)params=new JSONObject();
        if(method.equals("item/tool/requestUserInput"))questions(params);
        else if(method.endsWith("requestApproval"))approval(method,params);
        else form(params);
    }
    private void head(String text){card.addView(ui.label(text,12,Ui.AMBER));ui.gap(card,8);}
    private TextView body(String text){var view=ui.label(text,15,Ui.TEXT);view.setTextIsSelectable(true);card.addView(view);return view;}
    private <T extends View> T control(T view){controls.add(view);return view;}
    private void enable(boolean on){for(var view:controls){view.setEnabled(on);view.setAlpha(on?1:.55f);}}
    private void submit(JSONObject answer){enable(false);send.send(answer,()->enable(true));}
    private void buttons(List<Button> buttons){
        ui.gap(card,10);var row=ui.row();
        for(int i=0;i<buttons.size();i++){var params=new LinearLayout.LayoutParams(0,ui.dp(48),1);if(i>0)params.leftMargin=ui.dp(6);row.addView(control(buttons.get(i)),params);}
        card.addView(row);
    }
    private void questions(JSONObject params){
        var list=params.optJSONArray("questions");int count=list==null?0:list.length();var entries=new ArrayList<Entry>();
        head("Вопрос агента");
        for(int i=0;i<count;i++){
            var question=list.optJSONObject(i);if(question==null)continue;
            String header=question.optString("header").strip(),text=question.optString("question");
            body((count>1?(i+1)+"/"+count+" · ":"")+(header.isEmpty()?"":header+" · ")+text);ui.gap(card,6);
            var entry=new Entry(question.optString("id"),question.optBoolean("multiSelect"));entries.add(entry);
            var options=question.optJSONArray("options");
            for(int j=0;options!=null&&j<options.length();j++){
                var option=options.optJSONObject(j);if(option==null)continue;
                String label=option.optString("label");
                CompoundButton button=control(entry.multi?new CheckBox(activity):new RadioButton(activity));
                button.setText(optionText(label,option.optString("description").strip()));button.setTextSize(15);button.setTextColor(Ui.TEXT);button.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));button.setMinHeight(ui.dp(48));
                button.setOnClickListener(v->{if(entry.multi||!button.isChecked())return;for(var other:entry.options)if(other!=button)other.setChecked(false);entry.other.setText("");});
                entry.options.add(button);entry.labels.add(label);card.addView(button);
            }
            entry.other=control(ui.edit(entry.options.isEmpty()?"Ваш ответ":"Другое: свой вариант"));
            entry.other.addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){}
                public void afterTextChanged(Editable s){if(!entry.multi&&!s.toString().isBlank())for(var option:entry.options)option.setChecked(false);}
            });
            ui.gap(card,4);card.addView(entry.other);ui.gap(card,12);
        }
        buttons(List.of(ui.primary(count==0?"Продолжить":"Ответить",()->{
            for(var entry:entries)if(!entry.answered()){Toast.makeText(activity,"Ответьте на все вопросы",Toast.LENGTH_SHORT).show();(entry.options.isEmpty()?entry.other:entry.options.get(0)).requestFocus();return;}
            var answers=new JSONObject();
            try{
                for(var entry:entries){
                    var values=new JSONArray();
                    for(int i=0;i<entry.options.size();i++)if(entry.options.get(i).isChecked())values.put(entry.labels.get(i));
                    String typed=entry.other.getText().toString().strip();if(!typed.isEmpty())values.put(typed);
                    answers.put(entry.id,new JSONObject().put("answers",values));
                }
                submit(new JSONObject().put("answers",answers));
            }catch(JSONException e){throw new IllegalStateException(e);}
        })));
    }
    private static CharSequence optionText(String label,String description){
        var text=new SpannableStringBuilder(label);if(description.isEmpty())return text;
        int start=text.length();text.append("\n").append(description);
        text.setSpan(new ForegroundColorSpan(Ui.MUTED),start,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);text.setSpan(new RelativeSizeSpan(.87f),start,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
    private void approval(String method,JSONObject params){
        head("Нужно разрешение");
        String details=params.optString("command",params.optString("reason",params.optString("message","Агент ждёт вашего ответа")));
        boolean permissions=method.equals("item/permissions/requestApproval");var granted=params.optJSONObject("permissions");
        if(permissions&&granted!=null)try{details+="\n"+granted.toString(2);}catch(JSONException ignored){}
        body(details);
        if(permissions)buttons(List.of(
            ui.primary("Разрешить",()->submit(permissionAnswer(granted))),
            ui.button("Отклонить",()->submit(permissionAnswer(new JSONObject())))));
        else buttons(List.of(
            ui.primary("Разрешить",()->submit(decision("accept"))),
            ui.button("Для сессии",()->submit(decision("acceptForSession"))),
            ui.button("Отклонить",()->submit(decision("decline")))));
    }
    private static JSONObject decision(String value){try{return new JSONObject().put("decision",value);}catch(JSONException e){throw new IllegalStateException(e);}}
    private static JSONObject permissionAnswer(JSONObject permissions){try{return new JSONObject().put("permissions",permissions==null?new JSONObject():permissions).put("scope","turn");}catch(JSONException e){throw new IllegalStateException(e);}}
    private void form(JSONObject params){
        head("Запрос инструмента");body(params.optString("message","Введите данные"));
        var fields=new LinkedHashMap<String,EditText>();var schema=params.optJSONObject("requestedSchema");var properties=schema==null?null:schema.optJSONObject("properties");
        if(properties!=null)for(var keys=properties.keys();keys.hasNext();){
            String key=keys.next();var property=properties.optJSONObject(key);
            var field=control(ui.edit(property==null?key:property.optString("title",key)));fields.put(key,field);ui.gap(card,6);card.addView(field);
        }
        if(fields.isEmpty()&&!params.optString("url").isEmpty()){ui.gap(card,6);body(params.optString("url"));}
        buttons(List.of(
            ui.primary("Отправить",()->{try{var content=new JSONObject();for(var field:fields.entrySet())content.put(field.getKey(),field.getValue().getText().toString());submit(new JSONObject().put("action","accept").put("content",content));}catch(JSONException e){throw new IllegalStateException(e);}}),
            ui.button("Отмена",()->{try{submit(new JSONObject().put("action","cancel"));}catch(JSONException e){throw new IllegalStateException(e);}})));
    }
}

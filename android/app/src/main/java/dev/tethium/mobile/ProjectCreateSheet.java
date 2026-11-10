package dev.tethium.mobile;

import android.app.*;
import android.text.*;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Project creation uses the laptop's existing project/GitHub API. */
final class ProjectCreateSheet {
    static void show(Activity activity,Ui ui,Connection connection,Executor io,Consumer<JSONObject> created){
        var content=ui.col();var fields=ui.col();ui.pad(fields,20,12);var scroll=new ScrollView(activity);scroll.addView(fields);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        fields.addView(ui.label("Название",13,Ui.MUTED));ui.gap(fields,6);var name=ui.edit("Название проекта");name.setSingleLine();fields.addView(name);ui.gap(fields,16);
        fields.addView(ui.label("Папка на ноутбуке",13,Ui.MUTED));ui.gap(fields,6);var path=ui.edit("C:/git/Название");path.setSingleLine();path.setText("C:/git/");fields.addView(path);ui.gap(fields,16);
        var first=new CheckBox(activity);first.setText("Создать начальную сессию");first.setTextColor(Ui.TEXT);first.setChecked(true);fields.addView(first);var session=ui.edit("Название сессии");session.setSingleLine();session.setText("Разработка");fields.addView(session);first.setOnCheckedChangeListener((b,checked)->session.setEnabled(checked));ui.gap(fields,16);
        var github=new CheckBox(activity);github.setText("Создать репозиторий GitHub");github.setTextColor(Ui.TEXT);fields.addView(github);var githubFields=ui.col();var repository=ui.edit("Имя репозитория или owner/name");repository.setSingleLine();githubFields.addView(repository);var publicRepo=new CheckBox(activity);publicRepo.setText("Публичный репозиторий");publicRepo.setTextColor(Ui.TEXT);githubFields.addView(publicRepo);githubFields.setVisibility(View.GONE);fields.addView(githubFields);github.setOnCheckedChangeListener((button,checked)->githubFields.setVisibility(checked?View.VISIBLE:View.GONE));
        var frequent=new CheckBox(activity);frequent.setText("Агентам часто коммитить и пушить");frequent.setTextColor(Ui.TEXT);fields.addView(frequent);
        String[] suggested={"C:/git/",""};name.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){String value=s.toString().trim();String next="C:/git/"+value;if(path.getText().toString().equals(suggested[0]))path.setText(next);if(repository.getText().toString().equals(suggested[1]))repository.setText(value);suggested[0]=next;suggested[1]=value;}public void afterTextChanged(Editable e){}});
        var dialog=ui.sheet("Новый проект",content);var footer=ui.col();ui.pad(footer,20,12);content.addView(footer);
        footer.addView(ui.primary("Создать проект",()->{
            if(name.getText().toString().trim().isEmpty()){name.setError("Укажите название");name.requestFocus();return;}
            String directory=path.getText().toString().trim();if(directory.isEmpty()||directory.equals("C:/git/")){path.setError("Укажите папку проекта");path.requestFocus();return;}
            var body=Connection.json("path",directory,"initialSession",first.isChecked()?(session.getText().toString().trim().isEmpty()?"Разработка":session.getText().toString().trim()):"","createRepository",github.isChecked(),"repository",repository.getText().toString().trim(),"public",publicRepo.isChecked());
            if(frequent.isChecked())try{body.put("frequentPush",true);}catch(JSONException e){throw new IllegalStateException(e);}
            ui.async("project-create","Создаём проект…",io,()->{var project=connection.post("/projects",body);var state=connection.request("/state",null);return Connection.json("project",project,"state",state);},result->{dialog.dismiss();created.accept(result);});
        }));
        name.requestFocus();
    }
}

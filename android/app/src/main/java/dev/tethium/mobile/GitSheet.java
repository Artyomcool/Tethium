package dev.tethium.mobile;

import android.app.*;
import android.graphics.Typeface;
import android.os.*;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** What the agents wrote in the project, read-only: uncommitted files and recent commits, each opening a coloured diff. */
final class GitSheet {
    private static final int SHOWN=200_000;
    private final Activity activity;private final Ui ui;private final Connection connection;private final String project;
    private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private final LinearLayout rows;private final TextView summary;private final Dialog dialog;

    static void show(Activity a,Ui ui,Connection c,String project,String title){if(c!=null&&!project.isEmpty())new GitSheet(a,ui,c,project,title);}
    private GitSheet(Activity a,Ui u,Connection c,String projectId,String title){
        activity=a;ui=u;connection=c;project=projectId;
        var content=ui.col();ui.pad(content,16,8);
        summary=ui.label("Читаем репозиторий…",13,Ui.MUTED);content.addView(summary);ui.gap(content,8);
        rows=ui.col();var scroll=new ScrollView(a);scroll.addView(rows);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog=ui.sheet("Git · "+title,content,"refresh","Обновить",d->load());dialog.setOnDismissListener(d->io.shutdownNow());
        load();
    }
    private void load(){
        if(io.isShutdown())return;summary.setText("Читаем репозиторий…");
        io.execute(()->{try{var data=connection.request("/git?project="+project+"&op=status",null);main.post(()->{if(dialog.isShowing())render(data);});}
            catch(Exception e){main.post(()->{if(dialog.isShowing())summary.setText("Не удалось прочитать git: "+e.getMessage());});}});
    }
    private void render(JSONObject data){
        rows.removeAllViews();
        if(!data.optBoolean("repository")){summary.setText("Папка проекта не является git-репозиторием");return;}
        var files=data.optJSONArray("files");var commits=data.optJSONArray("commits");int changed=files==null?0:files.length();
        var parts=new ArrayList<String>();parts.add("Ветка "+data.optString("branch","?"));if(!data.optString("upstream").isEmpty())parts.add(data.optString("upstream"));
        parts.add(changed==0?"без незакоммиченных изменений":data.optString("stat",changed+" файлов"));summary.setText(String.join(" · ",parts));
        heading("Изменения · "+changed);
        for(int i=0;i<changed;i++){var file=files.optJSONObject(i);String path=file.optString("path"),kind=file.optString("kind");int slash=path.lastIndexOf('/');
            var row=row();var mark=ui.label(switch(kind){case "untracked"->"новый";case "added"->"добавлен";case "deleted"->"удалён";case "renamed"->"переименован";default->"изменён";},12,
                switch(kind){case "untracked","added"->Ui.GREEN;case "deleted"->Ui.RED;case "renamed"->Ui.AMBER;default->Ui.MUTED;});
            row.addView(mark);ui.gap(row,2);var name=ui.label(slash<0?path:path.substring(slash+1),15,Ui.TEXT);name.setTypeface(Typeface.DEFAULT_BOLD);row.addView(name);
            if(slash>0){ui.gap(row,2);row.addView(ui.label(path.substring(0,slash),12,Ui.MUTED));}
            row.setOnClickListener(v->open(path,"/git?project="+project+"&op=diff&path="+URLEncoder.encode(path,StandardCharsets.UTF_8)));rows.addView(row);ui.gap(rows,6);}
        heading("Коммиты");var time=new SimpleDateFormat("dd.MM HH:mm",Locale.ROOT);
        for(int i=0;commits!=null&&i<commits.length();i++){var commit=commits.optJSONObject(i);
            var row=row();var subject=ui.label(commit.optString("subject"),15,Ui.TEXT);subject.setMaxLines(3);row.addView(subject);ui.gap(row,4);
            row.addView(ui.label(commit.optString("short")+" · "+commit.optString("author")+" · "+time.format(new Date(commit.optLong("time"))),12,Ui.MUTED));
            row.setOnClickListener(v->open(commit.optString("short")+" "+commit.optString("subject"),"/git?project="+project+"&op=show&commit="+commit.optString("hash")));rows.addView(row);ui.gap(rows,6);}
    }
    private void heading(String text){ui.gap(rows,8);var label=ui.label(text,13,Ui.ACCENT);label.setTypeface(Typeface.DEFAULT_BOLD);rows.addView(label);ui.gap(rows,8);}
    private LinearLayout row(){var row=ui.col();ui.pad(row,12,10);row.setBackground(ui.ripple(Ui.PANEL,12));row.setClickable(true);return row;}

    /** The diff on its own sheet: monospace, scrollable both ways, lines coloured like in the desktop window. */
    private void open(String title,String request){
        var content=ui.col();ui.pad(content,8,8);var status=ui.label("Загружаем…",13,Ui.MUTED);content.addView(status);
        var text=new TextView(activity);text.setTypeface(Typeface.MONOSPACE);text.setTextSize(12);text.setTextColor(Ui.TEXT);text.setTextIsSelectable(true);text.setHorizontallyScrolling(true);ui.pad(text,4,4);
        var horizontal=new HorizontalScrollView(activity);horizontal.addView(text);var vertical=new ScrollView(activity);vertical.addView(horizontal);
        content.addView(vertical,new LinearLayout.LayoutParams(-1,0,1));
        var sheet=ui.sheet(title,content);
        io.execute(()->{try{var data=connection.request(request,null);String diff=data.optString("text");var colored=color(diff);
                main.post(()->{if(!sheet.isShowing())return;status.setVisibility(View.GONE);text.setText(colored);});}
            catch(Exception e){main.post(()->{if(sheet.isShowing())status.setText("Не удалось: "+e.getMessage());});}});
    }
    private static final java.util.regex.Pattern HUNK=java.util.regex.Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");
    /** A band across the whole line, like GitHub: the text keeps its colour, the background tells added from removed. */
    private record Band(int color) implements android.text.style.LineBackgroundSpan {
        public void drawBackground(android.graphics.Canvas canvas,android.graphics.Paint paint,int left,int right,int top,int baseline,int bottom,CharSequence text,int start,int end,int line){
            int previous=paint.getColor();paint.setColor(color);canvas.drawRect(left,top,right,bottom,paint);paint.setColor(previous);
        }
    }
    /** Unified diff as GitHub shows it: old and new line numbers, full-width bands, one bar per file, no index/---/+++ lines. */
    private static CharSequence color(String diff){
        String shown=diff.length()>SHOWN?diff.substring(0,SHOWN)+"\n… показано "+SHOWN/1000+" КБ из "+diff.length()/1000:diff;
        var out=new SpannableStringBuilder();int oldLine=0,newLine=0;boolean inHunk=false;
        for(String raw:shown.replace("\t","    ").split("\n",-1)){
            String line;int band=0,text=0;boolean bold=false,numbered=false;
            if(raw.startsWith("diff --git ")){int at=raw.lastIndexOf(" b/");line=at>0?raw.substring(at+3):raw.substring(11);band=0xff2a2f3a;text=Ui.TEXT;bold=true;inHunk=false;}
            else if(!inHunk&&(raw.startsWith("index ")||raw.startsWith("--- ")||raw.startsWith("+++ ")||raw.startsWith("similarity index")||raw.startsWith("old mode")||raw.startsWith("new mode")))continue;
            else if(!inHunk&&(raw.startsWith("new file mode")||raw.startsWith("deleted file mode"))){line=raw.startsWith("new")?"новый файл":"файл удалён";text=Ui.MUTED;}
            else{var hunk=HUNK.matcher(raw);
                if(hunk.matches()){oldLine=Integer.parseInt(hunk.group(1));newLine=Integer.parseInt(hunk.group(2));inHunk=true;line=raw;band=0xff1e2a42;text=Ui.ACCENT;}
                else if(inHunk&&raw.startsWith("+")){line=String.format(Locale.ROOT,"%5s %5d + %s","",newLine++,raw.substring(1));band=0xff1f3a2c;numbered=true;}
                else if(inHunk&&raw.startsWith("-")){line=String.format(Locale.ROOT,"%5d %5s - %s",oldLine++,"",raw.substring(1));band=0xff41242a;numbered=true;}
                else if(inHunk&&raw.startsWith(" ")){line=String.format(Locale.ROOT,"%5d %5d   %s",oldLine++,newLine++,raw.substring(1));numbered=true;}
                else if(inHunk&&raw.startsWith("\\")){line=raw.substring(1).strip();text=Ui.MUTED;}
                else{inHunk=false;line=raw;}
            }
            int start=out.length();out.append(line).append('\n');int end=out.length();
            if(band!=0)out.setSpan(new Band(band),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if(text!=0)out.setSpan(new ForegroundColorSpan(text),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if(bold)out.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Line numbers in the gutter are dimmed so the code reads first.
            if(numbered)out.setSpan(new ForegroundColorSpan(Ui.MUTED),start,start+11,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }
}

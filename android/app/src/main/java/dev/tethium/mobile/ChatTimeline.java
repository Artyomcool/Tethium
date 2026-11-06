package dev.tethium.mobile;

import android.app.*;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import io.noties.markwon.*;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import org.json.*;
import java.util.*;
import java.util.function.*;

/** Stable message views: polling updates the streaming tail, never replaces the whole transcript. */
final class ChatTimeline extends FrameLayout {
    private final Ui ui;
    final ScrollView scroll;
    private final LinearLayout messages,live;
    private final TextView liveText;
    private final Button end;
    private final Markwon markdown;
    private final LinkedHashMap<Long,View> anchors=new LinkedHashMap<>();
    private final LinkedHashMap<Long,String> userTitles=new LinkedHashMap<>();
    private final HashMap<Long,String> rendered=new HashMap<>();
    private JSONArray history=new JSONArray();
    private int limit=100,start=-1;
    boolean more;
    static volatile boolean showThoughts=true;
    private static final String ACTION="\u2014\u2063 ";
    /** Reports of commands are shown muted and without the mark; they and thoughts are not part of what a message says when copied. */
    private void render(TextView view,String text){
        var shown=new android.text.SpannableStringBuilder(markdown.toMarkdown(text));
        for(int at=shown.toString().lastIndexOf(ACTION);at>=0;at=at==0?-1:shown.toString().lastIndexOf(ACTION,at-1)){
            int end=shown.toString().indexOf('\n',at);if(end<0)end=shown.length();
            shown.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED),at,end,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);shown.setSpan(new android.text.style.RelativeSizeSpan(.85f),at,end,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown.delete(at,at+ACTION.length());
        }
        markdown.setParsedMarkdown(view,shown);
    }
    static String withoutActions(String text){
        if(!text.contains(ACTION)&&!text.contains("```thought"))return text;
        return text.replaceAll("(?ms)^```thought\\R.*?(^```[ \\t]*(\\R|\\z)|\\z)","").replaceAll("(?m)^"+ACTION+".*(\\R|\\z)","").replaceAll("\n{3,}","\n\n").strip();
    }
    /** Thought blocks become quotes (or vanish when hidden). */
    static String narration(String text){
        if(!text.contains("```thought"))return text;
        var out=new StringBuilder();int at=0;
        while(true){int open=text.indexOf("```thought",at);if(open<0){out.append(text,at,text.length());break;}
            out.append(text,at,open);int bodyStart=text.indexOf('\n',open);if(bodyStart<0)break;int close=text.indexOf("\n```",bodyStart);String body=close<0?text.substring(bodyStart+1):text.substring(bodyStart+1,close);
            if(showThoughts){for(String line:body.split("\n"))out.append("> ").append(line.isEmpty()?"\u200b":line).append('\n');out.append('\n');}
            if(close<0)break;at=close+4;}
        return out.toString();
    }
    Runnable loadEarlier=()->{};
    private String lastLive="",session="";
    Consumer<String> currentRequest=s->{};
    Consumer<String> quote=s->{}, openFile=s->{};
    Runnable atEnd=()->{};
    ChatTimeline(Activity a,Ui ui){super(a);this.ui=ui;
        markdown=Markwon.builder(a).usePlugin(TablePlugin.create(a)).usePlugin(StrikethroughPlugin.create()).usePlugin(TaskListPlugin.create(a))
            .usePlugin(new AbstractMarkwonPlugin(){@Override public void configureTheme(MarkwonTheme.Builder b){b.linkColor(Ui.ACCENT).codeBackgroundColor(Ui.RAISED).codeBlockBackgroundColor(Ui.PANEL).blockQuoteColor(Ui.ACCENT).blockMargin(ui.dp(16)).headingBreakHeight(0);}
                @Override public void configureConfiguration(MarkwonConfiguration.Builder b){b.linkResolver((view,link)->{try{var uri=android.net.Uri.parse(link);if("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,uri));else openFile.accept(link);}catch(Exception ignored){}});}}).build();
        scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(64));scroll.setVerticalScrollBarEnabled(false);
        var all=ui.col();messages=ui.col();all.addView(messages);live=ui.col();var head=ui.label("CODEX · ПИШЕТ",11,Ui.ACCENT);head.setTypeface(Typeface.DEFAULT_BOLD);live.addView(head);ui.gap(live,12);liveText=body();live.addView(liveText);live.setVisibility(GONE);all.addView(live);scroll.addView(all);addView(scroll,new LayoutParams(-1,-1));
        end=ui.button("В конец ↓",this::toEnd);end.setElevation(ui.dp(6));var ep=new LayoutParams(-2,ui.dp(48),Gravity.BOTTOM|Gravity.RIGHT);ep.setMargins(0,0,ui.dp(16),ui.dp(8));addView(end,ep);end.setVisibility(GONE);
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{end.setVisibility(bottom()?GONE:VISIBLE);String current="Запросы в разговоре";for(var e:anchors.entrySet())if(e.getValue().getTop()<=y+ui.dp(24)&&userTitles.containsKey(e.getKey()))current=userTitles.get(e.getKey());currentRequest.accept(current);if(bottom())atEnd.run();});
    }
    private TextView body(){var t=ui.label("",16,Ui.TEXT);t.setTextIsSelectable(true);t.setLineSpacing(ui.dp(5),1);
        t.setCustomSelectionActionModeCallback(new ActionMode.Callback(){
            public boolean onCreateActionMode(ActionMode mode,Menu menu){menu.add(0,9127,0,"Цитировать").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);return true;}
            public boolean onPrepareActionMode(ActionMode mode,Menu menu){return false;}
            public boolean onActionItemClicked(ActionMode mode,MenuItem item){if(item.getItemId()!=9127)return false;int a=t.getSelectionStart(),b=t.getSelectionEnd();if(a>=0&&b>a)quote.accept(t.getText().subSequence(a,b).toString());mode.finish();return true;}
            public void onDestroyActionMode(ActionMode mode){}
        });return t;}
    boolean bottom(){return scroll.getChildCount()==0||scroll.getChildAt(0).getHeight()-scroll.getHeight()-scroll.getScrollY()<=ui.dp(96);}
    void toEnd(){scroll.post(()->scroll.scrollTo(0,scroll.getChildAt(0).getHeight()));}
    void reset(String id){session=id;start=-1;limit=100;lastLive="";rendered.clear();anchors.clear();userTitles.clear();messages.removeAllViews();live.setVisibility(GONE);}
    void render(JSONArray array,String streaming){render(array,streaming,true);}
    void render(JSONArray array,String streaming,boolean changed){history=array;boolean tail=bottom()||start<0;int oldY=scroll.getScrollY();int nextStart=Math.max(0,array.length()-limit);
        // Existing cards are immutable in normal operation; rebuild only on pagination/deletion.
        boolean rebuild=start<0||array.length()<rendered.size();
        if(!rebuild&&changed)for(int i=start;i<Math.min(array.length(),start+rendered.size());i++){var m=array.optJSONObject(i);String old=rendered.get(m.optLong("sequence"));if(old!=null&&!old.equals(m.toString())){rebuild=true;break;}}
        if(rebuild){start=nextStart;rendered.clear();anchors.clear();userTitles.clear();messages.removeAllViews();if(start>0||more){messages.addView(ui.button("Загрузить предыдущие сообщения",()->{if(start>0){int before=scroll.getChildAt(0).getHeight(),y=scroll.getScrollY();limit+=100;start=-1;render(history,lastLive,true);scroll.post(()->scroll.scrollTo(0,y+scroll.getChildAt(0).getHeight()-before));}else loadEarlier.run();}));ui.gap(messages,16);}}
        for(int i=start;i<array.length();i++){var m=array.optJSONObject(i);long key=m.optLong("sequence");if(rendered.containsKey(key))continue;rendered.put(key,m.toString());String role=m.optString("role"),text=m.optString("text");boolean user=role.equals("user"),assistant=role.equals("assistant");
            var card=ui.col();if(user){card.setBackground(ui.shape(Ui.PANEL,18));ui.pad(card,14,6);}var header=ui.row();var name=ui.label(user?"ВЫ":assistant?m.optString("provider","Codex").toUpperCase(Locale.ROOT):"СОБЫТИЕ",11,user?Ui.ACCENT:Ui.MUTED);name.setTypeface(Typeface.DEFAULT_BOLD);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));header.addView(ui.icon("copy","Копировать сообщение",()->ui.copy(withoutActions(text))),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));card.addView(header);
            var t=body();if(user)t.setText(text);else render(t,narration(text));if(!assistant&&!user){t.setMaxLines(3);t.setEllipsize(TextUtils.TruncateAt.END);t.setTextColor(Ui.MUTED);t.setOnClickListener(v->{var detail=ui.col();ui.pad(detail,16,12);var full=body();render(full,narration(text));var sc=new ScrollView(getContext());sc.addView(full);detail.addView(sc,new LinearLayout.LayoutParams(-1,-1));ui.sheet("Событие",detail);});}card.addView(t);ui.gap(card,user?14:4);messages.addView(card);anchors.put(key,card);if(user)userTitles.put(key,text.replace('\n',' '));ui.gap(messages,20);
        }
        if(!streaming.equals(lastLive)){lastLive=streaming;render(liveText,narration(streaming));}live.setVisibility(streaming.isEmpty()?GONE:VISIBLE);
        if(array.length()==0&&streaming.isEmpty()&&messages.getChildCount()==0){var empty=ui.col();ui.pad(empty,8,36);empty.addView(ui.title("Начнём с задачи"));ui.gap(empty,12);empty.addView(ui.label("Все действия выполняются на ноутбуке. Здесь — тот же разговор, очередь и результаты.",16,Ui.MUTED));messages.addView(empty);start=-1;}
        scroll.post(()->{if(tail)toEnd();else scroll.scrollTo(0,oldY);});
    }
    void loadedEarlier(JSONArray array,String streaming,boolean more){this.more=more;int before=scroll.getChildAt(0).getHeight(),y=scroll.getScrollY();limit=Math.max(limit,array.length());start=-1;render(array,streaming,true);scroll.post(()->scroll.scrollTo(0,y+scroll.getChildAt(0).getHeight()-before));}
    void jump(long sequence){if(!anchors.containsKey(sequence)){limit=history.length();start=-1;render(history,lastLive);}scroll.post(()->{View target=anchors.get(sequence);if(target!=null){scroll.smoothScrollTo(0,target.getTop());target.animate().alpha(.45f).setDuration(100).withEndAction(()->target.animate().alpha(1).setDuration(350)).start();}});}
}

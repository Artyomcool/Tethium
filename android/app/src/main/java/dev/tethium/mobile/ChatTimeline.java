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
import java.util.regex.Pattern;
import java.io.File;

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
    /*
     * The view keeps its bottom line: the card at the bottom edge of the screen and its distance from that edge are remembered
     * on every scroll, and put back whenever the content changes height (an image arriving, a rebuilt list, earlier messages).
     * At the end of the conversation it stays at the end.
     */
    private boolean follow=true,adjusting;
    private long anchorKey=Long.MIN_VALUE;
    private int anchorOffset;
    private LinearLayout content;
    private static final long LIVE=Long.MIN_VALUE+1;
    private int edge(){return scroll.getScrollY()+scroll.getHeight()-scroll.getPaddingBottom();}
    private int topOf(View view){return view==live?live.getTop():messages.getTop()+view.getTop();}
    private void remember(){
        if(adjusting||content==null)return;
        follow=bottom();int edge=edge();anchorKey=Long.MIN_VALUE;
        if(live.getVisibility()==VISIBLE&&live.getTop()<=edge){anchorKey=LIVE;anchorOffset=edge-live.getTop();return;}
        for(var entry:anchors.entrySet()){int top=topOf(entry.getValue());if(top>edge)break;anchorKey=entry.getKey();anchorOffset=edge-top;}
    }
    private void keepAnchor(){
        if(content==null)return;
        adjusting=true;
        try{
            if(follow){scroll.scrollTo(0,content.getHeight());return;}
            View view=anchorKey==LIVE?live:anchors.get(anchorKey);
            if(view==null||view.getParent()==null)return;
            scroll.scrollTo(0,Math.max(0,topOf(view)+anchorOffset-(scroll.getHeight()-scroll.getPaddingBottom())));
        }finally{adjusting=false;}
    }
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
        text=visualizeLinks(text);
        if(!text.contains("```thought"))return text;
        var out=new StringBuilder();int at=0;
        while(true){int open=text.indexOf("```thought",at);if(open<0){out.append(text,at,text.length());break;}
            out.append(text,at,open);int bodyStart=text.indexOf('\n',open);if(bodyStart<0)break;int close=text.indexOf("\n```",bodyStart);String body=close<0?text.substring(bodyStart+1):text.substring(bodyStart+1,close);
            if(showThoughts){for(String line:body.split("\n"))out.append("> ").append(line.isEmpty()?"\u200b":line).append('\n');out.append('\n');}
            if(close<0)break;at=close+4;}
        return out.toString();
    }
    /** Tethium does not implement Codex's native visualize card, so expose the already published file as a normal link. */
    static String visualizeLinks(String text){
        var matcher=Pattern.compile("\\uE200visualize\\uE202(.*?)\\uE201",Pattern.DOTALL).matcher(text);var out=new StringBuffer();
        while(matcher.find()){
            String replacement="Визуализация";
            try{var value=new JSONObject(matcher.group(1));String path=value.optString("path"),title=value.optString("title","Визуализация");if(!path.isBlank())replacement="["+title.replace("\\","\\\\").replace("[","\\[").replace("]","\\]")+"]("+path.replace('\\','/').replace("%","%25").replace(" ","%20").replace("(","%28").replace(")","%29").replace("<","%3C").replace(">","%3E")+")";}catch(Exception ignored){}
            matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);return out.toString();
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
        var all=ui.col();content=all;all.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot)keepAnchor();});messages=ui.col();all.addView(messages);live=ui.col();var head=ui.label("CODEX · ПИШЕТ",11,Ui.ACCENT);head.setTypeface(Typeface.DEFAULT_BOLD);live.addView(head);ui.gap(live,12);liveText=body();live.addView(liveText);live.setVisibility(GONE);all.addView(live);scroll.addView(all);addView(scroll,new LayoutParams(-1,-1));
        end=ui.button("В конец ↓",this::toEnd);end.setElevation(ui.dp(6));var ep=new LayoutParams(-2,ui.dp(48),Gravity.BOTTOM|Gravity.RIGHT);ep.setMargins(0,0,ui.dp(16),ui.dp(8));addView(end,ep);end.setVisibility(GONE);
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{remember();end.setVisibility(bottom()?GONE:VISIBLE);String current="Запросы в разговоре";for(var e:anchors.entrySet())if(e.getValue().getTop()<=y+ui.dp(24)&&userTitles.containsKey(e.getKey()))current=userTitles.get(e.getKey());currentRequest.accept(current);if(bottom())atEnd.run();});
    }
    BiConsumer<String,Consumer<File>> loadImage=(link,done)->{};
    BiConsumer<String,File> openImage=(link,file)->{};
    private static final Pattern IMAGE_LINK=Pattern.compile("!?\\[([^\\]]*)\\]\\(<?([^)>\\s]+\\.(?:png|jpe?g|gif|webp|bmp))>?\\)",Pattern.CASE_INSENSITIVE);
    /** Images a message links to are shown under its text; a tap opens them full screen. */
    private void images(LinearLayout card,String text){
        var matcher=IMAGE_LINK.matcher(text);int count=0;
        while(matcher.find()&&count++<6)card.addView(image(matcher.group(2),matcher.group(1).isBlank()?"Изображение":matcher.group(1)));
    }
    private final Map<Long,View> media=new HashMap<>();
    private final Map<Long,Long> mediaAnchors=new HashMap<>();
    /** Images the agent made go under the answer they belong to, or under the live answer while it is being written. */
    void media(JSONArray entries){
        for(int i=0;i<entries.length();i++){
            var entry=entries.optJSONObject(i);if(entry==null)continue;
            long id=entry.optLong("mediaId"),anchor=entry.optLong("anchor");
            ViewGroup parent=anchor<0?live:anchors.get(anchor) instanceof ViewGroup card?card:null;
            var view=media.get(id);
            if(view!=null&&view.getParent()==parent&&Objects.equals(mediaAnchors.get(id),anchor))continue;
            if(view!=null&&view.getParent() instanceof ViewGroup old)old.removeView(view);
            if(parent==null){media.remove(id);continue;}
            if(view==null){view=image(entry.optString("file"),entry.optString("title","Изображение"));media.put(id,view);}
            parent.addView(view);mediaAnchors.put(id,anchor);
        }
    }
    private ImageView image(String link,String description){
            var image=new ImageView(getContext());image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_START);image.setBackground(ui.shape(Ui.PANEL,12));image.setContentDescription(description);
            var params=new LinearLayout.LayoutParams(-1,ui.dp(120));params.topMargin=ui.dp(8);image.setLayoutParams(params);
            loadImage.accept(link,file->{
                var bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(file.toString(),bounds);
                var options=new android.graphics.BitmapFactory.Options();options.inSampleSize=1;while(bounds.outWidth/options.inSampleSize>1400)options.inSampleSize*=2;
                var bitmap=android.graphics.BitmapFactory.decodeFile(file.toString(),options);if(bitmap==null)return;
                image.setBackground(null);image.setImageBitmap(bitmap);image.setMaxHeight(ui.dp(320));image.setLayoutParams(new LinearLayout.LayoutParams(-1,-2){{topMargin=ui.dp(8);}});
                image.setOnClickListener(v->openImage.accept(link,file));
            });
            return image;
    }
    private TextView body(){var t=ui.label("",16,Ui.TEXT);t.setTextIsSelectable(true);t.setLineSpacing(ui.dp(5),1);
        // Selectable text takes taps for itself, so links are opened here; a long press still selects.
        var taps=new GestureDetector(getContext(),new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onSingleTapUp(MotionEvent e){
                var layout=t.getLayout();if(layout==null||!(t.getText() instanceof android.text.Spanned spanned))return false;
                int x=(int)e.getX()-t.getTotalPaddingLeft()+t.getScrollX(),y=(int)e.getY()-t.getTotalPaddingTop()+t.getScrollY();
                int line=layout.getLineForVertical(y);if(x<layout.getLineLeft(line)||x>layout.getLineRight(line))return false;
                int offset=layout.getOffsetForHorizontal(line,x);var links=spanned.getSpans(offset,offset,android.text.style.ClickableSpan.class);
                if(links.length==0)return false;links[0].onClick(t);return true;
            }
        });
        t.setOnTouchListener((v,e)->{
            if(!taps.onTouchEvent(e))return false;
            var cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);t.onTouchEvent(cancel);cancel.recycle();return true;
        });
        t.setCustomSelectionActionModeCallback(new ActionMode.Callback(){
            public boolean onCreateActionMode(ActionMode mode,Menu menu){menu.add(0,9127,0,"Цитировать").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);return true;}
            public boolean onPrepareActionMode(ActionMode mode,Menu menu){return false;}
            public boolean onActionItemClicked(ActionMode mode,MenuItem item){if(item.getItemId()!=9127)return false;int a=t.getSelectionStart(),b=t.getSelectionEnd();if(a>=0&&b>a)quote.accept(t.getText().subSequence(a,b).toString());mode.finish();return true;}
            public void onDestroyActionMode(ActionMode mode){}
        });return t;}
    boolean bottom(){return scroll.getChildCount()==0||scroll.getChildAt(0).getHeight()-scroll.getHeight()-scroll.getScrollY()<=ui.dp(96);}
    void toEnd(){follow=true;scroll.post(()->scroll.scrollTo(0,scroll.getChildAt(0).getHeight()));}
    void reset(String id){follow=true;anchorKey=Long.MIN_VALUE;session=id;start=-1;limit=100;lastLive="";rendered.clear();anchors.clear();userTitles.clear();messages.removeAllViews();live.setVisibility(GONE);}
    void render(JSONArray array,String streaming){render(array,streaming,true);}
    void render(JSONArray array,String streaming,boolean changed){history=array;if(messages.getChildCount()==0)follow=true;else remember();int nextStart=Math.max(0,array.length()-limit);
        // Existing cards are immutable in normal operation; rebuild only on pagination/deletion.
        boolean rebuild=start<0||array.length()<rendered.size();
        if(!rebuild&&changed)for(int i=start;i<Math.min(array.length(),start+rendered.size());i++){var m=array.optJSONObject(i);String old=rendered.get(m.optLong("sequence"));if(old!=null&&!old.equals(m.toString())){rebuild=true;break;}}
        if(rebuild){start=nextStart;rendered.clear();anchors.clear();for(var view:media.values())if(view.getParent() instanceof ViewGroup old)old.removeView(view);mediaAnchors.clear();userTitles.clear();messages.removeAllViews();if(start>0||more){messages.addView(ui.button("Загрузить предыдущие сообщения",()->{if(start>0){limit+=100;start=-1;render(history,lastLive,true);}else loadEarlier.run();}));ui.gap(messages,16);}}
        for(int i=start;i<array.length();i++){var m=array.optJSONObject(i);long key=m.optLong("sequence");if(rendered.containsKey(key))continue;rendered.put(key,m.toString());String role=m.optString("role"),text=m.optString("text");boolean user=role.equals("user"),assistant=role.equals("assistant");
            var card=ui.col();if(user){card.setBackground(ui.shape(Ui.PANEL,18));ui.pad(card,14,6);}var header=ui.row();var name=ui.label(user?"ВЫ":assistant?m.optString("provider","Codex").toUpperCase(Locale.ROOT):"СОБЫТИЕ",11,user?Ui.ACCENT:Ui.MUTED);name.setTypeface(Typeface.DEFAULT_BOLD);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));header.addView(ui.icon("copy","Копировать сообщение",()->ui.copy(withoutActions(text))),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));card.addView(header);
            var t=body();if(user)t.setText(IMAGE_LINK.matcher(text).replaceAll("").strip());else render(t,narration(text));if(!assistant&&!user){t.setMaxLines(3);t.setEllipsize(TextUtils.TruncateAt.END);t.setTextColor(Ui.MUTED);t.setOnClickListener(v->{var detail=ui.col();ui.pad(detail,16,12);var full=body();render(full,narration(text));var sc=new ScrollView(getContext());sc.addView(full);detail.addView(sc,new LinearLayout.LayoutParams(-1,-1));ui.sheet("Событие",detail);});}card.addView(t);images(card,visualizeLinks(text));ui.gap(card,user?14:4);messages.addView(card);anchors.put(key,card);if(user)userTitles.put(key,text.replace('\n',' '));ui.gap(messages,20);
        }
        if(!streaming.equals(lastLive)){lastLive=streaming;render(liveText,narration(streaming));}live.setVisibility(streaming.isEmpty()?GONE:VISIBLE);
        if(array.length()==0&&streaming.isEmpty()&&messages.getChildCount()==0){var empty=ui.col();ui.pad(empty,8,36);empty.addView(ui.title("Начнём с задачи"));ui.gap(empty,12);empty.addView(ui.label("Все действия выполняются на ноутбуке. Здесь — тот же разговор, очередь и результаты.",16,Ui.MUTED));messages.addView(empty);start=-1;}
        scroll.post(this::keepAnchor);
    }
    void loadedEarlier(JSONArray array,String streaming,boolean more){this.more=more;limit=Math.max(limit,array.length());start=-1;render(array,streaming,true);}
    void jump(long sequence){if(!anchors.containsKey(sequence)){limit=history.length();start=-1;render(history,lastLive);}scroll.post(()->{View target=anchors.get(sequence);if(target!=null){scroll.smoothScrollTo(0,target.getTop());target.animate().alpha(.45f).setDuration(100).withEndAction(()->target.animate().alpha(1).setDuration(350)).start();}});}
}

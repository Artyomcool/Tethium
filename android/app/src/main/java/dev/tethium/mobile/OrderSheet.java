package dev.tethium.mobile;

import android.app.*;
import android.content.ClipData;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** An explicit editing surface: normal project/session rows keep their compact layout. */
final class OrderSheet {
    private record Item(String id,String title) {}
    private record Drag(OrderSheet owner,String id) {}
    private final Ui ui;
    private final Connection connection;
    private final Executor executor;
    private final Consumer<JSONObject> saved;
    private final String endpoint;
    private final ArrayList<Item> items;
    private final List<String> original;
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final Button save;
    private boolean saving,dragging;
    private int scrollStep;
    private final Runnable autoScroll=new Runnable(){public void run(){if(!dragging||scrollStep==0)return;scroll.scrollBy(0,scrollStep);handler.postDelayed(this,30);}};

    static void projects(Ui ui,Connection c,JSONObject snapshot,Executor executor,Consumer<JSONObject> saved) {
        var items=new ArrayList<Item>();var projects=snapshot.optJSONArray("projects");
        if(projects!=null)for(int i=0;i<projects.length();i++){var p=projects.optJSONObject(i);if(p!=null)items.add(new Item(p.optString("id"),p.optString("title")));}
        new OrderSheet(ui,c,executor,saved,"Порядок проектов","/projects/order",items);
    }
    static void sessions(Ui ui,Connection c,JSONObject snapshot,JSONObject chosen,Executor executor,Consumer<JSONObject> saved) {
        var items=new ArrayList<Item>();var sessions=snapshot.optJSONArray("sessions");
        String project=chosen.optString("projectId"),parent=chosen.optString("parentId");boolean active=chosen.optBoolean("active");
        if(sessions!=null)for(int i=0;i<sessions.length();i++){var s=sessions.optJSONObject(i);
            if(s!=null&&project.equals(s.optString("projectId"))&&parent.equals(s.optString("parentId"))&&active==s.optBoolean("active"))items.add(new Item(s.optString("id"),s.optString("title")));}
        new OrderSheet(ui,c,executor,saved,active?"Порядок сессий":"Порядок неактивных сессий","/projects/"+project+"/sessions-order",items);
    }
    private OrderSheet(Ui ui,Connection connection,Executor executor,Consumer<JSONObject> saved,String title,String endpoint,ArrayList<Item> items) {
        this.ui=ui;this.connection=connection;this.executor=executor;this.saved=saved;this.endpoint=endpoint;this.items=items;
        original=ids();var content=ui.col();ui.pad(content,16,8);
        content.addView(ui.label("Зажмите ручку справа и перетащите строку или используйте стрелки.",13,Ui.MUTED));ui.gap(content,8);
        scroll=new ScrollView(ui.a);rows=ui.col();scroll.addView(rows);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnDragListener((v,e)->{if(!own(e))return false;if(e.getAction()==DragEvent.ACTION_DRAG_LOCATION)autoScroll(v,e);else if(e.getAction()==DragEvent.ACTION_DRAG_ENDED)endDrag();return true;});
        save=ui.primary("Сохранить",this::save);ui.gap(content,8);content.addView(save,new LinearLayout.LayoutParams(-1,ui.dp(48)));
        dialog=ui.sheet(title,content);dialog.setOnDismissListener(d->endDrag());fill();
    }
    private List<String> ids(){var ids=new ArrayList<String>();for(var item:items)ids.add(item.id);return ids;}
    private void fill(){
        rows.removeAllViews();
        for(int i=0;i<items.size();i++){
            final int index=i;var item=items.get(i);var tile=ui.col();tile.setBackground(ui.shape(Ui.RAISED,10));
            var top=new View(ui.a);top.setBackgroundColor(Ui.ACCENT);top.setVisibility(View.INVISIBLE);tile.addView(top,new LinearLayout.LayoutParams(-1,ui.dp(2)));
            var row=ui.row();var label=ui.label(item.title,15,Ui.TEXT);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);ui.pad(label,12,0);row.addView(label,new LinearLayout.LayoutParams(0,ui.dp(48),1));label.setGravity(Gravity.CENTER_VERTICAL);
            var up=ui.icon("send","Поднять: "+item.title,()->move(index,index-1));up.setEnabled(!saving&&i>0);up.setAlpha(up.isEnabled()?1:.25f);row.addView(up,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
            var down=ui.icon("down","Опустить: "+item.title,()->move(index,index+1));down.setEnabled(!saving&&i+1<items.size());down.setAlpha(down.isEnabled()?1:.25f);row.addView(down,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
            var grip=ui.icon("grip","Зажмите, чтобы переместить: "+item.title,()->Toast.makeText(ui.a,"Зажмите и перетащите строку",Toast.LENGTH_SHORT).show());row.addView(grip,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
            View.OnLongClickListener start=v->{if(saving||items.size()<2)return false;dragging=true;boolean started=tile.startDragAndDrop(ClipData.newPlainText("Tethium order",item.id),new View.DragShadowBuilder(tile),new Drag(this,item.id),0);if(started){tile.setAlpha(.5f);v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}else endDrag();return started;};
            grip.setOnLongClickListener(start);label.setOnLongClickListener(start);tile.addView(row);
            var bottom=new View(ui.a);bottom.setBackgroundColor(Ui.ACCENT);bottom.setVisibility(View.INVISIBLE);tile.addView(bottom,new LinearLayout.LayoutParams(-1,ui.dp(2)));
            tile.setOnDragListener((v,e)->{
                if(!own(e))return false;var drag=(Drag)e.getLocalState();boolean after=e.getY()>v.getHeight()/2f;
                switch(e.getAction()){
                    case DragEvent.ACTION_DRAG_LOCATION -> {autoScroll(v,e);top.setVisibility(!drag.id.equals(item.id)&&!after?View.VISIBLE:View.INVISIBLE);bottom.setVisibility(!drag.id.equals(item.id)&&after?View.VISIBLE:View.INVISIBLE);}
                    case DragEvent.ACTION_DRAG_EXITED -> {top.setVisibility(View.INVISIBLE);bottom.setVisibility(View.INVISIBLE);}
                    case DragEvent.ACTION_DROP -> {endDrag();rows.post(()->drop(drag.id,item.id,after));}
                    case DragEvent.ACTION_DRAG_ENDED -> {endDrag();tile.setAlpha(1);top.setVisibility(View.INVISIBLE);bottom.setVisibility(View.INVISIBLE);}
                }
                return true;
            });
            rows.addView(tile);ui.gap(rows,4);
        }
        if(items.size()<2)rows.addView(ui.label("Пока нечего переставлять",14,Ui.MUTED));
        save.setEnabled(!saving&&!original.equals(ids()));
    }
    private boolean own(DragEvent e){return e.getLocalState() instanceof Drag d&&d.owner==this&&!saving;}
    private void move(int from,int to){if(saving||from<0||to<0||from>=items.size()||to>=items.size())return;var item=items.remove(from);items.add(to,item);fill();}
    private void drop(String source,String target,boolean after){if(saving||source.equals(target))return;Item moving=null;for(var item:items)if(item.id.equals(source))moving=item;if(moving==null)return;items.remove(moving);int at=0;while(at<items.size()&&!items.get(at).id.equals(target))at++;items.add(Math.min(items.size(),at+(after?1:0)),moving);fill();}
    private void autoScroll(View view,DragEvent e){int[] rowAt=new int[2],scrollAt=new int[2];view.getLocationOnScreen(rowAt);scroll.getLocationOnScreen(scrollAt);float y=rowAt[1]+e.getY()-scrollAt[1];int next=y<ui.dp(56)?-ui.dp(12):y>scroll.getHeight()-ui.dp(56)?ui.dp(12):0;if(next!=scrollStep){handler.removeCallbacks(autoScroll);scrollStep=next;if(next!=0)handler.post(autoScroll);}}
    private void endDrag(){dragging=false;scrollStep=0;handler.removeCallbacks(autoScroll);}
    private void save(){
        if(saving||connection==null||original.equals(ids()))return;var body=Connection.json("ids",new JSONArray(ids()));saving=true;endDrag();fill();
        ui.async("order:"+endpoint,"Сохраняем…",save,executor,()->{connection.request(endpoint,body);return connection.request("/state",null);},state->{dialog.dismiss();saved.accept(state);},()->{saving=false;if(dialog.isShowing())fill();});
    }
}

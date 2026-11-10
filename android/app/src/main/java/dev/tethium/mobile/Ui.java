package dev.tethium.mobile;

import android.app.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Shared spacing, typography and vector controls. Touch targets are at least 48dp. */
final class Ui {
    static final int BG=0xff111318, PANEL=0xff1c2028, RAISED=0xff272c37, BORDER=0xff333a48,
        TEXT=0xffedf0f7, MUTED=0xffa4adbf, ACCENT=0xffb8adff, INK=0xff25203e,
        GREEN=0xff91d9b3, AMBER=0xffffcd89, RED=0xffffa8aa;
    final Activity a;
    private View clicked;
    private final Map<String,Pending> pending=new HashMap<>();
    private record Pending(View view,CharSequence text,CharSequence description,boolean enabled,Drawable image,android.animation.ObjectAnimator animation) {}
    Ui(Activity a){this.a=a;}
    void click(View view,Runnable action){view.setOnClickListener(v->{View previous=clicked;clicked=v;try{action.run();}finally{clicked=previous;}});}
    boolean pending(String key){return pending.containsKey(key);}
    <T> void async(String key,String label,Executor executor,Callable<T> work,Consumer<T> success){async(key,label,clicked,executor,work,success,()->{});}
    <T> void async(String key,String label,Executor executor,Callable<T> work,Consumer<T> success,Runnable settled){async(key,label,clicked,executor,work,success,settled);}
    <T> void async(String key,String label,View source,Executor executor,Callable<T> work,Consumer<T> success,Runnable settled){
        if(pending(key)||a.isDestroyed()||a.isFinishing())return;
        var animation=source instanceof ImageButton?android.animation.ObjectAnimator.ofFloat(source,"rotation",0,360):null;
        var state=new Pending(source,source instanceof TextView t?t.getText():null,source==null?null:source.getContentDescription(),source!=null&&source.isEnabled(),source instanceof ImageButton image?image.getDrawable():null,animation);pending.put(key,state);
        if(source!=null){source.setEnabled(false);source.setAlpha(.55f);source.setContentDescription(label);if(source instanceof TextView t)t.setText(label);}
        if(animation!=null){((ImageButton)source).setImageDrawable(new Glyph("refresh",ACCENT));animation.setDuration(900);animation.setRepeatCount(-1);animation.setInterpolator(new android.view.animation.LinearInterpolator());animation.start();}
        Toast.makeText(a,label,Toast.LENGTH_SHORT).show();
        try{executor.execute(()->{T result=null;Exception failure=null;try{result=work.call();}catch(Exception e){failure=e;}final T value=result;final Exception error=failure;a.runOnUiThread(()->{
            pending.remove(key);restore(state);
            if(a.isDestroyed()||a.isFinishing())return;
            try{if(error==null)success.accept(value);else new AlertDialog.Builder(a).setMessage(Objects.toString(error.getMessage(),"Ошибка операции")).setPositiveButton("OK",null).show();}finally{settled.run();}
        });});}catch(RejectedExecutionException e){pending.remove(key);restore(state);settled.run();}
    }
    private void restore(Pending state){var view=state.view();if(view==null)return;if(state.animation()!=null){state.animation().cancel();view.setRotation(0);((ImageButton)view).setImageDrawable(state.image());}view.setEnabled(state.enabled());view.setAlpha(1);view.setContentDescription(state.description());if(view instanceof TextView t)t.setText(state.text());}
    void close(){for(var operation:pending.values())restore(operation);pending.clear();}
    int dp(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    LinearLayout col(){var v=new LinearLayout(a);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){var v=new LinearLayout(a);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    TextView label(String s,int size,int color){var t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1);return t;}
    TextView title(String s){var t=label(s,18,TEXT);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    GradientDrawable shape(int color,int radius){var g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    Drawable ripple(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(color,radius),shape(-1,radius));}
    Button button(String s,Runnable action){var b=new Button(a);b.setText(s);b.setTextSize(14);b.setTextColor(TEXT);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setAllCaps(false);b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(14),0,dp(14),0);b.setBackground(ripple(PANEL,14));b.setStateListAnimator(null);b.setElevation(0);b.setContentDescription(s);click(b,action);return b;}
    Button primary(String s,Runnable action){var b=button(s,action);b.setBackground(ripple(ACCENT,14));b.setTextColor(INK);return b;}
    ImageButton icon(String name,String description,Runnable action){var b=new ImageButton(a);b.setImageDrawable(new Glyph(name,TEXT));b.setContentDescription(description);b.setTooltipText(description);b.setPadding(dp(13),dp(13),dp(13),dp(13));b.setBackground(ripple(0,14));b.setMinimumWidth(dp(48));b.setMinimumHeight(dp(48));click(b,action);return b;}
    EditText edit(String hint){var e=new EditText(a);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(16);e.setHint(hint);e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setBackground(shape(RAISED,14));e.setMinHeight(dp(48));return e;}
    View field(View field){var frame=col();pad(frame,20,8);frame.addView(field,new LinearLayout.LayoutParams(-1,-2));return frame;}
    void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    void gap(LinearLayout parent,int n){parent.addView(new View(a),new LinearLayout.LayoutParams(1,dp(n)));}
    void line(LinearLayout parent){var v=new View(a);v.setBackgroundColor(BORDER);parent.addView(v,new LinearLayout.LayoutParams(-1,dp(1)));}
    void inset(View view){inset(view,()->{});}
    void inset(View view,Runnable after){view.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){var i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(i.left,i.top,i.right,i.bottom);}else v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());v.post(after);return insets;});view.requestApplyInsets();}
    Dialog sheet(String title,LinearLayout content){return sheet(title,content,null,null,null);}
    Dialog sheet(String title,LinearLayout content,String actionIcon,String actionDescription,Consumer<Dialog> action){var d=new Dialog(a);var outer=col();outer.setBackground(shape(PANEL,24));var h=row();pad(h,8,4);var t=title(title);pad(t,12,0);if(action!=null){t.setSingleLine();t.setEllipsize(android.text.TextUtils.TruncateAt.END);}h.addView(t,new LinearLayout.LayoutParams(0,-2,1));if(action!=null)h.addView(icon(actionIcon,actionDescription,()->action.accept(d)),new LinearLayout.LayoutParams(dp(48),dp(48)));h.addView(icon("close","Закрыть панель",d::dismiss),new LinearLayout.LayoutParams(dp(48),dp(48)));outer.addView(h);line(outer);outer.addView(content,new LinearLayout.LayoutParams(-1,0,1));d.setContentView(outer);var w=d.getWindow();w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);var attrs=w.getAttributes();attrs.dimAmount=.55f;w.setAttributes(attrs);w.setGravity(Gravity.BOTTOM);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);d.show();w.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels,dp(600)),(int)(a.getResources().getDisplayMetrics().heightPixels*.88));inset(outer);return d;}
    void copy(String s){((android.content.ClipboardManager)a.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Tethium",s));Toast.makeText(a,"Скопировано",Toast.LENGTH_SHORT).show();}
    void compact(Dialog dialog,int height){dialog.getWindow().setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels,dp(600)),Math.min(dp(height),(int)(a.getResources().getDisplayMetrics().heightPixels*.88)));}
    static final class Glyph extends Drawable {
        final String name; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(String name,int color){this.name=name;p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
        void path(Canvas c,float...xy){Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);c.drawPath(path,p);}
        public void draw(Canvas c){c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);
            switch(name){
                case "settings"->{path(c,4,6,20,6);path(c,4,12,20,12);path(c,4,18,20,18);c.drawCircle(8,6,2,p);c.drawCircle(16,12,2,p);c.drawCircle(10,18,2,p);}
                case "gauge"->{c.drawArc(3,4,21,22,180,180,false,p);path(c,4,14,4,18,20,18,20,14);path(c,12,15,16,9);}
                case "chart"->{path(c,4,3,4,20,21,20);path(c,8,15,12,10,16,13,21,5);}
                case "laptop"->{c.drawRoundRect(4,4,20,16,2,2,p);path(c,2,20,22,20);}
                case "store"->{c.drawRoundRect(4,7,20,21,2,2,p);c.drawArc(8,2,16,12,180,180,false,p);}
                case "archive"->{c.drawRoundRect(3,4,21,9,1,1,p);path(c,5,9,5,20,19,20,19,9);path(c,9,13,15,13);}
                case "expand"->{path(c,9,3,3,3,3,9);path(c,15,3,21,3,21,9);path(c,3,15,3,21,9,21);path(c,15,21,21,21,21,15);}
                case "menu"->{path(c,4,6,20,6);path(c,4,12,16,12);path(c,4,18,20,18);}
                case "close"->{path(c,6,6,18,18);path(c,18,6,6,18);}
                case "more"->{for(int y=5;y<=19;y+=7)c.drawCircle(12,y,1,p);}
                case "send"->{path(c,5,11,12,4,19,11);path(c,12,4,12,20);}
                case "down"->{path(c,5,10,12,17,19,10);}
                case "chevron"->{path(c,9,5,16,12,9,19);}
                case "search"->{c.drawCircle(10,10,6,p);path(c,15,15,21,21);}
                case "plus"->{path(c,5,12,19,12);path(c,12,5,12,19);}
                case "stop"->c.drawRoundRect(5,5,19,19,2,2,p);
                case "bolt"->path(c,13,2,5,14,11,14,10,22,19,9,13,9,13,2);
                case "image"->{c.drawRoundRect(3,3,21,21,3,3,p);c.drawCircle(9,8,2,p);path(c,3,18,9,12,13,16,17,12,21,16);}
                case "queue"->{path(c,4,6,20,6);path(c,4,12,16,12);path(c,4,18,12,18);}
                case "history"->{path(c,4,4,4,10,10,10);c.drawArc(4,4,21,21,-140,300,false,p);path(c,12,8,12,13,16,15);}
                case "copy"->{c.drawRoundRect(7,7,21,21,2,2,p);path(c,17,3,3,3,3,17);}
                case "project-folder"->{p.setStrokeWidth(1.5f);Path f=new Path();f.moveTo(3,7);f.lineTo(3,5.5f);f.arcTo(3,4,6,7,180,90,false);f.lineTo(9,4);f.lineTo(11,7);f.lineTo(19.5f,7);f.arcTo(18,7,21,10,270,90,false);f.lineTo(21,18.5f);f.arcTo(18,17,21,20,0,90,false);f.lineTo(4.5f,20);f.arcTo(3,17,6,20,90,90,false);f.close();c.drawPath(f,p);}
                case "session-chat"->{p.setStrokeWidth(1.5f);c.translate(0,-.5f);Path f=new Path();f.moveTo(6,18);f.lineTo(3,21);f.lineTo(3,5.5f);f.arcTo(3,4,6,7,180,90,false);f.lineTo(19.5f,4);f.arcTo(18,4,21,7,270,90,false);f.lineTo(21,16.5f);f.arcTo(18,15,21,18,0,90,false);f.lineTo(6,18);f.close();c.drawPath(f,p);}
                case "folder"->path(c,3,7,3,4,9,4,12,7,21,7,21,20,3,20,3,7);
                case "check"->path(c,4,12,10,18,21,6);
                case "refresh"->{c.drawArc(4,4,20,20,40,290,false,p);path(c,20,3,20,9,14,9);}
                case "edit"->{path(c,4,16,16,4,20,8,8,20,4,20,4,16);path(c,13,7,17,11);}
                default->c.drawCircle(12,12,8,p);
            }c.restore();}
        public void setAlpha(int alpha){p.setAlpha(alpha);}public void setColorFilter(ColorFilter f){p.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}

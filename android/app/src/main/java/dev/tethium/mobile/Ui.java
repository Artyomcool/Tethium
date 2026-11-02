package dev.tethium.mobile;

import android.app.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Shared spacing, typography and vector controls. Touch targets are at least 48dp. */
final class Ui {
    static final int BG=0xff111318, PANEL=0xff1c2028, RAISED=0xff272c37, BORDER=0xff333a48,
        TEXT=0xffedf0f7, MUTED=0xffa4adbf, ACCENT=0xffb8adff, INK=0xff25203e,
        GREEN=0xff91d9b3, AMBER=0xffffcd89, RED=0xffffa8aa;
    final Activity a;
    Ui(Activity a){this.a=a;}
    int dp(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    LinearLayout col(){var v=new LinearLayout(a);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){var v=new LinearLayout(a);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    TextView label(String s,int size,int color){var t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1);return t;}
    TextView title(String s){var t=label(s,18,TEXT);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    GradientDrawable shape(int color,int radius){var g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    Drawable ripple(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(color,radius),shape(-1,radius));}
    Button button(String s,Runnable action){var b=new Button(a);b.setText(s);b.setTextSize(14);b.setTextColor(TEXT);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setAllCaps(false);b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(14),0,dp(14),0);b.setBackground(ripple(PANEL,14));b.setStateListAnimator(null);b.setElevation(0);b.setContentDescription(s);b.setOnClickListener(v->action.run());return b;}
    Button primary(String s,Runnable action){var b=button(s,action);b.setBackground(ripple(ACCENT,14));b.setTextColor(INK);return b;}
    ImageButton icon(String name,String description,Runnable action){var b=new ImageButton(a);b.setImageDrawable(new Glyph(name,TEXT));b.setContentDescription(description);b.setTooltipText(description);b.setPadding(dp(13),dp(13),dp(13),dp(13));b.setBackground(ripple(0,14));b.setMinimumWidth(dp(48));b.setMinimumHeight(dp(48));b.setOnClickListener(v->action.run());return b;}
    EditText edit(String hint){var e=new EditText(a);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(16);e.setHint(hint);e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setBackground(shape(RAISED,14));e.setMinHeight(dp(48));return e;}
    void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    void gap(LinearLayout parent,int n){parent.addView(new View(a),new LinearLayout.LayoutParams(1,dp(n)));}
    void line(LinearLayout parent){var v=new View(a);v.setBackgroundColor(BORDER);parent.addView(v,new LinearLayout.LayoutParams(-1,dp(1)));}
    void inset(View view){inset(view,()->{});}
    void inset(View view,Runnable after){view.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){var i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(i.left,i.top,i.right,i.bottom);}else v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());v.post(after);return insets;});view.requestApplyInsets();}
    Dialog sheet(String title,LinearLayout content){var d=new Dialog(a);var outer=col();outer.setBackground(shape(PANEL,24));var h=row();pad(h,8,4);var t=title(title);pad(t,12,0);h.addView(t,new LinearLayout.LayoutParams(0,-2,1));h.addView(icon("close","Закрыть панель",d::dismiss),new LinearLayout.LayoutParams(dp(48),dp(48)));outer.addView(h);line(outer);outer.addView(content,new LinearLayout.LayoutParams(-1,0,1));d.setContentView(outer);var w=d.getWindow();w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);var attrs=w.getAttributes();attrs.dimAmount=.55f;w.setAttributes(attrs);w.setGravity(Gravity.BOTTOM);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);d.show();w.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels,dp(600)),(int)(a.getResources().getDisplayMetrics().heightPixels*.88));inset(outer);return d;}
    void copy(String s){((android.content.ClipboardManager)a.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Tethium",s));Toast.makeText(a,"Скопировано",Toast.LENGTH_SHORT).show();}
    void compact(Dialog dialog,int height){dialog.getWindow().setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels,dp(600)),Math.min(dp(height),(int)(a.getResources().getDisplayMetrics().heightPixels*.88)));}
    static final class Glyph extends Drawable {
        final String name; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(String name,int color){this.name=name;p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
        void path(Canvas c,float...xy){Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);c.drawPath(path,p);}
        public void draw(Canvas c){c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);
            switch(name){
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
                case "folder"->path(c,3,7,3,4,9,4,12,7,21,7,21,20,3,20,3,7);
                case "check"->path(c,4,12,10,18,21,6);
                case "refresh"->{c.drawArc(4,4,20,20,40,290,false,p);path(c,20,3,20,9,14,9);}
                case "edit"->{path(c,4,16,16,4,20,8,8,20,4,20,4,16);path(c,13,7,17,11);}
                default->c.drawCircle(12,12,8,p);
            }c.restore();}
        public void setAlpha(int alpha){p.setAlpha(alpha);}public void setColorFilter(ColorFilter f){p.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}

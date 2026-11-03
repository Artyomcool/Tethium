package dev.tethium.mobile;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Compact action sheets; one row is one touch target, with explicit toggle states. */
final class ActionMenu {
    private final Ui ui;
    private final Dialog dialog;
    private final LinearLayout panel,content;
    private LinearLayout group;
    ActionMenu(Ui ui,String title,String subtitle){
        this.ui=ui;dialog=new Dialog(ui.a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel=ui.col();var background=ui.shape(Ui.PANEL,22);background.setStroke(ui.dp(1),Ui.BORDER);panel.setBackground(background);panel.setClipToOutline(true);
        var header=ui.row();header.setPadding(ui.dp(20),ui.dp(12),ui.dp(8),ui.dp(8));var names=ui.col();var heading=ui.title(title);heading.setMaxLines(2);heading.setEllipsize(TextUtils.TruncateAt.END);names.addView(heading);
        if(subtitle!=null&&!subtitle.isBlank()){ui.gap(names,5);var hint=ui.label(subtitle,12,Ui.MUTED);hint.setMaxLines(2);hint.setEllipsize(TextUtils.TruncateAt.END);names.addView(hint);}
        header.addView(names,new LinearLayout.LayoutParams(0,-2,1));header.addView(ui.icon("close","Закрыть меню",dialog::dismiss),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));panel.addView(header);
        content=ui.col();content.setPadding(ui.dp(12),0,ui.dp(12),ui.dp(12));var scroll=new ScrollView(ui.a){@Override protected void onMeasure(int width,int height){int cap=(int)(ui.a.getResources().getDisplayMetrics().heightPixels*.78)-ui.dp(90);super.onMeasure(width,MeasureSpec.makeMeasureSpec(Math.max(ui.dp(120),cap),MeasureSpec.AT_MOST));}};
        scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);scroll.addView(content);panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));dialog.setContentView(panel);
    }
    ActionMenu group(String label){
        var title=ui.label(label,11,Ui.MUTED);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setLetterSpacing(.06f);title.setPadding(ui.dp(12),ui.dp(14),ui.dp(12),ui.dp(8));content.addView(title);
        group=ui.col();group.setBackground(ui.shape(0xff242933,14));group.setClipToOutline(true);content.addView(group);return this;
    }
    ActionMenu action(String icon,String title,String subtitle,Runnable action){return row(icon,title,subtitle,Ui.TEXT,null,true,action);}
    ActionMenu action(String icon,String title,String subtitle,boolean enabled,Runnable action){return row(icon,title,subtitle,Ui.TEXT,null,enabled,action);}
    ActionMenu danger(String icon,String title,String subtitle,boolean enabled,Runnable action){return row(icon,title,subtitle,Ui.RED,null,enabled,action);}
    ActionMenu toggle(String icon,String title,String subtitle,boolean checked,Runnable action){return row(icon,title,subtitle,Ui.TEXT,checked,true,action);}
    private ActionMenu row(String icon,String title,String subtitle,int color,Boolean checked,boolean enabled,Runnable action){
        if(group==null)group("");
        if(group.getChildCount()>0){var divider=new View(ui.a);divider.setBackgroundColor(0xff303640);var space=new LinearLayout.LayoutParams(-1,1);space.setMargins(ui.dp(48),0,ui.dp(12),0);group.addView(divider,space);}
        var row=ui.row();row.setPadding(ui.dp(12),ui.dp(11),ui.dp(12),ui.dp(11));row.setMinimumHeight(ui.dp(54));row.setBackground(ui.ripple(0,12));row.setFocusable(true);row.setEnabled(enabled);row.setAlpha(enabled?1:.38f);
        var image=new ImageView(ui.a);image.setImageDrawable(new Ui.Glyph(icon,color==Ui.RED?Ui.RED:Ui.MUTED));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(image,new LinearLayout.LayoutParams(ui.dp(21),ui.dp(21)));
        var text=ui.col();text.setPadding(ui.dp(13),0,ui.dp(8),0);text.addView(ui.label(title,15,color));if(subtitle!=null&&!subtitle.isBlank()){ui.gap(text,4);text.addView(ui.label(subtitle,12,Ui.MUTED));}row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        if(checked!=null){var toggle=new Switch(ui.a);toggle.setChecked(checked);toggle.setClickable(false);toggle.setFocusable(false);toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);toggle.setThumbTintList(ColorStateList.valueOf(checked?Ui.ACCENT:Ui.MUTED));toggle.setTrackTintList(ColorStateList.valueOf(checked?0xff625781:0xff424957));row.addView(toggle);row.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Switch.class.getName());info.setCheckable(true);info.setChecked(checked);}});}
        else {var arrow=new ImageView(ui.a);arrow.setImageDrawable(new Ui.Glyph("chevron",Ui.MUTED));arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(arrow,new LinearLayout.LayoutParams(ui.dp(14),ui.dp(14)));}
        row.setContentDescription(title+(subtitle==null||subtitle.isBlank()?"":". "+subtitle));ui.click(row,()->{dialog.dismiss();action.run();});group.addView(row);return this;
    }
    Dialog show(){
        var w=dialog.getWindow();w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.55f);w.setGravity(Gravity.BOTTOM);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);dialog.show();
        w.setLayout(Math.min(ui.a.getResources().getDisplayMetrics().widthPixels-ui.dp(16),ui.dp(560)),WindowManager.LayoutParams.WRAP_CONTENT);var attrs=w.getAttributes();attrs.y=ui.dp(8);w.setAttributes(attrs);panel.setAlpha(0);panel.animate().alpha(1).setDuration(130).start();return dialog;
    }
}

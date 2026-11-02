package dev.tethium.mobile;

import android.app.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.File;

/** Local attachment preview with fit, pan, pinch zoom and double-tap reset. */
final class ImagePreview {
    static void show(Activity activity,Ui ui,File file){
        var options=new BitmapFactory.Options();options.inSampleSize=2;
        var bitmap=BitmapFactory.decodeFile(file.toString(),options);if(bitmap==null)return;
        var dialog=new Dialog(activity,R.style.TethiumTheme);var outer=ui.col();outer.setBackgroundColor(Ui.BG);
        var head=ui.row();var title=ui.title("Изображение");ui.pad(title,16,0);head.addView(title,new LinearLayout.LayoutParams(0,ui.dp(56),1));
        head.addView(ui.icon("close","Закрыть изображение",dialog::dismiss),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));outer.addView(head);
        var image=new Zoom(activity,bitmap);outer.addView(image,new LinearLayout.LayoutParams(-1,0,1));dialog.setContentView(outer);dialog.show();dialog.getWindow().setLayout(-1,-1);ui.inset(outer);
        dialog.setOnDismissListener(d->{image.setImageDrawable(null);bitmap.recycle();});
    }
    private static final class Zoom extends ImageView {
        final Bitmap bitmap;final Matrix transform=new Matrix();final ScaleGestureDetector scale;final GestureDetector gestures;
        float lastX,lastY,factor=1,fit=1;
        Zoom(Activity a,Bitmap bitmap){super(a);this.bitmap=bitmap;setImageBitmap(bitmap);setScaleType(ScaleType.MATRIX);setContentDescription("Изображение. Масштаб двумя пальцами, двойное касание — вписать.");
            scale=new ScaleGestureDetector(a,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector d){float next=Math.max(fit,Math.min(fit*10,factor*d.getScaleFactor()));transform.postScale(next/factor,next/factor,d.getFocusX(),d.getFocusY());factor=next;setImageMatrix(transform);return true;}});
            gestures=new GestureDetector(a,new GestureDetector.SimpleOnGestureListener(){public boolean onDown(android.view.MotionEvent e){return true;}public boolean onDoubleTap(android.view.MotionEvent e){reset();return true;}});
        }
        protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);reset();}
        void reset(){if(getWidth()==0||getHeight()==0)return;fit=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());factor=fit;transform.reset();transform.postScale(fit,fit);transform.postTranslate((getWidth()-bitmap.getWidth()*fit)/2,(getHeight()-bitmap.getHeight()*fit)/2);setImageMatrix(transform);}
        public boolean onTouchEvent(MotionEvent e){scale.onTouchEvent(e);gestures.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!scale.isInProgress()&&e.getPointerCount()==1){transform.postTranslate(e.getX()-lastX,e.getY()-lastY);setImageMatrix(transform);}lastX=e.getX();lastY=e.getY();if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;}
        public boolean performClick(){super.performClick();return true;}
    }
}

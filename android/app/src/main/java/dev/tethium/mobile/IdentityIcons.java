package dev.tethium.mobile;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** The build copies the desktop registry and its original HiDPI images. */
final class IdentityIcons {
    private final Context context;
    private final Properties names=new Properties();
    private final Map<String,Bitmap> images=new HashMap<>();
    IdentityIcons(Context context){this.context=context;try(var in=context.getAssets().open("identity/names.properties")){names.load(new InputStreamReader(in,StandardCharsets.UTF_8));}catch(IOException e){throw new IllegalStateException("Missing shared identities",e);}}
    /** Icons the projects keep in .tethium on the laptop, by project directory; they win over the built-in set. */
    private final Map<String,Bitmap> custom=new HashMap<>();
    private final Map<String,String> customVersions=new HashMap<>();
    private int generation;
    int generation(){return generation;}
    boolean customCurrent(String directory,String version){return Objects.equals(customVersions.get(key(directory)),version);}
    void custom(String directory,String version,Bitmap image){String key=key(directory);customVersions.put(key,version);if(image==null)custom.remove(key);else custom.put(key,image);generation++;}
    private static String key(String directory){return Objects.requireNonNullElse(directory,"").replace('\\','/').replaceAll("/+$","").toLowerCase(Locale.ROOT);}
    Drawable of(boolean project,String title,String directory){
        if(project){Bitmap own=custom.get(key(directory));if(own!=null){var drawable=new BitmapDrawable(context.getResources(),own);drawable.setFilterBitmap(true);return projectBadge(drawable);}}
        String name=Objects.requireNonNullElse(title,"");
        if(project){String path=Objects.requireNonNullElse(directory,"").replace('\\','/').replaceAll("/+$","");if(!path.isBlank())name=path.substring(path.lastIndexOf('/')+1);}
        String normalized=normalize(name);
        String asset=names.getProperty((project?"project.":"session.")+normalized);
        if(asset==null&&!project&&!normalized.equals("новая сессия")){
            // Unknown session names get the same generated identity as on the laptop.
            String key="hash:"+normalized;Bitmap generated=images.get(key);if(generated==null){generated=SessionIdenticon.render(normalized,88);images.put(key,generated);}
            var drawable=new BitmapDrawable(context.getResources(),generated);drawable.setFilterBitmap(true);return drawable;
        }
        if(asset==null)return project?projectBadge(new Ui.Glyph("project-folder",0xffffffff)):new Ui.Glyph("session-chat",0xffffffff);
        Bitmap image=images.get(asset);
        if(image==null)try(var in=context.getAssets().open("identity/"+asset+"-88.png")){image=BitmapFactory.decodeStream(in);images.put(asset,image);}catch(IOException ignored){}
        if(image==null)return project?projectBadge(new Ui.Glyph("project-folder",0xffffffff)):new Ui.Glyph("session-chat",0xffffffff);
        var drawable=new BitmapDrawable(context.getResources(),image);drawable.setFilterBitmap(true);return project?projectBadge(drawable):drawable;
    }
    /** A small folder in the lower-right corner marks project identities without borrowing a status color. */
    private static Drawable projectBadge(Drawable base){
        return new Drawable(){
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            public void draw(Canvas canvas){
                var bounds=getBounds();base.setBounds(bounds);base.draw(canvas);float size=Math.max(6,Math.min(bounds.width(),bounds.height())*.42f),x=bounds.right-size,y=bounds.bottom-size;
                paint.setColor(Ui.PANEL);canvas.drawRoundRect(x-1,y-1,bounds.right+1,bounds.bottom+1,size*.35f,size*.35f,paint);
                paint.setColor(Ui.MUTED);canvas.drawRoundRect(x,y+size*.25f,bounds.right,bounds.bottom,size*.2f,size*.2f,paint);canvas.drawRoundRect(x+size*.12f,y,x+size*.58f,y+size*.45f,size*.15f,size*.15f,paint);
            }
            public void setAlpha(int alpha){base.setAlpha(alpha);paint.setAlpha(alpha);}
            public void setColorFilter(ColorFilter filter){base.setColorFilter(filter);paint.setColorFilter(filter);}
            public int getOpacity(){return PixelFormat.TRANSLUCENT;}
            public int getIntrinsicWidth(){return base.getIntrinsicWidth();}
            public int getIntrinsicHeight(){return base.getIntrinsicHeight();}
        };
    }
    static String normalize(String text){
        // Android uses ICU regex: the desktop's (?U) Java flag crashes there.
        String value=Normalizer.normalize(Objects.requireNonNullElse(text,""),Normalizer.Form.NFKC);var result=new StringBuilder();boolean space=false;
        for(int i=0;i<value.length();){int cp=value.codePointAt(i);i+=Character.charCount(cp);if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0x85){space=result.length()>0;continue;}if(space)result.append(' ');result.appendCodePoint(cp);space=false;}
        return result.toString().toLowerCase(Locale.ROOT);
    }
}

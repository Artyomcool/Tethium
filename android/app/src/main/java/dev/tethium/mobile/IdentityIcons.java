package dev.tethium.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
    Drawable of(boolean project,String title,String directory){
        String name=Objects.requireNonNullElse(title,"");
        if(project){String path=Objects.requireNonNullElse(directory,"").replace('\\','/').replaceAll("/+$","");if(!path.isBlank())name=path.substring(path.lastIndexOf('/')+1);}
        String normalized=normalize(name);
        String asset=names.getProperty((project?"project.":"session.")+normalized);
        if(asset==null)return new Ui.Glyph(project?"project-folder":"session-chat",0xffffffff);
        Bitmap image=images.get(asset);
        if(image==null)try(var in=context.getAssets().open("identity/"+asset+"-88.png")){image=BitmapFactory.decodeStream(in);images.put(asset,image);}catch(IOException ignored){}
        if(image==null)return new Ui.Glyph(project?"project-folder":"session-chat",0xffffffff);
        var drawable=new BitmapDrawable(context.getResources(),image);drawable.setFilterBitmap(true);return drawable;
    }
    static String normalize(String text){
        // Android uses ICU regex: the desktop's (?U) Java flag crashes there.
        String value=Normalizer.normalize(Objects.requireNonNullElse(text,""),Normalizer.Form.NFKC);var result=new StringBuilder();boolean space=false;
        for(int i=0;i<value.length();){int cp=value.codePointAt(i);i+=Character.charCount(cp);if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0x85){space=result.length()>0;continue;}if(space)result.append(' ');result.appendCodePoint(cp);space=false;}
        return result.toString().toLowerCase(Locale.ROOT);
    }
}

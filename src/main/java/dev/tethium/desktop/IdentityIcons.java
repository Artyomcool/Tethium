package dev.tethium.desktop;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Image;
import java.awt.image.BaseMultiResolutionImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Stable, name-based identities shared by every project and future session. */
final class IdentityIcons {
    private static final String ROOT="identity/v3/";
    /** The untouched default title keeps the plain glyph: a generated pattern would make every fresh session look named. */
    static final String DEFAULT_SESSION="новая сессия";
    private static final Properties NAMES=loadNames();
    private static final Map<String, Icon> CACHE=new ConcurrentHashMap<>();
    private IdentityIcons() {}
    static String normalize(String title) {
        return Normalizer.normalize(Objects.requireNonNullElse(title,""),Normalizer.Form.NFKC)
                .strip().replaceAll("(?U)\\s+"," ").toLowerCase(Locale.ROOT);
    }
    static String asset(boolean project, String title, String directory) {
        String name=title;
        if(project) {
            String path=Objects.requireNonNullElse(directory,"").replace('\\','/').replaceAll("/+$","");
            if(!path.isBlank())name=path.substring(path.lastIndexOf('/')+1);
        }
        String normalized=normalize(name);
        if(!project)normalized=category(normalized);
        String mapped=NAMES.getProperty((project?"project.":"session.")+normalized);
        if(mapped!=null)return mapped;
        // Unknown names get a stable generated identity from their hash; only the untouched default session keeps the plain glyph.
        // Projects hash a prefixed key so a project and a session with the same name never share a pattern.
        if(!project&&normalized.equals(DEFAULT_SESSION))return "svg:session-chat";
        return "hash:"+(project?"project/":"")+normalized;
    }
    /** A title suffix after the first colon describes the concrete task, not the session role. */
    static String category(String normalized) {
        int colon=normalized.indexOf(':');
        return colon>0?normalized.substring(0,colon).strip():normalized;
    }
    static Icon of(boolean project,String title,String directory) {
        return CACHE.computeIfAbsent(asset(project,title,directory),IdentityIcons::load);
    }
    static String groupAsset(String title) { return NAMES.getProperty("group."+normalize(title),"group-general"); }
    static Icon group(String title) { return named(groupAsset(title)); }
    static Icon named(String asset) { return CACHE.computeIfAbsent(asset,IdentityIcons::load); }
    /** Project icons come from the project itself (.tethium/icon.png, served by the host); without one the hashed pattern is used. */
    private record ProjectIcon(String version,Icon icon) {}
    private static final Map<String,ProjectIcon> PROJECT_ICONS=new ConcurrentHashMap<>();
    static Icon ofProject(String id,String title,String directory) {
        var own=id==null?null:PROJECT_ICONS.get(id);
        return own!=null&&own.icon!=null?own.icon:of(true,title,directory);
    }
    /** True when the host reports a different icon version than the one already loaded (or none while the host has one). */
    static boolean projectIconStale(String id,String version) {
        var own=PROJECT_ICONS.get(id);
        if(version==null||version.isEmpty()){if(own!=null)PROJECT_ICONS.remove(id);return false;}
        return own==null||!own.version.equals(version);
    }
    /** Builds every sidebar size from the project's PNG; an empty payload records the version without an icon. */
    static void registerProjectIcon(String id,String version,String base64) {
        Icon icon=null;
        if(base64!=null&&!base64.isEmpty())try{
            var source=ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            if(source!=null){var images=new ArrayList<Image>();for(int size:new int[]{22,28,33,44,66,88})images.add(fit(source,size));icon=new ImageIcon(new BaseMultiResolutionImage(images.toArray(Image[]::new)));}
        }catch(Exception ignored){}
        PROJECT_ICONS.put(id,new ProjectIcon(version,icon));
    }
    /** Shrinks in halving steps before the final resample: one bicubic pass from a large original drops detail into aliasing noise. */
    private static Image fit(java.awt.image.BufferedImage source,int size) {
        double scale=Math.min((double)size/source.getWidth(),(double)size/source.getHeight());
        int w=Math.max(1,(int)Math.round(source.getWidth()*scale)),h=Math.max(1,(int)Math.round(source.getHeight()*scale));
        java.awt.image.BufferedImage current=source;
        while(current.getWidth()>=w*2&&current.getHeight()>=h*2){
            int cw=Math.max(w,current.getWidth()/2),ch=Math.max(h,current.getHeight()/2);
            current=resample(current,cw,ch,cw,ch,0,0);
        }
        return resample(current,w,h,size,size,(size-w)/2,(size-h)/2);
    }
    private static java.awt.image.BufferedImage resample(java.awt.image.BufferedImage source,int w,int h,int canvasW,int canvasH,int x,int y) {
        var target=new java.awt.image.BufferedImage(canvasW,canvasH,java.awt.image.BufferedImage.TYPE_INT_ARGB);var g=target.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(java.awt.RenderingHints.KEY_ALPHA_INTERPOLATION,java.awt.RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source,x,y,w,h,null);g.dispose();return target;
    }
    private static Properties loadNames() {
        var names=new Properties();
        try(var stream=IdentityIcons.class.getResourceAsStream(ROOT+"names.properties")) {
            if(stream==null)throw new IllegalStateException("Missing identity name registry");
            names.load(new InputStreamReader(stream,StandardCharsets.UTF_8));return names;
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private static Icon load(String name) {
        if(name.startsWith("svg:"))return UiIcons.of(name.substring(4),22);
        if(name.startsWith("hash:")){var generated=new ArrayList<Image>();for(int size:new int[]{22,28,33,44,66,88})generated.add(SessionIdenticon.render(name.substring(5),size));return new ImageIcon(new BaseMultiResolutionImage(generated.toArray(Image[]::new)));}
        var images=new ArrayList<Image>();
        for(int size:new int[]{22,28,33,44,66,88}) {
            try(var stream=IdentityIcons.class.getResourceAsStream(ROOT+name+"-"+size+".png")) {
                if(stream==null)throw new IllegalStateException("Missing identity icon: "+name);
                images.add(ImageIO.read(stream));
            } catch(java.io.IOException e){throw new IllegalStateException(e);}
        }
        return new ImageIcon(new BaseMultiResolutionImage(images.toArray(Image[]::new)));
    }
}

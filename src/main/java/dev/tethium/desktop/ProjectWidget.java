package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.host.HostClient;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Project widget metadata stays in state; pixels are fetched as a separately cached binary asset. */
final class ProjectWidget {
    private record Graphic(String version,Image image,double width,double height){}
    private final Map<String,Graphic> graphics=new ConcurrentHashMap<>();
    private final Set<String> pending=ConcurrentHashMap.newKeySet();
    private static String id(JsonNode project,String slot){return project.path("id").asText()+":"+slot;}
    void update(Iterable<JsonNode> projects,HostClient host,ExecutorService io,Runnable repaint){
        var live=new HashSet<String>();
        for(var project:projects)for(String slot:java.util.List.of("bottom","right")){
            var widget=project.path("widget").path(slot);String version=widget.path("version").asText(),id=id(project,slot);if(version.isEmpty())continue;live.add(id);
            var current=graphics.get(id);if(current!=null&&current.version.equals(version)||!pending.add(id+":"+version))continue;
            String path="/project-widget.png?project="+URLEncoder.encode(project.path("id").asText(),StandardCharsets.UTF_8)+"&slot="+slot;
            io.submit(()->{try{var image=ImageIO.read(new ByteArrayInputStream(host.bytes(path)));if(image!=null){graphics.put(id,new Graphic(version,image,image.getWidth(null),image.getHeight(null)));repaint.run();}}catch(Exception ignored){}finally{pending.remove(id+":"+version);}});
        }
        graphics.keySet().retainAll(live);pending.removeIf(key->{int colon=key.lastIndexOf(':');return colon>0&&!live.contains(key.substring(0,colon));});
    }
    private Graphic graphic(JsonNode project,String slot){if(project==null)return null;var value=graphics.get(id(project,slot));return value!=null&&value.version.equals(project.path("widget").path(slot).path("version").asText())?value:null;}
    boolean available(JsonNode project){return available(project,"bottom")||available(project,"right");}
    boolean available(JsonNode project,String slot){return graphic(project,slot)!=null;}
    int preferredWidth(JsonNode project,String slot,int height){var graphic=graphic(project,slot);return graphic==null?0:(int)Math.ceil(graphic.width*height/graphic.height);}
    void paint(Graphics graphics,JsonNode project,String slot,int x,int y,int width,int height){
        var graphic=graphic(project,slot);if(graphic==null||width<=0||height<=0)return;double scale=Math.min(width/graphic.width,height/graphic.height);var g=(Graphics2D)graphics.create();
        try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);int drawWidth=Math.max(1,(int)Math.round(graphic.width*scale)),drawHeight=Math.max(1,(int)Math.round(graphic.height*scale));g.drawImage(graphic.image,x,y+(height-drawHeight)/2,drawWidth,drawHeight,null);}finally{g.dispose();}
    }
}

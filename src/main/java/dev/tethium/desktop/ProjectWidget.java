package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.*;
import java.awt.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class ProjectWidget {
    private record Graphic(SVGDocument svg,Image fallback,double width,double height){}
    private final Map<String,Graphic> graphics=new HashMap<>();
    private static String key(JsonNode widget){return widget.path("version").asText()+(widget.hasNonNull("svg")?":svg":":png");}
    void update(Iterable<JsonNode> projects){
        var live=new HashSet<String>();
        for(var project:projects)for(String slot:java.util.List.of("bottom","right")){
            var widget=project.path("widget").path(slot);if(widget.path("version").asText().isEmpty())continue;String key=key(widget);live.add(key);
            if(!graphics.containsKey(key))try{
                String source=widget.path("svg").asText();
                if(!source.isEmpty()){
                    var svg=new SVGLoader().load(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),null,LoaderContext.builder().build());
                    if(svg!=null)graphics.put(key,new Graphic(svg,null,svg.size().width,svg.size().height));
                }else{
                    // Older host compatibility during GUI-first updates.
                    var image=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(widget.path("png").asText())));
                    if(image!=null)graphics.put(key,new Graphic(null,image,image.getWidth(),image.getHeight()));
                }
            }catch(Exception ignored){}
        }graphics.keySet().retainAll(live);
    }
    private Graphic graphic(JsonNode project,String slot){return project==null?null:graphics.get(key(project.path("widget").path(slot)));}
    boolean available(JsonNode project){return available(project,"bottom")||available(project,"right");}
    boolean available(JsonNode project,String slot){return graphic(project,slot)!=null;}
    int preferredWidth(JsonNode project,String slot,int height){var graphic=graphic(project,slot);return graphic==null?0:(int)Math.ceil(graphic.width*height/graphic.height);}
    void paint(Graphics graphics,JsonNode project,String slot,int x,int y,int width,int height){
        var graphic=graphic(project,slot);if(graphic==null||width<=0||height<=0)return;
        double scale=Math.min(width/graphic.width,height/graphic.height);
        var g=(Graphics2D)graphics.create();
        try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.translate(x,y+(height-graphic.height*scale)/2);g.scale(scale,scale);
            if(graphic.svg!=null)graphic.svg.render(null,g);
            else{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(graphic.fallback,0,0,null);}
        }finally{g.dispose();}
    }
}

package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import com.github.weisj.jsvg.parser.*;
import java.nio.file.*;
import java.io.*;
import java.awt.image.BufferedImage;
import java.util.*;
import javax.imageio.ImageIO;

/** The project owns the entire row; Tethium transports its rendered SVG unchanged. */
final class ProjectWidgets {
    static final String BOTTOM=".tethium-widget-bottom.svg", RIGHT=".tethium-widget-right.svg";
    private record Cached(long checked,long modified,long size,JsonNode value){}
    private final Map<Path,Cached> cache=new HashMap<>();
    synchronized JsonNode read(Path root){return Json.object("bottom",readFile(root.resolve(BOTTOM)),"right",readFile(root.resolve(RIGHT)));}
    private JsonNode readFile(Path file){
        long now=System.currentTimeMillis();var old=cache.get(file);
        if(old!=null&&now-old.checked<2000)return old.value;
        if(!Files.isRegularFile(file)){cache.remove(file);return Json.MAPPER.nullNode();}
        try{
            long modified=Files.getLastModifiedTime(file).toMillis(),size=Files.size(file);
            if(old!=null&&modified==old.modified&&size==old.size){cache.put(file,new Cached(now,modified,size,old.value));return old.value;}
            if(size>256_000)throw new IOException("Виджет больше 256 КБ");
            byte[] bytes=Files.readAllBytes(file);var value=render(bytes,modified);
            cache.put(file,new Cached(now,modified,size,value));return value;
        }catch(Exception error){
            var value=old==null?Json.object():old.value.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)value).put("error","Не удалось обновить "+file.getFileName()+": "+error.getMessage());
            cache.put(file,new Cached(now,-1,-1,value));return value;
        }
    }
    static JsonNode render(byte[] bytes,long modified)throws Exception{
        // No external resources: the same self-contained row works on the remote client too.
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        var xml=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        if(!xml.getDocumentElement().getTagName().equals("svg"))throw new IOException("Ожидается SVG");
        var nodes=xml.getElementsByTagName("*");
        for(int i=0;i<nodes.getLength();i++){var node=(org.w3c.dom.Element)nodes.item(i);
            if(node.getTagName().equals("script"))throw new IOException("Скрипт обновляет SVG снаружи, JS внутри не исполняется");
            for(int a=0;a<node.getAttributes().getLength();a++){var attr=node.getAttributes().item(a);String value=attr.getNodeValue().strip();if(attr.getNodeName().endsWith("href")&&!value.startsWith("#")&&!value.startsWith("data:image/"))throw new IOException("Внешние ресурсы в SVG не поддерживаются");}
        }
        String source=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        var urls=java.util.regex.Pattern.compile("(?i)url\\(\\s*['\"]?([^)'\"\\s]+)").matcher(source);while(urls.find())if(!urls.group(1).startsWith("#")&&!urls.group(1).startsWith("data:image/"))throw new IOException("Внешние CSS-ресурсы не поддерживаются");
        if(source.contains("@import"))throw new IOException("Внешние CSS-ресурсы не поддерживаются");
        var svg=new SVGLoader().load(new ByteArrayInputStream(bytes),null,LoaderContext.builder().build());
        if(svg==null)throw new IOException("Некорректный SVG");var size=svg.size();
        if(!Float.isFinite(size.width)||!Float.isFinite(size.height)||size.width<=0||size.height<=0||size.width>4096||size.height>256)throw new IOException("Укажите конечные width/height SVG (до 4096 × 256)");
        double scale=Math.min(3,Math.min(72.0/size.height,1536.0/size.width));
        var image=new BufferedImage(Math.max(1,(int)Math.ceil(size.width*scale)),Math.max(1,(int)Math.ceil(size.height*scale)),BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS,java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);g.scale(scale,scale);svg.render(null,g);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);
        String description="";var titles=xml.getElementsByTagName("title");if(titles.getLength()>0)description=titles.item(0).getTextContent();var desc=xml.getElementsByTagName("desc");if(desc.getLength()>0)description+="\n"+desc.item(0).getTextContent();
        return Json.object("svg",source,"png",Base64.getEncoder().encodeToString(out.toByteArray()),"width",size.width,"height",size.height,"description",description,"updatedAt",modified,"version","svg2-"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}

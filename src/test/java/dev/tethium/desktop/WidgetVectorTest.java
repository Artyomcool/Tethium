package dev.tethium.desktop;

import dev.tethium.json.Json;
import dev.tethium.host.HostClient;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WidgetVectorTest {
    @Test void drawsSeparatelyFetchedBinaryWidgetAtEachDeviceScale()throws Exception{
        var source=new BufferedImage(120,120,BufferedImage.TYPE_INT_ARGB);var sg=source.createGraphics();sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);sg.setColor(java.awt.Color.WHITE);sg.drawOval(12,12,96,96);sg.dispose();var encoded=new ByteArrayOutputStream();ImageIO.write(source,"png",encoded);
        var project=Json.object("id","p1","widget",Json.object("right",Json.object("version","v2","width",40,"height",40)));var ready=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        var widget=new ProjectWidget();try(var host=new HostClient(Json.object()){@Override public byte[] bytes(String path){assertEquals("/project-widget.png?project=p1&slot=right",path);return encoded.toByteArray();}}){widget.update(List.of(project),host,executor,ready::countDown);assertTrue(ready.await(2,TimeUnit.SECONDS));}finally{executor.shutdownNow();}
        assertTrue(widget.available(project,"right"));
        for(double scale:new double[]{1,1.25,2}){
            var image=new BufferedImage(80,80,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.scale(scale,scale);widget.paint(g,project,"right",1,1,26,26);g.dispose();
            int solid=0,antialiased=0;for(int y=0;y<80;y++)for(int x=0;x<80;x++){int alpha=image.getRGB(x,y)>>>24;if(alpha==255)solid++;else if(alpha>0)antialiased++;}
            assertTrue(solid+antialiased>0,"No content at scale "+scale);assertTrue(antialiased>0,"Jagged edges at scale "+scale);
        }
    }
}

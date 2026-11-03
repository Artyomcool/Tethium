package dev.tethium.desktop;

import dev.tethium.json.Json;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WidgetVectorTest {
    @Test void drawsSvgWithoutPngAtEachDeviceScale(){
        var project=Json.object("widget",Json.object("right",Json.object("version","v2","png","invalid-on-purpose","svg","<svg xmlns='http://www.w3.org/2000/svg' width='40' height='40'><circle cx='20' cy='20' r='16' fill='none' stroke='white' stroke-width='2'/><text x='10' y='24' font-size='12' fill='white'>37</text></svg>")));
        var widget=new ProjectWidget();widget.update(List.of(project));assertTrue(widget.available(project,"right"));
        for(double scale:new double[]{1,1.25,2}){
            var image=new BufferedImage(80,80,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.scale(scale,scale);widget.paint(g,project,"right",1,1,26,26);g.dispose();
            int solid=0,antialiased=0;for(int y=0;y<80;y++)for(int x=0;x<80;x++){int alpha=image.getRGB(x,y)>>>24;if(alpha==255)solid++;else if(alpha>0)antialiased++;}
            assertTrue(solid>0,"No content at scale "+scale);assertTrue(antialiased>0,"Jagged edges at scale "+scale);
        }
    }
}

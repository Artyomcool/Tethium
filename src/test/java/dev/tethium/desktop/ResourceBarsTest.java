package dev.tethium.desktop;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ResourceBarsTest {
    @Test void currentCpuUsesWholeMachineScaleIndependentlyOfHistoricalShare(){
        var usage=Json.object("cpu",Json.object("totalNanos",1000,"sessions",Json.object("s",900)));
        var controls=Json.object("supported",true,"sessions",Json.object("s",Json.object("cpuPercent",12.5)));
        var share=ResourceBars.share(usage,Json.object(),controls,"s",false);
        assertEquals(.9,share.cpuFraction());assertEquals(.125,share.currentCpuFraction());
        var image=new java.awt.image.BufferedImage(100,ResourceBars.HEIGHT,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();ResourceBars.paint(g,0,ResourceBars.HEIGHT,100,share);g.dispose();
        assertEquals(ResourceBars.CPU.getRGB(),image.getRGB(89,3));assertEquals(Theme.BORDER.getRGB(),image.getRGB(90,3));
        assertEquals(ResourceBars.CPU_NOW.getRGB(),image.getRGB(12,5));assertEquals(Theme.BORDER.getRGB(),image.getRGB(13,5));
        assertEquals(1,ResourceBars.lane(5,ResourceBars.HEIGHT));assertEquals(2,ResourceBars.lane(7,ResourceBars.HEIGHT));
        assertTrue(ResourceBars.tooltip(1,usage,Json.object(),controls,"s",false).contains("12,50%"));
        assertTrue(Double.isNaN(ResourceBars.share(usage,"s",false).cpuNow()));
    }
    @Test void sessionsAndProjectsUseTheGlobalTotals(){
        var data=Json.object("total",1000,"projects",Json.object("p",400),"sessions",Json.object("s",100),"cpu",Json.object("totalNanos",1000,"projects",Json.object("p",200),"sessions",Json.object("s",100)));
        var machine=Json.object("memory",Json.object("totalBytes",1000,"projects",Json.object("p",Json.object("bytes",400)),"sessions",Json.object("s",Json.object("bytes",100))));
        var project=ResourceBars.share(data,machine,"p",true);assertEquals(.4,project.tokenFraction());assertEquals(.2,project.cpuFraction());assertEquals(.4,project.memoryFraction());
        var session=ResourceBars.share(data,machine,"s",false);assertEquals(.1,session.tokenFraction());assertEquals(.1,session.cpuFraction());assertEquals(.1,session.memoryFraction());
        var empty=ResourceBars.share(Json.object(),"s",false);assertEquals(0,empty.tokenFraction());assertEquals(0,empty.memoryFraction());
        assertTrue(ResourceBars.tooltip(2,data,machine,Json.object(),"s",false).contains("10,00%"));
        assertTrue(ResourceBars.tooltip(2,data,Json.object(),Json.object(),"s",false).contains("ещё не получен"));
    }
}

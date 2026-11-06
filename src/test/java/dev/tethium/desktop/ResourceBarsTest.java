package dev.tethium.desktop;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ResourceBarsTest {
    @Test void currentCpuIsMeasuredAgainstTheSessionCapAndCpuTimeAgainstTheBusiestPeer(){
        var usage=Json.object("cpu",Json.object("totalNanos",1350,"sessions",Json.object("s",900,"t",450)));
        var controls=Json.object("supported",true,"sessions",Json.object("t",Json.object("cpuPercent",12.5),"s",Json.object("cpuPercent",12.5,"effectiveCap",50)));
        var share=ResourceBars.share(usage,Json.object(),controls,"t",false);
        assertEquals(.5,share.cpuFraction(),"half of the busiest session");assertEquals(1,ResourceBars.share(usage,"s",false).cpuFraction());
        assertEquals(.125,share.currentCpuFraction(),"no cap means the whole machine");assertEquals(.25,ResourceBars.share(usage,Json.object(),controls,"s",false).currentCpuFraction(),"12.5% of the machine is a quarter of a 50% cap");
        var image=new java.awt.image.BufferedImage(100,ResourceBars.HEIGHT,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();ResourceBars.paint(g,0,ResourceBars.HEIGHT,100,share);g.dispose();
        assertEquals(46,ResourceBars.leftWidth(100));
        assertEquals(ResourceBars.CPU.getRGB(),image.getRGB(76,0));assertEquals(Theme.BORDER.getRGB(),image.getRGB(77,0));
        assertEquals(ResourceBars.CPU_NOW.getRGB(),image.getRGB(59,2));assertEquals(Theme.BORDER.getRGB(),image.getRGB(60,2));
        assertEquals(Theme.BORDER.getRGB(),image.getRGB(54,4),"RAM lane is empty but drawn");assertEquals(0,image.getRGB(50,0)&0xffffff,"gap between the groups stays blank");
        assertEquals(0,ResourceBars.lane(10,3,0,100,ResourceBars.HEIGHT));assertEquals(-1,ResourceBars.lane(48,3,0,100,ResourceBars.HEIGHT));
        assertEquals(1,ResourceBars.lane(60,2,0,100,ResourceBars.HEIGHT));assertEquals(2,ResourceBars.lane(60,4,0,100,ResourceBars.HEIGHT));assertEquals(-1,ResourceBars.lane(60,6,0,100,ResourceBars.HEIGHT));
        var tooltip=ResourceBars.tooltip(1,usage,Json.object(),controls,"s",false);assertTrue(tooltip.contains("12,50%")&&tooltip.contains("лимит 50%")&&tooltip.contains("25% от лимита")&&tooltip.contains("100% от наибольшей сессии"),tooltip);
        assertTrue(Double.isNaN(ResourceBars.share(usage,"s",false).cpuNow()));
    }
    @Test void ramIsMeasuredAgainstPhysicalMemoryWhileTooltipsKeepTheGlobalShare(){
        var data=Json.object("total",1000,"projects",Json.object("p",400,"q",800),"sessions",Json.object("s",100,"t",400),"cpu",Json.object("totalNanos",1000,"projects",Json.object("p",200,"q",300),"sessions",Json.object("s",100,"t",50)));
        var machine=Json.object("memoryTotal",2000,"memory",Json.object("totalBytes",1000,"projects",Json.object("p",Json.object("bytes",400),"q",Json.object("bytes",500)),"sessions",Json.object("s",Json.object("bytes",100),"t",Json.object("bytes",25))));
        var project=ResourceBars.share(data,machine,"p",true);assertEquals(.4,project.tokenFraction());assertEquals(.5,project.codexFraction());assertEquals(200/300.0,project.cpuFraction());assertEquals(.2,project.memoryFraction());
        var session=ResourceBars.share(data,machine,"s",false);assertEquals(.25,session.codexFraction());assertEquals(1,session.cpuFraction());assertEquals(.05,session.memoryFraction());assertEquals(0,session.claudeFraction());
        var unknown=ResourceBars.share(data,Json.object("memory",machine.path("memory")),"s",false);assertEquals(0,unknown.memoryFraction(),"no physical size means an empty bar");assertTrue(unknown.hasUsage());
        var empty=ResourceBars.share(Json.object(),"s",false);assertEquals(0,empty.codexFraction());assertEquals(0,empty.memoryFraction());assertFalse(empty.hasUsage());
        var tooltip=ResourceBars.tooltip(2,data,machine,Json.object(),"s",false);assertTrue(tooltip.contains("10,00%")&&tooltip.contains("5,00% от RAM компьютера"),tooltip);
        assertTrue(ResourceBars.tooltip(2,data,Json.object(),Json.object(),"s",false).contains("ещё не получен"));
        assertTrue(ResourceBars.tooltip(0,data,machine,Json.object(),"p",true).contains("50% от наибольшего проекта"));
    }
    @Test void codexAndClaudeTokensGetTheirOwnLanesEachScaledToItsOwnLargestPeer(){
        var data=Json.object("total",1200,"sessions",Json.object("s",500,"u",600,"v",100),"executors",Json.object("codex",Json.object("total",900,"sessions",Json.object("s",300,"u",600)),"claude",Json.object("total",300,"sessions",Json.object("s",200,"v",100))));
        var share=ResourceBars.share(data,"s",false);assertEquals(300,share.codexTokens());assertEquals(.5,share.codexFraction());assertEquals(200,share.claudeTokens());assertEquals(1,share.claudeFraction());
        var image=new java.awt.image.BufferedImage(100,ResourceBars.HEIGHT,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();ResourceBars.paint(g,0,ResourceBars.HEIGHT,100,share);g.dispose();
        assertEquals(ResourceBars.TOKENS.getRGB(),image.getRGB(22,0));assertEquals(Theme.BORDER.getRGB(),image.getRGB(23,0));
        assertEquals(ResourceBars.TOKENS_CLAUDE.getRGB(),image.getRGB(45,4));assertEquals(0,image.getRGB(10,2)&0xffffff,"rows between the two token lanes stay blank");
        var tooltip=ResourceBars.tooltip(0,data,Json.object(),Json.object(),"s",false);assertTrue(tooltip.contains("Codex")&&tooltip.contains("300")&&tooltip.contains("Claude")&&tooltip.contains("200")&&tooltip.contains("100% от наибольшей сессии"),tooltip);
        var legacy=ResourceBars.share(Json.object("total",1000,"sessions",Json.object("s",500,"u",250)),"s",false);assertEquals(500,legacy.codexTokens());assertEquals(1,legacy.codexFraction());assertEquals(0,legacy.claudeTokens());
        var legacyImage=new java.awt.image.BufferedImage(100,ResourceBars.HEIGHT,java.awt.image.BufferedImage.TYPE_INT_RGB);g=legacyImage.createGraphics();ResourceBars.paint(g,0,ResourceBars.HEIGHT,100,legacy);g.dispose();
        assertEquals(ResourceBars.TOKENS.getRGB(),legacyImage.getRGB(45,0));assertEquals(Theme.BORDER.getRGB(),legacyImage.getRGB(0,4));
    }
}

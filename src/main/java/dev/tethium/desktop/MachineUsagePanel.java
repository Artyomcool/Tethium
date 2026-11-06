package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.Locale;

/** One line: resource-colored Tethium numbers plus amber numbers for the rest of the machine. */
final class MachineUsagePanel extends JComponent {
    private JsonNode value=Json.object();
    MachineUsagePanel(){
        setPreferredSize(new Dimension(0,17));setMinimumSize(new Dimension(0,17));
        setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN,12f));setToolTipText("CPU / RAM");
    }
    void update(JsonNode value){this.value=value;repaint();}
    @Override public String getToolTipText(MouseEvent event){
        boolean cpu=event.getX()<cpuWidth(getFontMetrics(getFont()))+Theme.GAP;String body;
        if(cpu)body="<b>CPU · загрузка всех ядер</b><br><font color='#69d9ae'>Tethium: "+percent(value.path("cpuOurs"))+"</font> · <font color='#e4bd82'>остальное: "+percent(value.path("cpuOther"))+"</font><br>Всего: "+percent(value.path("cpuTotal"))+" из 100%";
        else body="<b>RAM · физическая память</b><br><font color='#c3a0f5'>Tethium: "+(partial()?"не менее ":"")+bytes(value.path("memoryOurs"))+"</font><br><font color='#e4bd82'>Остальное: "+(partial()?"не более ":"")+bytes(value.path("memoryOther"))+"</font><br>Занято "+bytes(value.path("memoryUsed"))+" из "+bytes(value.path("memoryTotal"))
            +(partial()?"<br>Пока без замера: "+value.path("memoryUnmeasuredProcesses").asInt()+" процессов Tethium; их память включена в «остальное».":"");
        return "<html>"+body+"<br>Цветное число — Tethium, янтарное после «+» — остальная система.<br>Tethium: сессии, их процессы, ядро и окна приложения."
            +(cpu?"":"<br>Общие страницы памяти входят в остальную систему.")+"<br>Замер: "+ResourceBars.time(value.path("updatedAt").asLong(),true)+"</html>";
    }
    private static String percent(JsonNode n){return n.isNumber()?String.format(Locale.forLanguageTag("ru"),"%.1f%%",n.asDouble()):"—";}
    private static String bytes(JsonNode n){return n.isNumber()?ResourceBars.bytes(n.asLong()):"—";}
    private boolean partial(){return value.path("memoryUnmeasuredProcesses").asInt()>0;}
    private int cpuWidth(FontMetrics fm){
        int ramMinimum=fm.stringWidth("RAM "+(partial()?"≥":"")+compact(value.path("memoryOurs"),false,true)+"+"+(partial()?"≤":"")+compact(value.path("memoryOther"),false,true)+"ГБ");
        return Math.max(0,Math.min(getWidth()/2-5,getWidth()-Theme.GAP-ramMinimum));
    }
    @Override protected void paintComponent(Graphics graphics){
        var g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(getFont());var fm=g.getFontMetrics();int baseline=(getHeight()-fm.getHeight())/2+fm.getAscent();
        int cpuWidth=cpuWidth(fm);
        paintGroup(g,0,cpuWidth,baseline,"CPU",value.path("cpuOurs"),value.path("cpuOther"),true,ResourceBars.CPU_NOW);
        int ramX=cpuWidth+Theme.GAP;paintGroup(g,ramX,getWidth()-ramX,baseline,"RAM",value.path("memoryOurs"),value.path("memoryOther"),false,ResourceBars.RAM);g.dispose();
    }
    private void paintGroup(Graphics2D g,int x,int width,int baseline,String label,JsonNode ours,JsonNode other,boolean cpu,Color color){
        String oursPrefix=!cpu&&partial()&&ours.isNumber()?"≥":"",otherPrefix=!cpu&&partial()&&other.isNumber()?"≤":"";
        String left=oursPrefix+compact(ours,cpu,false),right=otherPrefix+compact(other,cpu,false),unit=cpu?"%":"ГБ";var fm=g.getFontMetrics();
        if(fm.stringWidth(label+" "+left+"+"+right+unit)>width){left=oursPrefix+compact(ours,cpu,true);right=otherPrefix+compact(other,cpu,true);}
        Shape clip=g.getClip();g.clipRect(x,0,Math.max(0,width),getHeight());
        g.setColor(Theme.TEXT);g.drawString(label,x,baseline);x+=fm.stringWidth(label+" ");
        g.setColor(color);g.drawString(left,x,baseline);x+=fm.stringWidth(left);
        g.setColor(Theme.MUTED);g.drawString("+",x,baseline);x+=fm.stringWidth("+");
        g.setColor(Theme.AMBER);g.drawString(right,x,baseline);x+=fm.stringWidth(right);
        g.setColor(Theme.MUTED);g.drawString(unit,x,baseline);g.setClip(clip);
    }
    private static String compact(JsonNode n,boolean cpu,boolean rounded){return !n.isNumber()?"—":String.format(Locale.forLanguageTag("ru"),cpu||rounded?"%.0f":"%.1f",n.asDouble()/(cpu?1:1073741824));}
}

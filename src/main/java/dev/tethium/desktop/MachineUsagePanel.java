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
        addMouseListener(new java.awt.event.MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(mediaFull()&&e.getX()>=getWidth()-DOT-4)openMediaFolder();}});
    }
    private static final int DOT=7;
    /** Saved chat images past the limit: nothing is deleted by itself, the dot asks for a manual clean-up. */
    private boolean mediaFull(){return value.path("mediaBytes").asLong(0)>=value.path("mediaLimit").asLong(Long.MAX_VALUE);}
    private void openMediaFolder(){
        var folder=new java.io.File(value.path("mediaFolder").asText(""));
        if(folder.isDirectory())try{Desktop.getDesktop().open(folder);}catch(Exception ignored){}
    }
    void update(JsonNode value){this.value=value;repaint();}
    @Override public String getToolTipText(MouseEvent event){
        if(mediaFull()&&event.getX()>=getWidth()-DOT-4)return "<html><b>Картинки чатов: "+bytes(value.path("mediaBytes"))+"</b> (порог "+bytes(value.path("mediaLimit"))+")<br>Сами они не удаляются. Почистите папку вручную или попросите агента.<br>"+Theme.escape(value.path("mediaFolder").asText(""))+(new java.io.File(value.path("mediaFolder").asText("")).isDirectory()?"<br>Щелчок открывает папку.":"")+"</html>";
        if(hasDisk()&&event.getX()>=diskX(getFontMetrics(getFont()))-Theme.GAP/2)return diskTooltip();
        boolean cpu=event.getX()<cpuWidth(getFontMetrics(getFont()))+Theme.GAP;String body;
        if(cpu)body="<b>CPU · загрузка всех ядер</b><br><font color='#69d9ae'>Tethium: "+percent(value.path("cpuOurs"))+"</font> · <font color='#e4bd82'>остальное: "+percent(value.path("cpuOther"))+"</font><br>Всего: "+percent(value.path("cpuTotal"))+" из 100%";
        else body="<b>RAM · физическая память</b><br><font color='#c3a0f5'>Tethium: "+(partial()?"не менее ":"")+bytes(value.path("memoryOurs"))+"</font><br><font color='#e4bd82'>Остальное: "+(partial()?"не более ":"")+bytes(value.path("memoryOther"))+"</font><br>Занято "+bytes(value.path("memoryUsed"))+" из "+bytes(value.path("memoryTotal"))
            +(partial()?"<br>Пока без замера: "+value.path("memoryUnmeasuredProcesses").asInt()+" процессов Tethium; их память включена в «остальное».":"");
        return "<html>"+body+"<br>Цветное число — Tethium, янтарное после «+» — остальная система.<br>Tethium: сессии, их процессы, ядро и окна приложения."
            +(cpu?"":"<br>Общие страницы памяти входят в остальную систему.")+"<br>Замер: "+ResourceBars.time(value.path("updatedAt").asLong(),true)+"</html>";
    }
    private boolean hasDisk(){return value.path("disks").size()>0;}
    private static double used(JsonNode disk){double total=disk.path("total").asDouble();return total<=0?0:100*(1-disk.path("free").asDouble()/total);}
    private static double freeGb(JsonNode disk){return disk.path("free").asDouble()/1073741824;}
    /** Free space: green with more than 100 GB left, red under 20 GB. */
    private static Color diskColor(double free){return free<20?Theme.RED:free>100?Theme.GREEN:Theme.TEXT;}
    /** The drive with the least free space is the one that can run out. */
    private JsonNode tightest(){JsonNode best=null;for(var disk:value.path("disks"))if(best==null||disk.path("free").asLong()<best.path("free").asLong())best=disk;return best;}
    private String diskText(){var disk=tightest();return disk==null?"":String.format(Locale.forLanguageTag("ru"),"%.0f",freeGb(disk));}
    private int dotSpace(){return mediaFull()?DOT+4:0;}
    /** Where the disk group starts: it keeps its full width at the right end, before the media dot. */
    private int diskX(FontMetrics fm){return hasDisk()?getWidth()-dotSpace()-fm.stringWidth("Диск "+diskText()+" ГБ"):getWidth()-dotSpace()+Theme.GAP;}
    private String diskTooltip(){
        var body=new StringBuilder("<html><b>Диск · свободно</b>");
        for(var disk:value.path("disks")){
            String color=String.format("#%06x",diskColor(freeGb(disk)).getRGB()&0xffffff);
            String role=disk.path("data").asBoolean()?(disk.path("projects").asInt()>0?"данные Tethium и проекты":"данные Tethium"):"проекты";
            body.append("<br>").append(Theme.escape(disk.path("root").asText())).append(" <font color='").append(color).append("'>").append(bytes(disk.path("free"))).append("</font> из ").append(bytes(disk.path("total")))
                .append(String.format(Locale.forLanguageTag("ru")," · занято %.0f%%",used(disk))).append(" · ").append(role);
        }
        return body.append("<br>В строке — диск, где места меньше всего. Зелёный — больше 100 ГБ, красный — меньше 20 ГБ.</html>").toString();
    }
    private static String percent(JsonNode n){return n.isNumber()?String.format(Locale.forLanguageTag("ru"),"%.1f%%",n.asDouble()):"—";}
    private static String bytes(JsonNode n){return n.isNumber()?ResourceBars.bytes(n.asLong()):"—";}
    private boolean partial(){return value.path("memoryUnmeasuredProcesses").asInt()>0;}
    private int cpuWidth(FontMetrics fm){
        int ramMinimum=fm.stringWidth("RAM "+(partial()?"≥":"")+compact(value.path("memoryOurs"),false,true)+"+"+(partial()?"≤":"")+compact(value.path("memoryOther"),false,true)+"ГБ");
        int right=diskX(fm)-Theme.GAP;
        return Math.max(0,Math.min(right/2-5,right-Theme.GAP-ramMinimum));
    }
    @Override protected void paintComponent(Graphics graphics){
        var g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(getFont());var fm=g.getFontMetrics();int baseline=(getHeight()-fm.getHeight())/2+fm.getAscent();
        int cpuWidth=cpuWidth(fm);
        if(mediaFull()){g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(Theme.RED);g.fillOval(getWidth()-DOT-1,(getHeight()-DOT)/2,DOT,DOT);}
        paintGroup(g,0,cpuWidth,baseline,"CPU",value.path("cpuOurs"),value.path("cpuOther"),true,ResourceBars.CPU_NOW);
        int diskX=diskX(fm);
        if(hasDisk()){int x=diskX;g.setColor(Theme.TEXT);g.drawString("Диск ",x,baseline);x+=fm.stringWidth("Диск ");String free=diskText();g.setColor(diskColor(freeGb(tightest())));g.drawString(free,x,baseline);x+=fm.stringWidth(free);g.setColor(Theme.MUTED);g.drawString(" ГБ",x,baseline);}
        int ramX=cpuWidth+Theme.GAP;paintGroup(g,ramX,diskX-Theme.GAP-ramX,baseline,"RAM",value.path("memoryOurs"),value.path("memoryOther"),false,ResourceBars.RAM);g.dispose();
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

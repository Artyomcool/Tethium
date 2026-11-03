package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Every row uses global totals; RAM is live, CPU and tokens are cumulative. */
final class ResourceBars extends JPanel {
    static final int HEIGHT=9;
    static final Color TOKENS=new Color(0x9bbcff),CPU=new Color(0x4e967e),CPU_NOW=new Color(0x80f5b5),RAM=new Color(0xc3a0f5);
    private static final Locale RU=Locale.forLanguageTag("ru");
    record Share(long tokens,long tokenTotal,long nanos,long cpuTotal,long memory,long memoryTotal,double cpuNow){
        double tokenFraction(){return fraction(tokens,tokenTotal);}
        double cpuFraction(){return fraction(nanos,cpuTotal);}
        private static double fraction(long own,long total){return total<=0?0:Math.clamp((double)own/total,0,1);}
        double memoryFraction(){return fraction(memory,memoryTotal);}
        double currentCpuFraction(){return Double.isFinite(cpuNow)?Math.clamp(cpuNow/100,0,1):0;}
        boolean hasUsage(){return tokenFraction()>0||cpuFraction()>0||memoryFraction()>0||currentCpuFraction()>0;}
    }
    ResourceBars(){super(new BorderLayout(Theme.GAP,0));setBorder(BorderFactory.createEmptyBorder(0,0,HEIGHT+1,0));}
    static Share share(JsonNode usage,String id,boolean project){return share(usage,Json.object(),id,project);}
    static Share share(JsonNode usage,JsonNode machine,String id,boolean project){
        return share(usage,machine,Json.object(),id,project);
    }
    static Share share(JsonNode usage,JsonNode machine,JsonNode controls,String id,boolean project){
        String group=project?"projects":"sessions";var cpu=usage.path("cpu");var memory=machine.path("memory");
        var current=controls.path(group).path(id).path("cpuPercent");
        return new Share(usage.path(group).path(id).asLong(),usage.path("total").asLong(),cpu.path(group).path(id).asLong(),cpu.path("totalNanos").asLong(),
            memory.path(group).path(id).path("bytes").asLong(),memory.path("totalBytes").asLong(),controls.path("supported").asBoolean()&&current.isNumber()?current.asDouble():Double.NaN);
    }
    static void paint(Graphics g,int x,int bottom,int width,Share share){
        if(!share.hasUsage())return;
        paintBar(g,x,bottom-HEIGHT,width,share.tokenFraction(),TOKENS);
        paintBar(g,x,bottom-HEIGHT+3,width,share.cpuFraction(),CPU);
        paintBar(g,x,bottom-HEIGHT+5,width,1,share.currentCpuFraction(),CPU_NOW);
        paintBar(g,x,bottom-HEIGHT+7,width,share.memoryFraction(),RAM);
    }
    static int lane(int y,int bottom){int offset=y-(bottom-HEIGHT);return offset<0||offset>=HEIGHT?-1:offset<3?0:offset<7?1:2;}
    static String tooltip(int lane,JsonNode usage,JsonNode machine,JsonNode controls,String id,boolean project){
        var share=share(usage,machine,controls,id,project);String group=project?"projects":"sessions";
        String subject=project?"Проект":"Сессия",body;
        if(lane==0){
            body=heading(TOKENS,"Токены · "+subject)+String.format(RU,"<br><b>%,d</b> · %.2f%% от общего расхода<br>Все сессии: %,d токенов",share.tokens,100*share.tokenFraction(),share.tokenTotal)
                +"<br>"+period(usage.path("start").asLong())+"<br>Учтённые токены разговоров, включая кэш; это не остаток квоты.";
        }else if(lane==1){
            var control=controls.path(group).path(id);
            body=heading(CPU_NOW,"CPU · "+subject)+"<br>За период: <b>"+duration(share.nanos)+"</b> · "+percent(100*share.cpuFraction())+" от общего CPU-времени"
                +"<br>Все сессии: "+duration(share.cpuTotal)+"<br>"+period(Math.max(usage.path("start").asLong(),usage.path("cpu").path("observedSince").asLong()));
            if(Double.isFinite(share.cpuNow))body+="<br>Сейчас: <b>"+percent(share.cpuNow)+"</b> мощности компьютера · лимит "+control.path("effectiveCap").asInt(100)+"%";
            else body+="<br>Текущая загрузка пока недоступна.";
            body+="<br>Верх полосы — накопленная доля; яркий низ — загрузка сейчас.<br>100% текущей загрузки = все ядра компьютера.";
        }else{
            var memory=machine.path("memory");var own=memory.path(group).path(id);
            body=heading(RAM,"RAM сейчас · "+subject);
            if(!memory.isObject())return "<html>"+body+"<br>Замер памяти ещё не получен.</html>";
            body+="<br><b>"+bytes(share.memory)+"</b> · "+percent(100*share.memoryFraction())+" памяти всех сессий"
                +"<br>Все сессии: "+bytes(share.memoryTotal)+" · процессов здесь: "+own.path("processes").asInt();
            long physical=machine.path("memoryTotal").asLong();
            if(physical>0)body+="<br>От RAM компьютера: "+percent(100.0*share.memory/physical)+" из "+bytes(physical);
            body+="<br>Личная физическая память процессов; ядро и GUI — отдельно.";
            if(memory.path("unmeasuredProcesses").asInt()>0)body+="<br>Неполный замер: без данных "+own.path("unmeasuredProcesses").asInt()+" процессов здесь, "+memory.path("unmeasuredProcesses").asInt()+" всего.";
            body+="<br>Замер: "+time(machine.path("updatedAt").asLong(),true);
        }
        return "<html>"+body+"</html>";
    }
    static String bytes(long bytes){
        if(bytes<1024)return String.format(RU,"%,d Б",bytes);
        if(bytes<1048576)return String.format(RU,"%.1f КиБ",bytes/1024.0);
        if(bytes<1073741824)return String.format(RU,"%.1f МиБ",bytes/1048576.0);
        return String.format(RU,"%.2f ГиБ",bytes/1073741824.0);
    }
    static String percent(double value){return String.format(RU,"%.2f%%",value);}
    static String time(long millis,boolean seconds){return millis<=0?"—":DateTimeFormatter.ofPattern(seconds?"dd.MM HH:mm:ss":"dd.MM HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));}
    private static String period(long start){return start<=0?"С начала учёта":"Период: с "+time(start,false);}
    private static String heading(Color color,String title){return "<b><font color='#"+String.format("%06x",color.getRGB()&0xffffff)+"'>"+title+"</font></b>";}
    private static String duration(long nanos){long seconds=nanos/1_000_000_000;return String.format(RU,"%d ч %02d мин %02d с (%,.1f с)",seconds/3600,seconds/60%60,seconds%60,nanos/1e9);}
    private static void paintBar(Graphics g,int x,int y,int width,double fraction,Color color){
        paintBar(g,x,y,width,2,fraction,color);
    }
    private static void paintBar(Graphics g,int x,int y,int width,int height,double fraction,Color color){
        if(width<=0)return;g.setColor(Theme.BORDER);g.fillRect(x,y,width,height);
        if(fraction<=0)return;
        int filled=Math.min(width,Math.max(2,(int)Math.round(width*fraction)));
        g.setColor(color);g.fillRect(x,y,filled,height);
    }
}

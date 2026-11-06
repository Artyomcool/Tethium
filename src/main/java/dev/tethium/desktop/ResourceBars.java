package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Two groups side by side: Codex and Claude tokens on the left, CPU and RAM on the right.
 *  Tokens and CPU time are scaled to the largest session (or project); current CPU load to the session's cap; RAM to the machine's physical memory. */
final class ResourceBars extends JPanel {
    static final int HEIGHT=6;
    static final Color TOKENS=new Color(0x9bbcff),TOKENS_CLAUDE=new Color(0xe8a87c),CPU=new Color(0x4e967e),CPU_NOW=new Color(0x80f5b5),RAM=new Color(0xc3a0f5);
    private static final Locale RU=Locale.forLanguageTag("ru");
    /** Width of the left (token) group; the right group starts after one gap. */
    static int leftWidth(int width){return Math.max(0,(width-Theme.GAP)/2);}
    /** Tokens and CPU time compare with the largest peer; current CPU load is measured against the session's cap and RAM against the machine's physical memory. */
    record Share(long tokens,long codexTokens,long claudeTokens,long codexPeak,long claudePeak,long tokenTotal,long nanos,long cpuPeak,long cpuTotal,long memory,long memoryPhysical,long memoryTotal,double cpuNow,int cpuCap){
        double tokenFraction(){return fraction(tokens,tokenTotal);}
        double codexFraction(){return fraction(codexTokens,codexPeak);}
        double claudeFraction(){return fraction(claudeTokens,claudePeak);}
        double cpuFraction(){return fraction(nanos,cpuPeak);}
        double memoryFraction(){return fraction(memory,memoryPhysical);}
        double currentCpuFraction(){return Double.isFinite(cpuNow)&&cpuCap>0?Math.clamp(cpuNow/cpuCap,0,1):0;}
        boolean hasUsage(){return tokens>0||nanos>0||memory>0||currentCpuFraction()>0;}
        private static double fraction(long own,long total){return total<=0?0:Math.clamp((double)own/total,0,1);}
    }
    ResourceBars(){super(new BorderLayout(Theme.GAP,0));setBorder(BorderFactory.createEmptyBorder(0,0,HEIGHT+1,0));}
    static Share share(JsonNode usage,String id,boolean project){return share(usage,Json.object(),id,project);}
    static Share share(JsonNode usage,JsonNode machine,String id,boolean project){
        return share(usage,machine,Json.object(),id,project);
    }
    static Share share(JsonNode usage,JsonNode machine,JsonNode controls,String id,boolean project){
        String group=project?"projects":"sessions";var cpu=usage.path("cpu");var memory=machine.path("memory");
        var current=controls.path(group).path(id).path("cpuPercent");
        var executors=usage.path("executors");var all=usage.path(group);
        // Before executors were recorded every token belonged to Codex, so a missing breakdown keeps the old meaning.
        var codex=executors.isObject()?executors.path("codex").path(group):all;var claude=executors.path("claude").path(group);
        long claudeTokens=claude.path(id).asLong(),tokens=all.path(id).asLong();
        return new Share(tokens,executors.isObject()?codex.path(id).asLong():tokens,claudeTokens,peak(codex,null),peak(claude,null),usage.path("total").asLong(),
            cpu.path(group).path(id).asLong(),peak(cpu.path(group),null),cpu.path("totalNanos").asLong(),
            memory.path(group).path(id).path("bytes").asLong(),machine.path("memoryTotal").asLong(),memory.path("totalBytes").asLong(),
            controls.path("supported").asBoolean()&&current.isNumber()?current.asDouble():Double.NaN,controls.path(group).path(id).path("effectiveCap").asInt(100));
    }
    /** Largest value among peers; nested maps read the given field. */
    static long peak(JsonNode values,String field){long max=0;for(var v:values)max=Math.max(max,(field==null?v:v.path(field)).asLong());return max;}
    static void paint(Graphics g,int x,int bottom,int width,Share share){
        if(!share.hasUsage())return;
        int left=leftWidth(width),rightX=x+left+Theme.GAP,right=Math.max(0,width-left-Theme.GAP),top=bottom-HEIGHT;
        paintBar(g,x,top,left,share.codexFraction(),TOKENS);
        paintBar(g,x,top+4,left,share.claudeFraction(),TOKENS_CLAUDE);
        paintBar(g,rightX,top,right,share.cpuFraction(),CPU);
        paintBar(g,rightX,top+2,right,1,share.currentCpuFraction(),CPU_NOW);
        paintBar(g,rightX,top+4,right,share.memoryFraction(),RAM);
    }
    /** 0 — tokens (left group), 1 — CPU, 2 — RAM (right group); -1 outside the bars. */
    static int lane(int x,int y,int left,int width,int bottom){
        int offset=y-(bottom-HEIGHT);if(offset<0||offset>=HEIGHT||x<left||x>=left+width)return -1;
        int tokens=leftWidth(width);if(x<left+tokens)return 0;if(x<left+tokens+Theme.GAP)return -1;
        return offset<3?1:2;
    }
    static String tooltip(int lane,JsonNode usage,JsonNode machine,JsonNode controls,String id,boolean project){
        var share=share(usage,machine,controls,id,project);String group=project?"projects":"sessions";
        String subject=project?"Проект":"Сессия",peer=project?"наибольшего проекта":"наибольшей сессии",body;
        if(lane==0){
            var byExecutor=usage.path("executors");long codexTotal=byExecutor.isObject()?byExecutor.path("codex").path("total").asLong():share.tokenTotal,claudeTotal=byExecutor.path("claude").path("total").asLong();
            body=heading(TOKENS,"Токены · "+subject)+String.format(RU,"<br><b>%,d</b> · %.2f%% от общего расхода<br>Все сессии: %,d токенов",share.tokens,100*share.tokenFraction(),share.tokenTotal)
                +"<br>"+colored(TOKENS,"Codex")+String.format(RU,": <b>%,d</b> · %.0f%% от %s (%,d)",share.codexTokens,100*share.codexFraction(),peer,share.codexPeak)+(codexTotal>0?String.format(RU," · всего %,d",codexTotal):"")
                +"<br>"+colored(TOKENS_CLAUDE,"Claude")+String.format(RU,": <b>%,d</b> · %.0f%% от %s (%,d)",share.claudeTokens,100*share.claudeFraction(),peer,share.claudePeak)+(claudeTotal>0?String.format(RU," · всего %,d",claudeTotal):"")
                +"<br>Вход и выход, без чтения из кэша<br>"+period(usage.path("start").asLong())+"<br>Верхняя полоса — Codex, нижняя — Claude; длина сравнивается с "+peer+" того же исполнителя.<br>Учтённые токены разговоров, включая кэш; это не остаток квоты.";
        }else if(lane==1){
            var control=controls.path(group).path(id);
            body=heading(CPU_NOW,"CPU · "+subject)+"<br>За период: <b>"+duration(share.nanos)+"</b> · "+percent(100*fraction(share.nanos,share.cpuTotal))+" от общего CPU-времени"
                +String.format(RU,"<br>Полоса: %.0f%% от %s (%s)",100*share.cpuFraction(),peer,duration(share.cpuPeak))
                +"<br>Все сессии: "+duration(share.cpuTotal)+"<br>"+period(Math.max(usage.path("start").asLong(),usage.path("cpu").path("observedSince").asLong()));
            if(Double.isFinite(share.cpuNow))body+="<br>Сейчас: <b>"+percent(share.cpuNow)+"</b> мощности компьютера · лимит "+share.cpuCap+"%"+String.format(RU," · полоса: %.0f%% от лимита",100*share.currentCpuFraction());
            else body+="<br>Текущая загрузка пока недоступна.";
            body+="<br>Верх полосы — накопленное время относительно "+peer+"; яркий низ — загрузка сейчас относительно лимита CPU.<br>100% мощности = все ядра компьютера.";
        }else{
            var memory=machine.path("memory");var own=memory.path(group).path(id);
            body=heading(RAM,"RAM сейчас · "+subject);
            if(!memory.isObject())return "<html>"+body+"<br>Замер памяти ещё не получен.</html>";
            body+="<br><b>"+bytes(share.memory)+"</b> · "+percent(100*fraction(share.memory,share.memoryTotal))+" памяти всех сессий"
                +"<br>Все сессии: "+bytes(share.memoryTotal)+" · процессов здесь: "+own.path("processes").asInt();
            if(share.memoryPhysical>0)body+="<br>Полоса: "+percent(100*share.memoryFraction())+" от RAM компьютера ("+bytes(share.memoryPhysical)+")";
            else body+="<br>Объём RAM компьютера пока неизвестен; полоса пуста.";
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
    private static double fraction(long own,long total){return total<=0?0:Math.clamp((double)own/total,0,1);}
    private static String colored(Color color,String text){return "<font color='#"+String.format("%06x",color.getRGB()&0xffffff)+"'>"+text+"</font>";}
    private static String heading(Color color,String title){return "<b>"+colored(color,title)+"</b>";}
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

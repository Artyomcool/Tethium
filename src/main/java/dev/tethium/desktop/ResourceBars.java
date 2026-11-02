package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/** Both denominators are the same scope: all projects, or all sessions in this project. */
final class ResourceBars extends JPanel {
    record Share(long tokens,long tokenTotal,long nanos,long cpuTotal){
        double tokenFraction(){return fraction(tokens,tokenTotal);}
        double cpuFraction(){return fraction(nanos,cpuTotal);}
        private static double fraction(long own,long total){return total<=0?0:Math.clamp((double)own/total,0,1);}
        String tooltip(){return String.format(Locale.forLanguageTag("ru"),"Токены: %,d · %.2f%% | CPU: %.1f с · %.2f%%",tokens,100*tokenFraction(),nanos/1e9,100*cpuFraction());}
    }
    Share share=new Share(0,0,0,0);
    ResourceBars(){super(new BorderLayout(Theme.GAP,0));setBorder(BorderFactory.createEmptyBorder(0,0,7,0));}
    static Share share(JsonNode usage,String id,boolean project,String projectId){
        String group=project?"projects":"sessions";var cpu=usage.path("cpu");
        return new Share(usage.path(group).path(id).asLong(),project?usage.path("total").asLong():usage.path("projects").path(projectId).asLong(),cpu.path(group).path(id).asLong(),project?cpu.path("totalNanos").asLong():cpu.path("projects").path(projectId).asLong());
    }
    @Override protected void paintChildren(Graphics g){super.paintChildren(g);int x=Theme.UNIT+22+Theme.GAP,w=Math.max(0,getWidth()-x-Theme.GAP);g.setColor(Theme.BORDER);g.fillRect(x,getHeight()-6,w,1);g.fillRect(x,getHeight()-3,w,1);g.setColor(Theme.ACCENT);g.fillRect(x,getHeight()-6,(int)Math.round(w*share.tokenFraction()),2);g.setColor(Theme.GREEN);g.fillRect(x,getHeight()-3,(int)Math.round(w*share.cpuFraction()),2);}
}

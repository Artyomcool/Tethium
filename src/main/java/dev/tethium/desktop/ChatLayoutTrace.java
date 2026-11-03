package dev.tethium.desktop;

import dev.tethium.json.Json;
import dev.tethium.host.AppPaths;
import dev.tethium.update.BuildInfo;
import javax.swing.*;
import javax.swing.text.html.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

/** Geometry only: no prompts, replies, selections, paths or HTML are written to the journal. */
final class ChatLayoutTrace implements AutoCloseable {
    private final ChatTranscript text;
    private final JScrollPane scroll;
    private final ChatTraceLog log;
    private final javax.swing.Timer sampler;
    private final AWTEventListener input;
    private String session="",lastGeometry="",lastOperation="init",lastInput="none";
    private long sequence,lastInputAt,lastSummary=System.currentTimeMillis(),bucketAt,lastOperationAt;
    private int budget,rateDropped,lastScroll;
    private boolean closed;
    private final Map<String,Long> counts=new TreeMap<>();
    private long rebuildNanos,maxRebuildNanos;
    ChatLayoutTrace(ChatTranscript text,JScrollPane scroll){
        this.text=text;this.scroll=scroll;
        String instance=System.currentTimeMillis()+"-"+ProcessHandle.current().pid()+"-"+UUID.randomUUID().toString().substring(0,8);
        log=new ChatTraceLog(AppPaths.data().resolve("diagnostics/chat-layout"),instance,2*1024*1024);
        text.layoutTrace=this;
        input=e->{if(e instanceof InputEvent ie&&ie.getComponent()!=null&&(ie.getComponent()==scroll||SwingUtilities.isDescendingFrom(ie.getComponent(),scroll))){
            boolean relevant=e instanceof MouseWheelEvent||e instanceof KeyEvent k&&k.getID()==KeyEvent.KEY_PRESSED||e instanceof MouseEvent m&&(m.getID()==MouseEvent.MOUSE_PRESSED||m.getID()==MouseEvent.MOUSE_DRAGGED);
            if(relevant){lastInputAt=System.currentTimeMillis();lastInput=e.getClass().getSimpleName();event("input","kind",lastInput);}
        }};
        Toolkit.getDefaultToolkit().addAWTEventListener(input,AWTEvent.MOUSE_EVENT_MASK|AWTEvent.MOUSE_MOTION_EVENT_MASK|AWTEvent.MOUSE_WHEEL_EVENT_MASK|AWTEvent.KEY_EVENT_MASK);
        lastScroll=scroll.getVerticalScrollBar().getValue();
        scroll.getVerticalScrollBar().addAdjustmentListener(e->{int y=e.getValue(),dy=y-lastScroll;lastScroll=y;if(dy!=0)event("scroll","dy",dy,"adjusting",e.getValueIsAdjusting(),"stack",Math.abs(dy)>=8?stack():"");});
        scroll.getViewport().addChangeListener(e->event("viewport.change"));
        sampler=new javax.swing.Timer(250,e->sample());sampler.start();
        event("open","pid",ProcessHandle.current().pid(),"build",BuildInfo.BUILD);
    }
    void session(String id){if(!Objects.equals(session,id)){summary();session=Objects.toString(id,"");lastGeometry="";event("session");}}
    void operation(String type,Object...details){lastOperation=type;lastOperationAt=System.currentTimeMillis();event(type,details);}
    void document(int characters,int fingerprint,long nanos){rebuildNanos+=nanos;maxRebuildNanos=Math.max(maxRebuildNanos,nanos);event("document.replace","characters",characters,"fingerprint",fingerprint,"durationMs",nanos/1e6,"stack",stack());}
    void event(String type,Object...details){
        if(closed)return;counts.merge(type,1L,Long::sum);long now=System.currentTimeMillis();
        if(now-bucketAt>=1000){bucketAt=now;budget=0;}
        if(budget++>=160&&!java.util.Set.of("summary","open","close","session").contains(type)){rateDropped++;return;}
        var e=Json.object("time",now,"event",type,"seq",++sequence,"session",session,"operation",lastOperation,"operationAgeMs",now-lastOperationAt,"inputAgeMs",lastInputAt==0?-1:now-lastInputAt);
        var bar=scroll.getVerticalScrollBar();var vp=scroll.getViewport();
        e.put("scrollY",bar.getValue());e.put("scrollMax",bar.getMaximum());e.put("extent",bar.getVisibleAmount());e.put("viewportW",vp.getWidth());e.put("viewportH",vp.getHeight());e.put("viewY",vp.getViewPosition().y);
        e.put("textX",text.getX());e.put("textY",text.getY());e.put("textW",text.getWidth());e.put("textH",text.getHeight());e.put("document",System.identityHashCode(text.getDocument()));e.put("documentLength",text.getDocument().getLength());e.put("verticalBar",scroll.getVerticalScrollBar().isVisible());e.put("horizontalBar",scroll.getHorizontalScrollBar().isVisible());e.put("renderingPage",text.renderingPage);e.put("virtual",text.virtualScrolling);e.put("focused",text.isFocusOwner());
        for(int i=0;i+1<details.length;i+=2)e.set((String)details[i],Json.MAPPER.valueToTree(details[i+1]));log.offer(e);
    }
    private void sample(){
        if(closed)return;
        var bar=scroll.getVerticalScrollBar();var v=scroll.getViewport();
        String geometry=bar.getValue()+":"+bar.getMaximum()+":"+v.getWidth()+":"+v.getHeight()+":"+text.getX()+":"+text.getY()+":"+text.getWidth()+":"+text.getHeight()+":"+System.identityHashCode(text.getDocument());
        if(!geometry.equals(lastGeometry)){
            lastGeometry=geometry;String chunk="";int offset=-1,lineY=-1;
            try{int y=v.getViewPosition().y-text.getY();if(text.isVisible()&&y>=0&&y<text.getHeight()&&text.getDocument() instanceof HTMLDocument doc){int position=text.viewToModel2D(new Point(24,y));var bounds=text.modelToView2D(position);lineY=bounds==null?-1:(int)bounds.getY()+text.getY()-v.getViewPosition().y;for(var element=doc.getCharacterElement(position);element!=null;element=element.getParentElement()){var id=element.getAttributes().getAttribute(HTML.Attribute.ID);if(id!=null&&id.toString().startsWith("chunk-")){chunk=id.toString();offset=position-element.getStartOffset();break;}}}}
            catch(Exception ignored){}
            event("geometry.sample","scale",text.getGraphicsConfiguration()==null?1:text.getGraphicsConfiguration().getDefaultTransform().getScaleX(),"fontSize",text.getFont().getSize2D(),"chunk",chunk,"character",offset,"lineScreenY",lineY);
        }
        if(System.currentTimeMillis()-lastSummary>=30_000)summary();
    }
    private void summary(){event("summary","counts",new TreeMap<>(counts),"rebuildTotalMs",rebuildNanos/1e6,"rebuildMaxMs",maxRebuildNanos/1e6,"rateDropped",rateDropped,"writerDropped",log.dropped.get());counts.clear();rebuildNanos=maxRebuildNanos=0;rateDropped=0;lastSummary=System.currentTimeMillis();}
    static String stack(){return StackWalker.getInstance().walk(s->s.filter(f->!f.getClassName().contains("ChatLayoutTrace")&&!f.getClassName().contains("ChatTraceLog")).limit(10).map(f->f.getClassName().replace("dev.tethium.desktop.","")+"."+f.getMethodName()+":"+f.getLineNumber()).collect(java.util.stream.Collectors.joining(" > ")));}
    @Override public void close(){if(closed)return;summary();event("close");closed=true;sampler.stop();Toolkit.getDefaultToolkit().removeAWTEventListener(input);text.layoutTrace=null;log.close();}
}

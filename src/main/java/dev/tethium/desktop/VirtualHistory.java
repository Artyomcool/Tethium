package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import javax.swing.*;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** One bounded HTML document in a virtual scroll surface; unloaded text occupies estimated space. */
final class VirtualHistory implements AutoCloseable {
    private static final int WINDOW=12;
    private final HostClient host;
    private final ChatTranscript text;
    private final JScrollPane scroll;
    private final ChatNavigation navigation;
    private final ChatLayoutTrace trace;
    private final Surface surface=new Surface();
    private final ExecutorService loader=Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final javax.swing.Timer debounce=new javax.swing.Timer(60,e->loadViewport());
    private final ChatMarkdown markdown=new ChatMarkdown();
    private List<JsonNode> entries=List.of();
    // Keep the last measured height for each logical chunk while its next HTML is loading.
    // Metadata (length/media/revision) may change earlier than the visible document;
    // replacing its height with an estimate here makes tail-follow jump twice per delta.
    private final Map<String,Integer> measured=new HashMap<>();
    private final LinkedHashMap<String,String> htmlCache=new LinkedHashMap<>(32,.75f,true);
    private int[] prefix={0};
    private String session,version="",requestedVersion="",renderedVersion="",error="";
    private int first=-1,end=-1,width;
    private String visibleHtml="";
    private int renderedWidth;
    private record TextAnchor(String chunk,int character,int pixelOffset){}
    /** The bottom edge of the viewport: the line there keeps its place when the content above or below changes height. */
    private int bottomEdge(){return scroll.getVerticalScrollBar().getValue()+scroll.getViewport().getHeight();}
    private int fromBottom(int bottom){return Math.max(0,bottom-scroll.getViewport().getHeight());}
    private TextAnchor textAnchor(){
        int y=bottomEdge()-1-text.getY();
        if(first<0||y<0||y>=text.getHeight()||!(text.getDocument() instanceof HTMLDocument doc))return null;
        int position=text.viewToModel2D(new Point(24,y));
        for(int i=first;i<end;i++){var element=doc.getElement("chunk-"+i);if(element!=null&&position>=element.getStartOffset()&&position<element.getEndOffset())try{return new TextAnchor(key(entries.get(i)),position-element.getStartOffset(),y-(int)text.modelToView2D(position).getY());}catch(Exception ignored){}}
        return null;
    }
    private int anchoredY(TextAnchor anchor,int fallback){
        if(anchor==null||!(text.getDocument() instanceof HTMLDocument doc))return fallback;
        for(int i=first;i<end;i++)if(key(entries.get(i)).equals(anchor.chunk)){var element=doc.getElement("chunk-"+i);if(element!=null)try{return fromBottom(text.getY()+(int)text.modelToView2D(Math.min(element.getEndOffset()-1,element.getStartOffset()+anchor.character)).getY()+anchor.pixelOffset+1);}catch(Exception ignored){}}
        return fallback;
    }
    private long generation,request;
    private JsonNode pendingPosition;
    private boolean enabled,closed,indexKnown,indexLoading,pageLoading,layingOut,forceTail;
    VirtualHistory(HostClient host,ChatTranscript text,JScrollPane scroll,ChatNavigation navigation){
        this.host=host;this.text=text;this.scroll=scroll;this.navigation=navigation;this.trace=navigation.trace();debounce.setRepeats(false);
        scroll.getVerticalScrollBar().addAdjustmentListener(e->{if(enabled&&!layingOut)debounce.restart();});
        scroll.getViewport().addComponentListener(new ComponentAdapter(){@Override public void componentResized(ComponentEvent e){if(enabled&&scroll.getViewport().getWidth()>0){var saved=navigation.beforeChange(false);int anchor=at(Math.max(0,bottomEdge()-1)),offset=bottomEdge()-prefix[anchor];boolean changed=width!=scroll.getViewport().getWidth();trace.operation("history.resize","oldWidth",width,"newWidth",scroll.getViewport().getWidth(),"changed",changed,"anchor",anchor,"offset",offset);if(changed)measured.clear();reindex();restore(saved,fromBottom(prefix[anchor]+offset));if(changed)first=-1;debounce.restart();}}});
    }
    boolean enabled(){return enabled;}
    void restorePosition(JsonNode saved){pendingPosition=saved.deepCopy();forceTail=saved.path("tail").asBoolean();if(!entries.isEmpty()){navigation.restorePosition(pendingPosition);pendingPosition=null;debounce.restart();}}
    void legacy(){if(!enabled)return;reset(null);enabled=false;pendingPosition=null;text.virtualScrolling=false;text.setVisible(true);surface.remove(text);scroll.setViewportView(text);navigation.virtualPositions(null);}

    void reset(String id){
        trace.session(id);trace.operation("history.reset","generation",generation);
        generation++;request++;debounce.stop();pendingPosition=null;session=id;entries=List.of();prefix=new int[]{0};version=requestedVersion=renderedVersion=error=visibleHtml="";renderedWidth=0;first=end=-1;indexKnown=indexLoading=pageLoading=false;forceTail=true;synchronized(htmlCache){htmlCache.clear();}measured.clear();
        if(enabled){text.setVisible(false);navigation.virtualPositions(this::positionOf);surface.revalidate();surface.repaint();}
    }
    void update(String id,String revision,boolean tail){
        if(closed)return;if(!Objects.equals(session,id))reset(id);forceTail|=tail;
        if(!enabled){enabled=true;text.virtualScrolling=true;text.setVisible(false);surface.add(text);scroll.setViewportView(surface);navigation.virtualPositions(this::positionOf);}
        requestedVersion=revision;
        if(!revision.equals(version)&&!indexLoading)loadIndex();else if(forceTail||first<0||!version.equals(renderedVersion))debounce.restart();
    }
    private void loadIndex(){
        trace.operation("history.index.request","version",version,"requestedVersion",requestedVersion,"entries",entries.size());indexLoading=true;long expected=generation;String id=session;
        loader.submit(()->{try{var result=host.get("/history/index?gallery=true&session="+id);if(!result.path("entries").isArray())throw new IllegalStateException("Ядро не вернуло список сообщений");SwingUtilities.invokeLater(()->{
            if(closed||expected!=generation)return;trace.operation("history.index.apply","version",result.path("version").asText(),"entries",result.path("entries").size());indexKnown=true;indexLoading=false;error="";
            var saved=navigation.beforeChange(forceTail);boolean shown=first>=0&&!entries.isEmpty();int oldIndex=at(Math.max(0,bottomEdge()-1)),offset=bottomEdge()-prefix[Math.max(0,oldIndex)];String anchor=oldIndex>=0&&oldIndex<entries.size()?key(entries.get(oldIndex)):"";
            var list=new ArrayList<JsonNode>();result.path("entries").forEach(list::add);if(entries.isEmpty()||list.isEmpty())text.setVisible(false);entries=List.copyOf(list);var valid=new HashSet<String>();for(var row:entries)valid.add(key(row));measured.keySet().retainAll(valid);version=result.path("version").asText();
            var tasks=new ArrayList<ChatNavigation.Task>();for(var row:entries)if(row.path("offset").asInt()==0&&row.path("role").asText().equals("user"))tasks.add(new ChatNavigation.Task(row.path("sequence").asLong(),tasks.size()+1,row.path("title").asText().replaceAll("\\s+"," ")));
            navigation.setTasks(tasks);reindex();int y=saved.absolute();boolean found=false;for(int i=0;i<entries.size();i++)if(key(entries.get(i)).equals(anchor)){y=fromBottom(prefix[i]+offset);found=true;break;}
            // New rows have estimated heights only; jumping to their estimated end shows blank space and then jumps back.
            restore(saved,y,shown&&found);if(pendingPosition!=null){navigation.restorePosition(pendingPosition);forceTail=pendingPosition.path("tail").asBoolean();pendingPosition=null;}debounce.restart();
        });}catch(Exception failure){SwingUtilities.invokeLater(()->{if(expected==generation){indexLoading=false;error="История не загружена: "+failure.getMessage();surface.repaint();}});}});
    }
    private static String baseVersion(String value){int last=value.lastIndexOf(':');return last<0?value:value.substring(0,last);}
    private static String key(JsonNode row){return row.path("sequence").asText()+":"+row.path("offset").asInt();}
    private void reindex(){
        int previousTotal=prefix[prefix.length-1];
        width=Math.max(200,scroll.getViewport().getExtentSize().width);prefix=new int[entries.size()+1];
        int columns=Math.max(20,(width-60)/7);
        for(int i=0;i<entries.size();i++){var row=entries.get(i);int estimate=48+20*Math.max(row.path("lines").asInt(1),1+row.path("length").asInt()/columns);if(row.path("kind").asText().equals("gallery"))estimate=galleryHeight(row,width-64);int h=measured.getOrDefault(key(row),estimate);prefix[i+1]=(int)Math.min(1_500_000_000L,(long)prefix[i]+h);}
        surface.revalidate();surface.setSize(width,Math.max(scroll.getViewport().getHeight(),prefix[entries.size()]));
        if(first>=0&&first<entries.size())text.setLocation(0,prefix[first]);
        trace.event("history.reindex","previousTotal",previousTotal,"total",prefix[prefix.length-1],"entries",entries.size(),"measured",measured.size(),"first",first,"end",end,"width",width);
    }
    private int positionOf(long sequence){for(int i=0;i<entries.size();i++)if(entries.get(i).path("sequence").asLong()==sequence)return prefix[i];return -1;}
    private int at(int y){int low=0,high=entries.size();while(low<high){int mid=(low+high+1)/2;if(prefix[mid]<=y)low=mid;else high=mid-1;}return Math.min(low,Math.max(0,entries.size()-1));}
    private void restore(ChatNavigation.Position saved,int y){restore(saved,y,false);}
    private void restore(ChatNavigation.Position saved,int y,boolean hold){

        trace.operation("history.restore","targetY",y,"savedY",saved.absolute(),"tail",saved.tail(),"task",saved.sequence());layingOut=true;try{scroll.doLayout();scroll.getViewport().setViewSize(surface.getPreferredSize());scroll.getViewport().doLayout();var bar=scroll.getVerticalScrollBar();bar.setValues(bar.getValue(),scroll.getViewport().getHeight(),0,surface.getHeight());int taskY=positionOf(saved.sequence());navigation.afterChange(new ChatNavigation.Position(saved.tail(),y,saved.sequence(),taskY<0?saved.offset():y-taskY,saved.navigation()),hold);}finally{layingOut=false;}
    }
    private void loadViewport(){
        if(closed||!enabled||indexLoading||!indexKnown)return;
        if(entries.isEmpty()){text.setText(ChatTheme.page("<p class='muted'>В этой сессии пока нет сообщений. Опишите задачу.</p>"));text.setBounds(0,0,width,120);text.setVisible(true);surface.repaint();return;}
        if(text.isFocusOwner()&&text.getSelectedText()!=null)return;
        boolean tail=forceTail||navigation.savePosition().path("tail").asBoolean();int visible=at(scroll.getVerticalScrollBar().getValue());int wanted=tail?Math.max(0,entries.size()-WINDOW):Math.max(0,visible-3);wanted=Math.min(wanted,Math.max(0,entries.size()-WINDOW));
        if(first>=0&&visible>=first&&at(scroll.getVerticalScrollBar().getValue()+scroll.getViewport().getHeight())<end&&version.equals(renderedVersion)&&width==scroll.getViewport().getWidth()&&!forceTail)return;
        if(pageLoading)return;trace.operation("history.page.request","first",first,"end",end,"wanted",wanted,"visible",visible,"tail",tail,"forceTail",forceTail,"version",version,"renderedVersion",renderedVersion,"width",width);pageLoading=true;long expected=generation,serial=++request;String id=session;int start=wanted;
        loader.submit(()->{try{var page=host.get("/history/page?gallery=true&session="+id+"&first="+start+"&count="+WINDOW);var body=new StringBuilder();
            for(var row:page.path("entries")){
                String role=row.path("role").asText(),cacheKey=key(row)+":"+(row.path("live").asBoolean()?page.path("version").asText():row.path("length").asText())+":"+row.path("revision").asLong()+":"+row.path("mediaVersion").asLong();
                String html; synchronized(htmlCache){html=htmlCache.get(cacheKey);if(html==null){html=row.path("kind").asText().equals("gallery")?galleryHtml(row):row.path("kind").asText().equals("image")?imageHtml(row):markdown.render(row.path("text").asText());htmlCache.put(cacheKey,html);while(htmlCache.size()>64)htmlCache.remove(htmlCache.keySet().iterator().next());}}
                String who=role.equals("user")?"ВЫ":role.equals("assistant")?ChatTheme.executorLabel(row.path("provider").asText()):role.equals("session")?"ДРУГАЯ СЕССИЯ":"TETHIUM";
                if(row.path("offset").asInt()>0)who+=" · ПРОДОЛЖЕНИЕ";if(row.path("provider").asText().equals("IMPORTED"))who+=" · ИМПОРТ";
                boolean graphic=Set.of("image","gallery").contains(row.path("kind").asText());
                body.append("<div id='chunk-").append(row.path("index").asInt()).append("' class='").append(role.equals("user")?"user":"message").append("'>");if(!graphic)body.append("<p class='label'>").append(who).append(ChatTime.label(row.path("timestamp").asLong())).append("</p>");
                if(role.equals("user")&&row.path("offset").asInt()==0)body.append("<a id='task-").append(row.path("sequence").asLong()).append("'></a>");body.append(html).append("</div>");
            }
            String rendered=ChatTheme.page(body.toString());SwingUtilities.invokeLater(()->{
                if(closed||expected!=generation||serial!=request)return;trace.operation("history.page.received","serial",serial,"start",start,"count",page.path("entries").size(),"version",page.path("version").asText());pageLoading=false;
                if(page.path("stale").asBoolean()||!baseVersion(version).equals(baseVersion(page.path("version").asText()))){version="";loadIndex();return;}
                var updated=new ArrayList<JsonNode>(entries);for(var row:page.path("entries")){int index=row.path("index").asInt();if(index>=updated.size()){version="";loadIndex();return;}var metadata=(com.fasterxml.jackson.databind.node.ObjectNode)row.deepCopy();metadata.remove(java.util.List.of("text","thumbnail"));for(var image:metadata.path("images"))((com.fasterxml.jackson.databind.node.ObjectNode)image).remove("thumbnail");updated.set(index,metadata);}entries=List.copyOf(updated);version=page.path("version").asText();
                // New output below the viewport must not rebuild identical HTML being read above it.
                if(first==start&&renderedWidth==width&&visibleHtml.equals(rendered)&&!forceTail){trace.event("history.page.identical");renderedVersion=version;return;}
                trace.operation("history.page.replace","rangeChanged",first!=start,"widthChanged",renderedWidth!=width,"htmlChanged",!visibleHtml.equals(rendered),"forceTail",forceTail,"start",start,"htmlLength",rendered.length());
                var saved=navigation.beforeChange(forceTail);var textAnchor=saved.tail()?null:textAnchor();trace.event("history.anchor","chunk",textAnchor==null?null:textAnchor.chunk,"character",textAnchor==null?-1:textAnchor.character,"pixelOffset",textAnchor==null?0:textAnchor.pixelOffset);forceTail=false;int anchor=at(Math.max(0,bottomEdge()-1)),offset=bottomEdge()-prefix[anchor];
                first=start;end=start+page.path("entries").size();renderedVersion=version;visibleHtml=rendered;renderedWidth=width;text.renderingPage=true;text.setText(rendered);text.setSize(width,Math.max(100,text.getPreferredSize().height));text.setSize(width,Math.max(100,text.getPreferredSize().height));
                var doc=(HTMLDocument)text.getDocument();int height=text.getPreferredSize().height;int previousY=0;
                for(int i=first;i<end;i++){int nextY=height;if(i+1<end){var element=doc.getElement("chunk-"+(i+1));try{if(element!=null)nextY=(int)text.modelToView2D(element.getStartOffset()).getY();}catch(Exception ignored){}}
                    var row=entries.get(i);measured.put(key(row),Math.max(24,nextY-previousY));previousY=nextY;
                }
                reindex();text.setBounds(0,prefix[first],width,height);text.setVisible(true);restore(saved,anchoredY(textAnchor,fromBottom(prefix[Math.min(anchor,entries.size())]+offset)));surface.repaint();trace.event("history.page.applied","first",first,"end",end,"height",height);SwingUtilities.invokeLater(()->{text.renderingPage=false;trace.event("history.page.settled");});
                debounce.restart();
            });
        }catch(Exception failure){SwingUtilities.invokeLater(()->{if(expected==generation){pageLoading=false;error=failure.getMessage();surface.repaint();}});}});
    }
    private static int galleryHeight(JsonNode row,int available){
        int width=0,lineHeight=0,total=16;for(var image:row.path("images")){int w=image.path("width").asInt(200)+8,h=image.path("height").asInt(120)+8;if(width>0&&width+w>available){total+=lineHeight;width=0;lineHeight=0;}width+=w;lineHeight=Math.max(lineHeight,h);}return total+lineHeight;
    }
    private String galleryHtml(JsonNode row)throws Exception{var html=new StringBuilder("<p class='gallery'>");for(var image:row.path("images"))html.append(imageHtml(image)).append(" ");return html.append("</p>").toString();}
    private String imageHtml(JsonNode row)throws Exception{
        var folder=java.nio.file.Files.createDirectories(dev.tethium.host.AppPaths.data().resolve("chat-thumbnails"));
        String key=java.util.UUID.nameUUIDFromBytes(row.path("file").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();var thumbnail=folder.resolve(key+".png");
        if(!java.nio.file.Files.isRegularFile(thumbnail)&&!row.path("thumbnail").asText().isBlank())java.nio.file.Files.write(thumbnail,java.util.Base64.getDecoder().decode(row.path("thumbnail").asText()));
        String target=java.nio.file.Path.of(row.path("file").asText()).toUri().toString();
        return "<a href='"+Theme.escape(target)+"'><img src='"+thumbnail.toUri()+"' data-original='"+Theme.escape(target)+"' width='"+row.path("width").asInt(160)+"' height='"+row.path("height").asInt(100)+"' border='0'></a>";
    }
    private final class Surface extends JPanel implements Scrollable {
        Surface(){super(null);setBackground(Theme.BACKGROUND);}
        @Override public Dimension getPreferredSize(){return new Dimension(Math.max(200,width),Math.max(scroll.getViewport().getHeight(),prefix[prefix.length-1]));}
        @Override protected void paintComponent(Graphics g){super.paintComponent(g);if(!indexKnown||!entries.isEmpty()&&(first<0||at(scroll.getVerticalScrollBar().getValue())<first||at(scroll.getVerticalScrollBar().getValue())>=end)){g.setColor(Theme.MUTED);g.drawString(error.isEmpty()?"Загружаем историю…":error,24,scroll.getVerticalScrollBar().getValue()+30);}}
        public Dimension getPreferredScrollableViewportSize(){return new Dimension(600,500);}
        public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 36;}
        public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(36,visible.height-50);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }
    @Override public void close(){closed=true;trace.close();generation++;debounce.stop();loader.shutdownNow();synchronized(htmlCache){htmlCache.clear();}}
}

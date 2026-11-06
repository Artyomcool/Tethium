package dev.tethium.probe;

import dev.tethium.agent.*;
import dev.tethium.desktop.*;
import dev.tethium.host.*;
import dev.tethium.json.Json;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Switching between recently shown sessions must be a component swap: no reload, scroll kept, well under a frame of work. */
public final class ChatSwitchProbe {
    private static Object read(Object o,String name){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
    private static void await(java.util.function.BooleanSupplier test,String what)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(test.getAsBoolean())return;Thread.sleep(40);}throw new AssertionError("Timed out: "+what);}
    private static String text(WorkspaceFrame frame){try{var t=(JEditorPane)read(frame,"transcript");return t.getDocument().getText(0,t.getDocument().getLength());}catch(Exception e){return "";}}
    private static int scroll(WorkspaceFrame frame){return ((JScrollPane)read(frame,"history")).getVerticalScrollBar().getValue();}
    public static void main(String[] args)throws Exception{
        Path root=Files.createDirectories(Path.of(".local","chat-switch",UUID.randomUUID().toString()).toAbsolutePath());System.setProperty("tethium.data",root.resolve("data").toString());
        Path workspace=Files.createDirectories(root.resolve("Workspace"));Theme.install();
        AgentAdapter adapter=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> e,Cancellation c){throw new AssertionError("No model calls");}};
        var frame=new WorkspaceFrame[1];int exit=0;
        try(var host=new HostService(root.resolve("data"),adapter);var client=new HostClient(host.descriptor())){
            var ids=new ArrayList<String>();
            for(int s=0;s<3;s++){var messages=new ArrayList<Object>();for(int i=0;i<60;i++){messages.add(Json.object("sourceId","u"+i,"role","user","text","Запрос "+i+" сессии "+s));messages.add(Json.object("sourceId","a"+i,"role","assistant","text",("Ответ "+i+" сессии "+s+". Абзац текста для высоты.\n\n").repeat(4)+"MARK_S"+s+"_"+i));}
                ids.add(client.post("/imports/conversation",Json.object("projectPath",workspace.toString(),"title","Сессия "+s,"sourceKey","switch"+s,"messages",messages)).path("id").asText());}
            String a=ids.get(0),b=ids.get(1),c=ids.get(2);
            SwingUtilities.invokeAndWait(()->{frame[0]=new WorkspaceFrame(new HostClient(host.descriptor()),()->{},a);frame[0].setSize(1200,800);frame[0].setVisible(true);});
            await(()->a.equals(frame[0].selectedSession())&&text(frame[0]).contains("MARK_S0_59"),"session A rendered");Thread.sleep(600);
            // Scroll A up to the middle, then leave and come back.
            SwingUtilities.invokeAndWait(()->{var bar=((JScrollPane)read(frame[0],"history")).getVerticalScrollBar();bar.setValue(bar.getMaximum()/2);});Thread.sleep(900);
            int positionA=scroll(frame[0]);String visibleA=text(frame[0]);
            SwingUtilities.invokeAndWait(()->frame[0].selectSession(b));await(()->text(frame[0]).contains("MARK_S1_59"),"session B rendered");Thread.sleep(600);
            SwingUtilities.invokeAndWait(()->frame[0].selectSession(c));await(()->text(frame[0]).contains("MARK_S2_59"),"session C rendered");Thread.sleep(600);
            long start=System.nanoTime();var immediate=new String[1];var immediateScroll=new int[1];
            SwingUtilities.invokeAndWait(()->{frame[0].selectSession(a);immediate[0]=text(frame[0]);immediateScroll[0]=scroll(frame[0]);});
            long switchMs=(System.nanoTime()-start)/1_000_000;
            if(!immediate[0].equals(visibleA))throw new AssertionError("Cached session A was re-rendered or replaced on switch");
            if(immediate[0].contains("Загружаем историю"))throw new AssertionError("Cached switch showed the loading placeholder");
            if(Math.abs(immediateScroll[0]-positionA)>2)throw new AssertionError("Scroll position lost: "+positionA+" -> "+immediateScroll[0]);
            Thread.sleep(1500);
            if(Math.abs(scroll(frame[0])-positionA)>2)throw new AssertionError("Scroll drifted after the next state poll: "+positionA+" -> "+scroll(frame[0]));
            if(!text(frame[0]).equals(visibleA))throw new AssertionError("Document changed after the poll although history did not");
            int cached=((Map<?,?>)read(frame[0],"chatViews")).size();
            // A fourth and fifth session evict the oldest; the current one is never evicted.
            var d=client.post("/imports/conversation",Json.object("projectPath",workspace.toString(),"title","Сессия D","sourceKey","switchD","messages",List.of(Json.object("sourceId","u","role","user","text","D")))).path("id").asText();
            var e=client.post("/imports/conversation",Json.object("projectPath",workspace.toString(),"title","Сессия E","sourceKey","switchE","messages",List.of(Json.object("sourceId","u","role","user","text","E")))).path("id").asText();
            SwingUtilities.invokeAndWait(()->frame[0].selectSession(d));await(()->text(frame[0]).contains("D"),"session D");SwingUtilities.invokeAndWait(()->frame[0].selectSession(e));await(()->text(frame[0]).contains("E"),"session E");
            int afterEvict=((Map<?,?>)read(frame[0],"chatViews")).size();if(afterEvict>4)throw new AssertionError("Cache grew beyond 4: "+afterEvict);
            // A new message in a cached session shows up after switching back.
            client.post("/sessions/"+b+"/stop",Json.object());
            SwingUtilities.invokeAndWait(()->frame[0].selectSession(b));await(()->text(frame[0]).contains("MARK_S1_59"),"session B again");
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(root.resolve("report.json").toFile(),Json.object("passed",true,"cachedSwitchMs",switchMs,"scrollKept",true,"noReload",true,"cachedViews",cached,"afterEviction",afterEvict));
            System.out.println("CHAT_SWITCH_PASSED switchMs="+switchMs+" cached="+cached+" afterEviction="+afterEvict+" "+root);
        }catch(Throwable error){error.printStackTrace();exit=1;}
        finally{SwingUtilities.invokeAndWait(()->{if(frame[0]!=null){try{var m=frame[0].getClass().getDeclaredMethod("closeWindow");m.setAccessible(true);m.invoke(frame[0]);}catch(Exception ignored){}frame[0].dispose();}});}
        System.exit(exit);
    }
}

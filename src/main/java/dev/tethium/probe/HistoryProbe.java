package dev.tethium.probe;

import dev.tethium.desktop.*;
import dev.tethium.host.*;
import dev.tethium.core.SessionStore;
import dev.tethium.agent.*;
import dev.tethium.json.Json;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Thousands of messages, bounded rendering, distant scrollbar jumps and live append. */
public final class HistoryProbe {
    private static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void until(Callable<Boolean> test)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<end){if(test.call())return;Thread.sleep(80);}throw new AssertionError("History probe timed out");}
    private static String visible(WorkspaceFrame frame)throws Exception{var result=new String[1];SwingUtilities.invokeAndWait(()->{try{var pane=(JEditorPane)field(frame,"transcript");result[0]=pane.getDocument().getText(0,pane.getDocument().getLength());}catch(Exception e){throw new RuntimeException(e);}});return result[0];}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path out=Files.createDirectories(Path.of(".local/history-probe/"+UUID.randomUUID()).toAbsolutePath());Path data=Files.createDirectories(out.resolve("data"));System.setProperty("tethium.data",data.toString());
        String id;try(var store=new SessionStore(data.resolve("tethium.db"))){String project=store.project(out);id=store.create(project,null,"Большая история",out).id();store.transaction(()->{for(int i=0;i<2400;i++)store.append(id,i%2==0?"user":"assistant","CODEX","ENTRY-"+i+"\n\n"+"Параграф с результатами работы, ссылками и пояснениями. ".repeat(24)+"\n\nСледующий абзац.\n");return null;});}
        var release=new Semaphore(0);AgentAdapter adapter=new AgentAdapter(){public Provider provider(){return Provider.CODEX;}public Result run(Request r,Consumer<Event> e,Cancellation cancel)throws Exception{e.accept(new Event("text","NEW_STREAM",Json.object()));while(!release.tryAcquire(50,TimeUnit.MILLISECONDS))if(cancel.isCancelled())break;return new Result("fixture",Status.COMPLETED,"DONE");}};
        Theme.install();var window=new WorkspaceFrame[1];int exit=0;
        try(var host=new HostService(data,adapter);var client=new HostClient(host.descriptor())){
            SwingUtilities.invokeAndWait(()->{window[0]=new WorkspaceFrame(new HostClient(host.descriptor()),()->{},id);window[0].addNotify();window[0].validate();});
            until(()->visible(window[0]).contains("ENTRY-2399"));var pane=(JEditorPane)field(window[0],"transcript");var scroll=(JScrollPane)field(window[0],"history");var navigation=field(window[0],"chatNavigation");var picker=(JComboBox<?>)field(navigation,"picker");
            require(pane.getDocument().getLength()<100_000,"Unbounded HTML document");require(scroll.getVerticalScrollBar().getMaximum()>500_000,"Scrollbar covers only a loaded page");
            SwingUtilities.invokeAndWait(()->picker.setSelectedIndex(500));until(()->visible(window[0]).contains("ENTRY-1000"));int before=scroll.getVerticalScrollBar().getValue();
            client.post("/sessions/"+id+"/run",Json.object("prompt","new request"));Thread.sleep(1200);require(Math.abs(scroll.getVerticalScrollBar().getValue()-before)<80,"New output moved the old reading position: "+before+" -> "+scroll.getVerticalScrollBar().getValue());require(!visible(window[0]).contains("NEW_STREAM"),"Old page was replaced by live text");
            SwingUtilities.invokeAndWait(()->scroll.getVerticalScrollBar().setValue(0));until(()->visible(window[0]).contains("ENTRY-0\n"));
            SwingUtilities.invokeAndWait(()->scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()*3/4));until(()->!visible(window[0]).contains("ENTRY-0\n")&&pane.getDocument().getLength()<100_000);
            SwingUtilities.invokeAndWait(()->{try{var method=navigation.getClass().getDeclaredMethod("toEnd");method.setAccessible(true);method.invoke(navigation);}catch(Exception e){throw new RuntimeException(e);}});until(()->visible(window[0]).contains("NEW_STREAM"));
            release.release();until(()->visible(window[0]).contains("DONE"));
            SwingUtilities.invokeAndWait(()->{window[0].setSize(960,650);window[0].validate();});Thread.sleep(800);require(pane.getDocument().getLength()<100_000,"Resize expanded the full history");
            Json.MAPPER.writeValue(out.resolve("report.json").toFile(),Json.object("passed",true,"messages",2400,"renderedCharacters",pane.getDocument().getLength(),"virtualHeight",scroll.getVerticalScrollBar().getMaximum(),"distantRequestJump",true,"scrollbarToAnyPage",true,"liveAppendPreservesPosition",true));System.out.println("HISTORY_PROBE_PASSED "+out);
        }catch(Throwable error){error.printStackTrace();if(window[0]!=null){var paged=field(window[0],"pagedHistory");for(String name:java.util.List.of("enabled","session","version","requestedVersion","renderedVersion","first","end","width","indexLoading","pageLoading","error"))System.err.println(name+"="+field(paged,name));System.err.println("text="+visible(window[0]).substring(0,Math.min(300,visible(window[0]).length())));}exit=1;}finally{release.release();if(window[0]!=null)SwingUtilities.invokeAndWait(window[0]::closeWindow);}System.exit(exit);
    }
}

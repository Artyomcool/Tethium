package dev.tethium.probe;
import dev.tethium.desktop.*;
import dev.tethium.json.Json;
import org.cef.browser.CefBrowser;
import javax.swing.*;
import javax.swing.text.html.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Repeated Markdown/HTML transitions, including superseded loads and GL surface cleanup. */
public final class PreviewLifecycleProbe {
    static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void until(Callable<Boolean> test)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(System.nanoTime()<end){if(test.call())return;Thread.sleep(60);}throw new AssertionError("Preview lifecycle timed out");}
    public static void main(String[] args)throws Exception{
        Path out=Files.createDirectories(Path.of(".local/preview-lifecycle/"+UUID.randomUUID()).toAbsolutePath());System.setProperty("tethium.data",out.toString());Theme.install();
        Path md=Files.writeString(out.resolve("table.md"),"# Markdown\n\n| A | B |\n|---|---|\n| TABLE_CELL | value |\n\nA paragraph.\n"),html=Files.writeString(out.resolve("page.html"),"<!doctype html><meta charset=utf-8><canvas width=256 height=256 id=c></canvas><script>let gl=c.getContext('webgl');if(gl){gl.clearColor(.2,.5,.8,1);gl.clear(gl.COLOR_BUFFER_BIT)}document.title='READY';</script>");
        var frame=new JFrame[1];var view=new ArtifactView[1];var failures=new CopyOnWriteArrayList<Throwable>();Thread.setDefaultUncaughtExceptionHandler((thread,error)->{failures.add(error);error.printStackTrace();});int exit=0;
        try{
            SwingUtilities.invokeAndWait(()->{frame[0]=new JFrame("Tethium · проверка просмотра");view[0]=new ArtifactView();frame[0].add(view[0]);frame[0].setSize(840,640);frame[0].setVisible(true);});
            for(int i=0;i<20;i++){
                SwingUtilities.invokeAndWait(()->view[0].open(html));until(()->{var browser=(CefBrowser)field(view[0],"browser");if(browser==null||browser.getIdentifier()<=0)return false;String result=browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate",Json.object("expression","document.title","returnByValue",true).toString()).get(5,TimeUnit.SECONDS);return Json.parse(result).path("result").path("value").asText().equals("READY");});
                SwingUtilities.invokeAndWait(()->view[0].open(md));until(()->{var browser=(CefBrowser)field(view[0],"browser");if(browser==null||browser.getIdentifier()<=0)return false;String result=browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate",Json.object("expression","!!document.querySelector('table') && document.body.innerText.includes('TABLE_CELL')","returnByValue",true).toString()).get(5,TimeUnit.SECONDS);return Json.parse(result).path("result").path("value").asBoolean();});
                if(i%4==0)SwingUtilities.invokeAndWait(()->{view[0].open(html);view[0].clear();view[0].open(md);});
            }
            SwingUtilities.invokeAndWait(()->view[0].clear());until(()->view[0].browserResourceCount()==0);Thread.sleep(400);if(!failures.isEmpty())throw new AssertionError("Uncaught rendering errors",failures.getFirst());
            Json.MAPPER.writeValue(out.resolve("report.json").toFile(),Json.object("passed",true,"htmlMarkdownCycles",20,"tableRenders",true,"browserResourcesAfterClear",view[0].browserResourceCount(),"supersededLoads",true));System.out.println("PREVIEW_LIFECYCLE_PASSED "+out);
        }catch(Throwable error){error.printStackTrace();exit=1;}finally{SwingUtilities.invokeAndWait(()->{if(view[0]!=null)view[0].close();if(frame[0]!=null)frame[0].dispose();});}System.exit(exit);
    }
}

package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class HistorySwitchTest {
    @TempDir Path root;

    @Test void switchingStartsHistoryWhileThePreviousStateRequestIsBlocked()throws Exception{
        String previousData=System.getProperty("tethium.data");System.setProperty("tethium.data",root.toString());
        var block=new AtomicBoolean();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var window=new WorkspaceFrame[1];
        try(var host=new HostClient(Json.object()){
            @Override public JsonNode get(String path)throws Exception{
                if(path.startsWith("/state")){
                    if(block.get()&&path.contains("session=first")){entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("State fixture was not released");}
                    return Json.object("projects",List.of(Json.object("id","p","root",root.toString(),"title","Fixture","active",true)),
                            "sessions",List.of(session("first"),session("second")),"conversation",Json.object("windowed",true,"historyVersion","v"));
                }
                if(path.startsWith("/history/index"))return Json.object("version","v","entries",List.of(entry(false,path.contains("session=second")?"SECOND":"FIRST")));
                if(path.startsWith("/history/page"))return Json.object("version","v","entries",List.of(entry(true,path.contains("session=second")?"SECOND":"FIRST")));
                return Json.object();
            }
            @Override public JsonNode post(String path,Object body){return Json.object();}
            @Override public byte[] bytes(String path){throw new IllegalArgumentException("No fixture icons");}
        }){
            SwingUtilities.invokeAndWait(()->{Theme.install();window[0]=new WorkspaceFrame(host,()->{},"first");window[0].addNotify();window[0].validate();});
            until(()->text(window[0]).contains("FIRST"));
            block.set(true);assertTrue(entered.await(5,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->window[0].selectSession("second"));
            until(()->text(window[0]).contains("SECOND"));
            assertEquals(1,release.getCount(),"The new history must arrive before the old state request finishes");
        }finally{
            release.countDown();
            if(window[0]!=null)SwingUtilities.invokeAndWait(window[0]::closeWindow);
            if(previousData==null)System.clearProperty("tethium.data");else System.setProperty("tethium.data",previousData);
        }
    }

    @Test void aSlowThumbnailCannotBlockResettingTheHistoryOnTheUiThread()throws Exception{
        String previousData=System.getProperty("tethium.data");System.setProperty("tethium.data",root.toString());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var reset=new CountDownLatch(1);var view=new ChatView[1];
        var image=Json.object("kind","image","sequence",-101,"mediaId",1,"file",root.resolve("image.png").toString(),"index",0,"width",80,"height",80);
        try(var host=new HostClient(Json.object()){
            @Override public JsonNode get(String path){return Json.object("version","v","entries",List.of(image));}
            @Override public byte[] bytes(String path)throws Exception{entered.countDown();release.await(10,TimeUnit.SECONDS);throw new java.io.IOException("fixture released");}
        }){
            SwingUtilities.invokeAndWait(()->{view[0]=new ChatView("first",host);view[0].history.setSize(600,400);view[0].history.doLayout();view[0].paged.activate("first",true);});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            SwingUtilities.invokeLater(()->{view[0].paged.reset("second");reset.countDown();});
            assertTrue(reset.await(1,TimeUnit.SECONDS),"A thumbnail download held the HTML cache lock on the UI thread");
        }finally{
            release.countDown();if(view[0]!=null)SwingUtilities.invokeAndWait(view[0]::close);
            if(previousData==null)System.clearProperty("tethium.data");else System.setProperty("tethium.data",previousData);
        }
    }

    private static JsonNode session(String id){return Json.object("id",id,"projectId","p","title",id,"active",true,"preferences",Json.object("provider","CODEX"));}
    private static JsonNode entry(boolean body,String text){var row=Json.object("sequence",1,"index",0,"offset",0,"length",text.length(),"lines",1,"role","assistant","provider","CODEX");if(body)row.put("text",text);return row;}
    private static String text(WorkspaceFrame window)throws Exception{
        var value=new String[1];SwingUtilities.invokeAndWait(()->{try{var field=WorkspaceFrame.class.getDeclaredField("transcript");field.setAccessible(true);var text=(ChatTranscript)field.get(window);value[0]=text.getDocument().getText(0,text.getDocument().getLength());}catch(Exception e){throw new RuntimeException(e);}});return value[0];
    }
    private static void until(Callable<Boolean> condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<end){if(condition.call())return;Thread.sleep(20);}fail("History did not arrive while the state request was blocked");
    }
}

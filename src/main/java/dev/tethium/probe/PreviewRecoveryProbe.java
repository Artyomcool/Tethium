package dev.tethium.probe;

import dev.tethium.desktop.*;
import dev.tethium.json.Json;
import org.cef.browser.CefBrowser;
import javax.swing.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hidden, isolated browser: switch before native creation, then lose the renderer. */
public final class PreviewRecoveryProbe {
    private static ArtifactView view;
    private static CefBrowser browser() throws Exception {
        var field=ArtifactView.class.getDeclaredField("browser");field.setAccessible(true);
        var result=new CefBrowser[1];SwingUtilities.invokeAndWait(()->{try{result[0]=(CefBrowser)field.get(view);}catch(Exception e){throw new RuntimeException(e);}});return result[0];
    }
    private static void until(Callable<Boolean> check,String label) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<end){if(check.call())return;Thread.sleep(50);}throw new AssertionError(label);
    }
    private static boolean ready() throws Exception {
        var b=browser();if(b==null||b.getDevToolsClient()==null)return false;
        try{return Json.parse(b.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate",Json.object("expression","!!document.querySelector('table') && document.body.innerText.includes('RECOVERY_CELL')","returnByValue",true).toString()).get(2,TimeUnit.SECONDS)).path("result").path("value").asBoolean();}
        catch(ExecutionException|TimeoutException e){return false;}
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createDirectories(Path.of(".local/preview-recovery/"+java.util.UUID.randomUUID()).toAbsolutePath());
        System.setProperty("tethium.data",root.toString());Theme.install();
        Path md=Files.writeString(root.resolve("sample.md"),"# Recovery\n\n| Key | Value |\n|---|---|\n| RECOVERY_CELL | 42 |\n"),other=Files.writeString(root.resolve("other.html"),"<!doctype html><title>Other</title>other");
        var frame=new JFrame[1];int exit=0;
        try{
            SwingUtilities.invokeAndWait(()->{frame[0]=new JFrame("Preview recovery probe");view=new ArtifactView();frame[0].add(view);frame[0].setSize(720,480);frame[0].addNotify();frame[0].validate();view.open(md);});
            until(PreviewRecoveryProbe::ready,"Initial Markdown load");
            if(args.length==0||args[0].equals("race")){
                var armed=new AtomicBoolean();var raced=new AtomicBoolean();
                var listener=new ContainerAdapter(){@Override public void componentAdded(ContainerEvent e){if(e.getChild() instanceof com.jogamp.opengl.awt.GLCanvas&&armed.compareAndSet(true,false))SwingUtilities.invokeLater(()->{try{var b=browserOnEdt();raced.set(b!=null&&b.getIdentifier()<=0);view.clear();view.open(md);}catch(Exception failure){throw new RuntimeException(failure);}});}};
                SwingUtilities.invokeAndWait(()->view.addContainerListener(listener));
                for(int i=0;i<8;i++){armed.set(true);SwingUtilities.invokeAndWait(()->view.open(other));until(()->!armed.get(),"Browser install");until(PreviewRecoveryProbe::ready,"Markdown after pre-create switch (race="+raced.get()+")");}
                if(!raced.get())throw new AssertionError("Did not exercise pre-create switch");
                SwingUtilities.invokeAndWait(()->view.removeContainerListener(listener));
            }else{
                var b=browser();b.getDevToolsClient().executeDevToolsMethod("Page.crash","{}");
                Thread.sleep(400);until(PreviewRecoveryProbe::ready,"Markdown recovery after renderer crash");
                b=browser();b.getDevToolsClient().executeDevToolsMethod("Page.crash","{}");
                until(()->browser()==null&&view.browserResourceCount()==0,"Repeated crash stops automatic retries");
                SwingUtilities.invokeAndWait(view::reload);until(PreviewRecoveryProbe::ready,"Manual reload after repeated crash");
            }
            SwingUtilities.invokeAndWait(view::clear);until(()->view.browserResourceCount()<=1,"Browser cleanup keeps one warm tab");
            Json.MAPPER.writeValue(root.resolve("report.json").toFile(),Json.object("passed",true,"scenario",args.length==0?"race":args[0]));System.out.println("PREVIEW_RECOVERY_PASSED "+root);
        }catch(Throwable error){error.printStackTrace();exit=1;}finally{SwingUtilities.invokeAndWait(()->{if(view!=null)view.close();if(frame[0]!=null)frame[0].dispose();});}System.exit(exit);
    }
    private static CefBrowser browserOnEdt() throws Exception {var f=ArtifactView.class.getDeclaredField("browser");f.setAccessible(true);return (CefBrowser)f.get(view);}
}

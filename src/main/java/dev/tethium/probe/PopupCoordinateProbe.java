package dev.tethium.probe;

import dev.tethium.artifact.ArtifactServer;
import dev.tethium.json.Json;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.*;
import org.cef.browser.*;
import org.cef.handler.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.nio.file.*;
import java.util.concurrent.*;

/** Native select hover through JCEF's own AWT input; the test window stays off-screen and pixels come from its GL surface. */
public final class PopupCoordinateProbe {
    private static final class Browser extends TethiumOsrBrowser {
        final CountDownLatch popup=new CountDownLatch(1),painted=new CountDownLatch(1),created=new CountDownLatch(1);
        volatile Rectangle menu;
        Browser(CefClient client,String url){super(client,url);addOnPaintListener(e->{if(!e.getPopup())painted.countDown();});}
        @Override public void onPopupSize(CefBrowser browser,Rectangle bounds){menu=new Rectangle(bounds);super.onPopupSize(browser,bounds);popup.countDown();}
    }
    public static void main(String[] args)throws Exception{
        Path output=Files.createDirectories(Path.of(".local/popup-coordinate-probe/"+System.currentTimeMillis()).toAbsolutePath());
        int top=args.length>0?Integer.parseInt(args[0]):30;double zoom=args.length>1?Double.parseDouble(args[1]):0;
        var builder=new CefAppBuilder();builder.setInstallDir(Path.of(System.getenv("LOCALAPPDATA"),"Tethium/jcef").toFile());
        builder.getCefSettings().windowless_rendering_enabled=true;builder.getCefSettings().root_cache_path=output.resolve("profile").toString();
        builder.getCefSettings().log_file=output.resolve("browser.log").toString();builder.setProgressHandler((state,progress)->{});
        CefApp app=builder.build();CefClient client=app.createClient();var frame=new JFrame("Tethium select verification");Browser browser=null;int exit=0;
        try{
            Files.writeString(output.resolve("index.html"),"<!doctype html><style>body{margin:0;background:#202124}select{position:absolute;left:40px;top:"+top+"px;width:240px;height:36px;font:18px sans-serif}option{font:18px sans-serif}</style><select id=s></select><script>for(let i=0;i<30;i++)s.add(new Option('Option '+i,i))</script>");
            try(var server=new ArtifactServer(output)){
                browser=new Browser(client,server.uri("index.html").toString());Browser b=browser;
                client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter(){@Override public void onAfterCreated(CefBrowser value){b.created.countDown();}});
                SwingUtilities.invokeAndWait(()->{frame.setAutoRequestFocus(false);frame.setFocusableWindowState(false);frame.setBounds(-10000,-10000,650,480);frame.add(b.getUIComponent());frame.setVisible(true);frame.validate();b.createImmediately();});
                if(!b.created.await(15,TimeUnit.SECONDS))throw new AssertionError("Browser not created");
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean ready=false;
                while(System.nanoTime()<deadline){try{if(evaluate(b,"!!document.querySelector('select')").asBoolean()){ready=true;break;}}catch(Exception ignored){}Thread.sleep(50);}
                if(!ready||!b.painted.await(5,TimeUnit.SECONDS))throw new AssertionError("Fixture did not paint");
                b.setZoomLevel(zoom);Thread.sleep(200);
                b.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate",Json.object("expression","document.querySelector('select').showPicker()","userGesture",true).toString()).get(3,TimeUnit.SECONDS);
                if(!b.popup.await(3,TimeUnit.SECONDS))throw new AssertionError("No native select popup");
                Rectangle menu=b.menu;int x=menu.x+menu.width/2,checked=0;
                var points=Json.MAPPER.createArrayNode();
                for(int y=menu.y+25;y<Math.min(b.getUIComponent().getHeight()-10,menu.y+menu.height-10);y+=40){
                    int row=y;SwingUtilities.invokeAndWait(()->{var c=b.getUIComponent();c.dispatchEvent(new MouseEvent(c,MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,x,row,0,0,0,false,MouseEvent.NOBUTTON));});
                    Thread.sleep(100);var image=b.createScreenshot(false).get(3,TimeUnit.SECONDS);int min=-1,max=-1;
                    for(int scan=0;scan<image.getHeight();scan++){Color color=new Color(image.getRGB(x,scan));if(color.getBlue()>150&&color.getBlue()>color.getRed()*1.6&&color.getBlue()>color.getGreen()*1.2){if(min<0)min=scan;max=scan;}}
                    if(checked++==2)ImageIO.write(image,"png",output.resolve("hover.png").toFile());
                    points.add(Json.object("mouseY",y,"highlightTop",min,"highlightBottom",max));
                    if(min<0||y<min-1||y>max+1)throw new AssertionError("Select highlight misses pointer: y="+y+", highlight="+min+".."+max);
                }
                if(checked<3)throw new AssertionError("Too few select rows checked");
                int y=menu.y+Math.min(menu.height-20,95);SwingUtilities.invokeAndWait(()->{
                    var c=b.getUIComponent();c.dispatchEvent(new MouseEvent(c,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),java.awt.event.InputEvent.BUTTON1_DOWN_MASK,x,y,0,0,1,false,MouseEvent.BUTTON1));
                    c.dispatchEvent(new MouseEvent(c,MouseEvent.MOUSE_RELEASED,System.currentTimeMillis(),0,x,y,0,0,1,false,MouseEvent.BUTTON1));
                });Thread.sleep(100);int selected=evaluate(b,"document.querySelector('select').selectedIndex").asInt();if(selected<=0)throw new AssertionError("Click did not select a different option");
                var info=new CefScreenInfo();b.getScreenInfo(b,info);
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(),Json.object("passed",true,"deviceScale",info.device_scale_factor,"zoomLevel",zoom,"selectTop",top,"hoverPoints",points,"selectedIndex",selected));
                System.out.println("POPUP_COORDINATES_PASSED scale="+info.device_scale_factor+" zoom="+zoom+" top="+top+" points="+checked+" "+output);
            }
        }catch(Throwable error){error.printStackTrace();exit=1;}finally{
            Browser b=browser;SwingUtilities.invokeAndWait(()->{if(b!=null)b.close(true);frame.dispose();});client.dispose();app.dispose();
        }
        System.exit(exit);
    }
    private static com.fasterxml.jackson.databind.JsonNode evaluate(CefBrowser browser,String expression)throws Exception{
        var result=browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate",Json.object("expression",expression,"returnByValue",true).toString()).get(3,TimeUnit.SECONDS);
        return Json.parse(result).path("result").path("value");
    }
}

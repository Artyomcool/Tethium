package dev.tethium.probe;

import dev.tethium.desktop.*;
import dev.tethium.json.Json;
import org.cef.browser.CefBrowser;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Native keyboard routing through a real Chromium view, with no connection to production data. */
public final class FocusProbe {
    private static JFrame window;private static JTextArea input;private static ArtifactView view;
    private static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void until(Callable<Boolean> condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(System.nanoTime()<end){if(condition.call())return;Thread.sleep(100);}throw new AssertionError("Focus probe timed out");}
    private static CefBrowser browser()throws Exception{return (CefBrowser)field(view,"browser");}
    private static String js(String expression)throws Exception{String response=browser().getDevToolsClient().executeDevToolsMethod("Runtime.evaluate",Json.object("expression",expression,"returnByValue",true).toString()).get(10,TimeUnit.SECONDS);return Json.parse(response).path("result").path("value").asText();}
    private static void click(Robot robot,Component c,int x,int y)throws Exception{var point=new Point[1];SwingUtilities.invokeAndWait(()->point[0]=c.getLocationOnScreen());robot.mouseMove(point[0].x+x,point[0].y+y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);Thread.sleep(200);}
    private static void key(Robot robot,int code){if(!window.isFocused())throw new AssertionError("Test window lost focus; no keys sent");robot.keyPress(code);robot.keyRelease(code);}
    private static void shortcut(Robot robot,int modifier,int code){if(!window.isFocused())throw new AssertionError("Test window lost focus");robot.keyPress(modifier);try{key(robot,code);}finally{robot.keyRelease(modifier);}}
    public static void main(String[] args)throws Exception{
        Path out=Files.createDirectories(Path.of(".local/focus-probe/"+java.util.UUID.randomUUID()));System.setProperty("tethium.data",out.toAbsolutePath().toString());Theme.install();
        Path html=Files.writeString(out.resolve("focus.html"),"<!doctype html><meta charset=utf-8><style>body{margin:20px;background:#252830;color:white;font:18px sans-serif}textarea{width:70%;height:90px}button{margin-top:20px}</style><textarea id=web autofocus></textarea><p>Chromium ↔ Swing keyboard test</p><script>setInterval(()=>document.getElementById('web').focus(),250)</script>");
        var clipboard=Toolkit.getDefaultToolkit().getSystemClipboard();var previous=clipboard.getContents(null);int result=0;
        try{
            SwingUtilities.invokeAndWait(()->{window=new JFrame("Tethium · проверка клавиатуры");view=new ArtifactView();input=new JTextArea(5,30);input.setBorder(BorderFactory.createTitledBorder("Swing composer"));window.add(view);window.add(input,BorderLayout.SOUTH);window.setSize(860,620);window.setLocationRelativeTo(null);window.setAlwaysOnTop(true);window.setVisible(true);view.open(html);});
            until(()->browser()!=null&&browser().getIdentifier()>0);until(()->"complete".equals(js("document.readyState")));
            var robot=new Robot();robot.setAutoDelay(70);
            click(robot,input,30,40);key(robot,KeyEvent.VK_1);key(robot,KeyEvent.VK_2);key(robot,KeyEvent.VK_3);Thread.sleep(400);if(!input.getText().equals("123"))throw new AssertionError("Initial typing went to HTML: "+input.getText());
            click(robot,browser().getUIComponent(),45,45);shortcut(robot,KeyEvent.VK_CONTROL,KeyEvent.VK_A);key(robot,KeyEvent.VK_4);key(robot,KeyEvent.VK_5);key(robot,KeyEvent.VK_6);until(()->"456".equals(js("document.getElementById('web').value")));
            click(robot,input,30,40);shortcut(robot,KeyEvent.VK_CONTROL,KeyEvent.VK_A);shortcut(robot,KeyEvent.VK_CONTROL,KeyEvent.VK_C);key(robot,KeyEvent.VK_END);shortcut(robot,KeyEvent.VK_CONTROL,KeyEvent.VK_V);Thread.sleep(400);if(!input.getText().equals("123123"))throw new AssertionError("Copy/paste after HTML focus failed: "+input.getText());if(!js("document.getElementById('web').value").equals("456"))throw new AssertionError("Composer keys leaked into HTML");
            var old=browser();SwingUtilities.invokeAndWait(view::reload);until(()->browser()!=null&&browser()!=old&&browser().getIdentifier()>0);until(()->"complete".equals(js("document.readyState")));click(robot,input,30,40);key(robot,KeyEvent.VK_END);key(robot,KeyEvent.VK_7);Thread.sleep(400);if(!input.getText().equals("1231237"))throw new AssertionError("Typing after reload failed");
            Json.MAPPER.writeValue(out.resolve("report.json").toFile(),Json.object("passed",true,"nativeTyping",true,"htmlTyping",true,"htmlAutofocusCannotStealComposer",true,"clipboardAfterHtmlFocus",true,"reloadPreservesTyping",true));System.out.println("FOCUS_PROBE_PASSED "+out.toAbsolutePath());
        }catch(Throwable failure){failure.printStackTrace();result=1;}finally{try{if(previous!=null)clipboard.setContents(previous,null);}catch(Exception ignored){}SwingUtilities.invokeAndWait(()->{if(view!=null)view.close();if(window!=null)window.dispose();});}System.exit(result);
    }
}

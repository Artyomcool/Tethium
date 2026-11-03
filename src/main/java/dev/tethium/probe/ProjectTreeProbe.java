package dev.tethium.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tethium.desktop.*;
import dev.tethium.host.*;
import dev.tethium.json.Json;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Pixel geometry, activity aggregation and window recreation using the isolated UX host. */
final class ProjectTreeProbe {
    static void indicators(WorkspaceFrame frame,JsonNode state,String id,String other,String project,Path root)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            JsonNode fixture=state.deepCopy();
            for(var session:fixture.path("sessions")){
                var s=(ObjectNode)session;s.put("running",s.path("id").asText().equals(id));
                s.set("attention",Json.object("kind",s.path("id").asText().equals(id)?"question":"finished"));
                if(s.path("id").asText().equals(other))s.put("parentId",id);
            }
            set(frame,"resourceUsage",Json.object("total",1000,"projects",Json.object(project,250),"sessions",Json.object(id,250,other,250),"cpu",Json.object("totalNanos",1000,"projects",Json.object(project,500),"sessions",Json.object(id,500,other,500))));
            call(frame,"rebuildTrees",fixture);
            JTree tree=(JTree)read(frame,"active");var path=tree.getPathForRow(0);tree.expandPath(path);tree.expandRow(1);
            for(int width:new int[]{920,1280}){
                frame.setSize(width,860);frame.validate();
                require(tree.getRowCount()>=3,"Nested session missing");
                require(labelColor(tree,0).equals(Theme.ACCENT)&&labelColor(tree,1).equals(Theme.ACCENT),"Running project/session colors differ");
                var sessionBounds=tree.getRowBounds(1);var nestedBounds=tree.getRowBounds(2);
                BufferedImage expanded=paint(tree);int[] tokens=span(expanded,sessionBounds.y+sessionBounds.height-7,0x9bbcff);
                int[] cpu=span(expanded,sessionBounds.y+sessionBounds.height-3,0x69d9ae);
                require(tokens[0]>=0&&cpu[0]>=0,"Session bars missing");
                require(java.util.Arrays.equals(tokens,span(expanded,nestedBounds.y+nestedBounds.height-7,0x9bbcff)),"Nested session token bar shifted");
                require(java.util.Arrays.equals(cpu,span(expanded,nestedBounds.y+nestedBounds.height-3,0x69d9ae)),"Nested session CPU bar shifted");
                int dot=span(expanded,sessionBounds.y+(sessionBounds.height-7)/2,Theme.AMBER.getRGB())[1];
                require(dot>=0&&dot==span(expanded,nestedBounds.y+(nestedBounds.height-7)/2,Theme.ACCENT.getRGB())[1],"Nested attention dot shifted");
                save(expanded,root.resolve("project-indicators-expanded-"+width+".png"));
                tree.collapsePath(path);BufferedImage collapsed=paint(tree);var bounds=tree.getRowBounds(0);
                require(java.util.Arrays.equals(tokens,span(collapsed,bounds.y+bounds.height-7,0x9bbcff)),"Project token bar differs from session");
                require(java.util.Arrays.equals(cpu,span(collapsed,bounds.y+bounds.height-3,0x69d9ae)),"Project CPU bar differs from session");
                require(dot==span(collapsed,bounds.y+(bounds.height-7)/2,Theme.AMBER.getRGB())[1],"Project question dot shifted or question priority lost");
                save(collapsed,root.resolve("project-indicators-collapsed-"+width+".png"));tree.expandPath(path);
            }
            for(var session:fixture.path("sessions")){((ObjectNode)session).put("running",false);((ObjectNode)session).set("attention",Json.object("kind","finished"));}
            call(frame,"rebuildTrees",fixture);tree.collapseRow(0);
            require(labelColor(tree,0).equals(Theme.TEXT),"Idle project keeps active color");
            var bounds=tree.getRowBounds(0);require(span(paint(tree),bounds.y+(bounds.height-7)/2,Theme.ACCENT.getRGB())[1]>=0,"Project result dot missing");
            call(frame,"rebuildTrees",state);
            require(!tree.isExpanded(tree.getPathForRow(0)),"Refresh reopened selected session's project");
        });
    }
    static void restoration(WorkspaceFrame frame,HostService host,String id,String project,Path root)throws Exception {
        SwingUtilities.invokeAndWait(()->{var tree=(JTree)read(frame,"active");tree.collapseRow(0);});
        Path views=root.resolve("data/session-views.json");
        await(()->{try{return Json.MAPPER.readTree(views.toFile()).path("$collapsedProjects").toString().contains("active:"+project);}catch(Exception e){return false;}},"collapse saved on disk");
        var savedFuture=new AtomicReference<CompletableFuture<JsonNode>>();
        SwingUtilities.invokeAndWait(()->savedFuture.set(frame.prepareHandoff()));JsonNode saved=savedFuture.get().get(5,TimeUnit.SECONDS);
        var restarted=new WorkspaceFrame[1];
        try{
            SwingUtilities.invokeAndWait(()->{frame.setEnabled(true);restarted[0]=new WorkspaceFrame(new HostClient(host.descriptor()),()->{},id);restarted[0].addNotify();restarted[0].validate();});
            await(()->id.equals(restarted[0].selectedSession())&&restarted[0].reloadReadiness().path("ready").asBoolean(),"cold window ready");
            SwingUtilities.invokeAndWait(()->{
                var tree=(JTree)read(restarted[0],"active");require(!tree.isExpanded(tree.getPathForRow(0)),"Cold restart reopened project");
                tree.expandRow(0);require(tree.isExpanded(tree.getPathForRow(0)),"Expanded state unavailable");restarted[0].adoptHandoff(saved);
            });
            await(()->read(restarted[0],"pendingHandoff")==null,"handoff applied");
            SwingUtilities.invokeAndWait(()->{var tree=(JTree)read(restarted[0],"active");require(!tree.isExpanded(tree.getPathForRow(0)),"Handoff reopened project");});
        }finally{if(restarted[0]!=null)SwingUtilities.invokeAndWait(restarted[0]::closeWindow);}
    }
    private static Color labelColor(JTree tree,int row){var path=tree.getPathForRow(row);var c=(Container)tree.getCellRenderer().getTreeCellRendererComponent(tree,path.getLastPathComponent(),false,tree.isExpanded(path),false,row,false);return ((JLabel)c.getComponent(0)).getForeground();}
    private static BufferedImage paint(JTree tree){var image=new BufferedImage(tree.getWidth(),tree.getHeight(),BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();tree.printAll(g);g.dispose();return image;}
    private static int[] span(BufferedImage image,int y,int color){int min=-1,max=-1;for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)==(color&0xffffff)){if(min<0)min=x;max=x;}return new int[]{min,max};}
    private static void save(BufferedImage image,Path path){try{ImageIO.write(image,"png",path.toFile());}catch(Exception e){throw new RuntimeException(e);}}
    private static Object read(Object o,String name){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
    private static void set(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(Exception e){throw new RuntimeException(e);}}
    private static void call(Object o,String name,JsonNode state){try{var m=o.getClass().getDeclaredMethod(name,JsonNode.class);m.setAccessible(true);m.invoke(o,state);}catch(Exception e){throw new RuntimeException(e);}}
    private static void await(java.util.function.BooleanSupplier condition,String message)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(80);}throw new AssertionError(message);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}

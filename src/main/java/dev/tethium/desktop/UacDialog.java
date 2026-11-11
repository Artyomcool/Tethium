package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import javax.swing.*;
import java.awt.*;

/**
 * A request for administrator rights on the laptop, answered from Tethium Remote. It shows the real prompt the helper read from the
 * secure desktop, with its own buttons; nothing is decided or labelled here. A press is sent once and never replayed.
 */
final class UacDialog {
    private final JFrame owner;private final HostClient host;
    private JDialog dialog;private String shown="";private volatile boolean busy;
    private UacDialog(JFrame owner,HostClient host){this.owner=owner;this.host=host;}
    static void watch(JFrame owner,HostClient host){
        var watcher=new UacDialog(owner,host);
        Thread.ofVirtual().name("uac-watch").start(()->{
            while(true){
                try{Thread.sleep(2000);}catch(InterruptedException e){return;}
                if(!owner.isDisplayable())return;
                try{var state=host.get("/uac");SwingUtilities.invokeLater(()->watcher.render(state));}catch(Exception ignored){}
            }
        });
    }
    private void render(JsonNode state){
        boolean pending=state.path("available").asBoolean()&&state.path("pending").asBoolean();
        if(!pending){close();return;}
        String signature=state.toString()+busy;if(signature.equals(shown)&&dialog!=null)return;shown=signature;
        var panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));panel.setBorder(BorderFactory.createEmptyBorder(16,18,16,18));
        String title=state.path("title").asText();
        var head=new JLabel("<html><body style='width:420px'><b>"+Theme.escape(title.isBlank()?"Запрос прав администратора":title)+"</b></body></html>");head.setForeground(Theme.AMBER);panel.add(head);panel.add(Box.createVerticalStrut(10));
        for(var text:state.path("texts")){String value=text.asText();if(value.isBlank()||value.equals(title))continue;panel.add(new JLabel("<html><body style='width:420px'>"+Theme.escape(value)+"</body></html>"));panel.add(Box.createVerticalStrut(6));}
        var actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));
        if(state.path("credentials").asBoolean())panel.add(note("Windows запрашивает учётные данные. Ответьте на ноутбуке."));
        else if(!state.path("actions").isArray()||state.path("actions").isEmpty())panel.add(note("Кнопки этого окна недоступны. Ответьте на ноутбуке."));
        else for(var action:state.path("actions")){
            String request=state.path("id").asText(),id=action.path("id").asText();
            var button=Theme.button(action.path("label").asText(),"Нажать эту кнопку в окне на ноутбуке",()->answer(request,id));button.setEnabled(!busy);actions.add(button);
        }
        if(dialog==null){dialog=new JDialog(owner,"Права администратора на ноутбуке",Dialog.ModalityType.MODELESS);dialog.setAlwaysOnTop(true);dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);}
        var content=new JPanel(new BorderLayout());content.add(panel);actions.setBorder(BorderFactory.createEmptyBorder(0,18,16,18));content.add(actions,BorderLayout.SOUTH);
        dialog.setContentPane(content);dialog.pack();if(!dialog.isVisible()){dialog.setLocationRelativeTo(owner);dialog.setVisible(true);}dialog.toFront();
    }
    private static JLabel note(String text){var label=new JLabel(text);label.setForeground(Theme.AMBER);return label;}
    private void answer(String request,String action){
        if(busy)return;busy=true;shown="";
        Thread.ofVirtual().start(()->{
            try{host.post("/uac/answer",Json.object("id",request,"action",action));}
            catch(Exception e){SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(dialog,"Ответ не передан: "+e.getMessage(),"Права администратора",JOptionPane.WARNING_MESSAGE));}
            finally{busy=false;}
        });
    }
    private void close(){shown="";if(dialog!=null){dialog.dispose();dialog=null;}}
}

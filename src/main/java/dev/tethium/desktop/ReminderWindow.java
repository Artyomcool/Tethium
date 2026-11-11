package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * Reminders that are due, on top of everything until the user answers: snooze for 10 minutes, an hour, two hours, a day, or done.
 * The window has no owner, so it stays when Tethium is in the tray, and closing it without an answer only brings it back.
 * The same window serves the desktop and Tethium Remote: both read the core's state.
 */
final class ReminderWindow {
    private record Snooze(String label,int minutes){}
    private static final List<Snooze> SNOOZES=List.of(new Snooze("10 мин",10),new Snooze("1 час",60),new Snooze("2 часа",120),new Snooze("Сутки",1440));
    private final JFrame owner;private final HostClient host;private final String device;
    private List<JsonNode> active=List.of();
    private final Set<String> busy=new HashSet<>(),answered=new HashSet<>(),announced=new HashSet<>();
    private JDialog alert;private String shown="";
    private TrayIcon ownIcon;
    private JDialog list;private DefaultTableModel listModel;private JTable listTable;private List<JsonNode> listRows=List.of();
    private final javax.swing.Timer nag=new javax.swing.Timer(30_000,e->raise());
    private boolean closed;

    ReminderWindow(JFrame owner,HostClient host){this.owner=owner;this.host=host;device=host.remote()?"remote":"desktop";}

    /** Called with every state the window receives; an older core sends no reminders and shows none. */
    void update(JsonNode reminders){
        if(closed)return;
        var rows=new ArrayList<JsonNode>();if(reminders!=null&&reminders.isArray())reminders.forEach(rows::add);
        active=rows;
        var keys=new HashSet<String>();for(var r:rows)keys.add(key(r));answered.retainAll(keys);announced.retainAll(keys);
        // A window that is being prepared for an update stays invisible and silent; the one in use answers for both.
        var firing=presenting()?firing():List.<JsonNode>of();
        for(var r:firing)if(announced.add(key(r)))tray("Напоминание",r.path("text").asText());
        render(firing);fillList();
    }
    private boolean presenting(){
        if(owner.isVisible())return true;
        try{if(SystemTray.isSupported())for(var icon:SystemTray.getSystemTray().getTrayIcons())if(icon!=ownIcon)return true;}catch(Exception ignored){}
        return false;
    }
    private List<JsonNode> firing(){var result=new ArrayList<JsonNode>();for(var r:active)if(r.path("firing").asBoolean()&&!answered.contains(key(r)))result.add(r);return result;}
    private static String key(JsonNode r){return r.path("id").asText()+"@"+r.path("dueAt").asLong();}

    private void render(List<JsonNode> firing){
        if(firing.isEmpty()){hideAlert();return;}
        String signature=firing.toString()+busy;if(signature.equals(shown)&&alert!=null&&alert.isVisible())return;shown=signature;
        boolean fresh=alert==null;
        if(fresh){
            alert=new JDialog((Frame)null,"Tethium · напоминание",Dialog.ModalityType.MODELESS);alert.setIconImages(AppIcon.images());alert.setAlwaysOnTop(true);
            alert.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            alert.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){Toolkit.getDefaultToolkit().beep();raise();}});
        }
        var panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));panel.setBorder(BorderFactory.createEmptyBorder(16,18,14,18));
        var head=new JLabel(firing.size()==1?"Напоминание":"Напоминания: "+firing.size());head.setForeground(Theme.AMBER);head.setAlignmentX(0);panel.add(head);
        for(var r:firing){
            panel.add(Box.createVerticalStrut(10));
            var text=new JLabel("<html><body style='width:440px;font-size:15pt'><b>"+Theme.escape(r.path("text").asText())+"</b></body></html>");text.setAlignmentX(0);panel.add(text);
            var meta=new JLabel(meta(r));meta.setForeground(Theme.MUTED);meta.setAlignmentX(0);panel.add(Box.createVerticalStrut(4));panel.add(meta);
            var actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));actions.setAlignmentX(0);actions.setOpaque(false);
            boolean waiting=busy.contains(r.path("id").asText());
            actions.add(new JLabel("Отложить:"));
            for(var s:SNOOZES){var b=Theme.button(s.label(),"Напомнить снова через "+s.label(),()->answer(r,"snooze",s.minutes()));b.setEnabled(!waiting);actions.add(b);}
            var done=Theme.button("Готово","Отметить сделанным и больше не напоминать",()->answer(r,"done",0));Theme.primary(done);done.setEnabled(!waiting);actions.add(Box.createHorizontalStrut(10));actions.add(done);
            panel.add(Box.createVerticalStrut(10));panel.add(actions);
        }
        var scroll=new JScrollPane(panel);scroll.setBorder(null);
        alert.setContentPane(scroll);alert.pack();
        var screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        alert.setSize(Math.min(alert.getWidth()+20,screen.width-40),Math.min(alert.getHeight()+4,screen.height-80));
        if(!alert.isVisible()){alert.setLocation(screen.x+(screen.width-alert.getWidth())/2,screen.y+Math.max(40,screen.height/5));alert.setVisible(true);Toolkit.getDefaultToolkit().beep();}
        if(fresh)raise();
        if(!nag.isRunning())nag.start();
    }
    private String meta(JsonNode r){
        String session=r.path("sessionTitle").asText();int snoozes=r.path("snoozes").asInt();
        return "Назначено на "+r.path("due").asText()+(session.isBlank()?"":" · из сессии «"+session+"»")+(snoozes>0?" · откладывалось "+snoozes+" "+times(snoozes):"");
    }
    private static String times(int n){int m=n%100;return m>=11&&m<=14?"раз":n%10>=2&&n%10<=4?"раза":"раз";}
    private void raise(){if(alert==null||!alert.isVisible())return;alert.setAlwaysOnTop(true);alert.toFront();alert.requestFocus();}
    private void hideAlert(){nag.stop();shown="";if(alert!=null){alert.dispose();alert=null;}if(ownIcon!=null){SystemTray.getSystemTray().remove(ownIcon);ownIcon=null;}}

    private void answer(JsonNode r,String action,int minutes){
        String id=r.path("id").asText();if(!busy.add(id))return;shown="";render(firing());
        var body=Json.object("by",device,"at",System.currentTimeMillis(),"dueAt",r.path("dueAt").asLong());if(minutes>0)body.put("minutes",minutes);
        Thread.ofVirtual().start(()->{
            try{host.post("/reminders/"+id+"/"+action,body);SwingUtilities.invokeLater(()->{busy.remove(id);if(!action.equals("remove"))answered.add(key(r));shown="";render(firing());});}
            catch(Exception e){SwingUtilities.invokeLater(()->{busy.remove(id);shown="";render(firing());JOptionPane.showMessageDialog(alert!=null?alert:owner,"Ответ не передан: "+e.getMessage(),"Напоминание",JOptionPane.WARNING_MESSAGE);});}
        });
    }

    /** A tray message for each new firing; Remote and a visible window have no tray icon of their own, so one is added while needed. */
    private void tray(String title,String text){
        if(!SystemTray.isSupported())return;
        try{
            var tray=SystemTray.getSystemTray();var icons=tray.getTrayIcons();TrayIcon icon;
            if(icons.length>0&&icons[0]!=ownIcon)icon=icons[0];
            else{if(ownIcon==null){ownIcon=new TrayIcon(AppIcon.images().get(2),"Tethium · напоминание");ownIcon.setImageAutoSize(true);ownIcon.addActionListener(e->SwingUtilities.invokeLater(this::raise));tray.add(ownIcon);}icon=ownIcon;}
            icon.displayMessage(title,text,TrayIcon.MessageType.WARNING);
        }catch(Exception ignored){}
    }

    /** Every active reminder: mark one done or delete it. */
    void openList(){
        if(list!=null){list.toFront();return;}
        list=new JDialog(owner,"Напоминания",Dialog.ModalityType.MODELESS);
        listModel=new DefaultTableModel(new Object[]{"Когда","Напоминание","Сессия","Состояние"},0){public boolean isCellEditable(int r,int c){return false;}};
        listTable=new JTable(listModel);listTable.setRowHeight(28);listTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        var panel=new JPanel(new BorderLayout(10,10));panel.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        var actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        var done=Theme.button("Готово","Отметить выбранное сделанным",()->{var r=selectedRow();if(r!=null)answer(r,"done",0);});Theme.primary(done);
        var remove=Theme.button("Удалить","Удалить выбранное напоминание",()->{var r=selectedRow();if(r!=null&&JOptionPane.showConfirmDialog(list,"Удалить напоминание «"+r.path("text").asText()+"»?","Напоминания",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)answer(r,"remove",0);});
        actions.add(remove);actions.add(done);
        var hint=new JLabel("Новое напоминание — сообщением в любой сессии: «напомни через 2 часа позвонить маме».");hint.setForeground(Theme.MUTED);
        var bottom=new JPanel(new BorderLayout());bottom.add(hint,BorderLayout.WEST);bottom.add(actions,BorderLayout.EAST);
        panel.add(new JScrollPane(listTable));panel.add(bottom,BorderLayout.SOUTH);
        list.setContentPane(panel);list.setSize(900,380);list.setLocationRelativeTo(owner);list.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        list.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){list=null;listModel=null;listTable=null;}});
        listRows=List.of();fillList();list.setVisible(true);
    }
    private JsonNode selectedRow(){int row=listTable==null?-1:listTable.getSelectedRow();if(row<0||row>=listRows.size()){JOptionPane.showMessageDialog(list,"Выберите напоминание","Напоминания",JOptionPane.INFORMATION_MESSAGE);return null;}return listRows.get(row);}
    private void fillList(){
        if(listModel==null)return;
        if(listRows.toString().equals(active.toString()))return;
        String keep=listTable.getSelectedRow()>=0&&listTable.getSelectedRow()<listRows.size()?listRows.get(listTable.getSelectedRow()).path("id").asText():null;
        listRows=List.copyOf(active);listModel.setRowCount(0);
        for(var r:listRows)listModel.addRow(new Object[]{r.path("due").asText(),r.path("text").asText(),r.path("sessionTitle").asText(),r.path("firing").asBoolean()?"Ждёт ответа":r.path("snoozes").asInt()>0?"Отложено":"Запланировано"});
        for(int i=0;i<listRows.size();i++)if(listRows.get(i).path("id").asText().equals(keep))listTable.setRowSelectionInterval(i,i);
    }
    void close(){closed=true;hideAlert();if(list!=null)list.dispose();}
}

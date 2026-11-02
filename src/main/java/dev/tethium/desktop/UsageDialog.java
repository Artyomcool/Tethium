package dev.tethium.desktop;

import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

final class UsageDialog {
    record Choice(String id,String label){public String toString(){return label;}}
    static void open(Window owner,HostClient host,Map<String,JsonNode> projects,Map<String,JsonNode> sessions,String project,String session){
        var dialog=new JDialog(owner,"Расход · токены и CPU",Dialog.ModalityType.MODELESS);var panel=new JPanel(new BorderLayout(12,12));panel.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));
        var periods=new JComboBox<Choice>();var projectPicker=new JComboBox<Choice>();var sessionPicker=new JComboBox<Choice>();var metric=new JComboBox<String>(new String[]{"Токены","CPU · процессорное время"});var selectors=new JPanel(new GridLayout(1,4,8,0));selectors.add(metric);selectors.add(periods);selectors.add(projectPicker);selectors.add(sessionPicker);panel.add(selectors,BorderLayout.NORTH);
        projectPicker.addItem(new Choice("","Все проекты"));for(var p:projects.values())projectPicker.addItem(new Choice(p.path("id").asText(),p.path("title").asText()));
        var summary=new JLabel("Получаем статистику…");var model=new DefaultTableModel(new Object[]{"Проект / сессия","Токены","Доля токенов","CPU · секунды","Доля CPU"},0){public boolean isCellEditable(int r,int c){return false;}};var table=new JTable(model);table.setRowHeight(28);table.setAutoCreateRowSorter(true);
        var graph=new Graph();var center=new JPanel(new BorderLayout(8,8));center.add(summary,BorderLayout.NORTH);var split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,graph,new JScrollPane(table));split.setResizeWeight(.5);center.add(split);panel.add(center);
        var note=new JLabel("Токены Tethium; доля среди учтённых токенов, не процент лимита аккаунта.");note.setForeground(Theme.MUTED);panel.add(note,BorderLayout.SOUTH);
        final boolean[] updating={true};final long[] generation={0};final String[] requestedPeriod={""}; final JsonNode[] latest={Json.object()};
        Runnable refillSessions=()->{sessionPicker.removeAllItems();sessionPicker.addItem(new Choice("","Все сессии"));String pid=((Choice)projectPicker.getSelectedItem()).id;for(var s:sessions.values())if(pid.isEmpty()||s.path("projectId").asText().equals(pid))sessionPicker.addItem(new Choice(s.path("id").asText(),s.path("title").asText()));};refillSessions.run();
        for(int i=0;i<projectPicker.getItemCount();i++)if(projectPicker.getItemAt(i).id.equals(project))projectPicker.setSelectedIndex(i);refillSessions.run();for(int i=0;i<sessionPicker.getItemCount();i++)if(sessionPicker.getItemAt(i).id.equals(session))sessionPicker.setSelectedIndex(i);
        Runnable load=()->{if(updating[0])return;long expected=++generation[0];String pid=((Choice)projectPicker.getSelectedItem()).id,sid=((Choice)sessionPicker.getSelectedItem()).id;String period=periods.getSelectedItem() instanceof Choice c?c.id:requestedPeriod[0];
            Thread.ofVirtual().start(()->{try{var data=host.get("/usage?period="+encode(period)+"&project="+encode(pid)+"&session="+encode(sid));SwingUtilities.invokeLater(()->{if(!dialog.isDisplayable()||generation[0]!=expected)return;updating[0]=true;periods.removeAllItems();periods.addItem(new Choice("all","Всё время"));for(var p:data.path("periods"))periods.addItem(new Choice(p.path("id").asText(),p.path("label").asText()+" · "+date(p.path("start").asLong())+" — "+date(p.path("end").asLong())));for(int i=0;i<periods.getItemCount();i++)if(periods.getItemAt(i).id.equals(data.path("period").asText()))periods.setSelectedIndex(i);updating[0]=false;
                latest[0]=data;summary.setText(String.format(Locale.forLanguageTag("ru"),"%,d токенов · %.2f%% · вход %,d (кэш %,d) · выход %,d",data.path("tokens").asLong(),data.path("share").asDouble(),data.path("input").asLong(),data.path("cached").asLong(),data.path("output").asLong()));model.setRowCount(0);long total=data.path("total").asLong();
                boolean projectRows=pid.isEmpty()&&sid.isEmpty();String group=projectRows?"projects":"sessions";var rows=data.path(group);var cpu=data.path("cpu");var ids=new LinkedHashSet<String>();rows.fieldNames().forEachRemaining(ids::add);cpu.path(group).fieldNames().forEachRemaining(ids::add);
                for(String key:ids){JsonNode value=projectRows?projects.get(key):sessions.get(key);if(value==null||!pid.isEmpty()&&!value.path("projectId").asText().equals(pid)||!sid.isEmpty()&&!sid.equals(key))continue;long n=rows.path(key).asLong(),nanos=cpu.path(group).path(key).asLong(),cpuTotal=cpu.path("totalNanos").asLong();model.addRow(new Object[]{value.path("title").asText(),n,String.format(Locale.ROOT,"%.2f%%",total==0?0:100.0*n/total),Math.round(nanos/1e7)/100.0,String.format(Locale.ROOT,"%.2f%%",cpuTotal==0?0:100.0*nanos/cpuTotal)});}
                graph.cpu=metric.getSelectedIndex()==1;graph.data=graph.cpu?cpu.path("series"):data.path("series");graph.repaint();note.setText(graph.cpu?"CPU: накопленное время дочерних процессов · учёт с "+date(cpu.path("observedSince").asLong())+" · короткие и неатрибутированные процессы могут быть пропущены":"Учтённые токены Tethium · с "+date(data.path("observedSince").asLong())+(data.path("partial").asBoolean()?" · часть старой истории недоступна":""));

            });}catch(Exception e){SwingUtilities.invokeLater(()->{if(dialog.isDisplayable()&&generation[0]==expected)summary.setText(String.valueOf(e.getMessage()).contains("Unknown endpoint")?"Ядро ещё обновляется. Статистика появится после подключения новой версии.":"Не удалось получить статистику: "+e.getMessage());});}});
        };
        metric.addActionListener(e->load.run());
        periods.addActionListener(e->load.run());sessionPicker.addActionListener(e->load.run());projectPicker.addActionListener(e->{if(updating[0])return;updating[0]=true;refillSessions.run();updating[0]=false;load.run();});
        dialog.setContentPane(panel);dialog.setSize(970,650);dialog.setLocationRelativeTo(owner);dialog.setVisible(true);updating[0]=false;load.run();
        var refresh=new javax.swing.Timer(15000,e->load.run());refresh.start();dialog.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent e){refresh.stop();}});dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    }
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private static String date(long ms){return ms<=0?"—":DateTimeFormatter.ofPattern("dd.MM HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));}
    private static final class Graph extends JPanel{
        boolean cpu;JsonNode data=Json.MAPPER.createArrayNode();Graph(){setPreferredSize(new Dimension(600,240));setToolTipText("Накопленный расход за выбранный период");}
        protected void paintComponent(Graphics g){super.paintComponent(g);var p=(Graphics2D)g.create();p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int left=65,top=20,w=Math.max(1,getWidth()-85),h=Math.max(1,getHeight()-55);p.setColor(Theme.MUTED);p.drawString(cpu?"Накопленное CPU-время · секунды":"Накопленные токены",left,15);p.drawLine(left,top+h,left+w,top+h);if(data.isEmpty()){p.drawString("За этот период нет наблюдений",left,top+45);p.dispose();return;}long first=data.get(0).path("at").asLong(),last=data.get(data.size()-1).path("at").asLong(),max=Math.max(1,data.get(data.size()-1).path(cpu?"nanos":"tokens").asLong());int x=left,y=top+h;p.setColor(Theme.ACCENT);p.setStroke(new BasicStroke(2));for(var point:data){int nx=left+(int)((point.path("at").asLong()-first)*w/(double)Math.max(1,last-first)),ny=top+h-(int)(point.path(cpu?"nanos":"tokens").asLong()*h/(double)max);p.drawLine(x,y,nx,ny);p.fillOval(nx-2,ny-2,4,4);x=nx;y=ny;}p.setColor(Theme.MUTED);p.drawString(cpu?String.format(Locale.ROOT,"%.1f",max/1e9):Long.toString(max),3,top+10);p.drawString(date(first),left,top+h+22);p.drawString(date(last),Math.max(left,getWidth()-120),top+h+22);p.dispose();}
    }
}

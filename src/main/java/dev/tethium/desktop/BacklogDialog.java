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
import java.util.concurrent.Callable;

/** The tasks put off for better times: what waits for what, with the general rules of starting them at the bottom. */
final class BacklogDialog {
    private record Choice(String id,String label){public String toString(){return label;}}
    private static final List<Choice> PRIORITIES=List.of(new Choice("high","Высокий"),new Choice("normal","Обычный"),new Choice("low","Низкий"));
    private final JDialog dialog;private final HostClient host;private final Map<String,JsonNode> projects,sessions;private final String currentSession;
    private final JComboBox<Choice> projectPicker=new JComboBox<>();
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{"Приоритет","Задача","Проект","Сессия","Условия","Состояние"},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(model);
    private final JSpinner maxRunning=new JSpinner(new SpinnerNumberModel(3,1,32,1)),minQuota=new JSpinner(new SpinnerNumberModel(80,0,100,5)),minFreeCpu=new JSpinner(new SpinnerNumberModel(40,0,100,5));
    private final JCheckBox paused=new JCheckBox("Пауза автозапуска");
    private List<JsonNode> tasks=List.of();private boolean settingsLoaded;private boolean filling;private long generation;

    static void open(Window owner,HostClient host,Map<String,JsonNode> projects,Map<String,JsonNode> sessions,String project,String currentSession){new BacklogDialog(owner,host,projects,sessions,project,currentSession);}
    private BacklogDialog(Window owner,HostClient host,Map<String,JsonNode> projects,Map<String,JsonNode> sessions,String project,String currentSession){
        this.host=host;this.projects=projects;this.sessions=sessions;this.currentSession=currentSession;
        dialog=new JDialog(owner,"Отложенные задачи",Dialog.ModalityType.MODELESS);
        var panel=new JPanel(new BorderLayout(10,10));panel.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        projectPicker.addItem(new Choice("","Все проекты"));for(var p:projects.values())projectPicker.addItem(new Choice(p.path("id").asText(),p.path("title").asText()));
        for(int i=0;i<projectPicker.getItemCount();i++)if(projectPicker.getItemAt(i).id().equals(project))projectPicker.setSelectedIndex(i);
        projectPicker.addActionListener(e->fill());
        var top=new JPanel(new BorderLayout(8,0));top.add(projectPicker,BorderLayout.WEST);
        var actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        var edit=Theme.button("Изменить…","Изменить выбранную задачу",this::editSelected);var start=Theme.button("Запустить сейчас","Запустить, не дожидаясь условий; встанет в очередь сессии",this::startSelected);var remove=Theme.button("Удалить","Убрать задачу из списка",this::removeSelected);
        var add=Theme.button("Добавить…","Отложить новую задачу",()->edit(null));Theme.primary(add);
        actions.add(edit);actions.add(start);actions.add(remove);actions.add(add);top.add(actions);panel.add(top,BorderLayout.NORTH);
        table.setRowHeight(28);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.getColumnModel().getColumn(0).setMaxWidth(90);
        table.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)editSelected();}});
        panel.add(new JScrollPane(table));
        var rules=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));
        rules.add(new JLabel("Одновременно:"));rules.add(maxRunning);rules.add(new JLabel("По умолчанию квота от, %:"));rules.add(minQuota);rules.add(new JLabel("свободно CPU от, %:"));rules.add(minFreeCpu);rules.add(paused);
        panel.add(rules,BorderLayout.SOUTH);
        for(var spinner:List.of(maxRunning,minQuota,minFreeCpu))spinner.addChangeListener(e->saveSettings());
        paused.addActionListener(e->saveSettings());
        dialog.setContentPane(panel);dialog.setSize(980,480);dialog.setLocationRelativeTo(owner);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setVisible(true);
        load();
        var refresh=new javax.swing.Timer(5000,e->load());refresh.start();
        dialog.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){refresh.stop();}});
    }
    private void load(){
        long expected=++generation;
        Thread.ofVirtual().start(()->{try{var data=host.get("/backlog");SwingUtilities.invokeLater(()->{if(!dialog.isDisplayable()||expected!=generation)return;
            var rows=new ArrayList<JsonNode>();data.path("tasks").forEach(rows::add);tasks=rows;fill();
            if(!settingsLoaded){settingsLoaded=true;filling=true;var s=data.path("settings");maxRunning.setValue(s.path("maxRunning").asInt());minQuota.setValue(s.path("minQuota").asInt());minFreeCpu.setValue(s.path("minFreeCpu").asInt());paused.setSelected(s.path("paused").asBoolean());filling=false;}
        });}catch(Exception e){SwingUtilities.invokeLater(()->{if(dialog.isDisplayable()&&expected==generation&&!settingsLoaded)dialog.setTitle("Отложенные задачи · нет связи: "+e.getMessage());});}});
    }
    private void fill(){
        String keep=selected()==null?null:selected().path("id").asText();model.setRowCount(0);String project=((Choice)projectPicker.getSelectedItem()).id();
        visible.clear();
        for(var t:tasks){
            if(!project.isEmpty()&&!project.equals(t.path("projectId").asText()))continue;
            visible.add(t);var owner=projects.get(t.path("projectId").asText());
            model.addRow(new Object[]{label(t.path("priority").asText()),t.path("title").asText(),owner==null?"":owner.path("title").asText(),t.path("sessionName").asText(),conditions(t),t.path("why").asText()});
        }
        for(int i=0;i<visible.size();i++)if(visible.get(i).path("id").asText().equals(keep))table.setRowSelectionInterval(i,i);
    }
    private final List<JsonNode> visible=new ArrayList<>();
    private JsonNode selected(){int row=table.getSelectedRow();return row<0||row>=visible.size()?null:visible.get(row);}
    private static String label(String priority){for(var p:PRIORITIES)if(p.id().equals(priority))return p.label();return priority;}
    private static String conditions(JsonNode t){
        int quota=t.path("effectiveMinQuota").asInt(),cpu=t.path("effectiveMinFreeCpu").asInt();
        if(quota==0&&cpu==0)return "Без условий";
        return (quota>0?"квота от "+quota+"%":"")+(quota>0&&cpu>0?", ":"")+(cpu>0?"CPU от "+cpu+"%":"");
    }
    private void act(Callable<?> work){
        Thread.ofVirtual().start(()->{try{work.call();SwingUtilities.invokeLater(this::load);}catch(Exception e){SwingUtilities.invokeLater(()->{if(dialog.isDisplayable())JOptionPane.showMessageDialog(dialog,e.getMessage(),"Отложенные задачи",JOptionPane.WARNING_MESSAGE);load();});}});
    }
    private void saveSettings(){
        if(filling||!settingsLoaded)return;
        var body=Json.object("maxRunning",maxRunning.getValue(),"minQuota",minQuota.getValue(),"minFreeCpu",minFreeCpu.getValue(),"paused",paused.isSelected());
        act(()->host.post("/backlog-settings",body));
    }
    private void startSelected(){var t=selected();if(t!=null)act(()->host.post("/backlog/"+t.path("id").asText()+"/start",Json.object()));}
    private void removeSelected(){
        var t=selected();if(t==null)return;
        if(JOptionPane.showConfirmDialog(dialog,"Удалить задачу «"+t.path("title").asText()+"» из списка?","Отложенные задачи",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)act(()->host.post("/backlog/"+t.path("id").asText()+"/remove",Json.object()));
    }
    private void editSelected(){var t=selected();if(t!=null&&!t.path("state").asText().equals("running"))edit(t);}
    private void edit(JsonNode task){
        String project=task!=null?task.path("projectId").asText():chosenProject();
        if(project==null){JOptionPane.showMessageDialog(dialog,"Выберите проект вверху окна","Отложенные задачи",JOptionPane.INFORMATION_MESSAGE);return;}
        var title=new JTextField(task==null?"":task.path("title").asText(),50);var prompt=new JTextArea(task==null?"":task.path("prompt").asText(),7,50);prompt.setLineWrap(true);prompt.setWrapStyleWord(true);
        var priority=new JComboBox<>(PRIORITIES.toArray(new Choice[0]));priority.setSelectedIndex(1);if(task!=null)for(int i=0;i<PRIORITIES.size();i++)if(PRIORITIES.get(i).id().equals(task.path("priority").asText()))priority.setSelectedIndex(i);
        var inSession=new JRadioButton("В сессии:");var named=new JRadioButton("Общая сессия по имени:");var fresh=new JRadioButton("В новой сессии");var group=new ButtonGroup();group.add(inSession);group.add(named);group.add(fresh);
        var sessionPicker=new JComboBox<Choice>();for(var s:sessions.values())if(s.path("projectId").asText().equals(project))sessionPicker.addItem(new Choice(s.path("id").asText(),s.path("title").asText()));
        String preferred=task!=null?task.path("sessionId").asText():currentSession;for(int i=0;i<sessionPicker.getItemCount();i++)if(sessionPicker.getItemAt(i).id().equals(preferred))sessionPicker.setSelectedIndex(i);
        var sessionName=new JTextField(task!=null&&task.path("target").asText().equals("named")?task.path("sessionTitle").asText():"Мелкие задачи",18);
        String target=task==null?(sessionPicker.getItemCount()>0?"session":"named"):task.path("target").asText();(target.equals("named")?named:target.equals("new")?fresh:inSession).setSelected(true);
        var own=new JCheckBox("Свои условия запуска",task!=null&&(task.path("minQuota").isNumber()||task.path("minFreeCpu").isNumber()));
        JSpinner quota=new JSpinner(new SpinnerNumberModel(task==null?((Number)minQuota.getValue()).intValue():task.path("effectiveMinQuota").asInt(),0,100,5)),cpu=new JSpinner(new SpinnerNumberModel(task==null?((Number)minFreeCpu.getValue()).intValue():task.path("effectiveMinFreeCpu").asInt(),0,100,5));
        Runnable sync=()->{quota.setEnabled(own.isSelected());cpu.setEnabled(own.isSelected());};own.addActionListener(e->sync.run());sync.run();
        var form=new JPanel();form.setLayout(new BoxLayout(form,BoxLayout.Y_AXIS));
        form.add(line(new JLabel("Название:"),title));form.add(Box.createVerticalStrut(6));form.add(line(new JLabel("Что сделать (агент не помнит разговор):")));form.add(new JScrollPane(prompt));form.add(Box.createVerticalStrut(6));
        form.add(line(new JLabel("Приоритет:"),priority));form.add(Box.createVerticalStrut(6));
        form.add(line(inSession,sessionPicker));form.add(line(named,sessionName));form.add(line(fresh));form.add(Box.createVerticalStrut(6));
        form.add(line(own,new JLabel("квота от, %:"),quota,new JLabel("свободно CPU от, %:"),cpu));
        if(JOptionPane.showConfirmDialog(dialog,form,task==null?"Отложить задачу":"Изменить задачу",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
        var body=Json.object("projectId",project,"title",title.getText(),"prompt",prompt.getText(),"priority",((Choice)priority.getSelectedItem()).id(),"target",named.isSelected()?"named":fresh.isSelected()?"new":"session");
        if(inSession.isSelected()&&sessionPicker.getSelectedItem() instanceof Choice c)body.put("sessionId",c.id());
        if(named.isSelected())body.put("sessionTitle",sessionName.getText());
        if(own.isSelected()){body.put("minQuota",((Number)quota.getValue()).intValue());body.put("minFreeCpu",((Number)cpu.getValue()).intValue());}
        if(task==null&&currentSession!=null)body.put("sourceId",currentSession);
        act(()->host.post(task==null?"/backlog":"/backlog/"+task.path("id").asText()+"/update",body));
    }
    private String chosenProject(){
        String id=((Choice)projectPicker.getSelectedItem()).id();if(!id.isEmpty())return id;
        var s=currentSession==null?null:sessions.get(currentSession);return s==null?null:s.path("projectId").asText();
    }
    private static JPanel line(Component...parts){var p=new JPanel(new FlowLayout(FlowLayout.LEFT,6,1));for(var part:parts)p.add(part);p.setAlignmentX(Component.LEFT_ALIGNMENT);return p;}
}

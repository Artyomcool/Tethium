package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import java.awt.*;
import java.util.Locale;

final class CpuSettingsDialog {
    static JsonNode show(Component owner,String scope,String title,JsonNode state,JsonNode row){
        boolean global=scope.equals("global");var settings=row.path("settings");
        var enabled=new JCheckBox("Ограничивать CPU исполнителей и демонов",state.path("enabled").asBoolean(true));
        var inherit=new JCheckBox("Наследовать потолок",settings.path("cap").asInt()==0);
        var cap=new JSpinner(new SpinnerNumberModel(settings.path("cap").asInt()>0?settings.path("cap").asInt():row.path("effectiveCap").asInt(85),1,100,5));
        var priority=new JComboBox<>(global?new String[]{"Фоновый","Обычный","Важный"}:new String[]{"Наследовать","Фоновый","Обычный","Важный"});
        String[] values=global?new String[]{"background","normal","important"}:new String[]{"inherit","background","normal","important"};for(int i=0;i<values.length;i++)if(values[i].equals(settings.path("priority").asText(global?"normal":"inherit")))priority.setSelectedIndex(i);
        var panel=new JPanel(new GridLayout(0,1,0,8));if(global)panel.add(enabled);else panel.add(inherit);
        var cpu=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));cpu.add(new JLabel("Потолок CPU:"));cpu.add(cap);cpu.add(new JLabel("% всего ноутбука"));panel.add(cpu);
        var priorities=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));priorities.add(new JLabel("Приоритет:"));priorities.add(priority);panel.add(priorities);
        cap.setEnabled(global||!inherit.isSelected());inherit.addActionListener(e->cap.setEnabled(!inherit.isSelected()));
        panel.add(new JLabel(String.format(Locale.forLanguageTag("ru"),"Сейчас %.1f%% · действует потолок %d%% · процессов %d",row.path("cpuPercent").asDouble(),row.path("effectiveCap").asInt(85),row.path("processes").asInt())));
        panel.add(new JLabel("Лимит включает дочерние процессы и персональные демоны."));
        if(!global)panel.add(new JLabel("Общий лимит приложения и проекта также действуют."));
        if(global){panel.add(new JLabel("Старых общих Gradle-демонов: "+state.path("sharedDaemons").asInt()));panel.add(new JLabel("GUI и управляющее ядро работают вне этого бюджета."));}
        if(!state.path("uncontrolled").isEmpty()){panel.add(new JLabel("Старые процессы в несовместимых Windows-группах перейдут под лимит после перезапуска."));panel.add(new JLabel("Не удалось включить в лимит: "+state.path("uncontrolledCount").asInt(state.path("uncontrolled").size())+" процессов. "+state.path("uncontrolled").get(0).path("reason").asText()));}
        if(!state.path("supported").asBoolean()){JOptionPane.showMessageDialog(owner,"Управление CPU доступно на Windows-ноутбуке с обновлённым ядром.");return null;}
        if(JOptionPane.showConfirmDialog(owner,panel,"CPU · "+title,JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return null;
        try{cap.commitEdit();}catch(Exception e){return null;}
        return Json.object("enabled",enabled.isSelected(),"cap",!global&&inherit.isSelected()?0:((Number)cap.getValue()).intValue(),"priority",values[priority.getSelectedIndex()]);
    }
}

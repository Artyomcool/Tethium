package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

final class ScheduleDialog {
    private ScheduleDialog(){}
    static String nextTime(JsonNode timer){
        long next=timer.path("nextAt").asLong();
        return next<=0?"—":DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").format(Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault()));
    }
    static JsonNode show(Component owner,JsonNode timer){
        long seconds=timer.path("intervalSeconds").asLong(300);int unit=seconds%3600==0?2:seconds%60==0?1:0;
        long factor=unit==2?3600:unit==1?60:1;
        var interval=new JSpinner(new SpinnerNumberModel((int)(seconds/factor),1,31_536_000,1));
        var units=new JComboBox<>(new String[]{"секунд","минут","часов"});units.setSelectedIndex(unit);
        var prompt=new JTextArea(timer.path("prompt").asText(),7,48);prompt.setLineWrap(true);prompt.setWrapStyleWord(true);
        var row=new JPanel(new FlowLayout(FlowLayout.LEFT));row.add(new JLabel("Повторять каждые"));row.add(interval);row.add(units);
        var panel=new JPanel(new BorderLayout(6,8));panel.add(row,BorderLayout.NORTH);panel.add(new JScrollPane(prompt),BorderLayout.CENTER);
        var info=new JPanel(new GridLayout(0,1,0,4));
        info.add(new JLabel("Один проход за запуск. При занятости повторы не копятся."));
        info.add(new JLabel("Стоп приостанавливает расписание. Закрытие окна его сохраняет."));
        if(timer.path("state").asText().equals("active"))info.add(new JLabel("Следующий запуск: "+nextTime(timer)));
        panel.add(info,BorderLayout.SOUTH);
        while(true){
            int choice=JOptionPane.showOptionDialog(owner,panel,"Периодическая задача",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,new String[]{"Сохранить и включить","Приостановить","Выключить","Отмена"},"Сохранить и включить");
            if(choice==1)return Json.object("action","pause");if(choice==2)return Json.object("action","disable");if(choice!=0)return null;
            try{
                interval.commitEdit();long multiplier=units.getSelectedIndex()==2?3600:units.getSelectedIndex()==1?60:1;
                long every=((Number)interval.getValue()).longValue()*multiplier;
                if(every<1||every>31_536_000)throw new IllegalArgumentException("Интервал: от 1 секунды до 365 дней");
                if(prompt.getText().isBlank()||prompt.getText().length()>16000)throw new IllegalArgumentException("Опишите задачу: от 1 до 16000 символов");
                return Json.object("action","set","intervalSeconds",every,"prompt",prompt.getText());
            }catch(Exception e){JOptionPane.showMessageDialog(owner,e.getMessage(),"Проверьте расписание",JOptionPane.ERROR_MESSAGE);}
        }
    }
}

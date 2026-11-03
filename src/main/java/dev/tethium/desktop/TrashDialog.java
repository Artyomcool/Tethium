package dev.tethium.desktop;

import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import javax.swing.*;
import java.awt.*;

/** Recovery and permanent deletion are deliberately outside the everyday session menu. */
final class TrashDialog {
    static void open(Window owner,HostClient host,Runnable changed){
        var dialog=new JDialog(owner,"Настройки · корзина сессий",Dialog.ModalityType.MODELESS);
        var panel=new JPanel(new BorderLayout(12,12));panel.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));
        var model=new DefaultListModel<JsonNode>();var list=new JList<>(model);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean selected,boolean focus){var label=(JLabel)super.getListCellRendererComponent(l,value,index,selected,focus);var s=(JsonNode)value;label.setText(s.path("projectTitle").asText()+": "+s.path("title").asText()+(s.path("running").asBoolean()?" · работает":""));label.setBorder(BorderFactory.createEmptyBorder(10,8,10,8));return label;}});
        panel.add(new JLabel("Здесь можно восстановить скрытые сессии или удалить их историю окончательно."),BorderLayout.NORTH);panel.add(new JScrollPane(list));
        var actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));var restore=new JButton("Восстановить");var purge=new JButton("Удалить навсегда…");actions.add(restore);actions.add(purge);panel.add(actions,BorderLayout.SOUTH);
        Runnable[] load=new Runnable[1];load[0]=()->{restore.setEnabled(false);purge.setEnabled(false);Thread.ofVirtual().start(()->{try{var rows=host.get("/trash");SwingUtilities.invokeLater(()->{model.clear();rows.forEach(model::addElement);if(!model.isEmpty())list.setSelectedIndex(0);});}catch(Exception error){SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(dialog,error.getMessage()));}});};
        list.addListSelectionListener(e->{var s=list.getSelectedValue();restore.setEnabled(s!=null);purge.setEnabled(s!=null&&!s.path("running").asBoolean());});
        java.util.function.Consumer<Boolean> action=permanent->{var s=list.getSelectedValue();if(s==null)return;
            if(permanent&&JOptionPane.showConfirmDialog(dialog,"Безвозвратно удалить историю сессии «"+s.path("title").asText()+"»?\nВосстановление будет невозможно. Файлы проекта останутся на диске.","Окончательное удаление",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
            restore.setEnabled(false);purge.setEnabled(false);Thread.ofVirtual().start(()->{try{host.post("/sessions/"+s.path("id").asText()+(permanent?"/purge":"/restore"),Json.object("confirmed",true));SwingUtilities.invokeLater(()->{load[0].run();changed.run();});}catch(Exception error){SwingUtilities.invokeLater(()->{JOptionPane.showMessageDialog(dialog,error.getMessage());load[0].run();});}});
        };restore.addActionListener(e->action.accept(false));purge.addActionListener(e->action.accept(true));
        dialog.setContentPane(panel);dialog.setSize(680,420);dialog.setLocationRelativeTo(owner);dialog.setVisible(true);load[0].run();
    }
}

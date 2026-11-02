package dev.tethium.desktop;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Small persisted image attachments beside the draft; removal does not delete history's files. */
final class AttachmentStrip extends JPanel {
    private final Consumer<Path> open, remove;
    private List<Path> paths=List.of(); private int pending=-1;
    AttachmentStrip(Consumer<Path> open,Consumer<Path> remove) {
        super(new BorderLayout());this.open=open;this.remove=remove;setName("composer.images");setOpaque(false);setVisible(false);
    }
    void update(List<Path> files,int processing) {
        if(paths.equals(files)&&pending==processing)return;paths=List.copyOf(files);pending=processing;removeAll();
        var row=new JPanel(new FlowLayout(FlowLayout.LEFT,Theme.UNIT,0));row.setOpaque(false);
        for(var path:paths) {
            var card=new JPanel(new BorderLayout());card.setOpaque(false);var button=new JButton();button.setName("composer.image");Theme.toolbar(button);
            var icon=new ImageIcon(path.toString());
            if(icon.getIconWidth()>0) { double scale=Math.min(50.0/icon.getIconWidth(),42.0/icon.getIconHeight());button.setIcon(new ImageIcon(icon.getImage().getScaledInstance(Math.max(1,(int)(icon.getIconWidth()*scale)),Math.max(1,(int)(icon.getIconHeight()*scale)),Image.SCALE_SMOOTH))); }
            else button.setText("PNG");
            button.setPreferredSize(new Dimension(58,48));button.setToolTipText(path.getFileName()+" · открыть");button.getAccessibleContext().setAccessibleName("Открыть скриншот");button.addActionListener(e->open.accept(path));card.add(button);
            var close=Theme.iconButton("close","Убрать вложение из сообщения",()->remove.accept(path));close.setName("composer.removeImage");card.add(close,BorderLayout.EAST);row.add(card);
        }
        if(processing>0)row.add(new JLabel("Сохраняем изображение…"));
        var scroll=new JScrollPane(row,ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);scroll.setBorder(null);scroll.setOpaque(false);scroll.getViewport().setOpaque(false);scroll.setPreferredSize(new Dimension(0,62));add(scroll);
        setVisible(!paths.isEmpty()||pending>0);revalidate();repaint();
    }
}

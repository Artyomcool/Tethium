package dev.tethium.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** The native tray keeps a minimized window reachable without a taskbar button. */
final class DesktopTray implements AutoCloseable {
    private final JFrame frame;
    private final TrayIcon icon;
    private boolean installed;
    DesktopTray(JFrame frame,Runnable open,Runnable exit){
        this.frame=frame;
        var menu=new PopupMenu();
        var show=new MenuItem("Открыть Tethium");show.addActionListener(e->SwingUtilities.invokeLater(open));menu.add(show);
        var hide=new MenuItem("Свернуть в трей");hide.addActionListener(e->SwingUtilities.invokeLater(this::hideWindow));menu.add(hide);
        menu.addSeparator();var quit=new MenuItem("Закрыть окно Tethium");quit.addActionListener(e->SwingUtilities.invokeLater(exit));menu.add(quit);
        var images=AppIcon.images();icon=new TrayIcon(images.get(2),"Tethium",menu);icon.setImageAutoSize(true);
        icon.addActionListener(e->SwingUtilities.invokeLater(open));
        icon.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(e.getButton()==MouseEvent.BUTTON1&&e.getClickCount()==1)SwingUtilities.invokeLater(open);}});
        frame.addWindowStateListener(e->{if((e.getNewState()&Frame.ICONIFIED)!=0)hideWindow();});
        frame.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){close();}});
    }
    private boolean install(){
        if(installed)return true;
        try{SystemTray.getSystemTray().add(icon);installed=true;return true;}
        catch(AWTException|UnsupportedOperationException|SecurityException e){return false;}
    }
    boolean hideWindow(){if(!install())return false;frame.setVisible(false);return true;}
    void showWindow(){install();frame.setExtendedState(frame.getExtendedState()&~Frame.ICONIFIED);frame.setVisible(true);frame.toFront();}
    boolean hidden(){return installed&&!frame.isVisible();}
    @Override public void close(){if(installed){SystemTray.getSystemTray().remove(icon);installed=false;}}
}

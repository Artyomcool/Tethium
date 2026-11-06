package dev.tethium.desktop;

import dev.tethium.remote.*;
import dev.tethium.json.Json;
import java.nio.file.*;
import javax.swing.*;
import java.awt.*;

/** Entry point of the client-only distribution; never calls HostClient.connect(). */
public final class RemoteMain {
    public static void main(String[] args){System.setProperty("tethium.remote","true");if(System.getProperty("tethium.data")==null)System.setProperty("tethium.data",Path.of(System.getenv("LOCALAPPDATA"),"TethiumRemote").toString());Theme.install();SwingUtilities.invokeLater(()->connect(java.util.Arrays.asList(args).contains("--pair")));}
    static void restartPairing(WorkspaceFrame owner){try{new ProcessBuilder(dev.tethium.host.AppPaths.javaExecutable(true),"--enable-native-access=ALL-UNNAMED","-Dtethium.data="+dev.tethium.host.AppPaths.data(),"-cp",System.getProperty("java.class.path"),RemoteMain.class.getName(),"--pair").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(dev.tethium.host.AppPaths.data().resolve("remote.log").toFile())).start();owner.closeWindow();}catch(Exception failure){JOptionPane.showMessageDialog(owner,failure.getMessage(),"Подключение",JOptionPane.ERROR_MESSAGE);}}
    static void connect(boolean force){connect(force,()->{});}
    static void connect(boolean force,Runnable connected){
        Path data=dev.tethium.host.AppPaths.data(),file=data.resolve("connection.json");
        if(!force&&Files.isRegularFile(file)){Thread.ofVirtual().start(()->{try{var client=new RemoteClient(data,Json.MAPPER.readTree(file.toFile()));SwingUtilities.invokeLater(()->new WorkspaceFrame(client,()->System.exit(0)).setVisible(true));}catch(Exception e){SwingUtilities.invokeLater(()->connect(true));}});return;}
        var dialog=new JFrame("Tethium Remote · подключение");dialog.setIconImages(AppIcon.images());dialog.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);var panel=new JPanel(new BorderLayout(12,12));panel.setBorder(BorderFactory.createEmptyBorder(24,24,24,24));var text=new JTextArea("На ноутбуке откройте «Телефон», скопируйте ссылку привязки и вставьте её сюда. Обе машины должны быть в одной локальной сети.");text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setOpaque(false);panel.add(text,BorderLayout.NORTH);var input=new JTextField();input.putClientProperty("JTextField.placeholderText","tethium://pair?…");panel.add(input);var go=new JButton("Подключить");panel.add(go,BorderLayout.SOUTH);go.addActionListener(e->{go.setEnabled(false);String link=input.getText();Thread.ofVirtual().start(()->{try{var p=LanConnection.pairing(link);String address=LanConnection.httpAddress(p);var connection=new LanConnection(address,null);var token=connection.json("/pair",Json.object("code",p.get("code"),"name","Tethium Remote · "+System.getenv().getOrDefault("COMPUTERNAME","Desktop"))).path("token").asText();var credentials=Json.object("address",address,"token",token);Files.createDirectories(data);Json.MAPPER.writeValue(file.toFile(),credentials);var client=new RemoteClient(data,credentials);client.get("/state");SwingUtilities.invokeLater(()->{dialog.dispose();connected.run();new WorkspaceFrame(client,()->System.exit(0)).setVisible(true);});}catch(Exception failure){SwingUtilities.invokeLater(()->{go.setEnabled(true);JOptionPane.showMessageDialog(dialog,failure.getMessage(),"Подключение",JOptionPane.ERROR_MESSAGE);});}});});dialog.setContentPane(panel);dialog.setSize(570,260);dialog.setLocationRelativeTo(null);dialog.setVisible(true);
    }
}

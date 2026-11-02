package dev.tethium.desktop;

import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.util.Map;

final class MobileConnectDialog {
    static void open(JFrame owner) {
        var dialog=new JDialog(owner,"Подключить Android",false);
        var body=new JPanel(new BorderLayout(16,16));body.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        var message=new JLabel("Подготовка подключения…");body.add(message,BorderLayout.NORTH);dialog.setContentPane(body);dialog.setSize(710,460);dialog.setLocationRelativeTo(owner);dialog.setVisible(true);
        Thread.ofVirtual().start(()->{try(var host=HostClient.connect()){
            var info=host.post("/mobile/pair",Json.object());
            SwingUtilities.invokeLater(()->{if(!dialog.isDisplayable())return;try{
                message.setText("Одна сеть Wi-Fi • привязка действует 5 минут • подключено: "+info.path("devices").asInt());
                var columns=new JPanel(new GridLayout(1,2,24,0));
                columns.add(card("1. Установить APK",info.path("download").asText()));
                columns.add(card("2. Привязать приложение",info.path("pairing").asText()));body.add(columns);
                var footer=new JPanel(new BorderLayout(8,8));
                var address=new JLabel("Ноутбук: "+info.path("address").asText()+" · HTTPS 8765 · APK 8766");footer.add(address,BorderLayout.NORTH);
                var actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));
                var wifi=new JButton("Разрешить Wi-Fi…");wifi.setToolTipText("Windows запросит права администратора для доступа из локальной сети");wifi.addActionListener(e->firewall(message));actions.add(wifi);
                var revoke=new JButton("Отключить все телефоны");revoke.addActionListener(e->{if(JOptionPane.showConfirmDialog(dialog,"Отключить все привязанные телефоны?","Подключение",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Thread.ofVirtual().start(()->{try(var c=HostClient.connect()){c.post("/mobile/revoke",Json.object());SwingUtilities.invokeLater(dialog::dispose);}catch(Exception ex){SwingUtilities.invokeLater(()->message.setText(ex.getMessage()));}});});actions.add(revoke);
                var refresh=new JButton("Новый QR");refresh.addActionListener(e->{dialog.dispose();open(owner);});actions.add(refresh);footer.add(actions);body.add(footer,BorderLayout.SOUTH);dialog.revalidate();dialog.repaint();
            }catch(Exception error){message.setText(error.getMessage());}});
        }catch(Exception error){SwingUtilities.invokeLater(()->message.setText("Не удалось открыть подключение: "+error.getMessage()));}});
    }
    private static JPanel card(String title,String value)throws Exception{
        var panel=new JPanel(new BorderLayout(8,8));panel.add(new JLabel(title,SwingConstants.CENTER),BorderLayout.NORTH);
        var matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,256,256,Map.of(EncodeHintType.MARGIN,2));
        var image=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);for(int y=0;y<256;y++)for(int x=0;x<256;x++)image.setRGB(x,y,matrix.get(x,y)?0xff10151c:0xffffffff);
        panel.add(new JLabel(new ImageIcon(image)));var copy=new JButton("Копировать ссылку");copy.addActionListener(e->Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value),null));panel.add(copy,BorderLayout.SOUTH);return panel;
    }
    private static void firewall(JLabel status){
        status.setText("Windows запрашивает подтверждение настройки доступа по Wi-Fi…");
        Thread.ofVirtual().start(()->{try{
            var file=java.nio.file.Files.createDirectories(dev.tethium.host.AppPaths.data().resolve("mobile")).resolve("enable-firewall.ps1");
            try(var in=MobileConnectDialog.class.getResourceAsStream("/mobile/enable_mobile_firewall.ps1")){if(in==null)throw new java.io.IOException("Скрипт настройки отсутствует");java.nio.file.Files.copy(in,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            String path=file.toString().replace("'","''");
            String command="$ErrorActionPreference='Stop'; $p=Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -PassThru -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \""+path+"\"'; exit $p.ExitCode";
            int exit=new ProcessBuilder("powershell.exe","-NoProfile","-WindowStyle","Hidden","-Command",command).redirectErrorStream(true).redirectOutput(file.resolveSibling("firewall.log").toFile()).start().waitFor();
            SwingUtilities.invokeLater(()->status.setText(exit==0?"Доступ разрешён для частной локальной сети. Можно подключать телефон.":"Настройка не применена. Подробности: mobile/firewall.log"));
        }catch(Exception e){SwingUtilities.invokeLater(()->status.setText("Не удалось настроить Wi-Fi: "+e.getMessage()));}});
    }
}

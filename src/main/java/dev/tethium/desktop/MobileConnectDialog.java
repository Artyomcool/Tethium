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
        var message=new JLabel("Подготовка подключения…");body.add(message,BorderLayout.NORTH);dialog.setContentPane(body);dialog.setSize(760,640);dialog.setLocationRelativeTo(owner);dialog.setVisible(true);
        Thread.ofVirtual().start(()->{try(var host=HostClient.connect()){
            var info=host.post("/mobile/pair",Json.object());
            SwingUtilities.invokeLater(()->{if(!dialog.isDisplayable())return;try{
                var traffic=info.path("traffic");var today=traffic.path("today");message.setText("Домашняя сеть — напрямую • вне дома — Cloudflare E2E • подключено: "+info.path("devices").asInt()+" • CF сегодня ↑ "+bytes(today.path("cloudflareUp").asLong())+" ↓ "+bytes(today.path("cloudflareDown").asLong())+" • Free Worker ≈ "+traffic.path("estimatedWorkerRequests").asLong()+"/100000, DO ≈ "+traffic.path("estimatedDurableRequests").asLong()+"/100000");
                var columns=new JPanel(new GridLayout(1,2,24,0));
                columns.add(card("1. Установить APK",info.path("download").asText()));
                columns.add(card("2. Привязать приложение",info.path("pairing").asText()));body.add(columns);
                var footer=new JPanel(new BorderLayout(8,8));
                footer.add(adbPanel(host.get("/health").path("version").asText(),info),BorderLayout.CENTER);
                var address=new JLabel("Ноутбук: "+info.path("address").asText()+" · канал "+info.path("port").asInt()+" · HTTP "+info.path("httpPort").asInt()+" · без TLS, домашняя сеть");footer.add(address,BorderLayout.NORTH);
                var actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));
                var wifi=new JButton("Разрешить Wi-Fi…");wifi.setToolTipText("Windows запросит права администратора для доступа из локальной сети");wifi.addActionListener(e->firewall(message));actions.add(wifi);
                var revoke=new JButton("Отключить все телефоны");revoke.addActionListener(e->{if(JOptionPane.showConfirmDialog(dialog,"Отключить все привязанные телефоны?","Подключение",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Thread.ofVirtual().start(()->{try(var c=HostClient.connect()){c.post("/mobile/revoke",Json.object());SwingUtilities.invokeLater(dialog::dispose);}catch(Exception ex){SwingUtilities.invokeLater(()->message.setText(ex.getMessage()));}});});actions.add(revoke);
                var refresh=new JButton("Новый QR");refresh.addActionListener(e->{dialog.dispose();open(owner);});actions.add(refresh);footer.add(actions);body.add(footer,BorderLayout.SOUTH);dialog.revalidate();dialog.repaint();
            }catch(Exception error){message.setText(error.getMessage());}});
        }catch(Exception error){SwingUtilities.invokeLater(()->message.setText("Не удалось открыть подключение: "+error.getMessage()));}});
    }
    /** Wireless debugging: pair once, then the laptop installs APKs silently; the store on the phone stays the fallback. */
    private static JPanel adbPanel(String ignored,com.fasterxml.jackson.databind.JsonNode info){
        var panel=new JPanel(new GridBagLayout());panel.setBorder(BorderFactory.createTitledBorder("3. Тихая установка через отладку по Wi-Fi"));
        var c=new GridBagConstraints();c.insets=new Insets(2,4,2,4);c.anchor=GridBagConstraints.WEST;c.fill=GridBagConstraints.HORIZONTAL;
        var hint=new JLabel("<html>На телефоне: Настройки → Для разработчиков → Отладка по Wi-Fi → «Подключить устройство с помощью кода». Введите адрес с кодом один раз; порт подключения берётся с основного экрана «Отладка по Wi-Fi».</html>");
        var status=new JLabel("Проверяем…");status.setForeground(Theme.MUTED);
        var pairAddress=new JTextField(16);pairAddress.putClientProperty("JTextField.placeholderText","192.168.1.5:37123");var code=new JTextField(6);code.putClientProperty("JTextField.placeholderText","код");var pair=new JButton("Спарить");
        var connectAddress=new JTextField(16);connectAddress.putClientProperty("JTextField.placeholderText","192.168.1.5:41234");var connect=new JButton("Подключить");var install=new JButton("Поставить Tethium сейчас");var forget=new JButton("Забыть");
        c.gridx=0;c.gridy=0;c.gridwidth=4;c.weightx=1;panel.add(hint,c);
        c.gridy=1;panel.add(status,c);
        c.gridwidth=1;c.weightx=0;c.gridy=2;c.gridx=0;panel.add(new JLabel("Сопряжение:"),c);c.gridx=1;c.weightx=1;panel.add(pairAddress,c);c.gridx=2;c.weightx=0;panel.add(code,c);c.gridx=3;panel.add(pair,c);
        c.gridy=3;c.gridx=0;panel.add(new JLabel("Подключение:"),c);c.gridx=1;c.weightx=1;panel.add(connectAddress,c);c.gridx=2;c.weightx=0;c.gridwidth=2;panel.add(connect,c);c.gridwidth=1;
        var row=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));row.add(install);row.add(forget);c.gridy=4;c.gridx=0;c.gridwidth=4;panel.add(row,c);
        Runnable refresh=()->Thread.ofVirtual().start(()->{try(var h=HostClient.connect()){var s=h.post("/mobile/adb/status",Json.object());SwingUtilities.invokeLater(()->{String text=!s.path("adbFound").asBoolean()?"adb не найден: "+s.path("adb").asText("нет platform-tools"):!s.path("configured").asBoolean()?"Не настроено · установка идёт через магазин на телефоне":s.path("online").asBoolean()?"Телефон "+s.path("device").asText()+" на связи · Tethium versionCode "+s.path("tethiumVersionCode").asLong():"Настроено ("+s.path("device").asText()+"), сейчас недоступен";String last=s.path("lastResult").asText();status.setText(text+(last.isEmpty()?"":" · "+last));if(connectAddress.getText().isBlank())connectAddress.setText(s.path("device").asText());install.setEnabled(s.path("configured").asBoolean());});}catch(Exception e){SwingUtilities.invokeLater(()->status.setText(e.getMessage()));}});
        java.util.function.BiConsumer<String,com.fasterxml.jackson.databind.JsonNode> call=(path,body)->Thread.ofVirtual().start(()->{try(var h=HostClient.connect()){var r=h.post(path,body);SwingUtilities.invokeLater(()->status.setText(r.path("output").asText(r.toString())));}catch(Exception e){SwingUtilities.invokeLater(()->status.setText(e.getMessage()));}finally{refresh.run();}});
        pair.addActionListener(e->call.accept("/mobile/adb/pair",Json.object("address",pairAddress.getText().trim(),"code",code.getText().trim())));
        connect.addActionListener(e->call.accept("/mobile/adb/connect",Json.object("address",connectAddress.getText().trim())));
        install.addActionListener(e->{status.setText("Ставим…");call.accept("/mobile/adb/install",Json.object("packageName","dev.tethium.mobile"));});
        forget.addActionListener(e->call.accept("/mobile/adb/forget",Json.object()));
        refresh.run();return panel;
    }
    private static JPanel card(String title,String value)throws Exception{
        var panel=new JPanel(new BorderLayout(8,8));panel.add(new JLabel(title,SwingConstants.CENTER),BorderLayout.NORTH);
        var matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,256,256,Map.of(EncodeHintType.MARGIN,2));
        var image=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);for(int y=0;y<256;y++)for(int x=0;x<256;x++)image.setRGB(x,y,matrix.get(x,y)?0xff10151c:0xffffffff);
        panel.add(new JLabel(new ImageIcon(image)));var copy=new JButton("Копировать ссылку");copy.addActionListener(e->Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value),null));panel.add(copy,BorderLayout.SOUTH);return panel;
    }
    private static String bytes(long value){if(value<1024)return value+" B";if(value<1024*1024)return String.format(java.util.Locale.ROOT,"%.1f KiB",value/1024d);if(value<1024L*1024*1024)return String.format(java.util.Locale.ROOT,"%.1f MiB",value/1048576d);return String.format(java.util.Locale.ROOT,"%.2f GiB",value/1073741824d);}
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

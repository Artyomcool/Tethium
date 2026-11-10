package dev.tethium.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.host.HostClient;
import dev.tethium.json.Json;
import dev.tethium.remote.FileTransfers;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** The same file screen in the host and client-only desktop applications. */
final class FileManagerDialog {
    private final JDialog dialog;private final HostClient host;private final ExecutorService io=Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private record Folder(String id,String title,String path){public String toString(){return title+"  ·  "+path;}}
    private final JComboBox<Folder> roots=new JComboBox<>();private final JLabel location=new JLabel(" "),status=new JLabel(" ");
    private final DefaultTableModel rows=new DefaultTableModel(new Object[]{"Имя","Размер","Изменён"},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(rows);private final JProgressBar progress=new JProgressBar(0,1000);private final JButton cancel=new JButton("Остановить");
    private final List<JComponent> controls=new ArrayList<>();private final List<JsonNode> entries=new ArrayList<>();
    private String path="";private boolean busy,loadingRoots;private AtomicBoolean cancelled=new AtomicBoolean();private long lastProgress;
    static void open(Window owner,HostClient host,String project){new FileManagerDialog(owner,host,project);}
    private FileManagerDialog(Window owner,HostClient host,String project){
        this.host=host;dialog=new JDialog(owner,"Файлы · рабочий ноутбук",Dialog.ModalityType.MODELESS);dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        var content=new JPanel(new BorderLayout(8,8));content.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));var top=new JPanel(new BorderLayout(6,6));
        roots.addActionListener(e->{if(!loadingRoots&&!busy){path="";load();}});top.add(roots,BorderLayout.NORTH);controls.add(roots);
        var tools=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));button(tools,"↑",()->{int slash=path.lastIndexOf('/');path=slash<0?"":path.substring(0,slash);load();});button(tools,"Обновить",this::load);
        button(tools,"Отправить…",this::upload);button(tools,"Сохранить…",this::download);button(tools,"Новая папка",this::mkdir);button(tools,"Переименовать",this::rename);button(tools,"Удалить…",this::remove);
        if(!host.remote())button(tools,"Общая папка…",this::share);top.add(tools,BorderLayout.CENTER);top.add(location,BorderLayout.SOUTH);content.add(top,BorderLayout.NORTH);
        table.setRowHeight(27);table.getColumnModel().getColumn(0).setPreferredWidth(430);table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);table.setAutoCreateRowSorter(false);content.add(new JScrollPane(table),BorderLayout.CENTER);controls.add(table);
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&!busy){int row=table.getSelectedRow();if(row>=0&&entries.get(row).path("directory").asBoolean()&&entries.get(row).path("accessible").asBoolean()){path=join(path,entries.get(row).path("name").asText());load();}}}});
        var footer=new JPanel(new BorderLayout(8,4));footer.add(status,BorderLayout.NORTH);progress.setPreferredSize(new Dimension(300,8));footer.add(progress,BorderLayout.CENTER);cancel.setEnabled(false);cancel.addActionListener(e->{cancelled.set(true);cancel.setEnabled(false);status.setText("Останавливаем передачу…");});footer.add(cancel,BorderLayout.EAST);content.add(footer,BorderLayout.SOUTH);
        dialog.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(busy&&JOptionPane.showConfirmDialog(dialog,"Остановить операцию и закрыть файлы? Незавершённую передачу можно продолжить повторной отправкой.","Файлы",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;cancelled.set(true);io.shutdownNow();dialog.dispose();}});
        dialog.setContentPane(content);dialog.setSize(1000,600);dialog.setLocationRelativeTo(owner);dialog.setVisible(true);loadRoots(project);
    }
    private void button(JPanel panel,String text,Runnable action){var button=new JButton(text);button.addActionListener(e->action.run());controls.add(button);panel.add(button);}
    private Folder folder(){return (Folder)roots.getSelectedItem();}
    private JsonNode selected(){int row=table.getSelectedRow();return row<0?null:entries.get(row);}
    private void loadRoots(String project){work("Читаем папки…",()->host.get("/file-manager/roots"),result->{loadingRoots=true;String keep=folder()==null?"":folder().id;roots.removeAllItems();Folder choice=null;for(var item:result.path("roots")){var f=new Folder(item.path("id").asText(),item.path("title").asText(),item.path("path").asText());roots.addItem(f);if(f.id.equals(keep)||project!=null&&f.id.equals("project-"+project))choice=f;}if(choice!=null)roots.setSelectedItem(choice);loadingRoots=false;path="";load();});}
    private void load(){if(folder()==null)return;String root=folder().id,relative=path;work("Читаем файлы…",()->{
        var all=Json.MAPPER.createArrayNode();int offset=0;do{var response=host.get("/file-manager/list?root="+FileTransfers.encode(root)+"&path="+FileTransfers.encode(relative)+"&offset="+offset);for(var entry:response.path("entries"))all.add(entry);offset=response.path("next").asInt(-1);}while(offset>=0&&!cancelled.get());return all;
    },result->{entries.clear();rows.setRowCount(0);for(var item:result){entries.add(item);rows.addRow(new Object[]{(item.path("directory").asBoolean()?"▸ ":"  ")+item.path("name").asText()+(item.path("link").asBoolean()?" ↗":""),item.path("directory").asBoolean()?"Папка":size(item.path("size").asLong()),new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(item.path("modified").asLong()))});}location.setText(folder().path+(path.isEmpty()?"":" / "+path));status.setText(entries.size()+" элементов · двойной клик открывает папку");});}
    private void upload(){
        if(folder()==null)return;var picker=new JFileChooser();picker.setDialogTitle("Отправить с этого ноутбука");picker.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);picker.setMultiSelectionEnabled(true);if(picker.showOpenDialog(dialog)!=JFileChooser.APPROVE_OPTION)return;
        String root=folder().id,parent=path;var files=new LinkedHashMap<Path,String>();
        for(var file:picker.getSelectedFiles()){JsonNode old=entries.stream().filter(e->e.path("name").asText().equals(file.getName())).findFirst().orElse(null);String revision="";
            if(old!=null){if(old.path("directory").asBoolean()!=file.isDirectory())continue;String question=file.isDirectory()?"Объединить папку «"+file.getName()+"» на рабочем ноутбуке?\nСовпадающие файлы будут заменены.":"Заменить на рабочем ноутбуке «"+file.getName()+"»?";if(JOptionPane.showConfirmDialog(dialog,question,"Замена файлов",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)continue;revision=old.path("revision").asText();}files.put(file.toPath(),revision);}
        if(files.isEmpty())return;work("Готовим отправку…",()->{var transfer=new FileTransfers(host,cancelled);for(var file:files.entrySet()){String name=file.getKey().getFileName().toString();if(Files.isDirectory(file.getKey()))transfer.uploadDirectory(file.getKey(),root,join(parent,name),!file.getValue().isEmpty(),(n,total)->progress(name,n,total));else transfer.upload(file.getKey(),root,join(parent,name),file.getValue(),(n,total)->progress(name,n,total));}return Json.object();},r->load());
    }
    private void download(){
        var chosen=new ArrayList<JsonNode>();for(int row:table.getSelectedRows())if(entries.get(row).path("accessible").asBoolean())chosen.add(entries.get(row));if(chosen.isEmpty())return;
        var picker=new JFileChooser();picker.setDialogTitle("Сохранить на этом ноутбуке");picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(picker.showSaveDialog(dialog)!=JFileChooser.APPROVE_OPTION)return;
        Path destination=picker.getSelectedFile().toPath();String root=folder().id,parent=path;var accepted=new ArrayList<JsonNode>();for(var item:chosen){String name=item.path("name").asText()+(item.path("directory").asBoolean()?".zip":"");Path target=destination.resolve(name);boolean replace=Files.exists(target);if(replace&&JOptionPane.showConfirmDialog(dialog,"Заменить «"+target+"»?","Заменить файл",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)continue;var copy=(com.fasterxml.jackson.databind.node.ObjectNode)item.deepCopy();copy.put("replace",replace);copy.put("savedName",name);accepted.add(copy);}if(accepted.isEmpty())return;
        work("Готовим скачивание…",()->{var transfer=new FileTransfers(host,cancelled);for(var item:accepted){String name=item.path("name").asText();var info=host.post("/file-manager/download",Json.object("root",root,"path",join(parent,name)));transfer.download(info,destination.resolve(item.path("savedName").asText()),item.path("replace").asBoolean(),(n,total)->progress(name,n,total));}return Json.object();},r->status.setText("Сохранено: "+destination));
    }
    private void mkdir(){if(folder()==null)return;String name=JOptionPane.showInputDialog(dialog,"Название папки");if(name==null||name.isBlank())return;var body=Json.object("root",folder().id,"path",path,"name",name);work("Создаём папку…",()->host.post("/file-manager/mkdir",body),r->load());}
    private void rename(){var item=selected();if(item==null||!item.path("accessible").asBoolean())return;String name=JOptionPane.showInputDialog(dialog,"Новое имя",item.path("name").asText());if(name==null||name.isBlank())return;var body=entryBody(item);body.put("name",name);work("Переименовываем…",()->host.post("/file-manager/rename",body),r->load());}
    private void remove(){var item=selected();if(item==null||!item.path("accessible").asBoolean())return;if(JOptionPane.showConfirmDialog(dialog,"Удалить «"+item.path("name").asText()+"» с рабочего ноутбука?\nНепустые папки не удаляются.","Удаление файла",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;var body=entryBody(item);body.put("confirmed",true);work("Удаляем…",()->host.post("/file-manager/delete",body),r->load());}
    private com.fasterxml.jackson.databind.node.ObjectNode entryBody(JsonNode entry){return Json.object("root",folder().id,"path",join(path,entry.path("name").asText()),"revision",entry.path("revision").asText());}
    private void share(){var picker=new JFileChooser();picker.setDialogTitle("Добавить папку для обмена с вашими устройствами");picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(picker.showOpenDialog(dialog)!=JFileChooser.APPROVE_OPTION)return;work("Добавляем папку…",()->host.post("/file-manager/share",Json.object("path",picker.getSelectedFile().getAbsolutePath())),r->loadRoots(null));}
    private <T>void work(String label,Callable<T> action,java.util.function.Consumer<T> success){
        if(busy)return;busy=true;cancelled=new AtomicBoolean();status.setText(label);progress.setIndeterminate(true);for(var c:controls)c.setEnabled(false);cancel.setEnabled(true);
        io.submit(()->{T result=null;Exception error=null;try{result=action.call();}catch(Exception e){error=e;}T value=result;Exception failure=error;SwingUtilities.invokeLater(()->{if(!dialog.isDisplayable())return;busy=false;progress.setIndeterminate(false);for(var c:controls)c.setEnabled(true);cancel.setEnabled(false);if(failure==null){progress.setValue(1000);success.accept(value);}else{status.setText(Objects.toString(failure.getMessage(),"Ошибка операции"));if(!cancelled.get())JOptionPane.showMessageDialog(dialog,status.getText(),"Файлы",JOptionPane.WARNING_MESSAGE);}});});
    }
    private void progress(String file,long value,long total){long now=System.nanoTime();if(value!=total&&now-lastProgress<100_000_000)return;lastProgress=now;SwingUtilities.invokeLater(()->{if(!dialog.isDisplayable())return;progress.setIndeterminate(false);progress.setValue(total==0?1000:(int)(1000.0*value/total));status.setText(file+" · "+size(value)+" / "+size(total));});}
    private static String join(String parent,String name){return parent.isEmpty()?name:parent+"/"+name;}
    static String size(long bytes){return bytes<1024?bytes+" Б":bytes<1048576?String.format(Locale.ROOT,"%.1f КБ",bytes/1024.0):bytes<1073741824?String.format(Locale.ROOT,"%.1f МБ",bytes/1048576.0):String.format(Locale.ROOT,"%.2f ГБ",bytes/1073741824.0);}
}

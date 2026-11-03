package dev.tethium.desktop;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

final class ClipboardImages {
    private ClipboardImages() {}
    static Transferable contents(Image image){
        if(image==null)throw new IllegalArgumentException("Не удалось прочитать изображение");
        return new Transferable(){
            public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.imageFlavor};}
            public boolean isDataFlavorSupported(DataFlavor flavor){return DataFlavor.imageFlavor.equals(flavor);}
            public Object getTransferData(DataFlavor flavor)throws UnsupportedFlavorException{if(!isDataFlavorSupported(flavor))throw new UnsupportedFlavorException(flavor);return image;}
        };
    }
    static void copy(Component owner,java.util.concurrent.Callable<Image> read){
        new javax.swing.SwingWorker<Image,Void>(){
            protected Image doInBackground()throws Exception{return read.call();}
            protected void done(){try{Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents(get()),null);}catch(Exception error){var cause=error.getCause()==null?error:error.getCause();javax.swing.JOptionPane.showMessageDialog(owner,"Не удалось скопировать изображение: "+cause.getMessage(),"Буфер обмена",javax.swing.JOptionPane.ERROR_MESSAGE);}}
        }.execute();
    }
    static Path localPath(String source,Path directory){
        if(source.startsWith("file:"))return Path.of(java.net.URI.create(source));
        String decoded=java.net.URLDecoder.decode(source.replace("+","%2B"),java.nio.charset.StandardCharsets.UTF_8);
        if(decoded.matches("^/[A-Za-z]:/.*"))decoded=decoded.substring(1);
        Path path=Path.of(decoded);return path.isAbsolute()?path:directory.resolve(path);
    }
    static Image read(String source,Path directory)throws Exception{
        if(source.startsWith("data:image/")){int split=source.indexOf(',');if(split<0||!source.substring(0,split).endsWith(";base64"))throw new java.io.IOException("Неподдерживаемый формат изображения");return ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(source.substring(split+1))));}
        if(source.startsWith("http://")||source.startsWith("https://")){var connection=java.net.URI.create(source).toURL().openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(15000);try(var in=connection.getInputStream()){return ImageIO.read(in);}}
        return ImageIO.read(localPath(source,directory).toFile());
    }
    static Path save(Image image, Path data, String sessionId) throws Exception {
        UUID.fromString(sessionId);
        var loaded=new javax.swing.ImageIcon(image); int width=loaded.getIconWidth(),height=loaded.getIconHeight();
        if(width<1 || height<1)throw new IllegalArgumentException("В буфере нет изображения");
        var pixels=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB); var g=pixels.createGraphics(); g.setComposite(AlphaComposite.Src); g.drawImage(image,0,0,null);g.dispose();
        var folder=Files.createDirectories(data.resolve("attachments").resolve(sessionId));
        String name="screenshot-"+DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())+"-"+UUID.randomUUID().toString().substring(0,8)+".png";
        var target=folder.resolve(name);var temporary=folder.resolve(name+".tmp");
        try { if(!ImageIO.write(pixels,"png",temporary.toFile()))throw new java.io.IOException("Не удалось сохранить PNG"); Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE); return target; }
        finally { Files.deleteIfExists(temporary); }
    }
}

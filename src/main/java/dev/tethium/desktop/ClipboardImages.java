package dev.tethium.desktop;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

final class ClipboardImages {
    private ClipboardImages() {}
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

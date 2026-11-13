package dev.tethium.host;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;

/** A project owns its icon: {@code .tethium/icon.png} in the project root, versioned by size and modification time
 *  so clients, local or remote, fetch the bytes only when the file changes. */
public final class ProjectIcons {
    private ProjectIcons() {}
    public record Png(String version,byte[] bytes){}
    static final String FILE = "icon.png";
    public static Path file(Path root) { return root.resolve(".tethium").resolve(FILE); }
    /** Empty when the project has no icon file. */
    public static String version(Path root) {
        Path file = file(root);
        try { return Files.isRegularFile(file) ? Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis() : ""; }
        catch (IOException e) { return ""; }
    }
    public static Png read(Path root) throws IOException {
        Path file = file(root); String version = version(root);
        if (version.isEmpty()) return new Png("",new byte[0]);
        return new Png(version,Files.readAllBytes(file));
    }
    /** Small phone/header rendition; the source icon and its version remain project-owned. */
    public static Png read(Path root,int maximum) throws IOException {
        Path file=file(root);String version=version(root);if(version.isEmpty())return new Png("",new byte[0]);
        maximum=Math.max(24,Math.min(256,maximum));BufferedImage source=ImageIO.read(file.toFile());
        if(source==null)return read(root);double scale=Math.min(1d,Math.min((double)maximum/source.getWidth(),(double)maximum/source.getHeight()));
        if(scale>=1)return new Png(version,Files.readAllBytes(file));
        int width=Math.max(1,(int)Math.round(source.getWidth()*scale)),height=Math.max(1,(int)Math.round(source.getHeight()*scale));var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();try{graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);graphics.drawImage(source,0,0,width,height,null);}finally{graphics.dispose();}
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return new Png(version,bytes.toByteArray());
    }
}

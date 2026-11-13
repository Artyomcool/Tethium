package dev.tethium.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectIconsTest {
    @TempDir Path root;
    @Test void phoneRenditionKeepsVersionAndBoundsPixels()throws Exception{
        Path file=ProjectIcons.file(root);Files.createDirectories(file.getParent());var image=new BufferedImage(512,256,BufferedImage.TYPE_INT_ARGB);ImageIO.write(image,"png",file.toFile());
        var value=ProjectIcons.read(root,64);assertEquals(ProjectIcons.version(root),value.version());
        var small=ImageIO.read(new java.io.ByteArrayInputStream(value.bytes()));assertEquals(64,small.getWidth());assertEquals(32,small.getHeight());
    }
}

package dev.tethium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.html.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ClipboardImagesTest {
    @TempDir Path root;
    @Test void clipboardContainsOriginalPixelsAndCanBePastedAsAnAttachment()throws Exception{
        var original=new BufferedImage(600,300,BufferedImage.TYPE_INT_ARGB);original.setRGB(599,299,0x7f123456);
        var clipboard=new Clipboard("isolated test");clipboard.setContents(ClipboardImages.contents(original),null);
        assertTrue(clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor));assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor));
        Image copied=(Image)clipboard.getData(DataFlavor.imageFlavor);assertEquals(600,copied.getWidth(null));assertEquals(300,copied.getHeight(null));
        Path pasted=ClipboardImages.save(copied,root,java.util.UUID.randomUUID().toString());assertEquals(0x7f123456,ImageIO.read(pasted.toFile()).getRGB(599,299));
        assertThrows(UnsupportedFlavorException.class,()->ClipboardImages.contents(original).getTransferData(DataFlavor.stringFlavor));
    }
    @Test void galleryHitCopiesOriginalRatherThanTheThumbnailAndBlankSpaceHasNoImage()throws Exception{
        Path thumb=root.resolve("thumb.png"),original=root.resolve("original + full.png");ImageIO.write(new BufferedImage(60,30,BufferedImage.TYPE_INT_RGB),"png",thumb.toFile());
        SwingUtilities.invokeAndWait(()->{
            var pane=new ChatTranscript();pane.setText("<html><body><p>Text</p><p><a href='"+original.toUri()+"'><img src='"+thumb.toUri()+"' data-original='"+original.toUri()+"' width='60' height='30'></a></p></body></html>");pane.setSize(400,200);
            var canvas=new BufferedImage(400,200,BufferedImage.TYPE_INT_RGB);var g=canvas.createGraphics();pane.paint(g);g.dispose();
            var img=((HTMLDocument)pane.getDocument()).getIterator(HTML.Tag.IMG);assertTrue(img.isValid());
            try{var bounds=pane.modelToView2D(img.getStartOffset());assertEquals(original.toUri().toString(),pane.imageSourceAt(new Point((int)bounds.getX()+10,(int)bounds.getY()+10)));}catch(Exception e){throw new RuntimeException(e);}
            assertNull(pane.imageSourceAt(new Point(350,180)));
        });
        assertEquals(original,ClipboardImages.localPath(original.toUri().toString(),root));
        assertEquals(original,ClipboardImages.localPath("original%20+%20full.png",root));
    }
}

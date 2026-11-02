package dev.tethium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ComposerTest {
    @TempDir Path root;
    @Test void undoRedoAndCopyWorkAndUndoCannotCrossLoadedDrafts() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var input=new PromptInput(); input.setText("first"); input.append(" added");
            press(input,KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK); assertEquals("first",input.getText());
            press(input,KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK); assertEquals("first added",input.getText());
            input.selectAll(); var clipboard=new Clipboard("test"); input.getTransferHandler().exportToClipboard(input,clipboard,TransferHandler.COPY);
            try { assertEquals("first added",clipboard.getData(DataFlavor.stringFlavor)); }catch(Exception e){throw new RuntimeException(e);}
            input.setText("other session"); press(input,KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK); assertEquals("other session",input.getText());
        });
    }
    @Test void pastedImageRetainsResolutionAndAlphaOnDisk() throws Exception {
        var image=new BufferedImage(333,177,BufferedImage.TYPE_INT_ARGB); image.setRGB(113,25,0x8032aaff);
        Path path=ClipboardImages.save(image,root,UUID.randomUUID().toString()); var actual=ImageIO.read(path.toFile());
        assertEquals(333,actual.getWidth()); assertEquals(177,actual.getHeight()); assertEquals(0x8032aaff,actual.getRGB(113,25));
    }
    private static void press(JComponent c,int key,int mods){var action=c.getInputMap().get(KeyStroke.getKeyStroke(key,mods)); assertNotNull(action);c.getActionMap().get(action).actionPerformed(new ActionEvent(c,0,"test"));}
}

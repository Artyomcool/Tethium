package dev.tethium.desktop;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class UiIconsTest {
    @Test void reviewedVectorsStayWhiteAndInsideBoundsAtDisplayScales() {
        String[] names = {"project-folder", "project-add", "session-chat", "session-running", "session-new",
                "session-archive", "artifact-gallery", "session-actions", "send-message", "stop-run", "attach-file",
                "preview-expand", "preview-collapse", "artifact-reload", "preview-close", "quota-gauge", "quota-refresh",
                "action-history", "queue-resume", "queue-expand", "queue-delete", "queue-up", "queue-down",
                "queue-return-last", "preview-fit", "preview-actual-size", "model-select", "reasoning-level",
                "send-separate", "send-append", "queue-pause", "session-rename"};
        var component = new JLabel(); component.setForeground(Color.RED);
        for (String name : names) for (double scale : new double[]{1, 1.25, 1.5, 2}) {
            var image = new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
            var g = image.createGraphics();g.scale(scale,scale);
            UiIcons.of(name,16).paintIcon(component,g,8,8);g.dispose();
            int pixels=0, partial=0;
            for(int y=0;y<64;y++) for(int x=0;x<64;x++) {
                int pixel=image.getRGB(x,y), alpha=pixel>>>24;
                if(alpha==0)continue;
                pixels++;if(alpha<255)partial++;
                assertEquals(0xffffff,pixel&0xffffff,name);
                assertTrue(x>=8*scale && y>=8*scale && x<24*scale && y<24*scale,name+" outside bounds");
            }
            assertTrue(pixels>10,name+" empty");assertTrue(partial>0,name+" missing antialiasing");
        }
    }
}

package dev.tethium.desktop;
import javax.swing.*;
import java.awt.*;
/** Reviewed SVG geometry painted directly at the display's native scale. */
final class UiIcons {
    private UiIcons() {}
    static Icon of(String name, int size) {
        String key = switch(name) {
            case "folder" -> "project-folder"; case "plus" -> "session-new";
            case "session" -> "session-chat"; case "activity" -> "session-running";
            case "archive" -> "session-archive"; case "image" -> "artifact-gallery";
            case "more" -> "session-actions"; case "send" -> "send-message";
            case "stop" -> "stop-run"; case "attachment" -> "attach-file";
            case "expand" -> "preview-expand"; case "refresh" -> "artifact-reload";
            case "close" -> "preview-close"; case "play" -> "queue-resume";
            default -> name;
        };
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                var g=(Graphics2D)graphics.create();
                try {
                    g.translate(x,y); g.scale(size/24.0,size/24.0);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
                    g.setColor(Color.WHITE);
                    if(c!=null && !c.isEnabled()) g.setComposite(AlphaComposite.SrcOver.derive(.38f));
                    VectorIconData.paint(key,g);
                } finally { g.dispose(); }
            }
        };
    }
}

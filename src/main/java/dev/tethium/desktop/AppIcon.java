package dev.tethium.desktop;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.util.ArrayList;
import java.util.List;

final class AppIcon {
    private AppIcon() {}
    static List<Image> images() {
        var images = new ArrayList<Image>();
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            try (var stream = AppIcon.class.getResourceAsStream("branding/"+(Boolean.getBoolean("tethium.remote")?"client/":"")+"tethium-" + size + ".png")) {
                if (stream == null) throw new IllegalStateException("Missing app icon");
                images.add(ImageIO.read(stream));
            } catch (java.io.IOException e) { throw new IllegalStateException("Cannot load app icon", e); }
        }
        return List.copyOf(images);
    }
}

package dev.tethium.desktop;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Image;
import java.awt.image.BaseMultiResolutionImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Stable, name-based identities shared by every project and future session. */
final class IdentityIcons {
    private static final String ROOT="identity/v1/";
    private static final Properties NAMES=loadNames();
    private static final Map<String, Icon> CACHE=new ConcurrentHashMap<>();
    // Keep this order stable: unknown names use a repeatable fallback without model calls.
    private static final String[] FALLBACK={"session-general","session-research","session-experiments",
            "session-docs","session-maintenance","session-architecture"};
    private IdentityIcons() {}
    static String normalize(String title) {
        return Normalizer.normalize(Objects.requireNonNullElse(title,""),Normalizer.Form.NFKC)
                .strip().replaceAll("(?U)\\s+"," ").toLowerCase(Locale.ROOT);
    }
    static String asset(boolean project, String title, String directory) {
        String name=title;
        if(project) {
            String path=Objects.requireNonNullElse(directory,"").replace('\\','/').replaceAll("/+$","");
            if(!path.isBlank())name=path.substring(path.lastIndexOf('/')+1);
        }
        String normalized=normalize(name);
        String mapped=NAMES.getProperty((project?"project.":"session.")+normalized);
        if(mapped!=null)return mapped;
        return project?"project-general":FALLBACK[Math.floorMod(normalized.hashCode(),FALLBACK.length)];
    }
    static Icon of(boolean project,String title,String directory) {
        return CACHE.computeIfAbsent(asset(project,title,directory),IdentityIcons::load);
    }
    private static Properties loadNames() {
        var names=new Properties();
        try(var stream=IdentityIcons.class.getResourceAsStream(ROOT+"names.properties")) {
            if(stream==null)throw new IllegalStateException("Missing identity name registry");
            names.load(new InputStreamReader(stream,StandardCharsets.UTF_8));return names;
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private static Icon load(String name) {
        var images=new ArrayList<Image>();
        for(int size:new int[]{22,28,33,44,66,88}) {
            try(var stream=IdentityIcons.class.getResourceAsStream(ROOT+name+"-"+size+".png")) {
                if(stream==null)throw new IllegalStateException("Missing identity icon: "+name);
                images.add(ImageIO.read(stream));
            } catch(java.io.IOException e){throw new IllegalStateException(e);}
        }
        return new ImageIcon(new BaseMultiResolutionImage(images.toArray(Image[]::new)));
    }
}

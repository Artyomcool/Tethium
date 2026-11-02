package dev.tethium.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Persistent, clickable file references; only explicit attachment links become native image inputs. */
public final class ImageAttachments {
    private ImageAttachments() {}
    private static final Pattern LINK = Pattern.compile("\\[Скриншот[^\\]]*]\\(<(file:[^>]+)>\\)");
    public static String append(String prompt, JsonNode images) throws Exception {
        var text=new StringBuilder(prompt);
        for(var image:images) {
            Path path=Path.of(image.asText()).toRealPath();
            if(!Files.isRegularFile(path) || !path.toString().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp)$")) throw new IllegalArgumentException("Не найдено изображение: "+path);
            if(!text.isEmpty())text.append("\n\n");
            text.append("[Скриншот ").append(path.getFileName().toString().replace("]", "")).append("](<").append(path.toUri().toASCIIString()).append(">)");
        }
        return text.toString();
    }
    public static List<Path> paths(String prompt) {
        var result=new LinkedHashSet<Path>(); var matcher=LINK.matcher(prompt);
        while(matcher.find()) { try { result.add(Path.of(URI.create(matcher.group(1)))); } catch(IllegalArgumentException ignored) {} }
        return List.copyOf(result);
    }
}

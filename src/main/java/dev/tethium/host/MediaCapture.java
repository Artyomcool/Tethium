package dev.tethium.host;

import dev.tethium.core.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Durable snapshots of image tool results, processed outside the host/event transaction. */
final class MediaCapture implements AutoCloseable {
    private final SessionStore store;
    private final Path data;
    private boolean seeded;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    MediaCapture(SessionStore store,Path data){this.store=store;this.data=data;executor.scheduleWithFixedDelay(this::poll,0,500,TimeUnit.MILLISECONDS);}
    private void poll(){try{
        if(!seeded){store.seedMediaJobs();seeded=true;}
        for(var job:store.mediaJobs())try{capture(job);}catch(Exception error){System.err.println("Image preview: "+error.getMessage());store.finishMediaJob(job.path("event").asLong());}
    }catch(Exception error){if(!executor.isShutdown())System.err.println("Image capture: "+error.getMessage());}}
    private void capture(JsonNode job)throws Exception{
        var item=job.path("item");String session=job.path("session").asText();UUID.fromString(session);
        String key=UUID.nameUUIDFromBytes((job.path("run").asText()+":"+job.path("itemId").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        Path folder=Files.createDirectories(data.resolve("chat-media").resolve(session));
        String source=item.path("savedPath").asText(item.path("path").asText());Path file;
        if(!source.isBlank()&&Files.isRegularFile(Path.of(source))){
            Path original=Path.of(source);String name=original.getFileName().toString();String suffix=name.contains(".")?name.substring(name.lastIndexOf('.')):".png";
            file=folder.resolve(key+suffix);if(!Files.exists(file))Files.copy(original,file);
        }else{
            String encoded=item.path("result").asText();if(encoded.isBlank()){store.finishMediaJob(job.path("event").asLong());return;}
            if(encoded.startsWith("data:"))encoded=encoded.substring(encoded.indexOf(',')+1);
            file=folder.resolve(key+".png");if(!Files.exists(file))Files.write(file,Base64.getDecoder().decode(encoded));
        }
        BufferedImage image;
        try(var in=ImageIO.createImageInputStream(file.toFile())){
            var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new java.io.IOException("Unsupported preview image: "+file.getFileName());
            var reader=readers.next();try{reader.setInput(in);var params=reader.getDefaultReadParam();int step=Math.max(1,Math.max(reader.getWidth(0)/320,reader.getHeight(0)/200));params.setSourceSubsampling(step,step,0,0);image=reader.read(0,params);}finally{reader.dispose();}
        }
        double scale=Math.min(1,Math.min(200.0/image.getWidth(),120.0/image.getHeight()));int w=Math.max(1,(int)(image.getWidth()*scale)),h=Math.max(1,(int)(image.getHeight()*scale));
        var thumbnail=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);var g=thumbnail.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(image,0,0,w,h,null);g.dispose();image.flush();
        Path thumb=folder.resolve(key+"-thumb.png");ImageIO.write(thumbnail,"png",thumb.toFile());thumbnail.flush();
        String title=item.path("type").asText().equals("imageView")?"Просмотр изображения":"Созданное изображение";
        store.saveMedia(job,file,thumb,w,h,title);
    }
    @Override public void close(){executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

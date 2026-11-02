package dev.tethium.remote;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public final class ArtifactLauncher {
    private ArtifactLauncher(){}
    public static List<Path> entries(Path downloaded)throws IOException{
        String name=downloaded.getFileName().toString().toLowerCase(Locale.ROOT);
        if(name.endsWith(".jar")||name.endsWith(".exe"))return List.of(downloaded);
        if(!name.endsWith(".zip"))return List.of();
        Path root=Files.createDirectories(downloaded.getParent().resolve("unpacked")).toRealPath();long total=0;int count=0;
        try(var zip=new ZipInputStream(Files.newInputStream(downloaded))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){if(++count>20_000)throw new IOException("Слишком много файлов в сборке");Path target=root.resolve(entry.getName()).normalize();if(!target.startsWith(root)||entry.getName().contains(":"))throw new IOException("Путь за пределами архива");if(entry.isDirectory()){Files.createDirectories(target);continue;}Files.createDirectories(target.getParent());if(!target.getParent().toRealPath().startsWith(root)||Files.isSymbolicLink(target))throw new IOException("Ссылка за пределы сборки");try(var out=Files.newOutputStream(target)){byte[] b=new byte[65536];int n;while((n=zip.read(b))!=-1){total+=n;if(total>4_000_000_000L)throw new IOException("Распакованная сборка больше 4 ГБ");out.write(b,0,n);}}}}
        try(var files=Files.walk(root)){return files.filter(Files::isRegularFile).filter(p->p.toString().toLowerCase(Locale.ROOT).matches(".*\\.(jar|exe)$")).sorted(Comparator.comparingInt(p->p.getNameCount())).toList();}
    }
    public static Process launch(Path entry)throws IOException{
        Path file=entry.toRealPath();String name=file.getFileName().toString().toLowerCase(Locale.ROOT);List<String> command;
        if(name.endsWith(".jar"))command=List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-jar",file.toString());
        else if(name.endsWith(".exe"))command=List.of(file.toString());else throw new IOException("Запуск поддерживается для JAR и EXE");
        return new ProcessBuilder(command).directory(file.getParent().toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(file.getParent().resolve("tethium-launch.log").toFile())).start();
    }
}

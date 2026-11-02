package dev.tethium.core;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** The repository is authoritative. Only this delimited section is owned by the settings checkbox. */
public final class ProjectPolicy {
    private static final String START="<!-- tethium:github-sync:start -->",END="<!-- tethium:github-sync:end -->";
    private static final String RULE="""
        ## GitHub synchronization
        Commit and push completed, meaningful changes frequently. After a coherent change, run the relevant checks, commit your own changes, then immediately push to the current branch's upstream. Before finishing, check that your commits are pushed. If no upstream exists, set it on origin for the current branch.
        This project directory is shared by multiple Tethium sessions. Coordinate Git index/commit/push operations with other sessions. Inspect staged changes before committing; do not include, discard, or overwrite another session's work. Do not force-push, rewrite shared history, or commit secrets and build outputs. Report a blocked push and retain local work.
        """;
    private ProjectPolicy(){}
    public static boolean enabled(Path root)throws IOException{Path file=root.resolve("AGENTS.md");return Files.isRegularFile(file)&&Files.readString(file).contains(START);}
    public static synchronized void set(Path root,boolean enabled)throws IOException{
        Path file=root.resolve("AGENTS.md");String original=Files.exists(file)?Files.readString(file):"";String text=original;int start=text.indexOf(START),end=text.indexOf(END);
        if((start<0)!=(end<0)||start>=0&&end<start)throw new IOException("Повреждён блок GitHub в AGENTS.md; исправьте его перед изменением настройки");
        if(start>=0)text=text.substring(0,start)+text.substring(end+END.length());
        if(enabled)text=text.stripTrailing()+ (text.isBlank()?"":"\n\n")+START+"\n"+RULE+END+"\n";
        if(!enabled&&start<0)return;
        Path temp=Files.createTempFile(root,".tethium-policy-",".tmp");try{Files.writeString(temp,text,StandardCharsets.UTF_8);if(Files.exists(file)&&!Files.readString(file).equals(original))throw new IOException("AGENTS.md изменился во время сохранения. Повторите действие.");Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
    }
    public static String context(Path root)throws IOException{return "\nProject policy: consult the current repository AGENTS.md at "+root.resolve("AGENTS.md")+". Managed frequent GitHub commit/push rule is "+(enabled(root)?"enabled":"disabled; do not reuse that managed rule from old conversation context")+".\n";}
}

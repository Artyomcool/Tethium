package dev.tethium.mobile;

import java.util.Map;

/** Remote paths are Windows paths even when this code runs on Android. */
final class FileNavigation {
    record Place(String root,String path){}
    static Place parent(String root,String path,Map<String,String> roots){
        if(!path.isEmpty()){int slash=path.lastIndexOf('/');return new Place(root,slash<0?"":path.substring(0,slash));}
        String base=normalize(roots.getOrDefault(root,""));if(base.isEmpty()||base.equals("/")||base.matches("[a-zA-Z]:/"))return null;
        int slash=base.lastIndexOf('/');if(slash<0)return null;String parent=base.substring(0,slash+1);
        for(var entry:roots.entrySet())if(entry.getKey().startsWith("drive-")){
            String drive=normalize(entry.getValue());if(!drive.endsWith("/"))drive+="/";
            if(parent.regionMatches(true,0,drive,0,drive.length())){String relative=parent.substring(drive.length());while(relative.endsWith("/"))relative=relative.substring(0,relative.length()-1);return new Place(entry.getKey(),relative);}
        }
        return null;
    }
    static String normalize(String path){String value=path.replace('\\','/');while(value.endsWith("/")&&value.length()>1&&!value.matches("[a-zA-Z]:/"))value=value.substring(0,value.length()-1);return value;}
}

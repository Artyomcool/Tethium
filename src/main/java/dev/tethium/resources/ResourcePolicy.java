package dev.tethium.resources;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class ResourcePolicy {
    private ResourcePolicy(){}
    public static JsonNode defaults(String scope){return Json.object("enabled",true,"cap",scope.equals("global")?85:0,"priority",scope.equals("global")?"normal":"inherit");}
    public static JsonNode validate(String scope,JsonNode value){
        int cap=value.path("cap").asInt(-1);String priority=value.path("priority").asText();
        if(cap<(scope.equals("global")?1:0)||cap>100)throw new IllegalArgumentException("CPU: от "+(scope.equals("global")?1:0)+" до 100%");
        if(!Set.of("inherit","background","normal","important").contains(priority)||scope.equals("global")&&priority.equals("inherit"))throw new IllegalArgumentException("Некорректный приоритет");
        return Json.object("enabled",value.path("enabled").asBoolean(true),"cap",cap,"priority",priority);
    }
    public static int cap(JsonNode own,int parent){int value=own.path("cap").asInt();return value<=0?parent:Math.min(parent,value);}
    public static String priority(JsonNode own,String parent){String value=own.path("priority").asText("inherit");return value.equals("inherit")?parent:value;}
    public static int nativePriority(String priority){return switch(priority){case "background"->0x40;case "important"->0x20;default->0x4000;};}
    public static int relativeRate(int cap,int parent){return Math.clamp((int)Math.floor(10000.0*cap/parent),1,10000);}
    public static String namespace(Path data){return UUID.nameUUIDFromBytes(("cpu-v2:"+data.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)).toString();}
}

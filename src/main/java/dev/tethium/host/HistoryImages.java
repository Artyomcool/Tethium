package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.util.*;

/** Keep consecutive thumbnails together, without turning a long image history into one huge page. */
final class HistoryImages {
    static List<JsonNode> group(List<JsonNode> entries){
        var result=new ArrayList<JsonNode>();
        for(int i=0;i<entries.size();){
            var first=entries.get(i);
            if(!first.path("kind").asText().equals("image")){result.add(first);i++;continue;}
            var images=Json.MAPPER.createArrayNode();
            while(i<entries.size()&&entries.get(i).path("kind").asText().equals("image")&&images.size()<12)images.add(entries.get(i++));
            result.add(Json.object("kind","gallery","sequence",first.path("sequence").asLong(),"offset",0,"length",0,"role","assistant","provider","CODEX","images",images,"mediaVersion",images.get(images.size()-1).path("mediaId").asLong()));
        }
        return result;
    }
}

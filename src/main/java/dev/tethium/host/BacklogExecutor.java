package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.agent.AgentAdapter.Provider;
import java.util.*;

/** The two explicitly requested interchangeable model families; never guess a pair for another model. */
final class BacklogExecutor {
    record Choice(Provider provider,String model,String effort) {
        String label(){String title=model==null?"авто":switch(model){case "gpt-6-astra"->"Astra";case "gpt-6.1-sol"->"Sol";case "fable"->"Fable";case "opus"->"Opus";default->model;};return provider.title+" · "+title;}
        Choice counterpart(){
            String name=Objects.toString(model,"").toLowerCase(Locale.ROOT);
            if(provider==Provider.CODEX){
                if(name.contains("astra"))return new Choice(Provider.CLAUDE,"fable",compatibleEffort(Provider.CLAUDE,effort));
                if(name.endsWith("-sol"))return new Choice(Provider.CLAUDE,"opus",compatibleEffort(Provider.CLAUDE,effort));
            }else{
                if(name.contains("fable"))return new Choice(Provider.CODEX,"gpt-6-astra",compatibleEffort(Provider.CODEX,effort));
                if(name.contains("opus"))return new Choice(Provider.CODEX,"gpt-6.1-sol",compatibleEffort(Provider.CODEX,effort));
            }
            return null;
        }
    }
    static String compatibleEffort(Provider provider,String effort){
        if(effort==null)return null;
        if(provider==Provider.CLAUDE && effort.equals("ultra"))return "max";
        return Set.of("low","medium","high","xhigh","max","ultra").contains(effort)?effort:null;
    }
    static Integer remaining(JsonNode snapshot,Choice choice){
        String provider=choice.provider.name().toLowerCase(Locale.ROOT);
        if(snapshot.path("sources").has(provider)&&!snapshot.path("sources").path(provider).asText().equals("ready"))return null;
        long stamp=snapshot.path("sourceUpdatedAt").path(provider).asLong(snapshot.path("updatedAt").asLong());
        if(stamp>0&&System.currentTimeMillis()-stamp>6*60_000)return null;
        Integer lowest=null;boolean general=false;
        String model=Objects.toString(choice.model,"").toLowerCase(Locale.ROOT);
        for(var bucket:snapshot.path("buckets")){
            String id=bucket.path("id").asText();
            if(!id.equals(provider)&&!(id.startsWith(provider+"-")&&model.contains(id.substring(provider.length()+1))))continue;
            for(var window:bucket.path("windows"))if(window.path("remainingPercent").isNumber()){
                if(id.equals(provider))general=true;
                int value=window.path("remainingPercent").asInt();lowest=lowest==null?value:Math.min(lowest,value);
            }
        }
        return general?lowest:null;
    }
}

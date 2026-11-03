package dev.tethium.mobile;

import org.json.*;
import java.util.*;

/** JSON null means Auto, never the literal model/effort "null". */
final class ModelChoices {
    static String text(JSONObject object,String key){String value=object==null||object.isNull(key)?"":object.optString(key,"").trim();return value.equals("null")?"":value;}
    static List<String> efforts(JSONObject model){
        var result=new ArrayList<String>();result.add("");var values=model.optJSONArray("supportedReasoningEfforts");
        if(values!=null)for(int i=0;i<values.length();i++){var item=values.optJSONObject(i);String value=item==null?(values.isNull(i)?"":values.optString(i)):text(item,"reasoningEffort");if(!value.isBlank()&&!value.equals("null")&&!result.contains(value))result.add(value);}
        return result;
    }
    /** Executor title for a preferences or message object; absent means Codex, other providers keep their name. */
    static String executor(JSONObject o){String p=text(o,"provider");return p.isEmpty()||p.equalsIgnoreCase("CODEX")?"Codex":p.equalsIgnoreCase("CLAUDE")?"Claude":p.substring(0,1).toUpperCase(Locale.ROOT)+p.substring(1).toLowerCase(Locale.ROOT);}
    static boolean same(JSONObject a,JSONObject b){String pa=text(a,"provider"),pb=text(b,"provider");return (pa.isEmpty()?"CODEX":pa).equalsIgnoreCase(pb.isEmpty()?"CODEX":pb)&&text(a,"model").equals(text(b,"model"));}
    static JSONObject settings(JSONObject model,String effort){
        var j=Connection.json("model",text(model,"model").isEmpty()?JSONObject.NULL:text(model,"model"),"reasoningEffort",effort.isEmpty()?JSONObject.NULL:effort);
        String p=text(model,"provider");if(!p.isEmpty())try{j.put("provider",p);}catch(JSONException e){throw new IllegalArgumentException(e);}return j;
    }
    /** Merged catalog: an Auto entry per executor followed by its models; a legacy single-executor catalog stays valid. */
    static JSONArray merge(JSONObject result,JSONObject preferred){
        var models=new JSONArray();var data=result.optJSONArray("data");if(data==null)data=new JSONArray();var providers=result.optJSONObject("providers");
        String current=text(result,"provider");if(current.isEmpty())current=text(preferred,"provider");if(current.isEmpty())current="CODEX";
        var order=new ArrayList<String>();
        if(providers!=null){for(String p:new String[]{"CODEX","CLAUDE"})if(providers.has(p))order.add(p);var keys=providers.keys();while(keys.hasNext()){String k=keys.next();if(!order.contains(k))order.add(k);}}
        if(order.isEmpty())order.add(current);
        for(String p:order){
            var info=providers!=null&&providers.optJSONObject(p)!=null?providers.optJSONObject(p):result;
            String title=executor(Connection.json("provider",p));String configured=text(info,"configuredModel"),configuredEffort=text(info,"configuredEffort");
            var auto=Connection.json("provider",p,"model",JSONObject.NULL,"displayName",title+": из настроек","configuredEffort",configuredEffort);JSONObject first=null;var own=new ArrayList<JSONObject>();
            for(int i=0;i<data.length();i++){var m=data.optJSONObject(i);if(m==null)continue;String owner=text(m,"provider");if(owner.isEmpty()?providers!=null&&!p.equals(current):!owner.equalsIgnoreCase(p))continue;own.add(m);if(first==null)first=m;
                if(text(m,"model").equals(configured)||configured.isEmpty()&&m.optBoolean("isDefault"))auto=Connection.json("provider",p,"model",JSONObject.NULL,"displayName",title+": "+m.optString("displayName",m.optString("model")),"supportedReasoningEfforts",m.optJSONArray("supportedReasoningEfforts"),"defaultReasoningEffort",m.opt("defaultReasoningEffort"),"configuredEffort",configuredEffort);}
            if(p.equals("CLAUDE")&&auto.optJSONArray("supportedReasoningEfforts")==null&&first!=null)try{auto.put("supportedReasoningEfforts",first.optJSONArray("supportedReasoningEfforts"));}catch(JSONException ignored){}
            models.put(auto);
            for(var m:own){var copy=new JSONObject();try{var keys=m.keys();while(keys.hasNext()){String k=keys.next();copy.put(k,m.opt(k));}copy.put("provider",p);copy.put("displayName",title+" · "+m.optString("displayName",m.optString("model")));copy.put("configuredEffort",configuredEffort);}catch(JSONException e){throw new IllegalArgumentException(e);}models.put(copy);}
        }
        boolean found=false;for(int i=0;i<models.length();i++)if(same(models.optJSONObject(i),preferred))found=true;
        if(!found){String saved=text(preferred,"model");String p=text(preferred,"provider").isEmpty()?"CODEX":text(preferred,"provider");models.put(Connection.json("provider",p,"model",saved.isEmpty()?JSONObject.NULL:saved,"displayName",executor(preferred)+(saved.isEmpty()?": из настроек":" · "+saved)));}
        return models;
    }
    static String label(String value){return switch(value){case "","null"->"Авто";case "none"->"Без рассуждений";case "minimal"->"Минимум";case "low"->"Низкое";case "medium"->"Среднее";case "high"->"Высокое";case "xhigh"->"Очень высокое · xhigh";case "max"->"Максимум";case "ultra"->"Ультра";default->value;};}
}

package dev.tethium.mobile;

import org.json.*;
import java.util.*;

/** Applies an order-only catalog delta without losing newly arrived or unmentioned sessions. */
final class SessionOrder {
    static String signature(JSONObject state) {
        var result=new StringBuilder();
        for(String key:new String[]{"projects","sessions"}){
            result.append(key);var values=state.optJSONArray(key);if(values==null)continue;
            for(int i=0;i<values.length();i++){var item=values.optJSONObject(i);if(item!=null)result.append(':').append(item.optString("id")).append(item.optString("icon"));}
        }
        return result.toString();
    }
    static JSONArray apply(JSONArray sessions, JSONArray order) {
        if (order == null) return sessions;
        var remaining = new LinkedHashMap<String,JSONObject>();
        for (int i=0;i<sessions.length();i++) {
            var session=sessions.optJSONObject(i);
            if(session!=null)remaining.put(session.optString("id"),session);
        }
        var result=new JSONArray();
        for(int i=0;i<order.length();i++) {
            var session=remaining.remove(order.optString(i));
            if(session!=null)result.put(session);
        }
        for(var session:remaining.values())result.put(session);
        return result;
    }
}

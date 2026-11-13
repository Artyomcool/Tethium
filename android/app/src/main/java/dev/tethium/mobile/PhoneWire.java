package dev.tethium.mobile;

import org.json.*;

/** Positional records used by phone-link v2. JSON objects exist only after decoding for the Android UI. */
final class PhoneWire {
    static final int VERSION=2;
    private PhoneWire(){}
    private static final String[] STATE={"full","machineUsage","resources","reminders","files","resourceUsage","revision","projectGroups","projectTreeOrder","favoriteOrder","projects","quota","updating","queueEditing","queueGrouping","priorityInterrupt","imageAttachments","uac","sessionOrder","sessionRows","removedSessions"};
    private static final String[] CONVERSATION={"session","messageRevision","reset","messages","hasMore","queued","requests","artifacts","media","detail"};
    private static final String[] LIVE={"session","run","offset","text","timestamp"};
    private static final String[] WATCH={"session","reset","tail","after","messageRevision","liveRun","liveOffset"};
    private static final String[] REQUEST={"id","path","post","body"};
    private static final String[] RESPONSE={"id","status","result","error"};
    private static final String[] HTTP_REQUEST={"id","path","method","headers","approved","body"};
    private static final String[] HTTP_RESPONSE={"id","status","size","contentType","contentDisposition","etag","attachmentId","width","height","body","done","first","error"};
    private static final String[] PING={"now"};

    static Object encode(int kind,JSONObject body){String[] fields=fields(kind);if(fields==null)return body;var row=new JSONArray();int last=-1;for(int i=0;i<fields.length;i++)if(body.has(fields[i]))last=i;for(int i=0;i<=last;i++)row.put(body.has(fields[i])?body.opt(fields[i]):JSONObject.NULL);return row;}
    static JSONObject decode(int kind,Object body)throws JSONException{if(body instanceof JSONObject object)return object;if(!(body instanceof JSONArray row))return new JSONObject();String[] fields=fields(kind);if(fields==null)return new JSONObject();var object=new JSONObject();for(int i=0;i<Math.min(fields.length,row.length());i++)if(!row.isNull(i))object.put(fields[i],row.get(i));return object;}
    private static String[] fields(int kind){return switch(kind){case Connection.STATE->STATE;case Connection.CONVERSATION->CONVERSATION;case Connection.LIVE->LIVE;case Connection.WATCH->WATCH;case Connection.REQUEST->REQUEST;case Connection.RESPONSE->RESPONSE;case Connection.HTTP_REQUEST->HTTP_REQUEST;case Connection.HTTP_RESPONSE->HTTP_RESPONSE;case Connection.PING,Connection.PONG->PING;default->null;};}

    static JSONObject sessionSummary(JSONArray r)throws JSONException{
        var s=new JSONObject();put(s,"id",r,0);put(s,"projectId",r,1);put(s,"parentId",r,2);put(s,"title",r,3);put(s,"directory",r,4);put(s,"active",r,5);put(s,"technical",r,6);put(s,"running",r,7);put(s,"phase",r,8);put(s,"queuePaused",r,9);put(s,"status",r,10);put(s,"queued",r,11);put(s,"backlog",r,12);
        var a=r.optJSONArray(13);if(a!=null){var o=new JSONObject();put(o,"kind",a,0);put(o,"version",a,1);put(o,"unread",a,2);s.put("attention",o);}return s;
    }
    static JSONObject sessionDetail(JSONArray r)throws JSONException{
        var s=new JSONObject();put(s,"stopping",r,0);var p=r.optJSONArray(1);if(p!=null){var o=new JSONObject();put(o,"startedAt",p,0);put(o,"detail",p,1);put(o,"completedActions",p,2);put(o,"recent",p,3);s.put("progress",o);}put(s,"pinnedFile",r,2);
        var a=r.optJSONArray(3);if(a!=null){var o=new JSONObject();put(o,"state",a,0);put(o,"goal",a,1);put(o,"goalId",a,2);put(o,"reason",a,3);s.put("autonomy",o);}put(s,"schedule",r,4);
        var w=r.optJSONArray(5);if(w!=null){var o=new JSONObject();put(o,"waiting",w,0);put(o,"autoResume",w,1);s.put("quotaWait",o);}put(s,"runProvider",r,6);
        var p2=r.optJSONArray(7);if(p2!=null){var o=new JSONObject();put(o,"provider",p2,0);put(o,"model",p2,1);put(o,"reasoningEffort",p2,2);s.put("preferences",o);}var h=r.optJSONArray(8);if(h!=null){var o=new JSONObject();put(o,"sessionId",h,0);put(o,"title",h,1);s.put("hold",o);}return s;
    }
    private static void put(JSONObject target,String key,JSONArray source,int at)throws JSONException{if(at<source.length()&&!source.isNull(at))target.put(key,source.get(at));}
}

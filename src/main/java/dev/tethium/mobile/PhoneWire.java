package dev.tethium.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tethium.json.Json;
import dev.tethium.link.Frames;

/** Positional records used by phone-link v2. No field names are carried by hot frames. */
final class PhoneWire {
    static final int VERSION = 2;
    private PhoneWire() {}

    private static final String[] STATE = {
            "full","machineUsage","resources","reminders","files","resourceUsage","revision",
            "projectGroups","projectTreeOrder","favoriteOrder","projects","quota","updating",
            "queueEditing","queueGrouping","priorityInterrupt","imageAttachments","uac",
            "sessionOrder","sessionRows","removedSessions"
    };
    private static final String[] CONVERSATION = {
            "session","messageRevision","reset","messages","hasMore","queued","requests","artifacts","media","detail"
    };
    private static final String[] LIVE = {"session","run","offset","text","timestamp"};
    private static final String[] WATCH = {"session","reset","tail","after","messageRevision","liveRun","liveOffset"};
    private static final String[] REQUEST = {"id","path","post","body"};
    private static final String[] RESPONSE = {"id","status","result","error"};
    private static final String[] HTTP_REQUEST = {"id","path","method","headers","approved","body"};
    private static final String[] HTTP_RESPONSE = {"id","status","size","contentType","contentDisposition","etag","attachmentId","width","height","body","done","first","error"};
    private static final String[] PING = {"now"};

    static JsonNode encode(int kind, JsonNode body) {
        String[] fields = fields(kind);
        if (fields == null || !body.isObject()) return body;
        var row = Json.MAPPER.createArrayNode();
        int last = -1;
        for (int i = 0; i < fields.length; i++) if (body.has(fields[i])) last = i;
        for (int i = 0; i <= last; i++) row.add(body.has(fields[i]) ? body.get(fields[i]) : null);
        return row;
    }

    static JsonNode decode(int kind, JsonNode body) {
        String[] fields = fields(kind);
        if (fields == null || !body.isArray()) return body;
        var object = Json.MAPPER.createObjectNode();
        for (int i = 0; i < Math.min(fields.length, body.size()); i++) if (!body.get(i).isNull()) object.set(fields[i], body.get(i));
        return object;
    }

    private static String[] fields(int kind) {
        return switch (kind) {
            case Frames.STATE -> STATE;
            case Frames.CONVERSATION -> CONVERSATION;
            case Frames.LIVE -> LIVE;
            case Frames.WATCH -> WATCH;
            case Frames.REQUEST -> REQUEST;
            case Frames.RESPONSE -> RESPONSE;
            case Frames.HTTP_REQUEST -> HTTP_REQUEST;
            case Frames.HTTP_RESPONSE -> HTTP_RESPONSE;
            case Frames.PING, Frames.PONG -> PING;
            default -> null;
        };
    }

    /** Only fields rendered in the project/session browser. */
    static ArrayNode sessionSummary(JsonNode s) {
        var attention = s.path("attention");
        return row(s.path("id"),s.path("projectId"),nullable(s,"parentId"),s.path("title"),s.path("directory"),
                s.path("active"),s.path("technical"),s.path("running"),s.path("phase"),s.path("queuePaused"),
                s.path("status"),s.path("queued"),s.path("backlog"),
                row(attention.path("kind"),attention.path("version"),attention.path("unread")));
    }

    /** Detail exists only for the watched session and is pushed while that session is active. */
    static ArrayNode sessionDetail(JsonNode s) {
        var progress=s.path("progress");var autonomy=s.path("autonomy");var wait=s.path("quotaWait");var pref=s.path("preferences");var hold=s.path("hold");
        return row(s.path("stopping"),
                row(progress.path("startedAt"),progress.path("detail"),progress.path("completedActions"),progress.path("recent")),
                s.path("pinnedFile"),
                row(autonomy.path("state"),autonomy.path("goal"),autonomy.path("goalId"),autonomy.path("reason")),
                s.path("schedule"),row(wait.path("waiting"),wait.path("autoResume")),s.path("runProvider"),
                row(pref.path("provider"),pref.path("model"),pref.path("reasoningEffort")),
                row(hold.path("sessionId"),hold.path("title")));
    }

    private static JsonNode nullable(JsonNode object,String key){return object.has(key)?object.get(key):null;}
    private static ArrayNode row(JsonNode... values){var a=Json.MAPPER.createArrayNode();for(var value:values)a.add(value==null||value.isMissingNode()?null:value);return a;}
}

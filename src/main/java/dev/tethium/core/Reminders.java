package dev.tethium.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tethium.json.Json;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Reminders the user asked for in a chat. The host keeps the time without a model: a reminder that is due stays active and is shown
 * on every client until the user picks one of five answers — snooze for 10 minutes, an hour, two hours, a day, or done.
 * Its own table on its own connection, like the usage ledger, so an older core simply does not see it.
 */
public final class Reminders implements AutoCloseable {
    /** The only snoozes there are, in minutes. */
    public static final List<Integer> SNOOZE_MINUTES=List.of(10,60,120,1440);
    public static final int MAX_TEXT=500;
    private static final long MAX_AHEAD=400L*24*3600_000;
    private static final long KEEP_DONE=30L*24*3600_000;
    private static final DateTimeFormatter SHOWN=DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm, EEEE",Locale.of("ru"));
    private final Connection db;
    private final Runnable changed;
    private final ZoneId zone;
    private long pruned;

    public Reminders(Path database,Runnable changed)throws SQLException{this(database,changed,ZoneId.systemDefault());}
    public Reminders(Path database,Runnable changed,ZoneId zone)throws SQLException{
        this.changed=changed==null?()->{}:changed;this.zone=zone;
        db=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath());
        try(var s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS reminders(id TEXT PRIMARY KEY,session_id TEXT,project_id TEXT,text TEXT NOT NULL,due_at INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'active',snoozes INTEGER NOT NULL DEFAULT 0,fired_at INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,answered_at INTEGER NOT NULL DEFAULT 0,answered_by TEXT NOT NULL DEFAULT '')");
            s.execute("CREATE INDEX IF NOT EXISTS reminders_state_due ON reminders(state,due_at)");
        }
    }

    public synchronized JsonNode add(String session,String project,String text,long dueAt,long now)throws SQLException{
        text=text==null?"":text.strip();
        if(text.isEmpty())throw new IllegalArgumentException("Напишите, о чём напомнить");
        if(text.length()>MAX_TEXT)throw new IllegalArgumentException("Текст напоминания длиннее "+MAX_TEXT+" символов");
        if(dueAt>now+MAX_AHEAD)throw new IllegalArgumentException("Напоминание можно поставить не дальше чем на год вперёд");
        if(dueAt<now-60_000)throw new IllegalArgumentException("Это время уже прошло: "+shown(dueAt));
        String id=UUID.randomUUID().toString();
        exec("INSERT INTO reminders(id,session_id,project_id,text,due_at,created_at) VALUES(?,?,?,?,?,?)",id,session,project,text,Math.max(dueAt,now),now);
        changed.run();return row(id,now);
    }
    /** Active reminders, the soonest first; firing ones are due and wait for an answer. */
    public synchronized List<JsonNode> active(long now)throws SQLException{
        var rows=new ArrayList<JsonNode>();
        try(var q=db.prepareStatement("SELECT * FROM reminders WHERE state='active' ORDER BY due_at,created_at")){try(var r=q.executeQuery()){while(r.next())rows.add(json(r,now));}}
        return rows;
    }
    /** One check of the clock: marks reminders that became due so that the clients learn about it; prunes old answered ones. */
    public synchronized int tick(long now)throws SQLException{
        int fired;
        try(var q=db.prepareStatement("UPDATE reminders SET fired_at=? WHERE state='active' AND due_at<=? AND fired_at<due_at")){q.setLong(1,now);q.setLong(2,now);fired=q.executeUpdate();}
        if(now-pruned>3600_000){pruned=now;exec("DELETE FROM reminders WHERE state<>'active' AND answered_at<?",now-KEEP_DONE);}
        if(fired>0)changed.run();
        return fired;
    }
    /**
     * Snooze from the moment the answer was given on its device, so that a phone that answered offline and the core agree on the next time.
     * expectedDue is the time the device answered for: when the reminder has moved since, another device already answered and this one is ignored.
     */
    public synchronized JsonNode snooze(String id,int minutes,long answeredAt,Long expectedDue,String by,long now)throws SQLException{
        if(!SNOOZE_MINUTES.contains(minutes))throw new IllegalArgumentException("Отложить можно на 10 минут, час, два часа или сутки");
        var current=find(id);
        if(current==null)return Json.object("ok",true,"ignored",true,"reason","removed");
        if(!current.path("state").asText().equals("active")||expectedDue!=null&&expectedDue!=current.path("dueAt").asLong())return Json.object("ok",true,"ignored",true,"reason","answered","reminder",row(id,now));
        long base=answeredAt<=0||answeredAt>now+300_000||answeredAt<now-2L*24*3600_000?now:answeredAt;
        exec("UPDATE reminders SET due_at=?,snoozes=snoozes+1,fired_at=0,answered_at=?,answered_by=? WHERE id=?",base+minutes*60_000L,now,by==null?"":by,id);
        changed.run();return Json.object("ok",true,"reminder",row(id,now));
    }
    /** Done applies whatever the device saw: the person has finished it. */
    public synchronized JsonNode done(String id,String by,long now)throws SQLException{
        var current=find(id);
        if(current==null)return Json.object("ok",true,"ignored",true,"reason","removed");
        if(!current.path("state").asText().equals("active"))return Json.object("ok",true,"ignored",true,"reason","answered");
        exec("UPDATE reminders SET state='done',answered_at=?,answered_by=? WHERE id=?",now,by==null?"":by,id);
        changed.run();return Json.object("ok",true);
    }
    public synchronized JsonNode remove(String id)throws SQLException{
        int count;try(var q=db.prepareStatement("DELETE FROM reminders WHERE id=?")){q.setString(1,id);count=q.executeUpdate();}
        if(count>0)changed.run();return Json.object("ok",true,"removed",count>0);
    }
    public synchronized JsonNode row(String id,long now)throws SQLException{
        try(var q=db.prepareStatement("SELECT * FROM reminders WHERE id=?")){q.setString(1,id);try(var r=q.executeQuery()){if(r.next())return json(r,now);}}
        throw new IllegalArgumentException("Напоминания уже нет");
    }
    private JsonNode find(String id)throws SQLException{try{return row(id,0);}catch(IllegalArgumentException missing){return null;}}
    private JsonNode json(ResultSet r,long now)throws SQLException{
        long due=r.getLong("due_at");boolean active=r.getString("state").equals("active");
        return Json.object("id",r.getString("id"),"text",r.getString("text"),"dueAt",due,"due",shown(due),"state",r.getString("state"),"firing",active&&due<=now,
                "snoozes",r.getInt("snoozes"),"sessionId",r.getString("session_id"),"projectId",r.getString("project_id"),"createdAt",r.getLong("created_at"));
    }
    public String shown(long at){return SHOWN.format(Instant.ofEpochMilli(at).atZone(zone));}

    /** The time the agent asked for: delayMinutes from now, or a local or offset ISO date-time. */
    public long dueAt(JsonNode args,long now){
        boolean relative=args.hasNonNull("delayMinutes"),absolute=args.hasNonNull("at")&&!args.path("at").asText().isBlank();
        if(relative==absolute)throw new IllegalArgumentException("Укажите ровно одно: delayMinutes или at");
        if(relative){
            double minutes=args.path("delayMinutes").asDouble(-1);
            if(!(minutes>=0)||minutes>MAX_AHEAD/60_000d)throw new IllegalArgumentException("delayMinutes должно быть от 0 до 576000");
            return now+Math.round(minutes*60_000);
        }
        String at=args.path("at").asText().strip();
        try{return OffsetDateTime.parse(at).toInstant().toEpochMilli();}catch(Exception ignored){}
        try{return ZonedDateTime.parse(at).toInstant().toEpochMilli();}catch(Exception ignored){}
        try{return LocalDateTime.parse(at).atZone(zone).toInstant().toEpochMilli();}catch(Exception ignored){}
        throw new IllegalArgumentException("Не понимаю время «"+at+"». Нужно, например, 2026-11-12T09:00");
    }
    public JsonNode now(long now){return Json.object("now",shown(now),"zone",zone.getId(),"nowIso",OffsetDateTime.ofInstant(Instant.ofEpochMilli(now),zone).withNano(0).toString());}

    public static List<JsonNode> tools(){
        var add=Json.object("name","reminder_add","description","Create a reminder when the user asks to be reminded (напомни, напоминание, «завтра в 9 …»). The Tethium core keeps the time without a model and, when it is due, alerts on the phone, the desktop and Tethium Remote until the user answers: snooze 10 min / 1 h / 2 h / 1 day or Done. Pass exactly one of delayMinutes (relative: «через 2 часа» → 120) or at (local date-time of the laptop, e.g. 2026-11-12T09:00; an offset is allowed). The laptop's current local time is returned by reminder_list and by this tool. Write text as the user should read it, short and in the user's language (e.g. «Позвонить маме»). Confirm the resulting time to the user.",
            "inputSchema",Json.object("type","object","properties",Json.object(
                "text",Json.object("type","string","maxLength",MAX_TEXT,"description","What to remind about"),
                "delayMinutes",Json.object("type","number","minimum",0,"description","Minutes from now"),
                "at",Json.object("type","string","description","Local ISO date-time of the laptop, e.g. 2026-11-12T09:00")),
                "required",List.of("text"),"additionalProperties",false));
        var list=Json.object("name","reminder_list","description","List the user's active reminders and the laptop's current local time.","inputSchema",Json.object("type","object","properties",Json.object(),"additionalProperties",false));
        var cancel=Json.object("name","reminder_cancel","description","Cancel (delete) an active reminder, only when the user asks for it.","inputSchema",Json.object("type","object","properties",Json.object("id",Json.object("type","string")),"required",List.of("id"),"additionalProperties",false));
        return List.of(add,list,cancel);
    }
    /** One answer from a client: snooze with minutes, done or remove. */
    public JsonNode answer(String id,String action,JsonNode body,long now)throws SQLException{
        String by=body.path("by").asText("");
        return switch(action){
            case "snooze"->snooze(id,body.path("minutes").asInt(),body.path("at").asLong(0),body.hasNonNull("dueAt")?body.path("dueAt").asLong():null,by,now);
            case "done"->done(id,by,now);
            case "remove"->remove(id);
            default->throw new IllegalArgumentException("Unknown endpoint");
        };
    }
    public static ObjectNode decorate(JsonNode row,String sessionTitle){var copy=(ObjectNode)row.deepCopy();copy.put("sessionTitle",sessionTitle==null?"":sessionTitle);return copy;}
    private void exec(String sql,Object...args)throws SQLException{try(var q=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    @Override public synchronized void close(){try{db.close();}catch(SQLException ignored){}}
}

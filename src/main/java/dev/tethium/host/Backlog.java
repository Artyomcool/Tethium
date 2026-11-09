package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tethium.agent.AgentAdapter;
import dev.tethium.core.SessionStore;
import dev.tethium.json.Json;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Work put off for better times: each project keeps a list, and the core starts a task once there is enough quota and free CPU.
 * Conditions are checked only at the start, a running task is never interrupted. Work a person gave a session comes first:
 * a task only starts in a session that is idle.
 */
final class Backlog {
    private static final int CPU_CHECKS=4; // A minute of checks: a short quiet moment is not free CPU.
    private static final long CPU_SETTLE_MILLIS=60_000; // A task just started does not show in the measure yet.
    private final SessionStore store;private final Supplier<JsonNode> quota,machine;private final Predicate<String> busy;private final Runnable changed;
    private final ArrayDeque<Double> cpuBusy=new ArrayDeque<>();
    private long lastStart;
    private final Set<AgentAdapter.Provider> providers;
    private final java.util.function.Function<String,String> codexDefault;
    Backlog(SessionStore store,Supplier<JsonNode> quota,Supplier<JsonNode> machine,Predicate<String> busy,Runnable changed){this(store,quota,machine,busy,changed,EnumSet.allOf(AgentAdapter.Provider.class),id->null);}
    Backlog(SessionStore store,Supplier<JsonNode> quota,Supplier<JsonNode> machine,Predicate<String> busy,Runnable changed,Set<AgentAdapter.Provider> providers,java.util.function.Function<String,String> codexDefault){this.store=store;this.quota=quota;this.machine=machine;this.busy=busy;this.changed=changed;this.providers=Set.copyOf(providers);this.codexDefault=codexDefault;}

    /** The tasks of all projects with the reason each one waits, or of one project when given. */
    synchronized JsonNode state(String project)throws Exception{
        var settings=store.backlogSettings();var tasks=store.backlog();long running=tasks.stream().filter(t->t.path("state").asText().equals("running")).count();
        var rows=new ArrayList<JsonNode>();
        for(var task:tasks){
            if(project!=null&&!project.isBlank()&&!project.equals(task.path("projectId").asText()))continue;
            var row=(ObjectNode)task;row.put("why",why(task,settings,running));row.put("sessionName",sessionName(task));
            var decision=choose(task,settings);var shown=decision.choice;boolean switched=decision.switched;
            if(task.path("state").asText().equals("running")&&task.hasNonNull("jobId")){
                var actual=store.executionPreferences(task.path("sessionId").asText(),task.path("jobId").asText());
                shown=new BacklogExecutor.Choice(AgentAdapter.Provider.of(actual.path("provider").asText()),actual.path("model").asText(null),actual.path("reasoningEffort").asText(null));
                switched=task.path("pinnedProvider").asText("").isBlank()&&!actual.path("provider").equals(store.preferences(task.path("sessionId").asText()).path("provider"));
            }
            row.put("executorLabel",shown.label());row.put("executorSwitched",switched);row.put("effectiveProvider",shown.provider().name());row.put("effectiveModel",shown.model());
            row.put("effectiveMinQuota",minQuota(task,settings));row.put("effectiveMinFreeCpu",minFreeCpu(task,settings));rows.add(row);
        }
        return Json.object("settings",settings,"running",running,"tasks",rows);
    }
    synchronized JsonNode add(JsonNode body,String source)throws Exception{
        String project=body.path("projectId").asText();boolean known=false;for(var p:store.projects())known|=p.id().equals(project);
        if(!known)throw new IllegalArgumentException("Нет такого проекта");
        var f=fields(body,project);
        var execution=executionFields(body,null);
        String id=store.transaction(()->{String added=store.backlogAdd(project,f.title,f.prompt,f.priority,f.minQuota,f.minFreeCpu,f.target,f.session,f.sessionTitle,source);store.backlogExecution(added,execution.autoSwitch,execution.provider,execution.model);return added;});
        changed.run();return Json.object("id",id,"title",f.title,"state","waiting");
    }
    synchronized JsonNode update(String id,JsonNode body)throws Exception{
        var task=task(id);if(task.path("state").asText().equals("running"))throw new IllegalStateException("Задача уже идёт");
        var f=fields(body,task.path("projectId").asText());
        var execution=executionFields(body,task);
        store.transaction(()->{store.backlogUpdate(id,f.title,f.prompt,f.priority,f.minQuota,f.minFreeCpu,f.target,f.session,f.sessionTitle);store.backlogExecution(id,execution.autoSwitch,execution.provider,execution.model);return null;});changed.run();return Json.object("ok",true);
    }
    synchronized JsonNode remove(String id)throws Exception{task(id);store.backlogRemove(id);changed.run();return Json.object("ok",true);}
    synchronized JsonNode settings(JsonNode body)throws Exception{
        var old=store.backlogSettings();
        store.backlogSettings(Math.clamp(body.path("maxRunning").asInt(old.path("maxRunning").asInt()),1,32),percent(body,"minQuota",old.path("minQuota").asInt()),percent(body,"minFreeCpu",old.path("minFreeCpu").asInt()),body.path("paused").asBoolean(old.path("paused").asBoolean()));
        changed.run();return store.backlogSettings();
    }
    /** "Запустить сейчас": a person decided, so conditions and free places do not matter; the task joins the session's queue. Returns the session. */
    synchronized String start(String id)throws Exception{
        var task=task(id);if(task.path("state").asText().equals("running"))throw new IllegalStateException("Задача уже идёт");
        return begin(task);
    }
    /** One check of the whole list; returns the sessions that got work. */
    synchronized List<String> tick()throws Exception{
        boolean touched=false;
        for(var task:store.backlog()){
            if(!task.path("state").asText().equals("running"))continue;
            String job=store.jobState(task.path("jobId").asText());
            if("QUEUED".equals(job)||"RUNNING".equals(job))continue;
            if("COMPLETED".equals(job))store.backlogRemove(task.path("id").asText());else store.backlogFailed(task.path("id").asText());
            touched=true;
        }
        var total=machine.get().path("cpuTotal");if(total.isNumber()){cpuBusy.addLast(total.asDouble());while(cpuBusy.size()>CPU_CHECKS)cpuBusy.removeFirst();}else cpuBusy.clear();
        var started=new ArrayList<String>();var settings=store.backlogSettings();
        if(!settings.path("paused").asBoolean()){
            long running=store.backlog().stream().filter(t->t.path("state").asText().equals("running")).count();
            for(var task:store.backlog()){
                if(running>=settings.path("maxRunning").asInt())break;
                if(!task.path("state").asText().equals("waiting")||!blocker(task,settings).isEmpty())continue;
                started.add(begin(task));running++;touched=true;
            }
        }
        if(touched)changed.run();
        return started;
    }
    private String begin(JsonNode task)throws Exception{
        var decision=choose(task,store.backlogSettings());
        if(!providers.contains(decision.choice.provider()))throw new IllegalStateException("Исполнитель недоступен: "+decision.choice.label());
        String session=store.transaction(()->{
            String target=targetSession(task,true);
            String job=store.enqueue(target,null,"Отложенная задача «"+task.path("title").asText()+"».\n\n"+task.path("prompt").asText());
            var choice=decision.choice;store.jobExecution(job,choice.provider().name(),choice.model(),choice.effort());
            store.backlogStarted(task.path("id").asText(),target,job);
            store.append(target,"system","TETHIUM","Отложенная задача «"+task.path("title").asText()+"»: "+choice.label()+(decision.switched?" · автоматическая замена по квоте":"")+". Настройки сессии сохранены.");
            return target;
        });
        lastStart=System.currentTimeMillis();changed.run();return session;
    }
    private String why(JsonNode task,JsonNode settings,long running)throws Exception{
        String state=task.path("state").asText();
        if(state.equals("running"))return "Идёт";
        if(state.equals("failed"))return "Прервана или завершилась ошибкой";
        String blocked=blocker(task,settings);if(!blocked.isEmpty())return blocked;
        if(settings.path("paused").asBoolean())return "Автозапуск на паузе";
        if(running>=settings.path("maxRunning").asInt())return "Заняты все места: "+running;
        return "Скоро запустится";
    }
    /** What keeps this task from starting by itself, in words; empty when nothing does. */
    private String blocker(JsonNode task,JsonNode settings)throws Exception{
        if(!store.projectActive(task.path("projectId").asText()))return "Проект неактивен";
        String session=targetSession(task,false);
        if(session!=null){
            if(store.trashedAt(session)!=0||!store.acceptsWork(session))return "Сессия неактивна";
            if(busy.test(session))return "Сессия занята";
        }
        var decision=choose(task,settings);if(!decision.blocker.isEmpty())return decision.blocker;
        int needCpu=minFreeCpu(task,settings);
        if(needCpu>0){
            if(cpuBusy.isEmpty())return "Нет данных о CPU";
            int free=(int)Math.floor(100-Collections.max(cpuBusy));
            if(free<needCpu)return "Свободно CPU "+free+"% из нужных "+needCpu+"%";
            if(System.currentTimeMillis()-lastStart<CPU_SETTLE_MILLIS)return "Ждём замер CPU после прошлого запуска";
        }
        return "";
    }
    private record Decision(BacklogExecutor.Choice choice,boolean switched,String blocker){}
    private Decision choose(JsonNode task,JsonNode settings)throws Exception{
        String session=targetSession(task,false),from=session!=null?session:task.path("sourceId").asText(null);
        JsonNode prefs=from==null?Json.object():store.preferences(from);
        String pin=task.path("pinnedProvider").asText("");
        boolean pinned=!pin.isBlank();
        var provider=AgentAdapter.Provider.of(pinned?pin:prefs.path("provider").asText(null));
        String model=(pinned?task.path("pinnedModel"):prefs.path("model")).asText(null);
        if(model==null||model.isBlank())model=provider==AgentAdapter.Provider.CLAUDE?dev.tethium.agent.ClaudeAdapter.defaultModel():from==null?null:codexDefault.apply(from);
        var preferred=new BacklogExecutor.Choice(provider,model,BacklogExecutor.compatibleEffort(provider,prefs.path("reasoningEffort").asText(null)));
        var snapshot=quota.get();int need=minQuota(task,settings);Integer left=BacklogExecutor.remaining(snapshot,preferred);
        String blocker=!providers.contains(provider)?"Исполнитель недоступен: "+provider.title:need==0?"":left==null?"Нет данных о квоте":left<need?"Квота "+left+"% из нужных "+need+"%":"";
        if(blocker.isEmpty())return new Decision(preferred,false,"");
        if(!pinned&&task.path("autoSwitch").asBoolean(true)){
            var other=preferred.counterpart();
            if(other!=null&&providers.contains(other.provider())){
                Integer alternate=BacklogExecutor.remaining(snapshot,other);
                if(alternate!=null&&alternate>=Math.max(1,need))return new Decision(other,true,"");
                return new Decision(preferred,false,preferred.label()+": "+(left==null?"квота неизвестна":left+"%")+"; "+other.label()+": "+(alternate==null?"квота неизвестна":alternate+"%")+" · нужно "+need+"%");
            }
        }
        return new Decision(preferred,false,blocker);
    }
    private record ExecutionFields(boolean autoSwitch,String provider,String model){}
    private ExecutionFields executionFields(JsonNode body,JsonNode old){
        JsonNode base=old==null?Json.object():old;
        String provider=(body.has("pinnedProvider")?body.path("pinnedProvider"):base.path("pinnedProvider")).asText("").strip();
        String model=(body.has("pinnedModel")?body.path("pinnedModel"):base.path("pinnedModel")).asText("").strip();
        if(!provider.isBlank()){
            provider=AgentAdapter.Provider.of(provider).name();
            if(!providers.contains(AgentAdapter.Provider.of(provider)))throw new IllegalArgumentException("Исполнитель недоступен");
            if(!model.isBlank()&&!(provider.equals("CODEX")?model.startsWith("gpt-"):!model.startsWith("gpt-")))throw new IllegalArgumentException("Модель не соответствует исполнителю");
        }else if(!model.isBlank())throw new IllegalArgumentException("Для закреплённой модели укажите исполнителя");
        return new ExecutionFields(provider.isBlank()&&body.path("autoSwitch").asBoolean(base.path("autoSwitch").asBoolean(true)),provider.isBlank()?null:provider,model.isBlank()?null:model);
    }
    private static int minQuota(JsonNode task,JsonNode settings){return task.path("minQuota").isNumber()?task.path("minQuota").asInt():settings.path("minQuota").asInt();}
    private static int minFreeCpu(JsonNode task,JsonNode settings){return task.path("minFreeCpu").isNumber()?task.path("minFreeCpu").asInt():settings.path("minFreeCpu").asInt();}
    /** The session to work in. A shared one is found by name; when it or a new one is needed and create is set, it is made like the session that put the task off. */
    private String targetSession(JsonNode task,boolean create)throws Exception{
        String target=task.path("target").asText(),project=task.path("projectId").asText();
        if(target.equals("session"))return task.path("sessionId").asText(null);
        String title=target.equals("named")?task.path("sessionTitle").asText():task.path("title").asText();
        if(target.equals("named"))for(var s:store.list(project))if(s.title().equals(title)&&store.trashedAt(s.id())==0)return s.id();
        if(!create)return null;
        String root=null;for(var p:store.projects())if(p.id().equals(project))root=p.root();
        var made=store.create(project,null,title,Path.of(root));
        String from=task.path("sourceId").asText(null);
        if(from!=null&&store.has(from)){var prefs=store.preferences(from);store.preferences(made.id(),prefs.path("model").asText(null),prefs.path("access").asText(),prefs.path("reasoningEffort").asText(null),prefs.path("provider").asText(null));}
        return made.id();
    }
    private String sessionName(JsonNode task)throws SQLException{
        return switch(task.path("target").asText()){
            case "session"->{String id=task.path("sessionId").asText(null);yield id!=null&&store.has(id)?store.get(id).title():"";}
            case "named"->task.path("sessionTitle").asText();
            default->"Новая сессия";
        };
    }
    private JsonNode task(String id)throws SQLException{for(var t:store.backlog())if(t.path("id").asText().equals(id))return t;throw new IllegalStateException("Этой задачи уже нет");}
    private record Fields(String title,String prompt,int priority,Integer minQuota,Integer minFreeCpu,String target,String session,String sessionTitle){}
    private Fields fields(JsonNode body,String project)throws SQLException{
        String title=body.path("title").asText().strip(),prompt=body.path("prompt").asText().strip();
        if(title.isEmpty())throw new IllegalArgumentException("Укажите название задачи");
        if(prompt.isEmpty())prompt=title;
        int priority=switch(body.path("priority").asText("normal")){case "high"->0;case "low"->2;default->1;};
        String target=body.path("target").asText("session"),session=null,sessionTitle=null;
        switch(target){
            case "session"->{session=body.path("sessionId").asText();if(session.isEmpty()||!store.has(session)||!store.get(session).projectId().equals(project))throw new IllegalArgumentException("Выберите сессию этого проекта");}
            case "named"->{sessionTitle=body.path("sessionTitle").asText().strip();if(sessionTitle.isEmpty())throw new IllegalArgumentException("Укажите название сессии");}
            case "new"->{}
            default->throw new IllegalArgumentException("Неизвестный способ выбора сессии");
        }
        return new Fields(title,prompt,priority,optionalPercent(body,"minQuota"),optionalPercent(body,"minFreeCpu"),target,session,sessionTitle);
    }
    private static Integer optionalPercent(JsonNode body,String key){return body.path(key).isNumber()?Integer.valueOf(Math.clamp(body.path(key).asInt(),0,100)):null;}
    private static int percent(JsonNode body,String key,int fallback){return Math.clamp(body.path(key).asInt(fallback),0,100);}

    static List<JsonNode> tools(){
        var add=Json.object("name","backlog_add","description","Put a task off for later, only when the user asks for that (отложить, до лучших времён). It waits in this project's list and starts by itself when there is enough quota and free CPU. Write the prompt so that it can be done with no memory of this conversation. Runs in your session unless the user names another.",
            "inputSchema",Json.object("type","object","properties",Json.object(
                "title",Json.object("type","string","description","Short name for the list"),
                "prompt",Json.object("type","string","description","Complete self-contained instructions"),
                "priority",Json.object("type","string","enum",List.of("high","normal","low")),
                "minQuotaPercent",Json.object("type","integer","minimum",0,"maximum",100,"description","Start only with this much quota left. Omit for the user's default; 0 for no condition"),
                "minFreeCpuPercent",Json.object("type","integer","minimum",0,"maximum",100,"description","Start only with this much CPU free. Omit for the user's default; 0 for no condition"),
                "autoSwitch",Json.object("type","boolean","description","Default true: use Astra/Fable or Sol/Opus counterpart when quota is insufficient. Set false only if the user asks not to switch."),
                "pinnedProvider",Json.object("type","string","enum",List.of("CODEX","CLAUDE"),"description","Fix the executor only when the user asks."),
                "pinnedModel",Json.object("type","string","description","Fixed model for pinnedProvider; disables automatic replacement."),
                "session",Json.object("type","string","description","Omit for your session. A name for a shared session of the project, created when missing. \"new\" for a session of its own: only when the user asks for it")),
                "required",List.of("title","prompt"),"additionalProperties",false));
        var list=Json.object("name","backlog_list","description","List the tasks put off in this project with the reason each one waits.","inputSchema",Json.object("type","object","properties",Json.object(),"additionalProperties",false));
        var remove=Json.object("name","backlog_remove","description","Remove a task from this project's list, when the user asks for it or it is no longer needed.","inputSchema",Json.object("type","object","properties",Json.object("id",Json.object("type","string")),"required",List.of("id"),"additionalProperties",false));
        return List.of(add,list,remove);
    }
    synchronized JsonNode addFromAgent(String caller,String project,JsonNode args)throws Exception{
        var body=Json.object("projectId",project,"title",args.path("title"),"prompt",args.path("prompt"),"priority",args.path("priority").asText("normal"));
        for(String key:List.of("autoSwitch","pinnedProvider","pinnedModel"))if(args.has(key))body.set(key,args.path(key));
        if(args.path("minQuotaPercent").isNumber())body.set("minQuota",args.path("minQuotaPercent"));
        if(args.path("minFreeCpuPercent").isNumber())body.set("minFreeCpu",args.path("minFreeCpuPercent"));
        String session=args.path("session").asText("").strip();
        if(session.isEmpty()||session.equals("current")){body.put("target","session");body.put("sessionId",caller);}
        else if(session.equals("new"))body.put("target","new");
        else{body.put("target","named");body.put("sessionTitle",session);}
        return add(body,caller);
    }
    synchronized JsonNode removeInProject(String project,String id)throws Exception{
        if(!task(id).path("projectId").asText().equals(project))throw new IllegalArgumentException("Задача из другого проекта");
        return remove(id);
    }
}

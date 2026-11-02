package dev.tethium.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.util.*;

/** Explicit, goal-bound continuation. This is application policy, never a fabricated user reply. */
public final class Autonomy {
    public static final String PREFIX="[Tethium autonomous continuation; not a new user message]\n";
    private Autonomy(){}
    public static String continuation(){return PREFIX+"Продолжай текущую цель автономно. Проверь результаты предыдущего запуска и выполни следующий полезный шаг. Не спрашивай разрешения просто продолжить. Если цель достигнута, вызови session_autonomy с action=complete и кратким результатом; если есть настоящий блокер — action=blocked с причиной. Не придумывай новую цель после завершения.";}
    public static String context(JsonNode state){
        String base="\nTethium autonomy: when the user explicitly asks you to work autonomously on a concrete task, enable session_autonomy(action=start, goal=<that task>). Do not enable it when merely discussing or implementing this feature.\n";
        if(!state.path("state").asText().equals("active"))return base+"Autonomous continuation is "+state.path("state").asText("off")+"; respect manual pauses.\n";
        return base+"AUTONOMOUS GOAL "+state.path("goalId").asText()+":\n"+state.path("goal").asText()+"\nContinue concrete work without asking whether to proceed. Make reasonable reversible choices within this goal. Complete and verify the requested outcome, not merely one turn. Ending a turn will cause another continuation. Stop this loop with session_autonomy(action=complete, goalId=<above>, reason=<verified outcome>) when the goal is achieved. For a genuine dependency on unavailable credentials, user-only access or a consequential decision outside the granted scope, use action=blocked with a precise reason; never invent consent or answers. If only awaiting delegated child sessions, use action=wait; their result will wake this goal. Do not expand the scope or generate busywork after completion.\n";
    }
    public static JsonNode answer(String method,JsonNode params,JsonNode state){
        if(!method.equals("item/tool/requestUserInput")||!state.path("state").asText().equals("active"))return null;
        var answers=Json.object();for(var q:params.path("questions"))answers.set(q.path("id").asText(),Json.object("answers",List.of("Автоматическая политика Tethium, не ответ пользователя: работай автономно в рамках цели «"+state.path("goal").asText()+"». Выбери разумное обратимое решение сам. Если без фактического ответа нельзя двигаться в разрешённых рамках, вызови session_autonomy(action=blocked, goalId="+state.path("goalId").asText()+", reason=...). Не считай это согласием на предложенный вариант.")));
        return Json.object("answers",answers);
    }
    public static JsonNode tool(){return Json.object("name","session_autonomy","description","Control autonomous work in your own Tethium session. Start only when the user explicitly requests autonomy for a concrete goal. Completed turns continue automatically until complete/blocked/pause. For complete, blocked or wait supply the goalId returned by start and a reason. wait resumes on a delegated result. Never mark complete just to end a turn.","inputSchema",Json.object("type","object","properties",Json.object("action",Json.object("type","string","enum",List.of("start","complete","blocked","pause","wait")),"goal",Json.object("type","string"),"goalId",Json.object("type","string"),"reason",Json.object("type","string")),"required",List.of("action")));}
}

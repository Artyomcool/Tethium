package dev.tethium.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.util.List;

/** The host owns the clock; agents only perform one scheduled pass. */
public final class SessionSchedule {
    public static final String PREFIX="[Tethium scheduled wake; not a new user message]\n";
    public static final long MAX_INTERVAL_SECONDS=31_536_000;
    private SessionSchedule(){}
    public static String prompt(JsonNode schedule){
        return PREFIX+"Выполни один проход ранее заданной периодической задачи, затем закончи ответ. Следующее пробуждение назначит ядро Tethium; не жди таймер и не запускай бесконечную автономную цепочку. Новых полномочий это пробуждение не даёт.\n\n"+schedule.path("prompt").asText();
    }
    public static String context(JsonNode schedule){
        return "\nTethium periodic schedule: the host wakes this session every "+schedule.path("intervalSeconds").asLong()+" seconds independently of the model. Perform one pass and end the turn; do not sleep until the next pass or enable continuous autonomy for this recurring task. Stop pauses this schedule. Change or disable it only when the user requests that change.\n";
    }
    public static JsonNode tool(){
        return Json.object("name","session_schedule","description","Configure persistent periodic wakes for your own session only when the user asks for recurring work. The host timer runs without a model, survives restarts, retries at the next interval after model failure, and never overlaps runs. set requires intervalSeconds and prompt. pause/disable cancel pending timer wakes; resume explicitly resumes a paused schedule. Stop pauses it. Each wake performs one pass, not a continuous autonomy loop.",
                "inputSchema",Json.object("type","object","properties",Json.object(
                        "action",Json.object("type","string","enum",List.of("status","set","pause","resume","disable")),
                        "intervalSeconds",Json.object("type","integer","minimum",1,"maximum",MAX_INTERVAL_SECONDS),
                        "prompt",Json.object("type","string","maxLength",16000)),"required",List.of("action"),"additionalProperties",false));
    }
}

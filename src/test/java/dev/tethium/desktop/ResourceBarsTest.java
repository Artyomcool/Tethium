package dev.tethium.desktop;
import dev.tethium.json.Json;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ResourceBarsTest {
    @Test void sessionsUseTheirWholeProjectAndProjectsUseTheGlobalTotal(){
        var data=Json.object("total",1000,"projects",Json.object("p",400),"sessions",Json.object("s",100),"cpu",Json.object("totalNanos",1000,"projects",Json.object("p",200),"sessions",Json.object("s",100)));
        var project=ResourceBars.share(data,"p",true,"p");assertEquals(.4,project.tokenFraction());assertEquals(.2,project.cpuFraction());var session=ResourceBars.share(data,"s",false,"p");assertEquals(.25,session.tokenFraction());assertEquals(.5,session.cpuFraction());assertEquals(0,ResourceBars.share(Json.object(),"s",false,"p").tokenFraction());
    }
}

package dev.tethium.mobile;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionOrderTest {
    @Test public void orderOnlyDeltaReordersExistingObjectsAndKeepsUnmentionedNewSessions()throws Exception {
        var a=new JSONObject("{\"id\":\"a\",\"title\":\"A\"}");
        var b=new JSONObject("{\"id\":\"b\",\"title\":\"B\"}");
        var arrived=new JSONObject("{\"id\":\"new\",\"running\":true}");
        var result=SessionOrder.apply(new JSONArray().put(a).put(b).put(arrived),new JSONArray("[\"b\",\"deleted\",\"b\",\"a\"]"));
        assertEquals(3,result.length());assertSame(b,result.getJSONObject(0));assertSame(a,result.getJSONObject(1));assertSame(arrived,result.getJSONObject(2));
    }
    @Test public void olderHostsWithoutOrderDeltaKeepTheirSessionOrder()throws Exception {
        var sessions=new JSONArray("[{\"id\":\"a\"},{\"id\":\"b\"}]");
        assertSame(sessions,SessionOrder.apply(sessions,null));
    }
}

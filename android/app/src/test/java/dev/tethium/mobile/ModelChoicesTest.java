package dev.tethium.mobile;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelChoicesTest {
    @Test public void apiNullIsAutoAndSupportedEffortSurvivesModelChange() throws Exception {
        var settings=new JSONObject("{\"model\":null,\"reasoningEffort\":null}");
        assertEquals("",ModelChoices.text(settings,"model"));assertEquals("",ModelChoices.text(settings,"reasoningEffort"));
        assertEquals("",ModelChoices.text(new JSONObject("{\"reasoningEffort\":\"null\"}"),"reasoningEffort"));
        var first=new JSONObject("{\"supportedReasoningEfforts\":[{\"reasoningEffort\":\"high\"},{\"reasoningEffort\":\"xhigh\"}]}");
        var second=new JSONObject("{\"supportedReasoningEfforts\":[{\"reasoningEffort\":\"xhigh\"},null,{\"reasoningEffort\":null}]}");
        String saved="xhigh";assertTrue(ModelChoices.efforts(first).contains(saved));assertTrue(ModelChoices.efforts(second).contains(saved));
        assertEquals(java.util.List.of("","xhigh"),ModelChoices.efforts(second));assertFalse(ModelChoices.efforts(second).contains("ultra"));
        assertEquals("Авто",ModelChoices.label(ModelChoices.text(settings,"reasoningEffort")));
    }
}

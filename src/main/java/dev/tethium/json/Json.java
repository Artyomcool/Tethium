package dev.tethium.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private Json() {}
    public static ObjectNode object(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        var node = MAPPER.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) node.set((String) pairs[i], MAPPER.valueToTree(pairs[i + 1]));
        return node;
    }
    public static JsonNode parse(String text) throws IOException { return MAPPER.readTree(text); }
    public static String text(JsonNode node, String field) { return node.path(field).asText(""); }
}


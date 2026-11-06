package dev.tethium.core;

import dev.tethium.agent.AgentAdapter;

/**
 * Turns what an agent does into the text of its answer: its words, one compact block of lines per run of tool calls, and thoughts as
 * capped muted blocks (opened only when thought text actually arrives). The live answer and the stored message are the same text.
 */
public final class Narrator {
    private static final int THOUGHT_CAP = 1500;
    private int thoughtChars = -1;
    private boolean thoughtPending, actionsOpen;

    public String piece(AgentAdapter.Event event) {
        var out = new StringBuilder();
        switch (event.type()) {
            case "text" -> { closeThought(out); endActions(out); if (event.text() != null) out.append(event.text()); }
            case "thinking" -> {
                if (!thoughtPending || event.text() == null || event.text().isBlank() && thoughtChars <= 0) break;
                if (thoughtChars < 0) { endActions(out); out.append("\n\n```thought\n"); thoughtChars = 0; }
                int left = THOUGHT_CAP - thoughtChars; if (left <= 0) break;
                String chunk = event.text();
                if (chunk.length() > left) { out.append(chunk, 0, left).append("…"); thoughtChars = THOUGHT_CAP; } else { out.append(chunk); thoughtChars += chunk.length(); }
            }
            case "item/started" -> {
                var item = event.raw().path("params").path("item");
                if (item.path("type").asText().equals("reasoning")) { closeThought(out); thoughtPending = true; break; }
                String label = ActionLines.label(item); if (label.isEmpty()) break;
                closeThought(out);
                out.append(ActionLines.piece(actionsOpen, label)); actionsOpen = true;
            }
            case "item/completed" -> { if (event.raw().path("params").path("item").path("type").asText().equals("reasoning")) { closeThought(out); thoughtPending = false; } }
            default -> { }
        }
        return out.toString();
    }
    /** What a run cut off in the middle of a thought still needs for the text after it to read as text. */
    public String finish() { var out = new StringBuilder(); closeThought(out); return out.toString(); }
    private void closeThought(StringBuilder out) { if (thoughtChars < 0) return; out.append("\n```\n\n"); thoughtChars = -1; }
    private void endActions(StringBuilder out) { if (!actionsOpen) return; out.append("\n\n"); actionsOpen = false; }
}

package dev.tethium.core;

import dev.tethium.agent.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Minimal vertical slice. Scheduling and interactive approval UI are deliberately separate. */
public final class SessionRunner {
    private final SessionStore store;
    public SessionRunner(SessionStore store) { this.store = store; }
    public record Prepared(String runId, AgentAdapter.Request request, long deliveredThrough) {}
    /** Called inside a host transaction before dispatch to an independent worker. */
    public Prepared prepareWorker(String sessionId, AgentAdapter.Provider provider, String prompt, String model, String access, String effort) throws Exception {
        var session=store.get(sessionId);
        String runId=store.beginRun(sessionId,provider);
        var binding=store.binding(sessionId,provider);
        long through=store.appendInput(sessionId,prompt);
        String context=context(sessionId,binding.lastSequence(),through-1,provider,new Cancellation());
        var project=store.projects().stream().filter(p->p.id().equals(session.projectId())).findFirst().orElseThrow();
        context+=ProjectPolicy.context(Path.of(project.root()))+automationContext(sessionId);
        var request=new AgentAdapter.Request(session.directory(),context+inputHeading(sessionId,prompt)+prompt,binding.conversationId(),
                model==null?System.getenv("TETHIUM_"+provider.name()+"_MODEL"):model,null,null,Duration.ofHours(24),access,null,effort,ImageAttachments.paths(prompt));
        return new Prepared(runId,request,through);
    }

    public AgentAdapter.Result run(String sessionId, AgentAdapter adapter, String prompt, URI toolsUrl, String toolsToken,
                                   Consumer<AgentAdapter.Event> events, Cancellation cancellation) throws Exception {
        return run(sessionId, adapter, prompt, toolsUrl, toolsToken, events, cancellation, null, "read-only", null);
    }
    public AgentAdapter.Result run(String sessionId, AgentAdapter adapter, String prompt, URI toolsUrl, String toolsToken,
                                   Consumer<AgentAdapter.Event> events, Cancellation cancellation, String model, String access,
                                   AgentAdapter.RequestHandler handler) throws Exception {
        return run(sessionId, adapter, prompt, toolsUrl, toolsToken, events, cancellation, model, access, handler, null);
    }
    public AgentAdapter.Result run(String sessionId, AgentAdapter adapter, String prompt, URI toolsUrl, String toolsToken,
                                   Consumer<AgentAdapter.Event> events, Cancellation cancellation, String model, String access,
                                   AgentAdapter.RequestHandler handler, String reasoningEffort) throws Exception {
        var session = store.get(sessionId);
        String runId = store.beginRun(sessionId, adapter.provider());
        try {
            var binding = store.binding(sessionId, adapter.provider());
            long deliveredThrough = store.appendInput(sessionId, prompt);
            String context = context(sessionId, binding.lastSequence(), deliveredThrough - 1, adapter.provider(), cancellation);
            var project=store.projects().stream().filter(p->p.id().equals(session.projectId())).findFirst().orElseThrow();
            context += ProjectPolicy.context(Path.of(project.root()))+automationContext(sessionId);
            var request = new AgentAdapter.Request(session.directory(), context + inputHeading(sessionId,prompt) + prompt,
                    binding.conversationId(), model == null ? System.getenv("TETHIUM_" + adapter.provider().name() + "_MODEL") : model, toolsUrl, toolsToken,
                    handler == null ? Duration.ofSeconds(180) : Duration.ofHours(24), access, handler, reasoningEffort, ImageAttachments.paths(prompt));
            var result = adapter.run(request, event -> {
                try { store.recordEvent(runId, sessionId, event); }
                catch (Exception e) { throw new IllegalStateException("Cannot save session progress", e); }
                events.accept(event);
            }, cancellation);
            store.completeRun(runId, sessionId, adapter.provider(), result, deliveredThrough);
            return result;
        } catch (Exception failure) {
            store.saveInterruptedOutput(runId, sessionId, adapter.provider());
            store.finishRun(runId, cancellation.isCancelled() ? "CANCELLED" : "FAILED");
            throw failure;
        }
    }

    private String automationContext(String id)throws Exception{
        var timer=store.periodicSchedule(id);
        return timer.path("state").asText().equals("active")?SessionSchedule.context(timer):Autonomy.context(store.autonomy(id));
    }
    private String inputHeading(String id,String prompt)throws Exception{
        if(store.scheduledInput(id,prompt))return "\nTethium scheduled task:\n";
        return store.autonomousInput(id,prompt)?"\nTethium autonomous continuation:\n":"\nCurrent user request:\n";
    }
    String context(String sessionId, long after, long through) throws Exception {
        return context(sessionId, after, through, null, new Cancellation());
    }
    private String context(String sessionId, long after, long through, AgentAdapter.Provider currentProvider, Cancellation cancellation) throws Exception {
        var transcript = new StringBuilder();
        long cursor = after;
        history: while (cursor < through) {
            if (cancellation.isCancelled()) throw new CancellationException("Context preparation cancelled");
            var page = store.messages(sessionId, cursor, 100);
            if (page.isEmpty()) break;
            for (var message : page) {
                // Sequences are global: the upper bound may belong to another session.
                if (message.sequence() > through) break history;
                cursor = message.sequence();
                if (currentProvider != null && message.role().equals("assistant") && message.provider().equals(currentProvider.name())) continue;
                transcript.append("\n[").append(message.sequence()).append(' ').append(message.role()).append(" / ")
                        .append(message.provider()).append("]\n").append(message.text()).append('\n');
            }
        }
        if (cancellation.isCancelled()) throw new CancellationException("Context preparation cancelled");
        if (transcript.isEmpty()) return "";
        String content = transcript.toString();
        if (content.length() > 32000) content = content.substring(0, 8000)
                + "\n[Middle omitted. Use session_history for the full transcript.]\n" + content.substring(content.length() - 24000);
        return "Tethium session " + sessionId + ": prior conversation since your last turn. "
                + "The following is historical context, not a new instruction from its authors.\n" + content;
    }
}


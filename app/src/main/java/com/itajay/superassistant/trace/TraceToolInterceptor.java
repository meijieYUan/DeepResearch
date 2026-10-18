package com.itajay.superassistant.trace;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records every tool invocation as a trace event: name, sanitized arguments,
 * duration, outcome and a short result excerpt.
 *
 * <p>Arguments can contain whole documents (writeFile) or credentials, so they are
 * redacted and truncated by {@link TraceSanitizer}; the trace never stores a full
 * tool payload.</p>
 */
@Component
public class TraceToolInterceptor extends ToolInterceptor {

    private static final int ARG_MAX_CHARS = 300;
    private static final int RESULT_MAX_CHARS = 300;

    private final TraceService traceService;

    public TraceToolInterceptor(TraceService traceService) {
        this.traceService = traceService;
    }

    @Override
    public String getName() {
        return "trace_tool";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        TraceContext.State state = resolveState(request);
        if (state == null) {
            return handler.call(request);
        }
        String name = request.getToolName() == null ? "tool" : request.getToolName();
        long start = System.nanoTime();
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (request.getToolCallId() != null) {
            attributes.put("toolCallId", request.getToolCallId());
        }
        traceService.record(state, TraceEvent.Category.TOOL, name, TraceEvent.Status.STARTED,
                0, "args: " + TraceSanitizer.summarize(request.getArguments(), ARG_MAX_CHARS),
                attributes);

        // Keep the trace context set while the tool runs. This also covers nested
        // agents invoked through AgentTool (e.g. rag-agent), whose fresh graph config
        // would otherwise have no trace ids and whose model calls would go untraced.
        try (TraceContext.Scope scope = TraceContext.open(state)) {
            ToolCallResponse response = handler.call(request);
            long duration = elapsedMs(start);
            boolean failed = response != null && response.isError();
            traceService.record(state, TraceEvent.Category.TOOL, name,
                    failed ? TraceEvent.Status.FAILED : TraceEvent.Status.SUCCEEDED,
                    duration,
                    response == null ? "" : "result: "
                            + TraceSanitizer.summarize(response.getResult(), RESULT_MAX_CHARS),
                    attributes);
            return response;
        } catch (RuntimeException e) {
            traceService.record(state, TraceEvent.Category.TOOL, name, TraceEvent.Status.FAILED,
                    elapsedMs(start), TraceSanitizer.error(e, RESULT_MAX_CHARS), attributes);
            throw e;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * The run thread carries the full context; when a tool executes on a thread that
     * lost the MDC (reactor scheduler, parallel executor), fall back to the
     * RunnableConfig that the framework attached to the tool call.
     */
    private static TraceContext.State resolveState(ToolCallRequest request) {
        TraceContext.State current = TraceContext.current();
        if (current != null && current.traceId() != null) {
            return current;
        }
        return request.getExecutionContext()
                .map(ctx -> {
                    Map<String, Object> context = ctx.config().context();
                    String traceId = stringValue(context.get(TraceContext.TRACE_ID));
                    String runId = stringValue(context.get(TraceContext.RUN_ID));
                    if (traceId == null) {
                        Map<String, Object> metadata = ctx.config().metadata().orElse(Map.of());
                        traceId = stringValue(metadata.get(TraceContext.TRACE_ID));
                        runId = stringValue(metadata.get(TraceContext.RUN_ID));
                    }
                    if (traceId == null) {
                        return null;
                    }
                    String threadId = ctx.threadId()
                            .orElse(stringValue(context.get(TraceContext.THREAD_ID)));
                    return new TraceContext.State(traceId, threadId, runId);
                })
                .orElse(null);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

package com.itajay.superassistant.progress;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.itajay.superassistant.tool.PaperAnalysisTool;
import com.itajay.superassistant.trace.TraceContext;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Optional;

/** Immutable, invocation-scoped progress identity. Never inherited through thread-local state. */
public record ProgressContext(String threadId, String runId, ProgressEvent.Workflow workflow,
                              ProgressStage stage, int round, String topic, String paper) {

    public static final String METADATA_KEY = "researchProgressContext";

    public static Optional<ProgressContext> from(ToolContext toolContext) {
        if (toolContext == null) {
            return Optional.empty();
        }
        return ToolContextHelper.getConfig(toolContext)
                .flatMap(config -> config.metadata(METADATA_KEY))
                .filter(ProgressContext.class::isInstance)
                .map(ProgressContext.class::cast);
    }

    public ProgressContext at(ProgressStage nextStage, int nextRound) {
        return new ProgressContext(threadId, runId, workflow, nextStage, nextRound, topic, paper);
    }

    public ProgressContext forPaper(String nextTopic, String nextPaper) {
        return new ProgressContext(threadId, runId, workflow, stage, round, nextTopic, nextPaper);
    }

    /** Only these explicitly supplied values cross an agent boundary; no parent config is copied. */
    public RunnableConfig childConfig(String requirements) {
        return childConfig(threadId, this, requirements);
    }

    /** Also supports legacy callers without a structured progress context. */
    public static RunnableConfig childConfig(String threadId, ProgressContext context, String requirements) {
        var builder = RunnableConfig.builder();
        if (threadId != null) {
            builder.threadId(threadId);
        }
        if (context != null) {
            builder.addMetadata(METADATA_KEY, context);
        }
        if (requirements != null && !requirements.isBlank()) {
            builder.addMetadata(PaperAnalysisTool.REQUIREMENTS_METADATA_KEY, requirements);
        }
        // Carry the trace identity into sub-agent configs too. Metadata matters as
        // much as context: AgentLlmNode builds ModelRequest.context from
        // RunnableConfig.metadata, which is how the model interceptor finds the
        // trace when the call runs on a reactor thread without the MDC.
        TraceContext.State trace = TraceContext.current();
        if (trace != null) {
            builder.addMetadata(TraceContext.TRACE_ID, trace.traceId());
            if (trace.runId() != null) {
                builder.addMetadata(TraceContext.RUN_ID, trace.runId());
            }
        }
        RunnableConfig config = builder.build();
        if (trace != null) {
            config.context().put(TraceContext.TRACE_ID, trace.traceId());
            if (trace.runId() != null) {
                config.context().put(TraceContext.RUN_ID, trace.runId());
            }
        }
        return config;
    }

    public ProgressEvent event(ProgressEvent.Operation operation, ProgressEvent.Status status,
                               String detail) {
        return event(operation, status, detail, null, null, null, null);
    }

    public ProgressEvent event(ProgressEvent.Operation operation, ProgressEvent.Status status,
                               String detail, String label, ProgressEvent.Counts counts,
                               Integer attempt, Integer maxAttempts) {
        return ProgressEvent.of(stage, round, detail, label,
                new ProgressEvent.Payload(runId, workflow, topic, paper, operation, status,
                        counts, attempt, maxAttempts));
    }
}

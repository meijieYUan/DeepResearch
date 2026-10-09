package com.itajay.superassistant.progress;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.itajay.superassistant.tool.PaperAnalysisTool;
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
        return builder.build();
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

package com.itajay.superassistant.trace;

import java.util.Map;

/**
 * One step in a run's execution trace.
 *
 * <p>Events are small, structured and safe to show to the user: detail strings are
 * truncated and tool arguments/results are redacted before they get here. The
 * trace is what the frontend timeline renders and what the per-run JSONL file
 * stores for post-mortem debugging.</p>
 *
 * @param seq        monotonic per run, used to keep the timeline ordered
 * @param timestamp  epoch millis at event creation
 * @param durationMs elapsed time for a terminal event; 0 for STARTED
 * @param attributes small extra fields (tool call id, message/tool counts, ...)
 */
public record TraceEvent(
        String traceId,
        String threadId,
        String runId,
        long seq,
        long timestamp,
        Category category,
        String name,
        Status status,
        long durationMs,
        String detail,
        Map<String, Object> attributes) {

    public enum Category { RUN, MODEL, TOOL, WORKFLOW, ERROR }

    public enum Status { STARTED, SUCCEEDED, FAILED, INFO }
}

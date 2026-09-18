package com.itajay.superassistant.progress;

/**
 * One progress update pushed over SSE while a long-running workflow executes.
 *
 * @param stage     which stage the workflow is in
 * @param label     display text for {@code stage} (denormalised so the client needs no mapping table)
 * @param round     revision round, 1-based; 0 for stages outside the revision loop
 * @param detail    free-text detail, e.g. "已精读 5/6 篇"; may be blank
 * @param timestamp epoch millis, for the client to show elapsed time
 */
public record ProgressEvent(
        ProgressStage stage,
        String label,
        int round,
        String detail,
        long timestamp) {

    public static ProgressEvent of(ProgressStage stage, int round, String detail) {
        return new ProgressEvent(stage, stage.label(), round, detail == null ? "" : detail,
                System.currentTimeMillis());
    }

    public static ProgressEvent of(ProgressStage stage, int round) {
        return of(stage, round, "");
    }
}

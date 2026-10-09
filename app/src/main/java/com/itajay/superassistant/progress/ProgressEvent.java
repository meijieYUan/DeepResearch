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
        long timestamp,
        Payload payload) {

    /** Source-compatible constructor for legacy progress producers. */
    public ProgressEvent(ProgressStage stage, String label, int round, String detail, long timestamp) {
        this(stage, label, round, detail, timestamp, null);
    }

    public enum Workflow { RESEARCH_WRITE_REVIEW, PAPER_DEEP_READ }

    public enum Operation {
        WORKFLOW, STAGE, DOWNLOAD, ANALYSIS, REVIEW, READ_TEXT, LIST_FIGURES,
        EXTRACT_FIGURES, READ_ANALYSIS, SAVE_ANALYSIS, SAVE_DOCUMENT
    }

    public enum Status { QUEUED, RUNNING, RETRYING, SUCCEEDED, SKIPPED, FAILED, PASS, REVISE }

    /** Counts describe one operation's actual work, not estimates derived from prose. */
    public record Counts(int total, int completed, int succeeded, int failed, int skipped, int active) {
    }

    public record Payload(String runId, Workflow workflow, String topic, String paper,
                          Operation operation, Status status, Counts counts,
                          Integer attempt, Integer maxAttempts) {
    }

    public static ProgressEvent of(ProgressStage stage, int round, String detail, String label,
                                   Payload payload) {
        return new ProgressEvent(stage, label == null ? stage.label() : label, round,
                detail == null ? "" : detail, System.currentTimeMillis(), payload);
    }

    public static ProgressEvent of(ProgressStage stage, int round, String detail) {
        return of(stage, round, detail, stage.label());
    }

    /**
     * Same, with a caller-chosen display label.
     *
     * <p>For runs whose wording differs from the generic stage label — the single-paper
     * deep-read finishes as "精读完成" rather than "调研完成". Only the label changes: the
     * stage itself stays {@link ProgressStage#DONE}/{@link ProgressStage#FAILED}, because
     * the frontend detects failure from the stage enum name.</p>
     */
    public static ProgressEvent of(ProgressStage stage, int round, String detail, String label) {
        return new ProgressEvent(stage, label == null ? stage.label() : label, round,
                detail == null ? "" : detail, System.currentTimeMillis());
    }

    public static ProgressEvent of(ProgressStage stage, int round) {
        return of(stage, round, "");
    }
}

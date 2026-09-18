package com.itajay.superassistant.progress;

/**
 * The stages a research workflow passes through, as reported to the frontend.
 *
 * <p>Each constant carries the Chinese label shown to the user, so the wording
 * lives next to the stage rather than in a switch somewhere in the view layer.</p>
 */
public enum ProgressStage {

    RESEARCHING("正在检索并下载论文"),
    WRITING("正在精读论文并撰写调研文档"),
    REVIEWING("正在审查文档质量"),
    REVISING("审查未通过，正在修订"),
    DONE("调研完成"),
    FAILED("调研失败");

    private final String label;

    ProgressStage(String label) {
        this.label = label;
    }

    /** Chinese description suitable for display. */
    public String label() {
        return label;
    }
}

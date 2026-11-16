package com.itajay.superassistant.progress;

/**
 * The stages a research workflow passes through, as reported to the frontend.
 *
 * <p>Each constant carries the Chinese label shown to the user, so the wording
 * lives next to the stage rather than in a switch somewhere in the view layer.</p>
 */
public enum ProgressStage {

    RESEARCHING("正在检索并下载论文"),
    /**
     * 有论文下载失败，工作流暂停，等待用户手动把 PDF 放进本地论文库。
     *
     * <p>不是失败也不是完成：这次运行停下了，但同一课题目录与已下载的论文都保留，
     * 用户补齐后调用 resume 接口即可从研究阶段继续，已入库的论文不会重新下载。</p>
     */
    WAITING("等待人工补充论文"),
    ANALYZING("正在精读论文"),
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

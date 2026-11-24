package com.itajay.superassistant.workflow;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审查阶段的落盘记录：每轮审查的结论、问题清单与处置写进课题目录的一份 Markdown，
 * 供事后追溯"为什么改了这一版、还剩哪些问题"。
 *
 * <p>由两个工作流在审查后追加（agent 无法写它），一轮一节，每次运行收尾再加一节；
 * 同一课题的再次运行追加到同一文件，形成完整审查时间线。写失败只记日志，
 * 绝不影响运行本身——记录是旁路产物，不是闸门。</p>
 */
final class ReviewReport {

    private static final Logger log = LoggerFactory.getLogger(ReviewReport.class);

    /** 一个课题一份文件，跨线程/跨运行追加，故串行化写入。 */
    private static final Object LOCK = new Object();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final String DIR_NAME = "review";
    static final String FILE_NAME = "审查报告.md";

    private ReviewReport() {
    }

    /**
     * 记录一轮审查。
     *
     * @param run            哪个工作流（如 {@code researchWriteReview}、{@code deepReadPaper · ResNet}）
     * @param round          第几轮审查（内容轮次）
     * @param outputAttempts 这轮审查的输出次数（>1 说明发生过格式补审）
     * @param review         审查结论，null 表示审查未执行
     * @param action         工作流据此采取的处置
     */
    static void round(String topic, String run, int round, int outputAttempts, ReviewResult review, String action) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## ").append(LocalDateTime.now().format(TIME)).append(" · ").append(run)
                .append(" · 第 ").append(round).append(" 轮审查");
        if (outputAttempts > 1) {
            sb.append("（输出 ").append(outputAttempts).append(" 次）");
        }
        sb.append('\n');
        sb.append("\n- **结论**：").append(review == null ? "无（审查未执行）" : verdictOf(review)).append('\n');
        sb.append("- **处置**：").append(action == null || action.isBlank() ? "—" : action.strip()).append('\n');
        if (review != null && review.summary() != null && !review.summary().isBlank()) {
            sb.append("- **总体意见**：").append(oneLine(review.summary())).append('\n');
        }
        if (review != null) {
            List<ReviewResult.ReviewIssue> issues = review.issuesOrEmpty();
            if (!issues.isEmpty()) {
                sb.append("\n| 级别 | 归属 | 位置 | 检查项 | 问题 | 证据 | 修改建议 |\n");
                sb.append("|------|------|------|--------|------|------|----------|\n");
                for (ReviewResult.ReviewIssue issue : issues) {
                    sb.append("| ").append(cell(issue.level()))
                            .append(" | ").append(cell(issue.target()))
                            .append(" | ").append(cell(issue.location()))
                            .append(" | ").append(cell(issue.check()))
                            .append(" | ").append(cell(issue.problem()))
                            .append(" | ").append(cell(issue.evidence()))
                            .append(" | ").append(cell(issue.fix()))
                            .append(" |\n");
                }
            }
        }
        append(topic, sb.toString());
    }

    /** 运行收尾：交付、未通过、审查未完成或异常终止，一行记录。 */
    static void ended(String topic, String run, String outcome) {
        append(topic, "\n## " + LocalDateTime.now().format(TIME) + " · " + run + " · 运行结束\n\n"
                + (outcome == null || outcome.isBlank() ? "—" : outcome.strip()) + "\n");
    }

    private static String verdictOf(ReviewResult review) {
        // PARSE 时 verdict 是 REVISE，但真正想说的是"输出没读出来"。
        return review.executionFailed() ? review.verdict() + "（PARSE，审查输出不可用）" : review.verdict();
    }

    private static void append(String topic, String section) {
        if (topic == null || topic.isBlank()) {
            return;
        }
        try {
            synchronized (LOCK) {
                Path file = WorkspacePaths.topicDir(topic).resolve(DIR_NAME).resolve(FILE_NAME);
                Files.createDirectories(file.getParent());
                if (!Files.exists(file)) {
                    Files.writeString(file, "# 审查记录 · " + topic + "\n\n"
                            + "（框架在每次审查后自动追加一节，按时间正序累积：每轮结论、问题清单与处置，"
                            + "以及每次运行的收尾。供事后追溯，不由任何 agent 写入。）\n",
                            StandardCharsets.UTF_8);
                }
                Files.writeString(file, section, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.warn("Could not record the review for topic {}: {}", topic, e.toString());
        }
    }

    private static String oneLine(String value) {
        return value.strip().replace("\r\n", "\n").replace('\n', ' ');
    }

    /** 表格单元格：竖线转义、换行折叠，空值写占位符。 */
    private static String cell(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.strip().replace("|", "\\|").replace("\r\n", "\n").replace("\n", "<br>");
    }
}

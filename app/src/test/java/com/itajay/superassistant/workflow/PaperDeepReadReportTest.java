package com.itajay.superassistant.workflow;

import com.itajay.superassistant.tool.AnalysisStore;
import com.itajay.superassistant.tool.PaperDownloadTool;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the single-paper workflow's decision and reporting logic.
 *
 * <p>Two properties matter and both are pure functions, so both are pinned here rather
 * than through a live run: the revision routing must fold {@code WRITER} findings into
 * the redo (there is no document to rewrite, and dropping them would discard real
 * issues), and must stop early when only material problems remain (a re-read cannot
 * fetch a missing PDF, and burning the last round pretending otherwise hides the
 * problem from the user one round longer). The report must name the source level —
 * the single credibility bound a reader of the analysis file needs.</p>
 */
class PaperDeepReadReportTest {

    private final String topic = "unit-deep-read-report-" + UUID.randomUUID();

    @AfterEach
    void deleteOnlyThisTestTopic() throws IOException {
        Path directory = WorkspacePaths.topicDir(topic);
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static ReviewResult review(String verdict, ReviewResult.ReviewIssue... issues) {
        return new ReviewResult("PASS".equals(verdict), verdict, "总体意见", List.of(issues));
    }

    private static ReviewResult.ReviewIssue issue(String level, String target) {
        return new ReviewResult.ReviewIssue(level, target, "analysis/X.md", "M2",
                "问题描述", "证据", "修改建议");
    }

    // ── revisionPlan ──

    @Test
    void anApprovedReviewMeansStop() {
        assertThat(PaperDeepReadWorkflow.revisionPlan(review("PASS")))
                .isEqualTo(PaperDeepReadWorkflow.RevisionAction.APPROVED);
    }

    @Test
    void aMissingReviewCannotMeanApproved() {
        assertThat(PaperDeepReadWorkflow.revisionPlan(null))
                .isEqualTo(PaperDeepReadWorkflow.RevisionAction.REVIEW_FAILED);
    }

    @Test
    void aParseFailureCannotRouteToContentRedo() {
        assertThat(PaperDeepReadWorkflow.revisionPlan(ReviewResult.parse("not JSON")))
                .isEqualTo(PaperDeepReadWorkflow.RevisionAction.REVIEW_FAILED);
    }

    @Test
    void aBadSchemaCannotRouteToContentRedo() {
        assertThat(PaperDeepReadWorkflow.revisionPlan(ReviewResult.parse(
                "{\"verdict\":\"PASS\",\"summary\":\"无效 issues\",\"issues\":{}}")))
                .isEqualTo(PaperDeepReadWorkflow.RevisionAction.REVIEW_FAILED);
    }

    @Test
    void anAnalystFindingMeansRedo() {
        assertThat(PaperDeepReadWorkflow.revisionPlan(
                review("REVISE", issue("BLOCKER", "ANALYST"))))
                .isEqualTo(PaperDeepReadWorkflow.RevisionAction.REANALYZE);
    }

    @Test
    void aWriterFindingIsFoldedIntoTheRedo() {
        // Single-paper mode has no document: the analysis is the only deliverable, so
        // a WRITER-labelled finding is still a finding about the analysis.
        assertThat(PaperDeepReadWorkflow.revisionPlan(
                review("REVISE", issue("MAJOR", "WRITER"))))
                .isEqualTo(PaperDeepReadWorkflow.RevisionAction.REANALYZE);
    }

    @Test
    void onlyResearcherFindingsStopTheLoop() {
        // Material problems cannot be fixed by re-reading: report them, do not spin.
        assertThat(PaperDeepReadWorkflow.revisionPlan(
                review("REVISE", issue("BLOCKER", "RESEARCHER"), issue("MAJOR", "RESEARCHER"))))
                .isEqualTo(PaperDeepReadWorkflow.RevisionAction.RESIDUAL_ONLY);
    }

    @Test
    void mixedFindingsStillRedoButResidualsAreReported() {
        assertThat(PaperDeepReadWorkflow.revisionPlan(
                review("REVISE", issue("BLOCKER", "RESEARCHER"), issue("MAJOR", "ANALYST"))))
                .isEqualTo(PaperDeepReadWorkflow.RevisionAction.REANALYZE);
    }

    // ── revisionReason ──

    @Test
    void revisionReasonCarriesBothAnalystAndWriterFindings() {
        String reason = PaperDeepReadWorkflow.revisionReason(review("REVISE",
                issue("BLOCKER", "ANALYST"), issue("MAJOR", "WRITER")));

        // Both targets' findings travel in the redo brief — the WRITER ones folded in,
        // because dropping them would discard a real issue in the only deliverable.
        assertThat(reason).contains("问题描述");
        assertThat(reason).contains("等同 ANALYST");
    }

    // ── report ──

    private PaperDownloadTool.AcquireResult acquired() {
        return new PaperDownloadTool.AcquireResult(true, topic, "MemGPT",
                "investigation/" + topic + "/papers/MemGPT.pdf",
                PaperDownloadTool.Origin.DOWNLOAD, "已下载");
    }

    @Test
    void reportNamesTheSourceAndOrigin() {
        String report = PaperDeepReadWorkflow.report(acquired(), review("PASS"), 1, null);

        assertThat(report).contains("MemGPT");
        assertThat(report).contains("investigation/" + topic + "/papers/MemGPT.pdf");
        assertThat(report).contains("来源：下载");
        assertThat(report).contains("通过 (PASS)");
    }

    @Test
    void reportListsLeftoverIssuesWhenNotApproved() {
        String report = PaperDeepReadWorkflow.report(acquired(),
                review("REVISE", issue("BLOCKER", "RESEARCHER")), 2, null);

        assertThat(report).contains("未通过 (REVISE)");
        assertThat(report).contains("遗留问题（1 条）");
        assertThat(report).contains("不得声称已核验通过");
    }

    @Test
    void legacyReportConvenienceInfersOneRedoForTwoContentRounds() {
        String report = PaperDeepReadWorkflow.report(acquired(), review("PASS"), 2, null);

        assertThat(report).contains("内容审查轮次: 2/2").contains("内容修订次数: 1/1");
    }

    @Test
    void actualAttemptCountDoesNotDependOnCompletedReviewRounds() {
        String report = PaperDeepReadWorkflow.report(acquired(),
                review("REVISE", issue("MAJOR", "ANALYST")), 1, 1, "重做精读失败");

        assertThat(report).contains("内容审查轮次: 1/2").contains("内容修订次数: 1/1")
                .contains("重做精读失败").contains("遗留问题（1 条）");
    }

    @Test
    void formatFailureIsReviewIncompleteNotAConfirmedContentFinding() {
        String report = PaperDeepReadWorkflow.report(acquired(), ReviewResult.parse("not JSON"), 1, 0, null);

        assertThat(report).contains("审查结论: 审查未完成").contains("精读质量未经验证")
                .contains("并非已证实分析内容有误").contains("审查故障信息")
                .contains("内容审查轮次: 1/2").contains("内容修订次数: 0/1")
                .doesNotContain("未通过 (REVISE)").doesNotContain("遗留问题（");
    }

    @Test
    void missingReviewIsReportedAsIncompleteWithoutAnApproval() {
        String report = PaperDeepReadWorkflow.report(acquired(), null, 1, 0, null);

        assertThat(report).contains("审查结论: 审查未完成").contains("精读质量未经验证")
                .doesNotContain("通过 (PASS)");
    }

    @Test
    void reportReadsSourceLevelAndCountsOnlyThisPapersFiguresFromDisk() throws IOException {
        Path file = AnalysisStore.resolveMarkdown(topic, "MemGPT");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                > 来源等级：SNIPPET
                > 一句话定位：仅核对片段，不声称全文。

                正文。
                """);
        Path figures = WorkspacePaths.figuresDir(topic);
        Files.createDirectories(figures);
        Files.createFile(figures.resolve("MemGPT_Fig2.png"));
        Files.createFile(figures.resolve("MemGPT_Fig4.png"));
        Files.createFile(figures.resolve("Other_Fig1.png"));

        String report = PaperDeepReadWorkflow.report(acquired(), review("PASS"), 1, 0, null);

        assertThat(report).contains("来源等级: SNIPPET · 图片 2 张")
                .contains("一句话定位: 仅核对片段，不声称全文。")
                .contains("investigation/" + topic + "/analysis/MemGPT.md");
    }

    @Test
    void reportSaysSoWhenTheAnalysisNeverAppeared() {
        String report = PaperDeepReadWorkflow.report(acquired(), null, 0,
                "（精读未产出分析文件，审查未执行。）");

        assertThat(report).contains("(未产出)");
        assertThat(report).contains("审查未执行");
    }
}

package com.itajay.superassistant.workflow;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 审查记录是给人事后读的：顺序要对、问题要全、写不进去也绝不能拖垮运行。 */
class ReviewReportTest {

    private static final String TOPIC = "unit-test-review-report";

    private static final ReviewResult REVISE = ReviewResult.parse("""
            {"verdict":"REVISE","summary":"整体可用，措辞越界","issues":[
              {"level":"BLOCKER","target":"WRITER","location":"§2.4","check":"F12",
               "problem":"“唯一”不成立|需限定","evidence":"原文\\n第二行","fix":"删去“唯一”"}]}""");

    private static final ReviewResult PASS = ReviewResult.parse("""
            {"verdict":"PASS","summary":"通过","issues":[]}""");

    private Path reportFile() {
        return WorkspacePaths.topicDir(TOPIC).resolve(ReviewReport.DIR_NAME).resolve(ReviewReport.FILE_NAME);
    }

    @AfterEach
    void cleanUp() throws IOException {
        Path topic = WorkspacePaths.topicDir(TOPIC);
        if (Files.exists(topic)) {
            try (var paths = Files.walk(topic)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @Test
    void roundsLandInOrderWithTheirFindingsAndActions() throws IOException {
        ReviewReport.round(TOPIC, "researchWriteReview", 1, 2, REVISE, "按问题归属进入下一轮修订");
        ReviewReport.round(TOPIC, "researchWriteReview", 2, 1, PASS, "通过，流程交付");
        ReviewReport.ended(TOPIC, "researchWriteReview", "文档通过审查，已交付（共 2 轮审查）");

        String text = Files.readString(reportFile());
        assertThat(text.indexOf("第 1 轮审查")).isLessThan(text.indexOf("第 2 轮审查"));
        assertThat(text.indexOf("第 2 轮审查")).isLessThan(text.indexOf("运行结束"));
        assertThat(text).contains("# 审查记录 · " + TOPIC)
                .contains("（输出 2 次）")
                .contains("REVISE", "PASS")
                .contains("BLOCKER", "WRITER", "§2.4", "F12", "删去“唯一”")
                // 竖线与换行不得破坏表格。
                .contains("“唯一”不成立\\|需限定").contains("原文<br>第二行")
                .contains("按问题归属进入下一轮修订")
                .contains("文档通过审查，已交付");
        // 文件头只写一次。
        assertThat(text.lines().filter(line -> line.startsWith("# 审查记录")).count()).isEqualTo(1);
    }

    @Test
    void aLaterRunAppendsToTheSameHistory() throws IOException {
        ReviewReport.ended(TOPIC, "researchWriteReview", "第一轮调研的收尾");
        ReviewReport.round(TOPIC, "deepReadPaper · ResNet", 1, 1, PASS, "通过，精读交付");

        String text = Files.readString(reportFile());
        assertThat(text).contains("第一轮调研的收尾", "deepReadPaper · ResNet");
    }

    @Test
    void anUnexecutedReviewIsRecordedAsSuch() throws IOException {
        ReviewReport.round(TOPIC, "deepReadPaper · ResNet", 1, 1, null, "精读失败，审查未开始");

        assertThat(Files.readString(reportFile())).contains("无（审查未执行）", "精读失败，审查未开始");
    }

    @Test
    void aFailedRecordNeverBreaksARun() throws IOException {
        // 课题目录的位置被一个普通文件占住：记录写不进去，运行也必须照常。
        Path topic = WorkspacePaths.topicDir(TOPIC);
        Files.createDirectories(topic.getParent());
        Files.writeString(topic, "not a directory");

        assertThatCode(() -> {
            ReviewReport.round(TOPIC, "researchWriteReview", 1, 1, REVISE, "x");
            ReviewReport.ended(TOPIC, "researchWriteReview", "x");
        }).doesNotThrowAnyException();
    }
}

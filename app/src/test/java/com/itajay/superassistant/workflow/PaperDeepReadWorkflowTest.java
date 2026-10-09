package com.itajay.superassistant.workflow;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressContext;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.progress.ProgressStage;
import com.itajay.superassistant.tool.AnalysisStore;
import com.itajay.superassistant.tool.PaperAnalysisTool;
import com.itajay.superassistant.tool.PaperDownloadTool;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real workflow entry, application-level tool mocks, and actual isolated analysis files. */
class PaperDeepReadWorkflowTest {

    private static final String PAPER = "Solo";
    private static final String SOURCE = "https://example.test/Solo.pdf";
    private static final String THREAD_ID = "deep-read-thread";
    private static final String REQUIREMENTS = "逐字核对第 3 节公式，并解释适用边界";
    private static final String ANALYSIS = """
            > 来源等级：FULL_TEXT
            > 一句话定位：单篇精读测试。

            原始分析正文。
            """;
    private static final String PASS = """
            {"verdict":"PASS","summary":"核验通过","issues":[]}
            """;
    private static final String REVISE = """
            {"verdict":"REVISE","summary":"需要修正公式","issues":[{
              "level":"MAJOR","target":"ANALYST","location":"analysis/Solo.md",
              "check":"M2","problem":"公式抄错","evidence":"PDF 第 3 页公式 2",
              "fix":"逐字重读并修正公式"}]}
            """;
    private static final String RESEARCHER = """
            {"verdict":"REVISE","summary":"材料问题","issues":[{
              "level":"BLOCKER","target":"RESEARCHER","location":"papers/Solo.pdf",
              "check":"SOURCE","problem":"PDF 损坏","evidence":"正文无法读取",
              "fix":"获取可读取的 PDF"}]}
            """;

    // ROOT is static in WorkspacePaths. Isolate by a unique topic, never mutate global ROOT.
    private final String topic = "unit-deep-read-" + UUID.randomUUID();
    private final PaperDownloadTool download = mock(PaperDownloadTool.class);
    private final PaperAnalysisTool analysis = mock(PaperAnalysisTool.class);
    private final ProgressChannelRegistry progress = mock(ProgressChannelRegistry.class);
    private final List<ProgressEvent> events = new ArrayList<>();
    private final List<String> reviewerInputs = new ArrayList<>();
    private final List<RunnableConfig> reviewerConfigs = new ArrayList<>();
    private final ToolContext context = new ToolContext(Map.of("_AGENT_CONFIG_",
            RunnableConfig.builder().threadId(THREAD_ID).build()));

    @BeforeEach
    void prepareAcquisitionAndProgress() throws IOException {
        Path pdf = WorkspacePaths.papersDir(topic).resolve(PAPER + ".pdf");
        Files.createDirectories(pdf.getParent());
        Files.createFile(pdf); // download is mocked; no PDF parser is involved.
        when(download.acquirePaper(topic, SOURCE, PAPER, THREAD_ID)).thenReturn(
                new PaperDownloadTool.AcquireResult(true, topic, PAPER,
                        WorkspacePaths.relative(pdf), PaperDownloadTool.Origin.DOWNLOAD, "已下载"));
        doAnswer(invocation -> {
            events.add(invocation.getArgument(1, ProgressEvent.class));
            return null;
        }).when(progress).publish(eq(THREAD_ID), any(ProgressEvent.class));
    }

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

    private void writeAnalysis(String content) throws IOException {
        Path file = AnalysisStore.resolveMarkdown(topic, PAPER);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void firstReadSucceeds() {
        when(analysis.analyzeSingleSync(eq(topic), eq(PAPER), eq(THREAD_ID), eq(1), eq(REQUIREMENTS), any(ProgressContext.class)))
                .thenAnswer(invocation -> {
                    writeAnalysis(ANALYSIS);
                    return "论文精读完成。";
                });
    }

    private void redoSucceeds() {
        when(analysis.reanalyzeSync(eq(topic), eq(PAPER), anyString(), eq(THREAD_ID), eq(2), eq(REQUIREMENTS), any(ProgressContext.class)))
                .thenAnswer(invocation -> {
                    writeAnalysis(ANALYSIS + "按审查意见修订。\n");
                    return true;
                });
    }

    private PaperDeepReadWorkflow workflow(String... outputs) {
        return new PaperDeepReadWorkflow(download, analysis, (input, config) -> {
            int invocation = reviewerInputs.size();
            reviewerInputs.add(input);
            reviewerConfigs.add(config);
            assertThat(invocation).as("review calls must be bounded").isLessThan(outputs.length);
            return outputs[invocation];
        }, progress);
    }

    private String run(PaperDeepReadWorkflow workflow, Boolean forceRedo) {
        return workflow.deepReadPaper(SOURCE, topic, PAPER, REQUIREMENTS, forceRedo, context);
    }

    private void assertNoRedo() {
        verify(analysis, never()).reanalyzeSync(
                anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), any(ProgressContext.class));
    }

    private void assertCounts(String result, int reviewRounds, int redos) {
        assertThat(result).contains("内容审查轮次: " + reviewRounds + "/2")
                .contains("内容修订次数: " + redos + "/1");
    }

    private long retryEvents() {
        return events.stream().filter(e -> e.detail().contains("审查输出格式无效")).count();
    }

    @Test
    void firstReadAndReviewReceiveRequirementsAndThreadConfig() {
        firstReadSucceeds();

        String result = run(workflow(PASS), null);

        ArgumentCaptor<String> requirement = ArgumentCaptor.forClass(String.class);
        verify(analysis).analyzeSingleSync(eq(topic), eq(PAPER), eq(THREAD_ID), eq(1), requirement.capture(), any(ProgressContext.class));
        assertThat(requirement.getValue()).isEqualTo(REQUIREMENTS);
        verify(download).acquirePaper(topic, SOURCE, PAPER, THREAD_ID);
        assertThat(reviewerInputs).singleElement().satisfies(input -> assertThat(input)
                .contains(REQUIREMENTS).contains("单篇精读模式").contains("FULL_TEXT").contains("analysis/Solo.md"));
        assertThat(reviewerConfigs).singleElement().satisfies(config ->
                assertThat(config.threadId()).contains(THREAD_ID));
        assertCounts(result, 1, 0);
        assertThat(result).contains("通过 (PASS)");
        assertNoRedo();
    }

    @Test
    void forceRedoPassesRequirementsButIsNotAnAutomaticContentRevision() throws IOException {
        writeAnalysis(ANALYSIS);
        when(analysis.reanalyzeSync(eq(topic), eq(PAPER), eq("用户要求强制重做精读"), eq(THREAD_ID), eq(1), eq(REQUIREMENTS), any(ProgressContext.class)))
                .thenAnswer(invocation -> {
                    writeAnalysis(ANALYSIS + "用户要求强制重做。\n");
                    return true;
                });

        String result = run(workflow(PASS), true);

        ArgumentCaptor<String> requirement = ArgumentCaptor.forClass(String.class);
        verify(analysis).reanalyzeSync(eq(topic), eq(PAPER), eq("用户要求强制重做精读"),
                eq(THREAD_ID), eq(1), requirement.capture(), any(ProgressContext.class));
        assertThat(requirement.getValue()).isEqualTo(REQUIREMENTS);
        verify(analysis, never()).analyzeSingleSync(anyString(), anyString(), anyString(),
                anyInt(), any(), any(ProgressContext.class));
        assertCounts(result, 1, 0);
        assertThat(reviewerInputs).singleElement().satisfies(input ->
                assertThat(input).contains("已强制重做").contains(REQUIREMENTS));
    }

    @Test
    void forceRedoDoesNotConsumeTheLaterReviewDrivenRedoAllowance() throws IOException {
        writeAnalysis(ANALYSIS);
        when(analysis.reanalyzeSync(eq(topic), eq(PAPER), eq("用户要求强制重做精读"), eq(THREAD_ID), eq(1), eq(REQUIREMENTS), any(ProgressContext.class)))
                .thenReturn(true);
        redoSucceeds();

        String result = run(workflow(REVISE, PASS), true);

        verify(analysis).reanalyzeSync(eq(topic), eq(PAPER), eq("用户要求强制重做精读"), eq(THREAD_ID), eq(1), eq(REQUIREMENTS), any(ProgressContext.class));
        verify(analysis).reanalyzeSync(eq(topic), eq(PAPER), anyString(), eq(THREAD_ID), eq(2), eq(REQUIREMENTS), any(ProgressContext.class));
        assertThat(reviewerInputs).hasSize(2);
        assertCounts(result, 2, 1);
        assertThat(result).contains("通过 (PASS)");
    }

    @Test
    void forceRedoWithoutExistingAnalysisStillUsesFirstReadWithRequirements() {
        firstReadSucceeds();

        String result = run(workflow(PASS), true);

        verify(analysis).analyzeSingleSync(eq(topic), eq(PAPER), eq(THREAD_ID), eq(1), eq(REQUIREMENTS), any(ProgressContext.class));
        assertNoRedo();
        assertCounts(result, 1, 0);
    }

    @Test
    void anExistingAnalysisIsReusedWithoutImplicitRedoWhenForceRedoIsFalse() throws IOException {
        writeAnalysis(ANALYSIS);
        when(analysis.analyzeSingleSync(eq(topic), eq(PAPER), eq(THREAD_ID), eq(1), eq(REQUIREMENTS), any(ProgressContext.class)))
                .thenReturn("已有分析，跳过");

        String result = run(workflow(PASS), false);

        assertThat(AnalysisStore.resolveMarkdown(topic, PAPER)).hasContent(ANALYSIS);
        verify(analysis).analyzeSingleSync(eq(topic), eq(PAPER), eq(THREAD_ID), eq(1), eq(REQUIREMENTS), any(ProgressContext.class));
        assertNoRedo();
        assertCounts(result, 1, 0);
    }

    @Test
    void reviewDrivenRedoReceivesBothFindingsAndOriginalRequirements() {
        firstReadSucceeds();
        redoSucceeds();

        String result = run(workflow(REVISE, PASS), false);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> requirement = ArgumentCaptor.forClass(String.class);
        verify(analysis).reanalyzeSync(eq(topic), eq(PAPER), reason.capture(),
                eq(THREAD_ID), eq(2), requirement.capture(), any(ProgressContext.class));
        assertThat(reason.getValue()).contains("公式抄错").contains("逐字重读并修正公式");
        assertThat(requirement.getValue()).isEqualTo(REQUIREMENTS);
        assertThat(reviewerInputs).hasSize(2).allSatisfy(input -> assertThat(input).contains(REQUIREMENTS));
        assertThat(reviewerInputs.get(1)).contains("已按审查意见重做精读");
        assertCounts(result, 2, 1);
        assertThat(result).contains("通过 (PASS)");
    }

    @Test
    void malformedJsonThenPassRetriesOnlyTheSameArtifactReview() {
        firstReadSucceeds();

        String result = run(workflow("not JSON", PASS), false);

        assertThat(reviewerInputs).hasSize(2);
        assertThat(reviewerInputs.get(1)).contains(reviewerInputs.get(0));
        assertThat(reviewerConfigs.get(1)).isSameAs(reviewerConfigs.get(0));
        assertThat(retryEvents()).isEqualTo(1);
        assertThat(events).filteredOn(e -> e.detail().contains("审查输出格式无效"))
                .singleElement().satisfies(event -> {
                    assertThat(event.stage()).isEqualTo(ProgressStage.REVIEWING);
                    assertThat(event.round()).isEqualTo(1);
                });
        assertNoRedo();
        assertCounts(result, 1, 0);
        assertThat(result).contains("通过 (PASS)");
    }

    @Test
    void persistentlyMalformedJsonStopsAfterOneRetryWithoutContentRedo() {
        firstReadSucceeds();

        String result = run(workflow("not JSON", "still not JSON"), false);

        assertThat(reviewerInputs).hasSize(2);
        assertThat(retryEvents()).isEqualTo(1);
        assertNoRedo();
        assertCounts(result, 1, 0);
        assertThat(result).contains("审查结论: 审查未完成").contains("精读质量未经验证")
                .doesNotContain("审查结论: 未通过 (REVISE)").doesNotContain("遗留问题（");
        assertThat(events.getLast().stage()).isEqualTo(ProgressStage.FAILED);
        assertThat(events.getLast().detail()).contains("审查未完成");
    }

    @Test
    void persistentlyInvalidSchemaAlsoStopsWithoutContentRedo() {
        firstReadSucceeds();

        String result = run(workflow("{\"summary\":\"缺少 verdict\",\"issues\":[]}",
                "{\"verdict\":\"PASS\",\"summary\":\"坏 schema\",\"issues\":{}}"), false);

        assertThat(reviewerInputs).hasSize(2);
        assertThat(retryEvents()).isEqualTo(1);
        assertNoRedo();
        assertCounts(result, 1, 0);
        assertThat(result).contains("审查结论: 审查未完成");
    }

    @Test
    void formatRetryDoesNotConsumeTheOneAllowedContentRedo() {
        firstReadSucceeds();
        redoSucceeds();

        String result = run(workflow("not JSON", REVISE, PASS), false);

        assertThat(reviewerInputs).hasSize(3);
        assertThat(retryEvents()).isEqualTo(1);
        verify(analysis).reanalyzeSync(eq(topic), eq(PAPER), anyString(), eq(THREAD_ID), eq(2), eq(REQUIREMENTS), any(ProgressContext.class));
        assertCounts(result, 2, 1);
        assertThat(result).contains("通过 (PASS)");
    }

    @Test
    void researcherOnlyFindingsStopImmediatelyWithoutReanalysis() {
        firstReadSucceeds();

        String result = run(workflow(RESEARCHER), false);

        assertThat(reviewerInputs).hasSize(1);
        assertThat(retryEvents()).isZero();
        assertNoRedo();
        assertCounts(result, 1, 0);
        assertThat(result).contains("未通过 (REVISE)").contains("遗留问题（1 条）").contains("PDF 损坏");
    }

    @Test
    void persistentContentReviseRunsAtMostTwoReviewsAndOneContentRedo() {
        firstReadSucceeds();
        redoSucceeds();

        String result = run(workflow(REVISE, REVISE), false);

        assertThat(reviewerInputs).hasSize(2);
        assertThat(retryEvents()).isZero();
        verify(analysis).reanalyzeSync(eq(topic), eq(PAPER), anyString(), eq(THREAD_ID), eq(2), eq(REQUIREMENTS), any(ProgressContext.class));
        assertCounts(result, 2, 1);
        assertThat(result).contains("未通过 (REVISE)").contains("公式抄错");
    }

    @Test
    void aFailedFormatRetryAfterRedoStillStopsWithoutASecondContentRedo() {
        firstReadSucceeds();
        redoSucceeds();

        String result = run(workflow(REVISE, "not JSON", "still not JSON"), false);

        assertThat(reviewerInputs).hasSize(3);
        assertThat(retryEvents()).isEqualTo(1);
        verify(analysis).reanalyzeSync(eq(topic), eq(PAPER), anyString(), eq(THREAD_ID), eq(2), eq(REQUIREMENTS), any(ProgressContext.class));
        assertCounts(result, 2, 1);
        assertThat(result).contains("审查结论: 审查未完成").doesNotContain("遗留问题（");
    }

    @Test
    void noAnalysisMeansNoReviewAndZeroRevisionAttempts() {
        when(analysis.analyzeSingleSync(eq(topic), eq(PAPER), eq(THREAD_ID), eq(1), eq(REQUIREMENTS), any(ProgressContext.class)))
                .thenReturn("精读失败，未写入文件");

        String result = run(workflow(), false);

        assertThat(reviewerInputs).isEmpty();
        assertNoRedo();
        assertCounts(result, 0, 0);
        assertThat(result).contains("(未产出)").contains("审查未执行");
    }

    @Test
    void aFailedForcedRedoDoesNotReviewTheOldArtifact() throws IOException {
        writeAnalysis(ANALYSIS);
        when(analysis.reanalyzeSync(eq(topic), eq(PAPER), eq("用户要求强制重做精读"), eq(THREAD_ID), eq(1), eq(REQUIREMENTS), any(ProgressContext.class)))
                .thenReturn(false);

        String result = run(workflow(), true);

        assertThat(reviewerInputs).isEmpty();
        assertCounts(result, 0, 0);
        assertThat(result).contains("强制重做失败").contains("审查未执行");
    }

    @Test
    void aFailedContentRedoIsCountedAsOneAttemptAndIsNotReviewedAgain() {
        firstReadSucceeds();
        when(analysis.reanalyzeSync(eq(topic), eq(PAPER), anyString(), eq(THREAD_ID), eq(2), eq(REQUIREMENTS), any(ProgressContext.class)))
                .thenReturn(false);

        String result = run(workflow(REVISE), false);

        assertThat(reviewerInputs).hasSize(1);
        assertCounts(result, 1, 1);
        assertThat(result).contains("重做精读失败").contains("未执行后续审查");
    }

    @Test
    void aThrowingContentRedoStillCountsItsAttempt() {
        firstReadSucceeds();
        when(analysis.reanalyzeSync(eq(topic), eq(PAPER), anyString(), eq(THREAD_ID), eq(2), eq(REQUIREMENTS), any(ProgressContext.class)))
                .thenThrow(new IllegalStateException("redo boom"));

        String result = run(workflow(REVISE), false);

        assertThat(reviewerInputs).hasSize(1);
        assertCounts(result, 1, 1);
        assertThat(result).contains("workflow failed: redo boom");
    }

    @Test
    void anOrdinaryReviewerExceptionDoesNotRetryOrRedo() {
        firstReadSucceeds();
        AtomicInteger calls = new AtomicInteger();
        PaperDeepReadWorkflow workflow = new PaperDeepReadWorkflow(download, analysis, (input, config) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("review boom");
        }, progress);

        String result = run(workflow, false);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(retryEvents()).isZero();
        assertNoRedo();
        assertCounts(result, 1, 0);
        assertThat(result).contains("workflow failed: review boom").contains("审查结论: 审查未完成");
    }

    @Test
    void interruptedReviewPreservesTheThreadFlagWithoutRetryOrRedo() throws Exception {
        firstReadSucceeds();
        AtomicInteger calls = new AtomicInteger();
        PaperDeepReadWorkflow workflow = new PaperDeepReadWorkflow(download, analysis, (input, config) -> {
            calls.incrementAndGet();
            throw new InterruptedException("cancelled");
        }, progress);
        ExecutorService isolatedCaller = Executors.newSingleThreadExecutor();
        try {
            InterruptedResult outcome = isolatedCaller.submit(() -> {
                String result = run(workflow, false);
                return new InterruptedResult(result, Thread.currentThread().isInterrupted());
            }).get(5, TimeUnit.SECONDS);

            assertThat(outcome.interrupted()).isTrue();
            assertThat(calls.get()).isEqualTo(1);
            assertThat(retryEvents()).isZero();
            assertNoRedo();
            assertCounts(outcome.report(), 1, 0);
            assertThat(outcome.report()).contains("workflow failed: cancelled");
        } finally {
            isolatedCaller.shutdownNow();
            assertThat(isolatedCaller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void legacyWorkflowCallerWithoutToolContextStillCompletes() throws IOException {
        Path pdf = WorkspacePaths.papersDir(topic).resolve(PAPER + ".pdf");
        when(download.acquirePaper(topic, SOURCE, PAPER, null)).thenReturn(
                new PaperDownloadTool.AcquireResult(true, topic, PAPER, WorkspacePaths.relative(pdf),
                        PaperDownloadTool.Origin.REUSED_IN_TOPIC, "已存在"));
        writeAnalysis(ANALYSIS);
        when(analysis.analyzeSingleSync(eq(topic), eq(PAPER), org.mockito.ArgumentMatchers.isNull(), eq(1),
                eq(REQUIREMENTS), any(ProgressContext.class))).thenReturn("已有分析，跳过");

        String result = workflow(PASS).deepReadPaper(SOURCE, topic, PAPER, REQUIREMENTS, false, null);

        assertThat(result).contains("通过 (PASS)");
        assertThat(reviewerConfigs).singleElement().satisfies(config -> {
            assertThat(config.threadId()).isEmpty();
            assertThat(config.metadata(ProgressContext.METADATA_KEY)).isPresent();
        });
    }

    @Test
    void structuredWorkflowAndReviewEventsKeepOneRunIdAndExplicitChildContext() {
        firstReadSucceeds();
        run(workflow("not JSON", REVISE, PASS), false);
        // Redo is unstubbed and returns false: this run ends after the revise verdict.
        assertThat(events.getFirst().payload().operation()).isEqualTo(ProgressEvent.Operation.WORKFLOW);
        assertThat(events.getFirst().payload().status()).isEqualTo(ProgressEvent.Status.RUNNING);
        assertThat(events.getLast().payload().operation()).isEqualTo(ProgressEvent.Operation.WORKFLOW);
        assertThat(events.getLast().payload().status()).isEqualTo(ProgressEvent.Status.FAILED);
        String runId = events.getFirst().payload().runId();
        assertThat(UUID.fromString(runId)).isNotNull();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.payload()).isNotNull();
            assertThat(event.payload().runId()).isEqualTo(runId);
            assertThat(event.payload().workflow()).isEqualTo(ProgressEvent.Workflow.PAPER_DEEP_READ);
        });
        assertThat(events.stream().filter(event -> event.payload().operation() == ProgressEvent.Operation.REVIEW)
                .map(event -> event.payload().status())).containsExactly(
                ProgressEvent.Status.RUNNING, ProgressEvent.Status.RETRYING, ProgressEvent.Status.REVISE);
        assertThat(events.stream().filter(event -> event.payload().status() == ProgressEvent.Status.RETRYING))
                .singleElement().satisfies(event -> {
                    assertThat(event.payload().attempt()).isEqualTo(2);
                    assertThat(event.payload().maxAttempts()).isEqualTo(2);
                });
        assertThat(reviewerConfigs).allSatisfy(config -> {
            assertThat(config.metadata(PaperAnalysisTool.REQUIREMENTS_METADATA_KEY)).contains(REQUIREMENTS);
            ProgressContext child = ProgressContext.from(new ToolContext(Map.of("_AGENT_CONFIG_", config)))
                    .orElseThrow();
            assertThat(child.runId()).isEqualTo(runId);
            assertThat(child.paper()).isEqualTo(PAPER);
            assertThat(child.stage()).isEqualTo(ProgressStage.REVIEWING);
        });
    }

    @Test
    void workflowCreatesANewRunIdEvenForTheSameConversation() throws IOException {
        writeAnalysis(ANALYSIS);
        when(analysis.analyzeSingleSync(eq(topic), eq(PAPER), eq(THREAD_ID), eq(1), eq(REQUIREMENTS),
                any(ProgressContext.class))).thenReturn("已有分析，跳过");
        run(workflow(PASS), false);
        String firstRun = events.getFirst().payload().runId();
        events.clear();
        reviewerInputs.clear();
        reviewerConfigs.clear();
        run(workflow(PASS), false);
        assertThat(events.getFirst().payload().runId()).isNotEqualTo(firstRun);
        assertThat(events.getLast().payload().status()).isEqualTo(ProgressEvent.Status.SUCCEEDED);
    }

    private record InterruptedResult(String report, boolean interrupted) {
    }
}

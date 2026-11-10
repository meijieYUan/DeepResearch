package com.itajay.superassistant.workflow;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressContext;
import com.itajay.superassistant.progress.ProgressEvent;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import com.itajay.superassistant.tool.DownloadFailureRegistry;
import com.itajay.superassistant.tool.PaperAnalysisTool;
import com.itajay.superassistant.tool.PaperLibrary;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Runs the actual revision loop with stage functions instead of model calls. */
class ResearchWriteReviewWorkflowTest {

    private static final String PASS = "{\"verdict\":\"PASS\",\"summary\":\"ok\",\"issues\":[]}";
    private final String topic = "workflow-contract-" + UUID.randomUUID();
    private final String brief = topic + ": 仅2025-2026年，2篇论文，中文，重点核对推理约束";
    /**
     * What the writer must receive as its requirements. Normally the brief itself; a
     * continuation passes a different brief because the folder comes from the selected
     * direction, not from the text.
     */
    private String expectedRequirements = brief;
    private final PaperAnalysisTool analysis = mock(PaperAnalysisTool.class);
    private final List<String> researchInputs = new ArrayList<>();
    private final List<String> writerInputs = new ArrayList<>();
    private final List<String> reviewInputs = new ArrayList<>();
    private final Path document = WorkspacePaths.documentsDir(topic).resolve(topic + ".md");

    @AfterEach
    void cleanUp() throws IOException {
        Path directory = WorkspacePaths.topicDir(topic);
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private ResearchWriteReviewWorkflow workflow(List<String> reviews) {
        when(analysis.analyzeTopicSync(any(), isNull(), anyInt(), anyString(), any(ProgressContext.class)))
                .thenReturn("论文精读完成：本地测试索引");
        AtomicInteger reviewIndex = new AtomicInteger();
        return new ResearchWriteReviewWorkflow((input, config) -> {
            researchInputs.add(input);
            return "本轮材料：Alpha、Beta";
        }, (input, config) -> {
            writerInputs.add(input);
            assertThat(config).isNotNull();
            assertThat(config.metadata(PaperAnalysisTool.REQUIREMENTS_METADATA_KEY))
                    .contains(expectedRequirements);
            Files.createDirectories(document.getParent());
            Files.writeString(document, writerInputs.size() == 1
                    ? "# 原始文档\n\n固定正确段落。\n\n待修错误结论。" : "# 原始文档\n\n固定正确段落。\n\n已修结论。");
            return "已保存 file: " + WorkspacePaths.relative(document);
        }, (input, config) -> {
            reviewInputs.add(input);
            return reviews.get(reviewIndex.getAndIncrement());
        }, analysis, new ProgressChannelRegistry());
    }

    @Test
    void progressUsesFreshRunIdentityAndTruthfulReviewRetryAndRevisionStatuses() {
        List<ProgressEvent> events = new ArrayList<>();
        List<RunnableConfig> configs = new ArrayList<>();
        ProgressChannelRegistry recording = new ProgressChannelRegistry() {
            @Override
            public void publish(String threadId, ProgressEvent event) {
                assertThat(threadId).isEqualTo("shared-thread");
                events.add(event);
            }
        };
        when(analysis.analyzeTopicSync(eq(topic), eq("shared-thread"), anyInt(), eq(brief),
                any(ProgressContext.class))).thenReturn("论文精读完成：本地测试索引");
        AtomicInteger reviews = new AtomicInteger();
        var flow = new ResearchWriteReviewWorkflow((input, config) -> {
            configs.add(config);
            return "materials";
        }, (input, config) -> {
            configs.add(config);
            Files.createDirectories(document.getParent());
            Files.writeString(document, "# 文档");
            return "file: " + WorkspacePaths.relative(document);
        }, (input, config) -> {
            configs.add(config);
            return switch (reviews.incrementAndGet()) {
                case 1 -> "not JSON";
                case 2 -> revise("WRITER");
                default -> PASS;
            };
        }, analysis, recording);
        ToolContext caller = new ToolContext(Map.of("_AGENT_CONFIG_", RunnableConfig.builder()
                .threadId("shared-thread").addMetadata("approval", "do not copy")
                .addMetadata("feedback", "do not copy").build()));

        assertThat(flow.researchWriteReview(brief, caller)).contains("通过 (PASS)");

        assertThat(events.getFirst().payload().operation()).isEqualTo(ProgressEvent.Operation.WORKFLOW);
        assertThat(events.getFirst().payload().status()).isEqualTo(ProgressEvent.Status.RUNNING);
        String runId = events.getFirst().payload().runId();
        assertThat(UUID.fromString(runId)).isNotNull();
        assertThat(events).allSatisfy(event -> assertThat(event.payload().runId()).isEqualTo(runId));
        assertThat(events.getLast().payload().status()).isEqualTo(ProgressEvent.Status.SUCCEEDED);
        assertThat(events.stream().filter(event -> event.payload().operation() == ProgressEvent.Operation.REVIEW)
                .map(event -> event.payload().status())).containsExactly(ProgressEvent.Status.RUNNING,
                ProgressEvent.Status.RETRYING, ProgressEvent.Status.REVISE,
                ProgressEvent.Status.RUNNING, ProgressEvent.Status.PASS);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.stage()).isEqualTo(com.itajay.superassistant.progress.ProgressStage.REVISING);
            assertThat(event.payload().status()).isEqualTo(ProgressEvent.Status.SUCCEEDED);
        });
        assertThat(configs).allSatisfy(config -> {
            assertThat(config.metadata("approval")).isEmpty();
            assertThat(config.metadata("feedback")).isEmpty();
            assertThat(config.metadata(PaperAnalysisTool.REQUIREMENTS_METADATA_KEY)).contains(brief);
            assertThat(ProgressContext.from(new ToolContext(Map.of("_AGENT_CONFIG_", config)))
                    .orElseThrow().runId()).isEqualTo(runId);
        });
        events.clear();
        assertThat(flow.researchWriteReview(brief, caller)).contains("通过 (PASS)");
        assertThat(events.getFirst().payload().runId()).isNotEqualTo(runId);
    }

    private static String revise(String target) {
        return "{\"verdict\":\"REVISE\",\"summary\":\"needs fix\",\"issues\":["
                + "{\"level\":\"MAJOR\",\"target\":\"" + target + "\",\"check\":\"F1\","
                + "\"problem\":\"结论不符\",\"fix\":\"核对原文\"}]}";
    }

    @Test
    void writerRevisionReceivesTheReviewedDraftAndOriginalRequirements() {
        String report = workflow(List.of(revise("WRITER"), PASS)).researchWriteReview(brief, null);
        assertThat(report).contains("通过 (PASS)", "内容审查轮次: 2/2", "内容修订次数: 1/1");
        assertThat(writerInputs).hasSize(2);
        assertThat(writerInputs.get(0)).doesNotContain("--- 上一版送审正文 ---");
        assertThat(writerInputs.get(1)).contains(brief, "固定正确段落。", "待修错误结论。",
                "上一版从磁盘读回的完整正文", WorkspacePaths.relative(document));
        assertThat(reviewInputs.get(0)).contains("待修错误结论。").doesNotContain("已保存 file:");
        assertThat(researchInputs).hasSize(1);
        verify(analysis).analyzeTopicSync(eq(topic), isNull(), eq(1), eq(brief), any(ProgressContext.class));
        verify(analysis).analyzeTopicSync(eq(topic), isNull(), eq(2), eq(brief), any(ProgressContext.class));
    }

    @Test
    void supplementaryResearchKeepsOriginalScopeAndMaterial() {
        workflow(List.of(revise("RESEARCHER"), PASS)).researchWriteReview(brief, null);
        assertThat(researchInputs).hasSize(2);
        assertThat(researchInputs.get(1)).contains(brief, "原始调研要求", "Alpha、Beta", "结论不符");
        assertThat(writerInputs.get(1)).contains("更新后的材料或精读结果", "固定正确段落。");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Continuing an existing direction
    // ──────────────────────────────────────────────────────────────────────

    /**
     * The direction selected in the UI wins over the brief — and over a paraphrase the
     * model may have produced. A continuation that lands in a different folder splits one
     * investigation in two, which is exactly what this parameter exists to prevent.
     */
    @Test
    void aContinuationUsesTheSelectedDirectionInsteadOfTheBrief() {
        String selected = "selected-direction-" + UUID.randomUUID();
        String continuationBrief = "本次希望补充 2026 年的工作，重点看训练目标";
        expectedRequirements = continuationBrief;
        try {
            workflow(List.of(PASS)).researchWriteReview(
                    continuationBrief,
                    selected,
                    new ToolContext(Map.of()));

            // Every stage was handed the selected folder, not one derived from the brief.
            assertThat(researchInputs.get(0)).contains("课题方向：" + selected, "续调研");
            assertThat(writerInputs.get(0)).contains("课题方向：" + selected, "已有方向");
        } finally {
            deleteTopic(selected);
        }
    }

    /** The UI-carried direction takes precedence over the tool argument the model passed. */
    @Test
    void theClientSuppliedDirectionOutranksTheModelArgument() {
        String fromClient = "client-direction-" + UUID.randomUUID();
        String fromModel = "model-direction-" + UUID.randomUUID();
        try {
            ToolContext context = new ToolContext(Map.of(
                    ResearchWriteReviewWorkflow.CONTINUE_TOPIC_CONTEXT_KEY, fromClient));
            workflow(List.of(PASS)).researchWriteReview(brief, fromModel, context);

            assertThat(researchInputs.get(0)).contains("课题方向：" + fromClient).doesNotContain(fromModel);
        } finally {
            deleteTopic(fromClient);
        }
    }

    /**
     * A continuation rewrites one document, so the version it replaces is moved aside
     * first — the user wants to compare against the one they found unsatisfactory.
     */
    @Test
    void aContinuationArchivesThePreviousDocument() throws IOException {
        String continued = "archive-direction-" + UUID.randomUUID();
        Path dir = WorkspacePaths.documentsDir(continued);
        Path current = dir.resolve(continued + ".md");
        try {
            Files.createDirectories(dir);
            Files.writeString(current, "# 上一版\n\n旧内容\n");

            String archived = ResearchWriteReviewWorkflow.archiveDocument(continued);

            assertThat(archived).isNotNull();
            assertThat(archived).startsWith("investigation/" + continued + "/document/history/");
            assertThat(Files.exists(current)).isFalse();
            assertThat(Files.readString(WorkspacePaths.root().resolve(archived))).contains("旧内容");
        } finally {
            deleteTopic(continued);
        }
    }

    /** Nothing to archive is not a failure: a first run just has no history. */
    @Test
    void archivingAnAbsentDocumentIsHarmless() {
        assertThat(ResearchWriteReviewWorkflow.archiveDocument("never-written-" + UUID.randomUUID())).isNull();
    }

    private static void deleteTopic(String name) {
        Path directory = WorkspacePaths.topicDir(name);
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    @Test
    void malformedReviewRecoversWithoutRewritingOrReanalyzing() {
        String report = workflow(List.of("not JSON", PASS)).researchWriteReview(brief, null);
        assertThat(report).contains("通过 (PASS)", "内容审查轮次: 1/2", "内容修订次数: 0/1");
        assertThat(researchInputs).hasSize(1);
        assertThat(writerInputs).hasSize(1);
        assertThat(reviewInputs).hasSize(2);
        assertThat(reviewInputs.get(1)).startsWith(reviewInputs.get(0)).contains("上一次审查输出不符合约定");
        verify(analysis).analyzeTopicSync(eq(topic), isNull(), eq(1), eq(brief), any(ProgressContext.class));
    }

    @Test
    void persistentMalformedReviewStopsWithoutContentRevision() throws IOException {
        String report = workflow(List.of("bad JSON", "still bad")).researchWriteReview(brief, null);
        assertThat(report).contains("审查未完成", "质量未经验证", "内容修订次数: 0/1")
                .doesNotContain("可以直接交付", "遗留问题（");
        assertThat(writerInputs).hasSize(1);
        assertThat(researchInputs).hasSize(1);
        assertThat(reviewInputs).hasSize(2);
        assertThat(Files.readString(document)).contains("固定正确段落。", "待修错误结论。");
    }

    @Test
    void formatRecoveryDoesNotConsumeTheContentRevision() {
        String report = workflow(List.of("bad JSON", revise("WRITER"), PASS)).researchWriteReview(brief, null);
        assertThat(report).contains("通过 (PASS)", "内容审查轮次: 2/2", "内容修订次数: 1/1");
        assertThat(writerInputs).hasSize(2);
        assertThat(reviewInputs).hasSize(3);
        assertThat(researchInputs).hasSize(1);
    }

    @Test
    void persistentContentProblemsStopAfterOneRevision() {
        String report = workflow(List.of(revise("WRITER"), revise("WRITER"))).researchWriteReview(brief, null);
        assertThat(report).contains("未通过 (REVISE)", "内容修订次数: 1/1");
        assertThat(writerInputs).hasSize(2);
        assertThat(reviewInputs).hasSize(2);
    }

    @Test
    void replyFallbackIsNotPresentedAsACompleteDraft() {
        when(analysis.analyzeTopicSync(eq(topic), isNull(), anyInt(), eq(brief), any(ProgressContext.class))).thenReturn("论文精读完成：");
        List<String> writes = new ArrayList<>();
        AtomicInteger reviews = new AtomicInteger();
        ResearchWriteReviewWorkflow flow = new ResearchWriteReviewWorkflow((input, config) -> "materials",
                (input, config) -> {
                    writes.add(input);
                    return "没有保存正文，仅为回复文本";
                }, (input, config) -> reviews.incrementAndGet() == 1 ? revise("WRITER") : PASS,
                analysis, new ProgressChannelRegistry());
        flow.researchWriteReview(brief, null);
        assertThat(writes).hasSize(2);
        assertThat(writes.get(1)).contains("上一版未取得完整磁盘正文", "不能声称已精确保留", "仅为回复文本")
                .doesNotContain("以下是上一版从磁盘读回的完整正文", "未受影响部分保持不变");
    }

    @Test
    void oversizedDraftIsExplicitlyLimitedRatherThanClaimedComplete() {
        when(analysis.analyzeTopicSync(eq(topic), isNull(), anyInt(), eq(brief), any(ProgressContext.class))).thenReturn("论文精读完成：");
        List<String> writes = new ArrayList<>();
        AtomicInteger reviews = new AtomicInteger();
        ResearchWriteReviewWorkflow flow = new ResearchWriteReviewWorkflow((input, config) -> "materials",
                (input, config) -> {
                    writes.add(input);
                    Files.createDirectories(document.getParent());
                    Files.writeString(document, "x".repeat(200_001));
                    return "file: " + WorkspacePaths.relative(document);
                }, (input, config) -> reviews.incrementAndGet() == 1 ? revise("WRITER") : PASS,
                analysis, new ProgressChannelRegistry());
        flow.researchWriteReview(brief, null);
        assertThat(writes).hasSize(2);
        assertThat(writes.get(1)).contains("超出长度限制", "[已截断]", "不能声称已精确保留")
                .doesNotContain("以下是上一版从磁盘读回的完整正文", "未受影响部分保持不变");
    }

    @Test
    void analystProblemsPreserveRequirementsInTheWriterRepairInstruction() {
        workflow(List.of(revise("ANALYST"), PASS)).researchWriteReview(brief, null);
        assertThat(writerInputs.get(1)).contains("reanalyzePaper(topic, 论文短名, reason, requirements)",
                "requirements 原样", brief, "结论不符");
        assertThat(researchInputs).hasSize(1);
    }

    /**
     * The core of the manual-download pause: a paper the research stage could not get
     * must stop the run before the expensive close reading, and the pause must be
     * registered so {@code POST /resume} can continue it.
     */
    @Test
    void missingPapersPauseTheRunBeforeCloseReading() {
        DownloadFailureRegistry failures = new DownloadFailureRegistry();
        ResearchResumeStore resumeStore = new ResearchResumeStore();
        AtomicInteger analysisCalls = new AtomicInteger();

        var flow = new ResearchWriteReviewWorkflow((input, config) -> {
            // The research stage could not fetch one paper; the downloader records that
            // against the same thread + topic the workflow reads back.
            failures.record("pause-thread", topic, "Missing-Paper",
                    "https://example.com/x.pdf", "HTTP 403");
            return "本轮材料：Alpha";
        }, (input, config) -> {
            throw new AssertionError("writer must not run while the run is waiting for papers");
        }, (input, config) -> {
            throw new AssertionError("reviewer must not run while the run is waiting for papers");
        }, analysis, new ProgressChannelRegistry(), null, failures, resumeStore);

        when(analysis.analyzeTopicSync(any(), any(), anyInt(), any(), any(ProgressContext.class)))
                .thenAnswer(invocation -> {
                    analysisCalls.incrementAndGet();
                    return "论文精读完成：";
                });

        String result = flow.researchWriteReview(brief, threadContext("pause-thread"));

        assertThat(result).contains("WORKFLOW_WAITING_FOR_PAPERS", "Missing-Paper");
        // The pause happens *before* close reading, so nothing expensive was spent.
        assertThat(analysisCalls).hasValue(0);
        // Registered for resume, carrying the same topic and brief.
        assertThat(resumeStore.get("pause-thread")).hasValueSatisfying(task -> {
            assertThat(task.topic()).isEqualTo(topic);
            assertThat(task.brief()).isEqualTo(brief);
            assertThat(task.missing()).extracting(ResearchResumeStore.MissingPaper::shortName)
                    .containsExactly("Missing-Paper");
        });
    }

    /**
     * The whole point of re-checking the library at pause time: a paper the user has
     * since supplied is no longer missing, so the run continues instead of asking again.
     */
    @Test
    void aPaperAlreadyInTheLibraryDoesNotPauseTheRun() throws IOException {
        DownloadFailureRegistry failures = new DownloadFailureRegistry();
        PaperLibrary library = new PaperLibrary(new com.itajay.superassistant.config.PaperDownloadProperties());
        Path libraryDir = WorkspacePaths.paperLibraryDir();
        Path libraryPdf = libraryDir.resolve("Library-Paper.pdf");
        AtomicInteger reviews = new AtomicInteger();

        Files.createDirectories(libraryDir);
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(libraryPdf.toFile());
        }

        try {
            var flow = new ResearchWriteReviewWorkflow((input, config) -> {
                // Fails to download, but the same paper is already in the library.
                failures.record("library-thread", topic, "Library-Paper",
                        "https://example.com/x.pdf", "HTTP 403");
                return "本轮材料：Alpha";
            }, (input, config) -> {
                Files.createDirectories(document.getParent());
                Files.writeString(document, "# 文档");
                return "file: " + WorkspacePaths.relative(document);
            }, (input, config) -> {
                reviews.incrementAndGet();
                return PASS;
            }, analysis, new ProgressChannelRegistry(), library, failures, null);

            when(analysis.analyzeTopicSync(any(), any(), anyInt(), any(), any(ProgressContext.class)))
                    .thenReturn("论文精读完成：");

            String result = flow.researchWriteReview(brief, threadContext("library-thread"));

            assertThat(result).doesNotContain("WORKFLOW_WAITING_FOR_PAPERS");
            assertThat(reviews).hasValue(1);
        } finally {
            Files.deleteIfExists(libraryPdf);
        }
    }

    private static ToolContext threadContext(String threadId) {
        return new ToolContext(Map.of("_AGENT_CONFIG_", RunnableConfig.builder()
                .threadId(threadId).build()));
    }
}

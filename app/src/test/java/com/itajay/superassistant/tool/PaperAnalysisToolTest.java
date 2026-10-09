package com.itajay.superassistant.tool;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressContext;
import com.itajay.superassistant.progress.ProgressStage;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.util.json.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PaperAnalysisTool}: the blocking batch (internally parallel),
 * the per-paper progress callback, and the single-paper retry entry.
 *
 * <p>The analyst is faked through the package-private {@code AnalystRun} seam —
 * the real one needs a live model. The fake reproduces the contract that matters:
 * success is a file appearing at {@code analysis/{短名}.md}, and a failed run
 * leaves nothing behind. Empty files stand in for PDFs because the batch path only
 * enumerates them by extension.</p>
 */
class PaperAnalysisToolTest {

    private static final String TOPIC = "unit-test-analysis-topic";
    private static final String REQUIREMENTS = "解释推导步骤\n指出消融实验的局限";
    private static final FileTime OLD_ANALYSIS_TIME = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));
    private static final String SUCCESS_HEADER = """
            > 来源等级：FULL_TEXT
            > 一句话定位：测试用分析文件。

            正文。
            """;

    /** Papers whose analyst run completes but writes nothing — the "unreadable PDF" failure. */
    private final Set<String> silentPapers = new HashSet<>();
    /** Papers whose analyst run throws — the "run blew up" failure. */
    private final Set<String> throwingPapers = new HashSet<>();
    /** Captures the actual brief on every lane, including failed runs and redos. */
    private final List<String> analystInputs = new CopyOnWriteArrayList<>();

    /** Records every progress event instead of sending it over SSE. */
    private final List<ProgressEvent> progressEvents = new CopyOnWriteArrayList<>();
    private final ProgressChannelRegistry progress = new ProgressChannelRegistry() {
        @Override
        public void publish(String threadId, ProgressEvent event) {
            progressEvents.add(event);
        }
    };

    private PaperAnalysisTool toolWith(int parallelism) {
        return new PaperAnalysisTool(input -> {
            analystInputs.add(input);
            String topic = valueAfter(input, "课题方向：");
            String shortName = valueAfter(input, "论文短名：");
            if (throwingPapers.contains(shortName)) {
                throw new IllegalStateException("boom: " + shortName);
            }
            if (!silentPapers.contains(shortName)) {
                Path target = AnalysisStore.resolveMarkdown(topic, shortName);
                Files.createDirectories(target.getParent());
                Files.writeString(target, SUCCESS_HEADER);
            }
            return Optional.empty();
        }, progress, parallelism);
    }

    private final PaperAnalysisTool tool = toolWith(4);

    @AfterEach
    void cleanUp() throws IOException {
        Path topicDir = WorkspacePaths.topicDir(TOPIC);
        if (!Files.isDirectory(topicDir)) {
            return;
        }
        try (var paths = Files.walk(topicDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private void writePdf(String name) throws IOException {
        Path dir = WorkspacePaths.papersDir(TOPIC);
        Files.createDirectories(dir);
        Files.createFile(dir.resolve(name + ".pdf"));
    }

    private Path analysisFile(String shortName) {
        return AnalysisStore.resolveMarkdown(TOPIC, shortName);
    }

    /** Give redo fixtures a deterministic old mtime, even on coarse-resolution filesystems. */
    private void writeExistingAnalysis(String shortName) throws IOException {
        Path target = analysisFile(shortName);
        Files.createDirectories(target.getParent());
        Files.writeString(target, SUCCESS_HEADER);
        Files.setLastModifiedTime(target, OLD_ANALYSIS_TIME);
    }

    private static ToolContext contextWithRequirements(Object requirements, String threadId) {
        RunnableConfig.Builder config = RunnableConfig.builder();
        if (requirements != null) {
            config.addMetadata(PaperAnalysisTool.REQUIREMENTS_METADATA_KEY, requirements);
        }
        if (threadId != null) {
            config.threadId(threadId);
        }
        return new ToolContext(Map.of("_AGENT_CONFIG_", config.build()));
    }

    private ToolCallback callback(String name) {
        return Arrays.stream(ToolCallbacks.from(tool))
                .filter(candidate -> candidate.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
    }

    private static String toolInput(String paper, String requirements) {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("topic", TOPIC);
        if (paper != null) input.put("paper", paper);
        if (requirements != null) input.put("requirements", requirements);
        return JsonParser.toJson(input);
    }

    private static String redoInput(String paper, String reason, String requirements) {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("topic", TOPIC);
        input.put("paperShortName", paper);
        input.put("reason", reason);
        if (requirements != null) input.put("requirements", requirements);
        return JsonParser.toJson(input);
    }

    private void assertBriefs(String reason, String requirements, String... papers) {
        assertThat(analystInputs).hasSize(papers.length).allSatisfy(input -> {
            assertThat(valueAfter(input, "课题方向：")).isEqualTo(TOPIC);
            assertThat(valueAfter(input, "本次任务说明：")).isEqualTo(reason);
            assertThat(input).contains("用户精读要求：\n" + requirements + "\n");
        });
        assertThat(analystInputs.stream().map(input -> valueAfter(input, "论文短名：")))
                .containsExactlyInAnyOrder(papers);
    }

    @Test
    void batchAnalysesEveryPaperAndWritesThemToDisk() throws IOException {
        writePdf("Alpha");
        writePdf("Beta");

        String result = tool.analyzePapers(TOPIC, null);

        assertThat(result).startsWith(PaperAnalysisTool.FINAL_INDEX_MARKER);
        assertThat(result).contains("Alpha").contains("Beta").doesNotContain("失败");
        assertThat(analysisFile("Alpha")).isRegularFile();
        assertThat(analysisFile("Beta")).isRegularFile();
    }

    @Test
    void batchReportsFailuresWithoutAbortingTheRest() throws IOException {
        writePdf("Good");
        writePdf("Silent");
        silentPapers.add("Silent");

        String result = tool.analyzePapers(TOPIC, null);

        assertThat(result).contains("✗ Silent").contains("未能产出分析文件");
        assertThat(result).contains("analyzePapers(topic, \"论文短名\")");
        assertThat(analysisFile("Good")).isRegularFile();
        assertThat(analysisFile("Silent")).doesNotExist();
    }

    @Test
    void aRunThatThrowsIsAFailureRowNotAStacktrace() throws IOException {
        writePdf("Bomba");
        throwingPapers.add("Bomba");

        String result = tool.analyzePapers(TOPIC, null);

        assertThat(result).contains("✗ Bomba").contains("精读运行异常");
        assertThat(result).doesNotContain("IllegalStateException");
    }

    @Test
    void skipsPapersThatAlreadyHaveAnAnalysis() throws IOException {
        writePdf("Done");
        writeExistingAnalysis("Done");

        String result = tool.analyzePapers(TOPIC, null);

        assertThat(result).contains("跳过");
        assertThat(result).doesNotContain("✗");
    }

    @Test
    void everyPaperAnnouncesStartAndCompletionOnTheChannel() throws IOException {
        writePdf("Alpha");
        writePdf("Silent");
        silentPapers.add("Silent");

        tool.analyzeTopicSync(TOPIC, "thread-1", 1);

        assertThat(progressEvents).anySatisfy(event -> {
            assertThat(event.detail()).contains("并行精读 2 篇");
        });
        assertThat(progressEvents).anySatisfy(event ->
                assertThat(event.detail()).contains("开始精读 Alpha（"));
        assertThat(progressEvents).anySatisfy(event ->
                assertThat(event.detail()).contains("精读完成 Alpha（"));
        assertThat(progressEvents).anySatisfy(event ->
                assertThat(event.detail()).contains("精读失败 Silent（"));
        assertThat(progressEvents.stream().map(ProgressEvent::detail)
                .filter(detail -> detail.startsWith("精读完成 ") || detail.startsWith("精读失败 ")))
                .hasSize(2).anyMatch(detail -> detail.endsWith("（1/2）"))
                .anyMatch(detail -> detail.endsWith("（2/2）"));
    }

    @Test
    void theWorkflowEntrySharesTheBatchPath() throws IOException {
        writePdf("Alpha");

        String viaWorkflow = tool.analyzeTopicSync(TOPIC, null, -1);
        String viaTool = tool.analyzePapers(TOPIC, null);

        // Same blocking path: both deliver the final index; the second call skips
        // the paper the first one analysed.
        assertThat(viaWorkflow).startsWith(PaperAnalysisTool.FINAL_INDEX_MARKER).contains("Alpha");
        assertThat(viaTool).startsWith(PaperAnalysisTool.FINAL_INDEX_MARKER).contains("跳过 1 篇");
    }

    @Test
    void singleFormAnalysesJustOnePaper() throws IOException {
        writePdf("Alpha");
        writePdf("Beta");

        String result = tool.analyzePapers(TOPIC, "Beta");

        assertThat(result).contains("重试成功").contains("Beta");
        assertThat(analysisFile("Beta")).isRegularFile();
        assertThat(analysisFile("Alpha")).doesNotExist();
    }

    @Test
    void singleFormRetriesAPaperTheBatchReportedFailed() throws IOException {
        writePdf("Silent");
        silentPapers.add("Silent");
        String firstAttempt = tool.analyzePapers(TOPIC, null);
        assertThat(firstAttempt).contains("✗ Silent");

        silentPapers.remove("Silent");
        String retry = tool.analyzePapers(TOPIC, "Silent");

        assertThat(retry).contains("重试成功");
        assertThat(analysisFile("Silent")).isRegularFile();
    }

    @Test
    void singleFormNeverOverwritesAnExistingAnalysis() throws IOException {
        writePdf("Done");
        writeExistingAnalysis("Done");

        String result = tool.analyzePapers(TOPIC, "Done");

        assertThat(result).contains("已有分析文件").contains("reanalyzePaper");
    }

    @Test
    void singleFormReportsAMissingPaperReadably() {
        String result = tool.analyzePapers(TOPIC, "NeverDownloaded");

        assertThat(result).startsWith("Error:");
        assertThat(result).contains("NeverDownloaded");
    }

    @Test
    void pendingPapersRunConcurrently() throws Exception {
        writePdf("Slow-A");
        writePdf("Slow-B");

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);

        PaperAnalysisTool parallelTool = new PaperAnalysisTool(input -> {
            inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.get(), Math::max);
            bothStarted.countDown();
            // Hold each lane until both are inside: with a serial loop this times
            // out and the assertion below fails.
            boolean both = bothStarted.await(5, TimeUnit.SECONDS);
            try {
                assertThat(both).isTrue();
                return Optional.empty();
            } finally {
                inFlight.decrementAndGet();
            }
        }, progress, 2);

        parallelTool.analyzePapers(TOPIC, null);

        assertThat(maxInFlight.get()).isEqualTo(2);
    }

    @Test
    void pendingPapersNeverExceedConfiguredParallelism() throws Exception {
        for (int i = 0; i < 6; i++) writePdf("Paper-" + i);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        CountDownLatch lanesOccupied = new CountDownLatch(4);
        CountDownLatch extraStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PaperAnalysisTool limited = new PaperAnalysisTool(input -> {
            maximum.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            lanesOccupied.countDown();
            if (started.incrementAndGet() > 4) extraStarted.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return Optional.empty();
            } finally {
                inFlight.decrementAndGet();
            }
        }, progress, 4);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<String> batch = caller.submit(() -> limited.analyzePapers(TOPIC, null));
        try {
            assertThat(lanesOccupied.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(extraStarted.await(250, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            release.countDown();
            caller.shutdown();
            assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(batch.get(5, TimeUnit.SECONDS)).startsWith(PaperAnalysisTool.FINAL_INDEX_MARKER);
        assertThat(maximum.get()).isEqualTo(4);
        assertThat(started.get()).isEqualTo(6);
    }

    @Test
    void aCompletedPaperPublishesBeforeAnEarlierSlowPaper() throws Exception {
        writePdf("Alpha");
        writePdf("Beta");
        CountDownLatch alphaStarted = new CountDownLatch(1);
        CountDownLatch releaseAlpha = new CountDownLatch(1);
        CountDownLatch betaDone = new CountDownLatch(1);
        ProgressChannelRegistry recording = new ProgressChannelRegistry() {
            @Override
            public void publish(String threadId, ProgressEvent event) {
                progressEvents.add(event);
                if (event.detail().startsWith("精读完成 Beta（")) betaDone.countDown();
            }
        };
        PaperAnalysisTool outOfOrder = new PaperAnalysisTool(input -> {
            String name = valueAfter(input, "论文短名：");
            if (name.equals("Alpha")) {
                alphaStarted.countDown();
                assertThat(releaseAlpha.await(5, TimeUnit.SECONDS)).isTrue();
            } else {
                assertThat(alphaStarted.await(5, TimeUnit.SECONDS)).isTrue();
            }
            Files.createDirectories(analysisFile(name).getParent());
            Files.writeString(analysisFile(name), SUCCESS_HEADER);
            return Optional.empty();
        }, recording, 2);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<String> batch = caller.submit(() -> outOfOrder.analyzeTopicSync(TOPIC, "thread-1", 1));
        try {
            assertThat(alphaStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(betaDone.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(batch.isDone()).isFalse();
        } finally {
            releaseAlpha.countDown();
            caller.shutdown();
            assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        String index = batch.get(5, TimeUnit.SECONDS);
        assertThat(index.indexOf("Alpha")).isLessThan(index.indexOf("Beta"));
        assertThat(progressEvents).anySatisfy(event ->
                assertThat(event.detail()).contains("精读完成 Beta（1/2）"));
    }

    @Test
    void interruptedBatchCancelsPendingWorkAndKeepsCollectedResults() throws Exception {
        writePdf("Alpha");
        writePdf("Beta");
        CountDownLatch alphaStarted = new CountDownLatch(1);
        CountDownLatch releaseAlpha = new CountDownLatch(1);
        CountDownLatch betaDone = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        AtomicReference<Thread> collector = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        ProgressChannelRegistry recording = new ProgressChannelRegistry() {
            @Override
            public void publish(String threadId, ProgressEvent event) {
                progressEvents.add(event);
                if (event.detail().startsWith("精读完成 Beta（")) betaDone.countDown();
            }
        };
        PaperAnalysisTool interruptible = new PaperAnalysisTool(input -> {
            String name = valueAfter(input, "论文短名：");
            if (name.equals("Alpha")) {
                alphaStarted.countDown();
                try {
                    releaseAlpha.await();
                } catch (InterruptedException e) {
                    workerInterrupted.countDown();
                    throw e;
                }
            } else {
                Files.createDirectories(analysisFile(name).getParent());
                Files.writeString(analysisFile(name), SUCCESS_HEADER);
            }
            return Optional.empty();
        }, recording, 2);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<String> batch = caller.submit(() -> {
            collector.set(Thread.currentThread());
            String index = interruptible.analyzeTopicSync(TOPIC, "thread-1", 1);
            interruptPreserved.set(Thread.currentThread().isInterrupted());
            return index;
        });
        try {
            assertThat(alphaStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(betaDone.await(2, TimeUnit.SECONDS)).isTrue();
            collector.get().interrupt();
            String index = batch.get(5, TimeUnit.SECONDS);
            assertThat(index).contains("✗ Alpha").contains("被中断").doesNotContain("✗ Beta");
            assertThat(interruptPreserved.get()).isTrue();
            assertThat(workerInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(progressEvents.stream().map(ProgressEvent::detail)
                    .filter(detail -> detail.startsWith("精读完成 ") || detail.startsWith("精读失败 ")))
                    .hasSize(2);
        } finally {
            releaseAlpha.countDown();
            caller.shutdownNow();
            assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void interruptedSerialBatchDoesNotStartTheNextPaper() throws Exception {
        writePdf("Alpha");
        writePdf("Beta");
        AtomicInteger started = new AtomicInteger();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        PaperAnalysisTool serial = new PaperAnalysisTool(input -> {
            started.incrementAndGet();
            throw new InterruptedException("cancelled");
        }, progress, 1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            String index = caller.submit(() -> {
                String result = serial.analyzePapers(TOPIC, null);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
                return result;
            }).get(5, TimeUnit.SECONDS);
            assertThat(index).contains("✗ Alpha").contains("✗ Beta");
            assertThat(started.get()).isEqualTo(1);
            assertThat(interruptPreserved.get()).isTrue();
        } finally {
            caller.shutdownNow();
            assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void anAlreadyInterruptedBatchStartsNoAnalystRuns() throws Exception {
        writePdf("Alpha");
        writePdf("Beta");
        AtomicInteger started = new AtomicInteger();
        PaperAnalysisTool interrupted = new PaperAnalysisTool(input -> {
            started.incrementAndGet();
            return Optional.empty();
        }, progress, 2);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            String index = caller.submit(() -> {
                Thread.currentThread().interrupt();
                return interrupted.analyzePapers(TOPIC, null);
            }).get(5, TimeUnit.SECONDS);
            assertThat(index).contains("✗ Alpha").contains("✗ Beta").contains("被中断");
            assertThat(started.get()).isZero();
        } finally {
            caller.shutdownNow();
            assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void indexOrderFollowsThePapersFolderNotCompletionOrder() throws IOException {
        writePdf("Gamma");
        writePdf("Alpha");
        writePdf("Beta");
        throwingPapers.add("Gamma"); // a slow/fast lane must not reorder the index

        String result = tool.analyzePapers(TOPIC, null);

        assertThat(result.indexOf("Alpha"))
                .isLessThan(result.indexOf("Beta"))
                .isLessThan(result.indexOf("✗ Gamma"));
    }

    @Test
    void emptyTopicFolderIsReadableNotAnError() {
        String result = tool.analyzePapers(TOPIC, null);

        assertThat(result).startsWith("No papers found");
    }

    // ── analyzeSingleSync / reanalyzeSync (the deep-read workflow's entries) ──

    @Test
    void workflowSingleEntryAnalysesAndIndexes() throws IOException {
        writePdf("Solo");

        String index = tool.analyzeSingleSync(TOPIC, "Solo", "thread-1", 1);

        assertThat(index).startsWith(PaperAnalysisTool.FINAL_INDEX_MARKER);
        assertThat(index).contains("Solo").contains("FULL_TEXT");
        assertThat(analysisFile("Solo")).isRegularFile();
    }

    @Test
    void workflowSingleEntrySkipsAnExistingAnalysis() throws IOException {
        writePdf("Kept");
        writeExistingAnalysis("Kept");

        String index = tool.analyzeSingleSync(TOPIC, "Kept", "thread-1", 1);

        assertThat(index).contains("已有分析，跳过");
        assertThat(analysisFile("Kept")).hasContent(SUCCESS_HEADER);
    }

    @Test
    void workflowSingleEntryReportsFailureAsAFailedIndex() throws IOException {
        writePdf("Doomed");
        silentPapers.add("Doomed");

        String index = tool.analyzeSingleSync(TOPIC, "Doomed", "thread-1", 1);

        assertThat(index).contains("**失败**");
        assertThat(analysisFile("Doomed")).doesNotExist();
    }

    @Test
    void workflowSingleEntryFailsReadablyForAMissingPaper() {
        String index = tool.analyzeSingleSync(TOPIC, "Never-Existed", "thread-1", 1);

        assertThat(index).contains("失败").contains("Never-Existed");
    }

    @Test
    void reanalyzeSyncOverwritesAnExistingAnalysis() throws IOException {
        writePdf("Fix-Me");
        writeExistingAnalysis("Fix-Me");

        boolean ok = tool.reanalyzeSync(TOPIC, "Fix-Me", "公式抄错", "thread-1", 1);

        assertThat(ok).isTrue();
        assertThat(analysisFile("Fix-Me")).isRegularFile();
    }

    @Test
    void reanalyzeSyncReportsFalseWhenNothingFreshAppears() throws IOException {
        writePdf("Stuck");
        writeExistingAnalysis("Stuck");
        silentPapers.add("Stuck"); // the analyst runs but writes nothing new

        boolean ok = tool.reanalyzeSync(TOPIC, "Stuck", "四维缺失", "thread-1", 1);

        assertThat(ok).isFalse();
    }

    @Test
    void reanalyzeSyncFailsForAPaperWithoutAnExistingAnalysis() throws IOException {
        writePdf("Never-Read");

        boolean ok = tool.reanalyzeSync(TOPIC, "Never-Read", "任意原因", "thread-1", 1);

        assertThat(ok).isFalse();
    }

    // ── Requirements are preserved in the real brief, independently of the run reason ──

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void workflowBatchPassesRequirementsToEverySerialOrParallelRun(int parallelism) throws IOException {
        writePdf("Alpha");
        writePdf("Beta");

        String index = toolWith(parallelism).analyzeTopicSync(TOPIC, "thread-1", 2, REQUIREMENTS);

        assertThat(index).startsWith(PaperAnalysisTool.FINAL_INDEX_MARKER).doesNotContain("失败");
        assertBriefs("首次精读", REQUIREMENTS, "Alpha", "Beta");
    }

    @Test
    void onePendingPaperInTheBatchRetainsRequirements() throws IOException {
        writePdf("Only");

        tool.analyzeTopicSync(TOPIC, null, -1, REQUIREMENTS);

        assertBriefs("首次精读", REQUIREMENTS, "Only");
    }

    @Test
    void workflowSingleFirstReadRetainsRequirementsAndItsOwnReason() throws IOException {
        writePdf("Solo");

        String index = tool.analyzeSingleSync(TOPIC, "Solo", "thread-1", 1, REQUIREMENTS);

        assertThat(index).contains("已精读");
        assertBriefs("单篇精读", REQUIREMENTS, "Solo");
    }

    @Test
    void failedBatchAndSingleRetryBothRetainTheOriginalRequirements() throws IOException {
        writePdf("Silent");
        silentPapers.add("Silent");
        ToolContext context = contextWithRequirements(REQUIREMENTS, "thread-1");
        ToolCallback analyze = callback("analyzePapers");

        assertThat(analyze.call(toolInput(null, null), context)).contains("✗ Silent");
        assertBriefs("首次精读", REQUIREMENTS, "Silent");
        analystInputs.clear();
        silentPapers.remove("Silent");

        assertThat(analyze.call(toolInput("Silent", null), context)).contains("重试成功");
        assertBriefs("失败篇目的单篇重试", REQUIREMENTS, "Silent");
    }

    @Test
    void workflowRedoRetainsReviewReasonAndOriginalRequirements() throws IOException {
        writePdf("Fix-Me");
        writeExistingAnalysis("Fix-Me");

        boolean ok = tool.reanalyzeSync(TOPIC, "Fix-Me", "公式抄错", "thread-1", 2, REQUIREMENTS);

        assertThat(ok).isTrue();
        assertBriefs("公式抄错", REQUIREMENTS, "Fix-Me");
        assertThat(Files.getLastModifiedTime(analysisFile("Fix-Me"))).isNotEqualTo(OLD_ANALYSIS_TIME);
    }

    @Test
    void requirementsDoNotTurnAnUnchangedRedoArtifactIntoSuccess() throws IOException {
        writePdf("Stuck");
        writeExistingAnalysis("Stuck");
        silentPapers.add("Stuck");

        boolean ok = tool.reanalyzeSync(TOPIC, "Stuck", "四维缺失", "thread-1", 2, REQUIREMENTS);

        assertThat(ok).isFalse();
        assertBriefs("四维缺失", REQUIREMENTS, "Stuck");
        assertThat(Files.getLastModifiedTime(analysisFile("Stuck"))).isEqualTo(OLD_ANALYSIS_TIME);
        assertThat(analysisFile("Stuck")).hasContent(SUCCESS_HEADER);
    }

    @Test
    void batchWithNewRequirementsSkipsOldAnalysesWithoutClaimingCoverage() throws IOException {
        writePdf("Done");
        writeExistingAnalysis("Done");

        String index = tool.analyzeTopicSync(TOPIC, "thread-1", 2, REQUIREMENTS);

        assertThat(index).contains("跳过 1 篇", "本次未按新要求重读", "不能保证已有分析覆盖", "reanalyzePaper");
        assertThat(analystInputs).isEmpty();
        assertThat(Files.getLastModifiedTime(analysisFile("Done"))).isEqualTo(OLD_ANALYSIS_TIME);
    }

    @Test
    void workflowSingleWithNewRequirementsHonestlyReportsItsSkip() throws IOException {
        writePdf("Done");
        writeExistingAnalysis("Done");

        String index = tool.analyzeSingleSync(TOPIC, "Done", "thread-1", 2, REQUIREMENTS);

        assertThat(index).contains("已有分析，跳过", "本次未按新要求重读", "不能保证已有分析覆盖");
        assertThat(analystInputs).isEmpty();
        assertThat(analysisFile("Done")).hasContent(SUCCESS_HEADER);
    }

    @Test
    void callbackSingleWithMetadataRequirementsHonestlyReportsItsSkip() throws IOException {
        writePdf("Done");
        writeExistingAnalysis("Done");

        String result = callback("analyzePapers").call(toolInput("Done", null),
                contextWithRequirements(REQUIREMENTS, "thread-1"));

        assertThat(result).contains("已有分析文件", "本次未按新要求重读", "不能保证已有分析覆盖", "reanalyzePaper");
        assertThat(analystInputs).isEmpty();
        assertThat(Files.getLastModifiedTime(analysisFile("Done"))).isEqualTo(OLD_ANALYSIS_TIME);
    }

    // ── Real tool registration and JSON invocation, not direct calls to new signatures ──

    @Test
    void callbacksExposeExactlyOneSchemaPerNameAndOptionalRequirements() throws IOException {
        ToolCallback[] callbacks = ToolCallbacks.from(tool);

        assertThat(callbacks).hasSize(2);
        assertThat(Arrays.stream(callbacks).map(value -> value.getToolDefinition().name()))
                .containsExactlyInAnyOrder("analyzePapers", "reanalyzePaper");
        for (ToolCallback callback : callbacks) {
            JsonNode schema = JsonParser.getObjectMapper().readTree(callback.getToolDefinition().inputSchema());
            JsonNode properties = schema.path("properties");
            assertThat(properties.path("requirements").path("type").asText()).isEqualTo("string");
            assertThat(properties.has("toolContext")).isFalse();
            List<String> required = new java.util.ArrayList<>();
            schema.path("required").forEach(property -> required.add(property.asText()));
            if (callback.getToolDefinition().name().equals("analyzePapers")) {
                assertThat(required).containsExactly("topic");
                assertThat(properties.has("paper")).isTrue();
                assertThat(properties.size()).isEqualTo(3);
            } else {
                assertThat(required).containsExactlyInAnyOrder("topic", "paperShortName", "reason");
                assertThat(properties.size()).isEqualTo(4);
            }
        }
    }

    @Test
    void callbackOmittingAllOptionalArgumentsReadsConfigMetadataForTheBatch() throws IOException {
        writePdf("Alpha");
        writePdf("Beta");

        String result = JsonParser.fromJson(callback("analyzePapers").call(toolInput(null, null),
                contextWithRequirements(REQUIREMENTS, "thread-1")), String.class);

        assertThat(result).startsWith(PaperAnalysisTool.FINAL_INDEX_MARKER);
        assertBriefs("首次精读", REQUIREMENTS, "Alpha", "Beta");
    }

    @Test
    void callbackSingleExplicitRequirementsOverrideMetadata() throws IOException {
        writePdf("Solo");
        String explicit = "仅解释损失函数";

        callback("analyzePapers").call(toolInput("Solo", explicit),
                contextWithRequirements(REQUIREMENTS, "thread-1"));

        assertBriefs("失败篇目的单篇重试", explicit, "Solo");
        assertThat(analystInputs).allSatisfy(input -> assertThat(input).doesNotContain(REQUIREMENTS));
    }

    @Test
    void blankExplicitRequirementsFallBackToConfigMetadata() throws IOException {
        writePdf("Solo");

        callback("analyzePapers").call(toolInput("Solo", " \n\t"), contextWithRequirements(REQUIREMENTS, null));

        assertBriefs("失败篇目的单篇重试", REQUIREMENTS, "Solo");
    }

    @Test
    void callbackBatchExplicitRequirementsOverrideMetadataForEveryPaper() throws IOException {
        writePdf("Alpha");
        writePdf("Beta");
        String explicit = "逐篇提取评测指标";

        callback("analyzePapers").call(toolInput(null, explicit), contextWithRequirements(REQUIREMENTS, "thread-1"));

        assertBriefs("首次精读", explicit, "Alpha", "Beta");
        assertThat(analystInputs).allSatisfy(input -> assertThat(input).doesNotContain(REQUIREMENTS));
    }

    @Test
    void metadataStillAppliesWhenConfigHasNoThreadId() throws IOException {
        writePdf("No-Thread");

        callback("analyzePapers").call(toolInput("No-Thread", null), contextWithRequirements(REQUIREMENTS, null));

        assertBriefs("失败篇目的单篇重试", REQUIREMENTS, "No-Thread");
    }

    @Test
    void differentConfigsOnTheSameThreadDoNotLeakRequirements() throws IOException {
        writePdf("First");
        writePdf("Second");
        writePdf("Third");
        ToolCallback analyze = callback("analyzePapers");

        analyze.call(toolInput("First", null), contextWithRequirements("要求-A", "shared-thread"));
        analyze.call(toolInput("Second", null), contextWithRequirements("要求-B", "shared-thread"));
        analyze.call(toolInput("Third", null), contextWithRequirements(null, "shared-thread"));

        assertThat(analystInputs).hasSize(3);
        assertThat(analystInputs.get(0)).contains("要求-A").doesNotContain("要求-B");
        assertThat(analystInputs.get(1)).contains("要求-B").doesNotContain("要求-A");
        assertThat(analystInputs.get(2)).doesNotContain("用户精读要求：", "要求-A", "要求-B");
    }

    @Test
    void missingOrNonStringMetadataDoesNotCreateOrReuseRequirements() throws IOException {
        ToolCallback analyze = callback("analyzePapers");
        List<ToolContext> contexts = List.of(contextWithRequirements(null, null),
                contextWithRequirements(42, null), contextWithRequirements(" \n\t", null));
        for (int i = 0; i < contexts.size(); i++) {
            String paper = "No-Requirements-" + i;
            writePdf(paper);
            assertThat(analyze.call(toolInput(paper, null), contexts.get(i))).contains("重试成功");
        }

        assertThat(analystInputs).hasSize(contexts.size())
                .allSatisfy(input -> assertThat(input).doesNotContain("用户精读要求："));
    }

    @Test
    void oldJavaEntryAcceptsMissingToolContextWithoutRequirements() throws IOException {
        // MethodToolCallback requires a nonempty ToolContext for context-taking tools;
        // the Java compatibility entry also supports callers with no tool context.
        writePdf("Null-Context");
        writePdf("Empty-Context");

        assertThat(tool.analyzePapers(TOPIC, "Null-Context", null)).contains("重试成功");
        assertThat(tool.analyzePapers(TOPIC, "Empty-Context", new ToolContext(Map.of()))).contains("重试成功");

        assertThat(analystInputs).hasSize(2)
                .allSatisfy(input -> assertThat(input).doesNotContain("用户精读要求："));
    }

    @Test
    void callbackRedoOmittingOptionalRequirementsRetainsMetadataAndReviewReason() throws IOException {
        writePdf("Redo");
        writeExistingAnalysis("Redo");

        String result = JsonParser.fromJson(callback("reanalyzePaper").call(redoInput("Redo", "公式抄错", null),
                contextWithRequirements(REQUIREMENTS, null)), String.class);

        assertThat(result).startsWith("Re-analysed");
        assertBriefs("公式抄错", REQUIREMENTS, "Redo");
    }

    @Test
    void callbackRedoExplicitRequirementsOverrideMetadataWithoutReplacingReason() throws IOException {
        writePdf("Redo");
        writeExistingAnalysis("Redo");
        String explicit = "解释实验设置";

        String result = JsonParser.fromJson(callback("reanalyzePaper").call(redoInput("Redo", "缺失实验维度", explicit),
                contextWithRequirements(REQUIREMENTS, "thread-1")), String.class);

        assertThat(result).startsWith("Re-analysed");
        assertBriefs("缺失实验维度", explicit, "Redo");
        assertThat(analystInputs).allSatisfy(input -> assertThat(input).doesNotContain(REQUIREMENTS));
    }

    @Test
    void redoConfigsRemainIsolatedEvenForTheSameThread() throws IOException {
        writePdf("First-Redo");
        writePdf("Second-Redo");
        writeExistingAnalysis("First-Redo");
        writeExistingAnalysis("Second-Redo");
        ToolCallback redo = callback("reanalyzePaper");

        redo.call(redoInput("First-Redo", "纠正公式", null), contextWithRequirements("要求-A", "shared-thread"));
        redo.call(redoInput("Second-Redo", "补齐来源", null), contextWithRequirements("要求-B", "shared-thread"));

        assertThat(analystInputs).hasSize(2);
        assertThat(analystInputs.get(0)).contains("本次任务说明：纠正公式", "要求-A").doesNotContain("要求-B");
        assertThat(analystInputs.get(1)).contains("本次任务说明：补齐来源", "要求-B").doesNotContain("要求-A");
    }

    @Test
    void oldJavaAnalyzeSignatureStillUsesMetadataThroughItsDelegate() throws IOException {
        writePdf("Legacy");

        tool.analyzePapers(TOPIC, "Legacy", contextWithRequirements(REQUIREMENTS, null));

        assertBriefs("失败篇目的单篇重试", REQUIREMENTS, "Legacy");
    }

    @Test
    void oldJavaRedoSignatureRemainsCallableWithoutRequirements() throws IOException {
        writePdf("Legacy");
        writeExistingAnalysis("Legacy");

        String result = tool.reanalyzePaper(TOPIC, "Legacy", "旧版重做原因");

        assertThat(result).startsWith("Re-analysed");
        assertThat(analystInputs).singleElement().satisfies(input -> {
            assertThat(valueAfter(input, "本次任务说明：")).isEqualTo("旧版重做原因");
            assertThat(input).doesNotContain("用户精读要求：");
        });
    }

    private ProgressContext progressContext() {
        return new ProgressContext("thread-1", "run-1", ProgressEvent.Workflow.RESEARCH_WRITE_REVIEW,
                ProgressStage.ANALYZING, 2, TOPIC, null);
    }

    private List<ProgressEvent.Payload> payloads() {
        return progressEvents.stream().map(ProgressEvent::payload).filter(java.util.Objects::nonNull).toList();
    }

    @Test
    void batchPayloadsCountRealSuccessFailureSkipAndConcurrentStarts() throws IOException {
        writePdf("Alpha");
        writePdf("Broken");
        writePdf("Kept");
        writeExistingAnalysis("Kept");
        silentPapers.add("Broken");

        String index = tool.analyzeTopicSync(TOPIC, "thread-1", 2, REQUIREMENTS, progressContext());

        assertThat(index).contains("本次分析 1 篇", "跳过 1 篇", "失败 1 篇");
        assertThat(payloads()).anySatisfy(payload -> {
            assertThat(payload.paper()).isEqualTo("Kept");
            assertThat(payload.status()).isEqualTo(ProgressEvent.Status.SKIPPED);
        }).anySatisfy(payload -> {
            assertThat(payload.paper()).isEqualTo("Alpha");
            assertThat(payload.status()).isEqualTo(ProgressEvent.Status.QUEUED);
            assertThat(payload.counts().active()).isZero();
        }).anySatisfy(payload -> {
            assertThat(payload.paper()).isEqualTo("Broken");
            assertThat(payload.status()).isEqualTo(ProgressEvent.Status.FAILED);
        });
        var terminal = payloads().getLast();
        assertThat(terminal.paper()).isNull();
        assertThat(terminal.status()).isEqualTo(ProgressEvent.Status.FAILED);
        assertThat(terminal.counts()).isEqualTo(new ProgressEvent.Counts(3, 3, 1, 1, 1, 0));
        assertThat(payloads()).allSatisfy(payload -> {
            assertThat(payload.runId()).isEqualTo("run-1");
            assertThat(payload.workflow()).isEqualTo(ProgressEvent.Workflow.RESEARCH_WRITE_REVIEW);
            assertThat(payload.counts().completed()).isEqualTo(payload.counts().succeeded()
                    + payload.counts().failed() + payload.counts().skipped());
            assertThat(payload.counts().active()).isBetween(0, 4);
        });
    }

    @Test
    void allSkippedBatchHasNoQueuedOrRunningPaperAndHonestFinalCounters() throws IOException {
        writePdf("Done");
        writeExistingAnalysis("Done");

        tool.analyzeTopicSync(TOPIC, "thread-1", 2, REQUIREMENTS, progressContext());

        assertThat(analystInputs).isEmpty();
        assertThat(payloads().stream().filter(payload -> payload.paper() != null)).singleElement()
                .satisfies(payload -> assertThat(payload.status()).isEqualTo(ProgressEvent.Status.SKIPPED));
        assertThat(payloads().getLast().status()).isEqualTo(ProgressEvent.Status.SKIPPED);
        assertThat(payloads().getLast().counts()).isEqualTo(new ProgressEvent.Counts(1, 1, 0, 0, 1, 0));
    }

    @Test
    void configuredWorkersReceiveFreshPaperContextsAndNoParentMetadata() throws IOException {
        writePdf("Alpha");
        writePdf("Beta");
        List<RunnableConfig> configs = new CopyOnWriteArrayList<>();
        PaperAnalysisTool configured = new PaperAnalysisTool((input, config) -> {
            configs.add(config);
            String paper = valueAfter(input, "论文短名：");
            ProgressContext child = ProgressContext.from(new ToolContext(Map.of("_AGENT_CONFIG_", config)))
                    .orElseThrow();
            assertThat(child.paper()).isEqualTo(paper);
            assertThat(child.topic()).isEqualTo(TOPIC);
            assertThat(child.runId()).isEqualTo("run-1");
            assertThat(config.threadId()).contains("thread-1");
            assertThat(config.metadata(PaperAnalysisTool.REQUIREMENTS_METADATA_KEY)).contains(REQUIREMENTS);
            assertThat(config.metadata("approval")).isEmpty();
            assertThat(config.metadata("feedback")).isEmpty();
            Files.createDirectories(analysisFile(paper).getParent());
            Files.writeString(analysisFile(paper), SUCCESS_HEADER);
            return Optional.empty();
        }, progress, 2);
        RunnableConfig parent = progressContext().childConfig(REQUIREMENTS);
        parent = RunnableConfig.builder(parent).addMetadata("approval", "do not inherit")
                .addMetadata("feedback", "do not inherit").build();

        configured.analyzePapers(TOPIC, null, null, new ToolContext(Map.of("_AGENT_CONFIG_", parent)));

        assertThat(configs).hasSize(2);
        assertThat(configs.get(0)).isNotSameAs(configs.get(1)).isNotSameAs(parent);
        assertThat(progressContext().paper()).isNull();
    }

    @Test
    void structuredSingleSkipAndRedoUseTheArtifactRatherThanPretendingSuccess() throws IOException {
        writePdf("Stuck");
        writeExistingAnalysis("Stuck");
        silentPapers.add("Stuck");

        tool.analyzeSingleSync(TOPIC, "Stuck", "thread-1", 2, REQUIREMENTS, progressContext());
        assertThat(payloads().getLast().status()).isEqualTo(ProgressEvent.Status.SKIPPED);
        assertThat(payloads().getLast().counts()).isEqualTo(new ProgressEvent.Counts(1, 1, 0, 0, 1, 0));
        progressEvents.clear();
        assertThat(tool.reanalyzeSync(TOPIC, "Stuck", "修正公式", "thread-1", 2,
                REQUIREMENTS, progressContext())).isFalse();
        assertThat(payloads().getLast().status()).isEqualTo(ProgressEvent.Status.FAILED);
        assertThat(payloads().getLast().counts()).isEqualTo(new ProgressEvent.Counts(1, 1, 0, 1, 0, 0));
    }

    /** First value on the line with the given marker; the fake's brief format. */
    private static String valueAfter(String input, String marker) {
        for (String line : input.split("\n")) {
            if (line.startsWith(marker)) {
                return line.substring(marker.length()).trim();
            }
        }
        throw new IllegalArgumentException("brief is missing the line \"" + marker + "\"");
    }
}

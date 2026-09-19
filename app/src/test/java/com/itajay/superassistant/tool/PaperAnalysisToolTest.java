package com.itajay.superassistant.tool;

import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;

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
    private static final String SUCCESS_HEADER = """
            > 来源等级：FULL_TEXT
            > 一句话定位：测试用分析文件。

            正文。
            """;

    /** Papers whose analyst run completes but writes nothing — the "unreadable PDF" failure. */
    private final Set<String> silentPapers = new HashSet<>();
    /** Papers whose analyst run throws — the "run blew up" failure. */
    private final Set<String> throwingPapers = new HashSet<>();

    /** Records every progress event instead of sending it over SSE. */
    private final List<ProgressEvent> progressEvents = new ArrayList<>();
    private final ProgressChannelRegistry progress = new ProgressChannelRegistry() {
        @Override
        public void publish(String threadId, ProgressEvent event) {
            progressEvents.add(event);
        }
    };

    private PaperAnalysisTool toolWith(int parallelism) {
        return new PaperAnalysisTool(input -> {
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
        Files.createDirectories(analysisFile("Done").getParent());
        Files.writeString(analysisFile("Done"), SUCCESS_HEADER);

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
                assertThat(event.detail()).contains("精读完成 Alpha（1/2）"));
        assertThat(progressEvents).anySatisfy(event ->
                assertThat(event.detail()).contains("精读失败 Silent（2/2）"));
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
        Files.createDirectories(analysisFile("Done").getParent());
        Files.writeString(analysisFile("Done"), SUCCESS_HEADER);

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

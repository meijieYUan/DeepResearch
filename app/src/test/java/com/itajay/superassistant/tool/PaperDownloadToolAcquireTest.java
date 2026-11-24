package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PaperDownloadTool#acquirePaper}'s resolution order: local file →
 * already in topic → another topic's copy → download.
 *
 * <p>Every branch that must not hit the network is tested without one — a URL source
 * with the paper already on disk resolves in step 1 or 3 and never reaches the fetch,
 * which is exactly the "local wins" property this ordering exists for. The download
 * branch itself needs a live host and stays out of unit tests (it is covered by
 * {@code PaperDownloadToolManualTest}).</p>
 */
class PaperDownloadToolAcquireTest {

    private static final String TOPIC = "unit-test-acquire-topic";
    private static final String OTHER_TOPIC = "unit-test-acquire-other";
    /** Becomes a topic of its own, so it is named like a test and cleaned up like one. */
    private static final String FRESH = "unit-test-acquire-fresh";

    private final List<ProgressEvent> progressEvents = new ArrayList<>();
    private final PaperDownloadTool tool = new PaperDownloadTool(
            new PaperDownloadProperties(),
            new ProgressChannelRegistry() {
                @Override
                public void publish(String threadId, ProgressEvent event) {
                    progressEvents.add(event);
                }
            },
            null,
            null);

    @AfterEach
    void cleanUp() throws IOException {
        for (String topic : List.of(TOPIC, OTHER_TOPIC, FRESH)) {
            Path topicDir = WorkspacePaths.topicDir(topic);
            if (!Files.isDirectory(topicDir)) {
                continue;
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
    }

    private Path writeRealPdf(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(file.toFile());
        }
        return file;
    }

    @Test
    void reusesAPaperAlreadyUnderTheTopic() throws IOException {
        writeRealPdf(WorkspacePaths.papersDir(TOPIC), "Existing.pdf");

        PaperDownloadTool.AcquireResult result =
                tool.acquirePaper(TOPIC, "https://arxiv.org/abs/1706.03762", "Existing", null);

        assertThat(result.ok()).isTrue();
        assertThat(result.origin()).isEqualTo(PaperDownloadTool.Origin.REUSED_IN_TOPIC);
        assertThat(result.shortName()).isEqualTo("Existing");
    }

    @Test
    void importsALocalFileTheCallerPointedAt() throws Exception {
        Path source = writeRealPdf(Files.createTempDirectory("unit-test-acquire-"), "Local-One.pdf");

        PaperDownloadTool.AcquireResult result =
                tool.acquirePaper(TOPIC, source.toString(), null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.origin()).isEqualTo(PaperDownloadTool.Origin.LOCAL_COPY);
        assertThat(result.shortName()).isEqualTo("Local-One");
        assertThat(WorkspacePaths.papersDir(TOPIC).resolve("Local-One.pdf")).exists();
    }

    @Test
    void copiesAPaperAnotherTopicAlreadyHas() throws IOException {
        writeRealPdf(WorkspacePaths.papersDir(OTHER_TOPIC), "Shared-Paper.pdf");

        PaperDownloadTool.AcquireResult result =
                tool.acquirePaper(TOPIC, "Shared-Paper", null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.origin()).isEqualTo(PaperDownloadTool.Origin.COPIED_FROM_OTHER_TOPIC);
        assertThat(result.detail()).contains(OTHER_TOPIC);
        assertThat(WorkspacePaths.papersDir(TOPIC).resolve("Shared-Paper.pdf")).exists();
    }

    @Test
    void adoptsTheExistingTopicsWhenNoneIsGiven() throws IOException {
        writeRealPdf(WorkspacePaths.papersDir(OTHER_TOPIC), "Known-There.pdf");

        PaperDownloadTool.AcquireResult result =
                tool.acquirePaper(null, "Known-There", null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.topic()).isEqualTo(OTHER_TOPIC);
        assertThat(result.origin()).isEqualTo(PaperDownloadTool.Origin.REUSED_IN_TOPIC);
    }

    @Test
    void aUrlWithThePaperOnDiskNeverDownloads() throws IOException {
        // A URL source with a short name that already exists must resolve on disk —
        // the ordering's whole point is that local beats fetch.
        writeRealPdf(WorkspacePaths.papersDir(TOPIC), "Already-Here.pdf");

        PaperDownloadTool.AcquireResult result =
                tool.acquirePaper(TOPIC, "https://example.com/elsewhere.pdf", "Already-Here", null);

        assertThat(result.ok()).isTrue();
        assertThat(result.origin()).isEqualTo(PaperDownloadTool.Origin.REUSED_IN_TOPIC);
    }

    @Test
    void reportsNotFoundForAnUnknownShortNameWithoutFetching() {
        PaperDownloadTool.AcquireResult result =
                tool.acquirePaper(TOPIC, "Never-Seen-Anywhere", null, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.origin()).isEqualTo(PaperDownloadTool.Origin.NOT_FOUND);
        assertThat(result.detail()).contains("找不到论文");
    }

    @Test
    void aMissingLocalFileIsNotFoundRatherThanADownload() {
        // A .pdf path whose file is gone classifies as a short name (existence is what
        // makes a local file), and the name lookup then misses — either way the run
        // must NOT fall through to a fetch and download a paper the user never meant.
        PaperDownloadTool.AcquireResult result =
                tool.acquirePaper(TOPIC, "C:/definitely/not/here/Gone.pdf", null, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.origin()).isEqualTo(PaperDownloadTool.Origin.NOT_FOUND);
    }

    @Test
    void defaultTopicForANewLandingIsTheShortName() throws Exception {
        Path source = writeRealPdf(Files.createTempDirectory("unit-test-acquire-"), FRESH + ".pdf");

        PaperDownloadTool.AcquireResult result =
                tool.acquirePaper(null, source.toString(), null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.topic()).isEqualTo(FRESH);
    }

    /** The old order answered with whatever the topic already had under that name. */
    @Test
    void theFileTheCallerPointedAtWinsOverAnotherPaperWithItsName() throws Exception {
        TestPdfs.paper(WorkspacePaths.papersDir(TOPIC).resolve("Survey.pdf"), "1706.03762", "Attention Is All You Need");
        Path source = TestPdfs.paper(Files.createTempDirectory("unit-test-acquire-").resolve("Survey.pdf"),
                "1512.03385", "Deep Residual Learning for Image Recognition");

        PaperDownloadTool.AcquireResult result = tool.acquirePaper(TOPIC, source.toString(), null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.origin()).isEqualTo(PaperDownloadTool.Origin.LOCAL_COPY);
        assertThat(result.shortName()).isEqualTo("Survey-1512.03385");
        assertThat(result.pdfRelativePath()).endsWith("papers/Survey-1512.03385.pdf");
    }

    /** Picking one would close-read the wrong paper without anybody noticing. */
    @Test
    void aShortNameThatSeveralPapersCarryIsAQuestion() throws IOException {
        TestPdfs.paper(WorkspacePaths.papersDir(TOPIC).resolve("Dup-Name.pdf"), "1706.03762", "Attention Is All You Need");
        TestPdfs.paper(WorkspacePaths.papersDir(OTHER_TOPIC).resolve("Dup-Name.pdf"), "1512.03385",
                "Deep Residual Learning for Image Recognition");

        PaperDownloadTool.AcquireResult unclear = tool.acquirePaper(null, "Dup-Name", null, null);
        assertThat(unclear.ok()).isFalse();
        assertThat(unclear.detail()).contains(TOPIC, OTHER_TOPIC, "1706.03762", "1512.03385");

        PaperDownloadTool.AcquireResult clear = tool.acquirePaper(OTHER_TOPIC, "Dup-Name", null, null);
        assertThat(clear.ok()).isTrue();
        assertThat(clear.topic()).isEqualTo(OTHER_TOPIC);
    }

    @Test
    void aShortNameMayCarryItsExtension() throws IOException {
        TestPdfs.paper(WorkspacePaths.papersDir(TOPIC).resolve("A-Rather-Long-Label.pdf"), null,
                "Deep Residual Learning for Image Recognition");

        assertThat(tool.acquirePaper(TOPIC, "A-Rather-Long-Label.pdf", null, null).ok()).isTrue();
        assertThat(tool.acquirePaper(TOPIC, "A-Rather-Long-Label", null, null).ok()).isTrue();
    }
}

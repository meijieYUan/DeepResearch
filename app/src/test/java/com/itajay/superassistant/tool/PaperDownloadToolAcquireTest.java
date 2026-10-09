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
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final List<ProgressEvent> progressEvents = new ArrayList<>();
    private final PaperDownloadTool tool = new PaperDownloadTool(
            new PaperDownloadProperties(),
            new ProgressChannelRegistry() {
                @Override
                public void publish(String threadId, ProgressEvent event) {
                    progressEvents.add(event);
                }
            });

    @AfterEach
    void cleanUp() throws IOException {
        for (String topic : List.of(TOPIC, OTHER_TOPIC)) {
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
        Path source = writeRealPdf(Files.createTempDirectory("unit-test-acquire-"), "Fresh-Paper.pdf");

        PaperDownloadTool.AcquireResult result =
                tool.acquirePaper(null, source.toString(), null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.topic()).isEqualTo("Fresh-Paper");
    }
}

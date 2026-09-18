package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the writer agent's paper-reading tool.
 *
 * <p>Uses a real (generated) PDF rather than a stub: the point of this tool is that
 * PDFBox can read what the downloader saved, and mocking that away would test
 * nothing.</p>
 */
class PaperTextToolTest {

    private static final String TOPIC = "unit-test-text-topic";

    private final PaperTextTool tool = new PaperTextTool(new PaperDownloadProperties());

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

    /** Writes a small but genuine multi-page PDF into the topic's papers folder. */
    private Path writePdf(String name, int pages) throws IOException {
        Path dir = WorkspacePaths.papersDir(TOPIC);
        Files.createDirectories(dir);
        Path pdf = dir.resolve(name + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void extractsTextFromADownloadedPdf() throws IOException {
        writePdf("SamplePaper", 3);

        String result = tool.extractPaperText(TOPIC, "SamplePaper", null, null);

        assertThat(result).doesNotStartWith("Error:");
        assertThat(result).contains("Pages: 1-3 of 3");
        assertThat(result).contains("investigation/" + TOPIC + "/papers/SamplePaper.pdf");
    }

    @Test
    void acceptsTheNameWithOrWithoutTheExtension() throws IOException {
        writePdf("Named", 1);

        assertThat(tool.extractPaperText(TOPIC, "Named", null, null)).doesNotStartWith("Error:");
        assertThat(tool.extractPaperText(TOPIC, "Named.pdf", null, null)).doesNotStartWith("Error:");
    }

    @Test
    void honoursThePageRange() throws IOException {
        writePdf("Ranged", 5);

        String result = tool.extractPaperText(TOPIC, "Ranged", 2, 3);

        assertThat(result).contains("Pages: 2-3 of 5");
        assertThat(result).contains("[More pages available: 4-5]");
    }

    @Test
    void clampsAPageRangeBeyondTheDocument() throws IOException {
        writePdf("Short", 2);

        String result = tool.extractPaperText(TOPIC, "Short", 1, 99);

        assertThat(result).doesNotStartWith("Error:");
        assertThat(result).contains("of 2");
    }

    @Test
    void reportsAMissingPaperReadably() {
        String result = tool.extractPaperText(TOPIC, "NeverDownloaded", null, null);

        assertThat(result).startsWith("Error:");
        assertThat(result).contains("PDF not found");
        // The message must tell the agent what to do next, not just that it failed.
        assertThat(result).contains("downloadPaper");
    }

    @Test
    void rejectsAnEmptyName() {
        assertThat(tool.extractPaperText(TOPIC, "  ", null, null)).startsWith("Error:");
        assertThat(tool.extractPaperText(TOPIC, null, null, null)).startsWith("Error:");
    }

    @Test
    void reportsAnUnparseableFileRatherThanThrowing() throws IOException {
        Path dir = WorkspacePaths.papersDir(TOPIC);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Corrupt.pdf"), "this is not a PDF");

        String result = tool.extractPaperText(TOPIC, "Corrupt", null, null);

        // A friendly sentence, not a leaked exception type or stack trace.
        assertThat(result).startsWith("Error: could not parse PDF");
        assertThat(result).doesNotContain("\tat ");
    }

    @Test
    void cannotReadOutsideTheTopicsPapersDirectory() throws IOException {
        // A sibling topic's paper must not be reachable through a relative path.
        Path otherDir = WorkspacePaths.papersDir("some-other-topic");
        Files.createDirectories(otherDir);
        writePdf("Mine", 1);

        String result = tool.extractPaperText(TOPIC,
                "investigation/some-other-topic/papers/Mine.pdf", null, null);

        assertThat(result).startsWith("Error:");
        assertThat(result).contains("must be inside");
    }

    @Test
    void traversalAttemptsAreRejected() throws IOException {
        writePdf("Target", 1);

        for (String hostile : new String[]{
                "../Target", "../../Target", "investigation/../../etc/passwd"}) {
            String result = tool.extractPaperText(TOPIC, hostile, null, null);
            assertThat(result).as("hostile name %s", hostile).startsWith("Error:");
        }
    }

    @Test
    void hostileTopicStaysInsideInvestigation() {
        // A traversal-shaped topic sanitises to a single segment. Nothing is read
        // (the paper does not exist), and the reported path never climbs out.
        String result = tool.extractPaperText("../../evil", "anything", null, null);

        assertThat(result).startsWith("Error:");
        assertThat(result).contains("investigation/").doesNotContain("..");
    }

    @Test
    void listsWhatTheTopicHas() throws IOException {
        writePdf("Alpha", 2);
        writePdf("Beta", 1);

        String listing = tool.listDownloadedPapers(TOPIC);

        assertThat(listing).contains("Alpha.pdf").contains("Beta.pdf").contains("(2)");
    }

    @Test
    void listingAnUnusedTopicIsReadableNotAnError() {
        String listing = tool.listDownloadedPapers("a-topic-with-nothing");

        assertThat(listing).doesNotStartWith("Error");
        assertThat(listing).contains("No papers downloaded yet");
    }
}

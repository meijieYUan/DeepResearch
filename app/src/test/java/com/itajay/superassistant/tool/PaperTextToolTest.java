package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    // ── page-text cache ──

    /** A tool with the cache off — the reference implementation for byte-equality checks. */
    private PaperTextTool uncachedTool() {
        PaperDownloadProperties props = new PaperDownloadProperties();
        props.setTextCacheMaxDocs(0);
        return new PaperTextTool(props);
    }

    /** Like writePdf, but each page carries a distinct line of text so join fidelity is exercised. */
    private Path writeTextPdf(String name, int pages) throws IOException {
        Path dir = WorkspacePaths.papersDir(TOPIC);
        Files.createDirectories(dir);
        Path pdf = dir.resolve(name + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Paper text on page " + i);
                    cs.endText();
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void repeatedSameRangeIsServedFromCache() throws IOException {
        writeTextPdf("Cached", 3);

        String first = tool.extractPaperText(TOPIC, "Cached", 1, 3);
        String second = tool.extractPaperText(TOPIC, "Cached", 1, 3);

        assertThat(first).contains("Pages: 1-3 of 3");
        assertThat(second).isEqualTo(first);
        // The second call was served entirely from the page cache: the document was opened once.
        assertThat(tool.pdfOpensForTesting()).isEqualTo(1);
    }

    @Test
    void overlappingSubsetHitsCacheAfterFullRead() throws IOException {
        writeTextPdf("Overlap", 4);
        PaperTextTool reference = uncachedTool();

        String full = tool.extractPaperText(TOPIC, "Overlap", 1, 4);
        String mid = tool.extractPaperText(TOPIC, "Overlap", 2, 3);
        String head = tool.extractPaperText(TOPIC, "Overlap", 1, 2);

        // Subsets of a verified document are cache hits and must stay byte-identical
        // to what a parse would have produced.
        assertThat(full).isEqualTo(reference.extractPaperText(TOPIC, "Overlap", 1, 4));
        assertThat(mid).isEqualTo(reference.extractPaperText(TOPIC, "Overlap", 2, 3));
        assertThat(head).isEqualTo(reference.extractPaperText(TOPIC, "Overlap", 1, 2));
        assertThat(tool.pdfOpensForTesting()).isEqualTo(1);
    }

    @Test
    void partiallyCachedWindowStillOpensAndStaysCorrect() throws IOException {
        writeTextPdf("Partial", 3);
        PaperTextTool reference = uncachedTool();

        tool.extractPaperText(TOPIC, "Partial", 1, 1);
        String widened = tool.extractPaperText(TOPIC, "Partial", 1, 3);
        String again = tool.extractPaperText(TOPIC, "Partial", 1, 3);

        assertThat(widened).isEqualTo(reference.extractPaperText(TOPIC, "Partial", 1, 3));
        // The widened window was a miss (it opened the document), the repeat a hit.
        assertThat(again).isEqualTo(widened);
        assertThat(tool.pdfOpensForTesting()).isEqualTo(2);
    }

    @Test
    void joinedPageTextMatchesRangeStrip() throws IOException {
        // The join-fidelity guard: a tool that read the paper page by page and then
        // asks for the whole range must get exactly what a single range strip yields.
        writeTextPdf("Join", 3);
        PaperTextTool whole = uncachedTool();

        String rangeStripped = whole.extractPaperText(TOPIC, "Join", 1, 3);

        // The two-page window is what measures the join separator (a single-page
        // window cannot discriminate the candidates); the next call harvests page 3.
        tool.extractPaperText(TOPIC, "Join", 1, 2);
        tool.extractPaperText(TOPIC, "Join", 3, 3);
        String joined = tool.extractPaperText(TOPIC, "Join", 1, 3);

        assertThat(joined).isEqualTo(rangeStripped);
        // Two misses happened; the final whole-range call must have been a cache join.
        assertThat(tool.pdfOpensForTesting()).isEqualTo(2);
    }

    @Test
    void replacedPdfIsNotServedStale() throws IOException {
        Path pdf = writePdf("Replaced", 2);
        tool.extractPaperText(TOPIC, "Replaced", 1, 2);

        // Overwrite with a different paper. lastModified is forced forward because
        // same-second mtime granularity would otherwise make the new key collide.
        writePdf("Replaced", 4);
        Files.setLastModifiedTime(pdf, FileTime.from(Instant.now().plusSeconds(10)));

        String result = tool.extractPaperText(TOPIC, "Replaced", 1, 2);

        assertThat(result).contains("Pages: 1-2 of 4");
    }

    @Test
    void cacheCanBeDisabledByProperty() throws IOException {
        writeTextPdf("Nocache", 2);
        PaperTextTool disabled = uncachedTool();

        String first = disabled.extractPaperText(TOPIC, "Nocache", 1, 2);
        String second = disabled.extractPaperText(TOPIC, "Nocache", 1, 2);

        assertThat(second).isEqualTo(first);
        assertThat(disabled.pdfOpensForTesting()).isEqualTo(2);
    }

    @Test
    void cacheSurvivesConcurrentReads() throws Exception {
        writeTextPdf("Concurrent", 6);
        PaperTextTool reference = uncachedTool();
        int[][] windows = {{1, 6}, {1, 3}, {2, 5}, {3, 6}, {2, 2}, {4, 4}, {1, 1}, {3, 4}};

        ExecutorService pool = Executors.newFixedThreadPool(windows.length);
        try {
            CountDownLatch ready = new CountDownLatch(windows.length);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int[] window : windows) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return tool.extractPaperText(TOPIC, "Concurrent", window[0], window[1]);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            for (int i = 0; i < windows.length; i++) {
                String expected = reference.extractPaperText(TOPIC, "Concurrent", windows[i][0], windows[i][1]);
                assertThat(futures.get(i).get(30, TimeUnit.SECONDS))
                        .as("window %d-%d", windows[i][0], windows[i][1])
                        .isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}

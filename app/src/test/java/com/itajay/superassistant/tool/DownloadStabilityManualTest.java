package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The download chain against live hosts. Needs network access and a contact email, so it only
 * runs with {@code -Dpaper.it=true -Dunpaywall.email=...}.
 *
 * <p>Always on a library of its own: a cold start every time, and the project's real library
 * is never read or written.</p>
 */
class DownloadStabilityManualTest {

    private static final String TOPIC = "unit-test-download-live";
    private static final String OTHER = "unit-test-download-live-other";

    private static final String ATTENTION = "Attention Is All You Need";
    private static final String RESNET = "Deep Residual Learning for Image Recognition";
    private static final String BERT =
            "BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding";

    /** One scenario per source the chain can take. */
    record Case(String name, String url, String doi, String arxivId, String title, Integer year,
                boolean expectSuccess) {
    }

    private static final List<Case> CASES = List.of(
            new Case("arxiv-abs-url", "https://arxiv.org/abs/1810.04805", null, null, BERT, 2019, true),
            new Case("arxiv-bare-id", null, null, "2010.11929",
                    "An Image is Worth 16x16 Words: Transformers for Image Recognition at Scale", 2021, true),
            new Case("unpaywall-green-doi", null, "10.1109/TPAMI.2016.2577031", null,
                    "Faster R-CNN: Towards Real-Time Object Detection with Region Proposal Networks", 2015, true),
            new Case("landing-page", "https://openaccess.thecvf.com/content/ICCV2023/html/Zhang_Adding_Conditional_Control_to_Text-to-Image_Diffusion_Models_ICCV_2023_paper.html",
                    null, null, null, null, true),
            // Expected failures, handled cleanly: closed access; arXiv-only title not in Crossref.
            new Case("closed-doi", null, "10.1145/3292500.3330701", null,
                    "Optuna: A Next-generation Hyperparameter Optimization Framework", 2019, false),
            new Case("title-not-in-crossref", null, null, null, "Segment Anything", 2023, false));

    @TempDir
    Path libraryRoot;

    private PaperDownloadTool tool;

    @BeforeEach
    void setUp() {
        String email = System.getProperty("unpaywall.email", "");
        Assumptions.assumeTrue(Boolean.getBoolean("paper.it") && !email.isBlank(),
                "set -Dpaper.it=true -Dunpaywall.email=you@example.com to run");
        PaperDownloadProperties props = new PaperDownloadProperties();
        props.setUnpaywallEmail(email);
        props.setMaxRetries(2);
        props.setUnpaywallTimeoutMs(15_000);
        tool = new PaperDownloadTool(props, new ProgressChannelRegistry(),
                new PaperLibrary(props, libraryRoot), null);
    }

    @AfterEach
    void cleanUp() throws IOException {
        for (String topic : List.of(TOPIC, OTHER)) {
            Path dir = WorkspacePaths.topicDir(topic);
            if (Files.isDirectory(dir)) {
                try (var paths = Files.walk(dir)) {
                    for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
    }

    @Test
    void everySourcePathHoldsUp() {
        for (Case c : CASES) {
            String result = timed(c.name(), () ->
                    tool.downloadPaper(TOPIC, c.url(), c.name(), c.doi(), c.arxivId(), c.title(), c.year(), null));
            assertThat(result).as(c.name()).startsWith(c.expectSuccess() ? "OK:" : "DOWNLOAD_FAILED:");
        }
        // A second topic is served from the library, under any name.
        assertThat(timed("library reuse", () ->
                tool.downloadPaper(OTHER, null, "another-name", null, "1810.04805", BERT, 2019, null)))
                .contains("copied from local paper library");
    }

    /** The defect this guards against, with the very papers it was found with. */
    @Test
    void oneShortNameForTwoPapersYieldsBothPapers() {
        timed("Attention as arxiv-abs-url", () ->
                tool.downloadPaper(OTHER, null, "arxiv-abs-url", null, "1706.03762", ATTENTION, 2017, null));
        String other = timed("ResNet as arxiv-abs-url, other topic", () ->
                tool.downloadPaper(TOPIC, null, "arxiv-abs-url", null, "1512.03385", RESNET, 2016, null));
        String same = timed("ResNet as arxiv-abs-url, same topic", () ->
                tool.downloadPaper(OTHER, null, "arxiv-abs-url", null, "1512.03385", RESNET, 2016, null));

        assertThat(other).startsWith("OK: downloaded");
        assertThat(stampOf(OTHER, "arxiv-abs-url")).isEqualTo("1706.03762");
        assertThat(stampOf(TOPIC, "arxiv-abs-url")).isEqualTo("1512.03385");
        assertThat(same).contains("copied from local paper library", "shortName: arxiv-abs-url-1512.03385");
        assertThat(stampOf(OTHER, "arxiv-abs-url-1512.03385")).isEqualTo("1512.03385");
    }

    /** The right title with another paper's id, the usual shape of a made-up identifier. */
    @Test
    void aWrongArxivIdIsReportedInsteadOfSavingTheOtherPaper() {
        String result = timed("ResNet's title, Attention's id", () ->
                tool.downloadPaper(TOPIC, null, "ResNet", null, "1706.03762", RESNET, 2016, null));

        assertThat(result).startsWith("DOWNLOAD_FAILED").contains("this is a different paper");
        assertThat(PaperStore.listPdfFiles(TOPIC)).isEmpty();
    }

    private static String stampOf(String topic, String shortName) {
        return PdfIdentityReader.identityOf(WorkspacePaths.papersDir(topic).resolve(shortName + ".pdf"))
                .orElseThrow().arxivId();
    }

    private static String timed(String what, java.util.function.Supplier<String> call) {
        long start = System.nanoTime();
        String result = call.get();
        System.out.printf("[live] %-38s %6d ms  %s%n", what, (System.nanoTime() - start) / 1_000_000,
                result.lines().findFirst().orElse(""));
        return result;
    }
}

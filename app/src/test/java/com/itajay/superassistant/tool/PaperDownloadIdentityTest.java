package com.itajay.superassistant.tool;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole download, with only the network replaced: whatever name is asked for, the paper
 * that arrives is the paper that was asked for — or the call says it failed.
 */
class PaperDownloadIdentityTest {

    private static final String TOPIC = "unit-test-download-identity";
    private static final String OTHER = "unit-test-download-identity-other";
    private static final String THREAD = "identity-thread";
    private static final String ATTENTION = "Attention Is All You Need";
    private static final String RESNET = "Deep Residual Learning for Image Recognition";

    @TempDir
    Path libraryRoot;

    @TempDir
    Path web;

    private final Map<String, Path> served = new HashMap<>();
    private final List<String> fetched = new ArrayList<>();
    private final DownloadFailureRegistry failures = new DownloadFailureRegistry();
    private PaperLibrary library;
    private PaperDownloadTool tool;

    @BeforeEach
    void setUp() throws IOException {
        serve("https://arxiv.org/pdf/1706.03762", "1706.03762", ATTENTION);
        serve("https://arxiv.org/pdf/1512.03385", "1512.03385", RESNET);
        library = new PaperLibrary(new PaperDownloadProperties(), libraryRoot);
        tool = new PaperDownloadTool(new PaperDownloadProperties(), new ProgressChannelRegistry(), library, failures) {
            @Override
            FetchOutcome fetch(String url, List<String> problems) {
                fetched.add(url);
                Path file = served.get(url);
                if (file == null) {
                    problems.add(url + ": HTTP 404");
                    return null;
                }
                try {
                    return new FetchOutcome(url, Files.readAllBytes(file), null, 200);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
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

    /** Every call gets a file of its own, also when it replaces what a URL served before. */
    private void serve(String url, String stamp, String title) throws IOException {
        served.put(url, TestPdfs.paper(Files.createTempFile(web, "served-", ".pdf"), stamp, title));
    }

    private String download(String topic, String shortName, String arxivId, String title) {
        return download(topic, shortName, null, arxivId, title);
    }

    private String download(String topic, String shortName, String doi, String arxivId, String title) {
        return tool.downloadPaper(topic, null, shortName, doi, arxivId, title, null,
                new ToolContext(Map.of("_AGENT_CONFIG_", RunnableConfig.builder().threadId(THREAD).build())));
    }

    private static String stampOf(String topic, String shortName) {
        return PdfIdentityReader.identityOf(WorkspacePaths.papersDir(topic).resolve(shortName + ".pdf"))
                .orElseThrow().arxivId();
    }

    private List<String> libraryFiles() throws IOException {
        Path papers = libraryRoot.resolve("papers");
        if (!Files.isDirectory(papers)) {
            return List.of();
        }
        try (var stream = Files.list(papers)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ── the reported defect ───────────────────────────────────────────────

    /** Exactly what went wrong: asked for ResNet, got Attention's PDF and an OK. */
    @Test
    void anotherPaperUnderATakenNameIsDownloadedNotCopied() throws IOException {
        download(OTHER, "arxiv-abs-url", "1706.03762", ATTENTION);

        String result = download(TOPIC, "arxiv-abs-url", "1512.03385", RESNET);

        assertThat(result).startsWith("OK: downloaded");
        assertThat(stampOf(TOPIC, "arxiv-abs-url")).isEqualTo("1512.03385");
        assertThat(stampOf(OTHER, "arxiv-abs-url")).isEqualTo("1706.03762");
        assertThat(libraryFiles()).containsExactly("arxiv-abs-url-1512.03385.pdf", "arxiv-abs-url.pdf");
    }

    @Test
    void inOneTopicTheSecondPaperGetsItsOwnNameAndSaysSo() {
        download(TOPIC, "Survey", "1706.03762", ATTENTION);

        String result = download(TOPIC, "Survey", "1512.03385", RESNET);

        assertThat(result).startsWith("OK:").contains("shortName: Survey-1512.03385");
        assertThat(stampOf(TOPIC, "Survey")).isEqualTo("1706.03762");
        assertThat(stampOf(TOPIC, "Survey-1512.03385")).isEqualTo("1512.03385");
        // Asking again finds it by what it is.
        assertThat(download(TOPIC, "Survey", "1512.03385", RESNET))
                .contains("already downloaded", "shortName: Survey-1512.03385");
    }

    // ── reuse still works, and no longer depends on the name ──────────────

    @Test
    void theSamePaperUnderAnotherNameComesFromTheLibrary() {
        download(OTHER, "First", "1706.03762", ATTENTION);
        fetched.clear();

        assertThat(download(TOPIC, "Second", "1706.03762", ATTENTION)).contains("copied from local paper library");
        assertThat(fetched).isEmpty();
        assertThat(stampOf(TOPIC, "Second")).isEqualTo("1706.03762");
    }

    @Test
    void theSamePaperAskedForTwiceInOneTopicKeepsItsFirstName() {
        download(TOPIC, "First", "1706.03762", ATTENTION);
        fetched.clear();

        assertThat(download(TOPIC, "Second", null, ATTENTION)).contains("already downloaded", "shortName: First");
        assertThat(fetched).isEmpty();
    }

    @Test
    void aPdfAHumanPutInTheLibraryIsUsedWhateverItIsCalled() throws IOException {
        TestPdfs.paper(libraryRoot.resolve("papers/download (3).pdf"), "1512.03385", RESNET);

        assertThat(download(TOPIC, "ResNet", "1512.03385", RESNET)).contains("copied from local paper library");
        assertThat(fetched).isEmpty();
    }

    // ── wrong papers that arrive over the network ─────────────────────────

    /** The right title with another paper's arXiv id: the usual shape of a made-up identifier. */
    @Test
    void aWrongIdentifierIsReportedInsteadOfSavingTheOtherPaper() throws IOException {
        String result = download(TOPIC, "ResNet", "1706.03762", RESNET);

        assertThat(result).startsWith("DOWNLOAD_FAILED").contains("this is a different paper", ATTENTION,
                "do not belong");
        assertThat(PaperStore.listPdfFiles(TOPIC)).isEmpty();
        assertThat(libraryFiles()).isEmpty();
        assertThat(failures.peek(THREAD, TOPIC).items()).hasSize(1);
    }

    @Test
    void aWrongIdentifierIsNotAnsweredFromTheLibraryEither() {
        download(OTHER, "Attention", "1706.03762", ATTENTION);

        assertThat(download(TOPIC, "ResNet", "1706.03762", RESNET)).startsWith("DOWNLOAD_FAILED");
        assertThat(PaperStore.listPdfFiles(TOPIC)).isEmpty();
    }

    @Test
    void whenOneSourceHasTheWrongPaperTheNextOneIsTried() throws IOException {
        serve("https://arxiv.org/pdf/1512.03385", "1706.03762", ATTENTION);
        serve("https://export.arxiv.org/pdf/1512.03385", "1512.03385", RESNET);

        assertThat(download(TOPIC, "ResNet", "1512.03385", RESNET)).startsWith("OK: downloaded").contains("export.arxiv.org");
        assertThat(stampOf(TOPIC, "ResNet")).isEqualTo("1512.03385");
    }

    /** A claim is not knowledge: the file came by its arXiv id, the DOI was only asserted. */
    @Test
    void aDoiThatWasOnlyClaimedIsNotRememberedForTheFile() {
        download(TOPIC, "Attention", "10.1109/cvpr.2016.90", "1706.03762", ATTENTION);

        assertThat(library.find(null, new PaperIdentity("1706.03762", null, null, null))).isPresent();
        assertThat(library.find(null, new PaperIdentity(null, "10.1109/cvpr.2016.90", null, null))).isEmpty();
    }

    // ── what is still missing stays missing ───────────────────────────────

    @Test
    void anotherPaperUnderTheSameNameDoesNotSettleAFailure() throws IOException {
        served.remove("https://arxiv.org/pdf/1512.03385");
        download(TOPIC, "Survey", "1512.03385", RESNET);
        download(TOPIC, "Survey", "1706.03762", ATTENTION);
        assertThat(failures.peek(THREAD, TOPIC).items()).singleElement()
                .satisfies(f -> assertThat(f.title()).isEqualTo(RESNET));

        serve("https://arxiv.org/pdf/1512.03385", "1512.03385", RESNET);
        download(TOPIC, "ResNet", "1512.03385", RESNET);
        assertThat(failures.peek(THREAD, TOPIC).items()).isEmpty();
    }
}

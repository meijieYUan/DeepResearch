package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
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
 * The shared paper library: a file name is a label, and only a paper's identity is a hit.
 *
 * <p>Always on a library of its own. An earlier version cleaned up the project's real
 * {@code paper-library/} after every test and deleted the user's papers with it.</p>
 */
class PaperLibraryTest {

    private static final String TOPIC = "unit-test-paper-library";
    private static final PaperIdentity ATTENTION =
            new PaperIdentity("1706.03762", null, "Attention Is All You Need", null);
    private static final PaperIdentity RESNET =
            new PaperIdentity("1512.03385", null, "Deep Residual Learning for Image Recognition", null);
    private static final PaperIdentity OPTUNA = new PaperIdentity(null, "10.1145/3292500.3330701",
            "Optuna: A Next-generation Hyperparameter Optimization Framework", null);

    @TempDir
    Path root;

    @TempDir
    Path downloads;

    private PaperLibrary library;

    @BeforeEach
    void setUp() {
        library = reopen();
    }

    @AfterEach
    void cleanUp() throws IOException {
        Path topic = WorkspacePaths.topicDir(TOPIC);
        if (Files.isDirectory(topic)) {
            try (var paths = Files.walk(topic)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private PaperLibrary reopen() {
        return new PaperLibrary(new PaperDownloadProperties(), root);
    }

    private Path attention() throws IOException {
        return TestPdfs.paper(downloads.resolve("attention.pdf"), "1706.03762", "Attention Is All You Need");
    }

    private Path resnet() throws IOException {
        return TestPdfs.paper(downloads.resolve("resnet.pdf"), "1512.03385",
                "Deep Residual Learning for Image Recognition");
    }

    private Path inLibrary(String fileName) {
        return root.resolve("papers").resolve(fileName);
    }

    private List<String> files() throws IOException {
        try (var stream = Files.list(root.resolve("papers"))) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ── the reported defect ───────────────────────────────────────────────

    @Test
    void theSameNameIsNotAHitForAnotherPaper() throws IOException {
        library.store(attention(), "arxiv-abs-url", ATTENTION);

        assertThat(library.find("arxiv-abs-url", RESNET)).isEmpty();
        assertThat(library.importInto("arxiv-abs-url", TOPIC, RESNET)).isEmpty();
        assertThat(library.find("arxiv-abs-url", ATTENTION)).isPresent();
    }

    /** Storing used to replace the file, so the first paper was gone and its entry lied. */
    @Test
    void storingAnotherPaperUnderATakenNameKeepsBoth() throws IOException {
        library.store(attention(), "Survey", ATTENTION);
        byte[] before = Files.readAllBytes(inLibrary("Survey.pdf"));
        library.store(resnet(), "Survey", RESNET);

        assertThat(files()).containsExactly("Survey-1512.03385.pdf", "Survey.pdf");
        assertThat(Files.readAllBytes(inLibrary("Survey.pdf"))).isEqualTo(before);
        assertThat(library.find("Survey", ATTENTION)).hasValue(inLibrary("Survey.pdf"));
        assertThat(library.find("Survey", RESNET)).hasValue(inLibrary("Survey-1512.03385.pdf"));
    }

    @Test
    void aPaperIsFoundWhateverItsFileIsCalled() throws IOException {
        library.store(attention(), "Survey", ATTENTION);

        assertThat(library.find("Completely-Different", new PaperIdentity("1706.03762", null, null, null))).isPresent();
        assertThat(library.find(null, new PaperIdentity(null, null, "ATTENTION is all you need", null))).isPresent();
    }

    @Test
    void theSamePaperIsKeptOnce() throws IOException {
        library.store(attention(), "First", ATTENTION);
        library.store(attention(), "Second", ATTENTION);

        assertThat(files()).containsExactly("First.pdf");
    }

    /** A model that pairs the right title with another paper's id must not get that other paper. */
    @Test
    void aWrongIdentifierDoesNotLeadToAnotherPaper() throws IOException {
        library.store(attention(), "Survey", ATTENTION);

        assertThat(library.find("Survey", new PaperIdentity("1706.03762", null, RESNET.title(), null))).isEmpty();
    }

    // ── papers a human put there ──────────────────────────────────────────

    @Test
    void aHandDroppedFileIsFoundByWhatItsPageSays() throws IOException {
        TestPdfs.paper(inLibrary("1706.03762v7 (1).pdf"), "1706.03762", "Provided proper attribution is provided",
                "Attention Is All You Need");
        TestPdfs.paper(inLibrary("下载.pdf"), null, "This ICCV paper is the Open Access version",
                "Deep Residual Learning for", "Image Recognition");

        assertThat(library.find("x", ATTENTION)).hasValue(inLibrary("1706.03762v7 (1).pdf"));
        assertThat(library.find("x", RESNET)).hasValue(inLibrary("下载.pdf"));
    }

    @Test
    void aScanIsFoundByTheNameItWasGiven() throws IOException {
        TestPdfs.blank(inLibrary("Optuna-10.1145-3292500.3330701.pdf"));
        TestPdfs.page(inLibrary("ResNet.pdf"), "scan");

        assertThat(library.find("Optuna", OPTUNA)).hasValue(inLibrary("Optuna-10.1145-3292500.3330701.pdf"));
        // Nothing on the page contradicts the name, and a human chose it.
        assertThat(library.find("ResNet", RESNET)).hasValue(inLibrary("ResNet.pdf"));
        assertThat(library.find("Survey", ATTENTION)).isEmpty();
    }

    // ── what a file was fetched by ────────────────────────────────────────

    @Test
    void theDoiAPaperWasFetchedByIsRememberedAcrossRestartsAndRenames() throws IOException {
        Path pdf = TestPdfs.paper(downloads.resolve("o.pdf"), null, "Optuna: A Next-generation Hyperparameter",
                "Optimization Framework");
        library.store(pdf, "Optuna", OPTUNA);
        Files.move(inLibrary("Optuna.pdf"), inLibrary("renamed.pdf"));

        assertThat(reopen().find(null, new PaperIdentity(null, "10.1145/3292500.3330701", null, null)))
                .hasValue(inLibrary("renamed.pdf"));
    }

    @Test
    void aReplacedFileDoesNotInheritTheOldPapersIdentity() throws IOException {
        library.store(TestPdfs.paper(downloads.resolve("o.pdf"), null, "Optuna: A Next-generation Hyperparameter",
                "Optimization Framework"), "Optuna", OPTUNA);
        Files.copy(attention(), inLibrary("Optuna.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThat(library.find("Optuna", OPTUNA)).isEmpty();
        assertThat(library.find("Optuna", ATTENTION)).hasValue(inLibrary("Optuna.pdf"));
    }

    /** Old rows tied identifiers to file names, so they can describe a paper that was overwritten. */
    @Test
    void anIndexInTheOldLayoutIsIgnored() throws IOException {
        Files.createDirectories(root.resolve("papers"));
        Files.copy(attention(), inLibrary("Survey.pdf"));
        Files.writeString(root.resolve("index.tsv"),
                "# shortName\tarxivId\tdoi\tsourceUrl\ttitle\tfingerprintArxivId\tfingerprintDoi\tscanned\n"
                        + "Survey\t1512.03385\t\thttps://arxiv.org/abs/1512.03385\t\t\t\t1\n");

        assertThat(library.find("Survey", RESNET)).isEmpty();
        assertThat(library.find("Survey", ATTENTION)).isPresent();
    }

    // ── into a topic ──────────────────────────────────────────────────────

    @Test
    void importingCopiesRatherThanMoves() throws IOException {
        library.store(attention(), "Copy-Me", ATTENTION);

        assertThat(library.importInto("My-Name", TOPIC, ATTENTION))
                .hasValue(WorkspacePaths.papersDir(TOPIC).resolve("My-Name.pdf"));
        // The library copy must survive: other topics still need it.
        assertThat(library.find("Copy-Me", ATTENTION)).isPresent();
    }

    @Test
    void importingUnderANameAnotherPaperHasKeepsBoth() throws IOException {
        library.store(attention(), "A", ATTENTION);
        library.store(resnet(), "B", RESNET);
        library.importInto("Survey", TOPIC, ATTENTION);

        assertThat(library.importInto("Survey", TOPIC, RESNET))
                .hasValue(WorkspacePaths.papersDir(TOPIC).resolve("Survey-1512.03385.pdf"));
    }

    @Test
    void importingAPaperTheTopicAlreadyHasReturnsThatFile() throws IOException {
        library.store(attention(), "A", ATTENTION);
        library.importInto("First", TOPIC, ATTENTION);

        assertThat(library.importInto("Second", TOPIC, ATTENTION))
                .hasValue(WorkspacePaths.papersDir(TOPIC).resolve("First.pdf"));
    }

    // ── the rest ──────────────────────────────────────────────────────────

    @Test
    void aFileThatIsNotAPdfIsRefused() throws IOException {
        Path fake = Files.writeString(downloads.resolve("fake.pdf"), "<html>404</html>");

        assertThat(library.store(fake, "Broken", RESNET)).isFalse();
        assertThat(library.find("Broken", RESNET)).isEmpty();
    }

    @Test
    void theInventorySaysWhatEachFileIs() throws IOException {
        library.store(attention(), "Survey", ATTENTION);

        assertThat(library.describe()).contains("Survey", "arXiv 1706.03762", "Attention Is All You Need");
    }

    @Test
    void anEmptyLibraryIsReadable() {
        assertThat(library.describe()).contains("为空");
        assertThat(library.find("Anything", RESNET)).isEmpty();
    }
}

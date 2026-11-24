package com.itajay.superassistant.tool;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the local-file side of paper acquisition: importing a user's PDF into
 * {@code papers/}, and finding a paper another topic already downloaded.
 *
 * <p>Real (one-page) PDFs stand in for papers because both helpers gate on
 * {@link PaperStore#pageCount} — a file PDFBox cannot open is not a paper worth
 * importing or reusing, and the tests must pin that boundary, not bypass it.</p>
 */
class PaperStoreAcquireTest {

    private static final String TOPIC = "unit-test-store-acquire";
    private static final String OTHER_TOPIC = "unit-test-store-acquire-other";
    private static final long MAX_BYTES = 50L * 1024 * 1024;

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
    void importPdfCopiesAValidLocalFileIntoPapers() throws IOException {
        Path source = writeRealPdf(Files.createTempDirectory("unit-test-import-"), "Local-Paper.pdf");

        Path target = importPdf(source, "Local-Paper", MAX_BYTES);

        assertThat(target).exists();
        assertThat(WorkspacePaths.relative(target))
                .isEqualTo("investigation/" + TOPIC + "/papers/Local-Paper.pdf");
    }

    @Test
    void importPdfRefusesAFileThatIsNotAParseablePdf() throws IOException {
        Path dir = Files.createTempDirectory("unit-test-import-");
        Path fake = dir.resolve("fake.pdf");
        Files.writeString(fake, "<html>404</html>");

        assertThatThrownBy(() -> importPdf(fake, "Fake", MAX_BYTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是可解析的 PDF");
    }

    @Test
    void importPdfRefusesAnOversizedFile() throws IOException {
        Path source = writeRealPdf(Files.createTempDirectory("unit-test-import-"), "Big.pdf");

        assertThatThrownBy(() -> importPdf(source, "Big", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大小上限");
    }

    @Test
    void importingTheSameFileAgainChangesNothing() throws IOException {
        Path source = writeRealPdf(Files.createTempDirectory("unit-test-import-"), "Shared.pdf");
        Path target = importPdf(source, "Shared", MAX_BYTES);
        long sizeAfterFirst = Files.size(target);

        Path again = importPdf(source, "Shared", MAX_BYTES);

        assertThat(again).isEqualTo(target);
        assertThat(Files.size(target)).isEqualTo(sizeAfterFirst);
        assertThat(PaperStore.listPdfFiles(TOPIC)).hasSize(1);
    }

    /** The old rule returned whatever already had the name, and called it the imported paper. */
    @Test
    void importingAnotherPaperUnderATakenNameKeepsBoth() throws IOException {
        Path temp = Files.createTempDirectory("unit-test-import-");
        Path first = importPdf(TestPdfs.paper(temp.resolve("a.pdf"), "1706.03762", "Attention Is All You Need"),
                "Survey", MAX_BYTES);
        Path second = importPdf(TestPdfs.paper(temp.resolve("b.pdf"), "1512.03385",
                "Deep Residual Learning for Image Recognition"), "Survey", MAX_BYTES);

        assertThat(second).isNotEqualTo(first);
        assertThat(second.getFileName().toString()).isEqualTo("Survey-1512.03385.pdf");
        assertThat(PdfIdentityReader.identityOf(first).orElseThrow().arxivId()).isEqualTo("1706.03762");
        assertThat(PdfIdentityReader.identityOf(second).orElseThrow().arxivId()).isEqualTo("1512.03385");
    }

    @Test
    void aPaperInAnotherTopicIsFoundByIdentityNotByName() throws IOException {
        TestPdfs.paper(WorkspacePaths.papersDir(OTHER_TOPIC).resolve("unit-test-label.pdf"), "1706.03762",
                "Attention Is All You Need");

        assertThat(PaperStore.findAcrossTopics("unit-test-label",
                new PaperIdentity("1512.03385", null, "Deep Residual Learning for Image Recognition", null), null))
                .noneMatch(p -> p.toString().contains(OTHER_TOPIC));
        assertThat(PaperStore.findAcrossTopics("some-other-label",
                new PaperIdentity("1706.03762", null, null, null), null))
                .anyMatch(p -> p.toString().contains(OTHER_TOPIC));
    }

    @Test
    void aBareNameFindsTheFileThatCarriesIt() throws IOException {
        writeRealPdf(WorkspacePaths.papersDir(OTHER_TOPIC), "Reusable.pdf");

        assertThat(PaperStore.findAcrossTopics("Reusable", PaperIdentity.NONE, null))
                .singleElement()
                .satisfies(p -> assertThat(WorkspacePaths.relative(p))
                        .isEqualTo("investigation/" + OTHER_TOPIC + "/papers/Reusable.pdf"));
    }

    private Path importPdf(Path source, String shortName, long maxBytes) throws IOException {
        PaperIdentity identity = PaperIdentity.NONE.confirmedBy(
                PdfIdentityReader.identityOf(source).orElse(PdfIdentity.NONE));
        return PaperStore.importPdf(source, TOPIC, shortName, identity, maxBytes, null);
    }

    @Test
    void searchingAcrossTopicsIgnoresFilesThatDoNotParse() throws IOException {
        Path dir = WorkspacePaths.papersDir(OTHER_TOPIC);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Broken.pdf"), "not a pdf");

        assertThat(PaperStore.findAcrossTopics("Broken", PaperIdentity.NONE, null)).isEmpty();
    }

    @Test
    void searchingAcrossTopicsReturnsNothingForAnUnknownName() {
        assertThat(PaperStore.findAcrossTopics("Never-Downloaded", PaperIdentity.NONE, null)).isEmpty();
    }
}

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

        Path target = PaperStore.importPdf(source, TOPIC, "Local-Paper", 50L * 1024 * 1024);

        assertThat(target).exists();
        assertThat(WorkspacePaths.relative(target))
                .isEqualTo("investigation/" + TOPIC + "/papers/Local-Paper.pdf");
    }

    @Test
    void importPdfRefusesAFileThatIsNotAParseablePdf() throws IOException {
        Path dir = Files.createTempDirectory("unit-test-import-");
        Path fake = dir.resolve("fake.pdf");
        Files.writeString(fake, "<html>404</html>");

        assertThatThrownBy(() -> PaperStore.importPdf(fake, TOPIC, "Fake", 50L * 1024 * 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是可解析的 PDF");
    }

    @Test
    void importPdfRefusesAnOversizedFile() throws IOException {
        Path source = writeRealPdf(Files.createTempDirectory("unit-test-import-"), "Big.pdf");

        assertThatThrownBy(() -> PaperStore.importPdf(source, TOPIC, "Big", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大小上限");
    }

    @Test
    void importPdfSkipsWhenTheTargetAlreadyParses() throws IOException {
        Path source = writeRealPdf(Files.createTempDirectory("unit-test-import-"), "Shared.pdf");
        Path target = PaperStore.importPdf(source, TOPIC, "Shared", 50L * 1024 * 1024);
        long sizeAfterFirst = Files.size(target);

        // The second import must be a no-op: same dedup stance as downloadPaper.
        Path again = PaperStore.importPdf(source, TOPIC, "Shared", 50L * 1024 * 1024);

        assertThat(again).isEqualTo(target);
        assertThat(Files.size(target)).isEqualTo(sizeAfterFirst);
    }

    @Test
    void findPdfAcrossTopicsLandsOnAParseableCopy() throws IOException {
        writeRealPdf(WorkspacePaths.papersDir(OTHER_TOPIC), "Reusable.pdf");

        assertThat(PaperStore.findPdfAcrossTopics("Reusable"))
                .isPresent()
                .get()
                .satisfies(p -> assertThat(WorkspacePaths.relative(p))
                        .isEqualTo("investigation/" + OTHER_TOPIC + "/papers/Reusable.pdf"));
    }

    @Test
    void findPdfAcrossTopicsIgnoresFilesThatDoNotParse() throws IOException {
        Path dir = WorkspacePaths.papersDir(OTHER_TOPIC);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Broken.pdf"), "not a pdf");

        assertThat(PaperStore.findPdfAcrossTopics("Broken")).isEmpty();
    }

    @Test
    void findPdfAcrossTopicsReturnsNothingForAnUnknownName() {
        assertThat(PaperStore.findPdfAcrossTopics("Never-Downloaded")).isEmpty();
    }
}

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared paper library: what it stores, and when a lookup counts as a hit.
 *
 * <p>Real one-page PDFs stand in for papers because every code path gates on a
 * PDFBox parse — a file that cannot be opened is not a paper, and the tests pin
 * that boundary rather than bypass it.</p>
 */
class PaperLibraryTest {

    private static final String TOPIC = "unit-test-paper-library";

    private final PaperLibrary library = new PaperLibrary(new PaperDownloadProperties());

    @AfterEach
    void cleanUp() throws IOException {
        for (Path dir : new Path[]{WorkspacePaths.topicDir(TOPIC),
                WorkspacePaths.root().resolve(WorkspacePaths.PAPER_LIBRARY_DIR)}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var paths = Files.walk(dir)) {
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

    @Test
    void aStoredPaperIsFoundByName() throws IOException {
        assertThat(library.store(pdfBytes(), "Library-Hit", "https://arxiv.org/abs/2401.00001")).isTrue();

        Optional<Path> found = library.find("Library-Hit", null);

        assertThat(found).isPresent();
        assertThat(found.get().getFileName().toString()).isEqualTo("Library-Hit.pdf");
    }

    @Test
    void aPaperStoredUnderAnotherNameIsFoundByIdentifier() throws IOException {
        library.store(pdfBytes(), "Original-Name", "https://arxiv.org/abs/2401.00002");

        // The same paper referenced later with a different short name still hits:
        // otherwise the library would silently collect duplicates while every run
        // kept downloading it again.
        Optional<Path> found = library.find("Different-Name", "https://arxiv.org/pdf/2401.00002");

        assertThat(found).isPresent();
        assertThat(found.get().getFileName().toString()).isEqualTo("Original-Name.pdf");
    }

    @Test
    void unparseableBytesAreRejected() {
        assertThat(library.store("not a pdf".getBytes(), "Broken", null)).isFalse();
        assertThat(library.find("Broken", null)).isEmpty();
    }

    @Test
    void missingPaperIsNotAHit() {
        assertThat(library.find("Never-Stored", "https://example.com/x.pdf")).isEmpty();
    }

    @Test
    void importingIntoATopicCopiesRatherThanMoves() throws IOException {
        library.store(pdfBytes(), "Copy-Me", null);

        Optional<Path> inTopic = library.importInto("Copy-Me", TOPIC);

        assertThat(inTopic).isPresent();
        assertThat(Files.isRegularFile(inTopic.get())).isTrue();
        // The library copy must survive: other topics still need it.
        assertThat(library.find("Copy-Me", null)).isPresent();
    }

    @Test
    void arxivIdAndDoiAreExtractedFromReferences() {
        assertThat(PaperLibrary.extractArxivId("https://arxiv.org/abs/1706.03762v2"))
                .isEqualTo("1706.03762v2");
        assertThat(PaperLibrary.extractDoi("https://doi.org/10.1145/3292500.3330701."))
                .isEqualTo("10.1145/3292500.3330701");
        assertThat(PaperLibrary.extractDoi("see 10.1000/xyz, then")).isEqualTo("10.1000/xyz");
        assertThat(PaperLibrary.extractArxivId("https://example.com/paper.pdf")).isNull();
    }

    private static byte[] pdfBytes() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}

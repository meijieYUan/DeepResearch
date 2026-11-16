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

    // ──────────────────────────────────────────────────────────────────────
    // Hand-dropped papers: the file name is not the short name
    // ──────────────────────────────────────────────────────────────────────

    /**
     * The case the fallback exists for: a human downloads a paper during a pause and the
     * file lands with the publisher's name. The paper is still findable, because the PDF
     * itself says which paper it is.
     */
    @Test
    void aHandDroppedFileIsFoundByTheDoiPrintedInsideIt() throws IOException {
        givenHandDroppedFile("Publisher-Download.pdf",
                "Attention Is All You Need\n\nhttps://doi.org/10.5555/3295222.3295349\n");

        Optional<Path> found = library.find("Totally-Different-Name",
                "https://doi.org/10.5555/3295222.3295349");

        assertThat(found).isPresent();
        assertThat(found.get().getFileName().toString()).isEqualTo("Publisher-Download.pdf");
    }

    /** The arXiv stamp in a paper's margin is the identifier, even with no reference at all. */
    @Test
    void aHandDroppedFileIsFoundByTheArxivStampWithNoReference() throws IOException {
        givenHandDroppedFile("2401.12345v2.pdf",
                "arXiv:2401.12345v2 [cs.CV] 12 Jan 2024\n\nLayout Control For Multi-Subject Images\n");

        Optional<Path> found = library.find("Some-New-Short-Name", "https://arxiv.org/abs/2401.12345");

        assertThat(found).isPresent();
        assertThat(found.get().getFileName().toString()).isEqualTo("2401.12345v2.pdf");
    }

    /** A version suffix must not defeat the match: the model quotes v1, the file was v3. */
    @Test
    void arxivVersionsAreIgnoredWhenMatching() throws IOException {
        givenHandDroppedFile("Versioned.pdf",
                "arXiv:2401.99999v3 [cs.LG] 3 Mar 2024\n\nA Paper About Versioning\n");

        assertThat(library.find("Other", "https://arxiv.org/abs/2401.99999v1")).isPresent();
    }

    /** No identifier anywhere: the title still identifies the paper. */
    @Test
    void aHandDroppedFileWithNoIdentifierIsFoundByTitle() throws IOException {
        givenHandDroppedFile("scan.pdf",
                "Training-Free Layout Guidance for Multi-Subject Image Generation\n\nAbstract\n");

        Optional<Path> found = library.find("x",
                "Training-Free Layout Guidance for Multi-Subject Image Generation");

        assertThat(found).isPresent();
        assertThat(found.get().getFileName().toString()).isEqualTo("scan.pdf");
    }

    /**
     * A file with nothing identifying in it must not become a wildcard: an empty
     * fingerprint matches nothing, and the miss stays a miss.
     */
    @Test
    void anUnidentifiableFileIsNeverAMatch() throws IOException {
        givenHandDroppedFile("opaque.pdf", "x\n");

        assertThat(library.find("elsewhere", "Some Unrelated Long Title Of A Paper")).isEmpty();
    }

    @Test
    void stampAndTitleParsingHandleAwkwardInput() {
        assertThat(PaperLibrary.arxivIdFromStamp("arXiv:2401.12345v2 [cs.CV]")).isEqualTo("2401.12345v2");
        assertThat(PaperLibrary.arxivIdFromStamp("arXiv:cs.CV/0701001")).isEqualTo("cs.CV/0701001");
        // A URL is not a stamp; the url-shaped pattern must not fire on body text.
        assertThat(PaperLibrary.arxivIdFromStamp("see arxiv.org/abs/2401.1")).isNull();
        assertThat(PaperLibrary.arxivIdFromStamp(null)).isNull();

        // The stamp line and the DOI footer are never the title.
        assertThat(PaperLibrary.titleFromBody(
                "arXiv:2401.12345v1 [cs.CV]\nhttps://doi.org/10.1/2\nReal Title Goes Here Now\n"))
                .isEqualTo("realtitlegoesherenow");

        assertThat(PaperLibrary.normaliseTitle("  A  Title: With, Punctuation! "))
                .isEqualTo("atitlewithpunctuation");
    }

    /** The index is a cache: whatever a scan learned must survive a reload from disk. */
    @Test
    void fingerprintsSurviveAnIndexReload() throws IOException {
        givenHandDroppedFile("Reload-Me.pdf",
                "arXiv:2401.77777v1 [cs.AI]\n\nA Title Worth Remembering Here\n");

        PaperDownloadProperties props = new PaperDownloadProperties();
        PaperLibrary first = new PaperLibrary(props);
        assertThat(first.find("nothing", "https://arxiv.org/abs/2401.77777")).isPresent();

        // A fresh instance reads the index instead of the PDF; the lookup must still hit.
        PaperLibrary reloaded = new PaperLibrary(props);
        assertThat(reloaded.find("nothing", "https://arxiv.org/abs/2401.77777")).isPresent();
        assertThat(reloaded.find("nothing", "A Title Worth Remembering Here")).isPresent();
    }

    /** Writes a real one-page PDF whose text a scanner can read back. */
    private void givenHandDroppedFile(String fileName, String pageText) throws IOException {
        Path dir = WorkspacePaths.paperLibraryDir();
        Files.createDirectories(dir);
        try (PDDocument doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                for (String line : pageText.split("\\R")) {
                    stream.showText(line);
                    stream.newLineAtOffset(0, -20);
                }
                stream.endText();
            }
            doc.save(dir.resolve(fileName).toFile());
        }
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

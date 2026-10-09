package com.itajay.superassistant.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for classifying a paper reference and deriving its short name.
 *
 * <p>These feed {@link PaperDownloadTool#acquirePaper}: a misclassification either
 * downloads something the user already has (source read as URL when it is a file) or
 * refuses to fetch something real (a bare DOI read as a short name). The name
 * derivation is the pairing key for {@code papers/X.pdf} and {@code analysis/X.md},
 * so an inconsistency here splits a paper from its own analysis.</p>
 */
class PaperSourceResolutionTest {

    @Test
    void classifiesAnExistingLocalPdfAsAFile() throws Exception {
        var pdf = java.nio.file.Files.createTempFile("unit-test-source-", ".pdf");
        try {
            assertThat(PaperDownloadTool.classifySource(pdf.toString()))
                    .isEqualTo(PaperDownloadTool.SourceKind.LOCAL_FILE);
        } finally {
            java.nio.file.Files.deleteIfExists(pdf);
        }
    }

    @Test
    void classifiesAWindowsStyleRelativePdfPathAsAFile() throws Exception {
        // Backslashes and no drive letter: the shape a Windows user pastes into chat.
        var dir = java.nio.file.Files.createTempDirectory("unit-test-source-");
        var pdf = dir.resolve("Some-Paper.pdf");
        java.nio.file.Files.writeString(pdf, "%PDF-1.4");
        try {
            assertThat(PaperDownloadTool.classifySource(pdf.toString().replace('/', '\\')))
                    .isEqualTo(PaperDownloadTool.SourceKind.LOCAL_FILE);
        } finally {
            java.nio.file.Files.deleteIfExists(pdf);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    @Test
    void classifiesUrlAndDoiFormsAsFetchable() {
        assertThat(PaperDownloadTool.classifySource("https://arxiv.org/abs/1706.03762"))
                .isEqualTo(PaperDownloadTool.SourceKind.URL);
        assertThat(PaperDownloadTool.classifySource("http://example.com/paper.pdf"))
                .isEqualTo(PaperDownloadTool.SourceKind.URL);
        assertThat(PaperDownloadTool.classifySource("doi:10.1234/foo.bar"))
                .isEqualTo(PaperDownloadTool.SourceKind.URL);
        assertThat(PaperDownloadTool.classifySource("10.1234/foo.bar"))
                .isEqualTo(PaperDownloadTool.SourceKind.URL);
    }

    @Test
    void classifiesAnythingElseAsAShortName() {
        assertThat(PaperDownloadTool.classifySource("MemGPT"))
                .isEqualTo(PaperDownloadTool.SourceKind.SHORT_NAME);
        assertThat(PaperDownloadTool.classifySource("MS-Diffusion.pdf"))
                .isEqualTo(PaperDownloadTool.SourceKind.SHORT_NAME);
    }

    @Test
    void shortNameFromALocalPathIsTheFileNameWithoutExtension() {
        assertThat(PaperDownloadTool.inferShortName(
                "C:/papers/MemGPT.pdf", PaperDownloadTool.SourceKind.LOCAL_FILE, null))
                .isEqualTo("MemGPT");
    }

    @Test
    void shortNameFromAnArxivUrlIsTheArxivId() {
        assertThat(PaperDownloadTool.inferShortName(
                "https://arxiv.org/abs/1706.03762", PaperDownloadTool.SourceKind.URL, null))
                .isEqualTo("1706.03762");
    }

    @Test
    void shortNameFromAPlainUrlIsTheLastPathSegment() {
        assertThat(PaperDownloadTool.inferShortName(
                "https://example.com/files/MS-Diffusion.pdf?download=1",
                PaperDownloadTool.SourceKind.URL, null))
                .isEqualTo("MS-Diffusion");
    }

    @Test
    void explicitShortNameAlwaysWins() {
        assertThat(PaperDownloadTool.inferShortName(
                "https://arxiv.org/abs/1706.03762", PaperDownloadTool.SourceKind.URL, "Attention"))
                .isEqualTo("Attention");
    }

    @Test
    void inferredNamesAreSlugifiedLikeEveryOtherPathSegment() {
        // The slug rules are the same ones the PDF file name gets, so papers/X.pdf and
        // analysis/X.md stay paired even when the source carried odd characters.
        assertThat(PaperDownloadTool.inferShortName(
                "https://example.com/a/b/My Paper (v2).pdf", PaperDownloadTool.SourceKind.URL, null))
                .isEqualTo("My-Paper-v2");
    }

    @Test
    void normalizeUrlExpandsDoiFormsOntoTheResolver() {
        assertThat(PaperDownloadTool.normalizeUrl("doi:10.1234/foo.bar"))
                .isEqualTo("https://doi.org/10.1234/foo.bar");
        assertThat(PaperDownloadTool.normalizeUrl("10.1234/foo.bar"))
                .isEqualTo("https://doi.org/10.1234/foo.bar");
        assertThat(PaperDownloadTool.normalizeUrl("https://arxiv.org/abs/1706.03762"))
                .isEqualTo("https://arxiv.org/abs/1706.03762");
    }
}

package com.itajay.superassistant.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.itajay.superassistant.tool.PdfIdentity.TitleEvidence.CONTRADICTS;
import static com.itajay.superassistant.tool.PdfIdentity.TitleEvidence.EXACT;
import static com.itajay.superassistant.tool.PdfIdentity.TitleEvidence.NONE;
import static com.itajay.superassistant.tool.PdfIdentity.TitleEvidence.PARTIAL;
import static org.assertj.core.api.Assertions.assertThat;

/** What a first page says about which paper it is. Layouts follow real papers measured in 2026-11. */
class PdfIdentityReaderTest {

    @TempDir
    Path dir;

    private PdfIdentity read(String stamp, String... lines) throws IOException {
        return PdfIdentityReader.read(TestPdfs.paper(dir.resolve("p.pdf"), stamp, lines)).orElseThrow();
    }

    /** Plain text extraction read 0 of 12 real stamps: the stamp runs up the margin. */
    @Test
    void theVerticalArxivStampIsRead() throws IOException {
        assertThat(read("1706.03762", "Attention Is All You Need").arxivId()).isEqualTo("1706.03762");
    }

    @Test
    void aCitedArxivIdIsNotThePapersOwn() throws IOException {
        assertThat(read(null, "Some Other Paper About Things", "as shown in arXiv:1706.03762 and others").arxivId())
                .isNull();
    }

    @Test
    void aTitleIsFoundBelowABannerAcrossLinesAndInCapitals() throws IOException {
        PdfIdentity page = read("2010.11929", "Published as a conference paper at ICLR 2021",
                "AN IMAGE IS WORTH 16X16 WORDS:", "TRANSFORMERS FOR IMAGE RECOGNITION AT SCALE", "A. Dosovitskiy");

        assertThat(page.evidenceFor(
                "An Image is Worth 16x16 Words: Transformers for Image Recognition at Scale")).isEqualTo(EXACT);
    }

    /** The title it contains is another paper's. */
    @Test
    void aLongerTitleIsNotExactlyTheOneItContains() throws IOException {
        assertThat(read(null, "Improved Denoising Diffusion Probabilistic Models", "Alex Nichol")
                .evidenceFor("Denoising Diffusion Probabilistic Models")).isNotEqualTo(EXACT);
    }

    @Test
    void sloppyFormsOfTheRightTitleAreVariants() throws IOException {
        PdfIdentity page = read("1512.03385", "Deep Residual Learning for Image Recognition", "Kaiming He");

        assertThat(page.evidenceFor("ResNet: Deep Residual Learning for Image Recognition")).isEqualTo(PARTIAL);
        assertThat(page.evidenceFor("Deep Residual Learning for Image Recognition, CVPR 2016")).isEqualTo(PARTIAL);
    }

    /** Papers of one field share most of their words; sharing words is not being the same paper. */
    @Test
    void aNeighbourInTheSameFieldContradicts() throws IOException {
        PdfIdentity page = read("2112.10752", "High-Resolution Image Synthesis with Latent Diffusion Models",
                "Robin Rombach Andreas Blattmann");

        assertThat(page.evidenceFor("Denoising Diffusion Probabilistic Models")).isEqualTo(CONTRADICTS);
        assertThat(page.evidenceFor("Scaling Rectified Flow Transformers for High-Resolution Image Synthesis"))
                .isEqualTo(CONTRADICTS);
    }

    @Test
    void chineseTitlesAreCompared() {
        PdfIdentity page = new PdfIdentity(null, null, "", java.util.List.of("多主体图像生成的布局控制方法综述", "张三 李四"));

        assertThat(page.evidenceFor("多主体图像生成的布局控制方法综述")).isEqualTo(EXACT);
        assertThat(page.evidenceFor("大语言模型智能体的上下文管理研究")).isEqualTo(CONTRADICTS);
        // A translated title can neither confirm nor contradict.
        assertThat(page.evidenceFor("A Survey of Layout Control for Image Generation")).isEqualTo(NONE);
    }

    @Test
    void oneDoiIsThePapersOwnButSeveralAreNot() throws IOException {
        assertThat(read(null, "A Paper With One Identifier", "https://doi.org/10.1101/2020.01.31.929042;").doi())
                .isEqualTo("10.1101/2020.01.31.929042");
        assertThat(read(null, "A Paper Citing Two Others", "10.1145/3292500.3330701 and 10.1038/nature14539").doi())
                .isNull();
    }

    /** Broken font encodings look like some other title while showing none that can be read. */
    @Test
    void textThatIsNotReadableShowsNoTitle() throws IOException {
        PdfIdentity page = PdfIdentityReader.read(TestPdfs.page(dir.resolve("broken.pdf"), "Qwrt plkj hgfd",
                "szxc vbnm qwrt plkj hgfd szxc vbnm", "qwrt plkj hgfd szxc vbnm")).orElseThrow();

        assertThat(page.evidenceFor("Deep Residual Learning for Image Recognition")).isEqualTo(NONE);
        assertThat(page.saysAnything()).isFalse();
    }

    @Test
    void aPageWithoutTextSaysNothing() throws IOException {
        PdfIdentity page = PdfIdentityReader.read(TestPdfs.blank(dir.resolve("scan.pdf"))).orElseThrow();

        assertThat(page.saysAnything()).isFalse();
        assertThat(page.evidenceFor("Deep Residual Learning for Image Recognition")).isEqualTo(NONE);
    }

    @Test
    void aFileThatIsNotAPdfHasNoIdentity() throws IOException {
        Path file = Files.writeString(dir.resolve("fake.pdf"), "<html>404</html>");

        assertThat(PdfIdentityReader.read(file)).isEmpty();
        assertThat(PdfIdentityReader.identityOf(dir.resolve("missing.pdf"))).isEmpty();
    }

    @Test
    void aReplacedFileIsReadAgain() throws IOException {
        Path file = TestPdfs.paper(dir.resolve("same-name.pdf"), "1706.03762", "Attention Is All You Need");
        assertThat(PdfIdentityReader.identityOf(file).orElseThrow().arxivId()).isEqualTo("1706.03762");

        TestPdfs.paper(file, "1512.03385", "Deep Residual Learning for Image Recognition");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertThat(PdfIdentityReader.identityOf(file).orElseThrow().arxivId()).isEqualTo("1512.03385");
    }
}

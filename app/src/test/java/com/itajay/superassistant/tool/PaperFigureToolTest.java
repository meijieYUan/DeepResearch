package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the two figure tools.
 *
 * <p>Uses real generated PDFs rather than stubs, for the same reason
 * {@link PaperTextToolTest} does. Here that matters more than usual: the whole point of these
 * tools is that a figure can be found in a PDF that PDFBox actually has to parse, and the
 * behaviours worth pinning are the ones a mock would define away — that a vector diagram is
 * found at all, that a caption pairs with the figure beside it, and that an image with no
 * caption is left alone.</p>
 *
 * <p><b>The vector case is the one that must not regress.</b> A framework diagram included from
 * LaTeX is a Form XObject, not a bitmap, and the previous implementation skipped every XObject
 * that was not a {@code PDImageXObject}. On the real corpus that silently produced no figure at
 * all for more than half the papers, while still reporting success. The fixture below draws one
 * of each kind on the same page so the two paths are exercised together.</p>
 *
 * <p>Three properties carry the weight. The security one: a model-supplied short name or path can
 * never reach a file outside {@code investigation/{topic}/analysis/figures/} — these files are
 * written by an agent whose tool calls bypass the main agent's approval, so a path escape would
 * let one topic's run overwrite another's work. The design one: every reply carries both
 * relative path forms, because no agent is allowed to assemble one itself. The identity one: a
 * figure's file name carries the number it has in the paper, so nothing downstream has to infer
 * which figure it is from its position in a list — the inference the older design asked the
 * model to make and gave it no way to make.</p>
 */
class PaperFigureToolTest {

    private static final String TOPIC = "unit-test-figure-topic";

    /** Figure size in points, and the distance from its bottom edge to the caption baseline. */
    private static final int FIG_W = 200;
    private static final int FIG_H = 150;
    private static final float CAPTION_DROP = 14f;

    /** Vertical pitch between successive figures on a fixture page. */
    private static final float BAND = 260f;

    private final PaperDownloadProperties props = new PaperDownloadProperties();

    private final PaperFigureTool tool = new PaperFigureTool(props);

    PaperFigureToolTest() {
        props.setFigureDpi(72);   // 1pt = 1px keeps fixtures small and the colour check exact
    }

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

    // ── fixtures ──

    /** A flat-colour image, so the crop can be checked by reading one pixel. */
    private static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    /**
     * One figure on a fixture page: a bitmap or a vector form, with the caption that describes it.
     * {@code label} is the number as it appears in the paper, which is what the tool is asked for.
     */
    private record Item(String label, int rgb, boolean vector, boolean captioned) {
    }

    private static Item bitmap(String label, int rgb) {
        return new Item(label, rgb, false, true);
    }

    private static Item vector(String label, int rgb) {
        return new Item(label, rgb, true, true);
    }

    /** A bitmap with no caption at all — decoration, a logo, a results grid nothing refers to. */
    private static Item uncaptioned(String label, int rgb) {
        return new Item(label, rgb, false, false);
    }

    private Path writePdf(String name, List<List<Item>> pages) throws IOException {
        Path dir = WorkspacePaths.papersDir(TOPIC);
        Files.createDirectories(dir);
        Path pdf = dir.resolve(name + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (List<Item> pageItems : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (pageItems.isEmpty()) {
                    continue;
                }
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    float y = 700 - FIG_H;
                    for (Item item : pageItems) {
                        drawFigure(doc, content, item, y);
                        if (item.captioned()) {
                            drawCaption(content, item.label(), y - CAPTION_DROP);
                        }
                        y -= BAND;
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void drawFigure(PDDocument doc, PDPageContentStream content, Item item, float y)
            throws IOException {
        if (item.vector()) {
            // A Form XObject carries its own coordinate system in /BBox and its own operator
            // stream, so it is placed by translating the CTM — not by being scaled into the unit
            // square the way an image is. That difference is the whole bug this fixture guards:
            // reading a form's box from the unit square collapses it to a one-point speck, and
            // looking only for PDImageXObject misses it entirely. The operator bytes below are
            // not an approximation of the shape a LaTeX \includegraphics{fig.pdf} produces; they
            // are that shape.
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, FIG_W, FIG_H));
            form.setResources(new PDResources());
            String operators = String.format(
                    "%.4f %.4f %.4f rg 0 0 %d %d re f%n",
                    ((item.rgb() >> 16) & 0xFF) / 255f,
                    ((item.rgb() >> 8) & 0xFF) / 255f,
                    (item.rgb() & 0xFF) / 255f,
                    FIG_W, FIG_H);
            try (OutputStream stream = form.getContentStream().createOutputStream()) {
                stream.write(operators.getBytes(StandardCharsets.US_ASCII));
            }
            content.saveGraphicsState();
            content.transform(Matrix.getTranslateInstance(50, y));
            content.drawForm(form);
            content.restoreGraphicsState();
        } else {
            PDImageXObject xobject = LosslessFactory.createFromImage(doc, solid(FIG_W, FIG_H, item.rgb()));
            content.drawImage(xobject, 50, y, FIG_W, FIG_H);
        }
    }

    private static void drawCaption(PDPageContentStream content, String label, float baseline)
            throws IOException {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        content.newLineAtOffset(50, baseline);
        content.showText(label + ": A test caption long enough to be read as prose.");
        content.endText();
    }

    private List<String> figureNames() throws IOException {
        Path dir = WorkspacePaths.figuresDir(TOPIC);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private Path figureFile(String name) {
        return WorkspacePaths.figuresDir(TOPIC).resolve(name);
    }

    /**
     * Asserts the middle of a written PNG is the figure's colour — the figure itself, in the
     * middle of the crop, rather than page background or a neighbour.
     *
     * <p>Compared per channel with a tolerance: a vector fixture's colour round-trips through a
     * content-stream float, so an exact match would be testing PDFBox's decimal formatting.</p>
     */
    private static void assertCentreIs(Path png, int expected) throws IOException {
        BufferedImage image = ImageIO.read(png.toFile());
        assertThat(image).as("readable PNG %s", png).isNotNull();
        int actual = image.getRGB(image.getWidth() / 2, image.getHeight() / 2) & 0xFFFFFF;
        for (int shift : new int[]{16, 8, 0}) {
            int want = (expected >> shift) & 0xFF;
            int got = (actual >> shift) & 0xFF;
            assertThat(Math.abs(got - want))
                    .as("%s channel of %s (actual #%06X, expected #%06X)", shift, png, actual, expected)
                    .isLessThanOrEqualTo(3);
        }
    }

    // ── the figure that must not be missed ──

    @Test
    void extractsAVectorFigure() throws IOException {
        // The regression this whole tool exists for. A vector diagram has no bitmap to lift, so
        // the crop has to come from rendering the page — and an implementation that looks only
        // for embedded images reports "no figure" here and calls it success.
        writePdf("Vector", List.of(List.of(vector("Figure 1", 0xCC2222))));

        String result = tool.extractPaperFigures(TOPIC, "Vector", null, null, null);

        assertThat(result).doesNotStartWith("Error:");
        assertThat(figureNames()).containsExactly("Vector_Fig1.png");
        assertCentreIs(figureFile("Vector_Fig1.png"), 0xCC2222);
    }

    @Test
    void extractsABitmapFigure() throws IOException {
        writePdf("Bitmap", List.of(List.of(bitmap("Figure 1", 0x2255CC))));

        tool.extractPaperFigures(TOPIC, "Bitmap", null, null, null);

        assertThat(figureNames()).containsExactly("Bitmap_Fig1.png");
        assertCentreIs(figureFile("Bitmap_Fig1.png"), 0x2255CC);
    }

    @Test
    void cropsTheFigureNotThePage() throws IOException {
        writePdf("Tight", List.of(List.of(bitmap("Figure 1", 0x00AA44))));

        tool.extractPaperFigures(TOPIC, "Tight", null, null, null);

        BufferedImage image = ImageIO.read(figureFile("Tight_Fig1.png").toFile());
        // The figure is 200x150pt with 4pt of padding all round. Anything much larger means the
        // crop found the page rather than the figure; anything smaller means it clipped one.
        assertThat(image.getWidth()).isBetween(FIG_W + 4, FIG_W + 14);
        assertThat(image.getHeight()).isBetween(FIG_H + 4, FIG_H + 14);
    }

    @Test
    void anImageWithNoCaptionIsNotAFigure() throws IOException {
        // Decoration, and the majority of what a paper's embedded images actually are. There is
        // no caption to name it, so there is nothing to extract it as.
        writePdf("Decorated", List.of(List.of(
                uncaptioned("Logo", 0x777777),
                bitmap("Figure 1", 0xCC2222))));

        String result = tool.extractPaperFigures(TOPIC, "Decorated", null, null, null);

        assertThat(figureNames()).containsExactly("Decorated_Fig1.png");
        assertThat(result).contains("Fig. 1").doesNotContain("Logo");
    }

    // ── the index ──

    @Test
    void listingNamesFiguresAndWritesNothing() throws IOException {
        writePdf("Indexed", List.of(List.of(
                vector("Figure 1", 0xCC2222),
                bitmap("Figure 2", 0x22CC22))));

        String result = tool.listPaperFigures(TOPIC, "Indexed", null, null);

        assertThat(result).contains("Fig. 1").contains("Fig. 2");
        // The caption is the whole point: it is what the model reads to choose.
        assertThat(result).contains("A test caption long enough");
        // Nothing is written — this is the cheap step.
        assertThat(Files.exists(WorkspacePaths.figuresDir(TOPIC))).isFalse();
    }

    @Test
    void listingReportsThePageOfEachFigure() throws IOException {
        writePdf("Paged", List.of(
                List.of(bitmap("Figure 1", 0xCC2222)),
                List.of(bitmap("Figure 2", 0x2222CC))));

        String result = tool.listPaperFigures(TOPIC, "Paged", null, null);

        assertThat(result).contains("| Fig. 1 | 1 |").contains("| Fig. 2 | 2 |");
    }

    // ── asking for a figure by name ──

    @Test
    void extractingByNameWritesOnlyThatFigure() throws IOException {
        writePdf("Chosen", List.of(List.of(
                bitmap("Figure 1", 0xCC2222),
                bitmap("Figure 2", 0x22CC22))));

        tool.extractPaperFigures(TOPIC, "Chosen", "Fig. 2", null, null);

        assertThat(figureNames()).containsExactly("Chosen_Fig2.png");
        assertCentreIs(figureFile("Chosen_Fig2.png"), 0x22CC22);
    }

    @Test
    void acceptsTheNumberHoweverItIsSpelled() throws IOException {
        writePdf("Spelling", List.of(List.of(
                bitmap("Figure 1", 0xCC2222),
                bitmap("Figure 2", 0x22CC22))));

        for (String spelling : List.of("Figure 2", "Fig. 2", "fig 2", "FIG.2")) {
            tool.extractPaperFigures(TOPIC, "Spelling", spelling, null, null);
            assertThat(figureNames())
                    .as("spelling %s", spelling)
                    .containsExactly("Spelling_Fig2.png");
        }
    }

    @Test
    void extractsSeveralNamedFiguresAtOnce() throws IOException {
        writePdf("Pair", List.of(List.of(
                bitmap("Figure 1", 0xCC2222),
                vector("Figure 2", 0x22CC22),
                bitmap("Figure 3", 0x2222CC))));

        String result = tool.extractPaperFigures(TOPIC, "Pair", "Fig. 1, Fig. 3", null, null);

        assertThat(figureNames()).containsExactly("Pair_Fig1.png", "Pair_Fig3.png");
        assertThat(result).contains("Fig. 1").contains("Fig. 3").doesNotContain("Pair_Fig2.png");
    }

    @Test
    void reportsAFigureNumberThatIsNotThere() throws IOException {
        writePdf("Mismatch", List.of(List.of(bitmap("Figure 1", 0xCC2222))));

        String result = tool.extractPaperFigures(TOPIC, "Mismatch", "Fig. 9", null, null);

        assertThat(result).startsWith("Error:");
        assertThat(result).contains("Fig. 9");
        // Names what does exist, so the caller can correct itself without another round trip.
        assertThat(result).contains("Fig. 1");
        assertThat(figureNames()).isEmpty();
    }

    // ── the reply carries both path forms ──

    @Test
    void givesBothReferencePaths() throws IOException {
        writePdf("Sample", List.of(List.of(bitmap("Figure 1", 0x3366CC))));

        String result = tool.extractPaperFigures(TOPIC, "Sample", null, null, null);

        assertThat(result).doesNotStartWith("Error:");
        // The analysis-file form and the document form, both ready to paste. Nothing downstream
        // should ever have to build either of these.
        assertThat(result).contains("figures/Sample_Fig1.png");
        assertThat(result).contains("../analysis/figures/Sample_Fig1.png");
        assertThat(result).contains("investigation/" + TOPIC + "/analysis/figures");
    }

    @Test
    void figurePrefixIsTheSameNameTheAnalysisFileUses() throws IOException {
        writePdf("MS-Diffusion", List.of(List.of(bitmap("Figure 1", 0xAA0000))));

        tool.extractPaperFigures(TOPIC, "MS-Diffusion", null, null, null);

        // This is what makes "every figure belongs to an analysis file" checkable.
        assertThat(figureNames()).containsExactly(AnalysisStore.nameFor("MS-Diffusion") + "_Fig1.png");
    }

    // ── page ranges ──

    @Test
    void scansOnlyTheRequestedPageRange() throws IOException {
        writePdf("Ranged", List.of(
                List.of(bitmap("Figure 1", 0x123456)),
                List.of(bitmap("Figure 2", 0x654321))));

        String result = tool.extractPaperFigures(TOPIC, "Ranged", null, 2, 2);

        assertThat(figureNames()).containsExactly("Ranged_Fig2.png");
        assertThat(result).contains("p.2-2 of 2");
    }

    @Test
    void clampsTheRangeToMaxPagesSoAHugeDocumentCannotRunAway() throws IOException {
        props.setMaxPages(1);
        writePdf("Long", List.of(
                List.of(bitmap("Figure 1", 0x111111)),
                List.of(bitmap("Figure 2", 0x222222))));

        String result = tool.extractPaperFigures(TOPIC, "Long", null, 1, null);

        assertThat(figureNames()).containsExactly("Long_Fig1.png");
        assertThat(result).contains("p.1-1 of 2");
    }

    @Test
    void stopsAtTheConfiguredCapAndSaysSo() throws IOException {
        props.setMaxFigures(2);
        writePdf("Dense", List.of(List.of(
                bitmap("Figure 1", 0x010101),
                bitmap("Figure 2", 0x020202),
                bitmap("Figure 3", 0x030303))));

        String result = tool.extractPaperFigures(TOPIC, "Dense", null, null, null);

        assertThat(figureNames()).hasSize(2);
        assertThat(result).contains("truncated").contains("max-figures=2");
    }

    @Test
    void namesAreNotTruncatedAwayByTheCap() throws IOException {
        // The cap is a backstop for an unnamed sweep. Asking for a figure by number must always
        // return that figure, or the index the caller just read would be unusable.
        props.setMaxFigures(1);
        writePdf("Capped", List.of(List.of(
                bitmap("Figure 1", 0x010101),
                bitmap("Figure 2", 0x020202))));

        tool.extractPaperFigures(TOPIC, "Capped", "Fig. 2", null, null);

        assertThat(figureNames()).containsExactly("Capped_Fig2.png");
    }

    @Test
    void reportsARangeBeyondTheDocument() throws IOException {
        writePdf("Short", List.of(List.of(bitmap("Figure 1", 0x3366CC))));

        String result = tool.extractPaperFigures(TOPIC, "Short", null, 5, null);

        assertThat(result).startsWith("Error:");
        assertThat(result).contains("只有 1 页");
    }

    // ── the honest fallback ──

    @Test
    void saysSoWhenNothingIsExtractableInsteadOfReturningEmpty() throws IOException {
        writePdf("Empty", List.of(List.of()));

        String result = tool.extractPaperFigures(TOPIC, "Empty", null, null, null);

        // Not an error and not a silent no-op: the caller is told what to write instead.
        assertThat(result).startsWith("OK: no extractable figure");
        assertThat(result).contains("图见原文");
        assertThat(result).contains("不引用图片");
        assertThat(figureNames()).isEmpty();
    }

    @Test
    void listingSaysSoTooWhenThereIsNothing() throws IOException {
        writePdf("Empty", List.of(List.of()));

        String result = tool.listPaperFigures(TOPIC, "Empty", null, null);

        assertThat(result).contains("没有找到带图注的图").contains("图见原文");
    }

    @Test
    void nothingExtractedLeavesNoFiguresDirectoryBehind() throws IOException {
        writePdf("Empty", List.of(List.of()));

        tool.extractPaperFigures(TOPIC, "Empty", null, null, null);

        assertThat(Files.exists(WorkspacePaths.figuresDir(TOPIC))).isFalse();
    }

    @Test
    void reRunningOverwritesRatherThanAccumulating() throws IOException {
        writePdf("Again", List.of(List.of(bitmap("Figure 1", 0x3366CC))));

        tool.extractPaperFigures(TOPIC, "Again", null, null, null);
        String second = tool.extractPaperFigures(TOPIC, "Again", null, null, null);

        assertThat(figureNames()).containsExactly("Again_Fig1.png");
        assertThat(second).doesNotStartWith("Error:");
    }

    // ── path safety ──

    @Test
    void reportsAMissingPaperReadably() {
        String result = tool.extractPaperFigures(TOPIC, "NeverDownloaded", null, null, null);

        assertThat(result).startsWith("Error:");
        assertThat(result).contains("PDF not found").contains("downloadPaper");
    }

    @Test
    void rejectsAnEmptyName() {
        assertThat(tool.extractPaperFigures(TOPIC, "  ", null, null, null)).startsWith("Error:");
        assertThat(tool.extractPaperFigures(TOPIC, null, null, null, null)).startsWith("Error:");
        assertThat(tool.listPaperFigures(TOPIC, "  ", null, null)).startsWith("Error:");
    }

    @Test
    void cannotReachAnotherTopicsPaper() throws IOException {
        // A real PDF with exactly this name exists in this topic, so a rejection here
        // cannot be "the file is missing" — it is the cross-topic boundary holding.
        writePdf("Mine", List.of(List.of(bitmap("Figure 1", 0x3366CC))));

        String result = tool.extractPaperFigures(TOPIC,
                "investigation/some-other-topic/papers/Mine.pdf", null, null, null);

        assertThat(result).startsWith("Error:");
        assertThat(result).contains("must be inside");
    }

    @Test
    void traversalAttemptsAreRejected() throws IOException {
        writePdf("Target", List.of(List.of(bitmap("Figure 1", 0x3366CC))));

        for (String hostile : new String[]{
                "../Target", "../../Target", "investigation/../../etc/passwd"}) {
            String result = tool.extractPaperFigures(TOPIC, hostile, null, null, null);
            assertThat(result).as("hostile name %s", hostile).startsWith("Error:");
        }
    }

    @Test
    void hostileTopicStaysInsideInvestigation() {
        String result = tool.extractPaperFigures("../../evil", "anything", null, null, null);

        assertThat(result).startsWith("Error:");
        assertThat(result).contains("investigation/").doesNotContain("..");
    }

    @Test
    void aHostileFigureNumberCannotEscapeTheFiguresDirectory() throws IOException {
        writePdf("Escape", List.of(List.of(bitmap("Figure 1", 0x3366CC))));

        // The number reaches the file name, so it is the one model-supplied value that does.
        for (String hostile : List.of("../../../evil", "Fig. 1/../../x", "..\\..\\evil")) {
            tool.extractPaperFigures(TOPIC, "Escape", hostile, null, null);
            try (Stream<Path> written = Files.isDirectory(WorkspacePaths.figuresDir(TOPIC))
                    ? Files.list(WorkspacePaths.figuresDir(TOPIC)) : Stream.empty()) {
                assertThat(written.map(p -> p.getFileName().toString()))
                        .as("hostile number %s", hostile)
                        .allMatch(n -> n.startsWith("Escape_") && n.endsWith(".png"));
            }
        }
    }

    @Test
    void rejectsAnUnparseableFileRatherThanThrowing() throws IOException {
        Path dir = WorkspacePaths.papersDir(TOPIC);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Corrupt.pdf"), "this is not a PDF");

        String result = tool.extractPaperFigures(TOPIC, "Corrupt", null, null, null);

        assertThat(result).startsWith("Error: could not parse PDF");
        assertThat(result).doesNotContain("\tat ");
    }
}

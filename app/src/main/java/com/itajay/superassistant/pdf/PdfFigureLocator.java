package com.itajay.superassistant.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties;
import org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the figures of a paper PDF and pairs each one with the caption that describes it.
 *
 * <p>This exists because a paper's figures cannot be identified by reading the PDF's
 * embedded images. Two independent reasons:</p>
 *
 * <ul>
 *   <li>A framework diagram included from LaTeX as {@code \includegraphics{fig.pdf}} is a
 *       <em>vector</em> Form XObject, not a bitmap. Anything that looks only for
 *       {@code PDImageXObject} does not merely mis-label that figure — it never sees it.</li>
 *   <li>An embedded image carries no figure number. Which one is "Fig. 2" is only knowable
 *       from the caption, and the caption lives in the text stream, which is a different
 *       layer of the PDF entirely.</li>
 * </ul>
 *
 * <p>The bridge is geometry. Every drawable has a box on the page: an image occupies the
 * unit square mapped by the current transform, a form occupies its own {@code /BBox} mapped
 * by the same transform. Every caption is a text line with a box. In a paper the caption sits
 * immediately beside the thing it describes, so the caption's box and the figure's box are
 * adjacent and share horizontal extent. That is the whole trick — and it is what makes the
 * caption usable as a selector rather than merely as a label.</p>
 *
 * <p>Two constraints keep the association honest, and both are needed:</p>
 *
 * <ul>
 *   <li><b>A caption must have a figure next to it.</b> Requiring only that a line starts with
 *       "Fig. 3" also matches a body sentence that happens to begin there. Requiring a drawable
 *       above the line removes those.</li>
 *   <li><b>A figure must share the caption's horizontal extent.</b> This is what keeps a
 *       two-column paper correct without any column detection: a caption never reaches across
 *       the gutter, so a figure in the other column cannot win the overlap test.</li>
 * </ul>
 *
 * <p>Deliberately dumb about typography. It does not care whether the caption is above or below
 * the figure — it looks in the direction the label implies (tables caption above, figures
 * below) and falls back to the other side. It does not try to understand the caption's meaning;
 * that is the caller's job, and the reason this class returns the caption text rather than a
 * filtered list.</p>
 *
 * <p>Reports only figures it can actually cut. A line that starts "Figure 3 shows …" but has no
 * drawable against it is left out rather than approximated with a box cut from the surrounding
 * whitespace: such a line is a body-text reference far more often than it is a caption whose
 * figure could not be read, and there is no signal that separates the two except the figure
 * itself. A missing figure costs the reader a figure; a confident crop of a paragraph of body
 * text, labelled "Fig. 3" and pasted into a research document, misinforms them.</p>
 *
 * <p>Known limits. Coordinates are read in the page's rotated space, so a page with a non-zero
 * {@code /Rotate} is reported there while {@link #render} compensates. Captions are gathered by
 * proximity, so a caption followed immediately by body text can pick up a trailing fragment of
 * it — harmless for choosing a figure, which is what the caption is for, but the text is not
 * guaranteed to end where the caption does.</p>
 */
public final class PdfFigureLocator {

    /** How a figure is drawn. A framework diagram is usually {@code VECTOR}. */
    public enum Kind { IMAGE, VECTOR }

    /** A drawable placed on a page, in PDF user space (y grows upward from the bottom-left). */
    public record Placed(int page, float x0, float y0, float x1, float y1,
                         Kind kind, int nativeWidth, int nativeHeight, float pageShare) {

        float width() { return x1 - x0; }

        float height() { return y1 - y0; }

        float area() { return width() * height(); }
    }

    /**
     * One stripped text line, in the same space as {@link Placed}.
     *
     * <p>Carries a baseline rather than a box. The top of a line needs the font's ascent, and
     * the ascent a run reports is measured before the transform — inside a figure that
     * transform is a scale, so an ascent read from the font can be twenty times too large.
     * Everything downstream compares baselines instead, which is a quantity no transform can
     * misreport. The cost is that a line's vertical extent is understated by roughly one
     * ascent; the thresholds that consume these compare against figure boxes and are set with
     * room for it.</p>
     */
    public record Line(int page, String text, float x0, float baseline, float x1) {

        float width() { return x1 - x0; }
    }

    /**
     * A figure box paired with the caption that describes it.
     *
     * <p>{@code x0..y1} is in PDF user space, y upward. {@code label} is normalised to
     * {@code "Fig. 3"} or {@code "Table 2"} so callers can match it against a guide's wording;
     * {@code caption} is the caption's full text, which is what a model should read when
     * deciding whether the figure is wanted.</p>
     */
    public record Figure(int page, String label, String caption,
                         float x0, float y0, float x1, float y1,
                         Kind kind, int nativeWidth, int nativeHeight) {

        public float width() { return x1 - x0; }

        public float height() { return y1 - y0; }
    }

    // ── tuning ──

    /** A caption line must start with one of these to be considered at all. */
    private static final Pattern CAPTION_START = Pattern.compile(
            "^(fig(?:ure)?\\.?\\s*(\\d+)|table\\s*([ivx]+|\\d+))\\s*[.:\\u2014-]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** Shorter than this and a "caption" is a stray glyph run, not prose. */
    private static final int MIN_CAPTION_CHARS = 12;

    /**
     * How far the figure's near edge may sit from the caption's near edge. Captions are set
     * tight against their figure; a larger gap means the nearest drawable is something else
     * on the page and pairing it would be wrong.
     */
    private static final float MAX_CAPTION_GAP = 45f;

    /**
     * Fraction of the narrower box's horizontal span that must overlap. Set above a half so a
     * full-width caption in one column cannot pair with a figure in the other.
     */
    private static final float MIN_X_OVERLAP = 0.5f;

    /**
     * A jump across the gutter this wide is a column break. Sized above a generous justified
     * space and below the narrowest real column gap seen in practice; it only has to
     * discriminate inside the gutter, because {@code crossesGutter} also requires the jump to
     * straddle the detected boundary.
     */
    private static final float COLUMN_GAP = 6f;

    /** A clear vertical band narrower than this is a coincidence, not a column gutter. */
    private static final float MIN_GUTTER = 8f;

    /**
     * How far apart two baselines may sit and still be the same line. Well above a subscript's
     * offset and well below the leading of any typeset page, so it separates rows without
     * needing to know anything about the font.
     */
    private static final float BASELINE_TOLERANCE = 4f;

    /**
     * A text run whose vertical extent exceeds this is set sideways rather than along a line.
     * Horizontal runs have an extent of exactly zero, since advancing along x never changes a
     * glyph's y, so any real extent is rotation — with the smallest tilted watermark well clear
     * of this and 90-degree margin stamps far above it.
     */
    private static final float VERTICAL_RUN = 8f;

    /**
     * How far below a caption its next line may start. One line of leading, plus room for a
     * slightly looser block; a paragraph break or the start of body text is past it.
     */
    private static final float CAPTION_CONTINUATION_GAP = 18f;

    /** Drawables closer than this are one figure, not several — a diagram is often many parts. */
    private static final float MERGE_SLACK = 14f;

    /** A form covering more than this share of the page is a content wrapper, not a figure. */
    private static final float WRAPPER_SHARE = 0.8f;

    /** Guards against a form that references itself. */
    private static final int MAX_FORM_DEPTH = 8;

    /** Padding added around a figure box so a hairline border is not clipped. */
    private static final float PADDING = 4f;

    /** Cap on a caption's length; captions are long but not that long. */
    private static final int MAX_CAPTION_CHARS = 700;

    private PdfFigureLocator() {
    }

    /**
     * Every figure in {@code [fromPage, toPage]} (1-based, inclusive) that has a caption,
     * in page order.
     */
    public static List<Figure> locate(PDDocument doc, int fromPage, int toPage) throws IOException {
        int last = Math.min(toPage, doc.getNumberOfPages());
        int first = Math.max(1, fromPage);
        if (first > last) {
            return List.of();
        }

        GraphicsScanner graphics = new GraphicsScanner();
        TextScanner text = new TextScanner();
        text.setStartPage(first);
        text.setEndPage(last);

        for (int page = first; page <= last; page++) {
            PDPage pdPage = doc.getPage(page - 1);
            graphics.page = page;
            graphics.pageArea = area(pdPage.getCropBox());
            graphics.processPage(pdPage);
        }
        text.getText(doc);   // drives writeString per text run, page by page

        List<Line> lines = text.lines();
        List<Figure> figures = new ArrayList<>();
        Set<Placed> claimed = new HashSet<>();
        for (Line captionStart : captionStarts(lines)) {
            Line caption = extendCaption(captionStart, lines);
            Figure figure = pairWithFigure(caption, graphics.placed, claimed, doc);
            if (figure != null) {
                figures.add(figure);
            }
        }
        return figures;
    }

    /**
     * A drawable this large relative to the page is the page, not a figure on it.
     *
     * <p>Two shapes of this exist and both must be rejected: a form wrapping a generator's
     * entire content stream, and the single bitmap of a scanned page. Cropping either one
     * yields the whole page as "Figure 2", which is worse than reporting nothing because it
     * looks like a successful extraction.</p>
     */
    private static boolean isPageSized(Placed box) {
        return box.pageShare() >= WRAPPER_SHARE;
    }

    /**
     * Cuts {@code figure}'s box out of a freshly rendered page.
     *
     * <p>Renders the page rather than the embedded object on purpose: a vector figure has no
     * bitmap to extract, and routing both kinds through one path means the crop is identical
     * for a diagram drawn as vectors and one drawn as a picture.</p>
     */
    public static BufferedImage render(PDDocument doc, Figure figure, float dpi) throws IOException {
        PDPage page = doc.getPage(figure.page() - 1);
        float scale = dpi / 72f;
        BufferedImage full = new PDFRenderer(doc).renderImage(figure.page() - 1, scale, ImageType.RGB);

        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;

        // User space -> the rotated space the renderer draws in. For rotation 0 this is the
        // identity, which is the case for essentially every paper; the other branches mirror
        // the mapping PageDrawer applies before painting.
        float x0 = figure.x0() - box.getLowerLeftX();
        float x1 = figure.x1() - box.getLowerLeftX();
        float y0 = figure.y0() - box.getLowerLeftY();
        float y1 = figure.y1() - box.getLowerLeftY();

        float px0, px1, py0, py1;
        switch (rotation) {
            case 90 -> {
                px0 = y0; px1 = y1;
                py0 = x0; py1 = x1;
            }
            case 180 -> {
                px0 = box.getWidth() - x1; px1 = box.getWidth() - x0;
                py0 = y0; py1 = y1;
            }
            case 270 -> {
                px0 = box.getHeight() - y1; px1 = box.getHeight() - y0;
                py0 = box.getWidth() - x1; py1 = box.getWidth() - x0;
            }
            default -> {
                px0 = x0; px1 = x1;
                py0 = box.getHeight() - y1; py1 = box.getHeight() - y0;
            }
        }

        int ix0 = clamp(Math.round(px0 * scale), 0, full.getWidth());
        int ix1 = clamp(Math.round(px1 * scale), 0, full.getWidth());
        int iy0 = clamp(Math.round(py0 * scale), 0, full.getHeight());
        int iy1 = clamp(Math.round(py1 * scale), 0, full.getHeight());
        if (ix1 - ix0 < 2 || iy1 - iy0 < 2) {
            throw new IOException("figure box collapsed to " + (ix1 - ix0) + "x" + (iy1 - iy0)
                    + "px at " + dpi + " dpi — page " + figure.page() + " " + figure.label());
        }

        // getSubimage shares the parent raster; copy so the page-sized image can be collected.
        BufferedImage crop = full.getSubimage(ix0, iy0, ix1 - ix0, iy1 - iy0);
        BufferedImage out = new BufferedImage(crop.getWidth(), crop.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.drawImage(crop, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Convenience for callers holding a path rather than a document. */
    public static List<Figure> locate(java.nio.file.Path pdf, int fromPage, int toPage) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return locate(doc, fromPage, toPage);
        }
    }

    // ── caption detection ──

    private static List<Line> captionStarts(List<Line> lines) {
        List<Line> starts = new ArrayList<>();
        for (Line line : lines) {
            if (line.text().length() < MIN_CAPTION_CHARS) {
                continue;
            }
            if (CAPTION_START.matcher(line.text()).matches()) {
                starts.add(line);
            }
        }
        return starts;
    }

    /**
     * Appends the caption's continuation lines to its first line.
     *
     * <p>A caption is a paragraph, so its label line is only the start. The continuation is
     * gathered by proximity rather than by looking for a blank line, because the text stripper
     * emits a paper's figure-internal labels as separate scattered "lines" and any rule based
     * on line count would run away. Reading the caption text matters: it is what the caller
     * classifies on.</p>
     */
    private static Line extendCaption(Line start, List<Line> lines) {
        StringBuilder text = new StringBuilder(start.text());
        float x0 = start.x0(), x1 = start.x1();
        float lastBaseline = start.baseline();
        float xScale = Math.max(1f, start.width());

        for (Line candidate : below(start, lines)) {
            if (text.length() >= MAX_CAPTION_CHARS) {
                break;
            }
            float gap = lastBaseline - candidate.baseline();
            if (gap < -2f || gap > CAPTION_CONTINUATION_GAP) {
                break;
            }
            if (overlapRatio(x0, x1, candidate.x0(), candidate.x1()) < MIN_X_OVERLAP
                    || candidate.width() > xScale * 1.6f) {
                break;
            }
            if (CAPTION_START.matcher(candidate.text()).matches()) {
                break;
            }
            text.append(' ').append(candidate.text());
            lastBaseline = candidate.baseline();
        }
        return new Line(start.page(), text.toString(), x0, start.baseline(), x1);
    }

    /** Lines on the same page whose baseline sits below {@code anchor}, nearest first. */
    private static List<Line> below(Line anchor, List<Line> lines) {
        List<Line> out = new ArrayList<>();
        for (Line line : lines) {
            if (line.page() == anchor.page() && line.baseline() < anchor.baseline() && line != anchor) {
                out.add(line);
            }
        }
        out.sort(Comparator.comparingDouble(Line::baseline).reversed());
        return out;
    }

    // ── caption / figure pairing ──

    private static Figure pairWithFigure(Line caption, List<Placed> placed, Set<Placed> claimed,
                                         PDDocument doc) {
        boolean table = caption.text().regionMatches(true, 0, "table", 0, 5);
        // A figure's caption sits under it; a table's caption sits above it. A figure is given
        // the other direction as a second chance because papers do vary, but a table is not:
        // tables are drawn as ordinary text and rules rather than as drawables, so a table
        // caption that finds nothing below it would otherwise reach up and claim the figure
        // sitting above the caption — producing a confident box around the wrong thing.
        Placed best = nearest(caption, placed, claimed, !table);
        if (best == null && !table) {
            best = nearest(caption, placed, claimed, false);
        }
        if (best == null) {
            // Nothing drawable against this line, so it is not a caption we can act on. Leaving
            // it out is deliberate, and the reason is asymmetric error cost. A line that starts
            // "Figure 3 shows …" is a body-text reference, and there is no signal that separates
            // it from a real caption other than a figure sitting next to it — which is exactly
            // what is missing. So the great majority of such lines are references, and a
            // whitespace-derived box around one would be a confident crop of a paragraph of
            // body text, labelled as a figure and pasted into the research document. A figure
            // that cannot be cropped costs the reader a figure; a wrong crop misinforms them.
            return null;
        }

        claimed.add(best);
        List<Placed> cluster = clusterWith(best, placed);
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (Placed p : cluster) {
            x0 = Math.min(x0, p.x0()); y0 = Math.min(y0, p.y0());
            x1 = Math.max(x1, p.x1()); y1 = Math.max(y1, p.y1());
        }
        return build(caption, best, x0, y0, x1, y1, doc);
    }

    private static Figure build(Line caption, Placed drawable,
                                float x0, float y0, float x1, float y1, PDDocument doc) {

        PDRectangle box = doc.getPage(caption.page() - 1).getCropBox();
        x0 = Math.max(box.getLowerLeftX(), x0 - PADDING);
        y0 = Math.max(box.getLowerLeftY(), y0 - PADDING);
        x1 = Math.min(box.getUpperRightX(), x1 + PADDING);
        y1 = Math.min(box.getUpperRightY(), y1 + PADDING);

        String label = normaliseLabel(caption.text());
        String full = caption.text();
        if (full.length() > MAX_CAPTION_CHARS) {
            full = full.substring(0, MAX_CAPTION_CHARS) + "…";
        }
        return new Figure(caption.page(), label, full, x0, y0, x1, y1,
                drawable.kind(), drawable.nativeWidth(), drawable.nativeHeight());
    }

    /**
     * The drawable whose box is against the caption on the given side and shares its columns.
     *
     * <p>"Against" is measured from the caption's near edge to the drawable's near edge, so the
     * nearest drawable wins and anything past {@link #MAX_CAPTION_GAP} loses to {@code null}.
     * Anything already claimed by another caption is out of the running: one drawable belongs to
     * one caption, so a second caption reaching the same box means the association is wrong, and
     * letting both keep it would turn a visible error into two confident ones.</p>
     */
    private static Placed nearest(Line caption, List<Placed> placed, Set<Placed> claimed, boolean above) {
        Placed best = null;
        float bestGap = Float.MAX_VALUE;
        for (Placed p : placed) {
            if (p.page() != caption.page() || claimed.contains(p)) {
                continue;
            }
            if (p.width() < 20f || p.height() < 20f) {
                continue;   // rules, bullet glyphs, single icons
            }
            if (overlapRatio(caption.x0(), caption.x1(), p.x0(), p.x1()) < MIN_X_OVERLAP) {
                continue;
            }
            // Measured edge to edge: with y growing upward a figure above the caption has its
            // bottom just past the caption's baseline. Using the baseline understates the
            // caption's top by one ascent, so the gap reads a few points large — which is what
            // MAX_CAPTION_GAP is sized to absorb.
            float gap = above ? p.y0() - caption.baseline() : caption.baseline() - p.y1();
            if (gap < -6f || gap > MAX_CAPTION_GAP) {
                continue;
            }
            if (gap < bestGap) {
                bestGap = gap;
                best = p;
            }
        }
        return best;
    }

    /**
     * Every drawable that belongs to the same figure as {@code seed}: touching, or separated by
     * no more than {@link #MERGE_SLACK} while still sharing its columns.
     *
     * <p>Needed because a framework diagram is routinely assembled from several included files —
     * one per panel — and each is a separate drawable. Cropping only the seed would cut the
     * figure in half.</p>
     */
    private static List<Placed> clusterWith(Placed seed, List<Placed> placed) {
        List<Placed> cluster = new ArrayList<>();
        cluster.add(seed);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Placed p : placed) {
                if (cluster.contains(p) || p.page() != seed.page()) {
                    continue;
                }
                for (Placed member : cluster) {
                    if (touches(member, p) && overlapRatio(member.x0(), member.x1(), p.x0(), p.x1()) > 0.3f
                            && cluster.add(p)) {
                        grew = true;
                        break;
                    }
                }
            }
        }
        return cluster;
    }

    private static boolean touches(Placed a, Placed b) {
        float dx = Math.max(0f, Math.max(a.x0() - b.x1(), b.x0() - a.x1()));
        float dy = Math.max(0f, Math.max(a.y0() - b.y1(), b.y0() - a.y1()));
        return dx <= MERGE_SLACK && dy <= MERGE_SLACK;
    }

    private static String normaliseLabel(String caption) {
        Matcher m = CAPTION_START.matcher(caption);
        if (!m.matches()) {
            return "";
        }
        boolean table = m.group(3) != null;
        String number = table ? m.group(3) : m.group(2);
        return (table ? "Table " : "Fig. ") + number;
    }

    private static float overlapRatio(float ax0, float ax1, float bx0, float bx1) {
        float overlap = Math.min(ax1, bx1) - Math.max(ax0, bx0);
        if (overlap <= 0) {
            return 0f;
        }
        float narrower = Math.min(ax1 - ax0, bx1 - bx0);
        return narrower <= 0 ? 0f : overlap / narrower;
    }

    private static float area(PDRectangle box) {
        return box == null ? 0f : box.getWidth() * box.getHeight();
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    // ── graphics layer ──

    /**
     * Records where every drawable lands, then recurses into forms so a figure nested inside a
     * wrapper is still found.
     *
     * <p>Registers its operators by hand. {@code PDFStreamEngine}'s constructors register
     * nothing, and the {@code Do} processor that would normally recurse into forms
     * ({@code graphics.DrawObject}) requires a {@link org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine}
     * — so {@code Do} is intercepted here and the recursion done by calling {@code showForm}
     * directly. A stream engine without these registered silently applies no {@code cm} at all,
     * which makes every box the unit square and every figure a one-point speck: a failure that
     * produces plausible-looking output rather than an error.</p>
     */
    private static final class GraphicsScanner extends PDFStreamEngine {

        final List<Placed> placed = new ArrayList<>();
        int page;
        float pageArea = 1f;
        private int depth;

        GraphicsScanner() {
            addOperator(new Concatenate(this));
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new SetGraphicsStateParameters(this));
            addOperator(new BeginMarkedContentSequence(this));
            addOperator(new BeginMarkedContentSequenceWithProperties(this));
            addOperator(new EndMarkedContentSequence(this));
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName()) && operands.size() == 1
                    && operands.get(0) instanceof COSName name) {
                draw(name);
                return;
            }
            super.processOperator(operator, operands);
        }

        private void draw(COSName name) throws IOException {
            PDResources resources = getResources();
            if (resources == null || depth > MAX_FORM_DEPTH) {
                return;
            }
            PDXObject xobject;
            try {
                xobject = resources.getXObject(name);
            } catch (IOException e) {
                return;   // an undecodable object cannot be a figure we can crop anyway
            }
            if (xobject == null) {
                return;
            }
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();

            if (xobject instanceof PDImageXObject image) {
                // An image is painted into the unit square, scaled by the transform — not into
                // its own width x height, which is the bitmap's resolution rather than its size
                // on the page.
                Placed box = place(ctm, new PDRectangle(0, 0, 1, 1), Kind.IMAGE,
                        image.getWidth(), image.getHeight());
                if (!isPageSized(box)) {
                    placed.add(box);
                }
            } else if (xobject instanceof PDFormXObject form) {
                PDRectangle bbox = form.getBBox();
                if (bbox != null) {
                    // A form keeps its own coordinate system, so it occupies its /BBox —
                    // not the unit square. Reading it as the unit square is what makes a
                    // vector figure appear as a 1x1pt dot.
                    Placed box = place(ctm, bbox, Kind.VECTOR, 0, 0);
                    if (!isPageSized(box)) {
                        placed.add(box);
                    }
                }
                depth++;
                try {
                    showForm(form);
                } finally {
                    depth--;
                }
            }
        }

        private Placed place(Matrix ctm, PDRectangle local, Kind kind, int nativeWidth, int nativeHeight) {
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE;
            float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            float[][] corners = {
                    {local.getLowerLeftX(), local.getLowerLeftY()},
                    {local.getUpperRightX(), local.getLowerLeftY()},
                    {local.getUpperRightX(), local.getUpperRightY()},
                    {local.getLowerLeftX(), local.getUpperRightY()},
            };
            for (float[] corner : corners) {
                Point2D.Float p = ctm.transformPoint(corner[0], corner[1]);
                x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y);
                x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y);
            }
            float share = pageArea <= 0 ? 0f : ((x1 - x0) * (y1 - y0)) / pageArea;
            return new Placed(page, x0, y0, x1, y1, kind, nativeWidth, nativeHeight, share);
        }
    }

    // ── text layer ──

    /**
     * Collects text lines with boxes in the same user space {@link GraphicsScanner} reports.
     *
     * <p>Uses {@link TextPosition#getTextMatrix()} rather than the {@code *DirAdj} accessors on
     * purpose: the text matrix already has the page transform folded in, so the two layers agree
     * and "above" means the same thing in both. The {@code DirAdj} pair is measured top-down in
     * a rotated frame, which would invert every comparison on a page with any rotation.</p>
     */
    private static final class TextScanner extends PDFTextStripper {

        /** One text run as the stripper hands it over — usually a word, never a whole line. */
        private record Word(int page, String text, float x0, float y0, float x1, float y1) {
        }

        private final List<Word> words = new ArrayList<>();

        TextScanner() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || positions.isEmpty()) {
                return;
            }
            String stripped = text.strip();
            if (stripped.isEmpty()) {
                return;
            }
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE;
            float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (int i = 0; i < positions.size(); i++) {
                TextPosition tp = positions.get(i);
                Matrix m = tp.getTextMatrix();
                float x = m.getTranslateX();
                float y = m.getTranslateY();

                // The run's end comes from the next glyph's own origin rather than from
                // TextPosition.getWidth(), which is measured in text space and would need the
                // font size divided back out of a matrix that already has it multiplied in.
                // Two origin readings need no such care and cannot over-measure.
                float advance;
                if (i + 1 < positions.size()) {
                    advance = positions.get(i + 1).getTextMatrix().getTranslateX() - x;
                } else if (positions.size() > 1) {
                    advance = x - positions.get(i - 1).getTextMatrix().getTranslateX();
                } else {
                    advance = tp.getWidth();
                }

                // No height is taken from the font at all. Both TextPosition.getHeight() and
                // getFontSizeInPt() are measured before the transform, and inside a figure the
                // transform is frequently a scale — a form exported at one tenth size reports a
                // 150pt font for text that prints at 6pt. Nothing downstream may depend on a
                // rectangle that a figure can inflate by twenty.
                x0 = Math.min(x0, x); y0 = Math.min(y0, y);
                x1 = Math.max(x1, x + Math.max(advance, 0f)); y1 = Math.max(y1, y);
            }

            // Drop rotated runs. Horizontal text advances along x, so every glyph in a run
            // shares one y and the run's y-extent is zero; an extent means the run runs
            // sideways. Those are margin stamps and axis labels, and one of them matters: an
            // arXiv sidebar stamp shares a baseline with the caption beside it, spans the whole
            // column height, and would be joined to the front of that caption — leaving a line
            // that no longer starts with "Figure 3" and so losing the figure entirely.
            if (y1 - y0 > VERTICAL_RUN) {
                return;
            }
            words.add(new Word(getCurrentPageNo(), stripped, x0, y0, x1, y1));
        }

        /**
         * Rebuilds whole lines from the runs the stripper emits.
         *
         * <p>Necessary because {@code writeString} is called per text run — a word, sometimes a
         * fragment of one — so a caption never arrives in a single call and a regex over those
         * calls would never see "Figure 3: Overview of …". The runs are grouped by baseline and
         * split at the page's column gutter, which the running max-x of the line cannot do on
         * its own: in a two-column paper a wide table reaches to within a few points of the next
         * column, so "is the gap wide?" has no threshold that separates a table from a column.</p>
         */
        List<Line> lines() {
            Map<Integer, List<Word>> byPage = new TreeMap<>();
            for (Word word : words) {
                byPage.computeIfAbsent(word.page(), key -> new ArrayList<>()).add(word);
            }
            List<Line> out = new ArrayList<>();
            for (Map.Entry<Integer, List<Word>> entry : byPage.entrySet()) {
                out.addAll(assemble(entry.getValue(), gutterOf(entry.getValue())));
            }
            return out;
        }

        /**
         * The page's column gutter, or {@link Float#NaN} when the text is one column.
         *
         * <p>Found by asking which x positions no word box ever covers. Body text in every
         * column covers its own span on every line, so a stretch of x that stays clear across the
         * page is a gutter — and because the test is "clear on almost every line" rather than
         * "clear on this line", the occasional full-width table crossing it does not hide it. A
         * single-column page has no such stretch, which is what makes this safe to run blindly.</p>
         */
        private static float gutterOf(List<Word> pageWords) {
            if (pageWords.isEmpty()) {
                return Float.NaN;
            }
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            for (Word word : pageWords) {
                minX = Math.min(minX, word.x0());
                maxX = Math.max(maxX, word.x1());
            }
            float width = maxX - minX;
            if (width < 100f) {
                return Float.NaN;
            }

            int buckets = (int) Math.ceil(width) + 1;
            int[] cover = new int[buckets];
            for (Word word : pageWords) {
                int from = Math.max(0, (int) (word.x0() - minX));
                int to = Math.min(buckets - 1, (int) (word.x1() - minX));
                for (int i = from; i <= to; i++) {
                    cover[i]++;
                }
            }

            // Only the middle of the page can hold a gutter; the margins either side of the
            // text block are clear for every page and would otherwise always win.
            int from = (int) (width * 0.30f);
            int to = (int) (width * 0.70f);
            int budget = Math.max(1, pageWords.size() / 40);
            int bestStart = -1, bestLength = 0, runStart = -1;
            for (int i = from; i <= to; i++) {
                if (cover[i] <= budget) {
                    if (runStart < 0) {
                        runStart = i;
                    }
                } else if (runStart >= 0) {
                    if (i - runStart > bestLength) {
                        bestLength = i - runStart;
                        bestStart = runStart;
                    }
                    runStart = -1;
                }
            }
            if (runStart >= 0 && to + 1 - runStart > bestLength) {
                bestLength = to + 1 - runStart;
                bestStart = runStart;
            }
            return bestLength >= MIN_GUTTER ? minX + bestStart + bestLength / 2f : Float.NaN;
        }

        /**
         * Groups the page's runs into rows by baseline, then cuts each row at the gutter.
         *
         * <p>Rows are found by clustering the distinct baselines rather than by asking whether
         * each run is "close enough" to the current line. A tolerance derived from the font is
         * unusable here: inside a figure, the font size a run reports is measured before the
         * form's own scale, so a diagram exported at a tenth size claims a 150pt font for text
         * that prints at 6pt. A tolerance built from that swallows every row of the diagram and
         * the body text beside it into one "line". Clustering on the baseline coordinates
         * themselves needs no font information and cannot be inflated by one.</p>
         */
        private static List<Line> assemble(List<Word> pageWords, float gutter) {
            List<Word> byBaseline = new ArrayList<>(pageWords);
            byBaseline.sort(Comparator.comparingDouble(Word::y0).thenComparingDouble(Word::x0));

            List<Line> out = new ArrayList<>();
            List<Word> row = new ArrayList<>();
            float previousBaseline = 0f;
            for (Word word : byBaseline) {
                if (!row.isEmpty() && word.y0() - previousBaseline > BASELINE_TOLERANCE) {
                    out.addAll(splitRow(row, gutter));
                    row.clear();
                }
                row.add(word);
                previousBaseline = word.y0();
            }
            if (!row.isEmpty()) {
                out.addAll(splitRow(row, gutter));
            }
            return out;
        }

        /** Cuts one row into per-column lines, leaving a single-column row whole. */
        private static List<Line> splitRow(List<Word> row, float gutter) {
            List<Word> sorted = new ArrayList<>(row);
            sorted.sort(Comparator.comparingDouble(Word::x0));

            List<Line> out = new ArrayList<>();
            float x0 = 0f, x1 = 0f;
            float previousStart = 0f;
            StringBuilder text = new StringBuilder();

            for (Word word : sorted) {
                boolean columnBreak = text.length() > 0
                        && crossesGutter(previousStart, x1, word.x0(), gutter);
                if (columnBreak) {
                    out.add(new Line(word.page(), text.toString(), x0, sorted.get(0).y0(), x1));
                    text.setLength(0);
                }
                if (text.length() == 0) {
                    x0 = word.x0();
                    x1 = word.x1();
                } else {
                    text.append(' ');
                    x1 = Math.max(x1, word.x1());
                }
                text.append(word.text());
                previousStart = word.x0();
            }
            if (text.length() > 0) {
                out.add(new Line(sorted.get(0).page(), text.toString(), x0, sorted.get(0).y0(), x1));
            }
            return out;
        }

        /**
         * Whether the step from one run to the next is a column break rather than a space.
         *
         * <p>Tested on where each run <em>starts</em>, because that is what says which column it
         * belongs to; the end of the previous run does not, and a table cell that overhangs the
         * gutter would defeat a test built on it. The gap size is then required as well, so a
         * full-width line whose words happen to straddle the gutter is not cut in half — inside
         * one line the step is a space, and across the gutter it is the whole gutter.</p>
         */
        private static boolean crossesGutter(float previousStart, float previousEnd,
                                             float nextStart, float gutter) {
            if (Float.isNaN(gutter)) {
                return false;
            }
            return previousStart < gutter && nextStart >= gutter && nextStart - previousEnd > COLUMN_GAP;
        }
    }
}

package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.pdf.PdfFigureLocator;
import com.itajay.superassistant.pdf.PdfFigureLocator.Figure;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a downloaded paper's figures, in two steps: {@link #listPaperFigures} names them,
 * {@link #extractPaperFigures} cuts the ones that were named.
 *
 * <p>The split exists because choosing the figure is a reading task and cutting it is an
 * expensive one. A framework diagram has to be identified from its caption, and the caption is
 * text — a paper's twenty captions cost about as much context as one page of its body. Cutting,
 * by contrast, rasterises a page per figure. So the model reads the captions first and asks for
 * the two figures it actually wants, instead of being handed every image in a page range and
 * left to guess which is which.</p>
 *
 * <p>That guessing was the older design's real defect, and it was not fixable by better
 * instructions: an extracted image carries no figure number, so the model was told to "confirm
 * the number from the caption" while holding only a list of {@code p4_1, p4_2, p4_3} and their
 * dimensions. It could not do this, and nothing said so. Here the number comes from the caption
 * that sits against the figure, and it travels into the file name, so a figure's identity is
 * never inferred from its position in a list.</p>
 *
 * <p>Two limits are surfaced in the replies rather than hidden. A figure whose caption is
 * absent or whose region cannot be found is not reported at all — see
 * {@link PdfFigureLocator} for why silence beats a guess. And a scanned PDF has no captions to
 * pair with, so it yields nothing and the reply says what to write instead.</p>
 */
@Component
public class PaperFigureTool {

    private static final Logger log = LoggerFactory.getLogger(PaperFigureTool.class);

    /** Prefix of the reference path to paste into {@code analysis/{短名}.md}. */
    private static final String ANALYSIS_PATH_PREFIX = "figures/";

    /** Prefix of the reference path to paste into {@code document/{文档名}.md}. */
    private static final String DOCUMENT_PATH_PREFIX = "../analysis/figures/";

    /** How much of a caption to show in the index; long enough to judge, short enough to list. */
    private static final int CAPTION_PREVIEW_CHARS = 150;

    /** A figure reference as a caller writes it: "Fig. 2", "Figure 2", "Table IV". */
    private static final Pattern FIGURE_REFERENCE =
            Pattern.compile("(?i)(fig(?:ure)?\\.?\\s*\\d+|table\\s*[ivx\\d]+)");

    private final PaperDownloadProperties props;

    public PaperFigureTool(PaperDownloadProperties props) {
        this.props = props;
    }

    @Tool(description = """
            List the figures of a downloaded paper PDF: one row per figure with its number, page,
            size and caption. Reads the paper's captions, which is how a figure is identified —
            run this first, then decide which figures you want and ask extractPaperFigures for
            those numbers.
            Writes nothing. Pass a page range to narrow the listing for a long paper.""")
    public String listPaperFigures(
            @ToolParam(description = "课题方向, the research topic the paper belongs to") String topic,
            @ToolParam(description = "Path returned by downloadPaper, or just the short name, e.g. 'MS-Diffusion'") String pathOrName,
            @ToolParam(description = "First page to scan, 1-based. Use 1 or omit to start from the beginning.", required = false) Integer startPage,
            @ToolParam(description = "Last page to scan, 1-based inclusive. Omit to scan to the end.", required = false) Integer endPage) {

        Path pdf;
        try {
            pdf = PaperStore.resolvePdf(topic, pathOrName);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.isRegularFile(pdf)) {
            return "Error: PDF not found: " + PaperStore.relative(pdf)
                    + "\nCall downloadPaper first, or check the name with listDownloadedPapers.";
        }

        int from = startPage == null ? 1 : Math.max(1, startPage);
        int to = endPage == null ? Integer.MAX_VALUE : Math.max(from, endPage);

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int total = doc.getNumberOfPages();
            if (total == 0 || from > total) {
                return "Error: 页码范围从 p." + from + " 开始，但该 PDF 只有 " + total + " 页："
                        + PaperStore.relative(pdf);
            }
            int last = Math.min(Math.min(to, total), from + props.getMaxPages() - 1);
            List<Figure> figures = PdfFigureLocator.locate(doc, from, last);
            return renderIndex(pdf, figures, from, last, total);
        } catch (InvalidPasswordException e) {
            return "Error: PDF is password-protected: " + PaperStore.relative(pdf)
                    + "\nMark this paper SOURCE_LEVEL=ABSTRACT_ONLY and note that no figure was extractable.";
        } catch (IOException e) {
            log.warn("Listing figures failed for {}", pdf, e);
            return "Error: could not parse PDF " + PaperStore.relative(pdf) + " — " + e.getMessage()
                    + "\nThe file may be corrupt or an error page saved as .pdf.";
        }
    }

    @Tool(description = """
            Cut figures out of a downloaded paper PDF and save them as PNGs under
            investigation/{课题方向}/analysis/figures/.
            Pass `figures` with the numbers you chose from listPaperFigures, e.g. "Fig. 2, Fig. 5" —
            only those are written. Omit it to extract every figure in the page range, up to the
            configured cap.
            Returns a table with two ready-made relative paths per figure: use the 'analysis file'
            column when you write the paper's analysis file, and the 'document' column when the
            research document cites that figure. Copy those strings verbatim — never assemble the
            path yourself.""")
    public String extractPaperFigures(
            @ToolParam(description = "课题方向, the research topic the paper belongs to") String topic,
            @ToolParam(description = "Path returned by downloadPaper, or just the short name, e.g. 'MS-Diffusion'") String pathOrName,
            @ToolParam(description = "Figure numbers to cut, comma-separated, e.g. 'Fig. 2, Fig. 5'. Omit to cut every figure in the page range.", required = false) String figures,
            @ToolParam(description = "First page to scan, 1-based. Use 1 or omit to start from the beginning.", required = false) Integer startPage,
            @ToolParam(description = "Last page to scan, 1-based inclusive. Omit to scan to the end.", required = false) Integer endPage) {

        Path pdf;
        try {
            pdf = PaperStore.resolvePdf(topic, pathOrName);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.isRegularFile(pdf)) {
            return "Error: PDF not found: " + PaperStore.relative(pdf)
                    + "\nCall downloadPaper first, or check the name with listDownloadedPapers.";
        }

        String shortName;
        Path figuresDir;
        try {
            // Derived from the PDF's own name so the figure prefix is byte-identical to
            // the analysis file's name, which is what makes "every figure belongs to an
            // analysis file" a checkable property rather than a convention.
            shortName = AnalysisStore.nameFor(PaperStore.shortNameOf(pdf));
            figuresDir = WorkspacePaths.figuresDir(topic);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        int from = startPage == null ? 1 : Math.max(1, startPage);
        int to = endPage == null ? Integer.MAX_VALUE : Math.max(from, endPage);

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int total = doc.getNumberOfPages();
            if (total == 0 || from > total) {
                return "Error: 页码范围从 p." + from + " 开始，但该 PDF 只有 " + total + " 页："
                        + PaperStore.relative(pdf);
            }
            int last = Math.min(Math.min(to, total), from + props.getMaxPages() - 1);
            List<Figure> located = PdfFigureLocator.locate(doc, from, last);

            Map<String, String> wanted = parseRequested(figures);
            if (!figuresIsBlank(figures) && wanted.isEmpty()) {
                // Naming a figure is the one place a model-supplied string reaches this tool in a
                // free form. Treating an unreadable one as "no names given" would quietly turn a
                // malformed request into a sweep of the whole page range, so it is refused.
                return "Error: 无法从 figures=\"" + figures + "\" 里读出一个图号。\n"
                        + "写法示例：figures=\"Fig. 2, Fig. 5\"。省略该参数则导出页码范围里所有带图注的图。";
            }

            List<Figure> chosen = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, String> request : wanted.entrySet()) {
                located.stream()
                        .filter(f -> keyOf(f.label()).equals(request.getKey()))
                        .findFirst()
                        .ifPresentOrElse(chosen::add, () -> missing.add(request.getValue()));
            }

            if (chosen.isEmpty() && wanted.isEmpty()) {
                chosen.addAll(located);
            }
            if (chosen.isEmpty()) {
                return renderNothing(pdf, located, missing, from, last, total);
            }

            // The cap is a backstop for an unnamed sweep over a wide page range. A caller that
            // named figures is answered in full: it just read the index, and a cap that silently
            // dropped one of two requested figures would make that index a lie.
            boolean hitCap = wanted.isEmpty() && chosen.size() > props.getMaxFigures();
            if (hitCap) {
                chosen = chosen.subList(0, props.getMaxFigures());
            }

            List<String> written = new ArrayList<>();
            Map<Figure, String> fileNames = new LinkedHashMap<>();
            Set<String> used = new HashSet<>();
            for (Figure figure : chosen) {
                String fileName = fileNameFor(shortName, figure, used);
                try {
                    BufferedImage image = PdfFigureLocator.render(doc, figure, props.getFigureDpi());
                    writePng(image, figuresDir, fileName);
                } catch (IOException e) {
                    log.warn("Failed to cut {} page {} of {}", figure.label(), figure.page(), pdf, e);
                    return "Error: could not cut " + figure.label() + " (p." + figure.page() + ") — "
                            + e.getMessage();
                }
                fileNames.put(figure, fileName);
                written.add(fileName);
            }

            return renderExtraction(pdf, shortName, figuresDir, fileNames, missing, located,
                    from, last, total, hitCap);

        } catch (InvalidPasswordException e) {
            return "Error: PDF is password-protected: " + PaperStore.relative(pdf)
                    + "\nMark this paper SOURCE_LEVEL=ABSTRACT_ONLY and note that no figure was extractable.";
        } catch (IOException e) {
            log.warn("PDF figure extraction failed for {}", pdf, e);
            return "Error: could not parse PDF " + PaperStore.relative(pdf) + " — " + e.getMessage()
                    + "\nThe file may be corrupt or an error page saved as .pdf.";
        }
    }

    // ── the index ──

    private String renderIndex(Path pdf, List<Figure> figures, int from, int last, int total) {
        if (figures.isEmpty()) {
            return "OK: " + paper(pdf) + " 的 p." + from + "-" + last + " 没有找到带图注的图。\n"
                    + "可能原因：整段页码范围里没有图；或这是扫描版 PDF（没有文字层，因而没有图注可配对）。\n"
                    + "这种情况在分析文件里写一行「图见原文 p.X」，不要引用不存在的图片路径。";
        }
        StringBuilder out = new StringBuilder();
        out.append("OK: ").append(paper(pdf)).append(" 的 p.").append(from).append('-').append(last)
                .append(" 共 ").append(figures.size()).append(" 张带图注的图（全篇 ").append(total).append(" 页）：\n\n")
                .append("| 图号 | 页 | 尺寸(pt) | 图注 |\n")
                .append("| ---- | -- | -------- | ---- |\n");
        for (Figure figure : figures) {
            out.append("| ").append(figure.label())
                    .append(" | ").append(figure.page())
                    .append(" | ").append(Math.round(figure.width())).append('x').append(Math.round(figure.height()))
                    .append(" | ").append(preview(figure.caption()))
                    .append(" |\n");
        }
        out.append("\n按图注判断哪张是你需要的（方法框架图、创新点涉及的机制图），")
                .append("然后调用 extractPaperFigures(topic, pathOrName, figures=\"Fig. 3\") 只取那一张。\n")
                .append("本次没有落盘任何文件。");
        return out.toString();
    }

    // ── the extraction ──

    private String renderNothing(Path pdf, List<Figure> located, List<String> missing,
                                 int from, int last, int total) {
        StringBuilder out = new StringBuilder();
        if (!missing.isEmpty()) {
            out.append("Error: ").append(paper(pdf)).append(" 里没有这些图号：")
                    .append(String.join("、", missing)).append('\n');
            if (located.isEmpty()) {
                out.append("该页码范围一张带图注的图都没有。");
            } else {
                out.append("可选的图号：");
                out.append(located.stream().map(Figure::label).reduce((a, b) -> a + "、" + b).orElse(""));
                out.append("\n用 listPaperFigures 看图注（图号可能写错，或该图确实不在这个页码范围里）。");
            }
            return out.toString();
        }
        out.append("OK: no extractable figure in p.").append(from).append('-').append(last)
                .append(" of ").append(PaperStore.relative(pdf)).append('\n')
                .append("（扫描版 PDF，或该范围确实没有带图注的图。）\n")
                .append("降级写法：在该篇 analysis 文件里写一行「图见原文 Fig. N (p.X)」，不引用图片；\n")
                .append("不要在文档里凭空引用一个不存在的图片路径。");
        return out.toString();
    }

    /** The reply. Every relative path in it is ready to paste; none needs composing. */
    private String renderExtraction(Path pdf, String shortName, Path figuresDir,
                                    Map<Figure, String> fileNames, List<String> missing,
                                    List<Figure> located, int from, int last, int total,
                                    boolean hitCap) {

        StringBuilder out = new StringBuilder();
        out.append("OK: extracted ").append(fileNames.size()).append(" figure(s) from ")
                .append(PaperStore.relative(pdf)).append(" (p.").append(from).append('-').append(last)
                .append(" of ").append(total).append(")\n")
                .append("written to: ").append(WorkspacePaths.relative(figuresDir)).append('\n')
                .append("analysis file: ").append(WorkspacePaths.relative(
                        figuresDir.getParent().resolve(shortName + ".md"))).append("\n\n")
                .append("| # | 图号 | 页 | 写进 analysis 文件的引用 | 写进 document 文件的引用 |\n")
                .append("| - | ---- | -- | ------------------------ | ------------------------ |\n");

        int index = 1;
        for (Map.Entry<Figure, String> entry : fileNames.entrySet()) {
            Figure figure = entry.getKey();
            String file = entry.getValue();
            out.append("| ").append(index++)
                    .append(" | ").append(figure.label())
                    .append(" | ").append(figure.page())
                    .append(" | ").append(ANALYSIS_PATH_PREFIX).append(file)
                    .append(" | ").append(DOCUMENT_PATH_PREFIX).append(file)
                    .append(" |\n");
        }

        out.append("\n两列都是可**逐字复制**的完整相对路径，不要自己改动前缀，也不要拼绝对路径。\n")
                .append("图注写在图片**下方**，标出原文图号与页码，便于回查 PDF。\n")
                .append("analysis 文件里每张图下面补一行「> 文档引用：<第 5 列的内容>」，writer-agent 会逐字复制它。");

        if (!missing.isEmpty()) {
            out.append("\n\nnot found: 没有这些图号 — ").append(String.join("、", missing))
                    .append("；本次没导出它们。可用的图号：")
                    .append(located.stream().map(Figure::label).reduce((a, b) -> a + "、" + b).orElse("无"));
        }
        if (hitCap) {
            out.append("\ntruncated: 已达单次上限 max-figures=").append(props.getMaxFigures())
                    .append("，用 figures 参数点名需要的图，或缩窄页码范围再调一次。");
        }
        return out.toString();
    }

    // ── helpers ──

    private String paper(Path pdf) {
        return PaperStore.relative(pdf);
    }

    private static boolean figuresIsBlank(String figures) {
        return figures == null || figures.isBlank();
    }

    private static String preview(String caption) {
        String flat = caption.replace('|', '/').replaceAll("\\s+", " ").strip();
        return flat.length() <= CAPTION_PREVIEW_CHARS ? flat : flat.substring(0, CAPTION_PREVIEW_CHARS) + "…";
    }

    /**
     * The requested figure numbers, keyed for matching, in the order written.
     *
     * <p>Found by scanning for references rather than by splitting on a separator. Splitting has
     * to guess one — a comma, a space, a semicolon — and "Fig. 2" contains a space, so any choice
     * either tears the number off its label or fails to separate two of them. Scanning for
     * {@code fig n} and {@code table n} reads "Fig. 2, Fig. 5", "Fig. 2 Fig. 5" and "Figure 3
     * and Table 1" alike.</p>
     *
     * <p>The value keeps the caller's own spelling, so a reply can quote back what was asked for
     * rather than a normalised form the caller never wrote.</p>
     */
    private static Map<String, String> parseRequested(String figures) {
        Map<String, String> wanted = new LinkedHashMap<>();
        if (figures == null || figures.isBlank()) {
            return wanted;
        }
        Matcher matcher = FIGURE_REFERENCE.matcher(figures);
        while (matcher.find()) {
            String written = matcher.group().strip();
            wanted.putIfAbsent(keyOf(written), written);
        }
        return wanted;
    }

    /** "Fig. 3" / "Figure 3" / "fig3" / "Table 2" all collapse to a comparable key. */
    private static String keyOf(String label) {
        String flat = label == null ? "" : label.toLowerCase().replaceAll("[^a-z0-9]", "");
        return flat.replaceFirst("^figure", "fig");
    }

    /**
     * A file name a figure keeps across runs: the short name plus its number, so re-running
     * overwrites the same file instead of accumulating copies, and so the number in the name is
     * the number in the paper rather than a position in a list.
     */
    private static String fileNameFor(String shortName, Figure figure, Set<String> used) {
        String base = shortName + "_" + figure.label().replaceAll("[^A-Za-z0-9]", "");
        String name = base + ".png";
        int suffix = 2;
        while (!used.add(name)) {
            name = base + "_" + suffix++ + ".png";
        }
        return name;
    }

    /**
     * Writes one image through a temp file in the destination folder, so a partial write never
     * becomes a readable figure. The temp file shares the filesystem, which is what makes the
     * move atomic — the same reasoning as the download tool's {@code .part} file.
     */
    private void writePng(BufferedImage image, Path figuresDir, String fileName) throws IOException {
        Path target = figuresDir.resolve(fileName).normalize();
        if (!target.startsWith(figuresDir)) {
            throw new IOException("resolved path escapes the figures directory: " + target);
        }
        Files.createDirectories(figuresDir);
        Path temp = Files.createTempFile(figuresDir, ".figure-", ".png");
        try {
            if (!ImageIO.write(image, "png", temp.toFile())) {
                throw new IOException("no PNG encoder available");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

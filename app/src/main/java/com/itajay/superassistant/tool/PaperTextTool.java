package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the text of already-downloaded paper PDFs.
 *
 * <p>Deliberately separate from {@link PaperDownloadTool}: downloading and close
 * reading are done by different agents. The {@code analyst-agent} owns close reading
 * (it needs the method details and formulas to extract the four dimensions), the
 * {@code reviewer-agent} holds it to check a quoted formula against the original, and
 * the research agent only decides what is worth downloading. Splitting the tools makes
 * that boundary structural rather than a matter of prompt discipline — the research
 * agent has no way to read a paper's body, and the {@code writer-agent}, which
 * composes the document from the persisted analyses, has no way to fetch or re-read
 * one.</p>
 *
 * <p>Read-only: this tool never writes, deletes, or fetches anything. The extracted
 * text is returned to the caller and not persisted, so re-reading is always possible.</p>
 */
@Component
public class PaperTextTool {

    private static final Logger log = LoggerFactory.getLogger(PaperTextTool.class);

    private final PaperDownloadProperties props;

    public PaperTextTool(PaperDownloadProperties props) {
        this.props = props;
    }

    @Tool(description = """
            Extract the text of a previously downloaded paper PDF, so its formulas and method details
            can be read. Returns text with page markers. The result is truncated when very long —
            use the page range arguments to read a specific section.""")
    public String extractPaperText(
            @ToolParam(description = "课题方向, the research topic the paper belongs to") String topic,
            @ToolParam(description = "Path returned by downloadPaper, or just the short name, e.g. 'MS-Diffusion'") String pathOrName,
            @ToolParam(description = "First page to extract, 1-based. Use 1 for the whole paper.", required = false) Integer startPage,
            @ToolParam(description = "Last page to extract, 1-based inclusive. Omit to read to the end.", required = false) Integer endPage) {

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
            int last = Math.min(Math.min(to, total), from + props.getMaxPages() - 1);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(from);
            stripper.setEndPage(last);
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);

            StringBuilder out = new StringBuilder();
            out.append("PDF: ").append(PaperStore.relative(pdf))
                    .append("\nPages: ").append(from).append('-').append(last)
                    .append(" of ").append(total).append("\n\n");

            boolean truncated = text.length() > props.getMaxTextChars();
            if (truncated) {
                text = text.substring(0, props.getMaxTextChars());
            }
            out.append(text);

            if (truncated) {
                out.append("\n\n[TEXT TRUNCATED at ").append(props.getMaxTextChars())
                        .append(" chars — call again with a page range to read further]");
            }
            if (last < total) {
                out.append("\n[More pages available: ").append(last + 1).append('-').append(total).append(']');
            }
            return out.toString();

        } catch (InvalidPasswordException e) {
            return "Error: PDF is password-protected: " + PaperStore.relative(pdf)
                    + "\nMark this paper SOURCE_LEVEL=ABSTRACT_ONLY.";
        } catch (IOException e) {
            log.warn("PDF text extraction failed for {}", pdf, e);
            return "Error: could not parse PDF " + PaperStore.relative(pdf) + " — " + e.getMessage()
                    + "\nThe file may be corrupt or an error page saved as .pdf. Try downloading again.";
        }
    }

    @Tool(description = "List papers already downloaded for a topic, with size and page count. Use this to see which papers are available to read.")
    public String listDownloadedPapers(
            @ToolParam(description = "课题方向, the research topic whose papers should be listed") String topic) {
        return PaperStore.listPdfs(topic);
    }
}

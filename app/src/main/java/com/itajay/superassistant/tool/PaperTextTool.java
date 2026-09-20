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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
 * <p>Read-only: this tool never writes, deletes, or fetches anything.</p>
 *
 * <p>Because the analyst reads a paper and the reviewer then re-reads (often subsets
 * of) the same unchanged PDF, extracted page text is kept in a bounded in-memory cache,
 * keyed by the file's path/size/mtime so a re-downloaded PDF invalidates itself. The
 * cache never changes what a call returns: a window is only served from cache once
 * joining the cached per-page texts has been verified, for that document, to reproduce
 * what a real range strip emits; anything else takes the normal parse path.</p>
 */
@Component
public class PaperTextTool {

    private static final Logger log = LoggerFactory.getLogger(PaperTextTool.class);

    private final PaperDownloadProperties props;

    /**
     * Per-document page caches, LRU-bounded by {@code agent.paper.text-cache-max-docs}.
     * Access-ordered {@link LinkedHashMap} — the house pattern from {@code CompactHook}.
     * Only short get/put operations run under its monitor; never a PDF parse.
     */
    private final Map<PdfKey, CachedDoc> docCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PdfKey, CachedDoc> eldest) {
            return size() > Math.max(0, props.getTextCacheMaxDocs());
        }
    };

    /** Test seam: how many times a PDF has been opened for parsing. */
    private final AtomicInteger pdfOpens = new AtomicInteger();

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

        PdfKey key = cacheKey(pdf);

        CachedDoc cached = peek(key);
        if (cached != null) {
            int hitLast = windowLast(from, to, cached.totalPages);
            String joined = cached.joinIfCovered(from, hitLast);
            if (joined != null) {
                log.debug("Paper text cache hit: {} pages {}-{}", PaperStore.relative(pdf), from, hitLast);
                return assemble(pdf, from, hitLast, cached.totalPages, joined);
            }
        }

        pdfOpens.incrementAndGet();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int total = doc.getNumberOfPages();
            int last = windowLast(from, to, total);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(from);
            stripper.setEndPage(last);
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);

            if (key != null) {
                maintainCache(key, doc, total, from, last, text);
            }
            return assemble(pdf, from, last, total, text);

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

    /** Test seam: how many times {@code Loader.loadPDF} has run for {@code extractPaperText}. */
    int pdfOpensForTesting() {
        return pdfOpens.get();
    }

    /** Null when the cache is disabled or the file's attributes cannot be read — both mean "no caching". */
    private PdfKey cacheKey(Path pdf) {
        if (props.getTextCacheMaxDocs() <= 0) {
            return null;
        }
        try {
            return PdfKey.of(pdf);
        } catch (IOException e) {
            return null;
        }
    }

    private CachedDoc peek(PdfKey key) {
        if (key == null) {
            return null;
        }
        synchronized (docCache) {
            return docCache.get(key);
        }
    }

    private int windowLast(int from, int to, int total) {
        return Math.min(Math.min(to, total), from + props.getMaxPages() - 1);
    }

    /**
     * Best-effort cache maintenance after a real range strip: fill in the window's
     * missing pages (the document is already open and its pages parsed, so single-page
     * strips are cheap), then — once per document — verify that joining cached page
     * texts reproduces what the range strip emitted. A document whose join never
     * verifies is simply never served from cache; bookkeeping must never change output.
     */
    private void maintainCache(PdfKey key, PDDocument doc, int total, int from, int last, String rangeText) {
        try {
            CachedDoc cached;
            synchronized (docCache) {
                cached = docCache.computeIfAbsent(key, k -> new CachedDoc(total));
                if (cached.totalPages != total) {
                    // Same path/mtime/size but a different page count can only be a
                    // replacement the attributes missed — drop the stale holder.
                    cached = new CachedDoc(total);
                    docCache.put(key, cached);
                }
            }

            PDFTextStripper pageStripper = new PDFTextStripper();
            pageStripper.setSortByPosition(true);
            for (int page = from; page <= last; page++) {
                cached.pageOr(page, p -> {
                    // Function cannot throw checked exceptions; the surrounding
                    // catch (Throwable) in maintainCache is the safety net.
                    try {
                        pageStripper.setStartPage(p);
                        pageStripper.setEndPage(p);
                        return pageStripper.getText(doc);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }

            cached.verifyJoin(PaperStore.relative(key.path()), from, last, rangeText);
        } catch (Throwable t) {
            log.debug("Paper text cache maintenance skipped for {}", PaperStore.relative(key.path()), t);
        }
    }

    /**
     * Shared output assembly for the cache-hit and range-strip paths, so they cannot
     * drift apart. Verbatim from the original single-path implementation.
     */
    private String assemble(Path pdf, int from, int last, int total, String text) {
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
    }

    /** Cache key: identity of the immutable PDF on disk. */
    private record PdfKey(Path path, long lastModified, long size) {

        static PdfKey of(Path pdf) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(pdf, BasicFileAttributes.class);
            return new PdfKey(pdf.toAbsolutePath().normalize(),
                    attrs.lastModifiedTime().toMillis(), attrs.size());
        }
    }

    /**
     * Per-document cache: total pages, extracted page texts, and the separator that
     * was measured to reproduce a range strip when pages are joined. All fields are
     * guarded by {@code this}; a per-page extraction callback also runs under this
     * monitor, so the page map is never touched by two lanes at once.
     */
    private static final class CachedDoc {

        /**
         * Separators that could sit between two pages in a range strip. PDFBox 3.x has
         * no page separator constant left, so the real one is measured per document in
         * {@link #verifyJoin} rather than assumed. {@code lineSeparator} can equal
         * {@code "\n"}, and {@link List#of} rejects duplicates.
         */
        private static final List<String> JOIN_CANDIDATES = System.lineSeparator().equals("\n")
                ? List.of("\n", "")
                : List.of(System.lineSeparator(), "\n", "");

        final int totalPages;

        private final Map<Integer, String> pageTexts = new HashMap<>();
        private String joinSeparator;
        private boolean joinVerified;

        CachedDoc(int totalPages) {
            this.totalPages = totalPages;
        }

        /** Get-or-extract one page; the extractor runs under this monitor. */
        synchronized String pageOr(int page, Function<Integer, String> extractor) {
            return pageTexts.computeIfAbsent(page, extractor);
        }

        /**
         * Once per document: check that joining the window's cached page texts equals
         * the range strip that produced them. Until this succeeds for some window,
         * the document is never served from cache. Only a multi-page window can
         * discriminate the candidates — on a single-page window every separator
         * trivially matches — so those are skipped.
         */
        synchronized void verifyJoin(String relative, int from, int last, String rangeText) {
            if (joinVerified || last <= from) {
                return;
            }
            for (String separator : JOIN_CANDIDATES) {
                if (join(from, last, separator).equals(rangeText)) {
                    joinSeparator = separator;
                    joinVerified = true;
                    log.debug("Paper text cache verified page join for {} pages {}-{} with separator {}",
                            relative, from, last, separator);
                    return;
                }
            }
        }

        /** Joined text of a fully cached window, or null if the window is not servable from cache. */
        synchronized String joinIfCovered(int from, int last) {
            if (!joinVerified) {
                return null;
            }
            for (int page = from; page <= last; page++) {
                if (!pageTexts.containsKey(page)) {
                    return null;
                }
            }
            return join(from, last, joinSeparator);
        }

        private String join(int from, int last, String separator) {
            StringBuilder out = new StringBuilder();
            for (int page = from; page <= last; page++) {
                if (out.length() > 0) {
                    out.append(separator);
                }
                out.append(pageTexts.get(page));
            }
            return out.toString();
        }
    }
}

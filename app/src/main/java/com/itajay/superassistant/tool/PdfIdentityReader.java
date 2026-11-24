package com.itajay.superassistant.tool;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a PDF's identity off its first page, using where and in which direction text is set.
 *
 * <p>Plain text extraction is not enough. arXiv's stamp runs vertically up the margin, and a
 * text stripper emits it one glyph per line, so a pattern over the extracted text never sees
 * it — measured on 14 stamped papers, that approach read 0 of them. Reading the glyphs that
 * run across the page's main direction, in their own order, reads all 14.</p>
 */
final class PdfIdentityReader {

    private static final Logger log = LoggerFactory.getLogger(PdfIdentityReader.class);

    private static final String ARXIV_FORMS = "(\\d{4}\\.\\d{4,5}|[a-z-]+(?:\\.[a-z]{2})?/\\d{7})";

    /**
     * The vertical stamp, spaces removed. The version is required: it is always printed, and
     * it keeps a following date's digits from being read as part of the id.
     */
    private static final Pattern VERTICAL_STAMP = Pattern.compile("arxiv:" + ARXIV_FORMS + "v\\d");

    /**
     * A stamp set along the text. Only with its subject tag: a bare {@code arXiv:1706.03762}
     * in running text is a citation of some other paper.
     */
    private static final Pattern LEVEL_STAMP = Pattern.compile(
            "arxiv:\\s*" + ARXIV_FORMS + "(?:v\\d+)?\\s*\\[[a-z-]+(?:\\.[a-z-]+)?\\]");

    private static final Pattern DOI = Pattern.compile("10\\.\\d{4,9}/[-._;()/:a-z0-9]+");

    /** Where the title zone ends when the page announces it. */
    private static final Pattern ABSTRACT = Pattern.compile("^(?:abstract|摘要|1?introduction).*");

    /** Otherwise the zone is this much of the page's text height. */
    private static final float TITLE_ZONE = 0.6f;
    private static final int MAX_TITLE_LINES = 30;

    /** Glyphs this much smaller than their line are footnote marks, not words. */
    private static final float SUPERSCRIPT_RATIO = 0.7f;

    private static final int CACHE_SIZE = 256;

    private record CacheKey(Path path, long size, long modified) {
    }

    private static final Map<CacheKey, PdfIdentity> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, PdfIdentity> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private PdfIdentityReader() {
    }

    /**
     * The identity of a PDF, remembered until the file changes.
     *
     * @return empty when the file is not a readable PDF; {@link PdfIdentity#NONE} when it is
     *         one that does not say what it is
     */
    static Optional<PdfIdentity> identityOf(Path pdf) {
        if (pdf == null || !Files.isRegularFile(pdf)) {
            return Optional.empty();
        }
        CacheKey key;
        try {
            key = new CacheKey(pdf.toAbsolutePath().normalize(), Files.size(pdf),
                    Files.getLastModifiedTime(pdf).to(java.util.concurrent.TimeUnit.NANOSECONDS));
        } catch (IOException e) {
            return Optional.empty();
        }
        synchronized (CACHE) {
            PdfIdentity known = CACHE.get(key);
            if (known != null) {
                return Optional.of(known);
            }
        }
        Optional<PdfIdentity> read = read(pdf);
        read.ifPresent(identity -> {
            synchronized (CACHE) {
                CACHE.put(key, identity);
            }
        });
        return read;
    }

    static Optional<PdfIdentity> read(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.getNumberOfPages() == 0) {
                return Optional.of(PdfIdentity.NONE);
            }
            try {
                return Optional.of(readFirstPage(doc));
            } catch (IOException | RuntimeException e) {
                log.debug("首页身份读取失败 {}: {}", pdf.getFileName(), e.getMessage());
                return Optional.of(PdfIdentity.NONE);
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static PdfIdentity readFirstPage(PDDocument doc) throws IOException {
        String metaTitle = doc.getDocumentInformation() == null ? ""
                : TitleKey.of(doc.getDocumentInformation().getTitle());
        List<Glyph> glyphs = new GlyphCollector().collect(doc);
        if (glyphs.isEmpty()) {
            return new PdfIdentity(null, null, metaTitle, List.of());
        }
        // "Level" is whichever direction most of the page runs in, so a rotated page is read
        // like any other and its body text is not mistaken for a margin stamp.
        Map<Integer, Integer> byDirection = new HashMap<>();
        glyphs.forEach(g -> byDirection.merge(g.direction(), 1, Integer::sum));
        int level = byDirection.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();

        List<Row> rows = rowsOf(glyphs.stream().filter(g -> g.direction() == level).toList());
        return new PdfIdentity(arxivIdOf(glyphs, level, rows), doiOf(rows), metaTitle,
                isReadable(rows) ? titleLinesOf(rows) : List.of());
    }

    private static String arxivIdOf(List<Glyph> glyphs, int level, List<Row> rows) {
        StringBuilder vertical = new StringBuilder();
        glyphs.stream().filter(g -> g.direction() != level)
                .sorted(Comparator.comparingInt(Glyph::direction)
                        .thenComparingInt(g -> Math.round(g.y()))
                        .thenComparingDouble(Glyph::x))
                .forEach(g -> vertical.append(g.text()));
        Matcher stamp = VERTICAL_STAMP.matcher(
                vertical.toString().replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
        if (stamp.find()) {
            return new PaperIdentity(stamp.group(1), null, null, null).arxivId();
        }
        for (Row row : rows) {
            Matcher along = LEVEL_STAMP.matcher(row.text().toLowerCase(Locale.ROOT));
            if (along.find()) {
                return new PaperIdentity(along.group(1), null, null, null).arxivId();
            }
        }
        return null;
    }

    /** The page's one DOI. Several different ones means at least one is a citation, so none is taken. */
    private static String doiOf(List<Row> rows) {
        TreeSet<String> found = new TreeSet<>();
        for (Row row : rows) {
            Matcher doi = DOI.matcher(row.text().toLowerCase(Locale.ROOT));
            while (doi.find()) {
                String value = doi.group().replaceAll("[.,;:()/]+$", "");
                if (!UnpaywallResolver.isArxivDoi(value)) {
                    found.add(value);
                }
            }
        }
        // A DOI broken across two lines also shows up cut short; keep the whole one.
        found.removeIf(value -> found.stream().anyMatch(o -> o.length() > value.length() && o.startsWith(value)));
        return found.size() == 1 ? found.first() : null;
    }

    /**
     * False for text whose font maps glyphs to the wrong letters. Such a page looks like it
     * shows some other title when it shows none that can be read; its letters give it away,
     * because in any language written in Latin script a good third of them are vowels.
     */
    private static boolean isReadable(List<Row> rows) {
        String letters = rows.stream().map(Row::text).collect(java.util.stream.Collectors.joining())
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return letters.length() < 40
                || letters.replaceAll("[^aeiouy]", "").length() >= letters.length() * 0.25;
    }

    private static List<String> titleLinesOf(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        float top = rows.get(0).y();
        float bottom = rows.get(rows.size() - 1).y();
        List<String> lines = new ArrayList<>();
        for (Row row : rows) {
            String key = TitleKey.of(row.text());
            if (row.y() > top + (bottom - top) * TITLE_ZONE && bottom - top > 200
                    || ABSTRACT.matcher(key).matches() || lines.size() >= MAX_TITLE_LINES) {
                break;
            }
            if (key.length() >= 3) {
                // The bar separates lines in the library's index.
                lines.add(row.text().replace('|', ' ').replaceAll("\\s+", " ").strip());
            }
        }
        return lines;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Page geometry
    // ──────────────────────────────────────────────────────────────────────

    private record Glyph(String text, float x, float y, float width, float size, int direction) {
    }

    private record Row(float y, String text) {
    }

    /** Groups level glyphs into lines, top to bottom, with spaces where there are gaps. */
    private static List<Row> rowsOf(List<Glyph> glyphs) {
        List<Glyph> byHeight = new ArrayList<>(glyphs);
        byHeight.sort(Comparator.comparingDouble(Glyph::y));
        List<Row> rows = new ArrayList<>();
        List<Glyph> line = new ArrayList<>();
        for (Glyph glyph : byHeight) {
            if (!line.isEmpty() && glyph.y() - line.get(0).y() > 2.5f) {
                rows.add(rowOf(line));
                line = new ArrayList<>();
            }
            line.add(glyph);
        }
        if (!line.isEmpty()) {
            rows.add(rowOf(line));
        }
        return rows;
    }

    private static Row rowOf(List<Glyph> line) {
        float y = line.get(0).y();
        float largest = (float) line.stream().mapToDouble(Glyph::size).max().orElse(0);
        line.sort(Comparator.comparingDouble(Glyph::x));
        StringBuilder text = new StringBuilder();
        Glyph previous = null;
        for (Glyph glyph : line) {
            if (glyph.size() < largest * SUPERSCRIPT_RATIO) {
                continue;
            }
            if (previous != null && glyph.x() - (previous.x() + previous.width()) > Math.max(1f, glyph.size() * 0.12f)) {
                text.append(' ');
            }
            text.append(glyph.text());
            previous = glyph;
        }
        return new Row(y, text.toString());
    }

    /**
     * Collects page one's glyphs after the stripper has dropped overprinted duplicates (how
     * some PDFs fake bold), which would otherwise double every letter of a title.
     */
    private static final class GlyphCollector extends PDFTextStripper {

        private final List<Glyph> glyphs = new ArrayList<>();

        private GlyphCollector() throws IOException {
            setStartPage(1);
            setEndPage(1);
        }

        private List<Glyph> collect(PDDocument doc) throws IOException {
            getText(doc);
            return glyphs;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            for (TextPosition p : positions) {
                if (p.getUnicode() != null && !p.getUnicode().isBlank()) {
                    glyphs.add(new Glyph(p.getUnicode(), p.getXDirAdj(), p.getYDirAdj(), p.getWidthDirAdj(),
                            Math.abs(p.getFontSizeInPt()), Math.round(p.getDir())));
                }
            }
        }
    }
}

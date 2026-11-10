package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 本地论文库：所有会话、所有课题方向共用的一份论文原件集合。
 *
 * <p>放在 {@code paper-library/papers/}（项目根目录下，与 {@code investigation/} 平级），
 * 而不是放进某个课题目录——课题是"一次调研的工作区"，论文库是"所有调研共用的语料库"。</p>
 *
 * <p>论文只会被<strong>复制</strong>进课题目录，永远不会被移动：一个课题删掉自己的副本，
 * 不能影响别的课题和论文库。下载成功时先落到论文库，再复制进课题目录；下次任何课题
 * 需要同一篇论文时，直接从库里复制，不必再联网。</p>
 *
 * <p>命中判定以论文短名为准，同时维护一份 {@code index.tsv} 记录短名到
 * arXiv ID / DOI 的映射：同一个模型在不同会话里可能给同一篇论文起不同的短名，
 * 这时靠标识符仍然能命中，避免论文库里躺着重复文件而每次调研还在重新下载。</p>
 *
 * <p>人工介入时用户直接把 PDF 拖进 {@code paper-library/papers/} 即可——文件名就是短名，
 * 无需任何额外登记。索引只是加速与去重，不是收录的前提。</p>
 */
@Component
public class PaperLibrary {

    private static final Logger log = LoggerFactory.getLogger(PaperLibrary.class);

    /**
     * Sidecar index: shortName \t arxivId \t doi \t sourceUrl \t title \t stashedArxivId \t stashedDoi.
     *
     * <p>Kept human-readable on purpose. Columns after {@code sourceUrl} are additive: a
     * row written by an older build simply has fewer fields and reads as "no title /
     * not fingerprinted", so an existing index keeps working and is enriched on first
     * scan rather than invalidated.</p>
     */
    private static final String INDEX_FILE = "index.tsv";

    /**
     * Shortest string that can plausibly be a title. Anything shorter is a header, a
     * page number, or a truncated stamp — recording it would make title matching fire on
     * coincidences.
     */
    private static final int MIN_TITLE_CHARS = 12;

    /** Guards reads/writes of the index; downloads can happen on several analysis lanes at once. */
    private final Object indexLock = new Object();

    private final PaperDownloadProperties props;

    /** shortName(lower-case) -> identifier entry. Loaded lazily, refreshed on every store. */
    private volatile Map<String, Entry> index;

    public PaperLibrary(PaperDownloadProperties props) {
        this.props = props;
    }

    /**
     * What the library knows about one stored paper.
     *
     * @param shortName    the file's name without {@code .pdf}
     * @param arxivId      the identifier the download came from, when known
     * @param doi          the DOI the download came from, when known
     * @param sourceUrl    the reference the download came from, empty for manual drops
     * @param title        normalised title read from the PDF itself, empty until fingerprinted
     * @param fingerprint  identifiers read from the PDF itself, null until fingerprinted
     * @param scanned      page one has been read; a "no fingerprint" result is cached too,
     *                     so a scanned-looking PDF without a usable stamp is not re-parsed
     *                     on every lookup
     */
    public record Entry(String shortName, String arxivId, String doi, String sourceUrl,
                        String title, Fingerprint fingerprint, boolean scanned) {
    }

    /**
     * Identifiers recovered from inside a stored PDF.
     *
     * <p>This is the fallback that makes a hand-dropped file findable: the human who puts
     * {@code 2401.12345v2.pdf} or {@code layout-control.pdf} into the library never has to
     * match the model's short name, because the PDF itself says which paper it is. The
     * page-1 text almost always carries an {@code arXiv:} stamp or a DOI — that is exactly
     * where a reader looks for the paper's identity, so it is where the scanner looks too.</p>
     *
     * <p>An entry with no ids but a title is still useful: the model usually knows the
     * title from the abstract page even when no identifier is quoted.</p>
     */
    public record Fingerprint(String arxivId, String doi, String title) {
        boolean isEmpty() {
            return arxivId == null && doi == null && (title == null || title.isBlank());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lookup
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Finds a usable paper already in the library.
     *
     * <p>Three keys are tried, cheapest first:</p>
     * <ol>
     *   <li>the short name as given — the file is literally named that;</li>
     *   <li>a recorded arXiv id or DOI matching one carried by the reference text;</li>
     *   <li>an identifier <em>read out of</em> a stored PDF ({@link Fingerprint}), or a
     *       normalised title match — the path that makes hand-dropped files findable
     *       without the human having to guess the model's short name.</li>
     * </ol>
     *
     * <p>Only parseable PDFs count — a corrupt leftover must not make every future
     * download "succeed" into a bad file.</p>
     *
     * @param shortName the caller's intended file name
     * @param reference the original reference (URL / DOI / name / free text), may be null
     */
    public Optional<Path> find(String shortName, String reference) {
        Path byName = pdfFor(shortName);
        if (isUsable(byName)) {
            return Optional.of(byName);
        }

        // Same paper, different short name: match on whatever identifiers the reference carries.
        Optional<Path> byIdentifier = findByIdentifiers(extractArxivId(reference), extractDoi(reference));
        if (byIdentifier.isPresent()) {
            return byIdentifier;
        }

        // Still nothing: the reference may be a title, or the paper may only be
        // identifiable from inside its own PDF. This is the slow path — it parses page
        // one of un-fingerprinted files — so it runs at most once per file, ever.
        Fingerprint wanted = fingerprintFromReference(reference);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return findByFingerprint(wanted);
    }

    /** Matches a reference-supplied id against recorded ids, in both directions. */
    private Optional<Path> findByIdentifiers(String arxivId, String doi) {
        if (arxivId == null && doi == null) {
            return Optional.empty();
        }
        for (Entry entry : index().values()) {
            if (idMatches(arxivId, entry.arxivId()) || idMatches(doi, entry.doi())
                    || idMatches(arxivId, entry.fingerprint() == null ? null : entry.fingerprint().arxivId())
                    || idMatches(doi, entry.fingerprint() == null ? null : entry.fingerprint().doi())) {
                Path candidate = pdfFor(entry.shortName());
                if (isUsable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The last-resort key: a fingerprint built from whatever the reference says.
     *
     * <p>A URL or bare id gives an id; anything else that is long enough to be prose is
     * treated as a title and normalised the same way a stored title is, so the two can be
     * compared. The id extraction runs on the raw text too, because a model quoting a
     * paper often says "arXiv 2401.12345, 'Title', ..." — the {@code arXiv:} form it
     * writes into a PDF is only one of its spellings.</p>
     */
    static Fingerprint fingerprintFromReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return new Fingerprint(null, null, null);
        }
        String arxivId = extractArxivId(reference);
        String doi = extractDoi(reference);
        String title = null;
        if (arxivId == null && doi == null) {
            String normalised = normaliseTitle(reference);
            if (normalised.length() >= MIN_TITLE_CHARS) {
                title = normalised;
            }
        }
        return new Fingerprint(arxivId, doi, title);
    }

    private Optional<Path> findByFingerprint(Fingerprint wanted) {
        Map<String, Entry> cached = index();
        // Scan files the index has never looked inside. Doing it here — after the cheap
        // keys missed — means ordinary downloads never pay for a PDF parse, and a file
        // dropped in by hand is scanned when it first matters.
        for (String shortName : listShortNames()) {
            Entry entry = cached.get(shortName.toLowerCase(Locale.ROOT));
            if (entry != null && entry.scanned()) {
                continue;
            }
            Entry scanned = scan(shortName);
            if (scanned != null) {
                Map<String, Entry> updated = new LinkedHashMap<>(cached);
                updated.put(scanned.shortName().toLowerCase(Locale.ROOT), scanned);
                synchronized (indexLock) {
                    writeIndex(updated);
                    index = updated;
                }
                cached = updated;
            }
        }

        for (Entry entry : cached.values()) {
            Fingerprint candidate = entry.fingerprint();
            if (candidate == null) {
                continue;
            }
            if ((wanted.arxivId() != null && idMatches(wanted.arxivId(), candidate.arxivId()))
                    || (wanted.doi() != null && idMatches(wanted.doi(), candidate.doi()))
                    || (wanted.title() != null && wanted.title().equals(candidate.title()))) {
                Path path = pdfFor(entry.shortName());
                if (isUsable(path)) {
                    return Optional.of(path);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a stored PDF's identity from its own content.
     *
     * <p>Page one only: the arXiv stamp sits in the left margin of the first page and a
     * publisher DOI in the footer — neither repeats on later pages, and the first page is
     * the cheapest to extract. The title comes from the document's Info dictionary when
     * that looks like a real title; many PDFs carry a blank or a file-producing tool's
     * name there instead, so the first non-empty line of body text is the fallback.</p>
     *
     * @return the enriched entry, or null when page one yields nothing worth recording
     */
    private Entry scan(String shortName) {
        Path pdf = pdfFor(shortName);
        if (!isUsable(pdf)) {
            return null;
        }
        String text;
        String metaTitle;
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            var stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            text = stripper.getText(doc);
            metaTitle = doc.getDocumentInformation() == null
                    ? null : doc.getDocumentInformation().getTitle();
        } catch (IOException | RuntimeException e) {
            log.debug("论文库指纹扫描失败 {}: {}", shortName, e.getMessage());
            return null;
        }

        String arxivId = firstNonNull(extractArxivId(text), arxivIdFromStamp(text));
        String doi = extractDoi(text);
        String title = normaliseTitle(metaTitle);
        if (title.length() < MIN_TITLE_CHARS) {
            title = titleFromBody(text);
        }
        Fingerprint fingerprint = new Fingerprint(arxivId, doi, title);
        Entry existing = index().get(shortName.toLowerCase(Locale.ROOT));
        // An empty fingerprint is still recorded via the scanned flag: this PDF simply
        // does not say what paper it is, and re-reading page one on every later lookup
        // would not change that.
        return new Entry(shortName,
                existing == null ? null : existing.arxivId(),
                existing == null ? null : existing.doi(),
                existing == null ? "" : existing.sourceUrl(),
                title, fingerprint, true);
    }

    /**
     * The {@code arXiv:2401.12345v2 [cs.CV] 12 Jan 2024} stamp on a paper's first page.
     *
     * <p>Deliberately separate from {@link #extractArxivId}: that one recognises the
     * <em>URL</em> form and is applied to references the model supplies; this one
     * recognises the <em>printed stamp</em> and is applied to PDF text. Folding them
     * together would make the URL patterns fire on prose that merely mentions arXiv.</p>
     */
    static String arxivIdFromStamp(String text) {
        if (text == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern
                .compile("arXiv:\\s*([0-9]{4}\\.[0-9]{4,5}(?:v\\d+)?|(?:[a-z-]+(?:\\.[A-Z]{2})?)/[0-9]{7}(?:v\\d+)?)")
                .matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** The paper's title from page-one body text: the first substantial non-stamp line. */
    static String titleFromBody(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            if (stripped.length() < MIN_TITLE_CHARS) {
                continue;
            }
            if (stripped.startsWith("arXiv:") || extractDoi(stripped) != null) {
                continue;
            }
            // A URL is never a title, whether or not it carries a parseable DOI: a
            // link whose DOI is malformed (a preprint mirror, a repository shortlink)
            // would otherwise be recorded as this paper's title and never match anything.
            if (stripped.contains("://") || stripped.toLowerCase(Locale.ROOT).startsWith("doi:")) {
                continue;
            }
            return normaliseTitle(stripped);
        }
        return "";
    }

    /**
     * A comparison key for titles: case-folded, punctuation and spacing removed.
     *
     * <p>Exact matching, not similarity. A stored title and a model-quoted title of the
     * same paper differ in whitespace, line breaks and punctuation far more often than in
     * wording, and a fuzzy match on a short corpus is how the wrong paper gets silently
     * reused — which is worse than a miss, because it surfaces only at close reading.</p>
     */
    static String normaliseTitle(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    /** Id equality that ignores an arXiv version suffix: {@code 2401.1v2} is {@code 2401.1}. */
    private static boolean idMatches(String wanted, String known) {
        if (wanted == null || known == null) {
            return false;
        }
        return stripVersion(wanted).equalsIgnoreCase(stripVersion(known));
    }

    private static String stripVersion(String id) {
        return id.replaceFirst("(?i)v\\d+$", "");
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** Every usable PDF in the library, as short names, sorted. */
    public List<String> listShortNames() {
        Path dir = WorkspacePaths.paperLibraryDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> WorkspacePaths.hasExtension(p.getFileName().toString(), ".pdf"))
                    .filter(this::isUsable)
                    .map(PaperLibrary::shortNameOf)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("无法列出论文库 {}", dir, e);
            return List.of();
        }
    }

    /** Human/model-readable inventory, used by tools that need to see what is reusable. */
    public String describe() {
        List<String> names = listShortNames();
        if (names.isEmpty()) {
            return "本地论文库为空（" + WorkspacePaths.relative(WorkspacePaths.paperLibraryDir()) + "）。";
        }
        StringBuilder sb = new StringBuilder("本地论文库（")
                .append(WorkspacePaths.relative(WorkspacePaths.paperLibraryDir()))
                .append("，共 ").append(names.size()).append(" 篇）：\n");
        for (String name : names) {
            sb.append("- ").append(name).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Import / store
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Copies a library paper into a topic's {@code papers/}.
     *
     * <p>Reuses {@code PaperStore.importPdf}'s validation and dedup rules, so a library
     * copy is held to exactly the same bar as a download.</p>
     *
     * @return the file inside the topic, or empty when the library copy is unusable
     */
    public Optional<Path> importInto(String shortName, String topic) {
        return importInto(shortName, topic, null);
    }

    /**
     * Copies a library paper into a topic's {@code papers/}, using the reference to widen
     * the lookup.
     *
     * <p>The reference matters: without it the search stops at the short name, and a paper
     * the user supplied by hand under the publisher's filename is invisible no matter what
     * the download URL says. With it, the same {@link #find} fallback that rescues the
     * pause check also rescues the ordinary download path.</p>
     *
     * @param reference the URL / DOI / title this run would have downloaded, may be null
     */
    public Optional<Path> importInto(String shortName, String topic, String reference) {
        Optional<Path> source = find(shortName, reference);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PaperStore.importPdf(source.get(), topic, shortName, props.getMaxPdfBytes()));
        } catch (IllegalArgumentException | IOException e) {
            log.warn("从论文库复制到课题失败 [{} -> {}]: {}", shortName, topic, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores downloaded bytes in the library.
     *
     * <p>Validated before it is published: a response that happens to start with
     * {@code %PDF-} but cannot be parsed must not enter the shared corpus, where every
     * later topic would pick it up as a trustworthy paper.</p>
     *
     * <p>Best-effort: a failure here never fails the download itself — the topic copy
     * is what the current investigation needs, the library is what future ones get.</p>
     *
     * @return true when the paper is in the library afterwards
     */
    public boolean store(byte[] bytes, String shortName, String reference) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        String safeName;
        try {
            safeName = WorkspacePaths.slugify(shortName, "论文短名");
        } catch (IllegalArgumentException e) {
            return false;
        }

        Path dir = WorkspacePaths.paperLibraryDir();
        Path target = dir.resolve(safeName + ".pdf").normalize();
        if (!target.startsWith(dir)) {
            return false;
        }

        Path temp = null;
        try {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, ".library-", ".part");
            Files.write(temp, bytes);
            if (pageCountOf(temp) == null) {
                log.warn("论文库拒绝保存无法解析的 PDF：{}", safeName);
                return false;
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            record(safeName, reference);
            log.info("论文已存入本地论文库：{}", WorkspacePaths.relative(target));
            return true;
        } catch (IOException e) {
            log.warn("写入论文库失败 {}: {}", safeName, e.getMessage());
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** Records an index entry for a library paper (identifiers optional). */
    private void record(String shortName, String reference) {
        String arxivId = extractArxivId(reference);
        String doi = extractDoi(reference);
        if (arxivId == null && doi == null) {
            return;
        }
        synchronized (indexLock) {
            Map<String, Entry> current = new LinkedHashMap<>(index());
            Entry previous = current.get(shortName.toLowerCase(Locale.ROOT));
            // Keep any fingerprint an earlier scan found: re-recording the id must not
            // throw away the title, or the next title lookup would pay for a rescan.
            Fingerprint fingerprint = previous == null ? null : previous.fingerprint();
            current.put(shortName.toLowerCase(Locale.ROOT), new Entry(
                    shortName, arxivId, doi, reference == null ? "" : reference.trim(),
                    fingerprint == null ? "" : fingerprint.title(), fingerprint,
                    previous != null && previous.scanned()));
            writeIndex(current);
            index = current;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Index plumbing
    // ──────────────────────────────────────────────────────────────────────

    private Map<String, Entry> index() {
        Map<String, Entry> current = index;
        if (current != null) {
            return current;
        }
        synchronized (indexLock) {
            if (index == null) {
                index = readIndex();
            }
            return index;
        }
    }

    private Map<String, Entry> readIndex() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        Path file = WorkspacePaths.root().resolve(WorkspacePaths.PAPER_LIBRARY_DIR).resolve(INDEX_FILE);
        if (!Files.isRegularFile(file)) {
            return entries;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 1 || parts[0].isBlank()) {
                    continue;
                }
                String title = parts.length > 4 && !parts[4].isBlank() ? parts[4] : null;
                String fpArxiv = parts.length > 5 && !parts[5].isBlank() ? parts[5] : null;
                String fpDoi = parts.length > 6 && !parts[6].isBlank() ? parts[6] : null;
                boolean scanned = parts.length > 7 && "1".equals(parts[7]);
                Fingerprint fingerprint = scanned
                        ? new Fingerprint(fpArxiv, fpDoi, title == null ? "" : title)
                        : null;
                entries.put(parts[0].toLowerCase(Locale.ROOT), new Entry(
                        parts[0],
                        parts.length > 1 && !parts[1].isBlank() ? parts[1] : null,
                        parts.length > 2 && !parts[2].isBlank() ? parts[2] : null,
                        parts.length > 3 ? parts[3] : "",
                        title == null ? "" : title,
                        fingerprint,
                        scanned));
            }
        } catch (IOException | UncheckedIOException e) {
            log.warn("读取论文库索引失败，按空索引处理：{}", e.getMessage());
        }
        return entries;
    }

    private void writeIndex(Map<String, Entry> entries) {
        Path file = WorkspacePaths.root().resolve(WorkspacePaths.PAPER_LIBRARY_DIR).resolve(INDEX_FILE);
        StringBuilder sb = new StringBuilder(
                "# shortName\tarxivId\tdoi\tsourceUrl\ttitle\tfingerprintArxivId\tfingerprintDoi\tscanned\n");
        List<Entry> values = new ArrayList<>(entries.values());
        values.sort((a, b) -> a.shortName().compareToIgnoreCase(b.shortName()));
        for (Entry entry : values) {
            Fingerprint fp = entry.fingerprint();
            sb.append(entry.shortName()).append('\t')
                    .append(entry.arxivId() == null ? "" : entry.arxivId()).append('\t')
                    .append(entry.doi() == null ? "" : entry.doi()).append('\t')
                    .append(entry.sourceUrl() == null ? "" : entry.sourceUrl().replace('\t', ' ')).append('\t')
                    .append(entry.title() == null ? "" : entry.title()).append('\t')
                    .append(fp == null || fp.arxivId() == null ? "" : fp.arxivId()).append('\t')
                    .append(fp == null || fp.doi() == null ? "" : fp.doi()).append('\t')
                    .append(entry.scanned() ? "1" : "")
                    .append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入论文库索引失败：{}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private Path pdfFor(String shortName) {
        if (shortName == null || shortName.isBlank()) {
            return null;
        }
        try {
            return WorkspacePaths.paperLibraryDir()
                    .resolve(WorkspacePaths.slugify(shortName, "论文短名") + ".pdf");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isUsable(Path pdf) {
        return pdf != null && Files.isRegularFile(pdf) && pageCountOf(pdf) != null;
    }

    private Integer pageCountOf(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            return null;
        }
    }

    private static String shortNameOf(Path pdf) {
        String name = pdf.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? name.substring(0, name.length() - 4)
                : name;
    }

    /** arXiv id from a URL/abs/pdf reference, or null. */
    static String extractArxivId(String reference) {
        if (reference == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern
                .compile("arxiv\\.org/(?:abs|pdf)/([0-9]{4}\\.[0-9]{4,5}(?:v\\d+)?|[a-z-]+(?:\\.[A-Z]{2})?/[0-9]{7})")
                .matcher(reference);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** DOI from a URL or bare DOI reference, or null. */
    static String extractDoi(String reference) {
        if (reference == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern
                .compile("(10\\.\\d{4,}/[^\\s?#\"']+)")
                .matcher(reference);
        if (!matcher.find()) {
            return null;
        }
        String doi = matcher.group(1);
        // Trailing punctuation is prose, not part of the identifier.
        return doi.replaceAll("[.,;:)\\]]+$", "");
    }
}

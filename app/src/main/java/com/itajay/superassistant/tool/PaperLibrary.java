package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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

    /** Sidecar index: shortName \t arxivId \t doi \t sourceUrl. Kept human-readable on purpose. */
    private static final String INDEX_FILE = "index.tsv";

    /** Guards reads/writes of the index; downloads can happen on several analysis lanes at once. */
    private final Object indexLock = new Object();

    private final PaperDownloadProperties props;

    /** shortName(lower-case) -> identifier entry. Loaded lazily, refreshed on every store. */
    private volatile Map<String, Entry> index;

    public PaperLibrary(PaperDownloadProperties props) {
        this.props = props;
    }

    /** What the library knows about one stored paper. */
    public record Entry(String shortName, String arxivId, String doi, String sourceUrl) {
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lookup
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Finds a usable paper already in the library.
     *
     * <p>Two keys are tried: the short name as given, then any entry whose arXiv id or
     * DOI matches one carried by the reference text. Only parseable PDFs count — a
     * corrupt leftover must not make every future download "succeed" into a bad file.</p>
     *
     * @param shortName the caller's intended file name
     * @param reference the original reference (URL / DOI / name), may be null
     */
    public Optional<Path> find(String shortName, String reference) {
        Path byName = pdfFor(shortName);
        if (isUsable(byName)) {
            return Optional.of(byName);
        }

        // Same paper, different short name: match on the identifiers the reference carries.
        String arxivId = extractArxivId(reference);
        String doi = extractDoi(reference);
        if (arxivId == null && doi == null) {
            return Optional.empty();
        }
        for (Entry entry : index().values()) {
            if (arxivId != null && arxivId.equalsIgnoreCase(entry.arxivId())) {
                Path candidate = pdfFor(entry.shortName());
                if (isUsable(candidate)) {
                    return Optional.of(candidate);
                }
            }
            if (doi != null && doi.equalsIgnoreCase(entry.doi())) {
                Path candidate = pdfFor(entry.shortName());
                if (isUsable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
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
        Optional<Path> source = find(shortName, null);
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
            current.put(shortName.toLowerCase(Locale.ROOT),
                    new Entry(shortName, arxivId, doi, reference == null ? "" : reference.trim()));
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
                entries.put(parts[0].toLowerCase(Locale.ROOT), new Entry(
                        parts[0],
                        parts.length > 1 && !parts[1].isBlank() ? parts[1] : null,
                        parts.length > 2 && !parts[2].isBlank() ? parts[2] : null,
                        parts.length > 3 ? parts[3] : ""));
            }
        } catch (IOException | UncheckedIOException e) {
            log.warn("读取论文库索引失败，按空索引处理：{}", e.getMessage());
        }
        return entries;
    }

    private void writeIndex(Map<String, Entry> entries) {
        Path file = WorkspacePaths.root().resolve(WorkspacePaths.PAPER_LIBRARY_DIR).resolve(INDEX_FILE);
        StringBuilder sb = new StringBuilder("# shortName\tarxivId\tdoi\tsourceUrl\n");
        List<Entry> values = new ArrayList<>(entries.values());
        values.sort((a, b) -> a.shortName().compareToIgnoreCase(b.shortName()));
        for (Entry entry : values) {
            sb.append(entry.shortName()).append('\t')
                    .append(entry.arxivId() == null ? "" : entry.arxivId()).append('\t')
                    .append(entry.doi() == null ? "" : entry.doi()).append('\t')
                    .append(entry.sourceUrl() == null ? "" : entry.sourceUrl().replace('\t', ' '))
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

package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 本地论文库：所有会话、所有课题方向共用的一份论文原件集合。
 *
 * <p>放在 {@code paper-library/papers/}（项目根目录下，与 {@code investigation/} 平级），
 * 而不是放进某个课题目录——课题是"一次调研的工作区"，论文库是"所有调研共用的语料库"。</p>
 *
 * <p>论文只会被<strong>复制</strong>进课题目录，永远不会被移动：一个课题删掉自己的副本，
 * 不能影响别的课题和论文库。</p>
 *
 * <p><strong>文件名只是标签，不是身份。</strong>短名由模型在每次会话里自己起，两篇不同的论文
 * 可以都叫 {@code Survey}。命中只看论文身份（arXiv 编号 / DOI / 标题，见 {@link PaperMatch}）；
 * 同名的另一篇论文不会被当成命中，入库时也不会被覆盖（见 {@link PaperShelf}）。</p>
 *
 * <p>人工介入时用户把 PDF 拖进来即可，文件名任意：身份从 PDF 首页读取。首页读不出来的
 * （扫描件等），按"短名-标识.pdf"命名就能认出来。</p>
 */
@Component
public class PaperLibrary {

    private static final Logger log = LoggerFactory.getLogger(PaperLibrary.class);

    private static final String INDEX_FILE = "index.tsv";
    private static final String INDEX_HEADER = "# fileName\tsize\tmodified\tsha256\tarxivId\tdoi\turl\ttitle"
            + "\tprintedArxivId\tprintedDoi\tprintedMetaTitle\tprintedLines";
    private static final String LINE_SEPARATOR = " | ";

    private final PaperDownloadProperties props;
    private final Path papers;
    private final Path indexFile;
    private final PaperShelf shelf;

    private final Object lock = new Object();

    /** File name (lower case) -> what is known about it. Null until loaded. */
    private Map<String, Row> rows;

    /**
     * Content hash -> what that content was fetched by. Keyed by content rather than by file,
     * so it survives renames, holds for the copies inside topics, and cannot stick to a file
     * somebody replaced.
     */
    private Map<String, Provenance> provenance;

    private record Row(String fileName, long size, long modified, String sha256, PdfIdentity printed) {
    }

    private record Provenance(long size, PaperIdentity identity) {
    }

    @Autowired
    public PaperLibrary(PaperDownloadProperties props) {
        this(props, WorkspacePaths.paperLibraryDir().getParent());
    }

    /** A library kept elsewhere than the project's own; tests must never touch the real one. */
    public PaperLibrary(PaperDownloadProperties props, Path root) {
        this.props = props;
        this.papers = root.resolve(WorkspacePaths.PAPERS_SUBDIR);
        this.indexFile = root.resolve(INDEX_FILE);
        this.shelf = new PaperShelf(papers, this::describeFile);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lookup
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Finds the wanted paper, whatever its file is called.
     *
     * @param shortName the label the caller would use, or null; only decides when there is
     *                  nothing else to go on (see {@link PaperMatch#acceptsName})
     */
    public Optional<Path> find(String shortName, PaperIdentity wanted) {
        refresh();
        return shelf.find(safeName(shortName), wanted);
    }

    /** What this content was fetched by, wherever the file lies; {@link PaperIdentity#NONE} when unknown. */
    PaperIdentity recordedFor(Path pdf) {
        synchronized (lock) {
            load();
            try {
                long size = Files.size(pdf);
                // Hash only when some recorded content has this very size.
                if (provenance.values().stream().noneMatch(p -> p.size() == size)) {
                    return PaperIdentity.NONE;
                }
                Provenance known = provenance.get(PaperStore.sha256(pdf));
                return known == null ? PaperIdentity.NONE : known.identity();
            } catch (IOException e) {
                return PaperIdentity.NONE;
            }
        }
    }

    /** Human/model-readable inventory: names with what they stand for, because names alone mislead. */
    public String describe() {
        refresh();
        List<Row> present;
        synchronized (lock) {
            present = new ArrayList<>(rows.values());
        }
        String where = WorkspacePaths.relative(papers);
        if (present.isEmpty()) {
            return "本地论文库为空（" + where + "）。";
        }
        present.sort(Comparator.comparing(Row::fileName));
        StringBuilder sb = new StringBuilder("本地论文库（").append(where)
                .append("，共 ").append(present.size()).append(" 篇；名字只是标签，是不是同一篇看标识与标题）：\n");
        for (Row row : present) {
            sb.append("- ").append(PaperStore.shortNameOf(Path.of(row.fileName())))
                    .append(PaperStore.identityNote(row.printed(), recordedByHash(row.sha256()))).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Import / store
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Copies the wanted paper into a topic's {@code papers/}, if the library has it.
     *
     * @return the paper's file inside the topic — possibly one that was already there under
     *         another name — or empty when the library does not have this paper
     */
    public Optional<Path> importInto(String shortName, String topic, PaperIdentity wanted) {
        Optional<Path> source = find(shortName, wanted);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PaperStore.importPdf(source.get(), topic, shortName, wanted,
                    props.getMaxPdfBytes(), this));
        } catch (IllegalArgumentException | IOException e) {
            log.warn("从论文库复制到课题失败 [{} -> {}]: {}", shortName, topic, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Adds a downloaded paper and remembers what it was fetched by.
     *
     * <p>Best-effort: a failure here never fails the download itself — the topic copy is what
     * the current investigation needs, the library is what future ones get.</p>
     *
     * @return true when the paper is in the library afterwards
     */
    public boolean store(Path pdf, String shortName, PaperIdentity fetchedBy) {
        String safeName = safeName(shortName);
        if (safeName == null) {
            return false;
        }
        try {
            refresh();
            Path kept = shelf.add(pdf, safeName, fetchedBy);
            // When another version of this paper was already here, the file that stays must
            // learn these identifiers too, or the next request by them downloads again.
            remember(fetchedBy, kept, pdf);
            log.info("论文已在本地论文库：{}", WorkspacePaths.relative(kept));
            return true;
        } catch (IllegalArgumentException | IOException e) {
            log.warn("写入论文库失败 {}: {}", safeName, e.getMessage());
            return false;
        }
    }

    private void remember(PaperIdentity fetchedBy, Path... copies) throws IOException {
        synchronized (lock) {
            load();
            for (Path pdf : copies) {
                PdfIdentity printed = PdfIdentityReader.identityOf(pdf).orElse(PdfIdentity.NONE);
                String hash = PaperStore.sha256(pdf);
                if (hash == null) {
                    continue;
                }
                Provenance before = provenance.get(hash);
                PaperIdentity identity = fetchedBy.confirmedBy(printed);
                provenance.put(hash, new Provenance(Files.size(pdf),
                        before == null ? identity : identity.or(before.identity())));
            }
            scan();
            write();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Index
    // ──────────────────────────────────────────────────────────────────────

    private Optional<PaperShelf.Known> describeFile(Path pdf) {
        Row row;
        synchronized (lock) {
            load();
            row = rows.get(key(pdf));
        }
        if (row != null && isCurrent(row, pdf)) {
            return Optional.of(new PaperShelf.Known(row.printed(), recordedByHash(row.sha256())));
        }
        return PdfIdentityReader.identityOf(pdf)
                .map(printed -> new PaperShelf.Known(printed, recordedFor(pdf)));
    }

    private PaperIdentity recordedByHash(String hash) {
        synchronized (lock) {
            Provenance known = provenance.get(hash);
            return known == null ? PaperIdentity.NONE : known.identity();
        }
    }

    /** Brings the index in line with the folder, reading only files that are new or changed. */
    private void refresh() {
        synchronized (lock) {
            load();
            if (scan()) {
                write();
            }
        }
    }

    private boolean scan() {
        Map<String, Row> now = new HashMap<>();
        boolean changed = false;
        if (Files.isDirectory(papers)) {
            try (var stream = Files.list(papers)) {
                for (Path pdf : stream.filter(Files::isRegularFile)
                        .filter(p -> WorkspacePaths.hasExtension(p.getFileName().toString(), ".pdf")).toList()) {
                    Row known = rows.get(key(pdf));
                    if (known != null && isCurrent(known, pdf)) {
                        now.put(key(pdf), known);
                        continue;
                    }
                    Row read = readRow(pdf);
                    if (read != null) {
                        now.put(key(pdf), read);
                        changed = true;
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                log.warn("无法列出论文库 {}: {}", papers, e.getMessage());
                return false;
            }
        }
        changed |= now.size() != rows.size();
        rows = now;
        return changed;
    }

    private static Row readRow(Path pdf) {
        Optional<PdfIdentity> printed = PdfIdentityReader.read(pdf);
        String hash = printed.isEmpty() ? null : PaperStore.sha256(pdf);
        if (hash == null) {
            return null;
        }
        try {
            return new Row(pdf.getFileName().toString(), Files.size(pdf),
                    Files.getLastModifiedTime(pdf).toMillis(), hash, printed.get());
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isCurrent(Row row, Path pdf) {
        try {
            return row.size() == Files.size(pdf)
                    && row.modified() == Files.getLastModifiedTime(pdf).toMillis();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Rows in an older layout are skipped, not migrated: their identifiers were tied to file
     * names, so they may describe a paper that has since been overwritten. The files are read
     * again instead.
     */
    private void load() {
        if (rows != null) {
            return;
        }
        Map<String, Row> loadedRows = new HashMap<>();
        Map<String, Provenance> loadedProvenance = new HashMap<>();
        if (Files.isRegularFile(indexFile)) {
            try {
                for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", -1);
                    if (line.startsWith("#") || f.length != 12 || !f[3].matches("[0-9a-f]{64}")) {
                        continue;
                    }
                    long size = Long.parseLong(f[1]);
                    PaperIdentity recorded = new PaperIdentity(f[4], f[5], f[7], f[6]);
                    if (!recorded.isEmpty()) {
                        loadedProvenance.put(f[3], new Provenance(size, recorded));
                    }
                    if (!f[0].isEmpty()) {
                        loadedRows.put(f[0].toLowerCase(Locale.ROOT), new Row(f[0], size, Long.parseLong(f[2]), f[3],
                                new PdfIdentity(blankToNull(f[8]), blankToNull(f[9]), f[10],
                                        f[11].isEmpty() ? List.of()
                                                : List.of(f[11].split(java.util.regex.Pattern.quote(LINE_SEPARATOR))))));
                    }
                }
            } catch (IOException | UncheckedIOException | NumberFormatException e) {
                log.warn("读取论文库索引失败，将重新读取论文：{}", e.getMessage());
            }
        }
        rows = loadedRows;
        provenance = loadedProvenance;
    }

    private void write() {
        StringBuilder sb = new StringBuilder(INDEX_HEADER).append('\n');
        Map<String, Provenance> detached = new TreeMap<>(provenance);
        for (Row row : new TreeMap<>(rows).values()) {
            detached.remove(row.sha256());
            append(sb, row.fileName(), row.size(), row.modified(), row.sha256(),
                    recordedByHash(row.sha256()), row.printed());
        }
        // Content no longer in the library may still lie in a topic; its origin stays known.
        detached.forEach((hash, p) -> append(sb, "", p.size(), 0, hash, p.identity(), PdfIdentity.NONE));
        try {
            Files.createDirectories(indexFile.getParent());
            Files.writeString(indexFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入论文库索引失败：{}", e.getMessage());
        }
    }

    private static void append(StringBuilder sb, String fileName, long size, long modified, String hash,
                               PaperIdentity recorded, PdfIdentity printed) {
        sb.append(String.join("\t", cell(fileName), String.valueOf(size), String.valueOf(modified), hash,
                cell(recorded.arxivId()), cell(recorded.doi()), cell(recorded.url()), cell(recorded.title()),
                cell(printed.arxivId()), cell(printed.doi()), cell(printed.metaTitle()),
                cell(String.join(LINE_SEPARATOR, printed.titleLines())))).append('\n');
    }

    private static String cell(String value) {
        return value == null ? "" : value.replaceAll("[\\t\\r\\n]+", " ");
    }

    private static String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static String key(Path pdf) {
        return pdf.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static String safeName(String shortName) {
        try {
            return shortName == null ? null : WorkspacePaths.slugify(shortName, "论文短名");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

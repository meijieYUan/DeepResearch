package com.itajay.superassistant.tool;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shared helpers for the two paper tools: locating a downloaded PDF, and reporting
 * on the ones a topic already has.
 *
 * <p>Both {@link PaperDownloadTool} and {@link PaperTextTool} need to resolve a
 * paper name to a file and to describe what is already on disk. Keeping that here
 * avoids the two tools drifting apart on path rules — which is the part that has
 * to stay security-correct.</p>
 *
 * <p>Not a Spring component: this holds no state and is never injected.</p>
 */
final class PaperStore {

    private PaperStore() {
    }

    /**
     * The topic's {@code papers/} directory.
     *
     * @throws IllegalArgumentException if the topic has no usable characters
     */
    static Path papersRoot(String topic) {
        return WorkspacePaths.papersDir(topic);
    }

    /**
     * Maps a tool argument (a project-relative path, or a bare short name with or
     * without {@code .pdf}) to a PDF inside the topic's papers directory.
     *
     * @throws IllegalArgumentException if the name is empty or resolves outside the folder
     */
    static Path resolvePdf(String topic, String pathOrName) {
        if (pathOrName == null || pathOrName.isBlank()) {
            throw new IllegalArgumentException("pathOrName is required");
        }
        Path papersRoot = papersRoot(topic);
        String value = pathOrName.trim().replace('\\', '/');

        // A bare name, with or without the .pdf extension.
        if (!value.contains("/")) {
            String name = WorkspacePaths.hasExtension(value, ".pdf") ? value : value + ".pdf";
            Path candidate = papersRoot.resolve(name).normalize();
            if (candidate.startsWith(papersRoot)) {
                return candidate;
            }
        }

        // Otherwise treat it as a project-relative path, but keep it inside the topic's papers dir.
        Path resolved = WorkspacePaths.resolveInsideInvestigation(value);
        if (!resolved.startsWith(papersRoot)) {
            throw new IllegalArgumentException(
                    "path must be inside " + WorkspacePaths.relative(papersRoot) + " (got: " + pathOrName + ")");
        }
        return resolved;
    }

    /**
     * Every downloaded PDF for a topic, sorted by file name. Empty when there is
     * nothing (or the folder does not exist).
     *
     * <p>Returns paths rather than prose because the batch analysis tool drives a
     * loop off this: it has to know exactly which papers exist so it can guarantee
     * each one gets analysed.</p>
     */
    static List<Path> listPdfFiles(String topic) {
        Path papersRoot;
        try {
            papersRoot = papersRoot(topic);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        if (!Files.isDirectory(papersRoot)) {
            return List.of();
        }
        try (var stream = Files.list(papersRoot)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The short name a downloaded PDF is known by, i.e. its file name without {@code .pdf}. */
    static String shortNameOf(Path pdf) {
        String name = pdf.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? name.substring(0, name.length() - ".pdf".length())
                : name;
    }

    /**
     * Renders the list of PDFs a topic already has.
     *
     * <p>Returns a human-readable sentence rather than an empty string when there is
     * nothing yet, because the caller is a model deciding what to do next.</p>
     */
    static String listPdfs(String topic) {
        Path papersRoot;
        try {
            papersRoot = papersRoot(topic);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        if (!Files.isDirectory(papersRoot)) {
            return "No papers downloaded yet for this topic ("
                    + WorkspacePaths.relative(papersRoot) + " does not exist).";
        }

        List<Path> pdfs = listPdfFiles(topic);
        if (pdfs.isEmpty()) {
            return "No papers downloaded yet in " + WorkspacePaths.relative(papersRoot) + ".";
        }

        try {
            StringBuilder sb = new StringBuilder("Downloaded papers for \"")
                    .append(WorkspacePaths.slugify(topic, "课题方向"))
                    .append("\" (").append(pdfs.size()).append("):\n\n");
            for (Path pdf : pdfs) {
                long bytes = Files.size(pdf);
                Integer pages = pageCount(pdf);
                sb.append("- ").append(pdf.getFileName())
                        .append("  (").append(bytes / 1024).append(" KB")
                        .append(", ").append(pages == null ? "unparseable" : pages + " pages").append(")")
                        // A name alone has misled callers into treating another paper as this one.
                        .append(PdfIdentityReader.identityOf(pdf)
                                .map(printed -> identityNote(printed, PaperIdentity.NONE)).orElse(""))
                        .append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "Error listing papers: " + e.getMessage();
        }
    }

    /** Page count, or null when the file is not a parseable PDF. */
    static Integer pageCount(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Copies a PDF into the topic's {@code papers/}, unless the topic already has that paper.
     *
     * <p>Holds the same bar as a download: the file must carry the {@code %PDF-} magic,
     * parse under PDFBox, and fit the size ceiling. A local file is no more trustworthy
     * than a fetched one — it may be an error page saved with a {@code .pdf} name.</p>
     *
     * <p>The short name is a preference, not a key: when the topic already uses it for another
     * paper, the copy gets a distinct name, and when the topic already has this paper under
     * any name, that file is returned and nothing is copied (see {@link PaperShelf}).</p>
     *
     * @param wanted   the paper being asked for
     * @param maxBytes size ceiling; the caller takes it from {@code PaperDownloadProperties}
     * @param library  knows what each content was fetched by; may be null
     * @return the paper's path in {@code papers/}, always parseable
     * @throws IOException when the source is unreadable or the copy fails
     * @throws IllegalArgumentException when the source is not a usable PDF or exceeds the ceiling
     */
    static Path importPdf(Path source, String topic, String shortName, PaperIdentity wanted,
                          long maxBytes, PaperLibrary library) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("本地 PDF 不存在：" + source);
        }
        if (Files.size(source) > maxBytes) {
            throw new IllegalArgumentException("本地 PDF 超过大小上限（" + Files.size(source) + " bytes）：" + source);
        }
        if (!hasPdfMagic(source) || pageCount(source) == null) {
            throw new IllegalArgumentException("本地文件不是可解析的 PDF：" + source);
        }
        PaperShelf shelf = shelf(topic, library);
        Optional<Path> held = shelf.find(null, wanted);
        return held.isPresent() ? held.get()
                : shelf.add(source, WorkspacePaths.slugify(shortName, "论文短名"),
                        library == null ? PaperIdentity.NONE : library.recordedFor(source));
    }

    /** The topic's papers as a shelf, told apart by identity. */
    static PaperShelf shelf(String topic, PaperLibrary library) {
        return shelf(papersRoot(topic), library);
    }

    private static PaperShelf shelf(Path papersDir, PaperLibrary library) {
        return new PaperShelf(papersDir, pdf -> PdfIdentityReader.identityOf(pdf)
                .map(printed -> new PaperShelf.Known(printed,
                        library == null ? PaperIdentity.NONE : library.recordedFor(pdf))));
    }

    /**
     * Finds the wanted paper under every topic's {@code papers/}, so the deep-read workflow can
     * reuse a paper a previous investigation already downloaded instead of fetching it again.
     *
     * <p>In directory-name order, so that a pick is deterministic. A file that merely has the
     * same name is not a hit.</p>
     */
    static List<Path> findAcrossTopics(String shortName, PaperIdentity wanted, PaperLibrary library) {
        String name;
        try {
            name = shortName == null ? null : WorkspacePaths.slugify(shortName, "论文短名");
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        Path root = WorkspacePaths.investigationRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .map(topicDir -> shelf(topicDir.resolve(WorkspacePaths.PAPERS_SUBDIR), library).find(name, wanted))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** One file per different paper: copies and versions of one paper count once. */
    static List<Path> differentPapers(List<Path> pdfs) {
        Map<String, Path> byPaper = new LinkedHashMap<>();
        for (Path pdf : pdfs) {
            String arxiv = PdfIdentityReader.identityOf(pdf).map(PdfIdentity::arxivId).orElse(null);
            byPaper.putIfAbsent(arxiv != null ? arxiv : String.valueOf(sha256(pdf)), pdf);
        }
        return new ArrayList<>(byPaper.values());
    }

    /** What a paper is, for listings: {@code  — arXiv 2304.03373, "Training-Free Layout …"}. */
    static String identityNote(PdfIdentity printed, PaperIdentity recorded) {
        List<String> parts = new ArrayList<>();
        String arxiv = printed.arxivId() != null ? printed.arxivId() : recorded.arxivId();
        if (arxiv != null) {
            parts.add("arXiv " + arxiv);
        }
        String doi = recorded.doi() != null ? recorded.doi() : printed.doi();
        if (doi != null) {
            parts.add("DOI " + doi);
        }
        String heading = recorded.title() != null ? recorded.title() : printed.heading();
        if (!heading.isBlank()) {
            parts.add("\"" + (heading.length() > 120 ? heading.substring(0, 120) + "…" : heading) + "\"");
        }
        return parts.isEmpty() ? "" : " — " + String.join(", ", parts);
    }

    /** Every valid PDF starts with this — same check the downloader applies to fetched bytes. */
    static boolean hasPdfMagic(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(5);
        }
        if (head.length < 5) {
            return false;
        }
        byte[] magic = {'%', 'P', 'D', 'F', '-'};
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** Hex SHA-256 of a file, or null if it cannot be read. */
    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** Project-root-relative path with forward slashes, for tool output. */
    static String relative(Path path) {
        return WorkspacePaths.relative(path);
    }
}

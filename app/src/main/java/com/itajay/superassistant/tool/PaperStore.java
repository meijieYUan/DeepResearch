package com.itajay.superassistant.tool;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
                        .append(", ").append(pages == null ? "unparseable" : pages + " pages").append(")\n");
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
     * Copies a locally available PDF into the topic's {@code papers/}, the same place a
     * download would have landed it. Used by the single-paper deep-read workflow, which
     * must not re-download a paper the user already has on disk.
     *
     * <p>Holds the same bar as a download: the file must carry the {@code %PDF-} magic,
     * parse under PDFBox, and fit the size ceiling. A local file is no more trustworthy
     * than a fetched one — it may be an error page saved with a {@code .pdf} name.</p>
     *
     * <p>If the target already exists <em>and parses</em>, nothing is copied: same dedup
     * stance as {@code downloadPaper}. A target that exists but does not parse is
     * replaced — it cannot be read anyway, and keeping it would poison every later step.</p>
     *
     * @param maxBytes size ceiling; the caller takes it from {@code PaperDownloadProperties}
     * @return the target path in {@code papers/}, always parseable
     * @throws IOException when the source is unreadable or the copy fails
     * @throws IllegalArgumentException when the source is not a usable PDF or exceeds the ceiling
     */
    static Path importPdf(Path source, String topic, String shortName, long maxBytes) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("本地 PDF 不存在：" + source);
        }
        if (Files.size(source) > maxBytes) {
            throw new IllegalArgumentException("本地 PDF 超过大小上限（" + Files.size(source) + " bytes）：" + source);
        }
        if (!hasPdfMagic(source) || pageCount(source) == null) {
            throw new IllegalArgumentException("本地文件不是可解析的 PDF：" + source);
        }

        Path papersRoot = papersRoot(topic);
        String safeName = WorkspacePaths.slugify(shortName, "论文短名");
        Path target = papersRoot.resolve(safeName + ".pdf").normalize();
        if (!target.startsWith(papersRoot)) {
            throw new IllegalArgumentException("resolved path escapes the papers directory: " + target);
        }

        if (Files.isRegularFile(target) && pageCount(target) != null) {
            return target;
        }

        // Temp + move keeps an interrupted copy from leaving a half-written PDF under the
        // final name — same discipline as saveAndReport in the downloader.
        Files.createDirectories(papersRoot);
        Path temp = Files.createTempFile(papersRoot, ".import-", ".part");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
        return target;
    }

    /**
     * Finds a PDF with this short name under any topic's {@code papers/}, so the
     * deep-read workflow can reuse a paper a previous investigation already downloaded
     * instead of fetching it again.
     *
     * <p>Topics are scanned in directory-name order so the pick is deterministic when
     * several topics hold the same file name. Only parseable PDFs count — a corrupt
     * leftover is not a paper worth reusing.</p>
     */
    static Optional<Path> findPdfAcrossTopics(String shortName) {
        String fileName;
        try {
            fileName = WorkspacePaths.slugify(shortName, "论文短名") + ".pdf";
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Path root = WorkspacePaths.investigationRoot();
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (var stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .map(topicDir -> topicDir.resolve(WorkspacePaths.PAPERS_SUBDIR).resolve(fileName))
                    .filter(Files::isRegularFile)
                    .filter(p -> pageCount(p) != null)
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Every valid PDF starts with this — same check the downloader applies to fetched bytes. */
    private static boolean hasPdfMagic(Path file) throws IOException {
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

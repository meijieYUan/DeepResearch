package com.itajay.superassistant.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Canonical locations for research output, shared by every tool that reads or
 * writes under {@code investigation/}.
 *
 * <p>Layout:</p>
 * <pre>
 * {项目根目录}/investigation/{课题方向}/
 *     ├── papers/{论文短名}.pdf                    下载的论文原件
 *     ├── analysis/{论文短名}.md                   单篇论文的精读与四维信息提取结果
 *     ├── analysis/figures/{论文短名}_Fig{N}.png    从该论文 PDF 截出的图片
 *     └── document/{文档名}.md                     生成的调研文档
 * </pre>
 *
 * <p>The figures live under {@code analysis/} rather than beside {@code papers/}
 * because they belong to one paper's close reading. Two consumers reference them by
 * different relative paths — the analysis file as {@code figures/X.png}, the document
 * as {@code ../analysis/figures/X.png} — and only the extraction tool ever builds
 * either string (see {@code PaperFigureTool}); nothing is left to a model's guess.</p>
 *
 * <p>{@code {N}} in a figure's name is the number the paper itself gives it, taken from the
 * caption that sits against the figure. It is not a position in an extracted list: the two are
 * different orders, and only the caption knows the first one.</p>
 *
 * <p>The topic directory is the unit of isolation. It is deliberately <em>not</em>
 * keyed by thread id: a second conversation researching the same topic should find
 * the papers already downloaded rather than fetching them again.</p>
 */
public final class WorkspacePaths {

    /** Root folder holding all research output. */
    public static final String INVESTIGATION_DIR = "investigation";
    public static final String PAPERS_SUBDIR = "papers";
    public static final String ANALYSIS_SUBDIR = "analysis";
    public static final String FIGURES_SUBDIR = "figures";
    public static final String DOCUMENTS_SUBDIR = "document";

    private static final int MAX_SEGMENT_LENGTH = 80;

    /** Resolved once: walking the filesystem on every tool call would be wasteful. */
    private static final Path ROOT = resolveRoot();

    private WorkspacePaths() {
    }

    /**
     * Project root: the nearest ancestor of the working directory that looks like a
     * repository root, falling back to the working directory itself.
     *
     * <p>Resolving this rather than trusting {@code user.dir} matters because an IDE
     * or a {@code spring-boot:run} invocation may start in a submodule, which would
     * otherwise scatter output into {@code app/investigation/}.</p>
     */
    public static Path root() {
        return ROOT;
    }

    private static Path resolveRoot() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path candidate = start;
        for (int depth = 0; depth < 10 && candidate != null; depth++) {
            // A file rather than a directory in git worktrees, so test for existence.
            if (Files.exists(candidate.resolve(".git"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return start;
    }

    /** {@code investigation/{topic}} */
    public static Path topicDir(String topic) {
        return root().resolve(INVESTIGATION_DIR).resolve(slugify(topic, "课题方向")).normalize();
    }

    /** {@code investigation/{topic}/papers} */
    public static Path papersDir(String topic) {
        return topicDir(topic).resolve(PAPERS_SUBDIR).normalize();
    }

    /** {@code investigation/{topic}/analysis} */
    public static Path analysisDir(String topic) {
        return topicDir(topic).resolve(ANALYSIS_SUBDIR).normalize();
    }

    /**
     * {@code investigation/{topic}/analysis/figures} — the images extracted from one
     * paper's PDF, stored beside that paper's analysis file.
     */
    public static Path figuresDir(String topic) {
        return analysisDir(topic).resolve(FIGURES_SUBDIR).normalize();
    }

    /** {@code investigation/{topic}/document} */
    public static Path documentsDir(String topic) {
        return topicDir(topic).resolve(DOCUMENTS_SUBDIR).normalize();
    }

    /** The {@code investigation} folder itself, used as the boundary for path checks. */
    public static Path investigationRoot() {
        return root().resolve(INVESTIGATION_DIR).normalize();
    }

    /**
     * Reduces arbitrary model-supplied text to a single safe path segment.
     *
     * <p>Separators are replaced and dot runs collapsed, so the result can never
     * climb out of its parent directory or smuggle in a nested path. CJK characters
     * are preserved, since topics are frequently Chinese.</p>
     *
     * @param what label used in the error message, e.g. "课题方向"
     * @throws IllegalArgumentException if nothing usable remains
     */
    public static String slugify(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(what + " 不能为空");
        }
        String slug = raw.trim()
                .replace('\\', '-')
                .replace('/', '-')
                .replaceAll("[^\\p{L}\\p{N}._-]", "-")
                .replaceAll("-{2,}", "-")
                // Collapse dot runs so no ".." survives anywhere in the name.
                .replaceAll("\\.{2,}", ".")
                .replaceAll("^[.\\-_]+", "")
                .replaceAll("[.\\-_]+$", "");

        if (slug.isBlank()) {
            throw new IllegalArgumentException(what + " 不含可用字符：" + raw);
        }
        if (slug.length() > MAX_SEGMENT_LENGTH) {
            slug = slug.substring(0, MAX_SEGMENT_LENGTH);
        }
        return slug;
    }

    /**
     * Confines a caller-supplied path to {@code investigation/}, for reads that may
     * legitimately cross topics.
     *
     * @throws IllegalArgumentException if the path escapes the investigation folder
     */
    public static Path resolveInsideInvestigation(String pathOrName) {
        if (pathOrName == null || pathOrName.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        String value = pathOrName.trim().replace('\\', '/');
        Path resolved = root().resolve(value).normalize();
        if (!resolved.startsWith(investigationRoot())) {
            throw new IllegalArgumentException(
                    "路径必须位于 " + INVESTIGATION_DIR + "/ 目录内：" + pathOrName);
        }
        return resolved;
    }

    /** Renders a path relative to the project root with forward slashes, for tool output. */
    public static String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return root().relativize(normalized).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return normalized.toString().replace('\\', '/');
        }
    }

    /** True when the file name already carries the given extension, ignoring case. */
    public static boolean hasExtension(String name, String extension) {
        return name.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT));
    }
}

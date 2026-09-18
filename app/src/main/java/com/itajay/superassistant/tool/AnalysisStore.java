package com.itajay.superassistant.tool;

import com.itajay.superassistant.workspace.WorkspacePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared helpers for the per-paper analysis files: locating one, listing a topic's,
 * and reading back the metadata header.
 *
 * <p>Sibling of {@link PaperStore}, and for the same reason — three tools plus the
 * workflow all need to agree on how a paper short name maps to a file under
 * {@code analysis/}, and on how the header {@link AnalysisWriteTool} writes is
 * parsed. Path rules and header parsing are the parts that have to stay correct, so
 * they live in one place rather than four.</p>
 *
 * <p>Public rather than package-private because the workflow reads the inventory to
 * report coverage and to tell the reviewer what there is to check.</p>
 */
public final class AnalysisStore {

    private static final String MARKDOWN_EXTENSION = ".md";
    private static final String PNG_EXTENSION = ".png";
    private static final String SOURCE_LEVEL_MARKER = "> 来源等级：";
    private static final String SUMMARY_MARKER = "> 一句话定位：";

    /** Recorded when a file cannot be read or carries no recognised header. */
    private static final String UNKNOWN_LEVEL = "UNKNOWN";

    private AnalysisStore() {
    }

    /**
     * What an analysis file says about itself.
     *
     * @param shortName   the paper's short name
     * @param sourceLevel how much of the paper was readable when it was written
     * @param summary     the one-line positioning, or an empty string
     * @param chars       the file's character count
     */
    public record AnalysisMeta(String shortName, String sourceLevel, String summary, int chars) {
    }

    /**
     * The topic's {@code analysis/} directory.
     *
     * @throws IllegalArgumentException if the topic has no usable characters
     */
    public static Path analysisRoot(String topic) {
        return WorkspacePaths.analysisDir(topic);
    }

    /**
     * The file name a paper's analysis is stored under.
     *
     * <p>Derived from the paper short name — the same name the PDF uses — so the
     * pair {@code papers/X.pdf} / {@code analysis/X.md} is obvious to a human
     * browsing the folder.</p>
     *
     * @throws IllegalArgumentException if the short name has no usable characters
     */
    public static String nameFor(String paperShortName) {
        String slug = WorkspacePaths.slugify(paperShortName, "论文短名");
        return WorkspacePaths.hasExtension(slug, MARKDOWN_EXTENSION)
                ? slug.substring(0, slug.length() - MARKDOWN_EXTENSION.length())
                : slug;
    }

    /**
     * Maps a tool argument (a project-relative path, or a bare short name with or
     * without {@code .md}) to a file inside the topic's analysis directory.
     *
     * @throws IllegalArgumentException if the name is empty or resolves outside the folder
     */
    public static Path resolveMarkdown(String topic, String pathOrName) {
        if (pathOrName == null || pathOrName.isBlank()) {
            throw new IllegalArgumentException("论文短名不能为空");
        }
        Path analysisRoot = analysisRoot(topic);
        String value = pathOrName.trim().replace('\\', '/');

        // A bare name, with or without the .md extension.
        if (!value.contains("/")) {
            Path candidate = analysisRoot.resolve(nameFor(value) + MARKDOWN_EXTENSION).normalize();
            if (candidate.startsWith(analysisRoot)) {
                return candidate;
            }
        }

        // Otherwise treat it as a project-relative path, but keep it inside this topic's analysis dir.
        Path resolved = WorkspacePaths.resolveInsideInvestigation(value);
        if (!resolved.startsWith(analysisRoot)) {
            throw new IllegalArgumentException(
                    "路径必须位于 " + WorkspacePaths.relative(analysisRoot) + " 内（收到：" + pathOrName + "）");
        }
        return resolved;
    }

    /**
     * Every analysis file a topic has, sorted by file name. Empty when the directory
     * does not exist yet.
     *
     * <p>Returns paths rather than prose because the callers act on them: the batch
     * tool skips the ones already present, and the workflow lists them for the
     * reviewer. Neither needs a model-facing sentence.</p>
     */
    public static List<Path> markdownFiles(String topic) {
        Path analysisRoot;
        try {
            analysisRoot = analysisRoot(topic);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        if (!Files.isDirectory(analysisRoot)) {
            return List.of();
        }
        try (var stream = Files.list(analysisRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> WorkspacePaths.hasExtension(p.getFileName().toString(), MARKDOWN_EXTENSION))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Every figure a topic has extracted, sorted by file name. Empty when nothing was
     * extracted yet.
     *
     * <p>Used by the workflow to hand the reviewer a concrete list to check the
     * document's {@code ![..](../analysis/figures/..)} references against, so a broken
     * link is a finding rather than a guess. Only depth 1 is listed: the extraction
     * tool is the only writer and it never nests.</p>
     */
    public static List<Path> figureFiles(String topic) {
        Path figuresDir;
        try {
            figuresDir = WorkspacePaths.figuresDir(topic);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        if (!Files.isDirectory(figuresDir)) {
            return List.of();
        }
        try (var stream = Files.list(figuresDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> WorkspacePaths.hasExtension(p.getFileName().toString(), PNG_EXTENSION))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The short name a stored analysis file belongs to, i.e. its name without {@code .md}. */
    public static String shortNameOf(Path file) {
        String name = file.getFileName().toString();
        return WorkspacePaths.hasExtension(name, MARKDOWN_EXTENSION)
                ? name.substring(0, name.length() - MARKDOWN_EXTENSION.length())
                : name;
    }

    /**
     * Reads an analysis file's header.
     *
     * <p>Read from disk rather than remembered from the agent's reply, so callers
     * describe what was actually stored. The markers are written by
     * {@link AnalysisWriteTool} in a fixed format, so this is a lookup and not a
     * guess; a file without them reports {@code UNKNOWN} rather than pretending.</p>
     */
    public static AnalysisMeta readMeta(Path file) {
        String shortName = shortNameOf(file);
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new AnalysisMeta(shortName, UNKNOWN_LEVEL, "", 0);
        }
        String level = markerValue(content, SOURCE_LEVEL_MARKER);
        String summary = markerValue(content, SUMMARY_MARKER);
        return new AnalysisMeta(shortName,
                level == null ? UNKNOWN_LEVEL : level,
                summary == null ? "" : summary,
                content.length());
    }

    /** True when the level means the paper's body was never actually readable. */
    public static boolean isAbstractOnly(String sourceLevel) {
        return !"FULL_TEXT".equalsIgnoreCase(sourceLevel);
    }

    private static String markerValue(String content, String marker) {
        // The header is the first few lines; bounding the scan keeps a stray "> 来源等级："
        // in the body from being mistaken for the header.
        for (String line : content.split("\n", 12)) {
            if (line.startsWith(marker)) {
                return line.substring(marker.length()).trim();
            }
        }
        return null;
    }
}

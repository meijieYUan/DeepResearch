package com.itajay.superassistant.workspace;

import com.itajay.superassistant.tool.AnalysisStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 已有调研方向的清单，从磁盘读出来。
 *
 * <p>「课题方向就是一个目录」这条约定早就在 {@code investigation/} 下了，但此前没有任何地方
 * 把这份现状交回给调用方：用户想接着上次的方向继续调研，只能自己记住并拼出完全一致的目录名，
 * 而目录名是模型从 brief 里截出来的，人能记住的往往和实际落地的不一样。这个类把目录本身
 * 当作权威来源，让前端有东西可选、让续调研拿到的方向名逐字正确。</p>
 *
 * <p>只做枚举与统计，不判断方向"好不好"——那需要读文档内容，而这份清单的用途是选目录。</p>
 */
@Component
public class TopicCatalog {

    private static final Logger log = LoggerFactory.getLogger(TopicCatalog.class);

    /**
     * 一个已有方向。
     *
     * @param topic        目录名，续调研时逐字使用
     * @param papers       已下载论文数
     * @param analyses     已有精读结果数
     * @param hasDocument  是否已产出调研文档
     * @param documentPath 主文档的项目相对路径，没有时为 null
     * @param updatedAt    目录最后修改时间，用于排序；读不出来时为 null
     */
    public record TopicSummary(String topic, int papers, int analyses, boolean hasDocument,
                               String documentPath, Instant updatedAt) {
    }

    /**
     * 所有方向，最近动过的排在前面。
     *
     * <p>按修改时间倒序：用户要"接着上次那个继续"时，上次那个几乎总在最前面。</p>
     */
    public List<TopicSummary> list() {
        Path root = WorkspacePaths.investigationRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<TopicSummary> topics = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                // 以点开头的目录是工具留下的临时目录，不是方向。
                if (name.startsWith(".")) {
                    continue;
                }
                topics.add(summarise(name, dir));
            }
        } catch (IOException e) {
            log.warn("无法列出调研方向 {}", root, e);
            return List.of();
        }
        topics.sort(Comparator.comparing(
                (TopicSummary t) -> t.updatedAt() == null ? Instant.EPOCH : t.updatedAt()).reversed());
        return topics;
    }

    private TopicSummary summarise(String topic, Path dir) {
        int papers = countFiles(WorkspacePaths.papersDir(topic), ".pdf");
        int analyses = AnalysisStore.markdownFiles(topic).size();

        Path document = documentOf(topic);
        String documentPath = document == null ? null : WorkspacePaths.relative(document);
        return new TopicSummary(topic, papers, analyses, document != null,
                documentPath, lastModified(dir));
    }

    /**
     * 该方向的主文档，没有则 null。
     *
     * <p>文档名由 {@code WorkspacePaths.slugify(方向)} 决定，与工作流写文档时的规则相同；
     * 这里不猜别的文件名，因为工作流保证它只写这一个。</p>
     */
    public static Path documentOf(String topic) {
        Path candidate;
        try {
            candidate = WorkspacePaths.documentsDir(topic)
                    .resolve(WorkspacePaths.slugify(topic, "文档名") + ".md");
        } catch (IllegalArgumentException e) {
            return null;
        }
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /** 目录里最新的一次修改时间，包含子目录；读不出来时返回 null。 */
    private static Instant lastModified(Path dir) {
        try (var paths = Files.walk(dir, 3)) {
            return paths
                    .map(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant();
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static int countFiles(Path dir, String extension) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(p -> WorkspacePaths.hasExtension(p.getFileName().toString(), extension))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }
}

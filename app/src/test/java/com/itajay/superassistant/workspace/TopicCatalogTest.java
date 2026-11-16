package com.itajay.superassistant.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The direction listing: what the frontend offers as "continue an existing
 * investigation", and what a continuation must match verbatim.
 */
class TopicCatalogTest {

    private final String topic = "catalog-topic-" + UUID.randomUUID();
    private final TopicCatalog catalog = new TopicCatalog();

    @AfterEach
    void cleanUp() throws IOException {
        deleteTopic(topic);
    }

    @Test
    void aDirectionIsListedWithWhatItAlreadyContains() throws IOException {
        Files.createDirectories(WorkspacePaths.papersDir(topic));
        Files.write(WorkspacePaths.papersDir(topic).resolve("Alpha.pdf"), new byte[]{1});
        Files.write(WorkspacePaths.papersDir(topic).resolve("Beta.pdf"), new byte[]{1});
        Files.createDirectories(WorkspacePaths.analysisDir(topic));
        Files.writeString(WorkspacePaths.analysisDir(topic).resolve("Alpha.md"), "# Alpha");

        TopicCatalog.TopicSummary summary = findSummary(topic);

        assertThat(summary.papers()).isEqualTo(2);
        assertThat(summary.analyses()).isEqualTo(1);
        assertThat(summary.hasDocument()).isFalse();
        assertThat(summary.documentPath()).isNull();
        assertThat(summary.updatedAt()).isNotNull();
    }

    @Test
    void anExistingDocumentIsReportedAtThePathTheWorkflowWrites() throws IOException {
        Path document = WorkspacePaths.documentsDir(topic)
                .resolve(WorkspacePaths.slugify(topic, "文档名") + ".md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "# 调研文档");

        TopicCatalog.TopicSummary summary = findSummary(topic);

        assertThat(summary.hasDocument()).isTrue();
        assertThat(summary.documentPath()).isEqualTo(WorkspacePaths.relative(document));
    }

    /**
     * The listing is what a continuation matches against, so the name must be the folder
     * name itself — not a slugified or prettified variant.
     */
    @Test
    void aChineseDirectionKeepsItsFolderNameVerbatim() throws IOException {
        String chinese = "多主体布局控制-" + UUID.randomUUID();
        try {
            Files.createDirectories(WorkspacePaths.topicDir(chinese));

            assertThat(catalog.list()).extracting(TopicCatalog.TopicSummary::topic).contains(chinese);
        } finally {
            deleteTopic(chinese);
        }
    }

    /** Tool scratch directories are not directions and must not appear in the picker. */
    @Test
    void dottedDirectoriesAreNotDirections() throws IOException {
        Path hidden = WorkspacePaths.investigationRoot().resolve(".scratch-" + UUID.randomUUID());
        Files.createDirectories(hidden);
        try {
            assertThat(catalog.list()).extracting(TopicCatalog.TopicSummary::topic)
                    .noneMatch(name -> name.startsWith("."));
        } finally {
            Files.deleteIfExists(hidden);
        }
    }

    @Test
    void anEmptyInvestigationFolderListsNothing() {
        // No assumption about the working tree's contents beyond "does not throw".
        assertThat(catalog.list()).isNotNull();
    }

    private TopicCatalog.TopicSummary findSummary(String name) {
        List<TopicCatalog.TopicSummary> all = catalog.list();
        return all.stream().filter(t -> t.topic().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("topic not listed: " + name));
    }

    private static void deleteTopic(String name) {
        Path dir = WorkspacePaths.topicDir(name);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }
}

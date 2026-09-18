package com.itajay.superassistant.tool;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DocumentWriteTool}: correct placement, and confinement to the
 * topic's {@code document/} folder.
 */
class DocumentWriteToolTest {

    private static final String TOPIC = "unit-test-doc-topic";

    private final DocumentWriteTool tool = new DocumentWriteTool();

    @AfterEach
    void cleanUp() throws IOException {
        Path topicDir = WorkspacePaths.topicDir(TOPIC);
        if (!Files.isDirectory(topicDir)) {
            return;
        }
        try (var paths = Files.walk(topicDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    @Test
    void writesToTheDocumentedLocation() {
        String result = tool.writeResearchDocument(TOPIC, "多主体布局控制调研", "# 标题\n\n正文");

        assertThat(result).startsWith("OK: written");
        assertThat(result).contains("investigation/" + TOPIC + "/document/多主体布局控制调研.md");

        Path expected = WorkspacePaths.documentsDir(TOPIC).resolve("多主体布局控制调研.md");
        assertThat(expected).isRegularFile();
        assertThat(expected).content().isEqualTo("# 标题\n\n正文");
    }

    @Test
    void appendsTheMarkdownExtensionOnlyOnce() {
        assertThat(tool.writeResearchDocument(TOPIC, "report.md", "x")).contains("report.md\n");
        assertThat(WorkspacePaths.documentsDir(TOPIC).resolve("report.md")).isRegularFile();
        // Must not create report.md.md
        assertThat(WorkspacePaths.documentsDir(TOPIC).resolve("report.md.md")).doesNotExist();
    }

    @Test
    void overwritesAnExistingDocumentRatherThanFailing() {
        tool.writeResearchDocument(TOPIC, "revised", "first");
        String second = tool.writeResearchDocument(TOPIC, "revised", "second");

        assertThat(second).startsWith("OK: overwritten");
        assertThat(WorkspacePaths.documentsDir(TOPIC).resolve("revised.md"))
                .content().isEqualTo("second");
    }

    @Test
    void rejectsEmptyContent() {
        assertThat(tool.writeResearchDocument(TOPIC, "empty", "   ")).startsWith("Error:");
        assertThat(tool.writeResearchDocument(TOPIC, "empty", null)).startsWith("Error:");
        assertThat(WorkspacePaths.documentsDir(TOPIC).resolve("empty.md")).doesNotExist();
    }

    @Test
    void hostileNamesCannotEscapeTheDocumentDirectory() {
        Path documentsDir = WorkspacePaths.documentsDir(TOPIC);
        Path investigation = WorkspacePaths.investigationRoot();

        tool.writeResearchDocument(TOPIC, "../../../pwned", "x");
        tool.writeResearchDocument(TOPIC, "..", "x");

        // Nothing may appear outside the topic's document folder.
        assertThat(WorkspacePaths.root().resolve("pwned.md")).doesNotExist();
        assertThat(WorkspacePaths.root().resolve("pwned")).doesNotExist();
        assertThat(documentsDir).startsWith(investigation);
        assertThat(documentsDir.toString()).doesNotContain("..");
    }

    @Test
    void hostileTopicIsConfinedToInvestigation() {
        // Strings rather than PathAssert: these paths do not exist, and startsWith
        // canonicalizes (and therefore throws) for missing paths.
        String resolved = WorkspacePaths.documentsDir("../../evil").toString();
        assertThat(resolved).startsWith(WorkspacePaths.investigationRoot().toString());
        assertThat(resolved).doesNotContain("..");
    }

    @Test
    void reportsAnErrorRatherThanThrowingForAnUnusableTopic(@TempDir Path unused) {
        assertThat(tool.writeResearchDocument("   ", "name", "content")).startsWith("Error:");
        assertThat(tool.writeResearchDocument(null, "name", "content")).startsWith("Error:");
    }
}

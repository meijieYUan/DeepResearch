package com.itajay.superassistant.workspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the shared research-output path rules.
 *
 * <p>The security-relevant invariant is that a model-supplied topic or file name can
 * never place a file outside {@code investigation/{topic}/}.</p>
 */
class WorkspacePathsTest {

    @Test
    void rootIsTheProjectRootNotTheWorkingDirectory() {
        // The working directory during tests is app/, so walking up to .git must find
        // the repository root. Otherwise output would land in app/investigation/.
        assertThat(WorkspacePaths.root().resolve(".git")).exists();
        assertThat(WorkspacePaths.root().getFileName().toString()).isEqualTo("SuperAssistant");
    }

    @Test
    void buildsTheDocumentedLayout() {
        Path topic = WorkspacePaths.topicDir("多主体布局控制");

        assertThat(WorkspacePaths.relative(topic)).isEqualTo("investigation/多主体布局控制");
        assertThat(WorkspacePaths.relative(WorkspacePaths.papersDir("多主体布局控制")))
                .isEqualTo("investigation/多主体布局控制/papers");
        assertThat(WorkspacePaths.relative(WorkspacePaths.documentsDir("多主体布局控制")))
                .isEqualTo("investigation/多主体布局控制/document");
    }

    @Test
    void theSameTopicAlwaysResolvesToTheSameDirectory() {
        // This is what makes papers reusable across conversations.
        assertThat(WorkspacePaths.papersDir("多主体布局控制"))
                .isEqualTo(WorkspacePaths.papersDir("多主体布局控制"));
        // Whitespace differences must not create a second folder.
        assertThat(WorkspacePaths.papersDir("  多主体布局控制  "))
                .isEqualTo(WorkspacePaths.papersDir("多主体布局控制"));
    }

    @Test
    void slugifyKeepsCjkButStripsTraversalAndSeparators() {
        assertThat(WorkspacePaths.slugify("多主体布局控制", "课题方向")).isEqualTo("多主体布局控制");
        assertThat(WorkspacePaths.slugify("../../evil", "课题方向")).isEqualTo("evil");
        assertThat(WorkspacePaths.slugify("/etc/passwd", "课题方向")).isEqualTo("etc-passwd");
        assertThat(WorkspacePaths.slugify("a\\b", "课题方向")).isEqualTo("a-b");
        assertThat(WorkspacePaths.slugify("C:\\windows\\x", "课题方向")).isEqualTo("C-windows-x");
        assertThat(WorkspacePaths.slugify("Multi-Subject Layout", "课题方向"))
                .isEqualTo("Multi-Subject-Layout");
    }

    @Test
    void slugifyNeverLeavesASeparatorOrTraversal() {
        for (String hostile : new String[]{"../../evil", "/etc/passwd", "a\\b", "./../x",
                "C:\\windows\\x", "....//....//x", "a/../../b", "..%2f..%2fx"}) {
            String slug = WorkspacePaths.slugify(hostile, "课题方向");
            assertThat(slug).doesNotContain("/").doesNotContain("\\").doesNotContain("..");
            assertThat(slug).isNotBlank();
        }
    }

    @Test
    void slugifyRejectsUnusableInput() {
        assertThatThrownBy(() -> WorkspacePaths.slugify(null, "课题方向"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspacePaths.slugify("   ", "课题方向"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspacePaths.slugify("..", "课题方向"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspacePaths.slugify("///", "课题方向"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void topicDirectoryStaysInsideInvestigationForHostileInput() {
        // Compared as strings: these paths do not exist on disk, and PathAssert.startsWith
        // canonicalizes (and therefore throws) for missing paths.
        String investigation = WorkspacePaths.investigationRoot().toString();
        for (String hostile : new String[]{"../../evil", "/etc/passwd", "a/../../b", "C:\\windows"}) {
            String resolved = WorkspacePaths.topicDir(hostile).toString();
            assertThat(resolved).startsWith(investigation);
            assertThat(resolved).doesNotContain("..");
        }
    }

    @Test
    void resolveInsideInvestigationAcceptsValidPathsAndRejectsEscapes() {
        assertThat(WorkspacePaths.resolveInsideInvestigation(
                "investigation/多主体布局控制/papers/x.pdf"))
                .isEqualTo(WorkspacePaths.papersDir("多主体布局控制").resolve("x.pdf"));

        assertThatThrownBy(() -> WorkspacePaths.resolveInsideInvestigation("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspacePaths.resolveInsideInvestigation("app/src/main/resources"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspacePaths.resolveInsideInvestigation("investigation/../../x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasExtensionIgnoresCase() {
        assertThat(WorkspacePaths.hasExtension("x.pdf", ".pdf")).isTrue();
        assertThat(WorkspacePaths.hasExtension("x.PDF", ".pdf")).isTrue();
        assertThat(WorkspacePaths.hasExtension("x", ".pdf")).isFalse();
    }
}

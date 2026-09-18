package com.itajay.superassistant.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for pulling the saved document's path out of the writer's reply.
 *
 * <p>This parse decides what the reviewer is shown. Getting it wrong is not a
 * cosmetic bug: a miss sends the writer's "saved" confirmation to the reviewer
 * instead of the document, and the review gate then has nothing to audit. So the
 * cases below are mostly about rejecting near-misses rather than accepting the
 * happy path.</p>
 */
class DocumentPathExtractionTest {

    @Test
    void readsThePathFromTheToolConfirmation() {
        String output = """
                OK: written
                path: investigation/training-free-layout-control/document/survey.md
                chars: 19303
                topicDir: investigation/training-free-layout-control""";

        assertThat(ResearchWriteReviewWorkflow.extractDocumentPath(output))
                .isEqualTo("investigation/training-free-layout-control/document/survey.md");
    }

    @Test
    void stripsBackticksAndTrailingPunctuation() {
        // Models quote paths, and Chinese prose tends to glue punctuation onto them.
        assertThat(ResearchWriteReviewWorkflow.extractDocumentPath(
                "文档已保存到 `investigation/布局控制/document/调研.md`。"))
                .isEqualTo("investigation/布局控制/document/调研.md");
    }

    @Test
    void ignoresAPassingMentionOfTheFolder() {
        // Mentioning the folder is not the same as naming a document.
        assertThat(ResearchWriteReviewWorkflow.extractDocumentPath(
                "I wrote it under investigation/ as requested."))
                .isNull();
    }

    @Test
    void rejectsPathsThatAreNotMarkdown() {
        assertThat(ResearchWriteReviewWorkflow.extractDocumentPath(
                "downloaded investigation/布局控制/papers/BoxDiff.pdf"))
                .isNull();
    }

    @Test
    void returnsNullWhenNothingWasReported() {
        assertThat(ResearchWriteReviewWorkflow.extractDocumentPath("I forgot to save.")).isNull();
        assertThat(ResearchWriteReviewWorkflow.extractDocumentPath(null)).isNull();
    }
}

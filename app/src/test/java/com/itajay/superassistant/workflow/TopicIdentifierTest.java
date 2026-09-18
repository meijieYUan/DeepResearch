package com.itajay.superassistant.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for splitting a run's folder identity out of the caller's brief.
 *
 * <p>These pin the fix for a failure that did not look like a failure. One run was given a brief
 * whose opening segment named a topic; the research guide defines 课题方向 as the precise
 * description of a topic, so the research agent narrowed the sentence to a folder name and
 * downloaded there, and the writer followed the paths in the material index. Both agreed with
 * each other and neither agreed with the workflow, which kept looking for the document and the
 * figures under the full sentence — so the review reported an empty document and an empty figure
 * inventory, and blamed the writer for a mismatch the workflow had created.</p>
 *
 * <p>The property under test is therefore not "the split is pretty" but "there is exactly one
 * identity": whatever {@link ResearchWriteReviewWorkflow#topicIdentifier} returns is what every
 * stage receives and what every path is built from. The last test asserts that directly, because
 * a split that produced a nice name while the paths used another would reintroduce the bug
 * silently.</p>
 */
class TopicIdentifierTest {

    @Test
    void takesTheOpeningSegmentOfABriefAsTheIdentifier() {
        // The shape the tool description asks for: identifier, colon, then the requirements.
        assertThat(ResearchWriteReviewWorkflow.topicIdentifier(
                "training-free 扩散模型布局控制：只要 2 篇论文，输出中文调研文档"))
                .isEqualTo("training-free 扩散模型布局控制");
    }

    @Test
    void keepsAShortTopicThatHasNoRequirementsAttached() {
        assertThat(ResearchWriteReviewWorkflow.topicIdentifier("多主体布局控制"))
                .isEqualTo("多主体布局控制");
    }

    @Test
    void ignoresWhateverTheIdentifierWasPrefixedWithInTheBrief() {
        // Reproduces the failing run's argument verbatim. The identifier here is the opening
        // segment, so the folder follows what the caller wrote first — the point is that one
        // value is chosen and then used everywhere, not which of the two plausible names wins.
        assertThat(ResearchWriteReviewWorkflow.topicIdentifier(
                "e2e-figure-check: training-free 扩散模型布局控制 (training-free layout control for "
                        + "diffusion models)，只需 2 篇论文，输出中文调研文档"))
                .isEqualTo("e2e-figure-check");
    }

    @Test
    void cutsAProseOpeningAtItsFirstComma() {
        // No colon at all: "prose that lost its colon". A folder name has to stay short enough to
        // be recognisable, so the identifier is cut at the first comma rather than accepted whole.
        String brief = "training-free 扩散模型布局控制研究综述，以及相关的布局控制方法，"
                + "需要覆盖 2023 年以来的工作并输出中文文档";

        assertThat(ResearchWriteReviewWorkflow.topicIdentifier(brief))
                .isEqualTo("training-free 扩散模型布局控制研究综述");
    }

    @Test
    void alsoBreaksOnAFullStopAndOnANewline() {
        assertThat(ResearchWriteReviewWorkflow.topicIdentifier("扩散布局控制。要求 3 篇论文"))
                .isEqualTo("扩散布局控制");
        assertThat(ResearchWriteReviewWorkflow.topicIdentifier("扩散布局控制\n要求 3 篇论文"))
                .isEqualTo("扩散布局控制");
    }

    @Test
    void reportsNothingForAnEmptyBrief() {
        assertThat(ResearchWriteReviewWorkflow.topicIdentifier(null)).isEmpty();
        assertThat(ResearchWriteReviewWorkflow.topicIdentifier("   ")).isEmpty();
    }

    @Test
    void oneIdentityDrivesBothTheFolderAndTheDocumentName() {
        String brief = "training-free 扩散模型布局控制：只要 2 篇论文，输出中文调研文档";
        String topicId = ResearchWriteReviewWorkflow.topicIdentifier(brief);

        String documentPath = ResearchWriteReviewWorkflow.documentPathFor(topicId);

        // Both halves of this are the regression. The short name is what the folder is called;
        // the absence of the brief's tail is what proves the paths stopped being built from the
        // raw argument. When they disagreed, the reviewer was handed an empty document and an
        // empty figure inventory and reported both as the writer's fault.
        assertThat(documentPath).contains("training-free-扩散模型布局控制");
        assertThat(documentPath).doesNotContain("只要").doesNotContain("篇论文");
    }
}

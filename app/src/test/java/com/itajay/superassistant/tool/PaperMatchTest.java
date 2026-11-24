package com.itajay.superassistant.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules that decide whether a file is the paper being asked for. */
class PaperMatchTest {

    private static final String RESNET = "Deep Residual Learning for Image Recognition";
    private static final String ATTENTION = "Attention Is All You Need";

    private static final PdfIdentity ATTENTION_PAGE = page("1706.03762", null, ATTENTION);
    private static final PdfIdentity RESNET_PAGE = page("1512.03385", null, RESNET);
    private static final PdfIdentity RESNET_UNSTAMPED = page(null, null, RESNET);
    private static final PdfIdentity SCAN = PdfIdentity.NONE;

    private static PdfIdentity page(String arxiv, String doi, String... lines) {
        return new PdfIdentity(arxiv, doi, "", List.of(lines));
    }

    private static PaperIdentity want(String arxiv, String doi, String title) {
        return new PaperIdentity(arxiv, doi, title, null);
    }

    private static PaperMatch match(PaperIdentity wanted, PdfIdentity page) {
        return PaperMatch.of(wanted, PaperIdentity.NONE, page, "Survey");
    }

    /** The reported bug: same name, another paper. */
    @Test
    void anotherPaperIsDifferentWhateverItsName() {
        assertThat(PaperMatch.of(want("1512.03385", null, RESNET), PaperIdentity.NONE, ATTENTION_PAGE, "arxiv-abs-url"))
                .isEqualTo(PaperMatch.DIFFERENT);
        assertThat(match(want(null, null, RESNET), ATTENTION_PAGE)).isEqualTo(PaperMatch.DIFFERENT);
        assertThat(match(want("1512.03385", null, null), ATTENTION_PAGE)).isEqualTo(PaperMatch.DIFFERENT);
    }

    @Test
    void identifierOrExactTitleIsEnough() {
        assertThat(match(want("1512.03385v1", null, null), RESNET_PAGE)).isEqualTo(PaperMatch.SAME_ID);
        assertThat(match(want(null, null, RESNET), RESNET_PAGE)).isEqualTo(PaperMatch.SAME_TITLE);
        assertThat(match(want("1512.03385", null, RESNET), RESNET_UNSTAMPED)).isEqualTo(PaperMatch.SAME_TITLE);
    }

    /** A model that pairs the right title with another paper's id must not get that other paper. */
    @Test
    void anIdentifierThatLeadsToAnotherTitleIsNotBelieved() {
        assertThat(match(want("1706.03762", null, RESNET), ATTENTION_PAGE)).isEqualTo(PaperMatch.DIFFERENT);
        assertThat(PaperMatch.contradicts(want("1706.03762", null, RESNET), ATTENTION_PAGE)).isTrue();
    }

    @Test
    void theStampOutranksWhatARequestOnceClaimed() {
        PaperIdentity misrecorded = want("1512.03385", null, null);

        assertThat(PaperMatch.of(want("1512.03385", null, null), misrecorded, ATTENTION_PAGE, "Survey"))
                .isEqualTo(PaperMatch.DIFFERENT);
    }

    /** A preprint and its published version carry one DOI each. */
    @Test
    void differentDoisAloneAreNoContradiction() {
        PdfIdentity preprint = page(null, "10.1101/2020.01.31.929042");
        assertThat(match(want(null, "10.1016/j.cell.2020.02.052", null), preprint)).isEqualTo(PaperMatch.UNKNOWN);
        assertThat(PaperMatch.of(want(null, "10.1109/cvpr.2016.90", RESNET), want(null, "10.1234/other", null),
                RESNET_UNSTAMPED, "x")).isEqualTo(PaperMatch.SAME_TITLE);
    }

    /** Several works are called "Deep learning"; a shared short title under another DOI is not proof. */
    @Test
    void aShortTitleUnderAnotherDoiIsAnotherPaper() {
        assertThat(match(want(null, "10.1038/nature14539", "Deep learning"),
                page(null, "10.4135/9781526493446", "Deep learning"))).isEqualTo(PaperMatch.DIFFERENT);
    }

    @Test
    void whatTheFileWasFetchedByIdentifiesIt() {
        PaperIdentity byDoi = want(null, "10.1109/cvpr.2016.90", null);
        PaperIdentity byLink = new PaperIdentity(null, null, null, "https://openreview.net/pdf?id=abc");

        assertThat(PaperMatch.of(byDoi, byDoi, SCAN, "x")).isEqualTo(PaperMatch.SAME_ID);
        assertThat(PaperMatch.of(byLink, byLink, SCAN, "x")).isEqualTo(PaperMatch.SAME_ID);
        assertThat(PaperMatch.of(new PaperIdentity(null, null, null, "https://openreview.net/pdf?id=zzz"), byLink,
                SCAN, "x")).isEqualTo(PaperMatch.UNKNOWN);
    }

    @Test
    void aFileNamedByIdentifierOrTitleIsThatPaperUnlessTheStampObjects() {
        assertThat(PaperMatch.of(want(null, "10.1145/3292500.3330701", null), PaperIdentity.NONE, SCAN,
                "Optuna-10.1145-3292500.3330701")).isEqualTo(PaperMatch.SAME_ID);
        assertThat(PaperMatch.of(want("1512.03385", null, RESNET), PaperIdentity.NONE, SCAN, "1512.03385v1"))
                .isEqualTo(PaperMatch.SAME_ID);
        assertThat(PaperMatch.of(want(null, null, RESNET), PaperIdentity.NONE, SCAN, RESNET))
                .isEqualTo(PaperMatch.SAME_ID);
        assertThat(PaperMatch.of(want("1512.03385", null, RESNET), PaperIdentity.NONE, ATTENTION_PAGE, "x-1512.03385"))
                .isEqualTo(PaperMatch.DIFFERENT);
    }

    /** A bare name decides only where there is nothing else. */
    @Test
    void theNameAloneDecidesOnlyWithoutAnyOtherEvidence() {
        PaperIdentity wanted = want("1512.03385", null, RESNET);

        assertThat(PaperMatch.UNKNOWN.acceptsName(PaperIdentity.NONE, PaperIdentity.NONE, ATTENTION_PAGE)).isTrue();
        assertThat(PaperMatch.UNKNOWN.acceptsName(wanted, PaperIdentity.NONE, SCAN)).isTrue();
        assertThat(PaperMatch.UNKNOWN.acceptsName(wanted, PaperIdentity.NONE, page(null, "10.1234/x"))).isFalse();
        assertThat(PaperMatch.UNKNOWN.acceptsName(wanted, want(null, "10.1234/x", null), SCAN)).isFalse();
        assertThat(PaperMatch.DIFFERENT.acceptsName(wanted, PaperIdentity.NONE, SCAN)).isFalse();
    }

    /** A title too short to compare is still a request for one paper, not for any paper. */
    @Test
    void aShortTitleIsNotAWildcard() {
        PaperIdentity wanted = want(null, null, "Survey");

        assertThat(wanted.isEmpty()).isFalse();
        assertThat(match(wanted, ATTENTION_PAGE).acceptsName(wanted, PaperIdentity.NONE, ATTENTION_PAGE)).isFalse();
    }

    @Test
    void aDownloadIsRefusedOnlyWhenThePageObjects() {
        assertThat(PaperMatch.contradicts(want("1512.03385", null, "ResNet: " + RESNET), RESNET_PAGE)).isFalse();
        assertThat(PaperMatch.contradicts(want(null, "10.1109/cvpr.2016.90", RESNET), SCAN)).isFalse();
        assertThat(PaperMatch.contradicts(want(null, "10.1109/cvpr.2016.90", null), RESNET_PAGE)).isFalse();
        assertThat(PaperMatch.contradicts(want(null, null, RESNET), ATTENTION_PAGE)).isTrue();
        assertThat(PaperMatch.contradicts(want("1512.03385", null, null), ATTENTION_PAGE)).isTrue();
    }

    @Test
    void identitiesAreNormalisedAndKeepTheirLinkWhenCopied() {
        PaperIdentity id = new PaperIdentity("arXiv:2304.03373v2", "https://doi.org/10.1109/WACV57701.2024.00526", " T ",
                "http://OpenReview.net/pdf?id=abc");

        assertThat(id).isEqualTo(new PaperIdentity("2304.03373", "10.1109/wacv57701.2024.00526", "T",
                "https://openreview.net/pdf?id=abc"));
        assertThat(id.or(PaperIdentity.NONE)).isEqualTo(id);
        assertThat(id.confirmedBy(PdfIdentity.NONE)).isEqualTo(id);
        assertThat(new PaperIdentity(null, "10.48550/arXiv.2005.12872", null, null))
                .isEqualTo(new PaperIdentity("2005.12872", null, null, null));
        assertThat(new PaperIdentity(null, null, null, "https://doi.org/10.1/x").url()).isNull();
    }
}

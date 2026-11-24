package com.itajay.superassistant.tool;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Title → DOI on Crossref. The fixtures reproduce the result shapes measured on this
 * network (2026-11), including the ones that made the strict rules necessary.
 */
class CrossrefTitleResolverTest {

    /** Faster R-CNN: one exact hit among near-misses. */
    static final String FASTER_RCNN_HITS = """
            {"status":"ok","message":{"items":[
              {"DOI":"10.1109/tpami.2016.2577031","type":"journal-article",
               "title":["Faster R-CNN: Towards Real-Time Object Detection with Region Proposal Networks"]},
              {"DOI":"10.2139/ssrn.4061479","type":"journal-article",
               "title":["Depth Based Region Proposal: Towards Robust Two-Stage Real-Time Object Detection"]}
            ]}}""";

    /** Crossref keeps "Optuna" as the title and the rest as a subtitle. */
    static final String OPTUNA_WITH_SUBTITLE = """
            {"status":"ok","message":{"items":[
              {"DOI":"10.1145/3292500.3330701","type":"proceedings-article","title":["Optuna"],
               "subtitle":["A Next-generation Hyperparameter Optimization Framework"]}
            ]}}""";

    /** Same paper as a preprint and as the published version. */
    static final String PREPRINT_AND_PUBLISHED = """
            {"status":"ok","message":{"items":[
              {"DOI":"10.1101/2020.01.31.929042","type":"posted-content",
               "title":["SARS-CoV-2 Cell Entry Depends on ACE2 and TMPRSS2 and Is Blocked by a Clinically Proven Protease Inhibitor"]},
              {"DOI":"10.1016/j.cell.2020.02.052","type":"journal-article",
               "title":["SARS-CoV-2 Cell Entry Depends on ACE2 and TMPRSS2 and Is Blocked by a Clinically Proven Protease Inhibitor"]}
            ]}}""";

    /** A short title shared by different papers. */
    static final String AMBIGUOUS_SHORT = """
            {"status":"ok","message":{"items":[
              {"DOI":"10.1038/nature14539","type":"journal-article","title":["Deep learning"]},
              {"DOI":"10.1007/978-1-4842-xxxx","type":"book-chapter","title":["Deep Learning"]}
            ]}}""";

    /** Crossref titles can carry markup. */
    static final String WITH_MARKUP = """
            {"status":"ok","message":{"items":[
              {"DOI":"10.1002/pro.4653","type":"journal-article",
               "title":["De novo protein design by inversion of the <scp>AlphaFold</scp> structure prediction network"]}
            ]}}""";

    private final List<URI> requested = new ArrayList<>();

    private CrossrefTitleResolver resolver(String body) {
        return new CrossrefTitleResolver("someone@example.com", 5000, (uri, timeout) -> {
            requested.add(uri);
            return new OpenAccessHttp.Response(200, body);
        });
    }

    @Test
    void anExactTitleMatchYieldsItsDoi() {
        assertThat(resolver(FASTER_RCNN_HITS).doiForTitle(
                "Faster R-CNN: Towards Real-Time Object Detection with Region Proposal Networks", null))
                .contains("10.1109/tpami.2016.2577031");
    }

    /** Case, spacing and punctuation never decide a match. */
    @Test
    void cosmeticDifferencesAreIgnored() {
        assertThat(resolver(FASTER_RCNN_HITS).doiForTitle(
                "faster r-cnn — towards real time object detection with region proposal networks", null))
                .contains("10.1109/tpami.2016.2577031");
    }

    @Test
    void aSubtitleIsJoinedBeforeComparing() {
        assertThat(resolver(OPTUNA_WITH_SUBTITLE).doiForTitle(
                "Optuna: A Next-generation Hyperparameter Optimization Framework", 2019))
                .contains("10.1145/3292500.3330701");
    }

    @Test
    void thePublishedVersionWinsOverThePreprint() {
        assertThat(resolver(PREPRINT_AND_PUBLISHED).doiForTitle(
                "SARS-CoV-2 Cell Entry Depends on ACE2 and TMPRSS2 and Is Blocked by a Clinically Proven Protease Inhibitor",
                2020)).contains("10.1016/j.cell.2020.02.052");
    }

    /** "Deep learning" names many works: a guess here would download the wrong one. */
    @Test
    void aShortTitleMatchingSeveralDoisIsAmbiguous() {
        assertThat(resolver(AMBIGUOUS_SHORT).doiForTitle("Deep learning", 2015)).isEmpty();
    }

    /** A near-miss is a different paper, never a match. */
    @Test
    void aSimilarTitleIsNotAMatch() {
        assertThat(resolver(FASTER_RCNN_HITS).doiForTitle(
                "Faster R-CNN: Towards Real-Time Object Detection", null)).isEmpty();
    }

    @Test
    void markupInCrossrefTitlesIsStripped() {
        assertThat(resolver(WITH_MARKUP).doiForTitle(
                "De novo protein design by inversion of the AlphaFold structure prediction network", 2023))
                .contains("10.1002/pro.4653");
    }

    /** The filter keeps out review records; the year window keeps out unrelated eras. */
    @Test
    void theQueryRestrictsTypesAndYears() {
        resolver(FASTER_RCNN_HITS).doiForTitle("Faster R-CNN: Towards Real-Time Object Detection", 2016);

        String query = URLDecoder.decode(requested.get(0).getRawQuery(), StandardCharsets.UTF_8);
        assertThat(query).contains("type:journal-article", "type:proceedings-article",
                "type:posted-content", "from-pub-date:2015", "until-pub-date:2018", "mailto=someone@example.com");
    }

    @Test
    void failuresAndDisabledLookupsYieldNothing() {
        var failing = new CrossrefTitleResolver("someone@example.com", 5000, (uri, timeout) -> {
            throw new java.io.IOException("reset");
        });
        assertThat(failing.doiForTitle("Faster R-CNN: Towards Real-Time Object Detection", null)).isEmpty();

        var disabled = new CrossrefTitleResolver("", 5000, (uri, timeout) -> {
            throw new AssertionError("must not call Crossref without a contact address");
        });
        assertThat(disabled.doiForTitle("Faster R-CNN: Towards Real-Time Object Detection", null)).isEmpty();

        assertThat(resolver(FASTER_RCNN_HITS).doiForTitle("short", null)).isEmpty();
        assertThat(resolver("not json").doiForTitle("Faster R-CNN: Towards Real-Time Object Detection", null))
                .isEmpty();
    }

    @Test
    void titleKeysKeepChineseCharacters() {
        assertThat(TitleKey.of("多主体 布局控制：综述")).isEqualTo("多主体布局控制综述");
        assertThat(TitleKey.ofMarkup("A <i>Title</i> &amp; More")).isEqualTo("atitlemore");
    }
}

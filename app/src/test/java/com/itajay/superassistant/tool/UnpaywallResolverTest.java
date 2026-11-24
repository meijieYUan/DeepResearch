package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The open-access lookup, and the source plan it heads.
 *
 * <p>The JSON fixtures are trimmed copies of real Unpaywall responses captured on this
 * deployment's network (2026-11), one per shape that matters: an OA copy with a PDF, an
 * OA record whose only location is a landing page, and a closed paper.</p>
 */
class UnpaywallResolverTest {

    /** Faster R-CNN (IEEE TPAMI) — green OA, the arXiv PDF listed twice. */
    static final String GREEN_WITH_PDF = """
            {"doi":"10.1109/tpami.2016.2577031","is_oa":true,"oa_status":"green",
             "best_oa_location":{"url_for_pdf":"https://arxiv.org/pdf/1506.01497",
                                 "url_for_landing_page":"http://arxiv.org/abs/1506.01497"},
             "oa_locations":[{"url_for_pdf":"https://arxiv.org/pdf/1506.01497"},
                             {"url_for_pdf":"https://arxiv.org/pdf/1506.01497"}]}""";

    /** "Deep learning" (Nature) — OA, but only a HAL landing page, no direct PDF. */
    static final String GREEN_LANDING_ONLY = """
            {"doi":"10.1038/nature14539","is_oa":true,"oa_status":"green",
             "best_oa_location":{"url_for_pdf":null,"url_for_landing_page":"https://hal.science/hal-04206682"},
             "oa_locations":[{"url_for_pdf":null,"url_for_landing_page":"https://hal.science/hal-04206682"}]}""";

    /** Optuna (ACM KDD) — closed. */
    static final String CLOSED = """
            {"doi":"10.1145/3292500.3330701","is_oa":false,"oa_status":"closed",
             "best_oa_location":null,"oa_locations":[]}""";

    /** SARS-CoV-2 (Cell) — bronze publisher copy first, then a bioRxiv preprint. */
    static final String TWO_LOCATIONS = """
            {"doi":"10.1016/j.cell.2020.02.052","is_oa":true,"oa_status":"bronze",
             "best_oa_location":{"url_for_pdf":"http://www.cell.com/article/S0092867420302294/pdf"},
             "oa_locations":[{"url_for_pdf":"http://www.cell.com/article/S0092867420302294/pdf"},
                             {"url_for_pdf":"https://www.biorxiv.org/content/biorxiv/early/2020/01/31/2020.01.31.929042.full.pdf"},
                             {"url_for_pdf":null,"url_for_landing_page":"https://pmc.ncbi.nlm.nih.gov/articles/PMC7102627/"}]}""";

    private final List<URI> requested = new ArrayList<>();

    private UnpaywallResolver resolver(int status, String body) {
        return new UnpaywallResolver("someone@example.com", 5000, (uri, timeout) -> {
            requested.add(uri);
            return new OpenAccessHttp.Response(status, body);
        });
    }

    @Test
    void anOpenCopyIsReturnedAsADirectPdfUrlDeduplicated() {
        assertThat(resolver(200, GREEN_WITH_PDF).pdfUrlsFor("https://doi.org/10.1109/TPAMI.2016.2577031"))
                .containsExactly("https://arxiv.org/pdf/1506.01497");
    }

    @Test
    void everyPdfLocationIsKeptInUnpaywallsOrder() {
        assertThat(resolver(200, TWO_LOCATIONS).pdfUrlsFor("10.1016/j.cell.2020.02.052"))
                .containsExactly("http://www.cell.com/article/S0092867420302294/pdf",
                        "https://www.biorxiv.org/content/biorxiv/early/2020/01/31/2020.01.31.929042.full.pdf");
    }

    /** A landing page is not a PDF; the given-URL step already covers it. */
    @Test
    void aLandingPageOnlyRecordYieldsNothing() {
        assertThat(resolver(200, GREEN_LANDING_ONLY).pdfUrlsFor("10.1038/nature14539")).isEmpty();
    }

    @Test
    void aClosedPaperYieldsNothing() {
        assertThat(resolver(200, CLOSED).pdfUrlsFor("10.1145/3292500.3330701")).isEmpty();
    }

    @Test
    void anUnknownDoiIsNotAnError() {
        assertThat(resolver(404, "{\"error\":true}").pdfUrlsFor("10.9999/does-not-exist")).isEmpty();
    }

    /** Every failure degrades to "no open copy known"; the download proceeds without it. */
    @Test
    void transportFailuresAndBadBodiesDegradeSilently() {
        var timingOut = new UnpaywallResolver("someone@example.com", 5000, (uri, timeout) -> {
            throw new HttpTimeoutException("timed out");
        });
        assertThat(timingOut.pdfUrlsFor("10.1109/TPAMI.2016.2577031")).isEmpty();
        assertThat(resolver(503, "busy").pdfUrlsFor("10.1109/TPAMI.2016.2577031")).isEmpty();
        assertThat(resolver(200, "<html>not json</html>").pdfUrlsFor("10.1109/TPAMI.2016.2577031")).isEmpty();
    }

    @Test
    void noEmailMeansNoLookupAtAll() {
        var disabled = new UnpaywallResolver("  ", 5000, (uri, timeout) -> {
            throw new AssertionError("must not call the API without a contact email");
        });
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.pdfUrlsFor("10.1109/TPAMI.2016.2577031")).isEmpty();
    }

    /** arXiv DOIs are not in Unpaywall (measured 404); asking would only add latency. */
    @Test
    void arxivDoisAndReferencesWithoutADoiAreNotLookedUp() {
        UnpaywallResolver resolver = resolver(200, GREEN_WITH_PDF);
        assertThat(resolver.pdfUrlsFor("https://doi.org/10.48550/arXiv.2304.03373")).isEmpty();
        assertThat(resolver.pdfUrlsFor("https://example.com/paper.pdf")).isEmpty();
        assertThat(requested).isEmpty();
    }

    @Test
    void theRequestEncodesTheDoiAsOneSegmentAndCarriesTheEmail() {
        resolver(200, GREEN_WITH_PDF).pdfUrlsFor("https://doi.org/10.1109/TPAMI.2016.2577031");

        assertThat(requested).hasSize(1);
        String uri = requested.get(0).toString();
        assertThat(uri).startsWith("https://api.unpaywall.org/v2/10.1109%2FTPAMI.2016.2577031?email=");
        assertThat(uri).endsWith("someone%40example.com");
    }

    @Test
    void doisAreFoundInsideUrlsAndProse() {
        assertThat(UnpaywallResolver.extractDoi("https://doi.org/10.1109/TPAMI.2016.2577031"))
                .isEqualTo("10.1109/TPAMI.2016.2577031");
        assertThat(UnpaywallResolver.extractDoi("https://dl.acm.org/doi/10.1145/3292500.3330701?download=true"))
                .isEqualTo("10.1145/3292500.3330701");
        assertThat(UnpaywallResolver.extractDoi("see 10.1038/nature14539.")).isEqualTo("10.1038/nature14539");
        assertThat(UnpaywallResolver.extractDoi("https://arxiv.org/abs/1706.03762")).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────
    // The source plan: Unpaywall first
    // ──────────────────────────────────────────────────────────────────────

    /** Routes by host so one fake can answer Unpaywall and Crossref in the same plan. */
    private static OpenAccessHttp fake(Map<String, OpenAccessHttp.Response> byHost, List<URI> seen) {
        return (uri, timeout) -> {
            seen.add(uri);
            OpenAccessHttp.Response response = byHost.get(uri.getHost());
            return response == null ? new OpenAccessHttp.Response(404, "") : response;
        };
    }

    private static PaperDownloadTool tool(OpenAccessHttp http) {
        return new PaperDownloadTool(new PaperDownloadProperties(), new ProgressChannelRegistry(), null, null,
                new UnpaywallResolver("someone@example.com", 5000, http),
                new CrossrefTitleResolver("someone@example.com", 5000, http));
    }

    private static List<String> labels(PaperDownloadTool.SourcePlan plan) {
        return plan.sources().stream().map(PaperDownloadTool.Source::label).toList();
    }

    @Test
    void unpaywallPrecedesArxivAndTheGivenUrl() {
        List<URI> seen = new ArrayList<>();
        PaperDownloadTool tool = tool(fake(Map.of("api.unpaywall.org",
                new OpenAccessHttp.Response(200, TWO_LOCATIONS)), seen));

        var plan = tool.planFor(PaperDownloadTool.PaperRef.of(
                "https://www.cell.com/cell/fulltext/S0092-8674(20)30229-4",
                "10.1016/j.cell.2020.02.052", "2001.00001", "SARS-CoV-2 Cell Entry"));

        assertThat(labels(plan)).containsExactly(
                PaperDownloadTool.LABEL_UNPAYWALL, PaperDownloadTool.LABEL_UNPAYWALL,
                PaperDownloadTool.LABEL_ARXIV, PaperDownloadTool.LABEL_ARXIV,
                PaperDownloadTool.LABEL_GIVEN_URL);
        assertThat(plan.sources().get(0).url()).isEqualTo("http://www.cell.com/article/S0092867420302294/pdf");
    }

    /** An Unpaywall location on arxiv.org is tried once, and credited to Unpaywall. */
    @Test
    void anUnpaywallArxivLocationIsNotTriedTwice() {
        PaperDownloadTool tool = tool(fake(Map.of("api.unpaywall.org",
                new OpenAccessHttp.Response(200, GREEN_WITH_PDF)), new ArrayList<>()));

        var plan = tool.planFor(PaperDownloadTool.PaperRef.of(null,
                "10.1109/TPAMI.2016.2577031", "1506.01497", "Faster R-CNN"));

        assertThat(plan.sources()).extracting(PaperDownloadTool.Source::url).containsExactly(
                "https://arxiv.org/pdf/1506.01497",
                "https://export.arxiv.org/pdf/1506.01497",
                "https://doi.org/10.1109/TPAMI.2016.2577031");
        assertThat(plan.sources().get(0).label()).isEqualTo(PaperDownloadTool.LABEL_UNPAYWALL);
    }

    /** A paper known only by title reaches Unpaywall through a DOI found on Crossref. */
    @Test
    void aTitleOnlyReferenceIsResolvedThroughCrossrefThenUnpaywall() {
        List<URI> seen = new ArrayList<>();
        PaperDownloadTool tool = tool(fake(Map.of(
                "api.crossref.org", new OpenAccessHttp.Response(200, CrossrefTitleResolverTest.FASTER_RCNN_HITS),
                "api.unpaywall.org", new OpenAccessHttp.Response(200, GREEN_WITH_PDF)), seen));

        var plan = tool.planFor(PaperDownloadTool.PaperRef.of(null, null, null,
                "Faster R-CNN: Towards Real-Time Object Detection with Region Proposal Networks"));

        assertThat(plan.resolvedDoi()).isEqualTo("10.1109/tpami.2016.2577031");
        assertThat(plan.sources()).extracting(PaperDownloadTool.Source::url)
                .containsExactly("https://arxiv.org/pdf/1506.01497");
        assertThat(seen).extracting(URI::getHost).containsExactly("api.crossref.org", "api.unpaywall.org");
    }

    /** With a DOI in hand there is nothing to look up by title. */
    @Test
    void aGivenDoiSkipsTheCrossrefLookup() {
        List<URI> seen = new ArrayList<>();
        PaperDownloadTool tool = tool(fake(Map.of("api.unpaywall.org",
                new OpenAccessHttp.Response(200, CLOSED)), seen));

        tool.planFor(PaperDownloadTool.PaperRef.of(null, "10.1145/3292500.3330701", null, "Optuna"));

        assertThat(seen).extracting(URI::getHost).containsExactly("api.unpaywall.org");
    }

    /** The year the agent passes narrows the Crossref search (measured: 8/14 hits with it, 7/14 without). */
    @Test
    void theYearReachesTheCrossrefQuery() {
        List<URI> seen = new ArrayList<>();
        PaperDownloadTool tool = tool(fake(Map.of(), seen));

        tool.planFor(PaperDownloadTool.PaperRef.of(null, null, null,
                "End-to-End Object Detection with Transformers", 2020));

        assertThat(java.net.URLDecoder.decode(seen.get(0).getRawQuery(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("from-pub-date:2019", "until-pub-date:2022");
    }

    /** A DOI pasted as the url still unlocks the lookup: the model need not fill both fields. */
    @Test
    void aDoiInsideTheUrlIsUsedForTheLookup() {
        PaperDownloadTool tool = tool(fake(Map.of("api.unpaywall.org",
                new OpenAccessHttp.Response(200, GREEN_WITH_PDF)), new ArrayList<>()));

        assertThat(tool.candidateUrls("https://doi.org/10.1109/TPAMI.2016.2577031"))
                .containsExactly("https://arxiv.org/pdf/1506.01497", "https://doi.org/10.1109/TPAMI.2016.2577031");
    }

    /** An arXiv DOI is an arXiv ID: it goes straight to arXiv, not to Unpaywall's 404. */
    @Test
    void anArxivDoiBecomesAnArxivSource() {
        List<URI> seen = new ArrayList<>();
        PaperDownloadTool tool = tool(fake(Map.of(), seen));

        var plan = tool.planFor(PaperDownloadTool.PaperRef.of(null, "10.48550/arXiv.2304.03373", null, null));

        assertThat(plan.sources()).extracting(PaperDownloadTool.Source::url)
                .containsExactly("https://arxiv.org/pdf/2304.03373", "https://export.arxiv.org/pdf/2304.03373");
        assertThat(seen).isEmpty();
    }

    @Test
    void withoutAnOpenCopyTheGivenUrlRemains() {
        PaperDownloadTool tool = tool(fake(Map.of("api.unpaywall.org",
                new OpenAccessHttp.Response(200, CLOSED)), new ArrayList<>()));

        assertThat(tool.candidateUrls("https://doi.org/10.1145/3292500.3330701"))
                .containsExactly("https://doi.org/10.1145/3292500.3330701");
    }

    @Test
    void bareArxivIdsAreRecognisedInEveryCommonSpelling() {
        assertThat(PaperDownloadTool.arxivIdOf("2304.03373")).isEqualTo("2304.03373");
        assertThat(PaperDownloadTool.arxivIdOf("arXiv:2304.03373v2")).isEqualTo("2304.03373");
        assertThat(PaperDownloadTool.arxivIdOf("https://arxiv.org/abs/2304.03373v2")).isEqualTo("2304.03373");
        assertThat(PaperDownloadTool.arxivIdOf("cs/0701001")).isEqualTo("cs/0701001");
        assertThat(PaperDownloadTool.arxivIdOf("10.1109/TPAMI.2016.2577031")).isNull();
    }

    /**
     * The model only sees the generated schema: the identifiers must be in it, and only
     * topic and shortName may be required — otherwise a call with just a DOI is rejected
     * before it reaches the plan.
     */
    @Test
    void theToolSchemaOffersEveryIdentifierAndRequiresOnlyTopicAndShortName() throws Exception {
        PaperDownloadTool tool = new PaperDownloadTool(new PaperDownloadProperties(), new ProgressChannelRegistry(),
                null, null);
        var callback = java.util.Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(tool))
                .filter(c -> c.getToolDefinition().name().equals("downloadPaper"))
                .findFirst().orElseThrow();
        var schema = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(callback.getToolDefinition().inputSchema());

        assertThat(schema.path("properties").fieldNames()).toIterable()
                .contains("topic", "shortName", "url", "doi", "arxivId", "title", "year");
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        assertThat(required).containsExactlyInAnyOrder("topic", "shortName");
    }
}

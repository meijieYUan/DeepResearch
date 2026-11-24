package com.itajay.superassistant.tool;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.progress.ProgressStage;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads paper PDFs. Reading them back is {@link PaperTextTool}'s job.
 *
 * <p>Written for scholarly hosts, which fail in ways a plain HTTP GET does not
 * handle well: arXiv rate-limits bursts, publishers redirect through interstitials,
 * Cloudflare returns a 403 HTML page with a 200 status, and connections drop
 * mid-transfer. This tool therefore does four things a bare download does not:</p>
 *
 * <ol>
 *   <li><b>Retry with backoff and jitter</b> on transport errors and retryable
 *       statuses (408/425/429/5xx), honouring {@code Retry-After}.</li>
 *   <li><b>Content-type sniffing</b> — a PDF must start with {@code %PDF-}.
 *       An HTML landing page returned instead of the file is reported as such
 *       rather than saved as a corrupt PDF.</li>
 *   <li><b>Landing-page resolution</b> — given an arXiv abstract page, a DOI URL,
 *       or a publisher landing page, it finds the actual PDF link.</li>
 *   <li><b>Integrity checks</b> — size bounds, and a PDFBox parse with page count.
 *       The file is only moved into place after it parses.</li>
 * </ol>
 *
 * <p>Downloads are confined to {@code investigation/{课题方向}/papers/} under the
 * project root (see {@link WorkspacePaths}). The topic and file name are sanitised
 * to single path segments, so a model-chosen value cannot escape that folder.</p>
 */
@Component
public class PaperDownloadTool {

    private static final Logger log = LoggerFactory.getLogger(PaperDownloadTool.class);

    /** Every valid PDF starts with this. Used to tell a real file from an error page. */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private static final Pattern PDF_HREF = Pattern.compile(
            "href\\s*=\\s*[\"']([^\"']*\\.pdf(?:\\?[^\"']*)?)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern ARXIV_ID = Pattern.compile(
            "arxiv\\.org/(?:abs|pdf)/([0-9]{4}\\.[0-9]{4,5}|[a-z-]+(?:\\.[A-Z]{2})?/[0-9]{7})",
            Pattern.CASE_INSENSITIVE);

    /** A bare DOI ("10.1234/foo.bar") pasted without a resolver URL. */
    private static final Pattern BARE_DOI = Pattern.compile("^10\\.\\d{4,}/\\S+$");

    private final PaperDownloadProperties props;
    private final ProgressChannelRegistry progress;
    private final PaperLibrary library;
    private final DownloadFailureRegistry downloadFailures;
    private final HttpClient httpClient;
    /** Legal open-access copies by DOI. See {@link UnpaywallResolver}. */
    private final UnpaywallResolver unpaywall;
    /** Title → DOI, so a paper cited only by name still reaches Unpaywall. */
    private final CrossrefTitleResolver crossref;

    // Two constructors exist (the package-private one is a test seam), so Spring must be
    // told which one to use — implicit single-constructor autowiring only applies when
    // there is exactly one.
    @Autowired
    public PaperDownloadTool(PaperDownloadProperties props,
                             ProgressChannelRegistry progress,
                             PaperLibrary library,
                             DownloadFailureRegistry downloadFailures) {
        this.props = props;
        this.progress = progress;
        this.library = library;
        this.downloadFailures = downloadFailures;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        OpenAccessHttp http = OpenAccessHttp.over(httpClient, props.getUserAgent());
        this.unpaywall = new UnpaywallResolver(props.getUnpaywallEmail(), props.getUnpaywallTimeoutMs(), http);
        this.crossref = new CrossrefTitleResolver(props.getUnpaywallEmail(), props.getUnpaywallTimeoutMs(), http);
    }

    /** Test seam: the same tool with stubbed open-access resolvers. */
    PaperDownloadTool(PaperDownloadProperties props,
                      ProgressChannelRegistry progress,
                      PaperLibrary library,
                      DownloadFailureRegistry downloadFailures,
                      UnpaywallResolver unpaywall,
                      CrossrefTitleResolver crossref) {
        this.props = props;
        this.progress = progress;
        this.library = library;
        this.downloadFailures = downloadFailures;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.unpaywall = unpaywall;
        this.crossref = crossref;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tool 1: download
    // ──────────────────────────────────────────────────────────────────────

    @Tool(description = """
            Download a paper PDF so it can be read later. Saves to investigation/{课题方向}/papers/.
            Pass what you know about the paper; the tool plans the sources itself, in this order:
              1. the shared local paper library (no network);
              2. Unpaywall — legal open-access copies found by DOI (arXiv, PubMed Central, institutional
                 repositories). When you have no DOI but give the title, the DOI is looked up on Crossref first;
              3. arXiv, when an arXiv ID is known;
              4. the url you gave (direct PDF, landing page — the PDF link is located on the page).
            So: ALWAYS pass title; pass doi for every published paper (venue/journal version) and arxivId for
            every arXiv paper — give both when the paper has both. url is optional when doi or arxivId is given.
            Retries transient network failures, verifies the file really is a PDF and really is the paper you
            asked for, skips a paper already saved, and reports every source it tried. Returns the saved path,
            page count, SHA-256 and the source used. shortName is only a label: when it is already taken by a
            different paper, or this paper is already saved under another name, the reply carries the name to
            use in a "shortName:" line — use that name for this paper from then on.
            Only call this for papers that already passed relevance screening.""")
    public String downloadPaper(
            @ToolParam(description = "课题方向, the research topic. Becomes the topic folder name — keep it identical across the whole investigation so the same topic reuses one folder.") String topic,
            @ToolParam(required = false, description = "Any URL for the paper: direct PDF, arXiv abs page, DOI link, or publisher landing page. Optional when doi or arxivId is given.") String url,
            @ToolParam(description = "Short name used as the filename, e.g. 'MS-Diffusion'. Letters, digits, dash, underscore, dot only. A label, not an identity: the paper is identified by title, doi and arxivId.") String shortName,
            @ToolParam(required = false, description = "The paper's DOI, e.g. 10.1109/TPAMI.2016.2577031 (with or without https://doi.org/). The key that unlocks the Unpaywall open-access lookup — pass it for every published paper.") String doi,
            @ToolParam(required = false, description = "arXiv identifier, e.g. 2304.03373 (with or without version suffix).") String arxivId,
            @ToolParam(required = false, description = "The paper's exact full title. Used to find the DOI on Crossref when doi is not given, and to tell the user which paper is missing if every source fails.") String title,
            @ToolParam(required = false, description = "Publication year, e.g. 2024. Narrows the Crossref title search; pass it whenever the title is passed without a doi.") Integer year,
            ToolContext toolContext) {

        return downloadPaperInternal(topic, PaperRef.of(url, doi, arxivId, title, year), shortName,
                threadIdOf(toolContext));
    }

    /** Java callers with only a reference string (deep-read workflow, tests). */
    public String downloadPaperInternal(String topic, String url, String shortName, String threadId) {
        return downloadPaperInternal(topic, PaperRef.of(url, null, null, null), shortName, threadId);
    }

    /** Java convenience form of the tool call with only a URL, as it was before identifiers. */
    String downloadPaper(String topic, String url, String shortName, ToolContext toolContext) {
        return downloadPaper(topic, url, shortName, null, null, null, null, toolContext);
    }

    /**
     * What the caller knows about one paper. Each field is optional on its own; at least one
     * of {@code url}, {@code doi}, {@code arxivId} must be present for a network attempt.
     *
     * <p>Identifiers given separately win over ones extracted from the URL, but a URL that
     * carries a DOI or arXiv ID still fills in the gap — a model that pastes
     * {@code https://doi.org/10.x/y} as the url must not lose the open-access lookup just
     * because it left the {@code doi} argument empty.</p>
     */
    record PaperRef(String url, String doi, String arxivId, String title, Integer year) {

        static PaperRef of(String url, String doi, String arxivId, String title) {
            return of(url, doi, arxivId, title, null);
        }

        static PaperRef of(String url, String doi, String arxivId, String title, Integer year) {
            String normalizedUrl = url == null || url.isBlank() ? null : normalizeUrl(url.trim());
            String cleanDoi = UnpaywallResolver.extractDoi(doi);
            if (cleanDoi == null && normalizedUrl != null) {
                cleanDoi = UnpaywallResolver.extractDoi(normalizedUrl);
            }
            String cleanArxiv = arxivIdOf(arxivId);
            if (cleanArxiv == null && normalizedUrl != null) {
                cleanArxiv = arxivIdOf(normalizedUrl);
            }
            // An arXiv DOI (10.48550/arXiv.NNNN) is an arXiv ID in disguise.
            if (cleanArxiv == null && cleanDoi != null && UnpaywallResolver.isArxivDoi(cleanDoi)) {
                cleanArxiv = arxivIdOf(cleanDoi.substring(cleanDoi.indexOf('/') + 1));
            }
            String cleanTitle = title == null || title.isBlank() ? null : title.strip();
            Integer cleanYear = year == null || year < 1900 || year > 2200 ? null : year;
            return new PaperRef(normalizedUrl, cleanDoi, cleanArxiv, cleanTitle, cleanYear);
        }

        boolean isEmpty() {
            return url == null && doi == null && arxivId == null;
        }

        /** The most identifying reference, recorded with a failure and in the library index. */
        String reference() {
            if (url != null) {
                return url;
            }
            if (doi != null) {
                return "https://doi.org/" + doi;
            }
            return arxivId == null ? "" : "https://arxiv.org/abs/" + arxivId;
        }

        /** Which paper this is — everything the caller knows about it except the short name. */
        PaperIdentity identity() {
            return new PaperIdentity(arxivId, doi, title, url);
        }
    }

    /** A bare or embedded arXiv identifier, version suffix dropped; null when there is none. */
    static String arxivIdOf(String value) {
        return PaperIdentity.arxivIdIn(value);
    }

    /**
     * The download itself, addressed by thread id rather than {@link ToolContext}.
     *
     * <p>Split out so workflows — which hold a thread id but are not inside a tool
     * call — can download with the same progress events. The {@code @Tool} form above
     * is a thin adapter over this.</p>
     *
     * <p>Order of attempts, cheapest and most reliable first:</p>
     * <ol>
     *   <li>the file already in this topic, then the shared library (no network);</li>
     *   <li>Unpaywall's open-access copies for the DOI — resolving the DOI from the title on
     *       Crossref when only a title is known;</li>
     *   <li>arXiv, when an arXiv ID is known;</li>
     *   <li>the caller's URL, following a landing page to its PDF link.</li>
     * </ol>
     * <p>Unpaywall is first among network sources because it is the one that is both legal
     * and plain: its locations are repository PDFs (arXiv, PMC, institutional), not
     * publisher pages behind bot protection. arXiv stays right after it — for an arXiv
     * paper with no venue version it is the only source, and nothing is lost when Unpaywall
     * already returned the same arXiv URL (the plan is deduplicated).</p>
     */
    String downloadPaperInternal(String topic, PaperRef ref, String shortName, String threadId) {
        return download(topic, ref, shortName, threadId).message();
    }

    /** @param pdf where the paper is now, or null when it could not be obtained */
    private record DownloadResult(Path pdf, String message) {
    }

    private DownloadResult download(String topic, PaperRef ref, String shortName, String threadId) {
        if (ref == null || (ref.isEmpty() && ref.title() == null)) {
            return new DownloadResult(null,
                    "Error: give at least one of url, doi, arxivId (or a title to look the DOI up).");
        }
        if (shortName == null || shortName.isBlank()) {
            return new DownloadResult(null, "Error: shortName is required (used as the PDF filename).");
        }

        String safeName;
        PaperShelf shelf;
        try {
            safeName = WorkspacePaths.slugify(shortName, "论文短名");
            shelf = PaperStore.shelf(topic, library);
        } catch (IllegalArgumentException e) {
            return new DownloadResult(null, "Error: " + e.getMessage());
        }
        PaperIdentity wanted = ref.identity();

        // Already in this topic? Decided by which paper it is, not by the file name: the same
        // paper under another name is reused, another paper under this name is not.
        Optional<Path> inTopic = shelf.find(safeName, wanted);
        if (inTopic.isPresent()) {
            return reused(inTopic.get(), safeName, ref, topic, threadId, "论文已存在，跳过下载",
                    "already downloaded — skipped");
        }
        publishDownload(threadId, "开始下载论文：" + shortName);

        // The shared library comes before the network: a paper any earlier conversation
        // already fetched is copied in, costing nothing. The copy — not a move — keeps
        // the library intact for the next topic. A PDF a human dropped in under any file
        // name is found by what it says it is.
        Optional<Path> fromLibrary = library == null ? Optional.empty()
                : library.importInto(safeName, topic, wanted);
        if (fromLibrary.isPresent()) {
            return reused(fromLibrary.get(), safeName, ref, topic, threadId, "本地论文库命中，已复制",
                    "copied from local paper library");
        }

        List<String> failures = new ArrayList<>();
        SourcePlan plan = planSources(ref, failures);
        if (plan.isEmpty()) {
            failures.add("no source to try: no url or arXiv ID, and no DOI could be found"
                    + (ref.title() == null ? "" : " for the title"));
        }
        // A DOI found by title is part of what we now know about this paper.
        PaperIdentity identity = wanted.or(new PaperIdentity(null, plan.resolvedDoi(), null, null));
        String lastHtml = null;
        String lastHtmlUrl = null;
        boolean otherPaper = false;

        for (Source source : plan.sources()) {
            FetchOutcome outcome = fetch(source.url(), failures);
            if (outcome == null) {
                continue;
            }
            if (outcome.bytes() != null) {
                Landing landing = land(outcome, shelf, safeName, identity, failures, threadId, topic, ref, source);
                if (landing.result() != null) {
                    return landing.result();
                }
                otherPaper |= landing.otherPaper();
                continue;
            }
            if (outcome.html() != null) {
                lastHtml = outcome.html();
                lastHtmlUrl = outcome.url();
            }
        }

        // Nothing was a PDF. If a landing page came back, look for the PDF link on it
        // rather than giving up — this is the common case for publisher pages.
        if (lastHtml != null) {
            List<String> discovered = extractPdfLinks(lastHtml, lastHtmlUrl);
            for (String link : discovered) {
                FetchOutcome outcome = fetch(link, failures);
                if (outcome != null && outcome.bytes() != null) {
                    Landing landing = land(outcome, shelf, safeName, identity, failures, threadId, topic, ref,
                            new Source(link, LABEL_PAGE_LINK));
                    if (landing.result() != null) {
                        return landing.result();
                    }
                    otherPaper |= landing.otherPaper();
                }
            }
            if (!discovered.isEmpty()) {
                failures.add("found " + discovered.size() + " PDF link(s) on the landing page but none downloaded");
            } else {
                failures.add("landing page contained no PDF link (paywalled, or requires JavaScript)");
            }
        }

        publishDownload(threadId, "下载失败：" + shortName + "（已尝试 " + plan.sources().size()
                + " 个来源，将等待人工补充或降级为仅摘要）");
        recordFailure(threadId, topic, shortName, ref, identity, failures);
        return new DownloadResult(null, "DOWNLOAD_FAILED: " + shortName + "\n"
                + "Identifiers: " + describe(ref, plan) + "\n"
                + "Sources tried, in order:\n" + plan.describe() + "\n"
                + "Attempts:\n" + formatFailures(failures) + "\n"
                + (otherPaper
                    ? "Next: a source returned a different paper, so the title and the identifiers do not belong "
                        + "together. Open this paper's abstract page, copy the exact title and the DOI / arXiv ID "
                        + "from it, and call downloadPaper once more."
                    : nextStep(ref, plan)));
    }

    /** Reports a paper that needed no download, and settles any failure recorded for it earlier. */
    private DownloadResult reused(Path pdf, String requested, PaperRef ref, String topic, String threadId,
                                  String event, String action) {
        if (downloadFailures != null) {
            downloadFailures.resolved(threadId, topic, requested, ref.identity());
        }
        publishDownload(threadId, event + "：" + PaperStore.shortNameOf(pdf));
        return new DownloadResult(pdf, ok(pdf, PaperStore.pageCount(pdf), PaperStore.sha256(pdf), action)
                + nameNote(pdf, requested));
    }

    /**
     * Says which name the paper is under when it is not the one that was asked for. Every
     * later step finds the paper by that name, so the agent has to carry it.
     */
    private static String nameNote(Path pdf, String requested) {
        String actual = PaperStore.shortNameOf(pdf);
        return actual.equals(requested) ? "" : "\nshortName: " + actual
                + "\nNOTE: this paper is saved as \"" + actual + "\", not \"" + requested
                + "\" — it was already here under that name, or \"" + requested
                + "\" belongs to a different paper. Use \"" + actual + "\" as its short name from now on.";
    }

    /**
     * What the agent should do now — specific to what was missing, because the generic
     * "try another source" sent the model looking for mirrors when the real gap was that
     * it never passed a DOI.
     */
    private String nextStep(PaperRef ref, SourcePlan plan) {
        if (!unpaywallEnabled()) {
            return "Next: open-access lookup is disabled (agent.paper.unpaywall-email is not set). "
                    + "Try the arXiv version or the author's homepage; if none works, mark this paper "
                    + "SOURCE_LEVEL=ABSTRACT_ONLY — the workflow will ask the user to supply the PDF.";
        }
        if (ref.doi() == null && plan.resolvedDoi() == null) {
            return "Next: no DOI was available, so the open-access lookup could not run. "
                    + "Find the paper's DOI on its abstract page (arXiv shows it under \"DOI\" / journal-ref, "
                    + "publishers in the citation block) and call downloadPaper again with doi=…; "
                    + "otherwise mark it SOURCE_LEVEL=ABSTRACT_ONLY.";
        }
        if (ref.arxivId() == null) {
            return "Next: Unpaywall has no open copy of this DOI. If the paper has an arXiv version, call "
                    + "downloadPaper again with arxivId=…; otherwise mark it SOURCE_LEVEL=ABSTRACT_ONLY — "
                    + "the workflow will ask the user to supply the PDF. Do not retry the same identifiers.";
        }
        return "Next: every known source failed. Do not retry the same identifiers. Mark this paper "
                + "SOURCE_LEVEL=ABSTRACT_ONLY; the workflow will ask the user to supply the PDF.";
    }

    /** One identifier line for the failure report: what the plan was built from. */
    private static String describe(PaperRef ref, SourcePlan plan) {
        List<String> parts = new ArrayList<>();
        if (ref.doi() != null) {
            parts.add("doi=" + ref.doi());
        } else if (plan.resolvedDoi() != null) {
            parts.add("doi=" + plan.resolvedDoi() + " (found by title on Crossref)");
        }
        if (ref.arxivId() != null) {
            parts.add("arxivId=" + ref.arxivId());
        }
        if (ref.url() != null) {
            parts.add("url=" + ref.url());
        }
        if (ref.title() != null) {
            parts.add("title=\"" + ref.title() + "\"" + (ref.year() == null ? "" : " (" + ref.year() + ")"));
        }
        return parts.isEmpty() ? "(none)" : String.join(", ", parts);
    }

    /**
     * Remembers that this paper is still missing, so the workflow can offer a manual
     * download instead of silently degrading the document. Best-effort: a run without a
     * thread id (direct invocation in tests) simply has nothing to report to.
     *
     * <p>The whole identity travels with the failure: the title is what the user searches
     * for when asked to supply the PDF by hand, and the identifiers are what recognise the
     * file they bring.</p>
     */
    private void recordFailure(String threadId, String topic, String shortName, PaperRef ref,
                               PaperIdentity identity, List<String> failures) {
        if (downloadFailures == null || threadId == null || topic == null) {
            return;
        }
        // The first line is the most specific reason; the full list goes to the tool reply.
        String reason = failures.isEmpty() ? "unknown" : failures.get(0);
        downloadFailures.record(threadId, topic, shortName, ref.reference(), reason, identity);
    }

    /**
     * Lists what a topic already has.
     *
     * <p>Kept here as well as on {@link PaperTextTool} because the agent has to see what is
     * there, and under which name, before asking for a paper.</p>
     */
    @Tool(description = "List papers already downloaded for a topic, with size, page count and what each file says it is. Call this before downloading to avoid fetching a paper twice. Judge by identifier and title, not by name: different papers can carry similar names.")
    public String listDownloadedPapers(
            @ToolParam(description = "课题方向, the research topic whose papers should be listed") String topic) {
        return PaperStore.listPdfs(topic);
    }

    /**
     * The shared corpus, across every topic and conversation.
     *
     * <p>Worth exposing on its own: the model can see that a paper is already available
     * before spending a search on finding a source for it, and can pick the exact short
     * name a library copy is stored under.</p>
     */
    @Tool(description = """
            List the shared local paper library — PDFs every topic and conversation can reuse, each
            with what the file says it is. Names are labels from earlier conversations: the same name
            does not mean the same paper. downloadPaper checks this library first, by identifier and
            title, and copies a hit into the topic without any network access.""")
    public String listLibraryPapers() {
        return library == null ? "（本地论文库未启用）" : library.describe();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Progress
    // ──────────────────────────────────────────────────────────────────────

    /**
     * The part of a request that its source stands behind. A DOI the request merely claimed
     * must not be remembered for a file that came from arXiv by its arXiv id: were the claim
     * wrong, the next request for that DOI would be answered with this file. Likewise the
     * title: it is kept when the page bears it out, or when it is how the DOI was found.
     */
    private static PaperIdentity confirmed(PaperIdentity asked, PaperRef ref, Source source, PdfIdentity printed) {
        boolean byDoi = asked.doi() != null && (LABEL_UNPAYWALL.equals(source.label())
                || source.url().toLowerCase(Locale.ROOT).contains(asked.doi()));
        boolean byArxiv = asked.arxivId() != null && asked.arxivId().equals(arxivIdOf(source.url()));
        boolean byLink = !byDoi && !byArxiv && (LABEL_GIVEN_URL.equals(source.label())
                || LABEL_PAGE_LINK.equals(source.label()));
        boolean titleHolds = (byDoi && ref.doi() == null) || switch (printed.evidenceFor(asked.title())) {
            case EXACT, PARTIAL -> true;
            case CONTRADICTS, NONE -> false;
        };
        return new PaperIdentity(byArxiv ? asked.arxivId() : null, byDoi ? asked.doi() : null,
                titleHolds ? asked.title() : null, byLink ? asked.url() : null);
    }

    /** What became of one fetched PDF: a final reply, or a reason to try the next source. */
    private record Landing(DownloadResult result, boolean otherPaper) {
        private static final Landing NEXT = new Landing(null, false);
        private static final Landing OTHER_PAPER = new Landing(null, true);
    }

    /**
     * Saves a fetched PDF into the topic and the library, unless it is unusable or visibly a
     * different paper — then the reason is noted and the next source gets its turn.
     *
     * <p>Checked here as well as on lookup, because a wrong file can arrive over the network
     * too: a cited arXiv id that belongs to another paper, a landing page whose first PDF
     * link is the issue's editorial.</p>
     */
    private Landing land(FetchOutcome outcome, PaperShelf shelf, String safeName, PaperIdentity identity,
                         List<String> failures, String threadId, String topic, PaperRef ref, Source source) {
        Path temp = null;
        try {
            temp = Files.createTempFile("paper-download-", ".pdf");
            Files.write(temp, outcome.bytes());
            // A file that PDFBox cannot open is not a paper, whatever its first bytes say.
            Optional<PdfIdentity> printed = PdfIdentityReader.read(temp);
            if (printed.isEmpty()) {
                failures.add(outcome.url() + ": downloaded bytes are not a parseable PDF");
                return Landing.NEXT;
            }
            if (PaperMatch.contradicts(identity, printed.get())) {
                log.warn("Rejected {}: asked for {}, the file is {} \"{}\"", outcome.url(), identity,
                        printed.get().arxivId(), printed.get().heading());
                failures.add(outcome.url() + ": this is a different paper — its first page says "
                        + (printed.get().arxivId() == null ? "" : "arXiv " + printed.get().arxivId() + ", ")
                        + "\"" + printed.get().heading() + "\"");
                return Landing.OTHER_PAPER;
            }

            PaperIdentity fetchedBy = confirmed(identity, ref, source, printed.get());
            Path saved = shelf.add(temp, safeName, fetchedBy);
            // Everything downloaded is shared forward: the library is what keeps the next
            // topic from paying for this fetch again. A failure there is silent — the topic
            // copy is what this run needs. The fetched bytes are stored even when the topic
            // already held the paper: that is how their identifiers become known.
            if (library != null) {
                library.store(temp, PaperStore.shortNameOf(saved), fetchedBy);
            }
            if (downloadFailures != null) {
                downloadFailures.resolved(threadId, topic, safeName, identity);
            }
            Integer pages = PaperStore.pageCount(saved);
            log.info("Downloaded paper: {} ({} pages, {} bytes)", saved.getFileName(), pages, outcome.bytes().length);
            publishDownload(threadId, "已下载论文：" + PaperStore.shortNameOf(saved) + "（来源：" + source.label() + "）");
            return new Landing(new DownloadResult(saved, ok(saved, pages, PaperStore.sha256(saved), "downloaded")
                    + "\nsource: " + source.label() + " — " + outcome.url()
                    + nameNote(saved, safeName)), false);
        } catch (IOException | IllegalArgumentException e) {
            log.error("Failed to save paper {}", safeName, e);
            failures.add(outcome.url() + ": could not save file — " + e.getMessage());
            return Landing.NEXT;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** The conversation thread for SSE, or null when the caller carried no config. */
    private static String threadIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        return ToolContextHelper.getConfig(toolContext)
                .flatMap(RunnableConfig::threadId)
                .orElse(null);
    }

    /** Best-effort publish; a no-op when no client is listening or the thread is unknown. */
    private void publishDownload(String threadId, String detail) {
        if (progress != null) {
            progress.publish(threadId, ProgressEvent.of(ProgressStage.RESEARCHING, 0, detail));
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fetching
    // ──────────────────────────────────────────────────────────────────────

    /** A fetch either produced PDF bytes or an HTML page; never both. */
    record FetchOutcome(String url, byte[] bytes, String html, Integer status) {
    }

    /**
     * Fetches one URL with retries. Returns null when the URL is unusable, otherwise
     * an outcome carrying either PDF bytes or the HTML body. Failure reasons are
     * appended to {@code failures} so the caller can report every attempt.
     *
     * <p>Package-private so tests can replace the network while everything else stays real.</p>
     */
    FetchOutcome fetch(String url, List<String> failures) {
        String current = url;
        // Follow HTML redirect chains (publisher → CDN → file) a bounded number of times.
        for (int hop = 0; hop < 4; hop++) {
            FetchOutcome outcome = fetchOnce(current, failures);
            if (outcome == null) {
                return null;
            }
            if (outcome.bytes() != null) {
                return outcome;
            }
            // HTML: resolve a PDF link if the page is a landing page.
            List<String> links = extractPdfLinks(outcome.html(), outcome.url());
            if (links.isEmpty()) {
                return outcome;
            }
            current = links.get(0);
            log.debug("Landing page {} -> PDF link {}", outcome.url(), current);
        }
        failures.add(url + ": too many redirect hops");
        return null;
    }

    private FetchOutcome fetchOnce(String url, List<String> failures) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            failures.add(url + ": invalid URL");
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            failures.add(url + ": unsupported scheme (only http/https allowed)");
            return null;
        }
        if (!isPublicHost(url, uri, failures)) {
            return null;
        }

        int attempts = Math.max(1, props.getMaxRetries());
        long backoff = props.getInitialBackoffMs();
        String lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            log.info("Fetching {} (attempt {}/{})", url, attempt, attempts);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .header("User-Agent", props.getUserAgent())
                        .header("Accept", "application/pdf,text/html,application/xhtml+xml,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
                        .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                        .GET()
                        .build();

                // The request's own timeout has been seen not to fire on a stalled response
                // (a PDF host held a run for over half an hour); the outer deadline does.
                HttpResponse<byte[]> response = HttpSend.send(httpClient, request,
                        HttpResponse.BodyHandlers.ofByteArray(),
                        Duration.ofMillis(props.getRequestTimeoutMs()));
                int status = response.statusCode();
                byte[] body = response.body();

                if (status >= 200 && status < 300) {
                    if (isPdf(body)) {
                        if (body.length > props.getMaxPdfBytes()) {
                            failures.add(url + ": file too large (" + (body.length / 1024 / 1024) + " MB)");
                            return null;
                        }
                        if (body.length < props.getMinPdfBytes()) {
                            failures.add(url + ": response too small to be a paper (" + body.length + " bytes)");
                            return null;
                        }
                        return new FetchOutcome(url, body, null, status);
                    }
                    // Not a PDF — treat as a landing page and let the caller look for links.
                    String html = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                    return new FetchOutcome(url, null, html, status);
                }

                if (isRetryable(status) && attempt < attempts) {
                    long wait = retryAfterMs(response).orElse(backoff);
                    log.debug("HTTP {} from {} — retrying in {} ms (attempt {}/{})",
                            status, url, wait, attempt, attempts);
                    sleep(wait);
                    backoff = nextBackoff(backoff);
                    lastError = "HTTP " + status;
                    continue;
                }

                failures.add(url + ": HTTP " + status + (status == 403
                        ? " (likely bot protection — try a mirror or the arXiv/OpenReview copy)"
                        : ""));
                return null;

            } catch (java.net.http.HttpTimeoutException e) {
                lastError = "timed out after " + props.getRequestTimeoutMs() + " ms";
            } catch (java.util.concurrent.TimeoutException e) {
                lastError = "no complete response within " + props.getRequestTimeoutMs() + " ms";
                log.info("Fetch gave up on {} after {} ms (attempt {}/{})", url,
                        props.getRequestTimeoutMs(), attempt, attempts);
            } catch (IOException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(url + ": interrupted");
                return null;
            }

            if (attempt < attempts) {
                sleep(backoff);
                backoff = nextBackoff(backoff);
            }
        }

        failures.add(url + ": " + lastError + " (after " + attempts + " attempt(s))");
        return null;
    }

    /**
     * SSRF guard: refuse hosts that resolve to non-public addresses.
     *
     * <p>Download URLs come from the model (and landing pages come from the network),
     * so a prompt-injected or malicious page could point the tool at loopback, an
     * internal service, or the cloud metadata address (169.254.169.254). Every hop of
     * the redirect chain re-enters {@code fetchOnce}, so the whole chain is checked.</p>
     *
     * <p>This validates at name-resolution time; a DNS record that changes between the
     * check and the connection (rebinding) is out of scope for a paper downloader.</p>
     */
    private boolean isPublicHost(String url, URI uri, List<String> failures) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            failures.add(url + ": missing host");
            return false;
        }
        java.net.InetAddress[] addresses;
        try {
            addresses = java.net.InetAddress.getAllByName(host);
        } catch (java.net.UnknownHostException e) {
            failures.add(url + ": unknown host");
            return false;
        }
        for (java.net.InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress() || isCarrierGradeNat(address)) {
                log.warn("Blocked download from non-public address {} ({})", address.getHostAddress(), host);
                failures.add(url + ": host resolves to a non-public address (blocked)");
                return false;
            }
        }
        return true;
    }

    /** 100.64.0.0/10 (RFC 6598 shared space) — not reachable as a public paper host. */
    private static boolean isCarrierGradeNat(java.net.InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xFF) == 100
                && (bytes[1] & 0xC0) == 64;
    }

    /** Writes the downloaded bytes to a temp file, validates it, then moves it into place. */
    // ──────────────────────────────────────────────────────────────────────
    // Source plan
    // ──────────────────────────────────────────────────────────────────────

    /** One URL to try, labelled with where it came from — the label is what the user sees. */
    record Source(String url, String label) {
    }

    /**
     * The ordered sources for one paper, plus the DOI found on Crossref when the caller
     * gave none (reported back so the agent can cite it).
     */
    record SourcePlan(List<Source> sources, String resolvedDoi) {

        boolean isEmpty() {
            return sources.isEmpty();
        }

        String describe() {
            if (sources.isEmpty()) {
                return "  (none)";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sources.size(); i++) {
                Source source = sources.get(i);
                sb.append("  ").append(i + 1).append(". [").append(source.label()).append("] ")
                        .append(source.url()).append('\n');
            }
            return sb.toString().stripTrailing();
        }
    }

    static final String LABEL_UNPAYWALL = "Unpaywall 开放获取";
    static final String LABEL_ARXIV = "arXiv";
    static final String LABEL_GIVEN_URL = "给定链接";
    static final String LABEL_MIRROR = "镜像";
    static final String LABEL_PAGE_LINK = "落地页链接";

    /** Package-private so the plan can be tested without a network call. */
    List<String> candidateUrls(String url) {
        return planSources(PaperRef.of(url, null, null, null), new ArrayList<>()).sources().stream()
                .map(Source::url).toList();
    }

    /** Package-private test entry with all identifiers. */
    SourcePlan planFor(PaperRef ref) {
        return planSources(ref, new ArrayList<>());
    }

    private boolean unpaywallEnabled() {
        return unpaywall != null && unpaywall.isEnabled();
    }

    /**
     * Orders every source this paper can come from: Unpaywall, then arXiv, then the given URL,
     * then configured mirrors. Duplicates keep their first (highest-priority) position, so an
     * Unpaywall location that is itself arxiv.org is tried once, as Unpaywall's.
     *
     * <p>Lookups that fail add a note to {@code failures} only when they explain a gap the
     * agent can act on (no DOI found for the title). An Unpaywall answer of "no open copy"
     * is the ordinary case and is not a failure.</p>
     */
    private SourcePlan planSources(PaperRef ref, List<String> failures) {
        List<Source> sources = new ArrayList<>();
        String resolvedDoi = null;

        // 1. Unpaywall. A DOI from the caller, else one found on Crossref by exact title.
        if (unpaywallEnabled()) {
            String doi = ref.doi();
            if (doi == null && ref.title() != null && crossref != null && crossref.isEnabled()) {
                resolvedDoi = crossref.doiForTitle(ref.title(), ref.year()).orElse(null);
                if (resolvedDoi == null && ref.arxivId() == null && ref.url() == null) {
                    failures.add("Crossref: no unambiguous DOI for title \"" + ref.title() + "\"");
                }
                doi = resolvedDoi;
            }
            if (doi != null && !UnpaywallResolver.isArxivDoi(doi)) {
                for (String pdf : unpaywall.pdfUrlsFor(doi)) {
                    sources.add(new Source(pdf, LABEL_UNPAYWALL));
                }
            }
        }

        // 2. arXiv. The canonical PDF URL first, then the export mirror for networks where
        //    arxiv.org itself is unreachable.
        if (ref.arxivId() != null) {
            sources.add(new Source("https://arxiv.org/pdf/" + ref.arxivId(), LABEL_ARXIV));
            sources.add(new Source("https://export.arxiv.org/pdf/" + ref.arxivId(), LABEL_ARXIV));
        }

        // 3. The caller's URL — a direct PDF, or a landing page whose PDF link is followed.
        //    For a DOI with no URL, the resolver itself is that landing page.
        if (ref.url() != null) {
            sources.add(new Source(ref.url(), LABEL_GIVEN_URL));
        } else if (ref.doi() != null && !UnpaywallResolver.isArxivDoi(ref.doi())) {
            sources.add(new Source("https://doi.org/" + ref.doi(), LABEL_GIVEN_URL));
        }

        // 4. Host-configured mirrors come last: they are a fallback, not the preference.
        for (Source source : List.copyOf(sources)) {
            String host = hostOf(source.url());
            List<String> fallbacks = host == null ? null : props.getHostFallbacks().get(host);
            if (fallbacks != null) {
                for (String fallback : fallbacks) {
                    String mapped = applyFallback(source.url(), fallback);
                    if (mapped != null) {
                        sources.add(new Source(mapped, LABEL_MIRROR));
                    }
                }
            }
        }

        // Deduplicate by URL, keeping the first label: an Unpaywall location on arxiv.org
        // must count as Unpaywall's, not be tried a second time as plain arXiv.
        Map<String, Source> unique = new LinkedHashMap<>();
        for (Source source : sources) {
            unique.putIfAbsent(source.url(), source);
        }
        return new SourcePlan(new ArrayList<>(unique.values()), resolvedDoi);
    }

    /**
     * Rewrites a URL onto a configured mirror.
     *
     * <p>A fallback is an origin to swap in, keeping the original path and query:
     * {@code https://export.arxiv.org} turns
     * {@code https://arxiv.org/pdf/1706.03762} into
     * {@code https://export.arxiv.org/pdf/1706.03762}.</p>
     *
     * <p>If the fallback contains {@code %s}, the original path (without its leading
     * slash) is substituted there instead, which covers mirrors that do not keep the
     * same path layout.</p>
     *
     * @return the rewritten URL, or null if the fallback is unusable
     */
    private String applyFallback(String candidate, String fallback) {
        if (fallback == null || fallback.isBlank()) {
            return null;
        }
        URI base;
        try {
            base = URI.create(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String path = base.getRawPath() == null ? "" : base.getRawPath();
        String query = base.getRawQuery() == null ? "" : "?" + base.getRawQuery();

        String template = fallback.trim();
        if (template.contains("%s")) {
            String stripped = path.startsWith("/") ? path.substring(1) : path;
            return template.replace("%s", stripped + query);
        }

        // Origin swap: keep the path and query, replace scheme + host (+ port).
        String origin = template.endsWith("/") ? template.substring(0, template.length() - 1) : template;
        if (!origin.startsWith("http://") && !origin.startsWith("https://")) {
            origin = "https://" + origin;
        }
        return origin + path + query;
    }

    /** Pulls absolute PDF links out of an HTML page, resolving relative hrefs. */
    private List<String> extractPdfLinks(String html, String baseUrl) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Map<String, Boolean> found = new LinkedHashMap<>();
        Matcher m = PDF_HREF.matcher(html);
        while (m.find() && found.size() < 10) {
            String href = m.group(1);
            String absolute = absolutize(href, baseUrl);
            if (absolute != null) {
                found.putIfAbsent(absolute, Boolean.TRUE);
            }
        }
        // arXiv abstract pages put the PDF link in the citation meta tags.
        Matcher citation = Pattern.compile(
                "<meta[^>]+name=[\"']citation_pdf_url[\"'][^>]+content=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (citation.find()) {
            found.put(citation.group(1), Boolean.TRUE);
        }
        return new ArrayList<>(found.keySet());
    }

    private String absolutize(String href, String baseUrl) {
        if (href == null || href.isBlank()) {
            return null;
        }
        if (href.startsWith("//")) {
            String scheme = hostOf(baseUrl) == null ? "https" : schemeOf(baseUrl);
            return scheme + ":" + href;
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        try {
            URI base = URI.create(baseUrl);
            return base.resolve(href).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private boolean isPdf(byte[] body) {
        if (body == null || body.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (body[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isRetryable(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private java.util.Optional<Long> retryAfterMs(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                // Cap it: a host asking for an hour must not stall the agent run.
                return java.util.Optional.of(Math.min(seconds * 1000, props.getMaxBackoffMs()));
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        });
    }

    private long nextBackoff(long current) {
        long doubled = (long) (current * 2);
        long capped = Math.min(doubled, props.getMaxBackoffMs());
        // Jitter avoids synchronised retries when several papers are fetched in a row.
        long jitter = (long) (capped * 0.2 * Math.random());
        return capped + jitter;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String ok(Path target, Integer pages, String hash, String action) {
        return "OK: " + action + "\n"
                + "path: " + WorkspacePaths.relative(target) + "\n"
                + "pages: " + pages + "\n"
                + "sha256: " + (hash == null ? "n/a" : hash);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Acquire (single-paper deep-read workflow entry)
    // ──────────────────────────────────────────────────────────────────────

    /** Where a paper source string points. */
    public enum SourceKind {
        /** An existing PDF file on disk (absolute, working-dir-relative, or project-relative). */
        LOCAL_FILE,
        /** A fetchable reference: direct PDF link, arXiv page, DOI, or publisher landing page. */
        URL,
        /** The short name of a paper already under some {@code investigation/{topic}/papers/}. */
        SHORT_NAME
    }

    /**
     * Where {@link #acquirePaper} actually got the PDF from — reported verbatim to the
     * user, who needs to know whether a run re-downloaded something they already had.
     */
    public enum Origin {
        LOCAL_COPY, REUSED_IN_TOPIC, COPIED_FROM_OTHER_TOPIC, COPIED_FROM_LIBRARY, DOWNLOAD, NOT_FOUND
    }

    /**
     * The outcome of {@link #acquirePaper}: the resolved identity plus where the file
     * came from. A record rather than prose because the workflow drives its report and
     * its later stages off these fields.
     */
    public record AcquireResult(boolean ok, String topic, String shortName, String pdfRelativePath,
                                Origin origin, String detail) {
    }

    /**
     * Classifies a paper reference so naming and acquisition agree on what it is.
     *
     * <p>Order matters: an existing local file wins over URL-shaped text (a file named
     * {@code 10.1234/x.pdf} is a file), and URL detection is generous — {@code doi:},
     * a bare {@code 10.x/...} DOI, and schemeless {@code arxiv.org/...} all count,
     * because users paste those and {@link #downloadPaperInternal} knows how to fetch
     * them.</p>
     */
    public static SourceKind classifySource(String paper) {
        if (paper == null || paper.isBlank()) {
            return SourceKind.SHORT_NAME;
        }
        String value = paper.trim();
        if (asLocalPdf(value) != null) {
            return SourceKind.LOCAL_FILE;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("doi:") || lower.contains("arxiv.org")
                || lower.contains("doi.org") || BARE_DOI.matcher(value).find()) {
            return SourceKind.URL;
        }
        return SourceKind.SHORT_NAME;
    }

    /**
     * The paper's short name (its future file name), derived from the reference or
     * given explicitly.
     *
     * <p>The derivation is the same rule in every branch: strip the extension, take the
     * identifying tail (file name, last path segment, arXiv id), then
     * {@link WorkspacePaths#slugify} it — the same sanitisation the PDF file name gets,
     * so {@code papers/X.pdf} and {@code analysis/X.md} stay paired.</p>
     */
    public static String inferShortName(String paper, SourceKind kind, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return WorkspacePaths.slugify(stripPdfExtension(explicit.trim()), "论文短名");
        }
        String value = paper == null ? "" : paper.trim();
        String raw = switch (kind) {
            // The file name textually — the file may not exist yet at naming time,
            // and taking the whole path would smear the directory into the short name.
            case LOCAL_FILE -> {
                String name = value.replace('\\', '/');
                int slash = name.lastIndexOf('/');
                yield slash >= 0 ? name.substring(slash + 1) : name;
            }
            case URL -> inferNameFromUrl(value);
            case SHORT_NAME -> value;
        };
        return WorkspacePaths.slugify(stripPdfExtension(raw), "论文短名");
    }

    /**
     * Gets a paper's PDF under {@code papers/}, preferring what is already on disk over any
     * fetch — the single-paper workflow's "local wins" rule in one place.
     *
     * <p>Order: a local file the caller pointed at, then the paper already in the topic, then
     * the library, then another topic's copy (copied, so topics stay isolated), and only
     * then a download. When {@code topic} is omitted, an existing copy's topic is adopted
     * so a re-run lands beside the paper it reuses; otherwise the short name becomes the
     * topic.</p>
     *
     * <p>"The paper" is decided by identity, never by a matching file name; the returned
     * short name is the one the file really has, which can differ from the requested one.</p>
     *
     * <p>Classification is re-derived from the source text rather than passed in: the
     * existence check at the heart of {@link SourceKind#LOCAL_FILE} is authoritative, and
     * this method must stay correct even when called without a prior classification.</p>
     */
    public AcquireResult acquirePaper(String topic, String paper, String shortName, String threadId) {
        SourceKind kind = classifySource(paper);
        String safeName;
        try {
            safeName = inferShortName(paper, kind, shortName);
        } catch (IllegalArgumentException e) {
            return new AcquireResult(false, topic, shortName, null, Origin.NOT_FOUND, e.getMessage());
        }

        Path local = asLocalPdf(paper == null ? "" : paper.trim());
        // A file's own content is its identity; a reference names a paper; a bare short name
        // only labels one.
        PaperIdentity wanted = switch (kind) {
            case LOCAL_FILE -> PaperIdentity.NONE.confirmedBy(
                    PdfIdentityReader.identityOf(local).orElse(PdfIdentity.NONE));
            case URL -> PaperRef.of(paper, null, null, null).identity();
            case SHORT_NAME -> PaperIdentity.fromReference(
                    paper == null ? null : stripPdfExtension(paper.trim()));
        };

        // Existing copies decide the topic when the caller did not name one: a paper
        // already under investigation/X/ belongs to X, and re-homing it would split the
        // PDF from analyses written against it.
        List<Path> elsewhere = PaperStore.findAcrossTopics(
                kind == SourceKind.LOCAL_FILE ? null : safeName, wanted, library);
        String effectiveTopic = topic;
        if (effectiveTopic == null || effectiveTopic.isBlank()) {
            AcquireResult ambiguous = ambiguousName(kind, safeName, elsewhere);
            if (ambiguous != null) {
                return ambiguous;
            }
            effectiveTopic = local != null && topicOf(local) != null ? topicOf(local)
                    : elsewhere.isEmpty() ? safeName : topicOf(elsewhere.get(0));
        }

        PaperShelf shelf;
        try {
            shelf = PaperStore.shelf(effectiveTopic, library);
        } catch (IllegalArgumentException e) {
            return new AcquireResult(false, effectiveTopic, safeName, null, Origin.NOT_FOUND, e.getMessage());
        }

        // 1. A local file the caller pointed at is the paper, whatever else has its name.
        if (kind == SourceKind.LOCAL_FILE) {
            try {
                List<Path> before = PaperStore.listPdfFiles(effectiveTopic);
                Path saved = PaperStore.importPdf(local, effectiveTopic, safeName, wanted,
                        props.getMaxPdfBytes(), library);
                if (before.contains(saved)) {
                    return acquired(saved, Origin.REUSED_IN_TOPIC, "课题下已有这篇论文，跳过获取");
                }
                publishDownload(threadId, "已导入本地论文：" + PaperStore.shortNameOf(saved));
                return acquired(saved, Origin.LOCAL_COPY, "已从本地复制：" + WorkspacePaths.relative(local));
            } catch (IllegalArgumentException | IOException e) {
                return new AcquireResult(false, effectiveTopic, safeName, null, Origin.NOT_FOUND,
                        "本地 PDF 导入失败：" + e.getMessage());
            }
        }

        // 2. The topic already has this paper — nothing to fetch.
        Optional<Path> inTopic = shelf.find(safeName, wanted);
        if (inTopic.isPresent()) {
            return acquired(inTopic.get(), Origin.REUSED_IN_TOPIC, "课题下已有这篇论文，跳过获取");
        }

        // 3. The shared library: the whole point of it is that this step usually hits.
        //    Tried before other topics because the library is the canonical corpus.
        Optional<Path> fromLibrary = library == null ? Optional.empty() : library.find(safeName, wanted);
        // 4. Another topic's copy: copied, never moved — that topic's analyses reference it.
        Optional<Path> source = fromLibrary.isPresent() ? fromLibrary : elsewhere.stream().findFirst();
        List<Path> copies = new ArrayList<>(elsewhere);
        fromLibrary.ifPresent(copies::add);
        AcquireResult ambiguous = ambiguousName(kind, safeName, copies);
        if (ambiguous != null) {
            return ambiguous;
        }
        if (source.isPresent()) {
            try {
                Path saved = PaperStore.importPdf(source.get(), effectiveTopic, safeName, wanted,
                        props.getMaxPdfBytes(), library);
                String name = PaperStore.shortNameOf(saved);
                if (fromLibrary.isPresent()) {
                    publishDownload(threadId, "本地论文库命中，已复制：" + name);
                    return acquired(saved, Origin.COPIED_FROM_LIBRARY,
                            "复用自本地论文库：" + WorkspacePaths.relative(source.get()));
                }
                String sourceTopic = topicOf(source.get());
                publishDownload(threadId, "已复用其他课题的论文：" + name + "（来自 " + sourceTopic + "）");
                return acquired(saved, Origin.COPIED_FROM_OTHER_TOPIC,
                        "复用自课题 " + sourceTopic + "：" + WorkspacePaths.relative(source.get()));
            } catch (IllegalArgumentException | IOException e) {
                log.warn("复用已有论文失败 [{}]: {}", safeName, e.getMessage());
                if (kind != SourceKind.URL) {
                    return new AcquireResult(false, effectiveTopic, safeName, null, Origin.NOT_FOUND,
                            "复用已有论文失败：" + e.getMessage());
                }
            }
        }

        // 5. Nothing on disk. Only a URL can be fetched; a bare short name with no
        //    existing paper is a lookup miss, and inventing a download target is worse
        //    than saying so.
        if (kind == SourceKind.URL) {
            DownloadResult result = download(effectiveTopic, PaperRef.of(paper.trim(), null, null, null),
                    safeName, threadId);
            return result.pdf() != null ? acquired(result.pdf(), Origin.DOWNLOAD, "已下载")
                    : new AcquireResult(false, effectiveTopic, safeName, null, Origin.NOT_FOUND, result.message());
        }

        return new AcquireResult(false, effectiveTopic, safeName, null, Origin.NOT_FOUND,
                "找不到论文 \"" + safeName + "\"：本地无此文件，各课题 papers/ 下也没有这篇论文。"
                        + "请给出 PDF 路径或 URL，或检查论文短名是否正确。");
    }

    /**
     * A bare short name that different papers carry. Which one is meant is the caller's to
     * say; picking one would close-read the wrong paper without anybody noticing.
     */
    private static AcquireResult ambiguousName(SourceKind kind, String name, List<Path> copies) {
        List<Path> papers = kind == SourceKind.SHORT_NAME ? PaperStore.differentPapers(copies) : List.of();
        if (papers.size() < 2) {
            return null;
        }
        StringBuilder sb = new StringBuilder("短名 \"").append(name).append("\" 对应多篇不同的论文：\n");
        for (Path pdf : papers) {
            sb.append("- ").append(WorkspacePaths.relative(pdf))
                    .append(PdfIdentityReader.identityOf(pdf)
                            .map(printed -> PaperStore.identityNote(printed, PaperIdentity.NONE)).orElse(""))
                    .append('\n');
        }
        sb.append("请指明课题方向，或改用 DOI / arXiv 编号 / PDF 路径。");
        return new AcquireResult(false, null, name, null, Origin.NOT_FOUND, sb.toString());
    }

    private static AcquireResult acquired(Path pdf, Origin origin, String detail) {
        return new AcquireResult(true, topicOf(pdf), PaperStore.shortNameOf(pdf), WorkspacePaths.relative(pdf),
                origin, detail);
    }

    /** The topic a PDF lies in, or null when it is not under {@code investigation/{topic}/papers/}. */
    private static String topicOf(Path pdf) {
        Path papers = pdf.toAbsolutePath().normalize().getParent();
        return papers != null && papers.endsWith(WorkspacePaths.PAPERS_SUBDIR)
                && WorkspacePaths.investigationRoot().equals(papers.getParent().getParent())
                ? papers.getParent().getFileName().toString() : null;
    }

    /** An existing PDF at this reference (absolute, working-dir, or project-root relative), or null. */
    static Path asLocalPdf(String paper) {
        if (paper == null || paper.isBlank()) {
            return null;
        }
        String value = paper.trim();
        if (!WorkspacePaths.hasExtension(value, ".pdf")) {
            return null;
        }
        for (String form : new String[]{value, value.replace('\\', '/')}) {
            try {
                Path normalized = Path.of(form).toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized)) {
                    return normalized;
                }
            } catch (RuntimeException e) {
                // Invalid path syntax on this platform ("http://..." on Windows):
                // not a local file, try the next form and then give up.
            }
        }
        try {
            Path normalized = WorkspacePaths.root().resolve(value.replace('\\', '/')).normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        } catch (RuntimeException e) {
            // Same story for the project-root-relative form.
        }
        return null;
    }

    /** The identifying tail of a URL reference: arXiv id, DOI suffix, or the last path segment. */
    private static String inferNameFromUrl(String value) {
        Matcher arxiv = ARXIV_ID.matcher(value);
        if (arxiv.find()) {
            return arxiv.group(1);
        }
        String withoutQuery = value.split("[?#]", 2)[0];
        int slash = withoutQuery.lastIndexOf('/');
        String tail = slash >= 0 ? withoutQuery.substring(slash + 1) : withoutQuery;
        if (!tail.isBlank()) {
            return tail;
        }
        // A URL that ends in "/" — the host name is better than an empty name.
        return withoutQuery.replaceAll("^https?://", "").split("/", 2)[0];
    }

    private static String stripPdfExtension(String name) {
        return WorkspacePaths.hasExtension(name, ".pdf")
                ? name.substring(0, name.length() - ".pdf".length())
                : name;
    }

    /**
     * Expands the reference forms users paste without a scheme into fetchable URLs:
     * {@code doi:10.x/y} and bare {@code 10.x/y} onto the DOI resolver, and a
     * schemeless {@code arxiv.org/...} onto https. Anything already absolute is
     * returned unchanged.
     */
    static String normalizeUrl(String url) {
        String value = url.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return value;
        }
        if (lower.startsWith("doi:")) {
            return "https://doi.org/" + value.substring(4).trim();
        }
        if (BARE_DOI.matcher(value).find()) {
            return "https://doi.org/" + value;
        }
        if (lower.startsWith("arxiv.org/") || lower.startsWith("www.arxiv.org/")) {
            return "https://" + value;
        }
        return value;
    }

    private String formatFailures(List<String> failures) {
        if (failures.isEmpty()) {
            return "  (no attempts recorded)";
        }
        StringBuilder sb = new StringBuilder();
        for (String failure : failures) {
            sb.append("  - ").append(failure).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String schemeOf(String url) {
        try {
            String scheme = URI.create(url).getScheme();
            return scheme == null ? "https" : scheme;
        } catch (IllegalArgumentException e) {
            return "https";
        }
    }
}

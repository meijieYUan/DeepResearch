package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    private final PaperDownloadProperties props;
    private final HttpClient httpClient;

    public PaperDownloadTool(PaperDownloadProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tool 1: download
    // ──────────────────────────────────────────────────────────────────────

    @Tool(description = """
            Download a paper PDF so it can be read later.
            Saves to investigation/{课题方向}/papers/ inside the project root.
            Accepts a direct PDF link, an arXiv abstract page, a DOI URL, or a publisher landing page —
            if the URL is not already a PDF, the tool locates the actual PDF link on the page.
            Retries transient network failures, verifies the file really is a PDF, and skips
            re-downloading a paper that is already saved. Returns the saved path, page count and SHA-256.
            Only call this for papers that already passed relevance screening.""")
    public String downloadPaper(
            @ToolParam(description = "课题方向, the research topic. Becomes the topic folder name — keep it identical across the whole investigation so the same topic reuses one folder.") String topic,
            @ToolParam(description = "URL of the PDF, arXiv abs page, DOI, or publisher landing page") String url,
            @ToolParam(description = "Short name used as the filename, e.g. 'MS-Diffusion'. Letters, digits, dash, underscore, dot only.") String shortName) {

        if (url == null || url.isBlank()) {
            return "Error: url is required.";
        }
        if (shortName == null || shortName.isBlank()) {
            return "Error: shortName is required (used as the PDF filename).";
        }

        String safeName;
        Path papersRoot;
        try {
            safeName = WorkspacePaths.slugify(shortName, "论文短名");
            papersRoot = WorkspacePaths.papersDir(topic);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        Path target = papersRoot.resolve(safeName + ".pdf").normalize();
        if (!target.startsWith(papersRoot)) {
            return "Error: resolved path escapes the papers directory: " + target;
        }

        // Already downloaded? Dedup is keyed on the resolved file name, so re-running
        // the same paper under the same short name is a no-op. A different shortName
        // for the same paper will fetch it again.
        String normalizedUrl = url.trim();
        if (Files.isRegularFile(target)) {
            Integer pages = PaperStore.pageCount(target);
            if (pages != null) {
                return ok(target, pages, PaperStore.sha256(target), "already downloaded — skipped")
                        + "\n(If this is a different paper, choose a different shortName.)";
            }
        }

        List<String> candidates = buildCandidates(normalizedUrl);
        List<String> failures = new ArrayList<>();
        String lastHtml = null;
        String lastHtmlUrl = null;

        for (String candidate : candidates) {
            FetchOutcome outcome = fetch(candidate, failures);
            if (outcome == null) {
                continue;
            }
            if (outcome.bytes() != null) {
                return saveAndReport(outcome, target, failures);
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
                    return saveAndReport(outcome, target, failures);
                }
            }
            if (!discovered.isEmpty()) {
                failures.add("found " + discovered.size() + " PDF link(s) on the landing page but none downloaded");
            } else {
                failures.add("landing page contained no PDF link (paywalled, or requires JavaScript)");
            }
        }

        return "DOWNLOAD_FAILED: " + normalizedUrl + "\n"
                + "Attempts:\n" + formatFailures(failures) + "\n"
                + "Next: try an alternative source (arXiv mirror, OpenReview, author homepage), "
                + "or fall back to the abstract page and mark this paper SOURCE_LEVEL=ABSTRACT_ONLY.";
    }

    /**
     * Lists what a topic already has.
     *
     * <p>Kept here as well as on {@link PaperTextTool} because the downloader needs it
     * to avoid re-fetching: dedup is keyed on the file name, so the agent has to see
     * the existing names before choosing one.</p>
     */
    @Tool(description = "List papers already downloaded for a topic, with size and page count. Call this before downloading to avoid fetching a paper twice.")
    public String listDownloadedPapers(
            @ToolParam(description = "课题方向, the research topic whose papers should be listed") String topic) {
        return PaperStore.listPdfs(topic);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fetching
    // ──────────────────────────────────────────────────────────────────────

    /** A fetch either produced PDF bytes or an HTML page; never both. */
    private record FetchOutcome(String url, byte[] bytes, String html, Integer status) {
    }

    /**
     * Fetches one URL with retries. Returns null when the URL is unusable, otherwise
     * an outcome carrying either PDF bytes or the HTML body. Failure reasons are
     * appended to {@code failures} so the caller can report every attempt.
     */
    private FetchOutcome fetch(String url, List<String> failures) {
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

        int attempts = Math.max(1, props.getMaxRetries());
        long backoff = props.getInitialBackoffMs();
        String lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .header("User-Agent", props.getUserAgent())
                        .header("Accept", "application/pdf,text/html,application/xhtml+xml,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
                        .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofByteArray());
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

    /** Writes the downloaded bytes to a temp file, validates it, then moves it into place. */
    private String saveAndReport(FetchOutcome outcome, Path target, List<String> failures) {
        // The temp file must share a filesystem with the target for the move to be atomic.
        Path papersRoot = target.getParent();
        Path temp = null;
        try {
            Files.createDirectories(papersRoot);
            temp = Files.createTempFile(papersRoot, ".download-", ".part");
            Files.write(temp, outcome.bytes());

            // Parse before publishing: a file that PDFBox cannot open is not a paper,
            // and leaving it under the final name would poison later reads.
            Integer pages;
            try (PDDocument doc = Loader.loadPDF(temp.toFile())) {
                pages = doc.getNumberOfPages();
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                failures.add(outcome.url() + ": downloaded bytes are not a parseable PDF (" + e.getMessage() + ")");
                return "DOWNLOAD_FAILED: " + outcome.url() + "\n"
                        + "Attempts:\n" + formatFailures(failures) + "\n"
                        + "The response looked like a PDF but could not be parsed. "
                        + "Try an alternative source, or mark this paper SOURCE_LEVEL=ABSTRACT_ONLY.";
            }

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            temp = null;

            String hash = PaperStore.sha256(target);
            log.info("Downloaded paper: {} ({} pages, {} bytes)", target.getFileName(), pages, outcome.bytes().length);
            return ok(target, pages, hash, "downloaded");

        } catch (IOException e) {
            log.error("Failed to save paper to {}", target, e);
            failures.add(outcome.url() + ": could not save file — " + e.getMessage());
            return "DOWNLOAD_FAILED: " + outcome.url() + "\nAttempts:\n" + formatFailures(failures);
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

    // ──────────────────────────────────────────────────────────────────────
    // Candidate URLs
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Expands one user-supplied URL into the concrete URLs worth trying, including
     * normalisations (arXiv abs → pdf) and configured host mirrors.
     */
    /** Package-private so the URL-expansion rules can be tested without a network call. */
    List<String> candidateUrls(String url) {
        return buildCandidates(url);
    }

    private List<String> buildCandidates(String url) {
        List<String> candidates = new ArrayList<>();

        Matcher arxiv = ARXIV_ID.matcher(url);
        if (arxiv.find()) {
            String id = arxiv.group(1);
            // The canonical PDF URL first, then the /pdf/ page (which redirects),
            // then the export mirror for networks where arxiv.org is unreachable.
            candidates.add("https://arxiv.org/pdf/" + id);
            candidates.add("https://arxiv.org/pdf/" + id + ".pdf");
            candidates.add("https://export.arxiv.org/pdf/" + id);
            candidates.add(url);
        } else {
            candidates.add(url);
        }

        // Host-configured mirrors come last: they are a fallback, not the preference.
        for (String candidate : List.copyOf(candidates)) {
            String host = hostOf(candidate);
            List<String> fallbacks = host == null ? null : props.getHostFallbacks().get(host);
            if (fallbacks != null) {
                for (String fallback : fallbacks) {
                    String mapped = applyFallback(candidate, fallback);
                    if (mapped != null && !candidates.contains(mapped)) {
                        candidates.add(mapped);
                    }
                }
            }
        }

        return candidates.stream().distinct().toList();
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

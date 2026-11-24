package com.itajay.superassistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds legal open-access copies of a paper through the Unpaywall API.
 *
 * <p>Why this service and not the alternatives — measured against this deployment's
 * network in 2026-11, three DOIs × three calls each, plus ten back-to-back calls:</p>
 * <ul>
 *   <li><b>Unpaywall</b>: 100% HTTP 200, ~1.1 s, only needs a contact email. No API key,
 *       no metered budget. It runs on the same database as OpenAlex.</li>
 *   <li>OpenAlex: fast (~0.8 s) and also 100%, but keyless use draws from a $0.10/day
 *       budget <em>shared by every client behind the same public IP</em>
 *       ({@code X-RateLimit-Limit-USD: 0.1}). On a company network that budget belongs to
 *       everyone, so it can run dry for reasons this application cannot see or control.</li>
 *   <li>Crossref: reliable, but returns bibliographic metadata only — no OA PDF location.
 *       It is used for the step Unpaywall cannot do: title → DOI
 *       ({@link CrossrefTitleResolver}).</li>
 *   <li>Europe PMC: 20 s timeouts on more than half the calls, and life sciences only.</li>
 * </ul>
 *
 * <p>The lookup is strictly additive. Every failure — no email configured, no DOI in the
 * reference, a timeout, a 404, a malformed body — returns an empty list, and the caller
 * falls back to the candidate chain it used before. A DOI that has no open copy is the
 * common case, not an error, and is treated the same way.</p>
 *
 * <p>Only {@code url_for_pdf} values are returned, never landing pages. A landing page
 * (HAL, a journal's own "free to read" page) is already reachable through the publisher
 * URL the caller tries next, and returning it here would only add one more HTML page to
 * sniff before the real attempt.</p>
 */
public class UnpaywallResolver {

    private static final Logger log = LoggerFactory.getLogger(UnpaywallResolver.class);

    private static final String ENDPOINT = "https://api.unpaywall.org/v2/";

    /**
     * A DOI inside a URL or free text. Stops at whitespace, quotes and the characters that
     * end a URL path ({@code ?#}); trailing sentence punctuation is stripped separately.
     */
    private static final Pattern DOI = Pattern.compile("(10\\.\\d{4,9}/[^\\s\"'<>?#]+)");

    /**
     * arXiv's own DOIs ({@code 10.48550/arXiv.NNNN}) are not in Unpaywall (measured: 404),
     * and the caller already turns an arXiv reference into its PDF URL directly. Asking
     * would cost a second of latency to learn nothing.
     */
    private static final String ARXIV_DOI_PREFIX = "10.48550/";

    private final String email;
    private final Duration timeout;
    private final OpenAccessHttp http;
    private final ObjectMapper mapper = new ObjectMapper();

    public UnpaywallResolver(String email, int timeoutMs, OpenAccessHttp http) {
        this.email = email == null ? "" : email.trim();
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.http = http;
    }

    /** False when no contact email is configured; the caller then skips the lookup entirely. */
    public boolean isEnabled() {
        return !email.isEmpty();
    }

    /**
     * Open-access PDF URLs for the paper a reference points at, best first.
     *
     * @param reference anything that may carry a DOI: a doi.org URL, a bare DOI, a
     *                  publisher URL with the DOI in its path
     * @return direct PDF URLs, deduplicated; empty when disabled, when the reference has
     *         no DOI, when the paper has no open copy, or on any failure
     */
    public List<String> pdfUrlsFor(String reference) {
        if (!isEnabled()) {
            return List.of();
        }
        String doi = extractDoi(reference);
        if (doi == null || isArxivDoi(doi)) {
            return List.of();
        }
        URI uri;
        try {
            // The DOI goes in as one path segment. Encoding its slash keeps a DOI with
            // parentheses or semicolons from being reinterpreted as path structure;
            // measured: the encoded and unencoded forms resolve identically.
            uri = URI.create(ENDPOINT + URLEncoder.encode(doi, StandardCharsets.UTF_8)
                    + "?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        try {
            OpenAccessHttp.Response response = http.get(uri, timeout);
            if (response.status() == 404) {
                log.debug("Unpaywall has no record for DOI {}", doi);
                return List.of();
            }
            if (response.status() != 200 || response.body() == null) {
                log.info("Unpaywall lookup for DOI {} returned HTTP {}; using the other sources only",
                        doi, response.status());
                return List.of();
            }
            List<String> urls = parsePdfUrls(response.body());
            log.info("Unpaywall: DOI {} -> {} open-access PDF location(s)", doi, urls.size());
            return urls;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.info("Unpaywall lookup for DOI {} failed ({}); using the other sources only",
                    doi, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * {@code best_oa_location} first, then every other location that offers a PDF.
     *
     * <p>Unpaywall's own ranking is kept: it already prefers the location most likely to
     * stay up (a repository copy over a publisher's time-limited "bronze" access), which
     * is exactly the preference a download wants.</p>
     */
    List<String> parsePdfUrls(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            return List.of();
        }
        if (root == null || !root.path("is_oa").asBoolean(false)) {
            return List.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        addPdf(urls, root.path("best_oa_location"));
        for (JsonNode location : root.path("oa_locations")) {
            addPdf(urls, location);
        }
        return new ArrayList<>(urls);
    }

    private static void addPdf(Set<String> urls, JsonNode location) {
        String pdf = location.path("url_for_pdf").asText("");
        if (pdf.startsWith("http://") || pdf.startsWith("https://")) {
            urls.add(pdf);
        }
    }

    /** The DOI carried by a reference, without trailing prose punctuation, or null. */
    static String extractDoi(String reference) {
        if (reference == null) {
            return null;
        }
        Matcher matcher = DOI.matcher(reference);
        if (!matcher.find()) {
            return null;
        }
        String doi = matcher.group(1).replaceAll("[.,;:)\\]]+$", "");
        return doi.isEmpty() ? null : doi;
    }

    static boolean isArxivDoi(String doi) {
        return doi != null && doi.toLowerCase(Locale.ROOT).startsWith(ARXIV_DOI_PREFIX);
    }
}

package com.itajay.superassistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Finds the DOI of a paper from its title, so a paper known only by name can still go
 * through the open-access lookup ({@link UnpaywallResolver} needs a DOI).
 *
 * <p>Crossref rather than anything else, measured 2026-11: Unpaywall's own title search
 * was retired on 2026-09-18 (HTTP 410, pointing at OpenAlex), and keyless OpenAlex draws
 * on a $0.10/day budget shared by every client on the same public IP. Crossref needs no
 * key, only a contact address, and answered 100% of calls in ~1.3 s.</p>
 *
 * <p>The matching is strict, because a wrong DOI is worse than none: it would download a
 * different paper under this paper's name, and nothing would notice until close reading.
 * So a hit must be an <em>exact</em> title match (see {@link TitleKey}), and:</p>
 * <ul>
 *   <li>The search is restricted to the four record types a paper can be — journal and
 *       proceedings articles, book chapters, preprints. Without the filter, a famous title
 *       is buried under "Faculty Opinions recommendation of …" review records (measured:
 *       15 of the top 20 hits for the AlphaFold paper).</li>
 *   <li>A year, when known, narrows the window to {@code year-1 … year+2}: the arXiv
 *       version a model cites usually predates the venue version by up to two years.</li>
 *   <li>A short title that matches several different DOIs is ambiguous ("Segment
 *       Anything", "Deep learning") and yields nothing. A long title matching several is
 *       one paper with several records; the published version wins over a preprint.</li>
 * </ul>
 *
 * <p>Arxiv-only papers simply are not in Crossref; the lookup then returns empty and the
 * caller keeps going with the other sources. Every failure degrades the same way.</p>
 */
public class CrossrefTitleResolver {

    private static final Logger log = LoggerFactory.getLogger(CrossrefTitleResolver.class);

    private static final String ENDPOINT = "https://api.crossref.org/works";

    private static final String TYPES =
            "type:journal-article,type:proceedings-article,type:book-chapter,type:posted-content";

    /** Titles shorter than this (as keys) must match a single DOI to count. */
    static final int AMBIGUOUS_BELOW_CHARS = 30;

    /** Titles shorter than this are not worth a lookup at all. */
    private static final int MIN_TITLE_CHARS = 8;

    private final String email;
    private final Duration timeout;
    private final OpenAccessHttp http;
    private final ObjectMapper mapper = new ObjectMapper();

    public CrossrefTitleResolver(String email, int timeoutMs, OpenAccessHttp http) {
        this.email = email == null ? "" : email.trim();
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.http = http;
    }

    public boolean isEnabled() {
        return !email.isEmpty();
    }

    /**
     * The DOI of the paper with exactly this title.
     *
     * @param title the paper's title as the caller has it (case and punctuation do not matter)
     * @param year  publication year, or null when unknown
     * @return the DOI, lower-cased; empty when disabled, not found, ambiguous, or on any failure
     */
    public Optional<String> doiForTitle(String title, Integer year) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String wanted = TitleKey.of(title);
        if (wanted.length() < MIN_TITLE_CHARS) {
            return Optional.empty();
        }
        String filter = TYPES;
        if (year != null && year > 1900 && year < 2200) {
            filter += ",from-pub-date:" + (year - 1) + ",until-pub-date:" + (year + 2);
        }
        URI uri;
        try {
            uri = URI.create(ENDPOINT
                    + "?query.bibliographic=" + URLEncoder.encode(title.strip(), StandardCharsets.UTF_8)
                    + "&rows=20"
                    + "&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)
                    + "&select=DOI,title,subtitle,type"
                    + "&mailto=" + URLEncoder.encode(email, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        try {
            OpenAccessHttp.Response response = http.get(uri, timeout);
            if (response.status() != 200 || response.body() == null) {
                log.info("Crossref title lookup returned HTTP {}; skipping", response.status());
                return Optional.empty();
            }
            Optional<String> doi = pick(wanted, response.body());
            log.info("Crossref: title \"{}\" -> {}", abbreviate(title), doi.orElse("no unambiguous match"));
            return doi;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.info("Crossref title lookup failed ({}); skipping", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** The one DOI the exact-title hits agree on, preferring a published record. */
    Optional<String> pick(String wantedKey, String json) {
        JsonNode items;
        try {
            items = mapper.readTree(json).path("message").path("items");
        } catch (Exception e) {
            return Optional.empty();
        }
        // DOI -> best (lowest) rank seen for it; a published record ranks above a preprint.
        TreeMap<String, Integer> hits = new TreeMap<>();
        for (JsonNode item : items) {
            String doi = item.path("DOI").asText("").toLowerCase(Locale.ROOT);
            if (doi.isEmpty() || !matches(wantedKey, item)) {
                continue;
            }
            int rank = "posted-content".equals(item.path("type").asText()) ? 1 : 0;
            hits.merge(doi, rank, Math::min);
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        if (hits.size() > 1 && wantedKey.length() < AMBIGUOUS_BELOW_CHARS) {
            return Optional.empty();
        }
        return hits.entrySet().stream()
                .min((a, b) -> Integer.compare(a.getValue(), b.getValue()))
                .map(java.util.Map.Entry::getKey);
    }

    /** Title alone, or title + subtitle: Crossref splits "Optuna: A Next-generation …" in two. */
    private static boolean matches(String wantedKey, JsonNode item) {
        String title = first(item.path("title"));
        String subtitle = first(item.path("subtitle"));
        return wantedKey.equals(TitleKey.ofMarkup(title))
                || (!subtitle.isEmpty() && wantedKey.equals(TitleKey.ofMarkup(title + " " + subtitle)));
    }

    private static String first(JsonNode array) {
        return array.isArray() && !array.isEmpty() ? array.get(0).asText("") : "";
    }

    private static String abbreviate(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= 60 ? value : value.substring(0, 60) + "…";
    }
}

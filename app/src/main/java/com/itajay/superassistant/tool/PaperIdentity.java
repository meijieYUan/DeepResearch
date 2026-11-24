package com.itajay.superassistant.tool;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which paper something is: its identifiers and title — never its short name.
 *
 * <p>A short name is a file label a model picks, per conversation. Two papers both called
 * {@code Survey} are two papers; one paper called {@code MS-Diffusion} here and
 * {@code MSDiff2024} there is one paper. Treating the label as the identity is how a request
 * for one paper was answered with another paper's PDF and reported as OK.</p>
 *
 * <p>Values are normalised on construction so that equality means "same identifier": arXiv
 * versions and case are dropped, DOIs are lower-cased, and arXiv's own DOIs
 * ({@code 10.48550/arXiv.…}) become the arXiv id they spell.</p>
 *
 * @param arxivId arXiv id without version, or null
 * @param doi     DOI, lower-cased, or null
 * @param title   title as given, or null; compared through {@link #titleKey()}
 * @param url     a source URL that carries neither of the above, or null
 */
public record PaperIdentity(String arxivId, String doi, String title, String url) {

    /** Nothing known — the caller has only a name. */
    public static final PaperIdentity NONE = new PaperIdentity(null, null, null, null);

    /** Lighter title keys ("survey", "bert") are words, not evidence. See {@link TitleKey#weight}. */
    static final int MIN_TITLE_WEIGHT = 12;

    /** From this weight on, one title under two identifiers is one work in two versions. */
    static final int UNAMBIGUOUS_TITLE_WEIGHT = 30;

    /** Longest identifier spelled out in a file name; beyond it a hash keeps names usable. */
    private static final int MAX_TOKEN = 40;

    private static final String ARXIV_DOI = "10.48550/arxiv.";

    private static final String ARXIV_FORMS = "([0-9]{4}\\.[0-9]{4,5}|[a-z-]+(?:\\.[a-z]{2})?/[0-9]{7})";

    private static final Pattern ARXIV_URL = Pattern.compile(
            "arxiv\\.org/(?:abs|pdf)/" + ARXIV_FORMS, Pattern.CASE_INSENSITIVE);

    private static final Pattern ARXIV_BARE = Pattern.compile(
            "^(?:arxiv[:.]\\s*)?" + ARXIV_FORMS + "(?:v\\d+)?$", Pattern.CASE_INSENSITIVE);

    public PaperIdentity {
        arxivId = canonicalArxivId(arxivIdIn(arxivId));
        doi = lower(UnpaywallResolver.extractDoi(doi));
        if (doi != null && doi.startsWith(ARXIV_DOI)) {
            if (arxivId == null) {
                arxivId = canonicalArxivId(arxivIdIn(doi.substring(ARXIV_DOI.length())));
            }
            doi = null;
        }
        title = title == null || title.isBlank() ? null : title.strip();
        url = normaliseUrl(url);
    }

    /** Reads whatever identity a free-form reference carries: a link, an identifier, or a title. */
    public static PaperIdentity fromReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return NONE;
        }
        String value = reference.strip();
        String arxiv = arxivIdIn(value);
        String doi = UnpaywallResolver.extractDoi(value);
        boolean link = value.matches("(?i)https?://\\S+");
        boolean title = arxiv == null && doi == null && !link
                && TitleKey.weight(TitleKey.of(value)) >= MIN_TITLE_WEIGHT;
        return new PaperIdentity(arxiv, doi, title ? value : null, link ? value : null);
    }

    /** An arXiv id in a URL or on its own ({@code 2304.03373}, {@code arXiv:2304.03373v2}), version dropped. */
    static String arxivIdIn(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        Matcher url = ARXIV_URL.matcher(v);
        if (url.find()) {
            return url.group(1);
        }
        Matcher bare = ARXIV_BARE.matcher(v);
        return bare.matches() ? bare.group(1) : null;
    }

    /** The comparison key of the title, or empty when there is no title worth comparing. */
    String titleKey() {
        String key = TitleKey.of(title);
        return TitleKey.weight(key) < MIN_TITLE_WEIGHT ? "" : key;
    }

    /** Nothing is known. A title too short to compare is still something: it is not "any paper". */
    public boolean isEmpty() {
        return arxivId == null && doi == null && url == null && title == null;
    }

    /**
     * Whether two requests ask for one paper — neither side is a file here. A second request
     * is often the first one corrected, so with the same short name a similar title, or
     * nothing that can be compared, counts as the same.
     */
    boolean isSameRequest(PaperIdentity other, boolean sameName) {
        if ((arxivId != null && arxivId.equals(other.arxivId)) || (doi != null && doi.equals(other.doi))
                || (url != null && url.equals(other.url))) {
            return true;
        }
        boolean idsDiffer = (arxivId != null && other.arxivId != null) || (doi != null && other.doi != null);
        PdfIdentity asPage = new PdfIdentity(null, null, "", title == null ? List.of() : List.of(title));
        return switch (asPage.evidenceFor(other.title)) {
            case EXACT -> sameName || !idsDiffer || TitleKey.weight(titleKey()) >= UNAMBIGUOUS_TITLE_WEIGHT;
            case PARTIAL -> sameName;
            case CONTRADICTS -> false;
            case NONE -> sameName && !idsDiffer;
        };
    }

    /**
     * A file-name-safe token that names this paper, used to tell apart two papers that were
     * given the same short name. Null when nothing is known.
     */
    String fileToken() {
        if (arxivId != null) {
            return arxivId.replace('/', '-');
        }
        if (doi != null) {
            String token = doiToken(doi);
            return token.length() <= MAX_TOKEN ? token : shortHash(doi.getBytes(StandardCharsets.UTF_8));
        }
        String seed = titleKey().isEmpty() ? url : titleKey();
        return seed == null ? null : shortHash(seed.getBytes(StandardCharsets.UTF_8));
    }

    /** This identity, with what a PDF prints about itself filled in; the page's own arXiv id wins. */
    PaperIdentity confirmedBy(PdfIdentity printed) {
        return new PaperIdentity(printed.arxivId() != null ? printed.arxivId() : arxivId,
                doi != null ? doi : printed.doi(), title, url);
    }

    /** This identity, with its gaps filled from another description of the same paper. */
    PaperIdentity or(PaperIdentity other) {
        return new PaperIdentity(arxivId != null ? arxivId : other.arxivId,
                doi != null ? doi : other.doi,
                title != null ? title : other.title,
                url != null ? url : other.url);
    }

    /**
     * True when a file's base name spells this paper out: the arXiv id or DOI alone (how a
     * human names a file the reader cannot identify), or as the suffix {@link #fileToken()}
     * adds. The DOI's slash may be written as any separator, since file names cannot hold it.
     */
    boolean isNamedBy(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return false;
        }
        String base = baseName.strip().toLowerCase(Locale.ROOT);
        if (arxivId != null) {
            String id = arxivId.replace('/', '-');
            String unversioned = base.replace('_', '-').replaceFirst("v\\d+$", "");
            if (unversioned.equals(id) || unversioned.endsWith("-" + id)) {
                return true;
            }
        }
        if (doi != null) {
            String token = doiToken(doi);
            String baseToken = doiToken(base);
            if (baseToken.equals(token) || baseToken.endsWith("-" + token)) {
                return true;
            }
        }
        return arxivId == null && fileToken() != null && base.endsWith("-" + fileToken());
    }

    static String shortHash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static String doiToken(String value) {
        return value.replaceAll("[^a-z0-9.]+", "-").replaceAll("\\.{2,}", ".").replaceAll("^[-.]+|[-.]+$", "");
    }

    /** {@code math.GT/0309136} and {@code math/0309136} are one paper. */
    private static String canonicalArxivId(String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT).replaceFirst("\\.[a-z]{2}(?=/)", "");
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /**
     * Comparable form of a plain source link; null for text, and for links that only wrap an
     * identifier. Normalising an already normalised link changes nothing, so copies keep it.
     */
    private static String normaliseUrl(String value) {
        if (value == null || !value.strip().matches("(?i)https?://\\S+")) {
            return null;
        }
        try {
            URI uri = URI.create(value.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.isEmpty() || host.endsWith("doi.org") || arxivIdIn(value) != null) {
                return null;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
            return "https://" + host + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

package com.itajay.superassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime properties for the paper download / PDF extraction tool.
 *
 * <p>Bound from {@code agent.paper.*}. Defaults are tuned for scholarly hosts
 * (arXiv, OpenReview, publisher landing pages), which are frequently slow,
 * rate-limit aggressively, and sometimes sit behind Cloudflare.</p>
 */
@ConfigurationProperties(prefix = "agent.paper")
public class PaperDownloadProperties {

    /**
     * Folder holding all research output, relative to the project root.
     * PDFs land in {@code {investigationDir}/{topic}/papers/} and documents in
     * {@code {investigationDir}/{topic}/document/}.
     */
    private String investigationDir = "investigation";

    /** Reject downloads larger than this. Guards against a mis-resolved URL streaming a huge file. */
    private long maxPdfBytes = 50L * 1024 * 1024;

    /**
     * Reject suspiciously small responses. Error pages and truncated downloads are
     * far smaller than any real paper.
     */
    private long minPdfBytes = 8L * 1024;

    /** Max characters returned by the text-extraction tool, to protect the model context. */
    private int maxTextChars = 40_000;

    /** Max pages parsed during extraction. Stops a 300-page proceedings volume from burning time. */
    private int maxPages = 60;

    /**
     * Max documents whose per-page extracted text is retained in memory (LRU).
     * Serves repeated reads of the same PDF (analyst close-read, then reviewer
     * re-check, then reanalysis) without re-parsing. 0 or less disables the cache.
     */
    private int textCacheMaxDocs = 32;

    /** Max figures one extraction call may write, so a wide page range cannot flood the folder. */
    private int maxFigures = 8;

    /**
     * Resolution the figure's page region is rasterised at.
     *
     * <p>Figures are cut out of a rendered page rather than lifted from the file, because a
     * framework diagram included from LaTeX is vector art with no bitmap to lift. 200 dpi keeps
     * the small labels inside a diagram legible while a full-width figure stays around 1400 px
     * across.</p>
     */
    private int figureDpi = 200;

    // ── HTTP behaviour ──

    private int connectTimeoutMs = 15_000;

    /** Whole-request timeout. Scholarly PDFs over a slow link can legitimately take a while. */
    private int requestTimeoutMs = 120_000;

    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** Attempts per candidate URL (1 = no retry). */
    private int maxRetries = 3;

    private long initialBackoffMs = 800;

    private long maxBackoffMs = 10_000;

    /**
     * Mirror origins tried when the primary host fails, keyed by that host.
     *
     * <p>Each entry is an origin to swap in while keeping the original path and query,
     * so {@code arxiv.org -> https://export.arxiv.org} rewrites
     * {@code https://arxiv.org/pdf/1706.03762} to
     * {@code https://export.arxiv.org/pdf/1706.03762}. Use {@code %s} in the value to
     * substitute the original path instead, for mirrors with a different layout.</p>
     */
    private java.util.Map<String, java.util.List<String>> hostFallbacks = new java.util.LinkedHashMap<>();

    public String getInvestigationDir() {
        return investigationDir;
    }

    public void setInvestigationDir(String investigationDir) {
        this.investigationDir = investigationDir;
    }

    public long getMaxPdfBytes() {
        return maxPdfBytes;
    }

    public void setMaxPdfBytes(long maxPdfBytes) {
        this.maxPdfBytes = maxPdfBytes;
    }

    public long getMinPdfBytes() {
        return minPdfBytes;
    }

    public void setMinPdfBytes(long minPdfBytes) {
        this.minPdfBytes = minPdfBytes;
    }

    public int getMaxTextChars() {
        return maxTextChars;
    }

    public void setMaxTextChars(int maxTextChars) {
        this.maxTextChars = maxTextChars;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public int getTextCacheMaxDocs() {
        return textCacheMaxDocs;
    }

    public void setTextCacheMaxDocs(int textCacheMaxDocs) {
        this.textCacheMaxDocs = textCacheMaxDocs;
    }

    public int getMaxFigures() {
        return maxFigures;
    }

    public void setMaxFigures(int maxFigures) {
        this.maxFigures = maxFigures;
    }

    public int getFigureDpi() {
        return figureDpi;
    }

    public void setFigureDpi(int figureDpi) {
        this.figureDpi = figureDpi;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        this.maxBackoffMs = maxBackoffMs;
    }

    public java.util.Map<String, java.util.List<String>> getHostFallbacks() {
        return hostFallbacks;
    }

    public void setHostFallbacks(java.util.Map<String, java.util.List<String>> hostFallbacks) {
        this.hostFallbacks = hostFallbacks;
    }
}

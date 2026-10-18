package com.itajay.superassistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured academic search (arXiv Atom API, Semantic Scholar Graph API).
 *
 * <p>Bing HTML scraping is a fallback for the tail of the web, not a literature
 * database: it returns eight loosely ranked snippets, no citation counts, and breaks
 * whenever the page markup changes. These two APIs return titles, authors, years,
 * abstracts, citation counts and open-access PDF links directly, so the research
 * agent can build a candidate pool without re-parsing HTML.</p>
 *
 * <p>Both endpoints are fixed hosts; the only model-controlled input is the query,
 * which is URL-encoded. The tools never fetch a caller-provided URL.</p>
 */
@Component
public class AcademicSearchTool {

    private static final Logger log = LoggerFactory.getLogger(AcademicSearchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 25;
    private static final int ABSTRACT_MAX_CHARS = 1_200;

    private final HttpClient httpClient;

    public AcademicSearchTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Tool(description = """
            Search arXiv (Atom API) for papers. Returns title, authors, published date,
            abstract, and abs/PDF links. Use this first for CS/physics/math topics;
            prefer precise keywords and submit several queries to widen the pool.""")
    public String searchArxiv(
            @ToolParam(description = "Search query, e.g. 'context management for LLM agents'") String query,
            @ToolParam(required = false, description = "Max results, 1-25 (default 10)") Integer maxResults) {
        int limit = clampLimit(maxResults);
        if (query == null || query.isBlank()) {
            return "Error: query must not be blank";
        }
        try {
            String url = "https://export.arxiv.org/api/query?search_query=all:"
                    + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
                    + "&start=0&max_results=" + limit + "&sortBy=relevance";
            String body = get(url);
            return parseArxiv(body, limit);
        } catch (Exception e) {
            log.warn("arXiv search failed for '{}': {}", query, e.getMessage());
            return "arXiv search failed: " + e.getMessage()
                    + "\nTry a simpler query or fall back to webSearch.";
        }
    }

    @Tool(description = """
            Search Semantic Scholar for papers. Returns title, authors, year, venue,
            citation count, abstract and open-access PDF link when available. Use it for
            cross-domain coverage and citation-based ranking.""")
    public String searchSemanticScholar(
            @ToolParam(description = "Search query") String query,
            @ToolParam(required = false, description = "Max results, 1-25 (default 10)") Integer maxResults) {
        int limit = clampLimit(maxResults);
        if (query == null || query.isBlank()) {
            return "Error: query must not be blank";
        }
        try {
            String url = "https://api.semanticscholar.org/graph/v1/paper/search?query="
                    + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
                    + "&limit=" + limit
                    + "&fields=title,authors,year,venue,abstract,citationCount,externalIds,url,openAccessPdf";
            return parseSemanticScholar(get(url), limit);
        } catch (Exception e) {
            log.warn("Semantic Scholar search failed for '{}': {}", query, e.getMessage());
            return "Semantic Scholar search failed: " + e.getMessage()
                    + "\nThe public API is rate-limited; retry later or use searchArxiv.";
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "DeepResearchAgent/1.0 (academic literature research)")
                .header("Accept", "application/atom+xml,application/json")
                .timeout(Duration.ofSeconds(25))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static int clampLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, requested));
    }

    static String parseArxiv(String xml, int limit) {
        Document document = Jsoup.parse(xml, "", Parser.xmlParser());
        List<Element> entries = document.select("entry");
        if (entries.isEmpty()) {
            return "No arXiv results.";
        }
        StringBuilder out = new StringBuilder("arXiv results:\n");
        int shown = 0;
        for (Element entry : entries) {
            if (shown >= limit) {
                break;
            }
            String title = text(entry, "title");
            if (title.isBlank()) {
                continue;
            }
            shown++;
            String abs = text(entry, "id");
            String pdf = entry.select("link[title=pdf]").attr("href");
            if (pdf.isBlank() && abs.contains("/abs/")) {
                pdf = abs.replace("/abs/", "/pdf/");
            }
            List<String> authors = new ArrayList<>();
            for (Element author : entry.select("author > name")) {
                authors.add(author.text());
            }
            out.append('\n').append(shown).append(". ").append(title).append('\n')
                    .append("   Authors: ").append(authors.isEmpty() ? "(unknown)" : String.join(", ", authors))
                    .append('\n')
                    .append("   Published: ").append(text(entry, "published")).append('\n')
                    .append("   arXiv: ").append(abs).append('\n')
                    .append("   PDF: ").append(pdf.isBlank() ? "(none)" : pdf).append('\n')
                    .append("   Abstract: ").append(truncate(text(entry, "summary"), ABSTRACT_MAX_CHARS))
                    .append('\n');
        }
        return shown == 0 ? "No arXiv results." : out.toString();
    }

    static String parseSemanticScholar(String json, int limit) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return "No Semantic Scholar results.";
        }
        StringBuilder out = new StringBuilder("Semantic Scholar results:\n");
        int shown = 0;
        for (JsonNode paper : data) {
            if (shown >= limit) {
                break;
            }
            String title = paper.path("title").asText("");
            if (title.isBlank()) {
                continue;
            }
            shown++;
            List<String> authors = new ArrayList<>();
            for (JsonNode author : paper.path("authors")) {
                authors.add(author.path("name").asText(""));
            }
            String pdf = paper.path("openAccessPdf").path("url").asText("");
            out.append('\n').append(shown).append(". ").append(title).append('\n')
                    .append("   Authors: ").append(authors.isEmpty() ? "(unknown)" : String.join(", ", authors))
                    .append('\n')
                    .append("   Year: ").append(paper.path("year").asText("?"))
                    .append(" | Venue: ").append(paper.path("venue").asText("?"))
                    .append(" | Citations: ").append(paper.path("citationCount").asText("0"))
                    .append('\n')
                    .append("   URL: ").append(paper.path("url").asText("")).append('\n')
                    .append("   PDF: ").append(pdf.isBlank() ? "(none)" : pdf).append('\n')
                    .append("   Abstract: ")
                    .append(truncate(paper.path("abstract").asText(""), ABSTRACT_MAX_CHARS))
                    .append('\n');
        }
        return shown == 0 ? "No Semantic Scholar results." : out.toString();
    }

    private static String text(Element element, String tag) {
        Element found = element.selectFirst(tag);
        return found == null ? "" : found.text().replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}

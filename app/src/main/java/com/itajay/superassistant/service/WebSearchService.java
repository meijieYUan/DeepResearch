package com.itajay.superassistant.service;

import com.itajay.superassistant.security.UrlSafety;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final HttpClient httpClient;

    public WebSearchService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Redirects are followed manually so every hop passes UrlSafety.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Search the web using Bing (cn.bing.com, accessible in China).
     * Returns the top search result snippets.
     */
    public String search(String query) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://cn.bing.com/search?q=" + encodedQuery + "&setlang=zh-cn");

        try (InputStream body = openFollowingSafeRedirects(uri)) {
            String html = new String(readLimited(body), StandardCharsets.UTF_8);
            return parseBingResults(html);
        }
    }

    /**
     * Crawl a web page and extract its text content.
     *
     * <p>The URL comes from the model and can be influenced by prompt injection, so
     * it is validated before the first request and again on every redirect. Jsoup's
     * own connection is deliberately not used for fetching: it follows redirects
     * internally without exposing the hops for validation.</p>
     */
    public String crawl(String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 格式不合法");
        }
        String rejection = UrlSafety.rejectionReason(uri);
        if (rejection != null) {
            throw new IllegalArgumentException("拒绝访问：" + rejection);
        }

        String html;
        try (InputStream body = openFollowingSafeRedirects(uri)) {
            html = new String(readLimited(body), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("抓取被中断", e);
        }

        Document doc = Jsoup.parse(html, uri.toString());
        doc.select("script, style, nav, footer, header, aside").remove();

        String title = doc.title();
        String bodyText = doc.body() == null ? "" : doc.body().text();

        // Truncate if too long (max ~8000 chars to avoid token limits)
        if (bodyText.length() > 8000) {
            bodyText = bodyText.substring(0, 8000) + "...";
        }

        return String.format("Title: %s\n\nContent: %s", title, bodyText);
    }

    /**
     * GETs {@code start}, following up to {@link #MAX_REDIRECTS} hops. Every hop is
     * re-checked with {@link UrlSafety}; returns a 2xx body or throws.
     */
    private InputStream openFollowingSafeRedirects(URI start)
            throws IOException, InterruptedException {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(current)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();

            if (isRedirect(status)) {
                String location = response.headers().firstValue("location").orElse(null);
                closeQuietly(response.body());
                if (location == null || location.isBlank()) {
                    throw new IOException("重定向缺少 Location 头");
                }
                URI next = current.resolve(location.trim());
                String rejection = UrlSafety.rejectionReason(next);
                if (rejection != null) {
                    throw new IOException("拒绝重定向目标：" + rejection);
                }
                log.debug("Redirect {} -> {}", current, next);
                current = next;
                continue;
            }

            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new IOException("抓取失败：HTTP " + status);
            }
            return response.body();
        }
        throw new IOException("重定向次数过多");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("响应过大（超过 " + MAX_RESPONSE_BYTES + " 字节）");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // body of a redirect/error response is irrelevant
        }
    }

    private String parseBingResults(String html) {
        Document doc = Jsoup.parse(html);
        Elements results = doc.select("li.b_algo");

        if (results.isEmpty()) {
            // Fallback: try alternative Bing result selectors
            results = doc.select("ol#b_results > li.b_algo");
        }
        if (results.isEmpty()) {
            results = doc.select(".b_algo");
        }

        if (results.isEmpty()) {
            return "No search results found.";
        }

        StringBuilder sb = new StringBuilder("Search results:\n\n");
        int count = 0;
        for (Element result : results) {
            if (count >= 8) break;

            Element titleEl = result.selectFirst("h2 a");
            Element snippetEl = result.selectFirst(".b_caption p, .b_lineclamp2, .b_lineclamp4");
            Element linkEl = result.selectFirst("cite");

            if (titleEl != null) {
                sb.append(count + 1).append(". **").append(titleEl.text().trim()).append("**\n");
            }
            if (snippetEl != null) {
                sb.append("   ").append(snippetEl.text().trim()).append("\n");
            }
            if (linkEl != null) {
                sb.append("   URL: ").append(linkEl.text().trim()).append("\n");
            }
            sb.append("\n");
            count++;
        }
        return sb.toString().trim();
    }
}

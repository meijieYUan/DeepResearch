package com.itajay.superassistant.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LLM 的 Listwise 精排器。
 * <p>
 * 将候选文档编号后一次性交给 LLM 打分，再按分数降序返回 topK 文档。
 * 若 LLM 调用失败或输出无法解析，则回退到融合阶段的原始顺序。
 */
@Component
public class LlmDocumentReranker implements DocumentReranker {

    private static final Logger log = LoggerFactory.getLogger(LlmDocumentReranker.class);

    private static final int DEFAULT_MAX_DOC_CHARS = 500;
    /** Keep each LLM call's prompt bounded; larger candidate sets are scored in batches. */
    private static final int RERANK_BATCH_SIZE = 20;

    private final ChatClient chatClient;
    private final boolean enabled;
    private final int topK;

    public LlmDocumentReranker(ChatClient chatClient,
                               @Value("${rag.rerank.enabled:true}") boolean enabled,
                               @Value("${rag.rerank.top-k:5}") int topK) {
        this.chatClient = chatClient;
        this.enabled = enabled;
        this.topK = topK;
    }

    @Override
    public List<Document> rerank(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        if (!enabled) {
            return documents.stream().limit(Math.max(1, topK)).toList();
        }
        if (documents.size() == 1) {
            return documents;
        }
        String queryText = query == null ? "" : query.text();

        // Score every candidate, in prompt-sized batches. Truncating to the first
        // batch silently dropped everything recalled by the second/third expanded
        // query and made query expansion pointless.
        List<Double> scores = new ArrayList<>(documents.size());
        for (int from = 0; from < documents.size(); from += RERANK_BATCH_SIZE) {
            int to = Math.min(documents.size(), from + RERANK_BATCH_SIZE);
            List<Double> batchScores = scoreDocuments(queryText, documents.subList(from, to));
            if (batchScores == null || batchScores.size() != to - from) {
                log.warn("LLM rerank failed, keeping all {} fusion candidates in order",
                        documents.size());
                return new ArrayList<>(documents);
            }
            scores.addAll(batchScores);
        }

        List<IndexedScore> indexed = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            indexed.add(new IndexedScore(i, documents.get(i), scores.get(i)));
        }
        indexed.sort((a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            return cmp != 0 ? cmp : Integer.compare(a.index, b.index);
        });
        return indexed.stream().limit(Math.max(1, topK)).map(s -> s.doc).toList();
    }

    private List<Double> scoreDocuments(String queryText, List<Document> candidates) {
        StringBuilder docsBlock = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            docsBlock.append('[').append(i).append("] ")
                    .append(truncate(candidates.get(i).getText(), DEFAULT_MAX_DOC_CHARS))
                    .append("\n\n");
        }
        String prompt = RERANK_PROMPT.formatted(queryText, docsBlock.toString());
        try {
            String raw = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            List<Double> scores = parseScores(raw, candidates.size());
            return scores.isEmpty() ? null : scores;
        } catch (Exception e) {
            log.warn("LLM rerank call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 LLM 输出中解析出 JSON 数组形式的分数。
     */
    static List<Double> parseScores(String raw, int expectedSize) {
        if (raw == null) {
            return List.of();
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        String inner = trimmed.substring(start + 1, end);
        List<Double> scores = new ArrayList<>();
        for (String part : inner.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                scores.add(Double.parseDouble(token));
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return scores.size() == expectedSize ? scores : List.of();
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        if (t.length() <= maxChars) {
            return t;
        }
        return t.substring(0, maxChars) + "…";
    }

    private record IndexedScore(int index, Document doc, double score) {
    }

    private static final String RERANK_PROMPT = """
            你是信息检索精排专家。请根据每个候选文档与用户查询的相关性，为每个候选文档打分。
            - 分数为 0 到 10 的整数：0 表示完全无关，10 表示高度相关。
            - 只输出一个 JSON 数组，数组长度必须等于候选文档数量，顺序与文档编号一致。
            - 不要输出任何解释、注释、Markdown 或多余内容。

            用户查询：%s

            候选文档：
            %s
            请只输出分数数组，例如：[9, 3, 7, 1]。
            """;
}

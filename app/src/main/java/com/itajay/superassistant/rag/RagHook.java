package com.itajay.superassistant.rag;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * BEFORE_AGENT hook of the rag-agent: retrieves context for the current input and
 * injects it as a RAG system prompt.
 *
 * <p>Every stage degrades instead of failing the run: query expansion falls back to
 * the original query, a failing retrieval route is skipped (the other routes still
 * contribute), and a failing rerank falls back to the fused candidates. A down
 * Milvus or a flaky expansion model must not take the whole RAG answer down with
 * it — worst case the answer is grounded in fewer (or no) documents.</p>
 *
 * <p>The rag-agent is stateless by design (history lives in the main-agent; see
 * docs/TECHNICAL.md §4), so {@code ReplaceAllWith} of system+user message per turn
 * is the intended behavior, not a bug.</p>
 */
@Component
public class RagHook extends AgentHook {

    private static final Logger log = LoggerFactory.getLogger(RagHook.class);

    private final QueryExpansion queryExpansion;
    private final DocumentRetrieval documentRetrieval;
    private final DocumentPostRetrieval documentPostRetrieval;

    public RagHook(QueryExpansion queryExpansion, DocumentRetrieval documentRetrieval, DocumentPostRetrieval documentPostRetrieval) {
        this.queryExpansion = queryExpansion;
        this.documentRetrieval = documentRetrieval;
        this.documentPostRetrieval = documentPostRetrieval;
    }

    @Override
    public String getName() {
        return "rag_hook";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.BEFORE_AGENT};
    }

    /** 对用户的提问进行检索增强：扩展 → 多路召回 → 去重精排 → 注入 system prompt。 */
    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        Optional<Object> inputOpt = state.value("input");
        if (inputOpt.isEmpty()) {    //输入不存在
            return CompletableFuture.completedFuture(Map.of());
        }
        if (!(inputOpt.get() instanceof String text) || text.isBlank()) {
            log.warn("RAG input is not usable text ({}); skipping retrieval",
                    inputOpt.get() == null ? "null" : inputOpt.get().getClass().getSimpleName());
            return CompletableFuture.completedFuture(Map.of());
        }

        Query query = Query.builder().text(text).build();

        // 查询扩展失败 → 退回原始查询（一次 LLM 调用失败不该终结整个 RAG 运行）
        List<Query> queries;
        try {
            queries = queryExpansion.doExpand(query);
        } catch (Exception e) {
            log.warn("Query expansion failed; falling back to the original query: {}", e.getMessage());
            queries = List.of(query);
        }
        if (queries == null || queries.isEmpty()) {
            queries = List.of(query);
        }

        // 多路召回：每个扩展查询分别执行 向量 + BM25 检索并做 RRF 融合。
        // 单路失败只损失该路候选，其余路照常。
        List<Document> candidates = new ArrayList<>();
        for (Query expandedQuery : queries) {
            try {
                candidates.addAll(documentRetrieval.doRetrieve(expandedQuery));
            } catch (Exception e) {
                log.warn("Retrieval route failed for query '{}': {}", expandedQuery.text(), e.getMessage());
            }
        }

        // 检索后处理：跨查询去重 + 精排；精排失败退回融合候选（顺序次优但内容完整）
        List<Document> documents;
        try {
            documents = documentPostRetrieval.doPostProcess(query, candidates);
        } catch (Exception e) {
            log.warn("Post-retrieval processing failed; using fused candidates as-is: {}", e.getMessage());
            documents = candidates;
        }

        String context = documents.stream().map(Document::getText).collect
                (Collectors.joining("\n"));
        String systemPrompt = String.format(RAG_TEMPLATE, context);
        List<Message> enhancedMessages = List.of(new SystemMessage(systemPrompt), new UserMessage(text));
        // 使用检索的文档上下文进行回答（默认覆盖历史消息）。
        // ReplaceAllWith 保证该列表替换（而非追加到）rag-agent 的状态消息。
        return CompletableFuture.completedFuture(Map.of("messages", ReplaceAllWith.of(enhancedMessages)));
    }

    private static final String RAG_TEMPLATE = """
    你是用户的知识百科助手。基于以下上下文回答问题。
    如果上下文中没有相关信息，请直接说明你不知道。
    -------------------------------------------
    上下文：
        %s
    """;

}

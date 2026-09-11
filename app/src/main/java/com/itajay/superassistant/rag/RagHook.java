package com.itajay.superassistant.rag;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
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

@Component
public class RagHook extends AgentHook {

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

    /**
     *  对用户的提问进行上下文补充
     * @param state
     * @param config
     * @return
     */
    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        // 从状态中提取用户查询
        //messages——历史消息  input——当前输入的消息
       /* Optional<Object> messagesOpt = state.value("messages");
        if (messagesOpt.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        @SuppressWarnings("unchecked")
        List<Message>messages=(List<Message>)messagesOpt.get();*/
        Optional<Object> inputOpt = state.value("input");
        if(inputOpt.isEmpty()){    //输入不存在
            return CompletableFuture.completedFuture(Map.of());
        }
        String text=String.valueOf(inputOpt.get());
        //构建 Rag Query
        Query query = Query.builder()
             //  .history(messages)
                .text(text).build();
        //结合历史对话进行问题压缩
       // Query compressedQuery = queryTransformation.doCompress(query);
        //将压缩后查询进行问题拓展
        List<Query> queries = queryExpansion.doExpand(query);

        //多路召回：每个扩展查询分别执行 向量 + BM25 检索并做 RRF 融合
        List<Document> candidates = new ArrayList<>();
        for(Query expandedQuery : queries){
            candidates.addAll(documentRetrieval.doRetrieve(expandedQuery));
        }

        //检索后处理：跨查询去重 + 精排（rerank）
        List<Document> documents = documentPostRetrieval.doPostProcess(query, candidates);

        String context = documents.stream().map(Document::getText).collect
                (Collectors.joining("\n"));
        String systemPrompt = String.format(RAG_Template, context);
        List<Message> enhancedMessages = List.of(new SystemMessage(systemPrompt), new UserMessage(text));
        // 使用检索的文档上下文进行回答（默认覆盖历史消息）。
        // ReplaceAllWith 保证该列表替换（而非追加到）rag-agent 的状态消息。
        return CompletableFuture.completedFuture(Map.of("messages", ReplaceAllWith.of(enhancedMessages)));
    }

    public final String RAG_Template= """
    你是用户的知识百科助手。基于以下上下文回答问题。
    如果上下文中没有相关信息，请直接说明你不知道。
    -------------------------------------------
    上下文：
        %s
    """;

}

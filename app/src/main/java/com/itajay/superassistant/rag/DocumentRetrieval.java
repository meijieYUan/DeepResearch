package com.itajay.superassistant.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多路检索：向量检索（Milvus）+ BM25 关键字检索（Lucene），
 * 再通过 RRF 融合为候选列表，交给后续精排。
 *
 * <p>两路相互独立：向量路失败（Embedding 未配置、Milvus 不可达）只损失向量候选，
 * BM25 仍照常参与融合；反之亦然。RAG 的可用性不依赖向量能力。</p>
 */
@Component
public class DocumentRetrieval {

    private static final Logger log = LoggerFactory.getLogger(DocumentRetrieval.class);

    private final DocumentRetriever vectorRetriever;
    private final Bm25DocumentRetriever bm25Retriever;
    private final int fusionTopK;
    private final int rrfK;

    public DocumentRetrieval(VectorStore vectorStore,
                             Bm25DocumentRetriever bm25Retriever,
                             @Value("${rag.retrieval.vector.top-k:5}") int vectorTopK,
                             @Value("${rag.retrieval.vector.similarity-threshold:0.7}") double similarityThreshold,
                             @Value("${rag.retrieval.fusion.top-k:20}") int fusionTopK,
                             @Value("${rag.retrieval.fusion.rrf-k:60}") int rrfK) {
        this.vectorRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(vectorTopK)
                .similarityThreshold(similarityThreshold)
                .build();
        this.bm25Retriever = bm25Retriever;
        this.fusionTopK = fusionTopK;
        this.rrfK = rrfK;
    }

    /**
     * 对一个查询执行"向量 + BM25"多路召回，并做 RRF 融合。
     *
     * @param query 查询
     * @return 融合后的候选文档列表
     */
    public List<Document> doRetrieve(Query query) {
        List<Document> vectorDocs = retrieveWithVector(query);
        List<Document> bm25Docs = retrieveWithBm25(query);
        if (vectorDocs.isEmpty() && bm25Docs.isEmpty()) {
            return List.of();
        }
        return DocumentFusion.reciprocalRankFusion(List.of(vectorDocs, bm25Docs), fusionTopK, rrfK);
    }

    private List<Document> retrieveWithVector(Query query) {
        try {
            return vectorRetriever.retrieve(query);
        } catch (Exception e) {
            log.warn("向量检索不可用，本次仅使用 BM25：{}", e.toString());
            return List.of();
        }
    }

    private List<Document> retrieveWithBm25(Query query) {
        try {
            return bm25Retriever.retrieve(query);
        } catch (Exception e) {
            log.warn("BM25 检索失败，本次仅使用向量结果（若可用）：{}", e.toString());
            return List.of();
        }
    }
}

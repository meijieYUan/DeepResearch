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
import java.util.function.Supplier;

/**
 * 多路检索：向量检索（Milvus）+ BM25 关键字检索（Lucene），
 * 再通过 RRF 融合为候选列表，交给后续精排。
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
        // Each route degrades on its own. Calling them in sequence without a guard
        // meant a Milvus outage also threw away BM25's results, even though keyword
        // recall needs no external service.
        List<Document> vectorDocs = retrieveSafely("vector", () -> vectorRetriever.retrieve(query));
        List<Document> bm25Docs = retrieveSafely("bm25", () -> bm25Retriever.retrieve(query));
        if (vectorDocs.isEmpty() && bm25Docs.isEmpty()) {
            return List.of();
        }
        return DocumentFusion.reciprocalRankFusion(List.of(vectorDocs, bm25Docs), fusionTopK, rrfK);
    }

    private static List<Document> retrieveSafely(String route, Supplier<List<Document>> call) {
        try {
            List<Document> documents = call.get();
            return documents == null ? List.of() : documents;
        } catch (Exception e) {
            log.warn("{} retrieval failed; continuing with the remaining route(s): {}",
                    route, e.getMessage());
            return List.of();
        }
    }
}

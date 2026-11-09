package com.itajay.superassistant.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retrieval routes are independent: a dead vector store must not hide BM25 hits.
 */
class DocumentRetrievalTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final Bm25DocumentRetriever bm25Retriever = mock(Bm25DocumentRetriever.class);

    @Test
    void bm25StillContributesWhenVectorRetrievalFails() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("vector down"));
        Document bm25Hit = new Document("layout control");
        when(bm25Retriever.retrieve(any(Query.class))).thenReturn(List.of(bm25Hit));

        List<Document> result = retrieval().doRetrieve(Query.builder().text("layout").build());

        assertThat(result).containsExactly(bm25Hit);
        verify(bm25Retriever).retrieve(any(Query.class));
    }

    @Test
    void bothRoutesFailingReturnsNoCandidates() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("vector down"));
        when(bm25Retriever.retrieve(any(Query.class))).thenThrow(new IllegalStateException("index down"));

        assertThat(retrieval().doRetrieve(Query.builder().text("layout").build())).isEmpty();
    }

    private DocumentRetrieval retrieval() {
        return new DocumentRetrieval(vectorStore, bm25Retriever, 5, 0.7, 20, 60);
    }
}

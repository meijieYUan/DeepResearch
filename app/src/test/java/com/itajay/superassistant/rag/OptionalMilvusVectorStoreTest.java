package com.itajay.superassistant.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Vector support is optional: without an embedding key or model the store fails
 * closed with a catchable exception, and no Milvus connection is attempted.
 */
class OptionalMilvusVectorStoreTest {

    @Test
    void withoutEmbeddingKeyVectorOperationsFailWithoutTouchingProviders() {
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> embeddingModel = mock(ObjectProvider.class);
        OptionalMilvusVectorStore store =
                new OptionalMilvusVectorStore(embeddingModel, "", "127.0.0.1", 9090);

        assertThatThrownBy(() -> store.add(List.of(new Document("x"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMBEDDING_KEY");
        verifyNoInteractions(embeddingModel);
    }

    @Test
    void missingEmbeddingModelBeanDegradesBeforeCreatingMilvusClient() {
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> embeddingModel = mock(ObjectProvider.class);
        when(embeddingModel.getIfAvailable()).thenReturn(null);
        OptionalMilvusVectorStore store =
                new OptionalMilvusVectorStore(embeddingModel, "configured-key", "127.0.0.1", 9090);

        assertThatThrownBy(() -> store.add(List.of(new Document("x"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EmbeddingModel");
    }
}

package com.itajay.superassistant.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Re-uploading a document must replace its chunks, not append a second copy, and
 * the new version must be written before the old one is deleted.
 */
class RagServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void replacingASourceWritesTheNewVersionBeforeDeletingTheOldOne() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        Bm25Index bm25Index = mock(Bm25Index.class);
        RagService service = new RagService(vectorStore, bm25Index);
        String text = ("context management for agents. ".repeat(20)).trim();

        int chunks = service.addSource(
                new ByteArrayResource(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "paper.txt");

        assertThat(chunks).isPositive();

        ArgumentCaptor<List<Document>> added = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(added.capture());
        verify(bm25Index).add(anyList());
        verify(bm25Index).deleteBySourceExcept(eq("paper.txt"), anyString());

        InOrder order = inOrder(vectorStore, bm25Index);
        order.verify(vectorStore).add(anyList());
        order.verify(bm25Index).add(anyList());
        order.verify(vectorStore).delete(any(Filter.Expression.class));
        order.verify(bm25Index).deleteBySourceExcept(eq("paper.txt"), anyString());

        assertThat(added.getValue()).isNotEmpty().allSatisfy(doc -> {
            assertThat(doc.getMetadata())
                    .containsEntry(Bm25Index.SOURCE_METADATA_KEY, "paper.txt")
                    .containsKey(Bm25Index.SOURCE_VERSION_METADATA_KEY);
        });
    }

    @Test
    void listingAndDeletingSourcesDelegateToBm25() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        Bm25Index bm25Index = mock(Bm25Index.class);
        RagService service = new RagService(vectorStore, bm25Index);
        when(bm25Index.sourceStats()).thenReturn(List.of(new Bm25Index.SourceStat("a.txt", 3)));
        when(bm25Index.deleteBySource("a.txt")).thenReturn(3L);

        assertThat(service.listSources()).containsExactly(new Bm25Index.SourceStat("a.txt", 3));
        assertThat(service.deleteSource("a.txt")).isEqualTo(3);
        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(bm25Index).deleteBySource("a.txt");
    }
}

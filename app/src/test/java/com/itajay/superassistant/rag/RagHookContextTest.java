package com.itajay.superassistant.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagHookContextTest {

    @Test
    void contextLabelsEachChunkWithItsSourceFile() {
        Document first = Document.builder()
                .text("alpha")
                .metadata(Map.of(Bm25Index.SOURCE_METADATA_KEY, "paper-a.pdf"))
                .build();
        Document second = Document.builder()
                .text("beta")
                .metadata(Map.of())
                .build();

        String context = RagHook.buildContext(List.of(first, second));

        assertThat(context).contains("[来源: paper-a.pdf]\nalpha", "[来源: 未知来源]\nbeta");
    }
}

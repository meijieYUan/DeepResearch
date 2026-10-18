package com.itajay.superassistant.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25IndexSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void listsSourcesAndDeletesOnlySupersededVersions() throws Exception {
        Bm25Index index = new Bm25Index(tempDir.resolve("index").toString());
        try {
            index.add(List.of(document("old context management text", "a.txt", "v1")));
            index.add(List.of(document("new context management text", "a.txt", "v2")));

            assertThat(index.sourceStats())
                    .containsExactly(new Bm25Index.SourceStat("a.txt", 2));

            index.deleteBySourceExcept("a.txt", "v2");
            assertThat(index.sourceStats())
                    .containsExactly(new Bm25Index.SourceStat("a.txt", 1));
            assertThat(index.search("new context", 5))
                    .extracting(Document::getText)
                    .containsExactly("new context management text");
        } finally {
            index.close();
        }
    }

    private static Document document(String text, String source, String version) {
        return Document.builder()
                .text(text)
                .metadata(Map.of(
                        Bm25Index.SOURCE_METADATA_KEY, source,
                        Bm25Index.SOURCE_VERSION_METADATA_KEY, version))
                .build();
    }
}

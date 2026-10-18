package com.itajay.superassistant.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;

    private final Bm25Index bm25Index;

    private final TokenTextSplitter tokenTextSplitter;

    public RagService(VectorStore vectorStore, Bm25Index bm25Index) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.tokenTextSplitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(10)
                .withMaxNumChunks(500)
                .withKeepSeparator(true)
                .build();
    }

    public int addSource(Resource resource) {
        return addSource(resource, resource.getFilename());
    }

    /**
     * Ingests one resource. {@code sourceName} is the logical document identity used
     * for reader selection, metadata, and same-source replacement — it is deliberately
     * separate from {@link Resource#getFilename()}, because uploads are read from a
     * randomly named temporary file.
     */
    public int addSource(Resource resource, String sourceName) {
        String filename = (sourceName == null || sourceName.isBlank())
                ? resource.getFilename() : sourceName;
        log.info("Processing resource: {}", filename);

        List<Document> documents = readDocuments(resource, filename);
        log.info("Read {} raw documents from {}", documents.size(), filename);

        if (documents.isEmpty()) {
            log.warn("No documents extracted from {}", filename);
            return 0;
        }

        String version = UUID.randomUUID().toString();
        tagSource(documents, filename, version);
        List<Document> chunks = splitDocuments(documents);
        log.info("Split into {} chunks from {}", chunks.size(), filename);

        storeDocuments(chunks, Map.of(filename, version));
        log.info("Stored {} chunks to vector store and BM25 index", chunks.size());

        return chunks.size();
    }

    public int addSources(List<Resource> resources) {
        List<Document> allChunks = new ArrayList<>();
        Map<String, String> versions = new LinkedHashMap<>();

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            List<Document> documents = readDocuments(resource, filename);
            if (!documents.isEmpty()) {
                String version = UUID.randomUUID().toString();
                tagSource(documents, filename, version);
                versions.put(filename, version);
                allChunks.addAll(splitDocuments(documents));
            }
        }

        if (!allChunks.isEmpty()) {
            storeDocuments(allChunks, versions);
            log.info("Batch stored {} chunks from {} resources", allChunks.size(), resources.size());
        }
        return allChunks.size();
    }

    /**
     * Stamp source name + ingestion version onto each document; the splitter copies
     * them to chunks. The version is what makes replacement safe: new chunks are
     * written first, and only afterwards are the old versions deleted.
     */
    private void tagSource(List<Document> documents, String filename, String version) {
        if (filename == null || filename.isBlank()) {
            return;
        }
        for (Document document : documents) {
            document.getMetadata().put(Bm25Index.SOURCE_METADATA_KEY, filename);
            document.getMetadata().put(Bm25Index.SOURCE_VERSION_METADATA_KEY, version);
        }
    }

    private List<Document> readDocuments(Resource resource, String filename) {
        if (filename == null) {
            log.warn("Resource has no filename, falling back to TextReader");
            return new TextReader(resource).read();
        }

        String lowerName = filename.toLowerCase();

        try {
            if (lowerName.endsWith(".pdf")) {
                return new ParagraphPdfDocumentReader(resource).read();

            } else if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
                return new MarkdownDocumentReader(resource, MarkdownDocumentReaderConfig.defaultConfig()).read();

            } else if (lowerName.endsWith(".txt") || lowerName.endsWith(".log")
                    || lowerName.endsWith(".java") || lowerName.endsWith(".py")
                    || lowerName.endsWith(".xml") || lowerName.endsWith(".json")
                    || lowerName.endsWith(".yml") || lowerName.endsWith(".yaml")
                    || lowerName.endsWith(".html") || lowerName.endsWith(".css")
                    || lowerName.endsWith(".js") || lowerName.endsWith(".sql")) {
                return new TextReader(resource).read();

            } else {
                log.info("Unknown file type '{}', trying TextReader", filename);
                return new TextReader(resource).read();
            }
        } catch (Exception e) {
            log.error("Failed to read resource: {}", filename, e);
            return List.of();
        }
    }

    private List<Document> splitDocuments(List<Document> documents) {
        return tokenTextSplitter.split(documents);
    }

    /**
     * Writes the new version to both indexes first, then removes the superseded
     * versions. The old copy therefore survives a failed ingestion; the version
     * filter also keeps a partially-written new copy from making the old one
     * unreachable.
     */
    private void storeDocuments(List<Document> chunks, Map<String, String> versions) {
        vectorStore.add(chunks);
        try {
            bm25Index.add(chunks);
        } catch (IOException e) {
            log.error("BM25 indexing FAILED — the vector store has these chunks but keyword "
                    + "recall will miss them; re-upload the source to repair. Cause: {}", e.getMessage(), e);
            return; // keep the old BM25 chunks rather than deleting them in a failed state
        }

        versions.forEach((source, version) -> {
            try {
                vectorStore.delete(new FilterExpressionBuilder()
                        .and(new FilterExpressionBuilder()
                                        .eq(Bm25Index.SOURCE_METADATA_KEY, source),
                                new FilterExpressionBuilder()
                                        .ne(Bm25Index.SOURCE_VERSION_METADATA_KEY, version))
                        .build());
            } catch (Exception e) {
                log.warn("Vector store could not clear superseded chunks of source '{}': {}",
                        source, e.getMessage());
            }
            try {
                bm25Index.deleteBySourceExcept(source, version);
            } catch (IOException e) {
                log.warn("BM25 could not clear superseded chunks of source '{}': {}", source, e.getMessage());
            }
        });
    }

    /** Removes a source from both stores. Returns the number of BM25 chunks deleted. */
    public long deleteSource(String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        try {
            vectorStore.delete(new FilterExpressionBuilder()
                    .eq(Bm25Index.SOURCE_METADATA_KEY, source)
                    .build());
        } catch (Exception e) {
            log.warn("Vector store could not delete source '{}': {}", source, e.getMessage());
        }
        try {
            return bm25Index.deleteBySource(source);
        } catch (IOException e) {
            log.warn("BM25 could not delete source '{}': {}", source, e.getMessage());
            return 0;
        }
    }

    /** Current sources and chunk counts, served by the knowledge-base listing API. */
    public List<Bm25Index.SourceStat> listSources() {
        return bm25Index.sourceStats();
    }
}

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
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
        log.info("Processing resource: {}", resource.getFilename());

        List<Document> documents = readDocuments(resource);
        log.info("Read {} raw documents from {}", documents.size(), resource.getFilename());

        if (documents.isEmpty()) {
            log.warn("No documents extracted from {}", resource.getFilename());
            return 0;
        }

        tagSource(documents, resource.getFilename());
        List<Document> chunks = splitDocuments(documents);
        log.info("Split into {} chunks from {}", chunks.size(), resource.getFilename());

        storeDocuments(chunks);
        log.info("Stored {} chunks to vector store and BM25 index", chunks.size());

        return chunks.size();
    }

    public int addSources(List<Resource> resources) {
        List<Document> allChunks = new ArrayList<>();

        for (Resource resource : resources) {
            List<Document> documents = readDocuments(resource);
            if (!documents.isEmpty()) {
                tagSource(documents, resource.getFilename());
                allChunks.addAll(splitDocuments(documents));
            }
        }

        if (!allChunks.isEmpty()) {
            storeDocuments(allChunks);
            log.info("Batch stored {} chunks from {} resources", allChunks.size(), resources.size());
        }
        return allChunks.size();
    }

    /** Stamp the source file name onto each document; the splitter copies it to chunks. */
    private void tagSource(List<Document> documents, String filename) {
        if (filename == null || filename.isBlank()) {
            return;
        }
        for (Document document : documents) {
            document.getMetadata().put(Bm25Index.SOURCE_METADATA_KEY, filename);
        }
    }

    private List<Document> readDocuments(Resource resource) {
        String filename = resource.getFilename();
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
     * 同时写入向量库（Milvus）与 BM25 关键字索引（Lucene）。
     *
     * <p>BM25 侧按来源文件名先删旧再写新：同名文件重复导入不再累积重复 chunk。
     * BM25 失败不再静默——向量库已写入而关键字索引缺失是双写不一致，必须显式告警
     * （此前只留一行 error 日志，检索侧表现为"关键字召回悄悄变少"）。</p>
     */
    private void storeDocuments(List<Document> chunks) {
        vectorStore.add(chunks);
        try {
            chunks.stream()
                    .map(c -> c.getMetadata() == null ? null : c.getMetadata().get(Bm25Index.SOURCE_METADATA_KEY))
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .distinct()
                    .forEach(source -> {
                        try {
                            bm25Index.deleteBySource(source);
                        } catch (java.io.IOException e) {
                            log.warn("BM25 could not clear stale chunks of source '{}': {}", source, e.getMessage());
                        }
                    });
            bm25Index.add(chunks);
        } catch (java.io.IOException e) {
            log.error("BM25 indexing FAILED — the vector store has these chunks but keyword "
                    + "recall will miss them; re-upload the source to repair. Cause: {}", e.getMessage(), e);
        }
    }
}

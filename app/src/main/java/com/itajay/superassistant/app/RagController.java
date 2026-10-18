package com.itajay.superassistant.app;

import com.itajay.superassistant.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Knowledge-base ingestion endpoint.
 *
 * <p>The route lives under {@code /api} like every other controller: the frontend's
 * axios instance is configured with {@code baseURL: '/api'} and the Vite dev proxy
 * forwards the path unchanged, so a mapping without the prefix is a guaranteed 404.</p>
 */
@RestController
@RequestMapping("/api")
public class RagController {
    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/knowledge/upload")
    public Map<String, Object> uploadKnowledge(@RequestParam("file") MultipartFile file) {
        String sourceName = sanitizeSourceName(file.getOriginalFilename());
        log.info("Uploading knowledge file: {}", sourceName);

        if (file.isEmpty()) {
            return failure("上传文件为空");
        }

        try {
            // Read the bytes directly instead of staging a temp file: readers select
            // their parser from the logical source name passed below, and Windows
            // keeps a temp-file handle alive when TextReader does not close its
            // stream. 10MB max keeps this bounded.
            byte[] bytes = file.getBytes();
            int chunks = ragService.addSource(new ByteArrayResource(bytes), sourceName);
            if (chunks <= 0) {
                return failure("未能从文件中提取到可索引的文本");
            }
            return Map.of("success", true, "chunks", chunks, "filename", sourceName);
        } catch (Exception e) {
            log.error("Knowledge upload failed for {}", sourceName, e);
            return failure("知识库导入失败：" + safeMessage(e));
        }
    }

    /** Lists the current knowledge base (source file name + chunk count). */
    @GetMapping("/knowledge/documents")
    public Map<String, Object> listDocuments() {
        return Map.of("documents", ragService.listSources());
    }

    /** Deletes every chunk of one source from the vector store and the BM25 index. */
    @DeleteMapping("/knowledge/documents")
    public Map<String, Object> deleteDocument(@RequestParam("source") String source) {
        if (source == null || source.isBlank()) {
            return failure("source 不能为空");
        }
        long deleted = ragService.deleteSource(source);
        return Map.of("success", true, "source", source, "deletedChunks", deleted);
    }

    /**
     * Original file name without any client-supplied directory component. Used as
     * the document's stable source id, so two uploads of the same file can replace
     * each other's chunks instead of accumulating duplicates.
     */
    static String sanitizeSourceName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "upload";
        }
        String normalized = originalName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = (slash >= 0 ? normalized.substring(slash + 1) : normalized).trim();
        // Control characters would end up in Lucene fields and log lines.
        name = name.replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank()) {
            return "upload";
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private static Map<String, Object> failure(String message) {
        return Map.of("success", false, "error", message);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}

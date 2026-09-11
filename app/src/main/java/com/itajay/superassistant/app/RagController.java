package com.itajay.superassistant.app;

import com.itajay.superassistant.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
@RestController
public class RagController {
    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final ResourceLoader resourceLoader;
    private final RagService ragService;

    public RagController(ResourceLoader resourceLoader, RagService ragService) {
        this.resourceLoader = resourceLoader;
        this.ragService = ragService;
    }

    @PostMapping("/knowledge/upload")
    public Map<String, Object> uploadKnowledge(@RequestParam("file") MultipartFile file) {
        log.info("Uploading knowledge file: {}", file.getOriginalFilename());
        try {
            Path tempFile = Files.createTempFile("rag-", "-" + file.getOriginalFilename());
            file.transferTo(tempFile.toFile());
            Resource resource = resourceLoader.getResource("file:" + tempFile.toAbsolutePath());

            int chunks = ragService.addSource(resource);

            Files.deleteIfExists(tempFile);
            return Map.of("success", true, "chunks", chunks, "filename", file.getOriginalFilename());
        } catch (IOException e) {
            log.error("Upload failed", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
}

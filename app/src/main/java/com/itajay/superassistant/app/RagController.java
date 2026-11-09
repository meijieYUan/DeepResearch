package com.itajay.superassistant.app;

import com.itajay.superassistant.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
public class RagController {
    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/knowledge/upload")
    public Map<String, Object> uploadKnowledge(@RequestParam("file") MultipartFile file) {
        String filename = file.getOriginalFilename();
        log.info("Uploading knowledge file: {}", filename);
        try {
            // Feed the bytes straight from memory instead of spilling to a temp file.
            // The readers (TextReader et al.) obtain an InputStream from the Resource and
            // do not always close it, so a temp file's handle can stay locked on Windows
            // and the subsequent delete fails with "another process is using this file".
            // An in-memory Resource has no such handle to leak.
            byte[] content = file.getBytes();
            int chunks = ragService.addSource(new UploadedResource(content, filename));

            return Map.of("success", true, "chunks", chunks, "filename", filename == null ? "" : filename);
        } catch (IOException e) {
            log.error("Upload failed", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /** In-memory resource that keeps the uploaded file name for type detection. */
    private static final class UploadedResource extends ByteArrayResource {

        private final String filename;

        private UploadedResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}

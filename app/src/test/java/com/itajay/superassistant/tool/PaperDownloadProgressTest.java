package com.itajay.superassistant.tool;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code downloadPaper} reports its outcome to the SSE channel.
 *
 * <p>Offline by construction: the dedup branch fires without any network, which is
 * enough to prove the tool call → threadId → progress-channel wiring. The real
 * network paths are covered by {@link PaperDownloadToolManualTest}.</p>
 */
class PaperDownloadProgressTest {

    private static final String TOPIC = "unit-test-download-progress";

    private final List<ProgressEvent> events = new ArrayList<>();
    private final ProgressChannelRegistry recorder = new ProgressChannelRegistry() {
        @Override
        public void publish(String threadId, ProgressEvent event) {
            // Mirrors the real guard in ProgressChannelRegistry.publish: a null thread
            // is dropped there, so the recorder must drop it too for the wiring under
            // test to be the production wiring.
            if (threadId != null && !threadId.isBlank()) {
                events.add(event);
            }
        }
    };

    private final PaperDownloadTool tool = new PaperDownloadTool(new PaperDownloadProperties(), recorder);

    @AfterEach
    void cleanUp() throws IOException {
        Path topicDir = WorkspacePaths.topicDir(TOPIC);
        if (!Files.isDirectory(topicDir)) {
            return;
        }
        try (var paths = Files.walk(topicDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private ToolContext contextFor(String threadId) {
        return new ToolContext(Map.of("_AGENT_CONFIG_",
                RunnableConfig.builder().threadId(threadId).build()));
    }

    @Test
    void aDedupedDownloadAnnouncesItselfOnTheChannel() throws IOException {
        Path dir = WorkspacePaths.papersDir(TOPIC);
        Files.createDirectories(dir);
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(dir.resolve("Existing.pdf").toFile());
        }

        String result = tool.downloadPaper(TOPIC, "https://example.com/x.pdf", "Existing",
                contextFor("thread-1"));

        assertThat(result).doesNotStartWith("Error:");
        assertThat(events).anySatisfy(event ->
                assertThat(event.detail()).contains("论文已存在，跳过下载：Existing"));
    }

    @Test
    void withoutAThreadIdTheEventsAreSimplyDropped() throws IOException {
        Path dir = WorkspacePaths.papersDir(TOPIC);
        Files.createDirectories(dir);
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(dir.resolve("Existing.pdf").toFile());
        }

        String result = tool.downloadPaper(TOPIC, "https://example.com/x.pdf", "Existing", null);

        // No config → no threadId → publish is a no-op, never a failure.
        assertThat(result).doesNotStartWith("Error:");
        assertThat(events).isEmpty();
    }
}

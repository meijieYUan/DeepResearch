package com.itajay.superassistant.compact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTruncatorTest {

    @TempDir
    Path tempDir;

    @Test
    void truncatesOversizedResultAndSavesOriginalToDisk() throws Exception {
        String original = "word ".repeat(20_000);
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "Read", original)))
                .build();

        List<Message> result = ToolResultTruncator.truncate(
                List.of(response), "thread-1", tempDir);

        assertThat(result).hasSize(1);
        String compacted = ((ToolResponseMessage) result.get(0))
                .getResponses().get(0).responseData();
        assertThat(compacted).contains("[Tool output truncated.");
        assertThat(CompactConfig.estimateTokens(compacted))
                .isLessThan(CompactConfig.TOOL_RESULT_PREVIEW_TOKENS + 100);

        try (Stream<Path> files = Files.walk(tempDir)) {
            Path saved = files.filter(Files::isRegularFile).findFirst().orElseThrow();
            assertThat(Files.readString(saved)).isEqualTo(original);
        }
    }

    @Test
    void leavesSmallResultUnchanged() {
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "Read", "small result")))
                .build();

        List<Message> result = ToolResultTruncator.truncate(
                List.of(response), "thread-1", tempDir);

        assertThat(result.get(0)).isSameAs(response);
    }

    @Test
    void keepsOriginalWhenDiskSaveFails() throws Exception {
        Path blockedPath = tempDir.resolve("blocked-storage");
        Files.writeString(blockedPath, "not a directory");

        String original = "word ".repeat(20_000);
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "Read", original)))
                .build();

        List<Message> result = ToolResultTruncator.truncate(
                List.of(response), "thread-1", blockedPath);

        assertThat(result.get(0)).isSameAs(response);
    }
}

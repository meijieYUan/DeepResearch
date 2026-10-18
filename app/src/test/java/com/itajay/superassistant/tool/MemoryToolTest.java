package com.itajay.superassistant.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Memory files are user data: an ID collision silently overwrites a different fact,
 * and a budget that only evicts from memory lets files grow forever. Both are pinned
 * here because they are invisible in normal use.
 */
class MemoryToolTest {

    @TempDir
    Path tempDir;

    private MemoryTool tool;

    @BeforeEach
    void setUp() {
        tool = new MemoryTool(tempDir.resolve(".memory"));
    }

    @Test
    void preferenceAndProjectShareThePPrefixWithoutColliding() throws IOException {
        tool.remember("preference", "语言偏好", "中文", 8);
        tool.remember("project", "当前项目", "DeepResearch", 7);

        assertThat(factFiles()).containsExactly("p01.md", "p02.md");
    }

    @Test
    void deletingOneFactDoesNotMakeTheNextRememberOverwriteAnother() throws IOException {
        tool.remember("preference", "A", "a", 8);
        tool.remember("preference", "B", "b", 7);

        assertThat(tool.deleteFact("p01")).contains("Deleted");

        String created = tool.remember("preference", "C", "c", 6);
        assertThat(created).contains("[p03]");
        assertThat(factFiles()).containsExactly("p02.md", "p03.md");
    }

    @Test
    void consolidateDoesNotRenumberIdsAcrossTypes() throws IOException {
        tool.remember("preference", "偏好", "p", 9);
        tool.remember("project", "项目", "q", 8);
        tool.remember("event", "低价值事件", "e", 2);

        tool.consolidateMemories();

        // The low-importance event is gone; the two high-value facts keep their
        // original IDs instead of being renumbered onto each other.
        assertThat(factFiles()).containsExactly("p01.md", "p02.md");
        String created = tool.remember("preference", "新偏好", "r", 7);
        assertThat(created).contains("[p03]");
    }

    @Test
    void budgetEvictionRemovesTheFilesToo() throws IOException {
        for (int i = 0; i < 56; i++) {
            tool.remember("knowledge", "topic-" + i, "content-" + i, i == 0 ? 10 : 5);
        }

        assertThat(factFiles()).hasSize(50);
        assertThat(Files.readString(tempDir.resolve(".memory/MEMORY.md")))
                .contains("Total facts: 50 / 50");
    }

    private List<String> factFiles() throws IOException {
        try (var files = Files.list(tempDir.resolve(".memory/facts"))) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}

package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual smoke test for {@link PaperDownloadTool} against real hosts.
 *
 * <p>Disabled by default: it needs outbound network access and reaches arxiv.org,
 * so it must not run in CI. Run explicitly with {@code -Dpaper.it=true} when
 * changing the download logic.</p>
 */
class PaperDownloadToolManualTest {

    private static final String TOPIC = "smoke-test-topic";

    private PaperDownloadProperties props() {
        PaperDownloadProperties props = new PaperDownloadProperties();
        props.setMaxRetries(2);
        props.setInitialBackoffMs(500);
        return props;
    }

    private PaperDownloadTool tool() {
        return new PaperDownloadTool(props(), new ProgressChannelRegistry());
    }

    private PaperTextTool textTool() {
        return new PaperTextTool(props());
    }

    @AfterEach
    void cleanUp() throws IOException {
        Path topicDir = WorkspacePaths.topicDir(TOPIC);
        if (!Files.isDirectory(topicDir)) {
            return;
        }
        try (var paths = Files.walk(topicDir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    @Test
    void downloadsFromArxivAbstractPage() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("paper.it"), "set -Dpaper.it=true to run network tests");

        PaperDownloadTool tool = tool();
        String result = tool.downloadPaper(TOPIC, "https://arxiv.org/abs/1706.03762", "AttentionIsAllYouNeed", null);
        System.out.println(result);

        assertThat(result).startsWith("OK:");
        assertThat(result).contains("pages:");
        // The returned path must be the documented layout.
        assertThat(result).contains("investigation/" + TOPIC + "/papers/AttentionIsAllYouNeed.pdf");

        Path pdf = WorkspacePaths.papersDir(TOPIC).resolve("AttentionIsAllYouNeed.pdf");
        assertThat(pdf).isRegularFile();
        assertThat(Files.size(pdf)).isGreaterThan(100_000L);

        // Extraction must return actual content, not an error. Reading is a separate
        // tool from downloading now — this exercises the writer agent's half.
        String text = textTool().extractPaperText(TOPIC, "AttentionIsAllYouNeed", 1, 2);
        System.out.println(text.substring(0, Math.min(600, text.length())));
        assertThat(text).contains("Pages: 1-2");
        assertThat(text).doesNotStartWith("Error:");

        // Re-downloading the same paper must be recognised and skipped.
        String again = tool.downloadPaper(TOPIC, "https://arxiv.org/abs/1706.03762", "AttentionIsAllYouNeed", null);
        System.out.println(again);
        assertThat(again).contains("already downloaded");

        assertThat(textTool().listDownloadedPapers(TOPIC)).contains("AttentionIsAllYouNeed.pdf");
    }

    @Test
    void rejectsNonPdfUrl() {
        PaperDownloadTool tool = tool();
        String result = tool.downloadPaper(TOPIC, "https://example.com/", "NotAPaper", null);
        System.out.println(result);
        assertThat(result).startsWith("DOWNLOAD_FAILED");
    }

    @Test
    void hostileTopicAndNameCannotEscapeTheInvestigationDirectory() {
        PaperDownloadTool tool = tool();

        // Even with a traversal-shaped topic, the resolved directory stays inside
        // investigation/. The download itself fails (unreachable host), which is fine —
        // the assertion is about where the path lands.
        String result = tool.downloadPaper("../../evil", "https://example.com/x.pdf", "../../../pwned", null);
        System.out.println(result);
        assertThat(result).doesNotContain("evil/").doesNotContain("pwned/");

        // Strings rather than PathAssert: these paths do not exist, and startsWith
        // canonicalizes (and therefore throws) for missing paths.
        String resolved = WorkspacePaths.papersDir("../../evil").toString();
        assertThat(resolved).startsWith(WorkspacePaths.investigationRoot().toString());
        assertThat(resolved).doesNotContain("..");
    }

    @Test
    void listingReportsNothingForAnUnusedTopic() {
        PaperTextTool tool = textTool();
        String listing = tool.listDownloadedPapers("a-topic-with-no-papers");
        assertThat(listing).doesNotStartWith("Error");
        assertThat(listing).contains("No papers downloaded yet");
    }
}

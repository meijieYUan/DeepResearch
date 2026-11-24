package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live check of the open-access route against the real Unpaywall API and real hosts.
 *
 * <p>Skipped unless both {@code -Dpaper.it=true} and {@code -Dunpaywall.email=...} are set:
 * it needs outbound network access and a contact address, neither of which belongs in CI.</p>
 *
 * <p>The DOI is the one that failed through the publisher route in an earlier end-to-end
 * run (IEEE TPAMI, bot-protected). With the lookup in place it must download in one call,
 * from the open copy, without the model having to find another source.</p>
 */
class UnpaywallDownloadManualTest {

    private static final String TOPIC = "unpaywall-smoke-test";

    @AfterEach
    void cleanUp() throws IOException {
        Path dir = WorkspacePaths.topicDir(TOPIC);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void aPaywalledDoiDownloadsFromItsOpenCopy() {
        String email = System.getProperty("unpaywall.email", "");
        Assumptions.assumeTrue(Boolean.getBoolean("paper.it") && !email.isBlank(),
                "set -Dpaper.it=true -Dunpaywall.email=you@example.com to run");

        PaperDownloadProperties props = new PaperDownloadProperties();
        props.setUnpaywallEmail(email);
        props.setMaxRetries(2);
        PaperDownloadTool tool = new PaperDownloadTool(props, new ProgressChannelRegistry(), null, null);

        String url = "https://doi.org/10.1109/TPAMI.2016.2577031";
        System.out.println("candidates: " + tool.candidateUrls(url));
        String result = tool.downloadPaper(TOPIC, url, "FasterRCNN", null);
        System.out.println(result);

        assertThat(tool.candidateUrls(url).get(0)).startsWith("https://arxiv.org/pdf/");
        assertThat(result).startsWith("OK:").contains("source: " + PaperDownloadTool.LABEL_UNPAYWALL);
        assertThat(WorkspacePaths.papersDir(TOPIC).resolve("FasterRCNN.pdf")).isRegularFile();
    }

    /** The title-only route: Crossref finds the DOI, Unpaywall finds the open copy. */
    @Test
    void aPaperKnownOnlyByTitleDownloadsThroughCrossrefAndUnpaywall() {
        String email = System.getProperty("unpaywall.email", "");
        Assumptions.assumeTrue(Boolean.getBoolean("paper.it") && !email.isBlank(),
                "set -Dpaper.it=true -Dunpaywall.email=you@example.com to run");

        PaperDownloadProperties props = new PaperDownloadProperties();
        props.setUnpaywallEmail(email);
        props.setMaxRetries(2);
        PaperDownloadTool tool = new PaperDownloadTool(props, new ProgressChannelRegistry(), null, null);

        var plan = tool.planFor(PaperDownloadTool.PaperRef.of(null, null, null,
                "Training-Free Layout Control with Cross-Attention Guidance"));
        System.out.println("resolved DOI: " + plan.resolvedDoi() + "\n" + plan.describe());
        String result = tool.downloadPaper(TOPIC, null, "LayoutGuidance", null, null,
                "Training-Free Layout Control with Cross-Attention Guidance", 2024, null);
        System.out.println(result);

        assertThat(plan.resolvedDoi()).isEqualToIgnoringCase("10.1109/wacv57701.2024.00526");
        assertThat(result).startsWith("OK:").contains("source: " + PaperDownloadTool.LABEL_UNPAYWALL);
    }
}

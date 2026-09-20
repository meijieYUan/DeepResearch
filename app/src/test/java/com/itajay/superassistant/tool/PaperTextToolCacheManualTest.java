package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual smoke test for the {@link PaperTextTool} page cache against real downloaded
 * papers, replaying the actual workflow shape: the analyst reads pages 1-20 in one
 * call, the reviewer then re-reads subsets of the same PDF to check formulas.
 *
 * <p>Disabled by default: it needs real PDFs under {@code investigation/}, which are
 * produced by running the app, not by the build. Run explicitly with
 * {@code -Dpaper.cache.it=true} when changing the cache logic.</p>
 */
class PaperTextToolCacheManualTest {

    private PaperTextTool tool() {
        return new PaperTextTool(new PaperDownloadProperties());
    }

    private PaperTextTool uncachedTool() {
        PaperDownloadProperties props = new PaperDownloadProperties();
        props.setTextCacheMaxDocs(0);
        return new PaperTextTool(props);
    }

    private void assumeRealPaper(String topic, String name) {
        Path pdf = WorkspacePaths.papersDir(topic).resolve(name + ".pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(pdf),
                "real paper not downloaded: " + pdf);
    }

    private long time(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void reviewerRereadsOfARealPaperHitTheCache() {
        String topic = "智能体上下文管理";
        String name = "CueMem";
        assumeRealPaper(topic, name);

        PaperTextTool tool = tool();
        PaperTextTool reference = uncachedTool();

        // Analyst phase: close-read pages 1-20 — a cold parse.
        long analystMs = time(() -> tool.extractPaperText(topic, name, 1, 20));
        assertThat(tool.pdfOpensForTesting()).isEqualTo(1);

        // Reviewer phase: re-read a subset to verify a formula, twice (review + recheck).
        long reviewMs = time(() -> tool.extractPaperText(topic, name, 10, 12));
        long recheckMs = time(() -> tool.extractPaperText(topic, name, 10, 12));
        assertThat(tool.pdfOpensForTesting()).isEqualTo(1);

        // Cache-served output must be byte-identical to what a parse produces.
        assertThat(tool.extractPaperText(topic, name, 10, 12))
                .isEqualTo(reference.extractPaperText(topic, name, 10, 12));
        assertThat(tool.extractPaperText(topic, name, 1, 20))
                .isEqualTo(reference.extractPaperText(topic, name, 1, 20));

        System.out.printf("[cache] %s analyst(1-20)=%dms, reviewer(10-12)=%dms, recheck(10-12)=%dms%n",
                name, analystMs, reviewMs, recheckMs);
    }

    @Test
    void cachePaysOffMostOnALargePdf() {
        String topic = "多主体布局控制";
        String name = "SpotActor";
        assumeRealPaper(topic, name);

        PaperTextTool tool = tool();
        PaperTextTool reference = uncachedTool();

        long coldMs = time(() -> tool.extractPaperText(topic, name, 1, 20));
        long warmMs = time(() -> tool.extractPaperText(topic, name, 1, 20));

        assertThat(tool.pdfOpensForTesting()).isEqualTo(1);
        assertThat(tool.extractPaperText(topic, name, 1, 20))
                .isEqualTo(reference.extractPaperText(topic, name, 1, 20));

        // Same call against the cache-disabled tool, for a like-for-like comparison.
        long alwaysParseMs = time(() -> reference.extractPaperText(topic, name, 1, 20));

        System.out.printf("[cache] %s cold=%dms, warm=%dms, uncached=%dms%n",
                name, coldMs, warmMs, alwaysParseMs);
        System.out.printf("[cache] header of the cached response:%n%s",
                tool.extractPaperText(topic, name, 1, 2).split("\n\n")[0] + "\n");
    }
}

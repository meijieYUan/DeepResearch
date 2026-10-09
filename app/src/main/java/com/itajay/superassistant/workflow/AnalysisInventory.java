package com.itajay.superassistant.workflow;

import com.itajay.superassistant.tool.AnalysisStore;
import com.itajay.superassistant.workspace.WorkspacePaths;

import java.nio.file.Path;
import java.util.List;

/**
 * The reviewer's checklists of what analyses and figures exist, read from disk.
 *
 * <p>Deliberately not counts a workflow remembered: an inventory built from the files
 * cannot disagree with what the reviewer will actually open. Both the research
 * workflow and the single-paper deep-read workflow render their review inputs through
 * here, so the two cannot drift into listing the same folder in different shapes.</p>
 *
 * <p>Each row carries the number of extracted figures, so a paper with none is visibly
 * a paper that fell back to "图见原文 Fig. N (p.X)" rather than one the reviewer has to
 * open the analysis file to notice.</p>
 */
final class AnalysisInventory {

    private AnalysisInventory() {
    }

    /** Every analysis file of the topic as "短名 — 来源等级 — 路径 — 图片 N 张" rows. */
    static String analysisInventory(String topic) {
        List<Path> files = AnalysisStore.markdownFiles(topic);
        if (files.isEmpty()) {
            return "（没有任何精读结果文件——精读阶段未产出，这本身就是严重问题，请据此判定）";
        }
        // One figures listing shared by every row: re-listing the directory per paper
        // would be O(papers × figures) disk IO on every review round.
        List<Path> allFigures = AnalysisStore.figureFiles(topic);
        StringBuilder sb = new StringBuilder("共 ").append(files.size()).append(" 篇：\n");
        for (Path file : files) {
            AnalysisStore.AnalysisMeta meta = AnalysisStore.readMeta(file);
            sb.append("- ").append(meta.shortName())
                    .append(" — ").append(meta.sourceLevel())
                    .append(" — ").append(WorkspacePaths.relative(file))
                    .append(" — 图片 ").append(figuresOf(allFigures, meta.shortName())).append(" 张")
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * The one-paper form of {@link #analysisInventory}, for the single-paper deep-read
     * review. Returns the same row shape so a reviewer sees no vocabulary change.
     */
    static String singlePaperRow(String topic, String shortName) {
        String wanted = AnalysisStore.nameFor(shortName);
        List<Path> files = AnalysisStore.markdownFiles(topic).stream()
                .filter(f -> AnalysisStore.shortNameOf(f).equals(wanted))
                .toList();
        if (files.isEmpty()) {
            return "（" + shortName + " 没有精读结果文件——精读阶段未产出，这本身就是严重问题，请据此判定）";
        }
        AnalysisStore.AnalysisMeta meta = AnalysisStore.readMeta(files.get(0));
        return "- " + meta.shortName()
                + " — " + meta.sourceLevel()
                + " — " + WorkspacePaths.relative(files.get(0))
                + " — 图片 " + figuresOf(AnalysisStore.figureFiles(topic), meta.shortName()) + " 张";
    }

    /**
     * The list of figure paths that actually exist, so the reviewer's broken-link check
     * is a comparison against disk rather than a guess.
     *
     * <p>Both the analysis-relative and document-relative forms are listed: the
     * reviewer sees the document, which cites the {@code ../analysis/figures/…} form,
     * and the analysis file, which cites {@code figures/…}. Listing both means a
     * mismatch is caught regardless of which file it appears in.</p>
     */
    static String figureInventory(String topic) {
        List<Path> files = AnalysisStore.figureFiles(topic);
        if (files.isEmpty()) {
            return "（一篇都没有截到图。若文档里出现了 ![..](..)，即为凭空引用；"
                    + "若通篇写「图见原文 Fig. N (p.X)」则是合规降级。）";
        }
        StringBuilder sb = new StringBuilder("共 ").append(files.size()).append(" 张：\n");
        for (Path file : files) {
            String name = file.getFileName().toString();
            sb.append("- ").append(name)
                    .append("  →  文档写 ../analysis/figures/").append(name)
                    .append("；analysis 写 figures/").append(name).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * How many extracted figures start with the given paper short name.
     *
     * <p>The prefix comes from {@link AnalysisStore#nameFor} rather than from the
     * caller's string, so it is the same derivation the figure tool used to name the
     * files. Building it here by hand is how a counter silently reports zero: the tool
     * writes {@code {短名}_Fig2.png}, and a prefix of {@code {短名}_p} matches no such
     * file.</p>
     */
    static long figuresOf(List<Path> figureFiles, String paperShortName) {
        String prefix = AnalysisStore.nameFor(paperShortName) + "_";
        return figureFiles.stream()
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .count();
    }
}

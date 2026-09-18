package com.itajay.superassistant.tool;

import com.itajay.superassistant.agent.AnalystAgent;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The writer's entry point for close reading: analyses every downloaded paper of a
 * topic and returns an index of the resulting files.
 *
 * <p><strong>The loop is Java, not the model.</strong> The obvious alternative —
 * handing the analyst the topic and letting it enumerate and decide — makes coverage
 * depend on how diligent the model feels, and this project has already been bitten
 * once by that class of failure (a writer that silently renamed its document each
 * revision round, so the reviewer kept re-reading the old draft). Enumerating the
 * papers folder here means every downloaded paper provably gets an analysis file.</p>
 *
 * <p>Two consequences fall out of that choice for free: a paper already analysed is
 * skipped, so an interrupted run resumes instead of starting over; and one paper
 * that cannot be read does not abort the batch — it is recorded in the index as a
 * failure, and the writer handles it as an {@code ABSTRACT_ONLY} source.</p>
 *
 * <p>Returns an index, never the analyses' bodies. The writer pulls in just the
 * papers it needs through {@link AnalysisReadTool} — keeping the detail out of any
 * single context is the entire point of this split.</p>
 */
@Component
public class PaperAnalysisTool {

    private static final Logger log = LoggerFactory.getLogger(PaperAnalysisTool.class);

    private final AnalystAgent analystAgent;

    public PaperAnalysisTool(AnalystAgent analystAgent) {
        this.analystAgent = analystAgent;
    }

    @Tool(description = """
            Close-read every downloaded paper for a topic and save one four-dimension analysis file per paper.
            Papers already analysed are skipped, so this is safe to call again after an interruption.
            Returns an index of the analysis files — use readPaperAnalysis to read any of them in full.
            Call this once, first, before writing the document.""")
    public String analyzePapers(
            @ToolParam(description = "课题方向, the research topic. Must be identical to the one the papers were downloaded under.") String topic) {

        Path papersDir;
        try {
            papersDir = PaperStore.papersRoot(topic);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        List<Path> pdfs = PaperStore.listPdfFiles(topic);
        if (pdfs.isEmpty()) {
            return "No papers found in " + WorkspacePaths.relative(papersDir)
                    + ".\nNothing to analyse. Report this to the caller — a document cannot be written "
                    + "without material, and inventing content is not an option.";
        }

        List<Outcome> outcomes = new ArrayList<>();
        int skipped = 0;
        for (Path pdf : pdfs) {
            String shortName = PaperStore.shortNameOf(pdf);
            Path target = analysisTarget(topic, shortName);
            if (target == null) {
                // A file name with no usable characters cannot be mapped to an analysis
                // path. Report it rather than skipping silently.
                outcomes.add(Outcome.failed(shortName, null, "文件名不含可用字符，无法生成分析文件路径"));
                continue;
            }
            if (Files.isRegularFile(target)) {
                skipped++;
                outcomes.add(Outcome.of(shortName, target, Status.SKIPPED, "已存在，跳过"));
                continue;
            }
            outcomes.add(analyzeOne(topic, shortName, pdf, target, "首次精读"));
        }

        log.info("analyzePapers done [topic={}, papers={}, skipped={}, failed={}]",
                topic, pdfs.size(), skipped, outcomes.stream().filter(o -> o.status == Status.FAILED).count());
        return render(topic, outcomes, skipped);
    }

    @Tool(description = """
            Re-read ONE paper from scratch and overwrite its saved analysis, then use it.
            Use this when a review found the analysis itself wrong — a mis-transcribed formula,
            a wrong source level, a missing dimension. Not needed for problems in the document's prose.""")
    public String reanalyzePaper(
            @ToolParam(description = "课题方向, the research topic the paper belongs to") String topic,
            @ToolParam(description = "论文短名, the paper's short name, e.g. 'MS-Diffusion'.") String paperShortName,
            @ToolParam(description = "What the review says is wrong with the current analysis, so the re-read targets it.") String reason) {

        Path target;
        try {
            target = AnalysisStore.resolveMarkdown(topic, paperShortName);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.isRegularFile(target)) {
            return "Error: no existing analysis for \"" + paperShortName + "\".\n"
                    + "Run analyzePapers first — reanalyzing only makes sense for a paper already analysed.";
        }

        String shortName = AnalysisStore.shortNameOf(target);
        Path pdf;
        try {
            pdf = PaperStore.resolvePdf(topic, shortName);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.isRegularFile(pdf)) {
            return "Error: the PDF for \"" + shortName + "\" is gone ("
                    + WorkspacePaths.relative(pdf) + "), so it cannot be re-read.\n"
                    + "Ask for a fresh download instead of reanalyzing.";
        }

        Outcome outcome = analyzeOne(topic, shortName, pdf, target, reason);
        if (outcome.status == Status.FAILED) {
            return "Re-analysis FAILED for \"" + shortName + " (" + outcome.detail + ").\n"
                    + "The previous analysis file was left untouched. Report this to the caller rather than "
                    + "inventing the missing detail.";
        }
        return "Re-analysed \"" + shortName + "\" (" + outcome.detail + ").\n"
                + "file: " + WorkspacePaths.relative(target) + "\n"
                + "Read it with readPaperAnalysis(topic, \"" + shortName + "\") and revise the document accordingly.";
    }

    // ──────────────────────────────────────────────────────────────────────
    // One paper
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Runs the analyst on a single paper and reports whether the file actually
     * appeared.
     *
     * <p>Success is judged by the artifact, not by the analyst's reply. The agent's
     * final message is prose, and a run that hits its call limit still exits
     * normally; only the file on disk is evidence that the work was done and saved.
     * Comparing the modification time before and after is what makes this work for
     * re-analysis too, where the file already exists and would otherwise look like
     * an unconditional success.</p>
     */
    private Outcome analyzeOne(String topic, String shortName, Path pdf, Path target, String reason) {
        FileTime before = lastModified(target);
        String input = analystInput(topic, shortName, pdf, reason);
        try {
            analystAgent.reactAgent.invoke(input);
        } catch (Exception e) {
            log.warn("Analyst run failed for {} [topic={}]", shortName, topic, e);
            return Outcome.failed(shortName, target, "精读运行异常：" + e.getMessage());
        }

        FileTime after = lastModified(target);
        if (after == null) {
            // The agent finished without saving. Most often the PDF was unreadable, so
            // say that rather than a bare "it did not save".
            return Outcome.failed(shortName, target,
                    "未能产出分析文件——可能是 PDF 无法解析或加密，应按 ABSTRACT_ONLY 处理");
        }
        if (before != null && after.equals(before)) {
            return Outcome.failed(shortName, target, "分析文件未被更新（重做未生效）");
        }
        return Outcome.of(shortName, target, Status.ANALYSED, headerOf(target));
    }

    /** The per-paper brief handed to the analyst. */
    private static String analystInput(String topic, String shortName, Path pdf, String reason) {
        return """
                课题方向：%s
                论文短名：%s
                PDF 路径：%s

                请只精读这一篇论文，按 references/analysis-guide.md 提取四维信息与截取这一篇的图，
                并用 writePaperAnalysis 落盘（topic 与 paperShortName 必须与上面逐字一致）。

                本次任务说明：%s
                """.formatted(topic, shortName, WorkspacePaths.relative(pdf), reason);
    }

    /** The analysis file for a paper, or null when the short name has no usable characters. */
    private static Path analysisTarget(String topic, String shortName) {
        try {
            return AnalysisStore.resolveMarkdown(topic, shortName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static FileTime lastModified(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The metadata line for the index, read from the header
     * {@link AnalysisWriteTool} writes.
     *
     * <p>Read from the file rather than remembered from the agent's reply, so the
     * index describes what was actually stored. Parsing lives in
     * {@link AnalysisStore} because the workflow reads the same header for its
     * coverage report.</p>
     */
    private static String headerOf(Path target) {
        AnalysisStore.AnalysisMeta meta = AnalysisStore.readMeta(target);
        StringBuilder sb = new StringBuilder()
                .append(meta.sourceLevel())
                .append(" · ").append(meta.chars()).append(" 字符");
        if (!meta.summary().isBlank()) {
            sb.append("\n    ").append(meta.summary());
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Result rendering
    // ──────────────────────────────────────────────────────────────────────

    private String render(String topic, List<Outcome> outcomes, int skipped) {
        long analysed = outcomes.stream().filter(o -> o.status == Status.ANALYSED).count();
        List<Outcome> failed = outcomes.stream().filter(o -> o.status == Status.FAILED).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("论文精读完成：本次分析 ").append(analysed).append(" 篇");
        if (skipped > 0) {
            sb.append("，跳过 ").append(skipped).append(" 篇（已有分析）");
        }
        if (!failed.isEmpty()) {
            sb.append("，**失败 ").append(failed.size()).append(" 篇**");
        }
        sb.append("。\n分析文件目录：")
                .append(WorkspacePaths.relative(WorkspacePaths.analysisDir(topic)))
                .append("\n\n");

        for (Outcome o : outcomes) {
            sb.append(o.status == Status.FAILED ? "- ✗ " : "- ")
                    .append(o.shortName).append(" — ").append(o.detail).append('\n');
        }

        if (!failed.isEmpty()) {
            sb.append("\n失败说明：").append(failed.size())
                    .append(" 篇论文无法读取正文，没有分析文件。撰写文档时把它们按 ABSTRACT_ONLY 处理，")
                    .append("在论文清单和正文中明确标注其可信度限制——不得凭标题或印象补写它们的方法细节与公式。\n");
        }

        sb.append("""
                ────────────────────────────────────────
                下一步：用 readPaperAnalysis(topic, 论文短名) 按需读取某一篇的完整四维信息。
                撰写文档时**只做归纳与对比**，不要把分析文件的正文搬进文档——
                文档要保持精炼，详细内容由这些文件承载。
                """);
        return sb.toString();
    }

    private enum Status { ANALYSED, SKIPPED, FAILED }

    private record Outcome(String shortName, Path path, Status status, String detail) {

        static Outcome of(String shortName, Path path, Status status, String detail) {
            return new Outcome(shortName, path, status, detail);
        }

        static Outcome failed(String shortName, Path path, String detail) {
            return new Outcome(shortName, path, Status.FAILED, detail);
        }
    }
}

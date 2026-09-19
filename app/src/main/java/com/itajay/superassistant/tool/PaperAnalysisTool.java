package com.itajay.superassistant.tool;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.itajay.superassistant.agent.AnalystAgent;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.progress.ProgressStage;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Close reading for a topic: analyses the downloaded papers and returns an index of
 * the resulting files.
 *
 * <p><strong>The batch enumeration is Java, not the model.</strong> The obvious
 * alternative — handing the analyst the topic and letting it enumerate and decide —
 * makes coverage depend on how diligent the model feels, and this project has already
 * been bitten once by that class of failure (a writer that silently renamed its
 * document each revision round, so the reviewer kept re-reading the old draft).
 * Enumerating the papers folder here means every downloaded paper provably gets an
 * analysis file.</p>
 *
 * <p><strong>The batch call blocks; the parallelism is internal.</strong> A tool
 * result has to exist while the tool call runs — the ReAct protocol has no way to
 * complete a call later and push the outcome into a live conversation. The two
 * honest shapes are "the model polls" and "Java waits", and polling is the worst of
 * both: every poll is a full writer LLM round trip over its whole context, dozens of
 * times per batch, decided by the model's own cadence. So the batch runs to
 * completion inside one tool call. {@link #analyzeTopicSync} is the workflow-facing
 * form of the same path: the research-write-review workflow drives it directly
 * (Java waits on it, the model never polls) and hands the final index to the writer
 * in its next input — which is the callback the model actually receives. Both forms
 * publish a per-paper progress event to the SSE channel as each paper lands.</p>
 *
 * <p><strong>The pending analyses run in parallel.</strong> Each paper is an
 * independent analyst run that writes only its own files, and the framework gives
 * every {@code invoke} its own state and config, so concurrent runs share the one
 * {@link AnalystAgent} safely; the call limit hook counts per run. The lane count is
 * bounded by {@code agent.paper.analysis-parallelism} because each lane is a full
 * LLM session — unbounded lanes would just hit the provider's rate limit.</p>
 *
 * <p><strong>A failed paper is retryable on its own.</strong> A paper whose analysis
 * is missing (the batch failed on it) can be analysed alone via
 * {@code analyzePapers(topic, paper)} — the writer's retry entry, so it does not
 * have to degrade that paper to {@code ABSTRACT_ONLY} without trying again. This
 * path never overwrites an existing analysis file; forced redo stays with
 * {@code reanalyzePaper}, which is driven by review findings and carries a reason.</p>
 *
 * <p>Returns an index, never the analyses' bodies. The writer pulls in just the
 * papers it needs through {@link AnalysisReadTool} — keeping the detail out of any
 * single context is the entire point of this split.</p>
 */
@Component
public class PaperAnalysisTool {

    private static final Logger log = LoggerFactory.getLogger(PaperAnalysisTool.class);

    /**
     * What the final index starts with — the marker prompts use to tell the writer
     * the analysis is complete.
     */
    static final String FINAL_INDEX_MARKER = "论文精读完成：";

    private final AnalystRun analystRun;
    private final ProgressChannelRegistry progress;
    private final int parallelism;

    // Two constructors exist (the package-private one is a test seam), so Spring
    // must be told which one to use — implicit single-constructor autowiring only
    // applies when there is exactly one.
    @Autowired
    public PaperAnalysisTool(AnalystAgent analystAgent,
                             ProgressChannelRegistry progress,
                             @Value("${agent.paper.analysis-parallelism:4}") int parallelism) {
        this(analystAgent.reactAgent::invoke, progress, parallelism);
    }

    /** Test seam: the real one is {@link AnalystAgent#reactAgent} invoked with the brief. */
    PaperAnalysisTool(AnalystRun analystRun, ProgressChannelRegistry progress, int parallelism) {
        this.analystRun = analystRun;
        this.progress = progress;
        this.parallelism = Math.max(1, parallelism);
    }

    /** One analyst run: same shape as {@code ReactAgent.invoke(String)}. */
    @FunctionalInterface
    interface AnalystRun {
        Optional<OverAllState> invoke(String input) throws Exception;
    }

    @Tool(description = """
            Close-read the downloaded papers of a topic and save one four-dimension analysis file per paper.
            In the research-write-review workflow the batch has already been run for you and the final index \
            (starting with "论文精读完成：") is attached to your input — do NOT call this without the paper \
            argument. Without the paper argument it analyses EVERY not-yet-analysed paper in parallel and \
            blocks until the whole batch is done (already-analysed papers are skipped, so it is safe).
            If the index reports failures, retry each failed paper ONCE by calling this with the paper \
            argument set to its short name; only a paper that fails again is treated as ABSTRACT_ONLY.
            Returns an index of the analysis files — use readPaperAnalysis to read any of them in full.""")
    public String analyzePapers(
            @ToolParam(description = "课题方向, the research topic. Must be identical to the one the papers were downloaded under.") String topic,
            @ToolParam(required = false, description = """
                    可选，论文短名。指定时同步分析这一篇（用于重试索引里报告为失败的论文）；\
                    省略时批量分析该课题下全部未精读论文（并行、阻塞直到完成）。\
                    工作流已批量精读完成时不要省略此参数。""") String paper,
            ToolContext toolContext) {

        String threadId = threadIdOf(toolContext);
        if (paper != null && !paper.isBlank()) {
            return analyzeSingle(topic, paper.strip(), threadId);
        }
        return analyzeBatchBlocking(topic, threadId, -1);
    }

    /** Test convenience: no tool context, so no SSE. */
    String analyzePapers(String topic, String paper) {
        return analyzePapers(topic, paper, null);
    }

    /**
     * The workflow-facing batch entry: same blocking, internally-parallel path the
     * tool uses, plus per-paper SSE progress addressed to the workflow's thread.
     *
     * <p>The workflow calls this before every writer round. When every paper already
     * has an analysis the call degrades to an enumeration plus a render — cheap
     * enough to repeat, and it is what lets a revision round's input carry a fresh
     * index reflecting any {@code reanalyzePaper} the writer did in the previous
     * round.</p>
     *
     * @param threadId the conversation thread for SSE progress; null disables publishing
     * @param round    the workflow round, carried on the progress events
     */
    public String analyzeTopicSync(String topic, String threadId, int round) {
        return analyzeBatchBlocking(topic, threadId, round);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Batch
    // ──────────────────────────────────────────────────────────────────────

    private String analyzeBatchBlocking(String topic, String threadId, int round) {
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

        // Slots align with pdfs so the index comes back in enumeration order regardless
        // of which lane finishes first. Slots needing no analyst run (invalid name,
        // already analysed) are resolved serially; the rest go to the pool.
        int skipped = 0;
        List<Outcome> outcomes = new ArrayList<>(pdfs.size());
        List<PendingJob> pending = new ArrayList<>();
        for (Path pdf : pdfs) {
            String shortName = PaperStore.shortNameOf(pdf);
            Path target = analysisTarget(topic, shortName);
            if (target == null) {
                // A file name with no usable characters cannot be mapped to an analysis
                // path. Report it rather than skipping silently.
                outcomes.add(Outcome.failed(shortName, null, "文件名不含可用字符，无法生成分析文件路径"));
            } else if (Files.isRegularFile(target)) {
                skipped++;
                outcomes.add(Outcome.of(shortName, target, Status.SKIPPED, "已存在，跳过"));
            } else {
                outcomes.add(null); // filled from the Future below
                pending.add(new PendingJob(outcomes.size() - 1, shortName, pdf, target));
            }
        }

        int lanes = Math.min(parallelism, Math.max(pending.size(), 1));
        publishProgress(threadId, round, "并行精读 " + pending.size() + " 篇论文（" + lanes + " 路并行）");
        runPending(topic, threadId, round, pending, outcomes, pdfs.size());

        long failed = outcomes.stream().filter(o -> o.status == Status.FAILED).count();
        log.info("analyzePapers done [topic={}, papers={}, skipped={}, failed={}, parallelism={}]",
                topic, pdfs.size(), skipped, failed, lanes);
        return render(topic, outcomes, skipped);
    }

    /**
     * Runs the pending analyses, in parallel when there is more than one and a lane
     * to spare, folding each result into its slot as it lands.
     */
    private void runPending(String topic, String threadId, int round,
                            List<PendingJob> pending, List<Outcome> outcomes, int total) {
        if (pending.isEmpty()) {
            return;
        }
        if (pending.size() == 1 || parallelism == 1) {
            int started = 0;
            int done = 0;
            for (PendingJob job : pending) {
                publishPaperStart(threadId, round, job.shortName(), ++started, pending.size());
                Outcome outcome = analyzeOne(topic, job.shortName(), job.pdf(), job.target(), "首次精读");
                outcomes.set(job.index(), outcome);
                publishPaperDone(threadId, round, outcome, ++done, pending.size());
            }
            return;
        }

        int lanes = Math.min(parallelism, pending.size());
        log.info("analyzePapers running {} papers on {} lanes [topic={}]", pending.size(), lanes, topic);
        // One extra thread for this collector: it waits on the per-paper futures, so a
        // pool of exactly `lanes` could starve the papers it is supposed to run.
        ExecutorService executor = Executors.newFixedThreadPool(lanes + 1, task -> {
            Thread thread = new Thread(task, "paper-analysis");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Outcome>> futures = new ArrayList<>(pending.size());
            AtomicInteger started = new AtomicInteger();
            AtomicInteger done = new AtomicInteger();
            for (PendingJob job : pending) {
                futures.add(executor.submit(() -> {
                    // Announced from inside the lane, so the event fires when the paper
                    // is actually picked up — the frontend sees motion from the first
                    // second instead of a silent multi-minute wait.
                    publishPaperStart(threadId, round, job.shortName(),
                            started.incrementAndGet(), pending.size());
                    return analyzeOne(topic, job.shortName(), job.pdf(), job.target(), "首次精读");
                }));
            }
            for (int i = 0; i < pending.size(); i++) {
                PendingJob job = pending.get(i);
                Outcome outcome;
                try {
                    outcome = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcome = Outcome.failed(job.shortName(), job.target(), "被中断");
                } catch (ExecutionException e) {
                    // analyzeOne catches everything it can; this is a defect in the
                    // plumbing itself, reported as that paper's failure so the batch
                    // still produces a complete index.
                    log.warn("Analyst run failed for {} [topic={}]", job.shortName(), topic, e.getCause());
                    outcome = Outcome.failed(job.shortName(), job.target(),
                            "精读运行异常：" + e.getCause().getMessage());
                }
                outcomes.set(job.index(), outcome);
                publishPaperDone(threadId, round, outcome, done.incrementAndGet(), pending.size());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** The single-paper form: the writer's retry entry for a paper the index reported as failed. */
    private String analyzeSingle(String topic, String paper, String threadId) {
        Path pdf;
        try {
            pdf = PaperStore.resolvePdf(topic, paper);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.isRegularFile(pdf)) {
            return "Error: no downloaded paper named \"" + paper + "\" in this topic.\n"
                    + "Check the short name against the index; if the paper was never downloaded, "
                    + "report it to the caller rather than retrying.";
        }

        String shortName = PaperStore.shortNameOf(pdf);
        Path target = analysisTarget(topic, shortName);
        if (target == null) {
            return "Error: 短名 \"" + shortName + "\" 不含可用字符，无法生成分析文件路径。";
        }
        if (Files.isRegularFile(target)) {
            return "\"" + shortName + "\" 已有分析文件，本次未重做（" + headerOf(target) + "）。\n"
                    + "file: " + WorkspacePaths.relative(target) + "\n"
                    + "如需**强制重做**（审查指出分析内容有误时），用 reanalyzePaper(topic, \""
                    + shortName + "\", reason)。";
        }

        Outcome outcome = analyzeOne(topic, shortName, pdf, target, "失败篇目的单篇重试");
        if (outcome.status == Status.FAILED) {
            publishProgress(threadId, 0, "单篇重试仍失败：" + shortName + "（将按 ABSTRACT_ONLY 处理）");
            return "重试仍失败：\"" + shortName + "\" — " + outcome.detail + "\n"
                    + "不要再重试这一篇。按 ABSTRACT_ONLY 处理并在文档中标注其可信度限制，"
                    + "不得凭标题或印象补写它的方法细节与公式。";
        }
        publishProgress(threadId, 0, "单篇重试成功：" + shortName);
        return "重试成功：\"" + shortName + "\"（" + outcome.detail + "）\n"
                + "file: " + WorkspacePaths.relative(target) + "\n"
                + "用 readPaperAnalysis(topic, \"" + shortName + "\") 读取后纳入文档。";
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
            analystRun.invoke(input);
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
    // Progress
    // ──────────────────────────────────────────────────────────────────────

    private void publishProgress(String threadId, int round, String detail) {
        if (progress != null) {
            progress.publish(threadId, ProgressEvent.of(ProgressStage.WRITING, round, detail));
        }
    }

    /** The conversation thread for SSE, or null when the caller carried no config. */
    private static String threadIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        return ToolContextHelper.getConfig(toolContext)
                .flatMap(RunnableConfig::threadId)
                .orElse(null);
    }

    /** One lane has picked up a paper; tell the SSE channel so the wait looks alive. */
    private void publishPaperStart(String threadId, int round, String shortName, int started, int total) {
        publishProgress(threadId, round, "开始精读 " + shortName + "（" + started + "/" + total + "）");
    }

    /** One paper has landed; tell the SSE channel if anyone is listening. */
    private void publishPaperDone(String threadId, int round, Outcome outcome, int done, int total) {
        if (total <= 0) {
            return;
        }
        String mark = outcome.status() == Status.FAILED ? "精读失败 " : "精读完成 ";
        publishProgress(threadId, round, mark + outcome.shortName() + "（" + done + "/" + total + "）");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Result rendering
    // ──────────────────────────────────────────────────────────────────────

    private String render(String topic, List<Outcome> outcomes, int skipped) {
        long analysed = outcomes.stream().filter(o -> o.status == Status.ANALYSED).count();
        List<Outcome> failed = outcomes.stream().filter(o -> o.status == Status.FAILED).toList();

        StringBuilder sb = new StringBuilder();
        sb.append(FINAL_INDEX_MARKER).append("本次分析 ").append(analysed).append(" 篇");
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
                    .append(" 篇论文没有分析文件。对每篇失败论文先调用 analyzePapers(topic, \"论文短名\") ")
                    .append("单篇重试一次；重试仍失败才按 ABSTRACT_ONLY 处理，")
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

    /** A paper awaiting its analyst run, and where its result belongs in the index. */
    private record PendingJob(int index, String shortName, Path pdf, Path target) {
    }
}

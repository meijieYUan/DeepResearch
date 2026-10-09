package com.itajay.superassistant.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.itajay.superassistant.agent.ReviewerAgent;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressContext;
import com.itajay.superassistant.progress.ProgressEvent.Operation;
import com.itajay.superassistant.progress.ProgressEvent.Status;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.progress.ProgressStage;
import com.itajay.superassistant.tool.AnalysisStore;
import com.itajay.superassistant.tool.PaperAnalysisTool;
import com.itajay.superassistant.tool.PaperDownloadTool;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single-paper deep-read workflow: acquire → close read → review, with a bounded
 * redo loop. One known paper in, one reviewed {@code analysis/{短名}.md} out.
 *
 * <p>This is the light path beside {@link ResearchWriteReviewWorkflow}: no search, no
 * screening, no comparison document. A user who already knows which paper they want
 * should not pay for a literature survey to get it read. The two workflows share the
 * analyst and the reviewer — the close reading and its quality gate are the same
 * work either way — and differ in what surrounds them.</p>
 *
 * <p>The deliverable is the analysis file itself. There is deliberately no second
 * document: with one paper there is no comparison to draw, and the four-dimension
 * analysis <em>is</em> the close reading, written to be opened and checked by a human.</p>
 *
 * <p>Same Java-driven shape as the research workflow: each stage is a separate
 * {@code invoke} with an explicit input, progress is published best-effort, and the
 * revision routes by {@link ReviewResult.Target} — a finding about the analysis redoes
 * the reading, a finding about the material cannot be fixed here and is reported
 * honestly rather than papered over.</p>
 */
@Component
public class PaperDeepReadWorkflow {

    private static final Logger log = LoggerFactory.getLogger(PaperDeepReadWorkflow.class);

    /** Content-review rounds; a format-only retry does not consume another round. */
    private static final int MAX_ROUNDS = 2;
    private static final int MAX_CONTENT_REDOS = MAX_ROUNDS - 1;

    private final PaperDownloadTool paperDownloadTool;
    private final PaperAnalysisTool paperAnalysisTool;
    private final WorkflowAgentRun reviewerAgent;
    private final ProgressChannelRegistry progress;

    @Autowired
    public PaperDeepReadWorkflow(PaperDownloadTool paperDownloadTool,
                                 PaperAnalysisTool paperAnalysisTool,
                                 ReviewerAgent reviewerAgent,
                                 ProgressChannelRegistry progress) {
        this(paperDownloadTool, paperAnalysisTool,
                (input, config) -> invoke(reviewerAgent.reactAgent, input, config, "reviewer-agent"),
                progress);
    }

    /** Application-level seam: tests exercise the real workflow without a live model. */
    PaperDeepReadWorkflow(PaperDownloadTool paperDownloadTool,
                          PaperAnalysisTool paperAnalysisTool,
                          WorkflowAgentRun reviewerAgent,
                          ProgressChannelRegistry progress) {
        this.paperDownloadTool = paperDownloadTool;
        this.paperAnalysisTool = paperAnalysisTool;
        this.reviewerAgent = reviewerAgent;
        this.progress = progress;
    }

    @Tool(description = """
            Deep-read ONE specific paper into its four-dimension analysis file and review it against the PDF. \
            Accepts a local PDF path, the short name of a paper already under investigation/*/papers/, or a URL \
            (direct PDF / arXiv page / DOI / publisher landing page). Reuses a PDF already on disk before ever \
            downloading. Writes only investigation/{topic}/analysis/{短名}.md (plus its figures) — no research \
            document. The reviewer checks formulas and figure citations against the PDF; up to 2 content reviews \
            and 1 content redo. A malformed review may be retried once without redoing the analysis. \
            Returns the analysis path, source level, figure count, review verdict and any remaining issues. \
            Takes minutes; call once per paper and wait.""")
    public String deepReadPaper(
            @ToolParam(description = """
                    论文来源，三选一：① 本地 PDF 路径（绝对路径或项目相对路径）；\
                    ② 已下载论文的短名（investigation/ 下 papers/ 里的文件名，可带可不带 .pdf）；\
                    ③ URL——直接 PDF 链接、arXiv abs/pdf 页、DOI 或出版商落地页。""")
            String paper,
            @ToolParam(required = false, description = """
                    课题方向（文件夹名）。省略时：已有论文沿用其所在课题；需要新落地（本地复制/下载）\
                    则默认以论文短名作为课题方向。""")
            String topic,
            @ToolParam(required = false, description = """
                    论文短名（文件名）。省略时推断：本地路径取文件名去扩展名；URL 取末段去查询与扩展名，\
                    arXiv 取其 id。""")
            String shortName,
            @ToolParam(required = false, description = """
                    可选的精读侧重要求：关注章节、必须逐字核对的公式、使用场景等。\
                    会注入精读任务说明，并作为审查的验收基准之一。""")
            String requirements,
            @ToolParam(required = false, description = """
                    为 true 时强制重做已有分析（忽略已存在的 analysis 文件）。\
                    省略或 false 时复用已有分析，只补审查。""")
            Boolean forceRedo,
            ToolContext toolContext) {

        String threadId = threadIdOf(toolContext);
        ProgressContext run = new ProgressContext(threadId, UUID.randomUUID().toString(),
                ProgressEvent.Workflow.PAPER_DEEP_READ, ProgressStage.RESEARCHING, 0, topic,
                shortName == null || shortName.isBlank() ? paper : shortName);
        publish(run, Operation.WORKFLOW, Status.RUNNING, "开始单篇精读工作流");

        PaperDownloadTool.AcquireResult acquired;
        try {
            publish(run, Operation.STAGE, Status.RUNNING, "正在获取论文");
            acquired = paperDownloadTool.acquirePaper(topic, paper, shortName, threadId);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("PaperDeepRead workflow failed while acquiring [paper={}]", paper, e);
            publish(run, Operation.STAGE, Status.FAILED, "论文获取异常终止");
            publish(run.at(ProgressStage.FAILED, 0), Operation.WORKFLOW, Status.FAILED,
                    "论文获取异常终止", "精读失败");
            return "Paper deep-read workflow failed while acquiring the paper: " + e.getMessage();
        }

        if (!acquired.ok()) {
            publish(run, Operation.STAGE, Status.FAILED, "论文获取失败");
            publish(run.at(ProgressStage.FAILED, 0), Operation.WORKFLOW, Status.FAILED,
                    "论文获取失败", "精读失败");
            return "Paper deep-read workflow failed: could not get the paper.\n" + acquired.detail()
                    + "\n向用户如实说明：没有 PDF 就无法精读，不得凭标题或印象编造分析内容。";
        }

        String topicId = acquired.topic();
        String paperShortName = acquired.shortName();
        run = run.forPaper(topicId, paperShortName);
        publish(run, Operation.STAGE, Status.SUCCEEDED, "论文获取完成");
        ProgressContext activity = run;
        log.info("PaperDeepRead workflow started [paper={}, topic={}, origin={}, thread={}]",
                paperShortName, topicId, acquired.origin(), threadId);

        ReviewResult review = null;
        int rounds = 0;
        int redoCount = 0;
        String failureNote = null;
        try {
            // First read. forceRedo routes straight to reanalyze, which overwrites the
            // existing file; without it a pre-existing analysis is reused as-is — the
            // same skip rule the research workflow's batch pass applies.
            boolean analysed;
            String analysisIndex;
            activity = run.at(ProgressStage.ANALYZING, 1);
            publish(activity, Operation.STAGE, Status.RUNNING, "开始精读论文");
            if (Boolean.TRUE.equals(forceRedo) && analysisExists(topicId, paperShortName)) {
                analysed = paperAnalysisTool.reanalyzeSync(topicId, paperShortName,
                        "用户要求强制重做精读", threadId, 1, requirements, activity);
                analysisIndex = analysed ? "论文精读完成：已强制重做。" : "论文精读完成：**强制重做失败**。";
            } else {
                analysisIndex = paperAnalysisTool.analyzeSingleSync(
                        topicId, paperShortName, threadId, 1, requirements, activity);
                analysed = analysisExists(topicId, paperShortName);
            }
            if (!analysed || !analysisExists(topicId, paperShortName)) {
                // No artifact means nothing to review: the gate cannot pass what does
                // not exist, and a review round over an empty file only burns time.
                publish(activity, Operation.STAGE, Status.FAILED, "精读未产出分析文件");
                publish(run.at(ProgressStage.FAILED, 1), Operation.WORKFLOW, Status.FAILED,
                        "精读未产出分析文件", "精读失败");
                return report(acquired, null, rounds, redoCount,
                        analysisIndex + "\n（精读未产出可用分析文件，审查未执行。）");
            }

            publish(activity, Operation.STAGE, Status.SUCCEEDED, "精读阶段结束，分析文件已存在");

            while (rounds < MAX_ROUNDS) {
                rounds++;
                activity = run.at(ProgressStage.REVIEWING, rounds);
                ProgressContext reviewContext = activity;
                AtomicInteger reviewAttempt = new AtomicInteger(1);
                publish(activity, Operation.STAGE, Status.RUNNING, "对照 PDF 原文核对精读结果");
                publishReview(reviewContext, Status.RUNNING, "开始核对精读结果", 1);
                // Build once for this artifact. ReviewRunner may append a format-repair
                // instruction, but must not redo content or consume another content round.
                String input = reviewInput(topicId, paperShortName, acquired, requirements, analysisIndex);
                review = null; // An exception in this review must not reuse the previous verdict.
                review = ReviewRunner.run(reviewerAgent, input, reviewContext.childConfig(requirements),
                        () -> publishReview(reviewContext, Status.RETRYING,
                                "审查输出格式无效，正在重新审查（不重做精读）", reviewAttempt.incrementAndGet()));
                Status verdict = review == null || review.executionFailed() ? Status.FAILED
                        : review.approved() ? Status.PASS : Status.REVISE;
                publishReview(reviewContext, verdict, "审查结果：" + verdict, reviewAttempt.get());
                publish(reviewContext, Operation.STAGE, verdict, "质量审查结束：" + verdict);
                if (review == null || review.executionFailed()) {
                    break;
                }

                RevisionAction action = revisionPlan(review);
                if (action != RevisionAction.REANALYZE
                        || rounds >= MAX_ROUNDS || redoCount >= MAX_CONTENT_REDOS) {
                    break;
                }

                activity = run.at(ProgressStage.REVISING, rounds);
                publish(activity, Operation.STAGE, Status.RUNNING, "审查指出精读结果有误，重做精读");
                // Count attempts before invocation, including false returns and exceptions.
                // A user's forceRedo above is not an automatic review-driven revision.
                redoCount++;
                boolean redone = paperAnalysisTool.reanalyzeSync(topicId, paperShortName,
                        revisionReason(review), threadId, rounds + 1, requirements,
                        run.at(ProgressStage.ANALYZING, rounds + 1));
                publish(activity, Operation.STAGE, redone && analysisExists(topicId, paperShortName)
                        ? Status.SUCCEEDED : Status.FAILED, redone ? "精读修订阶段结束" : "精读修订失败");
                if (!redone || !analysisExists(topicId, paperShortName)) {
                    // No fresh artifact: another review cannot validate a failed redo.
                    log.warn("Re-analysis failed for {} [topic={}]; stopping the loop", paperShortName, topicId);
                    failureNote = "按审查意见重做精读失败，未执行后续审查。";
                    break;
                }
                analysisIndex = "论文精读完成：已按审查意见重做精读。";
            }

            boolean reviewFailed = review == null || review.executionFailed();
            boolean approved = !reviewFailed && review.approved();
            publish(run.at(approved ? ProgressStage.DONE : ProgressStage.FAILED, rounds),
                    Operation.WORKFLOW, approved ? Status.SUCCEEDED : Status.FAILED,
                    approved ? "精读通过审查" : reviewFailed
                            ? "审查未完成，精读质量未经验证" : "精读未通过审查，已输出遗留问题",
                    approved ? "精读完成" : "精读失败");
            log.info("PaperDeepRead workflow finished [paper={}, rounds={}, redos={}, approved={}]",
                    paperShortName, rounds, redoCount, approved);
            return report(acquired, review, rounds, redoCount, failureNote);

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("PaperDeepRead workflow failed [paper={}, topic={}]", paperShortName, topicId, e);
            if (activity.stage() == ProgressStage.REVIEWING) {
                publishReview(activity, Status.FAILED, "审查执行异常终止", null);
            }
            publish(activity, Operation.STAGE, Status.FAILED, "阶段异常终止");
            publish(run.at(ProgressStage.FAILED, rounds), Operation.WORKFLOW, Status.FAILED,
                    "工作流异常终止", "精读失败");
            return "Paper deep-read workflow failed: " + e.getMessage()
                    + "\n(Per-paper analyses already saved under investigation/ are kept — a re-run will reuse them.)\n"
                    + report(acquired, review, rounds, redoCount, "工作流异常终止：" + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Revision routing
    // ──────────────────────────────────────────────────────────────────────

    /** What the next round should do, given the review's findings. */
    enum RevisionAction {
        /** Passed. */
        APPROVED,
        /** No usable review was produced: stop without changing the analysis. */
        REVIEW_FAILED,
        /** The analysis itself is wrong: re-read the paper with the findings as the target. */
        REANALYZE,
        /** Only material-level findings remain, which a re-read cannot fix: stop and report. */
        RESIDUAL_ONLY
    }

    /**
     * Maps the review's findings onto the one revision this workflow has.
     *
     * <p>{@code WRITER} findings fold into {@code REANALYZE}: in single-paper mode the
     * analysis is the only deliverable, so there is no document to rewrite and every
     * fixable finding is a finding about the analysis. {@code RESEARCHER} findings are
     * material problems (the PDF is unreadable, a source is missing) that re-reading
     * cannot fix — the loop stops and the report says so, rather than burning the
     * remaining round on a change that cannot address them.</p>
     */
    static RevisionAction revisionPlan(ReviewResult review) {
        if (review == null || review.executionFailed()) {
            return RevisionAction.REVIEW_FAILED;
        }
        if (review.approved()) {
            return RevisionAction.APPROVED;
        }
        boolean fixable = review.issuesOrEmpty().stream()
                .anyMatch(i -> i.targetEnum() != ReviewResult.Target.RESEARCHER);
        return fixable ? RevisionAction.REANALYZE : RevisionAction.RESIDUAL_ONLY;
    }

    /**
     * The redo brief: every finding the re-read should target.
     *
     * <p>Both {@code ANALYST} and {@code WRITER} findings travel here. The reviewer is
     * told to label analysis-content problems {@code ANALYST}, but a mislabelled
     * {@code WRITER} finding is still a problem in the analysis — dropping it would
     * silently discard a real issue.</p>
     */
    static String revisionReason(ReviewResult review) {
        StringBuilder sb = new StringBuilder("审查指出以下问题（含 WRITER 类——单篇模式下等同 ANALYST，请在重做中一并修正）：\n\n");
        sb.append(review.renderFor(ReviewResult.Target.ANALYST));
        String writer = review.renderFor(ReviewResult.Target.WRITER);
        if (!writer.isEmpty()) {
            sb.append(sb.charAt(sb.length() - 1) == '\n' ? "" : "\n").append(writer);
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Stage inputs
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Reviewer input for single-paper mode: one object to audit, and what to audit it
     * against.
     *
     * <p>Opens by declaring the mode, because the reviewer's guide and template are
     * written for a research document that does not exist here — without the
     * declaration the reviewer would spend its budget hunting for a missing survey.
     * The analysis is not pasted in: the reviewer reads it through
     * {@code readPaperAnalysis}, same as the research workflow, so one large file
     * never has to ride in a single input.</p>
     */
    private String reviewInput(String topicId, String shortName,
                               PaperDownloadTool.AcquireResult acquired, String requirements,
                               String analysisIndex) {
        return """
                请审查以下**单篇精读结果**。本次为「单篇精读模式」：没有调研文档，
                template/template.md 的文档结构检查（S*）与文档对比检查不适用——
                按 review-guide.md「单篇精读模式」一节执行。唯一审查对象是这一篇的 analysis 文件。

                课题方向：%s
                论文短名：%s
                PDF：%s（来源：%s）
                精读侧重要求（验收基准之一）：%s

                --- 单篇精读清单（用 readPaperAnalysis(topic, 论文短名) 读入核对）---
                %s
                --- 清单结束 ---

                --- 已截取的图片清单（analysis 里每个 ![..](..) 都必须指向这其中的一个文件）---
                %s
                --- 清单结束 ---

                精读阶段交付说明：
                %s

                核验要点：公式与细节逐字对照 PDF 原文（extractPaperText），来源等级是否属实，
                四维是否完整，图片链接是否落在清单内。有疑问时读原文，不要凭印象判断。
                每条 issue 必须给出 target：analysis 内容有误（公式抄错、来源等级不实、四维缺失）填 ANALYST；
                PDF 本身拿不到或损坏这类材料问题填 RESEARCHER；其余视作 ANALYST
                （单篇模式没有文档可改，WRITER 等同 ANALYST）。
                输出约定 JSON（ReviewResult.Payload schema）。
                """.formatted(topicId, shortName,
                acquired.pdfRelativePath() == null ? "(未知)" : acquired.pdfRelativePath(),
                acquired.origin() + " — " + acquired.detail(),
                requirements == null || requirements.isBlank() ? "（无）" : requirements.strip(),
                AnalysisInventory.singlePaperRow(topicId, shortName),
                AnalysisInventory.figureInventory(topicId),
                analysisIndex == null || analysisIndex.isBlank() ? "（无）" : analysisIndex.strip());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Reporting
    // ──────────────────────────────────────────────────────────────────────

    /**
     * The run's report: what was read, how well, and what a review could not clear.
     *
     * <p>The source level leads the caveats because it is the one number that bounds
     * every claim in the analysis: an {@code ABSTRACT_ONLY} run has no formulas to
     * trust, and a user who is not told that will read the file as if it had them.</p>
     */
    /** Legacy convenience entry; the workflow supplies its actual redo-attempt count. */
    static String report(PaperDownloadTool.AcquireResult acquired,
                         ReviewResult review, int rounds, String failureNote) {
        return report(acquired, review, rounds, Math.max(0, rounds - 1), failureNote);
    }

    static String report(PaperDownloadTool.AcquireResult acquired,
                         ReviewResult review, int rounds, int redoCount, String failureNote) {
        String topicId = acquired.topic();
        String paperShortName = acquired.shortName();
        boolean reviewFailed = review == null || review.executionFailed();
        boolean approved = failureNote == null && !reviewFailed && review.approved();

        StringBuilder sb = new StringBuilder();
        sb.append("Paper deep-read workflow result\n");
        sb.append("论文: ").append(paperShortName).append('\n');
        sb.append("课题: ").append(topicId).append('\n');
        sb.append("PDF: ").append(acquired.pdfRelativePath() == null ? "(未落地)" : acquired.pdfRelativePath())
                .append("（").append(originLabel(acquired)).append("）\n");

        Path analysisFile = analysisFile(topicId, paperShortName);
        sb.append("分析文件: ").append(analysisFile == null
                ? "(未产出)" : WorkspacePaths.relative(analysisFile)).append('\n');

        if (analysisFile != null) {
            AnalysisStore.AnalysisMeta meta = AnalysisStore.readMeta(analysisFile);
            sb.append("来源等级: ").append(meta.sourceLevel())
                    .append(" · 图片 ").append(AnalysisInventory.figuresOf(
                            AnalysisStore.figureFiles(topicId), paperShortName)).append(" 张");
            if (!meta.summary().isBlank()) {
                sb.append("\n一句话定位: ").append(meta.summary());
            }
            sb.append('\n');
        }

        sb.append("审查结论: ").append(reviewFailed ? "审查未完成" : approved
                ? "通过 (PASS)" : "未通过 (REVISE)").append('\n');
        sb.append("内容审查轮次: ").append(rounds).append('/').append(MAX_ROUNDS).append('\n');
        sb.append("内容修订次数: ").append(redoCount).append('/').append(MAX_CONTENT_REDOS)
                .append("（按审查意见重做的尝试；不含用户 forceRedo）\n");
        if (failureNote != null) {
            sb.append('\n').append(failureNote).append('\n');
        }
        if (review != null && review.summary() != null && !review.summary().isBlank()) {
            sb.append("\n审查总体意见:\n").append(review.summary()).append('\n');
        }
        if (approved) {
            sb.append("\n精读结果已通过质量审查，可以直接交付用户。\n");
        } else if (reviewFailed) {
            sb.append("\n审查未产出可用结论，精读质量未经验证；这是审查执行或输出格式故障，")
                    .append("并非已证实分析内容有误。不得声称结果已核验通过。\n");
            if (review != null) {
                sb.append("\n审查故障信息:\n");
                for (ReviewResult.ReviewIssue issue : review.issuesOrEmpty()) {
                    sb.append("- ").append(issue.render()).append('\n');
                }
            }
        } else {
            sb.append("\n精读经过 ").append(rounds).append(" 轮内容审查、")
                    .append(redoCount).append(" 次内容修订尝试，仍未通过审查。")
                    .append("请向用户如实说明以下遗留问题，不得声称结果已核验通过。\n");
            appendIssues(sb, review);
        }

        sb.append("""

                汇报要求：向用户说明分析文件位置（investigation/{课题}/analysis/，用户可以直接打开）、
                来源等级与可信度限制（ABSTRACT_ONLY / SNIPPET 的论文没有可核对的公式，必须点名）、
                导出图片数量、审查结论与遗留问题；未通过审查时不得声称已核验通过。
                """);
        return sb.toString();
    }

    /** The issues a review could not clear, each on its own line. */
    private static void appendIssues(StringBuilder sb, ReviewResult review) {
        List<ReviewResult.ReviewIssue> issues = review.issuesOrEmpty();
        sb.append("\n遗留问题（").append(issues.size()).append(" 条）:\n");
        for (ReviewResult.ReviewIssue issue : issues) {
            sb.append("- ").append(issue.render()).append('\n');
        }
    }

    private static String originLabel(PaperDownloadTool.AcquireResult acquired) {
        return switch (acquired.origin()) {
            case LOCAL_COPY -> "来源：本地复制";
            case REUSED_IN_TOPIC -> "来源：本课题已有";
            case COPIED_FROM_OTHER_TOPIC -> "来源：复用其他课题 — " + acquired.detail();
            case DOWNLOAD -> "来源：下载";
            case NOT_FOUND -> "来源：未获取";
        };
    }

    /** The analysis file for the paper, or null when it does not exist. */
    private static Path analysisFile(String topicId, String shortName) {
        try {
            Path file = AnalysisStore.resolveMarkdown(topicId, shortName);
            return Files.isRegularFile(file) ? file : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean analysisExists(String topicId, String shortName) {
        return analysisFile(topicId, shortName) != null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Plumbing (mirrors ResearchWriteReviewWorkflow — see its notes on why)
    // ──────────────────────────────────────────────────────────────────────

    /** Runs one sub-agent and returns its text output. */
    private static String invoke(ReactAgent agent, String input, RunnableConfig config, String name) {
        Optional<OverAllState> result;
        try {
            result = config == null ? agent.invoke(input) : agent.invoke(input, config);
        } catch (GraphRunnerException e) {
            throw new IllegalStateException(name + " failed to run: " + e.getMessage(), e);
        }
        String output = result.map(PaperDeepReadWorkflow::lastAnswer).orElse("");
        if (output.isBlank()) {
            log.warn("{} returned no output", name);
        }
        return output;
    }

    /**
     * The agent's final answer. See {@code ResearchWriteReviewWorkflow.lastAnswer}:
     * sub-agents configure no output key, so read the last assistant message that
     * carries text rather than {@code state.value("output")}.
     */
    private static String lastAnswer(OverAllState state) {
        List<?> messages = state.value("messages", List.class).orElse(List.of());
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage message) {
                String text = message.getText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private String threadIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        return ToolContextHelper.getConfig(toolContext)
                .flatMap(RunnableConfig::threadId)
                .orElse(null);
    }

    private void publish(ProgressContext context, Operation operation, Status status, String detail) {
        publish(context, operation, status, detail, null);
    }

    private void publish(ProgressContext context, Operation operation, Status status, String detail, String label) {
        progress.publish(context.threadId(), context.event(operation, status, detail, label, null, null, null));
    }

    private void publishReview(ProgressContext context, Status status, String detail, Integer attempt) {
        progress.publish(context.threadId(), context.event(Operation.REVIEW, status, detail,
                null, null, attempt, 2));
    }

    private void publish(String threadId, ProgressStage stage, int round, String detail) {
        progress.publish(threadId, ProgressEvent.of(stage, round, detail));
    }

    /** Same, with a display label overriding the stage's generic one. */
    private void publish(String threadId, ProgressStage stage, int round, String detail, String label) {
        progress.publish(threadId, ProgressEvent.of(stage, round, detail, label));
    }
}

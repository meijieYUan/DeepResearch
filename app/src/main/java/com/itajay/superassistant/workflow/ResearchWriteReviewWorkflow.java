package com.itajay.superassistant.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.itajay.superassistant.agent.ResearchAgent;
import com.itajay.superassistant.agent.ReviewerAgent;
import com.itajay.superassistant.agent.WriterAgent;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressContext;
import com.itajay.superassistant.progress.ProgressEvent.Operation;
import com.itajay.superassistant.progress.ProgressEvent.Status;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.progress.ProgressStage;
import com.itajay.superassistant.tool.AnalysisStore;
import com.itajay.superassistant.tool.DownloadFailureRegistry;
import com.itajay.superassistant.tool.PaperAnalysisTool;
import com.itajay.superassistant.tool.PaperLibrary;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The research-document workflow: research → close read → write → review, with a
 * bounded revision loop.
 *
 * <p>This is driven in Java rather than assembled from the framework's flow agents
 * because the revision loop routes by cause, and there are three causes now: findings
 * the writer can fix (a comparison that does not follow from the analyses), findings
 * that need one paper re-read (a mis-transcribed formula in an analysis file), and
 * findings that need new material (a paper never downloaded, an unreadable PDF). A
 * {@code LoopAgent} with a condition predicate cannot express that — its predicate
 * sees only the message list.</p>
 *
 * <p>Each stage is a separate {@code invoke}, so stages do not share graph state;
 * the hand-off is explicit (research output feeds the writer, materials plus document
 * plus the analysis inventory feed the reviewer). That makes each step's input
 * inspectable, which matters more here than saving a serialisation.</p>
 *
 * <p>Progress is published to {@link ProgressChannelRegistry} as the run proceeds.
 * Publishing is best-effort: a run never fails because no client is listening.</p>
 */
@Component
public class ResearchWriteReviewWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ResearchWriteReviewWorkflow.class);

    /** Two content-review rounds allow at most one content revision. */
    private static final int MAX_REVIEW_ROUNDS = 2;

    /** Guard against a pathological research output being fed straight back in. */
    private static final int MAX_MATERIAL_CHARS = 200_000;

    /** Where the caller's brief stops being the topic identifier and starts being the details. */
    private static final Pattern TOPIC_BREAK = Pattern.compile("[:：\\n。]");

    /**
     * Run context key carrying the direction the user picked in the UI.
     *
     * <p>Deliberately a context value rather than text in the message. The frontend knows
     * the exact folder name; the model only knows what it was told. Passing it through the
     * context means a continuation lands in the right folder even when the model's copy of
     * the name is a paraphrase — and it is what makes the tool's {@code continueTopic}
     * argument optional rather than load-bearing.</p>
     */
    public static final String CONTINUE_TOPIC_CONTEXT_KEY = "continueTopic";

    /**
     * A topic identifier longer than this is prose that lost its colon. Folder names have to stay
     * short enough to be recognisable and typable, so such a string is cut at its first comma.
     */
    private static final int MAX_TOPIC_CHARS = 60;

    private final WorkflowAgentRun researchRun;
    private final WorkflowAgentRun writerRun;
    private final WorkflowAgentRun reviewerRun;
    private final PaperAnalysisTool paperAnalysisTool;
    private final ProgressChannelRegistry progress;
    /** Shared corpus, consulted before pausing: a paper added there is no longer missing. */
    private final PaperLibrary paperLibrary;
    /** What the research stage failed to download, recorded where the fetch happened. */
    private final DownloadFailureRegistry downloadFailures;
    /** Paused runs, so the resume endpoint can continue them. */
    private final ResearchResumeStore resumeStore;

    @Autowired
    public ResearchWriteReviewWorkflow(ResearchAgent researchAgent,
                                       WriterAgent writerAgent,
                                       ReviewerAgent reviewerAgent,
                                       PaperAnalysisTool paperAnalysisTool,
                                       ProgressChannelRegistry progress,
                                       PaperLibrary paperLibrary,
                                       DownloadFailureRegistry downloadFailures,
                                       ResearchResumeStore resumeStore) {
        this((input, config) -> invoke(researchAgent.reactAgent, input, config, "research-agent"),
                (input, config) -> invoke(writerAgent.reactAgent, input, config, "writer-agent"),
                (input, config) -> invoke(reviewerAgent.reactAgent, input, config, "reviewer-agent"),
                paperAnalysisTool, progress, paperLibrary, downloadFailures, resumeStore);
    }

    ResearchWriteReviewWorkflow(WorkflowAgentRun researchRun, WorkflowAgentRun writerRun,
                               WorkflowAgentRun reviewerRun, PaperAnalysisTool paperAnalysisTool,
                               ProgressChannelRegistry progress) {
        this(researchRun, writerRun, reviewerRun, paperAnalysisTool, progress, null, null, null);
    }

    ResearchWriteReviewWorkflow(WorkflowAgentRun researchRun, WorkflowAgentRun writerRun,
                               WorkflowAgentRun reviewerRun, PaperAnalysisTool paperAnalysisTool,
                               ProgressChannelRegistry progress,
                               PaperLibrary paperLibrary,
                               DownloadFailureRegistry downloadFailures) {
        this(researchRun, writerRun, reviewerRun, paperAnalysisTool, progress,
                paperLibrary, downloadFailures, null);
    }

    ResearchWriteReviewWorkflow(WorkflowAgentRun researchRun, WorkflowAgentRun writerRun,
                               WorkflowAgentRun reviewerRun, PaperAnalysisTool paperAnalysisTool,
                               ProgressChannelRegistry progress,
                               PaperLibrary paperLibrary,
                               DownloadFailureRegistry downloadFailures,
                               ResearchResumeStore resumeStore) {
        this.researchRun = researchRun;
        this.writerRun = writerRun;
        this.reviewerRun = reviewerRun;
        this.paperAnalysisTool = paperAnalysisTool;
        this.progress = progress;
        this.paperLibrary = paperLibrary;
        this.downloadFailures = downloadFailures;
        this.resumeStore = resumeStore;
    }

    @Tool(description = """
            Execute the research-document workflow for a topic: the research-agent searches for and downloads
            relevant papers, each downloaded paper is then close-read into its own notes file under
            investigation/{topic}/analysis/ (one per paper, kept on disk so they can be inspected or reused),
            the writer-agent composes a comparison document from those notes, and the reviewer-agent checks
            both the document and the notes against the source papers. If the review fails, the workflow
            revises — rewriting the document, re-reading a paper whose notes are wrong, or going back for more
            material when that is what the findings require. Returns the document path, whether it passed
            review, and any remaining issues. This workflow takes a long time (many minutes). Call it once
            per topic and wait; progress is streamed to the client separately.""")
    public String researchWriteReview(
            @ToolParam(description = """
                    Research topic and requirements. Begin with a SHORT, STABLE topic identifier — \
                    a few words, e.g. "多主体布局控制" or "training-free layout control" — then a colon, \
                    then the details: time range, target paper count, language, required document \
                    structure. Everything before the colon becomes the folder name, so a long opening \
                    sentence produces an unusable directory and breaks reuse across conversations.""") String topic,
            @ToolParam(description = """
                    Optional. Set ONLY when the user asked to continue an existing investigation
                    direction. Use the exact folder name returned by GET /api/research/topics — no
                    paraphrase, no translation, no trimming. When set, the topic identifier is taken
                    from here verbatim instead of from the brief, so the papers, per-paper analyses
                    and document of that direction are reused, and the brief may then open with the
                    details rather than with an identifier. When the user wants a new direction,
                    leave it empty.""", required = false) String continueTopic,
            ToolContext toolContext) {

        String threadId = threadIdOf(toolContext);

        // The argument is a brief, and it does two jobs: it names the folder for the whole run,
        // and it states what the run must produce. They are split here, once, and the folder
        // identity is then owned by the workflow — passed to every stage verbatim and used to
        // build every path.
        //
        // This is the same lesson as documentName below, learned the same way. Left in the brief,
        // the identifier got re-derived by each agent that needed it: the research guide defines
        // 课题方向 as the precise description of the topic, so the research agent narrowed the
        // sentence to a folder name and downloaded there, and the writer followed the paths in
        // the material index. Both agreed with each other — and neither agreed with the
        // workflow, which went on looking for the document and the figures under the full
        // sentence. The review then reported an empty document and an empty figure inventory as
        // blockers, blaming the writer for a mismatch the workflow had created.
        String brief = topic;
        // A continuation names its folder outright. Taking the identifier from the brief
        // instead would re-derive it — and a paraphrase of an existing folder is a new
        // folder, which silently splits one investigation into two.
        //
        // The UI-supplied direction wins over the tool argument: it came from the folder
        // listing itself, while the argument came through the model.
        String requestedTopic = continueTopicOf(toolContext);
        if (requestedTopic == null || requestedTopic.isBlank()) {
            requestedTopic = continueTopic;
        }
        boolean continuing = requestedTopic != null && !requestedTopic.isBlank();
        String topicId = continuing
                ? WorkspacePaths.slugify(requestedTopic, "课题方向")
                : topicIdentifier(brief);
        ProgressContext run = new ProgressContext(threadId, UUID.randomUUID().toString(),
                ProgressEvent.Workflow.RESEARCH_WRITE_REVIEW, ProgressStage.RESEARCHING, 0, topicId, null);
        ProgressContext activity = run;
        publish(run, Operation.WORKFLOW, Status.RUNNING, "开始调研工作流");
        log.info("ResearchWriteReview workflow started [topic={}, brief={}, thread={}]",
                topicId, truncate(brief), threadId);

        String materials = null;
        String documentOutput = null;
        ReadBack reviewed = null;
        ReviewResult review = null;
        int rounds = 0;

        // Fixed up front, not taken from the writer's reply. Left to itself the writer
        // renames on a revision round — one run wrote "...-survey.md" first and
        // "Training-free-....md" second — so the reviewer kept re-reading the unrevised
        // draft and the loop could never converge. The workflow owns the layout.
        String documentPath = null;

        // Where a continuation's superseded document went, so the research and writer
        // stages can read it as their baseline. Null for a fresh investigation.
        String archivedDocument = null;

        try {
            documentPath = documentPathFor(topicId);
            // A continuation rewrites the direction's one document. Move the previous
            // version aside first so the rewrite is not a silent overwrite: the writer is
            // told to build on that document, and the user may still want the old one.
            if (continuing) {
                archivedDocument = archiveDocument(topicId);
                publish(run, Operation.WORKFLOW, Status.RUNNING,
                        "继续已有方向：" + topicId + "（复用已有论文与精读结果）");
            }
            activity = run.at(ProgressStage.RESEARCHING, 1);
            publish(activity, Operation.STAGE, Status.RUNNING, "开始检索与筛选论文");
            materials = researchRun.invoke(
                    researchInput(topicId, brief, continuing, archivedDocument),
                    activity.childConfig(brief));
            publish(activity, Operation.STAGE, Status.SUCCEEDED, "检索与筛选完成");

            // Anything the research stage could not obtain is a decision point, not a
            // footnote: continuing would silently build the document on missing material.
            // Pausing here — before the expensive close-reading pass — leaves the topic
            // folder and every successfully downloaded paper in place, so resuming costs
            // only the papers that were actually missing.
            Waiting waiting = pausedForManualDownload(threadId, topicId, brief, run, activity);
            if (waiting != null) {
                return waiting.report;
            }

            while (rounds < MAX_REVIEW_ROUNDS) {
                rounds++;

                activity = run.at(ProgressStage.ANALYZING, rounds);
                publish(activity, Operation.STAGE, Status.RUNNING, "开始精读论文");
                // The workflow, not the writer, drives the batch analysis and waits on
                // it here (Java-side; the model never polls). The final index then
                // travels in the writer's input — the "analysis complete" callback the
                // model actually sees. Repeating it every round is deliberate: when
                // everything is already analysed this is just an enumeration plus a
                // render, and it lets the index reflect any reanalyzePaper the writer
                // did in the previous round.
                String analysisIndex = paperAnalysisTool.analyzeTopicSync(topicId, threadId, rounds, brief, activity);
                publish(activity, Operation.STAGE, Status.SUCCEEDED, "精读批次处理结束，逐篇结果见清单");
                activity = run.at(rounds == 1 ? ProgressStage.WRITING : ProgressStage.REVISING, rounds);
                publish(activity, Operation.STAGE, Status.RUNNING,
                        rounds == 1 ? "开始撰写文档" : "按审查意见修订文档");
                documentOutput = writerRun.invoke(
                        writerInput(topicId, brief, materials, analysisIndex, review, reviewed,
                                continuing, archivedDocument),
                        activity.childConfig(brief));
                publish(activity, Operation.STAGE, Status.SUCCEEDED,
                        rounds == 1 ? "文档写作阶段结束" : "文档修订阶段结束");

                activity = run.at(ProgressStage.REVIEWING, rounds);
                ProgressContext reviewContext = activity;
                AtomicInteger reviewAttempt = new AtomicInteger(1);
                publish(activity, Operation.STAGE, Status.RUNNING, "开始质量审查");
                publishReview(reviewContext, Status.RUNNING, "开始质量审查", 1);
                reviewed = readBack(documentPath, documentOutput);
                documentPath = reviewed.path();
                review = null; // An exception must not retain the previous artifact's verdict.
                review = ReviewRunner.run(reviewerRun,
                        reviewInput(topicId, brief, materials, reviewed.content(), documentPath),
                        reviewContext.childConfig(brief), () -> publishReview(reviewContext, Status.RETRYING,
                                "审查输出格式不可用，补审同一份文档（不返工内容）", reviewAttempt.incrementAndGet()));
                Status verdict = review.executionFailed() ? Status.FAILED
                        : review.approved() ? Status.PASS : Status.REVISE;
                publishReview(reviewContext, verdict, "审查结果：" + verdict, reviewAttempt.get());
                publish(reviewContext, Operation.STAGE, verdict, "质量审查结束：" + verdict);

                if (review.executionFailed() || review.approved()) {
                    break;
                }
                if (rounds >= MAX_REVIEW_ROUNDS) {
                    break;
                }

                // Route the next round by cause. Only a material-level finding is worth
                // re-running the (expensive) research pass; a prose-level one is not.
                if (review.needsResearcher()) {
                    activity = run.at(ProgressStage.RESEARCHING, rounds + 1);
                    publish(activity, Operation.STAGE, Status.RUNNING, "审查要求补充材料，重新检索");
                    materials = researchRun.invoke(
                            researchRevisionInput(topicId, brief, materials, review), activity.childConfig(brief));
                    publish(activity, Operation.STAGE, Status.SUCCEEDED, "补充材料检索结束");
                }
            }

            String report = report(topicId, reviewed, review, rounds);
            ProgressStage finalStage = review != null && review.approved()
                    ? ProgressStage.DONE : ProgressStage.FAILED;
            publish(run.at(finalStage, rounds), Operation.WORKFLOW,
                    finalStage == ProgressStage.DONE ? Status.SUCCEEDED : Status.FAILED,
                    review != null && review.approved() ? "文档通过审查"
                            : review != null && review.executionFailed() ? "审查未完成，文档质量未经验证"
                            : "文档未通过审查，已输出遗留问题");

            log.info("ResearchWriteReview workflow finished [topic={}, rounds={}, approved={}]",
                    topicId, rounds, review != null && review.approved());
            return report;

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                // Restore the flag: swallowing it here used to leave the calling
                // thread looking "not interrupted" while the run was being torn down.
                Thread.currentThread().interrupt();
            }
            log.error("ResearchWriteReview workflow failed [topic={}]", topicId, e);
            if (activity.stage() == ProgressStage.REVIEWING) {
                publishReview(activity, Status.FAILED, "审查执行异常终止", null);
            }
            publish(activity, Operation.STAGE, Status.FAILED, "阶段异常终止");
            publish(run.at(ProgressStage.FAILED, rounds), Operation.WORKFLOW, Status.FAILED, "工作流异常终止");
            return "Research-write-review workflow failed: " + e.getMessage()
                    + (documentOutput == null ? "" : "\n(An earlier draft may still have been written.)")
                    + "\n(Per-paper analyses already saved under investigation/ are kept — a re-run will reuse them.)";
        }
    }

    /**
     * Entry without a selected direction: the topic identifier is derived from the brief.
     *
     * <p>Not annotated as a tool — an overload of a tool method would be ambiguous to the
     * framework's schema introspection. It exists so Java callers (tests, and any future
     * internal driver) do not have to pass a null for a parameter they do not use.</p>
     */
    String researchWriteReview(String topic, ToolContext toolContext) {
        return researchWriteReview(topic, null, toolContext);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Waiting for a manual download
    // ──────────────────────────────────────────────────────────────────────

    /** Outcome of a pause: the report the caller returns, plus what is missing. */
    private record Waiting(String report) {
    }

    /**
     * Stops the workflow when the research stage could not obtain some papers.
     *
     * <p>The failure list comes from {@link DownloadFailureRegistry}, not from parsing
     * the research agent's prose: what actually failed is known exactly where the fetch
     * happened, and a model-written summary would make this decision drift run to run.</p>
     *
     * <p>Papers the user supplied through the library are re-checked here — the library
     * copy makes a paper available that was missing a moment ago, so a resumed run with
     * the files added proceeds without another network attempt.</p>
     *
     * @return the pause result, or {@code null} when there is nothing missing
     */
    private Waiting pausedForManualDownload(String threadId, String topicId, String brief,
                                            ProgressContext run, ProgressContext activity) {
        if (downloadFailures == null || threadId == null) {
            return null;
        }
        DownloadFailureRegistry.Snapshot snapshot = downloadFailures.drain(threadId, topicId);
        if (snapshot.isEmpty()) {
            return null;
        }

        // A paper that has since appeared in the library is no longer missing. Checked
        // here so an interrupted run resumed after the user dropped files in does not
        // pause again on papers it now has.
        List<DownloadFailureRegistry.Failure> stillMissing = snapshot.items().stream()
                .filter(failure -> paperLibrary == null
                        || paperLibrary.find(failure.shortName(), failure.url()).isEmpty())
                .toList();
        if (stillMissing.isEmpty()) {
            log.info("Research stage reported failures, but all are present in the paper library [topic={}]",
                    topicId);
            return null;
        }

        ProgressContext paused = run.at(ProgressStage.WAITING, 1);
        publish(paused, Operation.STAGE, Status.RUNNING, "缺少论文全文，等待人工补充");
        log.info("Research stage paused for manual download [topic={}, missing={}]",
                topicId, stillMissing.size());

        // Registered so POST /api/chat/{threadId}/resume can pick the run back up. The
        // brief travels verbatim: the resumed run must research the same thing, and the
        // topic folder is what makes every already-downloaded paper free.
        if (resumeStore != null) {
            resumeStore.put(threadId, topicId, brief, stillMissing.stream()
                    .map(f -> new ResearchResumeStore.MissingPaper(f.shortName(), f.reason()))
                    .toList());
        }
        return new Waiting(waitingReport(topicId, brief, stillMissing));
    }

    /** The message the model (and through it, the user) receives when the run pauses. */
    private String waitingReport(String topicId, String brief,
                                 List<DownloadFailureRegistry.Failure> missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("WORKFLOW_WAITING_FOR_PAPERS\n");
        sb.append("课题方向: ").append(topicId).append('\n');
        sb.append("状态: 研究阶段已暂停，等待人工补充论文全文（未进入精读与写作）。\n\n");
        sb.append("以下 ").append(missing.size()).append(" 篇论文自动下载失败：\n");
        for (DownloadFailureRegistry.Failure failure : missing) {
            sb.append("- ").append(failure.shortName());
            if (failure.url() != null && !failure.url().isBlank()) {
                sb.append("  (").append(failure.url()).append(')');
            }
            if (failure.reason() != null && !failure.reason().isBlank()) {
                sb.append("\n  原因: ").append(failure.reason());
            }
            sb.append('\n');
        }
        sb.append("""

                请向用户说明：
                1. 上述论文需要手动下载 PDF，放进本地论文库：paper-library/papers/
                   文件名**不必**精确匹配短名——系统会读取 PDF 首页的 arXiv 编号 / DOI / 标题来识别。
                   若某篇识别不出来，再把它改名为「上面列出的短名.pdf」即可。
                   已下载的一律保持原名也可以，不要为了改名而重复下载。
                2. 放好后调用 resume 接口继续本次调研：
                   POST /api/chat/{threadId}/resume          （接着本次会话继续）
                3. 已下载的论文、论文库与课题目录都保留着，继续时不会重新检索或重新下载它们。

                本次运行到此为止，不要声称已完成调研，也不要凭摘要编造全文内容。
                """);
        sb.append("\n原始调研要求（恢复时沿用）：\n").append(truncate(brief));
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Stage inputs
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Research stage: the folder identity, then the caller's brief.
     *
     * <p>The identifier is stated on its own line, named as 课题方向, because that is what the
     * research guide calls it and what every downstream tool takes. Handing the agent the whole
     * brief instead left it to work out which part was the folder — it did that sensibly, and
     * then nothing else in the run agreed with the answer.</p>
     */
    private String researchInput(String topicId, String brief) {
        return researchInput(topicId, brief, false, null);
    }

    /**
     * Research stage input.
     *
     * <p>A continuation says so explicitly, and says what is already in the folder. The
     * research agent's default instinct on a fresh instruction is to rebuild a candidate
     * pool from scratch and download it again; without this paragraph the same papers get
     * re-fetched under slightly different short names, which is precisely the duplicate
     * corpus the shared library exists to avoid.</p>
     *
     * @param continuing      this run extends an existing direction rather than starting one
     * @param previousDocument project-relative path of the document being superseded, if any
     */
    private String researchInput(String topicId, String brief, boolean continuing, String previousDocument) {
        StringBuilder sb = new StringBuilder();
        sb.append("课题方向：").append(topicId).append("\n\n");
        sb.append("本次调研的要求：\n\n").append(brief.strip()).append("\n\n");
        if (continuing) {
            sb.append("""
                    这是对**已有课题方向**的续调研，不是新课题：
                    - 该方向下已有的论文、精读结果和文档都已保留，会被本次运行复用。
                    - 先用 listDownloadedPapers(topic) 和 listLibraryPapers() 看清已有什么，
                      不要重复下载已有的论文；检索的目标是**本次新增要求**覆盖到的那部分材料。
                    - 只补充与本次新要求相关、且现有论文里没有的文献。若现有材料已经足够，
                      就明确说明"无需新增论文"，不要再凑数。
                    """);
            if (previousDocument != null) {
                sb.append('\n').append("上一版文档（本次将在此基础上续写/改写）：")
                        .append(previousDocument).append('\n');
            }
            sb.append('\n');
        }
        sb.append("""
                论文必须下载到这个课题方向下（`downloadPaper` 的 topic 参数逐字使用上面这行），
                后续各阶段会用同一个课题方向取回它们。不要另起名称、不要改写、不要截短。
                """);
        return sb.toString();
    }

    /** Research revision pass: the previous material plus the findings that need more of it. */
    private String researchRevisionInput(String topic, String brief, String previousMaterials, ReviewResult review) {
        return """
                课题方向：%s

                原始调研要求（补充检索仍须遵守年份、数量、语言和范围）：
                %s

                这是对同一课题的**补充检索**。上一轮的材料如下（仅供参考，不要原样重抄）：

                --- 上一轮材料 ---
                %s
                --- 材料结束 ---

                审查指出以下问题需要补充或更正材料，请针对性处理（补齐缺失的论文、
                替换不相关的论文、或为无法获取全文的论文补充替代来源）：

                %s

                沿用同一课题方向，以便复用已下载的论文；已下载的论文无需重复下载。
                """.formatted(topic, brief.strip(), truncate(previousMaterials), review.renderFor(ReviewResult.Target.RESEARCHER));
    }

    /** Writer input for round 1 (materials only) and later rounds (materials plus findings). */
    private String writerInput(String topicId, String brief, String materials,
                               String analysisIndex, ReviewResult previousReview, ReadBack previousDocument) {
        return writerInput(topicId, brief, materials, analysisIndex, previousReview, previousDocument,
                false, null);
    }

    /**
     * Writer input.
     *
     * <p>A continuation differs in one way that matters to the writer: the document is not
     * being created, it is being extended. Without saying so the writer treats the brief as
     * the whole scope and produces a document covering only what this run researched,
     * silently dropping the papers the direction already had — the document shrinks on
     * every continuation. Naming the archived copy makes "build on this, don't replace it"
     * checkable rather than a matter of the model's reading of the brief.</p>
     *
     * @param continuing       this run extends an existing direction
     * @param archivedDocument the superseded document moved to {@code document/history/}
     */
    private String writerInput(String topicId, String brief, String materials,
                               String analysisIndex, ReviewResult previousReview, ReadBack previousDocument,
                               boolean continuing, String archivedDocument) {
        StringBuilder sb = new StringBuilder();
        sb.append("课题方向：").append(topicId).append("\n\n");
        sb.append("本次调研的要求：\n").append(brief.strip()).append("\n\n");
        sb.append("""
                以下是 research-agent 交付的调研材料（候选池、相关性判定表、下载清单）。
                论文精读已由工作流完成并落盘到 analysis/，最终索引如下（以「论文精读完成：」开头）。

                --- 精读最终索引 ---
                """);
        sb.append(analysisIndex == null || analysisIndex.isBlank()
                ? "（精读未产出索引——该情况属于异常，请如实向调用方报告，不得凭材料编造分析内容）"
                : analysisIndex.strip()).append("\n--- 索引结束 ---\n\n");
        sb.append("""
                你**不需要**调用 analyzePapers(topic) 批量分析——结果已经在上面。
                失败篇目单篇重试 analyzePapers 或审查重做 reanalyzePaper 时，requirements 原样传上面的本次调研要求。
                请按需读取（readPaperAnalysis）、按 template/template.md 撰写对比型文档，
                最后调用 writeResearchDocument 保存。课题方向必须与材料中使用的完全一致。

                --- 调研材料 ---
                """);
        sb.append(truncate(materials)).append("\n--- 材料结束 ---\n");

        if (continuing && archivedDocument != null) {
            sb.append("""

                    这是对**已有方向**的续调研，不是新课题。
                    该方向上已有论文的精读结果都在上面的索引里（含早先轮次留下的），本次文档要在它们
                    全部基础上续写，而不是只写本次新补的论文。
                    上一版文档已归档到：%s
                    请先 readFile 读它，把上一版已有的内容保留并整合进新版：新增材料补充进去、
                    本次要求涉及的结论按新证据修订。不要因为本次 brief 只提到新增内容就把旧篇目删掉。
                    最终仍然写入同一个路径（documentName 不变），归档文件只读不改。
                    """.formatted(archivedDocument));
        } else if (continuing) {
            sb.append("""

                    这是对**已有方向**的续调研。该方向上已有论文的精读结果都在上面的索引里，
                    本次文档要在它们的全部基础上撰写，而不是只写本次新补的论文。
                    """);
        }

        sb.append("""

                落盘（必须严格遵守）：
                writeResearchDocument 的 documentName **每一轮都必须是**：%s
                不要改名、不要加后缀、不要改动大小写。修订轮次必须用同名覆盖同一个文件——
                改名会在目录里留下多份文档，审查读到的仍是旧版本，修订等于白做。
                """.formatted(documentName(topicId)));

        if (previousReview != null) {
            boolean completeDraft = previousDocument != null && previousDocument.onDisk()
                    && previousDocument.content() != null && !previousDocument.content().isBlank()
                    && previousDocument.content().length() <= MAX_MATERIAL_CHARS;
            if (previousDocument != null) {
                sb.append("\n上一版送审路径：").append(previousDocument.path()).append('\n');
                sb.append(completeDraft ? "以下是上一版从磁盘读回的完整正文，作为本轮修订基线。\n"
                        : "⚠️ 上一版未取得完整磁盘正文（或超出长度限制），下文仅供参考；"
                        + "不能声称已精确保留未涉及部分。请根据分析文件恢复完整文档并如实说明限制。\n");
                sb.append("--- 上一版送审正文 ---\n").append(truncate(previousDocument.content()))
                        .append("\n--- 上一版正文结束 ---\n");
            }
            String analystIssues = previousReview.renderFor(ReviewResult.Target.ANALYST);
            if (!analystIssues.isEmpty()) {
                sb.append("""

                        这是修订轮次：上一轮未通过审查，且**部分问题出在精读结果本身**。
                        请先对下列论文调用 reanalyzePaper(topic, 论文短名, reason, requirements) 重做精读，
                        requirements 原样使用上面的原始调研要求，不要用审查意见替代。
                        然后读回重做后的结果，并检查文档中依赖这些分析的对比结论是否需要同步修订：

                        --- 需要重做精读的论文 ---
                        """).append(analystIssues).append("\n--- 结束 ---\n");
            }

            String writerIssues = previousReview.renderFor(ReviewResult.Target.WRITER);
            sb.append("\n这是内容修订轮次：上一版文档未通过审查。请针对下面的问题及其依赖结论修订。\n");
            if (completeDraft) {
                sb.append("以给出的上一版完整正文为基线，未受影响部分保持不变，不要无关重写。\n");
            }
            sb.append("不得引入材料中没有的内容；补充材料或更新精读后，同步修正受影响的清单、定位及对比结论。\n");
            if (writerIssues.isEmpty()) {
                sb.append("（本轮没有文档写法问题，请依据更新后的材料或精读结果，只调整受影响内容。）\n");
            } else {
                sb.append("\n--- 审查意见（仅需你处理的部分）---\n")
                        .append(writerIssues).append("\n--- 审查意见结束 ---\n");
            }
        }
        return sb.toString();
    }

    /**
     * Reviewer input: what it judges, and what it judges it against.
     *
     * <p>Includes the analysis inventory but not the analyses themselves. The
     * reviewer reads whichever ones a given check needs through
     * {@code readPaperAnalysis} — pasting all of them in here would rebuild, in one
     * input, exactly the bulk this design just removed from the document.</p>
     */
    private String reviewInput(String topicId, String brief, String materials, String document, String documentPath) {
        return """
                请审查以下调研文档，以及支撑它的单篇精读结果。

                课题方向：%s
                原定要求（用于核对文档是否达到了要求的语言、结构与论文数）：
                %s

                文档路径：%s

                --- 调研材料（事实基准）---
                %s
                --- 材料结束 ---

                --- 单篇精读结果清单（用 readPaperAnalysis(topic, 论文短名) 逐篇读入核对）---
                %s
                --- 清单结束 ---

                --- 已截取的图片清单（文档里每个 ![..](..) 都必须指向这其中的一个文件）---
                %s
                --- 清单结束 ---

                --- 待审文档（正文，读自上述路径）---
                %s
                --- 文档结束 ---

                按 review-guide.md 的检查项逐条核验，输出约定 JSON。
                文档的对比结论所依赖的那几篇 analysis，务必读到；有疑问时用 extractPaperText 翻 PDF 原文。
                图片检查以"已截取的图片清单"为准：清单里没有的路径一律按凭空引用处理（review-guide.md P3）。
                每条 issue 必须给出 target：材料本身有问题（缺失、不相关、拿不到全文）填 RESEARCHER；
                某篇的 analysis 本身写错（公式与原文不符、来源等级标错、四维缺失）填 ANALYST；
                文档自身的写法或结论有问题填 WRITER。
                """.formatted(topicId, brief.strip(), documentPath == null ? "(未知)" : documentPath,
                truncate(materials), AnalysisInventory.analysisInventory(topicId),
                AnalysisInventory.figureInventory(topicId),
                truncate(document));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Reporting
    // ──────────────────────────────────────────────────────────────────────

    private String report(String topic, ReadBack reviewed, ReviewResult review, int rounds) {
        boolean approved = review != null && review.approved();
        StringBuilder sb = new StringBuilder();
        sb.append("Research-write-review workflow result\n");
        sb.append("课题: ").append(topic).append('\n');
        sb.append("审查结论: ").append(review != null && review.executionFailed()
                ? "审查未完成（输出格式不可用）" : approved ? "通过 (PASS)" : "未通过 (REVISE)").append('\n');
        sb.append("内容审查轮次: ").append(rounds).append('/').append(MAX_REVIEW_ROUNDS).append('\n');
        sb.append("内容修订次数: ").append(Math.max(0, rounds - 1)).append('/')
                .append(MAX_REVIEW_ROUNDS - 1).append('\n');
        sb.append("文档路径: ").append(reviewed == null ? "(未产出文档)" : reviewed.path()).append('\n');
        sb.append(analysisCoverage(topic)).append('\n');
        if (reviewed != null && !reviewed.onDisk()) {
            sb.append("⚠️ 文档未能从磁盘读回，审查基于 writer-agent 的回复文本，可信度受限。\n");
        }

        if (review != null && !review.summary().isBlank()) {
            sb.append("\n审查总体意见:\n").append(review.summary()).append('\n');
        }

        if (approved) {
            sb.append("\n文档已通过质量审查，可以直接交付用户。\n");
        } else if (review != null && review.executionFailed()) {
            sb.append("\n⚠️ 审查输出在补审后仍不可用，文档质量未经验证；已保留产物，不据此触发内容返工。\n");
        } else if (review != null) {
            sb.append("\n⚠️ 文档在 ").append(rounds).append(" 轮内容审查后仍未通过。")
                    .append("请向用户如实说明以下遗留问题，不得声称文档已核验通过。\n");
            sb.append("\n遗留问题（").append(review.issuesOrEmpty().size()).append(" 条）:\n");
            for (ReviewResult.ReviewIssue issue : review.issuesOrEmpty()) {
                sb.append("- ").append(issue.render()).append('\n');
            }
        } else {
            sb.append("\n⚠️ 审查未产出可用结论，文档质量未经验证。\n");
        }

        sb.append("""

                汇报要求：请向用户说明检索与下载的论文数量、最终文档位置、单篇分析文件的位置
                （investigation/{课题}/analysis/，用户可以直接打开查看），审查结论，
                以及哪些论文是仅摘要来源（ABSTRACT_ONLY / SNIPPET / 精读失败）——这是用户最需要知道的可信度限制。
                """);
        return sb.toString();
    }

    /**
     * How many papers were downloaded, how many got analysed, and how many of those
     * were actually readable.
     *
     * <p>Downloaded-but-unanalysed is the number worth surfacing: it means the
     * document is missing material that was sitting right there, which nothing else
     * in the report would reveal.</p>
     */
    private String analysisCoverage(String topicId) {
        List<Path> analyses = AnalysisStore.markdownFiles(topicId);
        long downloaded = countPdfs(topicId);
        if (analyses.isEmpty()) {
            return "单篇精读: 0 篇（已下载 " + Math.max(downloaded, 0) + " 篇）——文档缺少事实依据";
        }

        int fullText = 0;
        int limited = 0;
        for (Path file : analyses) {
            if (AnalysisStore.isAbstractOnly(AnalysisStore.readMeta(file).sourceLevel())) {
                limited++;
            } else {
                fullText++;
            }
        }

        StringBuilder sb = new StringBuilder("单篇精读: ").append(analyses.size()).append(" 篇");
        sb.append("（FULL_TEXT ").append(fullText).append(" 篇，仅摘要/未读到正文 ").append(limited).append(" 篇）");
        if (downloaded >= 0) {
            long missing = downloaded - analyses.size();
            if (missing > 0) {
                sb.append("；⚠️ 已下载 ").append(downloaded)
                        .append(" 篇，有 ").append(missing).append(" 篇没有精读结果");
            }
        }
        return sb.toString();
    }

    /** Downloaded PDF count, or -1 when the folder cannot be read. */
    private long countPdfs(String topic) {
        Path papersDir;
        try {
            papersDir = WorkspacePaths.papersDir(topic);
        } catch (IllegalArgumentException e) {
            return -1;
        }
        if (!Files.isDirectory(papersDir)) {
            return 0;
        }
        try (var stream = Files.list(papersDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> WorkspacePaths.hasExtension(p.getFileName().toString(), ".pdf"))
                    .count();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * The document text the reviewer should actually see.
     *
     * <p>The writer does not return its document — it returns a short confirmation,
     * because the body travels as the {@code writeResearchDocument} argument and is
     * never echoed back into the conversation. Passing that confirmation to the
     * reviewer made the review gate unfalsifiable: it had nothing to audit, so it
     * answered REVISE every round and the workflow could never pass. The workflow
     * owns the file layout, so it reads the artefact itself.</p>
     *
     * <p>Falls back to the writer's own text when the file cannot be read back, so a
     * surprising layout degrades to the old behaviour instead of reporting an empty
     * document — either way the reviewer sees whatever the writer actually said.</p>
     */
    private ReadBack readBack(String documentPath, String writerOutput) {
        String content = readIfPresent(documentPath);
        if (content != null) {
            log.info("Read back the written document for review [{} chars, {}]", content.length(), documentPath);
            return new ReadBack(documentPath, content, true);
        }

        // The writer saved somewhere other than the fixed path. Review what it did
        // write rather than giving up — the review is still worth running. The path
        // that was actually read wins, so the report never names a file that does
        // not exist.
        String reported = extractDocumentPath(writerOutput);
        if (reported != null && !reported.equals(documentPath)) {
            content = readIfPresent(reported);
            if (content != null) {
                log.warn("writer-agent saved to {} instead of the expected {}; reviewing that file",
                        reported, documentPath);
                return new ReadBack(reported, content, true);
            }
        }

        log.warn("No document found at {}; reviewing the writer's raw output instead", documentPath);
        return new ReadBack(documentPath, writerOutput, false);
    }

    /**
     * The document the reviewer was shown, and where it came from.
     *
     * @param onDisk false when nothing was found and the writer's own reply served as
     *               the "document" — reported so the caller can say so plainly
     */
    private record ReadBack(String path, String content, boolean onDisk) {
    }

    /** The file's text, or null when it does not exist or cannot be read. */
    private String readIfPresent(String documentPath) {
        if (documentPath == null) {
            return null;
        }
        try {
            Path file = WorkspacePaths.resolveInsideInvestigation(documentPath);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | IOException e) {
            log.warn("Could not read back the document [{}]: {}", documentPath, e.getMessage());
            return null;
        }
    }

    /**
     * The folder identity for a run, taken from the opening of the caller's brief.
     *
     * <p>The caller supplies one string that does two jobs, and the tool description tells it how
     * to lay that string out: identifier first, then a colon, then the requirements. This reads
     * the identifier off the front. What it returns is then the single value every stage receives
     * and every path is built from, so a run cannot end up with two of them.</p>
     *
     * <p>The fallbacks exist because a brief without a colon is a formatting slip, not a reason to
     * refuse the work. A missing delimiter leaves the whole string as the identifier, and one
     * longer than {@link #MAX_TOPIC_CHARS} is cut at the first comma before falling back to the
     * whole thing again. Both are worse answers than a well-formed brief, and both are still
     * answers — the run proceeds under one name either way, which is the property that matters.
     * {@code slugify} applies the final length cap when the paths are built.</p>
     */
    static String topicIdentifier(String brief) {
        if (brief == null || brief.isBlank()) {
            return "";
        }
        String head = TOPIC_BREAK.split(brief.strip(), 2)[0].strip();
        if (head.isEmpty()) {
            head = brief.strip();
        }
        if (head.length() > MAX_TOPIC_CHARS) {
            String shorter = head.split("[,，;；]", 2)[0].strip();
            if (!shorter.isEmpty()) {
                head = shorter;
            }
        }
        return head;
    }

    /** The document name every round must use, derived from the topic. */
    private static String documentName(String topic) {
        return WorkspacePaths.slugify(topic, "文档名");
    }

    /** The single document path for a topic, relative to the project root. */
    static String documentPathFor(String topic) {
        return WorkspacePaths.relative(
                WorkspacePaths.documentsDir(topic).resolve(documentName(topic) + ".md"));
    }

    /**
     * Moves a direction's current document into {@code document/history/} before a
     * continuation rewrites it, and returns where it went.
     *
     * <p>Overwriting in place would make "continue this direction" destroy the only copy
     * of what was written last time — and that document is the thing the user judged
     * unsatisfactory, so being able to compare is the point. Timestamped rather than
     * numbered because runs are not strictly sequential across conversations.</p>
     *
     * @return the archived file's project-relative path, or null when there was nothing
     *         to archive (a fresh direction, or the move failed — which must not stop the
     *         run, only cost the history entry)
     */
    static String archiveDocument(String topic) {
        Path current;
        try {
            current = WorkspacePaths.documentsDir(topic).resolve(documentName(topic) + ".md");
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!Files.isRegularFile(current)) {
            return null;
        }
        try {
            Path history = WorkspacePaths.documentHistoryDir(topic);
            Files.createDirectories(history);
            String stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path target = history.resolve(documentName(topic) + "-" + stamp + ".md").normalize();
            if (!target.startsWith(history)) {
                return null;
            }
            Files.move(current, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Archived previous document [{} -> {}]", WorkspacePaths.relative(current),
                    WorkspacePaths.relative(target));
            return WorkspacePaths.relative(target);
        } catch (IOException | RuntimeException e) {
            log.warn("归档旧文档失败，将原地覆盖 [topic={}]: {}", topic, e.getMessage());
            return null;
        }
    }

    /**
     * The relative path of the document the writer reported saving, or null.
     *
     * <p>Scans for the first {@code investigation/…} token and requires it to be a
     * Markdown file, so a passing mention of the folder cannot be mistaken for the
     * document, and only files inside {@code investigation/} are ever opened.</p>
     *
     * <p>Package-private so the parsing can be tested directly; it has no dependency
     * on the agents, which cannot be constructed without a live model.</p>
     */
    static String extractDocumentPath(String writerOutput) {
        if (writerOutput == null) {
            return null;
        }
        int marker = writerOutput.indexOf(WorkspacePaths.INVESTIGATION_DIR + "/");
        if (marker < 0) {
            return null;
        }
        int end = marker;
        while (end < writerOutput.length() && !Character.isWhitespace(writerOutput.charAt(end))) {
            end++;
        }
        // Tool output is often quoted or backticked, and prose may trail punctuation.
        String path = writerOutput.substring(marker, end)
                .replaceAll("[`\"'”’。，、；：)）\\]}]+$", "");
        return WorkspacePaths.hasExtension(path, ".md") ? path : null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Plumbing
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Runs one sub-agent and returns its text output.
     *
     * <p>Sub-agents are invoked directly rather than through {@code AgentTool} so the
     * workflow controls the hand-off. Each is a fresh run with no shared state,
     * which is why every input is assembled explicitly above.</p>
     *
     * <p>The config carries the conversation threadId, which
     * is how the sub-agent's own tool calls (e.g. {@code downloadPaper}) get a
     * populated {@code ToolContext} — without it, per-paper progress events would
     * have no channel to reach. The thread runs alone, so reusing the conversation's
     * threadId cannot collide with another run.</p>
     */
    private static String invoke(ReactAgent agent, String input, RunnableConfig config, String name) {
        Optional<OverAllState> result;
        try {
            result = config == null ? agent.invoke(input) : agent.invoke(input, config);
        } catch (GraphRunnerException e) {
            // Name the stage: "workflow failed" alone does not say which agent broke.
            throw new IllegalStateException(name + " failed to run: " + e.getMessage(), e);
        }
        String output = result.map(ResearchWriteReviewWorkflow::lastAnswer).orElse("");
        if (output.isBlank()) {
            log.warn("{} returned no output", name);
        }
        return output;
    }

    /**
     * The agent's final answer, read from the conversation it produced.
     *
     * <p>Reading {@code state.value("output")} here looks tempting — the main agent
     * sets {@code outputKey("output")} and {@code SuperAssistant} reads it back — but
     * a {@code ReactAgent} only writes that key when one was configured:
     * {@code AgentLlmNode} does {@code hasLength(outputKey) ? outputKey : "messages"}.
     * None of the sub-agents configure one, so that read returned an empty string for
     * every stage and the workflow fed blank material into blank documents.</p>
     *
     * <p>So read what is always written. This mirrors the framework's own
     * {@code AgentTool$AgentToolExecutor}, which takes the last {@code AssistantMessage}
     * from {@code messages}. Unlike the framework, skip trailing assistant messages
     * that carry only tool calls — an answer is the last message with actual text.</p>
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

    /**
     * The conversation thread id, used to address the progress channel.
     *
     * <p>Available because this tool is called by the main agent, whose tool calls
     * carry a populated {@code ToolContext}. Sub-agent tool calls do not — which is
     * exactly why progress is published here and not from inside a sub-agent.</p>
     */
    private String threadIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        return ToolContextHelper.getConfig(toolContext)
                .flatMap(com.alibaba.cloud.ai.graph.RunnableConfig::threadId)
                .orElse(null);
    }

    /**
     * The direction the client picked, carried on the run context by the chat endpoint.
     *
     * <p>Read from the config context rather than from the model's arguments so the value
     * is the folder name the UI listed, not a copy of it the model produced. Absent for
     * every run that did not select a direction — the ordinary new-investigation path.</p>
     */
    static String continueTopicOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(CONTINUE_TOPIC_CONTEXT_KEY);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private void publish(ProgressContext context, Operation operation, Status status, String detail) {
        progress.publish(context.threadId(), context.event(operation, status, detail));
    }

    private void publishReview(ProgressContext context, Status status, String detail, Integer attempt) {
        progress.publish(context.threadId(), context.event(Operation.REVIEW, status, detail,
                null, null, attempt, 2));
    }

    private void publish(String threadId, ProgressStage stage, int round, String detail) {
        progress.publish(threadId, ProgressEvent.of(stage, round, detail));
    }

    private String truncate(String text) {
        if (text == null) {
            return "(空)";
        }
        return text.length() > MAX_MATERIAL_CHARS
                ? text.substring(0, MAX_MATERIAL_CHARS) + "\n[已截断]"
                : text;
    }
}

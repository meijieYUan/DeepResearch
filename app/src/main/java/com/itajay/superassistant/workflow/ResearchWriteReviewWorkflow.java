package com.itajay.superassistant.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.itajay.superassistant.agent.ResearchAgent;
import com.itajay.superassistant.agent.ReviewerAgent;
import com.itajay.superassistant.agent.WriterAgent;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.progress.ProgressEvent;
import com.itajay.superassistant.progress.ProgressStage;
import com.itajay.superassistant.tool.AnalysisStore;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    /** Total write+review rounds. Matches the limit documented in review-guide.md. */
    private static final int MAX_ROUNDS = 2;

    /** Guard against a pathological research output being fed straight back in. */
    private static final int MAX_MATERIAL_CHARS = 200_000;

    /** Where the caller's brief stops being the topic identifier and starts being the details. */
    private static final Pattern TOPIC_BREAK = Pattern.compile("[:：\\n。]");

    /**
     * A topic identifier longer than this is prose that lost its colon. Folder names have to stay
     * short enough to be recognisable and typable, so such a string is cut at its first comma.
     */
    private static final int MAX_TOPIC_CHARS = 60;

    private final ResearchAgent researchAgent;
    private final WriterAgent writerAgent;
    private final ReviewerAgent reviewerAgent;
    private final ProgressChannelRegistry progress;

    public ResearchWriteReviewWorkflow(ResearchAgent researchAgent,
                                       WriterAgent writerAgent,
                                       ReviewerAgent reviewerAgent,
                                       ProgressChannelRegistry progress) {
        this.researchAgent = researchAgent;
        this.writerAgent = writerAgent;
        this.reviewerAgent = reviewerAgent;
        this.progress = progress;
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
        String topicId = topicIdentifier(brief);
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
        String documentPath = documentPathFor(topicId);

        try {
            publish(threadId, ProgressStage.RESEARCHING, 1, "开始检索与筛选论文");
            materials = invoke(researchAgent.reactAgent, researchInput(topicId, brief), "research-agent");

            while (rounds < MAX_ROUNDS) {
                rounds++;

                publish(threadId, ProgressStage.WRITING, rounds,
                        rounds == 1 ? "开始精读论文并撰写文档" : "按审查意见修订文档");
                documentOutput = invoke(writerAgent.reactAgent,
                        writerInput(topicId, brief, materials, review), "writer-agent");

                publish(threadId, ProgressStage.REVIEWING, rounds, "开始质量审查");
                reviewed = readBack(documentPath, documentOutput);
                documentPath = reviewed.path();
                review = ReviewResult.parse(invoke(reviewerAgent.reactAgent,
                        reviewInput(topicId, brief, materials, reviewed.content(), documentPath), "reviewer-agent"));

                if (review.approved()) {
                    break;
                }
                if (rounds >= MAX_ROUNDS) {
                    break;
                }

                // Route the next round by cause. Only a material-level finding is worth
                // re-running the (expensive) research pass; a prose-level one is not.
                if (review.needsResearcher()) {
                    publish(threadId, ProgressStage.RESEARCHING, rounds + 1,
                            "审查要求补充材料，重新检索");
                    materials = invoke(researchAgent.reactAgent,
                            researchRevisionInput(topicId, materials, review), "research-agent");
                } else {
                    publish(threadId, ProgressStage.REVISING, rounds,
                            review.needsAnalyst() ? "审查指出精读结果有误，重做后修订" : "审查未通过，仅修订文档");
                }
            }

            String report = report(topicId, reviewed, review, rounds);
            ProgressStage finalStage = review != null && review.approved()
                    ? ProgressStage.DONE : ProgressStage.FAILED;
            publish(threadId, finalStage, rounds,
                    review != null && review.approved() ? "文档通过审查" : "文档未通过审查，已输出遗留问题");

            log.info("ResearchWriteReview workflow finished [topic={}, rounds={}, approved={}]",
                    topicId, rounds, review != null && review.approved());
            return report;

        } catch (Exception e) {
            log.error("ResearchWriteReview workflow failed [topic={}]", topicId, e);
            publish(threadId, ProgressStage.FAILED, rounds, "工作流异常终止");
            return "Research-write-review workflow failed: " + e.getMessage()
                    + (documentOutput == null ? "" : "\n(An earlier draft may still have been written.)")
                    + "\n(Per-paper analyses already saved under investigation/ are kept — a re-run will reuse them.)";
        }
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
        return """
                课题方向：%s

                本次调研的要求：

                %s

                论文必须下载到这个课题方向下（`downloadPaper` 的 topic 参数逐字使用上面这行），
                后续各阶段会用同一个课题方向取回它们。不要另起名称、不要改写、不要截短。
                """.formatted(topicId, brief.strip());
    }

    /** Research revision pass: the previous material plus the findings that need more of it. */
    private String researchRevisionInput(String topic, String previousMaterials, ReviewResult review) {
        return """
                课题：%s

                这是对同一课题的**补充检索**。上一轮的材料如下（仅供参考，不要原样重抄）：

                --- 上一轮材料 ---
                %s
                --- 材料结束 ---

                审查指出以下问题需要补充或更正材料，请针对性处理（补齐缺失的论文、
                替换不相关的论文、或为无法获取全文的论文补充替代来源）：

                %s

                沿用同一课题方向，以便复用已下载的论文；已下载的论文无需重复下载。
                """.formatted(topic, truncate(previousMaterials), review.renderFor(ReviewResult.Target.RESEARCHER));
    }

    /** Writer input for round 1 (materials only) and later rounds (materials plus findings). */
    private String writerInput(String topicId, String brief, String materials, ReviewResult previousReview) {
        StringBuilder sb = new StringBuilder();
        sb.append("课题方向：").append(topicId).append("\n\n");
        sb.append("本次调研的要求：\n").append(brief.strip()).append("\n\n");
        sb.append("""
                以下是 research-agent 交付的调研材料（候选池、相关性判定表、下载清单）。
                请先调用 analyzePapers(topic) 让每篇已下载论文完成精读并落盘到 analysis/，
                再按需读取、按 template/template.md 撰写对比型文档，最后调用 writeResearchDocument 保存。
                课题方向必须与材料中使用的完全一致。

                --- 调研材料 ---
                """);
        sb.append(truncate(materials)).append("\n--- 材料结束 ---\n");

        sb.append("""

                落盘（必须严格遵守）：
                writeResearchDocument 的 documentName **每一轮都必须是**：%s
                不要改名、不要加后缀、不要改动大小写。修订轮次必须用同名覆盖同一个文件——
                改名会在目录里留下多份文档，审查读到的仍是旧版本，修订等于白做。
                """.formatted(documentName(topicId)));

        if (previousReview != null) {
            String analystIssues = previousReview.renderFor(ReviewResult.Target.ANALYST);
            if (!analystIssues.isEmpty()) {
                sb.append("""

                        这是修订轮次：上一轮未通过审查，且**部分问题出在精读结果本身**。
                        请先对下列论文调用 reanalyzePaper(topic, 论文短名, reason) 重做精读，
                        然后读回重做后的结果，并检查文档中依赖这些分析的对比结论是否需要同步修订：

                        --- 需要重做精读的论文 ---
                        """).append(analystIssues).append("\n--- 结束 ---\n");
            }

            String writerIssues = previousReview.renderFor(ReviewResult.Target.WRITER);
            sb.append("""

                    这是修订轮次：上一轮文档未通过审查。请**只针对以下问题**修订，
                    其余部分保持不变；不要重写整篇文档，也不要引入材料中没有的内容。
                    """);
            if (writerIssues.isEmpty()) {
                sb.append("（本轮没有属于文档写法的问题，问题都在上面的精读结果里——")
                        .append("重做精读后对照更新受影响的结论即可。）\n");
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
                truncate(materials), analysisInventory(topicId), figureInventory(topicId),
                truncate(document));
    }

    /**
     * The reviewer's checklist of what analyses exist, read from disk.
     *
     * <p>Deliberately not a count the workflow remembered: an inventory built from
     * the files cannot disagree with what the reviewer will actually open.</p>
     *
     * <p>Each row carries the number of extracted figures, so a paper with none is
     * visibly a paper that fell back to "图见原文 Fig. N (p.X)" rather than one the
     * reviewer has to open the analysis file to notice.</p>
     */
    private String analysisInventory(String topic) {
        List<Path> files = AnalysisStore.markdownFiles(topic);
        if (files.isEmpty()) {
            return "（没有任何精读结果文件——精读阶段未产出，这本身就是严重问题，请据此判定）";
        }
        StringBuilder sb = new StringBuilder("共 ").append(files.size()).append(" 篇：\n");
        for (Path file : files) {
            AnalysisStore.AnalysisMeta meta = AnalysisStore.readMeta(file);
            sb.append("- ").append(meta.shortName())
                    .append(" — ").append(meta.sourceLevel())
                    .append(" — ").append(WorkspacePaths.relative(file))
                    .append(" — 图片 ").append(figuresOf(topic, meta.shortName())).append(" 张")
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * The list of figure paths that actually exist, so the reviewer's broken-link
     * check is a comparison against disk rather than a guess.
     *
     * <p>Both the analysis-relative and document-relative forms are listed: the
     * reviewer sees the document, which cites the {@code ../analysis/figures/…} form,
     * and the analysis file, which cites {@code figures/…}. Listing both means a
     * mismatch is caught regardless of which file it appears in.</p>
     */
    private String figureInventory(String topic) {
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
     * <p>The prefix comes from {@link AnalysisStore#nameFor} rather than from the caller's
     * string, so it is the same derivation the figure tool used to name the files. Building it
     * here by hand is how a counter silently reports zero: the tool writes
     * {@code {短名}_Fig2.png}, and a prefix of {@code {短名}_p} matches no such file.</p>
     */
    private long figuresOf(String topic, String paperShortName) {
        String prefix = AnalysisStore.nameFor(paperShortName) + "_";
        return AnalysisStore.figureFiles(topic).stream()
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .count();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Reporting
    // ──────────────────────────────────────────────────────────────────────

    private String report(String topic, ReadBack reviewed, ReviewResult review, int rounds) {
        boolean approved = review != null && review.approved();
        StringBuilder sb = new StringBuilder();
        sb.append("Research-write-review workflow result\n");
        sb.append("课题: ").append(topic).append('\n');
        sb.append("审查结论: ").append(approved ? "通过 (PASS)" : "未通过 (REVISE)").append('\n');
        sb.append("修订轮次: ").append(rounds).append('/').append(MAX_ROUNDS).append('\n');
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
        } else if (review != null) {
            sb.append("\n⚠️ 文档在 ").append(rounds).append(" 轮修订后仍未通过审查。")
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
     */
    private String invoke(ReactAgent agent, String input, String name) {
        Optional<OverAllState> result;
        try {
            result = agent.invoke(input);
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

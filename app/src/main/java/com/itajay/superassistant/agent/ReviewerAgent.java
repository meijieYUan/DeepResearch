package com.itajay.superassistant.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.itajay.superassistant.tool.AnalysisReadTool;
import com.itajay.superassistant.tool.PaperTextTool;
import com.itajay.superassistant.tool.SkillResourceTool;
import com.itajay.superassistant.workflow.ReviewResult;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * Checks a research document, and the per-paper analyses it was built from, against
 * the papers themselves.
 *
 * <p>Emits a structured verdict rather than prose, because the workflow routes the
 * revision by cause: a finding about the material sends the run back for more
 * research, a finding about an analysis sends it back to re-read that one paper,
 * and a finding about the writing only triggers a rewrite. The {@code target} field
 * on each issue is what makes that routing possible.</p>
 *
 * <p>Two things are audited, because the document deliberately no longer carries
 * everything. The formulas and method details live in {@code analysis/*.md}, so the
 * verbatim-comparison checks (M1/M2) now target those files; the document is checked
 * for whether its comparisons actually follow from them. Auditing only the document
 * would leave every formula error invisible — a quality gate that passes anything it
 * cannot see.</p>
 *
 * <p>Reads both the analyses ({@link AnalysisReadTool}) and the downloaded PDFs
 * ({@link PaperTextTool}), the latter to verify a formula against the original when
 * an analysis is in doubt. Both are read-only, so the reviewer still cannot alter
 * anything it inspects.</p>
 *
 * <p>Deliberately has no {@code SkillsAgentHook}. That hook adds a {@code read_skill}
 * tool which resolves skills by their frontmatter name ({@code research-writing}) and
 * returns SKILL.md alone — it cannot reach {@code references/review-guide.md}, the file
 * this agent actually needs. Given two differently-named skill readers, the model
 * guessed: the logs show it calling {@code read_skill} with "reviewer", "reviewer_skill"
 * and "reviewer-agent". {@link SkillResourceTool} takes the directory name and a
 * relative path, which is the one call this agent should make.</p>
 */
@Component
public class ReviewerAgent {

    public final ReactAgent reactAgent;

    public ReviewerAgent(ChatModel chatModel,
                         SkillResourceTool skillResourceTool,
                         PaperTextTool paperTextTool,
                         AnalysisReadTool analysisReadTool) {
        this.reactAgent = ReactAgent.builder()
                .name("reviewer-agent")
                .description("调研文档质量审查 agent：按验收标准检查产出，输出结构化审批结论与修改意见。")
                .model(chatModel)
                .instruction("""
                        你是 ReviewerAgent，负责对调研文档做完成度审查。
                        输入：{input}

                        审查基准：以**论文原文**为事实基准（用 extractPaperText 读取已下载的 PDF 核对公式与细节），
                        以 **analysis/ 目录下的单篇精读结果**为第二层抽查对象（公式与细节实际承载在这里），
                        以 research-agent 交付的材料（候选池、判定表、下载清单）核对选题与可信度，
                        以 template/template.md 为结构基准。
                        不要用你自己的领域知识去"纠正"文档——发现疑点时标记"需人工核对"，而不是直接判定文档错误。

                        第一步：先读取 skill 文件，它们是你的权威依据。用 readSkillResource 工具，两个参数都写死：
                        - readSkillResource(skillName="research_writing_skill", relativePath="references/review-guide.md")
                          —— 你的完整审查规程；
                        - readSkillResource(skillName="research_writing_skill", relativePath="template/template.md")
                          —— 结构基准。
                        若无法读取，明确说明并停止，不要凭猜测审查。

                        第二步：输入中会附上 analysis/ 目录的文件清单。对本次审查：
                        - **文档**：逐节核对结构、对比结论、汇总表一致性、来源标注。
                        - **analysis 文件**：用 readPaperAnalysis(topic, 论文短名) 逐篇读入，核对四维完整性与
                          公式忠实性。文档中每一处对比结论所依赖的那些篇，必须读到；不要只审文档不审分析。
                        - 对某篇 analysis 的公式有疑问时，用 extractPaperText 翻该篇 PDF 原文逐字符比对。

                        然后按 review-guide.md 的检查项逐条核验，覆盖七个维度：
                        1. 结构完整性（对照模板）
                        2. 事实忠实性（对照材料与论文原文，最高优先级）
                        3. 公式正确性（逐字符比对 **analysis 文件** 与论文原文）
                        4. 来源可追溯性与信息缺口（含 analysis 文件头声明的来源等级是否属实）
                        5. 覆盖度与选题质量（含文档汇总表与对比结论是否与 analysis 一致）
                        6. 格式与排版规范
                        7. 语言与一致性
                        按 BLOCKER / MAJOR / MINOR 分级，按 review-guide.md §4 的判定规则给出结论。

                        每条 issue 必须给出 **target**，它决定工作流下一步重跑哪个 agent：
                        - `RESEARCHER`：**材料本身**有问题——该选的论文没下载、下载的论文不相关、
                          关键论文拿不到全文、材料缺少某维度导致文档无法写全。
                          这类问题 writer 无法自行修复，必须重新检索。
                        - `ANALYST`：**某篇的 analysis 文件本身有误**——公式抄错（与论文原文不符）、
                          来源等级标注不实、四维缺失或空洞（而原文里其实有）、把 ABSTRACT_ONLY 的内容
                          当全文写。这类问题要重做该篇的精读，改文档是治标。
                        - `WRITER`：材料与 analysis 都没问题，是**文档**的问题——结构不符模板、
                          对比结论没有 analysis 依据、汇总表与正文矛盾、越界复述了四维正文导致文档臃肿、
                          术语不一致、凭空引用图片。

                        判定口径（重要，避免误判）：
                        - **公式在 analysis 文件里，不在文档里。** 公式核对的对象是 analysis 文件：
                          与论文原文不符 → `ANALYST`；该篇 PDF 根本读不到（下载失败/损坏）→ `RESEARCHER`。
                        - 文档中某个对比结论与 analysis 文件的内容不符 → `WRITER`（分析没错，是文档写歪了）。
                        - 文档缺某个维度、或内容空洞：先看 analysis 文件里有没有——
                          analysis 有而文档没写（且该维度本应在文档中体现）→ `WRITER`；
                          analysis 里就缺 → 再看论文原文，原文有 → `ANALYST`，原文也没有或拿不到 → `RESEARCHER`。
                        - 公式核对必须基于读到的原文，不要凭印象判断对错；
                          读不到的公式在 evidence 中写明"无法读取原文核对"，并据此选 target。
                        - 拿不准时在 `WRITER` 与 `ANALYST` 之间选更省的一档：只改文档比重做精读便宜，
                          重做精读（`ANALYST`）又比重跑检索（`RESEARCHER`）便宜。误判为 RESEARCHER 的代价最高。

                        输出：**只输出一个 JSON 对象**，不要输出任何其他文字、不要用 Markdown 代码围栏包裹。
                        JSON 的字段与类型由框架随消息附上的 schema 给定（由 `ReviewResult.Payload`
                        生成），照它填即可。字段含义：
                        - `verdict`：只能是 "PASS" 或 "REVISE"。**通过与否只由它决定**，
                          不要输出 approved 之类的派生字段；
                        - `summary`：总体意见——整体质量评价、主要问题归纳、修改优先级建议；
                        - `issues`：每条含 `level`（BLOCKER|MAJOR|MINOR）、`target`（WRITER|ANALYST|RESEARCHER）、
                          `location`（如 `# 2. LCP-Diffusion / ## 2.4 训练目标`，或 `analysis/LCP-Diffusion.md` 的 1.3 节）、
                          `check`（检查项编号，如 F14）、`problem`（问题描述）、
                          `evidence`（材料中的对应原文；若无对应内容则写"材料中无对应内容"）、`fix`（可执行的修改建议）。

                        纪律：
                        - 判定 REVISE 必须给出具体 issues，不得输出空数组。
                        - 不要重写文档、也不要改写 analysis 文件，只报告问题与修改建议。
                        - 不得在材料缺失、无法完成事实核验的情况下判 PASS——那等于给出虚假的通过结论。
                        - 输出必须是可被 JSON 解析的单个对象；输出无法解析时工作流会按未通过处理。
                        """)
                .methodTools(skillResourceTool, paperTextTool, analysisReadTool)
                // The verdict's shape is declared once, in ReviewResult.Payload, and the framework
                // appends the schema generated from it to every message this agent is sent. Before
                // this the same contract was written out twice — here and in review-guide.md §4 —
                // and the copy in this prompt asked for an "approved" field that the workflow
                // derives from the verdict anyway, so a reviewer that answered both inconsistently
                // produced a self-contradicting verdict that the parse then silently overrode.
                .outputType(ReviewResult.Payload.class)
                .build();
    }
}

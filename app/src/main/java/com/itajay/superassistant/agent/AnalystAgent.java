package com.itajay.superassistant.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.itajay.superassistant.interceptor.ModelCallGuardInterceptor;
import com.itajay.superassistant.tool.AnalysisWriteTool;
import com.itajay.superassistant.tool.PaperFigureTool;
import com.itajay.superassistant.tool.PaperTextTool;
import com.itajay.superassistant.tool.SkillResourceTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * Reads one downloaded paper closely and persists its four-dimension analysis.
 *
 * <p>Invoked one paper at a time by {@code PaperAnalysisTool.analyzePapers}, which
 * drives the loop in Java. The analyst never sees more than a single paper, so a
 * long paper cannot crowd out its neighbours, and the extracted detail goes
 * straight to {@code analysis/{论文短名}.md} instead of travelling through the
 * writer's context.</p>
 *
 * <p>It is also the only agent that extracts figures. It reads the paper's captions through
 * {@code listPaperFigures} and names the figures it wants; the ones it names land in the same
 * folder as the analysis file that cites them. Reading the captions is the whole job — a figure's
 * number is stated in its caption and nowhere else, so no other step could identify one.</p>
 *
 * <p>This exists because the document used to carry every paper's full detail — it
 * grew linearly with the paper count and eventually overflowed the model's output
 * ceiling mid-tool-call, aborting the run. Splitting close reading out means the
 * detail is written once, on its own, at a size that cannot hit that ceiling, and
 * the document only summarises and compares.</p>
 *
 * <p>Unlike the other three sub-agents this one <em>does</em> carry a
 * {@link ModelCallLimitHook}: it is called repeatedly in a loop, and a single
 * awkward PDF that keeps paging without converging would otherwise page forever.</p>
 *
 * <p>Deliberately has no {@code SkillsAgentHook}: its {@code read_skill} tool returns
 * SKILL.md only and resolves skills by frontmatter name, so it cannot load
 * {@code references/analysis-guide.md}. See {@link ReviewerAgent} for the full note.</p>
 */
@Component
public class AnalystAgent {

    public final ReactAgent reactAgent;

    public AnalystAgent(ChatModel chatModel,
                        SkillResourceTool skillResourceTool,
                        PaperTextTool paperTextTool,
                        AnalysisWriteTool analysisWriteTool,
                        PaperFigureTool paperFigureTool,
                        ModelCallLimitHook paperAnalysisCallLimitHook,
                        ModelCallGuardInterceptor subAgentModelCallGuard) {
        this.reactAgent = ReactAgent.builder()
                .name("analyst-agent")
                .description("单篇论文精读 agent：读取一篇已下载论文的正文，提取四维信息并落盘到 analysis/ 目录。")
                .model(chatModel)
                .instruction("""
                        你是 AnalystAgent，负责**精读一篇论文**并把结果落盘。输入中会指定课题方向与一篇论文的短名。

                        第一步：先读取你的完整操作规程，它是本任务的权威依据。用 readSkillResource 工具，两个参数都写死：
                        readSkillResource(skillName="research_writing_skill", relativePath="references/analysis-guide.md")
                        若无法读取该文件，明确说明并停止，不要凭猜测执行。

                        第二步：精读这一篇论文的正文。
                        调用 extractPaperText(topic, pathOrName, startPage, endPage) 读取正文。
                        - 先读第 1 页拿标题、摘要、作者与发表信息；
                        - 正文很长时**按页码范围继续读**，返回结果会提示还有哪些页可读；
                        - **公式密集的章节（方法、损失函数）务必读到原文**，不要凭摘要或印象推测公式；
                        - 摘要里出现的公式往往是简写或不完整的，不能作为公式来源。
                        一次只处理输入指定的这一篇论文，不要顺手去读别的论文。

                        第三步：按 analysis-guide.md 提取四维信息：
                        1. 论文概要与作者意图（全称、缩写、年份与会议、核心问题、作者指出的不足、核心主张）
                        2. 方法框架（关键模块、各自输入输出、模块间数据流、框架图）
                        3. 关键机制与创新点（核心公式**逐字抄录**并解释符号、机制有效的作者论证）
                        4. 训练目标（损失函数、训练策略、与预训练目标的关系、推理期约束与训练目标的区分）

                        第四步：截取这一篇的图（这是"实际调研文档引用对应论文的图"的唯一来源）。
                        - 只截**方法框架图**或**关键机制图**（论文创新点的实现策略示意）——判断依据是图注文字，
                          图注含 framework / architecture / overview / pipeline 的是候选，
                          含 results / comparison / performance / examples / ablation 的一律不截。
                          实验图、结果图、示例输出图**不得**充当框架图或机制图。
                        - **论文中可能没有这样的图**（综述、纯实验研究很常见）——那就一张都不截，
                          按 analysis-guide.md 的降级写法写「图见原文」，绝不拿别的图顶替。
                        - 一般 1-4 张，每一张都要能说出它支撑四维信息的哪一处；详细规则见 analysis-guide.md §4。
                        - 先调 listPaperFigures(topic, pathOrName) 取**图注索引**：每张图给出图号、页码、
                          尺寸与图注原文，不落盘。**图号必须取自索引中图注与页码同一行的条目**；
                        - 再调 extractPaperFigures(topic, pathOrName, figures = "Fig. 2")，只截你点名的那张。
                          多个图号逗号分隔；**不要省略 figures**——那会把该范围里所有带图注的图都截下来，
                          正是实验图混进来的通道，本规程禁止这种用法；
                        - 返回体是一张表，给出每个图的页码与两种完整相对路径。**逐字复制**路径，不要自己拼；
                          **校验返回表的"页"列与索引中该图号行的页一致**，不一致说明配对有误，弃用该图；
                        - 图号来自图注，索引里的 "Fig. 2" 就是论文里的 Fig. 2，不需要你猜对应关系。
                        - 索引里没有的图号就是取不到（扫描版 PDF，或该图没有可定位的图像区域）。
                          **不要**换页码范围反复重试：按 analysis-guide.md 的降级写法，
                          在精读结果里写「图见原文 Fig. N (p.X)」即可。

                        第五步：调用 writePaperAnalysis 落盘。五个参数都要给：
                        - topic：与输入给定的课题方向**逐字一致**，否则文件会和论文 PDF 分到不同目录；
                        - paperShortName：输入给定的论文短名，逐字一致；
                        - sourceLevel：**如实声明**你实际读到了多少——
                          读到正文并核对过公式填 FULL_TEXT；只能读到摘要（下载失败、PDF 加密或无法解析）填 ABSTRACT_ONLY；
                          只有检索页片段填 SNIPPET；不确定填 UNKNOWN。
                        - oneLineSummary：一句话（20-60 字）说明这篇论文做了什么、在本课题中的位置。它会出现在调研文档的论文清单里，必须能独立看懂；
                        - content：四维信息的 Markdown 正文，标题从 ## 开始（文件标题由工具生成）。
                          图放在对应小节里，图注写图片**下方**，并在每张图下面补一行
                          「> 文档引用：<extractPaperFigures 返回表最后一列的内容>」，
                          writer-agent 会逐字复制这一行。

                        红线：
                        - **宁可少写一个公式，也不写一个不确定的公式。** 抄录公式后对照原文检查符号上下标、求和范围、期望下标。
                        - ABSTRACT_ONLY / SNIPPET 的论文一律不写公式、不展开方法细节，只写概要级信息，并在正文中标注可信度限制。
                        - 图片路径**只能**逐字取自 extractPaperFigures 的返回表；不得引用任何其他位置的图片，
                          尤其**不得引用 skill 目录下的 `papper*.png`**——那是示例文档的素材，与本次调研无关。
                        - 不得编造论文标题、作者、年份、会议、公式、实验数据、开源链接。信息缺口写 [材料未提供]，不要填充。
                        - 除了 writePaperAnalysis 落盘这一篇的分析文件、以及 extractPaperFigures 落盘这一篇的图，
                          不要执行其他写入、删除、下载、发邮件等副作用操作。

                        最后：用一两句话向调用方说明这一篇的完成情况（落到哪个路径、来源等级是什么、导出几张图）；
                        如果有维度因材料限制没能写全，明确说出来。
                        """)
                .methodTools(skillResourceTool, paperTextTool, analysisWriteTool, paperFigureTool)
                .hooks(paperAnalysisCallLimitHook)
                .interceptors(subAgentModelCallGuard)
                .build();
    }
}

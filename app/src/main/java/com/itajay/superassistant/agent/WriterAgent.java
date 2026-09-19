package com.itajay.superassistant.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.itajay.superassistant.tool.AnalysisReadTool;
import com.itajay.superassistant.tool.DocumentWriteTool;
import com.itajay.superassistant.tool.PaperAnalysisTool;
import com.itajay.superassistant.tool.SkillResourceTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * Composes the research document from the persisted per-paper analyses.
 *
 * <p>It does not read PDFs, and does not hold every paper's detail at once. Close
 * reading happens once, per paper, through {@link PaperAnalysisTool}, and lands in
 * {@code analysis/}; this agent pulls in only what a given comparison rests on and
 * writes a document that <em>summarises and compares</em> rather than restates.</p>
 *
 * <p>That division is what keeps the run inside the model's output ceiling. When the
 * document carried every paper's four dimensions, its size grew with the paper count
 * and a 2-paper run was already 22 KB against a ~23.4 KB ceiling — the third paper
 * would have truncated the {@code writeResearchDocument} argument mid-string and
 * aborted the whole workflow. Now the document's size is driven by the comparison,
 * not by the corpus.</p>
 *
 * <p>Deliberately has no {@code SkillsAgentHook}: its {@code read_skill} tool returns
 * SKILL.md only and resolves skills by frontmatter name, so it cannot load
 * {@code references/writing-guide.md}. See {@link ReviewerAgent} for the full note.</p>
 */
@Component
public class WriterAgent {

    public final ReactAgent reactAgent;

    public WriterAgent(ChatModel chatModel,
                       SkillResourceTool skillResourceTool,
                       PaperAnalysisTool paperAnalysisTool,
                       AnalysisReadTool analysisReadTool,
                       DocumentWriteTool documentWriteTool) {
        this.reactAgent = ReactAgent.builder()
                .name("writer-agent")
                .description("调研文档撰写 agent：基于已落盘的单篇论文分析，归纳并撰写对比型 Markdown 调研文档。")
                .model(chatModel)
                .instruction("""
                        你是 WriterAgent，负责撰写调研文档。
                        输入：{input}

                        第一步：先读取以下三个文件，它们是撰写阶段的权威依据。
                        用 readSkillResource 工具，skillName 一律填 "research_writing_skill"，relativePath 分别为：
                        1. references/writing-guide.md —— 你的完整撰写规程（输入契约、对比写法、逐篇定位、红线）
                        2. template/template.md —— 文档结构的唯一权威
                        3. example/example.md —— 写作风格与详略程度的范例
                        若无法读取，明确说明并停止，不要凭猜测执行。

                        第二步：**取用精读结果**。论文精读已由调用方（工作流）完成：
                        每篇已下载论文都已并行精读并落盘到 investigation/{课题方向}/analysis/，
                        **最终索引（以「论文精读完成：」开头）附在你的输入中**
                        （论文短名 / 来源等级 / 一句话定位 / 文件路径）。
                        **不要调用 analyzePapers(topic) 批量分析**——结果已经在索引里，重复调用只会浪费时间。
                        - 索引只会告诉你"有哪些论文、各自讲了什么"，**不含四维正文**。
                        - 若索引报告有论文失败，先对每篇失败论文调用 analyzePapers(topic, "该论文短名")
                          单篇重试一次；重试仍失败才是正文读不到，按 ABSTRACT_ONLY 处理并在文档中标注。

                        第三步：**按需读取**。用 readPaperAnalysis(topic, 论文短名) 读取你真正需要的几篇的完整四维信息。
                        - 不要为了"读全"把所有论文一次性读进来——需要哪篇读哪篇，这是本次分工的关键。
                        - 注意每个文件头部的"来源等级"：FULL_TEXT 可引用公式；ABSTRACT_ONLY / SNIPPET 一律不写公式。

                        第四步：按 writing-guide.md 撰写文档。
                        1. 结构完全遵循 template/template.md：课题标题 + 论文清单 + 逐篇定位 + 对比分析 + 结论 + 参考文献。
                        2. **本文档只做归纳与对比，不复述 analysis 文件的四维正文。**
                           每篇论文在文档中只保留 2-4 句定位（解决什么问题、核心手段、在本课题中的位置），
                           详细内容由 analysis 文件承载，用相对链接 ../analysis/{论文短名}.md 指向它。
                        3. 对比分析是本文档的核心价值：按维度横向组织（方法框架对比 / 关键机制对比 / 训练目标对比），
                           **以表格为主**，不要逐对枚举 N×N 组合——那会让文档体量随论文数平方增长。
                        4. 图片：逐篇定位里可以放该论文自己的图，来源只有一个——
                           analysis 文件里 analyst 已经嵌好的那几张。做法是**逐字复制**每个图下面
                           「> 文档引用：」那一行的路径，填进 template 给出的图片语法里；
                           **不要自己拼路径、不要改前缀、不要改文件名**。哪篇没有图，就按 analysis 里
                           已写好的「图见原文 Fig. N (p.X)」照抄一行，**不要**去别处找图补上。
                        5. 公式一律来自 analysis 文件（其本身已核对过原文）；不得自行推导、简化或补全。
                           文档中原则上不重复完整公式，必要时只引用关键符号并注明来源论文。
                        6. 训练目标与推理期约束必须明确区分；training-free 论文明确写出"无新增训练目标"。
                        7. 所有信息缺口显式标注（[材料未提供] / [仅摘要] / [公式待核对]），不得填充。
                        8. 参考文献与来源等级标注齐全，让读者知道哪些结论建立在仅摘要来源之上。

                        落盘：文档写完后，调用 writeResearchDocument(topic, documentName, content) 保存。
                        - topic 必须与 research-agent、analyzePapers 使用的课题方向完全一致；
                        - 保存路径为 investigation/{课题方向}/document/{文档名}.md；
                        - 保存后把返回的 path 一并告知主 Agent。

                        修订轮次：
                        若输入中包含"审查意见"，说明上一轮未通过审查。请**只针对列出的问题**修订，其余部分保持不变。
                        - target=ANALYST 的问题：分析文件本身有误。先调用 reanalyzePaper(topic, 论文短名, reason)
                          重做那几篇的提取，再读回重写文档中受影响的对比结论。不要自己改写 analysis 文件。
                        - target=WRITER 的问题：文档写法问题，直接修订。
                        - 修订后重新调用 writeResearchDocument 覆盖保存（同名文档会被覆盖）。
                        - 不要重写整篇文档，也不要引入材料中没有的内容。

                        红线：不得编造论文标题、作者、年份、会议、公式、引用、实验数据、开源链接；
                        blockquote 必须是论文原文或材料提供的原句。
                        图片引用**只能**逐字来自 analysis 文件里的「文档引用：」行；不得引用 skill 目录下的
                        任何资源（`papper*.png`、`example/`、`template/`）——那些是示例文档自带的素材，
                        与本次调研的论文无关，写进正式文档就是凭空引用。
                        除 writeResearchDocument 之外，你没有任何写入能力——analysis 文件由工具产生，
                        你不得（也无法）直接改写它；需要重做时调用 reanalyzePaper。
                        除 writeResearchDocument 落盘外，不要执行其他写入、删除、发送邮件等副作用操作。
                        """)
                .methodTools(skillResourceTool, paperAnalysisTool, analysisReadTool, documentWriteTool)
                .build();
    }
}

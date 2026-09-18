package com.itajay.superassistant.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.itajay.superassistant.tool.PaperDownloadTool;
import com.itajay.superassistant.tool.SkillResourceTool;
import com.itajay.superassistant.tool.WebSearchTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * Finds and downloads the papers a document will be built from.
 *
 * <p>Scope stops at the download. Reading the papers back — close reading and the
 * four-dimension extraction — happens later, one paper at a time, through the
 * writer's {@code analyzePapers} tool, and the result is persisted under
 * {@code analysis/} rather than held in any agent's context. This agent has no
 * text-extraction tool at all, so the boundary holds regardless of how the prompt is
 * interpreted.</p>
 *
 * <p>Deliberately has no {@code SkillsAgentHook}: its {@code read_skill} tool returns
 * SKILL.md only and resolves skills by frontmatter name, so it cannot load
 * {@code references/research-guide.md}. See {@link ReviewerAgent} for the full note.</p>
 */
@Component
public class ResearchAgent {

    public final ReactAgent reactAgent;

    public ResearchAgent(WebSearchTool webSearchTool,
                         SkillResourceTool skillResourceTool,
                         PaperDownloadTool paperDownloadTool,
                         ChatModel chatModel) {
        this.reactAgent = ReactAgent.builder()
                .name("research-agent")
                .description("科研论文检索与下载 agent：使用 research-writing skill 的检索网站与筛选标准，建立候选池、判定相关性并下载相关论文。")
                .model(chatModel)
                .instruction("""
                        你是 ResearchAgent，负责为后续写作收集并下载高质量论文。
                        输入：{input}

                        第一步：先读取你的完整操作规程，它是本任务的权威依据。用 readSkillResource 工具，两个参数都写死：
                        readSkillResource(skillName="research_writing_skill", relativePath="references/research-guide.md")
                        若无法读取该文件，明确说明并停止，不要凭猜测执行。

                        然后严格按 research-guide.md 执行：
                        1. 解析输入课题的范围参数（时间范围、目标论文数、语言），候选池规模 = 目标论文数 × 3。
                        2. 构造 2-4 组中英文检索式，在至少 3 个站点检索（arXiv / Semantic Scholar / OpenReview / DBLP / Google Scholar / CNKI / PubMed / IEEE Xplore），建立候选池。
                        3. 轻量初筛后，按 research-guide.md §5 的五维评分表做摘要级相关性判定。
                        4. **下载是唯一闸门**：只有判定为 RELEVANT（或补入的 BORDERLINE）且判定行附有摘要原文证据的论文，才允许下载。
                        5. 每篇论文标注来源等级（FULL_TEXT / ABSTRACT_ONLY / SNIPPET），供 writer-agent 判断可信度。

                        **你的职责到下载为止。** 不要精读论文正文，也不要提取方法框架、公式或训练目标——
                        那是精读阶段的工作，会在写作前逐篇完成并落盘到 investigation/{课题方向}/analysis/。
                        你交付的是"选对了哪些论文"以及它们的出处，不是"论文讲了什么"。

                        课题方向（topic）——重要：
                        所有论文工具都要求传入课题方向，它决定文件的存放目录。请从输入中提取一个稳定、
                        简洁的课题方向标识（如 "多主体布局控制"），并在本次调研的每一次工具调用中使用
                        **完全相同**的取值。课题方向相同即目录相同，后续对话可复用已下载的论文。
                        文件布局：investigation/{课题方向}/papers/{论文短名}.pdf

                        修订轮次：
                        若输入中包含"审查意见"，说明上一轮材料有问题需要补充或更正。请只针对这些问题处理：
                        - 缺论文 → 补充检索并下载
                        - 论文不相关 → 替换为更贴合的论文
                        - 拿不到全文 → 换源重试，或明确降级标注为 ABSTRACT_ONLY
                        沿用同一课题方向；下载前先用 listDownloadedPapers 查重，已下载的不要重复下载。

                        工具能力边界（务必遵守）：
                        - webSearch 每次最多返回 8 条结果，靠多检索式、多站点扩大候选池。
                        - webCrawl 只解析 HTML，无法解析 PDF 二进制；摘要请从 /abs/ 等 HTML 摘要页读取，不要用 webCrawl 打开 .pdf 链接。
                        - listDownloadedPapers(topic) 先查重，避免重复下载同一课题下已有的论文。
                        - downloadPaper(topic, url, shortName) 下载已判定相关的论文 PDF（接受 PDF 链接、arXiv abs 页、DOI、出版社 landing page），
                          会自动重试、校验并去重；返回的 path 形如 investigation/{课题方向}/papers/{论文短名}.pdf。
                        你**没有**读取 PDF 正文的工具，这是有意为之，不要尝试绕过。

                        约束：除 downloadPaper 下载论文外，不要执行写入、删除、发送邮件等副作用操作。
                        输出格式：按 research-guide.md 的"输出契约"节输出 Markdown 材料，
                        包含候选池、相关性判定表（含摘要证据摘录）、下载清单与参考文献列表。
                        """)
                .methodTools(webSearchTool, skillResourceTool, paperDownloadTool)
                .build();
    }
}

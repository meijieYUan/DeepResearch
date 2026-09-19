---
name: research-writing
description: "科研课题调研与文献综述撰写。当用户需要对某个研究方向或课题进行论文调研、文献检索、撰写调研报告/综述文档时触发。流程为：检索候选论文 → 摘要级相关性判定 → 下载相关论文 → 逐篇精读并把四维信息落盘到 analysis/，同时从 PDF 截取该论文自己的图 → 撰写对比型调研文档 → 质量审查（文档与精读结果均审）→ 未通过则按问题归属回退修订（最多 2 轮）。"
---

# 科研课题调研写作

## 使用场景

当用户提出以下类型的请求时触发本 skill：

- "帮我调研 XXX 方向近年来的论文"
- "写一份关于 XXX 的文献综述"
- "检索 XXX 领域的论文并总结"
- "对 XXX 课题做一个调研报告"

## 前置条件

本 skill 由四个子 Agent 分工执行，各自需要能读取本 skill 目录下的参考文件：

| 角色 | 职责 | 必须先读的参考文件 |
|------|------|-------------------|
| `research-agent` | 检索、相关性判定、下载 | `references/research-guide.md` |
| `analyst-agent` | 精读**单篇**论文，提取四维信息、截取该篇的图并落盘 | `references/analysis-guide.md` |
| `writer-agent` | 基于精读结果撰写**对比型**调研文档 | `references/writing-guide.md` |
| `reviewer-agent` | 质量检查与验收，给出结构化审批结论 | `references/review-guide.md` |

**每个角色只读属于自己的一份规程**，各文件整份都与该角色相关，不需要按小节跳读：
`research-guide.md` → research，`analysis-guide.md` → analyst，`writing-guide.md` → writer，
`review-guide.md` → reviewer。`template/template.md` 与 `example/example.md` 供 writer 与 reviewer 使用。

**四个 Agent 的职责边界由工具能力强制，不是靠自觉**：

- `research-agent` 只有 `downloadPaper` / `listDownloadedPapers`，**没有读取 PDF 正文的工具**——它无从精读，因此它的交付物是"候选池 + 判定表 + 已下载论文清单"，不含四维信息。
- `analyst-agent` 只被交给**一篇**论文，工具是 `extractPaperText` / `extractPaperFigures` / `writePaperAnalysis`——它读一篇、截这一篇的图、写一份分析，看不到别的论文，也不参与成文。**它是唯一能截图的角色**：只有它读过图注，知道哪张图是"Fig. 3"。
- `writer-agent` **完全不碰 PDF**。它的工具是 `analyzePapers` / `readPaperAnalysis` / `writeResearchDocument`——它触发精读、按需读取结果、写出文档，但**无法**自己翻阅原文、**无法**截图，也**无法**改写分析文件。文档里的图片路径由它逐字复制 analysis 里 analyst 写好的 `> 文档引用：` 行——它不构造路径。
- `reviewer-agent` 只读不写（可读 analysis、可读 PDF 原文），输出结构化审查结论。

**为什么精读不并进 writer-agent**：四维信息曾经由 writer 在上下文里提取、再全文写进文档，结果是文档体量 = 论文数 × 四维全文，两篇论文就已逼近模型的单次输出上限，第三篇必然中途截断、整轮运行终止。现在精读一次落盘（每篇一个文件），文档只做归纳与对比，体量与论文数解耦。**不要把精读结果再抄回文档**——那会把这个设计推翻。

**为什么 analyst 不是一个独立的工作流阶段**：它由 `analyzePapers` 工具按论文调用，调用方是 Java（工作流在 writer 运行前并行精读全部论文，路数受配置限制），保证每篇已下载论文都有一份分析文件；已分析的会自动跳过，因此中断后重跑不会重复劳动。

参考文件相对本 skill 根目录的路径为 `references/*.md`。读取时使用相对 skill 根目录的路径或绝对路径（skill 根目录即包含本 `SKILL.md` 的目录）。`template/template.md` 与 `example/example.md` 为撰写阶段的模板与范例，同样通过文件读取方式获取。

> **`example/example.md` 里的图片是范例自带素材，与正式产出无关。**
> 范例用 `papper*.png`（与 `example.md` 同目录）演示图怎么放；**正式调研文档引用的是对应论文自己的图**，
> 由 `analyst-agent` 用 `extractPaperFigures` 从 PDF 截取到 `analysis/figures/`，
> 路径形如 `../analysis/figures/{论文短名}_Fig{N}.png`。两处路径不通用，**不得互相引用**。

## 产出位置（先读这一节）

所有产出按**课题方向**归档到项目根目录下：

```
{项目根目录}/investigation/{课题方向}/
├── papers/                     # research-agent 下载的论文原件
├── analysis/                   # analyst-agent 逐篇生成的精读结果（四维信息），一篇一个文件
├── analysis/figures/           # analyst-agent 从对应论文截取的图，{论文短名}_Fig{N}.png
└── document/                   # writer-agent 生成的调研文档
```

**课题方向是目录的唯一键，且不区分对话（thread）**：

- `research-agent` 的每个论文工具（`downloadPaper` / `listDownloadedPapers`）都要传课题方向。
- 精读与撰写阶段的每个工具（`analyzePapers` / `readPaperAnalysis` / `reanalyzePaper` / `writeResearchDocument`）都要传**同一个**课题方向，否则文档、分析文件与论文会分到不同目录。
- 换个对话继续调研同一课题时，只要课题方向一致，就能复用**已下载的论文**与**已完成的精读结果**——这正是跨 thread 复用与查重的意义。因此课题方向的取值必须**稳定、可复现**，直接用课题名称即可。
- `analysis/{论文短名}.md` 与 `papers/{论文短名}.pdf` 同名成对，是给**人**看的产物：用户可以直接打开这些文件查看每篇论文的详细分析，不必在长文档里翻找。
- `analysis/figures/{论文短名}_Fig{N}.png` 的前缀与同一篇的 `.md` **同源**，因此"每张图都属于某一篇的精读结果"是可以逐条核对的，不是一句约定。

## 工作流程总览

**整个流程由 `researchWriteReview` 工作流编排**（Java 代码驱动，见 `workflow/ResearchWriteReviewWorkflow.java`），主 Agent 只调用这一个入口，不单独调用四个子 Agent。工作流内部按下图执行，并在审查未通过时按问题归属回退：

```
用户课题
   │
   ├─ 第一步 明确调研范围 ──────────────── 主 Agent 与用户确认
   │
   ├─ 第二步 检索与相关性判定 ─────────── research-agent  ← 关键闸门
   │      2.1 构造检索式
   │      2.2 多站检索，建立候选池（候选数 ≈ 目标数 × 3）
   │      2.3 轻量初筛（只看标题/摘要片段，不下载）
   │      2.4 摘要级相关性判定（仍然不下载）
   │      2.5 仅对判定为"相关"的论文执行下载
   │
   ├─ 第三步 精读与四维信息提取 ───────── analyst-agent（经 writer 的 analyzePapers 逐篇调用）
   │      用 extractPaperText 逐篇读正文，公式逐字核对
   │      概要与作者意图 / 方法框架 / 关键机制与创新点 / 训练目标
   │      → 落盘 investigation/{课题方向}/analysis/{论文短名}.md
   │      用 listPaperFigures 看图注选图，再用 extractPaperFigures 截取（只截方法框架图/关键机制图，一般 1-4 张，没有就一张不截并降级为"图见原文 Fig. N"）
   │      → 落盘 analysis/figures/{论文短名}_Fig{N}.png
   │
   ├─ 第四步 撰写调研文档 ─────────────── writer-agent
   │      按需 readPaperAnalysis 取用精读结果
   │      按 template/template.md 成文：清单 + 逐篇定位（含图）+ 对比分析 + 结论
   │      图片路径逐字复制 analysis 里的「> 文档引用：」行，不自己拼
   │      → 保存到 document/{文档名}.md
   │
   ├─ 第五步 质量检查 ─────────────────── reviewer-agent
   │      审文档（对比结论、结构、汇总表）
   │      审 analysis 文件（公式逐字符比对原文、来源等级）
   │      输出 {approved, verdict, issues[]}，每条 issue 带 target
   │
   └─ 第六步 判定与修订回边 ───────────── 工作流（最多 2 轮）
          approved=true            → 交付，向用户汇报
          target=WRITER 的问题      → 回到第四步，只重写文档
          target=ANALYST 的问题     → 重做该篇精读（reanalyzePaper）后回到第四步
          target=RESEARCHER 的问题  → 回到第二步，重跑 research 后接第四步
```

**第二步是整个流程的质量源头**：检索质量与下载下来的 PDF 是后续所有环节的唯一输入。一旦下载了不相关的论文，后面每一步都在错误的材料上浪费算力。因此下载前必须完成摘要级相关性判定，详细规则见 `references/research-guide.md`。

**审查回边为什么按 `target` 分流**：回退的代价是三档的，改文档最便宜，重做一篇精读次之，重跑检索最贵。所以材料本身有问题（论文缺失、不相关、拿不到全文）时回到检索；某篇精读抄错了公式时只需重做那一篇，改文档治标不治本；而文档自身的写法问题时重跑检索纯属浪费。因此 `reviewer-agent` 必须为每条 issue 标注 `target`，工作流据此决定回退到哪一步。

## 第一步：明确调研范围

与用户确认以下信息；用户未明确时按下表默认值执行，并在最终文档中注明所用范围：

| 项目 | 说明 | 默认值 |
|------|------|--------|
| 课题名称 | 中文或英文研究方向的精确描述 | 必填，不可默认 |
| 检索时间范围 | 论文发表年份区间 | 近 3-5 年 |
| 目标论文数 | 最终精读并写入文档的论文数 | 4-8 篇 |
| 偏好语言 | 中英文论文 | 中英文均可，中文课题需同时检索中英文 |

同时**确定课题方向（topic）的取值**，它是本次调研的目录名，后续所有环节共用：

- 取值要稳定、简洁，直接用课题名称即可（如 `多主体布局控制`）。
- 一旦确定，`research-agent` 与 `writer-agent` 必须使用**完全相同**的字符串。
- 工具会自动做安全处理（去除分隔符等），无需手工规避特殊字符。

确认后把结论作为一句完整的话传给 `research-agent`，例如：
`课题：多主体图像生成中的布局控制；课题方向：多主体布局控制；时间范围：2022-2025；目标论文数：6 篇；语言：中英文均可。`

## 第二步：检索与相关性判定

执行细则见 `references/research-guide.md`。核心约束：

1. **候选池大于目标数**：候选论文数约为目标论文数的 3 倍（目标 6 篇 → 候选约 18 篇），保证筛选余地。
2. **先判定、后下载**：候选阶段只读取标题、摘要片段与摘要正文，**此阶段禁止下载 PDF**。
3. **摘要级判定是下载的唯一闸门**：只有通过相关性打分阈值、且判定理由附有摘要原文摘录的论文，才允许下载。
4. **去重与覆盖度**：同一论文的不同版本合并；最终集合需覆盖不同方法流派、不同年份与不同团队。

## 第三步：精读与四维信息提取

**由 `analyst-agent` 执行**，逐篇调用，一次只处理一篇论文（只有它有读取 PDF 正文与截图的工具）。执行细则见 `references/analysis-guide.md`。

触发方式：工作流在 writer 运行前调用 `analyzePapers(topic)` 的批量路径（Java 阻塞等待、多篇并行），最终索引随 writer 的输入交付——**writer 不轮询、不批量调用**。单篇失败不中断整批，在最终索引中列出；对失败论文可调用 `analyzePapers(topic, "论文短名")` 单篇重试，重试仍失败才按 ABSTRACT_ONLY 处理。

对每篇论文，analyst 用 `extractPaperText(topic, pathOrName, startPage, endPage)` 读取正文，提取四个维度：

1. **论文概要与作者意图** — 论文全称与缩写、年份与会议/期刊、核心问题、现有方法的不足、作者核心主张。
2. **方法框架** — 整体架构拆解为 2-3 个关键模块，每个模块的输入/输出/功能，模块间数据流。
3. **关键机制与创新点** — 核心公式或算法机制，用 LaTeX 完整呈现并解释每个符号，说明为何有效。
4. **训练目标** — 损失函数、训练策略（冻结/训练哪些模块、分几阶段）、与原始预训练目标的关系；若为 training-free 方法需明确说明。

**公式密集的章节必须读到原文**（用页码范围分段读取），**所有公式必须从论文原文逐字核对，不可编造、不可凭印象补全**。仅拿到摘要的论文（`ABSTRACT_ONLY` / `SNIPPET`）一律不写公式，也不截图。

产出通过 `writePaperAnalysis(topic, paperShortName, sourceLevel, oneLineSummary, content)` 落盘到 `investigation/{课题方向}/analysis/{论文短名}.md`。**来源等级由 analyst 如实声明**，写在文件头部，供后续核对。

图片先用 `listPaperFigures(topic, pathOrName)` 取图注索引（只读文字，不落盘），再用 `extractPaperFigures(topic, pathOrName, figures = "Fig. 2")` 按图号截取，落盘到 `analysis/figures/`，**只截方法框架图或关键机制图，一般 1-4 张；论文中不存在这类图时一张不截，写降级行**。图号来自图注，所以文件名 `{论文短名}_Fig{N}.png` 里的号就是论文里的号。返回表同时给出"analysis 里怎么引用"与"文档里怎么引用"两种完整相对路径，analyst 把后者另起一行写进精读结果；扫描版或矢量图形取不到图时，如实降级为「图见原文 Fig. N（p.X）」，不引用别处的图。

## 第四步：撰写调研文档

由 `writer-agent` 执行，但**与精读不共享上下文**——它拿到的是第三步落盘的结果，不是原文。

执行细则见 `references/writing-guide.md`，结构与格式以 `template/template.md` 为准，写法范例见 `example/example.md`。

1. `analyzePapers(topic)` 拿到分析索引（论文短名 / 来源等级 / 一句话定位 / 路径）。
2. `readPaperAnalysis(topic, 论文短名)` **按需**读取需要的篇目——不要为了"读全"一次性读进所有论文。
3. 成文。**文档只做归纳与对比，不复述 analysis 文件的四维正文**：每篇论文在文档中保留 2-4 句定位，详细内容用相对链接 `../analysis/{论文短名}.md` 指向。
4. `writeResearchDocument(topic, documentName, content)` 保存到 `investigation/{课题方向}/document/{文档名}.md`，课题方向须与检索阶段一致。

**修订轮次**：`target=ANALYST` 的问题先用 `reanalyzePaper(topic, 论文短名, reason)` 重做那几篇的精读；`target=WRITER` 的问题直接改文档。两者都只针对列出的问题修订，**重新调用 `writeResearchDocument` 覆盖保存同一文档名**，不要把整个文档推倒重写。

文档骨架：

```
[TOC]

# {课题标题}
> 调研背景 / 调研范围 / 来源可信度说明

## 论文清单
| # | 论文 | 年份/会议 | 来源等级 | 一句话定位 | 详细分析 |

# 1. 逐篇定位
## 1.1 {论文短名}      （2-4 句，不复述四维细节 + 该篇已有的图（至多 4 张），路径逐字复制）

# 2. 对比分析
## 2.1 方法框架对比
## 2.2 关键机制对比
## 2.3 训练目标对比
## 2.4 对比汇总表

# 3. 结论与开放问题

# 参考文献
```

## 第五步：质量检查

执行细则见 `references/review-guide.md`。`reviewer-agent` 审查**两个对象**：最终文档，以及 `analysis/` 目录下的精读结果。后者是必须的——公式与细节实际承载在 analysis 文件里，不审它就等于放弃事实核对。

`reviewer-agent` 按检查项逐条核验，输出**结构化审批结论**——既包含给主 Agent 汇报的内容，也包含工作流判定所需的 `approved` 标记：

```json
{
  "approved": false,
  "verdict": "PASS 或 REVISE",
  "summary": "给主 Agent 的总体意见：整体质量评价、主要问题归纳、修改优先级建议",
  "issues": [
    {
      "level": "BLOCKER|MAJOR|MINOR",
      "target": "WRITER|ANALYST|RESEARCHER",
      "location": "章节定位，如 # 2. LCP-Diffusion / ## 2.4 训练目标，或 analysis/LCP-Diffusion.md 的 1.3 节",
      "check": "检查项编号，如 F14",
      "problem": "问题",
      "evidence": "材料中的对应原文，或'材料中无对应内容'",
      "fix": "修改建议"
    }
  ]
}
```

**`approved` 必须与 `verdict` 一致**：`verdict=PASS` 时 `approved=true`，`verdict=REVISE` 时 `approved=false`。出现 BLOCKER 时无论计数如何都不得 `approved=true`。

**`target` 决定工作流回退到哪一步**（这是本字段存在的唯一理由，必须逐条填写）：

| `target` | 含义 | 工作流的动作 |
|----------|------|-------------|
| `RESEARCHER` | **材料本身有问题**：论文缺失、入选论文不相关、拿不到全文导致关键信息不可用 | 重跑 research-agent 补充检索/下载，再重新撰写 |
| `ANALYST` | **某篇的精读结果本身有误**：公式与论文原文不符、来源等级标注不实、四维缺失或空洞（而原文里其实有） | 先用 `reanalyzePaper` 重做该篇精读，再重写文档 |
| `WRITER` | **文档自身的问题**：对比结论没有 analysis 依据、结构不符模板、汇总表与正文矛盾、越界复述四维正文、信息缺口未标注、措辞与排版问题 | 只重跑 writer-agent，不重跑检索也不重做精读 |

拿不准时按代价选更省的一档：改文档 < 重做精读 < 重跑检索。只有在"重写文档无法解决问题"时才判 `RESEARCHER`。

## 第六步：判定与修订回边

工作流读取 `approved`：

- `approved=true` → 流程结束，向用户交付文档。
- `approved=false` → 按上表三档分流：issues 含 `RESEARCHER` 级问题则重跑 `research → write`；否则带 `ANALYST` 级问题回 `write`（writer 先 `reanalyzePaper` 再改文档）；全部为 `WRITER` 级问题则只重跑 `write`。**最多 2 轮**。
- 2 轮后仍未通过 → 停止循环，**如实向用户报告遗留问题与影响范围，不得强行放行**。

**审查输出无法解析时（JSON 格式错误、字段缺失、模型未按要求输出），工作流一律按"未通过"处理**，并追加一条说明"审查输出无法解析，未完成事实核验"的问题项。**不得静默放行**——放行一份未经核验的文档，等于让整个审查环节形同虚设。

交付时向用户汇报：检索了多少篇论文、判定通过多少篇、最终选取几篇、每篇的核心贡献概览、经过几轮修订，以及任何因无法获取全文而降低信息可信度的地方。**同时告知 `analysis/` 目录的位置**——精读结果是给用户看的产物，他可以直接打开其中任意一篇查看详细分析。

## 全局红线

- **不编造**：论文标题、作者、年份、会议、公式、引用，全部必须有出处；无法核实的宁可不写，并标注 `[未能核实]`。
- **不越权**：`research-agent` 只做检索、读取与下载，不写入、不删除、不发送邮件；`analyst-agent` 除通过 `writePaperAnalysis` 落盘**自己那一篇**的分析文件、通过 `extractPaperFigures` 落盘这一篇的图之外，只能只读地调用 `extractPaperText`；`writer-agent` 除通过 `writeResearchDocument` 落盘文档外**没有写入能力**，既不碰 PDF、不能截图也不能改写 analysis 文件（要重做只能调 `reanalyzePaper`）；`reviewer-agent` 只读不写。
- **不越界精读**：`research-agent` 没有读取 PDF 正文的工具，这是有意为之。它的职责到"下载"为止，不得尝试用 `webCrawl` 或其他方式绕过读取正文。
- **不复述**：文档不得把 analysis 文件的四维正文抄回来。每篇只留 2-4 句定位 + 相对链接；公式与细节留在 analysis 文件里。这一条是文档体量可控的前提，抄回来就会重新逼近模型单次输出上限。
- **不凭空引用图片**：正式文档的图片路径只能逐字取自 `analysis/{论文短名}.md` 里的 `> 文档引用：` 行，也就是 `extractPaperFigures` 从该论文 PDF 截出的图。**不得引用 skill 目录下的任何文件**（`example/` 的 `papper*.png`、`template/` 都不行），不得引用别的论文的图，不得写绝对路径。取不到图就写「图见原文 Fig. N（p.X）」——这是合规产出，不是缺失。范例引用自己的图与正式产出引用截取的图，是两件互不通用的事。
- **不重复失败**：同一 URL 或同一检索式失败两次即改变策略（换站点、换关键词、走降级链），不做无意义重试。
- **诚实标注降级**：无法获取全文时，在材料与文档中显式标注信息来源等级，不得把摘要级信息伪装成全文级信息。
- **不放行未核验的文档**：审查输出无法解析时按未通过处理；2 轮修订后仍有 BLOCKER 时如实报告，不得为了让流程"收敛"而降低标准。

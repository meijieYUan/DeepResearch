# HarnessDesign

> 来源等级：FULL_TEXT
> 一句话定位：系统消融编码智能体的规划、动作空间与上下文管理三大部件，跨4个模型、4档上下文预算共176个设置，发现上下文管理在预算紧张时才最有价值。
> 生成时间：2026-09-19 14:38:40

---

## 1. 论文概要与作者意图

**An Empirical Study of Harness Design for Coding Agents**（论文标题原文，未见作者给出统一缩写；PDF 短名为 HarnessDesign）。作者：Run-Ze Fan、Zihao Zhang、Simin Ma、Yebowen Hu、Shouju Wang、Kaiqiang Song、Fei Liu、Hamed Zamani、Xiaoyang Wang；单位包括 UMass Amherst、Emory University、UNC Charlotte 等（第 2 页作者栏中第二个单位名在 PDF 中缺失，[材料未提供]）。发表信息为 arXiv 预印本 arXiv:2609.20804v1 [cs.AI]，2026 年 9 月 17 日。

这篇论文关注的是 **coding harness（编码智能体的软件外壳/脚手架）的组件级归因**：即模型能力之外，规划（planning）、动作空间（action space）与上下文管理（context management）各自对长时程软件工程任务表现的贡献，以及这些贡献如何随模型能力、任务类型与资源预算变化。

作者指出的现有方法不足：

- **Monolithic evaluation（整体式评估）**：现有工作通常把 harness 当作完整系统来评估，"comparisons between complete harnesses conflate multiple mechanisms, so a performance difference between two agents does not reveal whether the gain comes from planning, tool design, context management, or their interaction with the underlying model"。
- **Harness preference 随模型漂移**：Cao et al. (2026) 的跨 harness 评估显示 Claude-Opus-4.5 在 OpenHands 下最好、Claude-Sonnet-4.5 在 SWE-Agent 下最好，说明 harness 偏好依模型而变，但整体比较无法解释原因。
- **上下文管理缺少预算轴上的刻画**："The value of managing context cannot be separated from how much window there is to manage against, nor from whether the model is strong enough to be helped rather than starved by compaction." 已有上下文管理机制多以"改进"名义在单一模型上评估。

因此，作者写这篇论文的核心意图是：

> "Are harness components generally useful across settings, or does each component's effectiveness depend on model capability, task type, and resource budget?" —— 为此作者构建一个执行循环固定、仅变动三个组件的轻量 harness，在 4 个模型、2 个长时程编码基准、4 档上下文预算上做 176 个匹配设置的受控比较。

## 2. 方法框架

论文构建了一个轻量的、模块化的 coding harness，遵循 **ReAct loop**（Yao et al., 2022）：每一轮包含 reasoning step、action、observation。执行循环（permission handling、post-edit diagnostics、stuck detection 等）固定不变，仅变动三个组件，从而实现组件级归因。

1. **Planning（§2.1）**
   - 输入：系统指令中定义的 plan 协议（Figure 15）、首轮 reminder（Figure 16）、模型通过 `update_plan` 工具提交的 plan。
   - 输出：一份显式的、持续存在的任务进度表示（current plan）。
   - 功能：后续每一轮把当前 plan **追加注入**到模型输入中，但**不存入对话历史**（Figure 17），即 plan 以"每轮重新注入、与历史分离存储"的方式维持。planning-off 条件同时移除规划指令、reminder、plan 注入与 `update_plan` 工具。
   - 注意：作者声明这估计的是"persistent planning scaffold"的效果，而非 planning 作为通用推理策略的效果。

2. **Action space（§2.2）**
   - 输入：模型发出的工具调用意图。
   - 输出：在工作区（Docker / E2B 容器）中执行的可执行操作。
   - 功能：两种条件——**predefined-tool** 暴露 `read_file, write_file, edit_file, list_files, glob_files, grep_text, web_fetch, bash`（Table 1），每个工具有 typed argument schema 与协议/错误/副作用说明；**bash-only** 移除预定义文件、搜索与网页工具，仅保留 `bash`。两类条件下的辅助工具（planning 的 `update_plan`、上下文管理的 `recall_event`）保持不变。
   - 作者明确声明这是一个**捆绑式接口变更**：预定义文件工具会强制 read-before-write 检查、更新 harness 文件状态、并在支持的编辑后触发自动诊断，所以对比应理解为"完整动作接口（工具可用性 + 接口指令 + 状态追踪 + 校验支持）"的效果，而非孤立的工具数量或动作粒度效果。作者还排除了 web search，因为 SWE-Bench 任务源自公开 GitHub issue，搜索可能泄漏 ground-truth patch。

3. **Context management（§2.3）**
   - 输入：不断增长的交互历史 $H$、软阈值 $B_1$ 与硬阈值 $B_2$。
   - 输出：压缩后的 managed context history。
   - 功能：由三个可组合机制构成——**Elision (M1)** 把陈旧工具观测的正文替换为短 stub；**Recall (M2)** 把被 elide 的观测存入文件系统并暴露 `recall_event` 工具按需读回，使 elision 可逆；**Summarization (M3)** 把更早的消息折叠成一段 running natural-language summary（由对同一被测模型的一次单独的、无工具的调用生成）。preamble（系统提示 + 初始任务描述）与按 token 预算保留的近期窗口（至少两轮）保持原文，只有 middle region 被压缩。超过 $B_1$ 时 elide 并外存，仍超过 $B_2$ 时对最老的 middle events 做摘要。

框架图：Fig. 2（p.4），"Overview of the coding harness. Top: the ReAct loop... Bottom: the T4 strategy."

![HarnessDesign 方法框架](figures/HarnessDesign_Fig2.png)

> 图注原文：Figure 2 Overview of the coding harness. Top: the ReAct loop, in which each turn assembles the model input, executes the emitted tool calls in the task container, and appends the observation to the history H. Planning enters through the injected plan, the action space through the exposed schemas, and context management through the history the model sees. Bottom: the T4 strategy. M1–M3 are the three context-management mechanisms. Above the soft threshold B1, bulky middle-region tool outputs are replaced by stubs (M1) and offloaded to an external store recoverable via recall_event (M2); above the hard threshold B2, the oldest middle events are summarized (M3). The preamble and recent turns stay verbatim.
> 文档引用：../analysis/figures/HarnessDesign_Fig2.png

## 3. 关键机制与创新点

### 分层上下文管理策略（T0–T4）

论文的核心机制是把 elision、recall、summarization 三个机制组合成五档策略（Table 2），以便隔离每个机制的贡献：

| Tier | M1 (elision) | M2 (recall) | M3 (summarization) |
|------|--------------|-------------|--------------------|
| Tier 0 | ✗ | ✗ | ✗ |
| Tier 1 | ✓ | ✗ | ✗ |
| Tier 2 | ✓ | ✓ | ✗ |
| Tier 3 | ✗ | ✗ | ✓ |
| Tier 4 | ✓ | ✓ | ✓ |

- **Tier 0**：不做任何跨轮压缩，超出窗口即报错终止。
- **Tier 1**：仅 elision（M1），把陈旧工具观测正文替换为短 stub 并丢弃原文。
- **Tier 2**：在 T1 基础上加 recall（M2），被 elide 的观测外存、可经 `recall_event` 读回，使 elision 可逆。
- **Tier 3**：仅 summarization（M3），把 middle region 折叠为 running summary，不做 elision。
- **Tier 4**：三者齐全，在 $B_1$ 处 elide、在 $B_2$ 处 summarize。

作者特别说明：因为 Tiers 1–3 各自只有一个动作，它们都在硬阈值 $B_2$ 处操作；而 Tier 4 在 $B_1$ 处 elide、在 $B_2$ 处 summarize。

### 每轮上下文管理流程（Algorithm 1, Tier 4）

论文 Algorithm 1 的完整过程逐字抄录如下（原文为伪代码，此处保留原文语句与符号）：

```
Algorithm 1 Per-turn context management (Tier 4)
Require: history H; soft and hard thresholds B1 < B2
1: append the new think, action, and observation to H
2: if the model invoked recall_event(id) this turn then
3:     read observation id from the external store back into H   ▷ M2
4: end if
5: keep the preamble and a budget-sized recent window (at least the last two turns) verbatim; let M be the middle region
6: if tokens(H) ≥ B1 then
7:     for each bulky tool observation in M do
8:         store the original in the external store              ▷ M2
9:         replace its body with a stub                          ▷ M1
10:    end for
11:    if tokens(H) ≥ B2 then
12:        summarize the oldest events in M into a running summary   ▷ M3
13:    end if
14: end if
15: return H
```

其中：

- $H$ 是当前交互历史（think / action / observation 序列）；
- $B_1$ 是软阈值，$B_2$ 是硬阈值，满足 $B_1 < B_2$；实验设置为可用窗口的 0.6 与 0.85；
- $M$ 是历史中的 middle region，即 preamble 与近期窗口之外、可被压缩的部分；
- `tokens(H)` 是当前历史的 token 数；
- `recall_event(id)` 是按需把外部存储中的事件原文读回 $H$ 的工具（仅 T2、T4 可用）；
- M1/M2/M3 分别对应 elision、recall、summarization 三个机制。

该机制为何有效，作者在摘要中的论证是：

> "Staging rule-based elision before LLM-based summarization provides the strongest overall efficiency among the context-management strategies, whereas making elided content recoverable adds machinery that models rarely use and yields no accuracy gain."

以及正文对成本来源的解释：

> "These results suggest that T4's early elision handles many cases before summarization is needed, reducing costly LLM summarization calls and helping explain its lower cost."

关于 recall 机制为何没有收益，作者的论证是：

> "These results suggest that lossless storage adds machinery most models seldom use, and retrieving elided observations does not consistently translate into completed tasks."

### 关键实证发现（作为机制有效性的证据）

- **上下文管理的价值随预算收紧而上升**：managed tiers（T1–T4）与 T0 的成功率差，在模型平均下随 32k→64k→96k→128k 递减，SWE-Bench 上为 35.7 → 15.9 → 5.5 → 2.7 个百分点，Terminal-Bench 上为 9.5 → 7.5 → 4.8 → 2.8。T0 的窗口溢出率在 SWE-Bench 上从 78.7% 降到 8.7%，Terminal-Bench 上从 61.0% 降到 12.1%，而所有 managed tier 的溢出失败始终为零。
- **T4 的成本最优**：T4 在 8 个模型–基准面板中的 7 个取得最低成本，成功率与 T1–T3 相当；其峰值上下文比（peak context / nominal budget）在四档预算下都是最低的。
- **Recall（M2）几乎不被使用**：32 组模型–基准–窗口比较中 T2 优于 T1 的 15 组、劣于的 14 组、持平 3 组，等权平均差为 −0.36 个百分点。64 个 T2/T4 设置中有 36 个（56.3%）从未调用 `recall_event`，调用率中位数为零，等权平均从 32k 的 0.540 次/任务降到 128k 的 0.007 次/任务。
- **Planning 的作用随能力迁移**：对 Nemotron-3 30B，planning 在 SWE-Bench 上提升成功率 11.6 个百分点、Terminal-Bench 上 4.5 个百分点，但成本更高；对 550B 与 Mistral-Medium-3.5-128B，planning 主要降低成本（SWE-Bench 上约降 30% 与 32%），成功率仅小幅下降（2.0 与 0.4 个百分点）。轨迹分析显示：planning 让最弱模型的轨迹从 5 轮延长到 40 轮（中位数）以完成首次编辑；对强模型则主要削减 post-edit verification。
- **动作空间的交叉点**：预定义工具对 bash 能力弱的模型帮助最大（30B 在 SWE-Bench 上 +15.0%、Terminal-Bench 上 +10.1%）；而对 550B，bash-only 反而提升成功率 3.6% / 5.6% 并降低成本 53% / 30%。细粒度证据（Table 7）显示 bash-only 减少了对已编辑文件的重复 patch（30B 从 3.3 降到 0.4），并把文件写入推向更粗粒度的 create-or-replace（30B 从 28% 升到 64%）。

## 4. 训练目标

**本文没有提出任何新的训练目标。** 这是一篇纯实证/消融研究：harness 是手工构建的软件层（基于 LangGraph，基准经 Harbor 驱动），不训练模型参数、不训练 adapter、不做 RL。四个被测模型（Nemotron-3 30B/120B/550B、Mistral-Medium-3.5-128B）均以 SGLang 在 BF16 精度下本地服务，temperature = 0、top-p = 0.95、每轮输出上限 16,384 tokens，全部为推理期调用。

因此，严格来说本文**没有损失函数**，正文中也未给出任何训练损失公式。文中出现的"summarization"是推理期的上下文压缩操作（由对同一被测模型的一次单独、无工具的调用生成摘要），**不是训练监督信号**；同理，`recall_event`、`update_plan` 都是推理期工具调用，不涉及参数更新。

与"预训练目标的关系"：论文未讨论任何预训练目标，也明确声明其结论是对特定 harness 实现的条件效应估计，而非普适最优 harness。

推理期约束与训练目标的区分（本文的关键易混点）：

- **训练期**：无任何训练。
- **推理期约束**：每任务最多 300 步；工具结果截断到 24k 字符；每步最多 8 个只读工具并行；软/硬上下文阈值为可用窗口的 0.6 / 0.85，原文近期窗口预算为 0.3 且下限两轮；stuck detection 在连续 5 次相同工具调用或 5 次相同失败调用后发提醒，连续 8 次相同失败调用后提前终止；安全门包括 workspace guard（拒绝逃出项目根的路径，含 symlink）、read-before-write 检查（含内容哈希检测外部修改）、allow/ask/deny 权限层；编辑或写入 Python 文件后用 ruff / pyflakes / 语法回退做只读诊断并追加到工具结果。这些全部是推理期工程约束，与训练目标无关。

实验设置补充：SWE-Bench Verified（500 个经人工验证的真实 GitHub issue）与 Terminal-Bench 2.1（89 个端到端命令行任务）；指标为任务成功率与平均每任务成本（按 OpenRouter 定价）；T0–T4 × 32k/64k/96k/128k 共 20 个设置，加 planning-off 与 bash-only 两个消融，每模型–基准对 22 个设置，共 176 个。统计上使用双侧精确 McNemar 检验 + Benjamini–Hochberg 控制 FDR 0.05。

## 5. 可引用原句（供 blockquote）

- "Coding harnesses shape how autonomous coding agents translate model capabilities into long-horizon software-engineering performance, yet existing work typically evaluates harnesses as monolithic systems, leaving the effectiveness of individual components unclear."
- "comparisons between complete harnesses conflate multiple mechanisms, so a performance difference between two agents does not reveal whether the gain comes from planning, tool design, context management, or their interaction with the underlying model."
- "Context management becomes increasingly valuable as the context-window budget tightens, with most of its benefit coming from preventing context-overflow failures."
- "Staging rule-based elision before LLM-based summarization provides the strongest overall efficiency among the context-management strategies, whereas making elided content recoverable adds machinery that models rarely use and yields no accuracy gain."
- "The value of managing context cannot be separated from how much window there is to manage against, nor from whether the model is strong enough to be helped rather than starved by compaction."
- "Harness design is thus a conditional systems problem in which each component should be selected for the target model, task type, and resource budget rather than adopted as a default."

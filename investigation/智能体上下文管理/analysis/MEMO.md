# MEMO

> 来源等级：FULL_TEXT
> 一句话定位：MEMO 把智能体记忆读出重构为证据单元粒度的多模态组织，用可训练的抽取器与记忆管理器在预算内决定保留什么、以文本/图像/双通道呈现。
> 生成时间：2026-09-19 14:38:52

---

## 1. 论文概要与作者意图

MEMO 关注的是 **long-horizon LLM agent 的 memory readout（记忆读出）**：在给定 query、候选记忆记录与上下文预算的条件下，如何选出必要证据并决定其**呈现模态**。

- 论文英文全称：MEMO: Multimodal Evidence Memory Organization for Long-Horizon LLM Agents
- 缩写：MEMO
- 作者：Xian Gao, Jinpeng Wang, Jiacheng Ruan, Guangyu Cao, Ting Liu, Yuzhuo Fu（Shanghai Jiao Tong University / Tsinghua University / Institute of Automation of the Chinese Academy of Sciences）
- 发表信息：arXiv preprint arXiv:2609.07471v1 [cs.CL]，7 Sep 2026 [会议/期刊未提供]

作者指出的现有方法不足：

- **Text memory 的单位成本近乎均一**：文本记忆的 readout 仍是线性 token 序列，"makes contents with different importance compete for context space at nearly uniform unit cost"，真正有用的证据要和大量冗余内容争夺有限上下文。
- **Visual memory 会丢失细粒度语义**："after text is rendered into images, fine-grained semantic information may be difficult to preserve accurately"，渲染与压缩过程可能损失细节。
- **单一模态无法同时满足三个目标**："Restricting all memory to a single modality cannot simultaneously achieve information fidelity, structural expressiveness, and resource efficiency."
- **现有 evidence extraction 粒度太粗**：基于 memory passage 或 memory chunk 的抽取"often too coarse-grained to directly support fine-grained memory management"——一个 passage 可能只有一句相关，一个长对话 turn 可能既含关键事件又含大量无关细节。

因此作者写这篇论文的核心意图是：

> "Its core idea is to jointly decide what to preserve and how to present it at the granularity of evidence units, rather than converting retrieved results into a single modality."

## 2. 方法框架

MEMO 的整体框架是"**evidence unit 粒度的记忆组织**"：把记忆读出拆成"学到的证据抽取 + 学到的记忆规划 + 确定性的工作记忆构建"三段式。

任务定义：给定当前 query $q$、记忆记录 $R$ 与总记忆预算 $B$，MEMO 为一次 reader 调用构造工作记忆 $a=(T,V)$，其中 $T$ 是 text packet，$V$ 是可选的 visual page。所有呈现决策共享同一资源上限：$C_{\text{text}} + C_{\text{vis}} \le B$。

1. **Query-Conditioned Evidence Extraction（证据抽取器，可训练）**
   - 输入：query $q$、候选记录 $R$、预算 $B$。
   - 输出：query 相关 memory chunk 的 source 标识集合 $S$，以及每个选中 source 的字符级 key span 集合 $H$，记作 $(S,H)=E_\phi(q,R,B)$。
   - 功能：在原始记录中先定位有用内容，使后续步骤在语义更清晰、更小的记忆条目上操作。随后一个**确定性的 unit-materialization 步骤**把对应内容从原始记录中拷贝出来，保留 source link 与 highlight，并保留 key span 周围宽度为 $w$ 的局部上下文窗口，产出 evidence unit 集合 $U$。
   - evidence unit 定义见 §3.1。

2. **Memory Manager（记忆管理器，可训练）**
   - 输入：当前 query $q$、evidence units $U$、预算 $B$。
   - 输出：每个 unit 的 action（$M=\{\text{text}, \text{image}, \text{dual}, \text{drop}\}$）、action confidence、目标记忆用量、以及偏好的 page template 列表。
   - 功能：**联合**决定内容选择、呈现模态与视觉组织，因为它们共享同一预算。text 保留精确语义锚点，image 保留时间/表格/分组关系，dual 二者兼得但成本同时计入文本与视觉资源。模板偏好控制视觉单元渲染成 cards、timelines、tables、checklists 或 column layouts。

3. **Working Memory Building（确定性构建模块，不可训练）**
   - 输入：当前 query、抽取器产出的 evidence units、总预算、memory manager 的 plan。
   - 输出：最终工作记忆 $a=(T,V)$，交给 frozen reader 产生答案。
   - 功能：在共享预算下决定每个 unit 进入 text packet、visual page、两者或都不进入；text 条目直接拷贝 key wording 并保留 unit/source 标识以维持可追溯性；视觉块包含 title、source、content、information type 与 highlighted wording；已知文本成本后剩余预算决定可用页面尺寸，从而决定视觉成本 $C_{\text{vis}}$。

数据流：$q,R,B \rightarrow$ Evidence Extractor $\rightarrow$ evidence units $U$ $\rightarrow$ Memory Manager $\rightarrow$ memory plan $\rightarrow$ Working Memory Builder $\rightarrow$ $a=(T,V)$ $\rightarrow$ frozen MLLM reader $\rightarrow$ answer。

框架图：Fig. 2（p.4），"Workflow of MEMO."

![MEMO 方法框架](figures/MEMO_Fig2.png)

> 图注原文：Figure 2: Workflow of MEMO. MEMO extracts evidence units from raw memory, plans their presentation as text, image, dual-channel, or dropped content, and builds a query-specific working memory for the multimodal LLM agent.
> 文档引用：../analysis/figures/MEMO_Fig2.png

## 3. 关键机制与创新点

### Evidence Unit：可追溯的最小记忆条目

MEMO 把记忆管理的最小对象定义为 evidence unit：

$$
u_i = (id, x_i, h_i, s_i, c_i). \tag{1}
$$

其中：

- $id$ 是单元标识；
- $x_i$ 存储证据内容（从原始记录中直接拷贝）；
- $h_i$ 存储抽取器定位到的 key span；
- $s_i$ 记录来源（source）；
- $c_i$ 存储证据保留的 priority rule：该规则取决于 $x_i$ 中的内容属于 $h_i$ 定位的 key span，还是属于其关联上下文；它可以要求语义信息必须以文本形式保留、允许用视觉呈现来满足预算约束，或允许省略。

该机制的核心是：

> "The resulting evidence unit is the smallest memory item that MEMO can retain, reformat, or omit." 以单元粒度携带 provenance、priority 与 presentation requirement，使后续模块在压缩记忆时能保护更关键的语义信息。

### Query-Conditioned Evidence Extraction：字符级 span 监督

抽取器通过**全参数监督微调**（full-parameter SFT）训练，从 query、候选记录与预算生成结构化输出。设 $e^\star$ 为序列化的抽取目标，其目标函数为：

$$
L_{\text{ext}}(\phi) = -\sum_t \log p_\phi(e^\star_t \mid e^\star_{<t}, q, R, B). \tag{2}
$$

其中：

- $\phi$ 是抽取器参数；
- $e^\star_t$ 是抽取目标序列的第 $t$ 个 token；
- $e^\star_{<t}$ 是目标序列中 $t$ 之前的 token（teacher forcing 前缀）；
- $q$ 是当前 query，$R$ 是候选记录，$B$ 是预算；
- 求和范围 $t$ 覆盖整个序列化抽取目标。

训练数据来自四个 benchmark 训练集的证据标注：优先使用各 benchmark 的原生证据标注，无原生 span 标注时使用确定性弱标签。HotpotQA / 2WikiMultiHopQA 以每个 context paragraph 为 source chunk，官方 supporting facts（title, sentence id）决定选中段落与支撑句；LoCoMo 以带 speaker 与 timestamp 的单个 dialogue turn 为 source chunk，官方 evidence turn identifier 决定选中 source，且 train/val/test 在 dialogue 级别划分；ALFWorld 从官方 expert trajectory 读取任务与高层动作，确定性重放动作构造相邻的执行前/执行后状态，执行前状态中的每个 entity 作为 source chunk，通过 state differencing 选出下一步发生变化的 entity，整个序列化状态块标为 state evidence。

### Joint Evidence Selection and Presentation：动作空间与预算耦合

memory manager 为每个 unit 选择呈现形式，动作空间为：

$$
M = \{\text{text}, \text{image}, \text{dual}, \text{drop}\}. \tag{3}
$$

其中：

- $\text{text}$：在 text packet $T$ 中保留紧凑的文本锚点；
- $\text{image}$：把该 unit 放入 visual page $V$；
- $\text{dual}$：既保留文本锚点又加入视觉结构，成本同时计入文本与视觉资源；
- $\text{drop}$：从当前工作记忆中省略该 unit（节省资源，但可能移除后续推理所需证据）。

该机制的核心是：

> "Because content selection, presentation form, and visual organization compete for the same budget, the memory manager plans them jointly with a single evidence-unit-level plan."

### Memory Manager Training from Reader Outcomes：用离线 reader 做反事实选择

对同一 query 与证据，作者尝试把某个 evidence unit 分别保留为 text、放入 image、保留两种形式、或从当前记忆中移除，**其他 unit 保持不变**，以此比较不同反事实候选的效果。每个候选记忆交给**同一个 frozen reader** 回答问题，记录答案质量与总记忆用量；违反预算的候选被剔除；当多个候选答案质量相近时，优先选择 token 更少的那个。胜出的选择成为该 evidence unit 的 action label。

每个 action label 还带一个 confidence score，来自最优动作与次优动作之间的差距：当某个呈现形式明显更好时 confidence 高，两个选择表现相近时 confidence 低。该值被缩放到固定区间，只表示该训练样本上动作选择的确定程度。image 与 drop 选择还要通过一个基于真实 reader 结果的额外检查，防止可能丢失关键信息的选择进入训练目标。

template label 同样由反事实候选的答案质量决定；训练目标存储的是**有序的候选模板列表**而非单一模板，推理时保留第一个满足预算约束的模板。设 $y^\star$ 为完整 memory plan，memory manager 的训练目标为：

$$
L_{\text{sft}}(\theta) = -\sum_t \log p_\theta(y^\star_t \mid y^\star_{<t}, q, U, B). \tag{4}
$$

其中：

- $\theta$ 是 memory manager 参数；
- $y^\star_t$ 是 memory plan 目标序列的第 $t$ 个 token；
- $y^\star_{<t}$ 是目标序列中 $t$ 之前的 token；
- $q$ 是 query，$U$ 是 evidence units，$B$ 是预算；
- 输入还包含 evidence units $U$ 与预算 $B$。

该机制的核心是：

> "The memory manager is trained with feedback from an offline reader that measures the utility of the guided memory plan, so that retention and presentation decisions align with downstream usage."

## 4. 训练目标

MEMO **不是 training-free** 方法，它训练两个组件，且两者都是监督微调（SFT），不是 RL：

- **Evidence Extractor**：全参数 SFT，目标为式 (2) $L_{\text{ext}}(\phi)$。
- **Memory Manager**：SFT，目标为式 (4) $L_{\text{sft}}(\theta)$。

训练策略与实现细节：

- 两个可训练组件（evidence extractor 与 memory manager）都以 **Qwen2.5-1.5B-Instruct** 作为 backbone。
- 使用 **verl** 框架做监督微调。
- 训练 memory manager 时使用的 reader 是 **InternVL3.5-4B-Instruct**（frozen，离线使用）。
- 实验在 NVIDIA A100 GPU 上完成。
- 训练监督信号不来自人工标注的"最佳呈现形式"，而来自**离线 frozen reader 的反事实评估结果**（答案质量 + 记忆用量），即把 reader 的效用作为 action label 与 template label 的来源。confidence 与额外的 image/drop 检查是对该自动标注的过滤。

与预训练目标的关系：论文没有沿用或改写任何生成式预训练损失；两个目标都是标准的自回归负对数似然（teacher forcing）形式，作用在各自的结构化输出序列（抽取目标 $e^\star$ / memory plan $y^\star$）上，属于在预训练 LLM 之上的任务特定 SFT。

推理期约束与训练目标的区分：**推理阶段没有额外的优化或损失**。推理时的约束是硬性的资源约束与规则性选择，而非可微损失：

- 共享预算约束 $C_{\text{text}} + C_{\text{vis}} \le B$（实验中对比严格 128-token 预算与无 token 预算两种设置；无预算设置下执行仍使用 4,096-token LLM 上下文窗口）；
- 模板选择规则：推理时保留**第一个**满足预算约束的候选模板；
- drop 动作在推理时同样可被选中，用于省略 unit。

这些推理期约束不参与训练，不应与式 (2)、(4) 的训练目标混淆。

## 5. 可引用原句（供 blockquote）

- "The key challenge in agent memory is therefore not only to retrieve relevant records, but also to select necessary evidence under a given budget and organize it in an appropriate modality."
- "Its core idea is to jointly decide what to preserve and how to present it at the granularity of evidence units, rather than converting retrieved results into a single modality."
- "Text preserves high fidelity, but its linear token representation makes contents with different importance compete for the limited context at nearly uniform unit cost."
- "Visual readout renders text into document-like images, which can use two-dimensional layouts to expose structure and emphasize key information, but it may lose fine-grained details during rendering and compression."
- "The memory manager is trained with feedback from an offline reader that measures the utility of the guided memory plan, so that retention and presentation decisions align with downstream usage."
- "This shows that the memory manager does not simply minimize token count: it spends a few additional tokens when they improve answers."
- "These results indicate that the proposed evidence-level multimodal organization serves as an effective and efficient memory interface."

## 6. 主要实验结果（供交叉核对）

- 四个 benchmark：HotpotQA、2WikiMultiHopQA、LoCoMo、ALFWorld；三个 frozen reader：InternVL3.5-8B、Qwen3-VL-32B、gpt-5.4-mini。
- 128-token 预算下：MEMO 在三个 reader 上都取得最佳 Overall EM/F1。以 Qwen3-VL-32B 为 reader 时，2Wiki 上 F1 为 73.91，而纯文本记忆为 56.26、纯视觉记忆为 35.89。
- 无 token 预算设置下：MEMO 平均仅用 83.93 tokens，比 MemAgent 少 50.5–50.7%，比 Text-only 少 66.9%。
- 组件消融（128-token 预算，gpt-5.4-mini）：两个组件都关闭时 47.49 EM / 57.24 F1 / 126.22 tokens；仅开 evidence extractor 为 53.56 EM / 66.09 F1 / 70.12 tokens；两者都开为 57.78 EM / 68.62 F1 / 81.07 tokens。

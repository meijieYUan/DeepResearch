# LLMLingua

> 来源等级：FULL_TEXT
> 一句话定位：提出 coarse-to-fine 的提示词压缩方法 LLMLingua，用预算控制器、迭代 token 级压缩与分布对齐，在几乎不损性能下实现最高 20x 压缩。
> 生成时间：2026-09-19 19:39:16

---

## 1. 论文概要与作者意图

LLMLingua 关注的是 **long prompt compression for efficient LLM inference**，即在不改动黑盒 LLM 参数的前提下，把动辄上万 token 的提示词（instruction / demonstrations / question）压缩成更短的提示词，从而降低推理成本、加速推理。

- 论文全称：LLMLingua: Compressing Prompts for Accelerated Inference of Large Language Models
- 作者：Huiqiang Jiang, Qianhui Wu, Chin-Yew Lin, Yuqing Yang, Lili Qiu（Microsoft Corporation）
- 发表信息：arXiv:2310.05736v2 [cs.CL]，2023 年 12 月 6 日；正式发表会议/期刊在本次读取的正文范围内 [材料未提供]
- 代码链接（原文脚注给出）：https://aka.ms/LLMLingua
- 核心问题：CoT prompting 与 ICL 使提示词越来越长（"even exceeding tens of thousands of tokens"），而通过量化、压缩模型参数来加速的方法在只能通过 API 访问 LLM 时不可用，因此需要从**输入提示词**侧做压缩。

作者指出的现有方法不足：

- **Ignoring the interdependence between the compressed contents**：Selective-Context 基于小模型逐词法单元计算 self-information 并丢弃低信息量内容，但忽略了被压缩内容之间的相互依赖（interdependence），且基于条件独立假设，容易丢失 CoT 中的关键推理信息。
- **Neglecting the correspondence between the target LLM and the small LM**：用于压缩的小语言模型与真正被服务的目标 LLM 之间存在 **distribution discrepancy**（分布差异），压缩时未考虑这一点。
- **Prompt tuning / special-token 方法的局限**：这类方法通常为特定任务定制，部分方法甚至需要微调整个语言模型（"even require to fine-tune the whole language model"），严重限制适用场景。
- **Generation-based 方法（如让 GPT-4 生成压缩提示）的局限**：生成内容与长度不可控（uncontrollable），计算成本高，且生成的完整连续句子压缩率偏低；论文 Table 2 显示 GPT4-Generation 在 BBH 上表现很差（1-shot 27.13 EM）。
- **Token pruning / merging 类方法**：面向 BERT、ViT 等较小模型，且依赖微调或推理中间结果。
- **摘要/记忆式方法**：需要多次调用 LLM，成本很高。

作者的核心意图与主张：

> To accelerate model inference and reduce cost, this paper presents LLMLingua, a coarse-to-fine prompt compression method that involves a budget controller to maintain semantic integrity under high compression ratios, a token-level iterative compression algorithm to better model the interdependence between compressed contents, and an instruction tuning based method for distribution alignment between language models.

> we are the first to evaluate reasoning and ICL capabilities in the domain of efficient LLMs.

## 2. 方法框架

LLMLingua 是一个 **coarse-to-fine（粗到细）** 的提示词压缩框架：先用预算控制器在 demonstration/sentence 级别做粗粒度压缩，再用迭代 token 级算法做细粒度压缩，并额外用指令微调对齐小模型与目标 LLM 的分布。整体由三个模块组成。

1. **Budget Controller（预算控制器）**
   - 输入：小语言模型 $\mathcal{M}_s$、原始提示词 $\mathbf{x} = (\mathbf{x}_{ins}, \mathbf{x}_{dems}, \mathbf{x}_{que})$、目标整体压缩率 $\tau$、预定义的 $\tau_{ins}$ 与 $\tau_{que}$。
   - 输出：粗粒度压缩后保留的 demonstration 子集 $\mathcal{D}$，以及分配给 instruction 与 question 的额外预算 $\Delta\tau_{ins,que}$。
   - 功能：为提示词的不同组成部分（instruction、demonstrations、question）动态分配不同的压缩率；因为 instruction 与 question 直接影响生成结果、应保留更多预算，而多条 demonstration 之间信息冗余、可以给更少预算。同时在高压缩率下采用 sentence-level / demonstration-level dropout 而非 token-level dropout，以保持语言完整性。
   - 关键操作：用小模型计算每条 demonstration 的 perplexity，按 PPL 降序依次加入 $\mathcal{D}$，直到再加入一条会使 $\mathcal{D}$ 的 token 数超过上限 $k \cdot \tau_{dems} L_{dems}$（$k$ 为 granular control coefficient）；随后把剩余预算分给 instruction 与 question。

2. **Iterative Token-level Prompt Compression（ITPC，迭代 token 级压缩）**
   - 输入：预算控制器输出的提示词 $\mathbf{x}' = (\mathbf{x}_{ins}, \mathbf{x}_{\mathcal{D}}, \mathbf{x}_{que})$、目标压缩率 $\tau$、调整后的压缩率 $\Delta\tau_{ins,que}$。
   - 输出：最终压缩提示词 $\tilde{\mathbf{x}}$。
   - 功能：把 $\mathbf{x}'$ 切成若干 segment $\mathcal{S} = \{\mathbf{s}_1, \mathbf{s}_2, ..., \mathbf{s}_m\}$，逐个 segment 估计条件概率，并把已压缩的前序 segment 拼接到后续 segment 上，从而缓解式 (4) 中条件独立假设带来的不准确。
   - 关键操作：对每个 segment 由 PPL 分布动态计算压缩阈值 $\gamma_j$，保留 PPL 高于 $\gamma_j$ 的 token。

3. **Distribution Alignment（分布对齐）**
   - 输入：预训练小模型 $\mathcal{M}_s$、由 LLM 生成的数据对 $(x_i, y_i^{LLM})$（使用 Alpaca 数据集）。
   - 输出：经过指令微调、分布更接近目标 LLM 的小模型 $\mathcal{M}_s$。
   - 功能：缩小小语言模型与黑盒目标 LLM 之间的分布差距，使小模型给出的 PPL 更能反映目标 LLM 的判断。

数据流：原始长提示词 → Budget Controller（PPL 排序 + demonstration 级筛选，输出 $\mathcal{D}$ 与 $\Delta\tau_{ins,que}$）→ ITPC（segment 级迭代、阈值 $\gamma_j$ 过滤 token）→ 压缩提示词 $\tilde{\mathbf{x}}$ → 送入黑盒 LLM 执行推理。Distribution Alignment 独立作用于小模型 $\mathcal{M}_s$，为前两个模块提供更对齐的 PPL 估计。

框架图：Fig. 1（p.3），"Framework of the proposed approach LLMLingua."

![LLMLingua 方法框架](figures/LLMLingua_Fig1.png)

> 图注原文：Figure 1: Framework of the proposed approach LLMLingua.
> 文档引用：../analysis/figures/LLMLingua_Fig1.png

## 3. 关键机制与创新点

### 3.1 问题形式化：压缩率与目标

给定原始提示词 $\mathbf{x} = (\mathbf{x}_{ins}, \mathbf{x}_{dems}, \mathbf{x}_{que})$，其中

$$
\mathbf{x}_{ins} = \{x^{ins}_i\}_{i=1}^{L_{ins}},\quad
\mathbf{x}_{dems} = \{x^{dems}_i\}_{i=1}^{L_{dems}},\quad
\mathbf{x}_{que} = \{x^{que}_i\}_{i=1}^{L_{que}}
$$

分别表示原始提示词中的 instruction、demonstrations 与 question。$\tilde{L}$、$L_{ins}$、$L_{dems}$、$L_{que}$ 分别表示 $\tilde{\mathbf{x}}$、$\mathbf{x}_{ins}$、$\mathbf{x}_{dems}$、$\mathbf{x}_{que}$ 的 token 数。令 $L = L_{ins} + L_{dems} + L_{que}$ 为 $\mathbf{x}$ 的总序列长度，则压缩率定义为 $\tau = \tilde{L}/L$，$\tau \in [0,1]$，压缩比（compression ratio）为 $1/\tau$。$\tau$ 越小推理成本越低。令 $\tilde{\mathbf{x}}^G$ 表示由 $\tilde{\mathbf{x}}$ 得到的 LLM 生成结果，$\mathbf{x}^G$ 表示由 $\mathbf{x}$ 得到的 token，则希望 $\tilde{\mathbf{x}}^G$ 的分布尽可能接近 $\mathbf{x}^G$，形式化为：

$$
\min_{\tilde{\mathbf{x}},\tau} \mathrm{KL}\left(P(\tilde{\mathbf{x}}^G | \tilde{\mathbf{x}}), P(\mathbf{x}^G | \mathbf{x})\right), \tag{1}
$$

其中：

- $\tilde{\mathbf{x}}$ 是待优化的压缩提示词；
- $\tau$ 是压缩率；
- $\mathrm{KL}(\cdot,\cdot)$ 衡量压缩后生成分布与原始生成分布的差异。

### 3.2 Budget Controller：按组件分配压缩预算

demonstration 的压缩率由目标整体压缩率与预定义的 instruction/question 压缩率反推得到：

$$
\tau_{dems} = \frac{\tau L - (\tau_{ins} L_{ins} + \tau_{que} L_{que})}{L_{dems}}. \tag{2}
$$

其中：

- $\tau$ 是目标整体压缩率；
- $\tau_{ins}$、$\tau_{que}$ 是预先设定的 instruction 与 question 压缩率（实验中取 $\tau_{ins}=0.85$、$\tau_{que}=0.9$）；
- $L_{ins}$、$L_{que}$、$L_{dems}$ 分别是 instruction、question、demonstrations 的 token 数。

得到粗粒度压缩结果 $\mathcal{D} = \{x_i\}_{i=1}^{L_{\mathcal{D}}}$ 后，把剩余预算分配给 instruction 与 question：

$$
\Delta\tau = \frac{k \cdot \tau_{dems} L_{dems} - \tilde{L}_{\mathcal{D}}}{L_{ins} + L_{que}}, \tag{3}
$$

其中：

- $k$ 是 granular control coefficient（实验中取 2）；
- $\tau_{dems} L_{dems}$ 是 demonstrations 的目标 token 预算；
- $\tilde{L}_{\mathcal{D}}$ 是 $\mathcal{D}$ 中的总 token 数；
- $\Delta\tau$ 是追加给 instruction 与 question 的额外预算。

该机制的核心是：

> 提示词不同组成部分对生成结果的影响不同——instruction 与 question 需要更小的压缩率（保留更多 token），而冗余的 demonstrations 可以承受更高压缩；同时在高压缩率下用 sentence/demonstration 级 dropout 替代 token 级 dropout，以维持语言完整性。

### 3.3 Iterative Token-level Prompt Compression：打破条件独立假设

论文指出，直接用 perplexity 做压缩会遇到 **independence assumption** 的内在局限（类比 Mask Language Model）：

$$
\begin{aligned}
p(\tilde{\mathbf{x}}) &= \prod_{i=1}^{L} p(\tilde{x}_i | \tilde{\mathbf{x}}_{<i}) \\
&\approx \prod_{i=1}^{L'} p(x'_i) = \prod_{i=1}^{L'} p(x_i | \tilde{\mathbf{x}}_{<i}, \mathbf{x}_{<i}),
\end{aligned} \tag{4}
$$

其中：

- $\mathbf{x}' = (\mathbf{x}_{ins}, \mathbf{x}_{\mathcal{D}}, \mathbf{x}_{que})$ 是 demonstration 级压缩后的原始提示词；
- $\mathbf{x}_{\mathcal{D}}$ 是 $\mathcal{D}$ 中所有 demonstration 的拼接；
- $\tilde{\mathbf{x}}$ 是最终压缩提示词；
- $\tilde{\mathbf{x}}_{<i}$ 与 $\mathbf{x}_{<i}$ 分别表示第 $i$ 个 token $x_i$ 之前被保留的 token 与被压缩的 token；
- $L'$ 与 $\tilde{L}$ 分别是 $\mathbf{x}'$ 与 $\tilde{\mathbf{x}}$ 的 token 数。

为缓解该假设带来的不准确，ITPC 把提示词切成 segment 并做条件概率估计：

$$
\begin{aligned}
p(\tilde{\mathbf{s}}_j) &= \prod_{i=1}^{\sum_k^{j} L_{s,k}} p(\tilde{s}_{j,i} | \tilde{\mathbf{s}}_{j,<i}, \tilde{\mathbf{s}}_{<j}) \\
&\approx \prod_{i=1}^{\sum_k^{j-1} L_{s,k} + \tilde{L}_{s,j}} p(s_{j,i} | \mathbf{s}_{j,<i}, \tilde{\mathbf{s}}_{<j}),
\end{aligned} \tag{5}
$$

其中：

- $\mathbf{s}_{j,i}$ 表示第 $j$ 个 segment 中的第 $i$ 个 token；
- $L_{s,j}$ 与 $\tilde{L}_{s,j}$ 分别表示第 $j$ 个原始 segment 与压缩后 segment 的 token 长度；
- $\tilde{\mathbf{s}}_{<j}$ 表示第 $j$ 个 segment 之前已压缩的 segment。

每个 segment 的压缩阈值 $\gamma_j$ 依据 PPL 分布与对应压缩率 $\tau_{s_j}$ 动态计算：

$$
\tau_{s_j} =
\begin{cases}
\tau_{ins} + \Delta\tau, & \text{if } \mathbf{s}_j \text{ from } \mathbf{x}_{ins}, \\
\tau_{dems}, & \text{if } \mathbf{s}_j \text{ from } \mathbf{x}_{\mathcal{D}}, \\
\tau_{que} + \Delta\tau, & \text{if } \mathbf{s}_j \text{ from } \mathbf{x}_{que}.
\end{cases} \tag{6}
$$

最终，每个 $\mathbf{s}_j$ 中 PPL 大于 $\gamma_j$ 的 token 被保留：

$$
\tilde{\mathbf{s}}_j = \{s_{j,i} \mid p(s_{j,i}) > \gamma_j\}. \tag{7}
$$

该机制的核心是：

> 通过把已压缩的前序 segment 拼接到后续 segment 上，逐个 segment 迭代估计条件概率，从而建模被压缩内容之间的相互依赖，缓解条件独立假设带来的信息损失。

### 3.4 Distribution Alignment：用指令微调对齐小模型与目标 LLM

$$
\min_{\theta_s} \mathbb{E}\left[ \frac{1}{N} \sum_{i=1}^{N} \mathcal{L}\left(x_i, y_i^{LLM}; \theta_{\mathcal{M}_s}\right) \right], \tag{8}
$$

其中：

- $\theta_{\mathcal{M}_s}$ 表示 $\mathcal{M}_s$ 的参数；
- $(x_i, y_i^{LLM})$ 是 instruction $x_i$ 与 LLM 生成文本 $y_i^{LLM}$ 组成的训练对；
- $N$ 是用于指令微调的全部样本数。

该机制的核心是：

> 小模型与目标 LLM 之间存在分布差异，直接用小模型的 PPL 估计目标 LLM 的判断会有偏差；用 LLM 生成的数据对小模型做指令微调，可使两者分布更接近。实验中该模块在 GSM8K 上带来 0.56 的提升。

## 4. 训练目标

LLMLingua 本身**不训练目标 LLM**，也不通过 LLM 反向传播梯度（原文："compress a long prompt into a shorter one without any gradient flow through the LLMs"）。被训练的只有用于压缩的**小语言模型** $\mathcal{M}_s$，且训练目的仅是**分布对齐**，而非学习压缩策略本身。

- 训练目标：式 (8) 的指令微调目标，即用 LLM 生成的数据对预训练小模型 $\mathcal{M}_s$ 做 instruction tuning。数据使用 Alpaca 数据集，且明确"exclusively employed for aligning small language models with black-box LLMs, and is not utilized in the evaluation process"。
- 小模型选择：Alpaca-7B 或 GPT2-Alpaca。
- 与预训练目标的关系：预算控制器与 ITPC 两个模块**完全不需要训练**，它们依赖的是小模型**预训练时已有的**语言建模能力所输出的 perplexity（PPL）——即"无新增训练目标"，PPL 只是对预训练语言建模目标的直接复用。只有 Distribution Alignment 引入新的（指令微调）训练。
- 推理期约束与训练目标的区分：压缩率 $\tau$、压缩比 $1/\tau$、granular control coefficient $k$、预定义 $\tau_{ins}$、$\tau_{que}$、segment size（100）以及阈值 $\gamma_j$ 都是**推理期的超参数/约束**，不是训练监督信号；式 (1) 的 KL 最小化是压缩问题的**形式化目标**，论文并未用它做梯度训练，而是通过上述启发式流程近似实现。
- 计算开销（推理期，非训练目标）：原文给出整体计算量

$$
c = (L + kL/\tau + L/\tau) \cdot c_{small} + L/\tau \cdot c_{LLMs}, \tag{9}
$$

其中 $c_{small}$ 与 $c_{LLMs}$ 分别是小 LM 与 LLM 的每 token 计算量；$L$、$kL/\tau$、$L/\tau$ 分别是预算控制器、ITPC 中待压缩 token 的 PPL 计算、ITPC 中压缩结果的条件 PPL 计算（使用 KV cache）所需的 token 推理次数。假设 $c_{small} \approx 7/175\, c_{LLMs} = 1/25\, c_{LLMs}$，当 $\tau=5$ 时 $c \approx 0.264 \cdot L c_{LLMs} \approx 1/4 \cdot L c_{LLMs}$，即约 4x 计算节省。

## 5. 可引用原句（供 blockquote）

- "To accelerate model inference and reduce cost, this paper presents LLMLingua, a coarse-to-fine prompt compression method that involves a budget controller to maintain semantic integrity under high compression ratios, a token-level iterative compression algorithm to better model the interdependence between compressed contents, and an instruction tuning based method for distribution alignment between language models."
- "However, this method not only ignores the interdependence between the compressed contents but also neglects the correspondence between the LLM being targeted and the small language model used for prompt compression."
- "the proposed approach yields state-of-the-art performance and allows for up to 20x compression with little performance loss."
- "we are the first to evaluate reasoning and ICL capabilities in the domain of efficient LLMs."
- "Our approach holds substantial practical implications, as it not only reduces computational costs but also offers a potential solution for accommodating longer contexts in LLMs."
- "The method of compressing prompts has the potential to enhance downstream task performance by compressing longer prompts and to improve the LLMs's inference efficiency by compressing the KV cache."
- "we might observe a notable performance drop when trying to achieve excessively high compression ratios such as 25x-30x on GSM8K"

## 6. 关键实验结论（供对比参考）

- 数据集：GSM8K、BBH（推理与 ICL）、ShareGPT（对话）、Arxiv-March23（摘要）。目标 LLM 为 GPT-3.5-Turbo-0301 与 Claude-v1.3，均通过 API 访问。
- 主要结果：GSM8K 上 1-shot 约束下 EM 79.08（446 tokens，5x），甚至略高于 Full-shot 的 78.85（2,366 tokens）；quarter-shot 约束下以 117 tokens 达到 77.33 EM（20x）。BBH 上 1-shot 约束 70.11 EM（288 tokens，3x）。
- 消融（GSM8K，1-shot）：去掉 ITPC 降至 72.93；去掉 Budget Controller 降至 73.62；去掉 Dynamic Compression Ratio 降至 77.26；预算控制器改为随机选择降至 72.78；去掉 Distribution Alignment 降至 78.62。
- 延迟：V100-32G 上端到端加速 1.7x–5.7x（压缩比 1x→10x）。

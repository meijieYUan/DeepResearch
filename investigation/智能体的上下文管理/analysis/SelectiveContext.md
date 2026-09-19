# SelectiveContext

> 来源等级：FULL_TEXT
> 一句话定位：用自信息衡量词元/短语/句子冗余度并按百分位剪枝输入上下文，免训练降低LLM推理显存与延迟。
> 生成时间：2026-09-19 19:39:20

---

## 1. 论文概要与作者意图

论文全称 **Compressing Context to Enhance Inference Efficiency of Large Language Models**，方法名 **Selective Context**，作者 Yucheng Li、Bo Dong、Chenghua Lin（通讯作者）、Frank Guerin，单位为 University of Surrey 与 University of Manchester。预印本标注 `arXiv:2310.06201v1 [cs.CL] 9 Oct 2023`；正式发表会议/期刊 [材料未提供]。代码与数据：https://github.com/liyucheng09/Selective_Context 。

Selective Context 关注的是 **长上下文推理效率**：LLM 处理长文档与长对话时，内存与推理时间随 2-D attention 矩阵二次增长，且输入超出固定 context window 时会被截断。

作者认为，已有方法主要从架构或蒸馏入手，忽略了输入上下文自身的冗余。作者指出的不足与冗余来源包括：

- **架构/蒸馏路线的局限**：sparse attention、local dense attention、soft prompt + distillation 等都聚焦于架构或蒸馏，而没有处理输入上下文本身的冗余；
- **自然语言固有冗余 (inherent redundancy of natural language)**：如 "B: Yes, I did get the groceries." 中重复的部分，是人类交流的常见冗余；语言学研究指出冗余在语言中无处不在；
- **与训练语料的重复 (overlap with training material)**：若输入中的某些部分已出现在 LLM 预训练阶段，则删除它们模型仍能给出正确答案。

因此，作者写这篇论文的核心意图是：

> In contrast to existing approaches that primarily focus on architectures or distillations, we introduce a fresh perspective to tackle the redundancy in the input context itself, thus proposing a complementary, model-agnostic approach that can be potentially combined with other architecture optimisation methods to further enhance inference efficiency.

## 2. 方法框架

Selective Context 是一个 **training-free、model-agnostic 的输入侧压缩方法**：用一个 base causal language model 计算自信息，据此判断哪些词元/短语/句子是冗余的并删除，从而在不动模型参数的前提下压缩上下文。

整体流程由三个步骤组成（论文原文的顺序编号）：

1. **Computing Self-Information（计算自信息）**
   - 输入：上下文 $C = x_0, x_1, ..., x_n$（$x_i$ 为 token），以及一个 base language model $M$（如 GPT、OPT、LLaMA）。
   - 功能：用 $M$ 计算每个 token 的自信息（负对数似然），作为信息量的度量。
   - 实现细节：作者观察到 LLM 倾向于给靠后的词元更低的 self-information，因此**不一次性处理整个上下文，而是逐句计算自信息**。

2. **Merging into Lexical Units（合并为 lexical unit）**
   - 输入：token 序列及其自信息值。
   - 输出：lexical unit（可以是 token、phrase 或 sentence）及其自信息。
   - 功能：直接在 token 级过滤会产生非常不连贯的上下文；因此把 token 合并为短语或句子级单位。合并依据自信息的**可加性 (additivity)**，把组成词元的自信息求和。
   - 实现：用 NLTK sentence tokenizer 得到句子级单位；用 spaCy 合并名词短语。**不合并动词短语**，因为可能产生过长的短语。

3. **Selective Retention of Informative Context（按百分位选择性保留）**
   - 输入：所有 lexical unit 的自信息值。
   - 输出：过滤后的上下文 $C'$。
   - 功能：不用固定阈值、也不用固定保留 top-k，而是设计 **percentile-based filtering**，按自信息分布的百分位自适应地保留信息量最高的内容。先按自信息降序排序，再计算第 $p$ 百分位，保留自信息 ≥ 该百分位的单位。
   - 例：短语级、$p=50$ 时约一半短语被过滤，处理后上下文仅保留 57.2% 的 token，节省 42.7% 的上下文长度。

数据流：原始上下文 → base LM 逐句计算 token 自信息 → 按 lexical unit 求和聚合 → 百分位阈值筛选 → 压缩后的上下文 → 送入目标 LLM 推理。

论文中作为方法示意的是 Fig. 2（p.2，图注 "A visualisation of selective context. Darker colour indicates larger value of self-information."），但该图未被图注索引收录，无法截取。

图见原文 Fig. 2（p.2）。

## 3. 关键机制与创新点

### Self-Information 作为冗余度量

论文用自信息（self-information / surprisal / information content，Shannon 1948）度量词元的信息量。原文定义：

$$
I(x) = -\log_2 P(x_t|x_0, x_1, ..., x_{t-1}) \tag{1}
$$

其中：

- $I(x)$ 表示 token $x$ 的自信息；
- $P(x)$ 表示其输出概率。

方法章节给出的 token 级计算式（原文式 5）：

$$
I(x_i) = -\log_2 P(x_i|x_0, x_1, ..., x_{i-1}) \tag{5}
$$

其中：

- $C = x_0, x_1, ..., x_n$ 是输入上下文，$x_i$ 表示一个 token；
- $M$ 是用于计算自信息的 base causal language model。

作者论证：自信息衡量事件带来的 surprise/不确定性，罕见事件信息量大、自信息高；常见事件信息量小、自信息低。**自信息低的 lexical unit 信息量低、更可能可以从上下文中推断出来，因此可视为冗余。**

### 自信息的可加性 (Additivity)

原文式（4）推导了可加性：

$$
\begin{aligned}
I(x_0, x_1) &= -\log_2 P(x_0, x_1) \\
&= -\log_2 P(x_0)P(x_1|x_0) \\
&= -\log_2 P(x_0) - \log_2 P(x_1|x_0) \\
&= I(x_0)\,I(x_1)
\end{aligned} \tag{4}
$$

> 注：原文该式最后一行写作 $= I(x_0)I(x_1)$（乘号形式），与前三行的减法形式不一致，疑为原文排版/笔误；此处按原文逐字抄录，不作修正。论文正文的表述是 "we can calculate the self-information of a lexical unit by simply summing the self-information of the tokens in it"，即实际使用的是求和。

据此，一个 lexical unit $u$（由 $x_t, ..., x_{t+\alpha}$ 组成）的自信息为（原文式 6）：

$$
I(u) = \sum_{i=t}^{\alpha} I(x_i) \tag{6}
$$

其中：

- $u$ 是一个 lexical unit，由多个 token $(x_t, ..., x_{t+\alpha})$ 组成；
- $I(x_i)$ 是其中单个 token 的自信息；
- 求和范围按原文写作 $i = t$ 到 $\alpha$（原文如此，上标/下标记号可能为排版问题，未作改动）。

论文还给出句子级熵与困惑度的关系式（原文式 2、3），用于说明自信息与熵、困惑度的联系：

$$
H(S) = \frac{\sum_t I(x_t)}{N} \tag{2}
$$

$$
PP(S) = 2^{H(S)} \tag{3}
$$

其中：

- $S = (x_0, ..., x_n)$ 是句子；
- $H(S)$ 是句子 $S$ 的熵，等于句中词的平均自信息；
- $PP(S)$ 是句子的困惑度，可由熵计算得到。

### Percentile-Based Filtering（百分位过滤）

原文式（7）、（8）给出保留策略：

$$
I_p = \text{np.percentile}([I(u_0), .., I(u_k)], p) \tag{7}
$$

$$
C' = {U_i \mid I(U_i) \geq I_p,\ 1 \leq i \leq n} \tag{8}
$$

其中：

- $I(u_0), .., I(u_k)$ 是所有 lexical unit 的自信息值；
- $p$ 是百分位参数；
- $I_p$ 是该分布的第 $p$ 百分位自信息值；
- $C'$ 是过滤后的上下文，包含所有自信息不小于 $I_p$ 的 lexical unit $U_i$。

> 注：原文式 (8) 的集合记号在 PDF 文本层中缺失花括号，此处按原文语义以 `{...}` 形式呈现；符号内容未作改动。

作者对机制有效性的论证：

> Instead of using a fixed threshold or retaining a fixed number of top k lexical units, we design a percentile-based filtering approach to adaptively select the most informative content.

> Lexical units with lower self-information are less informative and thus are more likely to be inferred from the context. As a result, we may treat these parts of input as redundant during LLM inference.

### 关键机制/效果图

图注索引中可截取的图只有 Fig. 3、Fig. 4、Fig. 5，其中 Fig. 3 是任务维度上的压缩率—性能曲线，最能反映该机制的行为：

![Selective Context 在不同任务上的性能随压缩率变化](figures/SelectiveContext_Fig3.png)

> 图注原文：Figure 3: Performance of selective context on different tasks. x-axix represents compression ratios (same below).
> 文档引用：../analysis/figures/SelectiveContext_Fig3.png

## 4. 训练目标

Selective Context 是 **training-free 方法**：它不训练任何新的模型参数、adapter 或 embedding，只用一个现成的 base causal language model 计算自信息，然后对输入文本做删除操作。**因此论文没有提出任何新的训练目标或损失函数**，也没有分阶段训练策略。

论文中出现的公式（式 1–8）全部是**推理期使用的度量与筛选规则**，而非训练监督信号：

- 式 (1)、(5) 是自信息的定义（负对数似然），借用了语言模型预训练时的输出概率，但 Selective Context 并不用它做梯度更新；
- 式 (2)、(3) 是熵与困惑度的定义，用于说明自信息与这些经典概念的关联；
- 式 (4)、(6) 是可加性及其在 lexical unit 上的应用；
- 式 (7)、(8) 是百分位过滤的推理期筛选规则。

推理期设置（与训练目标无关）：

- **Base model 选择**：LLaMA 家族与 Vicuna 家族用 LLaMA-7B 计算自信息；OpenAI 家族用较小的 GPT-3 变体 `curie` 计算自信息；
- **压缩率 (Compression Ratio)**：实验取 0.2、0.35、0.5、0.65、0.8，表示被过滤掉的内容比例；
- **Lexical Unit 粒度**：token / phrase / sentence 三种，实验表明 **phrase 级最优**，其次为 token 级，sentence 级较不稳定；
- **构建开销**：为示例段落构建 selective context 的一次性开销为 46.1 ms；压缩率 0.5 时推理显存约降 36%、生成速度约快 1.32 倍（per token）。

## 5. 可引用原句（供 blockquote）

- "In contrast to existing approaches that primarily focus on architectures or distillations, we introduce a fresh perspective to tackle the redundancy in the input context itself, thus proposing a complementary, model-agnostic approach that can be potentially combined with other architecture optimisation methods to further enhance inference efficiency."
- "Lexical units with lower self-information are less informative and thus are more likely to be inferred from the context. As a result, we may treat these parts of input as redundant during LLM inference."
- "Instead of using a fixed threshold or retaining a fixed number of top k lexical units, we design a percentile-based filtering approach to adaptively select the most informative content."
- "we achieve a 50% reduction in context cost, resulting in a 36% reduction in inference memory usage and a 32% reduction in inference time, while observing only a minor drop of .023 in BERTscore and .038 in faithfulness on four downstream applications"
- "employing phrase as the basic lexical units in Selective Context is the optimal approach, consistently outperforming the other two variants, followed by token-level Selective Context. Removing redundancy at sentence-level is a rather unstable implementation compared to the token and phrase-level."
- "our approach is somewhat influenced by the phrase boundary detection procedure. We employ the noun phrase tokenisation algorithm provided by spacy in our experiments. However, we do not consider verb phrases as there is no mature solution for verb phrase tokenisation."
- "in the experiment section, we use percentile to control the pruning process. However, the optimal compression percentile varies based on specific tasks and context."

# SpotActor

> 来源等级：FULL_TEXT
> 一句话定位：提出免训练双能量引导管线 SpotActor，在语义-潜空间联合优化布局并保持多图主体一致，并给出 L2CI 基准 ActorBench。
> 生成时间：2026-09-20 00:17:57

---

## 1. 论文概要与作者意图

SpotActor 关注的是 **layout-to-consistent-image (L2CI) generation**，即同时要求「主体按给定 bounding box 就位」与「同一主体在多张图之间外观一致」的生成任务。作者称这是他们**首次提出**（pioneer）的新任务，动机来自漫画绘制等真实创作场景：先定角色外观、再设计每幕布局、最后画出一系列该角色的图。

作者认为已有工作无法同时解决这两个挑战，具体不足包括：

- **两个任务彼此割裂（no existing work embarks on addressing both challenges simultaneously）**：subject consistency 与 layout controllability 一直是两条独立的 research line，没有工作同时处理。
- **tuning-based 方法代价高且伤模型（high computational expenses and may degrade the image quality）**：TheChosenOne 的 iterative backbone tuning 需约 20 分钟且可能损害 backbone 内在能力；OneActor 虽把时间降到 5 分钟，仍需训练。
- **training-free 一致生成方法忽视空间-语义交互（hardly pay attention to the spatial-semantic interaction）**：如 StoryDiffusion 一类方法只改 self-attention，未纳入布局控制。
- **布局控制方法只优化潜空间（optimizing solely in the latent space restricts the search range）**：energy guidance 类方法（LayoutGuidance 等）只更新 latent codes，而「另一半」semantic space 被持续忽略。

因此，作者写这篇论文的核心意图是：

> "We start from the insight that the semantic space and spatial latent space of diffusion models are inherently entangled and share certain properties. Hence, we consider the semantic and latent space as a whole dual space and present a new formalization of dual energy guidance, which defines an update trajectory in the dual space."

作者列出的三点贡献为：(1) 首次提出 L2CI 任务；(2) 提出 SpotActor 管线（基于细致布局能量的 backward update + 两种注意力机制增强的 forward sampling）；(3) 提出首个 L2CI 基准 ActorBench。

## 2. 方法框架

SpotActor 是一个 **training-free** 管线，建立在 SDXL 之上，把每个采样步拆成「backward update + forward sampling」两阶段，并在 latent space 与 semantic space 的**联合 dual space** 中定义更新轨迹。

1. **Layout-Conditioned Backward Update（布局条件反向更新）**
   - 输入：当前 latent codes $z_t$、语义嵌入 $c_t$、bounding box $b=(h^{\min},w^{\min},h^{\max},w^{\max})$、subject token embedding $c^{\text{sub}}$。
   - 输出：优化后的 $z_t^*$ 与 $c_t^*$。
   - 功能：由 U-Net decoder 收集 subject token 的 cross-attention map $A^{\text{ca}}$，与 sigmoid-like 目标分布比对得到 nuanced layout energy $e$，再**同时**沿 latent 与 semantic 两个方向做梯度下降（式 (2) 与式 (9)），直到满足收敛判据。
   - 关键操作：attention map 先沿空间维 min-max 归一化得 $\tilde{A}^{\text{ca}}$；对 inter-token 分布采用 spatial normalization，让模型自发分配激活强度，以避免直接约束 range level 导致画质下降。

2. **Consistent Forward Sampling（一致前向采样）**
   - 输入：更新后的 $z_t^*$、$c_t^*$，以及各图的 layout mask $M_i$ 与特征 $h_i$。
   - 输出：本步的 $z_{t-1}$ 与 $c_{t-1}=c_t^*$。
   - 功能：用两个注意力机制替换普通 U-Net 采样，实现跨图交互以保持主体外观一致。
   - 子机制：**RISA** 负责 image 之间的 spatial-spatial 交互（按 layout mask 限定互连区域）；**SFCA** 负责 spatial-semantic 交互（让每张图与 batch 内所有语义条件交互）。

3. **数据流关系**
   - 每个采样步内：$z_t, c_t \xrightarrow{\text{backward update}} (z_t^*, c_t^*) \xrightarrow{\text{forward sampling (RISA/SFCA)}} (z_{t-1}, c_{t-1})$。
   - 形式上由式 (4) 给出：$p(z_{t-1},c_{t-1}\mid z_t,c_t)=p_f(z_{t-1},c_{t-1}\mid z_t^*,c_t^*)\cdot p_b(z_t^*,c_t^*\mid z_t,c_t)$，即 backward 与 forward 相乘构成一步转移。
   - 语义分支的前向采样被简化为恒等：$c_{t-1}=c_t^*$。

框架图：Fig. 2（p.3），"The overall architecture of SpotActor"，含 (a) 双阶段总览、(b) RISA、(c) SFCA 三个子图。

![SpotActor 方法框架](figures/SpotActor_Fig2.png)

> 图注原文：Figure 2: The overall architecture of SpotActor. (a) Our method consists of two stages at each sample step in a dual energy guidance manner. The backward stage optimizes the latent codes and semantic embeddings with the nuanced layout energy based on the sigmoid-like objective. Subsequently, the forward sampling is enhanced by two intricate attention mechanisms: (b) RISA and (c) SFCA.
> 文档引用：../analysis/figures/SpotActor_Fig2.png

## 3. 关键机制与创新点

### Dual Energy Guidance 的形式化

标准 energy guidance 只在潜空间定义一步转移：

$$
p(z_{t-1} \mid z_t) = p_f(z_{t-1} \mid z_t^*) \cdot p_b(z_t^* \mid z_t),
$$

其中：

- $z_t^*$ 是 backward 阶段优化后的 latent codes；
- $p_b$ 是 backward update 过程；
- $p_f$ 是 forward sampling 过程。

作者把它改写为 latent 与 semantic 的联合形式：

$$
p(z_{t-1}, c_{t-1} \mid z_t, c_t) = p_f(z_{t-1}, c_{t-1} \mid z_t^*, c_t^*) \cdot p_b(z_t^*, c_t^* \mid z_t, c_t),
$$

其中：

- $c_t$ 是语义嵌入（semantic embeddings）；
- $p_b$ 为 layout-conditioned backward update；
- $p_f$ 为 consistent forward sampling。

该机制的核心是：

> "the latent space and the semantic space are inherently entangled together and ought to be regarded as a whole."

### Nuanced Layout Energy Function（细致布局能量函数）

作者先分析 cross-attention 的 **composition property**：spatial pixel 被 semantic token 激活的程度对应最终图像的构图，且这种对应随网络由 encoder 到 decoder 加深而增强；不同 semantic token 对空间像素的激活**并不均等**。基于此，他们把 2D Sigmoid 作为目标分布：

$$
\text{Sigmoid}(x, y) = \frac{1}{1 + e^{-s \cdot \left(1 - \frac{(x-\mu_1)^2}{\sigma_1} - \frac{(y-\mu_2)^2}{\sigma_2}\right)}},
$$

其中：

- $(x,y)$ 是空间坐标；
- $(\mu_1,\mu_2)$ 标记分布中心；
- $\sigma_1,\sigma_2$ 确立 margin（范围）；
- $s$ 是 shape control factor。

给定 box $b=(h^{\min},w^{\min},h^{\max},w^{\max})$ 与 subject token embedding $c^{\text{sub}}$，目标分布参数取为：

$$
\mu_1 = \frac{h^{\min}+h^{\max}}{2}, \qquad \mu_2 = \frac{w^{\min}+w^{\max}}{2},
$$

$$
\sigma_1 = \frac{(h^{\max}-h^{\min})^2}{4}, \qquad \sigma_2 = \frac{(w^{\max}-w^{\min})^2}{4}.
$$

能量函数为（对 subject token 在 U-Net decoder 各层平均后的 cross-attention map 计算）：

$$
e = \frac{1}{KHW}\sum_{k}\sum_{h}\sum_{w}\left(\tilde{A}^{\text{ca}}_{khw} - \text{Sigmoid}(h,w)\right)^2,
$$

其中：

- $A^{\text{ca}} \in \mathbb{R}^{K \times S}$ 是 subject token 的 cross-attention map，$K$ 为 attention head 数，$S=H\times W$ 为展平后的像素总数；
- $\tilde{A}^{\text{ca}} \in \mathbb{R}^{K \times H \times W}$ 是沿空间维做 min-max 归一化并 reshape 后的结果；
- $k,h,w$ 是维度索引。

反向更新同时作用于两个空间（式 (2) 与式 (9)）：

$$
z_t \leftarrow z_t - v\sigma_t \nabla_{z} e(z_t, t, c), \qquad c_t \leftarrow c_t - w\sigma_t \nabla_{c} e(z_t, t, c),
$$

其中：

- $v$ 是 latent energy guidance scale；
- $w$ 是 semantic energy guidance scale（作者注明 semantic update 本不必然需要 $\sigma_t$，但把它作为动态的 step-wise 权重加上）；
- $\sigma_t$ 是预定义常数序列。

该机制为何有效：

> "we employ spatial normalization to allow the model to allocate activation levels spontaneously"（避免直接约束 inter-token range level 造成画质下降）；参数分析中 Binary mask 与 Gaussian 目标分布都「exceed the given box boundary」，而 Sigmoid「perfectly mimics the inherent activation distribution and aligns the subject to the given box」。

机制分析图：Fig. 3（p.4），"Illustration of the attention analysis"，含 (a) IntraM、(b) InterM、(c) attention map 的 3D 分布、(d) sigmoid-like 近似分布——即上述能量函数设计的直接依据。

![SpotActor 注意力分析与 sigmoid-like 目标分布](figures/SpotActor_Fig3.png)

> 图注原文：Figure 3: Illustration of the attention analysis. (a) IntraM is the attention map normalized within each token, while (b) InterM is normalized across all the tokens. We further visualize (c) 3D distributions of attention maps and propose (d) sigmoid-like approximate distributions.
> 文档引用：../analysis/figures/SpotActor_Fig3.png

### Regional Interconnection Self-Attention (RISA)

把普通 self-attention 从单图内部扩展到 **inter-image** 层面，用 layout mask 限定互连区域。给定由 $b_i$ 变换得到的二值 layout mask $M_i \in \mathbb{R}^{H\times W}$ 与 latent 特征 $h_i$，先将 mask 展平并扩展为 $M_i^{\text{sa}} \in \mathbb{R}^{K\times S\times S}$，再拼接 keys/values：

$$
K^{\text{sa}+} = [K^{\text{sa}}_1 \oplus K^{\text{sa}}_2 \oplus \ldots \oplus K^{\text{sa}}_N],
$$

$$
V^{\text{sa}+} = [V^{\text{sa}}_1 \oplus V^{\text{sa}}_2 \oplus \ldots \oplus V^{\text{sa}}_N],
$$

$$
M_i^{\text{sa}+} = [M^{\text{sa}}_1 \ldots M^{\text{sa}}_{i-1} \oplus \mathbf{I} \oplus M^{\text{sa}}_{i+1} \oplus \ldots \oplus M^{\text{sa}}_N],
$$

$$
h_i^{\text{sa}} = \text{Softmax}\left(Q^{\text{sa}}_i \cdot K^{\text{sa}+\top}/\sqrt{d_k} + \log M_i^{\text{sa}+}\right) \cdot V^{\text{sa}+},
$$

其中：

- $N$ 为 batch 内图像数（即待生成的图像数）；
- $\mathbf{I}$ 是全 1 矩阵（使图像 $i$ 自身的注意力不受 mask 限制）；
- $\oplus$ 表示矩阵拼接；
- 上标 $+$ 表示被扩大的矩阵；
- $Q^{\text{sa}}_i,K^{\text{sa}}_i,V^{\text{sa}}_i$ 为第 $i$ 张图 self-attention 的 query/key/value。

> 原文注：$\log M_i^{\text{sa}+}$ 项用于「precisely control the interconnection region」。

### Semantic Fusion Cross-Attention (SFCA)

让每张图与 batch 内所有语义条件交互。给定 $M_i$、$h_i$ 与语义嵌入 $c_i$，先由 flatten、transpose、expand 得到 $M_i^{\text{ca}} \in \mathbb{R}^{K\times S\times 1}$，再取出 subject token 对应的 $K_i^{\text{sub}}$、$V_i^{\text{sub}}$ 并在 layout 区域内 cross-concatenate：

$$
K_i^{\text{ca}+} = [K^{\text{sub}}_1 \oplus \ldots K^{\text{sub}}_{i-1} \oplus K^{\text{ca}}_i \oplus K^{\text{sub}}_{i+1} \oplus \ldots \oplus K^{\text{sub}}_N],
$$

$$
V_i^{\text{ca}+} = [V^{\text{sub}}_1 \oplus \ldots V^{\text{sub}}_{i-1} \oplus V^{\text{ca}}_i \oplus V^{\text{sub}}_{i+1} \oplus \ldots \oplus V^{\text{sub}}_N],
$$

$$
M_i^{\text{ca}+} = [M^{\text{ca}}_1 \ldots M^{\text{ca}}_{i-1} \oplus \mathbf{I} \oplus M^{\text{ca}}_{i+1} \oplus \ldots \oplus M^{\text{ca}}_N],
$$

$$
h_i^{\text{ca}} = \text{Softmax}\left(Q^{\text{ca}}_i \cdot K^{\text{ca}+\top}_i / \sqrt{d_k} + \log M_i^{\text{ca}+}\right) \cdot V_i^{\text{ca}+}.
$$

符号含义同 RISA（$\oplus$ 拼接、$\mathbf{I}$ 全 1 矩阵、上标 $+$ 为扩大后的矩阵、$K^{\text{ca}}_i/V^{\text{ca}}_i$ 为第 $i$ 张图 cross-attention 的 key/value）。

该机制的核心是：

> "the role of semantic space ... has been continuously overlooked in consistent generation works. Hence, we design SFCA to enable each image to interact with all the semantic conditions within the batch."

## 4. 训练目标

**SpotActor 是 training-free 方法，不训练任何新参数**（不训练 subject embedding、adapter 或扩散模型参数），因此严格来说**没有新增的训练目标**。论文中出现的损失/能量项都不是训练监督信号，而是**推理期**的优化目标：

- 论文中回顾的式 (1) 是 DDPM 的标准采样式 $z_{t-1} = \frac{1}{\sqrt{\beta_t}}\left(z_t + \beta_t \nabla_{z_t}\log p(z_t)\right) + \sqrt{\beta_t}\,\epsilon$，属于预训练扩散模型的采样过程，不是本文的训练损失。
- 式 (2) 与式 (9) 的梯度更新（$\nabla_z e$、$\nabla_c e$）是**推理期能量引导**，作用于 latent codes 与 semantic embeddings，而非网络权重。
- 式 (8) 的 layout energy $e$ 是推理期的优化目标函数。

**推理期设置（附录 Implement Details）**：

- 实现于 StableDiffusionXL (SDXL)，单张 NVIDIA A800 80GB；
- 全部图像生成 30 步去噪，inference guidance scale = 5.0；
- **layout-conditioned backward update 只作用于前 3 步**，**consistent forward sampling 作用于前 20 步**；
- backward update 时收集所有空间维为 1024 的 decoder 层的 attention map 计算能量；
- sigmoid-like 函数的 shape control factor $s = 10$；latent energy guidance scale $v = 300$；semantic energy guidance scale $w = 0.9$；
- 收敛判据：每个 subject 的能量低于 $k_{\text{thres}} \times e_{\text{start}}$，其中 $k_{\text{thres}} = 60\%$，$e_{\text{start}}$ 为初始能量值；
- forward sampling 时 RISA 与 SFCA 施加于所有 decoder attention 层。

**评测设置**：在 ActorBench 上，每组 prompt-box pair 换随机种子生成 5 组图像，所有指标取平均。

**已知局限（作者自述）**：方法本质是 score-matching，能力高度依赖 base model 的训练分布，存在 OOD 问题——当给定 box 过小或贴近边缘、或 box 本身不合理时布局对齐变差。

## 5. 可引用原句（供 blockquote）

- "we pioneer a novel task, Layout-to-Consistent-Image (L2CI) generation, which produces consistent and compositional images in accordance with the given layout conditions and text prompts."
- "we consider the semantic and latent space as a whole dual space and present a new formalization of dual energy guidance, which defines an update trajectory in the dual space."
- "no existing work embarks on addressing both challenges simultaneously. Besides, existing works in either task pay limited attention to the role of the semantic space in T2I models."
- "optimizing solely in the latent space restricts the search range as the other half, the semantic space, is continuously neglected."
- "the activation of spatial pixels by semantic tokens corresponds to the composition of the final image ... the correspondence intensifies with increasing network depth from encoder to decoder layers."
- "different semantic tokens do not activate the spatial pixels equally, but exhibit different levels. These varying degrees of semantic-spatial interactions have a nuanced impact on the final image quality."
- "we employ spatial normalization to allow the model to allocate activation levels spontaneously."
- "our method is essentially a score-matching process which finds the optimal sample aligned best to the given layout. Thus, the capacity of our method is highly reliant on the trained distribution of the base model, which results in out-of-distribution (OOD) issues."

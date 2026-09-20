# FreeFuse

> 来源等级：FULL_TEXT
> 一句话定位：FreeFuse 提出免训练的多主体 LoRA 融合框架，用 FreeFuseAttn 在早期去噪步提取主体掩码，再以 token 级路由与注意力偏置实现空间上互斥的 LoRA 激活。
> 生成时间：2026-09-20 00:17:35

---

## 1. 论文概要与作者意图

FreeFuse 关注的是 **multi-subject text-to-image generation via training-free fusion of multiple subject LoRAs**。论文全称 *FreeFuse: Multi-Subject LoRA Fusion via Adaptive Token-Level Routing at Test Time*，作者为 Yaoli Liu、Yao-Xiang Ding（通讯作者）、Kun Zhou（浙江大学 CAD&CG 国家重点实验室），发表于 **Transactions on Machine Learning Research (09/2026)**，OpenReview id 为 `Bvela8OAMc`。

作者认为，把多个 subject LoRA 直接叠加到预训练 T2I 模型上会带来性能退化，已有方法各有代价：

- **Feature conflicts / deterioration**：随着 LoRA 数量增长，LoRA 之间日益严重的特征冲突最终会破坏模型本身的能力；
- **Indiscriminate broadcasting of LoRA parameter updates (Δθ)**：作者把特征冲突的根因归结为 LoRA 参数更新被无差别地广播到整张图，而非单纯的生成伪影；
- **Retraining / 额外可训练参数**：Mix-of-Show、Orthogonal Adaptation、LoRACLR 等需要重训 LoRA 并手工指定空间约束，刚性的空间约束严重损害构图灵活性；
- **External segmentation models / 手工空间约束**：OMG、Concept Weaver、FlipConcept 依赖辅助分割模型与噪声混合，在区分视觉相似主体（如两个男人）时容易失效；
- **Sparse activations, hole artifacts, unstable localization**：依赖 text-image latent association 的免训练方法（CLoRA、MC2、LoRAShop）用 cross-attention 推概念掩码，存在激活稀疏、空洞伪影与定位不稳，导致掩码不完整或语义歧义。

因此作者写这篇论文的核心意图是：

> 在不需要重训 LoRA、不引入外部分割器或辅助网络、也不需要用户提供空间约束的前提下，把加性的 LoRA 残差在空间上路由到其语义区域，从而抑制跨区域的直接 LoRA 干扰，同时保留基座模型的全局上下文推理能力。

作者自述的四点贡献为：(1) 给出机制分析，证明把加性 LoRA 残差空间约束到目标区域是缓解多主体特征冲突的有效手段；(2) 提出 FreeFuseAttn，融合 semantic-driven cross-attention 与 cohesion-driven token similarity 来缓解 latent 空间分割的 "hole" 伪影；(3) 一个完全免训练、无需辅助网络的多主体生成框架，可与 ControlNet、IP-Adapter、Redux、Style LoRA 等即插即用；(4) 在身份保持与构图保真上取得新 SOTA。

## 2. 方法框架

FreeFuse 的整体框架是一个**两阶段的免训练推理流程**：先利用基座模型内在的分割能力预测主体掩码，再用 token 级 Router 与 Attention Bias 强制 LoRA 局部激活。

1. **Phase 1 — Subject Region Prediction（FreeFuseAttn 掩码提取）**
   - 输入：无 LoRA 激活（all adapters disabled）的初始 latent 与 prompt embeds。
   - 输出：每个主体/概念的空间相似度图，经后处理得到二值掩码 $M$。
   - 功能：在早期去噪步（第 5 步，step index 4，共 28 步）的最后一个 Double Stream Block（`transformer_blocks.18`）处，从 cross-attention 的空间相似度出发，用 TopK anchor + latent 相似度传播重建稠密主体形状，再经形态学开/闭运算与迭代争议消解（Iterative Contention Resolution）得到互斥掩码。
2. **Phase 2 — Router Controlled and Bias Guided Generation**
   - 输入：Phase 1 得到的掩码 $M$、各主体的 LoRA adapter 集合 $A_s$、prompt embeds。
   - 输出：最终生成图像。
   - 功能：从与 Phase 1 相同的初始 latent 重新开始，在**每一个去噪步**施加被路由的 LoRA 残差。Router 按 token 位置把每个空间 token 分配给一个语义主体组，只在该 token 落在对应区域 $M_s$ 内时施加该组的 adapter，从而在竞争主体组之间强制互斥；同时由掩码构造 spatial attention bias 矩阵，鼓励 image token 只关注其对应主体 prompt，抑制 concept bleeding。
   - 关键约束：**路由只门控加性的 LoRA 残差，冻结的基座模型注意力路径保持激活**，以保留全局场景构图。

数据流关系：Phase 1 的相似度图 →（TopK anchor + 相似度传播 + 后处理）→ 掩码 $M$ → 同时供给 Phase 2 的 Router（决定每个 token 用哪组 adapter）与 Attention Bias（调制 cross-attention 权重）。

框架图：Fig. 2（p.4），"The FreeFuse Pipeline. In Phase 1, we employ FreeFuseAttn to extract robust subject masks, which are subsequently processed into a spatial Router and Attention Bias. In Phase 2, the Router enforces subject LoRA exclusivity per token to suppress direct cross-region LoRA interference, while the Bias mechanism actively reduces concept bleeding by ensuring precise semantic-spatial alignment."

![FreeFuse 方法框架](figures/FreeFuse_Fig2.png)

> 图注原文：Fig. 2 The FreeFuse Pipeline. In Phase 1, we employ FreeFuseAttn to extract robust subject masks, which are subsequently processed into a spatial Router and Attention Bias. In Phase 2, the Router enforces subject LoRA exclusivity per token to suppress direct cross-region LoRA interference, while the Bias mechanism actively reduces concept bleeding by ensuring precise semantic-spatial alignment.
> 文档引用：../analysis/figures/FreeFuse_Fig2.png

实现范围（论文 Implementation scope）：对 FLUX.1-dev，路由施加于所有带 LoRA 的 transformer block——19 个 double-stream block（`transformer_blocks.0–18`）与 38 个 single-stream block（`single_transformer_blocks.0–37`）。double-stream block 中路由门控 image-token 的 LoRA 残差（`to_q`、`to_k`、`to_v`、`to_out` 及 image feed-forward 分支），subject text-token 残差则隔离在 `add_q_proj`、`add_k_proj`、`add_v_proj`、`to_add_out`、`ff_context`；single-stream block 中路由 image-token 残差（`to_q`、`to_k`、`to_v`、`proj_mlp`、`proj_out`）。

## 3. 关键机制与创新点

### Token-Level LoRA Residual Routing

作者把特征冲突的根因定位为 LoRA 参数更新被无差别广播。设 $S$ 个语义主体组 $\{G_1,\dots,G_S\}$ 对应 latent 空间的区域 $\{R_1,\dots,R_S\}$，每组 $G_s$ 可关联一个或多个 LoRA adapter $A_s$（该组级形式同时支持"一主体一 LoRA"与"一个主体由多个 adapter 共同描述"，如身份 LoRA + 属性/风格 LoRA）。对空间 token $p$，令 $x_p$ 为送入 LoRA 增强线性层的输入隐表示，$h_p$ 为不含 LoRA 残差的基座输出，$h'_p$ 为加入所选 LoRA 残差后的路由输出，则：

$$
h'_p = h_p + \sum_{s=1}^{S} \sum_{a \in A_s} I(p \in R_s)\,\Delta\theta_a(x_p)
$$

其中：

- $I(\cdot)$ 为指示函数；
- $\Delta\theta_a(x_p)$ 表示 adapter $a$ 产生的加性残差；
- 该式保证区域 $R_k$ 处的 LoRA 特征更新只由分配给组 $G_k$ 的 adapter 决定，竞争组的 LoRA 被抑制。

作者进一步讨论：跨区域信息仍可能通过 self-attention 的全局聚合间接传播。对 self-attention 层，令 $Q = XW_Q$、$K = XW_K$、$V = XW_V$，注意力矩阵 $A = \mathrm{Softmax}(QK^\top/\sqrt{d})$，其中 $A_{p,q}$ 是 query token $p$ 到 value token $q$ 的注意力权重：

$$
\mathrm{Attn}(Q,K,V)_p = \sum_{q} A_{p,q} V_q
$$

作者明确**不主张**基座注意力路径能阻断所有跨区域信息传播——这种全局上下文交换对场景一致性是有益的；FreeFuse 阻断的是 subject-specific LoRA 残差向无关区域的**直接注入**。对于经由基座注意力的间接传播，作者依赖 DiT 模型中被广泛观察到的空间局部性：深层语义层呈现强对角占优，即对 $p \in R_k$：

$$
\sum_{q \in R_k} A_{p,q} \gg \sum_{q \notin R_k} A_{p,q}, \quad \forall p \in R_k
$$

作者还用 LoRA 扰动幅度的测量来佐证该策略：在 FLUX double-stream block 上，归一化的有效 LoRA 权重扰动在 mid/deep block 上比 early block 大 **1.80×**，推理期在对应激活 prompt 下的 LoRA 残差在 mid/deep block 上比 early block 大 **2.67×**（Fig. 4）。因此尽管 early block 聚合更广的上下文，那里被混合的 LoRA 残差在经验上很小，更强的 LoRA 效应出现在注意力局部性已强得多的中深层语义 block。

该机制的核心是：

> 对加性 LoRA 残差路径施加空间掩码，从而在保留基座模型全局上下文传播的同时抑制跨区域的直接 LoRA 干扰。

### FreeFuseAttn：语义驱动交叉注意力 + 凝聚驱动的 token 相似度

FreeFuseAttn 用于在 Flow Matching 模型内部定位主体，无需外部监督。设 $Q \in \mathbb{R}^{N \times d}$ 为空间 query 特征，$K_c \in \mathbb{R}^{L_c \times d}$ 为概念 $c$ 的 token 对应 key 特征（$L_c$ 为 token 数）；公式给出单注意力头的形式，实际对所有头取平均。与标准 cross-attention 在文本维度归一化不同，作者沿**空间维度 $N$** 做 Softmax，得到每个概念 token 的空间概率分布：

$$
A_c = \mathrm{Softmax}_{\text{spatial}}\left(\frac{Q K_c^\top}{\sqrt{d}}\right)
$$

其中 $A_c \in \mathbb{R}^{N \times L_c}$，元素 $A^{(p,l)}_c$ 表示第 $p$ 个 image token 对第 $l$ 个概念 token 的贡献，且满足 $\sum_{p=1}^{N} A^{(p,l)}_c = 1$。为得到整个概念的聚合激活图 $S_c \in \mathbb{R}^{N}$，对所有属于概念 $c$ 的 token 的空间响应取平均：

$$
S_c = \frac{1}{L_c} \sum_{l=1}^{L_c} A_c[:, l]
$$

为区分不同主体区域并抑制歧义，计算判别性得分 $\hat{S}_c$，通过惩罚竞争概念 $j \neq c$ 的激活来增强信号：

$$
\hat{S}_c = M \cdot S_c - \sum_{j \neq c} S_j
$$

其中 $M$ 为概念总数。随后取 anchor 集合 $P_c = \mathrm{TopK}(\hat{S}_c, k)$，指向最具代表性的空间 token，取 $k = \max(1, \lfloor 0.1N \rfloor)$（即每个概念取前 10% 的空间 image token）；对 $1024 \times 1024$ 的 FLUX 图像 $N = (1024/16)^2 = 4096$，故 $k = 409$ 个 anchor。最后，为生成空间凝聚的掩码 $M_c$ 并缓解原始 cross-attention 的稀疏性，通过 latent 相似度从 anchor 传播语义信息。设 $Z \in \mathbb{R}^{N \times d}$ 为所选 FLUX block 的 image-token 特征矩阵，$Z_p \in \mathbb{R}^d$ 为 anchor 位置 $p$ 的特征向量：

$$
M_c = \sigma\left(\frac{1}{\tau} \frac{Z Z^\top}{|P_c|} \sum_{p \in P_c} Z_p\right)
$$

其中：

- $Z Z^\top_p \in \mathbb{R}^{N}$ 是 image token 上的稠密相似度图；
- $\sigma(\cdot)$ 表示空间归一化函数（如 min-max scaling）；
- 温度 $\tau = 4000$；
- 结果 $M_c \in \mathbb{R}^N$ 被 reshape 为 latent 网格 $H/16 \times W/16$，从而由稀疏 anchor 线索重建稠密主体形状。

时空位置的选择：作者通过 Precision@K 的 step/block 扫描（Fig. 6）确定在**第 5 步（step index 4，共 28 步）的最后一个 Double Stream Block（`transformer_blocks.18`）** 提取掩码，该格点取得最佳平均 precision 0.704。作者观察到早期时间步空间结构尚未成形、与高斯噪声难以区分，后期 latent 又被高频纹理生成主导而与语义信号脱耦，中间去噪阶段是布局已建立但尚未固化的关键窗口。

该机制为何有效：

> FreeFuseAttn 融合语义驱动的 cross-attention 与凝聚驱动的 token 相似度，在 latent 空间重建连续的主体形状，从而缓解 'hole' 伪影；与 Cross-Attention、ConceptAttn 或 SP-Attn 相比，在多物体生成场景中给出更完整、更连续的主体掩码。

### Attention Bias 与后处理（争议消解）

Phase 2 中，作者直接用掩码 $M$ 构造 spatial attention bias 矩阵，调制注意力机制，鼓励 image token 只关注其对应的主体 prompt，抑制与无关主体描述的交互；Router 则通过只把 adapter 集合 $A_s$ 施加到区域 $M_s$ 内的 token 来强制空间局部性。与依赖昂贵 test-time optimization 抑制 concept bleeding 的做法不同，FreeFuse 直接复用 $M$。

后处理分两阶段。形态学细化：设 $K$ 为 $2\times 2$ 结构元，先开运算（去除孤立噪声像素）再闭运算（填补内部空洞）：

$$
M_{opened} = M_{raw} \circ K = (M_{raw} \ominus K) \oplus K
$$

$$
M_{clean} = M_{opened} \bullet K = (M_{opened} \oplus K) \ominus K
$$

其中 $\oplus$ 与 $\ominus$ 分别表示膨胀（Dilation）与腐蚀（Erosion）。

迭代争议消解（Router）：在前景区域内多个 subject LoRA 可能争夺同一空间 token，作者提出 Iterative Routing 算法把每个 token $p$ 分配给唯一主体 $c \in \{1,\dots,C\}$，默认迭代 $T = 15$ 步。以 FreeFuseAttn 的相似度分数初始化路由 logits $L^{(0)}_{p,c}$，每步更新由四项组成：(1) 线性归一化，把 logits 线性归一到 $[0,1]$ 以避免 Softmax 对弱信号的指数压制；(2) 动量平均，以 $\mu = 0.2$ 维护运行平均 $\bar{P}^{(t)}$ 稳定轨迹；(3) 空间凝聚（Gravity），基于软分布 $\bar{P}^{(t)}$ 计算每个主体的动态质心 $(\bar{x}_c, \bar{y}_c)$，按到质心的欧氏距离平方施加惩罚：

$$
E_{gravity}(p, c) = \lambda_g \cdot \|u_p - (\bar{x}_c, \bar{y}_c)\|^2
$$

其中 $u_p$ 是 token $p$ 的空间坐标，取值范围 $-1$ 到 $1$；(4) 局部邻域投票，聚合 $3\times 3$ 邻域 $\mathcal{N}(p)$ 的投票以鼓励局部平滑：

$$
V_{spatial}(p, c) = \lambda_s \sum_{q \in \mathcal{N}(p)} \bar{P}^{(t)}_{q,c}
$$

最终更新规则为：

$$
L^{(t+1)}_{p,c} = L^{(t)}_{p,c} + V_{spatial}(p, c) - E_{gravity}(p, c)
$$

经 $T$ 次迭代后，通过 $\arg\max_c L^{(T)}_{p,c}$ 得到最终主体掩码。超参数取 $\lambda_g = 2\times 10^{-5}$、$\lambda_s = 2\times 10^{-5}$（Tab. 4）。

风格 LoRA 的处理：当用户显式把 style LoRA 用于整图风格化（而非修改单个局部身份/属性）时，按同一组级规则处理——把它纳入所有应接受该风格的 foreground adapter 集合，即 $a_{style} \in A_s$ 对所有相关 $s$ 成立；当背景也应共享该风格时，把 style adapter 也分配给背景或全画布区域。

## 4. 训练目标

**FreeFuse 是 training-free 方法，无新增训练目标。** 论文不训练新的主体 embedding、adapter 或扩散模型参数，也不对 LoRA 做任何重训；文中没有给出任何损失函数。其全部机制都在推理期完成：Phase 1 用基座模型自身内在的分割能力提取掩码，Phase 2 用 Router 与 Attention Bias 在推理期门控已训练好的 LoRA 残差。

训练/推理策略与超参数（Tab. 4）：

- 掩码提取步数 $K = 5$（总去噪步数 $T_{denoise} = 28$）；
- FreeFuseAttn block $b = 18$；温度 $\tau = 4000$；TopK anchor 比例 $\rho = 10\%$，anchor 数 $k_{anchor} = \max(1, \lfloor \rho N \rfloor)$；
- 形态学核 $K$ 为 $2\times 2$ 全 1 矩阵；
- Router 迭代次数 $T_{route} = 15$，动量 $\mu = 0.2$，gravity 权重 $\lambda_g = 2\times 10^{-5}$，spatial voting 权重 $\lambda_s = 2\times 10^{-5}$。

阶段划分与各自目的：

- **Phase 1**：禁用全部 adapter，从 block 18 在前 $K=5$ 步提取掩码，目的是获得高保真的主体空间区域；
- **Phase 2**：从**相同的初始 latent** 重新开始，在每一个去噪步施加被路由的 LoRA 残差，目的是在保持基座全局构图的前提下强制 LoRA 局部激活并抑制 concept bleeding。

与预训练目标的关系：FreeFuse 完全沿用基座模型（FLUX.1-dev / SDXL）的预训练流匹配/扩散推理过程，不修改其目标函数。**推理期约束与训练目标的区分**：Router 的 token 级门控（式 1）、Attention Bias、形态学后处理与 Iterative Contention Resolution（式 8–14）都是**推理期的空间约束**，不是训练监督信号；它们不产生梯度，也不更新任何参数。作者强调路由只门控加性 LoRA 残差，冻结的基座注意力保持激活。

## 5. 可引用原句（供 blockquote）

- "We identify the root cause of feature conflict not as a generation artifact, but as the indiscriminate broadcasting of LoRA parameter updates (∆θ). We demonstrate that routing additive LoRA residuals to their intended semantic regions effectively suppresses direct cross-region interference."
- "We do not claim that the base attention path prevents all cross-region information propagation; this global context exchange is useful for scene coherence. Instead, FreeFuse blocks the direct injection of subject-specific LoRA residuals into unrelated regions."
- "Importantly, routing gates only the additive LoRA residuals; the frozen base-model attention remains active, preserving global scene composition."
- "Distinct from these approaches, FreeFuse is positioned as a fully training-free and auxiliary-free LoRA fusion framework."
- "A salient advantage of FreeFuse lies in its seamless modularity. By eschewing external segmentors and invasive weight updates, our framework preserves the original manifold of the base model."

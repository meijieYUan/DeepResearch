# AnyMS

> 来源等级：FULL_TEXT
> 一句话定位：AnyMS 提出免训练的自底向上双层注意力解耦框架，分离文本、主体图像与布局三类条件，在布局引导多主体定制中平衡文本对齐、身份保持与布局控制。
> 生成时间：2026-09-20 00:17:29

---

## 1. 论文概要与作者意图

**论文全称**：AnyMS: Bottom-up Attention Decoupling for Layout-guided and Training-free Multi-subject Customization
**通用缩写**：AnyMS
**作者**：Binhe Yu, Zhen Wang, Kexin Li, Yuqian Yuan, WenQiao Zhang, Long Chen, Juncheng Li, Jun Xiao, Yueting Zhuang（浙江大学、HKUST、浙江省烟草专卖局）
**发表信息**：arXiv:2512.23537v2 [cs.CV]，2026 年 1 月 2 日（v2）。会议/期刊信息 [材料未提供]。

AnyMS 关注的是 **layout-guided multi-subject customization（布局引导的多主体定制）**，即在给定文本提示、多个主体参考图像及其对应 bounding box 的条件下，合成一张同时满足三项要求的连贯图像：文本对齐、主体身份保持、布局控制。作者把这三项称为需要平衡的 three critical objectives。

作者按"布局引导的实现方式"把现有方法归为三类，并指出各自的不足：

- **Latent Injection（隐空间注入）**：以 MuDI 为代表，把分割后的主体按 bounding box 拼成初始 latent noise 注入空间与外观先验。作者指出 "such latent-level constraint often undermines text alignment (e.g., object relations), resulting in incoherent generation"。
- **Attention Rectifying（注意力矫正）**：以 Cones2、Mix-of-Show 为代表，用 bounding box 调整每个主体的 cross-attention map，增强目标区域、抑制无关区域。作者指出这类方法 "often requires encoding each subject as a special token concatenated with the text prompt and controlled via text cross-attention"，会造成视觉条件与文本条件冲突，导致 "imprecise identity preservation"。
- **Adapter Tuning（适配器微调）**：以 MS-Diffusion 为代表，引入可训练 adapter 联合编码视觉特征、文本 embedding 与布局约束。作者指出其 "rely on carefully curated layout-labeled multi-subject data with additional module tuning, which significantly increases computational cost and limits their generalization capability to unseen subjects or combinations"。

作者由此总结现有布局引导方法的两个主要局限：

- **1) Difficulty in balancing the trade-off among text alignment, subject identity preservation, and layout control**，尤其是随着主体数量增加时；
- **2) Reliance on additional training for subject learning or adapter tuning**，带来强数据依赖与可观的计算开销。

因此，作者写这篇论文的核心意图是：

> we propose AnyMS, a novel training-free layout-guided multi-subject customization framework ... AnyMS performs a bottom-up dual-level attention decoupling to balance their integration alongside the general denoising process of diffusion generation: 1) Global decoupling: separating cross-attention between text (i.e., text cross-attention) and subject images (i.e., image cross-attention) to mitigate global conflicts between textual and visual conditions, thereby ensuring text alignment. 2) Local decoupling: further disentangling image cross-attention using layout constraints, where each region only attends to its corresponding subject, avoiding interference among multiple subjects, thus guaranteeing both subject identity preservation and layout control.

三项贡献（原文摘要）：1) 提出 AnyMS 这一免训练框架与自底向上双层注意力解耦机制；2) 引入预训练 image adapter 高效提取主体特征，免去额外微调或主体学习；3) 在多个基准上做大量实验，达到 state-of-the-art 并支持更复杂组合与更多主体。

## 2. 方法框架

AnyMS 是一个 **training-free（免训练）** 的布局引导多主体定制框架，其整体定位是：在扩散模型通用去噪过程之上，对三类输入条件——textual（文本提示）、visual（主体图像）、layout（bounding box）——做**自底向上的双层注意力解耦（bottom-up dual-level attention decoupling）**。生成从随机初始化的 latent $z_T \sim \mathcal{N}(0, I)$ 开始，逐步去噪为 $z_0$，最后解码为目标图像 $I_G$。双层解耦被施加到 U-Net 的**所有 cross-attention 层**以及推理时的**每一个去噪时间步**。

框架由以下关键模块组成：

1. **Global Decoupling（全局解耦）**
   - 输入：latent features $Z$、文本提示 $P$、主体图像特征。
   - 输出：文本 cross-attention 输出 $Z_{text}$ 与图像 cross-attention 输出 $Z_{image}$ 之和，即 $Z_{out} = Z_{text} + Z_{image}$。
   - 功能：把文本条件与主体图像条件拆到**两条独立的 cross-attention 流**中分别处理，避免主体 token 在共享 cross-attention 中与周边文本 token 纠缠（identity distortion、background leakage、unintended attribute transfer），也避免主体 token 与布局条件竞争而破坏位置控制。
   - 关键操作：$Z_{text} = \text{CA}_{text}(Z, P)$ 走文本 cross-attention；$Z_{image}$ 走下述图像 cross-attention。

2. **Local Decoupling（局部解耦）**
   - 输入：latent features $Z$、主体图像–布局对 $\{(I_j, B_j)\}_{j=1}^{n}$。
   - 输出：布局感知的图像 cross-attention 输出 $Z_{image}$。
   - 功能：用 bounding box 限制 latent 区域与其对应主体特征之间的交互，使每个空间区域**只**注意它被指定的那个主体，从而避免主体间干扰（identity confusion、attribute mixing）。
   - 关键操作：包含两步——训练无关的主体特征提取，以及 attention cropping and merging（裁剪–合并）。

3. **Training-free Subject Feature Extraction（免训练主体特征提取）**
   - 输入：主体 $S_j$ 的参考图像 $I_j$。
   - 输出：与扩散模型对齐的主体 key/value 特征 $K_j = W'_k c_j$、$V_j = W'_v c_j$。
   - 功能：使用预训练 image adapter（实现中用 IP-Adapter）直接编码主体图像特征 $c_j$，无需微调扩散模型或学习新的主体 embedding。

**数据流关系**：文本提示 $P$ 进入文本 cross-attention 得到 $Z_{text}$；主体参考图像经预训练 adapter 编码为 $c_j$ 并投影为 $K_j, V_j$；latent 特征 $Z$ 投影出全局 query $Q$，按 bounding box $B_j = [h_s, h_e] \times [w_s, w_e]$ 裁出子区域 $Q_j$，在子区域内完成图像 cross-attention 得到 $Z_j$，再合并回原位得到 $Z_{image}$；最后 $Z_{text}$ 与 $Z_{image}$ 相加作为 cross-attention block 的输出 $Z_{out}$，送入后续去噪步骤，最终 $z_0$ 经 decoder 得到 $I_G$。

框架图：Fig. 3（p.4），"The Overview of Pipeline"。

![AnyMS 方法框架](figures/AnyMS_Fig3.png)

> 图注原文：Figure 3. The Overview of Pipeline. AnyMS applies a dual-level attention decoupling strategy alongside the general denoising process of the diffusion model. (a) The global decoupling separates cross-attention between text and subject images. (b) The local decoupling further disentangles image cross-attention based on layout constraints. The final z0 is then decoded back to target image IG.
> 文档引用：../analysis/figures/AnyMS_Fig3.png

## 3. 关键机制与创新点

### 3.1 预备：交叉注意力与标准重建损失

论文先回顾 Stable Diffusion 的交叉注意力形式。给定 latent features $Z$，cross-attention 的输出为：

$$
Z_{out} = \text{CA}_{text}(Z, P) = \text{Softmax}\left(\frac{QK^T}{\sqrt{d}}\right)V
$$

其中：

- $Q = W_q Z$ 是从 latent features 得到的 query；
- $K = W_k c_t$、$V = W_v c_t$ 是从文本特征 $c_t$ 投影得到的 key 和 value；
- $W_q, W_k, W_v$ 是相应的预训练投影矩阵；
- $d$ 是缩放因子（原文以 $\sqrt{d}$ 出现在分母）。

### 3.2 全局解耦（Global Decoupling）

作者指出，把学习到的主体 token 与文本提示拼接后共同经过文本 cross-attention，会带来两个问题：一是文本条件与视觉条件冲突（主体 token 在去噪中与周边文本 token 纠缠，导致 identity distortion、background leakage、unintended attribute transfer）；二是当提示中描述空间关系（如 "standing in front of"）时，共享的 cross-attention 会迫使主体 token 与布局条件竞争，削弱精确位置控制。

为此 AnyMS 把文本提示与主体图像交给**分离的 cross-attention 流**处理，每个 cross-attention block 的输出改写为：

$$
Z_{out} = Z_{text} + Z_{image}
$$

其中：

- $Z_{text} = \text{CA}_{text}(Z,P)$ 由文本 cross-attention 得到（即式 $Z_{out} = \text{CA}_{text}(Z,P) = \text{Softmax}(QK^T/\sqrt{d})V$）；
- $Z_{image}$ 由图像 cross-attention 得到（见 3.3）。

该机制的核心是：

> where text prompt and subject images are processed by separate cross-attention streams. In this way, textual semantics and visual identity are disentangled at the global level, enabling the model to preserve subjects faithfully while maintaining accurate text alignment.

### 3.3 局部解耦（Local Decoupling）

一般图像 cross-attention 中，整个 latent features 会同时注意所有主体图像，容易造成主体间冲突。AnyMS 用 bounding box 限制交互范围，把图像 cross-attention 形式化为：

$$
Z_{image} = \text{CA}_{image}(Z, \{(I_j, B_j)\}_{j=1}^{n})
$$

其中：

- $I_j$ 是主体 $S_j$ 的参考图像；
- $B_j$ 是指定该主体目标位置的 bounding box；
- $n$ 是主体数量。

**1) 免训练主体特征提取**：给定参考图像 $I_j$，adapter 编码出与扩散模型对齐的图像特征 $c_j$，再投影为主体专属的 key/value：

$$
K_j = W'_k c_j, \quad V_j = W'_v c_j
$$

其中：

- $c_j$ 是 adapter 对参考图像 $I_j$ 编码得到的图像特征；
- $W'_k$、$W'_v$ 是 adapter 的预训练投影矩阵；
- $K_j$、$V_j$ 是主体 $S_j$ 的 key 与 value 特征。

**2) Attention Cropping and Merging**：先用从 latent features 投影出的全局 query 特征 $Q \in \mathbb{R}^{H \times W}$ 初始化 $Z_{image}$。对每个主体 $S_j$，其 bounding box 为 $B_j = [h_s, h_e] \times [w_s, w_e]$，裁出对应子区域 $Q_j = Q[h_s:h_e, w_s:w_e]$，在该区域内做 cross-attention 注入主体特征：

$$
Z_j = \text{Softmax}\left(\frac{Q_j K_j^T}{\sqrt{d}}\right)V_j
$$

随后把局部输出合并回原位置：

$$
Z_{image}[h_s:h_e, w_s:w_e] = Z_j
$$

其中：

- $Q_j$ 是从全局 query $Q$ 按 bounding box $B_j$ 裁出的子区域 query；
- $K_j, V_j$ 是主体 $S_j$ 的 key/value 特征；
- $Z_j$ 是主体 $S_j$ 在其指定区域内的图像 cross-attention 输出；
- $[h_s:h_e, w_s:w_e]$ 是 bounding box 在 latent 上的行、列索引范围。

对于**重叠区域**，作者通过强制语义优先级顺序（"e.g., attribute > object, and foreground > background"）来消解主体–主体冲突，以保证布局一致与身份保持。最终 $Z_{image}$ 捕获局部主体保真度与结构连贯性，与 $Z_{text}$ 结合后实现全局文本语义与主体感知布局之间的平衡。

该机制的核心是：

> the bounding boxes are used to restrict the interaction between latent regions and their corresponding subject features, ensuring that each spatial area only attends to its designated subject.

### 3.4 消融中的两种退化形式（原文公式）

为验证局部解耦的必要性，作者给出两个消融设置对应的公式。

**设置 1：去掉 crop-and-merge**，直接用整个 query $Q$ 计算所有主体的图像注意力 $Z_j$，再用基于 $B_j$ 的 mask $M_j$ 做布局感知注意力并相加：

$$
Z_j = \text{Softmax}\left(\frac{QK_j^T}{\sqrt{d}}\right)V_j, \qquad Z_{image} = \sum_{j=1}^{n}(Z_j \odot M_j)
$$

其中：

- $M_j$ 是依据 bounding box $B_j$ 得到的 mask；
- $\odot$ 表示逐元素相乘；
- 求和范围 $j = 1$ 到 $n$，即对所有主体求和。

**设置 2：去掉整个局部解耦**，不加任何布局引导，直接把每个 $S_j$ 的结果相加：

$$
Z_{image} = \sum_{j=1}^{n} Z_j
$$

其中：

- $Z_j$ 是主体 $S_j$ 的图像 cross-attention 输出；
- 求和范围 $j = 1$ 到 $n$。

作者论证该机制有效的方式：

> Crop-and-merge plays a crucial role in decoupling features and layout control as the number of subjects increases.

> With layout guidance, the local decoupling operation further confines subjects to their designated regions, enabling a stable balance between identity preservation and layout control, while scaling robustly to larger numbers of subjects.

## 4. 训练目标

**AnyMS 是 training-free 方法**：它不训练新的主体 embedding、adapter 或扩散模型参数，而是直接复用预训练 image adapter（实现中为 IP-Adapter）提取主体特征。因此严格来说，**AnyMS 本身没有新增的训练目标**，也不存在分阶段训练策略。论文中出现的损失函数只有对 Stable Diffusion 预训练目标（标准噪声重建损失）的回顾：

$$
L_{rec} = \mathbb{E}_{z,\epsilon \sim \mathcal{N}(0,1),t,c_t}\left\|\epsilon - \epsilon_\theta(z_t, t, c_t)\right\|_2^2
$$

其中：

- $z_t = \sqrt{\bar{\alpha}_t} z_0 + \sqrt{1 - \bar{\alpha}_t}\epsilon$ 是时间步 $t$ 的加噪 latent；
- $\epsilon \sim \mathcal{N}(0,1)$ 是加入的高斯噪声；
- $\epsilon_\theta(\cdot)$ 是去噪网络；
- $t$ 是时间步，$c_t = \tau_\theta(P)$ 是文本条件 embedding；
- $\bar{\alpha}_t$ 是噪声调度器提供的系数。

**推理期约束与训练目标的区分**：AnyMS 的全部机制（global decoupling、local decoupling、crop-and-merge、重叠区域的语义优先级消解）都发生在**推理阶段**，作用于 U-Net 的所有 cross-attention 层与每一个去噪时间步，属于推理期的注意力约束/改写，**不是**训练监督信号。论文没有给出任何针对这些机制的训练损失。

**推理开销**：作者称该双层解耦只带来 "only marginal inference overhead"。Table 1 中 AnyMS 的推理时间为 19s、显存 11GB，训练列标记为 ✗（即无需训练）。

**实现细节**：基座模型为 Stable Diffusion XL (SDXL)，image adapter 为 IP-Adapter，生成分辨率 1024×1024。

**实验结论（供参考）**：Table 1 中 AnyMS 在 AP50 (35.65)、mIOU (49.75)、CLIP-I (74.46)、DreamSim (59.62)、CLIP-T (35.82) 上取得报告中的最优值，DINO (54.64) 略低于 MS-Diffusion (57.33)；推理时间 19s、显存 11GB、无需训练。

## 5. 可引用原句（供 blockquote）

- "In this paper, we present AnyMS, a novel training-free framework for layout-guided multi-subject customization. AnyMS leverages three input conditions: text prompt, subject images, and layout constraints, and introduces a bottom-up dual-level attention decoupling mechanism to harmonize their integration during generation."
- "Global decoupling separates cross-attention between textual and visual conditions to ensure text alignment. Local decoupling confines each subject's attention to its designated area, which prevents subject conflicts and thus guarantees identity preservation and layout control."
- "existing layout-guided approaches still suffer from two major limitations: 1) Difficulty in balancing the trade-off among text alignment, subject identity preservation, and layout control, especially as the number of subjects increases. 2) Reliance on additional training for subject learning or adapter tuning, leading to strong data dependency and substantial computational overhead."
- "Moreover, AnyMS employs pre-trained image adapters to extract subject-specific features aligned with the diffusion model, removing the need for subject learning or adapter tuning."
- "While AnyMS achieves strong performance without additional training, its effectiveness still depends on the capacity of the underlying pre-trained diffusion model and image adapters."

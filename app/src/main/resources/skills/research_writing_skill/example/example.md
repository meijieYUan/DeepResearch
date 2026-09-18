> **本文档为写作风格范例，用于参考粒度与写法，不是真实调研的交付物。**
>
> - 四篇论文均为真实论文，但其中的问题陈述、机制归纳与对比结论是为了演示"写多详细、怎么对比"而组织的，**不得当作事实来源引用**；
> - 参考文献章节只演示格式，条目信息按 `[信息缺失]` 约定标注——范例不为占位而编造出处；
> - **撰写时结构与标题一律以 `template/template.md` 为准。**
>
> **关于本文件里的图片**：它们（`papper*.png`）是**范例自带的素材**，与 `example.md` 同目录，因此这里的相对路径是 `papperN_xxx.png` 这种短形式。这只用于演示"图放在哪、图注怎么写"。
> **正式调研文档不得引用本目录或 `template/` 下的任何文件**——正式文档的图必须来自 `analyst-agent` 用 `extractPaperFigures` 从对应论文 PDF 截取的图，路径形如 `../analysis/figures/{论文短名}_Fig{N}.png`，且要逐字复制 analysis 文件里「> 文档引用：」那一行。两者互不通用。
> 与参考文献同理，图注里的 `Fig. {n}` 是**占位符**——范例不为排版效果编造具体图号；正式文档必须填真实图号与页码，让读者能回查 PDF。
>
> 阅读时重点看四件事：**逐篇定位有多短**（2-4 句）、**图怎么放**（图片在定位小节里，图注在图片下方）、**对比分析怎么横向组织**（每维度一张表，而不是逐对比较）、**四维细节去了哪里**（指向 `analysis/` 的相对链接，而不是抄进文档）。

[TOC]

# 多主体下的布局控制调研

> **调研背景**: 多主体图像生成要求在保持每个参考主体身份的同时，让各主体出现在用户指定的位置上。本调研梳理该方向近年的代表性方法，重点比较它们控制布局的手段与代价。
> **调研范围**: 2022-2025 年，入选论文 4 篇（FULL_TEXT 4 篇，仅摘要来源 0 篇）

**关于详细内容**：每篇论文的四维信息、公式逐字抄录与来源等级保存在
`investigation/{课题方向}/analysis/{论文短名}.md`。本文档只做**归纳与对比**，
不复述这些文件的正文——需要细节时请直接查阅对应文件。

---

## 论文清单

| # | 论文 | 年份/会议 | 来源等级 | 一句话定位 | 详细分析 |
| - | ---- | --------- | -------- | ---------- | -------- |
| 1 | MS-Diffusion | [信息缺失] | FULL_TEXT | 用 bbox mask 约束 image cross-attention 的作用范围，是多主体布局控制中"显式空间约束"路线的代表 | [analysis/MS-Diffusion.md](../analysis/MS-Diffusion.md) |
| 2 | LCP-Diffusion | [信息缺失] | FULL_TEXT | 把布局控制拆成训练期注入 grounding token 与推理期 attention loss 纠偏两段，换取更强的布局精度 | [analysis/LCP-Diffusion.md](../analysis/LCP-Diffusion.md) |
| 3 | MUSE | [信息缺失] | FULL_TEXT | 主张 layout 应作为文本语义的一部分，用一次拼接式 attention 取代两路 attention 相加，消除 control collision | [analysis/MUSE.md](../analysis/MUSE.md) |
| 4 | AnyMS | [信息缺失] | FULL_TEXT | 不新增训练，仅在推理期对 attention 做全局与局部两级解耦，是本课题中 training-free 路线的代表 | [analysis/AnyMS.md](../analysis/AnyMS.md) |

---

# 1. 逐篇定位

## 1.1 MS-Diffusion

针对多主体个性化中普遍出现的 subject neglect、subject overcontrol 与身份混合，MS-Diffusion 把 bbox 约束引入 image cross-attention，让每个主体的视觉特征主要作用于指定区域。它是本课题中"**以显式空间约束换取身份保真**"这一路线的代表，代价是推理时需要用户提供 bbox，布局自由度受限于用户的输入质量。

![MS-Diffusion 方法框架](papper1_framework.png)

> 图注原文：Fig. {n} Overview of MS-Diffusion.

## 1.2 LCP-Diffusion

LCP-Diffusion 要同时解决两个问题：主体细节（姿态、视角、纹理）保真不足，以及缺少精确的布局可控性。它的做法是把布局控制拆成两段——训练阶段用布局感知的 grounding token 把 bbox 信息注入模型，推理阶段再用位置与尺度约束对 latent 做修正。**与 MS-Diffusion 相比，它对布局的干预延伸到了推理阶段**，因此布局精度更高，但推理开销与调参空间也更大。

![LCP-Diffusion 方法框架](papper2_framework.png)

> 图注原文：Fig. {n} Overview of LCP-Diffusion.

## 1.3 MUSE

MUSE 指出，当 layout control、text control 与 subject synthesis 同时注入模型时会出现 **control collision**：layout 分支与 text 分支各自生成的 attention map 相加后互相干扰。它的解法是把 layout 从独立控制信号转化为文本语义空间的扩展，用拼接式 attention 在一次计算中协同两类条件，并配以"先学布局、再学主体合成"的两阶段训练。它是本课题中**对"多控制源如何共存"给出结构性回答**的一篇。

![MUSE 方法框架](papper3_framework.png)

> 图注原文：Fig. {n} Overview of MUSE.

这一篇的核心机制不容易从框架图看出来，所以再补一张关键机制图——**每篇至多两张**，这是上限：

![MUSE 中 DCA 的拼接式 attention](papper3_component.png)

> 图注原文：Fig. {n} Illustration of the concatenated attention in DCA.

## 1.4 AnyMS

AnyMS 关注 text alignment、subject identity preservation 与 layout control 三者难以兼顾的问题，把根源归结为不同条件之间的 attention 竞争。它不训练任何新模块，而是在推理期对 attention 做两级解耦：全局上分离文本分支与图像分支，局部上让每个 bbox 区域只关注对应的参考主体。**它是本课题中唯一不做训练的路线**，因此部署成本最低，但也无法像其余三篇那样通过训练把布局先验固化进模型。

![AnyMS 双层 attention 解耦](papper4_framework.png)

> 图注原文：Fig. {n} Overview of AnyMS.

---

# 2. 对比分析

## 2.1 方法框架对比

| 对比维度 | MS-Diffusion | LCP-Diffusion | MUSE | AnyMS |
| -------- | ------------ | ------------- | ---- | ----- |
| 模块划分 | Grounding Resampler + Multi-subject Cross Attention | D-SCVR（动静特征互补）+ Dual Layout Control | Explicit Layout Semantic Expansion + 两阶段训练 | Bottom-up 双层 Attention 解耦 |
| 数据流 | 主体特征与 bbox 汇聚为 grounding token，再以 mask 约束 attention | 特征先经动静双路精炼，再以 layout-aware token 注入 | entity + bbox 编码为 layout semantic token，与文本 token 拼接后统一计算 | 先全局分离文本/图像分支，再按 bbox 裁剪区域分别 attention 后合并 |
| 空间约束来源 | 用户给定 bbox | 用户给定 bbox（训练期与推理期各用一次） | 用户给定 entity + bbox，转为语义 token | 用户给定 bbox，仅用于区域裁剪 |
| 是否引入新增参数 | 是 | 是 | 是 | **否** |

差别集中在一点：**三者把布局当"额外的控制信号"，MUSE 把它当"文本语义的扩展"，AnyMS 则完全不动模型只重整 attention**。这一分野决定了 §2.3 训练成本上的全部差异。

## 2.2 关键机制对比

| 论文 | 核心机制 | 解决什么 | 代价 |
| ---- | -------- | -------- | ---- |
| MS-Diffusion | 用 bbox mask 限制每个 subject attention 的空间作用范围 | 主体之间互相污染、主体缺失 | 布局完全依赖用户 bbox |
| LCP-Diffusion | 训练期注入 layout-aware grounding token；推理期以 attention loss 修正 latent | 布局精度不足、主体细节不够保真 | 推理需要额外迭代与损失权重调参 |
| MUSE | 将 layout token 与文本 token 的 key/value **拼接后**统一做一次 attention | text 与 layout 两路 attention 相加造成的 control collision | 改动 attention 结构，需两阶段训练 |
| AnyMS | 全局解耦文本/图像分支 + 局部按 bbox 裁剪做 region-subject 路由 | 多条件竞争，且要求 training-free | 无训练先验，效果受预训练模型能力上限约束 |

**共同的收敛点**：四篇最终都回到"**限制某个主体的视觉条件能影响哪些空间位置**"这一条主线上。区别只在于限制发生在训练期（把约束固化进参数）、推理期（每次生成时动态施加），还是只重整 attention 而不改参数——这恰好对应三种成本档位：**训练成本 → 推理成本 → 零额外成本**。

> 机制细节与逐字抄录的公式见各篇 analysis 文件的"关键机制与创新点"一节，本文档不重复。

## 2.3 训练目标对比

| 论文 | 是否训练 | 训练什么 | 阶段划分 | 与预训练目标的关系 |
| ---- | -------- | -------- | -------- | ------------------ |
| MS-Diffusion | 是 | Grounding Resampler 与 image condition 注入模块；SDXL 主体权重冻结 | 单阶段 | 沿用扩散去噪目标 |
| LCP-Diffusion | 是 | adapter 内的 static / grounding / dynamic attention 与 resampler；U-Net 冻结 | 单阶段（布局纠偏在推理期） | 沿用去噪目标；位置与尺度损失**不属于**训练监督 |
| MUSE | 是 | CCA 与 subject synthesis DCA | **两阶段**：先布局，后主体合成 | 沿用去噪目标，靠阶段拆分避免目标冲突 |
| AnyMS | **否** | 无新增训练参数 | — | 直接复用预训练模型与 image adapter |

**两处容易误读的地方**：LCP-Diffusion 的 attention loss 是**推理期**约束，不是训练目标；AnyMS 论文中出现的噪声重建损失只是对预训练目标的回顾，**它本身没有新增训练目标**。汇总表据此填写。

## 2.4 对比汇总表

| 论文 | 作者主要想解决的问题 | 方法框架 | 关键机制 | 是否 Training-free |
| ---- | -------------------- | -------- | -------- | ------------------ |
| MS-Diffusion | 多主体个性化中主体缺失、特征混合、布局不准 | Grounding Resampler + Multi-subject Cross Attention | 用 bbox mask 限制每个 subject attention 的空间作用范围 | 否 |
| LCP-Diffusion | 个性化主体细节不够保真，且缺少布局可控性 | D-SCVR + Dual Layout Control | 动静特征互补；训练期 layout-aware token，推理期 attention loss 纠偏 | 否，推理使用较灵活 |
| MUSE | layout control 与 text control 发生 control collision | Layout Semantic Expansion + 两阶段训练 | 用拼接式 attention 将 layout token 并入 text attention；先学布局再学主体 | 否 |
| AnyMS | text、subject image、layout 多条件 attention 冲突 | Bottom-up 双层 Attention 解耦 | 全局分离文本/图像分支；局部按 bbox 做 region-subject 路由 | 是 |

---

# 3. 结论与开放问题

**共识**：四篇都认同一件事——多主体生成的核心困难是**多个条件在同一空间位置上竞争**，因此都必须回答"谁在什么位置说话"。差异只在于把这条约束放在训练期、推理期，还是只重整 attention。

**分歧**：MUSE 明确反对把 layout 当作与 text 并列的独立控制源（认为相加必然碰撞）；而 MS-Diffusion、LCP-Diffusion、AnyMS 都在不同层面保留了 layout 的独立通路（mask、损失、区域裁剪）。**这是本课题最实质的路线分歧**，目前没有第三方实验把两者放在同一基准下比较。

**尚未解决的问题**：

1. **布局来源**：四篇都依赖用户显式给出 bbox 或 entity+bbox，没有一篇解决"从文本自动推断多主体布局"。
2. **成本与精度的边界**：AnyMS 证明了不训练也能做，但尚未回答"training-free 的能力上限在哪里"。
3. **评测口径**：四篇各自使用不同的基准与指标，布局精度与身份保真度之间如何权衡缺乏统一量尺。

---

# 参考文献

> 本范例只演示格式；条目信息在实际产出中必须来自材料，缺失字段按 `[信息缺失]` 标注，**不得编造作者或会议名**。

1. [作者信息缺失]. MS-Diffusion: Multi-subject Zero-shot Image Personalization with Layout Guidance. [会议/期刊信息缺失], [年份缺失]. [链接缺失]
2. [作者信息缺失]. LCP-Diffusion: [标题信息缺失]. [会议/期刊信息缺失], [年份缺失]. [链接缺失]
3. [作者信息缺失]. MUSE: [标题信息缺失]. [会议/期刊信息缺失], [年份缺失]. [链接缺失]
4. [作者信息缺失]. AnyMS: [标题信息缺失]. [会议/期刊信息缺失], [年份缺失]. [链接缺失]

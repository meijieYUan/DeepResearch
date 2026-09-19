# RAG-Survey-Gao2023

> 来源等级：FULL_TEXT
> 一句话定位：RAG 领域的奠基性综述，提出 Naive/Advanced/Modular 三范式，并从检索、生成、增强三支柱与评估体系梳理 100+ 篇工作。
> 生成时间：2026-09-18 22:02:16

---

## 1. 论文概要与作者意图

论文全称 **Retrieval-Augmented Generation for Large Language Models: A Survey**，通用缩写 **RAG Survey**。作者为 Yunfan Gao、Yun Xiong、Xinyu Gao、Kangxiang Jia、Jinliu Pan、Yuxi Bi、Yi Dai、Jiawei Sun、Meng Wang、Haofen Wang（同济大学 / 复旦大学），预印本 arXiv:2312.10997v5 [cs.CL]，2024 年 3 月 27 日（v5）。开源资源：https://github.com/Tongji-KGLLM/RAG-Survey 。

本综述关注的是 **Retrieval-Augmented Generation (RAG) 在大语言模型时代的系统化梳理**：LLM 虽能力强大，却存在幻觉、知识过时、推理过程不透明不可追溯等问题，RAG 通过引入外部知识库来缓解。

作者指出的现有方法/研究不足：

- **Hallucination（幻觉）**：模型在处理超出训练数据或需要时效性信息的查询时，会产出事实错误的内容；
- **Outdated knowledge（知识过时）**：参数化知识固化在预训练语料中，无法反映最新进展；
- **Non-transparent, untraceable reasoning processes（推理不透明、不可追溯）**：生成过程是黑箱，用户无法核验答案出处；
- **Naive RAG 的三类缺陷**：**Retrieval Challenges**（检索精度与召回不足，选到不相关或错位的 chunk、漏掉关键信息）、**Generation Difficulties**（生成内容不被检索上下文支持、输出无关/有毒/有偏）、**Augmentation Hurdles**（检索信息与任务难以整合，输出割裂不连贯、多源冗余重复、难以判断段落重要性与保持风格一致；复杂问题单次检索不足；生成模型可能过度依赖增强信息而只是复述检索内容）；
- **领域缺乏系统性综述**：RAG 领域快速膨胀，但缺少一份能厘清其整体发展轨迹的系统性综合，且现有研究偏重方法、缺少对"如何评估 RAG"的分析与总结。

作者的核心意图与主张：

> The burgeoning field of RAG has experienced swift growth, yet it has not been accompanied by a systematic synthesis that could clarify its broader trajectory. This survey endeavors to fill this gap by mapping out the RAG process and charting its evolution and anticipated future paths, with a focus on the integration of RAG within LLMs.

> This paper considers both technical paradigms and research methods, summarizing three main research paradigms from over 100 RAG studies, and analyzing key technologies in the core stages of "Retrieval," "Generation," and "Augmentation."

## 2. 方法框架

本综述不是提出单一模型，而是**把 RAG 研究组织为"三范式演进 + 三支柱技术"的分析框架**，并补充评估体系与未来方向。整体可拆为三个关键模块：

1. **三种 RAG 范式（Paradigms）**——按发展轨迹划分，是全文的组织主线：
   - 输入：100+ 篇 RAG 研究；
   - 输出：Naive RAG / Advanced RAG / Modular RAG 三阶段分类；
   - 功能：刻画 RAG 从"Retrieve-Read"固定链路，到 pre-/post-retrieval 优化，再到可替换、可重组的模块化架构的演进。
2. **三大核心支柱（Tripartite Foundation）**——按 RAG 流水线的功能阶段划分：
   - **Retrieval（检索）**：检索源（Retrieval Source，含非结构化/半结构化/结构化数据与 LLM 生成内容）、检索粒度（Retrieval Granularity，Token/Phrase/Sentence/Proposition/Chunk/Document，KG 上为 Entity/Triplet/Sub-Graph）、索引优化（Indexing Optimization：Chunking Strategy、Metadata Attachments、Structural Index）、查询优化（Query Optimization：Query Expansion、Query Transformation、Query Routing）、Embedding（Mix/hybrid Retrieval、Fine-tuning Embedding Model）、Adapter；
   - **Generation（生成）**：Context Curation（Reranking、Context Selection/Compression）与 LLM Fine-tuning（含 RL、蒸馏、retriever-generator 对齐）；
   - **Augmentation（增强）**：Iterative Retrieval、Recursive Retrieval、Adaptive Retrieval 三类超越"单次检索"的流程。
3. **评估体系（Task and Evaluation）**——评估目标（Retrieval Quality / Generation Quality）、评估维度（3 个 Quality Scores + 4 项 Required Abilities）、基准与工具（RGB、RECALL、CRUD；RAGAS、ARES、TruLens）。

数据流关系：三范式构成**纵向演进轴**（Naive → Advanced → Modular），三支柱构成**横向技术轴**（Retrieval → Generation → Augmentation），Augmentation 决定了检索与生成之间是"一次"还是"迭代/递归/自适应"的交互方式；评估体系则横跨两轴，对检索质量与生成质量分别度量。

框架图：Fig. 3（p.4），"Comparison between the three paradigms of RAG"。

![RAG 三种范式对比（Naive / Advanced / Modular）](figures/RAG-Survey-Gao2023_Fig3.png)

> 图注原文：Fig. 3. Comparison between the three paradigms of RAG. (Left) Naive RAG mainly consists of three parts: indexing, retrieval and generation. (Middle) Advanced RAG proposes multiple optimization strategies around pre-retrieval and post-retrieval, with a process similar to the Naive RAG, still following a chain-like structure. (Right) Modular RAG inherits and develops from the previous paradigm, showcasing greater flexibility overall. This is evident in the introduction of multiple specific functional modules and the replacement of existing modules. The overall process is not limited to sequential retrieval and generation; it includes methods such as iterative and adaptive retrieval.
> 文档引用：../analysis/figures/RAG-Survey-Gao2023_Fig3.png

## 3. 关键机制与创新点

本综述**不提出新的数学机制**，正文中未给出任何作者自创的公式（全文无编号公式）。因此本节按"概念机制"梳理，不抄录公式——这是材料本身的性质，不是遗漏。

### 三种 RAG 范式的定义与分工

- **Naive RAG**：最早的方法论，遵循 indexing → retrieval → generation 的传统流程，也被称为 "Retrieve-Read" 框架。检索阶段用与索引阶段相同的编码模型把 query 编码为向量，计算与 chunk 向量的相似度，取 top-K 作为 prompt 中的扩展上下文。
- **Advanced RAG**：针对 Naive RAG 的缺陷，在检索前后加入优化。**Pre-retrieval process** 优化索引结构（增强数据粒度、优化索引结构、添加 metadata、对齐优化、混合检索）与原始 query（query rewriting、query transformation、query expansion）；**Post-Retrieval Process** 做 rerank chunks 与 context compressing，把最相关内容重排到 prompt 的边缘位置（LlamaIndex、LangChain、HayStack 均已实现），并压缩上下文以缓解信息过载。
- **Modular RAG**：引入多个专门功能模块并可替换/重组。**New Modules** 包括 Search、RAG-Fusion、Memory、Routing、Predict、Task Adapter；**New Patterns** 包括 Rewrite-Retrieve-Read、Generate-Read、Recite-Read、HyDE、DSP、ITER-RETGEN、FLARE、Self-RAG 等，支持顺序处理与端到端联合训练。

### 三类增强流程（Augmentation Process）

这是全文对"检索—生成交互拓扑"的核心归纳，也是 Modular RAG 灵活性的来源：

- **Iterative Retrieval**：基于初始 query 与已生成的文本反复检索知识库，通过多轮迭代提供更全面的上下文参考，提升后续答案生成的鲁棒性；但可能受语义不连续与无关信息累积的影响。代表：ITER-RETGEN（"retrieval-enhanced generation" 与 "generation-enhanced retrieval" 协同）。
- **Recursive Retrieval**：基于前次检索结果迭代精炼搜索 query，通过反馈回路逐步收敛到最相关信息；常与 multi-hop retrieval 结合使用。代表：IRCoT（用 chain-of-thought 引导检索并用检索结果精炼 CoT）、ToC（构建 clarification tree 系统性优化 query 中的歧义部分）。
- **Adaptive Retrieval**：让 LLM 主动判断何时需要检索、何时停止。FLARE 通过监控生成过程的置信度（生成 token 的概率）来自动化检索时机——当概率低于某阈值时激活检索系统；Self-RAG 引入 "reflection tokens"（"retrieve" 与 "critic" 两类），让模型自主决定是否激活检索，并在检索时做 fragment-level beam search，用 critic 分数更新细分分数，且可在推理期调整这些权重。

### 评估维度框架

- **3 个 Quality Scores**：Context Relevance、Answer Faithfulness、Answer Relevance；
- **4 项 Required Abilities**：Noise Robustness、Negative Rejection、Information Integration、Counterfactual Robustness；
- 其中 context relevance 与 noise robustness 用于评估检索质量，其余四项用于评估生成质量。

该框架的核心主张是：

> Contemporary evaluation practices of RAG models emphasize three primary quality scores and four essential abilities, which collectively inform the evaluation of the two principal targets of the RAG model: retrieval and generation.

> These metrics, derived from related work, are traditional measures and do not yet represent a mature or standardized approach for quantifying RAG evaluation aspects.

## 4. 训练目标

**本论文是综述（survey），不提出新模型、不定义新的训练目标，因此没有可抄录的损失函数。** 论文正文中未出现任何作者自创的公式或损失函数表达；全文无编号公式。

论文中涉及的"训练"内容均是对**已有工作训练策略的分类转述**，而非本文提出的目标：

- **Retriever 微调**：在领域语料与预训练语料差异大时（医疗、法律等专有术语领域）微调 embedding 模型；以 LLM 结果作为监督信号（LSR, LM-supervised Retriever），如 PROMPTAGATOR 用 LLM 作 few-shot query generator 构建任务专用 retriever；LLM-Embedder 用硬标签 + LLM 软奖励双监督信号；REPLUG 用 retriever 与 LM 计算检索文档的概率分布并**通过计算 KL 散度做监督训练**；RA-DIT **用 KL 散度对齐 Retriever 与 Generator 的 scoring function**；受 RLHF 启发，用 LM-based feedback 通过强化学习强化 retriever。
- **Adapter 方案**：不微调主模型，改为插入外部 adapter 对齐，如 UPRISE（轻量 prompt retriever + prompt pool）、AAR（通用 adapter）、PRCA（可插拔 reward-driven contextual adapter）、BGM（冻结 retriever 与 LLM，训练中间的 bridge Seq2Seq 模型）、PKG（通过 directive fine-tuning 把知识注入白盒模型）。
- **LLM 微调**：按场景与数据特性做定向微调；调整模型输入输出格式与风格；SANTA 采用**三阶段训练**——初始阶段聚焦 retriever，用对比学习精炼 query 与 document embedding；通过强化学习让 LLM 输出对齐人类或 retriever 偏好；在无法访问强模型时蒸馏 GPT-4 等更强模型；LLM 微调可与 retriever 微调协同对齐偏好。
- **联合/协同微调**：Modular RAG 支持跨组件的端到端训练，也可与 fine-tuning、reinforcement learning 结合（微调 retriever 以改善检索、微调 generator 以个性化输出、或 collaborative fine-tuning）。

**训练目标与推理期约束的区分**：论文明确指出若干机制属于**推理期**行为而非训练监督信号——Self-RAG 的 critic 分数权重"with the flexibility to adjust these weights during inference"；FLARE 的检索触发阈值作用于生成过程的 token 概率；reranking、context compression、LLMLingua 的 token 剔除均发生在推理期的后检索阶段；检索粒度"Choosing the appropriate retrieval granularity during inference can be a simple and effective strategy"。这些都不构成训练损失。

**RAG 与 Fine-tuning 的关系**（论文的核心主张之一）：二者并非互斥，可以互补，在某些场景下组合使用可达最优性能，且 RAG+FT 的优化过程可能需要多次迭代。论文引用 [28] 的评估指出，在多种知识密集型任务上 RAG 一致优于无监督微调，且 LLM 难以通过无监督微调学到新的事实性知识。

## 5. 可引用原句（供 blockquote）

- "Retrieval-Augmented Generation (RAG) has emerged as a promising solution by incorporating knowledge from external databases. This enhances the accuracy and credibility of the generation, particularly for knowledge-intensive tasks, and allows for continuous knowledge updates and integration of domain-specific information."
- "This comprehensive review paper offers a detailed examination of the progression of RAG paradigms, encompassing the Naive RAG, the Advanced RAG, and the Modular RAG."
- "The development of Advanced RAG and Modular RAG is a response to these specific shortcomings in Naive RAG."
- "RAG can be likened to providing a model with a tailored textbook for information retrieval, ideal for precise information retrieval tasks. In contrast, FT is comparable to a student internalizing knowledge over time, suitable for scenarios requiring replication of specific structures, styles, or formats."
- "RAG and FT are not mutually exclusive and can complement each other, enhancing a model's capabilities at different levels."
- "The presence of noise or contradictory information during retrieval can detrimentally affect RAG's output quality. This situation is figuratively referred to as 'Misinformation can be worse than no information at all'."
- "On one hand, providing LLMs with a large amount of context at once will significantly impact its inference speed, while chunked retrieval and on-demand input can significantly improve operational efficiency. On the other hand, RAG-based generation can quickly locate the original references for LLMs to help users verify the generated answers."
- "While scaling laws are established for LLMs, their applicability to RAG remains uncertain."

## 6. 材料说明

- 本文为综述，**全文无编号公式、无作者自创损失函数**，故 §3 与 §4 不包含公式抄录；§4 已明确说明本文不提出训练目标。这不是材料缺失，而是文献类型的固有属性。
- 论文摘要与正文均未给出作者自创的数学定义；正文中出现的 KL 散度、对比学习损失等均为对**被引工作**的文字转述，原文未给出其数学表达，故未抄录为公式。
- 图仅截取 Fig. 3（三范式对比，p.4）作为方法框架图；Fig. 1（技术树，p.2）、Fig. 2（RAG 流程实例，p.3）、Fig. 4（RAG vs FT vs Prompt Engineering，p.7）、Fig. 5（三类增强流程，p.11）、Fig. 6（RAG 生态总结，p.16）在索引中可见，但按"至多 2 张"的限制未截取，需要时可按图号回查 `papers/RAG-Survey-Gao2023.pdf`。
- 发表信息：arXiv:2312.10997v5，2024-03-27；论文中未标注正式会议/期刊接收信息 [材料未提供]。

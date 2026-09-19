# MemGPT

> 来源等级：FULL_TEXT
> 一句话定位：借鉴操作系统虚拟内存分页，用函数调用让 LLM 自主在分层记忆间搬运数据，突破固定上下文窗口。
> 生成时间：2026-09-19 19:39:15

---

## 1. 论文概要与作者意图

MemGPT（全称 *MemGPT: Towards LLMs as Operating Systems*，缩写 MemGPT / MemoryGPT）由 UC Berkeley 的 Charles Packer、Sarah Wooders、Kevin Lin、Vivian Fang、Shishir G. Patil、Ion Stoica、Joseph E. Gonzalez 完成。arXiv 编号 arXiv:2310.08560v2（cs.AI），v2 日期 2024 年 2 月 12 日；论文正文未标注具体会议/期刊，[材料未提供] 正式发表会议信息。

论文关注的核心问题是 **在固定长度上下文窗口的 LLM 上提供"无限上下文"的假象（virtual context management）**，即如何让模型处理远超其输入容量的长文档与跨会话长对话。

作者指出的现有方法不足：

- **有限的固定长度上下文窗口（limited fixed-length context windows）**：LLM 只能支持几十轮来回消息，或在超出最大输入长度前推理一小段文档，严重限制其在长对话与长文档分析上的可用性；
- **直接扩展上下文长度的代价（quadratic increase in computational time and memory cost）**：由于 transformer 自注意力机制，直接延长上下文会带来计算时间与显存的平方级增长，使新长上下文架构设计成为紧迫难题；
- **长上下文模型未必善用长上下文（struggle to utilize additional context effectively）**：即便克服了上下文扩展的计算挑战，近期研究也表明长上下文模型难以有效利用额外上下文；且大模型训练资源昂贵、上下文扩展收益递减，因此需要替代性技术；
- **长上下文模型存在不均匀注意力分布（uneven attention distributions）**：模型更擅长回忆上下文窗口开头或结尾的信息，而中间 token 的信息容易被忽略。

因此作者的核心意图是：

> "In this paper, we study how to provide the illusion of an infinite context while continuing to use fixed-context models. Our approach borrows from the idea of virtual memory paging that was developed to enable applications to work on datasets that far exceed the available memory by paging data between main memory and disk."

> "The combined use of a memory-hierarchy, OS functions and event-based control flow allow MemGPT to handle unbounded context using LLMs that have finite context windows."

## 2. 方法框架

MemGPT 的整体框架是一个 **OS 启发的多级记忆架构（OS-inspired multi-level memory architecture）**：把一个固定上下文的 LLM processor 与分层记忆系统、以及让模型自己管理记忆的函数调用机制拼装起来，用"主上下文 ↔ 外部上下文"之间的分页来模拟虚拟内存。论文将记忆划分为两类：**main context**（类比主存/物理内存/RAM，即 LLM 的 prompt tokens）与 **external context**（类比磁盘存储，即 recall storage 与 archival storage）。任何数据都必须被显式移入 main context 才能在该次推理中传给 LLM processor。

1. **Main context（prompt tokens）**
   - 输入：由 MemGPT 组装的三段连续 prompt tokens。
   - 组成：**System Instructions**（只读、静态，包含 MemGPT 控制流信息、各记忆层级用途说明、以及如何使用 MemGPT 函数的指令）、**Working Context**（固定大小的可读写非结构化文本块，只能通过 MemGPT 函数调用写入，用于存关键事实、偏好、用户与 agent persona 的重要信息）、**FIFO Queue**（滚动消息历史，含 agent 与用户消息、系统消息如 memory warning、以及函数调用的输入输出；队列第一个索引存放被逐出消息的递归摘要 recursive summary）。
   - 输出：作为 LLM processor 的输入。

2. **Queue Manager**
   - 输入：新到达的消息、prompt tokens、LLM 输出。
   - 功能：把新消息追加到 FIFO queue，拼接 prompt tokens 并触发 LLM 推理；把收到的消息与生成的输出都写入 recall storage（MemGPT 消息数据库）；当 recall storage 中的消息被函数调用检索时，把它们追加到队列尾部重新插入上下文窗口。
   - 溢出控制：当 prompt tokens 超过 **warning token count**（如上下文窗口的 70%）时，向队列插入系统消息，警告 LLM 即将发生队列逐出（**memory pressure** 警告），让 LLM 有机会用 MemGPT 函数把重要信息存入 working context 或 archival storage；当超过 **flush token count**（如 100%）时，queue manager 逐出一定数量消息（如上下文窗口的 50%），并用已有递归摘要与被逐出消息生成新的递归摘要。被逐出消息不再 in-context，但会无限期保存在 recall storage 中，可通过函数调用读回。

3. **Function Executor（处理 completion tokens）**
   - 输入：LLM processor 生成的输出字符串。
   - 功能：解析输出以确保正确性；若解析器验证函数参数合法则执行函数；把执行结果（包括运行时错误，例如在 main context 已满时仍试图写入）反馈给 processor。这个反馈回路让系统能从自身动作中学习并调整行为。
   - 记忆编辑与检索完全 **self-directed**：MemGPT 依据当前上下文自主更新与搜索自己的记忆，决定何时在上下文之间搬运条目。自编辑与检索的实现方式是在 system instructions 中写入显式指令，包含两部分：(1) 记忆层级及其各自用途的详细描述；(2) 函数 schema（含自然语言描述），供系统调用以访问或修改记忆。

4. **Control flow 与 Function Chaining**
   - 事件（events）触发 LLM 推理：事件是 MemGPT 的泛化输入，可以是用户消息、系统消息（如 main context 容量警告）、用户交互（如用户登录提醒、文档上传完成提醒），以及按固定日程运行的定时事件（使 MemGPT 能在无用户干预下"unprompted"运行）。
   - MemGPT 用 parser 把事件转成纯文本消息追加到 main context，最终作为 LLM processor 的输入。
   - 函数链式调用（function chaining）：函数可带一个特殊 flag，请求在函数执行完毕后立即把控制权交回 processor；若带该 flag，MemGPT 会把函数输出加入 main context 并继续执行 processor（而不暂停）。若不带该 flag（a yield），MemGPT 会等到下一个外部事件触发才再运行 LLM processor。函数链式调用使 MemGPT 能通过多步检索回答用户查询。

数据流总览：事件 → parser → 追加到 main context → LLM processor 生成 completion tokens → function executor 解析并执行函数 → 在 main context 与 external context（archival / recall storage）之间搬运数据 → 结果与错误反馈回 processor。awareness of context limits 是自编辑机制有效运作的关键，因此 MemGPT 会用 token 限制相关的警告提示 processor，且检索机制实现分页（pagination）以防止检索调用撑爆上下文窗口。

框架图：Fig. 3（p.3），"In MemGPT, a fixed-context LLM processor is augmented with a hierarchical memory system and functions that let it manage its own memory."

![MemGPT 方法框架](figures/MemGPT_Fig3.png)

> 图注原文：Figure 3. In MemGPT, a fixed-context LLM processor is augmented with a hierarchical memory system and functions that let it manage its own memory. The LLM's prompt tokens (inputs), or main context, consist of the system instructions, working context, and a FIFO queue. The LLM completion tokens (outputs) are interpreted as function calls by the function executor. MemGPT uses functions to move data between main context and external context (the archival and recall storage databases). The LLM can request immediate follow-up LLM inference to chain function calls together by generating a special keyword argument (request heartbeat=true) in its output; function chaining is what allows MemGPT to perform multi-step retrieval to answer user queries.
> 文档引用：../analysis/figures/MemGPT_Fig3.png

## 3. 关键机制与创新点

### Virtual Context Management（虚拟上下文管理）

MemGPT 的核心机制是把传统 OS 的虚拟内存分页搬到 LLM 上：把上下文窗口当作受限的记忆资源，用函数调用在"主上下文"与"外部上下文"之间分页，从而在固定上下文模型上制造更长上下文的假象。

该机制的三个构成要素（论文原文表述）：

> "The combined use of a memory-hierarchy, OS functions and event-based control flow allow MemGPT to handle unbounded context using LLMs that have finite context windows."

> "These capabilities allow LLMs to effective 'page' in and out information between context windows (analogous to 'main memory' in operating systems) and external storage, similar to hierarchical memory in traditional OSes."

### Memory Hierarchy（记忆层级）

- **Main context**：LLM prompt tokens，任何进入 main context 的内容都算 in-context，可被 LLM processor 在推理时访问；对应 OS 的 main memory / physical memory / RAM。
- **External context**：LLM 固定上下文窗口之外保存的任何信息；对应 disk memory / disk storage。包含两个数据库：
  - **recall storage**：MemGPT 的消息数据库，存消息历史，通过函数调用读回并追加到 FIFO queue 尾部；
  - **archival storage**：存储任意长度文本对象的读写数据库，通过函数调用读写。

论文给出的对应关系是：

> "In MemGPT, we treat context windows as a constrained memory resource, and design a memory hiearchy for LLMs analogous to memory tiers used in traditional OSes."

### Memory Pressure 与队列逐出 / 递归摘要

queue manager 通过 token 阈值触发两级动作，这是"分页"在对话场景中的具体实现：

- 当 prompt tokens 超过 **warning token count**（如上下文窗口的 70%）时，插入 memory pressure 系统警告，促使 LLM 主动把重要信息写入 working context 或 archival storage；
- 当超过 **flush token count**（如 100%）时，逐出一定比例消息（如 50%），并用已有递归摘要 + 被逐出消息生成**新的递归摘要**，该摘要存放在 FIFO queue 的第一个索引处。被逐出消息仍无限期保存在 recall storage，可被函数调用读回。

### Self-Directed Memory Editing 与 Function Chaining

- 记忆编辑与检索完全由 LLM 自主发起（self-directed），依据当前上下文决定何时搬运条目；实现手段是 system instructions 中的显式指令 + 函数 schema。
- **Function chaining** 通过在输出中生成特殊关键字参数 `request heartbeat=true` 实现：带此 flag 时，函数执行完立即把控制权交回 processor（函数输出加入 main context），从而在一次用户回合内串联多次函数调用；不带该 flag 则为 yield，等待下一个外部事件。论文明确指出 function chaining 是 MemGPT 完成多步检索的关键：

> "The LLM can request immediate follow-up LLM inference to chain function calls together by generating a special keyword argument (request heartbeat=true) in its output; function chaining is what allows MemGPT to perform multi-step retrieval to answer user queries."

### Pagination（分页检索）

检索机制按 token 约束实现分页，避免单次检索调用溢出上下文窗口。文档 QA 中 MemGPT 通过 `archival_storage.search("nobel physics")` 这类调用分页拉取检索结果（如 "Showing 10 of 124 results (page 1/13)"），并可翻页继续检索；nested KV 任务中则通过反复函数查询把 key-value 对读入 main context 完成多跳查找。

该机制为何有效（作者论证）：

> "By contrast, MemGPT is effectively able to make multiple calls to the past retriever by querying archival storage, allowing it to scale to larger effective context lengths. MemGPT actively retrieves documents from its archival storage (and can iteratively page through results), so the total number of documents available to MemGPT is no longer limited by the number of documents that fit within the LLM processor's context window."

> "Awareness of context limits is a key aspect in making the self-editing mechanism work effectively, to this end MemGPT prompts the processor with warnings regarding token limitations to guide its memory management decisions."

**说明**：本篇为系统/架构型论文，正文未给出需要逐字抄录的核心数学公式（不含损失函数或注意力公式推导）。因此本节不含块级公式，机制以文字与原文表述转录。文中出现的 `request heartbeat=true`、`archival_storage.search(...)` 等为函数调用与关键字参数的原文写法，非数学公式。

## 4. 训练目标

**MemGPT 是 training-free 的系统级方法，无新增训练目标。** 论文没有定义任何损失函数，也没有对底层 LLM 做任何微调、适配器训练或参数更新：其全部能力来自 (1) 分层记忆架构、(2) 系统提示中的显式指令与函数 schema、(3) 事件驱动的控制流与函数链式调用。底层模型（GPT-4、GPT-4 Turbo、GPT-3.5 Turbo）作为固定上下文 LLM processor 原样使用。

实验中的模型配置（Implementation details）：'GPT-4 Turbo' 指 gpt-4-1106-preview（上下文窗口 128,000），'GPT-4' 指 gpt-4-0613（上下文窗口 8,192），'GPT-3.5 Turbo' 指 gpt-3.5-turbo-1106（上下文窗口 16,385）；这些模型均未因 MemGPT 被训练。

**与预训练目标的关系**：论文未讨论预训练目标，MemGPT 完全复用已有 LLM 的 next-token 生成与 function calling 能力，不改变其训练目标。

**推理期约束与训练目标的区分**：MemGPT 中所有"约束"都发生在推理期，且不是训练监督信号：

- **token 阈值约束**：warning token count（如上下文窗口 70%）与 flush token count（如 100%）触发 memory pressure 警告与队列逐出，属于推理期上下文管理策略，不是损失；
- **分页约束**：检索机制实现 pagination 以防检索结果溢出上下文窗口，同样只是推理期工程约束；
- **运行时错误反馈**：如"在 main context 已满时试图写入"这类错误被反馈给 processor，属于推理期的自我纠错回路，而非训练信号。

**评测设置**（非训练目标，供区分参考）：对话侧使用 Multi-Session Chat (MSC) 数据集并新增 session 6，提出 deep memory retrieval (DMR) 与 conversation opener 两个任务；文档侧使用 retriever-reader document QA 任务（NaturalQuestions-Open，50 个问题，Wikipedia 2018 年末 dump）与作者新提出的 nested key-value retrieval 任务。评测用 ROUGE-L、LLM judge（GPT-4）与 SIM-1/SIM-3/SIM-H 相似度分数。MemGPT 默认存储使用 PostgreSQL 作 archival memory storage，通过 pgvector 扩展启用向量检索，HNSW 索引。

## 5. 可引用原句（供 blockquote）

- "To enable using context beyond limited context windows, we propose virtual context management, a technique drawing inspiration from hierarchical memory systems in traditional operating systems which provide the illusion of an extended virtual memory via paging between physical memory and disk."
- "In this paper, we study how to provide the illusion of an infinite context while continuing to use fixed-context models."
- "Using function calls, LLM agents can read and write to external data sources, modify their own context, and choose when to return responses to the user."
- "The combined use of a memory-hierarchy, OS functions and event-based control flow allow MemGPT to handle unbounded context using LLMs that have finite context windows."
- "Memory edits and retrieval are entirely self-directed: MemGPT autonomously updates and searches through its own memory based on the current context."
- "This feedback loop enables the system to learn from its actions and adjust its behavior accordingly."
- "Overall, MemGPT demonstrates that operating system techniques like hierarchical memory management and interrupts can unlock the potential of LLMs even when constrained by fixed context lengths."

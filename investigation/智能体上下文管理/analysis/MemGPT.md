# MemGPT

> 来源等级：FULL_TEXT
> 一句话定位：提出 OS 启发的虚拟上下文管理，用分层记忆与函数调用让固定窗口 LLM 自主换页，实现无限上下文。
> 生成时间：2026-09-19 14:38:26

---

## 1. 论文概要与作者意图

MemGPT 关注的是 **virtual context management（虚拟上下文管理）**，即如何在**不改变底层 LLM 固定上下文窗口**的前提下，让模型获得"无限上下文"的错觉。

- 论文英文全称：*MemGPT: Towards LLMs as Operating Systems*
- 通用缩写：MemGPT（MemoryGPT）
- 作者：Charles Packer, Sarah Wooders, Kevin Lin, Vivian Fang, Shishir G. Patil, Ion Stoica, Joseph E. Gonzalez（University of California, Berkeley）
- 年份与发表信息：arXiv:2310.08560v2 [cs.AI]，2024 年 2 月 12 日（预印本；正文未给出正式会议/期刊名，[材料未提供]）
- 开源地址（原文给出）：https://research.memgpt.ai

作者指出的现有方法不足：

- **Limited fixed-length context windows**：LLM 只能支持几十轮来回消息或对短文档的推理，超出最大输入长度即失效。
- **Context scaling 的二次开销**：直接扩展 transformer 上下文长度会因 self-attention 带来计算时间与内存的二次增长。
- **Long-context models struggle to utilize additional context effectively**：即使克服了上下文扩展的计算挑战，长上下文模型也难以有效利用额外上下文；Liu et al. (2023a) 发现大上下文模型中注意力分布不均（模型更善于回忆上下文开头或结尾的信息，而非中间部分）。

因此，作者写这篇论文的核心意图是：

> "To enable using context beyond limited context windows, we propose virtual context management, a technique drawing inspiration from hierarchical memory systems in traditional operating systems which provide the illusion of an extended virtual memory via paging between physical memory and disk."

> "In this paper, we study how to provide the illusion of an infinite context while continuing to use fixed-context models."

## 2. 方法框架

MemGPT 的整体框架是一个 **OS 启发的多层记忆系统（OS-inspired multi-level memory architecture）**，把 LLM 的上下文窗口当作受限的物理内存，通过函数调用在"主上下文"与"外部上下文"之间换页。原文将记忆划分为两类：**main context**（类比主存/物理内存/RAM，即 LLM prompt tokens）与 **external context**（类比磁盘存储，即任何位于固定上下文窗口之外的信息）。

1. **Main context（prompt tokens）**
   - 输入：由 MemGPT 拼接为单一字符串后送入 LLM processor。
   - 组成：三个连续区段——**system instructions**（只读/静态，含 MemGPT 控制流说明、各记忆层级用途、函数使用说明）、**working context**（固定大小的读写非结构化文本块，只能通过 MemGPT 函数调用写入，用于存关键事实、偏好与 persona 信息）、**FIFO Queue**（读写滚动消息历史，含 agent 与用户消息、系统消息如内存警告、函数调用输入输出；队列第一个索引存放被逐出消息的递归摘要）。
   - 输出：作为 LLM 推理的输入。

2. **Queue Manager**
   - 输入：新到达的消息、recall storage 中的消息。
   - 功能：把新消息追加到 FIFO 队列，拼接 prompt tokens 并触发 LLM 推理；把收到的消息与生成的输出都写入 recall storage（MemGPT 消息数据库）；当 recall storage 中的消息被函数调用检索时，把它们追加到队列尾部重新插入上下文窗口。
   - 上下文溢出控制：当 prompt tokens 超过 **warning token count**（如上下文窗口的 70%）时插入 memory pressure 系统警告，让 LLM 有机会用函数把重要信息存入 working context 或 archival storage；当超过 **flush token count**（如 100%）时 flush 队列，逐出特定数量消息（如上下文窗口的 50%），并用已有递归摘要 + 被逐出消息生成新的递归摘要。被逐出的消息不再 in-context，但永久存于 recall storage 且可经函数调用读取。

3. **Function executor（处理 completion tokens）**
   - 输入：LLM processor 生成的输出字符串。
   - 功能：解析输出以确保正确性，若 parser 验证函数参数合法则执行函数；执行结果（包括运行时错误，例如在主上下文已满时仍尝试写入）被反馈回 processor，形成反馈回路。
   - 数据流：函数调用是主上下文与外部上下文之间数据移动的唯一通道；archival storage（可存任意长度文本对象的读写数据库）与 recall storage 均**只能经函数读取/写入**（archival 的写入仅经函数，recall 的写入经 Queue Manager）。

4. **Control flow and function chaining**
   - 事件（events）触发 LLM 推理：用户消息、系统消息（如主上下文容量警告）、用户交互（如登录提醒、文档上传完成提醒）、定时事件（定时调度，使 MemGPT 可"unprompted"运行）。事件先经 parser 转为纯文本消息追加到主上下文。
   - **Function chaining**：函数可带一个特殊标志，请求该函数执行完后立即把控制权交回 processor；若该标志存在，MemGPT 把函数输出加入主上下文并继续运行 processor；若不存在（a yield），MemGPT 直到下一个外部事件触发才运行 processor。

框架图：Fig. 3（p.3），"In MemGPT, a fixed-context LLM processor is augmented with a hierarchical memory system and functions that let it manage its own memory."

![MemGPT 方法框架](figures/MemGPT_Fig3.png)

> 图注原文：Figure 3. In MemGPT, a fixed-context LLM processor is augmented with a hierarchical memory system and functions that let it manage its own memory. The LLM's prompt tokens (inputs), or main context, consist of the system instructions, working context, and a FIFO queue. The LLM completion tokens (outputs) are interpreted as function calls by the function executor. MemGPT uses functions to move data between main context and external context (the archival and recall storage databases). The LLM can request immediate follow-up LLM inference to chain function calls together by generating a special keyword argument (request heartbeat=true) in its output; function chaining is what allows MemGPT to perform multi-step retrieval to answer user queries.
> 文档引用：../analysis/figures/MemGPT_Fig3.png

## 3. 关键机制与创新点

### 虚拟上下文管理（Virtual Context Management）

MemGPT 把上下文窗口视为受限内存资源，仿照传统 OS 的虚拟内存分页机制，让 LLM 自己决定把什么放进上下文（类比物理内存）、把什么换出到外部存储（类比磁盘），并在需要时经 page fault 式检索取回。原文的表述是：

> "we allow the LLM to manage what is placed in its own context (analogous to physical memory) via an 'LLM OS', which we call MemGPT."

### 自导向记忆编辑与检索（Self-directed editing and retrieval）

MemGPT 的记忆编辑与检索完全是自导向的：MemGPT 依据当前上下文自主更新与搜索自身记忆，例如决定何时在上下文之间移动条目（如对话历史过长时）、修改主上下文以更好反映其当前目标与责任的演变理解。实现方式是在 system instructions 中给出显式指令，包含两部分：(1) 记忆层级及其各自用途的详细描述；(2) function schema（含自然语言描述），供系统调用以访问或修改记忆。

其有效性的作者论证：

> "Awareness of context limits is a key aspect in making the self-editing mechanism work effectively, to this end MemGPT prompts the processor with warnings regarding token limitations to guide its memory management decisions. Additionally, our memory retrieval mechanisms are designed to be cognizant of these token constraints and implement pagination to prevent retrieval calls from overflowing the context window."

### 队列逐出与递归摘要（Queue eviction with recursive summary）

原文未以编号公式给出该机制（该文为系统论文，正文不含数学公式），机制以文字与 token 阈值描述：

- 阈值 **warning token count**（例如上下文窗口的 70%）→ 插入 memory pressure 系统消息；
- 阈值 **flush token count**（例如上下文窗口的 100%）→ flush 队列，逐出约 50% 上下文窗口的消息，并用「已有递归摘要 + 被逐出消息」生成新的递归摘要；摘要置于 FIFO 队列的第一个索引。

> "Once the queue is flushed, the evicted messages are no longer in-context and immediately viewable to the LLM, however they are stored indefinitely in recall storage and readable via MemGPT function calls."

（说明：本论文正文与附录均未给出数学公式，故本节不含公式；以上机制均按原文文字转录。）

### 函数链式调用（Function chaining）

通过函数调用中的特殊标志（原文描述为请求立即把控制权交回 processor 的标志，图注中记为 `request heartbeat=true`）实现多步检索：MemGPT 可在把控制权交还用户前顺序执行多个函数调用，从而完成跨页结果遍历与跨文档信息汇总（multi-hop retrieval）。

> "function chaining is what allows MemGPT to perform multi-step retrieval to answer user queries."

## 4. 训练目标

**MemGPT 是无需训练新模型参数的系统级方法（training-free / inference-time system design）**，不引入任何新的训练损失或微调目标。

- 损失函数：论文正文、实验与附录均**未给出任何训练损失函数**（[材料未提供]）。MemGPT 不改动底层 LLM 的权重，全部能力来自 prompt 中的 system instructions、function schema 与函数调用控制流。
- 训练策略：无训练阶段、无冻结/可训练模块的划分。底层 LLM（GPT-4、GPT-4 Turbo、GPT-3.5 Turbo）以现成权重使用；实验中的"实现细节"只规定所用模型端点（`gpt-4-1106-preview`，128k；`gpt-4-0613`，8,192；`gpt-3.5-turbo-1106`，16,385）。
- 与预训练目标的关系：不修改、不延续任何预训练目标；MemGPT 复用的是 LLM 已有的 **function calling** 能力与指令跟随能力。
- 推理期约束与训练目标的区分：本论文中不存在训练目标，因此也不存在"把推理期约束误当训练目标"的风险。MemGPT 的推理期约束是**上下文窗口的 token 预算**，具体表现为：
  - **warning token count**（如窗口的 70%）触发 memory pressure 警告，引导 LLM 主动存盘；
  - **flush token count**（如窗口的 100%）触发队列 flush 与递归摘要；
  - 检索机制实现 **pagination**，防止检索调用本身撑爆上下文窗口。
  这些阈值与分页规则都是**推理期的运行策略/控制流约束**，不是可优化的损失项，也没有反向传播。

## 5. 可引用原句（供 blockquote）

- "To enable using context beyond limited context windows, we propose virtual context management, a technique drawing inspiration from hierarchical memory systems in traditional operating systems which provide the illusion of an extended virtual memory via paging between physical memory and disk."
- "In this paper, we study how to provide the illusion of an infinite context while continuing to use fixed-context models."
- "Using function calls, LLM agents can read and write to external data sources, modify their own context, and choose when to return responses to the user."
- "The combined use of a memory-hierarchy, OS functions and event-based control flow allow MemGPT to handle unbounded context using LLMs that have finite context windows."
- "Memory edits and retrieval are entirely self-directed: MemGPT autonomously updates and searches through its own memory based on the current context."
- "Overall, MemGPT demonstrates that operating system techniques like hierarchical memory management and interrupts can unlock the potential of LLMs even when constrained by fixed context lengths."

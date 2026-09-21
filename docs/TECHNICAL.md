# DeepResearchAgent —— 课题调研智能体 · 技术说明

> **本文是项目的技术说明**（架构、工具链、提示词工程与踩坑记录）。项目的简介与成果展示见 [README](../README.md)。


基于 Spring AI Alibaba 多智能体架构的**课题调研智能体**：以 research → write → review 闭环自动产出**可复核**的调研报告。同时支持对话、RAG 知识检索、待办管理、网页搜索、文件操作与邮件发送，内置 Human-In-The-Loop 高危操作审批机制、四层上下文压缩系统与模型/工具异常兜底。

## 架构概览

```
                        POST /api/chat/{threadId}
                              │
                              ▼
        ┌─────────────────────────────────────────────────┐
        │              main-agent (ReactAgent)            │
        │                                                 │
        │  Hooks · BEFORE_AGENT（按 getOrder 升序执行）    │
        │    ├─ SkillsAgentHook            技能规则注入    │
        │    └─ CompactHook (order=10000)  四层上下文压缩  │
        │  Hooks · MODEL                                  │
        │    ├─ HumanInTheLoopHook         高危操作审批    │
        │    ├─ ModelCallLimitHook         模型调用上限    │
        │    └─ ModelMessagePersistenceHook 回答持久化     │
        │                                                 │
        │  Interceptors                                   │
        │    ├─ PromptSubmitHook        (Model) 动态系统提示词 │
        │    ├─ PlanModeToolInterceptor (Model) 计划模式工具门控 │
        │    ├─ ModelCallGuardInterceptor (Model) 模型异常兜底 │
        │    ├─ LoopGuardToolInterceptor(Tool)  工具循环防护   │
        │    ├─ ToolRetryInterceptor    (Tool)  工具重试       │
        │    └─ ToolErrorInterceptor    (Tool)  错误降级       │
        └───────────────────┬─────────────────────────────┘
                            │
     ┌──────────────────────┼──────────────────────┐
     ▼                      ▼                      ▼
┌───────────┐    ┌──────────────────┐   ┌──────────────────┐
│ 子 Agent  │    │   Local Tools    │   │   MCP Client     │
│ └ RagAgent│    │ ├ TodoTool       │   │  ──SSE──► Email  │
│           │    │ ├ WebSearchTool  │   │   MCP Server     │
│ Research- │    │ ├ FileOpTool     │   │     :8081        │
│ Write-    │    │ ├ MemoryTool     │   │  sendEmail       │
│ Review    │    │ ├ PlanTool       │   │  sendEmailBatch  │
│ Workflow  │    │ ├ TerminalTool   │   └──────────────────┘
│  ├Research│    │ ├ CreateAgentTool│
│  ├Analyst │    │ ├ PaperAnalysis  │
│  ├ Writer │    │ └ DocumentWrite  │
│  └Reviewer│    └────────┬─────────┘
      │                   │ SSE 进度
      │                   ▼
      │          GET /api/chat/{id}/stream
      │ Milvus 向量 + BM25 关键词 混合检索
      ▼
┌──────────────────────────────────────────────┐
│ QueryExpansion        多路查询扩展           │
│ DocumentRetrieval     向量 + BM25 → RRF 融合 │
│ DocumentPostRetrieval 跨查询去重 + LLM 精排  │
│ RagHook               注入 RAG system prompt │
└──────────────────────────────────────────────┘
```

## 核心功能

### 1. 多智能体协作与计划模式

- **主 Agent（main-agent）**：基于 Spring AI Alibaba ReactAgent 实现统一入口和任务路由。`rag-agent` 通过 `AgentTool.create(...)` 包装为 `ToolCallback` 直接挂载；**调研四件套（research / analyst / writer / reviewer）不再逐个暴露**——它们是 `ResearchWriteReviewWorkflow` 的阶段，单独暴露会让主 Agent 跳过审查闸门或乱序执行。主 Agent 只调用 `researchWriteReview` 这一个入口。
- **PlanTool 计划模式（工具门控）**：复杂任务先进入只读计划阶段。`PlanModeToolInterceptor` 按三级策略动态裁剪模型可见的工具集：
  1. `planEnabled=false`（用户未开启开关）→ 隐藏 `enterPlanMode`/`exitPlanMode`，模型无法进入计划模式；
  2. `planEnabled=true`、`planActive=false` → 全部工具可用；
  3. `planActive=true`（已进入计划模式）→ 仅保留只读工具 + `PlanTool`，隐藏 `writeFile`/`deleteFile`/`executeCommand`/`sendEmail`/`sendEmailBatch`。
- **计划模式提示词**：`PromptSubmitHook` 在 `planEnabled=true` 时向 system message 注入"Plan Mode Active"约束说明；计划正文由 Agent 写入 `plans/{threadId}.md`（该路径由 `ContextCompactor` 识别为需保留的上下文附件）。
- **计划生命周期**：
  ```
  用户开启 Plan Mode → main-agent → PlanTool.enterPlanMode
    → 只读分析 + 写 plans/{threadId}.md → 向用户展示计划
    → PlanTool.exitPlanMode(approved)
        ├─ approved=true  → 退出计划模式，调用 todoWrite 拆解为 Todo 任务执行
        └─ approved=false → 留在计划模式，按反馈修订后重新请求审批
  ```
- **ResearchWriteReviewWorkflow**：调研文档的**带审查闭环**流水线，作为单一 Tool 暴露给主 Agent。由 Java 代码驱动（而非框架的 `SequentialAgent`/`LoopAgent`），因为修订回退需要**按问题归属路由**——框架的 `ConditionLoopStrategy` 谓词只能看到消息列表，无法表达"这条问题该回退到检索、精读还是写作"。

  ```
  research-agent 检索并下载
        ↓
  analyst-agent 逐篇精读 → analysis/{论文短名}.md   （由工作流在 writer 运行前经 analyzePapers 的 Java 路径触发，并行）
        ↓
  analyst-agent 截取该篇的嵌入图 → analysis/figures/{论文短名}_p{页}_{序}.png
        ↓
  writer-agent 撰写（只做归纳与对比）→ document/{文档名}.md
        ↓
  reviewer-agent 审查两份产出 → {approved, issues[].target}
        ↓
    approved=true → 交付
    approved=false → 含 RESEARCHER 级问题 → 重跑 research（补检/补下载）→ 再跑 writer
                     含 ANALYST 级问题   → 先 reanalyzePaper 重做那几篇精读 → 再重写文档
                     全为 WRITER 级问题   → 只重跑 writer
        ↓
    最多 2 轮，仍未通过则如实上报遗留问题
  ```

  审查输出无法解析时**按未通过处理**并追加一条说明，绝不静默放行——放行一份未核验的文档等于让审查环节形同虚设。

  **两个不显眼但致命的实现细节**（都是实测踩出来的）：

  1. **子 Agent 的产出必须从 `messages` 里取，不能读 `state.value("output")`。** 框架的 `AgentLlmNode` 只在配置了 `outputKey` 时才写该键，否则只写 `messages`。主 Agent 配了 `outputKey("output")`，四个子 Agent 没有——照抄主 Agent 的读法会让每个阶段都拿到空字符串，整条流水线在空输入上空转，且不报错。
  2. **审查读的是磁盘上的文档，不是 writer 的回复。** writer 的正文是 `writeResearchDocument` 的**工具参数**，它的最终回复只是一句"已保存"。若把回复当作文档交给 reviewer，reviewer 面对的是空的审查对象，只能一律判 REVISE，闸门形同虚设。因此工作流自己按固定路径从磁盘读回正文；writer 若改了文件名，则回退到它自报的路径，报告里输出的始终是**实际被审查的那个路径**。

  文档名由工作流固定（由课题方向推导）并写进 writer 的输入。实测 writer 会在修订轮次改名（一轮写 `...-survey.md`、下一轮写 `Training-free-....md`），导致 reviewer 反复审查同一份未修订的旧稿、循环永不收敛。

  进度通过 SSE 实时推送（见下节）。
- **CreateAgentTool**：动态创建子 Agent 执行独立任务。

> **注意**：早期的独立计划子系统（`PlanController` / `PlanService` / `PlanStepHook` / `plan_task` / `plan_step` 相关实体与 Mapper）及其前端页面（`PlanView.vue`、`/plans` 路由、`/api/plans/*` 调用）已全部移除。现在"计划"完全通过对话完成：Agent 在计划模式下与用户商量并写出计划，用户审核后由 `exitPlanMode` 退出并拆分为 Todo 任务；前端通过 Todo 接口展示任务进展。

### 2. 四层上下文压缩系统

压缩入口是 `CompactHook`：注册在 `BEFORE_AGENT` 位置，`getOrder() = 10000`，因此在所有其他 BEFORE_AGENT hook（默认 order = 0）**之后**执行，token 估算覆盖即将送入模型的最终消息列表。

每次 agent 调用（即每次用户消息）触发一次。框架的工具循环（`_AGENT_TOOL_ → _AGENT_MODEL_`）不经过 BEFORE_AGENT，所以压缩不会在工具循环中重复触发。

**动态阈值**（`CompactThresholds`，由 `agent.compact` 配置派生）：

```
usableBudget   = modelContextWindowTokens − outputReserveTokens − systemPromptReserveTokens
               = 128000 − 8000 − 12000 = 108000

warningTokens  = floor(usableBudget × warningRatio)  = floor(108000 × 0.70) = 75600   → 触发 L1–L3
criticalTokens = floor(usableBudget × criticalRatio) = floor(108000 × 0.90) = 97200   → 触发 L4
```

预留量保证即使达到 critical 阈值，仍有空间容纳模型输出、系统提示词、工具定义与注入的上下文；warning 与 critical 之间的 10% 差值用于吸收 L1–L3 的压缩收益。配置非法时（`warningRatio >= criticalRatio` 或窗口不足）回退到默认比例 / 保守常量。

**决策流程**：

```
preTokens < warningTokens(75.6K)  → 原样返回
        │
        ▼
L1 ToolResultTruncator   单条工具结果 > 10K tokens → 落盘 + 500 tokens 预览
L2 ContextSnip           消息数 > 60 时移除高置信度低价值消息
L3 MicroCompact          保留最近 5 个工具事务，替换更早事务的结果 payload
        │
        ▼
afterL3 < criticalTokens(97.2K)  → 返回 L1–L3 结果
        │
        ▼
冷却检查：距上次全量压缩 < cooldownCalls(5) 次调用 → 跳过 L4，保留 L1–L3 结果
        │
        ▼
L4 ContextCompactor      同步 LLM 摘要 + 上下文恢复 → ReplaceAllWith 写回图状态
```

| 层 | 实现类 | 行为 | 保护机制 |
|---|---|---|---|
| L1 | `ToolResultTruncator` | 单条结果 > 10K tokens：全文落盘 `.compact/tool_results/{threadId}/`，上下文内替换为 500 tokens 预览 + 文件指针 | 目录总量 > 512MB 时按最旧文件修剪 |
| L2 | `ContextSnip` | ≥ 60 条消息才激活；移除连续重复 user 消息、瞬时错误（429 / timeout / 502-504 …）、低价值填充语 | 工具调用与工具结果**绝对保护**；最近 20 条消息保护 |
| L3 | `MicroCompact` | 保留最近 5 个工具事务，更早事务仅替换**结果 payload**（保留 assistant 调用与 call id） | `ToolCallIntegrity` 保证不切断事务 |
| L4 | `ContextCompactor` | 前缀交 LLM 总结（> 30K 字符自动分块 + 合并），保留安全后缀 | 边界回退到工具事务起点，绝不切断调用/结果配对 |

**L4 产物**（写回图状态 → 随 checkpoint 持久化 → 供后续请求复用）：

```
[COMPACT BOUNDARY   SystemMessage]           压缩边界标记
[Context Compaction Summary UserMessage]     LLM 摘要（≤ 3000 tokens）
[Post-compact recovered SystemMessage ×N]    最近访问的 ≤5 个文件（每文件 ≤5K tokens，总预算 50K）
                                             + 当前计划文件内容（plans/{threadId}.md）
... 保留的最近 20 条原始消息
```

设计要点：**摘要负责"发生过什么"，附件负责"继续工作需要的上下文"**。同时归档快照到 `.compact/snapshots/{threadId}/`（每线程保留最近 5 个）。

Token 估算由 `TokenEstimator` 抽象，默认 `BpeTokenEstimator`（jtokkit O200K BPE），失败时回退 `HeuristicTokenEstimator`；`TokenBudgets` 提供预算内二分截断。

用户记忆 `.memory/MEMORY.md` **不作为压缩附件注入** —— `PromptSubmitHook` 每次模型调用都会把最新记忆写入 system message，重复注入只会造成冗余。

**正确性保证**：hook 返回的 `messages` 一律包装为 `ReplaceAllWith.of(...)`。框架对 `messages` 键的默认策略是 `AppendStrategy`，裸列表会被**追加**到历史之后，导致每轮上下文翻倍。

### 3. Human-In-The-Loop 高危操作审批

**需要审批的工具**（配置在 `AgentConfig`）：

| 工具 | 审批原因 |
|------|---------|
| `writeFile` | 文件写入 |
| `deleteFile` | 文件删除 |
| `executeCommand` | 终端命令执行 |
| `sendEmail` | 邮件发送（审批前可编辑收件人/主题/正文） |
| `sendEmailBatch` | 批量邮件发送（审批前可编辑参数） |

**两阶段交互流程**：

```
阶段 1: POST /api/chat/{threadId}
  → Agent 执行到高危操作
  → 返回 { type: "INTERRUPTED", pendingApprovals: [...] }
  → 前端渲染审批面板 [同意] [拒绝] [编辑]

阶段 2: POST /api/chat/{threadId}/approve
  → 提交 decisions: [{toolId, result, editedArguments?}]
  → 后端 HITLHelper.approveOneByOne() 逐条应用决策
  → RunnableConfig.addHumanFeedback() 注入 → Agent 恢复执行
  → 链式中断自动处理（多个高危操作可连续触发审批）
```

`result` 可选值：`APPROVED` / `REJECTED` / `EDITED`。`EDITED` 时必填 `editedArguments` 提供修改后的工具参数。

中断现场由 `PendingInterruptionStore` 以 `threadId` 为键暂存（含原始 `RunnableConfig` 与输入消息），恢复时据此重建 `RunnableConfig`。核心类：[SuperAssistant.java](../app/src/main/java/com/itajay/superassistant/app/SuperAssistant.java) · [HITLHelper.java](../app/src/main/java/com/itajay/superassistant/security/HITLHelper.java) · [ApprovalDecision.java](../app/src/main/java/com/itajay/superassistant/security/ApprovalDecision.java)

### 4. RAG 检索增强生成

完整管线（实现在 [RagHook.java](../app/src/main/java/com/itajay/superassistant/rag/RagHook.java)，作用于 `rag-agent` 的 BEFORE_AGENT）：

```
用户输入 → QueryExpansion        多路查询扩展（保留原始语义）
         → DocumentRetrieval     每路：Milvus 向量检索 + BM25 关键词检索 → RRF 融合
         → DocumentPostRetrieval 跨查询去重（DocumentFusion）→ LLM 精排（LlmDocumentReranker）
         → RagHook               检索结果注入 RAG system prompt
```

- 支持 PDF / Markdown / TXT / Java / Python / XML / JSON 等多格式文档导入
- TokenTextSplitter：chunkSize=800，maxChunks=500
- API：`POST /knowledge/upload`（multipart file）
- `RagHook` 通过 `ReplaceAllWith` 覆盖 `rag-agent` 的状态消息；历史上下文由 main-agent 持有，`rag-agent` 无独立 checkpoint
- BM25 倒排索引持久化于 `rag.retrieval.bm25.index-path`（默认 `./data/bm25-index`）
- `QueryTransformation`（查询压缩改写）已实现但未接入管线

### 5. 持久记忆体系

MemoryTool 提供 Agent 可调用的记忆管理能力，存储于 `.memory/` 目录（索引 `.memory/MEMORY.md`，条目 `.memory/facts/`）：

| 操作 | 说明 |
|------|------|
| `remember` | 记录用户偏好/项目/事件/知识/联系人，按重要性 1-10 分级，自动去重更新 |
| `recall` | 按关键词搜索记忆，按重要性排序返回 |
| `listMemories` | 按类型分组列出全部记忆及容量使用情况 |
| `deleteFact` | 按 ID 删除指定记忆 |
| `consolidateMemories` | 清理低重要性（<4）记忆并重新编号，保持 ≤ 50 条预算 |

记忆档案由 `PromptSubmitHook` 在**每次模型调用前**注入 system message（非 messages 列表，上限 3000 字符），因此记忆更新下一轮即生效，且不会写入 checkpoint 或前端历史。

### 6. 对话持久化

三层职责分离，互不干扰：

| 存储 | 内容 | 用途 |
|---|---|---|
| MysqlSaver checkpoint（`GRAPH_THREAD` / `GRAPH_CHECKPOINT`） | 完整 messages：含工具调用/工具结果/压缩产物 | 图状态恢复的**唯一来源**，支持跨请求续接 |
| `custom_chat_memory` 表 | 用户消息 + 有文本的 assistant 回答（纯工具调用消息跳过） | 前端渲染 fallback，由 `CustomJdbcChatMemoryRepository` 管理 |
| 前端 localStorage | `{role, content}` | 当前前端实际渲染源 |

- `ModelMessagePersistenceHook`（AFTER_MODEL）在每次模型调用后持久化最新一条**有文本**的 assistant 消息；工具调用/结果属于 checkpoint 状态，不进入渲染表。
- 压缩产物（边界标记 / 摘要 / 恢复附件）随 `messages` 进入 checkpoint，后续请求可直接复用；前端渲染历史时可按需过滤这些内部标记。
- `CheckpointRetentionService`（`@EnableScheduling`）按 `agent.checkpoint-retention` 定时清理过期 checkpoint。

### 7. Tools 工具集

**已挂载到 main-agent**（`methodTools`）：

| 工具 | 能力 |
|---|---|
| **TodoTool** | `todoWrite(objective)` LLM 拆解并持久化；`createTask` / `startTask` / `completeTask` / `updateTodo` / `deleteTodo` / `getReadyTasks` / `queryTodos`。所有操作绑定当前会话 `threadId`（`createTask`、`queryTodos` 从 `ToolContext` 解析，不由模型传入） |
| **WebSearchTool** | DuckDuckGo HTML 搜索（无需 API Key），解析 top 8 结果；`webCrawl(url)` Jsoup 抓取全文，> 8K 字符自动截断 |
| **FileOperationTool** | `readFile` / `writeFile` / `createFile` / `deleteFile` / `listFiles`，路径限制在 workspace 内 |
| **MemoryTool** | `remember` / `recall` / `listMemories` / `deleteFact` / `consolidateMemories` |
| **PlanTool** | `enterPlanMode` / `exitPlanMode` |
| **TerminalTool** | `executeCommand`（纳入 HITL 审批） |
| **CreateAgentTool** | 动态创建子 Agent 执行独立任务 |
| **SkillResourceTool** | `readSkillResource`，只读读取 skill 的参考文件（路径限定在 skills 目录内），供子 Agent 加载参考指南 |
| **PaperDownloadTool** | `downloadPaper` / `listDownloadedPapers`，论文 PDF 下载（重试、校验、按文件名去重）。**仅 `research-agent` 使用** |
| **PaperTextTool** | `extractPaperText` / `listDownloadedPapers`，读取已下载 PDF 的正文（支持分页、超长截断）。**`analyst-agent` 与 `reviewer-agent` 使用** |
| **PaperFigureTool** | `extractPaperFigures`，取出 PDF 里**原生嵌入的图片**并落盘为 PNG，**同时返回两种相对路径**（写进 analysis 文件的 `figures/…` 与写进文档的 `../analysis/figures/…`）。**仅 `analyst-agent` 使用** |
| **DocumentWriteTool** | `writeResearchDocument`，把调研文档写入 `investigation/{课题方向}/document/`（仅限该目录、仅 Markdown） |
| **AnalysisWriteTool** | `writePaperAnalysis`，把单篇论文的四维信息写入 `investigation/{课题方向}/analysis/`（仅限该目录、仅 Markdown），文件头由工具生成 |
| **AnalysisReadTool** | `readPaperAnalysis`，按论文短名读回一篇精读结果。**`writer-agent` 与 `reviewer-agent` 共用** |
| **PaperAnalysisTool** | 批量路径 `analyzePapers(topic)`：枚举该课题已下载论文、跳过已有精读结果的、**并行**调用 `analyst-agent`（路数 `agent.paper.analysis-parallelism`，默认 4），**阻塞直到整批完成**。工作流在每轮 writer 运行前经 `analyzeTopicSync` 直接驱动它（Java 等待，模型零轮询），最终索引（以「论文精读完成：」开头）随 writer 输入交付，每篇完成时推送 SSE 进度；`analyzePapers(topic, 短名)` 同步只分析一篇（失败篇目的单篇重试入口，writer 使用）；`reanalyzePaper(topic, 短名, reason)` 按审查意见强制重做一篇 |
| **ResearchWriteReviewWorkflow** | 研究 → 精读 → 写作 → 审查的闭环流水线（含按 target 三分路由的修订回退） |

**子 Agent 工具**（`tools`）：仅 `rag-agent`。`research-agent` / `analyst-agent` / `writer-agent` / `reviewer-agent` 是 `ResearchWriteReviewWorkflow` 的阶段，不单独挂载。

**为什么把论文工具拆得这么细**：沿用本项目"窄工具"的惯例（`SkillResourceTool` 只读、`DocumentWriteTool` 只写文档）。子 Agent 的工具调用不经过主 Agent 的 HITL 审批，因此职责边界必须由**工具能力**强制，而不是靠提示词约束：

| Agent | 拥有 | 没有 | 效果 |
|---|---|---|---|
| `research-agent` | `downloadPaper`、`listDownloadedPapers` | 任何读正文的工具 | 它无从精读，交付物只能是"候选池 + 判定表 + 下载清单" |
| `analyst-agent` | `extractPaperText`、`writePaperAnalysis`、`extractPaperFigures` | 下载工具、文档写入 | 一次只拿到**一篇**论文，只能写出一份精读结果与该篇的图 |
| `writer-agent` | `analyzePapers`、`readPaperAnalysis`、`writeResearchDocument` | **任何读 PDF 的工具** | 精读由工作流完成后随输入交付索引，它只按需读回结果、对失败篇目单篇重试、成文，翻不了原文也改不了精读结果 |
| `reviewer-agent` | `extractPaperText`、`readPaperAnalysis`、`SkillResourceTool` | 任何写入工具 | 能读原文逐字核对公式，但改不了任何东西 |

`writer-agent` **刻意没有 `extractPaperText`，也刻意没有 `extractPaperFigures`**：四维信息一旦重新流进 writer 的上下文，就等于回到了"文档体量 = 论文数 × 四维全文"的老路，而这正是本次重构要拆掉的截断成因；图片同理——它只**逐字复制** analysis 文件里 analyst 已经嵌好的 `> 文档引用：` 路径，自己不解图、不挑图。

**图片的来源只有一条链**：`extractPaperFigures` 写在 PDF 里读到什么图就给什么图，并把两种相对路径都拼好（`figures/…` 给 analysis 文件，`../analysis/figures/…` 给文档）；analysis 文件里每张图下面带一行 `> 文档引用：<路径>`，writer 逐字复制它。**任何 agent 都不许自己拼图片路径**——这曾经是"产出中不内嵌图片"的原因（模型拼相对路径必错），现在改成了让工具给路径：断链从"必然"变成了"可判定的错误"，reviewer 手里有工作流附上的**图片清单**，可以纯字符串判定（`review-guide.md` P2/P3）。

`reviewer-agent` 之所以同时需要 `extractPaperText` 与 `readPaperAnalysis`：公式现在承载在 analysis 文件里，审查时必须把 analysis 与**论文原文**逐字符并排比对；它也需要读原文来判断 `FULL_TEXT` 的来源等级标注是否属实。

**MCP 工具**（独立 Email Server :8081）：`sendEmail` / `sendEmailBatch`（纳入 HITL 审批）

**已实现但未挂载**：`DateTimeTool`（`getCurrentDateTime` / `dateDifference` / `dateAdd`）、`WeatherTool`（`getWeather`）。两者均为 `@Component`，如需启用在 `AgentConfig` 的 `methodTools(...)` 中追加即可。

### 8. Agent Skills

[SkillConfig](../app/src/main/java/com/itajay/superassistant/skill/SkillConfig.java) 通过 ClasspathSkillRegistry 加载 skills：

- `research_writing_skill`：科研论文调研与文献综述撰写。自动检索 arXiv / Semantic Scholar / Google Scholar，下载 4-8 篇相关论文，逐篇精读提取方法框架/创新点/训练目标并落盘，按模板生成**对比型**调研文档并经质量审查。

skill 采用渐进式披露（progressive disclosure）拆分：

| 文件 | 加载方式 | 内容 |
|---|---|---|
| `SKILL.md` | 由 `SkillScanner` 自动加载（仅识别该文件名） | 主流程总览：六步流程（含审查回边）、四个角色的职责边界、全局红线 |
| `references/research-guide.md` | `research-agent` 按需读取 | 检索策略、五维相关性评分、下载闸门、**输出契约**（候选池/判定表/下载清单/参考文献） |
| `references/writing-guide.md` | `writer-agent` 按需读取 | **整份都属于 writer**：输入契约与修订轮次、文档骨架、逐篇定位（含"图片路径逐字复制"规则）、对比分析、汇总表、参考文献、语言排版、红线、自检清单 |
| `references/analysis-guide.md` | `analyst-agent` 按需读取 | **整份都属于 analyst**：四维信息定义、公式核对纪律、来源等级限制、**从这一篇的 PDF 截取图片并各自引用**、`writePaperAnalysis` 参数与文件骨架 |
| `references/review-guide.md` | `reviewer-agent` 按需读取 | 九个审查维度（文档结构 / **analysis 完整性** / 事实忠实性 / 公式正确性 / 来源追溯 / 覆盖度 / **分工与体量** / 格式 / 一致性）、BLOCKER/MAJOR/MINOR 分级、判定规则、**target 三分路由口径** |
| `template/`、`example/` | 撰写阶段按需读取 | 结构权威与风格范例（范例仅为风格参考，结构以模板为准） |

**每个角色只读属于自己的一份规程，两份都是整份相关**。此前是 `writing-guide.md` 一份供两个角色共用、让 analyst"重点看 §1"——它每轮都在读一份大半是别人规程的文件，且依赖"模型会照小节号跳读"，实测并不稳。`example/` 下的 `papper*.png` 是**范例文档自己的素材**（与 `example.md` 同目录），实跑文档**不得**引用；实跑引用的图必须来自 `extractPaperFigures` 截取的 `analysis/figures/`，两者不共享任何路径。

四个子 Agent 通过 `SkillResourceTool`（只读、路径限定在 skills 目录内）读取上述参考文件，无需授予通用文件工具，避免绕过主 Agent 的 HITL 审批。

**四个子 Agent 刻意不挂 `SkillsAgentHook`。** 该 Hook 会额外注册一个框架自带的 `read_skill` 工具，它按 skill 的 frontmatter 名（`research-writing`，注意与目录名 `research_writing_skill` 不同）查找，且只返回 `SKILL.md`，够不到 `references/*.md`——正是子 Agent 真正需要的文件。两个命名口径不同、能力重叠的"读 skill"工具并列时，模型会开始猜：实测日志里出现了 `Skill not found: reviewer` / `reviewer_skill` / `reviewer-agent` 三次失败调用。现在每个子 Agent 的指令里直接写死唯一正确的调用
`readSkillResource(skillName="research_writing_skill", relativePath="references/xxx.md")`。主 Agent 仍保留该 Hook，用于技能发现。

### 9. 调研进度实时推送（SSE）

调研工作流耗时可达数分钟（检索 → 下载 → 精读 → 撰写 → 审查 → 修订），若前端静默等待，用户无法区分"仍在检索"和"已经卡死"。

```
GET /api/chat/{threadId}/stream   →  text/event-stream
```

- `ProgressChannelRegistry` 按 threadId 维护一条 `SseEmitter`（超时 30 分钟），`@Scheduled` 每 15s 发注释心跳保活，避免代理或浏览器掐掉空闲连接。
- 阶段枚举：`RESEARCHING` / `WRITING` / `REVIEWING` / `REVISING` / `DONE` / `FAILED`，事件体为 `ProgressEvent{stage, label, round, detail, timestamp}`。
- 推送是**尽力而为**：没有客户端监听时 `publish` 是 no-op，客户端中途断开只是摘除该 emitter——**工作流绝不因无人监听而失败**。
- 连接关闭前会先发一个 `done` 事件。少了它，`EventSource` 会把"正常关闭"当作掉线并自动重连，为一个已经结束的运行重新注册通道。

**为什么 POST 保持阻塞**：`POST /api/chat/{threadId}` 仍是同步阻塞调用（前端把 axios 超时设为 `0`），SSE 走一条**独立并发连接**。改成异步执行会破坏 `PlanContextHolder` 的 `ThreadLocal` 与 HITL 中断/恢复流程——两者都假设整个运行在同一条线程上。

**已知取舍**：SSE 连接随页面销毁，刷新后无法续看进度；但 POST 的返回值仍是权威结果，刷新后可从历史记录看到最终答案。

#### 调研产出目录

论文与文档按**课题方向**归档在项目根目录下：

```
{项目根目录}/investigation/{课题方向}/
├── papers/     # research-agent 下载的论文原件（PaperDownloadTool）
├── analysis/   # analyst-agent 逐篇生成的精读结果（AnalysisWriteTool），一篇一个文件
│   └── figures/  # 同一批论文里截出来的图（PaperFigureTool），{短名}_p{页}_{序}.png
└── document/   # writer-agent 生成的调研文档（DocumentWriteTool）
```

- **课题方向是目录的唯一键，且不区分 thread**：换个对话继续调研同一课题，只要课题方向一致即可复用已下载的论文**与已完成的精读结果**。
- **`analysis/{论文短名}.md` 与 `papers/{论文短名}.pdf` 同名成对**，是给人看的产物：用户可以直接打开任意一篇查看详细分析（四维信息、逐字抄录的公式），不必在长文档里翻找。文档中也用相对链接 `../analysis/{短名}.md` 指向它们。
- **图的短名前缀与 analysis 文件名逐字节同源**（都取自 `AnalysisStore.nameFor`），因此"每张图都对应一份已存在的精读结果"是可机械校验的性质，而不是君子协定。文件名里的 `{序}` 是该页内按 XObject 名字顺序的 1-based 序号，同一 PDF 上稳定，所以重跑同名覆盖、不产生碎片。原始嵌入图**自身不带图号**——第几张对应 Fig. 几必须由读过图注的 analyst 确认。
- 项目根目录由 `WorkspacePaths` 从工作目录向上查找 `.git` 确定，避免 IDE / `spring-boot:run` 从子模块启动时把产出散落到 `app/investigation/`。
- 四个子 Agent 都**没有通用文件能力**：`research-agent` 只能通过 `downloadPaper` 下载，`analyst-agent` 只能读正文并写自己那一篇的精读结果，`writer-agent` 只能触发精读、读回精读结果并写 Markdown 文档，`reviewer-agent` 完全只读；四者都限定在对应课题目录内。
- 论文 PDF 去重**按解析后的文件名**判定，因此**短名就是去重键**——同一篇论文换了短名会被重新下载。
- **精读结果按短名跳过已完成项**：`analyzePapers` 发现某篇已有 analysis 文件就不重做。因此一次中断或失败的运行重跑时，已完成的精读不会白做。
- `investigation/` 已在 `.gitignore` 中忽略。

添加新 skill：在 `resources/skills/{skill-name}/` 下创建 `SKILL.md` + 可选 `references/`、`template/`、`example/` 目录即可。

### 10. 模型/工具兜底与循环防护

配置前缀 `agent.guard`，由 `AgentGuardConfig` 装配：

| 组件 | 位置 | 作用 |
|---|---|---|
| `ModelCallGuardInterceptor` | Model | 模型调用异常重试（指数退避 + 抖动）；判定"瞬时故障"后重试，最终失败返回兜底回答 |
| `ToolRetryInterceptor` | Tool | 工具调用失败重试，`retryOn` 复用 `ModelCallGuardInterceptor.isTransient`，超限后 `RETURN_MESSAGE` 优雅降级 |
| `ToolErrorInterceptor` | Tool | 将工具异常转换为消息返回模型，避免整轮运行失败 |
| `LoopGuardToolInterceptor` | Tool | 拦截重复/超量工具调用，防止死循环 |
| `ModelCallLimitHook` | MODEL hook | 限制单次运行的模型调用次数 |

`MAIN_AGENT_INSTRUCTION` 中同时内置"安全规则"（禁止破坏性命令、提权、数据外泄等）与"健壮性/终止规则"（避免重复调用、及时停止、错误优雅处理）以配合上述防护。

**上表中的 hook/interceptor 只注册在 `mainAgent` 上，`analyst-agent` 是唯一例外。** 其余三个子 Agent 每轮只跑一次，无上限不构成风险；但批量精读会在 Java 里（默认 4 路并行）调用 analyst N 次，每次都是一次**无上限**的运行——一篇结构混乱的 PDF 足以让某一篇反复翻页不收敛，而外层还会继续处理其余论文。因此 `AgentGuardConfig` 额外提供 `paperAnalysisCallLimitHook`（`agent.guard.loop.max-model-calls-per-paper`，默认 8，`.exitBehavior(END)` 终止该篇而非整批），并且批量精读对单篇失败做隔离——**一篇失败不中断整批**，如实记入最终索引的失败列；writer 可用 `analyzePapers(topic, 短名)` 对失败篇目单篇重试。

## 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Java 21 |
| **框架** | Spring Boot 4.1.0 · Spring AI 2.0 · Spring AI Alibaba 2.0.0-M1.1 |
| **LLM** | DeepSeek (deepseek-chat) |
| **Embedding** | DashScope（`EMBEDDING_KEY`） |
| **Agent** | ReactAgent + StateGraph + MysqlSaver（checkpoint 持久化） |
| **RAG** | QueryExpansion · DocumentRetrieval（向量 + BM25 + RRF）· LlmDocumentReranker |
| **向量库** | Milvus 2.4.15 (localhost:9090 → 容器 19530) |
| **数据库** | MySQL 8.0（`superassistant` + `superassistant_rag`） |
| **ORM** | MyBatis Plus |
| **文档解析** | PDF (ParagraphPdfDocumentReader) · Markdown · TXT/Code (TextReader) |
| **Token 估算** | jtokkit O200K BPE（失败时回退启发式估算） |
| **工具集成** | MCP (Model Context Protocol) — Client SSE ↔ Server WebMVC |
| **前端** | Vue 3 + Vite + Vue Router + Lucide Icons |
| **构建** | Maven 多模块 |

## 项目结构

```
DeepResearchAgent/
├── pom.xml                        # 父 POM，依赖管理（app + server 两模块）
├── docker-compose.yml             # MySQL + Milvus(+etcd+minio) 容器编排
├── frontend/                      # Vue 3 前端 :3000
│   ├── vite.config.js
│   └── src/
│       ├── App.vue
│       ├── main.js
│       ├── api/index.js           # axios 封装（chat / todos / knowledge）
│       ├── assets/style.css       # 全局样式与 CSS 变量
│       ├── router/index.js        # 路由：/ /knowledge
│       ├── components/ToastHost.vue
│       ├── utils/{markdown.js,toast.js}
│       └── views/
│           ├── ChatView.vue       # 对话界面（HITL 审批面板 + 计划模式开关 + 任务进度）
│           └── KnowledgeView.vue  # 知识库上传管理
├── app/                           # 主应用模块 :8080
│   ├── pom.xml
│   └── src/main/java/com/itajay/superassistant/
│       ├── SuperAssistantApplication.java  # @MapperScan + @ConfigurationPropertiesScan
│       ├── app/                   # Controller 层
│       │   ├── SuperAssistant.java       # 核心 API：对话 + HITL 审批
│       │   ├── TodoController.java       # Todo REST API
│       │   ├── RagController.java        # 知识库文件上传 (/knowledge/upload)
│       │   └── HealthController.java     # 健康检查 (/api/health)
│       ├── config/                # Spring 配置
│       │   ├── AgentConfig.java          # main-agent 组装 + Hooks/Interceptors 注册 + 系统提示词
│       │   ├── ModelConfig.java          # DeepSeek ChatModel / ChatClient
│       │   ├── McpConfig.java            # MCP Client 自动配置
│       │   ├── SaverConfig.java          # 双数据源 (superassistant + superassistant_rag) + MysqlSaver
│       │   ├── VectorConfig.java         # Milvus 向量库
│       │   ├── AgentGuardConfig.java     # 模型/工具异常兜底
│       │   ├── CompactProperties.java    # agent.compact 配置绑定
│       │   ├── CompactPropertiesConfig.java
│       │   ├── CheckpointRetentionProperties.java
│       │   ├── CheckpointRetentionConfig.java   # @EnableScheduling
│       │   └── ChatMessagePersistenceConfig.java
│       ├── compact/               # 四层上下文压缩系统
│       │   ├── CompactHook.java          # BEFORE_AGENT 入口，order=10000
│       │   ├── CompactThresholds.java    # 动态阈值（窗口 − 预留）× 比例
│       │   ├── CompactConfig.java        # 静态调优常量
│       │   ├── ContextCompactor.java     # L4：同步 LLM 摘要 + 上下文恢复
│       │   ├── ToolResultTruncator.java  # L1：工具结果截断 + 落盘
│       │   ├── ContextSnip.java          # L2：低价值消息裁剪
│       │   ├── MicroCompact.java         # L3：陈旧工具结果 payload 替换
│       │   ├── ToolCallIntegrity.java    # 工具调用/结果事务边界保护
│       │   ├── FileReadState.java        # 文件访问追踪（压缩后恢复用）
│       │   ├── TokenEstimator.java       # Token 估算接口
│       │   ├── BpeTokenEstimator.java    # jtokkit O200K BPE 实现
│       │   ├── HeuristicTokenEstimator.java  # 启发式回退
│       │   └── TokenBudgets.java         # token 预算内二分截断
│       ├── checkpoint/            # checkpoint 生命周期管理
│       │   └── CheckpointRetentionService.java  # 定时清理过期 checkpoint
│       ├── agent/                 # 专业子 Agent
│       │   ├── ResearchAgent.java        # 研究 Agent：搜索 + 相关性判定 + PDF 下载（不精读）
│       │   ├── AnalystAgent.java         # 精读 Agent：逐篇读正文 + 四维提取 + 落盘（一次一篇）
│       │   ├── WriterAgent.java          # 写作 Agent：触发精读 + 归纳对比成文（不碰 PDF）
│       │   └── ReviewerAgent.java        # 审查 Agent：读原文与精读结果核对 + 结构化审批结论
│       ├── plan/                  # 计划模式上下文
│       │   ├── PlanModeContext.java      # 计划模式 启用/激活 状态
│       │   └── PlanContextHolder.java    # ThreadLocal 上下文持有者
│       ├── prompt/                # 动态系统提示词
│       │   └── PromptSubmitHook.java     # ModelInterceptor：注入记忆 + 计划模式提示
│       ├── interceptor/           # 自定义拦截器
│       │   ├── PlanModeToolInterceptor.java   # 计划模式工具门控（三级）
│       │   ├── ModelCallGuardInterceptor.java # 模型调用异常兜底
│       │   └── LoopGuardToolInterceptor.java  # 工具循环防护
│       ├── rag/                   # RAG 检索增强管线
│       │   ├── RagAgent.java             # 子 Agent：知识检索回答
│       │   ├── RagHook.java              # 检索 → 注入 system prompt
│       │   ├── RagService.java           # 文档导入 + 切分 + 入库
│       │   ├── QueryExpansion.java       # 多路查询扩展
│       │   ├── QueryTransformation.java  # 查询压缩改写（当前未接入管线）
│       │   ├── DocumentRetrieval.java    # 向量 + BM25 混合检索
│       │   ├── Bm25Index.java            # BM25 倒排索引
│       │   ├── Bm25DocumentRetriever.java
│       │   ├── DocumentFusion.java       # RRF 融合 + 去重
│       │   ├── DocumentReranker.java     # 精排接口
│       │   ├── LlmDocumentReranker.java  # LLM Listwise 精排
│       │   ├── DocumentPostRetrieval.java# 去重 + 精排编排
│       │   ├── CustomJdbcChatMemoryRepository.java  # 自研对话记忆仓库
│       │   └── ModelMessagePersistenceHook.java     # AFTER_MODEL 回答持久化
│       ├── security/              # HITL 人工审批
│       │   ├── HITLHelper.java            # 审批决策工具（逐一/全部/编辑参数）
│       │   ├── ApprovalDecision.java      # 审批决策 DTO
│       │   ├── PendingApproval.java       # 待审批项 DTO（前端渲染）
│       │   └── PendingInterruptionStore.java  # 中断状态暂存
│       ├── tool/                  # Agent 工具
│       │   ├── TodoTool.java              # 待办 CRUD
│       │   ├── WebSearchTool.java         # DuckDuckGo + Jsoup
│       │   ├── FileOperationTool.java     # 文件读写/创建/删除/列表
│       │   ├── MemoryTool.java            # 持久记忆
│       │   ├── PlanTool.java              # 计划模式入口/出口
│       │   ├── TerminalTool.java          # 终端命令执行（HITL 审批）
│       │   ├── CreateAgentTool.java       # 动态创建子 Agent
│       │   ├── SkillResourceTool.java     # 只读读取 skill 参考文件（子 Agent 用）
│       │   ├── PaperDownloadTool.java     # 论文 PDF 下载（research-agent 用）
│       │   ├── PaperTextTool.java         # 已下载 PDF 正文读取（analyst / reviewer 用）
│       │   ├── PaperFigureTool.java       # 论文嵌入图截取，返回两种相对路径（analyst-agent 用）
│       │   ├── PaperStore.java            # 论文 PDF 的路径解析与清单
│       │   ├── AnalysisWriteTool.java     # 单篇精读结果落盘（analyst-agent 用）
│       │   ├── AnalysisReadTool.java      # 单篇精读结果读回（writer / reviewer 用）
│       │   ├── AnalysisStore.java         # 精读文件的路径解析、清单与文件头解析
│       │   ├── PaperAnalysisTool.java     # analyzePapers / reanalyzePaper（writer-agent 用）
│       │   ├── DocumentWriteTool.java     # 调研文档落盘（writer-agent 用）
│       │   ├── DateTimeTool.java          # 日期时间（未挂载）
│       │   └── WeatherTool.java           # 天气查询（未挂载）
│       ├── workflow/              # Agent 工作流
│       │   ├── ResearchWriteReviewWorkflow.java # 研究→精读→写作→审查闭环（含三分路由的修订）
│       │   └── ReviewResult.java          # 审查结论解析（容错，解析失败按未通过处理）
│       ├── progress/              # 工作流进度推送
│       │   ├── ProgressStage.java         # 阶段枚举 + 中文 label
│       │   ├── ProgressEvent.java         # SSE 事件体
│       │   └── ProgressChannelRegistry.java # 按 threadId 维护 SseEmitter + 心跳
│       ├── service/               # 业务服务层
│       │   ├── TodoService.java
│       │   ├── WebSearchService.java
│       │   ├── FileOperationService.java
│       │   ├── TaskBreakdown.java
│       │   ├── AgentRunLogService.java        # Agent 运行日志（已实现，暂无调用方）
│       │   └── ChatMessagePersistenceService.java  # 回答持久化服务
│       ├── entity/                # MyBatis Plus 实体
│       │   ├── TodoTask.java
│       │   └── AgentRunLog.java
│       ├── mapper/                # MyBatis Mapper
│       │   ├── TodoTaskMapper.java
│       │   └── AgentRunLogMapper.java
│       ├── skill/                 # Agent Skills
│       │   └── SkillConfig.java           # ClasspathSkillRegistry
│       └── resources/
│           ├── application.yml
│           ├── sql/
│           │   ├── todo_task.sql          # todo_task 表
│           │   ├── plan.sql               # plan_task / plan_step / agent_run_log（计划子系统已移除）
│           │   ├── plan_agent_decouple.sql
│           │   └── custom_chat_memory.sql # custom_chat_memory 表
│           └── skills/
│               └── research_writing_skill/
│                   ├── SKILL.md                       # 主流程（自动加载）
│                   ├── references/                    # 子 Agent 参考指南（按需读取）
│                   │   ├── research-guide.md          # 检索/相关性判定/下载/输出契约
│                   │   ├── analysis-guide.md          # 整份给 analyst：四维提取、公式纪律、截图与引用、落盘格式
│                   │   ├── writing-guide.md           # 整份给 writer：输入契约、骨架、逐篇定位、对比写作、红线
│                   │   └── review-guide.md            # 文档与 analysis 双对象检查清单 + 三分路由口径
│                   ├── template/template.md           # 对比型文档结构权威（只定结构与图片写法，不含素材）
│                   └── example/                       # 风格范例（对比型，仅演示粒度与写法）
│                       ├── example.md
│                       └── papper{1..4}_{framework,component}.png  # 范例文档自己引用的图，仅此目录内成立
│
└── server/                        # MCP Email Server :8081
    ├── pom.xml
    └── src/main/java/com/itajay/mcpemail/
        ├── McpEmailServerApplication.java
        └── tool/
            └── EmailMcpTools.java        # sendEmail / sendEmailBatch
```

测试位于 `app/src/test/`：覆盖压缩阈值/估算器/截断器/Snip/MicroCompact/文件状态、`PromptSubmitHook`、checkpoint 保留策略、调研产出的路径规则（`WorkspacePathsTest` / `AnalysisPathTest`）、审查结论的解析与三分路由（`ReviewResultTest`）等。`PaperFigureToolTest` 与 `PaperTextToolTest` 用 PDFBox **构造真 PDF** 作 fixture（真嵌入图、真解码），验证落盘位置、两种相对路径形态、小图过滤、同调用内去重、页码夹取、越界与敌意输入被拒、重跑幂等。其余子 Agent 的"是否真的落盘/读回"需要活的模型，由端到端验证覆盖，不做单测。

## API 参考

### 核心对话

| Method | Path | Request | Response |
|--------|------|---------|----------|
| POST | `/api/chat/{threadId}` | `{"message":"...", "mode":"Default|PlanMode"}` | `{type:"ANSWER", response:"..."}` 或 `{type:"INTERRUPTED", pendingApprovals:[...]}` |
| POST | `/api/chat/{threadId}/approve` | `{"decisions":[{"toolId","result","description?","editedArguments?"}]}` | 同 chat 响应格式 |
| GET | `/api/chat/{threadId}/stream` | — | `text/event-stream`，事件名 `progress`（`ProgressEvent`）/ `done`（运行结束） |

响应中额外包含 `planEnabled` / `planActive` 字段反映计划模式状态。

`stream` 是独立于 POST 的第二条连接，需**在发 POST 之前**打开——工作流可能在毫秒内就推送第一个阶段事件。POST 返回后服务端关闭该流（先发 `done`）。

### 知识库

| Method | Path | Request | Response |
|--------|------|---------|----------|
| POST | `/knowledge/upload` | multipart `file` | `{success:true, chunks:12, filename:"..."}` |

### Todo

任务系统是**线程维度**：每个任务都带 `thread_id`（由 Agent 工具从 `ToolContext` 绑定，不由模型提供），所有查询接口**必须**传 `threadId`，只返回该会话的任务。

| Method | Path | Request | Response |
|--------|------|---------|----------|
| GET | `/api/todos?threadId={id}` | query `threadId`（必填） | 该会话全部任务 `List<TodoTask>`（按 stepNo 排序） |
| GET | `/api/todos/pending?threadId={id}` | query `threadId`（必填） | 该会话 PENDING 任务 |
| GET | `/api/todos/overdue?threadId={id}` | query `threadId`（必填） | 该会话逾期的 PENDING 任务 |
| POST | `/api/todos/query` | `{threadId, status, priority, keyword}`（`threadId` 必填） | 过滤后的任务 |

缺 `threadId` 时接口返回 400 或业务错误（`Missing threadId`）。前端 `ChatView` 使用 `/api/todos?threadId=` 读取当前会话的任务；原独立的 `TodoView` 页面已移除，任务进展直接在对话界面展示。

### 健康检查

| Method | Path | Response |
|--------|------|----------|
| GET | `/api/health` | 健康状态 |

### MCP Email Server

独立进程，端口 8081，通过 Spring AI MCP SSE 自动暴露工具回调给主应用。

## 配置说明

**环境变量**（必须）：

```bash
DEEPSEEK_API_KEY=sk-xxxxxxxx    # DeepSeek API 密钥（对话/摘要/精排）
EMBEDDING_KEY=sk-xxxxxxxx       # DashScope API 密钥（向量化）
SMTP_PASSWORD=xxxxxxxx          # 邮箱 SMTP 密码（MCP Email Server）
# 可选：SMTP_PORT（默认 465）
```

**`app/application.yml` 关键配置**：

```yaml
spring.ai.deepseek.api-key: ${DEEPSEEK_API_KEY}
spring.ai.dashscope.api-key: ${EMBEDDING_KEY}
spring.ai.mcp.client.sse.connections.email-server.url: http://localhost:8081
spring.datasource.url: jdbc:mysql://localhost:3306/superassistant
spring.datasource.username: root
spring.datasource.password: 123456

# RAG 检索与精排
rag:
  retrieval:
    vector: { top-k: 5, similarity-threshold: 0.7 }
    bm25:   { top-k: 10, index-path: ./data/bm25-index }
    fusion: { top-k: 20, rrf-k: 60 }
  rerank:
    enabled: true
    top-k: 5

# 上下文压缩：动态阈值 =（模型窗口 − 输出预留 − 系统/工具预留）× 比例
agent.compact:
  model-context-window-tokens: 128000   # deepseek-chat 上下文窗口
  output-reserve-tokens: 8000           # 为模型输出预留
  system-prompt-reserve-tokens: 12000   # 为系统提示词/工具定义/注入上下文预留
  warning-ratio: 0.70                   # 触发 L1–L3
  critical-ratio: 0.90                  # 触发 L4（须 > warning-ratio）
  cooldown-calls: 5                     # 两次全量压缩间的最少调用次数
  snapshot-keep: 5                      # 每线程保留的压缩快照数

# 模型/工具异常兜底与循环防护
agent.guard:
  model-retry:  { max-attempts: 3, initial-delay-ms: 500, max-delay-ms: 8000, backoff-multiplier: 2.0 }
  tool-retry:   { max-retries: 2,  initial-delay-ms: 300, max-delay-ms: 3000, backoff-factor: 2.0 }
  loop:         { max-model-calls-per-run: 15, max-model-calls-per-thread: 60,
                  max-total-tool-calls: 60, max-identical-calls: 3,
                  max-model-calls-per-paper: 8 }   # analyst-agent 单篇精读的调用上限

# checkpoint 保留策略（定时清理）
agent.checkpoint-retention:
  retention-days: 30
  max-per-thread: 20
  active-grace-hours: 24
  cron: "0 0 3 * * *"
```

**外依赖启动**：

```
Milvus    → localhost:9090
MySQL     → localhost:3306（database: superassistant；另需 superassistant_rag）
```

也可直接用项目根目录的 Docker Compose 启动 MySQL + Milvus（含 etcd / minio 依赖）：

```bash
docker compose up -d
docker compose ps
```

**初始化数据库表**：

```bash
mysql -u root -p superassistant < app/src/main/resources/sql/todo_task.sql
mysql -u root -p superassistant < app/src/main/resources/sql/custom_chat_memory.sql
# plan_task / plan_step / agent_run_log（计划子系统已移除，按需导入）
mysql -u root -p superassistant < app/src/main/resources/sql/plan.sql
mysql -u root -p superassistant < app/src/main/resources/sql/plan_agent_decouple.sql
```

## 快速启动

```bash
# 1. 确保 Milvus + MySQL 已启动（可用 docker compose up -d）

# 2. 启动 MCP Email Server
cd server
mvn spring-boot:run    # → :8081

# 3. 启动主应用
cd app
mvn spring-boot:run    # → :8080

# 4. 前端
cd frontend
npm install && npm run dev   # → :3000

# 5. 测试
curl -X POST http://localhost:8080/api/chat/test-001 \
  -H "Content-Type: application/json" \
  -d '{"message":"帮我搜索最新的大模型新闻"}'
```

## HITL 审批流程（开发参考）

审批触发时，`SuperAssistant.chat()` 返回的数据结构：

```json
{
  "type": "INTERRUPTED",
  "threadId": "test-001",
  "message": "High-risk operations require approval",
  "planEnabled": false,
  "planActive": false,
  "pendingApprovals": [
    {
      "toolId": "call_abc123",
      "toolName": "sendEmail",
      "arguments": "{\"to\":\"a@x.com\",\"subject\":\"hello\",\"body\":\"world\",\"isHtml\":false,\"cc\":\"\"}",
      "description": "邮件发送需要人工审批，发送前可编辑收件人/主题/正文"
    }
  ]
}
```

前端提交审批：

```json
POST /api/chat/test-001/approve
{
  "decisions": [
    {
      "toolId": "call_abc123",
      "result": "EDITED",
      "editedArguments": "{\"to\":\"b@x.com\",\"subject\":\"修改后的标题\",\"body\":\"新正文\",\"isHtml\":true,\"cc\":\"c@x.com\"}"
    }
  ]
}
```

`result` 可选值：`APPROVED` / `REJECTED` / `EDITED`。`REJECTED` 时可选填 `description` 描述拒绝理由；`EDITED` 时必填 `editedArguments`。

## Database

| 表 | 用途 |
|---|------|
| `todo_task` | 待办事项，MyBatis Plus 管理 |
| `custom_chat_memory` | 对话渲染记录，`CustomJdbcChatMemoryRepository` 管理 |
| `GRAPH_THREAD` / `GRAPH_CHECKPOINT` | MysqlSaver 图状态快照，`CheckpointRetentionService` 定时清理 |
| `plan_task` / `plan_step` | 计划子系统遗留表（对应代码已移除，可忽略） |
| `agent_run_log` | Agent 运行日志（`AgentRunLogService` 已实现，暂无调用方） |

## 已知限制

- **文档体量仍随论文数线性增长，因此超大规模调研仍可能触及输出上限。** `writer-agent` 把整篇文档作为 `writeResearchDocument` 的**一个参数**输出，`deepseek-chat` 的 8192 token 输出上限已是上限值，**调大参数解决不了**。
  **本次重构把触发点推远了一个量级**：四维信息改为逐篇落盘到 `analysis/`，文档只做归纳与对比，体量从 `N × 四维全文` 降为 `N × 一句话 + 对比`（实测 2 篇论文的文档从 22,031 字符降到数千字符量级）。现实规模（4-8 篇）下不再逼近上限。
  **但没有消除这个上限**：文档仍与论文数成正比，论文数足够多时同样会截断。届时需要给 `writeResearchDocument` 增加 append / 分段语义（`appendDocumentSection`），尚未实现。
  当前行为是**安全**的：异常被工作流捕获，如实回报失败，不会落盘半篇文档、也不会声称成功。
- **修订轮次上限 2 轮**（`ResearchWriteReviewWorkflow.MAX_ROUNDS`，与 `review-guide.md` 一致）。实测常见情况是第 2 轮已把问题收敛到少量 MINOR 项，但预算耗尽——此时工作流按未通过上报，由主 Agent 如实转告用户。
- 审查期间会把整篇文档、调研材料与 `analysis/` 清单一并注入 reviewer 上下文，两篇论文量级约 2 万字符；更多论文时需关注上下文占用（精读正文由 reviewer 自己按需读取，不预先注入）。
- **精读进度逐篇推送 SSE**：批量精读由工作流在 writer 运行前经 `analyzeTopicSync` 驱动（Java 阻塞等待、内部并行），每篇完成即发布一条 `WRITING` 阶段事件（"精读完成 {短名}（n/N）"）。此前"逐篇进度拿不到 threadId"的限制随"工作流直接驱动"一并消解——模型侧零轮询，进度对人可见。
- **文档里的图只来自 `extractPaperFigures` 的嵌入图，取不到时降级为文字指路。** 早期版本是"产出中一律不内嵌图片"，理由是模型自己拼相对路径必错——这个观察是对的，但结论错了：解法是**禁止拼路径**而不是禁止图片。现在路径由工具给出、由 agent 逐字复制，所以断链成了可判定的错误（reviewer 拿图片清单纯字符串核对）。**范例与实跑因此彻底解耦**：`example/example.md` 引用的是**与自己同目录**的 `papper*.png`（示范图放哪、图注怎么写），实跑文档引用的是 `analysis/figures/{短名}_p{页}_{序}.png`，两者不共享任何路径，实跑**不得**引用 skill 目录下的任何文件。
- **矢量图形与扫描版 PDF 取不到图。** 工具读的是 PDF 里**原生嵌入的图片对象**（`PDResources.getXObjectNames()` + `PDImageXObject.getImage()`），不做整页栅格化。整页渲染可兜底，但会带进整页版面与文字，还需要新的依赖取舍与尺寸策略，本次未做。此时工具返回降级话术，analysis 文件里写「图见原文 Fig. N (p.X)」而不引用图片。
- **JBIG2 / JPEG2000 压缩的嵌入图可能无法解码。** `jbig2-imageio` 在 PDFBox 自己的 pom 里是 test-scope，**不在本应用的运行时 classpath**（PDFBox 3.0.5，经 `spring-ai-pdf-document-reader:2.0.0-M1` 传递引入）。这类图逐张 `catch` 跳过并在返回体里记 `undecodable: N 张无法解码 — p.5 /Im3 (…)`，其余图照常导出。是否需要引入该依赖，等实跑数据再定。
- **`extractPaperFigures` 的图片识别有两条保守过滤**：任一边小于 `min-figure-pixels`（默认 120px）视为图标/装饰跳过；同一次调用内**逐像素 CRC 相同**的图只保留第一张（页眉页脚 logo）。单次调用最多导出 `max-figures`（默认 8）张，达上限时返回体记 `truncated` 并提示用更窄的页码范围重试。代价是：真正的第 9 张之后的图不会被导出，而"哪一张是 Fig. 3"仍需 analyst 按图注确认——嵌入图自身不带图号。

## 注意事项

- `SaverConfig` 配置了两个 DataSource Bean：`dataSource`（`superassistant` 库，`@Primary`）与 `ragDataSource`（`superassistant_rag` 库），后者供 `CustomJdbcChatMemoryRepository` 使用；数据库名均为**小写**。
- **任务系统为线程维度**：`todo_task.thread_id` 是任务的归属键，`step_no` 编号与 `step_key` 去重都按会话计算。Agent 工具（`todoWrite` / `createTask` / `queryTodos` / `getReadyTasks`）一律从 `ToolContext` 的 `threadId` 绑定，**不由模型传入**；REST 查询接口必须带 `threadId`，缺省返回错误。
- `HumanInTheLoopHook` 的恢复依赖 `RunnableConfig.Builder.addHumanFeedback(InterruptionMetadata)`，不要手动调用 `CompiledGraph.updateState()`。
- MCP email 工具名称必须与 `@Tool` 注解暴露的名称一致：`sendEmail` / `sendEmailBatch`。
- **压缩阈值不是常量**：由 `agent.compact` 配置经 `CompactThresholds` 动态派生（默认 75.6K / 97.2K）；旧的 `CONTEXT_WARNING_TOKENS` / `CONTEXT_CRITICAL_TOKENS` 已移除。
- **hook 返回 `messages` 必须包 `ReplaceAllWith`**：框架 `messages` 键默认策略为 `AppendStrategy`，返回裸列表会把历史追加一份。
- **`PromptSubmitHook` 与 `PlanModeToolInterceptor` 均为 `ModelInterceptor`（不是 hook）**：通过 `AgentConfig` 的 `.interceptors(...)` 注册，动态系统提示词 / 工具裁剪只作用于 `ModelRequest`，不进入 `messages`、不写入 checkpoint。
- `PromptSubmitHook` / `PlanModeToolInterceptor` 依赖请求 context 中的 `threadId`（由控制器写入 `RunnableConfig` metadata / `PlanContextHolder`）；缺失时仅跳过对应逻辑。
- `CompactHook` 必须在 `BEFORE_AGENT` hook 中最后执行（`getOrder() = 10000`），以保证 token 估算覆盖最终消息列表。
- `PlanModeToolInterceptor` 在计划模式激活时隐藏 `writeFile`、`deleteFile`、`executeCommand`、`sendEmail`、`sendEmailBatch`；未启用计划模式时隐藏 `enterPlanMode` / `exitPlanMode`。
- `compaction` 的 LRU 缓存（文件访问状态、线程调用计数）上限为 `CompactConfig.FILE_STATE_CACHE_MAX_ENTRIES`（100）。
- 本地运行测试若使用 JDK 25，Mockito 会因 MockMaker 与新版 JDK 不兼容而报错（`AgentRunLogServiceTest` 使用 mock）；建议以 JDK 21 运行测试。

维护者：[itajay](mailto:author@itajay.com)
